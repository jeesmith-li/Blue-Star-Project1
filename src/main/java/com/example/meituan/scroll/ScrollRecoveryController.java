package com.example.meituan.scroll;

import com.example.meituan.config.AppConfig;
import com.example.meituan.ocr.OcrClient;
import com.example.meituan.ocr.OcrResult;
import com.example.meituan.review.ReviewCard;
import com.example.meituan.review.ReviewCardRecognizer;
import com.example.meituan.robot.RobotController;
import com.example.meituan.screenshot.ScreenCapture;
import com.example.meituan.util.DebugOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import com.example.meituan.util.TimeFormats;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能滑屏恢复控制器
 * 只有在当前屏幕无法检测到黄色回复按钮时才触发滑屏
 */
public class ScrollRecoveryController {
    private static final Logger logger = LoggerFactory.getLogger(ScrollRecoveryController.class);
    private static final int MAX_SCROLL_ATTEMPTS = 3;

    private final AppConfig config;
    private final ScreenCapture screenCapture;
    private final OcrClient ocrClient;
    private final RobotController robotController;
    private final ReviewCardRecognizer cardRecognizer;
    private final YellowReplyButtonDetector yellowButtonDetector;
    private final DynamicScrollAreaDetector scrollAreaDetector;

    private int scrollAttempts = 0;
    private ScreenFingerprint lastFingerprint = null;

    public ScrollRecoveryController(AppConfig config, ScreenCapture screenCapture,
                                   OcrClient ocrClient, RobotController robotController) {
        this.config = config;
        this.screenCapture = screenCapture;
        this.ocrClient = ocrClient;
        this.robotController = robotController;
        this.cardRecognizer = new ReviewCardRecognizer();
        this.yellowButtonDetector = new YellowReplyButtonDetector();
        this.scrollAreaDetector = new DynamicScrollAreaDetector();
    }

