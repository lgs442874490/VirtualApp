package com.carlos.vault.daily.gather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 采集配置 v2（6 队列 × 资源/等级/编队 + 全局项），1:1 对齐网页版
 * {@code DailyGatherConfig} / {@code DailyGatherConfigQueueConfig}。
 *
 * <p><b>纯 JVM 共享模型</b>：只依赖 {@code org.json}（App 为 Android 内置实现，
 * vault-server 为 org.json:json 依赖）；App 写配置、服务端读配置共用同一份解析/迁移/校验，
 * 避免双端漂移（App 源树 {@code app/src/main/java}，vault-server 通过 sourceSets include 编译）。
 *
 * <p><b>存储</b>：{@code roleParams.<sid>.gather.v2}（内嵌在既有 gather 参数对象，零新接口）；
 * 写 v2 的同时做「旧键双写投影」（min_level/resources_named/max_queues/strategy.send/alliance_mine/
 * formation_*），保证「新 App + 旧 server」「旧 App + 新 server」双向兼容与回滚。
 *
 * <p><b>等级语义</b>：{@code level} 是「最高目标等级」，服务端从该级逐级向下搜索到 1
 * （网页版 {@code searchLevels}）；可采上限由王国开服天数决定（第 701 天前 8 级、之后 9 级），
 * 仅 UI 提示/展示，不在此模型硬校验（未知时按 8）。
 *
 * <p>Java 8 兼容（App sourceCompatibility=1.8）。
 */
public final class GatherConfigV2 {

    public static final String KEY = "v2";
    public static final int VERSION = 2;
    public static final int QUEUE_COUNT = 6;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_RESOURCE_LEVEL = 9;
    public static final int LEVEL_9_UNLOCK_DAY = 701;

    public static final String RES_MEAT = "MEAT";
    public static final String RES_WOOD = "WOOD";
    public static final String RES_COAL = "COAL";
    public static final String RES_IRON = "IRON";

    public static final String MODE_AUTOMATIC = "AUTOMATIC";
    public static final String MODE_ROLE = "ROLE";

    public static final long DEFAULT_INFANTRY = 40000L;
    public static final long DEFAULT_LANCER = 30000L;
    public static final long DEFAULT_MARKSMAN = 30000L;

    /** 每队列配置。 */
    public static final class QueueCfg {
        public int id = 1;
        public boolean enabled = false;
        public String resourceType = RES_MEAT;
        public int level = 8;
        public String formationMode = MODE_AUTOMATIC;
        public long formationId = 0L;
        public long infantry = DEFAULT_INFANTRY;
        public long lancer = DEFAULT_LANCER;
        public long marksman = DEFAULT_MARKSMAN;

        public QueueCfg() {
        }

        public QueueCfg(int id, boolean enabled, String resourceType, int level,
                        String formationMode, long formationId, long infantry, long lancer,
                        long marksman) {
            this.id = id;
            this.enabled = enabled;
            this.resourceType = resourceType == null ? RES_MEAT : resourceType;
            this.level = level;
            this.formationMode = formationMode == null ? MODE_AUTOMATIC : formationMode;
            this.formationId = formationId;
            this.infantry = infantry;
            this.lancer = lancer;
            this.marksman = marksman;
        }

        public boolean isRole() {
            return MODE_ROLE.equals(normalizeMode(formationMode));
        }

        public long totalSoldiers() {
            return Math.max(0L, infantry) + Math.max(0L, lancer) + Math.max(0L, marksman);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            put(o, "id", id);
            put(o, "enabled", enabled);
            put(o, "resourceType", resourceType);
            put(o, "resourceLevel", level);
            put(o, "formationMode", normalizeMode(formationMode));
            if (isRole() && formationId > 0) put(o, "formationId", formationId);
            put(o, "infantry", infantry);
            put(o, "lancer", lancer);
            put(o, "marksman", marksman);
            return o;
        }

