# 05 — JWT 身份认证

## 是什么

JWT（JSON Web Token）是一种**无状态的身份认证令牌**。用户登录成功后，服务端给用户签发一个 Token（一段加密字符串），后续请求带着这个 Token 就能证明身份。

Token 长这样：
```
eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInVzZXJuYW1lIjoidGVzdHVzZXIifQ.xXxXxXxXxXxXxXx
```

## 为什么用 JWT 而不是 Session

### Session 方式（传统）

```
用户登录 → 服务器创建 Session → 存服务器内存中 → 返回 SessionID（一个随机字符串）
后续请求 → 带 SessionID → 服务器查内存找对应的 Session

问题：
- 服务器重启，Session 全部丢失
- 多台服务器时，Session 不共享（需要 Redis 存 Session）
- 占用服务器内存
```

### JWT 方式（本项目使用）

```
用户登录 → 服务器生成 Token（里面包含用户信息 + 签名）→ 返回给客户端
后续请求 → 客户端带 Token → 服务器验证签名即可，不需要查数据库

优势：
- 服务器不需要存任何东西（无状态）
- 多台服务器都可以验证 Token（不需要共享存储）
- 适合分布式系统
```

## 核心概念

### Token 结构

JWT 由三部分组成，用 `.` 分隔：

```
Header.Payload.Signature

Header:   {"alg":"HS256","typ":"JWT"}       ← 用什么算法签名
Payload:  {"userId":1,"username":"test"}    ← 存的数据（不加密，不要放密码！）
Signature: HMACSHA256(Header+"."+Payload, secret) ← 用密钥签名
```

### 验证原理

```
服务端验证 Token 时：
① 用同样的密钥对 Header+Payload 重新计算签名
② 比较计算出的签名和 Token 中的签名
③ 一致 → Token 没被篡改 → 信任 Payload 中的数据
④ 不一致 → Token 被篡改过 → 拒绝
```

## 项目中的代码

### 1. JWT 工具类

**文件位置**: `common/src/main/java/com/logistics/common/utils/JwtUtils.java`

```java
public class JwtUtils {

    // 签名密钥（生产环境不能硬编码，要放配置中心）
    private static final String SECRET = "logistics-system-secret-key-2024-ai-code-generation";
    private static final long EXPIRE = 1000 * 60 * 60 * 24; // 24小时

    // ① 生成 Token
    public static String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);            // 把用户信息放进 Payload
        claims.put("username", username);

        return Jwts.builder()
                .setClaims(claims)               // Payload 数据
                .setIssuedAt(new Date())         // 签发时间
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE)) // 过期时间
                .signWith(SignatureAlgorithm.HS256, SECRET)  // 用 HS256 + 密钥签名
                .compact();                      // 生成最终字符串
    }

    // ② 解析 Token
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .setSigningKey(SECRET)           // 用同一个密钥解析
                .parseClaimsJws(token)           // 解析 + 验证签名
                .getBody();                      // 取出 Payload
    }

    // ③ 验证 Token 是否有效
    public static boolean validateToken(String token) {
        try {
            parseToken(token);                   // 能解析成功 = 有效
            return true;
        } catch (Exception e) {
            return false;                        // 签名不对 / 已过期 / 格式错误
        }
    }

    // ④ 从 Token 中获取用户 ID
    public static Long getUserId(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    // ⑤ 从 Token 中获取用户名
    public static String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }
}
```

### 2. 登录时生成 Token

**文件位置**: `user-service/src/main/java/com/logistics/user/service/impl/UserServiceImpl.java`

```java
@Override
public String login(String username, String password) {
    // 查用户
    LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(User::getUsername, username);
    User user = this.getOne(wrapper);
    if (user == null) {
        throw new BusinessException(ResultCode.USER_NOT_FOUND);
    }

    // 校验密码（MD5 加密后比对）
    String md5Password = DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8));
    if (!md5Password.equals(user.getPassword())) {
        throw new BusinessException(ResultCode.PASSWORD_ERROR);
    }

    // 生成 JWT Token
    String token = JwtUtils.generateToken(user.getId(), user.getUsername());
    log.info("用户登录成功: username={}, userId={}", username, user.getId());
    return token;
}
```

