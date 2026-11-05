package com.carlos.vault.douyin;

import com.carlos.vault.AccountStore;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.Charset;
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
 * 抖音扫码登录的纯逻辑层（不 import 任何 android.*，可直接在 JVM 单测）。
 *
 * <p>实现来源：照抄自营网页工具箱 wjdr-tool（2026-11-04 构建，commit 22d1173）的
 * {@code core/account/DouyinQrLoginClient} + {@code DouyinLoginService} +
 * {@code OfficialLoginService.loginWithDouyinMiniGame}，链路：
 * <ol>
 *   <li>抖音网页 {@code get_qrcode} 生成二维码（模板见 {@link DouyinLoginTemplate}，
 *       运行时只刷新 ts/p_ts/msToken）；</li>
 *   <li>{@code check_qrconnect} 轮询扫码状态，确认后拿到抖音网页会话 {@code sessionid}；</li>
 *   <li>带 {@code X-Tma-Host-Sessionid} 调抖音小游戏登录接口，
 *       换到小游戏授权码 {@code code}；</li>
 *   <li>游戏 passport {@code login_with_sns}（{@code platform_id=tt_mini:<code>}）换
 *       {@code fpid + session_key}，与号库现有 token 注入管线完全兼容。</li>
 * </ol>
 *
 * <p>⚠️ 本类不做网络、不写盘、不打印 token；cookie 只保存在内存的 {@link CookieJar} 里。
 * 抖音小游戏 appid/aid 与游戏 passport 参数都是游戏公开接入参数（与网页版默认值一致），
 * 不含用户凭据。
 */
public final class DouyinQrLogin {

    /** 浏览器 UA（与网页版模板一致）。 */
    public static final String UA = DouyinLoginTemplate.USER_AGENT;
    /** 扫码成功后的跳转/来源站点。 */
    public static final String NEXT_URL = "https://live.douyin.com";
    public static final String REFERER = "https://live.douyin.com/";
    /** 登录完成后的会话页；状态 3 没有 redirect_url 时访问它来拿 sessionid。 */
    public static final String SESSION_URL = "https://www.douyin.com/";
    public static final String SESSION_COOKIE = "sessionid";
    public static final String CSRF_COOKIE = "passport_csrf_token";

    /** 抖音小游戏（无尽冬日）接入参数，与网页版默认值一致。 */
    public static final String DOUYIN_APP_ID = "ttcb0dbdb0543b8b2007";
    public static final String DOUYIN_AID = "1128";
    public static final String MINIGAME_LOGIN_URL =
            "https://minigame.zijieapi.com/mgplatform/api/apps/v3/login";

    /** 游戏 passport 接入参数（游戏客户端同源，非用户密钥；与 WechatQrLogin 一致）。 */
    public static final String PASSPORT_BASE = "https://passport-web-gof.campfiregames.cn";
    public static final String PASSPORT_PATH = "/client_api.php?ver=6";
    public static final String GAME_ID = "20173";
    public static final String GAME_KEY = "a1f3d1fbf975825d85f51d2f6ffb2ff1";

    /** 抖音二维码默认有效期（接口未返回 expire_time 时使用）。 */
    public static final long DEFAULT_QR_TTL_MILLIS = 120_000L;
    /** 轮询限频（error_code=7）后的等待时间。 */
    public static final long RATE_LIMIT_DELAY_MILLIS = 10_000L;

    private static final int ERROR_RATE_LIMITED = 7;
    private static final int ERROR_ACCOUNT_VERIFY = 2046;

    private static final Pattern TS = Pattern.compile("([?&]ts=)\\d+");
    private static final Pattern P_TS = Pattern.compile("([?&]p_ts=)\\d+");
    private static final Pattern MS_TOKEN = Pattern.compile("([?&]msToken=)[^&]*");

    private DouyinQrLogin() {
    }

    // ---------------- 接口模板 ----------------

    /** 抖音扫码接口模板（内置默认 + 可选外部覆盖）。 */
    public static final class Template {
        public final String fetchUrl;
        public final String checkUrl;
        public final String userAgent;

        public Template(String fetchUrl, String checkUrl, String userAgent) {
            this.fetchUrl = fetchUrl == null ? "" : fetchUrl.trim();
            this.checkUrl = checkUrl == null ? "" : checkUrl.trim();
            this.userAgent = (userAgent == null || userAgent.trim().isEmpty())
                    ? UA : userAgent.trim();
        }

