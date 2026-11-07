package com.example.meituan.web.controller;

import com.example.meituan.browser.ShopNotFoundException;
import com.example.meituan.web.entity.FoodSafetyAppeal;
import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.FoodSafetyAppealRepository;
import com.example.meituan.web.repository.ShopRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ClientLogService;
import com.example.meituan.web.service.FoodSafetyService;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食安险申诉接口（独立数据域）
 */
@RestController
@RequestMapping("/api/food-safety")
public class FoodSafetyController {

    @Autowired
    private FoodSafetyAppealRepository fsRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private FoodSafetyService foodSafetyService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ClientLogService clientLogService;

    /** 列表：可选 shopId、mtStatus 筛选；管理员看全部 */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) Integer mtStatus,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            List<FoodSafetyAppeal> all;
            if (user.getRole() == User.UserRole.ADMIN) {
                all = shopId != null
                        ? fsRepository.findByShopIdOrderByCreatedAtDesc(shopId)
                        : fsRepository.findAll();
            } else {
                all = fsRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FoodSafetyAppeal fs : all) {
                if (mtStatus != null && !mtStatus.equals(fs.getMtStatus())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", fs.getId());
                m.put("shopId", fs.getShopId());
                m.put("shopName", fs.getShopName());
                m.put("orderNo", fs.getOrderNo());
                m.put("orderTime", fs.getOrderTime());
                m.put("feedbackChannel", fs.getFeedbackChannel());
                m.put("feedbackType", fs.getFeedbackType());
                m.put("content", fs.getContent());
                m.put("mtStatus", fs.getMtStatus());
                m.put("mtStatusText", FoodSafetyService.statusText(fs.getMtStatus()));
                m.put("appealReason", fs.getAppealReason());
                m.put("appealTemplate", fs.getAppealTemplate());
                m.put("hasUploadImages", fs.getHasUploadImages());
                m.put("auditRemark", fs.getAuditRemark());
                m.put("appealTime", fs.getAppealTime());
                m.put("lastCheckTime", fs.getLastCheckTime());
                m.put("createdAt", fs.getCreatedAt());
                rows.add(m);
            }
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /** 手动抓取某店铺的食安负反馈 */
    @PostMapping("/scrape/{shopId}")
    public ResponseEntity<Map<String, Object>> scrape(@PathVariable Long shopId,
                                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String username = currentUsername(authHeader);
            response.putAll(foodSafetyService.scrape(shopId, username));
        } catch (ShopNotFoundException e) {
            response.put("success", false);
            response.put("code", "SHOP_NOT_FOUND");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "抓取失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /** 自动申诉：对所有待申诉单按关键词模板提交 */
    @PostMapping("/appeal/{shopId}")
    public ResponseEntity<Map<String, Object>> autoAppeal(@PathVariable Long shopId,
                                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String username = currentUsername(authHeader);
            response.putAll(foodSafetyService.autoAppeal(shopId, username));
        } catch (ShopNotFoundException e) {
            response.put("success", false);
            response.put("code", "SHOP_NOT_FOUND");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "自动申诉失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /** 手动申诉指定订单 */
    @PostMapping("/appeal-manual")
    public ResponseEntity<Map<String, Object>> manualAppeal(@RequestBody Map<String, Object> request,
                                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String username = currentUsername(authHeader);
            Long shopId = Long.valueOf(String.valueOf(request.get("shopId")));
            String orderNo = String.valueOf(request.getOrDefault("orderNo", ""));
            String reason = String.valueOf(request.getOrDefault("reason", ""));
            String templateMark = String.valueOf(request.getOrDefault("templateMark", ""));
            if (reason.trim().length() < 20 || reason.trim().length() > 300) {
                response.put("success", false);
                response.put("message", "食安申诉理由须为20至300字");
                return ResponseEntity.ok(response);
            }
            response.putAll(foodSafetyService.manualAppeal(shopId, orderNo, reason.trim(), templateMark, username));
        } catch (ShopNotFoundException e) {
            response.put("success", false);
            response.put("code", "SHOP_NOT_FOUND");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "申诉失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /** 状态回查（手动触发，防风控） */
    @PostMapping("/check/{shopId}")
    public ResponseEntity<Map<String, Object>> check(@PathVariable Long shopId,
                                                     @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String username = currentUsername(authHeader);
            response.putAll(foodSafetyService.checkStatus(shopId, username));
        } catch (ShopNotFoundException e) {
            response.put("success", false);
            response.put("code", "SHOP_NOT_FOUND");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "回查失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /** 店铺开关：食安自动申诉 */
    @PostMapping("/toggle-auto/{shopId}")
    public ResponseEntity<Map<String, Object>> toggleAuto(@PathVariable Long shopId,
                                                          @RequestBody Map<String, Object> request,
                                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String username = currentUsername(authHeader);
            Shop shop = shopRepository.findById(shopId)
                    .orElseThrow(() -> new IllegalArgumentException("店铺不存在"));
            Boolean enabled = Boolean.parseBoolean(String.valueOf(request.get("enabled")));
            shop.setFoodSafetyAutoAppeal(enabled);
            shopRepository.save(shop);
            clientLogService.info("food_safety", username, "食安自动申诉开关",
                    "店铺[" + shop.getName() + "] → " + (enabled ? "开启" : "关闭"));
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    private String currentUsername(String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        return jwtUtil.extractUsername(token);
    }
}
