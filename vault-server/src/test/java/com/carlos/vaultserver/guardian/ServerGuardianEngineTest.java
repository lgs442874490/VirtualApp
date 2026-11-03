package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.formation.FormationCatalog;
import com.carlos.vaultserver.formation.HeroAvailability;
import com.carlos.vaultserver.task.Exec;

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
import static org.junit.Assert.assertNull;
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
        /** 批次 D：同一 proto 的连续响应队列（多发追赶需要逐发不同 endtime）。 */
        final Map<String, java.util.Deque<GameMessage>> queues = new HashMap<>();
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
            java.util.Deque<GameMessage> q = queues.get(proto);
            if (q != null && !q.isEmpty()) return q.pollFirst();
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
        String effective = legacyTimingOff(guardianJson);
        if (effective != null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put(ServerGuardianEngine.FN_ID, effective);
            a.roleParams.put(String.valueOf(SID), params);
        }
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    /**
     * 本类用例写在批次 C-A3 之前，默认按“推送后立即发射”断言；统一注入 timing=false 保持原断言语义。
     * 卡秒默认开/重排/自愈/取消等新行为由 GuardianTimingTest 覆盖。
     */
    private static String legacyTimingOff(String guardianJson) {
        if (guardianJson == null || guardianJson.trim().isEmpty()) return guardianJson;
        try {
            JSONObject p = new JSONObject(guardianJson);
            if (!p.has("timing")) p.put("timing", false);
            return p.toString();
        } catch (Throwable ignored) {
            return guardianJson;
        }
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

    /** 取 attackPush 里的行军 map（可变，便于追加 squad_list / power 字段）。 */
    private static Map<String, Object> marchOf(Map<String, Object> push) {
        Object arr = push.get("to_marchs");
        assertTrue(arr instanceof List);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) ((List<?>) arr).get(0);
        return m;
    }

    /** squad_list{soldier:...} 包装（WarNotify.troopsOf 口径）。 */
    private static Map<String, Object> squadList(Object soldier) {
        Map<String, Object> squad = new LinkedHashMap<>();
        squad.put("soldier", soldier);
        return squad;
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
    public void parseConfig_envGrayOverrides() {
        try {
            System.clearProperty("VAULT_GUARDIAN_SPEEDUP");
            System.clearProperty("VAULT_GUARDIAN_DIAMOND");
            System.clearProperty("VAULT_GUARDIAN_ENABLED");
            System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
            System.clearProperty("VAULT_GUARDIAN_TROOPS_MIN");
            JSONObject p = new JSONObject("{\"sub\":{\"enabled\":true,\"dry_run\":false}}");
            ServerGuardianEngine.Config base = ServerGuardianEngine.parseConfig(p);
            assertFalse("env 未设置：以 roleParams 为准（默认关）", base.speedupEnabled);
            assertFalse(base.diamondSpeedupEnabled);

            System.setProperty("VAULT_GUARDIAN_SPEEDUP", "1");
            System.setProperty("VAULT_GUARDIAN_DIAMOND", "on");
            ServerGuardianEngine.Config gray = ServerGuardianEngine.parseConfig(p);
            assertTrue("env 灰度开启对等加速", gray.speedupEnabled);
            assertFalse("批次 K：env=1 不再强制开钻石，以用户 params 为准", gray.diamondSpeedupEnabled);
            assertFalse("env=1 不是硬关", gray.diamondHardOff);

            // 无 roleParams 时也能靠 env 实盘开启（目标由 push 自动解析，无需配置盟友/坐标）
            System.setProperty("VAULT_GUARDIAN_ENABLED", "1");
            System.setProperty("VAULT_GUARDIAN_DRY_RUN", "0");
            System.setProperty("VAULT_GUARDIAN_TROOPS_MIN", "50000");
            ServerGuardianEngine.Config live = ServerGuardianEngine.parseConfig(null);
            assertTrue("env 实盘：无 roleParams 也可开守护", live.enabled);
            assertFalse("env 实盘：dry_run=0", live.dryRun);
            assertEquals("env 兵力档位", 50000L, live.troopsMin);

            // 批次 K：env=0/off 是全局硬关（忽略用户开关）
            System.setProperty("VAULT_GUARDIAN_DIAMOND", "0");
            ServerGuardianEngine.Config hard = ServerGuardianEngine.parseConfig(new JSONObject(
                    "{\"sub\":{\"enabled\":true,\"diamond_speedup_enabled\":true}}"));
            assertFalse("env=0 → 硬关，用户开也不生效", hard.diamondSpeedupEnabled);
            assertTrue("env=0 → diamond_hard_off 诊断字段", hard.diamondHardOff);
            assertTrue(hard.describe().contains("diamond_hard_off=true"));

            System.setProperty("VAULT_GUARDIAN_TROOPS_MIN", "abc");
            ServerGuardianEngine.Config badTroops = ServerGuardianEngine.parseConfig(null);
            assertEquals("非法兵力档位忽略", 0L, badTroops.troopsMin);

            System.setProperty("VAULT_GUARDIAN_SPEEDUP", "maybe");
            System.setProperty("VAULT_GUARDIAN_DIAMOND", "0");
            ServerGuardianEngine.Config bad = ServerGuardianEngine.parseConfig(p);
            assertFalse("非法 env 忽略 → 沿用 roleParams", bad.speedupEnabled);
            assertFalse("显式 0 可强制关闭钻石", bad.diamondSpeedupEnabled);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_SPEEDUP");
            System.clearProperty("VAULT_GUARDIAN_DIAMOND");
            System.clearProperty("VAULT_GUARDIAN_ENABLED");
            System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
            System.clearProperty("VAULT_GUARDIAN_TROOPS_MIN");
        }
    }

    @Test
    public void reloadConfig_appliesEnvOverrides_withoutRoleParams() {
        try {
            System.clearProperty("VAULT_GUARDIAN_ENABLED");
            System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
            accountWithParams(null);            // 账号存在但无 guardian roleParams（App 未下发）
            ServerGuardianEngine e = engine();
            e.reloadConfig(true);
            assertFalse("无 env/无参数 → 默认关", e.configSnapshot().enabled);
            assertTrue(e.configSnapshot().dryRun);

            System.setProperty("VAULT_GUARDIAN_ENABLED", "1");
            System.setProperty("VAULT_GUARDIAN_DRY_RUN", "0");
            e.reloadConfig(true);
            assertTrue("env 实盘：仅 env 即可开守护（无需任何目标配置/roleParams）", e.configSnapshot().enabled);
            assertFalse("env 实盘：dry_run=0", e.configSnapshot().dryRun);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_ENABLED");
            System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
        }
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

    @Test
    public void parseConfig_explicitEnabledIsAuthoritativeOverLegacyIntercept() {
        // 2026-10-24 总开关语义修复：App 配置卡总开关（显式 enabled）为权威值；
        // 即使旧 FnDetail 的 sub.intercept 仍为 true，也不能把「总开关关」重新打开。
        ServerGuardianEngine.Config off = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"enabled\":false,\"sub\":{\"intercept\":true,\"watch\":true}}"));
        assertFalse("显式 enabled=false 必须压过 intercept=true", off.enabled);

        ServerGuardianEngine.Config on = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"enabled\":true,\"sub\":{\"intercept\":false}}"));
        assertTrue("显式 enabled=true 不要求 intercept", on.enabled);

        // 旧数据无 enabled 键：保留 intercept 兼容语义（升级不丢启用状态）
        ServerGuardianEngine.Config legacy = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"intercept\":true}}"));
        assertTrue(legacy.enabled);
        ServerGuardianEngine.Config legacyOff = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"intercept\":false}}"));
        assertFalse(legacyOff.enabled);
    }

    @Test
    public void parseConfig_forceOnlineAndTroopsMin() {
        // 默认值：强制上线关、兵力下限 0（未配置新参数 → 旧行为零变化）
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertFalse("force_online 默认 false（不强制回连）", def.forceOnline);
        assertEquals("troops_min 默认 0=不限", 0L, def.troopsMin);
        assertTrue("describe 回显 force_online", def.describe().contains("force_online=false"));
        assertTrue("describe 回显 troops_min", def.describe().contains("troops_min=0"));

        // sub / strategy / top 兼容（first(sub,strategy,top)；sub 优先）
        ServerGuardianEngine.Config c = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"sub\":{\"force_online\":true},\"strategy\":{\"troops_min\":\"10000\"},"
                        + "\"force_online\":false,\"troops_min\":50}"));
        assertTrue("sub.force_online 优先于 top", c.forceOnline);
        assertEquals("strategy.troops_min 字符串数字兼容", 10000L, c.troopsMin);
        assertTrue(c.describe().contains("force_online=true"));
        assertTrue(c.describe().contains("troops_min=10000"));

        // 兼容别名 min_enemy_troops（仅在 troops_min 缺省时生效）
        ServerGuardianEngine.Config alias = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"min_enemy_troops\":5000}}"));
        assertEquals(5000L, alias.troopsMin);
        ServerGuardianEngine.Config both = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"troops_min\":300,\"min_enemy_troops\":5000}}"));
        assertEquals("troops_min 优先于别名", 300L, both.troopsMin);

        // 顶层扁平键（App 旧版只回显顶层）
        ServerGuardianEngine.Config top = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"force_online\":true,\"troops_min\":300}"));
        assertTrue(top.forceOnline);
        assertEquals(300L, top.troopsMin);

        // 负值/非法值 → 0（不限；不得回退成负阈值把威胁全拒）
        assertEquals(0L, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"troops_min\":-5}}")).troopsMin);
        assertEquals(0L, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"troops_min\":\"abc\"}}")).troopsMin);
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
    public void parseThreats_troopsAndPower() {
        long end = System.currentTimeMillis() / 1000L + 300L;

        // 目标系列：squad_list.soldier map 值=数量（WarNotify.troopsOf 口径）
        Map<String, Object> c1 = attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(c1).put("squad_list", squadList(Map.of("11000", 1200L, "21000", 800L)));
        ServerGuardianEngine.Threat t1 = ServerGuardianEngine.parseThreats(c1).get(0);
        assertTrue("map 值=数量 → 兵力已知", t1.troopsKnown);
        assertEquals("1200+800=2000", 2000L, t1.troops);
        assertEquals("无 power 字段 → 0=未知", 0L, t1.power);

        // map 值={count:n}
        Map<String, Object> c2 = attackPush(13081L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(c2).put("squad_list", squadList(Map.of("11000", Map.of("count", 500L))));
        ServerGuardianEngine.Threat t2 = ServerGuardianEngine.parseThreats(c2).get(0);
        assertTrue(t2.troopsKnown);
        assertEquals(500L, t2.troops);

        // list 元素={id,count}
        Map<String, Object> c3 = attackPush(13082L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(c3).put("squad_list", squadList(List.of(
                Map.of("id", 11000L, "count", 700L),
                Map.of("id", 31000L, "count", 300L))));
        ServerGuardianEngine.Threat t3 = ServerGuardianEngine.parseThreats(c3).get(0);
        assertTrue(t3.troopsKnown);
        assertEquals(1000L, t3.troops);

        // march.power 直接可读
        Map<String, Object> c4 = attackPush(13083L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(c4).put("power", 8888L);
        ServerGuardianEngine.Threat t4 = ServerGuardianEngine.parseThreats(c4).get(0);
        assertEquals("march.power 解析", 8888L, t4.power);
        assertFalse("无 squad_list → 兵力未知", t4.troopsKnown);

        // 观察哨外层 power：{"marches":{"9.0":{"march":{...},"power":n}}}，无 squad → 兵力未知
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 13079L);
        march.put("owner", ENEMY_UID);
        march.put("type", 2L);
        march.put("endtime", end);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        slot.put("power", 12345L);
        Map<String, Object> c5 = new LinkedHashMap<>();
        c5.put("marches", Map.of("9.0", slot));
        ServerGuardianEngine.Threat t5 = ServerGuardianEngine.parseThreats(c5).get(0);
        assertEquals("观察哨外层 power 必须拿到", 12345L, t5.power);
        assertFalse("只有 power 无 squad_list → 兵力未知（保守侧关键）", t5.troopsKnown);
        assertEquals(0L, t5.troops);

        // 同一行军 add（含 squad）+ update（缺 squad，endtime 前移）合并：不丢已知兵力
        Map<String, Object> known = attackPush(13084L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(known).put("squad_list", squadList(Map.of("11000", 2000L)));
        Map<String, Object> update = attackPush(13084L, ENEMY_UID, 1001L, end - 10, 679, 686);
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("to_marchs", List.of(marchOf(known), marchOf(update)));
        ServerGuardianEngine.Threat merged = ServerGuardianEngine.parseThreats(both).get(0);
        assertTrue("update 缺 squad 不丢已解析兵力", merged.troopsKnown);
        assertEquals(2000L, merged.troops);
        assertEquals(end - 10, merged.endEpochSec);
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
        // 批次 D（用户口径）：ETA 太近/刚落地不再直接放弃 → 落地 60s 内仍补驻防
        assertTrue("已落地 5s（-5s）仍补驻防",
                ServerGuardianEngine.decide(threat(3L, 2L, 1001L, now / 1000L - 5, 674, 684), cfg, now).hit);
        assertTrue("ETA 1s（加速压秒）仍尽量驻防",
                ServerGuardianEngine.decide(threat(4L, 2L, 1001L, now / 1000L + 1, 674, 684), cfg, now).hit);
        assertFalse("已落地 >60s 不再补驻防",
                ServerGuardianEngine.decide(threat(6L, 2L, 1001L, now / 1000L - 61, 674, 684), cfg, now).hit);
        assertFalse("已落地 90s 拒绝",
                ServerGuardianEngine.decide(threat(7L, 2L, 1001L, now / 1000L - 90, 674, 684), cfg, now).hit);
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

    @Test
    public void decide_troopsThreshold() {
        ServerGuardianEngine.Config cfg = new ServerGuardianEngine.Config();
        cfg.enabled = true;
        cfg.allyUid = 1001L;
        cfg.troopsMin = 5000L;
        long now = System.currentTimeMillis();
        long end = now / 1000L + 120L;

        ServerGuardianEngine.Decision low = ServerGuardianEngine.decide(
                threatTroops(1L, 2L, 1001L, end, 1200L, true, 0L), cfg, now);
        assertFalse("已知兵力 < 阈值 → 不派兵", low.hit);
        assertTrue("拒绝原因留痕 troops_low：" + low.reason, low.reason.contains("troops_low"));

        ServerGuardianEngine.Decision high = ServerGuardianEngine.decide(
                threatTroops(2L, 2L, 1001L, end, 8000L, true, 0L), cfg, now);
        assertTrue("已知兵力 ≥ 阈值 → 放行", high.hit);

        ServerGuardianEngine.Decision unknown = ServerGuardianEngine.decide(
                threatTroops(3L, 2L, 1001L, end, 0L, false, 0L), cfg, now);
        assertFalse("兵力未知 → 守护侧保守不发射", unknown.hit);
        assertTrue("拒绝原因留痕 troops_unknown：" + unknown.reason,
                unknown.reason.contains("troops_unknown"));

        // troops_min=0（默认）：兵力未知/偏低都按旧行为放行（未配置新参数 → 零变化）
        ServerGuardianEngine.Config off = new ServerGuardianEngine.Config();
        off.enabled = true;
        off.allyUid = 1001L;
        assertEquals(0L, off.troopsMin);
        assertTrue("旧行为：兵力低于任意值仍放行",
                ServerGuardianEngine.decide(threatTroops(4L, 2L, 1001L, end, 1L, true, 0L), off, now).hit);
        assertTrue("旧行为：兵力未知仍放行",
                ServerGuardianEngine.decide(threatTroops(5L, 2L, 1001L, end, 0L, false, 0L), off, now).hit);

        // 阈值不改变既有过滤顺序：非盟友仍按 not_ally 拒绝（不因兵力足够而放行）
        ServerGuardianEngine.Decision notAlly = ServerGuardianEngine.decide(
                threatTroops(6L, 2L, 2002L, end, 9000L, true, 0L), cfg, now);
        assertFalse(notAlly.hit);
        assertTrue(notAlly.reason.contains("not_ally"));
    }

    private static ServerGuardianEngine.Threat threatTroops(long marchId, long type, long targetUid,
                                                            long endSec, long troops,
                                                            boolean known, long power) {
        return new ServerGuardianEngine.Threat(marchId, ENEMY_UID, type, targetUid,
                674, 684, true, endSec, System.currentTimeMillis(), troops, known, power);
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
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":false},"
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

    // ======================= 5. 触发策略：force_online 查询 / 兵力阈值留痕 =======================

    @Test
    public void forceOnlineWanted_defaultOffAndQueryOnly() {
        // 默认关：未 attach 的引擎 → 不强制回连
        ServerGuardianEngine bare = engine();
        assertFalse("默认关不强制回连", bare.forceOnlineWanted());
        bare.shutdown();

        // enabled=true 但 force_online=false → 有威胁也不强制回连
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine g = engine();
        g.attach(e);
        long end = System.currentTimeMillis() / 1000L + 120L;
        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        g.awaitIdleForTest(2_000L);
        assertEquals("威胁已入账", 1, g.threatCount());
        assertFalse("force_online=false 不强制回连", g.forceOnlineWanted());
        assertTrue("查询本身零请求", e.requested.isEmpty());
        g.shutdown();
    }

    @Test
    public void forceOnlineWanted_enabledForceOnlineAndHit() {
        // dry_run=true：只记录不派兵 → 即使 force_online=true 也不抢登（用户预期：强制上线是为派兵）
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true,\"force_online\":true}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine g = engine();
        g.attach(e);
        long nowS = System.currentTimeMillis() / 1000L;

        // 无威胁 → false
        assertFalse("无威胁不回连", g.forceOnlineWanted());

        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 120L, 679, 686));
        g.awaitIdleForTest(2_000L);
        assertFalse("dry_run 不派兵 → 不强制回连", g.forceOnlineWanted());
        assertTrue("查询本身零请求", e.requested.isEmpty());
        g.shutdown();

        // dry_run=false（实发）：enabled ∧ force_online ∧ 命中 → true
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"force_online\":true}}");
        TestEngine e2 = new TestEngine();
        e2.online = false;   // 只验证查询语义；实发路径由既有用例覆盖
        ServerGuardianEngine g2 = engine();
        g2.attach(e2);
        g2.onPush(e2, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13081L, ENEMY_UID, 1001L, nowS + 120L, 679, 686));
        g2.awaitIdleForTest(2_000L);
        assertTrue("实发 ∧ enabled ∧ force_online ∧ 命中 → 强制回连", g2.forceOnlineWanted());
        assertTrue("查询本身零请求", e2.requested.isEmpty());
        g2.shutdown();

        // 仅有已过期威胁（endtime 早已过去 → decide ETA 拒绝）→ false
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"force_online\":true}}");
        TestEngine e3 = new TestEngine();
        e3.online = false;
        ServerGuardianEngine g3 = engine();
        g3.attach(e3);
        g3.onPush(e3, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13082L, ENEMY_UID, 1001L, nowS - 300L, 679, 686));
        g3.awaitIdleForTest(2_000L);
        assertEquals("过期威胁仍入账（供诊断）", 1, g3.threatCount());
        assertFalse("仅剩过期威胁不回连", g3.forceOnlineWanted());
        g3.shutdown();
    }

    @Test
    public void forceOnlineWanted_respectsTroopsThreshold() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"force_online\":true},"
                + "\"strategy\":{\"troops_min\":5000}}");
        TestEngine e = new TestEngine();
        e.online = false;   // 阈值/查询语义从 decide 层验证，不触发实发路径
        ServerGuardianEngine g = engine();
        g.attach(e);
        long end = System.currentTimeMillis() / 1000L + 120L;

        // 兵力未知 → decide 拒绝 → 不强制回连（保守）
        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686));
        g.awaitIdleForTest(2_000L);
        assertFalse("兵力未知被阈值拒绝 → 不回连", g.forceOnlineWanted());

        // 兵力低于阈值 → 不强制回连
        Map<String, Object> low = attackPush(13081L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(low).put("squad_list", squadList(Map.of("11000", 1000L)));
        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, low);
        g.awaitIdleForTest(2_000L);
        assertFalse("兵力低于阈值 → 不回连", g.forceOnlineWanted());

        // 兵力达到阈值 → 强制回连
        Map<String, Object> high = attackPush(13082L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(high).put("squad_list", squadList(Map.of("11000", 8000L)));
        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, high);
        g.awaitIdleForTest(2_000L);
        assertTrue("兵力达标 → 强制回连", g.forceOnlineWanted());
        assertTrue(e.requested.isEmpty());
        g.shutdown();
    }

    @Test
    public void troopsThreshold_ledgersSkipOnceAndNoRequest() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true},"
                + "\"strategy\":{\"troops_min\":5000}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine g = engine();
        g.attach(e);
        long end = System.currentTimeMillis() / 1000L + 120L;
        Map<String, Object> push = attackPush(13080L, ENEMY_UID, 1001L, end, 679, 686);
        marchOf(push).put("squad_list", squadList(Map.of("11000", 1200L)));

        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push);
        g.awaitIdleForTest(2_000L);
        assertTrue("阈值拒绝不发请求", e.requested.isEmpty());
        JSONObject last = lastLedger();
        assertEquals("阈值拒绝留痕", "skip_troops", last.getString("ev"));
        assertTrue("原因写明 troops_low：" + last.getString("note"),
                last.getString("note").contains("troops_low"));

        // 同一判定值的 update 推送不重复记账（防刷屏）
        Map<String, Object> update = attackPush(13080L, ENEMY_UID, 1001L, end - 30, 679, 686);
        marchOf(update).put("squad_list", squadList(Map.of("11000", 1200L)));
        g.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE, update);
        g.awaitIdleForTest(2_000L);
        JSONArray arr = new JSONArray(ledgerRaw());
        assertEquals("同一判定值只留痕一次", 1, arr.length());

        // 兵力未知（只有 power）→ 另起引擎验证 troops_unknown 留痕
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true},"
                + "\"strategy\":{\"troops_min\":5000}}");
        TestEngine e2 = new TestEngine();
        ServerGuardianEngine g2 = engine();
        g2.attach(e2);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 13090L);
        march.put("owner", ENEMY_UID);
        march.put("type", 2L);
        march.put("endtime", end);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        slot.put("power", 7777L);
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("marches", Map.of("9.0", slot));
        g2.onPush(e2, ServerGuardianEngine.PROTO_ALLIANCE_ADD, obs);
        g2.awaitIdleForTest(2_000L);
        assertTrue("兵力未知不发请求", e2.requested.isEmpty());
        assertEquals("skip_troops", lastLedger().getString("ev"));
        assertTrue("原因写明 troops_unknown：" + lastLedger().getString("note"),
                lastLedger().getString("note").contains("troops_unknown"));
        g2.shutdown();
        g.shutdown();
    }

    @Test
    public void describe_isDiagnostic() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true},"
                + "\"strategy\":{\"ally_uid\":1001,\"force_online\":true,\"troops_min\":5000}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.tick(e);
        String d = engine.describe();
        assertTrue(d.contains("guardian{"));
        assertTrue(d.contains("enabled=true"));
        assertTrue(d.contains("dry_run=true"));
        assertTrue(d.contains("ally_uid=1001"));
        assertTrue("describe 回显 force_online", d.contains("force_online=true"));
        assertTrue("describe 回显 troops_min", d.contains("troops_min=5000"));
        assertTrue(d.contains("threats=0"));
        engine.shutdown();
    }

    // ======================= 6. P1：对等加速接线 =======================

    /** 背包道具推送（notify_backpack_init data=[{id,count}]；生产形状实证）。 */
    private static void presetSpeedupStock(TestEngine e, long n25, long n50) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (n25 > 0) data.add(new LinkedHashMap<>(Map.of("id", 301900L, "count", n25)));
        if (n50 > 0) data.add(new LinkedHashMap<>(Map.of("id", 301901L, "count", n50)));
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("data", data);
        e.notifies.put("notify_backpack_init", bp);
    }

    /** 加速响应：{code:0, endtime:新到达服务器秒}（A-15 实证形状）。 */
    private static GameMessage speedupOk(long endtimeSec) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        c.put("endtime", endtimeSec);
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private static int requestCount(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) {
            if (proto.equals(p)) n++;
        }
        return n;
    }

    /** 清空请求留痕（只看某事件之后的请求序列）。 */
    private static void clearRequests(TestEngine e) {
        e.requested.clear();
        e.argLog.clear();
    }

    private static Map<String, Object> lastArgsOf(TestEngine e, String proto) {
        for (int i = e.requested.size() - 1; i >= 0; i--) {
            if (proto.equals(e.requested.get(i))) return e.argLog.get(i);
        }
        return null;
    }

    private JSONObject ledgerEvent(String ev) {
        String raw = ledgerRaw();
        if (raw == null) return null;
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (ev.equals(o.optString("ev"))) return o;
        }
        return null;
    }

    private static final String SPEEDUP_CFG =
            "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"speedup_enabled\":true,"
                    + "\"catch_up\":false},"   // 批次 D：本组用例只测 P1 压缩对等加速，关闭追赶
                    + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}";

    /** 发射一单 type=5 驻防（enemyEnd 已入威胁表、mission 已在册）。 */
    private ServerGuardianEngine launchForSpeedup(TestEngine e, long enemyEndSec, long ourEndSec) {
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, ourEndSec));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, enemyEndSec, 679, 686));
        engine.awaitIdleForTest(3_000L);
        return engine;
    }

    @Test
    public void parseConfig_speedupKeys_defaultsOff() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertFalse("speedup_enabled 默认关（关闭时零请求零行为变化）", def.speedupEnabled);
        assertFalse("diamond_speedup_enabled 默认关（默认零钻石硬约束）", def.diamondSpeedupEnabled);
        assertEquals("speedup_margin_s 默认 2s", 2L, def.speedupMarginS);
        assertEquals("max_speedups_per_march 默认 0=不限", 0, def.maxSpeedupsPerMarch);
        assertEquals("diamond_max_per_march 默认 3", 3, def.diamondMaxPerMarch);
        assertTrue(def.describe().contains("speedup=false"));
        assertTrue(def.describe().contains("diamond_speedup=false"));
        assertTrue(def.describe().contains("diamond_max=3"));

        ServerGuardianEngine.Config on = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"sub\":{\"speedup_enabled\":true,\"diamond_speedup_enabled\":true,"
                + "\"catch_up\":false},"
                        + "\"strategy\":{\"speedup_margin_s\":3,\"max_speedups_per_march\":4,"
                        + "\"diamond_max_per_march\":5}}"));
        assertTrue(on.speedupEnabled);
        assertTrue(on.diamondSpeedupEnabled);
        assertEquals(3L, on.speedupMarginS);
        assertEquals(4, on.maxSpeedupsPerMarch);
        assertEquals(5, on.diamondMaxPerMarch);
        assertTrue(on.describe().contains("speedup=true"));
        assertTrue(on.describe().contains("max_speedups=4"));
        assertTrue(on.describe().contains("diamond_max=5"));

        // diamond_max_per_march：0=不限；负数=非法回落默认 3
        assertEquals("0=不限", 0, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"diamond_max_per_march\":0}}")).diamondMaxPerMarch);
        assertEquals("负数非法回落默认 3", 3, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"diamond_max_per_march\":-1}}")).diamondMaxPerMarch);

        // 界外/非法值回落默认（防配置错误把 margin 当负/无限）
        ServerGuardianEngine.Config bad = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"strategy\":{\"speedup_margin_s\":999,\"max_speedups_per_march\":-3}}"));
        assertEquals(2L, bad.speedupMarginS);
        assertEquals(0, bad.maxSpeedupsPerMarch);

        // 顶层扁平/字符串数字兼容（roleParams 三处 first(sub,strategy,top) 同口径）
        ServerGuardianEngine.Config flat = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"speedup_enabled\":\"true\",\"speedup_margin_s\":\"5\"}"));
        assertTrue(flat.speedupEnabled);
        assertEquals(5L, flat.speedupMarginS);
    }

    @Test
    public void speedupDisabled_compressionNoSpeedupRequest() {
        // speedup_enabled=false 且 catch_up=false（批次 D 旧行为）：即使敌加速、道具充足，也必须零加速请求
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 10);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);
        assertEquals("驻防已在册", 1, engine.missionCount());

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals("speedup_enabled=false → 零加速请求", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        assertEquals("默认零钻石", 0, requestCount(e, "req_shop_quick_buy"));
        assertEquals("威胁仍在", 1, engine.threatCount());
        engine.shutdown();
    }

    @Test
    public void speedupEnabled_compressionSends25Percent() {
        accountWithParams(SPEEDUP_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 5);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);

        // 敌方压缩 120→95：我方 25% 后 ~90s，敌剩 ~95s → 领先 ≥2s，一发一阶足够
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 88L));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals("应发 1 次加速（25% 够用不升 50%）", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        Map<String, Object> args = lastArgsOf(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP);
        assertNotNull(args);
        assertEquals("加速目标=我方驻防 marchId", 555L, ((Number) args.get("id")).longValue());
        assertEquals("一阶 25% itemid", 301900L, ((Number) args.get("itemid")).longValue());
        assertEquals("owner 必须是字符串 uid（A-15 实证）", "1001", args.get("owner"));

        JSONObject ev = ledgerEvent("speedup");
        assertNotNull("加速必须有账本", ev);
        assertEquals(301900L, ev.getLong("itemid"));
        assertEquals("成功后本地库存递减", 9L, ev.getLong("stock25"));
        assertEquals(5L, ev.getLong("stock50"));
        assertEquals(nowS + 88L, ev.getLong("ownEndSec"));

        // 同一压缩态重复推送：不重复发（幂等，防请求风暴）
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(2_000L);
        assertEquals("重复压缩态不重复发", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        engine.shutdown();
    }

    @Test
    public void speedupEnabled_no25StockSends50Percent() {
        accountWithParams(SPEEDUP_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 5);   // 25% 库存 0 → 按规则升 50%
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);

        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 60L));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals(1, requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        Map<String, Object> args = lastArgsOf(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP);
        assertNotNull(args);
        assertEquals("25% 无库存 → 二阶 50% itemid", 301901L, ((Number) args.get("itemid")).longValue());
        JSONObject ev = ledgerEvent("speedup");
        assertNotNull(ev);
        assertEquals(4L, ev.getLong("stock50"));
        engine.shutdown();
    }

    @Test
    public void diamondSwitchOff_zeroQuickBuyEvenWhenNoItems() {
        // 默认 diamond_speedup_enabled=false：即使道具为 0，也不得有任何 quick_buy（硬约束零钻石）
        accountWithParams(SPEEDUP_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 0);   // 道具耗尽
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);
        clearRequests(e);   // 只看压缩事件之后的请求序列

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertTrue("开关关闭：请求序列必须为空（无 quick_buy/无 speedup）；实际=" + e.requested,
                e.requested.isEmpty());
        assertEquals("默认零钻石：绝不发 quick_buy", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY));
        JSONObject skip = ledgerEvent("speedup_skip");
        assertNotNull("诚实止损必须记账", skip);
        assertEquals("stock_insufficient", skip.getString("reason"));
        engine.shutdown();
    }

    @Test
    public void diamondSwitchOn_buysThenUses50Percent() {
        // 开关打开 + 道具 0 + 购买成功：2×25% 也来不及（120s vs 64s）→ quick_buy(301901) → speedup(301901)
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,"
                + "\"speedup_enabled\":true,\"diamond_speedup_enabled\":true,"
                + "\"catch_up\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 0);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);
        clearRequests(e);
        e.responses.put(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, msg(0));
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 60L));

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 64L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        // 请求序列：先买后加速（与 App「买完同一轮 speedup」同序）
        List<String> seq = new ArrayList<>(e.requested);
        assertEquals("请求序列=quick_buy → speedup；实际=" + seq, 2, seq.size());
        assertEquals(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, seq.get(0));
        assertEquals(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, seq.get(1));
        Map<String, Object> buyArgs = e.argLog.get(0);
        assertEquals("App 实证 use=false", Boolean.FALSE, buyArgs.get("use"));
        assertEquals("买二阶 50%", 301901L, ((Number) buyArgs.get("id")).longValue());
        assertEquals("一次买 1 颗", 1L, ((Number) buyArgs.get("count")).longValue());
        Map<String, Object> spArgs = e.argLog.get(1);
        assertEquals("买到的 301901 立即用于加速", 301901L, ((Number) spArgs.get("itemid")).longValue());

        JSONObject buy = ledgerEvent("speedup_diamond_buy");
        assertNotNull("购买成功必须记账", buy);
        assertEquals(301901L, buy.getLong("itemid"));
        assertEquals("花费 1 颗", 1L, buy.getLong("buyCount"));
        assertEquals("本行军累计购买 1", 1, buy.getInt("diamondBought"));
        assertEquals("购买后库存记账 +1", 1L, buy.getLong("stock50"));
        JSONObject sp = ledgerEvent("speedup");
        assertNotNull(sp);
        assertEquals("用掉后库存回 0", 0L, sp.getLong("stock50"));
        engine.shutdown();
    }

    @Test
    public void diamondBuyFailure_terminatesChainWithoutSpeedup() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,"
                + "\"speedup_enabled\":true,\"diamond_speedup_enabled\":true,"
                + "\"catch_up\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 0);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);
        clearRequests(e);
        e.responses.put(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, msg(1002));   // 购买失败码

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals("失败不做第二次购买（单发不重试）", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY));
        assertEquals("购买失败不发加速", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        JSONObject fail = ledgerEvent("speedup_diamond_buy_failed");
        assertNotNull("失败必须记账", fail);
        assertEquals(1002, fail.getInt("code"));
        assertEquals("失败不扣库存", 0L, fail.getLong("stock50"));
        assertEquals(0, fail.getInt("diamondBought"));
        engine.shutdown();
    }

    @Test
    public void diamondMaxPerMarch_one_stopsBeforeSecondBuy() {
        // 一颗 50% 不足以先到（我方 120s vs 敌 10s）：第一次购买后使用，第二次需要购买时止损不超买
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,"
                + "\"speedup_enabled\":true,\"diamond_speedup_enabled\":true,"
                + "\"catch_up\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]},"
                + "\"diamond_max_per_march\":1}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 0);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);
        clearRequests(e);
        e.responses.put(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, msg(0));
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 60L));

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 10L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals("diamond_max_per_march=1 → 只买 1 颗", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY));
        assertEquals("第一颗立即使用 1 次", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        JSONObject stop = ledgerEvent("speedup_diamond_skip");
        assertNotNull("超限必须记账", stop);
        assertEquals("diamond_limit", stop.getString("reason"));
        assertEquals(1, stop.getInt("diamondBought"));
        engine.shutdown();
    }

    @Test
    public void diamondSwitchOn_25PercentPreferred_noBuy() {
        // 有 25% 道具时优先 25%（成本优先），绝不触发钻石购买
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,"
                + "\"speedup_enabled\":true,\"diamond_speedup_enabled\":true,"
                + "\"catch_up\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 0);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);
        clearRequests(e);
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 88L));

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals("有 25% 道具 → 零 quick_buy", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY));
        assertEquals(1, requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        Map<String, Object> args = lastArgsOf(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP);
        assertNotNull(args);
        assertEquals("成本优先：用一阶 25%", 301900L, ((Number) args.get("itemid")).longValue());
        engine.shutdown();
    }

    @Test
    public void speedupMaxPerMarch_capsRequests() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"speedup_enabled\":true,"
                + "\"catch_up\":false},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]},"
                + "\"max_speedups_per_march\":1}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 0);   // 纯 25% 需 2 发，但上限 1 → 无法保证先到 → 诚实止损不发
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 120L);

        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 90L));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 80L, 679, 686));
        engine.awaitIdleForTest(3_000L);

        assertEquals("上限 1 发不足以保证先到 → 不浪费道具", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        JSONObject skip = ledgerEvent("speedup_skip");
        assertNotNull(skip);
        assertEquals("limit_prevents_ahead", skip.getString("reason"));
        engine.shutdown();
    }

    @Test
    public void speedupWithoutMission_noRequest() {
        // dry_run=true → 不发射 → 无我方驻防在途；敌加速时不得发加速请求
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":true,\"speedup_enabled\":true},"
                + "\"strategy\":{\"ally_uid\":1001}}");
        TestEngine e = new TestEngine();
        presetSpeedupStock(e, 10, 10);
        long nowS = System.currentTimeMillis() / 1000L;
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 120L, 679, 686));
        engine.awaitIdleForTest(2_000L);
        assertEquals("dry_run 无在途驻防任务", 0, engine.missionCount());

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS + 95L, 679, 686));
        engine.awaitIdleForTest(2_000L);
        assertTrue("无我方在途驻防 → 零请求", e.requested.isEmpty());
        assertNull(ledgerEvent("speedup_dry"));
        engine.shutdown();
    }

    // =======================================================================
    // 2026-10-28：415/406 自适应（生产：无编队参数取 5 英雄 → 该号上限 3 → 永远 415）
    // =======================================================================

    @SuppressWarnings("unchecked")
    private static int heroCount(Map<String, Object> formation) {
        Object raw = formation == null ? null : formation.get("hero_id");
        return raw instanceof List ? ((List<?>) raw).size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static long soldierTotal(Map<String, Object> formation) {
        Object raw = formation == null ? null : formation.get("soldier");
        if (!(raw instanceof List)) return 0L;
        long total = 0L;
        for (Object o : (List<?>) raw) {
            Map<String, Object> pair = (Map<String, Object>) o;
            total += Exec.longOf(pair.get("v"));
        }
        return total;
    }

    @Test
    public void formationOf_clampsExplicitHeroIds() {
        TestEngine e = new TestEngine();
        presetSoldiers(e);
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(null);
        cfg.heroIds = new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L));
        Map<String, Object> formation = engine().formationOf(e, cfg);
        assertNotNull(formation);
        assertEquals("hero_ids 显式路径同样夹到上限 3", 3, heroCount(formation));
    }

    @Test
    public void clampFormationHeroes_pure() {
        assertEquals(3, ServerGuardianEngine.clampFormationHeroes(
                List.of(1L, 2L, 3L, 4L, 5L), 0).size());
        assertEquals(2, ServerGuardianEngine.clampFormationHeroes(
                List.of(1L, 2L, 3L, 4L, 5L), 2).size());
        assertEquals(1, ServerGuardianEngine.clampFormationHeroes(List.of(1L), 3).size());
        assertTrue("null → 空列表", ServerGuardianEngine.clampFormationHeroes(null, 3).isEmpty());
        assertEquals("非法 id 跳过", 2, ServerGuardianEngine.clampFormationHeroes(
                List.of(0L, 1L, -5L, 2L), 3).size());
    }

    @Test
    public void scaleFormationSoldiers_pure() {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("k", 11000L);
        pair.put("v", 1000L);
        Map<String, Object> formation = new LinkedHashMap<>();
        formation.put("soldier", new ArrayList<>(List.of(pair)));
        assertEquals("100% 不缩放", 1000L,
                soldierTotal(ServerGuardianEngine.scaleFormationSoldiers(formation, 100)));
        assertEquals("75% 缩放", 750L,
                soldierTotal(ServerGuardianEngine.scaleFormationSoldiers(formation, 75)));
        assertEquals("25% 缩放", 250L,
                soldierTotal(ServerGuardianEngine.scaleFormationSoldiers(formation, 25)));
        // 缩放后不小于 1（防 0 兵 payload）
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("k", 11000L);
        one.put("v", 2L);
        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("soldier", new ArrayList<>(List.of(one)));
        assertEquals(1L, soldierTotal(ServerGuardianEngine.scaleFormationSoldiers(f2, 25)));
        assertNull(ServerGuardianEngine.scaleFormationSoldiers(null, 75));
    }

    @Test
    public void onLaunchFailure_415AdaptsAndCoolsDownTarget() {
        TestEngine e = new TestEngine();
        presetSoldiers(e);
        ServerGuardianEngine engine = engine();
        // 显式 3 英雄编队（无显式参数时已是 AUTO 编队；这里验证 415 自适应链路本身）
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(null);
        cfg.heroIds = new ArrayList<>(List.of(50001L, 50005L, 50006L));
        Map<String, Object> formation = engine.formationOf(e, cfg);
        assertEquals(3, heroCount(formation));

        long t0 = System.currentTimeMillis();
        String text = engine.onLaunchFailure(e, 415L, formation, "t:176846552", t0);
        assertNotNull("415 必须走自适应", text);
        assertTrue("文案写明 415 与降档结果，实际=" + text,
                text.contains("415") && text.contains("2 英雄"));
        assertEquals("415 后英雄上限 3→2", 2, HeroAvailability.autoHeroLimit(e));
        assertTrue("同目标进入冷却", engine.targetInCooldown("t:176846552", t0 + 1_000L));
        assertFalse("冷却到期（45s 后）", engine.targetInCooldown(
                "t:176846552", t0 + ServerGuardianEngine.LAUNCH_FAIL_COOLDOWN_MS + 1L));
        // 下一威胁按更低上限构造编队（显式 3 英雄也被 415 上限夹到 2）
        assertEquals("下一威胁英雄数降到 2", 2, heroCount(engine.formationOf(e, cfg)));
        // 成功发射后复位
        HeroAvailability.markSuccess(e);
        assertEquals("成功后复位默认上限", 3, HeroAvailability.autoHeroLimit(e));
    }

    @Test
    public void onLaunchFailure_406ScalesSoldiers() {
        TestEngine e = new TestEngine();
        presetSoldiers(e);
        ServerGuardianEngine engine = engine();
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(null);
        cfg.heroIds = new ArrayList<>(List.of(50001L, 50005L, 50006L));
        Map<String, Object> formation = engine.formationOf(e, cfg);

        String text = engine.onLaunchFailure(e, 406L, formation, "t:1", System.currentTimeMillis());
        assertNotNull(text);
        assertTrue("文案写明 406 与缩放比例，实际=" + text,
                text.contains("406") && text.contains("75%"));
        assertEquals(75, HeroAvailability.soldierScalePercent(e));
        // 下一威胁兵力按 75% 下发
        Map<String, Object> scaled = engine.formationOf(e, cfg);
        // 原始 40/30/30 夹取后 1000+900+1000=2900，×75% = 2175（每兵种向下取整、最小 1）
        assertEquals("兵力按 75% 缩放", 2175L, soldierTotal(scaled));
        HeroAvailability.markSuccess(e);
        assertEquals("成功后复位 100%", 100, HeroAvailability.soldierScalePercent(e));
    }

    @Test
    public void onLaunchFailure_otherCodesDoNotAdapt() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        long t0 = System.currentTimeMillis();
        assertNull("其它码不走自适应", engine.onLaunchFailure(e, 51L, new LinkedHashMap<>(), "t:9", t0));
        assertFalse(engine.targetInCooldown("t:9", t0 + 1_000L));
        assertEquals(3, HeroAvailability.autoHeroLimit(e));
    }

    @Test
    public void cooldownAndLogThrottlePure() {
        assertTrue(ServerGuardianEngine.inLaunchCooldown(1_100L, 1_000L));
        assertFalse(ServerGuardianEngine.inLaunchCooldown(1_000L, 1_000L));
        assertFalse(ServerGuardianEngine.inLaunchCooldown(0L, 1_000L));
        assertTrue("首次必记", ServerGuardianEngine.failureLogDue(0L, 1_000L, 60_000L));
        assertFalse("60s 内不重复", ServerGuardianEngine.failureLogDue(1_000L, 30_000L, 60_000L));
        assertTrue("超窗口可记", ServerGuardianEngine.failureLogDue(1_000L, 61_000L, 60_000L));
        assertTrue("时钟回拨按可记", ServerGuardianEngine.failureLogDue(5_000L, 1_000L, 60_000L));
    }

    // =======================================================================
    // 2026-10-28 用户需求：AUTO 最强空闲盾/矛/弓 + 50/20/30 按出征上限带兵
    // =======================================================================

    /** 英雄目录（notify_hero_init）：50001盾/50005矛/50006弓 为各职业最强。 */
    private static void presetHeroCatalog(TestEngine e) {
        List<Object> data = new ArrayList<>();
        data.add(heroRow(50000L, 50, 10, 1000));    // 盾较弱
        data.add(heroRow(50001L, 80, 20, 5000));    // 盾最强
        data.add(heroRow(50002L, 60, 12, 2000));    // 矛较弱
        data.add(heroRow(50005L, 90, 25, 9000));    // 矛最强
        data.add(heroRow(50003L, 70, 15, 3000));    // 弓较弱
        data.add(heroRow(50006L, 100, 30, 12000));  // 弓最强
        e.notifies.put("notify_hero_init", map("data", data));
    }

    private static Map<String, Object> heroRow(long id, long level, long star, long power) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("level", level);
        m.put("lev", level);
        m.put("star", star);
        m.put("power", power);
        return m;
    }

    /** 三兵种库存（各 count 份，最高档 11000/21000/31000）。 */
    private static void presetSoldierStock(TestEngine e, long count) {
        Map<String, Object> soldier = new LinkedHashMap<>();
        for (long id : new long[]{11000L, 21000L, 31000L}) {
            soldier.put(id + ".0", new LinkedHashMap<>(Map.of("id", id, "count", count)));
        }
        e.notifies.put("notify_soldier_init", map("soldier", soldier));
    }

    /** 指挥中心 331 级 20 → MarchCapacity 已同步（出征容量可算）。 */
    private static void presetCommandCenter(TestEngine e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bid", 331L);
        row.put("base", map("lv", 20L));
        e.notifies.put("notify_shelter_info", map("building_info", List.of(row)));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> heroIdsIn(Map<String, Object> formation) {
        List<Long> out = new ArrayList<>();
        Object raw = formation == null ? null : formation.get("hero_id");
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            Map<String, Object> pair = (Map<String, Object>) o;
            out.add(Exec.longOf(pair.get("v")));
        }
        return out;
    }

    /** 兵种兵力 [盾,矛,弓]（按 soldier id/10000 归并）。 */
    @SuppressWarnings("unchecked")
    private static long[] soldierTotals(Map<String, Object> formation) {
        long[] out = new long[3];
        Object raw = formation == null ? null : formation.get("soldier");
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            Map<String, Object> pair = (Map<String, Object>) o;
            long id = Exec.longOf(pair.get("k"));
            long v = Exec.longOf(pair.get("v"));
            int line = (int) (id / 10000L);
            if (line >= 1 && line <= 3) out[line - 1] += v;
        }
        return out;
    }

    private static Map<String, Object> map(Object key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(String.valueOf(key), value);
        return m;
    }

    @Test
    public void guardianAutoRatio_matchesUserRequirement() {
        Map<String, Long> ratio = ServerGuardianEngine.guardianAutoRatio();
        assertEquals(Long.valueOf(50L), ratio.get("infantry"));
        assertEquals(Long.valueOf(20L), ratio.get("lancer"));
        assertEquals(Long.valueOf(30L), ratio.get("marksman"));
        assertEquals("与网页版默认自动比例表一致",
                FormationCatalog.DEFAULT_AUTOMATIC_SETTINGS[0], ratio.get("infantry").intValue());
    }

    @Test
    public void heroProfessionSummary_pure() {
        assertEquals("盾1/矛1/弓1", ServerGuardianEngine.heroProfessionSummary(
                List.of(50001L, 50005L, 50006L)));
        assertEquals("盾2/矛0/弓0", ServerGuardianEngine.heroProfessionSummary(
                List.of(50000L, 50001L)));
        assertEquals("盾0/矛0/弓0", ServerGuardianEngine.heroProfessionSummary(List.of(999L)));
        assertEquals("盾0/矛0/弓0", ServerGuardianEngine.heroProfessionSummary(null));
    }

    @Test
    public void formationOf_noExplicitParams_usesStrongestFreeHeroes() {
        TestEngine e = new TestEngine();
        presetHeroCatalog(e);
        presetSoldierStock(e, 100_000L);
        presetCommandCenter(e);
        ServerGuardianEngine engine = engine();

        Map<String, Object> formation = engine.formationOf(e, ServerGuardianEngine.parseConfig(null));

        assertNotNull("无显式参数 → AUTO 编队应可用", formation);
        List<Long> heroes = heroIdsIn(formation);
        assertEquals("盾/矛/弓各一名", 3, heroes.size());
        assertTrue("最强盾 50001，实际=" + heroes, heroes.contains(50001L));
        assertTrue("最强矛 50005，实际=" + heroes, heroes.contains(50005L));
        assertTrue("最强弓 50006，实际=" + heroes, heroes.contains(50006L));
        assertFalse("不再取客户端编队英雄", heroes.contains(50000L) || heroes.contains(50002L));
    }

    @Test
    public void guardianAutoFormation_allocates50_20_30WithinCapacity() {
        TestEngine e = new TestEngine();
        presetHeroCatalog(e);
        presetSoldierStock(e, 100_000L);
        presetCommandCenter(e);
        ServerGuardianEngine engine = engine();

        long capacity = FormationCatalog.capacityForHeroes(e,
                List.of(50001L, 50005L, 50006L));
        assertTrue("测试夹具应让出征容量已知（>0），实际=" + capacity, capacity > 0L);

        ServerGuardianEngine.AutoFormation auto = engine.guardianAutoFormation(e);
        assertNotNull(auto);
        long[] soldiers = soldierTotals(auto.payload);
        long total = soldiers[0] + soldiers[1] + soldiers[2];
        long stockSum = 300_000L;
        assertEquals("总数=min(出征上限,库存)", Math.min(capacity, stockSum), total);
        assertRatio(soldiers, total);
        assertTrue("摘要含英雄职业数与比例，实际=" + auto.summary,
                auto.summary.contains("盾1/矛1/弓1") && auto.summary.contains("比例50:20:30"));
        assertTrue("摘要含容量，实际=" + auto.summary, auto.summary.contains("容量=" + capacity));
    }

    /** 断言三兵种比例≈50/20/30（最大余数分配允许 ±2 的取整误差）。 */
    private static void assertRatio(long[] soldiers, long total) {
        assertTrue("总兵力应 >0", total > 0L);
        long infantry = total * 50L / 100L;
        long lancer = total * 20L / 100L;
        long marksman = total * 30L / 100L;
        assertTrue("盾比例≈50%，实际 " + soldiers[0] + "/" + total,
                Math.abs(soldiers[0] - infantry) <= 2L);
        assertTrue("矛比例≈20%，实际 " + soldiers[1] + "/" + total,
                Math.abs(soldiers[1] - lancer) <= 2L);
        assertTrue("弓比例≈30%，实际 " + soldiers[2] + "/" + total,
                Math.abs(soldiers[2] - marksman) <= 2L);
    }

    @Test
    public void guardianAutoFormation_capacityUnknownFallsBack() {
        TestEngine e = new TestEngine();
        presetHeroCatalog(e);
        presetSoldierStock(e, 100_000L);   // 无 shelter → 容量未知
        ServerGuardianEngine engine = engine();

        ServerGuardianEngine.AutoFormation auto = engine.guardianAutoFormation(e);

        assertNotNull("容量未知不应崩溃/为空", auto);
        long[] soldiers = soldierTotals(auto.payload);
        long total = soldiers[0] + soldiers[1] + soldiers[2];
        assertEquals("容量未知 → 兜底 min(库存,5万)", 50_000L, total);
        assertRatio(soldiers, total);
        assertTrue("摘要注明容量未知兜底，实际=" + auto.summary,
                auto.summary.contains("未知(兜底库存)"));
    }

    @Test
    public void guardianAutoFormation_noHeroCatalogReturnsNull() {
        TestEngine e = new TestEngine();
        presetSoldierStock(e, 1000L);
        presetCommandCenter(e);
        ServerGuardianEngine engine = engine();

        assertNull("英雄目录缺失 → 返回 null（上层 skip_no_formation 可读原因）",
                engine.guardianAutoFormation(e));
        assertNull("formationOf 同步返回 null",
                engine.formationOf(e, ServerGuardianEngine.parseConfig(null)));
    }

    @Test
    public void guardianAutoFormation_415LimitReducesHeroCount() {
        TestEngine e = new TestEngine();
        presetHeroCatalog(e);
        presetSoldierStock(e, 100_000L);
        presetCommandCenter(e);
        ServerGuardianEngine engine = engine();

        HeroAvailability.noteHeroCountRejected(e, 3);   // 415：3 → 2
        ServerGuardianEngine.AutoFormation auto = engine.guardianAutoFormation(e);

        assertNotNull(auto);
        assertEquals("415 降档后 AUTO 英雄数同步降为 2", 2, heroIdsIn(auto.payload).size());
        HeroAvailability.markSuccess(e);
    }

    @Test
    public void formationOf_explicitHeroIdsKeepsManualBehavior() {
        TestEngine e = new TestEngine();
        presetHeroCatalog(e);
        presetSoldierStock(e, 100_000L);
        presetCommandCenter(e);
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(null);
        cfg.heroIds = new ArrayList<>(List.of(50002L, 50003L));

        Map<String, Object> formation = engine().formationOf(e, cfg);

        assertNotNull(formation);
        List<Long> heroes = heroIdsIn(formation);
        assertEquals("显式 hero_ids 保持原行为", 2, heroes.size());
        assertTrue(heroes.contains(50002L) && heroes.contains(50003L));
        assertFalse("显式路径不混入 AUTO 最强英雄", heroes.contains(50001L));
    }

    // ================= 批次 C A4：防守战报判定（只记账/告警，绝不撤防） =================

    /** notify_new_mail_syn 最小样本（mail_cover_info 形状，与 WarNotify 解析口径一致）。 */
    private static Map<String, Object> mailPush(long first, long second, long mailId, String title) {
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("first_type", first);
        cover.put("second_type", second);
        cover.put("mailid_new", mailId);
        cover.put("title", title);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("mail_cover_info", cover);
        return c;
    }

    private int ledgerEventCount(String event) {
        String raw = state.getString(ServerGuardianEngine.ledgerKey(FPID, SID));
        if (raw == null) return 0;
        JSONArray arr;
        try {
            arr = new JSONArray(raw);
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && event.equals(o.optString("ev", ""))) n++;
        }
        return n;
    }

    @Test
    public void mailReport_winLossUnknown_recordedWithoutMissionChanges() {
        accountWithParams("{\"sub\":{\"enabled\":true},\"strategy\":{\"dry_run\":true}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        int missionsBefore = engine.missionCount();
        int threatsBefore = engine.threatCount();

        // 胜：first=30 second=5 → report_win
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 5L, 9001L, "防守胜利"));
        assertEquals("胜战报记 report_win", 1, ledgerEventCount("report_win"));
        assertTrue(ledgerEventCount("report_loss") == 0);

        // 败：first=30 second=6 → report_loss + mod 文案
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 6L, 9002L, "防守失败"));
        assertEquals("败战报记 report_loss", 1, ledgerEventCount("report_loss"));
        String mod = state.getString(ServerGuardianEngine.modKey(FPID, SID));
        assertNotNull("败战报应写 mod 文案", mod);
        assertTrue("mod 应含“防守战报：败”", mod.contains("防守战报：败"));

        // 未收录：second=1 → report_unknown，且每分钟最多 1 条（连续两条只计 1）
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 1L, 9003L, "未校准"));
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 1L, 9004L, "未校准2"));
        assertEquals("未收录码每分钟最多 1 条 ledger", 1, ledgerEventCount("report_unknown"));

        // 非战报 first（16=普通邮件）忽略
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(16L, 5L, 9005L, "其它邮件"));
        assertEquals("非战报 first 不记 report_win", 1, ledgerEventCount("report_win"));
        assertEquals(0, ledgerEventCount("report_loss") - 1);

        // 铁律：战报只记账——不撤防、不改 missions、不发任何游戏协议
        assertEquals("战报不得改变 missions", missionsBefore, engine.missionCount());
        assertEquals("战报不得改变威胁表", threatsBefore, engine.threatCount());
        assertTrue("战报不得发任何游戏协议（撤防/召回/加速）", e.requested.isEmpty());
    }

    @Test
    public void mailReport_reportCodesOverride_turnsWinIntoUnknown() {
        JSONObject strategy = new JSONObject();
        strategy.put("dry_run", true);
        JSONObject byFirst = new JSONObject();
        byFirst.put("30", new JSONObject().put("5", "unknown"));
        strategy.put("report_codes", new JSONObject().put("by_first", byFirst).toString());
        JSONObject guardian = new JSONObject();
        guardian.put("sub", new JSONObject().put("enabled", true));
        guardian.put("strategy", strategy);
        accountWithParams(guardian.toString());

        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 5L, 9101L, "校准为未知"));
        assertEquals("params 覆盖：已校准未知 → 不判胜", 0, ledgerEventCount("report_win"));
        assertEquals(1, ledgerEventCount("report_unknown"));
        assertEquals(0, engine.missionCount());
        assertTrue(e.requested.isEmpty());
    }

    @Test
    public void mailReport_duplicateMailId_deduped() {
        accountWithParams("{\"sub\":{\"enabled\":true},\"strategy\":{\"dry_run\":true}}");
        TestEngine e = new TestEngine();
        ServerGuardianEngine engine = engine();
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 5L, 9201L, "重复"));
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 5L, 9201L, "重复"));
        assertEquals("同 mailId 重复推送只记 1 次", 1, ledgerEventCount("report_win"));
    }

    // =======================================================================
    // 批次 D：追赶加速（catch-up，2026-10-31）
    // 核心口径：驻防成功优先；我方晚到就主动追到先到；卡秒只是省道具。
    // =======================================================================

    /** 追赶默认开；仅 formation/ally 配置，不依赖 speedup_enabled/P1。 */
    private static final String CATCHUP_CFG =
            "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":true},"
                    + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}";

    private static final String CATCHUP_CFG_OFF =
            "{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":false},"
                    + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}";

    /** 等待追赶链跑完（轮次有 250ms 间隔；同时等 guardExec 队列清空）。 */
    private void awaitCatchup(ServerGuardianEngine engine, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            engine.awaitIdleForTest(200);
            if (engine.catchupCount() == 0) return;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    @Test
    public void parseConfig_catchupKeys_defaultOn_cappedAndEnvOff() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertTrue("catch_up 默认开（核心是驻防成功）", def.catchUp);
        assertEquals("catch_up_margin_s 默认 2s", 2L, def.catchUpMarginS);
        assertEquals("catch_up_max_rounds 默认 20", 20, def.catchUpMaxRounds);
        assertTrue(def.describe().contains("catch_up=true"));

        ServerGuardianEngine.Config off = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"sub\":{\"catch_up\":false},"
                        + "\"strategy\":{\"catch_up_margin_s\":3,\"catch_up_max_rounds\":5}}"));
        assertFalse(off.catchUp);
        assertEquals(3L, off.catchUpMarginS);
        assertEquals(5, off.catchUpMaxRounds);

        ServerGuardianEngine.Config capped = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"catch_up_max_rounds\":\"999\"}"));
        assertEquals("轮数硬上限 100（防配置笔误风暴）", 100, capped.catchUpMaxRounds);

        System.setProperty("VAULT_GUARDIAN_CATCHUP", "off");
        try {
            assertFalse("env off 可回旧行为",
                    ServerGuardianEngine.parseConfig(new JSONObject("{\"sub\":{\"enabled\":true}}")).catchUp);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_CATCHUP");
        }
    }

    @Test
    public void catchup_lateLaunch_oneShotReachesAhead_withLedger() {
        accountWithParams(CATCHUP_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 10);
        long nowS = System.currentTimeMillis() / 1000L;
        // 我方 130s vs 敌 120s（晚 10s）→ 一发 25% 后 ~97.5s，先到
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 95L));
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 130L);
        assertEquals("驻防已在册", 1, engine.missionCount());

        awaitCatchup(engine, 3_000L);

        assertEquals("只发 1 发即先到（25% 够用不升 50%）", 1,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        Map<String, Object> args = lastArgsOf(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP);
        assertNotNull(args);
        assertEquals(555L, ((Number) args.get("id")).longValue());
        assertEquals(301900L, ((Number) args.get("itemid")).longValue());
        assertEquals("默认零钻石", 0, requestCount(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY));

        JSONObject start = ledgerEvent("catchup_start");
        assertNotNull("必须有 catchup_start 账本", start);
        assertTrue("start note 含我方/敌方 end 与晚到差",
                start.optString("note").contains("我方 end=130")
                        && start.optString("note").contains("敌 end=120")
                        || start.optString("note").contains("晚 10s"));
        JSONObject round = ledgerEvent("catchup_round");
        assertNotNull("必须有 catchup_round 账本", round);
        assertTrue("round note 含 before/after/结果", round.optString("note").contains("追赶第1发"));
        JSONObject done = ledgerEvent("catchup_done");
        assertNotNull("先到必须记 catchup_done", done);
        assertTrue(done.optString("note").contains("追赶成功"));
        assertEquals("追赶链已结束", 0, engine.catchupCount());
        engine.shutdown();
    }

    @Test
    public void catchup_noItem_giveupNoItem_zeroRequests() {
        accountWithParams(CATCHUP_CFG);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 0);          // 道具耗尽
        long nowS = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 95L));
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 130L);

        awaitCatchup(engine, 2_000L);

        assertEquals("无道具 → 零加速请求", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        assertEquals("钻石默认关 → 零 quick_buy", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY));
        JSONObject giveup = ledgerEvent("catchup_giveup");
        assertNotNull(giveup);
        assertTrue("原因应为 no_item：" + giveup.optString("note"),
                giveup.optString("note").contains("no_item"));
        engine.shutdown();
    }

    @Test
    public void catchup_disabled_zeroRequestsOldBehavior() {
        accountWithParams(CATCHUP_CFG_OFF);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 10);
        long nowS = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 95L));
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 130L);

        awaitCatchup(engine, 1_000L);

        assertEquals("catch_up=false → 零加速请求（旧行为）", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        assertNull("不产生 catchup_start", ledgerEvent("catchup_start"));
        assertEquals(0, engine.catchupCount());
        engine.shutdown();
    }

    @Test
    public void catchup_enemyLandedOver30s_noCatchup() {
        long nowS = System.currentTimeMillis() / 1000L;
        assertTrue("敌已落地 40s → 过期", ServerGuardianEngine.catchupExpired(nowS - 40L, nowS));
        assertFalse("敌落地 10s → 仍在宽限", ServerGuardianEngine.catchupExpired(nowS - 10L, nowS));
        assertFalse("无 endtime → 不判过期", ServerGuardianEngine.catchupExpired(0L, nowS));

        // 先以 catch_up=false 发射（避免 0ms 首轮先跑掉），再把敌 end 推到过去并开启追赶触发
        accountWithParams(CATCHUP_CFG_OFF);
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 10);
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 95L));
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 130L);
        assertEquals(1, engine.missionCount());

        accountWithParams(CATCHUP_CFG);
        engine.reloadConfig(true);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_UPDATE,
                attackPush(13080L, ENEMY_UID, 1001L, nowS - 40L, 679, 686));
        engine.awaitIdleForTest(1_000L);

        assertEquals("敌已落地 >30s → 不追（零加速请求）", 0,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        assertEquals(0, engine.catchupCount());
        engine.shutdown();
    }

    @Test
    public void catchup_maxRounds_giveupRounds() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":true},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]},"
                + "\"catch_up_max_rounds\":2}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 10, 10);
        long nowS = System.currentTimeMillis() / 1000L;
        // 两发都只前移到「仍晚」：130→129→128（敌 120，margin 2 → 需 ≤118）
        java.util.Deque<GameMessage> q = new java.util.ArrayDeque<>();
        q.add(speedupOk(nowS + 129L));
        q.add(speedupOk(nowS + 128L));
        e.queues.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, q);
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 130L);

        awaitCatchup(engine, 3_000L);

        assertEquals("max_rounds=2 → 最多 2 发", 2,
                requestCount(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP));
        JSONObject giveup = ledgerEvent("catchup_giveup");
        assertNotNull(giveup);
        assertTrue("原因应为 rounds：" + giveup.optString("note"),
                giveup.optString("note").contains("rounds"));
        engine.shutdown();
    }

    @Test
    public void catchup_diamondEnabled_buysThenUses50Percent() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false,\"catch_up\":true,"
                + "\"diamond_speedup_enabled\":true},"
                + "\"strategy\":{\"ally_uid\":1001,\"formation\":{\"hero_ids\":[1]}}}");
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        presetSpeedupStock(e, 0, 0);
        long nowS = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, msg(0));
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, speedupOk(nowS + 95L));
        ServerGuardianEngine engine = launchForSpeedup(e, nowS + 120L, nowS + 130L);

        awaitCatchup(engine, 3_000L);

        List<String> seq = new ArrayList<>();
        for (String p : e.requested) {
            if (ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY.equals(p)
                    || ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP.equals(p)) seq.add(p);
        }
        assertEquals("钻石路径=先买后加速；实际=" + seq, 2, seq.size());
        assertEquals(ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY, seq.get(0));
        assertEquals(ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP, seq.get(1));
        Map<String, Object> buyArgs = lastArgsOf(e, ServerGuardianLauncher.PROTO_SHOP_QUICK_BUY);
        assertNotNull(buyArgs);
        assertEquals("批次 K：优先买 25% 道具 301900", 301900L,
                ((Number) buyArgs.get("id")).longValue());
        Map<String, Object> sp = lastArgsOf(e, ServerGuardianLauncher.PROTO_WORLD_MARCH_SPEEDUP);
        assertNotNull(sp);
        assertEquals("买入 301900 立即使用", 301900L, ((Number) sp.get("itemid")).longValue());
        JSONObject buy = ledgerEvent("speedup_diamond_buy");
        assertNotNull(buy);
        assertEquals("catchup", buy.getString("reason"));
        assertEquals(301900L, buy.getLong("itemid"));
        assertNotNull(ledgerEvent("catchup_done"));
        engine.shutdown();
    }

    // =======================================================================
    // 批次 K：钻石加速用户开关 + 25%/50% 经济换算
    // =======================================================================

    @Test
    public void parseConfig_diamondHardOffSemantics() {
        try {
            System.clearProperty("VAULT_GUARDIAN_DIAMOND");
            ServerGuardianEngine.Config userOn = ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"diamond_speedup_enabled\":true}}"));
            assertTrue("未设 env：用户开=生效", userOn.diamondSpeedupEnabled);
            assertFalse(userOn.diamondHardOff);

            ServerGuardianEngine.Config userOff = ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"enabled\":true}}"));
            assertFalse("未设 env：用户关=关（默认）", userOff.diamondSpeedupEnabled);

            System.setProperty("VAULT_GUARDIAN_DIAMOND", "1");
            assertFalse("env=1 不强制开（以用户为准）", ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"enabled\":true}}")).diamondSpeedupEnabled);
            assertTrue("env=1 + 用户开 → 生效", ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"diamond_speedup_enabled\":true}}"))
                    .diamondSpeedupEnabled);

            System.setProperty("VAULT_GUARDIAN_DIAMOND", "0");
            ServerGuardianEngine.Config hard = ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"diamond_speedup_enabled\":true}}"));
            assertFalse("env=0 → 硬关（忽略用户开）", hard.diamondSpeedupEnabled);
            assertTrue(hard.diamondHardOff);
            assertTrue(hard.describe().contains("diamond_hard_off=true"));

            System.setProperty("VAULT_GUARDIAN_DIAMOND", "off");
            assertTrue(ServerGuardianEngine.parseConfig(
                    new JSONObject("{\"sub\":{\"diamond_speedup_enabled\":true}}")).diamondHardOff);
        } finally {
            System.clearProperty("VAULT_GUARDIAN_DIAMOND");
        }
    }

    @Test
    public void burstMode_thresholds() {
        assertFalse(ServerGuardianEngine.burstMode(3, 4));
        assertTrue("1s 内 ≥4 次 → 梭哈", ServerGuardianEngine.burstMode(4, 4));
        assertTrue("累计 >4 次 → 梭哈", ServerGuardianEngine.burstMode(0, 5));
        assertFalse(ServerGuardianEngine.burstMode(0, 4));
    }

    @Test
    public void pickSpeedupStep_25PreferredAndStacked() {
        long margin = 2_000L;
        // 130s vs 120s：一发 25% 后 ≈97.5s，先到 → 25% 优先
        GuardianSpeedupPlanner.Step s1 = ServerGuardianEngine.pickSpeedupStep(
                130_000L, 120_000L, 10, 10, 0, 0, 0, 0, 0, false, margin);
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, s1.action);
        assertEquals(301900L, s1.itemId);

        // 200s vs 100s：2 发 25%（≈112.5s→84.4s）可达；50% 库存也有，仍应选 25%
        GuardianSpeedupPlanner.Step s2 = ServerGuardianEngine.pickSpeedupStep(
                200_000L, 100_000L, 10, 10, 0, 0, 0, 0, 0, false, margin);
        assertEquals("2×25% 更便宜，不升 50%", GuardianSpeedupPlanner.Action.USE_25, s2.action);
    }

    @Test
    public void pickSpeedupStep_burstForces25EvenWhenUpgradeWouldBeUsed() {
        long margin = 2_000L;
        // 无 25% 库存时普通 planner 会用 50%；梭哈模式有 25% 就必须连续 25%
        GuardianSpeedupPlanner.Step normal = GuardianSpeedupPlanner.next(
                new GuardianSpeedupPlanner.Input(2_000_000L, 100_000L, margin, 0, 10,
                        0, 0, 0, false));
        assertEquals(GuardianSpeedupPlanner.Action.USE_50, normal.action);

        GuardianSpeedupPlanner.Step burst = ServerGuardianEngine.pickSpeedupStep(
                2_000_000L, 100_000L, 10, 10, 4, 4, 0, 0, 0, false, margin);
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, burst.action);
        assertEquals("burst_25", burst.reasonCode);

        // 累计 >4 也触发（1s 窗口 0 次）
        GuardianSpeedupPlanner.Step cum = ServerGuardianEngine.pickSpeedupStep(
                2_000_000L, 100_000L, 10, 10, 0, 5, 0, 0, 0, false, margin);
        assertEquals(GuardianSpeedupPlanner.Action.USE_25, cum.action);

        // 梭哈无 25 有 50 → 用 50
        GuardianSpeedupPlanner.Step use50 = ServerGuardianEngine.pickSpeedupStep(
                2_000_000L, 100_000L, 0, 10, 4, 4, 0, 0, 0, false, margin);
        assertEquals(GuardianSpeedupPlanner.Action.USE_50, use50.action);

        // 梭哈无道具：钻石开→NEED_DIAMOND；钻石关→诚实止损
        GuardianSpeedupPlanner.Step needDia = ServerGuardianEngine.pickSpeedupStep(
                2_000_000L, 100_000L, 0, 0, 4, 4, 0, 0, 0, true, margin);
        assertEquals(GuardianSpeedupPlanner.Action.NEED_DIAMOND, needDia.action);
        GuardianSpeedupPlanner.Step none = ServerGuardianEngine.pickSpeedupStep(
                2_000_000L, 100_000L, 0, 0, 4, 4, 0, 0, 0, false, margin);
        assertEquals(GuardianSpeedupPlanner.Action.NONE, none.action);
        assertEquals("stock_insufficient", none.reasonCode);

        // 上限优先于梭哈
        GuardianSpeedupPlanner.Step limit = ServerGuardianEngine.pickSpeedupStep(
                2_000_000L, 100_000L, 10, 0, 4, 4, 2, 2, 0, true, margin);
        assertEquals(GuardianSpeedupPlanner.Action.NONE, limit.action);
        assertEquals("burst_march_limit", limit.reasonCode);
    }

    @Test
    public void diamondBuyItem_prefers25WhenReachableElse50() {
        long margin = 2_000L;
        GuardianSpeedupPlanner.Step need = ServerGuardianEngine.pickSpeedupStep(
                130_000L, 120_000L, 0, 0, 0, 0, 0, 0, 0, true, margin);
        assertEquals(GuardianSpeedupPlanner.Action.NEED_DIAMOND, need.action);
        assertEquals("再买 1 颗 25% 可达 → 优先 301900", 301900L,
                ServerGuardianEngine.diamondBuyItem(need, false, 130_000L, 120_000L,
                        0, 0, 0, 0, 0, true, margin));

        GuardianSpeedupPlanner.Step need50 = ServerGuardianEngine.pickSpeedupStep(
                120_000L, 80_000L, 0, 0, 0, 0, 0, 0, 0, true, margin);
        assertEquals(GuardianSpeedupPlanner.Action.NEED_DIAMOND, need50.action);
        assertEquals("1 颗 25% 不达、2 颗 25% 可达 → 仍优先 301900（800 钻 vs 2000 钻）", 301900L,
                ServerGuardianEngine.diamondBuyItem(need50, false, 120_000L, 80_000L,
                        0, 0, 0, 0, 0, true, margin));

        // 2×25% 也来不及（160→90，敌 90/margin 2），但 1×50% 可先到（80 ≤ 88）→ 才买 301901
        assertEquals("2×25% 不达、1×50% 可达 → 买 301901", 301901L,
                ServerGuardianEngine.diamondBuyItem(need50, false, 160_000L, 90_000L,
                        0, 0, 0, 0, 0, true, margin));
        // 预算只够 1 颗时不能按 2×25% 规划：1×25% 不达 → 直接买 50%
        assertEquals("预算只剩 1 颗 → 1×25% 不达则买 301901", 301901L,
                ServerGuardianEngine.diamondBuyItem(need50, false, 120_000L, 80_000L,
                        0, 0, 0, 0, 0, true, margin, 1));
        assertEquals("梭哈必买 301900", 301900L,
                ServerGuardianEngine.diamondBuyItem(need50, true, 120_000L, 80_000L,
                        0, 0, 0, 0, 0, true, margin));
    }

    @Test
    public void recordEnemyCompression_countsBurstAndTotal() {
        accountWithParams("{\"sub\":{\"enabled\":true,\"dry_run\":false}}");
        ServerGuardianEngine engine = engine();
        long nowMs = System.currentTimeMillis();
        long nowS = nowMs / 1000L;
        // 4 次 −50% 压缩（endtime 剩余减半）→ 1s 窗口 4 次 + 累计 4
        for (int i = 0; i < 4; i++) {
            ServerGuardianEngine.Threat prev = new ServerGuardianEngine.Threat(
                    9001L, 9L, 102L, 0L, 0, 0, false, nowS + 100L - i, nowMs);
            ServerGuardianEngine.Threat cur = new ServerGuardianEngine.Threat(
                    9001L, 9L, 102L, 0L, 0, 0, false, nowS + 50L - i, nowMs);
            engine.recordEnemyCompression(prev, cur, nowMs);
        }
        assertEquals(4, engine.enemy50TotalFor(9001L));
        assertEquals(4, engine.enemy50BurstCount(9001L, nowMs));
        assertTrue(ServerGuardianEngine.burstMode(
                engine.enemy50BurstCount(9001L, nowMs), engine.enemy50TotalFor(9001L)));

        // 25% 压缩（剩余 ×0.75）不计入 50%
        ServerGuardianEngine.Threat p2 = new ServerGuardianEngine.Threat(
                9002L, 9L, 102L, 0L, 0, 0, false, nowS + 100L, nowMs);
        ServerGuardianEngine.Threat c2 = new ServerGuardianEngine.Threat(
                9002L, 9L, 102L, 0L, 0, 0, false, nowS + 75L, nowMs);
        engine.recordEnemyCompression(p2, c2, nowMs);
        assertEquals(0, engine.enemy50TotalFor(9002L));
    }
}
