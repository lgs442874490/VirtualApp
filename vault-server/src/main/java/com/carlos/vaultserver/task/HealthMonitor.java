package com.carlos.vaultserver.task;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionControl;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.WeComNotifier;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 自动化健康告警（批次6 2026-10-23）：每 5 分钟评估一次自动化是否“还活着”，
 * 出问题时主动推企业微信（而非等次日日报）。
 *
 * <p><b>检查项</b>（阈值均为常量，便于调整；判定为纯函数，注入时钟/快照即可单测）：
 * <ul>
 *   <li>{@code offline} 会话离线：keep=true 账号离线超 15 分钟（首次观察到离线开始计时，
 *       5 分钟一拍 ≈ 3 次连续评估）；</li>
 *   <li>{@code stagnant} 节奏停滞：在线且 auto=true 账号最近 30 分钟无任何任务日志；</li>
 *   <li>{@code zero_claim} 零领取：在线账号近 2h CLAIMED=0 且 SKIPPED&gt;50（可能配置/协议全灭）；</li>
 *   <li>{@code error_rate} 错误率：近 1h 样本 ≥20 且 ERROR/(CLAIMED+SKIPPED+ERROR) &gt;30%；</li>
 *   <li>{@code cadence} 节奏键缺失：在线但 {@code srv:cad:*}=0（节奏调度可能没跑起来）。</li>
 * </ul>
 *
 * <p><b>数据只读</b>：{@link StateStore} 的 {@code log:{fpid}:{sid}:{fn}}（ts/cat/...，RoundRunner 落账）、
 * {@code srv:cad:*} 键数、{@link VaultStore} 账号 keep/auto/label、{@link SessionControl.SessionStatus}
 * 在线态；<b>0 游戏协议</b>，副作用仅：企微 webhook（可选）+ 节流 state 键 + 日志。
 *
 * <p><b>发送/节流</b>：复用 {@link WeComNotifier} + {@code VaultStore.watchConfig()}（enabled &&
 * wecomEnabled && webhookUrl 非空）；无 webhook 或未启用 → 只 {@link FileLog#warn}。
 * 同类告警 6 小时节流，state 键 {@code srv:alert:{kind}:{fpid}:{yyyy-MM-dd}}（跨日看昨天键，保证窗口正确）
 * + 内存兜底；{@code VAULT_ALERTS=off}（或 false/0）整体关闭。
 *
 * <p><b>线程</b>：{@link #start()} 起 daemon "health-monitor"，分段 sleep 到 5 分钟一拍；
 * 单次评估异常只记日志不退出；{@link #stop()} 中断退出（Main shutdown hook 调用）。
 */
public final class HealthMonitor {

    /** 开关环境变量：{@code VAULT_ALERTS=off/false/0} 关闭（默认开）。 */
    public static final String ENV_ENABLED = "VAULT_ALERTS";
    /** 告警节流 state 键前缀：{@code srv:alert:{kind}:{fpid}:{yyyy-MM-dd}}。 */
    public static final String STATE_PREFIX = "srv:alert:";

    /** 评估周期：5 分钟。 */
    public static final long EVAL_INTERVAL_MILLIS = 5L * 60_000L;
    /** 会话离线告警阈值：15 分钟。 */
    public static final long OFFLINE_ALERT_MILLIS = 15L * 60_000L;
    /** 节奏停滞阈值：30 分钟无任务日志。 */
    public static final long STAGNANT_ALERT_MILLIS = 30L * 60_000L;
    /** 零领取窗口：2 小时。 */
    public static final long ZERO_CLAIM_WINDOW_MILLIS = 2L * 3600_000L;
    /** 零领取跳过数阈值：SKIPPED &gt; 50。 */
    public static final int ZERO_CLAIM_SKIP_THRESHOLD = 50;
    /** 错误率窗口：1 小时。 */
    public static final long ERROR_WINDOW_MILLIS = 3600_000L;
    /** 错误率最小样本：20。 */
    public static final int ERROR_MIN_SAMPLE = 20;
    /** 错误率阈值：严格 &gt; 30%。 */
    public static final double ERROR_RATE_THRESHOLD = 0.30d;
    /** 同类告警节流：6 小时。 */
    public static final long THROTTLE_MILLIS = 6L * 3600_000L;
    /** 告警文本长度上限（企微 text 上限 2048 字节，告警保持简洁）。 */
    public static final int MAX_TEXT_CHARS = 800;

    public static final String KIND_OFFLINE = "offline";
    public static final String KIND_STAGNANT = "stagnant";
    public static final String KIND_ZERO_CLAIM = "zero_claim";
    public static final String KIND_ERROR_RATE = "error_rate";
    public static final String KIND_CADENCE = "cadence";

    private static final String LOG_PREFIX = "log:";
    private static final String CADENCE_PREFIX = "srv:cad:";

    private final VaultStore vaultStore;
    private final StateStore stateStore;
    private final WeComNotifier notifier;
    private final Supplier<List<SessionControl.SessionStatus>> sessionStatus;
    /** fpid → 首次观察到离线时刻（毫秒；在线即移除）。 */
    private final Map<String, Long> offlineSince = new ConcurrentHashMap<>();
    /** 内存节流兜底：{@code kind|fpid → 上次告警毫秒}（state 缺失/重置时仍能节流）。 */
    private final Map<String, Long> lastAlertMemory = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private volatile Thread thread;

    public HealthMonitor(VaultStore vaultStore, StateStore stateStore, WeComNotifier notifier,
                         Supplier<List<SessionControl.SessionStatus>> sessionStatus) {
        this.vaultStore = vaultStore;
        this.stateStore = stateStore;
        this.notifier = notifier;
        this.sessionStatus = sessionStatus;
    }

    // =======================================================================
    // 配置（系统属性优先，单测可注入）
    // =======================================================================

    /** 读配置：系统属性优先（单测注入），其次环境变量；缺失返回空串。 */
    static String setting(String key) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) v = System.getenv(key);
        return v == null ? "" : v.trim();
    }

    /** 告警总开关：{@code VAULT_ALERTS=off/false/0} 关闭，缺省开启。 */
    public static boolean enabled() {
        String v = setting(ENV_ENABLED);
        return !("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || "0".equals(v));
    }

    /** 节奏调度开关：{@code VAULT_TASK_CADENCE=off} 时不检查“节奏键缺失”（否则误报）。 */
    static boolean cadenceSchedulerEnabled() {
        String v = setting("VAULT_TASK_CADENCE");
        return !("off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || "0".equals(v));
    }

    // =======================================================================
    // 数据快照（只读 StateStore/VaultStore/会话状态）
    // =======================================================================

    /** 单账号健康指标（纯数据，供 {@link #evaluate} 判定与单测断言）。 */
    public static final class AccountHealth {
        public String fpid = "";
        public String sid = "";
        public String label = "";
        public boolean online;
        public boolean keep;
        public boolean auto;
        /** 首次观察到离线的时刻（0=在线/未知）。 */
        public long offlineSinceMillis;
        /** 该账号最近一条任务日志 ts（0=无）。 */
        public long lastTaskLogMillis;
        /** 近 2h 顶层日志计数（零领取判定）。 */
        public int claimed2h;
        public int skipped2h;
        /** 近 1h 顶层日志计数（错误率判定）。 */
        public int claimed1h;
        public int skipped1h;
        public int error1h;
        /** {@code srv:cad:{fpid}:*} 键数。 */
        public int cadenceKeys;

        /** 展示名：label(fpid:sid)，label 空则 fpid:sid。 */
        public String display() {
            String id = sid == null || sid.isEmpty() ? fpid : fpid + ":" + sid;
            return label == null || label.isEmpty() ? id : label + "(" + id + ")";
        }
    }

    /** 一条待发/已发告警（纯数据）。 */
    public static final class Alert {
        public final String kind;
        public final String fpid;
        public final String title;
        public final String account;
        public final String metric;
        public final String advice;
        public final long atMillis;

        Alert(String kind, String fpid, String title, String account, String metric,
              String advice, long atMillis) {
            this.kind = kind;
            this.fpid = fpid;
            this.title = title;
            this.account = account;
            this.metric = metric;
            this.advice = advice;
            this.atMillis = atMillis;
        }
    }

    /**
     * 聚合账号健康快照（纯读；无任何游戏协议）。
     *
     * @param offlineSince 离线计时表（入参被就地更新：在线移除、首次离线写入 now；可传空 map）
     */
    public static List<AccountHealth> snapshot(VaultStore vaultStore, StateStore stateStore,
                                               Supplier<List<SessionControl.SessionStatus>> sessionStatus,
                                               long nowMillis, Map<String, Long> offlineSince) {
        List<AccountHealth> out = new ArrayList<>();
        Map<String, SessionControl.SessionStatus> statusByFpid = new LinkedHashMap<>();
        if (sessionStatus != null) {
            try {
                List<SessionControl.SessionStatus> list = sessionStatus.get();
                if (list != null) {
                    for (SessionControl.SessionStatus s : list) {
                        if (s != null && s.fpid != null) statusByFpid.put(s.fpid, s);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 日志/节奏键按 fpid 聚合（同账号多角色合并，告警面向账号）
        Map<String, LogStats> stats = new LinkedHashMap<>();
        Map<String, Integer> cadenceByFpid = new LinkedHashMap<>();
        if (stateStore != null) {
            Map<String, Object> snap = stateStore.snapshot();
            long from2h = nowMillis - ZERO_CLAIM_WINDOW_MILLIS;
            long from1h = nowMillis - ERROR_WINDOW_MILLIS;
            for (Map.Entry<String, Object> e : snap.entrySet()) {
                String key = e.getKey();
                if (key == null) continue;
                if (key.startsWith(LOG_PREFIX)) {
                    collectLog(stats, key, e.getValue(), from2h, from1h, nowMillis);
                } else if (key.startsWith(CADENCE_PREFIX)) {
                    // 键型：srv:cad:{fpid}:{sid}:{fn}（TaskCadenceScheduler.nextKey）
                    String[] parts = key.split(":", 4);
                    if (parts.length >= 4 && !parts[2].isEmpty()) {
                        cadenceByFpid.merge(parts[2], 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, VaultStore.Account> accounts = new LinkedHashMap<>();
        if (vaultStore != null) {
            try {
                List<VaultStore.Account> list = vaultStore.snapshot();
                if (list != null) {
                    for (VaultStore.Account a : list) {
                        if (a != null && a.fpid != null && !a.fpid.isEmpty()) accounts.put(a.fpid, a);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 账号清单：以身份库为准；身份库缺失时退化为日志里出现过的 fpid（便于单测/只读场景）
        if (accounts.isEmpty()) {
            for (String fpid : stats.keySet()) accounts.put(fpid, null);
        }
        for (Map.Entry<String, VaultStore.Account> e : accounts.entrySet()) {
            String fpid = e.getKey();
            VaultStore.Account acc = e.getValue();
            SessionControl.SessionStatus st = statusByFpid.get(fpid);
            AccountHealth h = new AccountHealth();
            h.fpid = fpid;
            h.label = acc == null ? "" : (acc.label == null ? "" : acc.label);
            h.keep = acc != null && acc.keep;
            h.auto = acc != null && acc.auto;
            h.sid = st != null && st.sid != null && !st.sid.isEmpty() ? st.sid
                    : (acc != null && acc.activeSid != null ? acc.activeSid : "");
            h.online = st != null
                    && com.carlos.vault.wjdr.WjdrEngine.STATE_ONLINE.equals(st.state);
            if (h.online || !h.keep) {
                offlineSince.remove(fpid);
                h.offlineSinceMillis = 0L;
            } else {
                Long since = offlineSince.get(fpid);
                if (since == null) {
                    since = nowMillis;            // 首次观察到离线：从观测时刻起算（≈3 次连续评估）
                    offlineSince.put(fpid, since);
                }
                h.offlineSinceMillis = since;
            }
            LogStats ls = stats.get(fpid);
            if (ls != null) {
                h.lastTaskLogMillis = ls.lastLogMillis;
                h.claimed2h = ls.claimed2h;
                h.skipped2h = ls.skipped2h;
                h.claimed1h = ls.claimed1h;
                h.skipped1h = ls.skipped1h;
                h.error1h = ls.error1h;
            }
            Integer ck = cadenceByFpid.get(fpid);
            h.cadenceKeys = ck == null ? 0 : ck;
            out.add(h);
        }
        return out;
    }

    /** 日志聚合中间结果。 */
    private static final class LogStats {
        long lastLogMillis;
        int claimed2h;
        int skipped2h;
        int claimed1h;
        int skipped1h;
        int error1h;
    }

    /** 解析一条 {@code log:{fpid}:{sid}:{fn}}（子项 fn 含冒号；计数只取顶层，lastLog 含全部）。 */
    private static void collectLog(Map<String, LogStats> stats, String key, Object raw,
                                   long from2h, long from1h, long nowMillis) {
        String[] parts = key.split(":", 4);
        if (parts.length < 4) return;
        String fpid = parts[1];
        String fn = parts[3];
        if (fpid.isEmpty() || fn.isEmpty()) return;
        LogStats ls = stats.computeIfAbsent(fpid, k -> new LogStats());
        JSONArray arr = jsonArray(raw);
        boolean sub = fn.indexOf(':') >= 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long ts = o.optLong("ts", 0L);
            if (ts <= 0L || ts > nowMillis) continue;
            if (ts > ls.lastLogMillis) ls.lastLogMillis = ts;
            if (sub) continue;   // 子项日志不参与计数（避免同一领取双计），但影响“最近任务完成”判定
            String cat = o.optString("cat", "");
            if (ts >= from2h) {
                if ("CLAIMED".equals(cat)) ls.claimed2h++;
                else if ("SKIPPED".equals(cat)) ls.skipped2h++;
            }
            if (ts >= from1h) {
                if ("CLAIMED".equals(cat)) ls.claimed1h++;
                else if ("SKIPPED".equals(cat)) ls.skipped1h++;
                else if ("ERROR".equals(cat)) ls.error1h++;
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

    // =======================================================================
    // 判定（纯函数）
    // =======================================================================

    /** 依次评估 5 类检查项，返回命中的告警（没有命中返回空 list，绝不 null）。 */
    public static List<Alert> evaluate(List<AccountHealth> health, long nowMillis,
                                       boolean cadenceSchedulerEnabled) {
        List<Alert> out = new ArrayList<>();
        if (health == null) return out;
        for (AccountHealth h : health) {
            if (h == null) continue;
            addIfNotNull(out, checkOffline(h, nowMillis));
            addIfNotNull(out, checkStagnant(h, nowMillis));
            addIfNotNull(out, checkZeroClaim(h, nowMillis));
            addIfNotNull(out, checkErrorRate(h, nowMillis));
            addIfNotNull(out, checkCadence(h, nowMillis, cadenceSchedulerEnabled));
        }
        return out;
    }

    private static void addIfNotNull(List<Alert> out, Alert a) {
        if (a != null) out.add(a);
    }

    /** 会话离线：keep=true、当前离线、从首次观察起 ≥15 分钟。 */
    public static Alert checkOffline(AccountHealth h, long nowMillis) {
        if (h == null || !h.keep || h.online || h.offlineSinceMillis <= 0L) return null;
        long minutes = Math.max(0L, (nowMillis - h.offlineSinceMillis) / 60_000L);
        if (nowMillis - h.offlineSinceMillis < OFFLINE_ALERT_MILLIS) return null;
        return new Alert(KIND_OFFLINE, h.fpid, "会话离线", h.display(),
                "已离线 " + minutes + " 分钟（阈值 " + (OFFLINE_ALERT_MILLIS / 60_000L) + " 分钟）",
                "检查网络/Token 是否失效，必要时重新登录或手动上线", nowMillis);
    }

    /** 节奏停滞：在线且 auto=true，最近 30 分钟无任何任务日志（无日志也算停滞）。 */
    public static Alert checkStagnant(AccountHealth h, long nowMillis) {
        if (h == null || !h.online || !h.auto) return null;
        if (h.lastTaskLogMillis > 0L
                && nowMillis - h.lastTaskLogMillis < STAGNANT_ALERT_MILLIS) return null;
        long minutes = h.lastTaskLogMillis <= 0L ? -1L
                : Math.max(0L, (nowMillis - h.lastTaskLogMillis) / 60_000L);
        String metric = h.lastTaskLogMillis <= 0L
                ? "在线但从未产生任务日志"
                : "最近 " + minutes + " 分钟无任务完成（阈值 " + (STAGNANT_ALERT_MILLIS / 60_000L) + " 分钟）";
        return new Alert(KIND_STAGNANT, h.fpid, "节奏停滞", h.display(), metric,
                "检查任务开关/节奏调度是否开启、会话是否卡在恢复态", nowMillis);
    }

    /** 零领取：在线、近 2h CLAIMED=0 且 SKIPPED>50（阈值严格大于）。 */
    public static Alert checkZeroClaim(AccountHealth h, long nowMillis) {
        if (h == null || !h.online) return null;
        if (h.claimed2h != 0 || h.skipped2h <= ZERO_CLAIM_SKIP_THRESHOLD) return null;
        return new Alert(KIND_ZERO_CLAIM, h.fpid, "零领取", h.display(),
                "近 2 小时 CLAIMED=0，SKIPPED=" + h.skipped2h
                        + "（阈值 >" + ZERO_CLAIM_SKIP_THRESHOLD + "）",
                "可能活动配置全关/协议变更/账号无可领，检查活动子开关与错误日志", nowMillis);
    }

    /** 错误率：近 1h 样本 ≥20 且 ERROR/(CLAIMED+SKIPPED+ERROR) > 30%。 */
    public static Alert checkErrorRate(AccountHealth h, long nowMillis) {
        if (h == null || !h.online) return null;
        int sample = h.claimed1h + h.skipped1h + h.error1h;
        if (sample < ERROR_MIN_SAMPLE || h.error1h <= 0) return null;
        double rate = (double) h.error1h / (double) sample;
        if (rate <= ERROR_RATE_THRESHOLD) return null;
        return new Alert(KIND_ERROR_RATE, h.fpid, "错误率异常", h.display(),
                String.format(Locale.ROOT, "近 1 小时错误率 %.0f%%（ERROR=%d/样本=%d，阈值 >30%%）",
                        rate * 100.0d, h.error1h, sample),
                "查看任务日志错误码与协议兼容性，必要时关闭相关子任务止损", nowMillis);
    }

    /** 节奏键缺失：在线但 {@code srv:cad:*}=0（节奏调度关闭时不判，避免误报）。 */
    public static Alert checkCadence(AccountHealth h, long nowMillis, boolean cadenceSchedulerEnabled) {
        if (!cadenceSchedulerEnabled || h == null || !h.online || h.cadenceKeys > 0) return null;
        return new Alert(KIND_CADENCE, h.fpid, "节奏键缺失", h.display(),
                "在线但 srv:cad:* 键数为 0（节奏调度可能未启动/被关闭）",
                "确认 VAULT_TASK_CADENCE 未关闭、TaskCadenceScheduler 正常派单", nowMillis);
    }

    // =======================================================================
    // 文本（纯函数）
    // =======================================================================

    /** 告警文本：标题 + 账号 + 指标 + 建议 + 北京时间（简洁，≤{@link #MAX_TEXT_CHARS}）。 */
    public static String format(Alert a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder(256);
        sb.append("【自动化告警】").append(a.title).append('\n');
        sb.append("账号：").append(a.account).append('\n');
        sb.append("指标：").append(a.metric).append('\n');
        sb.append("建议：").append(a.advice).append('\n');
        sb.append("时间：").append(Beijing.fmt("MM-dd HH:mm").format(new Date(a.atMillis)));
        String text = sb.toString();
        if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS - 1) + "…";
        return text;
    }

    // =======================================================================
    // 节流（同类 6h；state 键 + 内存兜底）
    // =======================================================================

    /** 节流 state 键：{@code srv:alert:{kind}:{fpid}:{yyyy-MM-dd}}。 */
    public static String throttleKey(String kind, String fpid, long nowMillis) {
        return STATE_PREFIX + kind + ":" + fpid + ":"
                + Beijing.fmt("yyyy-MM-dd").format(new Date(nowMillis));
    }

    /**
     * 同类告警是否仍在 6h 节流窗口内。
     *
     * <p>state 键按“日期”分片，跨日时看今天+昨天两条的较大值，保证 6h 窗口跨零点仍正确；
     * 内存表兜底（state 被清/单测无 store）。
     */
    public static boolean throttled(StateStore store, Map<String, Long> memory,
                                    Map<String, Object> stateSnap,
                                    String kind, String fpid, long nowMillis) {
        long last = 0L;
        if (memory != null) {
            Long m = memory.get(kind + "|" + fpid);
            if (m != null) last = Math.max(last, m);
        }
        Map<String, Object> snap = stateSnap;
        if (snap == null && store != null) {
            try {
                snap = store.snapshot();
            } catch (Throwable ignored) {
            }
        }
        if (snap != null) {
            long today = longOf(snap.get(throttleKey(kind, fpid, nowMillis)));
            long yesterday = longOf(snap.get(throttleKey(kind, fpid, nowMillis - 24L * 3600_000L)));
            last = Math.max(last, Math.max(today, yesterday));
        }
        if (last <= 0L) return false;
        long delta = nowMillis - last;
        return delta >= 0L && delta < THROTTLE_MILLIS;   // 时钟回拨按“未节流”处理，避免永久静音
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

    /** 记录一次告警（state 键 + 内存），用于 6h 节流。 */
    static void markAlert(StateStore store, Map<String, Long> memory, String kind, String fpid,
                          long nowMillis) {
        if (memory != null) memory.put(kind + "|" + fpid, nowMillis);
        if (store != null) {
            try {
                store.putLong(throttleKey(kind, fpid, nowMillis), nowMillis);
            } catch (Throwable ignored) {
            }
        }
    }

    // =======================================================================
    // 执行（每 5 分钟一拍；单测注入 nowMillis）
    // =======================================================================

    /**
     * 评估一轮并派发命中告警（同类 6h 节流；无 webhook 只落日志）。
     *
     * @return 本轮实际派发的告警条数（0=无告警/被节流/已关闭）
     */
    public int runOnce(long nowMillis) {
        if (!enabled()) return 0;
        try {
            List<AccountHealth> health = snapshot(vaultStore, stateStore, sessionStatus,
                    nowMillis, offlineSince);
            List<Alert> alerts = evaluate(health, nowMillis, cadenceSchedulerEnabled());
            if (alerts.isEmpty()) return 0;
            Map<String, Object> snap = stateStore == null ? Collections.emptyMap()
                    : stateStore.snapshot();
            int dispatched = 0;
            for (Alert a : alerts) {
                if (throttled(stateStore, lastAlertMemory, snap, a.kind, a.fpid, nowMillis)) {
                    FileLog.info("健康告警节流（6h 内同类已发）kind={} fpid={}", a.kind, a.fpid);
                    continue;
                }
                boolean sent = trySend(format(a));
                markAlert(stateStore, lastAlertMemory, a.kind, a.fpid, nowMillis);
                FileLog.warn("健康告警[{}] {}：{}（{}）", a.kind, a.fpid, a.metric,
                        sent ? "已推送企微" : "仅日志（无可用 webhook）");
                dispatched++;
            }
            return dispatched;
        } catch (Throwable t) {
            FileLog.warn("健康告警评估失败（忽略，不影响任务/会话）：{}",
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            return 0;
        }
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
            FileLog.warn("健康告警推送异常（已降级为仅日志）：{}",
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            return false;
        }
    }

    // =======================================================================
    // daemon 线程（Main 启动/停止）
    // =======================================================================

    /** 启动 daemon 健康巡检线程（{@code VAULT_ALERTS=off} 时只打日志不启动）。幂等。 */
    public void start() {
        if (!enabled()) {
            FileLog.info("自动化健康告警已关闭（{}=off）", ENV_ENABLED);
            return;
        }
        if (thread != null && thread.isAlive()) return;
        stopped = false;
        FileLog.info("自动化健康告警已启用：每 {} 分钟评估（会话离线/节奏停滞/零领取/错误率/节奏键）",
                String.valueOf(EVAL_INTERVAL_MILLIS / 60_000L));
        Thread t = new Thread(this::loop, "health-monitor");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** 停止健康巡检线程（shutdown hook 调用；可重复调用）。 */
    public void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** 分段 sleep 到下一拍（60s 一段，stop 可及时退出）；单次异常不退出循环。 */
    private void loop() {
        while (!stopped) {
            long waited = 0L;
            while (!stopped && waited < EVAL_INTERVAL_MILLIS) {
                long step = Math.min(60_000L, EVAL_INTERVAL_MILLIS - waited);
                try {
                    Thread.sleep(step);
                } catch (InterruptedException e) {
                    if (stopped) return;
                }
                waited += step;
            }
            if (stopped) return;
            try {
                runOnce(System.currentTimeMillis());
            } catch (Throwable t) {
                FileLog.warn("健康告警线程异常（忽略，继续下一拍）：{}",
                        t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            }
        }
    }
}
