# License System Implementation Report

## 项目：蓝星 - SaaS 授权系统

实施日期：2026-09-15
版本：1.0.0

---

## 一、数据库变化

### User 表新增字段

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| license_key | varchar(64) | 授权密钥 | UNIQUE |
| device_id | varchar(128) | 设备ID | NULL |
| device_name | varchar(128) | 设备名称 | NULL |

### 原有字段说明

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| subscription_end_time | datetime | 账号有效截止时间 | NULL |
| status | varchar(20) | 账号状态 | NOT NULL, DEFAULT='ACTIVE' |

### 状态枚举值

- **ACTIVE**: 激活状态
- **EXPIRED**: 已过期
- **DISABLED**: 已禁用

---

## 二、新增文件列表

### License 模块核心文件

```
src/main/java/com/example/meituan/web/license/
├── LicenseService.java          # 授权服务核心逻辑
├── LicenseInterceptor.java      # 授权拦截器
├── LicenseController.java       # 授权API接口
└── LicenseValidator.java        # 授权验证工具类
```

### 配置文件

```
src/main/java/com/example/meituan/web/config/
└── WebMvcConfig.java            # MVC拦截器配置
```

### 管理接口

```
src/main/java/com/example/meituan/web/controller/
└── AdminUserController.java     # 管理员用户管理接口
```

### 修改文件

```
src/main/java/com/example/meituan/web/entity/User.java
src/main/java/com/example/meituan/web/service/AuthService.java
src/main/java/com/example/meituan/web/service/DataInitializer.java
```

---

## 三、API 接口列表

### 3.1 授权信息接口

#### GET /api/license/info
获取当前用户的授权信息

**请求头：**
```
Authorization: Bearer {token}
```

**响应示例：**
```json
{
  "username": "test002",
  "role": "USER",
  "status": "ACTIVE",
  "expireTime": "2027-09-15T23:59:59",
  "licenseKey": "6932393B210743C9965E9B37AC3C88AB",
  "deviceId": null,
  "deviceName": null,
  "remainingDays": 365,
  "isExpiringSoon": false
}
```

#### GET /api/license/check
检查授权状态

**请求头：**
```
Authorization: Bearer {token}
```

**响应示例（有效）：**
```json
{
  "valid": true,
  "status": "ACTIVE",
  "expireTime": "2027-09-15T23:59:59",
  "message": null
}
```

**响应示例（过期）：**
```json
{
  "valid": false,
  "status": "EXPIRED",
  "expireTime": "2026-09-14T23:59:59",
  "message": "账号已过期，请联系管理员续期"
}
```

### 3.2 管理员接口（仅 ADMIN 可访问）

#### GET /api/admin/users
获取所有用户列表

**请求头：**
```
Authorization: Bearer {admin_token}
```

**响应示例：**
```json
[
  {
    "id": 1,
    "username": "admin",
    "role": "ADMIN",
    "status": "ACTIVE",
    "userType": "NORMAL_USER",
    "expireTime": "2099-12-31T23:59:59",
    "licenseKey": "XXXXXXXXXXXXXXXXXXXXXXXXXXXX",
    "deviceId": null,
    "deviceName": null,
    "createdAt": "2026-09-15T23:16:26.247325",
    "lastLoginAt": "2026-09-15T23:16:53.606369",
    "shopCount": 0,
    "remainingDays": 26663
  }
]
```

#### POST /api/admin/users
创建新用户

**请求头：**
```
Authorization: Bearer {admin_token}
```

**请求体：**
```json
{
  "username": "newuser",
  "password": "Password123",
  "role": "USER",
  "expireTime": "2027-12-31 23:59:59"
}
```

**响应示例：**
```json
{
  "success": true,
  "message": "创建用户成功",
  "userId": 4,
  "licenseKey": "ABCD1234EFGH5678IJKL9012MNOP3456"
}
```

#### PUT /api/admin/users/{id}/expire
修改用户有效期

