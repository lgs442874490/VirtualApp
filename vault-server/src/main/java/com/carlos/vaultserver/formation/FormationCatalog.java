package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.Exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 编队目录（批次10）：从登录推送采集「英雄 / 兵种库存 / 出征容量」，供
 * {@link FormationResolver} 自动编队与 App/日报看板消费。
 *
 * <p>数据源（只读、不新增协议请求；与既有任务同口径）：
 * <ul>
 *   <li>{@code notify_hero_init}（实证形状 {@code {"50022.0":{id,name,lv,star,power,...},...}}）
 *       → {@link Hero} 列表；</li>
 *   <li>{@code notify_soldier_init} → 三兵种各档数量；对编队取值口径复用
 *       {@code Exec.baseSoldiers}（各兵种当前最高档可用量），兵种 id 用 {@code Exec.bestSoldierIds}；</li>
 *   <li>{@code notify_world_player_init.world.capacity} → 世界出征队列容量（同 ServerFieldTasks）。</li>
 * </ul>
 *
 * <p><b>目录快照 state 键型（冻结，供 App/日报消费）</b>：
 * {@code srv:formation:catalog:{fpid}:{sid}} = JSON：
 * <pre>
 * {"capturedAt":1792..., "capacity":5,
 *  "heroes":[{"id":50041,"name":"...","level":80,"star":8,"power":123456,"status":""}],
 *  "soldiers":{"infantry":20000,"lancer":1549,"marksman":28451,"best_ids":[11000,21000,31000]}}
 * </pre>
 */
public final class FormationCatalog {

    /** 目录快照 state 键前缀（SessionKeeper 周期采集写入；键型冻结）。 */
    public static final String STATE_KEY_PREFIX = "srv:formation:catalog:";

    /** 英雄目录条目（纯数据，无 Android 依赖）。 */
    public static final class Hero {
        public final long id;
        public final String name;
        public final long level;
        public final long star;
        public final long power;
        public final String status;

