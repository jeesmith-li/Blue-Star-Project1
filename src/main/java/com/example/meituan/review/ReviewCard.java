package com.example.meituan.review;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论卡片数据结构
 */
public class ReviewCard {
    private int reviewIndex;
    private String reviewer;
    private String reviewTime;
    private String content;
    private String orderDetails;  // 新增：订单详情
    private BBox contentBbox;

    // 评分信息
    private Integer productRating;
    private Integer packagingRating;
    private String deliveryRating;
    private String ratingEvidence;

    // 回复信息
    private ReplyState replyState;
    private String replyContent;
    private String replyEvidence;

    // 卡片信息
    private BBox anchorBbox;  // 新增：锚点位置
    private BBox cardBbox;
    private double confidence;

    // 额外字段
    private List<String> contentLines;
    private BBox ratingBbox;
    private BBox replyBbox;

    public ReviewCard() {
        this.contentLines = new ArrayList<>();
        this.replyState = ReplyState.UNKNOWN;
    }

    // Getters and Setters
    public int getReviewIndex() {
        return reviewIndex;
    }

    public void setReviewIndex(int reviewIndex) {
        this.reviewIndex = reviewIndex;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getReviewTime() {
        return reviewTime;
    }

    public void setReviewTime(String reviewTime) {
        this.reviewTime = reviewTime;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public BBox getContentBbox() {
        return contentBbox;
    }

    public void setContentBbox(BBox contentBbox) {
        this.contentBbox = contentBbox;
    }

    public Integer getProductRating() {
        return productRating;
    }

    public void setProductRating(Integer productRating) {
        this.productRating = productRating;
    }

    public Integer getPackagingRating() {
        return packagingRating;
    }

    public void setPackagingRating(Integer packagingRating) {
        this.packagingRating = packagingRating;
    }

    public String getDeliveryRating() {
        return deliveryRating;
    }

    public void setDeliveryRating(String deliveryRating) {
        this.deliveryRating = deliveryRating;
    }

    public String getRatingEvidence() {
        return ratingEvidence;
    }

    public void setRatingEvidence(String ratingEvidence) {
        this.ratingEvidence = ratingEvidence;
    }

    public ReplyState getReplyState() {
        return replyState;
    }

    public void setReplyState(ReplyState replyState) {
        this.replyState = replyState;
    }

    public String getReplyContent() {
        return replyContent;
    }

    public void setReplyContent(String replyContent) {
        this.replyContent = replyContent;
    }

    public String getReplyEvidence() {
        return replyEvidence;
    }

    public void setReplyEvidence(String replyEvidence) {
        this.replyEvidence = replyEvidence;
    }

    public String getOrderDetails() {
        return orderDetails;
    }

    public void setOrderDetails(String orderDetails) {
        this.orderDetails = orderDetails;
    }

    public BBox getAnchorBbox() {
        return anchorBbox;
    }

    public void setAnchorBbox(BBox anchorBbox) {
        this.anchorBbox = anchorBbox;
    }

    public BBox getCardBbox() {
        return cardBbox;
    }

    public void setCardBbox(BBox cardBbox) {
        this.cardBbox = cardBbox;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public List<String> getContentLines() {
        return contentLines;
    }

    public void setContentLines(List<String> contentLines) {
        this.contentLines = contentLines;
    }

    public void addContentLine(String line) {
        this.contentLines.add(line);
    }

    public BBox getRatingBbox() {
        return ratingBbox;
    }

    public void setRatingBbox(BBox ratingBbox) {
        this.ratingBbox = ratingBbox;
    }

    public BBox getReplyBbox() {
        return replyBbox;
    }

    public void setReplyBbox(BBox replyBbox) {
        this.replyBbox = replyBbox;
    }

    @Override
    public String toString() {
        return String.format("ReviewCard[#%d, reviewer=%s, product=%s, packaging=%s, delivery=%s, reply=%s, content='%s', bbox=%s, conf=%.2f]",
                reviewIndex,
                reviewer != null ? reviewer : "未知",
                productRating != null ? productRating + "分" : "未知",
                packagingRating != null ? packagingRating + "分" : "未知",
                deliveryRating != null ? deliveryRating : "未知",
                replyState,
                content != null && content.length() > 20 ? content.substring(0, 20) + "..." : content,
                cardBbox,
                confidence);
    }

    /**
     * 边界框辅助类
     */
    public static class BBox {
        private int x;
        private int y;
        private int width;
        private int height;

        public BBox(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getCenterX() {
            return x + width / 2;
        }

        public int getCenterY() {
            return y + height / 2;
        }

        public int getTop() {
            return y;
        }

        public int getBottom() {
            return y + height;
        }

        public int getLeft() {
            return x;
        }

        public int getRight() {
            return x + width;
        }

        @Override
        public String toString() {
            return String.format("(%d,%d,%dx%d)", x, y, width, height);
        }
    }
}
