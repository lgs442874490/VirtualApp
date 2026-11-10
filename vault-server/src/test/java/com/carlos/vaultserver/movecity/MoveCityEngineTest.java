package com.carlos.vaultserver.movecity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A3–A7：迁城引擎行为（1:1 对齐原版 MoveCityService 语义与文案）。
 *
 * <p>时间线测试用注入参数的引擎（原版 @Value wjdr.movecity.* 可配语义的等价物）：
 * retry=1ms / prewarm=30ms / prepareBefore=200ms / protection±=800ms，定时任务
 * executeAt=now+600ms → +200ms 准备、+600ms 释放，真实墙钟等待 ~2s 内完成。
 */
public class MoveCityEngineTest {

    // ---------------- 假传输 ----------------

    static final class FakeTransport implements MoveCityTransport {
        final Map<String, Boolean> online = new ConcurrentHashMap<>();
        final Map<String, Boolean> exists = new ConcurrentHashMap<>();
        final Map<String, ArrayDeque<Integer>> codesByFpid = new ConcurrentHashMap<>();
        // 并发安全：引擎按账号并发发请求，测试桩必须线程安全（否则偶发 ArrayList 并发写异常）
        final List<String> ensureOnlineCalls = java.util.Collections.synchronizedList(new ArrayList<>());
        final Map<String, Long> holds = new ConcurrentHashMap<>();
        /** 账号 → 会话角色标识 sid:uid（角色锁定校验用）。 */
        final Map<String, String> roles = new ConcurrentHashMap<>();
        final List<String> clearedHolds = java.util.Collections.synchronizedList(new ArrayList<>());
        /** 曾进入迁城优先窗的账号（执行结束后会被 clear，故需历史记录）。 */
        final java.util.Set<String> holdsSet = ConcurrentHashMap.newKeySet();
        final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile long clockOffsetMs = 0L;

        void put(String fpid, boolean online) {
            exists.put(fpid, true);
            this.online.put(fpid, online);
            roles.putIfAbsent(fpid, "1:1000" + fpid.hashCode());
        }

        void enqueue(String fpid, Integer... codes) {
            codesByFpid.computeIfAbsent(fpid, k -> new ArrayDeque<>()).addAll(List.of(codes));
        }

        @Override
        public boolean online(String fpid) {
            return Boolean.TRUE.equals(online.get(fpid));
        }

        @Override
        public boolean ensureOnline(String fpid) {
            ensureOnlineCalls.add(fpid);
            online.put(fpid, true);
            return true;
        }

        @Override
        public Response request(String fpid, String proto, Map<String, Object> args, int timeoutSec) {
            requests.add(fpid + ":" + proto);
            ArrayDeque<Integer> codes = codesByFpid.get(fpid);
            int code = codes == null || codes.isEmpty() ? 0 : codes.poll();
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", code);
            return new Response(code, content);
        }

        @Override
        public long serverNowMs(String fpid) {
            return System.currentTimeMillis() + clockOffsetMs;
        }

        @Override
        public void holdForMoveCity(String fpid, long untilMs) {
            holds.merge(fpid, untilMs, Math::max);
            holdsSet.add(fpid);
        }

        @Override
        public void clearMoveCityHold(String fpid) {
            holds.remove(fpid);
            clearedHolds.add(fpid);
        }

        @Override
        public String roleOf(String fpid) {
            return roles.getOrDefault(fpid, "");
        }

        @Override
        public boolean accountExists(String fpid) {
            return Boolean.TRUE.equals(exists.get(fpid));
        }
    }

    // ---------------- 测试脚手架 ----------------

    private FakeTransport transport;
    private Path dataDir;
    private MoveCityStore store;
    private MoveCityEngine engine;

    @Before
    public void setUp() throws Exception {
        transport = new FakeTransport();
        dataDir = Files.createTempDirectory("movecity-test");
        store = new MoveCityStore(dataDir);
        engine = newEngine();
    }

    private MoveCityEngine newEngine() {
        // 注入提速参数（等价原版 @Value 可配）
        return new MoveCityEngine(transport, store, 1L, 30L, 200L, 800L, 800L);
    }

