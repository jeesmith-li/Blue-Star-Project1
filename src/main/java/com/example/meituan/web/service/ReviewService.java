package com.example.meituan.web.service;

import com.example.meituan.web.entity.Review;
import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ReviewRepository;
import com.example.meituan.web.repository.ShopRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReviewService {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ShopRepository shopRepository;

    /**
     * 获取评价列表（带分页和筛选）
     *
     * @param replyStatus "replied"/"unreplied"/null(全部)
     * @param ratingStr   "bad"(1-2星)/"medium"(3星)/"good"(4-5星)/null(全部)
     * @param hasContent  "yes"/"no"/null(全部)
     */
    public ReviewListResult getReviews(User user, Long shopId, Integer page, Integer pageSize,
                                       String replyStatus, String ratingStr, String hasContent) {
        // 验证权限
        if (user.getRole() != User.UserRole.ADMIN) {
            // 普通用户只能查看自己的店铺
            if (shopId != null) {
                Shop shop = shopRepository.findById(shopId).orElse(null);
                if (shop == null || !shop.getUserId().equals(user.getId())) {
                    throw new RuntimeException("无权限访问此店铺的评价");
                }
            } else {
                // 没有指定店铺，获取用户的第一个店铺
                List<Shop> userShops = shopRepository.findByUserId(user.getId());
                if (userShops.isEmpty()) {
                    return new ReviewListResult(List.of(), 0);
                }
                shopId = userShops.get(0).getId();
            }
        }

        // 默认值
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;

        // 创建分页对象（按评价时间倒序）
        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by("reviewTime").descending());

        final Long specShopId = shopId;
        Specification<Review> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new java.util.ArrayList<>();
            if (specShopId != null) {
                ps.add(cb.equal(root.get("shopId"), specShopId));
            }
            if (replyStatus != null && !replyStatus.isEmpty() && !"all".equalsIgnoreCase(replyStatus)) {
                ps.add(cb.equal(root.get("replyStatus"), replyStatus));
            }
            if (ratingStr != null && !ratingStr.isEmpty() && !"all".equalsIgnoreCase(ratingStr)) {
                switch (ratingStr) {
                    case "bad":
                        ps.add(cb.between(root.get("rating"), 1, 2));
                        break;
                    case "medium":
                        ps.add(cb.equal(root.get("rating"), 3));
                        break;
                    case "good":
                        ps.add(cb.between(root.get("rating"), 4, 5));
                        break;
                    default:
                        break;
                }
            }
            if ("yes".equalsIgnoreCase(hasContent)) {
                // 有内容：length(content) > 0（NULL 的 length 为 NULL，同样被排除）
                ps.add(cb.greaterThan(cb.length(root.get("content")), 0));
            } else if ("no".equalsIgnoreCase(hasContent)) {
                ps.add(cb.or(
                    cb.isNull(root.get("content")),
                    cb.lessThanOrEqualTo(cb.length(root.get("content")), 0)
                ));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Review> reviewPage = reviewRepository.findAll(spec, pageable);

        List<ReviewDTO> items = reviewPage.getContent().stream()
            .map(this::toDTO)
            .collect(Collectors.toList());

        return new ReviewListResult(items, reviewPage.getTotalElements());
    }

    /**
     * 手动标记已回复：支持单条(id)、批量(ids)、整店全部(shopId)，返回实际更新条数。
     * 已是"replied"的记录跳过，避免覆盖真实回复时间。
     */
    public int markReplied(Long id, List<Long> ids, Long shopId) {
        List<Review> targets;
        if (ids != null && !ids.isEmpty()) {
            targets = reviewRepository.findAllById(ids);
        } else if (id != null) {
            targets = reviewRepository.findById(id).map(List::of).orElse(List.of());
        } else if (shopId != null) {
            targets = reviewRepository.findByShopId(shopId, Pageable.unpaged()).getContent();
        } else {
            return 0;
        }
        int n = 0;
        for (Review r : targets) {
            if (!"replied".equals(r.getReplyStatus())) {
                r.setReplyStatus("replied");
                r.setReplyTime(java.time.LocalDateTime.now());
                reviewRepository.save(r);
                n++;
            }
        }
        return n;
    }

    /**
     * 获取单个评价
     */
    public Review getReview(Long id) {
        return reviewRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("评价不存在"));
    }

    /**
     * 回复评价
     */
    public void replyReview(Long id, String replyContent) {
        Review review = getReview(id);
        review.setReplyContent(replyContent);
        review.setReplyStatus("replied");
        review.setReplyTime(java.time.LocalDateTime.now());
        reviewRepository.save(review);
    }

    /**
     * 将抓取到的评价去重入库（按 shopId + platformReviewId），返回新增条数
     */
    public int saveScrapedReviews(Long shopId, String shopName, List<java.util.Map<String, Object>> reviews) {
        if (reviews == null) return 0;
        int added = 0;
        for (java.util.Map<String, Object> r : reviews) {
            String platformId = str(r.get("platformReviewId"));
            Review existing = null;
            if (!platformId.isEmpty()) {
                existing = reviewRepository.findByShopIdAndPlatformReviewId(shopId, platformId).orElse(null);
            }
            Review review = existing != null ? existing : new Review();
            if (existing == null) {
                review.setShopId(shopId);
                review.setShopName(shopName);
                review.setPlatformReviewId(platformId.isEmpty() ? ("local_" + System.nanoTime()) : platformId);
                review.setCreatedAt(java.time.LocalDateTime.now());
            }
            review.setReviewer(str(r.get("reviewer")));
            Integer rating = toInt(r.get("rating"));
            review.setRating(rating == null ? 0 : rating);
            review.setContent(str(r.get("content")));
            review.setReviewTime(parseTime(str(r.get("reviewTime"))));
            String replyStatus = str(r.get("replyStatus"));
            review.setReplyStatus(replyStatus.isEmpty() ? "unreplied" : replyStatus);
            review.setReplyContent(str(r.get("replyContent")));
            String rt = str(r.get("replyTime"));
            review.setReplyTime(rt.isEmpty() ? null : parseTime(rt));
            reviewRepository.save(review);
            if (existing == null) added++;
        }
        return added;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private java.time.LocalDateTime parseTime(String s) {
        if (s == null || s.isEmpty()) return java.time.LocalDateTime.now();
        try {
            return java.time.LocalDateTime.parse(s.replace(" ", "T"));
        } catch (Exception e) {
            try {
                return java.time.LocalDate.parse(s.substring(0, 10)).atStartOfDay();
            } catch (Exception e2) {
                return java.time.LocalDateTime.now();
            }
        }
    }

    /**
     * 转换为 DTO
     */
    private ReviewDTO toDTO(Review review) {
        ReviewDTO dto = new ReviewDTO();
        dto.setId(review.getId());
        dto.setShopId(review.getShopId());
        dto.setShopName(review.getShopName());
        dto.setPlatformReviewId(review.getPlatformReviewId());
        dto.setReviewer(review.getReviewer());
        dto.setRating(review.getRating());
        dto.setContent(review.getContent());
        dto.setReviewTime(review.getReviewTime().toString().replace('T', ' '));
        dto.setReplyStatus(review.getReplyStatus());
        dto.setReplyContent(review.getReplyContent());
        dto.setReplyTemplate(review.getReplyTemplate());
        if (review.getReplyTime() != null) {
            dto.setReplyTime(review.getReplyTime().toString().replace('T', ' '));
        }
        dto.setAppealStatus(review.getAppealStatus());
        dto.setAppealContent(review.getAppealContent());
        dto.setAppealImages(review.getAppealImages());
        dto.setAppealTemplate(review.getAppealTemplate());
        if (review.getAppealTime() != null) {
            dto.setAppealTime(review.getAppealTime().toString().replace('T', ' '));
        }
        dto.setAppealResultNote(review.getAppealResultNote());
        return dto;
    }

    /**
     * 评价列表结果
     */
    public static class ReviewListResult {
        private List<ReviewDTO> items;
        private long total;

        public ReviewListResult(List<ReviewDTO> items, long total) {
            this.items = items;
            this.total = total;
        }

        public List<ReviewDTO> getItems() { return items; }
        public long getTotal() { return total; }
    }

    /**
     * 评价 DTO
     */
    public static class ReviewDTO {
        private Long id;
        private Long shopId;
        private String shopName;
        private String platformReviewId;
        private String reviewer;
        private Integer rating;
        private String content;
        private String reviewTime;
        private String replyStatus;
        private String replyContent;
        private String replyTemplate;
        private String replyTime;
        private String appealStatus;
        private String appealContent;
        private String appealImages;
        private String appealTemplate;
        private String appealTime;
        private String appealResultNote;

        // Getters and Setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getShopId() { return shopId; }
        public void setShopId(Long shopId) { this.shopId = shopId; }
        public String getShopName() { return shopName; }
        public void setShopName(String shopName) { this.shopName = shopName; }
        public String getPlatformReviewId() { return platformReviewId; }
        public void setPlatformReviewId(String platformReviewId) { this.platformReviewId = platformReviewId; }
        public String getReviewer() { return reviewer; }
        public void setReviewer(String reviewer) { this.reviewer = reviewer; }
        public Integer getRating() { return rating; }
        public void setRating(Integer rating) { this.rating = rating; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getReviewTime() { return reviewTime; }
        public void setReviewTime(String reviewTime) { this.reviewTime = reviewTime; }
        public String getReplyStatus() { return replyStatus; }
        public void setReplyStatus(String replyStatus) { this.replyStatus = replyStatus; }
        public String getReplyContent() { return replyContent; }
        public void setReplyContent(String replyContent) { this.replyContent = replyContent; }
        public String getReplyTemplate() { return replyTemplate; }
        public void setReplyTemplate(String replyTemplate) { this.replyTemplate = replyTemplate; }
        public String getReplyTime() { return replyTime; }
        public void setReplyTime(String replyTime) { this.replyTime = replyTime; }
        public String getAppealStatus() { return appealStatus; }
        public void setAppealStatus(String appealStatus) { this.appealStatus = appealStatus; }
        public String getAppealTemplate() { return appealTemplate; }
        public void setAppealTemplate(String appealTemplate) { this.appealTemplate = appealTemplate; }
        public String getAppealContent() { return appealContent; }
        public void setAppealContent(String appealContent) { this.appealContent = appealContent; }
        public String getAppealImages() { return appealImages; }
        public void setAppealImages(String appealImages) { this.appealImages = appealImages; }
        public String getAppealTime() { return appealTime; }
        public void setAppealTime(String appealTime) { this.appealTime = appealTime; }
        public String getAppealResultNote() { return appealResultNote; }
        public void setAppealResultNote(String appealResultNote) { this.appealResultNote = appealResultNote; }
    }
}
