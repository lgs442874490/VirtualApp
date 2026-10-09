package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** 签名与规范化 JSON：与 App 端 LicenseCrypto / license-server sign.go 同一方案。 */
public class ApiSignTest {

    private static final String SECRET = "test-secret";

    @Test
    public void canonical_flatScalars_sortedNoWhitespace() {
        Map<String, Object> f = new TreeMap<>();
        f.put("b", 1);
        f.put("a", "x");
        f.put("c", true);
        assertEquals("{\"a\":\"x\",\"b\":1,\"c\":true}", ApiSign.writeCanonical(f));
    }

    @Test
    public void canonical_stringEscaping_matchesFrozenRules() {
        Map<String, Object> f = new TreeMap<>();
        // 引号/反斜杠短转义，控制字符 \u001f，HTML 与斜杠与中文原样
        f.put("k", "a\"b\\c\nd\u001fe<f&g/h中文");
        assertEquals("{\"k\":\"a\\\"b\\\\c\\nd\\u001fe<f&g/h中文\"}",
                ApiSign.writeCanonical(f));
    }

    @Test
    public void canonical_longNumbers_stayIntegral() {
        Map<String, Object> f = new TreeMap<>();
        f.put("ts", 1787500000123L);
        assertEquals("{\"ts\":1787500000123}", ApiSign.writeCanonical(f));
    }

    @Test
    public void buildSignedBody_roundTripsVerify() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("fpid", "80971192");
        f.put("note", "备注<带符号>&/");
        String body = ApiSign.buildSignedBody(SECRET, "vaultToken", f);

        ApiSign.Verified v = ApiSign.verify(SECRET, "POST", "vaultToken", body,
                5 * 60_000L, new ApiSign.NonceCache());
        assertEquals(ApiSign.CODE_OK, v.code);
        assertNotNull(v.fields);
        assertEquals("80971192", String.valueOf(v.fields.get("fpid")));
    }

    @Test
    public void verify_wrongSecretRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("x", "y");
        String body = ApiSign.buildSignedBody(SECRET, "vaultStatus", f);
        ApiSign.Verified v = ApiSign.verify("other-secret", "POST", "vaultStatus", body,
                5 * 60_000L, new ApiSign.NonceCache());
        assertEquals(ApiSign.CODE_BAD_SIGNATURE, v.code);
    }

    @Test
    public void verify_wrongOpRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("x", "y");
        String body = ApiSign.buildSignedBody(SECRET, "vaultStatus", f);
        ApiSign.Verified v = ApiSign.verify(SECRET, "POST", "vaultSync", body,
                5 * 60_000L, new ApiSign.NonceCache());
        assertEquals(ApiSign.CODE_BAD_SIGNATURE, v.code);
    }

    @Test
    public void verify_nonceReplayRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("x", "y");
        String body = ApiSign.buildSignedBody(SECRET, "vaultStatus", f);
        ApiSign.NonceCache cache = new ApiSign.NonceCache();
        assertEquals(ApiSign.CODE_OK, ApiSign.verify(SECRET, "POST", "vaultStatus", body,
                5 * 60_000L, cache).code);
        assertEquals(ApiSign.CODE_NONCE_REPLAY, ApiSign.verify(SECRET, "POST", "vaultStatus", body,
                5 * 60_000L, cache).code);
    }

    @Test
    public void verify_staleTimestampRejected() {
        Map<String, Object> f = new TreeMap<>();
        f.put("x", "y");
        long ts = System.currentTimeMillis() - 10 * 60_000L; // 超 ±5min 窗口
        String nonce = "abc123";
        String canonical = ApiSign.writeCanonical(f);
        String sign = ApiSign.computeSign(SECRET, "POST", "vaultStatus", ts, nonce, canonical);
        Map<String, Object> withMeta = new TreeMap<>(f);
        withMeta.put("ts", ts);
        withMeta.put("nonce", nonce);
        withMeta.put("sign", sign);
        String body = ApiSign.writeCanonical(withMeta);
        ApiSign.Verified v = ApiSign.verify(SECRET, "POST", "vaultStatus", body,
                5 * 60_000L, new ApiSign.NonceCache());
        assertEquals(ApiSign.CODE_TIMESTAMP_SKEW, v.code);
        assertNotEquals(0, v.code);
    }
}
