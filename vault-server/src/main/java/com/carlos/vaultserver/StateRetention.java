package com.carlos.vaultserver;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 状态保留（retention，2026-10-30 批次 A）：清理 StateStore 中日渐增长的按天键。
 *
 * <p>背景（生产 2026-10-30）：{@code state.json} 2008 键 / 2.4MB，其中 {@code st:<day>:…}
 * 按「天 × 角色 × 任务」累积（约 1100+ 键），json 只增不减；长期会拖慢 flush/拉取。
 *
 * <p>规则（全部有界、可回滚）：
 * <ul>
 *   <li>{@code st:<yyyy-MM-dd>:…} 保留最近 {@code VAULT_STATE_RETENTION_DAYS}（默认 7）天；</li>
 *   <li>{@code srv:cad:stats:<day>} / {@code srv:digest:<day>} 保留最近 30 天；</li>
 *   <li>只删「能解析出日期且早于截止日」的键；其它键（账号/角色/旧键）一律不动；</li>
 *   <li>开关：{@code VAULT_STATE_RETENTION=off} 关闭；删键即时标脏，由 StateStore 周期 flush 落盘。</li>
 * </ul>
 *
 * <p>线程模型：独立 daemon 线程，启动后先跑一次，之后每 6 小时一次；异常只记日志。
 */
public final class StateRetention {

    /** 保留天数（按天状态键）。 */
    static final String ENV_DAYS = "VAULT_STATE_RETENTION_DAYS";
    /** 总开关：off/0/false 关闭。 */
    static final String ENV_SWITCH = "VAULT_STATE_RETENTION";
    static final int DEFAULT_DAYS = 7;
    /** 统计/日报键保留天数。 */
    static final int STATS_KEEP_DAYS = 30;
    static final long INTERVAL_MILLIS = 6L * 3600_000L;

    private static final AtomicLong LAST_RUN_AT = new AtomicLong(0);
    private static volatile boolean started;

    private StateRetention() {
    }

    /** 启动守护线程（幂等；测试/回滚不调用即可）。 */
    public static void start(final StateStore store) {
        if (store == null || started) return;
        if (!enabled()) {
            FileLog.info("状态保留已关闭（{}）", ENV_SWITCH);
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    int removed = runOnce(store, System.currentTimeMillis());
                    FileLog.info("状态保留：清理 {} 个过期键（st 保留 {} 天）",
                            String.valueOf(removed), String.valueOf(days()));
                } catch (Throwable e) {
                    FileLog.warn("状态保留异常：{}", String.valueOf(e));
                }
                try {
                    Thread.sleep(INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "state-retention");
        t.setDaemon(true);
        t.start();
    }

    static boolean enabled() {
        String v = setting(ENV_SWITCH);
        return v == null || !("off".equalsIgnoreCase(v) || "0".equals(v)
                || "false".equalsIgnoreCase(v));
    }

    static int days() {
        String raw = setting(ENV_DAYS);
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_DAYS;
        try {
            int d = Integer.parseInt(raw.trim());
            return d <= 0 ? DEFAULT_DAYS : Math.min(d, 365);
        } catch (Throwable t) {
            return DEFAULT_DAYS;
        }
    }

    private static String setting(String key) {
        try {
            String p = System.getProperty(key);
            if (p != null) return p;
        } catch (Throwable ignored) {
        }
        try {
            return System.getenv(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 执行一次清理（包级可见便于单测）。返回删除键数。 */
    static int runOnce(StateStore store, long nowMillis) {
        if (store == null) return 0;
        LAST_RUN_AT.set(nowMillis);
        String today = day(nowMillis);
        String stCutoff = cutoffDay(nowMillis, days());
        String statCutoff = cutoffDay(nowMillis, STATS_KEEP_DAYS);
        List<String> stale = new ArrayList<>();
        for (String key : store.snapshot().keySet()) {
            if (key == null) continue;
            try {
                if (key.startsWith("st:")) {
                    String d = dayOf(key, 1);
                    if (isBefore(d, stCutoff)) stale.add(key);
                } else if (key.startsWith("srv:cad:stats:")) {
                    String d = dayOf(key, 3);
                    if (isBefore(d, statCutoff)) stale.add(key);
                } else if (key.startsWith("srv:digest:")) {
                    String d = dayOf(key, 2);
                    if (isBefore(d, statCutoff)) stale.add(key);
                }
            } catch (Throwable ignored) {
                // 单键异常不影响其它键
            }
        }
        int removed = 0;
        for (String k : stale) {
            try {
                store.remove(k);
                removed++;
            } catch (Throwable ignored) {
            }
        }
        return removed;
    }

    /** 取 key 中第 idx 段（按 ':' 切分）。 */
    static String dayOf(String key, int idx) {
        String[] p = key.split(":");
        return idx < p.length ? p[idx] : "";
    }

    /** 只比较形如 yyyy-MM-dd 的日期串；非法返回 false（保守不删）。 */
    static boolean isBefore(String day, String cutoff) {
        if (day == null || cutoff == null) return false;
        if (day.length() != 10 || cutoff.length() != 10) return false;
        if (day.charAt(4) != '-' || day.charAt(7) != '-') return false;
        return day.compareTo(cutoff) < 0;
    }

    /** 北京时间自然日 yyyy-MM-dd。 */
    static String day(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date(millis));
    }

    /** 截止日（含）：早于该日的键被清理。 */
    static String cutoffDay(long nowMillis, int keepDays) {
        return day(nowMillis - Math.max(1, keepDays) * 86_400_000L);
    }

    /** 最近一次清理时刻（0=从未）。 */
    public static long lastRunAt() {
        return LAST_RUN_AT.get();
    }

    /** 当前北京日（供调试/测试）。 */
    static String today(long nowMillis) {
        return day(nowMillis);
    }
}
