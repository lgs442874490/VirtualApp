package com.carlos.vaultserver.watchpoint;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 观察点配置单测（2026-10-27 MVP）：
 * 首次落盘默认（K888 实扫的 5 个监视目标 + 8 个保护矩形）、往返读写、越界收敛、别名/友盟集合。
 */
public class WatchPointStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- 批次 B（API 写接口）用的共享夹具；原 2026-10-27 用例各自构造 store，互不影响 ----
    private WatchPointStore store;
    private Path file;

    @Before
    public void setUpStore() {
        store = new WatchPointStore(tmp.getRoot().toPath());
        store.ensureLoaded();          // 首启写默认（K888 实扫种子）
        file = store.file();
    }

    private String fileText() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static JSONObject target(long uid, String nick, int x, int y) {
        return new JSONObject().put("uid", uid).put("nick", nick).put("x", x).put("y", y);
    }

    private static JSONObject rect(String name, int x1, int y1, int x2, int y2) {
        return new JSONObject().put("name", name).put("x1", x1).put("y1", y1)
                .put("x2", x2).put("y2", y2);
    }

    private static JSONObject validConfig() {
        return new JSONObject()
                .put("kid", 913L)
                .put("wecomEnabled", false)
                .put("monitorTargets", new JSONArray()
                        .put(target(123L, "敌A", 10, 20))
                        .put(target(456L, "敌B", 1200, 0)))
                .put("protectRects", new JSONArray()
                        .put(rect("A", 100, 100, 155, 155)))
                .put("alertTypes", new JSONArray().put(1L).put(2L))
                .put("friendAids", "555,666")
                .put("aidLabels", new JSONObject().put("555", "SYD"));
    }

    @Test
    public void load_seedsDefaultsAndPersists() throws Exception {
        WatchPointStore store = new WatchPointStore(tmp.getRoot().toPath());
        store.load();
        assertTrue(Files.isRegularFile(store.file()));
        assertEquals(888L, store.kid);
        assertEquals(5, store.monitorTargets.size());
        assertEquals(8, store.protectRects.size());
        assertTrue("进攻城类型必须在告警类型内", store.alertTypes.contains(2L));
        assertTrue("集结类型必须在告警类型内", store.alertTypes.contains(99L));
        assertFalse("驻防/增援类型不告警", store.alertTypes.contains(5L));
        assertFalse("采集类型不告警", store.alertTypes.contains(4L));
        assertTrue("默认只告警打城（endpoint 命中城坐标）", store.cityTargetOnly);
        assertTrue(store.friendAids.contains(89700055L));
        assertEquals("SYD", store.aidLabels.get(89700055L));
        assertEquals("Hey", store.aidLabels.get(89700704L));
        assertTrue(store.enabled);
        // 监视目标默认是 Hey 的 80 级炉子（含坐标）
        WatchPointStore.Target t = store.monitorTargets.get(0);
        assertTrue(t.uid > 0);
        assertTrue(t.x > 0 && t.y > 0);
        // 落盘 JSON 可读
        JSONObject o = new JSONObject(new String(Files.readAllBytes(store.file()), StandardCharsets.UTF_8));
        assertEquals(5, o.getJSONArray("monitorTargets").length());
        assertEquals(8, o.getJSONArray("protectRects").length());
    }

    @Test
    public void load_roundTripAndClamp() throws Exception {
        WatchPointStore store = new WatchPointStore(tmp.getRoot().toPath());
        store.load();
        store.enabled = false;
        store.monitorIntervalMs = 100L;      // 会被夹到 ≥1000
        store.protectIntervalMs = 999_999_999L; // 会被夹到 ≤3600000
        store.maxReqPerSec = 99.0d;          // 会被夹到 ≤30
        store.monitorTargets.get(0).x = 601;
        store.monitorTargets.get(0).y = 602;
        store.protectRects.get(0).name = "TEST";
        store.save();

        WatchPointStore re = new WatchPointStore(tmp.getRoot().toPath());
        re.load();
        assertFalse(re.enabled);
        assertEquals(1000L, re.monitorIntervalMs);
        assertEquals(3_600_000L, re.protectIntervalMs);
        assertEquals(30.0d, re.maxReqPerSec, 0.0001d);
        assertEquals(601, re.monitorTargets.get(0).x);
        assertEquals(602, re.monitorTargets.get(0).y);
        assertEquals("TEST", re.protectRects.get(0).name);
    }

    @Test
    public void rect_contains() {
        WatchPointStore.Rect r = new WatchPointStore.Rect("S", 462, 550, 516, 604);
        assertTrue(r.contains(462, 550));
        assertTrue(r.contains(516, 604));
        assertFalse(r.contains(461, 550));
        assertFalse(r.contains(517, 604));
        assertNotNull(r.display());
    }

    @Test
    public void envEnabled_defaultsOff() {
        // 环境变量/系统属性缺省时关闭（安全默认）；显式 on/1/true 才开
        String prev = System.getProperty(WatchPointStore.ENV_ENABLED);
        try {
            System.clearProperty(WatchPointStore.ENV_ENABLED);
            if (System.getenv(WatchPointStore.ENV_ENABLED) == null) {
                assertFalse(WatchPointStore.envEnabled());
            }
            System.setProperty(WatchPointStore.ENV_ENABLED, "on");
            assertTrue(WatchPointStore.envEnabled());
            System.setProperty(WatchPointStore.ENV_ENABLED, "0");
            assertFalse(WatchPointStore.envEnabled());
        } finally {
            if (prev == null) System.clearProperty(WatchPointStore.ENV_ENABLED);
            else System.setProperty(WatchPointStore.ENV_ENABLED, prev);
        }
    }

    // ==================== 写/读 round-trip ====================

    @Test
    public void applyConfig_roundTrip_andBackup() throws Exception {
        assertTrue("首启应生成配置文件", Files.isRegularFile(file));
        assertNull(store.applyConfig(validConfig()));

        // 内存即时生效
        assertEquals(913L, store.kid);
        assertFalse(store.wecomEnabled);
        assertEquals(2, store.monitorTargets.size());
        assertEquals(1200, store.monitorTargets.get(1).x);
        assertEquals(1, store.protectRects.size());
        assertEquals(Set.of(1L, 2L), store.alertTypes);
        assertEquals(Set.of(555L, 666L), store.friendAids);
        assertEquals("SYD", store.aidLabels.get(555L));

        // 落盘 + 备份 + 重新载入一致
        assertTrue("写盘前应有 .bak", Files.isRegularFile(
                file.resolveSibling("watchpoint.json.bak")));
        String json = fileText();
        assertTrue(json.contains("\"kid\": 913") || json.contains("\"kid\":913"));

        WatchPointStore reload = new WatchPointStore(tmp.getRoot().toPath());
        reload.ensureLoaded();
        assertEquals(913L, reload.kid);
        assertFalse(reload.wecomEnabled);
        assertEquals(2, reload.monitorTargets.size());
        assertEquals(123L, reload.monitorTargets.get(0).uid);
        assertEquals("敌A", reload.monitorTargets.get(0).nick);
        assertEquals(155, reload.protectRects.get(0).x2);
        assertEquals(Set.of(555L, 666L), reload.friendAids);
    }

    @Test
    public void setEnabled_writesFileAndReloads() {
        store.setEnabled(false);
        assertFalse(store.enabled);
        WatchPointStore reload = new WatchPointStore(tmp.getRoot().toPath());
        reload.ensureLoaded();
        assertFalse("enabled=false 应持久化", reload.enabled);
        store.setEnabled(true);
        assertTrue(store.enabled);
    }

    // ==================== 非法输入 fail-closed ====================

    @Test
    public void invalidInputs_rejected_andFileUntouched() throws Exception {
        String before = fileText();

        // uid<=0
        JSONObject badUid = new JSONObject().put("monitorTargets",
                new JSONArray().put(target(0L, "坏", 1, 1)));
        assertNotNull("uid<=0 应拒绝", store.applyConfig(badUid));
        assertEquals("拒绝时不得落盘", before, fileText());

        // 坐标越界
        JSONObject badCoord = new JSONObject().put("monitorTargets",
                new JSONArray().put(target(9L, "坏", 1201, 1)));
        assertNotNull(store.applyConfig(badCoord));
        assertEquals(before, fileText());

        // 矩形跨度 > 55
        JSONObject badSpan = new JSONObject().put("protectRects",
                new JSONArray().put(rect("坏", 100, 100, 156, 155)));
        assertNotNull(store.applyConfig(badSpan));
        assertEquals(before, fileText());

        // 矩形反向
        JSONObject reversed = new JSONObject().put("protectRects",
                new JSONArray().put(rect("坏", 100, 100, 100, 155)));
        assertNotNull(store.applyConfig(reversed));

        // 空目标 / 空矩形（引擎启动要求至少 1 个，拒绝以免误清空）
        assertNotNull(store.applyConfig(new JSONObject().put("monitorTargets", new JSONArray())));
        assertNotNull(store.applyConfig(new JSONObject().put("protectRects", new JSONArray())));

        // 重复 uid
        JSONObject dup = new JSONObject().put("monitorTargets", new JSONArray()
                .put(target(7L, "A", 1, 1)).put(target(7L, "B", 2, 2)));
        assertNotNull(store.applyConfig(dup));

        // 告警类型非法 / 友盟 aid 非法
        assertNotNull(store.applyConfig(new JSONObject().put("alertTypes", "0")));
        assertNotNull(store.applyConfig(new JSONObject().put("friendAids", "-1")));
        assertEquals("所有非法输入都不得改动旧文件", before, fileText());
    }

    // ==================== 校验纯函数 ====================

    @Test
    public void validators_bounds() {
        assertNull(WatchPointStore.validateCoord(0));
        assertNull(WatchPointStore.validateCoord(1200));
        assertNotNull(WatchPointStore.validateCoord(-1));
        assertNotNull(WatchPointStore.validateCoord(1201));

        assertNull(WatchPointStore.validateTarget(1L, "x", 0, 1200));
        assertNotNull(WatchPointStore.validateTarget(0L, "x", 1, 1));
        assertNotNull(WatchPointStore.validateTarget(1L, "x".repeat(65), 1, 1));

        assertNull(WatchPointStore.validateRect("r", 10, 10, 65, 65));      // 55 跨度
        assertNotNull(WatchPointStore.validateRect("r", 10, 10, 66, 10));   // 56
        assertNotNull(WatchPointStore.validateRect("r", 10, 10, 65, 0));    // y2<=y1

        assertNull(WatchPointStore.validateAlertType(102L));
        assertNotNull(WatchPointStore.validateAlertType(0L));
        assertNotNull(WatchPointStore.validateFriendAid(-1L));
    }

    @Test
    public void parseIds_acceptsCsvAndJsonArray() {
        assertEquals(Set.of(1L, 2L, 99L), WatchPointStore.parseIds("1, 2,99", "告警类型"));
        assertEquals(Set.of(1L, 2L), WatchPointStore.parseIds(new JSONArray().put(1).put(2), "t"));
        assertEquals(Set.of(), WatchPointStore.parseIds("", "t"));
        try {
            WatchPointStore.parseIds("1,x", "告警类型");
            assertTrue("非法数字应抛异常", false);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void snapshotJson_containsApiFieldsAndCrops() {
        JSONObject snap = store.snapshotJson();
        assertTrue(snap.has("enabled"));
        assertTrue(snap.has("envEnabled"));
        assertTrue(snap.has("updatedAt"));
        assertTrue(snap.getLong("updatedAt") > 0L);
        assertTrue(snap.getJSONArray("monitorTargets").length() > 0);
        assertTrue(snap.getJSONArray("protectRects").length() > 0);
        assertTrue(snap.has("alertTypes"));
        assertTrue(snap.has("friendAids"));
        assertTrue(snap.has("aidLabels"));
    }
}
