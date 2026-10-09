package com.carlos.vaultserver.task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技/建筑目录 + 科技路线（App 端 daily/TownDevCatalog 移植；
 * 数据 towndev/towndev.json 等随 jar classpath 打包，与 App assets 同源）。
 */
public final class TownDevCatalog {

    public static final class Cost {
        public final long id, amount;

        Cost(long id, long amount) {
            this.id = id;
            this.amount = amount;
        }
    }

    public static final class TechLevel {
        public int level;
        public final List<Cost> costs = new ArrayList<>();
        public final List<long[]> conditions = new ArrayList<>();
    }

    public static final class Tech {
        public long id;
        public String name;
        public int category, maxLevel;
        public final List<TechLevel> levels = new ArrayList<>();

        TechLevel level(int lv) {
            for (TechLevel l : levels) if (l.level == lv) return l;
            return null;
        }
    }

    public static final class BuildingLevel {
        public int level;
        public final List<Cost> costs = new ArrayList<>();
        public final List<long[]> prerequisites = new ArrayList<>();
    }

    public static final class Building {
        public long id;
        public String name;
        public int initialLevel, maxLevel;
        public final List<BuildingLevel> levels = new ArrayList<>();

        BuildingLevel level(int lv) {
            for (BuildingLevel l : levels) if (l.level == lv) return l;
            return null;
        }
    }

    private static TownDevCatalog instance;

    public final Map<Long, Tech> techs = new HashMap<>();
    public final Map<Long, Building> buildings = new HashMap<>();
    public final Map<Long, String> resourceNames = new HashMap<>();

