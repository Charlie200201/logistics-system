@echo off
REM ============================
REM Redis 一主一从三哨兵 启动脚本
REM 自动检测 Docker IP 并更新项目配置
REM ============================
setlocal enabledelayedexpansion

echo ==========================================
echo   Redis Sentinel 容器启动 + 配置自动更新
echo ==========================================

REM 1. 创建网络（如已存在会忽略）
docker network create redis-net 2>nul

REM 2. 停止并删除旧容器
for %%c in (sentinel-3 sentinel-2 sentinel-1 redis-replica redis-master) do (
    docker stop %%c 2>nul
    docker rm %%c 2>nul
)

REM 3. 启动 Master (6379)
echo [1/5] 启动 Master (6379)...
docker run -d --name redis-master --network redis-net -p 6379:6379 ^
  -v %~dp0redis-master.conf:/usr/local/etc/redis/redis.conf ^
  redis:latest redis-server /usr/local/etc/redis/redis.conf

REM 4. 启动 Replica (6380)
echo [2/5] 启动 Replica (6380)...
docker run -d --name redis-replica --network redis-net -p 6380:6380 ^
  -v %~dp0redis-replica.conf:/usr/local/etc/redis/redis.conf ^
  redis:latest redis-server /usr/local/etc/redis/redis.conf

REM 5. 启动 3 个 Sentinel
echo [3/5] 启动 Sentinel-1 (26379)...
docker run -d --name sentinel-1 --network redis-net -p 26379:26379 ^
  -v %~dp0sentinel-1.conf:/usr/local/etc/redis/sentinel.conf ^
  redis:latest redis-sentinel /usr/local/etc/redis/sentinel.conf

echo [4/5] 启动 Sentinel-2 (26380)...
docker run -d --name sentinel-2 --network redis-net -p 26380:26380 ^
  -v %~dp0sentinel-2.conf:/usr/local/etc/redis/sentinel.conf ^
  redis:latest redis-sentinel /usr/local/etc/redis/sentinel.conf

echo [5/5] 启动 Sentinel-3 (26381)...
docker run -d --name sentinel-3 --network redis-net -p 26381:26381 ^
  -v %~dp0sentinel-3.conf:/usr/local/etc/redis/sentinel.conf ^
  redis:latest redis-sentinel /usr/local/etc/redis/sentinel.conf

REM 6. 等待容器初始化
echo 等待容器就绪...
timeout /t 5 >nul

REM 7. 获取 Docker 内网 IP
for /f "tokens=*" %%i in ('docker inspect --format="{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" redis-master') do set MASTER_IP=%%i
for /f "tokens=*" %%i in ('docker inspect --format="{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" redis-replica') do set REPLICA_IP=%%i

echo Master  IP: %MASTER_IP%
echo Replica IP: %REPLICA_IP%

REM 8. 更新 application.yml 中的 natMap IP
set YML=%~dp0..\logistics-system-parent\product-service\src\main\resources\application.yml
set TMP=%TEMP%\app-tmp.yml

powershell -Command "(Get-Content '%YML%') -replace 'docker-master-ip: .*', 'docker-master-ip: %MASTER_IP%' -replace 'docker-replica-ip: .*', 'docker-replica-ip: %REPLICA_IP%' | Set-Content '%YML%'"

if errorlevel 1 (
    echo [警告] 自动更新 application.yml 失败，请手动更新
) else (
    echo [成功] application.yml 中的 Docker IP 已更新
)

REM 9. 验证主从复制
echo.
echo ==========================================
echo   验证结果
echo ==========================================
docker exec redis-master redis-cli info replication | findstr "role connected_slaves"

echo.
echo Sentinel 发现 Mater:
docker exec sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster

echo.
echo ==========================================
echo   启动完成！请在 IDEA 中重新编译 product-service
echo ==========================================
pause
