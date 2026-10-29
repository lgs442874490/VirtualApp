package com.carlos.vaultserver.guardian;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.VaultStore;

import org.junit.After;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R2 Phase2B（2026-10-29，用户口径）：战争态只对「开启守护盟友且实盘（需要队列驻防盟友）」的
 * 角色触发。{@link ServerGuardianEngine#queueDefenseActive} 是唯一判定入口。
 *
 * <p>规则：enabled && !dry_run（parseConfig 已合并 env 覆盖）；
 * 参数读取 uid 优先 → sid 回退；无参数/异常一律 false（fail-closed）。
 */
public class ServerGuardianQueueDefenseTest {

    private static final long SID = 888L;
    private static final long UID = 88280792L;

    @After
    public void clearEnvOverride() {
        System.clearProperty("VAULT_GUARDIAN_ENABLED");
        System.clearProperty("VAULT_GUARDIAN_DRY_RUN");
    }

    private static VaultStore.Account account(String sidGuardianJson, String uidGuardianJson) {
        VaultStore.Account a = new VaultStore.Account("48460403");
        if (sidGuardianJson != null) {
            Map<String, String> sidParams = new LinkedHashMap<>();
            sidParams.put(ServerGuardianEngine.FN_ID, sidGuardianJson);
            a.roleParams.put(String.valueOf(SID), sidParams);
        }
        if (uidGuardianJson != null) {
            Map<String, String> uidParams = new LinkedHashMap<>();
            uidParams.put(ServerGuardianEngine.FN_ID, uidGuardianJson);
            a.roleParamsByUid.put(SID + ":" + UID, uidParams);
        }
        return a;
    }

    private static String cfg(boolean enabled, boolean dryRun) {
        return "{\"enabled\":" + enabled + ",\"dry_run\":" + dryRun + "}";
    }

    @Test
    public void enabledAndLive_isQueueDefenseActive() {
        assertTrue(ServerGuardianEngine.queueDefenseActive(account(cfg(true, false), null), SID, UID));
    }

    @Test
    public void dryRun_isNotQueueDefenseActive() {
        assertFalse("演练不真实驻防、不占队列 → 不触发战争态",
                ServerGuardianEngine.queueDefenseActive(account(cfg(true, true), null), SID, UID));
    }

    @Test
    public void disabled_isNotQueueDefenseActive() {
        assertFalse(ServerGuardianEngine.queueDefenseActive(account(cfg(false, false), null), SID, UID));
        assertFalse("无参数 fail-closed", ServerGuardianEngine.queueDefenseActive(
                new VaultStore.Account("48460403"), SID, UID));
        assertFalse("账号为空安全返回", ServerGuardianEngine.queueDefenseActive(null, SID, UID));
    }

    @Test
    public void uidParamsTakePrecedenceOverSidDefault() {
        // 区服默认关、角色 uid 开（实盘）→ 该角色应触发
        assertTrue(ServerGuardianEngine.queueDefenseActive(
                account(cfg(false, false), cfg(true, false)), SID, UID));
        // 区服默认开、角色 uid 关 → 该角色不触发
        assertFalse(ServerGuardianEngine.queueDefenseActive(
                account(cfg(true, false), cfg(false, false)), SID, UID));
    }

    @Test
    public void envOverrideStillDecides() {
        System.setProperty("VAULT_GUARDIAN_ENABLED", "0");
        System.setProperty("VAULT_GUARDIAN_DRY_RUN", "0");
        assertFalse("env 关闭覆盖参数", ServerGuardianEngine.queueDefenseActive(
                account(cfg(true, false), null), SID, UID));

        System.setProperty("VAULT_GUARDIAN_ENABLED", "1");
        System.setProperty("VAULT_GUARDIAN_DRY_RUN", "0");
        assertTrue("env 实盘开启（参数缺省）",
                ServerGuardianEngine.queueDefenseActive(account(null, null), SID, UID));

        System.setProperty("VAULT_GUARDIAN_DRY_RUN", "1");
        assertFalse("env 演练仍不算队列驻防", ServerGuardianEngine.queueDefenseActive(
                account(cfg(true, false), null), SID, UID));
    }
}
