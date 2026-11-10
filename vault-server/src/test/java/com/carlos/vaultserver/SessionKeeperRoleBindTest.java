package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.WjdrEngine.LoginResult;
import com.carlos.vault.wjdr.WjdrEngine.RoleInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 登录目标角色绑定（loginBoundRole）单测：同服多角色 activeUid>0 直绑路径 vs 旧回退路径。
 * 引擎替身合成登录/角色清单（不连网），状态存储用临时目录真 store。
 */
public class SessionKeeperRoleBindTest {

    private static final String FPID = "48460403";
    private static final String TOKEN = "tk-48460403";
    private static final long KID = 888L;
    private static final long UID_A = 35371154L;   // 隆科多
    private static final long UID_B = 88280792L;   // 小鹿矿工5号（同服第二角色）

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore stateStore;

    /** 合成引擎：记录调用序列，按预置角色清单应答。 */
    private static final class TestEngine extends WjdrEngine {
        final List<String> calls = new ArrayList<>();
        /** null = 清单里没有目标 uid（校验失败回退）；否则角色清单。 */
        List<RoleInfo> roles = new ArrayList<>();
        /** discoverRoleUid(kid) 的应答（旧路径发现）。 */
        Long discoverUid;
        /** loginByRole 实际落地 uid（null = 按请求 uid 落地）。 */
        Long landUid;

        TestEngine() {
            super(null);
        }

        @Override
        public void initialize() throws Exception {
            calls.add("initialize");
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid) throws Exception {
            calls.add("login:" + serverid);
            return result(serverid == null ? 0L : serverid, 0L);
        }

        @Override
        public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) throws Exception {
            calls.add("loginByRole:" + roleUid);
            long uid = landUid == null ? roleUid : landUid;
            return result(serverid == null ? 0L : serverid, uid);
        }

        @Override
        public Long discoverRoleUid(long kid) {
            calls.add("discoverRoleUid:" + kid);
            return discoverUid;
        }

        @Override
        public List<RoleInfo> discoverRoles() throws Exception {
            calls.add("discoverRoles");
            return roles;
        }

