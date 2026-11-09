package com.carlos.vault.daily;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

/**
 * 战争通知（App 侧短信通道）纯逻辑：手机号校验、发送门控（开关/权限/事件去重/5 分钟节流）、
 * 事件文本格式化。纯 JVM 可测，不 import android——SmsManager 调用在
 * {@link WarNotifyPoller}，持久化在 {@link WarNotifyStore}。
 *
 * <p>节流口径（任务书）：同一 eventId 只发一次；短信通道最多每 5 分钟一条（防轰炸）。
 * 权限被拒时调用方降级为只留日志（UI 引导授权后自动恢复）。
 *
 * <p>v4.1.8：新增事件级 {@link #formatSms(JSONObject, long)}（昵称/坐标/行为/兵力/ETA/结算，
 * 超 2 条短信预算时裁剪兵力明细保留合计）与最近事件列表的子行/详情格式化。
 */
public final class WarNotifyGate {

    /** 短信节流：最多每 5 分钟一条。 */
    public static final long SMS_INTERVAL_MS = 5 * 60_000L;
    /** 已发 eventId 记录上限（超限裁最旧）。 */
    public static final int SENT_CAP = 200;
    /**
     * 短信字数预算：中文短信 70 字/条（UCS-2），多段拆分后每段 67 字——
     * 控制在 2 条内即 ≤134 字，超出先裁兵力明细（保留合计），仍超再整体硬裁。
     */
    public static final int SMS_BUDGET_CHARS = 134;

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
        return sb.toString();   // 2026-11-02：通知里不带区服号等编号
    }

    /** 最近事件列表行（UI）：`MM-dd HH:mm 进攻 宿敌(3,4) @k888`。 */
    /** 观察点事件（只有 text）：列表行用 text 首行。 */
    public static String formatTextRow(long detectedAt, String text) {
        String t = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(detectedAt));
        String first = text == null ? "" : text.split("\n", -1)[0];
        return t + " " + first;
    }

    public static String formatEventRow(long detectedAt, String action, String targetName,
                                        boolean hasXY, int x, int y, long sid) {
        String t = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(detectedAt));
        StringBuilder sb = new StringBuilder(t).append(' ')
                .append(action == null ? "" : action).append(' ')
                .append(targetName == null ? "" : targetName);
        if (hasXY) sb.append('(').append(x).append(',').append(y).append(')');
        return sb.toString();   // 2026-11-02：列表行也不带区服号
    }

    // ---------------- v4.1.8：事件级短信 / 列表子行 / 详情 ----------------

    /**
     * 事件 JSON → 短信文本（完整版）：初始事件输出 行为/发起人/坐标/兵力/ETA，
     * 结算事件（phase=settle）输出 结果/耗时/破城。字段缺失优雅降级。
     * 长度控制：完整兵力超预算 → 裁剪为合计；仍超 → 硬裁到预算。
     */
    public static String formatSms(JSONObject e, long nowSec) {
        if (e == null) return "[号库]敌情";
        String full = smsCore(e, nowSec, true);
        if (full.length() <= SMS_BUDGET_CHARS) return full;
        String brief = smsCore(e, nowSec, false);
        if (brief.length() <= SMS_BUDGET_CHARS) return brief;
        return brief.substring(0, SMS_BUDGET_CHARS);
    }

    /**
     * 短信主体组装。withTroops=true 带完整兵力明细；false 只在超预算候选里带「合计N」。
     * （预算判定由 {@link #formatSms(JSONObject, long)} 做：先试全量、再试只留合计、最后硬裁。）
     */
    private static String smsCore(JSONObject e, long nowSec, boolean withTroops) {
        boolean settle = "settle".equals(e.optString("phase", ""));
        String attacker = firstNonEmpty(e.optString("attackerName", ""),
                e.optString("targetName", ""), "?");
        StringBuilder sb;
        if (settle) {
            sb = new StringBuilder("[号库]结算·")
                    .append(firstNonEmpty(e.optString("outcome", ""), e.optString("action", ""), "?"));
            sb.append(' ').append(attacker);
            long duration = e.optLong("durationSeconds", 0);
            if (duration > 0) {
                sb.append(" 耗时").append(duration / 60).append('分').append(duration % 60).append('秒');
            }
            String breach = e.optString("breach", "");
            if (!breach.isEmpty()) {
                sb.append(" 破城:").append(breach);
                String codes = e.optString("battleCodes", "");
                if (!codes.isEmpty()) sb.append('(').append(codes).append(')');
            }
        } else {
            String behavior = firstNonEmpty(e.optString("behavior", ""),
                    e.optString("action", ""), "动静");
            sb = new StringBuilder("[号库]敌情·").append(behavior).append(' ').append(attacker);
            JSONObject sp = e.optJSONObject("startPoint");
            if (sp != null) {
                sb.append('(').append(sp.optInt("x")).append(',').append(sp.optInt("y")).append(')');
            }
            JSONObject ep = e.optJSONObject("endpoint");
            if (ep != null) {
                sb.append("→(").append(ep.optInt("x")).append(',').append(ep.optInt("y")).append(')');
            }
        }
        if (withTroops) {
            String troops = e.optString("troops", "");
            if (!troops.isEmpty()) sb.append(' ').append(troops);
        } else if (!settle) {
            String brief = briefTroops(e.optString("troops", ""));
            if (!brief.isEmpty()) sb.append(' ').append(brief);
        }
        if (!settle) {
            long endSec = e.optLong("endtime", 0);
            if (endSec > 0 && endSec > nowSec) sb.append(' ').append(endSec - nowSec).append("s后到");
        }
        long sid = e.optLong("sid", 0);
        return sb.toString();   // 2026-11-02：通知里不带区服号等编号
    }

    /** 兵力明细 → 「合计N」（无合计信息返回空串）。 */
    static String briefTroops(String troops) {
        if (troops == null) return "";
        int i = troops.indexOf("（合计");
        if (i < 0) return "";
        int j = troops.indexOf('）', i);
        if (j < 0) return "";
        return troops.substring(i + 1, j);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** 最近事件列表子行（UI 第二行）：初始=行为+兵力+ETA；结算=结果+耗时+破城。 */
    public static String formatEventSub(JSONObject e) {
        if (e == null) return "";
        boolean settle = "settle".equals(e.optString("phase", ""));
        StringBuilder sb = new StringBuilder();
        if (settle) {
            sb.append("结算 ").append(firstNonEmpty(e.optString("outcome", ""), "?"));
            long duration = e.optLong("durationSeconds", 0);
            if (duration > 0) {
                sb.append(" · 耗时").append(duration / 60).append('分').append(duration % 60).append('秒');
            }
            String breach = e.optString("breach", "");
            if (!breach.isEmpty()) {
                sb.append(" · 破城:").append(breach);
                String codes = e.optString("battleCodes", "");
                if (!codes.isEmpty()) sb.append('(').append(codes).append(')');
            }
        } else {
            sb.append(firstNonEmpty(e.optString("behavior", ""), e.optString("action", ""), "动静"));
            String troops = e.optString("troops", "");
            if (!troops.isEmpty()) sb.append(" · ").append(troops);
            long eta = e.optLong("etaSeconds", 0);
            if (eta > 0) sb.append(" · ").append(eta).append("s后到");
        }
        return sb.toString();
    }

    /** 事件详情纯文本（点开弹窗）：6 项字段 + 结算 + 来源/事件号，缺失行自动省略。 */
    public static String formatEventDetail(JSONObject e) {
        if (e == null) return "";
        String fullText = e.optString("text", "");
        if (!fullText.isEmpty()) return fullText;   // 观察点事件：文案已是完整正文
        boolean settle = "settle".equals(e.optString("phase", ""));
        StringBuilder sb = new StringBuilder();
        long at = e.optLong("detectedAt", 0);
        if (at > 0) {
            sb.append("时间：").append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(at)));
        }
        appendLine(sb, "行为：", firstNonEmpty(e.optString("behavior", ""), e.optString("action", "")));
        appendLine(sb, "发起人：", firstNonEmpty(e.optString("attackerName", ""), e.optString("targetName", "")));
        JSONObject sp = e.optJSONObject("startPoint");
        if (sp != null) {
            sb.append("\n发起人坐标：(").append(sp.optInt("x")).append(',').append(sp.optInt("y")).append(')');
        }
        JSONObject ep = e.optJSONObject("endpoint");
        if (ep != null) {
            sb.append("\n目标坐标：(").append(ep.optInt("x")).append(',').append(ep.optInt("y")).append(')');
        }
        appendLine(sb, "兵力：", e.optString("troops", ""));
        if (!settle) {
            long endSec = e.optLong("endtime", 0);
            if (endSec > 0) {
                sb.append("\n到达：").append(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(endSec * 1000L)));
                long eta = e.optLong("etaSeconds", 0);
                if (eta > 0) sb.append("（").append(eta).append("s 后）");
            }
        } else {
            sb.append("\n结算：").append(firstNonEmpty(e.optString("outcome", ""), "?"));
            long duration = e.optLong("durationSeconds", 0);
            if (duration > 0) {
                sb.append(" · 耗时").append(duration / 60).append('分').append(duration % 60).append('秒');
            }
            String breach = e.optString("breach", "");
            if (!breach.isEmpty()) {
                sb.append(" · 破城:").append(breach);
                String codes = e.optString("battleCodes", "");
                if (!codes.isEmpty()) sb.append("（战报 ").append(codes).append('）');
            }
        }
        return sb.toString();   // 2026-11-02：详情里同样不带 uid/aid/区服/事件号
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(label).append(value);
    }
}
