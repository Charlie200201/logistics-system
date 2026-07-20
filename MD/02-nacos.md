# 02 — Nacos 服务注册发现 + 配置中心

## 是什么

Nacos 是阿里巴巴开源的服务治理平台，就像微服务世界的**电话簿 + 公告栏**：

- **服务注册发现**：每个服务告诉 Nacos "我在哪里"，需要调用时问 Nacos "谁在哪里"
- **配置中心**：配置文件统一管理，修改配置无需重启

## 为什么需要

### 没有 Nacos 时

```
服务 A 想调用服务 B，需要写死 B 的地址：
http://192.168.1.100:8081/api/users/1

问题：
- B 的 IP 变了怎么办？→ 改代码，重新部署
- B 启动了多个实例（8081, 8082, 8083）？→ 怎么选？
- B 挂了怎么办？→ A 一直调失败
```

### 有了 Nacos 后

```
服务 A 调用 → 问 Nacos: "user-service 在哪？"
                         ↓
Nacos 回答: "user-service 有 3 个实例: 192.168.1.100:8081, 192.168.1.101:8081, ..."
                         ↓
服务 A 选择一个实例调用（负载均衡）
如果某个实例挂了，Nacos 自动从列表中移除
```

## 核心概念

### 注册流程

```
服务启动
    │
    ├─→ ① 向 Nacos 注册: "我是 user-service，在 192.168.1.5:8081"
    │
    ├─→ ② 定期发送心跳（默认 5 秒一次）: "我还活着"
    │
    └─→ ③ 服务关闭时取消注册
```

### Nacos 如何判断服务是否存活？

```
健康检查机制:
  服务每 5 秒向 Nacos 发心跳
  如果 15 秒没收到心跳 → 标记为"不健康"
  如果 30 秒没收到心跳 → 从列表中移除
```

## 项目中的代码

### 步骤 1：引入依赖

每个服务（除 gateway-service）的 `pom.xml` 都有：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

### 步骤 2：配置 bootstrap.yml

**文件位置**: `user-service/src/main/resources/bootstrap.yml`

```yaml
spring:
  application:
    name: user-service                    # ① 服务名称
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848       # ② Nacos 地址
      config:
        server-addr: localhost:8848       # ③ 配置中心地址
        file-extension: yaml              # ④ 配置文件格式
```

5 个服务都在 `bootstrap.yml` 中配置了自己的名字：
- `user-service`
- `product-service`
- `order-service`
- `logistics-service`
- `gateway-service`

### 步骤 3：启动类加注解

**每个服务的启动类**：

```java
@SpringBootApplication
@EnableDiscoveryClient    // ← 这个注解让服务能被 Nacos 发现
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

### 步骤 4：Feign 通过服务名调用（结合 OpenFeign）

order-service 调用 user-service，不需要知道 IP：

```java
@FeignClient(name = "user-service")    // ← 服务名，不是 IP！
public interface UserFeignClient {
    @GetMapping("/api/users/{id}")
    Result<Map<String, Object>> getUserById(@PathVariable("id") Long id);
}
```

Feign 的调用流程：
```
FeignClient 要调 user-service
    → 去 Nacos 查 "user-service" 的实例列表
    → Nacos 返回: [192.168.1.100:8081, 192.168.1.101:8081]
    → 负载均衡选一个实例
    → 发起 HTTP 调用
```

## 配置说明

Nacos 配置是分层的：

```yaml
# bootstrap.yml（先加载，配置 Nacos 连接）
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      config:
        server-addr: localhost:8848

# application.yml（后加载，服务自己的业务配置）
server:
  port: 8081
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/db_user
```

**加载顺序**：bootstrap.yml → 拉取 Nacos 远程配置 → application.yml

**为什么分两层？**
- `bootstrap.yml` 的配置在应用上下文创建之前就加载了，这样才能连接 Nacos
- `application.yml` 在应用上下文创建之后加载

## 项目中的 Nacos 配置实际运行状态

Nacos 容器运行在 Docker 中，端口映射为 `8848:8848`：

```bash
docker ps | grep nacos
# nacos   nacos/nacos-server:v2.1.0-slim   0.0.0.0:8848->8848/tcp
```

## 验证方法

### 1. 确认 Nacos 运行

浏览器访问：http://localhost:8848/nacos  登录：nacos/nacos

### 2. 确认服务注册成功

启动一个服务后，在 Nacos 页面：
- 服务管理 → 服务列表
- 应该看到 `user-service` 在线，实例数 = 1

### 3. 确认服务发现工作

启动 order-service 后，看日志中是否有 Feign 调用成功的记录：

```
用户验证通过: userId=1
商品查询成功: productId=1, price=5999.00
```

这说明 order-service 通过 Nacos 成功找到了 user-service 和 product-service。

## 常见问题

**Q: Nacos 连不上怎么办？**
A: 确认 Docker 容器运行中：`docker ps | grep nacos`。确认 8848 端口没有被占用。

**Q: 服务注册了但显示"离线"？**
A: 检查 `spring.cloud.nacos.discovery.server-addr` 配置是否正确。确认网络能通。

**Q: 为什么要用 Nacos 而不是 Eureka？**
A: Nacos 同时支持服务发现 + 配置中心，Eureka 只做服务发现。Nacos 是阿里巴巴大规模使用验证过的。
