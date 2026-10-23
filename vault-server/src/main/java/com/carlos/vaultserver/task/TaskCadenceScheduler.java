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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
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
 *   <li>每个 tick（2s）扫描 auto=true 且会话在线的账号；</li>
 *   <li>取该账号当前角色已启用的任务（旧 roleConfig 覆盖 → 目录默认值，与整轮同口径）；</li>
 *   <li>挑「最久未到点」的一个任务提交单任务执行（{@link RoundScheduler.KeeperFacade#submitRound}，
 *       进入会话单线程执行器，天然与整轮/手动串行）；</li>
 *   <li>按 {@link TaskCadence} 写入下次执行时间（>60s 的间隔加 ±10% 抖动，避免整齐划一）。</li>
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
    /** 慢任务（间隔 ≥ 该值）提交时打 info 日志，快任务靠单任务轮次日志观测。 */
    private static final long LOG_INTERVAL_THRESHOLD_SECONDS = 300L;

    private final VaultStore store;
    private final StateStore stateStore;
    private final RoundScheduler.KeeperFacade keeper;
    private final ModuleRegistry registry;
    private final boolean respectWindow;
    private final Random random;
    private final Set<String> startedLog = ConcurrentHashMap.newKeySet();
    private final Set<String> busyWarned = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastDispatchInMemory = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                                RoundScheduler.KeeperFacade keeper) {
        this(store, stateStore, keeper, defaultRegistry(), windowEnabledByEnv(), new Random());
    }

    /** 可注入构造（单测：固定 registry / 窗口模式 / 随机种子）。 */
    TaskCadenceScheduler(VaultStore store, StateStore stateStore,
                         RoundScheduler.KeeperFacade keeper, ModuleRegistry registry,
                         boolean respectWindow, Random random) {
        this.store = store;
        this.stateStore = stateStore;
        this.keeper = keeper;
        this.registry = registry;
        this.respectWindow = respectWindow;
        this.random = random;
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

    /** 启动后台线程；返回 false 表示被环境变量关闭。 */
    public boolean start() {
        if (disabledByEnv()) {
            FileLog.info("任务节奏调度已关闭（VAULT_TASK_CADENCE=off），保持每天一轮行为");
            return false;
        }
        Thread t = new Thread(() -> {
            FileLog.info("任务节奏调度已启动：任务表 {} 项，tick={}ms，{}",
                    String.valueOf(TaskCadence.size()), String.valueOf(TICK_MILLIS),
                    respectWindow ? "按账号时间窗" : "全天生效（网页版默认语义）");
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

    /** 单次扫描（包级可见便于单测）。 */
    void tick(long now) {
        for (VaultStore.Account acc : store.snapshot()) {
            try {
                tickAccount(acc, now);
            } catch (Throwable t) {
                FileLog.warn("节奏调度账号异常 fpid={}：{}", acc.fpid, String.valueOf(t));
            }
        }
    }

    private void tickAccount(VaultStore.Account acc, long now) {
        if (acc.token == null || acc.token.isEmpty()) return;
        if (!acc.auto) return;                                  // 未开启自动：保持手动语义
        if (!keeper.sessionOnline(acc.fpid)) return;            // 会话不在线（含登录中/恢复中）
        long onlineSince = keeper.sessionOnlineSince(acc.fpid);
        if (onlineSince > 0 && now - onlineSince < INIT_GRACE_MILLIS) return;   // C4 登录初始化保护
        long sid = keeper.sessionSid(acc.fpid);
        if (sid <= 0) return;                                   // 角色未知（登录未落地）
        if (keeper.wartimeYield(acc.fpid)) return;              // P7 战时让位
        if (respectWindow && !inWindow(acc, now)) return;       // 可选：沿用 App 时间窗
        if (keeper.executorBusy(acc.fpid)) return;              // 上一单任务/整轮仍在跑

        long last = longOf(stateStore.snapshot(), "srv:cadlast:" + acc.fpid);
        if (last <= 0) last = lastDispatchInMemory.getOrDefault(acc.fpid, 0L);
        if (last > 0 && now - last < MIN_DISPATCH_GAP_MILLIS) return;

        List<String> enabled = enabledTaskIds(acc, sid);
        if (enabled.isEmpty()) return;

        Map<String, Object> snap = stateStore.snapshot();
        String dueFn = null;
        long dueAt = Long.MAX_VALUE;
        for (String fn : enabled) {
            long at = longOf(snap, nextKey(acc.fpid, sid, fn));
            if (at <= now && at < dueAt) {
                dueAt = at;
                dueFn = fn;
            }
        }
        if (dueFn == null) return;                              // 所有任务都还没到点

        boolean accepted;
        try {
            accepted = keeper.submitRound(acc.fpid, dueFn, "节奏");
        } catch (Throwable t) {
            accepted = false;
        }
        if (!accepted) {
            // 会话刚掉线/被暂停：60s 后重试，不消耗间隔
            stateStore.putLong(nextKey(acc.fpid, sid, dueFn), now + RETRY_MILLIS);
            stateStore.putLong("srv:cadlast:" + acc.fpid, now);
            lastDispatchInMemory.put(acc.fpid, now);
            if (busyWarned.add(acc.fpid + ":" + dueFn)) {
                FileLog.info("节奏任务暂未受理 fpid={} fn={}（会话不可用，60s 后重试）",
                        acc.fpid, dueFn);
            }
            return;
        }

        long intervalMs = TaskCadence.intervalSeconds(dueFn) * 1000L;
        long baseMs = nextIntervalForResult(acc, sid, dueFn, intervalMs, stateStore.snapshot());
        long jitter = baseMs >= 60_000L
                ? (long) ((random.nextDouble() * 0.2d - 0.1d) * baseMs) : 0L;
        stateStore.putLong(nextKey(acc.fpid, sid, dueFn), now + baseMs + jitter);
        stateStore.putLong("srv:cadlast:" + acc.fpid, now);
        lastDispatchInMemory.put(acc.fpid, now);
        busyWarned.remove(acc.fpid + ":" + dueFn);
        if (TaskCadence.intervalSeconds(dueFn) >= LOG_INTERVAL_THRESHOLD_SECONDS
                && startedLog.add(acc.fpid + ":" + dueFn)) {
            FileLog.info("节奏任务开始 fpid={} sid={} fn={} 间隔={}s",
                    acc.fpid, String.valueOf(sid), dueFn,
                    String.valueOf(TaskCadence.intervalSeconds(dueFn)));
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
