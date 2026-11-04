package com.example.meituan.web.service;

import com.example.meituan.browser.BrowserAutomationService;
import com.example.meituan.web.entity.FoodSafetyAppeal;
import com.example.meituan.web.entity.ReplyTemplate;
import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.repository.FoodSafetyAppealRepository;
import com.example.meituan.web.repository.ReplyTemplateRepository;
import com.example.meituan.web.repository.ShopRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食安险申诉服务：抓取食安负反馈明细、自动申诉、状态回查
 * 与差评申诉/负反馈为独立数据域
 */
@Service
public class FoodSafetyService {

    @Autowired
    private FoodSafetyAppealRepository fsRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ReplyTemplateRepository templateRepository;

    @Autowired
    private BrowserAutomationService browserAutomationService;

    @Autowired
    private ClientLogService clientLogService;

    /** 美团申诉状态 → 中文 */
    public static String statusText(Integer mtStatus) {
        if (mtStatus == null) return "未知";
        switch (mtStatus) {
            case 10: return "待申诉";
            case 20: return "申诉中";
            case 30: return "申诉成功";
            case 3: return "申诉驳回";
            case 40: return "申诉失败";
            case 50: return "申诉超时";
            case 41: return "可再次申诉";
            default: return "状态" + mtStatus;
        }
    }

    /**
     * 抓取某店铺的食安负反馈明细并落库（按订单号去重）
     */
    public Map<String, Object> scrape(Long shopId, String operator) throws Exception {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("店铺不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode resp = browserAutomationService.scrapeFoodSafety(shop.getName());
        String type = resp.path("type").asText("");
        if ("error".equals(type)) {
            clientLogService.error("food_safety", operator, "食安抓取失败",
                    "店铺[" + shop.getName() + "] " + resp.path("message").asText(""));
            result.put("success", false);
            result.put("message", resp.path("message").asText("浏览器未登录或页面加载失败"));
            return result;
        }

        int added = 0, skipped = 0;
        for (JsonNode item : resp.path("items")) {
            String orderNo = item.path("orderNo").asText("");
            if (orderNo.isEmpty()) continue;
            if (fsRepository.findByOrderNo(orderNo).isPresent()) {
                skipped++;
                continue;
            }
            FoodSafetyAppeal fs = new FoodSafetyAppeal();
            fs.setUserId(shop.getUserId());
            fs.setShopId(shop.getId());
            fs.setShopName(shop.getName());
            fs.setOrderNo(orderNo);
            fs.setOrderTime(item.path("orderTime").asText(""));
            fs.setFeedbackChannel(item.path("feedbackChannel").asText(""));
            fs.setFeedbackType(item.path("feedbackType").asText(""));
            fs.setContent(item.path("content").asText(""));
            fs.setMtStatus(10); // 刚抓到且未申诉过 → 待申诉
            fsRepository.save(fs);
            added++;
        }
        // 回填美团门店ID：申诉/回查直接复用，避免每次临时探测失败
        String poiId = resp.path("poiId").asText("");
        if (!poiId.isEmpty() && !poiId.equals(shop.getPoiId())) {
            shop.setPoiId(poiId);
            shopRepository.save(shop);
        }
        String msg = "店铺[" + shop.getName() + "] 食安抓取: 新增" + added + "条，跳过已存在" + skipped + "条"
                + (resp.path("message").asText("").isEmpty() ? "" : " | " + resp.path("message").asText());
        clientLogService.info("food_safety", operator, "食安抓取", msg);
        result.put("success", true);
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("message", msg);
        return result;
    }

    /**
     * 自动申诉：对该店铺所有"待申诉(10)"的食安单，关键词模板匹配生成理由并逐单提交。
     * 未命中模板的单留待人工。
     */
    public Map<String, Object> autoAppeal(Long shopId, String operator) throws Exception {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("店铺不存在"));
        List<FoodSafetyAppeal> pending = fsRepository.findByShopIdAndMtStatus(shopId, 10);
        Map<String, Object> result = new LinkedHashMap<>();
        if (pending.isEmpty()) {
            result.put("success", true);
            result.put("submitted", 0);
            result.put("message", "没有待申诉的食安单");
            return result;
        }

        int submitted = 0, manualLeft = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (FoodSafetyAppeal fs : pending) {
            TemplateMatch match = pickTemplate(shop.getUserId(), fs);
            if (match == null) {
                manualLeft++;
                clientLogService.info("food_safety", operator, "食安申诉留人工",
                        "店铺[" + shop.getName() + "] 订单" + fs.getOrderNo()
                                + " 类型[" + fs.getFeedbackType() + "] 未命中任何关键词申诉模板，留待人工处理");
                continue;
            }
            Map<String, Object> submitRes = submitOne(shop, fs, match.reason, match.mark, operator);
            if (Boolean.TRUE.equals(submitRes.get("success"))) {
                submitted++;
            } else {
                failed++;
                errors.add(fs.getOrderNo() + ": " + submitRes.get("message"));
            }
        }
        String msg = "店铺[" + shop.getName() + "] 食安自动申诉: 提交" + submitted + "单，留人工" + manualLeft
                + "单，失败" + failed + "单";
        clientLogService.info("food_safety", operator, "食安自动申诉",
                msg + (errors.isEmpty() ? "" : " | " + String.join("; ", errors)));
        result.put("success", failed == 0);
        result.put("submitted", submitted);
        result.put("manualLeft", manualLeft);
        result.put("failed", failed);
        result.put("message", msg + (errors.isEmpty() ? "" : " | " + String.join("; ", errors)));
        return result;
    }

