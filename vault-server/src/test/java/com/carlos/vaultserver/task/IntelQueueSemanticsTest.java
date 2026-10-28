package com.carlos.vaultserver.task;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 情报普通队列语义单测（P0 2026-10-28，bug 2「情报只会做营救类，不会打狩猎类」）。
 *
 * <p>基准：网页版 {@code DailyIntelligenceService.consumesNormalWorldMarchSlot:614-616}
 * （type!=2 && isWorldMarch(type)）与 {@code planActions:452-525}
 * （remaining=clamp(min(queueCount,playerQueueLimit)-active(301|302),0,world.idle())，
 * 仅普通队列事件检查/扣减 remaining）。
 *
 * <p>锁死三件事，防止回归到「所有世界行军一律要 freeSlots → 采集占满时营救照发、怪物永不发」：
 * <ol>
 *   <li>营救(type=2) 不占普通队列：队列满时仍必须可派；</li>
 *   <li>怪物(1)/活动巨兽(11)/冒险巨兽(100) 占普通队列：队列满时必须等待；</li>
 *   <li>剩余槽位按「配置/容量 - 在途情报」并受世界空闲上界钳制，type2 不扣减。</li>
 * </ol>
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
        // 已有 1 队情报在途（301/302 合计）→ 0（网页版 activeNormalMarches 扣减）
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
        assertFalse("无队列数据 → 空闲未知，走只扣在途的兜底口径", snap.queueIdleKnown);
        assertEquals(0, snap.normalIdle);
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
