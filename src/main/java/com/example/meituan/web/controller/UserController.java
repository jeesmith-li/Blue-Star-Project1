package com.example.meituan.web.controller;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.UserService;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 获取用户列表（仅管理员）
     */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getUsers(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User currentUser = authService.getCurrentUser(username);

            // 检查管理员权限
            if (currentUser.getRole() != User.UserRole.ADMIN) {
                response.put("success", false);
                response.put("message", "无权访问");
                return ResponseEntity.status(403).body(response);
            }

            List<User> users = userService.getAllUsers();
            response.put("success", true);
            response.put("data", users);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "获取用户列表失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 创建用户（仅管理员）
     */
    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User currentUser = authService.getCurrentUser(username);

            // 检查管理员权限
            if (currentUser.getRole() != User.UserRole.ADMIN) {
                response.put("success", false);
                response.put("message", "无权访问");
                return ResponseEntity.status(403).body(response);
            }

            String newUsername = (String) request.get("username");
            String password = (String) request.get("password");
            String phone = (String) request.get("phone");
            Integer subscriptionMonths = (Integer) request.get("subscriptionMonths");

            // 验证必填字段
            if (newUsername == null || newUsername.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "用户名不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            if (password == null || password.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "密码不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            // 检查用户名是否已存在
            if (userService.existsByUsername(newUsername)) {
                response.put("success", false);
                response.put("message", "用户名已存在");
                return ResponseEntity.badRequest().body(response);
            }

            User user = userService.createUser(newUsername, password, phone, subscriptionMonths);

            response.put("success", true);
            response.put("message", "用户创建成功");
            response.put("data", user);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "创建用户失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 更新用户（仅管理员）
     */
    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User currentUser = authService.getCurrentUser(username);

            // 检查管理员权限
            if (currentUser.getRole() != User.UserRole.ADMIN) {
                response.put("success", false);
                response.put("message", "无权访问");
                return ResponseEntity.status(403).body(response);
            }

            User user = userService.getUserById(id);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户不存在");
                return ResponseEntity.status(404).body(response);
            }

            // 更新字段
            if (request.containsKey("phone")) {
                String phone = (String) request.get("phone");
                userService.updateUserPhone(id, phone);
            }

            if (request.containsKey("status")) {
                String status = (String) request.get("status");
                userService.updateUserStatus(id, User.AccountStatus.valueOf(status));
            }

            User updatedUser = userService.getUserById(id);
            response.put("success", true);
            response.put("message", "更新成功");
            response.put("data", updatedUser);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "更新用户失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 设置用户有效期（仅管理员）
     */
    @PostMapping("/users/{id}/subscription")
    public ResponseEntity<Map<String, Object>> setSubscription(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User currentUser = authService.getCurrentUser(username);

            // 检查管理员权限
            if (currentUser.getRole() != User.UserRole.ADMIN) {
                response.put("success", false);
                response.put("message", "无权访问");
                return ResponseEntity.status(403).body(response);
            }

            User user = userService.getUserById(id);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户不存在");
                return ResponseEntity.status(404).body(response);
            }

            Integer months = (Integer) request.get("months");
            String endTime = (String) request.get("endTime");

            User updatedUser = userService.setSubscription(id, months, endTime);

            response.put("success", true);
            response.put("message", "有效期设置成功");
            response.put("data", updatedUser);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "设置有效期失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 删除用户（仅管理员）
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User currentUser = authService.getCurrentUser(username);

            // 检查管理员权限
            if (currentUser.getRole() != User.UserRole.ADMIN) {
                response.put("success", false);
                response.put("message", "无权访问");
                return ResponseEntity.status(403).body(response);
            }

            User user = userService.getUserById(id);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户不存在");
                return ResponseEntity.status(404).body(response);
            }

            // 不能删除管理员
            if (user.getRole() == User.UserRole.ADMIN) {
                response.put("success", false);
                response.put("message", "不能删除管理员账号");
                return ResponseEntity.status(400).body(response);
            }

            // 尝试删除用户
            boolean deleted = userService.deleteUser(id);

            if (deleted) {
                response.put("success", true);
                response.put("message", "删除成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "该用户存在业务数据，无法删除");
                return ResponseEntity.status(400).body(response);
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "删除用户失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
