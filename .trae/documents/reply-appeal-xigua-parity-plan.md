# 评价回复/申诉对标西瓜改造计划（放弃 OCR 辅助回复）

## Context

逆向确认西瓜系统模式：抓取评价 → 按好/中/差评三档固定文案**全自动回复** → reply_log 记录"谁/哪条/回复内容/时间"；它不识别美团已有回复，靠本地客户端**直接调美团接口**执行，完全不碰 UI、不用 OCR。

蓝星现状是三套体系并存：
1. **老 Java OCR+Robot 体系**（AutomationService → BatchReplyProcessor）：截屏+OCR+模拟点击，硬编码模板（最多3条），OCR 店铺校验/验证，回复记录是造的假数据（platformReviewId="auto_时间戳"、replyContent="（自动化回复）"），还需用户 5 秒倒计时切窗口。
2. **Python CDP 体系**（browser_automation.py）：抓评价/申诉/负反馈已走接口监听+DOM；评价列表页是 Flutter canvas（DOM 无文本），翻页用 OCR。
3. **纯数据库标记**（ReviewService.replyReview）：手动"设为已回复"不操作美团。

**用户已确认的三个决策**：
- 技术路线：**接口直调优先**（先抓包实测签名可复用性，失败回退并汇报）
- 触发模式：**全自动**（抓取后按星级模板自动回复，每店开关默认关）
- 老体系：**替换入口、弃用老代码**（暂不删除）

## Phase 0 抓包实测（关键路径，先行）

1. **零代码抓包**：CDP 接管的是用户本地 Edge，直接 F12 → Network，评价页手动回复一条评价，过滤 `reply`，记录：URL（query 中 ts/token/sign 类参数）、method、body 格式（form/JSON）、响应成功码字段。
2. **临时监听命令补充**：`browser_automation.py` 加 `capture_reply` 命令（对标 L794-808 comment/list 监听模式）：挂 `page.on("request")`+`page.on("response")`，120 秒等待窗口内人工回复一条，回传请求 URL/headers/postData + 响应体。保留为调试命令。
3. **重放验证**（`page.evaluate` 内 fetch，同源自带 cookie）：
   - 测试 A：原样重放 → 响应成功码
   - 测试 B：仅改 body 回复文本后重放 → **判定签名是否覆盖 body（关键分叉）**
   - 测试 C（可选）：comment/list 的 list_url 改 page 参数重放 → 决定翻页 OCR 是否可替换
4. **DOM 摸底**：手动点"回复"后检查是否出现真实 input/textarea（Flutter 页输入框常为 DOM）。

**判定**：A+B 成功 → 接口直调路线；B 失败（签名绑 body）→ 回退"OCR 定位回复按钮坐标 + DOM/键盘填字 + 监听 comment/list 确认状态"（Java 编排层接口不变，仅 Python 命令内部切换）。

### Phase 0 实测结论（2026-10-17，已完成）

- **捕获接口**：`POST https://waimaieapp.meituan.com/gw/customer/comment/reply?...&mtgsig=<签名>`，form body：`acctId / wmPoiId / token / appType / toCommentId / comment(URL编码) / userCommentCtime`；成功响应 `{"success":true,"code":0,"data":{"replyId":...,"auditStatus":1,...}}`
- **A/B/C 重放测试**（`page.evaluate` 同源 fetch，带原生 cookie）：
  - A 原样重放 → HTTP 200 `code:201 "该条评价已回复，请刷新"`（签名校验通过，到达业务层）
  - B 仅改评论文本 → HTTP 403（openresty 网关拦截）
  - C 换 toCommentId → HTTP 403
- **结论：mtgsig 签名深度绑定请求体，接口直调路线判死，回退 RPA 路线**（与西瓜本地 exe 同款架构：OCR 定位按钮文字 + CDP 键鼠注入）
- **页面事实**：评价列表为 Flutter Web canvas 渲染，DOM 语义树无文本可点；编辑器占位文字画在 canvas 上，DOM 检测不到 → 编辑器检测也必须 OCR
- **OCR 能否放弃**：不能完全放弃（按钮/编辑器文字定位必须 OCR），但用量大幅降低，且 CDP 注入不抢鼠标、无需窗口置顶、后台执行
- **成败判定升级**：监听页面真实 `comment/reply` POST 响应（code=0 成功），比老体系 OCR 找"已回复"文字 100% 准确
- **端到端验证**：RPA 流程真机跑通（点回复→编辑器→键入→提交→code=0→页面出现「修改回复」，replyId=4720904943）

## Phase 1 Python 端：cmd_reply_reviews（已按 RPA 路线实现，2026-10-17）

