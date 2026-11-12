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
 * D1SmsProvider 单测：URL/参数拼装（空值剔除 + 中文 URLEncode）/ERROR: 前缀转异常（消息不含 token）/
 * 三种响应形态宽容解析（JSON 串、纯 11 位手机号、含验证码文本）/全操作 happy path。
 *
 * <p>全部用注入的假 Transport，不联网。
 */
public class D1SmsProviderTest {

    private static final String TOKEN = "d1-token-abcdef123456";

    /** 捕获请求 URL 并按脚本逐请求回放响应的假 Transport（脚本耗尽后重复最后一个）。 */
    private static final class ScriptedTransport implements D1SmsProvider.Transport {
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

    private static D1SmsProvider provider(ScriptedTransport t) {
        return new D1SmsProvider(TOKEN, "", t);
    }

    // ---------------- URL / 参数拼装 ----------------

    @Test
    public void getPhone_urlAssembly_encodesChinese_andDropsBlankParams() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("13800138000");
        provider(t).acquireNumber("点点互动");

        String url = t.urls.get(0);
        assertTrue(url.startsWith(D1SmsProvider.DEFAULT_BASE_URL));
        // 中文关键词已 URL 编码（原文不裸奔）
        assertFalse(url.contains("点点互动"));
        Map<String, String> q = queryMap(url);
        assertEquals("getPhone", q.get("code"));
        assertEquals(TOKEN, q.get("token"));
        assertEquals("点点互动", q.get("keyWord"));
        // 未暴露配置项：空值剔除后不传
        assertFalse(q.containsKey("phone"));
        assertFalse(q.containsKey("province"));
        assertFalse(q.containsKey("cardType"));
        // 顺序：code 首位、token 次位、业务参数随后
        assertTrue(url.indexOf("code=getPhone") < url.indexOf("token="));
        assertTrue(url.indexOf("token=") < url.indexOf("keyWord="));
    }

    @Test
    public void acquire_nullKeyword_droppedFromQuery() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("13800138000");
        provider(t).acquireNumber(null);

