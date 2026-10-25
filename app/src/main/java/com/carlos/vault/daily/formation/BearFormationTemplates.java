package com.carlos.vault.daily.formation;

import com.carlos.vault.daily.FormationStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 巨熊编队模板（30 套 / 14 代，契约 §2.6、网页规格 §2.4/§4.8）：模板数据 + 一键生成 7 支编队。
 *
 * <p>数据来源：线上 {@code GET /api/daily/roles/{r}/formations/bear-templates} 实测 30 套
 * （大氪11 / 微氪5 / 零氪11 / 前瞻3；id 规则 {@code g{01..14}-{spender|mid|free|preview}}）。
 * 运行态优先读 assets {@code formation/bear_templates.json}（T6 提供原样 JSON），
 * 缺失时回落到本类内置常量（两份数据由单测逐套对齐校验）。
 *
 * <p>生成规则（网页 B1）：1 支开车队 + 6 支上车队，比例 盾 5% / 矛 5% / 弓 90%，
 * 命名 {@code 巨熊·开车队（{title}）} / {@code 巨熊·上车队{n}（{title}）}，兵量 0
 * （运行时按比例与实时容量换算）；客户端只按名字前缀 {@code 巨熊·} 识别巨熊编队。
 *
 * <p>纯逻辑（无 Android 依赖），JVM 单测可直跑。
 */
public final class BearFormationTemplates {

    /** 上车队数量（网页 JOIN_QUEUE_COUNT=6）。 */
    public static final int JOIN_COUNT = 6;
    /** 模板生成编队比例：盾 5% / 矛 5% / 弓 90%。 */
    public static final int RATIO_INFANTRY = 5;
    public static final int RATIO_LANCER = 5;
    public static final int RATIO_MARKSMAN = 90;
    /** 巨熊编队名称前缀（客户端唯一识别口径）。 */
    public static final String NAME_PREFIX = "巨熊·";
    /** assets 内模板数据路径（T6 提供，原样 30 套 JSON 数组）。 */
    public static final String ASSET_PATH = "formation/bear_templates.json";
    public static final String LAUNCH_NAME_FMT = NAME_PREFIX + "开车队（%s）";
    public static final String JOIN_NAME_FMT = NAME_PREFIX + "上车队%d（%s）";

    /** 模板英雄引用（含展示用名字与中文职业）。 */
    public static final class HeroRef {
        public long id;
        public String name = "";
        /** 中文职业：盾兵/矛兵/射手。 */
        public String profession = "";
        /** 1 盾 / 2 矛 / 3 弓 / 0 未知。 */
        public int professionType;

        public HeroRef() {
        }

