package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暂停自动恢复（P1 2026-10-21 契约 §二.2/§二.7）：
 * pause 前置校验与文案、恢复计划落库、到期重登、失败重试/冷却（可注入失败次数，
 * 间隔/冷却置 0 秒级驱动）、0 次尝试保持离线、stop/login/resume 清理、重启恢复。
 * 引擎替身合成登录（不连网），手动驱动节拍。
 */
public class SessionKeeperPauseRecoveryTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID_A = "35371154";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private SessionKeeper keeper;
    final List<RecoveryTestEngine> engines = new ArrayList<>();

    /** 合成引擎：可注入登录失败次数；登录即在线；不连网。 */
    static final class RecoveryTestEngine extends WjdrEngine {
        boolean online;
        int failLogins;
        boolean closed;

        RecoveryTestEngine() {
            super(null);
        }

        private LoginResult doLogin(long uid) {
            if (failLogins > 0) {
                failLogins--;
                throw new IllegalStateException("登录失败（测试注入）");
            }
            online = true;
            LoginResult r = new LoginResult();
            r.uid = uid;
            r.serverId = 888L;
            r.gameName = "隆科多";
            return r;
        }

        @Override
        public void initialize() {
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid) {
            return doLogin(0L);
        }

        @Override
        public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) {
            return doLogin(roleUid);
        }

        @Override
        public Long discoverRoleUid(long kid) {
            return Long.parseLong(UID_A);
        }

        @Override
        public java.util.List<RoleInfo> discoverRoles() {
            RoleInfo ri = new RoleInfo();
            ri.serverid = 888L;
            ri.uid = Long.parseLong(UID_A);
            ri.name = "隆科多";
            return java.util.Collections.singletonList(ri);
        }

        @Override
        public String sessionState() {
            return online ? STATE_ONLINE : STATE_OFFLINE;
        }

        @Override
        public void heartbeatTick() {
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            GameMessage m = new GameMessage();
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", 0);
            m.setContent(content);
            return m;
        }

        @Override
        public String identityJson() {
            return "";
        }

        @Override
        public void applyIdentity(String settingsJson) {
        }

        @Override
        public String lastFailure() {
            return null;
        }

        @Override
        public long lastServerId() {
            return 888L;
        }

        @Override
        public void close() {
            online = false;
            closed = true;
        }
    }

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        keeper = new SessionKeeper(store, config(8), null, stateStore,
                () -> {
                    RecoveryTestEngine e = new RecoveryTestEngine();
                    engines.add(e);
                    return e;
                }, true);
    }

    private VaultConfig config(int maxSessions) {
        return new VaultConfig("127.0.0.1:0", tmp.getRoot().toPath(), "s", null, "human_first", maxSessions);
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
    }

    private void loginOnline() {
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
    }

    // ---------- pause 前置校验（契约 §二.2） ----------

    @Test
    public void pause_requiresRecoveryEnabled_3002WithMessage() {
        syncAccount();
        loginOnline();
        store.find(FPID).takeoverRecoveryEnabled = false;
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(3002, r.code);
        assertEquals("顶号恢复未开启，无法暂停", r.message);
        assertFalse(r.accepted);
    }

    @Test
    public void pause_requiresOnlineRole_3002WithMessage() {
        syncAccount();
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_NOT_ONLINE, r.code);
        assertEquals("账号当前离线，无法暂停", r.message);
    }

    // ---------- pause 成功：计划落库 + 停会话 + 响应 ----------

    @Test
    public void pause_success_schedulesResume_stopsSession_keepsDesired() {
        syncAccount();
        loginOnline();
        long before = System.currentTimeMillis();

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(0, r.code);
        assertEquals(VaultStore.RUN_PAUSED, r.runState);
        assertEquals("账号已暂停，将在 30 分钟后自动上线", r.message);
        assertTrue("pauseResumeAt ≈ now+1800s", r.pauseResumeAt >= before + 1800_000L - 2000
                && r.pauseResumeAt <= System.currentTimeMillis() + 1800_000L + 2000);

        // 会话停止（引擎关闭），但期望在线保留（到期自动恢复）
        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertFalse("暂停停会话连接", rt.online);
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);
        assertEquals("倒计时透出", r.pauseResumeAt, rt.nextRecoveryAt);
        assertTrue(engines.get(0).closed);

        VaultStore.RoleEntry e = store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(VaultStore.RUN_PAUSED, e.runState);
        assertEquals(r.pauseResumeAt, e.pauseResumeAt);
        assertTrue(e.desiredOnline);

        // 暂停幂等（重复 pause 返回 PAUSED 不重排计划）
        assertEquals(VaultStore.RUN_PAUSED,
                keeper.roleAction(FPID, SID, UID_A, "pause").runState);
    }

    // ---------- 到期自动恢复：成功（契约 §二.7） ----------

    @Test
    public void autoResume_dueTime_relogins_clearsPause() {
        syncAccount();
        store.find(FPID).takeoverInitialDelaySeconds = 0;   // 测试：到期即刻
        loginOnline();

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(0, r.code);
        keeper.tickForTest();   // 到期 → 自动重新登录

        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertTrue("到期自动上线", rt.online);
        assertEquals(VaultStore.RUN_RUNNING, rt.runState);
        assertEquals("", rt.statusMessage);
        assertEquals(0, rt.nextRecoveryAt);
        VaultStore.RoleEntry e = store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(VaultStore.RUN_RUNNING, e.runState);
        assertEquals("成功后清 pauseResumeAt", 0, e.pauseResumeAt);
        assertTrue(e.desiredOnline);
    }

    // ---------- 失败 → 按策略重试 / 冷却（间隔与冷却置 0 秒级驱动） ----------

    @Test
    public void autoResume_failure_retriesWithInterval_thenSuccess() {
        syncAccount();
        VaultStore.Account acc = store.find(FPID);
        acc.takeoverInitialDelaySeconds = 0;
        acc.takeoverRetryIntervalSeconds = 0;
        loginOnline();
        engines.get(0).failLogins = 1;

        keeper.roleAction(FPID, SID, UID_A, "pause");
        keeper.tickForTest();   // 尝试 1：失败

        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);
        assertFalse(rt.online);
        assertTrue("中文重试说明（N/M）", rt.statusMessage.contains("等待重试（1/3）"));
        assertTrue("下次尝试时刻已排", rt.nextRecoveryAt > 0);
        assertEquals(VaultStore.RUN_PAUSED,
                store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).runState);

        keeper.tickForTest();   // 尝试 2：成功
        rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertTrue(rt.online);
        assertEquals(VaultStore.RUN_RUNNING, rt.runState);
        assertEquals(0, store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);
    }

    @Test
    public void autoResume_maxAttemptsReached_cooldownThenCounterReset() {
        syncAccount();
        VaultStore.Account acc = store.find(FPID);
        acc.takeoverInitialDelaySeconds = 0;
        acc.takeoverRetryIntervalSeconds = 0;
        acc.takeoverCooldownMinutes = 0;   // 冷却即刻到期
        acc.takeoverMaxAttempts = 2;
        loginOnline();
        engines.get(0).failLogins = 2;

        keeper.roleAction(FPID, SID, UID_A, "pause");
        keeper.tickForTest();   // 尝试 1 失败 → 等重试（1/2）
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).statusMessage.contains("等待重试（1/2）"));
        keeper.tickForTest();   // 尝试 2 失败 → 达上限进冷却

        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);
        assertTrue("冷却中文说明", rt.statusMessage.contains("连续 2 次登录失败，冷却 0 分钟后再试"));

        engines.get(0).failLogins = 0;
        keeper.tickForTest();   // 冷却结束 → 计数重置后再试 → 成功
        rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertTrue("冷却结束后恢复上线", rt.online);
        assertEquals(VaultStore.RUN_RUNNING, rt.runState);
    }

    @Test
    public void autoResume_zeroMaxAttempts_staysOfflineAfterFirstFailure() {
        syncAccount();
        VaultStore.Account acc = store.find(FPID);
        acc.takeoverInitialDelaySeconds = 0;
        acc.takeoverMaxAttempts = 0;   // 0=不自动顶号
        loginOnline();
        engines.get(0).failLogins = 1;

        keeper.roleAction(FPID, SID, UID_A, "pause");
        keeper.tickForTest();   // 到期尝试失败 → 不再自动重试

        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);
        assertFalse(rt.online);
        assertTrue(rt.statusMessage.contains("账号保持离线"));
        assertEquals("不再排下次尝试", 0, rt.nextRecoveryAt);
        assertEquals(0, store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);

        keeper.tickForTest();   // 无计划：不再发起尝试（仍离线）
        assertFalse(keeper.roleRuntime(FPID, SID, UID_A).online);
    }

    // ---------- stop / login / resume 清理暂停状态 ----------

    @Test
    public void stop_clearsPauseScheduleAndDesired() {
        syncAccount();
        loginOnline();
        keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "stop").code);

        VaultStore.RoleEntry e = store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(VaultStore.RUN_STOPPED, e.runState);
        assertEquals("stop 清空 pauseResumeAt", 0, e.pauseResumeAt);
        assertFalse("stop 撤销期望在线", e.desiredOnline);
    }

    @Test
    public void loginAndResume_clearPauseAndBringOnline() {
        syncAccount();
        loginOnline();
        keeper.roleAction(FPID, SID, UID_A, "pause");

        // 显式 resume：立即清除 PAUSED 与计划
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "resume").code);
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleRuntime(FPID, SID, UID_A).runState);
        assertEquals(0, store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);

        // 暂停后显式 login：等价恢复
        keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleRuntime(FPID, SID, UID_A).runState);
        assertEquals(0, store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);
    }

    // ---------- 重启：恢复计划落库后冷启动继续自动恢复 ----------

    @Test
    public void pauseSchedule_persistsAcrossRestart_andAutoResumesWhenDue() {
        syncAccount();
        store.find(FPID).takeoverInitialDelaySeconds = 0;
        loginOnline();
        keeper.roleAction(FPID, SID, UID_A, "pause");

        VaultStore reloadedStore = new VaultStore(tmp.getRoot().toPath());
        reloadedStore.load();
        assertEquals("计划落盘",
                store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt,
                reloadedStore.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);

        SessionKeeper restarted = new SessionKeeper(reloadedStore, config(8), null, stateStore,
                () -> {
                    RecoveryTestEngine e = new RecoveryTestEngine();
                    engines.add(e);
                    return e;
                }, true);
        restarted.reconcile();
        restarted.tickForTest();

        SessionControl.RoleRuntime rt = restarted.roleRuntime(FPID, SID, UID_A);
        assertTrue("重启后到期自动上线", rt.online);
        assertEquals(VaultStore.RUN_RUNNING, rt.runState);
        assertEquals(0, reloadedStore.find(FPID)
                .roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);
    }

    // ---------- 恢复设置变更：关闭时取消待恢复计划 ----------

    @Test
    public void recoverySettingsDisabled_cancelsPendingResume() {
        syncAccount();
        loginOnline();
        keeper.roleAction(FPID, SID, UID_A, "pause");
        assertNotNull(keeper.roleRuntime(FPID, SID, UID_A).statusMessage);

        store.updateRecoverySettings(FPID, false, 1800, 5, 3, 15);
        keeper.onRecoverySettingsChanged(FPID);

        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);
        assertEquals("待恢复计划已取消", 0, rt.nextRecoveryAt);
        assertTrue(rt.statusMessage.contains("顶号恢复已关闭"));
        keeper.tickForTest();
        assertFalse("关闭后不自动上线", keeper.roleRuntime(FPID, SID, UID_A).online);
    }

    // ---------- 暂停期间不执行任务（多角色并行兼容回归） ----------

    @Test
    public void pausedSession_doesNotExecuteRounds_butKeepsStoreIntent() {
        syncAccount();
        loginOnline();
        keeper.roleAction(FPID, SID, UID_A, "pause");
        assertFalse("PAUSED 不受理整轮", keeper.submitRound(FPID, "supply", "test"));
        assertTrue(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "logout").code);
    }
}
