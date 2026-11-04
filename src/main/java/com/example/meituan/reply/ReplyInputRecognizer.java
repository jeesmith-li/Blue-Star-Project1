package com.example.meituan.reply;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

/**
 * 回复输入框识别器
 * 基于 OCR + OpenCV 动态识别回复输入框
 */
public class ReplyInputRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(ReplyInputRecognizer.class);

    /**
     * 识别回复输入框
     */
    public Optional<ReplyInputTarget> findReplyInput(BufferedImage screenshot, List<OcrTextBox> textBoxes) {
        logger.info("[REPLY_INPUT] Searching for reply input field");

        // 1. 查找输入框特征文字
        for (OcrTextBox box : textBoxes) {
            String text = box.getText();

            // 美团回复框的特征文字
            if (text.contains("您的回复会被公开展示") || text.contains("请注意措辞") ||
                text.contains("最多300字") || text.contains("公开展示")) {
                logger.info("[REPLY_INPUT] Found input field by placeholder text: {}", text);
                return Optional.of(new ReplyInputTarget(box, box.getConfidence(), "placeholder match"));
            }

            // 其他可能的输入提示文字
            if (text.contains("请输入回复") || text.contains("回复内容") ||
                text.contains("请输入") || text.startsWith("回复")) {

                // 检查是否为输入区域（通常较大）
                if (box.getWidth() > 100 && box.getHeight() > 30) {
                    logger.info("[REPLY_INPUT] Found input field by text: {}", text);
                    return Optional.of(new ReplyInputTarget(box, box.getConfidence(), "text match"));
                }
            }
        }

        // 2. 查找大型文本区域（可能是输入框但没有 placeholder）
        for (OcrTextBox box : textBoxes) {
            // 输入框通常较大
            if (box.getWidth() > 300 && box.getHeight() > 50) {
                String text = box.getText();
                // 排除明显不是输入框的
                if (!text.contains("订单详情") && !text.contains("评价") &&
                    !text.contains("列表") && text.length() < 50) {
                    logger.info("[REPLY_INPUT] Found potential input field: large area");
                    return Optional.of(new ReplyInputTarget(box, 0.7, "large area"));
                }
            }
        }

        logger.warn("[REPLY_INPUT] No input field found");
        return Optional.empty();
    }
}
