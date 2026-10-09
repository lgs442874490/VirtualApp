package com.carlos.vault.daily;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * M2 回归：兵营可训练档位判定（对齐原版 DailyBarrackCatalog/DailyBarrackState）。
 * 真机事故背景：旧口径按"已拥有士兵最高档"推导，T11/T10/T11 兵营在未练过高档兵时被当成 T3。
 */
public class BarrackTierTest {

    private static Map<Long, Long> tech(long... kv) {
        Map<Long, Long> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void tierTable_matchesOriginalBarrackLevels() {
        // 原版 BARRACK_LEVELS：T1..T10 = {1,4,7,11,13,16,19,22,26,30}（免科技）
        long[] lv = new long[4];
        // 兵营 3 级 → 仅 T1（T2 需 4 级）
        lv[1] = 3;
        assertArrayEquals(new int[]{0, 1, 0, 0}, TownTasks.computeUnlockTiers(lv, null, null));
        // 兵营 10 级 → T3（T4 需 11 级）
        lv[1] = 10;
        assertArrayEquals(new int[]{0, 3, 0, 0}, TownTasks.computeUnlockTiers(lv, null, null));
        // 兵营 11 级 → T4（T5 需 13）
        lv[1] = 11;
        assertArrayEquals(new int[]{0, 4, 0, 0}, TownTasks.computeUnlockTiers(lv, null, null));
        // 兵营 29 级 → T9（T10 需 30）
        lv[1] = 29;
        assertArrayEquals(new int[]{0, 9, 0, 0}, TownTasks.computeUnlockTiers(lv, null, null));
    }

    @Test
    public void t11_requiresLevel30AndSparTech() {
        long[] lv = {0, 30, 30, 30};
        // 无科技数据（notify 缺失）→ 保守拦截 T11，回到 T10（对齐原版：科技查不到不放行）
        assertArrayEquals(new int[]{0, 10, 10, 10}, TownTasks.computeUnlockTiers(lv, null, null));
        // spar 科技全空 → 同样 T10
        assertArrayEquals(new int[]{0, 10, 10, 10},
                TownTasks.computeUnlockTiers(lv, new HashMap<>(), new HashMap<>()));
        // 只有盾的 7007 研究了 → 盾 T11，矛弓 T10
        assertArrayEquals(new int[]{0, 11, 10, 10},
                TownTasks.computeUnlockTiers(lv, tech(7007, 1), new HashMap<>()));
        // 全部 T11 科技（盾7007/矛8007/弓9007）
        assertArrayEquals(new int[]{0, 11, 11, 11},
                TownTasks.computeUnlockTiers(lv, tech(7007, 2, 8007, 1, 9007, 1), new HashMap<>()));
    }

    @Test
    public void t12_requiresRefineTech() {
        long[] lv = {0, 30, 30, 30};
        Map<Long, Long> spar = tech(7007, 1, 8007, 1, 9007, 1);
        // refine 科技全空 → T11
        assertArrayEquals(new int[]{0, 11, 11, 11}, TownTasks.computeUnlockTiers(lv, spar, new HashMap<>()));
        // 只有弓的 12006 → 弓 T12，其余 T11
        assertArrayEquals(new int[]{0, 11, 11, 12},
                TownTasks.computeUnlockTiers(lv, spar, tech(12006, 1)));
        // 三系 refine 齐 → 全 T12
        assertArrayEquals(new int[]{0, 12, 12, 12},
                TownTasks.computeUnlockTiers(lv, spar, tech(10006, 1, 11006, 1, 12006, 1)));
    }

    @Test
    public void realDeviceCase_tezhu_t11_t10_t11() {
        // 真机铁柱：盾营 30 级+7007、矛营 30 级但未研究 8007（T10 封顶）、弓营 30 级+9007
        long[] lv = {0, 30, 30, 30};
        Map<Long, Long> spar = tech(7007, 1, 9007, 1);
        assertArrayEquals(new int[]{0, 11, 10, 11}, TownTasks.computeUnlockTiers(lv, spar, null));
    }

    @Test
    public void mixedLevels_independentPerLine() {
        // 三营等级不同各自独立：3/13/22 → T1/T5/T8
        long[] lv = {0, 3, 13, 22};
        assertArrayEquals(new int[]{0, 1, 5, 8}, TownTasks.computeUnlockTiers(lv, null, null));
    }
}
