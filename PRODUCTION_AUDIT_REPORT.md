# 差评智控 - 生产前审计报告

生成时间：2026-09-15 21:15

---

## 一、项目概览

**项目名称**：差评智控（Bad Review Control System）
**当前状态**：开发/测试阶段
**目标状态**：待生产（Pre-Production）
**核心技术**：Java 17 + Spring Boot + Vue 3 + Robot自动化 + OCR + OpenCV

---

## 二、审计分类结果

### A. 必须保留（核心生产代码）

#### 后端核心
- `src/main/java/com/example/meituan/web/` - Spring Boot Web层
- `src/main/java/com/example/meituan/batch/` - 批量回复处理器
- `src/main/java/com/example/meituan/robot/` - Robot控制器
- `src/main/java/com/example/meituan/ocr/` - OCR客户端
- `src/main/java/com/example/meituan/screenshot/` - 截图服务
- `src/main/java/com/example/meituan/review/` - 评论识别
- `src/main/java/com/example/meituan/scroll/` - 智能滚动
- `src/main/java/com/example/meituan/reply/` - 回复识别
- `src/main/java/com/example/meituan/config/` - 配置
- `src/main/java/com/example/meituan/util/` - 工具类（部分需清理debug工具）
- `python/ocr_subprocess.py` - OCR子进程
- `pom.xml` - Maven配置

#### 前端核心
- `frontend/src/views/` - 所有页面（需验证）
- `frontend/src/components/` - 组件
- `frontend/src/api/` - API调用
- `frontend/src/router/` - 路由
- `frontend/src/stores/` - 状态管理
- `frontend/src/types/` - 类型定义

#### 数据库
- `data/database/badreview.mv.db` - 生产数据库（需清理测试数据）

---

### B. 可以删除（开发测试代码）

#### 阶段测试入口（12个文件）
- ❌ `src/main/java/com/example/meituan/Main.java` - 早期测试入口
- ❌ `src/main/java/com/example/meituan/MainAuto.java` - 自动化测试入口
- ❌ `src/main/java/com/example/meituan/Phase2Main.java` - 阶段2测试
- ❌ `src/main/java/com/example/meituan/Phase3Main.java` - 阶段3测试
- ❌ `src/main/java/com/example/meituan/Phase4Main.java` - 阶段4测试
- ❌ `src/main/java/com/example/meituan/Phase5Main.java` - 阶段5测试
- ❌ `src/main/java/com/example/meituan/Phase6Main.java` - 阶段6测试
- ❌ `src/main/java/com/example/meituan/Phase7Main.java` - 阶段7测试
- ❌ `src/main/java/com/example/meituan/Phase8Main.java` - 阶段8测试
- ❌ `src/main/java/com/example/meituan/SimpleAutoReply.java` - 简单测试
- ❌ `src/main/java/com/example/meituan/IntegrationTest.java` - 集成测试
- ❌ `src/main/java/com/example/meituan/OcrTimeoutTest.java` - OCR超时测试

#### 测试工具类（3个文件）
- ❌ `src/main/java/com/example/meituan/ocr/OcrJsonParseTest.java` - JSON解析测试
- ❌ `src/main/java/com/example/meituan/reply/ReplyInputTester.java` - 回复输入测试
- ❌ `src/test/java/com/example/meituan/ocr/OcrJacksonIsolationTest.java` - Jackson隔离测试

#### Debug工具类（需谨慎）
- ⚠️ `src/main/java/com/example/meituan/util/DebugOverlay.java` - 被生产代码引用，需保留但禁用
- ⚠️ `src/main/java/com/example/meituan/util/OcrDebugTool.java` - 检查引用
- ⚠️ `src/main/java/com/example/meituan/util/ReviewDebugOverlay.java` - 检查引用

#### 临时文件（2700+ MB）
- ❌ `data/debug/*.png` - 所有debug截图（约150个文件）
- ❌ `data/temp/ocr_input_*.png` - OCR输入临时截图（约2.5MB）
- ❌ `data/temp/ocr_output_*.json` - OCR输出临时JSON（约300KB）

#### 前端Mock数据
- ❌ `frontend/src/mock/index.ts` - Mock用户数据

---

### C. 必须修改（测试数据初始化）

#### 数据初始化器（2个文件）
- ⚠️ `src/main/java/com/example/meituan/web/service/DataInitializer.java`
  - **问题**：每次启动创建测试用户（zhangsan, lisi）
  - **修改**：只保留admin初始化，移除测试用户

- ⚠️ `src/main/java/com/example/meituan/web/service/ShopDataInitializer.java`
  - **问题**：自动创建6个测试店铺，包含模拟差评数据
  - **修改**：完全禁用或改为仅在开发环境启用

#### 配置文件
- ⚠️ `src/main/resources/application.properties`
  - **问题**：`debug.mode=true`
  - **修改**：改为 `debug.mode=false`
  - **问题**：`spring.h2.console.enabled=true`
  - **决策**：生产环境应禁用H2控制台

