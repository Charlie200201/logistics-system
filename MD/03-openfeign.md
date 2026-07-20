# 03 — OpenFeign 服务间远程调用

## 是什么

OpenFeign 让微服务之间的调用**像调用本地方法一样简单**。你只需要定义一个接口，加个注解，Feign 帮你完成 HTTP 请求的发送和响应的解析。

```java
// 定义了接口 = 定义了远程调用的方法
@FeignClient(name = "user-service")     // 告诉 Feign：调用 user-service
public interface UserFeignClient {

    @GetMapping("/api/users/{id}")       // 映射到对方的 Controller
    Result<Map<String, Object>> getUserById(@PathVariable Long id);
}

// 使用的时候就像调用本地方法
UserFeignClient client;
Result result = client.getUserById(1L);  // 实际发起了 HTTP GET 请求
```

## 为什么需要

### 没有 Feign 时

你需要自己写 HTTP 请求代码：

```java
// 传统方式：自己拼接 URL，发送请求，解析响应
String url = "http://192.168.1.100:8081/api/users/" + userId;
RestTemplate restTemplate = new RestTemplate();
ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
ObjectMapper mapper = new ObjectMapper();
Result result = mapper.readValue(response.getBody(), Result.class);
// 还要处理异常、超时、负载均衡...代码大量重复
```

### 有了 Feign 后

```java
// 一行调用，Feign 帮你处理所有细节
Result<Map<String, Object>> result = userFeignClient.getUserById(userId);
```

## 核心概念

### 调用流程

```
OrderServiceImpl.createOrder()
    │
    ├→ userFeignClient.getUserById(1L)
    │       │
    │       ├→ ① 解析 @FeignClient(name = "user-service")
    │       ├→ ② 去 Nacos 查 user-service 的地址列表
    │       ├→ ③ 负载均衡选一个实例
    │       ├→ ④ 解析 @GetMapping("/api/users/{id}")
    │       ├→ ⑤ 发送 HTTP GET 请求
    │       └→ ⑥ 把 JSON 响应转成 Result<Map<String, Object>>
    │
    └→ productFeignClient.deductStock(productId, body)
            │
            └→ 同上流程...
```

### Feign 本质

Feign 就是**声明式的 HTTP 客户端**。它通过 JDK 动态代理，在运行时为你的接口生成实现类。当你调用接口方法时，代理类拦截调用，动态构造 HTTP 请求。

## 项目中的代码

### 1. 定义 Feign 客户端

项目中有 3 个 Feign 客户端：

**order-service 调用 user-service**：

**文件位置**: `order-service/src/main/java/com/logistics/order/feign/UserFeignClient.java`

```java
package com.logistics.order.feign;

@FeignClient(name = "user-service")          // ← 调用哪个服务
public interface UserFeignClient {

    @GetMapping("/api/users/{id}")            // ← 对应 user-service 的 Controller
    Result<Map<String, Object>> getUserById(@PathVariable("id") Long id);

    @GetMapping("/api/users/verify")
    Result<Boolean> verifyToken(@RequestParam("token") String token);
}
```

**order-service 调用 product-service**：

**文件位置**: `order-service/src/main/java/com/logistics/order/feign/ProductFeignClient.java`

```java
@FeignClient(name = "product-service")
public interface ProductFeignClient {

    @GetMapping("/api/products/{id}")
    Result<Map<String, Object>> getProductById(@PathVariable("id") Long id);

    @PostMapping("/api/products/{id}/deduct-stock")
    Result<Boolean> deductStock(@PathVariable("id") Long id,
                                 @RequestBody Map<String, Integer> body);
}
```

**logistics-service 调用 order-service 和 product-service**（XXL-JOB 任务需要）：

**文件位置**: `logistics-service/src/main/java/com/logistics/logistics/feign/OrderFeignClient.java`

```java
@FeignClient(name = "order-service")
public interface OrderFeignClient {

    @GetMapping("/api/orders/expired")
    Result<List<Map<String, Object>>> getExpiredOrders(@RequestParam("minutes") int minutes);

    @PutMapping("/api/orders/{id}/cancel")
    Result<?> cancelOrder(@PathVariable("id") Long id);

    @GetMapping("/api/orders/stats/daily")
    Result<Map<String, Object>> getDailyStats(@RequestParam("date") String date);
}
```

### 2. 使用 Feign 客户端

**文件位置**: `order-service/src/main/java/com/logistics/order/service/impl/OrderServiceImpl.java`

```java
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order>
        implements OrderService {

    private final UserFeignClient userFeignClient;         // ← 像本地 Service 一样注入
    private final ProductFeignClient productFeignClient;   // ← 像本地 Service 一样注入

    @GlobalTransactional
    public Order createOrder(Order order) {
        // ① Feign 调用 user-service：验证用户
        Result<Map<String, Object>> userResult = userFeignClient.getUserById(order.getUserId());
        if (userResult.getCode() != 200 || userResult.getData() == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // ② Feign 调用 product-service：查询商品
        Result<Map<String, Object>> productResult = productFeignClient.getProductById(order.getProductId());
        Map<String, Object> productData = productResult.getData();
        BigDecimal price = new BigDecimal(productData.get("price").toString());

        // ③ Feign 调用 product-service：扣减库存
        Map<String, Integer> body = new HashMap<>();
        body.put("quantity", order.getQuantity());
        Result<Boolean> deductResult = productFeignClient.deductStock(order.getProductId(), body);

        // ...
    }
}
```

### 3. 启动类开启 Feign

**文件位置**: `order-service/src/main/java/com/logistics/order/OrderServiceApplication.java`

```java
@EnableFeignClients(basePackages = "com.logistics.order.feign")   // ← 必须加！
@SpringBootApplication
public class OrderServiceApplication { ... }
```

## 配置说明

Feign 的默认超时时间较短（1 秒），复杂操作可能超时。可在 `application.yml` 中调大：

```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 5000    # 连接超时 5 秒
        readTimeout: 5000       # 读取超时 5 秒
```

## 验证方法

启动 user-service、product-service、order-service 后：

```bash
# 创建订单（order-service 内部会 Feign 调用 user 和 product）
curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":1,"quantity":2,"address":"北京市朝阳区xxx"}'

# 如果成功返回，说明 Feign 调用正常
```

看 order-service 日志：
```
用户验证通过: userId=1           ← Feign 调用 user-service 成功
商品查询成功: productId=1, price=5999.00  ← Feign 调用 product-service 成功
库存扣减成功: productId=1, quantity=2, remainingStock=98  ← 又一次 Feign 调用成功
```

## 常见问题

**Q: Feign 调用报 "UnknownHostException"？**
A: 被调用的服务没有注册到 Nacos。检查目标服务是否启动，检查 Nacos 页面。

**Q: Feign 调用超时？**
A: 被调用方法执行时间太长。调整 `feign.client.config.default.readTimeout`。

**Q: Feign 无法注入，编译报错？**
A: 检查启动类是否有 `@EnableFeignClients`，检查 `basePackages` 路径是否正确。

**Q: Feign 接口的方法签名要和被调用方的 Controller 完全一致吗？**
A: 方法名可以不同。但 `@GetMapping("/api/users/{id}")` 路径、参数注解、返回类型要匹配。
