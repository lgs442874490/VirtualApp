package com.carlos.vaultserver.task;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 巨熊窗口让位门（2026-11-07）：开赛前 5 分钟到本场结束，暂停「会占行军队列」的任务，
 * 避免采集/狩猎/情报/巨兽/竞技场/活动出征等抢队列，导致打熊队与上车队英雄被占用（403）
 * 或队列已满（2878）。
 *
 * <p>与 {@link ArenaGate} 同构：按角色 {@link RoleKey} 隔离、内存态、窗口结束自动过期；
 * 由 {@link ServerBearTasks} 在开赛前 5 分钟进入、本场结束释放。
 * <ul>
 *   <li>只拦 {@link #QUEUE_TASKS} 中的队列类任务（用户 2026-11-07 口径：邮件/领奖等照常）；</li>
 *   <li>白名单 {@link #BEAR_TASK_ID} 恒不拦；</li>
 *   <li>TTL 兜底：窗口结束 + 1 分钟，防异常未释放导致长期停摆。</li>
 * </ul>
 */
public final class BearGate {

    /** 巨熊任务 id（恒不拦）。 */
    public static final String BEAR_TASK_ID = "bear";

    /** 窗口内需要让位的队列类任务（采集/狩猎/情报/巨兽/竞技场/守护/活动出征）。 */
    public static final Set<String> QUEUE_TASKS = Set.of(
            "gather", "hunt", "intel", "behemoth", "arena", "guardian",
            "act_feast", "act_mercenary", "act_truck", "act_icehunter");

    /** 窗口结束后的兜底余量（毫秒）。 */
    static final long TTL_MARGIN_MILLIS = 60_000L;

    private static final Map<RoleKey, Long> HOLDS = new ConcurrentHashMap<>();

    private BearGate() {
    }

    /** 进入本场窗口让位（untilMs=本场结束毫秒；<=0 时 30 分钟兜底）。 */
    public static void enter(RoleKey role, long untilMs) {
        enter(role, untilMs, System.currentTimeMillis());
    }

    static void enter(RoleKey role, long untilMs, long nowMs) {
        if (role == null || !role.valid()) return;
        long until = untilMs > 0L ? untilMs : nowMs + 30L * 60_000L;
        HOLDS.merge(role, until + TTL_MARGIN_MILLIS, Math::max);
    }

    /** 本场结束/异常时释放。 */
    public static void release(RoleKey role) {
        if (role != null) HOLDS.remove(role);
    }

    /** 是否处于巨熊窗口让位（已过期自动移除）。 */
    public static boolean active(RoleKey role) {
        return active(role, System.currentTimeMillis());
    }

    static boolean active(RoleKey role, long nowMs) {
        if (role == null || !role.valid()) return false;
        Long until = HOLDS.get(role);
        if (until == null) return false;
        if (until <= nowMs) {
            HOLDS.remove(role, until);
            return false;
        }
        return true;
    }

    /**
     * 本任务是否被巨熊窗口阻塞：只拦 {@link #QUEUE_TASKS}，巨熊自身/其它任务恒 false。
     *
     * @param role 角色（null/无效 → 不阻塞，保持旧行为）
     * @param fnId 任务 id
     */
    public static boolean blocks(RoleKey role, String fnId) {
        if (fnId == null || fnId.isEmpty()) return false;
        if (BEAR_TASK_ID.equals(fnId)) return false;
        if (!QUEUE_TASKS.contains(fnId)) return false;
        return active(role);
    }

    /** 当前让位截止毫秒（无/过期返回 0；单测用）。 */
    static long until(RoleKey role, long nowMs) {
        if (!active(role, nowMs)) return 0L;
        Long until = HOLDS.get(role);
        return until == null ? 0L : until;
    }

    /** 单测清理。 */
    static void clearAll() {
        HOLDS.clear();
    }
}
