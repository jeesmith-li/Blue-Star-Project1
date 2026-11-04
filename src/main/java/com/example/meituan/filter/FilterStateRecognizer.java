package com.example.meituan.filter;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 筛选状态识别器
 * 从 OCR 结果识别当前筛选器状态
 */
public class FilterStateRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(FilterStateRecognizer.class);

    /**
     * 识别当前筛选状态
     */
    public FilterState recognizeState(List<OcrTextBox> textBoxes) {
        FilterState state = new FilterState();

        // 检查回复筛选
        if (containsText(textBoxes, "未回复")) {
            state.setReplyFilter(FilterState.ReplyFilter.UNREPLIED);
        } else if (containsText(textBoxes, "已回复")) {
            state.setReplyFilter(FilterState.ReplyFilter.REPLIED);
        }

        // 检查评分筛选
        if (containsText(textBoxes, "差评")) {
            state.setRatingFilter(FilterState.RatingFilter.BAD);
        } else if (containsText(textBoxes, "好评")) {
            state.setRatingFilter(FilterState.RatingFilter.GOOD);
        } else if (containsText(textBoxes, "中评")) {
            state.setRatingFilter(FilterState.RatingFilter.MEDIUM);
        }

        // 检查内容筛选
        if (containsText(textBoxes, "有内容")) {
            state.setContentFilter(FilterState.ContentFilter.HAS_CONTENT);
        } else if (containsText(textBoxes, "无内容")) {
            state.setContentFilter(FilterState.ContentFilter.NO_CONTENT);
        }

        logger.info("[FILTER_STATE] Recognized: {}", state);
        return state;
    }

    private boolean containsText(List<OcrTextBox> textBoxes, String text) {
        return textBoxes.stream().anyMatch(box -> box.getText().contains(text));
    }
}
