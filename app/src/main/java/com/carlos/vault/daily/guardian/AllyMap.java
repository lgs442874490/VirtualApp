package com.carlos.vault.daily.guardian;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盟友坐标表（守护盟友 P2）：账号(fpid)+区服(kid) → uid → 坐标。
 * 数据源=联盟成员位置推送（notify_alliance_member_pos_info / _update，P1 实证仅含本盟成员）。
 * 20261016 多账号教训：同区服可开多个不同盟的号（用户在 K888 开了别盟测试号），
 * 按 kid 共享会把别盟成员污染进盟友表、发射器抓错引擎（1510/2815 之谜）——必须按 fpid 隔离。
 */
public final class AllyMap {

    /** fpid:kid → (uid → "x,y")。 */
    private static final Map<String, Map<Long, long[]>> MAP = new ConcurrentHashMap<>();

    private AllyMap() {
    }

    private static String key(String fpid, long kid) {
        return (fpid == null ? "" : fpid) + ":" + kid;
    }

    public static void put(String fpid, long kid, long uid, long x, long y) {
        if (kid <= 0 || uid <= 0) return;
        MAP.computeIfAbsent(key(fpid, kid), k -> new ConcurrentHashMap<>()).put(uid, new long[]{x, y});
    }

    /** 坐标是否命中本盟盟友城。命中返回 uid，否则 0。 */
    public static long match(String fpid, long kid, long x, long y) {
        Map<Long, long[]> m = MAP.get(key(fpid, kid));
        if (m == null) return 0L;
        for (Map.Entry<Long, long[]> e : m.entrySet()) {
            long[] xy = e.getValue();
            if (xy[0] == x && xy[1] == y) return e.getKey();
        }
        return 0L;
    }

    /** 是否本盟成员。 */
    public static boolean isAlly(String fpid, long kid, long uid) {
        Map<Long, long[]> m = MAP.get(key(fpid, kid));
        return m != null && m.containsKey(uid);
    }

    /** 移除盟友条目（使馆 1510 / 驻防 2815：真退盟或错账号兜底，防反复无效驻防）。 */
    public static void evict(String fpid, long kid, long uid) {
        Map<Long, long[]> m = MAP.get(key(fpid, kid));
        if (m != null) m.remove(uid);
    }

    public static int size(String fpid, long kid) {
        Map<Long, long[]> m = MAP.get(key(fpid, kid));
        return m == null ? 0 : m.size();
    }
}
