package com.carlos.vaultserver.task;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 情报普通队列语义单测（2026-10-31 用户规则 2 修正）。
 *
 * <p>用户口径：情报三种类型，**除狩猎外不需要行军队列**。
 * <ul>
 *   <li>狩猎(1)/活动巨兽(11)/冒险巨兽(100) → 302，占普通队列；</li>
 *   <li>营救(2) → 301 特殊队列，不占普通队列；英雄之旅(3) → PVE，不需要队列；</li>
 *   <li>剩余槽位只扣在途 302；**在途 301 不扣**（2026-10-31 根因：旧实现把 301 也计入，
 *       默认 queue_count=1 时一队营救在途就让狩猎永远派不出）。</li>
 * </ul>
 */
public class IntelQueueSemanticsTest {

    // ==================== 类型判定 ====================

    @Test
    public void worldMarchTypes_matchWeb() {
        assertTrue(ServerFieldTasks.intelIsWorldMarch(1));    // 怪物情报
        assertTrue(ServerFieldTasks.intelIsWorldMarch(2));    // 营救幸存者
        assertTrue(ServerFieldTasks.intelIsWorldMarch(11));   // 活动巨兽
        assertTrue(ServerFieldTasks.intelIsWorldMarch(100));  // 冒险巨兽
        assertFalse("英雄之旅是 PVE，不是世界行军", ServerFieldTasks.intelIsWorldMarch(3));
        assertFalse(ServerFieldTasks.intelIsWorldMarch(0));
    }

    @Test
    public void consumesNormalSlot_onlyWorldMarchExceptRescue() {
        assertFalse("营救(type=2) 不占普通行军队列（网页版 consumesNormalWorldMarchSlot）",
                ServerFieldTasks.intelConsumesNormalMarchSlot(2));
        assertTrue("怪物(type=1) 占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(1));
        assertTrue("活动巨兽(type=11) 占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(11));
        assertTrue("冒险巨兽(type=100) 占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(100));
        assertFalse("英雄之旅(type=3) 不占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(3));
        assertFalse(ServerFieldTasks.intelConsumesNormalMarchSlot(0));
    }

    // ==================== 剩余普通槽位 ====================

    @Test
    public void remainingNormalSlots_webFormula() {
        // allowed=min(配置,容量)=1；无在途 → 1 队可派
        assertEquals(1, ServerFieldTasks.intelRemainingNormalSlots(1, 0, 3));
        // 已有 1 队在途普通情报（type=302 狩猎）→ 0
        // （在途 301 营救不占普通队列，不参与此扣减）
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(1, 1, 3));
        // 世界空闲=0（被采集/狩猎占满）→ 上界钳到 0，即便配置允许多队也不派
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(3, 0, 0));
        // 配置 3 / 世界空闲 5 / 在途 1 → 2
        assertEquals(2, ServerFieldTasks.intelRemainingNormalSlots(3, 1, 5));
        // 世界空闲小于配置剩余 → 取世界空闲（网页版 Math.clamp 上界 world.idle）
        assertEquals(1, ServerFieldTasks.intelRemainingNormalSlots(3, 0, 1));
        // 在途超过配置 → 0，不为负
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(1, 3, 5));
        // 防御：负数输入不产生负槽位/超发
        assertEquals(1, ServerFieldTasks.intelRemainingNormalSlots(1, -2, 3));
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(-1, 0, 3));
    }

