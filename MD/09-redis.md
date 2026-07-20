# 09 — Redis 缓存 + 分布式锁

## 是什么

Redis 是一个**内存数据库**，数据存在内存中，读写速度极快（微秒级）。在本项目中有两大用途：**缓存热点数据**和**分布式锁**。

## 为什么需要

### 用途一：缓存 — 为什么 MySQL 不够快

```
MySQL 查询:
  磁盘 I/O → 缓存池 → 返回结果
  耗时: 几毫秒 ~ 几十毫秒

Redis 查询:
  内存直接读取 → 返回结果
  耗时: 微秒级

商品详情接口 QPS 很高 → 每次都查 MySQL → MySQL 压力大 → 加 Redis 缓存
```

### 用途二：分布式锁 — 为什么 synchronized 不够

```
单机部署:
  3 个线程同时减库存 → synchronized 锁住 → 安全

多实例部署:
  product-service-1 (JVM 1) synchronized → 锁住实例1
  product-service-2 (JVM 2) synchronized → 锁住实例2
  product-service-3 (JVM 3) synchronized → 锁住实例3

  → 三个 synchronized 互不影响！并发问题仍然存在！
  → 需要跨 JVM 的全局锁 → Redis 分布式锁
```

## 核心概念

### 缓存策略：Cache Aside（旁路缓存）

```
读操作:
  ① 先查 Redis → 命中 → 直接返回
  ② 未命中 → 查 MySQL → 写入 Redis（设 TTL）→ 返回

写操作:
  ① 更新 MySQL
  ② 删除 Redis 中的旧缓存（下次读取时重新加载）
```

### 分布式锁原理

```
Redis 中存一个 key "lock:stock:1" = "唯一标识"
获取锁: SET lock:stock:1 uuid NX EX 30
         ↑ 只有在 key 不存在时才设置成功（NX），设置 30 秒过期（EX）
释放锁: 检查值是否等于自己的 uuid → 是则 DELETE
         ↑ 防止误删别人的锁

Redisson 封装了上述逻辑，提供了 tryLock / unlock 接口
```

## 项目中的代码

### 1. Redis 缓存 — 查询商品

**文件位置**: `product-service/src/main/java/com/logistics/product/service/impl/ProductServiceImpl.java`

```java
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product>
        implements ProductService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_KEY_PREFIX = "product:";   // Key 前缀
    private static final long CACHE_TTL = 30;                     // 过期时间 30 分钟

    @Override
    public Product getProductById(Long id) {
        String cacheKey = CACHE_KEY_PREFIX + id;   // "product:1"

        // ① 先查 Redis 缓存
        Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
        if (product != null) {
            log.info("从缓存中查询商品: id={}", id);
            return product;   // 命中！直接返回
        }

        // ② 未命中 → 查 MySQL
        product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // ③ 写入 Redis 缓存，30 分钟后自动过期
        redisTemplate.opsForValue().set(cacheKey, product, CACHE_TTL, TimeUnit.MINUTES);
        log.info("从数据库查询商品并写入缓存: id={}", id);
        return product;
    }

    @Override
    public Product update(Product product) {
        this.updateById(product);                           // 更新 MySQL
        redisTemplate.delete(CACHE_KEY_PREFIX + product.getId()); // 删除缓存
        return product;
    }
}
```

**为什么更新时删除缓存而不是更新缓存？**
- 更新缓存：并发更新可能造成缓存和数据库不一致
- 删除缓存：简单可靠，下次读时自然会从数据库加载最新数据

### 2. Redis 缓存配置

**文件位置**: `product-service/src/main/java/com/logistics/product/config/RedisConfig.java`

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // JSON 序列化器（对象存 Redis 时自动转 JSON）
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);
        Jackson2JsonRedisSerializer<Object> jacksonSerializer =
                new Jackson2JsonRedisSerializer<>(om, Object.class);

        // Key 用字符串序列化，Value 用 JSON 序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
