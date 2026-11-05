package com.carlos.vaultserver.watchpoint;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 观察点建筑变化告警（2026-11-06 用户口径）：
 * 接入地图对象增量推送 {@code notify_world_mapobj_add/del/update}（session&lt;=0 主动推送），
 * 只在监视矩形（目标 ±27 格）/保护矩形内推「📍 建筑·新建/拆除/易主」。
 *
 * <p>背景：生产日志单日 add 213 / del 218 / update 306 条全被当「未匹配地图回包」丢弃，
 * 用户看不到「296.605 建旗子 / 289.604 拆旗子 / 276.574 总部建成」。
 */
public class WatchPointBuildingAlertTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 监视目标 (285,598) 的 ±27 格矩形 = x[258,312] y[571,625]；用户坐标均在其内。 */
    private WatchPointStore store() {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.monitorTargets.add(new WatchPointStore.Target(31374667L, "收球陨二手号不要", 285, 598));
        s.protectRects.add(new WatchPointStore.Rect("SYD", 722, 454, 776, 508));
        s.aidLabels.put(89700565L, "818");
        s.aidLabels.put(89700731L, "yYz");
        s.wecomEnabled = false;   // 单测不触达企微（vaultStore 为 null）
        return s;
    }

    private WatchPointEngine engine() {
        return new WatchPointEngine(null, store(), null, null);
    }

    private static Map<String, Object> point(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", (long) x);
        p.put("y", (long) y);
        return p;
    }

    private static Map<String, Object> flag(long aid) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("aid", aid);
        return f;
    }

    private static Map<String, Object> addPush(int x, int y, long aid) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("point", point(x, y));
        obj.put("map_alliance_flag", flag(aid));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("obj", obj);
        c.put("kid", 888L);
        c.put("lod", 1L);
        return c;
    }

    private static Map<String, Object> updatePush(int x, int y, long aid) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("map_alliance_flag", flag(aid));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("point", point(x, y));
        c.put("change", change);
        return c;
    }

    private static Map<String, Object> delPush(int x, int y) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("point", point(x, y));
        c.put("type", 8L);
        return c;
    }

    @Test
    public void addInWatchArea_alertsAndCaches() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_add", addPush(296, 605, 89700565L));

        assertEquals("旗子", e.buildingAtForTest(296, 605).kind);
        assertEquals(89700565L, e.buildingAtForTest(296, 605).aid);
        assertEquals(1, e.alertCountForTest());
        String text = e.lastAlertTextForTest();
        assertTrue("标题: " + text, text.contains("📍 建筑·新建"));
        assertTrue("类型: " + text, text.contains("类型：旗子"));
        assertTrue("联盟简称: " + text, text.contains("联盟：818"));
        assertTrue("坐标: " + text, text.contains("坐标：(296,605)"));
    }

    @Test
    public void addOutsideWatchArea_ignored() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_add", addPush(600, 900, 89700565L));

        assertEquals(0, e.alertCountForTest());
        assertNull("区域外不入缓存", e.buildingAtForTest(600, 900));
    }

    @Test
    public void addInProtectRect_alerts() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_add", addPush(750, 480, 89700731L));

        assertEquals(1, e.alertCountForTest());
        assertTrue("保护矩形内也告警: " + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("建筑·新建"));
        assertTrue("联盟: " + e.lastAlertTextForTest(),
                e.lastAlertTextForTest().contains("联盟：yYz"));
    }

    @Test
    public void delAlertsAndEvictsCache() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_add", addPush(289, 604, 89700565L));
        e.feedResponseForTest(-1, "notify_world_mapobj_del", delPush(289, 604));

        assertEquals(2, e.alertCountForTest());
        String text = e.lastAlertTextForTest();
        assertTrue("拆除标题: " + text, text.contains("📍 建筑·拆除"));
        assertTrue(text.contains("坐标：(289,604)"));
        assertNull("拆除后缓存清掉", e.buildingAtForTest(289, 604));
    }

    @Test
    public void delUnknownPoint_ignored() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_del", delPush(276, 574));

        assertEquals("未缓存的坐标（非联盟建筑）不告警", 0, e.alertCountForTest());
    }

    @Test
    public void updateOwnership_alertsYiZhu() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_add", addPush(296, 605, 89700565L));
        e.feedResponseForTest(-1, "notify_world_mapobj_update", updatePush(296, 605, 89700731L));

        assertEquals(2, e.alertCountForTest());
        String text = e.lastAlertTextForTest();
        assertTrue("易主标题: " + text, text.contains("📍 建筑·易主"));
        assertTrue("新联盟: " + text, text.contains("联盟：yYz"));
        assertEquals(89700731L, e.buildingAtForTest(296, 605).aid);
    }

    @Test
    public void updateSameOwner_noAlert() {
        WatchPointEngine e = engine();
        e.feedResponseForTest(-1, "notify_world_mapobj_add", addPush(296, 605, 89700565L));
        e.feedResponseForTest(-1, "notify_world_mapobj_update", updatePush(296, 605, 89700565L));

        assertEquals("同归属更新不重复告警", 1, e.alertCountForTest());
    }

    @Test
    public void nonAllianceObject_ignored() {
        WatchPointEngine e = engine();
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("point", point(296, 605));
        obj.put("type", 2L);   // 资源点
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("obj", obj);
        e.feedResponseForTest(-1, "notify_world_mapobj_add", c);

        assertEquals(0, e.alertCountForTest());
        assertNull(e.buildingAtForTest(296, 605));
    }

    @Test
    public void textFormat_matchesUserTone() {
        String text = WatchPointEngine.buildingAlertText(
                "新建", "总部", "818", 276, 572, "01:55:00");
        assertTrue(text.contains("📍 建筑·新建"));
        assertTrue(text.contains("类型：总部"));
        assertTrue(text.contains("联盟：818"));
        assertTrue(text.contains("坐标：(276,572)"));
        assertTrue(text.contains("时间：01:55:00"));
    }
}
