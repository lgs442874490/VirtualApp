package com.carlos.vaultserver.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PresetLiveResolver} 单测（task-7）：19 套预设出征时按当前英雄目录实时重算英雄。
 *
 * <p>规则与 App {@code PresetFormations} 逐条一致：最强职业回退、波根/吉娜/米亚/布拉德利优先级、
 * 别名、采集 10 万阈值与跨队列不重复、打熊队盾优先级、熊上车技能排序/冲突跳过；
 * 以及 {@link FormationResolver} 接入后的 PERCENTAGE 实时比例与巨兽 MANUAL 1/0/0。
 *
 * <p>不连真协议：复用 {@link TestEngines.FakeEngine} 回放 notify 推送。
 */
public class PresetLiveResolverTest {

    private TestEngines.FakeEngine engine;

    @Before
    public void setUp() {
        engine = new TestEngines.FakeEngine();
        FormationCatalog.reset(engine);
    }

    // ==================== 夹具 ====================

    /** 英雄推送行（level/star 走生产别名；star=展示星级×6；skills[0]=heroId*10）。 */
    private static Map<String, Object> hero(long id, String name, int starDisplay, long power,
                                            int skill, int weapon) {
        return TestEngines.FakeEngine.mapOf(
                "id", id,
                "name", name,
                "lev", 80L,
                "star", (long) starDisplay * 6L,
                "power", power,
                "elv", weapon,
                "skills", List.of(TestEngines.FakeEngine.mapOf("id", id * 10L, "lv", skill)));
    }

    /** 写入英雄目录并采集（其余推送保留）。 */
    private void presetHeroes(Map<String, Object>... heroes) {
        Map<String, Object> init = new LinkedHashMap<>();
        int i = 0;
        for (Map<String, Object> h : heroes) {
            init.put((i++) + ".0", h);
        }
        engine.notifies.put("notify_hero_init", init);
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
    }

    /** 兵种库存（足够大，避免库存缩放干扰兵量断言）。 */
    private void presetSoldiers(long infantry, long lancer, long marksman) {
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", TestEngines.FakeEngine.mapOf("id", 11000L, "count", infantry));
        soldier.put("21000.0", TestEngines.FakeEngine.mapOf("id", 21000L, "count", lancer));
        soldier.put("31000.0", TestEngines.FakeEngine.mapOf("id", 31000L, "count", marksman));
        engine.notifies.put("notify_soldier_init",
                TestEngines.FakeEngine.mapOf("soldier", soldier));
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
    }

    /** 指挥中心满级（表末档 102000 + 基础 500 = 102500，>10 万，采集只带队长）。 */
    private void presetCommandCenterMax() {
        engine.notifies.put("notify_shelter_info", TestEngines.FakeEngine.mapOf(
                "building_info", TestEngines.FakeEngine.mapOf(
                        "331", TestEngines.FakeEngine.mapOf(
                                "base", TestEngines.FakeEngine.mapOf("lv", 999L)))));
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
    }

    /** 构造第 index 套（1..19）预设 spec（库内英雄故意写 stale，refresh 必须替换）。 */
    private static FormationSpec preset(int index) {
        FormationSpec s = new FormationSpec();
        s.id = PresetLiveResolver.ID_FIRST + index - 1L;
        s.name = "预设" + index;
        s.templateKey = templateKeyOf(index);
        s.heroIds = new ArrayList<Long>(Arrays.asList(1L, 2L, 3L));
        s.captainHeroId = 1L;
        s.ratioExplicit = true;
        s.ratio = new LinkedHashMap<String, Long>();
        s.ratio.put("infantry", 50L);
        s.ratio.put("lancer", 20L);
        s.ratio.put("marksman", 30L);
        s.total = 3000L;
        if (index == 1) {
            s.allocationMode = FormationSpec.MODE_MANUAL;
            s.infantry = 1L;
            s.lancer = 0L;
            s.marksman = 0L;
        } else {
            s.allocationMode = FormationSpec.MODE_PERCENTAGE;
            s.infantry = 1000L;
            s.lancer = 500L;
            s.marksman = 1500L;
        }
        return s;
    }

