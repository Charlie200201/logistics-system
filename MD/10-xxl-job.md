# 10 — XXL-JOB 定时任务调度

## 是什么

XXL-JOB 是一个**分布式定时任务调度平台**。你可以在它的 Web 管理页面上创建、管理、监控所有定时任务，还可以随时修改执行时间、查看执行日志、手动触发。

## 为什么需要

### Spring 自带的 @Scheduled 的问题

```java
@Scheduled(fixedDelay = 30000)   // 硬编码在代码里
public void updateLocation() { ... }
```

问题：
- 改执行时间 → 改代码 → 重新部署
- 任务多了 → 分散在各个服务里 → 不好管理
- 想看执行记录 → 只能翻日志
- 任务执行失败了 → 没有告警

### XXL-JOB 的解决方案

```
① 新建任务 → 在 Web 页面上填 JobHandler 名称 + Cron 表达式
② 修改执行时间 → 页面上改，立即生效，不用重启
③ 查看日志 → 页面上看每次执行的耗时、结果、错误
④ 失败重试 → 自动重试 + 告警通知
```

## 核心概念

### 架构

```
XXL-JOB Admin（调度中心）
    运行在 Docker 容器中，端口 8088
    提供 Web 管理页面
    │
    │ 分配任务
    ▼
XXL-JOB Executor（执行器）
    嵌入在 logistics-service 中，端口 9999
    │
    │ 执行具体方法
    ▼
@XxlJob 注解的业务方法
```

### 执行流程

```
Admin 页面配置:
  JobHandler: orderTimeoutCancelJob     ← 任务的名字
  Cron: 0 */5 * * * ?                  ← 每 5 分钟执行

调度流程:
  每 5 分钟 → Admin 通知 Executor → Executor 找到 @XxlJob("orderTimeoutCancelJob") 方法
  → 执行 → 返回结果给 Admin → Admin 记录日志
```

## 项目中的代码

### 1. 执行器配置

**文件位置**: `logistics-service/src/main/java/com/logistics/logistics/config/XxlJobConfig.java`

```java
@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;     // "http://localhost:8088/xxl-job-admin"

    @Value("${xxl.job.executor.appname}")
    private String appname;            // "logistics-executor"

    @Value("${xxl.job.executor.port}")
    private int port;                  // 9999

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);   // 调度中心地址
        executor.setAppname(appname);                 // 执行器名称
        executor.setPort(port);                       // 执行器端口
        executor.setLogPath("/logs/xxl-job");         // 日志存储路径
        return executor;
    }
}
```

### 2. 超时订单自动取消任务

**文件位置**: `logistics-service/src/main/java/com/logistics/logistics/job/OrderTimeoutJob.java`

```java
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderFeignClient orderFeignClient;        // Feign 调用 order-service
    private final ProductFeignClient productFeignClient;    // Feign 调用 product-service

    @XxlJob("orderTimeoutCancelJob")    // JobHandler 名称
    public void execute() {
        log.info("========== 超时订单自动取消任务开始 ==========");

        // ① Feign 调用 order-service：查询超时 30 分钟的待支付订单
        Result<List<Map<String, Object>>> result = orderFeignClient.getExpiredOrders(30);
        List<Map<String, Object>> expiredOrders = result.getData();
        log.info("发现超时未支付订单数量: {}", expiredOrders.size());

        // ② 逐个取消
        for (Map<String, Object> order : expiredOrders) {
            Long orderId = Long.valueOf(order.get("id").toString());
            Long productId = Long.valueOf(order.get("productId").toString());
            Integer quantity = Integer.valueOf(order.get("quantity").toString());

            // 取消订单
            orderFeignClient.cancelOrder(orderId);
            // 恢复库存
            Map<String, Integer> body = new HashMap<>();
            body.put("quantity", quantity);
            productFeignClient.restoreStock(productId, body);

            log.info("超时订单已取消并恢复库存: orderId={}, productId={}, quantity={}",
                    orderId, productId, quantity);
        }
        log.info("========== 超时订单自动取消任务结束 ==========");
    }
}
```

### 3. 每日订单统计任务

**文件位置**: `logistics-service/src/main/java/com/logistics/logistics/job/DailyStatisticsJob.java`

