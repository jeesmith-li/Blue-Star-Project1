package com.example.meituan.web.controller;

import com.example.meituan.web.entity.NegativeFeedback;
import com.example.meituan.web.repository.NegativeFeedbackRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 负反馈控制器（对标西瓜系统 /negative 页）
 * 负反馈 = 顾客通过美团反馈渠道发起的投诉，独立于差评申诉的数据域
 */
@RestController
@RequestMapping("/api/negative-feedbacks")
public class NegativeFeedbackController {

    @Autowired
    private NegativeFeedbackRepository feedbackRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.example.meituan.web.service.NegativeFeedbackService negativeFeedbackService;

    private void requireUser(String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        authService.getCurrentUser(jwtUtil.extractUsername(token));
    }

    /**
     * 负反馈列表
     * query: shopName, status(pending/success/fail，空=全部), page(1起), pageSize
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "") String shopName,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            requireUser(authHeader);
            List<NegativeFeedback> all = feedbackRepository.findAllByOrderByIdDesc();
            List<NegativeFeedback> filtered = all.stream()
                    .filter(f -> shopName == null || shopName.isEmpty()
                            || (f.getShopName() != null && f.getShopName().contains(shopName)))
                    .filter(f -> status == null || status.isEmpty() || status.equals(f.getStatus()))
                    .toList();

            int total = filtered.size();
            int from = Math.max(0, (page - 1) * pageSize);
            int to = Math.min(total, from + pageSize);
            List<Map<String, Object>> items = new ArrayList<>();
            for (NegativeFeedback f : filtered.subList(from, to)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("shopId", f.getShopId());
                m.put("shopName", f.getShopName());
                m.put("orderNo", f.getOrderNo());
                m.put("channelName", f.getChannelName());
                m.put("indicatorName", f.getIndicatorName());
                m.put("content", f.getContent());
                m.put("feedbackTime", f.getFeedbackTime() == null ? null : f.getFeedbackTime().toString());
                m.put("status", f.getStatus());
                m.put("orderData", f.getOrderData());
                items.add(m);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("total", total);
            resp.put("page", page);
            resp.put("pageSize", pageSize);
            resp.put("items", items);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("total", 0);
            resp.put("items", List.of());
            resp.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 同步负反馈：浏览器自动化进入美团负反馈页面抓取
     * body: { shopName? }（服务商多店时切换店铺）
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            requireUser(authHeader);
            String shopName = body == null ? "" : String.valueOf(body.getOrDefault("shopName", ""));
            return ResponseEntity.ok(negativeFeedbackService.syncFromMeituan(shopName));
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "同步失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /** 手动标记状态 body: { status: pending|success|fail } */
    @PostMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> setStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            requireUser(authHeader);
            NegativeFeedback f = feedbackRepository.findById(id).orElse(null);
            if (f == null) {
                resp.put("success", false);
                resp.put("message", "记录不存在");
                return ResponseEntity.status(404).body(resp);
            }
            String status = String.valueOf(body.getOrDefault("status", ""));
            if (!List.of("pending", "success", "fail").contains(status)) {
                resp.put("success", false);
                resp.put("message", "无效状态");
                return ResponseEntity.badRequest().body(resp);
            }
            f.setStatus(status);
            feedbackRepository.save(f);
            resp.put("success", true);
            resp.put("message", "已更新");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "更新失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }
}
