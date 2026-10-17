package com.carlos.vault;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TC（P0-2）：容器游戏进程存活的 cmdline 判定口径。
 *
 * 背景：VA 里游戏跑在宿主包 stub 进程上，系统进程名是 com.carlos.multiapp:p0，
 * /proc/&lt;pid&gt;cmdline 才是 com.gof.china（真机 ANR 原文实证）。
 * 旧 isContainerGameAlive 按 processName.startsWith("com.gof.china") 匹配恒 false，
 * 导致 killGameAndWait 对运行中的游戏立即放行 → 旧进程未死就 wipe/注入/存档。
 * 本用例锁定新口径：cmdline 等于包名或带冒号子进程后缀才算游戏进程；
 * 「宿主 stub 进程名 com.carlos.multiapp:p0」绝不能误判为游戏（反向回归桩）。
 */
public class VaultEngineProcessTest {

    @Test
    public void mainProcessCmdline_matches() {
        assertTrue(VaultEngine.isGameCmdline("com.gof.china"));
    }

    @Test
    public void subprocessCmdline_matches() {
        // 真机 ANR 实证过的推送子进程形态
        assertTrue(VaultEngine.isGameCmdline("com.gof.china:xg_vip_service"));
        assertTrue(VaultEngine.isGameCmdline("com.gof.china:tool"));
    }

    @Test
    public void hostStubProcessName_neverMatches() {
        // 旧缺陷的根因形态：宿主 stub 进程名 ≠ 游戏 cmdline，绝不能算游戏存活
        assertFalse(VaultEngine.isGameCmdline("com.carlos.multiapp:p0"));
        assertFalse(VaultEngine.isGameCmdline("com.carlos.multiapp"));
        assertFalse(VaultEngine.isGameCmdline("com.carlos.multiapp:va_core"));
    }

    @Test
    public void lookalikeAndDegenerate_neverMatches() {
        // 前缀相似但非同包（com.gof.china.premium）不得命中
        assertFalse(VaultEngine.isGameCmdline("com.gof.china.premium"));
        assertFalse(VaultEngine.isGameCmdline("com.gof"));
        assertFalse(VaultEngine.isGameCmdline(""));
        assertFalse(VaultEngine.isGameCmdline(null));
    }

    @Test
    public void whitespace_isTolerated() {
        assertTrue(VaultEngine.isGameCmdline("  com.gof.china  "));
    }
}
