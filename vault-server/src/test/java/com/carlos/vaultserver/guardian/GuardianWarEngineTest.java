package com.carlos.vaultserver.guardian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.ServerWarState;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.task.ServerFieldTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批次 J（2026-10-31 用户长期规则第 3 条）：守护战争态动作（ServerGuardianEngine 侧）。
 *
 * <p>锁定契约：
 * <ul>
 *   <li>入战争态（侦查 type=1）→ 立即召回全部 type=4 采集行军（逐队 recall，保护类不碰）；</li>
 *   <li>攻击类与驻防类行军绝不召回；召回请求每条采集队只发一次（防风暴）；</li>
 *   <li>先到 + 战报邮件 first_type=30 → 账本 garrison_done（同一场只记一次）；</li>
 *   <li>目标无后续在途敌方出征 ≥war_exit_idle_sec → 召回驻防行军（在途 recall/驻扎 off_work）；</li>
 *   <li>守护未实盘/dry_run/VAULT_GUARDIAN_WAR=off → 零请求（完全回旧行为）；</li>
 *   <li>战争态 gather 直接停派（任务入口 SKIPPED，队列全部留给驻防）。</li>
 * </ul>
 */
public class GuardianWarEngineTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;
    private static final long ENEMY_UID = 49422287L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;
    private AtomicLong clock;
    private ServerWarState war;

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
        clock = new AtomicLong(System.currentTimeMillis());
        accountWithGuardian(liveGuardian(null));
        war = ServerWarState.forRole(FPID, SID, OWN_UID);
        ServerWarState.configure(vault.find(FPID), SID, OWN_UID);
    }

    @After
    public void clean() {
        ServerWarState.unregister(FPID, SID, OWN_UID);
        System.clearProperty("VAULT_GUARDIAN_WAR");
    }

    // ---------------- 夹具 ----------------

    private static String liveGuardian(String extra) {
        String base = "\"sub\":{\"enabled\":true,\"dry_run\":false}";
        return "{" + base + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private void accountWithGuardian(String guardianJson) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("guardian", guardianJson);
        a.roleParams.put(String.valueOf(SID), params);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    /** 实盘 + 显式编队（发驻防需要编队；与既有 ServerGuardianEngineTest 同款）。 */
    private void useLiveGuardianWithFormation() {
        accountWithGuardian("{\"sub\":{\"enabled\":true,\"dry_run\":false},"
                + "\"strategy\":{\"formation\":{\"hero_ids\":[1]},\"ally_uid\":1001}}");
        ServerWarState.configure(vault.find(FPID), SID, OWN_UID);
    }

    private ServerGuardianEngine engine(TestEngine e) {
        ServerGuardianEngine g = new ServerGuardianEngine(FPID, SID, OWN_UID, state, vault,
                () -> true, clock::get);
        if (e != null) g.attach(e);
        return g;
    }

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    private static Map<String, Object> scoutPush(long marchId, long endSec) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", ENEMY_UID);
        march.put("type", 1L);
        march.put("endtime", endSec);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

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
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("x", x);
        point.put("y", y);
        march.put("endpoint", point);
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

    private static GameMessage content(Map<String, Object> c) {
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private static GameMessage msg(int code) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        return content(c);
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
        return content(c);
    }

    /** 战报/驻防邮件：first_type=30（守城级联/驻防参战）。 */
    private static Map<String, Object> mailPush(long firstType, long secondType, long mailId) {
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("first_type", firstType);
        cover.put("second_type", secondType);
        cover.put("mailid_new", mailId);
        cover.put("title", "驻防战报");
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("mail_cover_info", cover);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("infolist", List.of(mail));
        return c;
    }

    /** 我方在途行军（ownMarches 的详情回退源）：id/type/state/endtime。 */
    private static void presetOwnMarches(TestEngine e, long[][] marches) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (long[] m : marches) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", m[0]);
            o.put("type", m[1]);
            o.put("state", m[2]);
            o.put("endtime", m[3]);
            o.put("owner", OWN_UID);
            list.add(o);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", 0);
        detail.put("marchs", list);
        e.responses.put(ServerGuardianEngine.PROTO_MARCH_DETAIL, content(detail));
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
        e.responses.put(ServerGuardianEngine.PROTO_MARCH_DETAIL, content(detail));
    }

    private void enterWar() {
        war.onPush("notify_world_alliance_situation_add",
                scoutPush(9900L, nowS() + 600L), OWN_UID);
        assertTrue("侦查应进入守护战争态", ServerWarState.guardianWarActive(FPID, SID, OWN_UID));
    }

    private String ledgerRaw(ServerGuardianEngine g) {
        return state.getString(g.ledgerKey());
    }

    private boolean ledgerHas(ServerGuardianEngine g, String ev) {
        return ledgerCount(g, ev) > 0;
    }

    private int ledgerCount(ServerGuardianEngine g, String ev) {
        String raw = ledgerRaw(g);
        if (raw == null) return 0;
        JSONArray arr = new JSONArray(raw);
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            if (ev.equals(arr.getJSONObject(i).optString("ev"))) n++;
        }
        return n;
    }

    private static long countRequested(TestEngine e, String proto) {
        long n = 0;
        for (String p : e.requested) {
            if (proto.equals(p)) n++;
        }
        return n;
    }

    // ======================= 1. 入战 → 召回全部采集 =======================

    @Test
    public void warEnter_recallsAllGatherMarches_butNeverProtectionMarches() {
        TestEngine e = new TestEngine();
        long future = nowS() + 600L;
        presetOwnMarches(e, new long[][]{
                {8001L, 4L, 2L, future},   // 采集在途 → 召回
                {8002L, 4L, 3L, future},   // 采集驻扎 → 召回
                {8003L, 5L, 2L, future},   // 驻防 → 不碰
                {8004L, 2L, 2L, future},   // 进攻 → 不碰
                {8005L, 110L, 2L, future}  // 特殊采集类 → 不碰（只认 4）
        });
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine g = engine(e);

        enterWar();
        g.tick(e);
        g.awaitIdleForTest(3_000L);

        assertEquals("两条 type=4 采集各召回一次", 2, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < e.requested.size(); i++) {
            if (ServerGuardianLauncher.PROTO_MARCH_RECALL.equals(e.requested.get(i))) {
                ids.add(((Number) e.argLog.get(i).get("id")).longValue());
            }
        }
        assertTrue(ids.contains(8001L));
        assertTrue(ids.contains(8002L));
        assertFalse("绝不召回驻防类", ids.contains(8003L));
        assertFalse("绝不召回进攻类", ids.contains(8004L));
        assertFalse("绝不召回特殊采集类", ids.contains(8005L));

        assertTrue(g.warModeActiveForTest());
        assertEquals(2, g.warRecalledCountForTest());
        assertTrue("入战记账", ledgerHas(g, "war_enter"));
        assertTrue("逐队召回记账", ledgerHas(g, "war_recall_gather"));
        assertTrue("汇总记账", ledgerHas(g, "war_recall_all"));

        // 同一场战争不重复召回（防风暴）
        g.tick(e);
        g.awaitIdleForTest(3_000L);
        assertEquals("同场战争每条采集队只召回一次",
                2, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        g.shutdown();
    }

    @Test
    public void noScout_noWarActions() {
        TestEngine e = new TestEngine();
        presetOwnMarches(e, new long[][]{{8001L, 4L, 2L, nowS() + 600L}});
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine g = engine(e);

        g.tick(e);
        g.tick(e);
        g.awaitIdleForTest(3_000L);

        assertFalse(ServerWarState.guardianWarActive(FPID, SID, OWN_UID));
        assertFalse(g.warModeActiveForTest());
        assertEquals(0, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        g.shutdown();
    }

    @Test
    public void envRollback_off_noWarActions() {
        System.setProperty("VAULT_GUARDIAN_WAR", "off");
        ServerWarState.configure(vault.find(FPID), SID, OWN_UID);
        TestEngine e = new TestEngine();
        presetOwnMarches(e, new long[][]{{8001L, 4L, 2L, nowS() + 600L}});
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine g = engine(e);

        war.onPush("notify_world_alliance_situation_add", scoutPush(9901L, nowS() + 600L), OWN_UID);
        g.tick(e);
        g.awaitIdleForTest(3_000L);

        assertFalse("回滚后不启用守护战争态动作", g.warModeActiveForTest());
        assertEquals(0, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        g.shutdown();
    }

    @Test
    public void dryRun_noWarActions() {
        accountWithGuardian("{\"sub\":{\"enabled\":true,\"dry_run\":true}}");
        ServerWarState.configure(vault.find(FPID), SID, OWN_UID);
        TestEngine e = new TestEngine();
        presetOwnMarches(e, new long[][]{{8001L, 4L, 2L, nowS() + 600L}});
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine g = engine(e);

        war.onPush("notify_world_alliance_situation_add", scoutPush(9902L, nowS() + 600L), OWN_UID);
        g.tick(e);
        g.awaitIdleForTest(3_000L);

        assertFalse("演练模式不发召回", g.warModeActiveForTest());
        assertEquals(0, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_RECALL));
        g.shutdown();
    }

    // ======================= 2. 驻防完成（先到 + 战报=garrison_done） =======================

    @Test
    public void garrisonDone_whenArrivedFirstAndBattleMailArrives() {
        useLiveGuardianWithFormation();
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long enemyEnd = nowS() + 120L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(555L, enemyEnd - 20L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine g = engine(e);
        enterWar();

        g.onPush(e, "notify_world_alliance_situation_add",
                attackPush(13080L, ENEMY_UID, OWN_UID, enemyEnd, 679, 686));
        g.awaitIdleForTest(3_000L);
        assertEquals(1, g.missionCount());

        // 战报/驻防邮件（first_type=30）→ 我方先到（ownEnd < enemyEnd）→ garrison_done
        g.onPushAt(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 4L, 701L), clock.get());
        assertTrue("应记 garrison_done", ledgerHas(g, "garrison_done"));
        JSONObject done = null;
        JSONArray arr = new JSONArray(ledgerRaw(g));
        for (int i = 0; i < arr.length(); i++) {
            if ("garrison_done".equals(arr.getJSONObject(i).optString("ev"))) {
                done = arr.getJSONObject(i);
                break;
            }
        }
        assertNotNull(done);
        assertTrue(" garrison_done 说明应含先到：" + done.optString("note"),
                done.optString("note").contains("先到"));

        // 同一任务不重复记账（另一封战报）
        g.onPushAt(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 5L, 702L), clock.get());
        assertEquals("同一驻防任务只记一次 garrison_done", 1, ledgerCount(g, "garrison_done"));
        g.shutdown();
    }

    @Test
    public void noGarrisonDone_whenArrivedLate() {
        useLiveGuardianWithFormation();
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long enemyEnd = nowS() + 120L;
        // 我方晚到（ownEnd > enemyEnd）→ 非有效驻防
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(556L, enemyEnd + 30L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        ServerGuardianEngine g = engine(e);
        enterWar();

        g.onPush(e, "notify_world_alliance_situation_add",
                attackPush(13081L, ENEMY_UID, OWN_UID, enemyEnd, 679, 686));
        g.awaitIdleForTest(3_000L);
        assertEquals(1, g.missionCount());

        g.onPushAt(e, ServerGuardianEngine.PROTO_NEW_MAIL, mailPush(30L, 4L, 703L), clock.get());
        assertEquals("晚到不算有效驻防", 0, ledgerCount(g, "garrison_done"));
        g.shutdown();
    }

    // ======================= 3. 无后续在途 → 召回驻防 =======================

    @Test
    public void noMoreIncoming_recallsGarrisonAfterIdle() {
        useLiveGuardianWithFormation();
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long enemyEnd = nowS() - 1L;             // 敌方已落地（≤2s → 判到达，旧路径不撤防）
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(557L, enemyEnd - 20L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_OFF_WORK, msg(0));
        ServerGuardianEngine g = engine(e);
        enterWar();

        g.onPush(e, "notify_world_alliance_situation_add",
                attackPush(13082L, ENEMY_UID, OWN_UID, enemyEnd, 679, 686));
        g.awaitIdleForTest(3_000L);
        assertEquals(1, g.missionCount());

        // 敌落地（del remain<=2s）→ 旧口径「战报/落地不撤防」
        g.onPush(e, "notify_world_alliance_situation_del", delPush(13082L));
        g.awaitIdleForTest(3_000L);
        assertEquals("落地不撤防（旧口径）", 1, g.missionCount());
        assertEquals(0, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_OFF_WORK));

        // 目标无后续在途持续 > war_exit_idle_sec(60s) → 立即召回驻防
        g.tick(e);                                // 战争态边沿（warModeActive=true）
        long t0 = clock.get();
        g.tick(e);                                // 登记目标 idle 起点
        clock.set(t0 + 61_000L);
        g.tick(e);                                // 满 60s → 召回
        g.awaitIdleForTest(3_000L);

        long recalls = countRequested(e, ServerGuardianLauncher.PROTO_MARCH_OFF_WORK)
                + countRequested(e, ServerGuardianLauncher.PROTO_MARCH_RECALL);
        assertEquals("应召回驻防行军", 1, recalls);
        assertEquals("召回成功后清任务", 0, g.missionCount());
        assertTrue("召回记账", ledgerHas(g, "garrison_recall") || ledgerHas(g, "recalled"));
        g.shutdown();
    }

    @Test
    public void stillIncoming_doesNotRecallGarrison() {
        useLiveGuardianWithFormation();
        TestEngine e = new TestEngine();
        presetQueueOk(e);
        presetSoldiers(e);
        long enemyEnd = nowS() + 120L;
        e.responses.put(ServerGuardianLauncher.PROTO_WORLD_MARCH, launchOk(558L, enemyEnd - 20L));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_RECALL, msg(0));
        e.responses.put(ServerGuardianLauncher.PROTO_MARCH_OFF_WORK, msg(0));
        ServerGuardianEngine g = engine(e);
        enterWar();

        g.onPush(e, "notify_world_alliance_situation_add",
                attackPush(13083L, ENEMY_UID, OWN_UID, enemyEnd, 679, 686));
        g.awaitIdleForTest(3_000L);
        assertEquals(1, g.missionCount());

        long t0 = clock.get();
        g.tick(e);
        clock.set(t0 + 61_000L);
        g.tick(e);
        g.awaitIdleForTest(3_000L);

        assertEquals("目标仍有在途敌方出征 → 不召回",
                0, countRequested(e, ServerGuardianLauncher.PROTO_MARCH_OFF_WORK));
        assertEquals(1, g.missionCount());
        g.shutdown();
    }

    // ======================= 4. 战争态采集停派 =======================

    @Test
    public void wartime_gatherTaskSkippedAndReserveAllQueues() {
        enterWar();
        Map<String, JSONObject> params = new LinkedHashMap<>();
        params.put("guardian", new JSONObject(liveGuardian(null)));
        TaskContext tc = new TaskContext(null, FPID, SID, state, params, "定时",
                TaskContext.enabledPredicate(null), OWN_UID);

        TaskOutcome out = ServerFieldTasks.gather(tc);
        assertNotNull(out);
        assertEquals("战争态 gather 直接停派", TaskOutcome.Category.SKIPPED, out.category);
        assertTrue("原因可读：" + out.message, out.message.contains("战争态"));

        assertTrue(GuardianReserve.gatherBlocked(tc));
        assertEquals("战争态 reserve_slots 视为全部队列（G3：=6，最强表达“全部让出”）",
                ServerGuardianEngine.MAX_CONCURRENT_CAP, GuardianReserve.reserveSlots(tc));
    }

    @Test
    public void wartime_garrisonConcurrencyUsesAllSixQueues() {
        TestEngine e = new TestEngine();
        ServerGuardianEngine g = engine(e);
        enterWar();
        assertEquals("战争态：可用队列全部留给驻防（最多 6 队）",
                ServerGuardianEngine.MAX_CONCURRENT_CAP, g.effectiveMaxConcurrent(g.configSnapshot()));
        // 平时沿用 max_concurrent（默认 3）
        ServerGuardianEngine peace = new ServerGuardianEngine("222", SID, state, vault);
        ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(new JSONObject(
                "{\"sub\":{\"enabled\":true,\"dry_run\":false},\"strategy\":{\"max_concurrent\":3}}"));
        assertEquals(3, peace.effectiveMaxConcurrent(cfg));
        g.shutdown();
        peace.shutdown();
    }

    @Test
    public void peacetime_gatherNotBlockedByWar() {
        Map<String, JSONObject> params = new LinkedHashMap<>();
        params.put("guardian", new JSONObject(liveGuardian(null)));
        TaskContext tc = new TaskContext(null, FPID, SID, state, params, "定时",
                TaskContext.enabledPredicate(null), OWN_UID);
        assertFalse("非战争态不停派采集", GuardianReserve.gatherBlocked(tc));
        assertEquals("非战争态 reserve=params.reserve_slots(默认1)",
                1, GuardianReserve.reserveSlots(tc));
    }
}
