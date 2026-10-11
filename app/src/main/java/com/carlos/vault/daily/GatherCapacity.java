package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出征容量估算（对标原版 MarchCapacityState/MarchCapacityCatalog，数值表逐项移植）。
 *
 * <p>公式（原版同款）：容量 = (Σ英雄容量 + 固定容量) × (10000 + 万分比加成) / 10000。
 * 固定容量 = 500 基础 + 指挥中心(331) + 科技 + 专家(7004) + 家园(生命树7000/容量建筑7036)
 * + 临时 buff（10506 加/10507 减），万分比 = Σ buff 10508。
 *
 * <p>v1 边界：领主装备/官职/专家等级（原版有、号库通知数据未接）暂记 0，容量略低估——
 * 宁低勿高，推荐兵量按"至少带这么多"语义安全。
 */
public final class GatherCapacity {

    private static final long[] HERO_CAPACITIES = {65, 140, 220, 305, 400, 500, 605, 720, 840, 970,
            1100, 1240, 1390, 1540, 1700, 1870, 2040, 2225, 2410, 2605, 2805, 3010, 3225, 3445,
            3670, 3905, 4145, 4390, 4645, 4905, 5175, 5445, 5715, 6015, 6310, 6600, 6895, 7190,
            7480, 7775, 8070, 8365, 8655, 8950, 9245, 9540, 9830, 10125, 10420, 10685, 10925,
            11140, 11340, 11525, 11700, 11860, 12010, 12140, 12260, 12370, 12470, 12560, 12650,
            12730, 12800, 12870, 12930, 12980, 13030, 13070, 13110, 13150, 13190, 13230, 13270,
            13310, 13350, 13390, 13430, 13470};
    private static final long[] COMMAND_CENTER_CAPACITIES = {400, 700, 1000, 1400, 1800, 2800,
            3800, 5000, 6500, 8000, 10500, 13000, 16000, 19000, 22000, 27000, 32000, 34000,
            36000, 38000, 40500, 43000, 46000, 48500, 52000, 54500, 57000, 60500, 64000, 67000,
            67700, 68400, 69100, 69800, 70500, 71200, 71900, 72600, 73300, 74000, 74700, 75400,
            76100, 76800, 77500, 78200, 78900, 79600, 80300, 81000, 81700, 82400, 83100, 83800,
            84500, 85200, 85900, 86600, 87300, 88000, 88700, 89400, 90100, 90800, 91500, 92200,
            92900, 93600, 94300, 95000, 95700, 96400, 97100, 97800, 98500, 99200, 99900, 100600,
            101300, 102000};
    private static final long[] EXPERT_CAPACITIES = {300, 600, 1000, 1500, 2000, 3000, 4000,
            5500, 7000, 8500, 10000};
    /** 普通科技：基 tid → 每级容量。 */
    private static final Map<Long, long[]> NORMAL_RESEARCH = new LinkedHashMap<>();
    /** 火晶/淬炎科技：基 tid → 每级 200×5。 */
    private static final Map<Long, long[]> SPAR_RESEARCH = new LinkedHashMap<>();
    /** 淬炼科技：基 tid → 600×5 或 每级 15 档。 */
    private static final Map<Long, long[]> REFINE_RESEARCH = new LinkedHashMap<>();

    static {
        NORMAL_RESEARCH.put(3014L, new long[]{320, 640, 1000});
        NORMAL_RESEARCH.put(3037L, new long[]{320, 640, 1400});
        NORMAL_RESEARCH.put(3057L, new long[]{620, 1200, 1800, 2800});
        NORMAL_RESEARCH.put(3080L, new long[]{990, 1900, 2900, 3900, 5600});
        NORMAL_RESEARCH.put(3100L, new long[]{1200, 2400, 3600, 4800, 6000, 8000});
        NORMAL_RESEARCH.put(3123L, new long[]{2000, 4000, 6000, 8000, 10000, 13400});
        for (long tid : new long[]{7001L, 8001L, 9001L}) SPAR_RESEARCH.put(tid, fiveLevels(200));
        for (long tid : new long[]{7010L, 8010L, 9010L}) SPAR_RESEARCH.put(tid, tenLevels());
        for (long tid : new long[]{10001L, 11001L, 12001L}) REFINE_RESEARCH.put(tid, fiveLevels(600));
        for (long tid : new long[]{10016L, 11016L, 12016L}) REFINE_RESEARCH.put(tid, fifteenLevels());
    }

    private static long[] fiveLevels(long per) {
        return new long[]{per, per * 2, per * 3, per * 4, per * 5};
    }

