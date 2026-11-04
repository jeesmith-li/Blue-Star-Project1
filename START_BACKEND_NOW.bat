@echo off
cd /d "%~dp0"
echo Starting Spring Boot Backend on port 8080...
mvn spring-boot:run -Dspring-boot.run.mainClass=com.example.meituan.web.WebApplication
