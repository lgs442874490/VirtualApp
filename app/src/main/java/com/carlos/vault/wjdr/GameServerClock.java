package com.carlos.vault.wjdr;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * 游戏服务器时钟（P0，守护盟友 M0 硬前置）——每 WjdrEngine 一份。
 *
 * 设计：design/游戏服务器时钟serverClock-立项设计.md + 守护盟友设计 §6.1。
 * 校准源：登录响应（loginauth/req_loaddata）、心跳响应——对响应内容做防御式深找
 * server_time 族字段；样本权按 RTT 取最优（近 5 分钟窗口），其余进误差界。
 * 外推：offset 挂在单调钟上（SystemClock.elapsedRealtime / 服务端 nanoTime，可注入），
 * 用户改系统时间/NTP 跳变不影响；漂移按 8μs/s 计入误差界。
 *
 * 全部时刻换算只经本类；相对等待用单调钟。禁止在调用方直接用 System.currentTimeMillis()
 * 表示服务器时间。
 */
public final class GameServerClock {

    /** 单调毫秒钟。Android 侧注入 SystemClock.elapsedRealtime（含深睡计数）；缺省 nanoTime。 */
    public interface Mono {
        long millis();
    }

    private static volatile Mono mono = () -> System.nanoTime() / 1_000_000L;

    /** Android 侧启动时注入（WjdrAndroid）；vault-server 不注入即用 nanoTime。 */
    public static void setMono(Mono m) {
        if (m != null) mono = m;
    }

    /** 当前单调毫秒（引擎侧打点用）。 */
    public static long monoNow() {
        return mono.millis();
    }

    /** 8μs/s 单调钟漂移上界（设计 §6.1）。 */
    private static final double DRIFT_PER_SEC = 0.008;
    /** 校准样本窗口。 */
    private static final long SAMPLE_WINDOW_MS = 5 * 60_000L;
    /** server_time 合法量级（秒域 ~1.7e9 / 毫秒域 ~1.7e12），过滤无关同名字段。 */
    private static final long SEC_MIN = 1_000_000_000L, SEC_MAX = 100_000_000_000L;

    private final Object lock = new Object();
    private volatile long offsetMs = 0L;          // serverMs − monoMs（当前最优样本）
    private volatile long errBoundMs = Long.MAX_VALUE / 4;
    private volatile long lastCalibMonoMs = 0L;
    private volatile long lastServerSec = 0L;
    private volatile boolean everCalibrated = false;

    // 窗口内最优样本（最小 RTT）与最大偏差
    private long bestRttMs = Long.MAX_VALUE;
    private long bestOffsetMs = 0L;
    private long windowStartMonoMs = 0L;

    // ------------------------------------------------------------------ 校准

    /**
     * 以一次"请求-响应"往返为样本校准。t0Mono/t1Mono 为请求前后单调毫秒；
     * sentLocalSec 为请求方随包携带的本地秒（心跳 time 字段），用于识别服务器原样回显。
     * 响应内深找 server_time 族字段；找不到=无操作（不打扰调用方）。
     */
    public void calibrate(Map<String, Object> content, long t0Mono, long t1Mono, long sentLocalSec) {
        if (content == null) return;
        long rtt = Math.max(0, t1Mono - t0Mono);
        long serverSec = deepFindServerSec(content, 0);
        if (serverSec < SEC_MIN || serverSec > SEC_MAX) return;
        // 心跳回显识别：响应时间与随包本地时间几乎一致 = 原样回显，不是服务器时间
        if (sentLocalSec > 0 && Math.abs(serverSec - sentLocalSec) <= 2) return;

        long serverMs = serverSec >= SEC_MAX / 10 ? serverSec : serverSec * 1000L;
        // 服务器打时间戳发生在 RTT 中段的期望位置
        long offset = serverMs - (t0Mono + rtt / 2);
        long nowMono = t1Mono;
        synchronized (lock) {
            if (!everCalibrated || nowMono - windowStartMonoMs > SAMPLE_WINDOW_MS) {
                bestRttMs = Long.MAX_VALUE;
                windowStartMonoMs = nowMono;
            }
            long maxDev = 0;
            if (rtt < bestRttMs) {
                // 换最优样本：旧最优降级为偏差贡献
                if (bestRttMs < Long.MAX_VALUE) {
                    maxDev = Math.abs(bestOffsetMs - offset);
                }
                bestRttMs = rtt;
                bestOffsetMs = offset;
            } else {
                maxDev = Math.abs(offset - bestOffsetMs);
            }
            double drift = DRIFT_PER_SEC * ((nowMono - windowStartMonoMs) / 1000.0);
            offsetMs = bestOffsetMs;
            errBoundMs = (long) Math.ceil(maxDev + drift) + (bestRttMs == Long.MAX_VALUE ? 0 : bestRttMs / 2);
            lastCalibMonoMs = nowMono;
            lastServerSec = serverSec;
            boolean first = !everCalibrated;
            everCalibrated = true;
            if (first) {
                WjdrLog.i("GameServerClock 首次校准成功 offset={}ms errBound={}ms rtt={}ms",
                        String.valueOf(offsetMs), String.valueOf(errBoundMs), String.valueOf(rtt));
            }
        }
    }

