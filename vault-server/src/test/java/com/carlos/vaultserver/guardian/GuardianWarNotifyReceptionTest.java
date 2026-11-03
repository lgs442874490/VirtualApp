package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.watchpoint.WatchPointEngine;
import com.carlos.vaultserver.watchpoint.WatchPointStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-04：守护盟友接入「战争通知接收」。
 *
 * <p>用户口径：驻防必须和企微战争通知同源同口径——明确知道「打的是自己盟友」才驻防；
 * 巨兽/野外集结、同联盟互打、侦查、加入集结、返程一律不算；目标城不属于本盟也不守。
 *
 * <p>覆盖：{@code notify_player_march_add} 真攻本盟城 → 发射；同联盟 → 跳过；侦查 → 跳过；
 * 外盟城 → skip_not_own_ally；我方自己的队列 → 忽略；目标数 &gt; 6 队列 → 前 6 先发 + 其余排队，
 * 队列释放后补发。
 */
public class GuardianWarNotifyReceptionTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;
    private static final long ENEMY_UID = 49422287L;
    private static final long ALLY_UID = 2002L;
    private static final long SELF_AID = 777L;
    private static final long ENEMY_AID = 888L;
    private static final long OTHER_AID = 999L;

    private static final int OWN_X = 100;
    private static final int OWN_Y = 100;
    private static final int ALLY_X = 120;
    private static final int ALLY_Y = 120;
    private static final int ENEMY_X = 500;
    private static final int ENEMY_Y = 500;

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
        WatchPointEngine.install(null);
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
        ServerGuardianEngine e = new ServerGuardianEngine(FPID, SID, state, vault);
        engines.add(e);
        return e;
    }

    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        final Map<String, GameMessage> responses = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();

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
            return responses.get(proto);
        }
        @Override public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
    }

    private static Map<String, Object> point(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", (long) x);
        p.put("y", (long) y);
        return p;
    }

    /** notify_player_march_add 实测形状 {march:{id,owner,type,state,startpoint,endpoint,endtime}}。 */
    private static Map<String, Object> playerMarch(long id, long owner, long type,
                                                   int sx, int sy, int ex, int ey,
                                                   long targetUid, long endSec) {
        Map<String, Object> squad = new LinkedHashMap<>();
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", new LinkedHashMap<>(Map.of("id", 11000L, "count", 1200L)));
        squad.put("soldier", soldier);
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("type", type);
        m.put("state", 2L);
        m.put("startpoint", point(sx, sy));
        m.put("endpoint", point(ex, ey));
        m.put("endtime", endSec);
        m.put("atk_city", atk);
        m.put("squad_list", squad);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("march", m);
        return c;
    }

    private static void presetWorld(TestEngine e, int capacity) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", (long) capacity);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        wpi.put("city_point", point(OWN_X, OWN_Y));
        e.notifies.put("notify_world_player_init", wpi);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", 0);
        detail.put("marchs", new ArrayList<>());
        GameMessage m = new GameMessage();
        m.setContent(detail);
        e.responses.put(ServerGuardianEngine.PROTO_MARCH_DETAIL, m);
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

    private static Map<String, Object> delPush(long id) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(Map.of("id", id)));
        return c;
    }

    /** 观察点整图城缓存：本城（aid=777）、盟友城（aid=777）、敌城（aid=888）。 */
    private void seedCities() {
        WatchPointStore wps = new WatchPointStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        WatchPointEngine wpe = new WatchPointEngine(null, wps, null, null);
        wpe.seedCityForTest(OWN_UID, SELF_AID, OWN_X, OWN_Y, "[本]城");
        wpe.seedCityForTest(ALLY_UID, SELF_AID, ALLY_X, ALLY_Y, "[友]城");
        wpe.seedCityForTest(ENEMY_UID, ENEMY_AID, ENEMY_X, ENEMY_Y, "[敌]城");
        WatchPointEngine.install(wpe);
    }

    private int ledgerCount(String ev) {
        String raw = state.getString(ServerGuardianEngine.ledgerKey(FPID, SID));
        if (raw == null) return 0;
        JSONArray arr = new JSONArray(raw);
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            if (ev.equals(arr.getJSONObject(i).optString("ev"))) n++;
        }
        return n;
    }

    private String ledgerText() {
        String raw = state.getString(ServerGuardianEngine.ledgerKey(FPID, SID));
        return raw == null ? "" : raw;
    }

    private int countProto(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) if (proto.equals(p)) n++;
        return n;
    }

    private static String liveCfg(int maxConcurrent, int retryIntervalMs) {
        return liveCfg(maxConcurrent, retryIntervalMs, true, true);
    }

    private static String liveCfg(int maxConcurrent, int retryIntervalMs, boolean rallyEnabled) {
        return liveCfg(maxConcurrent, retryIntervalMs, rallyEnabled, true);
    }

    private static String liveCfg(int maxConcurrent, int retryIntervalMs, boolean rallyEnabled,
                                  boolean requireAlly) {
        return "{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"timing\":false,\"max_concurrent\":" + maxConcurrent + ","
                + "\"queue_retry_interval_ms\":" + retryIntervalMs + ",\"queue_retry_max\":50,"
                + "\"rally_enabled\":" + rallyEnabled + ",\"require_ally\":" + requireAlly + ","
                + "\"formation\":{\"hero_ids\":[1]}}}";
    }

    // ---------------- 接收口径 ----------------

    @Test
    public void playerMarch_attackOnOwnAlly_launchesGarrison() {
        accountWithParams(liveCfg(6, 5_000));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                playerMarch(13080L, ENEMY_UID, 2L, ENEMY_X, ENEMY_Y,
                        ALLY_X, ALLY_Y, ALLY_UID, now + 120L));
        engine.awaitIdleForTest(3_000L);

        assertEquals("打自己盟友 → 必须发射驻防", 1,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(1, engine.missionCount());
        assertTrue("账本应有 launched", ledgerCount("launched") >= 1);
    }

    @Test
    public void playerMarch_sameAllianceTarget_skipped() {
        accountWithParams(liveCfg(6, 5_000));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        // 发起城 aid=777 与目标城 aid=777 相同 → 联盟内部帧，不是敌情
        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                playerMarch(13081L, OWN_UID, 2L, OWN_X, OWN_Y,
                        ALLY_X, ALLY_Y, ALLY_UID, now + 120L));
        engine.awaitIdleForTest(3_000L);

        assertEquals("同联盟互打不驻防", 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(0, engine.missionCount());
    }

    @Test
    public void playerMarch_scout_skipped() {
        accountWithParams(liveCfg(6, 5_000));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                playerMarch(13082L, ENEMY_UID, 1L, ENEMY_X, ENEMY_Y,
                        ALLY_X, ALLY_Y, ALLY_UID, now + 120L));
        engine.awaitIdleForTest(3_000L);

        assertEquals("侦查走观察点告警，不驻防", 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }

    @Test
    public void playerMarch_foreignAllianceCity_skipped() {
        accountWithParams(liveCfg(6, 5_000));
        seedCities();
        // 目标城属于别的联盟（aid=999），发起城 aid=888 → 不是我们该守的盟友
        WatchPointStore wps = new WatchPointStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        WatchPointEngine wpe = new WatchPointEngine(null, wps, null, null);
        wpe.seedCityForTest(OWN_UID, SELF_AID, OWN_X, OWN_Y, "[本]城");
        wpe.seedCityForTest(ALLY_UID, OTHER_AID, ALLY_X, ALLY_Y, "[外]城");
        wpe.seedCityForTest(ENEMY_UID, ENEMY_AID, ENEMY_X, ENEMY_Y, "[敌]城");
        WatchPointEngine.install(wpe);

        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                playerMarch(13083L, ENEMY_UID, 2L, ENEMY_X, ENEMY_Y,
                        ALLY_X, ALLY_Y, ALLY_UID, now + 120L));
        engine.awaitIdleForTest(3_000L);

        assertEquals("外盟城不驻防", 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertTrue("应记 skip_not_own_ally：" + ledgerText(),
                ledgerCount("skip_not_own_ally") >= 1);
    }

    @Test
    public void playerMarch_ownMarch_ignored() {
        accountWithParams(liveCfg(6, 5_000));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        // owner=本角色 → 我方队列，不是威胁
        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                playerMarch(13084L, OWN_UID, 2L, OWN_X, OWN_Y,
                        ALLY_X, ALLY_Y, ALLY_UID, now + 120L));
        engine.awaitIdleForTest(3_000L);

        assertEquals(0, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }

    @Test
    public void rallyOnAllyCoordOutsideCityCache_stillLaunches() {
        // 2026-11-04 真机：观察点城缓存只覆盖扫描区域，没扫到的盟友城 cityAt=null。
        // 只要终点在盟友坐标表里命中，就必须按真实威胁驻防（不得当巨兽/野外集结丢掉）。
        // 本用例只验证「终点确认是盟友 → 不被 PvE 过滤丢弃」，集结真伪链另行覆盖
        accountWithParams(liveCfg(6, 5_000, false));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        // 盟友坐标表（登录推送形状）：uid=2003 在 (600,600)，该点不在城缓存里
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("uid", 2003L);
        member.put("x", 600L);
        member.put("y", 600L);
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("member_pos", List.of(member));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, pos);
        engine.awaitIdleForTest(1_000L);

        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 13300L);
        m.put("owner", ENEMY_UID);
        m.put("type", 102L);
        m.put("state", 2L);
        m.put("endtime", now + 120L);
        m.put("endpoint", point(600, 600));
        m.put("atk_city", atk);
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("to_marchs", List.of(m));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push);
        engine.awaitIdleForTest(3_000L);

        assertEquals("盟友坐标命中 → 集结也要驻防：" + ledgerText(), 1,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(1, engine.missionCount());
    }

    @Test
    public void crossAllianceTarget_neverGarrisoned() {
        // 【硬性规则 2026-11-04】驻防只给本盟；跨盟只能进攻，绝不驻防。
        // 炎黄(ZON) 打隆科多(KFC) 这种跨盟目标必须被 skip_not_own_ally 拦下，且没有任何发射。
        accountWithParams(liveCfg(6, 5_000, false));
        WatchPointStore wps = new WatchPointStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        WatchPointEngine wpe = new WatchPointEngine(null, wps, null, null);
        wpe.seedCityForTest(OWN_UID, SELF_AID, OWN_X, OWN_Y, "[本]城");
        wpe.seedCityForTest(ALLY_UID, OTHER_AID, ALLY_X, ALLY_Y, "[外盟]城");
        wpe.seedCityForTest(ENEMY_UID, ENEMY_AID, ENEMY_X, ENEMY_Y, "[敌]城");
        WatchPointEngine.install(wpe);

        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                playerMarch(13401L, ENEMY_UID, 2L, ENEMY_X, ENEMY_Y,
                        ALLY_X, ALLY_Y, ALLY_UID, now + 120L));
        engine.awaitIdleForTest(3_000L);

        assertEquals("跨盟目标绝不驻防：" + ledgerText(), 0,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertTrue("应记 skip_not_own_ally：" + ledgerText(),
                ledgerCount("skip_not_own_ally") >= 1);
    }

    // ---------------- 目标数 > 队列数 ----------------

    @Test
    public void sevenTargets_sixQueues_firstSixLaunchAndSeventhQueued() {
        accountWithParams(liveCfg(6, 5_000, true, false));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        for (int i = 0; i < 7; i++) {
            engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                    playerMarch(13100L + i, ENEMY_UID, 2L, ENEMY_X, ENEMY_Y,
                            700 + i, 700 + i, 3000L + i, now + 100L + i));
        }
        engine.awaitIdleForTest(3_000L);

        assertEquals("6 个队列 → 只发 6 队", 6,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(6, engine.missionCount());
        assertTrue("第 7 个目标应记 skip_busy 并排队：" + ledgerText(),
                ledgerCount("skip_busy") >= 1);
        assertTrue("账本应标出排队序号：" + ledgerText(),
                ledgerText().contains("排队第 7"));
    }

    @Test
    public void seventhTarget_launchesAfterQueueFreed() throws Exception {
        accountWithParams(liveCfg(6, 1_000, true, false));
        seedCities();
        TestEngine e = new TestEngine();
        presetWorld(e, 5);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        for (int i = 0; i < 7; i++) {
            engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_ADD,
                    playerMarch(13200L + i, ENEMY_UID, 2L, ENEMY_X, ENEMY_Y,
                            800 + i, 800 + i, 4000L + i, now + 200L + i));
        }
        engine.awaitIdleForTest(3_000L);
        assertEquals(6, engine.missionCount());

        // 第一路敌情召回 → 撤防腾出并发额度 → 排队的第 7 个目标应在重试周期内补发
        engine.onPush(e, ServerGuardianEngine.PROTO_PLAYER_MARCH_DEL, delPush(13200L));
        engine.awaitIdleForTest(3_000L);
        // 排队重试最小周期 1s（配置 clamp）→ 等一个重试周期后再收口
        Thread.sleep(1_800L);
        engine.awaitIdleForTest(3_000L);

        assertEquals("额度释放后第 7 个目标应补发：" + ledgerText(), 7,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }
}