    @After
    public void tearDown() {
        engine.shutdown();
    }

    private static Map<String, Object> account(String fpid, int x, int y) {
        Map<String, Object> m = new HashMap<>();
        m.put("fpid", fpid);
        m.put("name", "角色" + fpid);
        m.put("x", x);
        m.put("y", y);
        m.put("selected", true);
        return m;
    }

    private static Map<String, Object> payload(String proto, List<Map<String, Object>> accounts,
                                               String executeAt, Long advanceMillis, Boolean autoLogin) {
        Map<String, Object> p = new HashMap<>();
        p.put("protocol", proto);
        p.put("accounts", accounts);
        if (executeAt != null) p.put("executeAt", executeAt);
        if (advanceMillis != null) p.put("advanceMillis", advanceMillis);
        if (autoLogin != null) p.put("autoLogin", autoLogin);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultsOf(Map<String, Object> jobMap) {
        return (List<Map<String, Object>>) jobMap.get("results");
    }

    /** 轮询直到条件满足（超时 fail）。时间线测试的真实墙钟等待。 */
    private static void await(long timeoutMs, String what, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("等待超时：" + what);
    }

    // ---------------- A3 立即迁城 ----------------

    @Test
    public void executeNow_multiAccount_allSuccess() {
        transport.put("a1", true);
        transport.put("a2", true);
        Map<String, Object> job = engine.executeNow(payload("req_world_move_city",
                List.of(account("a1", 100, 200), account("a2", 300, 400)), null, null, null));
        assertEquals("立即迁城", job.get("type"));
        await(3000, "立即迁城完成", () -> "已完成".equals(
                engine.job((String) job.get("job_id")).get("status")));
        Map<String, Object> done = engine.job((String) job.get("job_id"));
        List<Map<String, Object>> results = resultsOf(done);
        assertEquals(2, results.size());
        assertEquals(Boolean.TRUE, done.get("success"));
        assertEquals(2, ((Number) done.get("success_count")).intValue());
        for (Map<String, Object> r : results) {
            assertEquals(Boolean.TRUE, r.get("success"));
            assertEquals("迁城成功", r.get("message"));
            assertEquals(1, ((Number) r.get("attempts")).intValue());
        }
        // 独占窗曾设置（原版 enterMoveCityExclusiveWindow 等价物），并在执行结束后解除
        assertTrue("a1 应进入过迁城优先窗", transport.holdsSet.contains("a1"));
        assertTrue("a2 应进入过迁城优先窗", transport.holdsSet.contains("a2"));
    }

    @Test
    public void executeNow_offlineAccount_skippedWithCanonicalMessage() {
        transport.put("a1", true);
        transport.put("a2", false);   // 离线且未开 autoLogin
        Map<String, Object> job = engine.executeNow(payload("req_world_move_city",
                List.of(account("a1", 100, 200), account("a2", 300, 400)), null, null, null));
        await(3000, "立即迁城完成", () -> "已完成".equals(
                engine.job((String) job.get("job_id")).get("status")));
        Map<String, Object> done = engine.job((String) job.get("job_id"));
        List<Map<String, Object>> results = resultsOf(done);
        assertEquals(2, results.size());
        assertEquals(1, ((Number) done.get("success_count")).intValue());
        boolean hasSkipped = false;
        for (Map<String, Object> r : results) {
            if (Boolean.FALSE.equals(r.get("success")) && "a2".equals(r.get("fpid"))) {
                assertEquals("账号未在线，已跳过该账号", r.get("message"));
                assertEquals(0, ((Number) r.get("attempts")).intValue());
                hasSkipped = true;
            }
        }
        assertTrue("离线账号应记跳过文案", hasSkipped);
    }

    @Test
    public void executeNow_duplicatePoint_jobFailsWithCanonicalMessage() {
        // 原版：同坐标校验发生在 armExecution（异步执行期）→ 任务失败而非 HTTP 级拒绝
        transport.put("a1", true);
        transport.put("a2", true);
        Map<String, Object> job = engine.executeNow(payload("req_world_move_city",
                List.of(account("a1", 100, 200), account("a2", 100, 200)), null, null, null));
        String jobId = (String) job.get("job_id");
        await(3000, "同坐标任务失败", () -> "失败".equals(engine.job(jobId).get("status")));
        assertEquals("多个账号不能迁移到同一坐标: 100,200", engine.job(jobId).get("error"));
    }

    @Test
    public void executeNow_missingPoint_rejected() {
        transport.put("a1", true);
        Map<String, Object> acc = account("a1", 0, 0);
        try {
            engine.executeNow(payload("req_world_move_city", List.of(acc), null, null, null));
            fail("缺坐标应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("角色 a1 缺少迁城坐标", e.getMessage());
        }
    }

    @Test
    public void executeNow_unknownProtocol_rejected() {
        transport.put("a1", true);
        try {
            engine.executeNow(payload("req_not_exist", List.of(account("a1", 1, 2)), null, null, null));
            fail("未知协议应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("不支持的迁城协议: req_not_exist", e.getMessage());
        }
    }

    @Test
    public void executeNow_missingKid_rejected() {
        transport.put("a1", true);
        try {
            engine.executeNow(payload("req_world_wormhole_jumping_random",
                    List.of(account("a1", 0, 0)), null, null, null));
            fail("缺王国应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("角色 a1 缺少王国 ID", e.getMessage());
        }
    }

    // ---------------- A4/A5 定时迁城时间线与冲突 ----------------

    /** 定时任务 executeAt：下一整秒 + 1200ms（秒级解析精度）。 */
    private String nextSlot() {
        long now = System.currentTimeMillis();
        long at = (now / 1000 + 2) * 1000 + 200;   // 下一整秒后 1.2s
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at),
                com.carlos.vaultserver.Beijing.ZONE_ID)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    public void schedule_success_timelineToCompleted() {
        transport.put("a1", true);
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 100, 200)), nextSlot(), 0L, false));
        assertEquals("定时迁城", job.get("type"));
        assertEquals(Boolean.TRUE, job.get("cancelable"));
        String jobId = (String) job.get("job_id");
        await(6000, "定时迁城完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        Map<String, Object> done = engine.job(jobId);
        assertEquals(Boolean.TRUE, done.get("success"));
        List<Map<String, Object>> results = resultsOf(done);
        assertEquals("迁城成功", results.get(0).get("message"));
        // 精度记录字段（原版 prepared_at_nanos / batch_release_nanos / release_to_send_micros）
        assertNotNull(results.get(0).get("prepared_at_nanos"));
        assertNotNull(results.get(0).get("release_to_send_micros"));
    }

