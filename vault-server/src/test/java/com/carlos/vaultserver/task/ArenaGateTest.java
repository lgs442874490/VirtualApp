package com.carlos.vaultserver.task;

import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次 B：竞技场独占门（{@link ArenaGate}）单测。
 *
 * <p>覆盖：按角色隔离、白名单（arena/battle_shield/medic）、release/clear、ACTIVE 30min 与
 * PREPARING 2min TTL 自动过期、重复进入刷新 TTL、无效入参安全、竞技场任务未启用时清理，
 * 以及独占窗剩余场次纯函数（{@link ServerTownTasks#arenaGateRemaining}）。
 */
public class ArenaGateTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;
    private static final RoleKey A = new RoleKey(FPID, SID, UID_A);
    private static final RoleKey B = new RoleKey(FPID, SID, UID_B);

    @After
    public void clean() {
        ArenaGate.clearAll();
    }

    @Test
    public void blocksByRole_whitelistNeverBlocked() {
        ArenaGate.enterActive(A);
        assertTrue("A 的非白名单任务被阻塞", ArenaGate.blocks(A, "gather"));
        assertTrue(ArenaGate.blocks(A, "mail"));
        assertFalse("B 同服兄弟不受影响", ArenaGate.blocks(B, "gather"));
        assertFalse("arena 自身白名单", ArenaGate.blocks(A, ArenaGate.ARENA_TASK_ID));
        assertFalse("开盾白名单", ArenaGate.blocks(A, "battle_shield"));
        assertFalse("军医白名单", ArenaGate.blocks(A, "medic"));
        assertNull("B 无门", ArenaGate.phase(B));
    }

    @Test
    public void releaseAndClear_perRole() {
        ArenaGate.enterActive(A);
        ArenaGate.release(A);
        assertNull(ArenaGate.phase(A));
        assertFalse(ArenaGate.blocks(A, "gather"));

        ArenaGate.enterPreparing(A);
        ArenaGate.clear(A);
        assertNull(ArenaGate.phase(A));

        ArenaGate.enterActive(A);
        ArenaGate.clear(FPID, SID, UID_A);
        assertNull(ArenaGate.phase(A));

        ArenaGate.enterActive(A);
        ArenaGate.enterActive(B);
        ArenaGate.clearAll();
        assertNull(ArenaGate.phase(A));
        assertNull(ArenaGate.phase(B));
        assertEquals(0, ArenaGate.size());
    }

    @Test
    public void ttl_expiresAutomatically_activeAndPreparing() {
        long now = 1_000_000L;
        ArenaGate.enterActive(A, now);
        assertEquals(ArenaGate.Phase.ACTIVE, ArenaGate.phase(A, now));
        assertTrue(ArenaGate.blocks(A, "gather", now + ArenaGate.ACTIVE_TTL_MILLIS - 1L));
        assertFalse("ACTIVE 30min 到期自动失效",
                ArenaGate.blocks(A, "gather", now + ArenaGate.ACTIVE_TTL_MILLIS));
        assertNull(ArenaGate.phase(A, now + ArenaGate.ACTIVE_TTL_MILLIS));
        assertEquals("过期项被移除", 0, ArenaGate.size());

        long t2 = 2_000_000L;
        ArenaGate.enterPreparing(A, t2);
        assertEquals(ArenaGate.Phase.PREPARING, ArenaGate.phase(A, t2));
        assertTrue(ArenaGate.blocks(A, "gather", t2 + ArenaGate.PREPARING_TTL_MILLIS - 1L));
        assertFalse("PREPARING 2min 到期自动失效",
                ArenaGate.blocks(A, "gather", t2 + ArenaGate.PREPARING_TTL_MILLIS));
    }

    @Test
    public void reEnterRefreshesTtl() {
        long now = 5_000_000L;
        ArenaGate.enterActive(A, now);
        ArenaGate.enterActive(A, now + 25 * 60_000L);   // 竞技场继续推进：刷新
        assertTrue("刷新后未过期", ArenaGate.blocks(A, "gather", now + 50 * 60_000L));
        assertFalse(ArenaGate.blocks(A, "gather",
                now + 25 * 60_000L + ArenaGate.ACTIVE_TTL_MILLIS));
    }

    @Test
    public void nullAndInvalidInputsAreSafe() {
        assertFalse(ArenaGate.blocks(null, "gather"));
        assertFalse(ArenaGate.blocks(A, null));
        assertFalse(ArenaGate.blocks(A, ""));
        assertFalse(ArenaGate.blocks(new RoleKey(FPID, 0L, UID_A), "gather"));
        ArenaGate.enterActive(null);
        ArenaGate.enterActive(new RoleKey("", 0L, 0L));
        ArenaGate.release(null);
        ArenaGate.clear((String) null, 0L, 0L);
        assertEquals(0, ArenaGate.size());
    }

    @Test
    public void arenaTaskDisabled_releasesStaleGate() {
        ArenaGate.enterActive(A);
        ArenaGate.releaseIfArenaTaskDisabled(A, null);   // arena 目录默认 false
        assertNull("竞技场任务未启用 → 清掉残留独占窗", ArenaGate.phase(A));

        ArenaGate.enterActive(A);
        ArenaGate.releaseIfArenaTaskDisabled(A, Map.of("arena", true));
        assertTrue("显式启用竞技场 → 保留", ArenaGate.phase(A) != null);

        ArenaGate.enterActive(A);
        ArenaGate.releaseIfArenaTaskDisabled(A, Map.of("arena", false));
        assertNull(ArenaGate.phase(A));
    }

    @Test
    public void arenaGateRemaining_rules() {
        ServerTownTasks.ArenaDay d = new ServerTownTasks.ArenaDay();
        d.fightsSent = 2;
        assertEquals("默认 5 场 - 已发 2 场 = 3", 3L,
                ServerTownTasks.arenaGateRemaining(d, true, false));
        d.fightsSent = 5;
        assertEquals("打满 5 场释放", 0L, ServerTownTasks.arenaGateRemaining(d, true, false));
        assertEquals("关闭自动挑战释放", 0L, ServerTownTasks.arenaGateRemaining(d, false, false));
        assertEquals("结算窗释放", 0L, ServerTownTasks.arenaGateRemaining(d, true, true));

        d.fightsSent = 0;
        d.boughtTimes = 2;
        d.baseKnown = true;
        d.freeUsed = 5;
        d.buyTimes = 2;
        d.leftTimes = 1;   // knownCompleted = 5 + (2-1) = 6，cap=7 → 剩 1
        assertEquals("买次数后按 cap=7 计算", 1L,
                ServerTownTasks.arenaGateRemaining(d, true, false));

        d.exhausted = true;
        assertEquals("服务端判次数用完立即释放", 0L,
                ServerTownTasks.arenaGateRemaining(d, true, false));
    }
}
