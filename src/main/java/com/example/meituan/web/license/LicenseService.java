package com.example.meituan.web.license;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class LicenseService {

    private static final Logger logger = LoggerFactory.getLogger(LicenseService.class);

    @Autowired
    private UserRepository userRepository;

    /**
     * 检查用户授权是否有效
     *
     * @param user 用户对象
     * @return true=有效, false=无效
     */
    @Transactional
    public boolean checkUserLicense(User user) {
        if (user == null) {
            return false;
        }

        // ADMIN 永久有效
        if (user.getRole() == User.UserRole.ADMIN) {
            return true;
        }

        // 检查过期时间
        LocalDateTime expireTime = user.getSubscriptionEndTime();
        if (expireTime == null) {
            logger.warn("User {} has no expireTime set", user.getUsername());
            updateLicenseStatus(user, User.AccountStatus.EXPIRED);
            return false;
        }

        LocalDateTime now = LocalDateTime.now();

        // 已过期
        if (now.isAfter(expireTime)) {
            logger.warn("User {} license expired at {}", user.getUsername(), expireTime);
            updateLicenseStatus(user, User.AccountStatus.EXPIRED);
            return false;
        }

        // 有效
        if (user.getStatus() != User.AccountStatus.ACTIVE) {
            updateLicenseStatus(user, User.AccountStatus.ACTIVE);
        }

        return true;
    }

    /**
     * 更新用户授权状态
     */
    @Transactional
    public void updateLicenseStatus(User user, User.AccountStatus status) {
        if (user.getStatus() != status) {
            user.setStatus(status);
            userRepository.save(user);
            logger.info("Updated user {} license status to {}", user.getUsername(), status);
        }
    }

    /**
     * 生成唯一的 License Key
     */
    public String generateLicenseKey() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /**
     * 延长用户授权期限
     *
     * @param user 用户对象
     * @param expireTime 新的过期时间
     */
    @Transactional
    public void extendLicense(User user, LocalDateTime expireTime) {
        user.setSubscriptionEndTime(expireTime);
        user.setStatus(User.AccountStatus.ACTIVE);
        userRepository.save(user);
        logger.info("Extended user {} license to {}", user.getUsername(), expireTime);
    }

    /**
     * 检查授权状态并返回结果对象
     */
    public LicenseCheckResult checkLicenseWithResult(User user) {
        LicenseCheckResult result = new LicenseCheckResult();
        result.setValid(checkUserLicense(user));
        result.setStatus(user.getStatus());
        result.setExpireTime(user.getSubscriptionEndTime());
        result.setRole(user.getRole());

        if (!result.isValid() && user.getRole() != User.UserRole.ADMIN) {
            result.setMessage("账号已过期，请联系管理员续期");
        }

        return result;
    }

    /**
     * 授权检查结果
     */
    public static class LicenseCheckResult {
        private boolean valid;
        private User.AccountStatus status;
        private LocalDateTime expireTime;
        private User.UserRole role;
        private String message;

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public User.AccountStatus getStatus() {
            return status;
        }

        public void setStatus(User.AccountStatus status) {
            this.status = status;
        }

        public LocalDateTime getExpireTime() {
            return expireTime;
        }

        public void setExpireTime(LocalDateTime expireTime) {
            this.expireTime = expireTime;
        }

        public User.UserRole getRole() {
            return role;
        }

        public void setRole(User.UserRole role) {
            this.role = role;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
