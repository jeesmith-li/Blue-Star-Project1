package com.example.meituan.util;

import java.time.format.DateTimeFormatter;

/**
 * 全局统一的日期时间格式，避免各模块重复定义。
 */
public final class TimeFormats {

    /** 截图/日志文件名使用的时间戳 */
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 数据库与接口中使用的日期时间 */
    public static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormats() {
    }
}