### 3. Gateway 统一校验 Token

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/filter/JwtAuthGlobalFilter.java`

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();

    // 白名单：登录和注册不需要 Token
    if (path.equals("/api/users/login") || path.equals("/api/users/register")) {
        return chain.filter(exchange);
    }

    // 从 Header 中取 Token
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        return unauthorized(exchange, "未提供有效的认证Token");  // 返回 401
    }

    String token = authHeader.substring(7);     // 去掉 "Bearer " 前缀
    if (!JwtUtils.validateToken(token)) {
        return unauthorized(exchange, "Token无效或已过期");  // 返回 401
    }

    // Token 有效，把用户信息加到请求头传给下游服务
    Long userId = JwtUtils.getUserId(token);
    String username = JwtUtils.getUsername(token);
    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
            .header("X-UserId", userId.toString())
            .header("X-Username", username)
            .build();

    return chain.filter(exchange.mutate().request(mutatedRequest).build());
}
```

### 4. 前端如何传递 Token

```bash
# ① 先登录获取 Token
curl -X POST http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'

# 返回: {"code":200,"data":{"token":"eyJhbGciOi..."}}

# ② 后续请求在 Header 中带上 Token
curl -X GET http://localhost:8080/api/orders/1 \
  -H "Authorization: Bearer eyJhbGciOi..."
```

## 完整的认证流程

```
① 用户登录
   POST /api/users/login {username, password}
   → user-service 验证密码
   → 生成 JWT Token（含 userId, username，24 小时过期）
   → 返回 {"code":200, "data":{"token":"eyJ..."}}

② 后续请求
   GET /api/orders/1
   Header: Authorization: Bearer eyJ...

   → Gateway 拦截
   → 检查白名单：/api/orders/1 不在白名单中
   → 从 Header 取 Token
   → JwtUtils.validateToken(token) → 验证通过
   → 从 Token 中提取 userId=1, username=testuser
   → 把 X-UserId: 1, X-Username: testuser 加到请求头
   → 路由转发到 order-service

③ order-service 收到请求
   → 可以从请求头中获取 X-UserId 和 X-Username
   → 知道是哪个用户在操作
```

## 安全要点

| 要点 | 说明 |
|------|------|
| **密钥保密** | SECRET 不能提交到 Git，生产环境放配置中心 |
| **密码加密存储** | 本项目用 MD5，生产环境建议用 BCrypt |
| **Token 过期** | 本项目 24 小时过期，降低泄露风险 |
| **不在 Payload 放敏感信息** | Payload 只是 Base64 编码，不是加密！任何人都能解码 |
| **HTTPS** | 生产环境必须用 HTTPS，防止 Token 在网络传输中被劫持 |

## 验证方法

```bash
# 1. 不带 Token 访问 → 应返回 401
curl http://localhost:8085/api/products/1
# {"code":401,"message":"未提供有效的认证Token"}

# 2. 带错误 Token → 应返回 401
curl http://localhost:8085/api/products/1 \
  -H "Authorization: Bearer invalid_token"
# {"code":401,"message":"Token无效或已过期"}

# 3. 登录 → 获取 Token
curl -X POST http://localhost:8085/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'

# 4. 用正确 Token → 应返回数据
curl http://localhost:8085/api/products/1 \
  -H "Authorization: Bearer <正确的token>"
```

## 常见问题

**Q: Token 过期了怎么办？**
A: 前端需要引导用户重新登录。或者用 Refresh Token 机制自动续期。

**Q: 用户退出登录要怎么处理？**
A: JWT 无法"撤销"已签发的 Token。常见做法：① 客户端删除 Token；② 把退出用户的 Token 加入黑名单（Redis 短期存储）。

**Q: 多台服务器怎么共享 Token 验证？**
A: 不需要共享！每台服务器用同样的 SECRET 就能独立验证。这就是 JWT 最大的优势。
