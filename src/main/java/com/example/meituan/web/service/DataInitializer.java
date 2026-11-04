package com.example.meituan.web.service;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.license.LicenseService;
import com.example.meituan.web.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 初始化默认管理员用户
 */
@Component
@Order(1)
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LicenseService licenseService;

    @Override
    public void run(String... args) throws Exception {
        // 只创建一次管理员用户
        if (!userRepository.existsByUsername("admin")) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("123456"));
            admin.setRole(User.UserRole.ADMIN);
            admin.setStatus(User.AccountStatus.ACTIVE);
            admin.setSubscriptionStartTime(LocalDateTime.now());
            admin.setSubscriptionEndTime(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
            admin.setLicenseKey(licenseService.generateLicenseKey());
            admin.setShopCount(0);
            userRepository.save(admin);
            System.out.println("✓ 创建管理员用户: admin / 123456");
        }
    }
}
