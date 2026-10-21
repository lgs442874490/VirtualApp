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
import java.util.Arrays;
import java.util.List;

/**
 * 账号池角色显式控制面（M1 契约 §3.2/§4/§5；2026-10-21 M1d 多角色并行语义）：
 * 状态转换/幂等/错误码、同 fpid 多角色并行（login 不停兄弟）、跨 fpid 并行、
 * 并发上限按角色会话计数、暂停停任务、持久化期望重启恢复（desired 全恢复）。
 * 引擎替身合成登录（不连网），手动驱动节拍。
 */
public class SessionKeeperRoleControlTest {

    private static final String FPID = "48460403";
    private static final String FPID2 = "80971192";
    private static final String SID = "888";
    private static final String UID_A = "35371154";   // 隆科多
    private static final String UID_B = "88280792";   // 小鹿矿工5号（同服第二角色）

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private SessionKeeper keeper;

    /** 合成引擎：登录即在线（可关），不连网；每个会话一个实例。 */
    static final class PoolTestEngine extends WjdrEngine {
        boolean online;
        boolean closed;

        PoolTestEngine() {
            super(null);
        }

        @Override
        public void initialize() {
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid) {
            online = true;
            return result(serverid == null ? 888L : serverid, 0L);
        }

        @Override
        public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) {
            online = true;
            return result(serverid == null ? 888L : serverid, roleUid);
        }

        @Override
        public Long discoverRoleUid(long kid) {
            return Long.parseLong(UID_A);
        }

        @Override
        public List<RoleInfo> discoverRoles() {
            return Arrays.asList(role(888L, Long.parseLong(UID_A), "隆科多"),
                    role(888L, Long.parseLong(UID_B), "小鹿矿工5号"));
        }

        @Override
        public String sessionState() {
            return online ? STATE_ONLINE : STATE_OFFLINE;
        }

        @Override
        public void heartbeatTick() {
        }

