package com.carlos.vault.license;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次7 · 兑换码三级权限（App 侧）单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link LicenseConfig} tier 常量 / {@code normalizeTier} / {@code tierName}；</li>
 *   <li>旧票据（无 tier 字段）→ 默认 3（高级会员），且 HMAC 规范化串与批次7 之前**逐字节一致**，
 *       旧签名继续通过、不失效（冻结向量由独立 Python 双算）；</li>
 *   <li>新票据 tier=1/2/3 的 JSON 落盘/反序列化/HMAC；tier=3 保持旧签名口径；tier=1/2 参与签名；</li>
 *   <li>篡改 tier → 签名串变化 → HMAC 不再匹配（TAMPERED 判定依据）；</li>
 *   <li>旧票据反序列化后再落盘（写入 tier:3）仍保持旧签名串可校验；</li>
 *   <li>{@link LicenseManager#tier()}：无票据 → TIER_BASIC（最小权限）；旧票据 → TIER_PREMIUM（不降权）。</li>
 * </ul>
 */
public class LicenseTierTest {

    /** 票据 HMAC 测试密钥（representation 层验证；真实 App 用 ticketKey 派生）。 */
    private static final String KEY = "tier-test-ticket-key-2026";

    // ---- 冻结向量（Python hashlib/hmac 独立计算）----
    /** 旧票据（无 tier）规范化串 = 批次7 之前口径，必须保持不变。 */
    private static final String OLD_CANONICAL =
            "{\"activatedAt\":1757844000000,\"clockOffsetMs\":0,\"code\":\"K7M9-PQRS-TUVW-XYZL\","
                    + "\"deviceHash\":\"" + repeat("a", 64) + "\",\"expireAt\":1760436000000,"
                    + "\"lastVerifiedAt\":1757844600000,\"salt\":\"" + repeat("b", 32) + "\","
                    + "\"serverStatus\":\"active\",\"ticketId\":\"t_1_0123456789abcdef\",\"type\":\"month\"}";
    private static final String OLD_HMAC =
            "40125656bc1ae92802eb140e875b0245b909de4d8bebc7919cc6ff5fc5d5f4fd";
    private static final String TIER1_CANONICAL =
            "{\"activatedAt\":1757844000000,\"clockOffsetMs\":0,\"code\":\"K7M9-PQRS-TUVW-XYZL\","
                    + "\"deviceHash\":\"" + repeat("a", 64) + "\",\"expireAt\":1760436000000,"
                    + "\"lastVerifiedAt\":1757844600000,\"salt\":\"" + repeat("b", 32) + "\","
                    + "\"serverStatus\":\"active\",\"ticketId\":\"t_1_0123456789abcdef\",\"tier\":1,"
                    + "\"type\":\"month\"}";
    private static final String TIER1_HMAC =
            "a66571c97cc176a84a1389b75a58f7ee16a964b5cb21b920e5d921910c797191";
    private static final String TIER2_CANONICAL =
            "{\"activatedAt\":1757844000000,\"clockOffsetMs\":0,\"code\":\"K7M9-PQRS-TUVW-XYZL\","
                    + "\"deviceHash\":\"" + repeat("a", 64) + "\",\"expireAt\":1760436000000,"
                    + "\"lastVerifiedAt\":1757844600000,\"salt\":\"" + repeat("b", 32) + "\","
                    + "\"serverStatus\":\"active\",\"ticketId\":\"t_1_0123456789abcdef\",\"tier\":2,"
                    + "\"type\":\"month\"}";
    private static final String TIER2_HMAC =
            "1809751bad8e2756af81ad693a7b8db1e097fcbe9e891c6629c38c6c374e1d03";

    /** 真实 ticketKey = SHA256("nocert|com.carlos.multiapp|vault-license-v1")（单测无签名证书时兜底）。 */
    private static final String TEST_TICKET_KEY =
            "de7243f48f565f30e00d73eb8b626f560c0ca8e0d21a058af38af932b5c5559f";
    /** 旧票据落盘 JSON（无 tier 字段）+ 旧签名（用 TEST_TICKET_KEY 计算）。 */
    private static final String OLD_JSON =
            "{\"code\":\"K7M9-PQRS-TUVW-XYZL\",\"ticketId\":\"t_1_0123456789abcdef\","
                    + "\"type\":\"month\",\"activatedAt\":1757844000000,\"expireAt\":1760436000000,"
                    + "\"serverStatus\":\"active\",\"deviceHash\":\"" + repeat("a", 64) + "\","
                    + "\"salt\":\"" + repeat("b", 32) + "\",\"lastVerifiedAt\":1757844600000,"
                    + "\"clockOffsetMs\":0,\"sig\":\""
                    + "0ecb97ff8b289ed54a52c068daf96e3d380c38ad9275d93cec8839958ee5bbab\"}";
    /** 新票据 tier=2 落盘 JSON + 签名（用 TEST_TICKET_KEY 计算）。 */
    private static final String TIER2_JSON =
            "{\"code\":\"K7M9-PQRS-TUVW-XYZL\",\"ticketId\":\"t_1_0123456789abcdef\","
                    + "\"type\":\"month\",\"tier\":2,\"activatedAt\":1757844000000,"
                    + "\"expireAt\":1760436000000,\"serverStatus\":\"active\","
                    + "\"deviceHash\":\"" + repeat("a", 64) + "\",\"salt\":\"" + repeat("b", 32) + "\","
                    + "\"lastVerifiedAt\":1757844600000,\"clockOffsetMs\":0,\"sig\":\""
                    + "91ee505685cfd9c62c096a23fd953036e87e716e2c01b173529987c8d68b95ab\"}";

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** 构造与冻结向量一致的票据（默认 tier=3 走旧签名口径）。 */
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

    // ======================= 1. 常量 / 归一化 =======================

    @Test
    public void tierConstants_frozen() {
        assertEquals(1, LicenseConfig.TIER_BASIC);
        assertEquals(2, LicenseConfig.TIER_MEMBER);
        assertEquals(3, LicenseConfig.TIER_PREMIUM);
        assertEquals("存量兼容默认档=高级会员", LicenseConfig.TIER_PREMIUM, LicenseConfig.TIER_DEFAULT);

        assertEquals(LicenseConfig.TIER_BASIC, LicenseConfig.normalizeTier(1));
        assertEquals(LicenseConfig.TIER_MEMBER, LicenseConfig.normalizeTier(2));
        assertEquals(LicenseConfig.TIER_PREMIUM, LicenseConfig.normalizeTier(3));
        assertEquals("0 → 默认 3（存量兼容）", LicenseConfig.TIER_PREMIUM, LicenseConfig.normalizeTier(0));
        assertEquals("负数 → 默认 3", LicenseConfig.TIER_PREMIUM, LicenseConfig.normalizeTier(-7));
        assertEquals("超界 → 默认 3", LicenseConfig.TIER_PREMIUM, LicenseConfig.normalizeTier(99));

        assertEquals("普通", LicenseConfig.tierName(LicenseConfig.TIER_BASIC));
        assertEquals("会员", LicenseConfig.tierName(LicenseConfig.TIER_MEMBER));
        assertEquals("高级会员", LicenseConfig.tierName(LicenseConfig.TIER_PREMIUM));
        assertEquals("未知值按高级会员兼容", "高级会员", LicenseConfig.tierName(42));
    }

    // ======================= 2. 旧票据：默认 3 + HMAC 兼容 =======================

    @Test
    public void oldTicketWithoutTier_defaultsToPremium_andKeepsLegacyHmac() {
        LicenseTicket t = LicenseTicket.fromJson(OLD_JSON);
        assertNotNull(t);
        assertEquals("旧票据无 tier → 默认高级会员", LicenseConfig.TIER_PREMIUM, t.tier);
        assertEquals("旧票据规范化串必须与批次7 之前逐字节一致", OLD_CANONICAL, t.canonicalForSign());
        assertEquals("旧签名继续通过（不失效）", OLD_HMAC,
                LicenseCrypto.hmacSha256Hex(KEY, t.canonicalForSign()));
        // 旧票据按批次7 逻辑重新落盘（会写入 tier:3），但签名口径不变，仍可校验
        LicenseTicket reloaded = LicenseTicket.fromJson(t.toJson());
        assertNotNull(reloaded);
        assertEquals(LicenseConfig.TIER_PREMIUM, reloaded.tier);
        assertEquals("重落盘后签名串仍为旧口径", OLD_CANONICAL, reloaded.canonicalForSign());
        assertEquals(OLD_HMAC, LicenseCrypto.hmacSha256Hex(KEY, reloaded.canonicalForSign()));
    }

    // ======================= 3. 新票据：1/2/3 解析与 HMAC =======================

    @Test
    public void newTicket_tierOneAndTwoSignedInCanonical() {
        LicenseTicket t1 = baseTicket();
        t1.tier = LicenseConfig.TIER_BASIC;
        assertEquals(TIER1_CANONICAL, t1.canonicalForSign());
        assertEquals(TIER1_HMAC, LicenseCrypto.hmacSha256Hex(KEY, t1.canonicalForSign()));

        LicenseTicket t2 = baseTicket();
        t2.tier = LicenseConfig.TIER_MEMBER;
        assertEquals(TIER2_CANONICAL, t2.canonicalForSign());
        assertEquals(TIER2_HMAC, LicenseCrypto.hmacSha256Hex(KEY, t2.canonicalForSign()));

        // JSON 落盘 → 反序列化保持 tier
        LicenseTicket parsed = LicenseTicket.fromJson(t2.toJson());
        assertNotNull(parsed);
        assertEquals(LicenseConfig.TIER_MEMBER, parsed.tier);
        assertEquals(TIER2_CANONICAL, parsed.canonicalForSign());
    }

    @Test
    public void newTicket_premiumTierKeepsLegacySignature() {
        // tier=3 是默认档：即使显式下发，也走旧规范化串（不改变签名口径）
        LicenseTicket t = baseTicket();
        t.tier = LicenseConfig.TIER_PREMIUM;
        assertEquals(OLD_CANONICAL, t.canonicalForSign());
        assertEquals(OLD_HMAC, LicenseCrypto.hmacSha256Hex(KEY, t.canonicalForSign()));
    }

    @Test
    public void tamperedTier_breaksHmac() throws Exception {
        // 真实场景：新票据 tier=2，攻击者改 SharedPreferences 里的 tier
        LicenseTicket t2 = LicenseTicket.fromJson(TIER2_JSON);
        assertNotNull(t2);
        assertEquals(LicenseConfig.TIER_MEMBER, t2.tier);
        String legitSig = LicenseCrypto.hmacSha256Hex(KEY, t2.canonicalForSign());
        assertEquals(TIER2_HMAC, legitSig);

        // 2 → 3（试图升档）：规范化串退化为旧口径，签名不再匹配
        JSONObject o = new JSONObject(TIER2_JSON);
        o.put("tier", 3);
        LicenseTicket upgraded = LicenseTicket.fromJson(o.toString());
        assertNotNull(upgraded);
        assertEquals(LicenseConfig.TIER_PREMIUM, upgraded.tier);
        assertNotEquals("改档必须导致签名不符（TAMPERED）", legitSig,
                LicenseCrypto.hmacSha256Hex(KEY, upgraded.canonicalForSign()));

        // 2 → 1（降档）：同样改变签名串
        o.put("tier", 1);
        LicenseTicket downgraded = LicenseTicket.fromJson(o.toString());
        assertNotNull(downgraded);
        assertNotEquals(legitSig, LicenseCrypto.hmacSha256Hex(KEY, downgraded.canonicalForSign()));

        // 旧票据（无 tier）手工插入 tier:1 也会改变签名串
        JSONObject old = new JSONObject(OLD_JSON);
        old.put("tier", 1);
        LicenseTicket forged = LicenseTicket.fromJson(old.toString());
        assertNotNull(forged);
        assertNotEquals("旧签名不得接受被插入的 tier", OLD_HMAC,
                LicenseCrypto.hmacSha256Hex(KEY, forged.canonicalForSign()));
    }

    // ======================= 4. 服务端响应填充 / 拷贝 =======================

    @Test
    public void applyServerTicket_readsTierWithCompat() throws Exception {
        // 新服务端：ticket.tier 下发
        LicenseTicket t = new LicenseTicket();
        JSONObject newServer = new JSONObject();
        newServer.put("code", "K7M9-PQRS-TUVW-XYZL");
        newServer.put("ticketId", "t_1_x");
        newServer.put("type", "month");
        newServer.put("tier", 1);
        t.applyServerTicket(newServer);
        assertEquals(LicenseConfig.TIER_BASIC, t.tier);

        // 旧服务端：无 tier 字段 → 保持默认 3（存量兼容，不降权）
        LicenseTicket oldServer = new LicenseTicket();
        JSONObject legacy = new JSONObject();
        legacy.put("code", "K7M9-PQRS-TUVW-XYZL");
        legacy.put("ticketId", "t_1_x");
        legacy.put("type", "month");
        oldServer.applyServerTicket(legacy);
        assertEquals(LicenseConfig.TIER_PREMIUM, oldServer.tier);

        // 非法 tier → 归一化 3
        LicenseTicket invalid = new LicenseTicket();
        JSONObject bad = new JSONObject();
        bad.put("code", "X");
        bad.put("tier", 99);
        invalid.applyServerTicket(bad);
        assertEquals(LicenseConfig.TIER_PREMIUM, invalid.tier);
    }

    @Test
    public void copy_preservesTierAndSignature() {
        LicenseTicket t = baseTicket();
        t.tier = LicenseConfig.TIER_BASIC;
        LicenseTicket c = t.copy();
        assertEquals(LicenseConfig.TIER_BASIC, c.tier);
        assertEquals(t.canonicalForSign(), c.canonicalForSign());
        // 修改副本不影响原件
        c.tier = LicenseConfig.TIER_PREMIUM;
        assertEquals(LicenseConfig.TIER_BASIC, t.tier);
    }

    // ======================= 5. LicenseManager.tier() 门控 getter =======================

    @Test
    public void managerTier_noTicketIsBasic_oldTicketIsPremium() {
        Context ctx = mock(Context.class);
        SharedPreferences sp = mock(SharedPreferences.class);
        PackageManager pm = mock(PackageManager.class);
        when(ctx.getApplicationContext()).thenReturn(ctx);
        when(ctx.getPackageName()).thenReturn("com.carlos.multiapp");
        when(ctx.getSharedPreferences(LicenseConfig.SP_NAME, Context.MODE_PRIVATE)).thenReturn(sp);
        when(ctx.getPackageManager()).thenReturn(pm);
        when(ctx.getFilesDir()).thenReturn(null);

        LicenseManager lm = LicenseManager.get(ctx);

        // 1) 无票据：最小权限 TIER_BASIC，且不含会员/高级能力
        assertEquals("无票据按最小权限 fail-safe", LicenseConfig.TIER_BASIC, lm.tier());
        assertTrue(lm.tierAtLeast(LicenseConfig.TIER_BASIC));
        assertFalse(lm.tierAtLeast(LicenseConfig.TIER_MEMBER));

        // 2) 存量旧票据（无 tier 字段）：验签通过、等级=3（高级会员，不降权）
        when(sp.getString(eq(LicenseConfig.KEY_TICKET_JSON), nullable(String.class)))
                .thenReturn(OLD_JSON);
        when(sp.getString(eq(LicenseConfig.KEY_TICKET_SIG), nullable(String.class)))
                .thenReturn("0ecb97ff8b289ed54a52c068daf96e3d380c38ad9275d93cec8839958ee5bbab");
        lm.reload();
        assertNotEquals("旧票据验签不得失败（HMAC 兼容）", LicenseState.TAMPERED, lm.currentState());
        assertEquals("旧票据不降权 → 高级会员", LicenseConfig.TIER_PREMIUM, lm.tier());
        assertTrue(lm.tierAtLeast(LicenseConfig.TIER_PREMIUM));

        // 3) ticketKey 派生口径自检（与 Python 独立计算一致）
        assertEquals(TEST_TICKET_KEY, LicenseCrypto.ticketKey(ctx));
    }
}
