package com.carlos.vault.protocapture;

/**
 * 抓包目标选择（2026-11-08）：把「目标应用」从手填包名改成下拉选项。
 *
 * <p>关键背景：号库是 VA 容器，容器内游戏（com.gof.china）进程 UID 与号库宿主相同（如 10379）。
 * 若按游戏包名调用 {@code addAllowedApplication}，VPN 只覆盖原生包 UID（如 10113），
 * 容器内游戏流量不经过 VPN，表现为 {@code records=0}。因此「无尽冬日（容器内）」必须映射到
 * 号库宿主包名，按宿主 UID 抓取；只有原生安装场景才直接用游戏包名。
 */
public final class ProtoCaptureTargets {

    public static final String LABEL_CONTAINER_GAME = "无尽冬日（容器内）";
    public static final String LABEL_HOST = "号库";
    public static final String LABEL_NATIVE_GAME = "无尽冬日（原生安装）";
    public static final String LABEL_ALL = "全部应用";

    /** 无尽冬日国服/国际服包名。 */
    public static final String[] GAME_PACKAGES = {"com.gof.china", "com.gof.global"};

    private ProtoCaptureTargets() {
    }

    /** 下拉项文案（与 {@link #values(String)} 一一对应）。 */
    public static String[] labels() {
        return new String[]{LABEL_CONTAINER_GAME, LABEL_HOST, LABEL_NATIVE_GAME, LABEL_ALL};
    }

    /** 与下拉项对应的实际 target 包名；空串=全部应用（不按包过滤）。 */
    public static String[] values(String hostPackage) {
        String host = hostPackage == null ? "" : hostPackage.trim();
        return new String[]{host, host, GAME_PACKAGES[0], ""};
    }

    /** 默认下拉项：容器内有游戏 → 第 0 项；否则原生安装 → 第 2 项；否则全部应用。 */
    public static int defaultIndex(boolean containerGameRunning, boolean nativeGameInstalled) {
        if (containerGameRunning) return 0;
        if (nativeGameInstalled) return 2;
        return 3;
    }

    /** 已保存的 target 值 → 下拉索引；找不到则用 fallback。 */
    public static int indexOfValue(String value, String hostPackage, int fallback) {
        String v = value == null ? "" : value.trim();
        String[] vals = values(hostPackage);
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].equals(v)) return i;
        }
        return fallback;
    }

    /**
     * 实际抓取目标：目标包在容器内运行（其 UID == 号库自身 UID）时，改抓宿主包名，
     * 让 VPN 覆盖容器内游戏的流量。
     *
     * @param requested    下拉/用户选择的目标包名；空=全部应用
     * @param myUid        号库自身 UID
     * @param requestedUid 目标包对应 UID（未知/查不到=0）
     * @param hostPackage  号库宿主包名
     */
    public static String resolve(String requested, long myUid, long requestedUid, String hostPackage) {
        String t = requested == null ? "" : requested.trim();
        if (t.isEmpty()) return "";
        if (requestedUid > 0L && requestedUid == myUid) {
            return hostPackage == null ? t : hostPackage;
        }
        return t;
    }

    /** 是否无尽冬日包名（国服/国际服）。 */
    public static boolean isGamePackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim();
        for (String g : GAME_PACKAGES) {
            if (g.equals(p)) return true;
        }
        return false;
    }
}
