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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *   <li>每个 tick（2s）扫描 auto=true 且会话在线的账号，先做各账号闸门/到期判定得到候选；</li>
 *   <li>候选账号按 {@link #pickNextAccount 账号 fpid 字典序 + round-robin 游标} 公平选一个
 *       （同 tick 只派 1 单，避免遍历顺序导致某账号饿死）；</li>
 *   <li>全局每分钟派单预算 {@code VAULT_CADENCE_GLOBAL_MAX}（默认 20；off/0=不限）用 60s
 *       滑动窗口限流，超预算本 tick 不派（不写 cad 键，下 tick 继续）；</li>
 *   <li>把命中的任务提交单任务执行（{@link RoundScheduler.KeeperFacade#submitRound}，
 *       进入会话单线程执行器，天然与整轮/手动串行），并按 {@link TaskCadence} 写下次执行时间
 *       （>60s 的间隔加 ±10% 抖动；上轮 SKIPPED/ERROR 按 {@link #nextIntervalForResult} 退避）；</li>
 *   <li>派单统计写 {@code srv:cad:stats:{yyyy-MM-dd}}（JSON 文本，键型见 {@link #STATS_KEY_PREFIX}），
 *       供 DailyDigest/HealthMonitor 消费。</li>
 * </ol>
 *
 * <p>安全约束：同一账号两次提交至少间隔 {@link #MIN_DISPATCH_GAP_MILLIS}；
 * 会话线程忙（整轮/单任务执行中）时不提交，避免排队堆积；战时让位（P7）时不提交；
 * 暂停（PAUSED）/离线/未开启自动的账号不提交。默认全天生效（= 网页版默认语义，
 * 其“禁运行时段”默认关闭）；如服务器要保留原 05:00-09:00 时间窗，设环境变量
 * {@code VAULT_CADENCE_WINDOW=1}。整体回滚：{@code VAULT_TASK_CADENCE=off}。
 */
public final class TaskCadenceScheduler {

    /** 扫描节拍。 */
    static final long TICK_MILLIS = 2_000L;
    /**
     * 同一账号两次提交的最小间隔（模拟人手停顿，防请求突发）。
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
    /** 统计落盘最小间隔；有派单时立即落盘（skip 原因等低频计数按该间隔合并写）。 */
    static final long STATS_PUBLISH_MILLIS = 10_000L;
    /**
     * 派单统计键：{@code srv:cad:stats:{yyyy-MM-dd}}（北京时间日期；值为 JSON 文本）。
     *
     * <p><b>键型契约（供 DailyDigest / HealthMonitor / 看板消费，勿随意改名）</b>：
     * <pre>{@code
     * {
     *   "date": "2026-10-23",              // 统计日期（北京时间）
     *   "dispatch": 123,                   // 当日成功进入节奏提交的派单数（= 游戏请求轮次数）
     *   "byAccount": {"48460403": 80, ...},// 当日按账号分布
     *   "skip": {"min_gap": 900, ...},     // 当日各闸门拒绝次数（每 tick 每账号只记首个命中原因）
     *   "globalMax": 20,                   // 全局每分钟预算（0=不限）
     *   "minuteDispatch": 7,               // 当前 60s 滑动窗口内派单数
     *   "firstAt": 1792...,                // 当日统计起点（epoch millis）
     *   "updatedAt": 1792...               // 本次落盘时刻（epoch millis）
     * }
     * }</pre>
     * skip 原因取值：{@code no_token/auto_off/offline/init_grace/no_sid/wartime/window/busy/
     * min_gap/no_task/no_due/unaccepted/global_budget/error}。
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
    private final Set<String> startedLog = ConcurrentHashMap.newKeySet();
    private final Set<String> busyWarned = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastDispatchInMemory = new ConcurrentHashMap<>();
    /** 60s 滑动窗口：最近成功提交的派单时刻（毫秒，升序；仅 tick 线程读写）。 */
    private final ArrayDeque<Long> dispatchWindow = new ArrayDeque<>();
    /** round-robin 游标：上一 tick 选中的账号 fpid（null=首次）。 */
    private String pickCursor;
    /** 当日派单统计（内存计数，定期落盘；仅 tick 线程读写）。 */
    private final CadenceStats stats = new CadenceStats();
    /** 上次统计落盘时刻（0=尚未落盘）。 */
    private long lastStatsPublishAt;
    private volatile boolean stopped;

    public TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                                RoundScheduler.KeeperFacade keeper) {
        this(store, stateStore, keeper, defaultRegistry(), windowEnabledByEnv(), new Random(),
                globalMaxByEnv());
    }

    /** 可注入构造（单测：固定 registry / 窗口模式 / 随机种子；全局预算取环境变量）。 */
    TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                         RoundScheduler.KeeperFacade keeper, ModuleRegistry registry,
                         boolean respectWindow, Random random) {
        this(store, stateStore, keeper, registry, respectWindow, random, globalMaxByEnv());
    }

    /** 可注入构造（单测：额外固定全局每分钟预算，0=不限）。 */
    TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                         RoundScheduler.KeeperFacade keeper, ModuleRegistry registry,
                         boolean respectWindow, Random random, int globalMaxPerMinute) {
        this.store = store;
        this.stateStore = stateStore;
        this.keeper = keeper;
        this.registry = registry;
        this.respectWindow = respectWindow;
        this.random = random;
        this.globalMaxPerMinute = Math.max(0, globalMaxPerMinute);
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

    /** 启动后台线程；返回 false 表示被环境变量关闭。 */
    public boolean start() {
        if (disabledByEnv()) {
            FileLog.info("任务节奏调度已关闭（VAULT_TASK_CADENCE=off），保持每天一轮行为");
            return false;
        }
        Thread t = new Thread(() -> {
            FileLog.info("任务节奏调度已启动：任务表 {} 项，tick={}ms，{}，全局预算={}/分钟，账号 round-robin 公平",
                    String.valueOf(TaskCadence.size()), String.valueOf(TICK_MILLIS),
                    respectWindow ? "按账号时间窗" : "全天生效（网页版默认语义）",
                    globalMaxPerMinute <= 0 ? "不限" : String.valueOf(globalMaxPerMinute));
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
     *   <li>逐账号过既有闸门 + 找最久到期任务，得到候选；</li>
     *   <li>候选账号按 fpid 字典序 + round-robin 游标选 1 个（同 tick 全局最多派 1 单），提交；</li>
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
            List<Candidate> candidates = new ArrayList<>();
            for (VaultStore.Account acc : store.snapshot()) {
                try {
                    Candidate c = candidateOf(acc, now);
                    if (c != null) candidates.add(c);
                } catch (Throwable t) {
                    stats.skip("error");
                    FileLog.warn("节奏调度账号异常 fpid={}：{}", acc.fpid, String.valueOf(t));
                }
            }
            if (candidates.isEmpty()) return;
            List<String> fpids = new ArrayList<>(candidates.size());
            for (Candidate c : candidates) fpids.add(c.acc.fpid);
            String picked = pickNextAccount(fpids, pickCursor);
            if (picked == null) return;
            pickCursor = picked;                                 // 游标在选中即推进（含未受理，防单账号卡位）
            for (Candidate c : candidates) {
                if (c.acc.fpid.equals(picked)) {
                    dispatched = dispatch(c, now);
                    break;
                }
            }
        } finally {
            publishStatsIfDue(now, dispatched);
        }
    }

    /**
     * 账号级候选：保留原有全部安全闸（顺序不变），返回「最久到期」任务；不满足时记 skip 原因并返回 null。
     * 不满足闸门时<b>不写任何状态</b>（与既有行为一致）。
     */
    Candidate candidateOf(VaultStore.Account acc, long now) {
        if (acc.token == null || acc.token.isEmpty()) {
            stats.skip("no_token");
            return null;
        }
        if (!acc.auto) {                                         // 未开启自动：保持手动语义
            stats.skip("auto_off");
            return null;
        }
        if (!keeper.sessionOnline(acc.fpid)) {                   // 会话不在线（含登录中/恢复中）
            stats.skip("offline");
            return null;
        }
        long onlineSince = keeper.sessionOnlineSince(acc.fpid);
        if (onlineSince > 0 && now - onlineSince < INIT_GRACE_MILLIS) {   // C4 登录初始化保护
            stats.skip("init_grace");
            return null;
        }
        long sid = keeper.sessionSid(acc.fpid);
        if (sid <= 0) {                                          // 角色未知（登录未落地）
            stats.skip("no_sid");
            return null;
        }
        if (keeper.wartimeYield(acc.fpid)) {                     // P7 战时让位
            stats.skip("wartime");
            return null;
        }
        if (respectWindow && !inWindow(acc, now)) {              // 可选：沿用 App 时间窗
            stats.skip("window");
            return null;
        }
        if (keeper.executorBusy(acc.fpid)) {                     // 上一单任务/整轮仍在跑
            stats.skip("busy");
            return null;
        }

        Map<String, Object> snap = stateStore.snapshot();
        long last = longOf(snap, "srv:cadlast:" + acc.fpid);
        if (last <= 0) last = lastDispatchInMemory.getOrDefault(acc.fpid, 0L);
        if (last > 0 && now - last < MIN_DISPATCH_GAP_MILLIS) {
            stats.skip("min_gap");
            return null;
        }

        List<String> enabled = enabledTaskIds(acc, sid);
        if (enabled.isEmpty()) {
            stats.skip("no_task");
            return null;
        }

        String dueFn = null;
        long dueAt = Long.MAX_VALUE;
        for (String fn : enabled) {
            long at = longOf(snap, nextKey(acc.fpid, sid, fn));
            if (at <= now && at < dueAt) {
                dueAt = at;
                dueFn = fn;
            }
        }
        if (dueFn == null) {                                     // 所有任务都还没到点
            stats.skip("no_due");
            return null;
        }
        return new Candidate(acc, sid, dueFn);
    }

    /**
     * 提交一个候选任务（与原 tickAccount 后半段行为一致：未受理 → 60s 重试，不吞间隔；
     * 受理 → 写下次时间（含结果退避/抖动））。受理的成功派单才计入全局预算与统计。
     *
     * @return true=本次真正提交受理（计入预算）
     */
    private boolean dispatch(Candidate c, long now) {
        String fpid = c.acc.fpid;
        boolean accepted;
        try {
            accepted = keeper.submitRound(fpid, c.fn, "节奏");
        } catch (Throwable t) {
            accepted = false;
        }
        if (!accepted) {
            // 会话刚掉线/被暂停：60s 后重试，不消耗间隔
            stateStore.putLong(nextKey(fpid, c.sid, c.fn), now + RETRY_MILLIS);
            stateStore.putLong("srv:cadlast:" + fpid, now);
            lastDispatchInMemory.put(fpid, now);
            stats.skip("unaccepted");
            if (busyWarned.add(fpid + ":" + c.fn)) {
                FileLog.info("节奏任务暂未受理 fpid={} fn={}（会话不可用，60s 后重试）",
                        fpid, c.fn);
            }
            return false;
        }

        // 成功派单 → 计入全局预算（60s 滑动窗口）与当日统计
        pruneBudgetWindow(now - BUDGET_WINDOW_MILLIS);
        dispatchWindow.addLast(now);
        stats.dispatched(fpid);

        long intervalMs = TaskCadence.intervalSeconds(c.fn) * 1000L;
        long baseMs = nextIntervalForResult(c.acc, c.sid, c.fn, intervalMs, stateStore.snapshot());
        long jitter = baseMs >= 60_000L
                ? (long) ((random.nextDouble() * 0.2d - 0.1d) * baseMs) : 0L;
        stateStore.putLong(nextKey(fpid, c.sid, c.fn), now + baseMs + jitter);
        stateStore.putLong("srv:cadlast:" + fpid, now);
        lastDispatchInMemory.put(fpid, now);
        busyWarned.remove(fpid + ":" + c.fn);
        if (TaskCadence.intervalSeconds(c.fn) >= LOG_INTERVAL_THRESHOLD_SECONDS
                && startedLog.add(fpid + ":" + c.fn)) {
            FileLog.info("节奏任务开始 fpid={} sid={} fn={} 间隔={}s",
                    fpid, String.valueOf(c.sid), c.fn,
                    String.valueOf(TaskCadence.intervalSeconds(c.fn)));
        }
        return true;
    }

    // ---------- 公平性：账号 round-robin（纯函数，单测直接覆盖） ----------

    /**
     * 从「本 tick 有到期任务的账号」中按 round-robin 选一个（纯函数）。
     *
     * <p>实现：候选账号先按 fpid 字典序稳定排序，再取 cursor（上次选中账号）之后的第一个
     * （环回保证不饿死）。单账号时恒返回该账号；cursor 为 null/不在候选中时从字典序最小开始。
     *
     * @param candidates 候选账号 fpid（可有重复/乱序，内部排序）
     * @param cursor     上次选中的账号 fpid（null=首次）
     * @return 本 tick 派单账号；无候选返回 null
     */
    static String pickNextAccount(List<String> candidates, String cursor) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        if (cursor == null) return sorted.get(0);
        int idx = sorted.indexOf(cursor);
        if (idx < 0) return sorted.get(0);
        return sorted.get((idx + 1) % sorted.size());
    }

    // ---------- 全局预算：60s 滑动窗口 ----------

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
            o.put("skip", new JSONObject(stats.skip));
            o.put("globalMax", globalMaxPerMinute);
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
        final Map<String, Long> skip = new LinkedHashMap<>();
        long firstAt;

        void reset(String newDay, long now) {
            day = newDay;
            dispatch = 0L;
            byAccount.clear();
            skip.clear();
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
            JSONObject sk = o.optJSONObject("skip");
            if (sk != null) {
                for (String k : sk.keySet()) {
                    long v = sk.optLong(k, 0L);
                    if (v > 0) skip.put(k, v);
                }
            }
        }

        void skip(String reason) {
            skip.merge(reason, 1L, Long::sum);
        }

        void dispatched(String fpid) {
            dispatch++;
            byAccount.merge(fpid, 1L, Long::sum);
        }
    }

    /** 本 tick 可派账号候选：账号 + 角色 sid + 最久到期任务 id。 */
    static final class Candidate {
        final VaultStore.Account acc;
        final long sid;
        final String fn;

        Candidate(VaultStore.Account acc, long sid, String fn) {
            this.acc = acc;
            this.sid = sid;
            this.fn = fn;
        }
    }

    /**
     * 该账号当前角色已启用的任务 id（旧 roleConfig 覆盖 → {@link ServerTasks} 目录默认值；
     * 与 {@link ModularRoundRunner} 完全同口径，避免“App 勾选”与“节奏执行”不一致）。
     */
    List<String> enabledTaskIds(VaultStore.Account acc, long sid) {
        Map<String, Boolean> roleCfg = acc.roleConfig.get(String.valueOf(sid));
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

    private static String nextKey(String fpid, long sid, String fn) {
        return "srv:cad:" + fpid + ":" + sid + ":" + fn;
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
        String stKey = "st:" + Beijing.fmt("yyyy-MM-dd").format(new Date())
                + ":" + acc.fpid + ":" + sid + ":" + fn;
        int st = (int) longOf(snapshot, stKey);
        if (st == 4) {
            return Math.max(intervalMillis * SKIP_BACKOFF_MULTIPLIER, SKIP_BACKOFF_MIN_MILLIS);
        }
        if (st == 3) {
            return Math.max(intervalMillis * ERROR_BACKOFF_MULTIPLIER, ERROR_BACKOFF_MIN_MILLIS);
        }
        return intervalMillis;
    }

    private static long longOf(Map<String, Object> snap, String key) {
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
