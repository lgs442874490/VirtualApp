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
import java.util.List;

/**
 * FormationRules 单测（契约 §2.4 / 网页 §4）：容量公式、最大余数分配、夹取顺序、
 * 校验文案逐字、跨队列英雄冲突、比例码/格式化。
 */
public class FormationRulesTest {

    /** 服务端 state v2 目录（契约 §3.2 形状；容量实测 role5：140990+13470×3=181400）。 */
    private static FormationCatalogStore catalog() throws Exception {
        return FormationCatalogStore.parse("{\"version\":2,\"configured\":true,"
                + "\"heroes\":["
                + "{\"id\":50001,\"name\":\"盾A\",\"profession\":1,\"professionType\":1,"
                + "\"professionName\":\"盾兵\",\"level\":80,\"power\":100,\"troopCapacity\":13470,"
                + "\"available\":true,\"availabilityState\":\"AVAILABLE\"},"
                + "{\"id\":50002,\"name\":\"矛A\",\"profession\":2,\"professionType\":2,"
                + "\"level\":56,\"power\":90,\"troopCapacity\":11860,"
                + "\"available\":true,\"availabilityState\":\"AVAILABLE\"},"
                + "{\"id\":50003,\"name\":\"弓A\",\"profession\":3,\"professionType\":3,"
                + "\"level\":80,\"power\":80,\"troopCapacity\":13470,"
                + "\"available\":false,\"availabilityState\":\"HERO_AWAY\","
                + "\"availabilityReason\":\"英雄未归队\"},"
                + "{\"id\":50004,\"name\":\"盾B\",\"profession\":1,\"professionType\":1,"
                + "\"level\":80,\"power\":999,\"troopCapacity\":13470,\"available\":true}"
                + "],"
                + "\"marchCapacity\":{\"initialized\":true,\"fixedCapacity\":140990,"
                + "\"percentageBasisPoints\":0,"
                + "\"limits\":{\"threeHeroLimit\":181400,\"twoHeroLimit\":167930,"
                + "\"oneHeroLimit\":154460,\"zeroHeroLimit\":140990}}}");
    }

    private static FormationModel formation(String name, long... heroes) {
        FormationModel f = new FormationModel(name, toList(heroes));
        return f;
    }

    private static List<Long> toList(long... ids) {
        List<Long> out = new ArrayList<Long>();
        for (long id : ids) out.add(id);
        return out;
    }

    // ==================== 容量公式（§2.4 / 网页 C1/C2） ====================

    @Test
    public void heroCapacity_formulaMatchesWebSample() throws Exception {
        FormationCatalogStore cat = catalog();
        assertEquals("0 英雄 = fixedCapacity", 140990L,
                FormationRules.heroCapacity(cat, new ArrayList<Long>()));
        assertEquals("1 英雄 = 140990+13470", 154460L,
                FormationRules.heroCapacity(cat, toList(50001L)));
        assertEquals("2 英雄 = 140990+13470+11860", 166320L,
                FormationRules.heroCapacity(cat, toList(50001L, 50002L)));
        // 13470 + 11860 + 13470 = 38800 → 140990 + 38800 = 179790
        assertEquals(179790L, FormationRules.heroCapacity(cat, toList(50001L, 50002L, 50003L)));
    }

    @Test
    public void heroCapacity_percentageBuffAndFloor() throws Exception {
        FormationCatalogStore cat = FormationCatalogStore.parse("{\"configured\":true,"
                + "\"heroes\":[{\"id\":50001,\"professionType\":1,\"troopCapacity\":13470,"
                + "\"available\":true}],"
                + "\"marchCapacity\":{\"initialized\":true,\"fixedCapacity\":1000,"
                + "\"percentageBasisPoints\":1500}}");
        // (1000+13470)*11500/10000 = 14470*1.15 = 16640.5 → floor 16640
        assertEquals(16640L, FormationRules.heroCapacity(cat, toList(50001L)));
        // bp=0 时 floor
        FormationCatalogStore odd = FormationCatalogStore.parse("{\"configured\":true,"
                + "\"heroes\":[{\"id\":50001,\"professionType\":1,\"troopCapacity\":3,\"available\":true}],"
                + "\"marchCapacity\":{\"initialized\":true,\"fixedCapacity\":10,"
                + "\"percentageBasisPoints\":0}}");
        assertEquals(13L, FormationRules.heroCapacity(odd, toList(50001L)));
    }

