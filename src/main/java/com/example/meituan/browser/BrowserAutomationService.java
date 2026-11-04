package com.example.meituan.browser;

import com.example.meituan.config.AppConfig;
import com.example.meituan.web.service.ClientLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览器自动化服务
 * 管理 Python browser_automation.py 子进程，通过 stdin/stdout 通信
 */
@Service
public class BrowserAutomationService {
    private static final Logger logger = LoggerFactory.getLogger(BrowserAutomationService.class);

    private final AppConfig config = new AppConfig();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean running = false;

    @Autowired
    private ClientLogService logService;

    /**
     * 确保 Python 子进程已启动
     */
    private synchronized void ensureProcess() throws IOException {
        if (running && process != null && process.isAlive()) {
            return;
        }
        // 进程已死，清理状态后重启
        destroyProcess();
        startProcess();
    }

    private void startProcess() throws IOException {
        String pythonExe = config.getPythonExecutable();
        String scriptPath = "python/browser_automation.py";

        // 处理 python.exe 路径（可能是相对路径）
        File pythonFile = new File(pythonExe);
        if (!pythonFile.isAbsolute()) {
            pythonFile = new File(System.getProperty("user.dir"), pythonExe);
        }

        File scriptFile = config.resolveScript(scriptPath);

        List<String> command = new ArrayList<>();
        command.add(pythonFile.getAbsolutePath());
        command.add(scriptFile.getAbsolutePath());

        logger.info("[Browser] Starting process: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.directory(new File(System.getProperty("user.dir")));
        // 强制 Python 子进程使用 UTF-8 编码，避免中文 Windows 默认 GBK 导致店铺名等中文乱码
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        // stderr 重定向到文件，避免非UTF-8字节导致读取线程崩溃
        File stderrFile = new File("logs/browser_stderr.log");
        pb.redirectError(stderrFile);
        process = pb.start();

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));

        running = true;

