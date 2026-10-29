package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

/** API 端到端（假 SessionControl，不连游戏服）：签名信封 + sync/token/status/policy 全链路。 */
public class VaultHttpApiTest {

    private static final String SECRET = "api-test-secret";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private FakeKeeper keeper;
    private StateStore stateStore;
    private VaultStore store;
    private String base;

    static final class FakeKeeper implements SessionControl {
        int reconcileCalls;
        String yieldedFpid;
        long yieldedMillis;
        final List<SessionStatus> statuses = new ArrayList<>();

        @Override
        public void reconcile() {
            reconcileCalls++;
        }

        @Override
        public boolean ensureSession(String fpid) {
            return !"missing".equals(fpid);
        }

        @Override
        public List<SessionStatus> status() {
            return statuses;
        }

        @Override
        public boolean yield(String fpid, long probeMillis) {
            this.yieldedFpid = fpid;
            this.yieldedMillis = probeMillis;
            return !"missing".equals(fpid);
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

    // ---------- HTTP 工具 ----------

    private JSONObject post(String path, String op, Map<String, Object> fields) throws Exception {
        String body = ApiSign.buildSignedBody(SECRET, op, fields);
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        String text;
        try (InputStream in = conn.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new JSONObject(text);
    }

    private JSONObject get(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        String text;
        try (InputStream in = conn.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new JSONObject(text);
    }

    // ---------- 用例 ----------

    @Test
    public void healthz() throws Exception {
        JSONObject rsp = get("/healthz");
        assertEquals(0, rsp.getInt("code"));
        assertTrue(rsp.getLong("serverTime") > 0);
    }

    @Test
    public void sync_then_token_then_status_flow() throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject a1 = new JSONObject();
        a1.put("fpid", "80971192");
        a1.put("token", "tk-A");
        a1.put("label", "开发号");
        a1.put("keep", true);
        a1.put("activeSid", "913");
        a1.put("identity", "{\"distinct_id\":\"dd\"}");
        JSONArray roles = new JSONArray();
        JSONObject r1 = new JSONObject();
        r1.put("sid", "913");
        r1.put("identity", "{\"distinct_id\":\"dd\",\"device_id\":\"ee\"}");
        roles.put(r1);
        a1.put("roles", roles);
        accounts.put(a1);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        JSONObject rsp = post("/v1/vault/sync", "vaultSync", f);
        assertEquals(0, rsp.getInt("code"));
        assertEquals(1, rsp.getJSONObject("data").getInt("accepted"));
        assertEquals(1, keeper.reconcileCalls);

        // token 补给
        Map<String, Object> tf = new LinkedHashMap<>();
        tf.put("fpid", "80971192");
        tf.put("token", "tk-B");
        assertEquals(0, post("/v1/vault/token", "vaultToken", tf).getInt("code"));

        // 状态查询（假 keeper 回填）
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = "80971192";
        s.label = "开发号";
        s.state = "online";
        s.sid = "913";
        s.hasToken = true;
        keeper.statuses.add(s);
        Map<String, Object> sf = new LinkedHashMap<>();
        JSONObject status = post("/v1/vault/status", "vaultStatus", sf);
        assertEquals(0, status.getInt("code"));
        JSONArray arr = status.getJSONObject("data").getJSONArray("accounts");
        assertEquals(1, arr.length());
        assertEquals("online", arr.getJSONObject(0).getString("state"));
        assertEquals("913", arr.getJSONObject(0).getString("sid"));
    }

    @Test
    public void token_unknownAccount_1004() throws Exception {
        Map<String, Object> tf = new LinkedHashMap<>();
        tf.put("fpid", "nope");
        tf.put("token", "tk");
        JSONObject rsp = post("/v1/vault/token", "vaultToken", tf);
        assertEquals(1004, rsp.getInt("code"));
    }

    @Test
    public void policy_yield_delegates() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "yield");
        f.put("fpid", "80971192");
        f.put("minutes", 45);
        JSONObject rsp = post("/v1/vault/policy", "vaultPolicy", f);
        assertEquals(0, rsp.getInt("code"));
        assertEquals("80971192", keeper.yieldedFpid);
        assertEquals(45 * 60_000L, keeper.yieldedMillis);
    }

