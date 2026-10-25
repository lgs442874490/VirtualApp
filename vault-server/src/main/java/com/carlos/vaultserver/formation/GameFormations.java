package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.Exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 游戏内编队（{@code notify_soldier_init.prefab}）解析 —— 契约 §0/§3.5，服务端审计 P1-3。
 *
 * <p><b>真实帧形状（生产账号登录推送 + spb schema + release288 反编译三方一致）</b>：
 * <pre>
 * notify_soldier_init = {
 *   formation: [{id, soldier:[{id,count,minor_wound}], hero_id:[{k,v}]}],   // 当前出征编队（不落目录）
 *   soldier:  [{id,count}],                                                // 城内兵力（Exec.soldiers 口径）
 *   prefab:   [{id, prefab:{ hero_id:[{k,v}], soldier:[{k,v}] | formation_rate:[{k,v}],
 *                         formation_name:string }}],                      // 游戏内 6 个编队预设槽
 *   auto_join_prefab: {hero_id:[{k,v}], soldier:[{k,v}]}
 * }
 * </pre>
 * 槽语义（{@code BehemothAccountStateFormationSlot.from}）：{@code hero_id} 的 k=上阵位次、
 * v=英雄 id；{@code soldier} 的 k=兵种 id、v=数量；{@code formation_rate} 的 k=1/2/3（盾/矛/弓）
 * v=比例；{@code formation_name} 空 → 显示名回退「编队 {id}」；三类都空的槽丢弃。
 *
 * <p>增量：{@code notify_soldier_formation_update = {id, formation:{id, soldier:[...], hero_id:[...]}}}
 * —— 命中已有槽时更新英雄/兵力；未命中且带有效英雄时追加（防御式，形状以生产为准）。
 *
 * <p>下发口径（契约 §3.1/§3.2，App {@code FormationModel} 同构）：id=-slotId、source=GAME、
 * sourceId=slotId、captainHeroId=heroIds[0]、有 soldier → MANUAL、有 formation_rate → PERCENTAGE。
 *
 * <p>纯 java.util（不依赖 Android）；输入为 {@code engine.notifyData("notify_soldier_init")}
 * 已解包内容或原始 content 均可（自动剥 data/info/content 包装）。
 */
public final class GameFormations {

    /** 展示名回退前缀（与网页版/旧版逐字一致）。 */
    public static final String DEFAULT_NAME_PREFIX = "编队 ";
    /** 编队内英雄上限（盾/矛/弓各一）。 */
    public static final int HERO_MAX = 3;

    /** 一个游戏编队槽（不可变）。 */
    public static final class Slot {
        /** 游戏内槽位序号（1..N；契约里下发 id = -slotId）。 */
        public final int id;
        /** 显示名（formation_name 为空时回退「编队 {id}」）。 */
        public final String name;
        /** 原始 formation_name（未回退；恢复 payload 只在非空时写回）。 */
        public final String rawName;
        /** 上阵英雄 id（按 k 升序；≤3）。 */
        public final List<Long> heroIds;
        /** 士兵 id → 保存数量（soldier 形状）。 */
        public final Map<Long, Long> soldierCounts;
        /** 比例 [盾,矛,弓]（formation_rate 形状；缺失 0）。 */
        public final int[] ratios;
        public final boolean hasSoldiers;
        public final boolean hasRates;

        Slot(int id, String rawName, List<Long> heroIds, Map<Long, Long> soldierCounts,
             int[] ratios) {
            this.id = id;
            this.rawName = rawName == null ? "" : rawName.trim();
            this.name = this.rawName.isEmpty() ? DEFAULT_NAME_PREFIX + id : this.rawName;
            List<Long> heroes = new ArrayList<>();
            Set<Long> uniq = new LinkedHashSet<>();
            if (heroIds != null) {
                for (Long h : heroIds) {
                    if (h == null || h <= 0L || heroes.size() >= HERO_MAX) continue;
                    if (uniq.add(h)) heroes.add(h);
                }
            }
            this.heroIds = List.copyOf(heroes);
            Map<Long, Long> soldiers = new LinkedHashMap<>();
            if (soldierCounts != null) {
                for (Map.Entry<Long, Long> e : soldierCounts.entrySet()) {
                    if (e.getKey() != null && e.getKey() > 0L && e.getValue() != null && e.getValue() > 0L) {
                        soldiers.put(e.getKey(), e.getValue());
                    }
                }
            }
            this.soldierCounts = Map.copyOf(soldiers);
            this.hasSoldiers = !this.soldierCounts.isEmpty();
            this.ratios = ratios == null ? new int[3] : ratios;
            this.hasRates = this.ratios[0] > 0 || this.ratios[1] > 0 || this.ratios[2] > 0;
        }

