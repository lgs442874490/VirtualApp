package com.carlos.vaultserver.formation;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T3（v2 契约 §3.3/§5）编队库解析单测：新字段（captain/allocationMode/source/templateKey/
 * builtIn/version/updatedAt/teamSize/automaticSettings）、GAME id&lt;0 只读、别名/字符串英雄、
 * 缺省推导与"旧 App 零变化"。
 */
public class FormationV2LibraryTest {

    /** 契约 §5 完整 v2 样例（App → 服务端 formation_library）。 */
    private static final String V2_JSON = "{\"version\":2,"
            + "\"automatic_settings\":{\"infantry_ratio\":50,\"lancer_ratio\":20,\"marksman_ratio\":30},"
            + "\"formations\":[{"
            + "\"id\":1,\"name\":\"采集木\",\"source\":\"CUSTOM\",\"template_key\":\"GATHER_WOOD\","
            + "\"built_in\":true,\"hero_ids\":[50000],\"captain_hero_id\":50000,"
            + "\"allocation_mode\":\"PERCENTAGE\","
            + "\"soldiers\":{\"infantry\":15077,\"lancer\":15077,\"marksman\":120616},"
            + "\"ratio\":{\"infantry\":10,\"lancer\":10,\"marksman\":80},\"total\":150770,"
            + "\"version\":3,\"updated_at\":1792863932863}]}";

    @Test
    public void parseV2_contractSample_allNewFields() {
        FormationLibrary lib = FormationLibrary.parse(V2_JSON);
        assertEquals(1, lib.size());
        assertEquals(3, lib.formations.get(0).teamSize);
        FormationSpec f = lib.byId(1L);
        assertNotNull(f);
        assertEquals("采集木", f.name);
        assertEquals(List.of(50000L), f.heroIds);
        assertEquals(Long.valueOf(50000L), f.captainHeroId);
        assertEquals(FormationSpec.MODE_PERCENTAGE, f.allocationMode);
        assertEquals(FormationSpec.MODE_PERCENTAGE, f.effectiveAllocationMode());
        assertEquals(FormationSpec.SOURCE_CUSTOM, f.source);
        assertEquals("GATHER_WOOD", f.templateKey);
        assertTrue(f.builtIn);
        assertTrue(f.isBuiltIn());
        assertEquals(3L, f.version);
        assertEquals(1792863932863L, f.updatedAt);
        assertEquals(3, f.teamSize);
        assertTrue(f.ratioExplicit);
        assertTrue(f.usable());
        assertEquals("契约 §5 样例：盾/矛/弓", 15077L, f.infantry);
        assertEquals(120616L, f.marksman);
        assertEquals(150770L, f.soldiersTotal());
    }

    @Test
    public void parseV2_automaticSettings_defaultAndPresent() {
        FormationLibrary lib = FormationLibrary.parse(V2_JSON);
        assertTrue(lib.automaticSettingsPresent);
        int[] a = lib.automaticSettings();
        assertEquals(50, a[0]);
        assertEquals(20, a[1]);
        assertEquals(30, a[2]);
        assertTrue(lib.describe().contains("auto=50/20/30"));

        // 未配置 → 默认 50/20/30 且 present=false（Resolver 不据此覆盖 40:30:30）
        FormationLibrary plain = FormationLibrary.parse("{\"formations\":[]}");
        assertFalse(plain.automaticSettingsPresent);
        int[] d = plain.automaticSettings();
        assertEquals(50, d[0]);
        assertEquals(20, d[1]);
        assertEquals(30, d[2]);

        // 和≠100 / 负数 / 缺键 → 回落默认（不静默放大）
        FormationLibrary bad = FormationLibrary.parse("{\"automatic_settings\":"
                + "{\"infantry_ratio\":70,\"lancer_ratio\":70,\"marksman_ratio\":70},"
                + "\"formations\":[]}");
        int[] b = bad.automaticSettings();
        assertEquals(50, b[0]);
        assertEquals(20, b[1]);
        assertEquals(30, b[2]);
    }

