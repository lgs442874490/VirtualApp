package com.carlos.vaultserver.task;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 竞技场独占门（批次 B，2026-10-30）：某角色竞技场"还能继续打"期间，本角色其它自动化任务让位，
 * 避免抢队列/请求带宽；打完（剩余次数=0）、进入结算窗、关闭自动挑战或异常时释放。
 *
 * <p>参考网页版 {@code ArenaExclusiveGate}（per-role PREPARING/ACTIVE，板块级阻塞）+
 * {@code DailyArenaWindowCoordinator}（结算窗 23:10-24:00 release；canProgress && remaining&gt;0 →
 * ACTIVE；否则 release）。服务端没有独立巡视线程：由 {@link ServerTownTasks#arena} 每轮结束时
 * 根据"剩余场次/结算窗/开关"更新；{@link TaskCadenceScheduler} 与整轮执行器在派单前查询。
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>按角色 {@link RoleKey}(fpid,sid,uid) 隔离，兄弟角色不受影响（Phase 2 口径；uid&lt;=0 走旧单槽）；</li>
 *   <li>ACTIVE 有 TTL 兜底（默认 30 分钟，自动过期），PREPARING 2 分钟；过期查询即移除，不会永久卡死；</li>
 *   <li>白名单 {@link #ALLOWLIST} 永不被阻塞：arena（自身）/battle_shield/medic（安全关键）；</li>
 *   <li>{@code VAULT_PER_ROLE=off} 时角色 uid 可能为 0：仍按 (fpid,sid,0) 单实例生效，行为等同旧单角色。</li>
 * </ul>
 */
public final class ArenaGate {

    /** 竞技场任务 id（白名单，同时用于日志/统计文案）。 */
    public static final String ARENA_TASK_ID = "arena";

    /** 独占阶段：PREPARING=竞技场即将开始（本任务内短暂），ACTIVE=进行中（持续到打完/结算窗）。 */
    public enum Phase {
        PREPARING,
        ACTIVE
    }

    /** PREPARING 最长保持时间（防"准备中"卡死）。 */
    static final long PREPARING_TTL_MILLIS = 2 * 60_000L;
    /** ACTIVE 最长保持时间（防异常/任务未再跑导致全线停摆；打完正常路径会主动 release）。 */
    static final long ACTIVE_TTL_MILLIS = 30 * 60_000L;

    /** 永不被独占窗阻塞的任务：竞技场自身 + 安全关键（开盾/军医）。 */
    public static final Set<String> ALLOWLIST = Set.of(ARENA_TASK_ID, "battle_shield", "medic");

    private static final Map<RoleKey, Holding> HOLDINGS = new ConcurrentHashMap<>();

    private ArenaGate() {
    }

    /** 单角色门状态（不可变；更新时整体替换）。 */
    private static final class Holding {
        final Phase phase;
        final long untilMs;

        Holding(Phase phase, long untilMs) {
            this.phase = phase;
            this.untilMs = untilMs;
        }
    }

    // ---------------- 状态更新 ----------------

    /** 进入"准备中"（本角色竞技场即将开始；2 分钟自动过期）。 */
    public static void enterPreparing(RoleKey role) {
        enterPreparing(role, System.currentTimeMillis());
    }

    static void enterPreparing(RoleKey role, long nowMs) {
        enter(role, Phase.PREPARING, nowMs, PREPARING_TTL_MILLIS);
    }

    /** 进入"独占中"（有剩余场次且可推进；30 分钟自动过期，正常打完会 release）。 */
    public static void enterActive(RoleKey role) {
        enterActive(role, System.currentTimeMillis());
    }

    static void enterActive(RoleKey role, long nowMs) {
        enter(role, Phase.ACTIVE, nowMs, ACTIVE_TTL_MILLIS);
    }

    /** 释放本角色独占窗（打完/结算窗/关闭/异常）。 */
    public static void release(RoleKey role) {
        if (role == null || !role.valid()) return;
        HOLDINGS.remove(role);
    }

    private static void enter(RoleKey role, Phase phase, long nowMs, long ttlMillis) {
        if (role == null || !role.valid() || phase == null) return;
        HOLDINGS.put(role, new Holding(phase, nowMs + Math.max(1L, ttlMillis)));
    }

    // ---------------- 查询 ----------------

    /** 当前阶段（无/已过期 → null；过期项顺带移除）。 */
    public static Phase phase(RoleKey role) {
        return phase(role, System.currentTimeMillis());
    }

    static Phase phase(RoleKey role, long nowMs) {
        Holding h = live(role, nowMs);
        return h == null ? null : h.phase;
    }

    /** 是否处于独占窗（PREPARING/ACTIVE 且未过期）。 */
    public static boolean active(RoleKey role) {
        return phase(role) != null;
    }

    /**
     * 本任务是否被本角色的竞技场独占窗阻塞：白名单/arena 恒 false；无门/过期 false。
     *
     * @param role 角色（null/无效 → 不阻塞，保持旧行为）
     * @param fnId 任务 id（null → 不阻塞）
     */
    public static boolean blocks(RoleKey role, String fnId) {
        return blocks(role, fnId, System.currentTimeMillis());
    }

    static boolean blocks(RoleKey role, String fnId, long nowMs) {
        if (fnId == null || fnId.isEmpty()) return false;
        if (ALLOWLIST.contains(fnId)) return false;
        return live(role, nowMs) != null;
    }

    /** 取未过期门；过期自动移除。 */
    private static Holding live(RoleKey role, long nowMs) {
        if (role == null || !role.valid()) return null;
        Holding h = HOLDINGS.get(role);
        if (h == null) return null;
        if (nowMs >= h.untilMs) {
            HOLDINGS.remove(role, h);
            return null;
        }
        return h;
    }

    // ---------------- 清理（会话关闭/角色删除时由 SessionKeeper 调用） ----------------

    /** 清理本角色门（会话关闭/角色删除）。 */
    public static void clear(RoleKey role) {
        release(role);
    }

    /** 清理本角色门（便捷重载，供 SessionKeeper 直接调用）。 */
    public static void clear(String fpid, long sid, long uid) {
        clear(new RoleKey(fpid, sid, uid));
    }

    /** 清理全部（测试/进程级重置用）。 */
    public static void clearAll() {
        HOLDINGS.clear();
    }

    /** 当前持有门的角色数（观测/测试）。 */
    static int size() {
        return HOLDINGS.size();
    }

    /**
     * 安全清理：本角色竞技场任务未启用时，清掉可能残留的独占窗（防"关了竞技场但其它任务仍被挡"
     * 直到 TTL 过期）。启用口径与执行器一致：显式配置优先，缺省回退任务目录默认值。
     */
    public static void releaseIfArenaTaskDisabled(RoleKey role,
                                                  java.util.Map<String, Boolean> roleConfig) {
        if (role == null || !role.valid()) return;
        ServerTasks.TaskDef def = ServerTasks.byId(ARENA_TASK_ID);
        boolean enabled;
        if (roleConfig == null) {
            enabled = def != null && def.enabledByDefault;
        } else {
            Boolean explicit = roleConfig.get(ARENA_TASK_ID);
            enabled = explicit != null ? Boolean.TRUE.equals(explicit)
                    : def != null && def.enabledByDefault;
        }
        if (!enabled) release(role);
    }
}
