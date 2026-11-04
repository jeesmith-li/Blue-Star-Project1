package com.example.meituan.vision;

/**
 * 文本匹配器
 * 处理 OCR 识别中的常见问题：
 * - 额外空格
 * - 标点符号差异
 * - 常见 OCR 错误字符
 */
public class TextMatcher {

    /**
     * 计算文本匹配分数
     * 综合考虑：文本相似度、OCR 置信度
     */
    public double scoreMatch(String ocrText, String targetText, double ocrConfidence) {
        if (ocrText == null || targetText == null) {
            return 0.0;
        }

        String normalizedOcr = normalize(ocrText);
        String normalizedTarget = normalize(targetText);

        // 完全匹配
        if (normalizedOcr.equals(normalizedTarget)) {
            return ocrConfidence * 1.0;
        }

        // 包含匹配
        if (normalizedOcr.contains(normalizedTarget)) {
            double lengthRatio = (double) normalizedTarget.length() / normalizedOcr.length();
            return ocrConfidence * 0.9 * lengthRatio;
        }

        if (normalizedTarget.contains(normalizedOcr)) {
            double lengthRatio = (double) normalizedOcr.length() / normalizedTarget.length();
            return ocrConfidence * 0.85 * lengthRatio;
        }

        // 相似度匹配
        double similarity = calculateSimilarity(normalizedOcr, normalizedTarget);
        if (similarity > 0.7) {
            return ocrConfidence * similarity * 0.8;
        }

        return 0.0;
    }

    /**
     * 检查 OCR 文本是否包含目标文本
     */
    public boolean contains(String ocrText, String targetText) {
        return normalize(ocrText).contains(normalize(targetText));
    }

    /**
     * 文本归一化
     * 去除空格、统一标点符号
     */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replaceAll("\\s+", "") // 去除所有空格
                .replace("（", "(")
                .replace("）", ")")
                .replace("：", ":")
                .replace("，", ",")
                .toLowerCase();
    }

    /**
     * 计算两个字符串的相似度 (Levenshtein Distance)
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }

        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) {
            return 1.0;
        }

        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLength;
    }

    /**
     * Levenshtein Distance 算法
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }

        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[s1.length()][s2.length()];
    }
}