        Hero(long id, String name, long level, long star, long power, String status) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.level = level;
            this.star = star;
            this.power = power;
            this.status = status == null ? "" : status;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("level", level);
            m.put("star", star);
            m.put("power", power);
            m.put("status", status);
            return m;
        }
    }

    /** 一次采集的目录快照。 */
    public static final class Snapshot {
        public final long capturedAt;
        public final int capacity;
        public final List<Hero> heroes;
        public final long[] bestSoldierIds;
        public final Map<Long, Long> soldierCounts;
        public final long[] soldierStock;

        Snapshot(long capturedAt, int capacity, List<Hero> heroes, long[] bestSoldierIds,
                 Map<Long, Long> soldierCounts, long[] soldierStock) {
            this.capturedAt = capturedAt;
            this.capacity = capacity;
            this.heroes = heroes == null ? List.of() : List.copyOf(heroes);
            this.bestSoldierIds = bestSoldierIds == null ? new long[3] : bestSoldierIds;
            this.soldierCounts = soldierCounts == null ? Map.of() : soldierCounts;
            this.soldierStock = soldierStock == null ? new long[3] : soldierStock;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("capturedAt", capturedAt);
            root.put("capacity", capacity);
            List<Map<String, Object>> hs = new ArrayList<>();
            for (Hero h : heroes) hs.add(h.toMap());
            root.put("heroes", hs);
            Map<String, Object> sol = new LinkedHashMap<>();
            sol.put("infantry", soldierStock[0]);
            sol.put("lancer", soldierStock[1]);
            sol.put("marksman", soldierStock[2]);
            List<Long> ids = new ArrayList<>();
            ids.add(bestSoldierIds[0]);
            ids.add(bestSoldierIds[1]);
            ids.add(bestSoldierIds[2]);
            sol.put("best_ids", ids);
            root.put("soldiers", sol);
            return root;
        }
    }

    /** 每引擎一份缓存（WeakHashMap 防会话销毁后泄漏）。 */
    private static final Map<WjdrEngine, Snapshot> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FormationCatalog() {
    }

    /**
     * 采集/刷新目录（只读；幂等）。SessionKeeper online 后与周期采集一起调用。
     * 引擎为 null 时安全空操作。
     */
    public static void capture(WjdrEngine engine) {
        if (engine == null) return;
        try {
            Snapshot s = build(engine);
            if (s != null) CACHE.put(engine, s);
        } catch (Throwable ignored) {
        }
    }

    /** 取目录快照（未采集则即时采集一次；引擎 null 返回空快照）。 */
    public static Snapshot catalog(WjdrEngine engine) {
        if (engine == null) {
            return new Snapshot(System.currentTimeMillis(), 0, List.of(), new long[3], Map.of(), new long[3]);
        }
        Snapshot s = CACHE.get(engine);
        if (s == null) {
            capture(engine);
            s = CACHE.get(engine);
        }
        if (s == null) {
            s = new Snapshot(System.currentTimeMillis(), 0, List.of(), new long[3], Map.of(), new long[3]);
        }
        return s;
    }

    /** 目录快照（JSON 可序列化 Map；SessionKeeper 写 state 用）。 */
    public static Map<String, Object> snapshot(WjdrEngine engine) {
        return catalog(engine).toMap();
    }

    /** 全部英雄 id（按战力/等级/星级降序，即"强到弱"）。 */
    public static List<Long> heroIds(WjdrEngine engine) {
        List<Long> out = new ArrayList<>();
        for (Hero h : catalog(engine).heroes) out.add(h.id);
        return out;
    }

    /** 英雄目录（强到弱排序副本）。 */
    public static List<Hero> heroes(WjdrEngine engine) {
        List<Hero> out = new ArrayList<>(catalog(engine).heroes);
        out.sort(HERO_STRENGTH);
        return out;
    }

    /** 三兵种库存 [盾,矛,弓]（各兵种当前最高档可用量，同 Exec.baseSoldiers 口径）。 */
    public static long[] soldierStock(WjdrEngine engine) {
        return catalog(engine).soldierStock.clone();
    }

    /** 兵种 id → 数量（Exec.soldiers 口径；用于 payload k）。 */
    public static Map<Long, Long> soldierCounts(WjdrEngine engine) {
        return new LinkedHashMap<>(catalog(engine).soldierCounts);
    }

    /** 三兵种最高档 id [盾,矛,弓]（同 Exec.bestSoldierIds）。 */
    public static long[] bestSoldierIds(WjdrEngine engine) {
        return catalog(engine).bestSoldierIds.clone();
    }

    /** 世界出征队列容量（取不到=0，调用方按"未知"保守处理）。 */
    public static int capacity(WjdrEngine engine) {
        return catalog(engine).capacity;
    }

    /** 单测：清空指定引擎缓存。 */
    public static void reset(WjdrEngine engine) {
        if (engine != null) CACHE.remove(engine);
    }

    /** 英雄强度排序：power > level > star > id（越大越强）。 */
    static final Comparator<Hero> HERO_STRENGTH = (a, b) -> {
        int c = Long.compare(b.power, a.power);
        if (c != 0) return c;
        c = Long.compare(b.level, a.level);
        if (c != 0) return c;
        c = Long.compare(b.star, a.star);
        if (c != 0) return c;
        return Long.compare(b.id, a.id);
    };

    // ------------------------------------------------------------------
    // 采集实现（防御式；推送缺失=空目录，绝不抛异常）
    // ------------------------------------------------------------------

    private static Snapshot build(WjdrEngine engine) {
        long now = System.currentTimeMillis();
        int capacity = worldCapacity(engine);
        List<Hero> heroes = heroesOf(engine.notifyData("notify_hero_init"));
        Map<Long, Long> soldiers = Exec.soldiers(engine);
        long[] bestIds = Exec.bestSoldierIds(soldiers);
        long[] stock = baseStock(soldiers, bestIds);
        return new Snapshot(now, capacity, heroes, bestIds, soldiers, stock);
    }

    /** 与 Exec.baseSoldiers 同口径：各兵种最高档 id 的可用数量（不重复计入低档，防超发）。 */
    static long[] baseStock(Map<Long, Long> soldiers, long[] bestIds) {
        long[] out = new long[3];
        if (soldiers == null) return out;
        for (Map.Entry<Long, Long> e : soldiers.entrySet()) {
            long id = e.getKey() == null ? 0L : e.getKey();
            long cnt = e.getValue() == null ? 0L : e.getValue();
            if (id <= 0 || cnt <= 0) continue;
            int line = (int) (id / 10000);
            if (line < 1 || line > 3) continue;
            if (bestIds != null && bestIds[line - 1] > 0 && id == bestIds[line - 1]) {
                out[line - 1] += cnt;
            }
        }
        // 兜底：bestIds 缺失/未命中（老数据）→ 按线累计全部档位
        long sum = out[0] + out[1] + out[2];
        if (sum <= 0) {
            for (Map.Entry<Long, Long> e : soldiers.entrySet()) {
                long id = e.getKey() == null ? 0L : e.getKey();
                long cnt = e.getValue() == null ? 0L : e.getValue();
                int line = (int) (id / 10000);
                if (id > 0 && cnt > 0 && line >= 1 && line <= 3) out[line - 1] += cnt;
            }
        }
        return out;
    }

    /**
     * 解析英雄推送（防御式，支持 {@code notify_hero_init} 三种实证形状）：
     * ① {@code {"50022.0":{id,name,lv,...}}}；② {@code {"data":[{id,...}]}}；
     * ③ {@code {"heros":{...}}}。字段名兼容 name/nickName、level/lv、star/star_lv、power/fight_num。
     */
    @SuppressWarnings("unchecked")
    static List<Hero> heroesOf(Map<String, Object> heroInit) {
        List<Hero> out = new ArrayList<>();
        if (heroInit == null) return out;
        Object data = heroInit.get("data");
        if (data == null) data = heroInit.get("heros");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (data instanceof List) {
            for (Object o : (List<?>) data) {
                Map<String, Object> m = Exec.mapOf(o);
                if (m != null) rows.add(m);
            }
        } else if (data instanceof Map) {
            for (Object o : ((Map<?, ?>) data).values()) {
                Map<String, Object> m = Exec.mapOf(o);
                if (m != null) rows.add(m);
            }
            if (rows.isEmpty()) rows.add(heroInit);
        } else {
            for (Object o : heroInit.values()) {
                Map<String, Object> m = Exec.mapOf(o);
                if (m != null) rows.add(m);
            }
        }
        for (Map<String, Object> m : rows) {
            Hero h = heroOf(m);
            if (h != null) out.add(h);
        }
        out.sort(HERO_STRENGTH);
        return out;
    }

    private static Hero heroOf(Map<String, Object> m) {
        if (m == null) return null;
        long id = Exec.longOf(m.get("id"));
        if (id <= 0) id = Exec.longOf(m.get("hero_id"));
        if (id <= 0) return null;
        String name = str(m, "name", "nickName", "nick_name");
        long level = firstLong(m, "level", "lv");
        long star = firstLong(m, "star", "star_lv", "starLevel");
        long power = firstLong(m, "power", "fight_num", "fight_capacity");
        String status = str(m, "status", "state");
        return new Hero(id, name, level, star, power, status);
    }

    private static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return String.valueOf(v);
        }
        return "";
    }

    private static long firstLong(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                long n = Exec.longOf(v);
                if (n != 0) return n;
            }
        }
        return 0L;
    }

    /** 世界出征容量：{@code notify_world_player_init.world.capacity}（同 ServerFieldTasks 口径；取不到 0）。 */
    static int worldCapacity(WjdrEngine engine) {
        try {
            Map<String, Object> wpi = engine.notifyData("notify_world_player_init");
            if (wpi == null) return 0;
            Map<String, Object> world = Exec.mapOf(wpi.get("world"));
            long cap = world == null ? 0L : Exec.longOf(world.get("capacity"));
            if (cap <= 0) cap = Exec.longOf(wpi.get("capacity"));
            return cap > 0 && cap <= 12 ? (int) cap : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
