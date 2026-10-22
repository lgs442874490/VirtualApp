package com.carlos.vault.daily.guardian;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战争状态机 WarState（设计 §6.11 v1.4/v1.5）：叠加在 QueueGuard 之上的系统级模式开关。
 *
 * <ul>
 *   <li><b>进入</b>：任一 H 系硬触发（H0 侦查即入 / H1 战斗确认 / H2 多路并发 /
 *       H3 极限压缩者在途 / H4 佯攻潮 5次/30min）→ 立即 WARTIME，不等评分；
 *       软信号（指数衰减 τ=10min）只到 ALERT 预警级（停可选出征、采集限近郊）；</li>
 *   <li><b>退出</b>（二选一，作用于敌情板全部威胁源）：①全敌静默 ≥ quiet_exit(30min，
 *       任一敌人任何信号重置其计时)；②全敌开盾（且无在途进攻行军）【A-26】；</li>
 *   <li><b>人工通道</b>：强制战争态/解除优先级最高；人工解除后不自动重入（直至新 H 信号），
 *       人工锁定期间不自动退出；</li>
 *   <li><b>E1 任务闸</b>：WARTIME 时 allowlist 外任务一律拦截；ALERT 时仅可选出征类拦截；</li>
 *   <li>误判不对称裁决（v1.5 公理序）：宁可错入——经济帐（采集停摆）不进决策函数。</li>
 * </ul>
 *
 * 线程模型：信号来自推送线程（多会话），公开变更方法 synchronized（低频，开销可忽略）。
 * 纯 java.util：App 与 vault-server 共用（vault-server build.gradle 直编，禁止复制）。
 */
public final class WarState {

    public enum State { NORMAL, ALERT, WARTIME }

    /** H 系硬触发 + 人工（设计 §6.11.1）。 */
    public enum Trigger {
        H0_SCOUT("侦查即入"),
        H1_BATTLE("战斗确认"),
        H2_MULTI_PRONG("多路并发"),
        H3_EXTREME("极限压缩来袭"),
        H4_FEINT_BURST("佯攻潮"),
        MANUAL("人工强制");

        public final String text;

        Trigger(String text) {
            this.text = text;
        }
    }

    /** 一条战争信号：id 去重（marchId/邮件桶），hard=null 即软信号。 */
    public static final class Signal {
        public final String id;
        public final Trigger hard;
        /** 软信号权重（HARD 触发不加软分；软 +40 战斗/+30 新进攻/+10 佯攻/+15 联盟通知）。 */
        public final int softWeight;
        public final long ownerUid;
        public final ThreatBoard.Kind kind;
        public final long at;

        public static Signal soft(String id, int weight, long ownerUid, ThreatBoard.Kind kind, long at) {
            return new Signal(id, null, weight, ownerUid, kind, at);
        }

        public static Signal hard(String id, Trigger t, long ownerUid, ThreatBoard.Kind kind, long at) {
            return new Signal(id, t, 0, ownerUid, kind, at);
        }

        private Signal(String id, Trigger hard, int softWeight, long ownerUid,
                       ThreatBoard.Kind kind, long at) {
            this.id = id;
            this.hard = hard;
            this.softWeight = softWeight;
            this.ownerUid = ownerUid;
            this.kind = kind;
            this.at = at;
        }
    }

    /** 判定参数（§11 war.* 表）：App 侧由 FnParams p:war.* 装配，server 侧由 roleParams 装配。 */
    public static final class Config {
        /** war.auto：自动判定总开关（false=仅人工强制）。 */
        public boolean auto = true;
        /** war.scout_hard：H0 侦查硬触发开关（false=侦查降级 +15 软信号）。 */
        public boolean scoutHard = true;
        /** war.shield_exit：全敌开盾即退（A-26）。 */
        public boolean shieldExit = true;
        /** war.alert_th：预警级软分阈值。 */
        public int alertTh = 25;
        /** ALERT 退出的软分下限与静默窗（软分衰减至 &lt;10 且静默 10min）。 */
        public int alertExitTh = 10;
        public long alertQuietMs = 10 * 60_000L;
        /** war.quiet_exit_min：全敌静默退出窗。 */
        public long quietExitMs = 30 * 60_000L;
        /** 软分指数衰减时间常数 τ（设计 10min）。 */
        public long softTauMs = 10 * 60_000L;
        /** war.feint_burst：H4 佯攻潮密度（N 次 / feintWindowMs）。 */
        public int feintBurstCount = 5;
        public long feintWindowMs = 30 * 60_000L;
        /** war.war_duration_alert_min：持续超时提醒（仅提醒不自动退，0=关）。 */
        public long durationAlertMs = 120 * 60_000L;
        /** war.allowlist：战时仍放行的任务（不占行军队列的战斗支援）。 */
        public Set<String> allowlist = defaultAllowlist();
        /** war.friendly_uids：自家关联小号白名单（侦查不算敌情；7.31）。 */
        public Set<Long> friendlyUids = new LinkedHashSet<>();

