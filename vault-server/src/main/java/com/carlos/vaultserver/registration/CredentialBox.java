package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.passport.PassportIds;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 凭据批次期加密箱（任务书 M3 §2.1）：进程级随机 AES-256-GCM 密钥 + 实例内密文表。
 *
 * <p>批次全程凭据（sdkToken）/实名信息只存在本箱内：{@link #seal(Map)} 加密并以
 * {@code fpid} 为表键留存（返回 {@code base64(iv+ct)} 密文串），{@link #open(String)}
 * 只解开<b>表内留存</b>的密文（批次 {@link #clear()} 后一律失败，兑现「终态即不可解」），
 * {@link #masked()} 输出各值掩码摘要。
 *
 * <p>约束（任务书工程约束）：任何持久化 / 日志只允许出现 masked 形式；
 * 密钥进程启动时由 {@link SecureRandom} 生成一份（静态），实例间共享密钥但表各自独立。
 * GCM 每次加密使用随机 12 字节 IV，同一密钥下 IV 不重用。
 */
public final class CredentialBox {

    /** GCM IV 字节数（NIST 推荐 96 bit）。 */
    private static final int IV_BYTES = 12;
    /** GCM 认证标签长度（bit）。 */
    private static final int TAG_BITS = 128;
    /** 密文串前缀（base64(iv+ct) 之外加语义前缀，防误把其它 base64 当凭据密文解开）。 */
    private static final String PREFIX = "box1:";

    /** 进程级密钥：SecureRandom 生成一份，所有实例共享（任务书「静态一份」）。 */
    private static final SecretKey KEY = generateKey();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 实例内密文表：表键（通常 fpid）→ 密文串。批次终态 {@link #clear()} 清空。 */
    private final ConcurrentMap<String, String> vault = new ConcurrentHashMap<>();

    /**
     * 加密并存表：AES-256-GCM 加密整个 map（JSON 序列化），返回 {@code box1:base64(iv+ct)}；
     * 表键取 {@code values.get("fpid")}，缺失时用随机 UUID（仍可按返回串 {@link #open}）。
     *
     * @param values 凭据键值（如 fpid / sdkToken / phone）；空 map 抛 IllegalArgumentException
     */
    public String seal(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("凭据内容不能为空");
        }
        String json = mapToJson(values);
        String sealed = PREFIX + encrypt(json.getBytes(StandardCharsets.UTF_8));
        String key = values.get("fpid");
        vault.put(key == null || key.trim().isEmpty() ? UUID.randomUUID().toString() : key, sealed);
        return sealed;
    }

    /**
     * 解开表内密文：串必须是本实例 {@link #seal} 产出且仍在表内（批次 clear 后不可解）。
     *
     * @throws IllegalStateException 凭据已清除 / 串不在表内 / 密文非法
     */
    public Map<String, String> open(String sealed) {
        if (sealed == null || !vault.containsValue(sealed)) {
            throw new IllegalStateException("凭据已清除或不存在");
        }
        return jsonToMap(decrypt(sealed));
    }

    /** 按表键（fpid）取出并解密；键不存在返回 null。 */
    public Map<String, String> take(String key) {
        if (key == null) return null;
        String sealed = vault.get(key);
        return sealed == null ? null : open(sealed);
    }

    /** 表键是否在箱内（批次运行期判断凭据是否已产出）。 */
    public boolean has(String key) {
        return key != null && vault.containsKey(key);
    }

    /** 置空内部表（批次终态调用）：此后 open/take 一律失败，明文随 GC 回收。 */
    public void clear() {
        vault.clear();
    }

    /** 表内条目数（诊断用）。 */
    public int size() {
        return vault.size();
    }

    /**
     * 掩码摘要（持久化 / 日志唯一允许形态）：表键 → {字段 → PassportIds.mask(值)}。
     * 输出绝不包含任何明文凭据。
     */
    public Map<String, Map<String, String>> masked() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(vault).entrySet()) {
            Map<String, String> fields = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, String> f : jsonToMap(decrypt(e.getValue())).entrySet()) {
                    fields.put(f.getKey(), PassportIds.mask(f.getValue()));
                }
            } catch (Throwable t) {
                fields.put("error", "解密失败");
            }
            out.put(e.getKey(), fields);
        }
        return out;
    }

    // ---------------- AES-256-GCM ----------------

    private static SecretKey generateKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            return kg.generateKey();
        } catch (Throwable t) {
            throw new IllegalStateException("AES-256 密钥生成失败", t);
        }
    }

    /** 加密：随机 IV + GCM，返回 base64(iv + ciphertext)。 */
    private static String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Throwable t) {
            throw new IllegalStateException("凭据加密失败", t);
        }
    }

    /** 解密：base64(iv + ciphertext) → 明文字节。非法/篡改抛 IllegalStateException。 */
    private static String decrypt(String sealed) {
        String body = sealed.startsWith(PREFIX) ? sealed.substring(PREFIX.length()) : sealed;
        try {
            byte[] all = Base64.getDecoder().decode(body);
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("凭据密文不完整");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEY,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException t) {
            throw t;
        } catch (Throwable t) {
            throw new IllegalStateException("凭据密文非法或已被篡改", t);
        }
    }

    // ---------------- map ↔ json（org.json，键序不敏感） ----------------

    private static String mapToJson(Map<String, String> values) {
        org.json.JSONObject o = new org.json.JSONObject();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null) continue;
            o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return o.toString();
    }

    private static Map<String, String> jsonToMap(String json) {
        org.json.JSONObject o = new org.json.JSONObject(json);
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            out.put(k, o.optString(k, ""));
        }
        return out;
    }

    /** 供单测断言密文串形态（box1 前缀 + 标准 base64）。 */
    static boolean looksSealed(String s) {
        if (s == null || !s.startsWith(PREFIX)) return false;
        try {
            Base64.getDecoder().decode(s.substring(PREFIX.length()));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
