package com.example.meituan.reply;

import com.example.meituan.config.AppConfig;
import com.example.meituan.ocr.OcrClient;
import com.example.meituan.ocr.OcrResult;
import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.review.ReviewCard;
import com.example.meituan.review.ReviewCardRecognizer;
import com.example.meituan.review.ReplyState;
import com.example.meituan.robot.RobotController;
import com.example.meituan.screenshot.ScreenCapture;
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
 * 回复提交器 - PHASE 7
 * 提交第一条差评回复并验证
 */
public class ReplySubmitter {
    private static final Logger logger = LoggerFactory.getLogger(ReplySubmitter.class);

    private final AppConfig config;
    private final ScreenCapture screenCapture;
    private final OcrClient ocrClient;
    private final RobotController robotController;
    private final ReviewCardRecognizer cardRecognizer;
    private final ReplyInputRecognizer inputRecognizer;
    private final ReplySubmitRecognizer submitRecognizer;
    private final ClipboardController clipboardController;

    private ReplySubmitState currentState;
    private static final String TEST_REPLY_TEMPLATE = "感谢您的反馈，我们会认真关注您的体验，并持续改进服务。";

    private ReviewCard targetReview;
    private ReplyInputTarget inputTarget;

    public ReplySubmitter(AppConfig config, ScreenCapture screenCapture,
                         OcrClient ocrClient, RobotController robotController) {
        this.config = config;
        this.screenCapture = screenCapture;
        this.ocrClient = ocrClient;
        this.robotController = robotController;
        this.cardRecognizer = new ReviewCardRecognizer();
        this.inputRecognizer = new ReplyInputRecognizer();
        this.submitRecognizer = new ReplySubmitRecognizer();
        this.clipboardController = new ClipboardController();
        this.currentState = ReplySubmitState.IDLE;
    }

    /**
     * 执行回复提交
     */
    public boolean submit() {
        logger.info("[PHASE7] Starting reply submission");

        try {
            // 1. 准备阶段（复用 PHASE 6 逻辑）
            if (!prepareReply()) {
                return false;
            }

            // 2. 找到提交按钮
            BufferedImage screenshot = screenCapture.captureFullScreen();
            OcrResult ocrResult = ocrClient.recognize(screenshot);

            if (!ocrResult.isSuccess()) {
                logger.error("[PHASE7] OCR failed");
                currentState = ReplySubmitState.FAILED;
                return false;
            }

            saveDebugImage(screenshot, ocrResult, "submit_before");

            Optional<ReplySubmitRecognizer.SubmitButtonCandidate> submitButton =
                submitRecognizer.findSubmitButton(screenshot, ocrResult.getTextBoxes(), inputTarget);

            if (submitButton.isEmpty()) {
                logger.error("[PHASE7] Submit button not found");
                currentState = ReplySubmitState.FAILED;
                return false;
            }

            ReplySubmitRecognizer.SubmitButtonCandidate button = submitButton.get();
            currentState = ReplySubmitState.SUBMIT_BUTTON_FOUND;
            logger.info("[PHASE7] Submit button found: {}", button);

            // 3. 点击提交按钮
            logger.info("[PHASE7] Clicking submit button at ({},{})",
                button.getCenterX(), button.getCenterY());
            robotController.click(button.getCenterX(), button.getCenterY());
            Thread.sleep(2000);

            currentState = ReplySubmitState.SUBMIT_CLICKED;

            // 4. 验证回复成功
            ReplyVerificationResult verifyResult = verifyReply();

            if (verifyResult.isVerified()) {
                currentState = ReplySubmitState.REPLIED;
                logger.info("[PHASE7] Reply verified: {}", verifyResult);
                return true;
            } else {
                logger.error("[PHASE7] Reply verification failed: {}", verifyResult);
                currentState = ReplySubmitState.FAILED;
                return false;
            }

        } catch (InterruptedException e) {
            logger.error("[PHASE7] Interrupted", e);
            Thread.currentThread().interrupt();
            currentState = ReplySubmitState.FAILED;
            return false;
        } catch (Exception e) {
            logger.error("[PHASE7] Error", e);
            currentState = ReplySubmitState.FAILED;
            return false;
        }
    }

