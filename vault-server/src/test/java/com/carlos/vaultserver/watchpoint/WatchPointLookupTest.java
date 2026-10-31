package com.carlos.vaultserver.watchpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 批次 O（2026-10-31）：观察点缓存对外提供「坐标→城 uid/联盟」「保护矩形」查询，
 * 供守护盟友判定敌情目标是否友方（用户建议：参考观察点/监视目标信息）。
 */
public class WatchPointLookupTest {

    @After
    public void cleanup() {
        WatchPointEngine.install(null);
    }

    @Test
    public void cityAt_returnsUidAidForScannedCoordinate() {
        WatchPointStore store = new WatchPointStore(java.nio.file.Paths.get("/tmp"));
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.seedCityForTest(7001L, 8880001L, 537, 436, "盟友A");
        WatchPointEngine.install(e);

        WatchPointEngine.CityInfo ci = WatchPointEngine.cityAt(537, 436);
        assertNotNull("命中扫描缓存", ci);
        assertEquals(7001L, ci.uid);
        assertEquals(8880001L, ci.aid);
        assertEquals("盟友A", ci.nick);
        assertNull("无城坐标返回 null", WatchPointEngine.cityAt(1, 1));
        assertNull("未安装返回 null", WatchPointEngine.cityAt(0, 0));
    }

    @Test
    public void inProtectRect_usesConfiguredRects() {
        WatchPointStore store = new WatchPointStore(java.nio.file.Paths.get("/tmp"));
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.seedProtectRectForTest("yYz", 528, 431, 582, 485);
        WatchPointEngine.install(e);

        assertTrue("矩形内", WatchPointEngine.inProtectRect(537, 436));
        assertTrue("边界含端点", WatchPointEngine.inProtectRect(528, 431));
        assertFalse("矩形外", WatchPointEngine.inProtectRect(537, 430));
        assertEquals(1, WatchPointEngine.protectRects().size());
    }

    @Test
    public void notInstalled_isSafe() {
        WatchPointEngine.install(null);
        assertNull(WatchPointEngine.cityAt(100, 100));
        assertFalse(WatchPointEngine.inProtectRect(100, 100));
        assertTrue(WatchPointEngine.protectRects().isEmpty());
    }
}
