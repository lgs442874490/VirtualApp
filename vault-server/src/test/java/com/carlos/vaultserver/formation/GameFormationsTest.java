package com.carlos.vaultserver.formation;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.carlos.vaultserver.formation.TestEngines.FakeEngine.mapOf;
import static com.carlos.vaultserver.formation.TestEngines.FakeEngine.pair;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 游戏内编队 prefab 解析（契约 §3.5 / 审计 P1-3）。
 *
 * <p>形状来自三方一致证据：spb schema（{@code notify_soldier_init.request.prefab[] =
 * formation_build_pair_with_name{id, prefab:formation_build_with_name{hero_id[], soldier[],
 * formation_name, formation_rate[]}}}）、release288 {@code BehemothAccountState.soldierInit} 解析、
 * 生产账号登录推送。
 */
public class GameFormationsTest {

    /** 生产形状的 notify_soldier_init 内容（soldier 库存 + 6 槽 prefab，含空槽）。 */
    private static Map<String, Object> soldierInit() {
        List<Map<String, Object>> prefab = new ArrayList<>();
        Map<String, Object> slot1 = mapOf(
                "id", 1L,
                "prefab", mapOf(
                        "hero_id", List.of(pair(1, 50025), pair(2, 50019), pair(3, 50022)),
                        "soldier", List.of(pair(10300, 88885), pair(20100, 3629), pair(30300, 88886)),
                        "formation_name", "赫克托"));
        Map<String, Object> slot2 = mapOf(
                "id", 2L,
                "prefab", mapOf(
                        "hero_id", List.of(pair(1, 50006)),
                        "formation_rate", List.of(pair(1, 10), pair(2, 10), pair(3, 80))));
        Map<String, Object> slot3 = mapOf(
                "id", 3L,
                "prefab", mapOf("hero_id", List.of(), "soldier", List.of(),
                        "formation_rate", List.of(), "formation_name", "空队"));
        prefab.add(slot1);
        prefab.add(slot2);
        prefab.add(slot3);
        return mapOf(
                "soldier", List.of(
                        mapOf("id", 11000L, "count", 20000L),
                        mapOf("id", 21000L, "count", 1549L),
                        mapOf("id", 31000L, "count", 28451L)),
                "prefab", prefab);
    }

    @Test
    public void parse_realPrefabShape_dropsEmptySlot() {
        List<GameFormations.Slot> slots = GameFormations.parse(soldierInit());
        assertNotNull(slots);
        assertEquals("空槽丢弃 → 2 槽", 2, slots.size());

        GameFormations.Slot s1 = slots.get(0);
        assertEquals(1, s1.id);
        assertEquals("赫克托", s1.name);
        assertEquals(List.of(50025L, 50019L, 50022L), s1.heroIds);
        assertTrue(s1.hasSoldiers);
        assertFalse(s1.hasRates);
        assertEquals("MANUAL", s1.allocationMode());
        long[] counts = s1.soldiersByLine();
        assertEquals(88885L, counts[0]);
        assertEquals(3629L, counts[1]);
        assertEquals(88886L, counts[2]);
        assertEquals(181400L, s1.totalSoldiers());

        GameFormations.Slot s2 = slots.get(1);
        assertEquals("formation_name 为空 → 「编队 2」", "编队 2", s2.name);
        assertEquals(List.of(50006L), s2.heroIds);
        assertTrue(s2.hasRates);
        assertFalse(s2.hasSoldiers);
        assertEquals("PERCENTAGE", s2.allocationMode());
        assertEquals(10, s2.ratios[0]);
        assertEquals(80, s2.ratios[2]);
    }

    @Test
    public void parse_noPrefabField_returnsNullToKeepOldCache() {
        Map<String, Object> onlySoldiers = mapOf("soldier", List.of(mapOf("id", 11000L, "count", 1L)));
        assertNull("没有 prefab 字段 → null（保持旧缓存）", GameFormations.parse(onlySoldiers));
        assertNull(GameFormations.parse(null));
        // prefab 存在但全空 → 空列表（清空）
        assertTrue(GameFormations.parse(mapOf("prefab", List.of())).isEmpty());
    }

    @Test
    public void parse_dataWrappedContent() {
        Map<String, Object> wrapped = mapOf("data", soldierInit());
        List<GameFormations.Slot> slots = GameFormations.parse(wrapped);
        assertEquals(2, slots.size());
        assertEquals(1, slots.get(0).id);
    }

