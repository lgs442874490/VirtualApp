package com.carlos.vault.gameupdate;

/**
 * M3c「更新原生游戏 + 重新克隆分身」流程决策（纯函数，JVM 单测）。
 *
 * <p>背景（2026-10-21 用户澄清）：重大版本更新不再走 App 内 888MB 在线下载，
 * 改为「用户到应用商店更新原生游戏 → App 从已更新的原生游戏重新克隆/覆盖进容器
 * （保留容器数据）」。本类把三条纯决策收拢在一处：
 * <ol>
 *   <li>原生 vs 容器版本比较（onResume 是否提示「检测到原生游戏已更新」）；</li>
 *   <li>重新克隆的前置条件与完成后的回读校验；</li>
 *   <li>版本过旧弹窗的按钮集合（锁定「立即更新(888MB)」在线下载入口不再出现在 UI）。</li>
 * </ol>
 *
 * <p>版本口径与 {@link GameVersionCheck} 一致：只认 versionCode，渠道角标不作数
 * （T1b 真机取证 2026-10-21：容器/原生均 1.16.11(146)，最新 1.16.12(147)）。
 *
 * <p>失败文案的措辞与 {@code VaultEngine.categorizeFailure} 的关键词联动
 * （「克隆」→CLONE、「尚未更新」→VERSION、「安装」→INSTALL），有单测锁定。
 */
public final class GameRecloneFlow {

    /** 原生游戏商店详情页（无 market:// 处理器的 ROM 由调用方 fallback 官网页/指引弹窗） */
    public static final String GAME_MARKET_URI = "market://details?id=com.gof.china";

    private GameRecloneFlow() {
    }

    // ---------- ① 原生 vs 容器版本比较（onResume 检测） ----------

    public enum NativeCompare { UNKNOWN, NATIVE_NEWER, IN_SYNC, CONTAINER_NEWER }

    /** 原生/容器 versionCode 比较；任一读不到（&lt;=0）→ UNKNOWN。 */
    public static NativeCompare compareNativeVsContainer(long nativeVc, long containerVc) {
        if (nativeVc <= 0 || containerVc <= 0) return NativeCompare.UNKNOWN;
        if (nativeVc > containerVc) return NativeCompare.NATIVE_NEWER;
        if (nativeVc == containerVc) return NativeCompare.IN_SYNC;
        return NativeCompare.CONTAINER_NEWER;
    }

    /** onResume 是否提示重新克隆：仅原生 versionCode 严格大于容器时提示。 */
    public static boolean shouldPromptReclone(long nativeVc, long containerVc) {
        return compareNativeVsContainer(nativeVc, containerVc) == NativeCompare.NATIVE_NEWER;
    }

    /** onResume 提示文案（首行锁定契约原文「检测到原生游戏已更新，是否重新克隆分身？」）。 */
    public static String reclonePromptText(String nativeVersionName, long nativeVc,
                                           String containerVersionName, long containerVc) {
        return "检测到原生游戏已更新，是否重新克隆分身？\n"
                + "原生 " + safe(nativeVersionName) + "(" + nativeVc + ") · 分身 "
                + safe(containerVersionName) + "(" + containerVc + ")";
    }

    // ---------- ② 重新克隆：前置条件 + 完成后回读 ----------

    /** 重新克隆前置不满足的原因（引擎就绪由 VaultEngine.ensureEngineReady 单独把关）。 */
    public enum RecloneBlock { NONE, NATIVE_MISSING, NATIVE_UNREADABLE }

    /**
     * 前置条件：手机上已装原生游戏且版本可读。
     *
     * @param nativeInstalled  系统 PackageManager 能否解析到 GAME_PKG
     * @param nativeVersionCode 原生 versionCode（&lt;=0 = 读不到）
     */
    public static RecloneBlock recloneBlock(boolean nativeInstalled, long nativeVersionCode) {
        if (!nativeInstalled) return RecloneBlock.NATIVE_MISSING;
        if (nativeVersionCode <= 0) return RecloneBlock.NATIVE_UNREADABLE;
        return RecloneBlock.NONE;
    }

    /** 前置不满足的用户文案（NATIVE_MISSING 含「克隆」→ 诊断归 CLONE 类）；NONE 返回 null。 */
    public static String blockText(RecloneBlock block) {
        if (block == RecloneBlock.NATIVE_MISSING) {
            return "无法重新克隆：手机上未找到已安装的原生游戏「无尽冬日」，请先安装或更新原生游戏";
        }
        if (block == RecloneBlock.NATIVE_UNREADABLE) {
            return "无法读取原生游戏版本，请确认「无尽冬日」已在手机上正确安装";
        }
        return null;
    }

