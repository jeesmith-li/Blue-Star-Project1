package com.example.meituan.web.config;

import com.example.meituan.web.service.ClientLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常兜底：任何接口抛出的未捕获异常都记入审计日志（含堆栈摘要）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private ClientLogService logService;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception e) {
        String stack = Arrays.stream(e.getStackTrace()).limit(5)
                .map(StackTraceElement::toString).collect(Collectors.joining("\n"));
        String detail = e.getClass().getSimpleName() + ": " + e.getMessage() + "\n" + stack;
        log.error("[GlobalException] {}", detail);

        String uri = null;
        String username = "anonymous";
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                uri = attrs.getRequest().getMethod() + " " + attrs.getRequest().getRequestURI();
                username = "anonymous";
            }
        } catch (Exception ignore) {}
        logService.record("ERROR", "api_error", username, null, "fail", detail,
                null, uri, null, 500, null, null, null);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("message", "服务器内部错误，请稍后重试或联系技术支持（问题已自动记录）");
        return ResponseEntity.status(500).body(resp);
    }
}
