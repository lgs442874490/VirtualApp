package com.carlos.vaultserver;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 统一北京时间（GMT+8）工具。服务器系统时区为 Etc/UTC——SimpleDateFormat 的
 * Locale.CHINA 只影响语言习惯、不改变时区，此前所有按 JVM 默认时区生成的日期键
 * （st:yyyy-MM-dd）与文案（下一次可领…）都比 App 端（北京）晚 8 小时，造成
 * 「跨日 0~8 点全部显示未开始」「可领时间看起来是昨天」两类错位。修复后：
 * 日期键、调度窗口、一切面向用户的时间字符串一律经本类生成。
 */
public final class Beijing {

    public static final TimeZone TZ = TimeZone.getTimeZone("GMT+8");
    public static final java.time.ZoneId ZONE_ID = java.time.ZoneId.of("GMT+8");

    public static SimpleDateFormat fmt(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.CHINA);
        f.setTimeZone(TZ);
        return f;
    }

    private Beijing() {
    }
}
