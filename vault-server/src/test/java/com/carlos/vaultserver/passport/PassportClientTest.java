package com.carlos.vaultserver.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * PassportClient 单测：表单组装/签名（黄金值锚定）/cg-seid 优先级/响应解析/60s 冷却/实名校验。
 *
 * <p>全部用注入的假 Transport，不联网。
 */
public class PassportClientTest {

    // ---------------- 测试基建 ----------------

    /**
     * 黄金签名值：由 WechatQrLogin.signPassportJava 对 GOLDEN_FORM（guid=0123…cdef）的
     * 实际运行结果锚定（2026-11-07 /tmp 提取器输出）。
     */
    private static final String GOLDEN_SIGNATURE =
            "FP 20173:OGNiYjE5MTcxNmE5Zjk2YTgxZTkxMWFiYWJiODhkZmZkYmViMWI1Mjc1YTBjZTE5ZTIyMDQyYzkyMjQ5YTNmYg==";

    /** 黄金 form 的固定设备指纹（与黄金值提取器一致）。 */
    private static final String GOLDEN_DEVICE_ID = "0123456789abcdef0123456789abcdef";

    private static final DeviceProfile FIXED_PROFILE = DeviceProfile.seeded("m1-fixed-profile".getBytes());

    /** 捕获请求并按脚本回放响应的假 Transport。 */
    private static final class CapturingTransport implements PassportClient.Transport {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headersList = new ArrayList<>();
        final List<Map<String, String>> formsList = new ArrayList<>();
        String respondJson = "{}";
        IOException throwIo;

        void respond(String json) {
            this.respondJson = json;
        }

        @Override
        public String post(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
            if (throwIo != null) throw throwIo;
            urls.add(url);
            headersList.add(new LinkedHashMap<>(headers));
            formsList.add(new LinkedHashMap<>(form));
            return respondJson;
        }
    }

    // ---------------- 表单与签名 ----------------

    @Test
    public void smsLogin_formAssemblyAndOrder() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{\"data\":{\"fpid\":\"12345\",\"session_key\":\"sk\"}}");

        client.smsLogin("138-0013-8000", "1234", FIXED_PROFILE);

