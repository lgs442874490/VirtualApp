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
        // 注：MOVE_CITY 于 2026-11-06 立为已实现（网页版迁城 1:1 复刻），不再充当未实现样本。
        List<String> everything = Arrays.asList(
                FeatureGate.Feature.COORD_QUERY.key,
                FeatureGate.Feature.BEHEMOTH_BATCH.key,
                FeatureGate.Feature.WAR_STATE.key);
        assertFalse(FeatureGate.visible(FeatureGate.Feature.COORD_QUERY,
                FeatureGate.TIER_GM, true, everything, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.BEHEMOTH_BATCH,
                FeatureGate.TIER_GM, true, everything, RELEASE));
        assertFalse(FeatureGate.visible(FeatureGate.Feature.WAR_STATE,
                FeatureGate.TIER_GM, true, everything, RELEASE));
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
}
