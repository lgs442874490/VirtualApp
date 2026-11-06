package com.carlos.vaultserver.migrant;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 50ms 节拍抢位执行器移植回归（原版 {@code MigrantRetryExecutor} 84 行）：
 * 首次立即发、此后每 50ms 一次、200 次上限、成功即停、停止信号优先。
 */
public class MigrantRetryExecutorTest {

    /** 假单调钟 + 睡眠推进（纳秒）。 */
    private static final class FakeNanoClock implements LongSupplier {
        long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void sleep(long nanos) {
            assertTrue("睡眠时长不应为负", nanos >= 0L);
            now += nanos;
        }
    }

    private static MigrantRetryExecutor.AttemptOutcome ok() {
        return MigrantRetryExecutor.AttemptOutcome.succeeded();
    }

    private static MigrantRetryExecutor.AttemptOutcome fail() {
        return MigrantRetryExecutor.AttemptOutcome.failed(7401, null);
    }

    @Test
    public void successOnThirdAttempt_stopsImmediately() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicInteger calls = new AtomicInteger();
        MigrantRetryExecutor executor = new MigrantRetryExecutor();
        MigrantRetryExecutor.RetryResult result = executor.execute(attempt -> {
            calls.incrementAndGet();
            return attempt < 3 ? fail() : ok();
        }, () -> false, clock, clock::sleep);

        assertEquals(3, result.attemptCount);
        assertFalse("成功不属于停止信号", result.stoppedBySignal);
        assertFalse(result.exhausted);
        assertEquals("第 3 次尝试应在 2*50ms 时刻", 2L * MigrantRules.RETRY_INTERVAL_NANOS, clock.now);
        assertTrue(result.lastOutcome.success);
    }

    @Test
    public void exhaustedAfter200Attempts() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicInteger calls = new AtomicInteger();
        MigrantRetryExecutor executor = new MigrantRetryExecutor();
        MigrantRetryExecutor.RetryResult result = executor.execute(attempt -> {
            calls.incrementAndGet();
            return fail();
        }, () -> false, clock, clock::sleep);

        assertEquals(MigrantRules.MAX_ATTEMPTS, calls.get());
        assertEquals(200, result.attemptCount);
        assertTrue("200 次用尽", result.exhausted);
        assertFalse(result.stoppedBySignal);
        assertEquals("总时长 = 199 * 50ms",
                199L * MigrantRules.RETRY_INTERVAL_NANOS, clock.now);
        assertEquals(Integer.valueOf(7401), result.lastOutcome.responseCode);
    }

    @Test
    public void stopSignalMidwayStopsRetry() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        MigrantRetryExecutor executor = new MigrantRetryExecutor();
        MigrantRetryExecutor.RetryResult result = executor.execute(attempt -> {
            calls.incrementAndGet();
            if (attempt == 5) stop.set(true);
            return fail();
        }, stop::get, clock, clock::sleep);

        assertEquals("第 5 次发出后收到停止信号即停", 5, result.attemptCount);
        assertTrue(result.stoppedBySignal);
        assertFalse(result.exhausted);
        assertEquals(5, calls.get());
    }

    @Test
    public void stopBeforeStart_producesZeroAttempts() {
        FakeNanoClock clock = new FakeNanoClock();
        MigrantRetryExecutor executor = new MigrantRetryExecutor();
        MigrantRetryExecutor.RetryResult result = executor.execute(
                attempt -> {
                    throw new AssertionError("停止信号下不应发起尝试");
                }, () -> true, clock, clock::sleep);

        assertEquals(0, result.attemptCount);
        assertTrue(result.stoppedBySignal);
        assertEquals(0L, clock.now);
    }

    @Test
    public void senderExceptionIsRecordedAsFailure() {
        FakeNanoClock clock = new FakeNanoClock();
        MigrantRetryExecutor executor = new MigrantRetryExecutor();
        MigrantRetryExecutor.RetryResult result = executor.execute(attempt -> {
            throw new IllegalStateException("连接断开");
        }, () -> false, clock, clock::sleep);

        assertEquals(200, result.attemptCount);
        assertTrue(result.exhausted);
        assertTrue(result.lastOutcome.failure instanceof IllegalStateException);
        assertEquals("连接断开", result.lastOutcome.failure.getMessage());
    }

    @Test
    public void waitBudgetMillis_capsAndFloors() {
        long now = 1_000_000_000L;
        assertEquals("剩余充足 → 配置值", 1000L,
                MigrantRetryExecutor.waitBudgetMillis(now + 5_000_000_000L, now, 1000L));
        assertEquals("剩余不足 1ms → 至少 1ms", 1L,
                MigrantRetryExecutor.waitBudgetMillis(now + 500_000L, now, 1000L));
        assertEquals("已过期 → 0", 0L,
                MigrantRetryExecutor.waitBudgetMillis(now - 1L, now, 1000L));
    }

    @Test
    public void preparationRetrier_retriesThenSucceeds() {
        FakeNanoClock clock = new FakeNanoClock();
        AtomicInteger attempts = new AtomicInteger();
        MigrantPreparationRetrier retrier = new MigrantPreparationRetrier();
        MigrantPreparationRetrier.Result<String> result = retrier.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("登录失败");
            return "ok";
        }, clock.now + 10_000_000_000L, clock, millis -> clock.now += millis * 1_000_000L);

        assertEquals("ok", result.value);
        assertEquals(3, result.attemptCount);
        assertEquals(2_000L, clock.now / 1_000_000L);
    }

    @Test
    public void preparationRetrier_deadlineThrowsChineseMessage() {
        FakeNanoClock clock = new FakeNanoClock();
        MigrantPreparationRetrier retrier = new MigrantPreparationRetrier();
        try {
            retrier.execute(() -> {
                throw new IllegalStateException("上号失败");
            }, clock.now, clock, millis -> clock.now += millis * 1_000_000L);
            org.junit.Assert.fail("应当抛异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("自动上号和任务准备连续"));
            assertTrue(e.getMessage().contains("上号失败"));
        }
    }
}
