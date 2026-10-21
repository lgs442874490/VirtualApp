package com.carlos.vault.gameupdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M3c「更新原生游戏 + 重新克隆分身」决策纯函数。
 *
 * <p>T1b 取证基线（2026-10-21）：原生/容器均 1.16.11（versionCode 146），
 * 最新 1.16.12（147）。用例除锁定比较/前置/回读判定外，还锁定
 * 「版本过旧弹窗不含任何在线下载入口」（无「立即更新」/「888」/「MB」字样），
 * 以及失败文案与 {@code VaultEngine.categorizeFailure} 关键词的联动分类。
 */
public class GameRecloneFlowTest {

    // ---------- ① 原生 vs 容器版本比较 ----------

    @Test
    public void compare_nativeNewer() {
        assertEquals(GameRecloneFlow.NativeCompare.NATIVE_NEWER,
                GameRecloneFlow.compareNativeVsContainer(147, 146));
    }

    @Test
    public void compare_inSync_andContainerNewer() {
        assertEquals(GameRecloneFlow.NativeCompare.IN_SYNC,
                GameRecloneFlow.compareNativeVsContainer(146, 146));
        // 容器比原生新（异常态）不触发重新克隆提示
        assertEquals(GameRecloneFlow.NativeCompare.CONTAINER_NEWER,
                GameRecloneFlow.compareNativeVsContainer(146, 147));
    }

    @Test
    public void compare_unknownWhenEitherUnreadable() {
        assertEquals(GameRecloneFlow.NativeCompare.UNKNOWN,
                GameRecloneFlow.compareNativeVsContainer(-1, 146));
        assertEquals(GameRecloneFlow.NativeCompare.UNKNOWN,
                GameRecloneFlow.compareNativeVsContainer(147, 0));
        assertEquals(GameRecloneFlow.NativeCompare.UNKNOWN,
                GameRecloneFlow.compareNativeVsContainer(0, -1));
    }

    @Test
    public void prompt_onlyWhenNativeStrictlyNewer() {
        assertTrue(GameRecloneFlow.shouldPromptReclone(147, 146));
        assertFalse(GameRecloneFlow.shouldPromptReclone(146, 146));
        assertFalse(GameRecloneFlow.shouldPromptReclone(146, 147));
        assertFalse(GameRecloneFlow.shouldPromptReclone(-1, 146));
        assertFalse(GameRecloneFlow.shouldPromptReclone(147, -1));
    }

    @Test
    public void promptText_locksContractWording() {
        String s = GameRecloneFlow.reclonePromptText("1.16.12", 147, "1.16.11", 146);
        assertTrue(s, s.startsWith("检测到原生游戏已更新，是否重新克隆分身？"));
        assertTrue(s, s.contains("原生 1.16.12(147)"));
        assertTrue(s, s.contains("分身 1.16.11(146)"));
        assertTrue(s, !s.contains("null"));
    }

    // ---------- ② 重新克隆：前置条件 ----------

    @Test
    public void block_nativeMissingOrUnreadable() {
        assertEquals(GameRecloneFlow.RecloneBlock.NATIVE_MISSING,
                GameRecloneFlow.recloneBlock(false, -1));
        assertEquals(GameRecloneFlow.RecloneBlock.NATIVE_UNREADABLE,
                GameRecloneFlow.recloneBlock(true, 0));
        assertEquals(GameRecloneFlow.RecloneBlock.NONE,
                GameRecloneFlow.recloneBlock(true, 147));
    }

    @Test
    public void blockText_noneIsNull_missingMentionsClone() {
        assertNull(GameRecloneFlow.blockText(GameRecloneFlow.RecloneBlock.NONE));
        // 措辞含「克隆」→ categorizeFailure 归 CLONE 类
        assertEquals(com.carlos.vault.VaultEngine.FailureCategory.CLONE,
                com.carlos.vault.VaultEngine.categorizeFailure(
                        GameRecloneFlow.blockText(GameRecloneFlow.RecloneBlock.NATIVE_MISSING)));
    }

    // ---------- ② 重新克隆：完成后回读判定 ----------

    @Test
    public void verify_promoted_ok() {
        // 覆盖安装：146 → 147，与原生齐平
        assertEquals(GameRecloneFlow.PostReclone.OK,
                GameRecloneFlow.verifyAfterReclone(147, 146, 147));
        // 容器原本没装（全新克隆）：-1 → 147
        assertEquals(GameRecloneFlow.PostReclone.OK,
                GameRecloneFlow.verifyAfterReclone(147, -1, 147));
    }

    @Test
    public void verify_noChange_nativeNotUpdated() {
        // 原生还没更新（146==146）就点重新克隆：克隆前后没变 → 引导先去更新原生
        assertEquals(GameRecloneFlow.PostReclone.NATIVE_NOT_UPDATED,
                GameRecloneFlow.verifyAfterReclone(146, 146, 146));
        // 已同步（147>=147）但与克隆前一样，同样是「原生没更新」
        assertEquals(GameRecloneFlow.PostReclone.NATIVE_NOT_UPDATED,
                GameRecloneFlow.verifyAfterReclone(147, 147, 147));
        // 原生版本读不到（防御）：没法证明更新过
        assertEquals(GameRecloneFlow.PostReclone.NATIVE_NOT_UPDATED,
                GameRecloneFlow.verifyAfterReclone(-1, 146, 147));
    }