    /**
     * 手动申诉指定订单（用户页面输入理由，或指定模板）
     */
    public Map<String, Object> manualAppeal(Long shopId, String orderNo, String reason,
                                            String templateMark, String operator) throws Exception {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("店铺不存在"));
        FoodSafetyAppeal fs = fsRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
        String mark = (templateMark == null || templateMark.isEmpty()) ? "手动" : templateMark;
        return submitOne(shop, fs, reason, mark, operator);
    }

    /** 提交单个食安申诉并更新记录 */
    private Map<String, Object> submitOne(Shop shop, FoodSafetyAppeal fs,
                                          String reason, String templateMark, String operator) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode resp = browserAutomationService.appealFoodSafety(
                shop.getName(), List.of(fs.getOrderNo()), reason, shop.getPoiId());
        String type = resp.path("type").asText("");
        boolean ok = "food_safety_appeal_result".equals(type) && resp.path("success").asBoolean(false);
        if (ok) {
            fs.setMtStatus(20); // 申诉中
            fs.setAppealReason(reason);
            fs.setAppealTemplate(templateMark);
            fs.setAppealTime(LocalDateTime.now());
            fsRepository.save(fs);
            clientLogService.info("food_safety", operator, "食安申诉提交成功",
                    "店铺[" + shop.getName() + "] 订单" + fs.getOrderNo()
                            + " → 模板[" + templateMark + "] 理由[" + abbreviate(reason) + "]");
        } else {
            clientLogService.error("food_safety", operator, "食安申诉提交失败",
                    "店铺[" + shop.getName() + "] 订单" + fs.getOrderNo()
                            + " → " + resp.path("message").asText("未知错误"));
        }
        result.put("success", ok);
        result.put("message", ok ? "申诉提交成功" : resp.path("message").asText("提交失败"));
        return result;
    }

    /**
     * 状态回查：拦截 getOrdersWithStatus，更新每单的美团状态和审核意见
     */
    public Map<String, Object> checkStatus(Long shopId, String operator) throws Exception {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("店铺不存在"));
        JsonNode resp = browserAutomationService.checkFoodSafety(shop.getName(), shop.getPoiId());
        String type = resp.path("type").asText("");
        if ("error".equals(type)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", resp.path("message").asText("浏览器未登录或页面加载失败"));
            return r;
        }
        int updated = 0;
        List<String> changes = new ArrayList<>();
        for (JsonNode item : resp.path("items")) {
            String orderNo = item.path("orderNo").asText("");
            FoodSafetyAppeal fs = fsRepository.findByOrderNo(orderNo).orElse(null);
            if (fs == null) continue;
            Integer st = item.path("status").isNumber() ? item.path("status").asInt() : null;
            String remark = item.path("auditRemark").asText("");
            boolean changed = (st != null && !st.equals(fs.getMtStatus()))
                    || (!remark.isEmpty() && !remark.equals(fs.getAuditRemark()));
            if (st != null) fs.setMtStatus(st);
            if (!remark.isEmpty()) fs.setAuditRemark(remark);
            fs.setLastCheckTime(LocalDateTime.now());
            fsRepository.save(fs);
            if (changed) {
                updated++;
                changes.add("订单" + orderNo + " → " + statusText(st));
            }
        }
        String msg = "店铺[" + shop.getName() + "] 食安状态回查: 共"
                + resp.path("items").size() + "条，更新" + updated + "条"
                + (changes.isEmpty() ? "" : " | " + String.join("; ", changes));
        clientLogService.info("food_safety", operator, "食安状态回查", msg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("updated", updated);
        result.put("message", msg);
        return result;
    }

    /** 关键词模板匹配：评价内容/投诉类型包含模板关键词即命中，命中数最多者胜（仅匹配食安申诉模板） */
    private TemplateMatch pickTemplate(Long userId, FoodSafetyAppeal fs) {
        List<ReplyTemplate> templates = templateRepository.findByUserId(userId);
        String haystack = (fs.getContent() == null ? "" : fs.getContent()) + "|"
                + (fs.getFeedbackType() == null ? "" : fs.getFeedbackType());
        ReplyTemplate best = null;
        List<String> bestMatched = null;
        for (ReplyTemplate t : templates) {
            if (!"food_appeal".equals(t.getType()) || !"active".equals(t.getStatus())) continue;
            String kw = t.getKeywords();
            if (kw == null || kw.trim().isEmpty()) continue;
            List<String> hits = new ArrayList<>();
            for (String k : kw.split("[,，]")) {
                String kk = k.trim();
                if (!kk.isEmpty() && haystack.contains(kk)) hits.add(kk);
            }
            if (!hits.isEmpty() && (best == null || hits.size() > bestMatched.size())) {
                best = t;
                bestMatched = hits;
            }
        }
        if (best == null) return null;
        String reason = best.getContent();
        String mark = best.getName() + "(命中:" + String.join(",", bestMatched) + ")";
        return new TemplateMatch(reason, mark);
    }

    private static class TemplateMatch {
        final String reason;
        final String mark;
        TemplateMatch(String reason, String mark) { this.reason = reason; this.mark = mark; }
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
