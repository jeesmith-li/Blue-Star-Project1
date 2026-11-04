package com.example.meituan.reply;

/**
 * 回复提交状态
 */
public enum ReplySubmitState {
    IDLE("空闲"),
    TARGET_REVIEW("目标评论已确定"),
    REPLY_INPUT_READY("回复输入准备完成"),
    TEMPLATE_VERIFIED("模板已验证"),
    SUBMIT_BUTTON_FOUND("提交按钮已找到"),
    SUBMIT_CLICKED("提交按钮已点击"),
    VERIFYING("验证中"),
    REPLIED("已回复"),
    FAILED("失败");

    private final String description;

    ReplySubmitState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
