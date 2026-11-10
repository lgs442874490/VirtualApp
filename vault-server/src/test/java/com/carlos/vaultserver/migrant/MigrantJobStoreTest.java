package com.carlos.vaultserver.migrant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 任务仓落盘/恢复回归：{@code data/migrant.json} 原子写 + .bak 回落，
 * 重启后 WAITING/PREPARING 恢复调度，FIRING 不能证明结果 → UNKNOWN。
 */
public class MigrantJobStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void flushThenLoadRestoresJobAndAttempts() throws Exception {
        Path dir = folder.newFolder("data").toPath();
        MigrantJobStore store = new MigrantJobStore(dir);
        store.load();

        long freedomStart = System.currentTimeMillis() + 3_600_000L;
        MigrantJob job = MigrantTestSupport.job("job-1", "f1", 888L, 1001L, freedomStart);
        job.status = MigrantJob.PREPARING;
        job.preparationStartedAtMillis = 123L;
        job.responseCode = 0;
        job.speedupTier2Used = 3;
        MigrantJob.Attempt attempt = new MigrantJob.Attempt();
        attempt.attemptNo = 1;
        attempt.sentAtMillis = 111L;
        attempt.responseAtMillis = 222L;
        attempt.serverNowMillis = 150L;
        attempt.serverClockReady = true;
        attempt.outcome = "accepted";
        attempt.responseCode = 0;
        attempt.requestText = "req_migrant_migrate kid=1001";
        job.addAttempt(attempt);
        store.put(job);
        store.flush();

        assertTrue("落盘文件存在", Files.isRegularFile(dir.resolve("migrant.json")));

        MigrantJobStore reloaded = new MigrantJobStore(dir);
        reloaded.load();
        assertEquals(1, reloaded.count());
        MigrantJob restored = reloaded.get("job-1");
        assertNotNull(restored);
        assertEquals(MigrantJob.PREPARING, restored.status);
        assertEquals(888L, restored.sid);
        assertEquals(1001L, restored.uid);
        assertEquals(freedomStart, restored.freedomStartMillis);
        assertEquals(123L, restored.preparationStartedAtMillis);
        assertEquals(Integer.valueOf(0), restored.responseCode);
        assertEquals(3, restored.speedupTier2Used);
        assertEquals(1, restored.attemptsSnapshot().size());
        assertEquals(111L, restored.attemptsSnapshot().get(0).sentAtMillis);
        assertTrue(restored.precheck.getBoolean("ready"));
    }

    @Test
    public void loadFallsBackToBackupWhenMainCorrupt() throws Exception {
        Path dir = folder.newFolder("data2").toPath();
        MigrantJobStore store = new MigrantJobStore(dir);
        store.load();
        store.put(MigrantTestSupport.job("job-bak", "f1", 888L, 1001L,
                System.currentTimeMillis() + 3_600_000L));
        store.flush();

        // 第一次 flush 没产生 .bak；再改一次触发备份
        MigrantJob job = store.get("job-bak");
        job.error = "第二次写盘";
        store.markDirty();
        store.flush();
        assertTrue("第二次写盘后应有 .bak", Files.isRegularFile(dir.resolve("migrant.json.bak")));

        Files.write(dir.resolve("migrant.json"), "not a json".getBytes(StandardCharsets.UTF_8));

        MigrantJobStore reloaded = new MigrantJobStore(dir);
        reloaded.load();
        assertEquals("主文件损坏时应回落 .bak", 1, reloaded.count());
        assertNotNull(reloaded.get("job-bak"));
    }

    @Test
    public void unknownStatusNormalizedToUnknownOnLoad() throws Exception {
        Path dir = folder.newFolder("data3").toPath();
        Files.write(dir.resolve("migrant.json"),
                ("{\"version\":1,\"jobs\":[{\"job_id\":\"j\",\"status\":\"SENDING\","
                        + "\"fpid\":\"f1\",\"server_id\":888,\"uid\":1001}]}")
                        .getBytes(StandardCharsets.UTF_8));
        MigrantJobStore store = new MigrantJobStore(dir);
        store.load();
        assertEquals(MigrantJob.UNKNOWN, store.get("j").status);
    }

    @Test
    public void removeDropsJob() throws Exception {
        Path dir = folder.newFolder("data4").toPath();
        MigrantJobStore store = new MigrantJobStore(dir);
        store.load();
        store.put(MigrantTestSupport.job("j-rm", "f1", 888L, 1001L, 1L));
        assertNotNull(store.get("j-rm"));
        assertTrue(store.remove("j-rm"));
        assertNull(store.get("j-rm"));
    }

    /** 重启恢复：FIRING → UNKNOWN（禁止自动重发）；窗口已过 → UNKNOWN；窗口内 → WAITING。 */
    @Test
    public void restoreStatusRules() {
        long now = System.currentTimeMillis();

        MigrantJob firing = MigrantTestSupport.job("j-firing", "f1", 888L, 1001L,
                now + 3_600_000L);
        firing.status = MigrantJob.FIRING;
        firing.attemptCount = 7;
        assertTrue(MigrantEngine.restoreStatus(firing, now));
        assertEquals("发射/对账阶段重启不能猜结果", MigrantJob.UNKNOWN, firing.status);
        assertTrue(firing.finishedAtMillis > 0L);
        assertTrue(firing.error.contains("无法证明请求结果"));

        MigrantJob waiting = MigrantTestSupport.job("j-waiting", "f1", 888L, 1001L,
                now + 3_600_000L);
        assertTrue(MigrantEngine.restoreStatus(waiting, now));
        assertEquals("窗口内恢复为 WAITING", MigrantJob.WAITING, waiting.status);
        assertEquals(0L, waiting.preparationStartedAtMillis);

        MigrantJob expired = MigrantTestSupport.job("j-expired", "f1", 888L, 1001L, now - 1000L);
        expired.freedomEndMillis = now - 1L;
        assertTrue(MigrantEngine.restoreStatus(expired, now));
        assertEquals("已过窗口且无结果 → UNKNOWN", MigrantJob.UNKNOWN, expired.status);

        MigrantJob done = MigrantTestSupport.job("j-done", "f1", 888L, 1001L, now + 1000L);
        done.finish(MigrantJob.SUCCEEDED, "");
        assertTrue("终态不改动", !MigrantEngine.restoreStatus(done, now));
        assertEquals(MigrantJob.SUCCEEDED, done.status);
    }
}
