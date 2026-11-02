package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FormationBuiltIns 单测（契约 §2.6）：8 个内置定义 / 懒创建条件 / 幂等 / 保护规则。
 */
public class FormationBuiltInsTest {

    /** 已同步 + 有英雄目录（盾/矛/弓各一，战力不同）。 */
    static FormationCatalogStore readyCatalog() throws Exception {
        return FormationCatalogStore.parse("{\"configured\":true,"
                + "\"heroes\":["
                + "{\"id\":50001,\"name\":\"盾A\",\"professionType\":1,\"power\":100,"
                + "\"troopCapacity\":13470,\"available\":true},"
                + "{\"id\":50004,\"name\":\"盾B\",\"professionType\":1,\"power\":999,"
                + "\"troopCapacity\":13470,\"available\":true},"
                + "{\"id\":50002,\"name\":\"矛A\",\"professionType\":2,\"power\":500,"
                + "\"troopCapacity\":11860,\"available\":true},"
                + "{\"id\":50003,\"name\":\"弓A\",\"professionType\":3,\"power\":300,"
                + "\"troopCapacity\":13470,\"available\":false}],"
                + "\"marchCapacity\":{\"initialized\":true,\"fixedCapacity\":140990}}");
    }

    @Test
    public void base_definitions() {
        List<FormationModel> base = FormationBuiltIns.base();
        assertEquals(2, base.size());
        FormationModel a = base.get(0);
        assertEquals(FormationBuiltIns.ID_ONE_INFANTRY, a.id);
        assertEquals(9000000001L, a.id);
        assertEquals("基础编队·一盾兵（无英雄）", a.name);
        assertEquals("ONE_INFANTRY", a.templateKey);
        assertTrue(a.builtIn);
        assertEquals("CUSTOM", a.source);
        assertEquals(1L, a.infantry);
        assertEquals(0L, a.lancer);
        assertEquals(0L, a.marksman);
        assertTrue(a.heroIds.isEmpty());
        FormationModel b = base.get(1);
        assertEquals(9000000002L, b.id);
        assertEquals("基础编队·任意英雄一兵", b.name);
        assertEquals("RANDOM_HERO_ONE_SOLDIER", b.templateKey);
        assertEquals(1L, b.infantry);
    }

    @Test
    public void all_eightDefinitions() {
        List<FormationModel> all = FormationBuiltIns.all();
        assertEquals(8, all.size());
        long[] expectIds = {FormationBuiltIns.ID_ONE_INFANTRY,
                FormationBuiltIns.ID_ANY_HERO_ONE_SOLDIER, 57, 55, 54, 56, 53, 58};
        String[] expectNames = {"基础编队·一盾兵（无英雄）", "基础编队·任意英雄一兵",
                "基础编队·巨兽", "基础编队·煤矿采集", "基础编队·铁矿采集",
                "基础编队·生肉采集", "基础编队·木材采集", "基础编队·打野"};
        String[] expectKeys = {"ONE_INFANTRY", "RANDOM_HERO_ONE_SOLDIER", "BEHEMOTH",
                "GATHER_COAL", "GATHER_IRON", "GATHER_MEAT", "GATHER_WOOD", "WILD"};
        for (int i = 0; i < 8; i++) {
            assertEquals(expectIds[i], all.get(i).id);
            assertEquals(expectNames[i], all.get(i).name);
            assertEquals(expectKeys[i], all.get(i).templateKey);
            assertTrue(all.get(i).builtIn);
        }
    }

    @Test
    public void ensure_withoutCatalog_createsOnlyBase() {
        List<FormationModel> lib = new ArrayList<FormationModel>();
        assertEquals("未同步只创建 2 个基础内置", 2,
                FormationBuiltIns.ensure(lib, FormationCatalogStore.empty()));
        assertEquals(2, lib.size());
        assertEquals(FormationBuiltIns.ID_ONE_INFANTRY, lib.get(0).id);
        // 未同步目录对象（configured=false）同样只 2 个
        List<FormationModel> lib2 = new ArrayList<FormationModel>();
        assertEquals(2, FormationBuiltIns.ensure(lib2, null));
    }

