package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器调度器（30 秒节拍，对齐 App DailySchedulerService 语义）：
 * - 每日整轮：auto 开启且进入时间窗的账号，在窗内随机时刻跑一轮（同日不重复；错过窗口立即补跑）；
 * - 补给到点补领：supply online_ts 到点只跑 supply 单功能；
 * - 手动触发：policy action=run 提交的请求。
 * 执行都经由 SessionKeeper 派发到该账号的会话线程（会话不在线则跳过本轮）。
 *
 * <p>R2 Phase 2（2026-10-29）：调度单位从「账号」升级为「角色」——
 * <ul>
 *   <li>内存状态（{@code nextRunAt}/{@code manualQueue}/{@code supplyFiredAt}）以
 *       {@link RoleKey#key()} 为键，同账号多角色互不覆盖；</li>
 *   <li>整轮最近执行 {@code srv:lastrun}、补给补领去重 {@code srv:supplyfire} 写角色键，
 *       旧键只由 <b>active 角色镜像写</b>（App 显示链路不变）；</li>
 *   <li>{@code VAULT_PER_ROLE=on} 时遍历账号下全部角色；否则（默认/off）只调度 active 角色，
 *       行为与 Phase 2 之前完全一致；</li>
 *   <li>{@link #requestRun(String, String)} 兼容旧 API（= active 角色），新增
 *       {@link #requestRun(RoleKey, String)} 按角色精确触发。</li>
 * </ul>
 */
public final class RoundScheduler {

    private static final long TICK_MILLIS = 30_000L;
    /** 窗内随机化上限：与 App 语义一致（时间窗内随机时刻），封顶 60 分钟分散负载。 */
    private static final long RANDOMIZE_CAP_MILLIS = 60 * 60_000L;
    /** R2 Phase 2 按角色调度开关：on/1/true = 全角色；缺省/off = 只 active 角色（现网行为）。 */
    public static final String ENV_PER_ROLE = "VAULT_PER_ROLE";

    private final VaultStore store;
    private final StateStore stateStore;
    private final KeeperFacade keeper;
    /** true=账号下全部角色各自调度；false=只 active 角色（行为与 Phase 2 前一致）。 */
    private final boolean perRole;

    /** 手动触发请求：fn 为空表示整轮；deadline 前允许等待会话登录。 */
    private static final class ManualRequest {
        final RoleKey role;
        final String fn;
        final String trigger;
        final long deadline;

        ManualRequest(RoleKey role, String fn, String trigger, long deadline) {
            this.role = role;
            this.fn = fn == null || fn.isEmpty() ? null : fn;
            this.trigger = trigger;
            this.deadline = deadline;
        }
    }

    private final Map<String, Long> nextRunAt = new ConcurrentHashMap<>();
    private final Map<String, ManualRequest> manualQueue = new ConcurrentHashMap<>();
    private final Map<String, Long> supplyFiredAt = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    /** SessionKeeper 的调度面（单测可注入假实现）。 */
    public interface KeeperFacade {
        /** 提交整轮（fn=null）或单任务到会话线程；返回是否受理（会话存在）。 */
        boolean submitRound(String fpid, String fn, String trigger);

        /** 账号当前会话的 sid（无会话返回 0）。 */
        long sessionSid(String fpid);

        /** 会话是否在线。 */
        boolean sessionOnline(String fpid);

        /** 确保账号有会话并开始登录（账号页“登录”按钮/手动触发用）。 */
        boolean ensureSession(String fpid);

        /** 该账号会话线程是否正在执行任务（整轮/单任务）；节奏调度据此避免排队堆积。 */
        default boolean executorBusy(String fpid) {
            return false;
        }

        /**
         * 会话进入“在线”状态的时刻（epoch millis；未知/离线返回 0）。
         * 节奏调度用它做登录初始化保护：刚上线的 20 秒内先不派任务，等服务端 init 推送（活动/宝箱/
         * 补给/联盟科技等）到达——生产实测建会话即开任务比推送早约 20 秒，会导致“未同步”误判。
         */
        default long sessionOnlineSince(String fpid) {
            return 0L;
        }

        /** P7 战时让位：该账号任一角色处于战争态 → 自动整轮延后（退出后窗口补跑；手动触发不受限）。 */
        default boolean wartimeYield(String fpid) {
            return false;
        }

        // ---------------- R2 Phase 2（2026-10-29）：按角色寻址 ----------------

        /** 该账号所有“期望在线”的角色（含离线/登录中，供调度器逐角色做在线判定）；无则空列表。 */
        default java.util.List<RoleKey> rolesOf(String fpid) {
            return java.util.Collections.emptyList();
        }

        /** 提交整轮/单任务到**指定角色**会话线程；角色会话不存在/未 RUNNING 返回 false（不再 fallback 兄弟角色）。 */
        default boolean submitRound(RoleKey role, String fn, String trigger) {
            return role != null && submitRound(role.fpid, fn, trigger);
        }

        /** 指定角色会话是否在线。 */
        default boolean sessionOnline(RoleKey role) {
            return role != null && sessionOnline(role.fpid);
        }

        /** 指定角色登录初始化就绪时刻（0=未知/离线）。 */
        default long sessionOnlineSince(RoleKey role) {
            return role == null ? 0L : sessionOnlineSince(role.fpid);
        }

        /** 指定角色会话线程是否忙。 */
        default boolean executorBusy(RoleKey role) {
            return role != null && executorBusy(role.fpid);
        }

        /** 指定角色是否处于战争让位态（按角色；不再账号级一刀切）。 */
        default boolean wartimeYield(RoleKey role) {
            return role == null || wartimeYield(role.fpid);
        }

        /** G7：按任务粒度的战时让位（默认退回整角色口径；SessionKeeper 覆盖为占队清单）。 */
        default boolean wartimeYield(RoleKey role, String fnId) {
            return wartimeYield(role);
        }

        /** 该账号当前 App 选中角色（activeUid 优先）；无会话返回 null。 */
        default RoleKey activeRole(String fpid) {
            return null;
        }
    }

    public RoundScheduler(VaultStore store, StateStore stateStore, KeeperFacade keeper) {
        this(store, stateStore, keeper, perRoleEnabled());
    }

    /** 单测/回滚可显式指定是否按全角色调度（不读环境变量）。 */
    RoundScheduler(VaultStore store, StateStore stateStore, KeeperFacade keeper, boolean perRole) {
        this.store = store;
        this.stateStore = stateStore;
        this.keeper = keeper;
        this.perRole = perRole;
    }

    /** {@code VAULT_PER_ROLE=on/1/true} → 账号下全部角色各自调度；缺省/off → 只 active 角色（现网行为）。 */
    public static boolean perRoleEnabled() {
        String v = System.getenv(ENV_PER_ROLE);
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        return "on".equals(v) || "1".equals(v) || "true".equals(v);
    }

    public void start() {
        Thread t = new Thread(() -> {
            while (!stopped) {
                try {
                    tick();
                } catch (Throwable e) {
                    FileLog.warn("调度节拍异常：{}", String.valueOf(e));
                }
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "round-scheduler");
        t.setDaemon(true);
        t.start();
        FileLog.info("整轮调度器已启动（30s 节拍，perRole={}）", String.valueOf(perRole));
    }

    public void stop() {
        stopped = true;
    }

    /** 手动触发入口（policy/automation action=run，旧 API）：提交到该账号 active 角色。 */
    public boolean requestRun(String fpid, String fn) {
        if (store.find(fpid) == null) return false;
        RoleKey role = keeper.activeRole(fpid);
        if (role == null || !role.valid()) {
            // 旧数据/尚未登录落地：保持旧账号级语义（uid=0 角色键退化为旧键）。
            role = new RoleKey(fpid, keeper.sessionSid(fpid), 0L);
        }
        return requestRun(role, fn);
    }

    /** 手动触发入口（R2 Phase 2）：精确提交到指定角色；请求会等待该角色会话登录，最多 5 分钟。 */
    public boolean requestRun(RoleKey role, String fn) {
        if (role == null || store.find(role.fpid) == null) return false;
        manualQueue.put(role.key(), new ManualRequest(role, fn, "手动",
                System.currentTimeMillis() + 5 * 60_000L));
        return true;
    }

    void tick() {
        long now = System.currentTimeMillis();
        String today = com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd").format(new Date());
        // 1) 手动触发（最高优先级）：会话未在线则继续排队并推动登录，超时才丢弃
        for (Map.Entry<String, ManualRequest> e : manualQueue.entrySet()) {
            ManualRequest req = e.getValue();
            RoleKey role = req.role;
            if (keeper.sessionOnline(role)) {
                manualQueue.remove(e.getKey(), req);
                if (keeper.submitRound(role, req.fn, req.trigger)) {
                    FileLog.info("手动整轮启动 role={} fn={}", role.key(), String.valueOf(req.fn));
                } else {
                    FileLog.warn("手动整轮未受理 role={}（会话不可用）", role.key());
                }
            } else if (now > req.deadline) {
                manualQueue.remove(e.getKey(), req);
                FileLog.warn("手动整轮超时 role={}（5 分钟内会话未上线）", role.key());
            } else {
                keeper.ensureSession(role.fpid);
                FileLog.info("手动整轮等待会话 role={} fn={}", role.key(), String.valueOf(req.fn));
            }
        }
        for (VaultStore.Account acc : store.snapshot()) {
            if (acc.token == null || acc.token.isEmpty()) continue;
            RoleKey active = keeper.activeRole(acc.fpid);
            for (RoleKey role : rolesFor(acc)) {
                boolean isActive = active != null && active.key().equals(role.key());
                // 2) 每日整轮（auto + 时间窗；P7 战时让位：战争态不排不发，退出后错过窗口立即补跑）
                dailyRound(acc, role, isActive, now, today);
                // 3) 补给到点补领（只跑 supply 单功能；成功触发后按新一轮 online_ts 重新武装）
                supplyTick(acc, role, isActive, now);
            }
        }
    }

    /**
     * 该账号本次 tick 需要调度的角色：
     * <ul>
     *   <li>perRole：{@link KeeperFacade#rolesOf} 全部角色（含离线，供整轮排期），active 缺失时补入；</li>
     *   <li>非 perRole：仅 active 角色（现网行为）；</li>
     *   <li>旧实现（未实现 v2 接口、rolesOf 空 + activeRole null）：回退 {@code RoleKey(fpid,sid,0)}，
     *       key/状态键全部退化为旧账号键，行为不变。</li>
     * </ul>
     */
    private List<RoleKey> rolesFor(VaultStore.Account acc) {
        List<RoleKey> roles = new ArrayList<>(3);
        if (perRole) {
            for (RoleKey r : keeper.rolesOf(acc.fpid)) {
                if (r == null || !r.valid()) continue;
                if (!containsRole(roles, r)) roles.add(r);
            }
        }
        RoleKey active = keeper.activeRole(acc.fpid);
        if (active != null && active.valid() && (!perRole || !containsRole(roles, active))) {
            roles.add(active);
        }
        if (roles.isEmpty()) {
            // 旧实现/登录未落地：uid=0 账号级角色（sid 未知也保留旧排期语义，仅在会话在线时派单）。
            roles.add(new RoleKey(acc.fpid, keeper.sessionSid(acc.fpid), 0L));
        }
        return roles;
    }

    private static boolean containsRole(List<RoleKey> roles, RoleKey role) {
        for (RoleKey r : roles) {
            if (r.key().equals(role.key())) return true;
        }
        return false;
    }

    private void dailyRound(VaultStore.Account acc, RoleKey role, boolean active,
                            long now, String today) {
        // uid>0 必须有 sid（角色状态键含 sid）；uid=0 旧账号级排期不依赖 sid（与现网一致）。
        if (role == null || (role.roleScoped() && role.sid <= 0L)) return;
        if (!acc.auto || keeper.wartimeYield(role)) return;
        long lastRun = lastRunOf(role, active);
        boolean ranToday = lastRun > 0 && com.carlos.vaultserver.Beijing.fmt("yyyy-MM-dd")
                .format(new Date(lastRun)).equals(today);
        if (ranToday) {
            nextRunAt.remove(role.key());
            return;
        }
        long windowStart = todayAt(acc.windowStart);
        long windowEnd = todayAt(acc.windowEnd);
        Long planned = nextRunAt.get(role.key());
        if (planned == null) {
            // 首次入窗：在窗内随机取一时刻（错过窗口则立即补跑）
            long plannedAt;
            if (now >= windowEnd) {
                plannedAt = now;                       // 错过今日窗口 → 立即补跑
            } else if (now < windowStart) {
                plannedAt = -1L;                        // 未到窗口，先不排
            } else {
                long span = Math.min(windowEnd - now, RANDOMIZE_CAP_MILLIS);
                plannedAt = now + (long) (Math.random() * Math.max(span, 1));
            }
            if (plannedAt >= 0) {
                nextRunAt.put(role.key(), plannedAt);
                FileLog.info("整轮已排 role={} 予定={}", role.key(),
                        com.carlos.vaultserver.Beijing.fmt("HH:mm:ss").format(new Date(plannedAt)));
            }
        } else if (now >= planned && keeper.sessionOnline(role)) {
            nextRunAt.remove(role.key());
            if (keeper.submitRound(role, null, "定时")) {
                FileLog.info("定时整轮启动 role={}", role.key());
            }
        }
    }

    private void supplyTick(VaultStore.Account acc, RoleKey role, boolean active, long now) {
        if (role == null || role.sid <= 0L) return;
        if (!keeper.sessionOnline(role)) return;
        long wake = RoundRunner.supplyWakeAt(stateStore, role, active);
        if (wake <= 0 || now < wake || now - wake >= 30 * 60_000L) return;
        Long fired = supplyFiredAt.get(role.key());
        long cur = supplyFiredOf(role, active);
        if (fired == null || fired != wake) {
            supplyFiredAt.put(role.key(), wake);
            stateStore.putLong(RoundRunner.supplyFiredKey(role), wake);
            if (active && role.roleScoped()) {
                stateStore.putLong(RoundRunner.supplyFiredLegacyKey(role), wake);
            }
            if (keeper.submitRound(role, "supply", "定时")) {
                FileLog.info("补给补领启动 role={}", role.key());
            }
        } else if (cur == wake) {
            // 30 分钟仍未变（补领失败/不可领）：放弃本窗口等待下一轮采集刷新
            supplyFiredAt.remove(role.key());
            stateStore.putLong(RoundRunner.supplyFiredKey(role), 0L);
            if (active && role.roleScoped()) {
                stateStore.putLong(RoundRunner.supplyFiredLegacyKey(role), 0L);
            }
        }
    }

    /** 整轮最近执行：读角色键；active 角色 miss 时回退旧账号键（迁移兼容）。 */
    private long lastRunOf(RoleKey role, boolean active) {
        long v = longOf(RoundRunner.lastRunKey(role));
        if (v == 0L && active && role.roleScoped()) v = longOf(RoundRunner.lastRunLegacyKey(role));
        return v;
    }

    /** 补给补领去重时刻：读角色键；active 角色 miss 时回退旧账号键（迁移兼容）。 */
    private long supplyFiredOf(RoleKey role, boolean active) {
        long v = longOf(RoundRunner.supplyFiredKey(role));
        if (v == 0L && active && role.roleScoped()) v = longOf(RoundRunner.supplyFiredLegacyKey(role));
        return v;
    }

    private long longOf(String key) {
        Object v = stateStore.snapshot().get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private long todayAt(int hour) {
        java.util.Calendar c = java.util.Calendar.getInstance(com.carlos.vaultserver.Beijing.TZ);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
