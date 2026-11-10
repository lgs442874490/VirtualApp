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
 * A1 敌情目标补查回归（2026-11-11）：
 *
 * <p>目标城不在缓存时（扫描间隙/迁城/重启重建中），先对该坐标做 55×55 局部补查：
 * 命中则用真实目标发一条；超时按「未知」发。保证「目标（未知）」只在真的查不到时出现。
 */
public class WatchPointA1ProbeTest {

    private static final long KID = 888L;
    private static final long MONITOR_UID = 7000L;
    private static final long TARGET_UID = 8001L;
    private static final long TARGET_AID = 89700731L;
    private static final int MONITOR_X = 100;
    private static final int MONITOR_Y = 100;
    private static final int TARGET_X = 900;
    private static final int TARGET_Y = 900;
    private static final int MONITOR_SESSION = 100_000_000;
    private static final int PROBE_BASE = 100_600_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeEngine extends WjdrEngine {
        final List<Integer> sessions = new ArrayList<>();

        FakeEngine() {
            super(null);
        }

        @Override public String sessionState() { return STATE_ONLINE; }
        @Override public String lastFpid() { return "f1"; }
        @Override public long lastServerId() { return KID; }
        @Override public long lastUid() { return 9001L; }

        @Override public void sendFireAndForget(String proto, Map<String, Object> args, int session) {
            sessions.add(session);
        }

        int lastProbeSession() {
            for (int i = sessions.size() - 1; i >= 0; i--) {
                int s = sessions.get(i);
                if (s >= PROBE_BASE && s < PROBE_BASE + 100_000) return s;
            }
            return -1;
        }
    }

    private WatchPointStore store() {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.enabled = true;
        s.kid = KID;
        s.fpid = "";
        s.allAccounts = true;
        s.multiRole = true;
        s.wecomEnabled = false;
        s.monitorTargets = new ArrayList<>();
        s.monitorTargets.add(new WatchPointStore.Target(MONITOR_UID, "监视目标", MONITOR_X, MONITOR_Y));
        s.protectRects = new ArrayList<>();
        return s;
    }

    private static Map<String, Object> xy(int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", (long) x);
        m.put("y", (long) y);
        return m;
    }

    /** 喂一条监视目标发起的驻防/增援行军（type=5，起点在监视矩形内、终点在目标坐标）。 */
    private static void feedOutgoingMarch(WatchPointEngine engine) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", 123456L);
        march.put("owner", MONITOR_UID);
        march.put("type", 5L);
        march.put("startpoint", xy(MONITOR_X, MONITOR_Y));
        march.put("endpoint", xy(TARGET_X, TARGET_Y));
        march.put("endtime", System.currentTimeMillis() / 1000L + 600L);
        List<Object> datalist = new ArrayList<>();
        datalist.add(march);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("datalist", datalist);
        engine.feedResponseForTest(MONITOR_SESSION, "notify_world_marchs", content);
    }

    /** 补查回包：目标坐标上出现一座城。 */
    private static void feedProbeHit(WatchPointEngine engine, int probeSession) {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", 1L);
        city.put("point", xy(TARGET_X, TARGET_Y));
        Map<String, Object> mapCity = new LinkedHashMap<>();
        mapCity.put("uid", TARGET_UID);
        mapCity.put("aid", TARGET_AID);
        mapCity.put("lv", 80L);
        mapCity.put("nickName", "[818]测试目标");
        city.put("map_city", mapCity);
        List<Object> datalist = new ArrayList<>();
        datalist.add(city);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("datalist", datalist);
        content.put("end_flag", true);
        engine.feedResponseForTest(probeSession, "notify_world_mapobjs", content);
    }

    /** 建 MONITOR 会话（pollTick 发一次监视查询）。 */
    private static List<WjdrEngine> prepare(WatchPointEngine engine) {
        List<WjdrEngine> es = new ArrayList<>();
        es.add(new FakeEngine());
        engine.pollTickForTest(es);
        return es;
    }

    /** 已知目标：立即发送，不补查。 */
    @Test
    public void knownTargetSendsImmediately() {
        WatchPointEngine engine = new WatchPointEngine(null, store(), null, null);
        engine.seedCityForTest(TARGET_UID, TARGET_AID, TARGET_X, TARGET_Y, "[818]已知目标");
        prepare(engine);
        feedOutgoingMarch(engine);
        assertTrue("应立即发送并带真实目标：" + engine.lastAlertTextForTest(),
                engine.lastAlertTextForTest().contains("已知目标"));
        assertEquals("已知目标不补查", 0, engine.pendingA1CountForTest());
    }

    /** 未知目标：先补查（暂不发送），回包命中后用真实目标发送。 */
    @Test
    public void unknownTargetProbesThenSendsWithTarget() {
        WatchPointEngine engine = new WatchPointEngine(null, store(), null, null);
        List<WjdrEngine> es = prepare(engine);
        feedOutgoingMarch(engine);
        assertEquals("应先挂起等补查", 1, engine.pendingA1CountForTest());
        assertEquals("补查期间不发送", "", engine.lastAlertTextForTest());
        int probeSession = ((FakeEngine) es.get(0)).lastProbeSession();
        assertTrue("应发出 PROBE 查询", probeSession > 0);
        feedProbeHit(engine, probeSession);
        assertTrue("补查命中后应带真实目标：" + engine.lastAlertTextForTest(),
                engine.lastAlertTextForTest().contains("测试目标"));
        assertEquals("补查完成后队列归零", 0, engine.pendingA1CountForTest());
    }

    /** 未知目标 + 补查无回包：超时按「未知」发送，不丢告警。 */
    @Test
    public void unknownTargetProbeTimeoutSendsUnknown() {
        WatchPointEngine engine = new WatchPointEngine(null, store(), null, null);
        prepare(engine);
        feedOutgoingMarch(engine);
        assertEquals(1, engine.pendingA1CountForTest());
        engine.flushProbeTimeoutsForTest(System.currentTimeMillis() + 2_000L);
        assertTrue("超时仍应发送（目标未知）：" + engine.lastAlertTextForTest(),
                engine.lastAlertTextForTest().contains("（未知）"));
        assertEquals("超时后队列归零", 0, engine.pendingA1CountForTest());
    }
}
