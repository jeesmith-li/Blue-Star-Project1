package com.example.meituan.robot;

import com.example.meituan.ocr.OcrTextBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.InputEvent;

/**
 * Robot 控制器 - 鼠标键盘操作
 * 核心原则：所有点击位置必须来自 OCR 实时识别的 bounding box
 * 严禁使用固定坐标
 */
public class RobotController {
    private static final Logger logger = LoggerFactory.getLogger(RobotController.class);

    private final Robot robot;
    private final int clickDelay;
    private final int typeDelay;
    private final int moveDelay;

    public RobotController(int clickDelay, int typeDelay, int moveDelay) {
        try {
            this.robot = new Robot();
            this.clickDelay = clickDelay;
            this.typeDelay = typeDelay;
            this.moveDelay = moveDelay;
        } catch (AWTException e) {
            throw new RuntimeException("Failed to initialize Robot", e);
        }
    }

    /**
     * 点击 OCR 识别出的文本框中心
     * 坐标来自实时 OCR 识别，不是固定值
     */
    public void click(OcrTextBox textBox) {
        int centerX = textBox.getCenterX();
        int centerY = textBox.getCenterY();

        logger.info("[CLICK] Target='{}' at center=({},{})", textBox.getText(), centerX, centerY);

        // 移动鼠标到目标中心
        robot.mouseMove(centerX, centerY);
        robot.delay(moveDelay);

        // 点击
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(clickDelay);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(clickDelay);

        logger.debug("[CLICK] Completed");
    }

    /**
     * 点击指定坐标
     * 注意：这个坐标必须来自实时视觉识别，不能是写死的固定值
     */
    public void click(int x, int y) {
        logger.info("[CLICK] Position=({},{})", x, y);

        robot.mouseMove(x, y);
        robot.delay(moveDelay);

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(clickDelay);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(clickDelay);
    }

    /**
     * 双击
     */
    public void doubleClick(OcrTextBox textBox) {
        int centerX = textBox.getCenterX();
        int centerY = textBox.getCenterY();

        logger.info("[DOUBLE_CLICK] Target='{}' at center=({},{})", textBox.getText(), centerX, centerY);

        robot.mouseMove(centerX, centerY);
        robot.delay(moveDelay);

        for (int i = 0; i < 2; i++) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(50);
        }
    }

    /**
     * 按键
     */
    public void keyPress(int keyCode) {
        robot.keyPress(keyCode);
        robot.delay(typeDelay);
        robot.keyRelease(keyCode);
        robot.delay(typeDelay);
    }

    /**
     * 组合键 (如 Ctrl+V)
     */
    public void keyCombo(int... keyCodes) {
        // 按下所有键
        for (int keyCode : keyCodes) {
            robot.keyPress(keyCode);
            robot.delay(10);
        }

        robot.delay(typeDelay);

        // 释放所有键（逆序）
        for (int i = keyCodes.length - 1; i >= 0; i--) {
            robot.keyRelease(keyCodes[i]);
            robot.delay(10);
        }

        robot.delay(typeDelay);
    }

    /**
     * 鼠标滚轮
     * @param wheelAmt 正数向下滚动，负数向上滚动
     */
    public void mouseWheel(int wheelAmt) {
        logger.debug("[SCROLL] wheelAmt={}", wheelAmt);
        robot.mouseWheel(wheelAmt);
        robot.delay(500); // 等待滚动动画
    }

    /**
     * 延迟
     */
    public void delay(int ms) {
        robot.delay(ms);
    }

    /**
     * 模拟 Ctrl+V 粘贴
     */
    public void pasteFromClipboard() {
        try {
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_V);
            robot.delay(clickDelay);
            robot.keyRelease(java.awt.event.KeyEvent.VK_V);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            robot.delay(typeDelay);
            logger.debug("[ROBOT] Pasted from clipboard");
        } catch (Exception e) {
            logger.error("[ROBOT] Failed to paste", e);
        }
    }

    /**
     * 平滑拖拽（用于滚动）
     */
    public void smoothDrag(int startX, int startY, int endX, int endY, int steps) {
        logger.info("[ROBOT] Smooth drag from ({},{}) to ({},{}) in {} steps",
            startX, startY, endX, endY, steps);

        try {
            // 移动到起点
            robot.mouseMove(startX, startY);
            robot.delay(moveDelay);

            // 按下鼠标
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(clickDelay);

            // 平滑移动
            for (int i = 1; i <= steps; i++) {
                int x = startX + (endX - startX) * i / steps;
                int y = startY + (endY - startY) * i / steps;
                robot.mouseMove(x, y);
                robot.delay(10);
            }

            // 释放鼠标
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(clickDelay);

            logger.debug("[ROBOT] Smooth drag completed");
        } catch (Exception e) {
            logger.error("[ROBOT] Failed to perform smooth drag", e);
        }
    }
}