    private static String templateKeyOf(int index) {
        switch (index) {
            case 1: return PresetLiveResolver.TPL_BEHEMOTH;
            case 2: return PresetLiveResolver.TPL_HUNT;
            case 3: return PresetLiveResolver.TPL_BEAR_TEAM;
            case 4: return PresetLiveResolver.TPL_SHIELD_BOW;
            case 5: return PresetLiveResolver.TPL_SHIELD_SPEAR;
            case 6: return "PRESET_GATHER_MEAT";
            case 7: return "PRESET_GATHER_WOOD";
            case 8: return "PRESET_GATHER_COAL";
            case 9: return "PRESET_GATHER_IRON";
            default: return "PRESET_BEAR_RIDE_" + (index - 9);
        }
    }

    /** 全部具名英雄 + 10 个熊上车队长 + 补位（用于打熊/熊上车/采集综合场景）。 */
    private static Map<String, Object>[] richCatalog() {
        List<Map<String, Object>> list = new ArrayList<>();
        // 打熊队盾优先级候选（尼莫非满专武、赫克托 4 星）
        list.add(hero(50011L, "赫罗尼莫", 3, 7500, 4, 0));
        list.add(hero(50024L, "赫克托", 4, 8000, 2, 4));
        list.add(hero(50009L, "娜塔莉亚", 3, 6000, 3, 0));
        // 具名矛/弓
        list.add(hero(50029L, "卢姆·波根", 4, 9000, 2, 3));
        list.add(hero(50016L, "米娅", 4, 8500, 3, 2));
        list.add(hero(50012L, "茉莉", 3, 8000, 1, 0));
        list.add(hero(50008L, "吉娜", 5, 9500, 5, 10));
        list.add(hero(50033L, "布拉德利", 4, 8600, 5, 2));
        list.add(hero(50020L, "格雷格", 3, 8200, 1, 0));
        // 采集队长
        list.add(hero(50003L, "克劳瑞斯", 3, 5000, 0, 0));
        list.add(hero(50000L, "尤金", 3, 5000, 0, 0));
        list.add(hero(50002L, "查理", 3, 5000, 0, 0));
        list.add(hero(50001L, "史密斯", 3, 5000, 0, 0));
        // 其余熊上车队长
        list.add(hero(50036L, "亨德里克", 4, 4000, 1, 0));
        list.add(hero(50026L, "格温", 4, 4000, 5, 1));
        list.add(hero(50027L, "书允", 4, 4000, 4, 0));
        list.add(hero(50038L, "马格努斯", 4, 4000, 3, 0));
        list.add(hero(50043L, "布兰琪", 4, 4000, 0, 0));
        list.add(hero(50007L, "杰西", 4, 4000, 2, 0));
        list.add(hero(50028L, "杰塞尔", 4, 4000, 5, 0));
        list.add(hero(50035L, "索尼娅", 4, 4000, 1, 0));
        // 补位英雄（战力 3000~2000，三职业）
        list.add(hero(50004L, "谢尔盖", 3, 3000, 0, 0));
        list.add(hero(50005L, "帕特里克", 3, 2900, 0, 0));
        list.add(hero(50006L, "巴希提", 3, 2800, 0, 0));
        list.add(hero(50010L, "罗根", 3, 2700, 0, 0));
        list.add(hero(50013L, "菲兰德", 3, 2600, 0, 0));
        list.add(hero(50017L, "津曼", 3, 2500, 0, 0));
        list.add(hero(50019L, "无名", 3, 2400, 0, 0));
        list.add(hero(50021L, "弗林特", 3, 2300, 0, 0));
        list.add(hero(50022L, "琳恩", 3, 2200, 0, 0));
        list.add(hero(50025L, "诺拉", 3, 2100, 0, 0));
        list.add(hero(50030L, "芮妮", 3, 2000, 0, 0));
        list.add(hero(50031L, "韦恩", 3, 1900, 0, 0));
        list.add(hero(50032L, "艾迪丝", 3, 1800, 0, 0));
        list.add(hero(50034L, "加托", 3, 1700, 0, 0));
        list.add(hero(50037L, "凌霜", 3, 1600, 0, 0));
        list.add(hero(50039L, "弗雷德", 3, 1500, 0, 0));
        list.add(hero(50040L, "修拉", 3, 1400, 0, 0));
        list.add(hero(50041L, "格里高利", 3, 1300, 0, 0));
        list.add(hero(50042L, "芙蕾雅", 3, 1200, 0, 0));
        list.add(hero(50044L, "埃莱奥诺拉", 3, 1100, 0, 0));
        list.add(hero(50045L, "劳埃德", 3, 1000, 0, 0));
        @SuppressWarnings("unchecked")
        Map<String, Object>[] out = list.toArray(new Map[0]);
        return out;
    }

