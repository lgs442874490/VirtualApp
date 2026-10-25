package com.carlos.vaultserver.monitor;

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

import com.carlos.vaultserver.ApiSign;
import com.carlos.vaultserver.SessionControl;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultConfig;
import com.carlos.vaultserver.VaultHttpApi;
import com.carlos.vaultserver.VaultStore;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/vault/monitor} 路由端到端（契约 §3.3）：真实 HttpServer（端口 0）+ ApiSign 签名请求。
 *
 * <p>覆盖：未安装 MonitorStore 时 query/mark/status 全部返回 {@code enabled:false} 且不抛异常；
 * 安装（{@code VAULT_MONITOR=on}）后 mark → query(since) 游标递增、kinds/fpid 过滤、
 * includeData=0 剥离 data/args、limit 生效；错误 action 返回结构化错误、非 POST 走 405 信封。
 *
 * <p>防串测：{@link MonitorStore#shutdown()} 在 @Before/@After 都调用（MonitorStore 是进程级单例，
 * 与同 JVM 其它测试类隔离）；HTTP server 在 @After 停掉。
 */
public class MonitorApiTest {

    private static final String SECRET = "monitor-api-test-secret";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultHttpApi api;
    private Path dataDir;
    private String base;

    /** 假会话管理器（只测 HTTP 路由，不连游戏服；与 VaultHttpApiTest.FakeKeeper 同口径）。 */
    static final class FakeKeeper implements SessionControl {
        @Override
        public void reconcile() {
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }

        @Override
        public List<SessionStatus> status() {
            return new ArrayList<>();
        }

        @Override
        public boolean yield(String fpid, long probeMillis) {
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        MonitorStore.shutdown();   // 进程级单例：保证每个用例从「未安装」态起步
        dataDir = tmp.getRoot().toPath();
        VaultStore store = new VaultStore(dataDir);
        store.load();
        StateStore stateStore = new StateStore(dataDir);
        stateStore.load();
        VaultConfig config = newConfig("127.0.0.1:0", dataDir);
        api = new VaultHttpApi(config, store, new FakeKeeper(), stateStore);
        api.start();
        base = "http://127.0.0.1:" + portOf(api);
    }

    @After
    public void tearDown() {
        if (api != null) api.stop();
        MonitorStore.shutdown();
    }

    // ---------- 未安装 / 未启用 ----------

    @Test
    public void query_whenMonitorNotInstalled_returnsDisabledEmpty() throws Exception {
        JSONObject rsp = post("/v1/vault/monitor", "vaultMonitor", new LinkedHashMap<>());
        assertEquals(0, rsp.getInt("code"));
        JSONObject data = rsp.getJSONObject("data");
        assertFalse(data.getBoolean("enabled"));
        assertEquals(0, data.getJSONArray("events").length());
        assertEquals(0, data.getInt("count"));
        assertEquals(0L, data.getLong("lastSeq"));
        assertTrue(data.getLong("serverTime") > 0);
    }

    @Test
    public void mark_andStatus_whenMonitorNotInstalled_returnDisabled_notCrash() throws Exception {
        Map<String, Object> mf = new LinkedHashMap<>();
        mf.put("action", "mark");
        mf.put("msg", "原生游戏领取前");
        JSONObject mark = post("/v1/vault/monitor", "vaultMonitor", mf);
        assertEquals(0, mark.getInt("code"));
        assertFalse(mark.getJSONObject("data").getBoolean("enabled"));
        assertEquals(0L, mark.getJSONObject("data").getLong("seq"));

        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("action", "status");
        JSONObject status = post("/v1/vault/monitor", "vaultMonitor", sf);
        assertEquals(0, status.getInt("code"));
        assertFalse(status.getJSONObject("data").getBoolean("enabled"));
    }

    // ---------- 已启用：mark / query 游标 / 过滤 / 轻量 ----------

    @Test
    public void mark_thenQuery_eventsCursorKindsFpidAndIncludeData() throws Exception {
        MonitorStore.install(dataDir, Map.of("VAULT_MONITOR", "on"));

        // 用户打点两次：seq 单调递增
        long seq1 = mark("用户打点：原生游戏领取前");
        long seq2 = mark("用户打点：原生游戏领取后");
        assertTrue("seq 应递增: " + seq1 + " / " + seq2, seq2 > seq1);

        // query(since=0) 拿到两条 mark；lastSeq 至少到 seq2
        JSONObject all = query(Map.of("since", 0L));
        assertTrue(all.getBoolean("enabled"));
        JSONArray events = all.getJSONArray("events");
        assertEquals(2, events.length());
        assertEquals(2, all.getInt("count"));
        assertEquals("mark", events.getJSONObject(0).getString("kind"));
        assertTrue(events.getJSONObject(0).getString("msg").contains("领取前"));
        assertTrue(events.getJSONObject(1).getLong("seq") > events.getJSONObject(0).getLong("seq"));
        assertTrue(all.getLong("lastSeq") >= seq2);
        assertEquals(2, all.getInt("mem"));

        // 增量游标：since=seq1 只回 seq > seq1 的事件
        JSONObject incremental = query(Map.of("since", seq1));
        assertEquals(1, incremental.getInt("count"));
        assertEquals(seq2, incremental.getJSONArray("events").getJSONObject(0).getLong("seq"));

        // 再拉一次到游标位：无新事件
        long cursor = incremental.getLong("lastSeq");
        assertEquals(0, query(Map.of("since", cursor)).getInt("count"));

        // 注入 task/notify 事件（模拟 TaskTrace 落库内容）后测 kinds/fpid/includeData/limit
        JSONObject taskEvent = new JSONObject();
        taskEvent.put("kind", "task.end");
        taskEvent.put("fpid", "48460403");
        taskEvent.put("sid", "888");
        taskEvent.put("fn", "login_rewards");
        taskEvent.put("data", new JSONObject().put("rewards", new JSONArray().put("钻石×30")));
        taskEvent.put("args", new JSONObject().put("k", "v"));
        assertTrue(MonitorStore.get().record(taskEvent) > 0L);
        JSONObject notifyEvent = new JSONObject();
        notifyEvent.put("kind", "notify");
        notifyEvent.put("fpid", "80971192");
        notifyEvent.put("proto", "notify_daily_claim_info");
        assertTrue(MonitorStore.get().record(notifyEvent) > 0L);

        // kinds 过滤
        JSONObject kinds = query(Map.of("since", 0L, "kinds", "task.end"));
        assertEquals(1, kinds.getInt("count"));
        assertEquals("task.end", kinds.getJSONArray("events").getJSONObject(0).getString("kind"));

        // fpid 过滤
        JSONObject byFpid = query(Map.of("since", 0L, "fpid", "80971192"));
        assertEquals(1, byFpid.getInt("count"));
        assertEquals("notify", byFpid.getJSONArray("events").getJSONObject(0).getString("kind"));

        // 默认 includeData（缺省/1）带 data/args
        JSONObject full = query(Map.of("since", 0L, "kinds", "task.end"));
        assertTrue(full.getJSONArray("events").getJSONObject(0).has("data"));
        assertTrue(full.getJSONArray("events").getJSONObject(0).has("args"));

        // includeData=0 剥离 data/args（列表页轻量）
        JSONObject light = query(Map.of("since", 0L, "kinds", "task.end", "includeData", "0"));
        assertEquals(1, light.getInt("count"));
        JSONObject lightEvent = light.getJSONArray("events").getJSONObject(0);
        assertFalse("includeData=0 不应带 data", lightEvent.has("data"));
        assertFalse("includeData=0 不应带 args", lightEvent.has("args"));
        assertEquals("login_rewards", lightEvent.getString("fn"));

        // limit 生效
        assertEquals(1, query(Map.of("since", 0L, "limit", 1)).getInt("count"));
    }

    @Test
    public void status_afterInstall_reportsEnabledAndCounts() throws Exception {
        MonitorStore.install(dataDir, Map.of("VAULT_MONITOR", "on"));
        long seq = mark("打点");
        Map<String, Object> sf = new LinkedHashMap<>();
        sf.put("action", "status");
        JSONObject rsp = post("/v1/vault/monitor", "vaultMonitor", sf);
        assertEquals(0, rsp.getInt("code"));
        JSONObject data = rsp.getJSONObject("data");
        assertTrue(data.getBoolean("enabled"));
        assertTrue(data.getLong("lastSeq") >= seq);
        assertTrue(data.getInt("mem") >= 1);
        assertTrue(data.getInt("memMax") >= 1);
    }

    // ---------- 错误输入不崩 ----------

    @Test
    public void unknownAction_returnsStructuredError() throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "bogus");
        JSONObject rsp = post("/v1/vault/monitor", "vaultMonitor", f);
        assertEquals(0, rsp.getInt("code"));   // 业务错误仍走 HTTP 200 + 信封
        JSONObject data = rsp.getJSONObject("data");
        assertEquals("unknown_action", data.getString("error"));
        assertFalse(data.getBoolean("enabled"));
    }

    @Test
    public void malformedFieldTypes_queryDoesNotCrash() throws Exception {
        MonitorStore.install(dataDir, Map.of("VAULT_MONITOR", "on"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("since", "not-a-number");
        f.put("limit", "-5");
        f.put("kinds", " , task.end ,");
        JSONObject rsp = post("/v1/vault/monitor", "vaultMonitor", f);
        assertEquals(0, rsp.getInt("code"));
        assertTrue(rsp.getJSONObject("data").getBoolean("enabled"));
    }

    @Test
    public void nonPostMethod_returnsMethodNotAllowedEnvelope_disabled() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/v1/vault/monitor").openConnection();
        conn.setRequestMethod("GET");
        String text;
        try (InputStream in = conn.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JSONObject rsp = new JSONObject(text);
        assertEquals(3002, rsp.getInt("code"));   // guard 的 method not allowed，不崩
    }

    // ---------- 工具 ----------

    private long mark(String msg) throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", "mark");
        f.put("msg", msg);
        JSONObject rsp = post("/v1/vault/monitor", "vaultMonitor", f);
        assertEquals(0, rsp.getInt("code"));
        JSONObject data = rsp.getJSONObject("data");
        assertTrue("mark 应启用: " + data, data.getBoolean("enabled"));
        long seq = data.getLong("seq");
        assertTrue("mark 应返回递增 seq: " + data, seq > 0);
        return seq;
    }

    private JSONObject query(Map<String, Object> extra) throws Exception {
        Map<String, Object> f = new LinkedHashMap<>(extra);
        f.put("action", "query");
        JSONObject rsp = post("/v1/vault/monitor", "vaultMonitor", f);
        assertEquals(0, rsp.getInt("code"));
        return rsp.getJSONObject("data");
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
        String text;
        try (InputStream in = conn.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new JSONObject(text);
    }

    /**
     * 构造 VaultConfig：其构造器是包级私有（常规测试都在 com.carlos.vaultserver 包内），
     * 本测试在 monitor 子包，用反射构造；只为拿到可注入的 127.0.0.1:0 监听地址。
     */
    private static VaultConfig newConfig(String listen, Path dataDir) throws Exception {
        Constructor<VaultConfig> c = VaultConfig.class.getDeclaredConstructor(
                String.class, Path.class, String.class, InetSocketAddress.class, String.class);
        c.setAccessible(true);
        return c.newInstance(listen, dataDir, SECRET, null, "human_first");
    }

    /**
     * 取实际监听端口：{@link VaultHttpApi#server()} 是包级私有（本测试在 monitor 子包），
     * 用反射读取（无名模块同 JVM，setAccessible 允许），避免「先探测空闲端口再绑定」的竞态。
     */
    private static int portOf(VaultHttpApi api) throws Exception {
        Method m = VaultHttpApi.class.getDeclaredMethod("server");
        m.setAccessible(true);
        HttpServer server = (HttpServer) m.invoke(api);
        assertNotNull(server);
        return server.getAddress().getPort();
    }
}
