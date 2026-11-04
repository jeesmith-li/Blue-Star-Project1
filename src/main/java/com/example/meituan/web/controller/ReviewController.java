package com.example.meituan.web.controller;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ReviewService;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.example.meituan.web.service.ReplyExecutionService replyExecutionService;

    @GetMapping
    public ResponseEntity<ReviewService.ReviewListResult> getReviews(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String replyStatus,  // 前端筛选参数："replied"/"unreplied"/"all"
            @RequestParam(required = false) String status,       // 兼容旧参数名，同 replyStatus
            @RequestParam(required = false) String rating,       // "bad"(1-2星)/"medium"(3星)/"good"(4-5星)/"all"
            @RequestParam(required = false) String hasContent,   // "yes"/"no"/"all"
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        try {
            // 从 JWT token 获取当前用户
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            // 兼容两种参数名
            String rs = (replyStatus != null && !replyStatus.isEmpty()) ? replyStatus : status;

            ReviewService.ReviewListResult result = reviewService.getReviews(
                user, shopId, page, pageSize, rs, rating, hasContent);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            // 返回空结果而不是 404
            return ResponseEntity.ok(new ReviewService.ReviewListResult(java.util.List.of(), 0));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<com.example.meituan.web.entity.Review> getReview(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(reviewService.getReview(id));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/mark-replied")
    public ResponseEntity<Map<String, Object>> markReplied(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Long id = toLong(request.get("id"));
            List<Long> ids = toLongList(request.get("ids"));
            Long shopId = toLong(request.get("shopId"));
            int updated = reviewService.markReplied(id, ids, shopId);
            response.put("success", true);
            response.put("updated", updated);
            response.put("message", "已将 " + updated + " 条评价设为已回复");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "操作失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (!(o instanceof List)) return null;
        List<Long> out = new java.util.ArrayList<>();
        for (Object item : (List<Object>) o) {
            Long v = toLong(item);
            if (v != null) out.add(v);
        }
        return out;
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<Map<String, Object>> replyReview(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String replyContent = request.get("content");
            if (replyContent == null || replyContent.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "回复内容不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            boolean sendToMeituan = "true".equalsIgnoreCase(request.get("sendToMeituan"));

            String operator = "anonymous";
            try {
                String token = jwtUtil.extractTokenFromHeader(authHeader);
                operator = jwtUtil.extractUsername(token);
            } catch (Exception ignore) { }

            // 同时发送到美团：先走接口直调，成功才落库
            if (sendToMeituan) {
                com.example.meituan.web.entity.Review r = reviewService.getReview(id);
                Map<String, Object> send = replyExecutionService.replySingle(r, replyContent, operator);
                if (!Boolean.TRUE.equals(send.get("success"))) {
                    response.put("success", false);
                    response.put("message", "发送到美团失败: " + send.get("message"));
                    return ResponseEntity.badRequest().body(response);
                }
            }

            reviewService.replyReview(id, replyContent);

            response.put("success", true);
            response.put("message", sendToMeituan ? "已回复并同步到美团" : "回复成功");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "回复失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
