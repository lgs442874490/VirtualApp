package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 功能分级门控矩阵单测（批次7）：按「无权限不显示 / 未开发一律隐藏 / 调试项仅调试包」三条原则锁死。
 *
 * <p>矩阵依据：{@code design/全流程行为与分级门控-20261023.md}（批次7 审计）与代码证据；
 * 本测试只跑纯函数，不依赖 Android 运行时/LicenseManager 实例。
 */
public class FeatureGateTest {

    private static final boolean RELEASE = false;
    private static final boolean DEBUG = true;

    // ================= T1 普通：只有分身/账号/启动 =================

    @Test
    public void tier1_showsBasicAccountFeaturesOnly() {
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL, 1, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_ADD, 1, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_START, 1, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.BASIC_SETTINGS, 1, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.LICENSE_MANAGE, 1, RELEASE));
    }

    @Test
    public void tier1_hidesAutomationAndPremium() {
        // T2 日常脚本：T1 不显示
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TASK_TAB, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.AUTOMATION_STATUS, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.DAILY_CONFIG, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.SEVEN_MODULES, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.NOTIFY_SETTINGS, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.RUN_PROGRESS, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.KEEP_ALIVE, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.AID_ALLIANCE, 1, RELEASE));
        // T3 高阶：T1 更不可见
        assertFalse(FeatureGate.visible(FeatureGate.Feature.GUARDIAN, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH, 1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TOOLBOX, 1, RELEASE));
    }

    // ================= T2 会员：日常脚本 =================

    @Test
    public void tier2_showsDailyScriptFeatures() {
        assertTrue(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.TASK_TAB, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.AUTOMATION_STATUS, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.DAILY_CONFIG, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.SEVEN_MODULES, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.NOTIFY_SETTINGS, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.RUN_PROGRESS, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.KEEP_ALIVE, 2, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.AID_ALLIANCE, 2, RELEASE));
        // T1 基础功能对 T2 仍可见
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_START, 2, RELEASE));
    }

    @Test
    public void tier2_hidesPremiumFeatures() {
        assertFalse(FeatureGate.visible(FeatureGate.Feature.GUARDIAN, 2, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH, 2, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TOOLBOX, 2, RELEASE));
    }

    // ================= T3 高级会员：高阶功能 =================

    @Test
    public void tier3_showsOnlyFullyImplementedPremiumFeatures() {
        // 审计结论（design/全流程行为与分级门控-20261023.md §3.4）：T3 六项中仅敌情监控已实现
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH, 3, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.TOOLBOX, 3, RELEASE));
        // 守护盟友=部分实现（自动驻防未接服务器）→ 正式 UI 暂不显示
        assertFalse("部分实现不放出", FeatureGate.visible(FeatureGate.Feature.GUARDIAN, 3, RELEASE));
        // T2 功能对 T3 仍可见（等级向下兼容）
        assertTrue(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB, 3, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL, 3, RELEASE));
    }

    @Test
    public void unimplementedPremiumFeatures_hiddenEvenAtTier3() {
        // 高阶但未开发：即使 T3 也不放出去（用户要求“没开发的不要放出去”）
        assertTrue("前提：这些功能未实现", !FeatureGate.Feature.MOVE_CITY.implemented);
        assertFalse(FeatureGate.visible(FeatureGate.Feature.MOVE_CITY, 3, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.COORD_QUERY, 3, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.RECHARGE_RECORD, 3, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.BEHEMOTH_BATCH, 3, RELEASE));
    }

    @Test
    public void allPlannedButUnimplementedFeatures_areHiddenForEveryTierAndDebug() {
        int[] tiers = {FeatureGate.TIER_NONE, FeatureGate.TIER_BASIC,
                FeatureGate.TIER_MEMBER, FeatureGate.TIER_PREMIUM};
        for (FeatureGate.Feature f : FeatureGate.Feature.values()) {
            if (f.implemented) continue;
            for (int tier : tiers) {
                assertFalse("未实现功能必须隐藏：" + f.key,
                        FeatureGate.visible(f, tier, DEBUG));
            }
            assertNotNull("未实现功能要有隐藏原因文案", FeatureGate.reasonText(f));
            assertFalse("未实现原因不能为空", FeatureGate.reasonText(f).isEmpty());
        }
    }

    // ================= 调试/实测项：仅调试包 =================

    @Test
    public void debugOnlyFeatures_visibleInDebugBuildOnly() {
        assertFalse("正式包不渲染协议监听",
                FeatureGate.visible(FeatureGate.Feature.PROTOCOL_LOGGER, 3, RELEASE));
        assertFalse("正式包不渲染协议探针",
                FeatureGate.visible(FeatureGate.Feature.PROTOCOL_PROBE, 3, RELEASE));
        assertTrue("调试包 T3 可见协议监听",
                FeatureGate.visible(FeatureGate.Feature.PROTOCOL_LOGGER, 3, DEBUG));
        assertTrue("调试包 T1 也可用（调试工具不受业务等级限制）",
                FeatureGate.visible(FeatureGate.Feature.PROTOCOL_LOGGER, 1, DEBUG));
        assertEquals("仅调试版可用", FeatureGate.reasonText(FeatureGate.Feature.PROTOCOL_LOGGER));
    }

    // ================= 未知功能 / 越界等级安全 =================

    @Test
    public void nullFeature_safeHidden() {
        assertFalse(FeatureGate.visible(null, 3, DEBUG));
        assertEquals(FeatureGate.TIER_NONE, FeatureGate.requiredTier(null));
        assertNotNull(FeatureGate.reasonText(null));
        assertEquals("该功能未开放", FeatureGate.reasonText(null));
        assertEquals("未知功能", FeatureGate.label(null));
    }

    @Test
    public void unknownTier_doesNotGrantAnything() {
        // 0/负数=无权限；null-ish 等级不得当成高等级放行
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB, 0, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB, -1, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL, 0, RELEASE));
    }

    @Test
    public void sanitizeTier_clampsToKnownRange() {
        assertEquals(FeatureGate.TIER_NONE, FeatureGate.sanitizeTier(-5));
        assertEquals(FeatureGate.TIER_NONE, FeatureGate.sanitizeTier(0));
        assertEquals(FeatureGate.TIER_BASIC, FeatureGate.sanitizeTier(1));
        assertEquals(FeatureGate.TIER_MEMBER, FeatureGate.sanitizeTier(2));
        assertEquals(FeatureGate.TIER_PREMIUM, FeatureGate.sanitizeTier(3));
        assertEquals("超界按最高档封顶", FeatureGate.TIER_PREMIUM, FeatureGate.sanitizeTier(99));
    }

    // ================= 等级门槛/文案 =================

    @Test
    public void requiredTier_matchesMatrix() {
        assertEquals(FeatureGate.TIER_BASIC, FeatureGate.requiredTier(FeatureGate.Feature.ACCOUNT_START));
        assertEquals(FeatureGate.TIER_MEMBER, FeatureGate.requiredTier(FeatureGate.Feature.TODAY_TAB));
        assertEquals(FeatureGate.TIER_MEMBER, FeatureGate.requiredTier(FeatureGate.Feature.AUTOMATION_STATUS));
        assertEquals(FeatureGate.TIER_PREMIUM, FeatureGate.requiredTier(FeatureGate.Feature.GUARDIAN));
        assertEquals(FeatureGate.TIER_PREMIUM, FeatureGate.requiredTier(FeatureGate.Feature.ENEMY_WATCH));
        assertEquals(FeatureGate.TIER_PREMIUM, FeatureGate.requiredTier(FeatureGate.Feature.TOOLBOX));
    }

    @Test
    public void reasonText_explainsTier() {
        assertTrue(FeatureGate.reasonText(FeatureGate.Feature.TODAY_TAB).contains("会员"));
        assertTrue(FeatureGate.reasonText(FeatureGate.Feature.ENEMY_WATCH).contains("高级会员"));
        assertTrue("守护盟友=部分实现→尚未开放", FeatureGate.reasonText(FeatureGate.Feature.GUARDIAN).contains("尚未开放"));
        assertTrue(FeatureGate.reasonText(FeatureGate.Feature.MOVE_CITY).contains("尚未开放"));
        assertEquals("T1 功能无等级门槛，不应有提示文案",
                "", FeatureGate.reasonText(FeatureGate.Feature.ACCOUNT_START));
    }

    @Test
    public void tierName_matchesLicenseConfig() {
        assertEquals("普通", FeatureGate.tierName(1));
        assertEquals("会员", FeatureGate.tierName(2));
        assertEquals("高级会员", FeatureGate.tierName(3));
    }

    // ================= 日常功能（FnRegistry）门控：守护盟友 = T3 =================

    @Test
    public void dailyFnGuardian_hiddenEverywhereUntilProductized() {
        assertEquals(FeatureGate.Feature.GUARDIAN, FeatureGate.dailyFnFeature("guardian"));
        // 守护盟友=部分实现（监测/闸门可用，自动驻防未接服务器）→ 任何等级都不显示
        assertFalse(FeatureGate.dailyFnVisible("guardian", 1, RELEASE));
        assertFalse(FeatureGate.dailyFnVisible("guardian", 2, RELEASE));
        assertFalse(FeatureGate.dailyFnVisible("guardian", 3, RELEASE));
        assertFalse("调试包也不放半成品业务功能", FeatureGate.dailyFnVisible("guardian", 3, DEBUG));
    }

    @Test
    public void dailyFnWarAndBackpack_hidden() {
        // war：FnRegistry 有行但服务端 55 项无对应任务（勾选不执行）
        assertEquals(FeatureGate.Feature.WAR_STATE, FeatureGate.dailyFnFeature("war"));
        assertFalse(FeatureGate.dailyFnVisible("war", 2, RELEASE));
        assertFalse(FeatureGate.dailyFnVisible("war", 3, RELEASE));
        // backpack：解析已修但自动开箱范围产品未确认
        assertEquals(FeatureGate.Feature.BACKPACK_ITEMS, FeatureGate.dailyFnFeature("backpack"));
        assertFalse(FeatureGate.dailyFnVisible("backpack", 2, RELEASE));
        assertFalse(FeatureGate.dailyFnVisible("backpack", 3, RELEASE));
    }

    @Test
    public void dailyFnBehemoth_keptVisibleForT2() {
        // 巨兽狩猎：仅“原生自动上车”但服务端会执行该任务，UI 保留可关（文案待改，见交付说明）
        assertEquals(FeatureGate.Feature.BEHEMOTH_AUTOJOIN, FeatureGate.dailyFnFeature("behemoth"));
        assertFalse(FeatureGate.dailyFnVisible("behemoth", 1, RELEASE));
        assertTrue(FeatureGate.dailyFnVisible("behemoth", 2, RELEASE));
        assertTrue(FeatureGate.dailyFnVisible("behemoth", 3, RELEASE));
    }

    @Test
    public void dailyFnOthers_haveNoExtraGate() {
        // 其它日常功能由 T2 页面级门控负责（列表内不再额外过滤）
        assertEquals(null, FeatureGate.dailyFnFeature("mail"));
        assertTrue(FeatureGate.dailyFnVisible("mail", 2, RELEASE));
        assertTrue(FeatureGate.dailyFnVisible("gather", 2, RELEASE));
        // null / 未知 id 安全：不额外拦截
        assertTrue(FeatureGate.dailyFnVisible(null, 2, RELEASE));
        assertTrue(FeatureGate.dailyFnVisible("fn_not_exist", 2, RELEASE));
    }
}
