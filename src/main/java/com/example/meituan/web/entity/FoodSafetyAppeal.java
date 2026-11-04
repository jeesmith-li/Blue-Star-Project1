package com.example.meituan.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 食安险申诉记录（独立数据域，与差评申诉/负反馈隔离）
 * 美团侧状态枚举：10=可申诉(未申诉) 20=申诉中 30=申诉成功 3=申诉驳回 40=申诉失败 50=申诉超时 41=可再次申诉
 */
@Entity
@Table(name = "food_safety_appeals")
public class FoodSafetyAppeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long shopId;

    private String shopName;

    /** 美团订单号（唯一，防重复申诉） */
    @Column(nullable = false, unique = true)
    private String orderNo;

    /** 订单时间，如 10-06 */
    @Column(length = 50)
    private String orderTime;

    /** 反馈渠道，如 订单评价 */
    @Column(length = 100)
    private String feedbackChannel;

    /** 投诉类型，如 有异味/看着不新鲜 */
    @Column(length = 200)
    private String feedbackType;

    /** 顾客反馈原文 */
    @Column(length = 2000)
    private String content;

    /** 美团侧申诉状态（数字枚举，见类注释） */
    private Integer mtStatus;

    /** 我方提交的申诉理由 */
    @Column(length = 2000)
    private String appealReason;

    /** 使用的模板标记：模板名(命中:关键词)，手动输入为"手动"，无来源为 null */
    @Column(length = 200)
    private String appealTemplate;

    /** 是否上传了凭证图片（图片上传暂未实现，预留字段） */
    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Boolean hasUploadImages = false;

    /** 美团审核意见（回查时更新） */
    @Column(length = 500)
    private String auditRemark;

    /** 申诉提交时间 */
    private LocalDateTime appealTime;

    /** 最近一次状态回查时间 */
    private LocalDateTime lastCheckTime;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getOrderTime() { return orderTime; }
    public void setOrderTime(String orderTime) { this.orderTime = orderTime; }

    public String getFeedbackChannel() { return feedbackChannel; }
    public void setFeedbackChannel(String feedbackChannel) { this.feedbackChannel = feedbackChannel; }

    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getMtStatus() { return mtStatus; }
    public void setMtStatus(Integer mtStatus) { this.mtStatus = mtStatus; }

    public String getAppealReason() { return appealReason; }
    public void setAppealReason(String appealReason) { this.appealReason = appealReason; }

    public String getAppealTemplate() { return appealTemplate; }
    public void setAppealTemplate(String appealTemplate) { this.appealTemplate = appealTemplate; }

    public Boolean getHasUploadImages() { return hasUploadImages; }
    public void setHasUploadImages(Boolean hasUploadImages) { this.hasUploadImages = hasUploadImages; }

    public String getAuditRemark() { return auditRemark; }
    public void setAuditRemark(String auditRemark) { this.auditRemark = auditRemark; }

    public LocalDateTime getAppealTime() { return appealTime; }
    public void setAppealTime(LocalDateTime appealTime) { this.appealTime = appealTime; }

    public LocalDateTime getLastCheckTime() { return lastCheckTime; }
    public void setLastCheckTime(LocalDateTime lastCheckTime) { this.lastCheckTime = lastCheckTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
