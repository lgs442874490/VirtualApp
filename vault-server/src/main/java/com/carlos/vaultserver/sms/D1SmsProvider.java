package com.carlos.vaultserver.sms;

import com.carlos.vaultserver.passport.PassportIds;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * D1（d1jiema）接码平台实现：GET {@code data.php?code=<操作>&token=<token>&<业务参数>}，纯文本响应。
 *
 * <p>上游成功响应的文本形态未文档化——解析必须宽容：先试 JSON（取 phone/yzm/money 等常见字段），
 * 失败则按文本提取（验证码 = 关键词邻近的 4-8 位数字 → 独立 4-8 位数字 → 首段连续数字；
 * 号码 = 11 位手机号优先 → 首个纯数字行；余额 = 首个数字串，可含小数点）。
 * <b>真实轮校准点</b>：拿到真实响应样本后收敛上述规则（见各 parse/extract 方法注释）。
 *
 * <p>线程语义：无状态可并发；Transport 由构造注入，单测用假实现，
 * 生产用 {@link HttpUrlConnectionTransport}。异常消息绝不包含 token。
 */
public final class D1SmsProvider implements SmsProvider {

    /** D1 基地址（任务书 §2.1；真实轮如有变化可在构造传入覆盖）。 */
    public static final String DEFAULT_BASE_URL = "https://api.d1jiema.com/zc/data.php";

    private static final Pattern PHONE_11 = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    /** 验证码关键词邻近规则：防「2026年11月07日」类日期/订单号数字抢先命中。 */
    private static final Pattern CODE_NEAR_KEYWORD =
            Pattern.compile("(?i)(?:验证码|校验码|动态码|code)[^0-9]{0,20}?([0-9]{4,8})");
    /** 独立 4-8 位数字（豪猪同款兜底规则）。 */
    private static final Pattern CODE_STANDALONE = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");
    private static final Pattern ANY_NUMBER = Pattern.compile("\\d+");
    private static final Pattern BALANCE_NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
    /** 号码行的保守形态：纯数字 5-20 位（防把网关 HTML 行当号码）。 */
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d{5,20}");

    private final String token;
    private final String baseUrl;
    private final Transport transport;

    /** HTTP 传输抽象：GET url，返回响应体文本；网络错误抛 IOException。 */
    public interface Transport {
        String get(String url) throws IOException;
    }

    /** baseUrl 为空时用 {@link #DEFAULT_BASE_URL}；token 缺失抛 IllegalArgumentException。 */
    public D1SmsProvider(String token, String baseUrl, Transport transport) {
        if (isBlank(token)) {
            throw new IllegalArgumentException("缺少 D1 token（网页「个人中心→API对接中心→创建 API Token」获取）");
        }
        if (transport == null) {
            throw new IllegalArgumentException("缺少 D1 transport");
        }
        this.token = token.trim();
        this.baseUrl = isBlank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.transport = transport;
    }

    @Override
    public String name() {
        return "d1";
    }

    // ---------------- SmsProvider 四通道 ----------------

    @Override
    public ProviderStatus testConnection() {
        try {
            String body = call("leftAmount", new LinkedHashMap<>());
            return ProviderStatus.ok(parseBalance(body), -1, PassportIds.mask(token));
        } catch (RuntimeException t) {
            return ProviderStatus.fail(PassportIds.mask(token), t.getMessage());
        }
    }

