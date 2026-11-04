package com.example.meituan.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reply_templates")
public class ReplyTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String content;

    /** 模板类型：reply(回复模板，按星级档位分好/中/差评) / appeal(差评申诉模板) / food_appeal(食安申诉模板) */
    @Column(nullable = false)
    private String type = "reply";

    /**
     * 星级档位（仅 reply 模板用，对标西瓜好/中/差评三套文案）：
     * good(4-5星好评) / middle(3星中评) / bad(1-2星差评)，null=通用（该档无模板时回退）
     */
    @Column(length = 10)
    private String starLevel;

    /** 申诉模板绑定的凭证图片，多个用逗号分隔的相对路径 */
    @Column(length = 2000)
    private String images;

    /**
     * 触发关键词（回复/申诉模板通用），多个用逗号分隔，如：难吃,味道差,太辣。
     * 非空时自动回复/申诉优先按评价内容包含关键词匹配本模板（命中关键词数最多者优先）；
     * 用户存在关键词模板但差评未命中任何关键词时，留待人工处理，不再走星级/通用兜底。
     */
    @Column(length = 500)
    private String keywords;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String status = "active";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStarLevel() { return starLevel; }
    public void setStarLevel(String starLevel) { this.starLevel = starLevel; }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
