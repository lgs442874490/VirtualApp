package com.carlos.vaultserver.migrant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 规则移植回归（对照原版 {@code MigrantRules} 212 行）：
 * 许可书阈值、赛区战力档位、活动阶段窗口、名额/冷却/自动退盟豁免。
 */
public class MigrantRulesTest {

    @Test
    public void requiredPermits_thresholdTable() {
        assertEquals("个人分=标准分 → 50 张", 50, MigrantRules.requiredPermits(100L, 100L));
        assertEquals("95% → 45 张", 45, MigrantRules.requiredPermits(95L, 100L));
        assertEquals("50% → 12 张", 12, MigrantRules.requiredPermits(50L, 100L));
        assertEquals("49% → 落在 45% 档（10 张）", 10, MigrantRules.requiredPermits(49L, 100L));
        assertEquals("0% → 1 张", 1, MigrantRules.requiredPermits(0L, 100L));
        assertEquals("标准分无效 → 0", 0, MigrantRules.requiredPermits(500L, 0L));
        assertEquals("超过 100% → 50 张", 50, MigrantRules.requiredPermits(200L, 100L));
    }

    @Test
    public void evaluate_readyHappyPath() {
        MigrantRules.Evaluation evaluation =
                MigrantRules.evaluate(MigrantTestSupport.readyBuilder().build(),
                        MigrantTestSupport.TARGET_KID);
        assertTrue("默认快照应全部通过：" + MigrantRules.firstFailure(evaluation), evaluation.ready);
        assertEquals(12, evaluation.permitsRequired);
        assertEquals(20L, evaluation.permitsOwned);
        assertEquals("FREEDOM", evaluation.phase);
        assertEquals(MigrantRules.CONFIG_VERSION, evaluation.configVersion);
        assertEquals(14, evaluation.checks.size());
        assertEquals(3, evaluation.warnings.size());
        long startSec = 1_700_000_000L;
        assertEquals((startSec + MigrantRules.FREEDOM_START_OFFSET_SECONDS) * 1000L,
                evaluation.freedomStartMillis);
        assertEquals((startSec + MigrantRules.ACTIVITY_DURATION_SECONDS) * 1000L,
                evaluation.freedomEndMillis);
    }

    @Test
    public void evaluate_allianceBlocks_andAutoExitOverride() {
        MigrantRules.Evaluation blocked = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().alliance(12345L).build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("在联盟内不应 ready", blocked.ready);
        MigrantRules.Check alliance = blocked.checkOf("alliance");
        assertNotNull(alliance);
        assertFalse(alliance.passed);
        assertTrue(alliance.detail.contains("12345"));

        MigrantRules.Evaluation overridden = blocked.withAutoExitAlliance(true);
        assertTrue("开启自动退盟后联盟项不应阻断创建", overridden.ready);
        assertTrue(overridden.checkOf("alliance").passed);

        assertFalse("未开启自动退盟时仍阻断", blocked.withAutoExitAlliance(false).ready);
    }

