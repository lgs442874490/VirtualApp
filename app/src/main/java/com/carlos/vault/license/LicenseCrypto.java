package com.carlos.vault.license;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 摘要 / HMAC / 规范化 JSON / 票据密钥派生。
 *
 * 全部使用 JDK 内置能力，零第三方依赖。
 *
 * 关键约束：{@link #writeCanonical} 与 {@link #signRequest} 必须与服务端
 * <code>license-server/sign.go</code> 的 <code>parseSignedBody</code> /
 * <code>computeSign</code> **逐字节一致**，否则签名校验必然失败。改动前请对照两侧实现。
 */
public final class LicenseCrypto {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** HMAC 算法名。 */
    private static final String HMAC_ALG = "HmacSHA256";

    /** 签名证书不可得时的占位串，保证 ticketKey 仍然稳定可复现。 */
    private static final String CERT_FALLBACK = "nocert";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** ticketKey 派生结果缓存（证书指纹读取较慢，且同进程内不变）。 */
    private static volatile String cachedTicketKey = null;

    private LicenseCrypto() {
    }

    // ---------- 摘要 ----------

    public static String sha256Hex(String s) {
        return sha256Hex(s == null ? new byte[0] : s.getBytes(UTF8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(data));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key.getBytes(UTF8), HMAC_ALG));
            return toHex(mac.doFinal(data.getBytes(UTF8)));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 共享密钥（与服务端 LICENSE_SECRET 同值）。 */
    public static String appSecret() {
        return LicenseConfig.appSecret();
    }

    // ---------- 规范化 JSON（必须与服务端一致） ----------

    /**
     * 把字段集写成「键按字典序 + 无空白 + 数字原样 + 不做 HTML 转义」的紧凑 JSON。
     *
     * 与服务端 Go 实现的对齐点：
     * <ul>
     *   <li>键按字典序升序 —— 两侧均对 ASCII 键序达成一致（协议键全部为 ASCII）</li>
     *   <li>无多余空白 —— Go 侧 json.Encoder 不插空格，本实现同样不插</li>
     *   <li>数字原样 —— Go 侧用 json.Number 保留原文；本实现整型直接十进制输出</li>
     *   <li>不做 HTML 转义 —— Go 侧 SetEscapeHTML(false)，本实现不转义 &lt; &gt; &amp;</li>
     * </ul>
     */
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

    /**
     * JSON 字符串转义，与服务端 <code>sign.go</code> 的
     * <code>writeCanonicalString</code> 逐字节对齐：
     * <ul>
     *   <li>双引号、反斜杠、换行、回车、制表、退格、换页 使用短转义（反斜杠 + 对应字母）</li>
     *   <li>其余 U+0000..U+001F 控制字符：转义为其小写四位十六进制形式</li>
     *   <li>U+2028 / U+2029：恒定转义</li>
     *   <li><code>&lt;</code> <code>&gt;</code> <code>&amp;</code> 与 <code>/</code> <b>不转义</b>；
     *       非 ASCII（含中文）原样输出 UTF-8</li>
     * </ul>
     * 注意：本注释刻意不写出字面的「反斜杠 + u」序列，因为 javac 在词法分析前会先处理
     * Unicode 转义，非法的转义序列会让整个文件编译失败。
     */
    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\u2028':
                    sb.append("\\u2028");
                    break;
                case '\u2029':
                    sb.append("\\u2029");
                    break;
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

    // ---------- 请求签名 ----------

    /**
     * 构造待签名串：
     * <pre>
     * v1 \n METHOD \n OP \n ts \n nonce \n SHA256_HEX(body_without_sign)
     * </pre>
     * OP 为逻辑操作名（activate | verify | rebind），**不含 /v1 前缀、不是 URL 路径**。
     */
    public static String signPayload(String method, String op, long ts, String nonce, String canonicalBody) {
        return LicenseConfig.SIGN_VERSION + "\n"
                + method + "\n"
                + op + "\n"
                + ts + "\n"
                + nonce + "\n"
                + sha256Hex(canonicalBody);
    }

    /** 计算请求签名（hex）。 */
    public static String signRequest(String method, String op, long ts, String nonce, String canonicalBody) {
        return hmacSha256Hex(appSecret(), signPayload(method, op, ts, nonce, canonicalBody));
    }

    /** 生成 32 位 hex 的随机 nonce。 */
    public static String newNonce() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        return toHex(buf);
    }

    // ---------- 票据密钥派生的抗重打包锚点 ----------

    /** 取本应用签名证书的 SHA-256（hex）。取不到时返回空串。 */
    public static String signingCertHash(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return "";
                Signature[] certs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
                if (certs == null || certs.length == 0) return "";
                return sha256Hex(certs[0].toByteArray());
            }
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) return "";
            return sha256Hex(pi.signatures[0].toByteArray());
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 票据 HMAC 密钥 = SHA256(签名证书指纹 + "|" + 包名 + "|" + 常量后缀)。
     *
     * 效果：换签名重打包后本密钥变化，历史票据的 HMAC 必然校验失败，
     * 从而把「改 SharedPreferences 伪造票据」这条路堵死。
     */
    public static String ticketKey(Context ctx) {
        String cached = cachedTicketKey;
        if (cached != null) return cached;
        String cert = signingCertHash(ctx);
        if (cert.isEmpty()) cert = CERT_FALLBACK;
        String key = sha256Hex(cert + "|" + ctx.getPackageName() + "|" + LicenseConfig.TICKET_KEY_SUFFIX);
        cachedTicketKey = key;
        return key;
    }
}
