package com.carlos.vaultserver.alert;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 合并后的告警配置（设计 §12）。
 *
 * <p>P1 只放判定需要的字段，避免过早绑定存储；P2 再接 fid 名单与迁移。
 */
public final class AlertConfig {

    /** 总开关（迁移期 = watch.enabled OR watchpoint.enabled）。 */
    public boolean enabled;

    /** 友盟 aid 集合。 */
    public Set<Long> friendAids = new LinkedHashSet<>();

    /** aid → 联盟简称。 */
    public Map<Long, String> aidLabels = new LinkedHashMap<>();

    /** 佯攻/威胁阈值；<=0 视为关闭分级（一律威胁）。 */
    public long feintTroopsBelow = 1000L;

    /** 集结随从（99/100）是否告警；默认 false 与现有 A2 语义对齐（A2 只报 2/102）。 */
    public boolean rallyFollowerAlerts;

    /** 路由开关；缺省视为开。 */
    public final Map<Route, Boolean> routeEnabled = new LinkedHashMap<>();

    public boolean routeOn(Route route) {
        Boolean v = routeEnabled.get(route);
        return v == null || v;
    }
}
