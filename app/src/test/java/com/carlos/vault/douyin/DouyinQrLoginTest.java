package com.carlos.vault.douyin;

import com.carlos.vault.AccountStore;
import com.carlos.vault.wechat.WechatQrLogin;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link DouyinQrLogin} 纯逻辑单测：模板、cookie、二维码/轮询、小游戏授权码、
 * passport 表单/签名/解析、落库规则。
 */
public class DouyinQrLoginTest {

    private static final long NOW = 1793809082000L; // 固定时间，避免测试受当前时钟影响

    // ---------- 模板 ----------

    @Test
    public void builtInTemplateHasQrEndpoints() {
        DouyinQrLogin.Template t = DouyinQrLogin.Template.builtIn();
        assertTrue(t.isValid());
        assertTrue(t.fetchUrl.contains("/passport/web/get_qrcode/"));
        assertTrue(t.checkUrl.contains("/passport/web/check_qrconnect/"));
        assertFalse(t.userAgent.isEmpty());
    }

    @Test
    public void templateParseAcceptsOverride() {
        DouyinQrLogin.Template t = DouyinQrLogin.Template.parse(
                "{\"getQrUrl\":\"https://a/fetch\",\"checkQrUrl\":\"https://a/check\","
                        + "\"userAgent\":\"UA\"}");
        assertNotNull(t);
        assertEquals("https://a/fetch", t.fetchUrl);
        assertEquals("https://a/check", t.checkUrl);
        assertEquals("UA", t.userAgent);
    }

    @Test
    public void templateParseRejectsBrokenJsonAndMissingUrls() {
        assertNull(DouyinQrLogin.Template.parse("{oops"));
        assertNull(DouyinQrLogin.Template.parse("{\"getQrUrl\":\"https://a/fetch\"}"));
        assertNull(DouyinQrLogin.Template.parse(""));
    }

    // ---------- URL 刷新 ----------

    @Test
    public void timestampsAreRefreshed() {
        String url = "https://x/p?ts=1&p_ts=2&next=%2F&pid_ts=3";
        String out = DouyinQrLogin.withCurrentTimestamps(url, NOW);
        assertTrue(out.contains("ts=" + (NOW / 1000L)));
        assertTrue(out.contains("p_ts=" + NOW));
        // pid_ts 不能被误改
        assertTrue(out.contains("pid_ts=3"));
    }

    @Test
    public void msTokenIsReplacedOrAppended() {
        assertEquals("https://x/p?ts=1&msToken=ab%2Bcd%2Fef%3D&a=b",
                DouyinQrLogin.withMsToken("https://x/p?ts=1&msToken=old&a=b", "ab+cd/ef="));
        assertEquals("https://x/p?ts=1&msToken=ab%2Bcd",
                DouyinQrLogin.withMsToken("https://x/p?ts=1", "ab+cd"));
        assertEquals("https://x/p?ts=1",
                DouyinQrLogin.withMsToken("https://x/p?ts=1", ""));
    }

    // ---------- cookie ----------

    @Test
    public void cookieJarStoresAndBuildsHeader() {
        DouyinQrLogin.CookieJar jar = new DouyinQrLogin.CookieJar();
        jar.store("passport_csrf_token=abc; Path=/; Domain=douyin.com; Secure");
        jar.store("sessionid=def; Path=/; HttpOnly");
        assertEquals("abc", jar.value("passport_csrf_token"));
        assertEquals("def", jar.value("sessionid"));
        String header = jar.header();
        assertTrue(header.contains("passport_csrf_token=abc"));
        assertTrue(header.contains("sessionid=def"));
        assertEquals("", jar.value("missing"));
    }

    @Test
    public void cookieJarRemovesExpiredAndIgnoresMalformed() {
        DouyinQrLogin.CookieJar jar = new DouyinQrLogin.CookieJar();
        jar.store("ttwid=1");
        jar.store("ttwid=; Max-Age=0; Path=/");
        assertEquals("", jar.value("ttwid"));
        jar.store("no-equals-sign");
        jar.store(null);
        jar.store("");
        assertEquals(0, jar.size());
    }

