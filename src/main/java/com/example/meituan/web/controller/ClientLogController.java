package com.example.meituan.web.controller;

import com.example.meituan.web.entity.ClientLog;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ClientLogRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ClientLogService;
import com.example.meituan.web.util.JwtUtil;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 审计日志：前端/自动化上报 + 管理员查询
 */
@RestController
@RequestMapping("/api/client-logs")
public class ClientLogController {

    @Autowired
    private ClientLogService logService;

    @Autowired
    private ClientLogRepository repository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 前端批量上报（允许未登录时也上报，token 有效则归属用户）
     * body: [{type, level, action, result, detail, page, screen, ts?}, ...]
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody List<Map<String, Object>> logs,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest request) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String username = resolveUsername(authHeader);
            String ip = clientIp(request);
            int saved = 0;
            if (logs != null) {
                for (Map<String, Object> item : logs) {
                    String type = str(item.get("type"), "user_action");
                    String level = str(item.get("level"), "INFO");
                    logService.record(level, type, username,
                            str(item.get("action"), null),
                            str(item.get("result"), null),
                            str(item.get("detail"), null),
                            str(item.get("page"), null),
                            str(item.get("endpoint"), null),
                            item.get("durationMs") instanceof Number n ? n.longValue() : null,
                            item.get("httpStatus") instanceof Number n ? n.intValue() : null,
                            userAgent,
                            str(item.get("screen"), null),
                            ip);
                    saved++;
                }
            }
            resp.put("success", true);
            resp.put("saved", saved);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("success", false);
            return ResponseEntity.ok(resp); // 上报失败不让前端报错
        }
    }

    /**
     * 管理员查询日志
     * query: username, level, type, action, keyword, start, end, page, pageSize
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam(defaultValue = "") String username,
            @RequestParam(defaultValue = "") String level,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String start,
            @RequestParam(defaultValue = "") String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> resp = new HashMap<>();
        try {
            User user = currentUser(authHeader);
            if (user.getRole() != User.UserRole.ADMIN) {
                resp.put("total", 0);
                resp.put("items", List.of());
                resp.put("message", "仅管理员可查看日志");
                return ResponseEntity.status(403).body(resp);
            }
            Specification<ClientLog> spec = (root, q, cb) -> {
                List<Predicate> ps = new ArrayList<>();
                if (!username.isEmpty()) ps.add(cb.equal(root.get("username"), username));
                if (!level.isEmpty()) ps.add(cb.equal(root.get("level"), level));
                if (!type.isEmpty()) ps.add(cb.equal(root.get("type"), type));
                if (!keyword.isEmpty()) {
                    String like = "%" + keyword + "%";
                    ps.add(cb.or(cb.like(root.get("detail"), like),
                            cb.like(root.get("action"), like),
                            cb.like(root.get("endpoint"), like)));
                }
                try {
                    if (!start.isEmpty()) ps.add(cb.greaterThanOrEqualTo(root.get("ts"),
                            LocalDateTime.parse(start.replace(" ", "T"))));
                    if (!end.isEmpty()) ps.add(cb.lessThanOrEqualTo(root.get("ts"),
                            LocalDateTime.parse(end.replace(" ", "T"))));
                } catch (Exception ignore) {}
                return cb.and(ps.toArray(new Predicate[0]));
            };
            List<ClientLog> all = repository.findAll(spec);
            all.sort(Comparator.comparing(ClientLog::getTs).reversed()
                    .thenComparing(Comparator.comparingLong(ClientLog::getId).reversed()));
            int total = all.size();
            int from = Math.max(0, (page - 1) * pageSize);
            int to = Math.min(total, from + pageSize);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ClientLog l : all.subList(from, to)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.getId());
                m.put("ts", l.getTs() == null ? null : l.getTs().toString());
                m.put("username", l.getUsername());
                m.put("level", l.getLevel());
                m.put("type", l.getType());
                m.put("action", l.getAction());
                m.put("result", l.getResult());
                m.put("detail", l.getDetail());
                m.put("page", l.getPage());
                m.put("endpoint", l.getEndpoint());
                m.put("durationMs", l.getDurationMs());
                m.put("httpStatus", l.getHttpStatus());
                m.put("userAgent", l.getUserAgent());
                m.put("screen", l.getScreen());
                m.put("ip", l.getIp());
                items.add(m);
            }
            resp.put("total", total);
            resp.put("page", page);
            resp.put("pageSize", pageSize);
            resp.put("items", items);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("total", 0);
            resp.put("items", List.of());
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("token") || msg.contains("Token") || msg.contains("用户") || msg.contains("登录")) {
                resp.put("message", "未登录或登录已过期");
                return ResponseEntity.status(401).body(resp);
            }
            resp.put("message", "查询失败: " + msg);
            return ResponseEntity.status(500).body(resp);
        }
    }

    private String resolveUsername(String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            return jwtUtil.extractUsername(token);
        } catch (Exception e) {
            return "anonymous";
        }
    }

    private User currentUser(String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        String username = jwtUtil.extractUsername(token);
        return authService.getCurrentUser(username);
    }

    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isEmpty()) return v.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
