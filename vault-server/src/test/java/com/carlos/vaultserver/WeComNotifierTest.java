package com.carlos.vaultserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 企业微信机器人推送单测：本地 mock HttpServer 验证 payload 形状、errcode 判定、
 * 失败重试 1 次（总尝试 2 次）与超时/坏地址不抛异常。
 */
public class WeComNotifierTest {

    private HttpServer server;
    private volatile String lastBody = "";
    private final AtomicInteger attempts = new AtomicInteger();
    /** 响应行为：0=errcode 0；1=HTTP 500；2=errcode 非 0；3=先 500 后成功。 */
    private volatile int mode = 0;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            int n = attempts.incrementAndGet();
            lastBody = readAll(ex.getRequestBody());
            int m = mode;
            if (m == 1 || (m == 3 && n == 1)) {
                respond(ex, 500, "{}");
            } else if (m == 2) {
                respond(ex, 200, "{\"errcode\":40014,\"errmsg\":\"invalid https\"}");
            } else {
                respond(ex, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @Test
    public void send_successPayloadShape() {
        WeComNotifier n = new WeComNotifier();
        assertTrue(n.send(hookUrl(), "第一行\n第二\"引号\""));
        JSONObject body = new JSONObject(lastBody);
        assertEquals("text", body.optString("msgtype"));
        assertEquals("第一行\n第二\"引号\"", body.optJSONObject("text").optString("content"));
        assertEquals(1, attempts.get());   // 成功不重试
    }

    @Test
    public void send_http500RetriesOnce() {
        mode = 1;
        WeComNotifier n = new WeComNotifier();
        assertFalse(n.send(hookUrl(), "x"));
        assertEquals(2, attempts.get());   // 失败重试 1 次
    }

    @Test
    public void send_errcodeNonZeroRetriesOnce() {
        mode = 2;
        WeComNotifier n = new WeComNotifier();
        assertFalse(n.send(hookUrl(), "x"));
        assertEquals(2, attempts.get());
    }

    @Test
    public void send_retryThenSuccess() {
        mode = 3;
        WeComNotifier n = new WeComNotifier();
        assertTrue(n.send(hookUrl(), "x"));
        assertEquals(2, attempts.get());
    }

    @Test
    public void send_refusedConnectionReturnsFalse() {
        WeComNotifier n = new WeComNotifier();
        // 端口 1 几乎必然拒绝连接；断言不抛异常且返回 false
        assertFalse(n.send("http://127.0.0.1:1/hook", "x"));
    }

    @Test
    public void send_emptyUrlIsNoOp() {
        WeComNotifier n = new WeComNotifier();
        assertFalse(n.send("", "x"));
        assertFalse(n.send(null, "x"));
    }

    @Test
    public void textPayload_escapesAndParses() {
        String p = WeComNotifier.textPayload("a\"b\\c\nd");
        JSONObject o = new JSONObject(p);
        assertEquals("a\"b\\c\nd", o.optJSONObject("text").optString("content"));
    }
}
