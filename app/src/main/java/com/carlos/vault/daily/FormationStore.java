package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import com.carlos.vault.daily.formation.FormationBinding;
import com.carlos.vault.daily.formation.PresetFormations;
import com.carlos.vault.daily.formation.FormationCatalogStore;
import com.carlos.vault.daily.formation.FormationModel;
import com.carlos.vault.daily.formation.FormationRules;

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

    /** 编队英雄数量上限：游戏规则每队列最多 3 名英雄（盾/矛/弓各一，用户实证）。 */
    public static final int HERO_MIN = 1;
    public static final int HERO_MAX = 3;

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

        /** 旧模型 → v2 模型（v2 全量 API 用；契约 §2.2 兼容桥）。 */
        public FormationModel toModel() {
            return FormationStore.toModel(this);
        }
    }

    /** 编队目录里的英雄条目（服务端 state v2 {@code heroes[]} / 网页 {@code heroOptions[]}）。 */
    public static final class Hero {
        public long id;
        public String name = "";
        public int level;
        /** 原始星级（0..30，网页实测 30=5 星）。 */
        public int star;
        /** 展示星级（0..5，已 clamp）。 */
        public int starDisplay;
        /** 代数（0=基础）。 */
        public int generation;
        public long power;
        /** 该英雄为出征容量贡献的值（Σ 公式用）。 */
        public long troopCapacity;
        /** 是否在家可用（S2：available 只表示"在城可出征"）。 */
        public boolean available = true;
        /** 职业类型：1 盾 / 2 矛 / 3 弓 / 0 未知。 */
        public int professionType;
        /** 中文职业：盾兵 / 矛兵 / 射手（空=未知）。 */
        public String profession = "";
        /** 可用性枚举：AVAILABLE/HERO_AWAY/RESERVED/SYNC_PENDING/SYNC_ERROR。 */
        public String availabilityState = "";
        /** 中文可用性原因（如"英雄在家"）。 */
        public String availabilityReason = "";
        /** 旧版目录 status 原样（兼容保留）。 */
        public String status = "";
        /** 队长第一个远征技能等级（0..5，0=未同步；契约 §1）。 */
        public int skillLevel;
        /** 专武等级（0..10，0=未同步/无专武；契约 §1）。 */
        public int weaponLevel;

        public String label() {
            StringBuilder sb = new StringBuilder();
            sb.append(name == null || name.isEmpty() ? ("英雄" + id) : name);
            if (level > 0) sb.append(" Lv").append(level);
            if (starDisplay > 0) sb.append(" · ").append(starDisplay).append('星');
            else if (star > 0) sb.append(" · ").append(star).append('星');
            if (status != null && !status.isEmpty()) sb.append(" · ").append(status);
            return sb.toString();
        }

        /** 可用性中文（在家/未归队/...）。 */
        public String availabilityText() {
            if (availabilityReason != null && !availabilityReason.isEmpty()) {
                return availabilityReason;
            }
            if (available) return "英雄在家";
            if ("HERO_AWAY".equals(availabilityState)) return "英雄未归队";
            if ("RESERVED".equals(availabilityState)) return "前序占用";
            if ("SYNC_PENDING".equals(availabilityState)) return "等待同步";
            if ("SYNC_ERROR".equals(availabilityState)) return "同步异常";
            return "未归队";
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

    /**
     * 编队目录 state 键（v2，uid 感知；T7 2026-10-25）。
     *
     * <p>同 fpid:sid 下可有多个角色（生产实证 888 区两个 uid），服务端 v2 目录键带 uid
     * （{@code srv:formation:catalog:{fpid}:{sid}:{uid}}）并把旧键作一版过渡双写；
     * 只读旧键会被后写角色覆盖 → 英雄目录串角色。uid 为空/{@code "0"} 时返回旧键
     * （与旧行为逐字节一致）。
     */
    public static String catalogStateKey(String fpid, long sid, String uid) {
        String u = nz(uid);
        if (u.isEmpty() || "0".equals(u)) return catalogStateKey(fpid, sid);
        return catalogStateKey(fpid, sid) + ":" + u;
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

    /**
     * 目录 JSON → 英雄列表（坏条目跳过）。
     *
     * <p>兼容两种形状：服务端 state v2 {@code heroes[]}（契约 §3.2）与网页 collection
     * {@code heroOptions[]}（线上 GET，含 professionType/profession/generation/starDisplay/
     * troopCapacity/available/availabilityState/availabilityReason）。名称做去引号清洗
     * （服务端历史脏数据 {@code "\"巴希提\""} 兜底，App 审计 B1）。
     */
    public static List<Hero> parseHeroes(JSONObject catalog) {
        List<Hero> out = new ArrayList<>();
        if (catalog == null) return out;
        JSONArray arr = catalog.optJSONArray("heroes");
        if (arr == null) arr = catalog.optJSONArray("heroOptions");
        if (arr == null) arr = catalog.optJSONArray("hero_options");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Hero h = new Hero();
            h.id = o.optLong("id", 0);
            if (h.id <= 0) continue;
            h.name = FormationModel.cleanName(o.optString("name", ""));
            if (h.name.isEmpty()) h.name = com.carlos.vault.wjdr.asset.HeroCatalog.name(h.id);
            h.name = FormationModel.cleanName(h.name);
            if (h.name.isEmpty()) h.name = "英雄" + h.id;
            // 等级/星级/战力：宽容多别名（生产 notify_hero_init 缺 power 时仍保留 Lv/星 可展示）；
            // 有效等级缺省回退 training_lev，星级缺省由 star（0-30）折算展示星级。
            h.level = firstInt(o, "level", "effectiveLevel", "effective_level", "lev",
                    "training_lev", "trainingLev");
            h.star = firstInt(o, "star", "star_lv", "starLevel", "star_level");
            h.starDisplay = clampInt(firstInt(o, "starDisplay", "star_display"), 0, 5);
            if (h.starDisplay == 0 && h.star > 0) {
                h.starDisplay = h.star <= 5 ? (int) h.star : clampInt((int) (h.star / 6), 1, 5);
            }
            h.generation = Math.max(0, o.optInt("generation", 0));
            h.power = Math.max(0, firstLong(o, "power", "fight_num", "fight_capacity",
                    "fight", "combat_power", "power_value"));
            h.troopCapacity = Math.max(0, o.optLong("troopCapacity",
                    o.optLong("troop_capacity", 0)));
            h.status = o.optString("status", "");
            // 契约 §1：队长第一个远征技能 / 专武（宽容别名；缺字段=0，不报错）
            h.skillLevel = clampInt(firstInt(o, "expeditionSkillLevel",
                    "expedition_skill_level", "skillLevel", "skill_level",
                    "firstSkillLevel", "first_skill_level"), 0, 5);
            h.weaponLevel = clampInt(firstInt(o, "weaponLevel", "weapon_level",
                    "exclusiveWeaponLevel", "exclusive_weapon_level", "elv"), 0, 10);
            h.availabilityState = o.optString("availabilityState",
                    o.optString("availability_state", ""));
            h.availabilityReason = o.optString("availabilityReason",
                    o.optString("availability_reason", ""));
            // 职业：服务端 state v2 用数值 profession，网页 collection 用中文 profession
            Object prof = o.opt("profession");
            if (prof instanceof Number) {
                h.professionType = clampInt(((Number) prof).intValue(), 0, 3);
                h.profession = FormationRules.professionName(h.professionType);
            } else if (prof instanceof String) {
                h.profession = ((String) prof).trim();
                h.professionType = FormationRules.professionTypeOf(h.profession);
            }
            int profType = o.optInt("professionType",
                    o.optInt("profession_type", h.professionType));
            if (profType >= 0 && profType <= 3 && profType != h.professionType) {
                h.professionType = profType;
                if (h.profession.isEmpty()) {
                    h.profession = FormationRules.professionName(profType);
                }
            }
            String profName = o.optString("professionName",
                    o.optString("profession_name", ""));
            if (!profName.isEmpty()) h.profession = profName;
            if (h.professionType == 0 && !h.profession.isEmpty()) {
                h.professionType = FormationRules.professionTypeOf(h.profession);
            }
            // 可用性：显式 available 优先；否则按 availabilityState / 旧 status 推导
            if (o.has("available")) {
                h.available = o.optBoolean("available", true);
            } else if (!h.availabilityState.isEmpty()) {
                h.available = "AVAILABLE".equalsIgnoreCase(h.availabilityState);
            } else if (!h.status.isEmpty()) {
                String s = h.status.toLowerCase(java.util.Locale.ROOT);
                h.available = !(s.contains("away") || s.contains("march")
                        || s.contains("出征") || s.contains("驻防") || s.contains("outside"));
            }
            if (!h.available && h.availabilityReason.isEmpty()) {
                h.availabilityReason = h.availabilityText();
            }
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

    // ==================== 存取：本地编队库（v2 事实源 + 旧 API 兼容桥） ====================
    //
    // 存储格式 = 契约 §5：{"version":2,"automatic_settings":{...},"formations":[<FormationModel v2>]}
    // 旧 API（list/saveLibrary/upsert/delete/duplicate）仍可用，内部桥接到 v2；
    // 旧版 int id 无法表示内置大 id（9000000001/2），旧视图会跳过它们（新代码用 formations()）。

    /** 读取某角色编队库（v2 全量，宽容解析；无库返回空列表）。 */
    public static List<FormationModel> formations(Context ctx, String fpid, long sid) {
        if (ctx == null) return new ArrayList<>();
        return readModels(ctx, fpid, sid);
    }

    /** 按 id 取 v2 编队；不存在返回 null。 */
    public static FormationModel formation(Context ctx, String fpid, long sid, long id) {
        return FormationRules.findById(formations(ctx, fpid, sid), id);
    }

    /**
     * 新增/更新编队（契约 §2.2）：{@code id<=0} 新建分配 id；{@code id>0} 覆盖；
     * 游戏编队（{@code id<0}）自动转自定义副本；受保护内置（9000000001/2）返回 null 不修改。
     *
     * <p>覆盖时保留原 {@code source/builtIn/templateKey} 并递增 version；名称截断 30 字；
     * 保存时按当前目录刷新在城可用性字段。
     */
    public static FormationModel saveFormation(Context ctx, String fpid, long sid, FormationModel f) {
        return saveFormation(ctx, fpid, sid, "", f);
    }

    /**
     * 保存编队（v2，uid 感知；T7 2026-10-25）：可用性标记（是否在家）用 uid 键目录精确刷新，
     * 避免同 fpid:sid 多角色时按另一角色的目录把英雄标成"未归队/在家"。
     */
    public static FormationModel saveFormation(Context ctx, String fpid, long sid, String uid,
                                               FormationModel f) {
        if (ctx == null || f == null) return null;
        List<FormationModel> lib = formations(ctx, fpid, sid);
        FormationModel target = f.copy();
        String name = FormationModel.truncateName(target.name);
        if (name.isEmpty()) return null;
        target.name = name;

        boolean isNew = target.id <= 0 || target.id < 0 || FormationRules.findById(lib, target.id) == null;
        if (target.id < 0) {
            // 游戏编队只读 → 保存即生成自定义副本（契约 §2.5）
            target.id = 0;
            target.source = FormationModel.SOURCE_CUSTOM;
            target.builtIn = false;
            target.templateKey = null;
            isNew = true;
        }
        if (isNew) {
            target.id = nextFormationId(lib);
            target.source = FormationModel.SOURCE_CUSTOM;
            if (target.builtIn && target.templateKey == null) target.builtIn = false;
            target.version = Math.max(1, target.version);
            target.updatedAt = System.currentTimeMillis();
            lib.add(target);
        } else {
            FormationModel existing = FormationRules.findById(lib, target.id);
            target.source = existing.source;
            target.builtIn = existing.builtIn || target.builtIn;
            if (existing.templateKey != null) target.templateKey = existing.templateKey;
            target.version = Math.max(existing.version + 1, 2);
            target.updatedAt = System.currentTimeMillis();
            for (int i = 0; i < lib.size(); i++) {
                if (lib.get(i).id == target.id) {
                    lib.set(i, target);
                    break;
                }
            }
        }
        // 剔除非法队长（不在上阵列表 → 由 heroIds[0] 兜底）
        if (target.captainHeroId != null && target.captainHeroId > 0
                && !target.hasHero(target.captainHeroId)) {
            target.captainHeroId = target.heroIds.isEmpty() ? null : target.heroIds.get(0);
        }
        FormationRules.markAvailability(target, catalogOf(ctx, fpid, sid, uid));
        writeModels(ctx, fpid, sid, lib, null);
        return target;
    }

    /** 删除编队（契约 §2.2）：19 套预设永远保留；不存在静默返回。 */
    public static void deleteFormation(Context ctx, String fpid, long sid, long id) {
        if (ctx == null) return;
        if (PresetFormations.isPreset(id)) return;   // 预设是实时最强模板，不允许删除
        List<FormationModel> lib = formations(ctx, fpid, sid);
        boolean removed = false;
        for (int i = lib.size() - 1; i >= 0; i--) {
            if (lib.get(i).id == id) {
                lib.remove(i);
                removed = true;
            }
        }
        if (removed) writeModels(ctx, fpid, sid, lib, null);
    }

    /** 复制编队（契约 §2.2）：名称 + 「（副本）」并截断 30 字；源不存在返回 null。 */
    public static FormationModel duplicateFormation(Context ctx, String fpid, long sid, long id) {
        FormationModel src = formation(ctx, fpid, sid, id);
        if (src == null) return null;
        FormationModel copy = src.copy();
        copy.id = 0;
        copy.name = FormationModel.truncateName(src.name + "（副本）");
        copy.builtIn = false;
        copy.templateKey = null;
        copy.source = FormationModel.SOURCE_CUSTOM;
        copy.version = 1;
        return saveFormation(ctx, fpid, sid, copy);
    }

    /** 懒创建 19 套「预设编队」（契约 §2.6 / 2026-11-03 用户确认保留预设）。 */
    public static void ensurePresets(Context ctx, String fpid, long sid) {
        ensurePresets(ctx, fpid, sid, "");
    }

    /**
     * 懒创建 19 套预设编队（v2，uid 感知）：目录用 {@code uid} 键精确读取，
     * 避免同 fpid:sid 多角色时用错角色的英雄懒填预设编队。
     *
     * <p>幂等：已存在的预设不覆盖用户改名/兵量；目录未就绪时只清理旧内置，不创建预设。
     * 2026-11-03：用户要求删除网页版 8 套「内置编队」（错误数据），本方法会从库中
     * 清掉这些历史 id，再补齐 19 套预设。
     */
    public static void ensurePresets(Context ctx, String fpid, long sid, String uid) {
        if (ctx == null) return;
        List<FormationModel> lib = formations(ctx, fpid, sid);
        String before = fingerprint(lib);
        int removed = removeLegacyBuiltIns(lib);
        int created = PresetFormations.ensure(lib, catalogOf(ctx, fpid, sid, uid));
        if (removed > 0 || created > 0 || !before.equals(fingerprint(lib))) {
            writeModels(ctx, fpid, sid, lib, null);
        }
    }

    /** 旧 API 兼容：等价 {@link #ensurePresets(Context, String, long, String)}。 */
    @Deprecated
    public static void ensureBuiltIns(Context ctx, String fpid, long sid, String uid) {
        ensurePresets(ctx, fpid, sid, uid);
    }

    /** 旧 API 兼容（无 uid）。 */
    @Deprecated
    public static void ensureBuiltIns(Context ctx, String fpid, long sid) {
        ensurePresets(ctx, fpid, sid, "");
    }

    /** 网页版 8 套「内置编队」历史 id（2026-11-03 用户确认错误，删除）。 */
    private static final long[] LEGACY_BUILTIN_IDS = {
            9000000001L, 9000000002L, 57L, 55L, 54L, 56L, 53L, 58L};

    /** 从库中删除旧「内置编队」；返回删除数量。 */
    private static int removeLegacyBuiltIns(List<FormationModel> lib) {
        if (lib == null) return 0;
        int n = 0;
        for (int i = lib.size() - 1; i >= 0; i--) {
            FormationModel f = lib.get(i);
            if (f == null) continue;
            for (long id : LEGACY_BUILTIN_IDS) {
                if (f.id == id) {
                    lib.remove(i);
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** 自动编队全局比例（[盾,矛,弓]；App 库内优先，服务端目录兜底，默认 50/20/30）。 */
    public static int[] automaticSettings(Context ctx, String fpid, long sid) {
        if (ctx == null) return new int[]{50, 20, 30};
        int[] local = localAutomaticSettings(ctx, fpid, sid);
        return local != null ? local : catalogOf(ctx, fpid, sid).automaticSettings();
    }

    /**
     * 自动编队全局比例（v2，uid 感知；T7 2026-10-25）：App 本地库（fpid:sid 口径，契约 §0 共享）
     * 优先；兜底目录用 uid 键精确读取，避免多角色同区服时读到另一角色的全局比例。
     */
    public static int[] automaticSettings(Context ctx, String fpid, long sid, String uid) {
        if (ctx == null) return new int[]{50, 20, 30};
        int[] local = localAutomaticSettings(ctx, fpid, sid);
        return local != null ? local : catalogOf(ctx, fpid, sid, uid).automaticSettings();
    }

    /** 本地库里的自动比例（缺失/非法返回 null，交由调用方走目录兜底）。 */
    private static int[] localAutomaticSettings(Context ctx, String fpid, long sid) {
        try {
            JSONObject root = readRoot(ctx, fpid, sid);
            JSONObject local = root.optJSONObject("automatic_settings");
            if (local != null) {
                int inf = clampPct(local.optInt("infantry_ratio",
                        local.optInt("infantryRatio", -1)));
                int lanc = clampPct(local.optInt("lancer_ratio",
                        local.optInt("lancerRatio", -1)));
                int mark = clampPct(local.optInt("marksman_ratio",
                        local.optInt("marksmanRatio", -1)));
                if (inf >= 0 && lanc >= 0 && mark >= 0 && inf + lanc + mark == 100) {
                    return new int[]{inf, lanc, mark};
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 保存自动编队全局比例（三项 clamp 0..100；和=100 由 UI 校验，后端再校验）。 */
    public static void saveAutomaticSettings(Context ctx, String fpid, long sid,
                                             int inf, int lanc, int mark) {
        if (ctx == null) return;
        try {
            JSONObject root = readRoot(ctx, fpid, sid);
            JSONObject as = new JSONObject();
            as.put("infantry_ratio", clampPct(inf));
            as.put("lancer_ratio", clampPct(lanc));
            as.put("marksman_ratio", clampPct(mark));
            root.put("automatic_settings", as);
            prefs(ctx).edit().putString(prefKey(fpid, sid), root.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 上传服务端的 params JSON（契约 §5：version/automatic_settings/formations 全量 v2）。 */
    public static String libraryJson(Context ctx, String fpid, long sid) {
        if (ctx == null) return "{\"version\":2,\"automatic_settings\":{\"infantry_ratio\":50,"
                + "\"lancer_ratio\":20,\"marksman_ratio\":30},\"formations\":[]}";
        try {
            JSONObject root = readRoot(ctx, fpid, sid);
            root.put("version", 2);
            if (root.optJSONObject("automatic_settings") == null) {
                int[] a = automaticSettings(ctx, fpid, sid);
                JSONObject as = new JSONObject();
                as.put("infantry_ratio", a[0]);
                as.put("lancer_ratio", a[1]);
                as.put("marksman_ratio", a[2]);
                root.put("automatic_settings", as);
            }
            JSONArray arr = new JSONArray();
            for (FormationModel m : readModels(ctx, fpid, sid)) {
                arr.put(m.toJson(false));
            }
            root.put("formations", arr);
            return root.toString();
        } catch (Throwable t) {
            return "{\"version\":2,\"formations\":[]}";
        }
    }

    /** 读取旧版编队列表（兼容桥；跳过 int 无法表示的内置项）。 */
    public static List<Formation> list(Context ctx, String fpid, long sid) {
        List<Formation> out = new ArrayList<>();
        for (FormationModel m : formations(ctx, fpid, sid)) {
            Formation f = fromModel(m);
            if (f != null) out.add(f);
        }
        return out;
    }

    /** 写回旧版编队列表（兼容桥；保留旧 UI 无法表示的内置/游戏项，按 id 升序，>MAX 截断）。 */
    public static void saveLibrary(Context ctx, String fpid, long sid, List<Formation> formations) {
        if (ctx == null) return;
        try {
            List<FormationModel> next = new ArrayList<>();
            for (FormationModel m : formations(ctx, fpid, sid)) {
                if (m.id > Integer.MAX_VALUE || m.id < 0) next.add(m);
            }
            List<FormationModel> incoming = new ArrayList<>();
            if (formations != null) {
                for (Formation f : formations) {
                    if (f != null && f.id > 0) incoming.add(toModel(f));
                }
            }
            incoming.sort((x, y) -> Long.compare(x.id, y.id));
            if (incoming.size() > MAX_FORMATIONS) {
                incoming = new ArrayList<>(incoming.subList(0, MAX_FORMATIONS));
            }
            next.addAll(incoming);
            writeModels(ctx, fpid, sid, next, null);
        } catch (Throwable ignored) {
        }
    }

    /** 同步用：编队库 JSON（无编队返回空串=不上传，保持旧行为；内容为契约 §5 v2）。 */
    public static String libraryJsonForSync(Context ctx, String fpid, long sid) {
        if (formations(ctx, fpid, sid).isEmpty()) return "";
        return libraryJson(ctx, fpid, sid);
    }

    /** 按 id 取旧版编队；不存在返回 null（int 无法表示的内置 id 也返回 null）。 */
    public static Formation byId(Context ctx, String fpid, long sid, long id) {
        for (Formation f : list(ctx, fpid, sid)) {
            if (f.id == id) return f;
        }
        return null;
    }

    /**
     * 旧版新增/更新编队（兼容桥）：id≤0 时分配 {@link #nextId}；id 已存在则整条替换。
     * 返回保存后的编队（含分配后的 id）；校验失败返回 null。
     */
    public static Formation upsert(Context ctx, String fpid, long sid, Formation f) {
        if (f == null || validate(f) != null) return null;
        FormationModel saved = saveFormation(ctx, fpid, sid, toModel(f));
        if (saved == null || saved.id <= 0 || saved.id > Integer.MAX_VALUE) return null;
        f.id = (int) saved.id;
        return f;
    }

    /** 旧版删除编队（不存在返回 false）。 */
    public static boolean delete(Context ctx, String fpid, long sid, long id) {
        if (formation(ctx, fpid, sid, id) == null) return false;
        deleteFormation(ctx, fpid, sid, id);
        return true;
    }

    /** 旧版复制编队（新 id + 名称加「副本」）；源不存在返回 null。 */
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
        return readCatalogKey(ctx, catalogStateKey(fpid, sid));
    }

    /**
     * 读服务器编队目录（v2，uid 感知；T7 2026-10-25）。
     *
     * <p><b>读取优先级</b>：① {@code srv:formation:catalog:{fpid}:{sid}:{uid}}（v2 键，
     * 角色精确匹配）；② 缺失/空/解析失败 → 回退旧键 {@code ...:{fpid}:{sid}}（一版过渡兼容）；
     * ③ 都无 → 返回 null（调用方 {@link FormationCatalogStore} 降级 configured()=false）。
     * uid 为空/{@code "0"} 时等价于旧方法。
     */
    public static JSONObject catalog(Context ctx, String fpid, long sid, String uid) {
        JSONObject own = readCatalogKey(ctx, catalogStateKey(fpid, sid, uid));
        if (own != null) return own;
        String u = nz(uid);
        if (u.isEmpty() || "0".equals(u)) return null;          // 旧键即本体，避免重复读
        return readCatalogKey(ctx, catalogStateKey(fpid, sid)); // 过渡期回退旧键
    }

    /** 读 daily_state 中单个目录键（缺失/空/坏 JSON → null）。 */
    private static JSONObject readCatalogKey(Context ctx, String key) {
        if (ctx == null || key == null) return null;
        try {
            String raw = ctx.getSharedPreferences("daily_state", Context.MODE_PRIVATE)
                    .getString(key, null);
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

    /** 目录是否可用（uid 感知；优先级同 {@link #catalog(Context, String, long, String)}）。 */
    public static boolean hasCatalog(Context ctx, String fpid, long sid, String uid) {
        return !parseHeroes(catalog(ctx, fpid, sid, uid)).isEmpty();
    }

    /** App 侧目录 v2（契约 §2.3）：恒返回非空对象；无目录时 {@code configured()=false}（降级）。 */
    public static FormationCatalogStore catalogOf(Context ctx, String fpid, long sid) {
        return new FormationCatalogStore(catalog(ctx, fpid, sid));
    }

    /** App 侧目录 v2（uid 感知；优先级同 {@link #catalog(Context, String, long, String)}）。 */
    public static FormationCatalogStore catalogOf(Context ctx, String fpid, long sid, String uid) {
        return new FormationCatalogStore(catalog(ctx, fpid, sid, uid));
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

    /** 该功能指定编队 id（0=未指定；有 {@code formation_ids} 数组时取首个，兼容旧调用）。 */
    public static long fnFormationId(Context ctx, String fpid, long sid, String fnId) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            long id = p.optLong("formation_id", 0);
            if (id != 0) return id;
            long[] ids = FormationBinding.parseIds(p);
            return ids.length > 0 ? ids[0] : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 该功能绑定的编队 id 数组（契约 §2.2；兼容旧 {@code formation_id}）。 */
    public static long[] fnFormationIds(Context ctx, String fpid, long sid, String fnId) {
        return FormationBinding.readIds(ctx, fpid, sid, fnId);
    }

    /** 写该功能绑定编队 id 数组（模式置 ROLE；空数组=清引用并回落 AUTOMATIC）。 */
    public static void saveFnFormationIds(Context ctx, String fpid, long sid, String fnId,
                                          long[] ids) {
        if (ids == null || ids.length == 0) {
            FormationBinding.writeAutomatic(ctx, fpid, sid, fnId);
            return;
        }
        FormationBinding.writeIds(ctx, fpid, sid, fnId, ids);
    }

    /**
     * 队列绑定校验（契约 §2.2 / 网页 §4.6 文案）：{@code null}=合法，否则中文原因。
     */
    public static String validateBinding(FormationCatalogStore cat, List<FormationModel> lib,
                                         String fnId, String mode, long[] ids) {
        return FormationBinding.validate(cat, lib, fnId, mode, ids);
    }

    /** 写该功能编队模式（AUTOMATIC 时清掉 formation_id，避免服务器按旧 id 出征）。 */
    public static void saveFnMode(Context ctx, String fpid, long sid, String fnId, String mode) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            String m = normalizeMode(mode);
            p.put("formation_mode", m);
            if (MODE_AUTOMATIC.equals(m)) {
                p.remove("formation_id");
                p.remove("formation_ids");
            }
            FnParams.saveParams(ctx, fpid, sid, fnId, p);
        } catch (Throwable ignored) {
        }
    }

    /** 写该功能指定编队 id（同时把模式置 ROLE；兼容写 formation_ids 数组）。 */
    public static void saveFnFormation(Context ctx, String fpid, long sid, String fnId, long formationId) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            FormationBinding.applyIds(p, formationId > 0 ? new long[]{formationId} : new long[0]);
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

    // ==================== 功能级兵量覆盖（soldiers，批次11） ====================

    /** 读该功能的兵量覆盖 {inf,lancer,marksman}（全 0=未覆盖，使用编队库兵量）。 */
    public static long[] fnSoldiersOverride(Context ctx, String fpid, long sid, String fnId) {
        try {
            return parseSoldiers(FnParams.params(ctx, fpid, sid, fnId)
                    .optJSONObject("soldiers"));
        } catch (Throwable t) {
            return new long[]{0, 0, 0};
        }
    }

    /** 纯函数：soldiers JSON → {inf,lancer,marksman}（缺失/非法=0；负数归 0）。 */
    public static long[] parseSoldiers(JSONObject soldiers) {
        long[] out = new long[]{0, 0, 0};
        if (soldiers == null) return out;
        out[0] = Math.max(0, soldiers.optLong("infantry", 0));
        out[1] = Math.max(0, soldiers.optLong("lancer", 0));
        out[2] = Math.max(0, soldiers.optLong("marksman", 0));
        return out;
    }

    /** 纯函数：构造 soldiers 覆盖 JSON（契约键 infantry/lancer/marksman）。 */
    public static JSONObject soldiersJson(long infantry, long lancer, long marksman) {
        JSONObject o = new JSONObject();
        try {
            o.put("infantry", Math.max(0, infantry));
            o.put("lancer", Math.max(0, lancer));
            o.put("marksman", Math.max(0, marksman));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 覆盖文案：全 0 → "未覆盖"；否则 "覆盖：盾x/矛y/弓z"。 */
    public static String soldiersOverrideText(long[] s) {
        if (s == null || (s[0] <= 0 && s[1] <= 0 && s[2] <= 0)) return "未覆盖";
        return "覆盖：盾" + Math.max(0, s[0]) + "/矛" + Math.max(0, s[1])
                + "/弓" + Math.max(0, s[2]);
    }

    /** 写该功能兵量覆盖；三项全 ≤0 → 移除 soldiers 键（恢复用编队库兵量）。 */
    public static void saveFnSoldiersOverride(Context ctx, String fpid, long sid, String fnId,
                                              long infantry, long lancer, long marksman) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, fnId);
            if (infantry <= 0 && lancer <= 0 && marksman <= 0) {
                p.remove("soldiers");
            } else {
                p.put("soldiers", soldiersJson(infantry, lancer, marksman));
            }
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

    /** 上车槽是否启用（缺省 true，兼容老数据无 enabled 键）。 */
    public static boolean joinEnabled(JSONObject cfg) {
        if (cfg == null || !cfg.has("enabled")) return true;
        Object v = cfg.opt("enabled");
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return !"false".equalsIgnoreCase(String.valueOf(v).trim());
    }

    /** 读巨熊上车编队配置（旧调用；等价 fnId="bear"）。 */
    public static JSONObject joinFormation(Context ctx, String fpid, long sid, int joinId) {
        return joinFormation(ctx, fpid, sid, "bear", joinId);
    }

    /** 读某功能上车编队配置（join_formations 数组元素；缺失返回空对象）。 */
    public static JSONObject joinFormation(Context ctx, String fpid, long sid, String fnId,
                                           int joinId) {
        try {
            Object v = FnParams.params(ctx, fpid, sid, normalizeFnId(fnId)).opt("join_formations");
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

    /** 写巨熊上车编队（旧调用；默认 enabled=true）。 */
    public static void saveJoinFormation(Context ctx, String fpid, long sid, int joinId,
                                         String mode, long formationId) {
        saveJoinFormation(ctx, fpid, sid, "bear", joinId, mode, formationId, true);
    }

    /** 写巨熊上车编队（旧调用；显式 enabled）。 */
    public static void saveJoinFormation(Context ctx, String fpid, long sid, int joinId,
                                         String mode, long formationId, boolean enabled) {
        saveJoinFormation(ctx, fpid, sid, "bear", joinId, mode, formationId, enabled);
    }

    /**
     * 写某功能上车编队：自动/指定（指定时给 formation_id）+ 启用开关；幂等合并 id 1-6 元素。
     * disabled 的槽位仍保留配置（只写 enabled=false），重新启用不丢编队选择。
     */
    public static void saveJoinFormation(Context ctx, String fpid, long sid, String fnId,
                                         int joinId, String mode, long formationId,
                                         boolean enabled) {
        if (joinId < 1 || joinId > MAX_JOIN_SLOTS) return;
        try {
            String id = normalizeFnId(fnId);
            JSONObject p = FnParams.params(ctx, fpid, sid, id);
            JSONArray arr = p.optJSONArray("join_formations");
            if (arr == null) arr = new JSONArray();
            JSONArray next = new JSONArray();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("id", 0) == joinId) {
                    next.put(joinElement(joinId, mode, formationId, enabled));
                    found = true;
                } else {
                    next.put(o);
                }
            }
            if (!found) next.put(joinElement(joinId, mode, formationId, enabled));
            p.put("join_formations", next);
            FnParams.saveParams(ctx, fpid, sid, id, p);
        } catch (Throwable ignored) {
        }
    }

    /** 只切换上车槽启用状态（旧调用；fnId="bear"）。 */
    public static void saveJoinEnabled(Context ctx, String fpid, long sid, int joinId,
                                       boolean enabled) {
        saveJoinEnabled(ctx, fpid, sid, "bear", joinId, enabled);
    }

    /** 只切换某功能上车槽启用状态（保留现有 mode/formation_id）。 */
    public static void saveJoinEnabled(Context ctx, String fpid, long sid, String fnId,
                                       int joinId, boolean enabled) {
        JSONObject cur = joinFormation(ctx, fpid, sid, fnId, joinId);
        String mode = cur.optString("mode",
                cur.has("formation_id") ? MODE_ROLE : MODE_AUTOMATIC);
        long fid = cur.optLong("formation_id", 0);
        saveJoinFormation(ctx, fpid, sid, fnId, joinId, mode, fid, enabled);
    }

    // ==================== 出征队列数（queue_count，1-6） ====================

    /** 上限：游戏出征队列最多 6 支（与网页版 1-6 槽一致）。 */
    public static final int MAX_JOIN_SLOTS = 6;

    /** 兼容空 fnId（老调用）→ bear。 */
    static String normalizeFnId(String fnId) {
        return fnId == null || fnId.trim().isEmpty() ? "bear" : fnId.trim();
    }

    /**
     * 该角色出征队列数（1-6）：{@code queue_count} 显式配置优先；未配置时按目录里游戏
     * 编队槽数量推断（通常 5），仍无 → 5（多数账号的默认队列数）。
     */
    public static int fnQueueCount(Context ctx, String fpid, long sid, String fnId) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, normalizeFnId(fnId));
            int v = p.optInt("queue_count", 0);
            if (v > 0) return clampInt(v, 1, MAX_JOIN_SLOTS);
            int slots = 0;
            try {
                slots = catalogOf(ctx, fpid, sid).gameFormations().size();
            } catch (Throwable ignored) {
            }
            if (slots > 0) return clampInt(slots, 1, MAX_JOIN_SLOTS);
        } catch (Throwable ignored) {
        }
        return 5;
    }

    /**
     * 写该功能出征队列数：同时把超出 N 的上车槽置为停用（保留其编队选择），
     * 保证「队列数=3」时服务器不会再上第 4-6 队。
     */
    public static void saveFnQueueCount(Context ctx, String fpid, long sid, String fnId, int count) {
        try {
            String id = normalizeFnId(fnId);
            int n = clampInt(count, 1, MAX_JOIN_SLOTS);
            JSONObject p = FnParams.params(ctx, fpid, sid, id);
            p.put("queue_count", n);
            // 上车队数跟随队列数：巨兽用 join_limit（每日手工上车上限），巨熊用 join_count（本场上限）
            if ("behemoth".equals(id)) p.put("join_limit", n);
            else if ("bear".equals(id)) p.put("join_count", n);
            FnParams.saveParams(ctx, fpid, sid, id, p);
            for (int j = n + 1; j <= MAX_JOIN_SLOTS; j++) {
                JSONObject cur = joinFormation(ctx, fpid, sid, id, j);
                if (cur.length() == 0 || !joinEnabled(cur)) continue;
                saveJoinEnabled(ctx, fpid, sid, id, j, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 上车编队元素（契约：id 1-6 / mode / formation_id / enabled；AUTOMATIC 不带 formation_id）。 */
    static JSONObject joinElement(int joinId, String mode, long formationId) {
        return joinElement(joinId, mode, formationId, true);
    }

    static JSONObject joinElement(int joinId, String mode, long formationId, boolean enabled) {
        JSONObject o = new JSONObject();
        try {
            String m = normalizeMode(mode);
            o.put("id", joinId);
            o.put("mode", m);
            if (MODE_ROLE.equals(m) && formationId > 0) o.put("formation_id", formationId);
            o.put("enabled", enabled);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ==================== 巨熊编队模板（复用预设，2026-11-06） ====================

    /** 巨熊编队模板是否已应用：bear 发起编队指向预设「打熊队」。 */
    public static boolean bearPresetApplied(Context ctx, String fpid, long sid, String fnId) {
        try {
            JSONObject p = FnParams.params(ctx, fpid, sid, normalizeFnId(fnId));
            return MODE_ROLE.equals(p.optString("formation_mode", ""))
                    && p.optLong("formation_id", 0L) == PresetFormations.ID_BEAR_TEAM;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 纯函数：把「巨熊编队模板」写进 bear params（不依赖 Android，JVM 单测可直跑）。
     *
     * <p>写入内容：
     * <ul>
     *   <li>发起队：顶层 {@code formation_mode=ROLE} + {@code formation_id/formation_ids}=打熊队预设；</li>
     *   <li>上车队 1-6：{@code join_formations}=[{"id":1-6,"mode":"ROLE","formation_id":熊上车N,"enabled":true}]；</li>
     *   <li>{@code join_count}=出征队列数（显式 queue_count 优先保留，否则跟随推断值）；</li>
     *   <li>清 {@code soldiers}（各队用自己的兵量）与旧版 {@code launch_formation/template_state}。</li>
     * </ul>
     * 就地修改并返回同一对象。
     */
    public static JSONObject applyBearPresetParams(JSONObject params, int queueCount) {
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            FormationBinding.applyIds(p, new long[]{PresetFormations.ID_BEAR_TEAM});
            JSONArray joins = new JSONArray();
            for (int slot = 1; slot <= PresetFormations.BEAR_TEMPLATE_JOIN_COUNT; slot++) {
                JSONObject slotCfg = new JSONObject();
                slotCfg.put("id", slot);
                slotCfg.put("mode", MODE_ROLE);
                slotCfg.put("formation_id", PresetFormations.bearTemplateJoinId(slot));
                slotCfg.put("enabled", true);
                joins.put(slotCfg);
            }
            p.put("join_formations", joins);
            int n = clampInt(queueCount, 1, MAX_JOIN_SLOTS);
            p.put("join_count", n);
            if (!p.has("queue_count")) p.put("queue_count", n);
            p.remove("soldiers");
            p.remove("launch_formation");
            p.remove("template_state");
        } catch (Throwable ignored) {
        }
        return p;
    }

    /**
     * 一键应用「巨熊编队模板」= 预设「打熊队」（发起）+「熊上车1-6」（上车）。
     *
     * <p>与旧版 30 套模板（已删除）的区别：<b>不生成新编队</b>，直接把 bear 功能参数
     * 指向预设 id（预设随角色最强英雄/容量实时刷新）；发起编队写<b>顶层</b>
     * {@code formation_mode/formation_id/formation_ids}，服务器
     * {@code FormationResolver.isConfigured} 只认顶层键。
     *
     * <p>同时：6 个上车槽写 ROLE 引用（1-6 = 熊上车1-6，enabled=true）；{@code join_count}
     * 跟随该角色出征队列数（服务器本场上车上限），未知时用 1-6 的推断值；清掉功能级
     * {@code soldiers} 覆盖（各队用自己预设的兵量），移除旧版嵌套 {@code launch_formation}。
     *
     * @return 成功返回可读摘要；编队库/预设未就绪（等待英雄数据同步）返回 null
     */
    public static String applyBearPresetTemplate(Context ctx, String fpid, long sid, String uid) {
        if (ctx == null || fpid == null || fpid.isEmpty() || sid <= 0) return null;
        try {
            // 幂等懒建 19 套预设（目录未就绪时不建，下一步 findById 会返回 null）
            ensurePresets(ctx, fpid, sid, uid);
            List<FormationModel> lib = formations(ctx, fpid, sid);
            if (FormationRules.findById(lib, PresetFormations.ID_BEAR_TEAM) == null) return null;
            for (int slot = 1; slot <= PresetFormations.BEAR_TEMPLATE_JOIN_COUNT; slot++) {
                if (FormationRules.findById(lib, PresetFormations.bearTemplateJoinId(slot)) == null) {
                    return null;
                }
            }

            JSONObject p = FnParams.params(ctx, fpid, sid, "bear");
            // 本场上车上限=角色出征队列数（未配置 queue_count 时按目录游戏编队槽数推断）
            int queueCount = fnQueueCount(ctx, fpid, sid, "bear");
            applyBearPresetParams(p, queueCount);
            FnParams.saveParams(ctx, fpid, sid, "bear", p);
            return "已应用巨熊编队模板：打熊队 + 熊上车1-"
                    + PresetFormations.BEAR_TEMPLATE_JOIN_COUNT
                    + "（本场上车 " + clampInt(queueCount, 1, MAX_JOIN_SLOTS) + " 队）";
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 游戏编队副本 / 一键编队（契约外补充 API） ====================

    /**
     * 复制游戏编队为自定义（契约 §2.5 / 网页 R6）：名称 + 「（副本）」、id 新分配、source=CUSTOM。
     *
     * @param gameId 游戏编队 id（负数，如 -1；也接受正数 sourceId）
     * @return 保存后的自定义编队；不存在返回 null
     */
    public static FormationModel copyGameFormation(Context ctx, String fpid, long sid, long gameId) {
        return copyGameFormation(ctx, fpid, sid, "", gameId);
    }

    /** 复制游戏编队为自定义（v2，uid 感知）：游戏编队镜像按 uid 键目录读取（多角色不串）。 */
    public static FormationModel copyGameFormation(Context ctx, String fpid, long sid, String uid,
                                                   long gameId) {
        List<FormationModel> games = catalogOf(ctx, fpid, sid, uid).gameFormations();
        FormationModel src = null;
        long want = gameId < 0 ? gameId : -gameId;
        for (FormationModel g : games) {
            if (g.id == want || g.id == -Math.abs(gameId)) {
                src = g;
                break;
            }
        }
        if (src == null) return null;
        FormationModel copy = src.copy();
        copy.id = 0;
        copy.source = FormationModel.SOURCE_CUSTOM;
        copy.builtIn = false;
        copy.templateKey = null;
        copy.name = FormationModel.truncateName(src.name + "（副本）");
        copy.version = 1;
        return saveFormation(ctx, fpid, sid, copy);
    }

    // ==================== 模型兼容桥（Formation ⇄ FormationModel） ====================

    /** 旧模型 → v2 模型：比例模式映射为 PERCENTAGE；兵量取 soldiers 或 total。 */
    public static FormationModel toModel(Formation f) {
        if (f == null) return null;
        FormationModel m = new FormationModel();
        m.id = f.id;
        m.name = f.name == null ? "" : f.name;
        m.source = m.id < 0 ? FormationModel.SOURCE_GAME : FormationModel.SOURCE_CUSTOM;
        m.heroIds.addAll(f.heroIds);
        m.infantry = Math.max(0, f.infantry);
        m.lancer = Math.max(0, f.lancer);
        m.marksman = Math.max(0, f.marksman);
        m.infantryRatio = Math.max(0, f.ratioInfantry);
        m.lancerRatio = Math.max(0, f.ratioLancer);
        m.marksmanRatio = Math.max(0, f.ratioMarksman);
        m.totalSoldiers = m.soldiersTotal() > 0 ? m.soldiersTotal() : Math.max(0, f.total);
        m.allocationMode = f.ratioMode ? FormationModel.MODE_PERCENTAGE
                : FormationModel.MODE_MANUAL;
        // 旧 ratioMode 且兵量为 0：比例模式按容量换算，total 0=按容量
        if (f.ratioMode) {
            m.infantry = 0;
            m.lancer = 0;
            m.marksman = 0;
        }
        if (!m.heroIds.isEmpty()) m.captainHeroId = m.heroIds.get(0);
        return m;
    }

    /** v2 模型 → 旧模型；id 超出 int 范围（内置大 id）返回 null。 */
    public static Formation fromModel(FormationModel m) {
        if (m == null || m.id == 0) return null;
        if (m.id > Integer.MAX_VALUE || m.id < Integer.MIN_VALUE) return null;
        Formation f = new Formation();
        f.id = (int) m.id;
        f.name = m.name == null ? "" : m.name;
        f.heroIds.addAll(m.heroIds);
        f.infantry = Math.max(0, m.infantry);
        f.lancer = Math.max(0, m.lancer);
        f.marksman = Math.max(0, m.marksman);
        f.ratioInfantry = Math.max(0, m.infantryRatio);
        f.ratioLancer = Math.max(0, m.lancerRatio);
        f.ratioMarksman = Math.max(0, m.marksmanRatio);
        f.total = Math.max(0, m.soldiersTotal() > 0 ? m.soldiersTotal() : m.totalSoldiers);
        f.ratioMode = m.isPercentage();
        return f;
    }

    // ==================== 内部 ====================

    /** 读库根 JSON（恒定非 null）：兼容旧格式（裸数组/仅 formations）。 */
    private static JSONObject readRoot(Context ctx, String fpid, long sid) {
        JSONObject root = new JSONObject();
        try {
            String raw = prefs(ctx).getString(prefKey(fpid, sid), null);
            if (raw != null && !raw.trim().isEmpty()) {
                String text = raw.trim();
                if (text.startsWith("[")) {
                    root.put("formations", new JSONArray(text));
                } else {
                    root = new JSONObject(text);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (root.optJSONArray("formations") == null) root.put("formations", new JSONArray());
        } catch (Throwable ignored) {
        }
        return root;
    }

    /** 读 v2 编队列表（宽容；0/非法条目丢弃）。 */
    private static List<FormationModel> readModels(Context ctx, String fpid, long sid) {
        try {
            return FormationModel.parseArray(readRoot(ctx, fpid, sid).optJSONArray("formations"));
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * 写 v2 编队列表（保留 automatic_settings；{@code auto} 非空则一并覆盖）。
     * 不做数量上限（网页未设上限，契约 §0）。
     */
    private static void writeModels(Context ctx, String fpid, long sid,
                                    List<FormationModel> models, int[] auto) {
        if (ctx == null) return;
        try {
            JSONObject root = readRoot(ctx, fpid, sid);
            root.put("version", 2);
            JSONArray arr = new JSONArray();
            if (models != null) {
                List<FormationModel> sorted = new ArrayList<>();
                for (FormationModel m : models) {
                    if (m != null && m.id != 0) sorted.add(m);
                }
                sorted.sort((x, y) -> Long.compare(x.id, y.id));
                for (FormationModel m : sorted) arr.put(m.toJson(false));
            }
            root.put("formations", arr);
            if (auto != null) {
                JSONObject as = new JSONObject();
                as.put("infantry_ratio", clampPct(auto[0]));
                as.put("lancer_ratio", clampPct(auto[1]));
                as.put("marksman_ratio", clampPct(auto[2]));
                root.put("automatic_settings", as);
            } else if (root.optJSONObject("automatic_settings") == null) {
                JSONObject as = new JSONObject();
                as.put("infantry_ratio", 50);
                as.put("lancer_ratio", 20);
                as.put("marksman_ratio", 30);
                root.put("automatic_settings", as);
            }
            prefs(ctx).edit().putString(prefKey(fpid, sid), root.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 库内容指纹（用于懒创建后判断是否需要落盘，避免无变化写 prefs）。 */
    private static String fingerprint(List<FormationModel> lib) {
        StringBuilder sb = new StringBuilder();
        if (lib != null) {
            for (FormationModel m : lib) {
                if (m == null) continue;
                sb.append(m.id).append('|').append(m.name).append('|')
                        .append(m.heroIds).append('|').append(m.captainHeroId).append('|')
                        .append(m.infantry).append('/').append(m.lancer).append('/')
                        .append(m.marksman).append('\n');
            }
        }
        return sb.toString();
    }

    /** 下一个自定义编队 id：避开预设大 id、从 1 起、跳过已占用。 */
    private static long nextFormationId(List<FormationModel> lib) {
        long max = 0;
        Set<Long> used = new LinkedHashSet<>();
        if (lib != null) {
            for (FormationModel m : lib) {
                if (m == null || m.id <= 0) continue;
                used.add(m.id);
                if (m.id < PresetFormations.ID_FIRST && m.id > max) max = m.id;
            }
        }
        long next = max + 1;
        while (used.contains(next) || PresetFormations.isPreset(next)) next++;
        return next;
    }

    private static int clampPct(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 宽容取整（多别名；数字/数字字符串/浮点串均可；全缺失返回 0）。 */
    private static int firstInt(JSONObject o, String... keys) {
        if (o == null) return 0;
        for (String k : keys) {
            if (k == null || !o.has(k)) continue;
            Object v = o.opt(k);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try {
                    return (int) Math.round(Double.parseDouble(((String) v).trim()));
                } catch (Throwable ignored) {
                }
            }
        }
        return 0;
    }

    /** 宽容取长整数（多别名；数字/数字字符串/浮点串均可；全缺失返回 0）。 */
    private static long firstLong(JSONObject o, String... keys) {
        if (o == null) return 0L;
        for (String k : keys) {
            if (k == null || !o.has(k)) continue;
            Object v = o.opt(k);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) {
                try {
                    return Math.round(Double.parseDouble(((String) v).trim()));
                } catch (Throwable ignored) {
                }
            }
        }
        return 0L;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
