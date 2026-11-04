package com.example.meituan.filter;

import com.example.meituan.ocr.OcrTextBox;
import com.example.meituan.vision.TextMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 筛选识别器
 * 从 OCR 结果中识别筛选控件
 * 核心原则：不使用固定坐标，只基于 OCR 和空间关系
 */
public class FilterRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(FilterRecognizer.class);

    private final TextMatcher textMatcher;

    public FilterRecognizer() {
        this.textMatcher = new TextMatcher();
    }

    /**
     * 查找「未回复」筛选项
     * 返回最佳候选
     */
    public Optional<FilterCandidate> findUnrepliedFilter(List<OcrTextBox> textBoxes) {
        logger.info("[FILTER] Searching for UNREPLIED filter...");

        List<FilterCandidate> candidates = new ArrayList<>();

        // 查找所有包含"未回复"的文本框
        for (OcrTextBox box : textBoxes) {
            if (textMatcher.contains(box.getText(), "未回复")) {
                double score = calculateFilterScore(box, textBoxes, ReviewFilter.UNREPLIED);
                String reason = buildReason(box, score);

                FilterCandidate candidate = new FilterCandidate(
                        ReviewFilter.UNREPLIED, box, score, reason
                );

                candidates.add(candidate);
                logger.debug("[FILTER] UNREPLIED candidate: {}", candidate);
            }
        }

        if (candidates.isEmpty()) {
            logger.warn("[FILTER] No UNREPLIED candidates found");
            return Optional.empty();
        }

        // 选择得分最高的候选
        FilterCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(FilterCandidate::getScore))
                .orElse(null);

        logger.info("[FILTER] Best UNREPLIED candidate: {}", best);
        return Optional.ofNullable(best);
    }

    /**
     * 查找「差评」筛选项
     * 可能是"差评"文字，也可能是"1星"/"2星"等
     */
    public Optional<FilterCandidate> findBadReviewFilter(List<OcrTextBox> textBoxes) {
        logger.info("[FILTER] Searching for BAD_REVIEW filter...");

        List<FilterCandidate> candidates = new ArrayList<>();

        // 查找所有可能的差评筛选文本
        for (OcrTextBox box : textBoxes) {
            String text = box.getText();

            // 匹配"差评"或"1-2星"相关文本
            if (textMatcher.contains(text, "差评") ||
                textMatcher.contains(text, "1-2星") ||
                textMatcher.contains(text, "差评（含1-2星")) {

                double score = calculateFilterScore(box, textBoxes, ReviewFilter.BAD_REVIEW);
                String reason = buildReason(box, score);

                FilterCandidate candidate = new FilterCandidate(
                        ReviewFilter.BAD_REVIEW, box, score, reason
                );

                candidates.add(candidate);
                logger.debug("[FILTER] BAD_REVIEW candidate: {}", candidate);
            }
        }

        if (candidates.isEmpty()) {
            logger.warn("[FILTER] No BAD_REVIEW candidates found");
            return Optional.empty();
        }

        // 选择得分最高的候选
        FilterCandidate best = candidates.stream()
                .max(Comparator.comparingDouble(FilterCandidate::getScore))
                .orElse(null);

        logger.info("[FILTER] Best BAD_REVIEW candidate: {}", best);
        return Optional.ofNullable(best);
    }

    /**
     * 计算筛选候选的得分
     * 综合考虑：OCR 置信度、文本匹配、空间关系
     */
    private double calculateFilterScore(OcrTextBox box, List<OcrTextBox> allBoxes, ReviewFilter filterType) {
        double score = 0.0;

        // 1. OCR 置信度权重
        score += box.getConfidence() * 1.0;

        // 2. 文本长度合理性（筛选项通常是短文本）
        int textLength = box.getText().length();
        if (textLength >= 2 && textLength <= 10) {
            score += 0.5;
        }

        // 3. bbox 尺寸合理性（筛选按钮通常有一定尺寸）
        if (box.getWidth() >= 30 && box.getWidth() <= 200 &&
            box.getHeight() >= 15 && box.getHeight() <= 60) {
            score += 0.3;
        }

        // 4. 空间关系：是否在同一水平线上有其他筛选相关文本
        int nearbyFilterCount = countNearbyFilterTexts(box, allBoxes);
        score += nearbyFilterCount * 0.2;

        return score;
    }

    /**
     * 统计附近的筛选相关文本数量
     * 筛选项通常在同一水平区域
     */
    private int countNearbyFilterTexts(OcrTextBox target, List<OcrTextBox> allBoxes) {
        int count = 0;
        int targetY = target.getY();
        int tolerance = 50; // Y 坐标容差

        String[] filterKeywords = {"全部", "未回复", "已回复", "差评", "好评", "评价", "星"};

        for (OcrTextBox box : allBoxes) {
            if (box == target) continue;

            // 检查是否在相近的 Y 坐标
            if (Math.abs(box.getY() - targetY) < tolerance) {
                // 检查是否包含筛选关键词
                for (String keyword : filterKeywords) {
                    if (textMatcher.contains(box.getText(), keyword)) {
                        count++;
                        break;
                    }
                }
            }
        }

        return count;
    }

    /**
     * 构建候选选择理由
     */
    private String buildReason(OcrTextBox box, double score) {
        return String.format("conf=%.2f, size=%dx%d, score=%.2f",
                box.getConfidence(), box.getWidth(), box.getHeight(), score);
    }
}
