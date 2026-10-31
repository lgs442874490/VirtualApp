package com.carlos.vaultserver.task;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 情报轮次纯函数单测（2026-10-31 用户规则 1）：
 * 每天 0/8/16 刷新、每轮 15h、由 expire_ts 反推轮次、无事件按刷新点唤醒、剩余时间文案。
 */
public class IntelRoundTest {

    private static long epoch(String iso) {
        return Instant.parse(iso).getEpochSecond();
    }

    @Test
    public void roundStart_isBeijingMidnight8And16() {
        // 2026-10-31 20:00 北京 = 12:00Z → 本轮从 16:00 北京（08:00Z）开始
        long now = epoch("2026-10-31T12:00:00Z");
        assertEquals(epoch("2026-10-31T08:00:00Z"), IntelRound.currentRoundStartSec(now));
        // 2026-11-01 00:00 北京 = 2026-10-31 16:00Z 正好是刷新边界
        assertEquals(epoch("2026-10-31T16:00:00Z"),
                IntelRound.currentRoundStartSec(epoch("2026-10-31T16:00:00Z")));
        // 2026-10-31 07:59 北京 = 2026-10-30 23:59Z → 本轮从 00:00 北京（前一天 16:00Z）开始
        assertEquals(epoch("2026-10-30T16:00:00Z"),
                IntelRound.currentRoundStartSec(epoch("2026-10-30T23:59:00Z")));
    }

    @Test
    public void nextRefresh_isStrictlyAfterNow_andAt0_8_16() {
        long now = epoch("2026-10-31T12:00:00Z");   // 北京 20:00
        assertEquals(epoch("2026-10-31T16:00:00Z"), IntelRound.nextRefreshSec(now));   // 北京 0:00
        assertEquals(epoch("2026-10-31T08:00:00Z"),
                IntelRound.nextRefreshSec(epoch("2026-10-31T07:59:00Z")));            // 北京 15:59 → 16:00
        // 正好在刷新点：下一刷新是 8h 后（不在边界重复触发）
        long boundary = epoch("2026-10-31T08:00:00Z");
        assertEquals(boundary + IntelRound.ROUND_INTERVAL_SEC, IntelRound.nextRefreshSec(boundary));
        assertEquals((boundary + IntelRound.ROUND_INTERVAL_SEC) * 1000L
                + IntelRound.REFRESH_WAKE_GRACE_MILLIS, IntelRound.nextRefreshWakeMillis(boundary));
    }

    @Test
    public void roundStartFromExpire_uses15hLifetime() {
        long round16 = epoch("2026-10-31T08:00:00Z");   // 北京 16:00
        long expire = round16 + IntelRound.LIFETIME_SEC;
        assertEquals(round16, IntelRound.roundStartSecOfExpire(expire));
        // 过期时刻有几分钟抖动也能吸附回轮次边界
        assertEquals(round16, IntelRound.roundStartSecOfExpire(expire + 300L));
        assertEquals(round16, IntelRound.roundStartSecOfExpire(expire - 300L));
        assertEquals(0L, IntelRound.roundStartSecOfExpire(0L));
    }

    @Test
    public void remainingAndExpiringWindow() {
        long now = 1_000L;
        assertEquals(600L, IntelRound.remainingSec(now, 1_600L));
        assertEquals(0L, IntelRound.remainingSec(now, 900L));
        assertTrue(IntelRound.expired(now, 900L));
        assertFalse(IntelRound.expired(now, 0L));
        assertTrue(IntelRound.expiringSoon(now, now + 1800L));
        assertTrue(IntelRound.expiringSoon(now, now + 1L));
        assertFalse(IntelRound.expiringSoon(now, now + 1801L));
    }

    @Test
    public void labels() {
        assertEquals("16:00 批次", IntelRound.roundLabel(epoch("2026-10-31T08:00:00Z")));
        assertEquals("00:00 批次", IntelRound.roundLabel(epoch("2026-10-31T16:00:00Z")));
        assertEquals("轮次未知", IntelRound.roundLabel(0L));
        assertEquals("4h12m", IntelRound.remainingLabel(4 * 3600L + 12 * 60L));
        assertEquals("42m", IntelRound.remainingLabel(42 * 60L));
        assertEquals("0m", IntelRound.remainingLabel(-5L));
    }

    @Test
    public void wakeAt_prefersPreemptThenPendingThenDispatch() {
        long nowMs = 1_000_000L;
        long nowSec = epoch("2026-10-31T12:00:00Z");
        // 刚召回采集：15s 后重派
        assertEquals(nowMs + ServerFieldTasks.INTEL_PREEMPT_RETRY_DELAY_MILLIS,
                IntelRound.intelWakeAt(nowMs, nowSec, 0, 1, true, false, false));
        // 无事件：下一刷新点 + 5s
        assertEquals(IntelRound.nextRefreshWakeMillis(nowSec),
                IntelRound.intelWakeAt(nowMs, nowSec, 0, 0, false, true, false));
        // 还有可派但被门控：60s
        assertEquals(nowMs + 60_000L,
                IntelRound.intelWakeAt(nowMs, nowSec, 1, 1, false, false, false));
        // 已派出且无待办：15min 兜底
        assertEquals(nowMs + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS,
                IntelRound.intelWakeAt(nowMs, nowSec, 1, 0, false, false, false));
        // 只剩战斗中/去重：去重窗 10min + 5s（≤15min 兜底）
        assertEquals(nowMs + ServerFieldTasks.INTEL_DISPATCH_DEDUPE_MILLIS + 5_000L,
                IntelRound.intelWakeAt(nowMs, nowSec, 0, 0, false, false, true));
        // 防 0：now<=0 → 0
        assertEquals(0L, IntelRound.intelWakeAt(0L, nowSec, 0, 0, false, false, false));
    }
}
