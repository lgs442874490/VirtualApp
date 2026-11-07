package com.carlos.vaultserver.sms;

import com.carlos.vaultserver.passport.PassportIds;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 豪猪（haozhuma）接码平台实现：GET {@code ?api=<操作>&token=<token>&<业务参数>}，JSON 响应。
 *
 * <p>token 生命周期：懒登录（api=login）→ 内存缓存复用；任何调用遇 token 失效类错误
 * （msg 含 "token" 或 code 非 0/200/-1）自动重登一次并重试一次，仍失败抛 {@link SmsException}。
 * code ∈ {"0","200"} = 成功；code="-1" 且 msg 含「等待」= 短信未到（轮询方继续）；其余失败抛错带 msg。
 *
 * <p>线程语义：多线程并发首调可能各自登录一次（后写覆盖，无正确性问题）；
 * Transport 复用 {@link D1SmsProvider.Transport}（同为 GET 抽象，实例互通）。
 * 异常消息绝不包含 user/password/token。
 */
public final class HaozhuSmsProvider implements SmsProvider {

    /** 豪猪基地址（任务书 §2.2；真实轮如有变化可在构造传入覆盖）。 */
    public static final String DEFAULT_SERVER = "https://api.haozhuma.com/sms/";
    /** SDK 固定渠道参数（haozhuma PyPI SDK 同值）。 */
    static final String DEFAULT_AUTHOR = "adminzfz";

    private static final Pattern CODE_STANDALONE = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");
    private static final Pattern PHONE_11 = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");

    private final String server;
    private final String user;
    private final String password;
    private final String sid;
    private final D1SmsProvider.Transport transport;

    /** 登录态 token（内存缓存；懒登录/重登时写入，并发下后写覆盖）。 */
    private String token;

    /** GET 传输抽象：别名继承 {@link D1SmsProvider.Transport}（零新增类型，两者实例互通）。 */
    public interface Transport extends D1SmsProvider.Transport {
    }

    /** server 为空时用 {@link #DEFAULT_SERVER}；user/password/sid 缺失抛 IllegalArgumentException。 */
    public HaozhuSmsProvider(String server, String user, String password, String sid,
            D1SmsProvider.Transport transport) {
        if (isBlank(user)) {
            throw new IllegalArgumentException("缺少豪猪账号 user");
        }
        if (isBlank(password)) {
            throw new IllegalArgumentException("缺少豪猪密码 password");
        }
        if (isBlank(sid)) {
            throw new IllegalArgumentException("缺少豪猪项目 sid");
        }
        if (transport == null) {
            throw new IllegalArgumentException("缺少豪猪 transport");
        }
        this.server = isBlank(server) ? DEFAULT_SERVER : server.trim();
        this.user = user.trim();
        this.password = password.trim();
        this.sid = sid.trim();
        this.transport = transport;
    }

    @Override
    public String name() {
        return "haozhuma";
    }

    // ---------------- SmsProvider 四通道 ----------------

    /** 连接自检走 getSummary：balanceText 取 money/amount/balance 第一个非空；maxNumbers 取 maxPhone/limit 类字段（无则 -1）。 */
    @Override
    public ProviderStatus testConnection() {
        try {
            Resp resp = requireOk("getSummary", call("getSummary", new LinkedHashMap<>()));
            return ProviderStatus.ok(firstField(resp.json, "money", "amount", "balance"),
                    parseMaxNumbers(resp.json), PassportIds.mask(user));
        } catch (RuntimeException t) {
            return ProviderStatus.fail(PassportIds.mask(user), t.getMessage());
        }
    }

