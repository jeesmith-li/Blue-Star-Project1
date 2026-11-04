package com.example.meituan.web.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "shops")
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String meituanAccount;

    @Column(nullable = false)
    private String shopType;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String status = "active";

    private String lastSyncTime;

    @Column(nullable = false)
    private Integer todayBadReviews = 0;

    @Column(nullable = false)
    private Integer unrepliedCount = 0;

    /**
     * 自动申诉开关：开启后系统自动为符合条件的差评提交申诉
     */
    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Boolean appealAutoEnabled = false;

    /**
     * 自动申诉的星级范围，逗号分隔，如 "1,2"
     */
    @Column(nullable = false)
    private String appealStars = "1,2";

    /**
     * 自动申诉的评价范围：today(今天) / yesterday(昨天)
     */
    @Column(nullable = false)
    private String appealRange = "today";

    /**
     * 自动回复开关：开启后抓取评价时自动按好/中/差评模板回复未回复评价（对标西瓜全自动模式）
     */
    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Boolean autoReplyEnabled = false;

    /**
     * 食安险自动申诉开关：开启后系统自动为待申诉的食安负反馈单提交申诉
     */
    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Boolean foodSafetyAutoAppeal = false;

    /** 美团门店ID(wmPoiId)，食安抓取时自动探测回填，申诉/回查直接复用避免重复探测 */
    private String poiId;

    /** 执行顺序：自动化按该值升序逐店处理（为空时按ID顺序） */
    private Integer sortOrder;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMeituanAccount() {
        return meituanAccount;
    }

    public void setMeituanAccount(String meituanAccount) {
        this.meituanAccount = meituanAccount;
    }

    public String getShopType() {
        return shopType;
    }

    public void setShopType(String shopType) {
        this.shopType = shopType;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(String lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public Integer getTodayBadReviews() {
        return todayBadReviews;
    }

    public void setTodayBadReviews(Integer todayBadReviews) {
        this.todayBadReviews = todayBadReviews;
    }

    public Integer getUnrepliedCount() {
        return unrepliedCount;
    }

    public void setUnrepliedCount(Integer unrepliedCount) {
        this.unrepliedCount = unrepliedCount;
    }

    public Boolean getAppealAutoEnabled() {
        return appealAutoEnabled;
    }

    public void setAppealAutoEnabled(Boolean appealAutoEnabled) {
        this.appealAutoEnabled = appealAutoEnabled;
    }

    public String getAppealStars() {
        return appealStars;
    }

    public void setAppealStars(String appealStars) {
        this.appealStars = appealStars;
    }

    public String getAppealRange() {
        return appealRange;
    }

    public void setAppealRange(String appealRange) {
        this.appealRange = appealRange;
    }

    public Boolean getAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    public void setAutoReplyEnabled(Boolean autoReplyEnabled) {
        this.autoReplyEnabled = autoReplyEnabled;
    }

    public Boolean getFoodSafetyAutoAppeal() {
        return foodSafetyAutoAppeal;
    }

    public void setFoodSafetyAutoAppeal(Boolean foodSafetyAutoAppeal) {
        this.foodSafetyAutoAppeal = foodSafetyAutoAppeal;
    }

    public String getPoiId() {
        return poiId;
    }

    public void setPoiId(String poiId) {
        this.poiId = poiId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
