package com.carlos.vault.wechat;

import com.carlos.vault.AccountStore;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.TreeSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link WechatQrLogin} 纯逻辑单测：配置解析、二维码/轮询、微信身份、passport 签名与响应解析。
 */
public class WechatQrLoginTest {

    // ---------- 配置 ----------

    private static final String CG_XML =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                    + "    <string name=\"config_set\">{\"social\":{\"wechat\":{\"app_id\":\"wx32374c9038cf9759\","
                    + "\"app_key\":\"a6f69f7d4ab7606bcd3561e8cb23a376\",\"mini_program_id\":\"gh_x\"},"
                    + "\"douyin\":{\"client_key\":\"aw\"}},\"account\":{}}</string>\n"
                    + "    <string name=\"other\">x</string>\n</map>";

    @Test
    public void parseConfigReadsWechatAppIdAndKey() {
        WechatQrLogin.Config c = WechatQrLogin.parseConfig(CG_XML);
        assertNotNull(c);
        assertTrue(c.isValid());
        assertEquals("wx32374c9038cf9759", c.appId);
        assertEquals("a6f69f7d4ab7606bcd3561e8cb23a376", c.appKey);
    }

    @Test
    public void parseConfigHandlesXmlEscapedJson() {
        // 真实 SharedPreferences XML 只转义内容里的引号，不转义 XML 属性
        String json = "{\"social\":{\"wechat\":{\"app_id\":\"wx32374c9038cf9759\","
                + "\"app_key\":\"a6f69f7d4ab7606bcd3561e8cb23a376\"}}}";
        String escaped = "<map><string name=\"config_set\">"
                + json.replace("\"", "&quot;") + "</string></map>";
        WechatQrLogin.Config c = WechatQrLogin.parseConfig(escaped);
        assertNotNull(c);
        assertEquals("wx32374c9038cf9759", c.appId);
    }

    @Test
    public void parseConfigRejectsMissingOrEmpty() {
        assertNull(WechatQrLogin.parseConfig(null));
        assertNull(WechatQrLogin.parseConfig(""));
        assertNull(WechatQrLogin.parseConfig("<map></map>"));
        assertNull(WechatQrLogin.parseConfig(
                "<map><string name=\"config_set\">{\"social\":{\"wechat\":{\"app_id\":\"\"}}}</string></map>"));
    }

    // ---------- 二维码 ----------

    @Test
    public void buildQrPageUrlContainsEncodedParams() {
        WechatQrLogin.Config c = new WechatQrLogin.Config("wx123", "k");
        String url = WechatQrLogin.buildQrPageUrl(c, "state123");
        assertTrue(url.startsWith(WechatQrLogin.QR_PAGE + "?"));
        assertTrue(url.contains("appid=wx123"));
        assertTrue(url.contains("bundleid=com.gof.china"));
        assertTrue(url.contains("state=state123"));
        // scope 里有逗号，必须被编码
        assertTrue(url.contains("scope=snsapi_base%2Csnsapi_userinfo"));
    }

    @Test
    public void parseUuidFromQrImageUrlAndConfirmUrl() {
        String html1 = "<img src=\"https://open.weixin.qq.com/connect/qrcode/051wtCMs3QDpFa1q\"/>";
        assertEquals("051wtCMs3QDpFa1q", WechatQrLogin.parseUuid(html1));

        String html2 = "<a href=\"https://open.weixin.qq.com/connect/confirm?uuid=001KMabQ2mcJkl2Q\">x</a>";
        assertEquals("001KMabQ2mcJkl2Q", WechatQrLogin.parseUuid(html2));

        assertEquals("", WechatQrLogin.parseUuid(""));
        assertEquals("", WechatQrLogin.parseUuid("<html>no uuid</html>"));
    }

    @Test
    public void parsePollClassifiesStates() {
        // 关键实测：App 扫码确认后微信可能回 405 + wx_code，必须判 READY
        WechatQrLogin.Poll ready405 = WechatQrLogin.parsePoll(
                "{\"wx_errcode\":405, \"wx_code\":\"071GbsFa15FZFM0hZ8Ia1orY6Y2GbsFu\"}");
        assertEquals(WechatQrLogin.PollState.READY, ready405.state);
        assertTrue(ready405.isReady());
        assertEquals("071GbsFa15FZFM0hZ8Ia1orY6Y2GbsFu", ready405.code);

        WechatQrLogin.Poll ready0 = WechatQrLogin.parsePoll("{\"wx_errcode\":0, \"wx_code\":\"abc\"}");
        assertEquals(WechatQrLogin.PollState.READY, ready0.state);

        WechatQrLogin.Poll waiting = WechatQrLogin.parsePoll("{\"wx_errcode\":408}");
        assertEquals(WechatQrLogin.PollState.WAITING, waiting.state);

        WechatQrLogin.Poll scanned = WechatQrLogin.parsePoll("{\"wx_errcode\":405, \"wx_code\":\"\"}");
        assertEquals(WechatQrLogin.PollState.SCANNED, scanned.state);

        WechatQrLogin.Poll expired = WechatQrLogin.parsePoll("{\"wx_errcode\":400}");
        assertEquals(WechatQrLogin.PollState.EXPIRED, expired.state);

        WechatQrLogin.Poll jsStyle = WechatQrLogin.parsePoll(
                "window.wx_errcode=405;window.wx_code='code_x';");
        assertEquals(WechatQrLogin.PollState.READY, jsStyle.state);
        assertEquals("code_x", jsStyle.code);
    }

