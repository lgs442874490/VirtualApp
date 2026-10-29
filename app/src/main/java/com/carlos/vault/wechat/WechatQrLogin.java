package com.carlos.vault.wechat;

import com.carlos.vault.AccountStore;

import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信扫码登录的纯逻辑层（不 import 任何 android.*，可直接在 JVM 单测）。
 *
 * <p>实现来源：照抄自营网页工具箱 wjdr-tool 最新版（release 434，2026-10-26）的
 * {@code core/account/WechatLoginService} + {@code core/account/OfficialLoginService}：
 * <ol>
 *   <li>用<b>游戏自己的微信 appid/app_key</b> 打开微信「App 扫码登录」页
 *       {@code open.weixin.qq.com/connect/app/qrconnect}，解析 uuid，取二维码图；</li>
 *   <li>长轮询 {@code long.open.weixin.qq.com/connect/l/qrconnect} 拿 {@code wx_code}；</li>
 *   <li>用 appid+app_key 换 openid/unionid/access_token（{@code api.weixin.qq.com/sns/oauth2}）；</li>
 *   <li>用游戏 passport {@code client_api.php?ver=6 → login_with_sns} 换 fpid + session_key，
 *       与号库现有 token 注入管线完全兼容。</li>
 * </ol>
 *
 * <p>凭据来源：wechat app_id/app_key 从容器内游戏自己缓存的远端配置
 * （{@code <gameDataDir>/shared_prefs/com.cg.sdk.xml} 的 {@code config_set} →
 * {@code social.wechat.app_id/app_key}）读取，不在号库里硬编码微信密钥。
 * passport 的 game_id/game_key 是游戏全局接入参数（与客户端登录同源）。
 *
 * <p>⚠️ 本类不做网络、不写盘、不打印 token。
 */
public final class WechatQrLogin {

    /** 微信 App 扫码登录页。 */
    public static final String QR_PAGE = "https://open.weixin.qq.com/connect/app/qrconnect";
    /** 二维码图片。 */
    public static final String QR_IMAGE = "https://open.weixin.qq.com/connect/qrcode/";
    /** 扫码结果长轮询。 */
    public static final String QR_POLL = "https://long.open.weixin.qq.com/connect/l/qrconnect";
    /** code 换 access_token。 */
    public static final String TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    /** 拉微信用户信息（校验登录态 + 取 unionid/nickname）。 */
    public static final String USERINFO_URL = "https://api.weixin.qq.com/sns/userinfo";

    /** 微信二维码页要求带微信内置浏览器 UA 才会直接返回 uuid。 */
    public static final String WX_UA =
            "Mozilla/5.0 (Linux; Android 7.0; Mi-4c Build/NRD90M; wv) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/53.0.2785.49 Mobile MQQBrowser/6.2 "
                    + "TBS/043632 Safari/537.36 MicroMessenger/6.6.1.1220(0x26060135) NetType/WIFI "
                    + "Language/zh_CN miniProgram";

    /** 与游戏 App 包名一致（微信侧 app 登录要求 bundleid 与登记一致）。 */
    public static final String BUNDLE_ID = "com.gof.china";
    /** 与工具箱默认值一致。 */
    public static final String SCOPE = "snsapi_base,snsapi_userinfo,snsapi_friend,snsapi_message";

    /** 游戏 passport 接入参数（游戏客户端同源，非用户密钥）。 */
    public static final String PASSPORT_BASE = "https://passport-web-gof.campfiregames.cn";
    public static final String PASSPORT_PATH = "/client_api.php?ver=6";
    public static final String GAME_ID = "20173";
    public static final String GAME_KEY = "a1f3d1fbf975825d85f51d2f6ffb2ff1";
    /** cg-seid = md5(fpcs_source + "7CPsU5puEk")，与工具箱默认值一致。 */
    public static final String FPCS_SOURCE = "98937a85-0774-439f-889f-8510fb11634a";
    public static final String CG_SEID = "3db0eedaaed17399fedeaadb17c2e1a3";
    public static final String LANGUAGE = "zh_CN";
    public static final String GAME_VERSION = "1.16.12";
    public static final String SDK_VERSION = "U8.0.5.0-GOFCN-1";

    /** 扫码轮询状态。 */
    public enum PollState {
        /** 未扫码，继续轮询。 */
        WAITING,
        /** 已扫码待确认。 */
        SCANNED,
        /** 已确认且拿到 wx_code。 */
        READY,
        /** 二维码过期/被取消，需要换新码。 */
        EXPIRED,
        /** 其它异常状态。 */
        ERROR
    }