    // ==================== 1. isPreset / 名称解析 ====================

    @Test
    public void isPreset_byIdRangeOrTemplateKey() {
        FormationSpec byId = new FormationSpec();
        byId.id = PresetLiveResolver.ID_FIRST;
        assertTrue(PresetLiveResolver.isPreset(byId));
        FormationSpec byLast = new FormationSpec();
        byLast.id = PresetLiveResolver.ID_LAST;
        assertTrue(PresetLiveResolver.isPreset(byLast));
        FormationSpec byKey = new FormationSpec();
        byKey.id = 42L;
        byKey.templateKey = "PRESET_HUNT";
        assertTrue(PresetLiveResolver.isPreset(byKey));
        FormationSpec normal = new FormationSpec();
        normal.id = 42L;
        normal.templateKey = "ONE_INFANTRY";
        assertFalse(PresetLiveResolver.isPreset(normal));
        assertFalse(PresetLiveResolver.isPreset(null));
        FormationSpec outOfRange = new FormationSpec();
        outOfRange.id = PresetLiveResolver.ID_LAST + 1L;
        assertFalse(PresetLiveResolver.isPreset(outOfRange));
    }

    @Test
    public void heroNameAliases_matchApp() {
        assertEquals("卢姆·波根", PresetLiveResolver.canonicalHeroName("波根"));
        assertEquals("米娅", PresetLiveResolver.canonicalHeroName("米亚"));
        assertEquals("杰塞尔", PresetLiveResolver.canonicalHeroName("杰赛尔"));
        assertEquals("赫罗尼莫", PresetLiveResolver.canonicalHeroName("尼莫"));
        assertEquals("吉娜", PresetLiveResolver.canonicalHeroName("吉娜"));
        assertEquals("", PresetLiveResolver.canonicalHeroName(null));
    }

    // ==================== 2. 巨兽/狩猎：实时最强盾 + 波根/吉娜 ====================

    @Test
    public void behemoth_liveStrongestShield_followsCatalogChanges() {
        presetHeroes(
                hero(50004L, "盾A", 3, 100, 0, 0),
                hero(50010L, "盾B", 3, 900, 0, 0),
                hero(50005L, "矛A", 3, 500, 0, 0),
                hero(50006L, "弓A", 3, 400, 0, 0));
        FormationSpec refreshed = PresetLiveResolver.refresh(preset(1), engine);
        assertEquals("高战力盾 50010", Arrays.asList(50010L, 50005L, 50006L),
                refreshed.heroIds);
        assertEquals("队长=首位", Long.valueOf(50010L), refreshed.captainHeroId);
        assertEquals("巨兽保持 MANUAL 1/0/0", FormationSpec.MODE_MANUAL,
                refreshed.allocationMode);
        assertEquals(1L, refreshed.infantry);
        assertEquals(0L, refreshed.lancer);
        assertEquals(0L, refreshed.marksman);
        assertEquals("id/name/template 保留", PresetLiveResolver.ID_FIRST, refreshed.id);
        assertEquals("PRESET_BEHEMOTH", refreshed.templateKey);

        // 英雄成长后再次 refresh：低战力盾变最高 → 立刻换人（实时性）
        presetHeroes(
                hero(50004L, "盾A", 3, 99999, 0, 0),
                hero(50010L, "盾B", 3, 900, 0, 0),
                hero(50005L, "矛A", 3, 500, 0, 0),
                hero(50006L, "弓A", 3, 400, 0, 0));
        FormationSpec again = PresetLiveResolver.refresh(preset(1), engine);
        assertEquals("成长后的盾 50004", Long.valueOf(50004L), again.captainHeroId);
        assertEquals(Long.valueOf(50004L), again.heroIds.get(0));
    }