    // ---------- 微信身份 ----------

    @Test
    public void parseTokenAndMergeUserInfo() {
        String token = "{\"access_token\":\"AT\",\"expires_in\":7200,\"openid\":\"OPENID\",\"unionid\":\"UNION\"}";
        WechatQrLogin.Identity id = WechatQrLogin.parseToken(token);
        assertNotNull(id);
        assertEquals("OPENID", id.openid);
        assertEquals("AT", id.accessToken);
        assertEquals("UNION", id.unionId);

        String user = "{\"openid\":\"OPENID\",\"nickname\":\"春秋\",\"unionid\":\"U2\"}";
        WechatQrLogin.Identity merged = WechatQrLogin.mergeUserInfo(user, id);
        assertNotNull(merged);
        assertEquals("春秋", merged.nickname);

        assertNull(WechatQrLogin.parseToken("{\"errcode\":40029,\"errmsg\":\"invalid code\"}"));
        assertNull(WechatQrLogin.parseToken("not-json"));
    }

    // ---------- passport ----------

    @Test
    public void passportFormHasRequiredFields() {
        WechatQrLogin.Identity id = new WechatQrLogin.Identity("OPENID", "AT", "UNION", "nick");
        Map<String, String> form = WechatQrLogin.buildPassportForm(id);
        assertEquals("login_with_sns", form.get("method"));
        assertEquals(WechatQrLogin.GAME_ID, form.get("game_id"));
        assertEquals("login_with_sns", form.get("service_name"));
        assertEquals("wx:OPENID", form.get("platform_id"));
        assertEquals("wx", form.get("social_type"));
        assertEquals("android", form.get("os"));
        assertEquals("AT", form.get("access_token"));
        assertEquals("UNION", form.get("union_id"));
        assertEquals("", form.get("session_key"));   // 空值必须带 key（工具箱一致）
        assertFalse(form.get("guid").isEmpty());
        assertEquals(form.get("guid"), form.get("origin_guid"));
    }

    @Test
    public void passportFormOmitsUnionIdWhenBlank() {
        WechatQrLogin.Identity id = new WechatQrLogin.Identity("O", "A", "", "");
        Map<String, String> form = WechatQrLogin.buildPassportForm(id);
        assertFalse(form.containsKey("union_id"));
    }

    @Test
    public void passportSignatureMatchesHmacBase64Hex() throws Exception {
        WechatQrLogin.Identity id = new WechatQrLogin.Identity("OPENID", "AT", "UNION", "");
        Map<String, String> form = WechatQrLogin.buildPassportForm(id);
        String sign = WechatQrLogin.signPassportJava(form);

        // 独立复算：字典序 k=v&… → HMAC-SHA256 → hex → base64
        StringBuilder src = new StringBuilder();
        for (String key : new TreeSet<>(form.keySet())) {
            if (src.length() > 0) src.append('&');
            src.append(key).append('=').append(form.get(key));
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WechatQrLogin.GAME_KEY.getBytes(Charset.forName("UTF-8")), "HmacSHA256"));
        byte[] digest = mac.doFinal(src.toString().getBytes(Charset.forName("UTF-8")));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
        String expected = "FP " + WechatQrLogin.GAME_ID + ":"
                + Base64.getEncoder().encodeToString(hex.toString().getBytes(Charset.forName("UTF-8")));

