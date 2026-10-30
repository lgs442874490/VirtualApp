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
 * 批次 G2：队列满时召回采集队腾位（preempt）。
 *
 * <p>生产实证（2026-10-31）：队列 3/3、6/6 被采集/情报长期占满，守护 12 次重试后 giveup。
 * 口径：核心是驻防成功 → 允许召回 1 队 type=4 采集；绝不召回保护类（5/99/102/411 等）；
 * 每威胁最多 1 队、60s 冷却、每分钟最多 2 队；成功后 5s 重试发射；dry_run 不召回。
 */
public class GuardianPreemptTest {

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
        System.clearProperty("VAULT_GUARDIAN_PREEMPT");
    }

    // ---------------- 夹具 ----------------

    private void accountWithParams(String guardianJson) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        Map<String, String> params = new LinkedHashMap<>();
        params.put(ServerGuardianEngine.FN_ID, guardianJson);
        a.roleParams.put(String.valueOf(SID), params);
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
        final List<Map<String, Object>> ownMarches = new ArrayList<>();
        boolean online = true;

        TestEngine() {
            super(null);
        }

        @Override public String sessionState() { return online ? STATE_ONLINE : STATE_TAKEOVER; }
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
            if (ServerGuardianEngine.PROTO_MARCH_DETAIL.equals(proto)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", 0);
                c.put("marchs", new ArrayList<Map<String, Object>>(ownMarches));
                GameMessage gm = new GameMessage();
                gm.setContent(c);
                return gm;
            }
            return responses.get(proto);
        }
        @Override public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
    }

    private static Map<String, Object> ownMarch(long id, long type, long state, long endSec,
                                               long owner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("state", state);
        m.put("endtime", endSec);
        m.put("owner", owner);
        return m;
    }

    private static Map<String, Object> enemyMarch(long id, long endSec) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", ENEMY_UID);
        m.put("type", 102L);
        m.put("target_type", 1L);
        m.put("endtime", endSec);
        m.put("state", 2L);
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", 524L);
        ep.put("y", 420L);
        m.put("endpoint", ep);
        m.put("atk_city", atk);
        return m;
    }

    private static Map<String, Object> push(Map<String, Object> march) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
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

    /** 容量固定 1；在途数=ownMarches 中 state∈{2,3,4} 的条数 → 队列满由 mimic 决定。 */
    private static void presetQueue(TestEngine e, int capacity) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", (long) capacity);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        e.notifies.put(ServerGuardianEngine.PROTO_WORLD_PLAYER_INIT, wpi);
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

    private static GameMessage msg(int code) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private static int countProto(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) if (proto.equals(p)) n++;
        return n;
    }

    private static Map<String, Object> lastArgs(TestEngine e, String proto) {
        for (int i = e.requested.size() - 1; i >= 0; i--) {
            if (proto.equals(e.requested.get(i))) return e.argLog.get(i);
        }
        return null;
    }

    private JSONArray ledger() {
        String raw = state.getString(ServerGuardianEngine.ledgerKey(FPID, SID));
        return raw == null ? new JSONArray() : new JSONArray(raw);
    }

    private int ledgerCount(String ev) {
        JSONArray arr = ledger();
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            if (ev.equals(arr.getJSONObject(i).optString("ev"))) n++;
        }
        return n;
    }

    private JSONObject ledgerEvent(String ev) {
        JSONArray arr = ledger();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (ev.equals(o.optString("ev"))) return o;
        }
        return null;
    }

    /** 本类用坐标型威胁（targetUid=0）验证抢占；显式关 require_ally 以免被盟友反查拦截。 */
    private static String cfg(boolean preempt, int queueRetryMax, boolean dryRun) {
        return "{\"sub\":{\"enabled\":true,\"dry_run\":" + dryRun + ",\"preempt_gather\":"
                + preempt + ",\"require_ally\":false,\"rally_enabled\":false},"
                + "\"strategy\":{\"queue_retry_max\":" + queueRetryMax
                + ",\"formation\":{\"hero_ids\":[1]}}}";
    }

    private ServerGuardianEngine pushQueueFull(TestEngine e, String cfgJson, long enemyMarchId) {
        accountWithParams(cfgJson);
        presetQueue(e, 1);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, nowS + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(enemyMarch(enemyMarchId, nowS + 120L)));
        engine.awaitIdleForTest(2_000L);
        return engine;
    }

    // ---------------- 1. 配置 ----------------

    @Test
    public void parseConfig_preemptDefaultsAndClamp() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertTrue("preempt_gather 默认开", def.preemptGather);
        assertEquals(60_000L, def.preemptCooldownMs);
        assertEquals(2, def.preemptMaxPerMinute);
        assertTrue(def.describe().contains("preempt_gather=true"));

        ServerGuardianEngine.Config off = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"preempt_gather\":false},"
                        + "\"strategy\":{\"preempt_cooldown_ms\":0,\"preempt_max_per_minute\":5}}"));
        assertFalse(off.preemptGather);
        assertEquals(0L, off.preemptCooldownMs);
        assertEquals(5, off.preemptMaxPerMinute);

        assertEquals("每分钟上限夹 10", 10, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"preempt_max_per_minute\":99}")).preemptMaxPerMinute);
        assertEquals("负冷却回落默认", 60_000L, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"preempt_cooldown_ms\":-1}")).preemptCooldownMs);

        System.setProperty("VAULT_GUARDIAN_PREEMPT", "off");
        try {
            assertFalse("env off 回旧行为", ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"enabled\":true}}")).preemptGather);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_PREEMPT");
        }
    }

    // ---------------- 2. 队列满 + 采集队 → 召回并 5s 重试 ----------------

    @Test
    public void queueFull_recallsGather_thenRetryLaunches() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(777L, 4L, 2L, nowS + 600L, OWN_UID));
        ServerGuardianEngine engine = pushQueueFull(e, cfg(true, 12, false), 13080L);

        assertEquals("队列满不发射首次", 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("召回 1 队采集", 1,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        Map<String, Object> recall = lastArgs(e, ServerGuardianLauncher.PROTO_MARCH_RECALL);
        assertNotNull(recall);
        assertEquals(777L, ((Number) recall.get("id")).longValue());
        assertEquals(1, ledgerCount("preempt_recall"));
        assertEquals(1, ledgerCount("preempt_ok"));
        assertNotNull("召回成功安排 5s 重试", engine.preemptRetryTaskCountForTest());
        assertEquals(1, engine.preemptRetryTaskCountForTest());

        // 同一威胁最多 1 队：重试时队列仍满（march 还在）→ 不得第二次召回
        engine.preemptRetryFire(e, 13080L);
        engine.awaitIdleForTest(2_000L);
        assertEquals("同一威胁不重复召回", 1,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));

        // 队列腾出后（批次 F 重试链）成功发射
        e.ownMarches.clear();
        engine.cancelQueueRetry(13080L);
        engine.queueRetryFire(e, 13080L);
        engine.awaitIdleForTest(2_000L);
        assertEquals("队列腾出后发射 1 次", 1,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(1, engine.missionCount());
    }

    // ---------------- 3. 只有保护类 → preempt_none ----------------

    @Test
    public void queueFull_onlyProtected_noRecall_preemptNone() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(801L, 5L, 2L, nowS + 100L, OWN_UID));
        e.ownMarches.add(ownMarch(802L, 99L, 2L, nowS + 100L, OWN_UID));
        e.ownMarches.add(ownMarch(803L, 102L, 2L, nowS + 100L, OWN_UID));
        e.ownMarches.add(ownMarch(804L, 411L, 2L, nowS + 100L, OWN_UID));
        ServerGuardianEngine engine = pushQueueFull(e, cfg(true, 0, false), 13081L);

        assertEquals("保护类绝不召回", 0,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals(1, ledgerCount("preempt_none"));
        assertEquals("无可用重试链时仍记 skip_queue", 1, ledgerCount("skip_queue"));
    }

    // ---------------- 4. 只召回我方（owner 过滤） ----------------

    @Test
    public void queueFull_otherOwnerGather_ignored() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(901L, 4L, 2L, nowS + 600L, ENEMY_UID));
        ServerGuardianEngine engine = pushQueueFull(e, cfg(true, 0, false), 13082L);
        assertEquals("非我方行军不召回", 0,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertTrue(ledgerCount("preempt_none") >= 1);
    }

    // ---------------- 5. 冷却 & 每分钟上限 ----------------

    @Test
    public void preempt_cooldown_blocksSecondThreat() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(1001L, 4L, 2L, nowS + 600L, OWN_UID));
        e.ownMarches.add(ownMarch(1002L, 4L, 2L, nowS + 500L, OWN_UID));
        ServerGuardianEngine engine = pushQueueFull(e, cfg(true, 0, false), 13083L);
        assertEquals(1, countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));

        // 第二个威胁（不同 marchId）在同一分钟且冷却内 → 不得再召回
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(enemyMarch(13084L, nowS + 130L)));
        engine.awaitIdleForTest(2_000L);
        assertEquals("60s 冷却内不重复召回", 1,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertTrue(ledgerCount("preempt_none") >= 1);
    }

    @Test
    public void preempt_maxPerMinute_capsRecalls() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        for (int i = 0; i < 3; i++) {
            e.ownMarches.add(ownMarch(1100L + i, 4L, 2L, nowS + 600L, OWN_UID));
        }
        String json = "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"preempt_gather\":true,"
                + "\"require_ally\":false,\"rally_enabled\":false},"
                + "\"strategy\":{\"queue_retry_max\":0,\"preempt_cooldown_ms\":0,"
                + "\"preempt_max_per_minute\":2,\"formation\":{\"hero_ids\":[1]}}}";
        accountWithParams(json);
        presetQueue(e, 1);
        presetSoldiers(e);
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(enemyMarch(1201L, nowS + 120L)));
        engine.awaitIdleForTest(2_000L);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(enemyMarch(1202L, nowS + 121L)));
        engine.awaitIdleForTest(2_000L);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(enemyMarch(1203L, nowS + 122L)));
        engine.awaitIdleForTest(2_000L);

        assertEquals("每分钟最多 2 队", 2,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertTrue("第 3 队 preempt_none", ledgerCount("preempt_none") >= 1);
    }

    // ---------------- 6. dry_run / 关闭 / 失败 ----------------

    @Test
    public void dryRun_noRecall_noPreemptLedger() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(1301L, 4L, 2L, nowS + 600L, OWN_UID));
        ServerGuardianEngine engine = pushQueueFull(e, cfg(true, 0, true), 13085L);
        assertTrue("dry_run 不发任何请求", e.requested.isEmpty());
        assertEquals(0, ledgerCount("preempt_recall"));
        assertEquals(0, ledgerCount("preempt_ok"));
    }

    @Test
    public void preemptDisabled_oldBehavior() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(1401L, 4L, 2L, nowS + 600L, OWN_UID));
        ServerGuardianEngine engine = pushQueueFull(e, cfg(false, 0, false), 13086L);
        assertEquals("preempt_gather=false → 零召回", 0,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals(0, ledgerCount("preempt_recall"));
        assertEquals(1, ledgerCount("skip_queue"));
    }

    @Test
    public void recallFailed_preemptFailed_noRetryScheduled() {
        TestEngine e = new TestEngine();
        long nowS = clock.get() / 1000L;
        e.ownMarches.add(ownMarch(1501L, 4L, 2L, nowS + 600L, OWN_UID));
        accountWithParams(cfg(true, 0, false));
        presetQueue(e, 1);
        presetSoldiers(e);
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(500));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(enemyMarch(13087L, nowS + 120L)));
        engine.awaitIdleForTest(2_000L);

        assertEquals(1, countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals(1, ledgerCount("preempt_failed"));
        assertEquals("失败不安排 5s 重试", 0, engine.preemptRetryTaskCountForTest());
        assertEquals(1, ledgerCount("skip_queue"));
    }
}