        private static LoginResult result(long sid, long uid) {
            LoginResult r = new LoginResult();
            r.serverId = sid;
            r.uid = uid;
            r.gameName = "角色" + uid;
            return r;
        }
    }

    private static RoleInfo role(long sid, long uid) {
        RoleInfo ri = new RoleInfo();
        ri.serverid = sid;
        ri.uid = uid;
        ri.name = "角色" + uid;
        return ri;
    }

    @Before
    public void setUp() throws Exception {
        stateStore = new StateStore(tmp.getRoot().toPath());
        stateStore.load();
    }

    private TestEngine engineWithBothRoles() {
        TestEngine e = new TestEngine();
        e.roles = Arrays.asList(role(KID, UID_A), role(KID, UID_B));
        return e;
    }

    // ---------- activeUid>0：同服多角色直绑 ----------

    @Test
    public void activeUid_positive_bindsDirectlyWithoutDefaultLogin() throws Exception {
        TestEngine e = engineWithBothRoles();
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, String.valueOf(UID_B), stateStore);
        // 2026-11-08：直接按 uid 钉扎登录，不得先做"默认角色登录"（那条会话会顶掉兄弟角色）
        assertEquals(Arrays.asList("loginByRole:" + UID_B), e.calls);
        assertEquals(UID_B, r.uid);
        // 缓存键含 uid（与旧 sid 键互不干扰），二次登录免校验
        assertEquals(String.valueOf(UID_B),
                stateStore.getString("srv:roleuid:" + FPID + ":" + KID + ":" + UID_B));
    }

    @Test
    public void activeUid_positive_cacheHitSkipsValidation() throws Exception {
        stateStore.putString("srv:roleuid:" + FPID + ":" + KID + ":" + UID_A, String.valueOf(UID_A));
        TestEngine e = engineWithBothRoles();
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, String.valueOf(UID_A), stateStore);
        assertEquals(Arrays.asList("loginByRole:" + UID_A), e.calls);
        assertEquals(UID_A, r.uid);
    }

    @Test
    public void activeUid_positive_sameSidSecondRoleDoesNotCollide() throws Exception {
        // 同服两个角色的缓存键互不覆盖：先绑 A 再绑 B，两次都直达
        stateStore.putString("srv:roleuid:" + FPID + ":" + KID + ":" + UID_A, String.valueOf(UID_A));
        TestEngine e = engineWithBothRoles();
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, String.valueOf(UID_B), stateStore);
        // B 首次同样直接按 uid 登录（不再做默认登录清单校验）
        assertEquals(Arrays.asList("loginByRole:" + UID_B), e.calls);
        assertEquals(UID_B, r.uid);
    }

    @Test
    public void activeUid_positive_bindFails_clearsCacheAndReportsActual() throws Exception {
        stateStore.putString("srv:roleuid:" + FPID + ":" + KID + ":" + UID_B, String.valueOf(UID_B));
        TestEngine e = engineWithBothRoles();
        e.landUid = UID_A;   // 绑定未生效：落在另一个角色上
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, String.valueOf(UID_B), stateStore);
        assertEquals(UID_A, r.uid);   // 按实际角色上线，不静默
        assertEquals("", stateStore.getString("srv:roleuid:" + FPID + ":" + KID + ":" + UID_B));
    }

    @Test
    public void activeUid_positive_staleUid_bindsOnceWithoutFallback() throws Exception {
        // App 下发的 uid 已过期：仍只发一次 uid 钉扎登录，不再先默认登录、也不再二次回退登录
        //（每次额外登录都会顶掉一个兄弟角色；落错由返回 uid 校验与上层 verifyIdentity 如实告警）。
        TestEngine e = new TestEngine();
        e.roles = Arrays.asList(role(KID, UID_A));   // 清单里只有 A（旧实现会回退发现 A）
        e.discoverUid = UID_A;
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, "99999999", stateStore);
        assertEquals(Arrays.asList("loginByRole:99999999"), e.calls);
        assertEquals(99999999L, r.uid);
        assertEquals("99999999",
                stateStore.getString("srv:roleuid:" + FPID + ":" + KID + ":99999999"));
    }

    // ---------- activeUid 缺省：旧行为不变 ----------

    @Test
    public void activeUid_absent_legacyDiscoveryUnchanged() throws Exception {
        TestEngine e = new TestEngine();
        e.discoverUid = UID_A;
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, null, stateStore);
        assertEquals(Arrays.asList("login:null", "discoverRoleUid:" + KID, "initialize",
                "loginByRole:" + UID_A), e.calls);
        assertEquals(UID_A, r.uid);
        // 旧缓存键（srv:roleuid:fpid:sid）仍写 uid 值
        assertEquals(String.valueOf(UID_A), stateStore.getString("srv:roleuid:" + FPID + ":" + KID));
    }

    @Test
    public void activeUid_absent_legacyCacheHitBindsDirectly() throws Exception {
        stateStore.putString("srv:roleuid:" + FPID + ":" + KID, String.valueOf(UID_B));
        TestEngine e = engineWithBothRoles();
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, "", stateStore);
        assertEquals(Arrays.asList("loginByRole:" + UID_B), e.calls);
        assertEquals(UID_B, r.uid);
    }

    @Test
    public void activeUid_blankAndGarbage_treatedAsAbsent() throws Exception {
        for (String raw : new String[]{"", "  ", "abc", "-5", "0"}) {
            // 每轮全新目录：旧路径会把发现的 uid flush 落盘，同目录复用会缓存命中串味
            StateStore fresh = new StateStore(tmp.newFolder().toPath());
            fresh.load();
            TestEngine e = new TestEngine();
            e.discoverUid = UID_A;
            LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, raw, fresh);
            assertEquals("raw=" + raw, UID_A, r.uid);
            assertTrue("raw=" + raw, e.calls.contains("discoverRoleUid:" + KID));
        }
    }

    @Test
    public void noSid_defaultLogin() throws Exception {
        TestEngine e = new TestEngine();
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, 0, String.valueOf(UID_B), stateStore);
        assertEquals(Arrays.asList("login:null"), e.calls);
        assertNotNull(r);
    }

    @Test
    public void parseUid_semantics() {
        assertEquals(0L, SessionKeeper.parseUid(null));
        assertEquals(0L, SessionKeeper.parseUid(""));
        assertEquals(0L, SessionKeeper.parseUid("0"));
        assertEquals(0L, SessionKeeper.parseUid("-1"));
        assertEquals(0L, SessionKeeper.parseUid("abc"));
        assertEquals(UID_B, SessionKeeper.parseUid(" 88280792 "));
    }

    @Test
    public void legacy_noRoleOnKid_logsInAsDefault() throws Exception {
        TestEngine e = new TestEngine();
        e.discoverUid = null;   // 目标区无角色
        LoginResult r = SessionKeeper.loginBoundRole(e, FPID, TOKEN, KID, null, stateStore);
        // 默认角色上线 → 发现失败 → 再按默认角色上线返回（与旧实现一致）
        assertEquals(Arrays.asList("login:null", "discoverRoleUid:" + KID, "login:null"), e.calls);
        assertFalse(r.uid > 0 && r.serverId == KID);
        assertNull(stateStore.getString("srv:roleuid:" + FPID + ":" + KID));
    }
}