**请求头：**
```
Authorization: Bearer {admin_token}
```

**请求体：**
```json
{
  "expireTime": "2028-12-31 23:59:59"
}
```

**响应示例：**
```json
{
  "success": true,
  "message": "更新成功"
}
```

#### PUT /api/admin/users/{id}/status
修改用户状态

**请求头：**
```
Authorization: Bearer {admin_token}
```

**请求体：**
```json
{
  "status": "DISABLED"
}
```

**响应示例：**
```json
{
  "success": true,
  "message": "更新成功"
}
```

### 3.3 认证接口

#### POST /api/auth/login
用户登录

**请求体：**
```json
{
  "username": "test002",
  "password": "Test123456"
}
```

**响应示例（成功）：**
```json
{
  "token": "b4ab73f5-2f2f-405f-b61e-13e11d0f2bbe",
  "user": {
    "id": 3,
    "username": "test002",
    "role": "USER",
    "userType": "NORMAL_USER",
    "status": "ACTIVE",
    "subscriptionStartTime": null,
    "subscriptionEndTime": "2027-09-15T23:59:59",
    "createdAt": "2026-09-15T23:17:19.076893",
    "lastLoginAt": "2026-09-15T23:17:52.3422968",
    "shopCount": 0
  }
}
```

**响应示例（过期）：**
```json
{
  "success": false,
  "message": "账号已过期，请联系管理员续期"
}
```

---

## 四、权限规则

### 4.1 角色权限

| 角色 | 权限说明 |
|------|----------|
| ADMIN | 永久有效，拥有所有功能，可管理其他用户 |
| USER | 根据 expire_time 判断有效性，仅能访问业务功能 |

### 4.2 授权检查规则

#### ADMIN 用户
- 永不过期
- expire_time 设置为 2099-12-31 23:59:59
- 可以访问所有 API，包括 `/api/admin/*`

#### USER 用户
- 当前时间 <= expire_time：允许使用
- 当前时间 > expire_time：禁止业务操作
- 不能访问 `/api/admin/*` 接口（返回 403）

### 4.3 拦截器规则

**放行的接口：**
- `/api/auth/login` - 登录接口
- `/api/auth/register` - 注册接口
- 静态资源（/static, /assets, /logo, *.html, *.js, *.css, *.jpg, *.png, *.ico）

**拦截的接口：**
- 所有 `/api/**` 业务接口

**拦截逻辑：**
1. 检查 JWT Token 是否存在
2. 验证 Token 有效性
3. 获取用户信息
4. 调用 LicenseService.checkUserLicense() 检查授权
5. 授权失败返回 HTTP 403 + `{"code":"ACCOUNT_EXPIRED","message":"账号已过期，请联系管理员续期"}`

### 4.4 管理员接口权限

所有 `/api/admin/*` 接口：
- 仅 ADMIN 角色可访问
- USER 角色访问返回 403 + `{"code":"ERROR","message":"无权访问，仅管理员可操作"}`

---

## 五、测试结果

### 5.1 管理员账号测试

**账号信息：**
- 用户名：admin
- 密码：Admin@2026#Secure
- 角色：ADMIN
- 过期时间：2099-12-31 23:59:59
- License Key：自动生成

**测试结果：**
- ✅ 登录成功
- ✅ 永久有效
- ✅ 可访问所有接口
- ✅ 可管理其他用户

### 5.2 过期用户测试（test001）

**账号信息：**
- 用户名：test001
- 密码：Test123456
- 角色：USER
- 过期时间：2026-09-14 23:59:59（昨天）
- License Key：0BD07FF9F3384C9CA91FCD4CC5099BEC

**测试结果：**
- ✅ 登录失败
- ✅ 返回错误：`{"success":false,"message":"账号已过期，请联系管理员续期"}`
- ✅ 状态自动更新为 EXPIRED

### 5.3 有效用户测试（test002）

