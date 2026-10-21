package com.carlos.vault.daily;

/**
 * 账号池角色运行态（M2，<b>纯 Java，无 Android 依赖</b>——JVM 单测直跑）。
 *
 * <p>字段与 M1 契约 {@code /v1/vault/accounts} 的 {@code roles[].state} 一一对应
 * （《账号池M1-服务端接口契约》§2.1），11 态文案与优先级逐字对齐网页版权威状态机
 * （需求《网页版账号页复刻》§2.3 前端 vt(f)/yt(f)）。<b>服务端是唯一事实源</b>，
 * 本类只做展示映射与动作可用性判定，不在本地推导在线状态（P2 防回滚教训）。
 *
 * <p>11 态优先级（自上而下，先命中先生效，与网页版一致）：
 * <ol>
 *   <li>在线；2 已暂停·待恢复；3 已暂停；4 已停止；5 被顶号；6 需登录；</li>
 *   <li>7 待恢复（倒计时）；8 登录中；9 计划休眠；10 已收纳；11 离线。</li>
 * </ol>
 * 判定辅助 {@code yt = takeoverRecoveryActive && recoveryState == WAITING_RETRY}；
 * 「被顶号」= recoveryState DISABLED 且 statusMessage 含「其他设备」。
 *
 * <p>动作可用性（M4 §3.2 修订）：online→可 pause/stop；!online && enabled→可 login
 * （上线，含已停止——服务端 login 对停止角色即启动，与网页版一致）；paused→可
 * resume/stop；enabled=false→全部禁用。M2 初版曾把 STOPPED 排除出 login、让行内
 * 回落「登录（服务器自动化）」，与网页版 1:1 口径不符，已改——池状态可用时
 * 「登录」不再出现在行内（仅账号详情页与降级/无池路径保留）。
 *
 * <p>旧服务端降级（无 state 对象）：{@link #degradedLegacy} 沿用现有
 * 在线/重连中/被顶号暂停/需重导token/离线 文案，动作矩阵全 false（UI 回落旧行为），
 * stale 提示由页面横幅承担。
 */
public final class RoleRuntimeState {

    // ---------- M1 §2.1 字段值 ----------
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_PAUSED = "PAUSED";
    public static final String RUN_STOPPED = "STOPPED";
    public static final String REC_AVAILABLE = "AVAILABLE";
    public static final String REC_DISABLED = "DISABLED";
    public static final String REC_ACTION_REQUIRED = "ACTION_REQUIRED";
    public static final String REC_WAITING_RETRY = "WAITING_RETRY";
    public static final String SCHED_ACTIVE = "ACTIVE";
    public static final String SCHED_PAUSED = "PAUSED";

    /** 11 态文案（逐字对齐网页版 §2.3，勿改字）。 */
    public static final String L_ONLINE = "在线";
    public static final String L_PAUSED_RECOVERY = "已暂停·待恢复";
    public static final String L_PAUSED = "已暂停";
    public static final String L_STOPPED = "已停止";
    public static final String L_TAKEOVER = "被顶号";
    public static final String L_NEED_LOGIN = "需登录";
    public static final String L_RECOVERING = "待恢复";
    public static final String L_LOGIN_IN_PROGRESS = "登录中";
    public static final String L_SCHEDULED_SLEEP = "计划休眠";
    public static final String L_SHELVED = "已收纳";
    public static final String L_OFFLINE = "离线";

    /** 「其他设备」顶号标记关键词（网页版权威判定）。 */
    static final String TAKEOVER_MSG_KEY = "其他设备";

    // ---------- 字段（缺省值 = M1 无会话推导：离线 + STOPPED） ----------
    public final boolean online;
    public final String runState;             // RUNNING|PAUSED|STOPPED
    public final String recoveryState;        // AVAILABLE|DISABLED|ACTION_REQUIRED|WAITING_RETRY
    public final String statusMessage;
    public final boolean takeoverRecoveryActive;
    public final long nextRecoveryAt;         // epoch ms；WAITING_RETRY 倒计时用，0=未知（即将恢复）
    public final boolean startupInProgress;
    public final boolean loginInProgress;
    public final String runScheduleState;     // ACTIVE|PAUSED
    public final boolean enabled;
    /** 旧服务端降级态（无 state 对象）：文案走 legacyState 映射，动作矩阵全 false。 */
    public final boolean degraded;
    /** 降级态的旧状态键（online/recovering/takeover/need_token/其他）。 */
    public final String legacyState;

