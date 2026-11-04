package com.example.meituan.web.controller;

import com.example.meituan.web.entity.ReplyTemplate;
import com.example.meituan.web.entity.User;
import com.example.meituan.web.repository.ReplyTemplateRepository;
import com.example.meituan.web.service.AuthService;
import com.example.meituan.web.service.ClientLogService;
import com.example.meituan.web.util.JwtUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    @Autowired
    private ReplyTemplateRepository templateRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ClientLogService clientLogService;

    @GetMapping
    public ResponseEntity<List<ReplyTemplate>> getTemplates(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            List<ReplyTemplate> templates;
            if (user.getRole() == User.UserRole.ADMIN) {
                templates = templateRepository.findAll();
            } else {
                templates = templateRepository.findByUserId(user.getId());
            }

            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTemplate(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            String name = (String) request.get("name");
            String content = (String) request.get("content");
            String type = (String) request.get("type");
            String images = (String) request.get("images");

            if (name == null || name.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "模板名称不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            if (content == null || content.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "模板内容不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            ReplyTemplate template = new ReplyTemplate();
            template.setName(name);
            template.setContent(content);
            template.setType(type == null || type.isEmpty() ? "reply" : type);
            if (images != null) template.setImages(images);
            template.setUserId(user.getId());
            // 触发关键词（回复/申诉通用）：逗号分隔，评价内容包含任一关键词即命中
            String keywords = (String) request.get("keywords");
            if (keywords != null) {
                template.setKeywords(keywords.trim().isEmpty() ? null : keywords.trim());
            }
            // 星级档位（仅 reply 模板）：good/middle/bad/空=通用
            String starLevel = (String) request.get("starLevel");
            if (starLevel != null) {
                starLevel = starLevel.trim().isEmpty() ? null : starLevel;
                if (starLevel == null || List.of("good", "middle", "bad").contains(starLevel)) {
                    template.setStarLevel(starLevel);
                }
            }

            ReplyTemplate saved = templateRepository.save(template);

            response.put("success", true);
            response.put("message", "模板创建成功");
            response.put("data", saved);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "创建失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 检查关键词组合是否与该用户已有模板完全一致（甲方防呆需求）。
     * 规范化规则：中英文逗号统一、去空格、排序后比对；空关键词不参与比对。
     */
    @GetMapping("/check-keywords")
    public ResponseEntity<Map<String, Object>> checkKeywords(
            @RequestParam String keywords,
            @RequestParam(defaultValue = "reply") String type,
            @RequestParam(required = false) Long excludeId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);
            String normalized = normalizeKeywords(keywords);
            String dupName = null;
            if (normalized != null) {
                for (ReplyTemplate t : templateRepository.findByUserId(user.getId())) {
                    if (!type.equals(t.getType())) continue;
                    if (excludeId != null && excludeId.equals(t.getId())) continue;
                    if (normalized.equals(normalizeKeywords(t.getKeywords()))) {
                        dupName = t.getName();
                        break;
                    }
                }
            }
            response.put("success", true);
            response.put("duplicate", dupName != null);
            response.put("duplicateName", dupName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("duplicate", false);
            response.put("message", "检查失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /** 关键词规范化：统一中英文逗号、去空格、排序；无有效关键词返回 null */
    private String normalizeKeywords(String raw) {
        if (raw == null) return null;
        List<String> list = new ArrayList<>();
        for (String k : raw.split("[,，]")) {
            String s = k.trim();
            if (!s.isEmpty()) list.add(s);
        }
        if (list.isEmpty()) return null;
        java.util.Collections.sort(list);
        return String.join("|", list);
    }

    /**
     * Excel 批量导入模板（甲方需求）。
     * 列顺序：模板名称 | 模板内容 | 类型(回复/申诉/食安申诉) | 触发关键词(逗号分隔) | 星级档位(好评/中评/差评，仅回复模板)。
     * 规则：首行为表头自动跳过；名称+内容必填；按 用户+类型+名称 查重跳过，防止重复导入。
     */
    @PostMapping("/import-excel")
    public ResponseEntity<Map<String, Object>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Map<String, Object> response = new HashMap<>();
        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            if (file == null || file.isEmpty()) {
                response.put("success", false);
                response.put("message", "请选择要导入的 Excel 文件");
                return ResponseEntity.badRequest().body(response);
            }

            // 按用户+类型+名称查重
            Set<String> existKeys = new HashSet<>();
            for (ReplyTemplate t : templateRepository.findByUserId(user.getId())) {
                existKeys.add((t.getType() == null ? "reply" : t.getType()) + ":" + t.getName().trim());
            }
            // 关键词规范化比对表（防呆：与已有模板关键词完全一致的行跳过）
            Map<String, String> kwMap = new HashMap<>();
            for (ReplyTemplate t : templateRepository.findByUserId(user.getId())) {
                String nk = normalizeKeywords(t.getKeywords());
                if (nk != null) kwMap.putIfAbsent(nk, t.getName());
            }

            DataFormatter fmt = new DataFormatter();
            int total = 0, imported = 0, skipped = 0;
            List<String> errors = new ArrayList<>();
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                for (Row row : sheet) {
                    String name = fmt.formatCellValue(row.getCell(0)).trim();
                    String content = fmt.formatCellValue(row.getCell(1)).trim();
                    if (name.isEmpty() && content.isEmpty()) continue;
                    // 首行表头跳过
                    if (row.getRowNum() == sheet.getFirstRowNum() && name.replace(" ", "").contains("名称")) {
                        continue;
                    }
                    total++;
                    if (name.isEmpty() || content.isEmpty()) {
                        errors.add("第" + (row.getRowNum() + 1) + "行：模板名称或内容为空");
                        continue;
                    }
                    String typeRaw = fmt.formatCellValue(row.getCell(2)).trim().toLowerCase();
                    // 类型三分类：申诉 / 食安申诉 / 其他视为回复
                    String tplType;
                    if (typeRaw.equals("申诉") || typeRaw.equals("appeal")) tplType = "appeal";
                    else if (typeRaw.contains("食安") || typeRaw.equals("food_appeal")) tplType = "food_appeal";
                    else tplType = "reply";
                    String keywords = fmt.formatCellValue(row.getCell(3)).trim();
                    String starRaw = fmt.formatCellValue(row.getCell(4)).trim().toLowerCase();
                    String starLevel = null;
                    if ("reply".equals(tplType)) {
                        if (starRaw.equals("好评") || starRaw.equals("good")) starLevel = "good";
                        else if (starRaw.equals("中评") || starRaw.equals("middle")) starLevel = "middle";
                        else if (starRaw.equals("差评") || starRaw.equals("bad")) starLevel = "bad";
                    }
                    String key = tplType + ":" + name;
                    if (existKeys.contains(key)) {
                        skipped++;
                        continue;
                    }
                    String nk = normalizeKeywords(keywords);
                    if (nk != null && kwMap.containsKey(nk)) {
                        skipped++;
                        errors.add("第" + (row.getRowNum() + 1) + "行：关键词与模板【" + kwMap.get(nk)
                                + "】完全一致，后加模板可能无法生效，已跳过");
                        continue;
                    }
                    ReplyTemplate t = new ReplyTemplate();
                    t.setName(name);
                    t.setContent(content);
                    t.setType(tplType);
                    t.setUserId(user.getId());
                    if (!keywords.isEmpty()) t.setKeywords(keywords);
                    if (starLevel != null) t.setStarLevel(starLevel);
                    templateRepository.save(t);
                    existKeys.add(key);
                    if (nk != null) kwMap.putIfAbsent(nk, name);
                    imported++;
                }
            }

            String msg = "共 " + total + " 条，导入成功 " + imported + " 条，重复跳过 " + skipped + " 条，失败 " + errors.size() + " 条";
            clientLogService.info("template", username, "模板Excel导入", msg
                    + (errors.isEmpty() ? "" : " | " + String.join("; ", errors)));
            response.put("success", true);
            response.put("message", msg);
            response.put("total", total);
            response.put("imported", imported);
            response.put("skipped", skipped);
            response.put("failed", errors.size());
            response.put("errors", errors);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "导入失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /** 下载 Excel 导入示例模板（含表头与示例数据） */
    @GetMapping("/import-sample")
    public ResponseEntity<byte[]> importSample(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                Sheet sheet = wb.createSheet("模板导入");
                String[] header = {"模板名称", "模板内容", "类型", "触发关键词", "星级档位"};
                Row h = sheet.createRow(0);
                for (int c = 0; c < header.length; c++) h.createCell(c).setCellValue(header[c]);
                String[][] rows = {
                        {"差味道歉回复", "非常抱歉给您带来不好的用餐体验，我们已反馈后厨改进口味，期待您再次光临。", "回复", "难吃,味道差,太咸", "差评"},
                        {"包装破损申诉", "该订单出餐包装完好，破损系配送运输挤压所致，附出餐打包照片为证，恳请平台核实。", "申诉", "包装,撒了,洒了,漏了", "差评"},
                        {"食安异物申诉", "该订单出餐制作过程符合食品安全规范，顾客反馈的异物经核查不属于本店出餐问题，恳请平台核实。", "食安申诉", "异物,食品安全,吃出", ""},
                        {"好评感谢回复", "感谢您的认可与支持，我们会继续努力，欢迎下次光临！", "回复", "", "好评"}
                };
                for (int r = 0; r < rows.length; r++) {
                    Row row = sheet.createRow(r + 1);
                    for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
                }
                for (int c = 0; c < header.length; c++) sheet.setColumnWidth(c, 16 * 256);
                sheet.setColumnWidth(1, 50 * 256);
                wb.write(out);
                String fileName = URLEncoder.encode("模板批量导入示例.xlsx", StandardCharsets.UTF_8);
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename*=UTF-8''" + fileName)
                        .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .body(out.toByteArray());
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTemplate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            ReplyTemplate template = templateRepository.findById(id).orElse(null);
            if (template == null) {
                response.put("success", false);
                response.put("message", "模板不存在");
                return ResponseEntity.notFound().build();
            }

            // 权限验证
            if (user.getRole() != User.UserRole.ADMIN && !template.getUserId().equals(user.getId())) {
                response.put("success", false);
                response.put("message", "无权限操作此模板");
                return ResponseEntity.status(403).body(response);
            }

            String name = (String) request.get("name");
            String content = (String) request.get("content");
            String type = (String) request.get("type");
            String images = (String) request.get("images");
            String keywords = (String) request.get("keywords");

            if (name != null) template.setName(name);
            if (content != null) template.setContent(content);
            if (type != null) template.setType(type);
            if (images != null) template.setImages(images);
            // 触发关键词：空串清空（回退兜底逻辑），非空保存
            if (request.containsKey("keywords")) {
                template.setKeywords(keywords == null || keywords.trim().isEmpty() ? null : keywords.trim());
            }
            // 星级档位（仅 reply 模板）：good/middle/bad/空=通用
            if (request.containsKey("starLevel")) {
                String starLevel = (String) request.get("starLevel");
                if (starLevel == null || starLevel.trim().isEmpty()) {
                    template.setStarLevel(null);
                } else if (List.of("good", "middle", "bad").contains(starLevel.trim())) {
                    template.setStarLevel(starLevel.trim());
                }
            }

            ReplyTemplate updated = templateRepository.save(template);

            response.put("success", true);
            response.put("message", "模板更新成功");
            response.put("data", updated);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "更新失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTemplate(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Map<String, Object> response = new HashMap<>();

        try {
            String token = jwtUtil.extractTokenFromHeader(authHeader);
            String username = jwtUtil.extractUsername(token);
            User user = authService.getCurrentUser(username);

            ReplyTemplate template = templateRepository.findById(id).orElse(null);
            if (template == null) {
                response.put("success", false);
                response.put("message", "模板不存在");
                return ResponseEntity.notFound().build();
            }

            // 权限验证
            if (user.getRole() != User.UserRole.ADMIN && !template.getUserId().equals(user.getId())) {
                response.put("success", false);
                response.put("message", "无权限操作此模板");
                return ResponseEntity.status(403).body(response);
            }

            templateRepository.deleteById(id);

            response.put("success", true);
            response.put("message", "模板删除成功");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