        @Override
        public GameMessage request(String proto, java.util.Map<String, Object> args) {
            return null;
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

        private static RoleInfo role(long sid, long uid, String name) {
            RoleInfo ri = new RoleInfo();
            ri.serverid = sid;
            ri.uid = uid;
            ri.name = name;
            return ri;
        }

        private static LoginResult result(long sid, long uid) {
            LoginResult r = new LoginResult();
            r.serverId = sid;
            r.uid = uid;
            r.gameName = uid == Long.parseLong(UID_A) ? "隆科多"
                    : (uid == Long.parseLong(UID_B) ? "小鹿矿工5号" : "角色" + uid);
            r.level = 70;
            return r;
        }
    }

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        keeper = new SessionKeeper(store, config(8), null, stateStore,
                PoolTestEngine::new, true);
    }

    private VaultConfig config(int maxSessions) {
        return new VaultConfig("127.0.0.1:0", tmp.getRoot().toPath(), "s", null, "human_first", maxSessions);
    }

    /** sync 单账号（保留库内其它账号；keep=true；active 角色与附加角色入池）。 */
    private void syncAccount(String fpid, String activeUid, String... moreRoleUids) {
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.keep = true;
        a.auto = true;
        a.token = "tk-" + fpid;
        a.activeSid = SID;
        a.activeUid = activeUid;
        List<VaultStore.Account> in = new ArrayList<>(store.snapshot());
        in.removeIf(x -> x.fpid.equals(fpid));
        in.add(a);
        store.replaceAll(in);
        store.ensureRole(fpid, SID, activeUid);
        for (String uid : moreRoleUids) store.ensureRole(fpid, SID, uid);
    }

    // ---------- §5-1 状态转换 / 幂等 / 错误码 ----------

    @Test
    public void login_thenPause_thenResume_thenStop_transitions() {
        syncAccount(FPID, UID_A, UID_B);

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "login");
        assertTrue(r.accepted);
        assertEquals(0, r.code);
        assertEquals(VaultStore.RUN_RUNNING, r.runState);
        assertTrue(r.stoppedRoles.isEmpty());
        keeper.tickForTest();   // 驱动登录
        SessionControl.RoleRuntime rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertTrue(rt.online);
        assertEquals(VaultStore.RUN_RUNNING, rt.runState);

        // pause：连接保留（online 仍真）、任务暂停
        r = keeper.roleAction(FPID, SID, UID_A, "pause");
        assertEquals(VaultStore.RUN_PAUSED, r.runState);
        rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);
        assertTrue(rt.online);
        // 暂停幂等
        assertEquals(VaultStore.RUN_PAUSED, keeper.roleAction(FPID, SID, UID_A, "pause").runState);

        // 暂停期间任务不执行（submitRound 拒绝）
        assertFalse(keeper.submitRound(FPID, "supply", "test"));

        // resume：恢复 RUNNING
        r = keeper.roleAction(FPID, SID, UID_A, "resume");
        assertEquals(VaultStore.RUN_RUNNING, r.runState);
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleRuntime(FPID, SID, UID_A).runState);
        // resume 幂等
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleAction(FPID, SID, UID_A, "resume").runState);
        assertTrue(keeper.submitRound(FPID, "supply", "test"));

        // stop：销毁会话 → STOPPED / 离线
        r = keeper.roleAction(FPID, SID, UID_A, "stop");
        assertEquals(VaultStore.RUN_STOPPED, r.runState);
        rt = keeper.roleRuntime(FPID, SID, UID_A);
        assertFalse(rt.online);
        assertEquals(VaultStore.RUN_STOPPED, rt.runState);
        assertFalse(keeper.submitRound(FPID, "supply", "test"));
        // stop 幂等（重复 stop 安全）
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "stop").code);
    }

    @Test
    public void login_idempotentWhenAlreadyOnlineAndResumesPaused() {
        syncAccount(FPID, UID_A);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        keeper.tickForTest();
        // 已在线重复 login：幂等成功（ALREADY_ONLINE 非错误）
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "login");
        assertEquals(0, r.code);
        assertTrue(r.stoppedRoles.isEmpty());

        keeper.roleAction(FPID, SID, UID_A, "pause");
        // 暂停中 login = 启动/恢复该角色会话 → RUNNING
        r = keeper.roleAction(FPID, SID, UID_A, "login");
        assertEquals(0, r.code);
        assertEquals(VaultStore.RUN_RUNNING, r.runState);
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleRuntime(FPID, SID, UID_A).runState);
    }

    @Test
    public void errorCodes_notFound_disabled_tokenMissing_unknownAction() {
        // 账号不存在
        assertEquals(1004, keeper.roleAction("nope", SID, UID_A, "login").code);
        // 缺参数
        assertEquals(3002, keeper.roleAction(FPID, null, UID_A, "login").code);
        assertEquals(3002, keeper.roleAction(FPID, SID, null, "login").code);
        assertEquals(3002, keeper.roleAction(FPID, SID, UID_A, null).code);

        syncAccount(FPID, UID_A, UID_B);
        // 角色不存在（未入库）
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_NOT_FOUND,
                keeper.roleAction(FPID, SID, "99999999", "login").code);
        // pause 未在线
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_NOT_ONLINE,
                keeper.roleAction(FPID, SID, UID_A, "pause").code);
        // 未知动作
        assertEquals(3002, keeper.roleAction(FPID, SID, UID_A, "explode").code);

        // 已收纳（enabled=false）拒绝上线
        VaultStore.Account a = store.find(FPID);
        a.roles.get(VaultStore.roleKey(SID, UID_B)).enabled = false;
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_DISABLED,
                keeper.roleAction(FPID, SID, UID_B, "login").code);

        // 无 token
        VaultStore.Account b = new VaultStore.Account("999");
        b.keep = true;
        b.activeSid = SID;
        b.activeUid = UID_A;
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(b);
        store.replaceAll(in);
        assertEquals(SessionControl.RoleOpResult.CODE_TOKEN_MISSING,
                keeper.roleAction("999", SID, UID_A, "login").code);
    }

    @Test
    public void resumeWithoutSession_delegatesToLogin() {
        syncAccount(FPID, UID_A);
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "resume");
        assertEquals(0, r.code);
        assertEquals(VaultStore.RUN_RUNNING, r.runState);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
    }

    @Test
    public void logout_preservesKeep_stopClearsIt() {
        syncAccount(FPID, UID_A);
        assertTrue(store.find(FPID).keep);
        keeper.roleAction(FPID, SID, UID_A, "logout");
        assertTrue("logout 保留 keep/自动化配置", store.find(FPID).keep);
        assertEquals(VaultStore.RUN_STOPPED,
                store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).runState);

        keeper.roleAction(FPID, SID, UID_A, "login");
        keeper.roleAction(FPID, SID, UID_A, "stop");
        assertFalse("stop 且无其它 desired → keep 关（不自动恢复）", store.find(FPID).keep);
        // keep=false 且无 desired → reconcile 不复活
        keeper.reconcile();
        assertFalse(keeper.roleRuntime(FPID, SID, UID_A).online);
    }

    // ---------- §5-2 同 fpid 多角色并行（M1d 语义） ----------

    @Test
    public void sameFpid_twoRolesParallel_loginDoesNotStopSibling() {
        syncAccount(FPID, UID_A, UID_B);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);

        // 登录 B：不停兄弟 A（stoppedRoles 恒空），两角色并行在线
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_B, "login");
        assertTrue(r.accepted);
        assertTrue("login 不停止同账号其它角色", r.stoppedRoles.isEmpty());
        keeper.tickForTest();

        SessionControl.RoleRuntime rtA = keeper.roleRuntime(FPID, SID, UID_A);
        SessionControl.RoleRuntime rtB = keeper.roleRuntime(FPID, SID, UID_B);
        assertTrue("兄弟角色 A 保持在线", rtA.online);
        assertEquals(VaultStore.RUN_RUNNING, rtA.runState);
        assertTrue(rtB.online);
        assertEquals(VaultStore.RUN_RUNNING, rtB.runState);
        assertEquals("同 fpid 两角色会话并行", 2, keeper.onlineRoleCount());

        // 库内意图：A、B 均 desiredOnline（重启后都恢复）
        VaultStore.Account a = store.find(FPID);
        assertTrue(a.roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
        assertTrue(a.roles.get(VaultStore.roleKey(SID, UID_B)).desiredOnline);

        // reconcile 不破坏并行（两角色均期望在线）
        keeper.reconcile();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);

        // 兄弟独立：暂停 A 不影响 B；停 B 后 A 仍在线
        assertEquals(VaultStore.RUN_PAUSED, keeper.roleAction(FPID, SID, UID_A, "pause").runState);
        assertEquals(VaultStore.RUN_RUNNING, keeper.roleRuntime(FPID, SID, UID_B).runState);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_B, "stop").code);
        assertFalse(keeper.roleRuntime(FPID, SID, UID_B).online);
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
    }

    @Test
    public void sameFpid_loginIdempotentPerRole_afterSiblingLogin() {
        syncAccount(FPID, UID_A, UID_B);
        keeper.roleAction(FPID, SID, UID_A, "login");
        keeper.roleAction(FPID, SID, UID_B, "login");
        keeper.tickForTest();
        // 已在线角色重复 login：幂等成功，且不冲掉兄弟
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);
        assertEquals(2, keeper.onlineRoleCount());
    }

    // ---------- §5-3 跨 fpid 并行 + 并发上限 ----------

    @Test
    public void crossFpidParallel_bothOnline_onlineCountTwo() {
        syncAccount(FPID, UID_A);
        syncAccount(FPID2, UID_A);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        assertEquals(0, keeper.roleAction(FPID2, SID, UID_A, "login").code);
        keeper.tickForTest();

        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(keeper.roleRuntime(FPID2, SID, UID_A).online);
        assertEquals(2, keeper.onlineRoleCount());
        // 账号间互不顶号（各自的 desired 独立持久化）
        assertTrue(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
        assertTrue(store.find(FPID2).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
    }

    @Test
    public void concurrencyLimit_countsRoleSessions_refusedWithCode1429() {
        keeper = new SessionKeeper(store, config(1), null, stateStore, PoolTestEngine::new, true);
        syncAccount(FPID, UID_A, UID_B);
        syncAccount(FPID2, UID_A);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        // 上限=1：同账号第二角色与跨账号角色都被拒（并发按角色会话数计）
        assertEquals(SessionControl.RoleOpResult.CODE_CONCURRENCY_LIMIT,
                keeper.roleAction(FPID, SID, UID_B, "login").code);
        SessionControl.RoleOpResult r = keeper.roleAction(FPID2, SID, UID_A, "login");
        assertFalse(r.accepted);
        assertEquals(SessionControl.RoleOpResult.CODE_CONCURRENCY_LIMIT, r.code);
        // 释放名额后可再上
        keeper.roleAction(FPID, SID, UID_A, "stop");
        assertEquals(0, keeper.roleAction(FPID2, SID, UID_A, "login").code);

        // 上限=2：同账号两角色占满 2 个名额
        keeper = new SessionKeeper(store, config(2), null, stateStore, PoolTestEngine::new, true);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_B, "login").code);
        assertEquals(SessionControl.RoleOpResult.CODE_CONCURRENCY_LIMIT,
                keeper.roleAction(FPID2, SID, UID_A, "login").code);
    }

    // ---------- §5-6 持久化：重启后 desiredOnline 恢复 ----------

    @Test
    public void desiredOnline_restoredAfterKeeperRestart() {
        syncAccount(FPID, UID_A, UID_B);
        syncAccount(FPID2, UID_A);
        // 同 fpid 两角色并行上线（M1d）：重启后全部恢复
        keeper.roleAction(FPID, SID, UID_A, "login");
        keeper.roleAction(FPID, SID, UID_B, "login");
        keeper.roleAction(FPID2, SID, UID_A, "login");
        keeper.tickForTest();

        // 模拟进程重启：重新 load 落盘的 vault.json，新 SessionKeeper reconcile
        VaultStore reloadedStore = new VaultStore(tmp.getRoot().toPath());
        reloadedStore.load();
        SessionKeeper restarted = new SessionKeeper(reloadedStore, config(8), null, stateStore,
                PoolTestEngine::new, true);
        restarted.reconcile();
        restarted.tickForTest();

        // 同 fpid 两个 desired 角色 + 跨 fpid 一条，全部恢复在线
        assertTrue(restarted.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(restarted.roleRuntime(FPID, SID, UID_B).online);
        assertTrue(restarted.roleRuntime(FPID2, SID, UID_A).online);
        assertEquals(3, restarted.onlineRoleCount());
    }

    @Test
    public void pausedDesired_restoredAsPausedAfterRestart() {
        syncAccount(FPID, UID_A);
        keeper.roleAction(FPID, SID, UID_A, "login");
        keeper.roleAction(FPID, SID, UID_A, "pause");

        VaultStore reloadedStore = new VaultStore(tmp.getRoot().toPath());
        reloadedStore.load();
        SessionKeeper restarted = new SessionKeeper(reloadedStore, config(8), null, stateStore,
                PoolTestEngine::new, true);
        restarted.reconcile();
        restarted.tickForTest();

        SessionControl.RoleRuntime rt = restarted.roleRuntime(FPID, SID, UID_A);
        assertTrue(rt.online);   // 连接恢复
        assertEquals(VaultStore.RUN_PAUSED, rt.runState);   // 暂停态保留
        assertFalse(restarted.submitRound(FPID, "supply", "test"));
    }

    @Test
    public void legacyKeepWithoutUid_stillReconcilesActiveRoleSession() {
        // v4.1.4 兼容：keep=true、activeUid 缺省、无角色条目 → 旧行为（active 角色上线）
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk";
        a.activeSid = SID;
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        keeper.reconcile();
        keeper.tickForTest();
        assertEquals(1, keeper.status().size());
        assertEquals(WjdrEngine.STATE_ONLINE, keeper.status().get(0).state);
    }

    // ---------- M1e 冷启动旧格式兼容（2026-10-21） ----------

    /** 把旧格式 vault.json 落盘并重新 load（模拟冷启动读到生产旧库副本）。 */
    private VaultStore reloadFromLegacyVaultJson(String vaultJson) throws Exception {
        java.nio.file.Files.write(tmp.getRoot().toPath().resolve("vault.json"),
                vaultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        return reloaded;
    }

    @Test
    public void legacyVaultJson_coldStartRestoresActiveRoleSession() throws Exception {
        // 生产旧库复现（M1e 缺陷）：keep=true + activeSid/activeUid，角色条目无 desiredOnline
        // 字段 → 冷启动 reconcile 必须恢复 active 角色会话，不再限定 activeUid==0
        VaultStore reloaded = reloadFromLegacyVaultJson("""
                {
                  "accounts": [
                    {
                      "fpid": "48460403",
                      "token": "tk-legacy",
                      "label": "生产旧库",
                      "keep": true,
                      "activeSid": "888",
                      "activeUid": "35371154",
                      "auto": true,
                      "roles": {
                        "888|35371154": {"sid": "888", "uid": "35371154", "name": "隆科多",
                                          "level": 70, "enabled": true}
                      }
                    }
                  ]
                }
                """);
        assertFalse(reloaded.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
        SessionKeeper restarted = new SessionKeeper(reloaded, config(8), null, stateStore,
                PoolTestEngine::new, true);
        restarted.reconcile();
        restarted.tickForTest();
        assertTrue("冷启动恢复 active 角色会话", restarted.roleRuntime(FPID, SID, UID_A).online);
        assertEquals(1, restarted.onlineRoleCount());
        // 期望集推导不改库：角色条目仍无 desired（等 App sync 收敛到新格式）
        assertFalse(reloaded.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
    }

    @Test
    public void legacyVaultJson_multiRoleNoDesired_onlyActiveRestored() throws Exception {
        // 旧格式多角色（A=active、B 兄弟）且全部无 desired → 只恢复 active 一个
        VaultStore reloaded = reloadFromLegacyVaultJson("""
                {
                  "accounts": [
                    {
                      "fpid": "48460403",
                      "token": "tk-legacy",
                      "keep": true,
                      "activeSid": "888",
                      "activeUid": "35371154",
                      "auto": true,
                      "roles": {
                        "888|35371154": {"sid": "888", "uid": "35371154", "name": "隆科多", "enabled": true},
                        "888|88280792": {"sid": "888", "uid": "88280792", "name": "小鹿矿工5号", "enabled": true}
                      }
                    }
                  ]
                }
                """);
        SessionKeeper restarted = new SessionKeeper(reloaded, config(8), null, stateStore,
                PoolTestEngine::new, true);
        restarted.reconcile();
        restarted.tickForTest();
        assertTrue(restarted.roleRuntime(FPID, SID, UID_A).online);
        assertFalse("非 active 兄弟角色不恢复", restarted.roleRuntime(FPID, SID, UID_B).online);
        assertEquals(1, restarted.onlineRoleCount());
    }

    @Test
    public void desiredEntriesTakePrecedence_fallbackNotApplied() {
        // 有 desiredOnline 条目时回落一律不生效：active（A）非 desired 也不被 keep 复活
        syncAccount(FPID, UID_A, UID_B);   // sync 合并把 active（A）desired 置位
        store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline = false;
        store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_B)).desiredOnline = true;
        keeper.reconcile();
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);
        assertFalse("active 非 desired 不被 keep 回落复活", keeper.roleRuntime(FPID, SID, UID_A).online);
        assertEquals(1, keeper.onlineRoleCount());
    }

    @Test
    public void reconcile_restoresAllDesiredRoles_withoutExplicitLogin() {
        // 落库 desired（如重启前的运行态）→ reconcile 直接按期望集合恢复同 fpid 多角色
        syncAccount(FPID, UID_A, UID_B);
        store.applyRoleLogin(FPID, SID, UID_A);
        store.applyRoleLogin(FPID, SID, UID_B);
        keeper.reconcile();
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);
        assertEquals(2, keeper.onlineRoleCount());
    }

    // ---------- 账号级入口收敛（v4.1.4 兼容语义） ----------

    @Test
    public void ensureSession_accountLevelCollapsesToActiveRoleSession() {
        syncAccount(FPID, UID_A, UID_B);
        keeper.roleAction(FPID, SID, UID_A, "login");
        keeper.roleAction(FPID, SID, UID_B, "login");
        keeper.tickForTest();
        assertEquals(2, keeper.onlineRoleCount());

        // 账号级「登录并运行自动化」= 单会话收敛：active 角色（B，最后 login 的）保留，
        // 兄弟 A 会话停止（旧 App 登录按钮语义不变；多角色并行走 role-action）
        assertTrue(keeper.ensureSession(FPID));
        keeper.tickForTest();
        assertFalse(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);
        assertEquals(1, keeper.onlineRoleCount());
        // 库内意图同步收敛：仅 active（B）desired
        assertTrue(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_B)).desiredOnline);
        assertFalse(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
    }

    @Test
    public void legacySlotSession_replacedByExplicitRoleLogin() {
        // 旧数据（keep=true、activeUid 缺省、角色条目无 desired）→ 旧 active 槽会话
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk-" + FPID;
        a.activeSid = SID;
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        store.ensureRole(FPID, SID, UID_A);
        store.ensureRole(FPID, SID, UID_B);
        keeper.reconcile();
        keeper.tickForTest();
        // 旧槽会话（键 fpid|888|）在线
        assertTrue(keeper.roleRuntime(FPID, SID, "").online);

        // 显式角色上线 A：旧槽被接管（停掉），A 以角色键建会话
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertFalse("旧槽会话已被取代", keeper.roleRuntime(FPID, SID, "").online);
        assertEquals(1, keeper.onlineRoleCount());
    }

    // ---------- 状态视图：登录学到的角色落池 ----------

    @Test
    public void loginLearnsRoleIntoPool() {
        syncAccount(FPID, UID_A);
        keeper.roleAction(FPID, SID, UID_A, "login");
        keeper.tickForTest();
        VaultStore.RoleEntry learned = store.resolveRole(FPID, SID, UID_A);
        assertNotNull(learned);
        assertEquals("隆科多", learned.name);
        assertEquals(70, learned.level);
    }
}
