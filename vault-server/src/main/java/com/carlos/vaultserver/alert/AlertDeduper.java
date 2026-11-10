package com.carlos.vaultserver.alert;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局去重（设计 §10）：同一条行军（owner+marchId）在观察点/战争通知两条通道只产出一条告警。
 *
 * <p>键不含 fpid/sid：同一行军被多个账号会话看到也只放行一次。
 */
public final class AlertDeduper {

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMs;

    public AlertDeduper(long ttlMs) {
        this.ttlMs = Math.max(1L, ttlMs);
    }

    /** 返回 true=首次（放行）；false=TTL 内已出现（丢弃）。 */
    public boolean tryAcquire(String key, long now) {
        if (key == null || key.isEmpty()) return true;
        long[] prev = new long[1];
        seen.compute(key, (k, v) -> {
            if (v != null && now - v < ttlMs) {
                prev[0] = v;
                return v;
            }
            return now;
        });
        return prev[0] == 0L;
    }

    /** 清理过期键（懒清理，调用方按周期触发）。 */
    public void sweep(long now) {
        seen.entrySet().removeIf(e -> now - e.getValue() > ttlMs);
    }

    public int size() {
        return seen.size();
    }
}
