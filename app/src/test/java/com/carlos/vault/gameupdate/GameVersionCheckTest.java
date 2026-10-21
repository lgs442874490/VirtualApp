package com.carlos.vault.gameupdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M3 版本判定口径（纯函数）：以 versionCode 比较为唯一依据。
 *
 * <p>T1b 真机取证基线：容器内/系统原版均为 1.16.11（versionCode 146），
 * 最新 1.16.12（versionCode 147）；启动页角标「1.16.11.12」是渠道显示串，
 * 绝不能参与比较 —— 本类根本没有 versionName 比较入口，用例锁定这一点。
 */
public class GameVersionCheckTest {

    @Test
    public void outdated_whenInstalledBelowLatest() {
        assertEquals(GameVersionCheck.Status.OUTDATED,
                GameVersionCheck.check(146, 147, 0));
    }

    @Test
    public void upToDate_whenInstalledEqualsLatest() {
        assertEquals(GameVersionCheck.Status.UP_TO_DATE,
                GameVersionCheck.check(147, 147, 0));
    }

    @Test
    public void upToDate_whenInstalledAboveLatest() {
        // 比配置还新（例如服务端配置滞后）不能拦着用户启动
        assertEquals(GameVersionCheck.Status.UP_TO_DATE,
                GameVersionCheck.check(148, 147, 0));
    }

    @Test
    public void unknown_whenInstalledUnreadable() {
        // 容器未克隆 / 引擎未就绪时读不到版本（约定 <=0 表示未知）
        assertEquals(GameVersionCheck.Status.UNKNOWN, GameVersionCheck.check(-1, 147, 0));
        assertEquals(GameVersionCheck.Status.UNKNOWN, GameVersionCheck.check(0, 147, 0));
    }

    @Test
    public void unknown_whenConfigMissing() {
        assertEquals(GameVersionCheck.Status.UNKNOWN, GameVersionCheck.check(146, 0, 0));
        assertEquals(GameVersionCheck.Status.UNKNOWN, GameVersionCheck.check(146, -5, 0));
    }

    @Test
    public void unsupported_whenBelowMinSupportedBaseline() {
        // minSupportedVersionCode=100：低于基线即便能装也建议重新克隆
        assertEquals(GameVersionCheck.Status.UNSUPPORTED,
                GameVersionCheck.check(99, 147, 100));
        // 恰好等于基线不算 UNSUPPORTED
        assertEquals(GameVersionCheck.Status.OUTDATED,
                GameVersionCheck.check(100, 147, 100));
        // 基线未启用（<=0）永不触发
        assertEquals(GameVersionCheck.Status.OUTDATED,
                GameVersionCheck.check(1, 147, 0));
        // UNSUPPORTED 只在「低于最新版」的前提下出现
        assertEquals(GameVersionCheck.Status.UP_TO_DATE,
                GameVersionCheck.check(148, 147, 100));
    }

    @Test
    public void outdatedText_matchesContractWording() {
        assertEquals("分身游戏版本过旧（当前 1.16.11，最新 1.16.12）",
                GameVersionCheck.outdatedText("1.16.11", "1.16.12"));
        // 版本名读不到时给占位，不出现 "null"
        assertEquals("分身游戏版本过旧（当前 ?，最新 1.16.12）",
                GameVersionCheck.outdatedText(null, "1.16.12"));
    }

    @Test
    public void statusLine_coversAllStatuses() {
        assertEquals("分身游戏 1.16.11(146) · 可更新至 1.16.12(147)",
                GameVersionCheck.statusLine(GameVersionCheck.Status.OUTDATED,
                        "1.16.11", 146, "1.16.12", 147));
        assertEquals("分身游戏 1.16.12(147) · 已是最新",
                GameVersionCheck.statusLine(GameVersionCheck.Status.UP_TO_DATE,
                        "1.16.12", 147, "1.16.12", 147));
        assertEquals("分身游戏 1.16.10(99) · 过旧，建议重新克隆",
                GameVersionCheck.statusLine(GameVersionCheck.Status.UNSUPPORTED,
                        "1.16.10", 99, "1.16.12", 147));
        assertEquals("分身游戏版本未知",
                GameVersionCheck.statusLine(GameVersionCheck.Status.UNKNOWN,
                        null, -1, "1.16.12", 147));
    }

    @Test
    public void statusLine_neverPrintsNull() {
        String s = GameVersionCheck.statusLine(GameVersionCheck.Status.OUTDATED,
                null, 146, "1.16.12", 147);
        assertTrue(s, s.contains("(146)"));
        assertTrue(s, !s.contains("null"));
    }
}
