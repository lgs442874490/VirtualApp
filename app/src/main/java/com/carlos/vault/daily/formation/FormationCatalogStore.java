package com.carlos.vault.daily.formation;

import com.carlos.vault.daily.FormationStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * App 侧编队目录 v2 解析（契约 §2.3）。
 *
 * <p>数据来源（契约 §3.2）：服务端 state 键 {@code srv:formation:catalog:{fpid}:{sid}:{uid}}
 * 的值 JSON，形状：
 * <pre>{"version":2,"capturedAt":...,"configured":true,
 *  "heroes":[{id,name,profession,professionType,level,star,starDisplay,power,
 *             generation,troopCapacity,available,availabilityState,availabilityReason}],
 *  "soldiers":{...},"marchCapacity":{initialized,message,fixedCapacity,percentageBasisPoints,
 *  zeroHeroCapacity,oneHeroCapacity,twoHeroCapacity,threeHeroCapacity,limits{...}},
 *  "automaticSettings":{infantryRatio,lancerRatio,marksmanRatio},
 *  "gameFormations":[...],"avalanche":{...},"memory":{...},"bear":{...}}</pre>
 *
 * <p><b>兼容网页 collection 形状</b>（线上 GET {@code /api/daily/roles/{r}/formations} 实测）：
 * {@code heroOptions[]}/{@code avalancheCapacity}/{@code limits}/{@code formations[]}/{@code configured} 亦可解析，
 * 因此线上真实样本可直接作为单测数据（{@code app/src/test/resources/formation/formations_collection.json}）。
 *
 * <p>目录缺失/非法时返回 {@link #empty()} 式的非空对象（{@link #configured()}=false、容量 0），
 * 调用方无需判空即可降级渲染（契约 §2.3）。
 */
public final class FormationCatalogStore {

    private static final FormationCatalogStore EMPTY =
            new FormationCatalogStore(null);

    private final JSONObject raw;

    /** 构造：raw 可为 null（等价空目录）。 */
    public FormationCatalogStore(JSONObject raw) {
        this.raw = raw;
    }

    /** 空目录（configured()=false，容量 0）。 */
    public static FormationCatalogStore empty() {
        return EMPTY;
    }

    /** JSON 字符串 → 目录（容忍非法 JSON：返回空目录）。 */
    public static FormationCatalogStore parse(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return EMPTY;
        try {
            return new FormationCatalogStore(new JSONObject(rawJson.trim()));
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /** 原始 JSON（恒非 null；空目录为空对象）。 */
    public JSONObject raw() {
        return raw == null ? new JSONObject() : raw;
    }

    /** 容量是否已同步（{@code marchCapacity.initialized} 或 collection 的 {@code configured}）。 */
    public boolean configured() {
        JSONObject mc = marchCapacity();
        if (mc.optBoolean("initialized", false)) return true;
        return raw().optBoolean("configured", false);
    }

    /** 自动编队是否可用（缺省=容量已就绪）。 */
    public boolean automaticAvailable() {
        if (raw().has("automaticAvailable")) {
            return raw().optBoolean("automaticAvailable", false);
        }
        return configured();
    }

    /** 目录采集时间（毫秒；缺失 0）。 */
    public long capturedAt() {
        return raw().optLong("capturedAt", raw().optLong("captured_at", 0));
    }

    /** 全部拥有英雄（含不在城）；兼容 {@code heroOptions[]}（网页）与 {@code heroes[]}（state v2）。 */
    public List<FormationStore.Hero> heroOptions() {
        List<FormationStore.Hero> heroes = FormationStore.parseHeroes(raw());
        return heroes == null ? new ArrayList<FormationStore.Hero>() : heroes;
    }

    /** 按 id 取英雄；不存在返回 null。 */
    public FormationStore.Hero hero(long heroId) {
        for (FormationStore.Hero h : heroOptions()) {
            if (h.id == heroId) return h;
        }
        return null;
    }

    /** 全部拥有英雄 id 集合（判断模板缺英雄用；与是否在城无关，网页 S2）。 */
    public List<Long> heroIds() {
        List<Long> out = new ArrayList<Long>();
        for (FormationStore.Hero h : heroOptions()) out.add(h.id);
        return out;
    }

    /**
     * 出征容量（自动编队口径，网页 C3）：当前在家英雄按职业取最高战力，最多 3 人；
     * 无可用英雄时按 0 英雄容量（=fixedCapacity）。未同步/容量 0 → 0。
     */
    public long capacity() {
        if (!configured()) return 0;
        List<Long> picks = bestAvailableHeroIds();
        return capacityFor(picks);
    }

    /** 在家英雄按职业（1/2/3）取最高战力，最多 3 人（无职业信息的按顺序补足）。 */
    public List<Long> bestAvailableHeroIds() {
        FormationStore.Hero[] best = new FormationStore.Hero[4];
        List<FormationStore.Hero> rest = new ArrayList<FormationStore.Hero>();
        for (FormationStore.Hero h : heroOptions()) {
            if (!h.available) continue;
            int p = FormationRules.professionOf(h);
            if (p >= 1 && p <= 3) {
                if (best[p] == null || h.power > best[p].power) best[p] = h;
            } else {
                rest.add(h);
            }
        }
        List<Long> out = new ArrayList<Long>();
        for (int p = 1; p <= 3; p++) {
            if (best[p] != null) out.add(best[p].id);
        }
        for (FormationStore.Hero h : rest) {
            if (out.size() >= FormationModel.HERO_MAX) break;
            out.add(h.id);
        }
        while (out.size() > FormationModel.HERO_MAX) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 目录各职业（1/2/3）最高战力英雄 id，最多 3 人（**不要求在城**，内置编队懒填口径，
     * 契约 §2.6「目录前 3 职业最高战力」）；无职业信息的按战力补足。
     */
    public List<Long> bestProfessionHeroIds() {
        FormationStore.Hero[] best = new FormationStore.Hero[4];
        List<FormationStore.Hero> rest = new ArrayList<FormationStore.Hero>();
        for (FormationStore.Hero h : heroOptions()) {
            int p = FormationRules.professionOf(h);
            if (p >= 1 && p <= 3) {
                if (best[p] == null || h.power > best[p].power) best[p] = h;
            } else {
                rest.add(h);
            }
        }
        List<Long> out = new ArrayList<Long>();
        for (int p = 1; p <= 3; p++) {
            if (best[p] != null) out.add(best[p].id);
        }
        for (FormationStore.Hero h : rest) {
            if (out.size() >= FormationModel.HERO_MAX) break;
            out.add(h.id);
        }
        while (out.size() > FormationModel.HERO_MAX) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 指定英雄组合的出征容量（契约 §2.4）：{@code floor((fixedCapacity + Σ troopCapacity) *
     * (10000 + percentageBasisPoints) / 10000)}；未同步 → 0。
     *
     * <p>超大数用 BigInteger 精确计算，结果超过 long 上限时夹到 {@link Long#MAX_VALUE}。
     */
    public long capacityFor(List<Long> heroIds) {
        if (!configured()) return 0;
        BigInteger base = BigInteger.valueOf(Math.max(0, fixedCapacity()));
        if (heroIds != null) {
            for (Long id : heroIds) {
                if (id == null || id.longValue() <= 0) continue;
                FormationStore.Hero h = hero(id.longValue());
                if (h != null) base = base.add(BigInteger.valueOf(Math.max(0, h.troopCapacity)));
            }
        }
        BigInteger multiplier = BigInteger.valueOf(10000L + Math.max(0, percentageBasisPoints()));
        BigInteger v = base.multiply(multiplier).divide(BigInteger.valueOf(10000L));
        return clampLong(v);
    }

    /** 基础出征上限（0 英雄容量）。 */
    public long fixedCapacity() {
        JSONObject mc = marchCapacity();
        long v = mc.optLong("fixedCapacity", mc.optLong("fixed_capacity", -1));
        if (v >= 0) return v;
        long zero = mc.optLong("zeroHeroCapacity", -1);
        if (zero >= 0) return zero;
        JSONObject limits = limitsObject();
        long z = limits.optLong("zeroHeroLimit", -1);
        if (z >= 0) return z;
        // 旧版 v1 目录只有世界容量 capacity
        return Math.max(0, raw().optLong("capacity", 0));
    }

    /** 容量百分比 Buff（万分比：100=+1%）。 */
    public int percentageBasisPoints() {
        JSONObject mc = marchCapacity();
        int v = mc.optInt("percentageBasisPoints",
                mc.optInt("percentage_basis_points", 0));
        return Math.max(0, v);
    }

    /** 容量参考档位：[3H, 2H, 1H, 0H]（缺失填 0）。 */
    public long[] limits() {
        JSONObject limits = limitsObject();
        long three = limits.optLong("threeHeroLimit",
                limits.optLong("three_hero_limit", -1));
        long two = limits.optLong("twoHeroLimit", limits.optLong("two_hero_limit", -1));
        long one = limits.optLong("oneHeroLimit", limits.optLong("one_hero_limit", -1));
        long zero = limits.optLong("zeroHeroLimit", limits.optLong("zero_hero_limit", -1));
        if (three < 0 && two < 0 && one < 0 && zero < 0) {
            JSONObject mc = marchCapacity();
            three = mc.optLong("threeHeroCapacity", 0);
            two = mc.optLong("twoHeroCapacity", 0);
            one = mc.optLong("oneHeroCapacity", 0);
            zero = mc.optLong("zeroHeroCapacity", fixedCapacity());
        }
        return new long[]{Math.max(0, three), Math.max(0, two),
                Math.max(0, one), Math.max(0, zero)};
    }

    /** 服务端兜底的自动编队全局比例（和≠100 回退 50/20/30；缺省 50/20/30）。 */
    public int[] automaticSettings() {
        JSONObject s = raw().optJSONObject("automaticSettings");
        if (s == null) s = raw().optJSONObject("automatic_settings");
        int inf = 50, lanc = 20, mark = 30;
        if (s != null) {
            inf = clampPct(s.optInt("infantryRatio",
                    s.optInt("infantry_ratio", s.optInt("infantry", inf))));
            lanc = clampPct(s.optInt("lancerRatio",
                    s.optInt("lancer_ratio", s.optInt("lancer", lanc))));
            mark = clampPct(s.optInt("marksmanRatio",
                    s.optInt("marksman_ratio", s.optInt("marksman", mark))));
        }
        if (inf + lanc + mark != 100) return new int[]{50, 20, 30};
        return new int[]{inf, lanc, mark};
    }

    /** 游戏内编队（只读镜像；id<0，source=GAME）。 */
    public List<FormationModel> gameFormations() {
        List<FormationModel> out = new ArrayList<FormationModel>();
        JSONArray arr = raw().optJSONArray("gameFormations");
        if (arr == null) arr = raw().optJSONArray("game_formations");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            FormationModel m = FormationModel.fromJson(o);
            if (m == null) continue;
            m.source = FormationModel.SOURCE_GAME;
            if (m.id > 0) m.id = -m.id;
            if (m.id == 0) {
                long sourceId = o.optLong("sourceId", o.optLong("source_id", 0));
                if (sourceId > 0) m.id = -sourceId;
            }
            if (m.id == 0) continue;
            out.add(m);
        }
        return out;
    }

    /** 网页 collection 形状里的 {@code formations[]}（"我的编队"分页首屏，含 2 个基础内置）。 */
    public List<FormationModel> formations() {
        return FormationModel.parseArray(raw().optJSONArray("formations"));
    }

    /** 能力/容量提示（{@code marchCapacity} 原对象；缺失空对象）。 */
    public JSONObject marchCapacityMessage() {
        JSONObject mc = raw().optJSONObject("marchCapacity");
        return mc == null ? new JSONObject() : mc;
    }

    /** 雪猿宠物容量（{@code avalanche} 或网页 {@code avalancheCapacity}）。 */
    public JSONObject avalanche() {
        JSONObject a = raw().optJSONObject("avalanche");
        if (a == null) a = raw().optJSONObject("avalancheCapacity");
        if (a == null) a = raw().optJSONObject("avalanche_capacity");
        return a == null ? new JSONObject() : a;
    }

    /** 编队记忆状态（{@code memory:{daily,battle}}）。 */
    public JSONObject memoryStatus() {
        JSONObject m = raw().optJSONObject("memory");
        return m == null ? new JSONObject() : m;
    }

    /** 巨熊模板绑定（{@code bear:{applied,templateId,title,generation,tier,launchFormationId,joinFormationIds}}）。 */
    public JSONObject bearBinding() {
        JSONObject b = raw().optJSONObject("bear");
        return b == null ? new JSONObject() : b;
    }

    /** 目录里是否含至少一个英雄（旧口径 {@code hasCatalog}）。 */
    public boolean hasHeroes() {
        return !heroOptions().isEmpty();
    }

    // ==================== 内部 ====================

    private JSONObject marchCapacity() {
        JSONObject mc = raw().optJSONObject("marchCapacity");
        return mc == null ? new JSONObject() : mc;
    }

    private JSONObject limitsObject() {
        JSONObject l = raw().optJSONObject("limits");
        if (l != null) return l;
        JSONObject mc = marchCapacity();
        JSONObject inner = mc.optJSONObject("limits");
        return inner == null ? new JSONObject() : inner;
    }

    private static int clampPct(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    static long clampLong(BigInteger v) {
        if (v == null) return 0;
        if (v.compareTo(BigInteger.ZERO) < 0) return 0;
        if (v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) return Long.MAX_VALUE;
        return v.longValue();
    }
}
