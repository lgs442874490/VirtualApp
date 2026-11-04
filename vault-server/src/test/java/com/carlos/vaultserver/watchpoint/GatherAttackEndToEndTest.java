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
        assertTrue(text, text.contains("🚨 资源·受袭"));
        assertTrue("攻击人坐标: " + text, text.contains("(278,607)"));
        assertTrue("资源点坐标: " + text, text.contains("(512,688)"));
        assertTrue("出征信息: " + text, text.contains("出征信息"));
        assertFalse("通知里不能带 uid/aid/march 编号: " + text,
                text.contains("uid") || text.contains("aid") || text.contains("march"));
    }

    /** 2026-11-04 用户口径：盟友之间无法互相攻击 → 同联盟「采集点被攻击」必为误报。 */
    @Test
    public void sameAllianceGatherAttackIsSilent() {
        WatchPointStore store = store();
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        engine.pollTickForTest(List.of(fe));
        int session = fe.sessions.get(0);
        // 攻击者城缓存 aid 与采集者相同（同为友盟 SYD）
        engine.seedCityForTest(WATCHED, 89700055L, 278, 607, "[SYD]攻击者");

        Map<String, Object> gather = new LinkedHashMap<>();
        gather.put("march", march(9201L, VICTIM, 4L, 2L, 600, 700, 512, 688, 89700055L));
        engine.feedResponseForTest(session, "notify_world_marchs", gather);

        Map<String, Object> attack = new LinkedHashMap<>();
        attack.put("march", march(9202L, WATCHED, 2L, 2L, 278, 607, 512, 688, 0L));
        engine.feedResponseForTest(session, "notify_world_marchs", attack);

        assertEquals("同联盟不可能互相攻击，不能推送", 0, engine.alertCountForTest());
    }

    /** 2026-11-04：同阵营友盟（不同联盟）之间也不会互相攻击 → 不推。 */
    @Test
    public void friendlyCoalitionGatherAttackIsSilent() {
        WatchPointStore store = store();
        store.friendAids.add(89700055L);   // SYD
        store.friendAids.add(89700483L);   // KFC
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        engine.pollTickForTest(List.of(fe));
        int session = fe.sessions.get(0);
        // 攻击者 SYD、采集者 KFC：都在友盟集合里
        engine.seedCityForTest(WATCHED, 89700055L, 278, 607, "[SYD]攻击者");

        Map<String, Object> gather = new LinkedHashMap<>();
        gather.put("march", march(9251L, VICTIM, 4L, 2L, 600, 700, 512, 688, 89700483L));
        engine.feedResponseForTest(session, "notify_world_marchs", gather);

        Map<String, Object> attack = new LinkedHashMap<>();
        attack.put("march", march(9252L, WATCHED, 2L, 2L, 278, 607, 512, 688, 0L));
        engine.feedResponseForTest(session, "notify_world_marchs", attack);

        assertEquals("同阵营友盟之间不能互相攻击，不能推送", 0, engine.alertCountForTest());
    }

    /** 2026-11-04：巨兽/野外（PvE）出征终点落在矿点上，不算打采集点。 */
    @Test
    public void pveMarchOnGatherPointIsSilent() {
        WatchPointStore store = store();
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        engine.pollTickForTest(List.of(fe));
        int session = fe.sessions.get(0);

        Map<String, Object> gather = new LinkedHashMap<>();
        gather.put("march", march(9301L, VICTIM, 4L, 2L, 600, 700, 512, 688, FRIEND_AID));
        engine.feedResponseForTest(session, "notify_world_marchs", gather);

        Map<String, Object> attack = march(9302L, WATCHED, 2L, 2L, 278, 607, 512, 688, 0L);
        attack.put("atk_monster", new LinkedHashMap<>());   // 出征打巨兽/野怪
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("march", attack);
        engine.feedResponseForTest(session, "notify_world_marchs", wrapper);

        assertEquals("PvE 行军不能报采集点被攻击", 0, engine.alertCountForTest());
    }

    /** 2026-11-04：采集点缓存过期（人早走了）后，经过该点的行军不再误报。 */
    @Test
    public void staleGatherPointIsSilent() {
        WatchPointStore store = store();
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        engine.pollTickForTest(List.of(fe));
        int session = fe.sessions.get(0);
        // 21 分钟前的采集记录（超过 20 分钟 TTL）
        engine.recordResourceAt(512, 688, VICTIM, FRIEND_AID, 9400L,
                System.currentTimeMillis() - 21L * 60_000L);

        Map<String, Object> attack = new LinkedHashMap<>();
        attack.put("march", march(9402L, WATCHED, 2L, 2L, 278, 607, 512, 688, 0L));
        engine.feedResponseForTest(session, "notify_world_marchs", attack);

        assertEquals("过期采集点不能推送", 0, engine.alertCountForTest());
    }

    /** 2026-11-04：矿点变空（无人采集）必须清掉旧采集者，后续行军不得再按「被攻击」推。 */
    @Test
    public void emptyResourceClearsStaleGatherer() {
        WatchPointStore store = store();
        WatchPointEngine engine = new WatchPointEngine(null, store, null, null);
        FakeEngine fe = new FakeEngine();
        engine.pollTickForTest(List.of(fe));
        int session = fe.sessions.get(0);

        Map<String, Object> gather = new LinkedHashMap<>();
        gather.put("march", march(9501L, VICTIM, 4L, 2L, 600, 700, 512, 688, FRIEND_AID));
        engine.feedResponseForTest(session, "notify_world_marchs", gather);
        assertTrue(engine.resByPointForTest().containsKey("512,688"));

        // 地图对象：空矿点（uid=0/march_id=0）
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", 1250101L);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", 2L);
        item.put("point", point(512, 688));
        item.put("map_resource", res);
        List<Object> dl = new ArrayList<>();
        dl.add(item);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("datalist", dl);
        content.put("end_flag", true);
        engine.feedResponseForTest(session, "notify_world_mapobjs", content);
        assertFalse("空矿点必须清掉旧采集者", engine.resByPointForTest().containsKey("512,688"));

        Map<String, Object> attack = new LinkedHashMap<>();
        attack.put("march", march(9502L, WATCHED, 2L, 2L, 278, 607, 512, 688, 0L));
        engine.feedResponseForTest(session, "notify_world_marchs", attack);
        assertEquals("无人采集的矿点不能报被攻击", 0, engine.alertCountForTest());
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
