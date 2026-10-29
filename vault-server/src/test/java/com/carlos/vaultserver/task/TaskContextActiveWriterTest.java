package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * R2 Phase 2B：{@link TaskContext#activeWriter} + 整轮/单任务执行器把 activeWriter 传进任务上下文。
 *
 * <p>锁定契约：
 * <ul>
 *   <li>旧构造（6/7/8 参）默认 activeWriter=true —— Phase 2B 之前行为 100% 不变；</li>
 *   <li>9 参构造按传入保存 roleUid/activeWriter；</li>
 *   <li>RoundRunner / ModularRoundRunner 把 activeWriter 传给 TaskContext：兄弟角色（非 active）
 *       执行 supply/bear 时只写角色 mod 键，不覆盖旧 mod 键（修复前 ServerTasks 仍调旧 4 参采集，
 *       会写 {@code mod:<fpid>:<sid>:supply/bear}）；active 角色角色键 + 旧键一致镜像。</li>
 * </ul>
 */
public class TaskContextActiveWriterTest {

    private static final String FPID = "778899";   // 用独有账号，避免 ServerWarState 等静态注册表串测
    private static final long SID = 913L;
    private static final long UID_A = 1001L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private VaultStore.Account acc;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        acc = new VaultStore.Account(FPID);
        acc.token = "tk";
    }

    // ---------------- TaskContext 构造器 ----------------

    @Test
    public void legacyConstructors_defaultActiveWriterTrue() {
        TaskContext legacy = new TaskContext(null, FPID, SID, store,
                Collections.emptyMap(), "手动");
        assertTrue("旧 6 参构造默认 activeWriter=true（行为不变）", legacy.activeWriter);
        assertEquals(0L, legacy.roleUid);

        TaskContext withPredicate = new TaskContext(null, FPID, SID, store,
                Collections.emptyMap(), "手动", TaskContext.enabledPredicate(null));
        assertTrue(withPredicate.activeWriter);

        TaskContext phase1 = new TaskContext(null, FPID, SID, store,
                Collections.emptyMap(), "手动", null, UID_A);
        assertTrue("Phase 1 8 参构造默认 activeWriter=true", phase1.activeWriter);
        assertEquals(UID_A, phase1.roleUid);
    }

    @Test
    public void phase2bConstructor_storesRoleUidAndActiveWriter() {
        TaskContext sibling = new TaskContext(null, FPID, SID, store,
                Collections.emptyMap(), "手动", null, UID_A, false);
        assertEquals(UID_A, sibling.roleUid);
        assertFalse("兄弟角色（App 未选中）不镜像旧键", sibling.activeWriter);

        TaskContext active = new TaskContext(null, FPID, SID, store,
                Collections.emptyMap(), "手动", null, UID_A, true);
        assertEquals(UID_A, active.roleUid);
        assertTrue(active.activeWriter);
    }

    // ---------------- 执行器传递（RoundRunner.bear / ModularRoundRunner.supply） ----------------

    /** 巨熊响应：code=0 + reserve_time 属合法纪元秒 → CaptureTasks.bear 会写 mod:…:bear。 */
    private static CaptureTasksTest.FakeEngine bearEngine() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        long reserve = System.currentTimeMillis() / 1000L + 3600L;
        Map<String, Object> trap = new HashMap<>();
        trap.put("code", 0);
        trap.put("reserve_time", reserve);
        e.responses.put("req_alliance_trap_info", msg(trap));
        return e;
    }

    /** 补给响应：online_time 属未来合法纪元秒 → CaptureTasks.captureSupplyState 会写 mod:…:supply。 */
    private static CaptureTasksTest.FakeEngine supplyEngine() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        Map<String, Object> ri = new HashMap<>();
        ri.put("online_time", System.currentTimeMillis() / 1000L + 1800L);
        e.notifies.put("notify_recover_resource_init", ri);
        return e;
    }

    @Test
    public void roundRunnerBear_nonActiveRole_writesRoleCaptureModOnly() {
        CaptureTasksTest.FakeEngine e = bearEngine();

        RoundRunner.runRound(e, acc, SID, store, null, "bear", "手动", UID_A, false);

        assertNotNull("兄弟角色只写角色 mod 键",
                store.getString("mod:" + FPID + ":" + SID + ":" + UID_A + ":bear"));
        assertNull("非 active 角色不得覆盖旧 mod:bear 键（App 显示链路）",
                store.getString("mod:" + FPID + ":" + SID + ":bear"));
    }

    @Test
    public void roundRunnerBear_activeRole_mirrorsLegacyCaptureMod() {
        CaptureTasksTest.FakeEngine e = bearEngine();

        RoundRunner.runRound(e, acc, SID, store, null, "bear", "手动", UID_A, true);

        String roleKey = "mod:" + FPID + ":" + SID + ":" + UID_A + ":bear";
        String legacyKey = "mod:" + FPID + ":" + SID + ":bear";
        assertNotNull(store.getString(roleKey));
        assertEquals("active 角色角色键 + 旧键一致镜像",
                store.getString(roleKey), store.getString(legacyKey));
    }

    @Test
    public void modularRunnerSupply_nonActiveRole_writesRoleCaptureModOnly() {
        CaptureTasksTest.FakeEngine e = supplyEngine();

        ModularRoundRunner.runRound(e, acc, SID, store, null, "supply", "手动", UID_A, false);

        assertNotNull(store.getString("mod:" + FPID + ":" + SID + ":" + UID_A + ":supply"));
        assertNull("非 active 角色不得覆盖旧 mod:supply 键",
                store.getString("mod:" + FPID + ":" + SID + ":supply"));
    }

    @Test
    public void modularRunnerSupply_activeRole_mirrorsLegacyCaptureMod() {
        CaptureTasksTest.FakeEngine e = supplyEngine();

        ModularRoundRunner.runRound(e, acc, SID, store, null, "supply", "手动", UID_A, true);

        String roleKey = "mod:" + FPID + ":" + SID + ":" + UID_A + ":supply";
        String legacyKey = "mod:" + FPID + ":" + SID + ":supply";
        assertNotNull(store.getString(roleKey));
        assertEquals(store.getString(roleKey), store.getString(legacyKey));
    }

    // ---------------- 工具 ----------------

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }
}
