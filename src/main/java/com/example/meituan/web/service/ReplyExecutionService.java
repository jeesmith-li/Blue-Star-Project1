package com.example.meituan.web.service;

import com.example.meituan.browser.BrowserAutomationService;
import com.example.meituan.web.entity.ReplyTemplate;
import com.example.meituan.web.entity.Review;
import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.repository.ReplyTemplateRepository;
import com.example.meituan.web.repository.ReviewRepository;
import com.example.meituan.web.repository.ShopRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 评价回复执行服务（对标西瓜全自动回复模式）
 * 抓取到未回复评价 → 按好/中/差评档位选模板 → 接口直调批量回复 → 逐条落库 + 审计日志（对标西瓜 reply_log）
 * 单批上限 20 条；连续 3 条失败即停批次；AtomicBoolean 防重叠
 */
@Service
public class ReplyExecutionService {

    /** 单批回复上限（防风控/频控） */
    private static final int MAX_BATCH = 20;
    /** 连续失败即停批次 */
    private static final int MAX_CONSECUTIVE_FAIL = 3;

    private final java.util.concurrent.atomic.AtomicBoolean batchRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** /stop 中断标志：批内每条回复前检查 */
    private volatile boolean stopRequested = false;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReplyTemplateRepository templateRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private BrowserAutomationService browserService;

    @Autowired
    private ClientLogService clientLogService;

    @Autowired
    private com.example.meituan.web.service.ReviewService reviewService;

    // ==================== 公开入口 ====================

    /**
     * 自动回复某店铺的全部未回复评价（定时任务抓取后 / 自动化页手动触发）
     * @return {total, replied, failed, skipped, message}
     */
    public Map<String, Object> autoReplyForShop(Shop shop, String operator) {
        Map<String, Object> resp = new LinkedHashMap<>();
        long unreplied = reviewRepository.countByShopIdAndReplyStatus(shop.getId(), "unreplied");
        if (unreplied == 0) {
            resp.put("total", 0);
            resp.put("replied", 0);
            resp.put("failed", 0);
            resp.put("skipped", 0);
            resp.put("message", "店铺[" + shop.getName() + "]暂无未回复评价");
            return resp;
        }

        // 查未回复评价（复用分页查询方法取前 MAX_BATCH 条）
        List<Review> targets = new ArrayList<>();
        reviewRepository.findByShopIdAndReplyStatus(shop.getId(), "unreplied",
                        org.springframework.data.domain.PageRequest.of(0, MAX_BATCH))
                .forEach(targets::add);
        return replyBatch(shop, targets, operator, "自动回复");
    }

