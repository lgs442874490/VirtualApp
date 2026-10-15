package com.carlos.vault.daily.guardian;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盟友坐标表（守护盟友 P2）：kid → uid → 坐标。数据源=联盟成员位置推送
 * （notify_alliance_member_pos_info / _update，P1 实证仅含本盟成员——天然就是"盟友"集合）。
 */
public final class AllyMap {

    /** kid → (uid → "x,y")。 */
    private static final Map<Long, Map<Long, long[]>> MAP = new ConcurrentHashMap<>();

    private AllyMap() {
    }

    public static void put(long kid, long uid, long x, long y) {
        if (kid <= 0 || uid <= 0) return;
        MAP.computeIfAbsent(kid, k -> new ConcurrentHashMap<>()).put(uid, new long[]{x, y});
    }

    /** 坐标是否命中本盟盟友城。命中返回 uid，否则 0。 */
    public static long match(long kid, long x, long y) {
        Map<Long, long[]> m = MAP.get(kid);
        if (m == null) return 0L;
        for (Map.Entry<Long, long[]> e : m.entrySet()) {
            long[] xy = e.getValue();
            if (xy[0] == x && xy[1] == y) return e.getKey();
        }
        return 0L;
    }

    /** 是否本盟成员。 */
    public static boolean isAlly(long kid, long uid) {
        Map<Long, long[]> m = MAP.get(kid);
        return m != null && m.containsKey(uid);
    }

    public static int size(long kid) {
        Map<Long, long[]> m = MAP.get(kid);
        return m == null ? 0 : m.size();
    }
}
