package com.carlos.vault.gameupdate;

/**
 * 克隆/重新克隆「系统里找不到原生安卓版游戏」的判定与鸿蒙引导文案（纯函数，JVM 单测）。
 *
 * <p>背景（2026-10-29 用户反馈）：鸿蒙手机（HarmonyOS 7 + 应用市场）里装好「无尽冬日」，
 * 号库仍提示找不到安装包、无法克隆。原因是应用市场现在下发的是<b>鸿蒙原生版（HAP）</b>，
 * 它不在 Android PackageManager 的可见范围内 —— 号库容器只能克隆安卓版 APK，
 * 系统查询必然抛 {@code NameNotFoundException}，旧文案「手机上未找到已安装的无尽冬日」
 * 让用户误以为是安装包丢失。
 *
 * <p>本类只做三件事：识别「找不到安卓版」类失败（{@link #isNativePackageMissing}、
 * {@link #isEngineResolveFailure}）、按机型给出可执行的引导文案
 * （{@link #missingGuideText}）、提供引导文案前缀供 UI 判定（{@link #isMissingGuide}）。
 * 真正的兜底动作（从「下载」目录/本地选择 APK 装入容器）在
 * {@link com.carlos.vault.VaultEngine#installGameFromLocalApk} 与
 * {@link GameCloneGuide}。
 */
public final class GameCloneFallback {

    /**
     * 「系统里找不到可克隆的安卓版游戏」引导文案的固定开头。
     * UI 据此把这类失败升级成带「选择本地 APK」按钮的引导弹窗，而不是一句 toast。
     */
    public static final String MISSING_PREFIX = "系统里没有可克隆的「安卓版」无尽冬日";

    private GameCloneFallback() {
    }

    // ---------- 失败归因（纯函数） ----------

    /**
     * 失败文案是否属于「系统 PackageManager 里找不到已安装的安卓版游戏」。
     *
     * <p>覆盖三处引擎/流程原文：
     * <ul>
     *   <li>{@code VaultEngine.cloneFromSystem}：「手机上未找到已安装的无尽冬日」</li>
     *   <li>{@code GameRecloneFlow.blockText(NATIVE_MISSING)}：「无法重新克隆：手机上未找到已安装的原生游戏…」</li>
     *   <li>{@code GameRecloneFlow.blockText(NATIVE_UNREADABLE)}：「无法读取原生游戏版本…」</li>
     * </ul>
     */
    public static boolean isNativePackageMissing(String err) {
        if (err == null) return false;
        return err.contains("未找到已安装")
                || err.contains("无法读取原生游戏版本");
    }

    /**
     * 引擎自己解析不到安装包（package: URI）时的失败文案：status=7 INCOMPATIBLE /
     * status=8 NO_BASE_APK。这类情况同样可以退到「本地 APK」兜底。
     */
    public static boolean isEngineResolveFailure(String err) {
        return err != null && (err.contains("status=7") || err.contains("status=8"));
    }

    /** 是否应该退到「本地 APK」兜底（系统看不到原生游戏，或引擎解析不到安装包）。 */
    public static boolean shouldUseLocalApkFallback(String err) {
        return isNativePackageMissing(err) || isEngineResolveFailure(err);
    }

    /** 是否是「找不到安卓版」引导文案（{@link #missingGuideText} 的产出）。 */
    public static boolean isMissingGuide(String text) {
        return text != null && text.startsWith(MISSING_PREFIX);
    }

    // ---------- 机型与文案（纯函数） ----------

    /** 华为/鸿蒙设备判定：鸿蒙机型仍上报 manufacturer/brand 为 HUAWEI/HONOR。 */
    public static boolean isHuaweiLike(String manufacturer, String brand) {
        return containsIgnoreCase(manufacturer, "huawei")
                || containsIgnoreCase(manufacturer, "honor")
                || containsIgnoreCase(brand, "huawei")
                || containsIgnoreCase(brand, "honor");
    }

    /**
     * 找不到安卓版游戏时的引导文案。
     *
     * @param huaweiLike 华为/鸿蒙设备：说明「应用市场装的是鸿蒙版 HAP」这一真实原因
     */
    public static String missingGuideText(boolean huaweiLike) {
        if (huaweiLike) {
            return MISSING_PREFIX + "。\n"
                    + "鸿蒙手机在应用市场装的通常是「鸿蒙版」（HAP 应用），安卓容器看不到它，"
                    + "也没法克隆——不是安装包丢了。\n\n"
                    + "两个办法：\n"
                    + "① 用浏览器下载安卓版无尽冬日 APK，点「选择本地 APK」导入（推荐）；\n"
                    + "② 在卓易通/安卓环境里装一个安卓版无尽冬日，再回来点「开始克隆」自动识别。\n\n"
                    + "若已把 wjdr*.apk 放进「下载」目录，也可点「开始克隆」重试（需允许号库读取下载目录）。";
        }
        return MISSING_PREFIX + "。\n"
                + "请确认已在手机安装安卓版无尽冬日，或点「选择本地 APK」导入官方 APK。";
    }

    /** 引导文案 + 引擎原始失败原因（客服/诊断定位用，原文不丢）。 */
    public static String guideWithDetail(boolean huaweiLike, String rawError) {
        // rawError 本身已是引导文案（VaultEngine 兜底失败时原样返回）→ 不重复拼接
        if (isMissingGuide(rawError)) return rawError;
        String text = missingGuideText(huaweiLike);
        if (rawError == null || rawError.trim().isEmpty()) return text;
        return text + "\n\n系统返回：" + rawError.trim();
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }
}
