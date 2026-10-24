package com.carlos.vault;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.carlos.vault.daily.RoleIdentity;
import com.carlos.vault.daily.RoleStore;
import com.carlos.vault.license.LicenseCrypto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 号库服务器（vault-server）客户端：服务器模式下 App 是"身份库 + token 补给站"。
 *
 * 签名方案与 LicenseApi 同款（canonical JSON + HMAC-SHA256 + nonce），但使用独立
 * VAULT_SECRET（与授权密钥隔离泄露面）；复用 {@link LicenseCrypto} 的纯静态原语。
 * 复杂数据（账号列表）以 JSON 字符串字段（accountsJson）携带——签名层只出现扁平标量，
 * 与服务端 vault-server ApiSign 的字节级约定一致。
 *
 * 所有请求异步；失败静默（下次触发点重试），绝不阻塞调用线程。
 */
public final class VaultSync {

    private static final String TAG = "VaultSync";
    private static final String PREFS = "vault_server";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long HTTP_TIMEOUT_MS = 15_000L;

    private static final String PATH_SYNC = "/v1/vault/sync";
    private static final String PATH_TOKEN = "/v1/vault/token";
    private static final String PATH_STATUS = "/v1/vault/status";
    private static final String PATH_STATE = "/v1/vault/state";
    private static final String PATH_POLICY = "/v1/vault/policy";
    private static final String PATH_AUTOMATION = "/v1/vault/automation";
    private static final String PATH_ACCOUNTS = "/v1/vault/accounts";
    private static final String PATH_ROLE_ACTION = "/v1/vault/role-action";
    private static final String PATH_ACCOUNT_ACTION = "/v1/vault/account-action";
    private static final String PATH_GLOBAL_ACTION = "/v1/vault/global-action";
    private static final String PATH_WATCH = "/v1/vault/watch";
    private static final String PATH_EVENTS = "/v1/vault/events";
    private static final String OP_SYNC = "vaultSync";
    private static final String OP_TOKEN = "vaultToken";
    private static final String OP_STATUS = "vaultStatus";
    private static final String OP_STATE = "vaultState";
    private static final String OP_POLICY = "vaultPolicy";
    private static final String OP_AUTOMATION = "vaultAutomation";
    // M2 账号池四 OP（与 vault-server 白名单一一绑定，M1 契约 §6）
    private static final String OP_ACCOUNTS = "vaultAccounts";
    private static final String OP_ROLE_ACTION = "vaultRoleAction";
    private static final String OP_ACCOUNT_ACTION = "vaultAccountAction";
    private static final String OP_GLOBAL_ACTION = "vaultGlobalAction";
    private static final String OP_WATCH = "vaultWatch";
    private static final String OP_EVENTS = "vaultEvents";

    /** 与服务端 VAULT_SECRET 同值（base64 + 循环 XOR 混淆，防反编译明文）。 */
    private static final String SECRET_OBF = "ABIMAhdBQkQbRFFHWllZWxcQVAUGSw==";
    private static final byte[] SECRET_XOR_KEY = "vsync2026vault16".getBytes(Charset.forName("UTF-8"));

    /**
     * 默认端点：103.236.96.82（NAT VPS，网关在 64868 端口做 SSH/HTTP/TLS 复用，落机内
     * nginx 转发 vault-server；888syd.xyz 域名未备案，明文 Host/SNI 均被运营商 DPI 拦截，
     * 故走 IP+复用端口）。2026-10 起升级为 HTTPS-to-IP：
     *
     * - Java/OkHttp 对 IP 字面量地址不发送 SNI，TLS 里不含域名 → 不触发备案拦截（实测）；
     * - 证书为 10 年自签（IP SAN，见 vault-server/deploy/tls/），App 端只认 pin，
     *   不依赖系统 CA，中间人拿不出同公钥的证书 → 防篡改防嗅探；
     * - {@link #HTTP_PINNED} 仅用于 https://+本 IP 端点；其他 https 端点（如联调覆盖为
     * cs.888syd.xyz）走系统 CA 校验的 {@link #HTTP}；http:// 端点两者皆可（无 TLS 参与）。
     *
     * 证书轮换：用同一把私钥重签（命令见 deploy/tls/README.md）→ pin 不变，服务器换证书即可；
     * 换私钥则必须同步改 PROD_TLS_PIN 并发版。
     */
    private static final String PROD_HOST = "103.236.96.82";
    private static final String DEFAULT_BASE_URL = "https://" + PROD_HOST + ":64868";

