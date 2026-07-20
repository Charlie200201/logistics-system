# 15 — Jenkins CI/CD 持续集成部署

## 是什么

Jenkins 是一个**自动化构建部署工具**。每次你提交代码后，Jenkins 自动帮你完成：拉代码 → 编译 → 测试 → 打包 Docker 镜像 → 部署的整个流程。

## 为什么需要

### 手动部署的痛点

```
每次发布都要:
  ① git pull 拉代码
  ② mvn clean package -DskipTests（等 3 分钟）
  ③ docker build（等 2 分钟）
  ④ docker stop + docker rm + docker run
  ⑤ 5 个服务 × 5 步 = 25 步
  ⑥ 做错了还要回退
  → 一次发布 30 分钟
```

### 有了 Jenkins 后

```
① git push 到 Gogs
② Jenkins 自动触发流水线
③ 5 分钟后，所有服务更新完毕
④ 失败了自动通知你
```

## 核心概念

### CI/CD 是什么

```
CI（Continuous Integration，持续集成）:
  代码频繁合并到主分支 → 自动编译 → 自动测试 → 有问题立即发现

CD（Continuous Delivery/Deployment，持续交付/部署）:
  CI 通过后 → 自动构建镜像 → 自动部署到服务器
```

### 流水线（Pipeline）

流水线就是自动化过程的**步骤清单**。本项目用 Jenkinsfile 定义了 5 个阶段。

## 项目中的代码

### Jenkinsfile

**文件位置**: `Jenkinsfile`

```groovy
pipeline {
    agent any    // 在任何可用的 Jenkins 节点上运行

    stages {
        // 阶段 1: 拉代码
        stage('Checkout') {
            steps {
                echo '=== 从 Git 仓库拉取代码 ==='
                git url: 'http://localhost:3000/test/logistics-system.git',
                    branch: 'main',
                    credentialsId: 'gogs-credentials'
            }
        }

        // 阶段 2: Maven 编译打包
        stage('Build') {
            steps {
                echo '=== Maven 编译打包 ==='
                dir('logistics-system-parent') {
                    sh 'mvn clean package -DskipTests'
                }
            }
        }

        // 阶段 3: 运行单元测试
        stage('Test') {
            steps {
                echo '=== 运行单元测试 ==='
                dir('logistics-system-parent') {
                    sh 'mvn test'
                }
            }
        }

        // 阶段 4: 构建 Docker 镜像（5 个服务并行构建）
        stage('Docker Build') {
            parallel {
                stage('user-service image') {
                    steps {
                        dir('logistics-system-parent/user-service') {
                            sh 'docker build -t logistics-user-service:latest .'
                        }
                    }
                }
                stage('product-service image') {
                    steps {
                        dir('logistics-system-parent/product-service') {
                            sh 'docker build -t logistics-product-service:latest .'
                        }
                    }
                }
                stage('order-service image') {
                    steps {
                        dir('logistics-system-parent/order-service') {
                            sh 'docker build -t logistics-order-service:latest .'
                        }
                    }
                }
                stage('logistics-service image') {
                    steps {
                        dir('logistics-system-parent/logistics-service') {
                            sh 'docker build -t logistics-logistics-service:latest .'
                        }
                    }
                }
                stage('gateway-service image') {
                    steps {
                        dir('logistics-system-parent/gateway-service') {
                            sh 'docker build -t logistics-gateway-service:latest .'
                        }
                    }
                }
            }
        }

        // 阶段 5: 部署到 Docker
        stage('Deploy') {
            steps {
                echo '=== 部署服务到 Docker 容器 ==='
                script {
                    def services = [
                        [name: 'user-service', port: 8081],
                        [name: 'product-service', port: 8082],
                        [name: 'order-service', port: 8083],
                        [name: 'logistics-service', port: 8084],
                        [name: 'gateway-service', port: 8085]
                    ]
                    services.each { svc ->
                        sh """
                            docker stop logistics-${svc.name} 2>/dev/null || true
                            docker rm logistics-${svc.name} 2>/dev/null || true
                            docker run -d \
                                --name logistics-${svc.name} \
                                -p ${svc.port}:${svc.port} \
                                logistics-${svc.name}:latest
                        """
                    }
                }
            }
        }
    }

    post {
        success { echo '流水线执行成功!' }
        failure { echo '流水线执行失败，请检查日志。' }
    }
}
```

### 流水线各阶段说明

```
git push
    │
    ├→ Stage 1: Checkout   — 从 Gogs 拉代码（自动）
    ├→ Stage 2: Build      — mvn clean package（自动）
    ├→ Stage 3: Test       — mvn test（自动）
    ├→ Stage 4: Docker Build — 5 个镜像并行构建（自动）
    └→ Stage 5: Deploy     — docker run 启动容器（自动）
    │
    ├→ 成功 → 通知
    └→ 失败 → 通知
```

## Jenkins 配置步骤

### 1. 访问 Jenkins

Jenkins 运行在 Docker 中：

```bash
docker ps | grep jenkins
# jenkins   jenkins/jenkins:2.361.1-lts-jdk11   8089→8080, 50000

# 获取初始密码
docker logs jenkins 2>&1 | grep -A 10 "Please use the following password"
```

浏览器打开：http://localhost:8089

### 2. 安装插件

安装推荐插件，额外安装：
- Git plugin
- Pipeline plugin
- Docker Pipeline plugin

### 3. 配置 Gogs 凭据

Dashboard → Manage Jenkins → Manage Credentials → 添加 Gogs 的 Git 凭据（用户名 + 密码或 SSH key）

### 4. 创建 Pipeline 任务

1. New Item → 输入名称 → 选择 Pipeline
2. Pipeline → Definition: Pipeline script from SCM
3. SCM: Git → Repository URL: `http://gogs:3000/test/logistics-system.git`
4. Script Path: `Jenkinsfile`

### 5. 配置 Maven 工具

Manage Jenkins → Tools → Maven installations → 添加 Maven（名称 `maven-3.8`，自动安装）

### 6. 触发构建

```bash
# 提交代码到 Gogs → Jenkins 自动触发
# 或者在 Jenkins 页面点击 Build Now 手动触发
```

## 并行构建的优势

```groovy
stage('Docker Build') {
    parallel {
        // 5 个镜像同时构建，而不是一个接一个
        stage('user-service image') { ... }
        stage('product-service image') { ... }
        stage('order-service image') { ... }
        stage('logistics-service image') { ... }
        stage('gateway-service image') { ... }
    }
}
```

串行：5 个镜像各 2 分钟 = 10 分钟
并行：5 个镜像同时 2 分钟 = 2 分钟

## 验证方法

### 1. 手动触发

Jenkins 页面 → 点击任务 → Build Now → 查看 Console Output

### 2. Git Push 自动触发

```bash
git add .
git commit -m "test: 测试 Jenkins 自动构建"
git push
# 到 Jenkins 查看是否自动触发了构建
```

## 常见问题

**Q: Jenkins 连不上 Gogs？**
A: Jenkins 在 Docker 容器内，需要能访问 Gogs。用 `host.docker.internal:3000` 或 Docker 网络中的 Gogs 容器名。

**Q: Maven 构建失败？**
A: 检查 Jenkins 容器内是否安装了 JDK 和 Maven。Jenkins 镜像 `jdk11` 版本已含 JDK 11。

**Q: Docker 命令在 Jenkins 中不可用？**
A: 需要在 Jenkins 容器中挂载 Docker socket：`-v /var/run/docker.sock:/var/run/docker.sock`
