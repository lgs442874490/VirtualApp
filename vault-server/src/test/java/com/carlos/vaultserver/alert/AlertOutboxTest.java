package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.WatchEventStore;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P3 出口：事件先入库、alertKey 幂等、路由前缀推断。 */
public class AlertOutboxTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private WatchEventStore events() {
        WatchEventStore s = new WatchEventStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        s.load();
        return s;
    }

    @Test
    public void emitWritesEventWithRouteAndText() {
        WatchEventStore store = events();
        AlertOutbox outbox = new AlertOutbox(store);
        assertTrue(outbox.emit("A2", "scan:in:1:2", "🚨 敌袭·进攻\n级别：🔴 威胁进攻", 1L, "SCAN"));
        JSONObject e = store.after(0L).get(0);
        assertEquals("A2", e.optString("route"));
        assertEquals("scan:in:1:2", e.optString("alertKey"));
        assertTrue(e.optString("text").startsWith("🚨 敌袭·进攻"));
        assertEquals("SCAN", e.optString("source"));
        assertEquals(1, e.optInt("count", 0));
    }

    @Test
    public void sameAlertKeyIsIdempotent() {
        WatchEventStore store = events();
        AlertOutbox outbox = new AlertOutbox(store);
        assertTrue(outbox.emit("A1", "scan:out:1:2", "🚨 敌情·侦查", 1L, "SCAN"));
        assertFalse("同一条告警不能重复入库", outbox.emit("A1", "scan:out:1:2", "🚨 敌情·侦查", 2L, "SCAN"));
        assertEquals(1, store.size());
    }

    @Test
    public void withCountAppendsWaveCountAndRefreshesTime() {
        String base = "🚨 敌袭·集结出征\n级别：🔴 威胁进攻\n攻击者：a\n兵力：100\n"
                + "目标：b(1,2)\nETA：1分0秒\n时间：10:00:00";
        String out = AlertText.withCount(base, 3, "10:00:30");
        assertTrue(out.startsWith("🚨 敌袭·集结出征（共 3 波）"));
        assertTrue(out.contains("时间：10:00:30"));
        assertTrue(out.contains("攻击者：a"));
    }

    @Test
    public void routeOfTextMapsPrefixes() {
        assertEquals("A2", AlertOutbox.routeOfText("🚨 敌袭·进攻"));
        assertEquals("A1", AlertOutbox.routeOfText("🚨 敌情·侦查"));
        assertEquals("A3", AlertOutbox.routeOfText("📍 资源·采集"));
        assertEquals("A4", AlertOutbox.routeOfText("📍 观察点·坐标更新"));
        assertEquals("A4", AlertOutbox.routeOfText(null));
    }

    @Test
    public void capKeepsLatestFiveHundred() {
        WatchEventStore store = events();
        AlertOutbox outbox = new AlertOutbox(store);
        for (int i = 0; i < 505; i++) {
            outbox.emit("A1", "k" + i, "t" + i, i, "SCAN");
        }
        assertEquals(500, store.size());
    }
}
