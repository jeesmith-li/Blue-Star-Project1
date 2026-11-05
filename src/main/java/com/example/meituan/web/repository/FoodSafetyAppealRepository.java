package com.example.meituan.web.repository;

import com.example.meituan.web.entity.FoodSafetyAppeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FoodSafetyAppealRepository extends JpaRepository<FoodSafetyAppeal, Long> {

    Optional<FoodSafetyAppeal> findByOrderNo(String orderNo);

    List<FoodSafetyAppeal> findByShopIdOrderByCreatedAtDesc(Long shopId);

    List<FoodSafetyAppeal> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<FoodSafetyAppeal> findByShopIdAndMtStatus(Long shopId, Integer mtStatus);

    void deleteByShopId(Long shopId);

    long countByShopId(Long shopId);
}
