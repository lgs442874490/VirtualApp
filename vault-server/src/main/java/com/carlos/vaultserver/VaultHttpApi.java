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
 * POST /v1/vault/token/get   单账号 token 只读回取（重装/清数据后恢复，2026-10-25）
 * POST /v1/vault/status  会话状态查询
 * POST /v1/vault/policy  顶号策略设置 / 让位指令
 * POST /v1/vault/accounts        账号池列表+聚合计数+每角色运行态（M1）
 * POST /v1/vault/role-action     单角色 login/logout/pause/resume/stop（M1）；
 *                                formation.catalog-refresh / memory-save / memory-restore / bear-binding（编队 v2）
 * POST /v1/vault/account-action  账号级 login-all/stop-all（M1）
 * POST /v1/vault/global-action   全局 login-all/stop-all（M1）
 * POST /v1/vault/watch   战争通知：监视名单增删改查/通知配置/测试通知（2026-10-22）
 * POST /v1/vault/events  战争通知事件游标同步（cursor 之后的新事件 + ackCursor）
 * POST /v1/vault/monitor 真机监控：任务/协议/会话事件游标拉取 + 用户打点（2026-10-25）
 * GET  /v1/vault/hotconfig      热更配置（dataDir/hotconfig.json，{base} 替换为 scheme://Host）
 * GET  /v1/vault/app-latest.apk 整包下载（dataDir/download/app-latest.apk 流式返回）
 * GET  /healthz          探活
 * </pre>
 *
 * 信封 {code, message, data, serverTime}；业务错误 HTTP 200 + 信封 code（对齐 license-server）。
 * 日志纪律：不落 token 明文，只落 fpid/label/条数。
 */
public final class VaultHttpApi {

