package com.example.meituan.web.controller;

import com.example.meituan.browser.BrowserAutomationService;
import com.example.meituan.browser.ShopNotFoundException;
import com.example.meituan.web.entity.ReplyTemplate;
import com.example.meituan.web.entity.Review;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ReplyTemplateRepository;
import com.example.meituan.web.repository.ReviewRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 申诉控制器
 * 流程：已回复差评 → 系统标记待申诉(pending) → 用户上传图片 → 发起申诉(浏览器CDP) → 回查结果
 */
@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReplyTemplateRepository templateRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private BrowserAutomationService browserService;

    @Autowired
    private com.example.meituan.web.service.AppealService appealService;

    /**
     * 判定是否需要申诉：仅差评（1-2星）需要申诉，好评/中评不进入申诉流程
     */
    private boolean shouldAppeal(Review r) {
        return r.getRating() != null && r.getRating() <= 2;
    }

    private User currentUser(String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        return authService.getCurrentUser(jwtUtil.extractUsername(token));
    }

    /**
     * 扫描：把已回复且表达不满/恶意的评价标记为待申诉(pending)
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            User user = currentUser(authHeader);
            // 管理员看全部，普通用户看自己店铺的评价（通过 shop 关联）
            List<Review> replied = reviewRepository.findByReplyStatusAndAppealStatus("replied", "none");
            int flagged = 0;
            for (Review r : replied) {
                if (!"none".equals(r.getAppealStatus())) continue;
                if (user.getRole() != User.UserRole.ADMIN) {
                    // 普通用户：仅处理自己店铺的评价（简化：shopId 归属校验略，依赖前端传当前用户店铺）
                }
                if (shouldAppeal(r)) {
                    r.setAppealStatus("pending");
                    reviewRepository.save(r);
                    flagged++;
                }
            }
            resp.put("success", true);
            resp.put("flagged", flagged);
            resp.put("message", "扫描完成，新增 " + flagged + " 条待申诉评价");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "扫描失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 待申诉列表（appealStatus = pending 且为差评 1-2 星；好评/中评不需要申诉）
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> pendingList(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            currentUser(authHeader);
            List<Review> list = reviewRepository.findByAppealStatus("pending");
            List<Map<String, Object>> result = list.stream()
                .filter(r -> r.getRating() != null && r.getRating() <= 2)
                .map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("shopId", r.getShopId());
                m.put("shopName", r.getShopName());
                m.put("reviewer", r.getReviewer());
                m.put("rating", r.getRating());
                m.put("content", r.getContent());
                m.put("replyContent", r.getReplyContent());
                m.put("appealStatus", r.getAppealStatus());
                m.put("appealImages", r.getAppealImages());
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 为某条待申诉评价上传/关联图片，并可预选申诉模板
     * body: { images: "url1,url2", templateId?, content? }
     */
    @PostMapping("/{id}/prepare")
    public ResponseEntity<Map<String, Object>> prepare(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            currentUser(authHeader);
            Review r = reviewRepository.findById(id).orElse(null);
            if (r == null) {
                resp.put("success", false); resp.put("message", "评价不存在");
                return ResponseEntity.status(404).body(resp);
            }
            String images = (String) body.get("images");
            if (images != null) r.setAppealImages(images);

            Object tid = body.get("templateId");
            String content = (String) body.get("content");
            if (tid != null) {
                ReplyTemplate tpl = templateRepository.findById(Long.valueOf(tid.toString())).orElse(null);
                if (tpl != null) {
                    if (content == null || content.isEmpty()) content = tpl.getContent();
                    // 模板自带图片且用户未单独传图时，用模板图
                    if ((images == null || images.isEmpty()) && tpl.getImages() != null) {
                        r.setAppealImages(tpl.getImages());
                    }
                }
            }
            if (content != null) r.setAppealContent(content);
            r.setAppealStatus("pending");
            reviewRepository.save(r);

            resp.put("success", true);
            resp.put("message", "已保存申诉材料");
            resp.put("hasImages", r.getAppealImages() != null && !r.getAppealImages().isEmpty());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 发起申诉：调用浏览器 CDP 全自动执行
     * body: { content?, images? } 可选，覆盖已保存的材料
     */
    @PostMapping("/{id}/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            currentUser(authHeader);
            Review r = reviewRepository.findById(id).orElse(null);
            if (r == null) {
                resp.put("success", false); resp.put("message", "评价不存在");
                return ResponseEntity.status(404).body(resp);
            }
            if (body != null) {
                String c = (String) body.get("content");
                String imgs = (String) body.get("images");
                if (c != null) r.setAppealContent(c);
                if (imgs != null) r.setAppealImages(imgs);
            }
            // 图片转绝对路径（浏览器在项目根目录运行）
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
            if ("appeal_done".equals(type) || "appeal_success".equals(type)) {
                r.setAppealStatus("submitted");
                r.setAppealTime(LocalDateTime.now());
                reviewRepository.save(r);
                resp.put("success", true);
                resp.put("message", "申诉已提交");
            } else {
                resp.put("success", false);
                resp.put("message", result.path("message").asText("申诉执行失败"));
            }
            return ResponseEntity.ok(resp);
        } catch (ShopNotFoundException e) {
            resp.put("success", false);
            resp.put("code", "SHOP_NOT_FOUND");
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "申诉失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 回查申诉结果（简化：标记申诉中的评价，浏览器尝试读取状态）
     */
    @PostMapping("/check-results")
    public ResponseEntity<Map<String, Object>> checkResults(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            currentUser(authHeader);
            Map<String, Object> result = appealService.checkResultsAll();
            if ("SHOP_NOT_FOUND".equals(result.get("code"))) {
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "回查失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 申诉查询（对标西瓜 comment_list）：全部申诉记录 + 筛选 + 分页
     * query: shopName, status(none/pending/submitted/approved/rejected，空=全部申诉记录即排除none), page, pageSize
     */
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam(defaultValue = "") String shopName,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            currentUser(authHeader);
            Specification<Review> spec = (root, q, cb) -> {
                List<Predicate> ps = new ArrayList<>();
                // 申诉只针对差评（1-2星），好评/中评不进入申诉流程
                ps.add(cb.isNotNull(root.get("rating")));
                ps.add(cb.le(root.get("rating"), 2));
                if (status == null || status.isEmpty()) {
                    // 默认只看进入过申诉流程的记录
                    ps.add(cb.notEqual(root.get("appealStatus"), "none"));
                } else {
                    ps.add(cb.equal(root.get("appealStatus"), status));
                }
                if (shopName != null && !shopName.isEmpty()) {
                    ps.add(cb.like(root.get("shopName"), "%" + shopName + "%"));
                }
                return cb.and(ps.toArray(new Predicate[0]));
            };
            List<Review> all = reviewRepository.findAll(spec);
            all.sort(Comparator.comparingLong(Review::getId).reversed());

            int total = all.size();
            int from = Math.max(0, (page - 1) * pageSize);
            int to = Math.min(total, from + pageSize);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Review r : all.subList(from, to)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("shopId", r.getShopId());
                m.put("shopName", r.getShopName());
                m.put("reviewer", r.getReviewer());
                m.put("rating", r.getRating());
                m.put("content", r.getContent());
                m.put("reviewTime", r.getReviewTime() == null ? null : r.getReviewTime().toString());
                m.put("appealStatus", r.getAppealStatus());
                m.put("appealTime", r.getAppealTime() == null ? null : r.getAppealTime().toString());
                m.put("appealResultTime", r.getAppealResultTime() == null ? null : r.getAppealResultTime().toString());
                m.put("appealResultNote", r.getAppealResultNote());
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
     * 单条检测申诉状态（对标西瓜 comment_status）：浏览器进入美团申诉页回查结果
     */
    @PostMapping("/{id}/check-status")
    public ResponseEntity<Map<String, Object>> checkStatus(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            currentUser(authHeader);
            Review r = reviewRepository.findById(id).orElse(null);
            if (r == null) {
                resp.put("success", false); resp.put("message", "评价不存在");
                return ResponseEntity.status(404).body(resp);
            }
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
                resp.put("success", true);
                resp.put("appealStatus", status);
                resp.put("message", "approved".equals(status) ? "申诉已通过" : "申诉被驳回");
            } else if ("submitted".equals(status)) {
                resp.put("success", true);
                resp.put("appealStatus", "submitted");
                resp.put("message", "仍在审核中");
            } else {
                resp.put("success", false);
                resp.put("appealStatus", r.getAppealStatus());
                resp.put("message", res.path("message").asText("未能读取申诉状态，请确认美团页面已打开"));
            }
            return ResponseEntity.ok(resp);
        } catch (ShopNotFoundException e) {
            resp.put("success", false);
            resp.put("code", "SHOP_NOT_FOUND");
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "检测失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 自动申诉（对标西瓜 shop_set_appeal 的执行侧）：
     * 遍历开启自动申诉的店铺，按 星级+范围(today/yesterday) 筛选差评，
     * 标记 pending 后逐条调用浏览器自动提交
     */
    @PostMapping("/auto-run")
    public ResponseEntity<Map<String, Object>> autoRun(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            currentUser(authHeader);
            Map<String, Object> result = appealService.autoRunAll();
            if ("SHOP_NOT_FOUND".equals(result.get("code"))) {
                return ResponseEntity.badRequest().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            resp.put("success", false); resp.put("message", "自动申诉失败: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }
}
