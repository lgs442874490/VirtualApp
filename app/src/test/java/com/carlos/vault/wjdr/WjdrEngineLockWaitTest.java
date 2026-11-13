package com.carlos.vault.wjdr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * R0-1 阶段③ LOCK 观测单测（需求 R0-1 / 开发计划 S2）：
 * {@link WjdrEngine#lastRequestLockWaitMs()} 记录最近一次请求等待 requestLock 的毫秒数。
 *
 * <p>零行为约束：只断言新增只读观测值；请求流程/锁语义不变（由改动前全量单测守）。
 */
public class WjdrEngineLockWaitTest {

    @Test
    public void initialValue_isMinusOne() {
        WjdrEngine engine = new WjdrEngine(null);
        assertEquals("未发过请求应为 -1", -1L, engine.lastRequestLockWaitMs());
    }

    @Test
    public void requestAttempt_recordsNonNegativeWait() {
        WjdrEngine engine = new WjdrEngine(null);
        try {
            engine.request("req_noop", new LinkedHashMap<String, Object>(), 1);
        } catch (Throwable expected) {
            // 无会话客户端：持锁后必然失败；本用例只验证等待时长已落值
        }
        assertTrue("发起过请求后应记录 ≥0 的等待时长，实测 "
                + engine.lastRequestLockWaitMs(), engine.lastRequestLockWaitMs() >= 0L);
    }

    @Test
    public void blockedRequest_recordsMeasuredWait() throws Exception {
        WjdrEngine engine = new WjdrEngine(null);
        Field f = WjdrEngine.class.getDeclaredField("requestLock");
        f.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) f.get(engine);

        lock.lock();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                engine.request("req_noop", new LinkedHashMap<String, Object>(), 1);
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "lock-wait-probe");
        try {
            worker.start();
            Thread.sleep(150L);
        } finally {
            lock.unlock();
        }
        worker.join(3_000L);

        assertTrue("worker 应已结束", !worker.isAlive());
        assertTrue("持锁竞争应测到 ≥80ms 等待，实测 " + engine.lastRequestLockWaitMs(),
                engine.lastRequestLockWaitMs() >= 80L);
    }
}