    /**
     * 尝试通过滑屏找到黄色回复按钮
     *
     * @return 找到的黄色回复按钮，如果没有则返回 null
     */
    public YellowReplyButtonDetector.YellowReplyButton tryScrollToFindReplyButton() {
        logger.info("[ScrollRecovery] Starting scroll recovery, attempts={}/{}", scrollAttempts, MAX_SCROLL_ATTEMPTS);

        if (scrollAttempts >= MAX_SCROLL_ATTEMPTS) {
            logger.warn("[ScrollRecovery] Max scroll attempts reached");
            return null;
        }

        try {
            // 1. 保存当前屏幕指纹
            BufferedImage beforeScroll = screenCapture.captureFullScreen();
            OcrResult beforeOcr = ocrClient.recognize(beforeScroll);

            if (!beforeOcr.isSuccess()) {
                logger.error("[ScrollRecovery] OCR failed before scroll");
                return null;
            }

            ScreenFingerprint beforeFingerprint = new ScreenFingerprint(beforeOcr.getTextBoxes());
            saveDebugImage(beforeScroll, beforeOcr, "before_scroll_" + scrollAttempts);

            // 2. 检测滚动区域
            List<ReviewCard> cards = cardRecognizer.detectCards(beforeOcr.getTextBoxes());
            DynamicScrollAreaDetector.ScrollArea scrollArea =
                scrollAreaDetector.detectReviewListArea(cards, beforeOcr.getTextBoxes(), beforeScroll);

            logger.info("[ScrollRecovery] attempt={}/{}", scrollAttempts + 1, MAX_SCROLL_ATTEMPTS);
            logger.info("[ScrollRecovery] reviewArea=({},{},{}x{})",
                scrollArea.getStartX(), scrollArea.getStartY(),
                scrollArea.getEndX() - scrollArea.getStartX(),
                scrollArea.getEndY() - scrollArea.getStartY());
            logger.info("[ScrollRecovery] scrollStart=({},{})", scrollArea.getStartX(), scrollArea.getStartY());
            logger.info("[ScrollRecovery] scrollEnd=({},{})", scrollArea.getEndX(), scrollArea.getEndY());

            // 3. 执行滑动
            performScroll(scrollArea);
            scrollAttempts++;

            // 4. 等待 UI 响应
            Thread.sleep(1500);

            // 5. 重新截图和识别
            BufferedImage afterScroll = screenCapture.captureFullScreen();
            OcrResult afterOcr = ocrClient.recognize(afterScroll);

            if (!afterOcr.isSuccess()) {
                logger.error("[ScrollRecovery] OCR failed after scroll");
                return null;
            }

            ScreenFingerprint afterFingerprint = new ScreenFingerprint(afterOcr.getTextBoxes());
            saveDebugImage(afterScroll, afterOcr, "after_scroll_" + scrollAttempts);

            // 6. 检查屏幕是否发生变化
            double similarity = beforeFingerprint.similarity(afterFingerprint);
            boolean screenChanged = similarity < 0.8; // 相似度小于80%认为发生了变化

            logger.info("[ScrollRecovery] screenChanged={} (similarity={:.2f})", screenChanged, similarity);

            if (!screenChanged) {
                logger.warn("[ScrollRecovery] Screen did not change significantly after scroll");

                // 连续两次无变化，停止
                if (lastFingerprint != null && lastFingerprint.similarity(afterFingerprint) > 0.9) {
                    logger.warn("[ScrollRecovery] Consecutive unchanged scrolls detected, stopping");
                    scrollAttempts = MAX_SCROLL_ATTEMPTS; // 强制停止
                    return null;
                }
            }

            lastFingerprint = afterFingerprint;

            // 7. 检测黄色回复按钮
            List<YellowReplyButtonDetector.YellowReplyButton> buttons =
                yellowButtonDetector.detectYellowReplyButtons(afterScroll, afterOcr.getTextBoxes());

            boolean replyButtonFound = !buttons.isEmpty();
            logger.info("[ScrollRecovery] replyButtonFound={}", replyButtonFound);

            if (replyButtonFound) {
                logger.info("[ScrollRecovery] Found reply button after scroll");
                return buttons.get(0);
            } else {
                logger.info("[ScrollRecovery] No reply button found after scroll");
                return null;
            }

        } catch (Exception e) {
            logger.error("[ScrollRecovery] Exception during scroll recovery", e);
            return null;
        }
    }

    /**
     * 执行滚动操作 - 使用鼠标滚轮，不是拖动
     */
    private void performScroll(DynamicScrollAreaDetector.ScrollArea scrollArea) throws InterruptedException {
        int centerX = (scrollArea.getStartX() + scrollArea.getEndX()) / 2;
        int centerY = (scrollArea.getStartY() + scrollArea.getEndY()) / 2;

        logger.info("[ScrollRecovery] Moving mouse to review area center ({},{})", centerX, centerY);

        // 移动鼠标到评论列表区域中心
        robotController.click(centerX, centerY);
        Thread.sleep(200);

        // 向下滚动 3 次，每次滚动 3 个单位
        logger.info("[ScrollRecovery] Scrolling down with mouse wheel");
        for (int i = 0; i < 3; i++) {
            robotController.mouseWheel(3);
            Thread.sleep(300);
        }

        logger.info("[ScrollRecovery] Scroll completed");
    }

    /**
     * 重置滚动计数
     */
    public void resetScrollAttempts() {
        scrollAttempts = 0;
        lastFingerprint = null;
        logger.info("[ScrollRecovery] Reset scroll attempts");
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
            String debugPath = String.format("%s/scroll_%s_%s.png",
                config.getDebugDir(), stage, timestamp);
            DebugOverlay.saveDebugImage(screenshot, ocrResult.getTextBoxes(), debugPath);
        } catch (Exception e) {
            logger.warn("[ScrollRecovery] Failed to save debug image", e);
        }
    }

    public int getScrollAttempts() {
        return scrollAttempts;
    }
}
