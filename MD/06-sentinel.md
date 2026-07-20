# 06 — Sentinel 限流熔断

## 是什么

Sentinel 是阿里巴巴开源的**流量治理组件**。它像一个水龙头，控制着流入系统的请求量，保护服务不被突发流量打垮。

## 为什么需要

### 没有限流时

```
双十一秒杀：1000 QPS → 服务正常运行
突然：100000 QPS 流量涌入
→ 数据库连接池耗尽
→ 服务 A 挂了
→ 调用服务 A 的服务 B 也跟着挂了
→ 整个系统崩溃（级联故障 / 雪崩效应）
```

### 有了 Sentinel 后

```
100000 QPS 涌入
→ Sentinel 限流：只放行 50 QPS（令牌桶模式）
→ 其余请求快速返回 429 "系统繁忙"
→ 服务后端压力可控
→ 系统正常运行（只是部分用户体验降级，但不会全挂）
```

## 核心概念

### 三大能力

| 能力 | 说明 | 场景 |
|------|------|------|
| **流量控制（Flow Control）** | 限制 QPS / 并发数 | 防止突发流量 |
| **熔断降级（Circuit Breaking）** | 服务挂了快速失败 | 防止级联故障 |
| **系统保护（System Guard）** | 按系统负载限流 | 防止 CPU 打满 |

### 令牌桶模式（本项目使用）

```
                    ┌──────────────┐
  每秒放入 50 个 →  │   令牌桶      │  ← 容量上限 100 个
  (count=50)        │  [][][][]...  │
                    └──────┬───────┘
                           │
                    请求来了 → 有令牌？→ 取走一个 → 放行
                    请求来了 → 没令牌了？
                               → 等待 500ms → 还没等到 → 返回 429
```

**为什么用令牌桶？**

- **普通限流**：每秒严格 50 个请求，第 51 个直接拒绝——太粗暴
- **令牌桶**：平时少用可以攒令牌（最多 100 个），突发流量可以从桶里取积攒的令牌——更平滑

**参数含义**：
- `burst=100`：桶最多存 100 个令牌
- `count=50`：每秒放入 50 个新令牌
- `timeout=500ms`：没拿到令牌时最多等 500 毫秒

## 项目中的代码

### Sentinel Gateway 限流配置

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/config/SentinelConfig.java`

```java
@Configuration
public class SentinelConfig {

    @PostConstruct     // Bean 初始化后自动执行
    public void initGatewayRules() {
        // ① 定义要限流的 API 分组
        Set<ApiDefinition> apiDefinitions = new HashSet<>();
        ApiDefinition apiDefinition = new ApiDefinition("order-api-group")
                .setPredicateItems(new HashSet<ApiPathPredicateItem>() {{
                    add(new ApiPathPredicateItem()
                            .setPattern("/api/orders/**")     // ← 对订单接口限流
                            .setMatchStrategy(URL_MATCH_STRATEGY_PREFIX));
                }});
        apiDefinitions.add(apiDefinition);
        GatewayApiDefinitionManager.loadApiDefinitions(apiDefinitions);

        // ② 配置令牌桶限流规则
        Set<GatewayFlowRule> rules = new HashSet<>();
        GatewayFlowRule rule = new GatewayFlowRule("order-api-group")
                .setCount(50)                     // 每秒新增 50 个令牌（QPS 阈值）
                .setIntervalSec(1)                // 统计间隔 1 秒
                .setBurst(100)                    // 令牌桶容量 100
                .setControlBehavior(CONTROL_BEHAVIOR_DEFAULT)  // 令牌桶模式
                .setMaxQueueingTimeoutMs(500);    // 排队超时 500ms
        rules.add(rule);
        GatewayRuleManager.loadRules(rules);

        // ③ 被限流时的降级响应
        GatewayCallbackManager.setBlockHandler(new BlockRequestHandler() {
            @Override
            public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable t) {
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)  // HTTP 429
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"code\": 429, \"message\": \"系统繁忙，请稍后再试\"}");
            }
        });
    }
}
```

### 限流规则详解

```java
GatewayFlowRule rule = new GatewayFlowRule("order-api-group")
    .setCount(50)         // QPS 阈值 = 50
    .setBurst(100)        // 令牌桶容量 = 100
    .setMaxQueueingTimeoutMs(500);  // 超时 = 500ms
