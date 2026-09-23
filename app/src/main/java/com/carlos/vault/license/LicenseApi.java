package com.carlos.vault.license;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 授权服务端 HTTP 客户端。
 *
 * 刻意不使用 Retrofit / Gson：release 构建开启 <code>minifyEnabled</code>，
 * 而本工程缺少 proguard 规则文件，注解/反射模型在混淆下极易失效。
 * 这里用 okhttp + <code>org.json</code> 手工序列化，零反射、零新增依赖。
 *
 * 所有请求均为异步（{@code enqueue}），绝不阻塞调用线程 —— 这一点是硬要求：
 * {@link LicenseManager#gateForLaunch} 会在 UI 线程被调用，但那个路径完全不碰本类。
 */
public class LicenseApi {

    private static final MediaType JSON =
            MediaType.parse("application/json; charset=utf-8");

    /** 单次请求的结果。 */
    public static class ApiResult {
        /** 业务码（LicenseConfig.CODE_*）。网络失败时为 CODE_SERVER_ERROR。 */
        public final int code;
        /** 服务端 message（网络失败时为异常摘要）。 */
        public final String message;
        /** 响应 data 对象，可能为 null。 */
        public final JSONObject data;
        /** 服务端时间（epoch ms），为 0 表示不可用。 */
        public final long serverTime;
        /** 是否为网络层失败（连接/超时/DNS），而非业务失败。 */
        public final boolean networkError;

        ApiResult(int code, String message, JSONObject data, long serverTime, boolean networkError) {
            this.code = code;
            this.message = message;
            this.data = data;
            this.serverTime = serverTime;
            this.networkError = networkError;
        }

        static ApiResult net(String msg) {
            return new ApiResult(LicenseConfig.CODE_SERVER_ERROR,
                    msg == null ? "网络异常" : msg, null, 0L, true);
        }

        static ApiResult biz(int code, String message, JSONObject data, long serverTime) {
            return new ApiResult(code, message, data, serverTime, false);
        }

        public boolean ok() {
            return !networkError && code == LicenseConfig.CODE_OK;
        }
    }

    /** 异步回调。始终在主线程之外被调用的可能性存在，调用方需自行切线程。 */
    public interface ApiCallback {
        void onResult(ApiResult result);
    }

    private final Context ctx;
    private final OkHttpClient client;
    private final String baseUrl;

    /** 本机时钟相对服务端的偏移（ms），由 LicenseManager 在校验成功后更新，用于修正 ts。 */
    private volatile long clockOffsetMs = 0L;

    public LicenseApi(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.baseUrl = resolveBaseUrl(this.ctx);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(LicenseConfig.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(LicenseConfig.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(LicenseConfig.HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void setClockOffsetMs(long offsetMs) {
        this.clockOffsetMs = offsetMs;
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ---------- 基址解析 ----------

    /**
     * 解析服务端基址。
     *
     * 生产走 {@link LicenseConfig#BASE_URL}（强制 HTTPS）；
     * 调试期可用 <code>filesDir/license_endpoint.txt</code> 覆盖为本地地址
     * （该文件只能由 <code>adb run-as</code> 写入，普通用户无法触及）。
     */
    public static String resolveBaseUrl(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), LicenseConfig.ENDPOINT_OVERRIDE_FILE);
            if (f.exists() && f.isFile() && f.length() > 0 && f.length() < 512) {
                byte[] buf = new byte[(int) f.length()];
                InputStream in = new FileInputStream(f);
                try {
                    int n = in.read(buf);
                    if (n > 0) {
                        String s = new String(buf, 0, n, "UTF-8").trim();
                        if (s.startsWith("https://") || s.startsWith("http://")) {
                            while (s.endsWith("/")) {
                                s = s.substring(0, s.length() - 1);
                            }
                            Log.i(LicenseConfig.LOG_TAG, "使用调试期端点覆盖: " + s);
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
        return LicenseConfig.BASE_URL;
    }

    // ---------- 业务接口 ----------

    /** 阶段一：取一次性盐与 challengeId。 */
    public void hello(ApiCallback cb) {
        Map<String, Object> f = commonFields();
        f.put("phase", "hello");
        post(LicenseConfig.PATH_ACTIVATE, LicenseConfig.OP_ACTIVATE, f, cb);
    }

    /** 阶段二：绑定设备并出票。 */
    public void activate(String code, String deviceHash, String salt, String challengeId,
                         String deviceLabel, ApiCallback cb) {
        Map<String, Object> f = commonFields();
        f.put("phase", "bind");
        f.put("code", code);
        f.put("deviceHash", deviceHash);
        f.put("deviceLabel", deviceLabel == null ? "" : deviceLabel);
        f.put("salt", salt);
        f.put("challengeId", challengeId);
        post(LicenseConfig.PATH_ACTIVATE, LicenseConfig.OP_ACTIVATE, f, cb);
    }

    /** 校验授权状态（服务端为权威）。 */
    public void verify(String code, String ticketId, String deviceHash, ApiCallback cb) {
        Map<String, Object> f = commonFields();
        f.put("code", code);
        f.put("ticketId", ticketId == null ? "" : ticketId);
        f.put("deviceHash", deviceHash);
        post(LicenseConfig.PATH_VERIFY, LicenseConfig.OP_VERIFY, f, cb);
    }

    /** 换绑到新设备（需运营密钥）。 */
    public void rebind(String code, String newDeviceHash, String adminToken,
                       String deviceLabel, String reason, ApiCallback cb) {
        Map<String, Object> f = commonFields();
        f.put("code", code);
        f.put("newDeviceHash", newDeviceHash);
        f.put("adminToken", adminToken == null ? "" : adminToken);
        f.put("deviceLabel", deviceLabel == null ? "" : deviceLabel);
        f.put("reason", reason == null ? "" : reason);
        post(LicenseConfig.PATH_REBIND, LicenseConfig.OP_REBIND, f, cb);
    }

    // ---------- 内部 ----------

    private Map<String, Object> commonFields() {
        Map<String, Object> f = new TreeMap<>();
        f.put("pkg", ctx.getPackageName());
        f.put("appVersion", appVersionName());
        f.put("appVersionCode", appVersionCode());
        return f;
    }

    private String appVersionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int appVersionCode() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 构造已签名请求体。
     *
     * 步骤：字段排序 → 加 ts/nonce → 规范化 → HMAC 签名 → 加 sign → 再规范化输出。
     * 最终发出的 body 与「去掉 sign 后的规范化串」是一一对应的，
     * 因此服务端重算 canonical 必然得到同一串。
     *
     * @param op 签名槽位 3 的操作名（不含 /v1 前缀），非 URL 路径
     */
    private String buildSignedBody(String op, Map<String, Object> fields) {
        Map<String, Object> sorted = new TreeMap<>(fields);
        long ts = correctedNow();
        String nonce = LicenseCrypto.newNonce();
        sorted.put("ts", ts);
        sorted.put("nonce", nonce);

        String canonical = LicenseCrypto.writeCanonical(sorted);
        String sign = LicenseCrypto.signRequest("POST", op, ts, nonce, canonical);
        sorted.put("sign", sign);

        return LicenseCrypto.writeCanonical(sorted);
    }

    /** 用服务端校验成功的时钟偏移修正本机时间，提升时间戳窗口校验的通过率。 */
    private long correctedNow() {
        return System.currentTimeMillis() + clockOffsetMs;
    }

    /**
     * @param path URL 路径（拼地址用，如 /v1/activate）
     * @param op   签名槽位 3 的操作名（如 activate），不含 /v1 前缀
     */
    private void post(final String path, final String op, Map<String, Object> fields, final ApiCallback cb) {
        final String body = buildSignedBody(op, fields);
        final String url = baseUrl + path;

        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, body))
                    .build();
        } catch (Throwable t) {
            Log.w(LicenseConfig.LOG_TAG, "构造请求失败: " + t.getMessage());
            cb.onResult(ApiResult.net(t.getMessage()));
            return;
        }

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                Log.w(LicenseConfig.LOG_TAG, "请求失败 " + path + ": " + e.getMessage());
                cb.onResult(ApiResult.net(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                String text = "";
                try {
                    if (response.body() != null) {
                        text = response.body().string();
                    }
                } catch (Throwable t) {
                    cb.onResult(ApiResult.net("读取响应失败"));
                    return;
                } finally {
                    try {
                        response.close();
                    } catch (Throwable ignored) {
                    }
                }

                try {
                    JSONObject o = new JSONObject(text);
                    int code = o.optInt("code", LicenseConfig.CODE_SERVER_ERROR);
                    String message = o.optString("message", "");
                    JSONObject data = o.optJSONObject("data");
                    long serverTime = o.optLong("serverTime", 0L);
                    cb.onResult(ApiResult.biz(code, message, data, serverTime));
                } catch (Throwable t) {
                    Log.w(LicenseConfig.LOG_TAG, "响应解析失败: " + text);
                    cb.onResult(ApiResult.net("响应格式异常"));
                }
            }
        });
    }

    /** 便于日志标注 UA 之用。 */
    public static String deviceLabel() {
        return DeviceId.label();
    }

    /** 当前 SDK，仅用于排障日志。 */
    public static int sdkInt() {
        return Build.VERSION.SDK_INT;
    }
}
