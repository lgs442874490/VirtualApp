package com.carlos.vaultserver.formation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个「角色编队」定义（批次10 统一编队系统；1:1 参考网页版
 * {@code modules/daily/persistence/entity/DailyRoleFormationEntity} +
 * {@code model/DailyRoleFormationRuntimePlan} + {@code config/DailyBearFormationConfig}）。
 *
 * <p>数据来源 = App 功能页保存的编队库（roleParams {@code formation_library} 字符串 JSON）：
 * <pre>
 * {"formations":[{"id":1,"name":"采集队","hero_ids":[50041,50039],
 *   "soldiers":{"infantry":20000,"lancer":1549,"marksman":28451},
 *   "ratio":{"infantry":40,"lancer":30,"marksman":30},"total":50000}]}
 * </pre>
 *
 * <p><b>字段口径（与 {@link FormationLibrary#parse} 一致）</b>：
 * <ul>
 *   <li>{@link #heroIds}：编队英雄 id 列表（顺序=上阵顺序）；</li>
 *   <li>{@link #infantry}/{@link #lancer}/{@link #marksman}：盾/矛/弓**兵力数**（soldiers 字段）；</li>
 *   <li>{@link #ratio}：比例权重（AUTOMATIC 或 total=0 时按比例分配；默认 40:30:30）；</li>
 *   <li>{@link #total}：本编队总兵力上限；0=按比例/库存自动（见 {@link FormationResolver}）。</li>
 * </ul>
 *
 * <p>该类是纯数据对象（无 Android 依赖），服务端各队列任务与 impl-bear（巨熊/巨兽/守护盟友）共用。
 */
public final class FormationSpec {

    /** 编队 id（App 功能页生成；1..N；0=非法/未选）。 */
    public long id;
    /** 展示名（日志/状态行用）。 */
    public String name = "";
    /** 英雄 id 列表（顺序=上阵顺序；空=非法编队）。 */
    public List<Long> heroIds = new ArrayList<>();
    /** 盾兵兵力（soldiers.infantry）。 */
    public long infantry;
    /** 矛兵兵力（soldiers.lancer）。 */
    public long lancer;
    /** 弓兵兵力（soldiers.marksman）。 */
    public long marksman;
    /** 比例权重（infantry/lancer/marksman；默认 40/30/30；total=0 或 AUTOMATIC 时用）。 */
    public Map<String, Long> ratio = defaultRatio();
    /** 总兵力上限；0=按比例/库存自动。 */
    public long total;

    /** 默认比例 40:30:30（网页版 DailyGatherConfig / DailyHuntConfig 默认口径）。 */
    public static Map<String, Long> defaultRatio() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("infantry", 40L);
        m.put("lancer", 30L);
        m.put("marksman", 30L);
        return m;
    }

    /** 巨熊上车/发起专用比例 1:1:1（{@code DailyBearFormationConfig} 默认）。 */
    public static Map<String, Long> bearRatio() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("infantry", 1L);
        m.put("lancer", 1L);
        m.put("marksman", 1L);
        return m;
    }

    /** 三兵种兵力合计。 */
    public long soldiersTotal() {
        return infantry + lancer + marksman;
    }

    /** 是否可用于出征：id>0 且至少 1 个英雄。 */
    public boolean usable() {
        return id > 0 && heroIds != null && !heroIds.isEmpty();
    }

    /** 是否显式配置了兵量（soldiers 非全 0）。 */
    public boolean hasExplicitSoldiers() {
        return infantry > 0 || lancer > 0 || marksman > 0;
    }

    /** 比例权重（缺键回默认 40/30/30；负数按 0）。 */
    public long ratioOf(String key) {
        Long v = ratio == null ? null : ratio.get(key);
        if (v != null && v >= 0) return v;
        return defaultRatio().getOrDefault(key, 0L);
    }

    @Override
    public String toString() {
        return "FormationSpec{id=" + id + ", name=" + name + ", heroes=" + heroIds
                + ", soldiers=" + infantry + "/" + lancer + "/" + marksman
                + ", ratio=" + ratio + ", total=" + total + "}";
    }
}
