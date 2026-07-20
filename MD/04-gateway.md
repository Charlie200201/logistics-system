# 04 — Spring Cloud Gateway API 网关

## 是什么

Gateway 是所有请求的**统一大门**。前端只需要知道 Gateway 的地址，Gateway 根据请求路径把请求转发到不同的微服务。

```
前端 → http://localhost:8080/api/users/login    → Gateway → user-service:8081
前端 → http://localhost:8080/api/products/1     → Gateway → product-service:8082
前端 → http://localhost:8080/api/orders         → Gateway → order-service:8083
```

Gateway 不处理业务，它只做三件事：**路由转发**、**身份验证**、**流量控制**。

## 为什么需要

### 没有网关时

```
前端需要配置所有服务的地址：
  user-service: http://192.168.1.100:8081
  product-service: http://192.168.1.101:8082
  order-service: http://192.168.1.102:8083

前端 CORS 跨域问题：每个服务的域名/端口不同
安全：每个服务都要自己做认证
```

### 有了网关后

```
前端只需要一个地址：http://api.logistics.com
所有请求统一入口 → Gateway 负责转发
CORS 只在 Gateway 解决一次
认证：Gateway 统一校验 Token，通过后再转发
```

## 核心概念

### 架构位置

```
浏览器
  │
  ▼
Nginx (入口，宿主机端口 8080)
  │
  ▼
Gateway (端口 8085)  ← 你就在这
  │
  ├──→ /api/users/**     → user-service:8081
  ├──→ /api/products/**  → product-service:8082
  ├──→ /api/orders/**    → order-service:8083
  ├──→ /api/logistics/** → logistics-service:8084
  └──→ /api/logs/**      → 网关自己的 ES 日志查询
```

### 三个核心功能

| 功能 | 做什么 | 项目中的实现 |
|------|--------|-------------|
| **路由转发** | 根据 URL 把请求发到不同服务 | `application.yml` 中的 routes 配置 |
| **过滤器** | 请求前后做额外处理 | JwtAuthGlobalFilter（认证）、GatewayLogFilter（日志） |
| **限流** | 控制流量 | Sentinel 令牌桶限流 |

## 项目中的代码

### 1. 路由配置

**文件位置**: `gateway-service/src/main/resources/application.yml`

```yaml
server:
  port: 8085                   # 网关端口（Nginx 在 8080，这里用 8085）

spring:
  cloud:
    gateway:
      routes:
        - id: user-service              # 路由名称（随便起）
          uri: lb://user-service        # 目标服务（lb:// 表示从Nacos取地址 + 负载均衡）
          predicates:
            - Path=/api/users/**        # 匹配这个路径的请求 → 转发到 user-service

        - id: product-service
          uri: lb://product-service
          predicates:
            - Path=/api/products/**

        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**

        - id: logistics-service
          uri: lb://logistics-service
          predicates:
            - Path=/api/logistics/**

        - id: log-query
          uri: lb://gateway-service      # 自己调用自己（日志查询接口在网关）
          predicates:
            - Path=/api/logs/**
```

**`lb://` 的含义**：
- `lb` = Load Balance（负载均衡）
- Gateway 去 Nacos 查服务名对应的实例列表，然后负载均衡选一个实例
- 如果目标服务有 3 个实例，请求会均匀分到 3 个实例

### 2. JWT 认证过滤器

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/filter/JwtAuthGlobalFilter.java`

这是网关最重要的组件。每个请求到达网关后，先经过这个过滤器校验 Token：

```java
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    // 白名单：不需要 Token 的路径
    private static final List<String> WHITELIST = List.of(
            "/api/users/login",
            "/api/users/register"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // ① 白名单放行
        if (WHITELIST.contains(path)) {
            return chain.filter(exchange);   // 直接放行，不检查 Token
        }

        // ② 放行 Swagger 文档
        if (path.contains("swagger") || path.contains("doc.html")) {
            return chain.filter(exchange);
        }

        // ③ 从 Header 中取 Token
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "未提供有效的认证Token");
        }

        // ④ 验证 Token
        String token = authHeader.substring(7);  // 去掉 "Bearer " 前缀
        if (!JwtUtils.validateToken(token)) {
            return unauthorized(exchange, "Token无效或已过期");
        }

        // ⑤ 把用户信息传给下游服务
        Long userId = JwtUtils.getUserId(token);
        String username = JwtUtils.getUsername(token);
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-UserId", userId.toString())
                .header("X-Username", username)
                .build();

        // ⑥ 放行
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    // 返回 401 响应
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"" + message + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;  // 优先级：数字越小越先执行
    }
}
```

**过滤器执行顺序**：
```
请求到达 Gateway
    │
    ├→ GatewayLogFilter     (order = -50)  → 记录开始时间
    ├→ JwtAuthGlobalFilter  (order = -100) → 校验 Token
    ├→ Sentinel 限流检查
    └→ 路由转发
