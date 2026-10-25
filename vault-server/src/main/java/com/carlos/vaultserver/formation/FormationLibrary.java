package com.carlos.vaultserver.formation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色编队库（批次10）：解析 App 功能页保存的 {@code formation_library} JSON，供
 * {@link FormationResolver} 按 id 取编队。
 *
 * <p>参考网页版 {@code modules/daily/config/DailyFormationConfigSupport}（formationIds 去重）
 * + {@code persistence/entity/DailyRoleFormationEntity} + {@code config/DailyAutomaticFormationSettings}。
 * <b>解析是防御式的</b>：非法/缺失条目跳过而不是抛异常；整个 JSON 非法 → 返回空库
 * （调用方按"库缺失"SKIP，不静默回退旧编队）。
 *
 * <p>JSON 形状（契约 §5；旧 App 只写旧字段，全部缺省推导）：
 * <pre>
 * {"version":2,
 *  "automatic_settings":{"infantry_ratio":50,"lancer_ratio":20,"marksman_ratio":30},
 *  "formations":[
 *    {"id":1,"name":"采集队","hero_ids":[50041,50039],"captain_hero_id":50041,
 *     "allocation_mode":"MANUAL","source":"CUSTOM","template_key":null,"built_in":false,
 *     "soldiers":{"infantry":20000,"lancer":1549,"marksman":28451},
 *     "ratio":{"infantry":40,"lancer":30,"marksman":30},"total":50000,
 *     "version":3,"updated_at":1792863932863}]}
 * </pre>
 *
 * <p><b>v2（契约 §3.3）</b>：
 * <ul>
 *   <li>{@code formations} 允许 {@code id<0}（GAME 源只读镜像，id=-slotId）；{@code id==0}/缺失仍丢弃；</li>
 *   <li>每条缺省：captain=heroIds[0]、allocationMode=有显式兵量?MANUAL:PERCENTAGE、
 *       source=id&lt;0?GAME:CUSTOM、version=1、builtIn=templateKey 非空（网页版 D1）；</li>
 *   <li>{@link #automaticSettings}（自动编队全局比例 盾/矛/弓，默认 50/20/30）。</li>
 * </ul>
 * 旧 App（只写 {@code hero_ids/soldiers/ratio/total}）→ 上述缺省推导，行为与既有保持一致。
 */
public final class FormationLibrary {

    /** 自动编队全局比例默认值（网页版 {@code DailyAutomaticFormationSettings} 50:20:30）。 */
    public static final int[] DEFAULT_AUTOMATIC_SETTINGS = new int[]{50, 20, 30};

    /** 编队列表（按 id 去重，保持 App 保存顺序；可能为空；可含 id&lt;0 的 GAME 只读镜像）。 */
    public List<FormationSpec> formations = new ArrayList<>();

    /** 自动编队全局比例 [盾,矛,弓]；未配置=50/20/30。 */
    public int[] automaticSettings = DEFAULT_AUTOMATIC_SETTINGS.clone();

    /** 库 JSON 是否显式带了 {@code automatic_settings}（决定 Resolver 是否据此兜底 AUTO 比例）。 */
    public boolean automaticSettingsPresent;

    /** 空库（调用方判 {@link #isEmpty()} 后 SKIP）。 */
    public static FormationLibrary empty() {
        return new FormationLibrary();
    }

    /** 解析 {@code formation_library} JSON 字符串；null/空/非法 → 空库。 */
    public static FormationLibrary parse(String json) {
        FormationLibrary lib = new FormationLibrary();
        if (json == null || json.trim().isEmpty()) return lib;
        try {
            lib = parse(new JSONObject(json.trim()));
        } catch (Throwable ignored) {
            return empty();
        }
        return lib;
    }

    /** 解析已解析好的 JSON 对象（App sync 通道直接给 JSONObject 时用）；null/非法 → 空库。 */
    public static FormationLibrary parse(JSONObject root) {
        FormationLibrary lib = new FormationLibrary();
        if (root == null) return lib;
        try {
            lib.formations = parseFormations(root);
            lib.automaticSettings = parseAutomaticSettings(root);
            lib.automaticSettingsPresent = hasAutomaticSettings(root);
        } catch (Throwable ignored) {
            return empty();
        }
        return lib;
    }

    private static List<FormationSpec> parseFormations(JSONObject root) {
        JSONArray arr = root.optJSONArray("formations");
        if (arr == null) arr = root.optJSONArray("list");
        if (arr == null) return new ArrayList<>();
        Map<Long, FormationSpec> seen = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            FormationSpec spec = parseSpec(o);
            if (spec == null || spec.id == 0L || seen.containsKey(spec.id)) continue;
            seen.put(spec.id, spec);
        }
        return new ArrayList<>(seen.values());
    }

    /** 自动编队全局比例：{@code automatic_settings}/{@code automaticSettings}，兼容三键别名。 */
    private static int[] parseAutomaticSettings(JSONObject root) {
        JSONObject s = root.optJSONObject("automatic_settings");
        if (s == null) s = root.optJSONObject("automaticSettings");
        if (s == null) return DEFAULT_AUTOMATIC_SETTINGS.clone();
        int[] out = new int[]{
                (int) ratioValue(s, "infantry_ratio", "infantryRatio", "infantry", "shield"),
                (int) ratioValue(s, "lancer_ratio", "lancerRatio", "lancer", "spear"),
                (int) ratioValue(s, "marksman_ratio", "marksmanRatio", "marksman", "bow")};
        long sum = (long) out[0] + out[1] + out[2];
        boolean valid = out[0] >= 0 && out[1] >= 0 && out[2] >= 0 && out[0] <= 100
                && out[1] <= 100 && out[2] <= 100 && sum > 0L;
        // 契约/网页版：自动比例和必须=100；非法则回落默认（不静默放大成别的比例）。
        if (!valid || sum != 100L) return DEFAULT_AUTOMATIC_SETTINGS.clone();
        return out;
    }

    private static boolean hasAutomaticSettings(JSONObject root) {
        return root.has("automatic_settings") || root.has("automaticSettings");
    }

    private static long ratioValue(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) return Math.max(0L, o.optLong(k, 0L));
        }
        return 0L;
    }

    private static FormationSpec parseSpec(JSONObject o) {
        try {
            FormationSpec s = new FormationSpec();
            s.id = o.optLong("id", 0L);
            if (s.id == 0L) return null;                 // 0=未保存/非法；<0=GAME 只读镜像（允许）
            s.name = o.optString("name", "");
            s.heroIds = parseHeroIds(o);
            s.infantry = 0L;
            s.lancer = 0L;
            s.marksman = 0L;
            JSONObject soldiers = o.optJSONObject("soldiers");
            if (soldiers != null) {
                s.infantry = Math.max(0L, soldiers.optLong("infantry", 0L));
                s.lancer = Math.max(0L, soldiers.optLong("lancer", 0L));
                s.marksman = Math.max(0L, soldiers.optLong("marksman", 0L));
                // 兼容旧 App 别名：shield/spear/bow
                if (s.infantry <= 0) s.infantry = Math.max(0L, soldiers.optLong("shield", 0L));
                if (s.lancer <= 0) s.lancer = Math.max(0L, soldiers.optLong("spear", 0L));
                if (s.marksman <= 0) s.marksman = Math.max(0L, soldiers.optLong("bow", 0L));
            }
            JSONObject ratio = o.optJSONObject("ratio");
            if (ratio != null) {
                Map<String, Long> r = new LinkedHashMap<>();
                copyRatio(ratio, r, "infantry", "shield");
                copyRatio(ratio, r, "lancer", "spear");
                copyRatio(ratio, r, "marksman", "bow");
                if (!r.isEmpty()) {
                    s.ratio = r;
                    s.ratioExplicit = true;
                }
            }
            s.total = Math.max(0L, o.optLong("total", 0L));

            // ---- v2 字段（全部可选；缺省按契约 §3.3 推导） ----
            s.captainHeroId = longOrNull(o, "captain_hero_id", "captainHeroId");
            if (s.captainHeroId == null || s.captainHeroId <= 0L
                    || !s.heroIds.contains(s.captainHeroId)) {
                s.captainHeroId = s.heroIds.isEmpty() ? null : s.heroIds.get(0);
            }
            s.source = normalizeSource(str(o, "source"), s.id);
            s.templateKey = nullIfEmpty(str(o, "template_key", "templateKey"));
            s.builtIn = optBoolean(o, "built_in", "builtIn", s.templateKey != null);
            s.allocationMode = normalizeAllocationMode(str(o, "allocation_mode", "allocationMode"));
            long version = o.optLong("version", 1L);
            s.version = version > 0L ? version : 1L;
            s.updatedAt = Math.max(0L, o.optLong("updated_at", o.optLong("updatedAt", 0L)));
            s.teamSize = FormationSpec.TEAM_SIZE;
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 英雄 id 列表：数组优先，兼容逗号/空白分隔字符串；只保留 >0（顺序保持，**不去重**）。 */
    private static List<Long> parseHeroIds(JSONObject o) {
        List<Long> out = new ArrayList<>();
        JSONArray heroes = o.optJSONArray("hero_ids");
        if (heroes == null) heroes = o.optJSONArray("heroIds");
        if (heroes != null) {
            for (int i = 0; i < heroes.length(); i++) {
                long id = heroes.optLong(i, 0L);
                if (id > 0L) out.add(id);
            }
            return out;
        }
        String raw = str(o, "hero_ids", "heroIds");
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0L) out.add(id);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String normalizeSource(String raw, long id) {
        String s = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (FormationSpec.SOURCE_GAME.equals(s)) return FormationSpec.SOURCE_GAME;
        if (FormationSpec.SOURCE_CUSTOM.equals(s)) return FormationSpec.SOURCE_CUSTOM;
        return id < 0L ? FormationSpec.SOURCE_GAME : FormationSpec.SOURCE_CUSTOM;
    }

    private static String normalizeAllocationMode(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (FormationSpec.MODE_MANUAL.equals(s)) return FormationSpec.MODE_MANUAL;
        if (FormationSpec.MODE_PERCENTAGE.equals(s)) return FormationSpec.MODE_PERCENTAGE;
        return null;   // 未显式 → FormationSpec#effectiveAllocationMode 推断
    }

    private static boolean optBoolean(JSONObject o, String snake, String camel, boolean def) {
        for (String k : new String[]{snake, camel}) {
            if (o.has(k) && !o.isNull(k)) return o.optBoolean(k, def);
        }
        return def;
    }

    private static Long longOrNull(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                long v = o.optLong(k, 0L);
                return v != 0L ? v : null;
            }
        }
        return null;
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                String v = o.optString(k, "");
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return null;
    }

    private static String nullIfEmpty(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static void copyRatio(JSONObject ratio, Map<String, Long> out, String key, String alias) {
        long v = ratio.optLong(key, -1L);
        if (v < 0) v = ratio.optLong(alias, -1L);
        if (v >= 0) out.put(key, v);
    }

    /** 按 id 取编队（含 id&lt;0 的 GAME 只读镜像）；id==0/不存在返回 null。 */
    public FormationSpec byId(long id) {
        if (id == 0L) return null;
        for (FormationSpec s : formations) {
            if (s != null && s.id == id) return s;
        }
        return null;
    }

    /** 是否有编队（空库=App 未配置/未同步）。 */
    public boolean isEmpty() {
        return formations == null || formations.isEmpty();
    }

    public int size() {
        return formations == null ? 0 : formations.size();
    }

    /** 全部编队 id（诊断/状态行用；含 id&lt;0 的 GAME 镜像）。 */
    public List<Long> ids() {
        List<Long> out = new ArrayList<>();
        if (formations == null) return out;
        for (FormationSpec s : formations) {
            if (s != null && s.id != 0L) out.add(s.id);
        }
        return Collections.unmodifiableList(out);
    }

    /** 自动编队全局比例 [盾,矛,弓]（副本；未配置=50/20/30）。 */
    public int[] automaticSettings() {
        return automaticSettings == null ? DEFAULT_AUTOMATIC_SETTINGS.clone() : automaticSettings.clone();
    }

    /** 解析自检/日志：N 个编队，ids=[...]，自动比例=x/y/z。 */
    public String describe() {
        int[] a = automaticSettings();
        return "FormationLibrary{size=" + size() + ", ids=" + ids()
                + ", auto=" + a[0] + "/" + a[1] + "/" + a[2]
                + (automaticSettingsPresent ? "" : "(默认)") + "}";
    }
}
