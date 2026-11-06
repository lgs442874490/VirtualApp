package com.carlos.vaultserver.movecity;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 迁城引擎（1:1 复刻网页版 {@code MoveCityService}，2026-11-06）。
 *
 * <p>行为基准：{@code wjdr-review/src-tool/.../movecity/service/MoveCityService.java}
 * （常量/时间线/状态机/重试/短路文案逐项对齐，file:line 级对照见验证报告）。
 * 寻址与互斥的本项目映射（需求 H2/NFR5）：
 * <ul>
 *   <li>原版 {@code accountRoleId} 四元组身份 → 号库 {@code fpid}（SessionKeeper active 会话）；</li>
 *   <li>原版 {@code requestFast + waitForNoPendingRequest + 独占窗} →
 *       {@code engine.requestLock}（同会话请求天然串行）+ {@code MoveCityTransport.holdForMoveCity}
 *       （准备~释放窗口内抑制整轮任务提交）；</li>
 *   <li>原版 {@code loginAutomaticallyForFrozenIdentity} 抢占登录 →
 *       {@code MoveCityTransport.ensureOnline}（SessionKeeper.ensureSession）；</li>
 *   <li>原版 MyBatis DB 持久化 → {@link MoveCityStore}（data/movecity_jobs.json，H1）。</li>
 * </ul>
 */
public final class MoveCityEngine {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MILLIS_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 原版 :78-100 常量逐项对齐。 */
    static final long RETRY_INTERVAL_MILLIS = 50L;
    static final int MAX_ATTEMPTS = 200;
    static final long PREPARE_BEFORE_MILLIS = 5000L;
    static final long PREWARM_MILLIS = 1000L;
    static final String MOVE_ITEM_SHORTAGE_MESSAGE = "迁城道具不足，未使用钻石补购，本次迁城已跳过";
    static final long PROTECTION_BEFORE_MILLIS = 60_000L;
    static final long PROTECTION_AFTER_MILLIS = 60_000L;
    static final long PROTECTION_RETRY_MILLIS = 1000L;
    static final int MOVE_REQUEST_TIMEOUT_SEC = 6;
    static final long MOVE_EXCLUSIVE_WINDOW_MILLIS =
            5000L + 200L * MOVE_REQUEST_TIMEOUT_SEC * 1000L + 10_000L;
    static final long MAX_ADVANCE_MILLIS = 100L;
    static final long READY_WAIT_MILLIS = 6000L;
    /**
     * 已完成任务保留上限（审阅修复 P1）：原版落库到 MySQL 表无上限，但本项目
     * {@link MoveCityStore} 每次全量序列化+重写 JSON，历史无限累积会让每次 persist
     * 变慢。保留最近 200 条已完成记录（够回溯），更早的丢弃。
     */
    static final int MAX_FINISHED_JOBS = 200;

    private final MoveCityTransport transport;
    private final MoveCityStore store;

