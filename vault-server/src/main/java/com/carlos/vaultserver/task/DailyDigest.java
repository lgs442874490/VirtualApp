package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.WeComNotifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 每日自动化日报（批次5 2026-10-23，批次6 2026-10-23 增强）：
 * 每天北京时间定点（默认 09:00）汇总<b>昨日自然日 00:00-24:00（北京时间）</b>的自动化结果
 * （批次6 由滚动 24h 改为自然日，便于“日报=昨天一天”的运营口径；跨零点边界见
 * {@link #naturalDayWindow(long)}），并清理 30 天前的 {@code srv:digest:*} 键；
 * 推送企业微信机器人（复用 {@link WeComNotifier} + {@code vault.json} 的 {@code watch} 配置）；
 * 无可用 webhook 时只写日志 + {@link StateStore} 键 {@code srv:digest:{yyyy-MM-dd}}（App/运维可查）。
 *
 * <p><b>数据只读</b>：汇总全部来自 {@link StateStore} 现有键——
 * <ul>
 *   <li>{@code log:{fpid}:{sid}:{fn}}：任务结果 JSON 数组（ts/cat/reason/detail，RoundRunner 落账）；</li>
 *   <li>{@code srv:cad:{fpid}:{sid}:{fn}}：节奏调度下次到期时刻键；</li>
 *   <li>{@code srv:lastrun:{fpid}}：整轮调度最近执行时刻；</li>
 *   <li>{@link ServerNotifyHistory} 容量统计（通知历史条数/账号数）。</li>
 * </ul>
 * 绝不发任何游戏协议、不连游戏服，副作用仅：企微 webhook（可选）+ 一条 state 键 + 日志。
 *
 * <p><b>配置</b>（环境变量优先系统属性，便于单测注入）：
 * <ul>
 *   <li>{@code VAULT_DIGEST=on/1/true} 显式开启（<b>默认关闭</b>：企微只发告警，不发日报）；</li>
 *   <li>{@code VAULT_DIGEST_TIME=HH:mm} 覆盖触发时刻（非法回落 09:00）。</li>
 * </ul>
 *
 * <p><b>线程/幂等</b>：{@link #start()} 起 daemon 线程，按北京时间算下一触发点、分段 sleep 到点；
 * 异常只记日志不退出循环；{@link #stop()} 中断退出。同一北京时间日期只生成/推送一次
 * （{@link #runOnce(long)} 以 state 键存在为幂等标记，先看标记再汇总）。
 *
 * <p><b>可测性</b>：触发时刻（{@link #nextDigestAt(long, String)}/{@link #catchUpDue(long, String)}）、
 * 幂等判定（{@link #shouldSend(StateStore, long)}）、聚合（{@link #collect}）、
 * 文案（{@link #format(DigestData)}）全为纯函数/纯读，注入时钟与 {@link StateStore} 即可单测。
 */
public final class DailyDigest {

    /** 默认触发时刻（北京时间）。 */
    public static final String DEFAULT_TIME = "09:00";
    /** 日报 state 键前缀：{@code srv:digest:{yyyy-MM-dd}}（幂等标记 + App/运维查询）。 */
    public static final String STATE_PREFIX = "srv:digest:";
    /** 开关环境变量：{@code off}（或 false/0）关闭。 */
    public static final String ENV_ENABLED = "VAULT_DIGEST";
    /** 时刻覆盖环境变量：{@code HH:mm}。 */
    public static final String ENV_TIME = "VAULT_DIGEST_TIME";

    /** 汇总窗口：近 24h（批次5 滚动窗口；批次6 起日报实际使用 {@link #naturalDayWindow(long)}，
     *  本常量保留给 {@link #collect(StateStore, long, long, int)} 兼容/单测）。 */
    static final long WINDOW_MILLIS = 24L * 3600_000L;
    /** 日报 state 键保留天数（批次6：每天 +1~1.5KB，保留最近 30 份=含今天 30 个自然日）。 */
    static final long KEEP_DIGEST_DAYS = 30L;
    /** 在线数首次采样为 0 时的重采等待（生产首推与登录同秒竞争）；仅 daemon loop 路径真实等待。 */
    static final long ONLINE_RETRY_MILLIS = 30_000L;
    /** 启动补发窗口：当天触发点已过 ≤12h 且当日未生成 → 立即补一次（服务重启不丢当日日报）。 */
    static final long CATCHUP_MILLIS = 12L * 3600_000L;
    /** 每个角色展示的奖励摘要条数。 */
    static final int TOP_REWARDS = 5;
    /** 每个角色展示的跳过原因 TOP 数。 */
    static final int TOP_SKIP_REASONS = 5;
    /** 企微 text 消息体量控制（企业微信单条 text 上限 2048 字节，这里留足余量）。 */
    static final int MAX_TEXT_CHARS = 1500;
    /** 日报最多展示角色行数（多角色账号池防刷屏）。 */
    static final int MAX_ROLE_LINES = 8;
    /** 单条奖励/原因文案截断长度。 */
    static final int MAX_ITEM_CHARS = 80;

    static final String LOG_PREFIX = "log:";
    static final String CADENCE_PREFIX = "srv:cad:";
    static final String LASTRUN_PREFIX = "srv:lastrun:";
    /** 批次10：自动派单统计键前缀（{@code srv:cad:stats:{yyyy-MM-dd}}，契约见 TaskCadenceScheduler:90-108）。 */
    static final String CADENCE_STATS_PREFIX = "srv:cad:stats:";
    /** 批次10：守护盟友账本键前缀（{@code srv:guardian:{fpid}:{sid}:ledger}）。 */
    static final String GUARDIAN_LEDGER_PREFIX = "srv:guardian:";
    /** 批次10：守护盟友 mod 状态键后缀（{@code mod:{fpid}:{sid}:guardian}）。 */
    static final String GUARDIAN_MOD_SUFFIX = ":guardian";
    /** 批次10：派单段「每账号」展示条数上限（防 1500 字符爆掉）。 */
    static final int TOP_CADENCE_ACCOUNTS = 5;

    private final VaultStore vaultStore;
    private final StateStore stateStore;
    private final WeComNotifier notifier;
    /** 当前在线账号数（Main 注入 keeper::status 统计；null = 未知，文案显示 -1）。 */
    private final IntSupplier onlineAccounts;
    /** 最近一次在线数采样时刻（epoch millis；0=未采样；批次6 用于 0 值文案标注）。 */
    private volatile long lastOnlineSampledAtMillis;
    private volatile boolean stopped;
    private volatile Thread thread;

    /**
     * @param vaultStore    身份库（只读 watch.webhookUrl / enabled / wecomEnabled）
     * @param stateStore    状态仓（只读 log:/srv:cad:/srv:lastrun:，写 srv:digest:{date}）
     * @param notifier      企微推送器（可 null = 不推送，只落日志/state；单测可注入 fake）
     * @param onlineAccounts 在线账号数供应器（可 null）
     */
    public DailyDigest(VaultStore vaultStore, StateStore stateStore, WeComNotifier notifier,
                       IntSupplier onlineAccounts) {
        this.vaultStore = vaultStore;
        this.stateStore = stateStore;
        this.notifier = notifier;
        this.onlineAccounts = onlineAccounts;
    }

    // =======================================================================
    // 配置（纯函数；环境变量优先系统属性，便于单测注入）
    // =======================================================================

    /** 读配置：系统属性优先（单测可注入），其次环境变量；返回去空白后字符串（缺失=空串）。 */
    static String setting(String key) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) v = System.getenv(key);
        return v == null ? "" : v.trim();
    }

    /**
     * 日报开关：<b>缺省关闭</b>（2026-10-27 用户要求：企业微信只发告警，不发日报/汇总）；
     * 仅 {@code VAULT_DIGEST=on/1/true} 显式开启。
     */
    public static boolean enabled() {
        String v = setting(ENV_ENABLED);
        return "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * 显式关闭（{@code off/false/0}）：只用于 {@link #runOnce} 的手动触发闸门。
     * 缺省（未设置环境变量）时手动 runOnce 允许执行（单测/运维补发），但 daemon 线程
     * 必须由 {@link #enabled()} 显式开启才启动。
     */
    static boolean explicitlyDisabled() {
        String v = setting(ENV_ENABLED);
        return "off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || "0".equals(v);
    }

    /** 触发时刻（北京时间 HH:mm）：{@code VAULT_DIGEST_TIME} 合法则用之，否则 {@link #DEFAULT_TIME}。 */
    public static String digestTime() {
        return normalizeTime(setting(ENV_TIME));
    }

    /** 规范化 HH:mm（非法回落 09:00）。 */
    static String normalizeTime(String hhmm) {
        int[] hm = parseHm(hhmm);
        return String.format(Locale.ROOT, "%02d:%02d", hm[0], hm[1]);
    }

    /** 解析 HH:mm → {hour, minute}；非法（含空/越界/非数字）→ {9, 0}。 */
    static int[] parseHm(String hhmm) {
        if (hhmm != null) {
            String[] parts = hhmm.trim().split(":");
            if (parts.length == 2) {
                try {
                    int h = Integer.parseInt(parts[0].trim());
                    int m = Integer.parseInt(parts[1].trim());
                    if (h >= 0 && h <= 23 && m >= 0 && m <= 59) return new int[]{h, m};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new int[]{9, 0};
    }

    // =======================================================================
    // 触发/幂等（纯函数）
    // =======================================================================

    /** 北京时间日期键 {@code yyyy-MM-dd}。 */
    public static String dayKey(long nowMillis) {
        return Beijing.fmt("yyyy-MM-dd").format(new Date(nowMillis));
    }

    /** 北京时间当日 00:00:00.000（epoch millis）。纯函数（注入 now 可测）。 */
    static long dayStartMillis(long nowMillis) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.setTimeInMillis(nowMillis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 自然日统计窗口（批次6）：返回 {@code [昨日 00:00, 今日 00:00)}（北京时间，epoch millis）。
     * 09:00 触发时即“昨日 00:00-24:00”；GMT+8 无夏令时，跨日 -24h 安全。纯函数可测跨零点。
     */
    static long[] naturalDayWindow(long nowMillis) {
        long todayStart = dayStartMillis(nowMillis);
        return new long[]{todayStart - 24L * 3600_000L, todayStart};
    }

    /** 北京时间当天 HH:mm 的时刻（epoch millis；可能早于 now）。 */
    static long todayDigestAt(long nowMillis, String hhmm) {
        int[] hm = parseHm(hhmm);
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.setTimeInMillis(nowMillis);
        c.set(Calendar.HOUR_OF_DAY, hm[0]);
        c.set(Calendar.MINUTE, hm[1]);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 下一个触发时刻（epoch millis）：今天 HH:mm 尚未到则今天，否则明天 HH:mm。
     * 纯函数（注入 now 可测；GMT+8 无夏令时，跨日 +24h 安全）。
     */
    public static long nextDigestAt(long nowMillis, String hhmm) {
        long today = todayDigestAt(nowMillis, hhmm);
        return today > nowMillis ? today : today + 24L * 3600_000L;
    }

    /** 启动补发判定：当天触发点已过且未超过 {@link #CATCHUP_MILLIS}（再配合 {@link #shouldSend}）。 */
    public static boolean catchUpDue(long nowMillis, String hhmm) {
        long today = todayDigestAt(nowMillis, hhmm);
        long delta = nowMillis - today;
        return delta >= 0L && delta <= CATCHUP_MILLIS;
    }

    /** 幂等：该北京时间日期没有 {@code srv:digest:{date}} 键才需要生成。 */
    public static boolean shouldSend(StateStore store, long nowMillis) {
        return store != null && store.getString(STATE_PREFIX + dayKey(nowMillis)) == null;
    }

    // =======================================================================
    // 聚合（只读 StateStore；不新增任何采集/协议请求）
    // =======================================================================

    /** 单账号-角色聚合结果。 */
    public static final class RoleDigest {
        public final String fpid;
        public final String sid;
        /** 近 24h 顶层任务执行次数（log:{fpid}:{sid}:{fn}，fn 不含子项冒号）。 */
        public int runs;
        /** 顶层任务 cat=CLAIMED 次数。 */
        public int claimed;
        /** 顶层任务 cat=SKIPPED 次数。 */
        public int skipped;
        /** 顶层任务 cat=ERROR/DEGRADED/SESSION_CLOSED 次数（异常）。 */
        public int failed;
        /** 奖励摘要（detail，含子项 CLAIMED 的 reason 摘要；去重保序，≤TOP_REWARDS）。 */
        public final List<String> rewards = new ArrayList<>();
        /** 跳过原因归并：原因 → 次数（含子项 SKIPPED；按次数降序取 TOP_SKIP_REASONS）。 */
        public final Map<String, Integer> skipReasons = new LinkedHashMap<>();

        RoleDigest(String fpid, String sid) {
            this.fpid = fpid;
            this.sid = sid;
        }

        /** 角色展示键：fpid:sid。 */
        public String label() {
            return fpid + ":" + sid;
        }

        /** 跳过原因 TOP N（次数降序、同次数按首次出现序）。 */
        public List<Map.Entry<String, Integer>> topSkipReasons(int n) {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(skipReasons.entrySet());
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
        }
    }

    /** 一次日报的聚合结果（纯数据，供 {@link #format(DigestData)}/单测断言）。 */
    public static final class DigestData {
        public long nowMillis;
        public String day = "";
        /** 自然日统计窗口 [start,end)（epoch millis；0=未标注，兼容批次5 直接构造的调用方）。 */
        public long windowStartMillis;
        public long windowEndMillis;
        /** 在线账号数（-1 = 未知/未注入）。 */
        public int onlineAccounts = -1;
        /** 在线数采样时刻（epoch millis；0=未采样；为 0 时文案标注“采样时点”）。 */
        public long onlineSampledAtMillis;
        /** 有任务日志的账号-角色数。 */
        public int roleCount;
        public int totalRuns;
        public int totalClaimed;
        public int totalSkipped;
        public int totalFailed;
        /** {@code srv:cad:*} 键数（节奏调度待派发任务数）。 */
        public int cadenceKeys;
        /** 最近整轮时刻：fpid → srv:lastrun（最近在前）。 */
        public final Map<String, Long> lastRuns = new LinkedHashMap<>();
        /** 通知历史容量（ServerNotifyHistory：条数/账号数）。 */
        public int notifyEntries;
        public int notifyAccounts;
        /** 批次10：自动派单段（null=统计缺失 → 该段省略，不报错）。 */
        public CadenceDigest cadence;
        /** 批次10：守护盟友段（null=无 guardian 状态 → 该段省略）。 */
        public GuardianDigest guardian;
        public final List<RoleDigest> roles = new ArrayList<>();

        /** 最近整轮时刻（毫秒；0=无）。 */
        public long latestLastRun() {
            long max = 0L;
            for (Long v : lastRuns.values()) max = Math.max(max, v == null ? 0L : v);
            return max;
        }
    }

    // =======================================================================
    // 批次10：日报追加段（自动派单 / 守护盟友）——纯数据 + 纯解析
    // =======================================================================

    /** 自动派单段数据（来源 {@code srv:cad:stats:{yyyy-MM-dd}}；键型契约见 TaskCadenceScheduler:90-108）。 */
    public static final class CadenceDigest {
        /** 统计日期（yyyy-MM-dd；日报取“统计窗口那一天”的 stats）。 */
        public String day = "";
        /** 当日成功派单数。 */
        public long dispatch;
        /** 有派单记录的账号数。 */
        public int accounts;
        /** 全局每分钟预算（0=不限）。 */
        public int globalMax;
        /** 每账号派单数（fpid → count）。 */
        public final Map<String, Long> byAccount = new LinkedHashMap<>();
        /** 各闸门跳过次数（原因 → 次数）。 */
        public final Map<String, Long> skip = new LinkedHashMap<>();

        /** 跳过原因 Top N：次数降序、同次数按原因名升序（确定性，便于日报稳定输出/单测）。 */
        public List<Map.Entry<String, Long>> topSkips(int n) {
            List<Map.Entry<String, Long>> list = new ArrayList<>(skip.entrySet());
            list.sort((a, b) -> {
                int c = Long.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
        }

        /** 每账号派单数 Top N：次数降序、同次数按 fpid 升序。 */
        public List<Map.Entry<String, Long>> topAccounts(int n) {
            List<Map.Entry<String, Long>> list = new ArrayList<>(byAccount.entrySet());
            list.sort((a, b) -> {
                int c = Long.compare(b.getValue(), a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            });
            return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
        }
    }

    /** 守护盟友段数据（来源 {@code srv:guardian:{fpid}:{sid}:ledger} / {@code mod:{fpid}:{sid}:guardian}）。 */
    public static final class GuardianDigest {
        /** 有守护状态的账号数（fpid:sid 去重）。 */
        public int accounts;
        /** 账本总条数（≤50/账号）。 */
        public int ledgerEntries;
        public int dryRun;
        public int launched;
        public int launchFailed;
        public int recalled;
        /** 最近一次事件时刻（epoch millis；0=未知）。 */
        public long lastTs;
        public String lastEvent = "";
        public String lastNote = "";
        public String lastAccount = "";

        /** 合并另一份段数据（同账号 ledger+mod 兜底、多账号汇总）。 */
        void merge(GuardianDigest o) {
            if (o == null) return;
            ledgerEntries += o.ledgerEntries;
            dryRun += o.dryRun;
            launched += o.launched;
            launchFailed += o.launchFailed;
            recalled += o.recalled;
            if (o.lastTs >= lastTs && o.lastTs > 0L) {
                lastTs = o.lastTs;
                lastEvent = o.lastEvent;
                lastNote = o.lastNote;
                lastAccount = o.lastAccount;
            }
        }
    }

    /** 解析 {@code srv:cad:stats:{date}} JSON → {@link CadenceDigest}；缺失/坏 JSON → null（段落省略）。 */
    static CadenceDigest parseCadenceStats(Object raw, String day) {
        if (raw == null) return null;
        try {
            JSONObject o = raw instanceof JSONObject ? (JSONObject) raw
                    : new JSONObject(String.valueOf(raw));
            CadenceDigest c = new CadenceDigest();
            c.day = day == null || day.isEmpty() ? o.optString("date", "") : day;
            c.dispatch = Math.max(0L, o.optLong("dispatch", 0L));
            c.globalMax = Math.max(0, o.optInt("globalMax", 0));
            JSONObject by = o.optJSONObject("byAccount");
            if (by != null) {
                for (String k : by.keySet()) {
                    long v = by.optLong(k, 0L);
                    if (v > 0L) c.byAccount.put(k, v);
                }
            }
            JSONObject sk = o.optJSONObject("skip");
            if (sk != null) {
                for (String k : sk.keySet()) {
                    long v = sk.optLong(k, 0L);
                    if (v > 0L) c.skip.put(k, v);
                }
            }
            c.accounts = c.byAccount.size();
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析守护盟友账本 JSON 数组（最新在前）：dry_run/launched/launch_failed/recalled 计数 +
     * 最近一次（ts 最大）原因。空/坏数组 → null（该段省略）。
     */
    static GuardianDigest parseGuardianLedger(Object raw) {
        JSONArray arr = jsonArray(raw);
        if (arr.length() == 0) return null;
        GuardianDigest g = new GuardianDigest();
        g.ledgerEntries = arr.length();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String ev = e.optString("ev", "");
            switch (ev) {
                case "dry_run":
                    g.dryRun++;
                    break;
                case "launched":
                    g.launched++;
                    break;
                case "launch_failed":
                    g.launchFailed++;
                    break;
                case "recalled":
                case "recall_failed":
                case "recall_error":
                    g.recalled++;
                    break;
                default:
                    break;
            }
            long ts = e.optLong("ts", 0L);
            if (ts >= g.lastTs && ts > 0L) {
                g.lastTs = ts;
                g.lastEvent = ev;
                g.lastNote = trimTo(e.optString("note", ""), MAX_ITEM_CHARS);
            }
        }
        return g;
    }

    /** 解析 {@code mod:{fpid}:{sid}:guardian} 兜底（无账本时的最近一次原因）；空 → null。 */
    static GuardianDigest parseGuardianMod(Object raw) {
        if (raw == null) return null;
        try {
            JSONObject o = raw instanceof JSONObject ? (JSONObject) raw
                    : new JSONObject(String.valueOf(raw));
            long ts = o.optLong("last_ts", 0L);
            String last = trimTo(o.optString("last", ""), MAX_ITEM_CHARS);
            if (ts <= 0L && last.isEmpty()) return null;
            GuardianDigest g = new GuardianDigest();
            g.lastTs = ts;
            g.lastEvent = "state";
            g.lastNote = last;
            return g;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 守护键 → 账号键 {@code fpid:sid}（ledger 取第 3/4 段；mod 取第 2/3 段）；无法识别 → null。 */
    static String guardianAccountKey(String key) {
        if (key == null) return null;
        String[] p = key.split(":");
        if (key.startsWith(GUARDIAN_LEDGER_PREFIX) && p.length >= 5) return p[2] + ":" + p[3];
        if (key.startsWith("mod:") && p.length >= 4) return p[1] + ":" + p[2];
        return null;
    }

    /**
     * 聚合近 {@code windowMillis} 的 StateStore 状态（批次5 滚动窗口口径，纯读；保留兼容/单测）。
     *
     * @param onlineAccounts 在线账号数（-1/负数 = 未知）
     */
    public static DigestData collect(StateStore store, long nowMillis, long windowMillis,
                                     int onlineAccounts) {
        long from = nowMillis - Math.max(0L, windowMillis);
        return collectBetween(store, from, nowMillis + 1L, nowMillis, onlineAccounts);
    }

    /**
     * 聚合“昨日自然日 00:00-24:00（北京时间）”（批次6 日报口径，纯读）。
     * 窗口右开区间：{@code [昨日00:00, 今日00:00)}，不含今天 00:00 之后的日志。
     */
    public static DigestData collectNaturalDay(StateStore store, long nowMillis, int onlineAccounts) {
        long[] win = naturalDayWindow(nowMillis);
        return collectBetween(store, win[0], win[1], nowMillis, onlineAccounts);
    }

    /**
     * 聚合 {@code [fromMillis, toMillis)} 窗口的 StateStore 状态（纯读；所有窗口口径的唯一实现）。
     */
    static DigestData collectBetween(StateStore store, long fromMillis, long toMillis,
                                     long nowMillis, int onlineAccounts) {
        DigestData d = new DigestData();
        d.nowMillis = nowMillis;
        d.day = dayKey(nowMillis);
        d.windowStartMillis = fromMillis;
        d.windowEndMillis = toMillis;
        d.onlineAccounts = onlineAccounts;
        long from = fromMillis;
        Map<String, RoleDigest> roles = new LinkedHashMap<>();
        Object cadenceStatsRaw = null;   // 统计窗口那一天（昨日）的派单统计
        GuardianDigest guardian = null;
        Set<String> guardianAccounts = new LinkedHashSet<>();
        if (store != null) {
            Map<String, Object> snap = store.snapshot();
            for (Map.Entry<String, Object> e : snap.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                if (key.startsWith(LOG_PREFIX)) {
                    collectLogEntries(d, roles, key, e.getValue(), from, toMillis);
                } else if (key.startsWith(CADENCE_STATS_PREFIX)) {
                    // 批次10：派单统计键只取“统计窗口那一天”，其它日期不混入；也不计入 cadenceKeys
                    if (dayKey(fromMillis).equals(key.substring(CADENCE_STATS_PREFIX.length()))) {
                        cadenceStatsRaw = e.getValue();
                    }
                } else if (key.startsWith(CADENCE_PREFIX)) {
                    d.cadenceKeys++;
                } else if (key.startsWith(LASTRUN_PREFIX)) {
                    String fpid = key.substring(LASTRUN_PREFIX.length());
                    long ts = longOf(e.getValue());
                    if (!fpid.isEmpty() && ts > 0L) d.lastRuns.put(fpid, ts);
                } else if (key.startsWith(GUARDIAN_LEDGER_PREFIX) && key.endsWith(":ledger")) {
                    // 批次10：守护盟友账本（dry_run/实发/最近原因）
                    GuardianDigest g = parseGuardianLedger(e.getValue());
                    if (g != null) {
                        if (guardian == null) guardian = new GuardianDigest();
                        guardian.merge(g);
                        String account = guardianAccountKey(key);
                        if (account != null) guardianAccounts.add(account);
                    }
                } else if (key.startsWith("mod:") && key.endsWith(GUARDIAN_MOD_SUFFIX)) {
                    // 批次10：仅当同账号没有账本时用 mod 的 last/last_ts 兜底
                    String account = guardianAccountKey(key);
                    if (account != null && !guardianAccounts.contains(account)) {
                        GuardianDigest g = parseGuardianMod(e.getValue());
                        if (g != null) {
                            if (guardian == null) guardian = new GuardianDigest();
                            guardian.merge(g);
                            guardianAccounts.add(account);
                        }
                    }
                }
            }
        }
        if (guardian != null) guardian.accounts = guardianAccounts.size();
        d.guardian = guardian;
        d.cadence = parseCadenceStats(cadenceStatsRaw, dayKey(fromMillis));
        List<RoleDigest> sorted = new ArrayList<>(roles.values());
        sorted.sort(Comparator.comparing(RoleDigest::label));
        d.roles.addAll(sorted);
        d.roleCount = d.roles.size();
        for (RoleDigest r : d.roles) {
            d.totalRuns += r.runs;
            d.totalClaimed += r.claimed;
            d.totalSkipped += r.skipped;
            d.totalFailed += r.failed;
        }
        d.notifyEntries = ServerNotifyHistory.totalEntries();
        d.notifyAccounts = ServerNotifyHistory.accountCount();
        return d;
    }

    /** 解析一条 {@code log:{fpid}:{sid}:{fn}}（fn 可含子项冒号）并聚合（仅窗口内 ts）。 */
    private static void collectLogEntries(DigestData d, Map<String, RoleDigest> roles, String key,
                                          Object raw, long from, long toExclusive) {
        String[] parts = key.split(":", 4);
        if (parts.length < 4) return;
        String fpid = parts[1];
        String sid = parts[2];
        String fn = parts[3];
        if (fpid.isEmpty() || sid.isEmpty() || fn.isEmpty()) return;
        JSONArray arr = jsonArray(raw);
        boolean sub = fn.indexOf(':') >= 0;   // 子项日志（fn:sub）：只补充奖励/原因，不计任务执行次数
        RoleDigest rd = null;                 // 懒创建：窗口内没有有效条目的角色不进日报
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long ts = o.optLong("ts", 0L);
            if (ts < from || ts >= toExclusive) continue;   // 只看统计窗口（右开区间；历史保留 50 条/任务）
            String cat = o.optString("cat", "");
            String reason = trimTo(o.optString("reason", ""), MAX_ITEM_CHARS);
            String detail = trimTo(o.optString("detail", ""), MAX_ITEM_CHARS);
            if (!sub) {
                if (rd == null) rd = roles.computeIfAbsent(fpid + ":" + sid,
                        k -> new RoleDigest(fpid, sid));
                rd.runs++;
                switch (cat) {
                    case "CLAIMED":
                        rd.claimed++;
                        addUnique(rd.rewards, detail.isEmpty() ? reason : detail);
                        break;
                    case "SKIPPED":
                        rd.skipped++;
                        addSkipReason(rd, reason);
                        break;
                    case "ERROR":
                    case "DEGRADED":
                    case "SESSION_CLOSED":
                        rd.failed++;
                        break;
                    default:
                        break;
                }
            } else {
                // 子项：CLAIMED 的 reason 就是“子项：奖励”摘要；SKIPPED 原因最能说明“哪里一直跳过”
                if ("CLAIMED".equals(cat)) {
                    if (rd == null) rd = roles.computeIfAbsent(fpid + ":" + sid,
                            k -> new RoleDigest(fpid, sid));
                    addUnique(rd.rewards, reason.isEmpty() ? detail : reason);
                } else if ("SKIPPED".equals(cat)) {
                    if (rd == null) rd = roles.computeIfAbsent(fpid + ":" + sid,
                            k -> new RoleDigest(fpid, sid));
                    addSkipReason(rd, reason);
                }
            }
        }
    }

    private static JSONArray jsonArray(Object raw) {
        if (raw == null) return new JSONArray();
        try {
            if (raw instanceof JSONArray) return (JSONArray) raw;
            return new JSONArray(String.valueOf(raw));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static void addSkipReason(RoleDigest rd, String reason) {
        String r = trimTo(reason, MAX_ITEM_CHARS);
        if (r.isEmpty()) r = "（无原因）";
        rd.skipReasons.merge(r, 1, Integer::sum);
    }

    /** 去重保序追加，最多 {@link #TOP_REWARDS} 条（奖励摘要）。 */
    private static void addUnique(List<String> out, String item) {
        String s = trimTo(item, MAX_ITEM_CHARS);
        if (s.isEmpty() || out.contains(s) || out.size() >= TOP_REWARDS) return;
        out.add(s);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    // =======================================================================
    // 文案（纯函数）
    // =======================================================================

    /**
     * 生成企微 text 文案（纯函数）：总览 + 每角色 运行/领取/跳过/异常 + 奖励 TOP + 跳过原因 TOP。
     * 总长度受 {@link #MAX_TEXT_CHARS} 约束（企业微信单条上限 2048 字节）。
     */
    public static String format(DigestData d) {
        StringBuilder sb = new StringBuilder(1024);
        if (d == null) return "【自动化日报】（无数据）";
        sb.append("【自动化日报】").append(d.day);
        sb.append(String.format(Locale.ROOT, " %s\n", Beijing.fmt("HH:mm")
                .format(new Date(d.nowMillis))));
        if (d.windowStartMillis > 0L && d.windowEndMillis > d.windowStartMillis) {
            // 批次6：明示统计区间（自然日 → 昨日 00:00-24:00），避免读者误以为是滚动 24h
            sb.append("统计窗口 ").append(Beijing.fmt("MM-dd HH:mm").format(new Date(d.windowStartMillis)))
                    .append(" — ")
                    .append(Beijing.fmt("MM-dd HH:mm").format(new Date(d.windowEndMillis)))
                    .append("（北京时间）\n");
        }
        sb.append("在线账号 ").append(d.onlineAccounts < 0 ? "?" : String.valueOf(d.onlineAccounts));
        if (d.onlineAccounts == 0 && d.onlineSampledAtMillis > 0L) {
            // 批次6：0 在线多为“日报与登录同秒竞争”，标注采样时点，便于运营判断
            sb.append("（采样 ").append(Beijing.fmt("HH:mm")
                    .format(new Date(d.onlineSampledAtMillis))).append("，可能仍在登录）");
        }
        sb.append(" · 角色 ").append(d.roleCount)
                .append(" · 任务 ").append(d.totalRuns)
                .append(" · 领取 ").append(d.totalClaimed)
                .append(" · 跳过 ").append(d.totalSkipped)
                .append(" · 异常 ").append(d.totalFailed).append('\n');
        sb.append("节奏键 ").append(d.cadenceKeys)
                .append(" · 通知历史 ").append(d.notifyEntries)
                .append(" 条/").append(d.notifyAccounts).append(" 账号");
        long lastRun = d.latestLastRun();
        if (lastRun > 0L) {
            sb.append(" · 最近整轮 ").append(Beijing.fmt("MM-dd HH:mm")
                    .format(new Date(lastRun)));
        }
        sb.append('\n');
        // 批次10：自动派单段（统计缺失则整段省略，不报错）
        if (d.cadence != null) {
            CadenceDigest c = d.cadence;
            sb.append("自动派单 ").append(c.day).append("：派单 ").append(c.dispatch)
                    .append(" · 账号 ").append(c.accounts)
                    .append(" · 全局预算 ").append(c.globalMax <= 0 ? "不限" : c.globalMax + "/min");
            List<Map.Entry<String, Long>> skips = c.topSkips(3);
            if (!skips.isEmpty()) {
                sb.append(" · 跳过TOP ");
                for (int i = 0; i < skips.size(); i++) {
                    if (i > 0) sb.append('；');
                    sb.append(skips.get(i).getKey()).append('×').append(skips.get(i).getValue());
                }
            }
            sb.append('\n');
            List<Map.Entry<String, Long>> accounts = c.topAccounts(TOP_CADENCE_ACCOUNTS);
            if (!accounts.isEmpty()) {
                sb.append("  每账号：");
                for (int i = 0; i < accounts.size(); i++) {
                    if (i > 0) sb.append('；');
                    sb.append(accounts.get(i).getKey()).append('×').append(accounts.get(i).getValue());
                }
                if (c.byAccount.size() > accounts.size()) sb.append(" …");
                sb.append('\n');
            }
        }
        // 批次10：守护盟友段（无 guardian 状态则整段省略）
        if (d.guardian != null) {
            GuardianDigest g = d.guardian;
            sb.append("守护盟友：dry_run ").append(g.dryRun)
                    .append(" · 实发 ").append(g.launched);
            if (g.launchFailed > 0) sb.append("（失败 ").append(g.launchFailed).append("）");
            if (g.recalled > 0) sb.append(" · 撤防 ").append(g.recalled);
            if (!g.lastEvent.isEmpty()) sb.append(" · 最近 ").append(trimTo(g.lastEvent, 20));
            if (!g.lastNote.isEmpty()) sb.append("：").append(trimTo(g.lastNote, 40));
            sb.append('\n');
        }
        if (d.roles.isEmpty()) {
            sb.append("统计窗口内无任务日志\n");
        }
        int lines = 0;
        for (RoleDigest r : d.roles) {
            if (lines++ >= MAX_ROLE_LINES) {
                sb.append("… 另有 ").append(d.roles.size() - MAX_ROLE_LINES).append(" 个角色\n");
                break;
            }
            sb.append(String.format(Locale.ROOT, "— %s 运行 %d · 领取 %d · 跳过 %d · 异常 %d\n",
                    r.label(), r.runs, r.claimed, r.skipped, r.failed));
            if (!r.rewards.isEmpty()) {
                sb.append("  领取：").append(String.join("；", r.rewards)).append('\n');
            }
            List<Map.Entry<String, Integer>> skips = r.topSkipReasons(TOP_SKIP_REASONS);
            if (!skips.isEmpty()) {
                sb.append("  跳过TOP：");
                boolean first = true;
                for (Map.Entry<String, Integer> s : skips) {
                    if (!first) sb.append('；');
                    first = false;
                    sb.append(s.getKey()).append('×').append(s.getValue());
                }
                sb.append('\n');
            }
        }
        String text = sb.toString().trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS - 1) + "…";
        }
        return text;
    }

    // =======================================================================
    // 执行（幂等；单测注入 nowMillis）
    // =======================================================================

    /** 在线数等待器：生产传 {@code Thread::sleep}（0 值重采前等 30s）；单测可注入 fake。 */
    interface Waiter {
        void await(long millis) throws InterruptedException;
    }

    /** 生产等待器（daemon 线程内 30s 重采）。 */
    static final Waiter THREAD_WAITER = Thread::sleep;

    /**
     * 采样在线账号数（批次6 竞态修复）：首次为 0 且提供 waiter 时等待
     * {@link #ONLINE_RETRY_MILLIS} 后重采一次（生产首推“在线=0 角色=2”是与登录同秒竞争）。
     * waiter=null（公开 runOnce/单测路径）不等待，只采一次。
     */
    int sampleOnlineAccounts(Waiter waiter) {
        long sampledAt = System.currentTimeMillis();
        if (onlineAccounts == null) {
            lastOnlineSampledAtMillis = sampledAt;
            return -1;
        }
        int n = onlineAccounts.getAsInt();
        if (n == 0 && waiter != null) {
            try {
                waiter.await(ONLINE_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sampledAt = System.currentTimeMillis();
            n = onlineAccounts.getAsInt();
            FileLog.info("自动化日报首次采样在线账号=0，等待 {}s 重采后={}",
                    String.valueOf(ONLINE_RETRY_MILLIS / 1000L), String.valueOf(n));
        }
        lastOnlineSampledAtMillis = sampledAt;
        return n;
    }

    /**
     * 生成一次日报（幂等：当日 state 键已存在直接跳过）。公开入口不等待在线数重采（单测/手动触发快速返回）。
     *
     * @return true = 已尝试推送且企微确认送达；false = 跳过/仅落 state（无 webhook 或推送失败）
     */
    public boolean runOnce(long nowMillis) {
        return runOnce(nowMillis, null);
    }

    /**
     * 生成一次日报（批次6：自然日窗口 + 在线数 0 值等待重采 + 30 天 digest 历史清理）。
     *
     * @param waiter 在线数 0 时的等待器（daemon loop 传 {@link #THREAD_WAITER}；null=不等待）
     */
    boolean runOnce(long nowMillis, Waiter waiter) {
        if (explicitlyDisabled()) return false;
        if (stateStore == null) {   // 无状态仓：无法幂等/落盘，直接放弃（不影响主流程）
            FileLog.warn("自动化日报缺少 StateStore，跳过本次生成");
            return false;
        }
        if (!shouldSend(stateStore, nowMillis)) {
            FileLog.info("自动化日报 {} 今日已生成过，跳过（幂等）", dayKey(nowMillis));
            return false;
        }
        int online = sampleOnlineAccounts(waiter);
        // 批次6：统计「昨日 00:00-24:00（北京时间）」自然日窗口
        DigestData data = collectNaturalDay(stateStore, nowMillis, online);
        data.onlineSampledAtMillis = lastOnlineSampledAtMillis > 0L
                ? lastOnlineSampledAtMillis : nowMillis;
        if (data.cadence == null) {
            // 批次10：数据缺失时段落省略并留痕（不报错、不占企微字符）
            FileLog.info("自动化日报：{} 自动派单统计缺失（{}{} 未落盘），该段省略",
                    data.day, CADENCE_STATS_PREFIX, dayKey(data.windowStartMillis));
        }
        String text = format(data);
        boolean sent = trySend(text);
        stateStore.putString(STATE_PREFIX + data.day, text);   // 幂等标记 + App/运维可查
        int purged = purgeOldDigests(stateStore, nowMillis);
        FileLog.info("自动化日报{} day={} 在线账号={} 角色={} 任务={} 领取={} 跳过={} 异常={} 节奏键={} 清理={}",
                sent ? "已推送企微；" : "仅落日志/state（无可用 webhook）；",
                data.day, String.valueOf(data.onlineAccounts), String.valueOf(data.roleCount),
                String.valueOf(data.totalRuns), String.valueOf(data.totalClaimed),
                String.valueOf(data.totalSkipped), String.valueOf(data.totalFailed),
                String.valueOf(data.cadenceKeys), String.valueOf(purged));
        return sent;
    }

    // =======================================================================
    // 批次6：digest 历史清理（每天 +1~1.5KB 且此前无清理）
    // =======================================================================

    /** 需清理的 {@code srv:digest:yyyy-MM-dd} 键：早于“含今天共 30 个自然日”的窗口。纯函数可测。 */
    static List<String> expiredDigestKeys(java.util.Collection<String> keys, long nowMillis) {
        List<String> out = new ArrayList<>();
        if (keys == null || keys.isEmpty()) return out;
        String cutoff = dayKey(dayStartMillis(nowMillis)
                - (KEEP_DIGEST_DAYS - 1L) * 24L * 3600_000L);
        for (String key : keys) {
            if (key == null || !key.startsWith(STATE_PREFIX)) continue;
            String date = key.substring(STATE_PREFIX.length());
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) continue;   // 只清规范日期键，非法键不误删
            if (date.compareTo(cutoff) < 0) out.add(key);
        }
        return out;
    }

    /** 清理 30 天前的 digest 键（保留最近 30 份）；返回删除条数。 */
    static int purgeOldDigests(StateStore store, long nowMillis) {
        if (store == null) return 0;
        List<String> expired = expiredDigestKeys(store.snapshot().keySet(), nowMillis);
        for (String key : expired) store.remove(key);
        if (!expired.isEmpty()) {
            FileLog.info("自动化日报历史清理：删除 {} 个 30 天前 srv:digest 键", String.valueOf(expired.size()));
        }
        return expired.size();
    }

    /** 企微推送（复用 watch 配置；无 webhook/未启用/异常 → false，绝不抛出）。 */
    boolean trySend(String text) {
        if (notifier == null || vaultStore == null || text == null || text.isEmpty()) return false;
        try {
            VaultStore.WatchConfig wc = vaultStore.watchConfig();
            if (wc == null || !wc.enabled || !wc.wecomEnabled) return false;
            String url = wc.webhookUrl == null ? "" : wc.webhookUrl.trim();
            if (url.isEmpty()) return false;
            return notifier.send(url, text);
        } catch (Throwable t) {
            FileLog.warn("自动化日报推送异常（已降级为仅落 state）：{}",
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            return false;
        }
    }

    // =======================================================================
    // daemon 线程（Main 启动/停止）
    // =======================================================================

    /** 启动 daemon 日报线程（缺省关闭；{@code VAULT_DIGEST=on/1/true} 才启动）。幂等。 */
    public void start() {
        if (!enabled()) {
            FileLog.info("自动化日报已关闭（缺省关闭，仅 {}=on/1/true 开启；企微只发告警）", ENV_ENABLED);
            return;
        }
        if (thread != null && thread.isAlive()) return;
        stopped = false;   // 允许 stop 后再次 start（重启场景）
        String hhmm = digestTime();
        FileLog.info("自动化日报已启用：每日 {}（北京时间）；无 webhook 时仅落 state/日志", hhmm);
        Thread t = new Thread(() -> loop(hhmm), "daily-digest");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** 停止日报线程（shutdown hook 调用；可重复调用）。 */
    public void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** 线程主循环：启动时同日补发（窗口内）→ 之后按下一个 HH:mm 触发；异常不退出。 */
    private void loop(String hhmm) {
        try {
            long now = System.currentTimeMillis();
            if (catchUpDue(now, hhmm) && shouldSend(stateStore, now)) {
                safeRunOnce(now, THREAD_WAITER);
            }
            while (!stopped) {
                long at = nextDigestAt(System.currentTimeMillis(), hhmm);
                long wait = at - System.currentTimeMillis();
                Thread.sleep(Math.max(500L, Math.min(wait, 60_000L)));   // 分段 sleep，stop 可及时退出
                if (stopped) return;
                if (System.currentTimeMillis() < at) continue;
                safeRunOnce(System.currentTimeMillis(), THREAD_WAITER);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void safeRunOnce(long nowMillis, Waiter waiter) {
        try {
            runOnce(nowMillis, waiter);
        } catch (Throwable t) {
            FileLog.warn("自动化日报生成失败（忽略，不影响任务/会话）：{}",
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }
}
