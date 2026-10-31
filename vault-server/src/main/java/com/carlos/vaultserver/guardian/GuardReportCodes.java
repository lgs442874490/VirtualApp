package com.carlos.vaultserver.guardian;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 防守战报码表（服务端版，1:1 对标 App {@code daily/guardian/GuardReportCodes}，批次 C 2026-10-31）。
 *
 * <p><b>口径（用户 20261017 裁决 + App 交接 §四）</b>：驻防成败以战报邮件为准；
 * 战斗邮件 {@code first_type=30}（守城级联/驻防参战）；{@code second_type} 4/5=胜、6/7/8=败；
 * 未收录码 → 不判成败（{@code null}），只记 {@code report_unknown} 待校准，**绝不触发撤防**。
 *
 * <p><b>铁律</b>：本类与调用方只做判定/记账，不改变 missions、不发出任何游戏协议请求；
 * 撤防只由敌情解除事件驱动（{@code handleThreatGone}），战报永不撤防。
 *
 * <p>覆盖通道：roleParams {@code guardian.report_codes}（JSON 字符串，App 策略项同款）：
 * <pre>{"roles":{"30":"战斗/防守战报"}, "second":{"1":"loss"}, "by_first":{"30":{"5":"unknown"}}}</pre>
 * 显式 {@code "unknown"} = 已校准未知（不判成败，记 {@code report_unknown} 但可标注“已校准”）。
 */
public final class GuardReportCodes {

    public static final String WIN = "win";
    public static final String LOSS = "loss";
    public static final String UNKNOWN = "unknown";

    /** 默认战斗邮件 first_type（P1 实证：守城级联 + 驻防参战战报均为 30）。 */
    public static final long DEF_FIRST_BATTLE = 30L;

    private GuardReportCodes() {
    }

    /** 战报映射：内置默认 + params 覆盖合并（纯逻辑，可单测）。 */
    public static final class Mapping {
        /** first_type → 角色标注（键集=按战报处理的邮件类型）。 */
        private final Map<Long, String> roles = new LinkedHashMap<>();
        /** second_type → verdict 默认表（进攻战报 4/5=胜 6/7/8=败；防守语义待校准）。 */
        private final Map<Long, String> second = new LinkedHashMap<>();
        /** first_type → (second_type → verdict) 覆盖表（最高优先级）。 */
        private final Map<Long, Map<Long, String>> byFirst = new LinkedHashMap<>();

        public Mapping() {
            roles.put(DEF_FIRST_BATTLE, "战斗/防守战报");
            second.put(4L, WIN);
            second.put(5L, WIN);
            second.put(6L, LOSS);
            second.put(7L, LOSS);
            second.put(8L, LOSS);
        }

        public boolean isBattleFirst(long firstType) {
            return roles.containsKey(firstType);
        }

        public String roleOf(long firstType) {
            String r = roles.get(firstType);
            return r == null ? "战报" : r;
        }

        /**
         * verdict：{@code by_first[first][second]} → {@code second[second]} → null（未收录）。
         * 返回 win/loss/unknown/null；unknown 为“已校准未知”（同样不判成败）。
         */
        public String verdictOf(long firstType, long secondType) {
            Map<Long, String> over = byFirst.get(firstType);
            if (over != null) {
                String v = over.get(secondType);
                if (v != null) return v;
            }
            return second.get(secondType);
        }

        public int roleCount() {
            return roles.size();
        }

        public int secondSize() {
            return second.size();
        }

        /** 解析覆盖 JSON（并集进默认表；空/非法 → 纯默认表，宁缺毋滥）。 */
        public static Mapping parse(String json) {
            Mapping m = new Mapping();
            if (json == null) return m;
            String s = json.trim();
            if (s.isEmpty()) return m;
            try {
                JSONObject o = new JSONObject(s);
                JSONObject roles = o.optJSONObject("roles");
                if (roles != null) {
                    Iterator<String> it = roles.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long ft = parseLong(k);
                        String v = roles.optString(k, "").trim();
                        if (ft > 0 && !v.isEmpty()) m.roles.put(ft, v);
                    }
                }
                JSONObject second = o.optJSONObject("second");
                if (second != null) {
                    Iterator<String> it = second.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long st = parseLong(k);
                        String v = normalize(second.optString(k, ""));
                        if (st >= 0 && v != null) m.second.put(st, v);
                    }
                }
                JSONObject byFirst = o.optJSONObject("by_first");
                if (byFirst != null) {
                    Iterator<String> it = byFirst.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long ft = parseLong(k);
                        JSONObject inner = byFirst.optJSONObject(k);
                        if (ft <= 0 || inner == null) continue;
                        Map<Long, String> map = m.byFirst.get(ft);
                        if (map == null) {
                            map = new LinkedHashMap<>();
                            m.byFirst.put(ft, map);
                        }
                        Iterator<String> it2 = inner.keys();
                        while (it2.hasNext()) {
                            String k2 = it2.next();
                            long st = parseLong(k2);
                            String v = normalize(inner.optString(k2, ""));
                            if (st >= 0 && v != null) map.put(st, v);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 非法覆盖：保留内置默认（错误配置不能把败判成胜）
            }
            return m;
        }

