package com.carlos.vault.wjdr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Base64;
import java.util.HexFormat;

/**
 * 易盾风控检测令牌（P1 防封保护，对齐线上 HtProtectTokenSupport）：
 * 已知 payload+mask 的线上捕获样本复现、随机 mask 往返、长度校验、Base64URL 无填充。
 */
public class HtProtectTokenSupportTest {

    @Test
    public void rewrap_knownPayloadAndMask_reproducesOracleCapture() {
        byte[] payload = HexFormat.of().parseHex(HtProtectTokenSupport.ORACLE_RAW_PAYLOAD_HEX);
        byte[] mask = HexFormat.of().parseHex(HtProtectTokenSupport.ORACLE_MASK_HEX);
        assertEquals("线上捕获样本逐字节复现",
                HtProtectTokenSupport.ORACLE_CAPTURE_TOKEN,
                HtProtectTokenSupport.rewrapToken(payload, mask));
    }

    @Test
    public void rewrap_randomMask_roundTripsTrustedPayload() {
        String token = HtProtectTokenSupport.rewrapToken();
        assertArrayEquals("解码还原受信任特征 payload",
                HtProtectTokenSupport.trustedPayload(),
                HtProtectTokenSupport.decodeTokenPayload(token));
        byte[] mask = HtProtectTokenSupport.extractTokenMask(token);
        assertEquals(4, mask.length);
        // 两次随机 mask 产生不同令牌（Nonce 掩码机制）
        assertNotEquals(token, HtProtectTokenSupport.rewrapToken());
    }

    @Test
    public void generateToken_equivalentToRewrap() {
        byte[] payload = HtProtectTokenSupport.trustedPayload();
        byte[] mask = new byte[]{1, 2, 3, 4};
        assertEquals(HtProtectTokenSupport.rewrapToken(payload, mask),
                HtProtectTokenSupport.generateToken(payload, mask));
    }

    @Test
    public void lengthValidation_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> HtProtectTokenSupport.rewrapToken(new byte[23], new byte[4]));
        assertThrows(IllegalArgumentException.class,
                () -> HtProtectTokenSupport.rewrapToken(new byte[24], new byte[3]));
        assertThrows(IllegalArgumentException.class,
                () -> HtProtectTokenSupport.rewrapToken(null, null));
    }

    @Test
    public void token_isUrlSafeBase64_paddedLikeOracle() {
        // 对齐线上：URL-safe 字母表 + '=' 填充（28 字节 → 40 字符，样本以 "==" 结尾）
        String token = HtProtectTokenSupport.rewrapToken();
        assertFalse_contains(token, '+');
        assertFalse_contains(token, '/');
        assertTrue("URL-safe 字母表 + 填充，与线上捕获样本同构",
                token.equals(HtProtectTokenSupport.ORACLE_CAPTURE_TOKEN)
                        || token.endsWith("="));
        assertEquals(28, Base64.getUrlDecoder().decode(token).length);
    }

    private static void assertFalse_contains(String s, char c) {
        assertTrue("不含 " + c, s.indexOf(c) < 0);
    }
}
