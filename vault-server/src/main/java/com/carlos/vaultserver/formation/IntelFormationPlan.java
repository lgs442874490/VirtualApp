package com.carlos.vaultserver.formation;

import com.carlos.vaultserver.task.TaskContext;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 情报出征编队计划（设计契约 §4，唯一事实源
 * {@code design/情报-每日三轮与出征编队-20261031.md}）。
 *
 * <p><b>为什么需要本类</b>（根因 R2）：情报原先用 {@code Exec.formation(engine, 1)}（游戏第 1 队），
 * 那通常是采集队/英雄在途的第 1 队 → 301/403/无兵，派队反复失败。情报必须用「情报编队」：
 *
 * <ol>
 *   <li><b>玩家指定编队优先</b>（§4.1.1）：{@code intel} params 有
 *       {@code formation_mode=ROLE} + {@code formation_id(s)} → 完全沿用
 *       {@link FormationResolver} 的 ROLE 解析（不改既有语义，含候选回退/黑名单/容量缩放）。</li>
 *   <li><b>情报自定义 / 默认自动</b>（§4.1.2/§4.1.3）：{@code intel_hero_shield/lancer/marksman}
 *       （英雄 id，0=自动）+ {@code intel_ratio} + {@code intel_total}；
 *       默认<b>最强盾 / 波根 50029 / 吉娜 50008</b>，缺波根/吉娜或不可用时回退同职业最强可用；
 *       比例默认 <b>10:10:80</b>，总兵力 0=按容量/库存自动（单队上限 50000）。</li>
 * </ol>
 *
 * <p>本类只做“参数 → 解析入口”的翻译，不复制 {@link FormationResolver} 的库存/容量/406/415
 * 逻辑（统一走 {@link FormationResolver#autoWithPreferredHeroes}）；解析失败返回 SKIP + 可读原因，
 * <b>绝不静默回退游戏第 1 队</b>。
 */
public final class IntelFormationPlan {

    /** 波根（矛兵；情报默认矛位英雄，§4.1.3）。 */
    public static final long HERO_BORGAN = 50029L;
    /** 吉娜（射手；情报默认弓位英雄，§4.1.3）。 */
    public static final long HERO_GINA = 50008L;

    /** 情报默认兵种比例 盾:矛:弓 = 10:10:80（与内置打野 WILD 编队一致，§4.1.2）。 */
    public static final long[] DEFAULT_RATIO = new long[]{10L, 10L, 80L};

    /** 默认比例键（FormationResolver 同源口径：infantry/lancer/marksman）。 */
    private static final String[] RATIO_KEYS = {"infantry", "lancer", "marksman"};

    private IntelFormationPlan() {
    }

    /**
     * 解析情报出征编队（任务侧唯一入口）。
     *
     * @param tc 任务上下文（{@code tc.params("intel")} 读 App 同步的情报功能参数）
     * @return READY（formation 可直接下发 {@code req_world_march}）/ SKIP（reason 可读）
     */
    public static FormationResolver.ResolveOutcome resolve(TaskContext tc) {
        if (tc == null) return FormationResolver.ResolveOutcome.skip("情报编队：任务上下文为空");
        if (tc.engine == null) return FormationResolver.ResolveOutcome.skip("情报编队：engine 为空");
        JSONObject p = tc.params("intel");
        // ① 玩家指定编队优先（§4.1.1）：只在真的选了 formation_id(s) 时走 ROLE，
        //    避免“formation_mode=ROLE 但没选编队”把情报编队整体 SKIP 掉。
        Map<String, Object> map = toMap(p);
        if (FormationResolver.MODE_ROLE.equals(FormationResolver.modeOf(map))
                && !FormationResolver.formationIdsOf(map).isEmpty()) {
            return FormationResolver.resolveOutcome(tc.engine,
                    FormationResolver.libraryFor(tc, "intel"), p);
        }
        // ② 情报自定义 / 默认自动（§4.1.2/§4.1.3）
        return FormationResolver.autoWithPreferredHeroes(tc.engine, preferredHeroesOf(p),
                ratioOf(p), totalOf(p), null);
    }

    /**
     * 情报英雄偏好（下标 1/2/3 = 盾/矛/弓职业；纯函数，单测直接锁定）：
     * 盾 0=最强可用；矛 0=波根 {@link #HERO_BORGAN}；弓 0=吉娜 {@link #HERO_GINA}。
     * 指定值原样返回，由 {@link FormationResolver#preferredProfessionHeroes} 做职业/可用性校验与回退。
     */
    static long[] preferredHeroesOf(JSONObject p) {
        long[] out = new long[4];
        long shield = p == null ? 0L : p.optLong("intel_hero_shield", 0L);
        long lancer = p == null ? 0L : p.optLong("intel_hero_lancer", 0L);
        long marksman = p == null ? 0L : p.optLong("intel_hero_marksman", 0L);
        out[1] = Math.max(0L, shield);
        out[2] = lancer > 0L ? lancer : HERO_BORGAN;
        out[3] = marksman > 0L ? marksman : HERO_GINA;
        return out;
    }

    /**
     * 情报兵种比例（纯函数）：{@code intel_ratio{infantry,lancer,marksman}} 优先，
     * 其次旧 {@code ratio}/{@code soldier_ratio}，否则默认 {@link #DEFAULT_RATIO}（10:10:80）。
     * 返回 Map 始终含 infantry/lancer/marksman 三键（缺失项按默认补齐）。
     */
    static Map<String, Long> ratioOf(JSONObject p) {
        Map<String, Long> def = defaultRatioMap();
        Map<String, Long> explicit = intelRatioOf(p);
        if (explicit != null) {
            for (String k : RATIO_KEYS) {
                if (explicit.get(k) == null) explicit.put(k, def.get(k));
            }
            return explicit;
        }
        // 旧 ratio/soldier_ratio 存在则用，缺失项按情报默认补齐（不是 40:30:30）
        return FormationResolver.ratioOf(toMap(p), def);
    }

    /**
     * 情报总兵力：{@code intel_total>0} 优先，其次通用 {@code total}/{@code troop_total}，
     * 否则 0=按出征容量与库存自动（§4.1.2）。
     */
    static long totalOf(JSONObject p) {
        long explicit = p == null ? 0L : p.optLong("intel_total", 0L);
        if (explicit > 0L) return explicit;
        return Math.max(0L, FormationResolver.totalOf(toMap(p)));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** {@code intel_ratio}（对象/JSON 字符串；兼容 shield/spear/bow 别名）；无配置返回 null。 */
    private static Map<String, Long> intelRatioOf(JSONObject p) {
        if (p == null) return null;
        Object raw = p.opt("intel_ratio");
        if (raw == null) raw = p.opt("intelRatio");
        Map<String, Long> out = parseRatio(raw);
        return out == null || out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> parseRatio(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return null;
            try {
                raw = new JSONObject(s);
            } catch (Throwable t) {
                return null;
            }
        }
        Map<?, ?> m;
        if (raw instanceof JSONObject) {
            m = toMap((JSONObject) raw);
        } else if (raw instanceof Map) {
            m = (Map<?, ?>) raw;
        } else {
            return null;
        }
        Map<String, Long> out = new LinkedHashMap<>();
        putRatio(out, "infantry", pick(m, "infantry", "shield"));
        putRatio(out, "lancer", pick(m, "lancer", "spear"));
        putRatio(out, "marksman", pick(m, "marksman", "bow"));
        return out;
    }

    private static void putRatio(Map<String, Long> out, String key, Object raw) {
        long v = num(raw, -1L);
        if (v >= 0L) out.put(key, v);
    }

    private static Object pick(Map<?, ?> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) return m.get(k);
        }
        return null;
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

    private static Map<String, Long> defaultRatioMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("infantry", DEFAULT_RATIO[0]);
        m.put("lancer", DEFAULT_RATIO[1]);
        m.put("marksman", DEFAULT_RATIO[2]);
        return m;
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
}
