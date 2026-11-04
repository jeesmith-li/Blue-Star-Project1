package com.example.meituan.vision;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面识别器
 * 根据 OCR 结果综合判断当前页面状态
 * 严格禁止根据屏幕坐标判断
 */
public class PageRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(PageRecognizer.class);

    private final TextMatcher textMatcher;

    public PageRecognizer() {
        this.textMatcher = new TextMatcher();
    }

    /**
     * 判断是否为顾客管理页面
     * 特征：存在"顾客管理"文本
     */
    public boolean isCustomerManagementPage(List<OcrTextBox> textBoxes) {
        double score = calculatePageScore(textBoxes, Map.of(
                "顾客管理", 1.0
        ));

        logger.debug("[PAGE] CustomerManagement score: {}", String.format("%.2f", score));
        return score > 0.5;
    }

    /**
     * 判断是否为顾客评价页面
     * 特征：存在"顾客评价"文本
     */
    public boolean isCustomerReviewPage(List<OcrTextBox> textBoxes) {
        double score = calculatePageScore(textBoxes, Map.of(
                "顾客评价", 1.0,
                "外卖", 0.3,
                "堂食", 0.3
        ));

        logger.debug("[PAGE] CustomerReview score: {}", String.format("%.2f", score));
        return score > 0.5;
    }

    /**
     * 判断是否为外卖评价列表页面
     * 特征：综合多个关键词判断
     */
    public boolean isReviewListPage(List<OcrTextBox> textBoxes) {
        // 多个关键词，权重不同
        Map<String, Double> keywords = new HashMap<>();
        keywords.put("外卖评价列表", 0.8);
        keywords.put("外卖评价", 0.6);
        keywords.put("未回复", 0.4);
        keywords.put("差评", 0.3);
        keywords.put("好评", 0.2);
        keywords.put("评价", 0.2);

        double score = calculatePageScore(textBoxes, keywords);

        logger.info("[PAGE] ReviewList score: {}", String.format("%.2f", score));
        return score > 0.8;
    }

    /**
     * 计算页面匹配分数
     * 根据关键词及其权重综合评分
     */
    private double calculatePageScore(List<OcrTextBox> textBoxes, Map<String, Double> keywords) {
        double totalScore = 0.0;

        for (Map.Entry<String, Double> entry : keywords.entrySet()) {
            String keyword = entry.getKey();
            double weight = entry.getValue();

            boolean found = textBoxes.stream()
                    .anyMatch(box -> textMatcher.contains(box.getText(), keyword));

            if (found) {
                totalScore += weight;
                logger.trace("[PAGE] Found keyword '{}' weight={}", keyword, String.format("%.2f", weight));
            }
        }

        return totalScore;
    }

    /**
     * 查找包含指定文本的文本框
     */
    public boolean containsText(List<OcrTextBox> textBoxes, String targetText) {
        return textBoxes.stream()
                .anyMatch(box -> textMatcher.contains(box.getText(), targetText));
    }
}
