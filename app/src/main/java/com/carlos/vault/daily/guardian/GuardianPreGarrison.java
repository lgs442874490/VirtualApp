package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.FnParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守护盟友 P4：预驻防（设计 §3.2[3] 提前戒备 / §6.10.1 P1 准入；交接 20261017 遗留 #6）。
 *
 * 触发（高威胁信号，任一命中）：
 *   H3   EXTREME 锁存者的行军指向盟友（侦查也算——侦查=寻的信号，落地<9s 场景反应式无解）；
 *   惯犯 EXTREME 锁存者近期活跃（30 分钟内有信号）且新信号指向盟友；
 *   H2   ≥2 条 QUALIFIED 进攻行军同时指向盟友（多路并发）。
 *
 * 与反应式发射的互斥/仲裁（QueueGuard-lite）：
 *   - 门控 {@link #gate}：总开关→冷却→已驻防去重→反应式执行中让位→保留队列
 *     （INFLIGHT 恒留 1 队给反应式，手册 §4.1 保留池原则）；
 *   - 复用 GuardianLaunch 同一条执行链：ACTIVE 同目标去重、LAUNCH_SLOTS 并发、
 *     2878 队列满走既有仲裁（撤弱驻防/召回最旧采集/L2 迁城/持续重试）；
 *   - 预驻防失败只记日志与状态行，不推送人工告警（预防性任务，与反应式不同）。
 *
 * 默认关闭（subOn pre_garrison=false 且 strategy="0"），关闭时零行为变化。
 */
public final class GuardianPreGarrison {

    /** 同目标预驻防冷却：10 分钟（防反复派驻/与反应式抢队列）。 */
    static final long COOLDOWN_MS = 600_000L;
    /** 预驻防无威胁滞留：合成威胁 ETA=now+600s，到期后孤儿清扫器撤回释放队列（手册 §4.2 驻扎即负债）。 */
    static final long DWELL_SEC = 600L;
    /** "EXTREME 惯犯近期活跃"窗口。 */
    static final long RECENT_ACTIVE_MS = 1800_000L;
    /** 保留给反应式发射的队列数（INFLIGHT 并发 2 → 预驻防最多占 1）。 */
    static final int REACTIVE_RESERVE = 1;

    /** fpid:kid:targetUid → 上次预驻防派发时刻。 */
    private static final Map<String, Long> LAST_PRE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 配置

    /** 预驻防开关（子开关 + 策略"1"=开，双控同 shop/refresh_diamond 模式；默认关）。 */
    public static boolean preOn(Context ctx, String fpid, long sid) {
        try {
            return FnParams.subOn(ctx, fpid, sid, GuardianEngine.FN_ID, "pre_garrison", false)
                    && !"0".equals(FnParams.strategy(ctx, fpid, sid,
                            GuardianEngine.FN_ID, "pre_garrison", "0").trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目标优先级："threat"=受威胁优先（默认）/ "near"=受威胁优先+就近排序。 */
    public static String targetPriority(Context ctx, String fpid, long sid) {
        try {
            String v = FnParams.strategy(ctx, fpid, sid,
                    GuardianEngine.FN_ID, "pre_garrison_targets", "threat").trim();
            return v.isEmpty() ? "threat" : v;
        } catch (Throwable t) {
            return "threat";
        }
    }

    // ------------------------------------------------------------------ 触发入口（推送线程调用，只做快速判定，发射异步）

    /** GuardianEngine 新威胁登记时调用。 */
    public static void onThreatSignal(Context app, String fpid, long kid, GuardianEngine.Track t) {
        try {
            if (!preOn(app, fpid, kid)) return;
            boolean ownerExtreme = GuardianEngine.extremeLocked(t.owner);
            boolean ownerRecent = GuardianEngine.ownerRecentlyActive(t.owner, System.currentTimeMillis());
            int qualified = ownerExtreme ? 0 : countQualifiedAttacks(app, fpid, kid);
            if (!shouldTrigger(ownerExtreme, ownerRecent, qualified, t.type == 2L)) return;

            List<GuardianEngine.Track> targets = new ArrayList<>();
            if (ownerExtreme) {
                targets.add(t);   // H3/惯犯：就防它指向的这座城
            } else {
                // H2 多路并发：全部未覆盖的受威胁盟友城按优先级排序
                Map<Long, GuardianEngine.Track> byTarget = new LinkedHashMap<>();
                for (GuardianEngine.Track k : GuardianEngine.tracksSnapshot()) {
                    if (k.kid != kid || k.type != 2L || k.targetUid <= 0) continue;
                    byTarget.putIfAbsent(k.targetUid, k);
                }
                targets.addAll(byTarget.values());
                sortTargets(app, fpid, kid, targets);
            }
            String trigger = ownerExtreme ? "H3 极限惯犯 uid" + t.owner : "H2 多路并发";
            int inflight = com.carlos.vault.daily.guardian.GuardianLaunch.inflightCount();
            for (GuardianEngine.Track cand : targets) {
                Gate g = gate(true, coolingDown(fpid, kid, cand.targetUid, System.currentTimeMillis()),
                        com.carlos.vault.daily.guardian.GuardianLaunch.hasGarrisonOn(cand.targetUid),
                        com.carlos.vault.daily.guardian.GuardianLaunch.isActiveForTarget(cand.targetUid),
                        inflight, com.carlos.vault.daily.guardian.GuardianLaunch.maxConcurrent());
                if (g != Gate.ALLOW) continue;
                markCooldown(fpid, kid, cand.targetUid, System.currentTimeMillis());
                dispatch(app, fpid, kid, cand, trigger);
                if (++inflight >= com.carlos.vault.daily.guardian.GuardianLaunch.maxConcurrent() - REACTIVE_RESERVE)
                    break;   // 保留队列耗尽即停（手册 §4.2 红线 1）
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 触发判定（纯逻辑，单测锚点）。
     * H3/惯犯：EXTREME 锁存 + 近期活跃 → 任意指向盟友的行军（侦查/进攻）即触发；
     * H2：≥2 条 QUALIFIED 进攻并发 → 触发（本条须为进攻）。
     */
    static boolean shouldTrigger(boolean ownerExtreme, boolean ownerRecentlyActive,
                                 int qualifiedAttacks, boolean attackMarch) {
        if (ownerExtreme) return ownerRecentlyActive;
        return attackMarch && qualifiedAttacks >= 2;
    }

    // ------------------------------------------------------------------ 门控（QueueGuard-lite，纯逻辑，单测锚点）

    enum Gate { ALLOW, SKIP_OFF, SKIP_COOLDOWN, SKIP_GARRISONED, SKIP_REACTIVE_BUSY, SKIP_RESERVE_FULL }

    /** 门控顺序即优先级：总开关 → 冷却 → 已驻防去重 → 反应式执行中让位 → 保留队列。 */
    static Gate gate(boolean on, boolean cooling, boolean garrisoned,
                     boolean reactiveActive, int inflight, int maxConcurrent) {
        if (!on) return Gate.SKIP_OFF;
        if (cooling) return Gate.SKIP_COOLDOWN;
        if (garrisoned) return Gate.SKIP_GARRISONED;
        if (reactiveActive) return Gate.SKIP_REACTIVE_BUSY;
        if (inflight >= maxConcurrent - REACTIVE_RESERVE) return Gate.SKIP_RESERVE_FULL;
        return Gate.ALLOW;
    }

    // ------------------------------------------------------------------ 冷却账本

    static void markCooldown(String fpid, long kid, long targetUid, long nowMs) {
        if (targetUid <= 0) return;
        LAST_PRE.put(fpid + ":" + kid + ":" + targetUid, nowMs);
    }

    static boolean coolingDown(String fpid, long kid, long targetUid, long nowMs) {
        Long last = LAST_PRE.get(fpid + ":" + kid + ":" + targetUid);
        return last != null && nowMs - last < COOLDOWN_MS;
    }

    // ------------------------------------------------------------------ 内部

    /** H2 计数：当前威胁表中 QUALIFIED（ETA/战力阈值内）的进攻行军数（含刚登记的本条）。 */
    private static int countQualifiedAttacks(Context app, String fpid, long kid) {
        long pMin = GuardianEngine.powerMin(app, fpid, kid);
        long etaMax = GuardianEngine.etaMaxSec(app, fpid, kid);
        long nowS = System.currentTimeMillis() / 1000L;
        int n = 0;
        for (GuardianEngine.Track k : GuardianEngine.tracksSnapshot()) {
            if (k.kid != kid || k.type != 2L) continue;
            if (k.endServerSec - nowS > etaMax) continue;
            if (pMin > 0 && k.power < pMin) continue;
            n++;
        }
        return n;
    }

    /** 目标排序："near"=按与我城距离升序（坐标未知保持原序），否则原序（登记序≈威胁序）。 */
    private static void sortTargets(Context app, String fpid, long kid, List<GuardianEngine.Track> targets) {
        if (!"near".equals(targetPriority(app, fpid, kid))) return;
        int[] home = com.carlos.vault.daily.guardian.GuardianLaunch.ownCityPoint(app, fpid, kid);
        if (home == null) return;
        targets.sort((a, b) -> Long.compare(dist2(home, a), dist2(home, b)));
    }

    private static long dist2(int[] home, GuardianEngine.Track t) {
        long dx = t.x - home[0], dy = t.y - home[1];
        return dx * dx + dy * dy;
    }

    /** 派发：合成预驻防 Track（负 id，ETA=now+滞留窗）注册进威胁表后走既有发射链（异步）。 */
    private static void dispatch(Context app, String fpid, long kid,
                                 GuardianEngine.Track cand, String trigger) {
        GuardianEngine.Track pre = GuardianEngine.newPreTrack(fpid, kid, cand);
        if (pre == null) return;
        log(app, "🚀预驻防 → 盟友(" + pre.x + "," + pre.y
                + (pre.targetUid > 0 ? " uid" + pre.targetUid : "") + "）触发=" + trigger
                + "，滞留 " + DWELL_SEC / 60 + " 分钟（无威胁由清扫器撤回）");
        com.carlos.vault.daily.guardian.GuardianLaunch.preGarrison(app, fpid, kid, pre, trigger);
    }

    private static void log(Context c, String line) {
        try {
            java.io.File f = new java.io.File(c.getApplicationContext().getFilesDir(),
                    "guardian_log.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(new java.util.Date()) + " " + line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private GuardianPreGarrison() {
    }
}
