package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.automation.ActionDefinition;
import com.carlos.vaultserver.automation.AutomationConfig;
import com.carlos.vaultserver.automation.AutomationModule;
import com.carlos.vaultserver.automation.ModuleConfig;
import com.carlos.vaultserver.automation.ModuleRegistry;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务节奏调度器：把网页版 wjdr-tool 的「每任务独立间隔 + 持续执行」语义复刻到服务器。
 *
 * <p>背景（长期缺陷）：{@link RoundScheduler} 对每个账号每天只在 05:00-09:00 窗内跑
 * <b>一轮</b>整轮，窗外的挂机（10 分钟一领）/补给（倒计时到点）/活动奖励（活动开放即领）/
 * 联盟宝箱（随时进箱）/竞技场/采集/情报/巨熊窗口等全部错过——用户反馈的
 * 「挂了自动任务但好多奖励没有自动领」根因即此。网页版由 {@code TaskEngine} 按
 * {@code AbstractDailyAutomationTaskDefinition.forTask(taskKey).intervalSeconds()}
 * 给每个任务独立 wakeAt，并叠加通知唤醒，全天持续执行。
 *
 * <p>本调度器：
 * <ol>
 *   <li>每个 tick（2s）扫描 auto=true 且会话在线的调度单元，先做闸门/到期判定得到候选；</li>
 *   <li>候选按 {@link #pickNextAccount 账号 fpid 字典序 + round-robin 游标}（Phase 2 起为
 *       {@link #pickNextRole 角色 fpid|sid|uid round-robin + 每角色保底 floor}）公平选一个
 *       （同 tick 只派 1 单，避免遍历顺序导致某账号/角色饿死）；</li>
 *   <li>全局每分钟派单预算 {@code VAULT_CADENCE_GLOBAL_MAX}（默认 20；off/0=不限）用 60s
 *       滑动窗口限流，超预算本 tick 不派（不写 cad 键，下 tick 继续）；Phase 2 另加账号级
 *       {@code VAULT_CADENCE_ACCOUNT_MAX}（默认 24/分）防同账号双角色把预算挤爆；</li>
 *   <li>把命中的任务提交单任务执行（{@link RoundScheduler.KeeperFacade#submitRound(RoleKey, String, String)}，
 *       进入会话单线程执行器，天然与整轮/手动串行），并按 {@link TaskCadence} 写下次执行时间
 *       （>60s 的间隔加 ±10% 抖动；上轮 SKIPPED/ERROR 按 {@link #nextIntervalForResult} 退避）；</li>
 *   <li>派单统计写 {@code srv:cad:stats:{yyyy-MM-dd}}（JSON 文本，键型见 {@link #STATS_KEY_PREFIX}，
 *       Phase 2 增 {@code byRole/skipByRole}，保留 {@code byAccount}），供 DailyDigest/HealthMonitor 消费。</li>
 * </ol>
 *
 * <p>Phase 2（2026-10-29）按角色调度：
 * <ul>
 *   <li>调度单元 = {@link RoleKey}(fpid,sid,uid)：同账号同服两个角色各自 cadence/退避/最小间隔；
 *       状态键经 {@link StateKeys} 构造为 {@code srv:cad:<fpid>:<sid>:<uid>:<fn>} /
 *       {@code st:<day>:<fpid>:<sid>:<uid>:<fn>} / {@code srv:cadlast:<fpid>:<sid>:<uid>}；</li>
 *   <li>兼容：角色键 miss 时读旧键回退一次；active 角色额外镜像写旧键
 *       {@code srv:cad:<fpid>:<sid>:<fn>} 与 {@code srv:cadlast:<fpid>}（旧 App/回滚可读）；</li>
 *   <li>灰度：{@code VAULT_PER_ROLE=off|on}（默认 off=只调 active 角色，行为与现网一致）；</li>
 *   <li>公平：每角色保底 {@code floor = max(4, globalMax / max(1, 在线角色数))}，本 tick 优先
 *       派给「最近 60s 派单数低于 floor」的角色；连续未派单次数用于同分平局轮转。</li>
 * </ul>
 *
 * <p>安全约束：同一角色两次提交至少间隔 {@link #MIN_DISPATCH_GAP_MILLIS}；
 * 会话线程忙（整轮/单任务执行中）时不提交，避免排队堆积；战时让位（P7）时不提交；
 * 暂停（PAUSED）/离线/未开启自动的账号不提交。默认全天生效（= 网页版默认语义，
 * 其“禁运行时段”默认关闭）；如服务器要保留原 05:00-09:00 时间窗，设环境变量
 * {@code VAULT_CADENCE_WINDOW=1}。整体回滚：{@code VAULT_TASK_CADENCE=off}。
 */
public final class TaskCadenceScheduler {

    /** 扫描节拍。 */
    static final long TICK_MILLIS = 2_000L;
    /**
     * 同一角色两次提交的最小间隔（模拟人手停顿，防请求突发）。
     *
     * <p>生产观察（2026-10-23 12:28–12:33）：3 秒间隔在 40+ 默认任务下被 15-30 秒级任务打满，
     * 约 19 轮/分钟。收紧到 6 秒（≤10 轮/分钟）并与 60 秒任务下限配合，降低游戏端请求密度。
     */
    static final long MIN_DISPATCH_GAP_MILLIS = 6_000L;
    /** 会话忙/未受理时的重试间隔。 */
    static final long RETRY_MILLIS = 60_000L;
    /**
     * 登录初始化保护（C4）：会话刚上线的前 20 秒不派任务。生产实测建会话即开跑比服务端
     * 关键 init 推送早约 20 秒（活动/宝箱/补给/联盟科技），过早评估会把“尚未推送”误判成
     * “未同步/未开放”并写进状态与台账。
     */
    static final long INIT_GRACE_MILLIS = 20_000L;
    /** 上轮“已检查无可领”（st=4）的退避倍数与下限：×3 且 ≥180s。 */
    static final long SKIP_BACKOFF_MULTIPLIER = 3L;
    static final long SKIP_BACKOFF_MIN_MILLIS = 180_000L;
    /** 上轮异常（st=3）的退避倍数与下限：×2 且 ≥120s。 */
    static final long ERROR_BACKOFF_MULTIPLIER = 2L;
    static final long ERROR_BACKOFF_MIN_MILLIS = 120_000L;
    /** 全局预算滑动窗口（毫秒）：统计最近 60 秒成功派单数。 */
    static final long BUDGET_WINDOW_MILLIS = 60_000L;
    /** 全局每分钟派单预算默认值（环境变量未配置时；0=不限）。 */
    static final int DEFAULT_GLOBAL_MAX_PER_MINUTE = 20;
    /** Phase 2：账号级每分钟派单上限默认值（防同账号双角色把请求打爆；0=不限）。 */
    static final int DEFAULT_ACCOUNT_MAX_PER_MINUTE = 24;
    /** Phase 2：每角色每分钟保底派单数（不足优先派，避免兄弟角色饿死）。 */
    static final int MIN_ROLE_FLOOR_PER_MINUTE = 4;
    /** 统计落盘最小间隔；有派单时立即落盘（skip 原因等低频计数按该间隔合并写）。 */
    static final long STATS_PUBLISH_MILLIS = 10_000L;
    /** Phase 2 灰度开关：on=全角色独立调度；off=只调 active 角色（旧行为，默认）。 */
    static final String ENV_PER_ROLE = "VAULT_PER_ROLE";
    /** Phase 2：账号级每分钟派单上限（默认 24；off/0=不限）。 */
    static final String ENV_ACCOUNT_MAX = "VAULT_CADENCE_ACCOUNT_MAX";
    /**
     * 派单统计键：{@code srv:cad:stats:{yyyy-MM-dd}}（北京时间日期；值为 JSON 文本）。
     *
     * <p><b>键型契约（供 DailyDigest / HealthMonitor / 看板消费，勿随意改名）</b>：
     * <pre>{@code
     * {
     *   "date": "2026-10-23",              // 统计日期（北京时间）
     *   "dispatch": 123,                   // 当日成功进入节奏提交的派单数（= 游戏请求轮次数）
     *   "byAccount": {"48460403": 80, ...},// 当日按账号分布（保留）
     *   "byRole": {"48460403|888|35371154": 40, ...}, // Phase 2：按角色分布
     *   "skip": {"min_gap": 900, ...},     // 当日各闸门拒绝次数（每 tick 每账号只记首个命中原因）
     *   "skipByRole": {"48460403|888|…": {"min_gap": 3}}, // Phase 2：按角色跳过原因（可选）
     *   "globalMax": 20,                   // 全局每分钟预算（0=不限）
     *   "accountMax": 24,                  // Phase 2：账号级每分钟上限（0=不限）
     *   "minuteDispatch": 7,               // 当前 60s 滑动窗口内派单数
     *   "firstAt": 1792...,                // 当日统计起点（epoch millis）
     *   "updatedAt": 1792...               // 本次落盘时刻（epoch millis）
     * }
     * }</pre>
     * skip 原因取值：{@code no_token/auto_off/offline/init_grace/no_sid/wartime/window/busy/
     * min_gap/no_task/no_due/unaccepted/global_budget/account_budget/error}。
     */
    static final String STATS_KEY_PREFIX = "srv:cad:stats:";
    /** 慢任务（间隔 ≥ 该值）提交时打 info 日志，快任务靠单任务轮次日志观测。 */
    private static final long LOG_INTERVAL_THRESHOLD_SECONDS = 300L;

    private final VaultStore store;
    private final StateStore stateStore;
    private final RoundScheduler.KeeperFacade keeper;
    private final ModuleRegistry registry;
    private final boolean respectWindow;
    private final Random random;
    /** 全局每分钟派单预算（0=不限）。 */
    private final int globalMaxPerMinute;
    /** Phase 2：是否按角色独立调度（false=旧 active 单角色行为）。 */
    private final boolean perRoleEnabled;
    /** Phase 2：账号级每分钟派单上限（0=不限）。 */
    private final int accountMaxPerMinute;
    private final Set<String> startedLog = ConcurrentHashMap.newKeySet();
    private final Set<String> busyWarned = ConcurrentHashMap.newKeySet();
    /** 内存派单时刻（role key → ms；state 缺失时兜底最小间隔）。 */
    private final Map<String, Long> lastDispatchInMemory = new ConcurrentHashMap<>();
    /** 60s 滑动窗口：最近成功提交的派单时刻（毫秒，升序；仅 tick 线程读写）。 */
    private final ArrayDeque<Long> dispatchWindow = new ArrayDeque<>();
    /** Phase 2：每角色最近派单时刻窗口（role key → 60s 内派单时刻；仅 tick 线程读写）。 */
    private final Map<String, ArrayDeque<Long>> roleDispatchWindow = new LinkedHashMap<>();
    /** Phase 2：每账号最近派单时刻窗口（fpid → 60s 内派单时刻；仅 tick 线程读写）。 */
    private final Map<String, ArrayDeque<Long>> accountDispatchWindow = new LinkedHashMap<>();
    /** Phase 2：角色连续“有候选但未被选中”的 tick 数（公平平局用；仅 tick 线程读写）。 */
    private final Map<String, Integer> consecutiveMisses = new LinkedHashMap<>();
    /** round-robin 游标：上一 tick 选中的账号 fpid（Phase 2 on 模式为 role key）。 */
    private String pickCursor;
    /** 当日派单统计（内存计数，定期落盘；仅 tick 线程读写）。 */
    private final CadenceStats stats = new CadenceStats();
    /** 上次统计落盘时刻（0=尚未落盘）。 */
    private long lastStatsPublishAt;
    private volatile boolean stopped;

    public TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                                RoundScheduler.KeeperFacade keeper) {
        this(store, stateStore, keeper, defaultRegistry(), windowEnabledByEnv(), new Random(),
                globalMaxByEnv(), perRoleEnabledByEnv(), accountMaxByEnv());
    }

    /** 可注入构造（单测：固定 registry / 窗口模式 / 随机种子；全局预算取环境变量）。 */
    TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                         RoundScheduler.KeeperFacade keeper, ModuleRegistry registry,
                         boolean respectWindow, Random random) {
        this(store, stateStore, keeper, registry, respectWindow, random,
                globalMaxByEnv(), perRoleEnabledByEnv(), accountMaxByEnv());
    }

    /** 可注入构造（单测：额外固定全局每分钟预算，0=不限）。 */
    TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                         RoundScheduler.KeeperFacade keeper, ModuleRegistry registry,
                         boolean respectWindow, Random random, int globalMaxPerMinute) {
        this(store, stateStore, keeper, registry, respectWindow, random,
                globalMaxPerMinute, perRoleEnabledByEnv(), accountMaxByEnv());
    }

    /** Phase 2 完整注入（单测/灰度：perRole + 账号级预算显式指定）。 */
    TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                         RoundScheduler.KeeperFacade keeper, ModuleRegistry registry,
                         boolean respectWindow, Random random, int globalMaxPerMinute,
                         boolean perRoleEnabled, int accountMaxPerMinute) {
        this.store = store;
        this.stateStore = stateStore;
        this.keeper = keeper;
        this.registry = registry;
        this.respectWindow = respectWindow;
        this.random = random;
        this.globalMaxPerMinute = Math.max(0, globalMaxPerMinute);
        this.perRoleEnabled = perRoleEnabled;
        this.accountMaxPerMinute = Math.max(0, accountMaxPerMinute);
    }

    private static ModuleRegistry defaultRegistry() {
        ModuleRegistry r = Modules.registry();
        r.validate();
        return r;
    }

    /** 环境变量总开关：{@code VAULT_TASK_CADENCE=off} 时不启动（回滚每天一轮）。 */
    public static boolean disabledByEnv() {
        String v = System.getenv("VAULT_TASK_CADENCE");
        return v != null && ("off".equalsIgnoreCase(v.trim())
                || "0".equals(v.trim()) || "false".equalsIgnoreCase(v.trim()));
    }

    private static boolean windowEnabledByEnv() {
        String v = System.getenv("VAULT_CADENCE_WINDOW");
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }

    /** 全局每分钟派单预算（环境变量 {@code VAULT_CADENCE_GLOBAL_MAX}；未配置=默认 20）。 */
    static int globalMaxByEnv() {
        return parseGlobalMax(System.getenv("VAULT_CADENCE_GLOBAL_MAX"));
    }

    /**
     * 解析全局预算配置（纯函数）：null/空/非法 → {@link #DEFAULT_GLOBAL_MAX_PER_MINUTE}；
     * off/false/0/负数 → 0（不限）；正数 → 该值（次/分钟）。
     */
    static int parseGlobalMax(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_GLOBAL_MAX_PER_MINUTE;
        String v = raw.trim();
        if ("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return 0;
        try {
            int n = Integer.parseInt(v);
            return n <= 0 ? 0 : n;
        } catch (NumberFormatException e) {
            return DEFAULT_GLOBAL_MAX_PER_MINUTE;
        }
    }

    /** Phase 2 灰度开关（环境变量 {@code VAULT_PER_ROLE}；未配置=off 旧行为）。 */
    static boolean perRoleEnabledByEnv() {
        return parsePerRole(System.getenv(ENV_PER_ROLE));
    }

    /** 解析按角色开关（纯函数）：on/true/1 → true；其它（含 null/off/false）→ false。 */
    static boolean parsePerRole(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        return "on".equalsIgnoreCase(v) || "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /** Phase 2 账号级预算（环境变量 {@code VAULT_CADENCE_ACCOUNT_MAX}；未配置=默认 24）。 */
    static int accountMaxByEnv() {
        return parseAccountMax(System.getenv(ENV_ACCOUNT_MAX));
    }

    /**
     * 解析账号级预算（纯函数）：null/空/非法 → {@link #DEFAULT_ACCOUNT_MAX_PER_MINUTE}；
     * off/false/0/负数 → 0（不限）；正数 → 该值（次/分钟）。
     */
    static int parseAccountMax(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_ACCOUNT_MAX_PER_MINUTE;
        String v = raw.trim();
        if ("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return 0;
        try {
            int n = Integer.parseInt(v);
            return n <= 0 ? 0 : n;
        } catch (NumberFormatException e) {
            return DEFAULT_ACCOUNT_MAX_PER_MINUTE;
        }
    }

    /** Phase 2：当前是否按角色调度（测试/观测用）。 */
    boolean perRoleEnabled() {
        return perRoleEnabled;
    }

    /** Phase 2：账号级每分钟上限（0=不限；测试/观测用）。 */
    int accountMaxPerMinute() {
        return accountMaxPerMinute;
    }

    /** 启动后台线程；返回 false 表示被环境变量关闭。 */
    public boolean start() {
        if (disabledByEnv()) {
            FileLog.info("任务节奏调度已关闭（VAULT_TASK_CADENCE=off），保持每天一轮行为");
            return false;
        }
        Thread t = new Thread(() -> {
            FileLog.info("任务节奏调度已启动：任务表 {} 项，tick={}ms，{}，全局预算={}/分钟，{}，账号预算={}/分钟",
                    String.valueOf(TaskCadence.size()), String.valueOf(TICK_MILLIS),
                    respectWindow ? "按账号时间窗" : "全天生效（网页版默认语义）",
                    globalMaxPerMinute <= 0 ? "不限" : String.valueOf(globalMaxPerMinute),
                    perRoleEnabled ? "按角色调度（R2 Phase 2）" : "单角色 active 语义",
                    accountMaxPerMinute <= 0 ? "不限" : String.valueOf(accountMaxPerMinute));
            while (!stopped) {
                try {
                    tick(System.currentTimeMillis());
                } catch (Throwable e) {
                    FileLog.warn("节奏调度节拍异常：{}", String.valueOf(e));
                }
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "task-cadence");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public void stop() {
        stopped = true;
    }

    /**
     * 单次扫描（包级可见便于单测）：
     * <ol>
     *   <li>全局预算检查（60s 滑动窗口；超预算只记 skip 原因，不写任何 cad 键）；</li>
     *   <li>逐账号（Phase 2 on 时逐角色）过闸门 + 找最久到期任务，得到候选；</li>
     *   <li>候选按 round-robin（on 模式叠加每角色保底 floor）选 1 个（同 tick 全局最多派 1 单），提交；</li>
     *   <li>落统计（{@link #STATS_KEY_PREFIX}；派单后立即写，其它计数按 {@link #STATS_PUBLISH_MILLIS} 合并写）。</li>
     * </ol>
     */
    void tick(long now) {
        boolean dispatched = false;
        try {
            ensureStatsDay(now);
            if (budgetExhausted(now)) {
                stats.skip("global_budget");
                return;                                          // 超预算本 tick 不派（finally 里落统计）
            }
            List<RoleCandidate> candidates = buildCandidates(now);
            if (candidates.isEmpty()) return;
            // 账号级预算过滤（on/off 都生效；默认 24/分，对单角色现网行为无影响）
            List<RoleCandidate> eligible = filterByAccountBudget(candidates, now);
            if (eligible.isEmpty()) return;
            RoleCandidate picked;
            if (!perRoleEnabled) {
                // 旧路径：每账号仅一个候选，按 fpid round-robin（与 Phase 1 完全一致）
                List<String> fpids = new ArrayList<>(eligible.size());
                for (RoleCandidate c : eligible) fpids.add(c.acc.fpid);
                String pickedFpid = pickNextAccount(fpids, pickCursor);
                if (pickedFpid == null) return;
                pickCursor = pickedFpid;
                picked = null;
                for (RoleCandidate c : eligible) {
                    if (c.acc.fpid.equals(pickedFpid)) {
                        picked = c;
                        break;
                    }
                }
            } else {
                picked = pickFairestRole(eligible, now);
            }
            if (picked == null) return;
            pickCursor = perRoleEnabled ? picked.role.key() : picked.acc.fpid;
            dispatched = dispatch(picked, now);
            updateMissCounters(candidates, picked);
        } finally {
            publishStatsIfDue(now, dispatched);
        }
    }

    /** 构建本 tick 候选：off=每账号一个旧角色槽；on=每在线角色一个候选。 */
    List<RoleCandidate> buildCandidates(long now) {
        List<RoleCandidate> candidates = new ArrayList<>();
        for (VaultStore.Account acc : store.snapshot()) {
            List<RoleKey> roles;
            try {
                roles = rolesFor(acc);
            } catch (Throwable t) {
                stats.skip("error");
                FileLog.warn("节奏调度账号异常 fpid={}：{}", acc.fpid, String.valueOf(t));
                continue;
            }
            for (RoleKey role : roles) {
                try {
                    RoleCandidate c = candidateOf(acc, role, now);
                    if (c != null) candidates.add(c);
                } catch (Throwable t) {
                    stats.skip("error");
                    FileLog.warn("节奏调度角色异常 role={}：{}", role.key(), String.valueOf(t));
                }
            }
        }
        return candidates;
    }

    /**
     * 调度单元列表：Phase 2 off（默认）→ 账号唯一旧角色槽 {@code (fpid,sid,0)}（sid 由会话解析），
     * 与 Phase 1 行为完全一致；on → {@link RoundScheduler.KeeperFacade#rolesOf(String)} 的在线/期望角色，
     * 空时回落 activeRole / 旧角色槽（旧数据/单角色账号兼容）。
     */
    List<RoleKey> rolesFor(VaultStore.Account acc) {
        List<RoleKey> out = new ArrayList<>();
        if (acc == null || acc.fpid == null || acc.fpid.isEmpty()) return out;
        if (!perRoleEnabled) {
            out.add(new RoleKey(acc.fpid, keeper.sessionSid(acc.fpid), 0L));
            return out;
        }
        List<RoleKey> rs = keeper.rolesOf(acc.fpid);
        if (rs != null && !rs.isEmpty()) {
            LinkedHashSet<RoleKey> dedup = new LinkedHashSet<>();
            for (RoleKey r : rs) {
                if (r != null && r.valid()) dedup.add(r);
            }
            out.addAll(dedup);
        }
        if (out.isEmpty()) {
            RoleKey active = keeper.activeRole(acc.fpid);
            if (active != null && active.valid()) {
                out.add(active);
            } else {
                out.add(new RoleKey(acc.fpid, keeper.sessionSid(acc.fpid), 0L));
            }
        }
        return out;
    }

    /**
     * 角色级候选：保留原有全部安全闸（顺序不变），返回「最久到期」任务；不满足时记 skip 原因并返回 null。
     * 不满足闸门时<b>不写任何状态</b>（与既有行为一致）。
     */
    RoleCandidate candidateOf(VaultStore.Account acc, RoleKey role, long now) {
        if (role == null) return null;
        if (acc.token == null || acc.token.isEmpty()) {
            skip(role, "no_token");
            return null;
        }
        if (!acc.auto) {                                         // 未开启自动：保持手动语义
            skip(role, "auto_off");
            return null;
        }
        if (!keeper.sessionOnline(role)) {                       // 会话不在线（含登录中/恢复中）
            skip(role, "offline");
            return null;
        }
        long onlineSince = keeper.sessionOnlineSince(role);
        if (onlineSince > 0 && now - onlineSince < INIT_GRACE_MILLIS) {   // C4 登录初始化保护（按角色）
            skip(role, "init_grace");
            return null;
        }
        if (role.sid <= 0) {                                     // 角色未知（登录未落地）
            skip(role, "no_sid");
            return null;
        }
        if (keeper.wartimeYield(role)) {                         // P7 战时让位（按角色）
            skip(role, "wartime");
            return null;
        }
        if (respectWindow && !inWindow(acc, now)) {              // 可选：沿用 App 时间窗
            skip(role, "window");
            return null;
        }
        if (keeper.executorBusy(role)) {                         // 上一单任务/整轮仍在跑（按角色）
            skip(role, "busy");
            return null;
        }

        long last = lastDispatchAt(role);
        if (last > 0 && now - last < MIN_DISPATCH_GAP_MILLIS) {   // 最小间隔（按角色）
            skip(role, "min_gap");
            return null;
        }

        List<String> enabled = enabledTaskIds(acc, role.sid, role.uid);
        if (enabled.isEmpty()) {
            skip(role, "no_task");
            return null;
        }

        Map<String, Object> snap = stateStore.snapshot();
        String dueFn = null;
        long dueAt = Long.MAX_VALUE;
        for (String fn : enabled) {
            long at = nextAt(snap, acc, role, fn);
            if (at <= now && at < dueAt) {
                dueAt = at;
                dueFn = fn;
            }
        }
        if (dueFn == null) {                                     // 所有任务都还没到点
            skip(role, "no_due");
            return null;
        }
        return new RoleCandidate(acc, role, dueFn);
    }

    /** 记 skip：账号级原因计数 + （Phase 2）角色级原因计数。 */
    private void skip(RoleKey role, String reason) {
        stats.skip(reason);
        if (role != null && role.roleScoped()) stats.skipRole(role.key(), reason);
    }

    /**
     * 提交一个候选任务（未受理 → 60s 重试，不吞间隔；受理 → 写下次时间（含结果退避/抖动））。
     * 受理的成功派单才计入全局/账号预算与统计。
     *
     * @return true=本次真正提交受理（计入预算）
     */
    private boolean dispatch(RoleCandidate c, long now) {
        String fpid = c.acc.fpid;
        boolean accepted;
        try {
            accepted = keeper.submitRound(c.role, c.fn, "节奏");
        } catch (Throwable t) {
            accepted = false;
        }
        if (!accepted) {
            // 会话刚掉线/被暂停：60s 后重试，不消耗间隔（旧行为：仍写 retry cad + last）
            writeCadence(c, now + RETRY_MILLIS);
            writeLast(c, now);
            lastDispatchInMemory.put(c.role.key(), now);
            stats.skip("unaccepted");
            if (c.role.roleScoped()) stats.skipRole(c.role.key(), "unaccepted");
            if (busyWarned.add(c.role.key() + ":" + c.fn)) {
                FileLog.info("节奏任务暂未受理 role={} fn={}（会话不可用，60s 后重试）",
                        c.role.key(), c.fn);
            }
            return false;
        }

        // 成功派单 → 计入全局/角色/账号预算窗口与当日统计
        pruneBudgetWindow(now - BUDGET_WINDOW_MILLIS);
        dispatchWindow.addLast(now);
        recordRoleDispatch(c.role.key(), now);
        recordAccountDispatch(fpid, now);
        stats.dispatched(fpid, c.role.key());

        long intervalMs = TaskCadence.intervalSeconds(c.fn) * 1000L;
        long baseMs = nextIntervalForResult(c.acc, c.role, c.fn, intervalMs, stateStore.snapshot());
        long jitter = baseMs >= 60_000L
                ? (long) ((random.nextDouble() * 0.2d - 0.1d) * baseMs) : 0L;
        writeCadence(c, now + baseMs + jitter);
        writeLast(c, now);
        lastDispatchInMemory.put(c.role.key(), now);
        busyWarned.remove(c.role.key() + ":" + c.fn);
        if (TaskCadence.intervalSeconds(c.fn) >= LOG_INTERVAL_THRESHOLD_SECONDS
                && startedLog.add(c.role.key() + ":" + c.fn)) {
            FileLog.info("节奏任务开始 role={} fn={} 间隔={}s",
                    c.role.key(), c.fn, String.valueOf(TaskCadence.intervalSeconds(c.fn)));
        }
        return true;
    }

    // ---------- 角色级状态键（Phase 2）：写角色键；active 角色镜像旧键；读 miss 回退旧键 ----------

    /**
     * 下次到期键：角色键；active 角色额外镜像旧键 {@code srv:cad:<fpid>:<sid>:<fn>}。
     *
     * <p>off 模式（uid=0 旧角色槽）写旧键的同时，把 active 角色的 uid 键也刷新为同一值：
     * Phase 2 wake 钩子按会话 uid 写 {@code …:<uid>:<fn>=0}，若不刷新会保留 0 导致每 tick 反复到期。
     */
    private void writeCadence(RoleCandidate c, long at) {
        stateStore.putLong(StateKeys.cadence(c.role, c.fn), at);
        if (!c.role.roleScoped()) {
            long activeUid = parseUid(c.acc == null ? null : c.acc.activeUid);
            if (activeUid > 0L) {
                stateStore.putLong(StateKeys.cadence(
                        new RoleKey(c.role.fpid, c.role.sid, activeUid), c.fn), at);
            }
        }
        if (shouldMirrorLegacy(c)) {
            stateStore.putLong(StateKeys.cadenceLegacy(c.role, c.fn), at);
        }
    }

    /**
     * 最近派单时刻：roleScoped 写 {@code srv:cadlast:<fpid>:<sid>:<uid>}；旧角色槽写账号级
     * {@code srv:cadlast:<fpid>}（与 Phase 1 完全一致）；active 角色额外镜像账号级旧键。
     */
    private void writeLast(RoleCandidate c, long at) {
        if (c.role.roleScoped()) {
            stateStore.putLong(StateKeys.cadenceLast(c.role), at);
        }
        if (!c.role.roleScoped() || shouldMirrorLegacy(c)) {
            stateStore.putLong(StateKeys.account("srv:cadlast", c.role.fpid, null), at);
        }
    }

    /** 是否 active 角色（决定旧键镜像：旧 App/回滚可继续读取）。 */
    private boolean shouldMirrorLegacy(RoleCandidate c) {
        if (!perRoleEnabled || c.role == null || !c.role.roleScoped()) return false;
        return isActiveRole(c.acc, c.role);
    }

    boolean isActiveRole(VaultStore.Account acc, RoleKey role) {
        if (acc == null || role == null) return false;
        RoleKey active = keeper.activeRole(acc.fpid);
        if (active != null) return active.sid == role.sid && active.uid == role.uid;
        String asid = acc.activeSid == null ? "" : acc.activeSid;
        String auid = acc.activeUid == null ? "" : acc.activeUid;
        return String.valueOf(role.sid).equals(asid)
                && role.uid > 0L && String.valueOf(role.uid).equals(auid);
    }

    /**
     * 读下次到期：角色键优先；roleScoped 且角色键缺失时回退旧键一次（惰性迁移读）。
     *
     * <p>off 模式（uid=0 旧角色槽）额外兼容读取 active 角色的 uid 键：{@link TaskWakeRouter}
     * Phase 2 起按会话 uid 写 {@code srv:cad:<fpid>:<sid>:<uid>:<fn>=0}，off 模式调度器只写旧键，
     * 若不读 uid 键会丢失推送唤醒（事件类任务退化为纯间隔轮询）。取两者更早的值。
     */
    private long nextAt(Map<String, Object> snap, VaultStore.Account acc, RoleKey role, String fn) {
        String roleKey = StateKeys.cadence(role, fn);
        long v = snap.containsKey(roleKey) ? longOf(snap, roleKey) : 0L;
        if (role.roleScoped()) {
            String legacy = StateKeys.cadenceLegacy(role, fn);
            if (!snap.containsKey(roleKey) && !legacy.equals(roleKey) && snap.containsKey(legacy)) {
                v = longOf(snap, legacy);
            }
        } else {
            // off 模式：兼容 active 角色的唤醒键（只读；写仍走旧键）
            long activeUid = parseUid(acc == null ? null : acc.activeUid);
            if (activeUid > 0L) {
                String wakeKey = StateKeys.cadence(new RoleKey(role.fpid, role.sid, activeUid), fn);
                if (!wakeKey.equals(roleKey) && snap.containsKey(wakeKey)) {
                    long wake = longOf(snap, wakeKey);
                    if (!snap.containsKey(roleKey) || wake < v) v = wake;
                }
            }
            if (!snap.containsKey(roleKey) && v == 0L) v = 0L;   // 无状态 = 立即到期
        }
        return v;
    }

    private static long parseUid(String raw) {
        if (raw == null || raw.isEmpty()) return 0L;
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0L ? v : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 读最近派单时刻：roleScoped 只读角色键（账号级旧键 {@code srv:cadlast:<fpid>} 会被
     * active 角色的镜像写更新，兄弟角色读它会被对方 min_gap 拦住——不做跨角色回退）；
     * 旧角色槽（uid<=0）读账号级旧键（Phase 1 行为不变）。内存兜底覆盖 state 未落盘场景。
     */
    private long lastDispatchAt(RoleKey role) {
        Map<String, Object> snap = stateStore.snapshot();
        String roleKey = role.roleScoped()
                ? StateKeys.cadenceLast(role)
                : StateKeys.account("srv:cadlast", role.fpid, null);
        long v = snap.containsKey(roleKey) ? longOf(snap, roleKey) : 0L;
        long mem = lastDispatchInMemory.getOrDefault(role.key(), 0L);
        return Math.max(v, mem);
    }

    // ---------- 公平性：账号/角色 round-robin + 每角色保底 ----------

    /**
     * 从「本 tick 有到期任务的账号」中按 round-robin 选一个（纯函数，Phase 1 口径保留）。
     *
     * <p>实现：候选账号先按 fpid 字典序稳定排序，再取 cursor（上次选中账号）之后的第一个
     * （环回保证不饿死）。单账号时恒返回该账号；cursor 为 null/不在候选中时从字典序最小开始。
     */
    static String pickNextAccount(List<String> candidates, String cursor) {
        return pickNextFrom(candidates, cursor);
    }

    /** Phase 2：按角色 key（{@code fpid|sid|uid}）round-robin 选一个（纯函数）。 */
    static String pickNextRole(List<String> candidates, String cursor) {
        return pickNextFrom(candidates, cursor);
    }

    private static String pickNextFrom(List<String> candidates, String cursor) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        if (cursor == null) return sorted.get(0);
        int idx = sorted.indexOf(cursor);
        if (idx < 0) return sorted.get(0);
        return sorted.get((idx + 1) % sorted.size());
    }

    /**
     * Phase 2 公平选择：先找「最近 60s 派单数 < 每角色保底 floor」的候选（保底优先），
     * 在保底集合内按 (最近派单数升序, 连续未派单次数降序, role key 轮转) 选，避免兄弟角色饿死；
     * 无保底缺口时按 role key round-robin 选。
     */
    RoleCandidate pickFairestRole(List<RoleCandidate> eligible, long now) {
        if (eligible == null || eligible.isEmpty()) return null;
        int onlineRoles = Math.max(1, countOnlineRoles());
        int floor = globalMaxPerMinute <= 0
                ? MIN_ROLE_FLOOR_PER_MINUTE
                : Math.max(MIN_ROLE_FLOOR_PER_MINUTE, globalMaxPerMinute / onlineRoles);
        List<RoleCandidate> starved = new ArrayList<>();
        for (RoleCandidate c : eligible) {
            if (recentRoleDispatchCount(c.role.key(), now) < floor) starved.add(c);
        }
        List<RoleCandidate> pool = starved.isEmpty() ? eligible : starved;
        RoleCandidate best = null;
        for (RoleCandidate c : pool) {
            if (best == null || fairCompare(c, best, now) < 0) best = c;
        }
        return best;
    }

    /** 公平比较：最近派单数少者优先；同分看连续未派单次数多者；再同按 role key 轮转。 */
    private int fairCompare(RoleCandidate a, RoleCandidate b, long now) {
        int byCount = Long.compare(recentRoleDispatchCount(a.role.key(), now),
                recentRoleDispatchCount(b.role.key(), now));
        if (byCount != 0) return byCount;
        int byMiss = Integer.compare(consecutiveMisses.getOrDefault(b.role.key(), 0),
                consecutiveMisses.getOrDefault(a.role.key(), 0));
        if (byMiss != 0) return byMiss;
        String first = pickNextRole(Arrays.asList(a.role.key(), b.role.key()), pickCursor);
        return a.role.key().equals(first) ? -1 : 1;
    }

    /** 在线角色数（floor 分母；仅 tick 线程调用）。 */
    int countOnlineRoles() {
        int n = 0;
        for (VaultStore.Account acc : store.snapshot()) {
            for (RoleKey role : rolesFor(acc)) {
                if (keeper.sessionOnline(role)) n++;
            }
        }
        return n;
    }

    /** 更新连续未派单计数：未被选中的候选 +1，选中的清零。 */
    private void updateMissCounters(List<RoleCandidate> candidates, RoleCandidate picked) {
        String pickedKey = picked.role.key();
        for (RoleCandidate c : candidates) {
            if (c.role.key().equals(pickedKey)) {
                consecutiveMisses.remove(pickedKey);
            } else {
                consecutiveMisses.merge(c.role.key(), 1, Integer::sum);
            }
        }
    }

    // ---------- 全局预算 / 账号级预算（60s 滑动窗口） ----------

    /** 全局预算是否已用尽（0=不限）；顺带清理 60s 前的记录。 */
    boolean budgetExhausted(long now) {
        if (globalMaxPerMinute <= 0) return false;
        pruneBudgetWindow(now - BUDGET_WINDOW_MILLIS);
        return dispatchWindow.size() >= globalMaxPerMinute;
    }

    /** 移除窗口起点之前的派单记录（记录按时间升序入队）。 */
    private void pruneBudgetWindow(long cutoffMillis) {
        while (!dispatchWindow.isEmpty() && dispatchWindow.peekFirst() <= cutoffMillis) {
            dispatchWindow.pollFirst();
        }
    }

    /** 当前 60s 滑动窗口内派单数（统计/自检用）。 */
    int minuteDispatchCount() {
        return dispatchWindow.size();
    }

    /** Phase 2：账号级预算是否用尽（0=不限）；顺带清理窗口。 */
    boolean accountBudgetExhausted(String fpid, long now) {
        if (accountMaxPerMinute <= 0) return false;
        ArrayDeque<Long> w = accountDispatchWindow.get(fpid);
        if (w == null) return false;
        long cutoff = now - BUDGET_WINDOW_MILLIS;
        while (!w.isEmpty() && w.peekFirst() <= cutoff) w.pollFirst();
        return w.size() >= accountMaxPerMinute;
    }

    private void recordAccountDispatch(String fpid, long now) {
        accountDispatchWindow.computeIfAbsent(fpid, k -> new ArrayDeque<>()).addLast(now);
    }

    /** Phase 2：按账号预算过滤候选；被过滤角色记 account_budget skip（不写 cad 键）。 */
    private List<RoleCandidate> filterByAccountBudget(List<RoleCandidate> candidates, long now) {
        List<RoleCandidate> out = new ArrayList<>(candidates.size());
        for (RoleCandidate c : candidates) {
            if (accountBudgetExhausted(c.acc.fpid, now)) {
                stats.skip("account_budget");
                if (c.role.roleScoped()) stats.skipRole(c.role.key(), "account_budget");
            } else {
                out.add(c);
            }
        }
        return out;
    }

    private void recordRoleDispatch(String roleKey, long now) {
        roleDispatchWindow.computeIfAbsent(roleKey, k -> new ArrayDeque<>()).addLast(now);
    }

    /** 该角色最近 60s 派单数（顺带清理过期记录）。 */
    int recentRoleDispatchCount(String roleKey, long now) {
        ArrayDeque<Long> w = roleDispatchWindow.get(roleKey);
        if (w == null) return 0;
        long cutoff = now - BUDGET_WINDOW_MILLIS;
        while (!w.isEmpty() && w.peekFirst() <= cutoff) w.pollFirst();
        return w.size();
    }

    // ---------- 派单统计（srv:cad:stats:{yyyy-MM-dd}，键型见 STATS_KEY_PREFIX） ----------

    /** 切换/初始化统计日：跨天清零；同日重启优先继承已落盘计数（日报不丢数）。 */
    private void ensureStatsDay(long now) {
        String day = Beijing.fmt("yyyy-MM-dd").format(new Date(now));
        if (day.equals(stats.day)) return;
        stats.reset(day, now);
        try {
            String raw = stateStore.getString(STATS_KEY_PREFIX + day);
            if (raw != null) stats.load(new JSONObject(raw));
        } catch (Throwable ignored) {
        }
    }

    /** 派单后立即落统计；否则按 {@link #STATS_PUBLISH_MILLIS} 合并落盘（限流 skip 计数写频）。 */
    private void publishStatsIfDue(long now, boolean force) {
        if (!force && lastStatsPublishAt > 0 && now - lastStatsPublishAt < STATS_PUBLISH_MILLIS) return;
        publishStats(now);
    }

    /** 落盘当日派单统计（包级可见便于单测）。 */
    void publishStats(long now) {
        try {
            if (stats.day.isEmpty()) return;
            JSONObject o = new JSONObject();
            o.put("date", stats.day);
            o.put("dispatch", stats.dispatch);
            o.put("byAccount", new JSONObject(stats.byAccount));
            o.put("byRole", new JSONObject(stats.byRole));
            o.put("skip", new JSONObject(stats.skip));
            JSONObject skipByRole = new JSONObject();
            for (Map.Entry<String, Map<String, Long>> e : stats.skipByRole.entrySet()) {
                skipByRole.put(e.getKey(), new JSONObject(e.getValue()));
            }
            o.put("skipByRole", skipByRole);
            o.put("globalMax", globalMaxPerMinute);
            o.put("accountMax", accountMaxPerMinute);
            o.put("minuteDispatch", dispatchWindow.size());
            o.put("firstAt", stats.firstAt);
            o.put("updatedAt", now);
            stateStore.putString(STATS_KEY_PREFIX + stats.day, o.toString());
            lastStatsPublishAt = now;
        } catch (Throwable t) {
            FileLog.warn("节奏统计落盘失败：{}", String.valueOf(t));
        }
    }

    /** 当日派单统计（内存；tick 单线程写，落盘为 JSON 文本）。 */
    static final class CadenceStats {
        String day = "";
        long dispatch;
        final Map<String, Long> byAccount = new LinkedHashMap<>();
        /** Phase 2：按角色（fpid|sid|uid）派单数。 */
        final Map<String, Long> byRole = new LinkedHashMap<>();
        final Map<String, Long> skip = new LinkedHashMap<>();
        /** Phase 2：按角色的跳过原因分布。 */
        final Map<String, Map<String, Long>> skipByRole = new LinkedHashMap<>();
        long firstAt;

        void reset(String newDay, long now) {
            day = newDay;
            dispatch = 0L;
            byAccount.clear();
            byRole.clear();
            skip.clear();
            skipByRole.clear();
            firstAt = now;
        }

        /** 从同日已落盘 JSON 继承计数（服务重启不丢当天统计）。 */
        void load(JSONObject o) {
            if (o == null) return;
            dispatch = Math.max(0L, o.optLong("dispatch", 0L));
            firstAt = o.optLong("firstAt", firstAt);
            JSONObject by = o.optJSONObject("byAccount");
            if (by != null) {
                for (String k : by.keySet()) {
                    long v = by.optLong(k, 0L);
                    if (v > 0) byAccount.put(k, v);
                }
            }
            JSONObject byRole = o.optJSONObject("byRole");
            if (byRole != null) {
                for (String k : byRole.keySet()) {
                    long v = byRole.optLong(k, 0L);
                    if (v > 0) this.byRole.put(k, v);
                }
            }
            JSONObject sk = o.optJSONObject("skip");
            if (sk != null) {
                for (String k : sk.keySet()) {
                    long v = sk.optLong(k, 0L);
                    if (v > 0) skip.put(k, v);
                }
            }
            JSONObject skr = o.optJSONObject("skipByRole");
            if (skr != null) {
                for (String roleKey : skr.keySet()) {
                    JSONObject reasons = skr.optJSONObject(roleKey);
                    if (reasons == null) continue;
                    Map<String, Long> m = new LinkedHashMap<>();
                    for (String reason : reasons.keySet()) {
                        long v = reasons.optLong(reason, 0L);
                        if (v > 0) m.put(reason, v);
                    }
                    if (!m.isEmpty()) this.skipByRole.put(roleKey, m);
                }
            }
        }

        void skip(String reason) {
            skip.merge(reason, 1L, Long::sum);
        }

        /** Phase 2：角色级 skip 计数（同时计入账号级 skip）。 */
        void skipRole(String roleKey, String reason) {
            if (roleKey == null || reason == null) return;
            skipByRole.computeIfAbsent(roleKey, k -> new LinkedHashMap<>())
                    .merge(reason, 1L, Long::sum);
        }

        void dispatched(String fpid, String roleKey) {
            dispatch++;
            if (fpid != null) byAccount.merge(fpid, 1L, Long::sum);
            if (roleKey != null) byRole.merge(roleKey, 1L, Long::sum);
        }
    }

    /** 本 tick 可派候选：账号 + 角色执行单元（Phase 2）+ 最久到期任务 id。 */
    static final class RoleCandidate {
        final VaultStore.Account acc;
        final RoleKey role;
        final String fn;

        RoleCandidate(VaultStore.Account acc, RoleKey role, String fn) {
            this.acc = acc;
            this.role = role;
            this.fn = fn;
        }
    }

    /**
     * 该角色的启用任务 id：Phase 2 优先 {@code roleConfigByUid[sid:uid]}，缺省回退
     * {@code roleConfig[sid]}（旧 App/迁移），再回退 {@link ServerTasks} 目录默认值。
     */
    List<String> enabledTaskIds(VaultStore.Account acc, long sid, long uid) {
        Map<String, Boolean> roleCfg = null;
        if (uid > 0L && acc.roleConfigByUid != null) {
            roleCfg = acc.roleConfigByUid.get(sid + ":" + uid);
        }
        if (roleCfg == null) roleCfg = acc.roleConfig.get(String.valueOf(sid));
        AutomationConfig config = AutomationConfig.fromLegacy(
                registry.modules().values(), roleCfg, Collections.emptyMap());
        List<String> out = new ArrayList<>();
        for (AutomationModule m : registry.ordered()) {
            ModuleConfig mc = config.module(m.id());
            for (ActionDefinition a : m.actions()) {
                if (!TaskCadence.contains(a.id)) continue;
                if (mc.actionEnabled(a.id, a.defaultEnabled)) out.add(a.id);
            }
        }
        return out;
    }

    /** 旧签名（Phase 1）：uid=0 语义，行为不变。 */
    List<String> enabledTaskIds(VaultStore.Account acc, long sid) {
        return enabledTaskIds(acc, sid, 0L);
    }

    /**
     * 依据上轮结果类别计算下次间隔（{@code st:} 当日状态机：2=已领取、4=已检查无可领、3=异常、1=会话中断）。
     *
     * <p>生产观察（2026-10-23 14:0x）：60s 任务下限 + 约 40 个默认任务后，节奏调度持续跑在
     * 10 轮/分钟上限（平均间隔 6.02s），说明大量“无可领空转”任务占满提交位。本方法按结果退避：
     * <ul>
     *   <li>4=已检查无可领 → 间隔 ×3 且 ≥180s（事件类任务由 TaskWakeRouter 推送提前唤醒，不受影响）；</li>
     *   <li>3=异常 → 间隔 ×2 且 ≥120s（防错误风暴）；</li>
     *   <li>2=已领取 / 未知 → 正常间隔。</li>
     * </ul>
     */
    static long nextIntervalForResult(VaultStore.Account acc, long sid, String fn,
                                      long intervalMillis, Map<String, Object> snapshot) {
        return nextIntervalForResult(acc, new RoleKey(acc == null ? "" : acc.fpid, sid, 0L),
                fn, intervalMillis, snapshot);
    }

    /** Phase 2：按角色读当日结果状态机（角色键 miss 回退旧键一次）。 */
    static long nextIntervalForResult(VaultStore.Account acc, RoleKey role, String fn,
                                      long intervalMillis, Map<String, Object> snapshot) {
        String day = Beijing.fmt("yyyy-MM-dd").format(new Date());
        String roleKey = StateKeys.dayState(day, role, fn);
        int st;
        if (snapshot != null && snapshot.containsKey(roleKey)) {
            st = (int) longOf(snapshot, roleKey);
        } else if (snapshot != null && role != null && role.roleScoped()
                && snapshot.containsKey(StateKeys.dayStateLegacy(day, role, fn))) {
            st = (int) longOf(snapshot, StateKeys.dayStateLegacy(day, role, fn));
        } else if (snapshot != null && role != null && !role.roleScoped()) {
            // off 模式：兼容 active 角色的 uid st 键（Phase 2 执行器按角色写状态机时仍能退避）
            long activeUid = parseUid(acc == null ? null : acc.activeUid);
            String activeKey = activeUid > 0L
                    ? StateKeys.dayState(day, new RoleKey(role.fpid, role.sid, activeUid), fn) : null;
            st = activeKey != null && snapshot.containsKey(activeKey)
                    ? (int) longOf(snapshot, activeKey) : 0;
        } else {
            st = 0;
        }
        if (st == 4) {
            return Math.max(intervalMillis * SKIP_BACKOFF_MULTIPLIER, SKIP_BACKOFF_MIN_MILLIS);
        }
        if (st == 3) {
            return Math.max(intervalMillis * ERROR_BACKOFF_MULTIPLIER, ERROR_BACKOFF_MIN_MILLIS);
        }
        return intervalMillis;
    }

    private static long longOf(Map<String, Object> snap, String key) {
        if (snap == null || key == null) return 0L;
        Object v = snap.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private boolean inWindow(VaultStore.Account acc, long now) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.setTimeInMillis(now);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int start = acc.windowStart;
        int end = acc.windowEnd;
        if (start == end) return true;                    // 全时段
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end;               // 跨零点窗口
    }

    /** 自检用：节奏表是否覆盖 {@link ServerTasks} 全部任务（测试断言）。 */
    static Set<String> missingTaskIds() {
        Set<String> missing = new HashSet<>();
        for (ServerTasks.TaskDef def : ServerTasks.all()) {
            if (!TaskCadence.contains(def.id)) missing.add(def.id);
        }
        return missing;
    }
}
