package com.carlos.vaultserver.watchpoint;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 采集点被攻击端到端（2026-11-02）：走真实收包路径 onMessage →
 * 采集行军(type=4) 记账「谁在哪个点采」→ 进攻行军(type=2) 打同一个点 → 推送采集点被攻击。
 */
public class GatherAttackEndToEndTest {

    private static final String FPID = "48460403";
    private static final long KID = 888L;
    private static final long WATCHED = 31374667L;   // 监视目标（818）
    private static final long VICTIM = 19694427L;    // 被攻击的采集者
    private static final long FRIEND_AID = 89700352L;// yYz（默认友盟）

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeEngine extends com.carlos.vault.wjdr.WjdrEngine {
        final List<Integer> sessions = new ArrayList<>();

        FakeEngine() {
            super(null);
        }

        @Override public String sessionState() { return STATE_ONLINE; }
        @Override public String lastFpid() { return FPID; }
        @Override public long lastServerId() { return KID; }
        @Override public long lastUid() { return WATCHED; }
        @Override public void sendFireAndForget(String proto, Map<String, Object> args, int session) {
            sessions.add(session);
        }
    }

    private WatchPointStore store() {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.enabled = true;
        s.kid = KID;
        s.fpid = FPID;
        s.wecomEnabled = false;
        s.monitorTargets = new ArrayList<>();
        s.protectRects = new ArrayList<>();
        s.monitorTargets.add(new WatchPointStore.Target(WATCHED, "818 80级", 278, 607));
        return s;
    }

    private static Map<String, Object> march(long id, long owner, long type, long state,
                                             int sx, int sy, int ex, int ey, long aid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", owner);
        m.put("type", type);
        m.put("state", state);
        m.put("startpoint", point(sx, sy));
        m.put("endpoint", point(ex, ey));
        m.put("endtime", System.currentTimeMillis() / 1000L + 180L);
        m.put("aid", aid);
        return m;
    }

    private static Map<String, Object> point(int x, int y) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", (long) x);
        p.put("y", (long) y);
        return p;
    }

    @Test
    public void attackOnGatherPointAlertsWithAttackerVictimAndPoint() {
        WatchPointStore store = store();
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        // 触发一次监视轮询，拿到 session/ctx
        engine.pollTickForTest(List.of(fe));
        assertFalse("必须已经发出监视请求", fe.sessions.isEmpty());
        int session = fe.sessions.get(0);

        // ① 友盟的人在 (512,688) 采集（type=4，在途）
        Map<String, Object> gather = new LinkedHashMap<>();
        gather.put("march", march(9001L, VICTIM, 4L, 2L, 600, 700, 512, 688, FRIEND_AID));
        engine.feedResponseForTest(session, "notify_world_marchs", gather);

        // ② 监视目标去打这个采集点
        Map<String, Object> attack = new LinkedHashMap<>();
        attack.put("march", march(9002L, WATCHED, 2L, 2L, 278, 607, 512, 688, 0L));
        engine.feedResponseForTest(session, "notify_world_marchs", attack);

        assertEquals("应当推送一条采集点被攻击", 1, engine.alertCountForTest());
        String text = engine.lastAlertTextForTest();
        assertTrue(text, text.contains("采集点被攻击"));
        assertTrue("攻击人坐标: " + text, text.contains("(278,607)"));
        assertTrue("被攻击人: " + text, text.contains("uid" + VICTIM));
        assertTrue("资源点坐标: " + text, text.contains("(512,688)"));
        assertTrue("出征信息: " + text, text.contains("march 9002"));
    }

    @Test
    public void unrelatedGatherAttackIsSilent() {
        WatchPointStore store = store();
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        engine.pollTickForTest(List.of(fe));
        int session = fe.sessions.get(0);

        Map<String, Object> gather = new LinkedHashMap<>();
        // 采集者属于一个既不是友盟、也不是监视目标的联盟
        gather.put("march", march(9101L, VICTIM, 4L, 2L, 600, 700, 512, 688, 12345678L));
        engine.feedResponseForTest(session, "notify_world_marchs", gather);

        // 非监视目标、也不是友盟被打 → 不推（防全图采集点互殴刷屏）
        Map<String, Object> attack = new LinkedHashMap<>();
        attack.put("march", march(9102L, 70000001L, 2L, 2L, 100, 100, 512, 688, 0L));
        engine.feedResponseForTest(session, "notify_world_marchs", attack);

        assertEquals("无关采集点攻击不该推送", 0, engine.alertCountForTest());
    }
}
