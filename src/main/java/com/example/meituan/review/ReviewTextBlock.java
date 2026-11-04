package com.example.meituan.review;

import com.example.meituan.ocr.OcrTextBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本块 - OCR 聚类的中间结果
 * 多个相邻的 OCR TextBox 合并为一个 TextBlock
 */
public class ReviewTextBlock {
    private List<OcrTextBox> textBoxes;
    private ReviewCard.BBox boundingBox;
    private String mergedText;
    private double avgConfidence;

    public ReviewTextBlock() {
        this.textBoxes = new ArrayList<>();
    }

    public void addTextBox(OcrTextBox box) {
        textBoxes.add(box);
    }

    public List<OcrTextBox> getTextBoxes() {
        return textBoxes;
    }

    public ReviewCard.BBox getBoundingBox() {
        return boundingBox;
    }

    public void setBoundingBox(ReviewCard.BBox boundingBox) {
        this.boundingBox = boundingBox;
    }

    public String getMergedText() {
        return mergedText;
    }

    public void setMergedText(String mergedText) {
        this.mergedText = mergedText;
    }

    public double getAvgConfidence() {
        return avgConfidence;
    }

    public void setAvgConfidence(double avgConfidence) {
        this.avgConfidence = avgConfidence;
    }

    /**
     * 计算包围所有 textBoxes 的最小边界框
     */
    public void calculateBoundingBox() {
        if (textBoxes.isEmpty()) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (OcrTextBox box : textBoxes) {
            minX = Math.min(minX, box.getX());
            minY = Math.min(minY, box.getY());
            maxX = Math.max(maxX, box.getX() + box.getWidth());
            maxY = Math.max(maxY, box.getY() + box.getHeight());
        }

        this.boundingBox = new ReviewCard.BBox(
                minX, minY, maxX - minX, maxY - minY
        );
    }

    /**
     * 合并文本内容
     */
    public void mergeText() {
        StringBuilder sb = new StringBuilder();
        double totalConf = 0;

        for (int i = 0; i < textBoxes.size(); i++) {
            OcrTextBox box = textBoxes.get(i);
            sb.append(box.getText());
            totalConf += box.getConfidence();

            // 判断是否需要添加空格或换行
            if (i < textBoxes.size() - 1) {
                OcrTextBox next = textBoxes.get(i + 1);
                // 如果下一个 box 的 Y 坐标差距较大，添加换行
                if (Math.abs(next.getY() - box.getY()) > box.getHeight() * 0.5) {
                    sb.append("\n");
                } else {
                    sb.append(" ");
                }
            }
        }

        this.mergedText = sb.toString().trim();
        this.avgConfidence = textBoxes.isEmpty() ? 0 : totalConf / textBoxes.size();
    }

    public int size() {
        return textBoxes.size();
    }

    @Override
    public String toString() {
        return String.format("TextBlock[boxes=%d, text='%s', conf=%.2f, bbox=%s]",
                textBoxes.size(),
                mergedText != null && mergedText.length() > 30 ?
                        mergedText.substring(0, 30) + "..." : mergedText,
                avgConfidence,
                boundingBox);
    }
}