        assertEquals("https://passport-web-gof.campfiregames.cn/client_api.php?ver=6", t.urls.get(0));
        Map<String, String> form = t.formsList.get(0);
        // method 键首位（值为 method 名）
        assertEquals("method", firstEntry(form));
        assertEquals("loginWithPhoneCode", form.get("method"));
        // 真实客户端固定 service_name=passport-request（实测：传 method 名会得到 error=5）
        assertEquals("passport-request", form.get("service_name"));
        assertEquals("loginWithPhoneCode", form.get("method"));
        assertEquals("20173", form.get("game_id"));
        assertEquals("funsdk", form.get("domain"));
        assertEquals("U8.0.5.0-GOFCN-1", form.get("sdk_version"));
        assertEquals("13800138000", form.get("mobile"));
        assertEquals("1234", form.get("code"));
        // 实证口径（2026-11-10 真实客户端反编译+实测）：guid 独立随机、origin_guid=md5(guid)、android_id=MAC
        assertEquals(FIXED_PROFILE.guid, form.get("guid"));
        assertEquals(PassportIds.md5Hex(FIXED_PROFILE.guid), form.get("origin_guid"));
        assertEquals(FIXED_PROFILE.macAddress, form.get("android_id"));
        assertEquals("funplus", form.get("install_channelid"));
        // session_key 空值被剔除
        assertFalse(form.containsKey("session_key"));
    }

    @Test
    public void signature_matchesWechatQrLoginGoldenValue() {
        PassportClient client = new PassportClient(PassportConfig.defaults(), new CapturingTransport());

        // 第一层：黄金锚定——sign() 对黄金 form 的输出 == WechatQrLogin 实际运行结果
        Map<String, String> fixedForm = goldenForm();
        assertEquals(GOLDEN_SIGNATURE, client.sign(fixedForm));

        // 第二层：参照实现对拍——call() 组装的真实请求头 == 测试内逐字复制的算法
        CapturingTransport t = new CapturingTransport();
        PassportClient caller = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{}");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mobile", "13800138000");
        params.put("code", "1234");
        caller.call("loginWithPhoneCode", params, FIXED_PROFILE);
        assertEquals(goldenSign(t.formsList.get(0)), t.headersList.get(0).get("Authorization"));
    }

    @Test
    public void cgSeid_explicitWins_andFpcsDerived() {
        CapturingTransport t = new CapturingTransport();
        PassportClient explicit = new PassportClient(
                PassportConfig.builder().cgSeid("explicit-seid").build(), t);
        explicit.guestRegister(FIXED_PROFILE);
        assertEquals("explicit-seid", t.headersList.get(0).get("cg-seid"));

        CapturingTransport t2 = new CapturingTransport();
        PassportClient derived = new PassportClient(
                PassportConfig.builder().fpcsSource("fpcs-test").build(), t2);
        derived.guestRegister(FIXED_PROFILE);
        assertEquals(PassportIds.md5Hex("fpcs-test" + PassportIds.CG_SEID_SALT),
                t2.headersList.get(0).get("cg-seid"));

        CapturingTransport t3 = new CapturingTransport();
        new PassportClient(PassportConfig.defaults(), t3).guestRegister(FIXED_PROFILE);
        assertFalse(t3.headersList.get(0).containsKey("cg-seid"));
    }

    // ---------------- 响应解析 ----------------

    @Test
    public void parse_flatDataLayer() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{\"data\":{\"fpid\":\"111\",\"session_key\":\"sk1\"}}");
        PassportClient.PassportResult r = client.smsLogin("13800138000", "1", FIXED_PROFILE);
        assertEquals("111", r.fpid());
        assertEquals("sk1", r.sessionKey());
    }

    @Test
    public void parse_loginProfileNestedLayer() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{\"data\":{\"login_profile\":{\"fpid\":\"222\",\"sdkToken\":\"sk2\"}}}");
        PassportClient.PassportResult r = client.smsLogin("13800138000", "1", FIXED_PROFILE);
        assertEquals("222", r.fpid());
        assertEquals("sk2", r.sessionKey());
    }

    @Test
    public void parse_rootFallbackAndErrorText() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{\"error\":\"bad request\",\"fpid\":\"333\"}");
        PassportClient.PassportResult r = client.smsLogin("13800138000", "1", FIXED_PROFILE);
        assertEquals("333", r.fpid());
        assertEquals("bad request", r.errorText());
    }

    @Test
    public void parse_invalidJson_throwsWithoutAuthorization() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("<html>gateway error");
        try {
            client.smsLogin("13800138000", "1", FIXED_PROFILE);
            fail("应抛 PassportException");
        } catch (PassportException e) {
            assertTrue(e.getMessage().contains("非 JSON"));
            assertFalse(e.getMessage().contains("FP 20173"));
            assertTrue(e.getMessage().length() < 400);
        }
    }

    // ---------------- 60s 冷却 ----------------

    @Test
    public void sendSms_cooldownBlocksSecondCall() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{\"data\":{}}");
        client.sendSms("13800138000", null, FIXED_PROFILE);
        try {
            client.sendSms("13800138000", "86", FIXED_PROFILE);
            fail("应触发冷却");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("验证码已发送"));
            assertTrue(e.getMessage().matches(".*\\d+ 秒后再试.*"));
        }
        // 不同号不受影响
        client.sendSms("13900139000", "86", FIXED_PROFILE);
        assertEquals(2, t.urls.size());
    }

    @Test
    public void sendSms_transportFailure_releasesReservation() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.throwIo = new IOException("connect timeout");
        try {
            client.sendSms("13800138000", null, FIXED_PROFILE);
            fail("应抛网络异常");
        } catch (PassportException expected) {
            // 预约已释放：同号可立即重试
        }
        t.throwIo = null;
        t.respond("{\"data\":{}}");
        client.sendSms("13800138000", null, FIXED_PROFILE);
        assertEquals(1, t.urls.size());
    }

    @Test
    public void smsLogin_notAffectedBySmsCooldown() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        t.respond("{\"data\":{}}");
        client.sendSms("13800138000", null, FIXED_PROFILE);
        client.smsLogin("13800138000", "1234", FIXED_PROFILE); // 不抛
        assertEquals(2, t.urls.size());
    }

    // ---------------- 实名校验 ----------------

    @Test
    public void realName_localValidation() {
        CapturingTransport t = new CapturingTransport();
        PassportClient client = new PassportClient(PassportConfig.defaults(), t);
        try {
            client.realName("1", "sk", "", "110101199003077758");
            fail("空姓名应拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("请输入姓名", e.getMessage());
        }
        try {
            client.realName("1", "sk", "张三", "1101011990030777");
            fail("坏身份证应拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("身份证号格式不正确", e.getMessage());
        }
        // 18 位 X 结尾合法；通过本地校验后外呼
        t.respond("{\"data\":{\"ok\":1}}");
        assertNotNull(client.realName("1", "sk", "张三", "11010119900307775X"));
        assertEquals(1, t.urls.size());
        assertEquals("passport-request", t.formsList.get(0).get("service_name"));
        assertEquals("realNameVerify", t.formsList.get(0).get("method"));
        assertEquals("11010119900307775X", t.formsList.get(0).get("id_card"));
    }

    // ---------------- 工具 ----------------

    private static String firstEntry(Map<String, String> m) {
        return m.keySet().iterator().next();
    }

    /** 黄金 form（与黄金值提取器同构，guid 三字段=硬编码 32 位 hex）。 */
    private static Map<String, String> goldenForm() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("method", "loginWithPhoneCode");
        form.put("game_id", "20173");
        form.put("service_name", "loginWithPhoneCode");
        form.put("l", "zh_CN");
        form.put("game_version", "1.16.12");
        form.put("domain", "funsdk");
        form.put("sdk_version", "U8.0.5.0-GOFCN-1");
        form.put("origin_address", "02:00:00:00:00:00");
        form.put("guid", GOLDEN_DEVICE_ID);
        form.put("origin_guid", GOLDEN_DEVICE_ID);
        form.put("android_id", GOLDEN_DEVICE_ID);
        form.put("mobile", "13800138000");
        form.put("code", "1234");
        return form;
    }

    /** 测试内参照签名实现（逐字复制 WechatQrLogin.signPassportJava 的算法路径）。 */
    private static String goldenSign(Map<String, String> params) {
        StringBuilder src = new StringBuilder();
        for (String key : new java.util.TreeSet<>(params.keySet())) {
            if (src.length() > 0) src.append('&');
            src.append(key).append('=').append(params.get(key));
        }
        String hex = PassportIds.hmacSha256Hex(src.toString(), PassportConfig.DEFAULT_GAME_KEY);
        return "FP " + PassportConfig.DEFAULT_GAME_ID + ":"
                + PassportIds.base64(hex.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
