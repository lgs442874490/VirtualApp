package com.carlos.vault.license;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * TC-P01 / TC-P02：授权签名黄金向量（license-server README §6.3 架构师冻结版）。
 * 向量由独立 Python 与真实 Java 实现双算冻结；客户端必须逐字节复现。
 */
public class LicenseGoldenVectorTest {

    private static final String APP_SECRET = "vc2026-hj-license-9f3a7c1e5b8d2460";
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final long TS = 1757844000000L;

    private static Map<String, Object> base() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appVersion", "2.3.2");
        m.put("appVersionCode", 23202);
        m.put("pkg", "com.carlos.multiapp");
        m.put("ts", TS);
        m.put("nonce", NONCE);
        return m;
    }

    @Test
    public void vector1_activateHello() {
        Map<String, Object> body = base();
        body.put("phase", "hello");
        String canonical = LicenseCrypto.writeCanonical(body);
        assertEquals("{\"appVersion\":\"2.3.2\",\"appVersionCode\":23202,\"nonce\":\""
                + NONCE + "\",\"phase\":\"hello\",\"pkg\":\"com.carlos.multiapp\",\"ts\":1757844000000}",
                canonical);
        String sha = LicenseCrypto.sha256Hex(canonical);
        assertEquals("0271bbc23d680d85fca6fffc7422125904fe39a7374cde737e5c076e10d60d11", sha);
        // sign = HMAC(APP_SECRET, "v1\nPOST\nOP\nts\nnonce\nSHA256_HEX(canonical)")
        String sign = LicenseCrypto.hmacSha256Hex(APP_SECRET,
                LicenseCrypto.signPayload("POST", "activate", TS, NONCE, canonical));
        assertEquals("c245e9280564bfe4e67079ae2d9eec1f5eba147257cab691c363c6ae561ae319", sign);
    }

    @Test
    public void vector2_activateBind() {
        Map<String, Object> body = base();
        body.put("phase", "bind");
        body.put("code", "K7M9-PQRS-TUVW-XYZL");
        body.put("deviceHash", new String(new char[64]).replace('\0', 'a'));
        body.put("deviceLabel", "vivo V2141A");
        body.put("salt", new String(new char[32]).replace('\0', 'b'));
        body.put("challengeId", "9f3a7c1e5b8d2460");
        String canonical = LicenseCrypto.writeCanonical(body);
        String sha = LicenseCrypto.sha256Hex(canonical);
        assertEquals("6109e54289a78209e5aab99a0ff1a1228ae460d9acbd1a57d0b62bb760da0c7e", sha);
        String sign = LicenseCrypto.hmacSha256Hex(APP_SECRET,
                LicenseCrypto.signPayload("POST", "activate", TS, NONCE, canonical));
        assertEquals("9debd9c6c260baf16f63674f7b6880691994a0e62055b12cc6ff6c625c166d85", sign);
    }

    @Test
    public void vector3_verify() {
        // 注意：verify 向量不含 appVersionCode（README §6.3 曾误印，以 sign_test.go 为准——已勘误）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appVersion", "2.3.2");
        body.put("code", "K7M9-PQRS-TUVW-XYZL");
        body.put("deviceHash", new String(new char[64]).replace('\0', 'a'));
        body.put("nonce", NONCE);
        body.put("pkg", "com.carlos.multiapp");
        body.put("ticketId", "t_1_deadbeef");
        body.put("ts", TS);
        String canonical = LicenseCrypto.writeCanonical(body);
        assertEquals("696469710df51e763b9e968d5502ecd285cc0e89e1cde6346ef7f53b8c1b3f57",
                LicenseCrypto.sha256Hex(canonical));
        assertEquals("9665e4144a3ad9b3baf6305d3d8d4cab2af324f2f55bbe1466854838f333fd67",
                LicenseCrypto.hmacSha256Hex(APP_SECRET,
                        LicenseCrypto.signPayload("POST", "verify", TS, NONCE, canonical)));
    }

    @Test
    public void vector4_escapeBoundary() {
        // 转义边界：` " \ 换行 原样 <>& 中文 U+0001 U+2028
        // 期望（冻结值）：`\u0001`/`\u2028` 小写 hex；<>& 与中文不转义
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceLabel", "a\"b\\c\nd<e&f>中\u0001\u2028");
        body.put("nonce", NONCE);
        body.put("pkg", "com.carlos.multiapp");
        body.put("ts", TS);
        String canonical = LicenseCrypto.writeCanonical(body);
        String expected = "{\"deviceLabel\":\"a\\\"b\\\\c\\nd<e&f>中\\u0001\\u2028\","
                + "\"nonce\":\"" + NONCE + "\",\"pkg\":\"com.carlos.multiapp\",\"ts\":1757844000000}";
        assertEquals(expected, canonical);
        assertEquals("3ab81a5f66a9c8a00f69638b165af1d078e761342fdfe87055493bdce145ac2b",
                LicenseCrypto.sha256Hex(canonical));
        assertEquals(138, canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    public void escapeShortForms_bf() {
        // \b \f 用短转义（追加边界，README §6.3 末尾）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("d", "\b\f\u0001");
        assertEquals("{\"d\":\"\\b\\f\\u0001\"}", LicenseCrypto.writeCanonical(body));
    }

    @Test
    public void hmacHelper_plainUse() {
        // 向量 3 闭环：手工拼接待签名串直接 HMAC == signPayload 拼接后再 HMAC（== 冻结 sign）
        String canonical3 = "{\"appVersion\":\"2.3.2\",\"code\":\"K7M9-PQRS-TUVW-XYZL\","
                + "\"deviceHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"nonce\":\"" + NONCE + "\",\"pkg\":\"com.carlos.multiapp\","
                + "\"ticketId\":\"t_1_deadbeef\",\"ts\":1757844000000}";
        String manualPayload = "v1\nPOST\nverify\n1757844000000\n" + NONCE
                + "\n696469710df51e763b9e968d5502ecd285cc0e89e1cde6346ef7f53b8c1b3f57";
        // signPayload 只负责拼接，SHA 槽 = sha256Hex(canonicalBody)
        assertEquals(manualPayload, LicenseCrypto.signPayload("POST", "verify", TS, NONCE, canonical3));
        assertEquals("9665e4144a3ad9b3baf6305d3d8d4cab2af324f2f55bbe1466854838f333fd67",
                LicenseCrypto.hmacSha256Hex(APP_SECRET, manualPayload));
    }

    /**
     * TC-P02：客户端内置密钥（base64+循环 XOR 混淆）必须还原为与服务端部署约定一致的
     * 64 位 hex 串。期望值由宿主机 Python 独立解码得出。
     * android.util.Base64 在 JVM 用 java.util.Base64 静态替身。
     */
    @Test
    public void embeddedSecret_recoversToDeploymentKey() {
        try (MockedStatic<android.util.Base64> base64 =
                     mockStatic(android.util.Base64.class)) {
            base64.when(() -> android.util.Base64.decode(anyString(), anyInt()))
                    .thenAnswer(inv -> java.util.Base64.getDecoder()
                            .decode((String) inv.getArgument(0)));
            String secret = LicenseConfig.appSecret();
            assertEquals("afbaa5eea289951de631b1a0dabd618e6d46e4b1ad10d5a293049c147f50c586",
                    secret);
            assertEquals(LicenseCrypto.appSecret(), secret);
        }
    }

    @Test
    public void errorMessages_userFacing() {
        assertEquals("成功", LicenseConfig.messageFor(0));
        assertEquals("兑换码不存在或格式错误", LicenseConfig.messageFor(1001));
        assertEquals("该码已被使用（已绑定其他设备）", LicenseConfig.messageFor(1003));
        assertEquals("授权已到期", LicenseConfig.messageFor(1005));
        assertEquals("本机时间异常，请校准后重试", LicenseConfig.messageFor(2002));
        // 安全类错误统一收敛为「网络异常」，不暴露协议细节
        assertEquals("网络异常，请重试", LicenseConfig.messageFor(2001));
        assertEquals("网络异常，请重试", LicenseConfig.messageFor(2003));
        assertEquals("网络异常，请重试", LicenseConfig.messageFor(3001));
    }

    @Test
    public void protocolConstants_frozen() {
        // 签名槽位 3 用逻辑操作名（架构师冻结修订 2026-09-14）
        assertEquals("activate", LicenseConfig.OP_ACTIVATE);
        assertEquals("verify", LicenseConfig.OP_VERIFY);
        assertEquals("rebind", LicenseConfig.OP_REBIND);
        assertEquals("v1", LicenseConfig.SIGN_VERSION);
        assertEquals(3, LicenseConfig.GRACE_DAYS);
        assertEquals(10 * 60_000L, LicenseConfig.VERIFY_THROTTLE_MS);
        assertEquals(5 * 60_000L, LicenseConfig.SIGN_WINDOW_MS);
    }
}
