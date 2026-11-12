package com.carlos.vaultserver.registration;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 自包含凭据包（{@code buildEncrypted}/{@code parseEncrypted}）：互逆 + 非法输入（任务书 §2.3）。
 */
public class EncryptedTest {

    private static final String FPID = "fp-1001";
    private static final String TOKEN = "sk-secret-abcdef";
    private static final String DEVICE_JSON = "{\"account\":\"fp-1001\",\"device\":\"meizu\"}";

    @Test
    public void buildParseRoundTrip() {
        String encrypted = RegistrationService.buildEncrypted(FPID, TOKEN, DEVICE_JSON);
        assertTrue("应为 base64url（无 + / 与 padding）",
                encrypted.matches("[A-Za-z0-9_-]+"));
        String[] parts = RegistrationService.parseEncrypted(encrypted);
        assertArrayEquals(new String[]{FPID, TOKEN, DEVICE_JSON}, parts);
    }

    @Test
    public void roundTripWithUnicodeDeviceJson() {
        String json = "{\"device\":\"魅族 18 Pro\",\"lan\":\"zh_cn\"}";
        String encrypted = RegistrationService.buildEncrypted(FPID, TOKEN, json);
        String[] parts = RegistrationService.parseEncrypted(encrypted);
        assertEquals(json, parts[2]);
    }

    @Test
    public void encryptedDoesNotContainPlaintextToken() {
        String encrypted = RegistrationService.buildEncrypted(FPID, TOKEN, DEVICE_JSON);
        assertTrue("加密串绝不直接包含明文 token（它是凭据包形态）",
                !encrypted.contains(TOKEN));
    }

    @Test
    public void invalidInputs_throwUniformMessage() {
        java.util.Base64.Encoder url = java.util.Base64.getUrlEncoder().withoutPadding();
        String[] bad = {
                null,
                "",
                "   ",
                "not_base64!!!",                                   // 非 base64url
                url.encodeToString("no-newline-here".getBytes()),  // 缺换行分段
                url.encodeToString("fpid\nonly-two".getBytes()),   // 缺第三段
                url.encodeToString("\nsdk\ndev".getBytes()),       // 首段为空
                RegistrationService.buildEncrypted(FPID, TOKEN, DEVICE_JSON)
                        .substring(0, 10) + "@@"                   // 破坏尾部
        };
        for (String input : bad) {
            try {
                RegistrationService.parseEncrypted(input);
                fail("应拒绝非法输入: " + input);
            } catch (IllegalArgumentException e) {
                assertEquals("统一错误文案", "加密串格式不正确", e.getMessage());
            }
        }
    }

    @Test
    public void buildWithBlankField_throws() {
        for (String[] args : new String[][]{{"", TOKEN, DEVICE_JSON},
                {FPID, "", DEVICE_JSON}, {FPID, TOKEN, ""}}) {
            try {
                RegistrationService.buildEncrypted(args[0], args[1], args[2]);
                fail("空字段应拒绝: " + String.valueOf(args));
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("加密串格式不正确"));
            }
        }
    }
}
