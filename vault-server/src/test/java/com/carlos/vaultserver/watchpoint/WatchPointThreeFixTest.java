package com.carlos.vaultserver.watchpoint;

import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 批次 Q（2026-10-31 观察点三改）：
 * Q1 扫描完整性（>=95% 才判定、矩形连续 2 次完整为空才告警、迁城只在完整扫描）；
 * Q2 多角色选择/分发（2 引擎监视/保护/扫描分流；单角色回退）；
 * Q3 攻击者迁城/未定位跟踪（完整扫描触发、6h 未定位去重）。
 */
public class WatchPointThreeFixTest {

    private static final long KID = 888L;
    private static final String FPID = "48460403";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 合成引擎：不连网；捕获 fire-and-forget 的 session/proto。 */
    private static final class FakeEngine extends WjdrEngine {
        final String fpid;
        final long kid;
        final long uid;
        boolean online = true;
        final List<Integer> sessions = new ArrayList<>();
        final List<String> protos = new ArrayList<>();

        FakeEngine(String fpid, long kid, long uid) {
            super(null);
            this.fpid = fpid;
            this.kid = kid;
            this.uid = uid;
        }

        @Override public String sessionState() { return online ? STATE_ONLINE : STATE_TAKEOVER; }
        @Override public String lastFpid() { return fpid; }
        @Override public long lastServerId() { return kid; }
        @Override public long lastUid() { return uid; }
        @Override public void sendFireAndForget(String proto, Map<String, Object> args, int session) {
            protos.add(proto);
            sessions.add(session);
        }
    }

    private WatchPointStore store() {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.enabled = true;
        s.kid = KID;
        s.fpid = FPID;
        s.multiRole = true;
        s.wecomEnabled = false;
        s.monitorTargets = new ArrayList<>();
        s.protectRects = new ArrayList<>();
        return s;
    }

    private WatchPointEngine engine(WatchPointStore store) {
        return new WatchPointEngine(null, store, null, null);
    }

    /** 建满 484 个扫描 ctx（不自动完成），返回各自 session。 */
    private static List<Integer> primeScan(WatchPointEngine engine, FakeEngine fe) {
        engine.startScanForTest("单测");
        int guard = 0;
        while (engine.scanActiveForTest() && guard++ < 200) {
            int before = fe.sessions.size();
            engine.scanTickForTest(List.of(fe));
            if (fe.sessions.size() == before && engine.scanActiveForTest()
                    && engine.scanEndedForTest() == 0) {
                // 已发满 484（scanTick 在 ended 未达阈值时仍 active）→ 跳出
                break;
            }
        }
        return new ArrayList<>(fe.sessions);
    }

    private static void feedEmpty(WatchPointEngine engine, List<Integer> sessions, int count) {
        for (int i = 0; i < count && i < sessions.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("datalist", new ArrayList<>());
            c.put("end_flag", true);
            engine.feedResponseForTest(sessions.get(i), "notify_world_mapobjs", c);
        }
    }

    private static void feedCity(WatchPointEngine engine, int session, long uid, long aid,
                                 int x, int y, String nick) {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("uid", uid);
        city.put("aid", aid);
        if (nick != null) city.put("nickName", nick);
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("x", (long) x);
        pt.put("y", (long) y);
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("map_city", city);
        obj.put("point", pt);
        List<Object> dl = new ArrayList<>();
        dl.add(obj);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("datalist", dl);
        c.put("end_flag", true);
        engine.feedResponseForTest(session, "notify_world_mapobjs", c);
    }

    // ==================== Q1：完整度 ====================

    @Test
    public void incompleteScan_doesNotAlertRectOrMove() {
        WatchPointStore st = store();
        WatchPointStore.Rect rect = new WatchPointStore.Rect("THY-1", 0, 0, 100, 100);
        st.protectRects.add(rect);
        st.monitorTargets.add(new WatchPointStore.Target(7001L, "目标A", 10, 10));
        WatchPointEngine engine = engine(st);
        FakeEngine fe = new FakeEngine(FPID, KID, 1L);
        List<Integer> sessions = primeScan(engine, fe);
        // 100 帧（<95%）且带回迁城数据/空矩形 → 不允许判定
        feedCity(engine, sessions.get(0), 7001L, 888L, 20, 20, "目标A");
        feedEmpty(engine, sessions.subList(1, sessions.size()), 100);
        engine.finishScanForTest();

        assertEquals("不完整扫描 0 告警", 0, engine.alertCountForTest());
        assertFalse(engine.lastAlertTextForTest().contains("迁城"));
        assertFalse(engine.lastAlertTextForTest().contains("保护矩形为空"));
        // 保护矩形连续计数不因不完整扫描推进
        st.protectRects.clear();
        st.monitorTargets.clear();
    }

