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
import static org.junit.Assert.assertTrue;

/**
 * 批次 F：守护队列等待重试（skip_queue → 5s 后重查 free，最多 queue_retry_max 次）。
 *
 * <p>覆盖：free=0 记 1 条 skip_queue + 安排重试；free 空出后重试成功发射；上限 giveup；
 * 超过敌 end+60s 截止；威胁消失/shutdown/offline 取消；retry=0/env off 旧行为；账本 ≤1 条/分钟。
 * 假时钟 + 手工触发 {@link ServerGuardianEngine#queueRetryFire}，不真 sleep。
 */
public class GuardianQueueRetryTest {

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
        System.clearProperty("VAULT_GUARDIAN_QUEUE_RETRY");
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
        int capacity;
        int baseActiveMarches;
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
                int n = baseActiveMarches + countProto(this, ServerGuardianLauncher.PROTO_WORLD_MARCH);
                List<Map<String, Object>> marchs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", 8000L + i);
                    m.put("state", 2L);
                    m.put("type", 5L);
                    marchs.add(m);
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", 0);
                c.put("marchs", marchs);
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

    private static Map<String, Object> march(long id, long endSec, int x, int y) {
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
        ep.put("x", (long) x);
        ep.put("y", (long) y);
        m.put("endpoint", ep);
        m.put("atk_city", atk);
        return m;
    }

    private static Map<String, Object> push(Map<String, Object> march) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private static Map<String, Object> delPush(long marchId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", marchId);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(m));
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

    private static void presetQueue(TestEngine e, int capacity, int baseActive) {
        e.capacity = capacity;
        e.baseActiveMarches = baseActive;
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

    private static int countProto(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) if (proto.equals(p)) n++;
        return n;
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
        JSONArray arr = ledger();
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            if (ev.equals(arr.getJSONObject(i).optString("ev"))) n++;
        }
        return n;
    }

    private static String cfg(int retryMax) {
        return "{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"queue_retry_max\":" + retryMax
                + ",\"require_ally\":false"
                + ",\"formation\":{\"hero_ids\":[1]}}}";
    }

