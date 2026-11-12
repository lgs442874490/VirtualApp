package com.carlos.vaultserver.passport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * passport 包静态工具：摘要/编码/掩码/手机号与身份证校验。
 *
 * <p>算法实现与 App 侧 {@code com.carlos.vault.wechat.WechatQrLogin} 逐字同构
 * （HMAC-SHA256 → hex → base64(hex字符串的 UTF-8 bytes)），签名黄金值由单测锚定互证。
 */
public final class PassportIds {

    /** cg-seid 推导盐（与原版 OfficialLoginService / WechatQrLogin 一致）。 */
    public static final String CG_SEID_SALT = "7CPsU5puEk";

    private PassportIds() {
    }

    /** HmacSHA256(key, data) → 小写 hex；异常返回空串（与 App 侧容错一致）。 */
    public static String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    /** SHA-256 → 小写 hex（设备指纹派生用）。 */
    public static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** 标准 base64（无换行）。签名里 base64 的是 hex 字符串的 UTF-8 bytes。 */
    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 敏感值掩码：≤6 位全掩，否则 前3***后3。 */
    public static String mask(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.length() <= 6) {
            return "***";
        }
        return v.substring(0, 3) + "***" + v.substring(v.length() - 3);
    }

    /** 去除非数字字符。 */
    public static String normalizeMobile(String mobile) {
        if (mobile == null) {
            return "";
        }
        return mobile.replaceAll("\\D", "");
    }

    /**
     * 18 位身份证号格式校验：前 17 位数字 + 末位数字或 X/x。
     * 本期不做校验码加权验算（校验位算法与风控无关，后续需要时再补）。
     */
    public static boolean isValidIdCard(String idCard) {
        if (idCard == null) {
            return false;
        }
        return idCard.matches("\\d{17}[0-9Xx]");
    }
}
