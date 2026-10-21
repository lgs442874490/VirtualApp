package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号池控制面 API 端到端（假 SessionControl，不连游戏服）：
 * /v1/vault/accounts、/role-action、/account-action、/global-action 全链路
 * （签名信封 + 参数校验 + 错误码映射 + 批量聚合语义 + /status 兼容扩展，M1 契约 §3）。
 */
public class AccountPoolApiTest {

    private static final String SECRET = "pool-test-secret";
    private static final String FPID = "48460403";
    private static final String FPID2 = "80971192";
    private static final String SID = "888";
    private static final String UID_A = "35371154";
    private static final String UID_B = "88280792";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private PoolKeeper keeper;
    private String base;

    /** 可脚本化的控制面假实现（默认动作成功、默认离线）。 */
    static final class PoolKeeper implements SessionControl {
        final List<SessionStatus> statuses = new ArrayList<>();
        final Map<String, RoleRuntime> runtimes = new LinkedHashMap<>();
        final Map<String, RoleOpResult> scripted = new LinkedHashMap<>();
        final List<String[]> calls = new ArrayList<>();
        int onlineCount;

        private static String key(String fpid, String sid, String uid) {
            return fpid + "|" + sid + "|" + uid;
        }

        void runtime(String fpid, String sid, String uid, boolean online, String runState) {
            RoleRuntime rt = new RoleRuntime();
            rt.online = online;
            rt.runState = runState;
            runtimes.put(key(fpid, sid, uid), rt);
        }

        void script(String fpid, String sid, String uid, String action, int code, String message,
                    String runState, RoleRef... stopped) {
            RoleOpResult r = code == 0 ? RoleOpResult.ok(runState,
                    Arrays.asList(stopped)) : RoleOpResult.fail(code, message);
            scripted.put(key(fpid, sid, uid) + "|" + action, r);
        }

        @Override
        public void reconcile() {
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }

        @Override
        public List<SessionStatus> status() {
            return statuses;
        }

        @Override
        public boolean yield(String fpid, long probeMillis) {
            return true;
        }

        @Override
        public RoleRuntime roleRuntime(String fpid, String sid, String uid) {
            RoleRuntime rt = runtimes.get(key(fpid, sid, uid));
            return rt != null ? rt : RoleRuntime.offline();
        }

        @Override
        public RoleOpResult roleAction(String fpid, String sid, String uid, String action) {
            calls.add(new String[]{fpid, sid, uid, action});
            RoleOpResult r = scripted.get(key(fpid, sid, uid) + "|" + action);
            return r != null ? r : RoleOpResult.ok("RUNNING");
        }

        @Override
        public int onlineRoleCount() {
            return onlineCount;
        }
    }

    @Before
    public void setUp() throws Exception {
        Path dataDir = tmp.getRoot().toPath();
        VaultStore store = new VaultStore(dataDir);
        store.load();
        StateStore stateStore = new StateStore(dataDir);
        stateStore.load();
        keeper = new PoolKeeper();
        VaultConfig config = new VaultConfig("127.0.0.1:0", dataDir, SECRET, null, "human_first");
        api = new VaultHttpApi(config, store, keeper, stateStore);
        api.start();
        base = "http://127.0.0.1:" + api.server().getAddress().getPort();
        syncPool();
    }

    @After
    public void tearDown() {
        api.stop();
    }

    /** 账号池种子：48460403（888 服双角色）+ 80971192。 */
    private void syncPool() throws Exception {
        JSONArray accounts = new JSONArray();
        JSONObject a1 = new JSONObject();
        a1.put("fpid", FPID);
        a1.put("token", "tk-A");
        a1.put("label", "开发号");
        a1.put("keep", true);
        a1.put("activeSid", SID);
        a1.put("activeUid", UID_A);
        JSONArray roles = new JSONArray();
        roles.put(role(SID, UID_A, "隆科多", 70, 208350000, true));
        roles.put(role(SID, UID_B, "小鹿矿工5号", 30, 12000000, true));
        a1.put("roles", roles);
        accounts.put(a1);
        JSONObject a2 = new JSONObject();
        a2.put("fpid", FPID2);
        a2.put("token", "tk-B");
        a2.put("label", "备用号");
        a2.put("keep", false);
        a2.put("activeSid", "913");
        a2.put("activeUid", "1001");
        accounts.put(a2);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", f).getInt("code"));
    }