    /** 取号（code=getPhone）：keyWord=keyword；province/cardType/指定号未暴露配置，空值剔除后不传。 */
    @Override
    public PhoneOrder acquireNumber(String keyword) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyWord", nullToEmpty(keyword));
        String body = call("getPhone", params);
        JSONObject json = tryParseJson(body);
        String phone = json == null ? "" : jsonField(json, "phone", "mobile", "tel");
        if (phone.isEmpty()) {
            phone = extractPhone(body);
        }
        if (phone.isEmpty()) {
            throw new SmsException("D1 getPhone 未能从响应解析出号码: " + preview(body));
        }
        return new PhoneOrder(phone, json == null ? "" : jsonField(json, "order", "order_id", "orderid"));
    }

    /** 取码（code=getMsg）：phone + keyWord；D1 语义为「拉一次含关键词短信」，未到返回 waiting=true。 */
    @Override
    public SmsMessage fetchSms(PhoneOrder order, String keyword) {
        requirePhone(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("phone", order.phone);
        params.put("keyWord", nullToEmpty(keyword));
        return parseSms(call("getMsg", params));
    }

    @Override
    public void release(PhoneOrder order) {
        requirePhone(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("phone", order.phone);
        call("release", params);
    }

    @Override
    public void block(PhoneOrder order) {
        requirePhone(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("phone", order.phone);
        call("block", params);
    }

    // ---------------- 请求与宽容解析 ----------------

    /** 拼 query（code 首位、token 次位、业务参数随后，空值剔除）→ GET → 「ERROR:」前缀转 SmsException。 */
    private String call(String op, Map<String, String> params) {
        StringBuilder url = new StringBuilder(baseUrl);
        url.append(baseUrl.indexOf('?') >= 0 ? '&' : '?');
        url.append("code=").append(urlEncode(op));
        url.append("&token=").append(urlEncode(token));
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
        String body;
        try {
            body = transport.get(url.toString());
        } catch (IOException t) {
            throw new SmsException("D1 " + op + " 网络失败: " + t.getMessage(), t);
        }
        String text = body == null ? "" : body.trim();
        if (isError(text)) {
            throw new SmsException("D1 " + op + " 失败: " + errorText(text));
        }
        return text;
    }

    /** 「ERROR:」前缀判定（大小写不敏感，宽容网关变形）；内容 = 前缀后文本。 */
    private static boolean isError(String text) {
        return text.length() >= 6 && text.regionMatches(true, 0, "ERROR:", 0, 6);
    }

    private static String errorText(String text) {
        return text.substring(6).trim();
    }

    /**
     * 宽容解析验证码（<b>真实轮校准点</b>：D1「短信未到/已到」的响应形态均未文档化）。
     * 顺序：JSON 字段（yzm/smsCode/verifyCode，刻意不取 "code" 以免撞未来状态码字段）→
     * 对 content（sms/msg/content/text）或整段文本做 {@link #extractSmsCode} 三层提取；
     * 解析不到任何数字 → waiting=true（一律按「短信未到」处理，不抛错）。
     */
    private SmsMessage parseSms(String body) {
        // D1 未到码时的真实响应（2026-11-10 实测）：
        //   尚未收到包含关键字"点点互动"的短信，请5秒后再收取。请确保设置了正确的关键字。[尚未收到]
        // 必须在此之前判定，否则「请5秒后」里的 5 会被兜底规则当成验证码（真实轮踩过）。
        if (body != null && (body.contains("尚未收到") || body.contains("[尚未收到]"))) {
            return SmsMessage.waiting(body);
        }
        JSONObject json = tryParseJson(body);
        String code = "";
        String content = "";
        if (json != null) {
            code = jsonField(json, "yzm", "smsCode", "verifyCode");
            content = jsonField(json, "sms", "msg", "content", "text");
        }
        if (code.isEmpty()) {
            code = extractSmsCode(content.isEmpty() ? body : content);
        }
        if (code.isEmpty()) {
            return SmsMessage.waiting(body);
        }
        return SmsMessage.of(code, content, body);
    }

    /**
     * 三层宽容提取验证码（<b>真实轮校准点</b>）：
     * ① 「验证码/校验码/code」关键词邻近的 4-8 位数字（防日期/订单号干扰）；
     * ② 首个前后非数字的 4-8 位独立数字（豪猪同款规则）；
     * ③ 首段连续数字（任务书兜底：形态完全未知时的最后手段）。
     */
    static String extractSmsCode(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = CODE_NEAR_KEYWORD.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        m = CODE_STANDALONE.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        // 不再使用「首段连续数字」兜底：真实轮证明它会把提示语里的数字（如「请5秒后再收取」）
        // 误当验证码。解析不出宁可返回空（=未到码，继续等），由上层按超时处理。
        return "";
    }

    /**
     * 文本提取号码：11 位手机号优先；回退「首个纯数字行」（5-20 位）。
     * <b>真实轮校准点</b>：首行非空但非纯数字视为不可解析（任务书的「第一行非空文本」
     * 加纯数字约束，防网关错误页被当号码）。
     */
    static String extractPhone(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher m = PHONE_11.matcher(text);
        if (m.find()) {
            return m.group();
        }
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            return DIGITS_ONLY.matcher(l).matches() ? l : "";
        }
        return "";
    }

    /** 余额文本：JSON 字段（money/balance/amount/leftAmount）→ 首个数字串（可含小数点）→ 原文。 */
    private static String parseBalance(String body) {
        JSONObject json = tryParseJson(body);
        if (json != null) {
            String v = jsonField(json, "money", "balance", "amount", "leftAmount");
            if (!v.isEmpty()) {
                return v;
            }
        }
        Matcher m = BALANCE_NUMBER.matcher(body);
        return m.find() ? m.group() : body;
    }

    // ---------------- 小工具 ----------------

    /** JSON 宽容解析：根对象成功返回，否则 null（文本形态走提取；数组形态留待真实轮校准）。 */
    private static JSONObject tryParseJson(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.charAt(0) != '{') {
            return null;
        }
        try {
            return new JSONObject(t);
        } catch (Throwable notJson) {
            return null;
        }
    }

    /** JSON 字段按序取第一个非空（跳过 "null" 字面量；非字符串值按缺省处理）。 */
    private static String jsonField(JSONObject o, String... keys) {
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------------- 生产 Transport ----------------

    /** HttpURLConnection 生产实现：GET、UTF-8、10s 连接/15s 读超时、常规浏览器 UA。 */
    public static final class HttpUrlConnectionTransport implements Transport {
        /** D1 getPhone 实测 8.8s、release 可达 20s+（2026-11-10 真实轮），读超时放宽到 40s。 */
        private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
        private static final int READ_TIMEOUT_MILLIS = 40_000;
        private static final String USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0.0.0 Safari/537.36";

        @Override
        public String get(String url) throws IOException {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                conn.setReadTimeout(READ_TIMEOUT_MILLIS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                int code = conn.getResponseCode();
                String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code >= 400) {
                    throw new IOException("HTTP " + code);
                }
                return body;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        private static String readAll(InputStream in) throws IOException {
            if (in == null) {
                return "";
            }
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