```

**场景模拟**：

```
场景 1: 正常流量（10 QPS）
  桶里一直有令牌 → 所有请求正常通过

场景 2: 突发流量（100 QPS，持续 3 秒）
  第 1 秒: 桶里有 100 个令牌（积攒的），100 个请求全通过
  第 2 秒: 桶被掏空，只有新放入的 50 个 → 50 个通过，50 个等待
  第 3 秒: 同上，开始有请求超时 500ms → 返回 429

场景 3: 恢复
  流量回落到 10 QPS → 桶开始重新积攒令牌
```

## 配置说明

### application.yml 中的 Sentinel 配置

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8099      # Sentinel Dashboard 地址（监控面板）
      filter:
        enabled: false                  # 在 Gateway 中禁用默认的 Servlet Filter
      scg:
        order: -100                     # Sentinel Gateway 过滤器优先级
```

**为什么 `filter.enabled: false`？**
Gateway 基于 WebFlux，不是传统的 Servlet。Sentinel 的 Servlet Filter 在 Gateway 中不能用，要用专门的 Sentinel Gateway 适配。

## 验证方法

### 1. 快速请求触发限流

```bash
# 用 Apache Bench 工具快速发请求
# 先登录获取 Token
TOKEN="eyJ..."

# 并发 10，发 200 个请求（模拟高并发）
ab -n 200 -c 10 -H "Authorization: Bearer $TOKEN" \
  http://localhost:8085/api/orders/1

# 部分请求应返回:
# {"code": 429, "message": "系统繁忙，请稍后再试"}
```

### 2. 观察 Sentinel 日志

查看 gateway-service 的日志，当触发限流时会看到 Sentinel 的 blocking 日志。

### 3. Sentinel Dashboard（可选）

启动 Sentinel Dashboard 后可以看到实时的流量监控图表。

## 常见问题

**Q: 限流规则修改需要重启吗？**
A: 如果规则配置在 Nacos 配置中心，可以动态修改。代码中硬编码的需要重启。

**Q: 令牌桶和漏桶有什么区别？**
- 令牌桶：固定速率放令牌，可以应对突发（项目中用这种）
- 漏桶：固定速率处理请求，突发请求被平滑处理

**Q: 为什么只对 `/api/orders/**` 限流？**
A: 创建订单涉及多个服务调用和事务，是最重的操作。其他查询接口压力小。实际项目中根据监控判断对哪些接口限流。

---

## 熔断降级（Circuit Breaking）

### 是什么

熔断器像一个**电路保险丝**。当下游服务连续出错时，自动"跳闸"不再调用它，直接返回兜底数据。等一段时间后再试探性地放几个请求过去，恢复了就关闭断路器。

### 熔断 vs 限流

| | 限流（Flow Control） | 熔断（Circuit Breaking） |
|------|---------------------|--------------------------|
| **目标** | 保护自己 | 保护自己，同时快速失败 |
| **触发条件** | QPS 超过阈值 | 请求失败比例超过阈值 |
| **动作** | 排队等待或拒绝 | 直接走 fallback，不调下游 |
| **恢复** | 下一秒自动恢复 | 等熔断时间后进入半开状态 |

### 断路器三态模型

```
         正常状态                   失败率 > 阈值
      ┌──────────┐             ┌──────────┐
      │  CLOSED  │ ─────────→  │   OPEN   │
      │ (断路器关) │             │ (断路器开) │
      │ 正常调用  │             │ 直接降级  │
      └──────────┘             └─────┬────┘
           ↑                        │
           │         等熔断时长后     │
           │      放少量请求试探      │
           │    ┌──────────┐        │
           └─── │ HALF_OPEN│ ←─────┘
                │ (半开)    │
                └──────────┘
             试探成功 → CLOSED
             试探失败 → OPEN（继续熔断）
```

## 项目中的代码

### 1. Feign + Sentinel 集成

