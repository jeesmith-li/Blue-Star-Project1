package com.example.meituan.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 应用配置类
 */
public class AppConfig {
    private static final String CONFIG_FILE = "application.properties";
    private final Properties properties;

    public AppConfig() {
        this.properties = new Properties();
        loadConfig();
    }

    private void loadConfig() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + CONFIG_FILE, e);
        }
    }

    public String getPythonExecutable() {
        return properties.getProperty("ocr.python.executable", "python");
    }

    public String getOcrScriptPath() {
        return properties.getProperty("ocr.script.path", "python/ocr_subprocess.py");
    }

    /**
     * 解析 Python 脚本路径：优先使用编译后的 .pyc（源码保护），不存在则回退到 .py。
     * 入参为相对工作目录的 .py 路径（如 python/xxx.py），返回可直接执行的脚本文件。
     */
    public File resolveScript(String relativePyPath) {
        File py = new File(System.getProperty("user.dir"), relativePyPath);
        File pyc = new File(py.getAbsolutePath() + "c"); // xxx.py -> xxx.pyc
        if (pyc.exists()) {
            return pyc;
        }
        return py;
    }

    public int getOcrTimeoutSeconds() {
        return Integer.parseInt(properties.getProperty("ocr.timeout.seconds", "10"));
    }

    public String getTempDir() {
        return properties.getProperty("temp.dir", "data/temp");
    }

    public String getScreenshotDir() {
        return properties.getProperty("screenshot.dir", "data/screenshots");
    }

    public String getDebugDir() {
        return properties.getProperty("debug.dir", "data/debug");
    }

    public boolean isDebugMode() {
        return Boolean.parseBoolean(properties.getProperty("debug.mode", "true"));
    }

    public double getOcrConfidenceThreshold() {
        return Double.parseDouble(properties.getProperty("ocr.confidence.threshold", "0.7"));
    }

    public void ensureDirectories() {
        ensureDirectory(getTempDir());
        ensureDirectory(getScreenshotDir());
        ensureDirectory(getDebugDir());
    }

    private void ensureDirectory(String dir) {
        try {
            Path path = Paths.get(dir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directory: " + dir, e);
        }
    }
}
