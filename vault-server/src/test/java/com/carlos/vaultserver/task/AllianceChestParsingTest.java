package com.carlos.vaultserver.task;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P0 修复回归（2026-10-23 联盟宝箱 / 联盟捐献）：
 * - 宝箱分类口径 1:1 网页版（cid 区间 → type 1/2/3）；
 * - Exec.mapsOf 形状兼容（List/Map/Collection + Map 键回填 id），修复
 *   “科技数据未同步/宝箱无列表/背包未同步”这类“数据已到但解析判空”的根因。
 */
public class AllianceChestParsingTest {

    @Test
    public void chestTypeFollowsWebCategoryRanges() {
        assertEquals(1, ServerTasks.allianceChestType(90100L));   // 盟友赠礼下界
        assertEquals(1, ServerTasks.allianceChestType(90101L));   // 旧实现误判为 2
        assertEquals(1, ServerTasks.allianceChestType(90199L));   // 盟友赠礼上界
        assertEquals(2, ServerTasks.allianceChestType(90200L));   // 战利品下界
        assertEquals(2, ServerTasks.allianceChestType(90250L));
        assertEquals(3, ServerTasks.allianceChestType(90300L));   // 辉煌下界
        assertEquals(3, ServerTasks.allianceChestType(90399L));
        assertEquals(1, ServerTasks.allianceChestType(0L));       // 未知 cid 保持默认 1
        assertEquals(1, ServerTasks.allianceChestType(123456L));
    }

    @Test
    public void mapsOf_acceptsListShape() {
        List<Object> list = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", 11L);
        a.put("lv", 3L);
        list.add(a);
        list.add("not-a-map");

        List<Map<String, Object>> out = Exec.mapsOf(list);
        assertEquals(1, out.size());
        assertEquals(11L, Exec.longOf(out.get(0).get("id")));
    }

    @Test
    public void mapsOf_acceptsMapShapeAndInjectsId() {
        // 网页版 sciences/chests 形状：{id: {lv, donate}}，我方旧实现只认 List → 判空
        Map<String, Object> value1 = new LinkedHashMap<>();
        value1.put("lv", 5L);
        value1.put("donate", 2L);
        Map<String, Object> value2 = new LinkedHashMap<>();
        value2.put("lv", 7L);
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("101", value1);
        map.put("202", value2);

        List<Map<String, Object>> out = Exec.mapsOf(map);
        assertEquals(2, out.size());
        assertEquals("101", String.valueOf(out.get(0).get("id")));
        assertEquals(5L, Exec.longOf(out.get(0).get("lv")));
        assertEquals("202", String.valueOf(out.get(1).get("id")));
        assertEquals(7L, Exec.longOf(out.get(1).get("lv")));
    }

    @Test
    public void mapsOf_acceptsCollectionAndIgnoresOthers() {
        java.util.Collection<Object> set = new java.util.LinkedHashSet<>(Arrays.asList(
                Map.of("id", 1L), Map.of("id", 2L)));
        assertEquals(2, Exec.mapsOf(set).size());
        assertTrue(Exec.mapsOf("garbage").isEmpty());
        assertTrue(Exec.mapsOf(null).isEmpty());
    }

    @Test
    public void kvPairs_stillParsesLegacyKvList() {
        List<Object> list = new ArrayList<>();
        list.add(Exec.kv(100101L, 5L));
        list.add(Exec.kv(100102L, 2L));
        Map<Long, Long> out = Exec.kvPairs(list);
        assertEquals(Long.valueOf(5L), out.get(100101L));
        assertEquals(Long.valueOf(2L), out.get(100102L));
    }
}