```java
@Component
@RequiredArgsConstructor
public class DailyStatisticsJob {

    private final OrderFeignClient orderFeignClient;

    @XxlJob("dailyOrderStatisticsJob")    // JobHandler 名称
    public void execute() {
        log.info("========== 每日订单统计任务开始 ==========");

        // 获取昨天的日期（如 "2026-07-13"）
        String yesterday = LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);

        // Feign 调用 order-service 查询统计
        Result<Map<String, Object>> result = orderFeignClient.getDailyStats(yesterday);

        Map<String, Object> stats = result.getData();
        log.info("===== 订单统计 ({}) =====", yesterday);
        log.info("订单总数: {}", stats.get("totalCount"));
        log.info("订单总金额: {}", stats.get("totalAmount"));

        log.info("========== 每日订单统计任务结束 ==========");
    }
}
```

### 4. logistics-service 启动类

```java
@SpringBootApplication(scanBasePackages = {"com.logistics.logistics", "com.logistics.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.logistics.logistics.feign")  // 任务需要 Feign 调用
@EnableScheduling           // 原有 @Scheduled 任务
public class LogisticsServiceApplication { ... }
```

### 5. application.yml 配置

```yaml
xxl:
  job:
    admin:
      addresses: http://localhost:8088/xxl-job-admin   # 调度中心地址
    executor:
      appname: logistics-executor                      # 执行器名称
      port: 9999                                       # 执行器端口
      logpath: /logs/xxl-job                           # 日志路径
```

## 配置步骤（在 XXL-JOB Admin 页面）

### 1. 确认执行器注册

打开 http://localhost:8088/xxl-job-admin（admin/123456）
→ 执行器管理 → 确认 `logistics-executor` 自动注册（Online）

### 2. 新建任务

**任务 1：超时订单取消**
- JobHandler: `orderTimeoutCancelJob`
- Cron: `0 */5 * * * ?`（每 5 分钟）
- 运行模式: BEAN
- 路由策略: 第一个

**任务 2：每日订单统计**
- JobHandler: `dailyOrderStatisticsJob`
- Cron: `0 30 0 * * ?`（每天 00:30）
- 运行模式: BEAN

### 3. 启动任务

点击任务右侧的"启动"按钮。

### 4. 查看日志

任务执行后，点击"调度日志"查看执行记录。

## Cron 表达式速查

```
┌── 秒 (0-59)
│ ┌── 分钟 (0-59)
│ │ ┌── 小时 (0-23)
│ │ │ ┌── 日 (1-31)
│ │ │ │ ┌── 月 (1-12)
│ │ │ │ │ ┌── 星期 (0-7)
│ │ │ │ │ │
* * * * * *

常用表达式:
0 */5 * * * ?      每 5 分钟
0 30 0 * * ?       每天 00:30
0 0 2 * * ?        每天凌晨 2 点
0 0 9 * * 1-5      工作日早上 9 点
0 0/30 * * * ?     每 30 分钟
```

## Docker 中的 XXL-JOB

```bash
docker ps | grep xxl-job
# xxl-job   xuxueli/xxl-job-admin:2.3.0   0.0.0.0:8088->8080/tcp
```

XXL-JOB Admin 运行在 Docker 中，宿主机端口 8088。

## 验证方法

### 1. 确认执行器在线

浏览器打开 http://localhost:8088/xxl-job-admin → 执行器管理 → `logistics-executor` 显示"Online"

### 2. 手动触发测试

任务管理 → 找到任务 → 点击"执行一次" → 查看 logistics-service 日志

### 3. 验证超时订单取消

```bash
# 创建一个订单，等它超时（或用数据库手动修改 created_at 到 30 分钟前）
# 然后在 XXL-JOB Admin 手动触发 orderTimeoutCancelJob
# 查看订单状态是否变成 CANCELLED
```

## 常见问题

**Q: 执行器注册不上？**
A: 确认 Admin 地址配置正确 `http://localhost:8088/xxl-job-admin`，网络能通。确认执行器端口 9999 没有被占用。

**Q: 任务执行失败？**
A: 在 Admin 页面查看调度日志，日志里有详细的错误堆栈。通常是 Feign 调用相关的服务没启动。

**Q: Cron 改了不生效？**
A: 修改 Cron 后需要点"启动"或"重启"任务。
