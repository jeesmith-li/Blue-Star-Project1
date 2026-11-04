package com.example.meituan.web.controller;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.license.LicenseService;
import com.example.meituan.web.license.LicenseValidator;
import com.example.meituan.web.repository.UserRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import com.example.meituan.util.TimeFormats;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger logger = LoggerFactory.getLogger(AdminUserController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 检查管理员权限
     */
    private User checkAdminPermission(String authHeader) {
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        String username = jwtUtil.extractUsername(token);
        User user = authService.getCurrentUser(username);

        if (!LicenseValidator.isAdmin(user)) {
            throw new RuntimeException("无权访问，仅管理员可操作");
        }

        return user;
    }

    /**
     * 获取所有用户列表
     */
    @GetMapping
    public ResponseEntity<?> getUsers(@RequestHeader(value = "Authorization") String authHeader) {
        try {
            checkAdminPermission(authHeader);

            List<User> users = userRepository.findAll();
            List<Map<String, Object>> userList = new ArrayList<>();

            for (User user : users) {
                Map<String, Object> userInfo = new HashMap<>();
                userInfo.put("id", user.getId());
                userInfo.put("username", user.getUsername());
                userInfo.put("role", user.getRole().name());
                userInfo.put("status", user.getStatus().name());
                userInfo.put("userType", user.getUserType() != null ? user.getUserType().name() : null);
                userInfo.put("expireTime", user.getSubscriptionEndTime());
                userInfo.put("licenseKey", user.getLicenseKey());
                userInfo.put("deviceId", user.getDeviceId());
                userInfo.put("deviceName", user.getDeviceName());
                userInfo.put("createdAt", user.getCreatedAt());
                userInfo.put("lastLoginAt", user.getLastLoginAt());
                userInfo.put("shopCount", user.getShopCount());
                userInfo.put("remainingDays", LicenseValidator.getRemainingDays(user));

                userList.add(userInfo);
            }

            return ResponseEntity.ok(userList);

        } catch (RuntimeException e) {
            logger.error("Failed to get users", e);
            Map<String, Object> error = new HashMap<>();
            error.put("code", "ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.status(403).body(error);
        } catch (Exception e) {
            logger.error("Failed to get users", e);
            Map<String, Object> error = new HashMap<>();
            error.put("code", "ERROR");
            error.put("message", "获取用户列表失败");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 创建新用户
     */
    @PostMapping
    public ResponseEntity<?> createUser(
            @RequestHeader(value = "Authorization") String authHeader,
            @RequestBody Map<String, Object> request) {
        try {
            checkAdminPermission(authHeader);

            String username = (String) request.get("username");
            String password = (String) request.get("password");
            String roleStr = (String) request.get("role");
            String expireTimeStr = (String) request.get("expireTime");

            if (username == null || username.trim().isEmpty()) {
                throw new RuntimeException("用户名不能为空");
            }

            if (password == null || password.trim().isEmpty()) {
                throw new RuntimeException("密码不能为空");
            }

            // 检查用户名是否已存在
            if (userRepository.findByUsername(username).isPresent()) {
                throw new RuntimeException("用户名已存在");
            }

            // 创建用户
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setRole(roleStr != null && "ADMIN".equals(roleStr) ? User.UserRole.ADMIN : User.UserRole.USER);
            user.setStatus(User.AccountStatus.ACTIVE);
            user.setCreatedAt(LocalDateTime.now());

            // 生成 License Key
            user.setLicenseKey(licenseService.generateLicenseKey());

            // 设置过期时间
            if (expireTimeStr != null && !expireTimeStr.trim().isEmpty()) {
                LocalDateTime expireTime = LocalDateTime.parse(expireTimeStr, TimeFormats.DATETIME);
                user.setSubscriptionEndTime(expireTime);
            } else if (user.getRole() == User.UserRole.ADMIN) {
                // 管理员默认设置为 2099 年
                user.setSubscriptionEndTime(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
            }

            userRepository.save(user);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "创建用户成功");
            response.put("userId", user.getId());
            response.put("licenseKey", user.getLicenseKey());

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            logger.error("Failed to create user", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(400).body(error);
        } catch (Exception e) {
            logger.error("Failed to create user", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "创建用户失败");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 修改用户有效期
     */
    @PutMapping("/{id}/expire")
    public ResponseEntity<?> updateUserExpire(
            @RequestHeader(value = "Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            checkAdminPermission(authHeader);

            User user = userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("用户不存在"));

            String expireTimeStr = request.get("expireTime");
            if (expireTimeStr == null || expireTimeStr.trim().isEmpty()) {
                throw new RuntimeException("过期时间不能为空");
            }

            LocalDateTime expireTime = LocalDateTime.parse(expireTimeStr, TimeFormats.DATETIME);

            licenseService.extendLicense(user, expireTime);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "更新成功");

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            logger.error("Failed to update user expire", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(400).body(error);
        } catch (Exception e) {
            logger.error("Failed to update user expire", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "更新失败");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(
            @RequestHeader(value = "Authorization") String authHeader,
            @PathVariable Long id) {
        try {
            checkAdminPermission(authHeader);

            User user = userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("用户不存在"));

            // 不允许删除自己
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            if (user.getUsername().equals(username)) {
                throw new RuntimeException("不能删除当前登录的管理员账号");
            }

            userRepository.delete(user);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "删除成功");
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(400).body(error);
        } catch (Exception e) {
            logger.error("Failed to delete user", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "删除失败");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 禁用/启用用户
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateUserStatus(
            @RequestHeader(value = "Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            checkAdminPermission(authHeader);

            User user = userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("用户不存在"));

            String statusStr = request.get("status");
            if (statusStr == null) {
                throw new RuntimeException("状态不能为空");
            }

            User.AccountStatus status = User.AccountStatus.valueOf(statusStr);
            user.setStatus(status);
            userRepository.save(user);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "更新成功");

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            logger.error("Failed to update user status", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(400).body(error);
        } catch (Exception e) {
            logger.error("Failed to update user status", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "更新失败");
            return ResponseEntity.status(500).body(error);
        }
    }
}
