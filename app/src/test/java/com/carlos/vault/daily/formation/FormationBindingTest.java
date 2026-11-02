package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.FormationStore;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FormationBinding 单测（契约 §2.2 / §5 / 网页 §4.6）：绑定数组读写、旧 formation_id 兼容、
 * 队列绑定校验文案（不存在/重复/游戏编队/英雄冲突）、选择器 banner 文案。
 */
public class FormationBindingTest {

    private static FormationCatalogStore catalog() throws Exception {
        return FormationCatalogStore.parse("{\"configured\":true,"
                + "\"marchCapacity\":{\"initialized\":true,\"fixedCapacity\":100000}}");
    }

    private static FormationModel f(long id, String name, long... heroes) {
        FormationModel m = new FormationModel(name, toList(heroes));
        m.id = id;
        return m;
    }

    private static List<Long> toList(long... ids) {
        List<Long> out = new ArrayList<Long>();
        for (long id : ids) out.add(id);
        return out;
    }

    private static List<FormationModel> library() {
        return Arrays.asList(
                f(1, "甲队", 50001L, 50002L),
                f(2, "乙队", 50003L),
                f(3, "丙队", 50002L, 50004L),
                f(PresetFormations.ID_FIRST, "预设·巨兽"));
    }

    // ==================== params 读写 ====================

    @Test
    public void parseIds_prefersArrayThenLegacyId() throws Exception {
        JSONObject p = new JSONObject().put("formation_id", 7)
                .put("formation_ids", new org.json.JSONArray(Arrays.asList(1L, 2L)));
        assertArrayEquals(new long[]{1L, 2L}, FormationBinding.parseIds(p));
        assertArrayEquals("旧字段回落", new long[]{7L},
                FormationBinding.parseIds(new JSONObject().put("formation_id", 7)));
        assertArrayEquals(new long[0], FormationBinding.parseIds(new JSONObject()));
        assertArrayEquals(new long[0], FormationBinding.parseIds(null));
        // 0/负数保留（负数=游戏编队，由校验拦截），0 丢弃
        assertArrayEquals(new long[]{-1L},
                FormationBinding.parseIds(new JSONObject()
                        .put("formation_ids", new org.json.JSONArray(Arrays.asList(0L, -1L)))));
    }

    @Test
    public void applyIds_singleWritesLegacyId_multipleClears() throws Exception {
        JSONObject p = new JSONObject().put("ratio", 1);
        FormationBinding.applyIds(p, new long[]{5});
        assertEquals("ROLE", p.getString("formation_mode"));
        assertEquals("单编队兼容旧 formation_id", 5L, p.getLong("formation_id"));
        assertEquals(1, p.getJSONArray("formation_ids").length());
        assertEquals("其它键保留", 1, p.getInt("ratio"));
        FormationBinding.applyIds(p, new long[]{5, 6});
        assertEquals(2, p.getJSONArray("formation_ids").length());
        assertFalse("多编队移除旧 formation_id", p.has("formation_id"));
        // 0 值过滤
        FormationBinding.applyIds(p, new long[]{0, 8});
        assertEquals(1, p.getJSONArray("formation_ids").length());
        assertEquals(8L, p.getLong("formation_id"));
    }

    @Test
    public void applyAutomatic_clearsReferences() throws Exception {
        JSONObject p = new JSONObject().put("formation_mode", "ROLE")
                .put("formation_id", 3)
                .put("formation_ids", new org.json.JSONArray(Arrays.asList(3L)))
                .put("ratio", new JSONObject().put("infantry", 40));
        FormationBinding.applyAutomatic(p);
        assertEquals("AUTOMATIC", p.getString("formation_mode"));
        assertFalse(p.has("formation_id"));
        assertFalse(p.has("formation_ids"));
        assertTrue("其它配置保留", p.has("ratio"));
    }

    // ==================== 校验（网页 §4.6 文案） ====================

