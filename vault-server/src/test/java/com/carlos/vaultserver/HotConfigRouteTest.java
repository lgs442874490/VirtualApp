package com.carlos.vaultserver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 热更新自托管路由（GET hotconfig / app-latest.apk）：{base} 替换、X-Forwarded-Proto
 * 优先级、404 信封、APK Content-Type/Content-Length、非 GET 405。纯函数单测覆盖
 * Host 缺失回落与 scheme 解析。
 */
public class HotConfigRouteTest {

    private static final String SECRET = "hotconfig-route-test-secret";
    private static final String HOT_JSON =
            "{\"version\":2026102501,\"apk\":{\"versionCode\":23519,"
                    + "\"versionName\":\"4.2.9-HTTPS加密\","
                    + "\"url\":\"{base}/v1/vault/app-latest.apk\",\"notes\":\"热更测试\"}}";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private Path dataDir;
    private String base;

    @Before
    public void setUp() throws Exception {
        dataDir = tmp.getRoot().toPath();
        VaultStore store = new VaultStore(dataDir);
        store.load();
        StateStore stateStore = new StateStore(dataDir);
        stateStore.load();
        VaultConfig config = new VaultConfig("127.0.0.1:0", dataDir, SECRET, null, "human_first");
        api = new VaultHttpApi(config, store, new VaultHttpApiTest.FakeKeeper(), stateStore);
        api.start();
        base = "http://127.0.0.1:" + api.server().getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (api != null) api.stop();
    }

    // ---------- 路由端到端 ----------

    @Test
    public void hotconfig_replacesBasePlaceholderWithRequestHost() throws Exception {
        writeHotConfig();
        Resp r = request("GET", "/v1/vault/hotconfig");
        assertEquals(200, r.status);
        assertTrue("Content-Type 应为 JSON: " + r.contentType,
                r.contentType != null && r.contentType.startsWith("application/json"));
        String body = r.text();
        assertFalse("响应里不应残留 {base} 占位符: " + body, body.contains("{base}"));
        JSONObject o = new JSONObject(body);
        assertEquals(2026102501L, o.getLong("version"));
        JSONObject apk = o.getJSONObject("apk");
        assertEquals(23519L, apk.getLong("versionCode"));
        // Host 头由客户端带上 127.0.0.1:port；无 X-Forwarded-Proto 时默认 http
        assertEquals(base + "/v1/vault/app-latest.apk", apk.getString("url"));
        assertEquals("4.2.9-HTTPS加密", apk.getString("versionName"));
    }

    @Test
    public void hotconfig_prefersForwardedProtoHttps() throws Exception {
        writeHotConfig();
        Resp r = request("GET", "/v1/vault/hotconfig", "X-Forwarded-Proto", "https");
        assertEquals(200, r.status);
        JSONObject apk = new JSONObject(r.text()).getJSONObject("apk");
        String url = apk.getString("url");
        assertTrue("X-Forwarded-Proto=https 应生成 https 基址: " + url, url.startsWith("https://"));
        assertTrue("Host 应透传为 127.0.0.1:port: " + url,
                url.contains("127.0.0.1:" + api.server().getAddress().getPort()));
        assertTrue(url.endsWith("/v1/vault/app-latest.apk"));
    }

    @Test
    public void hotconfig_prefersForwardedHostOverHost() throws Exception {
        writeHotConfig();
        // nginx 常见配置：显式透传 X-Forwarded-Host/Proto；应优先于反代后的 Host(127.0.0.1)
        Resp r = request("GET", "/v1/vault/hotconfig",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "103.236.96.82:64868");
        assertEquals(200, r.status);
        assertEquals("https://103.236.96.82:64868/v1/vault/app-latest.apk",
                new JSONObject(r.text()).getJSONObject("apk").getString("url"));
    }

    @Test
    public void hotconfig_missingFileReturns404Envelope() throws Exception {
        Resp r = request("GET", "/v1/vault/hotconfig");
        assertEquals(404, r.status);
        JSONObject o = new JSONObject(r.text());
        assertEquals(3002, o.getInt("code"));
    }

