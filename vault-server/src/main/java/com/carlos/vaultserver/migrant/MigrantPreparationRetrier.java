package com.carlos.vaultserver.migrant;

import java.util.function.LongSupplier;

/**
 * 准备阶段重试器（逐条移植原版 {@code MigrantPreparationRetrier} 52 行）。
 *
 * <p>1 秒间隔重试直到截止时刻；到点仍失败抛 {@link IllegalStateException}
 * （中文文案带尝试次数与最后一次失败原因）。
 */
final class MigrantPreparationRetrier {

    static final long RETRY_INTERVAL_MILLIS = 1000L;

    <T> Result<T> execute(Attempt<T> attempt, long deadlineMillis, LongSupplier clock,
                          MillisSleeper sleeper) {
        int attempts = 0;
        RuntimeException lastFailure = null;
        while (true) {
            attempts++;
            try {
                return new Result<>(attempt.execute(), attempts);
            } catch (RuntimeException e) {
                lastFailure = e;
                long remaining = deadlineMillis - clock.getAsLong();
                if (remaining <= 0L) {
                    throw new IllegalStateException("自动上号和任务准备连续 " + attempts
                            + " 次失败：" + failureMessage(lastFailure), lastFailure);
                }
                sleeper.sleep(Math.min(RETRY_INTERVAL_MILLIS, remaining));
            }
        }
    }

    private String failureMessage(RuntimeException e) {
        return e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? "未知错误" : e.getMessage();
    }

    static final class Result<T> {
        final T value;
        final int attemptCount;

        Result(T value, int attemptCount) {
            this.value = value;
            this.attemptCount = attemptCount;
        }
    }

    @FunctionalInterface
    interface Attempt<T> {
        T execute();
    }

    @FunctionalInterface
    interface MillisSleeper {
        void sleep(long millis);
    }
}
