package com.carlos.vault.daily.formation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 编队实体 v2（1:1 网页版 {@code FormationView}，契约 §2.1）。
 *
 * <p>字段口径（契约 §2.1 / 网页规格 §2.1）：
 * <ul>
 *   <li>{@link #id}：{@code >0} 自定义/内置；{@code <0} 游戏编队（{@code =-sourceId}，只读镜像）；{@code 0}=新建未保存；</li>
 *   <li>{@link #source}：{@code CUSTOM}|{@code GAME}，必须与 id 符号一致（不一致的脏数据整条丢弃）；</li>
 *   <li>{@link #templateKey}：内置模板键（ONE_INFANTRY/GATHER_WOOD/...），非空即视为内置；</li>
 *   <li>{@link #heroIds}：上阵顺序（≤3，盾/矛/弓各一）；{@link #captainHeroId} 必须 ∈ heroIds；</li>
 *   <li>{@link #allocationMode}：{@code MANUAL}（手填兵力）|{@code PERCENTAGE}（比例换算）。</li>
 * </ul>
 *
 * <p><b>JSON 兼容</b>：{@link #fromJson(JSONObject)} 同时吃契约 §5 蛇形键（{@code template_key}/
 * {@code hero_ids}/{@code allocation_mode}/{@code updated_at}）、网页 collection 驼峰键
 * （{@code templateKey}/{@code heroIds}/{@code allocationMode}/{@code infantryCount}）与旧版 App
 * 库键（{@code soldiers}/{@code ratio}/{@code total}），脏数据不抛异常。
 *
 * <p>本类纯逻辑（无 Android 依赖），JVM 单测可直跑。
 */
public final class FormationModel {

    public static final String SOURCE_CUSTOM = "CUSTOM";
    public static final String SOURCE_GAME = "GAME";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_PERCENTAGE = "PERCENTAGE";

    /** 编队英雄上限（游戏规则每队列最多 3 名，盾/矛/弓各一）。 */
    public static final int HERO_MAX = 3;
    /** 编队名称上限（网页版输入框 maxlength=30）。 */
    public static final int NAME_MAX = 30;

    /** 编队编号：>0 自定义/内置；<0 游戏编队（=-slotId）；0=新建未保存。 */
    public long id;
    /** 显示名（≤30 字符）。 */
    public String name = "";
    /** 来源：CUSTOM | GAME。 */
    public String source = SOURCE_CUSTOM;
    /** 内置模板键；null=普通编队。 */
    public String templateKey;
    /** 是否内置（服务端下发或 templateKey!=null）。 */
    public boolean builtIn;
    /** 上阵英雄 id（顺序=上阵顺序，≤3）。 */
    public final List<Long> heroIds = new ArrayList<Long>();
    /** 队长英雄 id（必须 ∈ heroIds；null=用 heroIds[0]）。 */
    public Long captainHeroId;
    /** 兵量分配方式：MANUAL | PERCENTAGE。 */
    public String allocationMode = MODE_MANUAL;
    /** 固定数量模式：盾/矛/弓兵力。 */
    public long infantry;
    public long lancer;
    public long marksman;
    /** 比例模式：盾/矛/弓百分比（0..100，和 1..100）。 */
    public int infantryRatio;
    public int lancerRatio;
    public int marksmanRatio;
    /** 三兵种合计（派生；序列化时按三兵种重算，不信任外部脏值）。 */
    public long totalSoldiers;
    /** 编制英雄是否全部在家。 */
    public boolean allHeroesAvailable = true;
    /** 不在家英雄 id。 */
    public final List<Long> unavailableHeroIds = new ArrayList<Long>();
    /** 乐观锁/快照版本。 */
    public long version = 1;
    /** 更新时间（毫秒）。 */
    public long updatedAt;

    public FormationModel() {
    }

    public FormationModel(String name, List<Long> heroes) {
        this.name = name == null ? "" : name;
        if (heroes != null) {
            for (Long h : heroes) {
                if (h != null && h > 0) this.heroIds.add(h);
            }
        }
    }

    /** 深拷贝（列表/队长/可用性均复制）。 */
    public FormationModel copy() {
        FormationModel m = new FormationModel();
        m.id = id;
        m.name = name;
        m.source = source;
        m.templateKey = templateKey;
        m.builtIn = builtIn;
        m.heroIds.addAll(heroIds);
        m.captainHeroId = captainHeroId;
        m.allocationMode = allocationMode;
        m.infantry = infantry;
        m.lancer = lancer;
        m.marksman = marksman;
        m.infantryRatio = infantryRatio;
        m.lancerRatio = lancerRatio;
        m.marksmanRatio = marksmanRatio;
        m.totalSoldiers = totalSoldiers;
        m.allHeroesAvailable = allHeroesAvailable;
        m.unavailableHeroIds.addAll(unavailableHeroIds);
        m.version = version;
        m.updatedAt = updatedAt;
        return m;
    }

    /** 三兵种合计（实时相加，忽略 {@link #totalSoldiers} 脏值）。 */
    public long soldiersTotal() {
        return Math.max(0, infantry) + Math.max(0, lancer) + Math.max(0, marksman);
    }

    /** 是否游戏编队（只读镜像）。 */
    public boolean isGame() {
        return id < 0 || SOURCE_GAME.equals(normalizeSource(source));
    }

    /** 是否巨熊模板生成的编队（客户端只按名字前缀 `巨熊·` 识别，网页规格 §2.4）。 */
    public boolean isBear() {
        return name != null && name.startsWith(BearFormationTemplates.NAME_PREFIX);
    }

    /** 是否使用比例分配。 */
    public boolean isPercentage() {
        return MODE_PERCENTAGE.equals(normalizeAllocationMode(allocationMode));
    }

    /** 英雄是否已上阵。 */
    public boolean hasHero(long heroId) {
        for (Long h : heroIds) {
            if (h != null && h == heroId) return true;
        }
        return false;
    }

    /**
     * 有效队长（契约 §2.1 字面签名，返回英雄 id 字符串）。
     *
     * <p>仅用于契约兼容展示；<b>T4/T5 需要 id 时请用 {@link #effectiveCaptainId()}</b>。
     */
    public String effectiveCaptain() {
        Long id = effectiveCaptainId();
        return id == null ? null : String.valueOf(id.longValue());
    }

    /** 有效队长：captain 合法（∈ heroIds）返回之，否则 heroIds[0]，无英雄返回 null。 */
    public Long effectiveCaptainId() {
        if (captainHeroId != null && captainHeroId.longValue() > 0 && hasHero(captainHeroId)) {
            return captainHeroId;
        }
        for (Long h : heroIds) {
            if (h != null && h > 0) return h;
        }
        return null;
    }

    /** 比例合计（负数归 0）。 */
    public int ratioSum() {
        return Math.max(0, infantryRatio) + Math.max(0, lancerRatio) + Math.max(0, marksmanRatio);
    }

    /** 去重后的上阵英雄（保持顺序，丢弃非法 id）。 */
    public List<Long> distinctHeroIds() {
        Set<Long> uniq = new LinkedHashSet<Long>();
        for (Long h : heroIds) {
            if (h != null && h > 0) uniq.add(h);
        }
        return new ArrayList<Long>(uniq);
    }

    // ==================== 序列化 ====================

    /**
     * 编队 → JSON。
     *
     * @param compact true=仅旧字段（{@code id/name/hero_ids/soldiers/ratio/total}，服务端旧版兼容）；
     *                false=契约 §5 全量 v2 字段（App 库/上传默认）。
     */
    public JSONObject toJson(boolean compact) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name == null ? "" : name);
            JSONArray heroes = new JSONArray();
            for (Long h : heroIds) {
                if (h != null && h > 0) heroes.put(h.longValue());
            }
            o.put("hero_ids", heroes);
            JSONObject soldiers = new JSONObject();
            soldiers.put("infantry", Math.max(0, infantry));
            soldiers.put("lancer", Math.max(0, lancer));
            soldiers.put("marksman", Math.max(0, marksman));
            o.put("soldiers", soldiers);
            JSONObject ratio = new JSONObject();
            ratio.put("infantry", clampRatio(infantryRatio));
            ratio.put("lancer", clampRatio(lancerRatio));
            ratio.put("marksman", clampRatio(marksmanRatio));
            o.put("ratio", ratio);
            o.put("total", soldiersTotal());
            if (compact) return o;

            o.put("source", isGame() ? SOURCE_GAME : SOURCE_CUSTOM);
            o.put("template_key", templateKey == null ? JSONObject.NULL : templateKey);
            o.put("built_in", builtIn || (templateKey != null && !templateKey.trim().isEmpty()));
            if (effectiveCaptainId() != null) {
                o.put("captain_hero_id", effectiveCaptainId().longValue());
            }
            o.put("allocation_mode", normalizeAllocationMode(allocationMode));
            o.put("all_heroes_available", allHeroesAvailable);
            JSONArray unavailable = new JSONArray();
            for (Long h : unavailableHeroIds) {
                if (h != null && h > 0) unavailable.put(h.longValue());
            }
            o.put("unavailable_hero_ids", unavailable);
            o.put("version", Math.max(0, version));
            o.put("updated_at", updatedAt);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * JSON → 编队（宽容解析，不抛异常；脏数据返回 null）。
     *
     * <p>id=0 视为"新建未保存"仍可解析（{@link #parseLibrary(String)} 才过滤掉）；
     * {@code source} 与 id 符号不一致（网页 R3）→ null。
     */
    public static FormationModel fromJson(JSONObject o) {
        if (o == null) return null;
        FormationModel m = new FormationModel();
        m.id = o.optLong("id", 0);
        String explicitSource = normalizeSource(o.optString("source", ""));
        if (!explicitSource.isEmpty() && m.id != 0) {
            boolean game = SOURCE_GAME.equals(explicitSource);
            if (game != (m.id < 0)) return null;   // source 与 id 符号不一致 → 整条丢弃
        }
        m.source = !explicitSource.isEmpty() ? explicitSource
                : (m.id < 0 ? SOURCE_GAME : SOURCE_CUSTOM);

        m.name = cleanName(o.optString("name", ""));
        if (m.name.isEmpty()) m.name = "编队 " + m.id;

        String key = o.optString("template_key", o.optString("templateKey", ""));
        m.templateKey = key == null || key.trim().isEmpty() ? null : key.trim();
        m.builtIn = o.optBoolean("built_in", o.optBoolean("builtIn",
                m.templateKey != null));

        // 英雄（数组/逗号串/驼峰别名；≤3 且去重）
        JSONArray heroes = o.optJSONArray("hero_ids");
        if (heroes == null) heroes = o.optJSONArray("heroIds");
        if (heroes != null) {
            Set<Long> uniq = new LinkedHashSet<Long>();
            for (int i = 0; i < heroes.length(); i++) {
                long h = heroes.optLong(i, 0);
                if (h > 0) uniq.add(h);
            }
            int n = 0;
            for (Long h : uniq) {
                if (n++ >= HERO_MAX) break;
                m.heroIds.add(h);
            }
        } else {
            for (String part : o.optString("hero_ids", "").split(",")) {
                if (m.heroIds.size() >= HERO_MAX) break;
                try {
                    long h = Long.parseLong(part.trim());
                    if (h > 0 && !m.heroIds.contains(h)) m.heroIds.add(h);
                } catch (Throwable ignored) {
                }
            }
        }

        long captain = o.optLong("captain_hero_id", o.optLong("captainHeroId", 0));
        m.captainHeroId = captain > 0 ? Long.valueOf(captain) : null;

        m.allocationMode = normalizeAllocationMode(
                o.optString("allocation_mode", o.optString("allocationMode", "")));

        // 兵量：soldiers 对象（含网页/旧版别名）或顶层 *_count
        JSONObject soldiers = o.optJSONObject("soldiers");
        if (soldiers != null) {
            m.infantry = pickLong(soldiers, "infantry", "shield", "inf", "infantryCount");
            m.lancer = pickLong(soldiers, "lancer", "spear", "lanc", "lancerCount");
            m.marksman = pickLong(soldiers, "marksman", "bow", "mark", "marksmanCount");
        } else {
            m.infantry = pickLong(o, "infantry", "infantryCount", "infantry_count");
            m.lancer = pickLong(o, "lancer", "lancerCount", "lancer_count");
            m.marksman = pickLong(o, "marksman", "marksmanCount", "marksman_count");
        }
        m.infantry = Math.max(0, m.infantry);
        m.lancer = Math.max(0, m.lancer);
        m.marksman = Math.max(0, m.marksman);

        // 比例
        JSONObject ratio = o.optJSONObject("ratio");
        if (ratio != null) {
            m.infantryRatio = clampRatio(ratio.optInt("infantry",
                    ratio.optInt("shield", 0)));
            m.lancerRatio = clampRatio(ratio.optInt("lancer", ratio.optInt("spear", 0)));
            m.marksmanRatio = clampRatio(ratio.optInt("marksman", ratio.optInt("bow", 0)));
        } else {
            m.infantryRatio = clampRatio(o.optInt("infantryRatio",
                    o.optInt("infantry_ratio", 0)));
            m.lancerRatio = clampRatio(o.optInt("lancerRatio", o.optInt("lancer_ratio", 0)));
            m.marksmanRatio = clampRatio(o.optInt("marksmanRatio",
                    o.optInt("marksman_ratio", 0)));
        }

        long total = pickLong(o, "total", "totalSoldiers", "total_soldiers");
        m.totalSoldiers = total > 0 ? total : m.soldiersTotal();

        // allocation_mode 缺省推导（契约 §3.3 同口径）：有显式兵力→MANUAL；否则比例和>0→PERCENTAGE
        String rawMode = o.optString("allocation_mode", o.optString("allocationMode", ""));
        if (rawMode == null || rawMode.trim().isEmpty()) {
            m.allocationMode = m.soldiersTotal() > 0
                    ? MODE_MANUAL
                    : (m.ratioSum() > 0 ? MODE_PERCENTAGE : MODE_MANUAL);
        } else {
            m.allocationMode = normalizeAllocationMode(rawMode);
        }

        m.allHeroesAvailable = o.optBoolean("all_heroes_available",
                o.optBoolean("allHeroesAvailable", true));
        JSONArray unavailable = o.optJSONArray("unavailable_hero_ids");
        if (unavailable == null) unavailable = o.optJSONArray("unavailableHeroIds");
        if (unavailable != null) {
            for (int i = 0; i < unavailable.length(); i++) {
                long h = unavailable.optLong(i, 0);
                if (h > 0) m.unavailableHeroIds.add(h);
            }
        }

        m.version = Math.max(0, o.optLong("version", 1));
        m.updatedAt = pickTime(o, "updated_at", "updatedAt", "createdAt", "created_at");
        return m;
    }

    /** 数组 → 编队列表（坏条目跳过；id=0 丢弃）。 */
    public static List<FormationModel> parseArray(JSONArray arr) {
        List<FormationModel> out = new ArrayList<FormationModel>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            FormationModel m = fromJson(o);
            if (m != null && m.id != 0) out.add(m);
        }
        return out;
    }

    /** 库 JSON 字符串（{@code {"version":2,"formations":[...]}}）→ 编队列表；兼容裸数组与旧库。 */
    public static List<FormationModel> parseLibrary(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<FormationModel>();
        try {
            String text = raw.trim();
            if (text.startsWith("[")) return parseArray(new JSONArray(text));
            JSONObject root = new JSONObject(text);
            return parseArray(root.optJSONArray("formations"));
        } catch (Throwable t) {
            return new ArrayList<FormationModel>();
        }
    }

    // ==================== 归一化工具（各端同口径，供 FormationRules/各 Store 复用） ====================

    /** 来源归一：GAME/CUSTOM；未知/空返回空串（调用方决定默认）。 */
    public static String normalizeSource(String raw) {
        if (raw == null) return "";
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_GAME.equals(v)) return SOURCE_GAME;
        if (SOURCE_CUSTOM.equals(v)) return SOURCE_CUSTOM;
        return "";
    }

    /** 分配方式归一：MANUAL/PERCENTAGE；非法值按 MANUAL（网页 R2：非法值按 MANUAL）。 */
    public static String normalizeAllocationMode(String raw) {
        if (raw == null) return MODE_MANUAL;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return MODE_PERCENTAGE.equals(v) ? MODE_PERCENTAGE : MODE_MANUAL;
    }

    /** 编队名称清洗：去首尾空白、去包裹引号（服务端脏数据 `"\"巴希提\""` 同源问题口径）。 */
    public static String cleanName(String raw) {
        String s = raw == null ? "" : raw.trim();
        while (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 名称截断到 {@link #NAME_MAX}（保存入口兜底，网页 maxlength=30 同口径）。 */
    public static String truncateName(String name) {
        String s = name == null ? "" : name.trim();
        return s.length() <= NAME_MAX ? s : s.substring(0, NAME_MAX);
    }

    private static int clampRatio(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    private static long pickLong(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k)) {
                Object v = o.opt(k);
                if (v instanceof Number) return ((Number) v).longValue();
                if (v instanceof String) {
                    try {
                        return Long.parseLong(((String) v).trim());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return 0;
    }

    private static long pickTime(JSONObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k)) continue;
            Object v = o.opt(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (s.isEmpty()) continue;
                try {
                    return Long.parseLong(s);
                } catch (Throwable ignored) {
                }
                long iso = parseIso(s);
                if (iso > 0) return iso;
            }
        }
        return 0;
    }

    /** ISO8601（{@code 2026-08-06T15:39:27.156Z}）→ 毫秒；解析失败返回 0。 */
    static long parseIso(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(s).getTime();
            } catch (ParseException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }
}
