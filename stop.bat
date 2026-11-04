@echo off
setlocal enabledelayedexpansion
title BlueStar - Stop Service

echo ========================================
echo   BlueStar - Stop Review Service
echo ========================================
echo.

REM Stop only THIS app: kill java/javaw whose command line contains app.jar or meituan-review jar
echo Stopping application...
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and ($_.CommandLine -like '*app.jar*' -or $_.CommandLine -like '*meituan-review-automation*') } | ForEach-Object { Write-Host ('  Killing PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

REM Also free port 8080 if still occupied
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    echo Killing process on port 8080, PID: %%a
    taskkill /F /PID %%a >nul 2>&1
)

echo.
echo ========================================
echo  Application stopped.
echo ========================================
echo.
pause