```

### 3. 分布式锁 — 扣减库存

**文件位置**: `product-service/src/main/java/com/logistics/product/service/impl/ProductServiceImpl.java`

```java
private final RedissonClient redissonClient;
private static final String LOCK_KEY_PREFIX = "lock:stock:";

@Override
@Transactional
public boolean deductStock(Long productId, Integer quantity) {
    String lockKey = LOCK_KEY_PREFIX + productId;   // "lock:stock:1"
    RLock lock = redissonClient.getLock(lockKey);   // 获取分布式锁

    try {
        // tryLock(等待时间, 锁过期时间, 时间单位)
        // 等最多 10 秒获取锁，锁 30 秒后自动释放（防止死锁）
        if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
            try {
                // ===== 锁内的代码是串行的 =====
                Product product = this.getById(productId);
                if (product.getStock() < quantity) {
                    throw new BusinessException(ResultCode.STOCK_INSUFFICIENT);
                }
                product.setStock(product.getStock() - quantity);
                this.updateById(product);
                // ===== 锁内代码结束 =====
                return true;
            } finally {
                lock.unlock();   // 必须在 finally 中释放！
            }
        } else {
            throw new BusinessException(429, "系统繁忙，请稍后再试");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException(500, "扣减库存异常");
    }
}
```

**关键点**：
- `tryLock(10, 30, SECONDS)`：等 10 秒获取锁，锁最多持有 30 秒（防止死锁）
- `lock.unlock()` 必须在 `finally` 中调用，防止业务异常导致锁永不释放

### 4. Redisson 配置（Sentinel 模式）

**文件位置**: `product-service/src/main/java/com/logistics/product/config/RedissonConfig.java`

```java
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // Sentinel 模式：通过哨兵发现 Master，自动处理故障转移
        config.useSentinelServers()
                .setMasterName("mymaster")
                .addSentinelAddress("redis://localhost:26379")
                .addSentinelAddress("redis://localhost:26380")
                .addSentinelAddress("redis://localhost:26381")
                .setMasterConnectionPoolSize(10)
                .setSlaveConnectionPoolSize(10);
        return Redisson.create(config);
    }
}
```

### 5. application.yml 配置

**文件位置**: `product-service/src/main/resources/application.yml`

```yaml
spring:
  redis:
    sentinel:                    # Sentinel 模式
      master: mymaster
      nodes:
        - localhost:26379
        - localhost:26380
        - localhost:26381
    timeout: 5000ms
    lettuce:                     # Redis 连接池
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

## 配置说明

### Redis Sentinel 架构

```
项目通过 Sentinel 连接 Redis:
  Spring Boot → Sentinel(26379/26380/26381) → 询问: Master 在哪？
                                           → Sentinel 回答: 6379
                                           → Spring Boot 连接 Master
  如果 Master 挂了 → Sentinel 自动选举新 Master → Spring Boot 自动切换
```

## 验证方法

### 1. 验证缓存生效

```bash
# 第一次查询（从 MySQL 加载）
curl http://localhost:8080/api/products/1
# product-service 日志: 从数据库查询商品并写入缓存: id=1

# 第二次查询（从 Redis 缓存）
curl http://localhost:8080/api/products/1
# product-service 日志: 从缓存中查询商品: id=1
```

### 2. 验证分布式锁

```bash
# 并发扣库存（模拟超卖场景）
# 同时发多个请求扣同一个商品的库存
for i in {1..5}; do
  curl -X POST http://localhost:8080/api/products/1/deduct-stock \
    -H "Content-Type: application/json" \
    -d '{"quantity":1}' &
done

# 查看 Redis 中的锁（扣减时会出现）:
docker exec redis-master redis-cli keys "lock:stock:*"
```

### 3. 验证 Sentinel 故障转移

```bash
# 停掉 Master
docker stop redis-master
# 等待 5-10 秒，Sentinel 自动将 Replica 提升为新 Master
# 应用自动切换到新 Master，不影响使用
```

