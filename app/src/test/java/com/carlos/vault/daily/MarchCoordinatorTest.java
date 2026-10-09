package com.carlos.vault.daily;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * TC-S15 / TC-S16：行军资源锁（v3.8.0「行军锁」核心）。
 * 对标原版 Web 版 6 种资源读写锁（WORLD_MARCH/SOLDIERS/DIAMONDS…）的端侧合并实现：
 * 写独占、读共享、固定加锁顺序防死锁、超时让出返回 null（SKIPPED 语义，不是失败）。
 */
public class MarchCoordinatorTest {

    @Test
    public void guardedMatrix() {
        // 需求表（MarchCoordinator.NEEDS）
        for (String fn : new String[]{"gather", "hunt", "intel", "act_icehunter", "act_mercenary",
                "act_truck", "act_feast", "behemoth", "medic", "barracks", "arena",
                "bank", "shop", "alliance_shop", "act_flamefangs"}) {
            assertTrue("应受锁约束: " + fn, MarchCoordinator.isGuarded(fn));
        }
        for (String fn : new String[]{"mail", "login_rewards", "supply", "daily_mission",
                "alliance_help", "backpack", "red_packet", "activity_rewards", null}) {
            assertFalse("纯领取类不应受锁约束: " + fn, MarchCoordinator.isGuarded(fn));
        }
    }

    @Test
    public void unguardedTask_passThrough() throws Exception {
        MarchCoordinator c = MarchCoordinator.forKey("fp-test", 1L);
        AutoCloseable h = c.tryAcquire("mail", 50);
        assertNotNull("无需求任务直接放行", h);
        h.close();
    }