    private static JSONObject role(String sid, String uid, String name, long level, long power,
                                   boolean enabled) {
        JSONObject r = new JSONObject();
        r.put("sid", sid);
        r.put("uid", uid);
        r.put("name", name);
        r.put("level", level);
        r.put("power", power);
        r.put("enabled", enabled);
        return r;
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
        try (InputStream in = conn.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ---------- §5-5 /accounts 视图 / 计数 / state 字段完整 ----------

    @Test
    public void accountsView_countsAndStateFieldsComplete() throws Exception {
        keeper.runtime(FPID, SID, UID_A, true, "RUNNING");
        keeper.onlineCount = 1;

        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject rsp = post("/v1/vault/accounts", "vaultAccounts", f);
        assertEquals(0, rsp.getInt("code"));
        JSONObject data = rsp.getJSONObject("data");
        assertTrue(data.getLong("serverTime") > 0);
        assertEquals(1, data.getInt("onlineCount"));
        JSONArray arr = data.getJSONArray("accounts");
        assertEquals(2, arr.length());

        JSONObject acc = arr.getJSONObject(0);
        assertEquals(FPID, acc.getString("fpid"));
        assertEquals("开发号", acc.getString("accountName"));
        assertEquals(SID, acc.getString("activeSid"));
        assertEquals(UID_A, acc.getString("activeUid"));
        assertTrue(acc.getBoolean("keep"));
        assertEquals(2, acc.getInt("workspaceCount"));
        assertEquals(1, acc.getInt("onlineCount"));

        JSONArray roles = acc.getJSONArray("roles");
        assertEquals(2, roles.length());
        JSONObject ra = roles.getJSONObject(0);
        assertEquals(SID, ra.getString("sid"));
        assertEquals(UID_A, ra.getString("uid"));
        assertEquals("隆科多", ra.getString("name"));
        assertEquals(70, ra.getLong("level"));
        assertEquals(208350000, ra.getLong("power"));
        assertTrue(ra.getBoolean("enabled"));
        JSONObject st = ra.getJSONObject("state");
        assertTrue(st.getBoolean("online"));
        assertEquals("RUNNING", st.getString("runState"));
        // 契约 §2.1 state 九字段齐全
        assertTrue(st.has("recoveryState"));
        assertTrue(st.has("statusMessage"));
        assertTrue(st.has("takeoverRecoveryActive"));
        assertTrue(st.has("nextRecoveryAt"));
        assertTrue(st.has("startupInProgress"));
        assertTrue(st.has("loginInProgress"));
        assertTrue(st.has("runScheduleState"));

        JSONObject rb = roles.getJSONObject(1);
        assertFalse(rb.getJSONObject("state").getBoolean("online"));
        assertEquals("STOPPED", rb.getJSONObject("state").getString("runState"));
        assertEquals("AVAILABLE", rb.getJSONObject("state").getString("recoveryState"));
    }

    @Test
    public void accountsView_offlineRoleDerivedFromNoSession() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject data = post("/v1/vault/accounts", "vaultAccounts", f).getJSONObject("data");
        assertEquals(0, data.getInt("onlineCount"));
        JSONArray roles = data.getJSONArray("accounts").getJSONObject(0).getJSONArray("roles");
        for (int i = 0; i < roles.length(); i++) {
            JSONObject st = roles.getJSONObject(i).getJSONObject("state");
            assertFalse(st.getBoolean("online"));
            assertEquals("STOPPED", st.getString("runState"));
        }
    }

    @Test
    public void accountsView_sameFpidMultiRoleOnline_countsAggregated() throws Exception {
        // M1d：同 fpid 两角色并行在线 → account.onlineCount=2（可>1），全局=各账号之和
        keeper.runtime(FPID, SID, UID_A, true, "RUNNING");
        keeper.runtime(FPID, SID, UID_B, true, "RUNNING");
        keeper.runtime(FPID2, "913", "1001", true, "RUNNING");

        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject data = post("/v1/vault/accounts", "vaultAccounts", f).getJSONObject("data");
        JSONArray arr = data.getJSONArray("accounts");
        assertEquals(2, arr.getJSONObject(0).getInt("onlineCount"));
        assertEquals(1, arr.getJSONObject(1).getInt("onlineCount"));
        assertEquals(3, data.getInt("onlineCount"));
        JSONObject stB = arr.getJSONObject(0).getJSONArray("roles").getJSONObject(1)
                .getJSONObject("state");
        assertTrue(stB.getBoolean("online"));
        assertEquals("RUNNING", stB.getString("runState"));
    }

    // ---------- §3.2 /role-action：信封 / 错误映射 / stoppedRoles ----------

    @Test
    public void roleAction_successEnvelopeWithStoppedRoles() throws Exception {
        keeper.script(FPID, SID, UID_A, "login", 0, "", "RUNNING",
                new SessionControl.RoleRef(SID, UID_B));

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("sid", SID);
        f.put("uid", UID_A);
        f.put("action", "login");
        JSONObject rsp = post("/v1/vault/role-action", "vaultRoleAction", f);
        assertEquals(0, rsp.getInt("code"));
        JSONObject data = rsp.getJSONObject("data");
        assertTrue(data.getBoolean("accepted"));
        assertEquals("RUNNING", data.getString("state"));
        assertEquals("", data.getString("message"));
        JSONArray stopped = data.getJSONArray("stoppedRoles");
        assertEquals(1, stopped.length());
        assertEquals(SID, stopped.getJSONObject(0).getString("sid"));
        assertEquals(UID_B, stopped.getJSONObject(0).getString("uid"));
        // 请求字段按契约下发到 keeper
        assertTrue(Arrays.asList(FPID, SID, UID_A, "login")
                .equals(Arrays.asList(keeper.calls.get(0))));
    }

    @Test
    public void roleAction_errorCodeMappedToEnvelope() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("sid", SID);
        f.put("uid", "99999999");
        f.put("action", "login");
        // 假实现默认成功 → 这里显式脚本化为角色不存在
        keeper.script(FPID, SID, "99999999", "login",
                SessionControl.RoleOpResult.CODE_ROLE_NOT_FOUND, "角色不存在: sid=888 uid=99999999",
                "");
        JSONObject rsp = post("/v1/vault/role-action", "vaultRoleAction", f);
        assertEquals(SessionControl.RoleOpResult.CODE_ROLE_NOT_FOUND, rsp.getInt("code"));
        assertTrue(rsp.getString("message").contains("角色不存在"));
    }

