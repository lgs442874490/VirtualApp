package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 角色管理增强（2026-10-21 契约 §二.1/§二.3-§二.5）：
 * rename 校验/在线协议/51 道具不足 use_diamond 重试/离线报错、
 * remove 停会话清 active、enabled 收纳与启用。引擎替身可脚本化协议响应。
 */
public class SessionKeeperRoleOpsTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID_A = "35371154";
    private static final String UID_B = "88280792";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private StateStore stateStore;
    private SessionKeeper keeper;
    private final List<OpsTestEngine> engines = new ArrayList<>();

    /** 合成引擎：登录即在线；协议响应可脚本化；记录请求。 */
    static final class OpsTestEngine extends WjdrEngine {
        boolean online;
        boolean closed;
        final List<String> protocols = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();
        final List<GameMessage> scripted = new ArrayList<>();

        OpsTestEngine() {
            super(null);
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

        private LoginResult doLogin(long uid) {
            online = true;
            LoginResult r = new LoginResult();
            r.uid = uid;
            r.serverId = 888L;
            r.gameName = "隆科多";
            return r;
        }

        @Override
        public Long discoverRoleUid(long kid) {
            return Long.parseLong(UID_A);
        }

        @Override
        public java.util.List<RoleInfo> discoverRoles() {
            return Arrays.asList(role(888L, Long.parseLong(UID_A), "隆科多"),
                    role(888L, Long.parseLong(UID_B), "小鹿矿工5号"));
        }

        private static RoleInfo role(long sid, long uid, String name) {
            RoleInfo ri = new RoleInfo();
            ri.serverid = sid;
            ri.uid = uid;
            ri.name = name;
            return ri;
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
            protocols.add(proto);
            payloads.add(new LinkedHashMap<>(args));   // 快照：调用方可复用/改写原 map
            GameMessage m = new GameMessage();
            if (!scripted.isEmpty()) {
                m.setContent(scripted.remove(0).getContent());
            } else {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("code", 0);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("nickName", "新名字");
                content.put("result", result);
                m.setContent(content);
            }
            return m;
        }

        static GameMessage msg(Map<String, Object> content) {
            GameMessage m = new GameMessage();
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
                    OpsTestEngine e = new OpsTestEngine();
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
        store.ensureRole(FPID, SID, UID_B);
    }

    private void loginOnline(String uid) {
        assertEquals(0, keeper.roleAction(FPID, SID, uid, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, uid).online);
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, value);
        return p;
    }

    // ---------- op rename（契约 §二.3） ----------

    @Test
    public void rename_validationErrors_3002() {
        syncAccount();
        loginOnline(UID_A);
        engines.get(0).protocols.clear();
        engines.get(0).payloads.clear();
        assertEquals(3002, keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", null)).code);
        assertEquals("角色名不能为空", keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "")).message);
        assertEquals("角色名前后不能有空格", keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", " 隆科多")).message);
        assertEquals("角色名不能包含换行或控制字符", keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "隆科多\n二")).message);
        assertEquals("角色名长度需为 3~12 个字", keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "名字")).message);
        assertEquals("角色名长度需为 3~12 个字", keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "一二三四五六七八九十一二三四")).message);
        // 校验失败不打协议
        assertTrue(engines.get(0).protocols.isEmpty());
    }

    @Test
    public void rename_requiresOnlineRole() {
        syncAccount();
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "隆科多二世"));
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_NOT_ONLINE, r.code);
        assertEquals("角色不在线，无法修改角色名", r.message);
    }

    @Test
    public void rename_success_appliesNickName_writesBack() {
        syncAccount();
        loginOnline(UID_A);
        store.setRoleName(FPID, SID, UID_A, "隆科多");
        engines.get(0).protocols.clear();
        engines.get(0).payloads.clear();

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "隆科多二世"));
        assertEquals(0, r.code);
        assertEquals("新名字", r.name);   // 以协议 result.nickName 为准
        assertFalse(r.usedDiamond);
        assertEquals(0, r.diamondCost);
        assertEquals(610001L, r.itemId);
        // 协议请求与落库回写
        assertEquals(1, engines.get(0).protocols.size());
        assertEquals("req_player_modify_name", engines.get(0).protocols.get(0));
        assertEquals(Boolean.FALSE, engines.get(0).payloads.get(0).get("use_diamond"));
        assertEquals("新名字", store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).name);
    }

    @Test
    public void rename_itemShortage51_retriesWithDiamond() {
        syncAccount();
        loginOnline(UID_A);
        engines.get(0).protocols.clear();
        engines.get(0).payloads.clear();
        Map<String, Object> shortage = new LinkedHashMap<>();
        shortage.put("code", 51);
        engines.get(0).scripted.add(OpsTestEngine.msg(shortage));

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "隆科多二世"));
        assertEquals(0, r.code);
        assertTrue("51 → use_diamond 重试成功", r.usedDiamond);
        assertEquals(400L, r.diamondCost);
        assertEquals(610001L, r.itemId);
        // 两次协议：第一次 use_diamond=false，第二次 true
        assertEquals(2, engines.get(0).protocols.size());
        assertEquals(Boolean.FALSE, engines.get(0).payloads.get(0).get("use_diamond"));
        assertEquals(Boolean.TRUE, engines.get(0).payloads.get(1).get("use_diamond"));
    }

    @Test
    public void rename_errItemNotEnoughDescriptor_retriesWithDiamond() {
        syncAccount();
        loginOnline(UID_A);
        Map<String, Object> shortage = new LinkedHashMap<>();
        shortage.put("code", 999);
        shortage.put("err", "err_item_not_enough");
        engines.get(0).scripted.add(OpsTestEngine.msg(shortage));

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "隆科多二世"));
        assertEquals(0, r.code);
        assertTrue(r.usedDiamond);
    }

    @Test
    public void rename_protocolError_3002() {
        syncAccount();
        loginOnline(UID_A);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", 700);
        engines.get(0).scripted.add(OpsTestEngine.msg(err));

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "rename",
                payload("name", "隆科多二世"));
        assertEquals(3002, r.code);
        assertTrue(r.message.contains("700"));
    }

    // ---------- op remove（契约 §二.4） ----------

    @Test
    public void remove_stopsSession_clearsEntry_andActivePointersWhenActive() {
        syncAccount();
        loginOnline(UID_A);   // A 为 active

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_A, "remove");
        assertEquals(0, r.code);
        assertEquals("角色已移除（游戏内角色未删除）", r.message);
        assertTrue("会话已停", engines.get(0).closed);
        assertFalse(keeper.roleRuntime(FPID, SID, UID_A).online);
        assertNull("条目已从工作区移除",
                store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)));
        assertNull("active 指针清空", store.find(FPID).activeSid);
        // 角色不存在
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_NOT_FOUND,
                keeper.roleAction(FPID, SID, UID_A, "remove").code);
    }

    @Test
    public void remove_nonActiveRole_keepsActivePointers() {
        syncAccount();
        loginOnline(UID_A);
        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_B, "remove");
        assertEquals(0, r.code);
        assertEquals(UID_A, store.find(FPID).activeUid);
        assertNull(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_B)));
    }

    // ---------- op enabled（契约 §二.5） ----------

    @Test
    public void enabled_archivedStopsSession_andBlocksLogin() {
        syncAccount();
        loginOnline(UID_B);

        SessionControl.RoleOpResult r = keeper.roleAction(FPID, SID, UID_B, "enabled",
                payload("enabled", false));
        assertEquals(0, r.code);
        assertEquals("已收纳", r.message);
        assertFalse(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_B)).enabled);
        assertTrue("收纳停会话", engines.get(0).closed);
        assertFalse(keeper.roleRuntime(FPID, SID, UID_B).online);
        // 已收纳拒绝上线
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_DISABLED,
                keeper.roleAction(FPID, SID, UID_B, "login").code);

        r = keeper.roleAction(FPID, SID, UID_B, "enabled", payload("enabled", true));
        assertEquals(0, r.code);
        assertEquals("已启用", r.message);
        assertTrue(store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_B)).enabled);
        // 启用后可再次上线
        assertEquals(0, keeper.roleAction(FPID, SID, UID_B, "login").code);
        keeper.tickForTest();
        assertTrue(keeper.roleRuntime(FPID, SID, UID_B).online);
    }
}
