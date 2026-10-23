package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.CaptureTasks;
import com.carlos.vaultserver.StateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * 批次4：活动日历主动同步（CaptureTasks → {@link ServerNotifyHistory}）单测（2026-10-23）。
 *
 * <p>背景：批次3 的 C5 门控（{@code ServerTasks.activityCalendarBlock}）只消费
 * {@code notify_activity_calendar_data} 推送；会话长在线时推送可能 >24h 不更新 → 门控自动停用。
 * {@code CaptureTasks.captureCalendar} 每 10 分钟已有 {@code req_activity_calendar_get_data} 响应，
 * 本批次把该响应以同一协议名喂给历史（不新增网络请求、复用批次3 的 allowlist/8 条/2000 条/4MB 深拷贝红线）。
 *
 * <p>对照：网页版 service/DailyActivityCalendarState.java:17-18（REQUEST/NOTIFY 双协议同状态）、
 * :37-140（data List/Map + schedule[{begin_ts,end_ts}] 解析口径）。
 *
 * <p>批次8（2026-10-23）日志降噪回归：生产结构数组是 {@code Map<Double,...>}（id 为 Double 键），
 * 深拷贝会把 Map 键规整为 String → 旧实现“整帧 Map.equals”恒不等，于是每次同步都打 info
 * （生产批次5 jar 每 ~6s 一条，约 1.4 万行/天）。现改为“活动 id+窗口语义指纹变化 或 距上次日志 ≥6h”
 * 才打印；同内容重复同步只刷新 recordedAt（C5 新鲜度不变）。本测试覆盖 12 次同内容 1 条日志、
 * 内容变化立即打印、6h 心跳边界、Double/String 键等价。
 */
