package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.VaultStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 2026-11-12 审查修复 F2：巨熊窗口内 60s 重试不被调度器 SKIPPED/ERROR 退避吞掉。
 *
 * <p>窗口外保持原退避（≥180s/≥120s）；非 bear 任务不受影响。
 */
public class BearRetryCadenceTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID = 12345L;
    private static final long BASE_MS = 60_000L;

    private RoleKey role;

    @Before
    public void setUp() {
        role = new RoleKey(FPID, SID, UID);
        BearGate.release(role);
    }

    @After
    public void tearDown() {
        BearGate.release(role);
    }

    private static String dayKey() {
        return Beijing.fmt("yyyy-MM-dd").format(new Date());
    }

    private static Map<String, Object> snapshotWith(String fn, int st) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put(StateKeys.dayState(dayKey(), new RoleKey(FPID, SID, UID), fn), (long) st);
        return snapshot;
    }

    @Test
    public void bearInWindow_skipped_doesNotBackOff() {
        BearGate.enter(role, System.currentTimeMillis() + 600_000L);
        long next = TaskCadenceScheduler.nextIntervalForResult(
                null, role, "bear", BASE_MS, snapshotWith("bear", 4));
        assertEquals("窗口内 SKIPPED 应保持基础间隔（60s），实际=" + next, BASE_MS, next);
    }

    @Test
    public void bearInWindow_error_doesNotBackOff() {
        BearGate.enter(role, System.currentTimeMillis() + 600_000L);
        long next = TaskCadenceScheduler.nextIntervalForResult(
                null, role, "bear", BASE_MS, snapshotWith("bear", 3));
        assertEquals("窗口内 ERROR 应保持基础间隔（60s），实际=" + next, BASE_MS, next);
    }

    @Test
    public void bearOutOfWindow_skipped_keepsBackoff() {
        long next = TaskCadenceScheduler.nextIntervalForResult(
                null, role, "bear", BASE_MS, snapshotWith("bear", 4));
        assertEquals("窗口外 SKIPPED 仍应 ≥180s，实际=" + next, 180_000L, next);
    }

    @Test
    public void nonBearInWindow_skipped_keepsBackoff() {
        BearGate.enter(role, System.currentTimeMillis() + 600_000L);
        long next = TaskCadenceScheduler.nextIntervalForResult(
                null, role, "gather", BASE_MS, snapshotWith("gather", 4));
        assertEquals("窗口内其它任务不得被豁免退避，实际=" + next, 180_000L, next);
    }

    @Test
    public void bearInWindow_freshResult_keepsBase() {
        BearGate.enter(role, System.currentTimeMillis() + 600_000L);
        long next = TaskCadenceScheduler.nextIntervalForResult(
                null, role, "bear", BASE_MS, snapshotWith("bear", 2));
        assertEquals(BASE_MS, next);
    }

    @Test
    public void bearOutOfWindow_error_keepsBackoff() {
        long next = TaskCadenceScheduler.nextIntervalForResult(
                null, role, "bear", BASE_MS, snapshotWith("bear", 3));
        assertEquals("窗口外 ERROR 仍应 ≥120s，实际=" + next, 120_000L, next);
    }

    @Test
    public void legacyOverloadWithUid_doesNotApplyWindowShortCircuit() {
        // P2-7：旧重载构造 uid=0 的 RoleKey，与 BearGate 角色键不匹配；此处锁定现状并提示调用方改走角色重载
        VaultStore.Account acc = new VaultStore.Account(FPID);
        acc.fpid = FPID;
        acc.activeUid = String.valueOf(UID);
        BearGate.enter(role, System.currentTimeMillis() + 600_000L);
        long next = TaskCadenceScheduler.nextIntervalForResult(
                acc, SID, "bear", BASE_MS, snapshotWith("bear", 4));
        assertEquals("旧重载不保证 F2 生效（现状退避 180s）", 180_000L, next);
    }
}
