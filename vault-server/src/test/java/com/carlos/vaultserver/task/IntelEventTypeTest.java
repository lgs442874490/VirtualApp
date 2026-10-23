package com.carlos.vaultserver.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 情报事件类型解析回归（1:1 网页版 DailyIntelligenceEventCatalog.resolveType）：
 * 生产根因是“只读 notify_intelligence_events + 把消息 type 字段当事件类型 + type 映射相反”。
 */
public class IntelEventTypeTest {

    @Test
    public void resolvesByEventConfigIdRanges() {
        assertEquals(11, ServerFieldTasks.intelEventType(11001L));   // 活动巨兽下界
        assertEquals(11, ServerFieldTasks.intelEventType(11010L));   // 活动巨兽上界
        assertEquals(100, ServerFieldTasks.intelEventType(100000L)); // 冒险巨兽
        assertEquals(100, ServerFieldTasks.intelEventType(100200L));

        assertEquals(2, ServerFieldTasks.intelEventType(4001L));     // 营救幸存者
        assertEquals(2, ServerFieldTasks.intelEventType(2001L));
        assertEquals(2, ServerFieldTasks.intelEventType(2050L));
        assertEquals(2, ServerFieldTasks.intelEventType(6001L));

        assertEquals(3, ServerFieldTasks.intelEventType(3001L));     // 英雄之旅（PVE）
        assertEquals(3, ServerFieldTasks.intelEventType(3050L));
        assertEquals(3, ServerFieldTasks.intelEventType(7001L));
        assertEquals(3, ServerFieldTasks.intelEventType(7050L));

        assertEquals(1, ServerFieldTasks.intelEventType(1001L));     // 怪物情报
        assertEquals(1, ServerFieldTasks.intelEventType(1050L));
        assertEquals(1, ServerFieldTasks.intelEventType(4002L));
        assertEquals(1, ServerFieldTasks.intelEventType(5001L));
        assertEquals(1, ServerFieldTasks.intelEventType(8001L));

        assertEquals(0, ServerFieldTasks.intelEventType(0L));        // 未知
        assertEquals(0, ServerFieldTasks.intelEventType(999L));
        assertEquals(0, ServerFieldTasks.intelEventType(2000L));
    }

    @Test
    public void worldMarchTypesAreOneTwoElevenHundred() {
        assertTrue(ServerFieldTasks.intelIsWorldMarch(1));
        assertTrue(ServerFieldTasks.intelIsWorldMarch(2));
        assertTrue(ServerFieldTasks.intelIsWorldMarch(11));
        assertTrue(ServerFieldTasks.intelIsWorldMarch(100));
        assertFalse(ServerFieldTasks.intelIsWorldMarch(3));   // 英雄之旅走 PVE，不是世界行军
        assertFalse(ServerFieldTasks.intelIsWorldMarch(0));
    }
}