    @Test
    public void heroCapacity_degradedAndHuge() throws Exception {
        assertEquals("目录缺失/未同步 → 0", 0L,
                FormationRules.heroCapacity(FormationCatalogStore.empty(), toList(50001L)));
        assertEquals("null 目录 → 0", 0L, FormationRules.heroCapacity(null, toList(50001L)));
        FormationCatalogStore huge = FormationCatalogStore.parse("{\"configured\":true,"
                + "\"marchCapacity\":{\"initialized\":true,"
                + "\"fixedCapacity\":9223372036854775,\"percentageBasisPoints\":100000000}}");
        assertEquals("超大数夹到 Long.MAX 不溢出", Long.MAX_VALUE,
                FormationRules.heroCapacity(huge, new ArrayList<Long>()));
    }

    // ==================== 比例 → 兵力：最大余数法（网页 C5） ====================

    @Test
    public void allocateByRatio_webSample() {
        assertArrayEquals(new long[]{90700L, 36280L, 54420L},
                FormationRules.allocateByRatio(181400, 50, 20, 30));
        assertArrayEquals(new long[]{15077L, 15077L, 120616L},
                FormationRules.allocateByRatio(150770, 10, 10, 80));
        assertArrayEquals(new long[]{0L, 0L, 0L},
                FormationRules.allocateByRatio(0, 50, 20, 30));
        assertArrayEquals("比例和=0 → 全 0",
                new long[]{0L, 0L, 0L}, FormationRules.allocateByRatio(100, 0, 0, 0));
        assertArrayEquals("负比例归 0",
                new long[]{50L, 0L, 50L}, FormationRules.allocateByRatio(100, 50, -10, 50));
    }

    @Test
    public void allocateByRatio_remainderTiesShieldFirst() {
        // target=round(334*3/100)=10，三份 3 余 1 并列 → 先盾，再矛（盾→矛→弓）
        assertArrayEquals(new long[]{4L, 3L, 3L},
                FormationRules.allocateByRatio(334, 1, 1, 1));
        // target=round(167*3/100)=5，三份 1 余 2 并列 → 盾、矛各 +1
        assertArrayEquals(new long[]{2L, 2L, 1L},
                FormationRules.allocateByRatio(167, 1, 1, 1));
        // 单项余数最大者优先：101×(50/25/25) → 51/25/25
        assertArrayEquals(new long[]{51L, 25L, 25L},
                FormationRules.allocateByRatio(101, 50, 25, 25));
    }

    @Test
    public void allocateByRatio_ratioSumBelow100() {
        assertArrayEquals(new long[]{40L, 30L, 0L},
                FormationRules.allocateByRatio(100, 40, 30, 0));
        // target=round(181400*70/100)=126980 → 40/30/0 权重按 70 分
        long[] out = FormationRules.allocateByRatio(181400, 40, 30, 0);
        assertEquals(126980L, out[0] + out[1] + out[2]);
    }

    @Test
    public void allocateByRatio_hugeCapacityDoesNotOverflow() {
        long[] out = FormationRules.allocateByRatio(Long.MAX_VALUE, 50, 20, 30);
        assertTrue("三兵种非负", out[0] >= 0 && out[1] >= 0 && out[2] >= 0);
        assertTrue("至少盾/矛/弓有值", out[0] > 0 && out[1] > 0 && out[2] > 0);
    }

    // ==================== 容量夹取（网页 C6/C7：弓→矛→盾） ====================

