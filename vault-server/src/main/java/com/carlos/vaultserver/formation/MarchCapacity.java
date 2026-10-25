package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.MarchCapacityCalculator;
import com.carlos.vaultserver.task.Exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出征容量（兵力上限）目录 v2 —— 契约 §3.2 {@code marchCapacity} 的唯一计算口。
 *
 * <p><b>为什么必须重做</b>（服务端审计 P0-4）：旧目录 {@code capacity} 读
 * {@code notify_world_player_init.world.capacity}（生产不存在该键 → 恒 0），App 永远拿不到上限；
 * 生产账面三万+兵却提示 0。真实口径是共享公式 {@link MarchCapacityCalculator}：
 * <pre>
 * capacity = floor((Σ英雄容量(等级) + 固定容量) × (10000 + 万分比Buff) / 10000)
 * 固定容量 = 500 + 指挥中心(331) + 科技 + 专家(7004) + 家园(7000/7036) + buff(10506/10507)
 * 万分比  = Σ buff(10508)
 * </pre>
 *
 * <p><b>0/1/2/3 英雄分档</b>（网页版 {@code DailyRoleFormationLimits} 同构）：
 * 0H = 固定容量；1H/2H/3H = 固定 + 当前英雄目录里「容量最大的 1/2/3 名英雄」之和，
 * 上限 3 名（游戏每队盾/矛/弓各一）。
 *
 * <p><b>雪猿（avalanche）宠物容量</b>（旧版 {@code AvalancheCapacityState}）：
 * 宠物 55009（雪猿）突破等级决定技能等级；技能 550091 使用后 7200 秒内按等级加容量
 * （1..10 级 → 1500..15000，逐级 +1500）。这里只做展示口径（active 时 troopBonus 才生效），
 * 服务端任务派兵仍走 {@link MarchCapacityCalculator}（宁低勿高，防 406）。
 *
 * <p>纯 java.util + org.json 无关；未同步任何推送时返回 {@code initialized=false}（App 轮询等待）。
 */
public final class MarchCapacity {

    /** 出征编队英雄上限（盾/矛/弓各一）。 */
    public static final int HERO_MAX = 3;

    /** App 展示文案（与网页版/规格 §2.4 逐字一致）。 */
    public static final String MESSAGE_SYNCED = "登录容量状态已同步";
    /** App 等待文案（{@code configured=false} 时轮询）。 */
    public static final String MESSAGE_WAITING = "等待登录容量同步";
    /** 数据缺失说明（{@link #noteOf} 用，不直接下发）。 */
    static final String MISSING_COMMAND_CENTER = "指挥中心等级未同步";

    /** 雪猿宠物 id / 技能 id / 生效秒数 / 每级容量加成（旧版常量逐项移植）。 */
    public static final long AVALANCHE_PET_ID = 55009L;
    public static final long AVALANCHE_SKILL_ID = 550091L;
    public static final long AVALANCHE_ACTIVE_SECONDS = 7200L;
    private static final long[] AVALANCHE_TROOP_BONUSES =
            {1500L, 3000L, 4500L, 6000L, 7500L, 9000L, 10500L, 12000L, 13500L, 15000L};

    /** 一次容量估算（0/1/2/3 英雄分档 + 固定部分）。 */
    public static final class Model {
        public final boolean initialized;
        public final String message;
        public final long fixedCapacity;
        public final long percentageBasisPoints;
        public final long zeroHeroCapacity;
        public final long oneHeroCapacity;
        public final long twoHeroCapacity;
        public final long threeHeroCapacity;
        /** 缺失项说明（诊断用；生产日志可查）。 */
        public final String note;

        Model(boolean initialized, long fixedCapacity, long percentageBasisPoints,
              long zeroHeroCapacity, long oneHeroCapacity, long twoHeroCapacity,
              long threeHeroCapacity, String note) {
            this.initialized = initialized;
            this.message = initialized ? MESSAGE_SYNCED : MESSAGE_WAITING;
            this.fixedCapacity = Math.max(0L, fixedCapacity);
            this.percentageBasisPoints = Math.max(0L, percentageBasisPoints);
            this.zeroHeroCapacity = Math.max(0L, zeroHeroCapacity);
            this.oneHeroCapacity = Math.max(0L, oneHeroCapacity);
            this.twoHeroCapacity = Math.max(0L, twoHeroCapacity);
            this.threeHeroCapacity = Math.max(0L, threeHeroCapacity);
            this.note = note == null ? "" : note;
        }

        /** 按英雄数取上限（0..3；越界夹到 0/3）。 */
        public long limitFor(int heroCount) {
            if (heroCount <= 0) return zeroHeroCapacity;
            if (heroCount == 1) return oneHeroCapacity;
            if (heroCount == 2) return twoHeroCapacity;
            return threeHeroCapacity;
        }

