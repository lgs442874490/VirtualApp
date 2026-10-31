package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 侦查推送配额单测（用户 2026-11-01 硬规则）：滑动 5 分钟窗口内，同一个敌人只推送前 3 次侦查。
 *
 * <p>本类由 App 与 vault-server 同源编译（vault-server build.gradle sourceSets），口径只此一处；
 * 服务端「命中/超限/结算静默」的集成断言见 vault-server 的 WarNotifyTest。
 */
public class ScoutPushQuotaTest {

    @Test
    public void allow_firstThreePerEnemyThenSuppress() {
        ScoutPushQuota q = new ScoutPushQuota();
        long t0 = 1_700_000_000_000L;
        assertTrue(q.allow(888L, t0));
        assertTrue(q.allow(888L, t0 + 1_000));
        assertTrue(q.allow(888L, t0 + 2_000));
        assertFalse("第 4 次超限", q.allow(888L, t0 + 3_000));
        assertFalse("第 5 次仍超限", q.allow(888L, t0 + 4_000));
    }

    @Test
    public void allow_independentPerEnemy() {
        ScoutPushQuota q = new ScoutPushQuota();
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 3; i++) assertTrue(q.allow(11L, t0 + i));
        assertFalse(q.allow(11L, t0 + 10));
        assertTrue("另一个敌人独立计数", q.allow(22L, t0 + 10));
        assertEquals(2, q.trackedEnemies());
    }

    @Test
    public void allow_slidingWindowRecovers() {
        ScoutPushQuota q = new ScoutPushQuota();
        long t0 = 1_700_000_000_000L;
        long w = ScoutPushQuota.WINDOW_MS;
        assertTrue(q.allow(33L, t0));
        assertTrue(q.allow(33L, t0 + 1_000));
        assertTrue(q.allow(33L, t0 + 2_000));
        assertFalse("窗口未滑过：仍超限", q.allow(33L, t0 + w - 1));
        assertTrue("最早一笔正好满 5 分钟 → 恢复 1 次", q.allow(33L, t0 + w));
        assertFalse("窗口内又满 3 笔", q.allow(33L, t0 + w + 1));
        assertTrue("再过一个窗口彻底恢复", q.allow(33L, t0 + 2 * w));
    }

    @Test
    public void allow_invalidUidUnlimited() {
        ScoutPushQuota q = new ScoutPushQuota();
        for (int i = 0; i < 10; i++) assertTrue(q.allow(0L, 1_000 + i));
        assertTrue(q.allow(-5L, 1_000));
        assertEquals("异常 uid 不记账", 0, q.trackedEnemies());
    }

    @Test
    public void sweep_dropsWindowRecordsAndEmptyKeys() {
        ScoutPushQuota q = new ScoutPushQuota();
        long t0 = 1_700_000_000_000L;
        long w = ScoutPushQuota.WINDOW_MS;
        q.allow(44L, t0);
        q.allow(55L, t0 + 1_000);
        assertEquals(2, q.trackedEnemies());
        q.sweep(t0 + w - 1);
        assertEquals(2, q.trackedEnemies());   // 都还在窗口内
        q.sweep(t0 + w);                       // 44 的记录正好出窗
        assertEquals(1, q.trackedEnemies());
        q.sweep(t0 + w + 1_000);
        assertEquals(0, q.trackedEnemies());
    }
}