        assertFalse(queryMap(t.urls.get(0)).containsKey("keyWord"));
    }

    @Test
    public void blankBaseUrl_fallsBackToDefault() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("{}");
        new D1SmsProvider(TOKEN, "   ", t).testConnection();
        assertTrue(t.urls.get(0).startsWith(D1SmsProvider.DEFAULT_BASE_URL));
    }

    // ---------------- ERROR: 前缀 ----------------

    @Test
    public void errorPrefix_throwsSmsException_withoutToken() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("ERROR:余额不足，请充值");
        try {
            provider(t).acquireNumber("点点互动");
            fail("应抛 SmsException");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("余额不足"));
            assertTrue(e.getMessage().contains("D1"));
            assertTrue(e.getMessage().contains("getPhone"));
            assertFalse(e.getMessage().contains(TOKEN));
        }
    }

    @Test
    public void testConnection_errorPrefix_returnsFailStatus() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("ERROR:token无效");
        SmsProvider.ProviderStatus s = provider(t).testConnection();

        assertFalse(s.ok);
        assertEquals(-1, s.maxNumbers);
        assertTrue(s.error.contains("token无效"));
        assertEquals(PassportIds.mask(TOKEN), s.credentialMask);
    }

    // ---------------- 宽容解析：三种成功文本形态 ----------------

    @Test
    public void parse_jsonForms_phoneCodeAndBalance() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("{\"phone\":\"13800138000\",\"order\":\"order-1\"}");
        SmsProvider.PhoneOrder order = provider(t).acquireNumber(null);
        assertEquals("13800138000", order.phone);
        assertEquals("order-1", order.providerData);

        ScriptedTransport t2 = new ScriptedTransport();
        t2.respond("{\"yzm\":\"654321\",\"sms\":\"【点点互动】您的验证码654321\"}");
        SmsProvider.SmsMessage msg = provider(t2).fetchSms(new SmsProvider.PhoneOrder("13800138000", ""), "点点互动");
        assertFalse(msg.waiting);
        assertEquals("654321", msg.code);
        assertTrue(msg.content.contains("点点互动"));
        assertEquals("{\"yzm\":\"654321\",\"sms\":\"【点点互动】您的验证码654321\"}", msg.raw);

        ScriptedTransport t3 = new ScriptedTransport();
        t3.respond("{\"money\":\"12.34\"}");
        SmsProvider.ProviderStatus s = provider(t3).testConnection();
        assertTrue(s.ok);
        assertEquals("12.34", s.balanceText);
    }

    @Test
    public void parse_plainPhoneText() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("13800138000");
        SmsProvider.PhoneOrder order = provider(t).acquireNumber(null);
        assertEquals("13800138000", order.phone);
        assertEquals("", order.providerData);
    }

    @Test
    public void parse_codeTextTolerant() {
        // 形态一：普通验证码文本（无日期干扰）——独立 4-8 位规则命中
        ScriptedTransport t = new ScriptedTransport();
        t.respond("【点点互动】您的验证码是654321，请勿泄露");
        SmsProvider.SmsMessage m1 = provider(t).fetchSms(new SmsProvider.PhoneOrder("13800138000", ""), null);
        assertFalse(m1.waiting);
        assertEquals("654321", m1.code);

        // 形态二：4 位验证码
        ScriptedTransport t2 = new ScriptedTransport();
        t2.respond("您的验证码：1234，10分钟内有效");
        assertEquals("1234", provider(t2)
                .fetchSms(new SmsProvider.PhoneOrder("13800138000", ""), null).code);

        // 形态三：日期数字在前的文本——关键词邻近规则跳过 2026/11/07 命中验证码
        ScriptedTransport t3 = new ScriptedTransport();
        t3.respond("2026年11月07日 验证码 987654，请及时使用");
        assertEquals("987654", provider(t3)
                .fetchSms(new SmsProvider.PhoneOrder("13800138000", ""), null).code);
    }

    @Test
    public void fetch_noCodeText_returnsWaiting() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("暂无短信，请稍后再试");
        SmsProvider.SmsMessage m = provider(t)
                .fetchSms(new SmsProvider.PhoneOrder("13800138000", ""), "点点互动");
        // 解析不到验证码 → waiting=true（不抛错）
        assertTrue(m.waiting);
        assertEquals("", m.code);
        assertEquals("暂无短信，请稍后再试", m.raw);
    }

    @Test
    public void acquire_unparseableResponse_throws() {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("<html>bad gateway</html>");
        try {
            provider(t).acquireNumber(null);
            fail("解析不出号码应抛 SmsException");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("未能从响应解析出号码"));
            assertFalse(e.getMessage().contains(TOKEN));
        }
    }

    // ---------------- 全操作 happy path ----------------

    @Test
    public void testConnection_happyPath() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("12.34");
        SmsProvider.ProviderStatus s = provider(t).testConnection();

        assertTrue(s.ok);
        assertEquals("12.34", s.balanceText);
        assertEquals(-1, s.maxNumbers);
        assertEquals(PassportIds.mask(TOKEN), s.credentialMask);
        assertEquals("", s.error);
        assertEquals("leftAmount", queryMap(t.urls.get(0)).get("code"));
    }

    @Test
    public void fetch_params_phoneAndKeyword() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("您的验证码是246810");
        provider(t).fetchSms(new SmsProvider.PhoneOrder("13800138000", ""), "点点互动");

        Map<String, String> q = queryMap(t.urls.get(0));
        assertEquals("getMsg", q.get("code"));
        assertEquals("13800138000", q.get("phone"));
        assertEquals("点点互动", q.get("keyWord"));
    }

    @Test
    public void release_andBlock_params() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        t.respond("OK", "OK");
        D1SmsProvider p = provider(t);
        SmsProvider.PhoneOrder order = new SmsProvider.PhoneOrder("13800138000", "");
        p.release(order);
        p.block(order);

        Map<String, String> q1 = queryMap(t.urls.get(0));
        assertEquals("release", q1.get("code"));
        assertEquals("13800138000", q1.get("phone"));
        Map<String, String> q2 = queryMap(t.urls.get(1));
        assertEquals("block", q2.get("code"));
        assertEquals("13800138000", q2.get("phone"));
    }

    @Test
    public void transport_ioException_wrappedAsSmsException() {
        ScriptedTransport t = new ScriptedTransport();
        t.throwIo = new IOException("connect timeout");
        try {
            provider(t).acquireNumber(null);
            fail("应抛网络异常");
        } catch (SmsException e) {
            assertTrue(e.getMessage().contains("网络失败"));
            assertTrue(e.getMessage().contains("connect timeout"));
            assertFalse(e.getMessage().contains(TOKEN));
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void constructor_missingToken_throws() {
        try {
            new D1SmsProvider("  ", "", new ScriptedTransport());
            fail("缺 token 应拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("token"));
        }
    }

    // ---------------- 工具 ----------------

    /** 解析 URL query 为键值表（UTF-8 解码），供参数断言。 */
    private static Map<String, String> queryMap(String url) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    /** 真实轮回归（2026-11-10）：D1 未到码提示语不得被解析成验证码（尤其「请5秒后再收取」的 5）。 */
    @Test
    public void notYetReceivedHint_isWaiting_notCode() {
        String hint = "尚未收到包含关键字“点点互动”的短信，请5秒后再收取。请确保设置了正确的关键字。[尚未收到]";
        D1SmsProvider p = new D1SmsProvider("tk", null, url -> hint);
        SmsProvider.SmsMessage msg = p.fetchSms(new SmsProvider.PhoneOrder("16280908634", ""), "点点互动");
        assertTrue("应判定为未到码", msg.waiting);
        assertEquals("不得把提示语数字当验证码", "", msg.code);
    }

    /** 真实轮回归：真实短信文本（手机号/价格/内容）应提取到关键词后的 4 位验证码。 */
    @Test
    public void realSmsText_extractsKeywordCode() {
        String sms = "16280908634/0.22/【点点互动】您的验证码是：3710，5分钟内有效。为了您的账号安全，请不要把验证码泄露给任何人。";
        D1SmsProvider p = new D1SmsProvider("tk", null, url -> sms);
        SmsProvider.SmsMessage msg = p.fetchSms(new SmsProvider.PhoneOrder("16280908634", ""), "点点互动");
        assertFalse("已到码不应 waiting", msg.waiting);
        assertEquals("3710", msg.code);
    }

}
