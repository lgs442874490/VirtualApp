package com.carlos.vaultserver.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.carlos.vaultserver.passport.PassportIds;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * HaozhuSmsProvider 单测：login 时机（首次前自动 login、token 复用）/各操作参数与 sid/author/
 * code=0/200 成功与 code=-1「等待」/token 失效自动重登重试一次/yzm 直取与 sms 正则兜底（4-8 位边界）/
 * 异常消息不含密码与 token。全部用注入的假 Transport，不联网。
 */
public class HaozhuSmsProviderTest {

    private static final String USER = "hz-user";
    private static final String PASS = "hz-pass-secret";
    private static final String SID = "1024";

    /** 捕获请求 URL 并按脚本逐请求回放响应的假 Transport（脚本耗尽后重复最后一个）。 */
    private static final class ScriptedTransport implements HaozhuSmsProvider.Transport {
        final List<String> urls = new ArrayList<>();
        final List<String> script = new ArrayList<>();
        IOException throwIo;

        void respond(String... bodies) {
            script.addAll(Arrays.asList(bodies));
        }

        @Override
        public String get(String url) throws IOException {
            if (throwIo != null) {
                throw throwIo;
            }
            urls.add(url);
            return script.isEmpty() ? "" : script.get(Math.min(urls.size() - 1, script.size() - 1));
        }
    }

    private static HaozhuSmsProvider provider(ScriptedTransport t) {
        return new HaozhuSmsProvider("", USER, PASS, SID, t);
    }

    private static int countLogin(ScriptedTransport t) {
        int n = 0;
        for (String url : t.urls) {
            if (queryMap(url).get("api").equals("login")) {
                n++;
            }
        }
        return n;
    }

    // ---------------- login 时机与 token 复用 ----------------

