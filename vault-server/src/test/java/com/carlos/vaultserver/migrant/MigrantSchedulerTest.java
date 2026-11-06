package com.carlos.vaultserver.migrant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 1 秒调度扫描回归：准备到点 / 发射到点 / 窗口过期收口。
 *
 * <p>不注入 SessionKeeper（角色恒离线）：验证调度器<b>只做状态推进与重试节流</b>，
 * 不直接发协议、不猜结果。
 */
public class MigrantSchedulerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MigrantScheduler scheduler(MigrantJobStore jobs, MigrantEngine engine) {
        return new MigrantScheduler(null, null, jobs, engine);
    }

    @Test
    public void waitingJobTurnsPreparingWhenOffline_andThrottlesRetry() throws Exception {
        Path dir = folder.newFolder("d1").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);

        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("s1", "f1", 888L, 1001L, now + 60_000L);
        job.preparationStartMillis = now - 1000L;
        jobs.put(job);

        MigrantScheduler scheduler = scheduler(jobs, engine);
        scheduler.tick();
        assertEquals("到准备点且角色离线 → 进入 PREPARING 等上线",
                MigrantJob.PREPARING, job.status);
        assertTrue(job.error.contains("上线"));
        assertTrue("离线重试有退避，不能每 1s 占会话线程",
                job.prepareRetryAtMillis > System.currentTimeMillis());

        scheduler.tick();
        assertEquals("退避窗口内不重复投递", MigrantJob.PREPARING, job.status);
    }

    @Test
    public void pastFreedomEndMarksUnknown() throws Exception {
        Path dir = folder.newFolder("d2").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);

        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("s2", "f1", 888L, 1001L, now - 3_600_000L);
        job.freedomEndMillis = now - 1000L;
        jobs.put(job);

        scheduler(jobs, engine).tick();
        assertEquals("已过窗口无结果 → UNKNOWN", MigrantJob.UNKNOWN, job.status);
        assertTrue(job.error.contains("人工核验"));
        assertFalse(MigrantJob.isActive(job.status));
    }

    @Test
    public void armedJobTurnsFiringAtBoundary_evenIfOffline() throws Exception {
        Path dir = folder.newFolder("d3").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);

        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("s3", "f1", 888L, 1001L, now + 1000L);
        job.status = MigrantJob.ARMED;
        job.advanceMillis = 0;
        jobs.put(job);

        scheduler(jobs, engine).tick();
        assertEquals("到发射点（含 2s 余量）→ FIRING", MigrantJob.FIRING, job.status);
        assertEquals("尚未发出请求", 0L, job.sentAtMillis);
        assertTrue(job.error.contains("上线"));
        assertTrue(MigrantJob.isActive(job.status));
    }

    @Test
    public void firingAfterBurstKeepsReconcilingInSchedulerRounds() throws Exception {
        Path dir = folder.newFolder("d6").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);

        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("s6", "f1", 888L, 1001L, now + 3_600_000L);
        job.status = MigrantJob.FIRING;
        job.fireDoneAtMillis = now - 1_000L;
        job.reconcileDeadlineMillis = now + MigrantRules.RECONCILE_WINDOW_MILLIS;
        job.reconcileAttemptAtMillis = 0L;
        jobs.put(job);

        scheduler(jobs, engine).tick();
        assertEquals("对账窗口内不提前收口（P1-4 分轮）", MigrantJob.FIRING, job.status);
    }

    @Test
    public void firingPastReconcileDeadlineFallsUnknown() throws Exception {
        Path dir = folder.newFolder("d7").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);

        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("s7", "f1", 888L, 1001L, now + 3_600_000L);
        job.status = MigrantJob.FIRING;
        job.fireDoneAtMillis = now - MigrantRules.RECONCILE_WINDOW_MILLIS - 1_000L;
        job.reconcileDeadlineMillis = now - 1_000L;
        job.reconciledKid = 0L;
        jobs.put(job);

        scheduler(jobs, engine).tick();
        assertEquals("90s 窗口结束仍无结果 → UNKNOWN", MigrantJob.UNKNOWN, job.status);
        assertTrue(job.error.contains("对账窗口"));
        assertTrue("UNKNOWN 且未过活动窗口应安排 +3min 复核",
                job.nextReverifyAtMillis > 0L);
        assertEquals(0, job.reverifyIndex);
    }

    @Test
    public void unknownJobReverifyTickIsSafeWhenOffline() throws Exception {
        Path dir = folder.newFolder("d5").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);

        long now = System.currentTimeMillis();
        MigrantJob job = MigrantTestSupport.job("s5", "f1", 888L, 1001L, now + 3_600_000L);
        job.status = MigrantJob.UNKNOWN;
        job.reverifyIndex = 0;
        job.nextReverifyAtMillis = now - 1L;
        jobs.put(job);

        scheduler(jobs, engine).tick();
        // 角色离线（未注入 SessionKeeper）：不得抛异常/不得改终态，下一拍继续尝试
        assertEquals(MigrantJob.UNKNOWN, job.status);
        assertTrue(job.nextReverifyAtMillis > 0L);
    }

    @Test
    public void startStopIsIdempotent() throws Exception {
        Path dir = folder.newFolder("d4").toPath();
        MigrantJobStore jobs = new MigrantJobStore(dir);
        jobs.load();
        MigrantEngine engine = new MigrantEngine(null, null, jobs, null);
        MigrantScheduler scheduler = scheduler(jobs, engine);
        scheduler.start();
        assertTrue(scheduler.isRunning());
        scheduler.start();
        scheduler.stop();
        assertFalse(scheduler.isRunning());
        scheduler.stop();
    }
}
