package com.carlos.vaultserver.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * PassportConfig 单测：defaults、逐字段覆盖、maskedSummary 掩码、cg-seid 解析优先级。
 */
public class PassportConfigTest {

    @Test
    public void defaults_matchAppSideConstants() {
        PassportConfig c = PassportConfig.defaults();
        assertEquals("https://passport-web-gof.campfiregames.cn", c.baseUrl);
        assertEquals("/client_api.php?ver=6", c.path);
        assertEquals("20173", c.gameId);
        assertEquals("U8.0.5.0-GOFCN-1", c.sdkVersion);
        assertEquals("1.16.12", c.gameVersion);
        assertEquals("zh_CN", c.language);
        assertEquals("passport-request", c.serviceName);
        assertEquals("sendCaptcha", c.smsSendMethod);
        assertEquals("loginWithPhoneCode", c.smsLoginMethod);
        // 待真实轮校准的推定 method 名
        assertEquals("express_signin", c.guestRegisterMethod);
        assertEquals("idCardVerified", c.realNameMethod);
        assertEquals("", c.cgSeid);
        assertEquals("", c.fpcsSource);
    }

    @Test
    public void builder_overridesEachField() {
        PassportConfig c = PassportConfig.builder()
                .baseUrl("https://passport.example.com")
                .path("/api.php")
                .gameId("9")
                .gameKey("0123456789abcdef")
                .sdkVersion("S1")
                .gameVersion("1.0")
                .language("en_US")
                .originAddress("AA:BB")
                .smsSendMethod("m1")
                .smsLoginMethod("m2")
                .guestRegisterMethod("m3")
                .realNameMethod("m4")
                .fpcsSource("fps")
                .cgSeid("seid")
                .build();
        assertEquals("https://passport.example.com", c.baseUrl);
        assertEquals("/api.php", c.path);
        assertEquals("9", c.gameId);
        assertEquals("0123456789abcdef", c.gameKey);
        assertEquals("S1", c.sdkVersion);
        assertEquals("1.0", c.gameVersion);
        assertEquals("en_US", c.language);
        assertEquals("AA:BB", c.originAddress);
        assertEquals("m1", c.smsSendMethod);
        assertEquals("m2", c.smsLoginMethod);
        assertEquals("m3", c.guestRegisterMethod);
        assertEquals("m4", c.realNameMethod);
        // 显式 cgSeid 优先于 fpcsSource 推导
        assertEquals("seid", c.resolveCgSeid());
    }

    @Test
    public void cgSeid_fpcsDerivation_matchesSaltedMd5() {
        PassportConfig c = PassportConfig.builder().fpcsSource("fpcs-test").build();
        assertEquals(PassportIds.md5Hex("fpcs-test" + PassportIds.CG_SEID_SALT), c.resolveCgSeid());
        // 空配置不发该头
        assertEquals("", PassportConfig.defaults().resolveCgSeid());
    }

    @Test
    public void maskedSummary_masksGameKey() {
        Map<String, String> s = PassportConfig.defaults().maskedSummary();
        assertEquals("a1f***ff1", s.get("gameKey"));
        assertFalse(s.get("gameKey").contains("a1f3d1fbf975825d85f51d2f6ffb2ff1"));
        assertTrue(s.containsKey("baseUrl"));
        assertTrue(s.containsKey("smsSendMethod"));
        assertEquals("false", s.get("cgSeidConfigured"));
    }
}
