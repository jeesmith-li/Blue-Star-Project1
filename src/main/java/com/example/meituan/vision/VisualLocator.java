package com.example.meituan.vision;

import com.example.meituan.ocr.OcrResult;
import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 视觉定位器
 * 根据 OCR 结果实时定位目标元素
 * 核心原则：永远不知道元素在哪里，只能通过当前截图实时识别
 */
public class VisualLocator {
    private static final Logger logger = LoggerFactory.getLogger(VisualLocator.class);

    private final TextMatcher textMatcher;
    private final double confidenceThreshold;

    public VisualLocator(double confidenceThreshold) {
        this.textMatcher = new TextMatcher();
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * 在 OCR 结果中查找目标文本
     * 返回最佳匹配的文本框
     */
    public Optional<OcrTextBox> findText(OcrResult ocrResult, String targetText) {
        if (!ocrResult.isSuccess()) {
            logger.warn("[LOCATOR] OCR failed: {}", ocrResult.getErrorMessage());
            return Optional.empty();
        }

        List<OcrTextBox> textBoxes = ocrResult.getTextBoxes();
        logger.debug("[LOCATOR] Searching for '{}' in {} text boxes", targetText, textBoxes.size());

        OcrTextBox bestMatch = null;
        double bestScore = 0.0;

        for (OcrTextBox box : textBoxes) {
            // 过滤低置信度
            if (box.getConfidence() < confidenceThreshold) {
                continue;
            }

            double score = textMatcher.scoreMatch(box.getText(), targetText, box.getConfidence());

            if (score > bestScore) {
                bestScore = score;
                bestMatch = box;
            }

            logger.trace("[LOCATOR] Candidate='{}' confidence={} score={}",
                    box.getText(), String.format("%.2f", box.getConfidence()), String.format("%.2f", score));
        }

        if (bestMatch != null && bestScore > 0.5) {
            logger.info("[LOCATOR] Found '{}' -> '{}' confidence={} score={} bbox=({},{},{}x{})",
                    targetText, bestMatch.getText(), String.format("%.2f", bestMatch.getConfidence()),
                    String.format("%.2f", bestScore), bestMatch.getX(), bestMatch.getY(),
                    bestMatch.getWidth(), bestMatch.getHeight());
            return Optional.of(bestMatch);
        }

        logger.warn("[LOCATOR] Not found: '{}'", targetText);
        return Optional.empty();
    }

    /**
     * 查找包含目标文本的所有文本框
     */
    public List<OcrTextBox> findAllContaining(OcrResult ocrResult, String targetText) {
        return ocrResult.getTextBoxes().stream()
                .filter(box -> box.getConfidence() >= confidenceThreshold)
                .filter(box -> textMatcher.contains(box.getText(), targetText))
                .toList();
    }
}
