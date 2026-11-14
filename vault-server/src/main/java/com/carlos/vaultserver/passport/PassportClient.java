package com.carlos.vaultserver.passport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * FunSDK passport 客户端：表单组装 → FP 签名 → cg-seid → Transport POST → 响应解析。
 *
 * <p>四个业务通道：发码 {@link #sendSms(String, String, DeviceProfile)}（60 秒/号冷却）、
 * 验证码登录 {@link #smsLogin(String, String, DeviceProfile)}、游客注册
 * {@link #guestRegister(DeviceProfile)}、实名 {@link #realName(String, String, String, String)}。
 *
 * <p>线程语义：无状态可并发（冷却表并发安全）；Transport 由构造注入，
 * 单测用假实现，生产用 {@link HttpUrlConnectionTransport}。
 * 敏感值（gameKey/验证码/身份证号）不出现在任何异常消息里。
 */
public final class PassportClient {

    /** 发码冷却：同号两次 sendSms 至少间隔 60 秒（对齐原版 OfficialLoginService）。 */
    public static final long SMS_SEND_COOLDOWN_MILLIS = 60_000L;

    private final PassportConfig config;
    private final Transport transport;
    private final ConcurrentMap<String, Long> smsSendAvailableAt = new ConcurrentHashMap<>();

    /** HTTP 传输抽象：POST url，返回响应体文本；网络错误抛 IOException。 */
    public interface Transport {
        String post(String url, Map<String, String> headers, Map<String, String> form) throws IOException;
    }

    public PassportClient(PassportConfig config, Transport transport) {
        this.config = config == null ? PassportConfig.defaults() : config;
        this.transport = transport;
    }

    // ---------------- 业务通道 ----------------

    /**
     * 发送短信验证码。冷却 key = country_code:mobile（纯数字）；
     * 冷却期内抛 {@code IllegalArgumentException("验证码已发送，请 N 秒后再试")}；
     * 传输失败时释放冷却预约，允许立即重试。
     */
    public PassportResult sendSms(String mobile, String countryCode, DeviceProfile profile) {
        String cc = (countryCode == null || countryCode.replaceAll("\\D", "").isEmpty())
                ? "86" : countryCode.replaceAll("\\D", "");
        String number = PassportIds.normalizeMobile(mobile);
        if (number.isEmpty()) {
            throw new IllegalArgumentException("缺少手机号 mobile");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mobile", number);
        params.put("length", "4");
        params.put("fpid", "");
        params.put("session_key", "");
        params.put("country_code", cc);
        String key = cc + ":" + number;
        SmsReservation reservation = reserveSmsSend(key);
        PassportResult result;
        try {
            result = call(config.smsSendMethod, params, profile);
        } catch (RuntimeException t) {
            cancelSmsSend(reservation);
            throw t;
        }
        return result;
    }

    /** 手机号验证码登录：mobile + code → fpid + session_key。 */
    public PassportResult smsLogin(String mobile, String code, DeviceProfile profile) {
        String number = PassportIds.normalizeMobile(mobile);
        if (number.isEmpty()) {
            throw new IllegalArgumentException("缺少手机号 mobile");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少验证码 code");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mobile", number);
        params.put("code", code.trim());
        params.put("session_key", "");
        params.put("android_id", profile == null ? "" : profile.androidId);
        params.put("install_channelid", "funplus");
        return call(config.smsLoginMethod, params, profile);
    }

    /** 游客快速注册：仅公共参数 + 设备指纹，返回 account/fpid + sdkToken。 */
    public PassportResult guestRegister(DeviceProfile profile) {
        return call(config.guestRegisterMethod, new LinkedHashMap<>(), profile);
    }

    /**
     * 实名认证。本地前置校验：姓名非空、身份证 18 位格式；
     * 不合法抛 IllegalArgumentException（不外呼）。
     */
    public PassportResult realName(String fpid, String sessionKey, String name, String idCard) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入姓名");
        }
        if (!PassportIds.isValidIdCard(idCard)) {
            throw new IllegalArgumentException("身份证号格式不正确");
        }
        if (fpid == null || fpid.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 fpid");
        }
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 session_key");
        }
        Map<String, String> params = new LinkedHashMap<>();
        // 新版 PhoneRegistrationPassportGateway.verifyRealName 的权威载荷：
        // {check_way:"2", name, id_card, fpid, session_key}
        params.put("check_way", "2");
        params.put("name", name.trim());
        params.put("id_card", idCard.trim());
        params.put("fpid", fpid.trim());
        params.put("session_key", sessionKey.trim());
        return call(config.realNameMethod, params, null);
    }

    // ---------------- 核心调用 ----------------

    /**
     * 组表单（LinkedHashMap 保序：method 首位，公共字段随后，业务参数最后）→
     * 签名（FP gameId:base64(hex(HMAC-SHA256(字典序 k=v&…, gameKey)))）→
     * cg-seid 头（可选）→ Transport POST → 解析 {@link PassportResult}。
     */
    public PassportResult call(String serviceName, Map<String, String> businessParams, DeviceProfile profile) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("method", serviceName);
        form.put("game_id", config.gameId);
        // 真实客户端固定上传 "passport-request"（不是 method 名）；传错会得到 error=5 未知错误
        form.put("service_name", config.serviceName);
        form.put("l", config.language);
        form.put("game_version", config.gameVersion);
        form.put("domain", "funsdk");
        form.put("sdk_version", config.sdkVersion);
        form.put("origin_address", config.originAddress);
        if (profile != null) {
            form.put("guid", profile.guid);
            form.put("origin_guid", profile.originGuid);
            form.put("android_id", profile.androidId);
        }
        if (businessParams != null) {
            for (Map.Entry<String, String> e : businessParams.entrySet()) {
                if (e.getKey() == null) continue;
                String v = e.getValue();
                if (v == null || v.trim().isEmpty()) continue;
                form.put(e.getKey(), v);
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", sign(form));

        String url = config.baseUrl + config.path;
        String body;
        try {
            Map<String, String> sentHeaders = headers;
            String cgSeid = config.resolveCgSeid();
            if (!cgSeid.isEmpty()) {
                sentHeaders = new LinkedHashMap<>(headers);
                sentHeaders.put("cg-seid", cgSeid);
            }
            body = transport.post(url, sentHeaders, form);
        } catch (IOException t) {
            throw new PassportException("passport 网络失败: " + t.getMessage(), t);
        }
        return PassportResult.parse(body);
    }

    /** 签名：与 App 侧 WechatQrLogin.signPassportJava 逐字同构（黄金值单测锚定）。 */
    String sign(Map<String, String> params) {
        StringBuilder src = new StringBuilder();
        for (String key : new TreeSet<>(params.keySet())) {
            if (src.length() > 0) src.append('&');
            src.append(key).append('=').append(params.get(key));
        }
        String hex = PassportIds.hmacSha256Hex(src.toString(), config.gameKey);
        return "FP " + config.gameId + ":" + PassportIds.base64(hex.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- 发码冷却 ----------------

    private static final class SmsReservation {
        final String key;
        final long availableAtMillis;

        SmsReservation(String key, long availableAtMillis) {
            this.key = key;
            this.availableAtMillis = availableAtMillis;
        }
    }

    private SmsReservation reserveSmsSend(String key) {
        long now = System.currentTimeMillis();
        long availableAt = now + SMS_SEND_COOLDOWN_MILLIS;
        AtomicBoolean reserved = new AtomicBoolean(false);
        long[] remaining = {0L};
        smsSendAvailableAt.compute(key, (ignored, nextAvailableAt) -> {
            if (nextAvailableAt != null && now < nextAvailableAt) {
                remaining[0] = Math.max(1L, (nextAvailableAt - now + 999L) / 1000L);
                return nextAvailableAt;
            }
            reserved.set(true);
            return availableAt;
        });
        if (!reserved.get()) {
            throw new IllegalArgumentException("验证码已发送，请 " + remaining[0] + " 秒后再试");
        }
        return new SmsReservation(key, availableAt);
    }

    private void cancelSmsSend(SmsReservation reservation) {
        smsSendAvailableAt.remove(reservation.key, reservation.availableAtMillis);
    }

    // ---------------- 响应 ----------------

    /** passport 响应：data 层（root.data 或 root 本身）+ fpid/session_key 两层提取 + 错误文本。 */
    public static final class PassportResult {
        public final String raw;
        public final JSONObject data;
        /** 外层响应（root），保留以支持 status/code 的双层口径（P1-1）。 */
        private final JSONObject root;

        private PassportResult(String raw, JSONObject root, JSONObject data) {
            this.raw = raw == null ? "" : raw;
            this.root = root;
            this.data = data;
        }

        static PassportResult parse(String body) {
            String text = body == null ? "" : body.trim();
            JSONObject root;
            try {
                root = new JSONObject(text.isEmpty() ? "{}" : text);
            } catch (Throwable t) {
                String preview = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                throw new PassportException("passport 响应非 JSON: " + preview, t);
            }
            JSONObject data = root.optJSONObject("data");
            return new PassportResult(text, root, data != null ? data : root);
        }

        /** fpid：data 或 data.login_profile 层按序取 fpid|account|fp_id 第一个非空。 */
        public String fpid() {
            return firstNonBlank("fpid", "account", "fp_id");
        }

        /** session_key：同两层按序取 session_key|sdkToken|sdk_token。 */
        public String sessionKey() {
            return firstNonBlank("session_key", "sdkToken", "sdk_token");
        }

        /** 上游错误文本：data/root 层按序取 msg|error|error_msg|message（实测错误在 data.msg）。 */
        public String errorText() {
            String v = layerValue(data, "msg", "error", "error_msg", "message");
            if (v == null && data != null) {
                JSONObject lp = data.optJSONObject("login_profile");
                if (lp != null) v = layerValue(lp, "msg", "error", "error_msg", "message");
            }
            return v == null ? "" : v;
        }

        /**
         * 上游业务状态：实测成功响应为 {@code {"status":1,...}}，失败为 {@code {"status":0,"error":N}}。
         * 缺失 status（如被 gate 拦下的非 JSON 包装）时返回 -1，由调用方按 fpid/sessionKey 是否为空判定。
         *
         * @deprecated 只看 data 层，等价于 {@link #outerStatus()} 在无 data 层时的退化；
         *             实名判定请用 {@link #outerStatus()} + {@link #dataCode()}（P1-1）。
         */
        @Deprecated
        public int status() {
            if (data == null) return -1;
            return data.optInt("status", -1);
        }

        /** 上游业务错误码（data.error）；缺失返回 -1。 */
        public int errorCode() {
            if (data == null) return -1;
            return data.optInt("error", -1);
        }

        /**
         * 外层业务状态（root.status，P1-1：实测实名响应为 {@code {"status":1,"data":{"code":2,...}}}）。
         * 缺失返回 -1。
         */
        public int outerStatus() {
            return root == null ? -1 : root.optInt("status", -1);
        }

        /**
         * data 层业务码（data.code，P1-1）。网页版 PhoneRegistrationPassportGateway 读同一位置；
         * 无 data 层时退化为 root.code；缺失返回 -1。
         */
        public int dataCode() {
            if (data == null) return -1;
            return data.optInt("code", -1);
        }

        /** 原文摘要（≤300 字符），用于日志。 */
        public String rawSummary() {
            return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
        }

        private String firstNonBlank(String... keys) {
            if (data == null) return "";
            String v = layerValue(data, keys);
            if (v == null) {
                JSONObject lp = data.optJSONObject("login_profile");
                if (lp != null) v = layerValue(lp, keys);
            }
            return v == null ? "" : v;
        }

        private static String layerValue(JSONObject o, String... keys) {
            for (String k : keys) {
                String v = o.optString(k, "");
                if (v != null && !v.trim().isEmpty() && !"null".equals(v)) return v.trim();
            }
            return null;
        }
    }

    // ---------------- 生产 Transport ----------------

    /** HttpURLConnection 实现：application/x-www-form-urlencoded、UTF-8、10s 连接/15s 读超时。 */
    public static final class HttpUrlConnectionTransport implements Transport {
        private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
        private static final int READ_TIMEOUT_MILLIS = 15_000;

        @Override
        public String post(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                conn.setReadTimeout(READ_TIMEOUT_MILLIS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                byte[] payload = encodeForm(form).getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
                int code = conn.getResponseCode();
                String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code >= 400) {
                    throw new IOException("HTTP " + code);
                }
                return body;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static String encodeForm(Map<String, String> form) {
            StringBuilder sb = new StringBuilder();
            if (form != null) {
                for (Map.Entry<String, String> e : form.entrySet()) {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
                }
            }
            return sb.toString();
        }

        private static String urlEncode(String s) {
            try {
                return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
            } catch (java.io.UnsupportedEncodingException t) {
                return s == null ? "" : s;
            }
        }

        private static String readAll(java.io.InputStream in) throws IOException {
            if (in == null) return "";
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
