package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 编队目录采集单测（批次10）：{@code notify_hero_init/notify_soldier_init/notify_world_player_init}
 * → 英雄目录/三兵种库存/出征容量 → 冻结 state 快照形状。
 */
public class FormationCatalogTest {

    /** 最小引擎替身：只回放 notifyData（不连网）。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();

        TestEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) v;
                    return m;
                }
            }
            return c;
        }
    }

    private TestEngine engine;

    @Before
    public void setUp() {
        engine = new TestEngine();
        FormationCatalog.reset(engine);
    }

    /** notify_hero_init 实证形状：{"50022.0":{hero},...}。 */
    private void presetHeroes() {
        Map<String, Object> hi = new LinkedHashMap<>();
        hi.put("50022.0", hero(50022L, "弱鸡", 70, 6, 1000));
        hi.put("50041.0", hero(50041L, "主力", 80, 8, 9000));
        hi.put("50039.0", hero(50039L, "副手", 75, 7, 5000));
        engine.notifies.put("notify_hero_init", hi);
    }

    private static Map<String, Object> hero(long id, String name, long lv, long star, long power) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("name", name);
        h.put("lv", lv);
        h.put("star", star);
        h.put("power", power);
        return h;
    }

    /** notify_soldier_init 实证形状：{soldier:{"11000.0":{id,count},...}}。 */
    private void presetSoldiers() {
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", Map.of("id", 11000L, "count", 20000L));
        soldier.put("21000.0", Map.of("id", 21000L, "count", 1549L));
        soldier.put("31000.0", Map.of("id", 31000L, "count", 28451L));
        // 低档位：不应计入最高档库存
        soldier.put("10900.0", Map.of("id", 10900L, "count", 999L));
        Map<String, Object> si = new LinkedHashMap<>();
        si.put("soldier", soldier);
        engine.notifies.put("notify_soldier_init", si);
    }

    private void presetWorldCapacity() {
        Map<String, Object> warp = new LinkedHashMap<>();
        warp.put("world", Map.of("capacity", 5L));
        engine.notifies.put("notify_world_player_init", warp);
    }

    @Test
    public void capture_heroesSortedByPower_andFields() {
        presetHeroes();
        FormationCatalog.capture(engine);
        List<FormationCatalog.Hero> heroes = FormationCatalog.heroes(engine);
        assertEquals(3, heroes.size());
        assertEquals("战力降序", 50041L, heroes.get(0).id);
        assertEquals(50039L, heroes.get(1).id);
        assertEquals(50022L, heroes.get(2).id);
        assertEquals("主力", heroes.get(0).name);
        assertEquals(80L, heroes.get(0).level);
        assertEquals(8L, heroes.get(0).star);
        assertEquals(9000L, heroes.get(0).power);
        assertEquals(List.of(50041L, 50039L, 50022L), FormationCatalog.heroIds(engine));
    }

    @Test
    public void capture_soldierStock_usesBestTierOnly() {
        presetSoldiers();
        FormationCatalog.capture(engine);
        long[] stock = FormationCatalog.soldierStock(engine);
        assertEquals("盾=最高档 11000 的数量", 20000L, stock[0]);
        assertEquals(1549L, stock[1]);
        assertEquals(28451L, stock[2]);
        long[] ids = FormationCatalog.bestSoldierIds(engine);
        assertEquals(11000L, ids[0]);
        assertEquals(21000L, ids[1]);
        assertEquals(31000L, ids[2]);
        assertFalse("低档位不计入（同 Exec.baseSoldiers 口径）", stock[0] == 20000L + 999L);
    }

    @Test
    public void capture_capacityAndSnapshotShape() {
        presetHeroes();
        presetSoldiers();
        presetWorldCapacity();
        FormationCatalog.capture(engine);

        assertEquals(5, FormationCatalog.capacity(engine));
        Map<String, Object> snap = FormationCatalog.snapshot(engine);
        assertNotNull(snap);
        assertEquals(5, ((Number) snap.get("capacity")).intValue());
        assertTrue("冻结键型需含 capturedAt", snap.containsKey("capturedAt"));
        List<?> heroes = (List<?>) snap.get("heroes");
        assertEquals(3, heroes.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) heroes.get(0);
        assertEquals(50041L, ((Number) first.get("id")).longValue());
        assertTrue(first.containsKey("name"));
        assertTrue(first.containsKey("level"));
        assertTrue(first.containsKey("power"));
        @SuppressWarnings("unchecked")
        Map<String, Object> soldiers = (Map<String, Object>) snap.get("soldiers");
        assertEquals(20000L, ((Number) soldiers.get("infantry")).longValue());
        assertEquals(1549L, ((Number) soldiers.get("lancer")).longValue());
        assertEquals(28451L, ((Number) soldiers.get("marksman")).longValue());
        assertNotNull(soldiers.get("best_ids"));
        assertEquals("srv:formation:catalog:", FormationCatalog.STATE_KEY_PREFIX);
    }

    @Test
    public void capture_missingPushes_emptyCatalogNoCrash() {
        FormationCatalog.capture(engine);
        assertTrue(FormationCatalog.heroIds(engine).isEmpty());
        assertEquals(0L, FormationCatalog.soldierStock(engine)[0]);
        assertEquals(0, FormationCatalog.capacity(engine));
        assertNotNull(FormationCatalog.snapshot(engine));
    }

    @Test
    public void capture_isIdempotent_andResetClearsCache() {
        presetHeroes();
        FormationCatalog.capture(engine);
        FormationCatalog.capture(engine);
        assertEquals(3, FormationCatalog.heroIds(engine).size());
        FormationCatalog.reset(engine);
        FormationCatalog.reset(engine);
        assertEquals("reset 后重新采集", 3, FormationCatalog.heroIds(engine).size());
    }

    @Test
    public void heroesOf_parsesListShapeAndAliases() {
        Map<String, Object> hi = new LinkedHashMap<>();
        hi.put("data", List.of(
                Map.of("id", 1L, "nickName", "A", "level", 10L, "star", 2L, "fight_num", 111L),
                Map.of("hero_id", 2L, "name", "B", "lv", 20L)));
        List<FormationCatalog.Hero> heroes = FormationCatalog.heroesOf(hi);
        assertEquals(2, heroes.size());
        // 强度排序：power(111) > 0 → A 在前；字段别名 fight_num/nickName 生效
        FormationCatalog.Hero a = heroes.get(0);
        assertEquals(1L, a.id);
        assertEquals("A", a.name);
        assertEquals(111L, a.power);
        FormationCatalog.Hero b = heroes.get(1);
        assertEquals(2L, b.id);
        assertEquals("B", b.name);
        assertEquals("lv 别名生效", 20L, b.level);
        assertTrue("null 输入返回空目录", FormationCatalog.heroesOf(null).isEmpty());
        assertTrue(FormationCatalog.heroesOf(new LinkedHashMap<>()).isEmpty());
    }

    @Test
    public void heroInit_levAliasParsed() {
        // 生产真实帧：notify_hero_init.data["50022.0"] = {id,lev,star,...}（无 name/power）
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("50022.0", Map.of("id", 50022L, "lev", 45L, "star", 18L));
        Map<String, Object> init = Map.of("data", data);
        List<FormationCatalog.Hero> heroes = FormationCatalog.heroesOf(init);
        assertEquals(1, heroes.size());
        assertEquals(45L, heroes.get(0).level);
        assertEquals(18L, heroes.get(0).star);
    }

    // ==================== 契约 2026-11-02 §1：专武 elv / 第一个远征技能 skills ====================

    @Test
    public void heroInit_weaponLevelFromElv_andExpeditionSkillFromSkills() {
        // skills 列表形状：[{id:heroId*10+槽位, lv:...}]；槽 1 = 第一个远征技能（槽 0=基础技能）
        Map<String, Object> init = mapOf("data", mapOf("50011.0", mapOf(
                "id", 50011L, "lev", 80L, "star", 30L, "elv", 10L,
                "skills", List.of(mapOf("id", 500110L, "lv", 1L),
                        mapOf("id", 500111L, "lv", 5L),
                        mapOf("id", 500112L, "lv", 3L)))));
        FormationCatalog.Hero h = FormationCatalog.heroesOf(init).get(0);
        assertEquals("elv → weaponLevel", 10, h.weaponLevel);
        assertEquals("槽 0 是基础技能，不取；槽 1 → 5 级", 5, h.expeditionSkillLevel);
        Map<String, Object> m = h.toMap();
        assertEquals(10, ((Number) m.get("weaponLevel")).intValue());
        assertEquals(5, ((Number) m.get("expeditionSkillLevel")).intValue());

        // skills Map 形状：key=skill id（带 .0），值内无 id（槽 1）
        Map<String, Object> mapInit = mapOf("data", mapOf("50012.0", mapOf(
                "id", 50012L, "skills", mapOf("500120.0", mapOf("lv", 1L),
                        "500121.0", mapOf("lv", 4L)))));
        FormationCatalog.Hero mapped = FormationCatalog.heroesOf(mapInit).get(0);
        assertEquals(4, mapped.expeditionSkillLevel);
        assertEquals("缺 elv → 0", 0, mapped.weaponLevel);

        // 老帧只有槽 0 → 回退槽 0
        Map<String, Object> slot0Only = mapOf("data", mapOf("50014.0", mapOf(
                "id", 50014L, "skills", mapOf("500140.0", mapOf("lv", 2L)))));
        assertEquals(2, FormationCatalog.heroesOf(slot0Only).get(0).expeditionSkillLevel);

        // 越界夹取：elv 15 → 10，技能 9 → 5
        Map<String, Object> clampInit = mapOf("data", mapOf("50013.0", mapOf(
                "id", 50013L, "elv", 15L,
                "skills", mapOf("500131.0", mapOf("id", 500131L, "lv", 9L)))));
        FormationCatalog.Hero clamped = FormationCatalog.heroesOf(clampInit).get(0);
        assertEquals(10, clamped.weaponLevel);
        assertEquals(5, clamped.expeditionSkillLevel);
    }

    @Test
    public void heroInit_legacyFrameWithoutSkillFields_defaultsZero() {
        // 旧帧/旧服务端缺 elv/skills → 0，不抛异常
        Map<String, Object> init = mapOf("data", mapOf("50019.0",
                mapOf("id", 50019L, "lev", 56L, "star", 5L)));
        FormationCatalog.Hero h = FormationCatalog.heroesOf(init).get(0);
        assertEquals(0, h.weaponLevel);
        assertEquals(0, h.expeditionSkillLevel);
        // 直接构造（旧 6 参构造签名保留）也默认 0
        FormationCatalog.Hero legacy = new FormationCatalog.Hero(
                50022L, "无名", 1L, 0L, 0L, "");
        assertEquals(0, legacy.weaponLevel);
        assertEquals(0, legacy.expeditionSkillLevel);
        assertEquals(0, ((Number) legacy.toMap().get("weaponLevel")).intValue());
        assertEquals(0, ((Number) legacy.toMap().get("expeditionSkillLevel")).intValue());
        // 缺字段/空 Map 的静态解析入口不抛异常
        assertEquals(0, FormationCatalog.weaponLevelOf(null));
        assertEquals(0, FormationCatalog.expeditionSkillLevelOf(null, 50011L));
        assertEquals(0, FormationCatalog.expeditionSkillLevelOf(new LinkedHashMap<>(), 50011L));
    }

    @Test
    public void soldierUpdate_incrementallyAdjustsStock() {
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("soldier", mapOf(
                "11100.0", mapOf("id", 11100L, "count", 1000L),
                "21100.0", mapOf("id", 21100L, "count", 800L),
                "31100.0", mapOf("id", 31100L, "count", 600L)));
        engine.notifies.put("notify_soldier_init", soldier);
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
        assertEquals(1000L, FormationCatalog.soldierStock(engine)[0]);

        // 出征带走 400 盾兵（notify_soldier_update type=del）
        FormationCatalog.onNotify(engine, "notify_soldier_update", mapOf(
                "type", "del",
                "data", List.of(mapOf("id", 11100L, "count", 400L))));
        assertEquals("del 后盾兵库存递减", 600L, FormationCatalog.soldierStock(engine)[0]);

        // 回城归还 100（type=add）
        FormationCatalog.onNotify(engine, "notify_soldier_update", mapOf(
                "type", "add",
                "data", List.of(mapOf("id", 11100L, "count", 100L))));
        assertEquals("add 后盾兵库存递增", 700L, FormationCatalog.soldierStock(engine)[0]);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
