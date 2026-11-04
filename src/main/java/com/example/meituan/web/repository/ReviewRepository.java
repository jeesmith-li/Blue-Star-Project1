package com.example.meituan.web.repository;

import com.example.meituan.web.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long>, JpaSpecificationExecutor<Review> {

    Page<Review> findByShopId(Long shopId, Pageable pageable);

    Page<Review> findByShopIdAndReplyStatus(Long shopId, String replyStatus, Pageable pageable);

    Page<Review> findByShopIdAndRating(Long shopId, Integer rating, Pageable pageable);

    Page<Review> findByShopIdAndReplyStatusAndRating(Long shopId, String replyStatus, Integer rating, Pageable pageable);

    long countByShopId(Long shopId);

    long countByShopIdAndReplyStatus(Long shopId, String replyStatus);

    java.util.Optional<Review> findByShopIdAndPlatformReviewId(Long shopId, String platformReviewId);

    java.util.List<Review> findByReplyStatusAndAppealStatus(String replyStatus, String appealStatus);

    java.util.List<Review> findByAppealStatus(String appealStatus);
}
