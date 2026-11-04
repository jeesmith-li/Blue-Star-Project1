@echo off
REM ========================================
REM 差评智控 - 停止脚本
REM ========================================

title 差评智控 - 停止应用

echo.
echo ========================================
echo   差评智控系统 - 停止应用
echo ========================================
echo.

REM 查找运行在 8080 端口的进程
echo [检查] 正在查找应用进程...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    set PID=%%a
)

if "%PID%"=="" (
    echo [提示] 未发现运行中的应用（端口 8080 未被占用）
    echo.
    pause
    exit /b 0
)

echo [发现] 进程 PID: %PID%
echo.

REM 停止进程
echo [停止] 正在停止应用...
taskkill /F /PID %PID% >nul 2>&1

if %errorlevel% equ 0 (
    echo [成功] 应用已停止
) else (
    echo [失败] 无法停止应用，可能需要管理员权限
)

echo.
pause
