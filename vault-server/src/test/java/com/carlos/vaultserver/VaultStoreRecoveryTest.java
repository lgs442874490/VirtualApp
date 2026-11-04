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
 * P1 恢复策略 / 暂停恢复计划（VaultStore 持久化层，2026-10-21 契约 §一）：
 * 默认值、旧数据加载容错、落盘重载、恢复设置校验文案、removeRole/enabled 落库、
 * pauseResumeAt 生命周期（pause 置位 / stop-login 清除）、sync 不冲掉服务器侧设置。
 */
public class VaultStoreRecoveryTest {

    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID_A = "35371154";
    private static final String UID_B = "88280792";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
        store.load();
    }

    private VaultStore.Account seed(String activeUid, String... moreUids) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.keep = true;
        a.auto = true;
        a.token = "tk";
        a.activeSid = SID;
        a.activeUid = activeUid;
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        store.replaceAll(in);
        store.ensureRole(FPID, SID, activeUid);
        for (String uid : moreUids) store.ensureRole(FPID, SID, uid);
        return store.find(FPID);
    }

    // ---------- 默认值（契约 §一：true / 1800 / 5 / 3 / 15） ----------

    @Test
    public void recoveryDefaults_onNewAccount() {
        VaultStore.Account a = seed(UID_A);
        assertTrue(a.takeoverRecoveryEnabled);
        assertEquals(1800, a.takeoverInitialDelaySeconds);
        assertEquals(5, a.takeoverRetryIntervalSeconds);
        assertEquals(3, a.takeoverMaxAttempts);
        assertEquals(15, a.takeoverCooldownMinutes);
        // 角色级新字段默认：无恢复计划
        VaultStore.RoleEntry e = a.roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(0, e.pauseResumeAt);
    }

    @Test
    public void recoveryAndRoleFields_persistAcrossReload() {
        seed(UID_A, UID_B);
        store.updateRecoverySettings(FPID, false, 3600, 30, 7, 120);
        store.applyRolePauseSchedule(FPID, SID, UID_A, 499999999999L);

        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        VaultStore.Account a = reloaded.find(FPID);
        assertFalse(a.takeoverRecoveryEnabled);
        assertEquals(3600, a.takeoverInitialDelaySeconds);
        assertEquals(30, a.takeoverRetryIntervalSeconds);
        assertEquals(7, a.takeoverMaxAttempts);
        assertEquals(120, a.takeoverCooldownMinutes);
        VaultStore.RoleEntry ea = a.roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(499999999999L, ea.pauseResumeAt);
        assertTrue("暂停保留期望在线", ea.desiredOnline);
        assertEquals(VaultStore.RUN_PAUSED, ea.runState);
        // 未设置的角色保持默认
        VaultStore.RoleEntry eb = a.roles.get(VaultStore.roleKey(SID, UID_B));
        assertEquals(0, eb.pauseResumeAt);
    }

    @Test
    public void legacyVaultJson_missingRecoveryFields_defaultsApplied() throws Exception {
        // 生产旧库（v4.1.5）无恢复策略字段：加载容错回默认，不炸不漂
        java.nio.file.Files.write(tmp.getRoot().toPath().resolve("vault.json"),
                ("{\"accounts\":[{\"fpid\":\"" + FPID + "\",\"token\":\"tk\",\"keep\":true,"
                        + "\"activeSid\":\"" + SID + "\",\"activeUid\":\"" + UID_A + "\","
                        + "\"roles\":{\"" + SID + "|" + UID_A + "\":{\"sid\":\"" + SID
                        + "\",\"uid\":\"" + UID_A + "\",\"name\":\"隆科多\",\"enabled\":true}}}],"
                        + "\"takeoverPolicy\":\"human_first\"}").getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        VaultStore.Account a = reloaded.find(FPID);
        assertNotNull(a);
        assertTrue(a.takeoverRecoveryEnabled);
        assertEquals(1800, a.takeoverInitialDelaySeconds);
        assertEquals(5, a.takeoverRetryIntervalSeconds);
        assertEquals(3, a.takeoverMaxAttempts);
        assertEquals(15, a.takeoverCooldownMinutes);
        VaultStore.RoleEntry e = a.roles.get(VaultStore.roleKey(SID, UID_A));
        assertNotNull(e);
        assertEquals(0, e.pauseResumeAt);
        // 非法值兜底回默认（加载容错）
        reloaded = reloadWith("\"takeoverInitialDelaySeconds\":-5,\"takeoverRetryIntervalSeconds\":999,"
                + "\"takeoverMaxAttempts\":99,\"takeoverCooldownMinutes\":20");
        assertEquals(1800, reloaded.find(FPID).takeoverInitialDelaySeconds);
        assertEquals(5, reloaded.find(FPID).takeoverRetryIntervalSeconds);
        assertEquals(3, reloaded.find(FPID).takeoverMaxAttempts);
        assertEquals(15, reloaded.find(FPID).takeoverCooldownMinutes);
    }

    /** 在 vault.json 账号对象里注入给定字段片段后重载（非法值容错用）。 */
    private VaultStore reloadWith(String extraAccountFields) throws Exception {
        java.nio.file.Path p = tmp.getRoot().toPath().resolve("vault.json");
        String json = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        json = json.replace("\"keep\":true,", "\"keep\":true," + extraAccountFields + ",");
        java.nio.file.Files.writeString(p, json, java.nio.charset.StandardCharsets.UTF_8);
        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        return reloaded;
    }

    // ---------- updateRecoverySettings 持久化 + 校验文案（契约 §一） ----------

    @Test
    public void updateRecoverySettings_persists() {
        seed(UID_A);
        assertTrue(store.updateRecoverySettings(FPID, false, 90, 60, 1, 1440));
        VaultStore.Account a = store.find(FPID);
        assertFalse(a.takeoverRecoveryEnabled);
        assertEquals(90, a.takeoverInitialDelaySeconds);
        assertEquals(60, a.takeoverRetryIntervalSeconds);
        assertEquals(1, a.takeoverMaxAttempts);
        assertEquals(1440, a.takeoverCooldownMinutes);
        assertFalse("账号不存在返回 false", store.updateRecoverySettings("nope", true, 60, 5, 3, 15));
    }

    @Test
    public void validateRecovery_contractMessages() {
        assertNull(VaultStore.validateRecovery(30, 5, 3, 15));
        assertNull("边界通过", VaultStore.validateRecovery(1, 1, 0, 1440));
        assertNull("边界通过", VaultStore.validateRecovery(1440, 300, 10, 60));
        assertEquals("顶号后等待必须在 1 到 1440 分钟之间",
                VaultStore.validateRecovery(0, 5, 3, 15));
        assertEquals("顶号后等待必须在 1 到 1440 分钟之间",
                VaultStore.validateRecovery(1441, 5, 3, 15));
        assertEquals("重试间隔秒数必须在 1 到 300 之间",
                VaultStore.validateRecovery(30, 301, 3, 15));
        assertEquals("连续尝试次数必须在 0 到 10 之间",
                VaultStore.validateRecovery(30, 5, 11, 15));
        assertEquals("冷却时长只支持 15 分钟、30 分钟、1 小时、2 小时或 24 小时",
                VaultStore.validateRecovery(30, 5, 3, 20));
    }

    // ---------- 角色管理增强落库：remove / name / enabled ----------

    @Test
    public void removeRole_clearsEntryAndActivePointersWhenActive() {
        seed(UID_A, UID_B);   // active=A
        assertTrue(store.removeRole(FPID, SID, UID_A));
        VaultStore.Account a = store.find(FPID);
        assertNull(a.roles.get(VaultStore.roleKey(SID, UID_A)));
        assertNull("移除 active 角色 → activeSid 置空", a.activeSid);
        assertNull(a.activeUid);
        // 移除非 active 角色不影响 active 指针
        assertTrue(store.removeRole(FPID, SID, UID_B));
        assertFalse("角色不存在返回 false", store.removeRole(FPID, SID, UID_A));
    }

    @Test
    public void roleMutators_name_enabled_persist() {
        seed(UID_A, UID_B);
        assertTrue(store.setRoleName(FPID, SID, UID_A, "隆科多二世"));
        assertTrue(store.setRoleEnabled(FPID, SID, UID_B, false));

        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        assertEquals("隆科多二世",
                reloaded.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).name);
        assertFalse(reloaded.find(FPID).roles.get(VaultStore.roleKey(SID, UID_B)).enabled);
    }

    // ---------- pauseResumeAt 生命周期 ----------

    @Test
    public void pauseSchedule_lifecycle_setClearStopLogin() {
        seed(UID_A);
        store.applyRoleLogin(FPID, SID, UID_A);
        // 置计划：PAUSED + 时刻，desiredOnline 保留
        store.applyRolePauseSchedule(FPID, SID, UID_A, 123456789L);
        VaultStore.RoleEntry e = store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(VaultStore.RUN_PAUSED, e.runState);
        assertEquals(123456789L, e.pauseResumeAt);
        assertTrue(e.desiredOnline);

        // 清计划：恢复 RUNNING
        store.clearRolePause(FPID, SID, UID_A);
        e = store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(VaultStore.RUN_RUNNING, e.runState);
        assertEquals(0, e.pauseResumeAt);

        // stop 清计划；login 清计划
        store.applyRolePauseSchedule(FPID, SID, UID_A, 123456789L);
        store.applyRoleStop(FPID, SID, UID_A, false);
        assertEquals(0, store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A)).pauseResumeAt);
        store.applyRolePauseSchedule(FPID, SID, UID_A, 123456789L);
        store.applyRoleLogin(FPID, SID, UID_A);
        e = store.find(FPID).roles.get(VaultStore.roleKey(SID, UID_A));
        assertEquals(VaultStore.RUN_RUNNING, e.runState);
        assertEquals("显式 login 清除暂停恢复计划", 0, e.pauseResumeAt);
    }

    // ---------- sync 不冲掉服务器侧设置（App 不携带这些字段） ----------

    @Test
    public void sync_preservesServerRecoverySettings() {
        seed(UID_A);
        store.updateRecoverySettings(FPID, false, 7200, 10, 1, 30);

        // App 全量 sync（不带恢复策略字段）
        VaultStore.Account in = new VaultStore.Account(FPID);
        in.keep = true;
        in.auto = true;
        in.token = "tk2";
        in.activeSid = SID;
        in.activeUid = UID_A;
        VaultStore.RoleEntry ra = new VaultStore.RoleEntry(SID, UID_A);
        ra.name = "隆科多";
        in.roles.put(VaultStore.roleKey(SID, UID_A), ra);
        List<VaultStore.Account> list = new ArrayList<>();
        list.add(in);
        store.replaceAll(list);

        VaultStore.Account a = store.find(FPID);
        assertFalse("恢复设置跨 sync 保留", a.takeoverRecoveryEnabled);
        assertEquals(7200, a.takeoverInitialDelaySeconds);
        assertEquals(30, a.takeoverCooldownMinutes);
    }
}
