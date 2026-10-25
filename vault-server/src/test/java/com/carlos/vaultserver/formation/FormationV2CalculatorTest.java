package com.carlos.vaultserver.formation;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T3（v2 契约 §3.4 / 旧版 release288 算法）纯函数单测：
 * 最大余数分配、百分比分配、容量扩张（expandedFixedTarget）、同比缩放（scaleTotal）、
 * 库存夹取、比例合法性。公式与旧版 {@code DailyRoleFormationCalculator} /
 * {@code IntegerAllocationSupport} 逐行对应。
 */
public class FormationV2CalculatorTest {

    // ---------------- 最大余数法 ----------------

    @Test
    public void largestRemainders_basicAndTiesShieldFirst() {
        // 整除：400/300/300
        assertArrayEquals(new long[]{400, 300, 300},
                FormationResolver.allocateLargestRemainders(1000, 40, 30, 30));
        // 余数并列：10 按 1:1:1 → 3/3/3 余 1，并列取序号小（盾）→ 4/3/3
        assertArrayEquals(new long[]{4, 3, 3},
                FormationResolver.allocateLargestRemainders(10, 1, 1, 1));
        // 7 按 1:1:2 → floor 1/1/3（余 3/3/2，补 2 份给盾/矛）→ 2/2/3
        assertArrayEquals(new long[]{2, 2, 3},
                FormationResolver.allocateLargestRemainders(7, 1, 1, 2));
        // 总量守恒
        long[] a = FormationResolver.allocateLargestRemainders(20261025L, 50, 20, 30);
        assertEquals(20261025L, a[0] + a[1] + a[2]);
        // target=0 → 全 0
        assertArrayEquals(new long[]{0, 0, 0},
                FormationResolver.allocateLargestRemainders(0, 1, 1, 1));
    }

    // ---------------- 百分比分配（PERCENTAGE / fitFormation） ----------------

    @Test
    public void allocatePercentages_contractSample_10_10_80() {
        // 契约 §5 真样例：容量 150770，比例 10/10/80 → 15077/15077/120616
        long[] s = FormationResolver.allocatePercentages(150770L, 10, 10, 80);
        assertArrayEquals(new long[]{15077, 15077, 120616}, s);
        assertEquals(150770L, s[0] + s[1] + s[2]);
    }

    @Test
    public void allocatePercentages_roundedTargetAndRemainder() {
        // 565 × 100% = 565；50/20/30 → floor 282/113/169=564，余 1（盾/弓余数并列 50）→ 盾 +1
        assertArrayEquals(new long[]{283, 113, 169},
                FormationResolver.allocatePercentages(565L, 50, 20, 30));
        // 比例和 50%：target=round_half_up(1000*50/100)=500，再按 500×pct/100 分配
        long[] half = FormationResolver.allocatePercentages(1000L, 25, 25, 0);
        assertArrayEquals(new long[]{250, 250, 0}, half);
    }

    @Test
    public void allocatePercentages_invalidInputsReturnZeros() {
        assertArrayEquals(new long[]{0, 0, 0}, FormationResolver.allocatePercentages(1000L, 0, 0, 0));
        assertArrayEquals(new long[]{0, 0, 0}, FormationResolver.allocatePercentages(1000L, 60, 60, 0));
        assertArrayEquals(new long[]{0, 0, 0}, FormationResolver.allocatePercentages(1000L, -1, 50, 51));
        assertArrayEquals(new long[]{0, 0, 0}, FormationResolver.allocatePercentages(0L, 50, 20, 30));
        assertArrayEquals(new long[]{0, 0}, FormationResolver.allocatePercentages(1000L, 50, 50));
    }

    // ---------------- 同比缩放 / 容量扩张 ----------------

