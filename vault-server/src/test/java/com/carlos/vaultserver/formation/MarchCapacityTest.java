package com.carlos.vaultserver.formation;

import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 出征容量 v2（契约 §3.2 marchCapacity / 审计 P0-4）：真实公式、0/1/2/3 英雄分档、limits、
 * 雪猿（avalanche）宠物容量。修复「目录 capacity 恒 0」。
 */
public class MarchCapacityTest {

    private TestEngines.FakeEngine engine;

    @Before
    public void setUp() {
        engine = new TestEngines.FakeEngine();
        engine.notifies.put("notify_hero_init", TestEngines.FakeEngine.mapOf(
                "50025.0", hero(50025L, 80L, 0L),
                "50019.0", hero(50019L, 56L, 0L),
                "50006.0", hero(50006L, 6L, 0L)));
        // 指挥中心 331 lv=30 → 67000（MarchCapacityCalculator 表）；固定 = 500 + 67000
        engine.notifies.put("notify_shelter_info", TestEngines.FakeEngine.mapOf(
                "building_info", TestEngines.FakeEngine.mapOf(
                        "331", TestEngines.FakeEngine.mapOf(
                                "base", TestEngines.FakeEngine.mapOf("lv", 30L)))));
    }

    private static Map<String, Object> hero(long id, long lv, long trainingLev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("lev", lv);
        if (trainingLev > 0) m.put("training_lev", trainingLev);
        return m;
    }

    @Test
    public void capacity_isNonZeroAndTieredByHeroCount() {
        MarchCapacity.Model m = MarchCapacity.of(engine);
        assertTrue("指挥中心已同步 → initialized", m.initialized);
        assertEquals(MarchCapacity.MESSAGE_SYNCED, m.message);
        assertEquals("固定容量 = 500 + 指挥中心30级(67000)", 67500L, m.fixedCapacity);
        assertEquals("0 英雄 = 固定容量", 67500L, m.zeroHeroCapacity);
        assertEquals("1 英雄 = 固定 + 80级英雄容量(13470)", 80970L, m.oneHeroCapacity);
        assertEquals("2 英雄 = 固定 + 13470 + 11860", 92830L, m.twoHeroCapacity);
        assertEquals("3 英雄 = 固定 + 13470 + 11860 + 500(6级)", 93330L, m.threeHeroCapacity);
        assertTrue("容量必须非 0（审计 P0-4）", m.zeroHeroCapacity > 0L && m.threeHeroCapacity > 0L);

        long[] limits = m.limits();
        assertEquals(93330L, limits[0]);
        assertEquals(92830L, limits[1]);
        assertEquals(80970L, limits[2]);
        assertEquals(67500L, limits[3]);
        assertTrue(limits[0] >= limits[1] && limits[1] >= limits[2] && limits[2] >= limits[3]);
    }

