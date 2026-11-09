package com.carlos.vaultserver.alert;

/**
 * 名单服务全局注册表（最小侵入接线）。
 *
 * <p>观察点/战争通知在各自构造里拿不到 {@link WatchlistService}；P2 用静态注册表注入，
 * 未安装时观察点回退旧 {@code monitorTargets} 路径，保证既有单测与未迁移部署行为不变。
 */
public final class WatchlistRegistry {

    private static volatile WatchlistService shared;

    private WatchlistRegistry() {
    }

    public static void install(WatchlistService service) {
        shared = service;
    }

    public static WatchlistService shared() {
        return shared;
    }

    /** 是否命中关注名单（未安装注册表时返回 false，调用方回退旧路径）。 */
    public static boolean isWatchedUid(long uid) {
        WatchlistService s = shared;
        return s != null && s.allWatchedUids().contains(uid);
    }
}
