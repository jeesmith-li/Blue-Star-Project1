package com.example.meituan.scroll;

import com.example.meituan.ocr.OcrTextBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 屏幕指纹
 * 用于检测滑动是否产生视觉变化
 */
public class ScreenFingerprint {
    private final Set<String> textSet;
    private final int textBoxCount;
    private final String textSnapshot;

    public ScreenFingerprint(List<OcrTextBox> ocrBoxes) {
        this.textSet = new HashSet<>();
        this.textBoxCount = ocrBoxes.size();

        StringBuilder sb = new StringBuilder();
        for (OcrTextBox box : ocrBoxes) {
            String text = box.getText();
            if (text != null && text.length() > 2) {
                textSet.add(text);
                sb.append(text).append(";");
            }
        }
        this.textSnapshot = sb.toString();
    }

    /**
     * 计算与另一个指纹的相似度
     */
    public double similarity(ScreenFingerprint other) {
        if (other == null) {
            return 0.0;
        }

        // 1. 文本集合交集
        Set<String> intersection = new HashSet<>(this.textSet);
        intersection.retainAll(other.textSet);

        Set<String> union = new HashSet<>(this.textSet);
        union.addAll(other.textSet);

        double jaccardSimilarity = union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();

        // 2. 文本框数量相似度
        int maxCount = Math.max(this.textBoxCount, other.textBoxCount);
        int minCount = Math.min(this.textBoxCount, other.textBoxCount);
        double countSimilarity = maxCount == 0 ? 0.0 : (double) minCount / maxCount;

        // 综合
        return jaccardSimilarity * 0.7 + countSimilarity * 0.3;
    }

    public int getTextBoxCount() {
        return textBoxCount;
    }

    @Override
    public String toString() {
        return String.format("Fingerprint[boxes=%d, uniqueTexts=%d]", textBoxCount, textSet.size());
    }
}
