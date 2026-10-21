package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号池角色条目（VaultStore.RoleEntry）持久化与意图态（M1 契约 §2.1/§4）：
 * 角色池合并（sync 重算 desiredOnline）、显式动作落库、重启恢复期望。
 */
public class VaultStoreRoleStateTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
    }

    private VaultStore.Account acc(String fpid, boolean keep, String token) {
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.keep = keep;
        a.auto = keep;
        a.token = token;
        a.activeSid = "888";
        return a;
    }

    private VaultStore.Account acc2(String fpid, boolean keep, String token, String activeUid) {
        VaultStore.Account a = acc(fpid, keep, token);
        a.activeUid = activeUid;
        return a;
    }

    // ---------- 显式动作落库：applyRoleLogin / applyRoleStop / applyRoleRunState ----------

    @Test
    public void applyRoleLogin_marksTargetKeepsSiblingsAndSetsActive() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.ensureRole("48460403", "888", "35371154");
        store.ensureRole("48460403", "888", "88280792");
        // 先让 A 在线（多角色并行基线）
        assertTrue(store.applyRoleLogin("48460403", "888", "35371154"));

        // 登录 B：目标 desired，兄弟 A 的期望态保留（M1d 多角色并行——login 不停兄弟）
        assertTrue(store.applyRoleLogin("48460403", "888", "88280792"));

        VaultStore.Account a = store.find("48460403");
        VaultStore.RoleEntry target = a.roles.get(VaultStore.roleKey("888", "88280792"));
        VaultStore.RoleEntry sibling = a.roles.get(VaultStore.roleKey("888", "35371154"));
        assertTrue(target.desiredOnline);
        assertEquals(VaultStore.RUN_RUNNING, target.runState);
        assertTrue(target.desiredAt > 0);
        assertTrue("兄弟角色 desired 保留（多角色并行）", sibling.desiredOnline);
        // active 指向目标角色；keep/auto=true（契约 §3.2：成功后账号 keep=true）
        assertEquals("88280792", a.activeUid);
        assertTrue(a.keep);
        assertTrue(a.auto);
    }

    @Test
    public void applyRoleLoginExclusive_clearsSiblingDesired() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.ensureRole("48460403", "888", "35371154");
        store.ensureRole("48460403", "888", "88280792");
        assertTrue(store.applyRoleLogin("48460403", "888", "35371154"));

        // 账号级登录（automation 语义）：目标上线、兄弟 desired 清空（单会话收敛）
        assertTrue(store.applyRoleLoginExclusive("48460403", "888", "88280792"));

        VaultStore.Account a = store.find("48460403");
        assertTrue(a.roles.get(VaultStore.roleKey("888", "88280792")).desiredOnline);
        assertFalse(a.roles.get(VaultStore.roleKey("888", "35371154")).desiredOnline);
        assertEquals(VaultStore.RUN_STOPPED,
                a.roles.get(VaultStore.roleKey("888", "35371154")).runState);
        assertEquals("88280792", a.activeUid);
    }

    @Test
    public void applyRoleStop_keepsKeepWhileSiblingStillDesired() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.ensureRole("48460403", "888", "35371154");
        store.ensureRole("48460403", "888", "88280792");
        assertTrue(store.applyRoleLogin("48460403", "888", "35371154"));
        assertTrue(store.applyRoleLogin("48460403", "888", "88280792"));

        // stop B：A 仍 desired → keep/auto 保持（不误关账号自动化）
        assertTrue(store.applyRoleStop("48460403", "888", "88280792", false));
        VaultStore.Account a = store.find("48460403");
        assertFalse(a.roles.get(VaultStore.roleKey("888", "88280792")).desiredOnline);
        assertTrue("兄弟角色仍 desired → keep 不关", a.keep);
        assertTrue(a.auto);
    }

    @Test
    public void applyRoleStop_clearsDesiredAndDisablesKeepWhenNoneLeft() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        assertTrue(store.applyRoleLogin("48460403", "888", "35371154"));
        assertTrue(store.find("48460403").keep);

        // logout：保留 keep
        assertTrue(store.applyRoleStop("48460403", "888", "35371154", true));
        VaultStore.Account a = store.find("48460403");
        assertFalse(a.roles.get(VaultStore.roleKey("888", "35371154")).desiredOnline);
        assertTrue(a.keep);

        // stop：无其它 desired 角色 → keep/auto 关（不自动恢复）
        assertTrue(store.applyRoleStop("48460403", "888", "35371154", false));
        a = store.find("48460403");
        assertFalse(a.keep);
        assertFalse(a.auto);
        assertEquals(VaultStore.RUN_STOPPED,
                a.roles.get(VaultStore.roleKey("888", "35371154")).runState);
    }

    @Test
    public void applyRoleRunState_persistsPaused() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "35371154");

        store.applyRoleRunState("48460403", "888", "35371154", VaultStore.RUN_PAUSED);
        assertEquals(VaultStore.RUN_PAUSED,
                store.find("48460403").roles.get(VaultStore.roleKey("888", "35371154")).runState);
        // desiredOnline 不因暂停清除（重启后恢复为暂停会话）
        assertTrue(store.find("48460403")
                .roles.get(VaultStore.roleKey("888", "35371154")).desiredOnline);
    }

    // ---------- sync 合并：App 全量意图重算 desiredOnline ----------

    @Test
    public void replaceAll_recomputesDesiredOnlineFromKeepAndActive() {
        // 服务器侧曾有显式动作：B desired 且 active=B（M1 显式动作）
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "88280792");
        assertTrue(store.find("48460403")
                .roles.get(VaultStore.roleKey("888", "88280792")).desiredOnline);

        // App sync 切换 active 指针（B→A，v4.1.4 角色管理切角色即 sync）：
        // 按旧单会话语义收敛 → A desired，B 下线（App 是账号源头）
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        VaultStore.Account a = store.find("48460403");
        assertTrue(a.roles.get(VaultStore.roleKey("888", "35371154")).desiredOnline);
        assertFalse(a.roles.get(VaultStore.roleKey("888", "88280792")).desiredOnline);
        assertEquals(VaultStore.RUN_STOPPED,
                a.roles.get(VaultStore.roleKey("888", "88280792")).runState);
    }

    @Test
    public void replaceAll_keepTrueSameActive_preservesSiblingDesired() {
        // 多角色并行：A、B 先后显式 login（active=B，A/B 均 desired）
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "35371154");
        store.applyRoleLogin("48460403", "888", "88280792");

        // App sync（keep=true，active 仍是 B）：active 不变 → 兄弟 A 的 desired 保留
        // （多角色并行意图不因 sync 重算被冲掉）
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "88280792"))));
        VaultStore.Account a = store.find("48460403");
        assertTrue(a.roles.get(VaultStore.roleKey("888", "88280792")).desiredOnline);
        assertTrue("active 未变的 sync 不清兄弟 desired", a.roles.get(VaultStore.roleKey("888", "35371154")).desiredOnline);
    }

    @Test
    public void replaceAll_keepFalseAllOffline() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", false, "tk", "35371154"))));
        for (VaultStore.RoleEntry e : store.find("48460403").roles.values()) {
            assertFalse(e.desiredOnline);
            assertEquals(VaultStore.RUN_STOPPED, e.runState);
        }
    }

    @Test
    public void replaceAll_keepFalse_clearsAllDesired_multiRole() {
        // 多角色并行在线后，App sync keep=false = 全停意图：所有 desired 一并清空
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "35371154");
        store.applyRoleLogin("48460403", "888", "88280792");
        assertEquals(2, store.roles("48460403").size());

        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", false, "tk", "35371154"))));
        for (VaultStore.RoleEntry e : store.find("48460403").roles.values()) {
            assertFalse(e.desiredOnline);
            assertEquals(VaultStore.RUN_STOPPED, e.runState);
        }
    }

    @Test
    public void replaceAll_mergesIncomingRoleMetadataAndPreservesIntent() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "35371154");
        store.applyRoleRunState("48460403", "888", "35371154", VaultStore.RUN_PAUSED);

        // App sync 带 uid+name+enabled 的角色表
        VaultStore.Account in = acc2("48460403", true, "tk", "88280792");
        VaultStore.RoleEntry ra = new VaultStore.RoleEntry("888", "35371154");
        ra.name = "隆科多";
        ra.level = 70;
        ra.power = 208350000;
        VaultStore.RoleEntry rb = new VaultStore.RoleEntry("888", "88280792");
        rb.name = "小鹿矿工5号";
        in.roles.put(VaultStore.roleKey("888", "35371154"), ra);
        in.roles.put(VaultStore.roleKey("888", "88280792"), rb);
        store.replaceAll(new ArrayList<>(List.of(in)));

        VaultStore.Account a = store.find("48460403");
        assertEquals(2, a.roles.size());
        VaultStore.RoleEntry mergedA = a.roles.get(VaultStore.roleKey("888", "35371154"));
        assertEquals("隆科多", mergedA.name);
        assertEquals(70, mergedA.level);
        assertEquals(208350000, mergedA.power);
        VaultStore.RoleEntry mergedB = a.roles.get(VaultStore.roleKey("888", "88280792"));
        assertEquals("小鹿矿工5号", mergedB.name);
        // desired 重算：active 变成 B
        assertFalse(mergedA.desiredOnline);
        assertTrue(mergedB.desiredOnline);
    }

    // ---------- 持久化：vault.json 落盘 + 重启恢复（契约 §5-6） ----------

    @Test
    public void roleEntries_persistAcrossReload() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "35371154");
        store.learnRoleMeta("48460403", "888", "35371154", "隆科多", 70, 208350000);
        store.ensureRole("48460403", "888", "88280792");

        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        VaultStore.Account a = reloaded.find("48460403");
        assertEquals(2, a.roles.size());
        VaultStore.RoleEntry e = a.roles.get(VaultStore.roleKey("888", "35371154"));
        assertNotNull(e);
        assertTrue(e.desiredOnline);
        assertEquals(VaultStore.RUN_RUNNING, e.runState);
        assertEquals("隆科多", e.name);
        assertEquals(70, e.level);
        assertEquals(208350000, e.power);
        assertTrue(a.roles.get(VaultStore.roleKey("888", "88280792")).enabled);

        // 暂停态也恢复
        store.applyRoleRunState("48460403", "888", "35371154", VaultStore.RUN_PAUSED);
        VaultStore reloaded2 = new VaultStore(tmp.getRoot().toPath());
        reloaded2.load();
        assertEquals(VaultStore.RUN_PAUSED, reloaded2.find("48460403")
                .roles.get(VaultStore.roleKey("888", "35371154")).runState);
    }

    @Test
    public void roleEntries_legacyVaultJsonWithoutRoles_loadsClean() throws Exception {
        // v4.1.4 写出的 vault.json 没有 roles 键：加载不炸、条目为空
        java.nio.file.Path dataDir = tmp.getRoot().toPath();
        java.nio.file.Files.createDirectories(dataDir);
        java.nio.file.Files.writeString(dataDir.resolve("vault.json"),
                "{\"accounts\":[{\"fpid\":\"111\",\"token\":\"tk\",\"keep\":true,"
                        + "\"activeSid\":\"913\",\"windowStart\":5,\"windowEnd\":9}],"
                        + "\"takeoverPolicy\":\"human_first\"}",
                java.nio.charset.StandardCharsets.UTF_8);
        VaultStore reloaded = new VaultStore(dataDir);
        reloaded.load();
        assertNotNull(reloaded.find("111"));
        assertTrue(reloaded.roles("111").isEmpty());
    }

    // ---------- resolveRole / roles 快照 ----------

    @Test
    public void resolveRole_exactThenActiveFallback() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));

        // active 角色即使无条目也可寻址（自动建条目）
        VaultStore.RoleEntry active = store.resolveRole("48460403", "888", "35371154");
        assertNotNull(active);
        assertEquals(1, store.roles("48460403").size());

        assertNotNull(store.resolveRole("48460403", "888", "35371154"));
        assertNull(store.resolveRole("48460403", "888", "99999999"));
        assertNull(store.resolveRole("nope", "888", "35371154"));
        assertNull(store.resolveRole("48460403", "", "35371154"));

        List<VaultStore.RoleEntry> snapshot = store.roles("48460403");
        snapshot.get(0).name = "MUTATED";   // 深拷贝：不影响库内
        assertEquals("", store.roles("48460403").get(0).name);
    }

    @Test
    public void snapshot_carriesRoles() {
        store.replaceAll(new ArrayList<>(List.of(acc2("48460403", true, "tk", "35371154"))));
        store.applyRoleLogin("48460403", "888", "35371154");
        VaultStore.Account c = store.snapshot().get(0);
        assertEquals(1, c.roles.size());
        assertTrue(c.roles.get(VaultStore.roleKey("888", "35371154")).desiredOnline);
    }
}
