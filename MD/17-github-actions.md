# 17 — GitHub Actions CI/CD（替代 Jenkins + Gogs）

## 是什么

GitHub Actions 是 GitHub **内置的 CI/CD 自动化引擎**。你不需要额外搭建 Jenkins 服务器，不需要自己部署 Gogs，只要代码托管在 GitHub 上，推送代码就能自动触发构建、测试、打包、部署。

一句话理解：**把 Jenkins + Gogs 二合一，而且免费、免运维**。

## 为什么需要

### 当前方案的痛点

```
你现在的 CI/CD 流程:

  本地写代码 → git push 到 Gogs(localhost:3000)
                              │
                              ▼
                        Jenkins 检测到变更
                              │
                              ▼
                        拉代码 → 编译 → 测试 → 构建镜像 → 部署
                              │
                              ▼
                        推到本地 Docker Registry(localhost:5000)
```

这个流程需要你在本地同时运行 **Gogs + Jenkins + Docker Registry** 三个服务，任何一个挂了整个流程就断了。

### 换成 GitHub Actions 之后

```
  本地写代码 → git push 到 GitHub
                              │
                              ▼
                  GitHub Actions 自动触发
                              │
                              ▼
                  拉代码 → 编译 → 测试 → 构建镜像 → 部署
                              │
                              ▼
                      推到 GitHub Container Registry (ghcr.io)
```

**不需要** Gogs、**不需要** Jenkins、**不需要** 本地 Docker Registry。只需要一个 GitHub 仓库。

### 对比一览

| | Jenkins + Gogs | GitHub Actions |
|---|---|---|
| 代码托管 | Gogs（需要自己部署） | GitHub（SaaS，免费） |
| CI/CD 引擎 | Jenkins（需要自己部署） | 内置，无需部署 |
| 配置方式 | Jenkinsfile (Groovy) | YAML 文件（`.github/workflows/ci.yml`） |
| 镜像仓库 | 本地 localhost:5000 | GitHub Container Registry (ghcr.io) |
| 运行环境 | 自己的 Jenkins Agent | GitHub 提供的 Runner（或自托管） |
| 免费额度 | 取决于你的机器 | 公开仓库无限免费；私有仓库每月 2000 分钟 |
| 维护成本 | 高（升级、备份、修 bug） | 零（GitHub 维护） |

## 核心概念

### 五个关键名词

```
Workflow（工作流）
  └── Job（作业）
        └── Step（步骤）
              └── Action（动作）

Runner（运行环境）：执行这些 Job 的虚拟机
```

| 概念 | 说明 | 类比 Jenkins |
|---|---|---|
| **Workflow** | 一个完整的自动化流程，定义在 `.github/workflows/*.yml` | Pipeline |
| **Job** | Workflow 里的一个独立任务，多个 Job 默认并行 | Stage |
| **Step** | Job 里的一个步骤，按顺序执行 | Stage 里的单个 step |
| **Action** | 可复用的步骤单元（如 `actions/checkout` 拉代码） | 插件 |
| **Runner** | 执行 Job 的虚拟机（Ubuntu/Windows/macOS） | Agent |

### 触发方式

```yaml
# 推送到 main 或 develop 分支时触发
on:
  push:
    branches: [main, develop]

# 提 Pull Request 时触发
on:
  pull_request:
    branches: [main]

# 手动触发
on:
  workflow_dispatch:

# 定时触发（每天 UTC 0 点）
on:
  schedule:
    - cron: '0 0 * * *'
```

## 完整的 CI/CD 工作流

在项目根目录下创建 `.github/workflows/ci.yml`：

