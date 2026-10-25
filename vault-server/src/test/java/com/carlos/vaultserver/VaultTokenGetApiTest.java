package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重装后 token 恢复读通道 {@code POST /v1/vault/token/get}（OP=vaultTokenGet，2026-10-25）端到端：
 * 签名信封 + 路由（不得被写通道 /v1/vault/token 前缀吞掉）+ 正常返回 + 1004/1005/2004/2001 错误码。
 *
 * <p>日志/错误信息纪律：任何分支都不得出现 token 明文——测试对错误 message 做断言。
 * 假 SessionControl 不连游戏服（与 {@link VaultHttpApiTest} 同模式）。
 */
public class VaultTokenGetApiTest {

    private static final String SECRET = "token-get-test-secret";
    private static final String PATH = "/v1/vault/token/get";
    private static final String OP = "vaultTokenGet";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private VaultStore store;
    private StateStore stateStore;
    private FakeKeeper keeper;
    private String base;

    /** 只实现接口必需方法（其余 default），token 读通道不依赖会话。 */
    static final class FakeKeeper implements SessionControl {
        int reconcileCalls;
        final List<SessionStatus> statuses = new ArrayList<>();

        @Override
        public void reconcile() {
            reconcileCalls++;
        }

        @Override
        public boolean ensureSession(String fpid) {
            return false;
        }

        @Override
        public List<SessionStatus> status() {
            return statuses;
        }

        @Override
        public boolean yield(String fpid, long probeMillis) {
            return false;
        }
    }

    @Before
    public void setUp() throws Exception {
        Path dataDir = tmp.getRoot().toPath();
        store = new VaultStore(dataDir);
        store.load();
        stateStore = new StateStore(dataDir);
        stateStore.load();
        keeper = new FakeKeeper();
        VaultConfig config = new VaultConfig("127.0.0.1:0", dataDir, SECRET, null, "human_first");
        api = new VaultHttpApi(config, store, keeper, stateStore);
        api.start();
        int port = api.server().getAddress().getPort();
        base = "http://127.0.0.1:" + port;
    }

    @After
    public void tearDown() {
        api.stop();
    }

    // ---------- 工具 ----------

    private JSONObject post(String path, String op, Map<String, Object> fields) throws Exception {
        return postWithSecret(SECRET, path, op, fields);
    }

    private JSONObject postWithSecret(String secret, String path, String op,
                                      Map<String, Object> fields) throws Exception {
        String body = ApiSign.buildSignedBody(secret, op, fields);
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = conn.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 经写通道 /v1/vault/sync 造账号（token 走 sync 的 accountsJson）。 */
    private void syncAccount(String fpid, String token) throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject a = new JSONObject();
        a.put("fpid", fpid);
        a.put("token", token);
        a.put("label", "恢复测试号");
        a.put("keep", true);
        a.put("activeSid", "913");
        accounts.put(a);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", f).getInt("code"));
    }

    private JSONObject getToken(String fpid) throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", fpid);
        return post(PATH, OP, f);
    }

    // ---------- 正常回取 ----------

    @Test
    public void get_afterSync_returnsServerToken() throws Exception {
        syncAccount("80971192", "tk-restored-A");
        int reconcileAfterSync = keeper.reconcileCalls;

        JSONObject rsp = getToken("80971192");
        assertEquals(0, rsp.getInt("code"));
        assertTrue(rsp.getLong("serverTime") > 0);
        JSONObject data = rsp.getJSONObject("data");
        assertEquals("80971192", data.getString("fpid"));
        assertEquals("tk-restored-A", data.getString("token"));
        assertTrue(data.getBoolean("hasToken"));
        // 读通道只读：不触发会话重对齐（与写通道 /token 的 reconcile 不同）
        assertEquals(reconcileAfterSync, keeper.reconcileCalls);
    }

    @Test
    public void get_afterTokenUpdate_returnsLatest() throws Exception {
        syncAccount("48460403", "tk-old");
        Map<String, Object> tf = new LinkedHashMap<>();
        tf.put("fpid", "48460403");
        tf.put("token", "tk-new");
        assertEquals(0, post("/v1/vault/token", "vaultToken", tf).getInt("code"));

        JSONObject rsp = getToken("48460403");
        assertEquals(0, rsp.getInt("code"));
        assertEquals("tk-new", rsp.getJSONObject("data").getString("token"));
    }

    /** 路由防吞：读通道 OP 与写通道 OP 绑定，写通道签名打读路径必须验签失败。 */
    @Test
    public void get_withWriteChannelOp_badSignature() throws Exception {
        syncAccount("111", "tk");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", "111");
        JSONObject rsp = post(PATH, "vaultToken", f);
        assertEquals(2001, rsp.getInt("code"));
    }

    // ---------- 业务错误码 ----------

    @Test
    public void get_unknownAccount_1004_andMessageHasNoToken() throws Exception {
        // 库里另一个账号有 token：错误信息不得顺手把它带出去
        syncAccount("222", "tk-other-secret");
        JSONObject rsp = getToken("nope");
        assertEquals(VaultHttpApi.CODE_ACCOUNT_NOT_FOUND, rsp.getInt("code"));
        assertTrue(rsp.getString("message").contains("nope"));
        assertFalse("错误信息不得含 token 明文",
                rsp.getString("message").contains("tk-other-secret"));
        assertEquals(0, rsp.getJSONObject("data").length());
    }

    @Test
    public void get_accountWithoutToken_1005() throws Exception {
        syncAccount("333", "");
        JSONObject rsp = getToken("333");
        assertEquals(VaultHttpApi.CODE_TOKEN_MISSING, rsp.getInt("code"));
        assertTrue(rsp.getString("message").contains("333"));
        assertEquals(0, rsp.getJSONObject("data").length());
    }

    @Test
    public void get_missingFpid_2004() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        JSONObject rsp = post(PATH, OP, f);
        assertEquals(ApiSign.CODE_BAD_REQUEST, rsp.getInt("code"));
    }

    @Test
    public void get_badSignature_2001() throws Exception {
        syncAccount("444", "tk");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", "444");
        JSONObject rsp = postWithSecret("wrong-secret", PATH, OP, f);
        assertEquals(ApiSign.CODE_BAD_SIGNATURE, rsp.getInt("code"));
    }

    // ---------- store 只读访问器语义 ----------

    @Test
    public void storeGetToken_unknownNull_emptyString() {
        assertNull(store.getToken("missing"));
        VaultStore.Account a = new VaultStore.Account("555");
        List<VaultStore.Account> one = new ArrayList<>();
        one.add(a);
        store.replaceAll(one);
        assertEquals("", store.getToken("555"));
        assertTrue(store.updateToken("555", "tk-direct"));
        assertEquals("tk-direct", store.getToken("555"));
    }
}
