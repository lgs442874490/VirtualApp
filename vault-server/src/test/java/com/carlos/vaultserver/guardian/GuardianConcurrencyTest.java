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
import static org.junit.Assert.assertTrue;

/**
 * 批次 C-A6/D：守护多目标并发（批次 D 默认 3，上限 6）+ 同批威胁按 ETA（endSec）升序优先。
 *
 * <p>覆盖：配置解析与 clamp、max_concurrent=1 时第二威胁 skip_busy、max_concurrent=2 时双发射、
 * 同目标仍被队列/互斥拦住、同批多威胁按 endSec 升序下单。
 */
public class GuardianConcurrencyTest {

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

    private static Map<String, Object> march(long id, long targetUid, long endSec, int x, int y) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", ENEMY_UID);
        m.put("type", 2L);
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

    private static void presetQueueCap(TestEngine e, int capacity) {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("capacity", (long) capacity);
        Map<String, Object> wpi = new LinkedHashMap<>();
        wpi.put("world", world);
        e.notifies.put("notify_world_player_init", wpi);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", 0);
        detail.put("marchs", new ArrayList<>());
        e.responses.put(ServerGuardianEngine.PROTO_MARCH_DETAIL, msg(0));
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

    /** notify_new_mail_syn 最小样本（first_type=30 战斗/防守战报）。 */
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

    /** 立即发射（timing=false）+ 指定 max_concurrent。 */
    private static String liveCfg(int maxConcurrent) {
        return "{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"timing\":false,\"max_concurrent\":" + maxConcurrent + ","
                + "\"formation\":{\"hero_ids\":[1]}}}";
    }

    // ---------------- 配置 ----------------

    @Test
    public void parseConfig_maxConcurrent_defaultAndClamp() {
        ServerGuardianEngine.Config def = ServerGuardianEngine.parseConfig(
                new JSONObject("{\"sub\":{\"enabled\":true}}"));
        assertEquals("默认 6（守护账号 6 个队列全可用）", 6, def.maxConcurrent);
        assertTrue(def.describe().contains("max_concurrent=6"));

        assertEquals(2, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":2}}")).maxConcurrent);
        assertEquals("上限 6", 6, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":9}}")).maxConcurrent);
        assertEquals("下限 1", 1, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":0}}")).maxConcurrent);
        assertEquals("字符串数字兼容", 2, ServerGuardianEngine.parseConfig(
                new JSONObject("{\"strategy\":{\"max_concurrent\":\"2\"}}")).maxConcurrent);
    }

    // ---------------- 行为 ----------------

    @Test
    public void maxConcurrentOne_secondThreatSkippedBusy() {
        accountWithParams(liveCfg(1));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686),
                        march(13081L, OWN_UID + 1, now + 121L, 680, 687)));
        engine.awaitIdleForTest(3_000L);

        assertEquals("默认并发 1：只发 1 队", 1, engine.missionCount());
        assertEquals("只发 1 次 world_march",
                1, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertTrue("第二威胁 skip_busy", ledgerCount("skip_busy") >= 1);
    }

    @Test
    public void maxConcurrentTwo_bothThreatsLaunch() {
        accountWithParams(liveCfg(2));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686),
                        march(13081L, OWN_UID + 1, now + 121L, 680, 687)));
        engine.awaitIdleForTest(3_000L);

        assertEquals("max_concurrent=2：两目标各 1 队", 2, engine.missionCount());
        assertEquals(2, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
    }

    @Test
    public void sameTargetDefaultConcurrency_doesNotLaunchSecondGarrison() {
        // 2026-11-03 审查 P1-2：同目标第二个威胁不得再发一队（默认 6 队列）
        // （旧实现会 missions.put 覆盖第一支 Mission，导致第一队失联）。
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686),
                        march(13081L, OWN_UID, now + 121L, 679, 686)));
        engine.awaitIdleForTest(3_000L);

        assertEquals("同目标只保留 1 个 Mission", 1, engine.missionCount());
        assertEquals("同目标只发 1 队驻防", 1,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertTrue("第二威胁应记 skip_existing_garrison",
                ledgerCount("skip_existing_garrison") >= 1);
        engine.shutdown();
    }

    @Test
    public void enemyRecall_otherIncoming_keepsGarrison_thenRecallsWhenClear() {
        // 2026-11-03 审查 P1-1（规则 3.4）：同目标仍有第二路在途时，敌召回不得撤防。
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686),
                        march(13081L, OWN_UID, now + 121L, 679, 686)));
        engine.awaitIdleForTest(3_000L);
        assertEquals(1, engine.missionCount());

        // 第一路召回：第二路仍在途 → 保留驻防
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13080L));
        engine.awaitIdleForTest(3_000L);
        assertEquals("仍有后续在途 → 不撤防", 0,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals(1, engine.missionCount());
        assertTrue("应记 threat_recalled_keep_incoming",
                ledgerCount("threat_recalled_keep_incoming") >= 1);

        // 第二路也召回：目标无后续在途 → 撤防
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13081L));
        engine.awaitIdleForTest(3_000L);
        assertEquals("无后续在途 → 撤防", 1,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals("撤防成功后清任务", 0, engine.missionCount());
        engine.shutdown();
    }

    @Test
    public void defeatedGarrison_allowsNewGarrisonForNextWave() {
        // 2026-11-03 审查 P1-2 补强：战报判负后不得让残留 Mission 挡住同目标补防。
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686)));
        engine.awaitIdleForTest(3_000L);
        assertEquals(1, engine.missionCount());

        // first_type=30 second_type=6 = 防守失败 → Mission.defeated
        engine.onPush(e, ServerGuardianEngine.PROTO_NEW_MAIL,
                mailPush(30L, 6L, 9001L, "防守失败"));
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13081L, OWN_UID, now + 130L, 679, 686)));
        engine.awaitIdleForTest(3_000L);

        assertEquals("战败后应允许同目标补驻防", 2,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertTrue("应记 garrison_defeated_clear",
                ledgerCount("garrison_defeated_clear") >= 1);
    }

    @Test
    public void noEtaSecondThreat_keepsGarrisonOnFirstRecall() {
        // 2026-11-03 审查 P1-1 补强：第二路 ETA 尚未补齐（endtime=0）时保守视为在途，不撤防。
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686)));
        engine.awaitIdleForTest(3_000L);

        Map<String, Object> noEta = march(13081L, OWN_UID, now + 121L, 679, 686);
        noEta.remove("endtime");
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(noEta));
        engine.awaitIdleForTest(3_000L);

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13080L));
        engine.awaitIdleForTest(3_000L);
        assertEquals("第二路无 ETA 也视为在途 → 不撤防", 0,
                countProto(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        assertEquals(1, engine.missionCount());
        assertTrue(ledgerCount("threat_recalled_keep_incoming") >= 1);
    }

    @Test
    public void recallMarchNotFound_clearsGarrison() {
        // 2026-11-03 审查 P1-2 补强：2806=march 不存在 → 视为已消亡并清 Mission。
        accountWithParams(liveCfg(3));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(2806));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686)));
        engine.awaitIdleForTest(3_000L);
        assertEquals(1, engine.missionCount());

        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_DEL, delPush(13080L));
        engine.awaitIdleForTest(3_000L);
        assertEquals("2806 视为已消亡，清 Mission", 0, engine.missionCount());
        assertTrue(ledgerCount("garrison_gone") >= 1);
    }

    @Test
    public void maxConcurrentOne_sameTargetSecondSkipped() {
        accountWithParams(liveCfg(1));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD,
                push(march(13080L, OWN_UID, now + 120L, 679, 686),
                        march(13081L, OWN_UID, now + 121L, 679, 686)));
        engine.awaitIdleForTest(3_000L);
        assertEquals("同目标并发 1：只发 1 队", 1, engine.missionCount());
        assertEquals(1, countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertTrue("同目标第二威胁应被已有驻防拦住",
                ledgerCount("skip_existing_garrison") >= 1);
    }

    @Test
    public void sevenTargets_earliestSixLaunch_restSkipBusy() {
        // 2026-11-04：守护账号只有 6 个行军队列；>6 目标按 ETA 升序取前 6，其余 skip_busy。
        accountWithParams(liveCfg(6));
        TestEngine e = new TestEngine();
        presetQueueCap(e, 6);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        Map<String, Object>[] ms = new Map[7];
        for (int i = 0; i < 7; i++) {
            ms[i] = march(13080L + i, OWN_UID + i, now + 120L + i, 679 + i, 686 + i);
        }
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(ms));
        engine.awaitIdleForTest(3_000L);

        assertEquals("6 个队列 → 只发 6 队", 6,
                countProto(e, ServerGuardianLauncher.PROTO_WORLD_MARCH));
        assertEquals(6, engine.missionCount());
        assertTrue("第 7 个目标应 skip_busy", ledgerCount("skip_busy") >= 1);
        engine.shutdown();
    }

    @Test
    public void etaPriority_sortsBatchByEndSecAscending() {
        accountWithParams(liveCfg(2));
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long now = System.currentTimeMillis() / 1000L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, now + 60L));
        ServerGuardianEngine engine = engine();
        engine.attach(e);
        // 故意把“远威胁”放前、“近威胁”放后：应按 endSec 升序（近的优先）下单
        Map<String, Object> far = march(13080L, OWN_UID, now + 200L, 684, 691);
        Map<String, Object> near = march(13081L, OWN_UID + 1, now + 100L, 681, 688);
        engine.onPush(e, ServerGuardianEngine.PROTO_ALLIANCE_ADD, push(far, near));
        engine.awaitIdleForTest(3_000L);

        List<Map<String, Object>> launches = new ArrayList<>();
        for (int i = 0; i < e.requested.size(); i++) {
            if (ServerGuardianLauncher.PROTO_WORLD_MARCH.equals(e.requested.get(i))) {
                launches.add(e.argLog.get(i));
            }
        }
        assertEquals("两队都应发射", 2, launches.size());
        assertEquals("最紧急（endSec 小）先下单", 681L, endpointX(launches.get(0)));
        assertEquals(684L, endpointX(launches.get(1)));
    }

    private static long endpointX(Map<String, Object> args) {
        Object ep = args == null ? null : args.get("endpoint");
        if (ep instanceof Map) {
            Object x = ((Map<?, ?>) ep).get("x");
            return x instanceof Number ? ((Number) x).longValue() : 0L;
        }
        return 0L;
    }

    private static int countProto(TestEngine e, String proto) {
        int n = 0;
        for (String p : e.requested) if (proto.equals(p)) n++;
        return n;
    }
}
