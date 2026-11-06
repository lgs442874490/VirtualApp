package com.carlos.vault.license;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11-06 · 角色（role）与功能矩阵（features）单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>旧票据（无 role/features）HMAC 规范化串逐字节不变（兼容旧签名）；</li>
 *   <li>role 仅非默认 3 时参与签名；features 仅 featuresPresent 时参与（空数组也入串）；</li>
 *   <li>篡改 role（3→4）/ 删除 features / 伪造 featuresPresent=false → 签名串变化；</li>
 *   <li>toJson/fromJson 往返；applyServerRoleFeatures 字段缺失保持原值；</li>
 *   <li>{@link LicenseConfig#normalizeRole} / {@link LicenseConfig#roleName}。</li>
 * </ul>
 */
public class LicenseRoleFeatureTest {

    private static final String KEY = "role-feature-test-key-2026";

    private static LicenseTicket baseTicket() {
        LicenseTicket t = new LicenseTicket();
        t.code = "K7M9-PQRS-TUVW-XYZL";
        t.ticketId = "t_1_0123456789abcdef";
        t.type = LicenseConfig.TYPE_MONTH;
        t.activatedAt = 1757844000000L;
        t.expireAt = 1760436000000L;
        t.serverStatus = LicenseConfig.STATUS_ACTIVE;
        t.deviceHash = repeat("a", 64);
        t.salt = repeat("b", 32);
        t.lastVerifiedAt = 1757844600000L;
        t.clockOffsetMs = 0L;
        return t;
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ---------- 归一化 ----------

    @Test
    public void roleNormalization() {
        assertEquals(1, LicenseConfig.normalizeRole(1));
        assertEquals(2, LicenseConfig.normalizeRole(2));
        assertEquals(3, LicenseConfig.normalizeRole(3));
        assertEquals(4, LicenseConfig.normalizeRole(4));
        assertEquals("0 → 默认 3", 3, LicenseConfig.normalizeRole(0));
        assertEquals("负数 → 默认 3", 3, LicenseConfig.normalizeRole(-9));
        assertEquals("超界 → 默认 3", 3, LicenseConfig.normalizeRole(99));

        assertEquals("普通", LicenseConfig.roleName(1));
        assertEquals("会员", LicenseConfig.roleName(2));
        assertEquals("高级会员", LicenseConfig.roleName(3));
        assertEquals("GM", LicenseConfig.roleName(4));
        assertEquals("未知按高级会员", "高级会员", LicenseConfig.roleName(0));
    }

    // ---------- 旧票据兼容 ----------

    @Test
    public void legacyTicket_canonicalUnchanged() {
        LicenseTicket t = baseTicket();
        String canonical = t.canonicalForSign();
        // 默认 role=3、featuresPresent=false：不得出现 role/features 键。
        assertFalse("旧票据不应出现 role 键: " + canonical, canonical.contains("\"role\""));
        assertFalse("旧票据不应出现 features 键: " + canonical, canonical.contains("\"features\""));
        // 与 tier 测试中的冻结旧串前缀一致（完整串由 LicenseTierTest 锁定）。
        assertTrue(canonical.startsWith("{\"activatedAt\":1757844000000,"));
        assertTrue(canonical.endsWith("\"type\":\"month\"}"));
    }

    // ---------- role 参与签名 ----------

    @Test
    public void roleNonDefault_inSignature() {
        LicenseTicket gm = baseTicket();
        gm.role = LicenseConfig.TIER_GM;
        String canonical = gm.canonicalForSign();
        assertTrue("GM 的 role=4 必须入串: " + canonical, canonical.contains("\"role\":4"));

        LicenseTicket basic = baseTicket();
        basic.role = LicenseConfig.TIER_BASIC;
        assertTrue("T1 的 role=1 必须入串: " + basic.canonicalForSign(),
                basic.canonicalForSign().contains("\"role\":1"));

        String sigGm = LicenseCrypto.hmacSha256Hex(KEY, gm.canonicalForSign());
        String sigBasic = LicenseCrypto.hmacSha256Hex(KEY, basic.canonicalForSign());
        assertNotEquals("不同 role 的签名必须不同", sigGm, sigBasic);
    }

    // ---------- features 参与签名 ----------

    @Test
    public void features_signatureStableAndSorted() {
        LicenseTicket t = baseTicket();
        t.featuresPresent = true;
        t.features = Arrays.asList("today_tab", "account_pool", "account_pool");
        String canonical = t.canonicalForSign();
        assertTrue("features 应按字典序去重: " + canonical,
                canonical.contains("\"features\":\"account_pool,today_tab\""));

        // 顺序不同但内容相同 → 规范化串一致。
        LicenseTicket t2 = baseTicket();
        t2.featuresPresent = true;
        t2.features = Arrays.asList("account_pool", "today_tab");
        assertEquals(t.canonicalForSign(), t2.canonicalForSign());
    }

    @Test
    public void emptyFeatures_participatesInSignature() {
        LicenseTicket t = baseTicket();
        t.featuresPresent = true;
        t.features = Collections.emptyList();
        String canonical = t.canonicalForSign();
        assertTrue("空矩阵也必须入串（区分缺失）: " + canonical,
                canonical.contains("\"features\":\"\""));

        // 伪造 featuresPresent=false 会改变签名串 → 篡改可被发现。
        LicenseTicket forged = t.copy();
        forged.featuresPresent = false;
        assertNotEquals("删除 features 字段必须改变签名串",
                t.canonicalForSign(), forged.canonicalForSign());
    }

    @Test
    public void forgedRoleChange_detected() {
        LicenseTicket t = baseTicket();
        t.role = LicenseConfig.TIER_BASIC;
        String legit = LicenseCrypto.hmacSha256Hex(KEY, t.canonicalForSign());

        LicenseTicket upgraded = t.copy();
        upgraded.role = LicenseConfig.TIER_GM;
        assertNotEquals("T1→GM 提权必须改变签名",
                legit, LicenseCrypto.hmacSha256Hex(KEY, upgraded.canonicalForSign()));

        LicenseTicket noFeatures = t.copy();
        noFeatures.featuresPresent = true;
        noFeatures.features = Arrays.asList("account_pool");
        assertNotEquals("本地添加功能必须改变签名",
                legit, LicenseCrypto.hmacSha256Hex(KEY, noFeatures.canonicalForSign()));
    }

    // ---------- 序列化往返 ----------

    @Test
    public void jsonRoundTrip_roleAndFeatures() {
        LicenseTicket t = baseTicket();
        t.role = LicenseConfig.TIER_GM;
        t.featuresPresent = true;
        t.features = Arrays.asList("enemy_watch", "account_pool");

        LicenseTicket parsed = LicenseTicket.fromJson(t.toJson());
        assertNotNull(parsed);
        assertEquals(4, parsed.role);
        assertTrue(parsed.featuresPresent);
        assertEquals(Arrays.asList("account_pool", "enemy_watch"), parsed.normalizedFeatures());
        assertEquals("往返后签名串一致", t.canonicalForSign(), parsed.canonicalForSign());
    }

    @Test
    public void fromLegacyJson_defaultsToRoleThreeAndNoMatrix() {
        String json = "{\"code\":\"K7M9-PQRS-TUVW-XYZL\",\"ticketId\":\"t_1_0123456789abcdef\","
                + "\"type\":\"month\",\"activatedAt\":1757844000000,\"expireAt\":1760436000000,"
                + "\"serverStatus\":\"active\",\"deviceHash\":\"" + repeat("a", 64) + "\","
                + "\"salt\":\"" + repeat("b", 32) + "\",\"lastVerifiedAt\":1757844600000,"
                + "\"clockOffsetMs\":0,\"sig\":\"\"}";
        LicenseTicket t = LicenseTicket.fromJson(json);
        assertNotNull(t);
        assertEquals(3, t.role);
        assertFalse(t.featuresPresent);
        assertTrue(t.normalizedFeatures().isEmpty());
    }

    // ---------- 服务端下发解析 ----------

    @Test
    public void applyServerRoleFeatures_parsesAndKeepsOnMissing() throws Exception {
        LicenseTicket t = baseTicket();
        JSONObject data = new JSONObject();
        data.put("role", 2);
        data.put("features", new org.json.JSONArray(Arrays.asList("today_tab", "account_pool")));
        t.applyServerRoleFeatures(data);
        assertEquals(2, t.role);
        assertTrue(t.featuresPresent);
        assertEquals(Arrays.asList("account_pool", "today_tab"), t.normalizedFeatures());

        // 字段缺失：保持原值（旧服务端不清空矩阵）。
        JSONObject empty = new JSONObject();
        t.applyServerRoleFeatures(empty);
        assertEquals(2, t.role);
        assertTrue(t.featuresPresent);
        assertEquals(2, t.normalizedFeatures().size());

        // 空数组：明确无功能。
        JSONObject none = new JSONObject();
        none.put("role", 1);
        none.put("features", new org.json.JSONArray());
        t.applyServerRoleFeatures(none);
        assertEquals(1, t.role);
        assertTrue(t.featuresPresent);
        assertTrue(t.normalizedFeatures().isEmpty());
    }

    @Test
    public void applyServerTicket_parsesRoleFeatures() throws Exception {
        LicenseTicket t = new LicenseTicket();
        JSONObject ticket = new JSONObject();
        ticket.put("code", "K7M9-PQRS-TUVW-XYZL");
        ticket.put("ticketId", "t_1_0123456789abcdef");
        ticket.put("type", "permanent");
        ticket.put("tier", 3);
        ticket.put("role", 4);
        ticket.put("features", new org.json.JSONArray(Arrays.asList("account_pool", "enemy_watch")));
        t.applyServerTicket(ticket);
        assertEquals(4, t.role);
        assertEquals(3, t.tier);
        assertTrue(t.featuresPresent);
        assertEquals(Arrays.asList("account_pool", "enemy_watch"), t.normalizedFeatures());
    }
}
