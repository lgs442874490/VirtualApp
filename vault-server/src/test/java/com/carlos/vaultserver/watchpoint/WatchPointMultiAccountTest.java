package com.carlos.vaultserver.watchpoint;

import com.carlos.vault.wjdr.WjdrEngine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 A/B（2026-11-01）：观察点多账号累计监听 + 跨区角色探测。
 *
 * <p>覆盖：allAccounts/fpids/fpid 兼容选择、跨区开关+uid 白名单、crossKidSupported 阈值、
 * 跨区不可用自动排除、N=3 分发、单角色回退、snapshot/apply round-trip（C：仅单 kid）。
 */
public class WatchPointMultiAccountTest {

    private static final long KID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeEngine extends WjdrEngine {
        final String fpid;
        final long kid;
        final long uid;
        boolean online = true;
        final List<Integer> sessions = new ArrayList<>();

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
            sessions.add(session);
        }
    }

    private WatchPointStore store() {
        WatchPointStore s = new WatchPointStore(tmp.getRoot().toPath());
        s.enabled = true;
        s.kid = KID;
        s.fpid = "";
        s.allAccounts = true;
        s.fpids = new ArrayList<>();
        s.crossKid = false;
        s.crossKidRoleUids = new ArrayList<>();
        s.multiRole = true;
        s.wecomEnabled = false;
        s.monitorTargets = new ArrayList<>();
        s.protectRects = new ArrayList<>();
        return s;
    }

    private WatchPointEngine engine(WatchPointStore store) {
        return new WatchPointEngine(null, store, null, null);
    }

    private static void feedEmpty(WatchPointEngine engine, int session) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("datalist", new ArrayList<>());
        c.put("end_flag", true);
        engine.feedResponseForTest(session, "notify_world_mapobjs", c);
    }

    private static void feedCity(WatchPointEngine engine, int session, long uid, int x, int y) {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("uid", uid);
        city.put("aid", 1L);
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

    // ==================== A：多账号 ====================

    @Test
    public void pickEngines_multiAccount_andWhitelist() {
        WatchPointStore st = store();
        WatchPointEngine engine = engine(st);
        FakeEngine a = new FakeEngine("100", KID, 1L);
        FakeEngine b = new FakeEngine("100", KID, 2L);
        FakeEngine c = new FakeEngine("200", KID, 3L);
        FakeEngine otherKid = new FakeEngine("300", 999L, 4L);
        List<WjdrEngine> online = List.of(a, b, c, otherKid);

        assertEquals("allAccounts 默认 true：3 个同 kid 角色", 3,
                engine.pickEnginesForTest(online).size());

        st.fpids = List.of("200");
        List<WjdrEngine> white = engine.pickEnginesForTest(online);
        assertEquals("fpids 白名单只留 200", 1, white.size());
        assertEquals(c, white.get(0));

        st.fpids = new ArrayList<>();
        st.fpid = "100";
        assertEquals("旧 fpid 兼容优先", 2, engine.pickEnginesForTest(online).size());

        st.fpid = "";
        st.allAccounts = false;
        assertEquals("allAccounts=false 锁首个账号", 2,
                engine.pickEnginesForTest(online).size());
    }

    @Test
    public void pickEngines_crossKid_switchAndUidList() {
        WatchPointStore st = store();
        WatchPointEngine engine = engine(st);
        FakeEngine same = new FakeEngine("100", KID, 10L);
        FakeEngine cross = new FakeEngine("100", 999L, 20L);
        FakeEngine cross2 = new FakeEngine("100", 999L, 21L);
        List<WjdrEngine> online = List.of(same, cross, cross2);

        assertEquals("默认排除其它区服", 1, engine.pickEnginesForTest(online).size());
        st.crossKid = true;
        st.crossKidRoleUids = List.of(20L);
        List<WjdrEngine> picked = engine.pickEnginesForTest(online);
        assertEquals("显式 uid 纳入跨区", 2, picked.size());
        assertTrue(picked.contains(cross));
        assertFalse(picked.contains(cross2));
    }

    @Test
    public void crossKidSupported_threshold() {
        assertNull(WatchPointEngine.crossKidSupported(0, 7));
        assertEquals(Boolean.FALSE, WatchPointEngine.crossKidSupported(0, 8));
        assertEquals(Boolean.FALSE, WatchPointEngine.crossKidSupported(0, 20));
        assertEquals(Boolean.TRUE, WatchPointEngine.crossKidSupported(1, 1));
        assertEquals(Boolean.TRUE, WatchPointEngine.crossKidSupported(3, 8));
    }

    @Test
    public void crossProbe_unavailable_excludedFromFuturePicks() {
        WatchPointStore st = store();
        st.crossKid = true;
        st.crossKidRoleUids = List.of(20L);
        WatchPointEngine engine = engine(st);
        FakeEngine same = new FakeEngine("100", KID, 10L);
        FakeEngine cross = new FakeEngine("100", 999L, 20L);
        List<WjdrEngine> engines = List.of(same, cross);

        engine.startScanForTest("跨区探测");
        int guard = 0;
        while (cross.sessions.size() < 8 && guard++ < 100) {
            engine.scanTickForTest(engines);
        }
        assertTrue("跨区分到 tile", cross.sessions.size() >= 8);
        for (int i = 0; i < 8; i++) feedEmpty(engine, cross.sessions.get(i));

        assertEquals(8, engine.crossProbeTilesForTest(20L));
        assertEquals(Boolean.FALSE, engine.crossSupportedForTest(20L));
        assertTrue("不可用自动排除", engine.crossUnavailableForTest(20L));
        assertEquals("后续 pick 排除跨区不可用", 1,
                engine.pickEnginesForTest(engines).size());
    }

    @Test
    public void crossProbe_available_whenCitiesReturned() {
        WatchPointStore st = store();
        st.crossKid = true;
        st.crossKidRoleUids = List.of(21L);
        WatchPointEngine engine = engine(st);
        FakeEngine same = new FakeEngine("100", KID, 10L);
        FakeEngine cross = new FakeEngine("100", 999L, 21L);
        List<WjdrEngine> engines = List.of(same, cross);

        engine.startScanForTest("跨区可用探测");
        int guard = 0;
        while (cross.sessions.isEmpty() && guard++ < 100) {
            engine.scanTickForTest(engines);
        }
        feedCity(engine, cross.sessions.get(0), 7001L, 500, 500);
        assertEquals(Boolean.TRUE, engine.crossSupportedForTest(21L));
        assertFalse(engine.crossUnavailableForTest(21L));
    }

    // ==================== B/C：分发与单角色 ====================

    @Test
    public void n3_scanDistribution_balancedAndUnique() {
        WatchPointStore st = store();
        WatchPointEngine engine = engine(st);
        FakeEngine a = new FakeEngine("100", KID, 1L);
        FakeEngine b = new FakeEngine("100", KID, 2L);
        FakeEngine c = new FakeEngine("100", KID, 3L);
        List<WjdrEngine> engines = List.of(a, b, c);

        engine.startScanForTest("三引擎");
        int guard = 0;
        while (a.sessions.size() + b.sessions.size() + c.sessions.size() < 484 && guard++ < 300) {
            engine.scanTickForTest(engines);
        }
        int total = a.sessions.size() + b.sessions.size() + c.sessions.size();
        assertEquals(484, total);
        assertTrue("均匀: " + a.sessions.size() + "/" + b.sessions.size() + "/" + c.sessions.size(),
                a.sessions.size() >= 161 && b.sessions.size() >= 161 && c.sessions.size() >= 161);
        java.util.Set<Integer> all = new java.util.HashSet<>(a.sessions);
        all.addAll(b.sessions);
        all.addAll(c.sessions);
        assertEquals("session 唯一", 484, all.size());
    }

    @Test
    public void singleRoleFallback_unchanged() {
        WatchPointStore st = store();
        st.multiRole = false;
        st.monitorTargets.add(new WatchPointStore.Target(7001L, "A", 100, 100));
        st.protectRects.add(new WatchPointStore.Rect("R", 0, 0, 100, 100));
        WatchPointEngine engine = engine(st);
        FakeEngine a = new FakeEngine("100", KID, 1L);
        FakeEngine b = new FakeEngine("100", KID, 2L);

        List<WjdrEngine> picked = engine.pickEnginesForTest(List.of(a, b));
        assertEquals(1, picked.size());
        engine.pollTickForTest(picked);
        assertFalse(a.sessions.isEmpty());
        assertTrue(b.sessions.isEmpty());
    }

    // ==================== snapshot/apply round-trip ====================

    @Test
    public void snapshotApply_multiAccountCrossFields() {
        WatchPointStore st = store();
        st.load();   // 种子默认 targets/rects（apply 校验需要）
        st.allAccounts = false;
        st.fpids = List.of("100", "200");
        st.crossKid = true;
        st.crossKidRoleUids = List.of(9001L, 9002L);
        JSONObject snap = st.snapshotJson();
        assertFalse(snap.optBoolean("allAccounts"));
        assertEquals(2, snap.getJSONArray("fpids").length());
        assertTrue(snap.optBoolean("crossKid"));
        assertEquals(2, snap.getJSONArray("crossKidRoleUids").length());

        JSONObject in = new JSONObject();
        in.put("allAccounts", true);
        in.put("fpids", new JSONArray().put("300"));
        in.put("crossKid", false);
        in.put("crossKidRoleUids", new JSONArray().put(9100L));
        assertEquals(null, st.applyConfig(in));
        assertTrue(st.allAccounts);
        assertEquals(List.of("300"), st.fpids);
        assertFalse(st.crossKid);
        assertEquals(List.of(9100L), st.crossKidRoleUids);

        String err = st.applyConfig(new JSONObject().put("fpids", new JSONArray().put("abc")));
        assertNotNull(err);
        assertTrue(err.contains("数字串"));
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 21; i++) tooMany.put(String.valueOf(1000 + i));
        err = st.applyConfig(new JSONObject().put("fpids", tooMany));
        assertNotNull(err);
        assertTrue(err.contains("最多 20"));
        err = st.applyConfig(new JSONObject().put("crossKidRoleUids",
                new JSONArray().put(0L)));
        assertNotNull(err);
        assertTrue(err.contains("大于 0"));
    }

    @Test
    public void crossProbeVerdict_thresholds() {
        // 有城 → 立即可用
        assertEquals(Boolean.TRUE, WatchPointEngine.crossProbeVerdict(5, 1));
        // 首次 0 城 → 待定（会重试）
        assertNull(WatchPointEngine.crossProbeVerdict(0, 1));
        // 连续 2 次 0 城 → 不可用
        assertEquals(Boolean.FALSE, WatchPointEngine.crossProbeVerdict(0, 2));
        // 有城优先于次数
        assertEquals(Boolean.TRUE, WatchPointEngine.crossProbeVerdict(1, 5));
    }
}
