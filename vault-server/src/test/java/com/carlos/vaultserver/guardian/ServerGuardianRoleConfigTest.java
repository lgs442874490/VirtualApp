package com.carlos.vaultserver.guardian;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R2 Phase2C（2026-10-30）：守护盟友配置按角色 uid 读取回归。
 *
 * <p>修复前 {@code reloadConfig} 只读 {@code roleParams[sid]}（区服默认），同服双角色只能共用一套
 * 守护配置；修复后 {@code VaultStore.roleParamsOf(acc,sid,uid)} 优先读 {@code roleParamsByUid}，
 * 缺省回退 sid 默认。本测试直接驱动包可见的 {@code reloadConfig(true)} 与 {@code config()}。
 */
public class ServerGuardianRoleConfigTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore vault;
    private StateStore state;

    @Before
    public void setUp() {
        vault = new VaultStore(tmp.getRoot().toPath());
        state = new StateStore(tmp.getRoot().toPath());
    }

    private static String cfg(boolean enabled, boolean dryRun) {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("dry_run", dryRun);
        return o.toString();
    }

    /** 写入区服默认（sid 表）与角色 uid 表。 */
    private void seed(String sidJson, long uid, String uidJson) {
        VaultStore.Account a = new VaultStore.Account(FPID);
        a.token = "tk";
        a.keep = true;
        a.auto = true;
        a.activeSid = String.valueOf(SID);
        if (sidJson != null) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("guardian", sidJson);
            a.roleParams.put(String.valueOf(SID), m);
        }
        if (uid > 0 && uidJson != null) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("guardian", uidJson);
            a.roleParamsByUid.put(SID + ":" + uid, m);
        }
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(a);
        vault.replaceAll(in);
    }

    private ServerGuardianEngine engine(long uid) {
        return new ServerGuardianEngine(FPID, SID, uid, state, vault, null);
    }

    @Test
    public void uidParamsTakePrecedenceOverSidDefault() {
        // 区服默认关+演练；角色 B 单独开实盘
        seed(cfg(false, true), UID_B, cfg(true, false));
        ServerGuardianEngine b = engine(UID_B);
        b.reloadConfig(true);
        assertTrue("角色 B 应读到自己的 uid 配置（enabled）", b.config().enabled);
        assertFalse("角色 B 应读到自己的 uid 配置（实盘）", b.config().dryRun);
    }

    @Test
    public void siblingFallsBackToSidDefault() {
        seed(cfg(true, false), UID_B, cfg(false, true));
        ServerGuardianEngine a = engine(UID_A);
        a.reloadConfig(true);
        assertTrue("角色 A 无 uid 配置 → 回退区服默认（enabled）", a.config().enabled);
        assertFalse("角色 A 无 uid 配置 → 回退区服默认（实盘）", a.config().dryRun);

        ServerGuardianEngine b = engine(UID_B);
        b.reloadConfig(true);
        assertFalse("角色 B 的 uid 配置覆盖区服默认（关闭）", b.config().enabled);
    }

    @Test
    public void legacyUidZeroStillReadsSidDefault() {
        seed(cfg(true, false), UID_B, cfg(false, true));
        ServerGuardianEngine legacy = new ServerGuardianEngine(FPID, SID, state, vault);
        legacy.reloadConfig(true);
        assertTrue("uid=0 旧路径仍读区服默认", legacy.config().enabled);
    }
}
