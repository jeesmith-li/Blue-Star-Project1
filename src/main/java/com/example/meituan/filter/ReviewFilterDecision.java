package com.example.meituan.filter;

import com.example.meituan.rating.RatingResult;
import com.example.meituan.review.ReplyState;

import java.util.ArrayList;
import java.util.List;

/**
 * 评价筛选决策
 * 判断评论是否符合自动回复条件
 */
public class ReviewFilterDecision {
    private int reviewIndex;
    private BadReviewState badReviewState;
    private ReplyState replyState;
    private boolean eligibleForAutoReply;
    private double confidence;
    private String reason;
    private List<String> ratingEvidence;
    private List<String> replyEvidence;

    // 评分详情
    private Integer overallRating;
    private Integer productRating;
    private Integer packagingRating;
    private String deliveryRating;

    public ReviewFilterDecision() {
        this.ratingEvidence = new ArrayList<>();
        this.replyEvidence = new ArrayList<>();
    }

    // Getters and Setters
    public int getReviewIndex() {
        return reviewIndex;
    }

    public void setReviewIndex(int reviewIndex) {
        this.reviewIndex = reviewIndex;
    }

    public BadReviewState getBadReviewState() {
        return badReviewState;
    }

    public void setBadReviewState(BadReviewState badReviewState) {
        this.badReviewState = badReviewState;
    }

    public ReplyState getReplyState() {
        return replyState;
    }

    public void setReplyState(ReplyState replyState) {
        this.replyState = replyState;
    }

    public boolean isEligibleForAutoReply() {
        return eligibleForAutoReply;
    }

    public void setEligibleForAutoReply(boolean eligibleForAutoReply) {
        this.eligibleForAutoReply = eligibleForAutoReply;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getRatingEvidence() {
        return ratingEvidence;
    }

    public void setRatingEvidence(List<String> ratingEvidence) {
        this.ratingEvidence = ratingEvidence;
    }

    public List<String> getReplyEvidence() {
        return replyEvidence;
    }

    public void setReplyEvidence(List<String> replyEvidence) {
        this.replyEvidence = replyEvidence;
    }

    public Integer getOverallRating() {
        return overallRating;
    }

    public void setOverallRating(Integer overallRating) {
        this.overallRating = overallRating;
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

    /**
     * 判断是否符合自动回复条件
     */
    public static ReviewFilterDecision create(int reviewIndex, RatingResult ratingResult,
                                             BadReviewState badReviewState, ReplyState replyState) {
        ReviewFilterDecision decision = new ReviewFilterDecision();
        decision.setReviewIndex(reviewIndex);
        decision.setBadReviewState(badReviewState);
        decision.setReplyState(replyState);

        // 复制评分详情
        decision.setOverallRating(ratingResult.getOverallRating());
        decision.setProductRating(ratingResult.getProductRating());
        decision.setPackagingRating(ratingResult.getPackagingRating());
        decision.setDeliveryRating(ratingResult.getDeliveryRating());
        decision.setRatingEvidence(new ArrayList<>(ratingResult.getEvidence()));

        // 判断是否符合自动回复条件
        boolean eligible = false;
        String reason = "";

        if (badReviewState == BadReviewState.BAD_REVIEW && replyState == ReplyState.UNREPLIED) {
            eligible = true;
            reason = "差评且未回复";
        } else if (badReviewState == BadReviewState.UNKNOWN) {
            eligible = false;
            reason = "总体评分未知，无法判断";
        } else if (replyState == ReplyState.UNKNOWN) {
            eligible = false;
            reason = "回复状态未知，无法判断";
        } else if (badReviewState != BadReviewState.BAD_REVIEW) {
            eligible = false;
            reason = "非差评";
        } else if (replyState == ReplyState.REPLIED) {
            eligible = false;
            reason = "已回复";
        }

        decision.setEligibleForAutoReply(eligible);
        decision.setReason(reason);
        decision.setConfidence(ratingResult.getConfidence());

        return decision;
    }

    @Override
    public String toString() {
        return String.format("Decision[#%d, bad=%s, reply=%s, eligible=%s, reason='%s', conf=%.2f]",
                reviewIndex, badReviewState, replyState, eligibleForAutoReply, reason, confidence);
    }
}