    public static synchronized TownDevCatalog get() {
        if (instance != null) return instance;
        TownDevCatalog c = new TownDevCatalog();
        try {
            JSONObject root = new JSONObject(new String(readResource("towndev/towndev.json"),
                    StandardCharsets.UTF_8));
            JSONObject rn = root.optJSONObject("resourceNames");
            if (rn != null) {
                java.util.Iterator<String> it = rn.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.resourceNames.put(Long.parseLong(k), rn.optString(k));
                }
            }
            JSONArray ts = root.optJSONArray("technologies");
            if (ts != null) {
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject t = ts.getJSONObject(i);
                    Tech tech = new Tech();
                    tech.id = t.getLong("id");
                    tech.name = t.optString("name");
                    tech.category = t.optInt("category");
                    tech.maxLevel = t.optInt("maxLevel");
                    JSONArray ls = t.optJSONArray("levels");
                    if (ls != null) {
                        for (int j = 0; j < ls.length(); j++) {
                            JSONObject l = ls.getJSONObject(j);
                            TechLevel tl = new TechLevel();
                            tl.level = l.optInt("level");
                            parseCosts(l.optJSONArray("costs"), tl.costs);
                            JSONArray cs = l.optJSONArray("conditions");
                            if (cs != null) {
                                for (int m = 0; m < cs.length(); m++) {
                                    JSONObject cc = cs.getJSONObject(m);
                                    tl.conditions.add(new long[]{cc.optLong("type"),
                                            cc.optLong("targetId"), cc.optLong("value")});
                                }
                            }
                            tech.levels.add(tl);
                        }
                    }
                    c.techs.put(tech.id, tech);
                }
            }
            JSONArray bs = root.optJSONArray("buildings");
            if (bs != null) {
                for (int i = 0; i < bs.length(); i++) {
                    JSONObject b = bs.getJSONObject(i);
                    Building bd = new Building();
                    bd.id = b.getLong("id");
                    bd.name = b.optString("name");
                    bd.initialLevel = b.optInt("initialLevel", 1);
                    bd.maxLevel = b.optInt("maxLevel");
                    JSONArray ls = b.optJSONArray("levels");
                    if (ls != null) {
                        for (int j = 0; j < ls.length(); j++) {
                            JSONObject l = ls.getJSONObject(j);
                            BuildingLevel bl = new BuildingLevel();
                            bl.level = l.optInt("level");
                            parseCosts(l.optJSONArray("costs"), bl.costs);
                            JSONArray ps = l.optJSONArray("prerequisites");
                            if (ps != null) {
                                for (int m = 0; m < ps.length(); m++) {
                                    JSONObject pp = ps.getJSONObject(m);
                                    bl.prerequisites.add(new long[]{pp.optLong("id"), pp.optLong("amount")});
                                }
                            }
                            bd.levels.add(bl);
                        }
                    }
                    c.buildings.put(bd.id, bd);
                }
            }
        } catch (Exception ignored) {
        }
        instance = c;
        return c;
    }

    private static void parseCosts(JSONArray arr, List<Cost> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null) out.add(new Cost(c.optLong("id"), c.optLong("amount")));
        }
    }

    // ================= 科技路线（与 App/网页版一致） =================

    public static final class Goal {
        public final long tid;
        public final int targetLevel;
        public final String name;

        Goal(long tid, int targetLevel, String name) {
            this.tid = tid;
            this.targetLevel = targetLevel;
            this.name = name;
        }
    }

    private static final String[] GROWTH = {"工具改良", "病房扩建", "兵营扩建", "器材优化", "包扎术", "训练器材", "指挥艺术"};
    private static final String[] ECONOMY = {"食物采集", "木材采集", "煤矿采集", "铁矿采集", "生肉产量", "木材产量", "煤矿产量", "铁矿产量"};
    private static final String[] BATTLE = {"编制扩展", "武器特训", "稳守反击", "精准射击", "迅猛突刺", "防御阵型", "警戒队形",
            "坚守阵地", "防御特训", "生存特训", "突击特训", "不懈奋战", "定点狙击", "长矛改良", "盾牌改良", "射手护甲", "矛兵护甲"};

    /** profile: casual=休闲 warlord=战狂 */
    public static List<Goal> route(String profile) {
        TownDevCatalog c = get();
        List<Goal> out = new ArrayList<>();
        boolean warlord = "warlord".equals(profile);
        if (warlord) {
            goals(c, out, 1, GROWTH, 0, 3);
            goals(c, out, 3, BATTLE, 0, 4);
            goals(c, out, 1, GROWTH, 5, 5);
            goals(c, out, 2, ECONOMY, 0, 1);
        } else {
            goals(c, out, 1, GROWTH, 0, 5);
            goals(c, out, 2, ECONOMY, 0, 4);
            goals(c, out, 3, new String[]{"编制扩展", "防御特训", "生存特训", "突击特训"}, 0, 0);
        }
        Map<Long, Goal> uniq = new LinkedHashMap<>();
        for (Goal g : out) uniq.putIfAbsent(g.tid, g);
        return new ArrayList<>(uniq.values());
    }

    private static void goals(TownDevCatalog c, List<Goal> out, int category, String[] families,
                              int minTier, int maxTier) {
        Map<String, List<Tech>> byFamily = new LinkedHashMap<>();
        for (String f : families) byFamily.put(f, new ArrayList<>());
        for (Tech t : c.techs.values()) {
            if (t.category != category) continue;
            String fam = familyOf(t.name);
            List<Tech> l = byFamily.get(fam);
            if (l != null) l.add(t);
        }
        for (List<Tech> l : byFamily.values()) l.sort((a, b) -> Long.compare(a.id, b.id));
        int maxAvailable = 0;
        for (List<Tech> l : byFamily.values()) maxAvailable = Math.max(maxAvailable, l.size());
        int lastTier = Math.min(maxTier + 1, maxAvailable);
        for (int tier = minTier; tier < lastTier; tier++) {
            for (String f : families) {
                List<Tech> l = byFamily.get(f);
                if (tier >= l.size()) continue;
                Tech t = l.get(tier);
                if (t.maxLevel > 0) out.add(new Goal(t.id, t.maxLevel, t.name));
            }
        }
    }

    private static String familyOf(String name) {
        return name.replaceAll("[IVX]+$", "");
    }

    private static byte[] readResource(String name) throws Exception {
        java.io.InputStream in = TownDevCatalog.class.getClassLoader().getResourceAsStream(name);
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

    private TownDevCatalog() {
    }
}
