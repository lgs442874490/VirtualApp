package com.carlos.vaultserver.formation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个「角色编队」定义（批次10 统一编队系统；1:1 参考网页版
 * {@code modules/daily/persistence/entity/DailyRoleFormationEntity} +
 * {@code model/DailyRoleFormationSnapshot} + {@code model/DailyRoleFormationView} +
 * {@code model/DailyRoleFormationRuntimePlan} + {@code config/DailyBearFormationConfig}）。
 *
 * <p>数据来源 = App 功能页保存的编队库（roleParams {@code formation_library} 字符串 JSON）：
 * <pre>
 * {"formations":[{"id":1,"name":"采集队","hero_ids":[50041,50039],
 *   "captain_hero_id":50041,"allocation_mode":"MANUAL","source":"CUSTOM",
 *   "template_key":null,"built_in":false,
 *   "soldiers":{"infantry":20000,"lancer":1549,"marksman":28451},
 *   "ratio":{"infantry":40,"lancer":30,"marksman":30},"total":50000,
 *   "version":3,"updated_at":1792863932863}]}
 * </pre>
 *
 * <p><b>v2 新字段（契约 §3.3，全部向后兼容）</b>：
 * <ul>
 *   <li>{@link #captainHeroId}：队长（必须 ∈ {@link #heroIds}；缺省=heroIds[0]）；</li>
 *   <li>{@link #allocationMode}：MANUAL | PERCENTAGE（缺省由 {@link #hasExplicitSoldiers()} 推断）；</li>
 *   <li>{@link #source}：CUSTOM | GAME（GAME 时 {@link #id} <b>&lt;0</b>，只读镜像）；</li>
 *   <li>{@link #templateKey}/{@link #builtIn}：内置模板标识（网页版 {@code builtIn = templateKey!=null}）；</li>
 *   <li>{@link #version}/{@link #updatedAt}：并发保护与更新时间；</li>
 *   <li>{@link #teamSize}：上阵英雄上限（服务端固定 3，网页版 R1）。</li>
 * </ul>
 * 旧 App 只写 {@code id/name/hero_ids/soldiers/ratio/total} → 全部缺省推导，行为不变。
 *
 * <p>该类是纯数据对象（无 Android 依赖），服务端各队列任务与 impl-bear（巨熊/巨兽/守护盟友）共用。
 */
public final class FormationSpec {

    /** 每编队最多上阵英雄数（网页版 R1：盾/矛/弓各 1）。 */
    public static final int TEAM_SIZE = 3;

    /** 来源：自定义/内置（id>0）。 */
    public static final String SOURCE_CUSTOM = "CUSTOM";
    /** 来源：游戏内编队只读镜像（id=-slotId）。 */
    public static final String SOURCE_GAME = "GAME";

    /** 兵量分配方式：手动数量。 */
    public static final String MODE_MANUAL = "MANUAL";
    /** 兵量分配方式：比例（按容量分配）。 */
    public static final String MODE_PERCENTAGE = "PERCENTAGE";

    /** 编队 id（App 功能页生成；>0=CUSTOM/内置；<0=游戏编队只读镜像 -slotId；0=非法/未选）。 */
    public long id;
    /** 展示名（日志/状态行用）。 */
    public String name = "";
    /** 英雄 id 列表（顺序=上阵顺序；空=非法编队）。<b>不去重</b>：重复由 Resolver 校验并 SKIP。 */
    public List<Long> heroIds = new ArrayList<>();
    /** 队长 id（v2；必须 ∈ heroIds；null/非法 → {@link #effectiveCaptain()} 回落 heroIds[0]）。 */
    public Long captainHeroId;
    /** 兵量分配方式（v2：MANUAL/PERCENTAGE；null=未显式，按 {@link #hasExplicitSoldiers()} 推断）。 */
    public String allocationMode;
    /** 来源（v2：CUSTOM/GAME；id<0 恒为 GAME）。 */
    public String source = SOURCE_CUSTOM;
    /** 内置模板键（ONE_INFANTRY/RANDOM_HERO_ONE_SOLDIER/GATHER_WOOD/.../BEHEMOTH/WILD；可空）。 */
    public String templateKey;
    /** 是否内置（v2；缺省 = templateKey 非空，与网页版 D1 一致）。 */
    public boolean builtIn;
    /** 编队版本（v2；编辑自增，并发保护；缺省 1）。 */
    public long version = 1L;
    /** 更新时间（v2；毫秒；缺省 0）。 */
    public long updatedAt;
    /** 上阵英雄上限（服务端固定 {@link #TEAM_SIZE}，不随库 JSON 变化）。 */
    public int teamSize = TEAM_SIZE;

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
    /**
     * 库 JSON 是否显式带了 {@code ratio}（区分"默认 40:30:30"与"用户真的配了比例"）。
     * 服务端据此决定 PERCENTAGE 模式是否按容量重算（旧 App 未配比例时不擅自按比例发兵）。
     */
    public boolean ratioExplicit;

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

    /**
     * 是否可用于 ROLE 出征绑定：{@code id>0} 且至少 1 个英雄。
     *
     * <p>GAME 编队（id&lt;0）恒为 {@code false}：游戏编队只读，ROLE 绑定必须用 App 生成的副本
     * （契约 §3.3 / 网页版 R6）。英雄数/职业校验在 {@link FormationResolver} 做（保留可读 SKIP 原因，
     * 不在解析期静默修数据）。
     */
    public boolean usable() {
        return id > 0 && heroIds != null && !heroIds.isEmpty();
    }

    /** 是否显式配置了兵量（soldiers 非全 0）。 */
    public boolean hasExplicitSoldiers() {
        return infantry > 0 || lancer > 0 || marksman > 0;
    }

    /** 是否游戏内编队只读镜像（id<0 或 source=GAME）。 */
    public boolean isGame() {
        return id < 0 || SOURCE_GAME.equalsIgnoreCase(source == null ? "" : source.trim());
    }

    /** 是否内置编队（{@link #builtIn}；与网页版 D1：templateKey 非空也算内置）。 */
    public boolean isBuiltIn() {
        return builtIn || (templateKey != null && !templateKey.trim().isEmpty());
    }

    /**
     * 生效队长：{@link #captainHeroId} 合法（∈ heroIds）时返回之；否则回落 heroIds[0]；
     * 无英雄返回 null（网页版 R2：不合法时展示用 heroIds[0] 兜底）。
     */
    public Long effectiveCaptain() {
        if (heroIds == null || heroIds.isEmpty()) return null;
        if (captainHeroId != null && captainHeroId > 0L && heroIds.contains(captainHeroId)) {
            return captainHeroId;
        }
        return heroIds.get(0);
    }

    /** 生效分配方式：显式 MANUAL/PERCENTAGE 优先；缺省 = 有显式兵量?MANUAL:PERCENTAGE。 */
    public String effectiveAllocationMode() {
        String mode = allocationMode == null ? "" : allocationMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (MODE_MANUAL.equals(mode) || MODE_PERCENTAGE.equals(mode)) return mode;
        return hasExplicitSoldiers() ? MODE_MANUAL : MODE_PERCENTAGE;
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
                + ", captain=" + captainHeroId
                + ", mode=" + allocationMode + ", source=" + source
                + ", template=" + templateKey + ", builtIn=" + builtIn
                + ", version=" + version + ", updatedAt=" + updatedAt
                + ", soldiers=" + infantry + "/" + lancer + "/" + marksman
                + ", ratio=" + ratio + ", total=" + total + "}";
    }
}
