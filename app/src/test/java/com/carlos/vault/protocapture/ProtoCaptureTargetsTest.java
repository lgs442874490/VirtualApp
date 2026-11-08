package com.carlos.vault.protocapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 抓包目标下拉映射（2026-11-08）：容器内游戏 → 宿主 UID；原生安装 → 游戏包名。
 */
public class ProtoCaptureTargetsTest {

    private static final String HOST = "com.carlos.multiapp";

    @Test
    public void labelsAndValues_align() {
        String[] labels = ProtoCaptureTargets.labels();
        String[] values = ProtoCaptureTargets.values(HOST);
        assertEquals(labels.length, values.length);
        assertEquals(4, values.length);
        assertEquals(HOST, values[0]);              // 无尽冬日（容器内）
        assertEquals(HOST, values[1]);              // 号库
        assertEquals("com.gof.china", values[2]);   // 无尽冬日（原生安装）
        assertEquals("", values[3]);                // 全部应用
    }

    @Test
    public void defaultIndex_prefersContainerThenNativeThenAll() {
        assertEquals(0, ProtoCaptureTargets.defaultIndex(true, true));
        assertEquals(0, ProtoCaptureTargets.defaultIndex(true, false));
        assertEquals(2, ProtoCaptureTargets.defaultIndex(false, true));
        assertEquals(3, ProtoCaptureTargets.defaultIndex(false, false));
    }

    @Test
    public void indexOfValue_mapsSavedTargets() {
        assertEquals(3, ProtoCaptureTargets.indexOfValue("", HOST, 0));
        assertEquals(0, ProtoCaptureTargets.indexOfValue(HOST, HOST, 3));
        assertEquals(2, ProtoCaptureTargets.indexOfValue("com.gof.china", HOST, 3));
        assertEquals(3, ProtoCaptureTargets.indexOfValue("unknown.pkg", HOST, 3));
    }

    @Test
    public void resolve_containerGameUsesHostUid() {
        // 容器内：游戏包 UID == 宿主 UID → 改抓宿主包名（覆盖容器游戏流量）
        assertEquals(HOST, ProtoCaptureTargets.resolve("com.gof.china", 10379L, 10379L, HOST));
        // 原生安装：UID 不同 → 保持游戏包名
        assertEquals("com.gof.china",
                ProtoCaptureTargets.resolve("com.gof.china", 10379L, 10113L, HOST));
        // 全部应用
        assertEquals("", ProtoCaptureTargets.resolve("", 10379L, 0L, HOST));
        assertEquals("", ProtoCaptureTargets.resolve(null, 10379L, 0L, HOST));
        // 查不到 UID：保持原样
        assertEquals("com.gof.china",
                ProtoCaptureTargets.resolve("com.gof.china", 10379L, 0L, HOST));
    }

    @Test
    public void isGamePackage_onlyKnownGamePackages() {
        assertTrue(ProtoCaptureTargets.isGamePackage("com.gof.china"));
        assertTrue(ProtoCaptureTargets.isGamePackage("com.gof.global"));
        assertFalse(ProtoCaptureTargets.isGamePackage(HOST));
        assertFalse(ProtoCaptureTargets.isGamePackage(null));
    }
}