    @Test
    public void evaluate_powerLimitUsesWarzoneGear() {
        // 赛区 1、档位 1 = 20,000,000；档位 2 = 40,000,000
        MigrantRules.Evaluation gear1 = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().personalPoints(25_000_000L).build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("超出档位 1 上限应失败", gear1.checkOf("power").passed);

        MigrantRules.Evaluation gear2 = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().personalPoints(25_000_000L)
                        .targetKingdom(MigrantTestSupport.kingdom(MigrantTestSupport.TARGET_KID, 0L, 2))
                        .build(),
                MigrantTestSupport.TARGET_KID);
        assertTrue("档位 2 上限 40,000,000 应通过", gear2.checkOf("power").passed);
        assertEquals(40_000_000L, MigrantRules.powerLimitValue(
                MigrantTestSupport.readyBuilder().personalPoints(25_000_000L)
                        .targetKingdom(MigrantTestSupport.kingdom(MigrantTestSupport.TARGET_KID, 0L, 2))
                        .build(), MigrantTestSupport.TARGET_KID));
    }

    @Test
    public void evaluate_quotaAndInvitation() {
        MigrantRules.Evaluation full = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder()
                        .targetKingdom(MigrantTestSupport.kingdom(MigrantTestSupport.TARGET_KID, 55L, 1))
                        .build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("名额耗尽应阻断", full.checkOf("quota").passed);
        assertEquals(0L, full.remainingQuota);

        MigrantRules.Evaluation invited = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder()
                        .targetKingdom(MigrantTestSupport.kingdom(MigrantTestSupport.TARGET_KID, 55L, 1))
                        .invited(MigrantTestSupport.TARGET_KID)
                        .build(),
                MigrantTestSupport.TARGET_KID);
        assertTrue("有邀请时名额不足仍可交由服务端核定", invited.checkOf("quota").passed);
    }

    @Test
    public void evaluate_cooldownAndPermitsAndRoleCount() {
        long startSec = 1_700_000_000L;
        long nowSec = startSec + MigrantRules.FREEDOM_START_OFFSET_SECONDS + 3600L;
        MigrantRules.Evaluation cooldown = MigrantRules.evaluate(
                MigrantTestSupport.builder(startSec, nowSec * 1000L)
                        .lastMigrantSeconds(nowSec - 100L)
                        .build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("25 天冷却未满应阻断", cooldown.checkOf("cooldown").passed);

        MigrantRules.Evaluation permits = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().permits(3L).build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("许可书不足应阻断", permits.checkOf("permits").passed);

        MigrantRules.Evaluation tooManyRoles = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().targetRoleCount(4).build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("同账号目标王国满 4 角色应阻断", tooManyRoles.checkOf("role-count").passed);
    }

    @Test
    public void evaluate_activitySupportedAndPhaseWindows() {
        MigrantRules.Evaluation unsupported = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().activitySupported(false).build(),
                MigrantTestSupport.TARGET_KID);
        assertFalse("活动 ID 不匹配应阻断", unsupported.ready);
        assertFalse(unsupported.checkOf("activity").passed);

        long start = 1_000_000L;
        assertEquals("PROSPECTS", MigrantRules.phase(
                MigrantTestSupport.builder(start, (start + 1L) * 1000L).build(), start + 1L));
        assertEquals("INVITATION", MigrantRules.phase(
                MigrantTestSupport.builder(start, (start + MigrantRules.PROSPECT_SECONDS) * 1000L)
                        .build(),
                start + MigrantRules.PROSPECT_SECONDS));
        assertEquals("FREEDOM", MigrantRules.phase(
                MigrantTestSupport.builder(start, (start + MigrantRules.FREEDOM_START_OFFSET_SECONDS) * 1000L)
                        .build(),
                start + MigrantRules.FREEDOM_START_OFFSET_SECONDS));
        assertEquals("CLOSED", MigrantRules.phase(
                MigrantTestSupport.builder(start, (start + MigrantRules.ACTIVITY_DURATION_SECONDS) * 1000L)
                        .build(),
                start + MigrantRules.ACTIVITY_DURATION_SECONDS));
    }

    @Test
    public void evaluate_targetMustDifferFromCurrentKingdom() {
        MigrantRules.Evaluation same = MigrantRules.evaluate(
                MigrantTestSupport.readyBuilder().build(), MigrantTestSupport.CURRENT_KID);
        assertFalse("不能移民到当前王国", same.checkOf("different-kingdom").passed);
    }

    @Test
    public void advanceMillisClampedTo0to100() {
        assertEquals(0, MigrantJob.clampAdvance(-5));
        assertEquals(0, MigrantJob.clampAdvance(0));
        assertEquals(100, MigrantJob.clampAdvance(101));
        assertEquals(37, MigrantJob.clampAdvance(37));
    }
}