        public boolean isValid() {
            return !fetchUrl.isEmpty() && !checkUrl.isEmpty();
        }

        /** 内置模板。 */
        public static Template builtIn() {
            return new Template(DouyinLoginTemplate.FETCH_URL,
                    DouyinLoginTemplate.CHECK_URL, DouyinLoginTemplate.USER_AGENT);
        }

        /**
         * 解析覆盖用模板 JSON（同网页版 config/douyin-login.json 的字段）。
         *
         * @return 解析失败返回 {@code null}
         */
        public static Template parse(String json) {
            if (json == null || json.trim().isEmpty()) return null;
            try {
                JSONObject root = new JSONObject(json);
                Template t = new Template(root.optString("getQrUrl", ""),
                        root.optString("checkQrUrl", ""), root.optString("userAgent", ""));
                return t.isValid() ? t : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ---------------- cookie ----------------

    /** 单次扫码会话的内存 cookie（不落盘）。 */
    public static final class CookieJar {
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        private static final Pattern MAX_AGE_ZERO =
                Pattern.compile("(?i)(^|;)\\s*max-age=0\\b");

        /** 吸收一个 Set-Cookie 头。 */
        public void store(String setCookieHeader) {
            if (setCookieHeader == null || setCookieHeader.trim().isEmpty()) return;
            String first = setCookieHeader.split(";", 2)[0];
            int eq = first.indexOf('=');
            if (eq <= 0) return;
            String name = first.substring(0, eq).trim();
            String value = first.substring(eq + 1).trim();
            if (name.isEmpty()) return;
            if (value.isEmpty() || MAX_AGE_ZERO.matcher(setCookieHeader).find()) {
                values.remove(name);
                return;
            }
            values.put(name, value);
        }

        /** 取 cookie 值，不存在返回 {@code ""}。 */
        public String value(String name) {
            String v = values.get(name);
            return v == null ? "" : v;
        }

        /** 拼成 Cookie 请求头；没有 cookie 返回 {@code ""}。 */
        public String header() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        public void clear() {
            values.clear();
        }

        public int size() {
            return values.size();
        }
    }

    // ---------------- 二维码 ----------------

    /** 一张二维码及其扫码会话状态（字段可变：接口会刷新 token/图片/有效期）。 */
    public static final class Qr {
        public String token;
        public String imageBase64;
        public long expireAtMillis;
        public boolean frontier;
        public String msToken;

        public Qr(String token, String imageBase64, long expireAtMillis,
                  boolean frontier, String msToken) {
            this.token = token == null ? "" : token;
            this.imageBase64 = imageBase64 == null ? "" : imageBase64;
            this.expireAtMillis = expireAtMillis;
            this.frontier = frontier;
            this.msToken = msToken == null ? "" : msToken;
        }

        public boolean expired(long nowMillis) {
            return expireAtMillis > 0 && nowMillis >= expireAtMillis;
        }
    }

    /** 生成二维码请求 URL（刷新 ts/p_ts）。 */
    public static String buildQrFetchUrl(Template template, long nowMillis) {
        return withCurrentTimestamps(template.fetchUrl, nowMillis);
    }

    /** 轮询请求 URL（刷新 ts/p_ts + msToken）。 */
    public static String buildPollUrl(Template template, Qr qr, long nowMillis) {
        return withMsToken(withCurrentTimestamps(template.checkUrl, nowMillis),
                qr == null ? "" : qr.msToken);
    }

    /**
     * 解析 get_qrcode 响应。
     *
     * @throws IllegalStateException 响应异常（含抖音返回的错误说明）
     */
    public static Qr parseQr(String json, long nowMillis) {
        JSONObject data;
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            data = root.optJSONObject("data");
        } catch (Throwable t) {
            throw new IllegalStateException("抖音二维码响应格式无效", t);
        }
        if (data == null) throw new IllegalStateException("抖音二维码响应缺少 data");
        int code = data.optInt("error_code", 0);
        if (code != 0) {
            throw new IllegalStateException("抖音二维码生成失败："
                    + optText(data, "description", "未知错误"));
        }
        String qrcode = optText(data, "qrcode", "");
        String token = optText(data, "token", "");
        if (qrcode.isEmpty() || token.isEmpty()) {
            throw new IllegalStateException("抖音二维码响应缺少 qrcode/token");
        }
        String image = qrcode.startsWith("data:") ? stripDataUri(qrcode) : qrcode;
        long expireSeconds = data.optLong("expire_time", 0L);
        long expireAt = expireSeconds > 0
                ? expireSeconds * 1000L : nowMillis + DEFAULT_QR_TTL_MILLIS;
        boolean frontier = data.has("is_frontier") ? data.optBoolean("is_frontier", true) : true;
        return new Qr(token, image, expireAt, frontier, "");
    }

    /** 轮询状态。 */
    public enum PollState {
        /** 未扫码/等待确认，继续轮询。 */
        WAITING,
        /** 已扫码待确认。 */
        SCANNED,
        /** 已确认且拿到抖音网页 sessionid。 */
        CONFIRMED,
        /** 二维码过期/被取消，需要换新。 */
        EXPIRED,
        /** 抖音返回错误（含要求手机安全验证）。 */
        ERROR
    }

    /** 一次轮询的结果。 */
    public static final class Poll {
        public final PollState state;
        public final String message;
        public final String sessionId;
        /** 状态 4 自动刷新二维码时的新图（base64，可能为空）。 */
        public final String imageBase64;
        /** 新二维码有效期（毫秒时间戳，0 表示未变）。 */
        public final long expireAtMillis;
        /** 状态 3 时的确认跳转地址（需要客户端跟随拿 sessionid）。 */
        public final String redirectUrl;
        public final long retryAfterMillis;
        /** 状态 4 刷新后的新 token（可能为空）。 */
        public final String nextToken;

        public Poll(PollState state, String message, String sessionId, String imageBase64,
                    long expireAtMillis, String redirectUrl, long retryAfterMillis, String nextToken) {
            this.state = state;
            this.message = message == null ? "" : message;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.imageBase64 = imageBase64 == null ? "" : imageBase64;
            this.expireAtMillis = expireAtMillis;
            this.redirectUrl = redirectUrl == null ? "" : redirectUrl;
            this.retryAfterMillis = retryAfterMillis;
            this.nextToken = nextToken == null ? "" : nextToken;
        }

        public boolean isConfirmed() {
            return state == PollState.CONFIRMED;
        }
    }

    /** 轮询请求表单（保持网页版字段与顺序）。 */
    public static String buildPollForm(Qr qr) {
        return form(
                "need_logo", "false",
                "is_frontier", Boolean.toString(qr != null && qr.frontier),
                "token", qr == null ? "" : qr.token,
                "is_new_login", "1",
                "next", NEXT_URL,
                "need_short_url", "true");
    }

    /**
     * 解析 check_qrconnect 响应。
     *
     * @param jar 已吸收本次响应 Set-Cookie 的 cookie 容器（用于读 sessionid）
     */
    public static Poll parsePoll(String json, CookieJar jar, long nowMillis) {
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return error("抖音扫码响应格式无效");
            }
            int code = data.optInt("error_code", 0);
            if (code == ERROR_RATE_LIMITED) {
                return new Poll(PollState.WAITING,
                        "抖音正在确认，已自动放慢查询，请再等几秒",
                        "", "", 0L, "", RATE_LIMIT_DELAY_MILLIS, "");
            }
            if (code != 0) {
                String description = optText(data, "description", "抖音扫码失败");
                if (description.contains("过期") || description.contains("失效")) {
                    return new Poll(PollState.EXPIRED,
                            "二维码已过期，请重新生成", "", "", 0L, "", 0L, "");
                }
                if (code == ERROR_ACCOUNT_VERIFY
                        || "verify".equals(data.optString("account_flow", ""))) {
                    return error("抖音要求完成手机安全验证，请在抖音 App 内完成验证后重新扫码");
                }
                return error("抖音登录失败：" + (description.isEmpty() ? "请重新扫码" : description));
            }

            String rawStatus = optText(data, "status", "new").trim().toLowerCase();
            if (rawStatus.endsWith(".0")) {
                rawStatus = rawStatus.substring(0, rawStatus.length() - 2);
            }
            String redirectUrl = optText(data, "redirect_url", "");
            String nextToken = optText(data, "token", "");
            String qrcode = optText(data, "qrcode", "");
            String image = qrcode.isEmpty() ? ""
                    : (qrcode.startsWith("data:") ? stripDataUri(qrcode) : qrcode);
            long expireSeconds = data.optLong("expire_time", 0L);
            long expireAt = expireSeconds > 0 ? expireSeconds * 1000L : 0L;

            String sessionId = jar == null ? "" : jar.value(SESSION_COOKIE);
            if (!sessionId.isEmpty()) {
                return new Poll(PollState.CONFIRMED,
                        "扫码成功，正在换取游戏登录态", sessionId,
                        image, expireAt, redirectUrl, 0L, nextToken);
            }
            if ("5".equals(rawStatus) || rawStatus.contains("expire")
                    || rawStatus.contains("cancel")) {
                return new Poll(PollState.EXPIRED,
                        "二维码已过期，正在换新…", "", image, expireAt, redirectUrl, 0L, nextToken);
            }
            if ("2".equals(rawStatus) || rawStatus.contains("scan")) {
                return new Poll(PollState.SCANNED,
                        "已扫码，请在抖音 App 里点「确认」", "", image, expireAt,
                        redirectUrl, 0L, nextToken);
            }
            if ("4".equals(rawStatus)) {
                return new Poll(PollState.WAITING,
                        "二维码已自动刷新，请重新扫码", "", image, expireAt,
                        redirectUrl, 0L, nextToken);
            }
            if ("3".equals(rawStatus) || rawStatus.contains("confirm")
                    || rawStatus.contains("success")
                    || (!redirectUrl.isEmpty() && !rawStatus.contains("new"))) {
                // 没有 redirect_url 时用会话页探测 sessionid（网页版 resolveSessionCookie 同款）
                String confirmUrl = redirectUrl.isEmpty() ? SESSION_URL : redirectUrl;
                return new Poll(PollState.SCANNED,
                        "已确认，正在完成抖音登录", "", image, expireAt,
                        confirmUrl, 0L, nextToken);
            }
            return new Poll(PollState.WAITING,
                    "请使用抖音 App 扫描二维码", "", image, expireAt,
                    redirectUrl, 0L, nextToken);
        } catch (Throwable t) {
            return error("抖音扫码响应格式无效");
        }
    }

