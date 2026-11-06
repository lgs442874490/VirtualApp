package com.carlos.vaultserver.migrant;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionKeeper;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.task.RoleKey;

/**
 * 移民后台调度器（单线程，1 秒扫描）。
 *
 * <p>时点与职责（契约 §4 时序常量 + 2026-11-06 审查修复）：
 * <ol>
 *   <li><b>准备到点</b>：{@code freedom_start - 15min} 起分轮投递准备任务（每轮 ≤5s，未就绪
 *       交给下一拍，不长期占用角色会话线程）；</li>
 *   <li><b>发射到点</b>：{@code freedom_start - advance_millis}（角色忙时提前 30s 排队；
 *       时钟未校准由引擎直接收口 UNKNOWN，不发射）；</li>
 *   <li><b>对账分轮</b>：发射任务体发完 50ms 节拍即返回，本调度器每 5s 投递一次轻量探测，
 *       累计到 90 秒窗口后按最后一次探测结果收口（P1-4，避免 90s 不发心跳）；</li>
 *   <li><b>延迟复核</b>：UNKNOWN 任务在 +3min / +10min 自动复核一次（P1-5）；</li>
 *   <li><b>看门狗</b>：任务体卡死超过阈值强制收口并释放 running（P1-1）。</li>
 * </ol>
 *
 * <p>本线程不发游戏协议；所有网络动作都经 {@link MigrantEngine} 投递到角色会话线程。
 */
public final class MigrantScheduler {

    /** 扫描周期（毫秒）。 */
    static final long TICK_MILLIS = 1000L;
    /** 分轮对账探测间隔（毫秒）。 */
    static final long RECONCILE_PROBE_INTERVAL_MILLIS = 5_000L;
    /** 对账窗口结束后再留的收口余量。 */
    static final long FIRE_RECONCILE_GRACE_MILLIS = 60_000L;

    private final SessionKeeper keeper;
    private final VaultStore store;
    private final MigrantJobStore jobs;
    private final MigrantEngine engine;

    private volatile boolean running;
    private volatile Thread thread;

    public MigrantScheduler(SessionKeeper keeper, VaultStore store, MigrantJobStore jobs,
                            MigrantEngine engine) {
        this.keeper = keeper;
        this.store = store;
        this.jobs = jobs;
        this.engine = engine;
    }

