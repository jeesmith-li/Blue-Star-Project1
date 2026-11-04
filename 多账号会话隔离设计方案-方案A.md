# 蓝星系统 · 多美团账号会话隔离设计方案（方案 A）

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 编写日期 | 2026-10-26 |
| 状态 | 待评审 |
| 适用版本 | 蓝星 v1.2.2 及后续版本 |
| 关联问题 | 一个蓝星用户管理多个互相独立的美团账号，切换账号需重新登录 |

---

## 1. 背景与问题

甲方实际运营场景：**一个人管理多个美团商家账号**，但这些美团账号在平台侧是彼此独立的，账号之间无法互相切换，每次换号都必须退出并重新登录（含短信/扫码验证）。

当前系统只能同时保持**一个**美团登录态，导致：

- 无法同时绑定多个美团账号下的店铺；
- 切换账号后，上一个账号的登录态被覆盖；
- 每日自动抓取、自动回复、自动申诉只能针对"当前登录的那个账号"，其余账号的店铺无法被无人值守地处理。

目标是把系统从"单浏览器单会话"升级为"**多账号多会话池**"，每个美团账号拥有独立的浏览器数据目录与登录态，可长期并存。

---

## 2. 现状分析

### 2.1 Python 浏览器层

- 全局单例 `_browser` / `_playwright`，同一时刻只维护一个浏览器连接（`python/browser_automation.py` L26-L32、L208-L218）。
- CDP 调试端口固定为 `9222`（L17）。
- 用户数据目录固定为 `data/{edge|chrome}_profile`（L266-L268），所有账号共用同一份 Cookie。
- 登录状态通过当前页面 URL 判断（`check_login_status`，L324-L339），并非按账号维度区分。

**结论：同一 profile 下登录新账号会覆盖旧账号 Cookie，这是"切换必须重登"的根本原因。**

### 2.2 Java 浏览器服务层

- `BrowserAutomationService` 以单例方式管理**一个** Python 子进程（`src/main/java/com/example/meituan/browser/BrowserAutomationService.java` L31-L41）。
- `loginStatus` 虽然按用户名存了 Map，但底层只有一份浏览器会话，Map 只能表示"该用户曾经登录成功"，无法表示"多个美团账号同时在线"。

### 2.3 数据模型

- `Shop.meituanAccount` 只是店铺上的一个字符串字段（`Shop.java` L17-L24），没有独立的账号实体，无法承载 profile 目录、端口、登录状态、最后保活时间等会话信息。
- 店铺类型（服务商用户 / 美团单店）按绑定店铺数量推算（`BrowserAutomationController.bindShop` L137-L141）。

### 2.4 前端

- 店铺页已按美团账号分组展示（`frontend/src/views/Shops.vue` L10-L72）。
- 「添加店铺」是一个串行流程：启动浏览器 → 人工登录 → 抓店 → 勾选绑定（L79-L117），一次只能处理一个账号。

### 2.5 定时任务

- `DailyCaptureScheduler` 按**店铺**遍历执行抓取、回复、负反馈、申诉、食安流程，没有"账号"这一层，无法在账号之间切换会话。

---

## 3. 目标与非目标

### 3.1 目标

1. 一个蓝星用户可维护 **N 个美团账号**，每个账号独立登录、独立保活、互不覆盖。
2. 绑定店铺时按"账号 → 店铺"两级选择，单店账号与服务商账号统一建模。
3. 定时任务按账号分组串行执行，账号级失败隔离。
4. 账号登录失效可感知、可告警、可人工接管重登，不影响其他账号。
5. 保持现有店铺绑定、积分扣减、权限、审计日志逻辑不变。

### 3.2 非目标

- 不做验证码/短信自动识别与自动填写（必须人工兜底）。
- 不做美团接口直调（`mtgsig` 签名绑定请求体，接口直调不可行）。
- 不做跨机器分布式调度（本期仅单机）。
- 不改变"云端只存账号标识、不存美团密码"的既有安全边界。

---

## 4. 方案总览

```
┌──────────────────────────── 蓝星 Web (Spring Boot) ────────────────────────────┐
│  AccountController        ShopController / BrowserAutomationController          │
│        │                          │                                             │
│  MeituanAccountService     BrowserSessionManager（账号 → 会话）                 │
└────────┼──────────────────────────┼─────────────────────────────────────────────┘
         │                          │  JSON 行协议 {"action","account","args"}
         ▼                          ▼
   meituan_accounts 表      Python 会话池 browser_automation.py
   shops.account_id               │
                                  ├── Session(acc_1): CDP 9222, profile data/profiles/acc_1
                                  ├── Session(acc_2): CDP 9223, profile data/profiles/acc_2
                                  └── Session(acc_3): CDP 9224, profile data/profiles/acc_3
                                         │
                                         ▼
                                  Edge/Chrome（各自独立 Cookie / 登录态）
```

