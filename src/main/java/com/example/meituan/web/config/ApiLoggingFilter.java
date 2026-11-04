package com.example.meituan.web.config;

import com.example.meituan.web.service.ClientLogService;
import com.example.meituan.web.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局 API 请求审计过滤器：
 * - 所有写操作（POST/PUT/DELETE）成功也记录（用户干了什么一目了然）
 * - GET 请求只在 失败(>=400) 或 慢(>2s) 时记录
 * - /api/client-logs 自身不记录（防死循环）
 */
@Component
@Order(2)
public class ApiLoggingFilter extends OncePerRequestFilter {

    /** URL 后缀 → 用户动作名映射（写操作） */
    private static final Map<String, String> ACTION_MAP = new HashMap<>();
    static {
        ACTION_MAP.put("/api/auth/login", "login");
        ACTION_MAP.put("/api/auth/register", "register");
        ACTION_MAP.put("/api/shops/bind", "bind_shop");
        ACTION_MAP.put("/api/shops", "shop_change");
        ACTION_MAP.put("/api/shops/automation/start", "start_automation");
        ACTION_MAP.put("/api/shops/automation/stop", "stop_automation");
        ACTION_MAP.put("/api/reviews/fetch", "fetch_reviews");
        ACTION_MAP.put("/api/reviews/fetch-all", "fetch_all_reviews");
        ACTION_MAP.put("/api/reviews/reply", "reply_review");
        ACTION_MAP.put("/api/reviews/mark-replied", "mark_replied");
        ACTION_MAP.put("/api/appeals", "appeal_op");
        ACTION_MAP.put("/api/appeals/scan", "appeal_scan");
        ACTION_MAP.put("/api/appeals/auto-run", "appeal_auto_run");
        ACTION_MAP.put("/api/negative-feedbacks/sync", "negative_sync");
        ACTION_MAP.put("/api/upload", "upload_image");
        ACTION_MAP.put("/api/templates", "template_change");
        ACTION_MAP.put("/api/users", "user_change");
    }

    private static final long SLOW_MS = 2000;

    @Autowired
    private ClientLogService logService;

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 只管 /api/；日志上报接口跳过
        return !uri.startsWith("/api/") || uri.startsWith("/api/client-logs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - start;
            int status = response.getStatus();
            String method = request.getMethod();
            String uri = request.getRequestURI();
            boolean write = !"GET".equalsIgnoreCase(method);

            if (write || status >= 400 || cost > SLOW_MS) {
                String username = resolveUsername(request);
                String action = matchAction(method, uri);
                String level;
                String result;
                String type;
                if (status >= 500) {
                    level = "ERROR"; result = "fail"; type = "api_error";
                } else if (status >= 400) {
                    level = "WARN"; result = "fail"; type = "api_error";
                } else if (cost > SLOW_MS) {
                    level = "WARN"; result = "slow"; type = "slow_api";
                } else {
                    level = "INFO"; result = "success"; type = "user_action";
                }
                String detail = null;
                if (status >= 400 && "/api/auth/login".equals(uri)) {
                    detail = "登录失败（用户名或密码错误，或账号不存在）";
                } else if (status >= 400) {
                    detail = "HTTP " + status;
                }
                logService.record(level, type, username, action, result, detail,
                        null, method + " " + uri, cost, status,
                        request.getHeader("User-Agent"), null, request.getRemoteAddr());
            }
        }
    }

    private String matchAction(String method, String uri) {
        // 精确/长前缀优先
        String best = null;
        for (String prefix : ACTION_MAP.keySet()) {
            if (uri.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        if (best == null) return null;
        String name = ACTION_MAP.get(best);
        if ("/api/auth/login".equals(best) && "POST".equalsIgnoreCase(method)) return "login";
        return name;
    }

    private String resolveUsername(HttpServletRequest request) {
        try {
            String token = jwtUtil.extractTokenFromHeader(request.getHeader("Authorization"));
            if (token != null) return jwtUtil.extractUsername(token);
        } catch (Exception ignore) {}
        return "anonymous";
    }
}
