package com.carlos.vaultserver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.task.RoleKey;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 角色会话**严格寻址**回归（2026-11-06 代码审查 P0-1）。
 *
 * <p>背景：{@code SessionKeeper.findRoleSession} 的历史回退语义是「uid 精确 miss 后
 * 回落到账号 active 会话」。对整轮调度无害（任务幂等），但对**移民**这类不可逆操作是事故级：
 * 给角色 B 发 {@code test-early} 时若 B 无会话、同账号 A 在线，协议会投到 A 的引擎上，
 * 真正被迁移的是 A。
 *
 * <p>故移民路径全部改用严格查找：{@code uid>0} 只认精确键，miss 即视为「角色无会话」。
 */
public class SessionKeeperRoleStrictLookupTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID_A = "35371154";
    private static final String UID_B = "88280792";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private SessionKeeper keeper;
    private final List<SessionKeeperRoleOpsTest.OpsTestEngine> engines = new ArrayList<>();

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        keeper = new SessionKeeper(store, config(), null, stateStore,
                () -> {
                    SessionKeeperRoleOpsTest.OpsTestEngine e =
                            new SessionKeeperRoleOpsTest.OpsTestEngine();
                    engines.add(e);
                    return e;
                }, true);
    }

    private VaultConfig config() {
        return new VaultConfig("127.0.0.1:0", tmp.getRoot().toPath(), "s", null,
                "human_first", 8);
    }

    private void syncAccount() {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk-" + FPID;
        a.activeSid = SID;
        a.activeUid = UID_A;
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        store.ensureRole(FPID, SID, UID_A);
        store.ensureRole(FPID, SID, UID_B);
    }

    private void loginOnline(String uid) {
        assertTrue(keeper.roleAction(FPID, SID, uid, "login").code == 0);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, uid).online);
    }

    private static RoleKey key(String uid) {
        return new RoleKey(FPID, Long.parseLong(SID), Long.parseLong(uid));
    }

    /** 核心断言：uid 精确 miss 时**绝不**回退到同账号在线角色。 */
    @Test
    public void strictLookup_neverFallsBackToSiblingRole() {
        syncAccount();
        loginOnline(UID_A);   // 只让 A 有会话

        // 一个真实存在但从未建会话的角色（uid 不与会话键匹配）
        RoleKey ghost = new RoleKey(FPID, Long.parseLong(SID), 99999999L);

        assertFalse("角色无会话时 roleOnline 必须为 false（旧实现会因回退 A 而返回 true）",
                keeper.roleOnline(ghost));
        assertNull("角色无会话时不得给出兄弟角色的引擎", keeper.roleEngine(ghost));
        assertFalse(keeper.roleBusy(ghost));

        final AtomicBoolean ran = new AtomicBoolean(false);
        final AtomicBoolean skipped = new AtomicBoolean(false);
        boolean submitted = keeper.submitRoleJob(ghost, "test.strict",
                () -> ran.set(true), () -> skipped.set(true));
        keeper.tickForTest();
        assertFalse("无会话角色不应被接受投递", submitted);
        assertFalse("绝不能在兄弟角色线程上执行该任务", ran.get());

        // 对照：真实在线角色本身仍可用
        assertTrue("A 自己的在线判定不受影响", keeper.roleOnline(key(UID_A)));
    }

    /** 兄弟角色在线也不影响「另一个真实在线角色」的判定（正向对照）。 */
    @Test
    public void strictLookup_resolvesEachOnlineRoleIndependently() {
        syncAccount();
        loginOnline(UID_A);
        loginOnline(UID_B);

        assertTrue(keeper.roleOnline(key(UID_A)));
        assertTrue(keeper.roleOnline(key(UID_B)));
        assertTrue(keeper.roleEngine(key(UID_A)) != null);
        assertTrue(keeper.roleEngine(key(UID_B)) != null);
        assertTrue("两个角色必须是不同的引擎实例（不得共用兄弟会话）",
                keeper.roleEngine(key(UID_A)) != keeper.roleEngine(key(UID_B)));
    }

    /** onSkip：投递成功但执行时会话已非 ONLINE → 回调必须触发（避免调用方占位标记永久泄漏）。 */
    @Test
    public void submitRoleJob_onSkipRunsWhenSessionNotOnline() {
        syncAccount();
        loginOnline(UID_A);

        // 直接让会话退出 ONLINE（模拟投递后掉线）：close 会把引擎置非在线
        keeper.roleAction(FPID, SID, UID_A, "logout");

        final AtomicBoolean ran = new AtomicBoolean(false);
        final AtomicBoolean skipped = new AtomicBoolean(false);
        boolean submitted = keeper.submitRoleJob(key(UID_A), "test.skip",
                () -> ran.set(true), () -> skipped.set(true));
        keeper.tickForTest();
        // 下线后会话可能已不可投递（返回 false）或在投递后被跳过；两种都不得执行任务体
        assertFalse("任务体不得执行", ran.get());
    }
}
