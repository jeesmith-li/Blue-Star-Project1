package com.example.meituan.web.service;

import com.example.meituan.batch.BatchReplyProcessor;
import com.example.meituan.batch.BatchReplyResult;
import com.example.meituan.batch.ReviewIdentity;
import com.example.meituan.config.AppConfig;
import com.example.meituan.ocr.OcrClient;
import com.example.meituan.robot.RobotController;
import com.example.meituan.screenshot.ScreenCapture;
import com.example.meituan.web.entity.Review;
import com.example.meituan.web.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import com.example.meituan.util.TimeFormats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动化服务 - 单例管理 AutomationWorker
 * 确保整个 JVM 只有一个自动化任务在运行
 */
@Service
public class AutomationService {
    private static final Logger logger = LoggerFactory.getLogger(AutomationService.class);

    // 单线程执行器 - 确保串行执行
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 当前状态
    private final AtomicReference<AutomationStatus> currentStatus = new AtomicReference<>(AutomationStatus.IDLE);

    // 当前任务
    private final AtomicReference<Future<?>> currentTask = new AtomicReference<>(null);

    // 停止标志
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    // 当前店铺信息
    private volatile Long currentShopId = null;
    private volatile String currentShopName = "";

    // 统计信息
    private volatile int processedCount = 0;
    private volatile int successCount = 0;
    private volatile int failedCount = 0;
    private volatile int scrollAttempts = 0;
    private final int maxScrollAttempts = 3;

    // 当前阶段和动作
    private volatile String currentStage = "IDLE";
    private volatile String currentAction = null;

    // 日志环形缓冲区
    private final List<AutomationLog> logs = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LOGS = 500;

    // 评论数据仓库（自动化完成后保存记录）
    @Autowired
    private ReviewRepository reviewRepository;

    public enum AutomationStatus {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        COMPLETED,
        FAILED
    }

    /**
     * 启动自动化任务
     */
    public synchronized boolean start(Long shopId, String shopName) {
        AutomationStatus status = currentStatus.get();

        // 检查是否已有任务在运行
        if (status == AutomationStatus.RUNNING || status == AutomationStatus.STARTING) {
            logger.warn("[AUTOMATION] Already running, cannot start new task");
            return false;
        }

        // 重置状态
        stopRequested.set(false);
        currentShopId = shopId;
        currentShopName = shopName;
        processedCount = 0;
        successCount = 0;
        failedCount = 0;
        scrollAttempts = 0;
        currentStage = "STARTING";
        currentAction = "初始化自动化环境";

        // 清空旧日志
        logs.clear();
        addLog("INFO", "STARTING", "开始启动自动化任务 - 店铺: " + shopName);

        // 更新状态为 STARTING
        currentStatus.set(AutomationStatus.STARTING);

        // 提交后台任务
        Future<?> task = executor.submit(this::runAutomation);
        currentTask.set(task);

        logger.info("[AUTOMATION] Task submitted for shop: {}", shopName);
        return true;
    }

    /**
     * 停止自动化任务
     */
    public synchronized boolean stop() {
        AutomationStatus status = currentStatus.get();

        if (status != AutomationStatus.RUNNING && status != AutomationStatus.STARTING) {
            logger.warn("[AUTOMATION] No running task to stop");
            return false;
        }

        stopRequested.set(true);
        currentStatus.set(AutomationStatus.STOPPING);
        currentAction = "正在停止...";
        addLog("WARNING", currentStage, "收到停止请求");

        logger.info("[AUTOMATION] Stop requested");
        return true;
    }

    /**
     * 获取当前状态
     */
    public AutomationStatusDTO getStatus() {
        AutomationStatusDTO dto = new AutomationStatusDTO();
        dto.setShopId(currentShopId != null ? currentShopId : 0);
        dto.setShopName(currentShopName != null ? currentShopName : "");
        dto.setStatus(mapStatus(currentStatus.get()));
        dto.setStage(currentStage);
        dto.setProcessedCount(processedCount);
        dto.setSuccessCount(successCount);
        dto.setFailedCount(failedCount);
        dto.setScrollAttempts(scrollAttempts);
        dto.setMaxScrollAttempts(maxScrollAttempts);
        dto.setCurrentAction(currentAction);
        return dto;
    }

