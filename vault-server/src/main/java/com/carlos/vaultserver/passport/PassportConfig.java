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
    public static final String DEFAULT_SMS_SEND_METHOD = "sendSms";
    public static final String DEFAULT_SMS_LOGIN_METHOD = "loginWithPhoneCode";
    /** 推定值：游客注册的 service_name，待真实轮校准。 */
    public static final String DEFAULT_GUEST_REGISTER_METHOD = "registerGuest";
    /** 推定值：实名认证的 service_name，待真实轮校准。 */
    public static final String DEFAULT_REAL_NAME_METHOD = "realNameVerify";

    public final String baseUrl;
    public final String path;
    public final String gameId;
    public final String gameKey;
    public final String sdkVersion;
    public final String gameVersion;
    public final String language;
    public final String originAddress;
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