    @Test
    public void behemoth_borganGinaPriority_thenStrongestFallback() {
        presetHeroes(
                hero(50004L, "盾A", 3, 5000, 0, 0),
                hero(50029L, "卢姆·波根", 3, 100, 0, 0),
                hero(50005L, "矛B", 3, 9000, 0, 0),
                hero(50008L, "吉娜", 3, 100, 0, 0),
                hero(50006L, "弓B", 3, 9000, 0, 0));
        FormationSpec refreshed = PresetLiveResolver.refresh(preset(2), engine);
        assertEquals("波根/吉娜优先于更强职业",
                Arrays.asList(50004L, 50029L, 50008L), refreshed.heroIds);

        // 缺波根/吉娜 → 最强矛/弓
        presetHeroes(
                hero(50004L, "盾A", 3, 5000, 0, 0),
                hero(50005L, "矛B", 3, 9000, 0, 0),
                hero(50006L, "弓B", 3, 9000, 0, 0));
        FormationSpec fallback = PresetLiveResolver.refresh(preset(2), engine);
        assertEquals(Arrays.asList(50004L, 50005L, 50006L), fallback.heroIds);
    }

    // ==================== 3. 盾弓米亚 / 盾矛布拉德利 ====================

    @Test
    public void shieldBow_miaPriority_andShieldSpear_bradleyPriority() {
        presetHeroes(
                hero(50004L, "盾A", 3, 5000, 0, 0),
                hero(50016L, "米娅", 3, 100, 0, 0),
                hero(50005L, "矛B", 3, 9000, 0, 0),
                hero(50012L, "茉莉", 3, 8000, 0, 0),
                hero(50033L, "布拉德利", 3, 100, 0, 0),
                hero(50020L, "格雷格", 3, 8000, 0, 0),
                hero(50006L, "弓B", 3, 9000, 0, 0));
        FormationSpec shieldBow = PresetLiveResolver.refresh(preset(4), engine);
        assertEquals("盾弓：米亚优先于更强矛", Arrays.asList(50004L, 50016L, 50006L),
                shieldBow.heroIds);

        FormationSpec shieldSpear = PresetLiveResolver.refresh(preset(5), engine);
        assertEquals("盾矛：布拉德利优先于更强弓", Arrays.asList(50004L, 50005L, 50033L),
                shieldSpear.heroIds);
    }

    // ==================== 4. 采集：队长固定 / 10 万阈值 / 跨队列不重复 ====================

