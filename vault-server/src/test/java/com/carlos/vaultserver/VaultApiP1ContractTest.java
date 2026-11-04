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
 * P1 服务端 API 契约（2026-10-21，HTTP 全链路，假 SessionControl）：
 * /accounts 输出 account.recovery + roles[].pause_resume_at（ISO）；
 * /role-action 新 op 的响应扩展（pauseResumeAt/rename 字段）；
 * /account-action op recovery-settings（校验文案/持久化/recovery 回显/回调）。
 */
public class VaultApiP1ContractTest {

    private static final String SECRET = "p1-test-secret";
    private static final String FPID = "48460403";
    private static final String SID = "888";
    private static final String UID_A = "35371154";
    private static final String UID_B = "88280792";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private P1Keeper keeper;
    private VaultStore store;
    private String base;

    /** 可脚本化假 keeper：记录 5 参动作与恢复设置回调。 */
    static final class P1Keeper implements SessionControl {
        final List<SessionStatus> statuses = new ArrayList<>();
        final Map<String, RoleRuntime> runtimes = new LinkedHashMap<>();
        final List<Object[]> actionCalls = new ArrayList<>();   // {fpid,sid,uid,action,payload}
        boolean recoverySettingsNotified;

        private static String key(String fpid, String sid, String uid) {
            return fpid + "|" + sid + "|" + uid;
        }

        void runtime(String fpid, String sid, String uid, boolean online, String runState) {
            RoleRuntime rt = new RoleRuntime();
            rt.online = online;
            rt.runState = runState;
            runtimes.put(key(fpid, sid, uid), rt);
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
        public RoleOpResult roleAction(String fpid, String sid, String uid, String action,
                                       Map<String, Object> payload) {
            actionCalls.add(new Object[]{fpid, sid, uid, action, payload});
            if (!Arrays.asList("login", "start", "pause", "resume", "logout", "stop",
                    "rename", "remove", "enabled").contains(action)) {
                return RoleOpResult.fail(3002, "未知 action: " + action);
            }
            RoleOpResult r = RoleOpResult.ok("RUNNING");
            if ("pause".equals(action)) {
                r.runState = "PAUSED";
                r.message = "账号已暂停，将在 30 分钟后自动上线";
                r.pauseResumeAt = 499999999999L;
            } else if ("rename".equals(action)) {
                r.message = "角色名已更新";
                r.name = "新名字";
                r.usedDiamond = true;
                r.diamondCost = 400L;
                r.itemId = 610001L;
            }
            return r;
        }

        @Override
        public void onRecoverySettingsChanged(String fpid) {
            recoverySettingsNotified = true;
        }

        @Override
        public int onlineRoleCount() {
            return 0;
        }
    }

    @Before
    public void setUp() throws Exception {
        Path dataDir = tmp.getRoot().toPath();
        store = new VaultStore(dataDir);
        store.load();
        StateStore stateStore = new StateStore(dataDir);
        stateStore.load();
        keeper = new P1Keeper();
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
        roles.put(role(SID, UID_A, "隆科多"));
        roles.put(role(SID, UID_B, "小鹿矿工5号"));
        a1.put("roles", roles);
        accounts.put(a1);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("pkg", "com.carlos.vault");
        f.put("accountsJson", accounts.toString());
        assertEquals(0, post("/v1/vault/sync", "vaultSync", f).getInt("code"));
    }

    private static JSONObject role(String sid, String uid, String name) {
        JSONObject r = new JSONObject();
        r.put("sid", sid);
        r.put("uid", uid);
        r.put("name", name);
        r.put("enabled", true);
        return r;
    }

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

    // ---------- /accounts：account.recovery + roles[].pause_resume_at ----------

