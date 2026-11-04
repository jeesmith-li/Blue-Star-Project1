package com.example.meituan.ocr;

import com.example.meituan.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.example.meituan.util.TimeFormats;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OCR 客户端 - 调用 Python RapidOCR 子进程
 * 核心特性：
 * 1. 真正的进程级超时保护
 * 2. 超时后强制 kill 子进程
 * 3. 使用临时文件进行输入输出
 * 4. 不使用 stdin/stdout 管道避免阻塞
 */
public class OcrClient {
    private static final Logger logger = LoggerFactory.getLogger(OcrClient.class);

    private final AppConfig config;
    private final ObjectMapper objectMapper;

    public OcrClient(AppConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 识别图像中的文本
     * 使用进程级超时保护
     */
    public OcrResult recognize(BufferedImage image) {
        String timestamp = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
        Path inputFile = null;
        Path outputFile = null;
        Process process = null;

        try {
            // 1. 保存输入图像到临时文件
            inputFile = Paths.get(config.getTempDir(), "ocr_input_" + timestamp + ".png");
            ImageIO.write(image, "PNG", inputFile.toFile());
            logger.debug("[OCR] Saved input image: {}", inputFile);

            // 2. 准备输出文件路径
            outputFile = Paths.get(config.getTempDir(), "ocr_output_" + timestamp + ".json");

            // 3. 构建 Python 命令
            List<String> command = buildCommand(inputFile, outputFile);
            logger.debug("[OCR] Command: {}", String.join(" ", command));

            // 4. 启动 OCR 子进程
            long startTime = System.currentTimeMillis();
            logger.info("[OCR] Process started");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();

            // 5. 等待进程完成，带超时
            int timeoutSeconds = config.getOcrTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            long elapsed = System.currentTimeMillis() - startTime;

            if (!finished) {
                // 超时 - 强制杀死进程
                logger.error("[OCR] TIMEOUT after {}s - killing process", timeoutSeconds);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS); // 等待进程真正结束

                return OcrResult.failure("OCR process timeout after " + timeoutSeconds + " seconds");
            }

            // 6. 检查退出码
            int exitCode = process.exitValue();
            logger.info("[OCR] Process finished: exitCode={}, elapsed={}s", exitCode, String.format("%.2f", elapsed / 1000.0));

            if (exitCode != 0) {
                return OcrResult.failure("OCR process failed with exit code: " + exitCode);
            }

            // 7. 读取输出 JSON
            if (!Files.exists(outputFile)) {
                return OcrResult.failure("OCR output file not found: " + outputFile);
            }

            OcrResult result = parseOcrOutput(outputFile);
            logger.info("[OCR] Detected {} text boxes", result.getTextBoxCount());

            return result;

        } catch (IOException e) {
            logger.error("[OCR] IO error", e);
            return OcrResult.failure("IO error: " + e.getMessage());
        } catch (InterruptedException e) {
            logger.error("[OCR] Interrupted", e);
            Thread.currentThread().interrupt();
            return OcrResult.failure("OCR interrupted");
        } finally {
            // 清理进程
            if (process != null && process.isAlive()) {
                logger.warn("[OCR] Force killing process in finally block");
                process.destroyForcibly();
            }

            // 清理临时文件
            cleanupTempFiles(inputFile, outputFile);
        }
    }

    /**
     * 构建 Python 命令
     */
    private List<String> buildCommand(Path inputFile, Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add(config.getPythonExecutable());
        command.add(config.resolveScript(config.getOcrScriptPath()).getAbsolutePath());
        command.add(inputFile.toAbsolutePath().toString());
        command.add(outputFile.toAbsolutePath().toString());
        return command;
    }

    /**
     * 解析 OCR 输出 JSON
     */
    private OcrResult parseOcrOutput(Path outputFile) throws IOException {
        logger.info("[OCR] Checking output file: path={}", outputFile);

        if (!Files.exists(outputFile)) {
            logger.error("[OCR] Output file does not exist");
            return OcrResult.failure("Output file not found");
        }

        long fileSize = Files.size(outputFile);
        logger.info("[OCR] Output file exists=true, size={} bytes", fileSize);

        if (fileSize == 0) {
            logger.error("[OCR] Output file is empty");
            return OcrResult.failure("Output file is empty");
        }

        logger.info("[OCR] Reading OCR output");
        String json = Files.readString(outputFile);
        logger.info("[OCR] JSON text length: {}", json.length());

        logger.info("[OCR] JSON parse START");
        logger.info("[OCR] JSON parse calling ObjectMapper");

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
            logger.info("[OCR] JSON parse returned");
        } catch (Exception e) {
            logger.error("[OCR] JSON parse failed", e);
            return OcrResult.failure("JSON parse error: " + e.getMessage());
        }

        logger.info("[OCR] JSON parse END");

        boolean success = root.path("success").asBoolean(false);
        if (!success) {
            String error = root.path("error").asText("Unknown error");
            logger.error("[OCR] OCR process reported failure: {}", error);
            return OcrResult.failure(error);
        }

        logger.info("[OCR] Parsed OCR result successfully");

        List<OcrTextBox> textBoxes = new ArrayList<>();
        JsonNode items = root.path("items");

        logger.info("[OCR] Processing items array");

        if (items.isArray()) {
            int itemCount = items.size();
            logger.info("[OCR] Items array size: {}", itemCount);

            int processed = 0;
            for (JsonNode item : items) {
                try {
                    String text = item.path("text").asText();
                    double confidence = item.path("confidence").asDouble(0.0);
                    JsonNode box = item.path("box");

                    if (box.isArray() && box.size() == 4) {
                        // box 格式: [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
                        int x1 = box.get(0).get(0).asInt();
                        int y1 = box.get(0).get(1).asInt();
                        int x2 = box.get(2).get(0).asInt();
                        int y2 = box.get(2).get(1).asInt();

                        int x = Math.min(x1, x2);
                        int y = Math.min(y1, y2);
                        int width = Math.abs(x2 - x1);
                        int height = Math.abs(y2 - y1);

                        OcrTextBox textBox = new OcrTextBox(text, confidence, x, y, width, height);
                        textBoxes.add(textBox);

                        processed++;
                        if (processed % 50 == 0) {
                            logger.debug("[OCR] Processed {} items", processed);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[OCR] Failed to parse item: {}", e.getMessage());
                }
            }

            logger.info("[OCR] Finished processing items: {}/{}", processed, itemCount);
        }

        logger.info("[OCR] OCR boxes count: {}", textBoxes.size());
        logger.info("[OCR] Returning OCR result to caller");

        return OcrResult.success(textBoxes);
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(Path... files) {
        for (Path file : files) {
            if (file != null && Files.exists(file)) {
                try {
                    Files.delete(file);
                    logger.trace("[OCR] Deleted temp file: {}", file);
                } catch (IOException e) {
                    logger.warn("[OCR] Failed to delete temp file: {}", file, e);
                }
            }
        }
    }
}
