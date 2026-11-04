package com.example.meituan.review;

import com.example.meituan.ocr.OcrTextBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论锚点 - PHASE 4.2 重构版
 * 一个完整的评论锚点包含：用户名、评分、日期等多个证据
 */
public class ReviewAnchor {
    private OcrTextBox reviewerBox;
    private OcrTextBox ratingBox;
    private OcrTextBox dateBox;

    private String reviewer;
    private String date;
    private Integer productRating;
    private Integer packagingRating;
    private String deliveryRating;

    private ReviewCard.BBox anchorBbox;
    private double confidence;

    private List<OcrTextBox> allBoxes;

    public ReviewAnchor() {
        this.allBoxes = new ArrayList<>();
    }

    public OcrTextBox getReviewerBox() {
        return reviewerBox;
    }

    public void setReviewerBox(OcrTextBox reviewerBox) {
        this.reviewerBox = reviewerBox;
        addBox(reviewerBox);
    }

    public OcrTextBox getRatingBox() {
        return ratingBox;
    }

    public void setRatingBox(OcrTextBox ratingBox) {
        this.ratingBox = ratingBox;
        addBox(ratingBox);
    }

    public OcrTextBox getDateBox() {
        return dateBox;
    }

    public void setDateBox(OcrTextBox dateBox) {
        this.dateBox = dateBox;
        addBox(dateBox);
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
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

    public ReviewCard.BBox getAnchorBbox() {
        return anchorBbox;
    }

    public void setAnchorBbox(ReviewCard.BBox anchorBbox) {
        this.anchorBbox = anchorBbox;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public List<OcrTextBox> getAllBoxes() {
        return allBoxes;
    }

    private void addBox(OcrTextBox box) {
        if (box != null && !allBoxes.contains(box)) {
            allBoxes.add(box);
        }
    }

    /**
     * 计算锚点的中心Y坐标（用于排序）
     */
    public int getCenterY() {
        if (anchorBbox != null) {
            return anchorBbox.getCenterY();
        }
        if (reviewerBox != null) {
            return reviewerBox.getCenterY();
        }
        if (ratingBox != null) {
            return ratingBox.getCenterY();
        }
        if (dateBox != null) {
            return dateBox.getCenterY();
        }
        return 0;
    }

    /**
     * 重新计算锚点边界框
     */
    public void recalculateBbox() {
        if (allBoxes.isEmpty()) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (OcrTextBox box : allBoxes) {
            minX = Math.min(minX, box.getX());
            minY = Math.min(minY, box.getY());
            maxX = Math.max(maxX, box.getX() + box.getWidth());
            maxY = Math.max(maxY, box.getY() + box.getHeight());
        }

        this.anchorBbox = new ReviewCard.BBox(minX, minY, maxX - minX, maxY - minY);
    }

    @Override
    public String toString() {
        return String.format("Anchor[reviewer=%s, date=%s, rating=%s/%s, bbox=%s, conf=%.2f]",
                reviewer, date, productRating, packagingRating, anchorBbox, confidence);
    }
}
