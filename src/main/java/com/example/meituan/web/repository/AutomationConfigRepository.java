package com.example.meituan.web.repository;

import com.example.meituan.web.entity.AutomationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AutomationConfigRepository extends JpaRepository<AutomationConfig, Long> {

    /** 全局单行配置 */
    Optional<AutomationConfig> findFirstByOrderByIdAsc();
}
