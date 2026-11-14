package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.carlos.vault.wjdr.GameServerClock;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R0 观测埋点单测（需求 R0-1/R0-4/R0-5、开发计划 S3/S5）：
 * <ul>
 *   <li>紧急路径单条 {@code 盾时延 trace}：四段时刻、时钟字段、outcome；常规路径不输出；</li>
 *   <li>挂起/开盾成功日志追加 {@code endtime/serverNow/err/sinceCalib/src} 且不改文案前缀；</li>
 *   <li>到达观测：同一 marchId 只记首次、槽位无法映射标注 unmapped；</li>
 *   <li>{@code notify_world_march_del} 不进入 {@code SITUATION_PROTOS}（R0-6 零行为变更）。</li>
 * </ul>
 *
 * <p>FileLog 未 setup 时只写 stdout，用 System.out 捕获（与 BearObservabilityTest 同法）。
 */
public class ServerShieldObservabilityTest {

    private static final long OWN_UID = 1001L;
    private static final long SID = 913L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

    /** 最小引擎替身：不连网；lastRequestLockWaitMs 可注入，用于 LOCK 阶段。 */
    private static final class FakeEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<BiConsumer<String, Map<String, Object>>> hooks = new ArrayList<>();
        long lockWaitMs = 0L;
        /** 非空时在第一次 request 内回调一次，用于模拟"请求进行中又来紧急推送"。 */
        Runnable duringRequest;
        /** 非空时作为引擎时钟（用于已校准 trace 字段用例）。 */
        GameServerClock clock;

        FakeEngine() {
            super(null);
        }

        @Override
        public void addMessageHook(BiConsumer<String, Map<String, Object>> hook) {
            if (hook != null) hooks.add(hook);
        }

        void push(String proto, Map<String, Object> content) {
            for (BiConsumer<String, Map<String, Object>> h : hooks) h.accept(proto, content);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            return notifies.get(proto);
        }

        @Override
        public String sessionState() {
            return WjdrEngine.STATE_ONLINE;
        }

        @Override
        public long lastUid() {
            return OWN_UID;
        }

        @Override
        public long lastServerId() {
            return SID;
        }

        @Override
        public long lastRequestLockWaitMs() {
            return lockWaitMs;
        }