        /** 空槽（英雄/兵力/比例三者皆空；记忆时跳过、目录里丢弃）。 */
        public boolean isEmpty() {
            return heroIds.isEmpty() && soldierCounts.isEmpty() && !hasRates;
        }

        /** 分配方式：有士兵数量 → MANUAL；否则有比例 → PERCENTAGE；缺省 MANUAL。 */
        public String allocationMode() {
            if (hasSoldiers) return "MANUAL";
            if (hasRates) return "PERCENTAGE";
            return "MANUAL";
        }

        /** 三兵种数量（soldier id 按十万位归兵种：1/2/3 盾/矛/弓）。 */
        public long[] soldiersByLine() {
            long[] out = new long[3];
            for (Map.Entry<Long, Long> e : soldierCounts.entrySet()) {
                int line = (int) (e.getKey() / 10000L);
                if (line >= 1 && line <= 3) out[line - 1] += e.getValue();
            }
            return out;
        }

        public long totalSoldiers() {
            long total = 0L;
            for (long v : soldiersByLine()) total += v;
            return total;
        }

        /** 契约 §3.1 的 gameFormations[] 条目（source/availability 由 FormationCatalog 补）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", -(long) id);
            m.put("source", "GAME");
            m.put("sourceId", (long) id);
            m.put("name", name);
            List<Long> heroes = new ArrayList<>(heroIds);
            m.put("heroIds", heroes);
            if (!heroIds.isEmpty()) m.put("captainHeroId", heroIds.get(0));
            m.put("allocationMode", allocationMode());
            long[] counts = soldiersByLine();
            Map<String, Object> soldiers = new LinkedHashMap<>();
            soldiers.put("infantry", counts[0]);
            soldiers.put("lancer", counts[1]);
            soldiers.put("marksman", counts[2]);
            m.put("soldiers", soldiers);
            Map<String, Object> ratio = new LinkedHashMap<>();
            ratio.put("infantry", ratios[0]);
            ratio.put("lancer", ratios[1]);
            ratio.put("marksman", ratios[2]);
            m.put("ratio", ratio);
            m.put("totalSoldiers", totalSoldiers());
            m.put("builtIn", false);
            m.put("version", 0L);
            return m;
        }

        /**
         * 记忆快照里的槽（契约 §3.2 {@code slots[]}）：除展示字段外额外带 {@code prefab}
         * （恢复时要把原始 soldier id 逐槽发回游戏，展示用的三兵种汇总不够）。
         */
        public Map<String, Object> toMemorySlot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (long) id);
            m.put("name", name);
            List<Long> heroes = new ArrayList<>(heroIds);
            m.put("heroIds", heroes);
            if (!heroIds.isEmpty()) m.put("captainHeroId", heroIds.get(0));
            m.put("allocationMode", allocationMode());
            long[] counts = soldiersByLine();
            Map<String, Object> soldiers = new LinkedHashMap<>();
            soldiers.put("infantry", counts[0]);
            soldiers.put("lancer", counts[1]);
            soldiers.put("marksman", counts[2]);
            m.put("soldiers", soldiers);
            Map<String, Object> ratio = new LinkedHashMap<>();
            ratio.put("infantry", ratios[0]);
            ratio.put("lancer", ratios[1]);
            ratio.put("marksman", ratios[2]);
            m.put("ratio", ratio);
            m.put("prefab", prefab());
            return m;
        }

        /**
         * 恢复 payload 的内层 prefab（{@code req_soldier_set_prefab} 的
         * {@code prefab.prefab}）：hero_id 恒写，soldier / formation_rate 二选一（非空才写），
         * formation_rate 补齐 k=1/2/3（旧版 completeFormationRates 同口径），formation_name 非空才写。
         */
        public Map<String, Object> prefab() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("hero_id", heroPairs(heroIds));
            if (hasSoldiers) {
                List<Map<String, Object>> pairs = new ArrayList<>();
                for (Map.Entry<Long, Long> e : soldierCounts.entrySet()) {
                    pairs.add(Exec.kv(e.getKey(), e.getValue()));
                }
                p.put("soldier", pairs);
            }
            if (hasRates) {
                List<Map<String, Object>> rates = new ArrayList<>();
                rates.add(Exec.kv(1L, ratios[0]));
                rates.add(Exec.kv(2L, ratios[1]));
                rates.add(Exec.kv(3L, ratios[2]));
                p.put("formation_rate", rates);
            }
            if (!rawName.isEmpty()) p.put("formation_name", rawName);
            return p;
        }
    }

    private GameFormations() {
    }

    /**
     * 解析 {@code notify_soldier_init} 的 prefab 槽列表。
     *
     * @return {@code null}=该帧没有 prefab 字段（保持旧缓存）；空列表=帧里有 prefab 但全空槽（清空）
     */
    public static List<Slot> parse(Map<String, Object> soldierInit) {
        if (soldierInit == null) return null;
        Map<String, Object> content = unwrap(soldierInit);
        if (!content.containsKey("prefab")) return null;
        return parsePrefab(content.get("prefab"));
    }

    /** 解析任意 prefab 值（List / Map / 空）。 */
    public static List<Slot> parsePrefab(Object raw) {
        List<Slot> out = new ArrayList<>();
        for (Map<String, Object> entry : Exec.mapsOf(raw)) {
            Slot slot = fromEntry(entry);
            if (slot != null && !slot.isEmpty()) out.add(slot);
        }
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    /** 解析单个 {@code {id, prefab:{...}}} 槽条目。 */
    static Slot fromEntry(Map<String, Object> entry) {
        if (entry == null) return null;
        int id = (int) Exec.longOf(entry.get("id"));
        if (id <= 0) return null;
        Map<String, Object> prefab = Exec.mapOf(entry.get("prefab"));
        if (prefab == null) return null;
        return fromPrefab(id, prefab);
    }

    /** 由槽 id + 内层 prefab 映射构造槽。 */
    @SuppressWarnings("unchecked")
    public static Slot fromPrefab(int id, Map<String, Object> prefab) {
        if (prefab == null) return null;
        // hero_id: [{k:位次,v:英雄id}]（k 升序）
        TreeMap<Long, Long> heroByPos = new TreeMap<>();
        for (Map<String, Object> m : Exec.mapsOf(prefab.get("hero_id"))) {
            long k = Exec.longOf(m.get("k"));
            long v = Exec.longOf(m.get("v"));
            if (k > 0L && v > 0L) heroByPos.put(k, v);
        }
        List<Long> heroes = new ArrayList<>(heroByPos.values());
        // soldier: [{k:兵种id,v:数量}]
        Map<Long, Long> soldiers = new LinkedHashMap<>();
        for (Map<String, Object> m : Exec.mapsOf(prefab.get("soldier"))) {
            long k = Exec.longOf(m.get("k"));
            long v = Math.max(0L, Exec.longOf(m.get("v")));
            if (k > 0L && v > 0L) soldiers.put(k, v);
        }
        // formation_rate: [{k:1|2|3, v:比例}]
        int[] ratios = new int[3];
        for (Map<String, Object> m : Exec.mapsOf(prefab.get("formation_rate"))) {
            long k = Exec.longOf(m.get("k"));
            long v = Exec.longOf(m.get("v"));
            if (k >= 1L && k <= 3L) {
                ratios[(int) k - 1] = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, v));
            }
        }
        String name = prefab.get("formation_name") == null ? ""
                : String.valueOf(prefab.get("formation_name"));
        return new Slot(id, com.carlos.vault.wjdr.asset.HeroCatalog.cleanName(name), heroes,
                soldiers, ratios);
    }

    /**
     * 增量：{@code notify_soldier_formation_update = {id, formation:{id, soldier[], hero_id[]}}}。
     *
     * @return 更新后的槽列表（不可变副本）；无法解析时返回入参原样
     */
    public static List<Slot> applyUpdate(List<Slot> current, Map<String, Object> content) {
        if (content == null) return current;
        Map<String, Object> body = unwrap(content);
        Map<String, Object> formation = Exec.mapOf(body.get("formation"));
        if (formation == null) return current;
        int id = (int) Exec.longOf(body.get("id"));
        if (id <= 0) id = (int) Exec.longOf(formation.get("id"));
        if (id <= 0) return current;
        TreeMap<Long, Long> heroByPos = new TreeMap<>();
        for (Map<String, Object> m : Exec.mapsOf(formation.get("hero_id"))) {
            long k = Exec.longOf(m.get("k"));
            long v = Exec.longOf(m.get("v"));
            if (k > 0L && v > 0L) heroByPos.put(k, v);
        }
        Map<Long, Long> soldiers = new LinkedHashMap<>();
        // 该推送的 soldier 元素是 formation_soldier{id,count}（不是 {k,v}）——两种都兼容
        for (Map<String, Object> m : Exec.mapsOf(formation.get("soldier"))) {
            long sid = Exec.longOf(m.get("k"));
            if (sid <= 0L) sid = Exec.longOf(m.get("id"));
            long count = Math.max(0L, Exec.longOf(m.get("v")));
            if (count <= 0L && m.get("count") != null) count = Math.max(0L, Exec.longOf(m.get("count")));
            if (sid > 0L && count > 0L) soldiers.put(sid, count);
        }
        List<Long> heroes = new ArrayList<>(heroByPos.values());
        if (heroes.isEmpty() && soldiers.isEmpty()) return current;
        List<Slot> out = new ArrayList<>();
        boolean replaced = false;
        for (Slot slot : current == null ? List.<Slot>of() : current) {
            if (slot.id == id) {
                // 增量缺字段时保留旧值（推送只带变化部分）
                List<Long> mergedHeroes = heroes.isEmpty() ? slot.heroIds : heroes;
                Map<Long, Long> mergedSoldiers = soldiers.isEmpty() ? slot.soldierCounts : soldiers;
                out.add(new Slot(slot.id, slot.rawName, mergedHeroes, mergedSoldiers, slot.ratios));
                replaced = true;
            } else {
                out.add(slot);
            }
        }
        if (!replaced) out.add(new Slot(id, "", heroes, soldiers, new int[3]));
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return List.copyOf(out);
    }

    /** 英雄 id → 位次对（k=1..n，跳过非正 id）。 */
    public static List<Map<String, Object>> heroPairs(List<Long> heroIds) {
        return Exec.heroPairs(heroIds);
    }

    /** 剥掉 {data}/{info}/{content} 包装（引擎 addMessageHook 给的是原始 content）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unwrap(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        for (String k : new String[]{"data", "info", "content"}) {
            Object v = raw.get(k);
            if (v instanceof Map) {
                Map<String, Object> inner = (Map<String, Object>) v;
                // 只有内层看起来是推送主体时才继续下钻（防止把业务字段 data 当包装）
                if (inner.containsKey("prefab") || inner.containsKey("soldier")
                        || inner.containsKey("formation")
                        || inner.containsKey("hero_id")) {
                    return inner;
                }
            }
        }
        return raw;
    }

    /** notify_soldier_init 原始帧里是否有 prefab（供 FormationCatalog 判断是否覆盖旧缓存）。 */
    public static boolean hasPrefab(WjdrEngine engine) {
        if (engine == null) return false;
        Map<String, Object> init = engine.notifyData("notify_soldier_init");
        if (init == null) return false;
        return unwrap(init).containsKey("prefab");
    }
}