    @Test
    public void writeLock_excludesReaders_withTimeoutNull() throws Exception {
        MarchCoordinator c = MarchCoordinator.forKey("fp-w1", 1L);
        AtomicReference<AutoCloseable> behemoth = new AtomicReference<>();
        CountDownLatch held = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                behemoth.set(c.tryAcquire("behemoth", 1000)); // MARCH 写
                held.countDown();
                Thread.sleep(600);
                behemoth.get().close();
            } catch (Exception ignored) {
            }
        });
        t.start();
        held.await();

        long begin = System.currentTimeMillis();
        assertNull("behemoth 持 MARCH 写锁期间 gather(读) 必须超时让出",
                c.tryAcquire("gather", 150));
        long elapsed = System.currentTimeMillis() - begin;
        assertTrue("应在超时上限附近返回，而不是立即失败或死等", elapsed >= 100 && elapsed < 2000);
        assertNull(c.tryAcquire("hunt", 100));
        assertNull("arena 需要 MARCH 写，同样被挡", c.tryAcquire("arena", 100));

        t.join();
        // 释放后可再获取
        AutoCloseable g = c.tryAcquire("gather", 500);
        assertNotNull(g);
        g.close();
    }

    @Test
    public void independentResources_doNotBlock() throws Exception {
        MarchCoordinator c = MarchCoordinator.forKey("fp-ind", 1L);
        AutoCloseable behemoth = c.tryAcquire("behemoth", 500); // MARCH 写
        assertNotNull(behemoth);
        // DIAMONDS 与 MARCH 独立 ⇒ bank 不受影响
        AutoCloseable bank = c.tryAcquire("bank", 500);
        assertNotNull(bank);
        // SOLDIERS 与 MARCH 独立 ⇒ medic 不受影响
        AutoCloseable medic = c.tryAcquire("medic", 500);
        assertNotNull(medic);
        medic.close();
        bank.close();
        behemoth.close();
    }

    @Test
    public void arenaExclusiveWindow_blocksAllMarches() throws Exception {
        // M3 竞技场独占窗 = 原版 ArenaExclusiveGate 的行军域近似。
        // 注意：读写锁同线程可重入，独占效果必须跨线程验证。
        MarchCoordinator c = MarchCoordinator.forKey("fp-arena", 1L);
        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<AutoCloseable> arenaRef = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                arenaRef.set(c.tryAcquire("arena", 1000)); // ARENA 写 + MARCH 写
                held.countDown();
                Thread.sleep(500);
                arenaRef.get().close();
            } catch (Exception ignored) {
            }
        });
        t.start();
        held.await();
        assertNull(c.tryAcquire("gather", 100));
        assertNull(c.tryAcquire("act_feast", 100));
        assertNull(c.tryAcquire("behemoth", 100));
        t.join();
        AutoCloseable after = c.tryAcquire("gather", 500);
        assertNotNull(after);
        after.close();
    }

    @Test
    public void readReadShare() throws Exception {
        MarchCoordinator c = MarchCoordinator.forKey("fp-rr", 1L);
        AutoCloseable a = c.tryAcquire("gather", 500);
        assertNotNull(a);
        AutoCloseable b = c.tryAcquire("hunt", 500); // 同线程读读共存
        assertNotNull(b);
        b.close();
        a.close();
    }

    @Test
    public void rollbackOnPartialAcquisition() throws Exception {
        // gather 需要 MARCH 读 + SOLDIERS 读；先被 medic 占 SOLDIERS 写 ⇒ gather 拿到 MARCH 后
        // 在 SOLDIERS 上超时 ⇒ 必须回滚已持有的 MARCH 读锁（否则后续写锁会饿死）。
        // 读写锁同线程可重入，须跨线程持有。
        MarchCoordinator c = MarchCoordinator.forKey("fp-rb", 1L);
        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<AutoCloseable> medicRef = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                medicRef.set(c.tryAcquire("medic", 1000)); // SOLDIERS 写
                held.countDown();
                Thread.sleep(500);
                medicRef.get().close();
            } catch (Exception ignored) {
            }
        });
        t.start();
        held.await();
        assertNull(c.tryAcquire("gather", 150)); // 半持后回滚
        t.join();
        // MARCH 通道必须已完全释放：写锁可立即获取
        AutoCloseable behemoth = c.tryAcquire("behemoth", 200);
        assertNotNull("半持锁未回滚会卡死写锁", behemoth);
        behemoth.close();
    }

    @Test
    public void roleIsolation_betweenRoles() throws Exception {
        MarchCoordinator c1 = MarchCoordinator.forKey("fp-iso", 1L);
        MarchCoordinator c2 = MarchCoordinator.forKey("fp-iso", 2L);
        assertNotSame(c1, c2);
        AutoCloseable a = c1.tryAcquire("behemoth", 500);
        assertNotNull(a);
        AutoCloseable b = c2.tryAcquire("behemoth", 500); // 不同角色互不影响
        assertNotNull(b);
        b.close();
        a.close();
        assertSame(c1, MarchCoordinator.forKey("fp-iso", 1L)); // 同角色单例
    }

    @Test
    public void noDeadlock_underMixedContention() throws Exception {
        // 固定枚举序加锁：arena(MARCH→ARENA 写) 与行军类(MARCH→SOLDIERS 读) 高频交叉
        MarchCoordinator c = MarchCoordinator.forKey("fp-dl", 1L);
        AtomicInteger arenaWins = new AtomicInteger();
        AtomicInteger marchWins = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    AutoCloseable h = c.tryAcquire("arena", 50);
                    if (h != null) { arenaWins.incrementAndGet(); h.close(); }
                }
            } catch (Exception ignored) {
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    AutoCloseable h = c.tryAcquire("act_mercenary", 50);
                    if (h != null) { marchWins.incrementAndGet(); h.close(); }
                }
            } catch (Exception ignored) {
            }
        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join(10000);
        t2.join(10000);
        assertEquals("线程必须正常退出（无死锁）", Thread.State.TERMINATED, t1.getState());
        assertEquals(Thread.State.TERMINATED, t2.getState());
        assertTrue("两侧都应有进展", arenaWins.get() > 0 && marchWins.get() > 0);
    }
}
