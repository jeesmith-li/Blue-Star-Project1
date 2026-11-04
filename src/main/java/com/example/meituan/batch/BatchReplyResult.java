package com.example.meituan.batch;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量回复结果
 */
public class BatchReplyResult {
    private int maxReviews;
    private int successCount;
    private int failureCount;
    private boolean failed;
    private List<ReviewIdentity> processedReviews;
    private String failureReason;

    public BatchReplyResult(int maxReviews) {
        this.maxReviews = maxReviews;
        this.successCount = 0;
        this.failureCount = 0;
        this.failed = false;
        this.processedReviews = new ArrayList<>();
    }

    public int getMaxReviews() {
        return maxReviews;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void incrementSuccess() {
        this.successCount++;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void incrementFailure() {
        this.failureCount++;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public List<ReviewIdentity> getProcessedReviews() {
        return processedReviews;
    }

    public void addProcessed(ReviewIdentity identity) {
        this.processedReviews.add(identity);
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public boolean isCompleted() {
        return successCount >= maxReviews;
    }

    @Override
    public String toString() {
        return String.format("BatchResult[success=%d/%d, failed=%s, reason='%s']",
            successCount, maxReviews, failed, failureReason);
    }
}