    /** 深找 server_time 族字段（秒/毫秒量级自动识别），找不到返回 -1。 */
    private static long deepFindServerSec(Object node, int depth) {
        if (node instanceof Map && depth <= 4) {
            for (Object e : ((Map<?, ?>) node).entrySet()) {
                Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
                String k = String.valueOf(en.getKey()).toLowerCase(Locale.ROOT);
                if (k.equals("server_time") || k.equals("servertime") || k.equals("server_ts")
                        || k.equals("server_timestamp") || k.equals("game_time")) {
                    long v = num(en.getValue());
                    if (v >= SEC_MIN && v <= SEC_MAX) return v;
                }
            }
            for (Object v : ((Map<?, ?>) node).values()) {
                long r = deepFindServerSec(v, depth + 1);
                if (r > 0) return r;
            }
        } else if (node instanceof List && depth <= 4) {
            for (Object v : (List<?>) node) {
                long r = deepFindServerSec(v, depth + 1);
                if (r > 0) return r;
            }
        }
        return -1;
    }

    private static long num(Object o) {
        if (o == null) return -1;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 读取

    /** 当前服务器毫秒（单调外推）。未校准时返回本机时间（调用方应以 locked() 区分）。 */
    public long nowServerMs() {
        return mono.millis() + offsetMs;
    }

    /** 服务器秒。 */
    public long nowServerSec() {
        return nowServerMs() / 1000L;
    }

    /** 是否已完成过至少一次校准。 */
    public boolean locked() {
        return everCalibrated;
    }

    /** offset 误差上界（毫秒）。 */
    public long errBoundMs() {
        return everCalibrated ? errBoundMs : Long.MAX_VALUE / 4;
    }

    /** 距上次成功校准的单调毫秒数（-1 = 从未校准）。 */
    public long sinceCalibMs() {
        return everCalibrated ? mono.millis() - lastCalibMonoMs : -1L;
    }

    public long lastServerSec() {
        return lastServerSec;
    }

    // ------------------------------------------------------------------ 对齐等待

    /**
     * 睡到指定服务器毫秒。>100ms 段用 sleep；≤100ms 收敛段用 parkNanos 自旋
     * （设计 §6.1：>100ms sleep / ≤100ms parkNanos）。
     */
    public void waitUntil(long serverEpochMs) throws InterruptedException {
        while (true) {
            long diff = serverEpochMs - nowServerMs();
            if (diff <= 0) return;
            if (diff > 100) {
                Thread.sleep(Math.min(diff - 50, 2000));
            } else {
                LockSupport.parkNanos(Math.max(1_000_000L, (diff - 2) * 1_000_000L));
                if (serverEpochMs - nowServerMs() <= 0) return;
            }
        }
    }

    /** 便捷重载：服务器秒。 */
    public void waitUntilSec(long serverEpochSec) throws InterruptedException {
        waitUntil(serverEpochSec * 1000L);
    }
}
