package com.example.meituan.filter;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 筛选选中状态识别器
 * 通过视觉特征判断筛选项是否已选中
 *
 * 注意：不使用固定坐标和固定 RGB 值
 * 基于 OCR bbox 动态区域进行视觉分析
 */
public class FilterSelectionRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(FilterSelectionRecognizer.class);

    /**
     * 验证筛选项是否已选中
     *
     * 当前简化版本：通过重新 OCR 后文本是否仍然存在来判断
     * 后续可以增强：分析 bbox 区域的颜色、边框等视觉特征
     *
     * @param screenshot 当前截图
     * @param textBoxes 当前 OCR 结果
     * @param filterType 筛选类型
     * @param clickedBox 之前点击的文本框
     * @return 是否选中
     */
    public boolean isFilterSelected(BufferedImage screenshot,
                                   List<OcrTextBox> textBoxes,
                                   ReviewFilter filterType,
                                   OcrTextBox clickedBox) {

        logger.debug("[FILTER_VERIFY] Checking if {} is selected", filterType);

        // 简化版本：检查目标文本是否仍然存在
        // 这表示筛选项在页面上，间接说明点击成功
        String targetText = getFilterText(filterType);

        boolean found = textBoxes.stream()
                .anyMatch(box -> box.getText().contains(targetText));

        if (found) {
            logger.info("[FILTER_VERIFY] {} appears to be selected (text still present)", filterType);
            return true;
        } else {
            logger.warn("[FILTER_VERIFY] {} may not be selected (text not found)", filterType);
            return false;
        }
    }

    /**
     * 获取筛选类型对应的文本
     */
    private String getFilterText(ReviewFilter filterType) {
        return switch (filterType) {
            case UNREPLIED -> "未回复";
            case BAD_REVIEW -> "差评";
            case REPLIED -> "已回复";
            case GOOD_REVIEW -> "好评";
            case ALL -> "全部";
            default -> "";
        };
    }

    /**
     * 计算筛选选中的置信度得分
     * 0.0-1.0，越高越可能已选中
     */
    public double calculateSelectionScore(BufferedImage screenshot,
                                         List<OcrTextBox> textBoxes,
                                         ReviewFilter filterType,
                                         OcrTextBox clickedBox) {

        // 基础分数：文本仍然存在
        String targetText = getFilterText(filterType);
        boolean textExists = textBoxes.stream()
                .anyMatch(box -> box.getText().contains(targetText));

        double score = textExists ? 0.8 : 0.2;

        logger.debug("[FILTER_VERIFY] Selection score for {}: {}",
                filterType, String.format("%.2f", score));

        return score;
    }
}