**步骤一**：开启 `feign.sentinel.enabled`

**文件位置**: `order-service/src/main/resources/application.yml`

```yaml
feign:
  sentinel:
    enabled: true        # 让 Feign 的每个调用都受 Sentinel 管理
```

**步骤二**：为每个 `@FeignClient` 指定 fallback

**文件位置**: `order-service/src/main/java/com/logistics/order/feign/UserFeignClient.java`

```java
@FeignClient(
    name = "user-service",
    fallback = UserFeignClientFallback.class   // ← 熔断后走这个类
)
public interface UserFeignClient {
    @GetMapping("/api/users/{id}")
    Result<Map<String, Object>> getUserById(@PathVariable("id") Long id);
}
```

### 2. Fallback 实现

**文件位置**: `order-service/src/main/java/com/logistics/order/feign/fallback/UserFeignClientFallback.java`

```java
@Slf4j
@Component
public class UserFeignClientFallback implements UserFeignClient {

    @Override
    public Result<Map<String, Object>> getUserById(Long id) {
        log.error("user-service 调用失败，触发熔断降级: getUserById({})", id);
        return Result.fail(429, "用户服务暂不可用，请稍后重试");
        //                    ↑ 直接返回兜底结果，不再调 user-service
    }
}
```

**文件位置**: `order-service/src/main/java/com/logistics/order/feign/fallback/ProductFeignClientFallback.java`

```java
@Slf4j
@Component
public class ProductFeignClientFallback implements ProductFeignClient {

    @Override
    public Result<Boolean> deductStock(Long id, Map<String, Integer> body) {
        log.error("product-service 调用失败，触发熔断降级: deductStock({})", id);
        return Result.fail(429, "商品服务暂不可用，请稍后重试");
    }
}
```

### 3. 熔断规则（DegradeRule）

**文件位置**: `order-service/src/main/java/com/logistics/order/config/SentinelDegradeConfig.java`

```java
@Configuration
public class SentinelDegradeConfig {

    @PostConstruct
    public void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // 规则 1: user-service 查询接口
        DegradeRule userRule = new DegradeRule("GET:http://user-service/api/users/{id}")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)  // 异常比例模式
                .setCount(0.5)             // 50% 请求失败 → 触发熔断
                .setMinRequestAmount(5)     // 最少 5 个请求后才开始统计
                .setStatIntervalMs(10000)   // 10 秒统计窗口
                .setTimeWindow(60);         // 熔断 60 秒 → 半开
        rules.add(userRule);

        // 规则 2: product-service 查询接口
        DegradeRule productRule = new DegradeRule("GET:http://product-service/api/products/{id}")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)             // 50% 失败
                .setMinRequestAmount(5)
                .setStatIntervalMs(10000)
                .setTimeWindow(60);
        rules.add(productRule);

        // 规则 3: 扣减库存（写操作更敏感，阈值更低）
        DegradeRule stockRule = new DegradeRule("POST:http://product-service/api/products/{id}/deduct-stock")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.3)             // 30% 失败就熔断
                .setMinRequestAmount(3)     // 3 个请求开始统计
                .setStatIntervalMs(10000)
                .setTimeWindow(120);        // 熔断 120 秒
        rules.add(stockRule);

        DegradeRuleManager.loadRules(rules);
    }
}
```

**文件位置**: `logistics-service/src/main/java/com/logistics/logistics/config/SentinelDegradeConfig.java`

```java
@Configuration
public class SentinelDegradeConfig {

    @PostConstruct
    public void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // 超时订单查询（定时任务每 5 分钟调一次，统计窗口加大）
        DegradeRule orderRule = new DegradeRule("GET:http://order-service/api/orders/expired")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(3)      // 定时任务频率低，门槛设小
                .setStatIntervalMs(300000)   // 5 分钟统计窗口
                .setTimeWindow(120);
        rules.add(orderRule);

        // 恢复库存
        DegradeRule stockRule = new DegradeRule("POST:http://product-service/api/products/{id}/restore-stock")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.3)
                .setMinRequestAmount(3)
                .setStatIntervalMs(10000)
                .setTimeWindow(120);
        rules.add(stockRule);

        DegradeRuleManager.loadRules(rules);
    }
}
```

