package com.example.meituan.ocr;

/**
 * OCR 识别的文本框
 * 包含文本内容、置信度和边界框坐标
 */
public class OcrTextBox {
    private final String text;
    private final double confidence;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    public OcrTextBox(String text, double confidence, int x, int y, int width, int height) {
        this.text = text;
        this.confidence = confidence;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public String getText() {
        return text;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * 获取边界框中心点 X 坐标
     * 用于鼠标点击定位
     */
    public int getCenterX() {
        return x + width / 2;
    }

    /**
     * 获取边界框中心点 Y 坐标
     * 用于鼠标点击定位
     */
    public int getCenterY() {
        return y + height / 2;
    }

    @Override
    public String toString() {
        return String.format("OcrTextBox[text='%s', confidence=%.2f, bbox=(%d,%d,%d,%d), center=(%d,%d)]",
                text, confidence, x, y, width, height, getCenterX(), getCenterY());
    }
}
