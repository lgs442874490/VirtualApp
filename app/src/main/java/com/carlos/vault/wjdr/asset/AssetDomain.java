package com.carlos.vault.wjdr.asset;

/**
 * 核心资产库的领域枚举（App / vault-server 同源编译，纯 java.util）。
 *
 * <p>每个域对应 {@link AssetSnapshot} 的一个不可变快照对象；解析器按域标记 dirty，
 * 订阅者按 dirty 域做最小唤醒（例如 produced 资源高频变化不唤醒采集任务）。
 */
public enum AssetDomain {
    META,
    CLOCK,
    CALENDAR,
    RESOURCE,
    RECOVER,
    STAMINA,
    BACKPACK,
    QUEUE,
    INTEL,
    BEHEMOTH,
    BEAR,
    WAR,
    GUARDIAN
}
