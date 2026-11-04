package com.example.meituan.web.license;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;

@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LicenseInterceptor.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthService authService;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 放行登录、注册接口
        if (uri.startsWith("/api/auth/login") || uri.startsWith("/api/auth/register")) {
            return true;
        }

        // 放行静态资源
        if (uri.startsWith("/static") || uri.startsWith("/assets") || uri.startsWith("/logo") ||
            uri.endsWith(".html") || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".jpg") ||
            uri.endsWith(".png") || uri.endsWith(".ico")) {
            return true;
        }

        // 获取 JWT Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorizedResponse(response, "未提供有效的授权凭证");
            return false;
        }

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);

            // 获取用户信息
            User user = authService.getCurrentUser(username);
            if (user == null) {
                sendUnauthorizedResponse(response, "用户不存在");
                return false;
            }

            // 检查授权
            if (!licenseService.checkUserLicense(user)) {
                sendLicenseExpiredResponse(response);
                return false;
            }

            // 授权通过
            return true;

        } catch (Exception e) {
            logger.error("License check failed", e);
            sendUnauthorizedResponse(response, "授权验证失败");
            return false;
        }
    }

    /**
     * 发送未授权响应
     */
    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> result = new HashMap<>();
        result.put("code", "UNAUTHORIZED");
        result.put("message", message);

        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    /**
     * 发送授权过期响应
     */
    private void sendLicenseExpiredResponse(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> result = new HashMap<>();
        result.put("code", "ACCOUNT_EXPIRED");
        result.put("message", "账号已过期，请联系管理员续期");

        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