public class CalendarSyncTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        ServerNotifyHistory.clearAll();
    }

    // ===================================================================
    // helpers
    // ===================================================================

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static GameMessage msg(Map<String, Object> content) {
        GameMessage g = new GameMessage();
        g.getContent().putAll(content);
        return g;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    private TaskContext context(WjdrEngine engine) {
        return new TaskContext(engine, FPID, SID,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
    }

    private static List<Map<String, Object>> oneSchedule(long begin, long end) {
        return Collections.singletonList(map("begin_ts", begin, "end_ts", end));
    }

    /** data=Map 形状（网页版 data 为 Map&lt;活动id, {id,schedule}&gt;）。 */
    private static Map<String, Object> calendarMapPayload(long id, long begin, long end) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(String.valueOf(id), map("id", id, "schedule", oneSchedule(begin, end)));
        return map("code", 0, "data", data);
    }

    /** data=List 形状（网页版 data 为 List&lt;{id,schedule}&gt;）。 */
    private static Map<String, Object> calendarListPayload(long id, long begin, long end) {
        return map("code", 0, "data",
                Collections.singletonList(map("id", id, "schedule", oneSchedule(begin, end))));
    }

    /** 最小引擎替身：记录请求协议、按协议返回预置响应；collectNotifies 不睡。 */
    static final class CalendarEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        final List<String> requestedProtos = new ArrayList<>();

        CalendarEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
            // 单测不等待真实网络推送
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requestedProtos.add(proto);
            return responses.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }
    }

    // ===================================================================
    // 1) 同步 → 历史可读 → C5 门控消费（data=Map 形状）
    // ===================================================================

    @Test
    public void syncCalendar_mapShape_isConsumedByC5Gate() {
        long now = nowSec();
        int n = ServerNotifyHistory.syncActivityCalendar(FPID,
                calendarMapPayload(2508001L, now - 600L, now + 600L));
        assertEquals(1, n);
        assertEquals(1, ServerNotifyHistory.history(FPID, ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO)
                .size());
        assertTrue(ServerNotifyHistory.latestRecordedAt(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO) > 0L);

        // 格式与 C5 门控消费一致：parseActivityCalendar 直接读历史最新帧
        Map<String, Object> latest = ServerNotifyHistory.latestContent(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO);
        assertNotNull(latest);
        Map<Long, List<long[]>> parsed = ServerTasks.parseActivityCalendar(latest);
        assertEquals(1, parsed.size());
        assertEquals(1, parsed.get(2508001L).size());
        assertEquals(now - 600L, parsed.get(2508001L).get(0)[0]);
        assertEquals(now + 600L, parsed.get(2508001L).get(0)[1]);

        CalendarEngine engine = new CalendarEngine();
        TaskContext tc = context(engine);
        // 窗口正在开放 → 门控放行
        assertNull(ServerTasks.activityCalendarBlock(tc, 2508001L, now));
        // 同一活动同步为“刚结束”（30 分钟前）→ 门控拦截（证明采集同步能让门控每天有新鲜数据）
        assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID,
                calendarMapPayload(2508001L, now - 3600L, now - 1800L)));
        String block = ServerTasks.activityCalendarBlock(tc, 2508001L, now);
        assertNotNull(block);
        assertTrue(block.contains("日历"));
    }

    // ===================================================================
    // 2) data=List 形状 + 响应已解包（无 data）形状
    // ===================================================================

    @Test
    public void syncCalendar_listShape_andUnwrappedPayload() {
        long now = nowSec();
        // List 形状
        assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID,
                calendarListPayload(2508008L, now - 600L, now + 600L)));
        Map<Long, List<long[]>> parsed = ServerTasks.parseActivityCalendar(
                ServerNotifyHistory.latestContent(FPID, ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO));
        assertTrue(parsed.containsKey(2508008L));

        // 响应被解包成一层 {活动id: {id,schedule}}（无 data 包装）：sync 归一后再入历史
        Map<String, Object> unwrapped = map("2508002",
                map("id", 2508002L, "schedule", oneSchedule(now - 600L, now + 600L)));
        assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, unwrapped));
        Map<String, Object> latest = ServerNotifyHistory.latestContent(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO);
        assertTrue("未解包形状应被包装成 data", latest.containsKey("data"));
        assertTrue(ServerTasks.parseActivityCalendar(latest).containsKey(2508002L));
    }

    @Test
    public void syncCalendar_invalidPayload_noSideEffect() {
        assertEquals(0, ServerNotifyHistory.syncActivityCalendar(FPID, null));
        assertEquals(0, ServerNotifyHistory.syncActivityCalendar(FPID, new LinkedHashMap<>()));
        assertEquals(0, ServerNotifyHistory.syncActivityCalendar("", // 空账号
                calendarMapPayload(2508001L, 1L, 2L)));
        // data 非法/无活动条目 → 不写历史、不抛异常
        assertEquals(0, ServerNotifyHistory.syncActivityCalendar(FPID, map("data", "oops")));
        assertEquals(0, ServerNotifyHistory.syncActivityCalendar(FPID,
                map("data", Collections.singletonList(map("schedule", oneSchedule(1L, 2L))))));
        assertEquals(0, ServerNotifyHistory.entryCount(FPID));
    }

    // ===================================================================
    // 3) 复用批次3 容量红线：每 proto 8 条环形 / 总量 2000 / 4MB 字符预算
    // ===================================================================

    @Test
    public void syncCalendar_reusesHistoryRingAndTotalCaps() {
        long now = nowSec();
        for (int i = 0; i < 12; i++) {
            long id = 2508001L + i;
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID,
                    calendarMapPayload(id, now - 600L, now + 600L)));
        }
        List<ServerNotifyHistory.Entry> h = ServerNotifyHistory.history(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO);
        assertEquals("每 proto 最多 8 条（超出淘汰最旧）", ServerNotifyHistory.MAX_PER_PROTO, h.size());
        // 最新一帧是第 12 次同步（id=2508012）
        Map<Long, List<long[]>> parsed = ServerTasks.parseActivityCalendar(
                h.get(h.size() - 1).content);
        assertTrue(parsed.containsKey(2508012L));
        assertTrue(ServerNotifyHistory.totalEntries() <= ServerNotifyHistory.MAX_TOTAL_ENTRIES);
        assertTrue(ServerNotifyHistory.totalChars() <= ServerNotifyHistory.MAX_TOTAL_CHARS);
    }

    // ===================================================================
    // 4) CaptureTasks 周期采集接线（端到端，不新增网络请求）
    // ===================================================================

    @Test
    public void captureAll_feedsCalendarResponseIntoHistory() {
        long now = nowSec();
        CalendarEngine engine = new CalendarEngine();
        engine.responses.put("req_activity_calendar_get_data",
                msg(calendarMapPayload(2508001L, now - 600L, now + 600L)));
        StateStore store = new StateStore(tmp.getRoot().toPath());

        String err = CaptureTasks.captureAll(engine, store, FPID, SID);
        assertNull(err);

        // 响应已喂入历史，C5 门控可消费（长在线无 notify 推送时仍有新鲜日历）
        List<ServerNotifyHistory.Entry> h = ServerNotifyHistory.history(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO);
        assertEquals(1, h.size());
        Map<Long, List<long[]>> parsed = ServerTasks.parseActivityCalendar(h.get(0).content);
        assertTrue(parsed.containsKey(2508001L));
        assertNull(ServerTasks.activityCalendarBlock(context(engine), 2508001L, now));
        // 原有 todo 采集不受影响（App 今日页契约不变）
        String todo = store.getString("todo:" + FPID + ":" + SID);
        assertNotNull(todo);
        assertTrue(todo.contains("2508001"));
        // 不新增网络请求：只复用采集里已有的日历请求（+巨熊 trap 查询为 CaptureTasks 原有逻辑）
        assertEquals(1, Collections.frequency(engine.requestedProtos,
                "req_activity_calendar_get_data"));
        assertFalse(engine.requestedProtos.isEmpty());
    }

    @Test
    public void captureAll_sameCalendarTwice_dedupesHistoryFrame() {
        long now = nowSec();
        CalendarEngine engine = new CalendarEngine();
        engine.responses.put("req_activity_calendar_get_data",
                msg(calendarMapPayload(2508001L, now - 600L, now + 600L)));
        StateStore store = new StateStore(tmp.getRoot().toPath());

        assertNull(CaptureTasks.captureAll(engine, store, FPID, SID));
        assertNull(CaptureTasks.captureAll(engine, store, FPID, SID));

        // 同帧重复采集不占环形名额（record 内部同内容去重）
        assertEquals(1, ServerNotifyHistory.history(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO).size());
        assertEquals(2, Collections.frequency(engine.requestedProtos,
                "req_activity_calendar_get_data"));
    }

    // ===================================================================
    // 5) 批次8：日志降噪（语义指纹 + 6h 心跳）
    // ===================================================================

    /** 生产真实形状：data 为 Map，键是 Double（sproto 结构数组按 id 键），条目 id/schedule 也是 Double。 */
    private static Map<String, Object> calendarDoubleKeyPayload(long id, long begin, long end) {
        Map<Object, Object> data = new LinkedHashMap<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", (double) id);
        item.put("schedule", Collections.singletonList(map("begin_ts", (double) begin,
                "end_ts", (double) end)));
        data.put((double) id, item);   // Double 键：旧实现深拷贝后被 String 化，导致整帧 equals 恒不等
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", 0.0d);
        content.put("data", data);
        return content;
    }

    /** 捕获同步期间 stdout（FileLog 未 setup 时纯 stdout 输出），返回日志文本。 */
    private static String captureStdout(Runnable action) {
        PrintStream old = System.out;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(old);
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int countSyncLogs(String stdout) {
        int n = 0;
        for (String line : stdout.split("\n")) {
            if (line.contains("活动日历同步")) n++;
        }
        return n;
    }

    @Test
    public void syncCalendar_repeatedSameContent_logsOnlyOnceAndKeepsFreshness() {
        long now = nowSec();
        Map<String, Object> payload = calendarDoubleKeyPayload(2508001L, now - 600L, now + 600L);
        String stdout = captureStdout(() -> {
            for (int i = 0; i < 12; i++) {
                assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, payload));
            }
        });
        assertEquals("同内容连续同步 12 次只打 1 条", 1, countSyncLogs(stdout));
        // 历史仍写入且只剩 1 帧（同帧去重语义不变），recordedAt 持续刷新 → C5 新鲜度不受影响
        assertEquals(1, ServerNotifyHistory.history(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO).size());
        assertTrue(ServerNotifyHistory.latestRecordedAt(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO) > 0L);
        Map<Long, List<long[]>> parsed = ServerTasks.parseActivityCalendar(
                ServerNotifyHistory.latestContent(FPID, ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO));
        assertTrue(parsed.containsKey(2508001L));
        assertNull(ServerTasks.activityCalendarBlock(context(new CalendarEngine()),
                2508001L, now));

        // 等价内容换成 String 键（语义相同）→ 指纹一致，不再打印
        String stdout2 = captureStdout(() -> assertEquals(1, ServerNotifyHistory.syncActivityCalendar(
                FPID, calendarMapPayload(2508001L, now - 600L, now + 600L))));
        assertEquals("Double/String 键语义相同不重复打印", 0, countSyncLogs(stdout2));
    }

    @Test
    public void syncCalendar_contentChange_logsImmediately() {
        long now = nowSec();
        String stdout = captureStdout(() -> {
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID,
                    calendarDoubleKeyPayload(2508001L, now - 600L, now + 600L)));
            // 同一活动窗口变化 → 语义指纹变化 → 立即再打一条
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID,
                    calendarDoubleKeyPayload(2508001L, now - 600L, now + 7200L)));
            // 与上一帧完全相同 → 不再打印
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID,
                    calendarDoubleKeyPayload(2508001L, now - 600L, now + 7200L)));
        });
        assertEquals(2, countSyncLogs(stdout));
        // 历史保留 2 帧（内容确实变化）
        assertEquals(2, ServerNotifyHistory.history(FPID,
                ServerNotifyHistory.ACTIVITY_CALENDAR_PROTO).size());
    }

    @Test
    public void syncCalendar_sixHourHeartbeat() {
        long t0 = System.currentTimeMillis();
        Map<String, Object> payload = calendarDoubleKeyPayload(2508001L,
                t0 / 1000L - 600L, t0 / 1000L + 600L);
        String stdout = captureStdout(() -> {
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, payload, t0));
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, payload,
                    t0 + 5L * 3600_000L));                     // 5h：静默
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, payload,
                    t0 + 6L * 3600_000L - 60_000L));           // 5h59m：仍未到心跳
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, payload,
                    t0 + 6L * 3600_000L));                     // 6h 边界：心跳
            assertEquals(1, ServerNotifyHistory.syncActivityCalendar(FPID, payload,
                    t0 + 7L * 3600_000L));                     // 心跳后 1h：静默
        });
        assertEquals(2, countSyncLogs(stdout));
        assertTrue("心跳日志需有标识", stdout.contains("6h 心跳"));
    }

    @Test
    public void calendarFingerprint_semanticOnly() {
        long now = nowSec();
        // 同语义：Double 键 vs String 键 → 指纹一致
        assertEquals(ServerNotifyHistory.calendarFingerprint(
                        calendarDoubleKeyPayload(2508001L, now - 600L, now + 600L)),
                ServerNotifyHistory.calendarFingerprint(
                        calendarMapPayload(2508001L, now - 600L, now + 600L)));
        // 附加易变字段（如刷新时间）不影响指纹
        Map<String, Object> withVolatile = calendarDoubleKeyPayload(2508001L,
                now - 600L, now + 600L);
        @SuppressWarnings("unchecked")
        Map<Object, Object> data = (Map<Object, Object>) withVolatile.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) data.get((double) 2508001L);
        item.put("refresh_ts", (double) now);
        assertEquals(ServerNotifyHistory.calendarFingerprint(
                        calendarDoubleKeyPayload(2508001L, now - 600L, now + 600L)),
                ServerNotifyHistory.calendarFingerprint(withVolatile));
        // 活动集合/窗口变化 → 指纹变化
        assertFalse(ServerNotifyHistory.calendarFingerprint(
                        calendarDoubleKeyPayload(2508001L, now - 600L, now + 600L))
                .equals(ServerNotifyHistory.calendarFingerprint(
                        calendarDoubleKeyPayload(2508001L, now - 600L, now + 7200L))));
        assertFalse(ServerNotifyHistory.calendarFingerprint(
                        calendarDoubleKeyPayload(2508001L, now - 600L, now + 600L))
                .equals(ServerNotifyHistory.calendarFingerprint(
                        calendarDoubleKeyPayload(2508002L, now - 600L, now + 600L))));
    }
}
