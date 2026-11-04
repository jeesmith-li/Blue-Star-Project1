package com.example.meituan.web.service;

import com.example.meituan.web.entity.ClientLog;
import com.example.meituan.web.repository.ClientLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一审计日志服务：所有日志写入走这里（同步写 SQLite，量小无需异步队列）
 */
@Service
public class ClientLogService {

    private static final Logger log = LoggerFactory.getLogger(ClientLogService.class);

    /** 单条 detail 上限，防止超长堆栈撑爆 */
    private static final int MAX_DETAIL = 1900;

    @Autowired
    private ClientLogRepository repository;

    /** 便捷记录 */
    public void record(String level, String type, String username, String action,
                       String result, String detail, String page, String endpoint,
                       Long durationMs, Integer httpStatus, String userAgent,
                       String screen, String ip) {
        try {
            ClientLog l = new ClientLog();
            l.setLevel(level == null ? "INFO" : level);
            l.setType(type == null ? "system" : type);
            l.setUsername(username == null || username.isEmpty() ? "anonymous" : username);
            l.setAction(action);
            l.setResult(result);
            if (detail != null && detail.length() > MAX_DETAIL) {
                detail = detail.substring(0, MAX_DETAIL) + "...(截断)";
            }
            l.setDetail(detail);
            l.setPage(page);
            l.setEndpoint(endpoint);
            l.setDurationMs(durationMs);
            l.setHttpStatus(httpStatus);
            if (userAgent != null && userAgent.length() > 390) userAgent = userAgent.substring(0, 390);
            l.setUserAgent(userAgent);
            l.setScreen(screen);
            l.setIp(ip);
            repository.save(l);
        } catch (Exception e) {
            // 日志系统自身故障不能影响主业务
            log.warn("[ClientLog] 写入失败: {}", e.getMessage());
        }
    }

    public void info(String type, String username, String action, String detail) {
        record("INFO", type, username, action, "success", detail, null, null, null, null, null, null, null);
    }

    public void error(String type, String username, String action, String detail) {
        record("ERROR", type, username, action, "fail", detail, null, null, null, null, null, null, null);
    }

    /** 清理 N 天前的日志（防止库无限膨胀），返回删除条数 */
    public int purgeOlderThanDays(int days) {
        try {
            List<ClientLog> old = repository.findAll((root, q, cb) ->
                    cb.lessThan(root.get("ts"), java.time.LocalDateTime.now().minusDays(days)));
            repository.deleteAll(old);
            return old.size();
        } catch (Exception e) {
            log.warn("[ClientLog] 清理失败: {}", e.getMessage());
            return 0;
        }
    }
}
