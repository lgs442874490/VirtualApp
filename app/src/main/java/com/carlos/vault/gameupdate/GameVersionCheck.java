package com.carlos.vault.gameupdate;

/**
 * 分身游戏版本判定（M3，纯函数、可在 JVM 单测）。
 *
 * <p><b>以 versionCode 比较</b>：T1b 真机取证（2026-10-21）表明启动页渠道角标
 * 「1.16.11.12」只是显示串（渠道包后缀），包名里的 versionName 仍是 1.16.11、
 * versionCode 是 146；最新版是 1.16.12 / 147。任何基于 versionName/角标字符串的
 * 比较都会被渠道后缀干扰，故本类只接受 versionCode。
 *
 * <p>四种判定：
 * <ul>
 *   <li>{@link Status#UNKNOWN}：容器内版本读不到（未克隆/引擎未就绪）或配置缺失
 *       —— 不拦截启动（游戏服务端登录前置检查是最终裁决）；</li>
 *   <li>{@link Status#UNSUPPORTED}：低于配置的最低可升级基线（minSupportedVersionCode），
 *       覆盖安装不保证可用，建议重新克隆；</li>
 *   <li>{@link Status#OUTDATED}：低于最新版，走「下载/本地 APK → 覆盖安装（保数据）」；</li>
 *   <li>{@link Status#UP_TO_DATE}：不低于最新版。</li>
 * </ul>
 */
public final class GameVersionCheck {

    public enum Status { UNKNOWN, UNSUPPORTED, OUTDATED, UP_TO_DATE }

    private GameVersionCheck() {
    }

    /**
     * @param installedVersionCode 容器内游戏 versionCode；&lt;=0 表示读不到
     * @param latestVersionCode    配置的最新版 versionCode；&lt;=0 表示配置缺失
     * @param minSupportedVersionCode 最低可升级基线；&lt;=0 表示不启用 UNSUPPORTED 判定
     */
    public static Status check(long installedVersionCode, long latestVersionCode,
                               long minSupportedVersionCode) {
        if (installedVersionCode <= 0 || latestVersionCode <= 0) return Status.UNKNOWN;
        if (installedVersionCode >= latestVersionCode) return Status.UP_TO_DATE;
        if (minSupportedVersionCode > 0 && installedVersionCode < minSupportedVersionCode) {
            return Status.UNSUPPORTED;
        }
        return Status.OUTDATED;
    }

    /** 版本过旧弹窗正文（契约文案）：「分身游戏版本过旧（当前 1.16.11，最新 1.16.12）」。 */
    public static String outdatedText(String installedVersionName, String latestVersionName) {
        return "分身游戏版本过旧（当前 " + safe(installedVersionName) + "，最新 "
                + safe(latestVersionName) + "）";
    }

    /** 启动按钮下方状态行：把判定结果与两个版本号压成一句话。 */
    public static String statusLine(Status status, String installedVersionName,
                                    long installedVersionCode, String latestVersionName,
                                    long latestVersionCode) {
        String cur = installedVersionCode > 0
                ? safe(installedVersionName) + "(" + installedVersionCode + ")" : "未知";
        switch (status) {
            case UP_TO_DATE:
                return "分身游戏 " + cur + " · 已是最新";
            case OUTDATED:
                return "分身游戏 " + cur + " · 可更新至 " + safe(latestVersionName)
                        + "(" + latestVersionCode + ")";
            case UNSUPPORTED:
                return "分身游戏 " + cur + " · 过旧，建议重新克隆";
            case UNKNOWN:
            default:
                return "分身游戏版本未知";
        }
    }

    private static String safe(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }
}
