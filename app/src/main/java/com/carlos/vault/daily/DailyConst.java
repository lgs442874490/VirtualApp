package com.carlos.vault.daily;

/**
 * 日常/商店相关的 UI 常量（2026-11-06 从 DailyRunner 迁移）。
 *
 * <p>本地执行链删除后，仍有 UI 页面需要这些展示档位/时段常量，
 * 因此保留为独立常量类，不随执行器一起删除。
 */
public final class DailyConst {

    /** 背包道具档位阈值（40/80/120/160/215/270/325），功能页档位 chips 用。 */
    public static final int[] BOX_THRESHOLDS = {40, 80, 120, 160, 215, 270, 325};

    /** 燃霜矿区场次时段（12:30/16:00/19:00/20:30/22:00），与 ShopTasks 同名常量保持一致。 */
    public static final String[] FROSTFIRE_SLOTS = {"12:30", "16:00", "19:00", "20:30", "22:00"};

    private DailyConst() {
    }
}
