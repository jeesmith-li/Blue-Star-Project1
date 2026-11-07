package com.example.meituan.web.service;

import com.example.meituan.browser.BrowserAutomationService;
import com.example.meituan.browser.ShopNotFoundException;
import com.example.meituan.web.entity.AutomationConfig;
import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.repository.AutomationConfigRepository;
import com.example.meituan.web.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每日自动流水线（按店铺执行顺序闭环）：
 * 店铺1：抓取评价 → 自动回复 → 差评申诉 → 申诉回查 → 食安抓取 → 食安自动申诉 → 食安回查
 * 店铺1全部完成后再进入店铺2，依次类推。
 * 任意一家店铺"未找到/校验失败"立即中止整批，避免误操作到其他店铺。
 */
@Component
public class DailyCaptureScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DailyCaptureScheduler.class);
    private static final String OPERATOR = "system";

    /** 单店自动化复用窗口：2 小时内已抓取或已执行过自动化时，本次不再重复抓取评价 */
    private static final long SHOP_REUSE_WINDOW_HOURS = 2;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private BrowserAutomationService browserService;

    @Autowired
    private com.example.meituan.web.service.ReviewService reviewService;

    @Autowired
    private NegativeFeedbackService negativeFeedbackService;

    @Autowired
    private AppealService appealService;

    @Autowired
    private com.example.meituan.web.service.FoodSafetyService foodSafetyService;

    @Autowired
    private ClientLogService clientLogService;

    @Autowired
    private com.example.meituan.web.service.ReplyExecutionService replyExecutionService;

    @Autowired
    private AutomationConfigRepository configRepository;

    /** 每小时检查一次：到达用户配置的执行间隔（automation_config 表）才执行 */
    @Scheduled(cron = "0 7 * * * ?")
    public void scheduledCheck() {
        try {
            AutomationConfig cfg = getOrCreateConfig();
            if (!Boolean.TRUE.equals(cfg.getDailyCaptureEnabled())) {
                return;
            }
            LocalDateTime last = cfg.getLastRunTime();
            int interval = cfg.getIntervalHours() == null ? 24 : Math.max(1, cfg.getIntervalHours());
            if (last != null && LocalDateTime.now().isBefore(last.plusHours(interval))) {
                return;
            }
            runNow("定时触发");
        } catch (Exception e) {
            logger.warn("[SCHEDULER] 定时检查异常: {}", e.getMessage());
        }
    }

    private AutomationConfig getOrCreateConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> configRepository.save(new AutomationConfig()));
    }

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Object> runNow(String trigger) {
        return runNow(trigger, null);
    }

    /**
     * 立即执行一次完整流水线
     * @param userId 非空时只处理该用户名下的店铺（普通用户手动触发）；为空处理全部店铺
     */
    public Map<String, Object> runNow(String trigger, Long userId) {
        if (!running.compareAndSet(false, true)) {
            clientLogService.info("scheduler", OPERATOR, "自动流水线跳过", "上一次任务仍在执行中，本次(" + trigger + ")跳过");
            return Map.of("success", false, "message", "上一次自动流水线仍在执行中，请稍后再试");
        }
        try {
            Map<String, Object> result = doRun(trigger, userId);
            String resultType = String.valueOf(result.getOrDefault("resultType", ""));
            if (!"NO_SHOPS".equals(resultType)) {
                try {
                    AutomationConfig cfg = getOrCreateConfig();
                    cfg.setLastRunTime(LocalDateTime.now());
                    switch (resultType) {
                        case "SUCCESS" -> cfg.setLastRunResult("success");
                        case "SKIP_BROWSER" -> cfg.setLastRunResult("skip");
                        default -> cfg.setLastRunResult("partial");
                    }
                    cfg.setLastRunDetail(String.valueOf(result.get("message")));
                    configRepository.save(cfg);
                } catch (Exception e) {
                    logger.warn("[SCHEDULER] 更新执行状态失败: {}", e.getMessage());
                }
            }
            return result;
        } finally {
            running.set(false);
        }
    }

    /**
     * 仅对指定店铺执行一次自动化流水线：
     * 抓取评价（2 小时内已抓取/已执行过自动化则跳过抓取）→ 自动回复 → 差评申诉 → 申诉回查
     * → 食安抓取 → 食安自动申诉 → 食安回查
     * @param userId 非空时校验店铺归属（普通用户只能操作自己名下店铺）
     */
    public Map<String, Object> runForShop(Long shopId, String trigger, Long userId) {
        if (!running.compareAndSet(false, true)) {
            clientLogService.info("scheduler", OPERATOR, "单店自动化跳过", "上一次任务仍在执行中，本次(" + trigger + ")跳过");
            return Map.of("success", false, "message", "上一次自动流水线仍在执行中，请稍后再试");
        }
        try {
            return doRunForShop(shopId, trigger, userId);
        } finally {
            running.set(false);
        }
    }

    private Map<String, Object> doRunForShop(Long shopId, String trigger, Long userId) {
        long start = System.currentTimeMillis();
        Shop shop = shopRepository.findById(shopId).orElse(null);
        if (shop == null || (userId != null && !userId.equals(shop.getUserId()))) {
            String msg = "该店铺暂未寻到或无权操作（shopId=" + shopId + "）";
            clientLogService.error("scheduler", OPERATOR, "单店自动化跳过", msg);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("code", "SHOP_NOT_FOUND");
            r.put("message", msg);
            return r;
        }

        boolean loggedIn;
        try {
            loggedIn = browserService.checkLogin();
        } catch (Exception e) {
            loggedIn = false;
            logger.warn("[SCHEDULER] 单店登录检查异常: {}", e.getMessage());
        }
        if (!loggedIn) {
            String detail = "浏览器未连接或未登录美团，本次单店自动化(" + shop.getName() + ")跳过";
            clientLogService.error("scheduler", OPERATOR, "单店自动化跳过", detail);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("resultType", "SKIP_BROWSER");
            r.put("message", detail);
            return r;
        }

        int fetched = 0, added = 0, replied = 0, replyFailed = 0;
        int appealCandidates = 0, appealSubmitted = 0, appealUpdated = 0;
        int fsScraped = 0, fsSubmitted = 0, fsChecked = 0;
        boolean scrapeSkipped = false;
        String skipReason = null;
        try {
            // 2 小时复用窗口：最近抓取或最近自动化在 2 小时内，本次不重复抓取评价
            LocalDateTime last = shop.getLastScrapeTime();
            if (shop.getLastAutomationTime() != null && (last == null || shop.getLastAutomationTime().isAfter(last))) {
                last = shop.getLastAutomationTime();
            }
            if (last != null && last.isAfter(LocalDateTime.now().minusHours(SHOP_REUSE_WINDOW_HOURS))) {
                scrapeSkipped = true;
                skipReason = "最近一次抓取/自动化时间为 " + last + "，在 " + SHOP_REUSE_WINDOW_HOURS + " 小时内，本次不重复抓取评价";
                logger.info("[SCHEDULER] 单店{}跳过抓取: {}", shop.getId(), skipReason);
            }

            if (!scrapeSkipped) {
                Map<String, Object> res = browserService.scrapeReviews(shop.getName());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reviews = (List<Map<String, Object>>) res.get("reviews");
                fetched = reviews == null ? 0 : reviews.size();
                added = reviewService.saveScrapedReviews(shop.getId(), shop.getName(), reviews);
                shop.setLastScrapeTime(LocalDateTime.now());
                shopRepository.save(shop);
            }

            if (Boolean.TRUE.equals(shop.getAutoReplyEnabled())) {
                Map<String, Object> rr = replyExecutionService.autoReplyForShop(shop, OPERATOR);
                replied = (int) rr.getOrDefault("replied", 0);
                replyFailed = (int) rr.getOrDefault("failed", 0);
            }

            if (Boolean.TRUE.equals(shop.getAppealAutoEnabled())) {
                Map<String, Object> ap = appealService.autoRunForShop(shop, OPERATOR);
                if ("SHOP_NOT_FOUND".equals(ap.get("code"))) {
                    throw new ShopNotFoundException(String.valueOf(ap.get("message")));
                }
                appealCandidates = (int) ap.getOrDefault("candidates", 0);
                appealSubmitted = (int) ap.getOrDefault("submitted", 0);
            }

            Map<String, Object> ck = appealService.checkResultsForShop(shop);
            if ("SHOP_NOT_FOUND".equals(ck.get("code"))) {
                throw new ShopNotFoundException(String.valueOf(ck.get("message")));
            }
            appealUpdated = (int) ck.getOrDefault("updated", 0);

            if (Boolean.TRUE.equals(shop.getFoodSafetyAutoAppeal())) {
                Map<String, Object> sc = foodSafetyService.scrape(shop.getId(), OPERATOR);
                fsScraped = (int) sc.getOrDefault("added", 0);
                Map<String, Object> ap = foodSafetyService.autoAppeal(shop.getId(), OPERATOR);
                fsSubmitted = (int) ap.getOrDefault("submitted", 0);
                Map<String, Object> fsCk = foodSafetyService.checkStatus(shop.getId(), OPERATOR);
                fsChecked = (int) fsCk.getOrDefault("updated", 0);
            }

            shop.setLastAutomationTime(LocalDateTime.now());
            shopRepository.save(shop);

            long cost = System.currentTimeMillis() - start;
            boolean hasFailure = replyFailed > 0;
            String summary = String.format(
                    "触发:%s | 店铺:%s | 抓取:%s | 评价: 抓到%d 入库%d | 自动回复: 成功%d 失败%d | 申诉: 候选%d 提交%d 回查更新%d | 食安: 抓%d 申%d 回查%d | 耗时%d秒",
                    trigger, shop.getName(), scrapeSkipped ? "跳过(2小时内已抓取/已执行)" : "执行",
                    fetched, added, replied, replyFailed, appealCandidates, appealSubmitted, appealUpdated,
                    fsScraped, fsSubmitted, fsChecked, cost / 1000);
            if (hasFailure) {
                clientLogService.error("scheduler", OPERATOR, "单店自动化完成(部分失败)", summary);
            } else {
                clientLogService.info("scheduler", OPERATOR, "单店自动化完成", summary);
            }
            logger.info("[SCHEDULER] {}", summary);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", !hasFailure);
            result.put("resultType", hasFailure ? "PARTIAL" : "SUCCESS");
            result.put("message", summary);
            result.put("shopId", shop.getId());
            result.put("shopName", shop.getName());
            result.put("scrapeSkipped", scrapeSkipped);
            result.put("scrapeSkipReason", skipReason);
            result.put("reviewsFetched", fetched);
            result.put("reviewsAdded", added);
            result.put("replied", replied);
            result.put("replyFailed", replyFailed);
            result.put("appealCandidates", appealCandidates);
            result.put("appealSubmitted", appealSubmitted);
            result.put("appealStatusUpdated", appealUpdated);
            result.put("foodSafetyScraped", fsScraped);
            result.put("foodSafetySubmitted", fsSubmitted);
            result.put("foodSafetyChecked", fsChecked);
            return result;
        } catch (ShopNotFoundException sne) {
            String msg = "该店铺暂未寻到：" + shop.getName() + "，已停止本次单店自动化，请重新执行";
            clientLogService.error("scheduler", OPERATOR, "单店自动化中止(店铺未找到)", msg);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("code", "SHOP_NOT_FOUND");
            r.put("message", msg);
            return r;
        } catch (Exception e) {
            String msg = "店铺[" + shop.getName() + "]单店自动化失败: " + e.getMessage();
            clientLogService.error("scheduler", OPERATOR, "单店自动化失败", msg);
            logger.warn("[SCHEDULER] 单店自动化失败 shop={}: {}", shop.getId(), e.getMessage());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", msg);
            return r;
        }
    }

    private Map<String, Object> doRun(String trigger, Long userId) {
        long start = System.currentTimeMillis();
        logger.info("[SCHEDULER] 自动流水线开始({})...", trigger);
        List<Shop> shops = shopRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(s -> userId == null || s.getUserId().equals(userId))
                .toList();
        if (shops.isEmpty()) {
            clientLogService.info("scheduler", OPERATOR, "自动流水线跳过", "暂无绑定店铺");
            return Map.of("success", false, "resultType", "NO_SHOPS", "message", "暂无绑定店铺");
        }

        // 0. 浏览器前置检查：只复用已运行浏览器，未运行则跳过并记录
        boolean loggedIn;
        try {
            loggedIn = browserService.checkLogin();
        } catch (Exception e) {
            loggedIn = false;
        }
        if (!loggedIn) {
            String detail = "浏览器未连接或未登录美团，本次自动执行跳过。请启动程序并登录美团后再试。店铺数: " + shops.size();
            clientLogService.error("scheduler", OPERATOR, "自动流水线跳过", detail);
            return Map.of("success", false, "resultType", "SKIP_BROWSER", "message", detail);
        }

        // 1. 同步负反馈（全局一次）
        int negativeSaved = 0;
        String negativeMsg;
        try {
            Map<String, Object> neg = negativeFeedbackService.syncFromMeituan("");
            negativeSaved = neg.get("saved") == null ? 0 : (int) neg.get("saved");
            negativeMsg = String.valueOf(neg.get("message"));
        } catch (Exception e) {
            negativeMsg = "负反馈同步失败: " + e.getMessage();
        }

        // 2. 按执行顺序逐店闭环
        int shopOk = 0, shopFail = 0;
        int totalFetched = 0, totalAdded = 0, totalReplied = 0, totalReplyFailed = 0;
        int totalCandidates = 0, totalAppealSubmitted = 0, totalManualLeft = 0, totalAppealUpdated = 0;
        int fsScraped = 0, fsSubmitted = 0, fsChecked = 0;
        StringBuilder perShop = new StringBuilder();
        String missingShop = null;

        for (Shop shop : shops) {
            boolean shopAborted = false;
            try {
                // 2.1 抓取评价入库
                Map<String, Object> res = browserService.scrapeReviews(shop.getName());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reviews = (List<Map<String, Object>>) res.get("reviews");
                int fetched = reviews == null ? 0 : reviews.size();
                int added = reviewService.saveScrapedReviews(shop.getId(), shop.getName(), reviews);
                totalFetched += fetched;
                totalAdded += added;
                shop.setLastScrapeTime(LocalDateTime.now());
                shopRepository.save(shop);

                // 2.2 自动回复（开关开启时）
                if (Boolean.TRUE.equals(shop.getAutoReplyEnabled())) {
                    try {
                        Map<String, Object> rr = replyExecutionService.autoReplyForShop(shop, OPERATOR);
                        int replied = (int) rr.getOrDefault("replied", 0);
                        int replyFailed = (int) rr.getOrDefault("failed", 0);
                        totalReplied += replied;
                        totalReplyFailed += replyFailed;
                        perShop.append("[回复:成功").append(replied).append(" 失败").append(replyFailed).append("] ");
                    } catch (Exception re) {
                        totalReplyFailed++;
                        perShop.append("[回复失败] ");
                        logger.warn("[SCHEDULER] 店铺{}自动回复失败: {}", shop.getId(), re.getMessage());
                    }
                }

                // 2.3 差评自动申诉（开关开启时）
                if (Boolean.TRUE.equals(shop.getAppealAutoEnabled())) {
                    Map<String, Object> ap = appealService.autoRunForShop(shop, OPERATOR);
                    if ("SHOP_NOT_FOUND".equals(ap.get("code"))) {
                        throw new ShopNotFoundException(String.valueOf(ap.get("message")));
                    }
                    int candidates = (int) ap.getOrDefault("candidates", 0);
                    int submitted = (int) ap.getOrDefault("submitted", 0);
                    int manualLeft = (int) ap.getOrDefault("manualLeft", 0);
                    totalCandidates += candidates;
                    totalAppealSubmitted += submitted;
                    totalManualLeft += manualLeft;
                    perShop.append("[申诉:候选").append(candidates).append(" 提交").append(submitted)
                            .append(" 留人工").append(manualLeft).append("] ");
                }

                // 2.4 申诉回查（该店铺已提交的记录）
                Map<String, Object> ck = appealService.checkResultsForShop(shop);
                if ("SHOP_NOT_FOUND".equals(ck.get("code"))) {
                    throw new ShopNotFoundException(String.valueOf(ck.get("message")));
                }
                totalAppealUpdated += (int) ck.getOrDefault("updated", 0);

                // 2.5 食安险：抓取 → 自动申诉 → 回查（开关开启时）
                if (Boolean.TRUE.equals(shop.getFoodSafetyAutoAppeal())) {
                    Map<String, Object> sc = foodSafetyService.scrape(shop.getId(), OPERATOR);
                    fsScraped += (int) sc.getOrDefault("added", 0);
                    Map<String, Object> ap = foodSafetyService.autoAppeal(shop.getId(), OPERATOR);
                    fsSubmitted += (int) ap.getOrDefault("submitted", 0);
                    Map<String, Object> fsCk = foodSafetyService.checkStatus(shop.getId(), OPERATOR);
                    fsChecked += (int) fsCk.getOrDefault("updated", 0);
                    perShop.append("[食安:抓").append(sc.getOrDefault("added", 0))
                            .append(" 申").append(ap.getOrDefault("submitted", 0)).append("] ");
                }

                shopOk++;
                perShop.append(shop.getName()).append("(+").append(added).append(") ");
                shop.setLastAutomationTime(LocalDateTime.now());
                shopRepository.save(shop);
            } catch (ShopNotFoundException sne) {
                shopAborted = true;
                missingShop = shop.getName();
                logger.warn("[SCHEDULER] 店铺{}校验失败，中止整批: {}", shop.getId(), sne.getMessage());
            } catch (Exception e) {
                shopFail++;
                perShop.append(shop.getName()).append("(失败) ");
                logger.warn("[SCHEDULER] 店铺{}执行失败: {}", shop.getId(), e.getMessage());
            }

            if (shopAborted) {
                break;
            }
        }

        if (missingShop != null) {
            String abortMsg = "该店铺暂未寻到：" + missingShop + "，已停止本次自动执行（本次已完成 " + shopOk
                    + " 家店铺，抓到评价 " + totalFetched + " 条，提交申诉 " + (totalAppealSubmitted + fsSubmitted) + " 条），请重新执行";
            clientLogService.error("scheduler", OPERATOR, "自动流水线中止(店铺未找到)", abortMsg + " | 明细: " + perShop);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("code", "SHOP_NOT_FOUND");
            result.put("resultType", "PARTIAL");
            result.put("message", abortMsg);
            result.put("shopsOk", shopOk);
            result.put("shopsFail", shopFail);
            result.put("reviewsFetched", totalFetched);
            result.put("reviewsAdded", totalAdded);
            result.put("replied", totalReplied);
            result.put("appealSubmitted", totalAppealSubmitted + fsSubmitted);
            result.put("negativeSaved", negativeSaved);
            return result;
        }

        long cost = System.currentTimeMillis() - start;
        boolean hasFailure = shopFail > 0 || negativeMsg.startsWith("负反馈同步失败") || totalReplyFailed > 0;
        String summary = String.format(
                "触发:%s | 店铺: 成功%d 失败%d | 评价: 抓到%d 入库%d | 自动回复: 成功%d 失败%d | 申诉: 候选%d 提交%d 留人工%d 回查更新%d | 食安: 抓%d 申%d 回查%d | 负反馈: %s | 耗时%d秒",
                trigger, shopOk, shopFail, totalFetched, totalAdded, totalReplied, totalReplyFailed,
                totalCandidates, totalAppealSubmitted, totalManualLeft, totalAppealUpdated,
                fsScraped, fsSubmitted, fsChecked, negativeMsg, cost / 1000);
        if (hasFailure) {
            clientLogService.error("scheduler", OPERATOR, "自动流水线完成(部分失败)", summary + " | 明细: " + perShop);
        } else {
            clientLogService.info("scheduler", OPERATOR, "自动流水线完成", summary + " | 明细: " + perShop);
        }
        logger.info("[SCHEDULER] {}", summary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", !hasFailure);
        result.put("resultType", hasFailure ? "PARTIAL" : "SUCCESS");
        result.put("message", summary);
        result.put("shopsOk", shopOk);
        result.put("shopsFail", shopFail);
        result.put("reviewsFetched", totalFetched);
        result.put("reviewsAdded", totalAdded);
        result.put("replied", totalReplied);
        result.put("replyFailed", totalReplyFailed);
        result.put("negativeSaved", negativeSaved);
        result.put("appealCandidates", totalCandidates);
        result.put("appealSubmitted", totalAppealSubmitted);
        result.put("appealManualLeft", totalManualLeft);
        result.put("appealStatusUpdated", totalAppealUpdated);
        result.put("foodSafetyScraped", fsScraped);
        result.put("foodSafetySubmitted", fsSubmitted);
        result.put("foodSafetyChecked", fsChecked);
        return result;
    }
}
