package com.carlos.vault.wjdr.asset;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 行军队列容量/占用纯函数（1:1 网页版 {@code MarchCapacityState.worldMarchCapacity}）。
 *
 * <p><b>容量是客户端派生值，服务端不下发</b>（协议里没有 capacity 字段）：
 * <pre>
 * raw = 1 + Σ科技{1007,1020,1027}(有等级各+1) + (VIP≥6 且 vip_active_endtime>now ? 1:0)
 *       + Σ临时 buff(buff_map[10505], expire_ts>now) + max(0, world_extra_march.value)
 * </pre>
 * 只读层 {@link #clampCapacity(int)} 夹 1..6（网页版 {@code DailyFrostfireService} 唯一硬钳点），
 * 保留 raw 值供诊断。
 *
 * <p><b>占用</b>：自己行军里排除 {@link #SPECIAL_TYPES}（巨熊 411 等不占普通队列）去重 id，
 * 再并入自己发起的集结主记录中 type=99 的 id（加入集结占普通队列）。
 *
 * <p>纯 java.util，App/Server 同源编译；Java 8 兼容。
 */
public final class QueueCapacity {

    /** 普通行军队列硬上限（游戏普通队列最高 6）。 */
    public static final int MAX_QUEUE = 6;

    /** 提升队列数的科技 tid（各 +1 队）。 */
    public static final long[] TECH_IDS = {1007L, 1020L, 1027L};

    /** VIP 队列加成所需等级。 */
    public static final long VIP_QUEUE_LEVEL = 6L;

    /**
     * 不占普通出征队列的特殊行军类型（网页版 {@code NON_NORMAL_QUEUE_MARCH_TYPES}）：
     * 301 营救/情报、411 巨熊集结、801/804 燃霜矿区、901/1001 未确认（低置信）、
     * 1002 至尊盛宴、1201 雪原商路护送、1405 欢乐乱斗空投。
     *
     * <p>注意：type=99（加入集结）**占**普通队列；411（发起巨熊集结）**不占**。
     */
    public static final Set<Long> SPECIAL_TYPES = Collections.unmodifiableSet(
            new HashSet<Long>(Arrays.asList(301L, 411L, 801L, 804L, 901L, 1001L, 1002L,
                    1201L, 1405L)));

    /** 不占普通队列。 */
    public static boolean isSpecialQueue(long marchType) {
        return SPECIAL_TYPES.contains(marchType);
    }

    /** 占普通队列。 */
    public static boolean occupiesNormalQueue(long marchType) {
        return marchType > 0 && !SPECIAL_TYPES.contains(marchType);
    }

    /**
     * 容量公式原值（未 clamp）。
     *
     * @param techCount     已研究的队列科技个数（1007/1020/1027 中 lev>0 的个数，0..3）
     * @param vip6Active    VIP 等级 ≥6 且 vip_active_endtime 未过期
     * @param buffQueues    临时 buff 提供的队列数（buff_map[10505] 未过期项求和，通常 0..1）
     * @param extraMarch    额外队列（notify_world_player_init.world_extra_march.value，≥0）
     */
    public static int rawCapacity(int techCount, boolean vip6Active, int buffQueues,
                                  long extraMarch) {
        long total = 1L
                + Math.max(0, Math.min(3, techCount))
                + (vip6Active ? 1L : 0L)
                + Math.max(0, buffQueues)
                + Math.max(0L, extraMarch);
        if (total > Integer.MAX_VALUE) total = Integer.MAX_VALUE;
        return (int) total;
    }

    /** 只读层夹取：1..6（raw≤0 视为未知→0，由调用方决定回退策略）。 */
    public static int clampCapacity(int raw) {
        if (raw <= 0) return 0;
        return Math.min(MAX_QUEUE, Math.max(1, raw));
    }

    /** 普通占用：排除特殊队列的行军数（按 id 去重）。 */
    public static int countNormalOccupied(Collection<? extends AssetSnapshot.QueueMarch> marches) {
        if (marches == null) return 0;
        Set<Long> ids = new HashSet<Long>();
        for (AssetSnapshot.QueueMarch m : marches) {
            if (m == null) continue;
            if (occupiesNormalQueue(m.type) && m.id > 0) ids.add(m.id);
        }
        return ids.size();
    }

    /** 特殊队列 type → 条数（按 id 去重；不参与空闲计算，仅对外可见）。 */
    public static Map<Long, Integer> countSpecialByType(
            Collection<? extends AssetSnapshot.QueueMarch> marches) {
        Map<Long, Integer> out = new LinkedHashMap<Long, Integer>();
        if (marches == null) return out;
        Set<Long> seen = new HashSet<Long>();
        for (AssetSnapshot.QueueMarch m : marches) {
            if (m == null || !isSpecialQueue(m.type)) continue;
            if (m.id > 0 && !seen.add(m.id)) continue;
            Integer n = out.get(m.type);
            out.put(m.type, n == null ? 1 : n + 1);
        }
        return out;
    }

    /** 普通队列 type → 条数（按 id 去重）。 */
    public static Map<Long, Integer> countNormalByType(
            Collection<? extends AssetSnapshot.QueueMarch> marches) {
        Map<Long, Integer> out = new LinkedHashMap<Long, Integer>();
        if (marches == null) return out;
        Set<Long> seen = new HashSet<Long>();
        for (AssetSnapshot.QueueMarch m : marches) {
            if (m == null || !occupiesNormalQueue(m.type)) continue;
            if (m.id > 0 && !seen.add(m.id)) continue;
            Integer n = out.get(m.type);
            out.put(m.type, n == null ? 1 : n + 1);
        }
        return out;
    }

    /** 空闲 = limitReached ? 0 : max(0, capacity - occupied - pending)。 */
    public static int idle(int capacity, int occupied, int pending, boolean limitReached) {
        if (limitReached) return 0;
        return Math.max(0, capacity - Math.max(0, occupied) - Math.max(0, pending));
    }

    private QueueCapacity() {
    }
}