    public RoleRuntimeState(boolean online, String runState, String recoveryState,
                            String statusMessage, boolean takeoverRecoveryActive,
                            long nextRecoveryAt, boolean startupInProgress,
                            boolean loginInProgress, String runScheduleState, boolean enabled) {
        this(online, runState, recoveryState, statusMessage, takeoverRecoveryActive,
                nextRecoveryAt, startupInProgress, loginInProgress, runScheduleState,
                enabled, false, null);
    }

    private RoleRuntimeState(boolean online, String runState, String recoveryState,
                             String statusMessage, boolean takeoverRecoveryActive,
                             long nextRecoveryAt, boolean startupInProgress,
                             boolean loginInProgress, String runScheduleState, boolean enabled,
                             boolean degraded, String legacyState) {
        this.online = online;
        this.runState = runState == null ? RUN_STOPPED : runState;
        this.recoveryState = recoveryState == null ? REC_AVAILABLE : recoveryState;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.takeoverRecoveryActive = takeoverRecoveryActive;
        this.nextRecoveryAt = nextRecoveryAt;
        this.startupInProgress = startupInProgress;
        this.loginInProgress = loginInProgress;
        this.runScheduleState = runScheduleState == null ? SCHED_ACTIVE : runScheduleState;
        this.enabled = enabled;
        this.degraded = degraded;
        this.legacyState = legacyState;
    }

    /** M1 缺省态：无会话 = 离线 + STOPPED。 */
    public static RoleRuntimeState offline(boolean enabled) {
        return new RoleRuntimeState(false, RUN_STOPPED, REC_AVAILABLE, "",
                false, 0L, false, false, SCHED_ACTIVE, enabled);
    }

    /**
     * 旧服务端降级映射：沿用现有文案口径
     * （{@link RoleDisplay#serverStateLabel}：在线/重连中/被顶号暂停/需重导token/离线）。
     */
    public static RoleRuntimeState degradedLegacy(String legacyState) {
        return new RoleRuntimeState("online".equals(legacyState), RUN_STOPPED, REC_AVAILABLE,
                "", false, 0L, false, false, SCHED_ACTIVE, true, true,
                legacyState == null ? "" : legacyState);
    }

    // ---------- 判定辅助 ----------

    /** 网页版 yt(f)：顶号恢复倒计时中。 */
    public boolean takeoverRecovering() {
        return takeoverRecoveryActive && REC_WAITING_RETRY.equals(recoveryState);
    }

    /** 被顶号（恢复策略已停用 + 服务端标记「其他设备」顶下线）。 */
    public boolean takeoverKicked() {
        return REC_DISABLED.equals(recoveryState)
                && statusMessage.contains(TAKEOVER_MSG_KEY);
    }

    // ---------- 11 态文案 ----------

    /** 状态文案（含待恢复倒计时，nowMs 取当前时刻）。 */
    public String label(long nowMs) {
        if (degraded) return RoleDisplay.serverStateLabel(legacyState);
        if (online) return L_ONLINE;                                   // 1
        boolean yt = takeoverRecovering();
        if (RUN_PAUSED.equals(runState) && yt) return L_PAUSED_RECOVERY; // 2
        if (RUN_PAUSED.equals(runState)) return L_PAUSED;               // 3
        if (RUN_STOPPED.equals(runState)) return L_STOPPED;             // 4
        if (takeoverKicked()) return L_TAKEOVER;                        // 5
        if (REC_ACTION_REQUIRED.equals(recoveryState)) return L_NEED_LOGIN; // 6
        if (yt) return L_RECOVERING + recoverySuffix(nowMs);            // 7
        if (startupInProgress || loginInProgress) return L_LOGIN_IN_PROGRESS; // 8
        if (SCHED_PAUSED.equals(runScheduleState)) return L_SCHEDULED_SLEEP; // 9
        if (!enabled) return L_SHELVED;                                 // 10
        return L_OFFLINE;                                               // 11
    }

