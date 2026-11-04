@echo off
chcp 65001 >nul
title 创建 Windows 安装包

echo ========================================
echo   差评智控系统 - 原生安装包构建
echo ========================================
echo.

REM 检查 Java 版本
echo [1/6] 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Java
    pause
    exit /b 1
)

REM 检查 jpackage
jpackage --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] jpackage 不可用
    echo 请使用 JDK 17+ (需要 JDK，不是 JRE)
    pause
    exit /b 1
)
echo [成功] jpackage 已就绪

REM 设置变量
set APP_NAME=差评智控系统
set APP_VERSION=1.0.0
set VENDOR=蓝星科技
set JAR_FILE=target\meituan-review-automation-1.0.0-SNAPSHOT.jar
set OUTPUT_DIR=installer
set ICON_FILE=src\main\resources\static\logo\logo_9.png

REM 清理输出目录
echo [2/6] 清理旧文件...
if exist %OUTPUT_DIR% rmdir /s /q %OUTPUT_DIR%
mkdir %OUTPUT_DIR%

REM 构建 JAR
echo [3/6] 构建应用程序...
call mvn clean package spring-boot:repackage -DskipTests
if %errorlevel% neq 0 (
    echo [错误] 构建失败
    pause
    exit /b 1
)
echo [成功] JAR 构建完成

REM 创建临时目录结构
echo [4/6] 准备打包文件...
set TEMP_DIR=%OUTPUT_DIR%\temp
mkdir %TEMP_DIR%
mkdir %TEMP_DIR%\data
mkdir %TEMP_DIR%\data\database
mkdir %TEMP_DIR%\logs

copy %JAR_FILE% %TEMP_DIR%\app.jar

REM 创建启动器配置
echo [5/6] 创建启动器...
echo @echo off > %TEMP_DIR%\run.bat
echo cd /d "%%~dp0" >> %TEMP_DIR%\run.bat
echo if not exist "data\database" mkdir "data\database" >> %TEMP_DIR%\run.bat
echo start /b javaw -jar app.jar >> %TEMP_DIR%\run.bat
echo timeout /t 3 /nobreak ^>nul >> %TEMP_DIR%\run.bat
echo start http://localhost:8080 >> %TEMP_DIR%\run.bat

REM 使用 jpackage 创建安装包
echo [6/6] 创建 Windows 安装包...
echo 这可能需要几分钟...
echo.

jpackage ^
  --type msi ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --vendor "%VENDOR%" ^
  --description "美团差评智能管理系统 - 桌面版" ^
  --input %TEMP_DIR% ^
  --main-jar app.jar ^
  --dest %OUTPUT_DIR% ^
  --win-dir-chooser ^
  --win-menu ^
  --win-menu-group "%APP_NAME%" ^
  --win-shortcut ^
  --java-options "-Xmx2048m" ^
  --java-options "-Dfile.encoding=UTF-8"

if %errorlevel% neq 0 (
    echo.
    echo [错误] 安装包创建失败
    echo.
    echo 常见问题：
    echo 1. 需要 WiX Toolset (https://wixtoolset.org/)
    echo 2. 需要 JDK (不是 JRE)
    echo 3. 需要管理员权限
    echo.
    pause
    exit /b 1
)

REM 清理临时文件
rmdir /s /q %TEMP_DIR%

echo.
echo ========================================
echo  安装包创建成功！
echo ========================================
echo.
echo 文件位置：
dir /b %OUTPUT_DIR%\*.msi
echo.
echo 安装包已保存到: %OUTPUT_DIR%\
for %%I in (%OUTPUT_DIR%\*.msi) do echo 文件大小: %%~zI 字节 (约 %%~zI / 1048576 MB)
echo.
echo 双击 .msi 文件即可安装
pause
