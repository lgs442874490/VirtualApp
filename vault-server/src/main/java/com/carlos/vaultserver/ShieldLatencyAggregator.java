package com.carlos.vaultserver;

import java.util.Arrays;

/**
 * 开盾分段耗时聚合器（需求 R0-2 / 开发计划 S4）。
 *
 * <p>四段：{@code push→wake}、{@code wake→lock}、{@code lock→resp}、{@code push→resp}。
 * 每 <b>200 条</b>或每 <b>60 秒</b>（先到先输出）输出一条
 * {@code 盾时延 P50/P95/P99 ...}；{@code n<10} 只累计不输出（避免单样本噪声）。
 *
 * <p>线程模型：{@code synchronized} 内存计数 + 环形数组，**不新增线程、不做 IO**；
 * 由现有 1Hz 会话 tick 调 {@link #maybeFlush(long)}，满 200 条时由 {@link #add} 直接输出。
 * 进程重启计数清零（实例级状态）。
 */
final class ShieldLatencyAggregator {

    /** 单窗口样本上限（满即输出）。 */
    static final int CAPACITY = 200;
    /** 时间窗口（毫秒）。 */
    static final long FLUSH_INTERVAL_MS = 60_000L;
    /** 低于该样本数只累计不输出。 */
    static final int MIN_SAMPLES = 10;

    private final long[] pushWakeMs = new long[CAPACITY];
    private final long[] wakeLockMs = new long[CAPACITY];
    private final long[] lockRespMs = new long[CAPACITY];
    private final long[] totalMs = new long[CAPACITY];
    private int count;
    /** 当前窗口起点（首个样本时刻；-1=窗口为空）。 */
    private long windowStartMs = -1L;

    /**
     * 记一条样本。满 {@link #CAPACITY} 条立即输出并开启新窗口。
     *
     * @param nowMs 样本完成时刻（本机墙钟毫秒，用于窗口计时）
     */
    synchronized void add(long pushWake, long wakeLock, long lockResp, long total, long nowMs) {
        if (windowStartMs < 0L) windowStartMs = nowMs;
        pushWakeMs[count] = pushWake;
        wakeLockMs[count] = wakeLock;
        lockRespMs[count] = lockResp;
        totalMs[count] = total;
        count++;
        if (count >= CAPACITY) flush(nowMs);
    }

    /** 1Hz tick 调用：窗口内 ≥10 条且已满 60 秒时输出。 */
    synchronized void maybeFlush(long nowMs) {
        if (count >= MIN_SAMPLES && windowStartMs >= 0L
                && nowMs - windowStartMs >= FLUSH_INTERVAL_MS) {
            flush(nowMs);
        }
    }

    /** 当前窗口已累计样本数（单测/诊断用）。 */
    synchronized int pendingSamples() {
        return count;
    }

    private void flush(long nowMs) {
        int n = count;
        if (n <= 0) {
            windowStartMs = nowMs;
            return;
        }
        long[] pw = Arrays.copyOf(pushWakeMs, n);
        long[] wl = Arrays.copyOf(wakeLockMs, n);
        long[] lr = Arrays.copyOf(lockRespMs, n);
        long[] tt = Arrays.copyOf(totalMs, n);
        Arrays.sort(pw);
        Arrays.sort(wl);
        Arrays.sort(lr);
        Arrays.sort(tt);
        FileLog.info("盾时延 P50/P95/P99 pushWake={}/{}/{}ms wakeLock={}/{}/{}ms"
                        + " lockResp={}/{}/{}ms total={}/{}/{}ms n={}",
                String.valueOf(pct(pw, 50)), String.valueOf(pct(pw, 95)), String.valueOf(pct(pw, 99)),
                String.valueOf(pct(wl, 50)), String.valueOf(pct(wl, 95)), String.valueOf(pct(wl, 99)),
                String.valueOf(pct(lr, 50)), String.valueOf(pct(lr, 95)), String.valueOf(pct(lr, 99)),
                String.valueOf(pct(tt, 50)), String.valueOf(pct(tt, 95)), String.valueOf(pct(tt, 99)),
                String.valueOf(n));
        count = 0;
        windowStartMs = nowMs;
    }

    /** 最近秩百分位（已排序数组；p∈(0,100]）。 */
    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0L;
        int idx = (int) Math.ceil(sorted.length * (p / 100.0)) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }
}