    /** 重新克隆完成后的回读判定。 */
    public enum PostReclone { OK, NATIVE_NOT_UPDATED, INSTALL_NOT_EFFECTIVE }

    /**
     * @param nativeVc          原生 versionCode（前置已保证 &gt;0，防御再判）
     * @param containerVcBefore 克隆前容器 versionCode（-1 = 容器原本没装，走全新克隆）
     * @param containerVcAfter  克隆后回读的容器 versionCode
     */
    public static PostReclone verifyAfterReclone(long nativeVc, long containerVcBefore,
                                                 long containerVcAfter) {
        if (nativeVc <= 0) return PostReclone.NATIVE_NOT_UPDATED;
        if (containerVcAfter <= 0) return PostReclone.INSTALL_NOT_EFFECTIVE;
        if (containerVcAfter >= nativeVc) {
            // 已同步到原生：但若与克隆前一样（原生其实没升级），按「原生尚未更新」引导去商店
            return containerVcAfter > containerVcBefore
                    ? PostReclone.OK : PostReclone.NATIVE_NOT_UPDATED;
        }
        // 仍低于原生：比克隆前新说明覆盖安装没吃满（INSTALL_NOT_EFFECTIVE），没变说明白克隆一趟
        return containerVcAfter > containerVcBefore
                ? PostReclone.INSTALL_NOT_EFFECTIVE : PostReclone.NATIVE_NOT_UPDATED;
    }

    /** 「原生游戏尚未更新」契约文案（含「尚未更新」→ 诊断归 VERSION 类）。 */
    public static String nativeNotUpdatedText() {
        return "原生游戏尚未更新，请先更新原生游戏";
    }

    /** 回读失败的展示文案（OK 返回 null；NATIVE_NOT_UPDATED 原样；安装未生效带两侧版本号）。 */
    public static String failureText(PostReclone result, String nativeVersionName, long nativeVc,
                                     String containerVersionName, long containerVc) {
        if (result == PostReclone.NATIVE_NOT_UPDATED) return nativeNotUpdatedText();
        if (result == PostReclone.INSTALL_NOT_EFFECTIVE) {
            // 措辞避开「克隆/版本」关键词，让诊断归 INSTALL 类而不是 CLONE/VERSION
            return "覆盖安装未生效：容器内游戏仍是 " + safe(containerVersionName)
                    + "(" + containerVc + ")，原生为 " + safe(nativeVersionName) + "(" + nativeVc + ")";
        }
        return null;
    }

    // ---------- ③ 版本过旧弹窗按钮（在线下载入口已移除） ----------

    /** 弹窗选项动作（NONE = 稍后，仅关闭弹窗）。 */
    public enum UpdateAction { OPEN_STORE, RECLONE, LOCAL_APK, NONE }

    /**
     * 版本过旧弹窗按钮集合（顺序即 UI 顺序）：更新原生游戏（主）/
     * 重新克隆分身 / 从本地 APK 更新（高级兜底）/ 稍后。
     * <b>不含任何在线下载项</b>——单测锁定集合里不出现「立即更新」/「888」/「MB」。
     */
    public static String[] updateDialogOptions() {
        return new String[]{
                "更新原生游戏（推荐）",
                "重新克隆分身",
                "从本地 APK 更新（高级）",
                "稍后",
        };
    }

    /** 选项下标 → 动作；越界一律 NONE（仅关闭）。 */
    public static UpdateAction actionForOption(int which) {
        switch (which) {
            case 0: return UpdateAction.OPEN_STORE;
            case 1: return UpdateAction.RECLONE;
            case 2: return UpdateAction.LOCAL_APK;
            default: return UpdateAction.NONE;
        }
    }

    /** 弹窗正文：只讲「商店更新 → 重新克隆」路线，不出现下载大小等在线下载信息。 */
    public static String updateDialogMessage(String latestVersionName, long latestVersionCode) {
        return "最新版本 " + safe(latestVersionName) + "（versionCode " + latestVersionCode + "）。\n"
                + "推荐：先到应用商店更新手机上的原生游戏，回到本页点「重新克隆分身」同步进容器（游戏数据保留）。\n"
                + "「从本地 APK 更新」为高级兜底。";
    }

    private static String safe(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }
}
