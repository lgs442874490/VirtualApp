package com.carlos.vaultserver.formation;

import org.json.JSONObject;

import org.junit.Before;
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
 * 编队目录 v2（契约 §3.1/§3.2）：字段全量、英雄名去引号、uid 键、容量非 0、游戏编队、
 * 自动比例、记忆/巨熊默认、空快照保护、notify 增量。
 */
public class FormationCatalogV2Test {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final String UID = "88280792";

    private TestEngines.FakeEngine engine;

    @Before
    public void setUp() {
        engine = new TestEngines.FakeEngine();
        FormationCatalog.reset(engine);
        presetPushes(engine);
        FormationCatalog.identify(engine, FPID, SID, UID);
    }

    /** 生产登录期推送集合（hero_init/soldier_init+shelter/world/pet）。 */
    private static void presetPushes(TestEngines.FakeEngine e) {
        e.notifies.put("notify_hero_init", mapOf(
                // 生产原始帧形状：{ "50022.0": {id,lev,star,...} }（无 name/power）
                "50025.0", mapOf("id", 50025L, "lev", 80L, "star", 26L),
                "50019.0", mapOf("id", 50019L, "lev", 56L, "star", 5L),
                "50006.0", mapOf("id", 50006L, "lev", 6L, "star", 0L),
                "50041.0", mapOf("id", 50041L, "lev", 80L, "star", 30L)));
        List<Map<String, Object>> prefab = new ArrayList<>();
        prefab.add(mapOf("id", 1L, "prefab", mapOf(
                "hero_id", List.of(pair(1, 50025), pair(2, 50019), pair(3, 50022)),
                "soldier", List.of(pair(10300, 88885), pair(20100, 3629), pair(30300, 88886)),
                "formation_name", "赫克托")));
        prefab.add(mapOf("id", 2L, "prefab", mapOf(
                "hero_id", List.of(pair(1, 50006)),
                "formation_rate", List.of(pair(1, 10), pair(2, 10), pair(3, 80)))));
        e.notifies.put("notify_soldier_init", mapOf(
                "soldier", List.of(
                        mapOf("id", 11000L, "count", 20000L),
                        mapOf("id", 21000L, "count", 1549L),
                        mapOf("id", 31000L, "count", 28451L)),
                "prefab", prefab));
        e.notifies.put("notify_shelter_info", mapOf(
                "building_info", mapOf("331", mapOf("base", mapOf("lv", 30L)))));
        e.notifies.put("notify_world_player_init", mapOf("world", mapOf("capacity", 5L)));
        e.notifies.put("notify_pet_info", mapOf("info", List.of(mapOf(
                "id", 55009L, "lv", 1L, "break_lv", 9L, "is_unlock", true))));
        e.notifies.put("notify_pet_ex_info", mapOf("info", mapOf(
                "use_skill_info", List.of(mapOf(
                        "skill_id", 550091L,
                        "skill_info", mapOf("use_time", 0L, "use_skill_lv", 9L))))));
    }

    @Test
    public void snapshot_v2_hasFrozenFields() {
        FormationCatalog.capture(engine);
        Map<String, Object> snap = FormationCatalog.snapshot(engine);

        assertEquals(2, ((Number) snap.get("version")).intValue());
        assertEquals(FPID, snap.get("fpid"));
        assertEquals(SID, ((Number) snap.get("sid")).longValue());
        assertEquals(UID, snap.get("uid"));
        assertTrue("指挥中心已同步 → configured", (Boolean) snap.get("configured"));
        assertTrue((Boolean) snap.get("automaticAvailable"));
        assertTrue(snap.containsKey("capturedAt"));

        // 旧 APK 兼容键仍在（一版过渡）
        assertTrue(snap.containsKey("capacity"));
        assertTrue(snap.containsKey("soldiers"));
    }

