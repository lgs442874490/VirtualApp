package com.carlos.vaultserver.task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 情报轮次纯函数（2026-10-31 用户规则 1）。
 *
 * <p>规则（用户口径，唯一事实源）：
 * <ul>
 *   <li>每天 <b>00:00 / 08:00 / 16:00</b>（游戏服 GMT+8）各刷新一轮情报；</li>
 *   <li>每轮情报只存在 <b>15 小时</b>；</li>
 *   <li>上一轮没处理完，下一轮不会刷新（因此必须在本轮生存期内清完，漏清=损失下一轮）。</li>
 * </ul>
 *
 * <p>为什么用 epoch 秒取整：GMT+8 的 00:00/08:00/16:00 在 epoch 秒上正好是 28800（8h）的整数倍
 * （epoch 0 = 北京时间 08:00），所以 {@code sec / 28800 * 28800} 就是本轮开始、
 * {@code (sec/28800+1)*28800} 就是下一刷新点。事件轮次优先由权威字段 {@code expire_ts}
 * 反推（{@code roundStart = expireTs - 15h}，再就近吸附到 8h 边界），避免服务器时区/时钟误差。
 *
 * <p>纯函数、无状态，单测见 {@code IntelRoundTest}。
 */
final class IntelRound {

    /** 轮次间隔：8 小时。 */
    static final long ROUND_INTERVAL_SEC = 8L * 3600L;
    /** 单轮情报生存期：15 小时。 */
    static final long LIFETIME_SEC = 15L * 3600L;
    /** 采集让位窗口：情报剩余 ≤30 分钟时触发。 */
    static final long PREEMPT_WINDOW_SEC = 30L * 60L;

    /** 无事件时按刷新点唤醒的余量（毫秒，防刷新边界推送晚到）。 */
    static final long REFRESH_WAKE_GRACE_MILLIS = 5_000L;

    /** 游戏服/展示统一时区（与 {@link com.carlos.vaultserver.Beijing} 同口径）。 */
    private static final ZoneId ZONE = ZoneId.of("GMT+8");

    private IntelRound() {
    }

    /** 本轮开始时刻（epoch 秒）：把 now 向下取整到 00:00/08:00/16:00。 */
    static long currentRoundStartSec(long nowSec) {
        if (nowSec <= 0L) return 0L;
        return (nowSec / ROUND_INTERVAL_SEC) * ROUND_INTERVAL_SEC;
    }

    /** 下一刷新时刻（epoch 秒）：严格晚于 now。 */
    static long nextRefreshSec(long nowSec) {
        if (nowSec <= 0L) return 0L;
        return (nowSec / ROUND_INTERVAL_SEC + 1L) * ROUND_INTERVAL_SEC;
    }

    /** 无事件时下一次唤醒（epoch 毫秒）：下一刷新点 +5s。 */
    static long nextRefreshWakeMillis(long nowSec) {
        long next = nextRefreshSec(nowSec);
        return next <= 0L ? 0L : next * 1000L + REFRESH_WAKE_GRACE_MILLIS;
    }

    /**
     * 由事件过期时刻反推本轮开始：{@code expireTs - 15h} 后就近吸附到 8h 边界。
     * 尾部字段缺失/非法 → 0（调用方回退 {@link #currentRoundStartSec}）。
     */
    static long roundStartSecOfExpire(long expireTs) {
        if (expireTs <= 0L) return 0L;
        long raw = expireTs - LIFETIME_SEC;
        long snapped = Math.round((double) raw / (double) ROUND_INTERVAL_SEC) * ROUND_INTERVAL_SEC;
        return Math.max(0L, snapped);
    }

    /** 事件剩余秒（expireTs<=0 或已过期 → 0）。 */
    static long remainingSec(long nowSec, long expireTs) {
        if (expireTs <= 0L) return 0L;
        return Math.max(0L, expireTs - nowSec);
    }

    /** 是否已过期（expireTs<=0 视为未知/不过期）。 */
    static boolean expired(long nowSec, long expireTs) {
        return expireTs > 0L && expireTs <= nowSec;
    }

    /** 剩余时间是否已进入让位窗口（≤30min；已过期也算，调用方应先排除过期）。 */
    static boolean expiringSoon(long nowSec, long expireTs) {
        return expireTs > 0L && expireTs - nowSec <= PREEMPT_WINDOW_SEC;
    }

    /** 轮次文案：{@code "16:00 批次"}。 */
    static String roundLabel(long roundStartSec) {
        if (roundStartSec <= 0L) return "轮次未知";
        ZonedDateTime t = Instant.ofEpochSecond(roundStartSec).atZone(ZONE);
        return String.format(java.util.Locale.ROOT, "%02d:%02d 批次", t.getHour(), t.getMinute());
    }

    /** 剩余时长文案：{@code "4h12m"} / {@code "42m"} / {@code "0m"}。 */
    static String remainingLabel(long remainSec) {
        long sec = Math.max(0L, remainSec);
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        if (h > 0L) return h + "h" + m + "m";
        return m + "m";
    }

    /**
     * 情报任务下一次唤醒（纯函数，毫秒；0=不覆盖节奏间隔）。
     *
     * @param nowMillis          当前毫秒
     * @param nowSec             当前服务器秒
     * @param dispatched         本轮成功派队数
     * @param pendingDispatchable 仍有可派但本轮没派出去的事件数（队列/体力/编队门控）
     * @param preempted          本轮是否刚召回采集队腾位（需 15s 后重派）
     * @param eventsEmpty        当前是否有情报事件
     * @param dedupedOrInflight  仅剩去重窗内/战斗中事件（等结果推送即可）
     */
    static long intelWakeAt(long nowMillis, long nowSec, int dispatched, int pendingDispatchable,
                            boolean preempted, boolean eventsEmpty, boolean dedupedOrInflight) {
        if (nowMillis <= 0L) return 0L;
        if (preempted) return nowMillis + ServerFieldTasks.INTEL_PREEMPT_RETRY_DELAY_MILLIS;
        if (eventsEmpty || nowSec <= 0L) return nextRefreshWakeMillis(nowSec);
        if (pendingDispatchable > 0) return nowMillis + 60_000L;
        if (dispatched > 0) return nowMillis + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS;
        if (dedupedOrInflight) {
            // 去重窗 10 分钟：兜底不晚于“去重窗过期后 5s”，避免 15min 兜底把 10min 窗内
            // 本可重试的事件拖到过期；战斗中事件仍由结果推送提前唤醒。
            long dedupeWake = nowMillis + ServerFieldTasks.INTEL_DISPATCH_DEDUPE_MILLIS + 5_000L;
            return Math.min(nowMillis + ServerFieldTasks.INTEL_NEXT_WAKE_MILLIS, dedupeWake);
        }
        return nowMillis + 60_000L;
    }
}
