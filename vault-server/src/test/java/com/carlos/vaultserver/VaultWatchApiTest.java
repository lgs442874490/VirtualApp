package com.carlos.vaultserver;

import com.carlos.vaultserver.SessionControl.RoleOpResult;
import com.carlos.vaultserver.SessionControl.RoleRuntime;
import com.carlos.vaultserver.SessionControl.SessionStatus;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 战争通知 API 契约（2026-10-22，HTTP 全链路）：
 * /v1/vault/watch（list/add/update/remove/config/test）与 /v1/vault/events（游标同步）。
 */
public class VaultWatchApiTest {

    private static final String SECRET = "watch-test-secret";
    private static final String FPID = "48460403";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private VaultHttpApi legacyApi;
    private VaultStore store;
    private WatchEventStore events;
    private WarNotify war;
    private String base;
    private String legacyBase;

    /** 无会话假 keeper（本契约不涉及会话控制面）。 */
    static final class NoopKeeper implements SessionControl {
        @Override public void reconcile() { }
        @Override public boolean ensureSession(String fpid) { return true; }
        @Override public List<SessionStatus> status() { return new ArrayList<>(); }
        @Override public boolean yield(String fpid, long probeMillis) { return true; }
        @Override public RoleRuntime roleRuntime(String fpid, String sid, String uid) {
            return RoleRuntime.offline();
        }
        @Override public RoleOpResult roleAction(String fpid, String sid, String uid, String action) {
            return RoleOpResult.ok("RUNNING");
        }
    }

    @Before
    public void setUp() throws Exception {
        Path dataDir = tmp.getRoot().toPath();
        store = new VaultStore(dataDir);
        store.load();
        JSONObject a = new JSONObject();
        a.put("fpid", FPID);
        JSONArray accounts = new JSONArray().put(a);
        List<VaultStore.Account> incoming = new ArrayList<>();
        VaultStore.Account acc = new VaultStore.Account(FPID);
        acc.token = "tk";
        incoming.add(acc);
        store.replaceAll(incoming);

        events = new WatchEventStore(dataDir);
        events.load();
        war = new WarNotify(store, events, new WeComNotifier(), false);

        VaultConfig config = new VaultConfig("127.0.0.1:0", dataDir, SECRET, null, "human_first");
        api = new VaultHttpApi(config, store, new NoopKeeper(), new StateStore(dataDir), null, war);
        api.start();
        base = "http://127.0.0.1:" + api.server().getAddress().getPort();

        // 旧构造（无 war）：watch/events 端点应返回 3002
        VaultHttpApi legacy = new VaultHttpApi(config, store, new NoopKeeper(), new StateStore(dataDir));
        legacyApi = legacy;
        legacy.start();
        legacyBase = "http://127.0.0.1:" + legacy.server().getAddress().getPort();
    }