    /**
     * 准备回复（复用 PHASE 6）
     */
    private boolean prepareReply() throws Exception {
        BufferedImage screenshot = screenCapture.captureFullScreen();
        OcrResult ocrResult = ocrClient.recognize(screenshot);

        if (!ocrResult.isSuccess()) {
            return false;
        }

        // 获取第一条评论
        List<ReviewCard> cards = cardRecognizer.detectCards(ocrResult.getTextBoxes());
        if (cards.isEmpty()) {
            logger.error("[PHASE7] No review card found");
            return false;
        }

        targetReview = cards.get(0);
        currentState = ReplySubmitState.TARGET_REVIEW;
        logger.info("[PHASE7] Target review: #{}", targetReview.getReviewIndex());

        // 找到回复按钮并点击
        Optional<OcrTextBox> replyButton = findReplyButtonInCard(targetReview, ocrResult.getTextBoxes());
        if (replyButton.isEmpty()) {
            logger.error("[PHASE7] Reply button not found");
            return false;
        }

        OcrTextBox replyBox = replyButton.get();
        robotController.click(replyBox.getCenterX(), replyBox.getCenterY());
        Thread.sleep(2000);

        // 识别输入框
        screenshot = screenCapture.captureFullScreen();
        ocrResult = ocrClient.recognize(screenshot);

        Optional<ReplyInputTarget> input = inputRecognizer.findReplyInput(screenshot, ocrResult.getTextBoxes());
        if (input.isEmpty()) {
            logger.error("[PHASE7] Input field not found");
            return false;
        }

        inputTarget = input.get();
        currentState = ReplySubmitState.REPLY_INPUT_READY;

        // 点击输入框并粘贴
        robotController.click(inputTarget.getCenterX(), inputTarget.getCenterY());
        Thread.sleep(500);

        clipboardController.copy(TEST_REPLY_TEMPLATE);
        Thread.sleep(200);
        robotController.pasteFromClipboard();
        Thread.sleep(1000);

        currentState = ReplySubmitState.TEMPLATE_VERIFIED;
        logger.info("[PHASE7] Template pasted successfully");

        return true;
    }

    /**
     * 验证回复成功
     */
    private ReplyVerificationResult verifyReply() {
        ReplyVerificationResult result = new ReplyVerificationResult();
        currentState = ReplySubmitState.VERIFYING;

        int maxAttempts = 3;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(2000);

                BufferedImage screenshot = screenCapture.captureFullScreen();
                OcrResult ocrResult = ocrClient.recognize(screenshot);

                if (!ocrResult.isSuccess()) {
                    continue;
                }

                saveDebugImage(screenshot, ocrResult, "verify_attempt_" + (i + 1));

                // 查找回复状态证据
                for (OcrTextBox box : ocrResult.getTextBoxes()) {
                    String text = box.getText();

                    if (text.contains("已回复") || text.contains("回复成功")) {
                        result.setVerified(true);
                        result.setReplyState(ReplyState.REPLIED);
                        result.addEvidence(text);
                        result.setConfidence(box.getConfidence());
                        logger.info("[PHASE7] Found reply evidence: {}", text);
                        return result;
                    }
                }

            } catch (Exception e) {
                logger.error("[PHASE7] Verification attempt {} failed", i + 1, e);
            }
        }

        result.setVerified(false);
        result.setReplyState(ReplyState.UNKNOWN);
        result.setConfidence(0.0);
        return result;
    }

    /**
     * 在评论卡片中查找回复按钮
     */
    private Optional<OcrTextBox> findReplyButtonInCard(ReviewCard card, List<OcrTextBox> allBoxes) {
        ReviewCard.BBox cardBbox = card.getCardBbox();

        for (OcrTextBox box : allBoxes) {
            if (box.getText().equals("回复") || box.getText().contains("回复")) {
                if (box.getY() >= cardBbox.getTop() && box.getY() <= cardBbox.getBottom() + 100) {
                    return Optional.of(box);
                }
            }
        }

        return Optional.empty();
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
            String debugPath = String.format("%s/phase7_%s_%s.png",
                config.getDebugDir(), stage, timestamp);
            DebugOverlay.saveDebugImage(screenshot, ocrResult.getTextBoxes(), debugPath);
        } catch (Exception e) {
            logger.warn("[DEBUG] Failed to save debug image", e);
        }
    }

    public ReplySubmitState getCurrentState() {
        return currentState;
    }
}
