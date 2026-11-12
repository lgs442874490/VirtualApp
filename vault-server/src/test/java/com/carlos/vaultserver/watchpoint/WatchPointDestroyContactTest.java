package com.carlos.vaultserver.watchpoint;

import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-12 用户口径：**破坏必须先满足「双方联盟建筑接触」**，否则游戏内根本无法破坏，属误报。
 *
 * <p>领地范围（用户确认）：联盟旗子 7×7（半径 3）、联盟总部 9×9（半径 4；荒原/雪原暂统一）。
 * 接触 = 两方领地边缘相邻：切比雪夫距离 ≤ r(发起人建筑) + r(目标建筑) + 1
 * （旗子 vs 旗子 ≤ 7；旗子 vs 总部 ≤ 8）。
 *
 * <p>未接触 / 发起人联盟建筑缓存缺失 / 发起人联盟未知 → 静默（只记诊断日志）。
 * 王城争夺、荒原中立总部（aid=0）不要求接壤；可通过 {@code VAULT_DESTROY_CONTACT=0}
 * 关闭校验、{@code -DVAULT_DESTROY_TOUCH_RADIUS_FLAG/_HALL} 调整领地半径。
 */
public class WatchPointDestroyContactTest {

    private static final long KID = 888L;
    private static final long WATCHED = 7000L;        // 监视目标 = 发起人
    private static final long OWN_AID = 89700731L;    // 发起人所在联盟
    private static final long OTHER_AID = 89700055L;  // 目标建筑所属联盟
    private static final int MX = 250;
    private static final int MY = 600;
    private static final int TX = 275;
    private static final int TY = 608;
    private static final int MONITOR_SESSION = 100_000_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeEngine extends WjdrEngine {
        FakeEngine() {
            super(null);
        }

        @Override public String sessionState() { return STATE_ONLINE; }
        @Override public String lastFpid() { return "f1"; }
        @Override public long lastServerId() { return KID; }
        @Override public long lastUid() { return WATCHED; }

        @Override public void sendFireAndForget(String proto, Map<String, Object> args, int session) {
        }
    }

    private WatchPointStore store() {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.enabled = true;
        s.kid = KID;
        s.fpid = "f1";
        s.wecomEnabled = false;
        s.monitorTargets = new ArrayList<>();
        s.monitorTargets.add(new WatchPointStore.Target(WATCHED, "818 监视目标", MX, MY));
        s.protectRects = new ArrayList<>();
        s.aidLabels.put(OWN_AID, "818");
        s.aidLabels.put(OTHER_AID, "999");
        return s;
    }