    @Test
    public void completeEmptyRect_alertsOnlyOnSecondCompleteScan() {
        WatchPointStore st = store();
        st.protectRects.add(new WatchPointStore.Rect("THY-1", 0, 0, 100, 100));
        WatchPointEngine engine = engine(st);
        FakeEngine fe = new FakeEngine(FPID, KID, 1L);

        List<Integer> s1 = primeScan(engine, fe);
        feedEmpty(engine, s1, 460);
        engine.finishScanForTest();
        assertEquals("第一次完整空扫不告警", 0, engine.alertCountForTest());

        List<Integer> s2 = primeScan(engine, fe);
        feedEmpty(engine, s2, 460);
        engine.finishScanForTest();
        assertEquals("连续第二次完整空扫才告警", 1, engine.alertCountForTest());
        String text = engine.lastAlertTextForTest();
        assertTrue("文案含矩形: " + text, text.contains("保护矩形为空") && text.contains("THY-1"));
        assertTrue("文案含完整度: " + text, text.contains("完整度=460/484"));
        assertTrue("文案含连续次数: " + text, text.contains("连续 2 次"));
    }

    @Test
    public void completeScan_moveAlertIncludesCompleteness() {
        WatchPointStore st = store();
        st.monitorTargets.add(new WatchPointStore.Target(7001L, "目标A", 10, 10));
        WatchPointEngine engine = engine(st);
        FakeEngine fe = new FakeEngine(FPID, KID, 1L);
        List<Integer> sessions = primeScan(engine, fe);
        feedCity(engine, sessions.get(0), 7001L, 888L, 20, 20, "目标A");
        feedEmpty(engine, sessions.subList(1, sessions.size()), 459);
        engine.finishScanForTest();

        String text = engine.lastAlertTextForTest();
        assertTrue("迁城告警: " + text, text.contains("迁城") && text.contains("(20,20)"));
        assertTrue("完整度: " + text, text.contains("完整度=460/484"));
        assertEquals(20, st.monitorTargets.get(0).x);
    }

    // ==================== Q2：多角色 ====================

    @Test
    public void multiRole_selectsAllSameAccountOnline() {
        WatchPointStore st = store();
        FakeEngine a = new FakeEngine(FPID, KID, 1L);
        FakeEngine b = new FakeEngine(FPID, KID, 2L);
        FakeEngine off = new FakeEngine(FPID, KID, 3L);
        off.online = false;
        FakeEngine otherAccount = new FakeEngine("999", KID, 4L);
        FakeEngine otherKid = new FakeEngine(FPID, 889L, 5L);

        List<WjdrEngine> picked = engine(st).pickEnginesForTest(
                List.of(a, b, off, otherAccount, otherKid));
        assertEquals("同账号同 kid 在线 2 个", 2, picked.size());
        assertTrue(picked.contains(a) && picked.contains(b));
    }

    @Test
    public void multiRole_dispatchesMonitorProtectToDifferentEngines() {
        WatchPointStore st = store();
        st.monitorTargets.add(new WatchPointStore.Target(7001L, "A", 100, 100));
        st.monitorTargets.add(new WatchPointStore.Target(7002L, "B", 200, 200));
        st.protectRects.add(new WatchPointStore.Rect("R1", 0, 0, 100, 100));
        st.protectRects.add(new WatchPointStore.Rect("R2", 100, 100, 200, 200));
        WatchPointEngine engine = engine(st);
        FakeEngine a = new FakeEngine(FPID, KID, 1L);
        FakeEngine b = new FakeEngine(FPID, KID, 2L);
        engine.pollTickForTest(List.of(a, b));

        int total = a.sessions.size() + b.sessions.size();
        assertTrue("两引擎都收到查询: a=" + a.sessions + " b=" + b.sessions,
                !a.sessions.isEmpty() && !b.sessions.isEmpty());
        assertEquals("2 监视 + 2 保护 = 4 请求", 4, total);
        // session 不重复（按 index 分区，不按引擎混用）
        java.util.Set<Integer> all = new java.util.HashSet<>(a.sessions);
        all.addAll(b.sessions);
        assertEquals("session 唯一", total, all.size());
    }

    @Test
    public void singleRoleFallback_usesFirstEngineOnly() {
        WatchPointStore st = store();
        st.multiRole = false;
        st.monitorTargets.add(new WatchPointStore.Target(7001L, "A", 100, 100));
        st.protectRects.add(new WatchPointStore.Rect("R1", 0, 0, 100, 100));
        FakeEngine a = new FakeEngine(FPID, KID, 1L);
        FakeEngine b = new FakeEngine(FPID, KID, 2L);
        WatchPointEngine engine = engine(st);
        List<WjdrEngine> picked = engine.pickEnginesForTest(List.of(a, b));
        assertEquals("单角色只选 1 个", 1, picked.size());
        engine.pollTickForTest(picked);
        assertTrue("只发到第一个引擎", !a.sessions.isEmpty() && b.sessions.isEmpty());
    }

