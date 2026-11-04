package com.example.meituan.filter;

import com.example.meituan.ocr.OcrTextBox;

/**
 * 筛选候选项
 * 包含 OCR 文本框和评分信息
 */
public class FilterCandidate {
    private final ReviewFilter type;
    private final OcrTextBox textBox;
    private final double score;
    private final String reason;

    public FilterCandidate(ReviewFilter type, OcrTextBox textBox, double score, String reason) {
        this.type = type;
        this.textBox = textBox;
        this.score = score;
        this.reason = reason;
    }

    public ReviewFilter getType() {
        return type;
    }

    public OcrTextBox getTextBox() {
        return textBox;
    }

    public double getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }

    public int getCenterX() {
        return textBox.getCenterX();
    }

    public int getCenterY() {
        return textBox.getCenterY();
    }

    @Override
    public String toString() {
        return String.format("FilterCandidate[type=%s, text='%s', score=%.2f, bbox=(%d,%d,%dx%d), center=(%d,%d), reason=%s]",
                type, textBox.getText(), score,
                textBox.getX(), textBox.getY(), textBox.getWidth(), textBox.getHeight(),
                getCenterX(), getCenterY(), reason);
    }
}
