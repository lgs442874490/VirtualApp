package com.carlos.vaultserver.passport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FunSDK passport 接入配置（不可变）。
 *
 * <p>默认值与 App 侧 {@code WechatQrLogin}（passport 公开接入参数）一致；
 * guest/realName 两个 method 名为推定默认值，<b>待真实轮抓包校准</b>后可覆盖。
 */
public final class PassportConfig {

    public static final String DEFAULT_BASE_URL = "https://passport-web-gof.campfiregames.cn";
    public static final String DEFAULT_PATH = "/client_api.php?ver=6";
    public static final String DEFAULT_GAME_ID = "20173";
    /** 游戏客户端同源参数（非用户密钥）；日志与摘要中必须掩码。 */
    public static final String DEFAULT_GAME_KEY = "a1f3d1fbf975825d85f51d2f6ffb2ff1";
    public static final String DEFAULT_SDK_VERSION = "U8.0.5.0-GOFCN-1";
    public static final String DEFAULT_GAME_VERSION = "1.16.12";
    public static final String DEFAULT_LANGUAGE = "zh_CN";
    public static final String DEFAULT_ORIGIN_ADDRESS = "02:00:00:00:00:00";
    /**
     * form 里 {@code service_name} 的真实值：游戏 {@code RequestHelper.request} 固定上传
     * {@code "passport-request"}（<b>不是</b> method 名）。2026-11-10 dex 反编译+实测确认；
     * 传错会导致所有 business method 返回 {@code error=5 未知错误}。
     */
    public static final String DEFAULT_SERVICE_NAME = "passport-request";
    /** 发短信验证码：游戏 {@code sendCaptcha}（不是 sendSms）。实测成功返回 {"status":1,"data":{"result":1}}。 */
    public static final String DEFAULT_SMS_SEND_METHOD = "sendCaptcha";
    /** 手机号验证码登录。实测成功返回 status=1 + fpid/session_key。 */
    public static final String DEFAULT_SMS_LOGIN_METHOD = "loginWithPhoneCode";
    /** 游客快速注册：游戏 CGAccountType.Group.Express → express_signin。实测返回 is_new=true 新号。 */
    public static final String DEFAULT_GUEST_REGISTER_METHOD = "express_signin";
    /** 推定值：实名认证的 method，待真实轮校准（本地/未实名号实测用）。 */
    public static final String DEFAULT_REAL_NAME_METHOD = "realNameVerify";

    public final String baseUrl;
    public final String path;
    public final String gameId;
    public final String gameKey;
    public final String sdkVersion;
    public final String gameVersion;
    public final String language;
    public final String originAddress;
    /** form 里的 service_name（游戏固定 "passport-request"）。 */
    public final String serviceName;
    public final String smsSendMethod;
    public final String smsLoginMethod;
    public final String guestRegisterMethod;
    public final String realNameMethod;
    /** 非空时用于推导 cg-seid：md5(fpcsSource + 盐)。 */
    public final String fpcsSource;
    /** 显式 cg-seid，优先于 fpcsSource 推导。 */
    public final String cgSeid;

    private PassportConfig(Builder b) {
        this.baseUrl = nonBlank(b.baseUrl, DEFAULT_BASE_URL);
        this.path = nonBlank(b.path, DEFAULT_PATH);
        this.gameId = nonBlank(b.gameId, DEFAULT_GAME_ID);
        this.gameKey = nonBlank(b.gameKey, DEFAULT_GAME_KEY);
        this.sdkVersion = nonBlank(b.sdkVersion, DEFAULT_SDK_VERSION);
        this.gameVersion = nonBlank(b.gameVersion, DEFAULT_GAME_VERSION);
        this.language = nonBlank(b.language, DEFAULT_LANGUAGE);
        this.originAddress = nonBlank(b.originAddress, DEFAULT_ORIGIN_ADDRESS);
        this.serviceName = nonBlank(b.serviceName, DEFAULT_SERVICE_NAME);
        this.smsSendMethod = nonBlank(b.smsSendMethod, DEFAULT_SMS_SEND_METHOD);
        this.smsLoginMethod = nonBlank(b.smsLoginMethod, DEFAULT_SMS_LOGIN_METHOD);
        this.guestRegisterMethod = nonBlank(b.guestRegisterMethod, DEFAULT_GUEST_REGISTER_METHOD);
        this.realNameMethod = nonBlank(b.realNameMethod, DEFAULT_REAL_NAME_METHOD);
        this.fpcsSource = trim(b.fpcsSource);
        this.cgSeid = trim(b.cgSeid);
    }

    public static PassportConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** cg-seid 解析：显式值优先；否则 fpcsSource 推导；都空返回 ""（调用方不发该头）。 */
    public String resolveCgSeid() {
        if (!cgSeid.isEmpty()) {
            return cgSeid;
        }
        if (!fpcsSource.isEmpty()) {
            return PassportIds.md5Hex(fpcsSource + PassportIds.CG_SEID_SALT);
        }
        return "";
    }

    /** 配置摘要（gameKey 掩码），用于日志/UI 回显。 */
    public Map<String, String> maskedSummary() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("baseUrl", baseUrl);
        m.put("path", path);
        m.put("gameId", gameId);
        m.put("gameKey", PassportIds.mask(gameKey));
        m.put("sdkVersion", sdkVersion);
        m.put("gameVersion", gameVersion);
        m.put("serviceName", serviceName);
        m.put("smsSendMethod", smsSendMethod);
        m.put("smsLoginMethod", smsLoginMethod);
        m.put("guestRegisterMethod", guestRegisterMethod);
        m.put("realNameMethod", realNameMethod);
        m.put("cgSeidConfigured", String.valueOf(!resolveCgSeid().isEmpty()));
        return m;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nonBlank(String s, String fallback) {
        String v = trim(s);
        return v.isEmpty() ? fallback : v;
    }

    /** 逐字段覆盖构建。 */
    public static final class Builder {
        private String baseUrl;
        private String path;
        private String gameId;
        private String gameKey;
        private String sdkVersion;
        private String gameVersion;
        private String language;
        private String originAddress;
        private String serviceName;
        private String smsSendMethod;
        private String smsLoginMethod;
        private String guestRegisterMethod;
        private String realNameMethod;
        private String fpcsSource;
        private String cgSeid;

        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder path(String v) { this.path = v; return this; }
        public Builder gameId(String v) { this.gameId = v; return this; }
        public Builder gameKey(String v) { this.gameKey = v; return this; }
        public Builder sdkVersion(String v) { this.sdkVersion = v; return this; }
        public Builder gameVersion(String v) { this.gameVersion = v; return this; }
        public Builder language(String v) { this.language = v; return this; }
        public Builder originAddress(String v) { this.originAddress = v; return this; }
        public Builder serviceName(String v) { this.serviceName = v; return this; }
        public Builder smsSendMethod(String v) { this.smsSendMethod = v; return this; }
        public Builder smsLoginMethod(String v) { this.smsLoginMethod = v; return this; }
        public Builder guestRegisterMethod(String v) { this.guestRegisterMethod = v; return this; }
        public Builder realNameMethod(String v) { this.realNameMethod = v; return this; }
        public Builder fpcsSource(String v) { this.fpcsSource = v; return this; }
        public Builder cgSeid(String v) { this.cgSeid = v; return this; }

        public PassportConfig build() {
            return new PassportConfig(this);
        }
    }
}
