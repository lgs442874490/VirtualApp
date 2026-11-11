package com.carlos.vault.daily;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 侦查类推送配额（用户 2026-11-01 硬规则）：滑动 5 分钟窗口内，同一个敌人只推送前 3 次侦查。
 *
 * <p>本类是 App 告警中枢与 vault-server（{@code WarNotify}）<b>同源共用</b>
 * 的唯一口径实现（vault-server 的 sourceSets 直接编译本文件，与 RewardText/WarState 同例），
 * 改口径只改这里：
 * <ul>
 *   <li>计数维度 = 敌人 uid（不是监视会话/账号）：marchId 已全局去重，一次侦查只记一次账；</li>
 *   <li>窗口滑动：{@link #WINDOW_MS} 内最多 {@link #MAX_PUSHES} 次；被压制的侦查不记账、
 *       不顺延窗口，最早一笔推满窗口后自然恢复；</li>
 *   <li>只作用于侦查（march type=1）的首次推送；被压制行军的后续「加速/解除/到达」也不再推送
 *       （否则是无上下文的孤立通知）。进攻/集结/出征不走本配额。</li>
 * </ul>
 *
 * 纯 JVM 类（不 import android），可被纯 JVM 单测覆盖。
 */
public final class ScoutPushQuota {

    /** 滑动窗口（毫秒）：5 分钟。 */
    public static final long WINDOW_MS = 5 * 60_000L;
    /** 窗口内每个敌人最多推送次数：前 3 次。 */
    public static final int MAX_PUSHES = 3;

    /** 敌人 uid → 已推送时刻（毫秒）队列（只保留窗口内的记录）。 */
    private final ConcurrentHashMap<Long, ConcurrentLinkedDeque<Long>> pushedAt =
            new ConcurrentHashMap<>();

    /**
     * 仍有配额则记账并返回 true；超限返回 false（不记账）。
     *
     * @param enemyUid  敌人 uid（&lt;=0 的异常数据不设限）
     * @param nowMillis 当前时刻（毫秒；测试可注入固定时钟）
     */
    public boolean allow(long enemyUid, long nowMillis) {
        if (enemyUid <= 0L) return true;
        ConcurrentLinkedDeque<Long> at = pushedAt.computeIfAbsent(enemyUid,
                k -> new ConcurrentLinkedDeque<>());
        synchronized (at) {
            while (!at.isEmpty() && nowMillis - at.peekFirst() >= WINDOW_MS) at.pollFirst();
            if (at.size() >= MAX_PUSHES) return false;
            at.addLast(nowMillis);
            return true;
        }
    }

    /** 清理窗口外记录与空键（挂在推送模块的周期 sweep 上，防长期运行无界增长）。 */
    public void sweep(long nowMillis) {
        for (Map.Entry<Long, ConcurrentLinkedDeque<Long>> e : pushedAt.entrySet()) {
            ConcurrentLinkedDeque<Long> at = e.getValue();
            at.removeIf(t -> nowMillis - t >= WINDOW_MS);
            if (at.isEmpty()) pushedAt.remove(e.getKey(), at);
        }
    }

    /** 已记账敌人数（测试/诊断）。 */
    public int trackedEnemies() {
        return pushedAt.size();
    }
}
