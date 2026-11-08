package com.carlos.vaultserver.alert;

/**
 * 关注触发通道（并集）：
 * <ul>
 *   <li>{@link #PLAYER} T1：攻击者在关注玩家名单（fid 解析后的 uid）；</li>
 *   <li>{@link #AREA} T2：终点落在关注区域（保护矩形）；</li>
 *   <li>{@link #ALLY} T3：目标是友盟城；</li>
 *   <li>{@link #OPS} T4：运维类（迁城/建筑/扫描）。</li>
 * </ul>
 */
public enum Trigger {
    PLAYER, AREA, ALLY, OPS
}
