package com.carlos.vaultserver.registration;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link CredentialBox}：seal/open 互逆、clear 后一律失败、masked 不含明文（任务书 §2.3）。
 */
public class CredentialBoxTest {

    private static Map<String, String> creds() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fpid", "fp-123456789");
        m.put("sdkToken", "SECRET-TOKEN-ABCDEF");
        return m;
    }

    @Test
    public void sealOpenRoundTrip() {
        CredentialBox box = new CredentialBox();
        String sealed = box.seal(creds());
        assertTrue("密文串应为 box1 前缀 + base64", CredentialBox.looksSealed(sealed));
        assertTrue("表键 fpid 应存在", box.has("fp-123456789"));
        Map<String, String> opened = box.open(sealed);
        assertEquals("fp-123456789", opened.get("fpid"));
        assertEquals("SECRET-TOKEN-ABCDEF", opened.get("sdkToken"));
        // 按表键取等价
        Map<String, String> taken = box.take("fp-123456789");
        assertEquals("SECRET-TOKEN-ABCDEF", taken.get("sdkToken"));
    }

    @Test
    public void sealTwice_differentCiphertext() {
        CredentialBox box = new CredentialBox();
        String s1 = box.seal(creds());
        String s2 = box.seal(creds());
        assertNotEquals("随机 IV 下两次加密不应同串", s1, s2);
    }

    @Test
    public void clearThenOpenAndTakeFail() {
        CredentialBox box = new CredentialBox();
        String sealed = box.seal(creds());
        box.clear();
        assertEquals("clear 后表应为空", 0, box.size());
        try {
            box.open(sealed);
            throw new AssertionError("clear 后 open 应失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("已清除"));
        }
        assertNull("clear 后 take 应返回 null", box.take("fp-123456789"));
        assertFalse(box.has("fp-123456789"));
    }

    @Test
    public void maskedContainsNoPlaintext() {
        CredentialBox box = new CredentialBox();
        box.seal(creds());
        Map<String, Map<String, String>> masked = box.masked();
        assertEquals(1, masked.size());
        String dumped = String.valueOf(masked);
        assertFalse("masked 绝不能含明文 token", dumped.contains("SECRET-TOKEN-ABCDEF"));
        assertTrue("应含 fpid 掩码形态", masked.get("fp-123456789").containsValue("fp-***789"));
        assertEquals("fp-***789", masked.get("fp-123456789").get("fpid"));
        assertEquals("SEC***DEF", masked.get("fp-123456789").get("sdkToken"));
    }

    @Test
    public void sealEmptyThrows() {
        try {
            new CredentialBox().seal(new LinkedHashMap<>());
            throw new AssertionError("空凭据 seal 应抛参数异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不能为空"));
        }
    }

    @Test
    public void tamperedSealedFails() {
        CredentialBox box = new CredentialBox();
        box.seal(creds());
        // 篡改的串不在表内 → 直接拒（表校验先行，密文层兜底）
        try {
            box.open("box1:AAAA");
            throw new AssertionError("非本箱密文应拒绝");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("不存在"));
        }
    }
}