## 常见问题

**Q: 缓存和数据库数据不一致怎么办？**
A: 更新时先更新数据库，再删除缓存（Cache Aside 模式）。缓存设置合理的 TTL。

**Q: 分布式锁会不会死锁？**
A: Redisson 的 Watch Dog 机制会自动续期。即使程序崩溃，锁也会在 30 秒后自动过期。

**Q: Redis 内存满了怎么办？**
A: 设置合理的 TTL + 内存淘汰策略（LRU）。本项目缓存 30 分钟过期。

---

## 架构切换指南

### 当前默认架构：一主一从三哨兵（Sentinel）

```
            ┌─────────────┐
            │  product    │
            │  -service   │
            └──────┬──────┘
                   │
      ┌────────────┼────────────┐
      ▼            ▼            ▼
  sentinel-1   sentinel-2   sentinel-3
   :26379       :26380       :26381
      │            │            │
      └────────────┼────────────┘
                   │ 监控
          ┌────────┴────────┐
          ▼                 ▼
     redis-master      redis-replica
       :6379              :6380
    (可读写)            (只读)
```

### 一、三种 Redis 架构对比

| 特性 | 单机 | Sentinel | Cluster |
|------|------|----------|---------|
| 高可用 | ❌ 挂了全挂 | ✅ 自动故障转移 | ✅ 自动故障转移 |
| 数据分片 | ❌ | ❌ 全量复制 | ✅ 16384 个槽位 |
| 扩容 | 不可扩 | 只读扩容（加从节点） | 在线扩缩容 |
| 最小节点数 | 1 | 3（1主+1从+3哨兵） | 6（3主+3从） |
| 客户端感知 | 直连 | 通过哨兵发现 | 直连任意节点 |
| 适用场景 | 开发/测试 | 读多写少、高可用 | 大数据量、高并发 |

### 二、如何切换到 Sentinel 模式

#### 步骤 1：Docker 部署 Redis Sentinel

创建配置文件 `redis-sentinel/` 目录：

**redis-master.conf**（主节点）：
```
port 6379
bind 0.0.0.0
protected-mode no
replica-announce-ip 127.0.0.1    # Docker 中必须：对外宣告宿主机可达的 IP
replica-announce-port 6379
```

**redis-replica.conf**（从节点）：
```
port 6380
bind 0.0.0.0
protected-mode no
replicaof redis-master 6379       # Docker DNS 内网通信
replica-read-only yes
replica-announce-ip 127.0.0.1     # 对外宣告
replica-announce-port 6380
```

**sentinel-1.conf**（同理创建 sentinel-2、sentinel-3，改端口）：
```
port 26379
sentinel monitor mymaster redis-master 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
sentinel resolve-hostnames yes     # Docker 内用 DNS 解析容器名
sentinel announce-hostnames no
sentinel announce-ip 127.0.0.1
```

参数说明：
- `mymaster`：哨兵集群名称，所有哨兵和客户端共用
- `2`：至少 2 个哨兵同意才触发故障转移
- `resolve-hostnames yes`：允许哨兵在 Docker 内通过容器名 DNS 解析
- `announce-ip 127.0.0.1`：哨兵对外宣告自己，客户端用这个地址访问

