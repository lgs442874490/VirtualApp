package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.automation.services.DefaultFormationPlanner;
import com.carlos.vaultserver.automation.services.FormationPlanner;
import com.carlos.vaultserver.automation.services.HeroDirectory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FormationPlannerTest {

    private static final long GINA = 50001;   // 射手
    private static final long BOGEN = 50002;  // 矛兵
    private static final long SHIELD = 50003; // 最强盾兵

    private static HeroDirectory heroes() {
        return new HeroDirectory.ConfigHeroDirectory()
                .put(GINA, "吉娜", "shooter", 1000)
                .put(BOGEN, "波根", "spear", 900)
                .put(SHIELD, "盾王", "shield", 800);
    }

    private static FormationPlanner.TroopPool pool() {
        return new FormationPlanner.TroopPool()
                .put(10101, 100_000)
                .put(20101, 100_000)
                .put(30101, 100_000);
    }

    @Test
    public void gatherUsesDynamicTroopsAndRequestedHeroes() {
        FormationPlanner.Config cfg = new FormationPlanner.Config();
        cfg.carryPerTroop = 100;
        cfg.heroCarryBonus = 0.30;
        cfg.bufferPct = 10;
        cfg.minTroops = 100;
        cfg.maxTroops = 300_000;
        FormationPlanner.Request req = new FormationPlanner.Request(
                FormationPlanner.Scheme.GATHER, FormationPlanner.Resource.MEAT,
                100_000, new FormationPlanner.HeroSelectors(), false,
                java.util.Arrays.asList(GINA, BOGEN, SHIELD));

        FormationPlanner.Plan plan = new DefaultFormationPlanner()
                .plan(req, pool(), heroes(), cfg);
        assertTrue(plan.valid());
        assertEquals(java.util.Arrays.asList(GINA, BOGEN, SHIELD), plan.heroIds);
        assertTrue("total=" + plan.totalSoldiers + " note=" + plan.note,
                plan.totalSoldiers > 800 && plan.totalSoldiers < 900);
        assertTrue(plan.note.contains("required="));
    }

    @Test
    public void beastFullTroopsUsesWholePoolAndSameHeroes() {
        FormationPlanner.Request req = new FormationPlanner.Request(
                FormationPlanner.Scheme.BEAST, FormationPlanner.Resource.MEAT,
                0, new FormationPlanner.HeroSelectors(), true,
                java.util.Arrays.asList(GINA, BOGEN, SHIELD));
        FormationPlanner.Plan plan = new DefaultFormationPlanner()
                .plan(req, pool(), heroes(), new FormationPlanner.Config());
        assertTrue(plan.valid());
        assertEquals(300_000, plan.totalSoldiers);
        assertEquals(java.util.Arrays.asList(GINA, BOGEN, SHIELD), plan.heroIds);
    }

    @Test
    public void behemothRallyUsesShieldFirstAndShieldTroops() {
        FormationPlanner.Request req = new FormationPlanner.Request(
                FormationPlanner.Scheme.BEHEMOTH_RALLY, FormationPlanner.Resource.MEAT,
                90_000, new FormationPlanner.HeroSelectors(), false,
                java.util.Arrays.asList(GINA, BOGEN, SHIELD));
        FormationPlanner.Plan plan = new DefaultFormationPlanner()
                .plan(req, pool(), heroes(), new FormationPlanner.Config());
        assertTrue(plan.valid());
        assertEquals(java.util.Arrays.asList(SHIELD, GINA, BOGEN), plan.heroIds);
        for (long id : plan.soldiers.keySet()) {
            assertEquals("集结方案只派盾兵", 1, id / 10000);
        }
    }

    @Test
    public void missingHeroDirectoryProducesInvalidPlan() {
        FormationPlanner.Plan plan = new DefaultFormationPlanner().plan(
                new FormationPlanner.Request(FormationPlanner.Scheme.GATHER,
                        FormationPlanner.Resource.WOOD, 10_000,
                        new FormationPlanner.HeroSelectors(), false),
                pool(), HeroDirectory.empty(), new FormationPlanner.Config());
        assertFalse(plan.valid());
        assertTrue(plan.note.contains("英雄目录"));
    }
}