    @Test
    public void heroes_fullFields_andNoQuoteNames() {
        FormationCatalog.capture(engine);
        List<Map<String, Object>> heroes = FormationCatalog.heroOptions(engine);
        assertEquals(4, heroes.size());
        // 强到弱：power 全 0 → level 降序（80,80,56,6）
        Map<String, Object> h25 = findHero(heroes, 50025L);
        assertEquals("诺拉", h25.get("name"));
        assertFalse("名字不得带引号（根因修复）", String.valueOf(h25.get("name")).contains("\""));
        assertEquals(2, ((Number) h25.get("profession")).intValue());
        assertEquals(2, ((Number) h25.get("professionType")).intValue());
        assertEquals("矛兵", h25.get("professionName"));
        assertEquals(80L, ((Number) h25.get("level")).longValue());
        assertEquals(26L, ((Number) h25.get("star")).longValue());
        assertEquals("26 星 → 展示 4 星", 4, ((Number) h25.get("starDisplay")).intValue());
        assertEquals(5, ((Number) h25.get("generation")).intValue());
        assertEquals("80 级容量 13470", 13470L, ((Number) h25.get("troopCapacity")).longValue());
        assertEquals(Boolean.TRUE, h25.get("available"));
        assertEquals("AVAILABLE", h25.get("availabilityState"));
        assertEquals("英雄在家", h25.get("availabilityReason"));

        Map<String, Object> h19 = findHero(heroes, 50019L);
        assertEquals("无名", h19.get("name"));
        assertEquals("盾兵", h19.get("professionName"));
        assertEquals(6, ((Number) h19.get("generation")).intValue());

        Map<String, Object> h41 = findHero(heroes, 50041L);
        assertEquals(10, ((Number) h41.get("generation")).intValue());
        assertEquals("格里高利", h41.get("name"));
    }

    @Test
    public void soldiers_bestIdsAndLegacyShape() {
        FormationCatalog.capture(engine);
        long[] stock = FormationCatalog.soldierStock(engine);
        assertEquals(20000L, stock[0]);
        assertEquals(1549L, stock[1]);
        assertEquals(28451L, stock[2]);
        long[] ids = FormationCatalog.bestSoldierIds(engine);
        assertEquals(11000L, ids[0]);
        assertEquals(21000L, ids[1]);
        assertEquals(31000L, ids[2]);

        @SuppressWarnings("unchecked")
        Map<String, Object> soldiers = (Map<String, Object>)
                FormationCatalog.snapshot(engine).get("soldiers");
        assertEquals(20000L, ((Number) soldiers.get("infantry")).longValue());
        assertNotNull(soldiers.get("best_ids"));
    }