        @Override
        public GameServerClock serverClock() {
            return clock;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            Runnable cb = duringRequest;
            if (cb != null) {
                duringRequest = null;   // 只触发一次，避免递归
                cb.run();
            }
            return responses.get(proto);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
    }

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
    }

    // ---------------- 夹具 ----------------

    private void account(String battleShieldParams) {
        VaultStore.Account a = new VaultStore.Account("111");
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        a.roleParams.put(String.valueOf(SID),
                new LinkedHashMap<>(Map.of("battle_shield", battleShieldParams)));
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    private static Map<String, Object> alliancePush(long marchId, long type) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", OWN_UID);
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("uid", OWN_UID);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", 1);
        target.put("map_city", city);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", 49422287L);
        march.put("type", type);
        march.put("target_type", 1);
        march.put("atk_city", atk);
        march.put("target", target);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private static Map<String, Object> worldPush(long marchId, long type) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", 49422287L);
        march.put("type", type);
        march.put("target_type", 1);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("marches", Map.of("9.0", slot));
        return c;
    }

    private static Map<String, Object> withEndtime(Map<String, Object> content, long endSec) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list =
                (List<Map<String, Object>>) content.get("to_marchs");
        list.get(0).put("endtime", endSec);
        return content;
    }

    private static void presetBackpack(FakeEngine e, long itemId, long count) {
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("data", List.of(new LinkedHashMap<>(Map.of("id", itemId, "count", count))));
        e.notifies.put("notify_backpack_init", bp);
    }

    private static GameMessage msg(long code) {
        GameMessage m = new GameMessage();
        m.setContent(new LinkedHashMap<>(Map.of("code", code)));
        return m;
    }

    private ServerShieldResponder responder(AtomicLong nowMs, AtomicLong sec) {
        return new ServerShieldResponder("111", SID, 0L, state, vault, null,
                nowMs::get, sec::get);
    }

    // ---------------- 捕获与解析 ----------------

    private static String capture(Runnable action) {
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

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String lineContaining(String out, String needle) {
        for (String line : out.split("\\R")) {
            if (line.contains(needle)) return line;
        }
        fail("未找到包含「" + needle + "」的日志：" + out);
        return "";
    }

    private static long field(String line, String key) {
        Matcher m = Pattern.compile("\\b" + key + "=(-?\\d+)").matcher(line);
        assertTrue("日志缺少数字字段 " + key + "：" + line, m.find());
        return Long.parseLong(m.group(1));
    }

    // ---------------- R0-1 紧急 trace ----------------

    @Test
    public void urgentPath_emitsSingleTraceWithFourStagesAndOpenedOutcome() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        assertFalse("首推 300 秒应挂起", r.tick(e));

        String out = capture(() -> {
            nowMs.addAndGet(5_000L);
            sec.addAndGet(5L);
            // 加速到剩余 1 秒 → 命中紧急阈值
            r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
            r.tick(e);
        });

        assertEquals("一次紧急开盾只应输出 1 条 trace：" + out, 1, count(out, "盾时延 trace"));
        String line = lineContaining(out, "盾时延 trace");
        assertTrue("应带 fpid：" + line, line.contains("fpid=111"));
        assertTrue("应带 sid：" + line, line.contains("sid=913"));
        assertTrue("应带 uid：" + line, line.contains("uid=0"));
        assertTrue("应带 urgent：" + line, line.contains("urgent=true"));
        assertTrue("未校准 src 应为 LOCAL：" + line, line.contains("src=LOCAL"));
        assertTrue("未校准 err 应为 -1：" + line, line.contains("err=-1ms"));
        assertTrue("未校准 sinceCalib 应为 -1：" + line, line.contains("sinceCalib=-1ms"));
        assertTrue("应带 endtime：" + line, line.contains("endtime="));
        assertTrue("应带 serverNow：" + line, line.contains("serverNow="));
        assertTrue("应带 wall：" + line, line.contains("wall="));
        assertTrue("结果应为 opened：" + line, line.contains("outcome=opened"));

        long push = field(line, "push");
        long wake = field(line, "wake");
        long lock = field(line, "lock");
        long resp = field(line, "resp");
        assertTrue("PUSH 时刻应为本机墙钟毫秒：" + line, push >= 1_700_000_000_000L);
        assertEquals("同一推送线程内 PUSH/WAKE 时刻一致：" + line, push, wake);
        assertTrue("LOCK 不应早于 PUSH：" + line, lock >= push);
        assertTrue("RESP 不应早于 LOCK：" + line, resp >= lock);
        assertTrue("紧急路径 INFO 不应超过 2 条：" + out, count(out, " I/VaultSrv:") <= 2);
    }

    @Test
    public void regularPath_doesNotEmitTrace() {
        account("{\"sub\":{\"reactive\":true},\"strategy\":{\"shield_mode\":\"immediate\"}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(alliancePush(13080L, 2), OWN_UID);
        String out = capture(() -> r.tick(e));

        assertEquals("常规路径不应输出 trace：" + out, 0, count(out, "盾时延 trace"));
        assertEquals("常规开盾路径 INFO ≤1 条：" + out, 1, count(out, " I/VaultSrv:"));
    }

    @Test
    public void urgentPath_failedRequest_outcomeFailed() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(51));   // 背包使用失败
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        r.tick(e);

        String out = capture(() -> {
            nowMs.addAndGet(5_000L);
            sec.addAndGet(5L);
            r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
            r.tick(e);
        });
        String line = lineContaining(out, "盾时延 trace");
        assertTrue("请求失败应记 outcome=failed：" + line, line.contains("outcome=failed"));
        assertTrue("失败也应有 RESP 时刻：" + line, field(line, "resp") > 0L);
        assertTrue("失败也应有 LOCK 时刻：" + line, field(line, "lock") > 0L);
    }

    @Test
    public void urgentPath_skippedByGate_outcomeSkipWithoutStages() {
        account("{\"sub\":{\"reactive\":false}}");   // reactive 关闭 → 跳过
        FakeEngine e = new FakeEngine();
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        // 先记一条非紧急风险，再由"加速压缩 ETA"触发紧急判定（单次推送不带 accelerated 标记）
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        String out = capture(() -> {
            nowMs.addAndGet(5_000L);
            sec.addAndGet(5L);
            // 关闭 reactive 后仍会有紧急推送（风险已记录，决策被门控拦下）
            r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
            r.tick(e);
        });
        String line = lineContaining(out, "盾时延 trace");
        assertTrue("门控跳过应记 outcome=skip：" + line, line.contains("outcome=skip"));
        assertEquals("未发请求时 LOCK 记 -1：" + line, -1L, field(line, "lock"));
        assertEquals("未发请求时 RESP 记 -1：" + line, -1L, field(line, "resp"));
        assertTrue("跳过不应发请求", e.requested.isEmpty());
    }

    @Test
    public void urgentDiscoveredInDecision_stillEmitsTraceWithWakeMinusOne() {
        // 加速后推送时剩余 25s（>20s 不紧急），1Hz tick 过了阈值才紧急：仍须有 trace 可回溯。
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));

        nowMs.addAndGet(5_000L);
        sec.addAndGet(5L);
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 25L), OWN_UID);
        assertFalse("剩余 25s 不紧急，应继续挂起", r.tick(e));

        String out = capture(() -> {
            // 越过卡秒挂起点（etaCheckAtMs）后无新推送：决策时 remain=7s ≤20s → 紧急
            nowMs.addAndGet(18_000L);
            sec.addAndGet(18L);
            boolean acted = r.tick(e);
            assertTrue("到点后应紧急开盾", acted);
        });
        String line = lineContaining(out, "盾时延 trace");
        assertTrue("应记 opened：" + line, line.contains("outcome=opened"));
        assertEquals("无紧急唤醒回调时应记 wake=-1：" + line, -1L, field(line, "wake"));
        assertTrue("仍应有 PUSH 时刻：" + line, field(line, "push") > 0L);
        assertTrue("仍应有 LOCK/RESP：" + line,
                field(line, "lock") > 0L && field(line, "resp") > 0L);
    }

    // ---------------- R0-1 并发/多推送：trace 上下文一致性 ----------------

    @Test
    public void urgentPushDuringRequest_keepsSingleCompleteTrace() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));

        nowMs.addAndGet(5_000L);
        sec.addAndGet(5L);
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
        long pushA = nowMs.get();
        // 开盾请求进行中又来一条更急的推送：不得改写本次 trace 的 PUSH/LOCK/RESP
        e.duringRequest = () -> {
            nowMs.addAndGet(30L);
            r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 4L), OWN_UID);
        };
        String out = capture(() -> r.tick(e));

        assertEquals("一次决策只输出 1 条 trace：" + out, 1, count(out, "盾时延 trace"));
        String line = lineContaining(out, "盾时延 trace");
        assertEquals("PUSH 应为触发本次决策的推送时刻：" + line, pushA, field(line, "push"));
        assertTrue("LOCK 不应为 -1：" + line, field(line, "lock") > 0L);
        assertTrue("RESP 不应早于 LOCK：" + line, field(line, "resp") >= field(line, "lock"));
        assertTrue("outcome 应为 opened：" + line, line.contains("outcome=opened"));
    }

    @Test
    public void twoUrgentPushesBeforeDecision_keepEarliestPushTime() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));

        nowMs.addAndGet(5_000L);
        sec.addAndGet(5L);
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
        long pushA = nowMs.get();
        nowMs.addAndGet(100L);
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);

        String out = capture(() -> r.tick(e));
        assertEquals("一次决策只输出 1 条 trace：" + out, 1, count(out, "盾时延 trace"));
        String line = lineContaining(out, "盾时延 trace");
        assertEquals("决策前的后续紧急推送不得改写 PUSH：" + line, pushA, field(line, "push"));
        assertTrue("outcome 应为 opened：" + line, line.contains("outcome=opened"));
    }

    @Test
    public void freeShieldPath_recordsLockAndResp() {
        account("{\"sub\":{\"reactive\":true,\"free\":true}}");
        FakeEngine e = new FakeEngine();
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("data", List.of(new LinkedHashMap<>(Map.of("free_id", 777L))));
        e.notifies.put("notify_city_item_init", city);
        e.responses.put("req_city_item_free_use", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));
        nowMs.addAndGet(5_000L);
        sec.addAndGet(5L);
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
        String out = capture(() -> r.tick(e));

        assertTrue("应走免费盾请求：" + e.requested, e.requested.contains("req_city_item_free_use"));
        String line = lineContaining(out, "盾时延 trace");
        assertTrue("免费盾也应记 LOCK：" + line, field(line, "lock") > 0L);
        assertTrue("免费盾也应记 RESP：" + line, field(line, "resp") > 0L);
        assertTrue("免费盾成功应记 opened：" + line, line.contains("outcome=opened"));
    }

    @Test
    public void calibratedClock_traceFieldsReflectSample() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        GameServerClock ck = new GameServerClock();
        ck.calibratePush(new LinkedHashMap<>(Map.of("server_time", 1_700_000_000L)),
                GameServerClock.monoNow());
        e.clock = ck;
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));
        nowMs.addAndGet(5_000L);
        sec.addAndGet(5L);
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
        String out = capture(() -> r.tick(e));

        String line = lineContaining(out, "盾时延 trace");
        assertTrue("已校准 err 应为非负：" + line, field(line, "err") >= 0L);
        assertTrue("已校准 sinceCalib 应为非负：" + line, field(line, "sinceCalib") >= 0L);
        assertTrue("已校准 src 应为样本来源：" + line, line.contains("src=PUSH"));
    }

    // ---------------- R0-4 既有日志追加字段 ----------------

    @Test
    public void suspendLog_appendsClockFields_keepsPrefix() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        String out = capture(() -> r.tick(e));

        String line = lineContaining(out, "反应式开盾挂起");
        assertTrue("既有文案前缀不得改：" + line,
                line.contains("反应式开盾挂起 fpid=111 事件=WAR：约300秒后到达（卡秒2秒）"));
        assertTrue("应追加 endtime：" + line,
                line.contains("endtime=" + (sec.get() + 300L)));
        assertTrue("应追加 serverNow：" + line, line.contains("serverNow=" + sec.get()));
        assertTrue("应追加 err：" + line, line.contains("err=-1ms"));
        assertTrue("应追加 sinceCalib：" + line, line.contains("sinceCalib=-1ms"));
        assertTrue("应追加 src：" + line, line.contains("src=LOCAL"));
    }

    @Test
    public void backpackSuccessLog_appendsClockFields_keepsPrefix() {
        account("{\"sub\":{\"reactive\":true},\"strategy\":{\"shield_mode\":\"immediate\"}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        String out = capture(() -> r.tick(e));

        String line = lineContaining(out, "来源=背包");
        assertTrue("既有文案前缀不得改：" + line,
                line.contains("反应式开盾 fpid=111 事件=WAR 来源=背包 item="
                        + ServerShieldResponder.SHIELD_ITEM_2H + " code=0"));
        assertTrue("应追加 endtime：" + line,
                line.contains("endtime=" + (sec.get() + 300L)));
        assertTrue("应追加 serverNow：" + line, line.contains("serverNow=" + sec.get()));
        assertTrue("应追加 err：" + line, line.contains("err=-1ms"));
        assertTrue("应追加 sinceCalib：" + line, line.contains("sinceCalib=-1ms"));
        assertTrue("应追加 src：" + line, line.contains("src=LOCAL"));
    }

    // ---------------- R0-5 到达后观测 ----------------

    @Test
    public void arrivalObservation_sameMarchLoggedOnce() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(worldPush(13080L, 2), OWN_UID);
        assertEquals("应先跟踪到敌方行军", 1, r.trackedEnemyMarchCount());

        String out = capture(() -> {
            r.onSituationDel(Map.of("id", 13080L));
            r.onSituationDel(Map.of("id", 13080L));
        });

        assertEquals("同一 marchId 只记首次：" + out, 1, count(out, "盾到达观测"));
        String line = lineContaining(out, "盾到达观测");
        assertEquals(nowMs.get(), field(line, "wall"));
        assertTrue("应带 marchId：" + line, line.contains("marchId=13080"));
        assertTrue("应带 slot：" + line, line.contains("slot=0"));
        assertTrue("应带 source：" + line, line.contains("source=SITUATION_DEL"));
        assertEquals("行军已移除", 0, r.trackedEnemyMarchCount());
    }

    @Test
    public void arrivalObservation_slotUnmapped_logsSlotWithMarker() {
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        String out = capture(() -> {
            r.onSituationDel(Map.of("id_arr", List.of(9.0)));
            r.onSituationDel(Map.of("id_arr", List.of(9.0)));
        });
        assertEquals("无 marchId 可映射时仍记一条（同槽位不重复）：" + out,
                1, count(out, "盾到达观测"));
        String line = lineContaining(out, "盾到达观测");
        assertTrue("应带 slot：" + line, line.contains("slot=9"));
        assertEquals("无法映射时 marchId 记 0：" + line, 0L, field(line, "marchId"));
        assertTrue("应标注 unmapped：" + line, line.contains("unmapped=true"));
        assertTrue("应带 source：" + line, line.contains("source=SITUATION_DEL"));
    }

    // ---------------- R0-6 零行为变更：不监听 march_del ----------------

    @Test
    public void marchDelProto_notWatched() {
        assertFalse("notify_world_march_del 不得加入 SITUATION_PROTOS（会改变分发行为）",
                ServerShieldResponder.SITUATION_PROTOS.contains("notify_world_march_del"));

        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.attach(e);

        Map<String, Object> content = alliancePush(13080L, 2);
        e.push("notify_world_march_del", content);

        assertTrue("march_del 不应进入风险判定", r.lastEventKind.isEmpty());
        assertFalse("march_del 不应触发决策", r.tick(e));
        assertTrue("march_del 不应发请求：" + e.requested, e.requested.isEmpty());
    }

    // ---------------- 零行为变更辅助断言 ----------------

    @Test
    public void traceFields_documentedPlaceholders() {
        // 字段名属于对外日志契约（分支 2 的解析脚本依赖），逐字锁定
        account("{\"sub\":{\"reactive\":true}}");
        FakeEngine e = new FakeEngine();
        presetBackpack(e, ServerShieldResponder.SHIELD_ITEM_2H, 1L);
        e.responses.put("req_use_item", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setUrgentWakeup(() -> { });
        r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 300L), OWN_UID);
        r.tick(e);
        String out = capture(() -> {
            nowMs.addAndGet(5_000L);
            sec.addAndGet(5L);
            r.onPush(withEndtime(alliancePush(13080L, 2), sec.get() + 6L), OWN_UID);
            r.tick(e);
        });
        String line = lineContaining(out, "盾时延 trace");
        for (String key : new String[]{"id", "fpid", "sid", "uid", "push", "wake", "lock",
                "resp", "remain", "endtime", "serverNow", "wall", "err", "sinceCalib",
                "src", "urgent", "outcome"}) {
            assertTrue("trace 缺字段 " + key + "：" + line,
                    line.contains(key + "="));
        }
    }
}
