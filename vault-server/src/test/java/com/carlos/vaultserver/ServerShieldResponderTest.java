package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 反应式开盾（P1-2）单测：WAR/RECON 推送 → 门控 → 幂等 → 让位保护 → 状态留痕。
 * 引擎替身沿用 CaptureTasksTest.FakeEngine（合成 notify/响应驱动，不连网）。
 */
public class ServerShieldResponderTest {

    private static final long OWN_UID = 1001L;
    private static final long SID = 913L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

    /** 最小引擎替身：在线态/uid 可切，notify/request 用预置内容驱动（不连网）。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        boolean online = true;
        long uid = OWN_UID;
        final List<String> requested = new ArrayList<>();
        final List<Map<String, Object>> argLog = new ArrayList<>();
        final List<BiConsumer<String, Map<String, Object>>> hooks = new ArrayList<>();

        TestEngine() {
            super(null);
        }

        @Override
        public void addMessageHook(BiConsumer<String, Map<String, Object>> hook) {
            if (hook != null) hooks.add(hook);
        }

        /** 模拟引擎收到推送：驱动 attach 注册的全部钩子（验证 SITUATION_PROTOS 分发）。 */
        void push(String proto, Map<String, Object> content) {
            for (BiConsumer<String, Map<String, Object>> h : hooks) h.accept(proto, content);
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
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }

        @Override
        public String sessionState() {
            return online ? WjdrEngine.STATE_ONLINE : WjdrEngine.STATE_TAKEOVER;
        }

        @Override
        public long lastUid() {
            return uid;
        }

