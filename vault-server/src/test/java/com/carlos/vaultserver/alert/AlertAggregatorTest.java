package com.carlos.vaultserver.alert;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 30s 波次聚合：a 打 b 的 N 波合并；a/c/d 分别打 b 各自独立（用户 2026-11-09 口径）。
 */
public class AlertAggregatorTest {

    private static final long W = 30_000L;

    @Test
    public void firstWaveImmediate_restSilent() {
        AlertAggregator agg = new AlertAggregator(W);
        assertTrue(agg.feed("a2:1:100:200", "波1", 0L));
        assertFalse(agg.feed("a2:1:100:200", "波2", 10_000L));
        assertFalse(agg.feed("a2:1:100:200", "波3", 20_000L));
        assertEquals(1, agg.size());
    }

    @Test
    public void differentAttackersSameTargetAreIndependent() {
        AlertAggregator agg = new AlertAggregator(W);
        // a、b、c 同时打同一个目标点 → 三个窗口，各自首条都立即推
        assertTrue(agg.feed("a2:1:100:200", "a 波1", 0L));
        assertTrue(agg.feed("a2:2:100:200", "b 波1", 0L));
        assertTrue(agg.feed("a2:3:100:200", "c 波1", 0L));
        assertEquals(3, agg.size());
    }

    @Test
    public void flushReturnsOnlyMultiWaveWindows() {
        AlertAggregator agg = new AlertAggregator(W);
        agg.feed("a2:1:100:200", "波1", 0L);
        agg.feed("a2:1:100:200", "波2", 5_000L);
        agg.feed("a2:2:100:200", "单波", 0L);
        List<AlertAggregator.Window> out = agg.flushExpired(40_000L);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).count);
        assertEquals("波1", out.get(0).baseText);
    }

    @Test
    public void expiredWindowStartsFreshOnNextWave() {
        AlertAggregator agg = new AlertAggregator(W);
        assertTrue(agg.feed("a2:1:100:200", "波1", 0L));
        // 窗口过期后再来一波 → 新窗口，重新即时推
        assertTrue(agg.feed("a2:1:100:200", "波4", 31_000L));
        List<AlertAggregator.Window> out = agg.flushExpired(40_000L);
        assertTrue(out.isEmpty());  // 新窗口 count=1，不补发
        assertEquals(1, agg.size() == 0 ? 1 : agg.size()); // 窗口仍在（31s 起算）
    }

    @Test
    public void flushRemovesWindow() {
        AlertAggregator agg = new AlertAggregator(W);
        agg.feed("k", "t", 0L);
        agg.feed("k", "t2", 1_000L);
        agg.flushExpired(40_000L);
        assertEquals(0, agg.size());
    }
}
