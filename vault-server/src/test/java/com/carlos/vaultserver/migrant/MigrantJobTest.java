package com.carlos.vaultserver.migrant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 任务状态机回归（契约 §5）：WAITING → PREPARING → ARMED → FIRING → 终态；
 * 仅 WAITING 可取消；终态 = SUCCEEDED / REJECTED / UNKNOWN / CANCELLED。
 */
public class MigrantJobTest {

    @Test
    public void activeAndTerminalSets() {
        assertTrue(MigrantJob.isActive(MigrantJob.WAITING));
        assertTrue(MigrantJob.isActive(MigrantJob.PREPARING));
        assertTrue(MigrantJob.isActive(MigrantJob.ARMED));
        assertTrue(MigrantJob.isActive(MigrantJob.FIRING));
        assertFalse(MigrantJob.isActive(MigrantJob.SUCCEEDED));

        assertTrue(MigrantJob.isTerminal(MigrantJob.SUCCEEDED));
        assertTrue(MigrantJob.isTerminal(MigrantJob.REJECTED));
        assertTrue(MigrantJob.isTerminal(MigrantJob.UNKNOWN));
        assertTrue(MigrantJob.isTerminal(MigrantJob.CANCELLED));
        assertFalse(MigrantJob.isTerminal(MigrantJob.WAITING));
    }

    @Test
    public void onlyWaitingIsCancelable() {
        assertTrue(MigrantJob.isCancelable(MigrantJob.WAITING));
        assertFalse(MigrantJob.isCancelable(MigrantJob.PREPARING));
        assertFalse(MigrantJob.isCancelable(MigrantJob.ARMED));
        assertFalse(MigrantJob.isCancelable(MigrantJob.FIRING));
        assertFalse(MigrantJob.isCancelable(MigrantJob.SUCCEEDED));
    }

    @Test
    public void statusLabelsAreChinese() {
        assertEquals("等待开放", MigrantJob.labelOf(MigrantJob.WAITING));
        assertEquals("刷新预检", MigrantJob.labelOf(MigrantJob.PREPARING));
        assertEquals("已就绪", MigrantJob.labelOf(MigrantJob.ARMED));
        assertEquals("正在提交", MigrantJob.labelOf(MigrantJob.FIRING));
        assertEquals("移民成功", MigrantJob.labelOf(MigrantJob.SUCCEEDED));
        assertEquals("未执行/被拒绝", MigrantJob.labelOf(MigrantJob.REJECTED));
        assertEquals("结果待人工核验", MigrantJob.labelOf(MigrantJob.UNKNOWN));
        assertEquals("已取消", MigrantJob.labelOf(MigrantJob.CANCELLED));
    }

    @Test
    public void normalizeStatusFallsBackToUnknown() {
        assertEquals(MigrantJob.WAITING, MigrantJob.normalizeStatus("waiting"));
        assertEquals(MigrantJob.SUCCEEDED, MigrantJob.normalizeStatus(" SUCCEEDED "));
        assertEquals(MigrantJob.UNKNOWN, MigrantJob.normalizeStatus("SENDING"));
        assertEquals(MigrantJob.UNKNOWN, MigrantJob.normalizeStatus(null));
        assertEquals(MigrantJob.UNKNOWN, MigrantJob.normalizeStatus(""));
    }

