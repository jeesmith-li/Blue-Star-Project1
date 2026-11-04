package com.example.meituan.batch;

/**
 * 批量回复状态
 */
public enum BatchReplyState {
    IDLE("空闲"),
    FINDING_REVIEW("查找评论"),
    REVIEW_FOUND("评论已找到"),
    REPLY_ENTRY_FOUND("回复入口已找到"),
    REPLY_INPUT_FOUND("回复输入框已找到"),
    TEMPLATE_PASTED("模板已粘贴"),
    SUBMIT_FOUND("提交按钮已找到"),
    SUBMITTED("已提交"),
    VERIFYING("验证中"),
    REPLIED("已回复"),
    FINDING_NEXT("查找下一条"),
    COMPLETED("完成"),
    FAILED("失败");

    private final String description;

    BatchReplyState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
