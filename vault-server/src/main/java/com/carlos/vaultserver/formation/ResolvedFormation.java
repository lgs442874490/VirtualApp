package com.carlos.vaultserver.formation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的出征编队（批次10）：{@link FormationResolver} 的输出，{@link #toFormationPayload()}
 * 直接作为 {@code req_world_march} 的 {@code formation} 参数。
 *
 * <p>参考网页版 {@code modules/daily/model/DailyResolvedFormation}（heroPairs/soldierPairs）
 * 与 {@code DailyFormationSource}（来源枚举）。三重来源：
 * <ul>
 *   <li>{@link #SRC_ROLE}：App 功能页保存的角色编队（{@link FormationSpec} → 英雄+兵量）；</li>
 *   <li>{@link #SRC_AUTO}：自动编队（按库存/比例分配；AUTOMATIC 模式）；</li>
 *   <li>{@link #SRC_FALLBACK}：旧行为回退（无编队参数时 = 既有 {@code Exec.formation} 结果）；</li>
 * </ul>
 *
 * <p>payload 形状与既有服务端任务完全一致（{@code Exec.formationWithHeroes} 同款）：
 * <pre>{"hero_id":[{"k":50041,"v":1},...],"soldier":[{"k":11000,"v":20000},...]}</pre>
 */
public final class ResolvedFormation {

    /** 来源：角色编队（App 功能页保存）。 */
    public static final String SRC_ROLE = "ROLE";
    /** 来源：自动编队（比例+库存分配）。 */
    public static final String SRC_AUTO = "AUTO";
    /** 来源：旧行为回退（无编队参数时 = 既有 Exec.formation 结果；零行为变化）。 */
    public static final String SRC_FALLBACK = "FALLBACK";

    /** 编队 id（AUTO/FALLBACK=0）。 */
    public long formationId;
    /** 展示名（日志/状态行用）。 */
    public String label = "";
    /** 英雄 id 列表（顺序=上阵顺序）。 */
    public List<Long> heroIds = new ArrayList<>();
    /** 盾兵兵力。 */
    public long infantry;
    /** 矛兵兵力。 */
    public long lancer;
    /** 弓兵兵力。 */
    public long marksman;
    /** 来源：{@link #SRC_ROLE}/{@link #SRC_AUTO}/{@link #SRC_FALLBACK}。 */
    public String source = SRC_AUTO;

    /** {@code hero_id} 参数对（[{k:位次1..n, v:英雄id}]，实证与网页版一致）。 */
    public List<Map<String, Object>> heroPairs = new ArrayList<>();
    /** {@code soldier} 参数对（[{k:最高档兵种id, v:数量}]，c2s 请求侧 pair_int_int 语义）。 */
    public List<Map<String, Object>> soldierPairs = new ArrayList<>();

    /** FALLBACK 专用：直接回退既有 payload（不重建，保证旧行为逐字节一致）。 */
    private Map<String, Object> legacyPayload;

    /** ROLE/AUTO 结果工厂。 */
    public static ResolvedFormation of(String source, long formationId, String label,
                                       List<Long> heroIds, long infantry, long lancer, long marksman,
                                       List<Map<String, Object>> heroPairs,
                                       List<Map<String, Object>> soldierPairs) {
        ResolvedFormation f = new ResolvedFormation();
        f.source = source == null ? SRC_AUTO : source;
        f.formationId = formationId;
        f.label = label == null ? "" : label;
        f.heroIds = heroIds == null ? new ArrayList<>() : new ArrayList<>(heroIds);
        f.infantry = Math.max(0L, infantry);
        f.lancer = Math.max(0L, lancer);
        f.marksman = Math.max(0L, marksman);
        f.heroPairs = heroPairs == null ? new ArrayList<>() : new ArrayList<>(heroPairs);
        f.soldierPairs = soldierPairs == null ? new ArrayList<>() : new ArrayList<>(soldierPairs);
        return f;
    }

    /** 旧行为回退（无编队参数时）：payload 原样透传；payload=null 表示"无英雄可派"（与旧行为一致）。 */
    public static ResolvedFormation fallback(Map<String, Object> legacyPayload) {
        ResolvedFormation f = new ResolvedFormation();
        f.source = SRC_FALLBACK;
        f.label = "旧编队（无编队参数）";
        f.legacyPayload = legacyPayload;
        return f;
    }

    /** 三兵种合计。 */
    public long totalSoldiers() {
        return infantry + lancer + marksman;
    }

    /** 是否有可发出征（英雄或兵种至少一项）。 */
    public boolean usable() {
        if (legacyPayload != null) return !legacyPayload.isEmpty();
        return !heroPairs.isEmpty() && !soldierPairs.isEmpty();
    }

    /**
     * 转 {@code req_world_march} 的 formation 参数；无可用内容返回 null（调用方按"无英雄可派"SKIP）。
     * FALLBACK 返回旧 payload 本体（保证与既有行为逐字节一致）。
     */
    public Map<String, Object> toFormationPayload() {
        if (legacyPayload != null) return legacyPayload;
        if (heroPairs.isEmpty()) return null;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", heroPairs);
        f.put("soldier", soldierPairs);
        return f;
    }

    /** 日志/状态行文案：`来源=ROLE#1 英雄=[50041,50039] 兵种=盾20000/矛1549/弓28451`。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("来源=").append(source);
        if (formationId > 0) sb.append('#').append(formationId);
        if (label != null && !label.isEmpty()) sb.append('(').append(label).append(')');
        sb.append(" 英雄=").append(heroIds == null ? List.of() : heroIds);
        sb.append(" 兵种=盾").append(infantry).append("/矛").append(lancer).append("/弓").append(marksman);
        if (SRC_FALLBACK.equals(source)) sb.append("（旧行为）");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ResolvedFormation{" + describe() + "}";
    }
}
