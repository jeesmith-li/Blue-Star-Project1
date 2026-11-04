package com.example.meituan.web.service;

import com.example.meituan.browser.BrowserAutomationService;
import com.example.meituan.web.entity.NegativeFeedback;
import com.example.meituan.web.repository.NegativeFeedbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import com.example.meituan.util.TimeFormats;
import java.util.HashMap;
import java.util.Map;

/**
 * 负反馈服务：从 NegativeFeedbackController 抽取的同步逻辑，供控制器与定时任务共用
 */
@Service
public class NegativeFeedbackService {

    @Autowired
    private NegativeFeedbackRepository feedbackRepository;

    @Autowired
    private BrowserAutomationService browserService;

    /**
     * 同步负反馈：浏览器自动化进入美团负反馈页面抓取并入库
     * @param shopName 服务商多店时切换店铺，空=当前店铺
     */
    public Map<String, Object> syncFromMeituan(String shopName) throws java.io.IOException {
        Map<String, Object> resp = new HashMap<>();
        Map<String, Object> args = new HashMap<>();
        args.put("shopName", shopName == null ? "" : shopName);

        JsonNode result = browserService.scrapeNegativeFeedbacks(args);
        String type = result.path("type").asText("");
        if ("negative_data".equals(type)) {
            JsonNode rows = result.path("rows");
            int saved = 0;
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    try {
                        String orderNo = row.path("orderNo").asText("");
                        String channel = row.path("channelName").asText("");
                        String name = row.path("shopName").asText(shopName);
                        Long shopId = row.hasNonNull("shopId") ? row.path("shopId").asLong() : 0L;
                        // 去重：店铺+订单号+渠道 相同则跳过
                        if (!orderNo.isEmpty() && channel != null
                                && feedbackRepository.findByShopIdAndOrderNoAndChannelName(shopId, orderNo, channel).isPresent()) {
                            continue;
                        }
                        NegativeFeedback f = new NegativeFeedback();
                        f.setShopId(shopId);
                        f.setShopName(name);
                        f.setPlatform("meituan");
                        f.setOrderNo(orderNo);
                        f.setChannelName(channel);
                        f.setIndicatorName(row.path("indicatorName").asText(""));
                        f.setContent(row.path("content").asText(""));
                        f.setFeedbackTime(parseTime(row.path("feedbackTime").asText("")));
                        f.setStatus(mapStatus(row.path("status").asText("")));
                        if (row.has("orderData")) {
                            f.setOrderData(row.path("orderData").toString());
                        }
                        feedbackRepository.save(f);
                        saved++;
                    } catch (Exception ignore) {}
                }
            }
            resp.put("success", true);
            resp.put("saved", saved);
            resp.put("message", "同步完成，新增 " + saved + " 条负反馈");
        } else {
            resp.put("success", false);
            resp.put("message", result.path("message").asText("负反馈抓取失败"));
        }
        return resp;
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isEmpty()) return LocalDateTime.now();
        try {
            if (s.matches("\\d{10,13}")) {
                long ts = Long.parseLong(s);
                if (s.length() == 13) ts /= 1000;
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(ts),
                        java.time.ZoneId.systemDefault());
            }
            return LocalDateTime.parse(s, TimeFormats.DATETIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /** 美团返回的中文/数字状态统一映射为 pending/success/fail */
    private String mapStatus(String s) {
        if (s == null) return "pending";
        if (s.contains("成功") || s.equals("30")) return "success";
        if (s.contains("失败") || s.contains("驳回") || s.equals("20")) return "fail";
        return "pending";
    }
}
