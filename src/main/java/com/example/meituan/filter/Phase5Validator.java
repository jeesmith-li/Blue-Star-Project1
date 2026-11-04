package com.example.meituan.filter;

import com.example.meituan.review.ReplyState;
import com.example.meituan.review.ReviewCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * PHASE 5 验证器
 */
public class Phase5Validator {
    private static final Logger logger = LoggerFactory.getLogger(Phase5Validator.class);

    private List<String> errors = new ArrayList<>();

    /**
     * 验证筛选结果
     */
    public boolean validate(List<ReviewFilterDecision> decisions, List<ReviewCard> cards) {
        errors.clear();

        logger.info("[VALIDATION] Validating {} decisions against {} cards", decisions.size(), cards.size());

        // 1. ReviewCard 数量 > 0
        if (cards.isEmpty()) {
            errors.add("No ReviewCard detected");
            logger.error("[VALIDATION] No ReviewCard detected");
            return false;
        }

        // 2. 每个 ReviewCard 都有对应的 Decision
        if (decisions.size() != cards.size()) {
            String error = String.format("Decision count (%d) != Card count (%d)",
                    decisions.size(), cards.size());
            errors.add(error);
            logger.error("[VALIDATION] {}", error);
        }

        // 3. 检查每个 Decision
        for (ReviewFilterDecision decision : decisions) {
            // UNKNOWN 不能被误转换
            if (decision.getBadReviewState() == null) {
                errors.add("Review #" + decision.getReviewIndex() + ": BadReviewState is null");
            }

            if (decision.getReplyState() == null) {
                errors.add("Review #" + decision.getReviewIndex() + ": ReplyState is null");
            }

            // eligibleForAutoReply 必须满足条件
            if (decision.isEligibleForAutoReply()) {
                if (decision.getBadReviewState() != BadReviewState.BAD_REVIEW) {
                    errors.add("Review #" + decision.getReviewIndex() +
                            ": Eligible but not BAD_REVIEW");
                }

                if (decision.getReplyState() != ReplyState.UNREPLIED) {
                    errors.add("Review #" + decision.getReviewIndex() +
                            ": Eligible but not UNREPLIED");
                }
            }

            // UNKNOWN 不应该 eligible
            if (decision.getBadReviewState() == BadReviewState.UNKNOWN ||
                decision.getReplyState() == ReplyState.UNKNOWN) {
                if (decision.isEligibleForAutoReply()) {
                    errors.add("Review #" + decision.getReviewIndex() +
                            ": Eligible despite UNKNOWN state");
                }
            }
        }

        // 4. 报告结果
        if (errors.isEmpty()) {
            logger.info("[VALIDATION] PASS");
            return true;
        } else {
            logger.error("[VALIDATION] FAILED with {} errors:", errors.size());
            for (String error : errors) {
                logger.error("[VALIDATION]   - {}", error);
            }
            return false;
        }
    }

    public List<String> getErrors() {
        return errors;
    }
}
