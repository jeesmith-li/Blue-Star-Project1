package com.example.meituan.review;

/**
 * 回复状态枚举
 */
public enum ReplyState {
    UNKNOWN("未知"),
    REPLIED("已回复"),
    UNREPLIED("未回复");

    private final String description;

    ReplyState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
