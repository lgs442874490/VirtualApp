package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.guardian.WarState;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P7 vault-server 战争状态对等单测：推送解析（复用 ServerShieldResponder 实证形状）→
 * H0/H1/H2/H4 信号、全敌静默退出、p:war.* 参数装配、战时任务闸（gateNote/roundYieldNote/
 * wartimeYield）、/status warState 摘要。时间用真实时钟 + 偏移（信号入账时刻=真实 now）。
 */
public class ServerWarStateTest {

    private static final long SID = 913L;
    private static final long OWNER = 49422287L;
    private static final long OWN_UID = 1001L;
    private static final long M = 60_000L;

    private final List<String> created = new ArrayList<>();

    private ServerWarState ws(String fpid) {
        ServerWarState w = ServerWarState.register(fpid, SID);
        created.add(fpid);
        return w;
    }

    @After
    public void clean() {
        for (String fpid : created) {
            ServerWarState.unregister(fpid, SID);
        }
        created.clear();
    }

    // ---------------- 夹具 ----------------

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 联盟域推送（实证形状：to_marchs[]，atk_city.targetid）。 */
    private static Map<String, Object> alliancePush(Map<String, Object>... marches) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(marches));
        return c;
    }

    /** 一般世界推送（观察哨形状：marches.{槽位:{march}}；ownUid=自己城才触发）。 */
    private static Map<String, Object> worldPush(Map<String, Object> march, long targetUid) {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("uid", targetUid);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("map_city", city);
        march.put("target", target);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("marches", Map.of("9.0", slot));
        return c;
    }

    private static Map<String, Object> march(long id, long type, long endSec, long owner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("type", type);
        m.put("endtime", endSec);
        return m;
    }

    private static Map<String, Object> withTarget(Map<String, Object> m, long targetUid) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        m.put("atk_city", atk);
        return m;
    }

    /** 带自定义 p:war.* 参数的判定实例（Account 无需入库）。 */
    private ServerWarState wsWithParams(String fpid, String warJson) {
        ServerWarState w = ws(fpid);
        VaultStore.Account a = new VaultStore.Account(fpid);
        Map<String, String> roleParams = new LinkedHashMap<>();
        roleParams.put("war", warJson);
        a.roleParams.put(String.valueOf(SID), roleParams);
        ServerWarState.configure(a, SID);
        return w;
    }

    // ---------------- H0/H2/H1/H4 经推送触发 ----------------

    @Test
    public void allianceScout_entersWartime_gateBlocksNonAllowlist() {
        ServerWarState w = ws("fp-h0");
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(1, 1, nowS() + 600, OWNER), 777L)), OWN_UID);
        assertTrue("H0 侦查即入", w.core.wartime());
        assertEquals(WarState.Trigger.H0_SCOUT, w.core.reason());
        assertEquals("战时 allowlist 外拦截", true, ServerWarState.gateNote("fp-h0", SID, "gather") != null);
        assertNull("战斗支援放行", ServerWarState.gateNote("fp-h0", SID, "battle_shield"));
        assertNull("军医放行", ServerWarState.gateNote("fp-h0", SID, "medic"));
    }

    @Test
    public void worldPush_onlyOwnCityTriggers() {
        ServerWarState w = ws("fp-world");
        Map<String, Object> m = march(11, 1, nowS() + 600, OWNER);
        w.onPush("notify_world_situation_info_add", worldPush(m, 4242424L), OWN_UID);
        assertFalse("别人城被侦查不触发", w.core.wartime());
        Map<String, Object> m2 = march(12, 1, nowS() + 600, OWNER);
        w.onPush("notify_world_situation_info_add", worldPush(m2, OWN_UID), OWN_UID);
        assertTrue("自己城被侦查触发", w.core.wartime());
    }

    @Test
    public void ownMarchesIgnored_andFriendlyUidFiltered() {
        ServerWarState w = wsWithParams("fp-friendly",
                "{\"sub\":{\"scout_hard\":true},\"strategy\":{\"friendly_uids\":\"" + OWNER + "\"}}");
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(21, 1, nowS() + 600, OWNER), 777L)), OWN_UID);
        assertFalse("白名单小号侦查不算敌情（§7.31）", w.core.wartime());
        // 自家行军（owner==ownUid）也不入
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(22, 2, nowS() + 600, OWN_UID), 777L)), OWN_UID);
        assertFalse(w.core.wartime());
    }

    @Test
    public void h2_twoConcurrentAttacks_entersWartime() {
        ServerWarState w = ws("fp-h2");
        long end = nowS() + 600;
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(
                        withTarget(march(31, 2, end, OWNER), 777L),
                        withTarget(march(32, 2, end, OWNER + 1), 778L)),
                OWN_UID);
        assertTrue("两路并发 → H2", w.core.wartime());
        assertEquals(WarState.Trigger.H2_MULTI_PRONG, w.core.reason());
        assertEquals(2, w.core.inFlightAttacks());
    }

    @Test
    public void h1_landingDel_entersWartime() {
        ServerWarState w = ws("fp-h1");
        long t0 = System.currentTimeMillis();
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(41, 2, t0 / 1000 + 1, OWNER), 777L)), OWN_UID);
        assertFalse(w.core.wartime());   // 单条进攻只有软 +30
        w.onPush("notify_world_alliance_situation_del",
                alliancePush(march(41, 2, t0 / 1000 + 1, OWNER)), OWN_UID);
        assertTrue("落地（remain≤2s）→ H1", w.core.wartime());
        assertEquals(WarState.Trigger.H1_BATTLE, w.core.reason());
    }

    @Test
    public void h4_fiveFeints_withinWindow_entersWartime() {
        ServerWarState w = ws("fp-h4");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            long end = t0 / 1000 + 600;   // 距落地 10min → del 判召回（佯攻）
            w.onPush("notify_world_alliance_situation_add",
                    alliancePush(withTarget(march(51 + i, 2, end, OWNER), 777L)), OWN_UID);
            w.onPush("notify_world_alliance_situation_del",
                    alliancePush(march(51 + i, 2, end, OWNER)), OWN_UID);
            if (i < 4) assertFalse("前 4 次佯攻不触发", w.core.wartime());
        }
        assertTrue("5 次/30min 佯攻潮 → H4", w.core.wartime());
        assertEquals(WarState.Trigger.H4_FEINT_BURST, w.core.reason());
    }

    // ---------------- 退出判据 ----------------

    @Test
    public void quietExit_afterWindow_noNewSignals() {
        ServerWarState w = ws("fp-quiet");
        long t0 = System.currentTimeMillis();
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(61, 1, t0 / 1000 + 600, OWNER), 777L)), OWN_UID);
        assertTrue(w.core.wartime());
        w.tick(t0 + 29 * M);
        assertTrue("29min 不退", w.core.wartime());
        w.tick(t0 + 31 * M);
        assertFalse("全敌静默满 30min 退出", w.core.wartime());
        assertNull("平时不拦截", ServerWarState.gateNote("fp-quiet", SID, "gather"));
    }

    // ---------------- p:war.* 参数与闸 ----------------

    @Test
    public void configQuietExit_fromRoleParams() {
        ServerWarState w = wsWithParams("fp-cfg", "{\"strategy\":{\"quiet_exit\":\"20\"}}");
        long t0 = System.currentTimeMillis();
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(71, 1, t0 / 1000 + 600, OWNER), 777L)), OWN_UID);
        assertTrue(w.core.wartime());
        w.tick(t0 + 21 * M);
        assertFalse("quiet_exit=20min 生效", w.core.wartime());
    }

    @Test
    public void scoutHardOff_scoutDegradesToSoft_alertGatesFieldTasks() {
        ServerWarState w = wsWithParams("fp-alert", "{\"sub\":{\"scout_hard\":false}}");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            w.onPush("notify_world_alliance_situation_add",
                    alliancePush(withTarget(march(81 + i, 1, t0 / 1000 + 600, OWNER), 777L)), OWN_UID);
        }
        assertEquals("侦查降级软信号：3×15=45 ≥ 25 → 预警（非战争态）",
                WarState.State.ALERT, w.core.state());
        assertTrue(ServerWarState.gateNote("fp-alert", SID, "gather").contains("预警"));
        assertNull("预警级不合成整轮让位", ServerWarState.roundYieldNote("fp-alert", SID, ""));
    }

    @Test
    public void roundYield_and_wartimeYield() {
        ServerWarState w = ws("fp-yield");
        assertNull("平时不让位", ServerWarState.roundYieldNote("fp-yield", SID, ""));
        assertFalse(ServerWarState.wartimeYield("fp-yield"));
        long t0 = System.currentTimeMillis();
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(91, 1, t0 / 1000 + 600, OWNER), 777L)), OWN_UID);
        assertNotNull("战时整轮让位", ServerWarState.roundYieldNote("fp-yield", SID, ""));
        assertNotNull("单任务非 allowlist 让位", ServerWarState.roundYieldNote("fp-yield", SID, "gather"));
        assertNull("单任务 allowlist 放行", ServerWarState.roundYieldNote("fp-yield", SID, "battle_shield"));
        assertTrue("调度器账号级让位", ServerWarState.wartimeYield("fp-yield"));
    }

    // ---------------- /status 摘要 ----------------

    @Test
    public void summaryJson_shapes() {
        assertNull("无实例返回 null（App 端按未知处理）", ServerWarState.summaryJson("fp-none", SID));
        ServerWarState w = ws("fp-sum");
        long t0 = System.currentTimeMillis();
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(101, 1, t0 / 1000 + 600, OWNER), 777L)), OWN_UID);
        JSONObject o = ServerWarState.summaryJson("fp-sum", 0);   // sid 查不到 → fpid 兜底
        assertNotNull(o);
        assertEquals("WARTIME", o.getString("state"));
        assertTrue(o.getInt("enemies") >= 1);
        assertTrue(o.getLong("topEnemyUid") == OWNER);
        assertTrue(o.getString("explain").contains("战争态"));
    }
}
