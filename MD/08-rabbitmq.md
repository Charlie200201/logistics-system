# 08 — RabbitMQ 消息队列

## 是什么

RabbitMQ 是一个**消息中间件**，用于在不同服务之间传递消息。它的工作方式像一个**邮箱系统**：发送方把消息投进邮箱，接收方从邮箱取出消息处理。

## 为什么需要

### 同步调用的问题

```
order-service（同步调用）→ logistics-service
    │
    ├→ 如果 logistics-service 挂了 → 订单创建失败 → 用户看到错误
    ├→ 如果 logistics-service 慢了 → 用户等很久
    └→ order 和 logistics 强耦合 → 改 logistics 可能影响 order
```

### 异步解耦后

```
order-service → 发消息 → RabbitMQ → logistics-service 异步消费
    │
    ├→ logistics-service 挂了？消息留在队列，恢复后继续处理
    ├→ logistics-service 慢了？不阻塞订单创建，用户无感
    └→ 新增一个"短信通知服务"？只需要订阅队列，不改 order 代码
```

## 核心概念

### 四大角色

```
Producer（生产者）                  Consumer（消费者）
    │                                     │
    │  发送消息                            │  监听消息
    ▼                                     ▼
Exchange（交换机） ──路由──→ Queue（队列）
    │                         │
    └── 根据 Routing Key 分发   └── 消息排队等待消费
```

### 本项目中的消息流

```
order-service（生产者）
    │
    ├→ 创建订单成功
    ├→ 构建消息 JSON: {"orderId":1001, "userId":1, ...}
    │
    ▼
Exchange: logistics.exchange（直连交换机）
    │
    │ Routing Key: "logistics.create"
    ▼
Queue: logistics.queue
    │
    │ 消息排队等待
    ▼
logistics-service（消费者）
    │
    ├→ @RabbitListener 监听到消息
    ├→ 解析消息内容
    ├→ 生成物流单号
    └→ 写入物流表
```

## 项目中的代码

### 1. 生产者（order-service）

**配置队列、交换机和绑定**：

**文件位置**: `order-service/src/main/java/com/logistics/order/config/RabbitMQConfig.java`

```java
@Configuration
public class RabbitMQConfig {

    public static final String QUEUE = "logistics.queue";       // 队列名
    public static final String EXCHANGE = "logistics.exchange"; // 交换机名
    public static final String ROUTING_KEY = "logistics.create";// 路由键

    // ① 创建队列（持久化的，重启不会丢失）
    @Bean
    public Queue logisticsQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    // ② 创建直连交换机
    @Bean
    public DirectExchange logisticsExchange() {
        return new DirectExchange(EXCHANGE);
    }

    // ③ 绑定：把队列绑定到交换机，指定路由键
    @Bean
    public Binding binding(Queue logisticsQueue, DirectExchange logisticsExchange) {
        return BindingBuilder
                .bind(logisticsQueue)          // 绑定哪个队列
                .to(logisticsExchange)         // 绑定到哪个交换机
                .with(ROUTING_KEY);            // 路由键是什么
    }
}
```

**发送消息**：

**文件位置**: `order-service/src/main/java/com/logistics/order/service/impl/OrderServiceImpl.java`

```java
// 在 Seata 全局事务提交成功后发送消息
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("orderId", order.getId());
            message.put("userId", order.getUserId());
            message.put("productId", order.getProductId());
            message.put("quantity", order.getQuantity());
            message.put("address", order.getAddress());

            // 发送消息到指定交换机和路由键
            rabbitTemplate.convertAndSend(
                    "logistics.exchange",     // 交换机
                    "logistics.create",       // 路由键
                    objectMapper.writeValueAsString(message)  // 消息内容（JSON 字符串）
            );
            log.info("物流消息已发送: orderId={}", order.getId());
        } catch (Exception e) {
            log.error("发送物流消息失败: orderId={}", order.getId(), e);
        }
    }
});
```

### 2. 消费者（logistics-service）

