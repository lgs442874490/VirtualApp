package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.watchpoint.WatchPointEngine;
import com.carlos.vaultserver.watchpoint.WatchPointStore;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P1 等价测试：AlertRules/AlertText 抽取后，行为必须与迁移前逐条一致。
 *
 * <p>断言直接对齐 {@code WarNotifyClassifyTest} 的 7 个用例与
 * {@code WatchPointAlertFilterTest} 的纯函数/文案期望；这些期望来自迁移前基线（6bfaafe）。
 */
public class AlertRulesEquivalenceTest {

    private static final long ENEMY = 49422287L;
    private static final long ALLY = 2002L;
    private static final long ENEMY_AID = 888L;
    private static final long SELF_AID = 777L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void clean() {
        WatchPointEngine.install(null);
    }

    private void installCache() {
        WatchPointStore wps = new WatchPointStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        WatchPointEngine.install(new WatchPointEngine(null, wps, null, null));
    }

    private static Map<String, Object> point(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", (long) x);
        p.put("y", (long) y);
        return p;
    }

    private static Map<String, Object> march(long id, long owner, long type, long state,
                                             int sx, int sy, int ex, int ey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("type", type);
        m.put("state", state);
        m.put("startpoint", point(sx, sy));
        m.put("endpoint", point(ex, ey));
        m.put("endtime", System.currentTimeMillis() / 1000L + 120L);
        return m;
    }

    // ---------------------------------------------------------------- 与 WarNotifyClassifyTest 对齐

    @Test
    public void singleAttackOnAllyCity_accepted() {
        installCache();
        WatchPointEngine.observeCity(ALLY, SELF_AID, 120, 120, "[友]城");
        WatchPointEngine.observeCity(ENEMY, ENEMY_AID, 500, 500, "[敌]城");
        AlertVerdict v = AlertRules.classifyMarch("notify_player_march_add",
                march(1L, ENEMY, 2L, 2L, 500, 500, 120, 120));
        assertTrue("打盟友城 = 敌情", v.accepted);
        assertNull(v.reject);
        assertEquals(ENEMY, v.ownerUid);
        assertEquals(2L, v.type);
    }

    @Test
    public void sameAllianceTarget_rejected() {
        installCache();
        WatchPointEngine.observeCity(ALLY, SELF_AID, 120, 120, "[友]城");
        WatchPointEngine.observeCity(ENEMY, SELF_AID, 500, 500, "[同]城");
        AlertVerdict v = AlertRules.classifyMarch("notify_player_march_add",
                march(2L, ENEMY, 2L, 2L, 500, 500, 120, 120));
        assertFalse(v.accepted);
        assertEquals("same_alliance", v.reject);
    }

    @Test
    public void scoutFollowerLanded_rejected() {
        installCache();
        assertEquals("scout", AlertRules.classifyMarch("notify_player_march_add",
                march(3L, ENEMY, 1L, 2L, 500, 500, 120, 120)).reject);
        assertEquals("state", AlertRules.classifyMarch("notify_player_march_add",
                march(4L, ENEMY, 2L, 3L, 500, 500, 120, 120)).reject);
        assertEquals("follower", AlertRules.classifyMarch("notify_player_march_add",
                march(5L, ENEMY, 99L, 2L, 500, 500, 120, 120)).reject);
    }

    @Test
    public void gatherAndGarrison_rejectedAsNonPvp() {
        installCache();
        assertEquals("type", AlertRules.classifyMarch("notify_player_march_add",
                march(6L, ENEMY, 4L, 2L, 500, 500, 120, 120)).reject);
        assertEquals("type", AlertRules.classifyMarch("notify_player_march_add",
                march(7L, ENEMY, 5L, 2L, 500, 500, 120, 120)).reject);
    }

    @Test
    public void behemothRally_notCity_rejected() {
        installCache();
        assertEquals("rally_not_city", AlertRules.classifyMarch("notify_mass_march_add",
                march(8L, ENEMY, 102L, 2L, 500, 500, 700, 700)).reject);
    }

    @Test
    public void loopbackAndSelfTarget_rejected() {
        installCache();
        // 起点==终点 → loopback
        assertEquals("loopback", AlertRules.classifyMarch("notify_player_march_add",
                march(9L, ENEMY, 2L, 2L, 500, 500, 500, 500)).reject);
        // 起点≠终点，但终点是发起人自己的城 → self
        WatchPointEngine.observeCity(ENEMY, ENEMY_AID, 500, 500, "[敌]城");
        assertEquals("self", AlertRules.classifyMarch("notify_player_march_add",
                march(10L, ENEMY, 2L, 2L, 600, 600, 500, 500)).reject);
    }