    /** 服务器自签证书（CN/SAN=103.236.96.82，EC P-256）叶子公钥的 SPKI SHA-256 pin。 */
    private static final String PROD_TLS_PIN = "sha256/Di1/kXdjk27x3whwgGyei4CJHgMwXev8ty5APzZmKQQ=";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    /**
     * 信任管理器：不依赖 OkHttp CertificatePinner（其内部 chain cleaner 会用系统 CA
     * 清洗自签链导致空链、pin 必失败——真机 4.1.0 实测），直接在 TLS 握手期校验
     * 叶子证书 SPKI SHA-256。本客户端（HTTP_PINNED）只用于 https://PROD_HOST 端点，
     * 对所有经它的连接执行同一 pin，host 无歧义。
     */
    private static final X509TrustManager PINNING_TM = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) {
                throw new java.security.cert.CertificateException("空证书链");
            }
            byte[] spki;
            try {
                spki = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(chain[0].getPublicKey().getEncoded());
            } catch (Throwable t) {
                throw new java.security.cert.CertificateException("pin 计算失败: " + t);
            }
            String actual = "sha256/" + Base64.encodeToString(spki, Base64.NO_WRAP);
            if (!PROD_TLS_PIN.equals(actual)) {
                throw new java.security.cert.CertificateException(
                        "证书 pin 不匹配 actual=" + actual);
            }
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private static final OkHttpClient HTTP_PINNED = buildPinnedClient();

    private static OkHttpClient buildPinnedClient() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{PINNING_TM}, null);
            return new OkHttpClient.Builder()
                    .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .sslSocketFactory(sc.getSocketFactory(), PINNING_TM)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Throwable t) {
            // 不可达兜底：SSLContext 初始化失败时退回普通客户端（https 会因自签证书握手失败）
            return HTTP;
        }
    }

    /** 变更去抖：3 秒内的 token/keep/import 变更合并为一次全量 sync。 */
    private static final AtomicLong PENDING_SYNC_AT = new AtomicLong(0);
    private static volatile Thread debounceThread;

    /**
     * 全量 sync 串行执行器（2026-10-20 修复切角色被旧值回写竞态）：
     * 旧实现每次 syncAll 各起一条线程，并发请求到达服务器顺序不可控——切换角色时
     * 「旧角色的慢请求」可能晚于「新角色的快请求」落地，服务端最后一次写入变回旧角色
     * （真机复现：切到隆科多后 2-3s 服务器又重启回小鹿）。
     * 单线程串行化后，payload 在任务执行时构建（读当前角色），并按提交顺序完成 HTTP，
     * 服务器最后一次写入必然是最新状态。
     */
    private static final java.util.concurrent.ExecutorService SYNC_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vault-sync-serial");
                t.setDaemon(true);
                return t;
            });

    private VaultSync() {
    }

    // ---------- 服务器模式开关 ----------

    public static boolean isServerMode(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("server_mode", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setServerMode(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("server_mode", on).apply();
        if (on) {
            syncAll(ctx);
        }
    }

    /**
     * 统一自动化只允许服务器执行：App 启动时强制打开服务器模式。
     * 返回 true 表示本次发生了模式切换（已触发一次全量同步）。
     */
    public static boolean ensureServerMode(Context ctx) {
        if (isServerMode(ctx)) return false;
        setServerMode(ctx, true);
        Log.i(TAG, "已强制启用服务器模式（自动化任务仅服务器执行）");
        return true;
    }

    // ---------- 业务接口 ----------

    /** 全量同步身份库（账号/token/指纹/keep/active 角色）。异步、失败静默。 */
    public static void syncAll(Context ctx) {
        syncAll(ctx, null);
    }

    /** 全量同步并回调结果（回调在 OkHttp 线程；账号页登录按钮用它串起“同步→触发”）。 */
    public static void syncAll(Context ctx, Callback done) {
        final Context app = ctx.getApplicationContext();
        // 串行执行（见 SYNC_EXEC）：payload 在任务真正执行时构建，读当前 active 角色；
        // 多个调用按提交顺序逐条完成 HTTP，杜绝"旧 payload 后到覆盖新角色"。
        SYNC_EXEC.execute(() -> post(app, PATH_SYNC, OP_SYNC, syncFields(app),
                (code, message, data) -> {
                    // 2026-10-20：sync 成功只代表 payload 被接收，会话重启异步——
                    // 此处不再清 dirty；改由 learnServerRole 收到与本地一致的 activeUid
                    // 时按 fpid 清除（另有 TTL 兜底），避免旧快照把刚切的角色回滚。
                    if (done != null) done.onResult(code, message, data);
                }));
    }

    /** 账号数据变更钩子（token 更新/导入/keep 切换）：去抖后全量同步。 */
    public static void onAccountsChanged(Context ctx) {
        if (!isServerMode(ctx)) return;
        final Context app = ctx.getApplicationContext();
        PENDING_SYNC_AT.set(System.currentTimeMillis());
        synchronized (VaultSync.class) {
            if (debounceThread != null && debounceThread.isAlive()) return;
            debounceThread = new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    // 去抖窗口内又有新变更则继续等，直到安静 3 秒
                    while (System.currentTimeMillis() - PENDING_SYNC_AT.get() < 3000) {
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    return;
                }
                syncAll(app);
            }, "vault-debounce");
            debounceThread.start();
        }
    }

    /**
     * 用户切换了当前角色（UI 所有切换入口统一走这里）：标记「待上传」并触发去抖全量同步。
     *
     * <p>防回滚两件套（2026-10-20 P2，见 {@link #learnServerRole}）：
     * <ul>
     *   <li>{@code markRoleDirty}：上传完成前服务端 {@code /state}、{@code /status} 回传仍是
     *       旧角色，期间一律不回写当前角色——只靠时间戳挡不住「切换后才发起、同步前到达」
     *       的请求（如 RoleSessionKeeper 巡检、autoSyncAccount 打开页面即拉状态）；</li>
     *   <li>{@link #onAccountsChanged}：把原本最长 10 分钟（kickFullSync 防抖）的上行窗口
     *       压缩到约 3 秒，服务端尽快跟上本地选择。</li>
     * </ul>
     * 本地模式（无服务器）时 {@link #onAccountsChanged} 自动 no-op，待上传标记不会被读取。
     */
    public static void notifyRoleSwitched(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return;
        RoleStore.markRoleDirty(ctx, fpid);
        onAccountsChanged(ctx);
    }

    /** 单账号 token 立即补给（NEED_TOKEN 等场景，绕过去抖）。 */
    public static void pushToken(Context ctx, String fpid, String token) {
        if (!isServerMode(ctx) || fpid == null || fpid.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        Map<String, Object> f = commonFields(app);
        f.put("fpid", fpid);
        f.put("token", token);
        new Thread(() -> post(app, PATH_TOKEN, OP_TOKEN, f, null), "vault-token").start();
    }

    /** 拉取服务器会话状态并缓存（UI 从 cachedStateOf 读）。 */
    public static void refreshStatus(Context ctx) {
        final Context app = ctx.getApplicationContext();
        // 请求发起时刻：回传角色是否比本地激活更新，按它判定（防回滚，见 learnServerRole）
        final long requestTs = System.currentTimeMillis();
        Map<String, Object> f = commonFields(app);
        new Thread(() -> post(app, PATH_STATUS, OP_STATUS, f,
                (code, message, data) -> {
                    if (code == 0 && data != null) {
                        cacheAccounts(app, data.optJSONArray("accounts"), requestTs);
                    }
                }), "vault-status").start();
    }

    /**
     * 拉取采集状态 + 会话状态：states 的键与本地 daily_state prefs 同名同型
     * （todo:/res:/rallies:/questprog:/questdone:/mod:… 字符串，act:/boxes: int，questts: long），
     * 原样回写后今日页/日常页零改动渲染。
     */
    public static void fetchState(Context ctx) {
        fetchState(ctx, null);
    }

    /** 同上，附带完成回调（回写完成后触发，含失败；回调在 OkHttp 线程）。 */
    public static void fetchState(Context ctx, Callback done) {
        final Context app = ctx.getApplicationContext();
        // 请求发起时刻：回传角色是否比本地激活更新，按它判定（防回滚，见 learnServerRole）
        final long requestTs = System.currentTimeMillis();
        Map<String, Object> f = commonFields(app);
        new Thread(() -> post(app, PATH_STATE, OP_STATE, f,
                (code, message, data) -> {
                    if (code == 0 && data != null) {
                        cacheAccounts(app, data.optJSONArray("accounts"), requestTs);
                        JSONObject states = data.optJSONObject("states");
                        if (states != null && states.length() > 0) {
                            writeDailyState(app, states);
                            Log.i(TAG, "采集状态回写 " + states.length() + " 键");
                        }
                    }
                    if (done != null) done.onResult(code, message, data);
                }), "vault-state").start();
    }

    private static void cacheAccounts(Context app, JSONArray accounts, long requestTs) {
        if (accounts == null) return;
        try {
            JSONObject cache = new JSONObject();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject o = accounts.getJSONObject(i);
                String fpid = o.optString("fpid", "");
                cache.put(fpid, o);
                // 服务器登录后才知道实际落地 sid/角色名（App 端尚未本地发现角色时）：
                // 回写角色表 + active/current_role，保证角色卡与 st:…:sid:fn 都能命中。
                learnServerRole(app, fpid, o, requestTs);
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("status_cache", cache.toString())
                    .putLong("status_at", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把服务器实际 sid/角色元数据落到本地角色表与当前角色；已有别的账号 current_role 时不覆盖。
     *
     * <p><b>防回滚门控</b>（2026-10-20 P2，QA 报告 §3.1）：切角色只写本地、上传有延迟，
     * 窗口内服务端回传仍是旧角色。当前角色身份的回写受两层保护，任一命中即跳过
     * （仅跳过 setActiveRole/current_role）：
     * <ol>
     *   <li><b>待上传标记</b>（{@link RoleStore#isRoleDirty}）：本地刚切过角色且全量
     *       同步尚未成功。只靠时间戳挡不住「切换后才发起、同步前到达」的请求
     *       （RoleSessionKeeper 巡检、autoSyncAccount 等打开页面即拉状态的路径）；</li>
     *   <li><b>时间戳</b>（{@link RoleIdentity#shouldLearnActiveRole}）：本地激活身份
     *       与回传不同、且本地激活不早于请求发起（切换发生在请求发出之后 ⇒ 回传是
     *       切换前旧快照）；同时本地已知 uid 时拒绝服务端 uid 缺失的降级回写。</li>
     * </ol>
     * 另有两项与身份无关的学习：{@code migrateRole}（sid=0 配置迁移）保持无条件；
     * {@code registerRole} 元数据学习受 {@link RoleIdentity#shouldLearnServerMeta}
     * 门控（P1-2：uid=0 旧服务器回传不污染同服已知行，见方法内注释）。
     */
    private static void learnServerRole(Context app, String fpid, JSONObject status, long requestTs) {
        if (fpid == null || fpid.isEmpty() || status == null) return;
        String sidText = status.optString("sid", "");
        if (sidText == null || sidText.isEmpty()) return;
        try {
            long sid = Long.parseLong(sidText.trim());
            if (sid <= 0) return;
            // 角色发现前用户可能已改过开关/参数（写到 sid=0）；服务器登录学到真实 sid 后
            // 立刻迁移，否则这些修改永远不会进入 roles[].config/params。
            new com.carlos.vault.daily.DailyConfigStore(app).migrateRole(fpid, 0, sid);
            com.carlos.vault.daily.FnParams.migrateRole(app, fpid, 0, sid);
            // 批次10：编队库同样按 sid=0 预配置 → 学到真实 sid 后迁移（与参数同口径）
            com.carlos.vault.daily.FormationStore.migrateRole(app, fpid, 0, sid);
            String name = status.optString("roleName", "");
            // 服务器实际登录角色的 uid（同服多角色区分依据；旧服务器无此字段=0）
            long uid = parseUidSafe(status.optString("activeUid", ""));
            // P1-2（QA 报告 §3.4）：uid=0（旧服务器无 activeUid）时 registerRole 的宽松
            // 匹配会把会话角色元数据覆盖到同服第一行——该 sid 已有 uid 已知行时跳过本次
            // 元数据学习；仅首次引导（该 sid 还无已知行，单角色账号）允许 uid=0 登记。
            boolean learnMeta = RoleIdentity.shouldLearnServerMeta(
                    sid, uid, RoleStore.roles(app, fpid));
            if (learnMeta && (!name.isEmpty() || uid > 0)) {
                RoleStore.registerRole(app, fpid, sid, uid, name,
                        status.optLong("roleLevel", 0), status.optLong("rolePower", 0),
                        status.optString("roleAlliance", ""));
            } else if (!learnMeta) {
                Log.i(TAG, "跳过服务器角色元数据学习（防同服串行）fpid=" + fpid
                        + " sid=" + sid + " uid=" + uid);
            }
            // ---- 当前角色身份回写：双层防回滚门控 ----
            RoleStore.ActiveRole local = RoleStore.activeRoleEx(app, fpid);
            long localTs = RoleStore.activeRoleAt(app, fpid);
            boolean dirty = RoleStore.isRoleDirty(app, fpid);
            if (dirty && local.sid == sid && (uid <= 0 || local.uid <= 0 || local.uid == uid)) {
                // 服务器会话已是我们本地切到的角色：确认完成，清 dirty（不再拦截后续回写）
                RoleStore.clearRoleDirty(app, fpid);
                Log.i(TAG, "服务器角色确认一致，清除待确认标记 fpid=" + fpid
                        + " role=" + sid + ":" + uid);
            } else if (dirty || !RoleIdentity.shouldLearnActiveRole(
                    local.sid, local.uid, localTs, sid, uid, requestTs)) {
                Log.i(TAG, "跳过服务器角色回写（防回滚）fpid=" + fpid
                        + " local=" + local.sid + ":" + local.uid + "@" + localTs
                        + " server=" + sid + ":" + uid + " reqTs=" + requestTs
                        + " dirty=" + dirty);
                return;
            }
            RoleStore.setActiveRole(app, fpid, sid, uid);
            RoleStore.clearRoleDirty(app, fpid);   // 服务器已确认本地当前角色
            android.content.SharedPreferences home =
                    app.getSharedPreferences("home", Context.MODE_PRIVATE);
            String cur = home.getString("current_role", "");
            if (cur == null || cur.isEmpty() || cur.startsWith(fpid + ":")) {
                home.edit().putString("current_role",
                                fpid + ":" + sid + (uid > 0 ? ":" + uid : ""))
                        .putLong("current_role_at", System.currentTimeMillis()).apply();
            }
            Log.i(TAG, "采纳服务器角色 fpid=" + fpid + " sid=" + sid + " uid=" + uid + " name=" + name);
        } catch (Throwable ignored) {
        }
    }

    private static long parseUidSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 回写服务器状态：按前缀路由到对应 prefs——
     * log:*（去掉前缀）与 st:* → daily_logs（运行日志/当日状态机，与 LogStore 同键型）；
     * 其余（todo:/res:/rallies:/questprog:/mod:/summary:/act:/boxes:/questts:）→ daily_state。
     */
    private static void writeDailyState(Context app, JSONObject states) {
        try {
            android.content.SharedPreferences ds =
                    app.getSharedPreferences("daily_state", Context.MODE_PRIVATE);
            android.content.SharedPreferences dl =
                    app.getSharedPreferences("daily_logs", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor edDs = ds.edit();
            android.content.SharedPreferences.Editor edDl = dl.edit();
            boolean dsDirty = false, dlDirty = false;
            java.util.Iterator<String> it = states.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith("log:")) {
                    edDl.putString(k.substring(4), states.optString(k, "[]"));
                    dlDirty = true;
                } else if (k.startsWith("st:")) {
                    edDl.putInt(k, (int) states.optLong(k, 0));
                    dlDirty = true;
                } else if (k.startsWith("act:") || k.startsWith("boxes:")) {
                    edDs.putInt(k, states.optInt(k));
                    dsDirty = true;
                } else if (k.startsWith("questts:")) {
                    edDs.putLong(k, states.optLong(k));
                    dsDirty = true;
                } else {
                    edDs.putString(k, states.optString(k, ""));
                    dsDirty = true;
                }
            }
            if (dsDirty) edDs.apply();
            if (dlDirty) edDl.apply();
            // 角色列表统一标签（888-cch-昵称）数据源：从 srv:asset:{fpid}:{sid}:{uid}
            // 投影的 meta.allianceAbbr 缓存联盟简称（只缓存，不新建/不改开关）。
            java.util.Iterator<String> kit = states.keys();
            while (kit.hasNext()) {
                String k = kit.next();
                if (!k.startsWith("srv:asset:")) continue;
                try {
                    String[] seg = k.split(":");
                    if (seg.length < 5) continue;   // srv asset fpid sid [uid]
                    String fpid = seg[2];
                    long sid = Long.parseLong(seg[3]);
                    long uid = seg.length >= 5 ? Long.parseLong(seg[4]) : 0L;
                    JSONObject proj = new JSONObject(states.optString(k, "{}"));
                    JSONObject meta = proj.optJSONObject("meta");
                    String abbr = meta == null ? "" : meta.optString("allianceAbbr", "");
                    if (!abbr.isEmpty()) {
                        com.carlos.vault.daily.RoleStore.cacheAllianceAbbr(app, fpid, sid, uid, abbr);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "daily_state 回写失败: " + t);
        }
    }

    /** 进游戏前让服务器让位（默认 30 分钟后探测回连）。 */
    public static void yieldForGame(Context ctx, String fpid, int minutes) {
        if (!isServerMode(ctx) || fpid == null || fpid.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        Map<String, Object> f = commonFields(app);
        f.put("action", "yield");
        f.put("fpid", fpid);
        f.put("minutes", minutes);
        new Thread(() -> post(app, PATH_POLICY, OP_POLICY, f, null), "vault-yield").start();
        Log.i(TAG, "已通知服务器让位 fpid=" + fpid + " " + minutes + "min");
    }

    /** 服务器模式：提交整轮（fn=null）或单任务到服务器执行。 */
    public static void runTask(Context ctx, String fpid, String fn) {
        final Context app = ctx.getApplicationContext();
        Map<String, Object> f = commonFields(app);
        f.put("action", "run");
        f.put("fpid", fpid);
        if (fn != null) f.put("fn", fn);
        new Thread(() -> post(app, PATH_POLICY, OP_POLICY, f, null), "vault-run").start();
        Log.i(TAG, "已提交服务器执行 fpid=" + fpid + " fn=" + fn);
    }

    /**
     * 登录并触发服务器自动化：服务端确保该账号有常驻会话，读取/返回当前配置，
     * 排队执行整轮（fn=null）或单任务。回调在 OkHttp 线程。
     */
    public static void triggerAutomation(Context ctx, String fpid, String fn, Callback done) {
        final Context app = ctx.getApplicationContext();
        Map<String, Object> f = commonFields(app);
        f.put("action", "run");
        f.put("fpid", fpid);
        if (fn != null && !fn.isEmpty()) f.put("fn", fn);
        f.put("includeConfig", true);
        new Thread(() -> post(app, PATH_AUTOMATION, OP_AUTOMATION, f, done), "vault-automation-run").start();
        Log.i(TAG, "已提交服务器自动化 fpid=" + fpid + " fn=" + fn);
    }

    /** 只读账号自动化配置（账号页登录前展示/排障用）。 */
    public static void readAutomation(Context ctx, String fpid, Callback done) {
        final Context app = ctx.getApplicationContext();
        Map<String, Object> f = commonFields(app);
        f.put("action", "read");
        f.put("fpid", fpid);
        new Thread(() -> post(app, PATH_AUTOMATION, OP_AUTOMATION, f, done), "vault-automation-read").start();
    }

    /**
     * 「登录」共享后半程（第二轮 UI 统一，2026-10-20）：以<b>脚本模拟登录该角色在线</b>
     * ——不是启动游戏客户端。
     *
     * <p>语义链（调用方先完成角色钉扎 {@code setCurrentRole(fpid, sid, uid)}）：
     * <ol>
     *   <li>开统一自动化（keep=auto，服务器常驻会话与整轮调度同开关）；</li>
     *   <li>强制服务器模式（自动化只允许服务器执行）；</li>
     *   <li>全量上传身份库（含复合激活角色 activeSid/activeUid）；</li>
     *   <li>服务器确保会话并排队整轮（fn=null）。</li>
     * </ol>
     * 回调语义与 {@link #triggerAutomation} 一致（OkHttp 线程；data 含
     * {@code accepted/state/sid/config}）。账号页角色行、账号页账号级登录、账号详情页
     * 角色行三处共用——原先三处各写一份内联实现（详情页那份还漏了 cfg 统计）。
     */
    public static void loginRoleOnline(Context ctx, String fpid, Callback done) {
        final Context app = ctx.getApplicationContext();
        RoleStore.saveAutomation(app, fpid, true);
        ensureServerMode(app);
        syncAll(app, (code, message, data) -> {
            if (code != 0) {
                if (done != null) done.onResult(code, message, data);
                return;
            }
            triggerAutomation(app, fpid, null, done);
        });
    }

    // ---------- M2 账号池（/v1/vault/accounts + 动作控制面） ----------

    /** role-action 的动作值（M1 契约 §3.2）。 */
    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_LOGOUT = "logout";
    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_STOP = "stop";
    /** account-action / global-action 的批量动作值（M1 契约 §3.3/§3.4）。 */
    public static final String ACTION_LOGIN_ALL = "login-all";
    public static final String ACTION_STOP_ALL = "stop-all";

    /**
     * 拉取账号池列表 + 每角色运行态 + 聚合计数（M2 账号页轮询源，5s）。
     *
     * <p>成功（code==0）→ {@link AccountPoolCache#save}；失败分两类（M4 真机验证）：
     * 服务端没有该接口（code=3002/not found/404，如生产 v4.1.4）→
     * {@link AccountPoolCache#markUnsupported}（UI 不显示失败横幅、直接 legacy 渲染）；
     * 其它失败（网络断/超时）→ {@link AccountPoolCache#markStale} 但<b>保留上次缓存</b>
     * （UI 显示「状态获取失败，点击重试」，不误判离线）。回调在 OkHttp 线程。
     * 只做列表/状态，不替代 {@link #fetchState} 的今日页数据回写。
     */
    public static void fetchAccountPool(Context ctx, Callback done) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app)) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = commonFields(app);
        new Thread(() -> post(app, PATH_ACCOUNTS, OP_ACCOUNTS, f, (code, message, data) -> {
            if (code == 0 && data != null) {
                AccountPoolCache.save(app, data);
            } else if (AccountPoolCache.isUnsupportedReply(code, message)) {
                AccountPoolCache.markUnsupported(app);
            } else {
                AccountPoolCache.markStale(app);
            }
            if (done != null) done.onResult(code, message, data);
        }), "vault-accounts").start();
    }

    /**
     * 单角色动作（M1 §3.2）：action ∈ login/logout/pause/resume/stop。
     * M2d 语义变更（多角色并行）：login 只启动目标角色，不停止同账号其它角色，
     * 失败只影响本角色；旧服务端仍可能在 data.stoppedRoles 返回被停角色，
     * App 忽略（仅日志）。回调在 OkHttp 线程。
     */
    public static void roleAction(Context ctx, String fpid, String sid, String uid,
                                  String action, Callback done) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app) || fpid == null || fpid.isEmpty()) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = commonFields(app);
        f.put("fpid", fpid);
        f.put("sid", sid == null ? "" : sid);
        f.put("uid", uid == null ? "" : uid);
        f.put("action", action);
        new Thread(() -> post(app, PATH_ROLE_ACTION, OP_ROLE_ACTION, f, done),
                "vault-role-action").start();
    }

    /** 账号级批量（M1 §3.3）：action ∈ login-all/stop-all，出参 results[] 每角色结果。 */
    public static void accountAction(Context ctx, String fpid, String action, Callback done) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app) || fpid == null || fpid.isEmpty()) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = commonFields(app);
        f.put("fpid", fpid);
        f.put("action", action);
        new Thread(() -> post(app, PATH_ACCOUNT_ACTION, OP_ACCOUNT_ACTION, f, done),
                "vault-account-action").start();
    }

    /** 全局批量（M1 §3.4）：action ∈ login-all/stop-all，原子批量，出参 accounts[] 每账号结果。 */
    public static void globalAction(Context ctx, String action, Callback done) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app)) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = commonFields(app);
        f.put("action", action);
        new Thread(() -> post(app, PATH_GLOBAL_ACTION, OP_GLOBAL_ACTION, f, done),
                "vault-global-action").start();
    }

    // ---------- P1 角色动作扩展（契约《账号池P1-功能契约》§二） ----------

    /**
     * 防封保护开关（P1 §二.1，role-action op {@code antiban}，payload {@code enabled}）。
     * 在线/离线均可切；响应 {@code message}「已开启/已关闭防封风控安全保护」。
     * 回调在 OkHttp 线程。
     */
    public static void roleActionAntiban(Context ctx, String fpid, String sid, String uid,
                                         boolean enabled, Callback done) {
        roleActionWithPayload(ctx, fpid, sid, uid, "antiban", p -> p.put("enabled", enabled),
                done, "vault-role-antiban");
    }

    /**
     * 暂停（自动恢复）（P1 §二.2）：沿用现有 role-action pause 通道，服务端置
     * PAUSED + pauseResumeAt=now+initialDelay；失败（3002）message 为中文原因
     * （如「顶号恢复未开启，无法暂停」/「账号当前离线，无法暂停」），UI 直接 toast。
     * 回调在 OkHttp 线程。
     */
    public static void roleActionPause(Context ctx, String fpid, String sid, String uid,
                                       Callback done) {
        roleAction(ctx, fpid, sid, uid, ACTION_PAUSE, done);
    }

    /**
     * 角色重命名（P1 §二.3，op {@code rename}，payload {@code name}）：
     * 服务端调协议改游戏内昵称（道具不足自动 400 钻石），响应
     * {@code {name, usedDiamond, diamondCost, itemId}}。回调在 OkHttp 线程。
     */
    public static void roleActionRename(Context ctx, String fpid, String sid, String uid,
                                        String name, Callback done) {
        roleActionWithPayload(ctx, fpid, sid, uid, "rename", p -> p.put("name", name),
                done, "vault-role-rename");
    }

    /**
     * 移除角色（P1 §二.4，op {@code remove}）：仅从服务端工作区移除（不删除游戏内
     * 角色），先停会话；响应 {@code message}「角色已移除（游戏内角色未删除）」。
     * 回调在 OkHttp 线程。
     */
    public static void roleActionRemove(Context ctx, String fpid, String sid, String uid,
                                        Callback done) {
        roleActionWithPayload(ctx, fpid, sid, uid, "remove", null, done, "vault-role-remove");
    }

    /**
     * 启用/收纳角色（P1 §二.5，op {@code enabled}，payload {@code enabled}）：
     * 停用（收纳）时服务端停止会话；响应 {@code message}「已启用」/「已收纳」。
     * 回调在 OkHttp 线程。
     */
    public static void roleActionEnabled(Context ctx, String fpid, String sid, String uid,
                                         boolean enabled, Callback done) {
        roleActionWithPayload(ctx, fpid, sid, uid, "enabled", p -> p.put("enabled", enabled),
                done, "vault-role-enabled");
    }

    /**
     * 顶号恢复设置（P1 §二.6，account-action op {@code recovery-settings}）：
     * 账号级字段（作用于全部角色）；UI 端已按 {@link RecoverySettings#validate}
     * 预校验，服务端 3002 文案兜底。响应 {@code {message, recovery}}。回调在 OkHttp 线程。
     */
    public static void updateRecoverySettings(Context ctx, String fpid, RecoverySettings s,
                                              Callback done) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app) || fpid == null || fpid.isEmpty()) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = commonFields(app);
        f.put("fpid", fpid);
        f.put("action", "recovery-settings");
        f.put("enabled", s.enabled);
        f.put("initialDelayMinutes", s.initialDelayMinutes);
        f.put("retryIntervalSeconds", s.retryIntervalSeconds);
        f.put("maxAttempts", s.maxAttempts);
        f.put("cooldownMinutes", s.cooldownMinutes);
        new Thread(() -> post(app, PATH_ACCOUNT_ACTION, OP_ACCOUNT_ACTION, f, done),
                "vault-recovery-settings").start();
    }

    /** role-action 带扩展 payload 的公共通道（action 与 payload 键平铺进签名表）。 */
    private interface PayloadFiller {
        void fill(Map<String, Object> f);
    }

    private static void roleActionWithPayload(Context ctx, String fpid, String sid, String uid,
                                              String action, PayloadFiller payload,
                                              Callback done, String threadName) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app) || fpid == null || fpid.isEmpty()) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = commonFields(app);
        f.put("fpid", fpid);
        f.put("sid", sid == null ? "" : sid);
        f.put("uid", uid == null ? "" : uid);
        f.put("action", action);
        if (payload != null) payload.fill(f);
        new Thread(() -> post(app, PATH_ROLE_ACTION, OP_ROLE_ACTION, f, done),
                threadName).start();
    }

    // ---------- 战争通知（2026-10-22：监视名单 / 通知配置 / 事件游标） ----------

    /** 战争通知 op 通道（action 与字段平铺进签名表；非服务器模式回调 server_mode_off）。 */
    private static void watchPost(Context ctx, Map<String, Object> fields, Callback done,
                                  String threadName) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app)) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        new Thread(() -> post(app, PATH_WATCH, OP_WATCH, fields, done), threadName).start();
    }

    private static Map<String, Object> watchFields(String action, String fpid) {
        Map<String, Object> f = new TreeMap<>();
        f.put("action", action);
        f.put("fpid", fpid == null ? "" : fpid);
        return f;
    }

    /** 监视名单 + 全局通知配置（list）。回调在 OkHttp 线程。 */
    public static void watchList(Context ctx, String fpid, Callback done) {
        watchPost(ctx, watchFields("list", fpid), done, "vault-watch-list");
    }

    /** 新增监视目标（uid 必填；name/kid/note 可选）。回调在 OkHttp 线程。 */
    public static void watchAdd(Context ctx, String fpid, String uid, String name,
                                String kid, String note, Callback done) {
        Map<String, Object> f = watchFields("add", fpid);
        f.put("uid", uid == null ? "" : uid);
        f.put("name", name == null ? "" : name);
        f.put("kid", kid == null ? "" : kid);
        f.put("note", note == null ? "" : note);
        watchPost(ctx, f, done, "vault-watch-add");
    }

    /** 更新监视目标（null 字段不变；enabled null=不变）。回调在 OkHttp 线程。 */
    public static void watchUpdate(Context ctx, String fpid, String uid, String name,
                                   String kid, String note, Boolean enabled, Callback done) {
        Map<String, Object> f = watchFields("update", fpid);
        f.put("uid", uid == null ? "" : uid);
        if (name != null) f.put("name", name);
        if (kid != null) f.put("kid", kid);
        if (note != null) f.put("note", note);
        if (enabled != null) f.put("enabled", enabled);
        watchPost(ctx, f, done, "vault-watch-update");
    }

    /** 删除监视目标。回调在 OkHttp 线程。 */
    public static void watchRemove(Context ctx, String fpid, String uid, Callback done) {
        Map<String, Object> f = watchFields("remove", fpid);
        f.put("uid", uid == null ? "" : uid);
        watchPost(ctx, f, done, "vault-watch-remove");
    }

    /**
     * 全局通知配置（enabled/wecomEnabled/webhookUrl/cooldownSeconds/quietStart/quietEnd；
     * 传 null 的字段服务端保持旧值）。回调在 OkHttp 线程。
     */
    public static void watchConfig(Context ctx, Boolean enabled, Boolean wecomEnabled,
                                   String webhookUrl, Integer cooldownSeconds,
                                   Integer quietStart, Integer quietEnd, Callback done) {
        Map<String, Object> f = watchFields("config", "");
        if (enabled != null) f.put("enabled", enabled);
        if (wecomEnabled != null) f.put("wecomEnabled", wecomEnabled);
        if (webhookUrl != null) f.put("webhookUrl", webhookUrl);
        if (cooldownSeconds != null) f.put("cooldownSeconds", cooldownSeconds);
        if (quietStart != null) f.put("quietStart", quietStart);
        if (quietEnd != null) f.put("quietEnd", quietEnd);
        watchPost(ctx, f, done, "vault-watch-config");
    }

    /** 测试通知（服务端向企业微信群发一条测试消息，data.ok=是否送达）。回调在 OkHttp 线程。 */
    public static void watchTestNotify(Context ctx, Callback done) {
        watchPost(ctx, watchFields("test", ""), done, "vault-watch-test");
    }

    /** 事件游标同步：cursor 之后的新事件 + ackCursor。回调在 OkHttp 线程。 */
    public static void fetchEvents(Context ctx, long cursor, Callback done) {
        final Context app = ctx.getApplicationContext();
        if (!isServerMode(app)) {
            if (done != null) done.onResult(-1, "server_mode_off", null);
            return;
        }
        Map<String, Object> f = new TreeMap<>();
        f.put("cursor", cursor);
        new Thread(() -> post(app, PATH_EVENTS, OP_EVENTS, f, done), "vault-events").start();
    }

    /** 上次成功的账号池响应（UI 渲染读；无缓存/失败保留旧值，配合 {@link AccountPoolCache#isStale}）。 */
    public static JSONObject cachedAccountPool(Context ctx) {
        return AccountPoolCache.load(ctx);
    }

    /** 上次账号池请求是否失败（stale 横幅开关；unsupported 时不显示横幅）。 */
    public static boolean isPoolStale(Context ctx) {
        return AccountPoolCache.isStale(ctx);
    }

    /** 服务端是否没有池接口（生产 v4.1.4 降级：UI 直接 legacy 渲染，不显示失败横幅）。 */
    public static boolean isPoolUnsupported(Context ctx) {
        return AccountPoolCache.isUnsupported(ctx);
    }

    // ---------- 状态缓存（UI 读） ----------

    /** 服务器回传的账号状态（无缓存/该号缺项返回 null）。 */
    public static JSONObject cachedStatusOf(Context ctx, String fpid) {
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("status_cache", "");
            if (raw.isEmpty()) return null;
            JSONObject cache = new JSONObject(raw);
            JSONObject o = cache.optJSONObject(fpid);
            return o != null && o.has("state") ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 服务器态快捷取用：online/recovering/takeover/need_token/offline；未同步过返回 null。 */
    public static String cachedStateOf(Context ctx, String fpid) {
        JSONObject o = cachedStatusOf(ctx, fpid);
        return o == null ? null : o.optString("state", null);
    }

    // ---------- 组装 ----------

    /** 身份库全量 payload：账号 + token + 指纹（账号级/角色级）+ keep/auto/窗口 + 任务配置与参数。 */
    static Map<String, Object> syncFields(Context ctx) {
        Map<String, Object> f = commonFields(ctx);
        JSONArray accounts = new JSONArray();
        try {
            AccountStore store = new AccountStore(ctx);
            com.carlos.vault.daily.DailyConfigStore cfgStore =
                    new com.carlos.vault.daily.DailyConfigStore(ctx);
            for (AccountStore.Account a : store.all()) {
                if (a == null || a.fpid == null || a.fpid.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("fpid", a.fpid);
                o.put("token", a.token == null ? "" : a.token);
                o.put("label", a.label == null ? "" : a.label);
                o.put("group", a.group == null ? "" : a.group);
                // 统一自动化：keep（服务器常驻会话）与 auto（服务器整轮调度）必须同值，
                // 否则旧数据会出现“有开关但没有会话/不跑整轮”的半开状态。
                boolean automation = RoleStore.automationEnabled(ctx, a.fpid);
                o.put("keep", automation);
                // 当前角色 = 复合身份 (sid, uid)：同服多角色时服务器只拿 sid 无法区分登哪个角色
                RoleStore.ActiveRole active = RoleStore.activeRoleEx(ctx, a.fpid);
                o.put("activeSid", active.sid > 0 ? String.valueOf(active.sid) : "");
                o.put("activeUid", active.uid > 0 ? String.valueOf(active.uid) : "");
                String identity = store.getSettings(a.fpid);
                o.put("identity", identity == null ? "" : identity);
                // 统一自动化与时间窗（服务器整轮调度依据）
                o.put("auto", automation);
                int[] win = RoleStore.window(ctx);
                o.put("windowStart", win[0]);
                o.put("windowEnd", win[1]);
                JSONArray roles = new JSONArray();
                for (RoleStore.Role r : RoleStore.roles(ctx, a.fpid)) {
                    if (r.serverid <= 0) continue;
                    String rid = store.getRoleSettings(a.fpid, r.serverid);
                    // 任务开关（服务器按角色执行整轮的依据）
                    Map<String, Boolean> cfg = cfgStore.roleConfig(a.fpid, r.serverid);
                    // 角色行 JSON 由纯函数组装（M2b 新增 name/level/power/enabled 账号池
                    // 元数据；旧服务器忽略未知字段，前向兼容；旧字段键序/格式不变）
                    JSONObject ro = new JSONObject(roleFields(r, rid, cfg));
                    // 任务参数（策略/子项开关等）
                    JSONObject prm = new JSONObject();
                    for (com.carlos.vault.daily.FnRegistry.Fn fn
                            : com.carlos.vault.daily.FnRegistry.all()) {
                        JSONObject p = com.carlos.vault.daily.FnParams
                                .params(ctx, a.fpid, r.serverid, fn.id);
                        if (p.length() > 0) prm.put(fn.id, p);
                    }
                    // 批次10/11：统一编队库作为角色保留参数随 params 上传（字符串 JSON）。
                    // 服务器 FormationResolver.libraryFor 的读取顺序是「任务自身 params →
                    // tc.params("formation") → tc.params("daily")」，角色顶层保留键不直接命中；
                    // 故同时写伪功能参数 "formation"（服务端全局库回落点，guardian/bear/behemoth
                    // 与采集/情报/狩猎共用）。仅新增键，不改现有 params 结构；无编队不上传。
                    String formLib = com.carlos.vault.daily.FormationStore
                            .libraryJsonForSync(ctx, a.fpid, r.serverid);
                    if (formLib != null && !formLib.isEmpty()) {
                        prm.put(com.carlos.vault.daily.FormationStore.KEY_LIBRARY, formLib);
                        JSONObject globalFormation = new JSONObject();
                        globalFormation.put(com.carlos.vault.daily.FormationStore.KEY_LIBRARY,
                                formLib);
                        prm.put("formation", globalFormation);
                    }
                    if (prm.length() > 0) ro.put("params", prm);
                    roles.put(ro);
                }
                o.put("roles", roles);
                accounts.put(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "payload 组装异常: " + t);
        }
        f.put("accountsJson", accounts.toString());
        f.put("count", accounts.length());
        return f;
    }

    /**
     * 单角色行 JSON 字段组装（M2b 自 {@link #syncFields} 抽出的纯函数，纯 JVM 可测）：
     * 返回 LinkedHashMap 保持键序，调用方经 JSONObject(Map) 包装后入 accountsJson
     * （config 子 Map 由 JSONObject(Map).wrap 包成 JSONObject，线上格式不变）。
     *
     * <p>键序：sid/uid 在前、identity/config 收尾（旧字段相对顺序不变），新增账号池
     * 元数据 name/level/power/enabled 插在 uid 之后（M1 服务端 /v1/vault/accounts 的
     * 显示名/等级/战力/是否启用与 enabled 批量动作依赖；旧服务端忽略未知字段，前向
     * 兼容）。空值兜底：name/identity null → ""，uid≤0 → ""（与服务端字符串口径
     * 一致），config 为 null 不输出键；params 需 FnParams 按 fn 读盘，仍由调用方另行 put。
     */
    static Map<String, Object> roleFields(RoleStore.Role r, String identity,
                                          Map<String, Boolean> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sid", String.valueOf(r.serverid));
        m.put("uid", r.uid > 0 ? String.valueOf(r.uid) : "");
        m.put("name", r.name == null ? "" : r.name);
        m.put("level", r.level);
        m.put("power", r.power);
        m.put("enabled", r.enabled);
        m.put("identity", identity == null ? "" : identity);
        if (config != null) m.put("config", config);
        return m;
    }

    private static Map<String, Object> commonFields(Context ctx) {
        Map<String, Object> f = new TreeMap<>();
        f.put("pkg", ctx.getPackageName());
        f.put("appVersion", "vault-sync-1");
        return f;
    }

    // ---------- HTTP（签名 + 信封） ----------

    public interface Callback {
        void onResult(int code, String message, JSONObject data);
    }

    private static void post(Context app, String path, String op, Map<String, Object> fields,
                             Callback cb) {
        try {
            String body = buildSignedBody(op, fields);
            String base = resolveBaseUrl(app);
            OkHttpClient client = base.startsWith("https://" + PROD_HOST) ? HTTP_PINNED : HTTP;
            Request request = new Request.Builder()
                    .url(base + path)
                    .post(RequestBody.create(JSON, body))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                JSONObject envelope;
                try {
                    envelope = new JSONObject(text);
                } catch (Throwable t2) {
                    // 非 JSON 响应（旧服务端 404 HTML 等）：把 HTTP 码带进 message，
                    // 供上层把「接口不存在」（unsupported）与网络故障（stale）区分开
                    Log.w(TAG, path + " 非 JSON 响应 http=" + response.code());
                    if (cb != null) cb.onResult(-1,
                            "HTTP " + response.code() + " " + t2.getMessage(), null);
                    return;
                }
                int code = envelope.optInt("code", -1);
                String message = envelope.optString("message", "");
                JSONObject data = envelope.optJSONObject("data");
                if (code != 0) {
                    Log.w(TAG, path + " 业务失败 code=" + code + " " + message);
                }
                if (cb != null) cb.onResult(code, message, data);
            }
        } catch (Throwable t) {
            Log.w(TAG, path + " 网络失败: " + t.getMessage());
            if (cb != null) cb.onResult(-1, String.valueOf(t.getMessage()), null);
        }
    }

    /** 与服务端 ApiSign.computeSign 同式（v1 前缀，OP 不含 /v1 前缀）。 */
    private static String buildSignedBody(String op, Map<String, Object> fields) {
        Map<String, Object> sorted = new TreeMap<>(fields);
        long ts = System.currentTimeMillis();
        String nonce = LicenseCrypto.sha256Hex(String.valueOf(System.nanoTime())
                + String.valueOf(Math.random())).substring(0, 32);
        sorted.put("ts", ts);
        sorted.put("nonce", nonce);
        String canonical = LicenseCrypto.writeCanonical(sorted);
        String payload = "v1\nPOST\n" + op + "\n" + ts + "\n" + nonce + "\n"
                + LicenseCrypto.sha256Hex(canonical);
        sorted.put("sign", LicenseCrypto.hmacSha256Hex(appSecret(), payload));
        return LicenseCrypto.writeCanonical(sorted);
    }

    static String appSecret() {
        try {
            byte[] cipher = Base64.decode(SECRET_OBF, Base64.DEFAULT);
            byte[] plain = new byte[cipher.length];
            for (int i = 0; i < cipher.length; i++) {
                plain[i] = (byte) (cipher[i] ^ SECRET_XOR_KEY[i % SECRET_XOR_KEY.length]);
            }
            return new String(plain, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 端点解析：filesDir/vault_endpoint.txt 覆盖（adb run-as 写入，联调用）→ 默认同授权域。
     */
    static String resolveBaseUrl(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "vault_endpoint.txt");
            if (f.exists() && f.isFile() && f.length() > 0 && f.length() < 512) {
                byte[] buf = new byte[(int) f.length()];
                InputStream in = new FileInputStream(f);
                try {
                    int n = in.read(buf);
                    if (n > 0) {
                        String s = new String(buf, 0, n, "UTF-8").trim();
                        if (s.startsWith("https://") || s.startsWith("http://")) {
                            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
                            return s;
                        }
                    }
                } finally {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_BASE_URL;
    }
}