    /**
     * 自动化页手动触发：即时抓取该店铺评价入库，再自动回复一轮
     * @return {success, message, replied, failed, fetched, added}
     */
    public Map<String, Object> runForShopNow(Long shopId, String operator) {
        Shop shop = shopRepository.findById(shopId).orElse(null);
        if (shop == null) {
            return Map.of("success", false, "message", "店铺不存在");
        }
        long start = System.currentTimeMillis();
        // 1. 即时抓取评价入库（复用每日流水线的抓取+保存逻辑）
        int fetched = 0, added = 0;
        try {
            Map<String, Object> res = browserService.scrapeReviews(shop.getName());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reviews = (List<Map<String, Object>>) res.get("reviews");
            fetched = reviews == null ? 0 : reviews.size();
            added = reviewService.saveScrapedReviews(shop.getId(), shop.getName(), reviews);
        } catch (Exception e) {
            clientLogService.error("reply", operator, "即时抓取失败",
                    "店铺[" + shop.getName() + "] 抓取评价失败: " + e.getMessage());
            return Map.of("success", false, "message", "抓取评价失败: " + e.getMessage());
        }

        // 2. 自动回复
        Map<String, Object> reply = autoReplyForShop(shop, operator);
        long cost = System.currentTimeMillis() - start;
        int replied = (int) reply.getOrDefault("replied", 0);
        int failed = (int) reply.getOrDefault("failed", 0);
        boolean ok = failed == 0;
        String summary = String.format("店铺[%s] 抓取%d条 入库%d条 | 回复: 成功%d 失败%d 跳过%s | 耗时%d秒",
                shop.getName(), fetched, added, replied, failed,
                String.valueOf(reply.getOrDefault("skipped", 0)), cost / 1000);
        if (ok) {
            clientLogService.info("reply", operator, "自动化回复完成", summary);
        } else {
            clientLogService.error("reply", operator, "自动化回复完成(部分失败)", summary);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", ok);
        resp.put("message", summary);
        resp.put("fetched", fetched);
        resp.put("added", added);
        resp.put("replied", replied);
        resp.put("failed", failed);
        resp.put("skipped", reply.getOrDefault("skipped", 0));
        resp.put("results", reply.getOrDefault("results", List.of()));
        return resp;
    }

    /**
     * 单条回复并发送到美团（评价页"同时发送到美团"）
     * @return {success, message}
     */
    public Map<String, Object> replySingle(Review r, String content, String operator) {
        Shop shop = shopRepository.findById(r.getShopId()).orElse(null);
        if (shop == null) {
            return Map.of("success", false, "message", "评价所属店铺不存在");
        }
        // 单条也走批次锁（与自动批次互斥），模板内容用传入文案而非模板
        return replySingleInternal(shop, r, content, operator);
    }

    private Map<String, Object> replySingleInternal(Shop shop, Review r, String content, String operator) {
        if (!batchRunning.compareAndSet(false, true)) {
            return Map.of("success", false, "message", "自动回复批次正在执行中，请稍后再试");
        }
        stopRequested = false;
        try {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("platformReviewId", r.getPlatformReviewId());
            item.put("rating", r.getRating());
            item.put("content", r.getContent());
            item.put("replyText", content);
            try {
                JsonNode respNode = browserService.replyReviews(shop.getName(), List.of(item));
                String type = respNode.path("type").asText("");
                if (!"reply_done".equals(type)) {
                    throw new IllegalStateException(respNode.path("message").asText("回复命令失败: " + type));
                }
                JsonNode first = respNode.path("results").path(0);
                boolean ok = first.path("success").asBoolean(false);
                String msg = first.path("message").asText("");
                if (ok) {
                    clientLogService.info("reply", operator, "手动回复成功",
                            "店铺[" + shop.getName() + "] 评价人[" + r.getReviewer() + "] 评价"
                                    + r.getPlatformReviewId() + " 已回复: " + abbreviate(content));
                    return Map.of("success", true, "message", msg.isEmpty() ? "回复成功" : msg);
                }
                clientLogService.error("reply", operator, "手动回复失败",
                        "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId() + " 回复失败: " + msg);
                return Map.of("success", false, "message", msg.isEmpty() ? "回复失败" : msg);
            } catch (Exception e) {
                clientLogService.error("reply", operator, "手动回复失败",
                        "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId() + " 回复异常: " + e.getMessage());
                return Map.of("success", false, "message", e.getMessage());
            }
        } finally {
            batchRunning.set(false);
        }
    }

    /** 中断当前回复批次（自动化页 /stop） */
    public void stop() {
        stopRequested = true;
    }

    public boolean isRunning() {
        return batchRunning.get();
    }

    // ==================== 批量执行核心 ====================

    /**
     * 对一批评价执行自动回复：选模板 → 接口直调 → 逐条落库+审计
     */
    private Map<String, Object> replyBatch(Shop shop, List<Review> targets, String operator, String scene) {
        if (!batchRunning.compareAndSet(false, true)) {
            clientLogService.info("reply", operator, scene + "跳过", "上一次回复批次仍在执行中");
            return Map.of("total", targets.size(), "replied", 0, "failed", 0,
                    "skipped", targets.size(), "message", "上一次回复批次仍在执行中");
        }
        stopRequested = false;
        try {
            return doReplyBatchProtected(shop, targets, operator, scene);
        } finally {
            batchRunning.set(false);
        }
    }

    private Map<String, Object> doReplyBatchProtected(Shop shop, List<Review> targets, String operator, String scene) {
        int replied = 0, failed = 0, skipped = 0, consecutiveFail = 0;
        List<Map<String, Object>> detailList = new ArrayList<>();

        // 1. 逐条选模板，组 items
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Review> idToReview = new LinkedHashMap<>();
        Map<String, String> idToTemplate = new LinkedHashMap<>();
        for (Review r : targets) {
            if (items.size() >= MAX_BATCH) { skipped++; continue; }
            TemplatePick pick = pickTemplateSmart(shop.getUserId(), r.getContent(), r.getRating());
            if (pick.content() == null) {
                skipped++;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("platformReviewId", r.getPlatformReviewId());
                d.put("reviewer", r.getReviewer());
                d.put("rating", r.getRating());
                d.put("success", false);
                d.put("message", pick.keywordMode() ? "未命中任何关键词模板，留待人工处理" : "无匹配档位模板且无通用模板，已跳过");
                detailList.add(d);
                if (pick.keywordMode()) {
                    clientLogService.info("reply", operator, scene + "跳过(未命中关键词)",
                            "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId()
                                    + "(" + r.getRating() + "星) 内容[" + abbreviate(r.getContent())
                                    + "] 未命中任何关键词模板，留待人工处理");
                } else {
                    clientLogService.error("reply", operator, scene + "跳过(无模板)",
                            "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId()
                                    + "(" + r.getRating() + "星) 无匹配档位模板且无通用模板，已跳过");
                }
                continue;
            }
            if (pick.keywordMode()) {
                clientLogService.info("reply", operator, scene + "关键词命中",
                        "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId()
                                + " 命中关键词[" + pick.hitKeywords() + "] → 模板[" + pick.templateName() + "]");
            }
            String tpl = pick.content();
            String mark = pick.keywordMode()
                    ? pick.templateName() + "(命中:" + pick.hitKeywords() + ")"
                    : String.valueOf(pick.templateName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("platformReviewId", r.getPlatformReviewId());
            item.put("rating", r.getRating());
            item.put("content", r.getContent());
            item.put("replyText", tpl);
            items.add(item);
            idToReview.put(r.getPlatformReviewId(), r);
            idToTemplate.put(r.getPlatformReviewId(), mark);
        }

        if (items.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("total", targets.size());
            resp.put("replied", 0);
            resp.put("failed", 0);
            resp.put("skipped", skipped);
            resp.put("results", detailList);
            resp.put("message", "无可回复评价（均无匹配模板或已达单批上限）");
            return resp;
        }

        // 2. 一次批量调用 Python RPA（OCR 定位 + CDP 键鼠，监听真实接口响应判成败）
        List<Map<String, Object>> results;
        try {
            JsonNode respNode = browserService.replyReviews(shop.getName(), items);
            String type = respNode.path("type").asText("");
            if (!"reply_done".equals(type)) {
                throw new IllegalStateException(respNode.path("message").asText("回复命令失败: " + type));
            }
            results = new ArrayList<>();
            JsonNode arr = respNode.path("results");
            if (arr.isArray()) {
                arr.forEach(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("platformReviewId", n.path("platformReviewId").asText(""));
                    m.put("success", n.path("success").asBoolean(false));
                    m.put("message", n.path("message").asText(""));
                    results.add(m);
                });
            }
        } catch (Exception e) {
            // 整批失败：全部计失败
            clientLogService.error("reply", operator, scene + "整批失败",
                    "店铺[" + shop.getName() + "] " + items.size() + "条回复失败: " + e.getMessage());
            for (Review r : idToReview.values()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("platformReviewId", r.getPlatformReviewId());
                d.put("reviewer", r.getReviewer());
                d.put("rating", r.getRating());
                d.put("success", false);
                d.put("message", e.getMessage());
                detailList.add(d);
                clientLogService.error("reply", operator, scene + "失败",
                        "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId()
                                + " 回复失败: " + e.getMessage());
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("total", targets.size());
            resp.put("replied", 0);
            resp.put("failed", items.size());
            resp.put("skipped", skipped);
            resp.put("results", detailList);
            resp.put("message", "回复失败: " + e.getMessage());
            return resp;
        }

        // 3. 逐条按真实结果落库 + 审计（对标西瓜 reply_log：谁/哪条/回复内容/时间）
        for (Map<String, Object> result : results) {
            String prid = String.valueOf(result.get("platformReviewId"));
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            String msg = String.valueOf(result.getOrDefault("message", ""));
            Review r = idToReview.get(prid);
            if (r == null) continue;

            if (stopRequested) {
                skipped++;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("platformReviewId", prid);
                d.put("reviewer", r.getReviewer());
                d.put("rating", r.getRating());
                d.put("success", false);
                d.put("message", "已停止，跳过本条");
                detailList.add(d);
                continue;
            }

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("platformReviewId", prid);
            d.put("reviewer", r.getReviewer());
            d.put("rating", r.getRating());
            d.put("success", ok);
            d.put("message", msg);
            detailList.add(d);

            if (ok) {
                r.setReplyStatus("replied");
                r.setReplyContent(findReplyText(items, prid));
                r.setReplyTime(LocalDateTime.now());
                r.setReplyTemplate(idToTemplate.get(prid));
                reviewRepository.save(r);
                replied++;
                consecutiveFail = 0;
                clientLogService.info("reply", operator, scene + "成功",
                        "店铺[" + shop.getName() + "] 评价人[" + r.getReviewer() + "] 评价" + prid
                                + " 已回复: " + abbreviate(findReplyText(items, prid)));
            } else {
                failed++;
                consecutiveFail++;
                clientLogService.error("reply", operator, scene + "失败",
                        "店铺[" + shop.getName() + "] 评价" + prid + " 回复失败: " + msg);
                if (consecutiveFail >= MAX_CONSECUTIVE_FAIL) {
                    clientLogService.error("reply", operator, scene + "中止",
                            "连续失败" + MAX_CONSECUTIVE_FAIL + "条，中止本店铺剩余回复（防风控）");
                    break;
                }
            }
        }

        int total = targets.size();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("replied", replied);
        resp.put("failed", failed);
        resp.put("skipped", skipped);
        resp.put("results", detailList);
        resp.put("message", "回复: 成功" + replied + " 失败" + failed + " 跳过" + skipped);
        return resp;
    }

    /** 模板选择结果：content=null 表示未选中；keywordMode=true 表示关键词模式未命中而留待人工 */
    private record TemplatePick(String content, String templateName, String hitKeywords, boolean keywordMode) {}

    /**
     * 智能选模板（甲方需求：按评价内容关键词调用对应名称的模板）：
     * 1. 用户配置了带关键词的 reply 模板 → 按"评价内容包含关键词"匹配，命中关键词数最多者优先（同数取先配置的）；
     *    未命中任何关键词 → 返回 keywordMode=true 且 content=null，留待人工处理（不走星级兜底）
     * 2. 用户没有任何关键词模板 → 维持原星级档位逻辑（向后兼容）
     */
    private TemplatePick pickTemplateSmart(Long userId, String reviewContent, Integer rating) {
        List<ReplyTemplate> all = templateRepository.findByUserId(userId);
        List<ReplyTemplate> kwList = new ArrayList<>();
        for (ReplyTemplate t : all) {
            if ("reply".equals(t.getType()) && "active".equals(t.getStatus())
                    && t.getKeywords() != null && !t.getKeywords().trim().isEmpty()) {
                kwList.add(t);
            }
        }
        if (kwList.isEmpty()) {
            ReplyTemplate star = pickStarTemplate(userId, rating);
            return new TemplatePick(star == null ? null : star.getContent(),
                    star == null ? null : star.getName(), null, false);
        }
        String text = reviewContent == null ? "" : reviewContent;
        ReplyTemplate best = null;
        int bestHits = 0;
        String bestMatched = null;
        for (ReplyTemplate t : kwList) {
            List<String> matched = new ArrayList<>();
            for (String kw : t.getKeywords().split("[,，]")) {
                String k = kw.trim();
                if (!k.isEmpty() && text.contains(k)) {
                    matched.add(k);
                }
            }
            if (matched.size() > bestHits) {
                bestHits = matched.size();
                best = t;
                bestMatched = String.join(",", matched);
            }
        }
        if (best == null) {
            return new TemplatePick(null, null, null, true);
        }
        return new TemplatePick(best.getContent(), best.getName(), bestMatched, true);
    }

    /**
     * 按星级选模板：≤2→bad、==3→middle、≥4→good；该档无启用模板回退通用（starLevel 为空）
     * @return 命中的模板对象，无可用模板返回 null
     */
    private ReplyTemplate pickStarTemplate(Long shopUserId, Integer rating) {
        if (rating == null) rating = 0;
        String level = rating <= 2 ? "bad" : (rating == 3 ? "middle" : "good");
        List<ReplyTemplate> all = templateRepository.findByUserId(shopUserId);
        for (ReplyTemplate t : all) {
            if ("reply".equals(t.getType()) && "active".equals(t.getStatus()) && level.equals(t.getStarLevel())) {
                return t;
            }
        }
        for (ReplyTemplate t : all) {
            if ("reply".equals(t.getType()) && "active".equals(t.getStatus())
                    && (t.getStarLevel() == null || t.getStarLevel().isEmpty())) {
                return t;
            }
        }
        return null;
    }

    private String findReplyText(List<Map<String, Object>> items, String platformReviewId) {
        for (Map<String, Object> item : items) {
            if (platformReviewId.equals(String.valueOf(item.get("platformReviewId")))) {
                return String.valueOf(item.get("replyText"));
            }
        }
        return "";
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