**启动容器**：
```bash
# 创建 Docker 网络
docker network create redis-net

# 启动 Master
docker run -d --name redis-master --network redis-net -p 6379:6379 \
  -v $(pwd)/redis-sentinel/redis-master.conf:/usr/local/etc/redis/redis.conf \
  redis:latest redis-server /usr/local/etc/redis/redis.conf

# 启动 Replica
docker run -d --name redis-replica --network redis-net -p 6380:6380 \
  -v $(pwd)/redis-sentinel/redis-replica.conf:/usr/local/etc/redis/redis.conf \
  redis:latest redis-server /usr/local/etc/redis/redis.conf

# 启动 3 个 Sentinel
docker run -d --name sentinel-1 --network redis-net -p 26379:26379 \
  -v $(pwd)/redis-sentinel/sentinel-1.conf:/usr/local/etc/redis/sentinel.conf \
  redis:latest redis-sentinel /usr/local/etc/redis/sentinel.conf

docker run -d --name sentinel-2 --network redis-net -p 26380:26380 \
  -v $(pwd)/redis-sentinel/sentinel-2.conf:/usr/local/etc/redis/sentinel.conf \
  redis:latest redis-sentinel /usr/local/etc/redis/sentinel.conf

docker run -d --name sentinel-3 --network redis-net -p 26381:26381 \
  -v $(pwd)/redis-sentinel/sentinel-3.conf:/usr/local/etc/redis/sentinel.conf \
  redis:latest redis-sentinel /usr/local/etc/redis/sentinel.conf
```

#### 步骤 2：项目 application.yml 配置

**文件位置**: `product-service/src/main/resources/application.yml`

```yaml
spring:
  redis:
    # ===== Sentinel 模式 =====
    sentinel:
      master: mymaster          # 哨兵集群名称
      nodes:
        - localhost:26379
        - localhost:26380
        - localhost:26381
    # ===== 公共配置 =====
    password:                   # 无密码留空
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 8           # 最大连接数
        max-idle: 8             # 最大空闲连接
        min-idle: 0
```

#### 步骤 3：Redisson 配置

**文件位置**: `product-service/src/main/java/com/logistics/product/config/RedissonConfig.java`

```java
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSentinelServers()
                .setMasterName("mymaster")          // 哨兵集群名称
                .addSentinelAddress(
                        "redis://localhost:26379",
                        "redis://localhost:26380",
                        "redis://localhost:26381"
                )
                // === Docker NAT 映射（关键！） ===
                // Sentinel 在 Docker 内网看到 master 的 IP 是 172.19.0.x
                // 但宿主机无法访问这个 IP，需要映射为 localhost
                .setNatMap(new HashMap<String, String>() {{
                    put("172.19.0.2:6379", "127.0.0.1:6379");   // master
                    put("172.19.0.3:6380", "127.0.0.1:6380");   // replica
                }})
                .setCheckSentinelsList(false)       // 跳过哨兵列表校验
                .setMasterConnectionPoolSize(10)     // 主节点连接池
                .setSlaveConnectionPoolSize(10)      // 从节点连接池
                .setMasterConnectionMinimumIdleSize(5)
                .setSlaveConnectionMinimumIdleSize(5);
        return Redisson.create(config);
    }
}
```

**注意**：`natMap` 中的 Docker 内网 IP 可能变化。容器重启后，通过以下命令获取实际 IP：

```bash
docker exec sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
# 输出: 172.19.0.2  6379  → 对应的 natMap key
docker exec redis-replica redis-cli -p 6380 config get slave-announce-ip
# 或 docker inspect redis-replica | grep IPAddress
```

#### 步骤 4：读写模式配置

Sentinel 模式支持两种读写策略：

**模式 A：仅主节点读写**（默认，最简单）

```java
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis://localhost:26379", ...)
    .setReadMode(ReadMode.MASTER)       // 只从主节点读
    .setSubscriptionMode(SubscriptionMode.MASTER);
//   ↑ 所有读写都走 Master
```

- 读写都走 Master
- Replica 只做备份，不承担读流量
- 数据强一致性，不会读到旧数据
- 适用场景：对数据一致性要求高，或写多读少

**模式 B：主写从读**（读写分离）

```java
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis://localhost:26379", ...)
    .setReadMode(ReadMode.SLAVE)        // 从节点读
    .setSubscriptionMode(SubscriptionMode.SLAVE)
    .setMasterConnectionPoolSize(10)
    .setSlaveConnectionPoolSize(10);    // 从节点连接池
//   ↑ 写走 Master，读走 Replica
```

- 写操作走 Master，读操作走 Replica
- Replica 可水平扩展提升读吞吐
- 可能出现短暂的主从延迟导致读到旧数据
- 适用场景：读多写少，如商品详情页

