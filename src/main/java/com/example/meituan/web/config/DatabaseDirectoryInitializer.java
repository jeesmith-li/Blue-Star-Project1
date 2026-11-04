package com.example.meituan.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.File;

/**
 * 数据库目录初始化
 */
@Configuration
public class DatabaseDirectoryInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseDirectoryInitializer.class);

    @PostConstruct
    public void init() {
        String dbPath = "data/database";
        File dbDir = new File(dbPath);

        if (!dbDir.exists()) {
            boolean created = dbDir.mkdirs();
            if (created) {
                logger.info("✓ 创建数据库目录: {}", dbDir.getAbsolutePath());
            } else {
                logger.error("✗ 创建数据库目录失败: {}", dbDir.getAbsolutePath());
            }
        } else {
            logger.info("✓ 数据库目录已存在: {}", dbDir.getAbsolutePath());
        }
    }
}
