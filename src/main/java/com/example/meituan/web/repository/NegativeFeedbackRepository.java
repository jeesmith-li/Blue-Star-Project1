package com.example.meituan.web.repository;

import com.example.meituan.web.entity.NegativeFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NegativeFeedbackRepository extends JpaRepository<NegativeFeedback, Long> {

    List<NegativeFeedback> findByShopIdOrderByIdDesc(Long shopId);

    List<NegativeFeedback> findAllByOrderByIdDesc();

    List<NegativeFeedback> findByStatus(String status);

    Optional<NegativeFeedback> findByShopIdAndOrderNoAndChannelName(Long shopId, String orderNo, String channelName);

    void deleteByShopId(Long shopId);

    long countByShopId(Long shopId);
}
