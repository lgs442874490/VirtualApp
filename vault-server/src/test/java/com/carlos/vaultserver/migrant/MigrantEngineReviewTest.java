package com.carlos.vaultserver.migrant;

import com.carlos.vault.wjdr.GameServerClock;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-06 代码审查修复项回归：
 * P0-2 服务器钟域 / P1-2 时钟未校准 / P1-3 attempt 历史恢复 / P1-4 分轮辅助 /
 * P2-1 钻石额度 / P2-2 抢位阶段 / P2-4 预检选项 / P2-9 target_kid 上限。
 */
public class MigrantEngineReviewTest {

    /** 校准到「本机时间 + 1 小时」的游戏服时钟（模拟角色服务器钟）。 */
    private GameServerClock calibratedClock(long offsetMillis) {
        GameServerClock clock = new GameServerClock();
        long targetMillis = System.currentTimeMillis() + offsetMillis;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("server_time", targetMillis / 1000L);
        clock.calibratePush(content, GameServerClock.monoNow());
        return clock;
    }

    @Test
    public void firstAttemptSentAt_usesServerDomainAndSatisfiesV2() {
        GameServerClock clock = calibratedClock(3_600_000L);
        assertTrue("时钟应已校准", clock.locked());

        long freedomStart = clock.nowServerMs() + 60_000L;
        int advance = 80;
        long jobSentAt = freedomStart - advance;   // 边界前 advance 开始发请求
        long sent = MigrantEngine.attemptSentAtMillis(clock, jobSentAt, 1);
        assertEquals("首条 attempt 必须复用任务 sent_at（服务器钟域）", jobSentAt, sent);
        long offset = sent - freedomStart;
        assertTrue("V2：首发偏差必须 ∈ [-advance, 50]，实际 " + offset,
                offset >= -advance && offset <= 50);

        // 非首条必须取角色游戏服时钟，不能是主机墙钟（本机与服务器差 1 小时）
        long later = MigrantEngine.attemptSentAtMillis(clock, jobSentAt, 2);
        long hostDelta = later - System.currentTimeMillis();
        assertTrue("非首条 sent_at 必须落在服务器钟域（≈+1h），实际差 " + hostDelta,
                Math.abs(hostDelta - 3_600_000L) < 10_000L);
    }

    @Test
    public void attemptPhaseBucketsAndLabels() {
        assertEquals("BURST", MigrantEngine.attemptPhase(0L));
        assertEquals("BURST", MigrantEngine.attemptPhase(4_999L));
        assertEquals("FOLLOW_UP", MigrantEngine.attemptPhase(5_000L));
        assertEquals("FOLLOW_UP", MigrantEngine.attemptPhase(9_999L));
        assertEquals("FALLBACK", MigrantEngine.attemptPhase(10_000L));
        assertEquals("抢位爆发", MigrantEngine.attemptPhaseLabel("BURST"));
        assertEquals("持续跟进", MigrantEngine.attemptPhaseLabel("FOLLOW_UP"));
        assertEquals("兜底重试", MigrantEngine.attemptPhaseLabel("FALLBACK"));
    }

    @Test
    public void restoreWithAttemptHistoryNeverReturnsToWaiting() {
        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("rev-1", "f1", 888L, 1001L, now + 3_600_000L);
        MigrantJob.Attempt attempt = new MigrantJob.Attempt();
        attempt.attemptNo = 1;
        attempt.sentAtMillis = now - 1000L;
        attempt.responseCode = 7401;
        attempt.outcome = "rejected";
        job.addAttempt(attempt);
        // 即使状态被归一为 WAITING，已有真实请求历史也必须 UNKNOWN（不得重发）
        job.status = MigrantJob.WAITING;
        assertTrue(MigrantEngine.restoreStatus(job, now));
        assertEquals(MigrantJob.UNKNOWN, job.status);
        assertTrue(job.error.contains("禁止自动重发"));
    }

    @Test
    public void precheckOptionWarningsMatchExecutionPolicy() {
        List<String> none = MigrantEngine.precheckOptionWarnings(true, true, 0);
        assertTrue("全开且不提前 → 无额外警告", none.isEmpty());

        List<String> noRecall = MigrantEngine.precheckOptionWarnings(false, true, 40);
        assertEquals(3, noRecall.size());
        assertTrue(noRecall.get(0).contains("未开启开放前召回"));
        assertTrue(noRecall.get(1).contains("加速设置不会执行"));
        assertTrue(noRecall.get(2).contains("提前 40 毫秒"));
    }

    @Test
    public void targetKidMustFitIntRange() {
        try {
            MigrantEngine.checkedTargetKid(0L);
            org.junit.Assert.fail("0 应报错");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("请选择目标王国"));
        }
        try {
            MigrantEngine.checkedTargetKid((long) Integer.MAX_VALUE + 1L);
            org.junit.Assert.fail("超 int 应报错");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("超出范围"));
        }
        assertEquals(1001, MigrantEngine.checkedTargetKid(1001L));
    }

    @Test
    public void diamondBudgetIsTenPerJob() {
        assertTrue(MigrantEngine.diamondBudgetAvailable(0));
        assertTrue(MigrantEngine.diamondBudgetAvailable(9));
        assertFalse(MigrantEngine.diamondBudgetAvailable(10));
        assertFalse(MigrantEngine.diamondBudgetAvailable(11));
    }

    @Test
    public void uncalibratedClockReportsNotLocked() {
        GameServerClock clock = new GameServerClock();
        assertFalse("未校准 → 引擎不得发射", clock.locked());
        assertFalse(MigrantEngine.diamondBudgetAvailable(10));
    }
}
