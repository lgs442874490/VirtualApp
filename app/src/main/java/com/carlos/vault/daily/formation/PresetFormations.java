package com.carlos.vault.daily.formation;

import com.carlos.vault.daily.FormationStore;
import com.carlos.vault.wjdr.asset.HeroCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 19 套「预设编队」定义与懒创建（唯一事实源
 * {@code design/编队管理-实施契约-20261102.md} §2/§3）。
 *
 * <p>id 固定 {@code 9100000001}..{@code 9100000019}，{@code source=CUSTOM}、
 * {@code builtIn=true}、{@code templateKey=PRESET_*}；除「巨兽」为 MANUAL 1/0/0 外，
 * 其余均为 PERCENTAGE，并按 {@link FormationRules#allocateByRatio(long, int, int, int)}
 * 以当前容量预算三兵种数量（仅展示用，出征时仍按比例实时换算）。
 *
 * <table border="1">
 *   <tr><th>id</th><th>名称</th><th>英雄规则</th><th>比例</th></tr>
 *   <tr><td>9100000001</td><td>巨兽</td><td>最强盾 / 波根(缺→最强矛) / 吉娜(缺→最强弓)</td><td>MANUAL 1/0/0</td></tr>
 *   <tr><td>9100000002</td><td>狩猎</td><td>同巨兽</td><td>50/20/30</td></tr>
 *   <tr><td>9100000003</td><td>打熊队</td><td>盾优先级 + 米亚矛 + 最强弓（避开熊上车队长）</td><td>2/3/95</td></tr>
 *   <tr><td>9100000004</td><td>盾弓</td><td>最强盾 / 米亚→茉莉→最强矛 / 最强弓</td><td>49/2/49</td></tr>
 *   <tr><td>9100000005</td><td>盾矛</td><td>最强盾 / 最强矛 / 布拉德利→格雷格→最强弓</td><td>60/39/1</td></tr>
 *   <tr><td>9100000006..9</td><td>采集肉/木/煤/铁</td><td>队长=克劳瑞斯/尤金/查理/史密斯 + 采集 10 万阈值</td><td>34/33/33</td></tr>
 *   <tr><td>9100000010..19</td><td>熊上车 1..10</td><td>亨德里克/格温/书允/布拉德利/马格努斯/布兰琪/杰西/杰赛尔/赫罗尼莫/索尼娅</td><td>10/20/70</td></tr>
 * </table>
 *
 * <p><b>降级</b>：目录未就绪（{@code configured=false}）或无英雄时，{@link #all} 返回 19 条
 * 英雄为空的骨架、{@link #ensure} 不创建（与旧内置懒创建同口径）。
 *
 * <p>纯逻辑（无 Android 依赖），JVM 单测可直跑。
 */
public final class PresetFormations {

    /** 第一套预设 id（巨兽）。 */
    public static final long ID_FIRST = 9100000001L;
    /** 最后一套预设 id（熊上车10）。 */
    public static final long ID_LAST = 9100000019L;
    /** 采集「只带队长」的容量阈值（{@code catalog.capacityFor([captain]) > 10 万}）。 */
    public static final long GATHER_CAPACITY_THRESHOLD = 100000L;
    /** 熊上车默认最多写入库的队数（其余仅备选）。 */
    public static final int BEAR_RIDE_MAX_TEAMS = 6;
    /** 队长第一个远征技能满级（优先排前）。 */
    public static final int MAX_EXPEDITION_SKILL_LEVEL = 5;
    /** 专武满级。 */
    public static final int MAX_WEAPON_LEVEL = 10;

    // ==================== 具名英雄 id（契约 §2；名称解析见 ALIASES） ====================

    public static final long HERO_BORGAN = 50029L;      // 卢姆·波根（矛）
    public static final long HERO_GINA = 50008L;        // 吉娜（弓）
    public static final long HERO_MIA = 50016L;         // 米娅（矛）
    public static final long HERO_JASMINE = 50012L;     // 茉莉（矛）
    public static final long HERO_BRADLEY = 50033L;     // 布拉德利（弓）
    public static final long HERO_GREG = 50020L;        // 格雷格（弓）
    public static final long HERO_HECTOR = 50024L;      // 赫克托（盾）
    public static final long HERO_NEMO = 50011L;        // 赫罗尼莫/尼莫（盾）
    public static final long HERO_NATALIA = 50009L;     // 娜塔莉亚（盾）
    public static final long HERO_GATHER_MEAT = 50003L; // 克劳瑞斯（采集肉队长）
    public static final long HERO_GATHER_WOOD = 50000L; // 尤金（采集木队长）
    public static final long HERO_GATHER_COAL = 50002L; // 查理（采集煤队长）
    public static final long HERO_GATHER_IRON = 50001L; // 史密斯（采集铁队长）

    // ==================== 模板键（PRESET_*） ====================

    public static final String TPL_BEHEMOTH = "PRESET_BEHEMOTH";
    public static final String TPL_HUNT = "PRESET_HUNT";
    public static final String TPL_BEAR_TEAM = "PRESET_BEAR_TEAM";
    public static final String TPL_SHIELD_BOW = "PRESET_SHIELD_BOW";
    public static final String TPL_SHIELD_SPEAR = "PRESET_SHIELD_SPEAR";

    public static final String NAME_BEHEMOTH = "巨兽";
    public static final String NAME_HUNT = "狩猎";
    public static final String NAME_BEAR_TEAM = "打熊队";
    public static final String NAME_SHIELD_BOW = "盾弓";
    public static final String NAME_SHIELD_SPEAR = "盾矛";

    // ==================== 编队模板分类（2026-11-06） ====================

    /** 巨熊编队模板·发起队 = 预设「打熊队」（id 9100000003）。 */
    public static final long ID_BEAR_TEAM = ID_FIRST + 2L;
    /** 采集队 id 起点：采集肉（采集木/煤/铁 = +1/+2/+3）。 */
    public static final long ID_GATHER_FIRST = ID_FIRST + 5L;
    /** 熊上车队 id 起点：熊上车1（熊上车1-6 = +0..+5）。 */
    public static final long ID_BEAR_RIDE_FIRST = ID_FIRST + 9L;
    /** 巨熊编队模板上车队数（熊上车1-6）。 */
    public static final int BEAR_TEMPLATE_JOIN_COUNT = 6;
    /** 采集队数量（采集肉/木/煤/铁）。 */
    public static final int GATHER_TEAM_COUNT = 4;

    // ==================== 采集（6..9） ====================

    private static final String[] GATHER_NAMES = {"采集肉", "采集木", "采集煤", "采集铁"};
    private static final String[] GATHER_CAPTAINS = {"克劳瑞斯", "尤金", "查理", "史密斯"};
    private static final long[] GATHER_CAPTAIN_IDS = {
            HERO_GATHER_MEAT, HERO_GATHER_WOOD, HERO_GATHER_COAL, HERO_GATHER_IRON};
    private static final String[] GATHER_TPL = {
            "PRESET_GATHER_MEAT", "PRESET_GATHER_WOOD",
            "PRESET_GATHER_COAL", "PRESET_GATHER_IRON"};
    /** 采集队长集合（补成员时必须排除：采集队列之间英雄不得重复）。 */
    private static final Set<Long> GATHER_CAPTAIN_ID_SET = buildGatherCaptainSet();

    // ==================== 熊上车（10..19） ====================

    private static final String[] BEAR_RIDE_CAPTAINS = {
            "亨德里克", "格温", "书允", "布拉德利", "马格努斯",
            "布兰琪", "杰西", "杰赛尔", "赫罗尼莫", "索尼娅"};
    private static final String[] BEAR_RIDE_NAMES = {
            "熊上车1", "熊上车2", "熊上车3", "熊上车4", "熊上车5",
            "熊上车6", "熊上车7", "熊上车8", "熊上车9", "熊上车10"};
    private static final String[] BEAR_RIDE_TPL = {
            "PRESET_BEAR_RIDE_1", "PRESET_BEAR_RIDE_2", "PRESET_BEAR_RIDE_3",
            "PRESET_BEAR_RIDE_4", "PRESET_BEAR_RIDE_5", "PRESET_BEAR_RIDE_6",
            "PRESET_BEAR_RIDE_7", "PRESET_BEAR_RIDE_8", "PRESET_BEAR_RIDE_9",
            "PRESET_BEAR_RIDE_10"};
    private static final long[] BEAR_RIDE_CAPTAIN_IDS = {
            50036L, 50026L, 50027L, 50033L, 50038L,
            50043L, 50007L, 50028L, 50011L, 50035L};
    /** 熊上车队长集合（打熊队选矛/弓时避开）。 */
    private static final Set<Long> BEAR_RIDE_CAPTAIN_ID_SET = buildBearRideCaptainSet();

    // ==================== 别名 / 名称反查 ====================

    /** 契约 §2 别名 → HeroCatalog 精确名。 */
    private static final Map<String, String> ALIASES = buildAliases();
    /** HeroCatalog 精确名 → id（供「按名字」解析兜底）。 */
    private static final Map<String, Long> NAME_IDS = buildNameIds();

    private static final Comparator<FormationStore.Hero> POWER_DESC =
            new Comparator<FormationStore.Hero>() {
                @Override
                public int compare(FormationStore.Hero a, FormationStore.Hero b) {
                    int c = Long.compare(b.power, a.power);
                    if (c != 0) return c;
                    c = Integer.compare(b.starDisplay, a.starDisplay);
                    if (c != 0) return c;
                    c = Long.compare(b.level, a.level);
                    return c != 0 ? c : Long.compare(a.id, b.id);
                }
            };


    private PresetFormations() {
    }

    // ==================== 公开 API（契约 §3） ====================

    /** 是否预设编队 id（9100000001..9100000019）。 */
    public static boolean isPreset(long id) {
        return id >= ID_FIRST && id <= ID_LAST;
    }

    // ---------- 巨熊 / 采集分类（「编队管理-我的编队」与一键应用共用） ----------

    /** 是否巨熊编队模板队：打熊队 + 熊上车1-6。 */
    public static boolean isBearTemplateTeam(long id) {
        return id == ID_BEAR_TEAM
                || (id >= ID_BEAR_RIDE_FIRST
                && id < ID_BEAR_RIDE_FIRST + BEAR_TEMPLATE_JOIN_COUNT);
    }

    /** 是否采集队：采集肉/木/煤/铁。 */
    public static boolean isGatherTeam(long id) {
        return id >= ID_GATHER_FIRST && id < ID_GATHER_FIRST + GATHER_TEAM_COUNT;
    }

    /** 巨熊编队模板 id 列表：index 0 = 打熊队（发起），1..6 = 熊上车1..6（上车）。 */
    public static List<Long> bearTemplateIds() {
        List<Long> out = new ArrayList<Long>();
        out.add(Long.valueOf(ID_BEAR_TEAM));
        for (int slot = 1; slot <= BEAR_TEMPLATE_JOIN_COUNT; slot++) {
            out.add(Long.valueOf(bearTemplateJoinId(slot)));
        }
        return out;
    }

    /** 巨熊编队模板第 slot 支上车队 id（slot 1..6；越界返回 0）。 */
    public static long bearTemplateJoinId(int slot) {
        if (slot < 1 || slot > BEAR_TEMPLATE_JOIN_COUNT) return 0L;
        return ID_BEAR_RIDE_FIRST + (slot - 1);
    }

    /** 采集队 id 列表：采集肉/木/煤/铁（顺序与 {@link #gatherTeamId(int)} 一致）。 */
    public static List<Long> gatherTeamIds() {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < GATHER_TEAM_COUNT; i++) {
            out.add(Long.valueOf(gatherTeamId(i)));
        }
        return out;
    }

    /** 第 index 支采集队 id（index 0..3；越界返回 0）。 */
    public static long gatherTeamId(int index) {
        if (index < 0 || index >= GATHER_TEAM_COUNT) return 0L;
        return ID_GATHER_FIRST + index;
    }

    /**
     * 19 套预设定义（目录未就绪/无英雄时英雄为空）。
     *
     * <p>熊上车 10 队都会返回（仅备选）；真正写入库的 6 队由 {@link #ensure} 判定。
     */
    public static List<FormationModel> all(FormationCatalogStore cat) {
        return resolve(cat).models;
    }

    /** 按 id 取预设定义（英雄为空骨架；非预设返回 null）。 */
    public static FormationModel byId(long id) {
        if (!isPreset(id)) return null;
        for (FormationModel f : all(FormationCatalogStore.empty())) {
            if (f.id == id) return f;
        }
        return null;
    }

    /**
     * 懒创建预设编队（就地修改 {@code lib}）。
     *
     * <p>幂等：已存在的 id 不重复创建、不覆盖用户改名/兵量；目录未就绪或无英雄时不创建。
     * 早期「目录未同步时建了骨架」的预设，若当前英雄为空则补英雄（仿
     * 预设懒创建分支）。
     *
     * @return 本次新增数量（不含补英雄的已存在项）
     */
    public static int ensure(List<FormationModel> lib, FormationCatalogStore cat) {
        if (lib == null || !ready(cat)) return 0;
        Resolution r = resolve(cat);
        int created = 0;
        Set<Long> keptBearRide = r.keptBearRideIds;
        // 先移除「未编辑过、但本轮已不在前 6 队」的熊上车备选（用户编辑过的保留）
        for (int i = lib.size() - 1; i >= 0; i--) {
            FormationModel old = lib.get(i);
            if (old == null || old.isGame()) continue;
            if (!isPreset(old.id) || !isBearRide(old.id)) continue;
            if (keptBearRide.contains(Long.valueOf(old.id))) continue;
            if (untouched(old)) lib.remove(i);
        }
        for (FormationModel def : r.models) {
            boolean kept = !isBearRide(def.id)
                    || keptBearRide.contains(Long.valueOf(def.id));
            FormationModel existing = FormationRules.findById(lib, def.id);
            if (!kept) continue;
            if (existing == null) {
                lib.add(def.copy());
                created++;
                continue;
            }
            if (existing.heroIds.isEmpty() && !def.heroIds.isEmpty()) {
                copyContent(def, existing);
            } else if (differs(def, existing)) {
                // 2026-11-03：预设是「实时最强」模板，始终随英雄目录/容量刷新；
                // 用户编辑预设时 App 会另存为新编队，不再修改预设本体，因此这里可以无条件覆盖内容。
                copyContent(def, existing);
            }
        }
        return created;
    }

    /** 是否用户未保存过（ensure 创建的预设 updatedAt=0/version=1；saveFormation 后会写 updatedAt）。 */
    private static boolean untouched(FormationModel f) {
        return f != null && f.updatedAt <= 0L && f.version <= 1L;
    }

    private static boolean differs(FormationModel def, FormationModel old) {
        if (!def.heroIds.equals(old.heroIds)) return true;
        if (def.captainHeroId != null ? !def.captainHeroId.equals(old.captainHeroId)
                : old.captainHeroId != null) return true;
        if (!def.allocationMode.equals(old.allocationMode)) return true;
        if (def.infantryRatio != old.infantryRatio
                || def.lancerRatio != old.lancerRatio
                || def.marksmanRatio != old.marksmanRatio) return true;
        if (def.infantry != old.infantry || def.lancer != old.lancer
                || def.marksman != old.marksman) return true;
        return false;
    }

    /** 刷新编队内容（保留 id/name/templateKey/version/updatedAt 与用户字段）。 */
    private static void copyContent(FormationModel def, FormationModel target) {
        target.heroIds.clear();
        target.heroIds.addAll(def.heroIds);
        target.captainHeroId = def.captainHeroId;
        target.allocationMode = def.allocationMode;
        target.infantry = def.infantry;
        target.lancer = def.lancer;
        target.marksman = def.marksman;
        target.infantryRatio = def.infantryRatio;
        target.lancerRatio = def.lancerRatio;
        target.marksmanRatio = def.marksmanRatio;
        target.totalSoldiers = def.totalSoldiers;
        target.allHeroesAvailable = def.allHeroesAvailable;
        target.unavailableHeroIds.clear();
        target.unavailableHeroIds.addAll(def.unavailableHeroIds);
    }

    // ==================== 别名 / 名称解析（公开便于 UI/单测） ====================

    /** 别名 → HeroCatalog 精确名（波根→卢姆·波根；米亚→米娅；杰赛尔→杰塞尔；尼莫→赫罗尼莫）。 */
    public static String canonicalHeroName(String name) {
        String v = FormationModel.cleanName(name);
        if (v.isEmpty()) return "";
        String alias = ALIASES.get(v);
        return alias == null ? v : alias;
    }

    /**
     * 按名称/别名在目录中解析英雄 id（先按名字，其次 HeroCatalog 精确名 + id 归属校验）。
     *
     * @return 已拥有的英雄 id；未拥有/未知返回 0
     */
    public static long resolveHeroId(FormationCatalogStore cat, String name) {
        if (cat == null || name == null) return 0;
        String canonical = canonicalHeroName(name);
        if (canonical.isEmpty()) return 0;
        for (FormationStore.Hero h : cat.heroOptions()) {
            if (canonical.equals(canonicalHeroName(h.name))) return h.id;
        }
        long official = officialHeroId(canonical);
        if (official > 0 && cat.hero(official) != null) return official;
        return 0;
    }

    // ==================== 解析实现 ====================

    private static Resolution resolve(FormationCatalogStore cat) {
        Resolution r = new Resolution();
        boolean ready = ready(cat);
        View v = ready ? new View(cat) : null;

        // 1 巨兽（MANUAL 1/0/0）
        r.models.add(buildManual(1, NAME_BEHEMOTH, TPL_BEHEMOTH,
                heroesForBehemoth(v), 1L, 0L, 0L, cat));
        // 2 狩猎（同巨兽英雄）
        r.models.add(build(2, NAME_HUNT, TPL_HUNT, 50, 20, 30,
                heroesForBehemoth(v), cat));

        // 3 打熊队（打熊队英雄 = 熊上车冲突基准）
        List<Long> bearTeamHeroes = heroesForBearTeam(v);
        r.models.add(build(3, NAME_BEAR_TEAM, TPL_BEAR_TEAM, 2, 3, 95,
                bearTeamHeroes, cat));

        // 4 盾弓 / 5 盾矛
        r.models.add(build(4, NAME_SHIELD_BOW, TPL_SHIELD_BOW, 49, 2, 49,
                heroesForShieldBow(v), cat));
        r.models.add(build(5, NAME_SHIELD_SPEAR, TPL_SHIELD_SPEAR, 60, 39, 1,
                heroesForShieldSpear(v), cat));

        // 6..9 采集（队列之间英雄不重复）
        Set<Long> gatherUsed = new LinkedHashSet<Long>();
        for (int i = 0; i < GATHER_NAMES.length; i++) {
            List<Long> heroes = gatherTeam(v, GATHER_CAPTAINS[i],
                    GATHER_CAPTAIN_IDS[i], gatherUsed);
            r.models.add(build(6 + i, GATHER_NAMES[i], GATHER_TPL[i], 34, 33, 33,
                    heroes, cat));
        }

        // 10..19 熊上车：技能排序 → 前 6 队写入库；其余仅备选
        List<BearRidePick> picks = bearRidePicks(v, bearTeamHeroes);
        Map<Integer, List<Long>> teamsByOrder = new LinkedHashMap<Integer, List<Long>>();
        Set<Long> bearRideUsed = new LinkedHashSet<Long>(bearTeamHeroes);
        // 先预留全部候选队长：禁止高优先级队的补位抢走后续队队长（跨队英雄不得重复）
        for (BearRidePick pick : picks) bearRideUsed.add(Long.valueOf(pick.captainId));
        for (BearRidePick pick : picks) {
            List<Long> heroes = new ArrayList<Long>();
            heroes.add(pick.captainId);
            fillUp(v, heroes, bearRideUsed, FormationModel.HERO_MAX - heroes.size());
            teamsByOrder.put(pick.order, heroes);
        }
        for (int i = 0; i < picks.size() && i < BEAR_RIDE_MAX_TEAMS; i++) {
            r.keptBearRideIds.add(bearRideIndexToId(picks.get(i).order));
        }
        for (int order = 0; order < BEAR_RIDE_NAMES.length; order++) {
            List<Long> heroes = teamsByOrder.get(order);
            r.models.add(build(10 + order, BEAR_RIDE_NAMES[order],
                    BEAR_RIDE_TPL[order], 10, 20, 70,
                    heroes == null ? new ArrayList<Long>() : heroes, cat));
        }
        return r;
    }

    /** 巨兽/狩猎：最强盾 / 波根(缺→最强矛) / 吉娜(缺→最强弓)。 */
    private static List<Long> heroesForBehemoth(View v) {
        List<Long> out = new ArrayList<Long>();
        if (v == null) return out;
        addHero(out, v.strongest(HeroCatalog.INFANTRY, null));
        long spear = v.resolve("波根");
        if (spear <= 0) spear = v.strongest(HeroCatalog.LANCER, null);
        addHero(out, spear);
        long bow = v.resolve("吉娜");
        if (bow <= 0) bow = v.strongest(HeroCatalog.MARKSMAN, null);
        addHero(out, bow);
        return out;
    }

    /**
     * 打熊队：盾优先级 ①满星满专武尼莫 → ②4 星以上赫克托 → ③尼莫或娜塔莉亚 → ④最强盾；
     * 矛=米亚(缺→最强矛)；弓=<b>最强弓（战力+装备综合，游戏 power 口径）</b>。
     *
     * <p><b>2026-11-08 修正（用户口径）</b>：打熊队优先级<b>高于</b>熊上车队——选弓/矛
     * <b>不避让熊上车队长</b>，最强英雄直接给发车队；上车队侧再用
     * {@code bearRideUsed = 发车队英雄} 避让（见 {@link #resolve}），不会重复占用。
     * 旧实现反向避让：账号里最强弓恰是上车队长时会被跳过，退而选到最弱一批的弓
     * （生产 21:30 场：打熊队弓=50006 巴希提 gen0）。
     */
    private static List<Long> heroesForBearTeam(View v) {
        List<Long> out = new ArrayList<Long>();
        if (v == null) return out;
        addHero(out, bearTeamShield(v));
        long spear = v.resolve("米亚");
        if (spear <= 0) spear = v.strongest(HeroCatalog.LANCER, null);
        addHero(out, spear);
        addHero(out, v.strongest(HeroCatalog.MARKSMAN, null));
        return out;
    }

    private static long bearTeamShield(View v) {
        FormationStore.Hero nemo = v.resolveHero("尼莫");
        if (nemo != null && nemo.starDisplay >= 5 && nemo.weaponLevel >= MAX_WEAPON_LEVEL) {
            return nemo.id;
        }
        FormationStore.Hero hector = v.resolveHero("赫克托");
        if (hector != null && hector.starDisplay >= 4) return hector.id;
        if (nemo != null) return nemo.id;
        FormationStore.Hero natalia = v.resolveHero("娜塔莉亚");
        if (natalia != null) return natalia.id;
        return v.strongest(HeroCatalog.INFANTRY, null);
    }

    /** 盾弓：最强盾 / 米亚→茉莉→最强矛 / 最强弓。 */
    private static List<Long> heroesForShieldBow(View v) {
        List<Long> out = new ArrayList<Long>();
        if (v == null) return out;
        addHero(out, v.strongest(HeroCatalog.INFANTRY, null));
        long spear = v.resolve("米亚");
        if (spear <= 0) spear = v.resolve("茉莉");
        if (spear <= 0) spear = v.strongest(HeroCatalog.LANCER, null);
        addHero(out, spear);
        addHero(out, v.strongest(HeroCatalog.MARKSMAN, null));
        return out;
    }

    /** 盾矛：最强盾 / 最强矛 / 布拉德利→格雷格→最强弓。 */
    private static List<Long> heroesForShieldSpear(View v) {
        List<Long> out = new ArrayList<Long>();
        if (v == null) return out;
        addHero(out, v.strongest(HeroCatalog.INFANTRY, null));
        addHero(out, v.strongest(HeroCatalog.LANCER, null));
        long bow = v.resolve("布拉德利");
        if (bow <= 0) bow = v.resolve("格雷格");
        if (bow <= 0) bow = v.strongest(HeroCatalog.MARKSMAN, null);
        addHero(out, bow);
        return out;
    }

    /**
     * 采集队：队长固定；若 {@code capacityFor([captain]) > 10 万} 只带队长，
     * 否则从「未分配给任何采集队列」的英雄里按战力降序补 2 名（排除其他采集队长，
     * 且同编队不重复职业，满足游戏每队列盾/矛/弓各一）。
     */
    private static List<Long> gatherTeam(View v, String captainName, long fallbackCaptainId,
                                         Set<Long> usedAcrossQueues) {
        List<Long> out = new ArrayList<Long>();
        if (v == null) return out;   // 目录未就绪 → 空骨架（不写任何英雄 id）
        long captain = fallbackCaptainId;
        long byName = v.resolve(captainName);
        if (byName > 0) captain = byName;
        if (captain <= 0) return out;
        out.add(captain);
        usedAcrossQueues.add(captain);
        if (v.cat.capacityFor(Collections.singletonList(captain)) > GATHER_CAPACITY_THRESHOLD) {
            return out;
        }
        Set<Integer> professions = new LinkedHashSet<Integer>();
        addProfession(professions, v.profession(captain));
        for (FormationStore.Hero h : v.byPower) {
            if (out.size() >= FormationModel.HERO_MAX) break;
            if (usedAcrossQueues.contains(h.id)) continue;
            if (GATHER_CAPTAIN_ID_SET.contains(h.id)) continue;
            int p = FormationRules.professionOf(h);
            if (p >= 1 && p <= 3 && professions.contains(p)) continue;
            out.add(h.id);
            usedAcrossQueues.add(h.id);
            addProfession(professions, p);
        }
        return out;
    }

    /**
     * 熊上车候选：队长必须已拥有且不与打熊队冲突；排序 = 5 级队按上表顺序在前，
     * 其余按技能等级降序、同级按上表顺序。
     */
    private static List<BearRidePick> bearRidePicks(View v, List<Long> bearTeamHeroes) {
        List<BearRidePick> out = new ArrayList<BearRidePick>();
        if (v == null) return out;
        Set<Long> blocked = new LinkedHashSet<Long>(bearTeamHeroes);
        for (int order = 0; order < BEAR_RIDE_CAPTAINS.length; order++) {
            long captain = v.resolve(BEAR_RIDE_CAPTAINS[order]);
            if (captain <= 0) continue;          // 队长未拥有 → 跳过该队
            if (blocked.contains(captain)) continue; // 与打熊队重复 → 跳过该队
            FormationStore.Hero h = v.hero(captain);
            int skill = h == null ? 0 : clampInt(h.skillLevel,
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

    /** 用战力降序补英雄（排除已占用；同编队职业不重复，最多补到 3 人）。 */
    private static void fillUp(View v, List<Long> heroes, Set<Long> used, int count) {
        if (v == null || count <= 0) return;
        Set<Integer> professions = new LinkedHashSet<Integer>();
        for (Long id : heroes) addProfession(professions, v.profession(id));
        while (heroes.size() < FormationModel.HERO_MAX && count > 0) {
            long pick = 0L;
            for (FormationStore.Hero h : v.byPower) {
                if (used.contains(h.id)) continue;
                int p = FormationRules.professionOf(h);
                if (p >= 1 && p <= 3 && professions.contains(p)) continue;
                pick = h.id;
                break;
            }
            if (pick <= 0) break;
            heroes.add(pick);
            used.add(pick);
            addProfession(professions, v.profession(pick));
            count--;
        }
    }

    // ==================== 构造 / 工具 ====================

    private static FormationModel build(int index, String name, String templateKey,
                                        int infRatio, int lancRatio, int markRatio,
                                        List<Long> heroes, FormationCatalogStore cat) {
        FormationModel f = base(index, name, templateKey, heroes, cat);
        f.allocationMode = FormationModel.MODE_PERCENTAGE;
        f.infantryRatio = clampRatio(infRatio);
        f.lancerRatio = clampRatio(lancRatio);
        f.marksmanRatio = clampRatio(markRatio);
        long capacity = (cat == null || f.heroIds.isEmpty()) ? 0L : cat.capacityFor(f.heroIds);
        long[] soldiers = FormationRules.allocateByRatio(capacity,
                f.infantryRatio, f.lancerRatio, f.marksmanRatio);
        f.infantry = soldiers[0];
        f.lancer = soldiers[1];
        f.marksman = soldiers[2];
        f.totalSoldiers = f.soldiersTotal();
        return f;
    }

    private static FormationModel buildManual(int index, String name, String templateKey,
                                              List<Long> heroes, long infantry, long lancer,
                                              long marksman, FormationCatalogStore cat) {
        FormationModel f = base(index, name, templateKey, heroes, cat);
        f.allocationMode = FormationModel.MODE_MANUAL;
        f.infantry = Math.max(0L, infantry);
        f.lancer = Math.max(0L, lancer);
        f.marksman = Math.max(0L, marksman);
        f.totalSoldiers = f.soldiersTotal();
        return f;
    }

    private static FormationModel base(int index, String name, String templateKey,
                                       List<Long> heroes, FormationCatalogStore cat) {
        FormationModel f = new FormationModel();
        f.id = ID_FIRST + (index - 1);
        f.name = name;
        f.source = FormationModel.SOURCE_CUSTOM;
        f.templateKey = templateKey;
        f.builtIn = true;
        if (heroes != null) {
            for (Long h : heroes) {
                if (h != null && h.longValue() > 0
                        && !f.heroIds.contains(h)
                        && f.heroIds.size() < FormationModel.HERO_MAX) {
                    f.heroIds.add(h);
                }
            }
        }
        if (!f.heroIds.isEmpty()) f.captainHeroId = f.heroIds.get(0);
        f.version = 1;
        FormationRules.markAvailability(f, cat);
        return f;
    }

    /** 目录是否就绪（容量已同步 + 至少一个英雄；与内置懒创建同口径）。 */
    private static boolean ready(FormationCatalogStore cat) {
        return cat != null && cat.configured() && cat.hasHeroes();
    }

    private static boolean isBearRide(long id) {
        return id >= bearRideIndexToId(0) && id <= bearRideIndexToId(BEAR_RIDE_NAMES.length - 1);
    }

    private static long bearRideIndexToId(int order) {
        return ID_FIRST + 9L + order; // order 0 → 9100000010（熊上车1）
    }

    private static void addHero(List<Long> out, long heroId) {
        if (heroId <= 0L || out.size() >= FormationModel.HERO_MAX) return;
        if (!out.contains(heroId)) out.add(heroId);
    }

    private static void addProfession(Set<Integer> professions, int profession) {
        if (profession >= 1 && profession <= 3) {
            professions.add(Integer.valueOf(profession));
        }
    }

    private static int clampRatio(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
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
            String name = FormationModel.cleanName(e.getValue());
            if (!name.isEmpty()) m.put(name, e.getKey());
        }
        return Collections.unmodifiableMap(m);
    }

    private static Set<Long> buildGatherCaptainSet() {
        Set<Long> s = new LinkedHashSet<Long>();
        for (long id : GATHER_CAPTAIN_IDS) s.add(Long.valueOf(id));
        return Collections.unmodifiableSet(s);
    }

    private static Set<Long> buildBearRideCaptainSet() {
        Set<Long> s = new LinkedHashSet<Long>();
        for (long id : BEAR_RIDE_CAPTAIN_IDS) s.add(Long.valueOf(id));
        return Collections.unmodifiableSet(s);
    }

    private static long officialHeroId(String canonicalName) {
        Long id = NAME_IDS.get(canonicalName);
        return id == null ? 0L : id.longValue();
    }

    /** 一次解析的目录视图（英雄列表只解析一次；排序=战力降序）。 */
    private static final class View {
        final FormationCatalogStore cat;
        final List<FormationStore.Hero> heroes;
        final List<FormationStore.Hero> byPower;
        final Map<Long, FormationStore.Hero> index;

        View(FormationCatalogStore cat) {
            this.cat = cat;
            List<FormationStore.Hero> list = cat == null
                    ? new ArrayList<FormationStore.Hero>() : cat.heroOptions();
            this.heroes = list;
            this.index = new LinkedHashMap<Long, FormationStore.Hero>();
            for (FormationStore.Hero h : list) index.put(Long.valueOf(h.id), h);
            this.byPower = new ArrayList<FormationStore.Hero>(list);
            Collections.sort(byPower, POWER_DESC);
        }

        FormationStore.Hero hero(long id) {
            return index.get(Long.valueOf(id));
        }

        FormationStore.Hero resolveHero(String name) {
            long id = resolve(name);
            return id > 0 ? hero(id) : null;
        }

        /** 按名字/别名解析（先目录名匹配，再用 HeroCatalog 精确名 + 归属校验）。 */
        long resolve(String name) {
            String canonical = canonicalHeroName(name);
            if (canonical.isEmpty()) return 0L;
            for (FormationStore.Hero h : heroes) {
                if (canonical.equals(canonicalHeroName(h.name))) return h.id;
            }
            long official = officialHeroId(canonical);
            return official > 0L && index.containsKey(Long.valueOf(official)) ? official : 0L;
        }

        int profession(long heroId) {
            FormationStore.Hero h = hero(heroId);
            return h == null ? 0 : FormationRules.professionOf(h);
        }

        long strongest(int profession, Set<Long> exclude) {
            // 最强 = 战力（游戏 power 已含装备/星级/专武/等级的综合加成）。
            for (FormationStore.Hero h : byPower) {
                if (exclude != null && exclude.contains(Long.valueOf(h.id))) continue;
                if (FormationRules.professionOf(h) == profession) return h.id;
            }
            return 0L;
        }
    }

    /** 一次解析结果（19 条定义 + 应写入库的熊上车 id 集合）。 */
    private static final class Resolution {
        final List<FormationModel> models = new ArrayList<FormationModel>();
        final Set<Long> keptBearRideIds = new LinkedHashSet<Long>();
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
