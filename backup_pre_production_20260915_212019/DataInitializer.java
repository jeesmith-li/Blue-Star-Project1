package com.example.meituan.web.service;

import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 初始化默认用户数据
 */
@Component
@Order(1)
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Override
    public void run(String... args) throws Exception {
        // 创建管理员用户
        if (!userRepository.existsByUsername("admin")) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword("123456");
            admin.setRole(User.UserRole.ADMIN);
            admin.setStatus(User.AccountStatus.ACTIVE);
            admin.setExpireAt(LocalDateTime.now().plusYears(10));
            admin.setShopCount(0);
            userRepository.save(admin);
            System.out.println("✓ 创建管理员用户: admin / 123456");
        }

        // 创建普通用户
        if (!userRepository.existsByUsername("zhangsan")) {
            User user = new User();
            user.setUsername("zhangsan");
            user.setPassword("123456");
            user.setRole(User.UserRole.USER);
            user.setUserType(User.UserType.NORMAL_USER);
            user.setStatus(User.AccountStatus.ACTIVE);
            user.setExpireAt(LocalDateTime.now().plusYears(1));
            user.setShopCount(1);
            userRepository.save(user);
            System.out.println("✓ 创建普通用户: zhangsan / 123456");
        }

        // 创建服务商用户
        if (!userRepository.existsByUsername("lisi")) {
            User sp = new User();
            sp.setUsername("lisi");
            sp.setPassword("123456");
            sp.setRole(User.UserRole.USER);
            sp.setUserType(User.UserType.SERVICE_PROVIDER);
            sp.setStatus(User.AccountStatus.ACTIVE);
            sp.setExpireAt(LocalDateTime.now().plusYears(1));
            sp.setShopCount(5);
            userRepository.save(sp);
            System.out.println("✓ 创建服务商用户: lisi / 123456");
        }
    }
}
