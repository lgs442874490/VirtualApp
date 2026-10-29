package com.carlos.vault.gameupdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 鸿蒙克隆兜底（2026-10-29）：失败归因、机型判定与引导文案纯函数。
 *
 * <p>背景：鸿蒙手机应用市场装的是「鸿蒙版」（HAP），安卓容器看不到、无法克隆。
 * 这些用例锁定：① 只有「找不到安卓版」类失败才走本地 APK 兜底；
 * ② 华为/鸿蒙机型文案必须说清真实原因（鸿蒙版 HAP）并给出两条出路；
 * ③ 引导文案有固定前缀供 UI 判定，且原文可回溯。
 */
public class GameCloneFallbackTest {

    // ---------- 失败归因 ----------

    @Test
    public void nativeMissing_cloneFromSystem() {
        // VaultEngine.cloneFromSystem 原文
        assertTrue(GameCloneFallback.isNativePackageMissing(
                "手机上未找到已安装的无尽冬日"));
        // GameRecloneFlow NATIVE_MISSING 原文
        assertTrue(GameCloneFallback.isNativePackageMissing(
                "无法重新克隆：手机上未找到已安装的原生游戏「无尽冬日」，请先安装或更新原生游戏"));
        // GameRecloneFlow NATIVE_UNREADABLE 原文
        assertTrue(GameCloneFallback.isNativePackageMissing(
                "无法读取原生游戏版本，请确认「无尽冬日」已在手机上正确安装"));
    }

    @Test
    public void nativeMissing_falseForOtherFailures() {
        assertFalse(GameCloneFallback.isNativePackageMissing(null));
        assertFalse(GameCloneFallback.isNativePackageMissing(""));
        assertFalse(GameCloneFallback.isNativePackageMissing("引擎未就绪，请重启App后重试"));
        assertFalse(GameCloneFallback.isNativePackageMissing("安装失败: status=3"));
        assertFalse(GameCloneFallback.isNativePackageMissing("原生游戏尚未更新，请先更新原生游戏"));
    }

    @Test
    public void engineResolveFailure_status7and8() {
        // package: URI 解析不到 → STATUS_FAILURE_INCOMPATIBLE(7) / NO_BASE_APK(8)
        assertTrue(GameCloneFallback.isEngineResolveFailure("安装失败: status=7"));
        assertTrue(GameCloneFallback.isEngineResolveFailure("安装失败: status=8"));
        assertFalse(GameCloneFallback.isEngineResolveFailure("安装失败: status=3"));
        assertFalse(GameCloneFallback.isEngineResolveFailure(null));
    }

    @Test
    public void shouldUseLocalApkFallback_onlyForResolveFailures() {
        assertTrue(GameCloneFallback.shouldUseLocalApkFallback("手机上未找到已安装的无尽冬日"));
        assertTrue(GameCloneFallback.shouldUseLocalApkFallback("安装失败: status=7"));
        assertFalse(GameCloneFallback.shouldUseLocalApkFallback("存储空间不足：可用 800MB"));
        assertFalse(GameCloneFallback.shouldUseLocalApkFallback("正在切换/启动账号，请稍候再试"));
    }

    // ---------- 机型判定 ----------

    @Test
    public void huaweiLike_byManufacturerOrBrand() {
        assertTrue(GameCloneFallback.isHuaweiLike("HUAWEI", "HUAWEI"));
        assertTrue(GameCloneFallback.isHuaweiLike("HUAWEI", "HONOR"));
        assertTrue(GameCloneFallback.isHuaweiLike("honor", "HONOR"));
        assertTrue(GameCloneFallback.isHuaweiLike(null, "HUAWEI"));
        assertTrue(GameCloneFallback.isHuaweiLike("Huawei", null));
        assertFalse(GameCloneFallback.isHuaweiLike("Xiaomi", "Redmi"));
        assertFalse(GameCloneFallback.isHuaweiLike(null, null));
        assertFalse(GameCloneFallback.isHuaweiLike("", ""));
    }

    // ---------- 引导文案 ----------

    @Test
    public void guide_huawei_explainsHapAndGivesTwoWays() {
        String s = GameCloneFallback.missingGuideText(true);
        assertNotNull(s);
        assertTrue(s.startsWith(GameCloneFallback.MISSING_PREFIX));
        // 真实原因：鸿蒙版是 HAP，安卓容器看不到
        assertTrue(s, s.contains("鸿蒙版"));
        assertTrue(s, s.contains("HAP"));
        // 两条可执行出路
        assertTrue(s, s.contains("选择本地 APK"));
        assertTrue(s, s.contains("卓易通"));
        assertTrue(s, s.contains("wjdr"));
    }

    @Test
    public void guide_nonHuawei_staysNeutral() {
        String s = GameCloneFallback.missingGuideText(false);
        assertTrue(s.startsWith(GameCloneFallback.MISSING_PREFIX));
        assertFalse(s, s.contains("鸿蒙版"));
        assertTrue(s, s.contains("选择本地 APK"));
    }

    @Test
    public void missingGuide_marker() {
        assertTrue(GameCloneFallback.isMissingGuide(
                GameCloneFallback.missingGuideText(true)));
        assertTrue(GameCloneFallback.isMissingGuide(
                GameCloneFallback.guideWithDetail(true, "手机上未找到已安装的无尽冬日")));
        assertFalse(GameCloneFallback.isMissingGuide(null));
        assertFalse(GameCloneFallback.isMissingGuide("安装失败: status=3"));
        assertFalse(GameCloneFallback.isMissingGuide("克隆失败：" + GameCloneFallback.MISSING_PREFIX));
    }

    @Test
    public void guideWithDetail_keepsRawError() {
        String s = GameCloneFallback.guideWithDetail(false, "手机上未找到已安装的无尽冬日");
        assertTrue(s, s.contains("系统返回：手机上未找到已安装的无尽冬日"));
        // 没有原文/空白原文时不追加「系统返回：」
        assertFalse(GameCloneFallback.guideWithDetail(false, null).contains("系统返回："));
        assertFalse(GameCloneFallback.guideWithDetail(false, "  ").contains("系统返回："));
    }

    @Test
    public void guideWithDetail_doesNotDuplicateGuide() {
        String guide = GameCloneFallback.missingGuideText(true);
        String s = GameCloneFallback.guideWithDetail(true, guide);
        assertEquals(guide, s);
        assertFalse(s.contains("系统返回："));
    }

    /** 引导文案进诊断分类时仍归 CLONE（含「克隆」关键词），UI 修复动作一致。 */
    @Test
    public void guide_textCategorizedAsClone() {
        assertEquals(com.carlos.vault.VaultEngine.FailureCategory.CLONE,
                com.carlos.vault.VaultEngine.categorizeFailure(
                        GameCloneFallback.missingGuideText(true)));
        assertEquals(com.carlos.vault.VaultEngine.FailureCategory.CLONE,
                com.carlos.vault.VaultEngine.categorizeFailure(
                        GameCloneFallback.guideWithDetail(false, "手机上未找到已安装的无尽冬日")));
    }
}