        public static Set<String> defaultAllowlist() {
            Set<String> s = new LinkedHashSet<>();
            s.add("battle_shield");
            s.add("medic");
            return s;
        }
    }

    /** 战争态进/出与提醒回调（E2/E5/E6/X1/X4 由宿主接线；server 侧各自实现）。 */
    public interface Listener {
        /** 进入 WARTIME（含人工强制）。 */
        void onWarEnter(Trigger trigger, String detail);

        /** 退出 WARTIME（含人工解除；detail 含退出原因+敌情板归档）。 */
        void onWarExit(String reason, String detail);

        /** 战时过程提醒（持续时长超时等，不改变状态）。 */
        void onNotice(String text);
    }

    /** 默认空实现（宿主只关心其中一两个回调时用）。 */
    public static class ListenerAdapter implements Listener {
        @Override public void onWarEnter(Trigger trigger, String detail) {
        }

        @Override public void onWarExit(String reason, String detail) {
        }

        @Override public void onNotice(String text) {
        }
    }

    // ---------------------------------------------------------------- 状态

    private final Config config = new Config();
    private final ThreatBoard board = new ThreatBoard();
    private volatile State state = State.NORMAL;
    private volatile Trigger enterReason;
    private volatile long enteredAt;
    private volatile boolean manualLock;
    /** 软分与最近软分时刻（指数衰减基准）。 */
    private double softScore;
    private long lastSoftAt;
    /** 硬触发去重：信号 id → 最近入账时刻（10min 内同 id 只记一次）。 */
    private final Map<String, Long> recentHardIds = new ConcurrentHashMap<>();
    private static final long HARD_DEDUPE_MS = 10 * 60_000L;
    /** H1 战斗确认去重（落地 vs 战报邮件同场只加一次软分 +40）：owner|target → 时刻。 */
    private final Map<String, Long> recentBattles = new HashMap<>();
    private static final long BATTLE_DEDUPE_MS = 5 * 60_000L;
    /** H2/H3 依赖的在途进攻表：marchId → [ownerUid, endServerSec]。 */
    private final Map<String, long[]> inFlightAttacks = new ConcurrentHashMap<>();
    /** H4 佯攻记录：ownerUid → 召回时刻队列（窗口内计数）。 */
    private final Map<Long, ArrayDeque<Long>> feints = new HashMap<>();
    /** 持续时长提醒只发一次。 */
    private volatile boolean durationAlerted;

    private volatile Listener listener = new ListenerAdapter();

    // ---------------------------------------------------------------- 配置/回调

    /** 热载参数（FnParams/roleParams 变更后重建 Config 调入；只影响后续判定）。 */
    public synchronized void configure(Config c) {
        if (c == null) return;
        config.auto = c.auto;
        config.scoutHard = c.scoutHard;
        config.shieldExit = c.shieldExit;
        config.alertTh = c.alertTh;
        config.alertExitTh = c.alertExitTh;
        config.alertQuietMs = c.alertQuietMs;
        config.quietExitMs = c.quietExitMs;
        config.softTauMs = c.softTauMs;
        config.feintBurstCount = c.feintBurstCount;
        config.feintWindowMs = c.feintWindowMs;
        config.durationAlertMs = c.durationAlertMs;
        config.allowlist = c.allowlist == null ? Config.defaultAllowlist() : c.allowlist;
        config.friendlyUids = c.friendlyUids == null ? new LinkedHashSet<>() : c.friendlyUids;
    }

    public void setListener(Listener l) {
        listener = l == null ? new ListenerAdapter() : l;
    }

    // ---------------------------------------------------------------- 信号入口