        @Override
        public long lastServerId() {
            return SID;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            argLog.add(args);
            return responses.get(proto);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }
    }

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
    }

    // ---------- 夹具 ----------

    private VaultStore.Account account(boolean auto, String battleShieldParams) {
        VaultStore.Account a = new VaultStore.Account("111");
        a.token = "tk";
        a.auto = auto;
        a.activeSid = String.valueOf(SID);
        // 2026-11-06 决策：服务端只读新键（旧 eta_sec/troops_min 不再兼容）。
        // 旧用例语义是“收到即开”，用显式 shield_mode=immediate 表达；新默认另立用例覆盖。
        if (battleShieldParams == null) {
            battleShieldParams = "{\"strategy\":{\"shield_mode\":\"immediate\"}}";
        }
        // 2026-11-13：免费盾默认改为关闭（free 缺省 false）。本类旧用例均假设「免费盾可用」，
        // 未显式声明 sub.free 时补 true 保持用例语义；free 开关专属用例不走本夹具。
        battleShieldParams = ensureFreeEnabled(battleShieldParams);
        a.roleParams.put(String.valueOf(SID),
                new LinkedHashMap<>(Map.of("battle_shield", battleShieldParams)));
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
        return a;
    }

    /** 夹具：battle_shield 参数 JSON 未显式写 sub.free 时补 true（旧用例假设免费盾可用）。 */
    private static String ensureFreeEnabled(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject sub = o.optJSONObject("sub");
            if (sub == null) {
                sub = new JSONObject();
                o.put("sub", sub);
            }
            if (!sub.has("free")) sub.put("free", true);
            return o.toString();
        } catch (Throwable t) {
            return json;
        }
    }

    private ServerShieldResponder responder() {
        return new ServerShieldResponder("111", SID, state, vault);
    }

    /** 可注入时间源的 responder（卡秒 ETA 单测：本机毫秒与服务器秒同步推进）。 */
    private ServerShieldResponder responder(AtomicLong nowMs, AtomicLong serverSec) {
        return new ServerShieldResponder("111", SID, state, vault, nowMs::get, serverSec::get);
    }

    /** 目标系列推送（实证形状：to_marchs[].atk_city.targetid / target.map_city.uid）。 */
    private static Map<String, Object> alliancePush(long marchId, long type, long targetUid,
                                                    long targetType) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("uid", targetUid);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", 1);
        target.put("map_city", city);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", 49422287L);
        march.put("type", type);
        march.put("target_type", targetType);
        march.put("atk_city", atk);
        march.put("target", target);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    /** 观察哨系列推送（实证形状：marches.{槽位:{march:{...}}}，无目标 uid）。 */
    private static Map<String, Object> worldPush(long marchId, long type) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", 49422287L);
        march.put("type", type);
        march.put("target_type", 1);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        slot.put("power", 0);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("marches", Map.of("9.0", slot));
        return c;
    }

    /** 目标系列推送补 squad_list.soldier（兵力阈值单测；与 WarNotify.troopsOf 同口径）。 */
    private static Map<String, Object> withTroops(Map<String, Object> content, long troops) {
        Map<String, Object> march = firstMarch(content);
        if (march != null) {
            Map<String, Object> soldier = new LinkedHashMap<>();
            soldier.put("10100", troops);   // 盾T1
            Map<String, Object> squad = new LinkedHashMap<>();
            squad.put("soldier", soldier);
            march.put("squad_list", squad);
        }
        return content;
    }

    /** 目标系列推送补 endtime（服务器秒，卡秒 ETA 单测）。 */
    private static Map<String, Object> withEndtime(Map<String, Object> content, long endSec) {
        Map<String, Object> march = firstMarch(content);
        if (march != null) march.put("endtime", endSec);
        return content;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMarch(Map<String, Object> content) {
        Object list = content.get("to_marchs");
        if (!(list instanceof List) || ((List<?>) list).isEmpty()) return null;
        return (Map<String, Object>) ((List<?>) list).get(0);
    }

    private static void presetCityItem(TestEngine e, long freeId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("free_id", freeId);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("data", List.of(item));
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("data", inner);
        e.notifies.put("notify_city_item_init", outer);
    }

    private static GameMessage msg(long code) {
        GameMessage m = new GameMessage();
        m.setContent(new LinkedHashMap<>(Map.of("code", code)));
        return m;
    }

    private String modText() {
        return state.getString("mod:111:" + SID + ":battle_shield");
    }

    // ---------- 通知触发开盾 ----------

    @Test
    public void warPush_opensShield_freeIdFromNotify_notHardcoded() {
        // 无角色参数 → reactive 默认 true（App FnDetail 同款默认）
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);   // free_id 必须来自 notify_city_item_init，不能写死 1
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertNotNull(e.argLog.get(0).get("free_id"));
        assertEquals(42L, ((Number) e.argLog.get(0).get("free_id")).longValue());
        assertEquals("WAR", r.lastEventKind);
        JSONObject mod = new JSONObject(modText());
        assertTrue(mod.getString("reactive").contains("被进攻"));
        assertTrue(mod.getString("reactive").contains("8小时免费护盾已开启"));
        assertEquals("反应式", mod.getString("reactive_trg"));
        // 开盾成功 → 仅 60s 防风暴冷却；幂等由盾账本 shieldActive 判定（不再用 8h 窗）
        long cool = r.nextAttemptAt.get();
        long t0 = System.currentTimeMillis();
        assertTrue(cool >= t0 + ServerShieldResponder.OPEN_SUCCESS_COOLDOWN_MILLIS - 2000);
        assertTrue(cool <= t0 + ServerShieldResponder.OPEN_SUCCESS_COOLDOWN_MILLIS + 2000);
    }

    @Test
    public void reconPush_opensShield() {
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13079L, 1, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertEquals("RECON", r.lastEventKind);
        assertTrue(new JSONObject(modText()).getString("reactive").contains("被侦查"));
    }

    @Test
    public void repeatedPushes_idempotent_singleShield() {
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        // 同一波攻击的后续推送（含新行军号）不得重复开盾
        r.onPush(alliancePush(13081L, 2, OWN_UID, 1), OWN_UID);
        assertFalse(r.tick(e));
        r.onPush(alliancePush(13082L, 1, OWN_UID, 1), OWN_UID);
        assertFalse(r.tick(e));

        assertEquals(1, e.requested.size());
    }

    // ---------- 码表归 SKIPPED ----------

    @Test
    public void skipCodes_classifiedSkipped_withRetryCooldown() {
        account(true, null);
        for (int code : new int[]{51, 6151, 6158, 7501, 4303}) {
            TestEngine e = new TestEngine();
            presetCityItem(e, 42L);
            e.responses.put("req_city_item_free_use", msg(code));
            ServerShieldResponder r = responder();

            r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
            assertTrue(r.tick(e));

            assertEquals("码 " + code + " 应发一次开盾请求", 1, e.requested.size());
            JSONObject mod = new JSONObject(modText());
            assertTrue(mod.getString("reactive").contains("码" + code));
            // 非成功结果 → 5 分钟重试冷却（而非 8 小时）
            long t0 = System.currentTimeMillis();
            long cool = r.nextAttemptAt.get();
            assertTrue(cool >= t0 + ServerShieldResponder.RETRY_COOLDOWN_MILLIS - 2000);
            assertTrue(cool <= t0 + ServerShieldResponder.RETRY_COOLDOWN_MILLIS + 2000);
        }
    }

    @Test
    public void noCityItemNotify_skipped_noRequest() {
        account(true, null);
        TestEngine e = new TestEngine();   // 未预置 notify_city_item_init
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertTrue(e.requested.isEmpty());   // free_id 取不到 → 不发请求
        assertTrue(new JSONObject(modText()).getString("reactive").contains("城市道具未同步"));
    }

    // ---------- 开关门控 ----------

    @Test
    public void reactiveSubOff_noShield() {
        account(true, "{\"sub\":{\"reactive\":false}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertTrue(e.requested.isEmpty());
        assertTrue(new JSONObject(modText()).getString("reactive").contains("已关闭"));
    }

    @Test
    public void automationOff_noShield() {
        account(false, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertTrue(e.requested.isEmpty());
        assertTrue(new JSONObject(modText()).getString("reactive").contains("自动化未开启"));
    }

    // ---------- human_first 让位保护 ----------

    @Test
    public void takeoverYield_noShield_noKick_thenResume() {
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        // 推送到达后人玩上线（takeover）：tick 不得发请求（不互踢）
        e.online = false;
        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertFalse(r.tick(e));
        assertTrue(e.requested.isEmpty());
        assertNull(modText());

        // 回连后事件仍在保鲜期 → 补开盾
        e.online = true;
        assertTrue(r.tick(e));
        assertEquals(List.of("req_city_item_free_use"), e.requested);
    }

    @Test
    public void staleEventAfterYield_dropped() {
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        // 让位 30 分钟后回连：事件已超过保鲜期 → 不为旧攻击补盾
        r.lastEventAt -= ServerShieldResponder.EVENT_TTL_MILLIS + 60_000L;
        assertFalse(r.tick(e));
        assertTrue(e.requested.isEmpty());
        assertNull(modText());
    }

    // ---------- 目标过滤 ----------

    @Test
    public void foreignOrNonCityTargets_neverTrigger() {
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        // 他城被进攻（目标 uid 非本人）
        r.onPush(alliancePush(13080L, 2, OWN_UID + 7, 1), OWN_UID);
        assertFalse(r.tick(e));
        // 驻防/增援行军（type=5）即使目标是本人也不触发
        r.onPush(alliancePush(11260L, 5, OWN_UID, 1), OWN_UID);
        assertFalse(r.tick(e));
        // 非玩家城目标（target_type≠1，打野/情报类）
        r.onPush(alliancePush(13081L, 2, OWN_UID, 2), OWN_UID);
        assertFalse(r.tick(e));

        assertTrue(e.requested.isEmpty());
        assertNull(modText());
    }

    @Test
    public void worldSituationPush_withoutTargetUid_notTriggered() {
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        // 观察哨推送无守方 uid，无法确认是本人城 → 不浪费每日免费盾
        r.onPush(worldPush(13080L, 2), OWN_UID);
        assertFalse(r.tick(e));
        r.onPush(worldPush(13079L, 1), OWN_UID);
        assertFalse(r.tick(e));

        assertTrue(e.requested.isEmpty());
    }

    // ---------- classify 纯函数 ----------

    @Test
    public void classify_unitSemantics() {
        assertNull(ServerShieldResponder.classify(null, OWN_UID));
        assertNull(ServerShieldResponder.classify(alliancePush(1L, 2, OWN_UID, 1), 0));
        assertNull(ServerShieldResponder.classify(alliancePush(1L, 2, OWN_UID + 1, 1), OWN_UID));
        assertNull(ServerShieldResponder.classify(alliancePush(1L, 5, OWN_UID, 1), OWN_UID));
        assertNull(ServerShieldResponder.classify(worldPush(1L, 2), OWN_UID));
        assertEquals("RECON", ServerShieldResponder.classify(
                alliancePush(1L, 1, OWN_UID, 1), OWN_UID));
        assertEquals("WAR", ServerShieldResponder.classify(
                alliancePush(1L, 2, OWN_UID, 1), OWN_UID));
        // 同时有侦查+进攻 → 报 WAR（严重度优先）
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("to_marchs", Arrays.asList(
                marchOf(1L, 1, OWN_UID), marchOf(2L, 2, OWN_UID)));
        assertEquals("WAR", ServerShieldResponder.classify(both, OWN_UID));
    }

    // ---------- 总开关 / 兵力阈值 / 卡秒 ETA / force_online（2026-10-24 策略扩展） ----------

    @Test
    public void totalSwitchOff_noShield_noRequest() {
        account(true, "{\"sub\":{\"enabled\":false}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));   // 有动作（留痕），但零请求

        assertTrue(e.requested.isEmpty());
        assertTrue(new JSONObject(modText()).getString("reactive").contains("总开关"));
    }

    @Test
    public void freeShield_totalSwitchOff_skipped_noRequest() throws Exception {
        // freeShield 自身也门控总开关（定时免费盾整轮路径：缺省 true 保持旧行为）
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        Map<String, JSONObject> params = new HashMap<>();
        params.put("battle_shield", new JSONObject("{\"sub\":{\"enabled\":false}}"));
        TaskContext tc = new TaskContext(e, "111", SID, state, params, "测试");

        TaskOutcome out = ServerTasks.freeShield(tc);

        assertEquals(TaskOutcome.Category.SKIPPED, out.category);
        assertTrue(out.message.contains("总开关"));
        assertTrue(e.requested.isEmpty());
    }

    @Test
    public void freeShield_freeSwitchOff_skipped_noRequest() throws Exception {
        // 2026-11-13：每日免费护盾独立开关 free=false 时只关定时免费盾（enabled 仍开、
        // 反应式开盾不受影响）；此前服务端未消费 free，App 关了也照开。
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        Map<String, JSONObject> params = new HashMap<>();
        params.put("battle_shield", new JSONObject("{\"sub\":{\"enabled\":true,\"free\":false}}"));
        TaskContext tc = new TaskContext(e, "111", SID, state, params, "测试");

        TaskOutcome out = ServerTasks.freeShield(tc);

        assertEquals(TaskOutcome.Category.SKIPPED, out.category);
        assertTrue(out.message.contains("每日免费护盾"));
        assertTrue(e.requested.isEmpty());
    }

    @Test
    public void troopsBelowMin_skipped() {
        account(true, "{\"strategy\":{\"shield_troops_min\":\"100000\"}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(withTroops(alliancePush(13080L, 2, OWN_UID, 1), 5000L), OWN_UID);
        assertTrue(r.tick(e));

        assertTrue(e.requested.isEmpty());
        String text = new JSONObject(modText()).getString("reactive");
        assertTrue(text.contains("兵力"));
        assertTrue(text.contains("阈值"));
    }

    @Test
    public void troopsUnknown_withMin_triggersProtectionFirst() {
        account(true, "{\"strategy\":{\"shield_troops_min\":\"100000\"}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        // 推送无 squad_list → 兵力未知：保护优先照常开盾（不因阈值漏防）
        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertTrue(new JSONObject(modText()).getString("reactive").contains("8小时免费护盾已开启"));
    }

    @Test
    public void troopsOf_shapesAndUnknown() {
        Map<String, Object> mapShape = new LinkedHashMap<>();
        Map<String, Object> squad = new LinkedHashMap<>();
        squad.put("soldier", new LinkedHashMap<>(Map.of("10100", 1200, "10200", 800)));
        mapShape.put("squad_list", squad);
        assertEquals(2000L, ServerShieldResponder.troopsOf(mapShape));

        Map<String, Object> listShape = new LinkedHashMap<>();
        Map<String, Object> squad2 = new LinkedHashMap<>();
        squad2.put("soldier", List.of(
                new LinkedHashMap<>(Map.of("id", 10100, "count", 300)),
                new LinkedHashMap<>(Map.of("id", 20100, "count", 200))));
        listShape.put("squad_list", squad2);
        assertEquals(500L, ServerShieldResponder.troopsOf(listShape));

        // 无 squad / 兵力 0 → 未知（-1）
        assertEquals(-1L, ServerShieldResponder.troopsOf(new LinkedHashMap<>()));
        Map<String, Object> zero = new LinkedHashMap<>();
        Map<String, Object> squad3 = new LinkedHashMap<>();
        squad3.put("soldier", new LinkedHashMap<>(Map.of("10100", 0)));
        zero.put("squad_list", squad3);
        assertEquals(-1L, ServerShieldResponder.troopsOf(zero));
    }

    @Test
    public void etaNotDue_keptPending_thenOpensAtDue() {
        account(true, "{\"strategy\":{\"shield_eta_sec\":\"60\"}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong serverSec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, serverSec);

        // 敌方 300 秒后到达，卡秒 60 秒 → 挂起（本拍不发请求，返回 false 表示无动作）
        r.onPush(withEndtime(alliancePush(13080L, 2, OWN_UID, 1), 1_000_300L), OWN_UID);
        assertFalse(r.tick(e));
        assertTrue(e.requested.isEmpty());
        assertTrue(new JSONObject(modText()).getString("reactive").contains("等待卡秒"));

        // 挂起期间本机时间推进超过旧 10 分钟保鲜窗：不得被判死丢弃（仍挂起等待）
        nowMs.addAndGet(ServerShieldResponder.EVENT_TTL_MILLIS + 60_000L);
        assertFalse(r.tick(e));
        assertTrue(e.requested.isEmpty());

        // 到点（剩余 60 秒）→ 开盾
        serverSec.set(1_000_240L);    // 剩余 = 1000300-1000240 = 60 秒
        nowMs.addAndGet(5 * 60_000L); // 越过挂起判定时刻
        assertTrue(r.tick(e));

        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertTrue(new JSONObject(modText()).getString("reactive").contains("被进攻"));
    }

    @Test
    public void etaZero_opensImmediately_evenWithFutureEndtime() {
        account(true, "{\"strategy\":{\"shield_mode\":\"immediate\"}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        // shield_mode=immediate：即使推送带很远的 endtime 也收到即开
        long endSec = System.currentTimeMillis() / 1000L + 300L;
        r.onPush(withEndtime(alliancePush(13080L, 2, OWN_UID, 1), endSec), OWN_UID);
        assertTrue(r.tick(e));

        assertEquals(List.of("req_city_item_free_use"), e.requested);
    }

    @Test
    public void forceOnlineWanted_switchSemantics() {
        // 无风险 → false（即使开关都开）
        account(true, "{\"sub\":{\"force_online\":true}}");
        assertFalse(responder().forceOnlineWanted());

        // 有新鲜风险但 force_online 默认 false → false
        account(true, null);
        ServerShieldResponder r1 = responder();
        r1.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertFalse(r1.forceOnlineWanted());

        // force_online=true + 新鲜风险 → true；不要求会话 online（推送到达后被顶号场景）
        account(true, "{\"sub\":{\"force_online\":true}}");
        ServerShieldResponder r2 = responder();
        TestEngine offline = new TestEngine();
        offline.online = false;
        assertEquals(WjdrEngine.STATE_TAKEOVER, offline.sessionState());
        r2.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r2.forceOnlineWanted());

        // 总开关关闭 → false
        account(true, "{\"sub\":{\"enabled\":false,\"force_online\":true}}");
        ServerShieldResponder r3 = responder();
        r3.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertFalse(r3.forceOnlineWanted());

        // reactive 关闭 → false
        account(true, "{\"sub\":{\"reactive\":false,\"force_online\":true}}");
        ServerShieldResponder r4 = responder();
        r4.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertFalse(r4.forceOnlineWanted());

        // 陈旧风险（超过 10 分钟保鲜窗）→ false
        account(true, "{\"sub\":{\"force_online\":true}}");
        ServerShieldResponder r5 = responder();
        r5.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        r5.lastEventAt -= ServerShieldResponder.EVENT_TTL_MILLIS + 60_000L;
        assertFalse(r5.forceOnlineWanted());

        // 卡秒挂起中（尚未到达）→ 仍算新鲜 → true
        account(true, "{\"sub\":{\"force_online\":true},\"strategy\":{\"shield_eta_sec\":\"60\"}}");
        ServerShieldResponder r6 = responder();
        long nowSec = System.currentTimeMillis() / 1000L;
        r6.onPush(withEndtime(alliancePush(13080L, 2, OWN_UID, 1), nowSec + 300L), OWN_UID);
        assertTrue(r6.forceOnlineWanted());
    }

    // ---------- 采集点遭袭（2026-10-24：对齐网页版 GATHERING_ATTACK） ----------

    /** 采集点遭袭推送（target.type=2 + map_resource.uid，endpoint 指向资源点）。 */
    private static Map<String, Object> gatherPush(long marchId, long type, long ownUid,
                                                  int x, int y, boolean withResourceUid) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", 2);
        if (withResourceUid) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("uid", ownUid);
            target.put("map_resource", resource);
        }
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("x", x);
        endpoint.put("y", y);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", 49422287L);
        march.put("type", type);
        march.put("endtime", System.currentTimeMillis() / 1000L + 600L);
        march.put("endpoint", endpoint);
        march.put("target", target);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    private static List<ServerShieldResponder.GatherMarch> gatherAt(long marchId, int x, int y) {
        return List.of(new ServerShieldResponder.GatherMarch(marchId, x, y));
    }

    private static List<ServerShieldResponder.GatherMarch> gatherState(long marchId, int x, int y,
                                                                       long state, long endSec) {
        return List.of(new ServerShieldResponder.GatherMarch(marchId, x, y, state, endSec, 0L));
    }

    /** 采集点敌方行军推送（可指定 endtime）。 */
    private static Map<String, Object> gatherPushAt(long marchId, long type, long ownUid,
                                                    int x, int y, long endSec) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", 2);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uid", ownUid);
        target.put("map_resource", resource);
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("x", x);
        endpoint.put("y", y);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", marchId);
        march.put("owner", 49422287L);
        march.put("type", type);
        march.put("endtime", endSec);
        march.put("endpoint", endpoint);
        march.put("target", target);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(march));
        return c;
    }

    @Test
    public void gatherAttack_notifiesAndRecallsOwnGatheringMarch_whenEnabled() {
        // 2026-11-06 决策：采集召回统一走 gatherWatchTick（gather_mode 缺省 before_arrival），
        // "收到即撤矿"只保留给显式 immediate 模式；本用例锁死 immediate 路径仍能撤矿。
        account(true, "{\"sub\":{\"gather_attack\":true,\"gather_recall\":true},"
                + "\"strategy\":{\"gather_mode\":\"immediate\"}}");
        TestEngine e = new TestEngine();
        e.responses.put("req_world_march_off_work", msg(0));
        ServerShieldResponder r = responder();
        r.setMarchSourceForTest(engine -> gatherAt(7001L, 120, 340));

        r.onPush(gatherPush(14001L, 4, OWN_UID, 120, 340, true), OWN_UID, e);
        assertTrue("采集告警应产生动作（提醒/撤矿）", r.tick(e));

        assertEquals(List.of("req_world_march_off_work"), e.requested);
        assertEquals(7001L, com.carlos.vaultserver.task.Exec.longOf(e.argLog.get(0).get("id")));
        JSONObject mod = new JSONObject(modText());
        String text = mod.getString("gather");
        assertTrue("状态行应含采集点遭袭文案：" + text, text.contains("采集点遭袭"));
        assertTrue("状态行应含撤矿结果：" + text, text.contains("已撤"));
        // 同 marchId 冷却内重复推送不再发请求
        r.onPush(gatherPush(14002L, 4, OWN_UID, 120, 340, true), OWN_UID, e);
        r.tick(e);
        assertEquals("同队伍 10 分钟冷却内不得重复撤矿", 1, e.requested.size());
    }

    @Test
    public void gatherAttack_notifyOnlyByDefault_noRequest() {
        account(true, null);   // gather_attack 默认 true、gather_recall 默认 false
        TestEngine e = new TestEngine();
        ServerShieldResponder r = responder();
        r.setMarchSourceForTest(engine -> gatherAt(7001L, 120, 340));

        r.onPush(gatherPush(14001L, 4, OWN_UID, 120, 340, true), OWN_UID, e);
        assertTrue(r.tick(e));

        assertTrue("默认只提醒不撤矿", e.requested.isEmpty());
        assertTrue(new JSONObject(modText()).getString("gather").contains("采集点遭袭"));
    }

    @Test
    public void gatherAttack_switchOff_noAction() {
        account(true, "{\"sub\":{\"gather_attack\":false}}");
        TestEngine e = new TestEngine();
        ServerShieldResponder r = responder();
        r.setMarchSourceForTest(engine -> gatherAt(7001L, 120, 340));

        r.onPush(gatherPush(14001L, 4, OWN_UID, 120, 340, true), OWN_UID, e);
        assertTrue(r.tick(e));

        assertTrue(e.requested.isEmpty());
        String text = new JSONObject(modText()).getString("gather");
        assertTrue("关闭时应留痕：" + text, text.contains("已关闭"));
    }

    @Test
    public void gatherAttack_endpointFallback_whenResourceUidMissing() {
        account(true, null);
        TestEngine e = new TestEngine();
        ServerShieldResponder r = responder();
        r.setMarchSourceForTest(engine -> gatherAt(7001L, 120, 340));

        // 推送不含 map_resource.uid，但 endpoint 与自己采集行军坐标一致 → 仍识别
        r.onPush(gatherPush(14001L, 110, OWN_UID, 120, 340, false), OWN_UID, e);
        assertTrue(r.tick(e));

        String text = new JSONObject(modText()).getString("gather");
        assertTrue("坐标兜底应识别并带出在采队伍：" + text, text.contains("#7001"));
    }

    // ---------- 采集极限场景（2026-11-05） ----------

    @Test
    public void gatherWatch_scenario1_inTransit_recallBeforeArrival() {
        account(true, "{\"sub\":{\"gather_recall\":true},"
                + "\"strategy\":{\"gather_mode\":\"before_arrival\",\"gather_eta_sec\":\"2\"}}");
        TestEngine e = new TestEngine();
        e.responses.put("req_world_march_recall", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setMarchSourceForTest(engine -> gatherState(7001L, 120, 340, 2L, 1_000_100L));

        // 敌方采集队 1_000_090 到，早于我方 1_000_100；N=2 → 应在 1_000_088 挂起
        r.onPush(gatherPushAt(14001L, 4, OWN_UID, 120, 340, 1_000_090L), OWN_UID, e);
        r.tick(e);
        assertTrue("未到触发点不应发请求: " + e.requested, e.requested.isEmpty());

        nowMs.addAndGet(90_000L);
        sec.addAndGet(90L);
        assertTrue(r.tick(e));
        assertTrue("场景一应在途召回: " + e.requested, e.requested.contains("req_world_march_recall"));
    }

    @Test
    public void gatherWatch_scenario2_follower_offWorkBeforeEnemy() {
        account(true, "{\"sub\":{\"gather_recall\":true},"
                + "\"strategy\":{\"gather_mode\":\"before_arrival\",\"gather_eta_sec\":\"2\"}}");
        TestEngine e = new TestEngine();
        e.responses.put("req_world_march_off_work", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setMarchSourceForTest(engine -> gatherState(7002L, 121, 341, 3L, 1_000_500L));

        // 我方已落位、采集 1_000_500 结束；敌方 1_000_300 到 → 场景二
        r.onPush(gatherPushAt(14002L, 4, OWN_UID, 121, 341, 1_000_300L), OWN_UID, e);
        r.tick(e);
        assertTrue("未到 E-N 不应发请求: " + e.requested, e.requested.isEmpty());

        nowMs.addAndGet(300_000L);
        sec.addAndGet(300L);
        assertTrue(r.tick(e));
        assertTrue("场景二应撤矿: " + e.requested, e.requested.contains("req_world_march_off_work"));
    }

    @Test
    public void gatherWatch_antiHarassBelowThreshold_noRecall() {
        account(true, "{\"sub\":{\"gather_recall\":true,\"gather_anti_harass\":true},"
                + "\"strategy\":{\"gather_mode\":\"before_arrival\",\"gather_eta_sec\":\"2\","
                + "\"gather_troops_min\":\"5000\"}}");
        TestEngine e = new TestEngine();
        ServerShieldResponder r = responder();
        r.setMarchSourceForTest(engine -> gatherState(7006L, 125, 345, 3L,
                System.currentTimeMillis() / 1000L + 1_000L));

        Map<String, Object> push = withTroops(gatherPushAt(14006L, 4, OWN_UID, 125, 345,
                System.currentTimeMillis() / 1000L + 500L), 1_000L);
        r.onPush(push, OWN_UID, e);
        r.tick(e);

        assertTrue("低于防骚扰阈值不应召回: " + e.requested, e.requested.isEmpty());
    }

    @Test
    public void gatherWatch_enemyAfterGatherEnd_noRecall() {
        account(true, "{\"sub\":{\"gather_recall\":true},"
                + "\"strategy\":{\"gather_mode\":\"before_arrival\",\"gather_eta_sec\":\"2\"}}");
        TestEngine e = new TestEngine();
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setMarchSourceForTest(engine -> gatherState(7003L, 122, 342, 3L, 1_000_100L));

        // 敌方 1_000_500 才到，晚于采集结束 1_000_100 → 不召回
        r.onPush(gatherPushAt(14003L, 4, OWN_UID, 122, 342, 1_000_500L), OWN_UID, e);
        r.tick(e);
        assertTrue("采集结束后才到不召回: " + e.requested, e.requested.isEmpty());
    }

    @Test
    public void gatherWatch_landedEnemy_immediateOffWork() {
        account(true, "{\"sub\":{\"gather_recall\":true},"
                + "\"strategy\":{\"gather_mode\":\"before_arrival\",\"gather_eta_sec\":\"2\"}}");
        TestEngine e = new TestEngine();
        e.responses.put("req_world_march_off_work", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setMarchSourceForTest(engine -> gatherState(7004L, 123, 343, 3L, 1_000_500L));

        // 敌方已经落地（E <= now）→ 立即撤矿
        r.onPush(gatherPushAt(14004L, 2, OWN_UID, 123, 343, 999_900L), OWN_UID, e);
        assertTrue(r.tick(e));
        assertTrue("已落地应立即撤矿: " + e.requested, e.requested.contains("req_world_march_off_work"));
    }

    @Test
    public void gatherWatch_mapobjFallback_occupiedRecalls() {
        account(true, "{\"sub\":{\"gather_recall\":true},"
                + "\"strategy\":{\"gather_mode\":\"before_arrival\",\"gather_eta_sec\":\"2\"}}");
        TestEngine e = new TestEngine();
        GameMessage mapobj = new GameMessage();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", 0);
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", 2L);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("march_id", 999L);   // 非己方
        obj.put("map_resource", res);
        content.put("mapobj", obj);
        mapobj.setContent(content);
        e.responses.put("req_world_mapobj", mapobj);
        e.responses.put("req_world_march_off_work", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);
        r.setMarchSourceForTest(engine -> gatherState(7005L, 124, 344, 3L, 1_000_500L));

        r.tick(e);   // 新建 watch：本拍不查地图
        assertTrue("首个周期不查地图: " + e.requested, e.requested.isEmpty());
        nowMs.addAndGet(61_000L);
        sec.addAndGet(61L);
        assertTrue(r.tick(e));
        assertTrue("兜底应先查地图: " + e.requested, e.requested.contains("req_world_mapobj"));
        assertTrue("点位被占应撤矿", e.requested.contains("req_world_march_off_work"));
    }

    @Test
    public void shieldDefault_beforeArrival2s_waitsThenOpens() {
        account(true, "{\"sub\":{\"reactive\":true}}");   // 无 eta 键 → 新默认 2 秒
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(withEndtime(alliancePush(13080L, 2, OWN_UID, 1), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));
        assertTrue("默认应挂起而不是立即开", e.requested.isEmpty());
        assertTrue(new JSONObject(modText()).getString("reactive").contains("等待卡秒"));

        nowMs.addAndGet(299_000L);
        sec.addAndGet(299L);
        assertTrue(r.tick(e));
        assertEquals(List.of("req_city_item_free_use"), e.requested);
    }

    @Test
    public void shieldBackpack_usedBeforeDiamond() {
        account(true, "{\"sub\":{\"reactive\":true},"
                + "\"strategy\":{\"shield_mode\":\"immediate\"}}");
        TestEngine e = new TestEngine();
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("data", List.of(new LinkedHashMap<>(Map.of("id", 302301L, "count", 1L))));
        e.notifies.put("notify_backpack_init", bp);
        e.responses.put("req_use_item", msg(0));
        ServerShieldResponder r = responder();
        r.initLedgerFromEngine(e);

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        assertTrue("应优先用背包盾", e.requested.contains("req_use_item"));
        assertFalse("有背包盾不买钻石", e.requested.contains("req_shop_quick_buy"));
        assertTrue(new JSONObject(modText()).getString("reactive").contains("背包"));
    }

    @Test
    public void shieldDiamondBuy_buys2hThenUses() {
        account(true, "{\"sub\":{\"reactive\":true,\"shield_buy_diamond\":true},"
                + "\"strategy\":{\"shield_mode\":\"immediate\"}}");
        TestEngine e = new TestEngine();
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("data", List.of());
        e.notifies.put("notify_backpack_init", bp);
        e.responses.put("req_shop_quick_buy", msg(0));
        e.responses.put("req_use_item", msg(0));
        ServerShieldResponder r = responder();
        r.initLedgerFromEngine(e);

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        assertTrue("无盾且开关开 → 买 2 小时盾: " + e.requested, e.requested.contains("req_shop_quick_buy"));
        assertTrue("买后应使用: " + e.requested, e.requested.contains("req_use_item"));
        assertTrue(new JSONObject(modText()).getString("reactive").contains("钻石"));
    }

    @Test
    public void rallyAttack_type100_triggersShieldLikeWar() {
        account(true, "{\"sub\":{\"reactive\":true}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        // type=100 集结进攻自己城 → 按 WAR 触发护盾（网页版 RALLY_CITY_ATTACK 同口径）
        r.onPush(alliancePush(15001L, 100, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertTrue(new JSONObject(modText()).getString("reactive").contains("被进攻"));
    }

    // ================= 2026-11-07：预警推送形状/多波合并/集结主体/全量快照/del 形状 =================

    @Test
    public void singleMarchShape_triggersShield() {
        // 实证 2026-10-15 §2.2：敌进攻发射帧是 {"march":{...}} 单数形状（此前被解析器丢弃）。
        account(true, null);   // 默认 immediate
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", marchOf(13080L, 2, OWN_UID));
        r.onPush(content, OWN_UID);

        assertTrue(r.tick(e));
        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertEquals("WAR", r.lastEventKind);
    }

    @Test
    public void multiWave_keepsEarliestEta() {
        // 多波进攻：后到的更晚波次不得推迟已记录的更早卡秒点，否则第一波到达时无盾。
        account(true, "{\"sub\":{\"reactive\":true}}");   // before_arrival 默认 2 秒
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(withEndtime(alliancePush(13080L, 2, OWN_UID, 1), sec.get() + 300L), OWN_UID);
        assertFalse(r.tick(e));
        long earliestCheck = r.etaCheckAtMs;
        assertTrue("首波应挂起到到达前 2 秒", earliestCheck > 0L);

        // 第二波更晚（+600s）：不得把卡秒点推迟到第二波
        r.onPush(withEndtime(alliancePush(13081L, 2, OWN_UID, 1), sec.get() + 600L), OWN_UID);
        assertFalse(r.tick(e));
        assertEquals("多波风险应保留最早到达时间", earliestCheck, r.etaCheckAtMs);
        assertTrue(e.requested.isEmpty());

        // 到第一波到达前 1 秒：开盾（而不是等第二波）
        nowMs.addAndGet(299_000L);
        sec.addAndGet(299L);
        assertTrue(r.tick(e));
        assertEquals(List.of("req_city_item_free_use"), e.requested);
    }

    @Test
    public void rallyMainType102_triggersShield() {
        // 实证 A-1：102=集结主体（此前只认 100，集结主体打城不开盾）。
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(15002L, 102, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        assertEquals(List.of("req_city_item_free_use"), e.requested);
        assertEquals("WAR", r.lastEventKind);
    }

    @Test
    public void fullSnapshotProto_triggersShield() {
        // 登录/重连全量快照 notify_world_situation_info（此前不在白名单，在途进攻丢失）。
        // 真实形状是 situation_info（见 AssetParsers.worldSituation），不是 marches。
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        ServerShieldResponder r = responder();
        r.attach(e);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", 9L);
        entry.put("march", marchOf(13080L, 2, OWN_UID));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("situation_info", Map.of("9.0", entry));
        e.push("notify_world_situation_info", snapshot);

        assertEquals("全量快照槽位应记录映射",
                Long.valueOf(13080L), r.situationSlotMarch.get(9L));
        assertTrue(r.tick(e));
        assertEquals(List.of("req_city_item_free_use"), e.requested);
    }

    @Test
    public void situationDel_idArr_clearsSlotMapping() {
        // 观察哨 del 用 {"id_arr":[9.0]}（槽位 id）表达；应按槽位映射清掉敌方行军缓存。
        account(true, null);
        TestEngine e = new TestEngine();
        ServerShieldResponder r = responder();
        r.attach(e);

        e.push("notify_world_situation_info_add", worldPush(13080L, 2));
        assertEquals("槽位 9.0 应映射到 marchId 13080",
                Long.valueOf(13080L), r.situationSlotMarch.get(9L));

        Map<String, Object> del = new LinkedHashMap<>();
        del.put("id_arr", List.of("9.0"));
        e.push("notify_world_situation_info_del", del);
        assertFalse("del 后槽位映射应清理", r.situationSlotMarch.containsKey(9L));
    }

    @Test
    public void situationDel_topLevelId_removesTrackedMarch() {
        // 设计契约：alliance_situation_del 形如 {"id":11261}（无 marches 包裹）。
        account(true, null);
        TestEngine e = new TestEngine();
        ServerShieldResponder r = responder();
        r.attach(e);

        e.push("notify_world_alliance_situation_add", alliancePush(11261L, 2, OWN_UID, 1));
        assertEquals(1, r.trackedEnemyMarchCount());

        Map<String, Object> del = new LinkedHashMap<>();
        del.put("id", 11261L);
        e.push("notify_world_alliance_situation_del", del);
        assertEquals(0, r.trackedEnemyMarchCount());
    }

    // ================= 2026-11-06 决策：护盾只读新键（旧 eta_sec/troops_min 不再兼容） =================

    @Test
    public void legacyKeys_ignored_shieldReadsNewKeysOnly() throws Exception {
        // 只写旧键（eta_sec/troops_min）的存量配置：一律按新键缺省值处理——
        // shield_mode 缺省 before_arrival、shield_eta_sec 缺省 2、shield_troops_min 缺省 0。
        TestEngine e = new TestEngine();
        Map<String, JSONObject> params = new HashMap<>();
        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"eta_sec\":\"0\",\"troops_min\":\"100000\"}}"));
        TaskContext tc = new TaskContext(e, "111", SID, state, params, "测试");
        ServerShieldResponder r = responder();

        assertEquals("before_arrival", r.shieldMode(tc));   // 旧 eta_sec=0 不再映射 immediate
        assertEquals(2L, r.shieldEtaSec(tc));               // 旧 eta_sec 值被忽略 → 默认 2
        assertEquals(0L, r.shieldTroopsMin(tc));            // 旧 troops_min 被忽略 → 默认 0（不限）
    }

    @Test
    public void newKeys_takeEffect_inShieldConfig() throws Exception {
        TestEngine e = new TestEngine();
        Map<String, JSONObject> params = new HashMap<>();
        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"shield_mode\":\"immediate\",\"shield_eta_sec\":\"60\","
                        + "\"shield_troops_min\":\"5000\"}}"));
        TaskContext tc = new TaskContext(e, "111", SID, state, params, "测试");
        ServerShieldResponder r = responder();

        assertEquals("immediate", r.shieldMode(tc));
        assertEquals(60L, r.shieldEtaSec(tc));
        assertEquals(5000L, r.shieldTroopsMin(tc));
    }

    // ================= 2026-11-07：免费盾假成功 / 幂等窗对齐 / 失败保留风险 =================

    @Test
    public void freeShieldMissingCode_notClaimed_retryCooldown() {
        // 响应缺 code 无法确认开盾：不得判 CLAIMED（否则假写 8h 盾账本 + 8h 幂等窗）。
        account(true, "{\"sub\":{\"reactive\":true},"
                + "\"strategy\":{\"shield_mode\":\"immediate\"}}");
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        GameMessage noCode = new GameMessage();
        noCode.setContent(new LinkedHashMap<>());
        e.responses.put("req_city_item_free_use", noCode);
        ServerShieldResponder r = responder();

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));

        JSONObject mod = new JSONObject(modText());
        assertFalse("缺 code 不能写已开盾",
                mod.getString("reactive").contains("8小时免费护盾已开启"));
        assertTrue("应保留未开盾/重试语义", mod.getString("reactive").contains("未开盾"));
        long t0 = System.currentTimeMillis();
        assertTrue("缺 code 应按失败冷却而非 8h 幂等窗",
                r.nextAttemptAt.get() <= t0 + ServerShieldResponder.RETRY_COOLDOWN_MILLIS + 2000);
    }

    @Test
    public void shieldExpired_opensAgain() {
        // 盾到期/被撤销后：幂等由账本判定，不能被历史 8h nextAttemptAt 长期挡住。
        account(true, null);   // immediate
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(0));
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_700_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        assertEquals(1, e.requested.size());

        // 9 小时后盾已过期；新攻击应再次开盾
        nowMs.addAndGet(9 * 3600_000L);
        sec.addAndGet(9 * 3600L);
        r.onPush(alliancePush(13081L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        assertEquals("盾过期后应允许再次开盾", 2, e.requested.size());
    }

    @Test
    public void noAvailableShield_keepsRisk_retriesAfterCooldown() {
        // 免费盾不可用 + 无背包 + 钻石关：风险不能被一次失败消费，5 分钟后应重试。
        account(true, null);
        TestEngine e = new TestEngine();
        presetCityItem(e, 42L);
        e.responses.put("req_city_item_free_use", msg(51));   // 今日已用
        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        AtomicLong sec = new AtomicLong(1_700_000_000L);
        ServerShieldResponder r = responder(nowMs, sec);

        r.onPush(alliancePush(13080L, 2, OWN_UID, 1), OWN_UID);
        assertTrue(r.tick(e));
        assertEquals(1, e.requested.size());

        // 5 分钟后（无新推送）：风险仍保留 → 自动重试
        nowMs.addAndGet(ServerShieldResponder.RETRY_COOLDOWN_MILLIS);
        sec.addAndGet(ServerShieldResponder.RETRY_COOLDOWN_MILLIS / 1000L);
        assertTrue(r.tick(e));
        assertEquals("失败后 5 分钟应重试", 2, e.requested.size());
    }

    // ================= 2026-11-08：钻石购盾日预算用户可配（默认 1200 钻） =================

    @Test
    public void diamondBudget_defaultIs1200AndAllowsThreeBuys() {
        TestEngine e = new TestEngine();
        TaskContext tc = new TaskContext(e, "111", SID, state, new HashMap<>(), "测试");
        ServerShieldResponder r = responder();
        long sec = 1_700_000_000L;

        assertEquals(1200L, r.diamondBudget(tc));
        assertTrue(r.allowDiamondBuy(tc, sec));
        r.recordDiamondBuy(sec);
        r.recordDiamondBuy(sec);
        assertTrue("默认预算 1200 钻可买 3 个（还剩 1 个）", r.allowDiamondBuy(tc, sec));
        r.recordDiamondBuy(sec);
        assertFalse("3 个后预算用尽", r.allowDiamondBuy(tc, sec));
    }

    @Test
    public void diamondBudget_custom800_allowsTwoBuys() {
        TestEngine e = new TestEngine();
        Map<String, JSONObject> params = new HashMap<>();
        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"shield_diamond_budget\":\"800\"}}"));
        TaskContext tc = new TaskContext(e, "111", SID, state, params, "测试");
        ServerShieldResponder r = responder();
        long sec = 1_700_000_000L;

        assertEquals(800L, r.diamondBudget(tc));
        r.recordDiamondBuy(sec);
        assertTrue(r.allowDiamondBuy(tc, sec));
        r.recordDiamondBuy(sec);
        assertFalse("800 钻只够 2 个", r.allowDiamondBuy(tc, sec));
    }

    @Test
    public void diamondBudget_zeroDisablesAndInvalidFallsBack() {
        TestEngine e = new TestEngine();
        Map<String, JSONObject> params = new HashMap<>();
        ServerShieldResponder r = responder();

        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"shield_diamond_budget\":\"0\"}}"));
        TaskContext zero = new TaskContext(e, "111", SID, state, params, "测试");
        assertEquals(0L, r.diamondBudget(zero));
        assertFalse("预算 0 = 不购买", r.allowDiamondBuy(zero, 1_700_000_000L));

        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"shield_diamond_budget\":\"abc\"}}"));
        TaskContext bad = new TaskContext(e, "111", SID, state, params, "测试");
        assertEquals("非法值回退 1200", 1200L, r.diamondBudget(bad));

        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"shield_diamond_budget\":\"-5\"}}"));
        TaskContext negative = new TaskContext(e, "111", SID, state, params, "测试");
        assertEquals("负数回退 1200", 1200L, r.diamondBudget(negative));

        params.put("battle_shield", new JSONObject(
                "{\"strategy\":{\"shield_diamond_budget\":\"150000\"}}"));
        TaskContext over = new TaskContext(e, "111", SID, state, params, "测试");
        assertEquals("超上限夹到 100000", 100000L, r.diamondBudget(over));
    }

    private static Map<String, Object> marchOf(long id, long type, long targetUid) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("target_type", 1);
        m.put("atk_city", atk);
        return m;
    }
}
