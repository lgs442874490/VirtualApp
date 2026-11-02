package com.carlos.vaultserver.watchpoint;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 监视目标坐标跟踪回归（2026-11-02 用户口径）：
 * 「只要他飞到哪里就跟着监视到哪里」——目标一出兵，行军起点就是他的城；
 * 丢失视野后先查已知视野，没有就以最后坐标为中心搜索（周边优先，再扫全图）。
 */
public class WatchPointTargetTrackingTest {

    private static final long UID = 31374667L;

    private WatchPointStore storeWithTarget() {
        WatchPointStore store = new WatchPointStore(Paths.get("/tmp"));
        store.wecomEnabled = false;                       // 单测不推送，只验证文案/坐标
        store.monitorTargets.clear();
        store.monitorTargets.add(new WatchPointStore.Target(UID, "818 80级", 100, 100));
        return store;
    }

    @After
    public void cleanup() {
        WatchPointEngine.install(null);
    }

    @Test
    public void marchStartpointUpdatesTargetCity() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.noteTargetCity(UID, 532, 641, "[818]鲱鱼罐头");
        WatchPointStore.Target t = store.monitorTargets.get(0);
        assertEquals(532, t.x);
        assertEquals(641, t.y);
        assertNotNull("坐标变化必须产生通知", e.lastAlertTextForTest());
        assertTrue(e.lastAlertTextForTest(), e.lastAlertTextForTest().contains("新坐标"));
        assertTrue(e.lastAlertTextForTest(), e.lastAlertTextForTest().contains("(532,641)"));
        assertTrue("昵称要带上，用户只认简称/昵称", e.lastAlertTextForTest().contains("鲱鱼罐头"));
    }

    @Test
    public void smallJitterDoesNotAlert() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.noteTargetCity(UID, 101, 102, "x");   // 差 1-2 格：静默校正
        WatchPointStore.Target t = store.monitorTargets.get(0);
        assertEquals(101, t.x);
        assertEquals(102, t.y);
        assertTrue("口径误差不该刷屏，实际通知：" + e.lastAlertTextForTest(), e.lastAlertTextForTest().isEmpty());
    }

    @Test
    public void lostTargetFoundInKnownViewNotifiesImmediately() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        // 已知视野（城缓存）里已经有他的新坐标
        e.seedCityForTest(UID, 89700731L, 720, 880, "[818]鲱鱼罐头");
        e.handleTargetLost(store.monitorTargets.get(0));
        WatchPointStore.Target t = store.monitorTargets.get(0);
        assertEquals(720, t.x);
        assertEquals(880, t.y);
        assertTrue(e.lastAlertTextForTest().contains("(720,880)"));
    }

    @Test
    public void scanOrderPutsAreaAroundTargetFirst() {
        int[] order = WatchPointEngine.scanOrder(532, 641);
        assertEquals("扫描格数不变", 22 * 22, order.length);
        int firstSx = (order[0] / 22) * 55;
        int firstSy = (order[0] % 22) * 55;
        assertTrue("第一格必须覆盖目标周边", Math.abs(firstSx + 27 - 532) <= 55
                && Math.abs(firstSy + 27 - 641) <= 55);
        // 距离单调不减（周边优先）
        long prev = -1;
        for (int idx : order) {
            int sx = (idx / 22) * 55;
            int sy = (idx % 22) * 55;
            long dx = sx + 27 - 532;
            long dy = sy + 27 - 641;
            long d = dx * dx + dy * dy;
            assertTrue("扫描顺序必须由近到远", d >= prev);
            prev = d;
        }
    }
}
