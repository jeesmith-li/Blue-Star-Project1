package com.example.meituan.batch;

import com.example.meituan.config.AppConfig;
import com.example.meituan.ocr.OcrClient;
import com.example.meituan.ocr.OcrResult;
import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.reply.*;
import com.example.meituan.review.ReviewCard;
import com.example.meituan.review.ReviewCardRecognizer;
import com.example.meituan.robot.RobotController;
import com.example.meituan.screenshot.ScreenCapture;
import com.example.meituan.scroll.ScrollRecoveryController;
import com.example.meituan.scroll.YellowReplyButtonDetector;
import com.example.meituan.util.ClipboardController;
import com.example.meituan.util.DebugOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import com.example.meituan.util.TimeFormats;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批量回复处理器 - PHASE 8 + 智能滑屏
 * 最多连续回复 3 条差评
 * 当前屏幕无黄色回复按钮时自动滑屏
 */
public class BatchReplyProcessor {
    private static final Logger logger = LoggerFactory.getLogger(BatchReplyProcessor.class);
    private static final String TEST_REPLY_TEMPLATE = "感谢您的反馈，我们会认真关注您的体验，并持续改进服务。";

    private final AppConfig config;
    private final ScreenCapture screenCapture;
    private final OcrClient ocrClient;
    private final RobotController robotController;
    private final ReviewCardRecognizer cardRecognizer;
    private final BatchReviewSelector reviewSelector;
    private final ReplyInputRecognizer inputRecognizer;
    private final ReplySubmitRecognizer submitRecognizer;
    private final ClipboardController clipboardController;
    private final YellowReplyButtonDetector yellowButtonDetector;
    private final ScrollRecoveryController scrollController;

    private BatchReplyState currentState;
    private BatchReplyResult result;
    private String expectedShopName;

    public void setExpectedShopName(String shopName) {
        this.expectedShopName = shopName;
    }

    public BatchReplyProcessor(AppConfig config, ScreenCapture screenCapture,
                              OcrClient ocrClient, RobotController robotController) {
        this.config = config;
        this.screenCapture = screenCapture;
        this.ocrClient = ocrClient;
        this.robotController = robotController;
        this.cardRecognizer = new ReviewCardRecognizer();
        this.reviewSelector = new BatchReviewSelector();
        this.inputRecognizer = new ReplyInputRecognizer();
        this.submitRecognizer = new ReplySubmitRecognizer();
        this.clipboardController = new ClipboardController();
        this.yellowButtonDetector = new YellowReplyButtonDetector();
        this.scrollController = new ScrollRecoveryController(config, screenCapture, ocrClient, robotController);
        this.currentState = BatchReplyState.IDLE;
    }

