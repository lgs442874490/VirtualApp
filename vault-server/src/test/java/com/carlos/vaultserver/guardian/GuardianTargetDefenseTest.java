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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 D：守护"按敌情目标驻防"（用户 2026-10-31 口径）。
 *
 * <p>覆盖：生产真实形状 type=102（targetUid=0 + x/y）命中并使用推送坐标；attack_types 默认 {2,102}
 * 可配置；timing 默认关（立即发射）；max_concurrent 默认 3 / clamp 1..6 / 队列空闲夹取；
 * 三目标按 ETA 升序发射；ETA 太近/-30s 补驻防（late_reinforce）/-90s 拒绝；目标=自城 skip_self。
 */
public class GuardianTargetDefenseTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;
    private static final long ENEMY_UID = 49422287L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;
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
        ServerGuardianEngine e = new ServerGuardianEngine(FPID, SID, state, vault);
        engines.add(e);
        return e;
    }

    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        final Map<String, GameMessage> responses = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<Map<String, Object>> argLog = new ArrayList<>();
        /** 动态队列占用：返回 detail 里在途行军条数（模拟我方已发射的任务）。 */
        java.util.function.IntSupplier activeMarchesForDetail;

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
            if (ServerGuardianEngine.PROTO_MARCH_DETAIL.equals(proto) && activeMarchesForDetail != null) {
                int n = Math.max(0, activeMarchesForDetail.getAsInt());
                List<Map<String, Object>> marchs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", 9000L + i);
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

    private static Map<String, Object> march(long id, long targetUid, long type, long endSec,
                                             int x, int y) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", ENEMY_UID);
        m.put("type", type);
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

    private static Map<String, Object> push(Map<String, Object>... marches) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(marches));
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

    private static void presetQueue(TestEngine e, int capacity) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", (long) capacity);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        e.notifies.put(ServerGuardianEngine.PROTO_WORLD_PLAYER_INIT, wpi);
        e.activeMarchesForDetail = () -> countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH);
    }

    private static void presetCity(TestEngine e, int x, int y) {
        Map<String, Object> current = e.notifies.get(ServerGuardianEngine.PROTO_WORLD_PLAYER_INIT);
        Map<String, Object> wpi = current == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("x", (long) x);
        cp.put("y", (long) y);
        wpi.put("city_point", cp);
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

    private static int countProto(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) if (proto.equals(p)) n++;
        return n;
    }

    private static List<Map<String, Object>> launches(TestEngine e) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < e.requested.size(); i++) {
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH.equals(e.requested.get(i))) {
                out.add(e.argLog.get(i));
            }
        }
        return out;
    }

    private static long endpointX(Map<String, Object> args) {
        Object ep = args == null ? null : args.get("endpoint");
        if (ep instanceof Map) {
            Object x = ((Map<?, ?>) ep).get("x");
            return x instanceof Number ? ((Number) x).longValue() : 0L;
        }
        return 0L;
    }

    /** 本类只验证发射/目标/并发语义；批次 D 起 catch_up 默认开，显式关闭避免追赶账本干扰断言。 */
    private static String liveCfg(int maxConcurrent) {
        return "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":false},"
                + "\"strategy\":{\"max_concurrent\":" + maxConcurrent + ","
                + "\"require_ally\":false,"
                + "\"formation\":{\"hero_ids\":[1]}}}";
    }

    // ---------------- 1. 生产真实威胁形状（type=102） ----------------

    @Test
    public void productionType102_hits_andUsesPushCoordinates() {
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertTrue("默认攻击类型含 2/102", cfg.attackTypes.contains(2L)
                && cfg.attackTypes.contains(102L));
        ServerGuardianEngine engine = engine();
        long now = System.currentTimeMillis();
        long end = now / 1000L + 60L;
        int[][] coords = {{524, 420}, {530, 430}, {540, 440}};
        for (int i = 0; i < coords.length; i++) {
            ServerGuardianEngine.Threat t = new ServerGuardianEngine.Threat(
                    9100L + i, ENEMY_UID, 102L, 0L, coords[i][0], coords[i][1], true, end, now);
            assertTrue("生产形状 type=102/targetUid=0/x-y 必须命中: " + i,
                    ServerGuardianEngine.decide(t, cfg, now).hit);
            int[] pt = engine.resolveTargetPoint(t, cfg);
            assertNotNull(pt);
            assertEquals("驻防坐标用推送 x", coords[i][0], pt[0]);
            assertEquals("驻防坐标用推送 y", coords[i][1], pt[1]);
        }
        // 非攻击类型仍拒绝
        assertFalse(ServerGuardianEngine.decide(new ServerGuardianEngine.Threat(
                9200L, ENEMY_UID, 1L, 0L, 524, 420, true, end, now), cfg, now).hit);
        assertFalse(ServerGuardianEngine.decide(new ServerGuardianEngine.Threat(
                9201L, ENEMY_UID, 103L, 0L, 524, 420, true, end, now), cfg, now).hit);
    }

    @Test
    public void attackTypes_configurableAndFallback() {
        long now = System.currentTimeMillis();
        long end = now / 1000L + 60L;
        ServerGuardianEngine.Threat t102 = new ServerGuardianEngine.Threat(
                1L, ENEMY_UID, 102L, 0L, 500, 500, true, end, now);
        ServerGuardianEngine.Threat t2 = new ServerGuardianEngine.Threat(
                2L, ENEMY_UID, 2L, 0L, 500, 500, true, end, now);

        ServerGuardianEngine.Config only2 = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true},"
                        + "\"strategy\":{\"attack_types\":\"2\"}}"));
        assertTrue(only2.attackTypes.contains(2L));
        assertFalse("attack_types=2 时 102 被拒", ServerGuardianEngine.decide(t102, only2, now).hit);
        assertTrue(ServerGuardianEngine.decide(t2, only2, now).hit);

        ServerGuardianEngine.Config arr = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"attack_types\":[2,102]}}"));
        assertTrue(arr.attackTypes.contains(2L) && arr.attackTypes.contains(102L));

        ServerGuardianEngine.Config comma = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"attack_types\":\"2, 102\"}}"));
        assertTrue(comma.attackTypes.contains(102L));

        ServerGuardianEngine.Config bad = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"attack_types\":\"abc\"}}"));
        assertEquals("非法回落默认", ServerGuardianEngine.DEFAULT_ATTACK_TYPES, bad.attackTypes);
        ServerGuardianEngine.Config zero = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"attack_types\":[0,-1]}}"));
        assertEquals("全非法回落默认", ServerGuardianEngine.DEFAULT_ATTACK_TYPES, zero.attackTypes);
    }

    // ---------------- 2. 默认立即发射（timing=false） ----------------

    @Test
    public void timingDefaultOff_immediateLaunch() {
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertFalse("批次 D：卡秒默认关", cfg.timingEnabled);
        assertEquals(3, cfg.maxConcurrent);

        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"require_ally\":false,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, 0L, 102L, now + 120L, 524, 420)));
        engine.awaitIdleForTest(3_000L);

        assertTrue("timing 默认关 → 立即发射 type=102",
                e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("不应注册卡秒任务", 0, engine.fireTaskCount());
        assertEquals("launched", lastLedger().getString("ev"));
        assertEquals(0, ledgerCount("timing_wait"));
    }

    // ---------------- 3. 多目标并发 + 队列夹取 + ETA 排序 ----------------

    @Test
    public void maxConcurrent_default3_clamp6_andQueueClamp() {
        assertEquals(3, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}")).maxConcurrent);
        assertEquals(6, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":9}}")).maxConcurrent);
        assertEquals(1, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":0}}")).maxConcurrent);

        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueue(e, 2);   // 队列只有 2 个空位 → 最多发 2 队
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, 0L, 102L, now + 120L, 524, 420),
                        march(13081L, 0L, 102L, now + 121L, 530, 430),
                        march(13082L, 0L, 102L, now + 122L, 540, 440)));
        engine.awaitIdleForTest(3_000L);

        assertEquals("max_concurrent=3 但队列只 2 空位 → 发 2 队",
                2, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(2, engine.missionCount());
        assertTrue("第三目标 skip_queue", ledgerCount("skip_queue") >= 1);
    }

    @Test
    public void threeTargets_etaAscending() {
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        // 故意乱序推送：远→中→近；应按 endSec 升序（近、中、远）发射
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, 0L, 102L, now + 200L, 603, 440),
                        march(13081L, 0L, 102L, now + 150L, 602, 430),
                        march(13082L, 0L, 102L, now + 100L, 601, 420)));
        engine.awaitIdleForTest(3_000L);

        List<Map<String, Object>> ls = launches(e);
        assertEquals("三目标同时被攻 → 三队驻防", 3, ls.size());
        assertEquals(601L, endpointX(ls.get(0)));
        assertEquals(602L, endpointX(ls.get(1)));
        assertEquals(603L, endpointX(ls.get(2)));
    }

    // ---------------- 4. 晚到也发（late_reinforce） ----------------

    @Test
    public void etaTooClose_lateReinforceWithin60s_rejectedAfter60s() {
        long now = System.currentTimeMillis();
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        ServerGuardianEngine.Threat late30 = new ServerGuardianEngine.Threat(
                1L, ENEMY_UID, 102L, 0L, 500, 500, true, now / 1000L - 30, now);
        ServerGuardianEngine.Threat late90 = new ServerGuardianEngine.Threat(
                2L, ENEMY_UID, 102L, 0L, 500, 500, true, now / 1000L - 90, now);
        assertTrue("已落地 30s：仍补驻防", ServerGuardianEngine.decide(late30, cfg, now).hit);
        assertFalse("已落地 90s：不再补", ServerGuardianEngine.decide(late90, cfg, now).hit);

        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetSoldiers(e);
        long nowS = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, nowS + 100L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, 0L, 102L, nowS - 30L, 524, 420)));
        engine.awaitIdleForTest(3_000L);

        assertTrue("晚到仍发射", e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        JSONObject last = lastLedger();
        assertEquals("launched", last.getString("ev"));
        assertTrue("账本标记 late_reinforce: " + last.getString("note"),
                last.getString("note").contains("late_reinforce"));
    }

    // ---------------- 5. 自身城市跳过 ----------------

    @Test
    public void selfCity_skipped() {
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueue(e, 5);
        presetCity(e, 500, 500);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, 0L, 102L, now + 120L, 500, 500)));
        engine.awaitIdleForTest(3_000L);
        assertTrue("目标=自城不发射", e.requested.isEmpty());
        assertEquals("skip_self", lastLedger().getString("ev"));

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13081L, 0L, 102L, now + 121L, 501, 500)));
        engine.awaitIdleForTest(3_000L);
        assertTrue("非自城照常发射",
                e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }
}
