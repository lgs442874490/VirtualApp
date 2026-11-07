package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.FileLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建筑门槛目录（M4a 任务书 §2.1）：解析 classpath {@code bootstrap/building-gate-catalog.json}，
 * 承载升级经验门槛（needExp）、家具门槛（furniture）、家具成本/经验/上限。
 *
 * <p>数据结构与原版 wjdr-tool {@code config/building-gate-catalog.json} 同源：
 * <pre>
 * buildings = { "301": { id, name(空), maxLevel, levels: [ { level, needExp,
 *              furniture: [ {id, amount} ], prerequisites: [ {id, amount} ] } ] }, ... }
 * furniture = { "101:1": { exp, costs: [ {id, amount} ] }, ... }
 * </pre>
 *
 * <p>加载风格与容错对齐 {@code TownDevCatalog.get()}：懒加载单例，解析失败降级为空目录
 * （supports 全 false），只记 warn 日志不抛异常。建筑名称/成本/前置以 {@code TownDevCatalog}
 * 为准（见 {@link BootstrapCatalog}），本目录只提供门槛侧查询。
 */
public final class BuildingGateCatalog {

    /**
     * 通用成本条目（道具 id + 数量）。
     *
     * <p>任务书允许复用 {@code TownDevCatalog.Cost}，但其构造器为 task 包私有、bootstrap 包
     * 无法实例化，故本包内自建同形 record（字段语义与原版 DailyTownDevelopmentCatalog.Cost 一致）。
     */
    public record Cost(long id, long amount) {
    }

    /** 建筑前置条目（前置建筑 id + 需求等级）。 */
    public record Prerequisite(long id, long amount) {
    }

    /** 建筑单级门槛：等级 + 经验门槛 + 家具门槛 + 前置（gate json 自带前置仅作兜底）。 */
    public record GateLevel(long level, long needExp, List<Cost> furniture,
                            List<Prerequisite> prerequisites) {
        public GateLevel {
            furniture = furniture == null ? List.of() : List.copyOf(furniture);
            prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        }
    }

    /** 家具单级定义：经验值 + 升级成本。 */
    public record FurnitureLevel(long exp, List<Cost> costs) {
        public FurnitureLevel {
            costs = costs == null ? List.of() : List.copyOf(costs);
        }
    }

    /** gate 建筑定义：id、名称（数据为空，名称以 TownDevCatalog 为准）、最大等级、各级门槛。 */
    public static final class GateBuilding {
        public final long id;
        public final long maxLevel;
        public final Map<Long, GateLevel> levels = new LinkedHashMap<>();

        GateBuilding(long id, long maxLevel) {
            this.id = id;
            this.maxLevel = maxLevel;
        }

        /** 按等级取门槛定义；缺失返回 null。 */
        public GateLevel level(long lv) {
            return levels.get(lv);
        }
    }

    private static final String RESOURCE_PATH = "bootstrap/building-gate-catalog.json";
    private static BuildingGateCatalog instance;

    /** gate 建筑表（键 = 建筑 id）。 */
    public final Map<Long, GateBuilding> buildings = new HashMap<>();
    /** 家具等级表（键 = 家具 id，值 = 等级 → 定义）。 */
    public final Map<Long, Map<Long, FurnitureLevel>> furniture = new HashMap<>();
    /** 家具最大等级表（键 = 家具 id）。 */
    public final Map<Long, Long> furnitureMaxLevels = new HashMap<>();