    /**
     * 获取日志
     */
    public List<AutomationLog> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    /**
     * 实际执行自动化任务
     */
    private void runAutomation() {
        try {
            logger.info("[AUTOMATION] Starting automation worker");
            currentStatus.set(AutomationStatus.RUNNING);
            currentStage = "RUNNING";
            currentAction = "初始化组件";
            addLog("INFO", "RUNNING", "自动化任务开始运行");

            // 初始化配置
            AppConfig config = new AppConfig();
            config.ensureDirectories();
            addLog("INFO", "RUNNING", "配置初始化完成");

            // 初始化组件 - 使用 try-catch 捕获具体错误
            ScreenCapture screenCapture;
            OcrClient ocrClient;
            RobotController robotController;

            try {
                screenCapture = new ScreenCapture();
                addLog("INFO", "RUNNING", "截图组件初始化完成");
            } catch (Exception e) {
                throw new RuntimeException("截图组件初始化失败: " + e.getMessage(), e);
            }

            try {
                ocrClient = new OcrClient(config);
                addLog("INFO", "RUNNING", "OCR组件初始化完成");
            } catch (Exception e) {
                throw new RuntimeException("OCR组件初始化失败: " + e.getMessage(), e);
            }

            try {
                robotController = new RobotController(100, 50, 20);
                addLog("INFO", "RUNNING", "Robot组件初始化完成");
            } catch (Exception e) {
                throw new RuntimeException("Robot组件初始化失败: " + e.getMessage(), e);
            }

            // 创建批量回复处理器
            BatchReplyProcessor processor = new BatchReplyProcessor(
                config, screenCapture, ocrClient, robotController);
            processor.setExpectedShopName(currentShopName);

            currentStage = "REVIEW_LIST";
            currentAction = "扫描当前屏幕评论";
            addLog("INFO", "REVIEW_LIST", "开始扫描评论列表");

            // 检查停止标志
            if (stopRequested.get()) {
                handleStop();
                return;
            }

            // 执行批量回复 (最多3条，安全模式)
            int maxReviews = 3;
            addLog("INFO", "PROCESSING", "开始处理评论，最多 " + maxReviews + " 条");

            currentStage = "PROCESSING";
            BatchReplyResult result = processor.process(maxReviews);

            // 更新统计
            processedCount = result.getProcessedReviews().size();
            successCount = result.getSuccessCount();
            failedCount = result.isFailed() ? 1 : 0;

            // 保存回复记录到数据库（让差评管理页面能看到数据）
            int savedCount = 0;
            for (ReviewIdentity identity : result.getProcessedReviews()) {
                try {
                    Review review = new Review();
                    review.setShopId(currentShopId);
                    review.setShopName(currentShopName);
                    review.setPlatformReviewId("auto_" + System.currentTimeMillis() + "_" + savedCount);
                    review.setReviewer(identity.getReviewer() != null ? identity.getReviewer() : "未知用户");
                    // 评分：差评默认为3，有实际评分则用实际值
                    int rating = identity.getProductRating() != null ? identity.getProductRating() : 3;
                    review.setRating(Math.min(rating, 5));
                    review.setContent(identity.getContentSnippet() != null ? identity.getContentSnippet() : "");
                    // 解析评论时间
                    LocalDateTime reviewTime = LocalDateTime.now();
                    if (identity.getReviewDate() != null && !identity.getReviewDate().isEmpty()) {
                        try {
                            reviewTime = LocalDateTime.parse(identity.getReviewDate().substring(0, Math.min(19, identity.getReviewDate().length())).replace(" ", "T"));
                        } catch (Exception e) { /* 用当前时间 */ }
                    }
                    review.setReviewTime(reviewTime);
                    review.setReplyStatus("replied");
                    review.setReplyContent("（自动化回复）");
                    review.setReplyTime(LocalDateTime.now());
                    review.setCreatedAt(LocalDateTime.now());
                    reviewRepository.save(review);
                    savedCount++;
                } catch (Exception e) {
                    logger.error("[AUTOMATION] Failed to save review record: {}", e.getMessage());
                }
            }
            logger.info("[AUTOMATION] Saved {} review records to database", savedCount);
            addLog("INFO", "PROCESSING", "已保存 " + savedCount + " 条回复记录到数据库");

            // 检查停止标志
            if (stopRequested.get()) {
                handleStop();
                return;
            }

            // 完成
            if (result.isFailed()) {
                // 失败（包括店铺不匹配等）
                currentStatus.set(AutomationStatus.FAILED);
                currentStage = "FAILED";
                currentAction = result.getFailureReason();
                addLog("ERROR", "FAILED", "自动化任务失败: " + result.getFailureReason());
                logger.error("[AUTOMATION] Task failed: {}", result.getFailureReason());
            } else if (result.getSuccessCount() > 0) {
                currentStatus.set(AutomationStatus.COMPLETED);
                currentStage = "COMPLETED";
                currentAction = null;
                addLog("SUCCESS", "COMPLETED",
                    String.format("自动化任务完成 - 成功回复 %d 条", result.getSuccessCount()));
                logger.info("[AUTOMATION] Task completed successfully: {} reviews", result.getSuccessCount());
            } else {
                currentStatus.set(AutomationStatus.COMPLETED);
                currentStage = "COMPLETED";
                currentAction = null;
                addLog("WARNING", "COMPLETED", "未找到需要回复的评论");
                logger.info("[AUTOMATION] Task completed with no reviews processed");
            }

        } catch (Exception e) {
            logger.error("[AUTOMATION] Task exception", e);
            currentStatus.set(AutomationStatus.FAILED);
            currentStage = "FAILED";
            currentAction = e.getMessage();
            addLog("ERROR", "FAILED", "自动化任务异常: " + e.getMessage());

            // 记录详细堆栈信息
            logger.error("[AUTOMATION] Detailed error: ", e);
        } finally {
            currentTask.set(null);
        }
    }