    @Test
    public void gather_fixedCaptains_threeHeroes_noDuplicateAcrossQueues() {
        // 无容量推送 → capacityForHeroes=0（不触发 10 万阈值）→ 补到 3 人
        presetHeroes(richCatalog());
        long[] captains = {PresetLiveResolver.HERO_GATHER_MEAT, PresetLiveResolver.HERO_GATHER_WOOD,
                PresetLiveResolver.HERO_GATHER_COAL, PresetLiveResolver.HERO_GATHER_IRON};
        List<List<Long>> teams = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            FormationSpec f = PresetLiveResolver.refresh(preset(6 + i), engine);
            assertEquals("第 " + (6 + i) + " 队 3 人", 3, f.heroIds.size());
            assertEquals("队长固定", Long.valueOf(captains[i]), f.heroIds.get(0));
            assertEquals(Long.valueOf(captains[i]), f.captainHeroId);
            // 同队职业不重复
            java.util.Set<Integer> profs = new java.util.HashSet<>();
            for (Long id : f.heroIds) {
                int p = professionOf(id.longValue());
                if (p > 0) assertTrue("同队职业不重复", profs.add(Integer.valueOf(p)));
            }
            teams.add(f.heroIds);
        }
        // 四队之间英雄不重复
        java.util.Set<Long> union = new java.util.LinkedHashSet<>();
        for (List<Long> team : teams) union.addAll(team);
        assertEquals("采集队列之间英雄不得重复", 12, union.size());
    }

    @Test
    public void gather_capacityAbove100k_onlyCaptain() {
        presetHeroes(richCatalog());
        presetSoldiers(200000L, 100000L, 100000L);
        presetCommandCenterMax();
        assertTrue("模拟容量 >10 万",
                FormationCatalog.capacityForHeroes(engine,
                        List.of(PresetLiveResolver.HERO_GATHER_MEAT)) > 100000L);
        long[] captains = {PresetLiveResolver.HERO_GATHER_MEAT, PresetLiveResolver.HERO_GATHER_WOOD,
                PresetLiveResolver.HERO_GATHER_COAL, PresetLiveResolver.HERO_GATHER_IRON};
        for (int i = 0; i < 4; i++) {
            FormationSpec f = PresetLiveResolver.refresh(preset(6 + i), engine);
            assertEquals("容量 >10 万 → 只带队长", 1, f.heroIds.size());
            assertEquals(Long.valueOf(captains[i]), f.heroIds.get(0));
        }
    }

    // ==================== 5. 打熊队盾优先级 / 熊上车冲突与技能排序 ====================

    @Test
    public void bearTeam_shieldPriority_fullNemo_thenBearRideConflictSkipped() {
        // 满星满专武尼莫 → 打熊队盾=尼莫；尼莫也是熊上车9 队长 → 该队冲突跳过
        List<Map<String, Object>> list = new ArrayList<>(Arrays.asList(richCatalog()));
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> h = list.get(i);
            if (((Number) h.get("id")).longValue() == 50011L) {
                list.set(i, hero(50011L, "赫罗尼莫", 5, 7000, 4, 10));
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object>[] heroes = list.toArray(new Map[0]);
        presetHeroes(heroes);
        FormationSpec bearTeam = PresetLiveResolver.refresh(preset(3), engine);
        assertEquals("①满星满专武尼莫", Long.valueOf(50011L), bearTeam.heroIds.get(0));
        assertEquals("矛=米亚", Long.valueOf(50016L), bearTeam.heroIds.get(1));
        assertEquals("弓避开熊上车队长=吉娜", Long.valueOf(50008L), bearTeam.heroIds.get(2));

        FormationSpec bearRide9 = PresetLiveResolver.refresh(preset(18), engine);
        assertTrue("与打熊队冲突的熊上车9 无英雄（跳过）", bearRide9.heroIds.isEmpty());
        assertNull(bearRide9.captainHeroId);
    }

    @Test
    public void bearRide_skillSorting_priorityTeamsGetStrongerFillFirst() {
        presetHeroes(richCatalog());
        // 打熊队盾=4 星赫克托（非熊上车队长），避免占用队长
        FormationSpec bearTeam = PresetLiveResolver.refresh(preset(3), engine);
        assertEquals(Long.valueOf(50024L), bearTeam.heroIds.get(0));

        // 熊上车2（格温，技能 5）排序最前 → 优先拿到最高战力补位（50029 波根 / 50009 盾）
        FormationSpec ride2 = PresetLiveResolver.refresh(preset(11), engine);
        assertEquals(Long.valueOf(50026L), ride2.heroIds.get(0));
        assertEquals("技能满级队优先补位：50029", Long.valueOf(50029L), ride2.heroIds.get(1));

        // 熊上车4（布拉德利，技能 5，表序第 2）→ 下一批补位（50012 茉莉）
        FormationSpec ride4 = PresetLiveResolver.refresh(preset(13), engine);
        assertEquals(Long.valueOf(50033L), ride4.heroIds.get(0));
        assertEquals("前序队占用后取次强补位", Long.valueOf(50012L), ride4.heroIds.get(1));

        // 熊上车1（亨德里克，技能 1）排序靠后：队长仍在，但补位是剩余英雄
        FormationSpec ride1 = PresetLiveResolver.refresh(preset(10), engine);
        assertEquals(Long.valueOf(50036L), ride1.heroIds.get(0));
        assertTrue("低技能队仍有队长", ride1.heroIds.size() >= 1);
    }

    // ==================== 6. 降级：非预设 / engine 空 / 目录空 ====================

    @Test
    public void nonPreset_engineNull_emptyCatalog_returnOriginalSpec() {
        FormationSpec normal = new FormationSpec();
        normal.id = 42L;
        normal.name = "普通编队";
        normal.heroIds = new ArrayList<Long>(Arrays.asList(50041L, 50039L));
        assertSame("非预设原样返回", normal, PresetLiveResolver.refresh(normal, engine));

        FormationSpec preset = preset(1);
        assertSame("engine 空原样返回", preset, PresetLiveResolver.refresh(preset, null));

        // 目录空（无 hero 推送）→ 原样返回
        engine.notifies.clear();
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
        assertTrue(FormationCatalog.heroes(engine).isEmpty());
        assertSame("目录无英雄原样返回", preset, PresetLiveResolver.refresh(preset, engine));

        // 未知 PRESET 键（id 不在区间、也无匹配模板）→ 原样返回
        FormationSpec unknown = new FormationSpec();
        unknown.id = 42L;
        unknown.templateKey = "PRESET_UNKNOWN";
        assertSame(unknown, PresetLiveResolver.refresh(unknown, engine));
        assertNull(PresetLiveResolver.refresh(null, engine));
    }

    // ==================== 7. FormationResolver 接入 ====================

    @Test
    public void resolver_rolePreset_recomputesHeroes_andPercentageByLiveCapacity() {
        presetHeroes(
                hero(50004L, "盾A", 3, 100, 0, 0),
                hero(50010L, "盾B", 3, 900, 0, 0),
                hero(50016L, "米娅", 3, 500, 0, 0),
                hero(50005L, "矛B", 3, 9000, 0, 0),
                hero(50008L, "吉娜", 3, 600, 0, 0),
                hero(50006L, "弓B", 3, 9000, 0, 0));
        presetSoldiers(200000L, 100000L, 100000L);
        presetCommandCenterMax();

        // 库里是旧英雄 50004/50005/50009 + 旧预算兵量；服务端应实时换人并按比例×容量重算
        String libJson = "{\"formations\":[{"
                + "\"id\":9100000004,\"name\":\"盾弓\",\"template_key\":\"PRESET_SHIELD_BOW\","
                + "\"allocation_mode\":\"PERCENTAGE\",\"hero_ids\":[50004,50005,50009],"
                + "\"captain_hero_id\":50004,"
                + "\"soldiers\":{\"infantry\":999,\"lancer\":999,\"marksman\":999},"
                + "\"ratio\":{\"infantry\":49,\"lancer\":2,\"marksman\":49},"
                + "\"total\":3000}]}";
        FormationLibrary lib = FormationLibrary.parse(libJson);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 9100000004L);
        FormationResolver.ResolveOutcome out =
                FormationResolver.resolveOutcome(engine, lib, p);
        assertTrue("预设应解析成功：" + out.reason, out.ready());
        assertEquals("实时英雄（最强盾/米亚/最强弓）",
                Arrays.asList(50010L, 50016L, 50006L), out.formation.heroIds);
        long capacity = FormationCatalog.capacityForHeroes(engine, out.formation.heroIds);
        assertTrue("容量已同步", capacity > 0L);
        assertEquals("PERCENTAGE 预设按实时容量重算（忽略库内 999 预算）",
                capacity, out.formation.infantry + out.formation.lancer
                        + out.formation.marksman);
        assertTrue("按 49/2/49 比例（矛占比最小）",
                out.formation.lancer < out.formation.infantry
                        && out.formation.lancer < out.formation.marksman);
        assertEquals("巨兽以外的预设不会被改成 MANUAL", FormationSpec.MODE_PERCENTAGE,
                out.formation.allocationMode);
    }

    @Test
    public void resolver_behemothPreset_keepsManualOneSoldier_withLiveHeroes() {
        presetHeroes(
                hero(50004L, "盾A", 3, 100, 0, 0),
                hero(50010L, "盾B", 3, 900, 0, 0),
                hero(50005L, "矛A", 3, 500, 0, 0),
                hero(50006L, "弓A", 3, 400, 0, 0));
        presetSoldiers(200000L, 100000L, 100000L);
        presetCommandCenterMax();

        String libJson = "{\"formations\":[{"
                + "\"id\":9100000001,\"name\":\"巨兽\",\"template_key\":\"PRESET_BEHEMOTH\","
                + "\"allocation_mode\":\"MANUAL\",\"hero_ids\":[50004,50005,50006],"
                + "\"captain_hero_id\":50004,"
                + "\"soldiers\":{\"infantry\":1,\"lancer\":0,\"marksman\":0},"
                + "\"ratio\":{\"infantry\":0,\"lancer\":0,\"marksman\":0},\"total\":1}]}";
        FormationLibrary lib = FormationLibrary.parse(libJson);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 9100000001L);
        FormationResolver.ResolveOutcome out =
                FormationResolver.resolveOutcome(engine, lib, p);
        assertTrue("巨兽预设应解析成功：" + out.reason, out.ready());
        assertEquals("实时最强盾", Long.valueOf(50010L), out.formation.heroIds.get(0));
        assertEquals("MANUAL 1/0/0 保持", 1L, out.formation.infantry);
        assertEquals(0L, out.formation.lancer);
        assertEquals(0L, out.formation.marksman);
    }

    // ==================== 工具 ====================

    private static int professionOf(long heroId) {
        return com.carlos.vault.wjdr.asset.HeroCatalog.profession(heroId);
    }
}