    private static long[] tenLevels() {
        return new long[]{500, 1100, 1800, 2600, 3500, 4500, 5600, 6800, 8100, 9500};
    }

    private static long[] fifteenLevels() {
        return new long[]{600, 1300, 2100, 3000, 4000, 5100, 6300, 7600, 9000, 10500, 12100,
                13800, 15600, 17500, 19500};
    }

    private static long levelValue(long[] table, long level) {
        if (level <= 0 || table.length == 0) return 0;
        return table[(int) Math.min(level, table.length) - 1];
    }

    public static long heroCapacity(long level) {
        return levelValue(HERO_CAPACITIES, level);
    }

    private static long commandCenterCapacity(long level) {
        return levelValue(COMMAND_CENTER_CAPACITIES, level);
    }

    private static long researchCapacity(long baseTid, long level) {
        if (level <= 0) return 0;
        long[] t = NORMAL_RESEARCH.get(baseTid);
        if (t != null) return levelValue(t, level);
        t = SPAR_RESEARCH.get(baseTid);
        if (t != null) return levelValue(t, level);
        t = REFINE_RESEARCH.get(baseTid);
        if (t != null) return levelValue(t, level);
        return 0;
    }

    private static long homelandCapacity(long lifeTreeLevel, long capacityBuildingLevel) {
        long tree = lifeTreeLevel >= 8 ? 4000 : (lifeTreeLevel >= 2 ? 1000 : 0);
        return tree + Math.min(Math.max(capacityBuildingLevel, 0), 5) * 200;
    }

    private static long calculate(long heroCapacity, long fixedCapacity, long bp) {
        long subtotal = Math.max(0, heroCapacity + fixedCapacity);
        long multiplier = Math.max(0, 10000 + bp);
        return Math.floorDiv(subtotal * multiplier, 10000L);
    }

    /** 容量估算结果。 */
    public static final class Estimate {
        public final boolean ready;          // 英雄数据是否足够（缺失时 capacity 是固定容量近似）
        public final long heroCapacity;      // 指定英雄的容量合计（不含固定）
        public final long fixedCapacity;     // 固定容量（含 500 基础）
        public final long bp;                // 万分比加成
        public final long capacity;          // 最终容量
        public final String note;            // 缺数据说明

        Estimate(boolean ready, long heroCapacity, long fixedCapacity, long bp, String note) {
            this.ready = ready;
            this.heroCapacity = heroCapacity;
            this.fixedCapacity = fixedCapacity;
            this.bp = bp;
            this.capacity = calculate(heroCapacity, fixedCapacity, bp);
            this.note = note;
        }
    }

    /**
     * 估算指定英雄阵容的单队出征容量（输入全部来自 notify 缓存，缺啥少啥都会在 note 里说明）。
     *
     * @param heroIds 出征英雄 id（空=按三职业最大英雄近似）
     */
    public static Estimate estimateSquad(WjdrEngine engine, List<Long> heroIds) {
        boolean heroOk = false;
        long heroCap = 0;
        Map<Long, Long> levels = heroLevels(engine);
        for (Long id : heroIds == null ? new ArrayList<Long>() : heroIds) {
            Long lv = levels.get(id);
            if (lv != null) {
                heroCap += heroCapacity(lv);
                heroOk = true;
            }
        }
        Totals t = totals(engine);
        String note = (heroOk ? "" : "英雄等级未同步；") + t.note;
        return new Estimate(heroOk, heroCap, t.fixed, t.bp, note);
    }

