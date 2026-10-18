package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

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

        TestEngine() {
            super(null);
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
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            argLog.add(args);
            return responses.get(proto);
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
        if (battleShieldParams != null) {
            a.roleParams.put(String.valueOf(SID),
                    new LinkedHashMap<>(Map.of("battle_shield", battleShieldParams)));
        }
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
        return a;
    }

    private ServerShieldResponder responder() {
        return new ServerShieldResponder("111", SID, state, vault);
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
        // 开盾成功 → 8 小时幂等窗
        long cool = r.nextAttemptAt.get();
        long t0 = System.currentTimeMillis();
        assertTrue(cool >= t0 + ServerShieldResponder.SHIELD_ACTIVE_MILLIS - 2000);
        assertTrue(cool <= t0 + ServerShieldResponder.SHIELD_ACTIVE_MILLIS + 2000);
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
