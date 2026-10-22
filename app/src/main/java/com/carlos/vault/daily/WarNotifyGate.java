package com.carlos.vault.daily;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 战争通知（App 侧短信通道）纯逻辑：手机号校验、发送门控（开关/权限/事件去重/5 分钟节流）、
 * 事件文本格式化。纯 JVM 可测，不 import android——SmsManager 调用在
 * {@link WarNotifyPoller}，持久化在 {@link WarNotifyStore}。
 *
 * <p>节流口径（任务书）：同一 eventId 只发一次；短信通道最多每 5 分钟一条（防轰炸）。
 * 权限被拒时调用方降级为只留日志（UI 引导授权后自动恢复）。
 */
public final class WarNotifyGate {

    /** 短信节流：最多每 5 分钟一条。 */
    public static final long SMS_INTERVAL_MS = 5 * 60_000L;
    /** 已发 eventId 记录上限（超限裁最旧）。 */
    public static final int SENT_CAP = 200;

    private WarNotifyGate() {
    }

    /** 手机号宽松校验：5~20 位纯数字（国际号段兼容）。 */
    public static boolean phoneValid(String phone) {
        if (phone == null) return false;
        String p = phone.trim();
        if (p.length() < 5 || p.length() > 20) return false;
        for (int i = 0; i < p.length(); i++) {
            if (!Character.isDigit(p.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 发送门控：返回 null=可发；否则返回不可发原因（中文，日志用）。
     * 判定序：开关 → 手机号 → 权限 → eventId 去重 → 5 分钟节流。
     */
    public static String gate(boolean smsEnabled, String phone, boolean hasPermission,
                              long lastSmsAt, long now, String eventId, Set<String> sentIds) {
        if (!smsEnabled) return "短信通知未开启";
        if (!phoneValid(phone)) return "手机号未配置";
        if (!hasPermission) return "缺 SEND_SMS 权限";
        if (eventId != null && !eventId.isEmpty() && sentIds != null && sentIds.contains(eventId)) {
            return "事件已发过";
        }
        if (lastSmsAt > 0 && now - lastSmsAt < SMS_INTERVAL_MS) return "5 分钟节流中";
        return null;
    }

    /**
     * 事件 → 短信文本（控制在一两条短信内）：`[号库]敌情·进攻 宿敌(3,4) 320s后到 k888`。
     * 撤回/到达是已发生事件，不带 ETA；字段缺失优雅降级不空指针。
     */
    public static String formatSms(String targetName, String action, boolean hasXY, int x, int y,
                                   long endSec, long nowSec, long sid) {
        StringBuilder sb = new StringBuilder("[号库]敌情·")
                .append(action == null || action.isEmpty() ? "动静" : action);
        sb.append(' ').append(targetName == null || targetName.isEmpty() ? "?" : targetName);
        if (hasXY) sb.append('(').append(x).append(',').append(y).append(')');
        if (endSec > 0 && endSec > nowSec && !"撤回".equals(action) && !"到达".equals(action)) {
            sb.append(' ').append(endSec - nowSec).append("s后到");
        }
        if (sid > 0) sb.append(" k").append(sid);
        return sb.toString();
    }

    /** 最近事件列表行（UI）：`MM-dd HH:mm 进攻 宿敌(3,4) @k888`。 */
    public static String formatEventRow(long detectedAt, String action, String targetName,
                                        boolean hasXY, int x, int y, long sid) {
        String t = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(detectedAt));
        StringBuilder sb = new StringBuilder(t).append(' ')
                .append(action == null ? "" : action).append(' ')
                .append(targetName == null ? "" : targetName);
        if (hasXY) sb.append('(').append(x).append(',').append(y).append(')');
        if (sid > 0) sb.append(" @k").append(sid);
        return sb.toString();
    }
}
