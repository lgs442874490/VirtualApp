package com.carlos.vault.wjdr;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * TC-X04：游戏登录签名（loginauth sign）与原版反编译 CryptoUtils 逐字节对齐。
 * 预期值由独立 Python 实现（SHA-1(前缀+";"+11 字段定序拼接) → Base64）预计算。
 */
public class WjdrCryptoTest {

    private static final String EXPECT_FULL = "t3km5/QX3XC3buFY0LOhNTfb098=";
    private static final String EXPECT_ALL_EMPTY = "8iriJsZEXDJspDWzNFzfiN2aEgE=";
    private static final String EXPECT_INT_NORM = "lssBES2wGYvyVQdxg8niqFj9tjI=";

    private static Map<String, Object> sampleFields() {
        // 11 字段按 loginauth 固定顺序
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", "81444913");
        m.put("sdkToken", "tok-abc-0123456789abcdef");
        m.put("uid", "500123456");
        m.put("device", "Xiaomi-2106118AG");
        m.put("device_os", "Android 13");
        m.put("country_code", "CN");
        m.put("distinct_id", "d-1234567890abcdef");
        m.put("lan", "zh-cn");
        m.put("version", "1.15.22");
        m.put("package_tag", "china");
        m.put("ts", "1757844000");
        return m;
    }

    @Test
    public void loginauthSign_fullFields() {
        assertEquals(EXPECT_FULL, WjdrCrypto.loginauthSign(sampleFields()));
    }

    @Test
    public void loginauthSign_allMissingBecomeEmpty() {
        // null 一律按空串参与拼接（与服务端口径一致）
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) m.put("k" + i, null);
        assertEquals(EXPECT_ALL_EMPTY, WjdrCrypto.loginauthSign(m));
    }

    @Test
    public void loginauthSign_numberNormalization() {
        // 数值字段整数化：1.0 → "1"；Long ts → 十进制原文
        Map<String, Object> m = sampleFields();
        m.put("uid", 1.0d);
        m.put("ts", 1757844000L);
        assertEquals(EXPECT_INT_NORM, WjdrCrypto.loginauthSign(m));
    }

    @Test
    public void withLoginauthSign_addsTsAndSign() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", "81444913");
        Map<String, Object> signed = WjdrCrypto.withLoginauthSign(m);
        assertTrue(signed.containsKey("ts"));
        assertTrue(signed.containsKey("sign"));
        long ts = ((Number) signed.get("ts")).longValue();
        long nowSec = System.currentTimeMillis() / 1000L;
        assertTrue("ts 应为当前秒级时间", Math.abs(nowSec - ts) <= 2);
        // sign 为合法 Base64（SHA-1 摘要 20 字节 → 28 字符）
        String sign = (String) signed.get("sign");
        assertEquals(28, sign.length());
        java.util.Base64.getDecoder().decode(sign);
    }
}
