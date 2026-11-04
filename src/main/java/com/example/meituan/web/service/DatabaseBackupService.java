package com.example.meituan.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 数据库自动备份服务
 * 在应用启动时自动备份数据库文件
 */
@Component
@Order(0)
public class DatabaseBackupService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBackupService.class);

    @Autowired
    private DataSource dataSource;

    private static final String DB_FILE = "data/database/badreview.db";
    private static final String BACKUP_DIR = "backup/database";
    private static final int MAX_BACKUPS = 7; // 保留最近7份备份

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        backupDatabase();
        cleanOldBackups();
    }

    /** 每天凌晨 4:30 自动备份一次（本地部署机器不常重启，仅启动备份频率不够） */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 4 * * ?")
    public void scheduledBackup() {
        backupDatabase();
        cleanOldBackups();
    }

    /**
     * 备份数据库文件（使用 SQLite 的 VACUUM INTO 在线备份，不阻塞写入、生成紧凑独立文件）
     */
    private void backupDatabase() {
        try {
            Path dbPath = Paths.get(DB_FILE);

            // 检查数据库文件是否存在
            if (!Files.exists(dbPath)) {
                logger.info("数据库文件不存在，跳过备份: {}", DB_FILE);
                return;
            }

            // 创建备份目录
            Path backupDirPath = Paths.get(BACKUP_DIR);
            Files.createDirectories(backupDirPath);

            // 生成备份文件名: badreview-20260915-213045.zip
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String backupFileName = "badreview-" + timestamp + ".db";
            Path backupPath = backupDirPath.resolve(backupFileName);

            // 使用 SQLite 的 VACUUM INTO 在线备份数据库（不阻塞、不受文件锁影响；目标文件必须不存在）
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                if (Files.exists(backupPath)) {
                    Files.delete(backupPath);
                }
                String sql = String.format("VACUUM INTO '%s'", backupPath.toString().replace("\\", "/"));
                stmt.execute(sql);

                long fileSize = Files.size(backupPath) / 1024; // KB
                logger.info("✓ 数据库备份成功: {} ({}KB)", backupFileName, fileSize);
            }

        } catch (Exception e) {
            logger.warn("数据库备份失败（非致命错误）: {}", e.getMessage());
        }
    }

    /**
     * 清理旧备份，只保留最近7份
     */
    private void cleanOldBackups() {
        try {
            Path backupDirPath = Paths.get(BACKUP_DIR);

            if (!Files.exists(backupDirPath)) {
                return;
            }

            // 获取所有备份文件，按修改时间排序（最新的在前）
            try (Stream<Path> files = Files.list(backupDirPath)) {
                List<Path> backupFiles = files
                    .filter(p -> p.getFileName().toString().startsWith("badreview-"))
                    .filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted((p1, p2) -> {
                        try {
                            return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .collect(Collectors.toList());

                // 如果备份数量超过限制，删除最旧的
                if (backupFiles.size() > MAX_BACKUPS) {
                    for (int i = MAX_BACKUPS; i < backupFiles.size(); i++) {
                        Path oldBackup = backupFiles.get(i);
                        Files.delete(oldBackup);
                        logger.info("✓ 删除旧备份: {}", oldBackup.getFileName());
                    }
                }
            }

        } catch (IOException e) {
            logger.warn("清理旧备份失败（非致命错误）: {}", e.getMessage());
        }
    }
}