        /** 契约 §3.2 的 limits 对象：[3H,2H,1H,0H]。 */
        public long[] limits() {
            return new long[]{threeHeroCapacity, twoHeroCapacity, oneHeroCapacity, zeroHeroCapacity};
        }

        /** 契约 §3.2 下发 JSON（字段名冻结）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("initialized", initialized);
            m.put("message", message);
            m.put("fixedCapacity", fixedCapacity);
            m.put("percentageBasisPoints", percentageBasisPoints);
            m.put("zeroHeroCapacity", zeroHeroCapacity);
            m.put("oneHeroCapacity", oneHeroCapacity);
            m.put("twoHeroCapacity", twoHeroCapacity);
            m.put("threeHeroCapacity", threeHeroCapacity);
            Map<String, Object> limits = new LinkedHashMap<>();
            limits.put("threeHeroLimit", threeHeroCapacity);
            limits.put("twoHeroLimit", twoHeroCapacity);
            limits.put("oneHeroLimit", oneHeroCapacity);
            limits.put("zeroHeroLimit", zeroHeroCapacity);
            m.put("limits", limits);
            return m;
        }
    }

    private MarchCapacity() {
    }

    /** 估算当前角色的出征容量（0/1/2/3 英雄分档）。引擎为 null → 空模型（未同步）。 */
    public static Model of(WjdrEngine engine) {
        if (engine == null) return emptyModel();
        try {
            MarchCapacityCalculator.Estimate base = MarchCapacityCalculator.estimateSquad(engine, List.of());
            long fixedRaw = base.fixedCapacity;
            long bp = base.bp;
            // heroOk 对空英雄列表恒 false，用 note 判断固定容量是否已同步（指挥中心是最主要来源）
            String note = base.note == null ? "" : base.note;
            boolean initialized = !note.contains(MISSING_COMMAND_CENTER);

            if (!initialized) return emptyModel(note);
            List<Long> heroCaps = heroTroopCapacities(engine);
            long s1 = 0L, s2 = 0L, s3 = 0L;
            if (!heroCaps.isEmpty()) {
                s1 = heroCaps.get(0);
                s2 = s1 + (heroCaps.size() > 1 ? heroCaps.get(1) : 0L);
                s3 = s2 + (heroCaps.size() > 2 ? heroCaps.get(2) : 0L);
            }
            return new Model(initialized, fixedRaw, bp,
                    calc(fixedRaw, 0L, bp),
                    calc(fixedRaw, s1, bp),
                    calc(fixedRaw, s2, bp),
                    calc(fixedRaw, s3, bp), note);
        } catch (Throwable t) {
            return emptyModel();
        }
    }

    /** 契约 §3.1：{@code marchCapacity} 下发对象。 */
    public static Map<String, Object> toMap(WjdrEngine engine) {
        return of(engine).toMap();
    }

    /**
     * 指定英雄阵容的出征容量（契约 §2.4/§3.1：{@code floor((fixed + Σ troopCapacity) × (10000+bp)/10000)}）。
     *
     * <p>未同步（{@code initialized=false}）→ 0，调用方视为「未知、不夹取」（与
     * {@link MarchCapacityCalculator#capacityFor} 语义一致）；已同步但个别英雄等级缺失 → 该英雄记 0。
     */
    public static long capacityForHeroes(WjdrEngine engine, List<Long> heroIds) {
        if (engine == null) return 0L;
        Model model = of(engine);
        if (!model.initialized) return 0L;
        Map<Long, Long> levels = heroLevels(engine);
        long heroCap = 0L;
        if (heroIds != null) {
            for (Long id : heroIds) {
                if (id == null || id <= 0L) continue;
                Long lv = levels.get(id);
                if (lv != null) heroCap += MarchCapacityCalculator.heroCapacity(lv);
            }
        }
        return calc(model.fixedCapacity, heroCap, model.percentageBasisPoints);
    }

    /** 单英雄为该队贡献的容量（等级按 lev 与 training_lev 取大，同 MarchCapacityCalculator）。 */
    public static long troopCapacity(WjdrEngine engine, long heroId) {
        Long lv = heroLevels(engine).get(heroId);
        return lv == null ? 0L : MarchCapacityCalculator.heroCapacity(lv);
    }

    /** 当前目录里所有英雄的容量贡献（降序；供 1H/2H/3H 分档）。 */
    public static List<Long> heroTroopCapacities(WjdrEngine engine) {
        List<Long> out = new ArrayList<>();
        for (Long lv : heroLevels(engine).values()) {
            long cap = MarchCapacityCalculator.heroCapacity(lv);
            if (cap > 0L) out.add(cap);
        }
        out.sort((a, b) -> Long.compare(b, a));
        return out;
    }

