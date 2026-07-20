# 07 — Seata 分布式事务

## 是什么

Seata（Simple Extensible Autonomous Transaction Architecture）是阿里巴巴开源的**分布式事务解决方案**。当一个业务操作跨越多个微服务时，Seata 保证这些操作要么全部成功，要么全部回滚。

## 为什么需要

### 问题场景

创建订单这个操作涉及 3 个服务，2 个数据库：

```
order-service     → db_order.t_order    写入订单
product-service   → db_product.t_product 扣减库存
                   → db_product.undo_log Seata 回滚日志

假设流程：
① 写入订单 ✓（order-service 本地事务提交成功）
② 扣减库存 ✗（product-service 出错了！）

结果：订单创建了，但库存没扣 —— 数据不一致！
```

### 传统方案的问题

```
方案 A: 用 @Transactional
  → @Transactional 只能管自己的数据库，管不了 product-service 的数据库
  无法解决跨服务的事务

方案 B: 手动补偿
  → ① 尝试扣库存 → ② 失败了 → ③ 调用 order-service "删除刚才创建的订单"
  代码复杂，每个操作都要写补偿逻辑
```

### Seata 的解决方案

```
@GlobalTransactional   ← 一个注解，Seata 自动管理所有跨服务的操作
public Order createOrder(Order order) {
    productFeignClient.deductStock(...);   // 操作 product-service 的数据库
    this.save(order);                      // 操作 order-service 的数据库
    // 任何一个失败 → Seata 自动回滚所有操作
}
```

## 核心概念

### AT 模式原理

Seata AT 模式通过**自动生成反向 SQL**来实现回滚：

```
全局事务开始
    │
    ├→ order-service 写入订单
    │   实际执行: INSERT INTO t_order ...
    │   undo_log 记录: DELETE FROM t_order WHERE id = ?
    │
    ├→ product-service 扣减库存
    │   实际执行: UPDATE t_product SET stock = stock - 1 WHERE id = 1
    │   undo_log 记录: UPDATE t_product SET stock = old_value WHERE id = 1
    │
    ├→ 全部成功 → TC 通知所有人提交（删除 undo_log）
    └→ 某步失败 → TC 通知所有人回滚（执行 undo_log 中的反向 SQL）
```

### 三个角色

| 角色 | 简称 | 做什么 | 项目中谁担任 |
|------|------|--------|-------------|
| Transaction Coordinator | TC | 协调全局事务的提交/回滚 | Seata Server（Docker 容器） |
| Transaction Manager | TM | 开启全局事务 | order-service（@GlobalTransactional） |
| Resource Manager | RM | 管理分支事务 | product-service（@Transactional） |

### undo_log 表

每个参与分布式事务的数据库都需要 `undo_log` 表：

```sql
CREATE TABLE IF NOT EXISTS undo_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,       -- 分支事务 ID
    xid VARCHAR(100) NOT NULL,       -- 全局事务 ID
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL, -- 回滚信息（反向 SQL）
    log_status INT NOT NULL,         -- 状态
    log_created DATETIME NOT NULL,
    log_modified DATETIME NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB;
```

项目中的 `schema.sql` 在 `db_order` 和 `db_product` 两个数据库都创建了此表。

## 项目中的代码

### 1. Seata 配置类

**文件位置**: `order-service/src/main/java/com/logistics/order/config/SeataConfig.java`

```java
@Configuration
public class SeataConfig {
    @Bean
    public GlobalTransactionScanner globalTransactionScanner() {
        return new GlobalTransactionScanner(
                "order-service",            // 应用名
                "logistics_tx_group"        // 事务分组名
        );
    }
}
```

### 2. 全局事务入口（@GlobalTransactional）

**文件位置**: `order-service/src/main/java/com/logistics/order/service/impl/OrderServiceImpl.java`

