package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONObject;

/**
 * 批次 E：守护预留行军队列（采集让位）。
 *
 * <p>生产 2026-10-31 实证：守护已能命中 type=102，但采集占满 3 队 → 账本全是
 * {@code skip_queue free=0}。用户口径"驻防成功优先"，因此守护 enabled && !dry_run 时，
 * 采集的队列上限让出 {@code reserve_slots}（默认 1，clamp 0..2），并保证至少保留 1 队采集。
 *
 * <p>纯函数/无状态；配置与 {@link ServerGuardianEngine#parseConfig(JSONObject)} 同源，
 * 自动继承 params sub/strategy/顶层形状与 {@code VAULT_GUARDIAN_ENABLED/DRY_RUN} env 覆盖。
 */
public final class GuardianReserve {

    private GuardianReserve() {
    }

    /**
     * 当前角色应预留的世界行军队列数：
     * 守护关闭/演练(dry_run)/异常 → 0（零行为变化）；否则 params {@code reserve_slots}（默认 1，clamp 0..2）。
     */
    public static int reserveSlots(TaskContext tc) {
        if (tc == null) return 0;
        try {
            JSONObject guardian = tc.params(ServerGuardianEngine.FN_ID);
            ServerGuardianEngine.Config cfg = ServerGuardianEngine.parseConfig(guardian);
            if (cfg == null || !cfg.enabled || cfg.dryRun) return 0;
            return Math.max(0, Math.min(ServerGuardianEngine.MAX_RESERVE_SLOTS, cfg.reserveSlots));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 把预留从采集队列上限中扣除：{@code maxQueues<=0 → 0}；reserve 0 → 原值；
     * 否则 {@code max(1, maxQueues - reserve)}（下限保留 1 队采集，reserve 再大也不清零）。
     */
    public static int effectiveMaxQueues(int maxQueues, int reserve) {
        if (maxQueues <= 0) return 0;
        int r = Math.max(0, Math.min(ServerGuardianEngine.MAX_RESERVE_SLOTS, reserve));
        if (r <= 0) return maxQueues;
        return Math.max(1, maxQueues - r);
    }
}