    @Test
    public void toMap_matchesCatalogContract() {
        GameFormations.Slot s1 = GameFormations.parse(soldierInit()).get(0);
        Map<String, Object> m = s1.toMap();
        assertEquals("游戏编队 id = -slotId", -1L, ((Number) m.get("id")).longValue());
        assertEquals("GAME", m.get("source"));
        assertEquals(1L, ((Number) m.get("sourceId")).longValue());
        assertEquals("赫克托", m.get("name"));
        assertEquals(List.of(50025L, 50019L, 50022L), m.get("heroIds"));
        assertEquals("captainHeroId=heroIds[0]", 50025L, ((Number) m.get("captainHeroId")).longValue());
        assertEquals("MANUAL", m.get("allocationMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> soldiers = (Map<String, Object>) m.get("soldiers");
        assertEquals(88885L, ((Number) soldiers.get("infantry")).longValue());
        assertEquals(88886L, ((Number) soldiers.get("marksman")).longValue());
        assertEquals(181400L, ((Number) m.get("totalSoldiers")).longValue());
        assertEquals(Boolean.FALSE, m.get("builtIn"));
    }

    @Test
    public void prefab_restorePayload_keepsOriginalSoldierIds() {
        List<GameFormations.Slot> slots = GameFormations.parse(soldierInit());
        Map<String, Object> soldierPrefab = slots.get(0).prefab();
        assertNotNull(soldierPrefab.get("hero_id"));
        assertEquals("original soldier id 必须保留（恢复要回发游戏）", true,
                hasPair(soldierPrefab.get("soldier"), 10300L, 88885L));
        assertEquals("赫克托", soldierPrefab.get("formation_name"));

        Map<String, Object> ratePrefab = slots.get(1).prefab();
        assertEquals("formation_rate 补齐 k=1/2/3", 3,
                ((List<?>) ratePrefab.get("formation_rate")).size());
        assertFalse("回退显示名不写回 formation_name", ratePrefab.containsKey("formation_name"));
        assertFalse("比例槽不写 soldier", ratePrefab.containsKey("soldier"));
    }

    @Test
    public void toMemorySlot_hasDisplayFieldsAndPrefab() {
        Map<String, Object> slot = GameFormations.parse(soldierInit()).get(0).toMemorySlot();
        assertEquals(1L, ((Number) slot.get("id")).longValue());
        assertEquals("赫克托", slot.get("name"));
        assertEquals(List.of(50025L, 50019L, 50022L), slot.get("heroIds"));
        assertEquals(50025L, ((Number) slot.get("captainHeroId")).longValue());
        assertEquals("MANUAL", slot.get("allocationMode"));
        assertNotNull(slot.get("soldiers"));
        assertNotNull(slot.get("ratio"));
        assertNotNull("记忆快照保留原始 prefab", slot.get("prefab"));
    }

    @Test
    public void applyUpdate_incrementalFormationUpdate() {
        List<GameFormations.Slot> before = GameFormations.parse(soldierInit());
        // notify_soldier_formation_update = {id, formation:{id, soldier:[{id,count}], hero_id:[{k,v}]}}
        Map<String, Object> update = mapOf(
                "id", 1L,
                "formation", mapOf(
                        "id", 1L,
                        "hero_id", List.of(pair(1, 50041), pair(2, 50042)),
                        "soldier", List.of(mapOf("id", 10300L, "count", 12345L))));
        List<GameFormations.Slot> after = GameFormations.applyUpdate(before, update);
        assertEquals(2, after.size());
        GameFormations.Slot s1 = after.get(0);
        assertEquals(List.of(50041L, 50042L), s1.heroIds);
        assertEquals(12345L, s1.soldiersByLine()[0]);
        assertEquals("名字沿用旧槽", "赫克托", s1.name);
    }

    @Test
    public void applyUpdate_newSlotAppended_unknownIgnored() {
        List<GameFormations.Slot> before = GameFormations.parse(soldierInit());
        Map<String, Object> add = mapOf("id", 4L, "formation", mapOf(
                "id", 4L, "hero_id", List.of(pair(1, 50008))));
        List<GameFormations.Slot> after = GameFormations.applyUpdate(before, add);
        assertEquals(3, after.size());
        assertEquals(4, after.get(2).id);
        // 没有任何 hero/soldier 的更新 → 原样返回
        Map<String, Object> empty = mapOf("id", 5L, "formation", mapOf("id", 5L));
        assertEquals(before, GameFormations.applyUpdate(before, empty));
    }

    @Test
    public void parse_unknownHeroPositiveFiltered_andHeroMaxThree() {
        Map<String, Object> prefab = mapOf("id", 7L, "prefab", mapOf(
                "hero_id", List.of(pair(1, 50025), pair(2, 50019), pair(3, 50022),
                        pair(4, 50041), pair(5, 50042)),
                "soldier", List.of(pair(10300, 10))));
        List<GameFormations.Slot> slots = GameFormations.parsePrefab(List.of(prefab));
        assertEquals(1, slots.size());
        assertEquals("英雄上限 3（盾/矛/弓各一）", 3, slots.get(0).heroIds.size());
        assertEquals(List.of(50025L, 50019L, 50022L), slots.get(0).heroIds);
    }

    private static boolean hasPair(Object list, long k, long v) {
        if (!(list instanceof List)) return false;
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            if (((Number) m.get("k")).longValue() == k
                    && ((Number) m.get("v")).longValue() == v) return true;
        }
        return false;
    }
}
