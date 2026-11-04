package com.example.meituan.rating;

import java.util.ArrayList;
import java.util.List;

/**
 * 评分识别结果
 */
public class RatingResult {
    private Integer productRating;
    private Integer packagingRating;
    private String deliveryRating;
    private Integer overallRating;  // 总体评分
    private double confidence;
    private List<String> evidence;

    public RatingResult() {
        this.evidence = new ArrayList<>();
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

    public Integer getOverallRating() {
        return overallRating;
    }

    public void setOverallRating(Integer overallRating) {
        this.overallRating = overallRating;
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

    @Override
    public String toString() {
        return String.format("Rating[overall=%s, product=%s, packaging=%s, delivery=%s, conf=%.2f]",
                overallRating != null ? overallRating : "UNKNOWN",
                productRating != null ? productRating : "UNKNOWN",
                packagingRating != null ? packagingRating : "UNKNOWN",
                deliveryRating != null ? deliveryRating : "UNKNOWN",
                confidence);
    }
}
