package com.carlos.vaultserver.formation;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-04 用户口径：不是凑够满编才出征——能满编尽量满编，没有能带多少带多少。
 *
 * <p>实测：号上 T11 盾 400 + T10 盾 69605，旧实现只统计最高档 → 只带 400 盾（或档位对不上
 * 请求超量 → 401）。现按兵种累计全部档位，并从高档往低档装兵、跨档补足。
 */
public class FormationTierFillTest {

    /** 游戏兵种 id 口径：line*10000 + tier*100（如 T11 盾=11100、T10 盾=11000）。 */
    private static long id(long tier, long line) {
        return line * 10000L + tier * 100L;
    }

    @Test
    public void baseStock_sumsAllTiers() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        counts.put(id(11, 1), 400L);      // T11 盾 400
        counts.put(id(10, 1), 69605L);    // T10 盾 69605
        counts.put(id(9, 2), 27842L);
        counts.put(id(10, 3), 41763L);
        long[] best = com.carlos.vaultserver.task.Exec.bestSoldierIds(counts);
        long[] stock = FormationCatalog.baseStock(counts, best);
        assertEquals("盾兵应累计全部档位", 70005L, stock[0]);
        assertEquals(27842L, stock[1]);
        assertEquals(41763L, stock[2]);
    }

    @Test
    public void soldierPairs_fillFromHighTierThenLower() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        counts.put(id(11, 1), 400L);
        counts.put(id(10, 1), 69605L);
        counts.put(id(9, 2), 27842L);
        counts.put(id(10, 3), 41763L);
        long[] best = com.carlos.vaultserver.task.Exec.bestSoldierIds(counts);
        long[] want = new long[]{69605L, 27842L, 41763L};
        long[] actual = new long[3];
        List<Map<String, Object>> pairs =
                FormationResolver.soldierPairsFor(counts, want, best, actual);

        assertEquals("盾实际装上", 69605L, actual[0]);
        assertEquals(27842L, actual[1]);
        assertEquals(41763L, actual[2]);
        // 盾兵跨两档：先 T11 400，再 T10 补 69205
        long shield11 = 0, shield10 = 0;
        for (Map<String, Object> p : pairs) {
            long sid = ((Number) p.get("k")).longValue();
            long cnt = ((Number) p.get("v")).longValue();
            if (sid == id(11, 1)) shield11 += cnt;
            if (sid == id(10, 1)) shield10 += cnt;
        }
        assertEquals("高档先用", 400L, shield11);
        assertEquals("低档补足", 69205L, shield10);
    }

    @Test
    public void soldierPairs_partialWhenStockShort() {
        // 库存只有 1000 盾/500 矛/0 弓：带现有的，不放弃
        Map<Long, Long> counts = new LinkedHashMap<>();
        counts.put(id(10, 1), 1000L);
        counts.put(id(10, 2), 500L);
        long[] best = com.carlos.vaultserver.task.Exec.bestSoldierIds(counts);
        long[] want = new long[]{69605L, 27842L, 41763L};
        long[] actual = new long[3];
        List<Map<String, Object>> pairs =
                FormationResolver.soldierPairsFor(counts, want, best, actual);
        assertEquals(1000L, actual[0]);
        assertEquals(500L, actual[1]);
        assertEquals("弓兵没有库存就不带", 0L, actual[2]);
        assertTrue("至少要有可发的兵", pairs.size() >= 2);
    }
}