    @Test
    public void accountsShape_recoveryAndPauseResumeAt() throws Exception {
        store.updateRecoverySettings(FPID, false, 45 * 60, 10, 5, 60);
        store.applyRolePauseSchedule(FPID, SID, UID_B, 1700000000000L);
        keeper.runtime(FPID, SID, UID_A, true, "RUNNING");

        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject data = post("/v1/vault/accounts", "vaultAccounts", f).getJSONObject("data");
        JSONObject acc = data.getJSONArray("accounts").getJSONObject(0);

        // account.recovery 五字段（initialDelayMinutes 秒→分钟向上取整）
        JSONObject rec = acc.getJSONObject("recovery");
        assertFalse(rec.getBoolean("enabled"));
        assertEquals(45, rec.getInt("initialDelayMinutes"));
        assertEquals(10, rec.getInt("retryIntervalSeconds"));
        assertEquals(5, rec.getInt("maxAttempts"));
        assertEquals(60, rec.getInt("cooldownMinutes"));

        JSONArray roles = acc.getJSONArray("roles");
        JSONObject ra = roles.getJSONObject(0);
        assertEquals("无恢复计划 → 空", "", ra.getString("pause_resume_at"));
        JSONObject rb = roles.getJSONObject(1);
        assertEquals("ISO-8601 时刻", "2023-11-14T22:13:20Z", rb.getString("pause_resume_at"));
        assertEquals("暂停/待恢复 online=false", false,
                rb.getJSONObject("state").getBoolean("online"));
    }

    @Test
    public void accountsShape_recoveryDefaults_whenUntouched() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        JSONObject rec = post("/v1/vault/accounts", "vaultAccounts", f).getJSONObject("data")
                .getJSONArray("accounts").getJSONObject(0).getJSONObject("recovery");
        assertTrue(rec.getBoolean("enabled"));
        assertEquals(30, rec.getInt("initialDelayMinutes"));
        assertEquals(5, rec.getInt("retryIntervalSeconds"));
        assertEquals(3, rec.getInt("maxAttempts"));
        assertEquals(15, rec.getInt("cooldownMinutes"));
    }

    // ---------- /role-action：P1 op 响应扩展 + payload 下发 ----------