核心思想：**账号 = 一个持久化浏览器 profile + 一个 CDP 端口 + 一份独立登录态**。同一账号内的多店铺仍沿用现有 `_switch_to_shop` 切换；跨账号只切换会话，不切换店铺。

---

## 5. 详细设计

### 5.1 数据模型

新增实体 `MeituanAccount`（表 `meituan_accounts`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 自增主键 |
| userId | BIGINT NOT NULL | 归属蓝星用户 |
| accountName | VARCHAR(100) | 美团账号名（如 fwsc_3187_001），登录后回填 |
| displayName | VARCHAR(100) | 展示名/备注，默认同 accountName |
| profileDir | VARCHAR(300) | 独立浏览器数据目录，如 `data/profiles/acc_{id}` |
| cdpPort | INT | 分配的调试端口（默认从 9222 起递增） |
| loginStatus | VARCHAR(20) | `never` / `online` / `expired` / `need_verify` |
| lastLoginAt | DATETIME | 最近一次登录成功时间 |
| lastCheckAt | DATETIME | 最近一次保活检测时间 |
| lastCheckMessage | VARCHAR(500) | 最近检测结果说明 |
| enabled | INTEGER DEFAULT 1 | 是否参与定时任务 |
| createdAt / updatedAt | DATETIME | 审计字段 |

`shops` 表调整：

- 新增 `meituan_account_id BIGINT`（逻辑外键，索引）；
- 保留原 `meituan_account` 字符串字段作为冗余展示与迁移兼容，后续版本可移除。

迁移策略：

- 项目使用 `spring.jpa.hibernate.ddl-auto=update`（`application.properties` L13），新表与新列自动创建；
- 启动时由 `DataInitializer` 执行一次性回填：按 `(user_id, meituan_account)` 去重生成账号记录，分配 `profileDir`/`cdpPort`，并回写 `shops.meituan_account_id`；
- 回填幂等：已存在账号记录则复用，不重复创建。

### 5.2 Python 会话池

改造 `python/browser_automation.py`：

```python
# 原：单例
_browser = None
_playwright = None

# 新：按账号 ID 隔离的会话池
_sessions = {}   # acc_id -> {"proc", "browser", "page", "port", "profile"}
```

关键函数：

| 函数 | 职责 |
|---|---|
| `ensure_session(acc_id)` | 会话不存在/已断开时启动并连接，幂等 |
| `launch_session(acc_id, profile_dir, port)` | 用独立 `--user-data-dir` 与 `--remote-debugging-port` 启动浏览器 |
| `get_page(acc_id)` | 返回该会话的活动页面 |
| `close_session(acc_id)` | 关闭并清理该账号浏览器进程 |
| `session_status(acc_id)` | 返回 `connected / login_ok / login_expired` |

端口与目录分配规则：

- 端口：`9222 + accountIndex`，范围 9222–9250，冲突时顺延并探测占用；
- 目录：`data/profiles/acc_{id}`，互不共享，登录态天然隔离；
- 残留进程清理：`_kill_our_browsers(profile_dir)` 改为只清理当前账号目录，避免误杀其他账号浏览器。

生命周期：

- 用户首次登录某账号后保持浏览器常驻（保活需要）；
- 后端关闭或用户手动「退出账号」时调用 `close_session`；
- 长时间无任务的会话可选配置空闲回收（默认不回收，优先保活）。

### 5.3 命令协议

协议从"全局单会话"升级为"带账号路由"：

```json
{"action": "scrape_shops", "account": 12, "args": {}}
{"action": "scrape_reviews", "account": 12, "args": {"shopName": "某某店"}}
{"action": "reply_reviews", "account": 12, "args": {"shopName": "某某店", "items": [...]}}
```

- `account` 为 `meituan_accounts.id`；缺省时兼容旧行为（使用默认会话 0），保证未升级的调用方可用；
- 新增命令：`login_start`、`login_status`、`logout`、`close_session`；
- 返回体中增加 `account` 字段，便于 Java 侧校验响应来源。

### 5.4 Java 服务层