        public static QueueCfg fromJson(JSONObject o, int fallbackId) {
            QueueCfg q = new QueueCfg();
            if (o == null) {
                q.id = fallbackId;
                q.enabled = false;
                return q;
            }
            q.id = clampInt(o.optInt("id", fallbackId), 1, QUEUE_COUNT);
            q.enabled = o.optBoolean("enabled", false);
            q.resourceType = normalizeResource(o.optString("resourceType",
                    o.optString("resource", RES_MEAT)));
            q.level = clampInt(o.optInt("resourceLevel", o.optInt("level", 8)),
                    MIN_LEVEL, MAX_RESOURCE_LEVEL);
            q.formationMode = normalizeMode(o.optString("formationMode",
                    o.optString("formation_mode", MODE_AUTOMATIC)));
            q.formationId = o.optLong("formationId", o.optLong("formation_id", 0L));
            q.infantry = Math.max(0L, o.optLong("infantry", DEFAULT_INFANTRY));
            q.lancer = Math.max(0L, o.optLong("lancer", DEFAULT_LANCER));
            q.marksman = Math.max(0L, o.optLong("marksman", DEFAULT_MARKSMAN));
            return q;
        }

        @Override
        public String toString() {
            return "#" + id + (enabled ? "开" : "关") + " " + resourceType + "L" + level
                    + " " + (isRole() ? "ROLE#" + formationId : "AUTO");
        }
    }

    // ================= 实例字段 =================

    public final List<QueueCfg> queues = new ArrayList<QueueCfg>();
    public boolean fullResourceOnly = true;
    /** >0 时作为「最多使用队列数」安全阀；0=只按启用队列数。 */
    public int maxQueues = 0;
    /** 联盟矿（本期仅占位，沿用服务端既有 alliance_mine 骨架）。 */
    public boolean allianceMineEnabled = false;
    public String allianceMineFormationMode = MODE_AUTOMATIC;
    public long allianceMineFormationId = 0L;
    public long allianceMineInfantry = DEFAULT_INFANTRY;
    public long allianceMineLancer = DEFAULT_LANCER;
    public long allianceMineMarksman = DEFAULT_MARKSMAN;

    public GatherConfigV2() {
        for (int i = 1; i <= QUEUE_COUNT; i++) queues.add(new QueueCfg(i, false, defaultResource(i),
                8, MODE_AUTOMATIC, 0L, DEFAULT_INFANTRY, DEFAULT_LANCER, DEFAULT_MARKSMAN));
    }

    // ================= 解析 / 迁移 =================

    /** gather 参数对象是否已含 v2。 */
    public static boolean hasV2(JSONObject gatherParams) {
        return gatherParams != null && gatherParams.optJSONObject(KEY) != null;
    }

    /**
     * 解析：有 v2 用 v2；无 v2 按「旧配置生效行为」迁移（不改变存量行为）。
     * 绝不返回 null；坏 JSON 回退默认（旧行为等价默认）。
     */
    public static GatherConfigV2 parse(JSONObject gatherParams) {
        try {
            if (gatherParams != null) {
                JSONObject v2 = gatherParams.optJSONObject(KEY);
                if (v2 != null) return fromV2(v2);
            }
            return migrate(gatherParams);
        } catch (Throwable t) {
            GatherConfigV2 d = legacyDefaults();
            return d;
        }
    }

