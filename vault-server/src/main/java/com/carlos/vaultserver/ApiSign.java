package com.carlos.vaultserver;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 请求签名（与 App 端 LicenseCrypto / license-server sign.go 同一方案，独立 VAULT_SECRET）：
 *
 * <pre>
 * sign = HMAC_SHA256(SECRET, "v1\n" + METHOD + "\n" + OP + "\n" + ts + "\n" + nonce + "\n" + SHA256_HEX(body_without_sign))
 * </pre>
 *
 * 规范化 JSON：键字典序、无空白、数字原样、字符串短转义（&lt;&gt;&amp;/ 与非 ASCII 不转义）。
 * 本服务只收扁平标量字段（复杂数据以 JSON 字符串字段携带，如 accountsJson），与既有客户端实现保持字节级一致。
 */
public final class ApiSign {

    public static final int CODE_OK = 0;
    public static final int CODE_BAD_SIGNATURE = 2001;
    public static final int CODE_TIMESTAMP_SKEW = 2002;
    public static final int CODE_NONCE_REPLAY = 2003;
    public static final int CODE_BAD_REQUEST = 2004;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiSign() {
    }

    // ---------- 摘要原语 ----------

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    // ---------- 规范化 JSON（与服务端/客户端既有实现逐字节对齐） ----------

    public static String writeCanonical(Map<String, Object> fields) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (fields != null) {
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (e.getKey() != null) sorted.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Long) {
            sb.append(((Long) v).longValue());
        } else if (v instanceof Integer) {
            sb.append(((Integer) v).intValue());
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------- 签名计算 ----------

    public static String computeSign(String secret, String method, String op, long ts,
                                     String nonce, String canonicalBody) {
        String payload = "v1\n" + method + "\n" + op + "\n" + ts + "\n" + nonce + "\n"
                + sha256Hex(canonicalBody);
        return hmacSha256Hex(secret, payload);
    }

    /** 客户端侧构造已签名请求体（单测与联调脚本也用它造请求）。 */
    public static String buildSignedBody(String secret, String op, Map<String, Object> fields) {
        Map<String, Object> sorted = new TreeMap<>(fields);
        long ts = System.currentTimeMillis();
        String nonce = newNonce();
        sorted.put("ts", ts);
        sorted.put("nonce", nonce);
        String canonical = writeCanonical(sorted);
        String sign = computeSign(secret, "POST", op, ts, nonce, canonical);
        sorted.put("sign", sign);
        return writeCanonical(sorted);
    }

    static String newNonce() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        return toHex(buf);
    }

    // ---------- 服务端验签 ----------

    /** nonce 防重放缓存（进程内；窗口 10 分钟，容量上限防御性清理）。 */
    public static final class NonceCache {
        private final ConcurrentHashMap<String, Long> used = new ConcurrentHashMap<>();

        boolean consume(String nonce, long now) {
            Long prev = used.putIfAbsent(nonce, now);
            if (used.size() > 50_000) {
                used.entrySet().removeIf(e -> now - e.getValue() > 600_000L);
            }
            return prev == null;
        }
    }

    /** 验签结果：成功时 fields 为去掉 sign 的请求字段。 */
    public static final class Verified {
        public final int code;
        public final Map<String, Object> fields;

        Verified(int code, Map<String, Object> fields) {
            this.code = code;
            this.fields = fields;
        }
    }

    /** 校验顺序：参数完整(2004) → 时间窗(2002) → 签名(2001) → nonce(2003)。 */
    public static Verified verify(String secret, String method, String op, String rawBody,
                                  long signWindowMillis, NonceCache cache) {
        if (rawBody == null || rawBody.isEmpty()) return new Verified(CODE_BAD_REQUEST, null);
        Map<String, Object> m;
        try {
            JSONObject o = new JSONObject(rawBody);
            m = new TreeMap<>();
            for (String k : o.keySet()) m.put(k, o.get(k));
        } catch (Throwable t) {
            return new Verified(CODE_BAD_REQUEST, null);
        }
        Object tsV = m.get("ts");
        long ts;
        if (tsV instanceof Number) {
            ts = ((Number) tsV).longValue();
        } else {
            try {
                ts = Long.parseLong(String.valueOf(tsV));
            } catch (NumberFormatException e) {
                return new Verified(CODE_BAD_REQUEST, null);
            }
        }
        String nonce = String.valueOf(m.get("nonce"));
        String sign = String.valueOf(m.get("sign"));
        if (nonce.isEmpty() || "null".equals(nonce) || sign.isEmpty() || "null".equals(sign)) {
            return new Verified(CODE_BAD_REQUEST, null);
        }
        m.remove("sign");
        String canonical = writeCanonical(m);
        long now = System.currentTimeMillis();
        long diff = Math.abs(now - ts);
        if (diff > signWindowMillis) return new Verified(CODE_TIMESTAMP_SKEW, null);
        String expected = computeSign(secret, method, op, ts, nonce, canonical);
        if (!constantTimeEquals(expected, sign)) return new Verified(CODE_BAD_SIGNATURE, null);
        if (!cache.consume(nonce, now)) return new Verified(CODE_NONCE_REPLAY, null);
        return new Verified(CODE_OK, m);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
