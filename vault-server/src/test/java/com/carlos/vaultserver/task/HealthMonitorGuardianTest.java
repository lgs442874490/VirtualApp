package com.carlos.vaultserver.task;

import com.carlos.vaultserver.SessionControl;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 C A11：守护盟友健康告警（HealthMonitor）。
 *
 * <p>两类检查：
 * <ul>
 *   <li>{@code guardian_idle}（INFO，只落日志）：守护已启用且在线，但账本最近事件 &gt;24h（或从未产生）；</li>
 *   <li>{@code guardian_fail}（WARN，推企微）：近 6h {@code launch_failed}/{@code recall_failed} ≥3。</li>
 * </ul>
 * 账本键兼容 Phase2 角色键 {@code srv:guardian:{fpid}:{sid}:{uid}:ledger} 与旧键 {@code …:{sid}:ledger}。
 */
public class HealthMonitorGuardianTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID = "35371154";
    private static final long SID_L = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;
    private VaultStore vault;

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
        vault.load();
    }

    // ---------------- 夹具 ----------------

    private void account(boolean guardianEnabled) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.activeSid = SID;
        a.activeUid = UID;
        String json = guardianEnabled ? "{\"sub\":{\"enabled\":true},\"strategy\":{\"dry_run\":true}}"
                : "{\"sub\":{\"enabled\":false}}";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("guardian", json);
        a.roleParams.put(SID, params);
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    private static SessionControl.SessionStatus online() {
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = FPID;
        s.sid = SID;
        s.state = com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE;
        return s;
    }

    private static JSONArray events(Object... evTs) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i + 1 < evTs.length; i += 2) {
            JSONObject o = new JSONObject();
            o.put("ts", ((Number) evTs[i + 1]).longValue());
            o.put("ev", String.valueOf(evTs[i]));
            arr.put(o);
        }
        return arr;
    }

    private static HealthMonitor.AccountHealth health(boolean online, boolean guardianEnabled,
                                                      long lastEventMillis, int fails6h) {
        HealthMonitor.AccountHealth h = new HealthMonitor.AccountHealth();
        h.fpid = FPID;
        h.sid = SID;
        h.online = online;
        h.guardianEnabled = guardianEnabled;
        h.guardianLastEventMillis = lastEventMillis;
        h.guardianFails6h = fails6h;
        return h;
    }

    // ==================== 纯函数：24h 无事件（INFO） ====================

    @Test
    public void idleGuardian_recentEvent_quiet() {
        long now = System.currentTimeMillis();
        assertNull("24h 内有事件 → 不告警",
                HealthMonitor.checkGuardianIdle(health(true, true, now - 3600_000L, 0), now));
    }

    @Test
    public void idleGuardian_24hWithoutEvent_infoAlert() {
        long now = System.currentTimeMillis();
        HealthMonitor.Alert a = HealthMonitor.checkGuardianIdle(
                health(true, true, now - 25L * 3600_000L, 0), now);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_GUARDIAN_IDLE, a.kind);
        assertEquals("INFO 级别（只落日志，不推企微）", HealthMonitor.SEVERITY_INFO, a.severity);
        assertTrue(a.metric.contains("24h") || a.metric.contains("小时"));

        HealthMonitor.Alert never = HealthMonitor.checkGuardianIdle(health(true, true, 0L, 0), now);
        assertNotNull("从未产生账本事件也要告警", never);
        assertTrue(never.metric.contains("从未"));
    }

    @Test
    public void idleGuardian_disabledOrOffline_noAlert() {
        long now = System.currentTimeMillis();
        assertNull("守护未启用 → 不告警",
                HealthMonitor.checkGuardianIdle(health(true, false, 0L, 0), now));
        assertNull("离线 → 不告警（离线由 offline 类告警覆盖）",
                HealthMonitor.checkGuardianIdle(health(false, true, 0L, 0), now));
    }

    // ==================== 纯函数：6h 连续失败（WARN） ====================

    @Test
    public void guardianFails_thresholdAndSeverity() {
        long now = System.currentTimeMillis();
        assertNull("失败 2 次未达阈值", HealthMonitor.checkGuardianFail(
                health(true, true, now, 2), now));
        HealthMonitor.Alert a = HealthMonitor.checkGuardianFail(
                health(true, true, now, 3), now);
        assertNotNull(a);
        assertEquals(HealthMonitor.KIND_GUARDIAN_FAIL, a.kind);
        assertEquals(HealthMonitor.SEVERITY_WARN, a.severity);
        assertTrue(a.metric.contains("3"));
        assertNull("守护未启用不告警", HealthMonitor.checkGuardianFail(
                health(true, false, now, 5), now));
        assertNull("离线不告警", HealthMonitor.checkGuardianFail(
                health(false, true, now, 5), now));
    }

    @Test
    public void evaluate_includesGuardianChecksWithSeverity() {
        long now = System.currentTimeMillis();
        List<HealthMonitor.AccountHealth> hs = List.of(
                health(true, true, now - 25L * 3600_000L, 4));
        List<HealthMonitor.Alert> alerts = HealthMonitor.evaluate(hs, now, false);
        HealthMonitor.Alert idle = null, fail = null;
        for (HealthMonitor.Alert a : alerts) {
            if (HealthMonitor.KIND_GUARDIAN_IDLE.equals(a.kind)) idle = a;
            if (HealthMonitor.KIND_GUARDIAN_FAIL.equals(a.kind)) fail = a;
        }
        assertNotNull("24h 无事件应出现在评估结果（INFO）", idle);
        assertNotNull("连续失败应出现在评估结果（WARN）", fail);
        assertEquals(HealthMonitor.SEVERITY_INFO, idle.severity);
        assertEquals(HealthMonitor.SEVERITY_WARN, fail.severity);
    }

    // ==================== 快照：角色键 + 旧键聚合 ====================

    @Test
    public void snapshot_parsesRoleAndLegacyLedgerKeys() {
        long now = System.currentTimeMillis();
        account(true);
        // 角色键：近 1h 有事件 + 2 次 launch_failed（6h 内）+ 1 次 7h 前旧失败（不计入）
        state.putString("srv:guardian:" + FPID + ":" + SID + ":" + UID + ":ledger", events(
                "launched", now - 3600_000L,
                "launch_failed", now - 7200_000L,
                "report_loss", now - 9000_000L,
                "launch_failed", now - 10_800_000L,
                "launch_failed", now - 7L * 3600_000L).toString());
        // 旧键（无 uid）：1 次 recall_failed（6h 内）
        state.putString("srv:guardian:" + FPID + ":" + SID + ":ledger", events(
                "recall_failed", now - 1200_000L).toString());

        List<HealthMonitor.AccountHealth> hs = HealthMonitor.snapshot(vault, state,
                () -> Collections.singletonList(online()), now, new LinkedHashMap<>());
        assertEquals(1, hs.size());
        HealthMonitor.AccountHealth h = hs.get(0);
        assertTrue("守护已启用（角色参数生效）", h.guardianEnabled);
        assertTrue("最近事件应取角色键/旧键的最大 ts", h.guardianLastEventMillis > 0L);
        assertEquals("6h 内失败=角色键 2 + 旧键 1（7h 前的不计）", 3, h.guardianFails6h);
        assertNull("有近期事件 → 不触发 24h 告警", HealthMonitor.checkGuardianIdle(h, now));
        assertNotNull("3 次失败 → WARN", HealthMonitor.checkGuardianFail(h, now));
    }

    @Test
    public void snapshot_legacyKeyOnly_stillParsed() {
        long now = System.currentTimeMillis();
        account(true);
        state.putString("srv:guardian:" + FPID + ":" + SID + ":ledger",
                events("threat_landed", now - 60_000L).toString());
        List<HealthMonitor.AccountHealth> hs = HealthMonitor.snapshot(vault, state,
                () -> Collections.singletonList(online()), now, new LinkedHashMap<>());
        assertEquals(1, hs.size());
        assertTrue("旧键兼容：最近事件可读", hs.get(0).guardianLastEventMillis > 0L);
        assertEquals(0, hs.get(0).guardianFails6h);
    }

    @Test
    public void snapshot_guardianDisabled_noGuardianAlerts() {
        long now = System.currentTimeMillis();
        account(false);
        state.putString("srv:guardian:" + FPID + ":" + SID + ":" + UID + ":ledger",
                events("launch_failed", now - 60_000L).toString());
        List<HealthMonitor.AccountHealth> hs = HealthMonitor.snapshot(vault, state,
                () -> Collections.singletonList(online()), now, new LinkedHashMap<>());
        assertEquals(1, hs.size());
        assertFalse("参数显式 enabled=false → 不告警", hs.get(0).guardianEnabled);
        assertNull(HealthMonitor.checkGuardianIdle(hs.get(0), now));
        assertNull(HealthMonitor.checkGuardianFail(hs.get(0), now));
    }

    @Test
    public void throttleKey_worksForGuardianKinds() {
        long now = System.currentTimeMillis();
        String key = HealthMonitor.throttleKey(HealthMonitor.KIND_GUARDIAN_FAIL, FPID, now);
        assertNotNull(key);
        assertTrue(key.contains(HealthMonitor.KIND_GUARDIAN_FAIL));
        assertTrue(key.contains(FPID));
    }
}
