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
 * + {@code persistence/entity/DailyRoleFormationEntity}。**解析是防御式的**：非法/缺失条目
 * 跳过而不是抛异常；整个 JSON 非法 → 返回空库（调用方按"库缺失"SKIP，不静默回退旧编队）。
 *
 * <p>JSON 形状：
 * <pre>
 * {"formations":[
 *   {"id":1,"name":"采集队","hero_ids":[50041,50039],
 *    "soldiers":{"infantry":20000,"lancer":1549,"marksman":28451},
 *    "ratio":{"infantry":40,"lancer":30,"marksman":30},"total":50000}]}
 * </pre>
 */
public final class FormationLibrary {

    /** 编队列表（按 id 去重，保持 App 保存顺序；可能为空）。 */
    public List<FormationSpec> formations = new ArrayList<>();

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
            JSONArray arr = root.optJSONArray("formations");
            if (arr == null) arr = root.optJSONArray("list");
            if (arr == null) return lib;
            Map<Long, FormationSpec> seen = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                FormationSpec spec = parseSpec(o);
                if (spec == null || spec.id <= 0 || seen.containsKey(spec.id)) continue;
                seen.put(spec.id, spec);
            }
            lib.formations = new ArrayList<>(seen.values());
        } catch (Throwable ignored) {
            return empty();
        }
        return lib;
    }

    private static FormationSpec parseSpec(JSONObject o) {
        try {
            FormationSpec s = new FormationSpec();
            s.id = o.optLong("id", 0L);
            if (s.id <= 0) return null;
            s.name = o.optString("name", "");
            JSONArray heroes = o.optJSONArray("hero_ids");
            if (heroes == null) heroes = o.optJSONArray("heroIds");
            if (heroes != null) {
                for (int i = 0; i < heroes.length(); i++) {
                    long id = heroes.optLong(i, 0L);
                    if (id > 0) s.heroIds.add(id);
                }
            }
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
                if (!r.isEmpty()) s.ratio = r;
            }
            s.total = Math.max(0L, o.optLong("total", 0L));
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void copyRatio(JSONObject ratio, Map<String, Long> out, String key, String alias) {
        long v = ratio.optLong(key, -1L);
        if (v < 0) v = ratio.optLong(alias, -1L);
        if (v >= 0) out.put(key, v);
    }

    /** 按 id 取编队；不存在返回 null（调用方 SKIP，不回退）。 */
    public FormationSpec byId(long id) {
        if (id <= 0) return null;
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

    /** 全部编队 id（诊断/状态行用）。 */
    public List<Long> ids() {
        List<Long> out = new ArrayList<>();
        if (formations == null) return out;
        for (FormationSpec s : formations) {
            if (s != null && s.id > 0) out.add(s.id);
        }
        return Collections.unmodifiableList(out);
    }

    /** 解析自检/日志：N 个编队，ids=[...]。 */
    public String describe() {
        return "FormationLibrary{size=" + size() + ", ids=" + ids() + "}";
    }
}
