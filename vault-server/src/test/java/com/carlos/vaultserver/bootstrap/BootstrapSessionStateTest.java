package com.carlos.vaultserver.bootstrap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link BootstrapSessionState} 单测（审查 P1-2 / 需求 R-02）：按作用域隔离、清理语义、
 * 以及同一 fpid 并发读-改-写不丢更新。
 */
public class BootstrapSessionStateTest {

    @Test
    public void holder_sameScopeReused_differentScopeIsolated() {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapSessionState.Holder a1 = store.holder("fpid-a");
        BootstrapSessionState.Holder a2 = store.holder("fpid-a");
        BootstrapSessionState.Holder b = store.holder("fpid-b");
        assertSame("同一 scope 必须复用同一 holder", a1, a2);
        assertFalse("不同 scope 不得共享状态", a1 == b);

        a1.markWorldKingdomInfoSent();
        a1.setFinishPassDay("2026-11-12");
        assertTrue(a1.worldKingdomInfoSent());
        assertFalse("b 的作用域未被污染", b.worldKingdomInfoSent());
        assertEquals("", b.finishPassDay());
    }

    @Test
    public void clearScope_resetsState() {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapSessionState.Holder holder = store.holder("fpid-x");
        holder.markWorldKingdomInfoSent();
        holder.markWorldFogSent();
        holder.setWorldKingdomId(77L);
        holder.markResourceEnded();
        holder.incrementResourceExhaustedAttempts();

        store.clearScope("fpid-x");

        BootstrapSessionState.Holder fresh = store.holder("fpid-x");
        assertFalse(fresh.worldKingdomInfoSent());
        assertFalse(fresh.worldFogSent());
        assertEquals(0L, fresh.worldKingdomId());
        assertFalse(fresh.resourceEnded());
        assertEquals(0, fresh.resourceExhaustedAttempts());
    }

    /** 需求 R-02 边界：同一 fpid 并发请求不得丢失更新（读-改-写同临界区）。 */
    @Test
    public void concurrentIncrement_neverLosesUpdate() throws Exception {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapSessionState.Holder holder = store.holder("fpid-concurrent");
        int threads = 16;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        holder.incrementResourceExhaustedAttempts();
                        holder.incrementWorldAttempts();
                        holder.nextResUpdateSeq();
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000L);
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(threads * perThread, holder.resourceExhaustedAttempts());
        assertEquals(threads * perThread, holder.worldAttempts());
    }

    /** 共享实例：同一 store 的两个 service 通过同 scope 看到同一份会话状态。 */
    @Test
    public void sharedStore_visibleToMultipleHolders() {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapSessionState.Holder first = store.holder("fpid-shared");
        first.setWorldKingdomId(88L);
        first.markWorldKingdomInfoSent();
        BootstrapSessionState.Holder second = store.holder("fpid-shared");
        assertEquals(88L, second.worldKingdomId());
        assertTrue(second.worldKingdomInfoSent());
    }

    /** P2-4：world kingdom info 的“检查→置位”必须原子（并发只有一个赢家）。 */
    @Test
    public void claimWorldKingdomInfo_onlyOneWinnerConcurrently() throws Exception {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapSessionState.Holder holder = store.holder("fp-claim");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                if (holder.claimWorldKingdomInfo()) {
                    winners.incrementAndGet();
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000L);
        }
        assertEquals("并发下只允许一个请求认领 world kingdom info", 1, winners.get());
        assertTrue(holder.worldKingdomInfoSent());
    }

    /** P2-4：收尾占位同样原子；全空回滚后可再次认领（R-09）。 */
    @Test
    public void claimFinishPass_onlyOneWinnerAndRelease() {
        BootstrapSessionState store = new BootstrapSessionState();
        BootstrapSessionState.Holder holder = store.holder("fp-finish-claim");
        assertTrue(holder.claimFinishPass("2026-11-12"));
        assertFalse("同日第二次认领失败", holder.claimFinishPass("2026-11-12"));
        holder.releaseFinishPass("2026-11-12");
        assertEquals("回滚后当日未置位", "", holder.finishPassDay());
        assertTrue("回滚后可再次认领", holder.claimFinishPass("2026-11-12"));
    }
}
