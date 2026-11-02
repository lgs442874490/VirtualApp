package com.carlos.vault.daily;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 情报出征设置（设计 §4.2 App 设置 / §4.3 参数键）纯逻辑层。
 *
 * <p>服务端 {@code IntelFormationPlan}（vault-server）按以下 params 键解析情报出征编队：
 * <ul>
 *   <li>{@code intel_hero_shield}/{@code intel_hero_lancer}/{@code intel_hero_marksman}：
 *       英雄 id（long；<b>0=自动</b>：盾=最强可用盾兵，矛=波根→最强矛，弓=吉娜→最强弓）；</li>
 *   <li>{@code intel_ratio}：兵种比例 {@code {infantry,lancer,marksman}}（相对权重，默认 50/20/30）；</li>
 *   <li>{@code intel_total}：总兵力（long；0=按出征容量/库存自动）。</li>
 * </ul>
 *
 * <p><b>本类不引用任何 Android 类型</b>（JVM 单测直测）：
 * {@link #read(JSONObject)}/{@link #write(JSONObject)}/{@link #clear(JSONObject)}/
 * {@link #validateRatio(int, int, int)}/{@link #isRoleBound(JSONObject)} 全为纯函数，
 * 只有 {@link IntelFormationCard} 负责落到 {@link FnParams#saveParams}。
 *
 * <p><b>写入口径</b>：{@link #write(JSONObject)} 只落"非默认值"——英雄 0（自动）与
 * 50:20:30（默认比例）、总兵力 0 的键都会从 params 中移除，因此「写默认值」与
 * {@link #clear(JSONObject)} 等价，直接回落到服务端默认自动编队（设计 §6 回滚口径）。
 */
public final class IntelFormationConfig {

    /** 盾兵英雄 id（0=最强可用盾兵）。 */
    public static final String KEY_HERO_SHIELD = "intel_hero_shield";
    /** 矛兵英雄 id（0=波根 50029→最强可用矛兵）。 */
    public static final String KEY_HERO_LANCER = "intel_hero_lancer";
    /** 弓兵英雄 id（0=吉娜 50008→最强可用弓兵）。 */
    public static final String KEY_HERO_MARKSMAN = "intel_hero_marksman";
    /** 兵种比例对象 {infantry,lancer,marksman}。 */
    public static final String KEY_RATIO = "intel_ratio";
    /** 总兵力（0=按容量/库存自动）。 */
    public static final String KEY_TOTAL = "intel_total";

    /** 英雄"自动（最强）"哨兵值。 */
    public static final long HERO_AUTO = 0L;

    public static final int DEFAULT_INFANTRY = 50;
    public static final int DEFAULT_LANCER = 20;
    public static final int DEFAULT_MARKSMAN = 30;

    public static final int RATIO_MIN = 0;
    public static final int RATIO_MAX = 100;

    /** 编队模式（与 {@link FormationStore#MODE_ROLE}/{@link FormationStore#MODE_AUTOMATIC} 同值）。 */
    public static final String MODE_ROLE = "ROLE";
    public static final String MODE_AUTOMATIC = "AUTOMATIC";

    /** 盾兵英雄 id；0=自动。 */
    public long heroShield = HERO_AUTO;
    /** 矛兵英雄 id；0=自动。 */
    public long heroLancer = HERO_AUTO;
    /** 弓兵英雄 id；0=自动。 */
    public long heroMarksman = HERO_AUTO;
    /** 盾兵比例（0..100）。 */
    public int ratioInfantry = DEFAULT_INFANTRY;
    /** 矛兵比例（0..100）。 */
    public int ratioLancer = DEFAULT_LANCER;
    /** 弓兵比例（0..100）。 */
    public int ratioMarksman = DEFAULT_MARKSMAN;
    /** 总兵力；0=按容量/库存自动。 */
    public long total = 0L;

    public IntelFormationConfig() {
    }

    /**
     * 解析 params → 配置（缺失/非法一律回落默认，绝不抛异常）。
     *
     * <p>比例对象存在但三项合计为 0（脏数据/手写错误）时同样回落 50/20/30：
     * 合计为 0 的配置无意义（服务端也会回退默认），UI 不应展示一个必然保存失败的组合。
     */
    public static IntelFormationConfig read(JSONObject params) {
        IntelFormationConfig c = new IntelFormationConfig();
        if (params == null) return c;
        try {
            c.heroShield = clampHero(params.optLong(KEY_HERO_SHIELD, HERO_AUTO));
            c.heroLancer = clampHero(params.optLong(KEY_HERO_LANCER, HERO_AUTO));
            c.heroMarksman = clampHero(params.optLong(KEY_HERO_MARKSMAN, HERO_AUTO));
            JSONObject ratio = params.optJSONObject(KEY_RATIO);
            if (ratio != null) {
                int r0 = clampRatio(ratio.optInt("infantry", DEFAULT_INFANTRY));
                int r1 = clampRatio(ratio.optInt("lancer", DEFAULT_LANCER));
                int r2 = clampRatio(ratio.optInt("marksman", DEFAULT_MARKSMAN));
                if (r0 + r1 + r2 > 0) {
                    c.ratioInfantry = r0;
                    c.ratioLancer = r1;
                    c.ratioMarksman = r2;
                }
            }
            c.total = Math.max(0L, params.optLong(KEY_TOTAL, 0L));
        } catch (Throwable ignored) {
            // 坏数据 → 默认值（不静默改写 params，用户点「保存」才落库）
        }
        return c;
    }

    /**
     * 写入 params（就地修改并返回）：非默认值落键，默认值移除键（见类注释"写入口径"）。
     *
     * @return 同一个 params 实例（null → 新建）
     */
    public JSONObject write(JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            putHero(p, KEY_HERO_SHIELD, heroShield);
            putHero(p, KEY_HERO_LANCER, heroLancer);
            putHero(p, KEY_HERO_MARKSMAN, heroMarksman);
            int r0 = clampRatio(ratioInfantry);
            int r1 = clampRatio(ratioLancer);
            int r2 = clampRatio(ratioMarksman);
            if (r0 == DEFAULT_INFANTRY && r1 == DEFAULT_LANCER && r2 == DEFAULT_MARKSMAN) {
                p.remove(KEY_RATIO);
            } else {
                JSONObject ratio = new JSONObject();
                ratio.put("infantry", r0);
                ratio.put("lancer", r1);
                ratio.put("marksman", r2);
                p.put(KEY_RATIO, ratio);
            }
            if (total > 0L) {
                p.put(KEY_TOTAL, total);
            } else {
                p.remove(KEY_TOTAL);
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    /** 清空情报出征配置（就地修改并返回）：移除全部 5 个键 → 服务端回落默认自动编队。 */
    public static JSONObject clear(JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            p.remove(KEY_HERO_SHIELD);
            p.remove(KEY_HERO_LANCER);
            p.remove(KEY_HERO_MARKSMAN);
            p.remove(KEY_RATIO);
            p.remove(KEY_TOTAL);
        } catch (Throwable ignored) {
        }
        return p;
    }

    /**
     * 比例校验（服务端同口径）：每项 0..100 且合计 &gt; 0。
     *
     * @return {@code null}=合法；否则中文错误文案
     */
    public static String validateRatio(int r0, int r1, int r2) {
        String err = checkOne(r0, "盾兵");
        if (err != null) return err;
        err = checkOne(r1, "矛兵");
        if (err != null) return err;
        err = checkOne(r2, "弓兵");
        if (err != null) return err;
        if (r0 + r1 + r2 <= 0) return "兵种比例合计必须大于 0";
        return null;
    }

    /**
     * 当前 params 是否为「指定编队（ROLE）且已绑定编队」——此时情报走玩家显式选择，
     * 本页的 {@code intel_hero_*}/{@code intel_ratio} 不生效（设计 §4.1 解析优先级 1）。
     */
    public static boolean isRoleBound(JSONObject params) {
        if (params == null) return false;
        try {
            if (!MODE_ROLE.equals(normalizeMode(params.optString("formation_mode", "")))) {
                return false;
            }
            if (params.optLong("formation_id", 0L) != 0L) return true;
            JSONArray ids = params.optJSONArray("formation_ids");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    if (ids.optLong(i, 0L) != 0L) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 编队模式归一（与 {@link FormationStore#normalizeMode} 同口径；缺省/非法 → AUTOMATIC）。 */
    public static String normalizeMode(String raw) {
        if (raw == null) return MODE_AUTOMATIC;
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return MODE_AUTOMATIC;
        if ("NORMAL".equals(v) || "CUSTOM".equals(v)) return MODE_ROLE;
        if (MODE_ROLE.equals(v)) return MODE_ROLE;
        return MODE_AUTOMATIC;
    }

    /** 比例文案 {@code 50:20:30}。 */
    public String ratioText() {
        return quoteRatio(ratioInfantry, ratioLancer, ratioMarksman);
    }

    /** 纯函数：比例文案。 */
    public static String quoteRatio(int r0, int r1, int r2) {
        return String.format(java.util.Locale.ROOT, "%d:%d:%d", r0, r1, r2);
    }

    /** 是否全部为默认值（英雄全自动 + 默认比例 + 总兵力自动）。 */
    public boolean isDefault() {
        return heroShield <= 0L && heroLancer <= 0L && heroMarksman <= 0L
                && total <= 0L
                && clampRatio(ratioInfantry) == DEFAULT_INFANTRY
                && clampRatio(ratioLancer) == DEFAULT_LANCER
                && clampRatio(ratioMarksman) == DEFAULT_MARKSMAN;
    }

    /** 纯函数：解析输入框文本 → 比例值（空/非数字 → def；越界按 0/100 夹取由 UI 提示校验）。 */
    public static int parseRatio(String text, int def) {
        if (text == null) return def;
        String v = text.trim();
        if (v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 比例夹取 0..100。 */
    public static int clampRatio(int v) {
        return v < RATIO_MIN ? RATIO_MIN : (v > RATIO_MAX ? RATIO_MAX : v);
    }

    // ==================== 内部 ====================

    private static String checkOne(int v, String label) {
        if (v < RATIO_MIN) return label + "比例不能小于 0";
        if (v > RATIO_MAX) return label + "比例不能大于 100";
        return null;
    }

    private static long clampHero(long id) {
        return id > 0L ? id : HERO_AUTO;
    }

    private static void putHero(JSONObject p, String key, long heroId) throws Exception {
        if (heroId > 0L) {
            p.put(key, heroId);
        } else {
            p.remove(key);
        }
    }
}
