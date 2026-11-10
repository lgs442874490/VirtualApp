package com.carlos.vaultserver.alert;

/** 威胁分级：只影响文案级别，不影响是否告警。 */
public enum ThreatLevel {
    /** 兵力未知。 */
    UNKNOWN,
    /** 低于佯攻阈值。 */
    FEINT,
    /** 达到/超过阈值。 */
    THREAT
}
