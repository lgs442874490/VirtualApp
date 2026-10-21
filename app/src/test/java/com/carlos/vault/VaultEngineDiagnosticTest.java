package com.carlos.vault;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M3 启动/更新失败诊断分类：categorizeFailure 纯函数 + Diagnostic 保留原文
 * （契约 §4.1「必须展示 lastLaunchError 具体值（分类+原文）」）。
 *
 * <p>用例里的原文全部取自引擎实际会产生的文案（含 T1 真机复盘的旧泛化文案对照）。
 */
public class VaultEngineDiagnosticTest {

    // ---------- 各类命中 ----------

    @Test
    public void license() {
        assertEquals(VaultEngine.FailureCategory.LICENSE,
                VaultEngine.categorizeFailure("请先使用兑换码激活"));
        assertEquals(VaultEngine.FailureCategory.LICENSE,
                VaultEngine.categorizeFailure("授权已过期，请重新激活本应用"));
    }

    @Test
    public void engine() {
        assertEquals(VaultEngine.FailureCategory.ENGINE,
                VaultEngine.categorizeFailure("引擎未就绪，请重启App后重试"));
        assertEquals(VaultEngine.FailureCategory.ENGINE,
                VaultEngine.categorizeFailure("引擎启动异常:null"));
    }

    @Test
    public void cloneCategory() {
        // launchGameLocked 真实文案
        assertEquals(VaultEngine.FailureCategory.CLONE,
                VaultEngine.categorizeFailure("容器里没有该游戏，请先完成①克隆"));
        assertEquals(VaultEngine.FailureCategory.CLONE,
                VaultEngine.categorizeFailure("解析不到启动入口（已试 userId=0,1,2），请确认已完成①克隆"));
    }

    @Test
    public void version() {
        assertEquals(VaultEngine.FailureCategory.VERSION,
                VaultEngine.categorizeFailure("分身游戏版本过旧（当前 1.16.11，最新 1.16.12）"));
    }

    @Test
    public void version_recloneNativeNotUpdated() {
        // M3c：原生没更新就点「重新克隆分身」的契约文案 → VERSION（UI 据此给「更新原生游戏」）
        assertEquals(VaultEngine.FailureCategory.VERSION,
                VaultEngine.categorizeFailure("原生游戏尚未更新，请先更新原生游戏"));
    }

    @Test
    public void download() {
        assertEquals(VaultEngine.FailureCategory.DOWNLOAD,
                VaultEngine.categorizeFailure("下载服务返回 HTTP 502"));
        assertEquals(VaultEngine.FailureCategory.DOWNLOAD,
                VaultEngine.categorizeFailure("连接中断：Connection reset（重试将断点续传）"));
        assertEquals(VaultEngine.FailureCategory.DOWNLOAD,
                VaultEngine.categorizeFailure("安装失败: 更新包不存在或为空"));
    }

    @Test
    public void install() {
        assertEquals(VaultEngine.FailureCategory.INSTALL,
                VaultEngine.categorizeFailure("安装失败: status=3"));
        assertEquals(VaultEngine.FailureCategory.INSTALL,
                VaultEngine.categorizeFailure("安装异常:null"));
        assertEquals(VaultEngine.FailureCategory.INSTALL,
                VaultEngine.categorizeFailure("安装未生效：覆盖安装后容器内游戏仍是 1.16.11(146)"));
    }

    @Test
    public void install_recloneNotEffective() {
        // M3c：重新克隆回读「容器仍低于原生」的文案 —— 避开「克隆/版本」词，归 INSTALL
        assertEquals(VaultEngine.FailureCategory.INSTALL,
                VaultEngine.categorizeFailure(
                        "覆盖安装未生效：容器内游戏仍是 1.16.11(146)，原生为 1.16.12(147)"));
    }

