package com.example.meituan.web.controller;

import com.example.meituan.web.dto.LoginRequest;
import com.example.meituan.web.dto.LoginResponse;
import com.example.meituan.web.dto.UserDTO;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            logger.info("Login attempt - username: {}", request.getUsername());

            // 验证输入
            if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "用户名不能为空");
                return ResponseEntity.badRequest().body(error);
            }

            if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "密码不能为空");
                return ResponseEntity.badRequest().body(error);
            }

            LoginResponse response = authService.login(request);
            logger.info("Login successful - username: {}", request.getUsername());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Login failed - username: {}, error: {}", request.getUsername(), e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());

            // 检查是否为账号过期错误
            if (e.getMessage() != null && e.getMessage().contains("账号已过期")) {
                error.put("code", "ACCOUNT_EXPIRED");
                return ResponseEntity.status(403).body(error);
            }

            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/user")
    public ResponseEntity<UserDTO> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            UserDTO user = UserDTO.fromEntity(authService.getCurrentUser(username));
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            logger.error("Get current user failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.ok().build();
    }
}