```

`order = -100` 比 `order = -50` 先执行（负数越小越优先）。

### 3. 日志记录过滤器

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/filter/GatewayLogFilter.java`

```java
@Component
public class GatewayLogFilter implements GlobalFilter, Ordered {

    private final RestHighLevelClient esClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();   // 记录请求开始时间

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;  // 计算耗时

            // 异步写入 ES，不阻塞用户请求返回
            CompletableFuture.runAsync(() -> {
                Map<String, Object> logEntry = new HashMap<>();
                logEntry.put("requestTime", LocalDateTime.now().toString());
                logEntry.put("path", path);
                logEntry.put("method", method);
                logEntry.put("userId", userId != null ? userId : "anonymous");
                logEntry.put("statusCode", statusCode);
                logEntry.put("duration", duration);

                String indexName = "gateway-logs-" + LocalDate.now().format(fmt);
                esClient.indexAsync(new IndexRequest(indexName).source(logEntry),
                        RequestOptions.DEFAULT, listener);
            });
        });
    }

    @Override
    public int getOrder() {
        return -50;  // 在 JWT 过滤器之后执行
    }
}
```

### 4. Sentinel 限流配置

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/config/SentinelConfig.java`

详见 [06-sentinel.md](./06-sentinel.md)

### 5. 启动类

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/GatewayServiceApplication.java`

```java
@SpringBootApplication(
    scanBasePackages = {"com.logistics.gateway", "com.logistics.common"},
    exclude = {DataSourceAutoConfiguration.class}  // 网关不需要数据库，排除自动配置
)
@EnableDiscoveryClient
public class GatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
```

**为什么排除 DataSourceAutoConfiguration？**
Gateway 不做数据库操作，但没有这个排除的话 Spring 会自动尝试配置数据源，由于没配置数据库连接信息会导致启动失败。

## Gateway vs Nginx

| | Gateway | Nginx |
|------|---------|-------|
| 定位 | 应用层网关（Java） | 网络层反向代理（C） |
| 性能 | 中等 | 极高 |
| 动态路由 | 支持（从 Nacos 动态获取） | 需要手动配置 |
| 过滤器 | 编程方式（写 Java） | 配置方式（写配置） |
| 集成 | 与 Spring Cloud 深度集成 | 独立部署 |

项目中 Gateway 和 Nginx **配合使用**：
- Nginx 在最外层：处理静态资源、做最基础的流量分发
- Gateway 在里面：做 JWT 校验、限流、动态路由

## 验证方法

### 1. 测试路由

```bash
# 通过网关访问 user-service
curl http://localhost:8085/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456","phone":"13800138000"}'

# 通过网关访问 product-service
curl http://localhost:8085/api/products/1 \
  -H "Authorization: Bearer <token>"
```

### 2. 测试认证拦截

```bash
# 不带 Token 访问需要认证的接口
curl http://localhost:8085/api/products/1

# 应返回 401: {"code":401,"message":"未提供有效的认证Token"}
```

### 3. 查看 Nacos 服务列表

确认 `gateway-service` 已注册到 Nacos。

## 常见问题

**Q: Gateway 启动报 "DataSource" 错误？**
A: 检查启动类是否有 `exclude = {DataSourceAutoConfiguration.class}`。

**Q: 路由不生效，请求 404？**
A: 检查 predicates 路径是否正确匹配。确认目标服务已启动并在 Nacos 注册。

**Q: Gateway 和普通 Spring Boot 应用有什么区别？**
A: Gateway 基于 WebFlux（响应式），不要引入 `spring-boot-starter-web`（会冲突）。用 `spring-cloud-starter-gateway`。
