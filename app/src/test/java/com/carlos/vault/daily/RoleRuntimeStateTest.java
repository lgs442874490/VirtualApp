package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 账号池角色运行态单测（M2）：11 态文案逐字对齐网页版 §2.3（含优先级）、
 * 待恢复倒计时格式、被顶号 banner、动作矩阵、旧服务端降级映射。
 * 全部纯 JVM（{@link RoleRuntimeState} 无 Android 依赖）。
 */
public class RoleRuntimeStateTest {

    // ---------- 测试构造辅助（缺省 = M1 无会话推导态） ----------

    /** 可变参数便捷构造：缺省 runState=STOPPED / rec=AVAILABLE / sched=ACTIVE / enabled=true。 */
    private static RoleRuntimeState st(boolean online, String runState, String recoveryState,
                                       String statusMessage, boolean takeoverRecoveryActive,
                                       long nextRecoveryAt, boolean startupInProgress,
                                       boolean loginInProgress, String runScheduleState,
                                       boolean enabled) {
        return new RoleRuntimeState(online, runState, recoveryState, statusMessage,
                takeoverRecoveryActive, nextRecoveryAt, startupInProgress, loginInProgress,
                runScheduleState, enabled);
    }

    private static RoleRuntimeState running() {
        return st(false, RoleRuntimeState.RUN_RUNNING, RoleRuntimeState.REC_AVAILABLE,
                "", false, 0L, false, false, RoleRuntimeState.SCHED_ACTIVE, true);
    }

    private static final long NOW = 1_700_000_000_000L;

    // ---------- 11 态文案（逐字） ----------

    @Test
    public void label_1_online_hasTopPriority() {
        // online=true 压过一切其它字段（即使 runState/recovery 矛盾）
        assertEquals("在线", st(true, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_WAITING_RETRY, "", true, NOW + 1, true, true,
                RoleRuntimeState.SCHED_PAUSED, true).label(NOW));
    }

