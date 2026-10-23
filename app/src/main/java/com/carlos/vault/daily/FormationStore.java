package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批次10（2026-10-23）：统一编队库 —— App 侧编队数据唯一事实源。
 *
 * <p>用户需求：所有需要队列的功能（采集/情报/巨兽/巨熊/守护盟友）在功能页选好编队
 * （英雄/兵种/比例），服务器按此编队出征。参考网页版
 * {@code DailyRoleFormation*} / {@code DailyFormationConfigSupport} /
 * {@code DailyBearFormationConfig}。
 *
 * <p><b>与服务器冻结契约（vault-server {@code formation.FormationLibrary.parse}）</b>：
 * 编队库以<b>字符串 JSON</b>挂在角色 params 的保留键 {@value #KEY_LIBRARY} 上：
 * <pre>{"formations":[{"id":1,"name":"采集队","hero_ids":[50041,50039],
 *   "soldiers":{"infantry":20000,"lancer":1549,"marksman":28451},
 *   "ratio":{"infantry":40,"lancer":30,"marksman":30},"total":50000}]}</pre>
 * 每功能 params：{@code formation_mode}("AUTOMATIC"|"ROLE")、{@code formation_id}、
 * {@code soldiers}(可选覆盖)、{@code ratio}(AUTO 用)、{@code total}(可选；0=按容量自动)；
 * 巨熊上车：{@code join_formations} = [{"id":1-6,"mode":"AUTOMATIC|ROLE","formation_id":N}]。
 *
 * <p>向后兼容：未配置时不写任何字段 → 服务器保持旧行为（默认编队）。
 *
 * <p>本类纯函数（序列化/校验/编解码/摘要/键映射/目录解析）均为 static 且无 Android 依赖，
 * 可在 JVM 单测直跑；Context 方法只做 prefs/state 读写。
 */
public final class FormationStore {

    /** 角色 params 保留键（服务器 FormationLibrary.parse 读取；值=字符串 JSON）。 */
    public static final String KEY_LIBRARY = "formation_library";

    /** 编队英雄数量范围（网页版 RoleFormationPatrolMatrix / 编队上限 5）。 */
    public static final int HERO_MIN = 1;
    public static final int HERO_MAX = 5;

    /** 单角色编队库上限（防 params 膨胀；正常用户 ≤10）。 */
    public static final int MAX_FORMATIONS = 20;

    /** 编队模式（与网页版 DailyFormationMode / 服务器契约一致）。 */
    public static final String MODE_AUTOMATIC = "AUTOMATIC";
    public static final String MODE_ROLE = "ROLE";

    /** App 本地编队库 prefs（按 fpid:sid 隔离）。 */
    static final String PREFS = "formation_library";
    /** 服务器 state 键（编队目录：英雄/兵种/容量）。 */
    static final String STATE_KEY_PREFIX = "srv:formation:catalog:";

    // ==================== 模型 ====================

    /** 一支编队：名称/英雄/兵种数量/比例/总量（比例模式 total=0 表示按容量自动）。 */
    public static final class Formation {
        /** 编队编号（≥1，库内唯一；与网页版 formationId 一致）。 */
        public int id;
        public String name = "";
        /** 英雄 id 列表（1-5 个）。 */
        public final List<Long> heroIds = new ArrayList<>();
        /** 固定数量模式：盾/矛/弓兵力。 */
        public long infantry;
        public long lancer;
        public long marksman;
        /** 比例模式：盾/矛/弓百分比（和为 100）。 */
        public int ratioInfantry;
        public int ratioLancer;
        public int ratioMarksman;
        /** 总量（0=按服务器容量自动；比例模式常用）。 */
        public long total;
        /** UI 编辑态：true=比例+总量，false=固定数量。 */
        public boolean ratioMode;

        public Formation() {
        }

        public Formation(String name, List<Long> heroIds) {
            this.name = name == null ? "" : name;
            if (heroIds != null) this.heroIds.addAll(heroIds);
        }

        public long totalSoldiers() {
            return infantry + lancer + marksman;
        }

        public Formation copy() {
            Formation f = new Formation();
            f.id = id;
            f.name = name;
            f.heroIds.addAll(heroIds);
            f.infantry = infantry;
            f.lancer = lancer;
            f.marksman = marksman;
            f.ratioInfantry = ratioInfantry;
            f.ratioLancer = ratioLancer;
            f.ratioMarksman = ratioMarksman;
            f.total = total;
            f.ratioMode = ratioMode;
            return f;
        }
    }

    /** 编队目录里的英雄条目（服务器 state srv:formation:catalog 的 heroes[]）。 */
    public static final class Hero {
        public long id;
        public String name = "";
        public int level;
        public int star;
        public long power;
        /** 空闲/出征中（服务器原样透传；未知为空）。 */
        public String status = "";

        public String label() {
            StringBuilder sb = new StringBuilder();
            sb.append(name == null || name.isEmpty() ? ("英雄" + id) : name);
            if (level > 0) sb.append(" Lv").append(level);
            if (star > 0) sb.append(" · ").append(star).append('星');
            if (status != null && !status.isEmpty()) sb.append(" · ").append(status);
            return sb.toString();
        }
    }

    private FormationStore() {
    }

    // ==================== 纯函数：键 / 序列化 / 校验 / 摘要 ====================

    /** 本地库 prefs 键：按 fpid:sid 隔离（不同区服/账号互不串味）。 */
    public static String prefKey(String fpid, long sid) {
        return "f:" + nz(fpid) + ":" + sid;
    }

    /** 编队目录 state 键（服务器写 state → App 经 fetchState 回写 daily_state 同名键）。 */
    public static String catalogStateKey(String fpid, long sid) {
        return STATE_KEY_PREFIX + nz(fpid) + ":" + sid;
    }

    /** 编队模式归一：NORMAL/CUSTOM→ROLE（网页版 DailyFormationMode.from 同口径），未知→AUTOMATIC。 */
    public static String normalizeMode(String raw) {
        if (raw == null) return MODE_AUTOMATIC;
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("NORMAL".equals(v) || "CUSTOM".equals(v)) return MODE_ROLE;
        if (MODE_ROLE.equals(v)) return MODE_ROLE;
        return MODE_AUTOMATIC;
    }

    /** 模式中文（UI 展示）。 */
    public static String modeLabel(String raw) {
        return MODE_ROLE.equals(normalizeMode(raw)) ? "指定编队" : "自动编队";
    }

    /** 比例和（比例模式应为 100）。 */
    public static int ratioSum(Formation f) {
        if (f == null) return 0;
        return Math.max(0, f.ratioInfantry) + Math.max(0, f.ratioLancer) + Math.max(0, f.ratioMarksman);
    }

    /** 编队列表 → 契约 JSON（字符串）。空库返回 {@code {"formations":[]}}。 */
    public static String libraryJson(List<Formation> formations) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            if (formations != null) {
                for (Formation f : formations) {
                    if (f == null) continue;
                    arr.put(toJson(f));
                }
            }
            root.put("formations", arr);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    /** 单支编队 → 契约 JSON 对象。 */
    public static JSONObject toJson(Formation f) {
        JSONObject o = new JSONObject();
        if (f == null) return o;
        try {
            o.put("id", f.id);
            o.put("name", f.name == null ? "" : f.name);
            JSONArray heroes = new JSONArray();
            for (Long h : f.heroIds) {
                if (h != null && h > 0) heroes.put(h.longValue());
            }
            o.put("hero_ids", heroes);
            JSONObject soldiers = new JSONObject();
            soldiers.put("infantry", Math.max(0, f.infantry));
            soldiers.put("lancer", Math.max(0, f.lancer));
            soldiers.put("marksman", Math.max(0, f.marksman));
            o.put("soldiers", soldiers);
            JSONObject ratio = new JSONObject();
            ratio.put("infantry", Math.max(0, f.ratioInfantry));
            ratio.put("lancer", Math.max(0, f.ratioLancer));
            ratio.put("marksman", Math.max(0, f.ratioMarksman));
            o.put("ratio", ratio);
            o.put("total", Math.max(0, f.total));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * 契约 JSON 字符串 → 编队列表（宽容解析：坏条目跳过、不抛异常；供 UI/同步共用）。
     * 兼容 Map/List 与缺字段；hero_ids 支持 JSONArray 或逗号分隔字符串。
     */
    public static List<Formation> parseLibrary(String raw) {
        List<Formation> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(raw.trim());
            JSONArray arr = root.optJSONArray("formations");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Formation f = fromJson(o);
                if (f != null) out.add(f);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 单支编队 JSON → Formation；无有效 id 时返回 null。 */
    public static Formation fromJson(JSONObject o) {
        if (o == null) return null;
        Formation f = new Formation();
        f.id = o.optInt("id", 0);
        if (f.id <= 0) return null;
        f.name = o.optString("name", "");
        JSONArray heroes = o.optJSONArray("hero_ids");
        if (heroes != null) {
            for (int i = 0; i < heroes.length(); i++) {
                long id = heroes.optLong(i, 0);
                if (id > 0) f.heroIds.add(id);
            }
        } else {
            for (String part : o.optString("hero_ids", "").split(",")) {
                try {
                    long id = Long.parseLong(part.trim());
                    if (id > 0) f.heroIds.add(id);
                } catch (Throwable ignored) {
                }
            }
        }
        JSONObject soldiers = o.optJSONObject("soldiers");
        if (soldiers != null) {
            f.infantry = soldiers.optLong("infantry", 0);
            f.lancer = soldiers.optLong("lancer", 0);
            f.marksman = soldiers.optLong("marksman", 0);
        }
        JSONObject ratio = o.optJSONObject("ratio");
        if (ratio != null) {
            f.ratioInfantry = ratio.optInt("infantry", 0);
            f.ratioLancer = ratio.optInt("lancer", 0);
            f.ratioMarksman = ratio.optInt("marksman", 0);
        }
        f.total = o.optLong("total", 0);
        // 比例模式推断：数量全 0 且比例和为 100 → 比例模式（契约无 alloc 字段）
        f.ratioMode = f.totalSoldiers() == 0 && ratioSum(f) == 100;
        return f;
    }

    /**
     * 校验（UI 保存前调用；纯函数，返回 null=合法，否则中文原因）。
     * 规则：名称非空；英雄 1-5 个且不重复；兵种数量≥0；比例模式比例和=100 且各比例≤100。
     */
    public static String validate(Formation f) {
        if (f == null) return "编队为空";
        String name = f.name == null ? "" : f.name.trim();
        if (name.isEmpty()) return "编队名称不能为空";
        if (name.length() > 20) return "编队名称最多 20 个字";
        Set<Long> uniq = new LinkedHashSet<>();
        for (Long h : f.heroIds) {
            if (h != null && h > 0) uniq.add(h);
        }
        if (uniq.size() < HERO_MIN) return "请至少选择 1 个英雄";
        if (uniq.size() > HERO_MAX) return "英雄最多 " + HERO_MAX + " 个";
        if (f.infantry < 0 || f.lancer < 0 || f.marksman < 0 || f.total < 0) return "兵力/总量不能为负数";
        if (f.ratioMode) {
            if (f.ratioInfantry < 0 || f.ratioLancer < 0 || f.ratioMarksman < 0) return "比例不能为负数";
            if (ratioSum(f) != 100) return "比例之和必须等于 100（当前 " + ratioSum(f) + "）";
        } else if (f.totalSoldiers() <= 0) {
            return "请填写兵力数量（盾/矛/弓至少一项 >0）";
        }
        return null;
    }

    /** 下一编队 id（现有最大 +1；空库从 1 开始）。 */
    public static int nextId(List<Formation> list) {
        int max = 0;
        if (list != null) {
            for (Formation f : list) {
                if (f != null && f.id > max) max = f.id;
            }
        }
        return max + 1;
    }

    /** 编队摘要（功能页摘要行/列表副题）：`英雄2名 · 盾20000/矛1549/弓28451 · 总量50000`。 */
    public static String summary(Formation f) {
        if (f == null) return "未选择编队";
        StringBuilder sb = new StringBuilder();
        sb.append("英雄").append(f.heroIds == null ? 0 : f.heroIds.size()).append("名");
        if (f.ratioMode) {
            sb.append(" · 比例").append(Math.max(0, f.ratioInfantry)).append(':')
                    .append(Math.max(0, f.ratioLancer)).append(':')
                    .append(Math.max(0, f.ratioMarksman));
            sb.append(" · 总量").append(f.total <= 0 ? "按容量" : String.valueOf(f.total));
        } else {
            sb.append(" · 盾").append(f.infantry)
                    .append(" 矛").append(f.lancer)
                    .append(" 弓").append(f.marksman);
        }
        return sb.toString();
    }

    /** 目录 JSON → 英雄列表（坏条目跳过）。 */
    public static List<Hero> parseHeroes(JSONObject catalog) {
        List<Hero> out = new ArrayList<>();
        if (catalog == null) return out;
        JSONArray arr = catalog.optJSONArray("heroes");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Hero h = new Hero();
            h.id = o.optLong("id", 0);
            if (h.id <= 0) continue;
            h.name = o.optString("name", "");
            h.level = o.optInt("level", 0);
            h.star = o.optInt("star", 0);
            h.power = o.optLong("power", 0);
            h.status = o.optString("status", "");
            out.add(h);
        }
        return out;
    }

    /** 目录里的兵力库存（soldiers: {infantry,lancer,marksman}，缺省 0）。 */
    public static long soldierStock(JSONObject catalog, String key) {
        if (catalog == null) return -1;
        JSONObject s = catalog.optJSONObject("soldiers");
        if (s == null || !s.has(key)) return -1;
        return Math.max(0, s.optLong(key, 0));
    }

    /** 目录容量（state.capacity；服务器世界容量/编队上限，缺失或非法返回 -1）。 */
    public static long capacity(JSONObject catalog) {
        if (catalog == null || !catalog.has("capacity")) return -1;
        long v = catalog.optLong("capacity", -1);
        return v > 0 ? v : -1;
    }

    /** 三兵种最高档士兵 id（soldiers.best_ids，形如 [11000,21000,31000]；缺失返回空列表）。 */
    public static List<Long> bestSoldierIds(JSONObject catalog) {
        List<Long> out = new ArrayList<>();
        if (catalog == null) return out;
        JSONObject soldiers = catalog.optJSONObject("soldiers");
        JSONArray arr = soldiers == null ? null : soldiers.optJSONArray("best_ids");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            long id = arr.optLong(i, 0);
            if (id > 0) out.add(id);
        }
        return out;
    }

    /** 目录兵种库存摘要（"盾80000/矛40000/弓30000"；无库存返回空串）。 */
    public static String soldierStockText(JSONObject catalog) {
        long inf = soldierStock(catalog, "infantry");
        long lan = soldierStock(catalog, "lancer");
        long mar = soldierStock(catalog, "marksman");
        if (inf < 0 && lan < 0 && mar < 0) return "";
        StringBuilder sb = new StringBuilder();
        if (inf >= 0) sb.append("盾").append(inf);
        if (lan >= 0) {
            if (sb.length() > 0) sb.append('/');
            sb.append("矛").append(lan);
        }
        if (mar >= 0) {
            if (sb.length() > 0) sb.append('/');
            sb.append("弓").append(mar);
        }
        return sb.toString();
    }

    /** 目录是否新鲜（capturedAt 距今 ≤maxAgeMs；缺失按新鲜处理=不拦 UI）。 */
    public static boolean catalogFresh(JSONObject catalog, long nowMs, long maxAgeMs) {
        if (catalog == null) return false;
        long at = catalog.optLong("capturedAt", 0);
        if (at <= 0) return true;
        return nowMs - at <= maxAgeMs;
    }

    /** 英雄 id 列表 → 可读名串（有目录时带名字，否则纯 id，便于摘要展示）。 */
    public static String heroesText(Formation f, List<Hero> catalog) {
        if (f == null || f.heroIds == null || f.heroIds.isEmpty()) return "未选英雄";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.heroIds.size(); i++) {
            long id = f.heroIds.get(i);
            String name = null;
            if (catalog != null) {
                for (Hero h : catalog) {
                    if (h.id == id) {
                        name = h.name;
                        break;
                    }
                }
            }
            if (i > 0) sb.append("、");
            sb.append(name == null || name.isEmpty() ? String.valueOf(id) : name);
        }
        return sb.toString();
    }

    // ==================== 存取：本地编队库 ====================

    /** 读取某角色编队库（宽容解析；无库返回空列表）。 */
    public static List<Formation> list(Context ctx, String fpid, long sid) {
        if (ctx == null) return new ArrayList<>();
        try {
            String raw = prefs(ctx).getString(prefKey(fpid, sid), null);
            return parseLibrary(raw);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** 写回某角色编队库（按 id 升序保存；>MAX_FORMATIONS 只保留前 N 支）。 */
    public static void saveLibrary(Context ctx, String fpid, long sid, List<Formation> formations) {
        if (ctx == null) return;
        try {
            List<Formation> sorted = new ArrayList<>();
            if (formations != null) {
                for (Formation f : formations) {
                    if (f != null && f.id > 0) sorted.add(f);
                }
            }
            sorted.sort((x, y) -> Integer.compare(x.id, y.id));
            if (sorted.size() > MAX_FORMATIONS) {
                sorted = new ArrayList<>(sorted.subList(0, MAX_FORMATIONS));
            }
            prefs(ctx).edit().putString(prefKey(fpid, sid), libraryJson(sorted)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 同步用：编队库 JSON（无编队返回空串=不上传，保持旧行为）。 */
    public static String libraryJsonForSync(Context ctx, String fpid, long sid) {
        List<Formation> list = list(ctx, fpid, sid);
        if (list.isEmpty()) return "";
        return libraryJson(list);
    }

    /** 按 id 取编队；不存在返回 null。 */
    public static Formation byId(Context ctx, String fpid, long sid, long id) {
        for (Formation f : list(ctx, fpid, sid)) {
            if (f.id == id) return f;
        }
        return null;
    }

    /**
     * 新增/更新编队：id≤0 时分配 {@link #nextId}；id 已存在则整条替换。
     * 返回保存后的编队（含分配后的 id）；校验失败返回 null。
     */
    public static Formation upsert(Context ctx, String fpid, long sid, Formation f) {
        if (f == null || validate(f) != null) return null;
        List<Formation> list = list(ctx, fpid, sid);
        if (f.id <= 0) f.id = nextId(list);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == f.id) {
                list.set(i, f);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(f);
        saveLibrary(ctx, fpid, sid, list);
        return f;
    }

    /** 删除编队（不存在返回 false）。 */
    public static boolean delete(Context ctx, String fpid, long sid, long id) {
        List<Formation> list = list(ctx, fpid, sid);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) {
                list.remove(i);
                removed = true;
            }
        }
        if (removed) saveLibrary(ctx, fpid, sid, list);
        return removed;
    }

    /** 复制编队（新 id + 名称加「副本」）；源不存在返回 null。 */
    public static Formation duplicate(Context ctx, String fpid, long sid, long id) {
        Formation src = byId(ctx, fpid, sid, id);
        if (src == null) return null;
        Formation copy = src.copy();
        copy.id = 0;
        copy.name = (src.name == null ? "" : src.name) + "副本";
        if (copy.name.length() > 20) copy.name = copy.name.substring(0, 20);
        return upsert(ctx, fpid, sid, copy);
    }

    // ==================== 存取：服务器编队目录（state 键） ====================

    /** 读服务器编队目录（daily_state 键 srv:formation:catalog:{fpid}:{sid}）；缺失返回 null。 */
    public static JSONObject catalog(Context ctx, String fpid, long sid) {
        if (ctx == null) return null;
        try {
            String raw = ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE)
                    .getString(catalogStateKey(fpid, sid), null);
            if (raw == null || raw.trim().isEmpty()) return null;
            return new JSONObject(raw.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目录是否可用（含至少一个英雄）。 */
    public static boolean hasCatalog(Context ctx, String fpid, long sid) {
        return !parseHeroes(catalog(ctx, fpid, sid)).isEmpty();
    }

    // ==================== 每功能 params（formation_mode/formation_id/ratio/total） ====================

    /** 该功能编队模式（默认 AUTOMATIC；未配置不写字段=服务器旧行为）。 */
    public static String fnMode(Context ctx, String fpid, long sid, String fnId) {
        try {
            return normalizeMode(FnParams.params(ctx, fpid, sid, fnId)
                    .optString("formation_mode", MODE_AUTOMATIC));
        } catch (Throwable t) {
            return MODE_AUTOMATIC;
        }
    }

    /** 该功能指定编队 id（0=未指定）。 */
    public static long fnFormationId(Context ctx, String fpid, long sid, String fnId) {
        try {
            return FnParams.params(ctx, fpid, sid, fnId).optLong("formation_id", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 写该功能编队模式（AUTOMATIC 时清掉 formation_id，避免服务器按旧 id 出征）。 */
    public static void saveFnMode(Context ctx, String fpid, long sid, String fnId, String mode) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            String m = normalizeMode(mode);
            p.put("formation_mode", m);
            if (MODE_AUTOMATIC.equals(m)) p.remove("formation_id");
            FnParams.saveParams(ctx, fpid, sid, fnId, p);
        } catch (Throwable ignored) {
        }
    }

    /** 写该功能指定编队 id（同时把模式置 ROLE）。 */
    public static void saveFnFormation(Context ctx, String fpid, long sid, String fnId, long formationId) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            p.put("formation_mode", MODE_ROLE);
            p.put("formation_id", formationId);
            FnParams.saveParams(ctx, fpid, sid, fnId, p);
        } catch (Throwable ignored) {
        }
    }

    /** 写 AUTO 模式比例+总量（比例和必须 100，由 UI 校验；total=0 表示按服务器容量自动）。 */
    public static void saveFnAutoRatio(Context ctx, String fpid, long sid, String fnId,
                                       int inf, int lancer, int marksman, long total) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            p.put("formation_mode", MODE_AUTOMATIC);
            p.remove("formation_id");
            JSONObject ratio = new JSONObject();
            ratio.put("infantry", Math.max(0, inf));
            ratio.put("lancer", Math.max(0, lancer));
            ratio.put("marksman", Math.max(0, marksman));
            p.put("ratio", ratio);
            p.put("total", Math.max(0, total));
            FnParams.saveParams(ctx, fpid, sid, fnId, p);
        } catch (Throwable ignored) {
        }
    }

    /** 读 AUTO 比例（默认 40:30:30；返回 {inf,lancer,marksman,total}，total=0 表示按容量自动）。 */
    public static long[] fnAutoRatio(Context ctx, String fpid, long sid, String fnId) {
        long inf = 40, lancer = 30, marksman = 30;
        long total = 0;
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            JSONObject ratio = p.optJSONObject("ratio");
            if (ratio != null) {
                inf = ratio.optLong("infantry", inf);
                lancer = ratio.optLong("lancer", lancer);
                marksman = ratio.optLong("marksman", marksman);
            }
            total = p.optLong("total", 0);
        } catch (Throwable ignored) {
        }
        return new long[]{Math.max(0, inf), Math.max(0, lancer), Math.max(0, marksman),
                Math.max(0, total)};
    }

    /**
     * 角色发现前用户可能已建编队（写到 sid=0）；服务器登录学到真实 sid 后迁移到真实 sid
     * （与 {@link FnParams#migrateRole} 同口径；目标已有编队库时以目标为准）。
     */
    public static void migrateRole(Context ctx, String fpid, long fromSid, long toSid) {
        if (ctx == null || fpid == null || fpid.isEmpty() || fromSid == toSid) return;
        try {
            SharedPreferences sp = prefs(ctx);
            String from = prefKey(fpid, fromSid);
            String to = prefKey(fpid, toSid);
            String raw = sp.getString(from, null);
            if (raw == null) return;
            SharedPreferences.Editor ed = sp.edit();
            if (sp.getString(to, null) == null) ed.putString(to, raw);
            ed.remove(from);
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 巨熊上车编队（join_formations，id 1-6） ====================

    /** 读巨熊上车编队配置（join_formations 数组元素；缺失返回空对象）。 */
    public static JSONObject joinFormation(Context ctx, String fpid, long sid, int joinId) {
        try {
            Object v = FnParams.params(ctx, fpid, sid, "bear").opt("join_formations");
            if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && o.optInt("id", 0) == joinId) return o;
                }
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    /** 写巨熊上车编队：自动/指定（指定时必须给 formation_id）；幂等合并 id 1-6 的元素。 */
    public static void saveJoinFormation(Context ctx, String fpid, long sid, int joinId,
                                         String mode, long formationId) {
        if (joinId < 0 || joinId > 6) return;
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, "bear");
            JSONArray arr = p.optJSONArray("join_formations");
            if (arr == null) arr = new JSONArray();
            JSONArray next = new JSONArray();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("id", 0) == joinId) {
                    next.put(joinElement(joinId, mode, formationId));
                    found = true;
                } else {
                    next.put(o);
                }
            }
            if (!found) next.put(joinElement(joinId, mode, formationId));
            p.put("join_formations", next);
            FnParams.saveParams(ctx, fpid, sid, "bear", p);
        } catch (Throwable ignored) {
        }
    }

    /** 上车编队元素（契约：id 1-6 / mode / formation_id；AUTOMATIC 不带 formation_id）。 */
    static JSONObject joinElement(int joinId, String mode, long formationId) {
        JSONObject o = new JSONObject();
        try {
            String m = normalizeMode(mode);
            o.put("id", joinId);
            o.put("mode", m);
            if (MODE_ROLE.equals(m) && formationId > 0) o.put("formation_id", formationId);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ==================== 内部 ====================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
