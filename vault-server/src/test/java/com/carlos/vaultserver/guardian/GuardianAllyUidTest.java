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
 * 批次 G：按盟友坐标反查 target_uid（消 2810）。
 *
 * <p>生产：type=102 威胁 targetUid=0 只有 x/y，launcher 三变体 extra 为空 → 2810。
 * 本类覆盖：坐标命中盟友 → uid 解析并走 target_uid 变体；未命中 → skip_no_ally 零请求；
 * allies 空 → skip_allies_unknown（≤1 条/分钟）；require_ally=false/env off → 旧行为；
 * 多 uid 同坐标/ally_uid 优先；allies 更新时间诊断。
 */
public class GuardianAllyUidTest {

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
        System.clearProperty("VAULT_GUARDIAN_REQUIRE_ALLY");
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
        boolean succeedOnEmptyVariant;

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
            if (ServerGuardianEngine.PROTO_MARCH_DETAIL.equals(proto)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", 0);
                c.put("marchs", new ArrayList<>());
                GameMessage gm = new GameMessage();
                gm.setContent(c);
                return gm;
            }
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH.equals(proto)) {
                Object extra = args == null ? null : args.get("extra");
                boolean hasUid = extra instanceof Map
                        && ((Map<?, ?>) extra).get("target_uid") instanceof Number;
                if (hasUid || succeedOnEmptyVariant) {
                    return launchOk(555L, clockStatic + 60L);
                }
                return msg(2810);
            }
            return responses.get(proto);
        }
        @Override public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
    }

    /** 测试里给 launchOk 一个固定 endtime（避免静态上下文引用实例 clock）。 */
    private static volatile long clockStatic = System.currentTimeMillis() / 1000L;

    private static Map<String, Object> memberPos(long uid, int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("uid", uid);
        p.put("x", (long) x);
        p.put("y", (long) y);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("member_pos", p);
        return c;
    }

    private static Map<String, Object> memberList(long[][] uidsAndXy) {
        Map<String, Object> list = new LinkedHashMap<>();
        for (int i = 0; i < uidsAndXy.length; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("uid", uidsAndXy[i][0]);
            p.put("x", uidsAndXy[i][1]);
            p.put("y", uidsAndXy[i][2]);
            list.put(i + ".0", p);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("member_list", list);
        return c;
    }

    private static Map<String, Object> attackPush(long marchId, long endSec, int x, int y) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", marchId);
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

    private static String liveCfg(boolean requireAlly) {
        return "{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"require_ally\":" + requireAlly + ","
                + "\"rally_enabled\":false,"
                + "\"formation\":{\"hero_ids\":[1]}}}";
    }

    private void presetEnv(TestEngine e) {
        presetQueue(e, 5);
        presetSoldiers(e);
        clockStatic = clock.get() / 1000L;
    }

    // ---------------- 1. 坐标命中 → uid + target_uid 变体 ----------------

    @Test
    public void coordinateMatch_resolvesUid_andLaunchesTargetUidVariant() {
        accountWithParams(liveCfg(true));
        TestEngine e = new TestEngine();
        presetEnv(e);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO,
                memberPos(2001L, 524, 420));
        engine.awaitIdleForTest(1_000L);
        assertTrue(engine.describe().contains("allies=1"));
        assertTrue(engine.describe().contains("alliesUpdatedAgoMs=0"));
        assertTrue(engine.alliesUpdatedAt() > 0L);

        long end = clock.get() / 1000L + 120L;
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, end, 524, 420));
        engine.awaitIdleForTest(3_000L);

        assertEquals("坐标反查 uid 后发射成功", 1, engine.missionCount());
        assertEquals("先空 extra(2810) 再 target_uid 变体", 2,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        JSONObject last = lastLedger();
        assertEquals("launched", last.getString("ev"));
        assertEquals("账本 targetUid=反查 uid", 2001L, last.getLong("targetUid"));
        assertEquals("命中 target_uid 变体", 1, last.getInt("variant"));
        assertTrue("note 带盟友 uid: " + last.getString("note"),
                last.getString("note").contains("盟友uid=2001"));
    }

    // ---------------- 2. 未命中 → skip_no_ally 零请求 ----------------

    @Test
    public void noMatch_skipNoAlly_zeroRequests() {
        accountWithParams(liveCfg(true));
        TestEngine e = new TestEngine();
        presetEnv(e);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO,
                memberPos(2001L, 600, 600));
        engine.awaitIdleForTest(1_000L);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13081L, clock.get() / 1000L + 120L, 500, 500));
        engine.awaitIdleForTest(3_000L);

        assertEquals("未匹配盟友不发射", 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("skip_no_ally", lastLedger().getString("ev"));
        assertEquals(0L, lastLedger().getLong("targetUid"));
    }

    // ---------------- 3. require_ally=false → 旧行为 ----------------

    @Test
    public void requireAllyFalse_oldBehavior() {
        accountWithParams(liveCfg(false));
        TestEngine e = new TestEngine();
        e.succeedOnEmptyVariant = true;
        presetEnv(e);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13082L, clock.get() / 1000L + 120L, 500, 500));
        engine.awaitIdleForTest(3_000L);

        assertEquals("require_ally=false → 坐标直发", 1, engine.missionCount());
        assertEquals(1, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals("launched", lastLedger().getString("ev"));
        assertEquals(0L, lastLedger().getLong("targetUid"));
    }

    @Test
    public void envRequireAllyOff_oldBehavior() {
        accountWithParams(liveCfg(true));
        TestEngine e = new TestEngine();
        e.succeedOnEmptyVariant = true;
        presetEnv(e);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        try {
            System.setProperty("VAULT_GUARDIAN_REQUIRE_ALLY", "off");
            engine.reloadConfig(true);
            engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                    attackPush(13083L, clock.get() / 1000L + 120L, 500, 500));
            engine.awaitIdleForTest(3_000L);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_REQUIRE_ALLY");
        }
        assertEquals("env off → 坐标直发", 1, engine.missionCount());
        assertEquals(1, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }

    // ---------------- 4. allies 空 → skip_allies_unknown（≤1 条/分钟） ----------------

    @Test
    public void alliesEmpty_skipUnknown_throttled() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"require_ally\":true,"
                + "\"rally_enabled\":false,"
                + "\"ally_use_watchpoint\":false,\"ally_use_protect_rect\":false},"
                + "\"strategy\":{\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetEnv(e);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long end = clock.get() / 1000L + 120L;
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13084L, end, 524, 420));
        engine.awaitIdleForTest(2_000L);
        assertEquals("无坐标表不发射", 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        // 2026-10-31：登录缓存可能补到盟友坐标/观察点兜底 → 记录“未命中”即可（都不得发射）
        String ev = ledger().toString();
        assertTrue("应记录未命中的保守跳过：" + ev,
                ev.contains("skip_allies_unknown") || ev.contains("skip_no_ally"));
        assertEquals("仍不得发射", 0, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));

        // 1 分钟内同威胁 update：账本节流不新增（同一事件计数不增长）
        int before = ledger().length();
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13084L, end, 524, 420));
        engine.awaitIdleForTest(2_000L);
        assertTrue("同一威胁 ≤1 条/分钟（事件数不增）", ledger().length() <= before);

        clock.addAndGet(61_000L);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13084L, end, 524, 420));
        engine.awaitIdleForTest(2_000L);
        assertEquals("超过 1 分钟再记 1 条", 2, ledgerCount("skip_allies_unknown"));
    }

    // ---------------- 5. 多 uid 同坐标 / ally_uid 优先 / 容差 ----------------

    @Test
    public void duplicateCoordinates_firstOrConfiguredAlly() {
        accountWithParams(liveCfg(true));
        TestEngine e = new TestEngine();
        presetEnv(e);
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO,
                memberList(new long[][]{{2001L, 524, 420}, {2002L, 524, 420}}));
        engine.awaitIdleForTest(1_000L);

        long uid = engine.allyUidByPoint(524, 420);
        assertTrue("同坐标取第一个（2001/2002 之一）: " + uid, uid == 2001L || uid == 2002L);
        assertEquals("坐标容差 0：邻近点不匹配", 0L, engine.allyUidByPoint(525, 420));

        // 配置 ally_uid=2002 → 优先返回它
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"require_ally\":true,\"ally_uid\":2002,"
                + "\"formation\":{\"hero_ids\":[1]}}}");
        engine.reloadConfig(true);
        assertEquals(2002L, engine.allyUidByPoint(524, 420));
    }

    // ---------------- 6. 配置默认与 describe ----------------

    @Test
    public void config_requireAllyDefaultAndDescribe() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertTrue("require_ally 默认 true", def.requireAlly);
        assertTrue(def.describe().contains("require_ally=true"));

        ServerGuardianEngine.Config off = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"require_ally\":false}}"));
        assertFalse(off.requireAlly);

        accountWithParams(liveCfg(true));
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        assertTrue(engine.describe().contains("allies=0"));
        assertTrue(engine.describe().contains("alliesUpdatedAgoMs=-1"));
    }

    /** 2026-10-31：endpoint 与成员坐标存在 ±1 取整差时，默认容差 1 命中。 */
    @Test
    public void toleranceOne_matchesNeighborCoordinate() {
        com.carlos.vaultserver.VaultStore.Account a = new com.carlos.vaultserver.VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put(ServerGuardianEngine.FN_ID,
                "{\"sub\":{\"enabled\":true,\"ally_match_tolerance\":1}}");
        a.roleParams.put(String.valueOf(SID), params);
        java.util.List<com.carlos.vaultserver.VaultStore.Account> in = new java.util.ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
        ServerGuardianEngine g = new ServerGuardianEngine(FPID, SID, state, vault);
        try {
            java.util.List<Object> members = new java.util.ArrayList<>();
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("uid", 2001L);
            m.put("x", 500L);
            m.put("y", 600L);
            members.add(m);
            java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
            content.put("member_list", members);
            g.onPush(null, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, content);
            assertEquals("坐标差 1 格默认容差 1 命中", 2001L, g.allyUidByPoint(501, 600));
            assertEquals("容差内另一方向命中", 2001L, g.allyUidByPoint(500, 599));
            assertEquals("容差 1 时差 2 格不匹配", 0L, g.allyUidByPoint(502, 600));
        } finally {
            g.shutdown();
        }
    }
}