    @Test
    public void activeToFiringToTerminal() {
        MigrantJob job = MigrantTestSupport.job("j1", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        assertTrue(job.active());
        assertTrue(job.cancelable());

        job.status = MigrantJob.PREPARING;
        assertTrue(job.active());
        assertFalse(job.cancelable());

        job.status = MigrantJob.ARMED;
        assertTrue(job.active());

        job.status = MigrantJob.FIRING;
        assertTrue(job.active());
        assertFalse(job.isCancelable(job.status));

        job.finish(MigrantJob.SUCCEEDED, "");
        assertTrue(job.active() == false);
        assertTrue("终态", MigrantJob.isTerminal(job.status));
        assertTrue("终态必须写 finishedAtMillis", job.finishedAtMillis > 0L);
        assertTrue(job.updatedAtMillis >= job.finishedAtMillis);
    }

    @Test
    public void jsonRoundTripKeepsFieldsAndAttempts() {
        MigrantJob job = MigrantTestSupport.job("j2", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        job.advanceMillis = 80;
        job.responseCode = 12044;
        job.exitAllianceMessage = "已退出联盟";
        job.speedupDiamondUsed = 2;
        job.fireDoneAtMillis = 1_700_000_000_500L;
        job.reconcileDeadlineMillis = 1_700_000_090_500L;
        job.successCandidate = true;
        job.transportFailure = true;
        job.nextReverifyAtMillis = 1_700_000_180_500L;
        job.reverifyIndex = 1;

        MigrantJob.Attempt attempt = new MigrantJob.Attempt();
        attempt.attemptNo = 1;
        attempt.sentAtMillis = 1_700_000_000_000L;
        attempt.hostSentAtMillis = 1_699_996_400_000L;
        attempt.phase = "BURST";
        attempt.phaseLabel = "抢位爆发";
        attempt.responseAtMillis = 1_700_000_000_100L;
        attempt.serverNowMillis = 1_700_000_000_050L;
        attempt.serverClockReady = true;
        attempt.outcome = "rejected";
        attempt.responseCode = 7401;
        attempt.requestText = "req_migrant_migrate kid=1001";
        attempt.responseText = "{code=7401}";
        job.addAttempt(attempt);

        MigrantJob restored = MigrantJob.fromJson(job.toStoreJson());
        assertEquals("j2", restored.jobId);
        assertEquals("f1", restored.fpid);
        assertEquals(888L, restored.sid);
        assertEquals(1001L, restored.uid);
        assertEquals(MigrantJob.WAITING, restored.status);
        assertEquals(80, restored.advanceMillis);
        assertEquals(Integer.valueOf(12044), restored.responseCode);
        assertEquals("已退出联盟", restored.exitAllianceMessage);
        assertEquals(2, restored.speedupDiamondUsed);
        assertEquals(1_700_000_000_500L, restored.fireDoneAtMillis);
        assertEquals(1_700_000_090_500L, restored.reconcileDeadlineMillis);
        assertTrue(restored.successCandidate);
        assertTrue(restored.transportFailure);
        assertEquals(1_700_000_180_500L, restored.nextReverifyAtMillis);
        assertEquals(1, restored.reverifyIndex);
        assertEquals(1, restored.attemptsSnapshot().size());
        MigrantJob.Attempt restoredAttempt = restored.attemptsSnapshot().get(0);
        assertEquals(1, restoredAttempt.attemptNo);
        assertEquals(Integer.valueOf(7401), restoredAttempt.responseCode);
        assertTrue(restoredAttempt.serverClockReady);
        assertEquals(1_699_996_400_000L, restoredAttempt.hostSentAtMillis);
        assertEquals("BURST", restoredAttempt.phase);
        assertEquals("抢位爆发", restoredAttempt.phaseLabel);
        assertNotNull(restored.precheck);
    }

    @Test
    public void jsonResponseExposesWebFields() {
        MigrantJob job = MigrantTestSupport.job("j3", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L);
        org.json.JSONObject json = job.toJson();
        assertEquals("j3", json.getString("job_id"));
        assertEquals(MigrantJob.WAITING, json.getString("status"));
        assertEquals("等待开放", json.getString("status_label"));
        assertTrue(json.getBoolean("active"));
        assertTrue(json.getBoolean("cancelable"));
        assertEquals(1001L, json.getLong("account_role_id"));
        assertEquals(1001, json.getInt("target_kid"));
        assertEquals(200, json.getInt("max_attempts"));
        assertEquals(888L, json.getLong("server_id"));
        assertTrue(json.has("precheck"));
        assertTrue("落盘 JSON 额外带 attempts", job.toStoreJson().has("attempts"));
    }
}