    static GatherConfigV2 fromV2(JSONObject v2) {
        GatherConfigV2 c = new GatherConfigV2();
        c.queues.clear();
        JSONArray arr = v2.optJSONArray("queues");
        List<QueueCfg> parsed = new ArrayList<QueueCfg>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                parsed.add(QueueCfg.fromJson(arr.optJSONObject(i), i + 1));
            }
        }
        // 以 id 1..6 对齐补齐（缺失沿用默认），保证固定 6 队列
        for (int id = 1; id <= QUEUE_COUNT; id++) {
            QueueCfg found = null;
            for (QueueCfg q : parsed) {
                if (q.id == id) {
                    found = q;
                    break;
                }
            }
            c.queues.add(found != null ? found : new QueueCfg(id, false, defaultResource(id),
                    8, MODE_AUTOMATIC, 0L, DEFAULT_INFANTRY, DEFAULT_LANCER, DEFAULT_MARKSMAN));
        }
        c.fullResourceOnly = v2.optBoolean("fullResourceOnly",
                v2.optBoolean("full_resource_only", true));
        c.maxQueues = clampInt(v2.optInt("maxQueues", v2.optInt("max_queues", 0)),
                0, QUEUE_COUNT);
        JSONObject am = v2.optJSONObject("allianceMine");
        if (am == null) am = v2.optJSONObject("alliance_mine");
        if (am != null) {
            c.allianceMineEnabled = am.optBoolean("enabled", false);
            c.allianceMineFormationMode = normalizeMode(am.optString("formationMode",
                    am.optString("formation_mode", MODE_AUTOMATIC)));
            c.allianceMineFormationId = am.optLong("formationId",
                    am.optLong("formation_id", 0L));
            c.allianceMineInfantry = Math.max(0L, am.optLong("infantry", DEFAULT_INFANTRY));
            c.allianceMineLancer = Math.max(0L, am.optLong("lancer", DEFAULT_LANCER));
            c.allianceMineMarksman = Math.max(0L, am.optLong("marksman", DEFAULT_MARKSMAN));
        }
        return c;
    }

    /**
     * 旧配置迁移（按「旧配置的生效行为」，而不是网页版默认值）：
     * <ul>
     *   <li>resources_named（多选池，空=肉+木）→ 按顺序轮转分配给启用队列；</li>
     *   <li>resources（1-4 或 102-105）→ 同资源池（低于 named 优先级）；</li>
     *   <li>min_level → 所有启用队列 level（clamp 1..9，缺省 5）；</li>
     *   <li>max_queues → 前 N 条启用、其余关闭；maxQueues 保留；</li>
     *   <li>strategy.send=full → fullResourceOnly=true（缺省 false，保持旧行为）；</li>
     *   <li>formation_mode/formation_id（功能级）→ 仅队列 1 继承 ROLE；其余 AUTO；</li>
     *   <li>soldiers（功能级覆盖）→ 每队列兵量（>0 才覆盖）；</li>
     *   <li>squad_index 不迁入（旧游戏内存编队保留顶层键供旧 server 分支）。</li>
     * </ul>
     */
    static GatherConfigV2 migrate(JSONObject p) {
        GatherConfigV2 c = legacyDefaults();
        if (p == null) return c;
        int maxQueues = clampInt(p.optInt("max_queues", p.optInt("maxQueues", 3)), 1, 5);
        boolean full = "full".equalsIgnoreCase(legacyStrategySend(p));
        int minLevel = clampInt(p.optInt("min_level", 5), MIN_LEVEL, MAX_RESOURCE_LEVEL);
        List<String> resources = legacyResources(p);
        boolean role = MODE_ROLE.equals(normalizeMode(p.optString("formation_mode",
                p.optString("formationMode", MODE_AUTOMATIC))));
        long formationId = p.optLong("formation_id", p.optLong("formationId", 0L));
        JSONObject soldiers = p.optJSONObject("soldiers");
        long inf = soldiers == null ? 0L : Math.max(0L, soldiers.optLong("infantry", 0L));
        long lan = soldiers == null ? 0L : Math.max(0L, soldiers.optLong("lancer", 0L));
        long mar = soldiers == null ? 0L : Math.max(0L, soldiers.optLong("marksman", 0L));

        c.queues.clear();
        for (int id = 1; id <= QUEUE_COUNT; id++) {
            QueueCfg q = new QueueCfg();
            q.id = id;
            q.enabled = id <= maxQueues;
            q.resourceType = resources.isEmpty()
                    ? defaultResource(id)
                    : resources.get((id - 1) % resources.size());
            q.level = minLevel;
            if (id == 1 && role && formationId > 0) {
                q.formationMode = MODE_ROLE;
                q.formationId = formationId;
            } else {
                q.formationMode = MODE_AUTOMATIC;
                q.formationId = 0L;
            }
            q.infantry = inf > 0 ? inf : DEFAULT_INFANTRY;
            q.lancer = lan > 0 ? lan : DEFAULT_LANCER;
            q.marksman = mar > 0 ? mar : DEFAULT_MARKSMAN;
            c.queues.add(q);
        }
        c.fullResourceOnly = full;
        c.maxQueues = maxQueues;
        c.allianceMineEnabled = p.optBoolean("alliance_mine", p.optBoolean("allianceMine", false));
        return c;
    }

    /** 旧行为等价默认（空配置/坏 JSON）：肉+木、队列 1-3、等级 5、自动编队、任意存量。 */
    public static GatherConfigV2 legacyDefaults() {
        GatherConfigV2 c = new GatherConfigV2();
        c.queues.clear();
        for (int id = 1; id <= QUEUE_COUNT; id++) {
            QueueCfg q = new QueueCfg();
            q.id = id;
            q.enabled = id <= 3;
            q.resourceType = id == 2 ? RES_WOOD : RES_MEAT;
            q.level = 5;
            q.formationMode = MODE_AUTOMATIC;
            q.infantry = DEFAULT_INFANTRY;
            q.lancer = DEFAULT_LANCER;
            q.marksman = DEFAULT_MARKSMAN;
            c.queues.add(q);
        }
        c.fullResourceOnly = false;
        c.maxQueues = 3;
        c.allianceMineEnabled = false;
        return c;
    }

    // ================= 输出 / 投影 =================

    /** v2 子对象。 */
    public JSONObject toV2Json() {
        JSONObject v2 = new JSONObject();
        put(v2, "version", VERSION);
        JSONArray arr = new JSONArray();
        for (QueueCfg q : queues) add(arr, q.toJson());
        put(v2, "queues", arr);
        put(v2, "fullResourceOnly", fullResourceOnly);
        put(v2, "maxQueues", maxQueues);
        JSONObject am = new JSONObject();
        put(am, "enabled", allianceMineEnabled);
        put(am, "formationMode", normalizeMode(allianceMineFormationMode));
        if (MODE_ROLE.equals(normalizeMode(allianceMineFormationMode)) && allianceMineFormationId > 0) {
            put(am, "formationId", allianceMineFormationId);
        }
        put(am, "infantry", allianceMineInfantry);
        put(am, "lancer", allianceMineLancer);
        put(am, "marksman", allianceMineMarksman);
        put(v2, "allianceMine", am);
        return v2;
    }

    /**
     * 写回 gather 参数对象：保留其它键，写 v2 + 旧键双写投影。
     * 返回新的 JSONObject（不修改入参）。
     */
    public JSONObject applyTo(JSONObject gatherParams) {
        JSONObject out;
        try {
            out = gatherParams == null ? new JSONObject()
                    : new JSONObject(gatherParams.toString());
        } catch (Throwable t) {
            out = new JSONObject();
        }
        put(out, KEY, toV2Json());
        writeLegacyProjection(out);
        return out;
    }

    /** 旧键投影（供旧 server jar/回滚读取；只在语义可无损表达时写）。 */
    void writeLegacyProjection(JSONObject out) {
        List<QueueCfg> enabled = enabledQueues();
        int maxLevel = 5;
        for (QueueCfg q : enabled) maxLevel = Math.max(maxLevel, q.level);
        put(out, "min_level", maxLevel);

        JSONArray names = new JSONArray();
        Set<String> seen = new LinkedHashSet<String>();
        for (QueueCfg q : enabled) {
            if (seen.add(q.resourceType)) add(names, q.resourceType);
        }
        if (names.length() == 0) {
            add(names, RES_MEAT);
            add(names, RES_WOOD);
        }
        put(out, "resources_named", names);
        put(out, "max_queues", clampInt(enabled.isEmpty() ? 1 : enabled.size(), 1, 5));

        JSONObject strategy = out.optJSONObject("strategy");
        if (strategy == null) strategy = new JSONObject();
        put(strategy, "send", fullResourceOnly ? "full" : "any");
        put(out, "strategy", strategy);

        put(out, "alliance_mine", allianceMineEnabled);

        // 编队：所有启用队列一致（且为 ROLE）时才写功能级字段，否则写 AUTOMATIC 并移除 id
        String mode = MODE_AUTOMATIC;
        long fid = 0L;
        boolean uniform = !enabled.isEmpty();
        boolean uniformSoldiers = !enabled.isEmpty();
        long inf = -1, lan = -1, mar = -1;
        for (QueueCfg q : enabled) {
            if (!q.isRole()) {
                uniform = false;
                break;
            }
            if (fid == 0L) fid = q.formationId;
            else if (fid != q.formationId) uniform = false;
            if (inf < 0) {
                inf = q.infantry;
                lan = q.lancer;
                mar = q.marksman;
            } else if (inf != q.infantry || lan != q.lancer || mar != q.marksman) {
                uniformSoldiers = false;
            }
        }
        if (uniform && fid > 0) {
            mode = MODE_ROLE;
            put(out, "formation_mode", mode);
            put(out, "formation_id", fid);
        } else {
            put(out, "formation_mode", mode);
            out.remove("formation_id");
        }
        if (uniformSoldiers && inf >= 0) {
            JSONObject soldiers = new JSONObject();
            put(soldiers, "infantry", inf);
            put(soldiers, "lancer", lan);
            put(soldiers, "marksman", mar);
            put(out, "soldiers", soldiers);
        } else {
            out.remove("soldiers");
        }
    }

    // ================= 校验 =================

    /** 校验（保存前/服务端防御）；非法抛 IllegalArgumentException（中文原因）。 */
    public void validate() {
        if (queues == null || queues.size() != QUEUE_COUNT) {
            throw new IllegalArgumentException("采集配置必须包含 6 条队列");
        }
        Set<Integer> ids = new LinkedHashSet<Integer>();
        Set<Long> roleFormationIds = new LinkedHashSet<Long>();
        for (QueueCfg q : queues) {
            if (q == null) throw new IllegalArgumentException("采集队列配置为空");
            if (q.id < 1 || q.id > QUEUE_COUNT) {
                throw new IllegalArgumentException("队列编号必须为 1-6");
            }
            if (!ids.add(q.id)) throw new IllegalArgumentException("采集队列编号重复：" + q.id);
            if (q.level < MIN_LEVEL || q.level > MAX_RESOURCE_LEVEL) {
                throw new IllegalArgumentException("队列 " + q.id + " 的资源等级必须为 1-9");
            }
            if (!isValidResource(q.resourceType)) {
                throw new IllegalArgumentException("队列 " + q.id + " 未选择采集资源");
            }
            String mode = normalizeMode(q.formationMode);
            q.formationMode = mode;
            if (MODE_ROLE.equals(mode) && q.formationId <= 0) {
                throw new IllegalArgumentException("队列 " + q.id + " 请选择指定编队");
            }
            if (MODE_ROLE.equals(mode) && !roleFormationIds.add(q.formationId)) {
                throw new IllegalArgumentException("编队 #" + q.formationId
                        + " 已被其他采集队列使用（同一编队只能分配给一个队列）");
            }
            if (q.infantry < 0 || q.lancer < 0 || q.marksman < 0) {
                throw new IllegalArgumentException("队列 " + q.id + " 的兵力不能小于 0");
            }
        }
        if (maxQueues < 0 || maxQueues > QUEUE_COUNT) {
            throw new IllegalArgumentException("最多使用队列数必须为 0-6");
        }
        if (allianceMineEnabled && MODE_ROLE.equals(normalizeMode(allianceMineFormationMode))
                && allianceMineFormationId > 0 && !roleFormationIds.add(allianceMineFormationId)) {
            throw new IllegalArgumentException("联盟矿编队 #" + allianceMineFormationId
                    + " 已被采集队列使用");
        }
    }

    // ================= 查询 =================

    public List<QueueCfg> enabledQueues() {
        List<QueueCfg> out = new ArrayList<QueueCfg>();
        for (QueueCfg q : queues) {
            if (q != null && q.enabled) out.add(q);
        }
        return out;
    }

    public QueueCfg queue(int id) {
        for (QueueCfg q : queues) {
            if (q != null && q.id == id) return q;
        }
        return null;
    }

    /** 有效使用队列数上限：启用队列数、maxQueues、世界容量三者取小（worldCapacity=0 视为未知）。 */
    public int effectiveQueueLimit(int worldCapacity, int manualLimit) {
        int limit = enabledQueues().size();
        if (maxQueues > 0) limit = Math.min(limit, maxQueues);
        if (manualLimit > 0) limit = Math.min(limit, manualLimit);
        if (worldCapacity > 0) limit = Math.min(limit, worldCapacity);
        return Math.max(0, limit);
    }

    /** 从配置等级递减到 1（服务端逐级搜索用；超可采上限时先夹到上限）。 */
    public static List<Integer> searchLevels(int configuredLevel, int maxLevel) {
        int top = Math.min(clampInt(configuredLevel, MIN_LEVEL, MAX_RESOURCE_LEVEL),
                maxLevel > 0 ? maxLevel : MAX_RESOURCE_LEVEL);
        List<Integer> out = new ArrayList<Integer>();
        for (int lv = top; lv >= MIN_LEVEL; lv--) out.add(lv);
        return out;
    }

    /** 王国天数 → 可采资源最高等级（701 天前 8，之后 9）。 */
    public static int maxLevelForKingdomDay(int kingdomDay) {
        return kingdomDay >= LEVEL_9_UNLOCK_DAY ? 9 : 8;
    }

    /** 资源名 → 协议 res_id（102/103/104/105）；未知返回 0。 */
    public static int resId(String resourceType) {
        String r = normalizeResource(resourceType);
        if (RES_MEAT.equals(r)) return 102;
        if (RES_WOOD.equals(r)) return 103;
        if (RES_COAL.equals(r)) return 104;
        if (RES_IRON.equals(r)) return 105;
        return 0;
    }

    /** 摘要文案（功能页/日志）：如「3队 · 肉8/木7/煤9 · 只采满矿」。 */
    public String summary() {
        List<QueueCfg> enabled = enabledQueues();
        StringBuilder sb = new StringBuilder();
        sb.append(enabled.size()).append("队");
        if (!enabled.isEmpty()) {
            sb.append(" · ");
            for (int i = 0; i < enabled.size(); i++) {
                if (i > 0) sb.append('/');
                QueueCfg q = enabled.get(i);
                sb.append(resourceLabel(q.resourceType)).append(q.level);
            }
        }
        if (fullResourceOnly) sb.append(" · 只采满矿");
        if (maxQueues > 0) sb.append(" · 上限").append(maxQueues).append("队");
        return sb.toString();
    }

    // ================= 工具 =================

    public static String normalizeResource(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (RES_WOOD.equals(v) || RES_COAL.equals(v) || RES_IRON.equals(v) || RES_MEAT.equals(v)) {
            return v;
        }
        // 数值资源（旧配置 1-4 / 102-105）
        try {
            int n = Integer.parseInt(v);
            if (n >= 1 && n <= 4) n += 101;
            if (n == 102) return RES_MEAT;
            if (n == 103) return RES_WOOD;
            if (n == 104) return RES_COAL;
            if (n == 105) return RES_IRON;
        } catch (Throwable ignored) {
        }
        return RES_MEAT;
    }

    public static String normalizeMode(String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("ROLE".equals(v) || "NORMAL".equals(v) || "CUSTOM".equals(v)) return MODE_ROLE;
        return MODE_AUTOMATIC;
    }

    public static String resourceLabel(String resourceType) {
        String r = normalizeResource(resourceType);
        if (RES_MEAT.equals(r)) return "肉";
        if (RES_WOOD.equals(r)) return "木";
        if (RES_COAL.equals(r)) return "煤";
        if (RES_IRON.equals(r)) return "铁";
        return "?";
    }

    static boolean isValidResource(String resourceType) {
        String r = resourceType == null ? "" : resourceType.trim().toUpperCase(Locale.ROOT);
        return RES_MEAT.equals(r) || RES_WOOD.equals(r) || RES_COAL.equals(r) || RES_IRON.equals(r);
    }

    static String defaultResource(int id) {
        switch (id) {
            case 2:
                return RES_WOOD;
            case 3:
                return RES_COAL;
            case 4:
                return RES_IRON;
            default:
                return RES_MEAT;
        }
    }

    static String legacyStrategySend(JSONObject p) {
        if (p == null) return "any";
        JSONObject st = p.optJSONObject("strategy");
        return st == null ? "any" : st.optString("send", "any");
    }

    @SuppressWarnings("unchecked")
    static List<String> legacyResources(JSONObject p) {
        List<String> out = new ArrayList<String>();
        if (p == null) return out;
        JSONArray named = p.optJSONArray("resources_named");
        if (named != null && named.length() > 0) {
            for (int i = 0; i < named.length(); i++) {
                String r = normalizeResource(named.optString(i, ""));
                if (!out.contains(r)) out.add(r);
            }
            return out;
        }
        JSONArray arr = p.optJSONArray("resources");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String r = normalizeResource(arr.optString(i, ""));
                if (!out.contains(r)) out.add(r);
            }
        }
        return out;
    }

    public static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ================= JSON 兼容（App 主源用 android.jar 桩，JSONException 是 checked） =================

    /** 受保护 put：App(Android 桩) 与 Server(org.json) 双端编译兼容；失败静默（值均为简单类型）。 */
    static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Throwable ignored) {
        }
    }

    /** 受保护数组追加（同 put 兼容策略）。 */
    static void add(JSONArray arr, Object value) {
        try {
            arr.put(value);
        } catch (Throwable ignored) {
        }
    }

    /** 只读副本（测试/诊断）。 */
    public List<QueueCfg> queuesCopy() {
        return Collections.unmodifiableList(new ArrayList<QueueCfg>(queues));
    }
}
