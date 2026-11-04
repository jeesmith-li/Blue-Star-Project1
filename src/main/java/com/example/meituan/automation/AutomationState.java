package com.example.meituan.automation;

/**
 * 自动化状态枚举
 */
public enum AutomationState {
    IDLE("空闲"),
    CUSTOMER_MANAGEMENT("顾客管理页面"),
    CUSTOMER_REVIEW("顾客评价页面"),
    REVIEW_LIST("外卖评价列表"),
    FILTER_UNREPLIED("筛选未回复"),
    VERIFY_UNREPLIED("验证未回复"),
    FILTER_BAD_REVIEW("筛选差评"),
    VERIFY_BAD_REVIEW("验证差评"),
    FILTERED_REVIEW_LIST("已筛选评价列表"),
    COMPLETED("完成"),
    FAILED("失败");

    private final String description;

    AutomationState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
