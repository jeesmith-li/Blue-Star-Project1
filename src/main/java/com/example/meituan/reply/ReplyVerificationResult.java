package com.example.meituan.reply;

import com.example.meituan.review.ReplyState;
import com.example.meituan.review.ReviewCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 回复验证结果
 */
public class ReplyVerificationResult {
    private boolean verified;
    private double confidence;
    private List<String> evidence;
    private ReviewCard matchedReview;
    private ReplyState replyState;

    public ReplyVerificationResult() {
        this.evidence = new ArrayList<>();
        this.replyState = ReplyState.UNKNOWN;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void addEvidence(String e) {
        this.evidence.add(e);
    }

    public ReviewCard getMatchedReview() {
        return matchedReview;
    }

    public void setMatchedReview(ReviewCard matchedReview) {
        this.matchedReview = matchedReview;
    }

    public ReplyState getReplyState() {
        return replyState;
    }

    public void setReplyState(ReplyState replyState) {
        this.replyState = replyState;
    }

    @Override
    public String toString() {
        return String.format("VerificationResult[verified=%s, state=%s, conf=%.2f, evidence=%s]",
            verified, replyState, confidence, evidence);
    }
}
