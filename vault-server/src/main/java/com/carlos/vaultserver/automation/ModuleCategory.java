package com.carlos.vaultserver.automation;

/** 自动化模块域。模块只按域分组，不按游戏页面分组，便于复用与独立开关。 */
public enum ModuleCategory {
    REWARD,
    ALLIANCE,
    BANK,
    GATHER,
    PET,
    SHOP,
    MEDIC,
    WILDERNESS,
    LEGACY
}
