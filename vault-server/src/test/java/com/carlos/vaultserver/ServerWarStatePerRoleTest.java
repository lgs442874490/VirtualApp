package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.guardian.WarState;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R2 Phase 2B：{@link ServerWarState} 战争态按角色 uid 隔离。
 *
 * <p>覆盖：同账号同服双角色实例独立（A 战时不影响 B 的 gateNote/roundYieldNote/wartimeYield/
 * summaryJson）；账号级聚合 wartimeYield(fpid) 保留；legacy uid=0 行为不变；key 形状；
 * unregister 只移除目标角色；p:war.* 按 roleParamsByUid 生效；全局节拍对双角色实例都生效。
 */
public class ServerWarStatePerRoleTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;
    private static final long OWNER = 49422287L;
    private static final long OWN_UID = 1001L;
    private static final long M = 60_000L;

    private final List<Long> created = new ArrayList<>();

    @After
    public void clean() {
        for (long uid : created) ServerWarState.unregister(FPID, SID, uid);
        created.clear();
    }

    private ServerWarState ws(long uid) {
        created.add(uid);
        return ServerWarState.register(FPID, SID, uid);
    }

    private static long nowS() {
        return System.currentTimeMillis() / 1000L;
    }

    private static Map<String, Object> march(long id, long type, long endSec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", OWNER);
        m.put("type", type);
        m.put("endtime", endSec);
        return m;
    }

    private static Map<String, Object> withTarget(Map<String, Object> m) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", 777L);   // 联盟域推送天然指向盟友，target 仅作形状
        m.put("atk_city", atk);
        return m;
    }

    private static Map<String, Object> alliancePush(Map<String, Object>... marches) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("to_marchs", List.of(marches));
        return c;
    }

    /** 让实例进入 WARTIME（H0 侦查即入）。 */
    private static void enterWartime(ServerWarState w, long marchId) {
        w.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(marchId, 1, nowS() + 600))), OWN_UID);
        assertTrue("夹具应进入战时", w.core.wartime());
    }

    // ---------------- 键与实例独立 ----------------

    @Test
    public void keyShape_includesUidAndLegacyZero() {
        assertEquals(FPID + "|" + SID + "|" + UID_A, ServerWarState.key(FPID, SID, UID_A));
        assertEquals(FPID + "|" + SID + "|" + UID_B, ServerWarState.key(FPID, SID, UID_B));
        assertEquals("旧 key（2 参）= uid0 槽", FPID + "|" + SID + "|0",
                ServerWarState.key(FPID, SID));
    }

    @Test
    public void dualRoles_instancesAndGatesIndependent() {
        ServerWarState a = ws(UID_A);
        ServerWarState b = ws(UID_B);
        assertEquals("同账号同服不同 uid 必须是不同实例", false, a == b);

        enterWartime(a, 1L);
        assertFalse("A 战时不影响 B 实例", b.core.wartime());

        assertNotNull("A 的 allowlist 外任务被拦", ServerWarState.gateNote(FPID, SID, UID_A, "gather"));
        assertNull("B 不被 A 拦", ServerWarState.gateNote(FPID, SID, UID_B, "gather"));
        assertNotNull("A 整轮让位", ServerWarState.roundYieldNote(FPID, SID, UID_A, ""));
        assertNull("B 整轮不让位", ServerWarState.roundYieldNote(FPID, SID, UID_B, ""));
        assertTrue("A 角色级让位", ServerWarState.wartimeYield(FPID, SID, UID_A));
        assertFalse("B 角色级不让位", ServerWarState.wartimeYield(FPID, SID, UID_B));
        assertTrue("账号级聚合仍为战时（任一角色）", ServerWarState.wartimeYield(FPID));

        JSONObject sumA = ServerWarState.summaryJson(FPID, SID, UID_A);
        JSONObject sumB = ServerWarState.summaryJson(FPID, SID, UID_B);
        assertNotNull(sumA);
        assertNotNull(sumB);
        assertEquals("WARTIME", sumA.getString("state"));
        assertEquals("NORMAL", sumB.getString("state"));
        assertNull("未注册角色摘要=null（不兜底兄弟角色）",
                ServerWarState.summaryJson(FPID, SID, 123456L));
    }

    @Test
    public void legacyUidZero_behaviorUnchanged() {
        created.add(0L);
        ServerWarState w = ServerWarState.register(FPID, SID);   // 旧签名 → uid=0
        enterWartime(w, 11L);

        // 旧签名全部仍按 uid=0 实例判定
        assertNotNull(ServerWarState.gateNote(FPID, SID, "gather"));
        assertNull(ServerWarState.gateNote(FPID, SID, "battle_shield"));
        assertNotNull(ServerWarState.roundYieldNote(FPID, SID, ""));
        assertTrue(ServerWarState.wartimeYield(FPID));
        assertTrue(ServerWarState.wartimeYield(FPID, SID, 0L));
        assertFalse("角色 uid>0 无实例 → 不会误命中旧聚合实例",
                ServerWarState.wartimeYield(FPID, SID, UID_A));
        JSONObject o = ServerWarState.summaryJson(FPID, SID);
        assertNotNull(o);
        assertEquals("WARTIME", o.getString("state"));
    }

    // ---------------- 注销 / 解卡 / 节拍 ----------------

    @Test
    public void unregister_onlyRemovesTargetRole() {
        ServerWarState a = ws(UID_A);
        ServerWarState b = ws(UID_B);
        enterWartime(a, 21L);
        enterWartime(b, 22L);

        ServerWarState.unregister(FPID, SID, UID_A);
        assertFalse("A 注销后不再让位", ServerWarState.wartimeYield(FPID, SID, UID_A));
        assertTrue("B 实例保留", ServerWarState.wartimeYield(FPID, SID, UID_B));
        assertTrue("账号聚合仍有 B 的战时态", ServerWarState.wartimeYield(FPID));

        ServerWarState freshA = ServerWarState.forRole(FPID, SID, UID_A);
        assertFalse("重新注册 A 是全新实例（旧战时态不残留）", freshA.core.wartime());
    }

    @Test
    public void resetWartime_clearsAllRoleInstancesOfAccount() {
        ServerWarState a = ws(UID_A);
        ServerWarState b = ws(UID_B);
        enterWartime(a, 31L);
        enterWartime(b, 32L);
        assertEquals("解卡返回解除实例数（2 个角色）", 2, ServerWarState.resetWartime(FPID));
        assertFalse(a.core.wartime());
        assertFalse(b.core.wartime());
        assertFalse("账号聚合解除", ServerWarState.wartimeYield(FPID));
    }

    @Test
    public void globalTicker_exitsBothRoleInstances() {
        ServerWarState a = ws(UID_A);
        ServerWarState b = ws(UID_B);
        long t0 = System.currentTimeMillis();
        long end = t0 / 1000 + 600;
        a.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(41, 1, end))), OWN_UID);
        b.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(42, 1, end))), OWN_UID);
        assertTrue(a.core.wartime());
        assertTrue(b.core.wartime());

        ServerWarState.tickAll(t0 + 31 * M);
        assertFalse("全局节拍让 A 静默退出", a.core.wartime());
        assertFalse("全局节拍让 B 静默退出", b.core.wartime());
        assertFalse("账号聚合随之解除", ServerWarState.wartimeYield(FPID));
    }

    @Test
    public void activeWartimeSummary_includesUid() {
        ServerWarState a = ws(UID_A);
        enterWartime(a, 51L);
        String s = ServerWarState.activeWartimeSummary(System.currentTimeMillis());
        assertTrue("摘要含 fpid|sid|uid: " + s, s.contains(FPID + "|" + SID + "|" + UID_A));
        ServerWarState.resetWartime(FPID);
        assertTrue(ServerWarState.activeWartimeSummary(System.currentTimeMillis()).isEmpty());
    }

    // ---------------- p:war.* 按角色配置 ----------------

    @Test
    public void configOf_prefersRoleParamsByUidThenSidFallback() {
        VaultStore.Account acc = new VaultStore.Account(FPID);
        Map<String, String> sidParams = new LinkedHashMap<>();
        sidParams.put("war", "{\"strategy\":{\"quiet_exit\":\"30\"}}");
        acc.roleParams.put(String.valueOf(SID), sidParams);
        Map<String, String> uidParams = new LinkedHashMap<>();
        uidParams.put("war", "{\"strategy\":{\"quiet_exit\":\"5\"}}");
        acc.roleParamsByUid.put(VaultStore.roleUidConfigKey(SID, UID_A), uidParams);

        assertEquals("uid 配置优先", 5 * M, ServerWarState.configOf(acc, SID, UID_A).quietExitMs);
        assertEquals("无 uid 配置回退 sid 配置", 30 * M,
                ServerWarState.configOf(acc, SID, UID_B).quietExitMs);
        assertEquals("旧签名（uid=0）走 sid 配置", 30 * M,
                ServerWarState.configOf(acc, SID).quietExitMs);
    }

    @Test
    public void configure_appliesPerRoleQuietExit() {
        ServerWarState a = ws(UID_A);
        ServerWarState b = ws(UID_B);
        VaultStore.Account acc = new VaultStore.Account(FPID);
        Map<String, String> sidParams = new LinkedHashMap<>();
        sidParams.put("war", "{\"strategy\":{\"quiet_exit\":\"30\"}}");
        acc.roleParams.put(String.valueOf(SID), sidParams);
        Map<String, String> uidParams = new LinkedHashMap<>();
        uidParams.put("war", "{\"strategy\":{\"quiet_exit\":\"5\"}}");
        acc.roleParamsByUid.put(VaultStore.roleUidConfigKey(SID, UID_A), uidParams);
        ServerWarState.configure(acc, SID, UID_A);
        ServerWarState.configure(acc, SID, UID_B);

        long t0 = System.currentTimeMillis();
        long end = t0 / 1000 + 600;
        a.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(61, 1, end))), OWN_UID);
        b.onPush("notify_world_alliance_situation_add",
                alliancePush(withTarget(march(62, 1, end))), OWN_UID);
        a.tick(t0 + 6 * M);
        assertFalse("A quiet_exit=5min → 6min 已退出", a.core.wartime());
        b.tick(t0 + 6 * M);
        assertTrue("B quiet_exit=30min（sid 回退）→ 仍战时", b.core.wartime());
        assertEquals("WarState 状态与核心一致", WarState.State.WARTIME, b.core.state());
    }
}
