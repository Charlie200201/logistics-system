# 智能物流追踪系统

基于 Spring Cloud Alibaba 的微服务物流追踪系统，全面整合 Docker 容器化中间件。

## Docker 容器一览

| 容器 | 宿主机端口 | 用途 |
|------|-----------|------|
| nacos | 8848 | 服务注册发现 + 配置中心 |
| redis | 6379 | 缓存 + 分布式锁 |
| rabbitmq | 5672 / 15672 | 消息队列 |
| seata | 8091 | 分布式事务协调 |
| xxl-job | 8088→8080 | 定时任务调度 |
| elasticsearch | 9200 / 9300 | 网关日志存储 |
| nginx | 8080→80 | 反向代理入口 |
| kibana | 5601 | EFK 日志可视化 |
| filebeat | - | 日志采集传送 |
| jenkins | 8089→8080, 50000 | CI/CD 流水线 |
| gogs | 3000, 3022→22 | Git 代码仓库 |
| mysql | 3306 | 数据持久化（宿主机） |

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.6.13 | 基础框架 |
| Spring Cloud | 2021.0.5 | 微服务治理 |
| Spring Cloud Alibaba | 2021.0.5.0 | Nacos + Sentinel + Seata |
| Nacos | 2.1.0 | 注册中心 + 配置中心 |
| OpenFeign | - | 服务间远程调用 |
| Spring Cloud Gateway | - | API 网关 (8085) |
| Sentinel | - | 令牌桶限流 |
| Seata | AT 模式 | 分布式事务 |
| RabbitMQ | 3.8 | 消息队列 |
| Redis | latest | 缓存 + 分布式锁 |
| Elasticsearch | 7.12.1 | 网关日志收集 + EFK 日志存储 |
| XXL-JOB | 2.3.0 | 定时任务调度 |
| Filebeat | 7.12.1 | 日志采集传送 |
| Kibana | 7.12.1 | 日志可视化面板 |
| MySQL | 8.0 | 数据持久化 |
| MyBatis-Plus | 3.5.2 | ORM |
| Redisson | 3.17.7 | 分布式锁 |
| JJWT | 0.9.1 | JWT 认证 |
| Knife4j | 3.0.3 | API 文档 |
| Nginx | latest | 反向代理入口 |
| Jenkins | 2.361.1 | CI/CD 流水线 |
| Gogs | 0.12 | Git 代码仓库 |

## 微服务模块

| 模块 | 端口 | 数据库 | 说明 | 关键依赖/基础设施 |
|------|------|--------|------|-------------------|
| user-service | 8081 | db_user | 用户注册、登录、Token 验证 | Nacos, MySQL |
| product-service | 8082 | db_product | 商品 CRUD、缓存、库存扣减（Seata分支） | Nacos, MySQL, Redis Sentinel, Redisson, Seata TC |
| order-service | 8083 | db_order | 订单创建（Seata全局事务入口）、查询 | Nacos, MySQL, RabbitMQ, Seata TC, Sentinel |
| logistics-service | 8084 | db_logistics | 物流单生成、轨迹跟踪、XXL-JOB定时任务 | Nacos, MySQL, RabbitMQ, XXL-JOB, Sentinel |
| gateway-service | 8085 | - | 统一网关、JWT 鉴权、Sentinel限流、ES日志 | Nacos, Elasticsearch, Sentinel |

## 微服务详细说明

### common（公共模块）
所有微服务共享的底层库，非独立服务：
- **`Result<T>`**：统一 API 响应体（code + message + data）
- **`ResultCode`**：标准错误码枚举
- **`BusinessException`**、**`GlobalExceptionHandler`**：业务异常 + 全局 `@RestControllerAdvice` 处理
- **`JwtUtils`**：JWT 生成/解析/校验（HS256，24h 过期，共享密钥）

### user-service（用户服务，端口 8081）
纯粹的用戶身份管理服务，最轻量，无外部依赖调用：
- 注册：MD5 密码加密存储
- 登录：验证凭证后返回 JWT
- 对外暴露 `/api/users/{id}` 查询接口供其他服务通过 Feign 调用
- 对外暴露 `/api/users/verify` Token 校验接口供网关内部调用
- **不调用其他服务，不参与分布式事务，无缓存/消息队列**

### product-service（商品服务，端口 8082）
商品管理 + 库存扣减，充当 Seata AT 分支事务参与者：
- 商品 CRUD，查询结果缓存到 Redis
- **Redisson 分布式锁**：`deductStock()` 加锁防止超卖
- **Seata AT 分支事务**：扣库存作为 order-service 全局事务的分支，`undo_log` 表支持自动回滚
- Redis Sentinel 集群（1 主 1 从 3 哨兵）
- 不调用其他服务