    private WechatQrLogin() {
    }

    // ---------------- 配置（从游戏缓存配置里读） ----------------

    /** 微信平台接入凭据（游戏自己的移动应用 appid/app_key）。 */
    public static final class Config {
        public final String appId;
        public final String appKey;

        public Config(String appId, String appKey) {
            this.appId = appId == null ? "" : appId.trim();
            this.appKey = appKey == null ? "" : appKey.trim();
        }

        public boolean isValid() {
            return !appId.isEmpty() && !appKey.isEmpty();
        }
    }

    /** SharedPreferences XML 里会被转义的字符还原。 */
    private static String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static final Pattern CONFIG_SET = Pattern.compile(
            "<string\\s+name=\"config_set\"\\s*>(.*?)</string>", Pattern.DOTALL);

    /**
     * 从游戏缓存配置（{@code com.cg.sdk.xml} 文本）解析微信 appid/app_key。
     *
     * @return 解析失败返回 {@code null}
     */
    public static Config parseConfig(String prefsXml) {
        if (prefsXml == null || prefsXml.isEmpty()) return null;
        try {
            Matcher m = CONFIG_SET.matcher(prefsXml);
            if (!m.find()) return null;
            JSONObject root = new JSONObject(unescapeXml(m.group(1)));
            JSONObject wechat = root.optJSONObject("social");
            if (wechat == null) return null;
            wechat = wechat.optJSONObject("wechat");
            if (wechat == null) return null;
            Config c = new Config(wechat.optString("app_id", ""), wechat.optString("app_key", ""));
            return c.isValid() ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 二维码 ----------------

    /** 构造 App 扫码登录页 URL。 */
    public static String buildQrPageUrl(Config config, String state) {
        return QR_PAGE + "?appid=" + urlEncode(config.appId)
                + "&bundleid=" + urlEncode(BUNDLE_ID)
                + "&scope=" + urlEncode(SCOPE)
                + "&state=" + urlEncode(state);
    }

    private static final Pattern UUID_PATTERN =
            Pattern.compile("connect(?:/qrcode/|/confirm\\?uuid=)([A-Za-z0-9]+)");

    /** 从扫码页 HTML 里解析 uuid；失败返回 {@code ""}。 */
    public static String parseUuid(String pageHtml) {
        if (pageHtml == null || pageHtml.isEmpty()) return "";
        Matcher m = UUID_PATTERN.matcher(pageHtml);
        return m.find() ? m.group(1) : "";
    }

    public static String qrImageUrl(String uuid) {
        return QR_IMAGE + uuid;
    }

    public static String buildPollUrl(String uuid) {
        return QR_POLL + "?f=json&uuid=" + urlEncode(uuid);
    }

    /** 一次长轮询的结果。 */
    public static final class Poll {
        public final int errcode;
        public final String code;
        public final PollState state;

        public Poll(int errcode, String code) {
            this.errcode = errcode;
            this.code = code == null ? "" : code.trim();
            this.state = classify(errcode, this.code);
        }

        private static PollState classify(int errcode, String code) {
            // 实测：App 扫码流程在用户确认后可能返回 405 + wx_code（不是 0），以 code 为准
            if (!code.isEmpty() && (errcode == 0 || errcode == 405)) return PollState.READY;
            if (errcode == 408) return PollState.WAITING;
            if (errcode == 405) return PollState.SCANNED;
            if (errcode == 400 || errcode == 402 || errcode == 403 || errcode == 404) {
                return PollState.EXPIRED;
            }
            return PollState.ERROR;
        }

        public boolean isReady() {
            return state == PollState.READY;
        }
    }

    private static final Pattern ERRCODE = Pattern.compile("wx_errcode\\s*[=:]\\s*[\"']?(\\d+)");
    /** 只认字母数字下划线/连字符的 code（微信 code 就是这种字符集），锚定 = 或 : 后取值。 */
    private static final Pattern CODE = Pattern.compile("wx_code\\s*[=:]\\s*[\"']?([A-Za-z0-9_\\-]+)");

    /** 解析长轮询响应（JSON 或 JS 片段均可）。 */
    public static Poll parsePoll(String body) {
        if (body == null) return new Poll(408, "");
        String trimmed = body.trim();
        // 线上实测返回 JSON（含 "wx_errcode":405,"wx_code":"…"）：优先走 JSON 解析
        if (trimmed.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(trimmed);
                return new Poll(o.optInt("wx_errcode", 408), o.optString("wx_code", ""));
            } catch (Throwable ignored) {
                // 落到下面的正则兜底
            }
        }
        int errcode = 408;
        String code = "";
        Matcher em = ERRCODE.matcher(body);
        if (em.find()) {
            try {
                errcode = Integer.parseInt(em.group(1));
            } catch (Throwable ignored) {
            }
        }
        Matcher cm = CODE.matcher(body);
        if (cm.find()) code = cm.group(1).trim();
        return new Poll(errcode, code);
    }

    // ---------------- 微信身份 ----------------

    /** 微信身份：openid + access_token + unionid + 昵称。 */
    public static final class Identity {
        public final String openid;
        public final String accessToken;
        public final String unionId;
        public final String nickname;

        public Identity(String openid, String accessToken, String unionId, String nickname) {
            this.openid = openid == null ? "" : openid;
            this.accessToken = accessToken == null ? "" : accessToken;
            this.unionId = unionId == null ? "" : unionId;
            this.nickname = nickname == null ? "" : nickname;
        }

        public boolean isValid() {
            return !openid.isEmpty() && !accessToken.isEmpty();
        }
    }

    public static String buildTokenUrl(Config config, String code) {
        return TOKEN_URL + "?appid=" + urlEncode(config.appId)
                + "&secret=" + urlEncode(config.appKey)
                + "&code=" + urlEncode(code)
                + "&grant_type=authorization_code";
    }

    public static String buildUserInfoUrl(String openid, String accessToken) {
        return USERINFO_URL + "?openid=" + urlEncode(openid) + "&access_token=" + urlEncode(accessToken);
    }

    /**
     * 解析 code→token 响应，返回 Identity（unionid 可能只在 token 响应里）。
     *
     * @return 失败返回 {@code null}
     */
    public static Identity parseToken(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            if (o.optInt("errcode", 0) != 0) return null;
            Identity id = new Identity(o.optString("openid", ""), o.optString("access_token", ""),
                    o.optString("unionid", ""), "");
            return id.isValid() ? id : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 合并 userinfo（校验 + 取昵称/unionid）；传入 identity 不可变，返回新对象。 */
    public static Identity mergeUserInfo(String json, Identity base) {
        if (base == null) return null;
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            if (o.optInt("errcode", 0) != 0) return base;
            String union = base.unionId.isEmpty() ? o.optString("unionid", "") : base.unionId;
            return new Identity(base.openid, base.accessToken, union, o.optString("nickname", ""));
        } catch (Throwable t) {
            return base;
        }
    }

    // ---------------- passport ----------------

    /** 游戏会话（fpid + session_key）。 */
    public static final class Session {
        public final String fpid;
        public final String sessionKey;

        public Session(String fpid, String sessionKey) {
            this.fpid = fpid == null ? "" : fpid.trim();
            this.sessionKey = sessionKey == null ? "" : sessionKey.trim();
        }

        public boolean isValid() {
            return !fpid.isEmpty() && !sessionKey.isEmpty();
        }
    }

    /** 构造 login_with_sns 的表单（保持顺序，与工具箱一致）。 */
    public static Map<String, String> buildPassportForm(Identity identity) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("method", "login_with_sns");
        form.put("game_id", GAME_ID);
        form.put("service_name", "login_with_sns");
        form.put("l", LANGUAGE);
        form.put("game_version", GAME_VERSION);
        form.put("domain", "funsdk");
        form.put("sdk_version", SDK_VERSION);
        String origin = "02:00:00:00:00:00";
        form.put("origin_address", origin);
        String deviceId = randomHex(16);
        form.put("guid", deviceId);
        form.put("origin_guid", deviceId);
        form.put("android_id", deviceId);
        form.put("social_type", "wx");
        form.put("platform_id", "wx:" + identity.openid);
        form.put("os", "android");
        form.put("access_token", identity.accessToken);
        form.put("require_bound_info", "true");
        form.put("session_key", "");
        if (!identity.unionId.isEmpty()) form.put("union_id", identity.unionId);
        return form;
    }

