package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 巨熊窗口让位门单测（2026-11-07）：只拦队列类任务、按角色隔离、窗口结束自动过期。
 */
public class BearGateTest {

    private static final long NOW = System.currentTimeMillis();
    private static final RoleKey ROLE = new RoleKey("48460403", 888L, 35_371_154L);
    private static final RoleKey OTHER = new RoleKey("48460403", 888L, 88_280_792L);

    @After
    public void cleanup() {
        BearGate.clearAll();
    }

    @Test
    public void enter_blocksQueueTasksOnly() {
        BearGate.enter(ROLE, NOW + 30L * 60_000L, NOW);
        assertTrue("采集让位", BearGate.blocks(ROLE, "gather"));
        assertTrue("狩猎让位", BearGate.blocks(ROLE, "hunt"));
        assertTrue("情报让位", BearGate.blocks(ROLE, "intel"));
        assertTrue("巨兽让位", BearGate.blocks(ROLE, "behemoth"));
        assertTrue("竞技场让位", BearGate.blocks(ROLE, "arena"));
        assertTrue("活动出征让位", BearGate.blocks(ROLE, "act_truck"));

        assertFalse("巨熊自身不让位", BearGate.blocks(ROLE, "bear"));
        assertFalse("邮件/领奖照常", BearGate.blocks(ROLE, "mail"));
        assertFalse("军医照常", BearGate.blocks(ROLE, "medic"));
        assertFalse("护盾照常", BearGate.blocks(ROLE, "battle_shield"));
    }

    @Test
    public void roleIsolation_andRelease() {
        BearGate.enter(ROLE, NOW + 60_000L, NOW);
        assertTrue(BearGate.blocks(ROLE, "gather"));
        assertFalse("兄弟角色不被阻塞", BearGate.blocks(OTHER, "gather"));

        BearGate.release(ROLE);
        assertFalse("释放后不再阻塞", BearGate.blocks(ROLE, "gather"));
    }

    @Test
    public void expiresAfterWindowPlusMargin() {
        BearGate.enter(ROLE, NOW + 60_000L, NOW);
        long until = NOW + 60_000L + BearGate.TTL_MARGIN_MILLIS;
        assertEquals(until, BearGate.until(ROLE, NOW + 1L));
        assertTrue(BearGate.active(ROLE, until - 1L));
        assertFalse("窗口结束 + 兜底余量后自动过期", BearGate.active(ROLE, until));
        assertEquals(0L, BearGate.until(ROLE, until + 1L));
    }

    @Test
    public void enterKeepsLaterDeadline() {
        BearGate.enter(ROLE, NOW + 10_000L, NOW);
        BearGate.enter(ROLE, NOW + 20_000L, NOW);
        assertEquals(NOW + 20_000L + BearGate.TTL_MARGIN_MILLIS, BearGate.until(ROLE, NOW));
    }
}