**账号信息：**
- 用户名：test002
- 密码：Test123456
- 角色：USER
- 过期时间：2027-09-15 23:59:59（一年后）
- License Key：6932393B210743C9965E9B37AC3C88AB

**测试结果：**
- ✅ 登录成功
- ✅ 返回 Token 和用户信息
- ✅ 可以访问业务接口
- ✅ 剩余天数：365 天

### 5.4 权限隔离测试

**测试场景：**
使用 test002（USER）的 Token 访问管理员接口

**测试结果：**
- ✅ 访问 `/api/admin/users` 返回 403
- ✅ 返回错误：`{"code":"ERROR","message":"无权访问，仅管理员可操作"}`

---

## 六、核心功能说明

### 6.1 LicenseService

**核心方法：**

- `checkUserLicense(User user)` - 检查用户授权有效性
  - ADMIN 永久返回 true
  - USER 根据 expire_time 判断
  - 过期自动更新状态为 EXPIRED

- `generateLicenseKey()` - 生成唯一的 32 位十六进制 License Key

- `extendLicense(User user, LocalDateTime expireTime)` - 延长用户授权期限

- `checkLicenseWithResult(User user)` - 检查授权并返回详细结果对象

### 6.2 LicenseInterceptor

**功能：**
- 拦截所有 API 请求（除登录/注册）
- 验证 JWT Token
- 检查用户授权状态
- 授权失败返回 403 + ACCOUNT_EXPIRED

**放行策略：**
- 登录/注册接口
- 静态资源文件

### 6.3 LicenseValidator

**工具方法：**
- `isAdmin(User user)` - 判断是否管理员
- `isExpired(User user)` - 判断是否过期
- `getRemainingDays(User user)` - 获取剩余天数
- `isExpiringSoon(User user)` - 判断是否即将过期（7天内）
- `isValidLicenseKeyFormat(String licenseKey)` - 验证 License Key 格式

---

## 七、安全特性

1. **密码加密**：使用 BCrypt 加密存储
2. **Token 验证**：JWT Token 验证用户身份
3. **权限隔离**：ADMIN 和 USER 严格分离
4. **自动过期**：过期用户自动更新状态为 EXPIRED
5. **License Key**：每个用户唯一的 32 位授权密钥
6. **设备绑定**：预留 deviceId 和 deviceName 字段，可扩展设备绑定功能

---

## 八、后续扩展建议

### 8.1 设备绑定
- 绑定用户到特定设备
- 限制多设备登录
- 设备授权管理

### 8.2 授权码激活
- 用户使用 License Key 激活账号
- 批量生成授权码
- 授权码使用记录

### 8.3 到期提醒
- 7 天内到期提醒
- 邮件通知
- 系统内提示

### 8.4 授权统计
- 用户使用时长统计
- 授权到期分布
- 续期转化率

---

## 九、部署说明

### 9.1 数据库迁移

系统启动时会自动创建新增字段：
- license_key
- device_id
- device_name

已有用户需要手动生成 License Key 或重新创建。

### 9.2 默认管理员账号

- 用户名：admin
- 密码：Admin@2026#Secure
- 过期时间：2099-12-31 23:59:59
- 首次启动自动创建

### 9.3 配置项

无需额外配置，授权系统开箱即用。

---

## 十、总结

✅ **已完成：**
1. 数据库字段扩展（license_key, device_id, device_name）
2. License 授权服务模块
3. API 拦截器实现
4. 管理员用户管理接口
5. 权限检查和隔离
6. 测试用户创建和验证

✅ **测试通过：**
1. 管理员账号永久有效
2. 过期用户无法登录
3. 有效用户正常使用
4. 权限隔离正确

✅ **API 完整：**
1. 授权信息查询接口
2. 授权状态检查接口
3. 用户管理接口（创建、查询、更新）
4. 登录时授权验证

---

**实施完成时间：** 2026-09-15 23:18
**状态：** 生产就绪 ✅
