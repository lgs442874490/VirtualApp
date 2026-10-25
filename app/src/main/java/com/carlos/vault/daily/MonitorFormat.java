package com.carlos.vault.daily;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 真机监控事件渲染 / 过滤 / seq 游标（纯 Java，无 Android 依赖，JVM 单测直跑）。
 *
 * <p>契约：{@code design/真机监控-服务端任务级协议监控-20261025.md} §2 事件模型 + §3.3 API。
 * 服务端 {@code POST /v1/vault/monitor} 返回的每条事件都是扁平 JSONObject：
 * <pre>
 * {"seq":101,"ts":1792916000000,"kind":"task.start","fpid":"...","sid":"888",
 *  "fn":"login_rewards","name":"登录好礼","trigger":"节奏", ...}
 * </pre>
 * 本类只做「事件 → 人读单行文案」「kind → 过滤分类」「seq 去重/游标推进」「HH:mm:ss 时间」，
 * 不持有任何状态、不读写盘、不联网，页面与单测共用同一套口径。
 *
 * <p><b>行文案示例</b>（{@link #formatEvent(JSONObject)}，单行上限 {@link #MAX_LINE} 字符）：
 * <pre>
 * 16:13:20 #101 ▶ 登录好礼 开始（节奏）
 * 16:13:20 #102 → req_login_reward {"type":1}
 * 16:13:20 #103 ← req_login_reward code=0 48ms {"rewards":[...]}
 * 16:13:20 #104 ✅ 登录好礼 · 已领取 · 钻石×30 · 61ms
 * 16:13:30 #105 会话 takeover · 检测到顶号/人工登录
 * 16:13:40 #106 推送 notify_daily_claim_info
 * 16:13:50 #107 打点 用户打点：原生游戏领取前
 * </pre>
 *
 * <p><b>防御口径</b>：所有字段走 {@code opt*}，缺字段/类型不符/未知 kind 一律不抛异常；
 * 未识别 kind 原样输出该条 JSON（同样截断）。
 */
public final class MonitorFormat {

    /** 列表行单行字符上限（超过截断并以 … 结尾，避免超长 TextView 卡顿）。 */
    public static final int MAX_LINE = 400;

    // ===== 过滤分类（页面 chips 与 kind 归类唯一事实源）=====
    /** 全部（含未识别 kind）。 */
    public static final int CAT_ALL = 0;
    /** 任务：kind 以 {@code task.} 开头（task.start/task.end）。 */
    public static final int CAT_TASK = 1;
    /** 协议：kind 以 {@code io.} 开头（io.req/io.rsp/io.err）。 */
    public static final int CAT_PROTO = 2;
    /** 会话：kind={@code session}。 */
    public static final int CAT_SESSION = 3;
    /** 推送：kind={@code notify}。 */
    public static final int CAT_NOTIFY = 4;
    /** 打点：kind={@code mark}（用户手动打点）。 */
    public static final int CAT_MARK = 5;
    /** 未识别 kind（不属于任何分类，仅「全部」下显示）。 */
    public static final int CAT_UNKNOWN = -1;

    /** chips 文案（下标 = CAT_*；CAT_UNKNOWN 无独立 chip）。 */
    public static final String[] CATEGORY_LABELS = {"全部", "任务", "协议", "会话", "推送", "打点"};

    /** 单字段摘要上限（args/data 在行内只展示摘要，完整 JSON 在详情弹窗）。 */
    private static final int SUMMARY_LEN = 160;

    private static final ThreadLocal<SimpleDateFormat> TIME_FMT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss", Locale.US);
                }
            };

    private MonitorFormat() {
    }

    // ================= kind → 过滤分类 =================

    /** kind 归类：任务=task.*，协议=io.*，会话=session，推送=notify，打点=mark；其余 {@link #CAT_UNKNOWN}。 */
    public static int categoryOf(String kind) {
        if (kind == null) return CAT_UNKNOWN;
        String k = kind.trim();
        if ("session".equals(k)) return CAT_SESSION;
        if ("notify".equals(k)) return CAT_NOTIFY;
        if ("mark".equals(k)) return CAT_MARK;
        if (k.startsWith("task.")) return CAT_TASK;
        if (k.startsWith("io.")) return CAT_PROTO;
        return CAT_UNKNOWN;
    }

    /** 该事件在指定分类下是否展示（{@link #CAT_ALL} 恒真）。 */
    public static boolean accept(int category, JSONObject ev) {
        if (category == CAT_ALL) return true;
        if (ev == null) return false;
        return categoryOf(ev.optString("kind", "")) == category;
    }

    /** 按分类过滤（保持入参顺序；null 安全）。 */
    public static List<JSONObject> filter(List<JSONObject> events, int category) {
        List<JSONObject> out = new ArrayList<>();
        if (events == null) return out;
        for (JSONObject ev : events) {
            if (accept(category, ev)) out.add(ev);
        }
        return out;
    }

    // ================= 事件 → 单行文案 =================

    /**
     * 事件渲染为列表单行文案：{@code HH:mm:ss #seq <正文>}。
     * 缺 ts/seq 时省略对应前缀；任何字段异常都退化为该条 JSON 文本（永不抛异常）。
     */
    public static String formatEvent(JSONObject ev) {
        if (ev == null) return "";
        try {
            long ts = ev.optLong("ts", 0L);
            long seq = ev.optLong("seq", 0L);
            StringBuilder sb = new StringBuilder(96);
            if (ts > 0L) sb.append(formatTime(ts)).append(' ');
            if (seq > 0L) sb.append('#').append(seq).append(' ');
            sb.append(body(ev.optString("kind", ""), ev));
            return clip(oneLine(sb.toString()), MAX_LINE);
        } catch (Throwable t) {
            // 渲染异常不得让整页列表崩：退化为该条 JSON 原文（同样截断）
            return clip(oneLine(safeJson(ev)), MAX_LINE);
        }
    }

    /** 正文（不含时间/seq 前缀）。 */
    private static String body(String kind, JSONObject ev) {
        switch (kind == null ? "" : kind) {
            case "task.start":
                return "▶ " + taskName(ev) + " 开始" + parenthesized(ev.optString("trigger", ""));
            case "task.end":
                return taskEnd(ev);
            case "io.req":
                return "→ " + proto(ev) + summarizeSuffix(ev.opt("args"), " ");
            case "io.rsp":
                return "← " + proto(ev) + " code=" + ev.optInt("code", 0)
                        + durSuffix(ev) + summarizeSuffix(ev.opt("data"), " ");
            case "session": {
                String sessionEvent = oneLine(ev.optString("event", ""));
                String sessionMsg = oneLine(ev.optString("msg", ""));
                if (sessionEvent.isEmpty()) {
                    return sessionMsg.isEmpty() ? "会话" : "会话 " + sessionMsg;
                }
                return "会话 " + sessionEvent + (sessionMsg.isEmpty() ? "" : " · " + sessionMsg);
            }
            case "notify":
                return "推送 " + proto(ev) + summarizeSuffix(ev.optString("msg", ""), " · ");
            case "mark":
                return "打点 " + oneLine(ev.optString("msg", "（无文案）"));
            case "error":
                return "⚠️ 错误 " + oneLine(ev.optString("msg", "（无文案）"));
            default:
                return safeJson(ev);
        }
    }

    /** task.end：✅ 完成 / ⏭ 跳过 / ⚠️ 注意 / ❌ 失败 + 名称 + 原因 + 奖励 + 耗时。 */
    private static String taskEnd(JSONObject ev) {
        StringBuilder sb = new StringBuilder();
        String emoji = outcomeEmoji(ev);
        if (!emoji.isEmpty()) sb.append(emoji).append(' ');
        sb.append(taskName(ev));
        String msg = oneLine(ev.optString("msg", ""));
        if (!msg.isEmpty()) sb.append(" · ").append(msg);
        String rewards = oneLine(ev.optString("rewards", ""));
        if (!rewards.isEmpty()) sb.append(" · ").append(rewards);
        sb.append(durSuffix(ev));
        return sb.toString();
    }

    /**
     * 任务结果 emoji：FAIL/ERR/TIMEOUT → ❌，SKIP/FILTER/IGNORE → ⏭，
     * WARN/PARTIAL/LIMIT → ⚠️，CLAIM/DONE/OK/SUCCESS → ✅；未知分类不臆测（空串）。
     */
    static String outcomeEmoji(JSONObject ev) {
        String cat = ev.optString("cat", "").toUpperCase(Locale.US);
        if (cat.isEmpty()) return "";
        if (cat.contains("FAIL") || cat.contains("ERROR") || cat.contains("ERR")
                || cat.contains("TIMEOUT") || cat.contains("EXCEPTION")) return "❌";
        if (cat.contains("SKIP") || cat.contains("FILTER") || cat.contains("IGNORE")) return "⏭";
        if (cat.contains("WARN") || cat.contains("PARTIAL") || cat.contains("LIMIT")) return "⚠️";
        if (cat.contains("CLAIM") || cat.contains("DONE") || cat.contains("OK")
                || cat.contains("SUCCESS")) return "✅";
        return "";
    }

    /** 任务名：优先 name（中文名）→ fn（任务 id）→ 「任务」。 */
    private static String taskName(JSONObject ev) {
        String name = oneLine(ev.optString("name", ""));
        if (!name.isEmpty()) return name;
        String fn = oneLine(ev.optString("fn", ""));
        return fn.isEmpty() ? "任务" : fn;
    }

    /** 协议名：proto → notifyProto → 「?」。 */
    private static String proto(JSONObject ev) {
        String p = oneLine(ev.optString("proto", ""));
        if (!p.isEmpty()) return p;
        p = oneLine(ev.optString("notifyProto", ""));
        return p.isEmpty() ? "?" : p;
    }

    /** 耗时后缀：durMs>0 → 「 61ms」，否则空串。 */
    private static String durSuffix(JSONObject ev) {
        long dur = ev.optLong("durMs", 0L);
        return dur > 0L ? " " + dur + "ms" : "";
    }

    /** 括号文案：「（触发器）」，空则空串。 */
    private static String parenthesized(String s) {
        String t = oneLine(s);
        return t.isEmpty() ? "" : "（" + t + "）";
    }

    /** 摘要后缀：前缀 + 摘要；摘要为空则不追加。 */
    private static String summarizeSuffix(Object v, String prefix) {
        String s = summarize(v);
        return s.isEmpty() ? "" : prefix + s;
    }

    /**
     * args/data 摘要：JSON 容器 → 紧凑 JSON 文本；字符串 → 原样；数字/布尔 → 文本。
     * 空容器（{} / []）/null 返回空串；结果按 {@link #SUMMARY_LEN} 截断。
     */
    public static String summarize(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        String s;
        if (v instanceof JSONObject) s = v.toString();
        else if (v instanceof JSONArray) s = v.toString();
        else s = String.valueOf(v);
        if (s == null) return "";
        s = oneLine(s).trim();
        if (s.isEmpty() || "{}".equals(s) || "[]".equals(s) || "null".equals(s)) return "";
        return clip(s, SUMMARY_LEN);
    }

    // ================= 时间 =================

    /** 毫秒时间戳 → {@code HH:mm:ss}（服务端/本地同口径）；ts≤0 返回 {@code --:--:--}。 */
    public static String formatTime(long ts) {
        if (ts <= 0L) return "--:--:--";
        try {
            return TIME_FMT.get().format(new Date(ts));
        } catch (Throwable t) {
            return "--:--:--";
        }
    }

    // ================= seq 游标 / 去重 =================

    /**
     * 增量拉取结果：{@link #events} 为游标之后的新事件（按 seq 升序、同批重复 seq 已去重），
     * {@link #cursor} 为推进后的游标（旧游标与新事件最大 seq 的较大值）。
     */
    public static final class Delta {
        /** 新增事件（seq 升序，已去重）。 */
        public final List<JSONObject> events;
        /** 推进后的游标 = max(旧游标, 本批最大 seq)。 */
        public final long cursor;
        /** 本批最大 seq（无新事件时等于旧游标）。 */
        public final long maxSeq;

        Delta(List<JSONObject> events, long cursor, long maxSeq) {
            this.events = events;
            this.cursor = cursor;
            this.maxSeq = maxSeq;
        }

        /** 本批是否有新事件。 */
        public boolean isEmpty() {
            return events.isEmpty();
        }
    }

    /**
     * 按游标取新增事件并推进游标（页面增量拉取 + 单测共用）：
     * <ul>
     *   <li>只保留 {@code seq > cursor}；缺少 seq 字段的事件按 seq=0（不会被当成新增）；</li>
     *   <li>按 seq 升序稳定排序；同批重复 seq 只保留第一条；</li>
     *   <li>游标永不回退（max(旧游标, 本批最大 seq)）；</li>
     *   <li>incoming 为 null/空 → 空结果、游标不变。</li>
     * </ul>
     */
    public static Delta advance(long cursor, List<JSONObject> incoming) {
        long base = Math.max(0L, cursor);
        List<JSONObject> added = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        long max = base;
        if (incoming != null && !incoming.isEmpty()) {
            List<JSONObject> sorted = new ArrayList<>();
            for (JSONObject ev : incoming) {
                if (ev != null) sorted.add(ev);
            }
            // 稳定排序：seq 相同保持输入顺序（去重取第一条）。用 Collections.sort
            // 而不是 List.sort：minSdk 21 上 List#sort 是 API 24 才有的默认方法。
            Collections.sort(sorted, (a, b) ->
                    Long.compare(a.optLong("seq", 0L), b.optLong("seq", 0L)));
            for (JSONObject ev : sorted) {
                long seq = ev.optLong("seq", 0L);
                if (seq <= base) continue;
                if (!seen.add(seq)) continue;
                added.add(ev);
                if (seq > max) max = seq;
            }
        }
        return new Delta(added, max, max);
    }

    /** 内存只保留最近 cap 条（直接裁剪入参列表；null/cap≤0 安全 no-op）。 */
    public static void trimTo(List<JSONObject> events, int cap) {
        if (events == null || cap <= 0) return;
        while (events.size() > cap) events.remove(0);
    }

    // ================= 文本工具 =================

    /** 单行化：换行/制表符替换为空格（避免多行文本把列表行高撑开）。 */
    public static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    /** 截断到 max 字符（超长以 … 收尾；结果长度 ≤ max）。 */
    public static String clip(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 安全 JSON 文本（toString 异常时退化为类名）。 */
    private static String safeJson(JSONObject ev) {
        try {
            return ev.toString();
        } catch (Throwable t) {
            return String.valueOf(ev);
        }
    }
}