    /** 战争信号入账（H 系硬触发 / 软信号；按 id 去重）。 */
    public synchronized void onSignal(Signal s) {
        if (s == null || s.id == null) return;
        long now = s.at > 0 ? s.at : System.currentTimeMillis();
        if (s.hard != null) {
            Long prev = recentHardIds.get(s.id);
            if (prev != null && now - prev < HARD_DEDUPE_MS) return;
            recentHardIds.put(s.id, now);
            if (recentHardIds.size() > 256) trimRecentHardIds(now);
        }
        board.onSignal(s.ownerUid, s.kind, now);
        if (s.hard != null) {
            if (config.auto) {
                enterWartime(s.hard, now);   // war.auto=false（§11）：自动判定关闭，仅人工强制可入
            }
            return;
        }
        // 软信号：score = score·e^(−Δt/τ) + w（v1.4：只用于 ALERT，不决定战争态进出）
        softScore = decayedScore(now) + s.softWeight;
        lastSoftAt = now;
        if (config.auto && !manualLock && state == State.NORMAL && softScore >= config.alertTh) {
            state = State.ALERT;
            notice("⚠️战争预警（软分 " + (int) softScore + " ≥ " + config.alertTh
                    + "）：停可选出征、采集限近郊 · " + board.summary(now, config.quietExitMs));
        }
    }

    /** 侦查信号入口（H0）：盟友/白名单过滤由宿主完成后再调。 */
    public void onScout(long marchId, long ownerUid, long at) {
        if (config.scoutHard) {
            onSignal(Signal.hard("H0:m" + marchId, Trigger.H0_SCOUT, ownerUid, ThreatBoard.Kind.SCOUT, at));
        } else {
            // H0 降级（A-24 无法识别/旋钮关闭）：侦查回退 +15 软信号，战争态改由 H1-H4 触发
            onSignal(Signal.soft("S0:m" + marchId, 15, ownerUid, ThreatBoard.Kind.SCOUT, at));
        }
    }

    /** 新进攻行军在途登记（H2 数据源；qualified=构成 QUALIFIED 威胁时才计软分 +30）。 */
    public synchronized void onAttackMarch(long marchId, long ownerUid, long endServerSec,
                                           boolean qualified, long at) {
        inFlightAttacks.put(String.valueOf(marchId),
                new long[]{ownerUid, endServerSec, at});
        if (qualified) {
            onSignal(Signal.soft("S30:m" + marchId, 30, ownerUid, ThreatBoard.Kind.ATTACK, at));
        }
        if (inFlightAttacks.size() == 2) {
            // H2 多路并发：恰好第 2 条在途时触发一次（1→2→1→2 的再过渡会再发，进战时幂等）
            onSignal(Signal.hard("H2:m" + marchId, Trigger.H2_MULTI_PRONG,
                    ownerUid, ThreatBoard.Kind.ATTACK, at));
        }
    }

    /** 进攻行军更新（endtime 变化时刷新在途表；加减速判定/H3 由宿主按 EXTREME 锁存发信号）。 */
    public void onAttackUpdated(long marchId, long endServerSec) {
        long[] v = inFlightAttacks.get(String.valueOf(marchId));
        if (v != null) v[1] = endServerSec;
    }

    /** 在途进攻结束：recalledBeforeLanding=佯攻（距落地 &gt;2s 召回，7.2 口径）→ H4 计数 + 软 +10；落地 → H1。 */
    public synchronized void onAttackEnded(long marchId, long ownerUid, long targetUid,
                                           boolean recalledBeforeLanding, long at) {
        inFlightAttacks.remove(String.valueOf(marchId));
        if (recalledBeforeLanding) {
            recordFeint(ownerUid, at);
            onSignal(Signal.soft("S10:m" + marchId, 10, ownerUid, ThreatBoard.Kind.FEINT, at));
        } else {
            // 敌攻落地 = H1（战报/战斗推送尚未到也先入——宁可错入；战报到达走 onBattleConfirmed）
            recentBattles.put(ownerUid + "|" + targetUid, at);
            onSignal(Signal.hard("H1:m" + marchId, Trigger.H1_BATTLE,
                    ownerUid, ThreatBoard.Kind.ATTACK, at));
        }
    }

