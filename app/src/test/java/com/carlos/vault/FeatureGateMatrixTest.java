package com.carlos.vault;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-06 · 服务端功能矩阵优先的门控单测。
 *
 * <p>覆盖 {@link FeatureGate#visible(FeatureGate.Feature, int, boolean, List, boolean)}：
 * <ul>
 *   <li>服务端已下发矩阵 → 按 key 命中（角色等级不再直接决定）；</li>
 *   <li>空矩阵 → 明确无功能（不回退硬编码）；</li>
 *   <li>未下发矩阵 → 回退 minTier（与历史行为一致）；</li>
 *   <li>未实现功能恒隐藏（GM 也不行）；debugOnly 仅 debug 包；</li>
 *   <li>GM 角色 + 空矩阵 / 未知 key 的安全性；sanitizeTier/tierName 支持 GM。</li>
 * </ul>
 */
public class FeatureGateMatrixTest {

    private static final boolean RELEASE = false;
    private static final boolean DEBUG = true;

    @Test
    public void matrixHit_winsOverRole() {
        List<String> onlyBasic = Arrays.asList("account_pool", "account_start");
        // T3 角色但矩阵只给了 2 个 key：未列出的功能必须隐藏。
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL,
                FeatureGate.TIER_PREMIUM, true, onlyBasic, RELEASE));
        assertFalse("矩阵未列出的功能必须隐藏（即使 T3）",
                FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH,
                        FeatureGate.TIER_PREMIUM, true, onlyBasic, RELEASE));
        assertFalse("矩阵未列出的功能必须隐藏（即使 T3）",
                FeatureGate.visible(FeatureGate.Feature.TODAY_TAB,
                        FeatureGate.TIER_PREMIUM, true, onlyBasic, RELEASE));
    }

    @Test
    public void matrixCanOpenBelowDefaultTier() {
        // T1 角色但矩阵显式给了高阶功能：以矩阵为准（管理员按角色勾选）。
        List<String> openHigher = Arrays.asList("account_pool", "enemy_watch");
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH,
                FeatureGate.TIER_BASIC, true, openHigher, RELEASE));
    }

    @Test
    public void emptyMatrix_meansNoFeature() {
        assertFalse(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL,
                FeatureGate.TIER_GM, true, Collections.emptyList(), RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB,
                FeatureGate.TIER_PREMIUM, true, Collections.emptyList(), RELEASE));
    }

    @Test
    public void noMatrix_fallsBackToMinTier() {
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL,
                FeatureGate.TIER_BASIC, false, null, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB,
                FeatureGate.TIER_BASIC, false, null, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB,
                FeatureGate.TIER_MEMBER, false, null, RELEASE));
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ENEMY_WATCH,
                FeatureGate.TIER_PREMIUM, false, null, RELEASE));
        assertFalse("无授权（0）不得放行",
                FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL,
                        FeatureGate.TIER_NONE, false, null, RELEASE));
    }

    @Test
    public void unimplementedAlwaysHiddenEvenWithMatrix() {
        // 2026-11-06 决策6：RANKING 等零引用死枚举已删除；用仍保留的未实现项验证矩阵不越权。
        List<String> everything = Arrays.asList(
                FeatureGate.Feature.MOVE_CITY.key,
                FeatureGate.Feature.BEHEMOTH_BATCH.key,
                FeatureGate.Feature.WAR_STATE.key);
        assertFalse(FeatureGate.visible(FeatureGate.Feature.MOVE_CITY,
                FeatureGate.TIER_GM, true, everything, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.BEHEMOTH_BATCH,
                FeatureGate.TIER_GM, true, everything, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.WAR_STATE,
                FeatureGate.TIER_GM, true, everything, RELEASE));
    }

    @Test
    public void debugOnly_followsBuildType() {
        List<String> withProto = Arrays.asList(FeatureGate.Feature.PROTOCOL_LOGGER.key);
        assertFalse("release 包不得渲染调试项",
                FeatureGate.visible(FeatureGate.Feature.PROTOCOL_LOGGER,
                        FeatureGate.TIER_GM, true, withProto, RELEASE));
        assertTrue("debug 包可见",
                FeatureGate.visible(FeatureGate.Feature.PROTOCOL_LOGGER,
                        FeatureGate.TIER_BASIC, true, withProto, DEBUG));
    }

    @Test
    public void unknownKeysIgnored() {
        List<String> withUnknown = Arrays.asList("not_a_real_key", "account_pool");
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_POOL,
                FeatureGate.TIER_BASIC, true, withUnknown, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.TODAY_TAB,
                FeatureGate.TIER_BASIC, true, withUnknown, RELEASE));
    }

    @Test
    public void roleSanitizeAndName() {
        assertEquals(0, FeatureGate.sanitizeTier(0));
        assertEquals(1, FeatureGate.sanitizeTier(1));
        assertEquals(4, FeatureGate.sanitizeTier(4));
        assertEquals("超界封顶 4", 4, FeatureGate.sanitizeTier(9));
        assertEquals("GM", FeatureGate.tierName(4));
        assertEquals("普通", FeatureGate.tierName(1));
    }

    @Test
    public void dailyFnVisible_usesMatrix() {
        // 无矩阵映射的日常功能：恒可见（页面级门控负责）。
        assertTrue(FeatureGate.dailyFnVisible("mail", FeatureGate.TIER_BASIC, RELEASE));
        // guardian 映射到 Feature.GUARDIAN：未实现→恒隐藏（当前矩阵 implemented=true，见下一条）。
        assertFalse(FeatureGate.dailyFnVisible("war", FeatureGate.TIER_GM, RELEASE));
    }

    // ================= ACCOUNT_FARM（M5 起号，2026-11-07 立项恢复） =================

    @Test
    public void accountFarm_implementedAndVisibleForPremium() {
        // M5 转正：implemented=true，未下发矩阵时回退 minTier=T3。
        assertTrue(FeatureGate.Feature.ACCOUNT_FARM.implemented);
        assertEquals(FeatureGate.TIER_PREMIUM, FeatureGate.Feature.ACCOUNT_FARM.minTier);
        assertFalse("非调试项：正式包同样可见", FeatureGate.Feature.ACCOUNT_FARM.debugOnly);
        assertTrue(FeatureGate.visible(FeatureGate.Feature.ACCOUNT_FARM,
                FeatureGate.TIER_PREMIUM, false, null, RELEASE));
        assertTrue("矩阵命中 key 放行（低等级也可）",
                FeatureGate.visible(FeatureGate.Feature.ACCOUNT_FARM,
                        FeatureGate.TIER_BASIC, true,
                        java.util.Collections.singletonList(FeatureGate.Feature.ACCOUNT_FARM.key),
                        RELEASE));
        assertEquals("起号", FeatureGate.Feature.ACCOUNT_FARM.label);
    }

    @Test
    public void accountFarm_hiddenWithoutPermission() {
        assertFalse("T2 及以下（无矩阵回退）不可见",
                FeatureGate.visible(FeatureGate.Feature.ACCOUNT_FARM,
                        FeatureGate.TIER_MEMBER, false, null, RELEASE));
        assertFalse("矩阵未列出时不可见",
                FeatureGate.visible(FeatureGate.Feature.ACCOUNT_FARM,
                        FeatureGate.TIER_GM, true,
                        java.util.Collections.singletonList("enemy_watch"), RELEASE));
    }

    @Test
    public void accountFarm_countsIntoVisibleToolCount() {
        // 起号卡计入工具箱计数：T3（无矩阵回退）至少 5（起号+敌情监控+真机监控+观察点+真机抓包）。
        assertTrue(FeatureGate.visibleToolCount(FeatureGate.TIER_PREMIUM, RELEASE) >= 5);
        assertTrue("T2（无矩阵）不含起号：起号 T3 起",
                FeatureGate.visibleToolCount(FeatureGate.TIER_MEMBER, RELEASE) == 0);
    }
}
