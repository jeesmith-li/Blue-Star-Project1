package com.example.meituan.scroll;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 黄色回复按钮检测器
 * 只有在当前屏幕无法检测到黄色回复按钮时才触发滑屏
 */
public class YellowReplyButtonDetector {
    private static final Logger logger = LoggerFactory.getLogger(YellowReplyButtonDetector.class);

    /**
     * 检测黄色回复按钮
     */
    public List<YellowReplyButton> detectYellowReplyButtons(BufferedImage screenshot, List<OcrTextBox> ocrBoxes) {
        logger.info("[ReplyDetector] Detecting yellow reply buttons");

        List<YellowReplyButton> candidates = new ArrayList<>();

        // 1. 先找到所有包含"回复"的 OCR
        List<OcrTextBox> replyTextBoxes = new ArrayList<>();
        for (OcrTextBox box : ocrBoxes) {
            if (box.getText().equals("回复") || box.getText().contains("回复")) {
                replyTextBoxes.add(box);
            }
        }

        logger.debug("[ReplyDetector] Found {} OCR boxes containing '回复'", replyTextBoxes.size());

        // 2. 对每个"回复"OCR，检测其是否有黄色背景
        for (OcrTextBox box : replyTextBoxes) {
            double yellowScore = detectYellowBackground(screenshot, box);

            if (yellowScore > 0.3) { // 黄色阈值
                YellowReplyButton button = new YellowReplyButton(
                    box,
                    yellowScore,
                    calculateConfidence(box, yellowScore)
                );
                candidates.add(button);
                logger.debug("[ReplyDetector] Yellow candidate: {}", button);
            }
        }

        logger.info("[ReplyDetector] screen=current, yellow_candidates={}, reply_candidates={}",
            candidates.size(), replyTextBoxes.size());

        // 3. 过滤并返回可靠的黄色回复按钮
        List<YellowReplyButton> reliable = new ArrayList<>();
        for (YellowReplyButton btn : candidates) {
            if (btn.getConfidence() > 0.5) {
                reliable.add(btn);
                logger.info("[ReplyDetector] FOUND reply button bbox=({},{},{}x{}), center=({},{}), yellowScore={:.2f}, ocrText='{}', confidence={:.2f}",
                    btn.getX(), btn.getY(), btn.getWidth(), btn.getHeight(),
                    btn.getCenterX(), btn.getCenterY(),
                    btn.getYellowScore(), btn.getText(), btn.getConfidence());
            }
        }

        if (reliable.isEmpty()) {
            logger.warn("[ReplyDetector] NO reliable yellow reply button");
        }

        return reliable;
    }

    /**
     * 检测黄色背景
     */
    private double detectYellowBackground(BufferedImage screenshot, OcrTextBox box) {
        try {
            int x = box.getX();
            int y = box.getY();
            int width = box.getWidth();
            int height = box.getHeight();

            int sampleCount = 0;
            int yellowCount = 0;

            // 采样按钮区域
            for (int dy = 0; dy < height; dy += Math.max(1, height / 5)) {
                for (int dx = 0; dx < width; dx += Math.max(1, width / 5)) {
                    int px = x + dx;
                    int py = y + dy;

                    if (px >= 0 && px < screenshot.getWidth() && py >= 0 && py < screenshot.getHeight()) {
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

            return sampleCount > 0 ? (double) yellowCount / sampleCount : 0.0;

        } catch (Exception e) {
            logger.warn("[ReplyDetector] Yellow detection failed for box", e);
            return 0.0;
        }
    }

    /**
     * 计算置信度
     */
    private double calculateConfidence(OcrTextBox box, double yellowScore) {
        double conf = 0.0;

        // OCR 置信度
        conf += box.getConfidence() * 0.3;

        // 黄色得分
        conf += yellowScore * 0.5;

        // 文本匹配
        if (box.getText().equals("回复")) {
            conf += 0.2;
        }

        return Math.min(1.0, conf);
    }

    /**
     * 黄色回复按钮
     */
    public static class YellowReplyButton {
        private final OcrTextBox textBox;
        private final double yellowScore;
        private final double confidence;

        public YellowReplyButton(OcrTextBox textBox, double yellowScore, double confidence) {
            this.textBox = textBox;
            this.yellowScore = yellowScore;
            this.confidence = confidence;
        }

        public OcrTextBox getTextBox() {
            return textBox;
        }

        public int getX() {
            return textBox.getX();
        }

        public int getY() {
            return textBox.getY();
        }

        public int getWidth() {
            return textBox.getWidth();
        }

        public int getHeight() {
            return textBox.getHeight();
        }

        public int getCenterX() {
            return textBox.getCenterX();
        }

        public int getCenterY() {
            return textBox.getCenterY();
        }

        public String getText() {
            return textBox.getText();
        }

        public double getYellowScore() {
            return yellowScore;
        }

        public double getConfidence() {
            return confidence;
        }

        @Override
        public String toString() {
            return String.format("YellowButton[text='%s', yellow=%.2f, conf=%.2f, at=(%d,%d)]",
                getText(), yellowScore, confidence, getCenterX(), getCenterY());
        }
    }
}
