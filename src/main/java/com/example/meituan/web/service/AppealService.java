package com.example.meituan.web.service;

import com.example.meituan.browser.BrowserAutomationService;
import com.example.meituan.browser.ShopNotFoundException;
import com.example.meituan.web.entity.ReplyTemplate;
import com.example.meituan.web.entity.Review;
import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.repository.ReplyTemplateRepository;
import com.example.meituan.web.repository.ReviewRepository;
import com.example.meituan.web.repository.ShopRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 申诉服务：从 AppealController 抽取的核心执行逻辑，供控制器与定时任务共用
 */
@Service
public class AppealService {

    private static final String OPERATOR = "system";

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ReplyTemplateRepository templateRepository;

    @Autowired
    private ClientLogService clientLogService;

    @Autowired
    private BrowserAutomationService browserService;

    /**
     * 自动申诉（对标西瓜 shop_set_appeal 的执行侧）：
     * 遍历开启自动申诉的店铺，按 星级+范围(today/yesterday) 筛选差评，
     * 标记 pending 后逐条调用浏览器自动提交
     */
    public Map<String, Object> autoRunAll() {
        List<Shop> shops = shopRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(s -> Boolean.TRUE.equals(s.getAppealAutoEnabled()))
                .toList();
        return autoRunShops(shops, OPERATOR);
    }

    /**
     * 对单个店铺执行自动申诉（流水线逐店闭环调用）
     * 返回 code=SHOP_NOT_FOUND 表示店铺切换校验失败，调用方应立即中止整批
     */
    public Map<String, Object> autoRunForShop(Shop shop, String operator) {
        return autoRunShops(List.of(shop), operator);
    }

    private Map<String, Object> autoRunShops(List<Shop> shops, String operator) {
        Map<String, Object> resp = new HashMap<>();
        int submitted = 0;
        int candidates = 0;
        int manualLeft = 0;
        List<String> errors = new ArrayList<>();
        LocalDate today = LocalDate.now();
        String missingShop = null;
        for (Shop shop : shops) {
            Set<Integer> stars = new HashSet<>();
            for (String s : shop.getAppealStars().split(",")) {
                try { stars.add(Integer.parseInt(s.trim())); } catch (Exception ignore) {}
            }
            if (stars.isEmpty()) stars.add(1);
            LocalDate rangeStart = "yesterday".equals(shop.getAppealRange()) ? today.minusDays(1) : today;
            // 该店铺用户的申诉模板：存在带关键词的启用模板时进入关键词匹配模式（甲方需求）
            List<ReplyTemplate> kwAppealTpls = templateRepository.findByUserId(shop.getUserId()).stream()
                    .filter(t -> "appeal".equals(t.getType()) && "active".equals(t.getStatus())
                            && t.getKeywords() != null && !t.getKeywords().trim().isEmpty())
                    .toList();
            List<Review> list = reviewRepository.findAll((root, q, cb) -> cb.and(
                    cb.equal(root.get("shopId"), shop.getId()),
                    cb.equal(root.get("appealStatus"), "none"),
                    cb.equal(root.get("replyStatus"), "replied"),
                    root.get("rating").in(stars),
                    cb.between(root.get("reviewTime"),
                            rangeStart.atStartOfDay(), rangeStart.plusDays(1).atStartOfDay())));
            for (Review r : list) {
                candidates++;
                try {
                    if (!kwAppealTpls.isEmpty()) {
                        AppealMatch match = matchAppealTemplate(kwAppealTpls, r.getContent());
                        if (match == null) {
                            manualLeft++;
                            clientLogService.info("appeal", operator, "申诉留人工",
                                    "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId()
                                            + " 内容[" + abbreviate(r.getContent()) + "] 未命中任何关键词申诉模板，留待人工处理");
                            continue;
                        }
                        r.setAppealContent(match.template().getContent());
                        r.setAppealTemplate(match.template().getName() + "(命中:" + match.hitKeywords() + ")");
                        if (match.template().getImages() != null && !match.template().getImages().isEmpty()) {
                            r.setAppealImages(match.template().getImages());
                        }
                        clientLogService.info("appeal", operator, "申诉关键词命中",
                                "店铺[" + shop.getName() + "] 评价" + r.getPlatformReviewId()
                                        + " 命中关键词[" + match.hitKeywords() + "] → 申诉模板[" + match.template().getName() + "]");
                    } else {
                        r.setAppealContent("该评价与实际情况不符，我方已按规范出餐与配送，不存在评价所述问题，恳请平台核实。");
                        r.setAppealTemplate("通用话术");
                    }
                    r.setAppealStatus("pending");
                    reviewRepository.save(r);
                    Map<String, Object> sub = doSubmit(r);
                    if (Boolean.TRUE.equals(sub.get("success"))) {
                        submitted++;
                    } else {
                        errors.add(r.getId() + ": " + sub.get("message"));
                    }
                } catch (ShopNotFoundException ex) {
                    // 店铺切换/二次校验失败：立即中止整批自动申诉，避免操作到别的店铺
                    missingShop = shop.getName();
                    errors.add(missingShop + ": " + ex.getMessage());
                    break;
                } catch (Exception ex) {
                    errors.add(r.getId() + ": " + ex.getMessage());
                }
            }
            if (missingShop != null) {
                break;
            }
        }
        if (missingShop != null) {
            resp.put("success", false);
            resp.put("code", "SHOP_NOT_FOUND");
            resp.put("candidates", candidates);
            resp.put("submitted", submitted);
            resp.put("errors", errors);
            resp.put("message", "该店铺暂未寻到：" + missingShop
                    + "，已停止本次自动申诉（本次已提交 " + submitted + " 条），请重新执行");
            return resp;
        }
        resp.put("success", true);
        resp.put("candidates", candidates);
        resp.put("submitted", submitted);
        resp.put("manualLeft", manualLeft);
        resp.put("errors", errors);
        resp.put("message", String.format("自动申诉完成：候选 %d 条，成功提交 %d 条，未命中关键词留人工 %d 条",
                candidates, submitted, manualLeft));
        return resp;
    }

