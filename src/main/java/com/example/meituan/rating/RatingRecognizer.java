package com.example.meituan.rating;

import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.review.ReviewCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评分识别器
 * 基于 OCR 和 OpenCV 识别评分
 */
public class RatingRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(RatingRecognizer.class);

    /**
     * 识别评分
     */
    public RatingResult recognizeRating(ReviewCard card, BufferedImage screenshot, List<OcrTextBox> allBoxes) {
        RatingResult result = new RatingResult();

        // 从 ReviewCard 已有的评分信息
        result.setProductRating(card.getProductRating());
        result.setPackagingRating(card.getPackagingRating());
        result.setDeliveryRating(card.getDeliveryRating());

        if (card.getProductRating() != null) {
            result.addEvidence("商品评分: " + card.getProductRating());
        }
        if (card.getPackagingRating() != null) {
            result.addEvidence("包装评分: " + card.getPackagingRating());
        }
        if (card.getDeliveryRating() != null) {
            result.addEvidence("配送: " + card.getDeliveryRating());
        }

        // 尝试从 OCR 中查找总体评分
        Integer overallRating = findOverallRating(card, allBoxes);
        result.setOverallRating(overallRating);

        if (overallRating != null) {
            result.addEvidence("总体评分: " + overallRating);
        }

        // 计算置信度
        double confidence = calculateConfidence(result);
        result.setConfidence(confidence);

        logger.debug("[RATING] Recognized: {}", result);

        return result;
    }

    /**
     * 查找总体评分
     * 注意：商品评分、包装评分不等于总体评分
     */
    private Integer findOverallRating(ReviewCard card, List<OcrTextBox> allBoxes) {
        // 在卡片区域内查找明确的总体评分标识
        ReviewCard.BBox cardBbox = card.getCardBbox();

        for (OcrTextBox box : allBoxes) {
            if (!isInsideCard(box, cardBbox)) {
                continue;
            }

            String text = box.getText();

            // 查找明确的总体评分模式
            // 例如："总体评分: 2星", "综合: 1星"
            Pattern pattern = Pattern.compile("(总体|综合|评分).*?([1-5])\\s*星");
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 如果没有明确的总体评分，返回 null（UNKNOWN）
        // 不要自动用商品评分替代
        return null;
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

    /**
     * 计算置信度
     */
    private double calculateConfidence(RatingResult result) {
        double conf = 0.0;

        if (result.getProductRating() != null) {
            conf += 0.3;
        }

        if (result.getPackagingRating() != null) {
            conf += 0.2;
        }

        if (result.getDeliveryRating() != null) {
            conf += 0.2;
        }

        if (result.getOverallRating() != null) {
            conf += 0.3;
        }

        return conf;
    }
}
