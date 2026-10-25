package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.FormationStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BearFormationTemplates 单测（契约 §2.6 / 网页 §2.4 / §4.8）：
 * 30 套模板常量与线上 JSON 逐套对齐、5:5:90 生成 7 支命名编队、缺英雄判定、可用性标注。
 */
public class BearFormationTemplatesTest {

    // ==================== 模板数据 ====================

    @Test
    public void all_templates30Across14Generations() {
        List<BearFormationTemplates.Template> all = BearFormationTemplates.all();
        assertEquals("30 套模板", 30, all.size());
        Set<Integer> generations = new HashSet<Integer>();
        int spender = 0, mid = 0, free = 0, preview = 0;
        for (BearFormationTemplates.Template t : all) {
            generations.add(t.generation);
            if ("大氪".equals(t.tier)) spender++;
            else if ("微氪".equals(t.tier)) mid++;
            else if ("零氪".equals(t.tier)) free++;
            else if ("前瞻".equals(t.tier)) preview++;
            assertTrue("id 规则 gNN-tier: " + t.id, t.id.matches("g\\d\\d-(spender|mid|free|preview)"));
            assertEquals("每套 6 组上车队", BearFormationTemplates.JOIN_COUNT, t.joinHeroes.size());
            assertFalse("开车队 1..3 人", t.launchHeroes.isEmpty() || t.launchHeroes.size() > 3);
            for (List<BearFormationTemplates.HeroRef> join : t.joinHeroes) {
                assertFalse("上车队 1..3 人", join.isEmpty() || join.size() > 3);
            }
        }
        assertEquals("14 代", 14, generations.size());
        assertEquals(11, spender);
        assertEquals(5, mid);
        assertEquals(11, free);
        assertEquals(3, preview);
        assertEquals("g06+ 无微氪", 0, countTier(all, "微氪", 6));
        assertEquals("g12+ 为前瞻", 3, countTierGte(all, "前瞻", 12));
    }

    private static int countTier(List<BearFormationTemplates.Template> all, String tier, int gen) {
        int n = 0;
        for (BearFormationTemplates.Template t : all) {
            if (t.generation == gen && tier.equals(t.tier)) n++;
        }
        return n;
    }

    private static int countTierGte(List<BearFormationTemplates.Template> all, String tier, int gen) {
        int n = 0;
        for (BearFormationTemplates.Template t : all) {
            if (t.generation >= gen && tier.equals(t.tier)) n++;
        }
        return n;
    }

    @Test
    public void builtInConstant_matchesLiveJson() throws Exception {
        String raw = FormationCatalogStoreTest.resource("formation/bear_templates.json");
        List<BearFormationTemplates.Template> live = BearFormationTemplates.parse(raw);
        List<BearFormationTemplates.Template> built = BearFormationTemplates.all();
        assertEquals("解析线上 JSON 得 30 套", 30, live.size());
        assertEquals(live.size(), built.size());
        for (int i = 0; i < live.size(); i++) {
            BearFormationTemplates.Template a = live.get(i);
            BearFormationTemplates.Template b = built.get(i);
            assertEquals("id 对齐", a.id, b.id);
            assertEquals("generation", a.generation, b.generation);
            assertEquals("tier", a.tier, b.tier);
            assertEquals("title", a.title, b.title);
            assertEquals("开车队英雄 " + a.id, a.launchHeroIds(), b.launchHeroIds());
            for (int n = 1; n <= BearFormationTemplates.JOIN_COUNT; n++) {
                assertEquals("上车队" + n + "英雄 " + a.id, a.joinHeroIds(n), b.joinHeroIds(n));
            }
            // 英雄名字/职业转录一致
            assertEquals(a.launchHeroes.size(), b.launchHeroes.size());
            for (int k = 0; k < a.launchHeroes.size(); k++) {
                assertEquals(a.launchHeroes.get(k).name, b.launchHeroes.get(k).name);
                assertEquals(a.launchHeroes.get(k).profession,
                        b.launchHeroes.get(k).profession);
                assertEquals(a.launchHeroes.get(k).professionType,
                        b.launchHeroes.get(k).professionType);
            }
        }
    }

