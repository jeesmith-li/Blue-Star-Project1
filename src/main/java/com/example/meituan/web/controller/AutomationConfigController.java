package com.example.meituan.web.controller;

import com.example.meituan.web.entity.AutomationConfig;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.AutomationConfigRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ClientLogService;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 自动化执行配置：用户可在自动化页设置自动抓取开关与执行间隔（几小时/几天），
 * 并查看最近一次执行状态（供仪表盘告警条与自动化页展示）。
 */
@RestController
@RequestMapping("/api/automation-config")
public class AutomationConfigController {

    @Autowired
    private AutomationConfigRepository configRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ClientLogService clientLogService;

    /** 获取配置（不存在则创建默认：开启，每 24 小时） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> response = new HashMap<>();
        try {
            AutomationConfig cfg = getOrCreate();
            response.put("success", true);
            response.put("data", toMap(cfg));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取配置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /** 更新配置：dailyCaptureEnabled(开关)、intervalValue(数值)、intervalUnit(hour/day) */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            AutomationConfig cfg = getOrCreate();

            if (request.containsKey("dailyCaptureEnabled")) {
                cfg.setDailyCaptureEnabled(Boolean.parseBoolean(String.valueOf(request.get("dailyCaptureEnabled"))));
            }
            if (request.containsKey("intervalValue") || request.containsKey("intervalUnit")) {
                double value = request.containsKey("intervalValue")
                        ? Double.parseDouble(String.valueOf(request.get("intervalValue"))) : 1;
                String unit = request.containsKey("intervalUnit")
                        ? String.valueOf(request.get("intervalUnit")) : "hour";
                int hours;
                if ("day".equals(unit)) {
                    hours = (int) Math.round(value * 24);
                } else {
                    hours = (int) Math.round(value);
                }
                if (hours < 1) {
                    response.put("success", false);
                    response.put("message", "执行间隔不能小于 1 小时");
                    return ResponseEntity.badRequest().body(response);
                }
                if (hours > 720) {
                    response.put("success", false);
                    response.put("message", "执行间隔不能超过 30 天");
                    return ResponseEntity.badRequest().body(response);
                }
                cfg.setIntervalHours(hours);
            }
            cfg.setUpdatedAt(LocalDateTime.now());
            configRepository.save(cfg);

            String msg = String.format("自动抓取:%s, 执行间隔:%d小时(%s)",
                    Boolean.TRUE.equals(cfg.getDailyCaptureEnabled()) ? "开启" : "关闭",
                    cfg.getIntervalHours(),
                    cfg.getIntervalHours() % 24 == 0 ? (cfg.getIntervalHours() / 24) + "天" : cfg.getIntervalHours() + "小时");
            clientLogService.info("automation", username, "自动执行配置修改", user.getUsername() + " 修改配置: " + msg);

            response.put("success", true);
            response.put("message", "保存成功");
            response.put("data", toMap(cfg));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private AutomationConfig getOrCreate() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> {
            AutomationConfig cfg = new AutomationConfig();
            return configRepository.save(cfg);
        });
    }

    private Map<String, Object> toMap(AutomationConfig cfg) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", cfg.getId());
        m.put("dailyCaptureEnabled", Boolean.TRUE.equals(cfg.getDailyCaptureEnabled()));
        m.put("intervalHours", cfg.getIntervalHours());
        m.put("lastRunTime", cfg.getLastRunTime());
        m.put("lastRunResult", cfg.getLastRunResult());
        m.put("lastRunDetail", cfg.getLastRunDetail());
        return m;
    }
}