    @Test
    public void toMap_hasFrozenFieldNames() {
        Map<String, Object> map = MarchCapacity.toMap(engine);
        assertTrue((Boolean) map.get("initialized"));
        assertEquals(MarchCapacity.MESSAGE_SYNCED, map.get("message"));
        assertEquals(67500L, map.get("fixedCapacity"));
        assertEquals(0L, map.get("percentageBasisPoints"));
        assertEquals(67500L, map.get("zeroHeroCapacity"));
        assertEquals(80970L, map.get("oneHeroCapacity"));
        assertEquals(92830L, map.get("twoHeroCapacity"));
        assertEquals(93330L, map.get("threeHeroCapacity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) map.get("limits");
        assertEquals(93330L, limits.get("threeHeroLimit"));
        assertEquals(92830L, limits.get("twoHeroLimit"));
        assertEquals(80970L, limits.get("oneHeroLimit"));
        assertEquals(67500L, limits.get("zeroHeroLimit"));
    }

    @Test
    public void capacityForHeroes_sumsRequestedHeroCapacity() {
        assertEquals("未指定英雄 = 固定容量", 67500L,
                MarchCapacity.capacityForHeroes(engine, List.of()));
        assertEquals(80970L, MarchCapacity.capacityForHeroes(engine, List.of(50025L)));
        assertEquals(67500L + 13470L + 11860L,
                MarchCapacity.capacityForHeroes(engine, List.of(50025L, 50019L)));
        // 未知英雄记 0（低估，宁低勿高）
        assertEquals(80970L, MarchCapacity.capacityForHeroes(engine, List.of(50025L, 999999L)));
        assertEquals(13470L, MarchCapacity.troopCapacity(engine, 50025L));
    }

    @Test
    public void trainingLevRaisesEffectiveCapacity() {
        engine.notifies.put("notify_hero_init", TestEngines.FakeEngine.mapOf(
                "50025.0", hero(50025L, 1L, 80L)));
        MarchCapacity.Model m = MarchCapacity.of(engine);
        // 1 级 65，training_lev 80 → 13470（有效等级取大）
        assertEquals(67500L + 13470L, m.oneHeroCapacity);
    }

    @Test
    public void missingPush_isNotInitialized_andCapacityZero() {
        TestEngines.FakeEngine empty = new TestEngines.FakeEngine();
        MarchCapacity.Model m = MarchCapacity.of(empty);
        assertFalse(m.initialized);
        assertEquals(MarchCapacity.MESSAGE_WAITING, m.message);
        assertEquals(0L, m.zeroHeroCapacity);
        assertEquals(0L, MarchCapacity.capacityForHeroes(empty, List.of(50025L)));
    }

    @Test
    public void avalanche_ownedSkillLevelAndActiveWindow() {
        long now = System.currentTimeMillis() / 1000L;
        engine.notifies.put("notify_pet_info", TestEngines.FakeEngine.mapOf(
                "info", List.of(TestEngines.FakeEngine.mapOf(
                        "id", 55009L, "lv", 1L, "break_lv", 9L, "is_unlock", true))));
        engine.notifies.put("notify_pet_ex_info", TestEngines.FakeEngine.mapOf(
                "info", TestEngines.FakeEngine.mapOf(
                        "use_skill_info", List.of(TestEngines.FakeEngine.mapOf(
                                "skill_id", 550091L,
                                "skill_info", TestEngines.FakeEngine.mapOf(
                                        "use_time", now - 100L, "use_skill_lv", 9L))))));

        Map<String, Object> a = MarchCapacity.avalanche(engine);
        assertTrue((Boolean) a.get("initialized"));
        assertTrue((Boolean) a.get("owned"));
        assertEquals(9L, a.get("petBreakLevel"));
        assertEquals(9L, a.get("skillLevel"));
        assertEquals("9 级 → 13500", 13500L, a.get("configuredTroopBonus"));
        assertTrue("使用后 7200 秒内生效", (Boolean) a.get("active"));
        assertEquals(13500L, a.get("troopBonus"));
        assertEquals(now - 100L + 7200L, a.get("activeUntil"));
    }

    @Test
    public void avalanche_expiredOrNotOwned_hasNoBonus() {
        long now = System.currentTimeMillis() / 1000L;
        engine.notifies.put("notify_pet_info", TestEngines.FakeEngine.mapOf(
                "info", List.of(TestEngines.FakeEngine.mapOf(
                        "id", 55009L, "lv", 1L, "break_lv", 4L, "is_unlock", true))));
        engine.notifies.put("notify_pet_ex_info", TestEngines.FakeEngine.mapOf(
                "info", TestEngines.FakeEngine.mapOf(
                        "use_skill_info", List.of(TestEngines.FakeEngine.mapOf(
                                "skill_id", 550091L,
                                "skill_info", TestEngines.FakeEngine.mapOf(
                                        "use_time", now - 8000L, "use_skill_lv", 4L))))));
        Map<String, Object> a = MarchCapacity.avalanche(engine);
        assertFalse("超过 7200 秒不生效", (Boolean) a.get("active"));
        assertEquals(0L, a.get("troopBonus"));
        assertEquals(6000L, a.get("configuredTroopBonus"));

        // 未拥有宠物
        engine.notifies.put("notify_pet_info", TestEngines.FakeEngine.mapOf("info", List.of()));
        Map<String, Object> b = MarchCapacity.avalanche(engine);
        assertFalse((Boolean) b.get("owned"));
        assertEquals(0L, MarchCapacity.avalancheTroopBonus(0L));
        assertEquals(1500L, MarchCapacity.avalancheTroopBonus(1L));
        assertEquals(15000L, MarchCapacity.avalancheTroopBonus(10L));
        assertEquals(0L, MarchCapacity.avalancheTroopBonus(11L));
    }
}