    @Test
    public void parseOldAppJson_defaultsInferred_zeroBehaviorChange() {
        // 旧 App 只写 hero_ids/soldiers/ratio/total（契约 §3.3：全部缺省推导）
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":7,"
                + "\"name\":\"旧队\",\"hero_ids\":[50041,50039],"
                + "\"soldiers\":{\"infantry\":20000,\"lancer\":1549,\"marksman\":28451},"
                + "\"ratio\":{\"infantry\":40,\"lancer\":30,\"marksman\":30},\"total\":50000}]}");
        FormationSpec f = lib.byId(7L);
        assertNotNull(f);
        assertEquals("缺省队长=heroIds[0]", Long.valueOf(50041L), f.captainHeroId);
        assertNull("缺省 allocationMode=null（按兵量推断）", f.allocationMode);
        assertEquals("有显式兵量 → MANUAL", FormationSpec.MODE_MANUAL, f.effectiveAllocationMode());
        assertEquals(FormationSpec.SOURCE_CUSTOM, f.source);
        assertNull(f.templateKey);
        assertFalse(f.builtIn);
        assertEquals("缺省 version=1", 1L, f.version);
        assertEquals(0L, f.updatedAt);
        assertEquals(3, f.teamSize);
        assertTrue(f.hasExplicitSoldiers());
        assertTrue(f.ratioExplicit);
    }

    @Test
    public void parseOldAppJson_noSoldiers_effectivePercentage() {
        FormationSpec f = FormationLibrary.parse("{\"formations\":[{\"id\":8,"
                + "\"hero_ids\":[50022],\"ratio\":{\"infantry\":1,\"lancer\":1,\"marksman\":1}}]}")
                .byId(8L);
        assertNotNull(f);
        assertFalse(f.hasExplicitSoldiers());
        assertEquals("无显式兵量 → PERCENTAGE", FormationSpec.MODE_PERCENTAGE,
                f.effectiveAllocationMode());
        assertTrue(f.ratioExplicit);
    }

    @Test
    public void parseAliases_commaHeroString_soldiersRatioAliases_camelCase() {
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":[{\"id\":2,"
                + "\"name\":\"别名队\",\"heroIds\":\"50041, 50039\","
                + "\"soldiers\":{\"shield\":10000,\"spear\":5000,\"bow\":5000},"
                + "\"ratio\":{\"shield\":50,\"spear\":25,\"bow\":25},"
                + "\"captainHeroId\":50039,\"allocationMode\":\"manual\","
                + "\"templateKey\":\"WILD\",\"builtIn\":true,\"updatedAt\":99}]}");
        FormationSpec f = lib.byId(2L);
        assertNotNull(f);
        assertEquals("逗号字符串英雄列表兼容", List.of(50041L, 50039L), f.heroIds);
        assertEquals(10000L, f.infantry);
        assertEquals(5000L, f.lancer);
        assertEquals(50L, f.ratioOf("infantry"));
        assertEquals(Long.valueOf(50039L), f.captainHeroId);
        assertEquals(FormationSpec.MODE_MANUAL, f.allocationMode);
        assertEquals("WILD", f.templateKey);
        assertTrue(f.builtIn);
        assertEquals(99L, f.updatedAt);
    }

    @Test
    public void gameFormation_negativeId_parsedReadOnly() {
        FormationLibrary lib = FormationLibrary.parse("{\"formations\":["
                + "{\"id\":-5,\"name\":\"游戏队\",\"source\":\"GAME\",\"hero_ids\":[50041],"
                + "\"soldiers\":{\"infantry\":100}},"
                + "{\"id\":0,\"name\":\"未保存\",\"hero_ids\":[50041]},"
                + "{\"name\":\"缺 id\",\"hero_ids\":[50041]}]}");
        assertEquals("id=0/缺失丢弃，id<0 保留（GAME 只读镜像）", 1, lib.size());
        FormationSpec game = lib.byId(-5L);
        assertNotNull(game);
        assertTrue(game.isGame());
        assertEquals(FormationSpec.SOURCE_GAME, game.source);
        assertFalse("GAME 编队不可用于 ROLE 绑定", game.usable());
        assertTrue(lib.ids().contains(-5L));
        // id<0 但未写 source → 仍推断 GAME
        FormationLibrary lib2 = FormationLibrary.parse("{\"formations\":[{\"id\":-9,"
                + "\"hero_ids\":[50041]}]}");
        assertTrue(lib2.byId(-9L).isGame());
        assertEquals(FormationSpec.SOURCE_GAME, lib2.byId(-9L).source);
    }

    @Test
    public void captainInvalid_fallsBackToFirstHero_orNull() {
        FormationSpec bad = FormationLibrary.parse("{\"formations\":[{\"id\":3,"
                + "\"hero_ids\":[50041,50039],\"captain_hero_id\":99999}]}").byId(3L);
        assertEquals("队长不在 heroIds → 回落 heroIds[0]", Long.valueOf(50041L),
                bad.captainHeroId);
        assertEquals(Long.valueOf(50041L), bad.effectiveCaptain());
        assertEquals("队长合法 → 生效队长", Long.valueOf(50039L),
                FormationLibrary.parse("{\"formations\":[{\"id\":4,\"hero_ids\":[50041,50039],"
                        + "\"captain_hero_id\":50039}]}").byId(4L).effectiveCaptain());
        assertNull("无英雄 → 队长 null",
                FormationLibrary.parse("{\"formations\":[{\"id\":5,\"hero_ids\":[]}]}")
                        .byId(5L).effectiveCaptain());
    }

    @Test
    public void builtIn_derivedFromTemplateKey_whenExplicitMissing() {
        FormationSpec f = FormationLibrary.parse("{\"formations\":[{\"id\":57,"
                + "\"hero_ids\":[50041],\"template_key\":\"BEHEMOTH\"}]}").byId(57L);
        assertTrue("网页版 D1：builtIn = templateKey 非空", f.builtIn);
        assertTrue(f.isBuiltIn());
        FormationSpec plain = FormationLibrary.parse("{\"formations\":[{\"id\":58,"
                + "\"hero_ids\":[50041]}]}").byId(58L);
        assertFalse(plain.builtIn);
        assertFalse(plain.isBuiltIn());
    }

    @Test
    public void allocationMode_invalidTreatedAsUnset() {
        FormationSpec f = FormationLibrary.parse("{\"formations\":[{\"id\":6,"
                + "\"hero_ids\":[50041],\"soldiers\":{\"infantry\":1},"
                + "\"allocation_mode\":\"BOGUS\"}]}").byId(6L);
        assertNull(f.allocationMode);
        assertEquals(FormationSpec.MODE_MANUAL, f.effectiveAllocationMode());
    }

    @Test
    public void versionZeroOrNegative_coercedToOne_andUnknownFieldsIgnored() {
        FormationSpec f = new FormationLibrary().byId(1L);
        assertNull(f);
        FormationSpec s = FormationLibrary.parse("{\"formations\":[{\"id\":9,"
                + "\"version\":0,\"updated_at\":-5,\"hero_ids\":[50041],"
                + "\"future_field\":\"x\"}]}").byId(9L);
        assertNotNull(s);
        assertEquals("version<=0 → 1", 1L, s.version);
        assertEquals("updated_at 负数 → 0", 0L, s.updatedAt);
    }

    @Test
    public void parseJsonObjectOverload_matchesStringParse() {
        JSONObject root = new JSONObject(V2_JSON);
        FormationLibrary fromObject = FormationLibrary.parse(root);
        FormationLibrary fromString = FormationLibrary.parse(V2_JSON);
        assertEquals(fromString.size(), fromObject.size());
        assertEquals(fromString.formations.get(0).captainHeroId,
                fromObject.formations.get(0).captainHeroId);
        assertTrue(fromObject.automaticSettingsPresent);
        assertTrue("空/非法 JSON → 空库", FormationLibrary.parse((JSONObject) null).isEmpty());
    }
}
