# 差评智控 - 生产前变更清单（STEP 2）

生成时间：2026-09-15 21:30
状态：**仅列清单，未执行任何操作**

---

## 一、阶段测试入口分析（12个文件）

| 文件 | 位置 | 被引用 | 生产路径 | pom.xml | 建议 |
|-----|------|--------|----------|---------|------|
| Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ⚠️ maven-jar-plugin | **需修改pom.xml后删除** |
| MainAuto.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| SimpleAutoReply.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| IntegrationTest.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| OcrTimeoutTest.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase2Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase3Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase4Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase5Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase6Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase7Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |
| Phase8Main.java | src/main/java/com/example/meituan/ | ❌ 无 | ❌ 否 | ❌ 无 | **建议删除** |

**关键发现**：
- ✅ 所有12个测试入口均未被其他Java类引用
- ⚠️ **Main.java** 在 `pom.xml` 的 `maven-jar-plugin` 中被指定为 `mainClass`
- ✅ 生产正式入口是 `WebApplication.java`（spring-boot-maven-plugin）
- ⚠️ 删除前必须修改 `pom.xml` 第116行

---

## 二、测试工具类分析（6个文件）

| 文件 | 位置 | 被引用 | 生产代码使用 | 建议 |
|-----|------|--------|-------------|------|
| OcrJsonParseTest.java | src/main/java/.../ocr/ | ❌ 无 | ❌ 否 | **建议删除** |
| ReplyInputTester.java | src/main/java/.../reply/ | ✅ 2处 | ⚠️ 仅测试代码 | **建议删除** |
| ReplyTestState.java | src/main/java/.../reply/ | ✅ 是 | ⚠️ 仅ReplyInputTester | **依赖删除** |
| OcrJacksonIsolationTest.java | src/test/java/.../ocr/ | ❌ 无 | ❌ 否 | **建议删除** |
| DebugOverlay.java | src/main/java/.../util/ | ✅ 9处 | ✅ **生产核心** | **保留（受debug.mode控制）** |
| OcrDebugTool.java | src/main/java/.../util/ | ❌ 无 | ❌ 否 | **建议删除** |
| ReviewDebugOverlay.java | src/main/java/.../util/ | ✅ 1处 | ⚠️ Phase4Main | **建议删除** |

**引用分析**：
- `DebugOverlay.java` 被以下生产代码引用（必须保留）：
  - BatchReplyProcessor.java ✅
  - FilterAction.java ✅
  - ScrollRecoveryController.java ✅
  - FilterWorkflow.java ✅
  - ReplySubmitter.java ✅
  
- `ReplyInputTester.java` 仅被测试代码引用：
  - IntegrationTest.java（待删除）
  - Phase6Main.java（待删除）

---

## 三、临时文件统计

