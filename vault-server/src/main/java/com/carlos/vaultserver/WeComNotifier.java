package com.carlos.vaultserver;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 企业微信群机器人 webhook 推送（战争通知 server 侧通道）。
 *
 * <p>POST {"msgtype":"text","text":{"content":"..."}}；连接/读取各 6s 超时；
 * 失败重试 1 次（网络异常或 HTTP 非 2xx 或 errcode!=0 均算失败）。
 * 通知是尽力而为：所有异常就地吞掉返回 false，绝不影响会话与事件入库。
 */
public class WeComNotifier {

    /** 连接/读取超时（毫秒）。 */
    static final int TIMEOUT_MS = 6000;
    /** 总尝试次数 = 1 次重试 + 首次。 */
    static final int ATTEMPTS = 2;

    /** 推送文本消息；返回是否确认送达（HTTP 2xx 且 errcode=0）。 */
    public boolean send(String webhookUrl, String content) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return false;
        return post(webhookUrl, textPayload(content));
    }

    /** 企业微信 text 消息 payload（org.json 转义，内容含换行安全）。 */
    static String textPayload(String content) {
        JSONObject text = new JSONObject();
        text.put("content", content == null ? "" : content);
        JSONObject body = new JSONObject();
        body.put("msgtype", "text");
        body.put("text", text);
        return body.toString();
    }

    /** POST JSON 并校验 errcode；失败重试 1 次。包可见供单测打 mock server。 */
    boolean post(String url, String jsonBody) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (postOnce(url, jsonBody)) return true;
        }
        return false;
    }

    private static boolean postOnce(String url, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                FileLog.warn("企微推送 HTTP {} url={}", code, hostOf(url));
                return false;
            }
            String resp = readAll(conn.getInputStream());
            // 企业微信 2xx 响应体 {"errcode":0,"errmsg":"ok"}；errcode 缺失视为失败（防代理劫持页）
            JSONObject o = new JSONObject(resp);
            return o.optInt("errcode", -1) == 0;
        } catch (Throwable t) {
            FileLog.warn("企微推送失败 url={}：{}", hostOf(url),
                    t.getMessage() == null ? String.valueOf(t) : t.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 日志只留 host，不落完整 webhook key。 */
    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