    @Test
    public void policy_warReset_clearsStuckWartime() throws Exception {
        ServerWarState w = ServerWarState.forRole("80971192", 913L);
        long now = System.currentTimeMillis();
        w.core.forceWartime(true, "测试卡死", now);
        assertTrue(w.core.wartime());

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "war-reset");
        f.put("fpid", "80971192");
        JSONObject rsp = post("/v1/vault/policy", "vaultPolicy", f);
        assertEquals(0, rsp.getInt("code"));
        assertEquals("清除 1 个战时实例", 1, rsp.getJSONObject("data").getInt("cleared"));
        assertFalse("解卡后不再拦截", w.core.wartime());
        assertEquals(0, ServerWarState.resetWartime("80971192"));
        ServerWarState.unregister("80971192", 913L);
    }

    @Test
    public void sync_emptyListRejectedUnlessAllowEmpty() throws Exception {
        // 1) 先同步 1 个账号，建立服务端身份库
        JSONArray accounts = new JSONArray();
        JSONObject a1 = new JSONObject();
        a1.put("fpid", "80971192");
        a1.put("token", "tk-A");
        a1.put("label", "开发号");
        accounts.put(a1);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", f).getInt("code"));
        assertEquals(1, store.snapshot().size());

        // 2) 空列表（模拟卸载重装/换机后 App 首次全量同步）：拒绝覆盖，账号保留
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("pkg", "com.carlos.vault");
        empty.put("accountsJson", "[]");
        JSONObject rejected = post("/v1/vault/sync", "vaultSync", empty);
        assertEquals(0, rejected.getInt("code"));
        JSONObject d = rejected.getJSONObject("data");
        assertEquals(0, d.getInt("accepted"));
        assertTrue("空同步被识别并拒绝", d.getBoolean("emptyRejected"));
        assertEquals(1, d.getInt("serverAccounts"));
        assertEquals("服务端账号不被清空", 1, store.snapshot().size());
        assertEquals("80971192", store.snapshot().get(0).fpid);

        // 3) 显式 allowEmpty=true（App「删除全部账号」）→ 正常清空
        Map<String, Object> forced = new LinkedHashMap<>();
        forced.put("pkg", "com.carlos.vault");
        forced.put("accountsJson", "[]");
        forced.put("allowEmpty", true);
        JSONObject cleared = post("/v1/vault/sync", "vaultSync", forced);
        assertEquals(0, cleared.getInt("code"));
        assertEquals(0, cleared.getJSONObject("data").getInt("accepted"));
        assertEquals(0, store.snapshot().size());
    }

    @Test
    public void state_endpoint_returnsStatesAndAccounts() throws Exception {
        stateStore.putString("todo:111:913", "[{\"id\":2705001}]");
        stateStore.putString("mod:111:913:bear", "{\"trap\":\"未预约\"}");
        stateStore.putLong("act:111:913", 270L);
        stateStore.putLong("questts:111:913", System.currentTimeMillis());
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = "111";
        s.state = "online";
        keeper.statuses.add(s);

        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject rsp = post("/v1/vault/state", "vaultState", f);
        assertEquals(0, rsp.getInt("code"));
        JSONObject states = rsp.getJSONObject("data").getJSONObject("states");
        assertEquals(4, states.length());
        assertEquals("[{\"id\":2705001}]", states.getString("todo:111:913"));
        assertEquals(270L, states.getLong("act:111:913"));
        assertEquals("online", rsp.getJSONObject("data").getJSONArray("accounts")
                .getJSONObject(0).getString("state"));
    }

