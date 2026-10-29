package com.carlos.vaultserver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 1（2026-10-29，双角色独立联盟）门控单测：
 * 兄弟角色（非主控）跑联盟科技捐献前的角色级任务开关判定
 * {@link SessionKeeper#roleTaskEnabled(VaultStore.Account, long, String)}。
 *
 * <p>口径与 TaskCadenceScheduler.enabledTaskIds 一致：roleConfig 显式值优先，
 * 缺省回退任务目录默认（alliance_donate 默认开启）。
 */
public class SessionKeeperRoleScopedTest {

    @Test
    public void explicitRoleConfigOverridesCatalogDefault() {
        VaultStore.Account a = new VaultStore.Account("48460403");
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("alliance_donate", false);
        a.roleConfig.put("888", cfg);
        assertFalse("显式关闭必须生效（兄弟角色不捐献）",
                SessionKeeper.roleTaskEnabled(a, 888L, "alliance_donate"));

        cfg.put("alliance_donate", true);
        assertTrue("显式开启生效",
                SessionKeeper.roleTaskEnabled(a, 888L, "alliance_donate"));
    }

    @Test
    public void missingConfigFallsBackToCatalogDefault() {
        VaultStore.Account a = new VaultStore.Account("48460403");
        assertTrue("alliance_donate 目录默认开启",
                SessionKeeper.roleTaskEnabled(a, 888L, "alliance_donate"));
        assertFalse("目录中不存在 → 不开",
                SessionKeeper.roleTaskEnabled(a, 888L, "no_such_fn"));
        assertFalse("账号为空 → 不开",
                SessionKeeper.roleTaskEnabled(null, 888L, "alliance_donate"));
    }

    @Test
    public void configIsScopedByServerId() {
        VaultStore.Account a = new VaultStore.Account("48460403");
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("alliance_donate", false);
        a.roleConfig.put("888", cfg);
        assertFalse(SessionKeeper.roleTaskEnabled(a, 888L, "alliance_donate"));
        assertTrue("其他区服无显式配置 → 回退默认",
                SessionKeeper.roleTaskEnabled(a, 913L, "alliance_donate"));
    }
}
