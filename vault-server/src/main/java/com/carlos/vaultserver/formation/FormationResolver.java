package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统一编队解析器（批次10/v2，服务端核心）：把「功能页选好的编队」或「自动编队」解析成
 * {@code req_world_march} 的 formation 参数，供采集/情报/狩猎/联盟矿（以及 impl-bear 的
 * 巨熊/巨兽/守护盟友）统一使用。
 *
 * <p><b>1:1 参考网页版/旧版（release 288）</b>：
 * <ul>
 *   <li>{@code DailyFormationMode}：AUTOMATIC | ROLE（NORMAL/CUSTOM→ROLE）；</li>
 *   <li>{@code DailyFormationConfigSupport}：formationMode/formationIds、模式回落规则（有 id→ROLE）；</li>
 *   <li>{@code DailyMarchFormationResolver}：AUTOMATIC 走空闲/最强英雄 + 容量夹取；ROLE 取保存编队，
 *       候选 {@code formation_ids[]} 逐个尝试，不可用返回 WAITING/SKIP（不静默换编队）；</li>
 *   <li>{@code DailyRoleFormationService.validated}（保存期校验 → 运行期兜底）：英雄 ≤3、无重复、
 *       职业唯一（盾/矛/弓各 1）、必须在角色英雄库；</li>
 *   <li>{@code DailyRoleFormationCalculator}：最大余数分配 {@code allocateLargestRemainders}、
 *       百分比分配 {@code allocatePercentages}、容量扩张 {@code expandedFixedTarget}、同比缩放 {@code scaleTotal}；</li>
 *   <li>{@code DailyRoleFormationService.fitFormation}：MANUAL=按容量扩张后按兵量权重重分配；
 *       PERCENTAGE=按容量×比例重算；容量未知 → 不夹取仅 WARN。</li>
 * </ul>
 *
 * <p><b>参数 schema（roleParams，App 写入；无参数=旧行为）</b>：
 * <ul>
 *   <li>全局库：{@code formation_library}（字符串 JSON，见 {@link FormationLibrary}）；</li>
 *   <li>每任务：{@code formation_mode}("AUTOMATIC"|"ROLE"|"NORMAL"|"CUSTOM")、
 *       {@code formation_id} / {@code formation_ids[]}（候选列表，按序回退）、
 *       {@code soldiers:{infantry,lancer,marksman}}（覆盖编队兵量）、
 *       {@code ratio:{infantry,lancer,marksman}}、{@code total}、
 *       {@code formation_fallback}（黑名单回退：AUTO|SKIP，默认 SKIP）、{@code squad_index}；</li>
 *   <li>多队列预留：{@code resolveOutcome(..., usedHeroes)} 传入本轮前序队列已用英雄（采集 v2 4 队列）。</li>
 * </ul>
 *
 * <p><b>SKIP 语义（宁可不发）</b>：库缺失/候选全部非法/英雄目录未同步/库存不足为 0/兵量分配为 0
 * 一律返回 {@code null}（{@link ResolveOutcome#reason} 给可读中文原因），<b>绝不静默回退旧编队</b>；
 * 唯一例外=**无任何编队参数**（{@link #isConfigured} false）时返回 FALLBACK（旧行为，零变化）。
 * v2 起「显式兵量超容量/超库存」不再 SKIP，而是按旧版 fitFormation 口径缩放后下发。
 */
public final class FormationResolver {

    public static final String MODE_AUTOMATIC = "AUTOMATIC";
    public static final String MODE_ROLE = "ROLE";

    /** 黑名单/候选不可用时回退自动编队（params {@code formation_fallback=AUTO}）。 */
    public static final String FALLBACK_AUTO = "AUTO";
    /** 黑名单/候选不可用时不发（默认）。 */
    public static final String FALLBACK_SKIP = "SKIP";

    /** 自动编队最多上阵英雄数（网页版最强/空闲编队 ≤5；v2 实际上限 3，见 {@link HeroAvailability}）。 */
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

    // ==================================================================
    // 入口（冻结契约；旧签名全部保留并委托新重载）
    // ==================================================================

    /** 解析本任务编队（冻结契约入口；失败返回 null，调用方 SKIP）。 */
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
        return resolveOutcome(engine, lib, toMap(fnParams), null);
    }

    /** JSONObject 重载 + 同轮已用英雄（多队列预留）。 */
    public static ResolveOutcome resolveOutcome(WjdrEngine engine, FormationLibrary lib,
                                                JSONObject fnParams, Collection<Long> usedHeroes) {
        return resolveOutcome(engine, lib, toMap(fnParams), usedHeroes);
    }

    /**
     * 带原因的解析（服务端任务 SKIP 文案用；契约外的增量 API）。
     *
     * @param usedHeroes 本轮前序队列已占用英雄（null=不预留）；AUTO 候选与 ROLE 候选都会排除
     */
    public static ResolveOutcome resolveOutcome(WjdrEngine engine, FormationLibrary lib,
                                                Map<String, Object> fnParams) {
        return resolveOutcome(engine, lib, fnParams, null);
    }

    /** 带原因 + 同轮已用英雄预留的解析（采集 v2 多队列调用）。 */
    public static ResolveOutcome resolveOutcome(WjdrEngine engine, FormationLibrary lib,
                                                Map<String, Object> fnParams,
                                                Collection<Long> usedHeroes) {
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
                return resolveRole(engine, lib, p, normalizeHeroes(usedHeroes));
            }
            return autoOutcome(engine, ratioOf(p, automaticDefaultRatio(lib)), totalOf(p), usedHeroes);
        } catch (Throwable t) {
            return ResolveOutcome.skip("编队解析异常：" + brief(t.getMessage()));
        }
    }

    /**
     * 自动编队（冻结契约）：按可用英雄（强→弱，≤3）+ 兵种库存/比例分配。
     *
     * @param ratio 比例权重（键 infantry/lancer/marksman；null/空=40:30:30）
     * @param total 目标总兵力（≤0=按库存上限，单队再夹 50000 与本队容量）
     * @return 编队；英雄/库存缺失返回 null（不静默回退）
     */
    public static ResolvedFormation auto(WjdrEngine engine, Map<String, Long> ratio, long total) {
        return autoOutcome(engine, ratio, total).formation;
    }

    /** 自动编队（带原因）。 */
    public static ResolveOutcome autoOutcome(WjdrEngine engine, Map<String, Long> ratio, long total) {
        return autoOutcome(engine, ratio, total, null);
    }

    /** 自动编队（带原因 + 同轮已用英雄预留；415/406 自适应在此消费）。 */
    public static ResolveOutcome autoOutcome(WjdrEngine engine, Map<String, Long> ratio, long total,
                                             Collection<Long> usedHeroes) {
        if (engine == null) return ResolveOutcome.skip("engine 为空");
        List<Long> heroIds = autoProfessionHeroes(engine, usedHeroes);
        if (heroIds.isEmpty()) {
            String why = "英雄目录未同步（notify_hero_init 缺失或无可用英雄）";
            if (usedHeroes != null && !usedHeroes.isEmpty()) {
                why = why + "；本轮前序队列已占用英雄 " + usedHeroes;
            }
            return ResolveOutcome.skip(why);
        }
        return autoOutcomeFor(engine, heroIds, ratio, total, "自动编队", "自动编队");
    }

    /**
     * 按职业指定偏好英雄的自动编队（情报出征；契约 §4.1.2/§4.1.3）。
     *
     * <p>与 {@link #autoOutcome} <b>完全同口径</b>（库存夹取、出征容量夹取、406 缩放、比例分配、
     * payload 构造共用 {@link #autoOutcomeFor}，不维护第二份实现）；唯一差别是英雄候选由
     * {@link #preferredProfessionHeroes} 给出：指定英雄不可用（不在城/301 拉黑/在途占用/职业不符）
     * → 同职业最强可用兜底。
     *
     * @param preferredByProfession 下标 1/2/3 = 盾/矛/弓职业的指定英雄 id（0=自动取该职业最强）
     * @param ratio                 兵种比例（null/全 0 → 40:30:30）
     * @param total                 目标总兵力（&lt;=0=按库存/容量自动）
     * @param usedHeroes            本轮前序队列已占用英雄（null=不预留）
     * @return READY/SKIP（reason 含「情报编队（盾/矛/弓=…）」与库存/容量/缩放说明）
     */
    public static ResolveOutcome autoWithPreferredHeroes(WjdrEngine engine, long[] preferredByProfession,
                                                         Map<String, Long> ratio, long total,
                                                         Collection<Long> usedHeroes) {
        if (engine == null) return ResolveOutcome.skip("engine 为空");
        List<Long> heroIds = preferredProfessionHeroes(engine, preferredByProfession, usedHeroes);
        if (heroIds.isEmpty()) {
            String why = "情报编队无可用英雄（英雄目录未同步或指定英雄均不可用）";
            if (usedHeroes != null && !usedHeroes.isEmpty()) {
                why = why + "；本轮前序队列已占用英雄 " + usedHeroes;
            }
            return ResolveOutcome.skip(why);
        }
        StringBuilder lead = new StringBuilder("情报编队（盾/矛/弓=");
        for (int i = 0; i < heroIds.size(); i++) {
            if (i > 0) lead.append('/');
            lead.append(heroIds.get(i));
        }
        lead.append('）');
        return autoOutcomeFor(engine, heroIds, ratio, total, "情报编队", lead.toString());
    }

    /**
     * AUTO 编队公共实现（{@link #autoOutcome} 与 {@link #autoWithPreferredHeroes} 共用）：
     * 库存校验 → 容量夹取 → 406 缩放 → 比例分配 → payload。调用方保证 {@code heroIds} 非空。
     */
    private static ResolveOutcome autoOutcomeFor(WjdrEngine engine, List<Long> heroIds,
                                                 Map<String, Long> ratio, long total,
                                                 String label, String reasonLead) {
        long[] stock = FormationCatalog.soldierStock(engine);
        long stockSum = stock[0] + stock[1] + stock[2];
        if (stockSum <= 0) {
            return ResolveOutcome.skip("兵种库存未同步（notify_soldier_init 缺失）");
        }
        StringBuilder notes = new StringBuilder();
        long target = total > 0 ? total : Math.min(stockSum, MAX_SOLDIERS_PER_MARCH);
        // 406 修复：按“本队英雄容量”精确夹取总兵力（网页版 MarchCapacityState 公式）
        long capacity = capacityForHeroes(engine, heroIds);
        if (capacity > 0L) {
            if (target > capacity) {
                notes.append("，按容量").append(capacity).append("夹取");
                target = capacity;
            }
        } else {
            capacityWarn("AUTO", 0L, target);
            notes.append("，出征容量未知未夹取");
        }
        // 406 拒绝后按 75/50/25% 逐级缩放（成功后由 HeroAvailability.markSuccess 复位）
        int scalePct = HeroAvailability.soldierScalePercent(engine);
        if (scalePct < 100) {
            target = Math.max(1L, applyPercent(target, scalePct));
            notes.append("，406 缩放至").append(scalePct).append('%');
        }
        long[] weights = ratioArray(ratio, DEFAULT_RATIO);
        long[] soldiers = allocate(target, weights, stock);
        if (soldiers[0] + soldiers[1] + soldiers[2] <= 0) {
            return ResolveOutcome.skip("兵种库存不足（盾" + stock[0] + "/矛" + stock[1] + "/弓" + stock[2] + "）");
        }
        int heroLimit = HeroAvailability.autoHeroLimit(engine);
        if (heroLimit < HeroAvailability.DEFAULT_AUTO_HEROES) {
            notes.append("，415 英雄上限下调至").append(heroLimit);
        }
        ResolvedFormation rf = build(engine, ResolvedFormation.SRC_AUTO, 0L, label,
                heroIds, soldiers);
        return ResolveOutcome.ready(rf, reasonLead + "（比例 " + weights[0] + ":" + weights[1] + ":"
                + weights[2] + notes + "）");
    }

    /**
     * 旧行为回退：无编队参数时保持既有 {@code Exec.formation(engine, squadIndex)} 结果，
     * source=FALLBACK（payload 原样透传，零行为变化）。
     */
    public static ResolvedFormation fallback(WjdrEngine engine, int squadIndex) {
        if (engine == null) return ResolvedFormation.fallback(null);
        return ResolvedFormation.fallback(Exec.formation(engine, squadIndex <= 0 ? 1 : squadIndex));
    }

    // ==================================================================
    // ROLE 解析（候选回退 + 合法性校验 + 容量/库存缩放 + 黑名单）
    // ==================================================================

    private static ResolveOutcome resolveRole(WjdrEngine engine, FormationLibrary lib,
                                              Map<String, Object> p, Set<Long> usedHeroes) {
        if (lib == null || lib.isEmpty()) {
            return ResolveOutcome.skip("编队库未同步（roleParams formation_library 缺失/为空）");
        }
        List<Long> candidates = formationIdsOf(p);
        if (candidates.isEmpty()) {
            return ResolveOutcome.skip("formation_mode=ROLE 但未选择 formation_id（请到功能页选编队）");
        }
        List<String> failures = new ArrayList<>();
        for (Long cid : candidates) {
            if (cid == null || cid == 0L) continue;
            ResolveOutcome outcome = tryRoleCandidate(engine, lib, p, cid, usedHeroes, failures);
            if (outcome != null) return outcome;
        }
        String summary = String.join("；", failures);
        if (summary.length() > 200) summary = summary.substring(0, 200) + "…";
        return ResolveOutcome.skip("编队候选均不可用：" + summary);
    }

    /**
     * 尝试单个 ROLE 候选；失败返回 null 并往 {@code failures} 追加可读原因（继续下一候选）。
     * 成功返回 READY（reason 含来源/缩放说明）。
     */
    private static ResolveOutcome tryRoleCandidate(WjdrEngine engine, FormationLibrary lib,
                                                   Map<String, Object> p, long formationId,
                                                   Set<Long> usedHeroes, List<String> failures) {
        FormationSpec stored = lib.byId(formationId);
        if (stored == null) {
            failures.add("编队 #" + formationId + " 不存在（编队库 " + lib.ids() + "）");
            return null;
        }
        if (stored.isGame()) {
            failures.add("编队 #" + formationId + " 是游戏内编队（只读），请在 App 里生成副本后使用");
            return null;
        }
        // 预设编队（PRESET_* / id 9100000001..19）：按当前英雄目录实时重算英雄（必须在 usable 前）
        FormationSpec spec = PresetLiveResolver.refresh(stored, engine);
        boolean presetRatioMode = PresetLiveResolver.isPercentagePreset(spec);
        // 2026-11-07：显式 allocation_mode=PERCENTAGE 的编队（App「按比例保存」）一律按
        // 「实时出征容量 × 比例」分配，忽略库内预算 soldiers/total；不再用 406 缩放兜底。
        // 注意：未写 allocation_mode 的旧编队仍保留 total/ratio 旧语义（兼容）。
        String explicitMode = spec.allocationMode == null ? ""
                : spec.allocationMode.trim().toUpperCase(java.util.Locale.ROOT);
        boolean capacityRatioMode = presetRatioMode
                || FormationSpec.MODE_PERCENTAGE.equals(explicitMode);
        if (!spec.usable()) {
            failures.add("编队 #" + formationId + " 没有英雄或 id 非法");
            return null;
        }
        // ① 英雄合法性：≤3 / 无重复 / 职业唯一 / 目录存在（网页版 R1 + 旧版保存期校验）
        String heroError = validateRoleHeroes(engine, spec);
        if (heroError != null) {
            failures.add("编队 #" + formationId + " " + heroError);
            return null;
        }
        // ② 同轮多队列预留：英雄被前序队列占用 → 试下一候选（旧版 HERO_RESERVED）
        List<Long> reserved = intersection(spec.heroIds, usedHeroes);
        if (!reserved.isEmpty()) {
            failures.add("编队 #" + formationId + " 英雄已被本轮前序队列占用（HERO_RESERVED）：" + reserved);
            return null;
        }
        // ③ 301/403 黑名单：命中 → 按 formation_fallback（默认 SKIP）回退 AUTO 或 SKIP
        List<Long> blocked = HeroAvailability.blockedAmong(engine, spec.heroIds);
        if (!blocked.isEmpty()) {
            String away = "编队 #" + formationId + " 英雄不在城/被占用（HERO_AWAY）：" + blocked;
            if (FALLBACK_AUTO.equals(fallbackModeOf(p))) {
                ResolveOutcome auto = autoOutcome(engine, ratioOf(p, automaticDefaultRatio(lib)),
                        totalOf(p), usedHeroes);
                if (auto.ready()) {
                    return ResolveOutcome.ready(auto.formation,
                            away + " → formation_fallback=AUTO，回退自动编队 · " + auto.reason);
                }
                failures.add(away + "；回退自动编队失败：" + auto.reason);
                return null;
            }
            failures.add(away);
            return null;
        }
        // ④ 兵种库存
        long[] stock = FormationCatalog.soldierStock(engine);
        long stockSum = stock[0] + stock[1] + stock[2];
        if (stockSum <= 0) {
            failures.add("编队 #" + formationId + " 兵种库存未同步（notify_soldier_init 缺失）");
            return null;
        }
        // ⑤ 兵量：显式兵量/比例 → fitFormation（超容量同比缩放、超库存缩放，不再 SKIP）
        List<String> notes = new ArrayList<>();
        // PERCENTAGE 预设：忽略库内预算兵量与队列 total，强制「预设比例 × 实时容量」
        long[] override = capacityRatioMode ? null : soldiersOf(p);
        long[] merged = new long[]{
                Math.max(0L, spec.infantry),
                Math.max(0L, spec.lancer),
                Math.max(0L, spec.marksman)};
        boolean overrideAny = false;
        if (override != null) {
            for (int i = 0; i < 3; i++) {
                if (override[i] > 0) {
                    merged[i] = override[i];   // 功能级覆盖：只覆盖 >0 的兵种，其余沿用库兵量
                    overrideAny = true;
                }
            }
        }
        // 契约 §3.1：容量上限统一走 T2 FormationCatalog.capacityForHeroes（marchCapacity 同源）
        long capacity = capacityForHeroes(engine, new ArrayList<>(spec.heroIds));
        long bp = percentageBasisPoints(engine);
        long[] soldiers;
        String sourceNote;
        if (!capacityRatioMode && (overrideAny || spec.hasExplicitSoldiers())) {
            // MANUAL：configuredTotal → expandedFixedTarget（容量扩张）→ 最大余数按兵量权重重分配
            long configuredTotal = merged[0] + merged[1] + merged[2];
            long target = configuredTotal;
            if (capacity > 0L) {
                long expanded = expandedFixedTarget(configuredTotal, bp, 0L, capacity);
                if (expanded < 0L) {
                    capacityWarn("ROLE", capacity, configuredTotal);
                    target = Math.min(configuredTotal, capacity);
                } else {
                    target = expanded;
                    if (target != configuredTotal) {
                        notes.add("兵量(" + configuredTotal + ")按出征容量(" + capacity
                                + ")同比缩放为 " + target);
                    }
                }
            } else {
                capacityWarn("ROLE", 0L, configuredTotal);
                notes.add("出征容量未知（0），未按容量夹取");
            }
            soldiers = allocateLargestRemainders(target, merged);
            sourceNote = overrideAny ? "功能级 soldiers 覆盖" : "编队库兵量";
        } else {
            long total = capacityRatioMode ? 0L : totalOf(p);
            if (!capacityRatioMode && total <= 0L) total = spec.total;
            boolean ratioInformed = capacityRatioMode || hasExplicitRatio(p) || spec.ratioExplicit;
            long[] weights = capacityRatioMode
                    ? ratioArray(spec.ratio, DEFAULT_RATIO)
                    : ratioArray(ratioOf(p, spec.ratio), DEFAULT_RATIO);
            if (total > 0L) {
                // 旧 App/队列级 total 语义：目标总量按比例权重分配（兼容既有行为）
                long target = total;
                if (capacity > 0L && target > capacity) {
                    notes.add("total(" + target + ")超出出征容量(" + capacity + ")，已夹取");
                    target = capacity;
                } else if (capacity <= 0L) {
                    capacityWarn("ROLE", 0L, target);
                    notes.add("出征容量未知（0），未按容量夹取");
                }
                soldiers = allocateLargestRemainders(target, weights);
                sourceNote = "total=" + target + " 按比例分配";
            } else if (ratioInformed && capacity > 0L) {
                // PERCENTAGE（1:1 旧版 fitFormation）：targetTotal=round(capacity×sumPct/100)，
                // 再按比例权重最大余数分配（denominator=100，余数相同盾优先）。
                String ratioError = validatePercentages(weights);
                if (ratioError != null) {
                    failures.add("编队 #" + formationId + " " + ratioError);
                    return null;
                }
                soldiers = allocatePercentages(capacity, weights);
                sourceNote = (presetRatioMode ? "预设实时按比例分配（容量" : "按比例分配（容量")
                        + capacity + " × "
                        + weights[0] + ":" + weights[1] + ":" + weights[2] + "）";
            } else if (ratioInformed) {
                // 容量未知：退化为"库存上限内按比例分配"（旧行为，不阻塞），只 WARN
                capacityWarn("ROLE", 0L, 0L);
                long target = Math.min(stockSum, MAX_SOLDIERS_PER_MARCH);
                soldiers = allocateLargestRemainders(target, weights);
                notes.add("出征容量未知（0），按库存上限分配");
                sourceNote = "total=" + target + " 按比例分配";
            } else {
                failures.add("编队 #" + formationId
                        + " 未配置兵量（soldiers 全 0）且未设置 total/ratio（请在功能页编队里配置兵量或比例）");
                return null;
            }
        }
        // ⑥ 406 自适应：仅非「按比例」编队保留（按比例编队以容量×比例为唯一口径，
        // 用户 2026-11-07 口径：不再向下缩放）；按比例被 406 拒绝时由调用方做一次 60s 重试。
        if (!capacityRatioMode) {
            int scalePct = HeroAvailability.soldierScalePercent(engine);
            if (scalePct < 100) {
                long total = soldiers[0] + soldiers[1] + soldiers[2];
                if (total > 0L) {
                    long scaled = Math.max(1L, applyPercent(total, scalePct));
                    soldiers = allocateLargestRemainders(scaled, soldiers);
                    notes.add("406 缩放至" + scalePct + "%");
                }
            }
        }
        // ⑦ 库存缩放（逐兵种夹到可用库存；不再 SKIP 空转）
        long[] clamped = clampSoldiersToStock(soldiers, stock);
        if (!java.util.Arrays.equals(clamped, soldiers)) {
            notes.add("库存不足已按库存缩放（盾" + stock[0] + "/矛" + stock[1] + "/弓" + stock[2] + "）");
            soldiers = clamped;
        }
        if (soldiers[0] + soldiers[1] + soldiers[2] <= 0) {
            failures.add("编队 #" + formationId + " 兵量分配为 0（比例/库存不可用）");
            return null;
        }
        // ⑧ 队长排到上阵位第 1（网页版 captainFirst）
        Long captain = spec.effectiveCaptain();
        List<Long> heroOrder = captainFirst(spec.heroIds, captain);
        ResolvedFormation rf = build(engine, ResolvedFormation.SRC_ROLE, spec.id,
                spec.name, heroOrder, soldiers)
                .withMeta(captain, spec.effectiveAllocationMode(), spec.templateKey, spec.isBuiltIn());
        StringBuilder reason = new StringBuilder("角色编队 #").append(spec.id);
        if (spec.name != null && !spec.name.isEmpty()) reason.append('(').append(spec.name).append(')');
        reason.append(" · ").append(sourceNote);
        if (!notes.isEmpty()) reason.append(" · ").append(String.join("；", notes));
        return ResolveOutcome.ready(rf, reason.toString());
    }

    /**
     * ROLE 英雄合法性校验（返回 null=合法，否则中文原因；契约 §3.4）：
     * 英雄数 ≤ {@link FormationSpec#TEAM_SIZE}、无重复、职业唯一（未知职业=0 跳过不误杀）、
     * 必须在角色英雄目录内。
     */
    static String validateRoleHeroes(WjdrEngine engine, FormationSpec spec) {
        List<Long> heroes = spec.heroIds == null ? List.of() : spec.heroIds;
        if (heroes.isEmpty()) return "没有英雄";
        if (heroes.size() > FormationSpec.TEAM_SIZE) {
            return "英雄数 " + heroes.size() + " 超过上限 " + FormationSpec.TEAM_SIZE
                    + "（盾/矛/弓各 1 名）";
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> duplicates = new ArrayList<>();
        for (Long h : heroes) {
            if (h == null || h <= 0L) continue;
            if (!seen.add(h)) duplicates.add(h);
        }
        if (!duplicates.isEmpty()) return "英雄重复：" + duplicates;
        List<FormationCatalog.Hero> catalog = FormationCatalog.heroes(engine);
        if (catalog.isEmpty()) {
            return "英雄目录未同步（notify_hero_init 缺失），无法校验编队英雄";
        }
        Map<Long, Integer> professionOf = new LinkedHashMap<>();
        for (FormationCatalog.Hero h : catalog) professionOf.put(h.id, h.profession);
        List<Long> missing = new ArrayList<>();
        for (Long h : heroes) {
            if (h == null || h <= 0L || !professionOf.containsKey(h)) missing.add(h);
        }
        if (!missing.isEmpty()) return "英雄不在角色英雄库：" + missing + "（可能已下阵/未同步）";
        Map<Integer, Long> byProfession = new LinkedHashMap<>();
        List<Long> conflict = new ArrayList<>();
        for (Long h : heroes) {
            int p = professionOf.getOrDefault(h, 0);
            if (p < 1 || p > 3) continue;                 // 未知职业=0：跳过职业校验（不误杀）
            Long first = byProfession.get(p);
            if (first != null) {
                conflict.add(first);
                conflict.add(h);
            } else {
                byProfession.put(p, h);
            }
        }
        if (!conflict.isEmpty()) {
            return "职业重复（盾/矛/弓各限 1 名）：" + conflict;
        }
        return null;
    }

    // ==================================================================
    // 纯函数：旧版 release288 分配/缩放算法（单测直接锁定）
    // ==================================================================

    /**
     * 406 兵力百分比缩放（旧版自动编队 {@code target * pct / 100} 的等价四舍五入实现）：
     * {@code round_half_up(total*pct/100)}；pct&lt;=0 → 0，pct&gt;=100 → 原值。
     *
     * <p>注意与 {@link #scaleTotal} 区分：scaleTotal 是"按容量上限同比缩放"（要求 oldTotal≤oldLimit），
     * 本方法是"按百分比缩放总量"（如 406 后 75%/50%/25%）。
     */
    public static long applyPercent(long total, int pct) {
        if (total <= 0L || pct <= 0) return 0L;
        if (pct >= 100) return total;
        try {
            return Math.addExact(Math.multiplyExact(total, (long) pct), 50L) / 100L;
        } catch (ArithmeticException e) {
            return total / 100L * pct;
        }
    }

    /**
     * 旧版 {@code DailyRoleFormationCalculator.scaleTotal}：<b>四舍五入</b>等比缩放
     * {@code round_half_up(oldTotal*newLimit/oldLimit)}，再 {@code min(newLimit, ·)}。
     * 参数非法（oldTotal<0 / oldLimit<=0 / newLimit<0 / oldTotal>oldLimit）返回 <b>-1</b>
     * （旧版抛错；服务端改为 WARN+跳过缩放，不阻塞出征）。
     */
    public static long scaleTotal(long oldTotal, long oldLimit, long newLimit) {
        if (oldTotal < 0L || oldLimit <= 0L || newLimit < 0L || oldTotal > oldLimit) return -1L;
        BigInteger divisor = BigInteger.valueOf(oldLimit);
        BigInteger scaled = BigInteger.valueOf(oldTotal).multiply(BigInteger.valueOf(newLimit))
                .add(divisor.divide(BigInteger.TWO)).divide(divisor);
        try {
            return Math.min(newLimit, scaled.longValueExact());
        } catch (ArithmeticException e) {
            return -1L;
        }
    }

    /**
     * 旧版 {@code DailyRoleFormationService.expandedFixedTarget}：
     * {@code min(floor((configuredTotal+fixedBonus)*(10000+max(0,bp))/10000), effectiveLimit)}。
     * 参数非法/溢出返回 <b>-1</b>（调用方 WARN+跳过缩放）。
     *
     * <p>服务端当前无 avalanche/cyril 运行时容量增益，{@code fixedBonus} 传 0（容量天然偏小，
     * 宁低勿高，安全）。
     */
    public static long expandedFixedTarget(long configuredTotal, long percentageBasisPoints,
                                           long fixedBonus, long effectiveLimit) {
        if (configuredTotal < 0L || fixedBonus < 0L || effectiveLimit < 0L) return -1L;
        try {
            long multiplier = Math.addExact(10000L, Math.max(0L, percentageBasisPoints));
            long fixedSubtotal = Math.addExact(configuredTotal, fixedBonus);
            long expanded = Math.floorDiv(Math.multiplyExact(fixedSubtotal, multiplier), 10000L);
            return Math.min(expanded, effectiveLimit);
        } catch (ArithmeticException e) {
            return -1L;
        }
    }

    /**
     * 最大余数法分配（旧版 {@code IntegerAllocationSupport.allocateLargestRemainders}）：
     * 按权重把 {@code targetTotal} 分成三份，每份先 floor，余数大者依次 +1；
     * <b>余数并列时序号小的优先（盾→矛→弓）</b>。
     */
    public static long[] allocateLargestRemainders(long targetTotal, long... weights) {
        if (weights == null || weights.length == 0) return new long[0];
        long weightSum = 0L;
        for (long w : weights) weightSum += Math.max(0L, w);
        return allocateFractions(targetTotal, targetTotal, weightSum, weights);
    }

    /**
     * 最大余数法分配通用形式：每份 {@code floor(multiplier*weight/divisor)}，余数大者依次 +1。
     * PERCENTAGE 路径用 {@code multiplier=limit, divisor=100}（与旧版 allocatePercentages 一致）。
     */
    public static long[] allocateFractions(long targetTotal, long multiplier,
                                           long divisor, long... weights) {
        if (weights == null || weights.length == 0) return new long[0];
        long[] out = new long[weights.length];
        if (targetTotal <= 0L || multiplier <= 0L || divisor <= 0L) return out;
        BigInteger div = BigInteger.valueOf(divisor);
        BigInteger mult = BigInteger.valueOf(multiplier);
        BigInteger[] remainders = new BigInteger[weights.length];
        long allocated = 0L;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] qr = mult.multiply(BigInteger.valueOf(Math.max(0L, weights[i])))
                    .divideAndRemainder(div);
            try {
                out[i] = qr[0].longValueExact();
            } catch (ArithmeticException e) {
                out[i] = 0L;
            }
            remainders[i] = qr[1];
            allocated += out[i];
        }
        // 余数补齐：每轮取余数最大者 +1 并置 -1（并列取序号小=盾优先）
        int guard = 0;
        while (allocated < targetTotal && guard++ < 10_000) {
            int target = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i].compareTo(remainders[target]) > 0) target = i;
            }
            out[target] += 1L;
            allocated += 1L;
            remainders[target] = BigInteger.valueOf(-1L);
        }
        return out;
    }

    /**
     * 旧版 {@code allocatePercentages(limit, inf%,lanc%,mark%)}：
     * {@code targetTotal=round_half_up(limit*sumPct/100)}，再按 {@code multiplier=limit, divisor=100}
     * 最大余数分配（每份 = limit×pct/100）。比例非法（负/&gt;100/合计 0 或 &gt;100）返回全 0。
     */
    public static long[] allocatePercentages(long limit, long... percentages) {
        long[] out = new long[percentages == null ? 0 : percentages.length];
        if (percentages == null || percentages.length != 3 || limit <= 0L) return out;
        long sum = 0L;
        for (long p : percentages) {
            if (p < 0L || p > 100L) return out;
            sum += p;
        }
        if (sum <= 0L || sum > 100L) return out;
        BigInteger limitValue = BigInteger.valueOf(limit);
        long targetTotal = limitValue.multiply(BigInteger.valueOf(sum))
                .add(BigInteger.valueOf(50L)).divide(BigInteger.valueOf(100L)).longValue();
        return allocateFractions(targetTotal, limit, 100L, percentages);
    }

    /** 逐兵种夹到可用库存（"库存不足按库存缩放"；返回新数组，不修改入参）。 */
    public static long[] clampSoldiersToStock(long[] soldiers, long[] stock) {
        long[] out = new long[3];
        for (int i = 0; i < 3; i++) {
            long want = soldiers == null || i >= soldiers.length ? 0L : Math.max(0L, soldiers[i]);
            long cap = stock == null || i >= stock.length ? 0L : Math.max(0L, stock[i]);
            out[i] = Math.min(want, cap);
        }
        return out;
    }

    // ==================================================================
    // 构造 payload
    // ==================================================================

    private static ResolvedFormation build(WjdrEngine engine, String source, long formationId,
                                           String label, List<Long> heroIds, long[] soldiers) {
        List<Map<String, Object>> heroPairs = Exec.heroPairs(heroIds);
        Map<Long, Long> counts = FormationCatalog.soldierCounts(engine);
        long[] bestIds = FormationCatalog.bestSoldierIds(engine);
        long[] actual = new long[3];
        List<Map<String, Object>> soldierPairs = soldierPairsFor(counts, soldiers, bestIds, actual);
        return ResolvedFormation.of(source, formationId, label, heroIds,
                actual[0], actual[1], actual[2], heroPairs, soldierPairs);
    }

    /**
     * 按兵种装兵（2026-11-04 用户口径：能满编尽量满编，没有能带多少带多少）。
     *
     * <p>旧实现只取「最高档」一个 id：号上 T11 盾 400 + T10 盾 69605 时只带 400 盾，
     * 或档位对不上时请求超量 → 401。现从高档往低档依次装，跨档补足；某档不够就带下一档，
     * 全部不够就带现有的，绝不因为凑不满而放弃出征。
     *
     * @param actualOut 输出各兵种实际装上的数量（下标 0/1/2=盾/矛/弓）
     */
    static List<Map<String, Object>> soldierPairsFor(Map<Long, Long> counts, long[] soldiers,
                                                     long[] bestIds, long[] actualOut) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (soldiers == null) return out;
        for (int i = 0; i < 3 && i < soldiers.length; i++) {
            long need = Math.max(0L, soldiers[i]);
            if (need <= 0L) continue;
            for (long sid : tierIdsDesc(counts, i + 1)) {
                if (need <= 0L) break;
                long avail = counts == null ? 0L : Math.max(0L,
                        counts.getOrDefault(sid, 0L));
                if (avail <= 0L) continue;
                long take = Math.min(need, avail);
                out.add(Exec.soldierPair(sid, take));
                need -= take;
                if (actualOut != null && i < actualOut.length) actualOut[i] += take;
            }
            // 库存明细完全缺失（老数据/未同步）才用最高档兜底；有明细但不够 → 就带现有的，
            // 绝不多报数量（多报会 401「兵力不足」）
            if (need > 0L && (counts == null || counts.isEmpty())) {
                long sid = bestIds != null && i < bestIds.length && bestIds[i] > 0L
                        ? bestIds[i] : defaultSoldierId(i);
                out.add(Exec.soldierPair(sid, need));
                if (actualOut != null && i < actualOut.length) actualOut[i] += need;
            }
        }
        return out;
    }

    /** 某兵种（line=1/2/3）的全部档位 id，按档位从高到低。 */
    static List<Long> tierIdsDesc(Map<Long, Long> counts, int line) {
        List<Long> ids = new ArrayList<>();
        if (counts == null) return ids;
        for (Long id : counts.keySet()) {
            if (id == null || id <= 0L) continue;
            if ((int) (id / 10000L) == line) ids.add(id);
        }
        ids.sort((a, b) -> Long.compare(b % 10000L, a % 10000L));
        return ids;
    }

    private static long defaultSoldierId(int line) {
        return switch (line) {
            case 0 -> 11000L;
            case 1 -> 21000L;
            default -> 31000L;
        };
    }

    /** 队长排到第 1 位（其余保持原顺序）。 */
    static List<Long> captainFirst(List<Long> heroIds, Long captainHeroId) {
        List<Long> out = heroIds == null ? new ArrayList<>() : new ArrayList<>(heroIds);
        if (captainHeroId != null) {
            out.remove(captainHeroId);
            out.add(0, captainHeroId);
        }
        return out;
    }

    // ==================================================================
    // 英雄候选
    // ==================================================================

    /** 自动编队英雄候选：按战力/等级/星级/id 降序，最多 {@code max} 个（≤5）。 */
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

    /** 自动编队英雄（无同轮预留；旧签名保留）。 */
    public static List<Long> autoProfessionHeroes(WjdrEngine engine) {
        return autoProfessionHeroes(engine, null);
    }

    /**
     * 自动编队英雄：游戏规则每队列最多 3 名英雄且<b>盾/矛/弓各一名</b>。按职业各选
     * “最强且在城/未拉黑/未被前序队列占用”的一名；415 后上限按
     * {@link HeroAvailability#autoHeroLimit} 下调（3→2→1，成功出征复位）；
     * <b>职业未知（profession=0）的英雄不参与 AUTO</b>（网页版 R1 / P2-1）。
     *
     * @param usedHeroes 本轮前序队列已占用英雄（null/空=不预留；多队列 403 防护）
     */
    public static List<Long> autoProfessionHeroes(WjdrEngine engine, Collection<Long> usedHeroes) {
        List<Long> out = new ArrayList<>();
        if (engine == null) return out;
        Set<Long> used = normalizeHeroes(usedHeroes);
        java.util.Set<Long> busy = FormationCatalog.busyHeroIds(engine);
        int limit = Math.max(1, Math.min(HeroAvailability.DEFAULT_AUTO_HEROES,
                HeroAvailability.autoHeroLimit(engine)));
        long[] picked = new long[4];   // index 1/2/3 = 盾/矛/弓
        for (FormationCatalog.Hero h : FormationCatalog.heroes(engine)) {
            int p = h.profession;
            if (p < 1 || p > 3) continue;                 // 职业未知不参与自动编队
            if (picked[p] > 0L) continue;
            if (used.contains(h.id)) continue;            // 同轮前序队列已预留
            if (HeroAvailability.isBlocked(engine, h.id)) continue;
            if (busy.contains(h.id)) continue;
            picked[p] = h.id;
            if (picked[1] > 0L && picked[2] > 0L && picked[3] > 0L) break;
        }
        for (int p = 1; p <= 3 && out.size() < limit; p++) {
            if (picked[p] > 0L) out.add(picked[p]);
        }
        return out;
    }

    /**
     * 按职业指定偏好英雄的候选（情报出征；契约 §4.1.2/§4.1.3）：
     * 下标 1/2/3 = 盾/矛/弓职业的指定英雄 id（0=取该职业“最强可用”）。
     *
     * <p>指定英雄必须<b>存在于目录且职业匹配且当前可用</b>——目录不存在的 id、职业不符
     * （盾位填了弓英雄）、不可用（{@link FormationCatalog.Hero#available}=false 在途/不在城、
     * 301 拉黑 {@link HeroAvailability#isBlocked}、{@link FormationCatalog#busyHeroIds} 在途占用、
     * {@code usedHeroes} 本轮前序占用）一律跳过，回退该职业“最强可用”。
     *
     * <p>目录 {@link FormationCatalog#heroes} 已按强→弱排序，因此“最强可用”= 顺序首个可用者；
     * 最多 {@link HeroAvailability#autoHeroLimit} 名（415 自适应），职业唯一，按盾/矛/弓顺序输出；
     * 某职业无可用英雄则跳过该位（不硬凑）。
     *
     * @param preferredByProfession 长度 ≥4 的数组（下标 1/2/3 生效；null/短数组按 0=自动处理）
     * @param usedHeroes            本轮前序队列已占用英雄（null=不预留）
     */
    public static List<Long> preferredProfessionHeroes(WjdrEngine engine, long[] preferredByProfession,
                                                       Collection<Long> usedHeroes) {
        List<Long> out = new ArrayList<>();
        if (engine == null) return out;
        Set<Long> used = normalizeHeroes(usedHeroes);
        Set<Long> busy = FormationCatalog.busyHeroIds(engine);
        int limit = Math.max(1, Math.min(HeroAvailability.DEFAULT_AUTO_HEROES,
                HeroAvailability.autoHeroLimit(engine)));
        List<FormationCatalog.Hero> catalog = FormationCatalog.heroes(engine);   // 强 → 弱
        Set<Long> chosen = new LinkedHashSet<>();
        long[] picked = new long[4];   // index 1/2/3 = 盾/矛/弓
        for (int p = 1; p <= 3; p++) {
            long preferred = preferredByProfession != null && p < preferredByProfession.length
                    ? preferredByProfession[p] : 0L;
            if (preferred > 0L) {
                FormationCatalog.Hero hit = heroById(catalog, preferred);
                // 指定英雄：必须职业匹配且可用，否则回退该职业最强可用
                if (hit != null && hit.profession == p
                        && heroUsable(engine, hit, busy, used, chosen)) {
                    picked[p] = hit.id;
                    chosen.add(hit.id);
                    continue;
                }
            }
            for (FormationCatalog.Hero h : catalog) {
                if (h.profession != p) continue;
                if (!heroUsable(engine, h, busy, used, chosen)) continue;
                picked[p] = h.id;
                chosen.add(h.id);
                break;
            }
        }
        for (int p = 1; p <= 3 && out.size() < limit; p++) {
            if (picked[p] > 0L) out.add(picked[p]);
        }
        return out;
    }

    /** 目录内按 id 查英雄（不存在/非法 id 返回 null）。 */
    private static FormationCatalog.Hero heroById(List<FormationCatalog.Hero> catalog, long heroId) {
        if (heroId <= 0L || catalog == null) return null;
        for (FormationCatalog.Hero h : catalog) {
            if (h.id == heroId) return h;
        }
        return null;
    }

    /** 自动/指定英雄是否可用于本队列（在城 + 未拉黑 + 未在途 + 未被本队列/前序队列占用）。 */
    private static boolean heroUsable(WjdrEngine engine, FormationCatalog.Hero h, Set<Long> busy,
                                      Set<Long> used, Set<Long> chosen) {
        return h != null && h.available
                && (busy == null || !busy.contains(h.id))
                && (used == null || !used.contains(h.id))
                && (chosen == null || !chosen.contains(h.id))
                && !HeroAvailability.isBlocked(engine, h.id);
    }

    // ==================================================================
    // 比例分配（AUTO 用；库存上限 + 总量夹取；保留旧语义/单测）
    // ==================================================================

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

    // ==================================================================
    // 参数解析（纯函数，容错 Map/JSONObject/字符串数字）
    // ==================================================================

    /** 是否配置了「新编队系统」参数（无参数=旧行为）。 */
    public static boolean isConfigured(Map<String, Object> fnParams) {
        if (fnParams == null || fnParams.isEmpty()) return false;
        for (String k : new String[]{"formation_mode", "formationMode", "mode",
                "formation_id", "formationId", "formation_ids", "formationIds",
                "soldiers", "ratio", "total"}) {
            Object v = fnParams.get(k);
            if (v == null) continue;
            if (v instanceof String && ((String) v).trim().isEmpty()) continue;
            if (v instanceof Map && ((Map<?, ?>) v).isEmpty()) continue;
            if (v instanceof List && ((List<?>) v).isEmpty()) continue;
            if (v instanceof JSONArray && ((JSONArray) v).length() == 0) continue;
            return true;
        }
        return false;
    }

    /** JSONObject 版 {@link #isConfigured(Map)}。 */
    public static boolean isConfigured(JSONObject fnParams) {
        return isConfigured(toMap(fnParams));
    }

    /** 编队模式：AUTOMATIC|ROLE；NORMAL/CUSTOM→ROLE；有 formation_id(s) 且未写 mode→ROLE。 */
    public static String modeOf(Map<String, Object> fnParams) {
        String raw = str(fnParams, "formation_mode", "formationMode");
        if (raw.isEmpty()) {
            return firstFormationIdOf(fnParams) != 0L ? MODE_ROLE : MODE_AUTOMATIC;
        }
        String up = raw.trim().toUpperCase(Locale.ROOT);
        if ("NORMAL".equals(up) || "CUSTOM".equals(up)) return MODE_ROLE;
        if (MODE_ROLE.equals(up)) return MODE_ROLE;
        if (MODE_AUTOMATIC.equals(up)) return MODE_AUTOMATIC;
        return firstFormationIdOf(fnParams) != 0L ? MODE_ROLE : MODE_AUTOMATIC;
    }

    /** 选中的编队 id（formation_id/formationId；无=0）。 */
    public static long formationIdOf(Map<String, Object> fnParams) {
        Object v = first(fnParams, "formation_id", "formationId");
        return num(v, 0L);
    }

    /** ROLE 候选编队 id 列表（formation_ids[] 优先，formation_id 兜底；去重保持顺序）。 */
    public static List<Long> formationIdsOf(Map<String, Object> fnParams) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        addIds(out, first(fnParams, "formation_ids", "formationIds"));
        long single = formationIdOf(fnParams);
        if (single != 0L) out.add(single);
        return new ArrayList<>(out);
    }

    /** 首个候选 id（formation_id 优先，其次 formation_ids[0]；无=0）。 */
    private static long firstFormationIdOf(Map<String, Object> fnParams) {
        long single = formationIdOf(fnParams);
        if (single != 0L) return single;
        List<Long> ids = formationIdsOf(fnParams);
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    /** total 参数（无=0）。 */
    public static long totalOf(Map<String, Object> fnParams) {
        return num(first(fnParams, "total", "troop_total"), 0L);
    }

    /** 黑名单回退模式：AUTO|SKIP（默认 SKIP）。 */
    public static String fallbackModeOf(Map<String, Object> fnParams) {
        String raw = str(fnParams, "formation_fallback", "formationFallback", "fallback");
        return FALLBACK_AUTO.equalsIgnoreCase(raw.trim()) ? FALLBACK_AUTO : FALLBACK_SKIP;
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

    /** 是否显式给出比例（params.ratio/soldier_ratio 非空 Map；spec 默认 40:30:30 不算显式）。 */
    public static boolean hasExplicitRatio(Map<String, Object> fnParams) {
        Object v = first(fnParams, "ratio", "soldier_ratio");
        if (v instanceof JSONObject) v = toMap((JSONObject) v);
        if (!(v instanceof Map)) return false;
        return !((Map<?, ?>) v).isEmpty();
    }

    /** 自动编队默认比例：库内 {@code automatic_settings}（显式配置时）优先，否则 40:30:30。 */
    private static Map<String, Long> automaticDefaultRatio(FormationLibrary lib) {
        if (lib != null && lib.automaticSettingsPresent) {
            int[] a = lib.automaticSettings();
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("infantry", (long) Math.max(0, a[0]));
            m.put("lancer", (long) Math.max(0, a[1]));
            m.put("marksman", (long) Math.max(0, a[2]));
            return m;
        }
        return FormationSpec.defaultRatio();
    }

    /** 比例合法性（PERCENTAGE 模式）：0..100 且合计 1..100；返回 null=合法。 */
    static String validatePercentages(long[] percentages) {
        if (percentages == null || percentages.length != 3) return "比例配置缺失";
        long sum = 0L;
        for (long p : percentages) {
            if (p < 0L || p > 100L) return "比例必须是 0..100 的整数";
            sum += p;
        }
        if (sum <= 0L) return "比例合计必须大于 0%";
        if (sum > 100L) return "比例合计不能超过 100%";
        return null;
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

    // ==================================================================
    // 容量（契约 §3.1 FormationCatalog.capacityForHeroes 等价实现）
    // ==================================================================

    /**
     * 出征容量 = floor((fixedCapacity+Σ troopCapacity)*(10000+bp)/10000)（契约 §3.1/§2.4）。
     * 统一委托 T2 冻结访问器 {@code FormationCatalog.capacityForHeroes}；未同步/取不到返回 0
     * （调用方视为未知：不夹取，仅 WARN）。
     */
    public static long capacityForHeroes(WjdrEngine engine, List<Long> heroIds) {
        if (engine == null) return 0L;
        try {
            return FormationCatalog.capacityForHeroes(engine, heroIds);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 容量百分比 Buff（万分比；契约 §3.1 {@code marchCapacity.percentageBasisPoints} 同源口径）。
     *
     * <p>取<b>即时</b>值：{@code FormationCatalog.capacityForHeroes} 每次即时估算容量，若 bp 读
     * capture 时的目录快照，buff 变更后会与容量公式不一致（容量已扩、扩张目标却按旧 bp）。
     * 与 T2 {@code MarchCapacity.of(engine)} 使用同一数据源（{@code MarchCapacityCalculator}）。
     */
    public static long percentageBasisPoints(WjdrEngine engine) {
        if (engine == null) return 0L;
        try {
            com.carlos.vault.wjdr.asset.MarchCapacityCalculator.Estimate est =
                    com.carlos.vault.wjdr.asset.MarchCapacityCalculator.estimateSquad(
                            engine, java.util.Collections.emptyList());
            return est == null ? 0L : Math.max(0L, est.bp);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 容量未知/夹取告警（契约 §3.4：capacity<=0 不夹取只 WARN）。 */
    private static void capacityWarn(String source, long capacity, long wouldSend) {
        try {
            FileLog.warn("编队容量未知/夹取 [{}] capacity={} 欲下发={}（不夹取，仅告警）",
                    source, capacity, wouldSend);
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static Set<Long> normalizeHeroes(Collection<Long> usedHeroes) {
        if (usedHeroes == null || usedHeroes.isEmpty()) return Collections.emptySet();
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : usedHeroes) {
            if (id != null && id > 0L) out.add(id);
        }
        return out;
    }

    private static List<Long> intersection(List<Long> heroes, Set<Long> used) {
        List<Long> out = new ArrayList<>();
        if (heroes == null || used == null || used.isEmpty()) return out;
        for (Long h : heroes) {
            if (h != null && used.contains(h)) out.add(h);
        }
        return out;
    }

    private static void addIds(Set<Long> out, Object v) {
        if (v == null) return;
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) {
                long id = arr.optLong(i, 0L);
                if (id != 0L) out.add(id);
            }
            return;
        }
        if (v instanceof long[]) {
            for (long id : (long[]) v) if (id != 0L) out.add(id);
            return;
        }
        if (v instanceof int[]) {
            for (int id : (int[]) v) if (id != 0) out.add((long) id);
            return;
        }
        if (v instanceof Collection) {
            for (Object o : (Collection<?>) v) {
                long id = num(o, 0L);
                if (id != 0L) out.add(id);
            }
            return;
        }
        if (v instanceof Number) {
            long id = ((Number) v).longValue();
            if (id != 0L) out.add(id);
            return;
        }
        String raw = String.valueOf(v).trim();
        if (raw.isEmpty()) return;
        for (String part : raw.split("[,;\\s\\[\\]]+")) {
            if (part.isEmpty()) continue;
            try {
                long id = Long.parseLong(part.trim());
                if (id != 0L) out.add(id);
            } catch (Throwable ignored) {
            }
        }
    }

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
