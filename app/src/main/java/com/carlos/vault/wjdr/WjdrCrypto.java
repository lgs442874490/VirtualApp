package com.carlos.vault.wjdr;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 游戏登录签名（移植自反编译 CryptoUtils，算法不变） */
public final class WjdrCrypto {
    private static final String SIGN_PREFIX = "362d060fb708d4cca57ebb98f2c45c9c0d8f34ee0da2f6c1c388e6f6956edd5f";

    private WjdrCrypto() {
    }

    public static Map<String, Object> withLoginauthSign(Map<String, Object> content) {
        LinkedHashMap<String, Object> signed = new LinkedHashMap<>(content);
        signed.put("ts", System.currentTimeMillis() / 1000L);
        signed.put("sign", loginauthSign(signed));
        return signed;
    }

    static String loginauthSign(Map<String, Object> content) {
        String values = String.join(";",
                text(content.get("account")),
                text(content.get("sdkToken")),
                text(content.get("uid")),
                text(content.get("device")),
                text(content.get("device_os")),
                text(content.get("country_code")),
                text(content.get("distinct_id")),
                text(content.get("lan")),
                text(content.get("version")),
                text(content.get("package_tag")),
                timestampText(content.get("ts")));
        String source = SIGN_PREFIX + ";" + values;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d) && BigDecimal.valueOf(d).stripTrailingZeros().scale() <= 0) {
                return String.valueOf(((Number) value).longValue());
            }
        }
        return String.valueOf(value);
    }

    private static String timestampText(Object value) {
        if (value == null || String.valueOf(value).isEmpty()) return "";
        if (value instanceof Number) return String.valueOf(((Number) value).longValue());
        return String.valueOf((long) Double.parseDouble(String.valueOf(value)));
    }
}
