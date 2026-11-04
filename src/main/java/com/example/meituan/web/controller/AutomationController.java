package com.example.meituan.web.controller;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.AutomationService;
import com.example.meituan.web.service.DailyCaptureScheduler;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/automation")
public class AutomationController {

    @Autowired
    private AutomationService automationService;

    @Autowired
    private DailyCaptureScheduler dailyCaptureScheduler;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private com.example.meituan.web.service.ReplyExecutionService replyExecutionService;

    @GetMapping("/status")
    public ResponseEntity<AutomationService.AutomationStatusDTO> getStatus() {
        AutomationService.AutomationStatusDTO dto = automationService.getStatus();
        if (replyExecutionService.isRunning() || dailyCaptureScheduler.isRunning()) {
            dto.setStatus("running");
            dto.setCurrentAction(replyExecutionService.isRunning()
                    ? "正在自动回复评价..."
                    : "正在按店铺顺序执行自动流水线...");
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/logs")
    public ResponseEntity<List<AutomationService.AutomationLog>> getLogs() {
        return ResponseEntity.ok(automationService.getLogs());
    }

    /**
     * 立即执行一次完整流水线：按店铺执行顺序逐店完成
     * 抓取评价 → 自动回复 → 差评申诉 → 申诉回查 → 食安抓取 → 食安自动申诉 → 食安回查
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            // 管理员处理全部店铺；普通用户只处理自己名下店铺
            Long userId = user.getRole() == User.UserRole.ADMIN ? null : user.getId();
            Map<String, Object> result = dailyCaptureScheduler.runNow("手动触发(" + username + ")", userId);

            if ("SHOP_NOT_FOUND".equals(result.get("code"))) {
                response.put("success", false);
                response.put("code", "SHOP_NOT_FOUND");
                response.put("message", result.get("message"));
                response.put("detail", result);
                return ResponseEntity.badRequest().body(response);
            }
            response.put("success", result.getOrDefault("success", false));
            response.put("message", String.valueOf(result.get("message")));
            response.put("detail", result);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "启动失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        Map<String, Object> response = new HashMap<>();

        replyExecutionService.stop();
        response.put("success", true);
        response.put("message", "已发送停止请求，将在当前回复完成后中断（流水线正在进行的店铺切换/申诉步骤需等待结束）");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "暂停功能暂未实现");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "恢复功能暂未实现");
        return ResponseEntity.ok(response);
    }
}

