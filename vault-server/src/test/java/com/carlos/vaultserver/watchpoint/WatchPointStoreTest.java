package com.carlos.vaultserver.watchpoint;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 观察点配置单测（2026-10-27 MVP）：
 * 首次落盘默认（K888 实扫的 5 个监视目标 + 8 个保护矩形）、往返读写、越界收敛、别名/友盟集合。
 */
public class WatchPointStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

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
}
