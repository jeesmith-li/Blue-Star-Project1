package com.example.meituan.web.controller;

import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ShopService;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ShopController {

    @Autowired
    private ShopService shopService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping("/shops")
    public ResponseEntity<List<Shop>> getShops(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            List<Shop> shops = shopService.getShopsForUser(user);
            return ResponseEntity.ok(shops);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 调整店铺执行顺序（body: { ids: [店铺ID, ...] }，按数组顺序重排）
     */
    @PostMapping("/shops/reorder")
    public ResponseEntity<Map<String, Object>> reorderShops(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            Object raw = body.get("ids");
            if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
                response.put("success", false);
                response.put("message", "缺少店铺顺序参数");
                return ResponseEntity.badRequest().body(response);
            }
            List<Long> ids = new java.util.ArrayList<>();
            for (Object o : rawList) {
                try {
                    ids.add(Long.parseLong(String.valueOf(o)));
                } catch (Exception e) {
                    response.put("success", false);
                    response.put("message", "店铺顺序参数格式错误");
                    return ResponseEntity.badRequest().body(response);
                }
            }
            if (!shopService.reorderShops(user, ids)) {
                response.put("success", false);
                response.put("message", "店铺不存在或无权调整该店铺");
                return ResponseEntity.status(403).body(response);
            }
            response.put("success", true);
            response.put("message", "店铺执行顺序已保存");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/shops")
    public ResponseEntity<Map<String, Object>> createShop(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            String name = (String) request.get("name");
            String meituanAccount = (String) request.get("meituanAccount");
            String shopType = (String) request.get("shopType");

            if (name == null || name.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "店铺名称不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            if (meituanAccount == null || meituanAccount.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "美团账号不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            if (shopType == null || shopType.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "店铺类型不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            Shop shop = new Shop();
            shop.setName(name);
            shop.setMeituanAccount(meituanAccount);
            shop.setShopType(shopType);
            shop.setUserId(user.getId());

            Shop savedShop = shopService.createShop(shop);

            response.put("success", true);
            response.put("message", "店铺添加成功");
            response.put("data", savedShop);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "添加失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 自动申诉设定（对标西瓜 shop_set_appeal）
     * body: { appealAutoEnabled: bool, appealStars: "1,2", appealRange: "today"|"yesterday" }
     */
    @PutMapping("/shops/{id}/appeal-settings")
    public ResponseEntity<Map<String, Object>> updateAppealSettings(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            Shop shop = shopService.getShopById(id);
            if (shop == null) {
                response.put("success", false);
                response.put("message", "店铺不存在");
                return ResponseEntity.status(404).body(response);
            }
            if (!shop.getUserId().equals(user.getId()) && !user.getRole().equals(User.UserRole.ADMIN)) {
                response.put("success", false);
                response.put("message", "无权修改该店铺");
                return ResponseEntity.status(403).body(response);
            }

            if (body.containsKey("appealAutoEnabled")) {
                shop.setAppealAutoEnabled(Boolean.parseBoolean(String.valueOf(body.get("appealAutoEnabled"))));
            }
            if (body.containsKey("appealStars")) {
                String stars = String.valueOf(body.get("appealStars"));
                if (stars.matches("[12](,[12])*")) {
                    shop.setAppealStars(stars);
                }
            }
            if (body.containsKey("appealRange")) {
                String range = String.valueOf(body.get("appealRange"));
                if (List.of("today", "yesterday").contains(range)) {
                    shop.setAppealRange(range);
                }
            }
            shopService.updateShop(shop);

            response.put("success", true);
            response.put("message", "申诉设定已保存");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 自动回复设定（对标西瓜 is_real_time_review）
     * body: { autoReplyEnabled: bool }
     */
    @PutMapping("/shops/{id}/reply-settings")
    public ResponseEntity<Map<String, Object>> updateReplySettings(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            Shop shop = shopService.getShopById(id);
            if (shop == null) {
                response.put("success", false);
                response.put("message", "店铺不存在");
                return ResponseEntity.status(404).body(response);
            }
            if (!shop.getUserId().equals(user.getId()) && !user.getRole().equals(User.UserRole.ADMIN)) {
                response.put("success", false);
                response.put("message", "无权修改该店铺");
                return ResponseEntity.status(403).body(response);
            }

            if (body.containsKey("autoReplyEnabled")) {
                shop.setAutoReplyEnabled(Boolean.parseBoolean(String.valueOf(body.get("autoReplyEnabled"))));
            }
            shopService.updateShop(shop);

            response.put("success", true);
            response.put("message", "自动回复设定已保存");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @DeleteMapping("/shops/{id}")
    public ResponseEntity<Map<String, Object>> deleteShop(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            // 检查店铺是否属于当前用户
            Shop shop = shopService.getShopById(id);
            if (shop == null) {
                response.put("success", false);
                response.put("message", "店铺不存在");
                return ResponseEntity.status(404).body(response);
            }

            if (!shop.getUserId().equals(user.getId()) && !user.getRole().equals(User.UserRole.ADMIN)) {
                response.put("success", false);
                response.put("message", "无权删除该店铺");
                return ResponseEntity.status(403).body(response);
            }

            // 尝试删除店铺
            boolean deleted = shopService.deleteShop(id);

            if (deleted) {
                response.put("success", true);
                response.put("message", "删除成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "该店铺存在历史评价数据，无法删除");
                return ResponseEntity.status(400).body(response);
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @PostMapping("/shops/{id}/force-delete")
    public ResponseEntity<Map<String, Object>> forceDeleteShop(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User user;
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            user = authService.getCurrentUser(jwtUtil.extractUsername(token));
            if (user == null) throw new IllegalArgumentException();
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("message", "请重新登录"));
        }
        Shop shop = shopService.getShopById(id);
        if (shop == null) return ResponseEntity.status(404).body(Map.of("message", "店铺不存在"));
        if (!shop.getUserId().equals(user.getId()) && user.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(403).body(Map.of("message", "无权删除该店铺"));
        }
        String password = body.get("password");
        if (password == null || password.isBlank() || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(400).body(Map.of("message", "当前登录用户密码错误"));
        }
        shopService.forceDeleteShop(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "店铺及关联评价、申诉和反馈已删除"));
    }

}
