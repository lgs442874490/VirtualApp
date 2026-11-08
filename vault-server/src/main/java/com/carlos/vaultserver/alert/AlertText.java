package com.carlos.vaultserver.alert;

/**
 * 告警文案唯一实现（设计 §11.5 的 P1 落地）。
 *
 * <p>P1 先合并 A2 敌袭：观察点 {@code incomingAlertText} 与战争通知 {@code wecomText}
 * 输出逐字一致；兵力/昵称/联盟标签由调用方准备好传入，本类只负责拼版。
 */
public final class AlertText {

    private AlertText() {
    }

    /** 等级阈值文案：未知 / ⚪ 佯攻 / 🔴 威胁进攻（与旧 levelTag 一致）。 */
    public static String level(long troops, long feintBelow) {
        if (troops <= 0L) return "未知";
        if (feintBelow <= 0L) return "🔴 威胁进攻";
        return troops < feintBelow ? "⚪ 佯攻" : "🔴 威胁进攻";
    }

    public static String level(ThreatLevel threat) {
        if (threat == null || threat == ThreatLevel.UNKNOWN) return "未知";
        return threat == ThreatLevel.FEINT ? "⚪ 佯攻" : "🔴 威胁进攻";
    }

    /** A2 行为：2 进攻 / 102 集结出征（与旧 a2Behavior 一致）。 */
    public static String a2Behavior(long type) {
        return type == 102L ? "集结出征" : "进攻";
    }

    /**
     * A2 敌袭正文（逐字对齐旧实现）：
     * <pre>
     * 🚨 敌袭·{behavior}
     * 级别：{level}
     * 攻击者：{attackerLabel}
     * 兵力：{troops 或 未知}
     * 目标：{targetLabel 或 （未知）}({ex},{ey})
     * ETA：{hms}
     * 时间：{nowTime}
     * </pre>
     */
    public static String incoming(String behavior, String levelText, String attackerLabel,
                                  long troops, String targetLabel, int ex, int ey,
                                  long etaSeconds, String nowTime) {
        return incoming(behavior, levelText, attackerLabel, troops, targetLabel, ex, ey, true,
                etaSeconds, nowTime);
    }

    /**
     * A2 敌袭正文（逐字对齐旧实现）。{@code includeEndpoint=false} 时不拼坐标
     * （战争通知的推送帧可能没有 endpoint，旧文案此时只有「目标：（未知）」）。
     */
    public static String incoming(String behavior, String levelText, String attackerLabel,
                                  long troops, String targetLabel, int ex, int ey,
                                  boolean includeEndpoint, long etaSeconds, String nowTime) {
        StringBuilder sb = new StringBuilder("🚨 敌袭·").append(behavior).append('\n');
        sb.append("级别：").append(levelText).append('\n');
        sb.append("攻击者：").append(attackerLabel == null ? "" : attackerLabel).append('\n');
        sb.append("兵力：").append(troops > 0L ? String.valueOf(troops) : "未知").append('\n');
        sb.append("目标：");
        if (targetLabel != null && !targetLabel.isEmpty()) {
            sb.append(targetLabel);
        } else {
            sb.append("（未知）");
        }
        if (includeEndpoint) {
            sb.append('(').append(ex).append(',').append(ey).append(')');
        }
        sb.append('\n');
        sb.append("ETA：").append(hms(etaSeconds)).append('\n');
        sb.append("时间：").append(nowTime);
        return sb.toString();
    }

    /** 秒 → 「H时M分」或「M分S秒」；与观察点 hms / 战争通知 hmsShort 一致。 */
    public static String hms(long seconds) {
        long s = Math.max(0L, seconds);
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        return h > 0 ? h + "时" + m + "分" : m + "分" + sec + "秒";
    }
}
