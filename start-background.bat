@echo off
REM ========================================
REM 差评智控 - 后台运行脚本
REM ========================================

title 差评智控 - 后台启动

echo.
echo ========================================
echo   差评智控系统 - 后台启动
echo ========================================
echo.

REM 检查 JAR 文件
if not exist "target\meituan-review-automation-1.0.0-SNAPSHOT.jar" (
    echo [错误] 未找到 JAR 文件
    echo 请先执行: mvn clean package spring-boot:repackage -DskipTests
    pause
    exit /b 1
)

REM 创建目录
if not exist "logs" mkdir logs
if not exist "data\database" mkdir data\database
if not exist "backup\database" mkdir backup\database

REM 检查是否已在运行
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
    echo [警告] 端口 8080 已被占用，应用可能已在运行
    pause
    exit /b 1
)

REM 后台启动
echo [启动] 正在后台启动应用...
start /B javaw -jar target\meituan-review-automation-1.0.0-SNAPSHOT.jar > logs\startup.log 2>&1

REM 等待启动
echo [等待] 等待应用启动...
timeout /t 5 /nobreak >nul

REM 检查启动状态
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo [成功] 应用已在后台启动
    echo ----------------------------------------
    echo   访问地址: http://localhost:8080
    echo   日志文件: logs\application.log
    echo   管理员: admin / Admin@2026#Secure
    echo ========================================
    echo.
    echo [提示] 使用 stop-production.bat 停止应用
) else (
    echo.
    echo [失败] 应用启动失败，请查看 logs\startup.log
)

echo.
pause
