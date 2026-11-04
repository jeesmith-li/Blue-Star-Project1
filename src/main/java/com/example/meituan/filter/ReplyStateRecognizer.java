package com.example.meituan.filter;

import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.review.ReplyState;
import com.example.meituan.review.ReviewCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 回复状态识别器
 * 基于 OCR 识别评论是否已回复
 */
public class ReplyStateRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(ReplyStateRecognizer.class);

    /**
     * 识别回复状态
     */
    public ReplyState recognize(ReviewCard card, List<OcrTextBox> allBoxes) {
        // 如果 ReviewCard 已经有明确的回复状态，使用它
        if (card.getReplyState() != ReplyState.UNKNOWN) {
            return card.getReplyState();
        }

        // 在卡片区域内查找回复证据
        ReviewCard.BBox cardBbox = card.getCardBbox();

        for (OcrTextBox box : allBoxes) {
            if (!isInsideCard(box, cardBbox)) {
                continue;
            }

            String text = box.getText();

            // 查找回复关键词
            if (text.contains("商家回复") || text.contains("店家回复")) {
                logger.debug("[REPLY] Found reply evidence: {}", text);
                return ReplyState.REPLIED;
            }

            // 查找"回复："后跟内容的模式
            if (text.startsWith("回复：") || text.startsWith("回复:")) {
                logger.debug("[REPLY] Found reply evidence: {}", text);
                return ReplyState.REPLIED;
            }
        }

        // 没有找到明确证据，保持 UNKNOWN
        logger.debug("[REPLY] No reply evidence found, state remains UNKNOWN");
        return ReplyState.UNKNOWN;
    }

    /**
     * 判断 box 是否在卡片内
     */
    private boolean isInsideCard(OcrTextBox box, ReviewCard.BBox cardBbox) {
        if (cardBbox == null) {
            return false;
        }

        return box.getX() >= cardBbox.getLeft() &&
               box.getX() + box.getWidth() <= cardBbox.getRight() &&
               box.getY() >= cardBbox.getTop() &&
               box.getY() + box.getHeight() <= cardBbox.getBottom();
    }
}
