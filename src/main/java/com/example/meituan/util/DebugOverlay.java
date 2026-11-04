package com.example.meituan.util;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 调试工具 - 生成 Debug Overlay
 * 在截图上绘制 OCR bounding boxes
 */
public class DebugOverlay {
    private static final Logger logger = LoggerFactory.getLogger(DebugOverlay.class);

    /**
     * 在图像上绘制 OCR 文本框
     */
    public static BufferedImage drawTextBoxes(BufferedImage original, List<OcrTextBox> textBoxes) {
        // 创建副本以避免修改原图
        BufferedImage overlay = new BufferedImage(
                original.getWidth(),
                original.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g2d = overlay.createGraphics();

        // 绘制原图
        g2d.drawImage(original, 0, 0, null);

        // 设置绘制样式
        g2d.setColor(Color.GREEN);
        g2d.setStroke(new BasicStroke(2));

        Font font = new Font("Arial", Font.PLAIN, 12);
        g2d.setFont(font);

        // 绘制每个文本框
        for (OcrTextBox box : textBoxes) {
            // 绘制边界框
            g2d.drawRect(box.getX(), box.getY(), box.getWidth(), box.getHeight());

            // 绘制文本和置信度
            String label = String.format("[%s] %.2f", box.getText(), box.getConfidence());

            // 文本背景
            g2d.setColor(new Color(0, 255, 0, 180));
            g2d.fillRect(box.getX(), box.getY() - 15, label.length() * 7, 15);

            // 文本
            g2d.setColor(Color.BLACK);
            g2d.drawString(label, box.getX() + 2, box.getY() - 3);

            // 恢复边框颜色
            g2d.setColor(Color.GREEN);
        }

        g2d.dispose();
        return overlay;
    }

    /**
     * 保存带 overlay 的调试图像
     */
    public static void saveDebugImage(BufferedImage original, List<OcrTextBox> textBoxes,
                                      String outputPath) {
        try {
            BufferedImage overlay = drawTextBoxes(original, textBoxes);
            File outputFile = new File(outputPath);
            ImageIO.write(overlay, "PNG", outputFile);
            logger.debug("[DEBUG] Saved overlay image: {}", outputPath);
        } catch (IOException e) {
            logger.warn("[DEBUG] Failed to save overlay image: {}", outputPath, e);
        }
    }
}