    /** 启动后台扫描（幂等）。 */
    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "migrant-scheduler");
        t.setDaemon(true);
        thread = t;
        t.start();
        FileLog.info("移民调度器已启动：1s 扫描（准备分轮/发射/对账分轮/延迟复核）");
    }

    /** 停止调度（关停时先停调度再落盘任务）。 */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        jobs.flushIfDirty();
        FileLog.info("移民调度器已停止");
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (Throwable t) {
                FileLog.warn("移民调度扫描异常：{}", String.valueOf(t));
            }
            try {
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 单次扫描（包级可见，便于测试直接驱动）。 */
    void tick() {
        for (MigrantJob job : jobs.all()) {
            if (job == null) continue;
            try {
                if (MigrantJob.isActive(job.status)) {
                    scheduleJob(job);
                } else if (MigrantJob.UNKNOWN.equals(job.status)) {
                    maybeReverify(job);
                }
            } catch (Throwable t) {
                FileLog.warn("移民任务调度异常 job={}：{}", job.jobId, String.valueOf(t));
            }
        }
        jobs.flushIfDirty();
    }

    private void scheduleJob(MigrantJob job) {
        long now = engine.jobServerNowMillis(job);
        RoleKey role = MigrantEngine.roleOf(job);

        // 活动窗口结束：一律 UNKNOWN（不猜结果）；角色线程仍在跑时交给任务体自行收口
        if (job.freedomEndMillis > 0L && now >= job.freedomEndMillis) {
            if (engine.isRunning(job.jobId)) return;
            engine.markOverWindow(job);
            return;
        }

        if (MigrantJob.WAITING.equals(job.status)) {
            if (job.freedomStartMillis <= 0L) return;
            long prepareAt = job.preparationStartMillis > 0L
                    ? job.preparationStartMillis
                    : job.freedomStartMillis - MigrantRules.PREPARE_WINDOW_MILLIS;
            if (now >= prepareAt) engine.startPrepare(job);
            return;
        }

        if (MigrantJob.PREPARING.equals(job.status)) {
            if (engine.isRunning(job.jobId)) {
                // P1-1 看门狗：任务体卡死则强制收口（finally 会释放 running）
                if (engine.isRunningTooLong(job.jobId, MigrantEngine.PREPARE_STUCK_MILLIS)) {
                    engine.markPrepareTimeout(job);
                }
                return;
            }
            if (job.prepareRetryAtMillis > System.currentTimeMillis()) return;
            if (job.freedomStartMillis <= 0L) {
                engine.markPrepareTimeout(job);
                return;
            }
            long readyDeadline = job.freedomStartMillis - MigrantRules.READY_BEFORE_MILLIS;
            if (now >= readyDeadline) {
                engine.markPrepareTimeout(job);
            } else {
                engine.startPrepare(job);
            }
            return;
        }

        if (MigrantJob.ARMED.equals(job.status)) {
            if (job.freedomStartMillis <= 0L) return;
            long fireAt = job.freedomStartMillis - Math.max(0, job.advanceMillis);
            // P1-7：角色忙（整轮任务在跑）时提前 30s 提交，排到队列里等边界
            long lead = MigrantEngine.FIRE_LEAD_MILLIS;
            if (keeper != null && keeper.roleBusy(role)) {
                lead = MigrantEngine.FIRE_LEAD_BUSY_MILLIS;
            }
            if (now >= fireAt - lead) engine.startFire(job);
            return;
        }

        if (MigrantJob.FIRING.equals(job.status)) {
            if (engine.isRunning(job.jobId)) {
                if (engine.isRunningTooLong(job.jobId, MigrantEngine.FIRE_STUCK_MILLIS)) {
                    engine.markFireTimeout(job);
                }
                return;
            }
            long fireAt = job.freedomStartMillis - Math.max(0, job.advanceMillis);
            if (job.fireDoneAtMillis <= 0L) {
                // 还没开始发请求（投递失败/角色离线）：窗口内继续重试，窗口过了判 REJECTED
                if (now <= fireAt + MigrantRules.SUBMIT_WINDOW_MILLIS) {
                    engine.startFire(job);
                } else {
                    engine.markFireMissed(job);
                }
                return;
            }
            // 发射已结束 → 分轮对账（每 5s 一次轻量探测，90s 窗口后收口）
            long deadline = job.reconcileDeadlineMillis > 0L
                    ? job.reconcileDeadlineMillis
                    : job.fireDoneAtMillis + MigrantRules.RECONCILE_WINDOW_MILLIS;
            if (now >= deadline) {
                engine.finishReconcile(job);
                return;
            }
            if (System.currentTimeMillis() - job.reconcileAttemptAtMillis
                    >= RECONCILE_PROBE_INTERVAL_MILLIS) {
                engine.startReconcile(job);
            }
            return;
        }

        if (MigrantJob.UNKNOWN.equals(job.status)) {
            maybeReverify(job);
        }
    }

    /** P1-5：UNKNOWN 任务的 +3min/+10min 自动复核（角色在线才投递）。 */
    private void maybeReverify(MigrantJob job) {
        if (job.nextReverifyAtMillis <= 0L) return;
        if (engine.isRunning(job.jobId)) return;
        long now = engine.jobServerNowMillis(job);
        if (now < job.nextReverifyAtMillis) return;
        if (job.freedomEndMillis > 0L && now >= job.freedomEndMillis) return;
        engine.startReverify(job);
    }
}
