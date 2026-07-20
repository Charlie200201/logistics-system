# 智能物流追踪系统 — 后端技术学习指南

> 写给后端初学者的实战教程，以本项目为例，逐项讲解每个技术是什么、怎么用、为什么这样用。

---

## 目录

1. [概念篇：微服务到底是什么](#1-概念篇微服务到底是什么)
2. [Spring Boot — 一切的基础](#2-spring-boot--一切的基础)
3. [Nacos — 服务注册发现 + 配置中心](#3-nacos--服务注册发现--配置中心)
4. [OpenFeign — 服务之间怎么打电话](#4-openfeign--服务之间怎么打电话)
5. [Spring Cloud Gateway — 统一入口网关](#5-spring-cloud-gateway--统一入口网关)
6. [JWT — 用户登录和身份认证](#6-jwt--用户登录和身份认证)
7. [Sentinel — 限流保护你的服务](#7-sentinel--限流保护你的服务)
8. [Seata — 分布式事务保证数据一致性](#8-seata--分布式事务保证数据一致性)
9. [RabbitMQ — 消息队列异步解耦](#9-rabbitmq--消息队列异步解耦)
10. [Redis — 缓存和分布式锁](#10-redis--缓存和分布式锁)
11. [XXL-JOB — 定时任务调度](#11-xxl-job--定时任务调度)
12. [Elasticsearch — 日志搜索和分析](#12-elasticsearch--日志搜索和分析)
13. [MyBatis-Plus — 数据库操作简化](#13-mybatis-plus--数据库操作简化)
14. [Nginx — 反向代理和负载均衡](#14-nginx--反向代理和负载均衡)
15. [Docker — 容器化部署](#15-docker--容器化部署)
16. [Jenkins — 自动化构建部署](#16-jenkins--自动化构建部署)
17. [Gogs — 自己的 Git 代码仓库](#17-gogs--自己的-git-代码仓库)
18. [实战篇：一个请求的完整旅程](#18-实战篇一个请求的完整旅程)

---

## 1. 概念篇：微服务到底是什么

### 单体架构 vs 微服务架构

**单体架构**（传统做法）：
```
一个巨大的 war 包，包含所有功能
├── 用户模块
├── 商品模块
├── 订单模块
└── 物流模块
```
问题：改一行代码就要重新部署整个应用；某个模块挂了整个系统就崩了。

**微服务架构**（本项目做法）：
```
user-service.war    (8081)   独立部署
product-service.war (8082)   独立部署
order-service.war   (8083)   独立部署
logistics-service.war (8084) 独立部署
gateway-service.war (8085)   独立部署
```
好处：各服务独立开发、独立部署、独立扩容。订单流量大了只扩 order-service 就行。

### 微服务带来的新问题

拆开后产生了新的问题，每个问题对应一个技术解决方案：

| 问题 | 解决方案 |
|------|----------|
| 服务多了，怎么找到对方？ | **Nacos**（注册中心） |
| 服务之间怎么互相调用？ | **OpenFeign**（远程调用） |
| 用户请求发给谁？ | **Gateway**（网关） |
| 怎么控制流量防止崩溃？ | **Sentinel**（限流熔断） |
| 一个操作跨多个服务，怎么保证都成功？ | **Seata**（分布式事务） |
| 服务间需要异步通知怎么办？ | **RabbitMQ**（消息队列） |

---

## 2. Spring Boot — 一切的基础

### 它是什么

Spring Boot 是一个**快速开发框架**，帮你省去大量 XML 配置。你只需要写业务代码。

### 核心概念

**启动类**：每个服务的入口
```java
// user-service/src/main/java/com/logistics/user/UserServiceApplication.java
@SpringBootApplication  // 告诉Spring: 这是启动类
@EnableDiscoveryClient  // 注册到Nacos
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

**配置文件**：application.yml 控制一切
```yaml
server:
  port: 8081          # 这个服务跑在哪个端口

spring:
  datasource:         # 数据库连接信息
    url: jdbc:mysql://localhost:3306/db_user
    username: root
    password: 123456
```

**三层架构**（最重要！每个服务都按这个写）：
```
Controller  →  接收HTTP请求，调用Service
   ↓
Service     →  业务逻辑
   ↓
Mapper      →  操作数据库
```

以 user-service 为例：
```
UserController.java    →  @RestController + @RequestMapping("/api/users")
UserService.java       →  @Service，写业务逻辑
UserMapper.java        →  @Mapper，继承 BaseMapper<User>（MyBatis-Plus 提供）
```

### 注解速查表

| 注解 | 作用 | 用在哪 |
|------|------|--------|
| `@SpringBootApplication` | 标记启动类 | 唯一的 main 类 |
| `@RestController` | 这个类是 HTTP 接口 | Controller |
| `@RequestMapping("/api/users")` | URL 前缀 | Controller |
| `@GetMapping("/{id}")` | 处理 GET 请求 | Controller 方法 |
| `@PostMapping` | 处理 POST 请求 | Controller 方法 |
| `@RequestBody` | 把 JSON 转成 Java 对象 | Controller 方法参数 |
| `@PathVariable` | 从 URL 中取参数 | Controller 方法参数 |
| `@Service` | 标记业务逻辑类 | Service 实现类 |
| `@Autowired` / `@RequiredArgsConstructor` | 自动注入依赖 | 需要用到其他类的地方 |
| `@Mapper` | 标记数据库操作接口 | Mapper 接口 |
| `@Component` | 通用的 Spring Bean | 工具类、过滤器等 |
| `@Configuration` | 配置类 | 创建 Bean 的配置 |

### 实战：跟着一个请求走

```
用户请求: POST http://localhost:8081/api/users/register
                       ↓
UserController.register(@RequestBody Map<String, String> body)
    → 从 body 中取出 username, password, phone
    → 调用 userService.register(username, password, phone)
                       ↓
UserServiceImpl.register()
    → 检查用户名是否已存在
    → MD5 加密密码
    → 调用 this.save(user) — MyBatis-Plus 提供的方法
    → 保存到数据库
                       ↓
返回: {"code":200, "message":"success", "data":{...}}
```

---

## 3. Nacos — 服务注册发现 + 配置中心

### 它是什么

Nacos 就像一个**电话簿**。每个服务启动时把自己的地址（IP+端口）报给 Nacos。当 A 服务需要调用 B 服务时，先去 Nacos 查 B 服务的地址。

### 核心概念

```
服务启动 → 向Nacos注册: "我是user-service，我在192.168.1.5:8081"
服务调用 → 问Nacos: "order-service在哪？" → Nacos告诉它地址
服务下线 → Nacos自动感知并移除
```

### 配置方法

**1. 每个服务的 bootstrap.yml**（最先加载）：
```yaml
spring:
  application:
    name: user-service          # 服务名称，注册到Nacos用的名字
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848   # Nacos地址
      config:
        server-addr: localhost:8848   # 配置中心地址
```

**2. 启动类加注解**：
```java
@EnableDiscoveryClient   // 让这个服务能被Nacos发现
```

### 验证

所有服务启动后，打开 http://localhost:8848/nacos → 服务管理 → 服务列表，应该看到 5 个服务。

### 常见问题

**Q: 为什么要用 Nacos，不能直接写死 IP 吗？**
A: 微服务会动态扩缩容，IP 会变。写死 IP 的话，每次变了都要改代码重新部署。

**Q: 配置中心有什么用？**
A: 把配置文件放到 Nacos，修改配置后不需要重启服务就能生效。比如改个限流阈值。

---

## 4. OpenFeign — 服务之间怎么打电话

### 它是什么

Feign 让你**像调用本地方法一样调用远程服务**。你不用自己写 HTTP 请求代码。

### 工作原理

```
order-service 需要调用 user-service
    │
    ├── 传统做法: 自己写 HttpClient/OkHttp，拼接URL，发请求，解析响应
    │
    └── Feign做法:  定义一个接口，加注解，直接调用
```

### 实战代码

**步骤 1**：定义 Feign 接口
```java
// order-service/feign/UserFeignClient.java
@FeignClient(name = "user-service")     // 告诉Feign: 这个接口调用的是user-service
public interface UserFeignClient {

    @GetMapping("/api/users/{id}")      // 这个方法和user-service的Controller方法的URL一致
    Result<Map<String, Object>> getUserById(@PathVariable("id") Long id);
    //     ↑ 返回类型要和被调用方的返回类型匹配
}
```

**步骤 2**：直接注入使用
```java
@Service
@RequiredArgsConstructor
public class OrderServiceImpl {
    private final UserFeignClient userFeignClient;  // 像用本地Service一样注入

    public Order createOrder(Order order) {
        // 调用远程服务就像调用本地方法
        Result<Map<String, Object>> result = userFeignClient.getUserById(order.getUserId());
        // ...
    }
}
```

**步骤 3**：启动类加注解
```java
@EnableFeignClients(basePackages = "com.logistics.order.feign")  // 扫描Feign接口
```

### 调用链路

```
OrderServiceImpl.createOrder()
    → userFeignClient.getUserById(1L)
        → Feign 从 Nacos 查到 user-service 的地址
        → 发送 HTTP GET 请求到 http://user-service/api/users/1
        → 拿到返回结果
```

### 常见问题

**Q: Feign 调用超时了怎么办？**
A: 在 application.yml 中配置超时时间：
```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 5000
        readTimeout: 5000
```

---

## 5. Spring Cloud Gateway — 统一入口网关

### 它是什么

网关是所有请求的**大门**。它不处理业务，只负责**转发**和**拦截**。

### 为什么需要网关

没有网关时：
```
前端 → http://user-service:8081/api/users/login
前端 → http://product-service:8082/api/products/1
前端 → http://order-service:8083/api/orders/1
```
前端要知道每个服务的地址，很麻烦。

有了网关后：
```
前端 → http://gateway:8085/api/users/login    → 转发到 user-service:8081
前端 → http://gateway:8085/api/products/1    → 转发到 product-service:8082
前端 → http://gateway:8085/api/orders/1      → 转发到 order-service:8083
```
前端只需要知道一个地址：网关地址。

### 路由配置

```yaml
# gateway-service/application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service              # 路由名称（随意）
          uri: lb://user-service        # 目标服务（lb:// 表示从Nacos取地址）
          predicates:
            - Path=/api/users/**        # 匹配这个路径的请求转发到user-service
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
```

### 全局过滤器 — 在网关上做鉴权

```java
// gateway-service/filter/JwtAuthGlobalFilter.java
@Component
public class JwtAuthGlobalFilter implements GlobalFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取请求路径
        String path = request.getURI().getPath();

        // 2. 白名单放行（登录、注册不需要Token）
        if (path.equals("/api/users/login") || path.equals("/api/users/register")) {
            return chain.filter(exchange);  // 放行
        }

        // 3. 检查Token
        String token = request.getHeaders().getFirst("Authorization");
        if (token == null || !JwtUtils.validateToken(token)) {
            return unauthorized(exchange, "未提供有效的认证Token");  // 拦截
        }

        // 4. Token有效，放行
        return chain.filter(exchange);
    }
}
```

**过滤器执行顺序**：
```
请求 → GatewayLogFilter（记录日志，order=-50）
    → JwtAuthGlobalFilter（校验Token，order=-100）
    → Sentinel限流检查
    → 路由转发到目标服务
```

数字越大优先级越高。-50 > -100，所以日志过滤器在校验之前执行。

### 常见问题

**Q: lb:// 是什么意思？**
A: Load Balance（负载均衡）。如果 user-service 启动了 3 个实例，lb:// 会自动轮询选择一个实例。

**Q: 网关为什么不能处理业务？**
A: 网关是流量入口，需要高性能。如果网关处理业务逻辑，会成为瓶颈。

---

## 6. JWT — 用户登录和身份认证

### 它是什么

JWT（JSON Web Token）是一种**无状态的认证方式**。服务端不保存登录状态，而是通过签名来验证。

### 为什么用 JWT 而不是 Session

```
Session 方式:
  用户登录 → 服务器创建 Session → 返回 SessionID → 存在服务器内存中
  问题: 多台服务器之间 Session 不共享

JWT 方式:
  用户登录 → 服务器生成 Token（包含用户信息+签名） → 返回给客户端
  后续请求 → 客户端带 Token → 服务器验证签名即可
  优势: 无状态，不占服务器内存，适合分布式
```

### Token 的生成和验证

**生成 Token**：
```java
// JwtUtils.java
public static String generateToken(Long userId, String username) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("userId", userId);
    claims.put("username", username);
    return Jwts.builder()
            .setClaims(claims)                        // 存储用户信息
            .setIssuedAt(new Date())                  // 签发时间
            .setExpiration(new Date(now + 24小时))    // 过期时间
            .signWith(SignatureAlgorithm.HS256, SECRET) // 用密钥签名
            .compact();
}
```

**验证 Token**：
```java
public static boolean validateToken(String token) {
    try {
        Jwts.parser().setSigningKey(SECRET).parseClaimsJws(token);
        return true;   // 签名正确且未过期
    } catch (Exception e) {
        return false;  // 签名不对或已过期
    }
}
```

### 完整登录流程

```
1. 用户注册:
   POST /api/users/register {username, password, phone}
   → 密码 MD5 加密存储

2. 用户登录:
   POST /api/users/login {username, password}
   → 验证密码 → 生成JWT Token → 返回 {"token": "eyJ..."}

3. 后续请求:
   GET /api/orders/1
   Header: Authorization: Bearer eyJ...
   → Gateway 拦截 → 解析验证 Token → 提取 userId → 放行
```

### 安全要点

- **密钥（SECRET）要保密**，生产环境不能硬编码在代码里
- **密码要加密存储**，本项目用 MD5（生产环境建议用 BCrypt）
- **Token 有过期时间**，本项目 24 小时

---

## 7. Sentinel — 限流保护你的服务

### 它是什么

Sentinel 像一个**水龙头开关**。流量太大时自动限流，保护服务不被打垮。

### 为什么需要限流

```
正常情况: 每秒 100 个请求 → 服务正常处理
异常情况: 每秒 10000 个请求 → 数据库被打爆 → 整个系统崩溃

限流: 每秒最多放行 50 个请求，多余的快速失败
```

### 令牌桶模式（本项目使用）

```
┌─────────────┐
│  令牌桶      │  ← 每秒放入 50 个令牌（count=50）
│  容量: 100   │  ← 最多存 100 个令牌（burst=100）
│  当前: 80    │
└──────┬──────┘
       │
       ▼
  请求来了 → 有令牌就取走一个 → 放行
  请求来了 → 没令牌了 → 等待500ms → 还没等到 → 返回429
```

为什么用令牌桶而不是固定 QPS？
- 固定 QPS：每秒只能处理 50 个，突发流量全被拒
- 令牌桶：平时可以攒令牌，突发流量可以用积攒的令牌处理（最多 100 个）

### 配置代码

```java
// gateway-service/config/SentinelConfig.java
@PostConstruct
public void initGatewayRules() {
    GatewayFlowRule rule = new GatewayFlowRule("order-api-group")
            .setCount(50)              // 每秒放 50 个令牌
            .setIntervalSec(1)         // 统计间隔 1 秒
            .setBurst(100)             // 桶容量 100
            .setMaxQueueingTimeoutMs(500); // 超时 500ms

    // 降级响应
    GatewayCallbackManager.setBlockHandler((exchange, t) ->
        ServerResponse.status(429)
            .bodyValue("{\"code\": 429, \"message\": \"系统繁忙，请稍后再试\"}")
    );
}
```

### Sentinel 的三个核心能力

| 能力 | 说明 | 场景 |
|------|------|------|
| **流量控制** | 限制 QPS / 并发数 | 防止突发流量 |
| **熔断降级** | 某个服务挂了，快速失败 | 防止级联故障 |
| **系统保护** | 按系统负载限流 | 防止 CPU 打满 |

---

## 8. Seata — 分布式事务保证数据一致性

### 它是什么

Seata 解决**跨服务的数据一致性**问题。当一个操作涉及多个服务时，保证要么都成功，要么都回滚。

### 问题场景

```
创建订单涉及 3 个操作:

① order-service:   写入订单    ✓ 成功
② product-service: 扣减库存    ✓ 成功
③ logistics-service: 创建物流  ✗ 失败了！

结果: 订单创建了，库存扣了，但物流没创建 → 数据不一致！
```

### Seata AT 模式原理

```
Seata 把整个过程包在一个"全局事务"里:

协调器(TC) ── 管理全局事务
    │
    ├── order-service    (TM - 事务发起者)
    │       写入 t_order 表
    │       undo_log 记录: 反向SQL "DELETE FROM t_order WHERE id=?"
    │
    └── product-service  (RM - 资源参与者)
            扣减 t_product 的 stock
            undo_log 记录: 反向SQL "UPDATE t_product SET stock=旧值 WHERE id=?"

如果所有操作成功 → TC 通知所有人提交（删掉 undo_log）
如果任何一步失败 → TC 通知所有人回滚（执行 undo_log 里的反向SQL）
```

### 实战代码

**发起全局事务**：
```java
// order-service/OrderServiceImpl.java
@GlobalTransactional(name = "create-order-tx", timeoutMills = 300000)
// ↑ 替代原来的 @Transactional，Seata 管理分布式事务
public Order createOrder(Order order) {
    // ① 验证用户
    userFeignClient.getUserById(order.getUserId());

    // ② 扣减库存（product-service 执行，Seata 自动管理分支事务）
    productFeignClient.deductStock(order.getProductId(), body);

    // ③ 写入订单（本地操作，也在全局事务中）
    this.save(order);

    // ④ 事务提交后发送MQ消息
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            rabbitTemplate.convertAndSend(...);  // 提交后才发消息
        }
    });
}
```

**参与分支事务**：
```java
// product-service/ProductServiceImpl.java
@Transactional  // 本地事务，Seata 自动关联到全局事务
public boolean deductStock(Long productId, Integer quantity) {
    // ... 扣库存逻辑
    // Seata 自动在 undo_log 表记录回滚信息
}
```

### 关键配置

**undo_log 表**（每个参与分布式事务的数据库都要建）：
```sql
CREATE TABLE IF NOT EXISTS undo_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,       -- 分支事务ID
    xid VARCHAR(100) NOT NULL,       -- 全局事务ID
    rollback_info LONGBLOB NOT NULL, -- 回滚信息（反向SQL）
    ...
);
```

**application.yml**：
```yaml
seata:
  tx-service-group: logistics_tx_group           # 事务组名
  service:
    vgroup-mapping:
      logistics_tx_group: default                # 映射到 default 集群
    grouplist:
      default: localhost:8091                    # Seata Server 地址
```

### 为什么 RabbitMQ 消息要在事务提交后发送？

如果事务回滚了，但消息已经发出去了，物流服务就会为一个不存在的订单创建物流单。所以必须在 `afterCommit` 里发送。

---

## 9. RabbitMQ — 消息队列异步解耦

### 它是什么

消息队列像一个**邮箱系统**。发送方把消息扔进邮箱，不关心谁取走。接收方从邮箱取消息处理。

### 为什么需要消息队列

**同步调用的问题**：
```
order-service 创建订单 → 同步调用 logistics-service 创建物流
如果 logistics-service 挂了 → 订单创建失败 → 用户看到错误
```

**异步解耦后**：
```
order-service 创建订单 → 发消息给 RabbitMQ → 返回成功给用户
logistics-service 从 RabbitMQ 取消息 → 创建物流单
即使 logistics-service 挂了，订单也能创建成功，物流等恢复后再处理
```

### 核心概念

```
交换机(Exchange) ── 路由分发
    │
    ├── 路由键(Routing Key) = "logistics.create"
    │
    ▼
队列(Queue) = "logistics.queue"  ── 消息排队
    │
    ▼
消费者(Consumer) ── 取消息处理
```

### 生产者代码（order-service）

**配置交换机、队列、绑定**：
```java
@Configuration
public class RabbitMQConfig {
    public static final String QUEUE = "logistics.queue";
    public static final String EXCHANGE = "logistics.exchange";
    public static final String ROUTING_KEY = "logistics.create";

    @Bean
    public Queue logisticsQueue() {
        return QueueBuilder.durable(QUEUE).build();  // durable: 持久化，重启不丢
    }

    @Bean
    public DirectExchange logisticsExchange() {
        return new DirectExchange(EXCHANGE);  // 直连交换机
    }

    @Bean
    public Binding binding() {
        return BindingBuilder.bind(logisticsQueue())
                .to(logisticsExchange())
                .with(ROUTING_KEY);  // 用 routing key 绑定
    }
}
```

**发送消息**：
```java
rabbitTemplate.convertAndSend(
    "logistics.exchange",    // 交换机
    "logistics.create",      // 路由键
    jsonMessage              // 消息内容
);
```

### 消费者代码（logistics-service）

```java
@Component
public class LogisticsMessageListener {

    @RabbitListener(queues = "logistics.queue")  // 监听这个队列
    public void handleOrderCreated(String message) {
        // 解析消息
        Map<String, Object> msg = objectMapper.readValue(message, Map.class);
        Long orderId = Long.valueOf(msg.get("orderId").toString());

        // 创建物流单
        logisticsService.createLogistics(logisticsNo, orderId);
    }
}
```

### 交换机类型

| 类型 | 说明 | 路由逻辑 |
|------|------|----------|
| **Direct** | 直连（本项目使用） | 路由键完全匹配 |
| **Topic** | 主题 | 路由键支持通配符 |
| **Fanout** | 广播 | 忽略路由键，发给所有队列 |

### 常见问题

**Q: 消息丢了怎么办？**
A: 队列声明为 `durable`，消息发送时设置持久化。消费端用 `ack` 确认机制。

**Q: 消费者挂了怎么办？**
A: 消息在队列中等待。重连后继续消费。

---

## 10. Redis — 缓存和分布式锁

### 它是什么

Redis 是一个**内存数据库**，读写速度极快（微秒级）。两大用途：**缓存**和**分布式锁**。

### Redis vs MySQL

| | MySQL | Redis |
|------|-------|-------|
| 存储位置 | 硬盘 | 内存 |
| 读速度 | 毫秒级 | 微秒级 |
| 数据持久 | 是 | 可选 |
| 适用场景 | 永久数据 | 热数据/临时数据 |

### 用途一：缓存商品信息

**为什么缓存**：商品详情页访问量最大，每次都查 MySQL 太慢了。

**缓存策略（Cache Aside）**：
```
查询商品:
  ① 先查 Redis
  ② 命中 → 直接返回（快！）
  ③ 未命中 → 查 MySQL → 写入 Redis → 返回

更新商品:
  ① 更新 MySQL
  ② 删除 Redis 中的旧缓存
```

**代码实现**：
```java
public Product getProductById(Long id) {
    String cacheKey = "product:" + id;  // Key 格式: product:1

    // ① 先查缓存
    Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
    if (product != null) {
        return product;  // 命中！直接返回
    }

    // ② 查数据库
    product = this.getById(id);
    if (product != null) {
        // ③ 写入缓存，TTL 30 分钟
        redisTemplate.opsForValue().set(cacheKey, product, 30, TimeUnit.MINUTES);
    }
    return product;
}
```

**为什么设置 TTL（过期时间）**：
- 防止缓存永远不更新
- 防止数据占满 Redis 内存

### 用途二：分布式锁防止超卖

**问题场景**：
```
库存还剩 1 件:
  请求 A: 读到库存=1 → 准备下单
  请求 B: 读到库存=1 → 准备下单
  A 和 B 同时下单成功 → 超卖了！
```

**解决方案：加锁**：
```
  请求 A: 获得锁 → 查库存 → 扣库存 → 释放锁
  请求 B: 尝试获取锁 → 锁被占用 → 等待 → A释放后获取 → 查库存=0 → 失败
```

**Redisson 分布式锁代码**：
```java
public boolean deductStock(Long productId, Integer quantity) {
    String lockKey = "lock:stock:" + productId;
    RLock lock = redissonClient.getLock(lockKey);  // 获取分布式锁

    try {
        // tryLock(等待时间, 锁过期时间, 时间单位)
        if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
            try {
                Product product = this.getById(productId);
                if (product.getStock() < quantity) {
                    throw new BusinessException("库存不足");
                }
                product.setStock(product.getStock() - quantity);
                this.updateById(product);
                return true;
            } finally {
                lock.unlock();  // 必须在 finally 中释放锁！
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    return false;
}
```

**关键参数解释**：
- `tryLock(10, 30, SECONDS)`：
  - 10 秒：最多等 10 秒获取锁
  - 30 秒：锁的自动过期时间（防止死锁）
- **必须在 finally 中 unlock**：防止业务异常导致锁永不释放

### 为什么不用 synchronized？

`synchronized` 只能锁住同一个 JVM 进程内的线程。如果 product-service 启动了 3 个实例，`synchronized` 只能锁住各自实例内的线程，跨实例的并发控制不了。Redis 分布式锁是全局的，所有实例共享同一把锁。

---

## 11. XXL-JOB — 定时任务调度

### 它是什么

XXL-JOB 是一个**定时任务管理平台**。你可以在它的 Web 页面上管理所有定时任务。

### 为什么不用 @Scheduled

```java
@Scheduled(fixedDelay = 30000)  // 硬编码在代码里
public void doTask() { ... }
```

问题：
- 任务多了不好管理（分布在各个服务的代码里）
- 想改执行时间得改代码重新部署
- 没有执行历史和失败告警

XXL-JOB 的好处：
- Web 页面管理所有任务
- 随时修改 Cron 表达式，立即生效
- 查看执行日志和状态
- 支持失败重试和告警

### 架构

```
XXL-JOB Admin (调度中心)     →  Web 管理页面
     │
     │  分配任务
     ▼
XXL-JOB Executor (执行器)    →  嵌入在 logistics-service 中
     │
     │  执行具体逻辑
     ▼
业务方法 (@XxlJob 注解的方法)
```

### 实战

**步骤 1**：配置执行器
```java
@Configuration
public class XxlJobConfig {
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses("http://localhost:8088/xxl-job-admin");
        executor.setAppname("logistics-executor");  // 执行器名称
        executor.setPort(9999);                      // 执行器端口
        return executor;
    }
}
```

**步骤 2**：写任务处理类
```java
@Component
public class OrderTimeoutJob {

    private final OrderFeignClient orderFeignClient;
    private final ProductFeignClient productFeignClient;

    @XxlJob("orderTimeoutCancelJob")  // 任务名称，在 Admin 页面配置
    public void execute() {
        // 1. Feign 调用 order-service: 查询超时30分钟的待支付订单
        Result<List<Map<String, Object>>> result = orderFeignClient.getExpiredOrders(30);

        // 2. 逐个取消订单并恢复库存
        for (Map<String, Object> order : result.getData()) {
            orderFeignClient.cancelOrder(orderId);           // 取消订单
            productFeignClient.restoreStock(productId, qty); // 恢复库存
        }
    }
}
```

**步骤 3**：在 XXL-JOB Admin 页面配置任务

1. 打开 http://localhost:8088/xxl-job-admin
2. 执行器管理 → 确认 `logistics-executor` 已自动注册
3. 任务管理 → 新增任务：
   - JobHandler: `orderTimeoutCancelJob`
   - Cron: `0 */5 * * * ?`（每 5 分钟执行）

### Cron 表达式速查

```
┌── 秒 (0-59)
│ ┌── 分钟 (0-59)
│ │ ┌── 小时 (0-23)
│ │ │ ┌── 日 (1-31)
│ │ │ │ ┌── 月 (1-12)
│ │ │ │ │ ┌── 星期 (0-7, 0和7都是周日)
│ │ │ │ │ │
* * * * * *

0 */5 * * * ?    每5分钟
0 30 0 * * ?     每天00:30
0 0 2 * * ?      每天凌晨2点
0 0 9 * * 1-5    工作日早上9点
```

### 本项目的两个任务

| 任务 | Cron | 做什么 |
|------|------|--------|
| `orderTimeoutCancelJob` | `0 */5 * * * ?` | 扫描超时30分钟的待支付订单，取消并恢复库存 |
| `dailyOrderStatisticsJob` | `0 30 0 * * ?` | 统计前一天订单总数和总金额 |

---

## 12. Elasticsearch — 日志搜索和分析

### 它是什么

Elasticsearch 是一个**搜索引擎**，专门用来做全文搜索和数据分析。比 MySQL 的 LIKE 查询快几个数量级。

### 为什么用 ES 存日志

日志的特点：数据量大、写入频繁、需要全文搜索。MySQL 不适合存日志，因为：
- 大量写入会拖慢 MySQL
- LIKE 查询日志内容会全表扫描，极慢
- 日志数据不需要严格的事务保证

ES 的优势：
- 写入快（近实时）
- 全文搜索快（倒排索引）
- 按时间自动分索引
- 支持聚合分析

### 核心概念对应关系

| MySQL | Elasticsearch | 说明 |
|-------|---------------|------|
| Database | Index（索引） | 数据库 vs 索引 |
| Table | Type（已废弃） | ES 7.x 中一个索引只有一种类型 |
| Row | Document（文档） | 一行数据 vs 一个 JSON 文档 |
| Column | Field（字段） | 列 vs 字段 |

### 实战：网关日志写入

**步骤 1**：创建 ES 客户端
```java
@Configuration
public class ElasticsearchConfig {
    @Bean
    public RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(
            RestClient.builder(new HttpHost("localhost", 9200, "http"))
        );
    }
}
```

**步骤 2**：在 GlobalFilter 中记录日志
```java
@Component
public class GatewayLogFilter implements GlobalFilter {

    private final RestHighLevelClient esClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;

            // 异步写入 ES，不阻塞请求返回
            CompletableFuture.runAsync(() -> {
                Map<String, Object> logEntry = new HashMap<>();
                logEntry.put("requestTime", LocalDateTime.now().toString());
                logEntry.put("path", path);
                logEntry.put("method", method);
                logEntry.put("userId", userId);
                logEntry.put("statusCode", statusCode);
                logEntry.put("duration", duration);

                String indexName = "gateway-logs-" + LocalDate.now(); // 按天分索引
                IndexRequest request = new IndexRequest(indexName)
                        .source(logEntry, XContentType.JSON);

                esClient.indexAsync(request, RequestOptions.DEFAULT, listener);
            });
        });
    }
}
```

**步骤 3**：查询接口
```java
@GetMapping("/api/logs/search")
public Map<String, Object> search(
        @RequestParam String keyword,
        @RequestParam String startTime,
        @RequestParam String endTime) {

    // 构建查询
    BoolQueryBuilder query = QueryBuilders.boolQuery()
            .must(QueryBuilders.multiMatchQuery(keyword, "path", "userId"))
            .must(QueryBuilders.rangeQuery("requestTime").gte(startTime).lte(endTime));

    SearchRequest request = new SearchRequest(indices)
            .source(new SearchSourceBuilder().query(query));

    SearchResponse response = esClient.search(request, RequestOptions.DEFAULT);

    // 解析结果
    for (SearchHit hit : response.getHits().getHits()) {
        results.add(hit.getSourceAsMap());
    }
    return result;
}
```

### 联合查询示例

```bash
# 查询包含 "orders" 关键词的日志
GET /api/logs/search?keyword=orders

# 查询指定时间段内状态码为 500 的日志
GET /api/logs/search?keyword=500&startTime=2026-07-14T00:00:00&endTime=2026-07-14T23:59:59
```

---

## 13. MyBatis-Plus — 数据库操作简化

### 它是什么

MyBatis-Plus 在 MyBatis 的基础上做了增强。你只需要**继承 BaseMapper**，就能获得全套增删改查方法。

### 不使用 vs 使用 MyBatis-Plus

**传统 MyBatis**：要写 XML 映射文件
```xml
<!-- UserMapper.xml -->
<select id="selectById" resultType="User">
    SELECT id, username, password, phone FROM t_user WHERE id = #{id}
</select>
<insert id="insert">
    INSERT INTO t_user (username, password, phone) VALUES (#{username}, #{password}, #{phone})
</insert>
<!-- 每个方法都要写一遍 SQL... -->
```

**MyBatis-Plus**：零 XML
```java
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 什么都不用写！
    // 自动拥有: insert, deleteById, updateById, selectById, selectList, selectPage...
}

// 使用：
User user = userMapper.selectById(1L);          // 根据ID查询
userMapper.insert(user);                        // 插入
userMapper.updateById(user);                    // 更新
userMapper.deleteById(1L);                      // 删除
List<User> users = userMapper.selectList(null); // 查询全部
```

### 条件查询

```java
// Lambda 写法（推荐，类型安全）
LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
wrapper.eq(User::getUsername, "testuser")     // WHERE username = 'testuser'
       .gt(User::getCreatedAt, someDate)       // AND created_at > someDate
       .orderByDesc(User::getCreatedAt);        // ORDER BY created_at DESC
List<User> users = userMapper.selectList(wrapper);
```

### 分页查询

```java
Page<User> page = new Page<>(1, 10);  // 第1页，每页10条
Page<User> result = userMapper.selectPage(page, null);
// result.getRecords()  → 当前页数据
// result.getTotal()    → 总记录数
// result.getPages()    → 总页数
```

### 实体类映射

```java
@Data
@TableName("t_user")  // 指定表名
public class User {
    @TableId(type = IdType.AUTO)    // 主键自增
    private Long id;

    private String username;         // 自动映射 username 列
    private String password;
    private String phone;

    @TableField(fill = FieldFill.INSERT)  // 插入时自动填充
    private LocalDateTime createdAt;
}
```

**命名规则**：Java 驼峰 `createdAt` → MySQL 下划线 `created_at`（`map-underscore-to-camel-case: true`）

---

## 14. Nginx — 反向代理和负载均衡

### 它是什么

Nginx 是一个**高性能的 Web 服务器**，常用来做反向代理。就像一栋大楼的前台，所有访客先到前台，前台再告诉你该去哪个房间。

### 反向代理 vs 正向代理

```
正向代理（你翻墙用的VPN）:  你 → 代理服务器 → 谷歌
反向代理（本项目用Nginx）:  用户 → Nginx → 你的服务
```

### 为什么需要 Nginx

即使有了 Gateway，还需要 Nginx 吗？是的，因为：
- Nginx 更擅长处理静态资源（HTML/CSS/JS/图片）
- Nginx 可以隐藏后端架构细节
- Nginx 可以做更底层的流量控制（IP 黑名单等）

### 本项目配置

```nginx
server {
    listen 80;  # Nginx 监听 80 端口（Docker 中）

    # 静态资源请求 → 直接发给 Gateway 的静态资源
    location /static/ {
        proxy_pass http://host.docker.internal:8085/static/;
    }

    # API 请求 → 发给 Gateway
    location /api/ {
        proxy_pass http://host.docker.internal:8085/api/;
        proxy_set_header Host $host;                          # 传递原始域名
        proxy_set_header X-Real-IP $remote_addr;              # 传递真实IP
        proxy_set_header Authorization $http_authorization;   # 传递Token
    }
}
```

**请求链路**：
```
浏览器 → http://localhost:8080/api/users/login
         (Nginx 容器 80 端口映射到宿主机 8080)
           │
           ▼
         Nginx 匹配 /api/ 规则
           │
           ▼
         代理转发到 host.docker.internal:8085 (Gateway)
           │
           ▼
         Gateway 匹配 /api/users/** → user-service:8081
```

**`host.docker.internal`** 是 Docker Desktop for Windows 提供的特殊域名，让容器能访问宿主机的端口。

---

## 15. Docker — 容器化部署

### 它是什么

Docker 把应用和环境打包成一个**容器**，可以在任何机器上运行。简单理解：一个轻量级的虚拟机。

### 核心概念

```
镜像(Image)   →  程序的打包文件（类比安装包）
容器(Container) →  镜像运行起来的实例（类比安装后的程序）
```

一张镜像可以启动多个容器（就像用一个安装包装多台电脑）。

### 为什么用 Docker

```
没有 Docker 时安装 RabbitMQ:
  1. 下载 Erlang 安装包 → 安装
  2. 配置环境变量
  3. 下载 RabbitMQ 安装包 → 安装
  4. 启动服务
  5. 各种配置...
  （耗时 30 分钟，还可能遇到兼容性问题）

使用 Docker:
  docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3.8-management
  （一行命令，30 秒搞定）
```

### 常用命令

```bash
# 查看运行中的容器
docker ps

# 查看所有容器（包括已停止的）
docker ps -a

# 启动/停止/重启容器
docker start nginx
docker stop nginx
docker restart nginx

# 查看容器日志
docker logs nginx          # 查看日志
docker logs -f nginx       # 实时跟踪日志
docker logs --tail=50 nginx # 查看最后50行

# 进入容器内部
docker exec -it nginx bash

# 复制文件到容器
docker cp nginx.conf nginx:/etc/nginx/nginx.conf

# 删除容器
docker rm nginx

# 查看镜像
docker images

# 删除镜像
docker rmi nginx:latest
```

### Docker Compose（扩展知识）

当容器多了（像本项目有 9 个），单个 `docker run` 就太麻烦了。用 Docker Compose 可以一个命令启动所有容器：

```yaml
# docker-compose.yml（示例）
services:
  nacos:
    image: nacos/nacos-server:v2.1.0-slim
    ports:
      - "8848:8848"

  redis:
    image: redis:latest
    ports:
      - "6379:6379"

  rabbitmq:
    image: rabbitmq:3.8-management
    ports:
      - "5672:5672"
      - "15672:15672"
  # ...更多服务
```

启动：`docker-compose up -d`（一行命令启动全部）

---

## 16. Jenkins — 自动化构建部署

### 它是什么

Jenkins 是一个**CI/CD（持续集成/持续部署）**工具。帮你自动完成：拉代码 → 编译 → 测试 → 打包 → 部署的整个流程。

### 传统部署 vs CI/CD

```
传统部署:
  你写完代码 → 手动 mvn package → 手动 scp 到服务器 → 手动重启服务
  问题: 每次部署都要做一遍，容易出错

CI/CD:
  你 push 代码到 Gogs → Jenkins 自动触发
    → ① 拉取最新代码
    → ② Maven 编译打包
    → ③ 运行测试
    → ④ 构建 Docker 镜像
    → ⑤ 部署到服务器
  全程自动，你只需要 git push
```

### Jenkinsfile 解析

```groovy
pipeline {
    agent any  // 在任何可用的节点上运行

    stages {
        stage('Checkout') {
            steps {
                // ① 从 Gogs 拉取代码
                git url: 'http://localhost:3000/test/logistics-system.git',
                    branch: 'main'
            }
        }

        stage('Build') {
            steps {
                // ② Maven 编译打包
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Test') {
            steps {
                // ③ 运行单元测试
                sh 'mvn test'
            }
        }

        stage('Docker Build') {
            parallel {
                // ④ 并行构建 5 个服务的 Docker 镜像
                stage('user-service')    { sh 'docker build -t logistics-user-service:latest .' }
                stage('product-service') { sh 'docker build -t logistics-product-service:latest .' }
                // ...
            }
        }

        stage('Deploy') {
            steps {
                // ⑤ 部署到 Docker 容器
                sh 'docker run -d --name logistics-user-service -p 8081:8081 logistics-user-service:latest'
                // ...
            }
        }
    }

    post {
        failure {
            echo '流水线执行失败！'  // 失败了发通知
        }
    }
}
```

### CI/CD 流程全景

```
你写完代码
    │
    ▼
git push 到 Gogs
    │
    ▼
Jenkins 感知到变更（或手动触发）
    │
    ├── ① Checkout:   从 Gogs 拉取代码
    ├── ② Build:      mvn clean package
    ├── ③ Test:       mvn test
    ├── ④ Docker Build: docker build 各服务镜像
    └── ⑤ Deploy:     docker run 启动新容器
    │
    ▼
新版本上线！
```

---

## 17. Gogs — 自己的 Git 代码仓库

### 它是什么

Gogs 是一个**轻量级的 Git 服务**，类似 GitHub/GitLab，但是你可以自己部署。占用内存极小（几十 MB），适合学习和小团队使用。

### 为什么用自己的 Git 而不是 GitHub

- GitHub 私有仓库有限制（以前）
- 局域网访问更快
- 学习完整的 DevOps 流程
- 数据在自己机器上

### 基本使用

**初始化仓库**：
```bash
# 进入项目目录
cd D:\work\web-ai-code\logistics-system

# 初始化 Git
git init

# 创建 .gitignore（已生成好）
# 排除 target/、.idea/、*.log 等不需要提交的文件

# 添加所有文件
git add .

# 第一次提交
git commit -m "init: 智能物流追踪系统"

# 关联远程仓库（先在 Gogs 页面上创建仓库）
git remote add origin http://localhost:3000/test/logistics-system.git

# 推送到 Gogs
git push -u origin main
```

### Git 常用命令

```bash
git status                  # 查看有哪些文件修改了
git diff                    # 查看具体改了什么
git add .                   # 暂存所有修改
git commit -m "fix: 修复订单创建bug"  # 提交
git push                    # 推送到远程

git log                     # 查看提交历史
git log --oneline           # 紧凑模式

git branch                  # 查看分支
git checkout -b feature-xxx # 创建并切换到新分支
git checkout main           # 切换回主分支
git merge feature-xxx       # 合并分支

git pull                    # 拉取远程更新
```

### .gitignore 说明

```gitignore
# Maven 编译产物（几十 MB，不需要提交）
target/
*.jar

# IDE 配置文件（每个人IDE不一样，不需要提交）
.idea/
*.iml

# 日志文件
*.log
/logs/

# 系统文件
.DS_Store      # macOS
Thumbs.db      # Windows
```

---

## 18. 实战篇：一个请求的完整旅程

让我们追踪"创建订单"这个请求经过了哪些技术组件：

```
第 0 步: 用户操作
  浏览器打开 http://localhost:8080/static/order.html
  （Nginx 静态资源代理 → Gateway → HTML 页面）

第 1 步: 用户登录
  POST http://localhost:8080/api/users/login
    → Nginx (8080) → Gateway (8085) → user-service (8081)
    → 验证密码 → 生成 JWT Token
    ← 返回 {"token": "eyJ..."}

第 2 步: 创建订单
  POST http://localhost:8080/api/orders
  Header: Authorization: Bearer eyJ...
  Body: {"userId":1, "productId":1, "quantity":2, "address":"北京"}

    │
    ▼ Nginx (8080)
    │ 匹配 /api/ → 代理到 Gateway
    │
    ▼ Gateway (8085)
    │ ├── GatewayLogFilter:      记录请求开始时间
    │ ├── JwtAuthGlobalFilter:   解析Token → 提取 userId=1 → 校验通过
    │ ├── Sentinel 限流检查:      令牌桶有令牌 → 放行
    │ └── 路由转发:               /api/orders/** → order-service
    │
    ▼ order-service (8083)
    │ ├── @GlobalTransactional 开启 Seata 全局事务
    │ │
    │ ├── ① Feign → user-service:   验证用户存在
    │ │     └── Nacos 查 user-service 地址 → 发 HTTP 请求
    │ │
    │ ├── ② Feign → product-service: 查询商品价格
    │ │     └── product-service 先查 Redis 缓存
    │ │         └── 缓存命中！返回价格 5999.00
    │ │
    │ ├── ③ Feign → product-service: 扣减库存
    │ │     └── Redisson 分布式锁 → 扣库存 → Seata undo_log 记录
    │ │
    │ ├── ④ 本地: 写入订单 → status=PENDING_PAYMENT
    │ │     └── Seata undo_log 记录
    │ │
    │ └── Seata TC 判断全部成功 → 提交全局事务
    │     └── 触发 afterCommit 回调
    │         └── ⑤ RabbitMQ: 发送消息到 logistics.exchange
    │
    ▼ 响应返回
      ← Gateway: GatewayLogFilter 记录日志到 ES
      ← Nginx
      ← 浏览器: 显示订单创建成功

第 3 步: 物流服务异步处理
  logistics-service 收到 RabbitMQ 消息
    → 生成物流单号 LOG20260714...
    → 写入数据库
    → @Scheduled 每30秒模拟更新物流位置

第 4 步: XXL-JOB 定时检查
  每5分钟: orderTimeoutCancelJob
    → Feign 查 order-service: 有哪些超时30分钟的待支付订单？
    → 逐个取消 → Feign 调 product-service 恢复库存

  每天00:30: dailyOrderStatisticsJob
    → Feign 查 order-service: 昨天有多少订单？总金额？
    → 打印统计日志
```

---

## 附录：问题排查指南

### 服务启动不了

1. **端口被占用**：`netstat -ano | findstr 8081` 查看谁占用了端口
2. **数据库连不上**：确认 MySQL 已启动，用户名密码正确
3. **Nacos 连不上**：确认 Nacos 容器在运行：`docker ps | grep nacos`
4. **依赖冲突**：删掉本地 Maven 缓存重试：`mvn clean install -U`

### Feign 调用失败

1. 检查被调用方是否启动并注册到 Nacos
2. 打开 Nacos 页面确认服务在线
3. 检查 URL 路径是否匹配被调用方的 Controller 路径

### 事务没有回滚

1. 确认 Seata Server 在运行
2. 确认数据库有 `undo_log` 表
3. 确认方法上标注了 `@GlobalTransactional`

### ES 没日志

1. 确认 ES 容器在运行：`curl http://localhost:9200`
2. 检查索引是否创建：`curl http://localhost:9200/_cat/indices`
3. 查看 Gateway 日志有没有报错

---

> 学习建议：不要试图一次性理解所有技术。按照启动顺序逐个启动服务，边启动边看 Nacos 注册中心，然后在 Knife4j 页面逐个测试接口。理解了一个再学下一个。
