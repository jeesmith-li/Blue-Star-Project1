package com.example.meituan.screenshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import com.example.meituan.util.TimeFormats;
import java.time.LocalDateTime;

/**
 * 屏幕截图工具类
 * 使用 java.awt.Robot 动态获取屏幕尺寸并截图
 * 严格禁止使用固定分辨率和固定坐标
 */
public class ScreenCapture {
    private static final Logger logger = LoggerFactory.getLogger(ScreenCapture.class);

    private final Robot robot;

    public ScreenCapture() {
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            throw new RuntimeException("Failed to initialize Robot for screen capture", e);
        }
    }

    /**
     * 捕获整个屏幕
     * 动态获取当前屏幕尺寸，不使用固定分辨率
     */
    public BufferedImage captureFullScreen() {
        Rectangle screenBounds = getScreenBounds();
        logger.debug("[SCREEN] Capturing full screen: {}x{}", screenBounds.width, screenBounds.height);

        BufferedImage screenshot = robot.createScreenCapture(screenBounds);

        logger.debug("[SCREEN] Capture complete: size={}x{}", screenshot.getWidth(), screenshot.getHeight());
        return screenshot;
    }

    /**
     * 动态获取屏幕边界
     * 支持多显示器环境
     */
    private Rectangle getScreenBounds() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        // 获取主屏幕配置
        GraphicsDevice primaryScreen = screens[0];
        DisplayMode mode = primaryScreen.getDisplayMode();

        Rectangle bounds = new Rectangle(0, 0, mode.getWidth(), mode.getHeight());

        logger.trace("[SCREEN] Primary screen bounds: x={}, y={}, width={}, height={}",
            bounds.x, bounds.y, bounds.width, bounds.height);

        return bounds;
    }

    /**
     * 保存截图到文件
     * 使用时间戳生成唯一文件名
     */
    public File saveScreenshot(BufferedImage image, String directory, String prefix) throws IOException {
        String timestamp = LocalDateTime.now().format(TimeFormats.TIMESTAMP);
        String filename = String.format("%s_%s.png", prefix, timestamp);
        File outputFile = new File(directory, filename);

        ImageIO.write(image, "PNG", outputFile);
        logger.debug("[SCREEN] Saved screenshot: {}", outputFile.getAbsolutePath());

        return outputFile;
    }

    /**
     * 捕获并保存屏幕截图
     */
    public File captureAndSave(String directory, String prefix) throws IOException {
        BufferedImage screenshot = captureFullScreen();
        return saveScreenshot(screenshot, directory, prefix);
    }
}
