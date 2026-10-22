package com.carlos.vault.daily.guardian;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6 EXTREME 常驻戒备（hold_garrison_min）状态机单测。
 * 覆盖：威胁清空/超时/关闭三态、威胁在途不超时（开放态）、武装续期取 max、状态行文案。
 */
public class GuardianHoldTest {

    private static final String FP = "fp-hold-test";
    private static final long KID = 990001L;
    private static final long TARGET = 123456789L;
    private static final long T0 = 1_700_000_000_000L;
    private static final long MIN20 = 20 * 60_000L;

    @Test
    public void evaluate_closed_offByDefault() {
        // 未武装（holdUntil=0）→ 永远放行撤防（默认关闭行为不变）
        assertFalse(GuardianHold.evaluate(T0, 0L, false).hold);
        assertFalse(GuardianHold.evaluate(T0, 0L, true).hold);
    }

    @Test
    public void evaluate_windowActive_holdWithRemaining() {
        GuardianHold.Decision d = GuardianHold.evaluate(T0, T0 + MIN20, false);
        assertTrue(d.hold);
        assertEquals(MIN20, d.remainingMs);
        // 边界：now == until → 放行
        assertFalse(GuardianHold.evaluate(T0 + MIN20, T0 + MIN20, false).hold);
    }

    @Test
    public void evaluate_timeout_releasesWhenThreatCleared() {
        // 威胁清空 + 窗口已过 → 撤防恢复默认路径
        assertFalse(GuardianHold.evaluate(T0 + MIN20 + 1, T0 + MIN20, false).hold);
    }

    @Test
    public void evaluate_threatActive_neverTimesOut() {
        // 威胁在途：窗口已过也保留（开放态 remaining=-1），新威胁由在位驻防承接
        GuardianHold.Decision d = GuardianHold.evaluate(T0 + MIN20 + 1, T0 + MIN20, true);
        assertTrue(d.hold);
        assertEquals(-1L, d.remainingMs);
        // 威胁在途 + 窗口未过：按窗口剩余展示
        assertEquals(MIN20, GuardianHold.evaluate(T0, T0 + MIN20, true).remainingMs);
    }

    @Test
    public void arm_storeAndExpiry() {
        String fp = FP + "-arm";
        try {
            assertEquals(T0 + MIN20, GuardianHold.arm(fp, KID, TARGET, T0, MIN20));
            assertTrue(GuardianHold.shouldHold(fp, KID, TARGET, T0 + 1));
            // 超时（威胁表无活跃威胁）→ 放行
            assertFalse(GuardianHold.shouldHold(fp, KID, TARGET, T0 + MIN20 + 1));
        } finally {
            GuardianHold.clear(fp, KID, TARGET);
        }
    }

    @Test
    public void arm_rearm_takesMaxNotReset() {
        String fp = FP + "-max";
        try {
            GuardianHold.arm(fp, KID, TARGET, T0, MIN20);
            // 5 分钟后威胁再次结束、重新武装 20 分钟 → 取 max（T0+25m），既有窗口不被重置缩短
            long rearmed = GuardianHold.arm(fp, KID, TARGET, T0 + 5 * 60_000L, MIN20);
            assertEquals(T0 + 25 * 60_000L, rearmed);
            assertTrue(GuardianHold.shouldHold(fp, KID, TARGET, T0 + 19 * 60_000L));
            assertFalse(GuardianHold.shouldHold(fp, KID, TARGET, T0 + 25 * 60_000L + 1));
        } finally {
            GuardianHold.clear(fp, KID, TARGET);
        }
    }

    @Test
    public void arm_invalidInputs_noop() {
        assertEquals(0L, GuardianHold.arm(FP, KID, 0L, T0, MIN20));        // 无目标
        assertEquals(0L, GuardianHold.arm(FP, KID, TARGET, T0, 0L));       // 关闭（0 分钟）
        assertEquals(0L, GuardianHold.arm(FP, KID, TARGET, T0, -1L));
        assertFalse(GuardianHold.shouldHold(FP, KID, TARGET, T0));
    }

    @Test
    public void statusText_windowFormats() {
        String fp = FP + "-text";
        try {
            assertEquals("", GuardianHold.holdStatusText(fp, KID, T0));
            GuardianHold.arm(fp, KID, TARGET, T0, MIN20);
            assertEquals("🛡常驻戒备中（剩余19m）",
                    GuardianHold.holdStatusText(fp, KID, T0 + 30_000L));
            // 不足 1 分钟按 1m 展示，不显示 0
            assertEquals("🛡常驻戒备中（剩余1m）",
                    GuardianHold.holdStatusText(fp, KID, T0 + MIN20 - 20_000L));
            // 窗口已过 + 无威胁在途 → 无文案（撤防路径已恢复）
            assertEquals("", GuardianHold.holdStatusText(fp, KID, T0 + MIN20 + 1));
        } finally {
            GuardianHold.clear(fp, KID, TARGET);
        }
    }

    @Test
    public void isolation_byFpid() {
        String fpA = FP + "-isoA";
        String fpB = FP + "-isoB";
        try {
            GuardianHold.arm(fpA, KID, TARGET, T0, MIN20);
            // 多账号不同盟（20261016 教训）：A 账号的戒备不影响 B 账号
            assertFalse(GuardianHold.shouldHold(fpB, KID, TARGET, T0 + 1));
            assertTrue(GuardianHold.shouldHold(fpA, KID, TARGET, T0 + 1));
        } finally {
            GuardianHold.clear(fpA, KID, TARGET);
            GuardianHold.clear(fpB, KID, TARGET);
        }
    }
}
