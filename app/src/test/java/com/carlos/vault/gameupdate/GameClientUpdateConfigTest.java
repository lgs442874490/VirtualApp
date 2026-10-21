package com.carlos.vault.gameupdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M3 更新配置：assets JSON 解析 + 内置缺省（T1b 取证基线）。
 * 手写平铺 JSON 提取器专为 JVM 单测设计（org.json 在单测 classpath 是桩）。
 */
public class GameClientUpdateConfigTest {

    @Test
    public void defaults_matchT1bEvidence() {
        GameClientUpdateConfig cfg = GameClientUpdateConfig.defaults();
        assertEquals("1.16.12", cfg.latestVersionName);
        assertEquals(147L, cfg.latestVersionCode);
        assertEquals(887587540L, cfg.sizeBytes);
        assertEquals("https://wjdr-app.campfiregames.cn/download", cfg.apkUrl);
        assertTrue(cfg.force);
        // 契约红线：apkUrl 必须是 302 短链，绝不能内置含日期的 CDN 路径
        assertFalse(cfg.apkUrl.contains("/apk/package/"));
        assertFalse(cfg.apkUrl.contains("wjdr-download"));
    }

    @Test
    public void fromJson_parsesAllFields() {
        String json = "{\n"
                + "  \"latestVersionName\": \"1.17.0\",\n"
                + "  \"latestVersionCode\": 150,\n"
                + "  \"apkUrl\": \"https://example.com/dl\",\n"
                + "  \"shortLink\": \"https://example.com/s\",\n"
                + "  \"sizeBytes\": 900000000,\n"
                + "  \"sha256\": \"abcd1234\",\n"
                + "  \"force\": false,\n"
                + "  \"minSupportedVersionCode\": 100\n"
                + "}";
        GameClientUpdateConfig cfg = GameClientUpdateConfig.fromJson(json);
        assertEquals("1.17.0", cfg.latestVersionName);
        assertEquals(150L, cfg.latestVersionCode);
        assertEquals("https://example.com/dl", cfg.apkUrl);
        assertEquals("https://example.com/s", cfg.shortLink);
        assertEquals(900000000L, cfg.sizeBytes);
        assertEquals("abcd1234", cfg.sha256);
        assertFalse(cfg.force);
        assertEquals(100L, cfg.minSupportedVersionCode);
    }

    @Test
    public void fromJson_missingFieldsKeepDefaults() {
        GameClientUpdateConfig base = GameClientUpdateConfig.defaults();
        GameClientUpdateConfig cfg = GameClientUpdateConfig.fromJson("{\"latestVersionCode\":148}");
        assertEquals(148L, cfg.latestVersionCode);
        // 其余字段回到缺省值，而不是 0/null
        assertEquals(base.latestVersionName, cfg.latestVersionName);
        assertEquals(base.sizeBytes, cfg.sizeBytes);
        assertEquals(base.apkUrl, cfg.apkUrl);
    }

    @Test
    public void fromJson_brokenOrEmpty_returnsNull() {
        assertNull(GameClientUpdateConfig.fromJson(null));
        assertNull(GameClientUpdateConfig.fromJson(""));
        assertNull(GameClientUpdateConfig.fromJson("   "));
        assertNull(GameClientUpdateConfig.fromJson("not json at all"));
    }

    @Test
    public void scalarExtract_edges() {
        // 值带空格 / 字符串里含逗号冒号 / 布尔 / 非法数字回退默认
        String json = "{\"a\": \"x, y: z\" ,\"b\":123,\"c\":true,\"d\":\"147\"}";
        assertEquals("x, y: z", GameClientUpdateConfig.stringValue(json, "a", "def"));
        assertEquals(123L, GameClientUpdateConfig.longValue(json, "b", 0));
        assertTrue(GameClientUpdateConfig.boolValue(json, "c", false));
        assertEquals(147L, GameClientUpdateConfig.longValue(json, "d", 9));   // 字符串数字也认
        assertEquals(9L, GameClientUpdateConfig.longValue(json, "missing", 9));
        assertEquals("def", GameClientUpdateConfig.stringValue(json, "missing", "def"));
        assertTrue(GameClientUpdateConfig.boolValue(json, "missing", true));
        assertEquals(0L, GameClientUpdateConfig.longValue(json, "a", 0));     // 非数字 → 默认
    }

    @Test
    public void merge_overridesEveryField() {
        GameClientUpdateConfig base = GameClientUpdateConfig.defaults();
        GameClientUpdateConfig parsed = GameClientUpdateConfig.fromJson(
                "{\"latestVersionCode\":149,\"sha256\":\"ff\"}");
        GameClientUpdateConfig.merge(base, parsed);
        assertEquals(149L, base.latestVersionCode);
        assertEquals("ff", base.sha256);
    }

    @Test
    public void merge_nullSafe() {
        GameClientUpdateConfig base = GameClientUpdateConfig.defaults();
        GameClientUpdateConfig.merge(base, null);      // 不抛
        GameClientUpdateConfig.merge(null, base);      // 不抛
    }

    @Test
    public void describe_listsKeyValues() {
        String d = GameClientUpdateConfig.defaults().describe();
        assertTrue(d, d.contains("1.16.12"));
        assertTrue(d, d.contains("147"));
        assertTrue(d, d.contains("887587540"));
    }
}