        // 等待 ready 信号
        try {
            String line = stdout.readLine();
            if (line != null) {
                JsonNode msg = objectMapper.readTree(line);
                logger.info("[Browser] Ready signal: {}", msg.path("message").asText());
            }
        } catch (Exception e) {
            logger.warn("[Browser] No ready signal: {}", e.getMessage());
        }
    }

    /**
     * 发送命令并等待响应
     */
    private synchronized JsonNode sendCommand(String action, Map<String, Object> args) throws IOException {
        long start = System.currentTimeMillis();
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureProcess();
            try {
                Map<String, Object> cmd = Map.of("action", action, "args", args != null ? args : Map.of());
                stdin.write(objectMapper.writeValueAsString(cmd));
                stdin.newLine();
                stdin.flush();

                String line = stdout.readLine();
                if (line == null) {
                    throw new IOException("浏览器进程已断开");
                }
                JsonNode resp = objectMapper.readTree(line);
                // 自动化执行结果记入审计日志（跳过轮询类的轻量命令）
                if (!"check_login".equals(action)) {
                    String type = resp.path("type").asText("");
                    boolean ok = !type.isEmpty() && !"error".equals(type);
                    logService.record(ok ? "INFO" : "ERROR", "automation", null, action,
                            ok ? "success" : "fail",
                            ok ? "type=" + type : "type=" + type + " msg=" + resp.path("message").asText(""),
                            null, null, System.currentTimeMillis() - start, null, null, null, null);
                }
                return resp;
            } catch (IOException e) {
                logger.warn("[Browser] sendCommand attempt {} failed: {}", attempt + 1, e.getMessage());
                logService.record("ERROR", "automation", null, action, "fail",
                        "通信失败: " + e.getMessage(),
                        null, null, System.currentTimeMillis() - start, null, null, null, null);
                destroyProcess();
                if (attempt == 1) throw e;
            }
        }
        throw new IOException("浏览器通信失败");
    }

    /**
     * 启动浏览器并打开美团登录页
     */
    public JsonNode startLogin() throws IOException {
        logger.info("[Browser] startLogin");
        return sendCommand("start_login", null);
    }

    /**
     * 检查登录状态
     */
    public boolean checkLogin() {
        try {
            JsonNode resp = sendCommand("check_login", null);
            String type = resp.path("type").asText();
            return "login_success".equals(type);
        } catch (Exception e) {
            logger.error("[Browser] checkLogin error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 爬取店铺列表（含美团账号名）
     * 返回 Map: { account, shops: [...], count }
     */
    public Map<String, Object> scrapeShops() throws IOException {
        logger.info("[Browser] scrapeShops");
        JsonNode resp = sendCommand("scrape_shops", null);
        String type = resp.path("type").asText();
        if ("shops_fetched".equals(type)) {
            List<Map<String, Object>> shops = new ArrayList<>();
            JsonNode arr = resp.path("shops");
            if (arr.isArray()) {
                for (JsonNode s : arr) {
                    shops.add(Map.of(
                            "id", s.path("id").asText(),
                            "name", s.path("name").asText(),
                            "source", s.path("source").asText("meituan")
                    ));
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("account", resp.path("account").asText(""));
            result.put("shops", shops);
            result.put("count", shops.size());
            return result;
        }
        throw new IOException(resp.path("message").asText("爬取失败"));
    }

    /**
     * 抓取指定店铺的全部评价（监听评价接口 + 翻页），返回结构化评价列表
     */
    public Map<String, Object> scrapeReviews(String shopName) throws IOException {
        logger.info("[Browser] scrapeReviews for shop={}", shopName);
        JsonNode resp = sendCommand("scrape_reviews",
                Map.of("shopName", shopName == null ? "" : shopName));
        String type = resp.path("type").asText();
        if ("reviews_fetched".equals(type)) {
            List<Map<String, Object>> list = new ArrayList<>();
            JsonNode arr = resp.path("reviews");
            if (arr.isArray()) {
                for (JsonNode r : arr) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("platformReviewId", r.path("platformReviewId").asText(""));
                    m.put("reviewer", r.path("reviewer").asText(""));
                    m.put("rating", r.path("rating").asInt(0));
                    m.put("content", r.path("content").asText(""));
                    m.put("reviewTime", r.path("reviewTime").asText(""));
                    m.put("replyStatus", r.path("replyStatus").asText("unreplied"));
                    m.put("replyContent", r.path("replyContent").asText(""));
                    m.put("replyTime", r.path("replyTime").asText(""));
                    list.add(m);
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("reviews", list);
            result.put("total", resp.path("total").asInt(list.size()));
            result.put("skipped", resp.path("skipped").asInt(0));
            return result;
        }
        if ("SHOP_NOT_FOUND".equals(resp.path("code").asText(""))) {
            throw new ShopNotFoundException(resp.path("message").asText("该店铺暂未寻到，请重新执行"));
        }
        throw new IOException(resp.path("message").asText("抓取评价失败"));
    }

    /**
     * 批量回复评价（RPA 模式：OCR 定位按钮 + CDP 键鼠注入）
     * 美团 mtgsig 签名深度绑定请求体，接口直调不可行（重放改 body 即 403），
     * 故走浏览器 RPA：监听页面真实 comment/reply POST 响应（code=0）判定成败。
     * args: { shopName, items: [{platformReviewId, replyText}] }
     * 返回 { type: "reply_done", results: [{platformReviewId, success, message}] }
     */
    public JsonNode replyReviews(String shopName, List<Map<String, Object>> items) throws IOException {
        logger.info("[Browser] replyReviews shop={} count={}", shopName, items == null ? 0 : items.size());
        Map<String, Object> args = new HashMap<>();
        args.put("shopName", shopName == null ? "" : shopName);
        args.put("items", items == null ? List.of() : items);
        JsonNode resp = sendCommand("reply_reviews", args);
        return resp;
    }

    /**
     * 执行申诉（浏览器 CDP 全自动）
     */
    public JsonNode runAppeal(Map<String, Object> args) throws IOException {
        logger.info("[Browser] runAppeal for shop={} reviewer={}", args.get("shopName"), args.get("reviewer"));
        JsonNode resp = sendCommand("run_appeal", args);
        return ensureShopFound(resp);
    }

    /**
     * 回查申诉结果
     */
    public JsonNode checkAppealResult(Map<String, Object> args) throws IOException {
        logger.info("[Browser] checkAppealResult");
        return ensureShopFound(sendCommand("check_appeal", args));
    }

    /**
     * 抓取负反馈列表（美团负反馈页面，监听接口响应）
     */
    public JsonNode scrapeNegativeFeedbacks(Map<String, Object> args) throws IOException {
        logger.info("[Browser] scrapeNegativeFeedbacks shop={}", args.get("shopName"));
        return sendCommand("scrape_negative", args);
    }

    /** 抓取食安险负反馈明细（美团规则中心明细页，拦截 indicator/detail 响应） */
    public JsonNode scrapeFoodSafety(String shopName) throws IOException {
        logger.info("[Browser] scrapeFoodSafety shop={}", shopName);
        Map<String, Object> args = new HashMap<>();
        args.put("shopName", shopName == null ? "" : shopName);
        return ensureShopFound(sendCommand("scrape_food_safety", args));
    }

    /** 食安险自动申诉（表单页勾选订单+填理由+提交）；poiId 已知时直接复用，避免重复探测 */
    public JsonNode appealFoodSafety(String shopName, List<String> orderNos, String reason, String poiId) throws IOException {
        logger.info("[Browser] appealFoodSafety shop={} orders={}", shopName, orderNos);
        Map<String, Object> args = new HashMap<>();
        args.put("shopName", shopName == null ? "" : shopName);
        args.put("orderNos", orderNos == null ? List.of() : orderNos);
        args.put("reason", reason == null ? "" : reason);
        args.put("poiId", poiId == null ? "" : poiId);
        return ensureShopFound(sendCommand("appeal_food_safety", args));
    }

    /** 食安险申诉状态回查（拦截 getOrdersWithStatus 响应）；poiId 已知时直接复用 */
    public JsonNode checkFoodSafety(String shopName, String poiId) throws IOException {
        logger.info("[Browser] checkFoodSafety shop={}", shopName);
        Map<String, Object> args = new HashMap<>();
        args.put("shopName", shopName == null ? "" : shopName);
        args.put("poiId", poiId == null ? "" : poiId);
        return ensureShopFound(sendCommand("check_food_safety", args));
    }

    /** 统一识别 Python 侧 SHOP_NOT_FOUND，抛出让调用方中止整批任务的异常 */
    private JsonNode ensureShopFound(JsonNode resp) throws ShopNotFoundException {
        if ("SHOP_NOT_FOUND".equals(resp.path("code").asText(""))) {
            throw new ShopNotFoundException(resp.path("message").asText("该店铺暂未寻到，请重新执行"));
        }
        return resp;
    }

    /**
     * 关闭浏览器
     */
    public void close() {
        try {
            if (running) {
                sendCommand("close", null);
            }
        } catch (Exception e) {
            logger.warn("[Browser] close error: {}", e.getMessage());
        } finally {
            destroyProcess();
        }
    }

    private void destroyProcess() {
        running = false;
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }
    }

    @PreDestroy
    public void cleanup() {
        close();
    }
}
