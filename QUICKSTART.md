# 快速启动指南

## 环境准备

### 1. Java 环境
确保已安装 Java 21：
```bash
java -version
# 应显示 java version "21.x.x"
```

### 2. Maven
```bash
mvn -version
# 应显示 Apache Maven 3.x
```

### 3. Python 环境
```bash
python --version
# 应显示 Python 3.x

# 安装 RapidOCR
pip install rapidocr-onnxruntime
```

## 测试步骤

### 步骤 1：编译项目

```bash
mvn clean compile
```

### 步骤 2：测试 OCR 超时保护（必须先测试！）

这是最重要的测试，验证 OCR 进程超时后能被正确杀死。

```bash
mvn exec:java -Dexec.mainClass="com.example.meituan.OcrTimeoutTest"
```

**预期输出：**
```
[OCR] Process started
[OCR] TIMEOUT after 10s - killing process
[OCR] Process finished: exitCode=...
✓ 测试通过！
进程在超时时间内被正确杀死，Java 没有卡死。
```

**如果测试失败：**
- Java 卡死超过 15 秒 → 超时保护未生效
- 检查 ProcessBuilder 和 destroyForcibly() 实现

### 步骤 3：运行主程序

**重要准备：**
1. 打开浏览器
2. 访问美团商家后台
3. 确保已登录
4. 确保浏览器窗口可见（不要最小化）
5. 确保"顾客管理"按钮在屏幕上可见

```bash
mvn exec:java -Dexec.mainClass="com.example.meituan.Main"
```

**程序会：**
1. 截取整个屏幕
2. OCR 识别所有文本
3. 打印所有识别到的文本框
4. 查找"顾客管理"
5. 如果找到，询问是否点击
6. 输入 `y` 后执行点击
7. 重新截图验证是否出现"顾客评价"

### 步骤 4：检查调试输出

```bash
# 查看调试截图
ls -lh data/debug/

# 查看日志
cat logs/meituan-automation.log
```

## 配置说明

编辑 `src/main/resources/application.properties`：

```properties
# 如果 python 命令不可用，指定完整路径
ocr.python.executable=C:/Users/xxx/AppData/Local/Programs/Python/Python313/python.exe

# OCR 超时（秒）- 默认 10 秒
ocr.timeout.seconds=10

# 调试模式 - 保存所有中间截图
debug.mode=true
```

## 常见问题

### Q1: OCR 超时测试失败
**A:** 检查 Python 路径配置，确保 Python 可执行。

### Q2: 找不到"顾客管理"
**A:** 
- 确保美团页面在当前屏幕上
- 确保窗口没有被其他窗口遮挡
- 查看 `data/debug/` 中的截图，确认截图内容正确
- 查看日志中 OCR 识别到的所有文字

### Q3: RapidOCR 未安装
**A:** 
```bash
pip install rapidocr-onnxruntime
```

### Q4: Maven 编译失败
**A:** 
- 检查 Java 版本是否为 21
- 删除 `~/.m2/repository` 中的相关依赖并重新下载
- 运行 `mvn clean install -U`

### Q5: 点击后页面没有变化
**A:**
- 检查鼠标是否点击到正确位置
- 增加等待时间（修改代码中的 Thread.sleep）
- 查看日志中的点击坐标是否正确

## 日志说明

程序运行时会输出详细日志：

```
[SCREEN] Capturing full screen: 2560x1440
[OCR] Process started
[OCR] Process finished: exitCode=0, elapsed=0.83s
[OCR] Detected 43 text boxes
[LOCATOR] Searching for '顾客管理' in 43 text boxes
[LOCATOR] Found '顾客管理' -> '顾客管理' confidence=0.98 bbox=(123,456,120x36)
[CLICK] Target='顾客管理' at center=(183,474)
[VERIFY] SUCCESS
```

**重要：** 日志中的坐标 `(183,474)` 是实时识别出来的，每次运行可能不同。这些坐标不能保存成配置。

## 下一步

PHASE 1 验证成功后，可以继续开发：
- PHASE 2: PageRecognizer - 完整导航流程
- PHASE 3: 筛选功能 - 未回复 + 差评
- PHASE 4: 评论提取 - ReviewDetector
- PHASE 5: SQLite 数据库
- PHASE 6: 自动回复流程
- PHASE 7: 完善 - 滚动、重试、错误恢复

查看 README.md 了解完整开发路线图。