    /**
     * 取号（api=getPhone）。keyword 被忽略：豪猪项目由构造时的 sid 绑定（任务书 §3.1）。
     * 固定携带 author 渠道参数；isp/Province/ascription/paragraph/exclude 本包未暴露配置，
     * 空值剔除后自然不传（真实轮需要时再扩展构造配置）。
     */
    @Override
    public PhoneOrder acquireNumber(String keyword) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sid", sid);
        params.put("author", DEFAULT_AUTHOR);
        Resp resp = requireOk("getPhone", call("getPhone", params));
        String phone = firstField(resp.json, "phone", "mobile");
        if (phone.isEmpty()) {
            Matcher m = PHONE_11.matcher(resp.body);
            phone = m.find() ? m.group() : "";
        }
        if (phone.isEmpty()) {
            throw new SmsException("豪猪 getPhone 未能从响应解析出号码: " + preview(resp.body));
        }
        return new PhoneOrder(phone, firstField(resp.json, "order", "order_id", "orderid"));
    }

    /**
     * 取一次验证码（api=getMessage）。code="-1" 且 msg 含「等待」→ waiting=true；
     * 成功取 yzm，为空则从 sms 全文按 {@code (?<!\d)(\d{4,8})(?!\d)} 提取首个 4-8 位数字；
     * 二者皆空按「短信未到」处理（waiting=true，不抛错）。
     */
    @Override
    public SmsMessage fetchSms(PhoneOrder order, String keyword) {
        requirePhone(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sid", sid);
        params.put("phone", order.phone);
        Resp resp = call("getMessage", params);
        String code = resp.json.optString("code", "").trim();
        String msg = resp.json.optString("msg", "").trim();
        if ("-1".equals(code) && msg.contains("等待")) {
            return SmsMessage.waiting(resp.body);
        }
        requireOk("getMessage", resp);
        String yzm = firstField(resp.json, "yzm");
        String content = firstField(resp.json, "sms");
        if (yzm.isEmpty()) {
            Matcher m = CODE_STANDALONE.matcher(content);
            yzm = m.find() ? m.group(1) : "";
        }
        if (yzm.isEmpty()) {
            return SmsMessage.waiting(resp.body);
        }
        return SmsMessage.of(yzm, content, resp.body);
    }

    /** 释放（api=cancelRecv）。 */
    @Override
    public void release(PhoneOrder order) {
        requirePhone(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sid", sid);
        params.put("phone", order.phone);
        requireOk("cancelRecv", call("cancelRecv", params));
    }

    /** 拉黑（api=addBlacklist）。 */
    @Override
    public void block(PhoneOrder order) {
        requirePhone(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sid", sid);
        params.put("phone", order.phone);
        requireOk("addBlacklist", call("addBlacklist", params));
    }

    // ---------------- 核心调用与 token 生命周期 ----------------

    /**
     * 单次 API 调用：懒登录 → 请求 → token 失效类错误重登一次并重试一次。
     * code 语义判定（成功/等待/失败）交由上层（「等待」只在 getMessage 场景成立）。
     */
    private Resp call(String api, Map<String, String> params) {
        ensureToken();
        Resp resp = rawCall(api, params);
        if (needsRelogin(resp)) {
            forceRelogin();
            resp = rawCall(api, params);
        }
        return resp;
    }

    /** token 失效类错误：code 非 0/200/-1；或 code="-1" 且 msg 含 "token"（大小写不敏感）。 */
    private static boolean needsRelogin(Resp resp) {
        String code = resp.json.optString("code", "").trim();
        if (isOk(code) || "-1".equals(code)) {
            return "-1".equals(code)
                    && resp.json.optString("msg", "").toLowerCase(Locale.ROOT).contains("token");
        }
        return true;
    }

    /** 懒登录：token 为空才登录（synchronized；竞态下最多多登一次，无正确性问题）。 */
    private synchronized void ensureToken() {
        if (token == null || token.isEmpty()) {
            login();
        }
    }

    /** 强制重登：清缓存后重新 login（synchronized）。 */
    private synchronized void forceRelogin() {
        token = null;
        login();
    }

    /** api=login 换 token；失败抛 SmsException（消息只带上游 msg/原文摘要，不含 user/password）。 */
    private void login() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", user);
        params.put("pass", password);
        String body;
        try {
            body = transport.get(buildUrl("login", params));
        } catch (IOException t) {
            throw new SmsException("豪猪 login 网络失败: " + t.getMessage(), t);
        }
        String text = body == null ? "" : body.trim();
        JSONObject json = parseJsonOrThrow("login", text);
        if (!isOk(json.optString("code", "").trim())) {
            String msg = json.optString("msg", "").trim();
            throw new SmsException("豪猪 login 失败: " + (msg.isEmpty() ? preview(text) : msg));
        }
        String tk = json.optString("token", "").trim();
        if (tk.isEmpty()) {
            throw new SmsException("豪猪 login 未返回 token: " + preview(text));
        }
        token = tk;
    }

    /** GET + JSON 解析（非 JSON 抛 SmsException，正文 ≤300 字符入消息）。 */
    private Resp rawCall(String api, Map<String, String> params) {
        String body;
        try {
            body = transport.get(buildUrl(api, params));
        } catch (IOException t) {
            throw new SmsException("豪猪 " + api + " 网络失败: " + t.getMessage(), t);
        }
        String text = body == null ? "" : body.trim();
        return new Resp(text, parseJsonOrThrow(api, text));
    }

    private JSONObject parseJsonOrThrow(String api, String text) {
        try {
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } catch (Throwable notJson) {
            throw new SmsException("豪猪 " + api + " 响应非 JSON: " + preview(text), notJson);
        }
    }

    /** code ∈ {"0","200"} 视为成功；否则抛 SmsException（消息含平台名+操作+上游 msg）。 */
    private static Resp requireOk(String api, Resp resp) {
        if (!isOk(resp.json.optString("code", "").trim())) {
            String msg = resp.json.optString("msg", "").trim();
            throw new SmsException("豪猪 " + api + " 失败: " + (msg.isEmpty() ? preview(resp.body) : msg));
        }
        return resp;
    }

    private static boolean isOk(String code) {
        return "0".equals(code) || "200".equals(code);
    }

    // ---------------- 小工具 ----------------

    /** 拼 URL：server(?|&)api=<api>[&token=<token>][&业务参数...]，空值剔除，UTF-8 URL 编码。 */
    private String buildUrl(String api, Map<String, String> params) {
        StringBuilder url = new StringBuilder(server);
        url.append(server.indexOf('?') >= 0 ? '&' : '?');
        url.append("api=").append(urlEncode(api));
        if (token != null && !token.isEmpty()) {
            url.append("&token=").append(urlEncode(token));
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            url.append('&').append(urlEncode(e.getKey())).append('=').append(urlEncode(v));
        }
        return url.toString();
    }

    /** maxNumbers：maxPhone/max_phone/limit/maxNum 第一个可解析整数字段；无/不可解析 → -1。 */
    private static int parseMaxNumbers(JSONObject json) {
        String v = firstField(json, "maxPhone", "max_phone", "limit", "maxNum");
        if (v.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** JSON 字段按序取第一个非空（跳过 "null" 字面量；非字符串值按缺省处理）。 */
    private static String firstField(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty() && !"null".equals(v)) {
                return v.trim();
            }
        }
        return "";
    }

    /** 原文摘要（≤300 字符），用于异常消息（上游响应不含我方凭据）。 */
    private static String preview(String text) {
        String t = text == null ? "" : text;
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return s == null ? "" : s;
        }
    }

    private static void requirePhone(PhoneOrder order) {
        if (order == null || order.phone.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少手机号 order");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 原始响应：body 原文 + 解析后的 JSON（供上层取字段与 waiting/raw 组装）。 */
    private static final class Resp {
        final String body;
        final JSONObject json;

        Resp(String body, JSONObject json) {
            this.body = body;
            this.json = json;
        }
    }
}
