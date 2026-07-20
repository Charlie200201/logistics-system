# 14 — Docker 容器化

## 是什么

Docker 是一种**容器化技术**。它把应用程序和它依赖的环境打包在一起，变成一个"容器"。这个容器可以在任何安装了 Docker 的机器上运行，不会出现"在我的电脑上是好的"这种问题。

简单理解：一个**轻量级的虚拟机**。

## 为什么需要

### 传统安装中间件的痛苦

```
安装 RabbitMQ 的步骤:
  1. 下载 Erlang 安装包（特定版本）→ 安装
  2. 配置 Erlang 环境变量
  3. 下载 RabbitMQ 安装包（特定版本）→ 安装
  4. 配置 RabbitMQ 环境变量
  5. 启动 RabbitMQ 服务
  6. 开启管理插件
  7. 配置用户和权限
  （耗时: 30 分钟以上，还可能遇到版本兼容问题）

用 Docker:
  docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3.8-management
  （一行命令，30 秒搞定，不需要装 Erlang）
```

### 一键启动所有中间件

本项目用 9 个 Docker 容器提供了完整的开发环境：

```bash
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Ports}}"
```

## 核心概念

### 镜像 vs 容器

```
镜像 (Image)    = 程序的打包文件（类比：Windows 安装 ISO）
容器 (Container) = 镜像运行起来的实例（类比：安装好的 Windows）

一个镜像可以启动多个容器
例如：用 redis:latest 镜像 → 启动了 5 个 Redis 容器（master + replica + 3 sentinel）
```

### 端口映射

```
docker run -p 8848:8848 nacos/nacos-server

宿主机的 8848 端口  ───映射──→  容器的 8848 端口

外部访问 localhost:8848 → 实际访问的是容器内的 Nacos
```

## 项目中的 Docker 容器

### 全部 9 个容器

```
┌──────────────────┬─────────────────────────┬──────────────────────┐
│ 容器名           │ 镜像                     │ 端口映射              │
├──────────────────┼─────────────────────────┼──────────────────────┤
│ nginx            │ nginx:latest             │ 8080→80              │
│ nacos            │ nacos/nacos-server:2.1.0 │ 8848→8848            │
│ redis-master     │ redis:latest             │ 6379→6379            │
│ redis-replica    │ redis:latest             │ 6380→6380            │
│ sentinel-1       │ redis:latest             │ 26379→26379          │
│ sentinel-2       │ redis:latest             │ 26380→26380          │
│ sentinel-3       │ redis:latest             │ 26381→26381          │
│ rabbitmq         │ rabbitmq:3.8-management  │ 5672, 15672          │
│ seata            │ seataio/seata-server     │ 8091→8091            │
│ xxl-job          │ xuxueli/xxl-job-admin    │ 8088→8080            │
│ elasticsearch    │ elasticsearch:7.12.1     │ 9200, 9300           │
│ jenkins          │ jenkins/jenkins:2.361.1  │ 8089→8080, 50000     │
│ gogs             │ gogs/gogs:0.12           │ 3000, 3022→22        │
└──────────────────┴─────────────────────────┴──────────────────────┘
```

### 为什么 Redis 有 5 个容器

因为配置了**一主一从三哨兵**的高可用架构：

- `redis-master`：主节点，处理读写
- `redis-replica`：从节点，复制主节点数据
- `sentinel-1/2/3`：3 个哨兵，监控主节点，自动故障转移

它们通过 `redis-net` 网络互相通信。

### Docker 网络

```bash
docker network create redis-net
```

所有 Redis 和 Sentinel 容器都在同一个 `redis-net` 网络中，可以通过容器名互相访问（如 `redis-master:6379`）。

## 常用命令

### 容器管理

```bash
# 查看运行中的容器
docker ps

# 查看所有容器（包括已停止的）
docker ps -a

# 启动容器
docker start nginx

# 停止容器
docker stop nginx

# 重启容器
docker restart nginx

# 删除容器（需先停止）
docker rm nginx

# 强制删除运行中的容器
docker rm -f nginx
```

### 日志和调试

```bash
# 查看日志
docker logs nginx                    # 全部日志
docker logs -f nginx                 # 实时跟踪
docker logs --tail=50 nginx          # 最后 50 行

# 进入容器内部
docker exec -it nginx bash          # 进入 bash shell
docker exec redis-master redis-cli   # 进入 Redis 命令行

# 复制文件
docker cp file.txt nginx:/etc/nginx/   # 宿主机 → 容器
docker cp nginx:/var/log/access.log ./  # 容器 → 宿主机
```

### 镜像管理

```bash
# 查看本地镜像
docker images

# 拉取镜像
docker pull redis:latest

# 删除镜像
docker rmi redis:latest

# 清理未使用的镜像和容器
docker system prune -a
```

### 网络管理

```bash
# 创建网络
docker network create my-net

# 查看网络
docker network ls

# 查看网络中的容器
docker network inspect redis-net
```

## 配置文件启动

有些容器需要挂载配置文件：

```bash
docker run -d --name redis-master \
  --network redis-net \
  -p 6379:6379 \
  -v D:\work\web-ai-code\logistics-system\redis-sentinel\redis-master.conf:/usr/local/etc/redis/redis.conf \
  redis:latest \
  redis-server /usr/local/etc/redis/redis.conf
```

**`-v` 参数解释**：
- 冒号左边：宿主机上的文件路径
- 冒号右边：容器内的文件路径
- 效果：把宿主机上的配置文件"映射"到容器内

## 验证方法

### 确认所有容器运行

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

### 测试容器连通性

```bash
# 从容器内访问另一个容器
docker exec redis-master ping redis-replica
```

## 常见问题

**Q: 容器启动后立刻退出？**
A: `docker logs <容器名>` 查看错误日志。常见原因：端口冲突、配置文件路径错误、镜像下载失败。

**Q: 容器内 localhost 访问不到宿主机？**
A: 容器内的 localhost 指向容器自己。用 `host.docker.internal`（Windows/Mac）或宿主机 IP。

**Q: 怎么进入容器改配置？**
A: `docker exec -it 容器名 bash`。但容器重启后修改会丢失，应该通过 `-v` 挂载配置文件。