    @Test
    public void validate_automaticAlwaysOk() throws Exception {
        assertNull(FormationBinding.validate(catalog(), library(), "gather", "AUTOMATIC", null));
        assertNull(FormationBinding.validate(null, library(), "gather", "auto", new long[0]));
    }

    @Test
    public void validate_roleMissingSelection() throws Exception {
        assertEquals("采集请选择角色编队",
                FormationBinding.validate(catalog(), library(), "gather", "ROLE", new long[0]));
        assertEquals("采集请选择角色编队",
                FormationBinding.validate(catalog(), library(), "gather", "ROLE", null));
        assertEquals("巨兽请选择角色编队",
                FormationBinding.validate(catalog(), library(), "behemoth", "ROLE", new long[]{0}));
    }

    @Test
    public void validate_roleNonexistentFormation() throws Exception {
        assertEquals("采集引用的角色编队不存在，请重新选择",
                FormationBinding.validate(catalog(), library(), "gather", "ROLE", new long[]{99}));
    }

    @Test
    public void validate_roleGameFormationRejected() throws Exception {
        assertEquals("采集不能引用游戏编队，请先创建副本",
                FormationBinding.validate(catalog(), library(), "gather", "ROLE", new long[]{-1}));
    }

    @Test
    public void validate_roleDuplicateFormation() throws Exception {
        assertEquals("采集不能与同模块其他队列共用角色编队",
                FormationBinding.validate(catalog(), library(), "gather", "ROLE",
                        new long[]{1, 1}));
    }

    @Test
    public void validate_roleHeroConflict() throws Exception {
        String msg = FormationBinding.validate(catalog(), library(), "gather", "ROLE",
                new long[]{1, 3});   // 甲队(50001,50002) 与 丙队(50002,50004) 共用 50002
        assertTrue(msg, msg != null && msg.contains("英雄冲突"));
        assertTrue(msg.contains("甲队"));
        assertTrue(msg.contains("丙队"));
        // 无冲突 → 合法
        assertNull(FormationBinding.validate(catalog(), library(), "gather", "ROLE",
                new long[]{1, 2}));
        // 单编队 → 合法（预设 id 也可引用）
        assertNull(FormationBinding.validate(catalog(), library(), "behemoth", "ROLE",
                new long[]{PresetFormations.ID_FIRST}));
    }

    // ==================== banner / 队列名 ====================

    @Test
    public void selectionBanner_webCopy() {
        assertEquals("请选择一支编队", FormationBinding.selectionBanner(0, 1));
        assertNull("单选已选", FormationBinding.selectionBanner(1, 1));
        assertEquals("请选择 3 支编队", FormationBinding.selectionBanner(0, 3));
        assertEquals("还需要选择 2 支编队", FormationBinding.selectionBanner(1, 3));
        assertNull("多选已选够", FormationBinding.selectionBanner(3, 3));
    }

    @Test
    public void fnLabel_webModuleNames() {
        assertEquals("采集", FormationBinding.fnLabel("gather"));
        assertEquals("情报", FormationBinding.fnLabel("intel"));
        assertEquals("巨兽", FormationBinding.fnLabel("behemoth"));
        assertEquals("巨熊", FormationBinding.fnLabel("bear"));
        assertEquals("狩猎", FormationBinding.fnLabel("hunt"));
        assertEquals("守护盟友", FormationBinding.fnLabel("guardian"));
        assertEquals("英雄的使命", FormationBinding.fnLabel("hero_mission"));
        assertEquals("队列", FormationBinding.fnLabel(null));
        assertEquals("unknown_fn", FormationBinding.fnLabel("unknown_fn"));
    }

    @Test
    public void keyConstantsMatchContract5() {
        assertEquals("formation_mode", FormationBinding.KEY_MODE);
        assertEquals("formation_id", FormationBinding.KEY_ID);
        assertEquals("formation_ids", FormationBinding.KEY_IDS);
        assertEquals(FormationStore.MODE_ROLE, "ROLE");
        assertEquals(FormationStore.MODE_AUTOMATIC, "AUTOMATIC");
    }
}
