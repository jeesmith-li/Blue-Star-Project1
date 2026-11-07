package com.example.meituan.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reviews", indexes = {
        @Index(name = "idx_reviews_shop_platform", columnList = "shop_id,platform_review_id"),
        @Index(name = "idx_reviews_shop_time", columnList = "shop_id,review_time")
})
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shopId;

    private String shopName;

    @Column(nullable = false)
    private String platformReviewId;

    @Column(nullable = false)
    private String reviewer;

    @Column(nullable = false)
    private Integer rating;

    @Column(length = 2000)
    private String content;

    @Column(nullable = false)
    private LocalDateTime reviewTime;

    @Column(nullable = false)
    private String replyStatus = "unreplied";

    @Column(length = 2000)
    private String replyContent;

    private LocalDateTime replyTime;

    /** 自动回复使用的模板标记：模板名(命中:关键词)，手动回复为 null */
    @Column(length = 200)
    private String replyTemplate;

    /**
     * 申诉状态：none(无) / pending(待申诉，等待用户上传图片) / submitted(已提交申诉)
     *          / approved(申诉通过) / rejected(申诉驳回)
     */
    @Column(nullable = false)
    private String appealStatus = "none";

    /** 申诉理由文本 */
    @Column(length = 2000)
    private String appealContent;

    /** 申诉凭证图片，多个用逗号分隔的相对路径 */
    @Column(length = 2000)
    private String appealImages;

    /** 自动申诉使用的模板标记：模板名(命中:关键词)，通用话术兜底为"通用话术" */
    @Column(length = 200)
    private String appealTemplate;

    /** 申诉提交时间 */
    private LocalDateTime appealTime;

    /** 申诉结果回查时间 */
    private LocalDateTime appealResultTime;

    /** 申诉结果说明 */
    @Column(length = 500)
    private String appealResultNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getPlatformReviewId() {
        return platformReviewId;
    }

    public void setPlatformReviewId(String platformReviewId) {
        this.platformReviewId = platformReviewId;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getReviewTime() {
        return reviewTime;
    }

    public void setReviewTime(LocalDateTime reviewTime) {
        this.reviewTime = reviewTime;
    }

    public String getReplyStatus() {
        return replyStatus;
    }

    public void setReplyStatus(String replyStatus) {
        this.replyStatus = replyStatus;
    }

    public String getReplyContent() {
        return replyContent;
    }

    public void setReplyContent(String replyContent) {
        this.replyContent = replyContent;
    }

    public LocalDateTime getReplyTime() {
        return replyTime;
    }

    public void setReplyTime(LocalDateTime replyTime) {
        this.replyTime = replyTime;
    }

    public String getReplyTemplate() { return replyTemplate; }
    public void setReplyTemplate(String replyTemplate) { this.replyTemplate = replyTemplate; }

    public String getAppealStatus() { return appealStatus; }
    public void setAppealStatus(String appealStatus) { this.appealStatus = appealStatus; }

    public String getAppealContent() { return appealContent; }
    public void setAppealContent(String appealContent) { this.appealContent = appealContent; }

    public String getAppealImages() { return appealImages; }
    public void setAppealImages(String appealImages) { this.appealImages = appealImages; }

    public String getAppealTemplate() { return appealTemplate; }
    public void setAppealTemplate(String appealTemplate) { this.appealTemplate = appealTemplate; }

    public LocalDateTime getAppealTime() { return appealTime; }
    public void setAppealTime(LocalDateTime appealTime) { this.appealTime = appealTime; }

    public LocalDateTime getAppealResultTime() { return appealResultTime; }
    public void setAppealResultTime(LocalDateTime appealResultTime) { this.appealResultTime = appealResultTime; }

    public String getAppealResultNote() { return appealResultNote; }
    public void setAppealResultNote(String appealResultNote) { this.appealResultNote = appealResultNote; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
