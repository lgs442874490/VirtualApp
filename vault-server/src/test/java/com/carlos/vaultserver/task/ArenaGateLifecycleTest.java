package com.carlos.vaultserver.task;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 竞技场独占门（ArenaGate）与会话生命周期接线回归（2026-10-30 批次 B）。
 *
 * <p>规则：按角色 RoleKey 隔离；白名单 arena/battle_shield/medic 永不被挡；
 * ACTIVE/PREPARING 有 TTL 兜底（30min/2min）防卡死；clear 只清目标角色。
 */
public class ArenaGateLifecycleTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;

    private static RoleKey role(long uid) {
        return new RoleKey(FPID, SID, uid);
    }

    @After
    public void cleanup() {
        ArenaGate.clearAll();
    }

    @Test
    public void blocksOtherTasksButNotArenaOrCriticalTasks() {
        ArenaGate.enterActive(role(UID_A));
        assertTrue("普通任务被挡", ArenaGate.blocks(role(UID_A), "mail"));
        assertFalse("arena 自己不被挡", ArenaGate.blocks(role(UID_A), "arena"));
        assertFalse("开盾白名单", ArenaGate.blocks(role(UID_A), "battle_shield"));
        assertFalse("军医所白名单", ArenaGate.blocks(role(UID_A), "medic"));
        assertFalse("兄弟角色不受影响", ArenaGate.blocks(role(UID_B), "mail"));
    }

    @Test
    public void clearReleasesOnlyTargetRole() {
        ArenaGate.enterActive(role(UID_A));
        ArenaGate.enterActive(role(UID_B));
        ArenaGate.clear(FPID, SID, UID_A);
        assertFalse("A 已释放", ArenaGate.blocks(role(UID_A), "mail"));
        assertTrue("B 仍独占", ArenaGate.blocks(role(UID_B), "mail"));
    }

    @Test
    public void actorTtlExpiresAutomatically() {
        long now = 1_793_400_000_000L;
        ArenaGate.enterActive(role(UID_A), now);
        assertTrue(ArenaGate.blocks(role(UID_A), "mail", now + 60_000L));
        // ACTIVE TTL = 30min：过期后自动放行（防卡死全线停摆）
        assertFalse(ArenaGate.blocks(role(UID_A), "mail", now + 31L * 60_000L));
        assertFalse("过期后 phase 为空", ArenaGate.blocks(role(UID_A), "mail", now + 31L * 60_000L));
    }

    @Test
    public void releaseIfArenaTaskDisabledClearsResidue() {
        ArenaGate.enterActive(role(UID_A));
        java.util.Map<String, Boolean> cfg = new java.util.LinkedHashMap<>();
        cfg.put("arena", false);
        ArenaGate.releaseIfArenaTaskDisabled(role(UID_A), cfg);
        assertFalse("arena 任务关闭 → 残留门清理", ArenaGate.blocks(role(UID_A), "mail"));
    }

    @Test
    public void nullAndUidZeroSafe() {
        assertFalse(ArenaGate.blocks(null, "mail"));
        assertFalse(ArenaGate.blocks(new RoleKey(FPID, SID, 0L), "mail"));
        ArenaGate.clear(null);
        ArenaGate.clear(FPID, SID, 0L);
    }
}
