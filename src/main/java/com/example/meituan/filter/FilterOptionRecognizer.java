package com.example.meituan.filter;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 筛选选项识别器
 * 从 OCR 结果中识别筛选器选项位置
 */
public class FilterOptionRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(FilterOptionRecognizer.class);

    /**
     * 查找筛选选项
     */
    public Optional<FilterOption> findFilterOption(List<OcrTextBox> textBoxes, String targetText) {
        logger.info("[FILTER] Searching for filter option: '{}'", targetText);

        // 1. 查找所有包含目标文本的候选
        List<FilterOption> candidates = new ArrayList<>();

        for (OcrTextBox box : textBoxes) {
            String text = box.getText();

            // 完全匹配
            if (text.equals(targetText)) {
                candidates.add(new FilterOption(box, 1.0, "exact match"));
                continue;
            }

            // 包含匹配
            if (text.contains(targetText)) {
                double score = (double) targetText.length() / text.length();
                candidates.add(new FilterOption(box, score, "contains match"));
            }
        }

        // 2. 尝试合并相邻的 OCR boxes（处理拆分情况）
        if (candidates.isEmpty()) {
            candidates.addAll(findMergedMatches(textBoxes, targetText));
        }

        if (candidates.isEmpty()) {
            logger.warn("[FILTER] No candidates found for '{}'", targetText);
            return Optional.empty();
        }

        // 3. 选择最佳候选
        FilterOption best = candidates.stream()
                .max(Comparator.comparingDouble(FilterOption::getScore))
                .orElse(null);

        if (best != null) {
            logger.info("[FILTER] Found best candidate: {}", best);
        }

        return Optional.ofNullable(best);
    }

    /**
     * 查找合并匹配（处理 OCR 拆分情况）
     */
    private List<FilterOption> findMergedMatches(List<OcrTextBox> textBoxes, String targetText) {
        List<FilterOption> merged = new ArrayList<>();

        // 计算动态阈值
        int medianHeight = calculateMedianHeight(textBoxes);
        int verticalThreshold = medianHeight * 2;

        for (int i = 0; i < textBoxes.size(); i++) {
            OcrTextBox box1 = textBoxes.get(i);
            String text1 = box1.getText();

            // 第一个 box 必须包含目标文本的开头部分
            if (!targetText.startsWith(text1) && !text1.contains(targetText.substring(0, Math.min(2, targetText.length())))) {
                continue;
            }

            // 尝试合并相邻的 boxes
            StringBuilder combined = new StringBuilder(text1);
            List<OcrTextBox> mergedBoxes = new ArrayList<>();
            mergedBoxes.add(box1);

            for (int j = i + 1; j < textBoxes.size(); j++) {
                OcrTextBox box2 = textBoxes.get(j);

                // 检查垂直距离
                if (Math.abs(box2.getY() - box1.getY()) > verticalThreshold) {
                    break;
                }

                // 检查水平连续性
                if (box2.getX() > box1.getX() + box1.getWidth() + medianHeight * 2) {
                    break;
                }

                combined.append(box2.getText());
                mergedBoxes.add(box2);

                // 检查是否匹配目标
                if (combined.toString().contains(targetText)) {
                    FilterOption option = createMergedOption(mergedBoxes, targetText);
                    merged.add(option);
                    logger.debug("[FILTER] Found merged match: {}", option);
                    break;
                }
            }
        }

        return merged;
    }

    /**
     * 创建合并选项
     */
    private FilterOption createMergedOption(List<OcrTextBox> boxes, String targetText) {
        // 计算合并的 bbox
        int minX = boxes.stream().mapToInt(OcrTextBox::getX).min().orElse(0);
        int minY = boxes.stream().mapToInt(OcrTextBox::getY).min().orElse(0);
        int maxX = boxes.stream().mapToInt(b -> b.getX() + b.getWidth()).max().orElse(0);
        int maxY = boxes.stream().mapToInt(b -> b.getY() + b.getHeight()).max().orElse(0);

        double avgConfidence = boxes.stream().mapToDouble(OcrTextBox::getConfidence).average().orElse(0.0);

        OcrTextBox merged = new OcrTextBox(
                String.join("", boxes.stream().map(OcrTextBox::getText).collect(Collectors.toList())),
                avgConfidence,
                minX, minY, maxX - minX, maxY - minY
        );

        return new FilterOption(merged, 0.8, "merged match");
    }

    /**
     * 计算中位数高度
     */
    private int calculateMedianHeight(List<OcrTextBox> textBoxes) {
        List<Integer> heights = textBoxes.stream()
                .map(OcrTextBox::getHeight)
                .filter(h -> h > 5 && h < 100)
                .sorted()
                .collect(Collectors.toList());

        return heights.isEmpty() ? 20 : heights.get(heights.size() / 2);
    }

    /**
     * 筛选选项
     */
    public static class FilterOption {
        private final OcrTextBox textBox;
        private final double score;
        private final String reason;

        public FilterOption(OcrTextBox textBox, double score, String reason) {
            this.textBox = textBox;
            this.score = score;
            this.reason = reason;
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
            return String.format("FilterOption[text='%s', score=%.2f, bbox=(%d,%d,%dx%d), center=(%d,%d), reason=%s]",
                    textBox.getText(), score,
                    textBox.getX(), textBox.getY(), textBox.getWidth(), textBox.getHeight(),
                    getCenterX(), getCenterY(), reason);
        }
    }
}
