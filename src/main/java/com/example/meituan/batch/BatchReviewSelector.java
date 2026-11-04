package com.example.meituan.batch;

import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.review.ReviewCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 批量评论选择器
 * 从当前屏幕选择下一个需要回复的目标评论
 */
public class BatchReviewSelector {
    private static final Logger logger = LoggerFactory.getLogger(BatchReviewSelector.class);

    /**
     * 选择目标评论
     */
    public Optional<ReviewCard> selectTarget(List<ReviewCard> cards,
                                            List<OcrTextBox> ocrBoxes,
                                            List<ReviewIdentity> processedReviews) {
        logger.info("[SELECTOR] Selecting target from {} cards", cards.size());

        for (ReviewCard card : cards) {
            // 检查是否已处理
            if (isProcessed(card, processedReviews)) {
                logger.debug("[SELECTOR] Skip processed: {}", card);
                continue;
            }

            // 检查是否有回复入口（未回复的标志）
            if (hasReplyEntry(card, ocrBoxes)) {
                logger.info("[SELECTOR] Selected target: {}", card);
                return Optional.of(card);
            }
        }

        logger.warn("[SELECTOR] No eligible target found");
        return Optional.empty();
    }

    /**
     * 检查是否已处理
     */
    private boolean isProcessed(ReviewCard card, List<ReviewIdentity> processedReviews) {
        for (ReviewIdentity identity : processedReviews) {
            if (identity.matches(card)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否有回复入口
     */
    private boolean hasReplyEntry(ReviewCard card, List<OcrTextBox> ocrBoxes) {
        ReviewCard.BBox cardBbox = card.getCardBbox();

        for (OcrTextBox box : ocrBoxes) {
            if (box.getText().equals("回复")) {
                // 在卡片区域内或附近
                if (box.getY() >= cardBbox.getTop() && box.getY() <= cardBbox.getBottom() + 100) {
                    return true;
                }
            }
        }

        return false;
    }
}