    // ---------------- 抖音小游戏授权码 ----------------

    /** 抖音小游戏登录接口 URL（带 appid/aid）。 */
    public static String buildGameCodeUrl() {
        return MINIGAME_LOGIN_URL + "?appid=" + urlEncode(DOUYIN_APP_ID)
                + "&aid=" + urlEncode(DOUYIN_AID)
                + "&device_platform=android&is_phone_auth=false";
    }

    /**
     * 解析小游戏登录响应，返回授权码 code。
     *
     * @throws IllegalStateException 授权失败（含抖音返回的 err_tips）
     */
    public static String parseGameCode(String json) {
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            int errNo = root.optInt("err_no", 0);
            if (errNo != 0) {
                throw new IllegalStateException("抖音小游戏授权失败："
                        + optText(root, "err_tips", "未知错误"));
            }
            JSONObject data = root.optJSONObject("data");
            boolean isLogin = data != null && data.optBoolean("isLogin", false);
            String code = data == null ? "" : optText(data, "code", "");
            if (!isLogin || code.isEmpty()) {
                throw new IllegalStateException("抖音已确认，但小游戏授权未完成，请重新扫码");
            }
            return code;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("抖音小游戏授权响应格式无效", t);
        }
    }

    // ---------------- 游戏 passport ----------------

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

    /**
     * 构造 {@code login_with_sns} 表单（抖音小游戏走 raw 模式：只带 method/game_id + 业务字段，
     * 不带 service_name/game_version 等，也不带 cg-seid 头）。
     */
    public static Map<String, String> buildPassportForm(String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("method", "login_with_sns");
        form.put("game_id", GAME_ID);
        form.put("platform_id", "tt_mini:" + (code == null ? "" : code));
        form.put("access_token", code == null ? "" : code);
        form.put("minigame_scene_id", "");
        form.put("bi_data", "{\"social_type\":\"tt_mini_game\"}");
        return form;
    }

    /**
     * passport 签名：{@code FP <game_id>:base64(hex(HMAC-SHA256(按 key 字典序拼的 k=v&…, game_key)))}。
     */
    public static String signPassport(Map<String, String> params) {
        StringBuilder src = new StringBuilder();
        for (String key : new TreeSet<>(params.keySet())) {
            if (src.length() > 0) src.append('&');
            src.append(key).append('=').append(params.get(key) == null ? "" : params.get(key));
        }
        String hex = hmacSha256Hex(src.toString(), GAME_KEY);
        return "FP " + GAME_ID + ":" + base64(hex.getBytes(Charset.forName("UTF-8")));
    }

    /** 解析 passport 响应，取 fpid + session_key（含 login_profile 嵌套）。 */
    public static Session parsePassport(String json) {
        try {
            JSONObject root = new JSONObject(json == null ? "{}" : json);
            JSONObject data = root.optJSONObject("data");
            // 兼容未包 data 的响应（网页版 passportData 同款兜底）
            if (data == null) data = root;
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

    /** 自动备注：抖音·号ID 后 4 位。 */
    public static String labelFor(Session session) {
        String fpid = session == null ? "" : session.fpid;
        return "抖音·" + (fpid.length() > 4 ? fpid.substring(fpid.length() - 4) : fpid);
    }

    // ---------------- 落库（纯逻辑，便于单测） ----------------

    /**
     * 把扫码得到的会话写入账号列表（原地修改并返回目标账号），规则与微信扫码登录一致：
     * <ul>
     *   <li>{@code entryId} 非空：回填到该入口；备注为空/等于旧号ID时才用自动备注；</li>
     *   <li>{@code entryId} 为空：按 fpid 找已有账号覆盖，找不到就新建一条（未分组）。</li>
     * </ul>
     *
     * <p>⚠️ 不做任何 Android/IO 操作，调用方负责 {@code saveSync} 与同步通知。
     */
    public static AccountStore.Account applySession(List<AccountStore.Account> list,
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
        String label = target.label == null ? "" : target.label.trim();
        if (autoLabel != null && !autoLabel.isEmpty()
                && (label.isEmpty() || label.equals(oldFpid))) {
            target.label = autoLabel;
        }
        target.lastLogin = System.currentTimeMillis();
        return target;
    }

    // ---------------- 杂项 ----------------

    /** 刷新 URL 里的 ts（秒）与 p_ts（毫秒）。 */
    public static String withCurrentTimestamps(String url, long nowMillis) {
        if (url == null || url.isEmpty()) return "";
        String s = TS.matcher(url).replaceFirst("$1" + (nowMillis / 1000L));
        return P_TS.matcher(s).replaceFirst("$1" + nowMillis);
    }

    /** 替换/追加 URL 里的 msToken。 */
    public static String withMsToken(String url, String token) {
        if (url == null) return "";
        if (token == null || token.isEmpty()) return url;
        String encoded = Matcher.quoteReplacement(urlEncode(token));
        Matcher m = MS_TOKEN.matcher(url);
        if (m.find()) return m.replaceFirst("$1" + encoded);
        return url + (url.contains("?") ? "&" : "?") + "msToken=" + encoded;
    }

    static String stripDataUri(String qrcode) {
        int index = qrcode.indexOf("base64,");
        return index >= 0 ? qrcode.substring(index + "base64,".length()) : qrcode;
    }

    /**
     * 是否允许携带抖音 cookie / 跟随跳转的域名（只放行抖音系）。
     *
     * <p>sessionid 是凭据：即使模板被覆盖或服务端返回异常 Location，也不应把 cookie 发给陌生域名。
     */
    public static boolean isDouyinHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.equals("douyin.com") || h.endsWith(".douyin.com")
                || h.equals("snssdk.com") || h.endsWith(".snssdk.com");
    }

    private static Poll error(String message) {
        return new Poll(PollState.ERROR, message, "", "", 0L, "", 0L, "");
    }

    private static String form(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(values[i])).append('=').append(urlEncode(values[i + 1]));
        }
        return sb.toString();
    }

    static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Throwable t) {
            return value == null ? "" : value;
        }
    }

    static String hmacSha256Hex(String source, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(Charset.forName("UTF-8")), "HmacSHA256"));
            return toHex(mac.doFinal(source.getBytes(Charset.forName("UTF-8"))));
        } catch (Throwable t) {
            // 不能让失败静默产出 "FP 20173:" 这种无效签名（会变成服务端模糊报错）
            throw new IllegalStateException("生成官方请求签名失败", t);
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

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

    /** optString 的兜底：JSON null 在 org.json 里会变成字符串 "null"，这里统一当空串。 */
    private static String optText(JSONObject o, String name, String fallback) {
        String v = o.optString(name, fallback);
        if (v == null) return fallback == null ? "" : fallback;
        return "null".equals(v) ? "" : v;
    }

    private static String firstNonBlank(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty() && !"null".equals(v)) return v.trim();
        }
        return "";
    }
}