**文件位置**: `logistics-service/src/main/java/com/logistics/logistics/listener/LogisticsMessageListener.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class LogisticsMessageListener {

    private final LogisticsService logisticsService;
    private final ObjectMapper objectMapper;

    // @RabbitListener：监听 logistics.queue 队列，有消息到达自动调用此方法
    @RabbitListener(queues = "logistics.queue")
    public void handleOrderCreated(String message) {
        try {
            log.info("收到物流创建消息: {}", message);

            // ① 解析 JSON 消息
            Map<String, Object> msg = objectMapper.readValue(message, Map.class);
            Long orderId = Long.valueOf(msg.get("orderId").toString());

            // ② 生成物流单号
            String logisticsNo = generateLogisticsNo();

            // ③ 创建物流记录
            logisticsService.createLogistics(logisticsNo, orderId);

            log.info("物流单已创建: orderId={}, logisticsNo={}", orderId, logisticsNo);
        } catch (Exception e) {
            log.error("处理物流创建消息失败", e);
            // 抛出异常 → RabbitMQ 自动重试 → 重试失败 → 进入死信队列
        }
    }

    private String generateLogisticsNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "LOG" + date + String.format("%04d", new Random().nextInt(10000));
    }
}
```

### 3. application.yml 配置

**order-service**（生产者）：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

**logistics-service**（消费者）：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    listener:
      simple:
        acknowledge-mode: auto   # 自动确认
```

## 交换机类型

| 类型 | 路由逻辑 | 使用场景 |
|------|----------|----------|
| **Direct**（本项目使用） | 路由键完全匹配 | 精确路由，一个 key 对应一个队列 |
| **Topic** | 路由键支持通配符（`*` `#`） | 按模式匹配，一 key 多队列 |
| **Fanout** | 忽略路由键，广播 | 所有队列都收到，如配置刷新通知 |

### Direct 交换机示例

```
Exchange: logistics.exchange (direct)
  ├── routing key: "logistics.create"  → 只有这个 key 的消息进入 logistics.queue
  ├── routing key: "logistics.update"  → 不匹配，不会进入
  └── routing key: "order.create"      → 不匹配，不会进入
```

## 消息可靠性保证

| 机制 | 说明 |
|------|------|
| **持久化队列** | `QueueBuilder.durable()`：服务重启后队列不丢 |
| **生产者确认** | 确保消息成功到达 Broker |
| **消费者确认** | `acknowledge-mode: auto`：消费成功才从队列删除 |
| **重试** | 消费失败自动重试 |

## 验证方法

### 1. 检查 RabbitMQ 管理界面

访问：http://localhost:15672  登录：guest/guest

查看：
- Queues 标签：确认 `logistics.queue` 存在
- 创建订单后，观察队列中的消息数量变化

### 2. 测试消息发送和消费

```bash
# 创建订单
curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer <token>" \
  -d '{"userId":1,"productId":1,"quantity":2,"address":"北京市朝阳区xxx"}'

# 看 order-service 日志：
#   物流消息已发送: orderId=1

# 看 logistics-service 日志：
#   收到物流创建消息: {"orderId":1,...}
#   物流单已创建: orderId=1, logisticsNo=LOG20260714...

# 查询自动创建的物流单
curl http://localhost:8080/api/logistics/order/1 \
  -H "Authorization: Bearer <token>"
```

### 3. 测试异步解耦

```bash
# 停掉 logistics-service
# 创建订单 → 订单创建成功（不被物流阻塞）
# 消息留在队列中

# 启动 logistics-service
# 立即消费队列中的消息 → 创建物流单
```

## 常见问题

**Q: 消息发出去了但消费者没有收到？**
A: 检查队列名、交换机和路由键是否一致。查看 RabbitMQ 管理界面确认绑定关系。

**Q: 消息丢了怎么办？**
A: 声明持久化队列 + 发送消息时设置持久化 + 消费者手动确认。

**Q: 多个消费者同时监听一个队列会怎样？**
A: 默认轮询分发，每个消息只会被一个消费者处理。适合水平扩容。