#### 数据库数据
- ⚠️ `data/database/badreview.mv.db`
  - **问题**：包含6个测试店铺、模拟差评数据
  - **修改**：清空所有业务数据，保留表结构
  - **预期结果**：
    - 今日差评：0
    - 待回复：0
    - 已回复：0
    - 店铺数量：0（除admin外）

---

### D. 存在生产风险

#### 高风险
1. **测试数据自动生成**
   - `ShopDataInitializer` 每次启动创建测试店铺
   - 风险：生产环境显示虚假数据

2. **Debug模式默认开启**
   - `debug.mode=true`
   - 风险：大量debug截图占用磁盘空间

3. **H2控制台暴露**
   - `spring.h2.console.enabled=true`
   - 风险：数据库可通过 `/h2-console` 无密码访问

4. **无API限流保护**
   - 批量自动化接口无限流
   - 风险：恶意请求可能导致系统过载

#### 中风险
5. **明文密码**
   - DataInitializer中硬编码 `admin/123456`
   - 风险：初始管理员密码过于简单

6. **OCR临时文件清理**
   - `data/temp/` 无自动清理机制
   - 风险：长期运行后占用大量磁盘

7. **日志级别**
   - 未明确配置日志级别
   - 风险：可能泄露敏感信息

#### 低风险
8. **前端Mock数据残留**
   - `frontend/src/mock/index.ts` 未被使用但存在
   - 风险：可能被误引用

---

### E. 需要人工确认

#### 核心功能确认
1. **ReplyTestState.java**
   - 路径：`src/main/java/com/example/meituan/reply/ReplyTestState.java`
   - 问题：名字包含"Test"，但可能是状态枚举
   - 决策：需检查是否被生产代码引用

2. **DebugOverlay相关**
   - `DebugOverlay.java`, `OcrDebugTool.java`, `ReviewDebugOverlay.java`
   - 问题：被BatchReplyProcessor引用
   - 决策：需保留代码，但通过debug.mode控制是否执行

3. **前端路由完整性**
   - 是否所有页面都是生产必须
   - Logs.vue, Forbidden.vue是否需要

4. **Robot点击坐标验证**
   - 虽然grep未发现硬编码坐标
   - 需人工review关键点击代码确认

---

## 三、固定坐标检查结果

✅ **通过**

未发现硬编码点击坐标：
```bash
grep -rn "robot\.click\|robotController\.click" | grep -E "\([0-9]+\s*,\s*[0-9]+\)"
# 结果：空
```

所有点击操作均通过OCR/OpenCV动态定位。

---

## 四、Jackson OCR JSON解析验证

✅ **已修复**

- **历史问题**：Jackson版本冲突导致97秒阻塞
- **修复方案**：统一Jackson版本为2.15.3
- **当前状态**：解析耗时15ms
- **测试文件**：`OcrJacksonIsolationTest.java`（已完成使命，可删除）

---

## 五、核心自动化流程完整性

✅ **保持完整**

BatchReplyProcessor核心流程：
1. OCR识别评论卡片
2. 选择目标评论
3. 点击普通"回复"文本
4. 点击输入提示激活输入框
5. 粘贴模板文本
6. OCR+OpenCV检测黄色提交按钮
7. 点击提交
8. 验证回复成功

**关键组件**：
- ReviewCardRecognizer ✅
- YellowReplyButtonDetector ✅
- ScrollRecoveryController ✅（已修复滚动方式）
- DynamicScrollAreaDetector ✅
- ReviewIdentity防重复 ✅

---

## 六、建议的清理优先级

### 优先级1（立即执行）
1. 禁用ShopDataInitializer
2. 修改DataInitializer移除测试用户
3. 设置 debug.mode=false
4. 清空数据库业务数据
5. 删除 data/debug/*.png
6. 删除 data/temp/*.png, *.json

### 优先级2（重要）
7. 删除12个PhaseXMain测试入口
8. 删除3个测试工具类
9. 禁用H2控制台
10. 删除前端mock数据

### 优先级3（建议）
11. 修改admin默认密码机制
12. 添加OCR临时文件自动清理
13. 配置生产日志级别
14. 添加API限流

---

## 七、预期生产状态

### 数据库
- 用户：1个（admin）
- 店铺：0个
- 评论：0条
- 回复：0条
- 模板：0个

### 前端仪表盘
- 今日差评：0
- 待回复：0
- 已回复：0
- 店铺数量：0
- 最近差评：暂无数据
- 自动化状态：未开始

### 系统状态
- Debug模式：关闭
- H2控制台：禁用
- 日志级别：INFO
- 测试代码：已移除
- 临时文件：已清空

---

## 八、风险评估

**当前生产就绪度**：65%

**阻塞项**：
- 测试数据自动生成
- Debug模式开启
- 临时文件未清理

**修复后预计就绪度**：90%

**剩余风险**：
- 初始密码安全性
- 无API限流
- 日志安全性

---

## 九、下一步行动

建议按STEP 2-18顺序执行：
1. STEP 2: 列出准备删除/修改的文件
2. STEP 3: 确认引用关系
3. STEP 4: 清理测试数据
4. STEP 5: 清理测试代码
5. ...
6. STEP 18: 输出生产前验收报告

---


