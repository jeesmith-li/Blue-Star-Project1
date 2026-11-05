@echo off
chcp 65001 >nul
cd /d "%~dp0"

title 打包差评智控系统

echo ========================================
echo     蓝星 - 差评智控系统打包工具
echo ========================================
echo.

REM 检查 Maven
echo [1/4] 检查 Maven 环境...
call mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Maven
    pause
    exit /b 1
)
echo [成功] Maven 已安装

REM 清理旧文件
echo [2/4] 清理旧文件...
if exist target\*.jar (
    del /f /q target\*.jar
    echo 已清理旧 JAR 文件
)

REM 构建项目
echo [3/4] 构建项目...
echo.
call mvn clean package spring-boot:repackage -DskipTests
if %errorlevel% neq 0 (
    echo.
    echo [错误] 构建失败
    pause
    exit /b 1
)

REM 创建发布目录
echo.
echo [4/4] 创建发布包...
set "RELEASE_DIR=release"
set "APP_NAME=差评智控系统-v1.3.0"

if exist "%RELEASE_DIR%\%APP_NAME%" rmdir /s /q "%RELEASE_DIR%\%APP_NAME%"
mkdir "%RELEASE_DIR%\%APP_NAME%"

REM 复制文件
echo 复制应用文件...
copy "target\meituan-review-automation-1.3.0.jar" "%RELEASE_DIR%\%APP_NAME%\app.jar"
copy "start.bat" "%RELEASE_DIR%\%APP_NAME%\"
copy "stop.bat" "%RELEASE_DIR%\%APP_NAME%\"
copy "README.md" "%RELEASE_DIR%\%APP_NAME%\" 2>nul

REM 创建数据目录
mkdir "%RELEASE_DIR%\%APP_NAME%\data\database"
mkdir "%RELEASE_DIR%\%APP_NAME%\logs"

REM 创建说明文件
echo 差评智控系统 v1.3.0> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo.>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 运行要求：>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo - Java 17 或更高版本>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo - Windows 10/11 或 Windows Server 2016+>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo.>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 使用方法：>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 1. 双击 start.bat 启动应用>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 2. 浏览器自动打开 http://localhost:8080>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 3. 使用 admin / 123456 登录>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 4. 双击 stop.bat 停止应用>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo.>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 技术支持：>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"
echo 蓝星科技>> "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"

REM 显示文件大小
echo.
echo ========================================
echo  打包完成！
echo ========================================
for %%I in ("%RELEASE_DIR%\%APP_NAME%\app.jar") do echo  应用大小: %%~zI 字节
echo  发布目录: %RELEASE_DIR%\%APP_NAME%
echo ========================================
echo.
echo 文件清单：
dir /b "%RELEASE_DIR%\%APP_NAME%"
echo.
echo 可以将 %APP_NAME% 文件夹打包为 ZIP 发布
pause