    /** 战斗结算确认（战报邮件 first_type=30）：H1 硬触发 + 软 +40（同场落地已计入时软分只取一次）。 */
    public synchronized void onBattleConfirmed(long ownerUid, long targetUid, String mailKey, long at) {
        String key = ownerUid + "|" + targetUid;
        Long landed = recentBattles.get(key);
        boolean duplicate = landed != null && at - landed < BATTLE_DEDUPE_MS;
        if (!duplicate) recentBattles.put(key, at);
        if (recentBattles.size() > 64) trimRecentBattles(at);
        onSignal(Signal.hard("H1:" + (mailKey == null ? "b" + at : mailKey),
                Trigger.H1_BATTLE, ownerUid, ThreatBoard.Kind.ATTACK, at));
        if (!duplicate) {
            onSignal(Signal.soft("S40:" + (mailKey == null ? "b" + at : mailKey), 40,
                    ownerUid, ThreatBoard.Kind.ATTACK, at));
        }
    }

    /** 极限压缩者在途（H3）：EXTREME 锁存者的行军/加速事件由宿主映射入。 */
    public void onExtremeMarch(long marchId, long ownerUid, long at) {
        onSignal(Signal.hard("H3:m" + marchId, Trigger.H3_EXTREME,
                ownerUid, ThreatBoard.Kind.ATTACK, at));
    }

    /** 敌开盾/撤盾（退出判据②【A-26】；撤盾=新信号）。 */
    public void onEnemyShield(long ownerUid, boolean on, long at) {
        board.onShield(ownerUid, on, at);
    }

    /** 侦查行军消失（敌召回/落地，非进攻类）：只清展示态，不影响判定。 */
    public void onScoutEnded(long marchId) {
        // 侦查无在途表；保留接口使宿主两侧语义一致
    }

    // ---------------------------------------------------------------- 周期判定

    /**
     * 周期 tick（退出判定 + 持续时长提醒；宿主 60s 节拍驱动即可）。
     * manualLock（人工锁定）期间不自动退出；人工解除后状态 NORMAL，不自动重入。
     */
    public synchronized void tick(long now) {
        State st = state;
        if (st == State.WARTIME) {
            if (!manualLock) {
                if (config.shieldExit && board.allShielded() && inFlightAttacks.isEmpty()) {
                    exitWartime("全敌开盾（A-26：开盾=解除攻击能力）", now);
                    return;
                }
                if (board.allQuiet(now, config.quietExitMs)) {
                    exitWartime("全敌静默 ≥ " + (config.quietExitMs / 60_000L) + "min", now);
                    return;
                }
            }
            if (!durationAlerted && config.durationAlertMs > 0
                    && now - enteredAt >= config.durationAlertMs) {
                durationAlerted = true;
                notice("⏱️战争态已持续 " + ((now - enteredAt) / 60_000L)
                        + "min（提醒不劝退）· " + board.summary(now, config.quietExitMs));
            }
        } else if (st == State.ALERT) {
            double cur = decayedScore(now);
            long lastAny = Math.max(lastSoftAt, board.anySignalAt());
            if (cur < config.alertExitTh && now - lastAny >= config.alertQuietMs) {
                state = State.NORMAL;
                softScore = cur;
                notice("战争预警解除（软分 " + (int) cur + " < " + config.alertExitTh
                        + " 且静默 " + (config.alertQuietMs / 60_000L) + "min）");
            }
        }
    }

    // ---------------------------------------------------------------- 人工通道

    /** 人工强制/解除（优先级最高）。on=true 锁定 WARTIME（不自动退）；on=false 解除。 */
    public synchronized void forceWartime(boolean on, String reason, long at) {
        if (on) {
            manualLock = true;
            enterWartime(Trigger.MANUAL, at);
        } else {
            manualLock = false;
            if (state == State.WARTIME) {
                exitWartime("人工解除" + (reason == null || reason.isEmpty() ? "" : "（" + reason + "）"), at);
            }
        }
    }

    // ---------------------------------------------------------------- 查询/闸口

    public State state() {
        return state;
    }

    public boolean wartime() {
        return state == State.WARTIME;
    }

    /** 预警级（停可选出征、采集限近郊）。 */
    public boolean alert() {
        return state == State.ALERT;
    }

    /** E1 战时任务闸：WARTIME 时 allowlist 外任务一律拦截。 */
    public boolean dailyTaskGated(String taskId) {
        return state == State.WARTIME && (taskId == null || !config.allowlist.contains(taskId));
    }

    /** 人工锁定中（状态行展示）。 */
    public boolean manualLocked() {
        return manualLock;
    }

    /** 是否自家关联小号（war.friendly_uids 白名单；盟友名单过滤由宿主做）。 */
    public boolean isFriendlyUid(long ownerUid) {
        return ownerUid > 0 && config.friendlyUids.contains(ownerUid);
    }

