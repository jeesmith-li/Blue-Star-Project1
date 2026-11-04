# 蓝星 - 生产环境部署指南

## 目录
- [环境要求](#环境要求)
- [MySQL 数据库配置](#mysql-数据库配置)
- [应用部署](#应用部署)
- [Nginx 配置](#nginx-配置)
- [HTTPS 配置](#https-配置)
- [防火墙规则](#防火墙规则)
- [监控与维护](#监控与维护)

---

## 环境要求

### 操作系统
- Linux (推荐 Ubuntu 20.04+ 或 CentOS 7+)
- Windows Server 2019+

### 软件依赖
- **Java**: JDK 17 或更高版本
- **MySQL**: 8.0 或更高版本
- **Nginx**: 1.18+ (可选，用于反向代理)
- **内存**: 最低 2GB，推荐 4GB+
- **磁盘**: 最低 10GB 可用空间

### 端口要求
- **应用端口**: 8080 (可配置)
- **MySQL 端口**: 3306
- **Nginx HTTP**: 80
- **Nginx HTTPS**: 443

---

## MySQL 数据库配置

### 1. 安装 MySQL

#### Ubuntu/Debian
```bash
sudo apt update
sudo apt install mysql-server
sudo mysql_secure_installation
```

#### CentOS/RHEL
```bash
sudo yum install mysql-server
sudo systemctl start mysqld
sudo systemctl enable mysqld
sudo mysql_secure_installation
```

### 2. 创建数据库和用户

登录 MySQL：
```bash
mysql -u root -p
```

执行以下 SQL：
```sql
-- 创建数据库
CREATE DATABASE badreview_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户
CREATE USER 'badreview_user'@'localhost' IDENTIFIED BY 'YourSecurePassword123!';

-- 授权
GRANT ALL PRIVILEGES ON badreview_db.* TO 'badreview_user'@'localhost';

-- 刷新权限
FLUSH PRIVILEGES;

-- 退出
EXIT;
```

### 3. 验证连接

```bash
mysql -u badreview_user -p badreview_db
```

### 4. 初始化管理员账号

**生产环境需要手动创建管理员账号**

```sql
USE badreview_db;

-- 插入管理员账号 (密码: Admin@2026#Secure)
INSERT INTO users (username, password, role, status, subscription_start_time, subscription_end_time, license_key, created_at, shop_count)
VALUES (
    'admin',
    '$2a$10$YourBcryptHashedPasswordHere',  -- 需要替换为实际的 BCrypt 哈希
    'ADMIN',
    'ACTIVE',
    NOW(),
    '2099-12-31 23:59:59',
    'REPLACE_WITH_LICENSE_KEY',  -- 32位十六进制字符串
    NOW(),
    0
);
```

**注意**: 为了安全，请使用 BCrypt 工具生成密码哈希。可以使用以下 Java 代码：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("YourPassword");
        System.out.println(hash);
    }
}
```

---

## 应用部署

### 1. 编译项目

在开发环境执行：
```bash
mvn clean package -DskipTests
```

生成的 JAR 文件位于：
```
target/meituan-review-automation-1.0.0-SNAPSHOT.jar
```

### 2. 上传到服务器

```bash
scp target/meituan-review-automation-1.0.0-SNAPSHOT.jar user@server:/opt/badreview/
```

### 3. 创建应用目录结构

```bash
sudo mkdir -p /opt/badreview/{logs,backup,config}
sudo chown -R youruser:yourgroup /opt/badreview
cd /opt/badreview
```

### 4. 配置环境变量

创建 `.env` 文件：
```bash
vim /opt/badreview/.env
```

内容：
```bash
# 数据库配置
DB_PASSWORD=YourSecurePassword123!

# JWT 配置
JWT_SECRET=YourProductionJWTSecretKeyMustBeAtLeast32CharactersLong123456

# 应用端口
SERVER_PORT=8080

# 日志级别
LOG_LEVEL=INFO
```

### 5. 启动应用

#### 方式一: 直接启动 (测试用)
```bash
cd /opt/badreview
java -jar \
  -Dspring.profiles.active=prod \
  -Dspring.datasource.password=${DB_PASSWORD} \
  -Djwt.secret=${JWT_SECRET} \
  meituan-review-automation-1.0.0-SNAPSHOT.jar
```

#### 方式二: 后台运行
```bash
nohup java -jar \
  -Dspring.profiles.active=prod \
  -Dspring.datasource.password=${DB_PASSWORD} \
  -Djwt.secret=${JWT_SECRET} \
  meituan-review-automation-1.0.0-SNAPSHOT.jar \
  > /opt/badreview/logs/app.log 2>&1 &

echo $! > /opt/badreview/app.pid
```

#### 方式三: 使用 systemd (推荐)

创建服务文件：
```bash
sudo vim /etc/systemd/system/badreview.service
```

内容：
```ini
[Unit]
Description=Bad Review Control System
After=network.target mysql.service

[Service]
Type=simple
User=youruser
WorkingDirectory=/opt/badreview
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="DB_PASSWORD=YourSecurePassword123!"
Environment="JWT_SECRET=YourProductionJWTSecretKeyMustBeAtLeast32CharactersLong123456"
ExecStart=/usr/bin/java -jar -Xms512m -Xmx2g /opt/badreview/meituan-review-automation-1.0.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=badreview

[Install]
WantedBy=multi-user.target
```

启动服务：
```bash
sudo systemctl daemon-reload
sudo systemctl enable badreview
sudo systemctl start badreview
sudo systemctl status badreview
```

查看日志：
```bash
sudo journalctl -u badreview -f
```

### 6. 验证部署

检查应用是否运行：
```bash
curl http://localhost:8080/api/license/check
```

应该返回未授权错误（正常现象）。

测试登录：
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@2026#Secure"}'
```

---

## Nginx 配置

### 1. 安装 Nginx

```bash
# Ubuntu/Debian
sudo apt install nginx

# CentOS/RHEL
sudo yum install nginx
```

### 2. 配置反向代理

创建配置文件：
```bash
sudo vim /etc/nginx/sites-available/badreview
```

内容：
```nginx
upstream badreview_backend {
    server 127.0.0.1:8080;
}

server {
    listen 80;
    server_name your-domain.com;

    # 日志
    access_log /var/log/nginx/badreview_access.log;
    error_log /var/log/nginx/badreview_error.log;

    # 客户端上传大小限制
    client_max_body_size 10M;

    # 压缩
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

    # 静态资源
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        proxy_pass http://badreview_backend;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # API 代理
    location /api/ {
        proxy_pass http://badreview_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # 超时设置
        proxy_connect_timeout 300;
        proxy_send_timeout 300;
        proxy_read_timeout 300;
    }

    # 前端页面
    location / {
        proxy_pass http://badreview_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

启用配置：
```bash
# Ubuntu/Debian
sudo ln -s /etc/nginx/sites-available/badreview /etc/nginx/sites-enabled/

# 测试配置
sudo nginx -t

# 重启 Nginx
sudo systemctl restart nginx
```

---

## HTTPS 配置

### 1. 使用 Let's Encrypt (免费证书)

#### 安装 Certbot
```bash
# Ubuntu/Debian
sudo apt install certbot python3-certbot-nginx

# CentOS/RHEL
sudo yum install certbot python3-certbot-nginx
```

#### 申请证书
```bash
sudo certbot --nginx -d your-domain.com
```

按照提示完成配置。

#### 自动续期
Certbot 会自动配置定时任务。验证：
```bash
sudo certbot renew --dry-run
```

### 2. 手动配置 HTTPS

如果使用其他证书，修改 Nginx 配置：

```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    # SSL 证书
    ssl_certificate /path/to/your/fullchain.pem;
    ssl_certificate_key /path/to/your/privkey.pem;

    # SSL 配置
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # HSTS
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # 其他配置同 HTTP...
}

# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}
```

重启 Nginx：
```bash
sudo nginx -t
sudo systemctl restart nginx
```

---

## 防火墙规则

### 1. UFW (Ubuntu)

```bash
# 允许 SSH
sudo ufw allow 22/tcp

# 允许 HTTP
sudo ufw allow 80/tcp

# 允许 HTTPS
sudo ufw allow 443/tcp

# 允许 MySQL (仅本地)
# 不需要开放，MySQL 只监听 localhost

# 启用防火墙
sudo ufw enable

# 查看状态
sudo ufw status
```

### 2. firewalld (CentOS/RHEL)

```bash
# 允许 HTTP
sudo firewall-cmd --permanent --add-service=http

# 允许 HTTPS
sudo firewall-cmd --permanent --add-service=https

# 重载配置
sudo firewall-cmd --reload

# 查看状态
sudo firewall-cmd --list-all
```

### 3. 安全建议

- **不要**直接暴露应用端口 8080
- 只通过 Nginx 反向代理访问
- MySQL 只监听 localhost (127.0.0.1)
- 定期更新系统安全补丁
- 使用强密码和密钥认证
- 配置 fail2ban 防止暴力破解

---

## 监控与维护

### 1. 日志查看

应用日志：
```bash
# systemd 日志
sudo journalctl -u badreview -f

# 应用日志文件
tail -f /opt/badreview/logs/badreview.log

# Nginx 日志
tail -f /var/log/nginx/badreview_access.log
tail -f /var/log/nginx/badreview_error.log
```

### 2. 应用管理

```bash
# 启动
sudo systemctl start badreview

# 停止
sudo systemctl stop badreview

# 重启
sudo systemctl restart badreview

# 查看状态
sudo systemctl status badreview

# 查看 PID
ps aux | grep java
```

### 3. 数据库备份

应用自动备份数据库到 `/opt/badreview/backup/database/`，每天凌晨 2 点执行。

手动备份：
```bash
mysqldump -u badreview_user -p badreview_db > backup_$(date +%Y%m%d_%H%M%S).sql
```

恢复：
```bash
mysql -u badreview_user -p badreview_db < backup_20260915_020000.sql
```

### 4. 更新应用

```bash
# 停止服务
sudo systemctl stop badreview

# 备份旧版本
cp /opt/badreview/meituan-review-automation-1.0.0-SNAPSHOT.jar \
   /opt/badreview/backup/app_$(date +%Y%m%d_%H%M%S).jar

# 上传新版本
scp target/meituan-review-automation-1.0.0-SNAPSHOT.jar user@server:/opt/badreview/

# 启动服务
sudo systemctl start badreview

# 检查日志
sudo journalctl -u badreview -f
```

### 5. 性能监控

#### 系统资源
```bash
# CPU 和内存
top
htop

# 磁盘使用
df -h

# 磁盘 IO
iostat -x 1
```

#### 应用性能
```bash
# JVM 内存
jmap -heap <pid>

# 线程状态
jstack <pid>

# GC 日志
# 在启动参数中添加:
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:/opt/badreview/logs/gc.log
```

### 6. 健康检查

创建监控脚本：
```bash
vim /opt/badreview/health_check.sh
```

内容：
```bash
#!/bin/bash

HEALTH_URL="http://localhost:8080/api/license/check"
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" $HEALTH_URL)

if [ "$RESPONSE" == "401" ] || [ "$RESPONSE" == "403" ]; then
    echo "OK: Application is running"
    exit 0
else
    echo "ERROR: Application is not responding correctly (HTTP $RESPONSE)"
    # 可选: 发送告警邮件或重启服务
    # sudo systemctl restart badreview
    exit 1
fi
```

添加到 crontab：
```bash
chmod +x /opt/badreview/health_check.sh
crontab -e
```

添加：
```
*/5 * * * * /opt/badreview/health_check.sh >> /opt/badreview/logs/health.log 2>&1
```

---

## 故障排查

### 应用无法启动

1. 检查 Java 版本：`java -version`
2. 检查端口占用：`sudo netstat -tlnp | grep 8080`
3. 检查日志：`sudo journalctl -u badreview -n 100`
4. 检查数据库连接：`mysql -u badreview_user -p badreview_db`

### 数据库连接失败

1. 检查 MySQL 状态：`sudo systemctl status mysql`
2. 检查用户权限：`SHOW GRANTS FOR 'badreview_user'@'localhost';`
3. 检查密码：确认 `.env` 中的密码正确
4. 检查防火墙：`sudo ufw status`

### Nginx 502 错误

1. 检查应用是否运行：`sudo systemctl status badreview`
2. 检查端口：`curl http://localhost:8080`
3. 检查 Nginx 配置：`sudo nginx -t`
4. 查看 Nginx 日志：`tail -f /var/log/nginx/badreview_error.log`

---

## 安全检查清单

- [ ] 更改默认管理员密码
- [ ] 使用强密码 (至少 12 位，包含大小写、数字、特殊字符)
- [ ] 配置防火墙规则
- [ ] 启用 HTTPS
- [ ] MySQL 只监听 localhost
- [ ] 定期备份数据库
- [ ] 设置日志轮转
- [ ] 配置监控告警
- [ ] 限制 SSH 访问 (密钥认证)
- [ ] 安装 fail2ban
- [ ] 定期更新系统补丁
- [ ] 审计用户权限

---

## 技术支持

如有问题，请检查：
1. 应用日志：`/opt/badreview/logs/badreview.log`
2. 系统日志：`sudo journalctl -u badreview`
3. Nginx 日志：`/var/log/nginx/badreview_error.log`

---

**部署完成后，请访问**: `https://your-domain.com`

**默认管理员账号** (首次登录后请立即修改密码):
- 用户名: admin
- 密码: Admin@2026#Secure
