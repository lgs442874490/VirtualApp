package com.carlos.vault.daily;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机监控行文案 / 过滤 / seq 游标单测（纯 JVM，无 Android 依赖）。
 *
 * <p>契约：{@code design/真机监控-服务端任务级协议监控-20261025.md} §2 事件模型、§4 页面列表要求。
 * 口径锁死：
 * <ul>
 *   <li>任务行 ✅/⏭/⚠️/❌ + 名称 + 原因 + 耗时；task.start 用 ▶；</li>
 *   <li>协议行 → 请求 / ← 响应（proto + code + durMs + data 摘要）；</li>
 *   <li>会话 event+msg、推送 proto、打点 msg；未识别 kind 原样 JSON；</li>
 *   <li>所有字段 opt* 防御（缺字段/类型不符不抛异常），单行 ≤ {@link MonitorFormat#MAX_LINE}；</li>
 *   <li>seq 游标只取 seq&gt;cursor、同批去重、游标不回退。</li>
 * </ul>
 */
public class MonitorFormatTest {

    private static JSONObject ev(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return o;
    }

    private static JSONArray arr(Object... items) {
        JSONArray a = new JSONArray();
        try {
            for (Object o : items) a.put(o);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return a;
    }

    // ================= 行文案 =================

    @Test
    public void format_taskEnd_claimedLine() {
        JSONObject e = ev("seq", 104, "ts", 1792916000061L, "kind", "task.end",
                "fpid", "48460403", "fn", "login_rewards", "name", "登录好礼",
                "cat", "CLAIMED", "msg", "已领取", "rewards", "钻石×30", "durMs", 61);
        String line = MonitorFormat.formatEvent(e);
        assertTrue("含状态 ✅：" + line, line.contains("✅"));
        assertTrue("含任务中文名：" + line, line.contains("登录好礼"));
        assertTrue("含原因：" + line, line.contains("已领取"));
        assertTrue("含奖励：" + line, line.contains("钻石×30"));
        assertTrue("含耗时：" + line, line.contains("61ms"));
        assertTrue("带 seq 前缀：" + line, line.contains("#104"));
        // 时间前缀 HH:mm:ss
        assertTrue("带 HH:mm:ss 时间前缀：" + line, line.matches("^\\d{2}:\\d{2}:\\d{2} #104 .*$"));
    }

    @Test
    public void format_taskEnd_outcomeEmojis() {
        assertTrue(MonitorFormat.formatEvent(ev("kind", "task.end", "name", "邮件",
                "cat", "SKIP", "msg", "已领完")).contains("⏭"));
        assertTrue(MonitorFormat.formatEvent(ev("kind", "task.end", "name", "礼包",
                "cat", "WARN", "msg", "部分领取")).contains("⚠️"));
        assertTrue(MonitorFormat.formatEvent(ev("kind", "task.end", "name", "集结",
                "cat", "FAILED", "msg", "1507 参数错误")).contains("❌"));
        // 未知分类不臆测状态（不出现四种结果 emoji 之一）
        String unknown = MonitorFormat.formatEvent(ev("kind", "task.end", "name", "任务X", "cat", "WHATEVER"));
        assertFalse(unknown, unknown.contains("✅"));
        assertFalse(unknown, unknown.contains("❌"));
    }

    @Test
    public void format_taskStart_hasNameAndTrigger() {
        JSONObject e = ev("seq", 101, "kind", "task.start", "fn", "login_rewards",
                "name", "登录好礼", "trigger", "节奏");
        String line = MonitorFormat.formatEvent(e);
        assertTrue(line, line.contains("▶"));
        assertTrue(line, line.contains("登录好礼"));
        assertTrue(line, line.contains("节奏"));
        // 名称缺失时退回 fn，仍不空
        assertTrue(MonitorFormat.formatEvent(ev("kind", "task.start", "fn", "mail"))
                .contains("mail"));
    }

    @Test
    public void format_ioReq_arrowAndArgs() {
        JSONObject e = ev("seq", 102, "kind", "io.req", "proto", "req_login_reward",
                "args", ev("type", 1));
        String line = MonitorFormat.formatEvent(e);
        assertTrue(line, line.startsWith("→ ") || line.contains("→ req_login_reward"));
        assertTrue(line, line.contains("req_login_reward"));
        assertTrue("带 args 摘要：" + line, line.contains("\"type\""));
    }

    @Test
    public void format_ioRsp_arrowCodeDurData() {
        JSONObject e = ev("seq", 103, "kind", "io.rsp", "proto", "req_login_reward",
                "code", 0, "durMs", 48, "data", ev("rewards", "x"));
        String line = MonitorFormat.formatEvent(e);
        assertTrue(line, line.contains("← req_login_reward"));
        assertTrue(line, line.contains("code=0"));
        assertTrue(line, line.contains("48ms"));
        assertTrue("带 data 摘要：" + line, line.contains("rewards"));
        // 空响应 code=-1 也要原样展示
        assertTrue(MonitorFormat.formatEvent(ev("kind", "io.rsp", "proto", "req_x", "code", -1))
                .contains("code=-1"));
    }

    @Test
    public void format_session_eventAndMsg() {
        String line = MonitorFormat.formatEvent(ev("seq", 105, "kind", "session",
                "event", "takeover", "msg", "检测到顶号/人工登录，按 human_first 让位"));
        assertTrue(line, line.contains("会话"));
        assertTrue(line, line.contains("takeover"));
        assertTrue(line, line.contains("顶号"));
    }

    @Test
    public void format_notify_proto() {
        String line = MonitorFormat.formatEvent(ev("seq", 106, "kind", "notify",
                "proto", "notify_daily_claim_info", "fpid", "48460403"));
        assertTrue(line, line.contains("推送"));
        assertTrue(line, line.contains("notify_daily_claim_info"));
    }

    @Test
    public void format_mark_msg() {
        String line = MonitorFormat.formatEvent(ev("seq", 107, "kind", "mark",
                "msg", "用户打点：原生游戏领取前"));
        assertTrue(line, line.contains("打点"));
        assertTrue(line, line.contains("用户打点：原生游戏领取前"));
    }

    @Test
    public void format_unknownKind_keptRaw() {
        JSONObject raw = ev("seq", 200, "kind", "custom.unknown", "foo", "bar");
        String line = MonitorFormat.formatEvent(raw);
        assertTrue("未识别 kind 原样输出：" + line, line.contains("custom.unknown"));
        assertTrue(line, line.contains("foo"));
    }

    @Test
    public void format_missingFields_neverThrows() {
        // 空对象 / null / 各种缺字段
        assertNotNull(MonitorFormat.formatEvent(new JSONObject()));
        assertEquals("", MonitorFormat.formatEvent(null));
        assertNotNull(MonitorFormat.formatEvent(ev("kind", "task.end")));
        assertNotNull(MonitorFormat.formatEvent(ev("kind", "io.rsp")));
        assertNotNull(MonitorFormat.formatEvent(ev("kind", "session")));
        assertNotNull(MonitorFormat.formatEvent(ev("kind", "notify")));
        assertNotNull(MonitorFormat.formatEvent(ev("kind", "mark")));
        // 类型不符（字段是对象/数组而不是字符串）也不能抛
        assertNotNull(MonitorFormat.formatEvent(ev("kind", "task.end",
                "name", ev("x", 1), "msg", new JSONArray())));
        // 时间/seq 缺省时不出现占位前缀
        String bare = MonitorFormat.formatEvent(ev("kind", "mark", "msg", "hi"));
        assertFalse(bare, bare.contains("#"));
        assertFalse(bare, bare.contains("--:--:--"));
    }

    @Test
    public void format_longData_clippedToMaxLine() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append('x');
        String line = MonitorFormat.formatEvent(ev("kind", "io.req", "proto", "req_x", "args", big.toString()));
        assertTrue("单行不得超过 MAX_LINE：" + line.length(), line.length() <= MonitorFormat.MAX_LINE);
        assertTrue(line, line.endsWith("…"));
        // 多行 JSON 被折叠成单行
        assertFalse(MonitorFormat.formatEvent(ev("kind", "mark", "msg", "a\nb\tc"))
                .contains("\n"));
    }

    // ================= 过滤 =================

    @Test
    public void categoryOf_mapping() {
        assertEquals(MonitorFormat.CAT_TASK, MonitorFormat.categoryOf("task.start"));
        assertEquals(MonitorFormat.CAT_TASK, MonitorFormat.categoryOf("task.end"));
        assertEquals(MonitorFormat.CAT_PROTO, MonitorFormat.categoryOf("io.req"));
        assertEquals(MonitorFormat.CAT_PROTO, MonitorFormat.categoryOf("io.rsp"));
        assertEquals(MonitorFormat.CAT_SESSION, MonitorFormat.categoryOf("session"));
        assertEquals(MonitorFormat.CAT_NOTIFY, MonitorFormat.categoryOf("notify"));
        assertEquals(MonitorFormat.CAT_MARK, MonitorFormat.categoryOf("mark"));
        assertEquals(MonitorFormat.CAT_UNKNOWN, MonitorFormat.categoryOf("error"));
        assertEquals(MonitorFormat.CAT_UNKNOWN, MonitorFormat.categoryOf(""));
        assertEquals(MonitorFormat.CAT_UNKNOWN, MonitorFormat.categoryOf(null));
    }

    @Test
    public void filter_byCategory() {
        List<JSONObject> all = Arrays.asList(
                ev("seq", 1, "kind", "task.start"),
                ev("seq", 2, "kind", "io.req"),
                ev("seq", 3, "kind", "session"),
                ev("seq", 4, "kind", "notify"),
                ev("seq", 5, "kind", "mark"),
                ev("seq", 6, "kind", "task.end"));
        assertEquals(6, MonitorFormat.filter(all, MonitorFormat.CAT_ALL).size());
        assertEquals(2, MonitorFormat.filter(all, MonitorFormat.CAT_TASK).size());
        assertEquals(1, MonitorFormat.filter(all, MonitorFormat.CAT_PROTO).size());
        assertEquals(1, MonitorFormat.filter(all, MonitorFormat.CAT_SESSION).size());
        assertEquals(1, MonitorFormat.filter(all, MonitorFormat.CAT_NOTIFY).size());
        assertEquals(1, MonitorFormat.filter(all, MonitorFormat.CAT_MARK).size());
        assertEquals(0, MonitorFormat.filter(all, MonitorFormat.CAT_UNKNOWN).size());
        assertEquals(0, MonitorFormat.filter(null, MonitorFormat.CAT_ALL).size());
        // 分类文案与页面 chips 一致
        assertEquals(6, MonitorFormat.CATEGORY_LABELS.length);
        assertEquals("全部", MonitorFormat.CATEGORY_LABELS[MonitorFormat.CAT_ALL]);
        assertEquals("任务", MonitorFormat.CATEGORY_LABELS[MonitorFormat.CAT_TASK]);
        assertEquals("协议", MonitorFormat.CATEGORY_LABELS[MonitorFormat.CAT_PROTO]);
        assertEquals("会话", MonitorFormat.CATEGORY_LABELS[MonitorFormat.CAT_SESSION]);
        assertEquals("推送", MonitorFormat.CATEGORY_LABELS[MonitorFormat.CAT_NOTIFY]);
        assertEquals("打点", MonitorFormat.CATEGORY_LABELS[MonitorFormat.CAT_MARK]);
    }

    // ================= seq 游标 / 去重 =================

    @Test
    public void advance_returnsNewEventsAndPushesCursor() {
        List<JSONObject> incoming = Arrays.asList(
                ev("seq", 101, "kind", "task.start"),
                ev("seq", 103, "kind", "task.end"),
                ev("seq", 100, "kind", "old"),        // = cursor，不应返回
                ev("seq", 103, "kind", "task.end"),   // 同批重复 seq，去重
                ev("seq", 102, "kind", "io.req"));
        MonitorFormat.Delta d = MonitorFormat.advance(100L, incoming);
        assertEquals(3, d.events.size());
        // seq 升序
        assertEquals(101L, d.events.get(0).optLong("seq", 0));
        assertEquals(102L, d.events.get(1).optLong("seq", 0));
        assertEquals(103L, d.events.get(2).optLong("seq", 0));
        assertEquals(103L, d.cursor);
        assertEquals(103L, d.maxSeq);
    }

    @Test
    public void advance_sameBatchAgain_isEmptyAndCursorStable() {
        List<JSONObject> batch = Arrays.asList(
                ev("seq", 11, "kind", "mark"), ev("seq", 12, "kind", "mark"));
        MonitorFormat.Delta first = MonitorFormat.advance(0L, batch);
        assertEquals(12L, first.cursor);
        MonitorFormat.Delta again = MonitorFormat.advance(first.cursor, batch);
        assertTrue(again.isEmpty());
        assertEquals(12L, again.cursor);
        assertTrue(MonitorFormat.advance(5L, null).isEmpty());
        assertTrue(MonitorFormat.advance(5L, new ArrayList<JSONObject>()).isEmpty());
        assertEquals(5L, MonitorFormat.advance(5L, null).cursor);
    }

    @Test
    public void advance_cursorNeverRegresses() {
        // 服务端返回更旧的 seq（异常路径）→ 不返回、不回退游标
        MonitorFormat.Delta d = MonitorFormat.advance(500L,
                Arrays.asList(ev("seq", 1, "kind", "mark"), ev("seq", 499, "kind", "mark")));
        assertTrue(d.isEmpty());
        assertEquals(500L, d.cursor);
        // 负数/0 游标安全
        assertEquals(0L, MonitorFormat.advance(-10L, null).cursor);
        // 缺 seq 的事件按 0，不会被当成新增
        assertTrue(MonitorFormat.advance(0L, Arrays.asList(ev("kind", "mark"))).isEmpty());
    }

    @Test
    public void trimTo_keepsLatest() {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) list.add(ev("seq", i, "kind", "mark"));
        MonitorFormat.trimTo(list, 3);
        assertEquals(3, list.size());
        assertEquals(7L, list.get(0).optLong("seq", -1));
        assertEquals(9L, list.get(2).optLong("seq", -1));
        // null / 非法 cap 安全
        MonitorFormat.trimTo(null, 3);
        MonitorFormat.trimTo(list, 0);
        assertEquals(3, list.size());
    }

    // ================= 时间 / 文本工具 =================

    @Test
    public void formatTime_hhmmss() {
        assertEquals("--:--:--", MonitorFormat.formatTime(0L));
        assertEquals("--:--:--", MonitorFormat.formatTime(-1L));
        long ts = 1792916000061L;
        String expect = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts));
        assertEquals(expect, MonitorFormat.formatTime(ts));
        assertTrue(MonitorFormat.formatTime(ts).matches("\\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void summarize_defensive() {
        assertEquals("", MonitorFormat.summarize(null));
        assertEquals("", MonitorFormat.summarize(new JSONObject()));
        assertEquals("", MonitorFormat.summarize(new JSONArray()));
        assertEquals("{\"k\":1}", MonitorFormat.summarize(ev("k", 1)));
        assertEquals("纯文本", MonitorFormat.summarize("纯文本"));
        assertTrue(MonitorFormat.summarize(arr(1, 2)).contains("[1,2]"));
        // 超长摘要截断
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) big.append('y');
        assertTrue(MonitorFormat.summarize(big.toString()).length() <= 160);
    }
}