### order-service（订单服务，端口 8083）
订单生命周期管理，**Seata 全局事务发起方**，系统核心编排节点：
- `createOrder()` 标注 `@GlobalTransactional`：验证用户 → 查商品价格 → 扣库存（Redisson 锁）→ 保存订单，任意环节失败则 Seata AT 自动回滚
- **Feign 调用**：→ user-service（验证用户）、→ product-service（查价格、扣库存）
- **RabbitMQ 发布**：订单创建成功后，向 `logistics.exchange` 发送消息（routing key: `logistics.create`），异步触发物流单生成
- **Sentinel 熔断**：user/product 的 Feign 调用错误率 >50% 时自动降级，返回兜底响应

### logistics-service（物流服务，端口 8084）
物流单生成 + 轨迹模拟 + 定时任务调度：
- **RabbitMQ 消费**：`LogisticsMessageListener` 监听 `logistics.queue`，收到消息后生成物流单号（`LOG`+时间戳+随机数），初始状态 `PENDING`，初始位置 "仓库"
- **轨迹模拟**：`@Scheduled(fixedDelay=30000)` 每 30 秒扫描 `PENDING`/`IN_TRANSIT` 状态的物流单，随机分配城市并插入轨迹记录
- **XXL-JOB 定时任务**（executor 端口 9999）：
  - `OrderTimeoutJob`（每 5 分钟）：查过期订单 → 取消 → 恢复库存
  - `DailyStatisticsJob`（每天 00:30）：统计前一日订单数据
- **Feign 调用**：→ order-service（查过期订单、取消订单、统计）、→ product-service（恢复库存）

### gateway-service（网关服务，端口 8085）
系统唯一入口，所有横切关注点集中处理：
- **路由转发**：`/api/users/**` → `lb://user-service`、`/api/products/**` → `lb://product-service`、`/api/orders/**` → `lb://order-service`、`/api/logistics/**` → `lb://logistics-service`
- **JWT 鉴权**：`JwtAuthGlobalFilter`（优先级 -100）拦截所有请求校验 Token，白名单：`/login`、`/register`、Swagger/Knife4j 路径；通过后注入 `X-UserId`、`X-Username` 请求头给下游
- **Sentinel 令牌桶限流**：`/api/orders/**` 限流 50 QPS 持续、100 突发、500ms 队列超时，超限返回 HTTP 429
- **ES 访问日志**：`GatewayLogFilter` 异步将请求路径、方法、用户ID、状态码、耗时写入 Elasticsearch（索引按天：`gateway-logs-yyyy-MM-dd`）
- 自身暴露 `/api/logs/search` 接口查询 ES 日志
- 无数据库，排除 `DataSourceAutoConfiguration`

## 服务间通信方式

| 方式 | 场景 | 调用方向 |
|------|------|----------|
| **OpenFeign（同步 REST）** | 请求-响应式远程调用 | order → user、order → product、logistics → order、logistics → product |
| **RabbitMQ（异步消息）** | 订单创建后触发物流，解耦 + 最终一致性 | order → logistics |
| **Spring Cloud Gateway（反向代理）** | 外部流量统一入口，Nacos 服务发现 + 负载均衡 | Nginx → Gateway → 各业务服务 |
| **XXL-JOB（HTTP 回调）** | 定时任务统一调度 | XXL-JOB Admin → logistics executor → Feign 调用其他服务 |

### 下单核心流程

```
1. Client POST /api/orders → Nginx(:8080) → Gateway(:8085)
2. Gateway: JWT 鉴权 → Sentinel 限流 → 路由至 order-service(:8083)
3. order-service.createOrder() [@GlobalTransactional]:
   a. Feign → user-service: 验证用户存在
   b. Feign → product-service: 查询商品价格
   c. Feign → product-service: 扣减库存 (Redisson 分布式锁, Seata AT 分支)
   d. 保存订单 (Seata AT 分支)
   e. 事务提交后 → RabbitMQ: 发送物流创建消息
4. logistics-service.LogisticsMessageListener: 消费消息, 生成物流单
5. logistics-service.LogisticsTrackTask: 每30秒模拟轨迹更新
```

## 系统架构

```
用户请求 → Nginx (8080) → Gateway (8085)
                              │
              ┌───────────────┼───────────────┐
              │               │               │
         JWT鉴权      Sentinel限流     ES日志记录
              │               │               │
              └───────────────┼───────────────┘
                              │
                         Nacos 注册发现
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
   user-service        product-service        order-service
   (8081)              (8082)                 (8083)
                              │                     │
                        Redis 缓存               Seata AT
                      Redisson 分布式锁          全局事务
                              │                     │
                              │              RabbitMQ 消息
                              │                     │
                              └──────┬──────────────┘
                                     │
                              logistics-service (8084)
                                     │
                               XXL-JOB 定时任务
                              (超时取消 + 日统计)
```

