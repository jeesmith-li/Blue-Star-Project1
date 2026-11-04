package com.example.meituan.web.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 自动化执行配置（全局单行）：
 * 用户可在自动化页配置每日抓取的执行间隔（几小时或几天），并查看最近一次执行状态。
 */
@Entity
@Table(name = "automation_config")
public class AutomationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 自动抓取总开关 */
    @Column(nullable = false)
    private Boolean dailyCaptureEnabled = true;

    /** 执行间隔（小时），前端支持按天配置（天×24），最小 1 小时，最大 720 小时（30天） */
    @Column(nullable = false)
    private Integer intervalHours = 24;

    /** 最近一次执行时间（手动/定时执行均更新，含跳过） */
    private LocalDateTime lastRunTime;

    /** 最近一次执行结果：success / partial / skip / never */
    @Column(length = 20)
    private String lastRunResult = "never";

    /** 最近一次执行摘要说明 */
    @Column(length = 2000)
    private String lastRunDetail;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Boolean getDailyCaptureEnabled() { return dailyCaptureEnabled; }
    public void setDailyCaptureEnabled(Boolean dailyCaptureEnabled) { this.dailyCaptureEnabled = dailyCaptureEnabled; }

    public Integer getIntervalHours() { return intervalHours; }
    public void setIntervalHours(Integer intervalHours) { this.intervalHours = intervalHours; }

    public LocalDateTime getLastRunTime() { return lastRunTime; }
    public void setLastRunTime(LocalDateTime lastRunTime) { this.lastRunTime = lastRunTime; }

    public String getLastRunResult() { return lastRunResult; }
    public void setLastRunResult(String lastRunResult) { this.lastRunResult = lastRunResult; }

    public String getLastRunDetail() { return lastRunDetail; }
    public void setLastRunDetail(String lastRunDetail) { this.lastRunDetail = lastRunDetail; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