    @Test
    public void parse_tolerantForBadInput() {
        assertTrue(BearFormationTemplates.parse(null).isEmpty());
        assertTrue(BearFormationTemplates.parse("").isEmpty());
        assertTrue(BearFormationTemplates.parse("{bad").isEmpty());
        assertTrue(BearFormationTemplates.parse("{\"x\":1}").isEmpty());
        // 单条脏数据（缺 id / 缺英雄）不崩
        List<BearFormationTemplates.Template> one = BearFormationTemplates.parse(
                "[{\"id\":\"g99-x\",\"generation\":99,\"title\":\"测试\"},{\"generation\":1}]");
        assertEquals(1, one.size());
        assertEquals("g99-x", one.get(0).id);
        assertEquals("缺 joinHeroes 补齐 6 组空", 6, one.get(0).joinHeroes.size());
        assertTrue(one.get(0).launchHeroes.isEmpty());
    }

    @Test
    public void byId_andNames() {
        assertEquals("g01-spender", BearFormationTemplates.byId("g01-spender").id);
        assertNull(BearFormationTemplates.byId("nope"));
        assertNull(BearFormationTemplates.byId(null));
        BearFormationTemplates.Template g10 = BearFormationTemplates.byId("g10-free");
        assertNotNull(g10);
        assertEquals("十代·零氪", g10.title);
        assertEquals("巨熊·开车队（十代·零氪）", BearFormationTemplates.launchName(g10));
        assertEquals("巨熊·上车队6（十代·零氪）", BearFormationTemplates.joinName(g10, 6));
        assertEquals("巨熊·开车队（十代·零氪）", g10.launchName());
        BearFormationTemplates.Template g14 = BearFormationTemplates.byId("g14-preview");
        assertEquals("巨熊·上车队1（十四代·前瞻（多米尼克版））", g14.joinName(1));
        assertTrue(BearFormationTemplates.isBearName("巨熊·开车队（一代·大氪）"));
        assertFalse(BearFormationTemplates.isBearName("采集队"));
        assertFalse(BearFormationTemplates.isBearName(null));
    }

    @Test
    public void templateHeroHelpers() {
        BearFormationTemplates.Template t = BearFormationTemplates.byId("g01-spender");
        assertEquals(Arrays.asList(50011L, 50012L, 50006L), t.launchHeroIds());
        assertEquals(3, t.joinHeroIds(1).size());
        assertEquals(1, t.joinHeroIds(6).size());
        assertTrue("越界返回空", t.joinHeroIds(0).isEmpty());
        assertTrue(t.joinHeroIds(7).isEmpty());
        assertEquals("去重英雄总数", t.heroCount(), t.allHeroIds().size());
        assertTrue(t.heroCount() > 7 && t.heroCount() <= 21);
    }

    // ==================== 生成 7 支编队（5:5:90） ====================

    @Test
    public void buildFormations_sevenWithBearNames() {
        BearFormationTemplates.Template t = BearFormationTemplates.byId("g01-spender");
        List<FormationModel> out = BearFormationTemplates.buildFormations(t);
        assertEquals("1 开车队 + 6 上车队", 7, out.size());
        assertEquals("巨熊·开车队（一代·大氪）", out.get(0).name);
        for (int n = 1; n <= 6; n++) {
            assertEquals("巨熊·上车队" + n + "（一代·大氪）", out.get(n).name);
            assertTrue("客户端按名字前缀识别巨熊", out.get(n).isBear());
        }
        assertTrue(out.get(0).isBear());
        for (FormationModel f : out) {
            assertEquals("id=0 待保存", 0L, f.id);
            assertEquals(FormationModel.MODE_PERCENTAGE, f.allocationMode);
            assertEquals(5, f.infantryRatio);
            assertEquals(5, f.lancerRatio);
            assertEquals(90, f.marksmanRatio);
            assertEquals("兵量 0（运行时按比例换算）", 0L, f.soldiersTotal());
            assertEquals(0L, f.totalSoldiers);
            assertEquals("CUSTOM", f.source);
            assertFalse(f.builtIn);
            assertNull(f.templateKey);
            assertEquals("队长=首位", f.heroIds.get(0), f.effectiveCaptainId());
        }
        assertEquals(Arrays.asList(50011L, 50012L, 50006L), out.get(0).heroIds);
        assertEquals(Arrays.asList(50007L, 50000L, 50003L), out.get(1).heroIds);
        assertEquals(Arrays.asList(50008L), out.get(6).heroIds);
    }

