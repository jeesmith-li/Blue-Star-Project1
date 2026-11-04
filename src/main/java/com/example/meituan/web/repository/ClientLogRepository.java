package com.example.meituan.web.repository;

import com.example.meituan.web.entity.ClientLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClientLogRepository extends JpaRepository<ClientLog, Long>, JpaSpecificationExecutor<ClientLog> {
}
