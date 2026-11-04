package com.example.meituan.web.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 客户端审计日志：记录甲方使用系统时的一切行为与问题
 * type: user_action(用户操作) / api_error(接口报错) / slow_api(慢请求) /
 *       js_error(前端报错) / page_view(页面访问) / automation(自动化执行) / system(系统事件)
 * level: INFO / WARN / ERROR
 */
@Entity
@Table(name = "client_logs", indexes = {
        @Index(name = "idx_client_logs_ts", columnList = "ts"),
        @Index(name = "idx_client_logs_user", columnList = "username")
})
public class ClientLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发生时间 */
    @Column(nullable = false)
    private LocalDateTime ts = LocalDateTime.now();

    /** 操作用户（未登录时为 anonymous） */
    private String username;

    /** INFO / WARN / ERROR */
    @Column(nullable = false, length = 10)
    private String level = "INFO";

    /** 日志类型，见类注释 */
    @Column(nullable = false, length = 30)
    private String type;

    /** 动作名，如 login / bind_shop / fetch_reviews / reply / appeal_submit */
    @Column(length = 60)
    private String action;

    /** 结果：success / fail / slow /空 */
    @Column(length = 20)
    private String result;

    /** 详情（错误消息、参数摘要等，可为 JSON 文本） */
    @Column(length = 2000)
    private String detail;

    /** 页面路由，如 /shops */
    @Column(length = 100)
    private String page;

    /** 请求方法+路径，如 POST /api/shops */
    @Column(length = 200)
    private String endpoint;

    /** 耗时毫秒 */
    private Long durationMs;

    /** HTTP 状态码 */
    private Integer httpStatus;

    /** 浏览器 User-Agent */
    @Column(length = 400)
    private String userAgent;

    /** 屏幕分辨率 */
    @Column(length = 30)
    private String screen;

    /** 客户端 IP */
    @Column(length = 50)
    private String ip;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getTs() { return ts; }
    public void setTs(LocalDateTime ts) { this.ts = ts; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getScreen() { return screen; }
    public void setScreen(String screen) { this.screen = screen; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
}
