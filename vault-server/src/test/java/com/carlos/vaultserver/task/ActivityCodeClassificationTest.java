package com.carlos.vaultserver.task;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 活动领取错误码归类回归（2026-10-30）：
 * 生产实证「米娅许愿屋 req_fortune_card_get_daily_free_gift → code=10151」每 ~6 分钟重复请求，
 * 旧实现记 ERROR。10151 归一为「今日已领/不可领」：跳过 + 台账去重（当天不再请求）。
 */
public class ActivityCodeClassificationTest {

    @Test
    public void miaWishHouse10151_isAlreadyClaimedAndUnavailable() {
        assertTrue("10151 属不可用/已领 → 不记 ERROR",
                ServerTasks.isActivityUnavailableCode(10151));
        assertTrue("10151 写台账后当天不再请求",
                ServerTasks.isActivityAlreadyClaimedCode(10151));
    }

    @Test
    public void existingCodesUnchanged() {
        assertTrue(ServerTasks.isActivityUnavailableCode(7401));
        assertTrue(ServerTasks.isActivityAlreadyClaimedCode(1507));
        assertFalse("未知码仍按失败处理", ServerTasks.isActivityUnavailableCode(999999));
        assertFalse(ServerTasks.isActivityAlreadyClaimedCode(10152));
    }

    /** 2026-11-01：新角色（152/531/882）实测的签到码 1706/11501 归“不可用”（SKIP 不刷 DEGRADED）。 */
    @Test
    public void loginTrackCodes_1706_11501_areUnavailableNotClaimed() {
        assertTrue("七天签到 1706 = 不可用", ServerTasks.isActivityUnavailableCode(1706));
        assertTrue("21天签到 11501 = 不可用", ServerTasks.isActivityUnavailableCode(11501));
        assertFalse("1706 不写已领台账", ServerTasks.isActivityAlreadyClaimedCode(1706));
        assertFalse("11501 不写已领台账", ServerTasks.isActivityAlreadyClaimedCode(11501));
    }
}
