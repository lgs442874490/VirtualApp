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
 * 2026-11-12 用户实测回归：观察点把「打熊活动（巨熊集结）」推成
 * {@code 📍 资源·破坏 / 发起人：[818]大陆暖男車宝山 / 目标点：(275,608)}。
 *
 * <p>确认口径：
 * <ol>
 *   <li>巨熊集结（type=411 / 帧内带 {@code mass_alliance_trap}）是活动行为，不参与资源分类、不推送；</li>
 *   <li>任何联盟的熊坑陷阱都不推「破坏」（打熊是活动行为，归属无关）；</li>
 *   <li>归属未知（发起人联盟 / 建筑归属未解析）不判破坏；</li>
 *   <li>同人 + 同行为 + 同目标 30 分钟内只推一条（建设/采集/撞矿），破坏告警不被该窗口压制。</li>
 * </ol>
 */
public class WatchPointBearActivityAlertTest {

    private static final long KID = 888L;
    private static final long WATCHED = 7000L;        // 监视目标 = 发起人
    private static final long OWN_AID = 89700731L;    // 发起人所在联盟
    private static final long OTHER_AID = 89700055L;  // 敌对/其他联盟
    /** 发起人城 / 监视目标坐标：远离熊坑陷阱，避免被 cityNear 判成「打自己」。 */
    private static final int MX = 250;
    private static final int MY = 600;
    /** 用户样本里的打熊目标点（熊坑陷阱）。 */
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

    /** 建观察点实例并开 MONITOR 会话（不 seed 发起人城：默认归属未知）。 */
    private WatchPointEngine engine() {
        WatchPointEngine e = new WatchPointEngine(null, store(), null, null);
        List<WjdrEngine> es = new ArrayList<>();
        es.add(new FakeEngine());
        e.pollTickForTest(es);
        return e;
    }

    private static Map<String, Object> xy(int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", (long) x);
        m.put("y", (long) y);
        return m;
    }

    private static Map<String, Object> massTrap() {
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("max", 4L);
        mass.put("uids", new ArrayList<Object>());
        return mass;
    }

    /** 监视目标从自己坐标出发，终点 (TX,TY) 的在途行军；extra 可带活动键。 */
    private static void feedMarch(WatchPointEngine e, long id, long type, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", WATCHED);
        m.put("type", type);
        m.put("state", 2L);
        m.put("startpoint", xy(MX, MY));
        m.put("endpoint", xy(TX, TY));
        m.put("endtime", System.currentTimeMillis() / 1000L + 300L);
        if (extra != null) m.putAll(extra);
        List<Object> datalist = new ArrayList<>();
        datalist.add(m);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("datalist", datalist);
        e.feedResponseForTest(MONITOR_SESSION, "notify_world_marchs", content);
    }

    /** 喂一条带 build_finish 的联盟建筑到地图回包（真实解析路径）。 */
    private static void feedFlag(WatchPointEngine e, long aid, boolean built) {
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("aid", aid);
        flag.put("build_finish", built);
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("point", xy(TX, TY));
        obj.put("map_alliance_flag", flag);
        List<Object> datalist = new ArrayList<>();
        datalist.add(obj);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("datalist", datalist);
        content.put("end_flag", true);
        e.feedResponseForTest(MONITOR_SESSION, "notify_world_mapobjs", content);
    }

    // ---------------- ① 打熊活动不推送 ----------------

    @Test
    public void bearRallyOnOwnTrap_notAlerted() {
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "陷阱", OWN_AID);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("mass_alliance_trap", massTrap());
        feedMarch(e, 1001L, 411L, extra);
        assertEquals("巨熊集结（打自己联盟熊坑）是活动行为，不应推送", 0, e.alertCountForTest());
        assertTrue("不应产生任何文案：" + e.lastAlertTextForTest(), e.lastAlertTextForTest().isEmpty());
    }

    @Test
    public void bearRallyOnOtherTrap_notAlerted() {
        // 用户样本：目标 (275,608) 是他联盟的熊坑陷阱，被判「资源·破坏」。
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]大陆暖男車宝山");
        e.seedBuildingForTest(TX, TY, "陷阱", OTHER_AID);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("mass_alliance_trap", massTrap());
        feedMarch(e, 1002L, 411L, extra);
        assertEquals("打熊活动不是破坏，不应推送", 0, e.alertCountForTest());
    }

    @Test
    public void activityKeyOnNormalType_notAlerted() {
        // 帧内带活动键（mass_alliance_trap）但 type 不是 411：仍按活动行军静默。
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "陷阱", OTHER_AID);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("mass_alliance_trap", massTrap());
        feedMarch(e, 1003L, 2L, extra);
        assertEquals(0, e.alertCountForTest());
    }

    // ---------------- ② 熊坑陷阱不推破坏 ----------------

    @Test
    public void otherTrapDestroyNeverAlerted() {
        // 非活动出征打他盟熊坑陷阱：也不推「破坏」（陷阱=活动建筑）。
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "陷阱", OTHER_AID);
        feedMarch(e, 1004L, 2L, null);
        assertEquals("熊坑陷阱不推破坏", 0, e.alertCountForTest());
    }

    // ---------------- ③ 归属未知不判破坏 ----------------

    @Test
    public void unknownOwnerAidOnFlag_noDestroyAlert() {
        // 发起人城未缓存（ownerAid=0）：不能确定是破坏 → 静默。
        WatchPointEngine e = engine();
        e.seedBuildingForTest(TX, TY, "旗子", OTHER_AID);
        feedMarch(e, 1005L, 2L, null);
        assertEquals("归属未知不能判破坏", 0, e.alertCountForTest());
    }

    @Test
    public void unknownBuildingAidOnFlag_noDestroyAlert() {
        // 建筑归属解析失败（bld.aid=0）：不能确定是破坏 → 静默。
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "旗子", 0L);
        feedMarch(e, 1006L, 2L, null);
        assertEquals("建筑归属未知不能判破坏", 0, e.alertCountForTest());
    }

    /** 归属明确敌对：真实破坏告警必须保留。 */
    @Test
    public void knownEnemyFlag_stillDestroys() {
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "旗子", OTHER_AID);
        feedMarch(e, 1007L, 2L, null);
        assertEquals("明确敌对仍要报破坏", 1, e.alertCountForTest());
        assertTrue("应含破坏文案：" + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("破坏"));
    }

    // ---------------- ④ 30 分钟窗口 ----------------

    @Test
    public void sameOwnerSameBehaviorWithin30Min_alertsOnce() {
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        feedFlag(e, OWN_AID, false);   // 己方未建成旗子 → 建设
        feedMarch(e, 2001L, 6L, null);
        assertEquals(1, e.alertCountForTest());
        assertTrue("第一条应是建设：" + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("建设"));
        // 同人 + 同目标 + 同行为，另一条行军（不同 marchId）：30 分钟内不再推。
        feedMarch(e, 2002L, 6L, null);
        assertEquals("同人同目标同行为 30 分钟内只推一条", 1, e.alertCountForTest());
    }

    @Test
    public void destroyNotSuppressedBy30MinWindow() {
        WatchPointEngine e = engine();
        e.seedCityForTest(WATCHED, OWN_AID, MX, MY, "[818]监视目标");
        e.seedBuildingForTest(TX, TY, "旗子", OTHER_AID);
        feedMarch(e, 2003L, 2L, null);
        feedMarch(e, 2004L, 2L, null);
        assertEquals("破坏告警不能被 30 分钟窗口压掉（真实威胁）", 2, e.alertCountForTest());
    }
}
