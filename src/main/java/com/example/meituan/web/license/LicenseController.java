package com.example.meituan.web.license;

import com.example.meituan.web.entity.User;
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
@RequestMapping("/api/license")
public class LicenseController {

    private static final Logger logger = LoggerFactory.getLogger(LicenseController.class);

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 获取当前用户的授权信息
     */
    @GetMapping("/info")
    public ResponseEntity<?> getLicenseInfo(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            Map<String, Object> response = new HashMap<>();
            response.put("username", user.getUsername());
            response.put("role", user.getRole().name());
            response.put("status", user.getStatus().name());
            response.put("expireTime", user.getSubscriptionEndTime());
            response.put("licenseKey", user.getLicenseKey());
            response.put("deviceId", user.getDeviceId());
            response.put("deviceName", user.getDeviceName());
            response.put("remainingDays", LicenseValidator.getRemainingDays(user));
            response.put("isExpiringSoon", LicenseValidator.isExpiringSoon(user));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to get license info", e);
            Map<String, Object> error = new HashMap<>();
            error.put("code", "ERROR");
            error.put("message", "获取授权信息失败");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 检查授权状态
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkLicense(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            LicenseService.LicenseCheckResult result = licenseService.checkLicenseWithResult(user);

            Map<String, Object> response = new HashMap<>();
            response.put("valid", result.isValid());
            response.put("status", result.getStatus().name());
            response.put("expireTime", result.getExpireTime());
            response.put("message", result.getMessage());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to check license", e);
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("message", "授权检查失败");
            return ResponseEntity.status(500).body(error);
        }
    }
}
