@echo off
REM ========================================
REM 差评智控 - 生产环境启动脚本
REM 版本: 1.0.0
REM ========================================

title 差评智控 - 生产环境启动

echo.
echo ========================================
echo   差评智控系统 - 生产环境启动
echo ========================================
echo.

REM 检查 Java 环境
echo [1/5] 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Java 环境
    echo.
    echo 请安装 Java 17 或更高版本
    echo 下载地址: https://www.oracle.com/java/technologies/downloads/
    echo.
    pause
    exit /b 1
)

REM 显示 Java 版本
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%g
)
echo [成功] Java 版本: %JAVA_VERSION%
echo.

REM 检查 JAR 文件
echo [2/5] 检查 JAR 文件...
if not exist "target\meituan-review-automation-1.0.0-SNAPSHOT.jar" (
    echo [错误] 未找到 JAR 文件
    echo.
    echo 请先执行以下命令进行打包:
    echo   mvn clean package spring-boot:repackage -DskipTests
    echo.
    pause
    exit /b 1
)

REM 显示 JAR 文件大小
for %%A in ("target\meituan-review-automation-1.0.0-SNAPSHOT.jar") do set JAR_SIZE=%%~zA
set /a JAR_SIZE_MB=%JAR_SIZE% / 1048576
echo [成功] JAR 文件: %JAR_SIZE_MB% MB
echo.

REM 创建必要的目录
echo [3/5] 创建必要的目录...
if not exist "logs" mkdir logs
if not exist "data\database" mkdir data\database
if not exist "data\screenshots" mkdir data\screenshots
if not exist "data\temp" mkdir data\temp
if not exist "data\debug" mkdir data\debug
if not exist "backup\database" mkdir backup\database
echo [成功] 目录结构已就绪
echo.

REM 检查端口占用
echo [4/5] 检查端口 8080...
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
    echo [警告] 端口 8080 已被占用
    echo.
    echo 请先停止占用端口的进程，或修改配置文件中的端口号
    echo.
    pause
    exit /b 1
)
echo [成功] 端口 8080 可用
echo.

REM 启动应用
echo [5/5] 启动应用...
echo.
echo ========================================
echo   差评智控系统正在启动...
echo ----------------------------------------
echo   端口: 8080
echo   日志: logs\application.log
echo   管理员: admin / Admin@2026#Secure
echo ========================================
echo.
echo [提示] 启动后请访问: http://localhost:8080
echo [提示] 按 Ctrl+C 停止应用
echo.

java -jar target\meituan-review-automation-1.0.0-SNAPSHOT.jar

pause
