# 16 — Gogs 代码仓库

## 是什么

Gogs 是一个**轻量级的 Git 代码托管服务**，类似于 GitHub / GitLab，但可以在你自己的机器上运行。占用内存极小（几十 MB），非常适合个人学习和团队协作。

## 为什么需要

### 为什么不用 GitHub

- GitHub 私有仓库有时间限制（部分账号）
- 局域网访问更快（Gogs 在本地）
- 学习完整的 DevOps 流程（本地 Git → Jenkins → Docker）
- 数据完全自己掌控

### Gogs 在 DevOps 流程中的位置

```
IDE 写完代码
    │
    git push
    ▼
Gogs（代码仓库） ─────→ Jenkins 拉取代码
                           │
                           ▼
                      构建 → 测试 → Docker 镜像 → 部署
```

## 核心概念

### Git 基础知识

```
工作区 (workspace)     暂存区 (staging)      本地仓库 (local)      远程仓库 (remote/Gogs)
    │                      │                      │                      │
    │  git add             │  git commit          │  git push            │
    │ ─────────────────→   │ ─────────────────→   │ ─────────────────→   │
    │                      │                      │                      │
    │                      │                      │  git pull            │
    │  ←─────────────────────────────────────────────────────────────────│
```

### 常用 Git 命令

```bash
# 查看状态
git status                  # 哪些文件修改了？
git diff                    # 具体改了什么？

# 提交
git add .                   # 暂存所有修改
git add filename.java       # 暂存指定文件
git commit -m "fix: 修复订单创建bug"   # 提交到本地仓库

# 推送/拉取
git push                    # 推送到 Gogs
git push -u origin main     # 第一次推送（关联远程分支）
git pull                    # 拉取最新代码

# 分支
git branch                  # 查看本地分支
git branch feature-xxx      # 创建分支
git checkout feature-xxx    # 切换分支
git checkout -b feature-xxx # 创建并切换
git merge feature-xxx       # 合并分支到当前分支

# 历史
git log                     # 查看提交历史
git log --oneline           # 紧凑模式
git log --oneline --graph   # 带分支图

# 撤销
git reset --soft HEAD~1     # 撤销最近一次 commit（保留修改）
git checkout -- filename    # 丢弃工作区修改
```

## 项目中的 Gogs 配置

### 1. .gitignore 文件

**文件位置**: `.gitignore`

```gitignore
# Maven 编译产物（不需要提交到仓库，太大了）
target/
*.jar
*.war

# IDE 配置文件（每个人的 IDE 不一样）
.idea/
*.iml
.project
.classpath
.settings/
.vscode/

# 日志文件
*.log
/logs/
/xxl-job/

# 操作系统文件
.DS_Store
Thumbs.db

# 环境变量
.env
*.local.properties
```

### 2. 仓库初始化步骤

```bash
# ① 初始化 Git 仓库
cd D:\work\web-ai-code\logistics-system
git init

# ② 配置用户信息（仅第一次）
git config user.name "你的名字"
git config user.email "你的邮箱"

# ③ 添加所有文件
git add .

# ④ 第一次提交
git commit -m "init: 智能物流追踪系统"

# ⑤ 关联远程仓库（先在 Gogs 页面上创建仓库）
git remote add origin http://localhost:3000/test/logistics-system.git

# ⑥ 推送到 Gogs
git push -u origin main
```

### 3. 日常开发流程

```bash
# 拉取最新代码
git pull

# 修改代码...

# 查看改了什么
git status
git diff

# 提交
git add .
git commit -m "feat: 新增用户积分功能"

# 推送
git push
```

## Gogs 访问

Gogs 运行在 Docker 容器中：

```bash
docker ps | grep gogs
# gogs   gogs/gogs:0.12   0.0.0.0:3000->3000/tcp, 0.0.0.0:3022->22/tcp
```

浏览器打开：http://localhost:3000

### 首次配置

1. 数据库类型: SQLite3（最简单）
2. 域名: localhost
3. 应用 URL: http://localhost:3000/
4. 可选：创建管理员账号

### 创建仓库

1. 登录后 → 我的仓库 → 创建仓库
2. 仓库名称: `logistics-system`
3. 可见性: 私有
4. 创建后按页面提示关联本地仓库

## Commit 消息规范

推荐使用约定式提交格式：

```
<type>: <description>

feat:     新功能   → "feat: 新增订单导出功能"
fix:      Bug修复   → "fix: 修复库存超卖问题"
refactor: 重构     → "refactor: 优化订单查询SQL"
docs:     文档     → "docs: 更新 README"
test:     测试     → "test: 添加订单创建单元测试"
chore:    杂项     → "chore: 更新依赖版本"
```

## 验证方法

### 1. 确认 Gogs 运行

```bash
curl http://localhost:3000
# 应返回 Gogs 登录页面
```

### 2. 推拉测试

```bash
# 查看远程仓库
git remote -v
# origin  http://localhost:3000/test/logistics-system.git

# 创建测试文件
echo "test" > test.txt
git add test.txt
git commit -m "test: 测试 Gogs 连接"
git push

# 在 Gogs 页面（http://localhost:3000）确认文件已上传
```

## 常见问题

**Q: `git push` 报 "Authentication failed"？**
A: 在 Gogs 页面上确认用户名密码正确。或用 SSH 方式（Gogs 暴露了 3022 端口）。

**Q: `.gitignore` 不生效？**
A: 如果文件已经被 Git 跟踪了，加到 `.gitignore` 后需要先 `git rm --cached 文件名` 移除跟踪。

**Q: 本地 Git 仓库怎么关联到 Gogs？**
A: `git remote add origin http://localhost:3000/用户名/仓库名.git`
