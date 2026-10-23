package com.carlos.vault.wjdr.asset;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 队列容量/占用纯函数单测（1:1 网页版 MarchCapacityState / BehemothAccountState 口径）。
 *
 * <p>证据：design/research-20261024/队列系统协议梳理.md §1（公式/占用/idle/特殊队列）。
 */
public class QueueCapacityTest {

    private static AssetSnapshot.QueueMarch march(long id, long type) {
        return new AssetSnapshot.QueueMarch(id, 1L, type, 3L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L);
    }

    private static List<AssetSnapshot.QueueMarch> marches(AssetSnapshot.QueueMarch... items) {
        return new ArrayList<AssetSnapshot.QueueMarch>(Arrays.asList(items));
    }

    @Test
    public void rawCapacity_technologyLadder() {
        assertEquals(1, QueueCapacity.rawCapacity(0, false, 0, 0L));
        assertEquals(2, QueueCapacity.rawCapacity(1, false, 0, 0L));
        assertEquals(3, QueueCapacity.rawCapacity(2, false, 0, 0L));
        assertEquals(4, QueueCapacity.rawCapacity(3, false, 0, 0L));
        // 科技最多计 3 个（1007/1020/1027）
        assertEquals(4, QueueCapacity.rawCapacity(5, false, 0, 0L));
        // 负数按 0
        assertEquals(1, QueueCapacity.rawCapacity(-2, false, 0, 0L));
    }

    @Test
    public void rawCapacity_vipBuffExtra() {
        assertEquals(2, QueueCapacity.rawCapacity(0, true, 0, 0L));
        assertEquals(3, QueueCapacity.rawCapacity(0, true, 1, 0L));
        assertEquals(4, QueueCapacity.rawCapacity(0, true, 1, 1L));
        assertEquals(6, QueueCapacity.rawCapacity(3, true, 1, 0L));
        assertEquals(7, QueueCapacity.rawCapacity(3, true, 1, 1L));
        // 额外队列负数按 0，buff 负数按 0
        assertEquals(1, QueueCapacity.rawCapacity(0, false, -2, -5L));
    }

    @Test
    public void clampCapacity_matchesWebFrostfireOnlyHardClamp() {
        assertEquals(0, QueueCapacity.clampCapacity(0));
        assertEquals(0, QueueCapacity.clampCapacity(-3));
        assertEquals(1, QueueCapacity.clampCapacity(1));
        assertEquals(5, QueueCapacity.clampCapacity(5));
        assertEquals(6, QueueCapacity.clampCapacity(6));
        assertEquals(6, QueueCapacity.clampCapacity(7));
        assertEquals(6, QueueCapacity.clampCapacity(10));
    }

    @Test
    public void specialTypes_excludeFromNormalQueue() {
        assertTrue(QueueCapacity.SPECIAL_TYPES.contains(301L));
        assertTrue(QueueCapacity.SPECIAL_TYPES.contains(411L));
        assertTrue(QueueCapacity.SPECIAL_TYPES.contains(1201L));
        // 301 营救 / 411 巨熊集结 / 1201 雪原商路：不占普通队列
        assertFalse(QueueCapacity.occupiesNormalQueue(301L));
        assertFalse(QueueCapacity.occupiesNormalQueue(411L));
        assertFalse(QueueCapacity.occupiesNormalQueue(1201L));
        assertTrue(QueueCapacity.isSpecialQueue(1405L));
        // type=4 采集 / type=99 加入集结：占普通队列
        assertTrue(QueueCapacity.occupiesNormalQueue(4L));
        assertTrue(QueueCapacity.occupiesNormalQueue(99L));
        // type=0 未知：不算占用
        assertFalse(QueueCapacity.occupiesNormalQueue(0L));
    }

    @Test
    public void countNormalOccupied_dedupesAndExcludesSpecial() {
        assertEquals(2, QueueCapacity.countNormalOccupied(marches(
                march(1L, 4L), march(2L, 99L), march(3L, 411L), march(4L, 301L))));
        // 同 id 重复只算一次
        assertEquals(1, QueueCapacity.countNormalOccupied(marches(
                march(1L, 4L), march(1L, 4L))));
        assertEquals(0, QueueCapacity.countNormalOccupied(null));
    }

    @Test
    public void countSpecialAndNormalByType() {
        Map<Long, Integer> special = QueueCapacity.countSpecialByType(marches(
                march(1L, 411L), march(2L, 411L), march(3L, 1201L), march(4L, 4L)));
        assertEquals(Integer.valueOf(2), special.get(411L));
        assertEquals(Integer.valueOf(1), special.get(1201L));
        assertFalse(special.containsKey(4L));

        Map<Long, Integer> normal = QueueCapacity.countNormalByType(marches(
                march(4L, 4L), march(5L, 4L), march(6L, 99L), march(7L, 411L)));
        assertEquals(Integer.valueOf(2), normal.get(4L));
        assertEquals(Integer.valueOf(1), normal.get(99L));
        assertFalse(normal.containsKey(411L));
    }

    @Test
    public void idle_clampedAndLimitReached() {
        assertEquals(2, QueueCapacity.idle(4, 2, 0, false));
        assertEquals(1, QueueCapacity.idle(4, 2, 1, false));
        assertEquals(0, QueueCapacity.idle(4, 2, 3, false));
        assertEquals(0, QueueCapacity.idle(4, 4, 0, false));
        // 2878 置位后空闲强制 0
        assertEquals(0, QueueCapacity.idle(4, 1, 0, true));
        // 负占用/负 pending 按 0
        assertEquals(4, QueueCapacity.idle(4, -2, -1, false));
    }

    @Test
    public void stateOccupied_countsNormalAndMassMaster99Only() {
        AssetState st = new AssetState(new AssetKey("fpid", 1L, 2L, 3L));
        st.marches.put(1L, march(1L, 4L));
        st.marches.put(2L, march(2L, 411L));       // 巨熊集结：不占
        st.marches.put(3L, march(3L, 301L));       // 营救：不占
        st.massMasters.put(4L, march(4L, 99L));    // 加入集结主记录：占
        st.massMasters.put(5L, march(5L, 501L));   // 其它集结：不占
        assertEquals(2, st.occupied());

        List<AssetSnapshot.QueueMarch> all = new ArrayList<AssetSnapshot.QueueMarch>(
                st.marches.values());
        all.addAll(st.massMasters.values());
        assertEquals(5, all.size());
    }
}
