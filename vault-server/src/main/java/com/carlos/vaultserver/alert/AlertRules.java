package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.watchpoint.WatchPointEngine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警判定唯一口径（设计 §8 的 P1 落地）。
 *
 * <p>P1 原则是「同序等价」：把 {@code WarNotify.classifyMarch} 的 13 步顺序原样搬进来，
 * 拒绝码保持旧值，两个引擎改为调用这里；行为零变化，供等价测试锁死。
 * P3 再按设计 §8.1 的统一顺序（类型归类前移、路由/触发通道）演进。
 */
public final class AlertRules {

    private AlertRules() {
    }

    // ------------------------------------------------------------------ 推送侧：敌情接收口径

    /**
     * 敌情接收口径（战争通知 + 守护盟友共用）。顺序即拒绝优先级，与旧实现逐行一致。
     */
    public static AlertVerdict classifyMarch(String proto, Map<String, Object> m) {
        if (m == null) return AlertVerdict.no("no_owner", 0L, 0L, 0L, 0L);
        long marchId = Exec.longOf(m.get("id"));
        long owner = Exec.longOf(m.get("owner"));
        long type = Exec.longOf(m.get("type"));
        long state = Exec.longOf(m.get("state"));
        if (marchId <= 0L || owner <= 0L) {
            return AlertVerdict.no("no_owner", marchId, owner, type, state);
        }
        int[] xy = endpointOf(m);
        int[] sp = startPointOf(m);
        // 打野/巨兽/采集等 PvE 行军不推（资源类由观察点单独推）。
        if (isPveMarch(m)) return AlertVerdict.no("type", marchId, owner, type, state);
        // 目标是联盟总部/旗子/王城 → 由观察点按归属推「建设/破坏」，这里不重复推。
        if (xy != null && WatchPointEngine.buildingAt(xy[0], xy[1]) != null) {
            return AlertVerdict.no("type", marchId, owner, type, state);
        }
        if (state >= 3L) return AlertVerdict.no("state", marchId, owner, type, state);
        // 侦查统一由观察点·情报推送，战争警报不再重复。
        if (type == 1L) return AlertVerdict.no("scout", marchId, owner, type, state);
        if (isRallyFollower(m, type)) return AlertVerdict.no("follower", marchId, owner, type, state);
        if (!isPvpType(type) && (proto == null || !proto.contains("mass_march"))) {
            return AlertVerdict.no("type", marchId, owner, type, state);
        }
        if (xy != null && sp != null && xy[0] == sp[0] && xy[1] == sp[1]) {
            return AlertVerdict.no("loopback", marchId, owner, type, state);
        }
        WatchPointEngine.CityInfo tgtCity = xy == null ? null : WatchPointEngine.cityAt(xy[0], xy[1]);
        WatchPointEngine.CityInfo atkCity = sp == null ? null : WatchPointEngine.cityAt(sp[0], sp[1]);
        if (tgtCity != null && tgtCity.uid == owner) {
            return AlertVerdict.no("self", marchId, owner, type, state);
        }
        long atkAid = atkCity == null ? 0L : atkCity.aid;
        long tgtAid = tgtCity == null ? 0L : tgtCity.aid;
        if (sameAlliance(atkAid, tgtAid)) {
            return AlertVerdict.no("same_alliance", marchId, owner, type, state);
        }
        boolean rally = isRallyType(type) || (proto != null && proto.contains("mass_march"));
        if (rally && (tgtCity == null || tgtAid <= 0L)) {
            return AlertVerdict.no("rally_not_city", marchId, owner, type, state);
        }
        return AlertVerdict.ok(marchId, owner, type, state);
    }

    // ------------------------------------------------------------------ 观察点：纯函数

    /**
     * 自循环/自身行军：起点==终点，或目标城就是发起者自己的城。坐标未知（-1）不参与。
     * 与 {@code WatchPointEngine.isSelfMarch} 完全同义，P1 起由本类持有实现。
     */
    public static boolean isSelfLoop(int sx, int sy, int ex, int ey, long targetUid, long owner) {
        if (sx >= 0 && sy >= 0 && sx == ex && sy == ey) return true;
        return targetUid > 0L && targetUid == owner;
    }

