package com.carlos.vaultserver.formation;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 编队库解析单测（批次10）：{@code formation_library} JSON 的防御式解析/去重/别名/byId。
 * 参考网页版 {@code DailyFormationConfigSupport}（formationIds 去重）与
 * {@code persistence/entity/DailyRoleFormationEntity}（编队保存形状）。
 */
public class FormationLibraryTest {

    private static final String LIB_JSON = "{\"formations\":["
            + "{\"id\":1,\"name\":\"采集队\",\"hero_ids\":[50041,50039],"
            + "\"soldiers\":{\"infantry\":20000,\"lancer\":1549,\"marksman\":28451},"
            + "\"ratio\":{\"infantry\":40,\"lancer\":30,\"marksman\":30},\"total\":50000},"
            + "{\"id\":2,\"name\":\"情报队\",\"hero_ids\":[50022],"
            + "\"soldiers\":{\"shield\":10000,\"spear\":5000,\"bow\":5000},"
            + "\"ratio\":{\"shield\":50,\"spear\":25,\"bow\":25},\"total\":0}]}";

    @Test
    public void parse_validLibrary_twoFormationsWithFields() {
        FormationLibrary lib = FormationLibrary.parse(LIB_JSON);
        assertFalse(lib.isEmpty());
        assertEquals(2, lib.size());
        assertEquals(List.of(1L, 2L), lib.ids());

        FormationSpec f1 = lib.byId(1L);
        assertNotNull(f1);
        assertEquals("采集队", f1.name);
        assertEquals(List.of(50041L, 50039L), f1.heroIds);
        assertEquals(20000L, f1.infantry);
        assertEquals(1549L, f1.lancer);
        assertEquals(28451L, f1.marksman);
        assertEquals(40L, f1.ratioOf("infantry"));
        assertEquals(30L, f1.ratioOf("lancer"));
        assertEquals(50000L, f1.total);
        assertTrue(f1.usable());
        assertTrue(f1.hasExplicitSoldiers());
        assertEquals(50000L, f1.soldiersTotal());

        // 旧别名 shield/spear/bow 兼容
        FormationSpec f2 = lib.byId(2L);
        assertNotNull(f2);
        assertEquals(10000L, f2.infantry);
        assertEquals(5000L, f2.lancer);
        assertEquals(5000L, f2.marksman);
        assertEquals(50L, f2.ratioOf("infantry"));
        assertEquals("total=0 表示按容量自动", 0L, f2.total);
    }

    @Test
    public void parse_duplicateIdKeepsFirst_andSkipsIncomplete() {
        String json = "{\"formations\":["
                + "{\"id\":3,\"name\":\"A\",\"hero_ids\":[1]},"
                + "{\"id\":3,\"name\":\"B\",\"hero_ids\":[2]},"
                + "{\"id\":0,\"name\":\"无 id\",\"hero_ids\":[3]},"
                + "{\"name\":\"缺 id\",\"hero_ids\":[4]},"
                + "{\"id\":4,\"hero_ids\":[]}]}";
        FormationLibrary lib = FormationLibrary.parse(json);
        assertEquals("重复 id 只保留首个；id<=0 跳过", 2, lib.size());
        assertEquals("A", lib.byId(3L).name);
        assertNotNull(lib.byId(4L));
        assertFalse("无英雄编队 usable=false", lib.byId(4L).usable());
    }

    @Test
    public void parse_invalidJson_nullOrEmpty_returnsEmptyLibrary() {
        assertTrue(FormationLibrary.parse((String) null).isEmpty());
        assertTrue(FormationLibrary.parse("").isEmpty());
        assertTrue(FormationLibrary.parse("   ").isEmpty());
        assertTrue(FormationLibrary.parse("not-json").isEmpty());
        assertTrue(FormationLibrary.parse("{\"formations\":[]}").isEmpty());
        assertTrue(FormationLibrary.parse(new JSONObject()).isEmpty());
        assertNull(FormationLibrary.parse("{}").byId(1L));
    }

    @Test
    public void byId_missingReturnsNull_andDescribe() {
        FormationLibrary lib = FormationLibrary.parse(LIB_JSON);
        assertNull("缺失编队 → null（调用方 SKIP，不回退）", lib.byId(99L));
        assertNull("id<=0 → null", lib.byId(0L));
        String d = lib.describe();
        assertTrue(d.contains("size=2"));
        assertTrue(d.contains("1"));
    }

    @Test
    public void formationSpec_defaultsAndRatios() {
        Map<String, Long> def = FormationSpec.defaultRatio();
        assertEquals(40L, (long) def.get("infantry"));
        assertEquals(30L, (long) def.get("lancer"));
        assertEquals(30L, (long) def.get("marksman"));

        Map<String, Long> bear = FormationSpec.bearRatio();
        assertEquals("巨熊默认 1:1:1", 1L, (long) bear.get("infantry"));
        assertEquals(1L, (long) bear.get("lancer"));
        assertEquals(1L, (long) bear.get("marksman"));

        FormationSpec s = new FormationSpec();
        s.ratio = null;
        assertEquals("ratio 为 null 时回落默认", 40L, s.ratioOf("infantry"));
        s.ratio = Map.of("infantry", -5L);
        assertEquals("负数权重按默认", 40L, s.ratioOf("infantry"));
    }
}
