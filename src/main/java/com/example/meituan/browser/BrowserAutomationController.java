package com.example.meituan.browser;

import com.example.meituan.web.entity.Shop;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ShopRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ShopService;
import com.example.meituan.web.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 浏览器自动化控制器
 * 提供美团商家后台登录绑定店铺的 API
 */
@RestController
@RequestMapping("/api/browser")
public class BrowserAutomationController {

    @Autowired
    private BrowserAutomationService browserService;

    @Autowired
    private ShopService shopService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private com.example.meituan.web.service.ReviewService reviewService;

    /**
     * 启动浏览器，打开美团登录页
     */
    @PostMapping("/start-login")
    public ResponseEntity<Map<String, Object>> startLogin() {
        Map<String, Object> response = new HashMap<>();
        try {
            JsonNode result = browserService.startLogin();
            response.put("success", true);
            response.put("message", result.path("message").asText("浏览器已启动"));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "启动失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 检查登录状态（前端轮询）
     */
    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> checkLoginStatus() {
        Map<String, Object> response = new HashMap<>();
        boolean loggedIn = browserService.checkLogin();
        response.put("loggedIn", loggedIn);
        response.put("message", loggedIn ? "登录成功" : "等待登录...");
        return ResponseEntity.ok(response);
    }

    /**
     * 爬取店铺列表（登录成功后调用），同时返回美团账号名
     */
    @PostMapping("/scrape-shops")
    public ResponseEntity<Map<String, Object>> scrapeShops() {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> data = browserService.scrapeShops();
            response.put("success", true);
            response.put("account", data.get("account"));
            response.put("shops", data.get("shops"));
            response.put("count", data.get("count"));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 确认绑定选中的店铺到当前用户（批量）
     * request: { meituanAccount, shops: [{name}], shopType? }
     * 店铺数 >=2 自动判定为"服务商用户"，否则"美团单店"
     */
    @PostMapping("/bind-shop")
    public ResponseEntity<Map<String, Object>> bindShop(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            String meituanAccount = (String) request.get("meituanAccount");
            if (meituanAccount == null || meituanAccount.trim().isEmpty()) {
                meituanAccount = user.getUsername();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shopList = (List<Map<String, Object>>) request.get("shops");
            if (shopList == null || shopList.isEmpty()) {
                // 兼容旧的单店调用 { name }
                String single = (String) request.get("name");
                if (single != null && !single.trim().isEmpty()) {
                    shopList = List.of(Map.of("name", single));
                }
            }
            if (shopList == null || shopList.isEmpty()) {
                response.put("success", false);
                response.put("message", "店铺名称不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            // 店铺类型：>=2 家为服务商用户
            String shopType = (String) request.get("shopType");
            if (shopType == null || shopType.trim().isEmpty()) {
                shopType = shopList.size() >= 2 ? "服务商用户" : "美团单店";
            }

            // 查重：该用户已绑定的店铺名集合，重复绑定直接跳过
            Set<String> existing = new HashSet<>();
            for (Shop s : shopRepository.findByUserId(user.getId())) {
                existing.add(s.getName() == null ? "" : s.getName().trim());
            }

            List<String> toBind = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Map<String, Object> s : shopList) {
                String shopName = (String) s.get("name");
                if (shopName == null || shopName.trim().isEmpty()) continue;
                String trimmed = shopName.trim();
                if (existing.contains(trimmed)) {
                    skipped.add(trimmed);
                } else {
                    toBind.add(trimmed);
                }
            }

            if (toBind.isEmpty()) {
                response.put("success", true);
                response.put("message", "所选店铺均已绑定，未重复绑定（" + skipped.size() + " 家）");
                response.put("data", Map.of("bound", 0, "skipped", skipped.size(),
                        "failed", 0, "meituanAccount", meituanAccount, "shopType", shopType));
                return ResponseEntity.ok(response);
            }

            // 逐店绑定：单店失败不中断整批
            int bound = 0;
            List<String> failed = new ArrayList<>();
            for (String shopName : toBind) {
                try {
                    Shop shop = new Shop();
                    shop.setName(shopName);
                    shop.setMeituanAccount(meituanAccount);
                    shop.setShopType(shopType);
                    shop.setUserId(user.getId());
                    shopService.createShop(shop);
                    bound++;
                } catch (Exception ex) {
                    failed.add(shopName);
                }
            }

            StringBuilder msg = new StringBuilder("成功绑定 " + bound + " 个店铺");
            if (!skipped.isEmpty()) msg.append("，跳过已绑定 ").append(skipped.size()).append(" 个");
            if (!failed.isEmpty()) msg.append("，失败 ").append(failed.size()).append(" 个");
            response.put("success", bound > 0);
            response.put("message", msg.toString());
            Map<String, Object> data = new HashMap<>();
            data.put("bound", bound);
            data.put("skipped", skipped.size());
            data.put("failed", failed.size());
            data.put("failedShops", failed);
            data.put("meituanAccount", meituanAccount);
            data.put("shopType", shopType);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "绑定失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 抓取单个店铺的全部评价并入库
     * request: { shopId }
     */
    @PostMapping("/sync-reviews")
    public ResponseEntity<Map<String, Object>> syncReviews(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = currentUser(authHeader);
            Long shopId = toLong(request.get("shopId"));
            if (shopId == null) {
                response.put("success", false);
                response.put("message", "缺少 shopId");
                return ResponseEntity.badRequest().body(response);
            }
            Shop shop = shopRepository.findById(shopId).orElse(null);
            if (shop == null || (!user.getRole().equals(User.UserRole.ADMIN) && !shop.getUserId().equals(user.getId()))) {
                response.put("success", false);
                response.put("message", "店铺不存在或无权限");
                return ResponseEntity.badRequest().body(response);
            }
            Map<String, Object> res = browserService.scrapeReviews(shop.getName());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reviews = (List<Map<String, Object>>) res.get("reviews");
            int total = (int) res.getOrDefault("total", reviews.size());
            int skipped = (int) res.getOrDefault("skipped", 0);
            int added = reviewService.saveScrapedReviews(shop.getId(), shop.getName(), reviews);
            response.put("success", true);
            response.put("message", "抓取完成：共抓到 " + total + " 条评价，其中 " + reviews.size()
                    + " 条有具体文字内容已记录，" + skipped + " 条无文字内容未做记录");
            response.put("data", Map.of("shop", shop.getName(), "total", total,
                    "saved", reviews.size(), "skipped", skipped, "added", added));
            return ResponseEntity.ok(response);
        } catch (ShopNotFoundException e) {
            response.put("success", false);
            response.put("code", "SHOP_NOT_FOUND");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "抓取失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 抓取当前用户所有店铺的评价并入库
     */
    @PostMapping("/sync-all-reviews")
    public ResponseEntity<Map<String, Object>> syncAllReviews(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = currentUser(authHeader);
            List<Shop> shops = user.getRole().equals(User.UserRole.ADMIN)
                    ? shopRepository.findAllByOrderBySortOrderAscIdAsc()
                    : shopRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId());
            if (shops.isEmpty()) {
                response.put("success", false);
                response.put("message", "暂无绑定店铺，请先添加店铺");
                return ResponseEntity.badRequest().body(response);
            }
            int totalAdded = 0, totalFetched = 0, totalAll = 0, totalSkipped = 0;
            StringBuilder perShop = new StringBuilder();
            Shop notFoundShop = null;
            for (Shop shop : shops) {
                try {
                    Map<String, Object> res = browserService.scrapeReviews(shop.getName());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> reviews = (List<Map<String, Object>>) res.get("reviews");
                    int added = reviewService.saveScrapedReviews(shop.getId(), shop.getName(), reviews);
                    totalAdded += added;
                    totalFetched += reviews.size();
                    totalAll += (int) res.getOrDefault("total", reviews.size());
                    totalSkipped += (int) res.getOrDefault("skipped", 0);
                    perShop.append(shop.getName()).append("(+").append(added).append(") ");
                } catch (ShopNotFoundException e) {
                    perShop.append(shop.getName()).append("(未找到) ");
                    notFoundShop = shop;
                    break;
                } catch (Exception e) {
                    perShop.append(shop.getName()).append("(失败) ");
                }
            }
            if (notFoundShop != null) {
                response.put("success", false);
                response.put("code", "SHOP_NOT_FOUND");
                response.put("message", "该店铺暂未寻到：" + notFoundShop.getName()
                        + "，已停止本次全部抓取（本次已保存 " + totalAdded + " 条），请重新执行");
                response.put("data", Map.of("shops", shops.size(), "total", totalAll,
                        "saved", totalFetched, "skipped", totalSkipped, "added", totalAdded,
                        "detail", perShop.toString().trim()));
                return ResponseEntity.badRequest().body(response);
            }
            response.put("success", true);
            response.put("message", "全部店铺抓取完成：共抓到 " + totalAll + " 条评价，其中 "
                    + totalFetched + " 条有文字内容已记录，" + totalSkipped + " 条无文字内容未记录");
            response.put("data", Map.of("shops", shops.size(), "total", totalAll,
                    "saved", totalFetched, "skipped", totalSkipped, "added", totalAdded,
                    "detail", perShop.toString().trim()));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "抓取失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private User currentUser(String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        String username = jwtUtil.extractUsername(token);
        return authService.getCurrentUser(username);
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    /**
     * 关闭浏览器
     */
    @PostMapping("/close")
    public ResponseEntity<Map<String, Object>> close() {
        browserService.close();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "浏览器已关闭");
        return ResponseEntity.ok(response);
    }
}
