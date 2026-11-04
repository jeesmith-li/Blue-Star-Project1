package com.example.meituan.web.util;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JWT 工具类
 * token 格式: base64url(username) + "." + base64url(HMAC-SHA256(secret, username))
 * 不依赖第三方库，保证用户名能正确编解码（修复多用户数据串号问题）
 */
@Component
public class JwtUtil {

    // 签名密钥（生产环境应从配置读取）
    private static final String SECRET = "bluestar-meituan-rpa-secret-key-2026";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }

    /**
     * 从 Authorization header 中提取 token
     * 格式: "Bearer <token>"
     */
    public String extractTokenFromHeader(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }

    /**
     * 从 token 提取用户名（校验签名，防伪造）
     */
    public String extractUsername(String token) {
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("token 为空");
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            throw new RuntimeException("token 格式错误");
        }
        String userB64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String username = new String(B64D.decode(userB64), StandardCharsets.UTF_8);
        // 校验签名
        if (!hmac(username).equals(sig)) {
            throw new RuntimeException("token 签名无效");
        }
        return username;
    }

    /**
     * 生成 token（编码用户名并签名）
     */
    public String generateToken(String username, Long userId, String role) {
        String userB64 = B64.encodeToString(username.getBytes(StandardCharsets.UTF_8));
        return userB64 + "." + hmac(username);
    }
}