    @Test
    public void schedule_multiAccount_rejected() {
        transport.put("a1", true);
        transport.put("a2", true);
        try {
            engine.schedule(payload("req_world_move_city",
                    List.of(account("a1", 100, 200), account("a2", 300, 400)),
                    nextSlot(), 0L, false));
            fail("定时任务多账号应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("每个定时任务只能设置一个账号", e.getMessage());
        }
    }

    @Test
    public void schedule_pastTime_rejected() {
        transport.put("a1", true);
        String past = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 60_000),
                com.carlos.vaultserver.Beijing.ZONE_ID)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            engine.schedule(payload("req_world_move_city", List.of(account("a1", 1, 2)),
                    past, 0L, false));
            fail("过期时间应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("定时时间必须晚于当前时间", e.getMessage());
        }
    }

    @Test
    public void schedule_conflictSameSecondAndWithin5s_rejected() {
        transport.put("a1", true);
        String at = nextSlot();
        engine.schedule(payload("req_world_move_city", List.of(account("a1", 1, 2)), at, 0L, false));
        // 同一时刻（同秒精度）→ 同一秒冲突
        try {
            engine.schedule(payload("req_world_random_move_city",
                    List.of(account("a1", 0, 0)), at, 0L, false));
            fail("同秒应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("该账号在同一秒已存在定时任务，不能重复添加", e.getMessage());
        }
        // ±5s 内（不同秒）→ 5 秒内冲突
        String at2 = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(parseToMillis(at) + 3000),
                        com.carlos.vaultserver.Beijing.ZONE_ID)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            engine.schedule(payload("req_world_random_move_city",
                    List.of(account("a1", 0, 0)), at2, 0L, false));
            fail("5 秒内应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("该账号已有 5 秒内的定时任务，请至少间隔 5 秒以上再添加", e.getMessage());
        }
        // 不同账号不受影响
        transport.put("a2", true);
        Map<String, Object> other = engine.schedule(payload("req_world_random_move_city",
                List.of(account("a2", 0, 0)), at, 0L, false));
        assertNotNull(other.get("job_id"));
    }

    private static long parseToMillis(String yyyyMMddHHmmss) {
        return java.time.LocalDateTime.parse(yyyyMMddHHmmss,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(com.carlos.vaultserver.Beijing.ZONE_ID).toInstant().toEpochMilli();
    }

    @Test
    public void schedule_advanceMillis_clampedTo100() {
        transport.put("a1", true);
        transport.put("a2", true);
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 999L, false));
        assertEquals(100L, ((Number) job.get("advance_millis")).longValue());
        // 负值同样 clamp 到 0（换账号避免同秒冲突）
        Map<String, Object> job2 = engine.schedule(payload("req_world_random_move_city",
                List.of(account("a2", 0, 0)), nextSlot(), -5L, false));
        assertEquals(0L, ((Number) job2.get("advance_millis")).longValue());
    }

    // ---------------- A6 短路与重试 ----------------

    @Test
    public void scheduled_itemShortage_shortCircuitsWithoutRetry() {
        transport.put("a1", true);
        transport.enqueue("a1", 2835);   // 迁城道具不足（协议 1 专属短缺码）
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 0L, false));
        String jobId = (String) job.get("job_id");
        await(6000, "短缺短路完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        Map<String, Object> done = engine.job(jobId);
        List<Map<String, Object>> results = resultsOf(done);
        assertEquals(Boolean.FALSE, done.get("success"));
        assertEquals("迁城道具不足，未使用钻石补购，本次迁城已跳过", results.get(0).get("message"));
        assertEquals(1, ((Number) results.get(0).get("attempts")).intValue());   // 短路：不重试
    }

    @Test
    public void scheduled_retryThenSuccess() {
        transport.put("a1", true);
        transport.enqueue("a1", 2836, 0);   // 首次格被占，重试成功
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 0L, false));
        String jobId = (String) job.get("job_id");
        await(6000, "重试后完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        Map<String, Object> done = engine.job(jobId);
        List<Map<String, Object>> results = resultsOf(done);
        assertEquals(Boolean.TRUE, done.get("success"));
        assertEquals(2, ((Number) results.get(0).get("attempts")).intValue());
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) results.get(0).get("attempt_details");
        assertEquals(2, attempts.size());
        assertEquals(Boolean.FALSE, attempts.get(0).get("success"));
        assertEquals(Boolean.TRUE, attempts.get(1).get("success"));
    }

    @Test
    public void scheduled_autoLogin_offlineAccountEnsuredOnline() {
        transport.put("a1", false);   // 离线 + autoLogin → 准备阶段抢登
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 0L, true));
        String jobId = (String) job.get("job_id");
        await(6000, "自动上线迁城完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        Map<String, Object> done = engine.job(jobId);
        assertEquals(Boolean.TRUE, done.get("success"));
        assertTrue("应触发执行前自动上线", transport.ensureOnlineCalls.contains("a1"));
    }

    // ---------------- 取消 / 移除 / 删除联动 ----------------

    @Test
    public void cancel_beforeStart_succeeds_withUserCancelMessage() {
        transport.put("a1", true);
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 0L, false));
        Map<String, Object> canceled = engine.cancel((String) job.get("job_id"));
        assertEquals("已取消", canceled.get("status"));
        assertEquals("用户取消", canceled.get("error"));
        // 再取消 → 已结束
        try {
            engine.cancel((String) job.get("job_id"));
            fail("已结束任务不能取消");
        } catch (IllegalArgumentException e) {
            assertEquals("任务已经结束，不能取消", e.getMessage());
        }
    }

    @Test
    public void cancel_afterStart_rejected() {
        transport.put("a1", true);
        // executeAt = 下一整秒（秒级精度下的最近未来时刻）→ 准备立即发生（准备中/等待执行）
        String at = fmt((System.currentTimeMillis() / 1000 + 1) * 1000);
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), at, 0L, false));
        String jobId = (String) job.get("job_id");
        await(4000, "任务开始", () -> Boolean.FALSE.equals(engine.job(jobId).get("cancelable")));
        try {
            engine.cancel(jobId);
            fail("已开始任务不能取消");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("不能取消"));
        }
    }

    @Test
    public void removeAccountEverywhere_cancelsJobsOfDeletedAccount() {
        transport.put("a1", true);
        transport.put("a2", true);
        Map<String, Object> job1 = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 0L, false));
        engine.schedule(payload("req_world_move_city", List.of(account("a2", 3, 4)),
                nextSlot(), 0L, false));
        engine.removeAccountEverywhere("a1");
        assertEquals("已取消", engine.job((String) job1.get("job_id")).get("status"));
        assertEquals("游戏账号已删除，任务已取消",
                engine.job((String) job1.get("job_id")).get("error"));
        Map<String, Object> jobs = engine.jobs();
        List<Map<String, Object>> scheduled = (List<Map<String, Object>>) jobs.get("scheduled");
        assertEquals("a2 的任务不受影响", 1, scheduled.size());
    }

    // ---------------- A7 持久化与恢复 ----------------

    @Test
    public void persistence_restoreReschedulesPendingJob() {
        transport.put("a1", true);
        // 远期任务（不会被本引擎执行）
        String far = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 3600_000),
                        com.carlos.vaultserver.Beijing.ZONE_ID)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), far, 0L, false));
        String jobId = (String) job.get("job_id");

        // 模拟重启：新引擎 restore 同一 store
        engine.shutdown();
        MoveCityEngine restored = newEngine();
        try {
            restored.restore(store.loadJobs());
            Map<String, Object> loaded = restored.job(jobId);
            assertEquals("定时迁城", loaded.get("type"));
            assertEquals(1, ((Number) loaded.get("accounts")).intValue());
            assertEquals(Boolean.TRUE, loaded.get("cancelable"));
        } finally {
            restored.shutdown();
        }
    }

    @Test
    public void persistence_finishedJobsArchivedNotRescheduled() {
        transport.put("a1", true);
        Map<String, Object> job = engine.executeNow(payload("req_world_move_city",
                List.of(account("a1", 5, 6)), null, null, null));
        String jobId = (String) job.get("job_id");
        await(3000, "立即迁城完成", () -> "已完成".equals(engine.job(jobId).get("status")));

        engine.shutdown();
        MoveCityEngine restored = newEngine();
        try {
            restored.restore(store.loadJobs());
            Map<String, Object> loaded = restored.job(jobId);
            assertEquals("已完成", loaded.get("status"));   // 归档展示，不重放
            assertEquals(Boolean.FALSE, loaded.get("cancelable"));
        } finally {
            restored.shutdown();
        }
    }

    // ---------------- 审阅修复回归 ----------------

    /** P0-3：执行结束后必须立即解除迁城优先窗（否则日常任务让位到 1215s 窗口上限）。 */
    @Test
    public void executeNow_clearsMoveCityHoldAfterFinish() {
        transport.put("a1", true);
        Map<String, Object> job = engine.executeNow(payload("req_world_move_city",
                List.of(account("a1", 5, 6)), null, null, null));
        String jobId = (String) job.get("job_id");
        await(3000, "立即迁城完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        await(2000, "窗口解除", () -> transport.clearedHolds.contains("a1"));
        assertFalse("优先窗应已解除", transport.holds.containsKey("a1"));
    }

    /** P0-2：任务创建后账号切换了角色 → 必须拒绝执行（防迁错号）。 */
    @Test
    public void roleSwitched_afterCreate_jobFails() {
        transport.put("a1", true);
        transport.roles.put("a1", "1:111");
        Map<String, Object> job = engine.schedule(payload("req_world_move_city",
                List.of(account("a1", 1, 2)), nextSlot(), 0L, false));
        String jobId = (String) job.get("job_id");
        // 用户在任务创建后切到同账号另一个角色
        transport.roles.put("a1", "1:222");
        await(6000, "定时任务收尾", () -> {
            String st = String.valueOf(engine.job(jobId).get("status"));
            return "失败".equals(st) || "已完成".equals(st);
        });
        Map<String, Object> done = engine.job(jobId);
        // 原版语义：定时任务跑完即「已完成」，单个账号成败看 results（此处应为失败）
        assertEquals("已完成", done.get("status"));
        assertEquals("角色切换后不得计为成功", Boolean.FALSE, done.get("success"));
        List<Map<String, Object>> results = resultsOf(done);
        assertEquals(Boolean.FALSE, results.get(0).get("success"));
        assertTrue("应带角色切换文案：" + results,
                results.get(0).get("message").toString().contains("账号当前角色已切换"));
    }

    /** P0-2 正向：角色未变时正常执行。 */
    @Test
    public void roleUnchanged_executesNormally() {
        transport.put("a1", true);
        transport.roles.put("a1", "2:777");
        Map<String, Object> job = engine.executeNow(payload("req_world_move_city",
                List.of(account("a1", 9, 9)), null, null, null));
        String jobId = (String) job.get("job_id");
        await(3000, "立即迁城完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        assertEquals(Boolean.TRUE, engine.job(jobId).get("success"));
    }

    // ---------------- 任务列表形状 ----------------

    @Test
    public void jobs_splitScheduledAndFinished_sortedByExecuteAtDesc() {
        transport.put("a1", true);
        transport.put("a2", true);
        transport.put("a3", true);
        long base = System.currentTimeMillis();
        String at1 = fmt(base + 3600_000);
        String at2 = fmt(base + 7200_000);
        String at3 = fmt(base + 10_800_000);
        engine.schedule(payload("req_world_move_city", List.of(account("a1", 1, 2)), at1, 0L, false));
        Map<String, Object> done = engine.executeNow(payload("req_world_move_city",
                List.of(account("a2", 3, 4)), null, null, null));
        String doneId = (String) done.get("job_id");
        await(3000, "立即迁城完成", () -> "已完成".equals(engine.job(doneId).get("status")));
        Map<String, Object> jobs = engine.jobs();
        List<Map<String, Object>> scheduled = (List<Map<String, Object>>) jobs.get("scheduled");
        List<Map<String, Object>> finished = (List<Map<String, Object>>) jobs.get("finished");
        assertEquals(1, scheduled.size());
        assertEquals(1, finished.size());
        assertEquals(at1, scheduled.get(0).get("execute_at"));
        assertEquals("已完成", finished.get(0).get("status"));
        // 再加一个更晚的 → scheduled 首位（倒序）
        engine.schedule(payload("req_world_move_city", List.of(account("a3", 7, 8)), at3, 0L, false));
        engine.schedule(payload("req_world_move_city", List.of(account("a3", 9, 10)), at2, 0L, false));
        jobs = engine.jobs();
        scheduled = (List<Map<String, Object>>) jobs.get("scheduled");
        assertEquals(3, scheduled.size());
        assertEquals(at3, scheduled.get(0).get("execute_at"));
        assertEquals(at2, scheduled.get(1).get("execute_at"));
        assertEquals(at1, scheduled.get(2).get("execute_at"));
    }

    private static String fmt(long millis) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis),
                        com.carlos.vaultserver.Beijing.ZONE_ID)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Test
    public void job_unknownId_rejected() {
        try {
            engine.job("no-such-job");
            fail("不存在的任务应报错");
        } catch (IllegalArgumentException e) {
            assertEquals("定时迁城任务不存在", e.getMessage());
        }
    }

    @Test
    public void randomProtocol_immediate_noCoordinateNeeded() {
        transport.put("a1", true);
        Map<String, Object> job = engine.executeNow(payload("req_world_random_move_city",
                List.of(account("a1", 0, 0)), null, null, null));
        String jobId = (String) job.get("job_id");
        await(3000, "随机迁城完成", () -> "已完成".equals(engine.job(jobId).get("status")));
        assertEquals(Boolean.TRUE, engine.job(jobId).get("success"));
        assertEquals(1, transport.requests.size());
        assertTrue(transport.requests.get(0).endsWith("req_world_random_move_city"));
    }
}
