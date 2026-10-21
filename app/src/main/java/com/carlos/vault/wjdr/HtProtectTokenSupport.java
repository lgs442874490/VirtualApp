package com.carlos.vault.wjdr;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 易盾风控检测令牌（req_dun163_risk_check token）重封装算法。
 *
 * <p>移植自线上最新版（release 400）反编译实现
 * {@code com.wjdr.tool.core.common.util.HtProtectTokenSupport}：24 字节受信任特征
 * payload 与 4 字节随机 mask 逐字节异或得 raw28 前 24 字节，mask 附于尾部，
 * Base64URL（URL-safe 字母表 + '=' 填充，与线上捕获样本同构）编码。
 *
 * <p>本文件被 App（Java 8 源级 / minSdk 21）与 server（Java 17）共用：
 * 不可用 {@code HexFormat}（Java 17）、{@code java.util.Base64}（Android API 26+）、
 * {@code String.isBlank}（Java 11），故 hex / Base64URL 编解码为内置 Java 8 实现，
 * 输出与对应 JDK API 逐字节一致。算法/常量与线上一致，勿改动。
 */
public final class HtProtectTokenSupport {

    public static final String ORACLE_RAW_PAYLOAD_HEX =
            "3dbc96467b10571f4446101001438853222a02f05a572ff8";
    public static final String ORACLE_MASK_HEX = "7c754fe7";
    public static final String ORACLE_CAPTURE_TOKEN = "QcnZoQdlGPg4M1_3fTbHtF5fTRcmImAffHVP5w==";
    private static final byte[] TRUSTED_PAYLOAD_BYTES = parseHex(ORACLE_RAW_PAYLOAD_HEX);
    /** RFC 4648 URL-safe 字母表（与 java.util.Base64#getUrlEncoder 一致）。 */
    private static final char[] BASE64URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private HtProtectTokenSupport() {
    }

    /** 随机 mask 重封装一次（线上同款入口）。 */
    public static String rewrapToken() {
        return rewrapToken(TRUSTED_PAYLOAD_BYTES, null);
    }

    public static String rewrapToken(byte[] payload24, byte[] mask4) {
        if (payload24 == null || payload24.length != 24) {
            throw new IllegalArgumentException("Payload 长度必须恰好为 24 字节");
        }
        byte[] mask = mask4 != null ? mask4 : randomMask();
        if (mask.length != 4) {
            throw new IllegalArgumentException("Mask 长度必须恰好为 4 字节");
        }
        byte[] raw28 = new byte[28];
        for (int i = 0; i < 24; i++) {
            raw28[i] = (byte) (payload24[i] ^ mask[i % 4]);
        }
        System.arraycopy(mask, 0, raw28, 24, 4);
        return base64UrlEncode(raw28);
    }

    public static String generateToken() {
        return rewrapToken();
    }

    public static String generateToken(byte[] payload24, byte[] mask4) {
        return rewrapToken(payload24, mask4);
    }

    /** 解出令牌内的 24 字节 payload（raw28 前 24 字节 ^ mask 循环异或）。 */
    public static byte[] decodeTokenPayload(String token) {
        byte[] raw28 = decodeRaw28(token);
        byte[] mask = Arrays.copyOfRange(raw28, 24, 28);
        byte[] payload24 = new byte[24];
        for (int i = 0; i < 24; i++) {
            payload24[i] = (byte) (raw28[i] ^ mask[i % 4]);
        }
        return payload24;
    }

    public static byte[] extractTokenMask(String token) {
        byte[] raw28 = decodeRaw28(token);
        return Arrays.copyOfRange(raw28, 24, 28);
    }

    public static byte[] trustedPayload() {
        return TRUSTED_PAYLOAD_BYTES.clone();
    }

    private static byte[] randomMask() {
        byte[] mask = new byte[4];
        SECURE_RANDOM.nextBytes(mask);
        return mask;
    }

    private static byte[] decodeRaw28(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("令牌不能为空");
        }
        byte[] raw28 = base64UrlDecode(token.trim());
        if (raw28.length != 28) {
            throw new IllegalArgumentException(
                    "令牌解码长度必须为 28 字节，实际: " + raw28.length);
        }
        return raw28;
    }

    // ================= Java 8 兼容编解码（输出与对应 JDK API 逐字节一致） =================

    /** hex 解码（大小写均可，替代 java.util.HexFormat#parseHex）。 */
    private static byte[] parseHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("HEX 长度必须为偶数");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((hexCharValue(hex.charAt(i * 2)) << 4)
                    | hexCharValue(hex.charAt(i * 2 + 1)));
        }
        return out;
    }

    private static int hexCharValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("非法 HEX 字符: " + c);
    }

    /** Base64URL 编码，含 '=' 填充（28 字节 → 40 字符，替代 java.util.Base64#getUrlEncoder）。 */
    private static String base64UrlEncode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b1 = data[i] & 0xFF;
            int b2 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b3 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            int triple = (b1 << 16) | (b2 << 8) | b3;
            out.append(BASE64URL_ALPHABET[(triple >>> 18) & 0x3F]);
            out.append(BASE64URL_ALPHABET[(triple >>> 12) & 0x3F]);
            out.append(i + 1 < data.length ? BASE64URL_ALPHABET[(triple >>> 6) & 0x3F] : '=');
            out.append(i + 2 < data.length ? BASE64URL_ALPHABET[triple & 0x3F] : '=');
        }
        return out.toString();
    }

    /** Base64URL 解码，容忍 '=' 填充或无填充（替代 java.util.Base64#getUrlDecoder）。 */
    private static byte[] base64UrlDecode(String text) {
        int len = text.length();
        while (len > 0 && text.charAt(len - 1) == '=') {
            len--;
        }
        int rem = len % 4;
        if (rem == 1) {
            throw new IllegalArgumentException("Base64URL 长度非法: " + len);
        }
        byte[] out = new byte[len / 4 * 3 + (rem == 0 ? 0 : rem - 1)];
        int outPos = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            acc = ((acc << 6) | base64UrlCharValue(text.charAt(i))) & 0xFFFF;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outPos++] = (byte) ((acc >>> bits) & 0xFF);
            }
        }
        return out;
    }

    private static int base64UrlCharValue(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-') return 62;
        if (c == '_') return 63;
        throw new IllegalArgumentException("非法 Base64URL 字符: " + c);
    }
}