    @Test
    public void roleAction_missingParams_3002() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sid", SID);
        f.put("uid", UID_A);
        f.put("action", "login");
        assertEquals(3002, post("/v1/vault/role-action", "vaultRoleAction", f).getInt("code"));
    }

    // ---------- §3.3 /account-action：login-all 全角色尝试（M1d 多角色并行）/ stop-all / 部分失败 ----------

    @Test
    public void accountAction_loginAll_attemptsEveryRoleAndReports() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("action", "login-all");
        JSONObject data = post("/v1/vault/account-action", "vaultAccountAction", f)
                .getJSONObject("data");
        assertTrue(data.getBoolean("accepted"));
        JSONArray results = data.getJSONArray("results");
        assertEquals(2, results.length());
        // M1d：全部角色逐个尝试上线（不再因一个在线就跳过），结果逐角色返回
        assertTrue(results.getJSONObject(0).getBoolean("ok"));
        assertEquals(UID_A, results.getJSONObject(0).getString("uid"));
        assertTrue(results.getJSONObject(1).getBoolean("ok"));
        assertEquals(UID_B, results.getJSONObject(1).getString("uid"));
        assertEquals("", results.getJSONObject(1).getString("message"));
        assertEquals("两个角色都发起了 login", 2, keeper.calls.size());
        assertEquals("login", keeper.calls.get(0)[3]);
        assertEquals("login", keeper.calls.get(1)[3]);
    }

    @Test
    public void accountAction_loginAll_partialFailureReportedPerRole() throws Exception {
        keeper.script(FPID, SID, UID_A, "login",
                SessionControl.RoleOpResult.CODE_CONCURRENCY_LIMIT, "并发会话已达上限 1", "");
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("action", "login-all");
        JSONObject data = post("/v1/vault/account-action", "vaultAccountAction", f)
                .getJSONObject("data");
        JSONArray results = data.getJSONArray("results");
        // A 失败不连带 B：B 仍被尝试并成功；A 的失败原因逐角色回传
        assertFalse(results.getJSONObject(0).getBoolean("ok"));
        assertTrue(results.getJSONObject(0).getString("message").contains("上限"));
        assertTrue(results.getJSONObject(1).getBoolean("ok"));
        assertEquals(2, keeper.calls.size());
    }

    @Test
    public void accountAction_stopAll_loopsEveryRole() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("action", "stop-all");
        JSONObject data = post("/v1/vault/account-action", "vaultAccountAction", f)
                .getJSONObject("data");
        assertTrue(data.getBoolean("accepted"));
        assertEquals(2, data.getJSONArray("results").length());
        assertEquals(2, keeper.calls.size());
        assertEquals("stop", keeper.calls.get(0)[3]);
        assertEquals("stop", keeper.calls.get(1)[3]);
    }

    @Test
    public void accountAction_paramAndAccountValidation() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "login-all");
        assertEquals(3002, post("/v1/vault/account-action", "vaultAccountAction", f).getInt("code"));

        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("fpid", "nope");
        f2.put("action", "login-all");
        assertEquals(1004, post("/v1/vault/account-action", "vaultAccountAction", f2).getInt("code"));

        Map<String, Object> f3 = new LinkedHashMap<>();
        f3.put("fpid", FPID);
        f3.put("action", "bogus");
        assertEquals(3002, post("/v1/vault/account-action", "vaultAccountAction", f3).getInt("code"));
    }

    // ---------- §3.4 /global-action：每账号全部 enabled 角色 / 汇总 ----------

    @Test
    public void globalAction_loginAll_attemptsEveryEnabledRoleAndAggregates() throws Exception {
        keeper.onlineCount = 2;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "login-all");
        JSONObject data = post("/v1/vault/global-action", "vaultGlobalAction", f)
                .getJSONObject("data");
        assertTrue(data.getBoolean("accepted"));
        assertEquals(2, data.getInt("onlineCount"));
        JSONArray accounts = data.getJSONArray("accounts");
        assertEquals(2, accounts.length());
        // 48460403：两个 enabled 角色都尝试上线（M1d 多角色并行），onlineUid 取首个成功
        JSONObject first = accounts.getJSONObject(0);
        assertEquals(FPID, first.getString("fpid"));
        assertTrue(first.getBoolean("ok"));
        assertEquals(UID_A, first.getString("onlineUid"));
        JSONArray rs = first.getJSONArray("results");
        assertEquals(2, rs.length());
        assertTrue(rs.getJSONObject(0).getBoolean("ok"));
        assertEquals(UID_A, rs.getJSONObject(0).getString("uid"));
        assertTrue(rs.getJSONObject(1).getBoolean("ok"));
        assertEquals(UID_B, rs.getJSONObject(1).getString("uid"));
        // 80971192：sync 合并自动建 active 角色条目（913/1001）→ 该角色尝试上线成功
        JSONObject second = accounts.getJSONObject(1);
        assertTrue(second.getBoolean("ok"));
        assertEquals("1001", second.getString("onlineUid"));
        assertEquals(1, second.getJSONArray("results").length());
        // 三个角色（48460403 两个 + 80971192 一个）都发起了 login
        assertEquals(3, keeper.calls.size());
        assertEquals("login", keeper.calls.get(0)[3]);
        assertEquals("login", keeper.calls.get(1)[3]);
        assertEquals("login", keeper.calls.get(2)[3]);
    }

    @Test
    public void globalAction_stopAll_andTokenlessAccountSkipped() throws Exception {
        // 80971192 无 token 的场景：重建一个空库种子
        api.stop();
        Path dataDir = tmp.newFolder().toPath();
        VaultStore store = new VaultStore(dataDir);
        store.load();
        StateStore stateStore = new StateStore(dataDir);
        stateStore.load();
        keeper = new PoolKeeper();
        VaultConfig config = new VaultConfig("127.0.0.1:0", dataDir, SECRET, null, "human_first");
        api = new VaultHttpApi(config, store, keeper, stateStore);
        api.start();
        base = "http://127.0.0.1:" + api.server().getAddress().getPort();

        JSONArray accounts = new JSONArray();
        JSONObject a1 = new JSONObject();
        a1.put("fpid", FPID);
        a1.put("token", "");
        a1.put("keep", false);
        accounts.put(a1);
        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("pkg", "com.carlos.vault");
        sf.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", sf).getInt("code"));

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "stop-all");
        JSONObject data = post("/v1/vault/global-action", "vaultGlobalAction", f)
                .getJSONObject("data");
        assertTrue(data.getBoolean("accepted"));
        assertEquals(0, data.getInt("onlineCount"));
        JSONArray accs = data.getJSONArray("accounts");
        assertEquals(1, accs.length());
        assertFalse(accs.getJSONObject(0).getBoolean("ok"));
        assertTrue(accs.getJSONObject(0).getString("message").contains("token"));
    }

    @Test
    public void globalAction_unknownAction_3002() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "reboot");
        assertEquals(3002, post("/v1/vault/global-action", "vaultGlobalAction", f).getInt("code"));
    }

    // ---------- §3.5 /status 扩展（旧字段保留） ----------

    @Test
    public void statusExtension_oldFieldsKeptAndStateRolesAdded() throws Exception {
        SessionControl.SessionStatus s = new SessionControl.SessionStatus();
        s.fpid = FPID;
        s.label = "开发号";
        s.state = "online";
        s.sid = SID;
        s.activeUid = UID_A;
        s.hasToken = true;
        keeper.statuses.add(s);
        keeper.runtime(FPID, SID, UID_A, true, "RUNNING");

        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject rsp = post("/v1/vault/status", "vaultStatus", f);
        assertEquals(0, rsp.getInt("code"));
        JSONObject row = rsp.getJSONObject("data").getJSONArray("accounts").getJSONObject(0);
        // 旧字段（App v4.1.4 依赖）原样保留 —— state 仍是字符串引擎态
        assertEquals("online", row.getString("state"));
        assertEquals(SID, row.getString("sid"));
        assertEquals(UID_A, row.getString("activeUid"));
        assertTrue(row.getBoolean("hasToken"));
        assertTrue(row.has("sinceTs"));
        // M1 扩展（roleState 对象 + roles 数组；不与旧字符串 state 同名冲突）
        assertEquals("RUNNING", row.getJSONObject("roleState").getString("runState"));
        assertTrue(row.getJSONObject("roleState").getBoolean("online"));
        assertEquals(2, row.getJSONArray("roles").length());
    }

    // ---------- 新 OP 参与签名（op 名不对 → 2001） ----------

    @Test
    public void newEndpoints_signedWithWrongOp_rejected() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        // 用旧 op 名签新端点：签名不匹配
        assertEquals(2001, post("/v1/vault/accounts", "vaultStatus", f).getInt("code"));
        assertEquals(2001, post("/v1/vault/role-action", "vaultAccounts", f).getInt("code"));
        assertEquals(2001, post("/v1/vault/account-action", "vaultSync", f).getInt("code"));
        assertEquals(2001, post("/v1/vault/global-action", "vaultPolicy", f).getInt("code"));
    }
}