> **项目当前使用默认模式 A（仅主节点读写）**，因为写操作需要最新的库存数据。

### 三、如何切换到 Cluster 模式

#### 什么是 Cluster 模式

Redis Cluster 将数据**分片**存储到多个 Redis 节点上。每个节点只存一部分数据，节点间通过 Gossip 协议通信。

```
                 ┌──────────┐
                 │  Client  │
                 └────┬─────┘
                      │ 可连任意节点
        ┌─────────────┼─────────────┐
        ▼             ▼             ▼
   ┌─────────┐  ┌─────────┐  ┌─────────┐
   │ Master1 │  │ Master2 │  │ Master3 │     ← 3 个主节点，各管 1/3 数据
   │ 槽0-5460│  │5461-10922│  │10923-   │
   │         │  │         │  │16383    │
   └────┬────┘  └────┬────┘  └────┬────┘
        │            │            │
        ▼            ▼            ▼
   ┌─────────┐  ┌─────────┐  ┌─────────┐
   │ Replica1│  │ Replica2│  │ Replica3│     ← 每个主节点各一个副本
   └─────────┘  └─────────┘  └─────────┘

数据分布:
  商品1的缓存 → 计算 hash → 槽 12345 → Master2
  商品2的缓存 → 计算 hash → 槽 500   → Master1
  分布式锁    → 计算 hash → 槽 16000 → Master3
```

- 16384 个槽位（slot），均匀分给所有 Master
- key 通过 `CRC16(key) % 16384` 决定落在哪个槽
- Master 挂了 → 对应的 Replica 自动提升为 Master
- 最小配置：3 Master + 3 Replica = 6 个节点

#### Sentinel vs Cluster

| | Sentinel | Cluster |
|------|----------|---------|
| 数据分布 | 每个节点存全量数据 | 数据分片，每个节点存一部分 |
| 存储上限 | 等于单节点的内存上限 | 可水平扩展（加节点就行） |
| 读扩展 | 加 Replica | 加 Replica |
| 写扩展 | **无法扩展**（所有写走 Master） | **可扩展**（不同 key 写不同节点） |
| 架构复杂度 | 简单 | 较复杂 |

#### 步骤 1：Docker 部署 Redis Cluster

创建配置文件 `redis-cluster/redis-cluster.tmpl`：

```
port ${PORT}
bind 0.0.0.0
protected-mode no
cluster-enabled yes                          # 开启 Cluster
cluster-config-file nodes.conf               # 集群拓扑存储文件
cluster-node-timeout 5000                    # 节点超时时间
cluster-announce-ip 127.0.0.1               # 对外宣告宿主机 IP
cluster-announce-port ${PORT}
cluster-announce-bus-port 1${PORT}           # 集群总线端口 = 10000+端口
appendonly yes                               # AOF 持久化
```

**一键部署脚本**（`redis-cluster/start-cluster.sh`）：

```bash
#!/bin/bash
# 创建网络
docker network create redis-cluster-net 2>/dev/null

# 启动 6 个 Redis 节点
for port in 7000 7001 7002 7003 7004 7005; do
  mkdir -p $(pwd)/redis-cluster/node-${port}
  # 用模板生成实际配置
  PORT=${port} envsubst < $(pwd)/redis-cluster/redis-cluster.tmpl > $(pwd)/redis-cluster/node-${port}/redis.conf
  docker run -d --name redis-${port} --network redis-cluster-net \
    -p ${port}:${port} -p 1${port}:1${port} \
    -v $(pwd)/redis-cluster/node-${port}/redis.conf:/usr/local/etc/redis/redis.conf \
    redis:latest redis-server /usr/local/etc/redis/redis.conf
done

# 等待节点就绪
sleep 3

# 创建集群（3 主 3 从）
docker exec redis-7000 redis-cli --cluster create \
  127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 \
  127.0.0.1:7003 127.0.0.1:7004 127.0.0.1:7005 \
  --cluster-replicas 1 --cluster-yes

# 验证
docker exec redis-7000 redis-cli cluster info
docker exec redis-7000 redis-cli cluster nodes
```

