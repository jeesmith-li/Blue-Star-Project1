@echo off
chcp 65001 > nul
echo Starting Meituan Review Automation Backend...
echo.

mvn spring-boot:run -Djava.awt.headless=false

pause