    @Test
    public void verify_partialOrUnreadable_installNotEffective() {
        // 比克隆前新但仍低于原生：覆盖安装没吃满
        assertEquals(GameRecloneFlow.PostReclone.INSTALL_NOT_EFFECTIVE,
                GameRecloneFlow.verifyAfterReclone(147, 145, 146));
        // 安装后回读不到容器版本
        assertEquals(GameRecloneFlow.PostReclone.INSTALL_NOT_EFFECTIVE,
                GameRecloneFlow.verifyAfterReclone(147, 146, -1));
        assertEquals(GameRecloneFlow.PostReclone.INSTALL_NOT_EFFECTIVE,
                GameRecloneFlow.verifyAfterReclone(147, 146, 0));
    }

    @Test
    public void failureText_nativeNotUpdated_locksContractWording() {
        assertEquals("原生游戏尚未更新，请先更新原生游戏",
                GameRecloneFlow.nativeNotUpdatedText());
        assertEquals("原生游戏尚未更新，请先更新原生游戏",
                GameRecloneFlow.failureText(GameRecloneFlow.PostReclone.NATIVE_NOT_UPDATED,
                        "1.16.11", 146, "1.16.11", 146));
        // 措辞含「尚未更新」→ categorizeFailure 归 VERSION 类（UI 据此给「更新原生游戏」）
        assertEquals(com.carlos.vault.VaultEngine.FailureCategory.VERSION,
                com.carlos.vault.VaultEngine.categorizeFailure(
                        GameRecloneFlow.nativeNotUpdatedText()));
    }

    @Test
    public void failureText_installNotEffective_categorizesInstall() {
        String s = GameRecloneFlow.failureText(GameRecloneFlow.PostReclone.INSTALL_NOT_EFFECTIVE,
                "1.16.12", 147, "1.16.11", 146);
        assertTrue(s, s.contains("1.16.11(146)"));
        assertTrue(s, s.contains("1.16.12(147)"));
        // 措辞避开「克隆/版本」→ 归 INSTALL 而非 CLONE/VERSION
        assertEquals(com.carlos.vault.VaultEngine.FailureCategory.INSTALL,
                com.carlos.vault.VaultEngine.categorizeFailure(s));
        assertNull(GameRecloneFlow.failureText(GameRecloneFlow.PostReclone.OK,
                "1.16.12", 147, "1.16.12", 147));
    }

    // ---------- ③ 版本过旧弹窗按钮（无在线下载入口） ----------

    @Test
    public void updateDialogOptions_fixedOrder() {
        String[] opts = GameRecloneFlow.updateDialogOptions();
        assertEquals(4, opts.length);
        assertTrue(opts[0].contains("更新原生游戏"));
        assertEquals("重新克隆分身", opts[1]);
        assertTrue(opts[2].contains("本地 APK"));
        assertEquals("稍后", opts[3]);
    }

    @Test
    public void updateDialogOptions_noOnlineDownloadEntry() {
        StringBuilder joined = new StringBuilder();
        for (String o : GameRecloneFlow.updateDialogOptions()) joined.append(o);
        // 锁定 888MB 在线下载入口不再出现在 UI（2026-10-21 用户澄清）
        assertTrue(joined.toString(), !joined.toString().contains("立即更新"));
        assertTrue(joined.toString(), !joined.toString().contains("888"));
        assertTrue(joined.toString(), !joined.toString().contains("MB"));
    }

    @Test
    public void actionMapping_andOutOfRangeFallsToNone() {
        assertEquals(GameRecloneFlow.UpdateAction.OPEN_STORE,
                GameRecloneFlow.actionForOption(0));
        assertEquals(GameRecloneFlow.UpdateAction.RECLONE,
                GameRecloneFlow.actionForOption(1));
        assertEquals(GameRecloneFlow.UpdateAction.LOCAL_APK,
                GameRecloneFlow.actionForOption(2));
        assertEquals(GameRecloneFlow.UpdateAction.NONE,
                GameRecloneFlow.actionForOption(3));
        assertEquals(GameRecloneFlow.UpdateAction.NONE,
                GameRecloneFlow.actionForOption(-1));
        assertEquals(GameRecloneFlow.UpdateAction.NONE,
                GameRecloneFlow.actionForOption(99));
    }

    @Test
    public void updateDialogMessage_noDownloadSize() {
        String s = GameRecloneFlow.updateDialogMessage("1.16.12", 147);
        assertTrue(s, s.contains("1.16.12"));
        assertTrue(s, s.contains("147"));
        assertTrue(s, s.contains("重新克隆分身"));
        assertTrue(s, !s.contains("888"));
        assertTrue(s, !s.contains("MB"));
        assertTrue(s, !s.contains("null"));
    }

    @Test
    public void marketUri_targetsGamePackage() {
        assertEquals("market://details?id=com.gof.china", GameRecloneFlow.GAME_MARKET_URI);
    }
}