    @Test
    public void scaleTotal_roundHalfUp_cappedAndInvalid() {
        assertEquals("100 × 75/100 = 75", 75L, FormationResolver.scaleTotal(100, 100, 75));
        assertEquals("10 × 25/100 = 2.5 → 四舍五入 3", 3L,
                FormationResolver.scaleTotal(10, 100, 25));
        assertEquals("3 × 50/100 = 1.5 → 2", 2L, FormationResolver.scaleTotal(3, 100, 50));
        assertEquals("5 × 7/10 = 3.5 → 四舍五入 4", 4L,
                FormationResolver.scaleTotal(5, 10, 7));
        // 非法参数返回 -1（旧版抛错；服务端 WARN+跳过缩放）
        assertEquals(-1L, FormationResolver.scaleTotal(10, 0, 5));
        assertEquals(-1L, FormationResolver.scaleTotal(10, 5, 6));
        assertEquals(-1L, FormationResolver.scaleTotal(-1, 10, 5));
        assertEquals(-1L, FormationResolver.scaleTotal(5, 10, -1));
    }

    @Test
    public void expandedFixedTarget_contractFormula() {
        // 无 buff / 无固定加成：min(configured, limit)
        assertEquals(1000L, FormationResolver.expandedFixedTarget(1000, 0, 0, 50000));
        assertEquals("超容量被夹到 capacity", 565L,
                FormationResolver.expandedFixedTarget(1000, 0, 0, 565));
        // 容量百分比 Buff +20%：floor(1000 × 1.2) = 1200
        assertEquals(1200L, FormationResolver.expandedFixedTarget(1000, 2000, 0, 50000));
        // 固定加成（雪猿/巨熊运行时增益；服务端当前传 0）：floor((1000+500) × 1.2)=1800
        assertEquals(1800L, FormationResolver.expandedFixedTarget(1000, 2000, 500, 50000));
        // capacity 上限优先
        assertEquals(1100L, FormationResolver.expandedFixedTarget(1000, 2000, 0, 1100));
        // 负数参数 → -1（调用方 WARN+跳过缩放）
        assertEquals(-1L, FormationResolver.expandedFixedTarget(-1, 0, 0, 100));
        assertEquals(-1L, FormationResolver.expandedFixedTarget(10, 0, -1, 100));
        assertEquals(-1L, FormationResolver.expandedFixedTarget(10, 0, 0, -1));
    }

    @Test
    public void clampSoldiersToStock_perLineAndNoMutation() {
        long[] want = new long[]{50000, 50000, 50000};
        long[] stock = new long[]{20000, 1549, 28451};
        long[] clamped = FormationResolver.clampSoldiersToStock(want, stock);
        assertArrayEquals(new long[]{20000, 1549, 28451}, clamped);
        assertArrayEquals("不修改入参", new long[]{50000, 50000, 50000}, want);
        // 库存充足 → 原样
        assertArrayEquals(new long[]{100, 200, 300},
                FormationResolver.clampSoldiersToStock(new long[]{100, 200, 300},
                        new long[]{1000, 1000, 1000}));
        // 库存缺失 → 全 0（调用方 SKIP，不超发）
        assertArrayEquals(new long[]{0, 0, 0}, FormationResolver.clampSoldiersToStock(want, null));
        assertArrayEquals(new long[]{0, 0, 0},
                FormationResolver.clampSoldiersToStock(new long[]{5, 5, 5}, new long[]{0, 0, 0}));
    }

    // ---------------- 比例合法性 ----------------

    @Test
    public void validatePercentages_rules() {
        assertNull(FormationResolver.validatePercentages(new long[]{50, 20, 30}));
        assertNull("合计可以 <100（旧版 0<sum≤100）",
                FormationResolver.validatePercentages(new long[]{10, 10, 10}));
        assertTrue(FormationResolver.validatePercentages(new long[]{0, 0, 0}).contains("大于 0"));
        assertTrue(FormationResolver.validatePercentages(new long[]{60, 50, 0}).contains("不能超过 100"));
        assertTrue(FormationResolver.validatePercentages(new long[]{-1, 50, 51}).contains("0..100"));
        assertTrue(FormationResolver.validatePercentages(null).contains("缺失"));
    }
}
