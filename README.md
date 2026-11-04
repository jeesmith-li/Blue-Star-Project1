# 美团商家后台差评自动化处理系统

## 项目概述

基于**视觉识别**的桌面 RPA 自动化系统，用于处理美团商家后台的差评回复。

**核心原则：程序永远不知道按钮在哪里，只能通过当前屏幕实时识别位置后点击。**

## 技术特点

- ✅ **零固定坐标** - 所有点击位置来自实时 OCR/视觉识别
- ✅ **真实桌面操作** - java.awt.Robot 模拟鼠标键盘
- ✅ **进程级超时保护** - OCR 子进程超时强制 kill
- ✅ **自适应分辨率** - 支持任意屏幕分辨率和 DPI
- ✅ **视觉验证** - 每步操作后重新截图验证状态

## 技术栈

- Java 17 LTS
- Maven
- java.awt.Robot
- OpenCV Java
- Python 3.13 + RapidOCR
- SQLite
- Jackson
- SLF4J + Logback

## 项目结构

```
meituan-review-automation/
├── src/main/java/com/example/meituan/
│   ├── Main.java                    # PHASE 1 演示程序
│   ├── OcrTimeoutTest.java          # OCR 超时测试
│   ├── automation/                  # 自动化状态机（待实现）
│   ├── robot/
│   │   ├── RobotController.java     # 鼠标键盘控制
│   │   └── ClipboardController.java # 剪贴板操作
│   ├── screenshot/
│   │   └── ScreenCapture.java       # 屏幕截图
│   ├── ocr/
│   │   ├── OcrClient.java           # OCR 客户端
│   │   ├── OcrResult.java           # OCR 结果
│   │   └── OcrTextBox.java          # OCR 文本框
│   ├── vision/
│   │   ├── VisualLocator.java       # 视觉定位器
│   │   └── TextMatcher.java         # 文本匹配器
│   └── config/
│       └── AppConfig.java           # 配置管理
├── python/
│   ├── ocr_subprocess.py            # RapidOCR 子进程
│   └── ocr_timeout_test.py          # 超时测试脚本
├── data/
│   ├── screenshots/                 # 截图存储
│   ├── debug/                       # 调试信息
│   └── temp/                        # 临时文件
└── pom.xml
```

## 当前进度：PHASE 1

✅ **已完成：**
- Maven 项目结构
- 屏幕截图（动态分辨率）
- OCR 客户端（进程级超时）
- 视觉定位器
- 文本匹配器
- Robot 控制器
- 剪贴板控制器
- 配置管理

## 安装与运行

### 1. 依赖安装

#### Java 依赖
```bash
mvn clean install
```

#### Python 依赖
```bash
pip install rapidocr-onnxruntime
```

### 2. 配置

编辑 `src/main/resources/application.properties`：

```properties
# Python 可执行文件路径（根据实际情况修改）
ocr.python.executable=python

# OCR 超时设置（秒）
ocr.timeout.seconds=10

# 调试模式
debug.mode=true
```

### 3. 测试 OCR 超时保护

**重要：先测试超时机制！**

```bash
# 编译
mvn clean compile

# 运行超时测试
mvn exec:java -Dexec.mainClass="com.example.meituan.OcrTimeoutTest"
```

**预期结果：**
- Python 脚本 sleep 60 秒
- Java 在 5-10 秒后强制杀死进程
- 打印：`[OCR] TIMEOUT` 和 `[OCR] PROCESS_KILLED`
- Java 程序不卡死

### 4. 运行 PHASE 1 演示

```bash
mvn exec:java -Dexec.mainClass="com.example.meituan.Main"
```

**演示流程：**
1. 确保美团商家后台在浏览器中打开
2. 程序截取整个屏幕
3. OCR 识别所有文本
4. 打印所有识别到的文本框
5. 查找"顾客管理"
6. 询问是否点击
7. 动态点击识别出的位置
8. 重新截图
9. 验证"顾客评价"是否出现

## 核心设计原则

### ❌ 绝对禁止

```java
// 禁止固定坐标
Robot.mouseMove(500, 300);
click(1200, 600);

// 禁止固定区域
Rectangle region = new Rectangle(0, 0, 1920, 1080);

// 禁止假设分辨率
if (screenWidth == 1920) { ... }

// 禁止点击后不验证
click(button);
updateDatabase(); // ❌ 没有验证是否真的点击成功
```

### ✅ 正确做法

```java
// 1. 截图
BufferedImage screenshot = screenCapture.captureFullScreen();

// 2. OCR 识别
OcrResult ocrResult = ocrClient.recognize(screenshot);

// 3. 视觉定位
Optional<OcrTextBox> target = visualLocator.findText(ocrResult, "顾客管理");

// 4. 动态点击
if (target.isPresent()) {
    robotController.click(target.get());
    
    // 5. 重新截图验证
    BufferedImage newScreenshot = screenCapture.captureFullScreen();
    OcrResult newOcr = ocrClient.recognize(newScreenshot);
    
    // 6. 验证成功
    Optional<OcrTextBox> verify = visualLocator.findText(newOcr, "顾客评价");
    if (verify.isPresent()) {
        // 验证成功，继续下一步
    }
}
```

## 日志说明

日志包含实时识别的坐标，但这些坐标**不能**保存成配置或在代码中复用：

```
[LOCATOR] Found '顾客管理' bbox=(123,456,120x36)
[CLICK] center=(183,474)
```

这些坐标是**当前截图**识别出来的，下次截图可能完全不同。

## 下一步计划

### PHASE 2: 页面识别
- PageRecognizer
- 完整导航流程：顾客管理 → 顾客评价 → 外卖评价列表

### PHASE 3: 筛选功能
- 未回复筛选
- 差评筛选

### PHASE 4: 评论提取
- ReviewRegionBuilder
- ReviewDetector
- 动态识别评论卡片边界

### PHASE 5: 数据库
- SQLite 集成
- 评论去重

### PHASE 6: 回复流程
- 输入框识别
- 剪贴板粘贴
- 黄色按钮识别
- 视觉验证回复成功

### PHASE 7: 完善
- 滚动处理
- 失败恢复
- 调试截图标注
- 详细日志

## 调试

调试模式会保存所有中间截图到 `data/debug/`：

```
data/debug/
├── step1_initial_20260915_010203_456.png
├── step5_after_click_20260915_010205_789.png
└── ...
```

## 许可证

MIT

## 注意事项

⚠️ **本系统为本地 MVP 原型，当前仅支持 Windows。**

⚠️ **用户需手动打开并登录美团商家后台。**

⚠️ **第一阶段不包含 Web 界面、用户系统、服务器部署。**

⚠️ **系统独占鼠标键盘，运行时请勿手动操作。**
