@echo off
echo Testing backend connection...
echo.
curl http://localhost:8080/api/health
echo.
echo.
pause
