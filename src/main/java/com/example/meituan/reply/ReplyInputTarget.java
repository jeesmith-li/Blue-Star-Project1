package com.example.meituan.reply;

import com.example.meituan.ocr.OcrTextBox;

/**
 * 回复输入目标
 */
public class ReplyInputTarget {
    private OcrTextBox textBox;
    private int centerX;
    private int centerY;
    private double confidence;
    private String evidence;

    public ReplyInputTarget(OcrTextBox textBox, double confidence, String evidence) {
        this.textBox = textBox;
        this.centerX = textBox.getCenterX();
        this.centerY = textBox.getCenterY();
        this.confidence = confidence;
        this.evidence = evidence;
    }

    public OcrTextBox getTextBox() {
        return textBox;
    }

    public int getCenterX() {
        return centerX;
    }

    public int getCenterY() {
        return centerY;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getEvidence() {
        return evidence;
    }

    @Override
    public String toString() {
        return String.format("ReplyInputTarget[bbox=(%d,%d,%dx%d), center=(%d,%d), conf=%.2f, evidence=%s]",
                textBox.getX(), textBox.getY(), textBox.getWidth(), textBox.getHeight(),
                centerX, centerY, confidence, evidence);
    }
}
