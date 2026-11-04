package com.example.meituan.web.license;

import com.example.meituan.web.entity.User;

import java.time.LocalDateTime;

/**
 * License 验证工具类
 */
public class LicenseValidator {

    /**
     * 验证用户是否有管理员权限
     */
    public static boolean isAdmin(User user) {
        return user != null && user.getRole() == User.UserRole.ADMIN;
    }

    /**
     * 验证授权是否过期
     */
    public static boolean isExpired(User user) {
        if (user == null) {
            return true;
        }

        // 管理员永不过期
        if (isAdmin(user)) {
            return false;
        }

        LocalDateTime expireTime = user.getSubscriptionEndTime();
        if (expireTime == null) {
            return true;
        }

        return LocalDateTime.now().isAfter(expireTime);
    }

    /**
     * 获取剩余天数
     */
    public static long getRemainingDays(User user) {
        if (user == null || user.getSubscriptionEndTime() == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireTime = user.getSubscriptionEndTime();

        if (now.isAfter(expireTime)) {
            return 0;
        }

        return java.time.Duration.between(now, expireTime).toDays();
    }

    /**
     * 检查是否即将过期（7天内）
     */
    public static boolean isExpiringSoon(User user) {
        long remainingDays = getRemainingDays(user);
        return remainingDays > 0 && remainingDays <= 7;
    }

    /**
     * 验证 License Key 格式
     */
    public static boolean isValidLicenseKeyFormat(String licenseKey) {
        if (licenseKey == null || licenseKey.isEmpty()) {
            return false;
        }

        // License Key 应为32位十六进制字符串
        return licenseKey.matches("^[A-F0-9]{32}$");
    }
}