    // ---------- 二维码 ----------

    private static String qrJson(String qrcode, String token, long expire, boolean frontier) {
        return "{\"data\":{\"error_code\":0,\"description\":\"\",\"qrcode\":\"" + qrcode
                + "\",\"token\":\"" + token + "\",\"expire_time\":" + expire
                + ",\"is_frontier\":" + frontier + "},\"message\":\"success\"}";
    }

    @Test
    public void parseQrReadsImageTokenAndExpiry() {
        DouyinQrLogin.Qr qr = DouyinQrLogin.parseQr(
                qrJson("iVBORw0KGgo=", "tok_1", NOW / 1000L + 120, true), NOW);
        assertEquals("tok_1", qr.token);
        assertEquals("iVBORw0KGgo=", qr.imageBase64);
        assertEquals((NOW / 1000L + 120) * 1000L, qr.expireAtMillis);
        assertTrue(qr.frontier);
        assertFalse(qr.expired(NOW));
    }

    @Test
    public void parseQrStripsDataUriAndDefaultsExpiry() {
        DouyinQrLogin.Qr qr = DouyinQrLogin.parseQr(
                qrJson("data:image/png;base64,AAAA", "t", 0, false), NOW);
        assertEquals("AAAA", qr.imageBase64);
        assertEquals(NOW + DouyinQrLogin.DEFAULT_QR_TTL_MILLIS, qr.expireAtMillis);
        assertFalse(qr.frontier);
    }

