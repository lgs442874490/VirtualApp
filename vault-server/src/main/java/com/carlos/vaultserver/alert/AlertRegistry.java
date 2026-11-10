package com.carlos.vaultserver.alert;

/** 告警全局注册表：出口 + 去重（P3：观察点与战争通知共用同一把锁）。 */
public final class AlertRegistry {

    private static volatile AlertOutbox shared;
    private static final AlertDeduper A2_DEDUPER = new AlertDeduper(15L * 60_000L);
    /** A2 集结出征 30s 聚合：观察点与战争通知共用同一组窗口，避免各算各的。 */
    private static final AlertAggregator A2_AGG = new AlertAggregator(30_000L);
    /** 未装配出口（单测/未启用中枢）时的直通聚合器：永不聚合、永不补发。 */
    private static final AlertAggregator DISABLED_AGG = new AlertAggregator(0L);

    private AlertRegistry() {
    }

    public static void install(AlertOutbox outbox) {
        shared = outbox;
    }

    public static AlertOutbox shared() {
        return shared;
    }

    /**
     * A2 敌袭全局去重：同一 owner+marchId 只放行一次（观察点先扫到或推送流先到，先到先发）。
     * 不同攻击者/不同行军各自独立（a 打 b、c 打 b、d 打 b 都放行）。
     */
    public static boolean firstA2(long ownerUid, long marchId) {
        if (shared == null) return true;   // 未装配出口：不做跨引擎去重，保持旧单测行为
        return A2_DEDUPER.tryAcquire("a2:" + ownerUid + ":" + marchId, System.currentTimeMillis());
    }

    public static AlertAggregator a2Agg() {
        return shared == null ? DISABLED_AGG : A2_AGG;
    }

    public static void sweepDeduper(long now) {
        A2_DEDUPER.sweep(now);
    }
}