    @Test
    public void clampCounts_withinCapacityUnchanged() {
        assertArrayEquals(new long[]{50L, 30L, 20L},
                FormationRules.clampCounts(100, 50, 30, 20));
        assertArrayEquals("容量未知（≤0）不夹取（未同步时编辑禁用）",
                new long[]{999L, 888L, 777L}, FormationRules.clampCounts(0, 999, 888, 777));
        assertTrue(FormationRules.clampCounts(-1, 5, 5, 5)[0] == 5L);
    }

    @Test
    public void clampCounts_cutArcherFirstThenLancerThenShield() {
        // 总 120，容量 100：先削弓 30→10
        assertArrayEquals(new long[]{50L, 40L, 10L},
                FormationRules.clampCounts(100, 50, 40, 30));
        // 总 120，容量 60：弓削光（-30）再削矛至 10
        assertArrayEquals(new long[]{50L, 10L, 0L},
                FormationRules.clampCounts(60, 50, 40, 30));
        // 容量 10：弓、矛削光，盾削到 10
        assertArrayEquals(new long[]{10L, 0L, 0L},
                FormationRules.clampCounts(10, 50, 40, 30));
        // 负数输入归 0
        assertArrayEquals(new long[]{5L, 0L, 0L},
                FormationRules.clampCounts(5, 5, -1, -1));
    }

    @Test
    public void clampToCapacity_mutatesModel() {
        FormationModel f = formation("x", 50001L);
        f.infantry = 50;
        f.lancer = 40;
        f.marksman = 30;
        assertTrue(FormationRules.clampToCapacity(f, 100));
        assertEquals(10L, f.marksman);
        assertFalse("已在容量内 → 不再变更",
                FormationRules.clampToCapacity(f, 100));
    }

    // ==================== 比例码 / 格式化（网页 P2 / §2.4） ====================

    @Test
    public void ratioCode_webParity() {
        assertEquals("523", FormationRules.ratioCode(50, 20, 30));
        assertEquals("514", FormationRules.ratioCode(50, 10, 40));
        assertEquals("640", FormationRules.ratioCode(60, 40, 0));
        assertEquals("505", FormationRules.ratioCode(50, 0, 50));
        assertEquals("433", FormationRules.ratioCode(40, 30, 30));
        assertEquals("50:20:35", FormationRules.ratioCode(50, 20, 35));
        assertEquals("非法负值归 0（0/0/0 仍是 10 倍数拼接）", "000", FormationRules.ratioCode(-1, 0, 0));
        assertArrayEquals(new int[]{50, 20, 30}, FormationRules.parseRatioCode("523"));
        assertNull(FormationRules.parseRatioCode("abcd"));
        assertNull(FormationRules.parseRatioCode(null));
    }

    @Test
    public void fmt_thousandsSeparator() {
        assertEquals("181,400", FormationRules.fmt(181400));
        assertEquals("0", FormationRules.fmt(0));
        assertEquals("999", FormationRules.fmt(999));
        assertEquals("1,000", FormationRules.fmt(1000));
        assertEquals("-1,234,567", FormationRules.fmt(-1234567L));
        assertEquals("9,223,372,036,854,775,807", FormationRules.fmt(Long.MAX_VALUE));
        assertEquals("-9,223,372,036,854,775,808", FormationRules.fmt(Long.MIN_VALUE));
        assertEquals("1,234", FormationRules.fmt(1234));
    }

    // ==================== 校验（契约 §2.5 逐字） ====================

