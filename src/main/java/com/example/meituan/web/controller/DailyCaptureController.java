package com.example.meituan.web.controller;

import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.DailyCaptureScheduler;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 每日抓取任务手动触发入口（供管理员立即执行一次完整流水线，也便于验证）
 */
@RestController
@RequestMapping("/api/daily-capture")
public class DailyCaptureController {

    @Autowired
    private DailyCaptureScheduler scheduler;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        authService.getCurrentUser(jwtUtil.extractUsername(token));
        Map<String, Object> result = scheduler.runNow("手动触发(" + jwtUtil.extractUsername(token) + ")");
        if ("SHOP_NOT_FOUND".equals(result.get("code"))) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
