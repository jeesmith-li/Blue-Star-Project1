@echo off
chcp 65001 >nul
cd /d "%~dp0"

title 打包差评智控系统（含JRE）

echo ========================================
echo  蓝星 - 差评智控系统打包工具（含JRE）
echo ========================================
echo.

REM 检查 JDK
echo [1/6] 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Java
    pause
    exit /b 1
)
echo [成功] Java 已安装

REM 检查 jlink
jlink --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] jlink 不可用，请使用 JDK（不是 JRE）
    pause
    exit /b 1
)
echo [成功] jlink 已就绪

REM 检查 Maven
echo [2/6] 检查 Maven 环境...
call mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Maven
    pause
    exit /b 1
)
echo [成功] Maven 已安装

REM 构建项目
echo [3/6] 构建应用程序...
echo.
call mvn clean package spring-boot:repackage -DskipTests
if %errorlevel% neq 0 (
    echo.
    echo [错误] 构建失败
    pause
    exit /b 1
)
echo [成功] 应用程序构建完成

REM 创建自定义 JRE
echo [4/6] 创建自定义 JRE（这可能需要几分钟）...
set "JRE_DIR=jre17-custom"

if exist "%JRE_DIR%" rmdir /s /q "%JRE_DIR%"

jlink --add-modules java.base,java.logging,java.xml,java.desktop,java.management,java.naming,java.sql,jdk.unsupported,jdk.crypto.ec ^
      --strip-debug ^
      --no-man-pages ^
      --no-header-files ^
      --compress=2 ^
      --output "%JRE_DIR%"

if %errorlevel% neq 0 (
    echo [错误] JRE 创建失败
    pause
    exit /b 1
)
echo [成功] 自定义 JRE 创建完成

REM 创建发布目录
echo [5/6] 创建发布包...
set "RELEASE_DIR=release"
set "APP_NAME=差评智控系统-v1.3.0-完整版"

if exist "%RELEASE_DIR%\%APP_NAME%" rmdir /s /q "%RELEASE_DIR%\%APP_NAME%"
mkdir "%RELEASE_DIR%\%APP_NAME%"

REM 复制 JRE
echo 复制 JRE 运行时...
xcopy /E /I /Q "%JRE_DIR%" "%RELEASE_DIR%\%APP_NAME%\jre"

REM 复制应用文件
echo 复制应用文件...
copy "target\meituan-review-automation-1.3.0.jar" "%RELEASE_DIR%\%APP_NAME%\app.jar"

REM 创建启动脚本（使用内置 JRE）
echo 创建启动脚本...
(
echo @echo off
echo chcp 65001 ^>nul
echo title 差评智控系统
echo.
echo echo ========================================
echo echo     蓝星 - 差评智控系统
echo echo     Blue Star Review Control System
echo echo ========================================
echo echo.
echo.
echo REM 使用内置 JRE
echo set "JAVA_HOME=%%~dp0jre"
echo set "PATH=%%JAVA_HOME%%\bin;%%PATH%%"
echo.
echo echo [1/2] 检查 Java 环境...
echo java -version ^>nul 2^>^&1
echo if %%errorlevel%% neq 0 ^(
echo     echo [错误] Java 运行环境异常
echo     pause
echo     exit /b 1
echo ^)
echo echo [成功] Java 环境已就绪
echo.
echo REM 创建数据目录
echo if not exist "data\database" ^(
echo     mkdir "data\database"
echo     echo [创建] 数据库目录
echo ^)
echo.
echo echo [2/2] 正在启动应用...
echo echo.
echo echo ----------------------------------------
echo echo  系统启动中，请稍候...
echo echo  启动完成后将自动打开浏览器
echo echo ----------------------------------------
echo echo.
echo.
echo REM 启动应用
echo start /b javaw -jar app.jar ^>logs\application.log 2^>^&1
echo.
echo REM 等待服务启动
echo echo 等待服务启动...
echo timeout /t 5 /nobreak ^>nul
echo.
echo REM 打开浏览器
echo echo 正在打开浏览器...
echo start http://localhost:8080
echo.
echo echo.
echo echo ========================================
echo echo  启动完成！
echo echo  访问地址: http://localhost:8080
echo echo  管理员账号: admin
echo echo  管理员密码: 123456
echo echo ========================================
echo echo.
echo echo 按任意键关闭此窗口（应用将继续运行）
echo echo 要停止应用，请运行: stop.bat
echo pause ^>nul
) > "%RELEASE_DIR%\%APP_NAME%\start.bat"

