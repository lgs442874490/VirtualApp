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
 * 2026-11-12 用户口径：**破坏必须先满足「双方联盟建筑接触」**（发起人联盟任一建筑与目标坐标
 * 切比雪夫距离 ≤ 2 格），否则游戏内根本无法破坏，属误报，不应推送。
 *
 * <p>未接触 / 发起人联盟建筑缓存缺失 / 发起人联盟未知 → 静默（只记诊断日志）。
 * 王城争夺、荒原中立总部（aid=0）不要求接壤；可通过 {@code VAULT_DESTROY_CONTACT=0}
 * 关闭校验、{@code VAULT_DESTROY_TOUCH_RADIUS} 调整接触半径（默认 2）。
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

    /** 发起人城（联盟已知）+ 进攻敌方旗子（在终点精确命中）。 */
    private WatchPointEngine engineWithEnemyFlag() {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        List<WjdrEngine> es = new ArrayList<>();
        es.add(new FakeEngine());
        e.pollTickForTest(es);
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "旗子", OTHER_AID);
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

    // ---------------- 接触：推「破坏」 ----------------

    @Test
    public void contactDistance1_alerts() {
        WatchPointEngine e = engineWithEnemyFlag();
        e.seedBuildingForTest(TX - 1, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3001L);
        assertEquals("双方建筑相邻 → 可以破坏，应推送", 1, e.alertCountForTest());
        assertTrue("应是破坏文案：" + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("破坏"));
    }

    @Test
    public void contactDistance2_alerts() {
        WatchPointEngine e = engineWithEnemyFlag();
        e.seedBuildingForTest(TX - 2, TY - 2, "总部", OWN_AID);   // 切比雪夫距离 2
        feedDestroyMarch(e, 3002L);
        assertEquals("接触半径 2 格内应推送", 1, e.alertCountForTest());
    }

    // ---------------- 不接触：静默 ----------------

    @Test
    public void noContactDistance3_silent() {
        WatchPointEngine e = engineWithEnemyFlag();
        e.seedBuildingForTest(TX - 3, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3003L);
        assertEquals("双方建筑不接触时游戏内无法破坏，不能推送", 0, e.alertCountForTest());
    }

    @Test
    public void noContactDiagonal3_silent() {
        WatchPointEngine e = engineWithEnemyFlag();
        e.seedBuildingForTest(TX - 3, TY - 3, "旗子", OWN_AID);   // 切比雪夫距离 3
        feedDestroyMarch(e, 3004L);
        assertEquals(0, e.alertCountForTest());
    }

    @Test
    public void allyHasNoBuilding_silent() {
        WatchPointEngine e = engineWithEnemyFlag();   // 发起人联盟没有任何建筑缓存
        feedDestroyMarch(e, 3005L);
        assertEquals("无法确认接触 → 不推，只记诊断日志", 0, e.alertCountForTest());
    }

    // ---------------- 不要求接壤的例外 ----------------

    @Test
    public void capitalTarget_noContactRequired() {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        List<WjdrEngine> es = new ArrayList<>();
        es.add(new FakeEngine());
        e.pollTickForTest(es);
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "王城", OTHER_AID);
        feedDestroyMarch(e, 3006L);
        assertEquals("王城争夺不要求双方接壤，仍应推破坏", 1, e.alertCountForTest());
    }

    @Test
    public void neutralHallTarget_noContactRequired() {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        List<WjdrEngine> es = new ArrayList<>();
        es.add(new FakeEngine());
        e.pollTickForTest(es);
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "总部", 0L);   // 荒原中立总部
        feedDestroyMarch(e, 3007L);
        assertEquals("荒原中立总部不要求接壤，仍应推破坏", 1, e.alertCountForTest());
    }

    // ---------------- 开关与半径可调 ----------------

    @Test
    public void checkDisabled_alertsWithoutContact() {
        WatchPointEngine e = engineWithEnemyFlag();
        e.setDestroyContactCheckForTest(false);
        feedDestroyMarch(e, 3008L);
        assertEquals("关闭校验后恢复旧行为", 1, e.alertCountForTest());
    }

    @Test
    public void radiusOverride0_requiresOverlap() {
        WatchPointEngine e = engineWithEnemyFlag();
        e.setDestroyTouchRadiusForTest(0);
        e.seedBuildingForTest(TX - 1, TY, "旗子", OWN_AID);
        feedDestroyMarch(e, 3009L);
        assertEquals("半径 0 时相邻也不算接触", 0, e.alertCountForTest());
    }
}