    /**
     * passport 签名：{@code FP <game_id>:base64(hex(HMAC-SHA256(按 key 字典序拼的 k=v&…, game_key)))}。
     */
    public static String signPassport(Map<String, String> params) {
        StringBuilder src = new StringBuilder();
        for (String key : new TreeSet<>(params.keySet())) {
            if (src.length() > 0) src.append('&');
            src.append(key).append('=').append(params.get(key));
        }
        String hex = hmacSha256Hex(src.toString(), GAME_KEY);
        return "FP " + GAME_ID + ":" + base64(hex.getBytes(Charset.forName("UTF-8")));
    }

    /**
     * 纯 JVM 版签名（单测用，避免依赖 android.util.Base64 的桩）。
     */
    public static String signPassportJava(Map<String, String> params) {
        StringBuilder src = new StringBuilder();
        for (String key : new TreeSet<>(params.keySet())) {
            if (src.length() > 0) src.append('&');
            src.append(key).append('=').append(params.get(key));
        }
        String hex = hmacSha256Hex(src.toString(), GAME_KEY);
        return "FP " + GAME_ID + ":" + base64(hex.getBytes(Charset.forName("UTF-8")));
    }

    static String hmacSha256Hex(String source, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(Charset.forName("UTF-8")), "HmacSHA256"));
            return toHex(mac.doFinal(source.getBytes(Charset.forName("UTF-8"))));
        } catch (Throwable t) {
            return "";
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(s.getBytes(Charset.forName("UTF-8"))));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解析 passport 响应，取 fpid + session_key（含 login_profile 嵌套）。 */
    public static Session parsePassport(String json) {
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            List<JSONObject> layers = new ArrayList<>();
            layers.add(data);
            JSONObject lp = data.optJSONObject("login_profile");
            if (lp != null) layers.add(lp);
            String fpid = "", sk = "";
            for (JSONObject layer : layers) {
                if (fpid.isEmpty()) fpid = firstNonBlank(layer, "fpid", "account", "fp_id");
                if (sk.isEmpty()) sk = firstNonBlank(layer, "session_key", "sdkToken", "sdk_token");
            }
            Session s = new Session(fpid, sk);
            return s.isValid() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstNonBlank(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty() && !"null".equals(v)) return v.trim();
        }
        return "";
    }

    // ---------------- 落库（纯逻辑，便于单测） ----------------

    /**
     * 把扫码得到的会话写入账号列表（原地修改并返回目标账号）。
     *
     * <p>两种用法：
     * <ul>
     *   <li>{@code entryId} 非空（入口详情页发起）：把 fpid/token 回填到该入口；
     *       该入口已有备注名时保留，只有备注为空/等于旧号ID时才用自动备注；</li>
     *   <li>{@code entryId} 为空（独立扫码）：按 fpid 找已有账号覆盖，找不到就新建一条（未分组）。</li>
     * </ul>
     *
     * <p>⚠️ 不做任何 Android/IO 操作，调用方负责 {@code saveSync} 与同步通知。
     */
    public static AccountStore.Account applySession(java.util.List<AccountStore.Account> list,
                                                    String entryId,
                                                    Session session,
                                                    String autoLabel) {
        if (list == null || session == null || !session.isValid()) return null;
        AccountStore.Account target = null;
        boolean boundById = entryId != null && !entryId.isEmpty();
        if (boundById) {
            for (AccountStore.Account a : list) {
                if (a != null && entryId.equals(a.id)) {
                    target = a;
                    break;
                }
            }
        }
        if (target == null) {
            for (AccountStore.Account a : list) {
                if (a != null && session.fpid.equals(a.fpid)) {
                    target = a;
                    break;
                }
            }
        }
        if (target == null) {
            target = new AccountStore.Account();
            target.id = boundById ? entryId : session.fpid;
            target.fpid = session.fpid;
            target.group = "";
            list.add(target);
        }
        String oldFpid = target.fpid == null ? "" : target.fpid;
        target.fpid = session.fpid;
        target.token = session.sessionKey;
        // 备注：用户自定义过的（非空且不等于旧号ID）保留；否则用自动备注（微信·昵称）
        String label = target.label == null ? "" : target.label.trim();
        if (autoLabel != null && !autoLabel.isEmpty()
                && (label.isEmpty() || label.equals(oldFpid))) {
            target.label = autoLabel;
        }
        target.lastLogin = System.currentTimeMillis();
        return target;
    }

    // ---------------- 杂项 ----------------

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String randomHex(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = HEX[RANDOM.nextInt(16)];
        return new String(out);
    }

    /** 供微信授权页 state 用（32 位十六进制）。 */
    public static String randomState() {
        return randomHex(16);
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 标准 Base64（无换行）；不用 java.util.Base64（minSdk 21 无该类）。 */
    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int n = (data[i] & 0xff) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
        } else if (rem == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }
}
