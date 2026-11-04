package com.example.meituan.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 负反馈（顾客通过美团反馈渠道的投诉，与差评申诉是独立数据域）
 */
@Entity
@Table(name = "negative_feedbacks")
public class NegativeFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shopId;

    private String shopName;

    /** 平台：meituan */
    @Column(nullable = false)
    private String platform = "meituan";

    /** 订单号 */
    @Column(length = 100)
    private String orderNo;

    /** 反馈渠道，如 商家圈/评价/客服 */
    @Column(length = 100)
    private String channelName;

    /** 问题分类 */
    @Column(length = 200)
    private String indicatorName;

    /** 反馈内容 */
    @Column(length = 2000)
    private String content;

    /** 反馈时间 */
    private LocalDateTime feedbackTime;

    /**
     * 处理状态：pending(审核中/待处理) / success(申诉成功) / fail(申诉失败/已驳回)
     */
    @Column(nullable = false)
    private String status = "pending";

    /** 订单详情等原始 JSON 数据 */
    @Column(length = 4000)
    private String orderData;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getIndicatorName() { return indicatorName; }
    public void setIndicatorName(String indicatorName) { this.indicatorName = indicatorName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getFeedbackTime() { return feedbackTime; }
    public void setFeedbackTime(LocalDateTime feedbackTime) { this.feedbackTime = feedbackTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOrderData() { return orderData; }
    public void setOrderData(String orderData) { this.orderData = orderData; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
