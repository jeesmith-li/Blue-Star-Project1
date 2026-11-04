package com.example.meituan.filter;

/**
 * 差评状态枚举
 */
public enum BadReviewState {
    BAD_REVIEW("差评"),
    NOT_BAD_REVIEW("非差评"),
    UNKNOWN("未知");

    private final String description;

    BadReviewState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