```yaml
name: Logistics System CI/CD

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]
  workflow_dispatch:          # 允许手动触发

env:
  REGISTRY: ghcr.io           # GitHub Container Registry
  IMAGE_PREFIX: ${{ github.repository_owner }}/logistics

jobs:
  # ============================================
  # Job 1: 编译 + 测试
  # ============================================
  build-and-test:
    name: Build & Test
    runs-on: ubuntu-latest

    steps:
      # ① 拉取代码
      - name: Checkout code
        uses: actions/checkout@v4
        # 等价于 Jenkins: git url: '...', branch: 'main'

      # ② 安装 JDK 17
      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'       # Eclipse Temurin（开源 JDK）
          cache: 'maven'                # 自动缓存 Maven 依赖

      # ③ Maven 编译（跳过测试，先看能不能编过）
      - name: Maven Build
        run: mvn clean package -DskipTests
        working-directory: logistics-system-parent

      # ④ 运行单元测试
      - name: Maven Test
        run: mvn test
        working-directory: logistics-system-parent

      # ⑤ 上传构建产物（供后续 Job 使用）
      - name: Upload JAR artifacts
        uses: actions/upload-artifact@v4
        with:
          name: service-jars
          path: |
            logistics-system-parent/user-service/target/*.jar
            logistics-system-parent/product-service/target/*.jar
            logistics-system-parent/order-service/target/*.jar
            logistics-system-parent/logistics-service/target/*.jar
            logistics-system-parent/gateway-service/target/*.jar

  # ============================================
  # Job 2: 构建 Docker 镜像（并行）
  # ============================================
  docker-build:
    name: Build Docker Images
    runs-on: ubuntu-latest
    needs: build-and-test        # 等 build-and-test 成功后才执行
    strategy:
      matrix:                     # 矩阵策略：5 个服务并行构建
        service:
          - user-service
          - product-service
          - order-service
          - logistics-service
          - gateway-service

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Download JAR artifacts
        uses: actions/download-artifact@v4
        with:
          name: service-jars

      # 登录到 GitHub Container Registry
      - name: Login to GHCR
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # 构建并推送镜像
      - name: Build and push ${{ matrix.service }}
        uses: docker/build-push-action@v5
        with:
          context: logistics-system-parent/${{ matrix.service }}
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}-${{ matrix.service }}:latest
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}-${{ matrix.service }}:${{ github.sha }}

  # ============================================
  # Job 3: 部署（可选 — 部署到你的服务器）
  # ============================================
  deploy:
    name: Deploy
    runs-on: ubuntu-latest
    needs: docker-build
    if: github.ref == 'refs/heads/main'   # 只有 main 分支才自动部署

    steps:
      - name: Deploy via SSH
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.DEPLOY_HOST }}
          username: ${{ secrets.DEPLOY_USER }}
          key: ${{ secrets.DEPLOY_SSH_KEY }}
          script: |
            cd /opt/logistics

            SERVICES="user-service product-service order-service logistics-service gateway-service"
            for svc in $SERVICES; do
              docker stop logistics-$svc 2>/dev/null || true
              docker rm logistics-$svc 2>/dev/null || true
              docker pull ghcr.io/${{ github.repository_owner }}/logistics-$svc:latest
              docker run -d \
                --name logistics-$svc \
                --network host \
                ghcr.io/${{ github.repository_owner }}/logistics-$svc:latest
            done

            echo "=== 部署完成 ==="
```

### 工作流结构图

```
git push → GitHub
               │
               ▼
         build-and-test（编译 + 测试）
               │
               ▼
         docker-build（5 个服务并行构建镜像）──── 推送到 ghcr.io
               │
               ▼
            deploy（SSH 到服务器部署）── 仅 main 分支
```

## Dockerfile 模板

每个微服务需要一个 `Dockerfile`，放在对应模块目录下（如 `logistics-system-parent/user-service/Dockerfile`）。

### 通用多阶段 Dockerfile

```dockerfile
# ============ 第一阶段：构建 ============
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# 只复制 Maven wrapper 和 pom.xml，利用 Docker 缓存层
COPY pom.xml ./
RUN apk add --no-cache maven && mvn dependency:resolve

# 复制源码并编译
COPY src ./src
RUN mvn clean package -DskipTests -pl . -am

# ============ 第二阶段：运行 ============
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 创建非 root 用户（安全最佳实践）
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# 从构建阶段复制 JAR
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

> **注意**：以上是独立构建的 Dockerfile（不需要依赖父 POM）。如果要用父 POM 管理依赖，最简单的做法是在 `build-and-test` Job 中先编译好 JAR，然后写一个简单的 Dockerfile 直接复制 JAR 进去：

### 简化版 Dockerfile（推荐，配合上面的 CI 流程）

由于 GitHub Actions 的 `build-and-test` Job 已经完成了 Maven 编译，镜像构建时只需要把编译好的 JAR 包拷贝进去即可：

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

每个服务都需要这个 Dockerfile，放在：
- `logistics-system-parent/user-service/Dockerfile`
- `logistics-system-parent/product-service/Dockerfile`
- `logistics-system-parent/order-service/Dockerfile`
- `logistics-system-parent/logistics-service/Dockerfile`
- `logistics-system-parent/gateway-service/Dockerfile`

## 配置步骤

### 第一步：把代码推送到 GitHub

```bash
# ① 在 GitHub 上创建新仓库（如 logistics-system）

# ② 把原来的 Gogs 远程地址换成 GitHub
git remote remove origin
git remote add origin https://github.com/你的用户名/logistics-system.git

