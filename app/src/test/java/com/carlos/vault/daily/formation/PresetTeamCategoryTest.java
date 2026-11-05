package com.carlos.vault.daily.formation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 编队模板分类单测（2026-11-06）。
 *
 * <p>分类口径（与「编队管理-我的编队」四分类、巨熊行动/采集设置一键应用共用）：
 * <ul>
 *   <li>巨熊编队模板 = 预设「打熊队」（发起，9100000003）+「熊上车1-6」（上车，9100000010..15）；</li>
 *   <li>采集队 = 预设「采集肉/木/煤/铁」（9100000006..9）；</li>
 *   <li>两组互不重叠；熊上车7-10 不属于巨熊编队模板。</li>
 * </ul>
 */
public class PresetTeamCategoryTest {

    @Test
    public void bearTemplateIds_launchThenRideOneToSix() {
        List<Long> ids = PresetFormations.bearTemplateIds();
        assertEquals(1 + PresetFormations.BEAR_TEMPLATE_JOIN_COUNT, ids.size());
        assertEquals(Long.valueOf(PresetFormations.ID_BEAR_TEAM), ids.get(0));
        assertEquals(Long.valueOf(9100000003L), ids.get(0));
        for (int slot = 1; slot <= PresetFormations.BEAR_TEMPLATE_JOIN_COUNT; slot++) {
            assertEquals("第 " + slot + " 支上车队",
                    Long.valueOf(PresetFormations.bearTemplateJoinId(slot)), ids.get(slot));
        }
        assertEquals(9100000010L, PresetFormations.bearTemplateJoinId(1));
        assertEquals(9100000015L, PresetFormations.bearTemplateJoinId(6));
        assertEquals("slot 0 越界", 0L, PresetFormations.bearTemplateJoinId(0));
        assertEquals("slot 7 越界", 0L, PresetFormations.bearTemplateJoinId(7));
    }

    @Test
    public void isBearTemplateTeam_onlyLaunchAndRideOneToSix() {
        assertTrue(PresetFormations.isBearTemplateTeam(9100000003L));   // 打熊队
        for (int slot = 1; slot <= 6; slot++) {
            assertTrue("熊上车" + slot,
                    PresetFormations.isBearTemplateTeam(PresetFormations.bearTemplateJoinId(slot)));
        }
        assertFalse("狩猎不是巨熊模板", PresetFormations.isBearTemplateTeam(9100000002L));
        assertFalse("盾弓不是巨熊模板", PresetFormations.isBearTemplateTeam(9100000004L));
        assertFalse("采集肉不是巨熊模板", PresetFormations.isBearTemplateTeam(9100000006L));
        assertFalse("熊上车7 不在 1-6 模板内", PresetFormations.isBearTemplateTeam(9100000016L));
        assertFalse("非预设 id", PresetFormations.isBearTemplateTeam(42L));
    }

    @Test
    public void gatherTeamIds_areMeatWoodCoalIron() {
        List<Long> ids = PresetFormations.gatherTeamIds();
        assertEquals(PresetFormations.GATHER_TEAM_COUNT, ids.size());
        assertEquals(4, ids.size());
        long[] expect = {9100000006L, 9100000007L, 9100000008L, 9100000009L};
        for (int i = 0; i < expect.length; i++) {
            assertEquals(expect[i], ids.get(i).longValue());
            assertEquals(expect[i], PresetFormations.gatherTeamId(i));
            assertTrue(PresetFormations.isGatherTeam(expect[i]));
        }
        assertEquals("越界 -1", 0L, PresetFormations.gatherTeamId(-1));
        assertEquals("越界 4", 0L, PresetFormations.gatherTeamId(4));
        assertFalse("熊上车1 不是采集队", PresetFormations.isGatherTeam(9100000010L));
    }

    @Test
    public void gatherAndBearTemplateDoNotOverlap() {
        List<Long> gather = PresetFormations.gatherTeamIds();
        for (Long id : PresetFormations.bearTemplateIds()) {
            assertFalse("id " + id + " 不应同时属于采集与巨熊模板", gather.contains(id));
        }
        assertTrue("全部模板 id 仍属于 19 套预设",
                PresetFormations.isPreset(PresetFormations.ID_BEAR_TEAM)
                        && PresetFormations.isPreset(PresetFormations.ID_GATHER_FIRST)
                        && PresetFormations.isPreset(PresetFormations.ID_BEAR_RIDE_FIRST));
    }
}