REM 创建停止脚本
copy "stop.bat" "%RELEASE_DIR%\%APP_NAME%\"

REM 创建数据目录
mkdir "%RELEASE_DIR%\%APP_NAME%\data\database"
mkdir "%RELEASE_DIR%\%APP_NAME%\logs"

REM 创建说明文件
(
echo ========================================
echo     蓝星 - 差评智控系统 v1.3.0
echo     Blue Star Review Control System
echo     完整版（包含 Java 运行环境）
echo ========================================
echo.
echo 【系统特点】
echo ✓ 完全独立运行，无需安装 Java
echo ✓ 内置 Java 17 运行环境
echo ✓ 解压即用，绿色便携
echo.
echo 【运行要求】
echo - 操作系统: Windows 10/11 或 Windows Server 2016+
echo - 内存: 至少 4GB RAM
echo - 磁盘空间: 至少 500MB
echo.
echo 【使用方法】
echo 1. 双击 start.bat 启动应用
echo 2. 浏览器自动打开 http://localhost:8080
echo 3. 使用管理员账号登录：
echo    - 用户名: admin
echo    - 密码: 123456
echo 4. 双击 stop.bat 停止应用
echo.
echo 【目录说明】
echo app.jar         - 应用程序主文件
echo jre/            - Java 运行环境（内置）
echo start.bat       - 启动脚本
echo stop.bat        - 停止脚本
echo data/           - 数据存储目录
echo   └─database/   - SQLite 数据库文件
echo logs/           - 日志文件目录
echo.
echo 【功能特性】
echo ✓ 美团差评自动监控
echo ✓ 智能回复模板管理
echo ✓ 多店铺统一管理
echo ✓ 用户权限控制
echo ✓ 授权期限管理
echo.
echo 【数据备份】
echo 定期备份 data/database/ 目录下的数据库文件
echo 即可完整保存所有业务数据
echo.
echo 【技术支持】
echo 公司: 蓝星科技
echo 版本: 1.0.0 完整版
echo 日期: 2026-09-15
echo.
echo ========================================
) > "%RELEASE_DIR%\%APP_NAME%\使用说明.txt"

REM Include required automation runtime, then build a clean fresh-install ZIP.
if not exist "python-runtime\python.exe" (
    echo [ERROR] Missing python-runtime\python.exe
    exit /b 1
)
xcopy /E /I /Q /Y "python" "%RELEASE_DIR%\%APP_NAME%\python"
xcopy /E /I /Q /Y "python-runtime" "%RELEASE_DIR%\%APP_NAME%\python-runtime"
python package-release.py "%RELEASE_DIR%\%APP_NAME%"
if errorlevel 1 exit /b 1

REM 显示统计信息
echo [6/6] 生成统计信息...
echo.
echo ========================================
echo  打包完成！
echo ========================================
for %%I in ("%RELEASE_DIR%\%APP_NAME%\app.jar") do echo  应用大小: %%~zI 字节
for /f "tokens=3" %%a in ('dir "%RELEASE_DIR%\%APP_NAME%" /s /-c ^| findstr /C:" 字节"') do set size=%%a
echo  总大小: 约 %size% 字节
echo  发布目录: %RELEASE_DIR%\%APP_NAME%
echo ========================================
echo.
echo 文件清单：
dir /b "%RELEASE_DIR%\%APP_NAME%"
echo.
echo 提示：可以使用 7-Zip 或 WinRAR 压缩为 ZIP 发布
echo       推荐压缩后大小约为 100-120MB
pause