    /** 状态文案（倒计时按当前时刻）。 */
    public String label() {
        return label(System.currentTimeMillis());
    }

    /**
     * 待恢复倒计时后缀（网页版口径）：next_recovery_at ≤0/已过期 →「 · 即将恢复」；
     * ≥1h 显示 h:mm:ss（时不补零），否则 mm:ss（分秒补零）；秒数向上取整。
     */
    private String recoverySuffix(long nowMs) {
        return " " + countdownText(nextRecoveryAt, nowMs);
    }

    /** 倒计时文本：mm:ss / h:mm:ss / 「· 即将恢复」。 */
    public static String countdownText(long nextRecoveryAt, long nowMs) {
        if (nextRecoveryAt <= 0 || nextRecoveryAt - nowMs <= 0) return "· 即将恢复";
        long s = (nextRecoveryAt - nowMs + 999) / 1000;   // 向上取整：不虚标已恢复
        if (s >= 3600) {
            return (s / 3600) + String.format(java.util.Locale.CHINA, ":%02d:%02d",
                    (s % 3600) / 60, s % 60);
        }
        return String.format(java.util.Locale.CHINA, "%02d:%02d", s / 60, s % 60);
    }

    /**
     * 行下 banner 文案（契约 §3.1「账号被顶号 · 待恢复 mm:ss」）；无需 banner 返回 null。
     * 覆盖两场景：顶号恢复倒计时中（yt）与被顶号未排恢复（DISABLED+其他设备）。
     */
    public String bannerText(long nowMs) {
        if (degraded || online) return null;
        if (takeoverRecovering()) {
            if (nextRecoveryAt > 0 && nextRecoveryAt - nowMs > 0) {
                return "账号被顶号 · 待恢复 " + countdownText(nextRecoveryAt, nowMs);
            }
            return "账号被顶号 · 即将恢复";
        }
        if (takeoverKicked()) return "账号被顶号";
        return null;
    }

    /** 状态点配色基调：ok（绿）/ warn（琥珀）/ err（红）/ busy（进行中）/ idle（中性）。 */
    public String tone() {
        if (degraded) {
            if ("online".equals(legacyState)) return "ok";
            if ("recovering".equals(legacyState)) return "warn";
            if ("takeover".equals(legacyState) || "need_token".equals(legacyState)) return "err";
            return "idle";
        }
        String l = label();
        if (L_ONLINE.equals(l)) return "ok";
        if (L_LOGIN_IN_PROGRESS.equals(l)) return "busy";
        if (L_TAKEOVER.equals(l) || L_NEED_LOGIN.equals(l)) return "err";
        if (L_PAUSED.equals(l) || L_PAUSED_RECOVERY.equals(l)
                || l.startsWith(L_RECOVERING)) return "warn";
        return "idle";
    }

    // ---------- 动作矩阵（纯函数） ----------

    /**
     * 「上线」（role-action login）：未在线且已启用即可用——含已停止（M4 §3.2 修复：
     * 服务端 login 对停止角色就是启动，UI 口径与网页版一致；「停止」仍仅在线/暂停可点）。
     */
    public boolean canLogin() {
        return !degraded && enabled && !online;
    }

    /** 「暂停」（role-action pause）：在线（会话保留、停止执行）。 */
    public boolean canPause() {
        return !degraded && enabled && online;
    }

    /** 「停止」（role-action stop）：在线或暂停（有会话可销毁）。 */
    public boolean canStop() {
        return !degraded && enabled && (online || RUN_PAUSED.equals(runState));
    }

    /** 「恢复」（PAUSED→RUNNING；服务端无会话时等价 login）。UI 与「上线」共用按钮。 */
    public boolean canResume() {
        return !degraded && enabled && !online && RUN_PAUSED.equals(runState);
    }
}
