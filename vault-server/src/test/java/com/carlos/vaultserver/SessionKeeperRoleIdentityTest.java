package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 角色级设备指纹（2026-11-08，对齐原版网页版 ConfigJsonLoader.scopeFingerprintToRole）：
 * 同账号同服多角色必须各自一台独立"设备"（device_id/distinct_id 不同），否则会被服务器
 * 按同设备/同账号重复登录踢掉兄弟会话。
 *
 * <p>覆盖：同服双角色派生互异且稳定、单角色沿用旧指纹、login 后按 sid:uid 固化、
 * ensureSession 在 perRole=on 时不再清兄弟期望态（perRole=off 保持旧单会话收敛）。
 */
public class SessionKeeperRoleIdentityTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID_A = "35371154";
    private static final String UID_B = "88280792";
    private static final String BASE_IDENTITY =
            "{\"device_id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                    + "\"distinct_id\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                    + "\"ip\":\"111.0.1.2\",\"device\":\"Xiaomi 15\"}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private SessionKeeper keeper;
    private final List<RecordingEngine> engines = new ArrayList<>();

    /** 合成引擎：登录即在线；记录 applyIdentity 内容与落地角色 uid（不连网）。 */
    static final class RecordingEngine extends WjdrEngine {
        String applied = "";
        long roleUid;
        boolean online;
        int defaultLoginCalls;
        int loginByRoleCalls;
        Long lastServerid;
        /** >0 时模拟"绑定未生效"：loginByRole 返回该 uid。 */
        long forceUid;

        RecordingEngine() {
            super(null);
        }

        @Override
        public void initialize() {
        }

        @Override
        public void applyIdentity(String settingsJson) {
            applied = settingsJson == null ? "" : settingsJson;
        }

        @Override
        public String identityJson() {
            return applied;
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid) {
            // 账号默认角色登录（旧实现的首次校验路径）：单独计数，测试断言"不应发生"
            defaultLoginCalls++;
            this.roleUid = 0L;
            this.lastServerid = serverid;
            this.online = true;
            LoginResult r = new LoginResult();
            r.uid = 111L;
            r.serverId = serverid == null ? 888L : serverid;
            r.gameName = "default-role";
            return r;
        }

        @Override
        public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) {
            loginByRoleCalls++;
            this.roleUid = roleUid;
            this.lastServerid = serverid;
            this.online = true;
            long actualUid = forceUid > 0 ? forceUid : roleUid;
            LoginResult r = new LoginResult();
            r.uid = actualUid;
            r.serverId = serverid == null ? 888L : serverid;
            r.gameName = "role-" + actualUid;
            r.level = 70;
            return r;
        }

        @Override
        public Long discoverRoleUid(long kid) {
            return null;
        }

        @Override
        public List<RoleInfo> discoverRoles() {
            // 角色清单（loginBoundRole 首次绑定校验用）：同服两个角色都在清单里
            RoleInfo a = new RoleInfo();
            a.serverid = 888L;
            a.uid = Long.parseLong(UID_A);
            a.name = "role-A";
            RoleInfo b = new RoleInfo();
            b.serverid = 888L;
            b.uid = Long.parseLong(UID_B);
            b.name = "role-B";
            return Arrays.asList(a, b);
        }

        @Override
        public String sessionState() {
            return online ? STATE_ONLINE : STATE_OFFLINE;
        }

        @Override
        public boolean takeoverProbeDue(long now) {
            return false;
        }

        @Override
        public void beginRecoveryNow(String reason) {
        }

        @Override
        public void heartbeatTick() {
        }

        @Override
        public GameMessage request(String proto, java.util.Map<String, Object> args) {
            return null;
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
        }
    }

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
        keeper = new SessionKeeper(store, config(8), null, stateStore, () -> {
            RecordingEngine e = new RecordingEngine();
            engines.add(e);
            return e;
        }, true);
    }

    @After
    public void tearDown() {
        VaultStore.setPerRoleModeForTest(null);
    }

    private VaultConfig config(int maxSessions) {
        return new VaultConfig("127.0.0.1:0", tmp.getRoot().toPath(), "s", null,
                "human_first", maxSessions);
    }

    /** 构造账号并入库（activeUid 在前，附加角色在后；同 sid）。 */
    private void syncAccount(String... roleUids) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk-" + FPID;
        a.activeSid = SID;
        a.activeUid = roleUids[0];
        a.roleIdentity.put(SID, BASE_IDENTITY);
        List<VaultStore.Account> in = new ArrayList<>(store.snapshot());
        in.removeIf(x -> x.fpid.equals(FPID));
        in.add(a);
        store.replaceAll(in);
        for (String uid : roleUids) store.ensureRole(FPID, SID, uid);
    }

    /** 该角色（按落地 uid）的引擎；找不到返回 null。 */
    private RecordingEngine engineOf(long uid) {
        for (RecordingEngine e : engines) {
            if (e.roleUid == uid) return e;
        }
        return null;
    }

    private static String deviceId(String identityJson) throws Exception {
        return new JSONObject(identityJson).optString("device_id", "");
    }

    private static String distinctId(String identityJson) throws Exception {
        return new JSONObject(identityJson).optString("distinct_id", "");
    }

    // ---------- 同服多角色：每角色一台独立设备 ----------

    @Test
    public void sameSidTwoRoles_getDistinctStableIdentity() throws Exception {
        syncAccount(UID_A, UID_B);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_B, "login").code);
        keeper.tickForTest();

        RecordingEngine a = engineOf(Long.parseLong(UID_A));
        RecordingEngine b = engineOf(Long.parseLong(UID_B));
        assertNotNull("A 会话已登录", a);
        assertNotNull("B 会话已登录", b);
        assertFalse("两个角色都必须注入身份", a.applied.isEmpty() || b.applied.isEmpty());
        assertTrue("同服双角色 device_id 必须不同（否则服务器判同设备互踢）",
                !deviceId(a.applied).equals(deviceId(b.applied)));
        assertTrue("同服双角色 distinct_id 必须不同",
                !distinctId(a.applied).equals(distinctId(b.applied)));
        assertTrue("两个角色的派生值都不得等于账号级旧指纹",
                !deviceId(a.applied).equals(deviceId(BASE_IDENTITY))
                        && !deviceId(b.applied).equals(deviceId(BASE_IDENTITY)));
        assertEquals("ip/机型保持账号级（对齐原版 scoping 范围）",
                "111.0.1.2", new JSONObject(a.applied).optString("ip"));

        // 登录成功后按 sid:uid 固化
        assertFalse(store.find(FPID).roleIdentity.get(VaultStore.roleKey(SID, UID_A)).isEmpty());
        assertFalse(store.find(FPID).roleIdentity.get(VaultStore.roleKey(SID, UID_B)).isEmpty());

        // 稳定性：同一账号重建 keeper（模拟服务重启）后再次登录，沿用已固化指纹
        int before = engines.size();
        SessionKeeper fresh = new SessionKeeper(store, config(8), null, stateStore, () -> {
            RecordingEngine e = new RecordingEngine();
            engines.add(e);
            return e;
        }, true);
        assertEquals(0, fresh.roleAction(FPID, SID, UID_A, "login").code);
        assertEquals(0, fresh.roleAction(FPID, SID, UID_B, "login").code);
        fresh.tickForTest();
        // 新引擎追加在末尾：取最后两个
        List<RecordingEngine> created = engines.subList(before, engines.size());
        RecordingEngine lastA = null;
        RecordingEngine lastB = null;
        for (RecordingEngine e : created) {
            if (e.roleUid == Long.parseLong(UID_A)) lastA = e;
            if (e.roleUid == Long.parseLong(UID_B)) lastB = e;
        }
        assertNotNull(lastA);
        assertNotNull(lastB);
        assertEquals("重启后 A 设备指纹稳定", deviceId(a.applied), deviceId(lastA.applied));
        assertEquals("重启后 B 设备指纹稳定", deviceId(b.applied), deviceId(lastB.applied));
        assertFalse("重启后两角色仍互异",
                deviceId(lastA.applied).equals(deviceId(lastB.applied)));
    }

    // ---------- 首次登录必须一次 uid 钉扎，不得先做默认角色登录（互顶根因） ----------

    @Test
    public void loginBoundRole_pinsUidDirectly_withoutDefaultLogin() throws Exception {
        RecordingEngine e = new RecordingEngine();
        WjdrEngine.LoginResult r = SessionKeeper.loginBoundRole(
                e, FPID, "tk", 888L, UID_A, stateStore);
        assertEquals(Long.parseLong(UID_A), r.uid);
        assertEquals("首次登录不得先做默认角色登录（会顶掉同账号兄弟）",
                0, e.defaultLoginCalls);
        assertEquals("只发生一次 uid 钉扎登录", 1, e.loginByRoleCalls);
        assertEquals("登录需带 serverid（对齐网页版载荷）", Long.valueOf(888L), e.lastServerid);
        assertEquals("绑定成功写入诊断缓存", UID_A,
                stateStore.getString("srv:roleuid:" + FPID + ":888:" + UID_A));
    }

    @Test
    public void loginBoundRole_uidMismatch_doesNotRetry() throws Exception {
        RecordingEngine e = new RecordingEngine();
        e.forceUid = 999L;   // 模拟角色被删/uid 失效，服务器落到别的角色
        WjdrEngine.LoginResult r = SessionKeeper.loginBoundRole(
                e, FPID, "tk", 888L, UID_A, stateStore);
        assertEquals(999L, r.uid);
        assertEquals("绑定失败也不得二次登录（每次额外登录都会顶掉一个兄弟）",
                0, e.defaultLoginCalls);
        assertEquals(1, e.loginByRoleCalls);
        assertEquals("绑定失败清空诊断缓存", "",
                stateStore.getString("srv:roleuid:" + FPID + ":888:" + UID_A));
    }

    // ---------- 单角色：沿用旧指纹（零迁移） ----------

    @Test
    public void singleRoleOnSid_keepsLegacyIdentity() throws Exception {
        syncAccount(UID_A);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        keeper.tickForTest();
        RecordingEngine a = engineOf(Long.parseLong(UID_A));
        assertNotNull(a);
        assertEquals("单角色沿用旧 sid 指纹（不换脸）",
                deviceId(BASE_IDENTITY), deviceId(a.applied));
    }

    // ---------- 价 Learning：按 sid:uid 隔离 ----------

    @Test
    public void learnIdentity_isolatedByUid() throws Exception {
        syncAccount(UID_A, UID_B);
        String idA = "{\"device_id\":\"11111111111111111111111111111111\"}";
        String idB = "{\"device_id\":\"22222222222222222222222222222222\"}";
        store.learnIdentity(FPID, SID, UID_A, idA);
        store.learnIdentity(FPID, SID, UID_B, idB);
        assertEquals(idA, store.find(FPID).roleIdentity.get(VaultStore.roleKey(SID, UID_A)));
        assertEquals(idB, store.find(FPID).roleIdentity.get(VaultStore.roleKey(SID, UID_B)));
        // 已有固定设备不覆盖
        store.learnIdentity(FPID, SID, UID_A, idB);
        assertEquals(idA, store.find(FPID).roleIdentity.get(VaultStore.roleKey(SID, UID_A)));
        // 旧 3 参签名（uid 未知）仍写 sid 键，兼容旧数据
        store.learnIdentity(FPID, "913", "{\"device_id\":\"33333333333333333333333333333333\"}");
        assertEquals("{\"device_id\":\"33333333333333333333333333333333\"}",
                store.find(FPID).roleIdentity.get("913"));
    }

    // ---------- 账号级 ensureSession：perRole=on 保留兄弟期望 ----------

    @Test
    public void ensureSession_perRoleOn_keepsSiblingDesired() {
        VaultStore.setPerRoleModeForTest(true);
        syncAccount(UID_A, UID_B);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_A, "login").code);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_B, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);

        assertTrue("账号级登录应成功", keeper.ensureSession(FPID));
        keeper.tickForTest();

        VaultStore.Account a = store.find(FPID);
        assertTrue("perRole=on：兄弟 A 期望态保留",
                a.roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
        assertTrue("perRole=on：兄弟 B 期望态保留",
                a.roles.get(VaultStore.roleKey(SID, UID_B)).desiredOnline);
        keeper.reconcile();
        assertTrue("reconcile 后兄弟 A 仍在线", keeper.roleRuntime(FPID, SID, UID_A).online);
        assertTrue("reconcile 后兄弟 B 仍在线", keeper.roleRuntime(FPID, SID, UID_B).online);
    }

    @Test
    public void ensureSession_perRoleOff_keepsLegacyConvergence() {
        VaultStore.setPerRoleModeForTest(false);
        syncAccount(UID_A, UID_B);
        assertEquals(0, keeper.roleAction(FPID, SID, UID_B, "login").code);
        keeper.tickForTest();

        assertTrue(keeper.ensureSession(FPID));
        VaultStore.Account a = store.find(FPID);
        // ensureSession 串联的是当前 active 角色（上一步 login B 把 active 指向 B）
        assertTrue("active 角色 B 期望在线",
                a.roles.get(VaultStore.roleKey(SID, UID_B)).desiredOnline);
        assertFalse("perRole=off：兄弟 A 期望被收敛（旧单会话语义）",
                a.roles.get(VaultStore.roleKey(SID, UID_A)).desiredOnline);
    }
}