    /** 关键词申诉模板匹配结果 */
    private record AppealMatch(ReplyTemplate template, String hitKeywords) {}

    /**
     * 按评价内容匹配关键词申诉模板：内容包含关键词即候选，命中关键词数最多者优先（同数取先配置的）
     * @return 未命中任何关键词返回 null（留待人工处理）
     */
    private AppealMatch matchAppealTemplate(List<ReplyTemplate> kwTpls, String reviewContent) {
        String text = reviewContent == null ? "" : reviewContent;
        ReplyTemplate best = null;
        int bestHits = 0;
        String bestMatched = null;
        for (ReplyTemplate t : kwTpls) {
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
        return best == null ? null : new AppealMatch(best, bestMatched);
    }

    private String abbreviate(String s) {
        return s == null ? "" : (s.length() > 120 ? s.substring(0, 120) + "..." : s);
    }

    /**
     * 回查申诉结果：按店铺执行顺序逐个店铺回查 submitted 状态的申诉
     */
    public Map<String, Object> checkResultsAll() {
        List<Shop> shops = shopRepository.findAllByOrderBySortOrderAscIdAsc();
        int updated = 0;
        for (Shop shop : shops) {
            Map<String, Object> one = checkResultsForShop(shop);
            updated += (int) one.getOrDefault("updated", 0);
            if ("SHOP_NOT_FOUND".equals(one.get("code"))) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("code", "SHOP_NOT_FOUND");
                resp.put("updated", updated);
                resp.put("message", one.get("message"));
                return resp;
            }
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("updated", updated);
        resp.put("message", "回查完成，更新 " + updated + " 条");
        return resp;
    }

    /**
     * 单店回查申诉结果（流水线逐店闭环调用）
     * 返回 code=SHOP_NOT_FOUND 表示店铺切换校验失败，调用方应立即中止整批
     */
    public Map<String, Object> checkResultsForShop(Shop shop) {
        Map<String, Object> resp = new HashMap<>();
        List<Review> submittedList = reviewRepository.findByAppealStatus("submitted").stream()
                .filter(r -> shop.getId().equals(r.getShopId()))
                .toList();
        int updated = 0;
        for (Review r : submittedList) {
            try {
                Map<String, Object> args = new HashMap<>();
                args.put("shopName", r.getShopName());
                args.put("reviewer", r.getReviewer());
                JsonNode res = browserService.checkAppealResult(args);
                String status = res.path("status").asText("");
                if ("approved".equals(status) || "rejected".equals(status)) {
                    r.setAppealStatus(status);
                    r.setAppealResultTime(LocalDateTime.now());
                    r.setAppealResultNote(res.path("note").asText(""));
                    reviewRepository.save(r);
                    updated++;
                }
            } catch (ShopNotFoundException e) {
                // 店铺未找到：中止本店回查，由调用方决定是否中止整批
                resp.put("success", false);
                resp.put("code", "SHOP_NOT_FOUND");
                resp.put("updated", updated);
                resp.put("message", e.getMessage() + "，已停止本次回查");
                return resp;
            } catch (Exception ignore) {}
        }
        resp.put("success", true);
        resp.put("updated", updated);
        resp.put("message", "店铺[" + shop.getName() + "] 回查更新 " + updated + " 条");
        return resp;
    }

    /** 复用的申诉提交逻辑（供单条提交与自动申诉共用） */
    public Map<String, Object> doSubmit(Review r) throws Exception {
        String absImages = "";
        if (r.getAppealImages() != null && !r.getAppealImages().isEmpty()) {
            List<String> abs = new ArrayList<>();
            for (String p : r.getAppealImages().split(",")) {
                String trimmed = p.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("/uploads/")) {
                    abs.add(new File("data" + trimmed.substring("/uploads".length())).getAbsolutePath());
                } else {
                    abs.add(new File(trimmed).getAbsolutePath());
                }
            }
            absImages = String.join(",", abs);
        }
        Map<String, Object> args = new HashMap<>();
        args.put("shopName", r.getShopName());
        args.put("reviewer", r.getReviewer());
        args.put("content", r.getContent());
        args.put("appealContent", r.getAppealContent() == null ? "" : r.getAppealContent());
        args.put("images", absImages);

        JsonNode result = browserService.runAppeal(args);
        String type = result.path("type").asText();
        Map<String, Object> out = new HashMap<>();
        if ("appeal_done".equals(type) || "appeal_success".equals(type)) {
            r.setAppealStatus("submitted");
            r.setAppealTime(LocalDateTime.now());
            reviewRepository.save(r);
            out.put("success", true);
            out.put("message", "申诉已提交");
        } else {
            out.put("success", false);
            out.put("message", result.path("message").asText("申诉执行失败"));
        }
        return out;
    }
}
