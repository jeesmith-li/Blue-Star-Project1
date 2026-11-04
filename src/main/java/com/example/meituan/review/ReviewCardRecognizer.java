package com.example.meituan.review;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 评论卡片识别器 - PHASE 4.2 重构版
 * 核心修复：
 * 1. Anchor 去重 - 同一评论只生成一个 Anchor
 * 2. 卡片边界 - 基于相邻 Anchor 动态确定
 * 3. 页面噪声过滤 - 统计文字不进入 content
 * 4. 订单详情分离 - content 与 orderDetails 分开
 */
public class ReviewCardRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(ReviewCardRecognizer.class);

    // 页面级关键词（不应成为评论内容）
    private static final Set<String> PAGE_NOISE_KEYWORDS = Set.of(
            "外卖评价统计", "综合评分", "查看评分趋势", "商品质量分", "服务体验分",
            "查看明细", "近6个月", "近3个月", "近1个月", "开始时间", "结束时间",
            "筛选", "查询", "导出", "http", "https", "waimai.meituan.com",
            "导入收藏夹", "外卖评价列表", "评价日期", "领先同城同行"
    );

    /**
     * 检测评论卡片 - 主入口
     */
    public List<ReviewCard> detectCards(List<OcrTextBox> allTextBoxes) {
        logger.info("[REVIEW] Starting PHASE 4.2 card detection from {} OCR boxes", allTextBoxes.size());

        // 1. 构建初步锚点候选
        List<ReviewAnchor> rawAnchors = buildRawAnchors(allTextBoxes);
        logger.info("[REVIEW] Built {} raw anchors", rawAnchors.size());

        // 2. 合并和去重锚点
        List<ReviewAnchor> dedupedAnchors = deduplicateAnchors(rawAnchors, allTextBoxes);
        logger.info("[REVIEW] After deduplication: {} unique anchors", dedupedAnchors.size());

        // 3. 为每个锚点构建 ReviewCard
        List<ReviewCard> cards = buildCardsFromAnchors(dedupedAnchors, allTextBoxes);
        logger.info("[REVIEW] Built {} review cards", cards.size());

        // 4. 验证和过滤
        ReviewCardValidator validator = new ReviewCardValidator();
        cards = validator.validate(cards, dedupedAnchors);
        logger.info("[REVIEW] After validation: {} valid cards", cards.size());

        // 5. 排序并分配索引
        cards.sort(Comparator.comparingInt(c -> c.getCardBbox().getTop()));
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).setReviewIndex(i + 1);
        }

        return cards;
    }

    /**
     * Fallback: 基于「回复」按钮检测评论卡片
     * 当标准锚点检测失败时，用屏幕上的"回复"文字作为卡片锚点
     * 每个"回复"按钮代表一条未回复的评论
     */
    public List<ReviewCard> detectCardsFromReplyButtons(List<OcrTextBox> allTextBoxes) {
        logger.info("[REVIEW-FALLBACK] Trying reply-button based detection from {} boxes", allTextBoxes.size());

        // 找到所有"回复"文本框
        List<OcrTextBox> replyBoxes = allTextBoxes.stream()
                .filter(b -> "回复".equals(b.getText().trim()))
                .sorted(Comparator.comparingInt(OcrTextBox::getY))
                .collect(Collectors.toList());

        logger.info("[REVIEW-FALLBACK] Found {} reply buttons", replyBoxes.size());

        if (replyBoxes.isEmpty()) {
            return new ArrayList<>();
        }

        List<ReviewCard> cards = new ArrayList<>();
        int screenHeight = allTextBoxes.stream()
                .mapToInt(b -> b.getY() + b.getHeight())
                .max().orElse(1080);

        for (int i = 0; i < replyBoxes.size(); i++) {
            OcrTextBox replyBox = replyBoxes.get(i);
            ReviewCard card = new ReviewCard();

            // 卡片上边界：上一个回复按钮下方 或 屏幕顶部
            final int topY = (i > 0) ? replyBoxes.get(i - 1).getY() + 10 : 0;
            // 卡片下边界：当前回复按钮下方 200px（最后一个多延伸到300px）
            final int bottomY = (i == replyBoxes.size() - 1)
                    ? Math.min(replyBox.getY() + 300, screenHeight)
                    : Math.min(replyBox.getY() + 200, screenHeight);

            // 收集该区域内的文本框
            List<OcrTextBox> cardBoxes = allTextBoxes.stream()
                    .filter(b -> b.getY() >= topY && b.getY() <= bottomY)
                    .filter(b -> !isPageNoise(b.getText()))
                    .collect(Collectors.toList());

            // 找用户名（区域内最靠上的短文本）
            cardBoxes.stream()
                    .filter(b -> isUserNamePattern(b.getText()))
                    .min(Comparator.comparingInt(OcrTextBox::getY))
                    .ifPresent(b -> card.setReviewer(b.getText()));

            // 设置回复按钮位置（用于后续点击）
            card.setReplyBbox(new ReviewCard.BBox(
                    replyBox.getX(), replyBox.getY(),
                    replyBox.getWidth(), replyBox.getHeight()));

            // 设置卡片边界
            int minX = cardBoxes.stream().mapToInt(OcrTextBox::getX).min().orElse(replyBox.getX() - 200);
            int minY = topY;
            int maxX = cardBoxes.stream().mapToInt(b -> b.getX() + b.getWidth()).max().orElse(replyBox.getX() + 100);
            int maxY = bottomY;
            card.setCardBbox(new ReviewCard.BBox(minX, minY, maxX - minX, maxY - minY));
            card.setAnchorBbox(new ReviewCard.BBox(replyBox.getX(), replyBox.getY(),
                    replyBox.getWidth(), replyBox.getHeight()));

            card.setReplyState(ReplyState.UNREPLIED);
            card.setConfidence(0.8);
            card.setReviewIndex(i + 1);

            cards.add(card);
            logger.info("[REVIEW-FALLBACK] Card {}: reply at ({},{}), reviewer={}",
                    i + 1, replyBox.getX(), replyBox.getY(), card.getReviewer());
        }

        return cards;
    }

    /**
     * 构建初步锚点候选
     */
    private List<ReviewAnchor> buildRawAnchors(List<OcrTextBox> textBoxes) {
        Map<String, OcrTextBox> userBoxes = new HashMap<>();
        Map<String, OcrTextBox> ratingBoxes = new HashMap<>();
        Map<String, OcrTextBox> dateBoxes = new HashMap<>();

        // 分类识别不同类型的证据
        for (OcrTextBox box : textBoxes) {
            String text = box.getText();

            if (isPageNoise(text)) {
                continue;
            }

            if (isUserNamePattern(text)) {
                userBoxes.put(getBoxKey(box), box);
            }

            if (isRatingPattern(text)) {
                ratingBoxes.put(getBoxKey(box), box);
            }

            if (isDatePattern(text)) {
                dateBoxes.put(getBoxKey(box), box);
            }
        }

        logger.debug("[ANCHOR] Found {} user boxes, {} rating boxes, {} date boxes",
                userBoxes.size(), ratingBoxes.size(), dateBoxes.size());

        // 构建锚点组
        List<ReviewAnchor> anchors = new ArrayList<>();

        // 从评分入手（评分是最可靠的锚点）
        for (OcrTextBox ratingBox : ratingBoxes.values()) {
            ReviewAnchor anchor = new ReviewAnchor();
            anchor.setRatingBox(ratingBox);
            parseRating(anchor, ratingBox.getText());

            // 查找附近的用户名和日期
            findNearbyEvidence(anchor, userBoxes.values(), dateBoxes.values(), textBoxes);

            anchor.recalculateBbox();
            calculateAnchorConfidence(anchor);

            anchors.add(anchor);
            logger.debug("[ANCHOR] Created: {}", anchor);
        }

        return anchors;
    }

    /**
     * 去重锚点 - 核心算法
     */
    private List<ReviewAnchor> deduplicateAnchors(List<ReviewAnchor> rawAnchors, List<OcrTextBox> allBoxes) {
        if (rawAnchors.isEmpty()) {
            return rawAnchors;
        }

        // 计算动态阈值
        int medianHeight = calculateMedianHeight(allBoxes);
        int overlapThreshold = medianHeight * 3;

        logger.debug("[DEDUP] Median height: {}, overlap threshold: {}", medianHeight, overlapThreshold);

        List<ReviewAnchor> result = new ArrayList<>();
        Set<ReviewAnchor> processed = new HashSet<>();

        for (ReviewAnchor anchor : rawAnchors) {
            if (processed.contains(anchor)) {
                continue;
            }

            // 查找所有与当前锚点重叠的锚点
            List<ReviewAnchor> overlapping = new ArrayList<>();
            overlapping.add(anchor);

            for (ReviewAnchor other : rawAnchors) {
                if (other == anchor || processed.contains(other)) {
                    continue;
                }

                if (isOverlapping(anchor, other, overlapThreshold)) {
                    overlapping.add(other);
                }
            }

            // 合并重叠的锚点
            ReviewAnchor merged = mergeAnchors(overlapping);
            result.add(merged);

            processed.addAll(overlapping);
            logger.debug("[DEDUP] Merged {} anchors into one", overlapping.size());
        }

        return result;
    }

    /**
     * 判断两个锚点是否重叠
     */
    private boolean isOverlapping(ReviewAnchor a1, ReviewAnchor a2, int threshold) {
        ReviewCard.BBox bbox1 = a1.getAnchorBbox();
        ReviewCard.BBox bbox2 = a2.getAnchorBbox();

        if (bbox1 == null || bbox2 == null) {
            return false;
        }

        // 垂直距离
        int verticalGap = Math.abs(bbox1.getCenterY() - bbox2.getCenterY());

        // 水平重叠
        int overlapX = Math.min(bbox1.getRight(), bbox2.getRight()) -
                      Math.max(bbox1.getLeft(), bbox2.getLeft());

        return verticalGap < threshold && overlapX > 0;
    }

    /**
     * 合并多个锚点
     */
    private ReviewAnchor mergeAnchors(List<ReviewAnchor> anchors) {
        if (anchors.size() == 1) {
            return anchors.get(0);
        }

        ReviewAnchor merged = new ReviewAnchor();

        // 选择最佳证据
        for (ReviewAnchor anchor : anchors) {
            if (merged.getReviewerBox() == null && anchor.getReviewerBox() != null) {
                merged.setReviewerBox(anchor.getReviewerBox());
                merged.setReviewer(anchor.getReviewer());
            }

            if (merged.getRatingBox() == null && anchor.getRatingBox() != null) {
                merged.setRatingBox(anchor.getRatingBox());
                merged.setProductRating(anchor.getProductRating());
                merged.setPackagingRating(anchor.getPackagingRating());
                merged.setDeliveryRating(anchor.getDeliveryRating());
            }

            if (merged.getDateBox() == null && anchor.getDateBox() != null) {
                merged.setDateBox(anchor.getDateBox());
                merged.setDate(anchor.getDate());
            }
        }

        merged.recalculateBbox();
        calculateAnchorConfidence(merged);

        return merged;
    }

    /**
     * 从锚点构建 ReviewCard
     */
    private List<ReviewCard> buildCardsFromAnchors(List<ReviewAnchor> anchors, List<OcrTextBox> allBoxes) {
        if (anchors.isEmpty()) {
            return new ArrayList<>();
        }

        // 排序锚点
        anchors.sort(Comparator.comparingInt(ReviewAnchor::getCenterY));

        List<ReviewCard> cards = new ArrayList<>();

        for (int i = 0; i < anchors.size(); i++) {
            ReviewAnchor anchor = anchors.get(i);
            ReviewAnchor nextAnchor = (i + 1 < anchors.size()) ? anchors.get(i + 1) : null;

            ReviewCard card = buildSingleCard(anchor, nextAnchor, allBoxes);
            cards.add(card);
        }

        return cards;
    }

    /**
     * 构建单个 ReviewCard
     */
    private ReviewCard buildSingleCard(ReviewAnchor anchor, ReviewAnchor nextAnchor, List<OcrTextBox> allBoxes) {
        ReviewCard card = new ReviewCard();

        // 从锚点复制基本信息
        card.setReviewer(anchor.getReviewer());
        card.setReviewTime(anchor.getDate());
        card.setProductRating(anchor.getProductRating());
        card.setPackagingRating(anchor.getPackagingRating());
        card.setDeliveryRating(anchor.getDeliveryRating());
        card.setAnchorBbox(anchor.getAnchorBbox());

        // 确定卡片垂直边界
        int topBoundary = anchor.getAnchorBbox().getTop();
        int bottomBoundary = nextAnchor != null ?
                nextAnchor.getAnchorBbox().getTop() :
                Integer.MAX_VALUE;

        // 收集卡片区域内的文本
        List<OcrTextBox> cardBoxes = allBoxes.stream()
                .filter(box -> box.getY() >= topBoundary && box.getY() < bottomBoundary)
                .filter(box -> !isPageNoise(box.getText()))
                .collect(Collectors.toList());

        // 提取内容和订单详情
        extractContentAndOrderDetails(card, cardBoxes, anchor);

        // 检测回复状态
        detectReplyState(card, cardBoxes);

        // 计算卡片边界
        calculateCardBbox(card, cardBoxes);

        // 计算置信度
        calculateCardConfidence(card, anchor);

        return card;
    }

    /**
     * 提取内容和订单详情
     */
    private void extractContentAndOrderDetails(ReviewCard card, List<OcrTextBox> boxes, ReviewAnchor anchor) {
        List<String> contentParts = new ArrayList<>();
        List<String> orderParts = new ArrayList<>();

        for (OcrTextBox box : boxes) {
            String text = box.getText();

            // 跳过已处理的锚点文本
            if (anchor.getAllBoxes().contains(box)) {
                continue;
            }

            // 订单详情
            if (text.startsWith("订单详情") || text.contains("订单详情：")) {
                orderParts.add(text);
                continue;
            }

            // 申诉、回复等
            if (text.contains("申诉") || text.contains("回复")) {
                continue;
            }

            // 正常内容
            if (text.length() > 2 && !isPageNoise(text)) {
                contentParts.add(text);
            }
        }

        card.setContent(String.join(" ", contentParts).trim());
        card.setOrderDetails(String.join(" ", orderParts).trim());
    }

    /**
     * 检测回复状态
     */
    private void detectReplyState(ReviewCard card, List<OcrTextBox> boxes) {
        for (OcrTextBox box : boxes) {
            String text = box.getText();
            if (text.contains("商家回复") || (text.contains("回复") && text.length() > 10)) {
                card.setReplyState(ReplyState.REPLIED);
                card.setReplyEvidence(text);
                return;
            }
        }
        // 默认 UNKNOWN
    }

    /**
     * 计算卡片边界框
     */
    private void calculateCardBbox(ReviewCard card, List<OcrTextBox> boxes) {
        if (boxes.isEmpty()) {
            card.setCardBbox(card.getAnchorBbox());
            return;
        }

        int minX = boxes.stream().mapToInt(OcrTextBox::getX).min().orElse(0);
        int minY = boxes.stream().mapToInt(OcrTextBox::getY).min().orElse(0);
        int maxX = boxes.stream().mapToInt(b -> b.getX() + b.getWidth()).max().orElse(0);
        int maxY = boxes.stream().mapToInt(b -> b.getY() + b.getHeight()).max().orElse(0);

        card.setCardBbox(new ReviewCard.BBox(minX, minY, maxX - minX, maxY - minY));
    }

    /**
     * 计算锚点置信度
     */
    private void calculateAnchorConfidence(ReviewAnchor anchor) {
        double conf = 0.0;

        if (anchor.getReviewerBox() != null) {
            conf += 0.3;
        }

        if (anchor.getRatingBox() != null) {
            conf += 0.4;
        }

        if (anchor.getDateBox() != null) {
            conf += 0.3;
        }

        anchor.setConfidence(Math.min(1.0, conf));
    }

    /**
     * 计算卡片置信度
     */
    private void calculateCardConfidence(ReviewCard card, ReviewAnchor anchor) {
        double conf = anchor.getConfidence() * 0.5;

        if (card.getContent() != null && card.getContent().length() > 10) {
            conf += 0.3;
        }

        if (card.getProductRating() != null && card.getPackagingRating() != null) {
            conf += 0.2;
        }

        card.setConfidence(Math.min(1.0, conf));
    }

    // ========== 辅助方法 ==========

    private boolean isPageNoise(String text) {
        for (String keyword : PAGE_NOISE_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUserNamePattern(String text) {
        if (text == null || text.isEmpty()) return false;
        // 美团用户名通常是：张*、李**、a***b 等脱敏格式
        return (text.contains("*") && text.length() < 20) ||
               Pattern.matches("^[\\u4e00-\\u9fa5A-Za-z0-9]{1,3}\\*+[\\u4e00-\\u9fa5A-Za-z0-9]{0,3}$", text) ||
               (text.contains("用户") && text.length() < 10) ||
               // 纯中文短文本也可能是用户名（未脱敏）
               (text.length() >= 2 && text.length() <= 6 && Pattern.matches("^[\\u4e00-\\u9fa5]+$", text));
    }

    private boolean isRatingPattern(String text) {
        if (text == null || text.isEmpty()) return false;
        // 1. 标准格式：商品1包装1 / 商品 5 包装 4
        if (Pattern.matches(".*商品\\s*[1-5].*包装\\s*[1-5].*", text)) return true;
        // 2. 只有商品或只有包装评分
        if (Pattern.matches(".*商品\\s*[1-5].*", text)) return true;
        if (Pattern.matches(".*包装\\s*[1-5].*", text)) return true;
        // 3. 星星图标被识别为星号/特殊字符
        if (text.contains("★") || text.contains("☆")) return true;
        // 4. OCR 把星星识别成多个 * 号
        if (text.length() <= 8 && text.chars().filter(c -> c == '*').count() >= 3) return true;
        // 5. 包含评分关键词
        if ((text.contains("商品") || text.contains("包装") || text.contains("评分"))
            && text.length() < 20) return true;
        return false;
    }

    private boolean isDatePattern(String text) {
        if (text == null || text.isEmpty()) return false;
        // 2024-01-15 / 2024年1月15日 / 2024/01/15
        return Pattern.matches(".*20\\d{2}[-年/]\\d{1,2}[-月/]\\d{1,2}.*", text) ||
               Pattern.matches(".*\\d{1,2}[-月]\\d{1,2}.*", text);
    }

    private void parseRating(ReviewAnchor anchor, String text) {
        if (text.matches(".*商品\\s*([1-5]).*")) {
            try {
                anchor.setProductRating(Integer.parseInt(text.replaceAll(".*商品\\s*([1-5]).*", "$1")));
            } catch (Exception ignored) {}
        }

        if (text.matches(".*包装\\s*([1-5]).*")) {
            try {
                anchor.setPackagingRating(Integer.parseInt(text.replaceAll(".*包装\\s*([1-5]).*", "$1")));
            } catch (Exception ignored) {}
        }

        if (text.contains("配送满意")) {
            anchor.setDeliveryRating("满意");
        }
    }

    private void findNearbyEvidence(ReviewAnchor anchor, Collection<OcrTextBox> userBoxes,
                                    Collection<OcrTextBox> dateBoxes, List<OcrTextBox> allBoxes) {
        int medianHeight = calculateMedianHeight(allBoxes);
        int searchRadius = medianHeight * 5;

        OcrTextBox ratingBox = anchor.getRatingBox();
        int centerY = ratingBox.getCenterY();

        // 查找最近的用户名
        OcrTextBox nearestUser = findNearest(ratingBox, userBoxes, searchRadius);
        if (nearestUser != null) {
            anchor.setReviewerBox(nearestUser);
            anchor.setReviewer(nearestUser.getText());
        }

        // 查找最近的日期
        OcrTextBox nearestDate = findNearest(ratingBox, dateBoxes, searchRadius);
        if (nearestDate != null) {
            anchor.setDateBox(nearestDate);
            anchor.setDate(nearestDate.getText());
        }
    }

    private OcrTextBox findNearest(OcrTextBox reference, Collection<OcrTextBox> candidates, int maxDistance) {
        OcrTextBox nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (OcrTextBox candidate : candidates) {
            double distance = calculateDistance(reference, candidate);
            if (distance < maxDistance && distance < minDistance) {
                minDistance = distance;
                nearest = candidate;
            }
        }

        return nearest;
    }

    private double calculateDistance(OcrTextBox box1, OcrTextBox box2) {
        int dx = box1.getCenterX() - box2.getCenterX();
        int dy = box1.getCenterY() - box2.getCenterY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private int calculateMedianHeight(List<OcrTextBox> textBoxes) {
        List<Integer> heights = textBoxes.stream()
                .map(OcrTextBox::getHeight)
                .filter(h -> h > 5 && h < 100)
                .sorted()
                .collect(Collectors.toList());

        return heights.isEmpty() ? 20 : heights.get(heights.size() / 2);
    }

    private String getBoxKey(OcrTextBox box) {
        return box.getX() + "," + box.getY() + "," + box.getText();
    }
}
