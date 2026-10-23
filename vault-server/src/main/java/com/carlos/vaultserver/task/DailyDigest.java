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
 * 每日自动化日报（批次5 2026-10-23）：每天北京时间定点（默认 09:00）汇总近 24h 自动化结果，
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
 *   <li>{@code VAULT_DIGEST=off} 关闭（默认开）；</li>
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

    /** 汇总窗口：近 24h。 */
    static final long WINDOW_MILLIS = 24L * 3600_000L;
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

    private final VaultStore vaultStore;
    private final StateStore stateStore;
    private final WeComNotifier notifier;
    /** 当前在线账号数（Main 注入 keeper::status 统计；null = 未知，文案显示 -1）。 */
    private final IntSupplier onlineAccounts;
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

    /** 日报开关：{@code VAULT_DIGEST=off/false/0} 关闭，缺省开启。 */
    public static boolean enabled() {
        String v = setting(ENV_ENABLED);
        return !("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || "0".equals(v));
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
        /** 在线账号数（-1 = 未知/未注入）。 */
        public int onlineAccounts = -1;
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
        public final List<RoleDigest> roles = new ArrayList<>();

        /** 最近整轮时刻（毫秒；0=无）。 */
        public long latestLastRun() {
            long max = 0L;
            for (Long v : lastRuns.values()) max = Math.max(max, v == null ? 0L : v);
            return max;
        }
    }

    /**
     * 聚合近 {@code windowMillis} 的 StateStore 状态（纯读）。
     *
     * @param onlineAccounts 在线账号数（-1/负数 = 未知）
     */
    public static DigestData collect(StateStore store, long nowMillis, long windowMillis,
                                     int onlineAccounts) {
        DigestData d = new DigestData();
        d.nowMillis = nowMillis;
        d.day = dayKey(nowMillis);
        d.onlineAccounts = onlineAccounts;
        long from = nowMillis - Math.max(0L, windowMillis);
        Map<String, RoleDigest> roles = new LinkedHashMap<>();
        if (store != null) {
            Map<String, Object> snap = store.snapshot();
            for (Map.Entry<String, Object> e : snap.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                if (key.startsWith(LOG_PREFIX)) {
                    collectLogEntries(d, roles, key, e.getValue(), from, nowMillis);
                } else if (key.startsWith(CADENCE_PREFIX)) {
                    d.cadenceKeys++;
                } else if (key.startsWith(LASTRUN_PREFIX)) {
                    String fpid = key.substring(LASTRUN_PREFIX.length());
                    long ts = longOf(e.getValue());
                    if (!fpid.isEmpty() && ts > 0L) d.lastRuns.put(fpid, ts);
                }
            }
        }
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
                                          Object raw, long from, long nowMillis) {
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
            if (ts < from || ts > nowMillis) continue;   // 只看近 24h（历史保留 50 条/任务）
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
        sb.append("在线账号 ").append(d.onlineAccounts < 0 ? "?" : String.valueOf(d.onlineAccounts))
                .append(" · 角色 ").append(d.roleCount)
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
        if (d.roles.isEmpty()) {
            sb.append("近 24h 无任务日志\n");
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

    /**
     * 生成一次日报（幂等：当日 state 键已存在直接跳过）。
     *
     * @return true = 已尝试推送且企微确认送达；false = 跳过/仅落 state（无 webhook 或推送失败）
     */
    public boolean runOnce(long nowMillis) {
        if (!enabled()) return false;
        if (stateStore == null) {   // 无状态仓：无法幂等/落盘，直接放弃（不影响主流程）
            FileLog.warn("自动化日报缺少 StateStore，跳过本次生成");
            return false;
        }
        if (!shouldSend(stateStore, nowMillis)) {
            FileLog.info("自动化日报 {} 今日已生成过，跳过（幂等）", dayKey(nowMillis));
            return false;
        }
        int online = onlineAccounts == null ? -1 : onlineAccounts.getAsInt();
        DigestData data = collect(stateStore, nowMillis, WINDOW_MILLIS, online);
        String text = format(data);
        boolean sent = trySend(text);
        stateStore.putString(STATE_PREFIX + data.day, text);   // 幂等标记 + App/运维可查
        FileLog.info("自动化日报{} day={} 在线账号={} 角色={} 任务={} 领取={} 跳过={} 异常={} 节奏键={}",
                sent ? "已推送企微；" : "仅落日志/state（无可用 webhook）；",
                data.day, String.valueOf(data.onlineAccounts), String.valueOf(data.roleCount),
                String.valueOf(data.totalRuns), String.valueOf(data.totalClaimed),
                String.valueOf(data.totalSkipped), String.valueOf(data.totalFailed),
                String.valueOf(data.cadenceKeys));
        return sent;
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

    /** 启动 daemon 日报线程（{@code VAULT_DIGEST=off} 时只打一条日志不启动）。幂等。 */
    public void start() {
        if (!enabled()) {
            FileLog.info("自动化日报已关闭（{}=off）", ENV_ENABLED);
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
                safeRunOnce(now);
            }
            while (!stopped) {
                long at = nextDigestAt(System.currentTimeMillis(), hhmm);
                long wait = at - System.currentTimeMillis();
                Thread.sleep(Math.max(500L, Math.min(wait, 60_000L)));   // 分段 sleep，stop 可及时退出
                if (stopped) return;
                if (System.currentTimeMillis() < at) continue;
                safeRunOnce(System.currentTimeMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void safeRunOnce(long nowMillis) {
        try {
            runOnce(nowMillis);
        } catch (Throwable t) {
            FileLog.warn("自动化日报生成失败（忽略，不影响任务/会话）：{}",
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }
}