    public static final int CODE_ACCOUNT_NOT_FOUND = 1004;
    /** 账号存在但服务器侧没有 token（从未补给/已被清空）：App 应提示重贴，而非当网络故障重试。 */
    public static final int CODE_TOKEN_MISSING = 1005;

    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final VaultConfig config;
    private final VaultStore store;
    private final SessionControl keeper;
    private final StateStore stateStore;
    private final com.carlos.vaultserver.task.RoundScheduler roundScheduler;
    /** 战争通知中枢（可 null：测试旧构造；watch/events 端点返回 3002）。 */
    private final WarNotify war;
    private final ApiSign.NonceCache nonceCache = new ApiSign.NonceCache();
    private HttpServer server;

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper, StateStore stateStore) {
        this(config, store, keeper, stateStore, null);
    }

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler) {
        this(config, store, keeper, stateStore, roundScheduler, null);
    }

    /** 战争通知接线构造（Main 用）：watch/events 端点可用。 */
    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler,
                        WarNotify war) {
        this.config = config;
        this.store = store;
        this.keeper = keeper;
        this.stateStore = stateStore;
        this.roundScheduler = roundScheduler;
        this.war = war;
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
        // 注意：HttpServer 按最长前缀匹配，"/v1/vault/token/get" 必须单独注册（否则会被
        // "/v1/vault/token" 吞掉按写通道处理）。读通道与写通道分 OP，验签互不串用。
        server.createContext("/v1/vault/token/get", ex -> guard(ex, "POST", "vaultTokenGet", this::handleTokenGet));
        server.createContext("/v1/vault/token", ex -> guard(ex, "POST", "vaultToken", this::handleToken));
        server.createContext("/v1/vault/status", ex -> guard(ex, "POST", "vaultStatus", this::handleStatus));
        server.createContext("/v1/vault/state", ex -> guard(ex, "POST", "vaultState", this::handleState));
        server.createContext("/v1/vault/policy", ex -> guard(ex, "POST", "vaultPolicy", this::handlePolicy));
        server.createContext("/v1/vault/automation", ex -> guard(ex, "POST", "vaultAutomation", this::handleAutomation));
        // 账号池控制面（M1 2026-10-21，契约 §3）：列表/角色动作/账号批量/全局批量
        server.createContext("/v1/vault/accounts", ex -> guard(ex, "POST", "vaultAccounts", this::handleAccounts));
        server.createContext("/v1/vault/role-action", ex -> guard(ex, "POST", "vaultRoleAction", this::handleRoleAction));
        server.createContext("/v1/vault/account-action", ex -> guard(ex, "POST", "vaultAccountAction", this::handleAccountAction));
        server.createContext("/v1/vault/global-action", ex -> guard(ex, "POST", "vaultGlobalAction", this::handleGlobalAction));
        // 战争通知（2026-10-22）：监视名单/通知配置/测试通知 + 事件游标同步
        server.createContext("/v1/vault/watch", ex -> guard(ex, "POST", "vaultWatch", this::handleWatch));
        server.createContext("/v1/vault/events", ex -> guard(ex, "POST", "vaultEvents", this::handleEvents));
        // 真机监控（2026-10-25）：后台任务级协议监控事件游标拉取 + 用户打点；默认 off（VAULT_MONITOR=on 才开）
        server.createContext("/v1/vault/monitor", ex -> guard(ex, "POST", "vaultMonitor", this::handleMonitor));
        // 热更新自托管（2026-10-25）：App 默认更新源，无签名；详见 HotConfigRoutes
        server.createContext("/v1/vault/hotconfig",
                ex -> HotConfigRoutes.serveHotConfig(ex, config.dataDir));
        server.createContext("/v1/vault/app-latest.apk",
                ex -> HotConfigRoutes.serveApk(ex, config.dataDir));
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
            // 同服多角色：App 下发的当前角色 uid（旧客户端无此字段 → null = 旧行为）
            String auid = o.optString("activeUid", "");
            a.activeUid = auid.isEmpty() ? null : auid;
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
                    // 账号池角色种子（M1）：带 uid 的角色进池（name/level/power/enabled 可选，旧客户端不带 = 缺省）
                    String ruid = r.optString("uid", "");
                    if (!rsid.isEmpty() && !ruid.isEmpty()) {
                        VaultStore.RoleEntry e = new VaultStore.RoleEntry(rsid, ruid);
                        e.name = r.optString("name", "");
                        e.level = r.optLong("level", 0);
                        e.power = r.optLong("power", 0);
                        e.enabled = r.optBoolean("enabled", true);
                        a.roles.put(VaultStore.roleKey(rsid, ruid), e);
                    }
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

    /**
     * 重装/清数据后恢复 token（2026-10-25）：只读回取服务器留存的账号 token。
     *
     * <p>与写通道 {@code /v1/vault/token} 对称（同走 ApiSign，OP=vaultTokenGet）。App 新装后
     * 本地账号库为空、服务器 status_cache 仍 hasToken=true 时，用本接口把 token 拉回本地回填，
     * 免去重贴/手动登录一次；写库由调用方决定（本接口只读，不改 store）。
     *
     * <p>返回 {@code {fpid, token, hasToken:true}}；updatedAt 本期不落 token 更新时间戳，省略。
     * 业务码：1004 账号不存在、1005 账号无 token、2004 缺 fpid、2001 验签失败。
     * 日志纪律：只落 fpid/hasToken，绝不落 token 明文；错误信息同样不含 token。
     */
    private JSONObject handleTokenGet(Map<String, Object> fields) {
        String fpid = str(fields, "fpid");
        if (fpid == null || fpid.isEmpty()) {
            throw new BizError(ApiSign.CODE_BAD_REQUEST, "缺少 fpid");
        }
        String token = store.getToken(fpid);
        if (token == null) {
            FileLog.warn("token 读取失败：fpid={} 账号不存在", fpid);
            throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
        }
        if (token.isEmpty()) {
            FileLog.warn("token 读取失败：fpid={} 无 token", fpid);
            throw new BizError(CODE_TOKEN_MISSING, "账号没有 token: " + fpid);
        }
        FileLog.info("token 读取：fpid={} hasToken=true", fpid);
        JSONObject data = new JSONObject();
        data.put("fpid", fpid);
        data.put("token", token);
        data.put("hasToken", true);
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
        Map<String, VaultStore.Account> accs = new java.util.LinkedHashMap<>();
        for (VaultStore.Account a : store.snapshot()) accs.put(a.fpid, a);
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
            o.put("activeUid", s.activeUid == null ? "" : s.activeUid);
            o.put("hasToken", s.hasToken);
            o.put("roleName", s.roleName == null ? "" : s.roleName);
            o.put("roleLevel", s.roleLevel);
            o.put("rolePower", s.rolePower);
            o.put("roleAlliance", s.roleAlliance == null ? "" : s.roleAlliance);
            o.put("sinceTs", s.sinceTs);
            // M1 扩展（契约 §3.5）：每账号角色运行态 + 角色池 roles（旧字段保留，App v4.1.4 不受影响）。
            // 注意：旧 state 是字符串（引擎态），不能被同名对象覆盖 —— 新对象用 roleState 键。
            o.put("roleState", roleStateJson(keeper.roleRuntime(s.fpid, s.sid, s.activeUid)));
            // P7：战争状态摘要（无实例/未挂载 = null，App 端按未知处理）
            try {
                JSONObject war = ServerWarState.summaryJson(s.fpid, parseSid(s.sid));
                o.put("warState", war == null ? JSONObject.NULL : war);
            } catch (Throwable t) {
                o.put("warState", JSONObject.NULL);
            }
            VaultStore.Account acc = accs.get(s.fpid);
            o.put("roles", acc == null ? new JSONArray() : rolesJson(acc));
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
            data.put("activeUid", acc.activeUid == null ? "" : acc.activeUid);
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

    // ---------- 账号池控制面（M1 2026-10-21，契约 §3） ----------

    /** POST /v1/vault/accounts：账号池列表 + 聚合计数 + 每角色运行态（轻量，不含 config/params）。 */
    private JSONObject handleAccounts(Map<String, Object> fields) {
        JSONObject data = new JSONObject();
        data.put("serverTime", System.currentTimeMillis());
        JSONArray arr = new JSONArray();
        int totalOnline = 0;
        for (VaultStore.Account a : store.snapshot()) {
            JSONObject o = new JSONObject();
            o.put("fpid", a.fpid);
            o.put("accountName", a.label == null ? "" : a.label);
            o.put("activeSid", a.activeSid == null ? "" : a.activeSid);
            o.put("activeUid", a.activeUid == null ? "" : a.activeUid);
            o.put("keep", a.keep);
            o.put("recovery", recoveryJson(a));   // P1：账号级顶号恢复策略
            JSONArray roles = rolesJson(a);
            o.put("roles", roles);
            int online = 0;
            for (int i = 0; i < roles.length(); i++) {
                if (roles.getJSONObject(i).getJSONObject("state").optBoolean("online")) online++;
            }
            o.put("workspaceCount", roles.length());
            o.put("onlineCount", online);
            totalOnline += online;
            arr.put(o);
        }
        data.put("accounts", arr);
        data.put("onlineCount", totalOnline);   // 全局 onlineCount = 所有账号 online 角色数（契约 §2.1）
        return data;
    }

    /** 账号的角色池视图：库内角色条目；空池回落 active 角色（旧数据可见性）。 */
    private JSONArray rolesJson(VaultStore.Account a) {
        JSONArray arr = new JSONArray();
        java.util.LinkedHashSet<String> emitted = new java.util.LinkedHashSet<>();
        for (VaultStore.RoleEntry r : a.roles.values()) {
            arr.put(roleJson(a.fpid, r));
            emitted.add(VaultStore.roleKey(r.sid, r.uid));
        }
        if (arr.length() == 0 && a.activeSid != null && !a.activeSid.isEmpty()) {
            arr.put(roleJson(a.fpid,
                    new VaultStore.RoleEntry(a.activeSid, a.activeUid == null ? "" : a.activeUid)));
        }
        return arr;
    }

    private JSONObject roleJson(String fpid, VaultStore.RoleEntry r) {
        JSONObject o = new JSONObject();
        o.put("sid", r.sid);
        o.put("uid", r.uid);
        o.put("name", r.name == null ? "" : r.name);
        o.put("level", r.level);
        o.put("power", r.power);
        o.put("enabled", r.enabled);
        o.put("antibanEnabled", r.antibanEnabled);   // P1：防封保护开关
        o.put("pause_resume_at", isoOrNull(r.pauseResumeAt));   // P1：暂停自动恢复时刻（ISO-8601，可空）
        o.put("state", roleStateJson(keeper.roleRuntime(fpid, r.sid, r.uid)));
        return o;
    }

    /** epoch millis → ISO-8601（0/负 = 无计划，空串）。 */
    private static String isoOrNull(long epochMillis) {
        return epochMillis > 0 ? java.time.Instant.ofEpochMilli(epochMillis).toString() : "";
    }

    /** 账号级恢复策略视图（契约 §二：initialDelayMinutes 秒→分钟向上取整，对齐线上 toMap）。 */
    private static JSONObject recoveryJson(VaultStore.Account a) {
        JSONObject rec = new JSONObject();
        rec.put("enabled", a.takeoverRecoveryEnabled);
        rec.put("initialDelayMinutes", Math.max(1, (a.takeoverInitialDelaySeconds + 59) / 60));
        rec.put("retryIntervalSeconds", a.takeoverRetryIntervalSeconds);
        rec.put("maxAttempts", a.takeoverMaxAttempts);
        rec.put("cooldownMinutes", a.takeoverCooldownMinutes);
        return rec;
    }

    private static JSONObject roleStateJson(SessionControl.RoleRuntime rt) {
        JSONObject s = new JSONObject();
        s.put("online", rt.online);
        s.put("runState", rt.runState);
        s.put("recoveryState", rt.recoveryState);
        s.put("statusMessage", rt.statusMessage == null ? "" : rt.statusMessage);
        s.put("takeoverRecoveryActive", rt.takeoverRecoveryActive);
        s.put("nextRecoveryAt", rt.nextRecoveryAt);
        s.put("startupInProgress", rt.startupInProgress);
        s.put("loginInProgress", rt.loginInProgress);
        s.put("runScheduleState", rt.runScheduleState);
        return s;
    }

    /** POST /v1/vault/role-action：单角色动作（M1 §3.2 + P1 antiban/pause/rename/remove/enabled）。 */
    private JSONObject handleRoleAction(Map<String, Object> fields) throws Exception {
        String fpid = str(fields, "fpid");
        String sid = str(fields, "sid");
        String uid = str(fields, "uid");
        String action = str(fields, "action");
        if (fpid == null || fpid.isEmpty()) throw new BizError(3002, "缺少 fpid");
        if (action == null || action.isEmpty()) throw new BizError(3002, "缺少 action");
        // 编队动作（契约 §3.5；T2 2026-10-25）：formation.catalog-refresh / memory-save /
        // memory-restore / bear-binding。走 SessionKeeper 的编队动作入口（需要引擎/state 访问权）。
        if (action.startsWith("formation.")) {
            return handleFormationAction(fpid, sid, uid, action, fields);
        }
        SessionControl.RoleOpResult r = keeper.roleAction(fpid, sid, uid, action, fields);
        if (r.code != 0) throw new BizError(r.code, r.message);
        JSONObject data = new JSONObject();
        data.put("accepted", r.accepted);
        data.put("state", r.runState);
        data.put("message", r.message == null ? "" : r.message);
        JSONArray stopped = new JSONArray();
        for (SessionControl.RoleRef ref : r.stoppedRoles) {
            JSONObject so = new JSONObject();
            so.put("sid", ref.sid);
            so.put("uid", ref.uid);
            stopped.put(so);
        }
        data.put("stoppedRoles", stopped);
        // P1 扩展（按 op 选填）：pause 恢复时刻 / rename 结果字段
        if (r.pauseResumeAt > 0) data.put("pauseResumeAt", r.pauseResumeAt);
        if (r.name != null) data.put("name", r.name);
        if ("rename".equals(action)) {
            data.put("usedDiamond", r.usedDiamond);
            data.put("diamondCost", r.diamondCost);
            data.put("itemId", r.itemId);
        }
        return data;
    }

    /**
     * 编队动作分发（契约 §3.5）：{@code formation.catalog-refresh} / {@code memory-save} /
     * {@code memory-restore} / {@code bear-binding}。
     *
     * <p>返回 data 由动作决定：catalog-refresh={ok,capturedAt}；memory-save={kind,slotCount,savedAt,
     * version}；memory-restore={total,restored,failures[]}；bear-binding=绑定 JSON。错误走既有
     * {@link BizError}（HTTP 200 + 信封 code，中文文案）。
     */
    private JSONObject handleFormationAction(String fpid, String sid, String uid, String action,
                                             Map<String, Object> fields) throws Exception {
        if (!(keeper instanceof SessionKeeper)) {
            throw new BizError(3002, "编队动作未启用（服务端未就绪）");
        }
        SessionKeeper.FormationActionResult r =
                ((SessionKeeper) keeper).formationAction(fpid, sid, uid, action, fields);
        if (r.code != 0) throw new BizError(r.code, r.message);
        return r.data == null ? new JSONObject() : r.data;
    }

    /** POST /v1/vault/account-action：账号级 login-all/stop-all（契约 §3.3，允许部分失败）。 */
    private JSONObject handleAccountAction(Map<String, Object> fields) throws Exception {
        String fpid = str(fields, "fpid");
        String action = str(fields, "action");
        if (fpid == null || fpid.isEmpty()) throw new BizError(3002, "缺少 fpid");
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
        JSONArray results = new JSONArray();
        if ("recovery-settings".equals(action)) {
            return handleRecoverySettings(fpid, fields);
        }
        if ("login-all".equals(action)) {
            // M1d 多角色并行：全部工作区角色逐个尝试上线（已有在线角色 login 幂等成功），
            // 不再因一个在线就跳过；单角色失败只影响本角色
            for (VaultStore.RoleEntry r : loginOrder(acc)) {
                JSONObject ro = new JSONObject();
                ro.put("sid", r.sid);
                ro.put("uid", r.uid);
                SessionControl.RoleOpResult res = keeper.roleAction(fpid, r.sid, r.uid, "login");
                ro.put("ok", res.code == 0);
                ro.put("message", res.code == 0 ? "" : res.message);
                results.put(ro);
            }
        } else if ("stop-all".equals(action)) {
            for (VaultStore.RoleEntry r : store.roles(fpid)) {
                JSONObject ro = new JSONObject();
                ro.put("sid", r.sid);
                ro.put("uid", r.uid);
                if (!r.enabled) {
                    ro.put("ok", true);
                    ro.put("message", "已收纳，跳过");
                } else {
                    SessionControl.RoleOpResult res = keeper.roleAction(fpid, r.sid, r.uid, "stop");
                    ro.put("ok", res.code == 0);
                    ro.put("message", res.code == 0 ? "" : res.message);
                }
                results.put(ro);
            }
        } else {
            throw new BizError(3002, "未知 action: " + action);
        }
        JSONObject data = new JSONObject();
        data.put("accepted", true);
        data.put("results", results);
        data.put("onlineCount", accountOnlineCount(fpid));
        return data;
    }

    /** P1 op recovery-settings：校验（分钟域，3002+中文）→ 持久化 → 通知 keeper 重算暂停恢复。 */
    private JSONObject handleRecoverySettings(String fpid, Map<String, Object> fields) {
        boolean enabled = optBool(fields, "enabled", true);
        int initialDelayMinutes = optInt(fields, "initialDelayMinutes", 30);
        int retryIntervalSeconds = optInt(fields, "retryIntervalSeconds", 5);
        int maxAttempts = optInt(fields, "maxAttempts", 3);
        int cooldownMinutes = optInt(fields, "cooldownMinutes", 15);
        String invalid = VaultStore.validateRecovery(initialDelayMinutes, retryIntervalSeconds,
                maxAttempts, cooldownMinutes);
        if (invalid != null) throw new BizError(3002, invalid);
        if (!store.updateRecoverySettings(fpid, enabled, initialDelayMinutes * 60,
                retryIntervalSeconds, maxAttempts, cooldownMinutes)) {
            throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
        }
        keeper.onRecoverySettingsChanged(fpid);
        FileLog.info("顶号恢复设置已保存 fpid={} enabled={} 等待={}min 重试={}s 尝试={} 冷却={}min",
                fpid, enabled, initialDelayMinutes, retryIntervalSeconds, maxAttempts, cooldownMinutes);
        JSONObject data = new JSONObject();
        data.put("accepted", true);
        data.put("message", "顶号恢复设置已保存并立即生效");
        data.put("recovery", recoveryJson(store.find(fpid)));
        return data;
    }

    private static boolean optBool(Map<String, Object> fields, String key, boolean def) {
        Object v = fields.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
    }

    private static int optInt(Map<String, Object> fields, String key, int def) {
        Object v = fields.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    /** login-all 尝试顺序：active 角色优先，其余工作区角色跟后（仅 enabled、uid 明确的）。 */    private static List<VaultStore.RoleEntry> loginOrder(VaultStore.Account a) {
        List<VaultStore.RoleEntry> out = new ArrayList<>();
        for (VaultStore.RoleEntry r : a.roles.values()) {
            if (!r.enabled || r.uid == null || r.uid.isEmpty()) continue;
            boolean active = r.sid.equals(a.activeSid == null ? "" : a.activeSid)
                    && r.uid.equals(a.activeUid == null ? "" : a.activeUid);
            if (active) out.add(0, r);
            else out.add(r);
        }
        return out;
    }

    /** 该账号当前 online 角色数（按角色池逐个推导）。 */
    private int accountOnlineCount(String fpid) {
        int n = 0;
        for (VaultStore.RoleEntry r : store.roles(fpid)) {
            if (keeper.roleRuntime(fpid, r.sid, r.uid).online) n++;
        }
        return n;
    }

    /** POST /v1/vault/global-action：全局 login-all/stop-all（契约 §3.4，P0 不做进度推送）。 */
    private JSONObject handleGlobalAction(Map<String, Object> fields) throws Exception {
        String action = str(fields, "action");
        if (!"login-all".equals(action) && !"stop-all".equals(action)) {
            throw new BizError(3002, "未知 action: " + action);
        }
        JSONArray accounts = new JSONArray();
        for (VaultStore.Account a : store.snapshot()) {
            JSONObject ao = new JSONObject();
            ao.put("fpid", a.fpid);
            String message = "";
            boolean ok;
            String onlineUid = "";
            if (a.token == null || a.token.isEmpty()) {
                ok = false;
                message = "账号缺少 token，跳过";
            } else if ("login-all".equals(action)) {
                // M1d 多角色并行：每账号所有 enabled 角色逐个尝试上线（不再只上 active 一个）
                List<VaultStore.RoleEntry> order = loginOrder(a);
                if (order.isEmpty()) {
                    ok = false;
                    message = "无可上线角色";
                } else {
                    ok = true;
                    JSONArray rs = new JSONArray();
                    for (VaultStore.RoleEntry r : order) {
                        SessionControl.RoleOpResult res =
                                keeper.roleAction(a.fpid, r.sid, r.uid, "login");
                        JSONObject ro = new JSONObject();
                        ro.put("sid", r.sid);
                        ro.put("uid", r.uid);
                        ro.put("ok", res.code == 0);
                        ro.put("message", res.code == 0 ? "" : res.message);
                        rs.put(ro);
                        if (res.code == 0) {
                            if (onlineUid.isEmpty()) onlineUid = r.uid;
                        } else {
                            if (message.isEmpty()) message = res.message == null ? "" : res.message;
                        }
                    }
                    ao.put("results", rs);
                    ok = !onlineUid.isEmpty();   // 至少一个角色上线成功即账号级成功
                }
            } else {
                ok = true;
                for (VaultStore.RoleEntry r : store.roles(a.fpid)) {
                    if (!r.enabled) continue;
                    SessionControl.RoleOpResult res = keeper.roleAction(a.fpid, r.sid, r.uid, "stop");
                    if (res.code != 0) {
                        ok = false;
                        message = res.message == null ? "" : res.message;
                    }
                }
            }
            ao.put("ok", ok);
            ao.put("onlineUid", onlineUid);
            ao.put("message", message);
            accounts.put(ao);
        }
        JSONObject data = new JSONObject();
        data.put("accepted", true);
        data.put("accounts", accounts);
        data.put("onlineCount", keeper.onlineRoleCount());
        return data;
    }

    // ---------- 战争通知（2026-10-22）----------

    /**
     * POST /v1/vault/watch：action ∈ list/add/update/remove/config/test。
     * 名单是账号级（fpid 寻址）；config 为全局（watch 开关/企微/冷却/静默）。
     */
    private JSONObject handleWatch(Map<String, Object> fields) throws Exception {
        if (war == null) throw new BizError(3002, "战争通知未启用");
        String action = str(fields, "action");
        if (action == null || action.isEmpty()) throw new BizError(3002, "缺少 action");
        if ("config".equals(action)) return handleWatchConfig(fields);
        if ("test".equals(action)) {
            boolean ok = war.sendTest();
            FileLog.info("测试通知：{}", ok ? "已送达" : "发送失败");
            JSONObject data = new JSONObject();
            data.put("ok", ok);
            data.put("message", ok ? "测试消息已发送，请在企业微信群确认"
                    : "发送失败：请检查 webhook 地址与网络");
            return data;
        }
        String fpid = str(fields, "fpid");
        if (fpid == null || fpid.isEmpty()) throw new BizError(3002, "缺少 fpid");
        if ("list".equals(action)) return watchListData(fpid);
        if ("add".equals(action)) {
            String uid = str(fields, "uid");
            int r = store.addWatchTarget(fpid, uid, str(fields, "name"),
                    str(fields, "kid"), str(fields, "note"));
            switch (r) {
                case 0: break;
                case 1: throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
                case 2: throw new BizError(3002, "uid 必须为大于 0 的数字");
                default: throw new BizError(3002, "该 uid 已在监视名单中");
            }
            FileLog.info("监视名单新增 fpid={} uid={}", fpid, uid);
            JSONObject data = watchListData(fpid);
            data.put("message", "已添加监视");
            return data;
        }
        if ("update".equals(action)) {
            Boolean enabled = fields.containsKey("enabled") ? optBool(fields, "enabled", true) : null;
            boolean ok = store.updateWatchTarget(fpid, str(fields, "uid"), str(fields, "name"),
                    str(fields, "kid"), str(fields, "note"), enabled);
            if (!ok) throw new BizError(3002, "监视目标不存在: uid=" + str(fields, "uid"));
            FileLog.info("监视名单更新 fpid={} uid={}", fpid, str(fields, "uid"));
            return watchListData(fpid);
        }
        if ("remove".equals(action)) {
            boolean ok = store.removeWatchTarget(fpid, str(fields, "uid"));
            if (!ok) throw new BizError(3002, "监视目标不存在: uid=" + str(fields, "uid"));
            FileLog.info("监视名单删除 fpid={} uid={}", fpid, str(fields, "uid"));
            JSONObject data = watchListData(fpid);
            data.put("message", "已移除监视");
            return data;
        }
        throw new BizError(3002, "未知 action: " + action);
    }

    /** 名单 + 全局配置视图（add/update/remove 后整体回传，App 一次刷新）。 */
    private JSONObject watchListData(String fpid) throws Exception {
        VaultStore.Account acc = store.find(fpid);
        if (acc == null) throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
        JSONArray arr = new JSONArray();
        for (VaultStore.WatchTarget t : store.watchTargets(fpid)) {
            arr.put(watchTargetJson(t));
        }
        JSONObject data = new JSONObject();
        data.put("fpid", fpid);
        data.put("targets", arr);
        data.put("config", watchConfigJson());
        return data;
    }

    private static JSONObject watchTargetJson(VaultStore.WatchTarget t) {
        JSONObject o = new JSONObject();
        o.put("uid", t.uid);
        o.put("name", t.name == null ? "" : t.name);
        o.put("kid", t.kid == null ? "" : t.kid);
        o.put("note", t.note == null ? "" : t.note);
        o.put("enabled", t.enabled);
        return o;
    }

    /** 全局 watch/企微配置视图。 */
    private JSONObject watchConfigJson() {
        VaultStore.WatchConfig c = store.watchConfig();
        JSONObject o = new JSONObject();
        o.put("enabled", c.enabled);
        o.put("wecomEnabled", c.wecomEnabled);
        o.put("webhookUrl", c.webhookUrl == null ? "" : c.webhookUrl);
        o.put("cooldownSeconds", c.cooldownSeconds);
        o.put("quietStart", c.quietStart);
        o.put("quietEnd", c.quietEnd);
        return o;
    }

    /** op config：全局 watch/企微配置（先校验后保存；响应回显）。 */
    private JSONObject handleWatchConfig(Map<String, Object> fields) {
        VaultStore.WatchConfig cur = store.watchConfig();
        boolean enabled = optBool(fields, "enabled", cur.enabled);
        boolean wecomEnabled = optBool(fields, "wecomEnabled", cur.wecomEnabled);
        String webhookUrl = str(fields, "webhookUrl");
        if (webhookUrl == null) webhookUrl = cur.webhookUrl;
        int cooldownSeconds = optInt(fields, "cooldownSeconds", cur.cooldownSeconds);
        int quietStart = fields.containsKey("quietStart")
                ? optInt(fields, "quietStart", -1) : cur.quietStart;
        int quietEnd = fields.containsKey("quietEnd") ? optInt(fields, "quietEnd", cur.quietEnd) : cur.quietEnd;
        String invalid = VaultStore.validateWatchConfig(webhookUrl, cooldownSeconds,
                quietStart, quietEnd);
        if (invalid != null) throw new BizError(3002, invalid);
        store.updateWatchConfig(enabled, wecomEnabled, webhookUrl,
                cooldownSeconds, quietStart, quietEnd);
        FileLog.info("战争通知配置已保存 enabled={} wecom={} 冷却={}s 静默={}~{}",
                enabled, wecomEnabled, cooldownSeconds,
                quietStart < 0 ? "关" : String.valueOf(quietStart), String.valueOf(quietEnd));
        JSONObject data = new JSONObject();
        data.put("accepted", true);
        data.put("message", "通知配置已保存并立即生效");
        data.put("config", watchConfigJson());
        return data;
    }

    /**
     * POST /v1/vault/events：游标同步。请求 {cursor}（上次拿到的最大 seq，缺省 0）；
     * 响应 {events:[…最旧在前], cursor, ackCursor, serverTime}——cursor/ackCursor 同为
     * 服务器当前最大 seq（App 任选其一回传即可）。
     */
    private JSONObject handleEvents(Map<String, Object> fields) {
        if (war == null) throw new BizError(3002, "战争通知未启用");
        long cursor = 0;
        Object c = fields.get("cursor");
        if (c instanceof Number) cursor = ((Number) c).longValue();
        else if (c != null) {
            try {
                cursor = Long.parseLong(String.valueOf(c).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (cursor < 0) cursor = 0;
        List<JSONObject> fresh = war.eventsAfter(cursor);
        JSONArray arr = new JSONArray();
        for (JSONObject e : fresh) arr.put(e);
        long latest = war.latestSeq();
        JSONObject data = new JSONObject();
        data.put("events", arr);
        data.put("cursor", latest);
        data.put("ackCursor", latest);
        data.put("serverTime", System.currentTimeMillis());
        return data;
    }

    /**
     * POST /v1/vault/monitor：真机监控事件查询/打点/状态。
     *
     * <p>承载 {@link com.carlos.vaultserver.monitor.MonitorApi}；监控未启用时返回
     * {@code {enabled:false}}，不抛异常（旧服务端 App 提示“不支持/未启用”）。
     */
    private JSONObject handleMonitor(Map<String, Object> fields) {
        return com.carlos.vaultserver.monitor.MonitorApi.handle(fields);
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

    /** P7：会话 sid 字符串 → long（空/非法 = 0，warState 摘要查不到时按 fpid 兜底）。 */
    private static long parseSid(String sid) {
        try {
            return sid == null || sid.isEmpty() ? 0L : Long.parseLong(sid);
        } catch (NumberFormatException e) {
            return 0L;
        }
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