        assertEquals(expected, sign);
        assertTrue(sign.startsWith("FP 20173:"));
    }

    @Test
    public void parsePassportFindsFpidAndSessionKey() {
        String resp = "{\"status\":1,\"data\":{\"fpid\":\"24412302\",\"session_key\":\"SK123\"}}";
        WechatQrLogin.Session s = WechatQrLogin.parsePassport(resp);
        assertNotNull(s);
        assertEquals("24412302", s.fpid);
        assertEquals("SK123", s.sessionKey);
        assertTrue(s.isValid());
    }

    @Test
    public void parsePassportSupportsLoginProfileAndAliases() {
        String resp = "{\"status\":1,\"data\":{\"account\":\"888\",\"login_profile\":{\"sdkToken\":\"TOK\"}}}";
        WechatQrLogin.Session s = WechatQrLogin.parsePassport(resp);
        assertNotNull(s);
        assertEquals("888", s.fpid);
        assertEquals("TOK", s.sessionKey);

        String resp2 = "{\"status\":1,\"data\":{\"fp_id\":\"1\",\"sdk_token\":\"2\"}}";
        assertEquals("2", WechatQrLogin.parsePassport(resp2).sessionKey);
    }

    @Test
    public void parsePassportRejectsMissingData() {
        assertNull(WechatQrLogin.parsePassport("{}"));
        assertNull(WechatQrLogin.parsePassport("{\"status\":0}"));
        assertNull(WechatQrLogin.parsePassport("{\"data\":{\"fpid\":\"1\"}}"));  // 缺 session_key
        assertNull(WechatQrLogin.parsePassport(null));
    }

    // ---------- 落库（applySession） ----------

    private static AccountStore.Account account(String id, String fpid, String label) {
        AccountStore.Account a = new AccountStore.Account();
        a.id = id;
        a.fpid = fpid;
        a.label = label;
        return a;
    }

    @Test
    public void applySessionBindsToEntryAndKeepsUserLabel() {
        List<AccountStore.Account> list = new ArrayList<>();
        AccountStore.Account entry = account("e1", "123", "我的小号");
        list.add(entry);
        WechatQrLogin.Session s = new WechatQrLogin.Session("999", "TOKEN");

        AccountStore.Account target = WechatQrLogin.applySession(list, "e1", s, "微信·春秋");

        assertSame(entry, target);
        assertEquals(1, list.size());
        assertEquals("999", target.fpid);
        assertEquals("TOKEN", target.token);
        assertEquals("我的小号", target.label);       // 用户自定义备注保留
        assertTrue(target.lastLogin > 0);
    }

    @Test
    public void applySessionReplacesAutoLabelOnEntry() {
        List<AccountStore.Account> list = new ArrayList<>();
        AccountStore.Account entry = account("e1", "123", "123");   // 备注=旧号ID（自动备注）
        list.add(entry);

        WechatQrLogin.applySession(list, "e1", new WechatQrLogin.Session("999", "T"), "微信·春秋");
        assertEquals("微信·春秋", entry.label);
    }

    @Test
    public void applySessionCreatesEntryBoundAccountWhenMissing() {
        List<AccountStore.Account> list = new ArrayList<>();
        AccountStore.Account target = WechatQrLogin.applySession(
                list, "e9", new WechatQrLogin.Session("999", "T"), "微信·春秋");
        assertNotNull(target);
        assertEquals(1, list.size());
        assertEquals("e9", target.id);
        assertEquals("999", target.fpid);
        assertEquals("T", target.token);
        assertEquals("微信·春秋", target.label);
        assertEquals("", target.group);
    }

    @Test
    public void applySessionStandaloneUpdatesExistingByFpid() {
        List<AccountStore.Account> list = new ArrayList<>();
        AccountStore.Account old = account("999", "999", "老备注");
        list.add(old);

        AccountStore.Account target = WechatQrLogin.applySession(
                list, null, new WechatQrLogin.Session("999", "T2"), "微信·x");
        assertSame(old, target);
        assertEquals("T2", target.token);
        assertEquals("老备注", target.label);
        assertEquals(1, list.size());
    }

    @Test
    public void applySessionStandaloneCreatesNewUncategorized() {
        List<AccountStore.Account> list = new ArrayList<>();
        AccountStore.Account target = WechatQrLogin.applySession(
                list, "", new WechatQrLogin.Session("777", "T3"), "微信·y");
        assertNotNull(target);
        assertEquals("777", target.id);
        assertEquals("", target.group);
        assertEquals(1, list.size());
    }

    @Test
    public void applySessionRejectsInvalidInput() {
        assertNull(WechatQrLogin.applySession(null, "e1",
                new WechatQrLogin.Session("1", "2"), "x"));
        assertNull(WechatQrLogin.applySession(new ArrayList<>(), "e1",
                new WechatQrLogin.Session("", ""), "x"));
        assertNull(WechatQrLogin.applySession(new ArrayList<>(), "e1", null, "x"));
    }

    // ---------- 杂项 ----------

    @Test
    public void md5HexKnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", WechatQrLogin.md5Hex("abc"));
    }

    @Test
    public void cgSeidConstantMatchesDerivation() {
        // cg-seid = md5(fpcs_source + "7CPsU5puEk")，与工具箱默认值一致
        assertEquals(WechatQrLogin.CG_SEID,
                WechatQrLogin.md5Hex(WechatQrLogin.FPCS_SOURCE + "7CPsU5puEk"));
    }

    @Test
    public void base64EncoderMatchesJdk() {
        byte[] data = "hello world".getBytes(Charset.forName("UTF-8"));
        assertEquals(Base64.getEncoder().encodeToString(data), WechatQrLogin.base64(data));
        byte[] one = new byte[]{1};
        assertEquals(Base64.getEncoder().encodeToString(one), WechatQrLogin.base64(one));
        byte[] two = new byte[]{1, 2};
        assertEquals(Base64.getEncoder().encodeToString(two), WechatQrLogin.base64(two));
    }

    @Test
    public void randomHexRespectsLengthAndCharset() {
        String h = WechatQrLogin.randomHex(16);
        assertEquals(16, h.length());
        assertTrue(h.matches("[0-9a-f]+"));
        assertEquals(16, WechatQrLogin.randomState().length());
    }
}