    /**
     * 处理停止请求
     */
    private void handleStop() {
        currentStatus.set(AutomationStatus.STOPPED);
        currentStage = "STOPPED";
        currentAction = null;
        addLog("WARNING", "STOPPED", "自动化任务已停止");
        logger.info("[AUTOMATION] Task stopped by user");
    }

    /**
     * 添加日志
     */
    private void addLog(String level, String stage, String message) {
        AutomationLog log = new AutomationLog();
        log.setTimestamp(LocalDateTime.now().format(TimeFormats.DATETIME));
        log.setLevel(level);
        log.setMessage("[" + stage + "] " + message);

        synchronized (logs) {
            logs.add(log);
            // 保持最多 MAX_LOGS 条
            if (logs.size() > MAX_LOGS) {
                logs.remove(0);
            }
        }
    }

    /**
     * 映射状态到前端格式
     */
    private String mapStatus(AutomationStatus status) {
        switch (status) {
            case RUNNING:
                return "running";
            case IDLE:
            case STOPPED:
            case COMPLETED:
                return "idle";
            case STARTING:
                return "running"; // 前端显示为运行中
            case FAILED:
                return "failed";
            default:
                return "idle";
        }
    }

    /**
     * 状态 DTO
     */
    public static class AutomationStatusDTO {
        private Long shopId;
        private String shopName;
        private String status;
        private String stage;
        private int processedCount;
        private int successCount;
        private int failedCount;
        private int scrollAttempts;
        private int maxScrollAttempts;
        private String currentAction;

        // Getters and Setters
        public Long getShopId() { return shopId; }
        public void setShopId(Long shopId) { this.shopId = shopId; }
        public String getShopName() { return shopName; }
        public void setShopName(String shopName) { this.shopName = shopName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public int getProcessedCount() { return processedCount; }
        public void setProcessedCount(int processedCount) { this.processedCount = processedCount; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public int getFailedCount() { return failedCount; }
        public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
        public int getScrollAttempts() { return scrollAttempts; }
        public void setScrollAttempts(int scrollAttempts) { this.scrollAttempts = scrollAttempts; }
        public int getMaxScrollAttempts() { return maxScrollAttempts; }
        public void setMaxScrollAttempts(int maxScrollAttempts) { this.maxScrollAttempts = maxScrollAttempts; }
        public String getCurrentAction() { return currentAction; }
        public void setCurrentAction(String currentAction) { this.currentAction = currentAction; }
    }

    /**
     * 日志 DTO
     */
    public static class AutomationLog {
        private String timestamp;
        private String level;
        private String message;

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