    @Test
    public void cityCacheMissing_doesNotRejectRealThreat() {
        installCache();
        AlertVerdict v = AlertRules.classifyMarch("notify_player_march_add",
                march(11L, ENEMY, 2L, 2L, 500, 500, 120, 120));
        assertTrue("缓存缺失不得拒绝真实威胁", v.accepted);
    }

    // ---------------------------------------------------------------- 与 WatchPointAlertFilterTest 对齐

    @Test
    public void selfLoop_keepsOldSemantics() {
        assertTrue(AlertRules.isSelfLoop(5, 5, 5, 5, -1L, 1L));
        assertTrue(AlertRules.isSelfLoop(1, 1, 2, 2, 7L, 7L));
        assertFalse(AlertRules.isSelfLoop(1, 1, 2, 2, 0L, 1L));
        assertFalse(AlertRules.isSelfLoop(-1, -1, 3, 3, 0L, 1L));
    }

    @Test
    public void incomingAllowed_keepsOldSemantics() {
        Set<Long> friends = new LinkedHashSet<>(Collections.singletonList(SELF_AID));
        assertTrue(AlertRules.incomingAllowed(false, ENEMY_AID, SELF_AID, friends, 2L, true));
        assertFalse(AlertRules.incomingAllowed(true, ENEMY_AID, SELF_AID, friends, 2L, true));
        assertFalse(AlertRules.incomingAllowed(false, ENEMY_AID, 0L, friends, 2L, true));
        assertFalse(AlertRules.incomingAllowed(false, ENEMY_AID, 12345L, friends, 2L, true));
        assertFalse(AlertRules.incomingAllowed(false, SELF_AID, SELF_AID, friends, 2L, true));
        assertFalse(AlertRules.incomingAllowed(false, ENEMY_AID, SELF_AID, friends, 99L, false));
    }

    @Test
    public void monitorAllowed_keepsOldSemantics() {
        assertTrue(AlertRules.monitorAllowed(ENEMY_AID, SELF_AID));
        assertFalse(AlertRules.monitorAllowed(ENEMY_AID, ENEMY_AID));
    }

    @Test
    public void threatLevel_keepsOldSemantics() {
        assertEquals(ThreatLevel.UNKNOWN, AlertRules.threatLevel(0L, 1000L));
        assertEquals(ThreatLevel.FEINT, AlertRules.threatLevel(999L, 1000L));
        assertEquals(ThreatLevel.THREAT, AlertRules.threatLevel(1000L, 1000L));
        assertEquals(ThreatLevel.THREAT, AlertRules.threatLevel(5L, 0L));
    }

    @Test
    public void typePredicates_keepOldSemantics() {
        assertTrue(AlertRules.isPvpType(2L));
        assertTrue(AlertRules.isPvpType(102L));
        assertFalse(AlertRules.isPvpType(100L));
        assertTrue(AlertRules.isRallyType(102L));
        assertFalse(AlertRules.isRallyType(2L));
    }

    @Test
    public void rallyFollower_keepsOldSemantics() {
        assertTrue(AlertRules.isRallyFollower(new LinkedHashMap<>(), 99L));
        Map<String, Object> withSlave = new LinkedHashMap<>();
        withSlave.put("mass_slave", Collections.singletonMap("id", 1L));
        assertTrue(AlertRules.isRallyFollower(withSlave, 2L));
        assertFalse(AlertRules.isRallyFollower(new LinkedHashMap<>(), 2L));
    }

    // ---------------------------------------------------------------- 文案逐字对齐

    @Test
    public void alertText_incomingMatchesLegacyFormat() {
        String text = AlertText.incoming(AlertText.a2Behavior(2L), AlertText.level(85442L, 1000L),
                "酒当歌", 85442L, "[yYz]莲花emira", 493, 580, 66L, "12:34:56");
        assertTrue(text.startsWith("🚨 敌袭·进攻\n"));
        assertTrue(text.contains("级别：🔴 威胁进攻"));
        assertTrue(text.contains("攻击者：酒当歌"));
        assertTrue(text.contains("兵力：85442"));
        assertTrue(text.contains("目标：[yYz]莲花emira(493,580)"));
        assertTrue(text.contains("ETA：1分6秒"));
        assertTrue(text.endsWith("时间：12:34:56"));
    }

    @Test
    public void alertText_incomingWithoutEndpointSkipsCoords() {
        String text = AlertText.incoming(AlertText.a2Behavior(2L), AlertText.level(0L, 1000L),
                "未知名玩家", 0L, "", 0, 0, false, 0L, "12:00:00");
        assertTrue(text.contains("目标：（未知）\n"));
        assertFalse(text.contains("(0,0)"));
    }
}
