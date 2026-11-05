package com.carlos.vault.douyin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抖音扫码登录的网络层（必须在线程里调用）。
 *
 * <p>只用 {@link HttpURLConnection}，不新增依赖；二维码/状态/授权码/passport 的解析全部交给
 * 纯逻辑类 {@link DouyinQrLogin}。cookie 只保存在本实例内存里（一次扫码一个实例），
 * 不发往非抖音域名。
 */
public final class DouyinQrClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final DouyinQrLogin.Template template;
    private final DouyinQrLogin.CookieJar jar = new DouyinQrLogin.CookieJar();
    private DouyinQrLogin.Qr qr;

    public DouyinQrClient(DouyinQrLogin.Template template) {
        this.template = (template == null || !template.isValid())
                ? DouyinQrLogin.Template.builtIn() : template;
    }

    /** 拉取二维码（同时吸收 ttwid/csrf 等 cookie）。 */
    public DouyinQrLogin.Qr fetchQr() throws IOException {
        long now = System.currentTimeMillis();
        primeCookies();
        HttpResult result = request(DouyinQrLogin.buildQrFetchUrl(template, now),
                "GET", null, browserHeaders(""), true);
        if (result.code / 100 != 2) {
            throw new IOException("抖音二维码接口 HTTP " + result.code);
        }
        DouyinQrLogin.Qr fresh = DouyinQrLogin.parseQr(result.text(), now);
        String msToken = result.header("X-Ms-Token");
        if (!msToken.isEmpty()) fresh.msToken = msToken;
        qr = fresh;
        return fresh;
    }

    /** 轮询一次扫码状态（阻塞到服务端返回，一般 1 秒内）。 */
    public DouyinQrLogin.Poll poll() throws IOException {
        if (qr == null) throw new IOException("请先获取抖音二维码");
        String csrf = jar.value(DouyinQrLogin.CSRF_COOKIE);
        if (csrf.isEmpty()) {
            throw new IOException("抖音扫码会话缺少 CSRF 凭证，请刷新二维码");
        }
        long now = System.currentTimeMillis();
        String url = DouyinQrLogin.buildPollUrl(template, qr, now);
        String body = DouyinQrLogin.buildPollForm(qr);
        HttpResult result = request(url, "POST", body, browserHeaders(csrf), true);
        if (result.code / 100 != 2) {
            throw new IOException("抖音扫码状态 HTTP " + result.code);
        }
        DouyinQrLogin.Poll poll = DouyinQrLogin.parsePoll(result.text(), jar, now);

        String msToken = result.header("X-Ms-Token");
        if (!msToken.isEmpty()) qr.msToken = msToken;
        if (!poll.nextToken.isEmpty()) qr.token = poll.nextToken;
        if (!poll.imageBase64.isEmpty()) qr.imageBase64 = poll.imageBase64;
        if (poll.expireAtMillis > 0) qr.expireAtMillis = poll.expireAtMillis;

        if (poll.state == DouyinQrLogin.PollState.SCANNED
                && !poll.redirectUrl.isEmpty()
                && jar.value(DouyinQrLogin.SESSION_COOKIE).isEmpty()) {
            followRedirects(poll.redirectUrl);
            String sessionId = jar.value(DouyinQrLogin.SESSION_COOKIE);
            if (!sessionId.isEmpty()) {
                return new DouyinQrLogin.Poll(DouyinQrLogin.PollState.CONFIRMED,
                        "扫码成功，正在换取游戏登录态", sessionId, poll.imageBase64,
                        poll.expireAtMillis, "", 0L, poll.nextToken);
            }
        }
        return poll;
    }

    /** 抖音网页 sessionid → 抖音小游戏授权码。 */
    public String exchangeGameCode(String sessionId) throws IOException {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IOException("抖音 sessionid 为空，请重新扫码");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("X-Tma-Host-Sessionid", sessionId);
        headers.put("X-Tma-Host-Deviceid", "0");
        headers.put("User-Agent", template.userAgent);
        headers.put("Referer", DouyinQrLogin.REFERER);
        headers.put("Origin", DouyinQrLogin.NEXT_URL);
        HttpResult result = request(DouyinQrLogin.buildGameCodeUrl(), "GET", null, headers, false);
        if (result.code / 100 != 2) {
            throw new IOException("抖音小游戏授权 HTTP " + result.code);
        }
        return DouyinQrLogin.parseGameCode(result.text());
    }

    /** 小游戏授权码 → 游戏 passport（fpid + session_key）。 */
    public DouyinQrLogin.Session loginPassport(String code) throws IOException {
        Map<String, String> form = DouyinQrLogin.buildPassportForm(code);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Authorization", DouyinQrLogin.signPassport(form));
        HttpResult result = request(DouyinQrLogin.PASSPORT_BASE + DouyinQrLogin.PASSPORT_PATH,
                "POST", sb.toString(), headers, false);
        if (result.code / 100 != 2) {
            throw new IOException("游戏 passport HTTP " + result.code);
        }
        DouyinQrLogin.Session session = DouyinQrLogin.parsePassport(result.text());
        if (session == null) {
            throw new IOException("游戏登录成功但未返回 fpid/session_key");
        }
        return session;
    }

    // ---------------- 内部 ----------------

    /** 先访问一次 live.douyin.com 拿基础 cookie（失败不影响主链路）。 */
    private void primeCookies() {
        if (!template.fetchUrl.startsWith(DouyinQrLogin.NEXT_URL)) return;
        try {
            request(DouyinQrLogin.NEXT_URL + "/", "GET", null, navigationHeaders(), true);
        } catch (Throwable ignored) {
            // 预热失败时接口通常也能跑；不阻断
        }
    }

    /** 跟随确认跳转（最多 10 跳），目标只是让服务端把 sessionid 种进 cookie。 */
    private void followRedirects(String url) {
        String current = url;
        for (int hop = 0; hop < 10 && current != null && !current.isEmpty(); hop++) {
            if (!jar.value(DouyinQrLogin.SESSION_COOKIE).isEmpty()) return;
            if (!isAllowedHost(current)) return;
            HttpResult result;
            try {
                result = request(current, "GET", null, navigationHeaders(), true);
            } catch (Throwable t) {
                return;
            }
            if (!jar.value(DouyinQrLogin.SESSION_COOKIE).isEmpty()) return;
            if (result.code < 300 || result.code >= 400) return;
            String location = result.header("Location");
            if (location == null || location.isEmpty()) return;
            try {
                current = new URL(new URL(current), location).toString();
            } catch (Throwable t) {
                return;
            }
        }
    }

    /** 只对抖音系域名携带 cookie（sessionid 是凭据，纵深防御）。 */
    private static boolean isAllowedHost(String url) {
        try {
            return DouyinQrLogin.isDouyinHost(new URL(url).getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    private Map<String, String> browserHeaders(String csrf) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("User-Agent", template.userAgent);
        headers.put("Referer", DouyinQrLogin.REFERER);
        headers.put("Origin", DouyinQrLogin.NEXT_URL);
        headers.put("web-sdk-version", "1");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        if (csrf != null && !csrf.isEmpty()) headers.put("x-tt-passport-csrf-token", csrf);
        return headers;
    }

    private Map<String, String> navigationHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("User-Agent", template.userAgent);
        headers.put("Referer", DouyinQrLogin.REFERER);
        return headers;
    }

    private HttpResult request(String url, String method, String body,
                               Map<String, String> headers, boolean withCookies)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            URL target = new URL(url);
            conn = (HttpURLConnection) target.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            // 手动跟随：需要逐跳吸收 Set-Cookie（sessionid 可能在 30x 响应里）
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (withCookies && DouyinQrLogin.isDouyinHost(target.getHost())) {
                String cookie = jar.header();
                if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            }
            if (body != null) {
                conn.setDoOutput(true);
                byte[] data = body.getBytes(UTF8);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(data);
                    os.flush();
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] out = readAll(in);
            Map<String, List<String>> headerFields = conn.getHeaderFields();
            // 只吸收抖音系域名的 Set-Cookie（别让其它域名覆盖 sessionid）
            if (headerFields != null && DouyinQrLogin.isDouyinHost(target.getHost())) {
                for (Map.Entry<String, List<String>> e : headerFields.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    if (!"Set-Cookie".equalsIgnoreCase(e.getKey())) continue;
                    for (String value : e.getValue()) jar.store(value);
                }
            }
            return new HttpResult(code, out, headerFields);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }

    private static final class HttpResult {
        final int code;
        final byte[] body;
        final Map<String, List<String>> headers;

        HttpResult(int code, byte[] body, Map<String, List<String>> headers) {
            this.code = code;
            this.body = body == null ? new byte[0] : body;
            this.headers = headers;
        }

        String text() {
            return new String(body, UTF8);
        }

        String header(String name) {
            if (headers == null || name == null) return "";
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (!name.equalsIgnoreCase(e.getKey())) continue;
                for (String value : e.getValue()) {
                    if (value != null && !value.isEmpty()) return value;
                }
            }
            return "";
        }
    }
}
