package com.example.meituan.web.repository;

import com.example.meituan.web.entity.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShopRepository extends JpaRepository<Shop, Long> {
    List<Shop> findByUserId(Long userId);

    long countByUserId(Long userId);

    /** 按执行顺序升序（未设置顺序的排在最前，再按绑定ID） */
    List<Shop> findAllByOrderBySortOrderAscIdAsc();

    List<Shop> findByUserIdOrderBySortOrderAscIdAsc(Long userId);
}
