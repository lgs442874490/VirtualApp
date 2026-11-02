package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 热更源 URL 解析纯函数（不依赖 Android）：prefs hot_url 非空优先，否则默认
 * 自托管端点 {@code VaultSync.baseUrl(ctx) + "/v1/vault/hotconfig"}。
 *
 * <p>对应 task：热更新自托管——App 默认即可用，不再显示「未配置更新源」。
 */
public class HotConfigTest {

    private static final String DEFAULT_HOT =
            "https://cs.888syd.xyz/v1/vault/hotconfig";

    // ---------- resolveHotUrl：显式 prefs 优先 ----------

    @Test
    public void resolveHotUrl_prefUrlWins() {
        assertEquals("http://192.168.1.4:8765/hotconfig.json",
                HotConfig.resolveHotUrl("http://192.168.1.4:8765/hotconfig.json",
                        "https://cs.888syd.xyz"));
    }

    @Test
    public void resolveHotUrl_prefUrlTrimmed() {
        assertEquals("https://example.com/hotconfig.json",
                HotConfig.resolveHotUrl("  https://example.com/hotconfig.json  ",
                        "https://cs.888syd.xyz"));
    }

    @Test
    public void resolveHotUrl_blankPrefFallsBackToDefault() {
        assertEquals(DEFAULT_HOT, HotConfig.resolveHotUrl("", "https://cs.888syd.xyz"));
        assertEquals(DEFAULT_HOT, HotConfig.resolveHotUrl("   ", "https://cs.888syd.xyz"));
        assertEquals(DEFAULT_HOT, HotConfig.resolveHotUrl(null, "https://cs.888syd.xyz"));
    }

    // ---------- resolveHotUrl：默认端点拼接 ----------

    @Test
    public void resolveHotUrl_defaultJoinsServerPath() {
        assertEquals(DEFAULT_HOT, HotConfig.resolveHotUrl(null, "https://cs.888syd.xyz"));
    }

    @Test
    public void resolveHotUrl_stripsTrailingSlashes() {
        assertEquals(DEFAULT_HOT, HotConfig.resolveHotUrl(null, "https://cs.888syd.xyz/"));
        assertEquals(DEFAULT_HOT, HotConfig.resolveHotUrl(null, "https://cs.888syd.xyz///"));
        assertEquals("http://10.0.0.2:8790/v1/vault/hotconfig",
                HotConfig.resolveHotUrl(null, " http://10.0.0.2:8790/ "));
    }

    @Test
    public void resolveHotUrl_emptyBaseYieldsEmpty() {
        assertEquals("", HotConfig.resolveHotUrl(null, ""));
        assertEquals("", HotConfig.resolveHotUrl("  ", null));
        // prefs 显式配置时即使 base 为空也仍可用
        assertEquals("https://example.com/hotconfig.json",
                HotConfig.resolveHotUrl("https://example.com/hotconfig.json", null));
    }

    // ---------- 端到端语义：默认值等于服务器路由 ----------

    @Test
    public void defaultHotUrlMatchesServerRoute() {
        // 服务器路由：GET /v1/vault/hotconfig（nginx 反代 /v1/vault/），两者必须一致
        assertEquals("https://cs.888syd.xyz" + "/v1/vault/hotconfig", DEFAULT_HOT);
    }
}
