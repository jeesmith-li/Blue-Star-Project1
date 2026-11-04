package com.example.meituan.filter;

import com.example.meituan.rating.RatingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 差评识别器
 * 判断是否为差评（总体评分 <= 2）
 */
public class BadReviewRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(BadReviewRecognizer.class);

    /**
     * 识别是否为差评
     */
    public BadReviewState recognize(RatingResult ratingResult) {
        Integer overallRating = ratingResult.getOverallRating();

        // 只有明确的总体评分才能判断
        if (overallRating == null) {
            logger.debug("[BAD_REVIEW] Overall rating unknown, cannot determine");
            return BadReviewState.UNKNOWN;
        }

        // 差评：1星或2星
        if (overallRating <= 2) {
            logger.debug("[BAD_REVIEW] Detected bad review: {} stars", overallRating);
            return BadReviewState.BAD_REVIEW;
        }

        // 非差评：3星及以上
        logger.debug("[BAD_REVIEW] Not a bad review: {} stars", overallRating);
        return BadReviewState.NOT_BAD_REVIEW;
    }
}
