package com.carlos.vaultserver;

import com.carlos.vaultserver.watchpoint.WatchPointEngine;
import com.carlos.vaultserver.watchpoint.WatchPointStore;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-04：敌情接收口径（{@link WarNotify#classifyMarch}）纯函数。
 *
 * <p>战争通知与守护盟友共用同一口径：巨兽/野外集结、同联盟互打、侦查、加入集结、返程、
 * 打自己城、采集/驻防/狩猎一律不算敌情；缓存缺失时对应条件不拒绝（不因没扫全地图漏报）。
 */
public class WarNotifyClassifyTest {

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

    @Test
    public void singleAttackOnAllyCity_accepted() {
        installCache();
        WatchPointEngine.observeCity(ALLY, SELF_AID, 120, 120, "[友]城");
        WatchPointEngine.observeCity(ENEMY, ENEMY_AID, 500, 500, "[敌]城");
        WarNotify.MarchVerdict v = WarNotify.classifyMarch("notify_player_march_add",
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
        WatchPointEngine.observeCity(3001L, SELF_AID, 130, 130, "[友2]城");
        WarNotify.MarchVerdict v = WarNotify.classifyMarch("notify_player_march_add",
                march(2L, 3001L, 2L, 2L, 130, 130, 120, 120));
        assertFalse(v.accepted);
        assertEquals("same_alliance", v.reject);
    }

    @Test
    public void scoutFollowerLanded_rejected() {
        installCache();
        WatchPointEngine.observeCity(ALLY, SELF_AID, 120, 120, "[友]城");
        WatchPointEngine.observeCity(ENEMY, ENEMY_AID, 500, 500, "[敌]城");
        assertEquals("scout", WarNotify.classifyMarch("notify_player_march_add",
                march(3L, ENEMY, 1L, 2L, 500, 500, 120, 120)).reject);
        assertEquals("state", WarNotify.classifyMarch("notify_player_march_add",
                march(4L, ENEMY, 2L, 3L, 500, 500, 120, 120)).reject);
        assertEquals("follower", WarNotify.classifyMarch("notify_player_march_add",
                march(5L, ENEMY, 99L, 2L, 500, 500, 120, 120)).reject);
    }

    @Test
    public void gatherAndGarrison_rejectedAsNonPvp() {
        installCache();
        assertEquals("type", WarNotify.classifyMarch("notify_player_march_add",
                march(6L, ENEMY, 4L, 2L, 500, 500, 120, 120)).reject);
        assertEquals("type", WarNotify.classifyMarch("notify_player_march_add",
                march(7L, ENEMY, 5L, 2L, 500, 500, 120, 120)).reject);
    }

    @Test
    public void behemothRally_notCity_rejected() {
        installCache();
        WatchPointEngine.observeCity(ALLY, SELF_AID, 120, 120, "[友]城");
        // 巨兽集结：终点是 boss 点（无城）→ PvE，不是打盟友
        assertEquals("rally_not_city", WarNotify.classifyMarch("notify_mass_march_add",
                march(8L, ENEMY, 102L, 2L, 500, 500, 900, 900)).reject);
    }

    @Test
    public void loopbackAndSelfTarget_rejected() {
        installCache();
        WatchPointEngine.observeCity(ENEMY, ENEMY_AID, 500, 500, "[敌]城");
        // 起点=终点（返程/援助回自己城）
        assertEquals("loopback", WarNotify.classifyMarch("notify_player_march_add",
                march(9L, ENEMY, 2L, 2L, 500, 500, 500, 500)).reject);
        // 起点无城、终点=发起人自己的城 → self
        assertEquals("self", WarNotify.classifyMarch("notify_player_march_add",
                march(10L, ENEMY, 2L, 2L, 300, 300, 500, 500)).reject);
    }

    @Test
    public void cityCacheMissing_doesNotRejectRealThreat() {
        // 未装观察点缓存：cityAt=null → 不据此拒绝（防首扫未完成时漏报真实敌情）
        WarNotify.MarchVerdict v = WarNotify.classifyMarch("notify_player_march_add",
                march(11L, ENEMY, 2L, 2L, 500, 500, 120, 120));
        assertTrue("缓存缺失不得拒绝真实威胁", v.accepted);
    }
}
