package com.carlos.vaultserver.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * SmsProviders 工厂单测：d1 / haozhuma / 未知类型三分支 + 缺参中文错误。
 */
public class SmsProvidersTest {

    /** 空实现假 Transport（工厂创建路径不外呼）。 */
    private static final D1SmsProvider.Transport NOOP = url -> "";

    private static Map<String, String> config(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------------- 三分支 ----------------

    @Test
    public void create_d1() {
        SmsProvider p = SmsProviders.create("d1", config("token", "tk-1234567890"), NOOP);
        assertTrue(p instanceof D1SmsProvider);
        assertEquals("d1", p.name());
    }

    @Test
    public void create_d1_caseInsensitiveAndTrimmed() {
        SmsProvider p = SmsProviders.create("  D1 ", config("token", "tk-1234567890"), NOOP);
        assertTrue(p instanceof D1SmsProvider);
        assertEquals("d1", p.name());
    }

    @Test
    public void create_haozhuma() {
        SmsProvider p = SmsProviders.create("haozhuma",
                config("user", "u1", "password", "p1", "sid", "1024"), NOOP);
        assertTrue(p instanceof HaozhuSmsProvider);
        assertEquals("haozhuma", p.name());
    }

    @Test
    public void create_unknownType_throws() {
        try {
            SmsProviders.create("smsbao", config(), NOOP);
            fail("未知类型应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("未知接码平台类型"));
            assertTrue(e.getMessage().contains("smsbao"));
        }
    }

    @Test
    public void create_blankType_throws() {
        try {
            SmsProviders.create("  ", config(), NOOP);
            fail("空类型应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("不能为空"));
        }
    }

    // ---------------- 缺参中文错误 ----------------

    @Test
    public void create_d1_missingToken_throwsChineseError() {
        try {
            SmsProviders.create("d1", config(), NOOP);
            fail("缺 token 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("缺少接码平台配置"));
            assertTrue(e.getMessage().contains("token"));
        }
    }

    @Test
    public void create_haozhuma_missingParams_throwsChineseError() {
        try {
            SmsProviders.create("haozhuma", config("user", "u1"), NOOP);
            fail("缺 password 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("缺少接码平台配置"));
            assertTrue(e.getMessage().contains("password"));
        }
        try {
            SmsProviders.create("haozhuma", config("user", "u1", "password", "p1"), NOOP);
            fail("缺 sid 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("缺少接码平台配置"));
            assertTrue(e.getMessage().contains("sid"));
        }
    }

    @Test
    public void create_nullConfig_throwsMissingParamError() {
        try {
            SmsProviders.create("d1", null, NOOP);
            fail("null config 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("token"));
        }
    }
}
