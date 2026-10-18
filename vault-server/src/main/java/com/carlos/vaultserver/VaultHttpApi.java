package com.carlos.vaultserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP API（JDK HttpServer，线程池执行）。
 *
 * <pre>
 * POST /v1/vault/sync    全量身份库同步（accountsJson 字符串字段）
 * POST /v1/vault/token   单账号 token 更新（NEED_TOKEN 补给通道）
 * POST /v1/vault/status  会话状态查询
 * POST /v1/vault/policy  顶号策略设置 / 让位指令
 * GET  /healthz          探活
 * </pre>
 *
 * 信封 {code, message, data, serverTime}；业务错误 HTTP 200 + 信封 code（对齐 license-server）。
 * 日志纪律：不落 token 明文，只落 fpid/label/条数。
 */
public final class VaultHttpApi {

    public static final int CODE_ACCOUNT_NOT_FOUND = 1004;

    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final VaultConfig config;
    private final VaultStore store;
    private final SessionControl keeper;
    private final StateStore stateStore;
    private final com.carlos.vaultserver.task.RoundScheduler roundScheduler;
    private final ApiSign.NonceCache nonceCache = new ApiSign.NonceCache();
    private HttpServer server;

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper, StateStore stateStore) {
        this(config, store, keeper, stateStore, null);
    }

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler) {
        this.config = config;
        this.store = store;
        this.keeper = keeper;
        this.stateStore = stateStore;
        this.roundScheduler = roundScheduler;
    }

    public void start() throws IOException {
        String[] hp = config.listen.split(":");
        server = HttpServer.create(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 0);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/v1/vault/sync", ex -> guard(ex, "POST", "vaultSync", this::handleSync));
        server.createContext("/v1/vault/token", ex -> guard(ex, "POST", "vaultToken", this::handleToken));
        server.createContext("/v1/vault/status", ex -> guard(ex, "POST", "vaultStatus", this::handleStatus));
        server.createContext("/v1/vault/state", ex -> guard(ex, "POST", "vaultState", this::handleState));
        server.createContext("/v1/vault/policy", ex -> guard(ex, "POST", "vaultPolicy", this::handlePolicy));
        server.createContext("/v1/vault/automation", ex -> guard(ex, "POST", "vaultAutomation", this::handleAutomation));
        server.createContext("/healthz", ex -> {
            writeJson(ex, 200, envelope(0, "ok", new JSONObject()));
        });
        server.createContext("/", ex -> writeJson(ex, 404, envelope(3002, "not found", null)));
        server.start();
        FileLog.info("HTTP API 已启动：{}", config.listen);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    /** 测试用：实际监听地址（端口 0 时取分配结果）。 */
    com.sun.net.httpserver.HttpServer server() {
        return server;
    }

    // ---------- 处理器骨架 ----------

    private interface Handler {
        JSONObject handle(Map<String, Object> fields) throws Exception;
    }

    private void guard(HttpExchange ex, String method, String op, Handler handler) {
        try {
            if (!method.equals(ex.getRequestMethod())) {
                writeJson(ex, 200, envelope(3002, "method not allowed", null));
                return;
            }
            String rawBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (rawBody.length() > MAX_BODY_BYTES) {
                writeJson(ex, 200, envelope(3003, "body too large", null));
                return;
            }
            ApiSign.Verified v = ApiSign.verify(config.secret, method, op, rawBody,
                    config.signWindowMillis, nonceCache);
            if (v.code != ApiSign.CODE_OK) {
                FileLog.warn("验签失败 {} op={} code={}", ex.getRequestURI().getPath(), op, String.valueOf(v.code));
                writeJson(ex, 200, envelope(v.code, messageFor(v.code), null));
                return;
            }
            JSONObject data = handler.handle(v.fields);
            writeJson(ex, 200, envelope(0, "ok", data));
        } catch (BizError e) {
            tryWriteJson(ex, envelope(e.code, e.getMessage(), null));
        } catch (Throwable t) {
            FileLog.error("处理异常 {}：{}", ex.getRequestURI().getPath(), String.valueOf(t));
            tryWriteJson(ex, envelope(3001, "server error: " + t.getMessage(), null));
        }
    }

    private static void tryWriteJson(HttpExchange ex, JSONObject body) {
        try {
            writeJson(ex, 200, body);
        } catch (IOException ignored) {
        }
    }

    /** 业务错误（信封 code + 中文消息），如 1004 账号不存在。 */
    private static final class BizError extends RuntimeException {
        final int code;

        BizError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---------- 业务端点 ----------

    private JSONObject handleSync(Map<String, Object> fields) throws Exception {
        String accountsJson = str(fields, "accountsJson");
        if (accountsJson == null || accountsJson.isEmpty()) {
            throw new IllegalArgumentException("缺少 accountsJson");
        }
        JSONArray arr = new JSONArray(accountsJson);
        List<VaultStore.Account> incoming = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String fpid = o.optString("fpid", "");
            if (fpid.isEmpty()) continue;
            VaultStore.Account a = new VaultStore.Account(fpid);
            a.token = o.optString("token", "");
            a.label = o.optString("label", "");
            a.group = o.optString("group", "");
            a.keep = o.optBoolean("keep", false);
            String sid = o.optString("activeSid", "");
            a.activeSid = sid.isEmpty() ? null : sid;
            String id = o.optString("identity", "");
            a.identity = id.isEmpty() ? null : id;
            a.proxy = o.optString("proxy", "");
            if (a.proxy.isEmpty()) a.proxy = null;
            a.auto = o.optBoolean("auto", false);
            a.windowStart = o.optInt("windowStart", 5);
            a.windowEnd = o.optInt("windowEnd", 9);
            JSONArray roles = o.optJSONArray("roles");
            if (roles != null) {
                for (int j = 0; j < roles.length(); j++) {
                    JSONObject r = roles.getJSONObject(j);
                    String rsid = r.optString("sid", "");
                    String rid = r.optString("identity", "");
                    if (!rsid.isEmpty() && !rid.isEmpty()) a.roleIdentity.put(rsid, rid);
                    JSONObject cfg = r.optJSONObject("config");
                    if (rsid.isEmpty() || cfg == null) continue;
                    java.util.Map<String, Boolean> cfgMap = new java.util.LinkedHashMap<>();
                    for (String fn : cfg.keySet()) {
                        cfgMap.put(fn, cfg.optBoolean(fn, false));
                    }
                    a.roleConfig.put(rsid, cfgMap);
                    JSONObject prm = r.optJSONObject("params");
                    if (prm != null) {
                        java.util.Map<String, String> prmMap = new java.util.LinkedHashMap<>();
                        for (String fn : prm.keySet()) {
                            JSONObject p = prm.optJSONObject(fn);
                            if (p != null && p.length() > 0) prmMap.put(fn, p.toString());
                        }
                        a.roleParams.put(rsid, prmMap);
                    }
                }
            }
            incoming.add(a);
        }
        int n = store.replaceAll(incoming);
        keeper.reconcile();
        FileLog.info("身份库同步：{} 条（keep={}）", String.valueOf(n),
                String.valueOf(incoming.stream().filter(a -> a.keep).count()));
        JSONObject data = new JSONObject();
        data.put("accepted", n);
        return data;
    }

    private JSONObject handleToken(Map<String, Object> fields) throws Exception {
        String fpid = str(fields, "fpid");
        String token = str(fields, "token");
        if (fpid == null || fpid.isEmpty() || token == null || token.isEmpty()) {
            throw new IllegalArgumentException("缺少 fpid/token");
        }
        if (!store.updateToken(fpid, token)) {
            throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
        }
        keeper.reconcile();
        FileLog.info("token 更新：fpid={}", fpid);
        JSONObject data = new JSONObject();
        data.put("fpid", fpid);
        data.put("updated", true);
        return data;
    }

    private JSONObject handleStatus(Map<String, Object> fields) {
        JSONObject data = new JSONObject();
        data.put("accounts", statusAccountsJson(str(fields, "fpid")));
        return data;
    }

    /** 采集状态拉取：一并返回会话状态（App 一次往返完成状态缓存 + daily_state 回写）。 */
    private JSONObject handleState(Map<String, Object> fields) {
        String wantFpid = str(fields, "fpid");
        JSONObject data = new JSONObject();
        data.put("accounts", statusAccountsJson(wantFpid));
        JSONObject states = new JSONObject();
        for (Map.Entry<String, Object> e : stateStore.snapshot().entrySet()) {
            if (wantFpid != null && !wantFpid.isEmpty()
                    && !e.getKey().contains(":" + wantFpid + ":")) continue;
            states.put(e.getKey(), e.getValue());
        }
        data.put("states", states);
        return data;
    }

    private JSONArray statusAccountsJson(String wantFpid) {
        List<SessionControl.SessionStatus> list = keeper.status();
        JSONArray arr = new JSONArray();
        for (SessionControl.SessionStatus s : list) {
            if (wantFpid != null && !wantFpid.isEmpty() && !wantFpid.equals(s.fpid)) continue;
            JSONObject o = new JSONObject();
            o.put("fpid", s.fpid);
            o.put("label", s.label);
            o.put("group", s.group);
            o.put("state", s.state);
            o.put("lastFailure", s.lastFailure == null ? "" : s.lastFailure);
            o.put("sid", s.sid == null ? "" : s.sid);
            o.put("hasToken", s.hasToken);
            o.put("roleName", s.roleName == null ? "" : s.roleName);
            o.put("roleLevel", s.roleLevel);
            o.put("rolePower", s.rolePower);
            o.put("roleAlliance", s.roleAlliance == null ? "" : s.roleAlliance);
            o.put("sinceTs", s.sinceTs);
            arr.put(o);
        }
        return arr;
    }

    private JSONObject handlePolicy(Map<String, Object> fields) throws Exception {
        String action = str(fields, "action");
        if ("set".equals(action)) {
            String policy = str(fields, "policy");
            if (!store.setPolicy(policy)) {
                throw new IllegalArgumentException("非法策略: " + policy);
            }
            FileLog.info("顶号策略已设为 {}", policy);
            JSONObject data = new JSONObject();
            data.put("policy", policy);
            return data;
        }
        if ("yield".equals(action)) {
            String fpid = str(fields, "fpid");
            long minutes = 30;
            Object m = fields.get("minutes");
            if (m instanceof Number) minutes = ((Number) m).longValue();
            if (fpid == null || fpid.isEmpty()) throw new IllegalArgumentException("缺少 fpid");
            boolean ok = keeper.yield(fpid, Math.max(1, minutes) * 60_000L);
            if (!ok) throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
            JSONObject data = new JSONObject();
            data.put("fpid", fpid);
            data.put("yieldMinutes", minutes);
            return data;
        }
        if ("run".equals(action)) {
            String fpid = str(fields, "fpid");
            String fn = str(fields, "fn");
            if (fpid == null || fpid.isEmpty()) throw new IllegalArgumentException("缺少 fpid");
            if (roundScheduler == null) throw new BizError(3002, "整轮调度器未启用");
            boolean accepted = roundScheduler.requestRun(fpid, fn == null ? "" : fn);
            if (!accepted) throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
            FileLog.info("整轮手动触发 fpid={} fn={}", fpid, String.valueOf(fn));
            JSONObject data = new JSONObject();
            data.put("fpid", fpid);
            data.put("accepted", true);
            return data;
        }
        throw new IllegalArgumentException("未知 action: " + action);
    }

    /**
     * 账号自动化：read 返回服务器侧配置快照；run 打开统一开关、确保会话并排队整轮/单任务。
     * 这是账号页“登录”按钮的服务端入口（App 不再本地执行任务）。
     */
    private JSONObject handleAutomation(Map<String, Object> fields) throws Exception {
        String action = str(fields, "action");
        String fpid = str(fields, "fpid");
        if (fpid == null || fpid.isEmpty()) throw new IllegalArgumentException("缺少 fpid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
        if ("read".equals(action)) {
            return automationSnapshot(acc);
        }
        if ("run".equals(action)) {
            if (acc.token == null || acc.token.isEmpty()) {
                throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号没有 token: " + fpid);
            }
            if (roundScheduler == null) throw new BizError(3002, "整轮调度器未启用");
            String fn = str(fields, "fn");
            store.setAutomation(fpid, true);
            keeper.reconcile();
            boolean hasSession = keeper.ensureSession(fpid);
            boolean queued = roundScheduler.requestRun(fpid, fn == null ? "" : fn);
            acc = store.find(fpid);
            JSONObject data = automationSnapshot(acc);
            data.put("accepted", hasSession && queued);
            data.put("queued", queued);
            data.put("fn", fn == null ? "" : fn);
            FileLog.info("自动化登录触发 fpid={} fn={} session={} queued={}",
                    fpid, String.valueOf(fn), hasSession, queued);
            return data;
        }
        throw new IllegalArgumentException("未知 action: " + action);
    }

    private JSONObject automationSnapshot(VaultStore.Account acc) {
        JSONObject data = new JSONObject();
        try {
            String sid = acc.activeSid == null ? "" : acc.activeSid;
            data.put("fpid", acc.fpid);
            data.put("sid", sid);
            data.put("auto", acc.auto);
            data.put("keep", acc.keep);
            data.put("windowStart", acc.windowStart);
            data.put("windowEnd", acc.windowEnd);
            data.put("hasToken", acc.token != null && !acc.token.isEmpty());
            data.put("state", sessionStateOf(acc.fpid));
            Object last = stateStore.snapshot().get("srv:lastrun:" + acc.fpid);
            data.put("lastRun", last instanceof Number ? ((Number) last).longValue() : 0L);
            Map<String, Boolean> cfg = acc.roleConfig.get(sid);
            JSONObject config = new JSONObject();
            if (cfg != null) {
                for (Map.Entry<String, Boolean> e : cfg.entrySet()) {
                    config.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
                }
            }
            data.put("config", config);
            Map<String, String> prm = acc.roleParams.get(sid);
            JSONObject params = new JSONObject();
            if (prm != null) {
                for (Map.Entry<String, String> e : prm.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        params.put(e.getKey(), e.getValue());
                    }
                }
            }
            data.put("params", params);
        } catch (Throwable t) {
            FileLog.warn("自动化快照组装失败 fpid={}：{}", acc.fpid, String.valueOf(t));
        }
        return data;
    }

    private String sessionStateOf(String fpid) {
        try {
            for (SessionControl.SessionStatus s : keeper.status()) {
                if (fpid.equals(s.fpid)) return s.state == null ? "offline" : s.state;
            }
        } catch (Throwable ignored) {
        }
        return "offline";
    }

    // ---------- 工具 ----------

    private static String str(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return "null".equals(s) ? null : s;
    }

    private static String messageFor(int code) {
        switch (code) {
            case ApiSign.CODE_BAD_SIGNATURE: return "签名校验失败";
            case ApiSign.CODE_TIMESTAMP_SKEW: return "时间戳超出窗口";
            case ApiSign.CODE_NONCE_REPLAY: return "nonce 重放";
            case ApiSign.CODE_BAD_REQUEST: return "请求格式错误";
            default: return "错误码 " + code;
        }
    }

    private static JSONObject envelope(int code, String message, JSONObject data) {
        JSONObject o = new JSONObject();
        o.put("code", code);
        o.put("message", message);
        o.put("data", data == null ? new JSONObject() : data);
        o.put("serverTime", System.currentTimeMillis());
        return o;
    }

    private static void writeJson(HttpExchange ex, int httpStatus, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(httpStatus, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