- `BrowserAutomationService` 由"管理单个 Process"改为"管理账号会话表 + 单 Python 进程"（推荐）或"每账号一个 Python 进程"（备选）。
  - **推荐单进程多会话**：Java 只需一个 stdin/stdout 管道，命令带 `account` 路由，资源开销小、实现集中。
- 新增 `MeituanAccountRepository` / `MeituanAccountService`：
  - 账号 CRUD、登录状态维护、端口与目录分配；
  - `checkKeepAlive(accountId)` 调用 Python 探测登录态。
- `Shop` 绑定逻辑改为同时写入 `meituanAccountId`；积分扣减逻辑不变。
- 审计日志新增 `accountId` 维度，便于按账号排查。

### 5.5 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/accounts` | 当前用户的账号列表（含登录状态） |
| POST | `/api/accounts` | 新增账号（创建记录并分配 profile/端口） |
| DELETE | `/api/accounts/{id}` | 删除账号（需先解绑店铺或级联删除策略确认） |
| POST | `/api/accounts/{id}/login/start` | 打开该账号专属浏览器登录页 |
| GET | `/api/accounts/{id}/login/status` | 轮询登录状态 |
| POST | `/api/accounts/{id}/logout` | 退出并关闭该账号会话 |
| POST | `/api/accounts/{id}/check` | 手动保活检测 |
| POST | `/api/accounts/{id}/scrape-shops` | 抓取该账号下店铺 |
| POST | `/api/accounts/{id}/bind-shops` | 绑定所选店铺到当前用户 |
| GET | `/api/accounts/{id}/automation/status` | 该账号自动化运行状态 |

旧接口 `/api/browser/*` 保留一个版本作为兼容层，内部转调账号维度实现。

### 5.6 前端交互

店铺页调整为两个区块：

1. **账号区**：账号卡片列表，展示账号名、登录状态徽标、店铺数、最后保活时间；
   - 操作：`登录 / 重新登录`、`检测状态`、`退出`、`删除`；
   - 过期账号红色徽标 + 首页顶部告警条提示"账号 X 登录已失效"。
2. **店铺区**：按账号分组展示（沿用现有 `Shops.vue` 表格结构），绑定入口改为先选账号再抓店。

「添加店铺」流程改为：

```
选择/新增账号 → 打开该账号浏览器 → 人工完成登录 → 抓取店铺 → 勾选绑定（扣积分）
```

多账号各自拥有独立浏览器窗口，用户可分别完成登录，互不影响。

### 5.7 定时任务调度

`DailyCaptureScheduler` 改为三层遍历：

```
for account in 启用且在线的账号:
    ensure_session(account)
    if 登录失效: 标记 expired + 告警 + 跳过（不影响其他账号）
    for shop in account 下店铺:
        抓评价 → 自动回复 → 负反馈 → 自动申诉 → 状态回查 → 食安抓取/申诉
```

- 默认**串行**执行，同一时刻只有一个账号活动，降低同 IP 多账号并发的风控概率；
- 增加并发上限配置项（默认 1，可调至 2）；
- 账号级结果独立记录到审计日志，并写入 `AutomationConfig.lastRunDetail` 摘要。

### 5.8 登录保活与失效处理

- Cookie 持久化在各自 profile 目录，正常可保活数天至数周；
- 每日定时（默认 08:00）逐账号轻量检测：打开商家后台首页，若 URL 被重定向到登录页则判定 `expired`；
- 失效处理：
  1. 账号徽标变红，首页告警条展示；
  2. 审计日志记录 `account_login_expired`；
  3. 用户点击「重新登录」，系统打开该账号专属浏览器窗口，人工完成短信/扫码；
  4. 登录成功后状态恢复 `online`，后续定时任务自动恢复。
- 不自动填写验证码、不伪造设备指纹、不导出 Cookie 到云端。

### 5.9 安全与合规

- 美团密码不落库，系统只保存账号标识与本地浏览器 profile；
- profile 目录含登录 Cookie，需限制为本机用户可读，发布包不得包含 `data/profiles`；
- 备份/迁移时对 profile 做加密打包（可选 DPAPI），禁止明文上传；
- 删除账号时同步清理 profile 目录，避免残留登录态。

---

## 6. 兼容与迁移

