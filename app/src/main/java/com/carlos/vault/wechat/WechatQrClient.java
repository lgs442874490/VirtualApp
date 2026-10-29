package com.carlos.vault.wechat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 微信扫码登录的网络层（必须在线程里调用）。
 *
 * <p>只用 {@link HttpURLConnection}，不新增依赖；所有响应先以 UTF-8 文本/字节返回，
 * 解析交给纯逻辑类 {@link WechatQrLogin}。
 */
public final class WechatQrClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 一次扫码所需的二维码数据。 */
    public static final class Qr {
        public final String uuid;
        public final byte[] image;

        Qr(String uuid, byte[] image) {
            this.uuid = uuid;
            this.image = image;
        }
    }

    /** 拉取扫码页 → 解析 uuid → 下载二维码图片。 */
    public Qr fetchQr(WechatQrLogin.Config config, String state) throws IOException {
        String page = get(WechatQrLogin.buildQrPageUrl(config, state), 20000, WechatQrLogin.WX_UA);
        String uuid = WechatQrLogin.parseUuid(page);
        if (uuid.isEmpty()) {
            throw new IOException("微信未返回二维码（可能网络受限或二维码服务繁忙）");
        }
        byte[] image = request(WechatQrLogin.qrImageUrl(uuid), "GET", null,
                WechatQrLogin.WX_UA, 20000);
        if (image == null || image.length == 0) {
            throw new IOException("微信二维码图片为空");
        }
        return new Qr(uuid, image);
    }

    /** 长轮询扫码结果（阻塞到状态变化，最长约 40s）。 */
    public WechatQrLogin.Poll poll(String uuid) throws IOException {
        String body = get(WechatQrLogin.buildPollUrl(uuid), 45000, WechatQrLogin.WX_UA);
        return WechatQrLogin.parsePoll(body);
    }

    /** code → 微信身份（access_token/openid/unionid），并拉 userinfo 补 unionid/昵称。 */
    public WechatQrLogin.Identity exchange(WechatQrLogin.Config config, String code) throws IOException {
        String tokenJson = get(WechatQrLogin.buildTokenUrl(config, code), 25000, WechatQrLogin.WX_UA);
        WechatQrLogin.Identity identity = WechatQrLogin.parseToken(tokenJson);
        if (identity == null) {
            throw new IOException("微信登录态获取失败（code 可能已过期）");
        }
        try {
            String userJson = get(WechatQrLogin.buildUserInfoUrl(identity.openid, identity.accessToken),
                    25000, WechatQrLogin.WX_UA);
            identity = WechatQrLogin.mergeUserInfo(userJson, identity);
        } catch (Throwable ignored) {
            // userinfo 只是补充昵称/unionid；失败不影响主链路（token 响应里通常已带 unionid）
        }
        return identity;
    }

    /** 调游戏 passport login_with_sns 换 fpid + session_key。 */
    public WechatQrLogin.Session loginPassport(WechatQrLogin.Identity identity) throws IOException {
        Map<String, String> form = WechatQrLogin.buildPassportForm(identity);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        String body = post(WechatQrLogin.PASSPORT_BASE + WechatQrLogin.PASSPORT_PATH,
                sb.toString(), WechatQrLogin.signPassport(form),
                WechatQrLogin.CG_SEID, 30000);
        WechatQrLogin.Session session = WechatQrLogin.parsePassport(body);
        if (session == null) {
            throw new IOException("游戏登录成功但未返回 fpid/session_key");
        }
        return session;
    }

    // ---------------- HTTP ----------------

    private String get(String url, int readTimeoutMs, String ua) throws IOException {
        byte[] bytes = request(url, "GET", null, ua, readTimeoutMs);
        return bytes == null ? "" : new String(bytes, UTF8);
    }

    private byte[] getBytes(String url, int readTimeoutMs) throws IOException {
        return request(url, "GET", null, null, readTimeoutMs);
    }

    private String post(String url, String form, String authorization, String cgSeid,
                        int readTimeoutMs) throws IOException {
        byte[] out = request(url, "POST", form, null, readTimeoutMs, authorization, cgSeid);
        return out == null ? "" : new String(out, UTF8);
    }

    private byte[] request(String url, String method, String body, String ua, int readTimeoutMs,
                           String... extraHeaders) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(readTimeoutMs);
            // 微信扫码页可能 302 到 mp.weixin.qq.com 的 uuid 页：GET 跟随重定向；
            // POST（passport）不跟，避免被改写成 GET 丢掉表单。
            conn.setInstanceFollowRedirects("GET".equals(method));
            conn.setRequestProperty("Accept", "*/*");
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
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
            if (extraHeaders != null && extraHeaders.length > 0) {
                if (extraHeaders[0] != null) conn.setRequestProperty("Authorization", extraHeaders[0]);
                if (extraHeaders.length > 1 && extraHeaders[1] != null) {
                    conn.setRequestProperty("cg-seid", extraHeaders[1]);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IOException("HTTP " + code);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            } finally {
                in.close();
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            return bos.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