    @Test
    public void parseQrRejectsErrorAndMissingFields() {
        String err = "{\"data\":{\"error_code\":12,\"description\":\"请求频繁\"}}";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DouyinQrLogin.parseQr(err, NOW));
        assertTrue(e.getMessage().contains("请求频繁"));
        assertThrows(IllegalStateException.class,
                () -> DouyinQrLogin.parseQr("{\"data\":{\"error_code\":0,\"qrcode\":\"x\"}}", NOW));
        assertThrows(IllegalStateException.class,
                () -> DouyinQrLogin.parseQr("not-json", NOW));
    }

    // ---------- 轮询 ----------

    private static DouyinQrLogin.Qr qr() {
        return new DouyinQrLogin.Qr("tok", "img", NOW + 120_000L, true, "ms");
    }

    @Test
    public void pollFormAndUrlCarryTokenAndMsToken() {
        String form = DouyinQrLogin.buildPollForm(qr());
        assertTrue(form.contains("token=tok"));
        assertTrue(form.contains("is_frontier=true"));
        assertTrue(form.contains("is_new_login=1"));
        assertTrue(form.contains("need_short_url=true"));
        assertTrue(form.contains("next=https%3A%2F%2Flive.douyin.com"));

        String url = DouyinQrLogin.buildPollUrl(DouyinQrLogin.Template.builtIn(), qr(), NOW);
        assertTrue(url.contains("msToken=ms"));
        assertTrue(url.contains("p_ts=" + NOW));
    }

    private static String pollJson(String status, String redirect) {
        return "{\"data\":{\"error_code\":0,\"status\":\"" + status
                + "\",\"redirect_url\":\"" + redirect + "\"},\"message\":\"success\"}";
    }

    @Test
    public void pollWaitingWhenNotScanned() {
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                pollJson("new", ""), new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.WAITING, p.state);
        assertTrue(p.message.contains("扫描"));
    }

    @Test
    public void pollScannedOnStatus2() {
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                pollJson("2", ""), new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.SCANNED, p.state);
    }

    @Test
    public void pollExpiredOnStatus5() {
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                pollJson("5", ""), new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.EXPIRED, p.state);
    }

    @Test
    public void pollConfirmedWhenSessionCookiePresent() {
        DouyinQrLogin.CookieJar jar = new DouyinQrLogin.CookieJar();
        jar.store("sessionid=sid_123; Path=/");
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(pollJson("new", ""), jar, NOW);
        assertEquals(DouyinQrLogin.PollState.CONFIRMED, p.state);
        assertEquals("sid_123", p.sessionId);
    }

    @Test
    public void pollStatus3KeepsRedirectForClientFollow() {
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                pollJson("3", "https://www.douyin.com/confirm?x=1"),
                new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.SCANNED, p.state);
        assertEquals("https://www.douyin.com/confirm?x=1", p.redirectUrl);
    }

    @Test
    public void pollStatus3WithoutRedirectProbesSessionUrl() {
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                pollJson("3", ""), new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.SCANNED, p.state);
        assertEquals(DouyinQrLogin.SESSION_URL, p.redirectUrl);
    }

    @Test
    public void base64PadsRemaindersCorrectly() {
        Charset utf8 = Charset.forName("UTF-8");
        assertEquals("Zm8=", DouyinQrLogin.base64("fo".getBytes(utf8)));
        assertEquals("Zm9h", DouyinQrLogin.base64("foa".getBytes(utf8)));
        assertEquals("Zm9vYg==", DouyinQrLogin.base64("foob".getBytes(utf8)));
    }

    @Test
    public void onlyDouyinHostsAreAllowedForCookies() {
        assertTrue(DouyinQrLogin.isDouyinHost("douyin.com"));
        assertTrue(DouyinQrLogin.isDouyinHost("live.douyin.com"));
        assertTrue(DouyinQrLogin.isDouyinHost("www.douyin.com."));
        assertTrue(DouyinQrLogin.isDouyinHost("snssdk.com"));
        assertFalse(DouyinQrLogin.isDouyinHost("evil-douyin.com"));
        assertFalse(DouyinQrLogin.isDouyinHost("douyin.com.evil.com"));
        assertFalse(DouyinQrLogin.isDouyinHost(null));
    }

    @Test
    public void passportConstantsStayInSyncWithWechat() {
        assertEquals(WechatQrLogin.GAME_ID, DouyinQrLogin.GAME_ID);
        assertEquals(WechatQrLogin.GAME_KEY, DouyinQrLogin.GAME_KEY);
        assertEquals(WechatQrLogin.PASSPORT_BASE, DouyinQrLogin.PASSPORT_BASE);
        assertEquals(WechatQrLogin.PASSPORT_PATH, DouyinQrLogin.PASSPORT_PATH);
    }

    @Test
    public void pollStatus4RefreshesQrImage() {
        String json = "{\"data\":{\"error_code\":0,\"status\":4,\"token\":\"tok2\","
                + "\"qrcode\":\"data:image/png;base64,BBBB\",\"expire_time\":"
                + (NOW / 1000L + 60) + "}}";
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(json, new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.WAITING, p.state);
        assertEquals("tok2", p.nextToken);
        assertEquals("BBBB", p.imageBase64);
        assertEquals((NOW / 1000L + 60) * 1000L, p.expireAtMillis);
    }

    @Test
    public void pollRateLimitedWaitsAndVerifyFails() {
        String limited = "{\"data\":{\"error_code\":7,\"description\":\"操作频繁\"}}";
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                limited, new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.WAITING, p.state);
        assertEquals(DouyinQrLogin.RATE_LIMIT_DELAY_MILLIS, p.retryAfterMillis);

        String verify = "{\"data\":{\"error_code\":2046,\"description\":\"需要验证\"}}";
        DouyinQrLogin.Poll v = DouyinQrLogin.parsePoll(
                verify, new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.ERROR, v.state);
        assertTrue(v.message.contains("验证"));
    }

    @Test
    public void pollErrorOnOtherCodes() {
        DouyinQrLogin.Poll p = DouyinQrLogin.parsePoll(
                "{\"data\":{\"error_code\":99,\"description\":\"系统繁忙\"}}",
                new DouyinQrLogin.CookieJar(), NOW);
        assertEquals(DouyinQrLogin.PollState.ERROR, p.state);
        assertTrue(p.message.contains("系统繁忙"));
    }

    // ---------- 小游戏授权码 ----------

    @Test
    public void gameCodeUrlCarriesAppIdAndAid() {
        String url = DouyinQrLogin.buildGameCodeUrl();
        assertTrue(url.contains("appid=" + DouyinQrLogin.DOUYIN_APP_ID));
        assertTrue(url.contains("aid=" + DouyinQrLogin.DOUYIN_AID));
        assertTrue(url.contains("device_platform=android"));
    }

    @Test
    public void parseGameCodeReturnsCode() {
        assertEquals("cg_1", DouyinQrLogin.parseGameCode(
                "{\"err_no\":0,\"data\":{\"isLogin\":true,\"code\":\"cg_1\"}}"));
    }

    @Test
    public void parseGameCodeRejectsErrors() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DouyinQrLogin.parseGameCode(
                        "{\"err_no\":100,\"err_tips\":\"session失效\"}"));
        assertTrue(e.getMessage().contains("session失效"));
        assertThrows(IllegalStateException.class,
                () -> DouyinQrLogin.parseGameCode(
                        "{\"err_no\":0,\"data\":{\"isLogin\":false,\"code\":\"x\"}}"));
        assertThrows(IllegalStateException.class,
                () -> DouyinQrLogin.parseGameCode(
                        "{\"err_no\":0,\"data\":{\"isLogin\":true,\"code\":\"\"}}"));
    }

    // ---------- passport ----------

    @Test
    public void passportFormUsesMiniGamePayload() {
        Map<String, String> form = DouyinQrLogin.buildPassportForm("code_9");
        assertEquals("login_with_sns", form.get("method"));
        assertEquals(DouyinQrLogin.GAME_ID, form.get("game_id"));
        assertEquals("tt_mini:code_9", form.get("platform_id"));
        assertEquals("code_9", form.get("access_token"));
        assertEquals("", form.get("minigame_scene_id"));
        assertEquals("{\"social_type\":\"tt_mini_game\"}", form.get("bi_data"));
        // raw 模式：不带微信那套公共字段
        assertFalse(form.containsKey("service_name"));
        assertFalse(form.containsKey("cg-seid"));
    }

    @Test
    public void passportSignatureMatchesSharedOfficialSigner() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("method", "login_with_sns");
        form.put("game_id", DouyinQrLogin.GAME_ID);
        form.put("platform_id", "tt_mini:abc");
        form.put("access_token", "abc");
        form.put("minigame_scene_id", "");
        form.put("bi_data", "{\"social_type\":\"tt_mini_game\"}");
        String signed = DouyinQrLogin.signPassport(form);
        assertTrue(signed.startsWith("FP " + DouyinQrLogin.GAME_ID + ":"));
        // 同一算法在微信登录里已验证过，交叉校验签名实现一致
        assertEquals(WechatQrLogin.signPassport(form), signed);
    }

    @Test
    public void parsePassportReadsDirectAndNestedCredentials() {
        DouyinQrLogin.Session direct = DouyinQrLogin.parsePassport(
                "{\"data\":{\"fpid\":\"1234567\",\"session_key\":\"sk\"}}");
        assertNotNull(direct);
        assertEquals("1234567", direct.fpid);
        assertEquals("sk", direct.sessionKey);

        DouyinQrLogin.Session nested = DouyinQrLogin.parsePassport(
                "{\"data\":{\"login_profile\":{\"fpid\":\"7654321\",\"session_key\":\"sk2\"}}}");
        assertNotNull(nested);
        assertEquals("7654321", nested.fpid);
        assertEquals("sk2", nested.sessionKey);
    }

    @Test
    public void parsePassportFallsBackToRootLevelCredentials() {
        DouyinQrLogin.Session s = DouyinQrLogin.parsePassport(
                "{\"fpid\":\"8888888\",\"session_key\":\"sk3\"}");
        assertNotNull(s);
        assertEquals("8888888", s.fpid);
        assertEquals("sk3", s.sessionKey);
    }

    @Test
    public void parsePassportRejectsInvalid() {
        assertNull(DouyinQrLogin.parsePassport("{}"));
        assertNull(DouyinQrLogin.parsePassport("{\"data\":{\"fpid\":\"1\"}}"));
        assertNull(DouyinQrLogin.parsePassport("not-json"));
    }

    // ---------- 落库 ----------

    private static AccountStore.Account account(String id, String fpid, String label) {
        AccountStore.Account a = new AccountStore.Account();
        a.id = id;
        a.fpid = fpid;
        a.label = label;
        return a;
    }

    @Test
    public void applySessionBindsToEntryKeepCustomLabel() {
        List<AccountStore.Account> list = new ArrayList<>();
        list.add(account("e1", "old", "我的号"));
        DouyinQrLogin.Session session = new DouyinQrLogin.Session("newfpid", "tok");
        AccountStore.Account target = DouyinQrLogin.applySession(list, "e1", session, "抖音·pid");
        assertNotNull(target);
        assertEquals("newfpid", target.fpid);
        assertEquals("tok", target.token);
        assertEquals("我的号", target.label);
        assertEquals(1, list.size());
        assertTrue(target.lastLogin > 0);
    }

    @Test
    public void applySessionUsesAutoLabelWhenEmpty() {
        List<AccountStore.Account> list = new ArrayList<>();
        list.add(account("e1", "", ""));
        DouyinQrLogin.Session session = new DouyinQrLogin.Session("newfpid", "tok");
        DouyinQrLogin.applySession(list, "e1", session, "抖音·pid");
        assertEquals("抖音·pid", list.get(0).label);
    }

    @Test
    public void applySessionOverwritesByFpidWithoutEntry() {
        List<AccountStore.Account> list = new ArrayList<>();
        list.add(account("old-id", "fp1", "旧备注"));
        DouyinQrLogin.Session session = new DouyinQrLogin.Session("fp1", "tok2");
        AccountStore.Account target = DouyinQrLogin.applySession(list, null, session, "抖音·fp1");
        assertEquals(1, list.size());
        assertEquals("tok2", target.token);
        // 按 fpid 覆盖时不改用户自定义备注（与微信扫码登录规则一致）
        assertEquals("旧备注", target.label);
    }

    @Test
    public void applySessionOverwritesByFpidAndFillsAutoLabelWhenBlank() {
        List<AccountStore.Account> list = new ArrayList<>();
        list.add(account("old-id", "fp1", ""));
        DouyinQrLogin.Session session = new DouyinQrLogin.Session("fp1", "tok2");
        AccountStore.Account target = DouyinQrLogin.applySession(list, null, session, "抖音·fp1");
        assertEquals(1, list.size());
        assertEquals("抖音·fp1", target.label);
    }

    @Test
    public void applySessionRecreatesEntryWhenEntryIdMissing() {
        List<AccountStore.Account> list = new ArrayList<>();
        list.add(account("other", "fpX", "别的号"));
        DouyinQrLogin.Session session = new DouyinQrLogin.Session("fpY", "tokY");
        AccountStore.Account target = DouyinQrLogin.applySession(list, "deleted-entry", session,
                "抖音·fpY");
        assertNotNull(target);
        assertEquals("deleted-entry", target.id);
        assertEquals("fpY", target.fpid);
        assertEquals(2, list.size());
    }

    @Test
    public void applySessionCreatesUngroupedWhenMissing() {
        List<AccountStore.Account> list = new ArrayList<>();
        DouyinQrLogin.Session session = new DouyinQrLogin.Session("fp9", "tok9");
        AccountStore.Account target = DouyinQrLogin.applySession(list, null, session, "抖音·fp9");
        assertNotNull(target);
        assertEquals("fp9", target.id);
        assertEquals("", target.group);
        assertEquals(1, list.size());
    }

    @Test
    public void applySessionRejectsInvalidSession() {
        List<AccountStore.Account> list = new ArrayList<>();
        assertNull(DouyinQrLogin.applySession(list, "e1", null, "x"));
        assertNull(DouyinQrLogin.applySession(list, "e1",
                new DouyinQrLogin.Session("", "tok"), "x"));
        assertEquals(0, list.size());
    }

    @Test
    public void labelUsesLastFourDigits() {
        assertEquals("抖音·5678",
                DouyinQrLogin.labelFor(new DouyinQrLogin.Session("12345678", "tok")));
        assertEquals("抖音·12", DouyinQrLogin.labelFor(new DouyinQrLogin.Session("12", "tok")));
        assertEquals("抖音·", DouyinQrLogin.labelFor(null));
    }
}
