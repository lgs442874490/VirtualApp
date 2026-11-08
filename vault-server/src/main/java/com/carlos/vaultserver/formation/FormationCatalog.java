package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vault.wjdr.asset.HeroCatalog;
import com.carlos.vault.wjdr.asset.MarchCapacityCalculator;
import com.carlos.vaultserver.task.Exec;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 编队目录 v2（契约 §3.1/§3.2）：从登录/增量推送采集「英雄 / 兵种库存 / 出征容量 / 游戏内编队 /
 * 雪猿容量 / 编队记忆状态 / 巨熊绑定」，供 App 编队管理页、日报与服务端动作消费。
 *
 * <p><b>state 键</b>（SessionKeeper 写入；同内容双写）：
 * <ul>
 *   <li>{@code srv:formation:catalog:{fpid}:{sid}:{uid}} —— 新键，多角色隔离（审计 P0-6：旧键无 uid，
 *       生产同 fpid+sid 两个角色互相覆盖）；</li>
 *   <li>{@code srv:formation:catalog:{fpid}:{sid}} —— 旧键，兼容老 APK 一版过渡。</li>
 * </ul>
 *
 * <p><b>修复的硬伤</b>（服务端审计 P0-4/P0-6/P1-3/P2-1）：
 * <ul>
 *   <li>{@code capacity} 恒 0：改由 {@link MarchCapacity}（共享 {@link MarchCapacityCalculator} 公式）
 *       计算 0/1/2/3 英雄档上限，禁止恒 0；旧 {@code capacity}(世界队列数) 仍保留兼容；</li>
 *   <li>英雄名带引号：根因是 {@code HeroCatalog.NAMES} 字面量自带转义引号，已在共享目录修正，
 *       本类出口再过一次 {@link HeroCatalog#cleanName}；</li>
 *   <li>空快照被缓存 10 分钟 → 218 次「英雄目录未同步」：空快照不缓存、不覆盖已有好目录；
 *       {@code notify_*_init} 到达即增量刷新（{@link #onNotify}）；</li>
 *   <li>游戏内编队被丢弃：{@link GameFormations} 解析 {@code notify_soldier_init.prefab}。</li>
 * </ul>
 *
 * <p><b>数据源</b>（只读推送，不新增协议）：
 * {@code notify_hero_init}、{@code notify_soldier_init}（soldier + prefab）、
 * {@code notify_soldier_update}（增量兵力）、{@code notify_soldier_formation_update}、
 * {@code notify_shelter_info/notify_all_research/notify_homeland_info/notify_buff_show}（容量，
 * 经 MarchCapacityCalculator）、{@code notify_pet_info/notify_pet_ex_info}（雪猿）。
 *
 * <p>并发：缓存是 {@link WeakHashMap}（引擎销毁自动回收）；读写均在会话线程/inbound 线程，
 * 用同步 Map 兜底。所有解析防御式，任何异常只损失本项数据，不抛给调用方（任务侧不能用目录中断）。
 */
public final class FormationCatalog {

    /** 目录快照 state 键前缀（SessionKeeper 周期采集写入；键型冻结）。 */
    public static final String STATE_KEY_PREFIX = "srv:formation:catalog:";

    /** 目录 schema 版本（契约 §3.2）。 */
    public static final int VERSION = 2;

    /** 自动编队比例 state 键前缀（契约 §3.2；App 兜底读取）。 */
    public static final String AUTOSETTINGS_KEY_PREFIX = "srv:formation:autosettings:";

    /** 默认自动编队比例（契约 §3.2/§2.3：服务端兜底 50/20/30）。 */
    public static final int[] DEFAULT_AUTOMATIC_SETTINGS = {50, 20, 30};

    /** 英雄条目（纯数据，无 Android 依赖）。 */
    public static final class Hero {
        public final long id;
        public final String name;
        public final long level;
        public final long star;
        public final long power;
        public final String status;
        /** 职业：1=盾兵、2=矛兵、3=射手（游戏每队列只能上阵盾/矛/弓各一）。 */
        public final int profession;
        /** 代际（0=基础；网页版 GENERATIONS 同源）。 */
        public final int generation;
        /** 展示星级 0..5（star 0..30 → /6）。 */
        public final int starDisplay;
        /** 有效等级（level 与 training_lev 取大；容量按此等级查表）。 */
        public final long capLevel;
        /** 该英雄为出征容量贡献的值（MarchCapacityCalculator.heroCapacity(有效等级)）。 */
        public final long troopCapacity;
        /** 是否在家可用（目录瞬间态；在途行军 → false）。 */
        public final boolean available;
        /** AVAILABLE / HERO_AWAY / RESERVED / SYNC_PENDING / SYNC_ERROR。 */
        public final String availabilityState;
        /** 中文原因（英雄在家 / 前序占用 / 英雄未归队 …）。 */
        public final String availabilityReason;
        /** 专武等级（0..10，读推送 {@code elv}；0=未同步/无专武；契约 2026-11-02 §1）。 */
        public final int weaponLevel;
        /** 队长第一个远征技能等级（0..5，读 {@code skills} 中 skill id={@code heroId*10+1} 的 lv）。 */
        public final int expeditionSkillLevel;

        Hero(long id, String name, long level, long star, long power, String status) {
            this(id, name, level, star, power, status, 0L, true, "AVAILABLE", "英雄在家");
        }

        Hero(long id, String name, long level, long star, long power, String status,
             long troopLevel, boolean available, String availabilityState, String availabilityReason) {
            this(id, name, level, star, power, status, troopLevel, available,
                    availabilityState, availabilityReason, 0, 0);
        }

        Hero(long id, String name, long level, long star, long power, String status,
             long troopLevel, boolean available, String availabilityState,
             String availabilityReason, int weaponLevel, int expeditionSkillLevel) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.level = level;
            this.star = star;
            this.power = power;
            this.status = status == null ? "" : status;
            this.profession = HeroCatalog.profession(id);
            this.generation = HeroCatalog.generation(id);
            int sd = (int) (Math.max(0L, star) / 6L);
            this.starDisplay = Math.max(0, Math.min(5, sd));
            this.capLevel = troopLevel;
            this.troopCapacity = troopLevel > 0L
                    ? MarchCapacityCalculator.heroCapacity(troopLevel) : 0L;
            this.available = available;
            this.availabilityState = availabilityState == null ? "AVAILABLE" : availabilityState;
            this.availabilityReason = availabilityReason == null ? "" : availabilityReason;
            this.weaponLevel = Math.max(0, Math.min(10, weaponLevel));
            this.expeditionSkillLevel = Math.max(0, Math.min(5, expeditionSkillLevel));
        }

        /** 复制并按「是否在途行军」刷新可用性（RESERVED / AVAILABLE）。 */
        Hero withAvailability(boolean busy) {
            if (available == !busy) return this;
            return new Hero(id, name, level, star, power, status, capLevel,
                    !busy, busy ? "RESERVED" : "AVAILABLE", busy ? "前序占用" : "英雄在家",
                    weaponLevel, expeditionSkillLevel);
        }

        /** 目录 v2 条目（契约 §3.2；额外保留旧键 status 供老 APK 过渡）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", HeroCatalog.cleanName(name));
            m.put("profession", profession);
            m.put("professionType", profession);
            m.put("professionName", HeroCatalog.professionName(id));
            m.put("level", level);
            m.put("star", star);
            m.put("starDisplay", starDisplay);
            m.put("power", power);
            m.put("generation", generation);
            m.put("troopCapacity", troopCapacity);
            m.put("available", available);
            m.put("availabilityState", availabilityState);
            m.put("availabilityReason", availabilityReason);
            // 契约 2026-11-02 §1：预设编队（打熊队/熊上车排序）依赖的两个字段
            m.put("weaponLevel", weaponLevel);
            m.put("expeditionSkillLevel", expeditionSkillLevel);
            // 旧 APK 字段（一版过渡；新客户端不读）
            m.put("status", status);
            return m;
        }

        /** 供包内单测/日志的紧凑描述。 */
        String describe() {
            return "{id=" + id + ",name=" + name + ",lv=" + level + ",star=" + star
                    + ",prof=" + profession + ",gen=" + generation + ",cap=" + troopCapacity
                    + ",avail=" + availabilityState + '}';
        }
    }

    /** 一次采集的目录快照（不可变视图；state v2 全量）。 */
    public static final class Snapshot {
        public final long capturedAt;
        /** 旧口径：世界出征队列数（不为 0 时老 APK 仍可显示；新口径见 march）。 */
        public final int capacity;
        public final List<Hero> heroes;
        public final long[] bestSoldierIds;
        public final Map<Long, Long> soldierCounts;
        public final long[] soldierStock;
        /** 游戏内编队（prefab 镜像）。 */
        public final List<GameFormations.Slot> gameSlots;
        /** 出征容量（0/1/2/3 英雄档 + limits）。 */
        public final MarchCapacity.Model march;
        /** 自动编队全局比例 [盾,矛,弓]（角色参数或默认 50/20/30）。 */
        public final int[] automaticSettings;
        /** 编队记忆状态 {daily:{...},battle:{...}}。 */
        public final Map<String, Object> memory;
        /** 巨熊模板绑定。 */
        public final Map<String, Object> bear;
        /** 雪猿宠物容量。 */
        public final Map<String, Object> avalanche;
        /** 角色标识（state 键隔离用；未知时空串/0）。 */
        public final String fpid;
        public final long sid;
        public final String uid;

        Snapshot(long capturedAt, int capacity, List<Hero> heroes, long[] bestSoldierIds,
                 Map<Long, Long> soldierCounts, long[] soldierStock,
                 List<GameFormations.Slot> gameSlots, MarchCapacity.Model march,
                 int[] automaticSettings, Map<String, Object> memory, Map<String, Object> bear,
                 Map<String, Object> avalanche, String fpid, long sid, String uid) {
            this.capturedAt = capturedAt;
            this.capacity = capacity;
            this.heroes = heroes == null ? List.of() : List.copyOf(heroes);
            this.bestSoldierIds = bestSoldierIds == null ? new long[3] : bestSoldierIds;
            this.soldierCounts = soldierCounts == null ? Map.of() : soldierCounts;
            this.soldierStock = soldierStock == null ? new long[3] : soldierStock;
            this.gameSlots = gameSlots == null ? List.of() : List.copyOf(gameSlots);
            this.march = march == null ? MarchCapacity.emptyModel() : march;
            this.automaticSettings = automaticSettings == null
                    ? DEFAULT_AUTOMATIC_SETTINGS.clone() : automaticSettings.clone();
            this.memory = memory == null ? defaultMemory() : memory;
            this.bear = bear == null ? defaultBear() : bear;
            this.avalanche = avalanche == null ? new LinkedHashMap<>() : avalanche;
            this.fpid = fpid == null ? "" : fpid;
            this.sid = sid;
            this.uid = uid == null ? "" : uid;
        }

        /** 空快照（未采集/推送未到；不缓存）。 */
        static Snapshot empty(String fpid, long sid, String uid) {
            return new Snapshot(System.currentTimeMillis(), 0, List.of(), new long[3], Map.of(),
                    new long[3], List.of(), MarchCapacity.emptyModel(),
                    DEFAULT_AUTOMATIC_SETTINGS.clone(), defaultMemory(), defaultBear(),
                    new LinkedHashMap<>(), fpid, sid, uid);
        }

        /** 是否「空目录」（英雄/兵力/游戏编队都没采到；用于空快照保护）。 */
        public boolean isEmpty() {
            return heroes.isEmpty() && gameSlots.isEmpty()
                    && soldierStock[0] <= 0L && soldierStock[1] <= 0L && soldierStock[2] <= 0L;
        }

        /** 契约 §3.2 目录 v2 全量 JSON（有序 Map；JSONObject 直接可包）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", VERSION);
            root.put("capturedAt", capturedAt);
            root.put("fpid", fpid);
            root.put("sid", sid);
            root.put("uid", uid);
            root.put("configured", march.initialized);
            root.put("automaticAvailable", march.initialized);

            List<Map<String, Object>> hs = new ArrayList<>();
            Set<Long> unavailable = new LinkedHashSet<>();
            for (Hero h : heroes) {
                hs.add(h.toMap());
                if (!h.available) unavailable.add(h.id);
            }
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

            root.put("marchCapacity", march.toMap());

            Map<String, Object> auto = new LinkedHashMap<>();
            auto.put("infantryRatio", automaticSettings[0]);
            auto.put("lancerRatio", automaticSettings[1]);
            auto.put("marksmanRatio", automaticSettings[2]);
            root.put("automaticSettings", auto);

            List<Map<String, Object>> formations = new ArrayList<>();
            for (GameFormations.Slot slot : gameSlots) {
                Map<String, Object> m = slot.toMap();
                List<Long> unavailableHeroes = new ArrayList<>();
                for (Long h : slot.heroIds) {
                    if (unavailable.contains(h)) unavailableHeroes.add(h);
                }
                m.put("allHeroesAvailable", unavailableHeroes.isEmpty());
                m.put("unavailableHeroIds", unavailableHeroes);
                formations.add(m);
            }
            root.put("gameFormations", formations);
            root.put("avalanche", avalanche);
            root.put("memory", memory);
            root.put("bear", bear);
            // 旧 APK 兼容键（一版过渡；新口径读 marchCapacity）
            root.put("capacity", capacity);
            return root;
        }

        /** 游戏内编队条目（带可用性；契约 §3.1）。 */
        public List<Map<String, Object>> gameFormationMaps() {
            List<Map<String, Object>> out = new ArrayList<>();
            Set<Long> unavailable = new LinkedHashSet<>();
            for (Hero h : heroes) {
                if (!h.available) unavailable.add(h.id);
            }
            for (GameFormations.Slot slot : gameSlots) {
                Map<String, Object> m = slot.toMap();
                List<Long> unavailableHeroes = new ArrayList<>();
                for (Long h : slot.heroIds) {
                    if (unavailable.contains(h)) unavailableHeroes.add(h);
                }
                m.put("allHeroesAvailable", unavailableHeroes.isEmpty());
                m.put("unavailableHeroIds", unavailableHeroes);
                out.add(m);
            }
            return out;
        }
    }

    /** 每引擎的目录缓存（WeakHashMap 防会话销毁后泄漏）。 */
    private static final Map<WjdrEngine, Snapshot> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 每引擎的会话元数据（角色标识/自动比例/记忆/巨熊绑定；由 SessionKeeper 装配）。 */
    private static final Map<WjdrEngine, Meta> META =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Meta {
        String fpid = "";
        long sid;
        String uid = "";
        int[] automaticSettings = DEFAULT_AUTOMATIC_SETTINGS.clone();
        Map<String, Object> memory = defaultMemory();
        Map<String, Object> bear = defaultBear();
    }

    private FormationCatalog() {
    }

    // ------------------------------------------------------------------
    // 键
    // ------------------------------------------------------------------

    /** 新键：带 uid（契约 §3.2）。 */
    public static String stateKey(String fpid, String sid, String uid) {
        return STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + (sid == null ? "" : sid)
                + ":" + (uid == null ? "" : uid);
    }

    /** 旧键：无 uid（兼容老 APK 一版过渡）。 */
    public static String legacyStateKey(String fpid, String sid) {
        return STATE_KEY_PREFIX + (fpid == null ? "" : fpid) + ":" + (sid == null ? "" : sid);
    }

    /** 自动编队比例键：{@code srv:formation:autosettings:{fpid}:{sid}:{uid}}（契约 §3.2）。 */
    public static String autosettingsKey(String fpid, String sid, String uid) {
        return AUTOSETTINGS_KEY_PREFIX + (fpid == null ? "" : fpid) + ":"
                + (sid == null ? "" : sid) + ":" + (uid == null ? "" : uid);
    }

    // ------------------------------------------------------------------
    // 采集 / 缓存
    // ------------------------------------------------------------------

    /**
     * 采集/刷新目录（只读；幂等）。SessionKeeper online 后与周期采集一起调用、notify 钩子即时调用。
     *
     * <p>空快照保护（审计 P0-6）：新快照为空且已有好目录 → 保留好目录；空快照本身不缓存
     * （下次调用重试，避免「空目录被缓存 10 分钟」）。
     */
    public static void capture(WjdrEngine engine) {
        if (engine == null) return;
        try {
            Snapshot prev;
            synchronized (CACHE) {
                prev = CACHE.get(engine);
            }
            Snapshot next = build(engine, prev);
            if (next == null) return;
            if (next.isEmpty()) {
                if (prev != null && !prev.isEmpty()) {
                    // 保留旧的好目录（推送尚未到达/角色离线瞬间），不计为一次有效采集
                    return;
                }
                synchronized (CACHE) {
                    CACHE.remove(engine);
                }
                return;
            }
            synchronized (CACHE) {
                CACHE.put(engine, next);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 取目录快照（未采集则即时采集一次；引擎 null 返回空快照）。 */
    public static Snapshot catalog(WjdrEngine engine) {
        Meta meta = meta(engine);
        if (engine == null) {
            return Snapshot.empty(meta.fpid, meta.sid, meta.uid);
        }
        Snapshot s;
        synchronized (CACHE) {
            s = CACHE.get(engine);
        }
        if (s == null) {
            capture(engine);
            synchronized (CACHE) {
                s = CACHE.get(engine);
            }
        }
        return s != null ? s : Snapshot.empty(meta.fpid, meta.sid, meta.uid);
    }

    /** 目录快照（JSON 可序列化 Map；SessionKeeper 写 state 用；保持旧 Map 签名兼容）。 */
    public static Map<String, Object> snapshot(WjdrEngine engine) {
        return catalog(engine).toMap();
    }

    /** 目录快照 JSON 对象（契约 §3.1 JSONObject 形态；供 HTTP/动作层直接用）。 */
    public static JSONObject snapshotJson(WjdrEngine engine) {
        return new JSONObject(catalog(engine).toMap());
    }

    /** 单测：清空指定引擎缓存与元数据。 */
    public static void reset(WjdrEngine engine) {
        if (engine == null) return;
        synchronized (CACHE) {
            CACHE.remove(engine);
        }
        synchronized (META) {
            META.remove(engine);
        }
    }

    // ------------------------------------------------------------------
    // 会话元数据（SessionKeeper 装配）
    // ------------------------------------------------------------------

    /** 绑定角色标识（state 键/目录字段用；登录落地后由 SessionKeeper 调用）。 */
    public static void identify(WjdrEngine engine, String fpid, long sid, String uid) {
        if (engine == null) return;
        Meta m = meta(engine);
        synchronized (m) {
            m.fpid = fpid == null ? "" : fpid;
            m.sid = sid;
            m.uid = uid == null ? "" : uid;
        }
    }

    /** 自动编队全局比例（App 上传的角色参数或默认 50/20/30）。 */
    public static void setAutomaticSettings(WjdrEngine engine, int infantry, int lancer, int marksman) {
        if (engine == null) return;
        Meta m = meta(engine);
        synchronized (m) {
            m.automaticSettings = normalizeSettings(infantry, lancer, marksman);
        }
    }

    /** 编队记忆状态（FormationMemory.status 产出）。 */
    public static void setMemoryStatus(WjdrEngine engine, Map<String, Object> memory) {
        if (engine == null) return;
        Meta m = meta(engine);
        synchronized (m) {
            m.memory = memory == null ? defaultMemory() : memory;
        }
    }

    /** 巨熊模板绑定（state 键产出；无绑定用默认空对象）。 */
    public static void setBearBinding(WjdrEngine engine, Map<String, Object> bear) {
        if (engine == null) return;
        Meta m = meta(engine);
        synchronized (m) {
            m.bear = bear == null ? defaultBear() : bear;
        }
    }

    /** 角色参数里的自动比例：{@code {"automatic_settings":{...}}}（兼容对象/嵌套字符串；不合法返回 null）。 */
    public static int[] parseAutomaticSettings(String libraryJson) {
        if (libraryJson == null || libraryJson.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(libraryJson.trim());
            if (o.has("formation_library") && o.opt("formation_library") instanceof String) {
                return parseAutomaticSettings(o.optString("formation_library", ""));
            }
            JSONObject s = o.optJSONObject("automatic_settings");
            if (s == null) s = o.optJSONObject("automaticSettings");
            if (s == null) return null;
            int inf = s.optInt("infantry_ratio", s.optInt("infantryRatio", -1));
            int lanc = s.optInt("lancer_ratio", s.optInt("lancerRatio", -1));
            int mark = s.optInt("marksman_ratio", s.optInt("marksmanRatio", -1));
            if (inf < 0 || lanc < 0 || mark < 0 || inf + lanc + mark != 100) return null;
            return normalizeSettings(inf, lanc, mark);
        } catch (Throwable t) {
            return null;
        }
    }

    static int[] normalizeSettings(int infantry, int lancer, int marksman) {
        if (infantry < 0 || lancer < 0 || marksman < 0 || infantry + lancer + marksman != 100) {
            return DEFAULT_AUTOMATIC_SETTINGS.clone();
        }
        return new int[]{infantry, lancer, marksman};
    }

    private static Meta meta(WjdrEngine engine) {
        if (engine == null) return new Meta();
        synchronized (META) {
            Meta m = META.get(engine);
            if (m == null) {
                m = new Meta();
                META.put(engine, m);
            }
            return m;
        }
    }

    // ------------------------------------------------------------------
    // 契约 §3.1 公开访问器（签名冻结）
    // ------------------------------------------------------------------

    /** 英雄选项（强到弱；契约 §3.2 fields 全量，名字已去引号）。 */
    public static List<Map<String, Object>> heroOptions(WjdrEngine engine) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hero h : heroes(engine)) out.add(h.toMap());
        return out;
    }

    /**
     * 出征容量 + 0/1/2/3 英雄档 limits（契约 §3.2 marchCapacity）。
     *
     * <p><b>实时计算</b>（不读缓存快照）：容量依赖 buff/科技/家园等推送，这些推送到达后
     * 若读 capture 时刻的旧快照会给出过期上限（T3 {@code FormationResolver} 的
     * {@code expandedFixedTarget} 依赖同一份 bp/容量，必须与容量口径一致）。
     * 实时数据未同步（{@code initialized=false}）时回退缓存里的已同步模型。
     */
    public static Map<String, Object> marchCapacity(WjdrEngine engine) {
        MarchCapacity.Model live = MarchCapacity.of(engine);
        if (live.initialized) return live.toMap();
        Snapshot cached = catalog(engine);
        if (cached.march.initialized) return cached.march.toMap();
        return live.toMap();
    }

    /** 出征容量（Σ troopCapacity 公式；未同步 → 0，调用方视为未知不夹取）。 */
    public static long capacityForHeroes(WjdrEngine engine, List<Long> heroIds) {
        return MarchCapacity.capacityForHeroes(engine, heroIds);
    }

    /**
     * 兼容旧调用：世界出征队列容量（旧 state {@code capacity} 键口径）。
     *
     * <p>注意：这不是兵力上限——兵力上限请用 {@link #marchCapacity} /
     * {@link #capacityForHeroes}（审计 P0-4：旧口径在生产恒 0，已由 marchCapacity 取代）。
     */
    public static long capacity(WjdrEngine engine) {
        return catalog(engine).capacity;
    }

    /** 兼容旧调用（旧目录 {@code capacity} 键）：世界出征队列数。 */
    public static int worldQueueCapacity(WjdrEngine engine) {
        return catalog(engine).capacity;
    }

    /** 游戏内编队只读镜像（id=-slotId、source=GAME；契约 §3.1）。 */
    public static List<Map<String, Object>> gameFormations(WjdrEngine engine) {
        return catalog(engine).gameFormationMaps();
    }

    /** 游戏内编队槽（内部/记忆动作用；含原始 soldier id，可回发游戏）。 */
    public static List<GameFormations.Slot> gameSlots(WjdrEngine engine) {
        return catalog(engine).gameSlots;
    }

    /** 编队记忆状态 {daily:{exists,savedAt,slotCount,version},battle:{...}}。 */
    public static JSONObject memoryStatus(WjdrEngine engine) {
        return new JSONObject(catalog(engine).memory);
    }

    /** 巨熊模板绑定（供 App 刷新）。 */
    public static JSONObject bearBinding(WjdrEngine engine) {
        return new JSONObject(catalog(engine).bear);
    }

    /** 雪猿宠物容量（avalanche；契约 §3.2）。 */
    public static JSONObject avalanche(WjdrEngine engine) {
        return new JSONObject(catalog(engine).avalanche);
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

    /**
     * 当前在途行军的英雄 id（资产库 queue 域；供自动编队排除，防 403 重复选人）。
     * 资产库未接线/未 READY 返回空集合（不阻塞旧行为）。
     */
    public static Set<Long> busyHeroIds(WjdrEngine engine) {
        Set<Long> out = new LinkedHashSet<>();
        if (engine == null) return out;
        try {
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            if (lib != null && lib.ready() && lib.queue() != null) {
                for (AssetSnapshot.QueueMarch march : lib.queue().marches) {
                    if (march == null || march.heroIds == null) continue;
                    for (Long id : march.heroIds) {
                        if (id != null && id > 0L) out.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2026-11-04 真机：资产库 queue.marches 可能为空（实测 occupied=6 但 marches=0）→
        // 本方法恒返回空 → 自动编队选到正在驻防/出征的英雄 → 403 英雄被占用。
        // 兜底：直接读本角色 notify_world_player_init.march_info 的 squad_list.hero_id。
        try {
            Object wpi = engine.notifyData("notify_world_player_init");
            Map<String, Object> root = Exec.mapOf(wpi);
            if (root != null) {
                Object mi = root.get("march_info");
                collectMarchHeroes(mi, out, 0);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 递归收集 march_info（Map/List/嵌套包装）里的 squad_list.hero_id（在途/驻扎行军都算占用）。 */
    private static void collectMarchHeroes(Object node, Set<Long> out, int depth) {
        if (node == null || depth > 4 || out.size() >= 64) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectMarchHeroes(o, out, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        Map<String, Object> march = Exec.mapOf(m);
        if (march != null) {
            Map<String, Object> squad = Exec.mapOf(march.get("squad_list"));
            if (squad != null) {
                Object raw = squad.get("hero_id");
                if (raw == null) raw = squad.get("hero_ids");
                if (raw instanceof List) {
                    for (Object v : (List<?>) raw) {
                        Map<String, Object> pair = Exec.mapOf(v);
                        long id = pair == null || pair.isEmpty() ? Exec.longOf(v)
                                : firstHeroId(pair);
                        if (id > 0L) out.add(id);
                    }
                } else if (raw instanceof Map) {
                    for (Object v : ((Map<?, ?>) raw).values()) {
                        Map<String, Object> pair = Exec.mapOf(v);
                        long id = pair == null || pair.isEmpty() ? Exec.longOf(v) : firstHeroId(pair);
                        if (id > 0L) out.add(id);
                    }
                }
            }
        }
        // 继续下钻未知包装（如 {"march_info":{"5001":{...}}}）
        for (Object v : m.values()) {
            if (v instanceof Map || v instanceof List) collectMarchHeroes(v, out, depth + 1);
        }
    }

    /** 英雄对 {k,v} 取 v（新口径）→ 兼容 id / k。 */
    private static long firstHeroId(Map<String, Object> pair) {
        long id = Exec.longOf(pair.get("v"));
        if (id <= 0L) id = Exec.longOf(pair.get("hero_id"));
        if (id <= 0L) id = Exec.longOf(pair.get("id"));
        if (id <= 0L) id = Exec.longOf(pair.get("k"));
        return id;
    }

    /**
     * 入站推送增量（SessionKeeper 资产库钩子每个 inbound 都调；轻量、幂等）。
     *
     * <ul>
     *   <li>{@code notify_soldier_update{type:add|del,data:[{id,count}]}}：出征带走/回城归还兵力，
     *       只做增量（不到缓存不存在时忽略，下次 capture 从 init 全量重建）；</li>
     *   <li>{@code notify_hero_init} / {@code notify_soldier_init}：init 到达即时重建目录
     *       （修复「懒采集早于推送 → 空目录被缓存 10 分钟」的 218 次 SKIP）；</li>
     *   <li>{@code notify_soldier_formation_update}：游戏内编队增量刷新。</li>
     * </ul>
     */
    public static void onNotify(WjdrEngine engine, String proto, Map<String, Object> content) {
        if (engine == null || proto == null) return;
        try {
            switch (proto) {
                case "notify_soldier_update":
                    applySoldierUpdate(engine, content);
                    break;
                case "notify_hero_init":
                case "notify_soldier_init":
                    capture(engine);
                    break;
                case "notify_soldier_formation_update":
                    applyFormationUpdate(engine, content);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 恢复成功后本地回写槽（FormationMemory 用；不用等下一次推送）。 */
    public static void applyRestoredPrefab(WjdrEngine engine, int slotId,
                                          Map<String, Object> prefab) {
        if (engine == null || slotId <= 0 || prefab == null) return;
        try {
            Snapshot prev;
            synchronized (CACHE) {
                prev = CACHE.get(engine);
            }
            if (prev == null) {
                capture(engine);
                synchronized (CACHE) {
                    prev = CACHE.get(engine);
                }
            }
            if (prev == null) return;
            GameFormations.Slot slot = GameFormations.fromPrefab(slotId, prefab);
            if (slot == null) return;
            List<GameFormations.Slot> slots = new ArrayList<>();
            boolean replaced = false;
            for (GameFormations.Slot s : prev.gameSlots) {
                if (s.id == slotId) {
                    slots.add(slot);
                    replaced = true;
                } else {
                    slots.add(s);
                }
            }
            if (!replaced) slots.add(slot);
            slots.sort((a, b) -> Integer.compare(a.id, b.id));
            Snapshot next = new Snapshot(prev.capturedAt, prev.capacity, prev.heroes,
                    prev.bestSoldierIds, prev.soldierCounts, prev.soldierStock, slots,
                    prev.march, prev.automaticSettings, prev.memory, prev.bear, prev.avalanche,
                    prev.fpid, prev.sid, prev.uid);
            synchronized (CACHE) {
                CACHE.put(engine, next);
            }
        } catch (Throwable ignored) {
        }
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

    /** 默认编队记忆状态（无快照；契约 §3.2）。 */
    static Map<String, Object> defaultMemory() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("daily", emptyMemoryKind());
        m.put("battle", emptyMemoryKind());
        return m;
    }

    static Map<String, Object> emptyMemoryKind() {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("exists", false);
        k.put("savedAt", 0L);
        k.put("slotCount", 0);
        k.put("version", 0L);
        return k;
    }

    /** 默认巨熊绑定（未应用；契约 §3.2）。 */
    static Map<String, Object> defaultBear() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("applied", false);
        b.put("templateId", "");
        b.put("title", "");
        b.put("generation", 0);
        b.put("tier", "");
        b.put("launchFormationId", 0L);
        b.put("joinFormationIds", new ArrayList<>());
        return b;
    }

    // ------------------------------------------------------------------
    // 采集实现（防御式；推送缺失=沿用旧值/空目录，绝不抛异常）
    // ------------------------------------------------------------------

    private static Snapshot build(WjdrEngine engine, Snapshot prev) {
        long now = System.currentTimeMillis();
        Meta meta = meta(engine);

        // 英雄：hero_init 未到/为空时沿用旧目录（防「先空后满」把好目录冲掉）
        // 2026-11-09：英雄实力按网页版 HeroPowerCatalog 计算（等级/星级/技能/装备/专武），
        // 装备从 notify_equipment_info（登录全量）与 notify_equipment_update（增量）合并。
        List<Hero> heroes = heroesOf(engine.notifyData("notify_hero_init"), equipmentByHero(engine));
        if (heroes.isEmpty() && prev != null && !prev.heroes.isEmpty()) {
            heroes = prev.heroes;
        } else {
            heroes = withAvailability(heroes, busyHeroIds(engine));
        }

        Map<Long, Long> soldiers = Exec.soldiers(engine);
        if (soldiers.isEmpty() && prev != null) soldiers = prev.soldierCounts;
        long[] bestIds = Exec.bestSoldierIds(soldiers);
        long[] stock = baseStock(soldiers, bestIds);

        int capacity = worldCapacity(engine);
        if (capacity <= 0 && prev != null) capacity = prev.capacity;

        // 游戏内编队：prefab 存在才覆盖（无 prefab 的帧沿用旧值）
        List<GameFormations.Slot> slots = GameFormations.parse(engine.notifyData("notify_soldier_init"));
        if (slots == null) slots = prev != null ? prev.gameSlots : List.of();

        MarchCapacity.Model march = MarchCapacity.of(engine);
        if (!march.initialized && prev != null && prev.march.initialized) march = prev.march;

        Map<String, Object> avalanche = MarchCapacity.avalanche(engine);
        if (Boolean.FALSE.equals(avalanche.get("initialized")) && prev != null
                && Boolean.TRUE.equals(prev.avalanche.get("initialized"))) {
            avalanche = prev.avalanche;
        }

        synchronized (meta) {
            return new Snapshot(now, capacity, heroes, bestIds, soldiers, stock, slots, march,
                    meta.automaticSettings.clone(), meta.memory, meta.bear, avalanche,
                    meta.fpid, meta.sid, meta.uid);
        }
    }

    /** 在途行军英雄 → RESERVED；其余 AVAILABLE（旧版 mergeHeroAvailability 同义）。 */
    static List<Hero> withAvailability(List<Hero> heroes, Set<Long> busy) {
        List<Hero> out = new ArrayList<>(heroes.size());
        for (Hero h : heroes) {
            out.add(h.withAvailability(busy != null && busy.contains(h.id)));
        }
        return out;
    }

    /** {@code notify_soldier_update} 增量（add/del）。 */
    private static void applySoldierUpdate(WjdrEngine engine, Map<String, Object> content) {
        if (content == null) return;
        Snapshot old;
        synchronized (CACHE) {
            old = CACHE.get(engine);
        }
        if (old == null) return;   // 未采集：下次 capture 从 init 全量重建
        Map<Long, Long> counts = new LinkedHashMap<>(old.soldierCounts);
        boolean del = "del".equals(String.valueOf(content.get("type")));
        for (Map<String, Object> item : Exec.mapsOf(content.get("data"))) {
            long id = Exec.longOf(item.get("id"));
            long n = Math.max(0L, Exec.longOf(item.get("count")));
            if (id <= 0L) continue;
            if (del) {
                long left = Math.max(0L, counts.getOrDefault(id, 0L) - n);
                if (left <= 0L) counts.remove(id); else counts.put(id, left);
            } else {
                counts.merge(id, n, Long::sum);
            }
        }
        long[] bestIds = Exec.bestSoldierIds(counts);
        long[] stock = baseStock(counts, bestIds);
        Snapshot next = new Snapshot(old.capturedAt, old.capacity, old.heroes, bestIds, counts,
                stock, old.gameSlots, old.march, old.automaticSettings, old.memory, old.bear,
                old.avalanche, old.fpid, old.sid, old.uid);
        synchronized (CACHE) {
            CACHE.put(engine, next);
        }
    }

    /** {@code notify_soldier_formation_update} 增量。 */
    private static void applyFormationUpdate(WjdrEngine engine, Map<String, Object> content) {
        Snapshot old;
        synchronized (CACHE) {
            old = CACHE.get(engine);
        }
        if (old == null) {
            capture(engine);
            synchronized (CACHE) {
                old = CACHE.get(engine);
            }
            if (old == null) return;
        }
        List<GameFormations.Slot> slots = GameFormations.applyUpdate(old.gameSlots, content);
        if (slots == old.gameSlots) return;
        Snapshot next = new Snapshot(System.currentTimeMillis(), old.capacity, old.heroes,
                old.bestSoldierIds, old.soldierCounts, old.soldierStock, slots, old.march,
                old.automaticSettings, old.memory, old.bear, old.avalanche, old.fpid, old.sid, old.uid);
        synchronized (CACHE) {
            CACHE.put(engine, next);
        }
    }

    /** 与 Exec.baseSoldiers 同口径：各兵种最高档 id 的可用数量（不重复计入低档，防超发）。 */
    /**
     * 各兵种可用量（2026-11-04 用户口径：能满编尽量满编，没有能带多少带多少）。
     *
     * <p>旧实现只统计「最高档」：实测号上有 T11 盾 400 + T10 盾 69605 时只算出 400，
     * 驻防只带 400 盾（甚至因为档位对不上请求超量 → 401）。现按兵种累计**全部档位**，
     * 再由 {@link #soldierPairsFor} 从高档往低档装兵。
     */
    static long[] baseStock(Map<Long, Long> soldiers, long[] bestIds) {
        long[] out = new long[3];
        if (soldiers == null) return out;
        for (Map.Entry<Long, Long> e : soldiers.entrySet()) {
            long id = e.getKey() == null ? 0L : e.getKey();
            long cnt = e.getValue() == null ? 0L : e.getValue();
            if (id <= 0 || cnt <= 0) continue;
            int line = (int) (id / 10000);
            if (line < 1 || line > 3) continue;
            out[line - 1] += cnt;
        }
        return out;
    }

    /** 装备推送 → 英雄 id → 装备明细列表（登录全量 info + 增量 update 合并；uid 覆盖）。 */
    @SuppressWarnings("unchecked")
    static Map<Long, List<Map<String, Object>>> equipmentByHero(WjdrEngine engine) {
        Map<Long, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        if (engine == null) return out;
        Map<Long, Map<String, Object>> equipByUid = new java.util.LinkedHashMap<>();
        for (String proto : new String[]{"notify_equipment_info", "notify_equipment_update"}) {
            Object raw;
            try {
                raw = engine.notifyData(proto);
            } catch (Throwable t) {
                continue;
            }
            Map<String, Object> root = Exec.mapOf(raw);
            if (root == null) continue;
            Object detail = root.get("equip_detail");
            if (detail == null) detail = root.get("detail");
            Map<String, Object> details = Exec.mapOf(detail);
            if (details == null) continue;
            for (Map.Entry<String, Object> e : details.entrySet()) {
                Map<String, Object> d = Exec.mapOf(e.getValue());
                if (d == null) continue;
                long uid = Exec.longOf(d.get("uid"));
                if (uid <= 0L) {
                    try {
                        uid = Long.parseLong(e.getKey());
                    } catch (Throwable ignored) {
                    }
                }
                if (uid > 0L) equipByUid.put(uid, d);
            }
        }
        for (Map<String, Object> d : equipByUid.values()) {
            long hid = Exec.longOf(d.get("hid"));
            if (hid <= 0L) continue;
            out.computeIfAbsent(hid, k -> new java.util.ArrayList<>()).add(d);
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
        return heroesOf(heroInit, null);
    }

    /** 英雄目录（2026-11-09）：带装备明细 → 计算英雄实力（power）。 */
    @SuppressWarnings("unchecked")
    static List<Hero> heroesOf(Map<String, Object> heroInit,
                               Map<Long, List<Map<String, Object>>> equipByHero) {
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
            long hid = Exec.longOf(m.get("id"));
            if (hid <= 0) hid = Exec.longOf(m.get("hero_id"));
            List<Map<String, Object>> equip = equipByHero == null ? null : equipByHero.get(hid);
            Hero h = heroOf(m, equip);
            if (h != null) out.add(h);
        }
        out.sort(HERO_STRENGTH);
        return out;
    }

    private static Hero heroOf(Map<String, Object> m) {
        return heroOf(m, null);
    }

    private static Hero heroOf(Map<String, Object> m, List<Map<String, Object>> equipment) {
        if (m == null) return null;
        long id = Exec.longOf(m.get("id"));
        if (id <= 0) id = Exec.longOf(m.get("hero_id"));
        if (id <= 0) return null;
        // 名字：推送通常没有 name（生产实证），回退内置目录；两面都过 cleanName 防脏引号
        String name = HeroCatalog.cleanName(firstString(m, "name", "nickName", "nick_name"));
        if (name.isEmpty()) name = HeroCatalog.name(id);
        long level = firstLong(m, "level", "lv", "lev");
        long star = firstLong(m, "star", "star_lv", "starLevel");
        long power = firstLong(m, "power", "fight_num", "fight_capacity",
                "fight", "fight_power", "combat_power", "power_value");
        // 2026-11-09：协议无战力字段 → 用网页版 HeroPowerCatalog 公式估算（含装备/专武）；
        // 协议若显式给了 power 则保留协议值（HeroPowerCatalog.power 也会优先返回显式值）。
        if (power <= 0L) {
            try {
                long computed = HeroPowerCatalog.power(m, equipment);
                if (computed > 0L) power = computed;
            } catch (Throwable ignored) {
            }
        }
        String status = firstString(m, "status", "state");
        long trainingLev = firstLong(m, "training_lev", "trainingLev");
        long capLevel = Math.max(level, trainingLev);   // 容量按有效等级（同 MarchCapacityCalculator）
        // 2026-11-03：生产帧部分英雄只有 training_lev（lev=1），目录展示/排序按有效等级，
        // 避免情报英雄下拉全部显示 Lv1。
        level = capLevel;
        return new Hero(id, name, level, star, power, status,
                capLevel, true, "AVAILABLE", "英雄在家",
                weaponLevelOf(m), expeditionSkillLevelOf(m, id));
    }

    /**
     * 专武等级（契约 2026-11-02 §1）：读 {@code elv}（兼容 weaponLevel/weapon_level 等别名），
     * 0..10；旧帧缺字段 → 0。
     */
    static int weaponLevelOf(Map<String, Object> hero) {
        if (hero == null) return 0;
        long v = Math.max(0L, firstLong(hero, "elv", "weaponLevel", "weapon_level",
                "exclusiveWeaponLevel", "exclusive_weapon_level"));
        return (int) Math.min(10L, v);
    }

    /**
     * 队长第一个远征技能等级（契约 2026-11-02 §1）：{@code skills} 中 skill id =
     * {@code heroId*10+1} 的 {@code lv}，0..5；旧帧缺字段/解析失败 → 0。
     *
     * <p>技能槽位口径（真机帧 {@code skills} 实测）：槽 0 = 基础技能（实测恒 1），
     * 槽 1..3 = 远征技能 1..3，槽 4..6 = 探索技能，槽 7..8 = 专武技能；因此「第一个远征
     * 技能」取槽 1；若帧里没有槽 1 再回退槽 0（老帧防御）。
     *
     * <p>宽容兼容三种实证形状：{@code [{"id":500111,"lv":5}]}、
     * {@code {"500111.0":{"lv":5}}}、{@code {"500111.0":5}}。
     */
    static int expeditionSkillLevelOf(Map<String, Object> hero, long heroId) {
        if (hero == null || heroId <= 0L) return 0;
        Object raw = hero.get("skills");
        if (raw == null) raw = hero.get("skill");
        if (raw == null) return 0;
        long target = heroId * 10L + 1L;   // skill id = heroId*10+1（第一个远征技能槽位）
        List<Map<String, Object>> skills = new ArrayList<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                long keyId = tolerantId(e.getKey());
                Map<String, Object> inner = Exec.mapOf(e.getValue());
                if (inner == null) {
                    // 值直接是等级：{"500110.0": 5}
                    if (keyId == target) {
                        return (int) Math.min(5L, Math.max(0L, Exec.longOf(e.getValue())));
                    }
                    continue;
                }
                if (!inner.containsKey("id") && !inner.containsKey("skill_id")) {
                    inner = new LinkedHashMap<>(inner);
                    if (keyId > 0L) inner.put("id", keyId);
                }
                skills.add(inner);
            }
        } else {
            skills.addAll(Exec.mapsOf(raw));
        }
        for (Map<String, Object> s : skills) {
            if (s == null) continue;
            long sid = firstLong(s, "id", "skill_id", "sid");
            if (sid != target) continue;
            long lv = firstLong(s, "lv", "level", "skill_lv", "skillLevel", "use_skill_lv");
            return (int) Math.min(5L, Math.max(0L, lv));
        }
        // 老帧防御：没有槽 1 时回退槽 0
        return target == heroId * 10L + 1L
                ? expeditionSkillLevelOfSlot0(hero, heroId) : 0;
    }

    /** 槽 0 兜底（仅老帧/异常帧；正常帧第一个远征技能=槽 1）。 */
    private static int expeditionSkillLevelOfSlot0(Map<String, Object> hero, long heroId) {
        Object raw = hero == null ? null : hero.get("skills");
        if (raw == null) return 0;
        long target = heroId * 10L;
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (tolerantId(e.getKey()) != target) continue;
                Map<String, Object> inner = Exec.mapOf(e.getValue());
                if (inner == null) return (int) Math.min(5L, Math.max(0L, Exec.longOf(e.getValue())));
                long sid = firstLong(inner, "id", "skill_id", "sid");
                if (sid == 0L) {
                    long lv = firstLong(inner, "lv", "level", "skill_lv", "skillLevel", "use_skill_lv");
                    return (int) Math.min(5L, Math.max(0L, lv));
                }
                if (sid == target) {
                    return (int) Math.min(5L, Math.max(0L,
                            firstLong(inner, "lv", "level", "skill_lv", "skillLevel", "use_skill_lv")));
                }
            }
        } else {
            for (Map<String, Object> s : Exec.mapsOf(raw)) {
                if (s == null) continue;
                if (firstLong(s, "id", "skill_id", "sid") == target) {
                    return (int) Math.min(5L, Math.max(0L,
                            firstLong(s, "lv", "level", "skill_lv", "skillLevel", "use_skill_lv")));
                }
            }
        }
        return 0;
    }

    /** 键/值两栖数字解析：{@code "500110.0"}、{@code 500110}、{@code 500110L} 均可。 */
    private static long tolerantId(Object raw) {
        long direct = Exec.longOf(raw);
        if (direct > 0L) return direct;
        if (raw == null) return 0L;
        String s = String.valueOf(raw).trim();
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String firstString(Map<String, Object> m, String... keys) {
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

    /** 世界出征队列容量：{@code notify_world_player_init.world.capacity}（旧口径；取不到 0）。 */
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

    /** 诊断用：目录摘要（日志用，不含敏感信息）。 */
    public static String describe(WjdrEngine engine) {
        Snapshot s = catalog(engine);
        return "capturedAt=" + s.capturedAt + " heroes=" + s.heroes.size()
                + " soldiers=[" + s.soldierStock[0] + ',' + s.soldierStock[1] + ','
                + s.soldierStock[2] + "] capInit=" + s.march.initialized
                + " limits=" + s.march.threeHeroCapacity + '/' + s.march.twoHeroCapacity + '/'
                + s.march.oneHeroCapacity + '/' + s.march.zeroHeroCapacity
                + " gameFormations=" + s.gameSlots.size();
    }

}
