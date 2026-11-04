# 差评智控 - 快速启动指南

## 启动后端

1. 双击 `start-backend.bat`
2. 等待看到 "Started WebApplication"
3. **保持窗口打开**

## 启动前端

```bash
cd frontend
npm run dev
```

## 访问系统

浏览器打开: http://localhost:3000

登录账号:
- admin / 123456

## 验证后端

浏览器访问: http://localhost:8080/api/health

应返回: `{"success":true,"service":"bad-review-control-backend","status":"UP"}`

---

**重要**: 后端窗口必须保持打开，关闭窗口后端会停止。
