package com.carlos.vault.daily.guardian;

import android.content.Context;

import com.carlos.vault.daily.FnParams;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守护盟友 P6：EXTREME 常驻戒备（设计 §7.16/§6.8.6；交接 20261017 遗留 #6）。
 *
 * 背景：极限压缩惯犯（EXTREME 锁存）能"检测到落地<9s"，反应式发射物理无解
 * （标定 D≥16s，单发 50% 最好 ~8.5s）——唯一可靠覆盖 = 上一场驻防不撤，
 * 下一击直接落在已有驻防上（设计 §6.8.6：EXTREME 锁存者默认 PRE_LANDED 策略）。
 *
 * 机制：`hold_garrison_min`（FnParams strategy，默认 0=关闭）>0 且对手为 EXTREME 锁存者
 * （或本次遭遇压缩 ≥3 次）时，威胁结束（敌召回/落地/战报）即武装常驻戒备：
 *   - 覆盖"战报出即撤防"默认路径：repatriate / repatriateDelayed / sweepOrphans
 *     三条撤防路径统一经 {@link #shouldHold} 让位；
 *   - 保留 N 分钟；威胁在途期间不超时（威胁结束后重新起算）；
 *   - 期间新威胁到来由在位驻防承接（不重复发射，卡秒难题消失）。
 * 关闭（默认）时全部路径与现状逐字节一致。
 */
public final class GuardianHold {

    /** fpid:kid → (targetUid → holdUntilMs)。按账号隔离（多账号不同盟，同 AllyMap 教训）。 */
    private static final Map<String, Map<Long, Long>> HOLDS = new ConcurrentHashMap<>();

    /** 常驻戒备分钟数（strategy hold_garrison_min，"0"=关闭）。 */
    public static long holdGarrisonMin(Context ctx, String fpid, long sid) {
        try {
            return Long.parseLong(FnParams.strategy(ctx, fpid, sid,
                    GuardianEngine.FN_ID, "hold_garrison_min", "0").trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 常驻戒备是否开启（子开关 + 分钟数 >0，双控同 shop/refresh_diamond 模式）。 */
    public static boolean holdOn(Context ctx, String fpid, long sid) {
        try {
            return FnParams.subOn(ctx, fpid, sid, GuardianEngine.FN_ID, "hold_garrison_min", false)
                    && holdGarrisonMin(ctx, fpid, sid) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 威胁结束时武装常驻戒备（GuardianEngine.handleDel 与发射完成两处调用）。
     * 仅当配置开启且对手为 EXTREME 锁存者 / 本次遭遇压缩 ≥3 次才武装。
     *
     * @return 武装后的 holdUntilMs；未武装返回 0
     */
    public static long armIfNeeded(Context app, String fpid, long kid, long targetUid,
                                   long ownerUid, int compressCount) {
        if (targetUid <= 0) return 0L;
        try {
            if (!holdOn(app, fpid, kid)) return 0L;
            if (!GuardianEngine.extremeLocked(ownerUid) && compressCount < 3) return 0L;
            long min = holdGarrisonMin(app, fpid, kid);
            return arm(fpid, kid, targetUid, System.currentTimeMillis(), min * 60_000L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 武装/续期（包内可见供单测）：返回 holdUntilMs。 */
    static long arm(String fpid, long kid, long targetUid, long nowMs, long holdMs) {
        if (targetUid <= 0 || holdMs <= 0) return 0L;
        long until = nowMs + holdMs;
        HOLDS.computeIfAbsent(key(fpid, kid), k -> new ConcurrentHashMap<>())
                .merge(targetUid, until, Math::max);
        return until;
    }

    /**
     * 撤防路径统一入口：true=保留驻防（跳过撤防）。
     * 威胁在途 → 永不超时（新威胁由在位驻防承接）；否则看窗口。
     */
    public static boolean shouldHold(String fpid, long kid, long targetUid, long nowMs) {
        Long until = holdUntil(fpid, kid, targetUid);
        return evaluate(nowMs, until == null ? 0L : until,
                GuardianEngine.hasThreatOn(kid, targetUid)).hold;
    }

    /** 状态行文案："" / "🛡常驻戒备中（剩余Xm）" / "🛡常驻戒备中（威胁在途）"。 */
    public static String holdStatusText(String fpid, long kid, long nowMs) {
        Map<Long, Long> m = HOLDS.get(key(fpid, kid));
        if (m == null || m.isEmpty()) return "";
        long bestRemainMs = 0L;
        for (Map.Entry<Long, Long> e : m.entrySet()) {
            Decision d = evaluate(nowMs, e.getValue(),
                    GuardianEngine.hasThreatOn(kid, e.getKey()));
            if (!d.hold) continue;
            if (d.remainingMs < 0) return "🛡常驻戒备中（威胁在途）";   // 开放态优先展示
            bestRemainMs = Math.max(bestRemainMs, d.remainingMs);
        }
        return bestRemainMs > 0
                ? "🛡常驻戒备中（剩余" + Math.max(1, bestRemainMs / 60_000L) + "m）"
                : "";
    }

    /** 状态机纯逻辑（单测锚点）。threatActive=true 时窗口过期也保留（开放态 remaining=-1）。 */
    static Decision evaluate(long nowMs, long holdUntilMs, boolean threatActive) {
        if (holdUntilMs <= 0L) return Decision.release();
        if (threatActive) {
            return holdUntilMs > nowMs
                    ? Decision.hold(holdUntilMs - nowMs)
                    : Decision.hold(-1L);
        }
        return nowMs < holdUntilMs ? Decision.hold(holdUntilMs - nowMs) : Decision.release();
    }

    /** 决策：hold + remainingMs（-1=威胁在途的开放态，无固定剩余）。 */
    static final class Decision {
        final boolean hold;
        final long remainingMs;

        private Decision(boolean hold, long remainingMs) {
            this.hold = hold;
            this.remainingMs = remainingMs;
        }

        static Decision hold(long remainingMs) { return new Decision(true, remainingMs); }

        static Decision release() { return new Decision(false, 0L); }
    }

    /** 显式解除（暂无调用方，供人工通道/后续使用）。 */
    public static void clear(String fpid, long kid, long targetUid) {
        Map<Long, Long> m = HOLDS.get(key(fpid, kid));
        if (m != null) m.remove(targetUid);
    }

    private static Long holdUntil(String fpid, long kid, long targetUid) {
        Map<Long, Long> m = HOLDS.get(key(fpid, kid));
        return m == null ? null : m.get(targetUid);
    }

    private static String key(String fpid, long kid) {
        return (fpid == null ? "" : fpid) + ":" + kid;
    }

    private GuardianHold() {
    }
}