## 启动前准备

所有容器（除 MySQL）均已通过 Docker 部署，确认以下容器运行中：

```bash
docker ps
# 应有: nacos, redis, rabbitmq, seata, xxl-job, elasticsearch, nginx, jenkins, gogs
```

### 1. MySQL（宿主机）
- 端口：3306，用户名：root，密码：123456
- 执行 `schema.sql` 初始化数据库（含 Seata undo_log 表）

### 2. 数据库初始化
```bash
mysql -u root -p123456 < schema.sql
```

### 3. Nacos
- 地址：http://localhost:8848/nacos （nacos/nacos）

### 4. XXL-JOB 调度中心
- 地址：http://localhost:8088/xxl-job-admin （admin/123456）
- 在"执行器管理"中添加执行器 `logistics-executor`（自动注册）
- 在"任务管理"中添加任务：
  - `orderTimeoutCancelJob`：Cron `0 */5 * * * ?`（每5分钟）
  - `dailyOrderStatisticsJob`：Cron `0 30 0 * * ?`（每天00:30）

### 5. Seata
- 地址：localhost:8091，事务组：logistics_tx_group

### 6. Elasticsearch
- 地址：http://localhost:9200，Kibana 如安装可访问 http://localhost:5601

### 7. Nginx 配置更新
将 nginx/nginx.conf 复制到 nginx 容器：
```bash
docker cp nginx/nginx.conf nginx:/etc/nginx/nginx.conf
docker exec nginx nginx -s reload
```

### 8. Gogs 仓库初始化
```bash
# 访问 http://localhost:3000 完成 Gogs 初始化
# 创建仓库 logistics-system
git init
git add .
git commit -m "init: 智能物流追踪系统"
git remote add origin http://localhost:3000/test/logistics-system.git
git push -u origin main
```

### 9. Jenkins
- 地址：http://localhost:8089 （初始密码通过 `docker logs jenkins` 查看）
- 安装推荐插件，创建 Pipeline 任务，指向 Gogs 仓库
- Pipeline 脚本使用项目根目录的 `Jenkinsfile`

## 启动顺序

```bash
# 0. 确认所有 Docker 容器运行中
docker ps

# 1. 编译项目
cd logistics-system-parent
mvn clean install -DskipTests

# 2. 逐个启动服务
cd user-service && mvn spring-boot:run        # 8081
cd product-service && mvn spring-boot:run     # 8082
cd order-service && mvn spring-boot:run       # 8083
cd logistics-service && mvn spring-boot:run   # 8084
cd gateway-service && mvn spring-boot:run     # 8085
```

各服务启动后，检查 Nacos http://localhost:8848/nacos ，应看到 5 个服务在线（含 gateway-service）。

## API 接口文档

通过 Nginx 访问：http://localhost:8080/doc.html
各服务直接访问：
- user-service: http://localhost:8081/doc.html
- product-service: http://localhost:8082/doc.html
- order-service: http://localhost:8083/doc.html
- logistics-service: http://localhost:8084/doc.html
- gateway-service: http://localhost:8085/doc.html

## 测试接口（通过 Nginx → Gateway）

所有接口通过 Nginx 入口访问：http://localhost:8080

### 1. 用户注册（无需 Token）
```bash
curl -X POST http://localhost:8080/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456","phone":"13800138000"}'
```

### 2. 用户登录（无需 Token）
```bash
curl -X POST http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'
```
响应：`{"code":200, "message":"success", "data":{"token":"eyJ..."}}`

### 3. 查询商品
```bash
curl -X GET http://localhost:8080/api/products/1 \
  -H "Authorization: Bearer <token>"
```

### 4. 创建订单（Seata 全局事务 + Sentinel 令牌桶限流）
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"userId":1,"productId":1,"quantity":2,"address":"北京市朝阳区xxx"}'
```

### 5. 查询订单
```bash
curl -X GET http://localhost:8080/api/orders/1 \
  -H "Authorization: Bearer <token>"
```

### 6. 查询物流
```bash
# 通过物流单号
curl -X GET http://localhost:8080/api/logistics/LOG20260714120000xxxx \
  -H "Authorization: Bearer <token>"

# 通过订单ID
curl -X GET http://localhost:8080/api/logistics/order/1 \
  -H "Authorization: Bearer <token>"
