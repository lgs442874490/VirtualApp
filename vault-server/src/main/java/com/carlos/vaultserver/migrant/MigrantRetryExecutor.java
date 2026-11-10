package com.carlos.vaultserver.migrant;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 50ms 节拍抢位执行器（逐条移植原版 {@code MigrantRetryExecutor} 84 行）。
 *
 * <p>语义：
 * <ul>
 *   <li>第 1 次尝试立即发出（不睡），第 n 次在 {@code startedAt + (n-1)*50ms} 发出；</li>
 *   <li>最多 {@link MigrantRules#MAX_ATTEMPTS} 次；</li>
 *   <li>每次尝试前后都检查停止信号；成功立即返回；</li>
 *   <li>发送抛 RuntimeException 归一为失败结果，不中断节拍。</li>
 * </ul>
 *
 * <p>时钟与睡眠可注入，单测用假时钟验证 200 次上限与停止信号。
 */
final class MigrantRetryExecutor {

    static final long RETRY_INTERVAL_NANOS = MigrantRules.RETRY_INTERVAL_NANOS;
    static final int MAX_ATTEMPTS = MigrantRules.MAX_ATTEMPTS;

    /**
     * 执行抢位循环。
     *
     * @param sender     单次尝试（attempt 从 1 开始）
     * @param stopSignal 停止信号（收到成功/正在移民/迁服断线/窗口结束）
     * @param nanoClock  单调纳秒钟
     * @param sleeper    睡眠器（可注入假实现）
     */
    RetryResult execute(AttemptSender sender, BooleanSupplier stopSignal,
                        LongSupplier nanoClock, NanoSleeper sleeper) {
        long startedAt = nanoClock.getAsLong();
        AttemptOutcome lastOutcome = null;
        int attempts = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (stopSignal.getAsBoolean()) {
                return new RetryResult(attempts, lastOutcome, true, false);
            }
            sleepUntil(startedAt + (long) (attempt - 1) * RETRY_INTERVAL_NANOS, nanoClock, sleeper);
            if (stopSignal.getAsBoolean()) {
                return new RetryResult(attempts, lastOutcome, true, false);
            }
            attempts = attempt;
            try {
                lastOutcome = sender.send(attempt);
            } catch (RuntimeException e) {
                lastOutcome = AttemptOutcome.failed(null, e);
            }
            if (lastOutcome != null && lastOutcome.success) {
                return new RetryResult(attempts, lastOutcome, false, false);
            }
            if (!stopSignal.getAsBoolean()) continue;
            return new RetryResult(attempts, lastOutcome, true, false);
        }
        return new RetryResult(attempts, lastOutcome, false, true);
    }

    /** 提交窗口剩余预算（毫秒，至少 1ms；原版移植）。 */
    static long waitBudgetMillis(long deadlineNanos, long nowNanos, long configuredMillis) {
        long remainingNanos = deadlineNanos - nowNanos;
        if (remainingNanos <= 0L) return 0L;
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return remainingMillis <= 0L ? 1L : Math.min(configuredMillis, remainingMillis);
    }

    private void sleepUntil(long targetNanos, LongSupplier nanoClock, NanoSleeper sleeper) {
        long remaining;
        while ((remaining = targetNanos - nanoClock.getAsLong()) > 0L) {
            sleeper.sleep(remaining);
        }
    }

    /** 单次尝试结果。 */
    static final class AttemptOutcome {
        final boolean success;
        final Integer responseCode;
        final RuntimeException failure;

        AttemptOutcome(boolean success, Integer responseCode, RuntimeException failure) {
            this.success = success;
            this.responseCode = responseCode;
            this.failure = failure;
        }

        static AttemptOutcome succeeded() {
            return new AttemptOutcome(true, 0, null);
        }

        static AttemptOutcome failed(Integer responseCode, RuntimeException failure) {
            return new AttemptOutcome(false, responseCode, failure);
        }
    }

    /** 一轮抢位汇总。 */
    static final class RetryResult {
        final int attemptCount;
        final AttemptOutcome lastOutcome;
        final boolean stoppedBySignal;
        final boolean exhausted;

        RetryResult(int attemptCount, AttemptOutcome lastOutcome,
                    boolean stoppedBySignal, boolean exhausted) {
            this.attemptCount = attemptCount;
            this.lastOutcome = lastOutcome;
            this.stoppedBySignal = stoppedBySignal;
            this.exhausted = exhausted;
        }
    }

    @FunctionalInterface
    interface NanoSleeper {
        void sleep(long nanos);
    }

    @FunctionalInterface
    interface AttemptSender {
        AttemptOutcome send(int attempt);
    }
}
