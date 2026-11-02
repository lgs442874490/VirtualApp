package com.carlos.vaultserver.watchpoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观察点配置（服务器侧，{@code data/watchpoint.json}）。
 *
 * <p>2026-10-27 首次落地：配置来自 K888 实扫（见 design/观察点实测-20261027/README.md）。
 * <ul>
 *   <li>{@link #monitorTargets}：被监视的敌方 80 级炉子（uid + 当前坐标），各自一个 3–5s 高频查询矩形；</li>
 *   <li>{@link #protectRects}：7 个友盟聚集地（熊坑）的 20–30s 保护矩形；</li>
 *   <li>{@link #friendAids}：友盟 aid 集合（来袭告警时排除友军）；</li>
 *   <li>{@link #alertTypes}：触发告警的行军类型（1 侦查/2 进攻/5 驻防/99 集结随从/102 集结主体/10、12 等）。</li>
 * </ul>
 *
 * <p><b>启用</b>：环境变量 {@code VAULT_WATCHPOINT=on}（或 1/true）+ 本文件 {@code enabled=true} 同时满足。
 * 默认文件落盘时 {@code enabled=true}，但环境变量缺省 => 不启动（安全默认）。
 *
 * <p>文件修改后引擎会在 ~5s 内自动重载（mtime 变化），改配置不需要重启。
 */
public final class WatchPointStore {

    /** 开关环境变量：on/1/true 才启用（缺省关闭）。 */
    public static final String ENV_ENABLED = "VAULT_WATCHPOINT";

    /** 单个监视目标（敌方城）。{@code x/y} 为最近一次实扫坐标，迁城后由引擎自动更新。 */
    public static final class Target {
        public long uid;
        public String nick = "";
        public int x;
        public int y;

        public Target() {
        }

        public Target(long uid, String nick, int x, int y) {
            this.uid = uid;
            this.nick = nick == null ? "" : nick;
            this.x = x;
            this.y = y;
        }

        public String display() {
            return (nick.isEmpty() ? "" : nick + " ") + "(uid" + uid + ")";
        }
    }

    /** 查询/保护矩形（地图坐标，含端点，跨度 ≤55）。 */
    public static final class Rect {
        public String name = "";
        public int x1;
        public int y1;
        public int x2;
        public int y2;

        public Rect() {
        }

        public Rect(String name, int x1, int y1, int x2, int y2) {
            this.name = name == null ? "" : name;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        public boolean contains(int x, int y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }

        public String display() {
            return name + "(" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")";
        }
    }

    // ---------- 可编辑配置 ----------

    public boolean enabled = true;
    public long kid = 888L;
    /** 限定账号 fpid；空 = 任意在线账号（兼容旧配置；非空=只该账号）。 */
    public String fpid = "";
    /** 批次 A（2026-11-01）：累计监听所有账号（fpids 为空时生效）。 */
    public boolean allAccounts = true;
    /** 账号白名单（fpid 数字串，最多 20 个；空=全部）。 */
    public List<String> fpids = new ArrayList<>();
    /** 批次 B：跨区角色探测开关（默认 false；显式列出 uid 才尝试）。 */
    public boolean crossKid = false;
    /** 跨区角色 uid 列表（其它区服角色；查询仍带 store.kid）。 */
    public List<Long> crossKidRoleUids = new ArrayList<>();
    /** 监视目标查询间隔（毫秒）。 */
    public long monitorIntervalMs = 4000L;
    /** 保护矩形查询间隔（毫秒）。 */
    public long protectIntervalMs = 25000L;
    /** 常态请求限速（req/s），整图扫描突发不受此限。 */
    public double maxReqPerSec = 2.5d;
    /** 批次 Q2：多角色协同（同账号同 kid 的在线角色分摊监视/保护/扫描；env off 可强制单角色）。 */
    public boolean multiRole = true;
    /** 批次 Q1：整图扫描超时（毫秒；默认 60000，超时后按已回包数据收尾但不做告警判定）。 */
    public long scanTimeoutMs = 60_000L;
    /** 是否走企业微信（还需 vault.json watch.webhookUrl 非空）。只发告警，不发汇总。 */
    public boolean wecomEnabled = true;
    /** 可选：定期整图基线刷新间隔（毫秒；0=关闭，只在迁城事件触发）。 */
    public long fullScanIntervalMs = 0L;
    /** 触发告警的行军 type：只保留「侦查城(1)/进攻城(2)/集结打城(99/100/102)」。 */
    public Set<Long> alertTypes = new LinkedHashSet<>(List.of(1L, 2L, 99L, 100L, 102L));
    /** 只告警「目标是城」的行军（endpoint 命中已知城坐标）；false=不过滤（含打野/资源点）。 */
    public boolean cityTargetOnly = true;
    /**
     * 出征兵力判定阈值（2026-11-02）：低于该值视为「佯攻」，≥该值视为「威胁进攻」并在文案标红。
     * 默认 1000；0 = 关闭分级（一律按普通出征提示）。
     */
    public long feintTroopsBelow = 1000L;
    /**
     * 来袭告警是否包含 99/100「集结随从/集结」（2026-10-28 新增，默认 true）。
     *
     * <p>默认保留历史有效预警（如 技术分析→莲花emira [yYz]、小鹿月亮→师太 [SYD]）；
     * 设为 false 时 PROTECT 只告警 102 集结主体 / 2 进攻 / 1 侦查，便于以后嫌吵时关小类。
     * 注意：无论开关如何，目标必须是「已知友盟城」（aid&gt;0 且 ∈ friendAids），联盟未知目标永不告警。
     */
    public boolean incomingRallyFollowerAlerts = true;
    /** aid → 显示名（告警文案用；空则显示「盟{aid}」）。 */
    public Map<Long, String> aidLabels = new LinkedHashMap<>();
    /** 友盟 aid（来袭 owner 属于这些 aid 时不告警）。 */
    public Set<Long> friendAids = new LinkedHashSet<>(List.of(
            89700055L, 89700652L, 89700071L, 89700410L, 89700728L, 89700352L, 89700483L));
    /** 被监视的敌方城（80 级炉子）。 */
    public List<Target> monitorTargets = new ArrayList<>();
    /** 受保护的友盟聚集地（熊坑）。 */
    public List<Rect> protectRects = new ArrayList<>();

    // ---------------- 配置 API（批次 B 2026-10-30）：校验/快照/写回 ----------------

    /** 数量/长度上限（fail-closed：超限直接拒绝，不裁剪成“看似成功”）。 */
    public static final int MAX_TARGETS = 100;
    public static final int MAX_RECTS = 100;
    public static final int MAX_ALERT_TYPES = 32;
    public static final int MAX_FRIEND_AIDS = 200;
    public static final int MAX_TEXT = 64;
    /** 地图坐标有效范围（含端点；与引擎/网页版地图一致）。 */
    public static final int MAP_MIN = 0;
    public static final int MAP_MAX = 1200;
    /** 单个查询/保护矩形最大跨度（x/y 各自）。 */
    public static final int MAX_RECT_SPAN = 55;
    /** 告警 type 合理上限（1=侦查 … 102=集结主体；留余量给未来类型）。 */
    public static final long MAX_ALERT_TYPE = 99_999L;

    /** 配置快照（API get 返回；脱敏/裁剪大字段，线程安全）。 */
    public synchronized JSONObject snapshotJson() {
        ensureLoaded();
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("envEnabled", envEnabled());
        o.put("kid", kid);
        o.put("fpid", fpid == null ? "" : fpid);
        o.put("allAccounts", allAccounts);
        JSONArray fpa = new JSONArray();
        int fi = 0;
        for (String f : fpids) {
            if (fi++ >= 20) break;
            fpa.put(f);
        }
        o.put("fpids", fpa);
        o.put("crossKid", crossKid);
        JSONArray cua = new JSONArray();
        int ci = 0;
        for (Long u : crossKidRoleUids) {
            if (ci++ >= 100) break;
            cua.put(u);
        }
        o.put("crossKidRoleUids", cua);
        o.put("monitorIntervalMs", monitorIntervalMs);
        o.put("protectIntervalMs", protectIntervalMs);
        o.put("maxReqPerSec", maxReqPerSec);
        o.put("multiRole", multiRole);
        o.put("scanTimeoutMs", scanTimeoutMs);
        o.put("wecomEnabled", wecomEnabled);
        o.put("fullScanIntervalMs", fullScanIntervalMs);
        o.put("cityTargetOnly", cityTargetOnly);
        o.put("feintTroopsBelow", feintTroopsBelow);
        o.put("incomingRallyFollowerAlerts", incomingRallyFollowerAlerts);
        JSONArray at = new JSONArray();
        int n = 0;
        for (Long t : alertTypes) {
            if (n++ >= MAX_ALERT_TYPES) break;
            at.put(t);
        }
        o.put("alertTypes", at);
        JSONArray fa = new JSONArray();
        n = 0;
        for (Long a : friendAids) {
            if (n++ >= MAX_FRIEND_AIDS) break;
            fa.put(a);
        }
        o.put("friendAids", fa);
        JSONObject al = new JSONObject();
        n = 0;
        for (Map.Entry<Long, String> e : aidLabels.entrySet()) {
            if (n++ >= MAX_FRIEND_AIDS) break;
            al.put(String.valueOf(e.getKey()), clip(e.getValue(), 16));
        }
        o.put("aidLabels", al);
        JSONArray mt = new JSONArray();
        for (int i = 0; i < monitorTargets.size() && i < MAX_TARGETS; i++) {
            Target t = monitorTargets.get(i);
            mt.put(new JSONObject().put("uid", t.uid).put("nick", clip(t.nick, MAX_TEXT))
                    .put("x", t.x).put("y", t.y));
        }
        o.put("monitorTargets", mt);
        JSONArray pr = new JSONArray();
        for (int i = 0; i < protectRects.size() && i < MAX_RECTS; i++) {
            Rect r = protectRects.get(i);
            pr.put(new JSONObject().put("name", clip(r.name, MAX_TEXT)).put("x1", r.x1)
                    .put("y1", r.y1).put("x2", r.x2).put("y2", r.y2));
        }
        o.put("protectRects", pr);
        o.put("updatedAt", updatedAt());
        return o;
    }

    /** 文件最近修改时间（毫秒；无文件=0）。 */
    public synchronized long updatedAt() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 整体应用一份配置（API set）：**先全量校验，任何一条非法都拒绝且不落盘**（fail-closed），
     * 校验通过后才赋值 + 原子写 + 备份 {@code watchpoint.json.bak}。
     *
     * <p>字段缺省 = 保留当前值；显式给空数组 = 拒绝（引擎启动要求至少 1 目标/1 矩形，避免误清空）；
     * 字段既接受原生 JSONArray/JSONObject，也接受 App 下发的 JSON 字符串（HMAC 扁平标量约定）。
     *
     * @return null=成功；非空=中文错误（调用方转业务码，旧文件保持不变）
     */
    public synchronized String applyConfig(JSONObject in) {
        if (in == null) return "配置为空";
        ensureLoaded();
        try {
            boolean nEnabled = in.has("enabled") ? in.optBoolean("enabled", enabled) : enabled;
            long nKid = in.has("kid") ? in.optLong("kid", kid) : kid;
            if (nKid <= 0) return "区服 kid 必须大于 0";
            String nFpid = in.has("fpid") ? in.optString("fpid", "").trim() : fpid;
            if (nFpid.length() > MAX_TEXT) return "账号 fpid 过长";
            boolean nAllAccounts = in.has("allAccounts")
                    ? in.optBoolean("allAccounts", allAccounts) : allAccounts;
            List<String> nFpids;
            try {
                nFpids = in.has("fpids") ? parseFpids(in.get("fpids")) : new ArrayList<>(fpids);
            } catch (IllegalArgumentException iae) {
                return iae.getMessage();
            }
            boolean nCrossKid = in.has("crossKid")
                    ? in.optBoolean("crossKid", crossKid) : crossKid;
            List<Long> nCrossUids;
            try {
                nCrossUids = in.has("crossKidRoleUids")
                        ? parseLongIds(in.get("crossKidRoleUids"), "跨区角色 uid", 100)
                        : new ArrayList<>(crossKidRoleUids);
            } catch (IllegalArgumentException iae) {
                return iae.getMessage();
            }
            long nMonitorMs = in.has("monitorIntervalMs")
                    ? in.optLong("monitorIntervalMs", monitorIntervalMs) : monitorIntervalMs;
            long nProtectMs = in.has("protectIntervalMs")
                    ? in.optLong("protectIntervalMs", protectIntervalMs) : protectIntervalMs;
            long nFullScan = in.has("fullScanIntervalMs")
                    ? in.optLong("fullScanIntervalMs", fullScanIntervalMs) : fullScanIntervalMs;
            double nRate = in.has("maxReqPerSec")
                    ? optDouble(in.get("maxReqPerSec"), maxReqPerSec) : maxReqPerSec;
            if (nMonitorMs < 1000L || nMonitorMs > 300_000L) return "监视间隔须在 1000~300000ms";
            if (nProtectMs < 3000L || nProtectMs > 3_600_000L) return "保护间隔须在 3000~3600000ms";
            if (nFullScan < 0L || nFullScan > 86_400_000L) return "整图扫描间隔须在 0~86400000ms";
            if (nRate < 0.2d || nRate > 30d) return "限速须在 0.2~30 req/s";
            boolean nMulti = in.has("multiRole") ? in.optBoolean("multiRole", multiRole) : multiRole;
            long nScanTimeout = in.has("scanTimeoutMs")
                    ? in.optLong("scanTimeoutMs", scanTimeoutMs) : scanTimeoutMs;
            if (nScanTimeout < 5_000L || nScanTimeout > 300_000L) {
                return "扫描超时须在 5000~300000ms";
            }
            boolean nWecom = in.has("wecomEnabled") ? in.optBoolean("wecomEnabled", wecomEnabled) : wecomEnabled;
            boolean nCityOnly = in.has("cityTargetOnly") ? in.optBoolean("cityTargetOnly", cityTargetOnly)
                    : cityTargetOnly;
            long nFeint = in.has("feintTroopsBelow")
                    ? in.optLong("feintTroopsBelow", feintTroopsBelow) : feintTroopsBelow;
            if (nFeint < 0L) nFeint = 0L;
            if (nFeint > 100_000_000L) nFeint = 100_000_000L;
            boolean nRallyFollower = in.has("incomingRallyFollowerAlerts")
                    ? in.optBoolean("incomingRallyFollowerAlerts", incomingRallyFollowerAlerts)
                    : incomingRallyFollowerAlerts;

            Set<Long> nAlertTypes = in.has("alertTypes")
                    ? parseIds(in.get("alertTypes"), "告警类型") : new LinkedHashSet<>(alertTypes);
            if (nAlertTypes.isEmpty()) return "告警类型不能为空";
            if (nAlertTypes.size() > MAX_ALERT_TYPES) return "告警类型过多（>" + MAX_ALERT_TYPES + "）";
            for (Long t : nAlertTypes) {
                String err = validateAlertType(t);
                if (err != null) return err;
            }
            Set<Long> nFriends = in.has("friendAids")
                    ? parseIds(in.get("friendAids"), "友盟 aid") : new LinkedHashSet<>(friendAids);
            if (nFriends.size() > MAX_FRIEND_AIDS) return "友盟 aid 过多（>" + MAX_FRIEND_AIDS + "）";
            for (Long a : nFriends) {
                String err = validateFriendAid(a);
                if (err != null) return err;
            }
            Map<Long, String> nLabels = in.has("aidLabels")
                    ? parseAidLabels(in.get("aidLabels")) : new LinkedHashMap<>(aidLabels);
            List<Target> nTargets = in.has("monitorTargets")
                    ? parseTargets(in.get("monitorTargets")) : new ArrayList<>(monitorTargets);
            List<Rect> nRects = in.has("protectRects")
                    ? parseRects(in.get("protectRects")) : new ArrayList<>(protectRects);
            if (nTargets.isEmpty()) return "监视目标不能为空（至少 1 个）";
            if (nTargets.size() > MAX_TARGETS) return "监视目标过多（>" + MAX_TARGETS + "）";
            Set<Long> uids = new LinkedHashSet<>();
            for (Target t : nTargets) {
                String err = validateTarget(t.uid, t.nick, t.x, t.y);
                if (err != null) return err;
                if (!uids.add(t.uid)) return "监视目标 uid 重复: " + t.uid;
            }
            if (nRects.isEmpty()) return "保护矩形不能为空（至少 1 个）";
            if (nRects.size() > MAX_RECTS) return "保护矩形过多（>" + MAX_RECTS + "）";
            for (Rect r : nRects) {
                String err = validateRect(r.name, r.x1, r.y1, r.x2, r.y2);
                if (err != null) return err;
            }

            // ---- 校验全部通过后才提交（fail-closed：此前任何 return 均未改内存/文件） ----
            enabled = nEnabled;
            kid = nKid;
            fpid = nFpid;
            allAccounts = nAllAccounts;
            fpids = nFpids;
            crossKid = nCrossKid;
            crossKidRoleUids = nCrossUids;
            monitorIntervalMs = nMonitorMs;
            protectIntervalMs = nProtectMs;
            fullScanIntervalMs = nFullScan;
            maxReqPerSec = nRate;
            multiRole = nMulti;
            scanTimeoutMs = nScanTimeout;
            wecomEnabled = nWecom;
            cityTargetOnly = nCityOnly;
            feintTroopsBelow = nFeint;
            incomingRallyFollowerAlerts = nRallyFollower;
            alertTypes = nAlertTypes;
            friendAids = nFriends;
            aidLabels = nLabels;
            monitorTargets = nTargets;
            protectRects = nRects;
            save();
            return null;
        } catch (Throwable t) {
            return "配置解析失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    /** 只改开关（API enable/disable）；写盘+备份，其余字段不变。 */
    public synchronized void setEnabled(boolean on) {
        enabled = on;
        save();
    }

    // ---------------- schema 校验纯函数（包内/单测可见） ----------------

    public static String validateAlertType(long type) {
        if (type <= 0L || type > MAX_ALERT_TYPE) return "告警类型非法: " + type;
        return null;
    }

    public static String validateFriendAid(long aid) {
        if (aid <= 0L) return "友盟 aid 必须大于 0: " + aid;
        return null;
    }

    public static String validateTarget(long uid, String nick, int x, int y) {
        if (uid <= 0L) return "监视目标 uid 必须大于 0: " + uid;
        if (nick != null && nick.length() > MAX_TEXT) return "监视目标昵称过长（>" + MAX_TEXT + "）";
        String err = validateCoord(x);
        if (err != null) return "监视目标 x " + err;
        err = validateCoord(y);
        if (err != null) return "监视目标 y " + err;
        return null;
    }

    public static String validateRect(String name, int x1, int y1, int x2, int y2) {
        if (name != null && name.length() > MAX_TEXT) return "保护矩形名称过长（>" + MAX_TEXT + "）";
        String err = validateCoord(x1);
        if (err != null) return "保护矩形 x1 " + err;
        err = validateCoord(y1);
        if (err != null) return "保护矩形 y1 " + err;
        err = validateCoord(x2);
        if (err != null) return "保护矩形 x2 " + err;
        err = validateCoord(y2);
        if (err != null) return "保护矩形 y2 " + err;
        if (x2 <= x1 || y2 <= y1) return "保护矩形须满足 x2>x1 且 y2>y1: " + name;
        if (x2 - x1 > MAX_RECT_SPAN || y2 - y1 > MAX_RECT_SPAN) {
            return "保护矩形跨度超过 " + MAX_RECT_SPAN + ": " + name;
        }
        return null;
    }

    public static String validateCoord(int v) {
        if (v < MAP_MIN || v > MAP_MAX) return "坐标超出范围 " + MAP_MIN + "~" + MAP_MAX + ": " + v;
        return null;
    }

    // ---------------- 解析辅助（接受 JSON 字符串/数组；App 扁平标量约定） ----------------

    static List<Target> parseTargets(Object raw) {
        List<Target> out = new ArrayList<>();
        JSONArray arr = asArray(raw);
        if (arr == null) throw new IllegalArgumentException("monitorTargets 须为 JSON 数组");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException("monitorTargets[" + i + "] 须为对象");
            Target t = new Target(o.optLong("uid"), clip(o.optString("nick", ""), MAX_TEXT),
                    o.optInt("x"), o.optInt("y"));
            out.add(t);
        }
        return out;
    }

    static List<Rect> parseRects(Object raw) {
        List<Rect> out = new ArrayList<>();
        JSONArray arr = asArray(raw);
        if (arr == null) throw new IllegalArgumentException("protectRects 须为 JSON 数组");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException("protectRects[" + i + "] 须为对象");
            out.add(new Rect(clip(o.optString("name", ""), MAX_TEXT), o.optInt("x1"),
                    o.optInt("y1"), o.optInt("x2"), o.optInt("y2")));
        }
        return out;
    }

    /** 支持 JSON 数组字符串、JSONArray、或 "1,2,99" 逗号串。 */
    static Set<Long> parseIds(Object raw, String label) {
        Set<Long> out = new LinkedHashSet<>();
        if (raw == null) return out;
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) out.add(arr.optLong(i));
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return out;
        if (text.startsWith("[")) {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optLong(i));
            return out;
        }
        for (String part : text.split(",")) {
            String v = part.trim();
            if (v.isEmpty()) continue;
            try {
                out.add(Long.parseLong(v));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " 含非法数字: " + v);
            }
        }
        return out;
    }

    static Map<Long, String> parseAidLabels(Object raw) {
        Map<Long, String> out = new LinkedHashMap<>();
        Object v = raw;
        if (v instanceof String && !((String) v).trim().startsWith("{")) return out;
        JSONObject o;
        if (v instanceof JSONObject) o = (JSONObject) v;
        else if (v instanceof String) o = new JSONObject((String) v);
        else return out;
        for (String k : o.keySet()) {
            try {
                long aid = Long.parseLong(k.trim());
                if (aid > 0) out.put(aid, clip(o.optString(k, ""), 16));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static JSONArray asArray(Object raw) {
        if (raw instanceof JSONArray) return (JSONArray) raw;
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) return new JSONArray();
            return new JSONArray(text);
        }
        return null;
    }

    static double optDouble(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private final Path file;
    /** 是否已从磁盘载入（API 首次访问时惰性 load，见 {@link #ensureLoaded()}）。 */
    private volatile boolean loaded;

    public WatchPointStore(Path dataDir) {
        this.file = dataDir.resolve("watchpoint.json");
    }

    public Path file() {
        return file;
    }

    /** 是否启用（环境变量 + 文件开关）。 */
    public static boolean envEnabled() {
        String v = System.getenv(ENV_ENABLED);
        if (v == null) v = System.getProperty(ENV_ENABLED);
        return "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** API 首次访问时确保已载入（引擎未启动/未启用时也能读写配置）。 */
    public synchronized void ensureLoaded() {
        if (!loaded) load();
    }

    /** 载入配置；文件不存在时写入默认（K888 实扫结果）并落盘。 */
    public synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.isRegularFile(file)) {
                seedDefaults();
                saveLocked();
                loaded = true;
                return;
            }
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            enabled = o.optBoolean("enabled", true);
            kid = o.optLong("kid", 888L);
            fpid = o.optString("fpid", "");
            allAccounts = o.optBoolean("allAccounts", true);
            fpids = new ArrayList<>();
            JSONArray fpa = o.optJSONArray("fpids");
            if (fpa != null) {
                for (int i = 0; i < fpa.length() && fpids.size() < 20; i++) {
                    String v = String.valueOf(fpa.opt(i)).trim();
                    if (v.matches("\\d{1,20}")) fpids.add(v);
                }
            }
            crossKid = o.optBoolean("crossKid", false);
            crossKidRoleUids = new ArrayList<>();
            JSONArray cua = o.optJSONArray("crossKidRoleUids");
            if (cua != null) {
                for (int i = 0; i < cua.length() && crossKidRoleUids.size() < 100; i++) {
                    long v = cua.optLong(i, 0L);
                    if (v > 0L) crossKidRoleUids.add(v);
                }
            }
            monitorIntervalMs = clamp(o.optLong("monitorIntervalMs", 4000L), 1000L, 300_000L);
            protectIntervalMs = clamp(o.optLong("protectIntervalMs", 25000L), 3000L, 3_600_000L);
            maxReqPerSec = clampDouble(o.optDouble("maxReqPerSec", 2.5d), 0.2d, 30d);
            multiRole = o.optBoolean("multiRole", true);
            scanTimeoutMs = clamp(o.optLong("scanTimeoutMs", 60_000L), 5_000L, 300_000L);
            wecomEnabled = o.optBoolean("wecomEnabled", true);
            fullScanIntervalMs = clamp(o.optLong("fullScanIntervalMs", 0L), 0L, 86_400_000L);
            cityTargetOnly = o.optBoolean("cityTargetOnly", true);
            feintTroopsBelow = o.optLong("feintTroopsBelow", 1000L);
            if (feintTroopsBelow < 0L) feintTroopsBelow = 0L;
            incomingRallyFollowerAlerts = o.optBoolean("incomingRallyFollowerAlerts", true);
            alertTypes.clear();
            JSONArray at = o.optJSONArray("alertTypes");
            if (at != null) {
                for (int i = 0; i < at.length(); i++) alertTypes.add(at.optLong(i));
            }
            aidLabels.clear();
            JSONObject al = o.optJSONObject("aidLabels");
            if (al != null) {
                for (String k : al.keySet()) {
                    try {
                        aidLabels.put(Long.parseLong(k), al.optString(k, ""));
                    } catch (Throwable ignored) {
                    }
                }
            }
            friendAids.clear();
            JSONArray fa = o.optJSONArray("friendAids");
            if (fa != null) {
                for (int i = 0; i < fa.length(); i++) friendAids.add(fa.optLong(i));
            }
            monitorTargets.clear();
            JSONArray mt = o.optJSONArray("monitorTargets");
            if (mt != null) {
                for (int i = 0; i < mt.length(); i++) {
                    JSONObject t = mt.optJSONObject(i);
                    if (t == null) continue;
                    Target target = new Target(t.optLong("uid"), t.optString("nick", ""),
                            t.optInt("x"), t.optInt("y"));
                    if (target.uid > 0) monitorTargets.add(target);
                }
            }
            protectRects.clear();
            JSONArray pr = o.optJSONArray("protectRects");
            if (pr != null) {
                for (int i = 0; i < pr.length(); i++) {
                    JSONObject r = pr.optJSONObject(i);
                    if (r == null) continue;
                    Rect rect = new Rect(r.optString("name", ""), r.optInt("x1"), r.optInt("y1"),
                            r.optInt("x2"), r.optInt("y2"));
                    if (rect.x2 > rect.x1 && rect.y2 > rect.y1) protectRects.add(rect);
                }
            }
            if (monitorTargets.isEmpty() || protectRects.isEmpty()) {
                seedDefaults();
                saveLocked();
            }
            loaded = true;
        } catch (Throwable t) {
            seedDefaults();
            loaded = true;
        }
    }

    /** 落盘（原子替换）。 */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            saveLocked();
        } catch (IOException ignored) {
        }
    }

    private void saveLocked() throws IOException {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("kid", kid);
        o.put("fpid", fpid == null ? "" : fpid);
        o.put("allAccounts", allAccounts);
        JSONArray fpa = new JSONArray();
        int fi = 0;
        for (String f : fpids) {
            if (fi++ >= 20) break;
            fpa.put(f);
        }
        o.put("fpids", fpa);
        o.put("crossKid", crossKid);
        JSONArray cua = new JSONArray();
        int ci = 0;
        for (Long u : crossKidRoleUids) {
            if (ci++ >= 100) break;
            cua.put(u);
        }
        o.put("crossKidRoleUids", cua);
        o.put("monitorIntervalMs", monitorIntervalMs);
        o.put("protectIntervalMs", protectIntervalMs);
        o.put("maxReqPerSec", maxReqPerSec);
        o.put("multiRole", multiRole);
        o.put("scanTimeoutMs", scanTimeoutMs);
        o.put("wecomEnabled", wecomEnabled);
        o.put("fullScanIntervalMs", fullScanIntervalMs);
        o.put("cityTargetOnly", cityTargetOnly);
        o.put("feintTroopsBelow", feintTroopsBelow);
        o.put("incomingRallyFollowerAlerts", incomingRallyFollowerAlerts);
        JSONArray at = new JSONArray();
        for (Long t : alertTypes) at.put(t);
        o.put("alertTypes", at);
        JSONArray fa = new JSONArray();
        for (Long a : friendAids) fa.put(a);
        o.put("friendAids", fa);
        JSONObject al = new JSONObject();
        for (Map.Entry<Long, String> en : aidLabels.entrySet()) al.put(String.valueOf(en.getKey()), en.getValue());
        o.put("aidLabels", al);
        JSONArray mt = new JSONArray();
        for (Target t : monitorTargets) {
            mt.put(new JSONObject().put("uid", t.uid).put("nick", t.nick).put("x", t.x).put("y", t.y));
        }
        o.put("monitorTargets", mt);
        JSONArray pr = new JSONArray();
        for (Rect r : protectRects) {
            pr.put(new JSONObject().put("name", r.name).put("x1", r.x1).put("y1", r.y1)
                    .put("x2", r.x2).put("y2", r.y2));
        }
        o.put("protectRects", pr);
        // 批次 B：写前保留一份 .bak（仅当旧文件存在；备份失败不影响主流程）
        try {
            if (Files.isRegularFile(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignored) {
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, o.toString(2).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 默认种子：K888 实扫（2026-10-27）得到的 5 个监视目标 + 8 个保护矩形。 */
    private void seedDefaults() {
        enabled = true;
        kid = 888L;
        fpid = "";
        alertTypes = new LinkedHashSet<>(List.of(1L, 2L, 99L, 100L, 102L));
        cityTargetOnly = true;
        feintTroopsBelow = 1000L;
        incomingRallyFollowerAlerts = true;
        friendAids = new LinkedHashSet<>(List.of(
                89700055L, 89700652L, 89700071L, 89700410L, 89700728L, 89700352L, 89700483L));
        aidLabels = new LinkedHashMap<>();
        aidLabels.put(89700055L, "SYD");
        aidLabels.put(89700652L, "USY");
        aidLabels.put(89700071L, "LUV");
        aidLabels.put(89700410L, "SHE");
        aidLabels.put(89700728L, "THY");
        aidLabels.put(89700352L, "yYz");
        aidLabels.put(89700483L, "KFC");
        aidLabels.put(89700704L, "Hey");
        monitorTargets = new ArrayList<>(List.of(
                new Target(29180054L, "大陆元帥車宝山", 555, 554),
                new Target(13847079L, "放弃沉没成本远离是非之地", 774, 625),
                new Target(16253662L, "乔伊号", 755, 890),
                new Target(31374667L, "今晚你睡觉了么我的宝贝儿", 981, 651),
                new Target(35712407L, "鲱鱼罐头（dy雷霆）", 290, 885)));
        protectRects = new ArrayList<>(List.of(
                new Rect("SYD", 722, 454, 776, 508),
                new Rect("USY", 462, 550, 516, 604),
                new Rect("LUV", 562, 662, 616, 716),
                new Rect("SHE", 703, 634, 757, 688),
                new Rect("yYz", 528, 431, 582, 485),
                new Rect("KFC", 656, 666, 710, 720),
                new Rect("THY-1", 436, 666, 490, 720),
                new Rect("THY-2", 464, 666, 518, 720)));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
    /** fpids：JSONArray / 逗号字符串；元素必须数字串，最多 20 个。 */
    static List<String> parseFpids(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        List<String> parts = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length(); i++) parts.add(String.valueOf(a.opt(i)).trim());
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) parts.add(String.valueOf(o).trim());
        } else {
            for (String p : String.valueOf(raw).split("[,，\\s]+")) {
                if (!p.trim().isEmpty()) parts.add(p.trim());
            }
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!p.matches("\\d{1,20}")) throw new IllegalArgumentException("账号 fpid 须为数字串: " + p);
            if (seen.size() >= 20) throw new IllegalArgumentException("账号白名单最多 20 个");
            seen.add(p);
        }
        out.addAll(seen);
        return out;
    }

    /** 通用 long id 列表（JSONArray/逗号串/List）；上限 max，非法拒绝。 */
    static List<Long> parseLongIds(Object raw, String what, int max) {
        List<Long> out = new ArrayList<>();
        if (raw == null) return out;
        List<Object> vals = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length(); i++) vals.add(a.opt(i));
        } else if (raw instanceof List) {
            vals.addAll((List<?>) raw);
        } else {
            for (String p : String.valueOf(raw).split("[,，\\s]+")) {
                if (!p.trim().isEmpty()) vals.add(p.trim());
            }
        }
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (Object v : vals) {
            long id;
            try {
                id = v instanceof Number ? ((Number) v).longValue()
                        : Long.parseLong(String.valueOf(v).trim());
            } catch (Throwable t) {
                throw new IllegalArgumentException(what + " 须为数字: " + v);
            }
            if (id <= 0L) throw new IllegalArgumentException(what + " 须大于 0");
            if (seen.size() >= max) throw new IllegalArgumentException(what + " 最多 " + max + " 个");
            seen.add(id);
        }
        out.addAll(seen);
        return out;
    }

}