### 4. 项目中的完整熔断规则表

| 调用方 | 被调用方 | 资源名 | 失败阈值 | 最小请求 | 统计窗口 | 熔断时长 | 说明 |
|--------|----------|--------|----------|----------|----------|----------|------|
| order-service | user-service | `GET:.../api/users/{id}` | 50% | 5 | 10s | 60s | 查询接口 |
| order-service | product-service | `GET:.../api/products/{id}` | 50% | 5 | 10s | 60s | 查询接口 |
| order-service | product-service | `POST:.../deduct-stock` | 30% | 3 | 10s | 120s | **写操作更敏感** |
| logistics-service | order-service | `GET:.../orders/expired` | 50% | 3 | 5min | 120s | 定时任务低频 |
| logistics-service | product-service | `POST:.../restore-stock` | 30% | 3 | 10s | 120s | 写操作 |

### 5. 熔断触发后的行为

```java
// 正常情况下:
order.createOrder()
  → userFeignClient.getUserById(1L)     // 正常 HTTP 调用
  → productFeignClient.deductStock(1, {qty:2})  // 正常 HTTP 调用

// product-service 挂了，熔断触发后:
order.createOrder()
  → userFeignClient.getUserById(1L)     // 正常（user 没挂）
  → productFeignClient.deductStock(1, {qty:2})
        │
        ├─ 断路器 CLOSED: 尝试调用 → 失败 → 累计错误次数
        │   (10 秒内 3 次调用 100% 失败 > 30% 阈值)
        │
        ├─ 断路器 OPEN: 不调用 product，直接走 ProductFeignClientFallback
        │   → 返回 {"code":429, "message":"商品服务暂不可用，请稍后重试"}
        │   (持续 120 秒)
        │
        └─ 120 秒后 HALF_OPEN:
            放一个请求试探 → 成功 → CLOSED（恢复）
                           → 失败 → OPEN（再等 120 秒）
```

### 6. 参数详解

| 参数 | 含义 | 本项目值 | 为什么这样设 |
|------|------|----------|-------------|
| `grade` | 熔断模式 | `EXCEPTION_RATIO` | 按失败百分比，比固定次数更灵活 |
| `count` | 阈值 | 50% / 30% | 查询容忍 50% 失败，写操作用 30% 更敏感 |
| `minRequestAmount` | 最小请求数 | 3~5 | 太少没统计意义，太多响应慢 |
| `statIntervalMs` | 统计窗口 | 10000ms / 300000ms | 常规 10s，定时任务 5min |
| `timeWindow` | 熔断持续 | 60s / 120s | 查询 60s 快速恢复，写操作 120s 更保守 |

### 7. Fallback 设计原则

| 原则 | 说明 | 本项目示例 |
|------|------|-----------|
| **快速失败** | 不重试、不等超时 | 直接返回 429 |
| **不抛异常** | fallback 不能抛异常，否则断路器计数不准 | 所有异常都 catch 住 |
| **合理兜底** | 返回对业务影响最小的结果 | 超时订单查询返回空列表 |
| **记录日志** | 方便排查 | `log.error("xxx 调用失败，触发熔断降级")` |

## 验证方法

### 测试熔断

```bash
# 1. 停掉 product-service
# 2. 连续发 5 个创建订单请求（扣库存会调 product）
for i in 1 2 3 4 5; do
  curl -X POST http://localhost:8085/api/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"userId":1,"productId":1,"quantity":2,"address":"北京"}'
done

# 前 3-4 次: 抛异常或超时
# 第 5 次起: {"code":429,"message":"商品服务暂不可用，请稍后重试"}
#           ↑ 熔断器已 OPEN，不再调 product

# 3. 启动 product-service
# 4. 等 120 秒后再次请求 → 恢复正常
```

### 查看熔断状态

```bash
# 查看 Sentinel 日志中是否有 "fallback" 或 "degrade" 字样
# order-service 日志中应看到:
#   product-service 调用失败，触发熔断降级: deductStock(1)
```
