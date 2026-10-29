package com.carlos.vaultserver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

/**
 * R2 Phase 2（2026-10-29）：VAULT_PER_ROLE=on 时 App 切换当前角色不停止兄弟角色会话。
 *
 * <p>背景：旧 sync 语义「切角色 = 单会话收敛」会在 activeUid 变化时把其它 desired 角色
 * 置为 STOPPED（VaultStore.mergeRolesLocked）。Phase 2 双角色并行的目标要求切换只改
 * “当前展示角色”，兄弟角色继续自动化；off 档保持旧语义不变（可回滚）。
 */
public class VaultStorePerRoleSwitchTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        VaultStore.setPerRoleModeForTest(null);
    }

    @After
    public void tearDown() {
        VaultStore.setPerRoleModeForTest(null);
    }

    /** App 全量同步的账号对象：keep/auto=true、activeUid 指定、两个同服角色。 */
    private VaultStore.Account account(String activeUid) {
        VaultStore.Account a = new VaultStore.Account("48460403");
        a.keep = true;
        a.auto = true;
        a.token = "tk";
        a.activeSid = "888";
        a.activeUid = activeUid;
        VaultStore.RoleEntry r1 = new VaultStore.RoleEntry("888", "35371154");
        r1.enabled = true;
        r1.name = "隆科多";
        VaultStore.RoleEntry r2 = new VaultStore.RoleEntry("888", "88280792");
        r2.enabled = true;
        r2.name = "小鹿矿工5号";
        a.roles.put("888:35371154", r1);
        a.roles.put("888:88280792", r2);
        return a;
    }

    /** 基线：两角色都已 desired 在线（相当于「账号页→全部上线」之后），当前 active=隆科多。 */
    private void seedBothOnline() {
        store.replaceAll(new ArrayList<>(List.of(account("35371154"))));
        // 先登录 B、再登录 A：最终 activeUid = 35371154（隆科多），便于模拟“切到小鹿”
        assertTrue(store.applyRoleLogin("48460403", "888", "88280792"));
        assertTrue(store.applyRoleLogin("48460403", "888", "35371154"));
        assertTrue(desired("35371154"));
        assertTrue(desired("88280792"));
    }

    private boolean desired(String uid) {
        VaultStore.Account a = store.find("48460403");
        assertNotNull(a);
        VaultStore.RoleEntry e = a.roles.get("888:" + uid);
        assertNotNull("角色条目缺失: " + uid, e);
        return e.desiredOnline;
    }

    @Test
    public void perRoleOn_switchActiveRoleKeepsSiblingOnline() {
        seedBothOnline();
        VaultStore.setPerRoleModeForTest(true);
        // App 切到小鹿（activeUid 变化）：兄弟角色隆科多必须保持在线
        store.replaceAll(new ArrayList<>(List.of(account("88280792"))));
        VaultStore.Account a = store.find("48460403");
        assertNotNull(a);
        assertTrue("新 active 角色保持 desired", desired("88280792"));
        assertTrue("per-role on：兄弟角色不得被切换连带停止", desired("35371154"));
    }

    @Test
    public void perRoleOff_switchActiveRoleStopsSibling_legacySemantics() {
        seedBothOnline();
        VaultStore.setPerRoleModeForTest(false);
        store.replaceAll(new ArrayList<>(List.of(account("88280792"))));
        assertTrue(desired("88280792"));
        assertFalse("per-role off：旧 sync 语义=切角色单会话收敛", desired("35371154"));
    }

    @Test
    public void keepFalse_stillStopsAllRolesRegardlessOfPerRole() {
        seedBothOnline();
        VaultStore.setPerRoleModeForTest(true);
        VaultStore.Account incoming = account("88280792");
        incoming.keep = false;
        incoming.auto = false;
        store.replaceAll(new ArrayList<>(List.of(incoming)));
        assertFalse("关常驻（keep=false）仍应停全部角色", desired("35371154"));
        assertFalse("关常驻（keep=false）仍应停全部角色", desired("88280792"));
    }
}