    @Test
    public void buildFormations_marksAvailabilityFromCatalog() {
        BearFormationTemplates.Template t = BearFormationTemplates.byId("g01-spender");
        List<FormationStore.Hero> heroes = new ArrayList<FormationStore.Hero>();
        heroes.add(hero(50011L, false));
        heroes.add(hero(50012L, true));
        heroes.add(hero(50006L, true));
        List<FormationModel> out = BearFormationTemplates.buildFormations(t, heroes);
        assertEquals(7, out.size());
        assertFalse("开车队含不在城英雄", out.get(0).allHeroesAvailable);
        assertEquals(Arrays.asList(50011L), out.get(0).unavailableHeroIds);
        // 上车1 英雄均不在目录 → 视为不可用（保守）
        assertFalse(out.get(1).allHeroesAvailable);
        // 全部在城场景
        List<FormationStore.Hero> allIn = new ArrayList<FormationStore.Hero>();
        for (Long id : Arrays.asList(50011L, 50012L, 50006L)) allIn.add(hero(id, true));
        FormationModel launch = BearFormationTemplates.buildFormations(t, allIn).get(0);
        assertTrue(launch.allHeroesAvailable);
        assertTrue(launch.unavailableHeroIds.isEmpty());
    }

    private static FormationStore.Hero hero(long id, boolean available) {
        FormationStore.Hero h = new FormationStore.Hero();
        h.id = id;
        h.available = available;
        return h;
    }

    @Test
    public void missingHeroesAndApplicability() {
        BearFormationTemplates.Template t = BearFormationTemplates.byId("g01-spender");
        Set<Long> owned = new HashSet<Long>(t.allHeroIds());
        assertTrue(BearFormationTemplates.applicable(t, owned));
        assertEquals(0, BearFormationTemplates.missingCount(t, owned));
        owned.remove(50011L);
        owned.remove(50008L);
        assertFalse("缺英雄不可应用（只求 owned，不要求在城）",
                BearFormationTemplates.applicable(t, owned));
        assertEquals(2, BearFormationTemplates.missingCount(t, owned));
        assertEquals(Arrays.asList(50011L, 50008L),
                BearFormationTemplates.missingHeroIds(t, owned));
        assertEquals("赫罗尼莫、吉娜",
                BearFormationTemplates.heroNames(
                        BearFormationTemplates.missingHeroIds(t, owned), t));
        // owned 为空 → 全部缺失
        assertEquals(t.heroCount(),
                BearFormationTemplates.missingCount(t, new ArrayList<Long>()));
        assertFalse(BearFormationTemplates.applicable(null, owned));
        assertTrue(BearFormationTemplates.missingHeroIds(null, owned).isEmpty());
    }

    /**
     * T6 提供的 assets 文件（app/src/main/assets/formation/bear_templates.json）必须与内置常量一致；
     * assets 尚未落地时（T6 未完成）跳过文件校验，内置常量兜底仍为 30 套。
     */
    @Test
    public void assetsFile_matchesBuiltInConstant() throws Exception {
        java.io.File file = new java.io.File("src/main/assets/formation/bear_templates.json");
        if (!file.isFile()) {
            file = new java.io.File("app/src/main/assets/formation/bear_templates.json");
        }
        if (!file.isFile()) {
            assertEquals("assets 未就绪时内置常量兜底", 30,
                    BearFormationTemplates.all().size());
            return;
        }
        String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
        List<BearFormationTemplates.Template> live = BearFormationTemplates.parse(raw);
        assertEquals("assets 30 套", 30, live.size());
        List<BearFormationTemplates.Template> built = BearFormationTemplates.all();
        for (int i = 0; i < live.size(); i++) {
            assertEquals(live.get(i).id, built.get(i).id);
            assertEquals(live.get(i).title, built.get(i).title);
            assertEquals(live.get(i).launchHeroIds(), built.get(i).launchHeroIds());
            for (int n = 1; n <= BearFormationTemplates.JOIN_COUNT; n++) {
                assertEquals(live.get(i).joinHeroIds(n), built.get(i).joinHeroIds(n));
            }
        }
    }
}
