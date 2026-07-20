# 01 — Spring Boot 基础框架

## 是什么

Spring Boot 是一个基于 Java 的**快速应用开发框架**。它帮你省去了 Spring 框架繁琐的 XML 配置，让你专注于写业务代码。

一句话理解：**约定大于配置**。Spring Boot 内置了大量默认配置，你只需要写少量代码就能启动一个完整的 Web 应用。

## 为什么需要

```java
// 没有 Spring Boot 的时候，你要写一堆 XML 来配置 Spring：
// applicationContext.xml、dispatcher-servlet.xml、web.xml...

// 有了 Spring Boot，只需要一个注解：
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

一个 main 方法就能启动一个服务，内置 Tomcat，不需要再部署 WAR 包。

## 核心概念

### 1. 启动类

项目中每个服务都有一个启动类，例如用户服务：

**文件位置**: `user-service/src/main/java/com/logistics/user/UserServiceApplication.java`

```java
package com.logistics.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.logistics.user", "com.logistics.common"})
@EnableDiscoveryClient  // 注册到 Nacos
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

- `@SpringBootApplication`：标记启动类，同时等同于 `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`
- `scanBasePackages`：告诉 Spring 扫描哪些包下的组件（本服务的包 + 公共模块包）
- `@EnableDiscoveryClient`：让服务能被 Nacos 发现

### 2. 三层架构（最重要！）

项目中每个服务都遵循这个分层结构：

```
Controller  →  接收 HTTP 请求，调用 Service（不写业务逻辑）
   ↓
Service     →  写业务逻辑（不写 SQL）
   ↓
Mapper      →  操作数据库（只负责数据存取）
```

**Controller 层示例**（user-service）：

**文件位置**: `user-service/src/main/java/com/logistics/user/controller/UserController.java`

```java
@Api(tags = "用户服务")
@RestController                     // 这个类的所有方法都返回 JSON
@RequestMapping("/api/users")       // URL 前缀
@RequiredArgsConstructor            // Lombok：自动生成构造函数注入
public class UserController {

    private final UserService userService;  // 注入 Service

    @PostMapping("/register")       // POST /api/users/register
    public Result<User> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String phone = body.get("phone");
        User user = userService.register(username, password, phone);
        user.setPassword(null);     // 返回时隐藏密码
        return Result.ok(user);
    }
}
```

**Service 层示例**（user-service）：

**文件位置**: `user-service/src/main/java/com/logistics/user/service/impl/UserServiceImpl.java`

```java
@Slf4j                              // Lombok：自动生成 log 变量
@Service                            // 这个类是 Service 组件
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    @Override
    public User register(String username, String password, String phone) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (this.getOne(wrapper) != null) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        // 加密密码并保存
        User user = new User();
        user.setUsername(username);
        user.setPassword(DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8)));
        user.setPhone(phone);
        this.save(user);            // MyBatis-Plus 提供的保存方法
        log.info("用户注册成功: username={}, userId={}", username, user.getId());
        return user;
    }
}
```

**Mapper 层示例**（user-service）：

**文件位置**: `user-service/src/main/java/com/logistics/user/mapper/UserMapper.java`

```java
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 继承 BaseMapper，自动拥有增删改查方法，不需要写任何代码
}
```

### 3. 配置文件

每个服务有两层配置文件：

- `bootstrap.yml`：最先加载，配置 Nacos 连接
- `application.yml`：服务自己的业务配置

**文件位置**: `user-service/src/main/resources/bootstrap.yml`

```yaml
spring:
  application:
    name: user-service            # 服务名称，注册到 Nacos 用的
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      config:
        server-addr: localhost:8848
```

**文件位置**: `user-service/src/main/resources/application.yml`

```yaml
server:
  port: 8081                      # 服务端口

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/db_user
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    type: com.alibaba.druid.pool.DruidDataSource    # 连接池

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true   # 下划线转驼峰：created_at → createdAt
```

### 4. 依赖注入（DI）

Spring 自动帮你管理对象的创建和依赖关系：

```java
@Service
@RequiredArgsConstructor    // 生成包含所有 final 字段的构造函数
public class UserServiceImpl {
    private final UserMapper userMapper;    // Spring 自动注入实例
    // 等价于在构造函数中：this.userMapper = new UserMapper();
    // 你不需要 new UserMapper()，Spring 帮你做
}
```

### 5. 公开模块（common）

所有服务共享的代码放在 `common` 模块中：

**文件位置**: `common/src/main/java/com/logistics/common/`

```
common/
├── result/
│   ├── Result.java          # 统一响应格式：{"code":200, "message":"success", "data":{...}}
│   └── ResultCode.java      # 错误码枚举：USER_NOT_FOUND(1001, "用户不存在")
├── exception/
│   ├── BusinessException.java       # 业务异常类
│   └── GlobalExceptionHandler.java  # 全局异常处理（@RestControllerAdvice）
└── utils/
    └── JwtUtils.java        # JWT Token 工具类
```

每个服务的启动类都需要扫描 common 包：
```java
@SpringBootApplication(scanBasePackages = {"com.logistics.user", "com.logistics.common"})
```

## 注解速查表

| 注解 | 作用 | 使用位置 |
|------|------|----------|
| `@SpringBootApplication` | 标记启动类 | 唯一的 main 类 |
| `@RestController` | 这个类返回 JSON | Controller |
| `@RequestMapping("/api/users")` | URL 路径前缀 | Controller 类 |
| `@GetMapping("/{id}")` | 处理 GET 请求 | Controller 方法 |
| `@PostMapping` | 处理 POST 请求 | Controller 方法 |
| `@PutMapping` | 处理 PUT 请求 | Controller 方法 |
| `@DeleteMapping` | 处理 DELETE 请求 | Controller 方法 |
| `@RequestBody` | 把请求 JSON 转为 Java 对象 | Controller 方法参数 |
| `@PathVariable` | 从 URL 中取参数 | Controller 方法参数 |
| `@RequestParam` | 从 URL 查询参数取值 | Controller 方法参数 |
| `@Service` | 标记业务逻辑类 | Service 实现类 |
| `@Mapper` | 标记数据库操作接口 | Mapper 接口 |
| `@Configuration` | 标记配置类 | 配置类 |
| `@Bean` | 方法返回值交给 Spring 管理 | 配置类中的方法 |

## 验证方法

启动用户服务：

```bash
cd user-service
mvn spring-boot:run
# 看到 "Tomcat started on port(s): 8081" 表示启动成功
```

访问 Knife4j 文档页面：http://localhost:8081/doc.html

## 常见问题

**Q: 启动报错 "port 8081 already in use"？**
A: 端口被占用。修改 application.yml 中的 `server.port`，或先停掉占用端口的程序。

**Q: 为什么我的 @Autowired 注入是 null？**
A: 注入的类必须被 Spring 管理（加了 @Service/@Component 注解），不能自己 new。

**Q: `RequiredArgsConstructor` 是什么？**
A: Lombok 的注解，自动生成包含所有 `final` 字段的构造函数。Spring 通过构造函数注入依赖，比 `@Autowired` 更推荐。
