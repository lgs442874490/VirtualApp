package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.MarchCapacityCalculator;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一编队解析器（批次10，服务端核心）：把「功能页选好的编队」或「自动编队」解析成
 * {@code req_world_march} 的 formation 参数，供采集/情报/狩猎/联盟矿（以及 impl-bear 的
 * 巨熊/巨兽/守护盟友）统一使用。
 *
 * <p><b>1:1 参考网页版</b>：
 * <ul>
 *   <li>{@code modules/daily/config/DailyFormationMode}：AUTOMATIC | ROLE（NORMAL/CUSTOM→ROLE）；</li>
 *   <li>{@code modules/daily/config/DailyFormationConfigSupport}：formationMode/formationIds、
 *       模式回落规则（有 formationId→ROLE，否则 AUTOMATIC）；</li>
 *   <li>{@code service/DailyMarchFormationResolver}：AUTOMATIC 走 strongest/idle 英雄 + 比例容量；
 *       ROLE 取保存编队 heroIds + 兵量；不可用返回 WAITING/空（不静默换编队）；</li>
 *   <li>{@code service/DailyRoleFormationCalculator} / {@code DailyRoleFormationLimits}：容量/比例计算；</li>
 *   <li>{@code config/DailyBearFormationConfig}：巨熊 1:1:1 比例（由调用方传入 ratio，本类不写死）。</li>
 * </ul>
 *
 * <p><b>参数 schema（roleParams，App 写入；无参数=旧行为）</b>：
 * <ul>
 *   <li>全局库：{@code formation_library}（字符串 JSON，见 {@link FormationLibrary}）；
 *       优先从任务自身 params 读，其次从 {@code tc.params("formation")} 读；</li>
 *   <li>每任务：{@code formation_mode}("AUTOMATIC"|"ROLE"|"NORMAL"|"CUSTOM")、{@code formation_id}、
 *       {@code soldiers:{infantry,lancer,marksman}}（覆盖编队兵量）、{@code ratio:{infantry,lancer,marksman}}、
 *       {@code total}、{@code squad_index}（仅 FALLBACK 旧行为用）。</li>
 * </ul>
 *
 * <p><b>SKIP 语义（宁可不发）</b>：库缺失/编队 id 不存在/英雄不存在/库存不足/比例=0/容量未知
 * 一律返回 {@code null}（{@link ResolveOutcome#reason} 给可读原因），**绝不静默回退旧编队**；
 * 唯一例外=**无任何编队参数**（{@link #isConfigured} false）时返回 FALLBACK（旧行为，零变化）。
 */
public final class FormationResolver {

    public static final String MODE_AUTOMATIC = "AUTOMATIC";
    public static final String MODE_ROLE = "ROLE";

    /** 自动编队最多上阵英雄数（网页版最强/空闲编队 ≤5）。 */
    static final int MAX_AUTO_HEROES = 5;
    /** 单队兵力估算上限（与既有 Exec.formationWithHeroes 的 50000 上限同口径）。 */
    static final long MAX_SOLDIERS_PER_MARCH = 50_000L;
    /** 默认比例 40:30:30（网页版 DailyGatherConfig/DailyHuntConfig 默认）。 */
    public static final long[] DEFAULT_RATIO = new long[]{40L, 30L, 30L};
    /** 巨熊默认比例 1:1:1（供 impl-bear 直接引用；DailyBearFormationConfig 默认）。 */
    public static final long[] BEAR_RATIO = new long[]{1L, 1L, 1L};

    private FormationResolver() {
    }

    /** 解析结果：formation 非空=READY；空=SKIP（reason 可读）。 */
    public static final class ResolveOutcome {
        /** 可用编队；null=SKIP。 */
        public final ResolvedFormation formation;
        /** SKIP 原因（READY 时有值也仅作说明）。 */
        public final String reason;

        private ResolveOutcome(ResolvedFormation formation, String reason) {
            this.formation = formation;
            this.reason = reason == null ? "" : reason;
        }

        public boolean ready() {
            return formation != null;
        }

        static ResolveOutcome ready(ResolvedFormation f, String reason) {
            return new ResolveOutcome(f, reason);
        }

        static ResolveOutcome skip(String reason) {
            return new ResolveOutcome(null, reason);
        }
    }

    // ------------------------------------------------------------------
    // 入口（冻结契约）
    // ------------------------------------------------------------------

    /**
     * 解析本任务编队（冻结契约入口；失败返回 null，调用方 SKIP）。
     * {@code lib} 可以是 {@link #libraryFor(TaskContext, String)} 的返回值（可能为空库）。
     */
    public static ResolvedFormation resolve(WjdrEngine engine, FormationLibrary lib,
                                            Map<String, Object> fnParams) {
        return resolveOutcome(engine, lib, fnParams).formation;
    }

    /** JSONObject 重载（App 同步通道/TaskContext.params 直接可用）。 */
    public static ResolvedFormation resolve(WjdrEngine engine, FormationLibrary lib, JSONObject fnParams) {
        return resolveOutcome(engine, lib, toMap(fnParams)).formation;
    }

    /** JSONObject 重载（TaskContext.params 直接可用）。 */
    public static ResolveOutcome resolveOutcome(WjdrEngine engine, FormationLibrary lib, JSONObject fnParams) {
        return resolveOutcome(engine, lib, toMap(fnParams));
    }

    /** 带原因的解析（服务端任务 SKIP 文案用；契约外的增量 API）。 */
    public static ResolveOutcome resolveOutcome(WjdrEngine engine, FormationLibrary lib,
                                                Map<String, Object> fnParams) {
        Map<String, Object> p = fnParams == null ? Map.of() : fnParams;
        if (engine == null) return ResolveOutcome.skip("engine 为空");
        // 无任何编队参数 = 旧行为（零变化）；返回 FALLBACK（调用方可直接用 payload）
        if (!isConfigured(p)) {
            Map<String, Object> legacy = Exec.formation(engine, squadIndexOf(p, 1));
            return ResolveOutcome.ready(ResolvedFormation.fallback(legacy), "无编队参数（旧行为回退）");
        }
        try {
            String mode = modeOf(p);
            if (MODE_ROLE.equals(mode)) {
                return resolveRole(engine, lib, p);
            }
            return autoOutcome(engine, ratioOf(p, FormationSpec.defaultRatio()), totalOf(p));
        } catch (Throwable t) {
            return ResolveOutcome.skip("编队解析异常：" + brief(t.getMessage()));
        }
    }

    /**
     * 自动编队（冻结契约）：按可用英雄（强→弱，≤5）+ 兵种库存/比例分配。
     *
     * @param ratio 比例权重（键 infantry/lancer/marksman；null/空=40:30:30）
     * @param total 目标总兵力（≤0=按库存上限，单队再夹 50000）
     * @return 编队；英雄/库存缺失返回 null（不静默回退）
     */
    public static ResolvedFormation auto(WjdrEngine engine, Map<String, Long> ratio, long total) {
        return autoOutcome(engine, ratio, total).formation;
    }

    /** 自动编队（带原因）。 */
    public static ResolveOutcome autoOutcome(WjdrEngine engine, Map<String, Long> ratio, long total) {
        if (engine == null) return ResolveOutcome.skip("engine 为空");
        List<Long> heroIds = strongestHeroes(engine,
                Math.min(MAX_AUTO_HEROES, HeroAvailability.autoHeroLimit(engine)));
        if (heroIds.isEmpty()) {
            return ResolveOutcome.skip("英雄目录未同步（notify_hero_init 缺失或无英雄）");
        }
        long[] stock = FormationCatalog.soldierStock(engine);
        long stockSum = stock[0] + stock[1] + stock[2];
        if (stockSum <= 0) {
            return ResolveOutcome.skip("兵种库存未同步（notify_soldier_init 缺失）");
        }
        long target = total > 0 ? total : Math.min(stockSum, MAX_SOLDIERS_PER_MARCH);
        // 406 修复：按“本队英雄容量”精确夹取总兵力（网页版 MarchCapacityState 公式）
        long capacity = MarchCapacityCalculator.capacityFor(engine, heroIds);
        if (capacity > 0L) target = Math.min(target, capacity);
        long[] weights = ratioArray(ratio, DEFAULT_RATIO);
        long[] soldiers = allocate(target, weights, stock);
        if (soldiers[0] + soldiers[1] + soldiers[2] <= 0) {
            return ResolveOutcome.skip("兵种库存不足（盾" + stock[0] + "/矛" + stock[1] + "/弓" + stock[2] + "）");
        }
        return ResolveOutcome.ready(build(engine, ResolvedFormation.SRC_AUTO, 0L, "自动编队",
                heroIds, soldiers), "自动编队（比例 " + weights[0] + ":" + weights[1] + ":" + weights[2] + "）");
    }

    /**
     * 旧行为回退：无编队参数时保持既有 {@code Exec.formation(engine, squadIndex)} 结果，
     * source=FALLBACK（payload 原样透传，零行为变化）。
     */
    public static ResolvedFormation fallback(WjdrEngine engine, int squadIndex) {
        if (engine == null) return ResolvedFormation.fallback(null);
        return ResolvedFormation.fallback(Exec.formation(engine, squadIndex <= 0 ? 1 : squadIndex));
    }

    // ------------------------------------------------------------------
    // ROLE 解析
    // ------------------------------------------------------------------

    private static ResolveOutcome resolveRole(WjdrEngine engine, FormationLibrary lib,
                                              Map<String, Object> p) {
        long formationId = formationIdOf(p);
        if (formationId <= 0) {
            return ResolveOutcome.skip("formation_mode=ROLE 但未选择 formation_id（请到功能页选编队）");
        }
        if (lib == null || lib.isEmpty()) {
            return ResolveOutcome.skip("编队库未同步（roleParams formation_library 缺失/为空）");
        }
        FormationSpec spec = lib.byId(formationId);
        if (spec == null || !spec.usable()) {
            return ResolveOutcome.skip("编队 #" + formationId + " 不存在或没有英雄（编队库 " + lib.ids() + "）");
        }
        // 英雄校验：必须存在于角色英雄目录（目录缺失 → 宁可不发，不猜测上阵）
        Set<Long> known = new LinkedHashSet<>(FormationCatalog.heroIds(engine));
        if (known.isEmpty()) {
            return ResolveOutcome.skip("英雄目录未同步（notify_hero_init 缺失），无法校验编队英雄");
        }
        List<Long> missing = new ArrayList<>();
        for (Long h : spec.heroIds) {
            if (h == null || h <= 0 || !known.contains(h)) missing.add(h);
        }
        if (!missing.isEmpty()) {
            return ResolveOutcome.skip("编队 #" + formationId + " 英雄不在角色英雄列表：" + missing
                    + "（可能已下阵/未同步）");
        }
        // 兵种库存
        long[] stock = FormationCatalog.soldierStock(engine);
        long stockSum = stock[0] + stock[1] + stock[2];
        if (stockSum <= 0) {
            return ResolveOutcome.skip("兵种库存未同步（notify_soldier_init 缺失）");
        }
        // ---------------------------------------------------------------
        // ROLE 兵量优先级（批次11 修复：必须用编队库 spec 的兵量，不能落回自动分配）
        //   ① 功能级 soldiers 字段覆盖（>0 生效，按兵种逐项覆盖）
        //   ② 编队库 FormationSpec 三兵种（infantry/lancer/marksman，任一 >0）
        //   ③ spec.total>0 或显式 ratio → 按 total/ratio + 库存分配（保留 AUTOMATIC 分配语义）
        //   ④ 库无兵量且无 total/ratio → SKIP + 可读原因（不静默缩小/不静默自动分配）
        // 任何显式/库兵量超库存 → SKIP（不静默削减）。
        // ---------------------------------------------------------------
        long[] override = soldiersOf(p);
        long[] merged = new long[]{
                spec.infantry > 0 ? spec.infantry : 0L,
                spec.lancer > 0 ? spec.lancer : 0L,
                spec.marksman > 0 ? spec.marksman : 0L};
        boolean overrideAny = false;
        if (override != null) {
            for (int i = 0; i < 3; i++) {
                if (override[i] > 0) {
                    merged[i] = override[i];   // 功能级覆盖：只覆盖 >0 的兵种，其余沿用库兵量
                    overrideAny = true;
                }
            }
        }
        boolean libraryAny = merged[0] > 0 || merged[1] > 0 || merged[2] > 0;
        long capacity = MarchCapacityCalculator.capacityFor(engine, new ArrayList<>(spec.heroIds));
        long[] soldiers;
        String sourceNote;
        if (libraryAny) {
            soldiers = merged;
            String shortLine = shortage(soldiers, stock);
            if (shortLine != null) {
                return ResolveOutcome.skip("编队 #" + formationId + " 兵量超出库存：" + shortLine
                        + "（库存 盾" + stock[0] + "/矛" + stock[1] + "/弓" + stock[2] + "）");
            }
            if (capacity > 0L) {
                long sum = soldiers[0] + soldiers[1] + soldiers[2];
                if (sum > capacity) {
                    return ResolveOutcome.skip("编队 #" + formationId + " 兵量(" + sum
                            + ")超出出征容量(" + capacity + ")，请下调兵量或增加英雄");
                }
            }
            sourceNote = overrideAny ? "功能级 soldiers 覆盖" : "编队库兵量";
        } else {
            long total = totalOf(p);
            if (total <= 0) total = spec.total;
            boolean explicitRatio = hasExplicitRatio(p);
            if (total <= 0 && !explicitRatio) {
                return ResolveOutcome.skip("编队 #" + formationId
                        + " 未配置兵量（soldiers 全 0）且未设置 total/ratio（请在功能页编队里配置兵量或比例）");
            }
            // ③ total>0 或显式 ratio：按 total/ratio + 库存分配（网页版 AUTOMATIC 分配语义）
            if (total <= 0) total = Math.min(stockSum, MAX_SOLDIERS_PER_MARCH);
            if (capacity > 0L) total = Math.min(total, capacity);   // 406 修复：按英雄容量夹取
            long[] weights = ratioArray(ratioOf(p, spec.ratio), DEFAULT_RATIO);
            soldiers = allocate(total, weights, stock);
            if (soldiers[0] + soldiers[1] + soldiers[2] <= 0) {
                return ResolveOutcome.skip("编队 #" + formationId + " 兵量分配为 0（比例/库存不可用）");
            }
            sourceNote = "total=" + total + " 按比例分配";
        }
        ResolvedFormation rf = build(engine, ResolvedFormation.SRC_ROLE, spec.id,
                spec.name, new ArrayList<>(spec.heroIds), soldiers);
        return ResolveOutcome.ready(rf, "角色编队 #" + spec.id
                + (spec.name == null || spec.name.isEmpty() ? "" : "(" + spec.name + ")")
                + " · " + sourceNote);
    }

    /** 是否显式给出比例（params.ratio/soldier_ratio 非空 Map；spec 默认 40:30:30 不算显式）。 */
    static boolean hasExplicitRatio(Map<String, Object> fnParams) {
        Object v = first(fnParams, "ratio", "soldier_ratio");
        if (v instanceof JSONObject) v = toMap((JSONObject) v);
        if (!(v instanceof Map)) return false;
        return !((Map<?, ?>) v).isEmpty();
    }

    private static String shortage(long[] want, long[] stock) {
        StringBuilder sb = new StringBuilder();
        String[] names = new String[]{"盾", "矛", "弓"};
        for (int i = 0; i < 3; i++) {
            if (want[i] > stock[i]) {
                if (sb.length() > 0) sb.append('/');
                sb.append(names[i]).append("要").append(want[i]).append("有").append(stock[i]);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------
    // 构造 payload
    // ------------------------------------------------------------------

    private static ResolvedFormation build(WjdrEngine engine, String source, long formationId,
                                           String label, List<Long> heroIds, long[] soldiers) {
        List<Map<String, Object>> heroPairs = Exec.heroPairs(heroIds);
        long[] bestIds = FormationCatalog.bestSoldierIds(engine);
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (soldiers[i] > 0) {
                long sid = bestIds[i] > 0 ? bestIds[i] : defaultSoldierId(i);
                soldierPairs.add(Exec.soldierPair(sid, soldiers[i]));
            }
        }
        return ResolvedFormation.of(source, formationId, label, heroIds,
                soldiers[0], soldiers[1], soldiers[2], heroPairs, soldierPairs);
    }

    private static long defaultSoldierId(int line) {
        return switch (line) {
            case 0 -> 11000L;
            case 1 -> 21000L;
            default -> 31000L;
        };
    }

    /** 自动编队英雄候选：按战力/等级/星级/ id 降序，最多 {@code max} 个（≤5）。 */
    public static List<Long> strongestHeroes(WjdrEngine engine, int max) {
        int cap = Math.max(1, Math.min(MAX_AUTO_HEROES, max));
        List<Long> out = new ArrayList<>();
        java.util.Set<Long> busy = FormationCatalog.busyHeroIds(engine);
        for (FormationCatalog.Hero h : FormationCatalog.heroes(engine)) {
            // 301 拉黑窗内英雄跳过（换下一批候选；全部拉黑由调用方 SKIP）
            if (HeroAvailability.isBlocked(engine, h.id)) continue;
            // 403 修复：已在途行军中的英雄跳过（多队列并发时不重复选人）
            if (busy.contains(h.id)) continue;
            if (out.size() >= cap) break;
            out.add(h.id);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 比例分配（纯函数；库存上限 + 总量夹取）
    // ------------------------------------------------------------------

    /**
     * 按比例把 {@code total} 分配到三兵种，绝不超库存。
     *
     * <p>算法：先按权重 floor 分配并夹库存；余量按兵种顺序（盾→矛→弓）轮转补足到
     * {@code min(total, 库存合计)}。返回长度 3 的数组；库存全 0/total<=0 返回全 0。
     *
     * @param total   目标总兵力（会被夹到库存合计）
     * @param weights 三兵种权重（null/全 0=40:30:30）
     * @param stock   三兵种库存上限
     */
    public static long[] allocate(long total, long[] weights, long[] stock) {
        long[] out = new long[3];
        if (total <= 0 || stock == null || stock.length < 3) return out;
        long[] cap = new long[]{Math.max(0L, stock[0]), Math.max(0L, stock[1]), Math.max(0L, stock[2])};
        long capSum = cap[0] + cap[1] + cap[2];
        if (capSum <= 0) return out;
        long target = Math.min(total, capSum);
        long[] w = new long[3];
        long wsum = 0;
        for (int i = 0; i < 3; i++) {
            w[i] = weights != null && i < weights.length ? Math.max(0L, weights[i]) : 0L;
            wsum += w[i];
        }
        if (wsum <= 0) {
            w = DEFAULT_RATIO.clone();
            wsum = w[0] + w[1] + w[2];
        }
        long assigned = 0;
        for (int i = 0; i < 3; i++) {
            long share = target * w[i] / wsum;                 // floor；target*w 上界 ~50k*100 无溢出
            out[i] = Math.min(cap[i], share);
            assigned += out[i];
        }
        long remainder = target - assigned;
        int idx = 0;
        int guard = 0;
        while (remainder > 0 && guard++ < 100_000) {
            if (out[idx % 3] < cap[idx % 3]) {
                out[idx % 3]++;
                remainder--;
            }
            idx++;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 参数解析（纯函数，容错 Map/JSONObject/字符串数字）
    // ------------------------------------------------------------------

    /** 是否配置了「新编队系统」参数（无参数=旧行为）。 */
    public static boolean isConfigured(Map<String, Object> fnParams) {
        if (fnParams == null || fnParams.isEmpty()) return false;
        for (String k : new String[]{"formation_mode", "formationMode", "mode",
                "formation_id", "formationId", "soldiers", "ratio", "total"}) {
            Object v = fnParams.get(k);
            if (v == null) continue;
            if (v instanceof String && ((String) v).trim().isEmpty()) continue;
            if (v instanceof Map && ((Map<?, ?>) v).isEmpty()) continue;
            return true;
        }
        return false;
    }

    /** JSONObject 版 {@link #isConfigured(Map)}。 */
    public static boolean isConfigured(JSONObject fnParams) {
        return isConfigured(toMap(fnParams));
    }

    /** 编队模式：AUTOMATIC|ROLE；NORMAL/CUSTOM→ROLE；有 formation_id 且未写 mode→ROLE，否则 AUTOMATIC。 */
    public static String modeOf(Map<String, Object> fnParams) {
        String raw = str(fnParams, "formation_mode", "formationMode");
        if (raw.isEmpty()) {
            return formationIdOf(fnParams) > 0 ? MODE_ROLE : MODE_AUTOMATIC;
        }
        String up = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("NORMAL".equals(up) || "CUSTOM".equals(up)) return MODE_ROLE;
        if (MODE_ROLE.equals(up)) return MODE_ROLE;
        if (MODE_AUTOMATIC.equals(up)) return MODE_AUTOMATIC;
        return formationIdOf(fnParams) > 0 ? MODE_ROLE : MODE_AUTOMATIC;
    }

    /** 选中的编队 id（formation_id/formationId；无=0）。 */
    public static long formationIdOf(Map<String, Object> fnParams) {
        Object v = first(fnParams, "formation_id", "formationId");
        return num(v, 0L);
    }

    /** total 参数（无=0）。 */
    public static long totalOf(Map<String, Object> fnParams) {
        return num(first(fnParams, "total", "troop_total"), 0L);
    }

    /** 兵量覆盖（params.soldiers；兼容 shield/spear/bow 别名）；无配置返回 null。 */
    public static long[] soldiersOf(Map<String, Object> fnParams) {
        Object v = first(fnParams, "soldiers");
        if (v instanceof JSONObject) v = toMap((JSONObject) v);
        if (!(v instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) v;
        long inf = num(pick(m, "infantry", "shield"), 0L);
        long lan = num(pick(m, "lancer", "spear"), 0L);
        long mar = num(pick(m, "marksman", "bow"), 0L);
        return new long[]{Math.max(0L, inf), Math.max(0L, lan), Math.max(0L, mar)};
    }

    /** 比例（params.ratio；键 infantry/lancer/marksman，兼容 shield/spear/bow）；无配置返回 def。 */
    public static Map<String, Long> ratioOf(Map<String, Object> fnParams, Map<String, Long> def) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (def != null) out.putAll(def);
        Object v = first(fnParams, "ratio", "soldier_ratio");
        if (v instanceof JSONObject) v = toMap((JSONObject) v);
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            putRatio(out, "infantry", pick(m, "infantry", "shield"));
            putRatio(out, "lancer", pick(m, "lancer", "spear"));
            putRatio(out, "marksman", pick(m, "marksman", "bow"));
        }
        if (out.get("infantry") == null) out.put("infantry", 40L);
        if (out.get("lancer") == null) out.put("lancer", 30L);
        if (out.get("marksman") == null) out.put("marksman", 30L);
        return out;
    }

    private static void putRatio(Map<String, Long> out, String key, Object raw) {
        long v = num(raw, -1L);
        if (v >= 0) out.put(key, v);
    }

    /** {@link #ratioOf} → long[3]（顺序 盾/矛/弓；全 0 用默认）。 */
    public static long[] ratioArray(Map<String, Long> ratio, long[] def) {
        long[] out = new long[3];
        if (ratio != null) {
            out[0] = val(ratio.get("infantry"));
            out[1] = val(ratio.get("lancer"));
            out[2] = val(ratio.get("marksman"));
        }
        if (out[0] + out[1] + out[2] <= 0) {
            out = def == null || def.length < 3 ? DEFAULT_RATIO.clone() : def.clone();
        }
        return out;
    }

    /** 旧行为回退用的 squad_index（无=默认）。 */
    public static int squadIndexOf(Map<String, Object> fnParams, int def) {
        long v = num(first(fnParams, "squad_index", "squadIndex"), def);
        return v > 0 && v <= 6 ? (int) v : def;
    }

    /**
     * 本任务可用编队库：优先 fnParams 自身 {@code formation_library}，
     * 其次全局 {@code tc.params("formation").formation_library}。
     */
    public static FormationLibrary libraryFor(TaskContext tc, String fnId) {
        if (tc == null) return FormationLibrary.empty();
        try {
            String raw = libraryJson(tc.params(fnId));
            if (raw.isEmpty()) raw = libraryJson(tc.params("formation"));
            if (raw.isEmpty()) raw = libraryJson(tc.params("daily"));
            return FormationLibrary.parse(raw);
        } catch (Throwable t) {
            return FormationLibrary.empty();
        }
    }

    private static String libraryJson(JSONObject p) {
        if (p == null) return "";
        String raw = p.optString("formation_library", "");
        if (raw.isEmpty()) raw = p.optString("formationLibrary", "");
        if (raw.isEmpty()) {
            JSONObject lib = p.optJSONObject("formation_library");
            if (lib != null) raw = lib.toString();
        }
        return raw == null ? "" : raw.trim();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static Object first(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) return m.get(k);
        }
        return null;
    }

    private static Object pick(Map<?, ?> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) return m.get(k);
        }
        return null;
    }

    private static String str(Map<String, Object> m, String... keys) {
        Object v = first(m, keys);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static long num(Object v, long def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static long val(Long v) {
        return v == null ? 0L : Math.max(0L, v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JSONObject j) {
        if (j == null) return new LinkedHashMap<>();
        try {
            return j.toMap();
        } catch (Throwable t) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : j.keySet()) m.put(k, j.opt(k));
            return m;
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
