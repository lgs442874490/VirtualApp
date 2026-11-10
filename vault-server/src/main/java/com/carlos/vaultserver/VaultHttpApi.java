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
 * POST /v1/vault/sync    全量身份库同步（accountsJson 字符串字段；空列表保护：服务端非空时
 *                        空覆盖被拒，确需清空须显式 allowEmpty=true，2026-10-25）
 * POST /v1/vault/token   单账号 token 更新（NEED_TOKEN 补给通道）
 * POST /v1/vault/token/get   单账号 token 只读回取（重装/清数据后恢复，2026-10-25）
 * POST /v1/vault/status  会话状态查询
 * POST /v1/vault/policy  顶号策略设置 / 让位指令 / 战时解卡（action=war-reset，2026-10-25）
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

    /** 迁城 1:1 复刻（2026-11-06）：引擎可为 null（单测旧构造兼容 → movecity 端点返回 3002）。 */
    private final com.carlos.vaultserver.movecity.MoveCityEngine moveCity;
    /** 观察点配置仓（批次 B 2026-10-30；可 null：测试旧构造/watchpoint 端点返回 3002）。 */
    private final com.carlos.vaultserver.watchpoint.WatchPointStore watchPointStore;
    private final ApiSign.NonceCache nonceCache = new ApiSign.NonceCache();
    private HttpServer server;
    /**
     * 移民模块（藏宝阁-自由移民，2026-11-06）：可 null（旧构造/单测），此时端点返回 3002。
     * 用 attach 注入而不加构造重载，避免既有构造器继续膨胀。
     */
    private volatile com.carlos.vaultserver.migrant.MigrantApi migrant;

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper, StateStore stateStore) {
        this(config, store, keeper, stateStore, null);
    }

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler) {
        this(config, store, keeper, stateStore, roundScheduler, null);
    }

    /** 战争通知接线构造：watch/events 端点可用（watchpoint 端点返回 3002）。 */
    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler,
                        WarNotify war) {
        this(config, store, keeper, stateStore, roundScheduler, war, null);
    }

    /** 批次 B 构造（Main 用）：watch/events + 观察点配置端点可用。 */
    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler,
                        WarNotify war,
                        com.carlos.vaultserver.watchpoint.WatchPointStore watchPointStore) {
        this(config, store, keeper, stateStore, roundScheduler, war, watchPointStore, null);
    }

    public VaultHttpApi(VaultConfig config, VaultStore store, SessionControl keeper,
                        StateStore stateStore, com.carlos.vaultserver.task.RoundScheduler roundScheduler,
                        WarNotify war,
                        com.carlos.vaultserver.watchpoint.WatchPointStore watchPointStore,
                        com.carlos.vaultserver.movecity.MoveCityEngine moveCity) {
        this.config = config;
        this.store = store;
        this.keeper = keeper;
        this.stateStore = stateStore;
        this.roundScheduler = roundScheduler;
        this.war = war;
        this.watchPointStore = watchPointStore;
        this.moveCity = moveCity;
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
        // 移民（藏宝阁-自由移民，2026-11-06）：单端点 + action 分发（同 role-action 惯例）。
        // 网页版是 /api/migrant/* 14 个 REST 端点，本项目统一走 ApiSign 信封。
        server.createContext("/v1/vault/migrant", ex -> guard(ex, "POST", "vaultMigrant", this::handleMigrant));
        // 账号池控制面（M1 2026-10-21，契约 §3）：列表/角色动作/账号批量/全局批量
        server.createContext("/v1/vault/accounts", ex -> guard(ex, "POST", "vaultAccounts", this::handleAccounts));
        server.createContext("/v1/vault/role-action", ex -> guard(ex, "POST", "vaultRoleAction", this::handleRoleAction));
        server.createContext("/v1/vault/account-action", ex -> guard(ex, "POST", "vaultAccountAction", this::handleAccountAction));
        server.createContext("/v1/vault/global-action", ex -> guard(ex, "POST", "vaultGlobalAction", this::handleGlobalAction));
        // 战争通知（2026-10-22）：监视名单/通知配置/测试通知 + 事件游标同步
        server.createContext("/v1/vault/watch", ex -> guard(ex, "POST", "vaultWatch", this::handleWatch));
        server.createContext("/v1/vault/events", ex -> guard(ex, "POST", "vaultEvents", this::handleEvents));
        // 告警中枢 P2（2026-11-09）：fid 关注名单 list/add/update/remove/bulk（旧 /watch、/watchpoint 并存）
        server.createContext("/v1/vault/alert",
                ex -> guard(ex, "POST", "vaultAlert", this::handleAlert));
        // 观察点配置（批次 B 2026-10-30）：get/set/enable/disable；写盘后引擎 5s 内 mtime 热载
        server.createContext("/v1/vault/watchpoint",
                ex -> guard(ex, "POST", "vaultWatchpoint", this::handleWatchpoint));
        // 真机监控（2026-10-25）：后台任务级协议监控事件游标拉取 + 用户打点；默认 off（VAULT_MONITOR=on 才开）
        server.createContext("/v1/vault/monitor", ex -> guard(ex, "POST", "vaultMonitor", this::handleMonitor));
        // 迁城 1:1 复刻（2026-11-06）：protocols/execute/schedule/jobs/job/cancel/remove-account
        // （action 分发，语义对齐原版 /api/movecity/move-city/* REST 面）
        server.createContext("/v1/vault/movecity",
                ex -> guard(ex, "POST", "vaultMovecity", this::handleMovecity));
        // 热更新自托管（2026-10-25）：App 默认更新源，无签名；详见 HotConfigRoutes
        server.createContext("/v1/vault/hotconfig",
                ex -> HotConfigRoutes.serveHotConfig(ex, config.dataDir));
        server.createContext("/v1/vault/app-latest.apk",
                ex -> HotConfigRoutes.serveApk(ex, config.dataDir));
        // DexPatch 代码补丁（2026-11-09）：dataDir/patch/code.dex 流式返回
        server.createContext("/v1/vault/patch/code.dex",
                ex -> HotConfigRoutes.servePatch(ex, config.dataDir));
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

    /**
     * 注入移民模块（Main 用）。测试旧构造不注入 → /v1/vault/migrant 返回 3002。
     */
    public void attachMigrant(com.carlos.vaultserver.migrant.MigrantApi api) {
        this.migrant = api;
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

    /**
     * 移民端点（14 个 action 由 {@link com.carlos.vaultserver.migrant.MigrantApi#handle} 分发）。
     * 未注入（旧构造/单测）返回 3002，语义同 watch/watchpoint 的降级约定。
     */
    private JSONObject handleMigrant(Map<String, Object> fields) throws Exception {
        com.carlos.vaultserver.migrant.MigrantApi api = this.migrant;
        if (api == null) throw new BizError(3002, "移民模块未启用");
        try {
            return api.handle(fields);
        } catch (com.carlos.vaultserver.migrant.MigrantApi.BizError e) {
            // 模块内业务错误 → 统一信封（HTTP 200 + code），与 1004/3002 等同构
            throw new BizError(e.code, e.getMessage());
        }
    }

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
            a.configDefaultsVersion = o.optInt("configDefaultsVersion", 0);
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
                    JSONObject prm = r.optJSONObject("params");
                    java.util.Map<String, String> prmMap = null;
                    if (prm != null) {
                        prmMap = new java.util.LinkedHashMap<>();
                        for (String fn : prm.keySet()) {
                            JSONObject p = prm.optJSONObject(fn);
                            if (p != null && p.length() > 0) prmMap.put(fn, p.toString());
                        }
                    }
                    // R2 Phase 2：带 uid 的角色行写 per-uid 表（同服双角色各自独立）；
                    // 旧 App（无 uid）仍写 sid 表=该区服默认，保持继承语义。
                    long rsidNum = parseSid(rsid);
                    long ruidNum = parseSid(ruid);
                    if (ruidNum > 0L && rsidNum > 0L) {
                        String uidKey = VaultStore.roleUidConfigKey(rsidNum, ruidNum);
                        a.roleConfigByUid.put(uidKey, cfgMap);
                        if (prmMap != null) a.roleParamsByUid.put(uidKey, prmMap);
                    } else {
                        a.roleConfig.put(rsid, cfgMap);
                        if (prmMap != null) a.roleParams.put(rsid, prmMap);
                    }
                }
            }
            // 2026-11-11 默认开启策略：旧客户端不带版本字段 → 上传的 false 是旧默认，一次性迁移；
            // 新客户端带 current 版本，用户手动关保留。
            if (a.configDefaultsVersion < VaultStore.CURRENT_CONFIG_DEFAULTS_VERSION) {
                VaultStore.migrateConfigMaps(a.roleConfig);
                VaultStore.migrateConfigMaps(a.roleConfigByUid);
                a.configDefaultsVersion = VaultStore.CURRENT_CONFIG_DEFAULTS_VERSION;
            }
            incoming.add(a);
        }
        // 空同步保护（2026-10-25）：App 全量同步为「本地即真相」，卸载重装/换机/清数据后
        // App 本地账号列表为空，若直接 replaceAll([]) 会把服务端身份库清空（生产已发生一次：
        // 03:15:17 身份库同步 0 条 → vault.json accounts=[]，账号/token 全丢）。
        // 规则：incoming 为空且服务端已有账号时拒绝覆盖；确需清空必须显式 allowEmpty=true
        // （App 后续可在「删除全部账号」流程里带上）。返回 code=0 + emptyRejected，App 无需改码。
        int currentAccounts = store.snapshot().size();
        boolean allowEmpty = truthy(fields.get("allowEmpty")) || truthy(fields.get("force"));
        if (incoming.isEmpty() && currentAccounts > 0 && !allowEmpty) {
            FileLog.warn("身份库空同步已拒绝：服务端 {} 个账号，incoming=0（防卸载重装/换机空列表清库；"
                    + "确需清空请带 allowEmpty=true）", String.valueOf(currentAccounts));
            JSONObject data = new JSONObject();
            data.put("accepted", 0);
            data.put("emptyRejected", true);
            data.put("serverAccounts", currentAccounts);
            data.put("message", "服务端已有 " + currentAccounts + " 个账号，空列表同步被拒绝（防误清空）");
            return data;
        }
        // 骤减保护（2026-11-08）：多设备/多 App 实例共用同一身份库时，任一设备本地账号较少
        // （卸载重装/换机/另一实例）都会整库覆盖，把其它设备账号删掉（生产 20:02:40：
        // 5 账号 → 1 账号，其它设备点「全部上线」报「账号不存在」）。
        // 规则：incoming 缺失的「服务端已有且有 token」账号数达到显著比例（≥2 且超过半数）时
        // 拒绝覆盖；App 显式删除账号时随 sync 携带 deletedFpids（或 allowShrink=true）放行。
        // 缺失 1 个账号（如主设备对齐另一实例的单号）不拦，避免阻碍正常恢复。
        java.util.Set<String> incomingFpids = new java.util.HashSet<>();
        for (VaultStore.Account a : incoming) {
            if (a != null && a.fpid != null && !a.fpid.isEmpty()) incomingFpids.add(a.fpid);
        }
        java.util.Set<String> deletedFpids = parseFpidSet(fields.get("deletedFpids"));
        java.util.List<String> missingFpids = new java.util.ArrayList<>();
        for (VaultStore.Account old : store.snapshot()) {
            if (old == null || old.fpid == null || old.fpid.isEmpty()) continue;
            if (incomingFpids.contains(old.fpid)) continue;
            if (old.token == null || old.token.isEmpty()) continue;   // 无 token 的空壳允许自然收敛
            if (deletedFpids.contains(old.fpid)) continue;            // App 显式删除
            missingFpids.add(old.fpid);
        }
        boolean allowShrink = truthy(fields.get("allowShrink")) || truthy(fields.get("force"));
        if (!allowShrink && shouldRejectShrink(currentAccounts, missingFpids.size())) {
            FileLog.warn("身份库骤减同步已拒绝：服务端 {} 个账号，incoming={}，缺失且有 token {} 个={}"
                            + "（防其它设备本地账号较少整库覆盖；确需删除请带 deletedFpids/allowShrink）",
                    String.valueOf(currentAccounts), String.valueOf(incoming.size()),
                    String.valueOf(missingFpids.size()), missingFpids.toString());
            JSONObject data = new JSONObject();
            data.put("accepted", 0);
            data.put("shrinkRejected", true);
            data.put("serverAccounts", currentAccounts);
            data.put("missingAccounts", new org.json.JSONArray(missingFpids));
            data.put("message", "服务器账号库有其它设备同步的账号（" + missingFpids.size()
                    + " 个），本次同步会删除它们，已拒绝；如需删除账号请走删除流程");
            return data;
        }
        // 迁城 1:1 复刻（2026-11-06）：全量同步中消失的账号联动取消未开始的定时迁城任务
        // （原版 removeParentAccount 语义；防已删账号的任务到点白跑/报错）。
        java.util.Set<String> before = new java.util.HashSet<>();
        for (VaultStore.Account a : store.snapshot()) before.add(a.fpid);
        int n = store.replaceAll(incoming);
        if (moveCity != null) {
            for (String fpid : before) {
                if (store.find(fpid) == null) moveCity.removeAccountEverywhere(fpid);
            }
        }
        keeper.reconcile();
        FileLog.info("身份库同步：{} 条（keep={}）", String.valueOf(n),
                String.valueOf(incoming.stream().filter(a -> a.keep).count()));
        JSONObject data = new JSONObject();
        data.put("accepted", n);
        return data;
    }

    /**
     * 全量同步骤减判定（2026-11-08）：缺失「服务端已有且有 token」的账号数达到
     * ≥2 且超过服务端账号数一半时拒绝（防多设备互相整库覆盖）。
     * 缺失 1 个不拦（主设备与另一实例单号对齐/正常恢复）；显式删除走 deletedFpids/allowShrink。
     */
    static boolean shouldRejectShrink(int currentAccounts, int missingTokenAccounts) {
        return missingTokenAccounts >= 2 && missingTokenAccounts * 2 > currentAccounts;
    }

    /** 解析逗号/空白分隔的 fpid 列表（App 显式删除账号时随 sync 携带）。 */
    static java.util.Set<String> parseFpidSet(Object raw) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (raw == null) return out;
        for (String s : String.valueOf(raw).split("[,\\s]+")) {
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
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
        // R2 Phase 2（2026-10-29）：状态键内部按角色 uid 隔离，但 App 契约不变——
        // 把「App 当前选中角色（activeUid）」的角色级任务状态投影成旧键（fpid:sid）返回；
        // 其它角色的任务状态不下发（App 只展示当前角色；切角色后 activeUid 变化自动切换投影）。
        // srv:* 等新契约键（asset/formation 已按 uid）保持原样下发。
        Map<String, Long> activeUids = activeUidByFpid(wantFpid);
        JSONObject states = new JSONObject();
        for (Map.Entry<String, Object> e : stateStore.snapshot().entrySet()) {
            String key = e.getKey();
            if (wantFpid != null && !wantFpid.isEmpty()
                    && !key.contains(":" + wantFpid + ":")) continue;
            String projected = projectRoleStateKey(key, activeUids);
            if (projected == null) {
                states.put(key, e.getValue());          // 非角色级键：原样下发
            } else if (!projected.isEmpty()) {
                states.put(projected, e.getValue());    // active 角色：投影为旧键
            }
            // 空串 = 非 active 角色的任务状态：不下发（切角色后再生效）
        }
        data.put("states", states);
        return data;
    }

    /** App 可消费的“任务状态”family（按角色 uid 隔离，需要投影给 App）。 */
    private static final java.util.Set<String> ROLE_STATE_FAMILIES = new java.util.HashSet<>(
            java.util.Arrays.asList("mod", "log", "st", "todo", "res", "rallies",
                    "questprog", "questdone", "questts", "summary", "act", "boxes"));

    /** fpid（可空=全部）→ activeUid（0=未知）。 */
    private Map<String, Long> activeUidByFpid(String wantFpid) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        try {
            for (SessionControl.SessionStatus s : keeper.status()) {
                if (wantFpid != null && !wantFpid.isEmpty() && !wantFpid.equals(s.fpid)) continue;
                long uid = 0L;
                try {
                    String a = s.activeUid == null ? "" : s.activeUid.trim();
                    if (!a.isEmpty()) uid = Long.parseLong(a);
                } catch (Throwable ignored) {
                }
                out.put(s.fpid, uid);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 角色级任务状态键 → 旧键投影。
     *
     * @return null=非角色级键（原样下发）；空串=属于非 active 角色（不下发）；其它=投影后的旧键
     */
    static String projectRoleStateKey(String key, Map<String, Long> activeUids) {
        if (key == null || key.isEmpty() || activeUids == null || activeUids.isEmpty()) return null;
        String[] p = key.split(":");
        if (p.length < 4) return null;
        String family = p[0];
        if (!ROLE_STATE_FAMILIES.contains(family)) return null;
        int idx;                    // fpid 的位置
        if ("st".equals(family)) {
            idx = 2;                // st:<day>:<fpid>:<sid>:<uid>:<fn…>
        } else {
            idx = 1;                // <family>:<fpid>:<sid>:<uid>:<rest…>
        }
        if (p.length < idx + 3) return null;    // 没有 uid 段 = 旧键，原样下发
        String fpid = p[idx];
        Long activeUid = activeUids.get(fpid);
        long uid = parseUidSafe(p[idx + 2]);
        if (uid <= 0L || activeUid == null || activeUid <= 0L) return null;
        if (uid != activeUid) return "";
        StringBuilder legacy = new StringBuilder();
        int skip = idx + 2;     // 去掉 uid 段
        for (int i = 0; i < p.length; i++) {
            if (i == skip) continue;
            if (legacy.length() > 0) legacy.append(':');
            legacy.append(p[i]);
        }
        return legacy.toString();
    }

    private static long parseUidSafe(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
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
            // P7：战争状态摘要（Phase2B 按 active 角色实例；无实例/未挂载 = null，App 端按未知处理）
            try {
                JSONObject war = ServerWarState.summaryJson(s.fpid, parseSid(s.sid),
                        parseUidSafe(s.activeUid));
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
        if ("war-reset".equals(action)) {
            // 运维解卡（2026-10-25）：清掉该账号所有角色的 WARTIME，不必重启进程。
            // 事故场景：副角色实例卡在战时态且不再被 tick → 账号级战时让位无限期拦截节奏派单。
            String fpid = str(fields, "fpid");
            if (fpid == null || fpid.isEmpty()) throw new IllegalArgumentException("缺少 fpid");
            int cleared = ServerWarState.resetWartime(fpid);
            FileLog.info("战争态解卡 fpid={} 清除实例数={}", fpid, String.valueOf(cleared));
            JSONObject data = new JSONObject();
            data.put("fpid", fpid);
            data.put("cleared", cleared);
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
            // R2 Phase 2：按 active 角色返回生效配置（uid 优先 → 旧 sid 表回退 → 任务默认）。
            long sidNum = parseSid(sid);
            long uidNum = parseSid(acc.activeUid);
            Map<String, Boolean> cfg = VaultStore.roleConfigOf(acc, sidNum, uidNum);
            JSONObject config = new JSONObject();
            if (cfg != null) {
                for (Map.Entry<String, Boolean> e : cfg.entrySet()) {
                    config.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
                }
            }
            data.put("config", config);
            data.put("configScope", VaultStore.roleConfigScope(acc, sidNum, uidNum));
            Map<String, String> prm = VaultStore.roleParamsOf(acc, sidNum, uidNum);
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
            arr.put(roleJson(a, r));
            emitted.add(VaultStore.roleKey(r.sid, r.uid));
        }
        if (arr.length() == 0 && a.activeSid != null && !a.activeSid.isEmpty()) {
            arr.put(roleJson(a,
                    new VaultStore.RoleEntry(a.activeSid, a.activeUid == null ? "" : a.activeUid)));
        }
        return arr;
    }

    /** 角色池行（R2 Phase 2 追加 config/configScope 有效配置；旧 App 忽略未知字段）。 */
    private JSONObject roleJson(VaultStore.Account a, VaultStore.RoleEntry r) {
        JSONObject o = new JSONObject();
        o.put("sid", r.sid);
        o.put("uid", r.uid);
        o.put("name", r.name == null ? "" : r.name);
        o.put("level", r.level);
        o.put("power", r.power);
        o.put("enabled", r.enabled);
        o.put("pause_resume_at", isoOrNull(r.pauseResumeAt));   // P1：暂停自动恢复时刻（ISO-8601，可空）
        o.put("state", roleStateJson(keeper.roleRuntime(a == null ? "" : a.fpid, r.sid, r.uid)));
        // R2 Phase 2：按角色返回生效任务开关（uid 优先 → 旧 sid 表回退），供 App 角色管理展示。
        if (a != null) {
            long rsid = parseSid(r.sid);
            long ruid = parseSid(r.uid);
            Map<String, Boolean> eff = VaultStore.roleConfigOf(a, rsid, ruid);
            JSONObject cfg = new JSONObject();
            if (eff != null) {
                for (Map.Entry<String, Boolean> e : eff.entrySet()) {
                    cfg.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
                }
            }
            o.put("config", cfg);
            o.put("configScope", VaultStore.roleConfigScope(a, rsid, ruid));
        }
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

    /** POST /v1/vault/role-action：单角色动作（M1 §3.2 + P1 pause/rename/remove/enabled）。 */
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
     * 观察点配置（批次 B 2026-10-30）：
     * <ul>
     *   <li>{@code get}：返回 {@code {enabled,envEnabled,monitorTargets[],protectRects[],
     *       friendAids[],alertTypes[],aidLabels{},…,updatedAt}}（脱敏/裁剪大字段）；</li>
     *   <li>{@code set}：整体替换配置；复杂结构用 JSON 字符串字段（{@code monitorTargetsJson} 等，
     *       HMAC 扁平标量约定）；非法输入返回 3002 且旧文件不变（fail-closed）；</li>
     *   <li>{@code enable}/{@code disable}：只改 {@code enabled}。</li>
     * </ul>
     * 写盘=临时文件原子替换 + {@code watchpoint.json.bak} 备份；WatchPointEngine 按 mtime 5s 热载。
     */
    /**
     * 观察点配置快照 + {@code friendAbbrs}（App 只认简称，回填输入框用）：
     * 先用 App 自己维护的 {@code aidLabels}，没有再用玩家目录反查，取不到就给空串占位。
     */
    private JSONObject watchpointSnapshot() {
        JSONObject o = watchPointStore.snapshotJson();
        try {
            JSONArray abbrs = new JSONArray();
            JSONArray aids = o.optJSONArray("friendAids");
            JSONObject labels = o.optJSONObject("aidLabels");
            com.carlos.vaultserver.watchpoint.PlayerDirectory dir =
                    com.carlos.vaultserver.watchpoint.PlayerDirectory.shared();
            if (aids != null) {
                for (int i = 0; i < aids.length(); i++) {
                    long aid = aids.optLong(i);
                    String a = aid <= 0L || labels == null
                            ? "" : labels.optString(String.valueOf(aid), "");
                    if (a.isEmpty() && aid > 0L && dir != null) a = dir.abbrOfAid(aid);
                    abbrs.put(a);
                }
            }
            o.put("friendAbbrs", abbrs);
        } catch (Throwable ignore) {
            // 快照照常返回，简称补不上只是回填缺省
        }
        return o;
    }

    private JSONObject handleWatchpoint(Map<String, Object> fields) throws Exception {
        com.carlos.vaultserver.watchpoint.WatchPointStore wp = watchPointStore;
        if (wp == null) throw new BizError(3002, "观察点未启用（服务端未就绪）");
        wp.ensureLoaded();
        String action = str(fields, "action");
        if (action == null || action.isEmpty()) throw new BizError(3002, "缺少 action");
        if ("get".equals(action)) return watchpointSnapshot();
        if ("enable".equals(action) || "disable".equals(action)) {
            boolean on = "enable".equals(action);
            wp.setEnabled(on);
            FileLog.info("[观察点] 配置开关更新 enabled={}", String.valueOf(on));
            return watchpointSnapshot();
        }
        if ("alliance".equals(action)) {
            // 2026-11-02：联盟简称 → aid（官方 req_alliance_search_by_key；游戏内「查询联盟」同款）
            String abbr = str(fields, "abbr");
            if (abbr == null || abbr.trim().isEmpty()) throw new BizError(3002, "缺少 abbr");
            long aid = keeper.resolveAllianceAid(str(fields, "fpid"),
                    optInt(fields, "sid", 0), abbr.trim());
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("abbr", abbr.trim());
            data.put("aid", aid);
            data.put("found", aid > 0L);
            FileLog.info("[观察点] 联盟简称查询 abbr={} → aid={}", abbr.trim(), String.valueOf(aid));
            return data;
        }
        if ("dir".equals(action)) {
            // 2026-11-02：目录重建要 20-40s（约 90 次游戏协议），绝不能占 HTTP 线程（池仅 4 个）
            com.carlos.vaultserver.watchpoint.PlayerDirectory dir =
                    com.carlos.vaultserver.watchpoint.PlayerDirectory.shared();
            boolean refreshing = false;
            // 只有过期才需要城缓存列表（拉 4500 条也要几秒），未过期就别白跑
            if (dir != null && dir.isStale()) {
                java.util.List<com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo> all =
                        com.carlos.vaultserver.watchpoint.WatchPointEngine.lookup(null, null, null, 0, 0, 6000);
                if (keeper instanceof com.carlos.vaultserver.SessionKeeper) {
                    dir.refreshAsync((com.carlos.vaultserver.SessionKeeper) keeper,
                            resolveFpid(fields), 0L, all);
                    refreshing = true;
                }
            }
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("count", dir == null ? 0 : dir.size());
            data.put("builtAt", dir == null ? 0L : dir.builtAt());
            data.put("refreshing", refreshing);
            return data;
        }
        if ("lookup".equals(action)) {
            // 2026-11-02 小工具：按 uid / 昵称子串 / 联盟简称（昵称 [818] 前缀）/ aid / 最低等级 查整图城缓存
            String fidArg = str(fields, "fid");
            boolean refreshing = false;
            java.util.List<com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo> list;
            if (fidArg != null && !fidArg.trim().isEmpty()) {
                com.carlos.vaultserver.watchpoint.PlayerDirectory dir =
                        com.carlos.vaultserver.watchpoint.PlayerDirectory.shared();
                com.carlos.vaultserver.watchpoint.PlayerDirectory.Entry hit = dir == null ? null : dir.byFid(fidArg);
                if (hit == null && dir != null && dir.isStale()) {
                    // 2026-11-02：目录重建要 20-40s，HTTP 里不能同步做（会超时/拖死请求线程）
                    java.util.List<com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo> all =
                            com.carlos.vaultserver.watchpoint.WatchPointEngine.lookup(null, null, null, 0, 0, 6000);
                    if (keeper instanceof com.carlos.vaultserver.SessionKeeper) {
                        dir.refreshAsync((com.carlos.vaultserver.SessionKeeper) keeper,
                                resolveFpid(fields), 0L, all);
                        refreshing = true;
                    }
                }
                list = new java.util.ArrayList<>();
                if (hit != null) {
                    list.add(new com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo(
                            hit.uid, hit.aid, hit.stoveLv, hit.nick, hit.x, hit.y));
                }
            } else {
                list = com.carlos.vaultserver.watchpoint.WatchPointEngine.lookup(
                        str(fields, "uid"), str(fields, "nick"), str(fields, "abbr"),
                        optInt(fields, "aid", 0), optInt(fields, "minLv", 0),
                        optInt(fields, "limit", 200));
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo c : list) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("uid", c.uid);
                o.put("aid", c.aid);
                o.put("lv", c.lv);
                o.put("nick", c.nick);
                o.put("x", c.x);
                o.put("y", c.y);
                arr.put(o);
            }
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("count", arr.length());
            data.put("cities", arr);
            if (fidArg != null && !fidArg.trim().isEmpty() && arr.length() == 0) {
                data.put("refreshing", refreshing);
                data.put("hint", refreshing
                        ? "玩家目录正在后台重建（约 20-40 秒），稍后再查一次"
                        : "玩家目录里没有这个账号ID（可先点“刷新玩家目录”）");
            }
            FileLog.info("[观察点] 查询：uid={} nick={} abbr={} aid={} minLv={} → {} 条",
                    str(fields, "uid"), str(fields, "nick"), str(fields, "abbr"),
                    String.valueOf(optInt(fields, "aid", 0)), String.valueOf(optInt(fields, "minLv", 0)),
                    String.valueOf(arr.length()));
            return data;
        }
        if ("player".equals(action)) {
            // 2026-11-02 小工具：借在线会话查玩家条目（含 alliance{aid,abbr,name} / stove_lv）
            String uids = str(fields, "uids");
            if (uids == null) uids = str(fields, "uid");
            if (uids == null || uids.trim().isEmpty()) throw new BizError(3002, "缺少 uid/uids");
            String fpid = resolveFpid(fields);
            int sid = optInt(fields, "sid", 0);
            java.util.List<Long> want = new java.util.ArrayList<>();
            for (String part : uids.split(",")) {
                if (want.size() >= 50) break;
                try {
                    long uid = Long.parseLong(part.trim());
                    if (uid > 0L && !want.contains(uid)) want.add(uid);
                } catch (NumberFormatException e) {
                    // 忽略非数字
                }
            }
            // 一次批量协议（50/批）取回，避免逐个 uid 打协议
            java.util.Map<Long, java.util.Map<String, Object>> infos = want.isEmpty()
                    ? java.util.Collections.emptyMap() : keeper.resolvePlayers(fpid, sid, want);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Long uid : want) {
                java.util.Map<String, Object> info = infos.get(uid);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("uid", uid);
                if (info == null) {
                    o.put("found", false);
                } else {
                    o.put("found", true);
                    Object nick = info.get("nickName");
                    o.put("nick", nick == null ? "" : String.valueOf(nick));
                    o.put("stoveLv", com.carlos.vaultserver.task.Exec.longOf(info.get("stove_lv")));
                    o.put("fid", com.carlos.vaultserver.task.Exec.longOf(info.get("fid")));
                    o.put("power", com.carlos.vaultserver.task.Exec.longOf(info.get("power")));
                    Object al = info.get("alliance");
                    if (al instanceof Map) {
                        java.util.Map<String, Object> am = com.carlos.vaultserver.task.Exec.mapOf(al);
                        o.put("aid", com.carlos.vaultserver.task.Exec.longOf(am.get("aid")));
                        Object abbr = am.get("abbr");
                        Object name = am.get("name");
                        o.put("abbr", abbr == null ? "" : String.valueOf(abbr));
                        o.put("allianceName", name == null ? "" : String.valueOf(name));
                    }
                }
                arr.put(o);
            }
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("count", arr.length());
            data.put("players", arr);
            FileLog.info("[观察点] 玩家查询 fpid={} sid={} uids={} → {} 条",
                    fpid, String.valueOf(sid), uids, String.valueOf(arr.length()));
            return data;
        }
        if ("resolve".equals(action)) {
            // 2026-11-02 小工具：城缓存筛出候选（可带 minLv），借在线会话批量解析联盟简称，再按 abbr 过滤
            String abbr = str(fields, "abbr");
            int minLv = optInt(fields, "minLv", 0);
            int limit = optInt(fields, "limit", 400);
            int aid = optInt(fields, "aid", 0);
            java.util.List<com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo> cand =
                    com.carlos.vaultserver.watchpoint.WatchPointEngine.lookup(
                            null, null, null, aid, minLv, limit);
            String fpid = str(fields, "fpid");
            if (fpid == null || fpid.isEmpty()) {
                java.util.List<com.carlos.vaultserver.VaultStore.Account> all = store.snapshot();
                if (!all.isEmpty()) fpid = all.get(0).fpid;
            }
            int sid = optInt(fields, "sid", 0);
            org.json.JSONArray arr = new org.json.JSONArray();
            int batch = 50;
            for (int i = 0; i < cand.size(); i += batch) {
                int end = Math.min(cand.size(), i + batch);
                java.util.List<Long> uids = new java.util.ArrayList<>();
                for (int j = i; j < end; j++) uids.add(cand.get(j).uid);
                java.util.Map<Long, java.util.Map<String, Object>> infos =
                        keeper.resolvePlayers(fpid, sid, uids);
                for (int j = i; j < end; j++) {
                    com.carlos.vaultserver.watchpoint.WatchPointEngine.CityInfo c = cand.get(j);
                    java.util.Map<String, Object> info = infos.get(c.uid);
                    if (info == null) continue;
                    java.util.Map<String, Object> am =
                            com.carlos.vaultserver.task.Exec.mapOf(info.get("alliance"));
                    String a = am == null || am.get("abbr") == null
                            ? "" : String.valueOf(am.get("abbr"));
                    if (abbr != null && !abbr.isEmpty() && !abbr.equalsIgnoreCase(a)) continue;
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("uid", c.uid);
                    o.put("nick", c.nick);
                    o.put("x", c.x);
                    o.put("y", c.y);
                    o.put("stoveLv", com.carlos.vaultserver.task.Exec.longOf(info.get("stove_lv")));
                    o.put("fid", com.carlos.vaultserver.task.Exec.longOf(info.get("fid")));
                    o.put("abbr", a);
                    o.put("aid", am == null ? 0L : com.carlos.vaultserver.task.Exec.longOf(am.get("aid")));
                    o.put("allianceName", am == null || am.get("name") == null ? "" : String.valueOf(am.get("name")));
                    arr.put(o);
                }
            }
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("count", arr.length());
            data.put("players", arr);
            FileLog.info("[观察点] 联盟解析 abbr={} minLv={} 候选={} → 命中 {}",
                    abbr, String.valueOf(minLv), String.valueOf(cand.size()),
                    String.valueOf(arr.length()));
            return data;
        }
        if ("set".equals(action)) {
            // 2026-11-02：UI 只能看到联盟简称，入参 friendAbbrs 用玩家目录/官方联盟搜索换成 friendAids。
            // 约定：字段出现即生效——空串表示清空（原来“空串被忽略”导致 App 上清不掉）。
            if (fields.containsKey("friendAbbrs")) {
                String abbrs = str(fields, "friendAbbrs");
                com.carlos.vaultserver.watchpoint.PlayerDirectory dir =
                        com.carlos.vaultserver.watchpoint.PlayerDirectory.shared();
                java.util.List<String> aids = new java.util.ArrayList<>();
                java.util.List<String> missing = new java.util.ArrayList<>();
                int onlineLookups = 0;   // 未知简称在线解析上限 2 次，避免 N 个未知名称串行打协议拖长保存

                if (abbrs != null) {
                    for (String part : abbrs.split(",")) {
                        String a = part.trim();
                        if (a.isEmpty()) continue;
                        // 简称优先按「目录/官方搜索」解析：纯数字简称（如 [818]）真实存在，
                        // 不能先当 aid 直通，否则「回填→保存」会把 friendAids 写成 818 这种错值。
                        Long aid = dir == null ? null : dir.aidOfAbbr(a);
                        if ((aid == null || aid <= 0L) && onlineLookups < 2) {
                            onlineLookups++;
                            long viaApi = keeper.resolveAllianceAid(resolveFpid(fields),
                                    optInt(fields, "sid", 0), a);
                            if (viaApi > 0L && dir != null) dir.rememberAbbr(a, viaApi);
                            aid = pickAbbrAid(viaApi > 0L ? viaApi : null, a);
                        }
                        if (aid == null || aid <= 0L) missing.add(a);
                        else if (!aids.contains(String.valueOf(aid))) aids.add(String.valueOf(aid));
                    }
                }
                if (!missing.isEmpty()) {
                    throw new BizError(3002, "联盟简称未识别：" + missing
                            + "（先点“刷新玩家目录”，或确认简称拼写正确）");
                }
                fields.put("friendAids", String.join(",", aids));   // 空列表 = 空串 = 清空
            }
        }
        if (!"set".equals(action)) throw new BizError(3002, "未知 action: " + action);
        JSONObject in = new JSONObject();
        for (String k : new String[]{"enabled", "kid", "fpid", "monitorIntervalMs",
                "protectIntervalMs", "maxReqPerSec", "wecomEnabled", "fullScanIntervalMs",
                "cityTargetOnly", "feintTroopsBelow", "incomingRallyFollowerAlerts", "alertTypes", "friendAids",
                "aidLabels", "monitorTargets", "protectRects"}) {
            if (fields.containsKey(k)) in.put(k, fields.get(k));
            Object json = fields.get(k + "Json");
            if (json != null && !in.has(k)) in.put(k, json);
        }
        String err = wp.applyConfig(in);
        if (err != null) throw new BizError(3002, err);
        FileLog.info("[观察点] 配置已更新：目标={} 矩形={} 告警类型={} 友盟={}",
                String.valueOf(wp.monitorTargets.size()), String.valueOf(wp.protectRects.size()),
                String.valueOf(wp.alertTypes.size()), String.valueOf(wp.friendAids.size()));
        return watchpointSnapshot();
    }

    /**
     * 告警中枢 P2：fid 关注名单。action=list/add/update/remove/bulk。
     * 名单全局一份、不依赖 fpid；旧 /v1/vault/watch 保持原行为（迁移期双轨）。
     */
    private JSONObject handleAlert(Map<String, Object> fields) throws Exception {
        com.carlos.vaultserver.alert.WatchlistService svc =
                com.carlos.vaultserver.alert.WatchlistRegistry.shared();
        if (svc == null) throw new BizError(3002, "告警中枢未启用");
        String action = str(fields, "action");
        if (action == null || action.isEmpty()) throw new BizError(3002, "缺少 action");
        JSONObject data = new JSONObject();
        switch (action) {
            case "list":
                data.put("entries", alertEntriesJson(svc));
                data.put("unresolved", alertUnresolvedJson(svc));
                return data;
            case "add": {
                if (!svc.add(str(fields, "fid"), str(fields, "note"))) {
                    throw new BizError(3002, "FID 必须为纯数字（游戏领主档案里的那串数字）");
                }
                data.put("entries", alertEntriesJson(svc));
                return data;
            }
            case "update": {
                Boolean enabled = fields.containsKey("enabled")
                        ? optBool(fields, "enabled", true) : null;
                if (!svc.update(str(fields, "fid"), str(fields, "note"), enabled)) {
                    throw new BizError(3002, "名单条目不存在");
                }
                data.put("entries", alertEntriesJson(svc));
                return data;
            }
            case "remove": {
                if (!svc.remove(str(fields, "fid"))) throw new BizError(3002, "名单条目不存在");
                data.put("entries", alertEntriesJson(svc));
                return data;
            }
            case "bulk": {
                // App 本地 seed 上报：targets:[{uid 或 fid, note, kid}]。
                // 默认 enabled=false（暂停），用户在页面确认后再启用；只给 uid 时服务端反查 fid，
                // 反查不到则进 unresolved（uid 仍参与匹配，不漏监控）。
                Object raw = fields.get("targets");
                int n = 0;
                int unresolved = 0;
                if (raw instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) raw) {
                        if (!(o instanceof java.util.Map)) continue;
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                        String fid = m.get("fid") == null ? "" : String.valueOf(m.get("fid")).trim();
                        String note = m.get("note") == null ? "" : String.valueOf(m.get("note")).trim();
                        long uid = 0L;
                        Object u = m.get("uid");
                        if (u != null) {
                            try {
                                uid = Long.parseLong(String.valueOf(u).trim());
                            } catch (Exception ignored) {
                            }
                        }
                        if (fid.isEmpty() && uid > 0L) {
                            com.carlos.vaultserver.watchpoint.PlayerDirectory.Entry e =
                                    com.carlos.vaultserver.watchpoint.PlayerDirectory.shared() == null
                                            ? null
                                            : com.carlos.vaultserver.watchpoint.PlayerDirectory.shared().byUid(uid);
                            fid = e == null || e.fid == null ? "" : e.fid.trim();
                        }
                        if (fid.isEmpty()) {
                            if (uid > 0L) {
                                VaultStore.AlertWatchEntry ue =
                                        new VaultStore.AlertWatchEntry("uid:" + uid);
                                ue.uid = uid;
                                ue.note = note;
                                ue.health = "UNRESOLVED";
                                ue.source = "MIGRATED_LOCAL";
                                ue.legacyRaw = "uid:" + uid;
                                store.addAlertUnresolved(ue);
                                unresolved++;
                            }
                            continue;
                        }
                        if (svc.add(fid, note)) {
                            svc.update(fid, null, false);   // 默认暂停
                            n++;
                        }
                    }
                }
                data.put("imported", n);
                data.put("unresolved", unresolved);
                data.put("entries", alertEntriesJson(svc));
                return data;
            }
            default:
                throw new BizError(3002, "未知 action: " + action);
        }
    }

    private static JSONArray alertEntriesJson(com.carlos.vaultserver.alert.WatchlistService svc) {
        JSONArray arr = new JSONArray();
        for (VaultStore.AlertWatchEntry e : svc.list()) {
            JSONObject o = new JSONObject();
            o.put("fid", e.fid);
            o.put("uid", e.uid);
            o.put("nick", e.nick == null ? "" : e.nick);
            o.put("kid", e.kid == null ? "" : e.kid);
            o.put("note", e.note == null ? "" : e.note);
            o.put("enabled", e.enabled);
            o.put("health", e.health == null ? "UNRESOLVED" : e.health);
            o.put("source", e.source == null ? "USER" : e.source);
            o.put("resolvedAt", e.resolvedAt);
            arr.put(o);
        }
        return arr;
    }

    private static JSONArray alertUnresolvedJson(com.carlos.vaultserver.alert.WatchlistService svc) {
        JSONArray arr = new JSONArray();
        for (VaultStore.AlertWatchEntry e : svc.unresolved()) {
            JSONObject o = new JSONObject();
            o.put("legacyRaw", e.legacyRaw == null ? "" : e.legacyRaw);
            o.put("uid", e.uid);
            o.put("note", e.note == null ? "" : e.note);
            o.put("source", e.source == null ? "" : e.source);
            arr.put(o);
        }
        return arr;
    }

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
            String fid = str(fields, "fid");
            // 2026-11-04：用户只知道游戏内 FID，不知道内部 uid → 只给 fid 时服务端换算
            if ((uid == null || uid.trim().isEmpty() || "0".equals(uid.trim()))
                    && fid != null && !fid.trim().isEmpty()) {
                long resolved = keeper == null ? 0L : keeper.resolveUidByFid(fpid, fid);
                if (resolved <= 0L) {
                    throw new BizError(3002, "FID " + fid + " 未查到玩家（请确认 FID 与角色区服一致，"
                            + "或先让该账号在线）");
                }
                uid = String.valueOf(resolved);
                FileLog.info("监视名单 FID 换算 fpid={} fid={} → uid={}", fpid, fid, uid);
            }
            int r = store.addWatchTarget(fpid, uid, str(fields, "name"),
                    str(fields, "kid"), str(fields, "note"));
            switch (r) {
                case 0: break;
                case 1: throw new BizError(CODE_ACCOUNT_NOT_FOUND, "账号不存在: " + fpid);
                case 2: throw new BizError(3002, "uid/FID 必须为大于 0 的数字");
                default: throw new BizError(3002, "该玩家已在监视名单中");
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
        org.json.JSONArray scope = new org.json.JSONArray();
        if (c.roleUidScope != null) {
            for (Long u : c.roleUidScope) scope.put(u);
        }
        o.put("roleUidScope", scope);
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
        // 2026-11-03：名单生效角色（App 树状多选勾选的角色 uid；空=该账号全部角色）
        if (fields.containsKey("roleUidScope")) {
            java.util.List<Long> uids = new java.util.ArrayList<>();
            String csv = str(fields, "roleUidScope");
            if (csv != null) {
                for (String part : csv.split(",")) {
                    String t = part.trim();
                    if (t.isEmpty()) continue;
                    try {
                        long u = Long.parseLong(t);
                        if (u > 0L && !uids.contains(u)) uids.add(u);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            store.updateWatchRoleScope(uids);
            FileLog.info("名单生效角色已保存：{} 个", String.valueOf(uids.size()));
        }
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

    /** 迁城 1:1 复刻（2026-11-06）：action 分发委托 {@code MoveCityApi}；业务校验错 → 3002。 */
    private JSONObject handleMovecity(Map<String, Object> fields) {
        if (moveCity == null) {
            throw new BizError(3002, "服务端未启用迁城功能");
        }
        try {
            return com.carlos.vaultserver.movecity.MoveCityApi.handle(moveCity, fields);
        } catch (IllegalArgumentException e) {
            throw new BizError(3002, e.getMessage());
        }
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

    /**
     * 简称 → aid 的取值优先级（2026-11-02 修）：目录/官方搜索解析结果优先；
     * 纯数字简称（如地图前缀 {@code [818]}）真实存在，只有「≥7 位且解析不到」才当作 aid 直通，
     * 否则 {@code 818} 会被写成 friendAids=818，静默写坏友盟配置。
     */
    static Long pickAbbrAid(Long resolved, String raw) {
        if (resolved != null && resolved > 0L) return resolved;
        if (raw != null && raw.matches("\\d{7,}")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /** 取 fpid：入参 → 第一个账号。观察点小工具统一走这里，避免各处重复兜底。 */
    private String resolveFpid(Map<String, Object> fields) {
        String fpid = str(fields, "fpid");
        if (fpid != null && !fpid.isEmpty()) return fpid;
        java.util.List<com.carlos.vaultserver.VaultStore.Account> all = store.snapshot();
        return all.isEmpty() ? "" : all.get(0).fpid;
    }

    /** 宽松布尔解析（Boolean/true/1/yes；用于 allowEmpty/force 这类显式确认开关）。 */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
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
        // 2026-11-08：公网链路带宽有限（/state 915KB 实测 24s，App 请求超时 → 账号页「状态获取失败」），
        // 启用 gzip（JSON 压缩比约 10x）。OkHttp 默认带 Accept-Encoding: gzip 并自动解压，
        // 旧版 APK 无需改动即可受益。
        if (acceptsGzip(ex)) {
            try {
                byte[] gz = gzip(bytes);
                if (gz.length < bytes.length) {
                    ex.getResponseHeaders().set("Content-Encoding", "gzip");
                    ex.getResponseHeaders().add("Vary", "Accept-Encoding");
                    bytes = gz;
                }
            } catch (Throwable ignored) {
                // 压缩失败回退未压缩（绝不阻断响应）
            }
        }
        ex.sendResponseHeaders(httpStatus, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 请求是否接受 gzip（Accept-Encoding 含 gzip）。 */
    private static boolean acceptsGzip(HttpExchange ex) {
        try {
            String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
            return ae != null && ae.toLowerCase(java.util.Locale.ROOT).contains("gzip");
        } catch (Throwable t) {
            return false;
        }
    }

    /** gzip 压缩（失败由调用方按未压缩返回，绝不阻断响应）。 */
    private static byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream bos =
                new java.io.ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }
}
