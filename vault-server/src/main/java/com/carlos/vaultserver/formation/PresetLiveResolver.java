package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.HeroCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 预设编队「出征时实时重算英雄」（2026-11-03，task-7）。
 *
 * <p>背景：App 的 19 套预设编队（formation_library 中 id 9100000001..9100000019、
 * template_key=PRESET_*）在保存时把当时的英雄/兵量预算写进了库；英雄升级/升星/换专武后
 * 「最强盾/矛/弓」会变化，若服务端直接用库里的 hero_ids，预设就永远停留在旧英雄。
 *
 * <p>本类在 {@link FormationResolver#tryRoleCandidate} 解析 ROLE 编队前按当前
 * {@link FormationCatalog#heroes(WjdrEngine)} 实时重算英雄，规则与 App 端
 * {@code com.carlos.vault.daily.formation.PresetFormations} 逐条一致（本类不依赖 Android）。
 *
 * <p><b>只重算英雄</b>：id/name/soldiers/ratio/total/allocation_mode/template_key 等字段原样保留；
 * 兵量由 {@link FormationResolver} 处理——PERCENTAGE 预设强制按「预设比例 × 实时容量」重算
 * （忽略库内预算兵量），巨兽 MANUAL 固定 1/0/0。
 *
 * <p>纯逻辑（目录用 {@link FormationCatalog} 推送快照），JVM 单测可直接注入引擎替身。
 */
public final class PresetLiveResolver {

    /** 第一套预设 id（巨兽）。 */
    public static final long ID_FIRST = 9100000001L;
    /** 最后一套预设 id（熊上车10）。 */
    public static final long ID_LAST = 9100000019L;
    /** 采集「只带队长」的容量阈值。 */
    public static final long GATHER_CAPACITY_THRESHOLD = 100000L;
    /** 队长第一个远征技能满级（熊上车排序优先）。 */
    public static final int MAX_EXPEDITION_SKILL_LEVEL = 5;
    /** 专武满级。 */
    public static final int MAX_WEAPON_LEVEL = 10;

    // ==================== 具名英雄 id（与 App PresetFormations 一致） ====================

    public static final long HERO_BORGAN = 50029L;
    public static final long HERO_GINA = 50008L;
    public static final long HERO_MIA = 50016L;
    public static final long HERO_JASMINE = 50012L;
    public static final long HERO_BRADLEY = 50033L;
    public static final long HERO_GREG = 50020L;
    public static final long HERO_HECTOR = 50024L;
    public static final long HERO_NEMO = 50011L;
    public static final long HERO_NATALIA = 50009L;
    public static final long HERO_GATHER_MEAT = 50003L;
    public static final long HERO_GATHER_WOOD = 50000L;
    public static final long HERO_GATHER_COAL = 50002L;
    public static final long HERO_GATHER_IRON = 50001L;

    // ==================== 模板键（PRESET_*） ====================

    public static final String TPL_BEHEMOTH = "PRESET_BEHEMOTH";
    public static final String TPL_HUNT = "PRESET_HUNT";
    public static final String TPL_BEAR_TEAM = "PRESET_BEAR_TEAM";
    public static final String TPL_SHIELD_BOW = "PRESET_SHIELD_BOW";
    public static final String TPL_SHIELD_SPEAR = "PRESET_SHIELD_SPEAR";

    private static final String[] GATHER_CAPTAINS = {"克劳瑞斯", "尤金", "查理", "史密斯"};
    private static final long[] GATHER_CAPTAIN_IDS = {
            HERO_GATHER_MEAT, HERO_GATHER_WOOD, HERO_GATHER_COAL, HERO_GATHER_IRON};
    private static final String[] GATHER_TPL = {
            "PRESET_GATHER_MEAT", "PRESET_GATHER_WOOD",
            "PRESET_GATHER_COAL", "PRESET_GATHER_IRON"};
    private static final Set<Long> GATHER_CAPTAIN_ID_SET = buildSet(GATHER_CAPTAIN_IDS);

    private static final String[] BEAR_RIDE_CAPTAINS = {
            "亨德里克", "格温", "书允", "布拉德利", "马格努斯",
            "布兰琪", "杰西", "杰赛尔", "赫罗尼莫", "索尼娅"};
    private static final String[] BEAR_RIDE_TPL = {
            "PRESET_BEAR_RIDE_1", "PRESET_BEAR_RIDE_2", "PRESET_BEAR_RIDE_3",
            "PRESET_BEAR_RIDE_4", "PRESET_BEAR_RIDE_5", "PRESET_BEAR_RIDE_6",
            "PRESET_BEAR_RIDE_7", "PRESET_BEAR_RIDE_8", "PRESET_BEAR_RIDE_9",
            "PRESET_BEAR_RIDE_10"};
    private static final long[] BEAR_RIDE_CAPTAIN_IDS = {
            50036L, 50026L, 50027L, 50033L, 50038L,
            50043L, 50007L, 50028L, 50011L, 50035L};
    private static final Set<Long> BEAR_RIDE_CAPTAIN_ID_SET = buildSet(BEAR_RIDE_CAPTAIN_IDS);

    private static final Map<String, String> ALIASES = buildAliases();
    private static final Map<String, Long> NAME_IDS = buildNameIds();

    /** 战力排序（与 App PresetFormations.POWER_DESC 完全一致）。 */
    private static final Comparator<FormationCatalog.Hero> POWER_DESC =
            new Comparator<FormationCatalog.Hero>() {
                @Override
                public int compare(FormationCatalog.Hero a, FormationCatalog.Hero b) {
                    int c = Long.compare(b.power, a.power);
                    if (c != 0) return c;
                    c = Integer.compare(b.starDisplay, a.starDisplay);
                    if (c != 0) return c;
                    c = Long.compare(b.level, a.level);
                    return c != 0 ? c : Long.compare(a.id, b.id);
                }
            };

    private PresetLiveResolver() {
    }

    // ==================== 公开 API ====================

    /** 是否预设编队：templateKey 以 {@code PRESET_} 开头，或 id 在 9100000001..9100000019。 */
    public static boolean isPreset(FormationSpec spec) {
        if (spec == null) return false;
        if (spec.id >= ID_FIRST && spec.id <= ID_LAST) return true;
        String key = spec.templateKey == null ? "" : spec.templateKey.trim().toUpperCase(Locale.ROOT);
        return key.startsWith("PRESET_");
    }

    /** 是否 PERCENTAGE 预设（兵量需按「预设比例 × 实时容量」重算）。 */
    public static boolean isPercentagePreset(FormationSpec spec) {
        return isPreset(spec)
                && FormationSpec.MODE_PERCENTAGE.equals(spec.effectiveAllocationMode());
    }

    /**
     * 按当前英雄目录实时重算预设编队的英雄（返回副本，不改入参）。
     *
     * <p>非预设 / engine 空 / 目录无英雄 / 无法识别的 PRESET 键 → 原样返回入参。
     * 其余情况复制 spec（保留 id/name/soldiers/ratio/total/allocation_mode/templateKey 等），
     * 仅替换 {@code heroIds}/{@code captainHeroId}；巨兽额外固定 MANUAL 1/0/0。
     */
    public static FormationSpec refresh(FormationSpec spec, WjdrEngine engine) {
        if (spec == null || engine == null || !isPreset(spec)) return spec;
        int index = presetIndex(spec);
        if (index <= 0) return spec;
        List<FormationCatalog.Hero> heroes = FormationCatalog.heroes(engine);
        if (heroes == null || heroes.isEmpty()) return spec;
        Map<Integer, List<Long>> teams = resolveTeams(new View(heroes), engine);
        List<Long> heroIds = teams.get(Integer.valueOf(index));
        FormationSpec out = copy(spec);
        out.heroIds = distinct(heroIds);
        out.captainHeroId = out.heroIds.isEmpty() ? null : out.heroIds.get(0);
        if (index == 1) {   // 巨兽：MANUAL 1/0/0（App 定义固定）
            out.allocationMode = FormationSpec.MODE_MANUAL;
            out.infantry = 1L;
            out.lancer = 0L;
            out.marksman = 0L;
        }
        return out;
    }

    /** 预设 index（1..19）；非已知预设返回 0。 */
    static int presetIndex(FormationSpec spec) {
        if (spec == null) return 0;
        if (spec.id >= ID_FIRST && spec.id <= ID_LAST) {
            return (int) (spec.id - ID_FIRST) + 1;
        }
        String key = spec.templateKey == null ? "" : spec.templateKey.trim().toUpperCase(Locale.ROOT);
        if (TPL_BEHEMOTH.equals(key)) return 1;
        if (TPL_HUNT.equals(key)) return 2;
        if (TPL_BEAR_TEAM.equals(key)) return 3;
        if (TPL_SHIELD_BOW.equals(key)) return 4;
        if (TPL_SHIELD_SPEAR.equals(key)) return 5;
        for (int i = 0; i < GATHER_TPL.length; i++) {
            if (GATHER_TPL[i].equals(key)) return 6 + i;
        }
        for (int i = 0; i < BEAR_RIDE_TPL.length; i++) {
            if (BEAR_RIDE_TPL[i].equals(key)) return 10 + i;
        }
        return 0;
    }

    // ==================== 19 套解析（与 App 逐条一致） ====================

    private static Map<Integer, List<Long>> resolveTeams(View v, WjdrEngine engine) {
        Map<Integer, List<Long>> out = new LinkedHashMap<Integer, List<Long>>();
        List<Long> behemoth = heroesForBehemoth(v);
        out.put(Integer.valueOf(1), behemoth);
        out.put(Integer.valueOf(2), new ArrayList<Long>(behemoth));

        List<Long> bearTeam = heroesForBearTeam(v);
        out.put(Integer.valueOf(3), bearTeam);
        out.put(Integer.valueOf(4), heroesForShieldBow(v));
        out.put(Integer.valueOf(5), heroesForShieldSpear(v));

        Set<Long> gatherUsed = new LinkedHashSet<Long>();
        for (int i = 0; i < GATHER_CAPTAINS.length; i++) {
            out.put(Integer.valueOf(6 + i), gatherTeam(v, engine, GATHER_CAPTAINS[i],
                    GATHER_CAPTAIN_IDS[i], gatherUsed));
        }

        List<BearRidePick> picks = bearRidePicks(v, bearTeam);
        Map<Integer, List<Long>> rideTeams = new LinkedHashMap<Integer, List<Long>>();
        Set<Long> bearRideUsed = new LinkedHashSet<Long>(bearTeam);
        // 先预留全部候选队长：禁止高优先级队补位抢走后续队队长（跨队英雄不得重复）
        for (BearRidePick pick : picks) bearRideUsed.add(Long.valueOf(pick.captainId));
        for (BearRidePick pick : picks) {
            List<Long> heroes = new ArrayList<Long>();
            heroes.add(pick.captainId);
            fillUp(v, heroes, bearRideUsed, FormationSpec.TEAM_SIZE - heroes.size());
            rideTeams.put(Integer.valueOf(pick.order), heroes);
        }
        for (int order = 0; order < BEAR_RIDE_CAPTAINS.length; order++) {
            List<Long> heroes = rideTeams.get(Integer.valueOf(order));
            out.put(Integer.valueOf(10 + order),
                    heroes == null ? new ArrayList<Long>() : heroes);
        }
        return out;
    }

    /** 巨兽/狩猎：最强盾 / 波根(缺→最强矛) / 吉娜(缺→最强弓)。 */
    private static List<Long> heroesForBehemoth(View v) {
        List<Long> out = new ArrayList<Long>();
        addHero(out, v.strongest(HeroCatalog.INFANTRY, null));
        long spear = v.resolve("波根");
        if (spear <= 0L) spear = v.strongest(HeroCatalog.LANCER, null);
        addHero(out, spear);
        long bow = v.resolve("吉娜");
        if (bow <= 0L) bow = v.strongest(HeroCatalog.MARKSMAN, null);
        addHero(out, bow);
        return out;
    }

    /** 打熊队：盾优先级；米亚矛（缺→最强矛，避开熊上车队长）；最强弓（避开熊上车队长）。 */
    private static List<Long> heroesForBearTeam(View v) {
        List<Long> out = new ArrayList<Long>();
        addHero(out, bearTeamShield(v));
        long spear = v.resolve("米亚");
        if (spear <= 0L) spear = v.strongest(HeroCatalog.LANCER, BEAR_RIDE_CAPTAIN_ID_SET);
        addHero(out, spear);
        addHero(out, v.strongest(HeroCatalog.MARKSMAN, BEAR_RIDE_CAPTAIN_ID_SET));
        return out;
    }

    private static long bearTeamShield(View v) {
        FormationCatalog.Hero nemo = v.resolveHero("尼莫");
        if (nemo != null && nemo.starDisplay >= 5 && nemo.weaponLevel >= MAX_WEAPON_LEVEL) {
            return nemo.id;
        }
        FormationCatalog.Hero hector = v.resolveHero("赫克托");
        if (hector != null && hector.starDisplay >= 4) return hector.id;
        if (nemo != null) return nemo.id;
        FormationCatalog.Hero natalia = v.resolveHero("娜塔莉亚");
        if (natalia != null) return natalia.id;
        return v.strongest(HeroCatalog.INFANTRY, null);
    }

    /** 盾弓：最强盾 / 米亚→茉莉→最强矛 / 最强弓。 */
    private static List<Long> heroesForShieldBow(View v) {
        List<Long> out = new ArrayList<Long>();
        addHero(out, v.strongest(HeroCatalog.INFANTRY, null));
        long spear = v.resolve("米亚");
        if (spear <= 0L) spear = v.resolve("茉莉");
        if (spear <= 0L) spear = v.strongest(HeroCatalog.LANCER, null);
        addHero(out, spear);
        addHero(out, v.strongest(HeroCatalog.MARKSMAN, null));
        return out;
    }

    /** 盾矛：最强盾 / 最强矛 / 布拉德利→格雷格→最强弓。 */
    private static List<Long> heroesForShieldSpear(View v) {
        List<Long> out = new ArrayList<Long>();
        addHero(out, v.strongest(HeroCatalog.INFANTRY, null));
        addHero(out, v.strongest(HeroCatalog.LANCER, null));
        long bow = v.resolve("布拉德利");
        if (bow <= 0L) bow = v.resolve("格雷格");
        if (bow <= 0L) bow = v.strongest(HeroCatalog.MARKSMAN, null);
        addHero(out, bow);
        return out;
    }

    /** 采集队：队长固定；单队长容量 >10 万只带队长，否则按战力降序补到 3 人（队列间不重复/同队职业不重复）。 */
    private static List<Long> gatherTeam(View v, WjdrEngine engine, String captainName,
                                         long fallbackCaptainId, Set<Long> usedAcrossQueues) {
        List<Long> out = new ArrayList<Long>();
        long captain = fallbackCaptainId;
        long byName = v.resolve(captainName);
        if (byName > 0L) captain = byName;
        if (captain <= 0L) return out;
        out.add(captain);
        usedAcrossQueues.add(captain);
        long capacity = capacityForCaptain(engine, captain);
        if (capacity > GATHER_CAPACITY_THRESHOLD) return out;
        Set<Integer> professions = new LinkedHashSet<Integer>();
        addProfession(professions, v.profession(captain));
        for (FormationCatalog.Hero h : v.byPower) {
            if (out.size() >= FormationSpec.TEAM_SIZE) break;
            if (usedAcrossQueues.contains(h.id)) continue;
            if (GATHER_CAPTAIN_ID_SET.contains(h.id)) continue;
            int p = h.profession;
            if (p >= 1 && p <= 3 && professions.contains(Integer.valueOf(p))) continue;
            out.add(h.id);
            usedAcrossQueues.add(h.id);
            addProfession(professions, p);
        }
        return out;
    }

    private static long capacityForCaptain(WjdrEngine engine, long captainId) {
        try {
            return FormationCatalog.capacityForHeroes(engine,
                    Collections.singletonList(Long.valueOf(captainId)));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 熊上车候选：队长已拥有且不与打熊队冲突；5 级队按表顺序在前，其余技能降序、同级表顺序。 */
    private static List<BearRidePick> bearRidePicks(View v, List<Long> bearTeamHeroes) {
        List<BearRidePick> out = new ArrayList<BearRidePick>();
        Set<Long> blocked = new LinkedHashSet<Long>(bearTeamHeroes);
        for (int order = 0; order < BEAR_RIDE_CAPTAINS.length; order++) {
            long captain = v.resolve(BEAR_RIDE_CAPTAINS[order]);
            if (captain <= 0L) continue;                  // 队长未拥有 → 跳过该队
            if (blocked.contains(Long.valueOf(captain))) continue; // 与打熊队重复 → 跳过该队
            FormationCatalog.Hero h = v.hero(captain);
            int skill = h == null ? 0 : clampInt(h.expeditionSkillLevel,
                    0, MAX_EXPEDITION_SKILL_LEVEL);
            out.add(new BearRidePick(order, captain, skill));
        }
        Collections.sort(out, new Comparator<BearRidePick>() {
            @Override
            public int compare(BearRidePick a, BearRidePick b) {
                boolean aFull = a.skill >= MAX_EXPEDITION_SKILL_LEVEL;
                boolean bFull = b.skill >= MAX_EXPEDITION_SKILL_LEVEL;
                if (aFull != bFull) return aFull ? -1 : 1;
                if (!aFull) {
                    int c = Integer.compare(b.skill, a.skill);
                    if (c != 0) return c;
                }
                return Integer.compare(a.order, b.order);
            }
        });
        return out;
    }

    /** 按战力降序补位（排除已占用；同编队职业不重复）。 */
    private static void fillUp(View v, List<Long> heroes, Set<Long> used, int count) {
        if (count <= 0) return;
        Set<Integer> professions = new LinkedHashSet<Integer>();
        for (Long id : heroes) addProfession(professions, v.profession(id.longValue()));
        while (heroes.size() < FormationSpec.TEAM_SIZE && count > 0) {
            long pick = 0L;
            for (FormationCatalog.Hero h : v.byPower) {
                if (used.contains(Long.valueOf(h.id))) continue;
                int p = h.profession;
                if (p >= 1 && p <= 3 && professions.contains(Integer.valueOf(p))) continue;
                pick = h.id;
                break;
            }
            if (pick <= 0L) break;
            heroes.add(Long.valueOf(pick));
            used.add(Long.valueOf(pick));
            addProfession(professions, v.profession(pick));
            count--;
        }
    }

    // ==================== 复制 / 工具 ====================

    /** 复制 spec（只改英雄字段；其余逐项保留）。 */
    private static FormationSpec copy(FormationSpec s) {
        FormationSpec out = new FormationSpec();
        out.id = s.id;
        out.name = s.name;
        out.heroIds = new ArrayList<Long>(s.heroIds == null
                ? Collections.<Long>emptyList() : s.heroIds);
        out.captainHeroId = s.captainHeroId;
        out.allocationMode = s.allocationMode;
        out.source = s.source;
        out.templateKey = s.templateKey;
        out.builtIn = s.builtIn;
        out.version = s.version;
        out.updatedAt = s.updatedAt;
        out.teamSize = s.teamSize;
        out.infantry = s.infantry;
        out.lancer = s.lancer;
        out.marksman = s.marksman;
        out.ratio = s.ratio == null ? FormationSpec.defaultRatio()
                : new LinkedHashMap<String, Long>(s.ratio);
        out.total = s.total;
        out.ratioExplicit = s.ratioExplicit;
        return out;
    }

    private static List<Long> distinct(List<Long> heroIds) {
        List<Long> out = new ArrayList<Long>();
        if (heroIds == null) return out;
        Set<Long> seen = new LinkedHashSet<Long>();
        for (Long id : heroIds) {
            if (id == null || id.longValue() <= 0L) continue;
            if (!seen.add(id)) continue;
            out.add(id);
            if (out.size() >= FormationSpec.TEAM_SIZE) break;
        }
        return out;
    }

    private static void addHero(List<Long> out, long heroId) {
        if (heroId <= 0L || out.size() >= FormationSpec.TEAM_SIZE) return;
        if (!out.contains(Long.valueOf(heroId))) out.add(Long.valueOf(heroId));
    }

    private static void addProfession(Set<Integer> professions, int profession) {
        if (profession >= 1 && profession <= 3) {
            professions.add(Integer.valueOf(profession));
        }
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 中文/别名名 → 规范名（与 App 一致）。 */
    static String canonicalHeroName(String name) {
        String v = HeroCatalog.cleanName(name);
        if (v.isEmpty()) return "";
        String alias = ALIASES.get(v);
        return alias == null ? v : alias;
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("波根", "卢姆·波根");
        m.put("米亚", "米娅");
        m.put("杰赛尔", "杰塞尔");
        m.put("尼莫", "赫罗尼莫");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Long> buildNameIds() {
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        for (Map.Entry<Long, String> e : HeroCatalog.names().entrySet()) {
            String name = HeroCatalog.cleanName(e.getValue());
            if (!name.isEmpty()) m.put(name, e.getKey());
        }
        return Collections.unmodifiableMap(m);
    }

    private static Set<Long> buildSet(long[] ids) {
        Set<Long> s = new LinkedHashSet<Long>();
        for (long id : ids) s.add(Long.valueOf(id));
        return Collections.unmodifiableSet(s);
    }

    private static long officialHeroId(String canonicalName) {
        Long id = NAME_IDS.get(canonicalName);
        return id == null ? 0L : id.longValue();
    }

    /** 一次解析的目录视图（按战力排序 + 名称/id 索引）。 */
    private static final class View {
        final List<FormationCatalog.Hero> heroes;
        final List<FormationCatalog.Hero> byPower;
        final Map<Long, FormationCatalog.Hero> index;

        View(List<FormationCatalog.Hero> heroes) {
            this.heroes = heroes;
            this.index = new LinkedHashMap<Long, FormationCatalog.Hero>();
            for (FormationCatalog.Hero h : heroes) index.put(Long.valueOf(h.id), h);
            this.byPower = new ArrayList<FormationCatalog.Hero>(heroes);
            Collections.sort(byPower, POWER_DESC);
        }

        FormationCatalog.Hero hero(long heroId) {
            return index.get(Long.valueOf(heroId));
        }

        FormationCatalog.Hero resolveHero(String name) {
            long id = resolve(name);
            return id > 0L ? hero(id) : null;
        }

        /** 先按目录名（别名归一后精确匹配），再用 HeroCatalog 精确名 + id 归属校验。 */
        long resolve(String name) {
            String canonical = canonicalHeroName(name);
            if (canonical.isEmpty()) return 0L;
            for (FormationCatalog.Hero h : heroes) {
                if (canonical.equals(canonicalHeroName(h.name))) return h.id;
            }
            long official = officialHeroId(canonical);
            return official > 0L && index.containsKey(Long.valueOf(official)) ? official : 0L;
        }

        int profession(long heroId) {
            FormationCatalog.Hero h = hero(heroId);
            return h == null ? 0 : h.profession;
        }

        long strongest(int profession, Set<Long> exclude) {
            for (FormationCatalog.Hero h : byPower) {
                if (exclude != null && exclude.contains(Long.valueOf(h.id))) continue;
                if (h.profession == profession) return h.id;
            }
            return 0L;
        }
    }

    /** 熊上车候选（order=上表顺序 0..9）。 */
    private static final class BearRidePick {
        final int order;
        final long captainId;
        final int skill;

        BearRidePick(int order, long captainId, int skill) {
            this.order = order;
            this.captainId = captainId;
            this.skill = skill;
        }
    }
}