    @Test
    public void multiRole_scanSplitsTilesAcrossEngines() {
        WatchPointStore st = store();
        WatchPointEngine engine = engine(st);
        FakeEngine a = new FakeEngine(FPID, KID, 1L);
        FakeEngine b = new FakeEngine(FPID, KID, 2L);
        List<Integer> sessions = primeScan(engine, a);   // 用单引擎建满 ctx
        assertEquals("扫描 484 个 session", 484, sessions.size());

        // 重新扫一遍，用 2 引擎分发
        FakeEngine a2 = new FakeEngine(FPID, KID, 1L);
        FakeEngine b2 = new FakeEngine(FPID, KID, 2L);
        engine.finishScanForTest();   // 收尾上一轮（不完整，不影响）
        engine.startScanForTest("两引擎");
        int guard = 0;
        while (a2.sessions.size() + b2.sessions.size() < 484 && guard++ < 300) {
            int before = a2.sessions.size() + b2.sessions.size();
            engine.scanTickForTest(List.of(a2, b2));
            if (a2.sessions.size() + b2.sessions.size() == before) break;
        }
        int total = a2.sessions.size() + b2.sessions.size();
        assertEquals("两引擎共 484 tile", 484, total);
        assertTrue("引擎 A 有分片", a2.sessions.size() > 0);
        assertTrue("引擎 B 有分片", b2.sessions.size() > 0);
        assertEquals("按 index 均匀分流", 242, a2.sessions.size());
        assertEquals(242, b2.sessions.size());
        java.util.Set<Integer> all = new java.util.HashSet<>(a2.sessions);
        all.addAll(b2.sessions);
        assertEquals("session 唯一", 484, all.size());
    }

    // ==================== Q3：攻击者跟踪 ====================

    @Test
    public void trackedAttacker_moveAlertsWithNewCoords() {
        WatchPointStore st = store();
        WatchPointEngine engine = engine(st);
        engine.trackAttackerForTest(8001L, 0L, 10, 10, "攻击者");

        FakeEngine fe = new FakeEngine(FPID, KID, 1L);
        List<Integer> sessions = primeScan(engine, fe);
        feedCity(engine, sessions.get(0), 8001L, 999L, 50, 60, "攻击者");
        feedEmpty(engine, sessions.subList(1, sessions.size()), 459);
        engine.finishScanForTest();

        String text = engine.lastAlertTextForTest();
        assertTrue("攻击者迁城: " + text, text.contains("攻击者迁城"));
        assertTrue("含老/新坐标: " + text, text.contains("(10,10)") && text.contains("(50,60)"));
        assertTrue("含完整度: " + text, text.contains("完整度=460/484"));
    }

    @Test
    public void trackedAttacker_missingAlertsOncePer6h() {
        WatchPointStore st = store();
        WatchPointEngine engine = engine(st);
        engine.trackAttackerForTest(8002L, 0L, 30, 40, "被击飞");

        FakeEngine fe = new FakeEngine(FPID, KID, 1L);
        List<Integer> s1 = primeScan(engine, fe);
        feedEmpty(engine, s1, 460);
        engine.finishScanForTest();
        String text = engine.lastAlertTextForTest();
        assertTrue("未定位文案: " + text, text.contains("攻击者未定位") && text.contains("(30,40)"));
        assertEquals(1, engine.alertCountForTest());

        // 立刻再完整扫一次：6h 去重，不重复告警
        List<Integer> s2 = primeScan(engine, fe);
        feedEmpty(engine, s2, 460);
        engine.finishScanForTest();
        assertEquals("同 uid 未定位 6h 内不重复", 1, engine.alertCountForTest());
    }

    @Test
    public void monitorTargetUid_skippedByAttackerTracking() {
        WatchPointStore st = store();
        st.monitorTargets.add(new WatchPointStore.Target(8003L, "监视目标", 10, 10));
        WatchPointEngine engine = engine(st);
        engine.trackAttackerForTest(8003L, 0L, 10, 10, "监视目标");

        FakeEngine fe = new FakeEngine(FPID, KID, 1L);
        List<Integer> sessions = primeScan(engine, fe);
        feedEmpty(engine, sessions, 460);
        engine.finishScanForTest();
        // monitorTargets 自身缺失会走 scan-missing，但不应出攻击者未定位/迁城
        String text = engine.lastAlertTextForTest();
        assertFalse("攻击者跟踪跳过 monitorTargets: " + text,
                text.contains("攻击者迁城") || text.contains("攻击者未定位"));
    }
}