**停止和清理**：
```bash
for port in 7000 7001 7002 7003 7004 7005; do
  docker rm -f redis-${port}
done
```

#### 步骤 2：项目 application.yml 配置

```yaml
spring:
  redis:
    # ===== Cluster 模式 =====
    cluster:
      nodes:
        - localhost:7000
        - localhost:7001
        - localhost:7002
        - localhost:7003
        - localhost:7004
        - localhost:7005
      max-redirects: 3          # 最大重定向次数（槽位不在当前节点时转发）
    password:
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
```

#### 步骤 3：Redisson Cluster 配置

```java
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useClusterServers()
                .addNodeAddress(
                        "redis://localhost:7000",
                        "redis://localhost:7001",
                        "redis://localhost:7002",
                        "redis://localhost:7003",
                        "redis://localhost:7004",
                        "redis://localhost:7005"
                )
                .setReadMode(ReadMode.MASTER)         // 默认仅主节点读
                .setSubscriptionMode(SubscriptionMode.MASTER)
                .setMasterConnectionPoolSize(10)
                .setSlaveConnectionPoolSize(10)
                .setScanInterval(2000);               // 扫描集群拓扑间隔
        return Redisson.create(config);
    }
}
```

**Cluster 读写模式**：

```java
// 仅主节点读写（默认）
.setReadMode(ReadMode.MASTER)

// 主写从读（读写分离，可能读到旧数据）
.setReadMode(ReadMode.SLAVE)

// 优先从节点读，从节点不可用时走主节点
.setReadMode(ReadMode.MASTER_SLAVE)
```

#### 步骤 4：验证 Cluster

```bash
# 查看集群拓扑
docker exec redis-7000 redis-cli -p 7000 cluster nodes

# 查看槽位分布
docker exec redis-7000 redis-cli -p 7000 cluster slots

# 测试分片
docker exec redis-7000 redis-cli -p 7000 set key1 "value1"
docker exec redis-7000 redis-cli -p 7000 set key2 "value2"
# 两个 key 可能落在不同节点上

# 故障转移测试
docker stop redis-7000                    # 模拟 Master1 挂了
docker exec redis-7003 redis-cli -p 7003 cluster nodes  # 看 7003 是否成为新 Master
docker start redis-7000                   # 恢复 → 自动成为新 Replica
```

### 四、三种模式切换速查表

| 切换目标 | application.yml | RedissonConfig.java 核心方法 |
|----------|-----------------|------------------------------|
| **单机** | `redis.host: localhost`<br>`redis.port: 6379` | `.useSingleServer()`<br>`.setAddress("redis://localhost:6379")` |
| **Sentinel** | `redis.sentinel.master: mymaster`<br>`redis.sentinel.nodes: [26379,26380,26381]` | `.useSentinelServers()`<br>`.setMasterName("mymaster")`<br>`.addSentinelAddress(...)` |
| **Cluster** | `redis.cluster.nodes: [7000-7005]`<br>`redis.cluster.max-redirects: 3` | `.useClusterServers()`<br>`.addNodeAddress(...)` |

### 五、读写模式速查

| 模式 | Redisson 配置 | 适用场景 |
|------|--------------|----------|
| **仅主节点** | `ReadMode.MASTER` | 一致性要求高，如库存扣减 |
| **仅从节点** | `ReadMode.SLAVE` | 读多写少，可容忍延迟 |
| **优先从、回退主** | `ReadMode.MASTER_SLAVE` | 读多写少 + 高可用 |
| **Sentinel 主写从读** | `ReadMode.SLAVE` + `setSlaveConnectionPoolSize(N)` | 读负载均衡 |
| **Cluster 主写从读** | `ReadMode.SLAVE` | 大数据量 + 读写分离 |
