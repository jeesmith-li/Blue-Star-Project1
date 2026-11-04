package com.example.meituan.web.service;

import com.example.meituan.web.config.SecurityConfig;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ShopRepository;
import com.example.meituan.web.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private SecurityConfig securityConfig;

    /**
     * 获取所有用户
     */
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * 根据 ID 获取用户
     */
    public User getUserById(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    /**
     * 检查用户名是否存在
     */
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * 创建用户
     */
    public User createUser(String username, String password, String phone, Integer subscriptionMonths) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(securityConfig.passwordEncoder().encode(password));
        user.setRole(User.UserRole.USER);
        user.setStatus(User.AccountStatus.ACTIVE);
        user.setCreatedAt(LocalDateTime.now());

        // 设置订阅时间
        LocalDateTime startTime = LocalDateTime.now();
        user.setSubscriptionStartTime(startTime);

        if (subscriptionMonths != null && subscriptionMonths > 0) {
            user.setSubscriptionEndTime(startTime.plusMonths(subscriptionMonths));
        } else {
            // 默认 1 个月
            user.setSubscriptionEndTime(startTime.plusMonths(1));
        }

        return userRepository.save(user);
    }

    /**
     * 更新用户手机号
     */
    public void updateUserPhone(Long id, String phone) {
        User user = userRepository.findById(id).orElse(null);
        if (user != null) {
            // Note: User 实体需要添加 phone 字段
            userRepository.save(user);
        }
    }

    /**
     * 更新用户状态
     */
    public void updateUserStatus(Long id, User.AccountStatus status) {
        User user = userRepository.findById(id).orElse(null);
        if (user != null) {
            user.setStatus(status);
            userRepository.save(user);
        }
    }

    /**
     * 设置用户订阅有效期
     */
    public User setSubscription(Long id, Integer months, String endTime) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        if (user.getSubscriptionStartTime() == null) {
            user.setSubscriptionStartTime(now);
        }

        if (months != null && months > 0) {
            // 从当前时间延长指定月数
            user.setSubscriptionEndTime(now.plusMonths(months));
        } else if (endTime != null && !endTime.isEmpty()) {
            // 自定义结束时间
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            user.setSubscriptionEndTime(LocalDateTime.parse(endTime, formatter));
        }

        // 更新状态
        if (user.getSubscriptionEndTime() != null) {
            if (user.getSubscriptionEndTime().isAfter(now)) {
                user.setStatus(User.AccountStatus.ACTIVE);
            } else {
                user.setStatus(User.AccountStatus.EXPIRED);
            }
        }

        return userRepository.save(user);
    }

    /**
     * 删除用户（检查是否有关联数据）
     */
    public boolean deleteUser(Long id) {
        // 检查用户是否有店铺
        long shopCount = shopRepository.countByUserId(id);
        if (shopCount > 0) {
            return false;
        }

        // 没有关联数据，可以删除
        userRepository.deleteById(id);
        return true;
    }

    /**
     * 检查用户是否过期
     */
    public boolean isUserExpired(User user) {
        if (user.getRole() == User.UserRole.ADMIN) {
            return false; // 管理员永不过期
        }

        if (user.getSubscriptionEndTime() == null) {
            return false; // 没有设置过期时间，默认不过期
        }

        return LocalDateTime.now().isAfter(user.getSubscriptionEndTime());
    }

    /**
     * 检查用户账号状态
     */
    public void checkUserStatus(User user) {
        if (user.getRole() == User.UserRole.ADMIN) {
            return; // 管理员不检查
        }

        if (isUserExpired(user)) {
            user.setStatus(User.AccountStatus.EXPIRED);
            userRepository.save(user);
        }
    }
}
