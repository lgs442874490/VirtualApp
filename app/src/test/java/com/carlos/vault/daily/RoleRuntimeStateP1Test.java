package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P1 暂停自动恢复展示单测（契约 §二/§三.2）：{@code pauseResumeAt} 字段桥接、
 * PAUSED 态倒计时（「已暂停·待恢复」+ mm:ss / h:mm:ss）、旧服务端无 pause_resume_at
 * 时维持 M2 口径（回归不破）、online 优先级、tone 配色、10 参数旧构造兼容。
 */
public class RoleRuntimeStateP1Test {

    private static final long NOW = 1_700_000_000_000L;
    private static final long AT = NOW + 272_000L;   // 4m32s 后

    private static RoleRuntimeState paused(long pauseResumeAt, boolean takeoverActive,
                                           String recState, long nextRecoveryAt) {
        return new RoleRuntimeState(false, RoleRuntimeState.RUN_PAUSED, recState, "",
                takeoverActive, nextRecoveryAt, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true, pauseResumeAt);
    }

    // ---------- PAUSED + pause_resume_at（P1 §三.2） ----------

    @Test
    public void label_pausedWithPauseResumeAt_countdownSuffix() {
        assertEquals("已暂停·待恢复 04:32",
                paused(AT, false, RoleRuntimeState.REC_AVAILABLE, 0L).label(NOW));
    }

    @Test
    public void label_pausedWithPauseResumeAt_pastTime_soonSuffix() {
        assertEquals("已暂停·待恢复 · 即将恢复",
                paused(NOW - 1, false, RoleRuntimeState.REC_AVAILABLE, 0L).label(NOW));
    }

    @Test
    public void label_pausedWithPauseResumeAt_hourFormat() {
        // ≥1h：h 不补零（1:02:03），与待恢复倒计时同口径
        RoleRuntimeState s = new RoleRuntimeState(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true, NOW + 3_723_000L);
        assertEquals("已暂停·待恢复 1:02:03", s.label(NOW));
    }

    @Test
    public void label_paused_pauseResumeAtWinsOverRecoveryCountdown() {
        // 两个倒计时源并存：pause_resume_at 优先（P1 口径），next_recovery_at 仅旧路径兜底
        assertEquals("已暂停·待恢复 04:32",
                paused(AT, true, RoleRuntimeState.REC_WAITING_RETRY, NOW + 60_000L).label(NOW));
    }

    // ---------- 旧服务端兼容（pause_resume_at=0） ----------

    @Test
    public void label_legacyPausedWaitingRetry_keepsM2NoSuffix() {
        // M2 回归：PAUSED + yt（无 pause_resume_at）→ 纯「已暂停·待恢复」，无后缀
        assertEquals("已暂停·待恢复",
                paused(0L, true, RoleRuntimeState.REC_WAITING_RETRY, NOW + 60_000L).label(NOW));
    }

    @Test
    public void label_pausedPlain_withoutAnything() {
        assertEquals("已暂停",
                paused(0L, false, RoleRuntimeState.REC_AVAILABLE, 0L).label(NOW));
    }

    @Test
    public void label_onlineBeatsPausedResume() {
        // 优先级 1：online 压过 PAUSED+pauseResumeAt（暂停后自动恢复成功瞬间等）
        RoleRuntimeState s = new RoleRuntimeState(true, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true, AT);
        assertEquals("在线", s.label(NOW));
    }

    @Test
    public void label_pausedWithResumeAt_beatsStoppedAndShelved() {
        // 优先级 2 先于 4/10：enabled=false（收纳态）+ PAUSED + pauseResumeAt 仍显示待恢复
        RoleRuntimeState s = new RoleRuntimeState(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, false, AT);
        assertEquals("已暂停·待恢复 04:32", s.label(NOW));
    }

    // ---------- tone 配色（带后缀的 label 仍归 warn） ----------

    @Test
    public void tone_pausedWithCountdown_isWarn() {
        assertEquals("warn", paused(AT, false, RoleRuntimeState.REC_AVAILABLE, 0L).tone());
        assertEquals("warn", paused(0L, true, RoleRuntimeState.REC_WAITING_RETRY,
                NOW + 1).tone());
        assertEquals("warn", paused(0L, false, RoleRuntimeState.REC_AVAILABLE, 0L).tone());
    }

    // ---------- 构造兼容 ----------

    @Test
    public void legacyTenArgConstructor_pauseResumeAtDefaultsZero() {
        RoleRuntimeState s = new RoleRuntimeState(false, RoleRuntimeState.RUN_PAUSED,
                RoleRuntimeState.REC_AVAILABLE, "", false, 0L, false, false,
                RoleRuntimeState.SCHED_ACTIVE, true);
        assertEquals(0L, s.pauseResumeAt);
        assertEquals("已暂停", s.label(NOW));
    }

    @Test
    public void offlineFactory_pauseResumeAtZero() {
        assertEquals(0L, RoleRuntimeState.offline(true).pauseResumeAt);
        assertEquals(0L, RoleRuntimeState.degradedLegacy("online").pauseResumeAt);
    }

    // ---------- 动作矩阵（P1 语义不变性：暂停待恢复可上线/停止，不可再暂停） ----------

    @Test
    public void actions_pausedWithResumeAt_sameMatrix() {
        RoleRuntimeState s = paused(AT, false, RoleRuntimeState.REC_AVAILABLE, 0L);
        assertTrue(s.canLogin());    // 上线（服务端 login 等价 resume）
        assertTrue(s.canResume());
        assertTrue(s.canStop());
        assertFalse(s.canPause());   // 已暂停不可再暂停
        assertFalse(s.degraded);
    }

    // ---------- banner（P1 暂停不走被顶号 banner） ----------

    @Test
    public void banner_pausedWithResumeAt_noTakeoverBanner() {
        // 「已暂停·待恢复」倒计时在状态列呈现；被顶号 banner 仅顶号恢复（yt）场景
        assertNull(paused(AT, false, RoleRuntimeState.REC_AVAILABLE, 0L).bannerText(NOW));
    }
}
