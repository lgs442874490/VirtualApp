package com.carlos.vault.daily.formation;

import com.carlos.vault.daily.FormationStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 8 个内置编队定义与懒创建（契约 §2.6，与网页版同 id/名/模板键）。
 *
 * <table border="1">
 *   <tr><th>id</th><th>名称</th><th>templateKey</th><th>英雄</th><th>兵量</th></tr>
 *   <tr><td>9000000001</td><td>基础编队·一盾兵（无英雄）</td><td>ONE_INFANTRY</td><td>[]</td><td>1/0/0</td></tr>
 *   <tr><td>9000000002</td><td>基础编队·任意英雄一兵</td><td>RANDOM_HERO_ONE_SOLDIER</td><td>[]</td><td>1/0/0</td></tr>
 *   <tr><td>57</td><td>基础编队·巨兽</td><td>BEHEMOTH</td><td>懒填</td><td>1/0/0</td></tr>
 *   <tr><td>55/54/56/53/58</td><td>煤矿/铁矿/生肉/木材/打野采集</td><td>GATHER_* / WILD</td><td>懒填</td><td>1/0/0</td></tr>
 * </table>
 *
 * <p>规则（网页 §4.1 R3/R4）：2 个基础内置可重复引用、不可编辑/删除；其余 6 个在
 * {@code 容量已同步 + 有英雄目录} 时懒创建；全部 {@code source=CUSTOM, builtIn=true}。
 *
 * <p>纯逻辑（目录用 {@link FormationCatalogStore} 传入），JVM 单测可直跑；Context 持久化在
 * {@link FormationStore#ensureBuiltIns}。
 */
public final class FormationBuiltIns {

    public static final long ID_ONE_INFANTRY = 9000000001L;
    public static final long ID_ANY_HERO_ONE_SOLDIER = 9000000002L;
    public static final long ID_BEHEMOTH = 57L;
    public static final long ID_COAL = 55L;
    public static final long ID_IRON = 54L;
    public static final long ID_MEAT = 56L;
    public static final long ID_WOOD = 53L;
    public static final long ID_WILD = 58L;

    public static final String TPL_ONE_INFANTRY = "ONE_INFANTRY";
    public static final String TPL_ANY_ONE_SOLDIER = "RANDOM_HERO_ONE_SOLDIER";
    public static final String TPL_BEHEMOTH = "BEHEMOTH";
    public static final String TPL_COAL = "GATHER_COAL";
    public static final String TPL_IRON = "GATHER_IRON";
    public static final String TPL_MEAT = "GATHER_MEAT";
    public static final String TPL_WOOD = "GATHER_WOOD";
    public static final String TPL_WILD = "WILD";

    /** 6 个懒创建内置的固定顺序（网页分页域口径）。 */
    private static final long[] LAZY_IDS = {ID_BEHEMOTH, ID_COAL, ID_IRON, ID_MEAT, ID_WOOD, ID_WILD};
    private static final String[] LAZY_KEYS = {TPL_BEHEMOTH, TPL_COAL, TPL_IRON, TPL_MEAT,
            TPL_WOOD, TPL_WILD};
    private static final String[] LAZY_NAMES = {"基础编队·巨兽", "基础编队·煤矿采集",
            "基础编队·铁矿采集", "基础编队·生肉采集", "基础编队·木材采集", "基础编队·打野"};

    private FormationBuiltIns() {
    }

    /** 8 个内置定义（懒创建的 6 个英雄需目录；此处英雄为空，由 {@link #lazy} 填充）。 */
    public static List<FormationModel> all() {
        List<FormationModel> out = base();
        out.addAll(lazy(null));
        return out;
    }

    /** 2 个基础内置（始终存在，可重复引用、不可编辑/删除）。 */
    public static List<FormationModel> base() {
        List<FormationModel> out = new ArrayList<FormationModel>();
        out.add(build(ID_ONE_INFANTRY, "基础编队·一盾兵（无英雄）", TPL_ONE_INFANTRY));
        out.add(build(ID_ANY_HERO_ONE_SOLDIER, "基础编队·任意英雄一兵", TPL_ANY_ONE_SOLDIER));
        return out;
    }

    /**
     * 6 个懒创建内置；目录未同步或无英雄时英雄为空（表示"尚未就绪"）。
     * 英雄=目录各职业（盾/矛/弓）最高战力（不要求在城），不足则按战力补足（≤3）。
     */
    public static List<FormationModel> lazy(FormationCatalogStore cat) {
        List<FormationModel> out = new ArrayList<FormationModel>();
        List<Long> heroes = cat == null ? new ArrayList<Long>() : cat.bestProfessionHeroIds();
        for (int i = 0; i < LAZY_IDS.length; i++) {
            FormationModel f = build(LAZY_IDS[i], LAZY_NAMES[i], LAZY_KEYS[i]);
            f.heroIds.addAll(heroes);
            if (!heroes.isEmpty()) f.captainHeroId = heroes.get(0);
            if (cat != null) FormationRules.markAvailability(f, cat);
            out.add(f);
        }
        return out;
    }

    /** 是否受保护内置（不可编辑/删除：9000000001 / 9000000002）。 */
    public static boolean isProtected(long id) {
        return id == ID_ONE_INFANTRY || id == ID_ANY_HERO_ONE_SOLDIER;
    }

    /** 是否内置（8 个之一）。 */
    public static boolean isBuiltIn(long id) {
        if (isProtected(id)) return true;
        for (long id2 : LAZY_IDS) {
            if (id2 == id) return true;
        }
        return false;
    }

    /** 按 templateKey 找内置定义（未填充英雄）；未知返回 null。 */
    public static FormationModel byTemplateKey(String templateKey) {
        if (templateKey == null) return null;
        String key = templateKey.trim().toUpperCase(java.util.Locale.ROOT);
        for (FormationModel f : base()) {
            if (key.equals(f.templateKey)) return f;
        }
        for (int i = 0; i < LAZY_KEYS.length; i++) {
            if (key.equals(LAZY_KEYS[i])) {
                return build(LAZY_IDS[i], LAZY_NAMES[i], LAZY_KEYS[i]);
            }
        }
        return null;
    }

    /** 按 id 找内置定义；未知返回 null。 */
    public static FormationModel byId(long id) {
        for (FormationModel f : all()) {
            if (f.id == id) return f;
        }
        return null;
    }

    /** 采集资源类型 → 对应内置编队 id（MEAT/WOOD/COAL/IRON）；未知返回 0。 */
    public static long idForGatherResource(String resourceType) {
        if (resourceType == null) return 0;
        String v = resourceType.trim().toUpperCase(java.util.Locale.ROOT);
        if ("MEAT".equals(v)) return ID_MEAT;
        if ("WOOD".equals(v)) return ID_WOOD;
        if ("COAL".equals(v)) return ID_COAL;
        if ("IRON".equals(v)) return ID_IRON;
        return 0;
    }

    /**
     * 懒创建：把缺失的内置编队补进 {@code lib}（就地修改）。
     *
     * <p>幂等：已存在的 id 不重复创建、不覆盖用户数据；2 个基础内置总是补齐，
     * 6 个懒创建仅在 {@code cat.configured() && 目录有英雄} 时补齐（网页 R4）。
     *
     * @return 本次新增数量
     */
    public static int ensure(List<FormationModel> lib, FormationCatalogStore cat) {
        if (lib == null) return 0;
        int created = 0;
        for (FormationModel f : base()) {
            if (FormationRules.findById(lib, f.id) == null) {
                // 基础内置固定内容：即便已存在也不覆盖（用户可重复引用，但库内只存一份）
                lib.add(f);
                created++;
            }
        }
        boolean ready = cat != null && cat.configured() && cat.hasHeroes();
        if (!ready) return created;
        for (FormationModel f : lazy(cat)) {
            if (FormationRules.findById(lib, f.id) == null) {
                lib.add(f);
                created++;
            } else {
                // 已存在但英雄为空（早期未同步时创建）→ 补英雄，不动名称/兵量之外的字段
                FormationModel existing = FormationRules.findById(lib, f.id);
                if (existing.heroIds.isEmpty() && !f.heroIds.isEmpty()) {
                    existing.heroIds.addAll(f.heroIds);
                    existing.captainHeroId = f.captainHeroId;
                    existing.allHeroesAvailable = f.allHeroesAvailable;
                    existing.unavailableHeroIds.clear();
                    existing.unavailableHeroIds.addAll(f.unavailableHeroIds);
                }
            }
        }
        // 契约 2026-11-02 §3：目录就绪时懒创建 19 套预设编队（内部再判幂等/只建前 6 支熊上车）
        created += PresetFormations.ensure(lib, cat);
        return created;
    }

    // ==================== 内部 ====================

    private static FormationModel build(long id, String name, String templateKey) {
        FormationModel f = new FormationModel();
        f.id = id;
        f.name = name;
        f.source = FormationModel.SOURCE_CUSTOM;
        f.templateKey = templateKey;
        f.builtIn = true;
        f.allocationMode = FormationModel.MODE_MANUAL;
        f.infantry = 1;
        f.lancer = 0;
        f.marksman = 0;
        f.version = 1;
        return f;
    }
}