    /** 单测可调（原版 @Value wjdr.movecity.* 等价物）。 */
    final long retryIntervalMillis;
    final long prewarmMillis;
    final long prepareBeforeMillis;
    final long protectionBeforeMillis;
    final long protectionAfterMillis;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "movecity-scheduler");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService moveExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "movecity-exec");
        t.setDaemon(true);
        return t;
    });
    /** jobId → job（内存态；含已完成的近期记录，persist 全量落盘）。 */
    private final Map<String, MoveCityJob> jobs = new ConcurrentHashMap<>();
    /** jobId → 定时控制句柄（取消/停止恢复用）。 */
    private final Map<String, JobHandles> handles = new ConcurrentHashMap<>();
    private final ReentrantLock persistLock = new ReentrantLock();

    public MoveCityEngine(MoveCityTransport transport, MoveCityStore store) {
        this(transport, store,
                RETRY_INTERVAL_MILLIS, PREWARM_MILLIS, PREPARE_BEFORE_MILLIS,
                PROTECTION_BEFORE_MILLIS, PROTECTION_AFTER_MILLIS);
    }

    MoveCityEngine(MoveCityTransport transport, MoveCityStore store,
                   long retryIntervalMillis, long prewarmMillis, long prepareBeforeMillis,
                   long protectionBeforeMillis, long protectionAfterMillis) {
        this.transport = transport;
        this.store = store;
        this.retryIntervalMillis = retryIntervalMillis;
        this.prewarmMillis = prewarmMillis;
        this.prepareBeforeMillis = prepareBeforeMillis;
        this.protectionBeforeMillis = protectionBeforeMillis;
        this.protectionAfterMillis = protectionAfterMillis;
    }

    // ==================== API ====================

    /** 协议表（原版 protocols()）。 */
    public List<Map<String, Object>> protocols() {
        return MoveCityProtocol.protocolsMaps();
    }

    /** 立即迁城（多账号批量；原版 executeNow：提交即回，异步执行）。 */
    public Map<String, Object> executeNow(Map<String, Object> payload) {
        Request request = parseRequest(payload);
        MoveCityJob job = MoveCityJob.immediate(request.protocol(), request.actions());
        jobs.put(job.id, job);
        job.status("执行中");
        persist(job);
        moveExecutor.execute(() -> runImmediateJob(job, request));
        return job.toMap();
    }

    private void runImmediateJob(MoveCityJob job, Request request) {
        try {
            Execution execution = armExecution(resolveRequest(request), false, 0L);
            job.preparedAtNanos(execution.preparedAtNanos);
            releaseExecution(execution);
            job.results(collectResults(execution));
            job.status("已完成");
        } catch (Exception ex) {
            FileLog.warn("立即迁城任务失败 jobId={} message={}", job.id, String.valueOf(ex.getMessage()));
            job.error(ex.getMessage());
            job.status("失败");
        } finally {
            // 审阅修复（P0）：执行结束立即解除迁城优先窗，恢复该账号整轮任务
            clearHolds(request.actions());
        }
        persist(job);
    }

    /** 定时迁城（原版 schedule：每任务单账号）。 */
    public Map<String, Object> schedule(Map<String, Object> payload) {
        Request request = parseRequest(payload);
        if (request.actions().size() != 1) {
            throw new IllegalArgumentException("每个定时任务只能设置一个账号");
        }
        String executeAtText = text(payload.get("executeAt"));
        if (executeAtText.isBlank()) executeAtText = text(payload.get("execute_at"));
        if (executeAtText.isBlank()) {
            throw new IllegalArgumentException("缺少定时执行时间");
        }
        long executeAtMillis;
        try {
            executeAtMillis = LocalDateTime.parse(executeAtText, DATE_TIME_FORMATTER)
                    .atZone(Beijing.ZONE_ID).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("定时时间格式错误，应为 yyyy-MM-dd HH:mm:ss");
        }
        long advanceMillis = advanceMillis(payload);
        MoveCityJob.Action first = request.actions().get(0);
        long serverNow = serverNowMs(first.fpid());
        if (executeAtMillis <= serverNow) {
            throw new IllegalArgumentException("定时时间必须晚于当前时间");
        }
        validateScheduleConflict(first.fpid(), executeAtMillis);
        long releaseAtMillis = executeAtMillis - advanceMillis;
        long delayMillis = Math.max(0L, releaseAtMillis - serverNow);
        long prepareDelayMillis = Math.max(0L, delayMillis - prepareBeforeMillis);
        long autoLoginDelayMillis = Math.max(0L, delayMillis - protectionBeforeMillis);

        MoveCityJob job = new MoveCityJob(UUID.randomUUID().toString(), "定时迁城",
                request.protocol(), executeAtText, executeAtMillis, advanceMillis,
                request.actions(), request.autoLogin());
        jobs.put(job.id, job);
        JobHandles h = new JobHandles();
        handles.put(job.id, h);
        if (request.autoLogin()) {
            h.autoLoginFuture = scheduler.scheduleAtFixedRate(
                    () -> requestProtectedRecovery(job.id, "定时迁城保护窗口巡检"),
                    autoLoginDelayMillis, PROTECTION_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
        h.prepareFuture = scheduler.schedule(
                () -> moveExecutor.execute(() -> prepareScheduledJob(job.id, executeAtMillis)),
                prepareDelayMillis, TimeUnit.MILLISECONDS);
        persist(job);
        return job.toMap();
    }

    /** 任务列表（原版 jobs：scheduled + finished，各自按执行/创建时间倒序）。 */
    public Map<String, Object> jobs() {
        List<Map<String, Object>> scheduled = new ArrayList<>();
        List<Map<String, Object>> finished = new ArrayList<>();
        jobs.values().stream()
                .sorted(Comparator.comparingLong(MoveCityJob::sortTime).reversed())
                .forEach(job -> {
                    (job.finished() ? finished : scheduled).add(job.toMap());
                });
        return Map.of("scheduled", scheduled, "finished", finished);
    }

    /** 任务详情（原版 job(jobId)）。 */
    public Map<String, Object> job(String jobId) {
        MoveCityJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("定时迁城任务不存在");
        }
        return job.toMap();
    }

    /** 取消任务（原版 cancel：仅未开始可取消）。 */
    public Map<String, Object> cancel(String jobId) {
        MoveCityJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("定时迁城任务不存在");
        }
        if (job.finished()) {
            throw new IllegalArgumentException("任务已经结束，不能取消");
        }
        if (job.started()) {
            throw new IllegalArgumentException("任务已经开始准备或执行，不能取消");
        }
        cancelHandles(jobId);
        job.status("已取消");
        job.error("用户取消");
        persist(job);
        return job.toMap();
    }

    /** 从定时任务移除账号（原版 removeAccount）。 */
    public Map<String, Object> removeAccount(String jobId, String fpid) {
        MoveCityJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("定时迁城任务不存在");
        }
        if (job.finished()) {
            throw new IllegalArgumentException("任务已经结束，不能删除账号");
        }
        if (job.started()) {
            throw new IllegalArgumentException("任务已经开始准备或执行，不能删除账号");
        }
        job.removeAction(fpid);
        if (job.actionCount() == 0) {
            cancelHandles(jobId);
            job.status("已取消");
            job.error("已删除全部账号，任务自动取消");
        }
        persist(job);
        return job.toMap();
    }

    /** 号库账号删除联动（原版 removeParentAccount：未开始任务摘除该账号，摘空即取消）。 */
    public void removeAccountEverywhere(String fpid) {
        if (fpid == null || fpid.isEmpty()) return;
        for (MoveCityJob job : jobs.values()) {
            if (job.finished() || job.started()) continue;
            boolean had = job.actionsSnapshot().stream().anyMatch(a -> a.fpid().equals(fpid));
            if (!had) continue;
            job.removeAction(fpid);
            if (job.actionCount() == 0) {
                cancelHandles(job.id);
                job.status("已取消");
                job.error("游戏账号已删除，任务已取消");
            }
            persist(job);
        }
    }

    /** 服务重启恢复（原版 restorePendingJob：executeAt+60s 内未完成的重新排程）。 */
    public void restore(List<org.json.JSONObject> persisted) {
        for (JSONObject o : persisted) {
            MoveCityJob job = MoveCityJob.fromJson(o);
            if (job == null) continue;
            if (isFinishedMap(o)) {
                jobs.put(job.id, job);          // 历史记录归档（只读展示）
                continue;
            }
            long now = serverNowMs(job.actionsSnapshot().isEmpty() ? null : job.actionsSnapshot().get(0).fpid());
            if (job.executeAtMillis + protectionAfterMillis <= now) continue;
            jobs.put(job.id, job);
            JobHandles h = new JobHandles();
            handles.put(job.id, h);
            long delayMillis = Math.max(0L, job.executeAtMillis - job.advanceMillis - now);
            if (job.autoLogin) {
                h.autoLoginFuture = scheduler.scheduleAtFixedRate(
                        () -> requestProtectedRecovery(job.id, "恢复任务保护窗口巡检"),
                        Math.max(0L, delayMillis - protectionBeforeMillis),
                        PROTECTION_RETRY_MILLIS, TimeUnit.MILLISECONDS);
            }
            h.prepareFuture = scheduler.schedule(
                    () -> moveExecutor.execute(() -> prepareScheduledJob(job.id, job.executeAtMillis)),
                    Math.max(0L, delayMillis - prepareBeforeMillis), TimeUnit.MILLISECONDS);
            persist(job);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        moveExecutor.shutdownNow();
    }

    // ==================== 定时执行链（原版 prepareScheduledJob / releaseScheduledJob） ====================

    private void prepareScheduledJob(String jobId, long executeAtMillis) {
        MoveCityJob job = jobs.get(jobId);
        if (job == null) return;
        Request request = new Request(job.protocol, job.actionsSnapshot(), job.autoLogin);
        if (request.actions().isEmpty()) {
            job.status("已取消");
            job.error("没有可执行账号");
            persist(job);
            return;
        }
        job.status("准备中");
        try {
            if (request.autoLogin()) {
                autoLoginOfflineAccounts(job, request.actions(), "执行前自动上线");
            }
            request = resolveRequest(request);
            long protectionDeadline = job.autoLogin ? job.protectionDeadlineMillis() : 0L;
            Execution execution = armExecution(request, true, protectionDeadline);
            job.preparedAtNanos(execution.preparedAtNanos);
            job.status("等待执行");
            long releaseAtMillis = executeAtMillis - job.advanceMillis;
            long serverNow = serverNowMs(request.actions().get(0).fpid());
            long releaseDelayMillis = Math.max(0L, releaseAtMillis - serverNow);
            long prewarmDelayMillis = Math.max(0L, releaseAtMillis - prewarmMillis - serverNow);
            // 预热（T-1s 唤醒调度）+ T-0 高精度释放（GameServerClock.waitUntil parkNanos 收敛段）
            scheduler.schedule(() -> releaseExecution(execution), prewarmDelayMillis, TimeUnit.MILLISECONDS);
            JobHandles h = handles.get(jobId);
            if (h != null) {
                h.releaseFuture = scheduler.schedule(
                        () -> releaseScheduledJob(jobId, execution),
                        releaseDelayMillis, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ex) {
            FileLog.warn("定时迁城任务准备失败 jobId={} message={}", jobId, String.valueOf(ex.getMessage()));
            if (job.autoLogin && !protectionExpired(job.protectionDeadlineMillis())) {
                job.error(ex.getMessage());
                job.status("等待恢复");
                Request recovery = request;
                JobHandles h = handles.get(jobId);
                if (h != null) {
                    long serverNow = serverNowMillisOfJob(job);
                    h.releaseFuture = scheduler.schedule(
                            () -> releaseRecoveryOnlyScheduledJob(jobId, recovery, ex),
                            Math.max(0L, executeAtMillis - job.advanceMillis - serverNow),
                            TimeUnit.MILLISECONDS);
                }
            } else {
                job.error(ex.getMessage());
                job.results(preparationFailureResults(request, ex.getMessage()));
                job.status("失败");
            }
            persist(job);
        }
    }

    private void releaseScheduledJob(String jobId, Execution execution) {
        MoveCityJob job = jobs.get(jobId);
        if (job == null) return;
        stopPreExecutionRecovery(jobId);
        job.status("执行中");
        releaseExecution(execution);
        moveExecutor.execute(() -> {
            try {
                List<Map<String, Object>> results = collectResults(execution);
                job.results(results);
                job.status("已完成");
                persist(job);
            } catch (Exception ex) {
                FileLog.warn("定时迁城任务执行失败 jobId={} message={}", jobId, String.valueOf(ex.getMessage()));
                job.error(ex.getMessage());
                job.status("失败");
                persist(job);
            } finally {
                // 审阅修复（P0）：执行结束立即解除迁城优先窗（否则日常任务让位到窗口上限）
                clearHolds(job.actionsSnapshot());
            }
        });
    }

    private void releaseRecoveryOnlyScheduledJob(String jobId, Request request, Exception preparationFailure) {
        MoveCityJob job = jobs.get(jobId);
        if (job == null || job.finished()) return;
        stopPreExecutionRecovery(jobId);
        job.status("执行中");
        moveExecutor.execute(() -> {
            List<Map<String, Object>> results = new ArrayList<>(request.actions().size());
            for (MoveCityJob.Action action : request.actions()) {
                results.add(recoverPreparationAndExecute(request.protocol(), action,
                        preparationFailure, job.protectionDeadlineMillis()));
            }
            job.results(results);
            job.error("");
            job.status("已完成");
            persist(job);
            // 审阅修复（P0）：执行结束立即解除迁城优先窗
            clearHolds(request.actions());
        });
    }

    /** 保护窗巡检（原版 requestProtectedRecovery：T-60s 起每 1s，离线即抢登）。 */
    private void requestProtectedRecovery(String jobId, String reason) {
        MoveCityJob job = jobs.get(jobId);
        long now = System.currentTimeMillis();
        if (job == null || !acceptsPreExecutionRecovery(job, now)) return;
        for (MoveCityJob.Action action : job.actionsSnapshot()) {
            if (transport.accountExists(action.fpid()) && !transport.online(action.fpid())) {
                moveExecutor.execute(() -> {
                    try {
                        transport.ensureOnline(action.fpid());
                        FileLog.info("定时迁城抢占登录受理 jobId={} fpid={} reason={}", jobId, action.fpid(), reason);
                    } catch (Exception ex) {
                        FileLog.warn("定时迁城抢占登录失败 jobId={} fpid={} reason={} message={}",
                                jobId, action.fpid(), reason, String.valueOf(ex.getMessage()));
                    }
                });
            }
        }
    }

    private boolean acceptsPreExecutionRecovery(MoveCityJob job, long nowMillis) {
        return job.autoLogin && !job.finished()
                && nowMillis >= job.executeAtMillis - protectionBeforeMillis
                && nowMillis < job.executeAtMillis;
    }

    private void autoLoginOfflineAccounts(MoveCityJob job, List<MoveCityJob.Action> actions, String reason) {
        if (job == null || !job.autoLogin || job.finished()) return;
        for (MoveCityJob.Action action : actions) {
            if (transport.accountExists(action.fpid()) && !transport.online(action.fpid())) {
                try {
                    transport.ensureOnline(action.fpid());
                    FileLog.info("定时迁城{}：fpid={}", reason, action.fpid());
                } catch (Exception ex) {
                    FileLog.warn("定时迁城{}失败 fpid={} message={}",
                            reason, action.fpid(), String.valueOf(ex.getMessage()));
                }
            }
        }
    }

    // ==================== 执行核心（原版 armExecution / prepareAndExecute / executeOne） ====================

    private Execution armExecution(Request request, boolean retry, long protectionDeadlineMillis) {
        validateNoDuplicatePoint(request);
        CountDownLatch readyLatch = new CountDownLatch(request.actions().size());
        CountDownLatch startLatch = new CountDownLatch(1);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(request.actions().size());
        for (MoveCityJob.Action action : request.actions()) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> prepareAndExecute(request.protocol(), action, retry, readyLatch, startLatch,
                            protectionDeadlineMillis),
                    moveExecutor));
        }
        try {
            awaitReady(readyLatch);
        } catch (RuntimeException ex) {
            // 审阅修复（P0）：准备超时/中断时若不放行闸门，已提交的工作线程会永久阻塞在
            // awaitStart 上（startLatch 永不 countDown）→ 线程与 future 泄漏。
            // 原版此处同样泄漏；本项目按「失败必须收敛」原则显式释放后再向上抛。
            startLatch.countDown();
            throw ex;
        }
        return new Execution(futures, startLatch, System.nanoTime());
    }

    private void releaseExecution(Execution execution) {
        execution.release();
    }

    private List<Map<String, Object>> collectResults(Execution execution) {
        List<Map<String, Object>> results = new ArrayList<>(execution.futures.size());
        for (CompletableFuture<Map<String, Object>> future : execution.futures) {
            Map<String, Object> result = future.join();
            result.put("prepared_at_nanos", execution.preparedAtNanos);
            result.put("batch_release_nanos", execution.releaseNanos);
            Object sendStartNanos = result.get("send_start_nanos");
            if (sendStartNanos instanceof Number) {
                long micros = Math.max(0L, (((Number) sendStartNanos).longValue() - execution.releaseNanos) / 1000L);
                result.put("release_to_send_micros", micros);
            }
            results.add(result);
        }
        return results;
    }

    private Map<String, Object> prepareAndExecute(MoveCityProtocol protocol, MoveCityJob.Action action,
                                                  boolean retry, CountDownLatch readyLatch,
                                                  CountDownLatch startLatch, long protectionDeadlineMillis) {
        String referenceFpid = action.fpid();
        try {
            if (!transport.accountExists(referenceFpid)) {
                throw new IllegalArgumentException("账号不存在: " + referenceFpid);
            }
            if (!transport.online(referenceFpid)) {
                throw new IllegalStateException("账号未在线: " + referenceFpid);
            }
            checkRoleLocked(action);
            // 独占窗等价物：窗口内抑制整轮任务提交（原版 enterMoveCityExclusiveWindow）
            transport.holdForMoveCity(referenceFpid, System.currentTimeMillis() + MOVE_EXCLUSIVE_WINDOW_MILLIS);
            Map<String, Object> payload = protocol.buildPayload(action.x(), action.y(), action.kid());
            readyLatch.countDown();
            awaitStart(startLatch);
            return executeOne(protocol, action, payload, referenceFpid, retry, protectionDeadlineMillis);
        } catch (Exception ex) {
            readyLatch.countDown();
            awaitStart(startLatch);
            if (retry && protectionDeadlineMillis > 0L) {
                return recoverPreparationAndExecute(protocol, action, ex, protectionDeadlineMillis);
            }
            return failedPreparedResult(action, normalizedPreparationMessage(action, ex.getMessage()));
        }
    }

    /**
     * 角色锁定校验（审阅修复 P0）：任务创建时记录的 {@code sid:uid} 必须与当前会话一致，
     * 否则说明账号在同服多角色间切换过——继续执行会**迁错角色**（消耗他人道具/移动他人城）。
     * 任务角色未知（旧任务/创建时未登录）时不校验，保持兼容。
     */
    private void checkRoleLocked(MoveCityJob.Action action) {
        String expected = action.role();
        if (expected.isEmpty()) return;
        String current = transport.roleOf(action.fpid());
        if (current.isEmpty()) return;          // 会话未落地角色：由 online 检查兜底
        if (!expected.equals(current)) {
            throw new IllegalStateException("账号当前角色已切换（任务创建于 " + expected
                    + "，当前 " + current + "），请重新创建迁城任务");
        }
    }

    private Map<String, Object> executeOne(MoveCityProtocol protocol, MoveCityJob.Action action,
                                           Map<String, Object> payload, String referenceFpid,
                                           boolean retry, long protectionDeadlineMillis) {
        int maxAttempts = retry ? MAX_ATTEMPTS : 1;
        Map<String, Object> result = actionResult(action);
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (protectionExpired(protectionDeadlineMillis)) {
                return protectedWindowExpiredResult(result, attempts, attempt - 1);
            }
            try {
                if (!transport.online(referenceFpid)) {
                    throw new IllegalStateException("账号未在线: " + referenceFpid);
                }
                long attemptAtMillis = transport.serverNowMs(referenceFpid);
                long sendStartNanos = System.nanoTime();
                MoveCityTransport.Response response =
                        transport.request(referenceFpid, protocol.proto(), payload, MOVE_REQUEST_TIMEOUT_SEC);
                if (response.code() != 0) {
                    String desc = descOf(response.code());
                    throw new MoveCityTransport.ProtocolException(response.code(),
                            "协议返回错误码 " + response.code()
                                    + (desc.isEmpty() ? "" : "（" + desc + "）"));
                }
                attempts.add(attemptResult(attempt, true, "迁城成功",
                        response.content(), attemptAtMillis, null));
                result.put("success", true);
                result.put("attempts", attempt);
                result.put("attempt_details", attempts);
                result.put("message", "迁城成功");
                result.put("response", response.content() == null ? Map.of() : response.content());
                result.put("send_start_nanos", sendStartNanos);
                return result;
            } catch (Exception ex) {
                attempts.add(attemptResult(attempt, false, messageOf(ex), Map.of(),
                        transport.serverNowMs(referenceFpid), ex));
                if (attempt >= maxAttempts) {
                    return failedResult(result, attempts, attempt, messageOf(ex));
                }
                if (missingMoveItem(protocol, ex)) {
                    return failedResult(result, attempts, attempt, MOVE_ITEM_SHORTAGE_MESSAGE);
                }
                reloginIfDisconnected(referenceFpid, ex);
                sleepQuietly(retryIntervalMillis);
            }
        }
        return failedResult(result, attempts, maxAttempts, "迁城失败");
    }

    /**
     * 准备失败 + 保护窗内的恢复执行（原版 recoverPreparationAndExecute：
     * 循环 resolve + 抢登 + 执行，直到保护窗截止）。
     */
    private Map<String, Object> recoverPreparationAndExecute(MoveCityProtocol protocol,
                                                             MoveCityJob.Action action,
                                                             Exception preparationFailure,
                                                             long protectionDeadlineMillis) {
        Exception lastFailure = preparationFailure;
        while (!protectionExpired(protectionDeadlineMillis)) {
            try {
                if (!transport.accountExists(action.fpid())) {
                    throw new IllegalArgumentException("账号不存在: " + action.fpid());
                }
                if (!transport.online(action.fpid())) {
                    transport.ensureOnline(action.fpid());
                }
                if (!transport.online(action.fpid())) {
                    throw new IllegalStateException("账号未在线: " + action.fpid());
                }
                transport.holdForMoveCity(action.fpid(),
                        System.currentTimeMillis() + MOVE_EXCLUSIVE_WINDOW_MILLIS);
                Map<String, Object> payload = protocol.buildPayload(action.x(), action.y(), action.kid());
                return executeOne(protocol, action, payload, action.fpid(), true, protectionDeadlineMillis);
            } catch (Exception ex) {
                lastFailure = ex;
                sleepQuietly(PROTECTION_RETRY_MILLIS);
            }
        }
        String detail = lastFailure == null || lastFailure.getMessage() == null
                || lastFailure.getMessage().isBlank() ? "迁城准备失败" : lastFailure.getMessage();
        return failedPreparedResult(action, "迁城抢占保护窗口已结束: " + detail);
    }

    private void reloginIfDisconnected(String fpid, Exception cause) {
        try {
            if (!transport.online(fpid) && transport.accountExists(fpid)) {
                transport.ensureOnline(fpid);
                FileLog.info("定时迁城重试阶段账号已受理重新登录 fpid={} cause={}",
                        fpid, String.valueOf(cause.getMessage()));
            }
        } catch (Exception ex) {
            FileLog.warn("定时迁城重试阶段账号重新登录失败 fpid={} message={}",
                    fpid, String.valueOf(ex.getMessage()));
        }
    }

    private boolean missingMoveItem(MoveCityProtocol protocol, Exception failure) {
        int shortageCode = protocol.itemShortageCode();
        if (shortageCode <= 0) return false;
        return failure instanceof MoveCityTransport.ProtocolException
                && ((MoveCityTransport.ProtocolException) failure).code == shortageCode;
    }

    private boolean protectionExpired(long protectionDeadlineMillis) {
        return protectionDeadlineMillis > 0L
                && System.currentTimeMillis() > protectionDeadlineMillis;
    }

    // ==================== 请求解析与校验（原版 parseRequest / parseAction） ====================

    private record Request(MoveCityProtocol protocol, List<MoveCityJob.Action> actions, boolean autoLogin) {
    }

    private Request parseRequest(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("请求内容不能为空");
        }
        MoveCityProtocol protocol = MoveCityProtocol.resolve(firstText(
                payload.get("protocol"), payload.get("proto"), payload.get("name")));
        if (protocol == null) {
            throw new IllegalArgumentException("不支持的迁城协议: " + firstText(
                    payload.get("protocol"), payload.get("proto"), payload.get("name")));
        }
        Object rawAccounts = payload.get("accounts") != null ? payload.get("accounts") : payload.get("actions");
        if (!(rawAccounts instanceof List) || ((List<?>) rawAccounts).isEmpty()) {
            throw new IllegalArgumentException("请选择需要迁城的账号");
        }
        List<MoveCityJob.Action> actions = new ArrayList<>();
        for (Object item : (List<?>) rawAccounts) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = toStringKeyMap(item);
            if (map.isEmpty()) continue;
            // 原版：selected 取 selected/checked，缺省 true
            Object rawSelected = map.get("selected") != null ? map.get("selected") : map.get("checked");
            boolean selected = rawSelected == null
                    || Boolean.parseBoolean(String.valueOf(rawSelected))
                    || "1".equals(String.valueOf(rawSelected));
            if (!selected) continue;
            actions.add(parseAction(protocol, map));
        }
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("请选择需要迁城的账号");
        }
        boolean autoLogin = Boolean.parseBoolean(String.valueOf(
                payload.get("autoLogin") != null ? payload.get("autoLogin") : payload.get("auto_login")));
        return new Request(protocol, orderedActions(actions), autoLogin);
    }

    private MoveCityJob.Action parseAction(MoveCityProtocol protocol, Map<String, Object> map) {
        String fpid = text(map.get("fpid"));
        if (fpid.isBlank()) {
            throw new IllegalArgumentException("账号参数缺少 fpid，请刷新账号列表后重试");
        }
        if (!transport.accountExists(fpid)) {
            throw new IllegalArgumentException("账号不存在: " + fpid);
        }
        int x = MoveCityJob.intValue(map.get("x"));
        int y = MoveCityJob.intValue(map.get("y"));
        int kid = MoveCityJob.intValue(map.get("kid") != null ? map.get("kid") : map.get("kingdom_id"));
        if (protocol.needsPoint() && (x <= 0 || y <= 0)) {
            throw new IllegalArgumentException("角色 " + fpid + " 缺少迁城坐标");
        }
        if (protocol.needsKid() && kid <= 0) {
            throw new IllegalArgumentException("角色 " + fpid + " 缺少王国 ID");
        }
        boolean initiallyOnline = transport.online(fpid);
        String role = transport.roleOf(fpid);
        String sid = "";
        long uid = 0L;
        int sep = role.indexOf(':');
        if (sep > 0) {
            sid = role.substring(0, sep);
            try {
                uid = Long.parseLong(role.substring(sep + 1));
            } catch (NumberFormatException ignored) {
                uid = 0L;
            }
        }
        return new MoveCityJob.Action(fpid, text(map.get("name")), x, y, kid, initiallyOnline, sid, uid);
    }

    /** 原版 orderedActions：在线优先、按账号标识稳定排序。 */
    private List<MoveCityJob.Action> orderedActions(List<MoveCityJob.Action> actions) {
        return actions.stream()
                .sorted(Comparator.comparing(MoveCityJob.Action::initiallyOnline).reversed()
                        .thenComparing(MoveCityJob.Action::fpid))
                .toList();
    }

    /** 原版 resolveRequest：执行前再校验任务身份完整（本项目=账号仍存在）。 */
    private Request resolveRequest(Request request) {
        if (request.actions().isEmpty()) return request;
        List<MoveCityJob.Action> resolved = new ArrayList<>(request.actions().size());
        for (MoveCityJob.Action action : request.actions()) {
            if (!transport.accountExists(action.fpid())) {
                throw new IllegalArgumentException("账号不存在: " + action.fpid());
            }
            resolved.add(action);
        }
        return new Request(request.protocol(), resolved, request.autoLogin());
    }

    private void validateNoDuplicatePoint(Request request) {
        if (!request.protocol().needsPoint()) return;
        var points = ConcurrentHashMap.newKeySet();
        for (MoveCityJob.Action action : request.actions()) {
            String key = action.x() + "," + action.y();
            if (!points.add(key)) {
                throw new IllegalArgumentException("多个账号不能迁移到同一坐标: " + key);
            }
        }
    }

    /** 冲突校验（原版 validateScheduleConflict：同刻拒绝 / ±5s 拒绝，文案逐字对齐）。 */
    private void validateScheduleConflict(String fpid, long executeAtMillis) {
        for (MoveCityJob job : jobs.values()) {
            if (job.finished() || job.executeAtMillis <= 0) continue;
            boolean targets = job.actionsSnapshot().stream()
                    .anyMatch(a -> a.fpid().equals(fpid));
            if (!targets) continue;
            long diff = Math.abs(job.executeAtMillis - executeAtMillis);
            if (diff == 0L) {
                throw new IllegalArgumentException("该账号在同一秒已存在定时任务，不能重复添加");
            }
            if (diff <= 5000L) {
                throw new IllegalArgumentException("该账号已有 5 秒内的定时任务，请至少间隔 5 秒以上再添加");
            }
        }
    }

    // ==================== 结果形状（原版 actionResult / attemptResult / errorDetail） ====================

    private static Map<String, Object> actionResult(MoveCityJob.Action action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fpid", action.fpid());
        result.put("account_name", action.name());
        result.put("x", action.x());
        result.put("y", action.y());
        result.put("kid", action.kid());
        return result;
    }

    private Map<String, Object> attemptResult(int attempt, boolean success, String message,
                                              Map<String, Object> response, long attemptAtMillis,
                                              Exception exception) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempt", attempt);
        result.put("success", success);
        result.put("message", message == null ? "" : message);
        result.put("attempt_at_millis", attemptAtMillis);
        result.put("attempt_at", formatMillis(attemptAtMillis));
        result.put("error_detail", errorDetail(exception, success));
        result.put("response", response == null ? Map.of() : response);
        return result;
    }

    /**
     * 迁城常见错误码中文描述。
     *
     * <p>只收录**本项目已有实证**的码，未实证的不臆造（保留「协议返回错误码 N」）：
     * <ul>
     *   <li>2836 {@code can_not_move_city}：目标格被占用/不可迁
     *       （{@code design/守护盟友-会话交接-20261017.md:36} 实测）；</li>
     *   <li>2878 队列已满（同上，守护盟友实测）。</li>
     * </ul>
     */
    static String descOf(int code) {
        switch (code) {
            case 2836: return "目标位置不可迁城（格被占用或不可达）";
            case 2878: return "队列已满";
            default: return "";
        }
    }

    private Map<String, Object> errorDetail(Exception exception, boolean success) {
        if (success) {
            return Map.of("code", 0, "error", "success", "description", "成功");
        }
        if (exception instanceof MoveCityTransport.ProtocolException) {
            int code = ((MoveCityTransport.ProtocolException) exception).code;
            return Map.of("code", code, "error", "unknown_errcode_" + code,
                    "description", exception.getMessage());
        }
        return Map.of("code", "", "error", "",
                "description", exception == null || exception.getMessage() == null
                        ? "" : exception.getMessage());
    }

    private Map<String, Object> failedResult(Map<String, Object> result,
                                             List<Map<String, Object>> attempts,
                                             int attempt, String message) {
        result.put("success", false);
        result.put("attempts", attempt);
        result.put("attempt_details", attempts);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> failedPreparedResult(MoveCityJob.Action action, String message) {
        Map<String, Object> result = actionResult(action);
        String failureMessage = normalizedPreparationMessage(action, message);
        long attemptAtMillis = System.currentTimeMillis();
        result.put("success", false);
        result.put("attempts", 0);
        result.put("attempt_details", List.of());
        result.put("attempt_at_millis", attemptAtMillis);
        result.put("attempt_at", formatMillis(attemptAtMillis));
        result.put("error_detail", Map.of("code", "", "error", "", "description", failureMessage));
        result.put("message", failureMessage);
        return result;
    }

    private List<Map<String, Object>> preparationFailureResults(Request request, String message) {
        List<Map<String, Object>> out = new ArrayList<>(request.actions().size());
        for (MoveCityJob.Action action : request.actions()) {
            out.add(failedPreparedResult(action, normalizedPreparationMessage(action, message)));
        }
        return out;
    }

    /** 原版 normalizedPreparationMessage：未在线文案统一。 */
    private String normalizedPreparationMessage(MoveCityJob.Action action, String message) {
        if (message != null && message.contains("账号未在线: " + action.fpid())) {
            return "账号未在线，已跳过该账号";
        }
        return message == null || message.isBlank() ? "迁城准备失败" : message;
    }

    private Map<String, Object> protectedWindowExpiredResult(Map<String, Object> result,
                                                             List<Map<String, Object>> attempts,
                                                             int attemptCount) {
        String message = "迁城抢占保护窗口已结束";
        result.put("success", false);
        result.put("attempts", attemptCount);
        result.put("attempt_details", attempts);
        result.put("message", message);
        result.put("error_detail", Map.of("code", "", "error", "", "description", message));
        return result;
    }

    // ==================== 小工具 ====================

    private void awaitReady(CountDownLatch readyLatch) {
        try {
            if (!readyLatch.await(READY_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("迁城线程准备超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("迁城线程准备被中断", e);
        }
    }

    private void awaitStart(CountDownLatch startLatch) {
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("迁城线程启动被中断", e);
        }
    }

    private void cancelHandles(String jobId) {
        JobHandles h = handles.remove(jobId);
        if (h != null) h.cancel();
    }

    /** 解除一批账号的迁城优先窗（审阅修复 P0：任务结束必须让日常任务恢复）。 */
    private void clearHolds(List<MoveCityJob.Action> actions) {
        for (MoveCityJob.Action action : actions) {
            try {
                transport.clearMoveCityHold(action.fpid());
            } catch (Throwable ignored) {
            }
        }
    }

    private void stopPreExecutionRecovery(String jobId) {
        JobHandles h = handles.get(jobId);
        if (h != null) h.stopPreExecutionRecovery();
    }

    private long serverNowMs(String fpid) {
        return fpid == null ? System.currentTimeMillis() : transport.serverNowMs(fpid);
    }

    private long serverNowMillisOfJob(MoveCityJob job) {
        List<MoveCityJob.Action> snapshot = job.actionsSnapshot();
        return serverNowMs(snapshot.isEmpty() ? null : snapshot.get(0).fpid());
    }

    /** 提前量 clamp [0,100]（原版 advanceMillis）。 */
    private static long advanceMillis(Map<String, Object> payload) {
        Object raw = payload.get("advanceMillis") != null ? payload.get("advanceMillis") : payload.get("advance_millis");
        long value = 0L;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw != null) {
            try {
                value = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(0L, Math.min(MAX_ADVANCE_MILLIS, value));
    }

    private boolean isFinishedMap(JSONObject o) {
        Object results = o.opt("results");
        boolean hasResults = results instanceof org.json.JSONArray && ((org.json.JSONArray) results).length() > 0;
        return hasResults || o.optInt("total", 0) > 0 || !o.optString("error", "").isBlank();
    }

    private void persist(MoveCityJob job) {
        persistLock.lock();
        try {
            pruneFinishedJobs();
            store.save(new ArrayList<>(jobs.values()));
        } finally {
            persistLock.unlock();
        }
    }

    /** 已完成任务超过 {@link #MAX_FINISHED_JOBS} 时按执行时间从旧到新淘汰（未完成任务永不淘汰）。 */
    private void pruneFinishedJobs() {
        List<MoveCityJob> finished = new ArrayList<>();
        for (MoveCityJob j : jobs.values()) {
            if (j.finished()) finished.add(j);
        }
        int excess = finished.size() - MAX_FINISHED_JOBS;
        if (excess <= 0) return;
        finished.sort(Comparator.comparingLong(MoveCityJob::sortTime));
        for (int i = 0; i < excess; i++) {
            MoveCityJob old = finished.get(i);
            jobs.remove(old.id);
            handles.remove(old.id);
        }
        FileLog.info("迁城任务历史裁剪：淘汰 {} 条已完成记录（保留上限 {}）",
                String.valueOf(excess), String.valueOf(MAX_FINISHED_JOBS));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String formatMillis(long millis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), Beijing.ZONE_ID)
                .format(MILLIS_DATE_TIME_FORMATTER);
    }

    private static String messageOf(Exception ex) {
        return ex == null || ex.getMessage() == null ? "" : ex.getMessage();
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String s = text(value);
            if (!s.isBlank()) return s;
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringKeyMap(Object value) {
        if (!(value instanceof Map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<Object, Object>) value).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    // ==================== 内部结构 ====================

    /** 定时任务控制句柄（原版 MoveJob 的 prepare/release/autoLogin futures）。 */
    private static final class JobHandles {
        volatile ScheduledFuture<?> prepareFuture;
        volatile ScheduledFuture<?> releaseFuture;
        volatile ScheduledFuture<?> autoLoginFuture;

        void cancel() {
            if (prepareFuture != null) prepareFuture.cancel(false);
            if (releaseFuture != null) releaseFuture.cancel(false);
            if (autoLoginFuture != null) autoLoginFuture.cancel(false);
        }

        void stopPreExecutionRecovery() {
            if (autoLoginFuture != null) autoLoginFuture.cancel(false);
        }
    }

    /** 批量闸门（原版 MoveExecution：armed 就绪 → 同刻释放）。 */
    private static final class Execution {
        final List<CompletableFuture<Map<String, Object>>> futures;
        final CountDownLatch startLatch;
        final long preparedAtNanos;
        private volatile long releaseNanos;
        private final AtomicBoolean releaseRecorded = new AtomicBoolean();

        Execution(List<CompletableFuture<Map<String, Object>>> futures,
                  CountDownLatch startLatch, long preparedAtNanos) {
            this.futures = futures;
            this.startLatch = startLatch;
            this.preparedAtNanos = preparedAtNanos;
        }

        void release() {
            if (releaseRecorded.compareAndSet(false, true)) {
                releaseNanos = System.nanoTime();
            }
            startLatch.countDown();
        }

        long releaseNanos() {
            return releaseNanos;
        }
    }
}