| 项目 | 处理方式 |
|---|---|
| 旧 `shops.meituan_account` | 保留只读兼容，新增 `meituan_account_id` 为准 |
| 旧 `/api/browser/*` 接口 | 保留一版，内部路由到默认账号 |
| 旧单账号用户 | 升级后首次启动自动生成 1 个账号记录并回填店铺，无需人工操作 |
| 旧 Python 命令（不带 account） | 默认路由到 `default` 会话，行为与升级前一致 |
| 数据库 | `ddl-auto=update` 自动建表建列 + 启动回填，无手工 SQL |

---

## 7. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 同机同 IP 多账号登录触发美团风控 | 账号被限流/要求二次验证 | 串行执行、错峰、降低频率；账号多时分散到不同机器；优先申请连锁/服务商账号 |
| 浏览器资源占用（每账号 300–600MB） | 内存压力 | 默认只保留在线账号会话；限制最大并发；必要时回收空闲会话 |
| 短信/扫码验证无法自动化 | 无人值守中断 | 登录失效告警 + 人工重登入口；保活周期内尽量减少失效 |
| profile 被其他 Edge/Chrome 占用 | 启动失败 | 按账号目录精确清理残留进程；端口占用检测与顺延 |
| 端口/目录分配冲突 | 会话串号 | 统一由 `MeituanAccountService` 分配并持久化，启动时校验 |
| 删除账号误删店铺数据 | 数据丢失 | 删除前校验是否存在绑定店铺，默认要求先解绑或二次确认 |

---

## 8. 分阶段实施计划

| 阶段 | 内容 | 交付物 |
|---|---|---|
| P0 商务确认 | 向甲方确认能否申请美团连锁/总部账号（若能，本方案可大幅简化） | 结论记录 |
| P1 账号实体化 | `MeituanAccount` 实体、仓库、服务、启动回填；`shops.account_id` | 数据库升级可用 |
| P2 会话池 | Python 多 profile/多端口会话池、命令带 account、登录/登出/状态命令 | 多账号可同时在线 |
| P3 绑定流程 | 账号维度抓店与绑定接口；前端账号区 + 两级绑定流程 | 多账号可绑定店铺 |
| P4 调度与保活 | 定时任务按账号串行；每日保活检测；失效告警 | 可无人值守运行 |
| P5 优化 | 并发上限、空闲回收、审计细化、文档与用户手册 | 稳定版发布 |

> 发布前需按既有规则先确认版本号（例如 v1.3.0），再执行打包。

---

## 9. 验收标准

1. 同一蓝星用户可创建 ≥3 个美团账号，并**同时保持在线**，互不覆盖登录态。
2. 每个账号均可抓取并绑定其名下店铺；单店账号与服务商账号均正常。
3. 重启服务后，各账号登录态可恢复（Cookie 持久化生效），无需重复登录。
4. 每日定时任务按账号串行执行，某账号失效时其余账号任务正常完成。
5. 账号登录失效后 1 个检测周期内出现告警，人工重登后可自动恢复调度。
6. 绑定店铺扣积分、店铺查重、权限隔离、审计日志等既有行为无回归。
7. 旧数据升级后账号与店铺关联正确，无重复账号、无丢失店铺。

---

## 10. 待确认事项

1. 甲方是否可申请美团**连锁/总部账号**？若可以，优先走该路径，本方案可缩水为"账号管理增强"。
2. 甲方实际需要同时管理的账号数量上限（影响端口范围与内存规划）。
3. 是否允许浏览器窗口常驻后台（影响保活与资源策略）。
4. 账号删除策略：禁止删除有店铺的账号，还是级联解绑/删除？
5. 多账号自动化是否允许有限并发（默认串行）。

---

## 附：现有代码改造点索引

| 改造点 | 位置 |
|---|---|
| 单浏览器单例 / 固定端口 / 固定 profile | `python/browser_automation.py` L17、L26-L32、L208-L218、L240-L308 |
| 命令分发器 | `python/browser_automation.py` L3062-L3109（`main()`） |
| Java 单进程管理 | `src/main/java/com/example/meituan/browser/BrowserAutomationService.java` L31-L41 |
| 绑定店铺逻辑 | `src/main/java/com/example/meituan/browser/BrowserAutomationController.java` L106-L214 |
| 店铺账号字段 | `src/main/java/com/example/meituan/web/entity/Shop.java` L17-L24 |
| 店铺页与绑定流程 | `frontend/src/views/Shops.vue` L10-L117 |
| 每日抓取调度 | `src/main/java/com/example/meituan/web/service/DailyCaptureScheduler.java` |
| 数据库配置 | `src/main/resources/application.properties` L5-L13 |
