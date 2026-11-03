package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.watchpoint.WatchPointEngine;
import com.carlos.vaultserver.watchpoint.WatchPointStore;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-04 真机记录分析：集结巨兽/野外集结 vs 真实危险。
 *
 * <p>与战争通知同口径：集结类（100/102）只有终点命中观察点城缓存里的「城」才算敌情；
 * 巨兽/野外集结的终点是 boss/资源点，cityAt=null，不应进入守护发射链。
 */
public class GuardianPveFilterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void clean() {
        WatchPointEngine.install(null);
    }

    private static ServerGuardianEngine.Config cfg() {
        ServerGuardianEngine.Config c = new ServerGuardianEngine.Config();
        c.enabled = true;
        c.attackTypes = new LinkedHashSet<>(java.util.Arrays.asList(2L, 100L, 102L));
        c.troopsMin = 0L;
        return c;
    }

    private static ServerGuardianEngine.Threat threat(long type, int x, int y, long endSec) {
        return new ServerGuardianEngine.Threat(1L, 2L, type, 1001L, x, y, true,
                endSec, System.currentTimeMillis(), 1000L, true, 0L);
    }

    private void installCity(long uid, long aid, int x, int y) {
        WatchPointStore wps = new WatchPointStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        WatchPointEngine wpe = new WatchPointEngine(null, wps, null, null);
        wpe.seedCityForTest(uid, aid, x, y, "[T]城");
        WatchPointEngine.install(wpe);
    }

    @Test
    public void rallyToCity_isThreat() {
        installCity(1001L, 777L, 100, 100);
        long end = System.currentTimeMillis() / 1000L + 120L;
        ServerGuardianEngine.Decision d =
                ServerGuardianEngine.decide(threat(102L, 100, 100, end), cfg(), System.currentTimeMillis());
        assertTrue("集结目标命中城 → 真实威胁", d.hit);
    }

    @Test
    public void rallyToNonCity_isPveAndNotThreat() {
        installCity(1001L, 777L, 100, 100);
        long end = System.currentTimeMillis() / 1000L + 120L;
        ServerGuardianEngine.Decision d =
                ServerGuardianEngine.decide(threat(102L, 300, 300, end), cfg(), System.currentTimeMillis());
        assertFalse("巨兽/野外集结（终点无城）不驻防", d.hit);
        assertTrue("原因码应可识别：" + d.reason,
                d.reason != null && d.reason.startsWith("pve_rally"));
    }

    @Test
    public void singleAttackToNonCity_stillThreat() {
        // type=2 单人进攻不受“集结目标不是城”过滤影响（可能是非城目标/野怪，但守护原有语义保留）
        installCity(1001L, 777L, 100, 100);
        long end = System.currentTimeMillis() / 1000L + 120L;
        ServerGuardianEngine.Decision d =
                ServerGuardianEngine.decide(threat(2L, 300, 300, end), cfg(), System.currentTimeMillis());
        assertTrue("单人进攻仍按原规则判定", d.hit);
    }
}
