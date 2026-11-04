package com.example.meituan.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;

/**
 * 剪贴板控制器
 */
public class ClipboardController {
    private static final Logger logger = LoggerFactory.getLogger(ClipboardController.class);

    /**
     * 复制文本到剪贴板
     */
    public void copy(String text) {
        try {
            StringSelection selection = new StringSelection(text);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, null);
            logger.debug("[CLIPBOARD] Copied to clipboard: {}", text.substring(0, Math.min(20, text.length())));
        } catch (Exception e) {
            logger.error("[CLIPBOARD] Failed to copy to clipboard", e);
        }
    }
}
