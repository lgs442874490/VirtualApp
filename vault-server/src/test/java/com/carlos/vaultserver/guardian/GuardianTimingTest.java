package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 C-A3：守护卡秒发射（fireAt = 敌 endSec - dSec - fireMarginSec）。
 *
 * <p>覆盖：fireAt 纯函数、默认开启/参数与 env 关闭、未到点不发射且记 {@code timing_wait}、
 * ETA 刷新重排定时任务、到达 fireAt 发射、入口/tick 自愈（不依赖真实等待）、威胁消失/stop 取消、
 * dry_run 同样卡秒（[DRY] 文案）。
 */
public class GuardianTimingTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;
    private static final long ENEMY_UID = 49422287L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final List<ServerGuardianEngine> engines = new ArrayList<>();

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
        vault.load();
    }

    @After
    public void clean() {
        for (ServerGuardianEngine e : engines) {
            try {
                e.shutdown();
            } catch (Throwable ignored) {
            }
        }
        engines.clear();
        System.clearProperty("VAULT_GUARDIAN_TIMING");
    }

    // ---------------- 夹具 ----------------

    private void accountWithParams(String guardianJson) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        if (guardianJson != null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put(ServerGuardianEngine.FN_ID, guardianJson);
            a.roleParams.put(String.valueOf(SID), params);
        }
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    private ServerGuardianEngine engine() {
        ServerGuardianEngine e = new ServerGuardianEngine(FPID, SID, 0L, state, vault,
                null, clock::get);
        engines.add(e);
        return e;
    }

    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        final Map<String, GameMessage> responses = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<Map<String, Object>> argLog = new ArrayList<>();

        TestEngine() {
            super(null);
        }

        @Override public String sessionState() { return STATE_ONLINE; }
        @Override public long lastUid() { return OWN_UID; }
        @Override public long lastServerId() { return SID; }
        @Override public Map<String, Object> notifyContent(String proto) { return notifies.get(proto); }
        @Override public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) v;
                    return m;
                }
            }
            return c;
        }
        @Override public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            argLog.add(args);
            return responses.get(proto);
        }
        @Override public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
    }

    private static Map<String, Object> point(long x, long y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    private static Map<String, Object> attackPush(long marchId, long targetUid, long endSec,
                                                  int x, int y) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", ENEMY_UID);
        march.put("type", 2L);
        march.put("target_type", 1L);
        march.put("endtime", endSec);
        march.put("state", 2L);
        march.put("endpoint", point(x, y));
        march.put("atk_city", atk);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private static Map<String, Object> delPush(long marchId) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private static GameMessage launchOk(long marchId, long endSec) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", marchId);
        info.put("endtime", endSec);
        info.put("state", 2L);
        info.put("type", 5L);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        c.put("march_info", info);
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private static void presetSoldiers(TestEngine e) {
        Map<String, Object> soldier = new LinkedHashMap<>();
        for (long id : new long[]{11000L, 21000L, 31000L}) {
            soldier.put(id + ".0", new LinkedHashMap<>(Map.of("id", id, "count", 1000L)));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("soldier", soldier);
        e.notifies.put("notify_soldier_init", content);
    }

    private static void presetQueueOk(TestEngine e) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", 5L);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        e.notifies.put("notify_world_player_init", wpi);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", 0);
        detail.put("marchs", new ArrayList<>());
        GameMessage m = new GameMessage();
        m.setContent(detail);
        e.responses.put(ServerGuardianEngine.PROTO_MARCH_DETAIL, m);
    }

    private JSONArray ledger() {
        String raw = state.getString(ServerGuardianEngine.ledgerKey(FPID, SID));
        return raw == null ? new JSONArray() : new JSONArray(raw);
    }

    private JSONObject lastLedger() {
        JSONArray arr = ledger();
        assertTrue("缺账本", arr.length() > 0);
        return arr.getJSONObject(0);
    }

    private int ledgerCount(String ev) {
        int n = 0;
        JSONArray arr = ledger();
        for (int i = 0; i < arr.length(); i++) {
            if (ev.equals(arr.getJSONObject(i).optString("ev"))) n++;
        }
        return n;
    }

    /** 批次 D：timing 默认已改 false（核心是驻防成功）；本类验证 A3 卡秒故显式开启。
     *  同时显式关闭 catch_up：卡秒用例只测发射时序，避免追赶账本干扰断言。 */
    private static final String LIVE_CFG = "{\"sub\":{\"enabled\":true,\"dry_run\":false,"
            + "\"catch_up\":false},"
            + "\"strategy\":{\"timing\":true,\"require_ally\":false,\"formation\":{\"hero_ids\":[1]}}}";
    private static final String DRY_CFG = "{\"sub\":{\"enabled\":true,\"dry_run\":true,"
            + "\"catch_up\":false},"
            + "\"strategy\":{\"timing\":true,\"require_ally\":false}}";

    // ---------------- 纯函数：fireAt / 配置默认与关闭 ----------------

    @Test
    public void fireAt_pureFunction_andConfigDefaults() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertFalse("批次 D：卡秒默认关闭（立即发射优先）", def.timingEnabled);
        assertEquals("默认 fire margin=5s", 5L, def.fireMarginSec);
        assertEquals("默认并发=6", 6, def.maxConcurrent);
        assertTrue("默认攻击类型含 2/102", def.attackTypes.contains(2L)
                && def.attackTypes.contains(102L));
        assertTrue(def.describe().contains("timing=false"));
        assertTrue(def.describe().contains("max_concurrent=6"));

        ServerGuardianEngine.Threat t = new ServerGuardianEngine.Threat(
                1L, ENEMY_UID, 2L, OWN_UID, 674, 684, true, 1000L, clock.get());
        assertEquals("fireAt = end - d(38) - margin(5)", 957L,
                ServerGuardianEngine.fireAtSec(t, def));

        ServerGuardianEngine.Config custom = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"timing\":false,\"require_ally\":false,\"fire_margin_sec\":7,\"d_sec\":40,"
                        + "\"max_concurrent\":2}}"));
        assertFalse(custom.timingEnabled);
        assertEquals(7L, custom.fireMarginSec);
        assertEquals(2, custom.maxConcurrent);
        assertEquals(953L, ServerGuardianEngine.fireAtSec(t, custom));

        // 界外值 fail-safe
        ServerGuardianEngine.Config clamp = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"fire_margin_sec\":999,\"max_concurrent\":9}}"));
        assertEquals(ServerGuardianEngine.DEFAULT_FIRE_MARGIN_SEC, clamp.fireMarginSec);
        assertEquals("max_concurrent 上限 6", 6, clamp.maxConcurrent);
        assertEquals("0 → 下限 1", 1, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":0}}")).maxConcurrent);
    }

    // ---------------- 未到点等待 → 自愈到期发射 ----------------

    @Test
    public void futureThreat_waitsAndSelfHealFiresAt() {
        accountWithParams(DRY_CFG);
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long now = clock.get();
        long end = now / 1000L + 120L;
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, OWN_UID, end, 679, 686), now);
        engine.awaitIdleForTest(2_000L);

        assertEquals("未到 fireAt 不发动作", 0, engine.missionCount());
        assertEquals("注册 1 个延迟任务", 1, engine.fireTaskCount());
        long fireAt = end - ServerGuardianEngine.DEFAULT_D_SEC - 5L;
        assertEquals("计划 fireAt", fireAt, engine.scheduledFireAtSec(13080L));
        JSONObject wait = lastLedger();
        assertEquals("timing_wait", wait.getString("ev"));
        assertTrue("dry_run 卡秒文案 [DRY]: " + wait.getString("note"),
                wait.getString("note").startsWith("[DRY]"));

        // 推进注入时钟到 fireAt：入口自愈（模拟定时器丢失）→ 立即提交 guardExec
        clock.set(fireAt * 1000L + 1_000L);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, Map.of(), clock.get());
        engine.awaitIdleForTest(2_000L);
        assertEquals("到期自愈后完成 dry_run 记录", "dry_run", lastLedger().getString("ev"));
        assertEquals("定时任务已消费", 0, engine.fireTaskCount());
    }

    @Test
    public void nowAtOrPastFireAt_launchesImmediately_notLater() {
        accountWithParams(LIVE_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = clock.get();
        long end = now / 1000L + 40L;   // fireAt = now-3 → 已到点
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, end + 20L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, OWN_UID, end, 679, 686), now);
        engine.awaitIdleForTest(3_000L);

        assertTrue("已到 fireAt 必须立即发射（不比旧行为晚）",
                e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(0, engine.fireTaskCount());
        assertEquals("没有 timing_wait（未等待）", 0, ledgerCount("timing_wait"));
        assertEquals("launched", lastLedger().getString("ev"));
    }

    @Test
    public void timingOff_returnsToImmediateLaunch() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"timing\":false,\"require_ally\":false,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = clock.get();
        long end = now / 1000L + 120L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, end + 20L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, OWN_UID, end, 679, 686), now);
        engine.awaitIdleForTest(3_000L);
        assertTrue("timing=false → 旧立即发射", e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(0, engine.fireTaskCount());
        assertEquals(0, ledgerCount("timing_wait"));
    }

    @Test
    public void envTimingOff_returnsToImmediateLaunch() {
        accountWithParams(LIVE_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = clock.get();
        long end = now / 1000L + 120L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, end + 20L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        try {
            System.setProperty("VAULT_GUARDIAN_TIMING", "off");
            engine.reloadConfig(true);   // 立即热载 env（Config TTL 10s，否则可能沿用旧值）
            engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                    attackPush(13080L, OWN_UID, end, 679, 686), now);
            engine.awaitIdleForTest(3_000L);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_TIMING");
        }
        assertTrue("env off → 立即发射", e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(0, engine.fireTaskCount());
    }

    // ---------------- ETA 刷新重排 / 取消 ----------------

    @Test
    public void etaRefresh_reschedulesEarlierAndRearranges() {
        accountWithParams(DRY_CFG);
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long now = clock.get();
        long end = now / 1000L + 120L;
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, OWN_UID, end, 679, 686), now);
        long fireAt1 = engine.scheduledFireAtSec(13080L);
        assertEquals("首次计划", end - 43L, fireAt1);

        clock.addAndGet(5_000L);
        long newEnd = end - 30L;   // 敌加速：endtime 前移 30s
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, OWN_UID, newEnd, 679, 686), clock.get());
        long fireAt2 = engine.scheduledFireAtSec(13080L);
        assertEquals("ETA 刷新后重排：fireAt 前移 30s", fireAt1 - 30L, fireAt2);
        assertEquals("同一威胁只保留一个定时任务", 1, engine.fireTaskCount());
        assertTrue("等待计划变化会再记一次 timing_wait", ledgerCount("timing_wait") >= 2);

        clock.set(fireAt2 * 1000L + 500L);
        engine.selfHealTimingAt(e, clock.get());
        engine.awaitIdleForTest(2_000L);
        assertEquals("新 fireAt 到期后执行", "dry_run", lastLedger().getString("ev"));
    }

    @Test
    public void threatGone_cancelsScheduledFire() {
        accountWithParams(DRY_CFG);
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long now = clock.get();
        long end = now / 1000L + 120L;
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, OWN_UID, end, 679, 686), now);
        assertEquals(1, engine.fireTaskCount());
        long fireAt = engine.scheduledFireAtSec(13080L);

        clock.addAndGet(1_000L);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13080L), clock.get());
        assertEquals("威胁消失 → 取消定时任务", 0, engine.fireTaskCount());
        assertEquals(-1L, engine.scheduledFireAtSec(13080L));
        assertTrue("记 timing_cancel", ledgerCount("timing_cancel") >= 1);

        clock.set(fireAt * 1000L + 5_000L);
        engine.selfHealTimingAt(e, clock.get());
        engine.awaitIdleForTest(1_000L);
        assertEquals("取消后不得再发射/记账", 0, ledgerCount("dry_run"));
    }

    @Test
    public void shutdown_cancelsScheduledFires() {
        accountWithParams(DRY_CFG);
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long now = clock.get();
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, OWN_UID, now / 1000L + 120L, 679, 686), now);
        assertEquals(1, engine.fireTaskCount());
        engine.shutdown();
        assertEquals("shutdown 取消全部定时任务", 0, engine.fireTaskCount());
    }

    @Test
    public void fireTaskCount_zeroWhenDryRunThreatHasNoEta() {
        accountWithParams(DRY_CFG);
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long now = clock.get();
        // endtime 缺失（0）→ fireAt=0 → 立即（不再等待），交给 decide 拒绝 no_eta
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13081L, OWN_UID, 0L, 679, 686), now);
        engine.awaitIdleForTest(1_000L);
        assertEquals("无 ETA 不注册卡秒", 0, engine.fireTaskCount());
        assertEquals("无 ETA 不发射", 0, engine.missionCount());
    }
}
