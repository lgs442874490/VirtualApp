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
    /** 限定账号 fpid；空 = 任意在线账号。 */
    public String fpid = "";
    /** 监视目标查询间隔（毫秒）。 */
    public long monitorIntervalMs = 4000L;
    /** 保护矩形查询间隔（毫秒）。 */
    public long protectIntervalMs = 25000L;
    /** 常态请求限速（req/s），整图扫描突发不受此限。 */
    public double maxReqPerSec = 2.5d;
    /** 是否走企业微信（还需 vault.json watch.webhookUrl 非空）。只发告警，不发汇总。 */
    public boolean wecomEnabled = true;
    /** 可选：定期整图基线刷新间隔（毫秒；0=关闭，只在迁城事件触发）。 */
    public long fullScanIntervalMs = 0L;
    /** 触发告警的行军 type：只保留「侦查城(1)/进攻城(2)/集结打城(99/100/102)」。 */
    public Set<Long> alertTypes = new LinkedHashSet<>(List.of(1L, 2L, 99L, 100L, 102L));
    /** 只告警「目标是城」的行军（endpoint 命中已知城坐标）；false=不过滤（含打野/资源点）。 */
    public boolean cityTargetOnly = true;
    /** aid → 显示名（告警文案用；空则显示「盟{aid}」）。 */
    public Map<Long, String> aidLabels = new LinkedHashMap<>();
    /** 友盟 aid（来袭 owner 属于这些 aid 时不告警）。 */
    public Set<Long> friendAids = new LinkedHashSet<>(List.of(
            89700055L, 89700652L, 89700071L, 89700410L, 89700728L, 89700352L, 89700483L));
    /** 被监视的敌方城（80 级炉子）。 */
    public List<Target> monitorTargets = new ArrayList<>();
    /** 受保护的友盟聚集地（熊坑）。 */
    public List<Rect> protectRects = new ArrayList<>();

    private final Path file;

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

    /** 载入配置；文件不存在时写入默认（K888 实扫结果）并落盘。 */
    public synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.isRegularFile(file)) {
                seedDefaults();
                saveLocked();
                return;
            }
            JSONObject o = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            enabled = o.optBoolean("enabled", true);
            kid = o.optLong("kid", 888L);
            fpid = o.optString("fpid", "");
            monitorIntervalMs = clamp(o.optLong("monitorIntervalMs", 4000L), 1000L, 300_000L);
            protectIntervalMs = clamp(o.optLong("protectIntervalMs", 25000L), 3000L, 3_600_000L);
            maxReqPerSec = clampDouble(o.optDouble("maxReqPerSec", 2.5d), 0.2d, 30d);
            wecomEnabled = o.optBoolean("wecomEnabled", true);
            fullScanIntervalMs = clamp(o.optLong("fullScanIntervalMs", 0L), 0L, 86_400_000L);
            cityTargetOnly = o.optBoolean("cityTargetOnly", true);
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
        } catch (Throwable t) {
            seedDefaults();
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
        o.put("monitorIntervalMs", monitorIntervalMs);
        o.put("protectIntervalMs", protectIntervalMs);
        o.put("maxReqPerSec", maxReqPerSec);
        o.put("wecomEnabled", wecomEnabled);
        o.put("fullScanIntervalMs", fullScanIntervalMs);
        o.put("cityTargetOnly", cityTargetOnly);
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
}