    /**
     * 来袭告警判定（PROTECT）：已知友盟城才告警；友军自己人不告警；随从可关。
     * 顺序：自循环 → 目标联盟未知 → 目标非友盟 → 攻击者友军 → 随从开关。
     */
    public static boolean incomingAllowed(boolean selfMarch, long ownerAid, long targetAid,
                                          Set<Long> friendAids, long type, boolean rallyFollowerAlerts) {
        if (selfMarch) return false;
        if (targetAid <= 0L) return false;
        if (friendAids == null || !friendAids.contains(targetAid)) return false;
        if (ownerAid > 0L && friendAids.contains(ownerAid)) return false;
        if (!rallyFollowerAlerts && (type == 99L || type == 100L)) return false;
        return true;
    }

    /** 敌情告警判定（MONITOR）：同联盟/自家城之间的行军不算“打谁”。 */
    public static boolean monitorAllowed(long ownerAid, long targetAid) {
        return !(ownerAid > 0L && targetAid > 0L && ownerAid == targetAid);
    }

    // ------------------------------------------------------------------ 判定原语

    /** PvE 判定（deep 口径，与观察点/战争通知共用同一实现）。 */
    public static boolean isPveMarch(Map<String, Object> m) {
        return WatchPointEngine.isPveMarchDeep(m);
    }

    /** PvE 命中特征；空=非 PvE。 */
    public static String pveReason(Map<?, ?> m) {
        return WatchPointEngine.pveMarchReason(m);
    }

    /** 敌情类型：2 进攻 / 102 集结出征（100 发起集结归 A1）。 */
    public static boolean isPvpType(long type) {
        return type == 2L || type == 102L;
    }

    /** 集结相关类型：10/12 集结中、99 随从、100 发起集结、102 集结主体。 */
    public static boolean isRallyType(long type) {
        return type == 10L || type == 12L || type == 99L || type == 100L || type == 102L;
    }

    /** 是否「加入集结」帧：type=99 或带 mass_slave。 */
    public static boolean isRallyFollower(Map<String, Object> m, long type) {
        if (type == 99L) return true;
        Object slave = m.get("mass_slave");
        if (slave == null) slave = m.get("massSlave");
        if (slave instanceof Map) return !((Map<?, ?>) slave).isEmpty();
        if (slave instanceof List) return !((List<?>) slave).isEmpty();
        return slave != null && !String.valueOf(slave).trim().isEmpty()
                && !"0".equals(String.valueOf(slave).trim());
    }

    /** 同联盟：两边 aid 都已知且相等。 */
    public static boolean sameAlliance(long atkAid, long tgtAid) {
        return atkAid > 0L && tgtAid > 0L && atkAid == tgtAid;
    }

    /** 威胁分级（只影响文案）；阈值 <=0 时非零兵力一律威胁。 */
    public static ThreatLevel threatLevel(long troops, long feintBelow) {
        if (troops <= 0L) return ThreatLevel.UNKNOWN;
        if (feintBelow <= 0L) return ThreatLevel.THREAT;
        return troops < feintBelow ? ThreatLevel.FEINT : ThreatLevel.THREAT;
    }

    // ------------------------------------------------------------------ 坐标解析（与旧实现一致）

    /** endpoint 坐标：endpoint{x,y} 优先，退化 target{x,y}；取不到或 (0,0) 返回 null。 */
    public static int[] endpointOf(Map<String, Object> m) {
        for (String key : new String[]{"endpoint", "target"}) {
            Map<String, Object> p = Exec.mapOf(m.get(key));
            if (p == null) continue;
            int x = (int) Exec.longOf(p.get("x"));
            int y = (int) Exec.longOf(p.get("y"));
            if (x != 0 || y != 0) return new int[]{x, y};
        }
        return null;
    }

    /** 发起人坐标：startpoint{x,y}；取不到或 (0,0) 返回 null。 */
    public static int[] startPointOf(Map<String, Object> m) {
        Map<String, Object> p = Exec.mapOf(m.get("startpoint"));
        if (p == null) return null;
        int x = (int) Exec.longOf(p.get("x"));
        int y = (int) Exec.longOf(p.get("y"));
        if (x == 0 && y == 0) return null;
        return new int[]{x, y};
    }
}
