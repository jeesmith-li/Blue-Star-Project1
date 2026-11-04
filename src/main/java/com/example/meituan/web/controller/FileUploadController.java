package com.example.meituan.web.controller;

import com.example.meituan.web.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传接口（申诉凭证图片）
 * 上传后返回可访问的 URL，前端再把 URL 关联到模板或待申诉评价
 */
@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @Autowired
    private JwtUtil jwtUtil;

    private static final String[] ALLOWED = {"jpg", "jpeg", "png", "gif", "bmp", "webp"};

    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();
        try {
            // 简单鉴权
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            jwtUtil.extractUsername(token);

            if (file == null || file.isEmpty()) {
                response.put("success", false);
                response.put("message", "文件为空");
                return ResponseEntity.badRequest().body(response);
            }

            String original = file.getOriginalFilename();
            String ext = "";
            if (original != null && original.contains(".")) {
                ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            }
            boolean ok = false;
            for (String a : ALLOWED) { if (a.equals(ext)) { ok = true; break; } }
            if (!ok) {
                response.put("success", false);
                response.put("message", "仅支持图片格式: jpg/png/gif/bmp/webp");
                return ResponseEntity.badRequest().body(response);
            }

            // 用绝对路径：Spring transferTo 对相对路径会解析到 Tomcat 临时目录，导致找不到路径
            File dir = new File("data/uploads").getAbsoluteFile();
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    response.put("success", false);
                    response.put("message", "创建上传目录失败: " + dir.getAbsolutePath());
                    return ResponseEntity.status(500).body(response);
                }
            }

            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            File dest = new File(dir, fileName);
            file.transferTo(dest);

            String url = "/uploads/" + fileName;
            response.put("success", true);
            response.put("url", url);
            response.put("name", original);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
