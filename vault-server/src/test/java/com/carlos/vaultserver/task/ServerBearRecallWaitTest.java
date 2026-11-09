package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * 巨熊召回等待（2026-11-08，对齐网页版 DailyBearActionService.recallPending）：
 * 召回行军是异步的，全部回城前不得发车（否则 403 英雄占用、且旧实现 60s 盲重试同编队一次后封板）。
 */
public class ServerBearRecallWaitTest {

    @Test
    public void parseIds_toleratesCsvAndGarbage() {
        Set<Long> ids = ServerBearTasks.parseIds("12, 34,,abc,-5,0");
        assertEquals(2, ids.size());
        assertTrue(ids.contains(12L));
        assertTrue(ids.contains(34L));
        assertTrue(ServerBearTasks.parseIds("").isEmpty());
        assertTrue(ServerBearTasks.parseIds(null).isEmpty());
    }

    @Test
    public void joinIds_skipsInvalid() {
        assertEquals("11,22", ServerBearTasks.joinIds(Arrays.asList(11L, null, 22L, 0L, -3L)));
        assertEquals("", ServerBearTasks.joinIds(Collections.<Long>emptyList()));
        assertEquals("", ServerBearTasks.joinIds(null));
    }

    @Test
    public void pendingRecallIds_nullSafe() {
        assertTrue(ServerBearTasks.pendingRecallIds(null, null, null).isEmpty());
    }
}
