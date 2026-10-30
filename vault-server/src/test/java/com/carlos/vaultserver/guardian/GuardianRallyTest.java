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
 * 批次 I：集结真伪判定 + 5 分钟倒计时 + 出发秒 −25% 前置/持续加速。
 *
 * <p>覆盖：默认值与 clamp；兵力 > rally_troops_min(默认 30 万) 直接真；否则满兵+有人满兵上车+未取消；
 * 假/取消/无法判定；等待期零请求；出发秒赶得上前置加速、赶不上连续 −25%（优先 301900）。
 */
public class GuardianRallyTest {

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
            try { e.shutdown(); } catch (Throwable ignored) { }
        }
        engines.clear();
    }

    // ---------------- 夹具 ----------------

    private void accountWithParams(String json) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        Map<String, String> params = new LinkedHashMap<>();
        params.put(ServerGuardianEngine.FN_ID, json);
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
        final java.util.ArrayDeque<Long> speedupEnds = new java.util.ArrayDeque<>();
        long launchEnd;

        TestEngine() { super(null); }

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
            if (ServerGuardianEngine.PROTO_MARCH_DETAIL.equals(proto)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", 0);
                c.put("marchs", new ArrayList<>());
                GameMessage gm = new GameMessage();
                gm.setContent(c);
                return gm;
            }
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH.equals(proto)) {
                return launchOk(555L, launchEnd);
            }
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP.equals(proto)) {
                Long end = speedupEnds.pollFirst();
                return speedupOk(end == null ? 0L : end);
            }
            return responses.get(proto);
        }
        @Override public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
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
        return msg(c);
    }

    private static GameMessage speedupOk(long endSec) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        if (endSec > 0L) c.put("endtime", endSec);
        return msg(c);
    }

    private static GameMessage msg(Map<String, Object> c) {
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private static Map<String, Object> pointObj(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", (long) x);
        p.put("y", (long) y);
        return p;
    }

    private static Map<String, Object> massAdd(long id, long endSec, long soldierMax, long soldierCount,
                                               boolean memberFull, boolean withMembers) {
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("soldier_max", soldierMax);
        mass.put("soldier_count", soldierCount);
        mass.put("max", 10L);
        if (withMembers) {
            List<Map<String, Object>> members = new ArrayList<>();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uid", 7001L);
            m.put("soldier", memberFull ? soldierMax / 10L : soldierMax / 20L);
            m.put("soldier_max", soldierMax / 10L);
            members.add(m);
            mass.put("members", members);
        }
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", id);
        march.put("type", 102L);
        march.put("owner", ENEMY_UID);
        march.put("endtime", endSec);
        march.put("starttime", clockSec() - 5L);
        march.put("endpoint", pointObj(500, 500));
        march.put("mass_master", mass);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("march", march);
        return c;
    }

    private static long clockSec() { return System.currentTimeMillis() / 1000L; }

    private static Map<String, Object> attackPush(long id, long endSec) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", ENEMY_UID);
        m.put("type", 102L);
        m.put("target_type", 1L);
        m.put("endtime", endSec);
        m.put("state", 2L);
        m.put("endpoint", pointObj(500, 500));
        m.put("atk_city", atk);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(m));
        return c;
    }

    private static Map<String, Object> massDel(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", 102L);
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

    private static void presetQueue(TestEngine e, int capacity) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", (long) capacity);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        e.notifies.put(ServerGuardianEngine.PROTO_WORLD_PLAYER_INIT, wpi);
    }

    private static void presetStock(TestEngine e, long n25) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (n25 > 0) data.add(new LinkedHashMap<>(Map.of("id", 301900L, "count", n25)));
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("data", data);
        e.notifies.put("notify_backpack_init", bp);
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

    private static int requestCount(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) if (proto.equals(p)) n++;
        return n;
    }

    private static final String CFG = "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":false},"
            + "\"strategy\":{\"require_ally\":false,\"rally_troops_min\":300000,"
            + "\"formation\":{\"hero_ids\":[1]}}}";

    private ServerGuardianEngine.Rally rally(long soldierMax, long soldierCount,
                                             boolean hasSoldierInfo, boolean hasJoinInfo,
                                             boolean someoneFull, boolean cancelled) {
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(1L, clock.get());
        r.soldierMax = soldierMax;
        r.soldierCount = soldierCount;
        r.hasSoldierInfo = hasSoldierInfo;
        r.hasJoinInfo = hasJoinInfo;
        r.someoneFull = someoneFull;
        r.cancelled = cancelled;
        return r;
    }

    // ---------------- 1. 配置默认 / clamp ----------------

    @Test
    public void parseConfig_rallyDefaultsAndClamp() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertTrue(def.rallyEnabled);
        assertEquals(300L, def.rallyWaitSec);
        assertTrue(def.rallyEarlySpeedup);
        assertEquals(1.0d, def.rallyFullTroopsRatio, 0.0001d);
        assertTrue(def.rallyRequireJoinFull);
        assertEquals(300_000L, def.rallyTroopsMin);
        assertTrue(def.describe().contains("rally_troops_min=300000"));

        assertEquals(600L, ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"rally_wait_sec\":999}}")).rallyWaitSec);
        assertEquals(0L, ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"rally_wait_sec\":-5}}")).rallyWaitSec);
        assertEquals("越界 clamp 上限 10,000,000", 10_000_000L,
                ServerGuardianEngine.parseConfig(new JSONObject(
                        "{\"strategy\":{\"rally_troops_min\":20000000}}")).rallyTroopsMin);
        assertEquals(0L, ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"rally_troops_min\":-1}}")).rallyTroopsMin);
        assertEquals(500_000L, ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"rally_troops_min\":\"500000\"}}")).rallyTroopsMin);
        assertEquals(0.9d, ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"rally_full_troops_ratio\":0.9}}")).rallyFullTroopsRatio, 0.0001d);
    }

    // ---------------- 2. 真伪判定（>30 万直接真；否则满兵+上车） ----------------

    @Test
    public void truth_troopsAboveMin_directTrueEvenWithoutJoin() {
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        // 兵力 40 万 > 30 万：即使没有逐成员上车信息也算真
        assertEquals(ServerGuardianEngine.RallyTruth.TRUE,
                ServerGuardianEngine.evaluateRally(rally(100_000L, 400_000L, true, false, false, false), cfg));
        // 恰好 30 万不“大于” → 回退满兵/上车判据；缺上车信息 → UNKNOWN
        assertEquals(ServerGuardianEngine.RallyTruth.UNKNOWN,
                ServerGuardianEngine.evaluateRally(rally(300_000L, 300_000L, true, false, false, false), cfg));
        // 满兵 + 有人满兵上车 → 真
        assertEquals(ServerGuardianEngine.RallyTruth.TRUE,
                ServerGuardianEngine.evaluateRally(rally(300_000L, 300_000L, true, true, true, false), cfg));
        // 满兵但无人满兵上车 → 假
        assertEquals(ServerGuardianEngine.RallyTruth.FAKE,
                ServerGuardianEngine.evaluateRally(rally(300_000L, 300_000L, true, true, false, false), cfg));
        // 未满兵 → 假
        assertEquals(ServerGuardianEngine.RallyTruth.FAKE,
                ServerGuardianEngine.evaluateRally(rally(300_000L, 200_000L, true, true, true, false), cfg));
        // 取消 → CANCELLED
        assertEquals(ServerGuardianEngine.RallyTruth.CANCELLED,
                ServerGuardianEngine.evaluateRally(rally(300_000L, 400_000L, true, true, true, true), cfg));
        // 缺兵力信息 → UNKNOWN
        assertEquals(ServerGuardianEngine.RallyTruth.UNKNOWN,
                ServerGuardianEngine.evaluateRally(rally(0L, 0L, false, true, true, false), cfg));
        // 0.9 容差：27 万/30 万 满兵
        ServerGuardianEngine.Config ratio = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"rally_full_troops_ratio\":0.9,"
                        + "\"rally_troops_min\":1000000}}"));
        assertEquals(ServerGuardianEngine.RallyTruth.TRUE,
                ServerGuardianEngine.evaluateRally(rally(300_000L, 270_000L, true, true, true, false), ratio));
    }

    @Test
    public void parseMass_addUpdateDel_fields() {
        Map<String, Object> add = massAdd(13080L, clockSec() + 300L, 100_000L, 400_000L, true, true);
        ServerGuardianEngine.Rally r = ServerGuardianEngine.parseRally(add, clock.get());
        assertTrue(r != null);
        assertEquals(13080L, r.marchId);
        assertEquals(102L, r.type);
        assertTrue(r.hasSoldierInfo);
        assertEquals(100_000L, r.soldierMax);
        assertEquals(400_000L, r.soldierCount);
        assertTrue(r.hasJoinInfo);
        assertTrue(r.someoneFull);
        assertEquals(clockSec() + 300L, r.departAtSec);

        // mass_march 实证 type=501 也接受（威胁侧再按 100/102 关联）
        Map<String, Object> add501 = massAdd(13085L, clockSec() + 300L, 100_000L, 400_000L, true, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> march501 = (Map<String, Object>) add501.get("march");
        march501.put("type", 501L);
        assertTrue("mass type=501 也解析为集结", ServerGuardianEngine.parseRally(add501, clock.get()) != null);
        // 其它 mass（巨兽 99）忽略
        Map<String, Object> beast = massAdd(13086L, clockSec() + 300L, 1L, 1L, true, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> beastMarch = (Map<String, Object>) beast.get("march");
        beastMarch.put("type", 99L);
        assertEquals(null, ServerGuardianEngine.parseRally(beast, clock.get()));

        // update change.endtime + state=4 → cancelled
        ServerGuardianEngine.mergeRallyPatch(r, Map.of("endtime", clockSec() + 200L, "state", 4L));
        assertEquals(clockSec() + 200L, r.departAtSec);
    }

    // ---------------- 3. 等待期零请求 → 出发秒立即发射 ----------------

    @Test
    public void wait300_thenLaunchNoSpeedupWhenAhead() {
        accountWithParams(CFG);
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        e.launchEnd = nowS + 337L;   // 比敌 600s 早，无需加速
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_MASS_ADD,
                massAdd(13080L, nowS + 300L, 100_000L, 400_000L, true, true), clock.get());
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, nowS + 600L), clock.get());
        engine.awaitIdleForTest(2_000L);

        assertEquals("等待期零请求", 0, requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("rally_wait", lastLedger().getString("ev"));

        clock.addAndGet(301_000L);
        engine.resubmitRallyThreats(e);
        engine.awaitIdleForTest(3_000L);
        assertEquals("出发秒发射 1 队", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("赶得上不加速", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        assertEquals("rally_go", lastLedger().getString("ev"));
        assertTrue(lastLedger().getString("note").contains("前置加速=无"));
    }

    // ---------------- 4. 赶不上 → 连续 −25%（优先 301900） ----------------

    @Test
    public void behind_usesContinuous25Percent() {
        accountWithParams(CFG);
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        presetStock(e, 3L);
        long nowS = clock.get() / 1000L;
        e.launchEnd = nowS + 331L;   // 出发时刻 nowS+301 + 单程 30s；敌 nowS+320 → 落后
        e.speedupEnds.add(nowS + 323L);
        e.speedupEnds.add(nowS + 317L);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_MASS_ADD,
                massAdd(13081L, nowS + 300L, 100_000L, 400_000L, true, true), clock.get());
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13081L, nowS + 320L), clock.get());   // 出发 300s 后只剩 20s
        engine.awaitIdleForTest(2_000L);
        assertEquals("等待期零请求", 0, requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));

        clock.addAndGet(301_000L);
        engine.resubmitRallyThreats(e);
        engine.awaitIdleForTest(3_000L);

        assertEquals("先发射 1 队", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("连续 2 发 −25%", 2,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        int n25 = 0;
        for (int i = 0; i < e.requested.size(); i++) {
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP.equals(e.requested.get(i))) {
                Object item = e.argLog.get(i).get("itemid");
                if (item instanceof Number && ((Number) item).longValue() == 301900L) n25++;
            }
        }
        assertEquals("两发都是 301900(−25%)", 2, n25);
        assertEquals("rally_go", lastLedger().getString("ev"));
        assertTrue("账本含前置加速发数: " + lastLedger().getString("note"),
                lastLedger().getString("note").contains("前置加速=2发"));
        assertEquals(1, engine.missionCount());
    }

    // ---------------- 5. 取消 / 无法判定 ----------------

    @Test
    public void cancelled_afterMassDel_noLaunch() {
        accountWithParams(CFG);
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_MASS_ADD,
                massAdd(13082L, nowS + 300L, 100_000L, 400_000L, true, true), clock.get());
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13082L, nowS + 600L), clock.get());
        engine.awaitIdleForTest(1_000L);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_MASS_DEL, massDel(13082L), clock.get());
        engine.awaitIdleForTest(1_000L);

        assertTrue(ledgerCount("rally_cancelled") >= 1);
        assertEquals("取消不发射", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }

    @Test
    public void unknown_noMassPush_skipRallyUnknown_noLaunch() {
        accountWithParams(CFG);
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13083L, nowS + 600L), clock.get());
        engine.awaitIdleForTest(1_000L);
        assertEquals("无法判定不发射", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("rally_unknown", lastLedger().getString("ev"));
        assertTrue(lastLedger().getString("note").contains("阈值=300000"));
    }

    @Test
    public void fake_noFullTroops_noLaunch() {
        accountWithParams(CFG);
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long nowS = clock.get() / 1000L;
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPushAt(e, ServerGuardianEngine.PROTO_MASS_ADD,
                massAdd(13084L, nowS + 300L, 300_000L, 200_000L, false, true), clock.get());
        engine.onPushAt(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13084L, nowS + 600L), clock.get());
        engine.awaitIdleForTest(1_000L);
        assertEquals(0, requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("rally_fake", lastLedger().getString("ev"));
    }
}