    /** 静态懒加载单例（加载失败降级为空目录，对齐 TownDevCatalog 容错风格）。 */
    public static synchronized BuildingGateCatalog get() {
        if (instance != null) return instance;
        BuildingGateCatalog c = new BuildingGateCatalog();
        try {
            JSONObject root = new JSONObject(new String(readResource(RESOURCE_PATH),
                    StandardCharsets.UTF_8));
            c.parseBuildings(root.optJSONObject("buildings"));
            c.parseFurniture(root.optJSONObject("furniture"));
        } catch (Exception e) {
            FileLog.warn("[起号] 门槛目录加载失败（降级为空目录）: %s",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        instance = c;
        return c;
    }

    // ================= 门槛查询（语义对齐原版 DailyTownDevelopmentCatalog 对应方法） =================

    /** 建筑升到 {@code level} 级所需家具经验门槛；无门槛返回 0。 */
    public long needExp(long buildingId, long level) {
        GateLevel gl = gateLevel(buildingId, level);
        return gl == null ? 0L : gl.needExp();
    }

    /** 建筑升到 {@code level} 级的家具门槛（家具 id → 需求等级）；无门槛返回空列表。 */
    public List<Cost> furnitureRequirement(long buildingId, long level) {
        GateLevel gl = gateLevel(buildingId, level);
        return gl == null ? List.of() : gl.furniture();
    }

    /** 家具升到 {@code level} 级的成本；缺失返回空列表。 */
    public List<Cost> furnitureCosts(long furnitureId, long level) {
        FurnitureLevel fl = furnitureLevel(furnitureId, level);
        return fl == null ? List.of() : fl.costs();
    }

    /** 家具处于 {@code level} 级时贡献的家具经验；缺失返回 0。 */
    public long furnitureExp(long furnitureId, long level) {
        FurnitureLevel fl = furnitureLevel(furnitureId, level);
        return fl == null ? 0L : fl.exp();
    }

    /** 家具最大等级；未知家具返回 0。 */
    public long furnitureMaxLevel(long furnitureId) {
        return furnitureMaxLevels.getOrDefault(furnitureId, 0L);
    }

    /** 家具是否参与经验贡献（原版 furnitureContributesExp 语义：1 级经验 &gt; 0）。 */
    public boolean furnitureContributesExp(long furnitureId) {
        return furnitureExp(furnitureId, 1L) > 0L;
    }

    /** gate 目录是否收录该建筑。 */
    public boolean supports(long buildingId) {
        return buildings.containsKey(buildingId);
    }

    /** gate 目录内该建筑最大等级；未收录返回 0。 */
    public long gateMaxLevel(long buildingId) {
        GateBuilding b = buildings.get(buildingId);
        return b == null ? 0L : b.maxLevel;
    }

    private GateLevel gateLevel(long buildingId, long level) {
        GateBuilding b = buildings.get(buildingId);
        return b == null ? null : b.level(level);
    }

    private FurnitureLevel furnitureLevel(long furnitureId, long level) {
        return furniture.getOrDefault(furnitureId, Map.of()).get(level);
    }

    // ================= 解析 =================

    private void parseBuildings(JSONObject root) {
        if (root == null) return;
        java.util.Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String key = it.next();
            long id;
            try {
                id = Long.parseLong(key.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (id <= 0L) continue;
            JSONObject b = root.optJSONObject(key);
            if (b == null) continue;
            GateBuilding gb = new GateBuilding(id, b.optLong("maxLevel"));
            JSONArray ls = b.optJSONArray("levels");
            if (ls != null) {
                for (int i = 0; i < ls.length(); i++) {
                    JSONObject l = ls.optJSONObject(i);
                    if (l == null) continue;
                    List<Cost> furnitureGate = parseCosts(l.optJSONArray("furniture"));
                    List<Prerequisite> prereqs = parsePrerequisites(l.optJSONArray("prerequisites"));
                    gb.levels.put(l.optLong("level"),
                            new GateLevel(l.optLong("level"), l.optLong("needExp"),
                                    furnitureGate, prereqs));
                }
            }
            buildings.put(id, gb);
        }
    }

    private void parseFurniture(JSONObject root) {
        if (root == null) return;
        java.util.Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String key = it.next();
            // 键形 "fid:level"（对齐原版 loadGates 解析）
            int sep = key.indexOf(':');
            if (sep <= 0) continue;
            long fid;
            long lv;
            try {
                fid = Long.parseLong(key.substring(0, sep).trim());
                lv = Long.parseLong(key.substring(sep + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (fid <= 0L || lv <= 0L) continue;
            JSONObject v = root.optJSONObject(key);
            if (v == null) continue;
            FurnitureLevel fl = new FurnitureLevel(v.optLong("exp"),
                    parseCosts(v.optJSONArray("costs")));
            furniture.computeIfAbsent(fid, ignored -> new LinkedHashMap<>()).put(lv, fl);
            furnitureMaxLevels.merge(fid, lv, Math::max);
        }
    }

    private static List<Cost> parseCosts(JSONArray arr) {
        if (arr == null) return List.of();
        List<Cost> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null) out.add(new Cost(c.optLong("id"), c.optLong("amount")));
        }
        return List.copyOf(out);
    }

    private static List<Prerequisite> parsePrerequisites(JSONArray arr) {
        if (arr == null) return List.of();
        List<Prerequisite> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null) out.add(new Prerequisite(p.optLong("id"), p.optLong("amount")));
        }
        return List.copyOf(out);
    }

    private static byte[] readResource(String name) throws Exception {
        java.io.InputStream in = BuildingGateCatalog.class.getClassLoader().getResourceAsStream(name);
        if (in == null) throw new IllegalStateException("classpath 缺资源 " + name);
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private BuildingGateCatalog() {
    }
}
