package com.example.meituan.batch;

import com.example.meituan.review.ReviewCard;

/**
 * 评论身份标识
 * 用于跟踪已处理的评论，防止重复
 */
public class ReviewIdentity {
    private final String reviewer;
    private final String reviewDate;
    private final String contentSnippet;
    private final Integer productRating;
    private final Integer packagingRating;
    private final double identityConfidence;

    public ReviewIdentity(ReviewCard card) {
        this.reviewer = card.getReviewer();
        this.reviewDate = card.getReviewTime();
        this.contentSnippet = extractSnippet(card.getContent());
        this.productRating = card.getProductRating();
        this.packagingRating = card.getPackagingRating();
        this.identityConfidence = calculateConfidence(card);
    }

    private String extractSnippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 20 ? content.substring(0, 20) : content;
    }

    private double calculateConfidence(ReviewCard card) {
        double conf = 0.0;
        if (card.getReviewer() != null) conf += 0.3;
        if (card.getReviewTime() != null) conf += 0.3;
        if (card.getContent() != null && card.getContent().length() > 5) conf += 0.2;
        if (card.getProductRating() != null) conf += 0.1;
        if (card.getPackagingRating() != null) conf += 0.1;
        return conf;
    }

    /**
     * 匹配评论身份
     */
    public boolean matches(ReviewCard card) {
        int matchScore = 0;

        // 评论者匹配
        if (reviewer != null && reviewer.equals(card.getReviewer())) {
            matchScore += 3;
        }

        // 日期匹配
        if (reviewDate != null && card.getReviewTime() != null &&
            reviewDate.contains(card.getReviewTime().substring(0, Math.min(10, card.getReviewTime().length())))) {
            matchScore += 3;
        }

        // 内容匹配
        String cardSnippet = extractSnippet(card.getContent());
        if (contentSnippet.equals(cardSnippet)) {
            matchScore += 2;
        }

        // 评分匹配
        if (productRating != null && productRating.equals(card.getProductRating())) {
            matchScore += 1;
        }

        // 至少5分才认为是同一条
        return matchScore >= 5;
    }

    @Override
    public String toString() {
        return String.format("Identity[reviewer=%s, date=%s, snippet='%s', rating=%s/%s, conf=%.2f]",
            reviewer, reviewDate, contentSnippet, productRating, packagingRating, identityConfidence);
    }

    // Getters
    public String getReviewer() { return reviewer; }
    public String getReviewDate() { return reviewDate; }
    public String getContentSnippet() { return contentSnippet; }
    public Integer getProductRating() { return productRating; }
    public Integer getPackagingRating() { return packagingRating; }
    public double getIdentityConfidence() { return identityConfidence; }
}
