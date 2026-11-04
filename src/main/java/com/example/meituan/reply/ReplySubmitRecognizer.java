package com.example.meituan.reply;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 回复提交按钮识别器
 * 识别回复输入区域的最终提交按钮（黄底黑字）
 */
public class ReplySubmitRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(ReplySubmitRecognizer.class);

    /**
     * 识别提交按钮
     */
    public Optional<SubmitButtonCandidate> findSubmitButton(BufferedImage screenshot,
                                                           List<OcrTextBox> textBoxes,
                                                           ReplyInputTarget inputTarget) {
        logger.info("[SUBMIT] Searching for submit button near input");

        List<SubmitButtonCandidate> candidates = new ArrayList<>();

        // 查找所有「回复」候选
        for (OcrTextBox box : textBoxes) {
            String text = box.getText();

            if (text.equals("回复") || text.contains("回复")) {
                // 计算与输入框的空间关系
                double spatialScore = calculateSpatialScore(box, inputTarget);

                // 排除明显不是提交按钮的（太远）
                if (spatialScore < 0.1) {
                    continue;
                }

                // 检测黄色背景（提交按钮特征）
                double visualScore = detectYellowButton(screenshot, box);

                SubmitButtonCandidate candidate = new SubmitButtonCandidate(
                    box,
                    box.getConfidence(),
                    spatialScore,
                    visualScore
                );

                candidates.add(candidate);
                logger.debug("[SUBMIT] Candidate: {} - spatial={:.2f}, visual={:.2f}",
                    box.getText(), spatialScore, visualScore);
            }
        }

        if (candidates.isEmpty()) {
            logger.warn("[SUBMIT] No submit button candidates found");
            return Optional.empty();
        }

        // 选择最佳候选
        SubmitButtonCandidate best = candidates.stream()
            .max(Comparator.comparingDouble(SubmitButtonCandidate::getFinalScore))
            .orElse(null);

        if (best != null) {
            logger.info("[SUBMIT] Best candidate: {}", best);
        }

        return Optional.ofNullable(best);
    }

    /**
     * 计算空间关系得分
     */
    private double calculateSpatialScore(OcrTextBox buttonBox, ReplyInputTarget inputTarget) {
        OcrTextBox inputBox = inputTarget.getTextBox();

        // 计算距离
        int dx = Math.abs(buttonBox.getCenterX() - inputBox.getCenterX());
        int dy = Math.abs(buttonBox.getCenterY() - inputBox.getCenterY());
        double distance = Math.sqrt(dx * dx + dy * dy);

        // 提交按钮通常在输入框下方或右侧
        boolean isBelow = buttonBox.getY() > inputBox.getY();
        boolean isNear = distance < 500;

        double score = 0.0;

        if (isNear) {
            score += 0.5;
        }

        if (isBelow) {
            score += 0.3;
        }

        // 距离越近得分越高
        score += Math.max(0, 1.0 - distance / 1000.0) * 0.2;

        return Math.min(1.0, score);
    }

    /**
     * 检测黄色按钮（黄底黑字）
     */
    private double detectYellowButton(BufferedImage screenshot, OcrTextBox box) {
        try {
            int x = box.getX();
            int y = box.getY();
            int width = box.getWidth();
            int height = box.getHeight();

            // 采样按钮区域的颜色
            int sampleCount = 0;
            int yellowCount = 0;

            for (int dy = 0; dy < height; dy += Math.max(1, height / 5)) {
                for (int dx = 0; dx < width; dx += Math.max(1, width / 5)) {
                    int px = x + dx;
                    int py = y + dy;

                    if (px < screenshot.getWidth() && py < screenshot.getHeight()) {
                        int rgb = screenshot.getRGB(px, py);

                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;

                        // 黄色特征：R 高，G 高，B 低
                        if (r > 180 && g > 150 && b < 100) {
                            yellowCount++;
                        }

                        sampleCount++;
                    }
                }
            }

            double yellowRatio = sampleCount > 0 ? (double) yellowCount / sampleCount : 0.0;
            logger.debug("[SUBMIT] Yellow detection: ratio={:.2f}", yellowRatio);

            return yellowRatio;

        } catch (Exception e) {
            logger.warn("[SUBMIT] Yellow detection failed", e);
            return 0.0;
        }
    }

    /**
     * 提交按钮候选
     */
    public static class SubmitButtonCandidate {
        private final OcrTextBox textBox;
        private final double ocrConfidence;
        private final double spatialScore;
        private final double visualScore;

        public SubmitButtonCandidate(OcrTextBox textBox, double ocrConfidence,
                                     double spatialScore, double visualScore) {
            this.textBox = textBox;
            this.ocrConfidence = ocrConfidence;
            this.spatialScore = spatialScore;
            this.visualScore = visualScore;
        }

        public OcrTextBox getTextBox() {
            return textBox;
        }

        public int getCenterX() {
            return textBox.getCenterX();
        }

        public int getCenterY() {
            return textBox.getCenterY();
        }

        public double getFinalScore() {
            // 视觉特征（黄色按钮）权重最高
            return ocrConfidence * 0.2 + spatialScore * 0.3 + visualScore * 0.5;
        }

        @Override
        public String toString() {
            return String.format("SubmitButton[text='%s', ocr=%.2f, spatial=%.2f, visual=%.2f, final=%.2f, at=(%d,%d)]",
                textBox.getText(), ocrConfidence, spatialScore, visualScore, getFinalScore(),
                getCenterX(), getCenterY());
        }
    }
}