    @Test
    public void clone_recloneNativeMissing() {
        // M3c：手机上没有原生游戏时的前置失败文案（含「克隆」）→ CLONE
        assertEquals(VaultEngine.FailureCategory.CLONE,
                VaultEngine.categorizeFailure(
                        "无法重新克隆：手机上未找到已安装的原生游戏「无尽冬日」，请先安装或更新原生游戏"));
    }

    @Test
    public void system() {
        assertEquals(VaultEngine.FailureCategory.SYSTEM,
                VaultEngine.categorizeFailure("存储空间不足：可用 800MB，本次更新至少需要 1.50GB"));
    }

    @Test
    public void other() {
        assertEquals(VaultEngine.FailureCategory.OTHER,
                VaultEngine.categorizeFailure("正在切换/启动账号，请稍候再试"));
        assertEquals(VaultEngine.FailureCategory.OTHER,
                VaultEngine.categorizeFailure("已解析到入口但拉起失败（已试 userId=0）"));
        assertEquals(VaultEngine.FailureCategory.OTHER, VaultEngine.categorizeFailure(null));
        assertEquals(VaultEngine.FailureCategory.OTHER, VaultEngine.categorizeFailure(""));
        assertEquals(VaultEngine.FailureCategory.OTHER, VaultEngine.categorizeFailure("   "));
    }

    // ---------- 优先级（更具体优先，防「下载的存储不足」错分） ----------

    @Test
    public void specificWords_winOverGeneric() {
        // 「下载」先于「存储」命中 → 下载链路
        assertEquals(VaultEngine.FailureCategory.DOWNLOAD,
                VaultEngine.categorizeFailure("下载时存储空间不足"));
        // 「安装」先于「存储」命中 → 安装链路
        assertEquals(VaultEngine.FailureCategory.INSTALL,
                VaultEngine.categorizeFailure("安装时存储异常"));
    }

    // ---------- Diagnostic：分类 + 原文 ----------

    @Test
    public void diagnostic_keepsRawText() {
        String raw = "下载服务返回 HTTP 502";
        VaultEngine.Diagnostic d = new VaultEngine.Diagnostic(
                VaultEngine.categorizeFailure(raw), raw);
        assertEquals(VaultEngine.FailureCategory.DOWNLOAD, d.category);
        assertEquals(raw, d.raw);
        assertEquals("[下载] 下载服务返回 HTTP 502", d.display());
    }

    @Test
    public void diagnostic_nullRawGetsPlaceholder() {
        VaultEngine.Diagnostic d = new VaultEngine.Diagnostic(
                VaultEngine.FailureCategory.OTHER, null);
        assertEquals("", d.raw);
    }

    @Test
    public void categoryLabels_coverAll() {
        assertEquals("授权", VaultEngine.categoryLabel(VaultEngine.FailureCategory.LICENSE));
        assertEquals("容器引擎", VaultEngine.categoryLabel(VaultEngine.FailureCategory.ENGINE));
        assertEquals("游戏未克隆", VaultEngine.categoryLabel(VaultEngine.FailureCategory.CLONE));
        assertEquals("版本过旧", VaultEngine.categoryLabel(VaultEngine.FailureCategory.VERSION));
        assertEquals("下载", VaultEngine.categoryLabel(VaultEngine.FailureCategory.DOWNLOAD));
        assertEquals("安装", VaultEngine.categoryLabel(VaultEngine.FailureCategory.INSTALL));
        assertEquals("系统环境", VaultEngine.categoryLabel(VaultEngine.FailureCategory.SYSTEM));
        assertEquals("其他", VaultEngine.categoryLabel(VaultEngine.FailureCategory.OTHER));
        assertEquals("未知", VaultEngine.categoryLabel(null));
    }

    // ---------- 游戏包名常量（全链路一致性锚点） ----------

    @Test
    public void gamePkg_consistentWithVerifier() {
        assertEquals("com.gof.china", VaultEngine.GAME_PKG);
        assertEquals(VaultEngine.GAME_PKG, com.carlos.vault.gameupdate.GameApkVerifier.GAME_PKG);
        assertTrue(VaultEngine.GAME_PKG.length() > 0);
    }
}