```

### 7. 查询网关日志（ES）
```bash
curl -X GET "http://localhost:8080/api/logs/search?keyword=orders&startTime=2026-07-14T00:00:00&endTime=2026-07-14T23:59:59"
```

### 8. 静态页面
浏览器打开：http://localhost:8080/static/order.html

## 核心特性说明

### Seata 分布式事务（AT 模式）
- order-service 的 `createOrder()` 标注 `@GlobalTransactional`
- product-service 的 `deductStock()` 作为分支事务参与
- 事务组：`logistics_tx_group`，TC Server: localhost:8091
- undo_log 表已包含在 schema.sql 中

### XXL-JOB 定时任务
- 执行器：`logistics-executor`（端口 9999），注册在 logistics-service
- 任务1：`orderTimeoutCancelJob` — 每5分钟扫描超时30分钟的待支付订单，自动取消并恢复库存
- 任务2：`dailyOrderStatisticsJob` — 每天00:30统计前一天订单数据

### Sentinel 令牌桶限流
- 网关对 `/api/orders/**` 路径限流
- 令牌桶容量：100，每秒新增：50，超时：500ms
- 限流返回：`{"code": 429, "message": "系统繁忙，请稍后再试"}`

### Elasticsearch 网关日志
- 每次请求记录：请求时间、路径、方法、用户ID、状态码、耗时(ms)
- 索引：`gateway-logs-yyyy-MM-dd`（按天分索引）
- 查询接口：`GET /api/logs/search`
- 同时作为 EFK 集中日志存储，接收 Filebeat 采集的应用日志

### Nginx 反向代理
- `/static/` → gateway 静态资源
- `/api/` → gateway API
- 配置文件：`nginx/nginx.conf`

### Jenkins CI/CD 流水线
- Checkout → Build → Test → Docker Build → Deploy
- Pipeline 脚本：`Jenkinsfile`

### Gogs 代码托管
- 仓库地址：http://localhost:3000
- `.gitignore` 排除 target/、.idea/、*.log 等

## EFK 日志管理

系统通过 **Elasticsearch + Filebeat + Kibana (EFK)** 实现集中式日志管理。

### 架构

```
各服务 logback JSON 日志 ──┐
Nginx JSON 访问日志 ──────┼──► Filebeat ──► Elasticsearch ──► Kibana
XXL-JOB 日志 ─────────────┘
Gateway 访问日志 ───────────── 直写 ──────► Elasticsearch
```

### 日志流

| 日志来源 | 采集方式 | ES 索引 |
|----------|----------|---------|
| 5 个微服务应用日志 | Filebeat 采集 JSON 文件 | `app-logs-{service}-yyyy.MM.dd` |
| Gateway 访问日志 | GatewayLogFilter 直写 | `gateway-logs-yyyy-MM-dd` |
| Nginx 访问日志 | Filebeat 采集 JSON 文件 | `nginx-access-yyyy.MM.dd` |
| Nginx 错误日志 | Filebeat 采集 | `nginx-error-yyyy.MM.dd` |
| XXL-JOB 日志 | Filebeat 采集 | `xxl-job-yyyy.MM.dd` |

### 启动 EFK

```bash
# 启动 Filebeat + Kibana（ES 需已在运行）
docker-compose -f docker-compose-efk.yml up -d
```

### 访问 Kibana

http://localhost:5601

首次使用需要创建索引模式：
- Stack Management → Index Patterns → Create index pattern
- 输入 `app-logs-*` → 选择 `@timestamp` 作为时间字段
- 同样方式添加 `gateway-logs-*`（时间字段选 `requestTime`）

或通过 API 导入预配置：
```bash
curl -X POST "http://localhost:5601/api/saved_objects/_import?overwrite=true" \
  -H "kbn-xsrf: true" --form file=@elk/kibana/export.ndjson
```

### 各服务日志配置

每个服务通过 `logback-spring.xml` 同时输出：
- **控制台**：标准格式，方便本地开发
- **JSON 文件**：`logs/{service-name}.json`，Filebeat 采集源
- **滚动策略**：按天归档 + GZIP 压缩 + 保留 30 天
- **自定义字段**：`service` 字段标记日志来源

## 项目结构

```
logistics-system/
├── README.md
├── schema.sql                        # 数据库建表 + undo_log
├── Jenkinsfile                       # CI/CD 流水线
├── .gitignore
├── nginx/
│   └── nginx.conf                    # Nginx 反向代理配置
├── elk/
│   ├── filebeat/
│   │   └── filebeat.yml              # Filebeat 采集配置
│   └── kibana/
│       └── export.ndjson             # Kibana 索引模式导出
├── docker-compose-efk.yml            # EFK 日志栈编排
└── logistics-system-parent/
    ├── pom.xml                       # 父POM（依赖管理）
    ├── common/                       # 公共模块
    ├── user-service/                 # 用户服务 (8081)
    ├── product-service/              # 商品服务 (8082) + Seata分支
    ├── order-service/                # 订单服务 (8083) + Seata全局事务
    ├── logistics-service/            # 物流服务 (8084) + XXL-JOB
    └── gateway-service/              # API网关 (8085) + ES日志 + Sentinel
```
