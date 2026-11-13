package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * R0-2 分段耗时聚合单测（需求 R0-2 / 开发计划 S4）：
 * 200 条或 60 秒先到先输出；n&lt;10 只累计；进程内计数（新实例清零）。
 */
public class ShieldLatencyAggregatorTest {

    private static String capture(Runnable action) {
        PrintStream old = System.out;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(old);
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    @Test
    public void belowTen_neverFlushesEvenAfterMinute() {
        ShieldLatencyAggregator a = new ShieldLatencyAggregator();
        long t0 = 1_000_000L;
        String out = capture(() -> {
            for (int i = 0; i < 9; i++) a.add(10L, 20L, 30L, 60L, t0 + i);
            a.maybeFlush(t0 + 61_000L);
        });
        assertFalse("n<10 不得输出：" + out, out.contains("盾时延 P50/P95/P99"));
        assertEquals(9, a.pendingSamples());
    }

    @Test
    public void tenSamplesAfterMinute_flushesOnce() {
        ShieldLatencyAggregator a = new ShieldLatencyAggregator();
        long t0 = 1_000_000L;
        String out = capture(() -> {
            for (int i = 0; i < 10; i++) a.add(10L, 20L, 30L, 60L, t0 + i);
            a.maybeFlush(t0 + 60_000L);
        });
        assertEquals("应输出一次：" + out, 1, count(out, "盾时延 P50/P95/P99"));
        assertTrue("应带 n=10：" + out, out.contains("n=10"));
        assertEquals("输出后窗口清零", 0, a.pendingSamples());
    }

    @Test
    public void tenSamplesBeforeMinute_doNotFlush() {
        ShieldLatencyAggregator a = new ShieldLatencyAggregator();
        long t0 = 1_000_000L;
        String out = capture(() -> {
            for (int i = 0; i < 10; i++) a.add(10L, 20L, 30L, 60L, t0 + i);
            a.maybeFlush(t0 + 59_999L);
        });
        assertFalse("未到 60 秒不输出：" + out, out.contains("盾时延 P50/P95/P99"));
    }

    @Test
    public void twoHundredSamples_flushImmediately() {
        ShieldLatencyAggregator a = new ShieldLatencyAggregator();
        long t0 = 1_000_000L;
        String out = capture(() -> {
            for (int i = 0; i < ShieldLatencyAggregator.CAPACITY; i++) {
                a.add(1L, 1L, 1L, 3L, t0 + i);
            }
        });
        assertEquals("满 200 条应立即输出：" + out, 1, count(out, "盾时延 P50/P95/P99"));
        assertTrue("应带 n=200：" + out, out.contains("n=200"));
        assertEquals(0, a.pendingSamples());
    }

    @Test
    public void percentiles_areNearestRankPerSegment() {
        ShieldLatencyAggregator a = new ShieldLatencyAggregator();
        long t0 = 1_000_000L;
        String out = capture(() -> {
            for (int i = 1; i <= 100; i++) {
                a.add(i, i * 2L, i * 3L, i * 6L, t0 + i);
            }
            a.maybeFlush(t0 + 61_000L);
        });
        String line = lineContaining(out, "盾时延 P50/P95/P99");
        // 最近秩：p50=第 50 个、p95=第 95 个、p99=第 99 个
        assertTrue("pushWake 百分位：" + line, line.contains("pushWake=50/95/99ms"));
        assertTrue("wakeLock 百分位：" + line, line.contains("wakeLock=100/190/198ms"));
        assertTrue("lockResp 百分位：" + line, line.contains("lockResp=150/285/297ms"));
        assertTrue("total 百分位：" + line, line.contains("total=300/570/594ms"));
        assertTrue("样本数：" + line, line.contains("n=100"));
    }

    @Test
    public void newInstance_startsEmpty() {
        ShieldLatencyAggregator a = new ShieldLatencyAggregator();
        long t0 = 1_000_000L;
        capture(() -> {
            for (int i = 0; i < 100; i++) a.add(1L, 1L, 1L, 3L, t0 + i);
            a.maybeFlush(t0 + 60_000L);
        });
        ShieldLatencyAggregator fresh = new ShieldLatencyAggregator();
        assertEquals("进程重启语义：新实例计数清零", 0, fresh.pendingSamples());
    }

    private static String lineContaining(String out, String needle) {
        for (String line : out.split("\\R")) {
            if (line.contains(needle)) return line;
        }
        throw new AssertionError("未找到包含 " + needle + " 的日志：" + out);
    }
}
