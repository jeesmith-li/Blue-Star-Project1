package com.example.meituan.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论卡片验证器
 * 检测重复、重叠、缺失等问题
 */
public class ReviewCardValidator {
    private static final Logger logger = LoggerFactory.getLogger(ReviewCardValidator.class);

    private List<String> validationErrors = new ArrayList<>();

    /**
     * 验证评论卡片
     */
    public List<ReviewCard> validate(List<ReviewCard> cards, List<ReviewAnchor> anchors) {
        validationErrors.clear();

        logger.info("[VALIDATION] Validating {} cards against {} anchors", cards.size(), anchors.size());

        // 1. 检查 Anchor -> Card 映射
        if (cards.size() != anchors.size()) {
            String error = String.format("Card count (%d) != Anchor count (%d)", cards.size(), anchors.size());
            validationErrors.add(error);
            logger.warn("[VALIDATION] {}", error);
        }

        // 2. 过滤无效卡片
        List<ReviewCard> validCards = cards.stream()
                .filter(this::isValidCard)
                .collect(Collectors.toList());

        if (validCards.size() < cards.size()) {
            logger.info("[VALIDATION] Filtered {} invalid cards", cards.size() - validCards.size());
        }

        // 3. 检测重叠
        List<ReviewCard> deduped = detectAndRemoveOverlaps(validCards);

        // 4. 检测重复内容
        deduped = detectAndRemoveDuplicates(deduped);

        // 5. 最终报告
        if (validationErrors.isEmpty()) {
            logger.info("[VALIDATION] PASS - {} valid cards", deduped.size());
        } else {
            logger.warn("[VALIDATION] ISSUES FOUND:");
            for (String error : validationErrors) {
                logger.warn("[VALIDATION]   - {}", error);
            }
        }

        return deduped;
    }

    /**
     * 检查单个卡片是否有效
     */
    private boolean isValidCard(ReviewCard card) {
        // 必须有评论者或评分
        if (card.getReviewer() == null && card.getProductRating() == null) {
            logger.debug("[VALIDATION] Rejected: no reviewer and no rating");
            return false;
        }

        // 必须有内容
        if (card.getContent() == null || card.getContent().trim().isEmpty()) {
            logger.debug("[VALIDATION] Rejected: no content");
            return false;
        }

        // 内容不能太短
        if (card.getContent().length() < 5) {
            logger.debug("[VALIDATION] Rejected: content too short: '{}'", card.getContent());
            return false;
        }

        // 置信度不能太低
        if (card.getConfidence() < 0.4) {
            logger.debug("[VALIDATION] Rejected: low confidence {}", card.getConfidence());
            return false;
        }

        return true;
    }

    /**
     * 检测并移除重叠的卡片
     */
    private List<ReviewCard> detectAndRemoveOverlaps(List<ReviewCard> cards) {
        if (cards.size() <= 1) {
            return cards;
        }

        List<ReviewCard> result = new ArrayList<>();
        Set<ReviewCard> removed = new HashSet<>();

        for (int i = 0; i < cards.size(); i++) {
            if (removed.contains(cards.get(i))) {
                continue;
            }

            ReviewCard card1 = cards.get(i);
            boolean hasOverlap = false;

            for (int j = i + 1; j < cards.size(); j++) {
                ReviewCard card2 = cards.get(j);

                if (removed.contains(card2)) {
                    continue;
                }

                double iou = calculateIoU(card1.getCardBbox(), card2.getCardBbox());

                if (iou > 0.5) {  // 50% 重叠
                    hasOverlap = true;

                    // 保留置信度更高的
                    if (card1.getConfidence() >= card2.getConfidence()) {
                        removed.add(card2);
                        logger.debug("[VALIDATION] Removed overlapping card (IoU={:.2f}): {}", iou, card2);
                    } else {
                        removed.add(card1);
                        logger.debug("[VALIDATION] Removed overlapping card (IoU={:.2f}): {}", iou, card1);
                        break;
                    }
                }
            }

            if (!removed.contains(card1)) {
                result.add(card1);
            }
        }

        if (removed.size() > 0) {
            String error = String.format("Removed %d overlapping cards", removed.size());
            validationErrors.add(error);
        }

        return result;
    }

    /**
     * 检测并移除重复内容
     */
    private List<ReviewCard> detectAndRemoveDuplicates(List<ReviewCard> cards) {
        if (cards.size() <= 1) {
            return cards;
        }

        List<ReviewCard> result = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();

        for (ReviewCard card : cards) {
            String signature = createSignature(card);

            if (!seenSignatures.contains(signature)) {
                seenSignatures.add(signature);
                result.add(card);
            } else {
                logger.debug("[VALIDATION] Removed duplicate: {}", card);
                String error = "Duplicate content: " + signature;
                validationErrors.add(error);
            }
        }

        return result;
    }

    /**
     * 创建卡片签名（用于去重）
     */
    private String createSignature(ReviewCard card) {
        return String.format("%s|%s|%s|%s",
                card.getReviewer() != null ? card.getReviewer() : "",
                card.getReviewTime() != null ? card.getReviewTime() : "",
                card.getProductRating() != null ? card.getProductRating() : "",
                card.getContent() != null && card.getContent().length() > 20 ?
                        card.getContent().substring(0, 20) : card.getContent());
    }

    /**
     * 计算两个边界框的 IoU (Intersection over Union)
     */
    private double calculateIoU(ReviewCard.BBox bbox1, ReviewCard.BBox bbox2) {
        if (bbox1 == null || bbox2 == null) {
            return 0.0;
        }

        int x1 = Math.max(bbox1.getLeft(), bbox2.getLeft());
        int y1 = Math.max(bbox1.getTop(), bbox2.getTop());
        int x2 = Math.min(bbox1.getRight(), bbox2.getRight());
        int y2 = Math.min(bbox1.getBottom(), bbox2.getBottom());

        if (x2 <= x1 || y2 <= y1) {
            return 0.0;
        }

        int intersection = (x2 - x1) * (y2 - y1);
        int area1 = bbox1.getWidth() * bbox1.getHeight();
        int area2 = bbox2.getWidth() * bbox2.getHeight();
        int union = area1 + area2 - intersection;

        return union > 0 ? (double) intersection / union : 0.0;
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }

    public boolean hasErrors() {
        return !validationErrors.isEmpty();
    }
}