    @Test
    public void loginOnce_beforeFirstCall_thenTokenReused() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"phone\":\"13912345678\"}",
                "{\"code\":\"200\",\"yzm\":\"246810\",\"sms\":\"code 246810\"}");
        HaozhuSmsProvider p = provider(t);

        SmsProvider.PhoneOrder order = p.acquireNumber(null);
        assertEquals("13912345678", order.phone);
        SmsProvider.SmsMessage msg = p.fetchSms(order, null);
        assertFalse(msg.waiting);
        assertEquals("246810", msg.code);

        // 共 3 次请求：login + getPhone + getMessage；login 恰好 1 次（token 复用）
        assertEquals(3, t.urls.size());
        assertEquals(1, countLogin(t));
        assertEquals("login", queryMap(t.urls.get(0)).get("api"));
        assertEquals("tk-1", queryMap(t.urls.get(1)).get("token"));
        assertEquals("tk-1", queryMap(t.urls.get(2)).get("token"));
    }

    @Test
    public void login_params_userAndPass() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("{\"code\":\"0\",\"token\":\"tk-1\"}", "{\"code\":\"0\",\"phone\":\"13912345678\"}");
        provider(t).acquireNumber(null);

        Map<String, String> q = queryMap(t.urls.get(0));
        assertEquals("login", q.get("api"));
        assertEquals(USER, q.get("user"));
        assertEquals(PASS, q.get("pass"));
        // login 不带 token
        assertFalse(q.containsKey("token"));
    }

    // ---------------- 各操作参数 ----------------

    @Test
    public void getPhone_params_sidAndAuthor_blankDropped() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("{\"code\":\"0\",\"token\":\"tk-1\"}", "{\"code\":\"0\",\"phone\":\"13912345678\"}");
        provider(t).acquireNumber(null);

        Map<String, String> q = queryMap(t.urls.get(1));
        assertEquals("getPhone", q.get("api"));
        assertEquals(SID, q.get("sid"));
        assertEquals("adminzfz", q.get("author"));
        assertEquals("tk-1", q.get("token"));
        // 未暴露配置项：空值剔除后不传
        assertFalse(q.containsKey("phone"));
        assertFalse(q.containsKey("isp"));
        assertFalse(q.containsKey("Province"));
        assertFalse(q.containsKey("ascription"));
        assertFalse(q.containsKey("paragraph"));
        assertFalse(q.containsKey("exclude"));
    }

    @Test
    public void getMessage_andRelease_andBlock_params() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"-1\",\"msg\":\"等待短信到达\"}",
                "{\"code\":\"0\"}",
                "{\"code\":\"0\"}");
        HaozhuSmsProvider p = provider(t);
        SmsProvider.PhoneOrder order = new SmsProvider.PhoneOrder("13912345678", "");
        p.fetchSms(order, null);
        p.release(order);
        p.block(order);

        Map<String, String> qMsg = queryMap(t.urls.get(1));
        assertEquals("getMessage", qMsg.get("api"));
        assertEquals(SID, qMsg.get("sid"));
        assertEquals("13912345678", qMsg.get("phone"));
        Map<String, String> qRelease = queryMap(t.urls.get(2));
        assertEquals("cancelRecv", qRelease.get("api"));
        assertEquals(SID, qRelease.get("sid"));
        Map<String, String> qBlock = queryMap(t.urls.get(3));
        assertEquals("addBlacklist", qBlock.get("api"));
        assertEquals(SID, qBlock.get("sid"));
    }

    // ---------------- code 语义 ----------------

    @Test
    public void waiting_whenCodeMinusOneAndWaitingMsg() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"-1\",\"msg\":\"等待短信中\"}");
        SmsProvider.SmsMessage m = provider(t)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null);
        assertTrue(m.waiting);
        assertEquals("", m.code);
        assertEquals("{\"code\":\"-1\",\"msg\":\"等待短信中\"}", m.raw);
    }

    @Test
    public void throws_whenCodeMinusOneOtherMsg_noRelogin() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"-1\",\"msg\":\"号码已下线\"}");
        try {
            provider(t).fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null);
            fail("应抛 SmsException");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("号码已下线"));
            assertTrue(e.getMessage().contains("getMessage"));
        }
        // code=-1 且 msg 不含 token：不属于 token 失效，不触发重登
        assertEquals(1, countLogin(t));
    }

    @Test
    public void bothYzmAndSmsEmpty_returnsWaiting() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"yzm\":\"\",\"sms\":\"\"}");
        SmsProvider.SmsMessage m = provider(t)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null);
        assertTrue(m.waiting);
    }

    // ---------------- token 失效自动重登重试一次 ----------------

    @Test
    public void tokenInvalid_relogsOnce_andRetriesSucceeds() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"7\",\"msg\":\"token已失效\"}",
                "{\"code\":\"0\",\"token\":\"tk-2\"}",
                "{\"code\":\"0\",\"phone\":\"13912345678\"}");
        SmsProvider.PhoneOrder order = provider(t).acquireNumber(null);

        assertEquals("13912345678", order.phone);
        assertEquals(4, t.urls.size());
        assertEquals(2, countLogin(t));
        assertEquals("tk-1", queryMap(t.urls.get(1)).get("token"));
        assertEquals("tk-2", queryMap(t.urls.get(3)).get("token"));
    }

    @Test
    public void tokenInvalid_minusOneMsgContainsToken_relogsOnce() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"-1\",\"msg\":\"Token 过期\"}",
                "{\"code\":\"0\",\"token\":\"tk-2\"}",
                "{\"code\":\"0\",\"yzm\":\"111111\",\"sms\":\"验证码111111\"}");
        SmsProvider.SmsMessage m = provider(t)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null);

        assertFalse(m.waiting);
        assertEquals("111111", m.code);
        assertEquals(2, countLogin(t));
    }

    @Test
    public void tokenInvalid_retryStillFails_throwsWithoutThirdLogin() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"7\",\"msg\":\"token已失效\"}",
                "{\"code\":\"0\",\"token\":\"tk-2\"}",
                "{\"code\":\"8\",\"msg\":\"仍然失效\"}");
        try {
            provider(t).acquireNumber(null);
            fail("重试仍失败应抛 SmsException");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("仍然失效"));
        }
        // 只重登一次，不无限循环
        assertEquals(2, countLogin(t));
    }

    // ---------------- yzm 直取 / sms 正则兜底（4-8 位边界） ----------------

    @Test
    public void yzm_regexFallback_fromSmsText() {
        // yzm 空但 sms 含 6 位验证码
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"yzm\":\"\",\"sms\":\"您的验证码是 135790 请及时使用\"}");
        assertEquals("135790", provider(t)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null).code);

        // 4 位下边界
        ScriptedTransport t2 = new ScriptedTransport();
        t2.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"sms\":\"验证码1234\"}");
        assertEquals("1234", provider(t2)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null).code);

        // 8 位上边界
        ScriptedTransport t3 = new ScriptedTransport();
        t3.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"sms\":\"编号12345678有效\"}");
        assertEquals("12345678", provider(t3)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null).code);
    }

    @Test
    public void sms_noStandaloneDigits_returnsWaiting() {
        // 9 位连续数字不满足 4-8 位边界 → 正则不命中 → waiting=true
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"yzm\":\"\",\"sms\":\"订单号123456789已生成\"}");
        SmsProvider.SmsMessage m = provider(t)
                .fetchSms(new SmsProvider.PhoneOrder("13912345678", ""), null);
        assertTrue(m.waiting);
    }

    // ---------------- testConnection ----------------

    @Test
    public void testConnection_summaryFields() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"money\":\"8.88\",\"maxPhone\":\"20\"}");
        SmsProvider.ProviderStatus s = provider(t).testConnection();

        assertTrue(s.ok);
        assertEquals("8.88", s.balanceText);
        assertEquals(20, s.maxNumbers);
        assertEquals(PassportIds.mask(USER), s.credentialMask);
        assertEquals("", s.error);
        assertEquals("getSummary", queryMap(t.urls.get(1)).get("api"));
    }

    @Test
    public void testConnection_noMaxNumberField_returnsMinusOne() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"0\",\"money\":\"1.00\"}");
        assertEquals(-1, provider(t).testConnection().maxNumbers);
    }

    @Test
    public void testConnection_failure_returnsFailStatusWithoutThrowing() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond(
                "{\"code\":\"0\",\"token\":\"tk-1\"}",
                "{\"code\":\"-1\",\"msg\":\"账号被封禁\"}");
        SmsProvider.ProviderStatus s = provider(t).testConnection();

        assertFalse(s.ok);
        assertTrue(s.error.contains("账号被封禁"));
        assertEquals(PassportIds.mask(USER), s.credentialMask);
    }

    // ---------------- 异常消息不含凭据 ----------------

    @Test
    public void exceptionMessages_containNoCredentials() {
        // login 失败：上游 msg 进消息，但 user/password/token 不出现
        ScriptedTransport t = new ScriptedTransport();
        t.respond("{\"code\":\"-1\",\"msg\":\"用户名或密码错误\"}");
        try {
            provider(t).acquireNumber(null);
            fail("login 失败应抛 SmsException");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("用户名或密码错误"));
            assertFalse(e.getMessage().contains(PASS));
            assertFalse(e.getMessage().contains(USER));
            assertFalse(e.getMessage().contains("tk-"));
        }
    }

    @Test
    public void transport_ioException_wrappedAsSmsException() {
        ScriptedTransport t = new ScriptedTransport();
        t.throwIo = new IOException("read timeout");
        try {
            provider(t).acquireNumber(null);
            fail("应抛网络异常");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("网络失败"));
            assertTrue(e.getCause() instanceof IOException);
            assertFalse(e.getMessage().contains(PASS));
        }
    }

    @Test
    public void constructor_missingParams_throws() {
        ScriptedTransport t = new ScriptedTransport();
        try {
            new HaozhuSmsProvider("", "", PASS, SID, t);
            fail("缺 user 应拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("user"));
        }
        try {
            new HaozhuSmsProvider("", USER, "", SID, t);
            fail("缺 password 应拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("password"));
        }
        try {
            new HaozhuSmsProvider("", USER, PASS, " ", t);
            fail("缺 sid 应拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("sid"));
        }
    }

    // ---------------- 工具 ----------------

    /** 解析 URL query 为键值表（UTF-8 解码），供参数断言。 */
    private static Map<String, String> queryMap(String url) {
        Map<String, String> m = new LinkedHashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            try {
                m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (Exception e) {
                throw new IllegalStateException("query 解码失败: " + pair, e);
            }
        }
        return m;
    }
}