    @Test
    public void remainingNormalSlots_unknownIdleFallback() {
        // 队列空闲未知（资产库未接线）：只扣在途情报（旧口径），不因采集占满而卡死营救
        assertEquals(1, ServerFieldTasks.intelRemainingNormalSlots(1, 0));
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(1, 2));
        assertEquals(2, ServerFieldTasks.intelRemainingNormalSlots(3, 1));
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(0, 0));
    }

    // ==================== 单事件派队门控 ====================

    @Test
    public void gate_rescueNotBlockedByFullQueue() {
        // 核心修复点：普通队列满（0 空闲）时营救仍可派（不检查队列）
        assertEquals(ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(2, 0, true, 500L));
        // 体力不足时营救才会等待
        assertEquals(ServerFieldTasks.INTEL_GATE_STAMINA,
                ServerFieldTasks.intelDispatchGate(2, 0, true, 5L));   // 营救耗 12 点
    }

    @Test
    public void gate_monsterNeedsNormalSlot() {
        assertEquals(ServerFieldTasks.INTEL_GATE_QUEUE_FULL,
                ServerFieldTasks.intelDispatchGate(1, 0, true, 500L));
        assertEquals(ServerFieldTasks.INTEL_GATE_QUEUE_FULL,
                ServerFieldTasks.intelDispatchGate(11, 0, true, 500L));
        assertEquals(ServerFieldTasks.INTEL_GATE_QUEUE_FULL,
                ServerFieldTasks.intelDispatchGate(100, 0, true, 500L));
        // 有 1 队空闲 → 怪物可派
        assertEquals(ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(1, 1, true, 500L));
        // 队列优先于体力：队列满时文案应是“队列已满”而不是“体力不足”
        assertEquals(ServerFieldTasks.INTEL_GATE_QUEUE_FULL,
                ServerFieldTasks.intelDispatchGate(1, 0, true, 9L));
        // 体力不足：怪物 10 点
        assertEquals(ServerFieldTasks.INTEL_GATE_STAMINA,
                ServerFieldTasks.intelDispatchGate(1, 1, true, 9L));
    }

    @Test
    public void gate_pveAndUnknownStamina() {
        // 英雄之旅不占普通队列，队列满也能打（只受体力约束）
        assertEquals(ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(3, 0, true, 500L));
        // 体力数据未到（notify_recover_resource 未同步）：只做队列门，不阻塞派队
        assertEquals(ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(1, 1, false, 0L));
        assertEquals(ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(2, 0, false, 0L));
    }

    @Test
    public void gate_constantsAreDistinct() {
        // 文案分流依赖三个常数互异（队列已满 / 体力不足 / 可派）
        assertNotEquals(ServerFieldTasks.INTEL_GATE_OK, ServerFieldTasks.INTEL_GATE_QUEUE_FULL);
        assertNotEquals(ServerFieldTasks.INTEL_GATE_OK, ServerFieldTasks.INTEL_GATE_STAMINA);
        assertNotEquals(ServerFieldTasks.INTEL_GATE_QUEUE_FULL, ServerFieldTasks.INTEL_GATE_STAMINA);
    }

    // ==================== 队列快照（引擎不可用时兜底） ====================

    @Test
    public void queueSnapshot_nullEngineFallsBackWithoutThrowing() {
        ServerFieldTasks.IntelQueueSnapshot snap = ServerFieldTasks.intelQueueSnapshot(null);
        assertEquals("无引擎不猜容量", 0, snap.capacity);
        assertEquals(0, snap.activeIntelligenceMarches);
        assertEquals("无在途普通情报(302)", 0, snap.activeNormalIntelMarches);
        assertEquals("无在途特殊情报(301)", 0, snap.activeSpecialIntelMarches);
        assertFalse("无队列数据 → 空闲未知，走只扣在途的兜底口径", snap.queueIdleKnown);
        assertEquals(0, snap.normalIdle);
    }

    @Test
    public void snapshot_activeIntelligence_isSplitNormalAndSpecial() {
        // 在途 301 营救 + 302 狩猎：普通只认 302，营救单独展示
        ServerFieldTasks.IntelQueueSnapshot snap =
                new ServerFieldTasks.IntelQueueSnapshot(3, 1, 2, true, 2);
        assertEquals(3, snap.activeIntelligenceMarches);
        assertEquals(1, snap.activeNormalIntelMarches);
        assertEquals(2, snap.activeSpecialIntelMarches);
    }

    @Test
    public void gatherPriority_excludesRescueAndPve_includesHunts() {
        java.util.Map<Long, com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent> events =
                new java.util.LinkedHashMap<>();
        // 营救（event_id 2001 → type 2）：不占普通队列，采集不让位
        events.put(1L, new com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent(
                1L, 2001L, 1L, 1L, 1L, 9_999_999_999L, false));
        // 英雄之旅（code 3001 → type 3）：PVE，不让位
        events.put(2L, new com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent(
                2L, 3001L, 1L, 1L, 1L, 9_999_999_999L, false));
        com.carlos.vault.wjdr.asset.AssetSnapshot.Intel nonHunts =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Intel(0, 0, 0, 0, events, 0L);
        com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina st100 =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina(100, 200, 0, 0, 0);
        assertEquals(0, ServerFieldTasks.intelGatherPriorityCount(nonHunts, st100, 1000L));
        // 活动巨兽（code 11001 → type 11）占普通队列 → 计 1
        events.put(3L, new com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent(
                3L, 11001L, 1L, 1L, 1L, 9_999_999_999L, false));
        com.carlos.vault.wjdr.asset.AssetSnapshot.Intel withHunt =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Intel(0, 0, 0, 0, events, 0L);
        assertEquals(1, ServerFieldTasks.intelGatherPriorityCount(withHunt, st100, 1000L));
    }

    @Test
    public void queueSlots_unchangedContract() {
        assertEquals(1, ServerFieldTasks.intelQueueSlots(1, 3));
        assertEquals(3, ServerFieldTasks.intelQueueSlots(4, 3));
        assertEquals(2, ServerFieldTasks.intelQueueSlots(2, 0));
        assertEquals(6, ServerFieldTasks.intelQueueSlots(99, 10));
    }

    @Test
    public void defaultQueueCount_isOneLikeWeb() {
        assertEquals(1, ServerFieldTasks.intelQueueCount(null));
        assertEquals(1, ServerFieldTasks.intelQueueCount(new JSONObject()));
        assertEquals(2, ServerFieldTasks.intelQueueCount(new JSONObject().put("queue_count", 2)));
    }
}
