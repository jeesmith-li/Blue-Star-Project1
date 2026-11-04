@echo off
setlocal
title BlueStar - Review Management
cd /d "%~dp0"
set "APP_DIR=%~dp0"

echo ========================================
echo   BlueStar - Review Management System
echo ========================================
echo.

set "JAVA_EXE=%APP_DIR%jre\bin\javaw.exe"
if exist "%JAVA_EXE%" goto have_jre
set "JAVA_EXE=%APP_DIR%jre17-custom\bin\javaw.exe"
if exist "%JAVA_EXE%" goto have_jre
set "JAVA_EXE=%APP_DIR%jre\bin\java.exe"
if exist "%JAVA_EXE%" goto have_jre
echo [ERROR] JRE not found. Please extract the whole package first, do not run inside the zip.
goto fail

:have_jre
set "APP_JAR=%APP_DIR%app.jar"
if exist "%APP_JAR%" goto have_jar
set "APP_JAR="
for /f "delims=" %%f in ('dir /b /o-d "%APP_DIR%target\meituan-review-automation-*.jar" 2^>nul') do if not defined APP_JAR set "APP_JAR=%APP_DIR%target\%%f"
if not defined APP_JAR goto no_jar

:have_jar
if not exist "%APP_DIR%python-runtime\python.exe" echo [WARNING] python-runtime not found, scraping may not work.
if not exist "%APP_DIR%data\database" mkdir "%APP_DIR%data\database"
if not exist "%APP_DIR%logs" mkdir "%APP_DIR%logs"

echo [0/3] Checking port 8080...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
ping -n 3 127.0.0.1 >nul

echo [1/3] Starting service...
start /b "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar "%APP_JAR%" > "%APP_DIR%logs\startup.log" 2>&1

echo [2/3] Waiting for service to be ready...
set /a TRIES=0
:wait
ping -n 3 127.0.0.1 >nul
set /a TRIES+=1
netstat -an | findstr ":8080" | findstr "LISTENING" >nul
if errorlevel 1 goto wait_more
echo   Service is ready!
goto ready

:wait_more
if %TRIES% lss 15 goto wait
echo [ERROR] Service did not start within 30 seconds.
echo --- last lines of logs\startup.log ---
powershell -NoProfile -Command "Get-Content -Path '%APP_DIR%logs\startup.log' -Tail 25 -ErrorAction SilentlyContinue"
echo -------------------------------------------
goto fail

:ready
echo [3/3] Opening browser...
start http://localhost:8080
echo.
echo ========================================
echo  Started! URL: http://localhost:8080
echo  Admin: admin / 123456
echo ========================================
echo.
echo  You can close this window. App keeps running.
echo  To stop: run stop.bat
echo  Startup log: logs\startup.log
echo.
pause
exit /b 0

:no_jar
echo [ERROR] App JAR not found: %APP_DIR%app.jar
goto fail

:fail
echo.
echo Please send the text above to the developer.
pause
exit /b 1