        private static long parseLong(String s) {
            try {
                return (long) Double.parseDouble(s);
            } catch (Throwable t) {
                return -1L;
            }
        }

        /** win/loss/unknown 之外一律 unknown（防手滑写错导致误判）。 */
        private static String normalize(String v) {
            String s = v == null ? "" : v.trim().toLowerCase(Locale.CHINA);
            if (s.isEmpty()) return null;
            return WIN.equals(s) || LOSS.equals(s) ? s : UNKNOWN;
        }
    }

    // ==================== 推送解析（纯函数；形状防御式） ====================

    /** 一条战斗邮件摘要（first_type/second_type/mailId/title；不携带任何玩家隐私）。 */
    public static final class ReportMail {
        public final long firstType;
        public final long secondType;
        public final long mailId;
        public final String title;

        public ReportMail(long firstType, long secondType, long mailId, String title) {
            this.firstType = firstType;
            this.secondType = secondType;
            this.mailId = mailId;
            this.title = title == null ? "" : title;
        }

        public boolean valid() {
            return firstType > 0L || secondType > 0L;
        }
    }

    private static final int MAX_DEPTH = 5;
    private static final int MAX_MAILS = 32;
    private static final int MAX_TITLE = 120;

    /**
     * 解析 {@code notify_new_mail_syn} 的嵌套形状（List/Map 兼容），字段口径与 WarNotify 一致：
     * {@code mail_cover_info:{first_type,second_type,...}}；兼容 {@code infolist[]/data/{}/content{} 包装}
     * 以及直接把 first_type/second_type 放邮件对象本体的形状。
     */
    public static List<ReportMail> parseMails(Map<String, Object> content) {
        List<ReportMail> raw = new ArrayList<>();
        collect(content, 0, raw);
        // 去重：cover 已被显式解析，递归扫描可能再次命中同一对象；按 mailId 或 内容+título 去重
        List<ReportMail> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ReportMail m : raw) {
            String key = m.mailId > 0L ? "id:" + m.mailId
                    : ("c:" + m.firstType + ":" + m.secondType + ":" + m.title);
            if (seen.add(key)) out.add(m);
        }
        return out;
    }

    private static void collect(Object node, int depth, List<ReportMail> out) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_MAILS) return;
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            Object cover = m.get("mail_cover_info");
            if (cover != null) {
                addCover(cover, depth + 1, out);
                // cover 已单独解析；其余值继续扫描（可能是别的邮件对象）
            } else if (m.containsKey("first_type") || m.containsKey("second_type")) {
                addCover(m, depth, out);
            }
            for (Map.Entry<?, ?> en : m.entrySet()) {
                if ("mail_cover_info".equals(String.valueOf(en.getKey()))) continue;   // 已单独解析
                Object v = en.getValue();
                if (v instanceof Map || v instanceof List) collect(v, depth + 1, out);
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                if (v instanceof Map || v instanceof List) collect(v, depth + 1, out);
            }
        }
    }

    private static void addCover(Object cover, int depth, List<ReportMail> out) {
        if (cover == null || depth > MAX_DEPTH + 1) return;
        if (cover instanceof List) {
            for (Object o : (List<?>) cover) addCover(o, depth + 1, out);
            return;
        }
        if (!(cover instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) cover;
        long first = num(m.get("first_type"));
        long second = num(m.get("second_type"));
        long mailId = num(firstNonZero(m, "mailid_new", "mail_id", "mailid", "id"));
        String title = text(firstNonZero(m, "title", "content", "subject", "desc"));
        ReportMail mail = new ReportMail(first, second, mailId, clip(title, MAX_TITLE));
        if (mail.valid() && out.size() < MAX_MAILS) out.add(mail);
    }

    private static Object firstNonZero(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && num(v) != 0L) return v;
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return v;
        }
        return null;
    }

    static long num(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