        HeroRef(long id, String name, String profession) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.profession = profession == null ? "" : profession;
            this.professionType = FormationRules.professionTypeOf(this.profession);
        }
    }

    /** 一套巨熊模板。 */
    public static final class Template {
        public String id = "";
        public int generation;
        public String tier = "";
        /** 展示标题，如 {@code 一代·大氪}（生成编队名用）。 */
        public String title = "";
        /** 开车队英雄（1..3，首名=队长高亮，前端不使用）。 */
        public final List<HeroRef> launchHeroes = new ArrayList<HeroRef>();
        /** 上车队 1..6 英雄（固定 6 组，每组 1..3）。 */
        public final List<List<HeroRef>> joinHeroes = new ArrayList<List<HeroRef>>();

        public List<Long> launchHeroIds() {
            return idsOf(launchHeroes);
        }

        /** 上车队 n（1..6）英雄 id；越界返回空列表。 */
        public List<Long> joinHeroIds(int n) {
            if (n < 1 || n > joinHeroes.size()) return new ArrayList<Long>();
            return idsOf(joinHeroes.get(n - 1));
        }

        /** 模板全部英雄 id（去重）。 */
        public List<Long> allHeroIds() {
            Set<Long> uniq = new HashSet<Long>();
            List<Long> out = new ArrayList<Long>();
            for (HeroRef h : launchHeroes) {
                if (h.id > 0 && uniq.add(h.id)) out.add(h.id);
            }
            for (List<HeroRef> group : joinHeroes) {
                for (HeroRef h : group) {
                    if (h.id > 0 && uniq.add(h.id)) out.add(h.id);
                }
            }
            return out;
        }

        /** 开车队编队名：{@code 巨熊·开车队（一代·大氪）}。 */
        public String launchName() {
            return String.format(Locale.ROOT, LAUNCH_NAME_FMT, title);
        }

        /** 上车队 n 编队名：{@code 巨熊·上车队1（一代·大氪）}。 */
        public String joinName(int n) {
            return String.format(Locale.ROOT, JOIN_NAME_FMT, n, title);
        }

        public int heroCount() {
            return allHeroIds().size();
        }
    }

    private BearFormationTemplates() {
    }

    // ==================== 模板数据 ====================

    /** 30 套内置模板（懒解析一次，线程安全只读）。 */
    private static volatile List<Template> cached;

    /** 全部 30 套模板（内置常量；assets 缺失/损坏时使用）。 */
    public static List<Template> all() {
        List<Template> local = cached;
        if (local == null) {
            synchronized (BearFormationTemplates.class) {
                local = cached;
                if (local == null) {
                    local = parseCompact();
                    cached = local;
                }
            }
        }
        return local;
    }

    /** 按 id（如 {@code g01-spender}）取模板；不存在返回 null。 */
    public static Template byId(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String key = id.trim();
        for (Template t : all()) {
            if (key.equals(t.id)) return t;
        }
        return null;
    }

    /**
     * 解析线上模板 JSON（数组，30 套；容忍缺字段/脏条目）。
     *
     * <p>期望形状：{@code [{"id":"g01-spender","generation":1,"tier":"大氪","title":"一代·大氪",
     * "launchHeroes":[{"id":50011,"name":"赫罗尼莫","profession":"盾兵"}],
     * "joinHeroes":[[...],...×6],"applicable":false,"missingHeroes":[...]}]}。
     */
    public static List<Template> parse(String rawJson) {
        List<Template> out = new ArrayList<Template>();
        if (rawJson == null || rawJson.trim().isEmpty()) return out;
        try {
            String text = rawJson.trim();
            JSONArray arr;
            if (text.startsWith("[")) {
                arr = new JSONArray(text);
            } else {
                JSONObject root = new JSONObject(text);
                arr = root.optJSONArray("templates");
                if (arr == null) arr = root.optJSONArray("bearTemplates");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Template t = parseOne(o);
                if (t != null) out.add(t);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 解析单套模板（坏数据返回 null）。 */
    static Template parseOne(JSONObject o) {
        if (o == null) return null;
        Template t = new Template();
        t.id = o.optString("id", "").trim();
        if (t.id.isEmpty()) return null;
        t.generation = o.optInt("generation", 0);
        t.tier = o.optString("tier", "");
        t.title = o.optString("title", "");
        if (t.title.isEmpty()) t.title = t.id;
        readHeroes(o.optJSONArray("launchHeroes"), t.launchHeroes);
        JSONArray joins = o.optJSONArray("joinHeroes");
        if (joins != null) {
            for (int i = 0; i < joins.length() && i < JOIN_COUNT; i++) {
                List<HeroRef> group = new ArrayList<HeroRef>();
                readHeroes(joins.optJSONArray(i), group);
                t.joinHeroes.add(group);
            }
        }
        while (t.joinHeroes.size() < JOIN_COUNT) {
            t.joinHeroes.add(new ArrayList<HeroRef>());
        }
        return t;
    }

    private static List<Long> idsOf(List<HeroRef> refs) {
        List<Long> out = new ArrayList<Long>();
        if (refs == null) return out;
        for (HeroRef h : refs) {
            if (h != null && h.id > 0) out.add(h.id);
        }
        return out;
    }

    private static void readHeroes(JSONArray arr, List<HeroRef> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id", 0);
            if (id <= 0) continue;
            String name = FormationModel.cleanName(o.optString("name", ""));
            String profession = o.optString("profession",
                    o.optString("professionName", ""));
            out.add(new HeroRef(id, name, profession));
        }
    }

    // ==================== 缺英雄 / 可用性（网页 §1.11 / S2） ====================

    /** 模板缺的英雄（只要求 owned，不要求在城）；按模板顺序返回。 */
    public static List<Long> missingHeroIds(Template t, Collection<Long> ownedHeroIds) {
        List<Long> out = new ArrayList<Long>();
        if (t == null) return out;
        Set<Long> owned = ownedHeroIds == null ? new HashSet<Long>()
                : new HashSet<Long>(ownedHeroIds);
        for (Long id : t.allHeroIds()) {
            if (!owned.contains(id)) out.add(id);
        }
        return out;
    }

    /** 缺英雄数量（UI {@code 缺 N 个英雄}）。 */
    public static int missingCount(Template t, Collection<Long> ownedHeroIds) {
        return missingHeroIds(t, ownedHeroIds).size();
    }

    /** 是否可应用（模板全部英雄均在角色英雄库，不要求在城）。 */
    public static boolean applicable(Template t, Collection<Long> ownedHeroIds) {
        return t != null && missingHeroIds(t, ownedHeroIds).isEmpty();
    }

    /** 模板英雄名串（缺英雄提示用：{@code 赫罗尼莫、茉莉}）。 */
    public static String heroNames(List<Long> heroIds, Template t) {
        if (heroIds == null || heroIds.isEmpty()) return "";
        Map<Long, String> names = new LinkedHashMap<Long, String>();
        if (t != null) {
            for (HeroRef h : t.launchHeroes) names.put(h.id, h.name);
            for (List<HeroRef> group : t.joinHeroes) {
                for (HeroRef h : group) names.put(h.id, h.name);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : heroIds) {
            if (sb.length() > 0) sb.append('、');
            String n = names.get(id);
            sb.append(n == null || n.isEmpty() ? String.valueOf(id) : n);
        }
        return sb.toString();
    }

    /** 编队名是否巨熊编队（客户端唯一识别口径）。 */
    public static boolean isBearName(String name) {
        return name != null && name.startsWith(NAME_PREFIX);
    }

    /** 编队是否巨熊编队。 */
    public static boolean isBear(FormationModel f) {
        return f != null && isBearName(f.name);
    }

    /** 开车队编队名（静态便捷）。 */
    public static String launchName(Template t) {
        return t == null ? "" : t.launchName();
    }

    /** 上车队 n 编队名（静态便捷）。 */
    public static String joinName(Template t, int n) {
        return t == null ? "" : t.joinName(n);
    }

    // ==================== 生成 7 支编队 ====================

    /** 生成 1 开车队 + 6 上车队（无目录：可用性未知，按全部可用处理）。 */
    public static List<FormationModel> buildFormations(Template t) {
        return buildFormations(t, null);
    }

    /**
     * 生成 1 开车队 + 6 上车队（比例 5/5/90，兵量 0，id=0 待保存）。
     *
     * @param heroes 目录英雄（用于标记在城/未归队；null 或空=不判定，按全部可用）
     */
    public static List<FormationModel> buildFormations(Template t, List<FormationStore.Hero> heroes) {
        List<FormationModel> out = new ArrayList<FormationModel>();
        if (t == null) return out;
        Map<Long, FormationStore.Hero> byId = new LinkedHashMap<Long, FormationStore.Hero>();
        if (heroes != null) {
            for (FormationStore.Hero h : heroes) {
                if (h != null) byId.put(h.id, h);
            }
        }
        boolean judge = !byId.isEmpty();
        out.add(buildOne(t.launchName(), t.launchHeroIds(), byId, judge));
        for (int n = 1; n <= JOIN_COUNT; n++) {
            out.add(buildOne(t.joinName(n), t.joinHeroIds(n), byId, judge));
        }
        return out;
    }

    private static FormationModel buildOne(String name, List<Long> heroIds,
                                           Map<Long, FormationStore.Hero> byId, boolean judge) {
        FormationModel f = new FormationModel();
        f.id = 0;
        f.name = name;
        f.source = FormationModel.SOURCE_CUSTOM;
        f.builtIn = false;
        f.templateKey = null;
        f.allocationMode = FormationModel.MODE_PERCENTAGE;
        f.infantryRatio = RATIO_INFANTRY;
        f.lancerRatio = RATIO_LANCER;
        f.marksmanRatio = RATIO_MARKSMAN;
        f.infantry = 0;
        f.lancer = 0;
        f.marksman = 0;
        f.totalSoldiers = 0;
        f.version = 1;
        f.updatedAt = System.currentTimeMillis();
        for (Long id : heroIds) {
            if (id != null && id > 0 && f.heroIds.size() < FormationModel.HERO_MAX) {
                f.heroIds.add(id);
            }
        }
        if (!f.heroIds.isEmpty()) f.captainHeroId = f.heroIds.get(0);
        f.allHeroesAvailable = true;
        if (judge) {
            for (Long id : f.heroIds) {
                FormationStore.Hero h = byId.get(id);
                if (h == null || !h.available) f.unavailableHeroIds.add(id);
            }
            f.allHeroesAvailable = f.unavailableHeroIds.isEmpty();
        }
        return f;
    }

    // ==================== 紧凑常量（与 assets JSON 逐套对齐，由单测校验） ====================

    /**
     * 每行一套：{@code id|generation|tier|title|开车队|上车1;…;上车6}；
     * 英雄组内为 {@code id:名字:中文职业}，逗号分隔。
     */
    private static List<Template> parseCompact() {
        List<Template> out = new ArrayList<Template>();
        for (String line : RAW) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 6) continue;
            Template t = new Template();
            t.id = parts[0];
            t.generation = parseInt(parts[1]);
            t.tier = parts[2];
            t.title = parts[3];
            readCompactGroup(parts[4], t.launchHeroes);
            String[] joins = parts[5].split(";", -1);
            for (int i = 0; i < JOIN_COUNT; i++) {
                List<HeroRef> group = new ArrayList<HeroRef>();
                if (i < joins.length) readCompactGroup(joins[i], group);
                t.joinHeroes.add(group);
            }
            out.add(t);
        }
        return out;
    }

    private static void readCompactGroup(String text, List<HeroRef> out) {
        if (text == null || text.trim().isEmpty()) return;
        for (String item : text.split(",", -1)) {
            if (item.trim().isEmpty()) continue;
            String[] p = item.split(":", -1);
            if (p.length < 3) continue;
            long id = parseLong(p[0]);
            if (id > 0) out.add(new HeroRef(id, p[1], p[2]));
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 内置常量（从 /tmp/livedata/bear_templates.json 转录，30 套；单测对照 assets 校验）。 */
    private static final String[] RAW = {
        "g01-spender|1|大氪|一代·大氪|50011:赫罗尼莫:盾兵,50012:茉莉:矛兵,50006:巴希提:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50005:帕特里克:矛兵;50009:娜塔莉亚:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50004:谢尔盖:盾兵,50037:凌霜:矛兵;50008:吉娜:射手",
        "g01-mid|1|微氪|一代·微氪|50009:娜塔莉亚:盾兵,50012:茉莉:矛兵,50006:巴希提:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50005:帕特里克:矛兵;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50004:谢尔盖:盾兵,50037:凌霜:矛兵;50008:吉娜:射手",
        "g01-free|1|零氪|一代·零氪|50004:谢尔盖:盾兵,50012:茉莉:矛兵,50006:巴希提:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50005:帕特里克:矛兵;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50009:娜塔莉亚:盾兵,50037:凌霜:矛兵;50008:吉娜:射手",
        "g02-spender|2|大氪|二代·大氪|50011:赫罗尼莫:盾兵,50013:菲兰德:矛兵,50018:阿隆索:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50005:帕特里克:矛兵;50021:弗林特:盾兵,50029:卢姆·波根:矛兵,50008:吉娜:射手;50009:娜塔莉亚:盾兵,50037:凌霜:矛兵,50017:津曼:射手;50004:谢尔盖:盾兵,50012:茉莉:矛兵,50006:巴希提:射手",
        "g02-mid|2|微氪|二代·微氪|50009:娜塔莉亚:盾兵,50013:菲兰德:矛兵,50018:阿隆索:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50005:帕特里克:矛兵;50021:弗林特:盾兵,50029:卢姆·波根:矛兵,50008:吉娜:射手;50011:赫罗尼莫:盾兵,50037:凌霜:矛兵,50017:津曼:射手;50004:谢尔盖:盾兵,50012:茉莉:矛兵,50006:巴希提:射手",
        "g02-free|2|零氪|二代·零氪|50021:弗林特:盾兵,50012:茉莉:矛兵,50006:巴希提:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50018:阿隆索:射手,50029:卢姆·波根:矛兵;50009:娜塔莉亚:盾兵,50037:凌霜:矛兵,50008:吉娜:射手;50011:赫罗尼莫:盾兵,50013:菲兰德:矛兵,50017:津曼:射手",
        "g03-spender|3|大氪|三代·大氪|50009:娜塔莉亚:盾兵,50016:米娅:矛兵,50018:阿隆索:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50008:吉娜:射手;50021:弗林特:盾兵,50037:凌霜:矛兵,50017:津曼:射手;50020:格雷格:射手,50010:罗根:盾兵,50013:菲兰德:矛兵",
        "g03-mid|3|微氪|三代·微氪|50011:赫罗尼莫:盾兵,50013:菲兰德:矛兵,50018:阿隆索:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50016:米娅:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50021:弗林特:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50020:格雷格:射手,50010:罗根:盾兵,50037:凌霜:矛兵",
        "g03-free|3|零氪|三代·零氪|50021:弗林特:盾兵,50016:米娅:矛兵,50006:巴希提:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50008:吉娜:射手;50009:娜塔莉亚:盾兵,50037:凌霜:矛兵,50017:津曼:射手;50020:格雷格:射手,50010:罗根:盾兵,50013:菲兰德:矛兵",
        "g04-spender|4|大氪|四代·大氪|50011:赫罗尼莫:盾兵,50014:玲奈:矛兵,50018:阿隆索:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50016:米娅:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50022:琳恩:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50021:弗林特:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g04-mid|4|微氪|四代·微氪|50009:娜塔莉亚:盾兵,50016:米娅:矛兵,50022:琳恩:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50010:罗根:盾兵,50008:吉娜:射手;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50018:阿隆索:射手,50023:阿赫摩斯:盾兵,50037:凌霜:矛兵",
        "g04-free|4|零氪|四代·零氪|50021:弗林特:盾兵,50016:米娅:矛兵,50022:琳恩:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50020:格雷格:射手,50010:罗根:盾兵,50037:凌霜:矛兵",
        "g05-spender|5|大氪|五代·大氪|50011:赫罗尼莫:盾兵,50014:玲奈:矛兵,50026:格温:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50016:米娅:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50018:阿隆索:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50021:弗林特:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g05-mid|5|微氪|五代·微氪|50024:赫克托:盾兵,50016:米娅:矛兵,50026:格温:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50018:阿隆索:射手,50010:罗根:盾兵,50037:凌霜:矛兵",
        "g05-free|5|零氪|五代·零氪|50024:赫克托:盾兵,50016:米娅:矛兵,50022:琳恩:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50011:赫罗尼莫:盾兵,50029:卢姆·波根:矛兵,50017:津曼:射手;50026:格温:射手,50010:罗根:盾兵,50037:凌霜:矛兵",
        "g06-spender|6|大氪|六代·大氪|50011:赫罗尼莫:盾兵,50014:玲奈:矛兵,50031:韦恩:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50016:米娅:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50024:赫克托:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g06-free|6|零氪|六代·零氪|50024:赫克托:盾兵,50030:芮妮:矛兵,50022:琳恩:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50016:米娅:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50011:赫罗尼莫:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g07-spender|7|大氪|七代·大氪|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50033:布拉德利:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50032:艾迪丝:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g07-free|7|零氪|七代·零氪|50024:赫克托:盾兵,50016:米娅:矛兵,50033:布拉德利:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50015:哥顿:矛兵,50019:无名:盾兵,50017:津曼:射手",
        "g08-spender|8|大氪|八代·大氪|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50033:布拉德利:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50035:索尼娅:矛兵,50034:加托:盾兵,50017:津曼:射手",
        "g08-free|8|零氪|八代·零氪|50024:赫克托:盾兵,50016:米娅:矛兵,50033:布拉德利:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50036:亨德里克:射手,50034:加托:盾兵,50037:凌霜:矛兵",
        "g09-spender|9|大氪|九代·大氪|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50033:布拉德利:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50026:格温:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50038:马格努斯:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g09-free|9|零氪|九代·零氪|50024:赫克托:盾兵,50016:米娅:矛兵,50033:布拉德利:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50035:索尼娅:矛兵,50010:罗根:盾兵,50017:津曼:射手;50038:马格努斯:盾兵,50029:卢姆·波根:矛兵,50006:巴希提:射手",
        "g10-spender|10|大氪|十代·大氪|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50043:布兰琪:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50033:布拉德利:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50038:马格努斯:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g10-free|10|零氪|十代·零氪|50024:赫克托:盾兵,50016:米娅:矛兵,50043:布兰琪:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50033:布拉德利:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50041:格里高利:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g11-spender|11|大氪|十一代·大氪|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50046:鲁弗斯:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50043:布兰琪:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50038:马格努斯:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g11-free|11|零氪|十一代·零氪|50024:赫克托:盾兵,50016:米娅:矛兵,50046:鲁弗斯:射手|50007:杰西:矛兵,50000:尤金:盾兵,50003:克劳瑞斯:射手;50027:书允:射手,50001:史密斯:盾兵,50002:查理:矛兵;50028:杰塞尔:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50014:玲奈:矛兵,50009:娜塔莉亚:盾兵,50008:吉娜:射手;50043:布兰琪:射手,50010:罗根:盾兵,50029:卢姆·波根:矛兵;50041:格里高利:盾兵,50037:凌霜:矛兵,50017:津曼:射手",
        "g12-preview|12|前瞻|十二代·前瞻|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50046:鲁弗斯:射手|50007:杰西:矛兵,50047:赫尔薇尔:盾兵,50049:丽姬娅:射手;50027:书允:射手,50044:埃莱奥诺拉:盾兵,50048:加罗尔:矛兵;50028:杰塞尔:射手,50000:尤金:盾兵,50045:劳埃德:矛兵;50014:玲奈:矛兵,50001:史密斯:盾兵,50003:克劳瑞斯:射手;50043:布兰琪:射手,50004:谢尔盖:盾兵,50002:查理:矛兵;50041:格里高利:盾兵,50005:帕特里克:矛兵,50008:吉娜:射手",
        "g13-preview|13|前瞻|十三代·前瞻|50011:赫罗尼莫:盾兵,50016:米娅:矛兵,50046:鲁弗斯:射手|50007:杰西:矛兵,50050:吉塞拉:盾兵,50052:乌尔卡努斯:射手;50027:书允:射手,50047:赫尔薇尔:盾兵,50048:加罗尔:矛兵;50028:杰塞尔:射手,50000:尤金:盾兵,50002:查理:矛兵;50051:弗洛拉:矛兵,50001:史密斯:盾兵,50049:丽姬娅:射手;50043:布兰琪:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50038:马格努斯:盾兵,50029:卢姆·波根:矛兵,50003:克劳瑞斯:射手",
        "g14-preview|14|前瞻|十四代·前瞻（多米尼克版）|50011:赫罗尼莫:盾兵,50054:多米尼克:矛兵,50046:鲁弗斯:射手|50007:杰西:矛兵,50053:艾丽芙:盾兵,50055:卡拉:射手;50027:书允:射手,50050:吉塞拉:盾兵,50051:弗洛拉:矛兵;50028:杰塞尔:射手,50000:尤金:盾兵,50002:查理:矛兵;50014:玲奈:矛兵,50001:史密斯:盾兵,50052:乌尔卡努斯:射手;50043:布兰琪:射手,50004:谢尔盖:盾兵,50005:帕特里克:矛兵;50041:格里高利:盾兵,50029:卢姆·波根:矛兵,50003:克劳瑞斯:射手"
    };
}
