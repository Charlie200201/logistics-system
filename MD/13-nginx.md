# 13 — Nginx 反向代理

## 是什么

Nginx 是一个**高性能的 HTTP 服务器和反向代理**。在本项目中，Nginx 是整个系统的**第一道门**，所有请求先到 Nginx，Nginx 再转发给 Gateway。

## 为什么需要

### 为什么 Gateway 前面还要加 Nginx

```
                Nginx 做的           Gateway 做的
                ─────────           ────────────
静态资源 (HTML/CSS/JS)  ✓ 高效         ✗ 不擅长
JWT 校验              ✗ 不擅长        ✓ 擅长
动态路由（配合Nacos）   ✗ 不支持        ✓ 支持
限流（令牌桶）         ✗ 有限的         ✓ 灵活的 Java 代码
底层流量控制（IP黑名单） ✓ 擅长          ✗ 不擅长
```

两者各司其职，配合使用。

## 核心概念

### 反向代理是什么

```
正向代理（你翻墙用的）:
  你的电脑 → 代理服务器 → Google
  你主动通过代理去访问外部资源

反向代理（Nginx 做的）:
  用户 → Nginx → 你的后端服务
  用户只知道 Nginx 的地址，不知道后端的真实地址
```

### 请求链路

```
浏览器
  │  http://localhost:8080/api/users/login
  ▼
Nginx (Docker 容器, 80 端口, 映射到宿主机 8080)
  │  匹配规则 /api/ → 转发到 Gateway
  ▼
Gateway (宿主机 8085)
  │  匹配规则 /api/users/** → 转发到 user-service
  ▼
user-service (宿主机 8081)
  │  处理请求
  └──→ 返回响应
```

## 项目中的代码

### Nginx 配置文件

**文件位置**: `nginx/nginx.conf`

```nginx
worker_processes 1;                    # 工作进程数

events {
    worker_connections 1024;           # 每个进程最大连接数
}

http {
    include       mime.types;          # 文件类型映射
    default_type  application/octet-stream;
    sendfile      on;
    keepalive_timeout 65;

    server {
        listen 80;                     # 监听 80 端口
        server_name localhost;

        # ① 静态资源 → 直接发给 Gateway 的静态资源
        location /static/ {
            proxy_pass http://host.docker.internal:8085/static/;
            # host.docker.internal = Docker 容器的宿主机地址
        }

        # ② API 请求 → 转发给 Gateway
        location /api/ {
            proxy_pass http://host.docker.internal:8085/api/;
            proxy_set_header Host $host;                         # 传递原始域名
            proxy_set_header Authorization $http_authorization;  # 传递 Token
        }

        # ③ 其他请求 → 也发给 Gateway（如 doc.html）
        location / {
            proxy_pass http://host.docker.internal:8085/;
        }
    }
}
```

### 关键配置解释

| 配置项 | 含义 |
|--------|------|
| `listen 80` | Nginx 监听容器内的 80 端口 |
| `proxy_pass` | 转发目标地址 |
| `host.docker.internal` | Docker 容器的宿主机地址 |
| `proxy_set_header Host` | 传递原始请求域名 |
| `proxy_set_header Authorization` | 传递认证 Token |

### Nginx 容器端口映射

```
宿主机 8080 → 容器内 80
  │
  │ 为什么不是 80 → 80？
  │ Windows 上 80 端口可能被占用（IIS 等）
  │ 映射到 8080 避免端口冲突
```

## Nginx 配置更新方法

配置文件修改后，需要更新 Nginx 容器：

```bash
# 方式一：复制文件到容器并重载
docker cp nginx/nginx.conf nginx:/etc/nginx/nginx.conf
docker exec nginx nginx -s reload

# 方式二：重新创建容器
docker stop nginx && docker rm nginx
docker run -d --name nginx \
  -p 8080:80 \
  -v D:\work\web-ai-code\logistics-system\nginx\nginx.conf:/etc/nginx/nginx.conf \
  nginx:latest
```

## 配置说明

### 为什么用 `host.docker.internal`

```
Nginx 在 Docker 容器内
Gateway 在宿主机上（端口 8085）

容器内的 localhost → 容器自己
宿主机上的 Gateway → 需要 host.docker.internal 来访问

host.docker.internal → Docker Desktop 提供的特殊域名
                     → 让容器能访问宿主机
```

## 验证方法

### 1. 确认 Nginx 运行

```bash
docker ps | grep nginx
# nginx   nginx:latest   0.0.0.0:8080->80/tcp
```

### 2. 通过 Nginx 访问

```bash
# 直接通过 8080 访问（Nginx 入口）
curl http://localhost:8080/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'

# 和直接访问 Gateway 一样的结果
curl http://localhost:8085/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'
```

### 3. 查看 Nginx 日志

```bash
docker logs nginx --tail 20
```

## 常见问题

**Q: 从 8080 访问返回 502？**
A: Gateway 没启动（端口 8085）。Nginx 转发的目标不可达。

**Q: `host.docker.internal` 解析不了？**
A: Linux 上 Docker 不支持这个域名，需要改为宿主机 IP 或用 `--add-host` 参数。

**Q: 为什么要用 Nginx 做入口而不直接用 Gateway？**
A: Nginx 更快（C 语言实现），更稳定。Gateway 是 Java 应用，启动慢，占用内存大。Nginx 做第一层挡在最前面。
