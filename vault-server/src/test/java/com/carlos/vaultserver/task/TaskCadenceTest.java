package com.carlos.vaultserver.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 任务节奏表（网页版 forTask 1:1 映射）自检：
 * 覆盖全部服务端任务、间隔取网页版语义、下限与兜底值生效。
 */
public class TaskCadenceTest {

    @Test
    public void cadenceCoversEveryServerTask() {
        // 服务端 55 项任务都必须在节奏表里，否则新增任务只会在整轮里跑、节奏调度漏排。
        assertTrue("节奏表缺任务：" + TaskCadenceScheduler.missingTaskIds(),
                TaskCadenceScheduler.missingTaskIds().isEmpty());
        assertEquals(ServerTasks.all().size(), TaskCadence.size());
    }

    @Test
    public void intervalsFollowWebVersionTable() {
        // 网页版 AbstractDailyAutomationTaskDefinition.forTask 的逐项值
        assertEquals(180L, TaskCadence.intervalSeconds("mail"));            // mailbox-rewards 180
        assertEquals(3600L, TaskCadence.intervalSeconds("adventure"));      // adventure-rewards 3600
        assertEquals(180L, TaskCadence.intervalSeconds("activity_rewards"));// activity-rewards 180
        assertEquals(120L, TaskCadence.intervalSeconds("alliance_chest"));  // alliance-chests 120
        assertEquals(30L, TaskCadence.intervalSeconds("alliance_donate"));  // alliance-donation 30
        assertEquals(300L, TaskCadence.intervalSeconds("login_rewards"));   // login-rewards 300
        assertEquals(60L, TaskCadence.intervalSeconds("shop"));             // shops 60
        assertEquals(300L, TaskCadence.intervalSeconds("fire_crystal"));    // fire-crystal-refine 300
    }

    @Test
    public void fastWebTasksClampedToSafetyFloor() {
        // 网页版 2-10 秒级任务（target/balloon、bear、gather、intel）抬到 15 秒下限
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("act_balloon"));
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("bear"));
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("gather"));
        assertEquals(TaskCadence.MIN_INTERVAL_SECONDS, TaskCadence.intervalSeconds("intel"));
    }

    @Test
    public void unknownTaskUsesFallback() {
        assertEquals(TaskCadence.FALLBACK_INTERVAL_SECONDS, TaskCadence.intervalSeconds("not_a_task"));
        assertEquals(false, TaskCadence.contains(null));
    }
}