    @Test
    public void automation_read_returnsConfigSnapshot() throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject a = new JSONObject();
        a.put("fpid", "48460403");
        a.put("token", "tk-new");
        a.put("label", "新测试号");
        a.put("keep", false);
        a.put("auto", false);
        a.put("activeSid", "888");
        JSONArray roles = new JSONArray();
        JSONObject r = new JSONObject();
        r.put("sid", "888");
        JSONObject cfg = new JSONObject();
        cfg.put("mail", true);
        cfg.put("daily_mission", true);
        r.put("config", cfg);
        roles.put(r);
        a.put("roles", roles);
        accounts.put(a);
        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("pkg", "com.carlos.vault");
        sf.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", sf).getInt("code"));

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "read");
        f.put("fpid", "48460403");
        JSONObject rsp = post("/v1/vault/automation", "vaultAutomation", f);
        assertEquals(0, rsp.getInt("code"));
        JSONObject data = rsp.getJSONObject("data");
        assertEquals("48460403", data.getString("fpid"));
        assertEquals("888", data.getString("sid"));
        assertFalse(data.getBoolean("auto"));
        assertTrue(data.getBoolean("hasToken"));
        assertTrue(data.getJSONObject("config").getBoolean("mail"));
        assertEquals("offline", data.getString("state"));
    }

    @Test
    public void badSignature_rejected() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "yield");
        f.put("fpid", "x");
        String body = ApiSign.buildSignedBody("wrong-secret", "vaultPolicy", f);
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/v1/vault/policy").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        JSONObject rsp;
        try (InputStream in = conn.getInputStream()) {
            rsp = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(2001, rsp.getInt("code"));
    }

    @Test
    public void activeUid_roundTrips_syncToStatus() throws Exception {
        // 同服多角色：App sync 带 activeSid + activeUid（888 服第二角色）
        JSONArray accounts = new JSONArray();
        JSONObject a1 = new JSONObject();
        a1.put("fpid", "48460403");
        a1.put("token", "tk");
        a1.put("keep", true);
        a1.put("activeSid", "888");
        a1.put("activeUid", "88280792");
        accounts.put(a1);
        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("pkg", "com.carlos.vault");
        sf.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", sf).getInt("code"));

        // 无会话：status 回传账号库的 activeUid（App 据此确认服务器记对了角色）
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = "48460403";
        s.state = "offline";
        s.sid = "888";
        s.activeUid = "88280792";
        keeper.statuses.add(s);
        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject status = post("/v1/vault/status", "vaultStatus", f);
        JSONArray arr = status.getJSONObject("data").getJSONArray("accounts");
        assertEquals("888", arr.getJSONObject(0).getString("sid"));
        assertEquals("88280792", arr.getJSONObject(0).getString("activeUid"));
    }

    @Test
    public void activeUid_absentFromLegacyClient_statusEmptyString() throws Exception {
        // 旧客户端 sync 不带 activeUid → 库存 null → status 回传空串（App 按 0 处理）
        JSONArray accounts = new JSONArray();
        JSONObject a1 = new JSONObject();
        a1.put("fpid", "80971192");
        a1.put("token", "tk");
        a1.put("keep", true);
        a1.put("activeSid", "913");
        accounts.put(a1);
        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("pkg", "com.carlos.vault");
        sf.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", sf).getInt("code"));

        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = "80971192";
        s.state = "offline";
        s.sid = "913";
        keeper.statuses.add(s);
        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject status = post("/v1/vault/status", "vaultStatus", f);
        assertEquals("", status.getJSONObject("data").getJSONArray("accounts")
                .getJSONObject(0).getString("activeUid"));
    }

    @Test
    public void automationSnapshot_carriesActiveUid() throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject a = new JSONObject();
        a.put("fpid", "48460403");
        a.put("token", "tk-new");
        a.put("keep", false);
        a.put("auto", false);
        a.put("activeSid", "888");
        a.put("activeUid", "88280792");
        accounts.put(a);
        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("pkg", "com.carlos.vault");
        sf.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", sf).getInt("code"));

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "read");
        f.put("fpid", "48460403");
        JSONObject rsp = post("/v1/vault/automation", "vaultAutomation", f);
        assertEquals("888", rsp.getJSONObject("data").getString("sid"));
        assertEquals("88280792", rsp.getJSONObject("data").getString("activeUid"));
    }

    // ==================== R2 Phase 2：配置按角色 uid ====================

    private static JSONObject roleRow(String sid, String uid, Map<String, Object> cfg,
                                      Map<String, Object> params) {
        JSONObject r = new JSONObject();
        r.put("sid", sid);
        r.put("uid", uid);
        r.put("identity", "");
        JSONObject c = new JSONObject();
        for (Map.Entry<String, Object> e : cfg.entrySet()) c.put(e.getKey(), e.getValue());
        r.put("config", c);
        if (params != null) {
            JSONObject p = new JSONObject();
            for (Map.Entry<String, Object> e : params.entrySet()) p.put(e.getKey(), e.getValue());
            r.put("params", p);
        }
        return r;
    }

    @Test
    public void sync_perRoleUidConfig_storedAndEffectivePerRole() throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject acc = new JSONObject();
        acc.put("fpid", "80971192");
        acc.put("token", "tk");
        acc.put("keep", true);
        acc.put("auto", true);
        acc.put("activeSid", "888");
        acc.put("activeUid", "35371154");
        JSONArray roles = new JSONArray();
        roles.put(roleRow("888", "35371154",
                Map.of("gather", true, "mail", true), Map.of("mail", Map.of("x", 1))));
        roles.put(roleRow("888", "88280792",
                Map.of("gather", false, "mail", false), Map.of("mail", Map.of("y", 2))));
        acc.put("roles", roles);
        accounts.put(acc);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", f).getInt("code"));

        VaultStore.Account stored = store.find("80971192");
        assertEquals("888:35371154", VaultStore.roleUidConfigKey(888L, 35371154L));
        assertEquals(Boolean.TRUE, stored.roleConfigByUid.get("888:35371154").get("gather"));
        assertEquals(Boolean.FALSE, stored.roleConfigByUid.get("888:88280792").get("gather"));
        assertTrue("带 uid 的角色行不得写旧 sid 表", stored.roleConfig.isEmpty());
        assertEquals("{\"x\":1}", stored.roleParamsByUid.get("888:35371154").get("mail"));
        assertEquals("{\"y\":2}", stored.roleParamsByUid.get("888:88280792").get("mail"));

        // automation read：按 activeUid 返回生效配置
        Map<String, Object> af = new LinkedHashMap<>();
        af.put("action", "read");
        af.put("fpid", "80971192");
        JSONObject auto = post("/v1/vault/automation", "vaultAutomation", af);
        assertEquals(0, auto.getInt("code"));
        JSONObject data = auto.getJSONObject("data");
        assertEquals("uid", data.getString("configScope"));
        assertTrue(data.getJSONObject("config").getBoolean("gather"));
        // 参数值口径与旧实现一致：JSON 字符串（App 端自行解析）
        assertEquals(1, new JSONObject(
                data.getJSONObject("params").getString("mail")).getInt("x"));

        // accounts：每个角色行回传各自有效配置
        Map<String, Object> of = new LinkedHashMap<>();
        JSONObject accountsRsp = post("/v1/vault/accounts", "vaultAccounts", of);
        assertEquals(0, accountsRsp.getInt("code"));
        JSONArray outRoles = accountsRsp.getJSONObject("data").getJSONArray("accounts")
                .getJSONObject(0).getJSONArray("roles");
        JSONObject r1 = null, r2 = null;
        for (int i = 0; i < outRoles.length(); i++) {
            JSONObject o = outRoles.getJSONObject(i);
            if ("35371154".equals(o.getString("uid"))) r1 = o;
            if ("88280792".equals(o.getString("uid"))) r2 = o;
        }
        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals("uid", r1.getString("configScope"));
        assertTrue(r1.getJSONObject("config").getBoolean("gather"));
        assertFalse(r2.getJSONObject("config").getBoolean("gather"));
    }

    @Test
    public void sync_oldAppWithoutUid_keepsSidDefaultAndInheritance() throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject acc = new JSONObject();
        acc.put("fpid", "22222222");
        acc.put("token", "tk");
        acc.put("activeSid", "724");
        JSONArray roles = new JSONArray();
        JSONObject r = new JSONObject();
        r.put("sid", "724");
        JSONObject c = new JSONObject();
        c.put("gather", true);
        r.put("config", c);
        roles.put(r);
        acc.put("roles", roles);
        accounts.put(acc);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", f).getInt("code"));
        VaultStore.Account stored = store.find("22222222");
        assertTrue("旧 App（无 uid）写 sid 表", stored.roleConfig.containsKey("724"));
        assertTrue("新角色继承区服默认", VaultStore.roleConfigOf(stored, 724L, 999L).get("gather"));
        assertEquals("sid", VaultStore.roleConfigScope(stored, 724L, 999L));
    }
}
