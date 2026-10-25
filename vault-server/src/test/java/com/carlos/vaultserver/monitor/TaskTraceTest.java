package com.carlos.vaultserver.monitor;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机监控 TaskTrace 单测（2026-10-25）：任务上下文归属、事件形状、心跳过滤、
 * 未启用零事件、监听器异常不外溢、notify 白名单、内容截断、会话/打点事件。
 *
 * <p>用 {@link MonitorStore#install(Path, Map)} 注入临时目录 + {@code VAULT_MONITOR=on}
 * 环境替身，避免依赖部署环境；{@code @After} 统一 shutdown + reset，防串测。
 */
public class TaskTraceTest {

    private Path dir;
    private MonitorStore store;

    @Before
    public void setUp() throws IOException {
        TaskTrace.resetForTest();
        dir = Files.createTempDirectory("tasktrace-test");
        store = MonitorStore.install(dir, Map.of("VAULT_MONITOR", "on"));
    }

    @After
    public void tearDown() {
        TaskTrace.resetForTest();
        MonitorStore.shutdown();
        deleteRecursively(dir);
    }

    // ---------- task.start / task.end ----------

    @Test
    public void beginEnd_writesTaskStartAndEndWithOutcome() {
        TaskTrace.Context c = TaskTrace.begin("48460403", 888L, "login_rewards", "登录好礼", "节奏");
        assertNotNull(c);
        TaskOutcome o = new TaskOutcome("login_rewards", "登录好礼",
                TaskOutcome.Category.CLAIMED, "已领取");
        o.rewards = "钻石×30";
        o.durationMs = 61L;
        c.end(o);
        c.end(o);   // 幂等：重复 end 只落一条

        List<JSONObject> events = events();
        assertEquals(2, events.size());

        JSONObject start = events.get(0);
        assertEquals("task.start", start.optString("kind"));
        assertEquals("48460403", start.optString("fpid"));
        assertEquals("888", start.optString("sid"));
        assertEquals("login_rewards", start.optString("fn"));
        assertEquals("登录好礼", start.optString("name"));
        assertEquals("节奏", start.optString("trigger"));

        JSONObject end = events.get(1);
        assertEquals("task.end", end.optString("kind"));
        assertEquals("CLAIMED", end.optString("cat"));
        assertEquals("已领取", end.optString("msg"));
        assertEquals("钻石×30", end.optString("rewards"));
        assertEquals(61L, end.optLong("durMs"));
        assertEquals("login_rewards", end.optString("fn"));
    }

    @Test
    public void close_withoutOutcome_writesEmptyCategory() {
        TaskTrace.Context c = TaskTrace.begin("f1", 7L, "fn_a", "功能A", "整轮");
        c.close();
        c.end(new TaskOutcome("fn_a", "功能A", TaskOutcome.Category.ERROR, "late"));
        List<JSONObject> events = events();
        assertEquals(2, events.size());
        assertEquals("task.end", events.get(1).optString("kind"));
        assertEquals("", events.get(1).optString("cat"));
        assertEquals("", events.get(1).optString("msg"));
    }

    // ---------- IO 事件归属 ----------

    @Test
    public void io_withContext_attributedToInnermostTask() {
        Object args = mapOf("a", 1, "s", "x");
        TaskTrace.Context outer = TaskTrace.begin("f1", 888L, "outer_fn", "外层", "整轮");
        TaskTrace.onIo(null, "req", "req_outer", asMap(args), 0L, null);
        TaskTrace.Context inner = TaskTrace.begin("f1", 888L, "inner_fn", "内层", "手动");
        TaskTrace.onIo(null, "rsp", "req_inner", mapOf("code", 0, "list", List.of(1, 2)), 48L, null);
        inner.end(new TaskOutcome("inner_fn", "内层", TaskOutcome.Category.SKIPPED, "已领"));
        // 内层结束后恢复外层归属
        TaskTrace.onIo(null, "req", "req_after", null, 0L, null);
        outer.end(null);

        List<JSONObject> ioReq = eventsOf("io.req");
        assertEquals(2, ioReq.size());
        assertEquals("req_outer", ioReq.get(0).optString("proto"));
        assertEquals("outer_fn", ioReq.get(0).optString("fn"));
        assertEquals("{\"a\":1,\"s\":\"x\"}", ioReq.get(0).optString("args"));
        assertEquals("req_after", ioReq.get(1).optString("proto"));
        assertEquals("outer_fn", ioReq.get(1).optString("fn"));
        assertEquals("{\"a\":1,\"s\":\"x\"}", ioReq.get(0).optString("args"));
        assertEquals("", ioReq.get(0).optString("data"));

        JSONObject rsp = eventsOf("io.rsp").get(0);
        assertEquals("req_inner", rsp.optString("proto"));
        assertEquals("inner_fn", rsp.optString("fn"));
        assertEquals(0, rsp.optInt("code"));
        assertEquals(48L, rsp.optLong("durMs"));
        assertEquals("{\"code\":0,\"list\":[1,2]}", rsp.optString("data"));
    }

    @Test
    public void io_withoutContext_isSessionChannelWithEmptyFn() {
        TaskTrace.onIo(null, "req", "loginauth", mapOf("account", "81444913"), 0L, null);
        TaskTrace.onIo(null, "rsp", "loginauth", mapOf("uid", 500123456L), 30L, null);

        List<JSONObject> events = events();
        assertEquals(2, events.size());
        for (JSONObject e : events) {
            assertEquals("", e.optString("fn"));
            assertEquals("loginauth", e.optString("proto"));
        }
        // 无 code 字段=0（契约 §2）
        assertEquals(0, events.get(1).optInt("code"));
    }

    @Test
    public void io_codeSemantics_emptyResponseMinusOne_missingCodeZero() {
        TaskTrace.onIo(null, "rsp", "req_a", null, 5L, null);
        TaskTrace.onIo(null, "rsp", "req_b", mapOf("uid", 1), 5L, null);
        TaskTrace.onIo(null, "rsp", "req_c", mapOf("code", 5), 5L, null);

        List<JSONObject> rsp = eventsOf("io.rsp");
        assertEquals(-1, rsp.get(0).optInt("code"));
        assertEquals(0, rsp.get(1).optInt("code"));
        assertEquals(5, rsp.get(2).optInt("code"));
    }

    @Test
    public void io_errDirection_isRecordedAsIoRspWithError() {
        TaskTrace.onIo(null, "err", "req_broken", null, 12L,
                new IllegalStateException("connection reset"));

        JSONObject e = eventsOf("io.rsp").get(0);
        assertEquals("req_broken", e.optString("proto"));
        assertEquals(-1, e.optInt("code"));
        assertEquals(12L, e.optLong("durMs"));
        assertTrue(e.optBoolean("err"));
        assertTrue(e.optString("msg").contains("connection reset"));
    }

    @Test
    public void io_heartbeatIsSkipped() {
        TaskTrace.Context c = TaskTrace.begin("f1", 1L, "fn", "名", "手动");
        TaskTrace.onIo(null, "req", "req_heartbeat", mapOf(), 0L, null);
        TaskTrace.onIo(null, "rsp", "req_heartbeat", mapOf("code", 0), 3L, null);
        TaskTrace.onIo(null, "req", "req_ok", mapOf(), 0L, null);
        c.end(null);

        for (JSONObject e : events()) {
            assertFalse("心跳不应被记录：" + e,
                    "req_heartbeat".equals(e.optString("proto")));
        }
        assertEquals(1, eventsOf("io.req").size());
    }

    @Test
    public void io_contentIsTruncatedByContentLimit() {
        MonitorStore.shutdown();
        store = MonitorStore.install(dir, Map.of(
                "VAULT_MONITOR", "on", "VAULT_MONITOR_CONTENT", "200"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append('x');

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("blob", big.toString());
        TaskTrace.onIo(null, "rsp", "req_big", body, 1L, null);

        JSONObject e = eventsOf("io.rsp").get(0);
        assertTrue(e.optBoolean("trunc"));
        assertTrue(e.optString("data").endsWith(com.carlos.vault.wjdr.JsonText.TRUNCATED_MARK));
    }

    // ---------- 未启用：零事件 ----------

    @Test
    public void disabled_writesNothingAndBeginReturnsNoop() {
        MonitorStore.shutdown();
        store = MonitorStore.install(dir, Map.of("VAULT_MONITOR", "off"));
        TaskTrace.resetForTest();

        TaskTrace.Context c = TaskTrace.begin("f1", 1L, "fn", "名", "手动");
        TaskTrace.onIo(null, "req", "req_x", mapOf("a", 1), 0L, null);
        TaskTrace.onIo(null, "rsp", "req_x", mapOf("code", 0), 5L, null);
        TaskTrace.recordNotify("f1", () -> 1L, "notify_daily_claim_info", mapOf("a", 1));
        TaskTrace.session("f1", "1", "login", "登录成功", null);
        TaskTrace.mark("app", "打点");
        c.end(new TaskOutcome("fn", "名", TaskOutcome.Category.ERROR, "x"));

        assertEquals(0, events().size());
        assertFalse(store.query(0L, 100, null, "", true).optBoolean("enabled"));
        // 未启用时 install 不应注册监听
        TaskTrace.install();
        assertFalse(WjdrEngine.hasIoListeners());
    }

    @Test
    public void install_isIdempotentAndRemovedByReset() {
        assertFalse(WjdrEngine.hasIoListeners());
        TaskTrace.install();
        TaskTrace.install();
        assertTrue(WjdrEngine.hasIoListeners());
        TaskTrace.resetForTest();
        assertFalse(WjdrEngine.hasIoListeners());
    }

    // ---------- 监听器异常绝不影响请求 ----------

    @Test
    public void listenerException_neverPropagates() {
        AtomicInteger received = new AtomicInteger();
        WjdrEngine.IoListener bomb = (engine, direction, proto, body, durMs, error) -> {
            throw new RuntimeException("监听器爆炸");
        };
        WjdrEngine.IoListener recorder = (engine, direction, proto, body, durMs, error) ->
                received.incrementAndGet();
        WjdrEngine.addIoListener(bomb);
        WjdrEngine.addIoListener(recorder);
        try {
            WjdrEngine.notifyIo(null, "req", "req_x", mapOf(), 0L, null);
        } finally {
            WjdrEngine.removeIoListener(bomb);
            WjdrEngine.removeIoListener(recorder);
        }
        assertEquals(1, received.get());
    }

    @Test
    public void onIo_neverThrowsEvenOnBadInput() {
        TaskTrace.onIo(null, null, null, null, 0L, null);
        TaskTrace.onIo(null, "req", "", null, 0L, null);
        TaskTrace.onIo(null, "err", "req_x", null, 0L, new Error("x"));
        // 上面的调用允许静默忽略/记录，但不允许抛异常（能执行到这里即通过）
        assertNotNull(store);
    }

    // ---------- notify 白名单 ----------

    @Test
    public void notify_whitelistFiltersByDefault() {
        TaskTrace.setNotifyModeForTest("whitelist");
        TaskTrace.recordNotify("f1", () -> 888L, "notify_daily_claim_info", mapOf("id", 1));
        TaskTrace.recordNotify("f1", () -> 888L, "notify_achievement_info", mapOf("id", 2));
        TaskTrace.recordNotify("f1", () -> 888L, "notify_random_unknown", mapOf("id", 3));
        TaskTrace.recordNotify("f1", () -> 888L, "notify_battle_pass", mapOf("id", 4));
        TaskTrace.recordNotify("f1", () -> 888L, "notify_some_reward_push", mapOf("id", 5));

        List<JSONObject> notifies = eventsOf("notify");
        assertEquals(3, notifies.size());
        assertEquals("notify_daily_claim_info", notifies.get(0).optString("proto"));
        assertEquals("888", notifies.get(0).optString("sid"));
        assertEquals("notify_achievement_info", notifies.get(1).optString("proto"));
        assertEquals("notify_some_reward_push", notifies.get(2).optString("proto"));
    }

    @Test
    public void notify_modeAllAndOffOverrideWhitelist() {
        TaskTrace.setNotifyModeForTest("all");
        assertTrue(TaskTrace.notifyAllowed("notify_random_unknown"));
        TaskTrace.setNotifyModeForTest("off");
        assertFalse(TaskTrace.notifyAllowed("notify_daily_claim_info"));
        TaskTrace.setNotifyModeForTest("whitelist");
        assertTrue(TaskTrace.notifyAllowed("notify_daily_claim_info"));
        assertFalse(TaskTrace.notifyAllowed("notify_random_unknown"));
    }

    @Test
    public void attachNotify_registersHookWithoutThrowing() {
        WjdrEngine engine = new WjdrEngine(null);
        TaskTrace.attachNotify(engine, "f1", () -> 888L);
        TaskTrace.attachNotify(null, "f1", () -> 888L);   // 空引擎安全忽略
    }

    // ---------- session / mark ----------

    @Test
    public void sessionAndMark_events() {
        TaskTrace.session("f1", "888", "takeover", "检测到顶号，按 human_first 让位",
                new JSONObject().put("policy", "human_first"));
        TaskTrace.mark("app", "用户打点：原生游戏领取前");

        List<JSONObject> events = events();
        assertEquals(2, events.size());
        JSONObject s = events.get(0);
        assertEquals("session", s.optString("kind"));
        assertEquals("f1", s.optString("fpid"));
        assertEquals("888", s.optString("sid"));
        assertEquals("takeover", s.optString("event"));
        assertTrue(s.optString("msg").contains("顶号"));
        assertTrue(s.optString("data").contains("human_first"));

        JSONObject m = events.get(1);
        assertEquals("mark", m.optString("kind"));
        assertEquals("app", m.optString("src"));
        assertTrue(m.optString("msg").contains("领取前"));
    }

    // ---------- 工具 ----------

    private List<JSONObject> events() {
        JSONArray arr = store.query(0L, 1000, null, "", true).getJSONArray("events");
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        return out;
    }

    private List<JSONObject> eventsOf(String kind) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events()) {
            if (kind.equals(e.optString("kind"))) out.add(e);
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static void deleteRecursively(Path root) {
        if (root == null) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