    /** 全英雄满编近似：三职业各取最大英雄容量累加后套固定/bp（原版 snapshot 同款口径）。 */
    public static Estimate estimateFull(WjdrEngine engine) {
        Map<Long, Long> levels = heroLevels(engine);
        // 三职业近似：英雄 id 十位=职业（1盾/2矛/3弓），取每职业最大英雄容量
        Map<Long, Long> byProfession = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : levels.entrySet()) {
            long profession = (e.getKey() / 10) % 10;
            byProfession.merge(profession, heroCapacity(e.getValue()), Math::max);
        }
        boolean heroOk = !levels.isEmpty();
        long heroCap = 0;
        for (Long c : byProfession.values()) heroCap += c;
        Totals t = totals(engine);
        String note = (heroOk ? "" : "英雄等级未同步；") + t.note;
        return new Estimate(heroOk, heroCap, t.fixed, t.bp, note);
    }

    // ---------- 输入提取（全部防御式，缺数据记 note） ----------

    /** heroId → 有效等级（lev 与 training_lev 取大）。 */
    private static Map<Long, Long> heroLevels(WjdrEngine engine) {
        Map<Long, Long> out = new LinkedHashMap<>();
        Map<String, Object> hi = engine.notifyData("notify_hero_init");
        if (hi == null) return out;
        for (Map<String, Object> m : ExecUtil.mapsOf(hi.get("data"))) {
            long id = ExecUtil.longOf(m.get("id"));
            long lev = Math.max(ExecUtil.longOf(m.get("lev")), ExecUtil.longOf(m.get("training_lev")));
            if (id > 0 && lev > 0) out.put(id, lev);
        }
        return out;
    }

    private static final class Totals {
        final long fixed;
        final long bp;
        final String note;

        Totals(long fixed, long bp, String note) {
            this.fixed = fixed;
            this.bp = bp;
            this.note = note;
        }
    }

    private static Totals totals(WjdrEngine engine) {
        StringBuilder missing = new StringBuilder();
        long fixed = 500L;   // 基础（原版同款）

        // 指挥中心 331（notify_shelter_info.building_info["331"].base.lv / .lv）
        long ccLevel = 0;
        Map<String, Object> shelter = engine.notifyData("notify_shelter_info");
        Object bi = shelter == null ? null : shelter.get("building_info");
        if (bi instanceof Map) {
            Object cc = ((Map<?, ?>) bi).get("331");
            if (cc instanceof Map && ((Map<?, ?>) cc).get("base") instanceof Map) {
                cc = ((Map<?, ?>) cc).get("base");
            }
            if (cc instanceof Map) ccLevel = ExecUtil.longOf(((Map<?, ?>) cc).get("lv"));
        }
        if (ccLevel > 0) fixed += commandCenterCapacity(ccLevel);
        else missing.append("指挥中心等级未同步；");

        // 科技（notify_all_research.res/researched：{tid:lev} 或 [{tid,lev}]）
        int researchFound = 0;
        Map<String, Object> ar = engine.notifyData("notify_all_research");
        Object researched = ar == null ? null
                : (ar.get("res") != null ? ar.get("res") : ar.get("researched"));
        if (researched instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) researched).entrySet()) {
                long cap = researchCapacity(ExecUtil.longOf(e.getKey()), ExecUtil.longOf(e.getValue()));
                if (cap > 0) {
                    fixed += cap;
                    researchFound++;
                }
            }
        } else if (researched instanceof List) {
            for (Object o : (List<?>) researched) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> row = (Map<?, ?>) o;
                long cap = researchCapacity(ExecUtil.longOf(row.get("tid")), ExecUtil.longOf(row.get("lev")));
                if (cap > 0) {
                    fixed += cap;
                    researchFound++;
                }
            }
        }
        if (researchFound == 0) missing.append("科技等级未同步；");

        // 家园：生命树 7000 + 容量建筑 7036（TownTasks.homelandBuildings 复用）
        Map<String, Object> hi = engine.notifyData("notify_homeland_info");
        if (hi != null) {
            long treeLevel = 0, capBld = 0;
            for (Map<String, Object> b : TownTasks.homelandBuildings(hi)) {
                long bid = TownTasks.numIn(b, "bid");
                if (bid == 7000L) treeLevel = TownTasks.numIn(b, "level");
                if (bid == 7036L) capBld = TownTasks.numIn(b, "level");
            }
            fixed += homelandCapacity(treeLevel, capBld);
        } else {
            missing.append("家园等级未同步；");
        }

        // 临时 buff（10506 加/10507 减/10508 万分比）
        long bp = 0;
        Map<String, Object> buffShow = engine.notifyData("notify_buff_show");
        if (buffShow != null) {
            for (Map<String, Object> b : ExecUtil.mapsOf(buffShow.get("buffs"))) {
                Map<String, Object> bm = ExecUtil.mapOf(b.get("buff_map"));
                if (bm == null) continue;
                fixed += ExecUtil.longOf(bm.get("10506")) - ExecUtil.longOf(bm.get("10507"));
                bp += ExecUtil.longOf(bm.get("10508"));
            }
        }
        return new Totals(fixed, bp, missing.toString());
    }

    /** 采集英雄推荐提示（数值来自英雄详情页真机核对；后续可扩成 catalog 文件）。 */
    public static final String GATHER_HERO_HINT =
            "采集推荐：查理（全资源，技能满级+95%）、史密斯（铁矿+85%）";

    private GatherCapacity() {
    }
}
