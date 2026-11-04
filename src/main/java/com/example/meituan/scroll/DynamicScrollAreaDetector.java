package com.example.meituan.scroll;

import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.review.ReviewCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 动态滚动区域检测器
 * 根据当前屏幕视觉结构动态确定安全的滚动区域
 */
public class DynamicScrollAreaDetector {
    private static final Logger logger = LoggerFactory.getLogger(DynamicScrollAreaDetector.class);

    /**
     * 检测评论列表滚动区域
     */
    public ScrollArea detectReviewListArea(List<ReviewCard> reviewCards, List<OcrTextBox> ocrBoxes,
                                          BufferedImage screenshot) {
        logger.info("[ScrollArea] Detecting review list scroll area");

        if (reviewCards.isEmpty()) {
            logger.warn("[ScrollArea] No review cards, using fallback");
            return createFallbackArea(screenshot);
        }

        // 1. 从评论卡片计算区域
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = 0;
        int maxY = 0;

        for (ReviewCard card : reviewCards) {
            ReviewCard.BBox bbox = card.getCardBbox();
            if (bbox != null) {
                minX = Math.min(minX, bbox.getLeft());
                minY = Math.min(minY, bbox.getTop());
                maxX = Math.max(maxX, bbox.getRight());
                maxY = Math.max(maxY, bbox.getBottom());
            }
        }

        // 2. 调整边界，避开顶部导航和底部
        int safeMinY = Math.max(minY - 100, screenshot.getHeight() / 6); // 避开顶部
        int safeMaxY = Math.min(maxY + 100, screenshot.getHeight() * 5 / 6); // 避开底部

        int centerX = (minX + maxX) / 2;

        ScrollArea area = new ScrollArea(
            centerX,
            safeMinY,
            centerX,
            safeMaxY,
            maxX - minX,
            safeMaxY - safeMinY
        );

        logger.info("[ScrollArea] Detected area: {}", area);
        return area;
    }

    /**
     * 创建备用滚动区域（当无法检测评论卡片时）
     */
    private ScrollArea createFallbackArea(BufferedImage screenshot) {
        int screenWidth = screenshot.getWidth();
        int screenHeight = screenshot.getHeight();

        int centerX = screenWidth / 2;
        int startY = screenHeight * 2 / 3;
        int endY = screenHeight / 3;

        logger.info("[ScrollArea] Using fallback area");

        return new ScrollArea(
            centerX,
            startY,
            centerX,
            endY,
            screenWidth / 2,
            screenHeight / 3
        );
    }

    /**
     * 滚动区域
     */
    public static class ScrollArea {
        private final int startX;
        private final int startY;
        private final int endX;
        private final int endY;
        private final int width;
        private final int height;

        public ScrollArea(int startX, int startY, int endX, int endY, int width, int height) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.width = width;
            this.height = height;
        }

        public int getStartX() {
            return startX;
        }

        public int getStartY() {
            return startY;
        }

        public int getEndX() {
            return endX;
        }

        public int getEndY() {
            return endY;
        }

        public int getScrollDistance() {
            return Math.abs(endY - startY);
        }

        @Override
        public String toString() {
            return String.format("ScrollArea[start=(%d,%d), end=(%d,%d), distance=%d]",
                startX, startY, endX, endY, getScrollDistance());
        }
    }
}
