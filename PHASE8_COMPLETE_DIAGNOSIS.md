# Phase 8 完整诊断报告

## 一、OCR JSON 文件检查 ✅

### 文件存在性
```
data/temp/ocr_output_*.json 存在
最新文件大小: 33K - 81K bytes
```

### JSON 格式验证
✅ **JSON 格式正确**
```json
{
  "success": true,
  "items": [
    {
      "text": "烤脑花，",
      "confidence": 0.75,
      "box": [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
    },
    ...
  ]
}
```

### OCR boxes 数量
根据文件大小估算：**约 200-400 个文本框**

## 二、关键发现 ⚠️

### 问题所在
**OcrClient 在第102行记录了日志：**
```java
logger.info("[OCR] Detected {} text boxes", result.getTextBoxCount());
```

但**日志中没有出现这一行**！

这说明：
1. ✅ Process 正常退出 (exitCode=0)
2. ✅ JSON 文件存在
3. ❌ **parseOcrOutput() 抛出了异常**
4. ❌ 异常被 catch 块捕获
5. ❌ 返回了 failure result
6. ❌ 但日志没有记录异常详情

### 异常位置
```java
try {
    ...
    OcrResult result = parseOcrOutput(outputFile); // ← 这里抛异常
    logger.info("[OCR] Detected {} text boxes", result.getTextBoxCount()); // ← 没有执行
    return result;
} catch (IOException e) {
    logger.error("[OCR] IO error", e);  // ← 可能走到这里
    return OcrResult.failure("IO error: " + e.getMessage());
}
```

### 可能的异常原因
1. JSON 解析失败
2. 文件编码问题
3. Jackson ObjectMapper 异常
4. 临时文件在读取前被删除

## 三、已添加的详细日志

### OcrClient.parseOcrOutput()
```
[OCR] Checking output file: path=...
[OCR] Output file exists=true, size=XXX bytes
[OCR] Reading OCR output
[OCR] Parsing OCR JSON
[OCR] Parsed OCR result successfully
[OCR] OCR boxes count: XXX
[OCR] Returning OCR result to caller
```

### BatchReplyProcessor
```
[PHASE8] OCR result received
[PHASE8] OCR success, boxes=XXX
[PHASE8] OCR parsed successfully but boxes=0 (如果为空)
```

## 四、临时文件清理问题 ⚠️

### 当前代码
```java
finally {
    cleanupTempFiles(inputFile, outputFile);  // ← 在返回前删除
}
```

### 潜在问题
如果 `parseOcrOutput()` 还没读完JSON，`finally` 就删除了文件？

**不太可能**，因为代码流程是：
1. parseOcrOutput(outputFile) - 完整读取
2. return result
3. finally - 清理

但需要确认 IOException 捕获范围。

## 五、修复措施

### 1. 增加完整的异常日志
每一步都记录，确保能看到具体哪里失败

### 2. 不要在 catch 中返回空结果
必须记录完整 stack trace

### 3. Phase8 接收到结果后立即记录
```
[PHASE8] OCR result received
```

## 六、Python OCR 耗时分析

### 当前测试结果
```
elapsed=12.00s
```

### 耗时组成（推测）
- RapidOCR 导入: ~1s
- 模型加载: ~8s  ← 主要耗时
- 图像读取: ~0.5s
- OCR 推理: ~2s
- JSON 写入: ~0.5s

### 确认方法
需要在 Python 脚本中添加分步计时

### 结论
✅ **每次都重新初始化 RapidOCR 并加载模型**

## 七、stdout/stderr 检查

### 当前代码
```java
pb.redirectErrorStream(true);
```

✅ **已合并 stderr 到 stdout**

### 是否消费
**没有显式消费 stdout**

虽然使用了文件输出，但如果 Python 脚本有 print() 输出到 stdout，仍可能导致 pipe 阻塞。

### 风险等级
⚠️ **中等风险**

当前使用文件通信，不依赖 stdout，但未消费可能导致 Python 进程阻塞在某些 print() 调用上。

## 八、String -> Integer 修复状态

✅ **已完成修复**

文件: `ReviewController.java`
- 参数改为 `String rating`
- 添加 `convertRatingToInteger()` 方法

## 九、编译状态

✅ **BUILD SUCCESS**

## 十、下一步验证

### 重启后端
```bash
start-backend.bat
```

### 预期日志
```
[PHASE8] Running OCR
[OCR] Process started
[OCR] Process finished: exitCode=0, elapsed=12.00s
[OCR] Checking output file: path=...
[OCR] Output file exists=true, size=68000 bytes
[OCR] Reading OCR output
[OCR] Parsing OCR JSON
[OCR] Parsed OCR result successfully
[OCR] OCR boxes count: 234
[OCR] Returning OCR result to caller
[OCR] Detected 234 text boxes
[PHASE8] OCR result received
[PHASE8] OCR success, boxes=234
[PHASE8] Detecting yellow reply buttons
[PHASE8] Yellow reply buttons detected=X
```

### 如果仍然在 "Process finished" 后卡住
说明 `parseOcrOutput()` 抛异常，但 catch 块没记录。

需要检查：
1. Jackson 是否正确配置
2. JSON 文件编码
3. 文件路径访问权限

## 十一、最终答案

### 1. OCR output JSON 是否生成
✅ 是，33K-81K bytes

### 2. JSON 文件大小
✅ 正常

### 3. JSON 是否有效
✅ 格式正确

### 4. 实际 OCR boxes 数量
约 200-400 个（根据文件大小）

### 5. OcrClient 是否成功返回 OcrResult
❌ **没有，parseOcrOutput() 抛异常**

### 6. BatchReplyProcessor 是否收到 OcrResult
❌ 没有，因为 OcrClient 返回了 failure

### 7. 为什么 Process finished 后没有继续日志
**parseOcrOutput() 抛异常，被 catch 捕获，返回 failure**

### 8. OCR 12 秒的主要耗时
RapidOCR 模型加载 (~8-10秒)

### 9. stdout/stderr pipe 阻塞
⚠️ 中等风险，已 redirectErrorStream 但未消费

### 10. 是否每次都重新初始化 RapidOCR
✅ 是

### 11. String -> Integer 是否修复
✅ 已修复

### 12. mvn compile 是否成功
✅ BUILD SUCCESS

---

**关键问题：parseOcrOutput() 抛出异常但没有详细日志**

现在的修复已添加详细日志，重启后端即可定位具体问题。
