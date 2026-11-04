package com.example.meituan.filter;

import com.example.meituan.config.AppConfig;
import com.example.meituan.ocr.OcrClient;
import com.example.meituan.ocr.OcrResult;
import com.example.meituan.robot.RobotController;
import com.example.meituan.screenshot.ScreenCapture;
import com.example.meituan.util.DebugOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import com.example.meituan.util.TimeFormats;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 筛选器操作
 * 动态识别并点击筛选选项
 */
public class FilterAction {
    private static final Logger logger = LoggerFactory.getLogger(FilterAction.class);

    private final AppConfig config;
    private final ScreenCapture screenCapture;
    private final OcrClient ocrClient;
    private final RobotController robotController;
    private final FilterOptionRecognizer recognizer;

    public FilterAction(AppConfig config, ScreenCapture screenCapture,
                       OcrClient ocrClient, RobotController robotController) {
        this.config = config;
        this.screenCapture = screenCapture;
        this.ocrClient = ocrClient;
        this.robotController = robotController;
        this.recognizer = new FilterOptionRecognizer();
    }

    /**
     * 点击筛选选项
     */
    public boolean clickFilterOption(String targetText, String debugName, int maxRetries) {
        logger.info("[FILTER_ACTION] Attempting to click: '{}'", targetText);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            logger.info("[FILTER_ACTION] Attempt {}/{}", attempt, maxRetries);

            try {
                // 1. 截图
                BufferedImage screenshot = screenCapture.captureFullScreen();

                // 2. OCR
                OcrResult ocrResult = ocrClient.recognize(screenshot);
                if (!ocrResult.isSuccess()) {
                    logger.warn("[FILTER_ACTION] OCR failed: {}", ocrResult.getErrorMessage());
                    continue;
                }

                // 保存点击前状态
                if (config.isDebugMode()) {
                    String timestamp = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
                    String debugPath = String.format("%s/phase5_before_%s_%s.png",
                            config.getDebugDir(), debugName, timestamp);
                    DebugOverlay.saveDebugImage(screenshot, ocrResult.getTextBoxes(), debugPath);
                }

                // 3. 查找目标选项
                Optional<FilterOptionRecognizer.FilterOption> option =
                        recognizer.findFilterOption(ocrResult.getTextBoxes(), targetText);

                if (option.isEmpty()) {
                    logger.warn("[FILTER_ACTION] Target '{}' not found", targetText);
                    Thread.sleep(1000);
                    continue;
                }

                FilterOptionRecognizer.FilterOption target = option.get();
                logger.info("[FILTER_ACTION] Found target: {}", target);

                // 4. 点击
                int centerX = target.getCenterX();
                int centerY = target.getCenterY();
                logger.info("[FILTER_ACTION] Clicking at ({}, {})", centerX, centerY);

                robotController.click(centerX, centerY);

                // 5. 等待页面响应
                Thread.sleep(2000);

                // 6. 验证（重新截图）
                BufferedImage afterScreenshot = screenCapture.captureFullScreen();
                OcrResult afterOcrResult = ocrClient.recognize(afterScreenshot);

                if (!afterOcrResult.isSuccess()) {
                    logger.warn("[FILTER_ACTION] Post-click OCR failed");
                    continue;
                }

                // 保存点击后状态
                if (config.isDebugMode()) {
                    String timestamp = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
                    String debugPath = String.format("%s/phase5_after_%s_%s.png",
                            config.getDebugDir(), debugName, timestamp);
                    DebugOverlay.saveDebugImage(afterScreenshot, afterOcrResult.getTextBoxes(), debugPath);
                }

                // 验证目标文本仍然存在（简化版）
                boolean verified = afterOcrResult.getTextBoxes().stream()
                        .anyMatch(box -> box.getText().contains(targetText));

                if (verified) {
                    logger.info("[FILTER_ACTION] SUCCESS: '{}' clicked and verified", targetText);
                    return true;
                } else {
                    logger.warn("[FILTER_ACTION] Verification failed for '{}'", targetText);
                }

            } catch (InterruptedException e) {
                logger.error("[FILTER_ACTION] Interrupted", e);
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                logger.error("[FILTER_ACTION] Error on attempt {}", attempt, e);
            }
        }

        logger.error("[FILTER_ACTION] FAILED: Could not click '{}'", targetText);
        return false;
    }
}