# ③ 推送所有代码
git push -u origin main
```

### 第二步：在 GitHub 上配置 Secrets

GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret 名称 | 值 | 用途 |
|---|---|---|
| `DEPLOY_HOST` | 你的服务器 IP | SSH 部署目标 |
| `DEPLOY_USER` | 服务器用户名（如 root） | SSH 登录用户 |
| `DEPLOY_SSH_KEY` | 你的 SSH 私钥内容 | SSH 免密登录 |

> 如果只是学习、不需要自动部署，可以把 `deploy` Job 删掉或注释掉。不影响前面的构建。

### 第三步：推送工作流文件

把上面的 `ci.yml` 放到 `.github/workflows/` 目录下，推送到 GitHub：

```bash
mkdir -p .github/workflows
# 把 ci.yml 写入 .github/workflows/ci.yml
git add .github/workflows/ci.yml
git commit -m "feat: 添加 GitHub Actions CI/CD 工作流"
git push
```

推送后，GitHub 会自动检测到 `.github/workflows/ci.yml` 并开始运行。

### 第四步：在 GitHub 页面查看运行结果

GitHub 仓库 → Actions 标签页：

- 每个工作流运行都有详细日志
- 失败时 GitHub 会自动发邮件通知
- 可以在 Pull Request 页面直接看到 CI 状态（红叉 / 绿勾）

## Jenkinsfile vs GitHub Actions 对照

| Jenkins (Jenkinsfile) | GitHub Actions (ci.yml) |
|---|---|
| `pipeline { agent any }` | `runs-on: ubuntu-latest` |
| `environment { ... }` | `env:` |
| `stage('Checkout') { git ... }` | `actions/checkout@v4` |
| `stage('Build') { sh 'mvn ...' }` | `- name: Maven Build` + `run: mvn ...` |
| `stage('Docker Build') { parallel { ... } }` | `strategy: matrix:` |
| `docker build -t xxx .` | `docker/build-push-action@v5` |
| `docker run -d --name xxx` | `appleboy/ssh-action` (SSH 到服务器执行) |
| `post { success { ... } }` | GitHub 内置通知（邮件 + 页面状态） |
| 手动配置 `credentialsId` | GitHub Secrets（`${{ secrets.XXX }}`） |
| 手动配置 `maven-3.8` 工具 | `actions/setup-java@v4` + `cache: 'maven'` |

## GitHub Actions 免费额度

| 仓库类型 | 免费分钟数 / 月 |
|---|---|
| 公开仓库 | **无限** |
| 私有仓库 | 2,000 分钟（Linux Runner） |
| Windows Runner | 消耗 2× 分钟 |
| macOS Runner | 消耗 10× 分钟 |

对于这个项目（Maven 编译 + 5 个 Docker 镜像），一次完整 CI 大约消耗 **5-8 分钟**。公开仓库完全免费。

## 验证方法

### 1. 确认工作流触发

推送代码后，打开 GitHub 仓库 → Actions 标签页，应该能看到工作流正在运行：

```
Logistics System CI/CD
  build-and-test    ● 运行中
  docker-build      ○ 等待中
  deploy            ○ 等待中
```

### 2. 确认镜像已推送

```bash
# 查看你的 GitHub Packages
# 浏览器打开: https://github.com/你的用户名?tab=packages
# 应该能看到 5 个镜像包
```

或者用 Docker 直接拉取：

```bash
docker pull ghcr.io/你的用户名/logistics-user-service:latest
```

### 3. 确认部署结果

如果配置了 SSH 部署，登录服务器检查：

```bash
docker ps | grep logistics
# 应该能看到 5 个容器在运行
```

## 常见问题

**Q: GitHub Actions 和 Jenkins 能同时用吗？**
A: 可以。它们的配置文件互不影响。你可以先加上 GitHub Actions，验证通过后再停掉 Jenkins。

**Q: 我没有服务器，`deploy` Job 怎么办？**
A: 把 `ci.yml` 里的 `deploy` Job 删掉就行。镜像还是会构建并推送到 ghcr.io，之后手动 `docker pull` + `docker run` 即可。

**Q: GitHub Container Registry 镜像谁都能拉吗？**
A: 默认是私有的（只有你自己能拉），可以在镜像包的 Settings 里改成公开。

**Q: 每次推送都会触发构建，太频繁了怎么办？**
A: 改 `on.push.branches` 只保留 `main`，这样只在合并到主分支时才触发。

**Q: 本地开发时怎么调试 CI 流程？**
A: 可以用 [act](https://github.com/nektos/act) 这个工具在本地模拟 GitHub Actions 运行：
```bash
act push        # 模拟 push 事件
act -l          # 列出所有 Job
```

**Q: `actions/setup-java@v4` 里的 `cache: 'maven'` 是什么？**
A: 它会自动缓存 `~/.m2/repository`（Maven 依赖），下次构建时不需要重新下载依赖，能快 **2-3 分钟**。
