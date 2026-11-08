package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.FormationStore;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link PresetFormations} 单测（契约 2026-11-02 §2/§3/§5）：19 套定义、最强职业回退、
 * 打熊队盾优先级、别名、采集 10 万阈值、熊上车冲突/排序/6 队/缺队长跳过、目录缺失降级、ensure 幂等。
 */
public class PresetFormationsTest {

    /** 每个英雄的容量贡献（测试目录统一值，便于预算断言）。 */
    private static final long TROOP = 10000L;

    private static final String[] EXPECT_NAMES = {
            "巨兽", "狩猎", "打熊队", "盾弓", "盾矛",
            "采集肉", "采集木", "采集煤", "采集铁",
            "熊上车1", "熊上车2", "熊上车3", "熊上车4", "熊上车5",
            "熊上车6", "熊上车7", "熊上车8", "熊上车9", "熊上车10"};

    private static final long[] BEAR_RIDE_CAPTAINS = {
            50036L, 50026L, 50027L, 50033L, 50038L,
            50043L, 50007L, 50028L, 50011L, 50035L};

    // ==================== 目录构造 ====================

    /** 最小可解析目录（configured=true + 容量 + 英雄）。 */
    private static FormationCatalogStore catalog(long fixedCapacity, String... heroJsons) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"configured\":true,\"marchCapacity\":{\"initialized\":true,")
                .append("\"fixedCapacity\":").append(fixedCapacity)
                .append(",\"percentageBasisPoints\":0},\"heroes\":[");
        for (int i = 0; i < heroJsons.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(heroJsons[i]);
        }
        sb.append("]}");
        return FormationCatalogStore.parse(sb.toString());
    }

    private static String hero(long id, String name, int profession, long power) {
        return hero(id, name, profession, power, 0, 0, 0);
    }

    private static String heroGen(long id, String name, int profession, long power,
                                  int starDisplay, int skillLevel, int weaponLevel, int generation) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"professionType\":" + profession
                + ",\"power\":" + power + ",\"starDisplay\":" + starDisplay
                + ",\"generation\":" + generation
                + ",\"level\":80,\"troopCapacity\":" + TROOP
                + ",\"available\":true,\"expeditionSkillLevel\":" + skillLevel
                + ",\"weaponLevel\":" + weaponLevel + "}";
    }

    private static String hero(long id, String name, int profession, long power,
                               int starDisplay, int skillLevel, int weaponLevel) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"professionType\":" + profession
                + ",\"power\":" + power + ",\"starDisplay\":" + starDisplay
                + ",\"level\":80,\"troopCapacity\":" + TROOP
                + ",\"available\":true,\"expeditionSkillLevel\":" + skillLevel
                + ",\"weaponLevel\":" + weaponLevel + "}";
    }

    /**
     * 标准测试目录：包含全部具名英雄 + 10 个熊上车队长 + 足量补位英雄。
     *
     * <p>技能等级（熊上车表顺序）：亨德里克1/格温5/书允4/布拉德利5/马格努斯3/
     * 布兰琪0/杰西2/杰赛尔5/赫罗尼莫4/索尼娅1。打熊队盾=4 星赫克托（非队长）。
     */
    private static FormationCatalogStore standardCatalog(long fixedCapacity) {
        List<String> heroes = new ArrayList<String>(Arrays.asList(
                // 打熊队盾优先级候选
                hero(50011L, "赫罗尼莫", 1, 7500, 3, 4, 0),
                hero(50024L, "赫克托", 1, 7000, 4, 2, 4),
                hero(50009L, "娜塔莉亚", 1, 6000, 3, 3, 0),
                // 具名矛/弓（打熊队 米亚 + 最强弓）
                hero(50029L, "卢姆·波根", 2, 9000, 4, 2, 3),
                hero(50016L, "米娅", 2, 8500, 4, 3, 2),
                hero(50012L, "茉莉", 2, 8000, 3, 1, 0),
                hero(50008L, "吉娜", 3, 9100, 5, 5, 10),
                hero(50033L, "布拉德利", 3, 8600, 4, 5, 2),
                hero(50020L, "格雷格", 3, 8200, 3, 1, 0),
                // 采集队长
                hero(50003L, "克劳瑞斯", 3, 5000, 3, 0, 0),
                hero(50000L, "尤金", 1, 5000, 3, 0, 0),
                hero(50002L, "查理", 2, 5000, 3, 0, 0),
                hero(50001L, "史密斯", 1, 5000, 3, 0, 0),
                // 其余熊上车队长
                hero(50036L, "亨德里克", 3, 4000, 4, 1, 0),
                hero(50026L, "格温", 3, 4000, 4, 5, 1),
                hero(50027L, "书允", 3, 4000, 4, 4, 0),
                hero(50038L, "马格努斯", 1, 4000, 4, 3, 0),
                hero(50043L, "布兰琪", 3, 4000, 4, 0, 0),
                hero(50007L, "杰西", 2, 4000, 4, 2, 0),
                hero(50028L, "杰塞尔", 3, 4000, 4, 5, 0),
                hero(50035L, "索尼娅", 2, 4000, 4, 1, 0),
                // 补位英雄（战力 3000 / 2500 / 2000，三职业均衡）
                hero(50004L, "谢尔盖", 1, 3000), hero(50005L, "帕特里克", 2, 3000),
                hero(50006L, "巴希提", 3, 3000), hero(50010L, "罗根", 1, 2500),
                hero(50013L, "菲兰德", 2, 2500), hero(50017L, "津曼", 3, 2500),
                hero(50021L, "弗林特", 1, 2500), hero(50022L, "琳恩", 3, 2500),
                hero(50025L, "诺拉", 2, 2500), hero(50030L, "芮妮", 2, 2500),
                hero(50031L, "韦恩", 3, 2500), hero(50032L, "艾迪丝", 1, 2500),
                hero(50034L, "加托", 1, 2000), hero(50037L, "凌霜", 2, 2000),
                hero(50039L, "弗雷德", 2, 2000), hero(50040L, "修拉", 3, 2000),
                hero(50041L, "格里高利", 1, 2000), hero(50042L, "芙蕾雅", 2, 2000),
                hero(50044L, "埃莱奥诺拉", 3, 2000), hero(50045L, "劳埃德", 2, 2000)));
        return catalog(fixedCapacity, heroes.toArray(new String[0]));
    }

    private static FormationModel find(List<FormationModel> list, long id) {
        return FormationRules.findById(list, id);
    }

    private static Set<Long> heroIdSet(List<FormationModel> teams) {
        Set<Long> out = new LinkedHashSet<Long>();
        for (FormationModel f : teams) out.addAll(f.heroIds);
        return out;
    }

    // ==================== 19 套 / 名称 / id / 模板键 / 兵力 ====================

    @Test
    public void all_nineteenPresets_withFrozenNamesIdsTemplatesAndAllocation() {
        FormationCatalogStore cat = standardCatalog(30000L);
        List<FormationModel> all = PresetFormations.all(cat);
        assertEquals(19, all.size());
        for (int i = 0; i < 19; i++) {
            FormationModel f = all.get(i);
            long expectId = 9100000001L + i;
            assertEquals(expectId, f.id);
            assertEquals(EXPECT_NAMES[i], f.name);
            assertNotNull(f.templateKey);
            assertTrue("templateKey=PRESET_*", f.templateKey.startsWith("PRESET_"));
            assertTrue(f.builtIn);
            assertEquals("CUSTOM", f.source);
            assertEquals(FormationModel.SOURCE_CUSTOM, f.source);
            assertTrue("isPreset", PresetFormations.isPreset(f.id));
            assertNotNull("captain 在 heroIds 内", f.captainHeroId);
            assertTrue(f.hasHero(f.captainHeroId.longValue()));
        }
        // 巨兽 = MANUAL 1/0/0；其余 PERCENTAGE
        FormationModel behemoth = all.get(0);
        assertEquals(FormationModel.MODE_MANUAL, behemoth.allocationMode);
        assertEquals(1L, behemoth.infantry);
        assertEquals(0L, behemoth.lancer);
        assertEquals(0L, behemoth.marksman);
        for (int i = 1; i < 19; i++) {
            assertEquals(EXPECT_NAMES[i] + " PERCENTAGE",
                    FormationModel.MODE_PERCENTAGE, all.get(i).allocationMode);
        }
        // 比例严格按契约表
        assertArrayEquals(new int[]{50, 20, 30}, ratio(all.get(1)));
        assertArrayEquals(new int[]{2, 3, 95}, ratio(all.get(2)));
        assertArrayEquals(new int[]{49, 2, 49}, ratio(all.get(3)));
        assertArrayEquals(new int[]{60, 39, 1}, ratio(all.get(4)));
        for (int i = 5; i <= 8; i++) assertArrayEquals(new int[]{34, 33, 33}, ratio(all.get(i)));
        for (int i = 9; i < 19; i++) assertArrayEquals(new int[]{10, 20, 70}, ratio(all.get(i)));
        // 兵力预算：比例和=100 → 三兵种合计 == 该组合容量
        for (FormationModel f : all) {
            if (f.isPercentage() && !f.heroIds.isEmpty()) {
                assertEquals(f.name + " 预算=容量", f.soldiersTotal(),
                        cat.capacityFor(f.heroIds));
            }
        }
        // 具体预算（固定 30000 + 3*10000 = 60000）
        assertArrayEquals(new long[]{30000, 12000, 18000}, soldiers(all.get(1)));
        assertArrayEquals(new long[]{1200, 1800, 57000}, soldiers(all.get(2)));
        assertArrayEquals(new long[]{6000, 12000, 42000}, soldiers(all.get(9)));
    }

    private static int[] ratio(FormationModel f) {
        return new int[]{f.infantryRatio, f.lancerRatio, f.marksmanRatio};
    }

    private static long[] soldiers(FormationModel f) {
        return new long[]{f.infantry, f.lancer, f.marksman};
    }

    // ==================== 最强职业回退 / 具名优先 ====================

    @Test
    public void behemoth_namedHeroesFirst_thenStrongestProfessionFallback() {
        // 无波根/吉娜 → 最强矛/弓
        FormationCatalogStore fallback = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50005L, "矛A", 2, 4000),
                hero(50006L, "弓A", 3, 4500),
                hero(50010L, "盾B", 1, 1000));
        List<FormationModel> all = PresetFormations.all(fallback);
        assertEquals(Arrays.asList(50004L, 50005L, 50006L), all.get(0).heroIds);
        assertEquals("狩猎同巨兽英雄", all.get(0).heroIds, all.get(1).heroIds);

        // 有波根/吉娜 → 具名优先（即使战力更低）
        FormationCatalogStore named = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50005L, "矛B", 2, 9000),
                hero(50006L, "弓B", 3, 9000),
                hero(50029L, "卢姆·波根", 2, 100),
                hero(50008L, "吉娜", 3, 100));
        List<FormationModel> namedAll = PresetFormations.all(named);
        assertEquals(Arrays.asList(50004L, 50029L, 50008L), namedAll.get(0).heroIds);
    }

    // ==================== 打熊队盾优先级 ====================

    @Test
    public void bearTeam_shieldPriority_fullNemoThenHectorThenNemoNataliaThenStrongest() {
        // ① 满星（starDisplay=5）且满专武（weaponLevel=10）尼莫
        assertEquals(50011L, bearTeamShield(catalog(30000L,
                hero(50011L, "赫罗尼莫", 1, 7000, 5, 4, 10),
                hero(50024L, "赫克托", 1, 9000, 5, 2, 10),
                hero(50009L, "娜塔莉亚", 1, 8000)))
                .heroIds.get(0).longValue());
        // ② 尼莫未满专武 → 4 星赫克托
        assertEquals(50024L, bearTeamShield(catalog(30000L,
                hero(50011L, "赫罗尼莫", 1, 9000, 5, 4, 9),
                hero(50024L, "赫克托", 1, 7000, 4, 2, 0))).heroIds.get(0).longValue());
        // ③ 尼莫（未满星/未满专武）优先于娜塔莉亚
        assertEquals(50011L, bearTeamShield(catalog(30000L,
                hero(50011L, "赫罗尼莫", 1, 7000, 3, 4, 0),
                hero(50009L, "娜塔莉亚", 1, 9000))).heroIds.get(0).longValue());
        // ③ 无尼莫 → 娜塔莉亚
        assertEquals(50009L, bearTeamShield(catalog(30000L,
                hero(50009L, "娜塔莉亚", 1, 6000),
                hero(50004L, "盾A", 1, 9000))).heroIds.get(0).longValue());
        // ④ 都缺 → 最强盾
        assertEquals(50004L, bearTeamShield(catalog(30000L,
                hero(50004L, "盾A", 1, 9000),
                hero(50010L, "盾B", 1, 1000))).heroIds.get(0).longValue());
    }

    private static FormationModel bearTeamShield(FormationCatalogStore cat) {
        return PresetFormations.all(cat).get(2);
    }

    @Test
    public void bearTeam_bowUsesPowerIncludingGear_notGeneration() {
        // 用户口径（2026-11-08）：不按代选人——游戏 power 已含装备/星级/专武综合加成，
        // 高代弓通常战力最高，自然当选；这里验证排序依据是 power，不是 generation。
        FormationCatalogStore cat = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50016L, "米娅", 2, 100),
                heroGen(50006L, "巴希提", 3, 9000, 5, 5, 10, 0),   // 战力（含装备）最高
                heroGen(50022L, "琳恩", 3, 3000, 2, 1, 0, 4));      // 高代但战力低
        FormationModel bear = PresetFormations.all(cat).get(2);
        assertEquals("打熊队弓按战力（含装备）选最强，不按代际",
                Long.valueOf(50006L), bear.heroIds.get(2));
    }

    @Test
    public void bearTeam_prefersStrongest_evenIfBearRideCaptain() {
        // 用户口径（2026-11-08）：打熊队优先级高于上车队——最强矛/弓即使是熊上车队长
        // 也必须给发车队；上车队侧再避让（bearRideUsed 含发车队英雄，不重复占用）。
        FormationCatalogStore cat = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50035L, "索尼娅", 2, 9999),
                hero(50036L, "亨德里克", 3, 9999),
                hero(50005L, "矛B", 2, 1000),
                hero(50006L, "弓B", 3, 1000));
        FormationModel bear = PresetFormations.all(cat).get(2);
        assertEquals("打熊队应占用最强矛/弓（即使它们是熊上车队长）",
                Arrays.asList(50004L, 50035L, 50036L), bear.heroIds);
        // 上车队不得重复占用发车队英雄
        List<FormationModel> all = PresetFormations.all(cat);
        for (int i = 9; i < all.size(); i++) {
            for (Long id : all.get(i).heroIds) {
                assertFalse("熊上车队不得重复使用打熊队英雄 " + id,
                        bear.heroIds.contains(id));
            }
        }
        // 米亚优先于最强矛
        FormationCatalogStore withMia = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50016L, "米娅", 2, 100),
                hero(50005L, "矛B", 2, 9000),
                hero(50006L, "弓B", 3, 1000));
        assertEquals(Long.valueOf(50016L), PresetFormations.all(withMia).get(2).heroIds.get(1));
    }

    // ==================== 别名 ====================

    @Test
    public void aliases_resolveToHeroCatalogNames() {
        assertEquals("卢姆·波根", PresetFormations.canonicalHeroName("波根"));
        assertEquals("米娅", PresetFormations.canonicalHeroName("米亚"));
        assertEquals("杰塞尔", PresetFormations.canonicalHeroName("杰赛尔"));
        assertEquals("赫罗尼莫", PresetFormations.canonicalHeroName("尼莫"));
        assertEquals("吉娜", PresetFormations.canonicalHeroName("吉娜"));
        assertEquals("", PresetFormations.canonicalHeroName(null));

        FormationCatalogStore cat = catalog(30000L,
                hero(50029L, "卢姆·波根", 2, 1000),
                hero(50016L, "米娅", 2, 1000),
                hero(50028L, "杰塞尔", 3, 1000),
                hero(50011L, "赫罗尼莫", 1, 1000));
        assertEquals(50029L, PresetFormations.resolveHeroId(cat, "波根"));
        assertEquals(50016L, PresetFormations.resolveHeroId(cat, "米亚"));
        assertEquals(50028L, PresetFormations.resolveHeroId(cat, "杰赛尔"));
        assertEquals(50011L, PresetFormations.resolveHeroId(cat, "尼莫"));
        assertEquals(50029L, PresetFormations.resolveHeroId(cat, "卢姆·波根"));
        assertEquals("未拥有 → 0", 0L, PresetFormations.resolveHeroId(cat, "格雷格"));
        assertEquals(0L, PresetFormations.resolveHeroId(null, "波根"));
        assertEquals(0L, PresetFormations.resolveHeroId(cat, null));

        // 巨兽矛位按别名解析（目录名=卢姆·波根）
        FormationCatalogStore onlyBorgan = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50029L, "卢姆·波根", 2, 1000),
                hero(50006L, "弓A", 3, 5000));
        assertEquals(Long.valueOf(50029L),
                PresetFormations.all(onlyBorgan).get(0).heroIds.get(1));
    }

    // ==================== 采集：10 万阈值 / 队列间不重复 ====================

    @Test
    public void gather_capacityAboveThreshold_onlyCaptain() {
        List<FormationModel> all = PresetFormations.all(standardCatalog(200000L));
        long[] gatherIds = {PresetFormations.HERO_GATHER_MEAT, PresetFormations.HERO_GATHER_WOOD,
                PresetFormations.HERO_GATHER_COAL, PresetFormations.HERO_GATHER_IRON};
        for (int i = 0; i < 4; i++) {
            FormationModel f = all.get(5 + i);
            assertEquals(f.name + " 只带队长", 1, f.heroIds.size());
            assertEquals(Long.valueOf(gatherIds[i]), f.heroIds.get(0));
            assertEquals("队长=有效队长", Long.valueOf(gatherIds[i]), f.captainHeroId);
        }
    }

    @Test
    public void gather_belowThreshold_threeHeroes_noDuplicateAcrossQueues() {
        List<FormationModel> all = PresetFormations.all(standardCatalog(30000L));
        List<FormationModel> gathers = all.subList(5, 9);
        for (FormationModel f : gathers) {
            assertEquals(f.name + " 3 人", 3, f.heroIds.size());
            assertEquals("队长在首位", true, f.hasHero(f.captainHeroId.longValue()));
            assertEquals("编队内英雄不重复", 3, new HashSet<Long>(f.heroIds).size());
        }
        Set<Long> union = heroIdSet(gathers);
        assertEquals("采集队列之间英雄不得重复", 12, union.size());
        // 其他采集队长不会被补进别人的队列
        for (FormationModel f : gathers) {
            assertEquals(Integer.valueOf(3), Integer.valueOf(new HashSet<Long>(f.heroIds).size()));
        }
        assertEquals(50003L, all.get(5).heroIds.get(0).longValue());
        assertEquals(50000L, all.get(6).heroIds.get(0).longValue());
        assertEquals(50002L, all.get(7).heroIds.get(0).longValue());
        assertEquals(50001L, all.get(8).heroIds.get(0).longValue());
        // 每队职业不重复（游戏每队列盾/矛/弓各一）
        FormationCatalogStore cat = standardCatalog(30000L);
        for (FormationModel f : gathers) {
            Set<Integer> profs = new HashSet<Integer>();
            for (Long id : f.heroIds) {
                int p = FormationRules.professionOf(cat.hero(id.longValue()));
                if (p > 0) assertTrue("同队职业不重复: " + f.name, profs.add(Integer.valueOf(p)));
            }
        }
    }

    // ==================== 熊上车：排序 / 6 队 / 冲突 / 缺队长 ====================

    @Test
    public void bearRide_skillSorting_keepsTopSixBySkillThenTableOrder() {
        FormationCatalogStore cat = standardCatalog(30000L);
        List<FormationModel> lib = new ArrayList<FormationModel>();
        assertEquals("5 基础 + 4 采集 + 6 熊上车", 15, PresetFormations.ensure(lib, cat));

        List<FormationModel> kept = new ArrayList<FormationModel>();
        for (FormationModel f : lib) {
            if (f.id >= 9100000010L && f.id <= 9100000019L) kept.add(f);
        }
        assertEquals("最多保留 6 队", 6, kept.size());
        List<Long> keptIds = new ArrayList<Long>();
        for (FormationModel f : kept) keptIds.add(Long.valueOf(f.id));
        Collections.sort(keptIds);
        // 5 级：熊上车2/4/8 按表顺序 → 4 级：熊上车3/9 → 3 级：熊上车5
        assertEquals(Arrays.asList(9100000011L, 9100000012L, 9100000013L,
                9100000014L, 9100000017L, 9100000018L), keptIds);
        // 未入选的 4 队不写入库
        for (long id : new long[]{9100000010L, 9100000015L, 9100000016L, 9100000019L}) {
            assertNull("备选队不写入库: " + id, find(lib, id));
        }
        // 队长=首位
        assertEquals(50026L, find(lib, 9100000011L).heroIds.get(0).longValue());
        assertEquals(50027L, find(lib, 9100000012L).heroIds.get(0).longValue());
        assertEquals(50033L, find(lib, 9100000013L).heroIds.get(0).longValue());
        assertEquals(50038L, find(lib, 9100000014L).heroIds.get(0).longValue());
        assertEquals(50028L, find(lib, 9100000017L).heroIds.get(0).longValue());
        assertEquals(50011L, find(lib, 9100000018L).heroIds.get(0).longValue());
    }

    @Test
    public void bearRide_heroesDoNotConflictWithBearTeamOrEachOther() {
        FormationCatalogStore cat = standardCatalog(30000L);
        List<FormationModel> lib = new ArrayList<FormationModel>();
        PresetFormations.ensure(lib, cat);
        List<FormationModel> bearTeam = new ArrayList<FormationModel>();
        bearTeam.add(find(lib, 9100000003L));
        List<FormationModel> ride = new ArrayList<FormationModel>();
        for (FormationModel f : lib) {
            if (f.id >= 9100000010L && f.id <= 9100000019L) ride.add(f);
        }
        Set<Long> bearTeamHeroes = heroIdSet(bearTeam);
        Set<Long> rideHeroes = heroIdSet(ride);
        assertEquals("熊上车与打熊队不重复", 0,
                intersectionSize(bearTeamHeroes, rideHeroes));
        for (FormationModel f : ride) {
            Set<Long> own = new LinkedHashSet<Long>(f.heroIds);
            assertEquals("每队 3 人且不重复（含补位）", 3, own.size());
        }
        assertEquals("熊上车各队之间不重复", ride.size() * 3, rideHeroes.size());
    }

    @Test
    public void bearRide_captainConflictsWithBearTeam_skipped() {
        // 满星满专武尼莫 → 打熊队盾=50011（也是熊上车9队长）→ 熊上车9 跳过
        FormationCatalogStore cat = catalog(30000L,
                hero(50011L, "赫罗尼莫", 1, 7000, 5, 4, 10),
                hero(50004L, "盾A", 1, 1000), hero(50005L, "矛A", 2, 500),
                hero(50006L, "弓A", 3, 500),
                hero(50026L, "格温", 3, 1000), hero(50027L, "书允", 3, 1000),
                hero(50028L, "杰塞尔", 3, 1000), hero(50033L, "布拉德利", 3, 1000),
                hero(50036L, "亨德里克", 3, 1000), hero(50038L, "马格努斯", 1, 1000),
                hero(50043L, "布兰琪", 3, 1000),
                hero(50007L, "杰西", 2, 1000), hero(50035L, "索尼娅", 2, 1000));
        List<FormationModel> lib = new ArrayList<FormationModel>();
        PresetFormations.ensure(lib, cat);
        assertEquals("打熊队盾=满星满专武尼莫", 50011L,
                find(lib, 9100000003L).heroIds.get(0).longValue());
        assertNull("与打熊队冲突的熊上车9 跳过", find(lib, 9100000018L));
        // 其余熊上车队不含打熊队英雄
        Set<Long> bearTeamHeroes = new LinkedHashSet<Long>(find(lib, 9100000003L).heroIds);
        for (FormationModel f : lib) {
            if (f.id >= 9100000010L && f.id <= 9100000019L) {
                assertEquals("熊上车与打熊队不重复: " + f.name, 0,
                        intersectionSize(bearTeamHeroes, new LinkedHashSet<Long>(f.heroIds)));
            }
        }
    }

    @Test
    public void bearRide_missingCaptainsSkipped() {
        FormationCatalogStore cat = catalog(30000L,
                hero(50004L, "盾A", 1, 5000), hero(50005L, "矛A", 2, 4000),
                hero(50006L, "弓A", 3, 4000),
                hero(50026L, "格温", 3, 500, 4, 5, 0),
                hero(50036L, "亨德里克", 3, 500, 4, 1, 0));
        List<FormationModel> lib = new ArrayList<FormationModel>();
        PresetFormations.ensure(lib, cat);
        List<FormationModel> ride = new ArrayList<FormationModel>();
        for (FormationModel f : lib) {
            if (f.id >= 9100000010L && f.id <= 9100000019L) ride.add(f);
        }
        assertEquals("只创建已拥有队长的 2 队", 2, ride.size());
        assertNotNull(find(lib, 9100000011L));   // 格温（5 级优先）
        assertNotNull(find(lib, 9100000010L));   // 亨德里克
        assertNull(find(lib, 9100000012L));      // 书允未拥有
        // all() 仍返回 19 条骨架
        assertEquals(19, PresetFormations.all(cat).size());
        assertTrue(PresetFormations.all(cat).get(11).heroIds.isEmpty());
    }

    // ==================== 目录缺失降级 ====================

    @Test
    public void directoryMissing_degradesToEmptyHeroSkeleton() {
        for (FormationCatalogStore cat : Arrays.asList(
                FormationCatalogStore.empty(), null,
                FormationCatalogStore.parse("{bad json"),
                FormationCatalogStore.parse("{\"heroes\":[{\"id\":50011,\"name\":\"赫罗尼莫\"}]}"))) {
            List<FormationModel> all = PresetFormations.all(cat);
            assertEquals(19, all.size());
            for (FormationModel f : all) {
                assertTrue(f.name + " 英雄为空", f.heroIds.isEmpty());
                assertNull(f.captainHeroId);
                if (f.id == PresetFormations.ID_FIRST) {
                    assertEquals("巨兽骨架仍为 MANUAL 1/0/0", 1L, f.soldiersTotal());
                } else {
                    assertEquals("目录缺失不预算兵力", 0L, f.soldiersTotal());
                }
            }
            List<FormationModel> lib = new ArrayList<FormationModel>();
            assertEquals("目录未就绪不创建", 0, PresetFormations.ensure(lib, cat));
            assertTrue(lib.isEmpty());
        }
        // 有英雄但容量未同步（configured=false）同样降级
        FormationCatalogStore noCapacity = FormationCatalogStore.parse(
                "{\"heroes\":[{\"id\":50011,\"name\":\"赫罗尼莫\",\"professionType\":1}]}");
        assertFalse(noCapacity.configured());
        assertTrue(PresetFormations.all(noCapacity).get(0).heroIds.isEmpty());
        assertEquals(0, PresetFormations.ensure(new ArrayList<FormationModel>(), noCapacity));
    }

    // ==================== ensure 幂等 / 不覆盖用户改动 / 补骨架英雄 ====================

    @Test
    public void ensure_isIdempotent_andRefreshesPresetContent() {
        FormationCatalogStore cat = standardCatalog(30000L);
        List<FormationModel> lib = new ArrayList<FormationModel>();
        assertEquals(15, PresetFormations.ensure(lib, cat));
        assertEquals(15, lib.size());
        assertEquals("第二次幂等", 0, PresetFormations.ensure(lib, cat));
        assertEquals(15, lib.size());

        FormationModel behemoth = find(lib, 9100000001L);
        // 2026-11-03：预设是实时模板，即使旧数据里 updatedAt>0，内容也要按目录刷新；
        // 用户编辑时 App 会另存为新编队，不会改预设本体。
        behemoth.updatedAt = 123456789L;
        behemoth.version = 2L;
        behemoth.name = "我改的巨兽";
        behemoth.infantry = 123;
        assertEquals(0, PresetFormations.ensure(lib, cat));
        assertEquals("预设名称保持", "我改的巨兽", behemoth.name);
        assertEquals("兵量按预设定义刷新（巨兽 MANUAL 1/0/0）", 1L, behemoth.infantry);
    }

    @Test
    public void ensure_fillsHeroesForEarlyEmptySkeleton() {
        FormationCatalogStore cat = standardCatalog(30000L);
        List<FormationModel> lib = new ArrayList<FormationModel>();
        FormationModel skeleton = PresetFormations.byId(9100000001L);
        assertNotNull(skeleton);
        assertTrue(skeleton.heroIds.isEmpty());
        lib.add(skeleton);
        // 骨架已存在 → 补英雄但不计新增
        assertEquals("其余 14 套补齐", 14, PresetFormations.ensure(lib, cat));
        assertFalse("已存在骨架补英雄", skeleton.heroIds.isEmpty());
        assertEquals(15, lib.size());
        assertEquals("保留骨架名称", "巨兽", skeleton.name);
    }

    @Test
    public void ensure_refreshesUntouchedPreset_whenCatalogChanges() {
        // 第一轮：目录只有基础英雄 → 巨兽矛/弓回退最强（50005/50006）
        FormationCatalogStore first = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50005L, "矛A", 2, 4000),
                hero(50006L, "弓A", 3, 4500));
        List<FormationModel> lib = new ArrayList<FormationModel>();
        PresetFormations.ensure(lib, first);
        FormationModel behemoth = find(lib, 9100000001L);
        assertEquals(Arrays.asList(50004L, 50005L, 50006L), behemoth.heroIds);
        // 第二轮：获得波根/吉娜（未编辑过 → 刷新为具名英雄）
        FormationCatalogStore second = catalog(30000L,
                hero(50004L, "盾A", 1, 5000),
                hero(50005L, "矛A", 2, 4000),
                hero(50006L, "弓A", 3, 4500),
                hero(50029L, "卢姆·波根", 2, 100),
                hero(50008L, "吉娜", 3, 100));
        PresetFormations.ensure(lib, second);
        assertEquals(Arrays.asList(50004L, 50029L, 50008L), find(lib, 9100000001L).heroIds);
        // 旧数据里即使有 updatedAt/version，也按实时模板刷新（用户编辑会另存新编队）
        behemoth.updatedAt = 1L;
        behemoth.version = 2L;
        behemoth.heroIds.clear();
        behemoth.heroIds.add(50004L);
        PresetFormations.ensure(lib, second);
        assertEquals("预设始终刷新为最强盾/波根/吉娜", 3,
                find(lib, 9100000001L).heroIds.size());
        assertEquals(Arrays.asList(50004L, 50029L, 50008L),
                find(lib, 9100000001L).heroIds);
    }

    // ==================== 公开 API 边界 ====================

    @Test
    public void apiBounds_byIdAndIsPreset() {
        assertTrue(PresetFormations.isPreset(9100000001L));
        assertTrue(PresetFormations.isPreset(9100000019L));
        assertFalse(PresetFormations.isPreset(9100000000L));
        assertFalse(PresetFormations.isPreset(9100000020L));
        assertFalse(PresetFormations.isPreset(9000000001L));
        assertNull(PresetFormations.byId(9100000020L));
        assertNull(PresetFormations.byId(9000000001L));
        assertEquals("熊上车10", PresetFormations.byId(9100000019L).name);
        assertEquals("采集煤", PresetFormations.byId(9100000008L).name);
        assertEquals(PresetFormations.TPL_BEHEMOTH, PresetFormations.byId(9100000001L).templateKey);
        assertEquals(FormationModel.MODE_MANUAL,
                PresetFormations.byId(9100000001L).allocationMode);
    }

    // ==================== App 侧 Hero 技能/专武宽容解析（契约 §1） ====================

    @Test
    public void parseHeroes_skillLevelAndWeaponLevelAliases() throws Exception {
        JSONObject catalog = new JSONObject("{\"heroes\":["
                + "{\"id\":50011,\"name\":\"赫罗尼莫\",\"expeditionSkillLevel\":5,\"weaponLevel\":10},"
                + "{\"id\":50012,\"name\":\"茉莉\",\"expedition_skill_level\":4,\"elv\":9},"
                + "{\"id\":50013,\"name\":\"菲兰德\",\"skillLevel\":\"3\",\"weapon_level\":\"8\"},"
                + "{\"id\":50014,\"name\":\"玲奈\",\"skill_level\":2,\"exclusiveWeaponLevel\":7},"
                + "{\"id\":50015,\"name\":\"哥顿\",\"firstSkillLevel\":1,"
                + "\"exclusive_weapon_level\":6},"
                + "{\"id\":50016,\"name\":\"米娅\",\"first_skill_level\":5},"
                + "{\"id\":50017,\"name\":\"津曼\"}]}");
        List<FormationStore.Hero> heroes = FormationStore.parseHeroes(catalog);
        assertEquals(7, heroes.size());
        assertEquals(5, heroes.get(0).skillLevel);
        assertEquals(10, heroes.get(0).weaponLevel);
        assertEquals(4, heroes.get(1).skillLevel);
        assertEquals(9, heroes.get(1).weaponLevel);
        assertEquals("字符串数字", 3, heroes.get(2).skillLevel);
        assertEquals(8, heroes.get(2).weaponLevel);
        assertEquals(2, heroes.get(3).skillLevel);
        assertEquals(7, heroes.get(3).weaponLevel);
        assertEquals(1, heroes.get(4).skillLevel);
        assertEquals(6, heroes.get(4).weaponLevel);
        assertEquals(5, heroes.get(5).skillLevel);
        assertEquals("缺字段默认 0", 0, heroes.get(5).weaponLevel);
        assertEquals(0, heroes.get(6).skillLevel);
        assertEquals(0, heroes.get(6).weaponLevel);
    }

    private static int intersectionSize(Set<Long> a, Set<Long> b) {
        int n = 0;
        for (Long x : a) if (b.contains(x)) n++;
        return n;
    }
}