    @Test
    public void apk_streamsWithAndroidPackageHeaders() throws Exception {
        byte[] apk = new byte[128 * 1024 + 7];
        for (int i = 0; i < apk.length; i++) apk[i] = (byte) (i * 31);
        Path f = dataDir.resolve("download").resolve("app-latest.apk");
        Files.createDirectories(f.getParent());
        Files.write(f, apk);

        Resp r = request("GET", "/v1/vault/app-latest.apk");
        assertEquals(200, r.status);
        assertEquals(HotConfigRoutes.APK_CONTENT_TYPE, r.contentType);
        assertEquals(String.valueOf(apk.length), r.contentLength);
        assertArrayEquals(apk, r.body);
    }

    @Test
    public void apk_missingFileReturns404Envelope() throws Exception {
        Resp r = request("GET", "/v1/vault/app-latest.apk");
        assertEquals(404, r.status);
        assertEquals(3002, new JSONObject(r.text()).getInt("code"));
    }

    @Test
    public void nonGetReturns405Envelope() throws Exception {
        writeHotConfig();
        Resp hot = request("POST", "/v1/vault/hotconfig");
        assertEquals(405, hot.status);
        assertEquals(3002, new JSONObject(hot.text()).getInt("code"));

        Resp apk = request("POST", "/v1/vault/app-latest.apk");
        assertEquals(405, apk.status);
        assertEquals(3002, new JSONObject(apk.text()).getInt("code"));
    }

    // ---------- 纯函数 ----------

    @Test
    public void baseUrl_forwardedProtoAndHost() {
        assertEquals("https://example.com",
                HotConfigRoutes.baseUrl("https", "example.com", null));
        assertEquals("http://example.com",
                HotConfigRoutes.baseUrl(null, "example.com", null));
        // 逗号列表取第一段（多级反代），大小写/空白归一
        assertEquals("https://example.com",
                HotConfigRoutes.baseUrl(" HTTPS, http ", " example.com ", null));
        // 未知协议回落 http
        assertEquals("http://example.com",
                HotConfigRoutes.baseUrl("ftp", "example.com", null));
        // 多级反代 Host/X-Forwarded-Host 逗号列表取第一段
        assertEquals("https://a.example",
                HotConfigRoutes.baseUrl("https", "a.example, b.example", null));
    }

    @Test
    public void baseUrl_hostMissingFallsBackToLocalAddress() {
        assertEquals("http://127.0.0.1:9999",
                HotConfigRoutes.baseUrl(null, "", new InetSocketAddress("127.0.0.1", 9999)));
        assertEquals("https://127.0.0.1:9999",
                HotConfigRoutes.baseUrl("https", null, new InetSocketAddress("127.0.0.1", 9999)));
    }

    @Test
    public void renderHotConfig_replacesEveryPlaceholder() {
        String raw = "{\"a\":\"{base}/x\",\"b\":\"{base}\",\"c\":\"plain\"}";
        String out = HotConfigRoutes.renderHotConfig(raw, "https", "h:8443", null);
        assertEquals("{\"a\":\"https://h:8443/x\",\"b\":\"https://h:8443\",\"c\":\"plain\"}", out);
        assertEquals("", HotConfigRoutes.renderHotConfig(null, "https", "h", null));
        // VAULT_PUBLIC_BASE 覆盖路径用的固定基址重载
        assertEquals("{\"u\":\"https://103.236.96.82:64868/v1/vault/app-latest.apk\"}",
                HotConfigRoutes.renderHotConfig(
                        "{\"u\":\"{base}/v1/vault/app-latest.apk\"}",
                        "https://103.236.96.82:64868"));
    }

    // ---------- 工具 ----------

    private void writeHotConfig() throws Exception {
        Files.write(dataDir.resolve("hotconfig.json"), HOT_JSON.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Resp {
        int status;
        String contentType;
        String contentLength;
        byte[] body;

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private Resp request(String method, String path, String... headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5_000);
        c.setReadTimeout(5_000);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            c.setRequestProperty(headers[i], headers[i + 1]);
        }
        if (!"GET".equals(method)) {
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(new byte[0]);
            }
        }
        Resp r = new Resp();
        r.status = c.getResponseCode();
        r.contentType = c.getHeaderField("Content-Type");
        r.contentLength = c.getHeaderField("Content-Length");
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream src = in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        r.body = out.toByteArray();
        c.disconnect();
        return r;
    }
}
