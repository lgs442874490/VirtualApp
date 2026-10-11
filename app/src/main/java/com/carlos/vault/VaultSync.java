package com.carlos.vault;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

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
    private static final String OP_SYNC = "vaultSync";
    private static final String OP_TOKEN = "vaultToken";
    private static final String OP_STATUS = "vaultStatus";
    private static final String OP_STATE = "vaultState";
    private static final String OP_POLICY = "vaultPolicy";

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

    // ---------- 业务接口 ----------

    /** 全量同步身份库（账号/token/指纹/keep/active 角色）。异步、失败静默。 */
    public static void syncAll(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> post(app, PATH_SYNC, OP_SYNC, syncFields(app), null), "vault-sync").start();
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
        Map<String, Object> f = commonFields(app);
        new Thread(() -> post(app, PATH_STATUS, OP_STATUS, f,
                (code, message, data) -> {
                    if (code == 0 && data != null) {
                        cacheAccounts(app, data.optJSONArray("accounts"));
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
        Map<String, Object> f = commonFields(app);
        new Thread(() -> post(app, PATH_STATE, OP_STATE, f,
                (code, message, data) -> {
                    if (code == 0 && data != null) {
                        cacheAccounts(app, data.optJSONArray("accounts"));
                        JSONObject states = data.optJSONObject("states");
                        if (states != null && states.length() > 0) {
                            writeDailyState(app, states);
                            Log.i(TAG, "采集状态回写 " + states.length() + " 键");
                        }
                    }
                    if (done != null) done.onResult(code, message, data);
                }), "vault-state").start();
    }

    private static void cacheAccounts(Context app, JSONArray accounts) {
        if (accounts == null) return;
        try {
            JSONObject cache = new JSONObject();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject o = accounts.getJSONObject(i);
                cache.put(o.optString("fpid", ""), o);
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("status_cache", cache.toString())
                    .putLong("status_at", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
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
                o.put("keep", RoleStore.keepOnline(ctx, a.fpid));
                long active = RoleStore.activeRole(ctx, a.fpid);
                o.put("activeSid", active > 0 ? String.valueOf(active) : "");
                String identity = store.getSettings(a.fpid);
                o.put("identity", identity == null ? "" : identity);
                // 自动日常与时间窗（服务器整轮调度依据）
                RoleStore.Schedule sched = RoleStore.schedule(ctx, a.fpid);
                o.put("auto", sched.autoEnabled);
                int[] win = RoleStore.window(ctx);
                o.put("windowStart", win[0]);
                o.put("windowEnd", win[1]);
                JSONArray roles = new JSONArray();
                for (RoleStore.Role r : RoleStore.roles(ctx, a.fpid)) {
                    if (r.serverid <= 0) continue;
                    JSONObject ro = new JSONObject();
                    ro.put("sid", String.valueOf(r.serverid));
                    String rid = store.getRoleSettings(a.fpid, r.serverid);
                    ro.put("identity", rid == null ? "" : rid);
                    // 任务开关（服务器按角色执行整轮的依据）
                    Map<String, Boolean> cfg = cfgStore.roleConfig(a.fpid, r.serverid);
                    if (cfg != null) {
                        JSONObject cm = new JSONObject();
                        for (Map.Entry<String, Boolean> ce : cfg.entrySet()) {
                            cm.put(ce.getKey(), ce.getValue());
                        }
                        ro.put("config", cm);
                    }
                    // 任务参数（策略/子项开关等）
                    JSONObject prm = new JSONObject();
                    for (com.carlos.vault.daily.FnRegistry.Fn fn
                            : com.carlos.vault.daily.FnRegistry.all()) {
                        JSONObject p = com.carlos.vault.daily.FnParams
                                .params(ctx, a.fpid, r.serverid, fn.id);
                        if (p.length() > 0) prm.put(fn.id, p);
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
                JSONObject envelope = new JSONObject(text);
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