    /**
     * 雪猿宠物容量（契约 §3.2 {@code avalanche}；旧版 {@code AvalancheCapacityState} 同款）。
     */
    public static Map<String, Object> avalanche(WjdrEngine engine) {
        boolean petsInit = false;
        boolean extraInit = false;
        boolean owned = false;
        long petBreakLevel = 0L;
        long useTime = 0L;
        long useSkillLevel = 0L;
        try {
            if (engine != null) {
                Map<String, Object> petInit = engine.notifyData("notify_pet_info");
                petsInit = petInit != null;
                PetSkillSupport.PetState pet = PetSkillSupport.pet(engine, AVALANCHE_PET_ID);
                if (pet != null && pet.unlocked) {
                    owned = true;
                    petBreakLevel = Math.max(0L, pet.breakLevel);
                }
                Map<String, Object> extra = engine.notifyData("notify_pet_ex_info");
                extraInit = extra != null;
                if (extraInit) {
                    Map<String, Object> info = firstMap(extra.get("info"), extra);
                    for (Map<String, Object> row : Exec.mapsOf(info.get("use_skill_info"))) {
                        if (Exec.longOf(row.get("skill_id")) != AVALANCHE_SKILL_ID) continue;
                        Map<String, Object> skill = Exec.mapOf(row.get("skill_info"));
                        if (skill == null) continue;
                        useTime = Math.max(0L, Exec.longOf(skill.get("use_time")));
                        useSkillLevel = Math.max(0L, Exec.longOf(skill.get("use_skill_lv")));
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        boolean initialized = petsInit && extraInit;
        long skillLevel = useSkillLevel > 0L ? useSkillLevel : petBreakLevel;
        long configuredBonus = avalancheTroopBonus(skillLevel);
        long activeUntil = useTime > 0L ? useTime + AVALANCHE_ACTIVE_SECONDS : 0L;
        long nowSec = serverSeconds(engine);
        boolean active = initialized && owned && nowSec > 0L && useTime > 0L
                && nowSec < activeUntil && configuredBonus > 0L;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("initialized", initialized);
        m.put("owned", owned);
        m.put("petBreakLevel", petBreakLevel);
        m.put("useTime", useTime);
        m.put("skillLevel", skillLevel);
        m.put("activeUntil", activeUntil);
        m.put("active", active);
        m.put("configuredTroopBonus", configuredBonus);
        m.put("troopBonus", active ? configuredBonus : 0L);
        return m;
    }

    /** 雪猿技能等级 → 容量加成（1..10 → 1500..15000；越界 0）。 */
    public static long avalancheTroopBonus(long skillLevel) {
        if (skillLevel <= 0L || skillLevel > AVALANCHE_TROOP_BONUSES.length) return 0L;
        return AVALANCHE_TROOP_BONUSES[(int) skillLevel - 1];
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    static Model emptyModel() {
        return emptyModel("");
    }

    static Model emptyModel(String note) {
        return new Model(false, 0L, 0L, 0L, 0L, 0L, 0L, note);
    }

    /** 容量公式（与 MarchCapacityCalculator.calculate 同款，避免反射调用私有方法）。 */
    static long calc(long fixed, long heroCap, long bp) {
        long subtotal = Math.max(0L, fixed + heroCap);
        long multiplier = Math.max(0L, 10000L + bp);
        return Math.floorDiv(subtotal * multiplier, 10000L);
    }

    /** heroId → 有效等级（lev 与 training_lev 取大；与 MarchCapacityCalculator 同口径）。 */
    static Map<Long, Long> heroLevels(WjdrEngine engine) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (engine == null) return out;
        Map<String, Object> hi = engine.notifyData("notify_hero_init");
        if (hi == null) return out;
        List<Map<String, Object>> rows;
        Object data = hi.get("data");
        if (data == null) data = hi.get("heros");
        if (data != null) {
            rows = Exec.mapsOf(data);
        } else {
            rows = new ArrayList<>();
            for (Object v : hi.values()) {
                Map<String, Object> m = Exec.mapOf(v);
                if (m != null) rows.add(m);
            }
            if (rows.isEmpty()) rows = Exec.mapsOf(hi);
        }
        for (Map<String, Object> m : rows) {
            long id = Exec.longOf(m.get("id"));
            if (id <= 0L) continue;
            long lv = Math.max(Exec.longOf(m.get("lev")), Exec.longOf(m.get("training_lev")));
            if (lv <= 0L) continue;
            out.merge(id, lv, Math::max);
        }
        return out;
    }

    private static Map<String, Object> firstMap(Object a, Map<String, Object> fallback) {
        Map<String, Object> m = Exec.mapOf(a);
        return m == null ? (fallback == null ? Map.of() : fallback) : m;
    }

    private static long serverSeconds(WjdrEngine engine) {
        try {
            if (engine != null && engine.serverClock() != null && engine.serverClock().locked()) {
                long s = engine.serverClock().nowServerSec();
                if (s > 0L) return s;
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() / 1000L;
    }
}