    @Test
    public void label_2_pausedWithRecoveryWaiting() {
        // yt = takeoverRecoveryActive && WAITING_RETRY，且 runState=PAUSED → 已暂停·待恢复
        assertEquals("已暂停·待恢复", st(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_WAITING_RETRY, "", true, NOW + 60_000, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
    }

    @Test
    public void label_3_paused_withoutRecoveryPending() {
        // PAUSED 但 yt 不成立（takeoverRecoveryActive=false）→ 已暂停
        assertEquals("已暂停", st(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_WAITING_RETRY, "", false, NOW + 60_000, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
        // PAUSED + recoveryState 非 WAITING_RETRY 同样已暂停
        assertEquals("已暂停", st(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", true, NOW + 60_000, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
    }

    @Test
    public void label_4_stopped() {
        assertEquals("已停止", st(false, RoleRuntimeState.RUN_STOPPED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
    }

    @Test
    public void label_5_takeoverKicked_needsDisabledAndOtherDeviceMessage() {
        // DISABLED + statusMessage 含「其他设备」→ 被顶号（runState 非 STOPPED 时可见）
        assertEquals("被顶号", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_DISABLED, "账号在其他设备登录被顶下线", false, 0L,
                false, false, RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
        // DISABLED 但 message 不含「其他设备」→ 不判被顶号（继续走后续优先级）
        assertEquals("离线", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_DISABLED, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
        // 优先级：STOPPED(4) 先于 被顶号(5)——与网页版权威顺序一致
        assertEquals("已停止", st(false, RoleRuntimeState.RUN_STOPPED,
                RoleRuntimeState.REC_DISABLED, "被其他设备顶下线", false, 0L,
                false, false, RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
    }

    @Test
    public void label_6_actionRequired_needLogin() {
        assertEquals("需登录", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_ACTION_REQUIRED, "token 失效", false, 0L,
                false, false, RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
    }

    @Test
    public void label_7_recovering_withCountdown() {
        RoleRuntimeState s = st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_WAITING_RETRY, "", true,
                NOW + 272_000, false, false, RoleRuntimeState.SCHED_ACTIVE, true);
        assertEquals("待恢复 04:32", s.label(NOW));
        assertEquals("待恢复 00:05", s.label(NOW + 267_000));
        // ≥1h：h 不补零，m/s 补零（1:02:03）
        RoleRuntimeState h1 = st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_WAITING_RETRY, "", true,
                NOW + 3_723_000, false, false, RoleRuntimeState.SCHED_ACTIVE, true);
        assertEquals("待恢复 1:02:03", h1.label(NOW));
        // ≤0 / 未知时刻 → 即将恢复（M1：断线退避 nextRecoveryAt=0）
        assertEquals("待恢复 · 即将恢复", s.label(NOW + 272_000));
        assertEquals("待恢复 · 即将恢复",
                st(false, RoleRuntimeState.RUN_RUNNING, RoleRuntimeState.REC_WAITING_RETRY,
                        "", true, 0L, false, false, RoleRuntimeState.SCHED_ACTIVE, true)
                        .label(NOW));
    }

    @Test
    public void label_8_loginInProgress_beatsScheduledSleep() {
        assertEquals("登录中", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, true, false,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
        assertEquals("登录中", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, true,
                RoleRuntimeState.SCHED_ACTIVE, true).label(NOW));
    }

    @Test
    public void label_9_scheduledSleep() {
        // 计划休眠：runScheduleState=PAUSED（runState 非 STOPPED 才可见，STOPPED 先命中）
        assertEquals("计划休眠", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_PAUSED, true).label(NOW));
    }

    @Test
    public void label_10_shelved_whenDisabled() {
        // enabled=false → 已收纳（runState 非 STOPPED 才可见；见 11 态优先级 4>10）
        assertEquals("已收纳", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, false).label(NOW));
        // enabled=false + STOPPED：优先级 STOPPED(4) 先命中（与网页版一致）
        assertEquals("已停止", st(false, RoleRuntimeState.RUN_STOPPED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, false).label(NOW));
    }

    @Test
    public void label_11_offline_isDefault() {
        // 非在线、非暂停、非停止、无任何标记 → 离线
        assertEquals("离线", running().label(NOW));
    }

    // ---------- 被顶号 banner ----------

    @Test
    public void banner_recoveringWithCountdown() {
        RoleRuntimeState s = st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_WAITING_RETRY, "", true,
                NOW + 272_000, false, false, RoleRuntimeState.SCHED_ACTIVE, true);
        assertEquals("账号被顶号 · 待恢复 04:32", s.bannerText(NOW));
        // 已过期 → 即将恢复
        assertEquals("账号被顶号 · 即将恢复", s.bannerText(NOW + 300_000));
    }

    @Test
    public void banner_kickedWithoutRecoveryPlan() {
        assertEquals("账号被顶号", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_DISABLED, "账号在其他设备登录被顶下线", false, 0L,
                false, false, RoleRuntimeState.SCHED_ACTIVE, true).bannerText(NOW));
    }

    @Test
    public void banner_nullForNormalStates() {
        assertNull(running().bannerText(NOW));
        assertNull(RoleRuntimeState.offline(true).bannerText(NOW));
        assertNull(RoleRuntimeState.degradedLegacy("takeover").bannerText(NOW));
        // 在线不需要 banner
        assertNull(st(true, RoleRuntimeState.RUN_RUNNING, RoleRuntimeState.REC_WAITING_RETRY,
                "", true, NOW + 60_000, false, false, RoleRuntimeState.SCHED_ACTIVE, true)
                .bannerText(NOW));
    }

    // ---------- 动作矩阵 ----------

    @Test
    public void actions_online_pauseAndStop() {
        RoleRuntimeState s = st(true, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true);
        assertFalse(s.canLogin());
        assertTrue(s.canPause());
        assertTrue(s.canStop());
        assertFalse(s.canResume());
    }

    @Test
    public void actions_paused_loginResumeStop_noPause() {
        RoleRuntimeState s = st(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true);
        assertTrue(s.canLogin());     // M4 §3.2 修订：!online && enabled 即可上线
        assertFalse(s.canPause());
        assertTrue(s.canStop());
        assertTrue(s.canResume());
    }

    @Test
    public void actions_stopped_loginOnly_noStopPauseResume() {
        // 已停止（M4 §3.2 修复）：「上线」可用（role-action login，服务端对停止角色
        // 即启动）；「停止」仍只在在线/暂停显示，暂停/收纳语义不变
        RoleRuntimeState s = RoleRuntimeState.offline(true);
        assertTrue(s.canLogin());
        assertFalse(s.canPause());
        assertFalse(s.canStop());
        assertFalse(s.canResume());
    }

    @Test
    public void actions_actionRequired_loginOnly() {
        RoleRuntimeState s = st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_ACTION_REQUIRED, "token 失效", false, 0L,
                false, false, RoleRuntimeState.SCHED_ACTIVE, true);
        assertTrue(s.canLogin());
        assertFalse(s.canPause());
        assertFalse(s.canStop());
        assertFalse(s.canResume());
    }

    @Test
    public void actions_shelved_allDisabled() {
        // enabled=false → 全部禁用（契约示例第 4 条）
        RoleRuntimeState s = st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, false);
        assertFalse(s.canLogin());
        assertFalse(s.canPause());
        assertFalse(s.canStop());
        assertFalse(s.canResume());
        // 在线但已停用同样全部禁用
        RoleRuntimeState on = st(true, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, false);
        assertFalse(on.canPause());
        assertFalse(on.canStop());
    }

    @Test
    public void actions_degraded_allDisabled() {
        for (String legacy : new String[]{"online", "recovering", "takeover",
                "need_token", "offline"}) {
            RoleRuntimeState s = RoleRuntimeState.degradedLegacy(legacy);
            assertFalse(legacy, s.canLogin());
            assertFalse(legacy, s.canPause());
            assertFalse(legacy, s.canStop());
            assertFalse(legacy, s.canResume());
            assertTrue(legacy, s.degraded);
        }
    }

    // ---------- 旧服务端降级映射 ----------

    @Test
    public void degradedLegacy_labels_reuseExistingMapping() {
        assertEquals("在线", RoleRuntimeState.degradedLegacy("online").label());
        assertEquals("重连中", RoleRuntimeState.degradedLegacy("recovering").label());
        assertEquals("被顶号暂停", RoleRuntimeState.degradedLegacy("takeover").label());
        assertEquals("需重导token", RoleRuntimeState.degradedLegacy("need_token").label());
        assertEquals("离线", RoleRuntimeState.degradedLegacy("offline").label());
        assertEquals("离线", RoleRuntimeState.degradedLegacy(null).label());
        assertEquals("离线", RoleRuntimeState.degradedLegacy("future_state").label());
    }

    @Test
    public void degradedLegacy_toneMapping() {
        assertEquals("ok", RoleRuntimeState.degradedLegacy("online").tone());
        assertEquals("warn", RoleRuntimeState.degradedLegacy("recovering").tone());
        assertEquals("err", RoleRuntimeState.degradedLegacy("takeover").tone());
        assertEquals("err", RoleRuntimeState.degradedLegacy("need_token").tone());
        assertEquals("idle", RoleRuntimeState.degradedLegacy("offline").tone());
    }

    // ---------- 配色基调 ----------

    @Test
    public void tone_coversElevenStates() {
        assertEquals("ok", st(true, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).tone());
        assertEquals("warn", st(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).tone());
        assertEquals("warn", st(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_WAITING_RETRY, "", true, NOW + 1, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).tone());
        assertEquals("err", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_DISABLED, "被其他设备顶下线", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).tone());
        assertEquals("err", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_ACTION_REQUIRED, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true).tone());
        assertEquals("busy", st(false, RoleRuntimeState.RUN_RUNNING,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, true, false,
                RoleRuntimeState.SCHED_ACTIVE, true).tone());
        assertEquals("idle", RoleRuntimeState.offline(true).tone());
    }

    // ---------- 缺省构造（offline / 字段兜底） ----------

    @Test
    public void offline_defaultMatchesM1NoSession() {
        RoleRuntimeState s = RoleRuntimeState.offline(true);
        assertFalse(s.online);
        assertEquals(RoleRuntimeState.RUN_STOPPED, s.runState);
        assertEquals(RoleRuntimeState.REC_AVAILABLE, s.recoveryState);
        assertEquals(RoleRuntimeState.SCHED_ACTIVE, s.runScheduleState);
        assertTrue(s.enabled);
        assertEquals("已停止", s.label());
    }

    @Test
    public void constructor_nullRunState_fallsBackToStopped() {
        RoleRuntimeState s = new RoleRuntimeState(false, null, null, null,
                false, 0L, false, false, null, true);
        assertEquals(RoleRuntimeState.RUN_STOPPED, s.runState);
        assertEquals(RoleRuntimeState.REC_AVAILABLE, s.recoveryState);
        assertEquals("", s.statusMessage);
        assertEquals(RoleRuntimeState.SCHED_ACTIVE, s.runScheduleState);
    }

    // ---------- 倒计时边界 ----------

    @Test
    public void countdownText_boundaries() {
        // 整 1 小时 → h:mm:ss 口径
        assertEquals("1:00:00", RoleRuntimeState.countdownText(NOW + 3_600_000, NOW));
        // 59:59 仍是 mm:ss
        assertEquals("59:59", RoleRuntimeState.countdownText(NOW + 3_599_000, NOW));
        // 999ms 不足 1s → 向上取整为 00:01
        assertEquals("00:01", RoleRuntimeState.countdownText(NOW + 999, NOW));
        // 负值/零 → 即将恢复
        assertEquals("· 即将恢复", RoleRuntimeState.countdownText(NOW, NOW));
        assertEquals("· 即将恢复", RoleRuntimeState.countdownText(NOW - 1, NOW));
        assertEquals("· 即将恢复", RoleRuntimeState.countdownText(0, NOW));
    }
}