```java
@GlobalTransactional(name = "create-order-tx", timeoutMills = 300000)
// ↑ 替代 @Transactional。Seata 管理跨服务的分布式事务
public Order createOrder(Order order) {
    // ① Feign 调用 product-service 扣库存（分支事务 1）
    //    → product-service 执行 UPDATE，Seata 在 undo_log 记录反向 SQL
    Result<Boolean> deductResult = productFeignClient.deductStock(order.getProductId(), body);
    if (deductResult.getCode() != 200) {
        throw new BusinessException(ResultCode.STOCK_INSUFFICIENT);
        // ↑ 抛异常 → Seata 通知 TC → TC 通知 product-service 执行 undo_log 回滚
    }

    // ② 本地写入订单（分支事务 2）
    this.save(order);
    // → Seata 在 db_order.undo_log 记录反向 SQL

    // ③ 事务提交后发送消息（避免消息发出去了但事务回滚了）
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, message);
        }
    });

    return order;
}
```

### 3. 分支事务（product-service）

**文件位置**: `product-service/src/main/java/com/logistics/product/service/impl/ProductServiceImpl.java`

```java
@Transactional  // 本地事务，Seata 自动管理这个分支
public boolean deductStock(Long productId, Integer quantity) {
    // Redisson 分布式锁...
    Product product = this.getById(productId);
    product.setStock(product.getStock() - quantity);
    this.updateById(product);  // Seata 自动在 undo_log 记录回滚信息
    return true;
}
```

### 4. application.yml 配置

**文件位置**: `order-service/src/main/resources/application.yml`

```yaml
seata:
  enabled: true                          # 启用 Seata
  tx-service-group: logistics_tx_group   # 事务分组名
  service:
    vgroup-mapping:
      logistics_tx_group: default        # 映射到 default 集群
    grouplist:
      default: localhost:8091            # Seata Server 地址
```

`product-service` 也有相同的 Seata 配置。

### 5. RabbitMQ 消息的发送时机

```java
// 关键：消息必须等事务提交后才发送
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        // 只有全局事务成功提交，才发送消息
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, message);
    }
});
```

**为什么这样做？**
如果事务最终回滚了，但消息已经发出去了，物流服务就会为一个不存在的订单创建物流单。所以必须在 `afterCommit` 回调中发送。

## 配置说明

### Docker 中的 Seata Server

```bash
docker ps | grep seata
# seata   seataio/seata-server:latest   0.0.0.0:8091->8091/tcp
```

Seata Server（TC）运行在 Docker 容器中，端口 8091，事务分组 `logistics_tx_group` 映射到 `default` 集群。

### 参与的数据库

| 数据库 | 是否需要 undo_log | 原因 |
|--------|------------------|------|
| db_order | 是 | order-service 是 TM，会在 t_order 表写数据 |
| db_product | 是 | product-service 是 RM，会在 t_product 表写数据 |
| db_user | 否 | user-service 只参与查询，不修改数据 |
| db_logistics | 否 | logistics-service 通过 RabbitMQ 异步处理，不参与事务 |

## 验证方法

### 模拟回滚

```bash
# 先创建一个库存不足的情况
# productId=1 库存=5，下单 quantity=10

curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer <token>" \
  -d '{"userId":1,"productId":1,"quantity":10,"address":"北京"}'

# 应返回错误：{"code":2002,"message":"库存不足"}

# 查看 db_order.t_order：应该没有新增订单（Seata 回滚了）
# 查看 db_product.t_product：库存数量没变（Seata 回滚了）
```

### 查看 Seata 日志

```bash
docker logs seata --tail 30
```

可以看到全局事务的开始、分支事务的注册、事务提交/回滚的日志。

## 常见问题

**Q: Seata 怎么知道要回滚？**
A: @GlobalTransactional 方法中任何地方抛未捕获的异常，Seata 就会通知 TC 回滚。

**Q: AT 模式有性能影响吗？**
A: 有。每次写操作都会额外写一条 undo_log。但这是必要的代价，换来数据一致性。

**Q: 什么情况下不需要分布式事务？**
A: 如果操作只涉及一个服务一个数据库，用 `@Transactional` 就够了。只有跨服务跨数据库时才需要 Seata。

**Q: Seata 的 AT 模式和 TCC 模式有什么区别？**
A: AT 模式对业务无侵入（自动生成回滚 SQL），TCC 模式需要手写 try/confirm/cancel 逻辑。AT 是首选，TCC 适合复杂业务。