    @Test
    public void roleAction_pause_responseCarriesPauseResumeAt() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("sid", SID);
        f.put("uid", UID_A);
        f.put("action", "pause");
        JSONObject data = post("/v1/vault/role-action", "vaultRoleAction", f).getJSONObject("data");
        assertEquals("PAUSED", data.getString("state"));
        assertEquals("账号已暂停，将在 30 分钟后自动上线", data.getString("message"));
        assertEquals(499999999999L, data.getLong("pauseResumeAt"));
        // payload（整个 fields）下发到 keeper 5 参入口
        assertEquals("pause", keeper.actionCalls.get(0)[3]);
        assertEquals(FPID, keeper.actionCalls.get(0)[0]);
    }

    @Test
    public void roleAction_rename_responseCarriesRenameFields() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("sid", SID);
        f.put("uid", UID_A);
        f.put("action", "rename");
        f.put("name", "隆科多二世");
        JSONObject data = post("/v1/vault/role-action", "vaultRoleAction", f).getJSONObject("data");
        assertEquals("新名字", data.getString("name"));
        assertTrue(data.getBoolean("usedDiamond"));
        assertEquals(400, data.getLong("diamondCost"));
        assertEquals(610001, data.getLong("itemId"));
        assertEquals("隆科多二世", keeper.actionCalls.get(0)[4] == null
                ? null : ((Map<?, ?>) keeper.actionCalls.get(0)[4]).get("name"));
    }

    @Test
    public void roleAction_unknownP1Action_3002FromKeeper() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("sid", SID);
        f.put("uid", UID_A);
        f.put("action", "explode");
        assertEquals(3002, post("/v1/vault/role-action", "vaultRoleAction", f).getInt("code"));
    }

    // ---------- /account-action op recovery-settings（契约 §二.6） ----------

    @Test
    public void recoverySettings_saves_persists_notifiesAndEchoes() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("action", "recovery-settings");
        f.put("enabled", true);
        f.put("initialDelayMinutes", 45);
        f.put("retryIntervalSeconds", 10);
        f.put("maxAttempts", 5);
        f.put("cooldownMinutes", 60);
        JSONObject data = post("/v1/vault/account-action", "vaultAccountAction", f)
                .getJSONObject("data");
        assertTrue(data.getBoolean("accepted"));
        assertEquals("顶号恢复设置已保存并立即生效", data.getString("message"));
        JSONObject rec = data.getJSONObject("recovery");
        assertEquals(true, rec.getBoolean("enabled"));
        assertEquals(45, rec.getInt("initialDelayMinutes"));
        assertEquals(10, rec.getInt("retryIntervalSeconds"));
        assertEquals(5, rec.getInt("maxAttempts"));
        assertEquals(60, rec.getInt("cooldownMinutes"));
        // 持久化（分钟 → 秒）
        assertEquals(45 * 60, store.find(FPID).takeoverInitialDelaySeconds);
        assertEquals(10, store.find(FPID).takeoverRetryIntervalSeconds);
        assertEquals(5, store.find(FPID).takeoverMaxAttempts);
        assertEquals(60, store.find(FPID).takeoverCooldownMinutes);
        assertTrue("通知 keeper 重算暂停恢复", keeper.recoverySettingsNotified);

        // 重启加载不丢
        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        assertEquals(45 * 60, reloaded.find(FPID).takeoverInitialDelaySeconds);
    }

    @Test
    public void recoverySettings_validationErrors_contractMessages() throws Exception {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("fpid", FPID);
        base.put("action", "recovery-settings");
        base.put("enabled", true);

        Map<String, Object> f1 = new LinkedHashMap<>(base);
        f1.put("initialDelayMinutes", 0);
        JSONObject r1 = post("/v1/vault/account-action", "vaultAccountAction", f1);
        assertEquals(3002, r1.getInt("code"));
        assertEquals("顶号后等待必须在 1 到 1440 分钟之间", r1.getString("message"));

        Map<String, Object> f2 = new LinkedHashMap<>(base);
        f2.put("retryIntervalSeconds", 301);
        assertEquals("重试间隔秒数必须在 1 到 300 之间",
                post("/v1/vault/account-action", "vaultAccountAction", f2).getString("message"));

        Map<String, Object> f3 = new LinkedHashMap<>(base);
        f3.put("maxAttempts", 11);
        assertEquals("连续尝试次数必须在 0 到 10 之间",
                post("/v1/vault/account-action", "vaultAccountAction", f3).getString("message"));

        Map<String, Object> f4 = new LinkedHashMap<>(base);
        f4.put("cooldownMinutes", 20);
        assertEquals("冷却时长只支持 15 分钟、30 分钟、1 小时、2 小时或 24 小时",
                post("/v1/vault/account-action", "vaultAccountAction", f4).getString("message"));

        // 校验失败不落库
        assertTrue(store.find(FPID).takeoverRecoveryEnabled);
    }

    @Test
    public void recoverySettings_defaultsAppliedWhenOmitted_unknownAccount1004() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fpid", FPID);
        f.put("action", "recovery-settings");
        JSONObject rsp = post("/v1/vault/account-action", "vaultAccountAction", f);
        assertEquals(0, rsp.getInt("code"));
        assertEquals(30, rsp.getJSONObject("data").getJSONObject("recovery")
                .getInt("initialDelayMinutes"));

        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("fpid", "nope");
        f2.put("action", "recovery-settings");
        keeper.recoverySettingsNotified = false;   // 排除前一步成功保存的影响
        assertEquals(1004, post("/v1/vault/account-action", "vaultAccountAction", f2).getInt("code"));
        assertFalse("未知账号不触发回调", keeper.recoverySettingsNotified);
    }
}