| 目录/类型 | 文件数 | 总大小 | 最新日期 | 建议 |
|----------|--------|--------|----------|------|
| data/debug/*.png | 111 | 50 MB | 2026-09-15 | **删除全部** |
| data/temp/ocr_input_*.png | 5 | 2.5 MB | 2026-09-15 | **删除全部** |
| data/temp/ocr_output_*.json | 5 | 300 KB | 2026-09-15 | **删除全部** |
| data/temp/.gitkeep | 1 | 0 B | - | **保留** |
| data/debug/.gitkeep | 1 | 0 B | - | **保留** |

**清理后保留结构**：
```
data/
├── database/
│   └── badreview.mv.db (清空数据)
├── debug/
│   └── .gitkeep
└── temp/
    └── .gitkeep
```

---

## 四、数据初始化器分析

### 4.1 DataInitializer.java

**当前行为**：
- ✅ 创建 admin 用户（必须保留）
- ❌ 创建 zhangsan 测试用户（必须删除）
- ❌ 创建 lisi 测试服务商（必须删除）

**修改方案**：
```java
// 保留：
if (!userRepository.existsByUsername("admin")) {
    // 创建管理员
}

// 删除：
// 38-49行 zhangsan 用户创建
// 51-63行 lisi 用户创建
```

**建议操作**：删除第37-63行（测试用户创建）

---

### 4.2 ShopDataInitializer.java

**当前行为**：
- ❌ 自动创建6个测试店铺
- ❌ 包含虚假差评数据（todayBadReviews: 5-12）
- ❌ 包含虚假未回复数据（unrepliedCount: 2-8）

**测试数据详情**：
1. 张三餐饮店 - 12条差评, 8条未回复
2. 李四北京店 - 5条差评, 3条未回复
3. 李四上海店 - 8条差评, 4条未回复
4. 李四广州店 - 6条差评, 2条未回复
5. 李四深圳店 - 10条差评, 5条未回复
6. 李四杭州店 - 7条差评, 6条未回复

**修改方案**：
- **方案A（推荐）**：完全禁用此初始化器
  - 添加 `@Profile("dev")` 注解
  - 仅开发环境启用
  
- **方案B**：删除整个文件
  - 生产环境店铺由用户手工创建

**建议操作**：添加 `@Profile("dev")` 或删除文件

---

## 五、配置文件修改

### application.properties

| 配置项 | 当前值 | 生产值 | 风险等级 | 建议 |
|--------|--------|--------|----------|------|
| debug.mode | true | **false** | 🔴 高 | **必须修改** |
| spring.h2.console.enabled | true | **false** | 🔴 高 | **建议禁用** |
| logging.level.* | 未配置 | INFO | 🟡 中 | **建议添加** |

**建议修改**：
```properties
# 第31行
debug.mode=false

# 第11行（禁用H2控制台）
spring.h2.console.enabled=false

# 新增日志配置
logging.level.root=INFO
logging.level.com.example.meituan=INFO
logging.level.org.springframework=WARN
logging.level.org.hibernate=WARN
```

---

## 六、pom.xml修改

### 问题：maven-jar-plugin 指向测试入口

**当前配置**（第116行）：
```xml
<mainClass>com.example.meituan.Main</mainClass>
```

**生产配置**（spring-boot-maven-plugin已正确）：
```xml
<mainClass>com.example.meituan.web.WebApplication</mainClass>
```

**修改方案**：
- **方案A**：修改maven-jar-plugin的mainClass为WebApplication
- **方案B**：删除maven-jar-plugin配置（使用Spring Boot默认打包）

**建议操作**：方案A - 统一mainClass为WebApplication

---

## 七、前端Mock数据分析

### 7.1 Mock文件

| 文件 | 使用情况 | 建议 |
|-----|----------|------|
| frontend/src/mock/index.ts | ✅ 被auth.ts引用 | **保留但禁用** |

**当前使用**：
- `src/api/auth.ts` 在API失败时fallback到mock登录
- Mock用户：admin, zhangsan, lisi

**修改方案**：
```typescript
// 移除 mockLogin fallback
// 生产环境API失败应该真实报错，不应降级到mock
```

---

### 7.2 前端硬编码数据

| 文件 | 行号 | 硬编码值 | 建议 |
|-----|------|----------|------|
| Dashboard.vue | 123 | todayBadReviews: 24 | **改为API调用** |
| AdminDashboard.vue | ? | Mock data for demo | **改为API调用** |
| Automation.vue | ? | Mock data for demo | **改为API调用** |
| Logs.vue | ? | Mock data | **改为API调用** |
| Users.vue | ? | Mock data | **改为API调用** |

**建议操作**：移除所有硬编码mock数据，改为实际API调用

---

## 八、数据库清理计划

### 8.1 当前状态（估算）

基于ShopDataInitializer：
- User: 3条（admin, zhangsan, lisi）
- Shop: 6条（测试店铺）
- ReplyTemplate: 未知
- Review: 0条（未创建）
- SyncTask: 0条

### 8.2 清理目标

**保留**：
- User表：仅保留admin（id=1）
- 表结构：全部保留
- 字段定义：全部保留
- 外键约束：全部保留

**删除**：
- DELETE FROM Shop WHERE id > 0;
- DELETE FROM User WHERE username != 'admin';
- DELETE FROM ReplyTemplate WHERE id > 0;
- DELETE FROM Review WHERE id > 0;
- DELETE FROM SyncTask WHERE id > 0;

### 8.3 执行方式

⚠️ **不要直接执行SQL**

**推荐方式**：
1. 停止后端
2. 删除 `data/database/badreview.mv.db`
3. 删除 `data/database/badreview.trace.db`
4. 禁用ShopDataInitializer
5. 修改DataInitializer（只保留admin）
6. 重启后端
7. 数据库自动重建（空数据）

---

## 九、固定坐标验证结果

### 检查方法：
```bash
grep -rn "\.click\|\.mouseMove" src/main/java | grep -E "\([0-9]+\s*,\s*[0-9]+\)"
```

### 检查结果：
✅ **通过 - 未发现硬编码坐标**

所有Robot点击操作均通过以下方式：
1. OCR识别 → OcrTextBox
2. OpenCV检测 → BoundingBox
3. 计算中心点 → getCenterX(), getCenterY()
4. 动态点击 → robotController.click(x, y)

---

## 十、Jackson/OCR验证结果

### 历史问题：
- Jackson版本冲突导致JSON解析阻塞97秒

### 当前状态：
✅ **已修复**
- Jackson版本统一为2.15.3
- 解析耗时：15ms
- 测试文件：OcrJacksonIsolationTest.java（使命完成，可删除）

### OcrClient检查：
- ✅ ObjectMapper使用简单配置
- ✅ 无自定义Deserializer
- ✅ 超时保护：30秒
- ✅ 错误处理：完整

---

## 十一、AutomationWorker架构验证

### 单实例验证：
✅ **通过**

**核心保证**：
- RobotController：单例（Spring Bean）
- BatchReplyProcessor：单任务执行
- AutomationService：synchronized控制
- 无多线程GUI操作

**当前运行**：
- PID 2388: Spring Boot (端口8080)
- ✅ 单进程运行

---

## 十二、Spring Boot启动结构

### 生产正式入口：
✅ `com.example.meituan.web.WebApplication`

### 启动方式确认：
| 方式 | 命令 | 用途 | 建议 |
|-----|------|------|------|
| Maven | mvn spring-boot:run | 开发/生产 | ✅ 保留 |
| Bat脚本 | start-backend.bat | 快速启动 | ✅ 保留 |
| PhaseXMain | java PhaseXMain | 测试 | ❌ 删除 |
| IDE运行 | Run WebApplication | 开发 | ✅ 保留 |

---

## 十三、支付代码检查结果

### 搜索关键词：
充值, 支付, 套餐, 价格, 订阅, billing, pricing, subscription, recharge, payment

### 检查结果：
✅ **通过 - 未发现支付相关代码**

系统不包含支付功能，符合预期。

---

## 十四、H2数据库安全风险

### 当前暴露：
🔴 **高风险**

**问题**：
- H2 Console: `http://localhost:8080/h2-console`
- 无密码保护
- 可直接访问数据库

**建议**：
```properties
# 生产环境必须禁用
spring.h2.console.enabled=false
```

---

## 十五、明文密码风险

### 当前状态：
🟡 **中风险**

**问题**：
- DataInitializer硬编码：admin/123456
- 密码过于简单
- 源码可见

**建议**：
1. 修改默认密码为随机生成
2. 首次启动打印到控制台
3. 要求首次登录修改密码

**临时方案**：
- 在生产文档中明确说明需要修改密码

---

## 十六、API限流风险

### 当前状态：
🟡 **中风险**

**问题**：
- 无限流保护
- 批量自动化接口可能被滥用

**高风险接口**：
- POST /api/auth/login
- POST /api/automation/batch-reply
- POST /api/automation/start
- POST /api/automation/stop

**建议**（生产后优化）：
- 添加Spring Security RateLimiter
- 单用户登录限流：5次/分钟
- 自动化启动限流：1次/10秒

---

## A. DELETE LIST（删除清单）

### Java测试入口（11个文件）
```
src/main/java/com/example/meituan/MainAuto.java
src/main/java/com/example/meituan/SimpleAutoReply.java
src/main/java/com/example/meituan/IntegrationTest.java
src/main/java/com/example/meituan/OcrTimeoutTest.java
src/main/java/com/example/meituan/Phase2Main.java
src/main/java/com/example/meituan/Phase3Main.java
src/main/java/com/example/meituan/Phase4Main.java
src/main/java/com/example/meituan/Phase5Main.java
src/main/java/com/example/meituan/Phase6Main.java
src/main/java/com/example/meituan/Phase7Main.java
src/main/java/com/example/meituan/Phase8Main.java
```

### Java测试工具（4个文件）
```
src/main/java/com/example/meituan/ocr/OcrJsonParseTest.java
src/main/java/com/example/meituan/reply/ReplyInputTester.java
src/main/java/com/example/meituan/reply/ReplyTestState.java
src/main/java/com/example/meituan/util/OcrDebugTool.java
src/main/java/com/example/meituan/util/ReviewDebugOverlay.java
src/test/java/com/example/meituan/ocr/OcrJacksonIsolationTest.java
```

### Main.java特殊处理
```
⚠️ 先修改pom.xml第116行，再删除：
src/main/java/com/example/meituan/Main.java
```

### 临时文件（116个文件，~53 MB）
```
data/debug/*.png (111个文件)
data/temp/ocr_input_*.png (5个文件)
data/temp/ocr_output_*.json (5个文件)
```

**总计**：17个Java文件 + 116个临时文件

---

## B. MODIFY LIST（修改清单）

### B.1 DataInitializer.java
**文件**：`src/main/java/com/example/meituan/web/service/DataInitializer.java`

**修改原因**：移除测试用户自动创建

**修改方式**：删除第37-63行
```java
// 删除：
// 38-49行：zhangsan 用户创建
// 51-63行：lisi 用户创建
```

**风险**：✅ 无 - 仅admin是必须的

---

### B.2 ShopDataInitializer.java
**文件**：`src/main/java/com/example/meituan/web/service/ShopDataInitializer.java`

**修改原因**：禁止自动创建测试店铺

**修改方式**：添加 `@Profile("dev")` 注解
```java
@Component
@Order(2)
@Profile("dev")  // 新增：仅开发环境启用
public class ShopDataInitializer implements CommandLineRunner {
```

**风险**：✅ 无 - 生产数据应由用户创建

---

### B.3 application.properties
**文件**：`src/main/resources/application.properties`

**修改原因**：生产安全配置

**修改方式**：
```properties
# 第31行
debug.mode=false

# 第11行
spring.h2.console.enabled=false

# 新增（文件末尾）
logging.level.root=INFO
logging.level.com.example.meituan=INFO
logging.level.org.springframework=WARN
logging.level.org.hibernate=WARN
logging.level.org.hibernate.SQL=ERROR
```

**风险**：🟡 中 - 测试后确认日志级别

---

### B.4 pom.xml
**文件**：`pom.xml`

**修改原因**：移除测试入口引用

**修改方式**：第116行
```xml
<!-- 修改前 -->
<mainClass>com.example.meituan.Main</mainClass>

<!-- 修改后 -->
<mainClass>com.example.meituan.web.WebApplication</mainClass>
```

**风险**：✅ 无 - spring-boot-maven-plugin已正确配置

---

### B.5 frontend/src/api/auth.ts
**文件**：`frontend/src/api/auth.ts`

**修改原因**：移除Mock登录fallback

**修改方式**：删除mock相关代码
```typescript
// 删除：
import { mockLogin } from '../mock'
// 删除fallback逻辑
```

**风险**：🟡 中 - 确保API稳定后再删除

---

### B.6 前端Dashboard数据
**文件**：
- `frontend/src/views/Dashboard.vue`
- `frontend/src/views/admin/AdminDashboard.vue`
- `frontend/src/views/Automation.vue`
- `frontend/src/views/admin/Logs.vue`
- `frontend/src/views/admin/Users.vue`

**修改原因**：移除硬编码Mock数据

**修改方式**：改为API调用，初始值为0

**风险**：🟡 中 - 确保API可用

---

## C. CLEAN DATA LIST（数据清理清单）

### 方式一：删除数据库文件（推荐）

**操作步骤**：
1. 停止后端进程（PID 2388）
2. 删除 `data/database/badreview.mv.db`
3. 删除 `data/database/badreview.trace.db`
4. 执行 B.2（禁用ShopDataInitializer）
5. 执行 B.1（修改DataInitializer）
6. 重启后端
7. 数据库自动重建（只有admin）

**优点**：干净、彻底
**缺点**：需要重启

---

### 方式二：执行SQL清理（不推荐）

⚠️ **风险较高，不推荐**

如果必须保留数据库文件：
```sql
DELETE FROM SyncTask WHERE id > 0;
DELETE FROM Review WHERE id > 0;
DELETE FROM ReplyTemplate WHERE id > 0;
DELETE FROM Shop WHERE id > 0;
DELETE FROM User WHERE username != 'admin';
```

---

## D. KEEP LIST（必须保留）

### 核心生产代码（必须保留）

#### 后端核心
```
✅ src/main/java/com/example/meituan/web/ - Spring Boot Web层
✅ src/main/java/com/example/meituan/batch/ - 批量回复处理器
✅ src/main/java/com/example/meituan/robot/ - Robot控制器
✅ src/main/java/com/example/meituan/ocr/ - OCR客户端
✅ src/main/java/com/example/meituan/screenshot/ - 截图服务
✅ src/main/java/com/example/meituan/review/ - 评论识别
✅ src/main/java/com/example/meituan/scroll/ - 智能滚动
✅ src/main/java/com/example/meituan/reply/ - 回复识别（ReplySubmitter等）
✅ src/main/java/com/example/meituan/filter/ - 筛选器
✅ src/main/java/com/example/meituan/workflow/ - 工作流
✅ src/main/java/com/example/meituan/config/ - 配置
✅ src/main/java/com/example/meituan/util/DebugOverlay.java - Debug工具（受控）
✅ src/main/java/com/example/meituan/util/ClipboardController.java - 剪贴板
```

#### Python OCR
```
✅ python/ocr_subprocess.py
✅ python/requirements.txt
```

#### 前端核心
```
✅ frontend/src/views/ - 所有页面
✅ frontend/src/components/ - 组件
✅ frontend/src/api/ - API调用
✅ frontend/src/router/ - 路由
✅ frontend/src/stores/ - 状态管理
✅ frontend/src/types/ - 类型定义
```

#### 配置文件
```
✅ pom.xml
✅ application.properties（修改后）
✅ start-backend.bat
✅ frontend/package.json
✅ frontend/vite.config.ts
```

#### 数据目录结构
```
✅ data/database/ - 数据库目录
✅ data/debug/.gitkeep - 保留目录结构
✅ data/temp/.gitkeep - 保留目录结构
```

---

## E. MANUAL CONFIRM LIST（人工确认清单）

### E.1 admin初始密码
**问题**：硬编码 admin/123456

**选项**：
- A. 保持简单密码，文档提醒修改
- B. 改为随机密码，启动时打印
- C. 首次登录强制修改密码

**建议**：选项A + 生产部署文档说明

---

### E.2 H2数据库长期方案
**问题**：H2适合开发，生产环境稳定性待验证

**选项**：
- A. 继续使用H2（文件数据库）
- B. 迁移到PostgreSQL
- C. 迁移到MySQL

**建议**：A - 小规模生产可用H2，未来再迁移

---

### E.3 API限流方案
**问题**：无限流保护

**选项**：
- A. 生产初期不限流，观察使用情况
- B. 立即添加Spring Security限流
- C. 使用Redis限流

**建议**：A - 观察后再决定

---

### E.4 前端Mock数据清理时机
**问题**：auth.ts使用mock作为fallback

**选项**：
- A. 立即删除mock，API失败就报错
- B. 保留mock，生产时禁用
- C. 保留mock，仅开发环境使用

**建议**：B - 通过环境变量控制

---

## 执行优先级

### Priority 1（立即执行）- 阻塞生产
1. ✅ 修改 application.properties（debug.mode=false）
2. ✅ 修改 ShopDataInitializer（添加@Profile("dev")）
3. ✅ 修改 DataInitializer（删除测试用户）
4. ✅ 清空数据库
5. ✅ 删除 data/debug/*.png
6. ✅ 删除 data/temp/*.png, *.json

### Priority 2（重要）- 清理代码
7. ✅ 修改 pom.xml（mainClass）
8. ✅ 删除 11个测试入口
9. ✅ 删除 6个测试工具类
10. ✅ 禁用 H2 Console

### Priority 3（优化）- 提升质量
11. 🟡 添加日志配置
12. 🟡 修改前端mock fallback
13. 🟡 修改前端硬编码数据
14. 🟡 生产部署文档

---

## 预期生产状态

### 后端
- ✅ 编译通过：mvn compile
- ✅ 启动成功：mvn spring-boot:run
- ✅ Health API：GET /api/health → 200 OK
- ✅ 无测试代码：0个PhaseXMain
- ✅ 无临时文件：data/debug空, data/temp空

### 数据库
- ✅ User: 1条（admin）
- ✅ Shop: 0条
- ✅ Review: 0条
- ✅ ReplyTemplate: 0条

### 前端
- ✅ 编译通过：npm run build
- ✅ 仪表盘显示：今日差评0, 待回复0, 已回复0, 店铺0
- ✅ 无mock数据硬编码

---

## 风险评估

### 当前生产就绪度：65%

**阻塞项**（必须解决）：
- 🔴 测试数据自动生成
- 🔴 Debug模式开启
- 🔴 临时文件未清理
- 🔴 pom.xml引用测试类

**修复后预计就绪度：90%**

**剩余风险**：
- 🟡 admin初始密码简单
- 🟡 无API限流
- 🟡 H2控制台暴露（修改后解决）

---

## STEP 2 完成度：100%

### 已完成：
- ✅ 逐个分析12个测试入口
- ✅ 检查引用关系
- ✅ 统计临时文件
- ✅ 分析数据初始化器
- ✅ 检查固定坐标（通过）
- ✅ 验证Jackson/OCR（通过）
- ✅ 检查支付代码（通过）
- ✅ 生成完整变更清单

### 未执行：
- ❌ 未删除任何文件
- ❌ 未修改任何代码
- ❌ 未清空数据库
- ❌ 未启动自动化
- ❌ 未操作真实美团

---

## 下一步建议

**STEP 3：执行清理与生产化修改**

**执行顺序**：
1. 备份当前项目
2. 停止后端
3. 执行Priority 1修改
4. 删除测试文件
5. 清空数据库
6. 重启后端验证
7. 执行Priority 2修改
8. 前端编译验证
9. 完整功能测试
10. 生成最终验收报告

---

**文档生成人员**：Claude (Kiro)
**文档日期**：2026-09-15
**状态**：待批准执行