    @Test
    public void ensure_readyCreatesAllEight_withBestHeroes() throws Exception {
        List<FormationModel> lib = new ArrayList<FormationModel>();
        // 契约 2026-11-02 §3：目录就绪时 ensure 末尾还会懒创建 19 套预设；数量以实际定义数计
        int expectPresets = PresetFormations.ensure(new ArrayList<FormationModel>(), readyCatalog());
        assertEquals(8 + expectPresets, FormationBuiltIns.ensure(lib, readyCatalog()));
        assertEquals(8 + expectPresets, lib.size());
        for (FormationModel f : lib) {
            if (PresetFormations.isPreset(f.id)) continue;   // 预设英雄规则单测见 PresetFormationsTest
            if (f.templateKey != null && !"ONE_INFANTRY".equals(f.templateKey)
                    && !"RANDOM_HERO_ONE_SOLDIER".equals(f.templateKey)) {
                assertEquals("懒创建英雄=各职业最高战力（盾B/矛A/弓A）",
                        Arrays.asList(50004L, 50002L, 50003L), f.heroIds);
                assertEquals("队长=首位", Long.valueOf(50004L), f.captainHeroId);
                assertEquals("兵量 1/0/0", 1L, f.infantry);
                assertFalse("弓A 不在城", f.allHeroesAvailable);
                assertEquals(Arrays.asList(50003L), f.unavailableHeroIds);
            }
        }
        // 幂等：第二次不新增、不重复
        assertEquals(0, FormationBuiltIns.ensure(lib, readyCatalog()));
        assertEquals(8 + expectPresets, lib.size());
    }

    @Test
    public void ensure_isIdempotentAndKeepsUserEdits() throws Exception {
        List<FormationModel> lib = new ArrayList<FormationModel>();
        FormationBuiltIns.ensure(lib, readyCatalog());
        FormationModel behemoth = null;
        for (FormationModel f : lib) {
            if (f.id == FormationBuiltIns.ID_BEHEMOTH) behemoth = f;
        }
        assertNotNull(behemoth);
        behemoth.name = "我改过的巨兽队";
        behemoth.infantry = 123;
        assertEquals(0, FormationBuiltIns.ensure(lib, readyCatalog()));
        assertEquals("用户改名/兵量不被覆盖", "我改过的巨兽队", behemoth.name);
        assertEquals(123L, behemoth.infantry);
    }

    @Test
    public void ensure_fillsHeroesForEarlyEmptyLazy() throws Exception {
        List<FormationModel> lib = new ArrayList<FormationModel>();
        FormationBuiltIns.ensure(lib, FormationCatalogStore.empty());   // 只有 2 基础
        // 模拟早期异常数据：懒创建项已存在但英雄为空
        FormationModel early = FormationBuiltIns.byId(FormationBuiltIns.ID_MEAT).copy();
        lib.add(early);
        int expectPresets = PresetFormations.ensure(new ArrayList<FormationModel>(), readyCatalog());
        assertEquals("其余 5 个懒创建补齐（56 已存在）+ 预设",
                5 + expectPresets, FormationBuiltIns.ensure(lib, readyCatalog()));
        assertEquals(8 + expectPresets, lib.size());
        assertEquals("已存在项补英雄", Arrays.asList(50004L, 50002L, 50003L), early.heroIds);
        assertEquals(Long.valueOf(50004L), early.captainHeroId);
    }

    @Test
    public void protectedAndBuiltInFlags() {
        assertTrue(FormationBuiltIns.isProtected(9000000001L));
        assertTrue(FormationBuiltIns.isProtected(9000000002L));
        assertFalse(FormationBuiltIns.isProtected(57L));
        assertFalse(FormationBuiltIns.isProtected(1L));
        assertTrue(FormationBuiltIns.isBuiltIn(53L));
        assertTrue(FormationBuiltIns.isBuiltIn(58L));
        assertFalse(FormationBuiltIns.isBuiltIn(1L));
        assertNull(FormationBuiltIns.byId(1L));
        assertNull(FormationBuiltIns.byTemplateKey("NOPE"));
        assertEquals(FormationBuiltIns.ID_WILD,
                FormationBuiltIns.byTemplateKey("wild").id);
        assertEquals(FormationBuiltIns.ID_ONE_INFANTRY,
                FormationBuiltIns.byTemplateKey("ONE_INFANTRY").id);
    }

    @Test
    public void gatherResourceMapping() {
        assertEquals(FormationBuiltIns.ID_MEAT,
                FormationBuiltIns.idForGatherResource("MEAT"));
        assertEquals(FormationBuiltIns.ID_WOOD,
                FormationBuiltIns.idForGatherResource("wood"));
        assertEquals(FormationBuiltIns.ID_COAL,
                FormationBuiltIns.idForGatherResource("COAL"));
        assertEquals(FormationBuiltIns.ID_IRON,
                FormationBuiltIns.idForGatherResource("IRON"));
        assertEquals(0L, FormationBuiltIns.idForGatherResource("STONE"));
        assertEquals(0L, FormationBuiltIns.idForGatherResource(null));
    }
}
