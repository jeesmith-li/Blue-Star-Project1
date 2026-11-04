package com.example.meituan.web.service;

import com.example.meituan.web.dto.LoginRequest;
import com.example.meituan.web.dto.LoginResponse;
import com.example.meituan.web.dto.UserDTO;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.license.LicenseService;
import com.example.meituan.web.repository.UserRepository;
import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private JwtUtil jwtUtil;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        // 使用 BCrypt 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 检查账户状态
        if (user.getStatus() == User.AccountStatus.DISABLED) {
            throw new RuntimeException("账户已禁用");
        }

        // 使用 LicenseService 检查授权
        if (!licenseService.checkUserLicense(user)) {
            throw new RuntimeException("账号已过期，请联系管理员续期");
        }

        // 更新最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // 生成 token（编码用户名，保证后续请求能识别正确用户）
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getRole().name());

        return new LoginResponse(token, UserDTO.fromEntity(user));
    }

    public User getCurrentUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }
}
