package com.carlos.vault.wjdr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R0-3 时钟健康观测单测（需求 R0-3 / 开发计划 S1）：
 * <ul>
 *   <li>只读样本元数据：{@code lastSampleSource/lastSampleRttMs/lastOffsetDeltaMs}；</li>
 *   <li>首次校准日志追加 {@code wall/delta}；</li>
 *   <li>健康日志 24h 周期、未校准不输出。</li>
 * </ul>
 *
 * <p>零行为约束：本测试只断言新增观测字段，既有 offset/errBound 口径不在此固化（由改动前全量单测守）。
 * 单调钟用静态注入替换，{@link After} 恢复默认实现，避免污染同 JVM 的其他测试。
 */
public class GameServerClockObservabilityTest {

    private final AtomicLong mono = new AtomicLong(1_000_000L);

    @Before
    public void setUp() {
        GameServerClock.setMono(mono::get);
    }

    @After
    public void tearDown() {
        GameServerClock.setMono(() -> System.nanoTime() / 1_000_000L);
    }

    private static Map<String, Object> serverTime(long sec) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("server_time", sec);
        return c;
    }

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

    private static long parseLong(String text, String key) {
        Matcher m = Pattern.compile(key + "=(-?\\d+)").matcher(text);
        assertTrue("日志缺少字段 " + key + "：" + text, m.find());
        return Long.parseLong(m.group(1));
    }

    // ---------------- 未校准默认值 ----------------

    @Test
    public void uncalibrated_defaults() {
        GameServerClock c = new GameServerClock();
        assertFalse(c.locked());
        assertEquals("未校准来源应为 NONE", "NONE", c.lastSampleSource());
        assertEquals("未校准 rtt 应为 -1", -1L, c.lastSampleRttMs());
        assertEquals("未校准 delta 应为 0", 0L, c.lastOffsetDeltaMs());
        assertEquals("未校准 sinceCalib 应为 -1", -1L, c.sinceCalibMs());
    }

    // ---------------- 推送样本元数据 ----------------

    @Test
    public void pushSample_recordsSourceRttAndZeroDelta() {
        GameServerClock c = new GameServerClock();
        c.calibratePush(serverTime(1_700_000_000L), mono.get());

        assertTrue("推送样本应完成校准", c.locked());
        assertEquals("PUSH", c.lastSampleSource());
        assertEquals("推送侧伪 RTT 应为 300ms", 300L, c.lastSampleRttMs());
        assertEquals("首个样本 offset 变化应记 0", 0L, c.lastOffsetDeltaMs());
        assertEquals(1_700_000_000L, c.nowServerSec());
    }

    // ---------------- 请求样本元数据 ----------------

    @Test
    public void reqSample_recordsOffsetDeltaAgainstPreviousBest() {
        GameServerClock c = new GameServerClock();
        c.calibratePush(serverTime(1_700_000_000L), mono.get());
        long prevOffset = 1_700_000_000_000L - mono.get();

        mono.set(1_200_000L);
        long t0 = mono.get() - 40L;
        long t1 = mono.get();
        c.calibrate(serverTime(1_700_000_010L), t0, t1, 0L);

        long expectedOffset = 1_700_000_010_000L - (t0 + 20L);
        assertEquals("REQ", c.lastSampleSource());
        assertEquals(40L, c.lastSampleRttMs());
        assertEquals("offset 增量应相对上一最优样本",
                expectedOffset - prevOffset, c.lastOffsetDeltaMs());
    }

    // ---------------- 首次校准日志追加字段 ----------------

    @Test
    public void firstCalibrationLog_appendsWallAndDelta() {
        GameServerClock c = new GameServerClock();
        String out = capture(() -> c.calibratePush(serverTime(1_700_000_000L), mono.get()));

        assertTrue("既有首次校准日志前缀不得改：" + out,
                out.contains("GameServerClock 首次校准成功"));
        assertTrue("应追加 wall：" + out, out.contains("wall="));
        assertTrue("应追加 delta：" + out, out.contains("delta="));
        long wall = parseLong(out, "wall");
        long delta = parseLong(out, "delta");
        assertEquals("delta = nowServerMs - 本机墙钟", 1_700_000_000_000L - wall, delta);
    }

    // ---------------- 健康日志 24h 周期 ----------------

    @Test
    public void healthLog_onlyAfter24h() {
        GameServerClock c = new GameServerClock();
        long base = mono.get();

        String first = capture(() -> c.calibratePush(serverTime(1_700_000_000L), mono.get()));
        assertFalse("首次校准不输出健康日志：" + first, first.contains("时钟健康"));

        mono.set(base + 23L * 3600_000L);
        String at23h = capture(() -> c.calibratePush(
                serverTime(1_700_000_000L + 23L * 3600L), mono.get()));
        assertFalse("23h 不应输出健康日志：" + at23h, at23h.contains("时钟健康"));

        mono.set(base + 24L * 3600_000L);
        String at24h = capture(() -> c.calibratePush(
                serverTime(1_700_000_000L + 24L * 3600L), mono.get()));
        assertEquals("跨 24h 应恰好输出一条健康日志：" + at24h,
                1, count(at24h, "时钟健康"));
        assertTrue("健康日志字段：offset", at24h.contains("offset="));
        assertTrue("健康日志字段：err", at24h.contains("err="));
        assertTrue("健康日志字段：rtt", at24h.contains("rtt="));
        assertTrue("健康日志字段：src", at24h.contains("src=PUSH"));
        assertTrue("健康日志字段：serverNow", at24h.contains("serverNow="));
        assertTrue("健康日志字段：wall", at24h.contains("wall="));
        assertTrue("健康日志字段：delta", at24h.contains("delta="));
        assertTrue("健康日志字段：sinceCalib", at24h.contains("sinceCalib="));
        long serverNow = parseLong(at24h, "serverNow");
        long wall = parseLong(at24h, "wall");
        long delta = parseLong(at24h, "delta");
        assertEquals("delta = serverNow(毫秒) - wall",
                serverNow * 1000L - wall, delta);
    }

    @Test
    public void healthLog_resetsTimerAfterEmission() {
        GameServerClock c = new GameServerClock();
        long base = mono.get();
        capture(() -> c.calibratePush(serverTime(1_700_000_000L), mono.get()));

        mono.set(base + 24L * 3600_000L);
        String first24h = capture(() -> c.calibratePush(
                serverTime(1_700_000_000L + 24L * 3600L), mono.get()));
        assertEquals(1, count(first24h, "时钟健康"));

        mono.set(base + 30L * 3600_000L);
        String at30h = capture(() -> c.calibratePush(
                serverTime(1_700_000_000L + 30L * 3600L), mono.get()));
        assertFalse("输出后计时重置，30h 内不再输出：" + at30h,
                at30h.contains("时钟健康"));
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
}