    /** 当前生效配置快照键值（状态行/诊断展示用）。 */
    public String configSummary() {
        return "auto=" + config.auto + " scout_hard=" + config.scoutHard
                + " shield_exit=" + config.shieldExit
                + " quiet=" + (config.quietExitMs / 60_000L) + "min"
                + " alert_th=" + config.alertTh
                + " feint=" + config.feintBurstCount + "/30min"
                + " allowlist=" + config.allowlist;
    }

    public Trigger reason() {
        return enterReason;
    }

    public long enteredAt() {
        return enteredAt;
    }

    /** 战争态持续毫秒（未在战时 0）。 */
    public long durationMs(long now) {
        return state == State.WARTIME && enteredAt > 0 ? Math.max(0, now - enteredAt) : 0;
    }

    public ThreatBoard board() {
        return board;
    }

    /** 在途进攻数（allShielded 的"无在途进攻"半边；状态行展示）。 */
    public int inFlightAttacks() {
        return inFlightAttacks.size();
    }

    /** 当前态一行解释（状态行/复盘）。 */
    public String explain(long now) {
        switch (state) {
            case WARTIME:
                return "⚔️战争态 · " + (enterReason == null ? "" : enterReason.text)
                        + " · 已 " + (durationMs(now) / 60_000L) + "min"
                        + (manualLock ? " · 人工锁定" : "")
                        + " · " + board.summary(now, config.quietExitMs)
                        + " · 在途进攻 " + inFlightAttacks.size();
            case ALERT:
                return "⚠️战争预警 · 软分 " + (int) decayedScore(now) + "/" + config.alertTh
                        + " · " + board.summary(now, config.quietExitMs);
            default:
                return "平时";
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 进入 WARTIME（幂等：已在战时只刷新信号板，不重复发 E 事件）。 */
    private void enterWartime(Trigger t, long now) {
        durationAlerted = false;
        if (state == State.WARTIME) return;   // 重入即时，无冷却期（§6.11.2）
        state = State.WARTIME;
        enterReason = t;
        enteredAt = now;
        softScore = 0;
        try {
            listener.onWarEnter(t, "H 触发=" + t.text + " · " + board.summary(now, config.quietExitMs));
        } catch (Throwable ignored) {
        }
    }

    private void exitWartime(String reason, long now) {
        long duration = durationMs(now);
        state = State.NORMAL;
        enterReason = null;
        enteredAt = 0;
        durationAlerted = false;
        softScore = 0;
        String archive = board.archiveText(now, config.quietExitMs);
        board.reset();   // X4：归档文本已交 Listener；重入战争态重建空板
        try {
            listener.onWarExit(reason, "战时持续 " + (duration / 60_000L) + "min · " + archive);
        } catch (Throwable ignored) {
        }
    }

    private double decayedScore(long now) {
        if (lastSoftAt <= 0) return softScore;
        double dt = Math.max(0, now - lastSoftAt);
        return softScore * Math.exp(-dt / (double) Math.max(1L, config.softTauMs));
    }

    private void recordFeint(long ownerUid, long at) {
        ArrayDeque<Long> q = feints.get(ownerUid);
        if (q == null) {
            q = new ArrayDeque<>();
            feints.put(ownerUid, q);
        }
        q.addLast(at);
        while (!q.isEmpty() && at - q.peekFirst() > config.feintWindowMs) q.removeFirst();
        if (config.auto && q.size() >= config.feintBurstCount) {
            // H4 系统性牵制：同源窗口内 ≥N 次佯攻（每次触发后清队，防同一段样本重复触发）
            q.clear();
            onSignal(Signal.hard("H4:u" + ownerUid + ":" + at, Trigger.H4_FEINT_BURST,
                    ownerUid, ThreatBoard.Kind.FEINT, at));
        }
    }

    private void trimRecentHardIds(long now) {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> e : recentHardIds.entrySet()) {
            if (now - e.getValue() > HARD_DEDUPE_MS) stale.add(e.getKey());
        }
        for (String k : stale) recentHardIds.remove(k);
    }

    private void trimRecentBattles(long now) {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> e : recentBattles.entrySet()) {
            if (now - e.getValue() > BATTLE_DEDUPE_MS) stale.add(e.getKey());
        }
        for (String k : stale) recentBattles.remove(k);
    }

    private void notice(String text) {
        try {
            listener.onNotice(text);
        } catch (Throwable ignored) {
        }
    }
}