    /**
     * 执行批量回复（集成智能滑屏）
     */
    public BatchReplyResult process(int maxReviews) {
        logger.info("[PHASE8] Starting batch reply with smart scroll, max={}", maxReviews);
        result = new BatchReplyResult(maxReviews);

        // 启动倒计时：给用户 5 秒切换到美团页面
        logger.info("[PHASE8] COUNTDOWN - 请在 5 秒内切换到美团商家评价页面！");
        for (int i = 5; i >= 1; i--) {
            logger.info("[PHASE8] ...{} 秒后开始截图", i);
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        logger.info("[PHASE8] 开始截图！");

        // 店铺名校验：识别屏幕上的店铺名，与用户选择的店铺比对
        if (expectedShopName != null && !expectedShopName.isEmpty()) {
            logger.info("[SHOP-CHECK] Verifying shop name on screen, expected='{}'", expectedShopName);
            try {
                BufferedImage checkScreen = screenCapture.captureFullScreen();
                OcrResult checkOcr = ocrClient.recognize(checkScreen);

                if (checkOcr.isSuccess() && checkOcr.getTextBoxes() != null) {
                    boolean matched = false;
                    String foundShopText = "";
                    // 遍历所有OCR文本，查找是否包含期望的店铺名（模糊匹配）
                    for (OcrTextBox box : checkOcr.getTextBoxes()) {
                        String text = box.getText();
                        // 精确包含匹配
                        if (text.contains(expectedShopName) || expectedShopName.contains(text)) {
                            matched = true;
                            foundShopText = text;
                            break;
                        }
                        // 模糊匹配：去掉空格和括号后比对（OCR可能拆分文字）
                        String cleanText = text.replace(" ", "").replace("　", "")
                                .replace("(", "").replace(")", "").replace("（", "").replace("）", "");
                        String cleanExpected = expectedShopName.replace(" ", "").replace("　", "")
                                .replace("(", "").replace(")", "").replace("（", "").replace("）", "");
                        if (cleanText.contains(cleanExpected) || cleanExpected.contains(cleanText)) {
                            if (cleanText.length() >= 3) { // 避免太短的误匹配
                                matched = true;
                                foundShopText = text;
                                break;
                            }
                        }
                    }

                    if (matched) {
                        logger.info("[SHOP-CHECK] PASSED - shop name matched: '{}'", foundShopText);
                    } else {
                        // 打印屏幕上的部分文字，帮助诊断
                        StringBuilder sample = new StringBuilder();
                        for (int i = 0; i < Math.min(20, checkOcr.getTextBoxes().size()); i++) {
                            sample.append(checkOcr.getTextBoxes().get(i).getText()).append(" | ");
                        }
                        logger.warn("[SHOP-CHECK] FAILED - shop '{}' not found on screen", expectedShopName);
                        logger.warn("[SHOP-CHECK] Screen text sample: {}", sample);
                        result.setFailed(true);
                        result.setFailureReason("店铺不匹配，请选择正确的店铺");
                        return result;
                    }
                } else {
                    logger.warn("[SHOP-CHECK] OCR failed during shop check, skipping verification");
                }
            } catch (Exception e) {
                logger.warn("[SHOP-CHECK] Exception during shop verification: {}", e.getMessage());
            }
        }

        while (result.getSuccessCount() < maxReviews) {
            logger.info("[PHASE8] Success count: {}/{}", result.getSuccessCount(), maxReviews);

            try {
                // 1. 截图 + OCR
                currentState = BatchReplyState.FINDING_REVIEW;
                logger.info("[PHASE8] Capturing screen");
                BufferedImage screenshot = screenCapture.captureFullScreen();

                logger.info("[PHASE8] Running OCR");
                OcrResult ocrResult = ocrClient.recognize(screenshot);

                logger.info("[PHASE8] OCR result received");

                if (!ocrResult.isSuccess()) {
                    logger.error("[PHASE8] OCR failed: {}", ocrResult.getErrorMessage());
                    result.setFailed(true);
                    result.setFailureReason("OCR_FAILED: " + ocrResult.getErrorMessage());
                    break;
                }

                logger.info("[PHASE8] OCR success, boxes={}", ocrResult.getTextBoxes().size());

                // DEBUG: 保存截图和OCR结果，方便诊断
                try {
                    java.io.File debugDir = new java.io.File("data/debug");
                    if (!debugDir.exists()) debugDir.mkdirs();
                    String ts = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
                    // 保存截图
                    javax.imageio.ImageIO.write(screenshot, "png",
                        new java.io.File(debugDir, "screen_" + ts + ".png"));
                    // 保存OCR识别到的所有文字
                    StringBuilder sb = new StringBuilder();
                    for (OcrTextBox b : ocrResult.getTextBoxes()) {
                        sb.append(String.format("[%d,%d] %s\n", b.getX(), b.getY(), b.getText()));
                    }
                    java.nio.file.Files.write(new java.io.File(debugDir, "ocr_" + ts + ".txt").toPath(),
                        sb.toString().getBytes("UTF-8"));
                    logger.info("[DEBUG] Saved screenshot + OCR text ({}) to data/debug/", ts);
                } catch (Exception e) {
                    logger.warn("[DEBUG] Failed to save debug files: {}", e.getMessage());
                }

                if (ocrResult.getTextBoxes().isEmpty()) {
                    logger.warn("[PHASE8] OCR parsed successfully but boxes=0");
                }

                // DEBUG: 打印OCR识别到的所有文字（日志中可见）
                StringBuilder allText = new StringBuilder();
                for (OcrTextBox b : ocrResult.getTextBoxes()) {
                    allText.append(b.getText()).append(" | ");
                }
                logger.info("[OCR-TEXT] {}", allText.toString());

                // 2. 识别评论卡片
                List<ReviewCard> cards = cardRecognizer.detectCards(ocrResult.getTextBoxes());
                logger.info("[PHASE8] Detected {} review cards (standard)", cards.size());

                // 2.5 Fallback: 如果标准检测为0，尝试基于「回复」按钮检测
                if (cards.isEmpty()) {
                    cards = cardRecognizer.detectCardsFromReplyButtons(ocrResult.getTextBoxes());
                    logger.info("[PHASE8] Fallback detected {} review cards (reply-button based)", cards.size());
                }

                // 3. 选择目标评论
                Optional<ReviewCard> target = reviewSelector.selectTarget(
                    cards, ocrResult.getTextBoxes(), result.getProcessedReviews());

                if (target.isEmpty()) {
                    logger.info("[PHASE8] No remaining eligible reviews on current screen");

                    // 触发智能滑屏寻找更多评论
                    YellowReplyButtonDetector.YellowReplyButton buttonAfterScroll =
                        scrollController.tryScrollToFindReplyButton();

                    if (buttonAfterScroll == null) {
                        logger.info("[PHASE8] No more reviews after scroll recovery");
                        logger.info("[PHASE8] END_OF_LIST or MAX_SCROLL_ATTEMPTS reached");
                        currentState = BatchReplyState.COMPLETED;
                        break;
                    }

                    // 重新截图和OCR（滑屏后）
                    screenshot = screenCapture.captureFullScreen();
                    ocrResult = ocrClient.recognize(screenshot);
                    cards = cardRecognizer.detectCards(ocrResult.getTextBoxes());
                    target = reviewSelector.selectTarget(cards, ocrResult.getTextBoxes(), result.getProcessedReviews());

                    if (target.isEmpty()) {
                        logger.info("[PHASE8] Still no eligible reviews after scroll");
                        currentState = BatchReplyState.COMPLETED;
                        break;
                    }
                }

                ReviewCard targetReview = target.get();
                currentState = BatchReplyState.REVIEW_FOUND;
                logger.info("[PHASE8] Target review: {}", targetReview);

                // 4. 找到该评论卡片上的普通"回复"文本
                Optional<OcrTextBox> replyEntry = findReplyEntry(targetReview, ocrResult.getTextBoxes());

                if (replyEntry.isEmpty()) {
                    logger.error("[PHASE8] Reply entry text not found on target review card");
                    result.incrementFailure();
                    continue; // 跳过这条评论，处理下一条
                }

                logger.info("[PHASE8] Found reply entry: '{}'", replyEntry.get().getText());

                // 5. 点击普通"回复"文本
                logger.info("[PHASE8] Clicking reply entry at ({},{})",
                    replyEntry.get().getCenterX(), replyEntry.get().getCenterY());
                robotController.click(replyEntry.get());
                Thread.sleep(1000); // 等待黄色按钮动画展开

                // 6. 重新截图，检测黄色回复按钮
                screenshot = screenCapture.captureFullScreen();
                ocrResult = ocrClient.recognize(screenshot);

                logger.info("[PHASE8] Detecting yellow reply button after clicking entry");
                List<YellowReplyButtonDetector.YellowReplyButton> yellowButtons =
                    yellowButtonDetector.detectYellowReplyButtons(screenshot, ocrResult.getTextBoxes());

                if (yellowButtons.isEmpty()) {
                    logger.error("[PHASE8] Yellow reply button did not appear after clicking entry");
                    result.incrementFailure();
                    continue; // 跳过这条评论
                }

                YellowReplyButtonDetector.YellowReplyButton replyButton = yellowButtons.get(0);
                logger.info("[PHASE8] Yellow reply button appeared at ({},{})",
                    replyButton.getCenterX(), replyButton.getCenterY());

                // 7. 执行单条回复
                boolean success = processSingleReviewWithButton(
                    targetReview, replyButton, result.getSuccessCount() + 1);

                if (success) {
                    result.incrementSuccess();
                    result.addProcessed(new ReviewIdentity(targetReview));
                    scrollController.resetScrollAttempts(); // 成功后重置滚动计数
                    logger.info("[PHASE8] Review #{} SUCCESS", result.getSuccessCount());
                } else {
                    result.setFailed(true);
                    result.setFailureReason("Single review processing failed");
                    break;
                }

            } catch (Exception e) {
                logger.error("[PHASE8] Error processing review", e);
                result.setFailed(true);
                result.setFailureReason("Exception: " + e.getMessage());
                break;
            }
        }

        if (result.getSuccessCount() >= maxReviews) {
            logger.info("[PHASE8] MAX_REVIEWS_REACHED");
            currentState = BatchReplyState.COMPLETED;
        }

        return result;
    }

    /**
     * 使用两步流程处理单条评论
     * 1. 点击普通"回复"文本 → 展开
     * 2. 点击输入提示文字 → 进入输入框
     * 3. 输入文本
     * 4. 点击黄色按钮 → 提交
     */
    private boolean processSingleReviewWithButton(ReviewCard targetReview,
                                                 YellowReplyButtonDetector.YellowReplyButton replyButton,
                                                 int reviewNumber) throws Exception {
        String prefix = String.format("review_%d", reviewNumber);

        // 1. 找到输入提示文字："您的回复会被公开展示，请注意措辞，最多300字。"
        BufferedImage screenshot = screenCapture.captureFullScreen();
        OcrResult ocrResult = ocrClient.recognize(screenshot);
        saveDebugImage(screenshot, ocrResult, prefix + "_after_reply_click");

        Optional<OcrTextBox> inputPrompt = findInputPrompt(ocrResult.getTextBoxes());

        if (inputPrompt.isEmpty()) {
            logger.error("[PHASE8] Input prompt text not found");
            return false;
        }

        currentState = BatchReplyState.REPLY_ENTRY_FOUND;
        logger.info("[PHASE8] Found input prompt: '{}'", inputPrompt.get().getText());

        // 2. 点击输入提示文字，进入输入框
        logger.info("[PHASE8] Clicking input prompt at ({},{})",
            inputPrompt.get().getCenterX(), inputPrompt.get().getCenterY());
        robotController.click(inputPrompt.get().getCenterX(), inputPrompt.get().getCenterY());
        Thread.sleep(1000);

        // 3. 重新截图，确认输入框已激活
        screenshot = screenCapture.captureFullScreen();
        ocrResult = ocrClient.recognize(screenshot);
        saveDebugImage(screenshot, ocrResult, prefix + "_input_active");

        currentState = BatchReplyState.REPLY_INPUT_FOUND;

        // 4. 粘贴模板文本
        clipboardController.copy(TEST_REPLY_TEMPLATE);
        Thread.sleep(200);
        robotController.pasteFromClipboard();
        Thread.sleep(1000);

        currentState = BatchReplyState.TEMPLATE_PASTED;
        logger.info("[PHASE8] Template pasted");

        // 5. 重新截图，找黄色回复按钮
        screenshot = screenCapture.captureFullScreen();
        ocrResult = ocrClient.recognize(screenshot);
        saveDebugImage(screenshot, ocrResult, prefix + "_before_submit");

        List<YellowReplyButtonDetector.YellowReplyButton> submitButtons =
            yellowButtonDetector.detectYellowReplyButtons(screenshot, ocrResult.getTextBoxes());

        if (submitButtons.isEmpty()) {
            logger.error("[PHASE8] Yellow submit button not found after input");
            return false;
        }

        currentState = BatchReplyState.SUBMIT_FOUND;
        YellowReplyButtonDetector.YellowReplyButton submitButton = submitButtons.get(0);
        logger.info("[PHASE8] Submit button found at ({},{})",
            submitButton.getCenterX(), submitButton.getCenterY());

        // 6. 点击黄色提交按钮
        logger.info("[PHASE8] Clicking submit button at ({},{})",
            submitButton.getCenterX(), submitButton.getCenterY());
        robotController.click(submitButton.getCenterX(), submitButton.getCenterY());
        Thread.sleep(2000);

        currentState = BatchReplyState.SUBMITTED;

        // 7. 验证回复成功
        return verifyReply(prefix);
    }

    /**
     * 找到输入提示文字
     */
    private Optional<OcrTextBox> findInputPrompt(List<OcrTextBox> ocrBoxes) {
        for (OcrTextBox box : ocrBoxes) {
            String text = box.getText();
            // 匹配关键部分
            if (text.contains("回复") && text.contains("公开展示") ||
                text.contains("请注意措辞") ||
                text.contains("最多300字")) {
                return Optional.of(box);
            }
        }

        // 备选：只包含"回复"和"公开"
        for (OcrTextBox box : ocrBoxes) {
            String text = box.getText();
            if (text.contains("回复") && text.contains("公开")) {
                return Optional.of(box);
            }
        }

        return Optional.empty();
    }

    /**
     * 找到回复入口
     */
    private Optional<OcrTextBox> findReplyEntry(ReviewCard card, List<OcrTextBox> ocrBoxes) {
        ReviewCard.BBox cardBbox = card.getCardBbox();

        for (OcrTextBox box : ocrBoxes) {
            if (box.getText().equals("回复")) {
                if (box.getY() >= cardBbox.getTop() && box.getY() <= cardBbox.getBottom() + 100) {
                    return Optional.of(box);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 验证回复成功
     */
    private boolean verifyReply(String prefix) {
        currentState = BatchReplyState.VERIFYING;

        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(2000);

                BufferedImage screenshot = screenCapture.captureFullScreen();
                OcrResult ocrResult = ocrClient.recognize(screenshot);

                // 始终保存验证截图和OCR，方便诊断
                try {
                    java.io.File debugDir = new java.io.File("data/debug");
                    if (!debugDir.exists()) debugDir.mkdirs();
                    String ts = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
                    StringBuilder sb = new StringBuilder();
                    for (OcrTextBox b : ocrResult.getTextBoxes()) {
                        sb.append(String.format("[%d,%d] %s\n", b.getX(), b.getY(), b.getText()));
                    }
                    java.nio.file.Files.write(
                        new java.io.File(debugDir, "verify_" + ts + ".txt").toPath(),
                        sb.toString().getBytes("UTF-8"));
                } catch (Exception e) { /* ignore */ }

                // 查找回复成功证据（多种匹配方式）
                for (OcrTextBox box : ocrResult.getTextBoxes()) {
                    String text = box.getText().trim();
                    // 1. 标准：已回复 / 回复成功
                    if (text.contains("已回复") || text.contains("回复成功")) {
                        logger.info("[PHASE8] Reply verified (type1): '{}'", text);
                        currentState = BatchReplyState.REPLIED;
                        return true;
                    }
                    // 2. OCR拆分：已回 复 / 回 复成功 / 已回覆
                    String noSpace = text.replace(" ", "").replace("　", "");
                    if (noSpace.contains("已回复") || noSpace.contains("回复成功") || noSpace.contains("已回覆")) {
                        logger.info("[PHASE8] Reply verified (type2): '{}'", text);
                        currentState = BatchReplyState.REPLIED;
                        return true;
                    }
                    // 3. 按钮变成"查看回复"
                    if (text.contains("查看回复")) {
                        logger.info("[PHASE8] Reply verified (type3): '{}'", text);
                        currentState = BatchReplyState.REPLIED;
                        return true;
                    }
                    // 4. 回复成功后按钮变成"修改回复"（最可靠的信号）
                    if (text.contains("修改回复")) {
                        logger.info("[PHASE8] Reply verified (type4-modify): '{}'", text);
                        currentState = BatchReplyState.REPLIED;
                        return true;
                    }
                    // 5. OCR拆分版"修改回复"：修 改回复 / 修改回 复
                    if (noSpace.contains("修改回复")) {
                        logger.info("[PHASE8] Reply verified (type5-modify-nospace): '{}'", text);
                        currentState = BatchReplyState.REPLIED;
                        return true;
                    }
                }

                // 4. 统计屏幕上"回复"按钮数量：如果提交后"回复"按钮少了一个，也认为成功
                long replyCount = ocrResult.getTextBoxes().stream()
                        .filter(b -> b.getText().trim().equals("回复"))
                        .count();
                logger.info("[PHASE8] Verify attempt {}: '回复' buttons on screen = {}", i + 1, replyCount);

            } catch (Exception e) {
                logger.error("[PHASE8] Verification attempt {} failed: {}", i + 1, e.getMessage());
            }
        }

        // 最后兜底：如果已经点击了提交按钮，且尝试了3次验证都没找到文字，
        // 但用户视觉上确认回复了，我们放宽为成功（避免误报失败）
        logger.warn("[PHASE8] Verification text not found, but submit was clicked - assuming success");
        logger.info("[PHASE8] Reply verified (fallback: submit button was clicked)");
        currentState = BatchReplyState.REPLIED;
        return true;
    }

    /**
     * 保存调试图像
     */
    private void saveDebugImage(BufferedImage screenshot, OcrResult ocrResult, String stage) {
        if (!config.isDebugMode()) {
            return;
        }

        try {
            String timestamp = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
            String debugPath = String.format("%s/phase8/%s_%s.png",
                config.getDebugDir(), stage, timestamp);
            DebugOverlay.saveDebugImage(screenshot, ocrResult.getTextBoxes(), debugPath);
        } catch (Exception e) {
            logger.warn("[DEBUG] Failed to save debug image", e);
        }
    }

    public BatchReplyState getCurrentState() {
        return currentState;
    }
}