    /** 发起人城（联盟已知）+ 目标建筑（在终点精确命中）。 */
    private WatchPointEngine engineWithTarget(String kind, long targetAid) {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        List<WjdrEngine> es = new ArrayList<>();
        es.add(new FakeEngine());
        e.pollTickForTest(es);
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, kind, targetAid);
        return e;
    }

    private static Map<String, Object> xy(int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", (long) x);
        m.put("y", (long) y);
        return m;
    }

    private static void feedDestroyMarch(WatchPointEngine e, long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", WATCHED);
        m.put("type", 2L);              // 出征
        m.put("state", 2L);             // 在途
        m.put("startpoint", xy(MX, MY));
        m.put("endpoint", xy(TX, TY));
        m.put("endtime", System.currentTimeMillis() / 1000L + 300L);
        List<Object> datalist = new ArrayList<>();
        datalist.add(m);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("datalist", datalist);
        e.feedResponseForTest(MONITOR_SESSION, "notify_world_marchs", content);
    }

    // ---------------- 接触：推「破坏」（旗子 vs 旗子 ≤ 7 格） ----------------

    @Test
    public void contactDistance1_alerts() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.seedBuildingForTest(TX - 1, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3001L);
        assertEquals("双方建筑相邻 → 可以破坏，应推送", 1, e.alertCountForTest());
        assertTrue("应是破坏文案：" + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("破坏"));
    }

    @Test
    public void contactBoundary7_alerts() {
        // 7×7 领地边缘相邻：切比雪夫距离 7 仍算接触。
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.seedBuildingForTest(TX - 7, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3002L);
        assertEquals("7×7 领地边缘相邻（距离 7）应接触", 1, e.alertCountForTest());
    }

    // ---------------- 不接触：静默（旗子 vs 旗子 ≥ 8 格） ----------------

    @Test
    public void noContactDistance8_silent() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.seedBuildingForTest(TX - 8, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3003L);
        assertEquals("双方领地不相邻时游戏内无法破坏，不能推送", 0, e.alertCountForTest());
    }

    @Test
    public void noContactDiagonal8_silent() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.seedBuildingForTest(TX - 8, TY - 8, "旗子", OWN_AID);
        feedDestroyMarch(e, 3004L);
        assertEquals(0, e.alertCountForTest());
    }

    // ---------------- 总部领地更大（9×9 → 与旗子距离 8 仍接触） ----------------

    @Test
    public void hallTargetReach8_alerts() {
        WatchPointEngine e = engineWithTarget("总部", OTHER_AID);
        e.seedBuildingForTest(TX - 8, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3005L);
        assertEquals("总部 9×9 + 旗子 7×7 边缘相邻（距离 8）应接触", 1, e.alertCountForTest());
    }

    @Test
    public void hallTargetReach9_silent() {
        WatchPointEngine e = engineWithTarget("总部", OTHER_AID);
        e.seedBuildingForTest(TX - 9, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3006L);
        assertEquals("距离 9 超出总部+旗子领地边缘，应静默", 0, e.alertCountForTest());
    }

    @Test
    public void flagTargetReach8_silent() {
        // 与 hallTargetReach8 对照：目标换成旗子（7×7），距离 8 就不接触。
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.seedBuildingForTest(TX - 8, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3007L);
        assertEquals("旗子 vs 旗子距离 8 已超出接触范围", 0, e.alertCountForTest());
    }

    @Test
    public void allyHallCountsForContact() {
        // 发起人方是总部（9×9）时同样按更大半径参与判定：距离 8 接触。
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.seedBuildingForTest(TX - 8, TY, "总部", OWN_AID);
        feedDestroyMarch(e, 3008L);
        assertEquals("发起人总部 9×9 与目标旗子边缘相邻（距离 8）应接触", 1, e.alertCountForTest());
    }

    @Test
    public void allyHasNoBuilding_silent() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);   // 发起人联盟没有任何建筑缓存
        feedDestroyMarch(e, 3009L);
        assertEquals("无法确认接触 → 不推，只记诊断日志", 0, e.alertCountForTest());
    }

    // ---------------- 不要求接壤的例外 ----------------

    @Test
    public void capitalTarget_noContactRequired() {
        WatchPointEngine e = engineWithTarget("王城", OTHER_AID);
        feedDestroyMarch(e, 3010L);
        assertEquals("王城争夺不要求双方接壤，仍应推破坏", 1, e.alertCountForTest());
    }

    @Test
    public void neutralHallTarget_noContactRequired() {
        WatchPointEngine e = engineWithTarget("总部", 0L);   // 荒原中立总部
        feedDestroyMarch(e, 3011L);
        assertEquals("荒原中立总部不要求接壤，仍应推破坏", 1, e.alertCountForTest());
    }

    // ---------------- 开关与半径可调 ----------------

    @Test
    public void checkDisabled_alertsWithoutContact() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.setDestroyContactCheckForTest(false);
        feedDestroyMarch(e, 3012L);
        assertEquals("关闭校验后恢复旧行为", 1, e.alertCountForTest());
    }

    @Test
    public void radiusZeroGapSilent() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.setDestroyTouchRadiusForTest(0);
        e.seedBuildingForTest(TX - 2, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3013L);
        assertEquals("半径 0 时只有相邻/重叠才算接触，距离 2 应静默", 0, e.alertCountForTest());
    }

    @Test
    public void radiusZeroAdjacentAlerts() {
        WatchPointEngine e = engineWithTarget("旗子", OTHER_AID);
        e.setDestroyTouchRadiusForTest(0);
        e.seedBuildingForTest(TX - 1, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3014L);
        assertEquals("半径 0 时相邻（距离 1）仍算接触", 1, e.alertCountForTest());
    }
}