    @After
    public void tearDown() {
        api.stop();
        legacyApi.stop();
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

    private JSONObject postLegacy(String path, String op, Map<String, Object> fields) throws Exception {
        Map<String, Object> f = new LinkedHashMap<>(fields);
        f.put("_legacy", "1");
        String body = ApiSign.buildSignedBody(SECRET, op, f);
        HttpURLConnection conn = (HttpURLConnection) new URL(legacyBase + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = conn.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------------- 监视名单 CRUD ----------------

    @Test
    public void watch_addListUpdateRemove() throws Exception {
        // add
        JSONObject r = post("/v1/vault/watch", "vaultWatch",
                fields("action", "add", "fpid", FPID, "uid", "57347368",
                        "name", "仇人A", "kid", "888", "note", "宿敌"));
        assertEquals(0, r.getInt("code"));
        JSONArray targets = r.getJSONObject("data").getJSONArray("targets");
        assertEquals(1, targets.length());
        assertEquals("57347368", targets.getJSONObject(0).getString("uid"));
        assertTrue(targets.getJSONObject(0).getBoolean("enabled"));

        // 重复 add → 3002
        assertEquals(3002, post("/v1/vault/watch", "vaultWatch",
                fields("action", "add", "fpid", FPID, "uid", "57347368")).getInt("code"));
        // 非法 uid → 3002
        assertEquals(3002, post("/v1/vault/watch", "vaultWatch",
                fields("action", "add", "fpid", FPID, "uid", "abc")).getInt("code"));
        // 账号不存在 → 1004
        assertEquals(1004, post("/v1/vault/watch", "vaultWatch",
                fields("action", "add", "fpid", "no-such", "uid", "1")).getInt("code"));

        // update：停用 + 改备注
        r = post("/v1/vault/watch", "vaultWatch",
                fields("action", "update", "fpid", FPID, "uid", "57347368",
                        "note", "重点盯防", "enabled", false));
        assertEquals(0, r.getInt("code"));
        assertEquals(false, r.getJSONObject("data").getJSONArray("targets")
                .getJSONObject(0).getBoolean("enabled"));
        assertEquals("重点盯防", r.getJSONObject("data").getJSONArray("targets")
                .getJSONObject(0).getString("note"));

        // remove
        r = post("/v1/vault/watch", "vaultWatch",
                fields("action", "remove", "fpid", FPID, "uid", "57347368"));
        assertEquals(0, r.getInt("code"));
        assertEquals(0, r.getJSONObject("data").getJSONArray("targets").length());
        // 再删 → 3002
        assertEquals(3002, post("/v1/vault/watch", "vaultWatch",
                fields("action", "remove", "fpid", FPID, "uid", "57347368")).getInt("code"));
    }

    @Test
    public void watch_configValidationAndEcho() throws Exception {
        // 非法 webhook → 3002 中文文案
        JSONObject r = post("/v1/vault/watch", "vaultWatch",
                fields("action", "config", "enabled", true, "wecomEnabled", true,
                        "webhookUrl", "https://evil.example/hook", "cooldownSeconds", 300,
                        "quietStart", -1, "quietEnd", 8));
        assertEquals(3002, r.getInt("code"));
        assertTrue(r.getString("message").contains("qyapi"));

        // 冷却越界 → 3002
        assertEquals(3002, post("/v1/vault/watch", "vaultWatch",
                fields("action", "config", "cooldownSeconds", 10)).getInt("code"));

        // 合法保存 → 回显
        r = post("/v1/vault/watch", "vaultWatch",
                fields("action", "config", "enabled", true, "wecomEnabled", true,
                        "webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k",
                        "cooldownSeconds", 120, "quietStart", 23, "quietEnd", 7));
        assertEquals(0, r.getInt("code"));
        JSONObject cfg = r.getJSONObject("data").getJSONObject("config");
        assertEquals(true, cfg.getBoolean("enabled"));
        assertEquals(120, cfg.getInt("cooldownSeconds"));
        assertEquals(23, cfg.getInt("quietStart"));

        // list 带 config 回显（跨请求持久化）
        r = post("/v1/vault/watch", "vaultWatch", fields("action", "list", "fpid", FPID));
        assertEquals(0, r.getInt("code"));
        assertEquals(120, r.getJSONObject("data").getJSONObject("config").getInt("cooldownSeconds"));

        // 未知 action / 缺 fpid
        assertEquals(3002, post("/v1/vault/watch", "vaultWatch",
                fields("action", "wat", "fpid", FPID)).getInt("code"));
        assertEquals(3002, post("/v1/vault/watch", "vaultWatch",
                fields("action", "list")).getInt("code"));
    }

    @Test
    public void watch_legacyConstructorReturnsNotEnabled() throws Exception {
        JSONObject r = postLegacy("/v1/vault/watch", "vaultWatch",
                fields("action", "list", "fpid", FPID));
        assertEquals(3002, r.getInt("code"));
        JSONObject r2 = postLegacy("/v1/vault/events", "vaultEvents", fields("cursor", 0));
        assertEquals(3002, r2.getInt("code"));
    }

    // ---------------- 事件游标同步 ----------------

    @Test
    public void events_cursorFlow() throws Exception {
        store.updateWatchConfig(true, false, "", 300, -1, 8);   // 关企微，纯事件流
        store.addWatchTarget(FPID, "57347368", "仇人A", "888", "");
        long nowS = System.currentTimeMillis() / 1000L;
        war.onPush(FPID, 888L, "notify_player_march_add", march(201, 57347368L, 2, nowS));
        war.onPush(FPID, 888L, "notify_player_march_add", march(202, 57347368L, 1, nowS));
        war.onPush(FPID, 888L, "notify_mass_march_add", march(203, 57347368L, 501, nowS));

        // 首拉 cursor=0：3 条，最旧在前；ackCursor=最新 seq
        JSONObject r = post("/v1/vault/events", "vaultEvents", fields("cursor", 0));
        assertEquals(0, r.getInt("code"));
        JSONObject data = r.getJSONObject("data");
        JSONArray evs = data.getJSONArray("events");
        assertEquals(3, evs.length());
        assertEquals("进攻", evs.getJSONObject(0).getString("action"));
        assertEquals("侦查", evs.getJSONObject(1).getString("action"));
        assertEquals("集结", evs.getJSONObject(2).getString("action"));
        assertTrue(evs.getJSONObject(0).optLong("seq") < evs.getJSONObject(1).optLong("seq"));
        long latest = data.getLong("cursor");
        assertEquals(latest, data.getLong("ackCursor"));

        // 游标推进后：空增量
        r = post("/v1/vault/events", "vaultEvents", fields("cursor", latest));
        assertEquals(0, r.getJSONObject("data").getJSONArray("events").length());

        // 新事件 → cursor 之后增量 1 条
        war.onPush(FPID, 888L, "notify_player_march_del", delOf(201));
        r = post("/v1/vault/events", "vaultEvents", fields("cursor", latest));
        JSONArray inc = r.getJSONObject("data").getJSONArray("events");
        assertEquals(1, inc.length());
        assertEquals("撤回", inc.getJSONObject(0).getString("action"));
        assertTrue(r.getJSONObject("data").getLong("cursor") > latest);

        // 非法 cursor 按缺省 0
        r = post("/v1/vault/events", "vaultEvents", fields("cursor", "junk"));
        assertEquals(4, r.getJSONObject("data").getJSONArray("events").length());
    }

    private static Map<String, Object> march(long id, long owner, long type, long endSec) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", id);
        march.put("owner", owner);
        march.put("type", type);
        march.put("starttime", endSec - 100);
        march.put("endtime", endSec + 300);
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("x", 11);
        ep.put("y", 22);
        march.put("endpoint", ep);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", march);
        return content;
    }

    private static Map<String, Object> delOf(long id) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("type", 2);
        d.put("kid", 888);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", d);
        return content;
    }
}
