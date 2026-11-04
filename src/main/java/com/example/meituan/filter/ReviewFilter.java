package com.example.meituan.filter;

/**
 * 评价筛选类型
 */
public enum ReviewFilter {
    UNKNOWN("未知"),
    ALL("全部"),
    UNREPLIED("未回复"),
    REPLIED("已回复"),
    BAD_REVIEW("差评"),
    GOOD_REVIEW("好评");

    private final String description;

    ReviewFilter(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