`python/browser_automation.py` 已实现（注册进命令分发）：
- 输入：`{shopName, items: [{platformReviewId, replyText}]}`
- 流程（RPA 实装）：`_ensure_logged_in_page` → `_switch_to_shop` → 顾客管理→顾客评价页 → 监听 comment/list（当前页有序行+最新状态缓存，同一 dict 引用）与 comment/reply POST（解析 toCommentId+code=0 判成败）→ reload 等首轮列表 → 逐页（≤5页）：当前页未回复评价按序 ↔ 屏上第 k 个可见「回复」按钮（OCR 定位，min_x_frac=0.55 排除正文）→ `_rpa_reply_one`（点回复→OCR 等编辑器特征文字「公开展示/300字」→点锚点下方输入→Ctrl+A 清空→keyboard.type→黄底像素校验选提交按钮→10 秒内等 reply 响应判成败，失败点「取消」复位）→ 防频控间隔 1.5~3 秒随机
- 防重复：以 comment/list 最新 replyStatus 为准，已回复自动跳过；成功即更新内存状态
- 输出：`{type:"reply_done", results:[{platformReviewId, success, message}], message:"回复完成: 成功x/y，N条未找到或跳过"}`，单条失败不中断整批
- 注意：**抓包/诊断期间禁止跑其他 CDP 连接脚本**（同 target 单调试会话，并发客户端会踢掉自动化进程的页面监听——round-5 零捕获根因）

## Phase 2 Java 端

| 文件 | 改动 |
|---|---|
| `web/entity/ReplyTemplate.java` | 加 `starLevel`（"good"/"middle"/"bad"，null=通用），ddl-auto 自动建列 |
| `web/entity/Shop.java` | 照 appealAutoEnabled 模式加 `Boolean autoReplyEnabled = false` |
| `browser/BrowserAutomationService.java` | 加 `replyReviews(shopName, items)` → sendCommand("reply_reviews")，对标 scrapeReviews 写法 |
| `web/service/ReplyExecutionService.java`（**新建**） | 核心编排：查店铺未回复评价 → 按 rating 选模板（≤2→bad、==3→middle、≥4→good，该档无模板回退通用档，仍无则跳过+审计）→ 调 replyReviews → 逐条按真实结果更新 Review（真实 platformReviewId，杜绝造假）→ 每条/每批写 ClientLogService 审计（对标西瓜 reply_log 字段：用户/评价/回复内容/时间）。AtomicBoolean 防重叠；单批上限 20 条；连续 3 失败即停批次+告警 |
| `web/service/DailyCaptureScheduler.java` | 逐店循环内 saveScrapedReviews 之后插入：`if (shop.getAutoReplyEnabled()) replyExecutionService.autoReplyForShop(shop)`，单店 try-catch 隔离，摘要加"回复：成功x 失败y" |
| `web/controller/ReviewController.java` | `/reply` body 加可选 `sendToMeituan`：true 时先调 replyReviews 单条，成功才落库 |
| `web/controller/AutomationController.java` | `/start` 改调 `replyExecutionService.runForShopNow(shopId)`（即时抓取+回复一轮）；**L85-102 积分检查+首扣逻辑原样保留**；`/stop` 改为中断 ReplyExecutionService 当前批次；status/logs 接口保留（改读新服务）避免前端 404 |
| `ShopController` | 补 autoReplyEnabled 设置接口（照 appeal 设置接口模式） |

## Phase 3 前端

- `views/Templates.vue`：表单+列表加档位（好评/中评/差评/通用）
- `views/Shops.vue`：店铺设置处加 autoReplyEnabled 开关（复用申诉设置弹窗模式）
- `views/Reviews.vue`：回复弹窗加"同时发送到美团"复选框（透传 sendToMeituan）
- `views/Automation.vue`：结果区展示本批回复成功/失败明细；移除 5 秒倒计时相关提示（CDP 后台操控无需切窗口）

## 实施顺序与验证

1. **Phase 0 实测** → 验证：重放响应成功码 + 美团后台可见回复生效；结论书面记录（成功码字段、签名覆盖范围）
2. **Phase 1 Python** → 验证：手动发 JSON 到 Python stdin，对 1 家测试店 1 条评价回复成功，results 逐条正确
3. **Phase 2 Java** → 验证：单店手动触发，检查 review 表状态/审计日志字段齐全；/reply 两种模式分别测
4. **Phase 3 前端** → 验证：三档模板配置 → 开店开关 → 自动化页触发 → Reviews 页确认回复内容状态
5. **联调**：开单店 autoReplyEnabled 跑 `POST /api/daily-capture/run` 全流水线；autoReplyEnabled 默认关，单店灰度后再放开

## 风险与回退

| 风险 | 应对 |
|---|---|
| 签名绑定 body/一次性 token | 回退坐标点击+填字（Python 命令内部切换，Java 接口不变） |
| 美团风控/频控 | 每条 2~5 秒随机间隔、单批上限 20 条、3 连败即停+审计告警 |
| 接口成功但实际未生效 | 以 comment/list 的 replyStatus 为最终确认，下次抓取自动修正 |
| 回复已回复/已删除评价 | 回复前用最新列表过滤，跳过非 unreplied |
| 定时与手动并发 | AtomicBoolean 重叠跳过+审计 |
| 积分规则破坏 | AutomationController 积分逻辑原样保留搬迁 |

## 关键文件

- `python/browser_automation.py`（cmd_reply_reviews、capture_reply）
- `src/main/java/com/example/meituan/browser/BrowserAutomationService.java`
- `src/main/java/com/example/meituan/web/service/ReplyExecutionService.java`（新建）
- `src/main/java/com/example/meituan/web/service/DailyCaptureScheduler.java`
- `src/main/java/com/example/meituan/web/controller/AutomationController.java`
- `src/main/java/com/example/meituan/web/entity/ReplyTemplate.java`、`Shop.java`
