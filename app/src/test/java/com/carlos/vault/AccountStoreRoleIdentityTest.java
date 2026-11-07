package com.carlos.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 角色级设备指纹派生（2026-11-08，对齐原版网页版 ConfigJsonLoader.scopeFingerprintToRole）：
 * 同账号同服多角色必须得到互异且稳定的 device_id/distinct_id，否则会被服务器判为
 * 同设备重复登录而互踢（网页版实测同账号同服双角色可长期并存）。
 */
public class AccountStoreRoleIdentityTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final String ACCOUNT = "{\"device_id\":\"cccccccccccccccccccccccccccccccc\","
            + "\"distinct_id\":\"dddddddddddddddddddddddddddddddd\"}";
    private static final String BASE = "{\"device_id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
            + "\"distinct_id\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
            + "\"ip\":\"111.0.1.2\",\"device\":\"Xiaomi 15\"}";


    @Test
    public void resolveRoleSeed_mainRoleWithSibling_doesNotShareAccountSeed() {
        // P1-2 回归：主角色 + 同服有兄弟 + 无已固化种子 → 不得返回账号级（否则与兄弟同设备互踢）
        assertEquals("主角色有兄弟时不得共享账号级指纹",
                "", AccountStore.resolveRoleSeed("", "", ACCOUNT, "", 123L, SID, SID, true));
        assertEquals("非主角色有兄弟时同样不共享",
                "", AccountStore.resolveRoleSeed("", "", ACCOUNT, "", 123L, SID, 0L, true));
        assertEquals("主角色无兄弟 → 沿用账号级旧指纹（不换脸）",
                ACCOUNT, AccountStore.resolveRoleSeed("", "", ACCOUNT, "", 123L, SID, SID, false));
        assertEquals("uid 未知（旧数据）→ 保持旧语义，无法判断兄弟",
                ACCOUNT, AccountStore.resolveRoleSeed("", "", ACCOUNT, "", 0L, SID, SID, true));
    }

    @Test
    public void resolveRoleSeed_prefersExactThenScopedThenLegacy() {
        assertEquals("已固化 sid:uid 指纹优先",
                "exact", AccountStore.resolveRoleSeed("exact", "sid", ACCOUNT, "scoped",
                        1L, SID, SID, true));
        assertEquals("同服有兄弟 → 派生指纹",
                "scoped", AccountStore.resolveRoleSeed("", "sid", ACCOUNT, "scoped",
                        1L, SID, SID, true));
        assertEquals("无兄弟 → 沿用旧 sid 指纹",
                "sid", AccountStore.resolveRoleSeed("", "sid", ACCOUNT, "scoped",
                        1L, SID, SID, false));
        assertEquals("uid 未知 → 保持旧行为（沿用 sid 指纹）",
                "sid", AccountStore.resolveRoleSeed("", "sid", ACCOUNT, "scoped",
                        0L, SID, SID, true));
    }

    @Test
    public void sameSidDifferentUid_distinctFingerprints() throws Exception {
        long uidA = 35371154L;
        long uidB = 88280792L;
        String a = AccountStore.roleScopedIdentity(BASE, FPID, SID, uidA);
        String b = AccountStore.roleScopedIdentity(BASE, FPID, SID, uidB);
        assertNotEquals("同服双角色 device_id 必须互异",
                new JSONObject(a).optString("device_id"), new JSONObject(b).optString("device_id"));
        assertNotEquals("同服双角色 distinct_id 必须互异",
                new JSONObject(a).optString("distinct_id"), new JSONObject(b).optString("distinct_id"));
        assertNotEquals("派生值不得等于账号级旧指纹",
                new JSONObject(BASE).optString("device_id"), new JSONObject(a).optString("device_id"));
    }

    @Test
    public void derivation_keepsNonFingerprintFields() throws Exception {
        String a = AccountStore.roleScopedIdentity(BASE, FPID, SID, 35371154L);
        JSONObject o = new JSONObject(a);
        assertEquals("ip 保持账号级（原版 scoping 只覆盖 device/distinct）", "111.0.1.2", o.optString("ip"));
        assertEquals("机型保持账号级", "Xiaomi 15", o.optString("device"));
        assertEquals(32, o.optString("device_id").length());
        assertEquals(32, o.optString("distinct_id").length());
    }

    @Test
    public void derivation_isStable() {
        String first = AccountStore.roleScopedIdentity(BASE, FPID, SID, 35371154L);
        String second = AccountStore.roleScopedIdentity(BASE, FPID, SID, 35371154L);
        assertEquals("同一角色反复派生必须完全一致（登录后固化，不漂移）", first, second);
        String otherAccount = AccountStore.roleScopedIdentity(BASE, "99999999", SID, 35371154L);
        assertFalse("不同账号同 uid 也不应撞指纹", first.equals(otherAccount));
    }

    @Test
    public void malformedBase_returnedAsIs() {
        String bad = "not-a-json";
        assertEquals("非 JSON 原样返回，绝不阻断登录",
                bad, AccountStore.roleScopedIdentity(bad, FPID, SID, 1L));
        assertTrue(AccountStore.roleScopedIdentity("", FPID, SID, 1L).isEmpty());
    }
}