    @Test
    public void validate_nameRules() throws Exception {
        FormationModel f = formation("  ", 50001L);
        assertEquals("请填写编队名称", FormationRules.validate(f, catalog()));
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 31; i++) name.append('队');
        f.name = name.toString();
        assertEquals("编队名称最多 30 个字", FormationRules.validate(f, catalog()));
        assertEquals("空编队返回原因而非 null", FormationRules.MSG_SELECT_FORMATION,
                FormationRules.validate(null, catalog()));
    }

    @Test
    public void validate_heroCountAndProfession() throws Exception {
        FormationCatalogStore cat = catalog();
        FormationModel four = formation("x", 50001L, 50002L, 50003L, 50004L);
        assertEquals("编队最多只能选择三个英雄", FormationRules.validate(four, cat));
        // 盾A + 盾B 同职业
        FormationModel dup = formation("x", 50001L, 50004L);
        assertEquals("同一编队不能选择两个相同兵种职业的英雄",
                FormationRules.validate(dup, cat));
        // 盾/矛/弓各一 → 合法
        FormationModel ok = formation("x", 50001L, 50002L, 50003L);
        ok.captainHeroId = 50001L;
        assertNull(FormationRules.validate(ok, cat));
        // 无英雄编队（基础内置）合法
        assertNull(FormationRules.validate(formation("基础编队·一盾兵（无英雄）"), cat));
    }

    @Test
    public void validate_captainMustBeSelected() throws Exception {
        FormationModel f = formation("x", 50001L);
        f.captainHeroId = 50002L;
        assertEquals("队长必须是当前编队已选择的英雄", FormationRules.validate(f, catalog()));
        f.captainHeroId = 50001L;
        assertNull(FormationRules.validate(f, catalog()));
    }

    @Test
    public void validate_ratioRules() throws Exception {
        FormationCatalogStore cat = catalog();
        FormationModel f = formation("x", 50001L);
        f.allocationMode = FormationModel.MODE_PERCENTAGE;
        f.infantryRatio = 0;
        f.lancerRatio = 0;
        f.marksmanRatio = 0;
        assertEquals("比例合计必须大于 0%", FormationRules.validate(f, cat));
        f.infantryRatio = 60;
        f.lancerRatio = 30;
        f.marksmanRatio = 30;
        assertEquals("比例合计不能超过 100%", FormationRules.validate(f, cat));
        f.infantryRatio = 40;
        f.lancerRatio = 30;
        f.marksmanRatio = 30;   // 70 ≤ 100 合法（网页 P1）
        assertNull(FormationRules.validate(f, cat));
        f.infantryRatio = -10;
        f.lancerRatio = 10;
        f.marksmanRatio = 0;
        assertEquals(FormationRules.MSG_RATIO_GT_ZERO, FormationRules.validate(f, cat));
    }

    @Test
    public void validate_allocationModeRequired() throws Exception {
        FormationModel f = formation("x", 50001L);
        f.allocationMode = "";
        assertEquals("请选择兵量设置方式", FormationRules.validate(f, catalog()));
        f.allocationMode = "UNKNOWN";
        assertEquals("请选择兵量设置方式", FormationRules.validate(f, catalog()));
        f.allocationMode = FormationModel.MODE_MANUAL;
        assertNull(FormationRules.validate(f, catalog()));
    }

    @Test
    public void validate_manualOverCapacity() throws Exception {
        FormationCatalogStore cat = catalog();
        FormationModel f = formation("x", 50001L);   // 容量 154460
        f.infantry = 200000;
        assertEquals("兵力超过当前档位上限", FormationRules.validate(f, cat));
        f.infantry = 100000;
        assertNull(FormationRules.validate(f, cat));
        // 目录未同步：不拦（容量未知）
        FormationModel g = formation("x", 50001L);
        g.infantry = 999999;
        assertNull(FormationRules.validate(g, FormationCatalogStore.empty()));
    }

    @Test
    public void availabilityWarning_countsAwayHeroes() throws Exception {
        FormationCatalogStore cat = catalog();
        FormationModel ok = formation("x", 50001L, 50002L);
        assertNull(FormationRules.availabilityWarning(ok, cat));
        FormationModel away = formation("x", 50001L, 50003L);
        assertEquals("有 1 名英雄未归队，出征前请确认",
                FormationRules.availabilityWarning(away, cat));
        assertEquals(1, FormationRules.markAvailability(away, cat));
        assertFalse(away.allHeroesAvailable);
        assertEquals(Arrays.asList(50003L), away.unavailableHeroIds);
        assertNull("目录缺失不提示", FormationRules.availabilityWarning(away,
                FormationCatalogStore.empty()));
    }

    // ==================== 跨队列英雄冲突（网页 §4.2 S3 / §4.6） ====================

    @Test
    public void heroConflict_detectsSharedHero() {
        FormationModel a = formation("甲队", 50001L, 50002L);
        a.id = 1;
        FormationModel b = formation("乙队", 50002L, 50003L);
        b.id = 2;
        FormationModel c = formation("丙队", 50004L);
        c.id = 3;
        List<FormationModel> lib = Arrays.asList(a, b, c);
        assertNull("丙队(50004) 与甲队无共用英雄",
                FormationRules.heroConflict(lib, new long[]{3}, 1));
        assertNotNull(FormationRules.heroConflict(lib, new long[]{1}, 2));
        assertTrue(FormationRules.heroConflict(lib, new long[]{1}, 2).contains("英雄冲突"));
        assertEquals(50002L, FormationRules.heroConflictHeroId(lib, new long[]{1}, 2));
        assertEquals(-1L, FormationRules.heroConflictHeroId(lib, new long[]{1}, 3));
        // 自身在绑定列表里不算冲突
        assertNull(FormationRules.heroConflict(lib, new long[]{1, 3}, 1));
        // 候选不存在/无英雄 → 无冲突
        assertNull(FormationRules.heroConflict(lib, new long[]{1}, 99));
        assertNull(FormationRules.heroConflict(lib, new long[]{1}, 0));
    }

    // ==================== 职业映射 / 工具 ====================

    @Test
    public void professionMapping() throws Exception {
        FormationCatalogStore cat = catalog();
        assertEquals(1, FormationRules.professionOf(cat.hero(50001L)));
        assertEquals(2, FormationRules.professionOf(cat.hero(50002L)));
        assertEquals(3, FormationRules.professionOf(cat.hero(50003L)));
        FormationStore.Hero unknown = new FormationStore.Hero();
        assertEquals(0, FormationRules.professionOf(unknown));
        assertEquals(0, FormationRules.professionOf(null));
        assertEquals(1, FormationRules.professionTypeOf("盾兵"));
        assertEquals(2, FormationRules.professionTypeOf("矛兵"));
        assertEquals(2, FormationRules.professionTypeOf("长枪兵"));
        assertEquals(3, FormationRules.professionTypeOf("射手"));
        assertEquals(3, FormationRules.professionTypeOf("弓兵"));
        assertEquals(0, FormationRules.professionTypeOf("法师"));
        assertEquals("盾兵", FormationRules.professionName(1));
        assertEquals("", FormationRules.professionName(9));
        // 仅中文职业名（无 professionType）也能识别
        FormationStore.Hero byName = new FormationStore.Hero();
        byName.profession = "射手";
        assertEquals(3, FormationRules.professionOf(byName));
    }

    @Test
    public void ratioInRange_andFindById() {
        assertTrue(FormationRules.ratioInRange(40, 30, 30));
        assertTrue(FormationRules.ratioInRange(50, 20, 30));
        assertTrue("和=70 合法（>0 且 ≤100）", FormationRules.ratioInRange(30, 20, 20));
        assertFalse(FormationRules.ratioInRange(0, 0, 0));
        assertFalse(FormationRules.ratioInRange(60, 30, 30));
        assertFalse(FormationRules.ratioInRange(-10, 50, 50));
        assertFalse(FormationRules.ratioInRange(101, 0, 0));
        FormationModel f = formation("x");
        f.id = 9;
        assertEquals(f, FormationRules.findById(Arrays.asList(f), 9));
        assertNull(FormationRules.findById(Arrays.asList(f), 8));
        assertNull(FormationRules.findById(null, 1));
    }
}
