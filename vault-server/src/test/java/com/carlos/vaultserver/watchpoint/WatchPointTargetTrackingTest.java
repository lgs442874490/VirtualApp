package com.carlos.vaultserver.watchpoint;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 监视目标坐标跟踪回归（2026-11-02 用户口径，2026-11-04 修订）：
 * <ul>
 *   <li>「只要他飞到哪里就跟着监视到哪里」——坐标只认地图城对象（type=1 且非行军形状）；</li>
 *   <li>丢失视野后先查已知视野，没有就以最后坐标为中心静默搜索（周边优先，再扫全图）；</li>
 *   <li>2026-11-04：丢失/搜索不推送、不推原因，只在找到后推「📍 观察点·坐标更新」+ 实时游戏昵称；</li>
 *   <li>节流窗口内只排队最新坐标，窗口到期补推，绝不吞掉最后一条。</li>
 * </ul>
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
    public void relocationAlertPushedThenLatestQueuedForFlush() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        // 第一次迁城：立即推送（该目标 10 秒内还没推过）
        e.noteTargetCity(UID, 532, 641, "[818]鲶鱼");
        String text = e.lastAlertTextForTest();
        assertTrue("首次迁城要推送: " + text, text.contains("新坐标") && text.contains("(532,641)"));
        assertTrue("昵称要带上", text.contains("鲶鱼"));
        // 10 秒内又换坐标：坐标照样更新（窗口跟着走），推送节流但最新坐标必须排队待发
        e.noteTargetCity(UID, 300, 300, "[818]鲶鱼");
        assertEquals("坐标仍要更新", 300, store.monitorTargets.get(0).x);
        assertTrue("节流窗口内不重复推送: " + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("(532,641)"));
        assertEquals("最新坐标必须排队，不能被节流吞掉", 1, e.pendingMoveCountForTest());
        assertEquals(300, e.pendingMoveXForTest(UID));
    }

    @Test
    public void rapidCoordinateJitterProducesAtMostOneAlert() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.noteTargetCity(UID, 289, 578, "x");
        e.noteTargetCity(UID, 267, 605, "x");
        e.noteTargetCity(UID, 253, 608, "x");
        assertEquals("抖动只报一次", 1, e.alertCountForTest());
        assertEquals("只保留最新一条待补推", 253, e.pendingMoveXForTest(UID));
    }

    @Test
    public void lostTargetFoundInKnownViewNotifiesImmediately() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        // 已知视野（城缓存）里已经有他的新坐标
        e.seedCityForTest(UID, 89700731L, 720, 880, "[818]鲶鱼");
        e.handleTargetLost(store.monitorTargets.get(0));
        WatchPointStore.Target t = store.monitorTargets.get(0);
        assertEquals(720, t.x);
        assertEquals(880, t.y);
        assertTrue(e.lastAlertTextForTest().contains("(720,880)"));
    }

    @Test
    public void lostWithoutKnownCoordinateDoesNotPushLostMessage() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.handleTargetLost(store.monitorTargets.get(0));
        // 2026-11-04 用户口径：不需要推送「他失踪了你在找他」，静默搜索即可
        assertEquals("丢失/搜索过程不推送", 0, e.alertCountForTest());
        assertTrue(e.lastAlertTextForTest().isEmpty());
        assertEquals("最后坐标保持不变，等待搜索", 100, store.monitorTargets.get(0).x);
    }

    @Test
    public void moveAlertHasNoReasonAndUsesLiveNickname() {
        WatchPointStore store = storeWithTarget();
        WatchPointEngine e = new WatchPointEngine(null, store, null, null);
        e.noteTargetCity(UID, 600, 700, "时装小尓");
        String text = e.lastAlertTextForTest();
        assertTrue("标题: " + text, text.contains("📍 观察点·坐标更新"));
        assertTrue("实时昵称: " + text, text.contains("游戏昵称：时装小尓"));
        assertFalse("旧缓存名不得出现: " + text, text.contains("818 80级"));
        assertTrue("老/新坐标: " + text, text.contains("原坐标 (100,100) → 新坐标 (600,700)"));
        assertFalse("不推原因: " + text, text.contains("原因"));
        assertFalse("不推丢失说明: " + text, text.contains("丢失"));
        assertEquals("实时昵称回写配置", "时装小尓", store.monitorTargets.get(0).nick);
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
