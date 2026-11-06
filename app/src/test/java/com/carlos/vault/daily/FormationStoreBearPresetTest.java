package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.daily.formation.PresetFormations;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 「巨熊编队模板」一键应用的参数构造单测（2026-11-06）。
 *
 * <p>回归目标（生产 11-02/11-04 未成功的直接诱因之一）：发起编队必须写在<b>顶层</b>
 * {@code formation_mode/formation_id/formation_ids}，服务器 {@code FormationResolver}
 * 只认顶层键；旧版写嵌套 {@code launch_formation} 会被忽略，导致发车回退游戏内第 1 队。
 */
public class FormationStoreBearPresetTest {

    @Test
    public void writesTopLevelLaunchAndSixJoinSlots() throws Exception {
        JSONObject p = new JSONObject();
        p.put("soldiers", new JSONObject().put("infantry", 999));
        p.put("launch_formation", new JSONObject());
        p.put("template_state", new JSONObject());

        JSONObject out = FormationStore.applyBearPresetParams(p, 4);

        // 发起队：顶层统一编队参数
        assertEquals("ROLE", out.optString("formation_mode"));
        assertEquals(PresetFormations.ID_BEAR_TEAM, out.optLong("formation_id"));
        JSONArray ids = out.optJSONArray("formation_ids");
        assertNotNull(ids);
        assertEquals(1, ids.length());
        assertEquals(PresetFormations.ID_BEAR_TEAM, ids.optLong(0));

        // 上车队 1-6 = 熊上车1-6，全部启用
        JSONArray joins = out.optJSONArray("join_formations");
        assertNotNull(joins);
        assertEquals(PresetFormations.BEAR_TEMPLATE_JOIN_COUNT, joins.length());
        for (int slot = 1; slot <= PresetFormations.BEAR_TEMPLATE_JOIN_COUNT; slot++) {
            JSONObject j = joins.optJSONObject(slot - 1);
            assertNotNull(j);
            assertEquals(slot, j.optInt("id"));
            assertEquals("ROLE", j.optString("mode"));
            assertEquals(PresetFormations.bearTemplateJoinId(slot), j.optLong("formation_id"));
            assertTrue(j.optBoolean("enabled"));
        }

        // 本场上车上限与队列数
        assertEquals(4, out.optInt("join_count"));
        assertEquals(4, out.optInt("queue_count"));

        // 旧键/覆盖清理
        assertFalse("功能级士兵覆盖应清除", out.has("soldiers"));
        assertFalse("旧嵌套发起编队应清除", out.has("launch_formation"));
        assertFalse("旧模板状态应清除", out.has("template_state"));
    }

    @Test
    public void keepsExplicitQueueCountAndClampsJoinCount() throws Exception {
        JSONObject p = new JSONObject();
        p.put("queue_count", 2);
        JSONObject out = FormationStore.applyBearPresetParams(p, 9);
        assertEquals("join_count 夹到上限 6", 6, out.optInt("join_count"));
        assertEquals("显式 queue_count 保留", 2, out.optInt("queue_count"));

        JSONObject none = FormationStore.applyBearPresetParams(null, 0);
        assertEquals("join_count 下限 1", 1, none.optInt("join_count"));
        assertEquals("queue_count 跟随推断值", 1, none.optInt("queue_count"));
    }

    @Test
    public void secondApplyKeepsSameBindings() throws Exception {
        JSONObject p = new JSONObject();
        FormationStore.applyBearPresetParams(p, 5);
        FormationStore.applyBearPresetParams(p, 5);

        assertEquals("ROLE", p.optString("formation_mode"));
        assertEquals(PresetFormations.ID_BEAR_TEAM, p.optLong("formation_id"));
        assertEquals(5, p.optInt("join_count"));
        assertEquals(5, p.optInt("queue_count"));
        JSONArray joins = p.optJSONArray("join_formations");
        assertEquals(PresetFormations.BEAR_TEMPLATE_JOIN_COUNT, joins.length());
        assertEquals(PresetFormations.bearTemplateJoinId(6),
                joins.optJSONObject(5).optLong("formation_id"));
    }

    @Test
    public void bearPetSkillsParams_writesSwitch() throws Exception {
        JSONObject p = new JSONObject();
        assertTrue(FormationStore.bearPetSkillsParams(p, true).optBoolean("pet_skills"));
        assertFalse(FormationStore.bearPetSkillsParams(p, false).optBoolean("pet_skills"));
        assertFalse("缺省关闭", new JSONObject().optBoolean("pet_skills", false));
    }
}
