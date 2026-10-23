package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 守护盟友 P0 引擎单测（批次9；设计 {@code design/守护盟友-服务端化设计与分期-20261023.md} §3/§4.1）：
 * <ul>
 *   <li>配置解析：默认 <b>enabled=false / dry_run=true</b>，roleParams `guardian` sub/strategy 兼容、非法值 fail-closed；</li>
 *   <li>推送解析：目标系列/观察哨/包装层、del ids、盟友坐标、队列容量与占用（防御式，不猜协议）；</li>
 *   <li>规则：type=2 进攻 + ETA 窗口 + 单盟友/目标过滤；侦查(type=1)/过期/过近/不匹配拒绝；</li>
 *   <li>行为：默认关→零副作用；dry_run→只记录不请求；开启→type=5 发射并记账；
 *       敌召回→撤防；离线→跳过；每威胁最多 1 次动作。</li>
 * </ul>
 */
public class ServerGuardianEngineTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;
    private static final long ENEMY_UID = 49422287L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

    /** 最小引擎替身：notify/request 全预置，不连网。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<Map<String, Object>> argLog = new ArrayList<>();
        boolean online = true;
        long uid = OWN_UID;
        long kid = SID;

        TestEngine() {
            super(null);
        }

        @Override
        public String sessionState() {
            return online ? STATE_ONLINE : STATE_TAKEOVER;
        }

        @Override
        public long lastUid() {
            return uid;
        }

        @Override
        public long lastServerId() {
            return kid;
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
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

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            return request(proto, args, 15);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            requested.add(proto);
            argLog.add(args);
            return responses.get(proto);
        }
    }

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
        vault.load();
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
        return new ServerGuardianEngine(FPID, SID, state, vault);
    }

    private static Map<String, Object> point(long x, long y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    /** 目标系列推送（to_marchs[]；实证形状见协议实证 §2.2/§2.5）。 */
    private static Map<String, Object> attackPush(long marchId, long owner, long targetUid,
                                                  long endtimeSec, int x, int y) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", owner);
        march.put("type", 2L);
        march.put("target_type", 1L);
        march.put("endtime", endtimeSec);
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

    private static GameMessage msg(int code) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private static GameMessage okMsg(int code) {
        return msg(code);
    }

    private static GameMessage launchOk(long marchId, long endtimeSec) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", marchId);
        info.put("endtime", endtimeSec);
        info.put("state", 2L);
        info.put("type", 5L);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        c.put("march_info", info);
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    /** 让 formationOf 可用的最小兵力推送（Exec.soldiers 口径：soldier{id.0:{id,count}}）。 */
    private static void presetSoldiers(TestEngine e) {
        Map<String, Object> soldier = new LinkedHashMap<>();
        for (long id : new long[]{11000L, 21000L, 31000L}) {
            soldier.put(id + ".0", new LinkedHashMap<>(Map.of("id", id, "count", 1000L)));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("soldier", soldier);
        e.notifies.put("notify_soldier_init", content);
    }

    /** 队列容量/占用可查：capacity=5、当前 0 条行军。 */
    private static void presetQueueOk(TestEngine e) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", 5L);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        e.notifies.put("notify_world_player_init", wpi);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", 0);
        detail.put("marchs", new ArrayList<>());
        e.responses.put(ServerGuardianEngine.PROTO_MARCH_DETAIL, content(detail));
    }

    private static GameMessage content(Map<String, Object> c) {
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private String ledgerRaw() {
        return state.getString(ServerGuardianEngine.ledgerKey(FPID, SID));
    }

    private JSONObject lastLedger() {
        String raw = ledgerRaw();
        assertNotNull("缺账本键", raw);
        JSONArray arr = new JSONArray(raw);
        assertTrue(arr.length() > 0);
        return arr.getJSONObject(0);
    }

    // ======================= 1. 配置（默认关闭 + dry_run） =======================

    @Test
    public void parseConfig_defaultsDisabledAndDryRun() {
        ServerGuardianEngine.Config c = ServerGuardianEngine.parseConfig(null);
        assertFalse("默认必须关闭（宁可不发）", c.enabled);
        assertTrue("默认 dry_run=true（只记录不发射）", c.dryRun);
        assertTrue("默认允许敌召回后撤防", c.recall);
        assertFalse("speedup P1 预留默认 false", c.speedupEnabled);
        assertEquals(0L, c.allyUid);
        assertEquals(0L, c.targetUid);
        assertEquals(1, c.squadIndex);
        assertFalse(c.hasTargetPoint());
        assertEquals(ServerGuardianEngine.DEFAULT_D_SEC, c.dSec);
    }

    @Test
    public void parseConfig_subStrategyFieldsAndLegacyIntercept() {
        JSONObject p = new JSONObject(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"recall\":false},"
                        + "\"strategy\":{\"ally_uid\":1001,\"target_uid\":1002,"
                        + "\"target_point\":{\"x\":674,\"y\":684},"
                        + "\"formation\":{\"squad_index\":2,\"hero_ids\":[11,22]},"
                        + "\"d_sec\":40,\"recall_distance\":12}}");
        ServerGuardianEngine.Config c = ServerGuardianEngine.parseConfig(p);
        assertTrue(c.enabled);
        assertFalse(c.dryRun);
        assertFalse(c.recall);
        assertEquals(1001L, c.allyUid);
        assertEquals(1002L, c.targetUid);
        assertEquals(674, c.targetX);
        assertEquals(684, c.targetY);
        assertEquals(2, c.squadIndex);
        assertEquals(List.of(11L, 22L), c.heroIds);
        assertEquals(40L, c.dSec);
        assertEquals(12d, c.recallDistance, 0.001d);

        // 兼容 App FnDetail 既有 intercept 开关（作为 enabled 的别名；dry_run 仍默认 true → 安全）
        ServerGuardianEngine.Config legacy = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"intercept\":true}}"));
        assertTrue("intercept=true 视为启用", legacy.enabled);
        assertTrue("未显式关闭 dry_run 时仍只记录", legacy.dryRun);

        // 非法 d_sec 界外 → 回落默认（防配置错误把 D 当 0）
        ServerGuardianEngine.Config badD = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"d_sec\":0}}"));
        assertEquals(ServerGuardianEngine.DEFAULT_D_SEC, badD.dSec);
    }

    // ======================= 2. 推送解析（防御式） =======================

    @Test
    public void parseThreats_targetPushAndObservationShapes() {
        long end = 1_792_760_400L;
        List<ServerGuardianEngine.Threat> list = ServerGuardianEngine.parseThreats(
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        assertEquals(1, list.size());
        ServerGuardianEngine.Threat t = list.get(0);
        assertEquals(13080L, t.marchId);
        assertEquals(ENEMY_UID, t.ownerUid);
        assertEquals(2L, t.type);
        assertEquals(1001L, t.targetUid);
        assertEquals(end, t.endEpochSec);
        assertTrue(t.hasXY);
        assertEquals(679, t.x);
        assertEquals(686, t.y);

        // 观察哨形状：marches{9.0:{march:{...}}}
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 13079L);
        march.put("owner", ENEMY_UID);
        march.put("type", 1L);
        march.put("endtime", end + 5);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        slot.put("power", 0);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("marches", Map.of("9.0", slot));
        List<ServerGuardianEngine.Threat> l2 = ServerGuardianEngine.parseThreats(c);
        assertEquals(1, l2.size());
        assertEquals(1L, l2.get(0).type);
        assertEquals("侦查行军坐标缺失时 hasXY=false", false, l2.get(0).hasXY);

        // data 包装层
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", attackPush(13081L, ENEMY_UID, 1002L, end + 9, 674, 684));
        assertEquals(1, ServerGuardianEngine.parseThreats(wrapped).size());
    }

    @Test
    public void parseThreats_malformed_noSideEffects() {
        assertTrue(ServerGuardianEngine.parseThreats(null).isEmpty());
        assertTrue(ServerGuardianEngine.parseThreats(new LinkedHashMap<>()).isEmpty());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(Map.of("foo", "bar")));
        assertTrue("缺 id/owner 的候选必须被丢弃", ServerGuardianEngine.parseThreats(c).isEmpty());
    }

    @Test
    public void parseDeletedMarchIds_and_memberPos() {
        Map<String, Object> del = new LinkedHashMap<>();
        del.put("to_marchs", List.of(Map.of("id", 1L), Map.of("id", 2L)));
        del.put("id", 3L);
        List<Long> ids = ServerGuardianEngine.parseDeletedMarchIds(del);
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(2L));
        assertTrue(ids.contains(3L));

        Map<String, Object> member = new LinkedHashMap<>();
        member.put("uid", 1001L);
        member.put("x", 674L);
        member.put("y", 684L);
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("1001.0", member);
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("member_list", list);
        Map<Long, long[]> parsed = ServerGuardianEngine.parseMemberPos(pos);
        assertEquals(1, parsed.size());
        assertEquals(674L, parsed.get(1001L)[0]);
        assertEquals(684L, parsed.get(1001L)[1]);
    }

    @Test
    public void queueHelpers_capacityActiveFreeSlots() {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", 5L);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        assertEquals(5, ServerGuardianEngine.capacityOf(wpi));
        assertEquals("取不到容量返回 0（上层宁可不发）", 0, ServerGuardianEngine.capacityOf(null));

        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("id", 1L);
        m1.put("state", 2L);
        m1.put("type", 4L);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("id", 2L);
        m2.put("state", 3L);
        m2.put("type", 5L);
        Map<String, Object> m3 = new LinkedHashMap<>();
        m3.put("id", 3L);
        m3.put("state", 1L);   // 空闲/已结束：不占队列
        m3.put("type", 4L);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("marchs", List.of(m1, m2, m3));
        assertEquals("在途/驻扎/回程=占用（state 2/3/4）", 2, ServerGuardianEngine.activeMarchesOf(detail));
        assertEquals(3, ServerGuardianEngine.freeSlots(5, 2));
        assertEquals("负值防御", 0, ServerGuardianEngine.freeSlots(1, 2));
    }

    // ======================= 3. 规则判定（单盟友/进攻/ETA） =======================

    @Test
    public void decide_singleAllyAttackRules() {
        ServerGuardianEngine.Config cfg = new ServerGuardianEngine.Config();
        cfg.enabled = true;
        cfg.allyUid = 1001L;
        long now = System.currentTimeMillis();
        long end = now / 1000L + 120L;

        ServerGuardianEngine.Threat hit = threat(1L, 2L, 1001L, end, 674, 684);
        assertTrue(ServerGuardianEngine.decide(hit, cfg, now).hit);

        assertFalse("type=1 侦查不驻防",
                ServerGuardianEngine.decide(threat(2L, 1L, 1001L, end, 674, 684), cfg, now).hit);
        assertFalse("已过期（ETA<=0）拒绝",
                ServerGuardianEngine.decide(threat(3L, 2L, 1001L, now / 1000L - 5, 674, 684), cfg, now).hit);
        assertFalse("ETA<3s 过近拒绝（P0 追不上）",
                ServerGuardianEngine.decide(threat(4L, 2L, 1001L, now / 1000L + 1, 674, 684), cfg, now).hit);
        assertFalse("非白名单盟友拒绝",
                ServerGuardianEngine.decide(threat(5L, 2L, 2002L, end, 674, 684), cfg, now).hit);

        ServerGuardianEngine.Config off = new ServerGuardianEngine.Config();
        assertFalse("enabled=false 一律拒绝", ServerGuardianEngine.decide(hit, off, now).hit);
    }

    @Test
    public void decide_targetPointAndTargetUidFilters() {
        ServerGuardianEngine.Config cfg = new ServerGuardianEngine.Config();
        cfg.enabled = true;
        cfg.targetX = 674;
        cfg.targetY = 684;
        long now = System.currentTimeMillis();
        long end = now / 1000L + 120L;

        assertTrue("坐标匹配 target_point",
                ServerGuardianEngine.decide(threat(1L, 2L, 0L, end, 674, 684), cfg, now).hit);
        assertFalse("坐标不匹配拒绝",
                ServerGuardianEngine.decide(threat(2L, 2L, 0L, end, 675, 684), cfg, now).hit);
        assertFalse("target_point 已配但推送无坐标 → 宁可不发",
                ServerGuardianEngine.decide(threat(3L, 2L, 0L, end, 0, 0), cfg, now).hit);

        ServerGuardianEngine.Config byUid = new ServerGuardianEngine.Config();
        byUid.enabled = true;
        byUid.targetUid = 1002L;
        assertFalse("target_uid 不符拒绝",
                ServerGuardianEngine.decide(threat(4L, 2L, 1001L, end, 674, 684), byUid, now).hit);
        assertTrue("target_uid 匹配放行",
                ServerGuardianEngine.decide(threat(5L, 2L, 1002L, end, 674, 684), byUid, now).hit);
    }

    private static ServerGuardianEngine.Threat threat(long marchId, long type, long targetUid,
                                                      long endSec, int x, int y) {
        return new ServerGuardianEngine.Threat(marchId, ENEMY_UID, type, targetUid,
                x, y, x > 0 && y > 0, endSec, System.currentTimeMillis());
    }

    // ======================= 4. 行为：默认关 / dry_run / 发射 / 撤防 =======================

    @Test
    public void disabled_defaultNoSideEffectsAndNoRequest() {
        accountWithParams(null);   // 未配置 guardian → 默认关
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        long end = System.currentTimeMillis() / 1000L + 120L;
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        engine.awaitIdleForTest(2_000L);

        assertTrue("默认关：零请求", e.requested.isEmpty());
        assertTrue("默认关：零状态写入", state.snapshot().isEmpty());
        assertTrue(engine.describe().contains("enabled=false"));
        assertEquals(0, engine.threatCount());
        engine.shutdown();
    }

    @Test
    public void dryRun_recordsThreatAndLedgerWithoutRequest() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        long end = System.currentTimeMillis() / 1000L + 120L;
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        engine.awaitIdleForTest(2_000L);

        assertTrue("dry_run 不发任何请求（含队列/编队查询）", e.requested.isEmpty());
        JSONObject last = lastLedger();
        assertEquals("dry_run", last.getString("ev"));
        assertEquals(13080L, last.getLong("marchId"));
        assertEquals(1001L, last.getLong("targetUid"));
        String mod = state.getString(ServerGuardianEngine.modKey(FPID, SID));
        assertNotNull("dry_run 也写 mod 文案供 App 观测", mod);
        assertTrue(mod.contains("[DRY]"));

        // 同一威胁的后续 update 推送不得重复记账（每威胁最多 1 次动作）
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, end - 30, 679, 686));
        engine.awaitIdleForTest(2_000L);
        JSONArray arr = new JSONArray(ledgerRaw());
        assertEquals("dry_run 只记一次", 1, arr.length());
        assertTrue(e.requested.isEmpty());
        engine.shutdown();
    }

    @Test
    public void enabled_launchesType5AndRecordsMission() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long end = System.currentTimeMillis() / 1000L + 120L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, end + 20));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));

        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertTrue("应发 type=5 驻防；requested=" + e.requested + " ledger="
                + state.getString(ServerGuardianEngine.ledgerKey(FPID, SID))
                + " describe=" + engine.describe(), e.requested.contains(ServerGuardianLauncher.PROTO_WORLD_MARCH));
        Map<String, Object> args = null;
        for (int i = 0; i < e.requested.size(); i++) {
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH.equals(e.requested.get(i))) args = e.argLog.get(i);
        }
        assertNotNull(args);
        assertEquals(5L, ((Number) args.get("type")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> ep = (Map<String, Object>) args.get("endpoint");
        assertEquals(679L, ((Number) ep.get("x")).longValue());
        assertEquals(686L, ((Number) ep.get("y")).longValue());

        JSONObject last = lastLedger();
        assertEquals("launched", last.getString("ev"));
        assertEquals(555L, last.getLong("ownMarchId"));
        assertEquals(end + 20, last.getLong("ownEndSec"));
        assertEquals(1, engine.missionCount());
        engine.shutdown();
    }

    @Test
    public void enemyRecalled_triggersRecallRequest() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long nowS = System.currentTimeMillis() / 1000L;
        long enemyEnd = nowS + 120L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, nowS + 60L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));

        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, enemyEnd, 679, 686));
        engine.awaitIdleForTest(3_000L);
        assertEquals(1, engine.missionCount());

        // 敌召回（endtime 未到 → remain>2s 判召回）：应发在途召回，撤防后任务清空
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13080L));
        engine.awaitIdleForTest(3_000L);

        assertTrue("敌召回 → req_world_march_recall",
                e.requested.contains(ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals("撤防成功清任务", 0, engine.missionCount());
        engine.shutdown();
    }

    @Test
    public void offline_skipsWithoutRequest() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false}}");
        TestEngine e = new TestEngine();
        e.online = false;
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        long end = System.currentTimeMillis() / 1000L + 120L;
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        engine.awaitIdleForTest(2_000L);

        assertTrue("离线不发请求", e.requested.isEmpty());
        assertEquals("skip_offline", lastLedger().getString("ev"));
        engine.shutdown();
    }

    @Test
    public void allyMemberPosFeedsMapOnlyWhenEnabled() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);

        Map<String, Object> member = new LinkedHashMap<>();
        member.put("uid", 1001L);
        member.put("x", 674L);
        member.put("y", 684L);
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("member_list", Map.of("1001.0", member));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_POS_INFO, pos);
        assertEquals(1, engine.allyCount());
        engine.shutdown();
    }

    @Test
    public void describe_isDiagnostic() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true},\"strategy\":{\"ally_uid\":1001}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.tick(e);
        String d = engine.describe();
        assertTrue(d.contains("guardian{"));
        assertTrue(d.contains("enabled=true"));
        assertTrue(d.contains("dry_run=true"));
        assertTrue(d.contains("ally_uid=1001"));
        assertTrue(d.contains("threats=0"));
        engine.shutdown();
    }
}
