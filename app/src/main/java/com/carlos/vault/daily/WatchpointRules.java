package com.carlos.vault.daily;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观察点配置校验/解析（服务端 {@code WatchPointStore} 规则的 App 侧 1:1 镜像，2026-10-30）。
 *
 * <p>抽成纯 JVM 类便于单测（UI 不参与）：uid&gt;0、坐标 0~1200、矩形 x2&gt;x1/y2&gt;y1 且跨度 ≤55、
 * 目标/矩形数量与文本长度上限；错误返回中文文案（null=合法），保存前调用，服务端再兜底校验。
 */
public final class WatchpointRules {

    public static final int MAP_MIN = 0;
    public static final int MAP_MAX = 1200;
    public static final int MAX_SPAN = 55;
    public static final int MAX_TARGETS = 100;
    public static final int MAX_RECTS = 100;
    public static final int MAX_TEXT = 64;
    public static final int MAX_ALERT_TYPES = 32;
    public static final int MAX_FRIEND_AIDS = 200;

    private WatchpointRules() {
    }

    // ==================== 单字段校验 ====================

    /** uid：必须能解析为 >0 的长整型。 */
    public static String validateUid(String raw) {
        long uid = parseLong(raw);
        return uid > 0 ? null : "uid 必须为大于 0 的数字";
    }

    /** 坐标：0~1200（含端点）。 */
    public static String validateCoord(String raw) {
        long v = parseLong(raw);
        if (v < MAP_MIN || v > MAP_MAX) return "坐标须在 " + MAP_MIN + "~" + MAP_MAX + " 之间";
        return null;
    }

    /** 单条监视目标（uid/nick/x/y 均为输入框文本）。 */
    public static String validateTarget(String uid, String nick, String x, String y) {
        String err = validateUid(uid);
        if (err != null) return err;
        if (nick != null && nick.length() > MAX_TEXT) return "昵称过长（≤" + MAX_TEXT + " 字）";
        err = validateCoord(x);
        if (err != null) return "x " + err;
        err = validateCoord(y);
        if (err != null) return "y " + err;
        return null;
    }

    /** 单个保护矩形（name/x1/y1/x2/y2 均为输入框文本）。 */
    public static String validateRect(String name, String x1, String y1, String x2, String y2) {
        if (name != null && name.length() > MAX_TEXT) return "名称过长（≤" + MAX_TEXT + " 字）";
        String err = validateCoord(x1);
        if (err != null) return "x1 " + err;
        err = validateCoord(y1);
        if (err != null) return "y1 " + err;
        err = validateCoord(x2);
        if (err != null) return "x2 " + err;
        err = validateCoord(y2);
        if (err != null) return "y2 " + err;
        long ax = parseLong(x1);
        long ay = parseLong(y1);
        long bx = parseLong(x2);
        long by = parseLong(y2);
        if (bx <= ax || by <= ay) return "矩形须满足 x2>x1 且 y2>y1";
        if (bx - ax > MAX_SPAN || by - ay > MAX_SPAN) return "矩形跨度不能超过 " + MAX_SPAN;
        return null;
    }

    /** 友盟 aid 列表（逗号分隔；每项 &gt;0）。 */
    public static String validateFriendAids(String csv) {
        Set<Long> ids = parseIds(csv);
        if (ids == null) return "友盟 aid 含非法数字";
        if (ids.size() > MAX_FRIEND_AIDS) return "友盟 aid 过多（>" + MAX_FRIEND_AIDS + "）";
        for (Long id : ids) {
            if (id <= 0L) return "友盟 aid 必须大于 0";
        }
        return null;
    }

    /** 告警类型列表（逗号分隔；每项 1~99999）。 */
    public static String validateAlertTypes(String csv) {
        Set<Long> ids = parseIds(csv);
        if (ids == null) return "告警类型含非法数字";
        if (ids.isEmpty()) return "告警类型不能为空";
        if (ids.size() > MAX_ALERT_TYPES) return "告警类型过多（>" + MAX_ALERT_TYPES + "）";
        for (Long id : ids) {
            if (id <= 0L || id > 99_999L) return "告警类型非法: " + id;
        }
        return null;
    }

    // ==================== 整表校验 ====================

    /** 目标列表（每项 {uid,nick,x,y}）校验；返回 null=合法。 */
    public static String validateTargets(JSONArray targets) {
        if (targets == null || targets.length() == 0) return "监视目标不能为空（至少 1 个）";
        if (targets.length() > MAX_TARGETS) return "监视目标过多（>" + MAX_TARGETS + "）";
        Set<Long> uids = new LinkedHashSet<>();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject o = targets.optJSONObject(i);
            if (o == null) return "第 " + (i + 1) + " 个监视目标格式错误";
            long uid = o.optLong("uid");
            String err = validateTarget(String.valueOf(uid), o.optString("nick", ""),
                    String.valueOf(o.optInt("x")), String.valueOf(o.optInt("y")));
            if (err != null) return "第 " + (i + 1) + " 个监视目标：" + err;
            if (!uids.add(uid)) return "监视目标 uid 重复: " + uid;
        }
        return null;
    }

    /** 矩形列表（每项 {name,x1,y1,x2,y2}）校验；返回 null=合法。 */
    public static String validateRects(JSONArray rects) {
        if (rects == null || rects.length() == 0) return "保护矩形不能为空（至少 1 个）";
        if (rects.length() > MAX_RECTS) return "保护矩形过多（>" + MAX_RECTS + "）";
        for (int i = 0; i < rects.length(); i++) {
            JSONObject o = rects.optJSONObject(i);
            if (o == null) return "第 " + (i + 1) + " 个保护矩形格式错误";
            String err = validateRect(o.optString("name", ""), String.valueOf(o.optInt("x1")),
                    String.valueOf(o.optInt("y1")), String.valueOf(o.optInt("x2")),
                    String.valueOf(o.optInt("y2")));
            if (err != null) return "第 " + (i + 1) + " 个保护矩形：" + err;
        }
        return null;
    }

    /** 保存前整体校验（目标/矩形/友盟/告警类型）。 */
    public static String validateAll(JSONArray targets, JSONArray rects, String friendAids,
                                     String alertTypes) {
        String err = validateTargets(targets);
        if (err != null) return err;
        err = validateRects(rects);
        if (err != null) return err;
        err = validateFriendAids(friendAids);
        if (err != null) return err;
        return validateAlertTypes(alertTypes);
    }

    // ==================== 构造/解析 ====================

    public static JSONObject target(long uid, String nick, int x, int y) {
        try {
            return new JSONObject().put("uid", uid).put("nick", nick == null ? "" : nick)
                    .put("x", x).put("y", y);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static JSONObject rect(String name, int x1, int y1, int x2, int y2) {
        try {
            return new JSONObject().put("name", name == null ? "" : name)
                    .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 逗号分隔数字 → 去重集合；含非法项返回 null。 */
    public static Set<Long> parseIds(String csv) {
        Set<Long> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String v = part.trim();
            if (v.isEmpty()) continue;
            try {
                out.add(Long.parseLong(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** 集合 → 逗号文本（页面回填用）。 */
    public static String joinCsv(Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids == null) return "";
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** JSON 数组（[{uid,nick,x,y}]）→ 页面列表（保持顺序）。 */
    public static List<Map<String, Object>> parseTargets(JSONArray arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uid", o.optLong("uid"));
            m.put("nick", o.optString("nick", ""));
            m.put("x", o.optInt("x"));
            m.put("y", o.optInt("y"));
            out.add(m);
        }
        return out;
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }
}