    /** 初次推送：队列满（free=0），返回 engine。 */
    private ServerGuardianEngine pushQueueFull(TestEngine e, String cfgJson, long marchId) {
        accountWithParams(cfgJson);
        presetQueue(e, 1, 1);   // capacity=1 / 已有 1 队在途 → free=0
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, nowS + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(marchId, nowS + 120L, 524, 420)));
        engine.awaitIdleForTest(2_000L);
        return engine;
    }

    private static void retryNow(ServerGuardianEngine engine, TestEngine e, long marchId) {
        engine.cancelQueueRetry(marchId);   // 模拟 fireSched 到期：移除在途任务
        engine.queueRetryFire(e, marchId);
        engine.awaitIdleForTest(2_000L);
    }

    // ---------------- 1. 配置 ----------------

    @Test
    public void parseConfig_queueRetryDefaultsAndClamp() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertEquals(12, def.queueRetryMax);
        assertEquals(5_000L, def.queueRetryIntervalMs);
        assertTrue(def.describe().contains("queue_retry_max=12"));

        assertEquals(0, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"queue_retry_max\":0}}")).queueRetryMax);
        assertEquals(60, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"queue_retry_max\":99}}")).queueRetryMax);
        assertEquals("间隔 500ms 界外→默认", 5_000L, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"queue_retry_interval_ms\":500}}"))
                .queueRetryIntervalMs);
        assertEquals(1_000L, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"queue_retry_interval_ms\":1000}}"))
                .queueRetryIntervalMs);
    }

    // ---------------- 2. free=0 → 重试 → free 空出后成功 ----------------

    @Test
    public void queueFull_retrySucceedsWhenFree() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(12), 13080L);

        assertEquals("首发 skip_queue 只记 1 条", 1, ledgerCount("skip_queue"));
        assertEquals("安排第 1 次重试", 1, engine.queueRetryCountForTest(13080L));
        assertEquals(1, engine.queueRetryTaskCountForTest());
        assertFalse("队列满不发射", e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));

        // 队列空出（occupied 0 → free=1），手工触发重试
        e.baseActiveMarches = 0;
        retryNow(engine, e, 13080L);
        assertTrue("free 空出后重试发射成功",
                e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("launched", lastLedger().getString("ev"));
        assertEquals(0, engine.queueRetryCountForTest(13080L));
        assertEquals(0, engine.queueRetryTaskCountForTest());
    }

    // ---------------- 3. 上限 giveup ----------------

    @Test
    public void retryMax_reachesGiveup() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(3), 13081L);

        for (int i = 0; i < 3; i++) {
            retryNow(engine, e, 13081L);
        }
        assertEquals("达到上限后记 queue_giveup", 1, ledgerCount("queue_giveup"));
        assertEquals(0, engine.queueRetryTaskCountForTest());
        assertFalse(e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));

        retryNow(engine, e, 13081L);
        assertEquals("giveup 后不再新增账本刷屏", 1, ledgerCount("queue_giveup"));
    }

    // ---------------- 4. 截止（敌 end+60s） ----------------

    @Test
    public void deadline_afterLanded60s_givesUp() {
        TestEngine e = new TestEngine();
        accountWithParams(cfg(20));
        presetQueue(e, 1, 1);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, nowS + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13082L, nowS + 10L, 524, 420)));
        engine.awaitIdleForTest(2_000L);
        assertEquals(1, engine.queueRetryCountForTest(13082L));

        clock.addAndGet(71_000L);   // 越过 end+60s
        retryNow(engine, e, 13082L);
        assertTrue("超过截止 → queue_giveup(原因含截止)",
                ledgerCount("queue_giveup") >= 1);
        assertTrue(lastLedger().getString("note").contains("截止"));
        assertFalse(e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }

    // ---------------- 5. 取消：gone / shutdown / offline ----------------

    @Test
    public void threatGone_cancelsQueueRetry() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(12), 13083L);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13083L));
        engine.awaitIdleForTest(1_000L);
        assertEquals(0, engine.queueRetryTaskCountForTest());
        assertEquals(0, engine.queueRetryCountForTest(13083L));
        assertTrue("取消记 queue_cancel", ledgerCount("queue_cancel") >= 1);
    }

    @Test
    public void shutdown_cancelsQueueRetry() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(12), 13084L);
        assertEquals(1, engine.queueRetryTaskCountForTest());
        engine.shutdown();
        assertEquals(0, engine.queueRetryTaskCountForTest());
    }

    @Test
    public void offline_cancelsQueueRetry() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(12), 13085L);
        e.online = false;
        retryNow(engine, e, 13085L);
        assertEquals(0, engine.queueRetryTaskCountForTest());
        assertEquals(0, engine.queueRetryCountForTest(13085L));
        assertTrue(ledgerCount("queue_cancel") >= 1);
    }

    // ---------------- 6. retry=0 / env off 旧行为 ----------------

    @Test
    public void retryZero_oldBehavior() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(0), 13086L);
        assertEquals(1, ledgerCount("skip_queue"));
        assertEquals(0, engine.queueRetryCountForTest(13086L));
        assertEquals(0, engine.queueRetryTaskCountForTest());
        assertEquals("旧行为只记一次", 1, ledgerCount("skip_queue"));
    }

    @Test
    public void envOff_oldBehavior() {
        TestEngine e = new TestEngine();
        accountWithParams(cfg(12));
        presetQueue(e, 1, 1);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, nowS + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        try {
            System.setProperty("VAULT_GUARDIAN_QUEUE_RETRY", "off");
            engine.reloadConfig(true);
            engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                    push(march(13087L, nowS + 120L, 524, 420)));
            engine.awaitIdleForTest(2_000L);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_QUEUE_RETRY");
        }
        assertEquals(1, ledgerCount("skip_queue"));
        assertEquals(0, engine.queueRetryTaskCountForTest());
    }

    // ---------------- 7. 账本节流 ≤1 条/分钟 ----------------

    @Test
    public void ledgerThrottled_onePerMinute() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = pushQueueFull(e, cfg(20), 13088L);
        assertEquals(1, ledgerCount("skip_queue"));

        for (int i = 0; i < 3; i++) {
            retryNow(engine, e, 13088L);   // 1 分钟内多次重试
        }
        assertEquals("同一威胁 1 分钟内仅 1 条 skip_queue", 1, ledgerCount("skip_queue"));

        clock.addAndGet(61_000L);
        retryNow(engine, e, 13088L);
        assertEquals("超过 1 分钟再记 1 条", 2, ledgerCount("skip_queue"));
    }
}