    @Test
    public void marchCapacity_isNonZero_andHasLimits() {
        FormationCatalog.capture(engine);
        Map<String, Object> mc = FormationCatalog.marchCapacity(engine);
        assertTrue((Boolean) mc.get("initialized"));
        assertEquals("登录容量状态已同步", mc.get("message"));
        assertTrue("固定容量必须非 0（审计 P0-4）",
                ((Number) mc.get("fixedCapacity")).longValue() > 0L);
        long three = ((Number) mc.get("threeHeroCapacity")).longValue();
        long zero = ((Number) mc.get("zeroHeroCapacity")).longValue();
        assertTrue(three > zero);
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) mc.get("limits");
        assertEquals(three, ((Number) limits.get("threeHeroLimit")).longValue());
        assertEquals(zero, ((Number) limits.get("zeroHeroLimit")).longValue());
        assertEquals("容量公式口径（Σ troopCapacity）",
                zero + 13470L + 11860L + 500L,
                FormationCatalog.capacityForHeroes(engine, List.of(50025L, 50019L, 50006L)));
    }

    @Test
    public void gameFormations_mirrorPrefab() {
        FormationCatalog.capture(engine);
        List<Map<String, Object>> list = FormationCatalog.gameFormations(engine);
        assertEquals(2, list.size());
        Map<String, Object> f = list.get(0);
        assertEquals(-1L, ((Number) f.get("id")).longValue());
        assertEquals("GAME", f.get("source"));
        assertEquals(1L, ((Number) f.get("sourceId")).longValue());
        assertEquals("赫克托", f.get("name"));
        assertEquals(List.of(50025L, 50019L, 50022L), f.get("heroIds"));
        assertEquals(50025L, ((Number) f.get("captainHeroId")).longValue());
        assertEquals(181400L, ((Number) f.get("totalSoldiers")).longValue());
        assertEquals(2, FormationCatalog.gameSlots(engine).size());
    }

    @Test
    public void automaticSettings_defaultsAndRoleParam() {
        FormationCatalog.capture(engine);
        Map<String, Object> snap = FormationCatalog.snapshot(engine);
        @SuppressWarnings("unchecked")
        Map<String, Object> auto = (Map<String, Object>) snap.get("automaticSettings");
        assertEquals(50, ((Number) auto.get("infantryRatio")).intValue());
        assertEquals(20, ((Number) auto.get("lancerRatio")).intValue());
        assertEquals(30, ((Number) auto.get("marksmanRatio")).intValue());

        // 角色参数（App 上传）优先
        assertNotNull(FormationCatalog.parseAutomaticSettings(
                "{\"automatic_settings\":{\"infantry_ratio\":40,\"lancer_ratio\":30,"
                        + "\"marksman_ratio\":30}}"));
        int[] s = FormationCatalog.parseAutomaticSettings(
                "{\"automatic_settings\":{\"infantry_ratio\":10,\"lancer_ratio\":10,"
                        + "\"marksman_ratio\":80}}");
        assertEquals(80, s[2]);
        // 和≠100 / 负值 / 非法 JSON → null（调用方用默认 50/20/30）
        assertNull(FormationCatalog.parseAutomaticSettings(
                "{\"automatic_settings\":{\"infantry_ratio\":10,\"lancer_ratio\":10,"
                        + "\"marksman_ratio\":10}}"));
        assertNull(FormationCatalog.parseAutomaticSettings("not-json"));
        assertNull(FormationCatalog.parseAutomaticSettings(null));

        FormationCatalog.setAutomaticSettings(engine, 60, 20, 20);
        FormationCatalog.capture(engine);
        @SuppressWarnings("unchecked")
        Map<String, Object> auto2 = (Map<String, Object>)
                FormationCatalog.snapshot(engine).get("automaticSettings");
        assertEquals(60, ((Number) auto2.get("infantryRatio")).intValue());
    }

    @Test
    public void memoryAndBearDefaults() {
        FormationCatalog.capture(engine);
        JSONObject memory = FormationCatalog.memoryStatus(engine);
        assertFalse(memory.getJSONObject("daily").getBoolean("exists"));
        assertEquals(0, memory.getJSONObject("battle").getInt("slotCount"));
        JSONObject bear = FormationCatalog.bearBinding(engine);
        assertFalse(bear.getBoolean("applied"));
        assertEquals("", bear.getString("templateId"));
        assertTrue(bear.getJSONArray("joinFormationIds").isEmpty());

        Map<String, Object> mem = new LinkedHashMap<>();
        Map<String, Object> daily = mapOf("exists", true, "savedAt", 123L,
                "slotCount", 6, "version", 2L);
        mem.put("daily", daily);
        mem.put("battle", mapOf("exists", false, "savedAt", 0L, "slotCount", 0, "version", 0L));
        FormationCatalog.setMemoryStatus(engine, mem);
        FormationCatalog.capture(engine);
        JSONObject memory2 = FormationCatalog.memoryStatus(engine);
        assertTrue(memory2.getJSONObject("daily").getBoolean("exists"));
        assertEquals(123L, memory2.getJSONObject("daily").getLong("savedAt"));
    }

    @Test
    public void stateKeys_newKeyHasUid_legacyKeyDoesNot() {
        assertEquals("srv:formation:catalog:" + FPID + ":" + SID + ":" + UID,
                FormationCatalog.stateKey(FPID, String.valueOf(SID), UID));
        assertEquals("srv:formation:catalog:" + FPID + ":" + SID,
                FormationCatalog.legacyStateKey(FPID, String.valueOf(SID)));
        assertTrue(FormationCatalog.stateKey(FPID, String.valueOf(SID), UID)
                .startsWith(FormationCatalog.STATE_KEY_PREFIX));
    }

    @Test
    public void emptyPush_doesNotOverwriteGoodCatalog_andIsNotCached() {
        FormationCatalog.capture(engine);
        assertEquals(4, FormationCatalog.heroIds(engine).size());

        // 推送全部消失（离线瞬间/重连中）：空快照不得覆盖好目录
        engine.notifies.clear();
        FormationCatalog.capture(engine);
        assertEquals("空快照不覆盖好目录（审计 P0-6）", 4, FormationCatalog.heroIds(engine).size());
        assertEquals(20000L, FormationCatalog.soldierStock(engine)[0]);

        // 空快照不缓存：新引擎首次 catalog() 为空，推送到达后再次 catalog() 立即非空
        TestEngines.FakeEngine fresh = new TestEngines.FakeEngine();
        FormationCatalog.reset(fresh);
        assertTrue(FormationCatalog.catalog(fresh).heroes.isEmpty());
        presetPushes(fresh);
        assertFalse("空目录不得被缓存（下次调用重试）",
                FormationCatalog.catalog(fresh).heroes.isEmpty());
    }

    @Test
    public void onNotify_refreshesWithoutWaitingNextCapture() {
        TestEngines.FakeEngine fresh = new TestEngines.FakeEngine();
        FormationCatalog.reset(fresh);
        fresh.notifies.put("notify_hero_init", mapOf(
                "50006.0", mapOf("id", 50006L, "lev", 6L, "star", 0L)));
        FormationCatalog.onNotify(fresh, "notify_hero_init", fresh.notifies.get("notify_hero_init"));
        assertEquals(1, FormationCatalog.heroIds(fresh).size());

        // 兵种增量（出征带走/回城归还）
        fresh.notifies.put("notify_soldier_init", mapOf("soldier", List.of(
                mapOf("id", 11000L, "count", 1000L),
                mapOf("id", 21000L, "count", 800L),
                mapOf("id", 31000L, "count", 600L))));
        FormationCatalog.onNotify(fresh, "notify_soldier_init",
                fresh.notifies.get("notify_soldier_init"));
        assertEquals(1000L, FormationCatalog.soldierStock(fresh)[0]);
        FormationCatalog.onNotify(fresh, "notify_soldier_update", mapOf(
                "type", "del", "data", List.of(mapOf("id", 11000L, "count", 400L))));
        assertEquals(600L, FormationCatalog.soldierStock(fresh)[0]);
        FormationCatalog.onNotify(fresh, "notify_soldier_update", mapOf(
                "type", "add", "data", List.of(mapOf("id", 11000L, "count", 100L))));
        assertEquals(700L, FormationCatalog.soldierStock(fresh)[0]);

        // 游戏编队增量
        fresh.notifies.put("notify_soldier_init", mapOf("prefab", List.of(
                mapOf("id", 1L, "prefab", mapOf(
                        "hero_id", List.of(pair(1, 50006)),
                        "soldier", List.of(pair(10300, 500L)))))));
        FormationCatalog.onNotify(fresh, "notify_soldier_init",
                fresh.notifies.get("notify_soldier_init"));
        assertEquals(1, FormationCatalog.gameSlots(fresh).size());
        FormationCatalog.onNotify(fresh, "notify_soldier_formation_update", mapOf(
                "id", 1L, "formation", mapOf(
                        "id", 1L,
                        "hero_id", List.of(pair(1, 50041), pair(2, 50042)),
                        "soldier", List.of(mapOf("id", 10300L, "count", 900L)))));
        assertEquals(List.of(50041L, 50042L), FormationCatalog.gameSlots(fresh).get(0).heroIds);
    }

    @Test
    public void busyHero_isReserved_notAvailable() {
        // 资产库未接线时 busy 恒空 → AVAILABLE（不阻塞）；这里直接验证 withAvailability 语义
        List<FormationCatalog.Hero> heroes = FormationCatalog.heroesOf(mapOf(
                "50006.0", mapOf("id", 50006L, "lev", 6L)));
        List<FormationCatalog.Hero> marked = FormationCatalog.withAvailability(heroes,
                java.util.Set.of(50006L));
        assertEquals("RESERVED", marked.get(0).availabilityState);
        assertEquals("前序占用", marked.get(0).availabilityReason);
        assertFalse(marked.get(0).available);
        assertEquals("兵力容量不丢", 500L, marked.get(0).troopCapacity);
    }

    private static Map<String, Object> findHero(List<Map<String, Object>> heroes, long id) {
        for (Map<String, Object> h : heroes) {
            if (((Number) h.get("id")).longValue() == id) return h;
        }
        throw new AssertionError("hero not found: " + id);
    }
}
