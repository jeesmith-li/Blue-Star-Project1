package com.example.meituan.robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

/**
 * 剪贴板控制器
 * 用于通过剪贴板输入文本
 */
public class ClipboardController {
    private static final Logger logger = LoggerFactory.getLogger(ClipboardController.class);

    private final Clipboard clipboard;

    public ClipboardController() {
        this.clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    /**
     * 设置剪贴板内容
     */
    public void setText(String text) {
        StringSelection selection = new StringSelection(text);
        clipboard.setContents(selection, null);
        logger.debug("[CLIPBOARD] Set text: {} chars", text.length());
    }

    /**
     * 获取剪贴板内容
     */
    public String getText() {
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                logger.debug("[CLIPBOARD] Got text: {} chars", text.length());
                return text;
            }
        } catch (Exception e) {
            logger.warn("[CLIPBOARD] Failed to get text", e);
        }
        return "";
    }

    /**
     * 清空剪贴板
     */
    public void clear() {
        setText("");
    }
}
