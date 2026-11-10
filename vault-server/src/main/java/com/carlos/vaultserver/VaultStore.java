package com.carlos.vaultserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 身份库持久化（data/vault.json，原子写 + .bak 备份）。
 *
 * 数据权责：App 是账号/token/指纹的源头（全量 sync 覆盖）；服务器只在登录后
 * 把"学到"的设备指纹落库（learnIdentity），下次全量 sync 若 App 未带同角色指纹则保留服务器的，
 * 避免服务器生成的设备身份被 sync 冲掉造成指纹漂移。
 */
public final class VaultStore {

    /** 运行态意图值（持久化；与 M1 契约 §2.1 runState 对齐）。 */
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_PAUSED = "PAUSED";
    public static final String RUN_STOPPED = "STOPPED";

    /** 角色 key：sid:uid（同服多角色 sid 相同 uid 不同，uid 缺省用空串）。 */
    public static String roleKey(String sid, String uid) {
        return (sid == null ? "" : sid) + ":" + (uid == null ? "" : uid);
    }

    /** 按角色 uid 的配置表键（{@code roleConfigByUid/roleParamsByUid}）：{@code <sid>:<uid>}。 */
    public static String roleUidConfigKey(long sid, long uid) {
        return sid + ":" + uid;
    }

    /**
     * 角色**生效**任务开关（R2 Phase 2）：uid&gt;0 时按 uid 表优先 → 旧 sid 表（区服默认，兼容旧 App）
     * → null（调用方按任务目录默认值处理）。uid&lt;=0 时等价旧 sid 表。
     */
    public static Map<String, Boolean> roleConfigOf(Account acc, long sid, long uid) {
        if (acc == null) return null;
        if (uid > 0L) {
            Map<String, Boolean> byUid = acc.roleConfigByUid.get(roleUidConfigKey(sid, uid));
            if (byUid != null && !byUid.isEmpty()) return byUid;
        }
        return acc.roleConfig.get(String.valueOf(sid));
    }

    /** 角色**生效**任务参数（R2 Phase 2）：规则同 {@link #roleConfigOf}。 */
    public static Map<String, String> roleParamsOf(Account acc, long sid, long uid) {
        if (acc == null) return null;
        if (uid > 0L) {
            Map<String, String> byUid = acc.roleParamsByUid.get(roleUidConfigKey(sid, uid));
            if (byUid != null && !byUid.isEmpty()) return byUid;
        }
        return acc.roleParams.get(String.valueOf(sid));
    }

    /** 生效配置来源：{@code uid} / {@code sid} / {@code default}（可观测/测试用）。 */
    public static String roleConfigScope(Account acc, long sid, long uid) {
        if (acc == null) return "default";
        if (uid > 0L) {
            Map<String, Boolean> byUid = acc.roleConfigByUid.get(roleUidConfigKey(sid, uid));
            if (byUid != null && !byUid.isEmpty()) return "uid";
        }
        return acc.roleConfig.containsKey(String.valueOf(sid)) ? "sid" : "default";
    }

    /**
     * 账号池角色条目（M1 2026-10-21；2026-10-21 多角色并行修订）：服务器侧每角色持久化意图态。
     *
     * <p>desiredOnline/runState 是服务器侧"期望"（显式动作与 sync 重算写入），
     * 重启后 reconcile 据此恢复会话（desiredOnline 的角色全部恢复，同 fpid 可多个）；
     * enabled=false = 已收纳（动作拒绝执行）。
     */
    public static final class RoleEntry {
        public String sid;
        public String uid;
        public String name = "";
        public long level;
        public long power;
        public boolean enabled = true;
        public boolean desiredOnline;
        /** desiredOnline 最近一次置位时刻（诊断用；恢复不看它，desired 的全部恢复）。 */
        public long desiredAt;
        public String runState = RUN_STOPPED;
        /** 暂停自动恢复时刻（epoch millis；0=无）。PAUSED 态下到期即按恢复策略重新登录。 */
        public long pauseResumeAt;

        public RoleEntry(String sid, String uid) {
            this.sid = sid == null ? "" : sid;
            this.uid = uid == null ? "" : uid;
        }

        RoleEntry copy() {
            RoleEntry c = new RoleEntry(sid, uid);
            c.name = name;
            c.level = level;
            c.power = power;
            c.enabled = enabled;
            c.desiredOnline = desiredOnline;
            c.desiredAt = desiredAt;
            c.runState = runState;
            c.pauseResumeAt = pauseResumeAt;
            return c;
        }
    }

    /**
     * 配置默认值版本（2026-11-11「默认开启」策略）：
     * 版本达到本值的客户端配置视为“用户已按新默认看过/改过”，服务端不再强制迁移；
     * 低于本值（旧客户端）或缺失时，把 false 一次性迁移为新默认开。
     *
     * <p>v2：补齐跨端任务 guardian/war（服务端任务目录没有，v1 迁移漏掉）。
     */
    public static final int CURRENT_CONFIG_DEFAULTS_VERSION = 2;

    public static final class Account {
        public String fpid;
        public String token = "";
        public String label = "";
        public String group = "";
        public boolean keep;
        /** 当前选中角色 sid（null = 服务器默认角色）。 */
        public String activeSid;
        /**
         * 当前选中角色 uid（null/"" = 未提供 = 旧行为，只按 sid 发现角色）。
         *
         * <p>同服多角色（一个 token 在同一区服有多个角色）时 sid 无法区分登哪个——
         * App 2026-10-20 起随 sync 下发 activeUid，{@link SessionKeeper#loginBoundRole}
         * 优先按它直绑。缺省 0/空时保持原有「sid → discoverRoleUid」发现路径。
         */
        public String activeUid;
        /** 账号级设备指纹 JSON 串（可为 null）。 */
        public String identity;
        /** 每角色设备指纹：sid → JSON 串。 */
        public final Map<String, String> roleIdentity = new LinkedHashMap<>();
        /** 单账号 SOCKS5 出口覆盖（host:port，null = 用全局）。 */
        public String proxy;
        /** 自动日常开关（服务器整轮调度用）。 */
        public boolean auto;
        /** 自动日常时间窗（小时，含端点；默认 5-9）。 */
        public int windowStart = 5;
        public int windowEnd = 9;
        /** 每角色任务开关：sid → {fnId:bool}（null = 用任务默认值）。 */
        public final Map<String, Map<String, Boolean>> roleConfig = new LinkedHashMap<>();
        /** 每角色任务参数：sid → {fnId:参数JSON串}。 */
        public final Map<String, Map<String, String>> roleParams = new LinkedHashMap<>();
        /**
         * R2 Phase 2（2026-10-29）：同服多角色的**按 uid**任务开关/参数。
         * 键 {@code <sid>:<uid>}；读取优先级：本表 → 旧 sid 表（继承默认）→ 任务目录默认。
         * 旧表保留作为“该区服默认配置”，旧 App/回滚继续可用；本表由 sync 按角色 uid 落库。
         */
        public final Map<String, Map<String, Boolean>> roleConfigByUid = new LinkedHashMap<>();
        public final Map<String, Map<String, String>> roleParamsByUid = new LinkedHashMap<>();
        /** 配置默认值迁移版本（0=旧客户端/未迁移；达到 CURRENT 后不再强制迁移）。 */
        public int configDefaultsVersion;
        /** 账号池角色条目：roleKey(sid:uid) → 条目（M1 2026-10-21，服务器侧意图态）。 */
        public final Map<String, RoleEntry> roles = new LinkedHashMap<>();
        // ---------- 顶号恢复策略（P1 2026-10-21，账号级，作用于该账号全部角色） ----------
        /** 允许意外下线后自动上线（默认 true）。 */
        public boolean takeoverRecoveryEnabled = true;
        /** 顶号后等待秒数（默认 1800，范围 1..86400；API 以分钟收发）。 */
        public int takeoverInitialDelaySeconds = 1800;
        /** 重试间隔秒数（默认 5，范围 1..300）。 */
        public int takeoverRetryIntervalSeconds = 5;
        /** 连续尝试次数（默认 3，范围 0..10；0=不自动顶号，账号保持离线）。 */
        public int takeoverMaxAttempts = 3;
        /** 让号冷却分钟（默认 15，仅允许 15/30/60/120/1440）。 */
        public int takeoverCooldownMinutes = 15;
        /**
         * 战争通知监视名单（2026-10-22）：账号级 watch targets，服务器侧自有数据——
         * App sync 不携带，replaceAll 时跨 sync 保留（同 takeoverRecovery* 策略）。
         */
        public final List<WatchTarget> watchTargets = new ArrayList<>();

        public Account(String fpid) {
            this.fpid = fpid;
        }
    }

    /**
     * 单个被监视敌人（战争通知模块）：uid 必填（游戏内玩家 uid），name/kid/note 可选。
     * enabled=false 保留条目但暂停监视。
     */
    public static final class WatchTarget {
        public String uid;
        public String name = "";
        public String kid = "";
        public String note = "";
        public boolean enabled = true;

        public WatchTarget(String uid) {
            this.uid = uid == null ? "" : uid;
        }

        WatchTarget copy() {
            WatchTarget c = new WatchTarget(uid);
            c.name = name;
            c.kid = kid;
            c.note = note;
            c.enabled = enabled;
            return c;
        }
    }

    /**
     * 战争通知全局配置（vault.json 根 "watch" 对象；服务器侧自有，与账号无关）。
     * wecom 静默时段为小时 [quietStart, quietEnd)，跨夜（start&gt;end）按环绕判定；
     * -1 = 未启用静默。静默只抑制企业微信推送，事件照常入库。
     */
    public static final class WatchConfig {
        /** 监视总开关：关闭时推送不解析、不产生事件。 */
        public boolean enabled;
        /** 企业微信机器人推送开关（还需配置 webhookUrl）。 */
        public boolean wecomEnabled = true;
        /** 企业微信群机器人 webhook 地址。 */
        public String webhookUrl = "";
        /** 同一 target+action 的通知冷却秒数（60..3600，默认 300）。 */
        public int cooldownSeconds = 300;
        /** 静默开始小时（-1 = 关闭静默）。 */
        public int quietStart = -1;
        /** 静默结束小时（0..23，与 quietStart 配对）。 */
        public int quietEnd = 8;
        /** 名单生效角色（uid）；空 = 该账号下所有角色都生效（2026-11-03 App 树状多选）。 */
        public List<Long> roleUidScope = new ArrayList<>();

        WatchConfig copy() {
            WatchConfig c = new WatchConfig();
            c.enabled = enabled;
            c.wecomEnabled = wecomEnabled;
            c.webhookUrl = webhookUrl;
            c.cooldownSeconds = cooldownSeconds;
            c.quietStart = quietStart;
            c.quietEnd = quietEnd;
            c.roleUidScope = new ArrayList<>(roleUidScope == null
                    ? java.util.Collections.emptyList() : roleUidScope);
            return c;
        }
    }

    /**
     * 告警中枢关注名单条目（vault.json 根 alert.watchlist；fid 为主键，玩家肉眼可见）。
     * uid 是内部解析缓存；health=UNRESOLVED 表示 fid 还没解析成功。
     */
    public static final class AlertWatchEntry {
        public String fid = "";
        public long uid;
        public String nick = "";
        public String kid = "";
        public String note = "";
        public boolean enabled = true;
        public String health = "UNRESOLVED";
        public String source = "USER";
        public String legacyRaw = "";
        public long resolvedAt;

        public AlertWatchEntry() {
        }

        public AlertWatchEntry(String fid) {
            this.fid = fid == null ? "" : fid.trim();
        }

        AlertWatchEntry copy() {
            AlertWatchEntry c = new AlertWatchEntry(fid);
            c.uid = uid;
            c.nick = nick;
            c.kid = kid;
            c.note = note;
            c.enabled = enabled;
            c.health = health;
            c.source = source;
            c.legacyRaw = legacyRaw;
            c.resolvedAt = resolvedAt;
            return c;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("fid", fid);
            o.put("uid", uid);
            o.put("nick", nick);
            o.put("kid", kid);
            o.put("note", note);
            o.put("enabled", enabled);
            o.put("health", health);
            o.put("source", source);
            o.put("legacyRaw", legacyRaw);
            o.put("resolvedAt", resolvedAt);
            return o;
        }

        static AlertWatchEntry fromJson(JSONObject o) {
            if (o == null) return null;
            String fid = o.optString("fid", "").trim();
            if (fid.isEmpty()) return null;
            AlertWatchEntry c = new AlertWatchEntry(fid);
            c.uid = o.optLong("uid", 0L);
            c.nick = o.optString("nick", "");
            c.kid = o.optString("kid", "");
            c.note = o.optString("note", "");
            c.enabled = o.optBoolean("enabled", true);
            String h = o.optString("health", "UNRESOLVED");
            c.health = "OK".equals(h) || "STALE".equals(h) ? h : "UNRESOLVED";
            c.source = o.optString("source", "USER");
            c.legacyRaw = o.optString("legacyRaw", "");
            c.resolvedAt = o.optLong("resolvedAt", 0L);
            return c;
        }
    }

    /** watch 配置快照（深拷贝）。 */
    public WatchConfig watchConfig() {
        synchronized (lock) {
            return watchConfigLocked.copy();
        }
    }

    /**
     * 保存 watch 全局配置（先过 {@link #validateWatchConfig}；越界值就近收敛到合法域）。
     * webhookUrl 允许置空（清空配置）。
     */
    public void updateWatchConfig(boolean enabled, boolean wecomEnabled, String webhookUrl,
                                  int cooldownSeconds, int quietStart, int quietEnd) {
        synchronized (lock) {
            watchConfigLocked.enabled = enabled;
            watchConfigLocked.wecomEnabled = wecomEnabled;
            String url = webhookUrl == null ? "" : webhookUrl.trim();
            watchConfigLocked.webhookUrl = url;
            watchConfigLocked.cooldownSeconds =
                    Math.max(60, Math.min(3600, cooldownSeconds));
            watchConfigLocked.quietStart = quietStart < 0 ? -1 : Math.max(0, Math.min(23, quietStart));
            watchConfigLocked.quietEnd = Math.max(0, Math.min(23, quietEnd));
            saveLocked();
        }
    }

    /** 名单生效角色范围（uid 列表；空 = 全部角色）。 */
    public void updateWatchRoleScope(java.util.List<Long> roleUids) {
        synchronized (lock) {
            java.util.List<Long> next = new ArrayList<>();
            if (roleUids != null) {
                for (Long u : roleUids) {
                    if (u != null && u > 0L && !next.contains(u) && next.size() < 64) next.add(u);
                }
            }
            watchConfigLocked.roleUidScope = next;
            saveLocked();
        }
    }

    /**
     * webhook 额外放行前缀：环境变量 VAULT_WATCH_WEBHOOK_ALLOW（逗号分隔，容忍空格；
     * 仅用于测试/内网联调，如 http://127.0.0.1,http://192.168.）。默认空 = 仅放行企业微信官方域名。
     * 单测经同名系统属性注入（JVM 内无法改环境变量）。
     */
    static String watchWebhookAllowEnv() {
        String v = System.getProperty("VAULT_WATCH_WEBHOOK_ALLOW");
        if (v == null || v.trim().isEmpty()) v = System.getenv("VAULT_WATCH_WEBHOOK_ALLOW");
        return v == null ? "" : v.trim();
    }

    /** 官方域名或白名单（VAULT_WATCH_WEBHOOK_ALLOW 前缀命中）任一满足即放行。 */
    private static boolean webhookUrlAllowed(String url) {
        if (url.startsWith("https://qyapi.weixin.qq.com/")) return true;
        for (String p : watchWebhookAllowEnv().split(",")) {
            String pref = p.trim();
            if (!pref.isEmpty() && url.startsWith(pref)) return true;
        }
        return false;
    }

    /** watch 配置校验（API 配置保存与测试推送共用；返回 null=通过，否则中文原因）。 */
    public static String validateWatchConfig(String webhookUrl, int cooldownSeconds,
                                             int quietStart, int quietEnd) {
        String url = webhookUrl == null ? "" : webhookUrl.trim();
        if (!url.isEmpty() && !webhookUrlAllowed(url)) {
            return "webhook 地址须为 https://qyapi.weixin.qq.com/ 开头的企业微信机器人地址";
        }
        if (cooldownSeconds < 60 || cooldownSeconds > 3600) {
            return "通知冷却须在 60 到 3600 秒之间";
        }
        if (quietStart >= 0 && (quietStart > 23 || quietEnd < 0 || quietEnd > 23)) {
            return "静默时段须为 0 到 23 的小时";
        }
        return null;
    }

    private final Path file;
    private final Path backup;
    private final List<Account> accounts = new ArrayList<>();
    private String takeoverPolicy = "human_first";
    /** 战争通知全局配置（watch 配置节）。 */
    private WatchConfig watchConfigLocked = new WatchConfig();
    /** 告警中枢关注名单（vault.json 根 alert.watchlist；fid 主键，全局一份）。 */
    private final List<AlertWatchEntry> alertWatchlist = new ArrayList<>();
    /** 迁移时反查不到 fid 的旧条目（等用户补填 fid）。 */
    private final List<AlertWatchEntry> alertUnresolved = new ArrayList<>();
    /** 迁移是否已执行（幂等标记）。 */
    private boolean alertMigrated;
    private final Object lock = new Object();

    public VaultStore(Path dataDir) {
        this.file = dataDir.resolve("vault.json");
        this.backup = dataDir.resolve("vault.json.bak");
    }

    public void load() {
        synchronized (lock) {
            accounts.clear();
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                    JSONArray arr = root.optJSONArray("accounts");
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        Account a = new Account(o.optString("fpid", ""));
                        if (a.fpid.isEmpty()) continue;
                        a.token = o.optString("token", "");
                        a.label = o.optString("label", "");
                        a.group = o.optString("group", "");
                        a.keep = o.optBoolean("keep", false);
                        String sid = o.optString("activeSid", "");
                        a.activeSid = sid.isEmpty() ? null : sid;
                        String auid = o.optString("activeUid", "");
                        a.activeUid = auid.isEmpty() ? null : auid;
                        String id = o.optString("identity", "");
                        a.identity = id.isEmpty() ? null : id;
                        a.proxy = o.optString("proxy", "");
                        if (a.proxy.isEmpty()) a.proxy = null;
                        JSONObject roles = o.optJSONObject("roleIdentity");
                        if (roles != null) {
                            for (Iterator<String> it = roles.keys(); it.hasNext(); ) {
                                String k = it.next();
                                String v = roles.optString(k, "");
                                if (!v.isEmpty()) a.roleIdentity.put(k, v);
                            }
                        }
                        a.auto = o.optBoolean("auto", false);
                        a.windowStart = o.optInt("windowStart", 5);
                        a.windowEnd = o.optInt("windowEnd", 9);
                        a.configDefaultsVersion = o.optInt("configDefaultsVersion", 0);
                        JSONObject cfgs = o.optJSONObject("roleConfig");
                        if (cfgs != null) {
                            for (Iterator<String> it = cfgs.keys(); it.hasNext(); ) {
                                String cfgSid = it.next();
                                JSONObject cm = cfgs.optJSONObject(cfgSid);
                                if (cm == null) continue;
                                Map<String, Boolean> cfg = new LinkedHashMap<>();
                                for (Iterator<String> ck = cm.keys(); ck.hasNext(); ) {
                                    String fn = ck.next();
                                    cfg.put(fn, cm.optBoolean(fn, false));
                                }
                                a.roleConfig.put(cfgSid, cfg);
                            }
                        }
                        JSONObject prms = o.optJSONObject("roleParams");
                        if (prms != null) {
                            for (Iterator<String> it = prms.keys(); it.hasNext(); ) {
                                String prmSid = it.next();
                                JSONObject pm = prms.optJSONObject(prmSid);
                                if (pm == null) continue;
                                Map<String, String> pp = new LinkedHashMap<>();
                                for (Iterator<String> pk = pm.keys(); pk.hasNext(); ) {
                                    String fn = pk.next();
                                    pp.put(fn, pm.optJSONObject(fn) == null
                                            ? "" : pm.optJSONObject(fn).toString());
                                }
                                a.roleParams.put(prmSid, pp);
                            }
                        }
                        // R2 Phase 2：按角色 uid 的开关/参数（键 sid:uid；旧表保留为区服默认）
                        JSONObject cfgsUid = o.optJSONObject("roleConfigByUid");
                        if (cfgsUid != null) {
                            for (Iterator<String> it = cfgsUid.keys(); it.hasNext(); ) {
                                String key = it.next();
                                JSONObject cm = cfgsUid.optJSONObject(key);
                                if (cm == null) continue;
                                Map<String, Boolean> cfg = new LinkedHashMap<>();
                                for (Iterator<String> ck = cm.keys(); ck.hasNext(); ) {
                                    String fn = ck.next();
                                    cfg.put(fn, cm.optBoolean(fn, false));
                                }
                                a.roleConfigByUid.put(key, cfg);
                            }
                        }
                        JSONObject prmsUid = o.optJSONObject("roleParamsByUid");
                        if (prmsUid != null) {
                            for (Iterator<String> it = prmsUid.keys(); it.hasNext(); ) {
                                String key = it.next();
                                JSONObject pm = prmsUid.optJSONObject(key);
                                if (pm == null) continue;
                                Map<String, String> pp = new LinkedHashMap<>();
                                for (Iterator<String> pk = pm.keys(); pk.hasNext(); ) {
                                    String fn = pk.next();
                                    pp.put(fn, pm.optJSONObject(fn) == null
                                            ? "" : pm.optJSONObject(fn).toString());
                                }
                                a.roleParamsByUid.put(key, pp);
                            }
                        }
                        JSONObject rls = o.optJSONObject("roles");
                        if (rls != null) {
                            for (Iterator<String> it = rls.keys(); it.hasNext(); ) {
                                String key = it.next();
                                JSONObject ro = rls.optJSONObject(key);
                                if (ro == null) continue;
                                RoleEntry e = new RoleEntry(ro.optString("sid", ""),
                                        ro.optString("uid", ""));
                                e.name = ro.optString("name", "");
                                e.level = ro.optLong("level", 0);
                                e.power = ro.optLong("power", 0);
                                e.enabled = ro.optBoolean("enabled", true);
                                e.desiredOnline = ro.optBoolean("desiredOnline", false);
                                e.desiredAt = ro.optLong("desiredAt", 0);
                                String rs = ro.optString("runState", RUN_STOPPED);
                                e.runState = RUN_PAUSED.equals(rs) || RUN_RUNNING.equals(rs)
                                        ? rs : RUN_STOPPED;
                                // P1 扩展（旧数据缺字段 → 缺省 0，加载容错）
                                e.pauseResumeAt = ro.optLong("pauseResumeAt", 0);
                                a.roles.put(roleKey(e.sid, e.uid), e);
                            }
                        }
                        // P1 恢复策略（旧数据缺字段 → 默认值；非法负数/零值兜底回默认）
                        a.takeoverRecoveryEnabled = o.optBoolean("takeoverRecoveryEnabled", true);
                        a.takeoverInitialDelaySeconds = clampInt(
                                o.optInt("takeoverInitialDelaySeconds", 1800), 1, 86400, 1800);
                        a.takeoverRetryIntervalSeconds = clampInt(
                                o.optInt("takeoverRetryIntervalSeconds", 5), 1, 300, 5);
                        a.takeoverMaxAttempts = clampInt(
                                o.optInt("takeoverMaxAttempts", 3), 0, 10, 3);
                        a.takeoverCooldownMinutes = parseCooldown(
                                o.optInt("takeoverCooldownMinutes", 15));
                        // 战争通知监视名单（服务器侧自有；旧数据无此节 → 空表）
                        JSONArray wts = o.optJSONArray("watchTargets");
                        if (wts != null) {
                            for (int wi = 0; wi < wts.length(); wi++) {
                                JSONObject wt = wts.optJSONObject(wi);
                                if (wt == null) continue;
                                String wuid = wt.optString("uid", "");
                                if (wuid.isEmpty()) continue;
                                WatchTarget t = new WatchTarget(wuid);
                                t.name = wt.optString("name", "");
                                t.kid = wt.optString("kid", "");
                                t.note = wt.optString("note", "");
                                t.enabled = wt.optBoolean("enabled", true);
                                a.watchTargets.add(t);
                            }
                        }
                        accounts.add(a);
                    }
                    String policy = root.optString("takeoverPolicy", "");
                    if (!policy.isEmpty()) takeoverPolicy = policy;
                    JSONObject wc = root.optJSONObject("watch");
                    if (wc != null) {
                        watchConfigLocked.enabled = wc.optBoolean("enabled", false);
                        watchConfigLocked.wecomEnabled = wc.optBoolean("wecomEnabled", true);
                        watchConfigLocked.webhookUrl = wc.optString("webhookUrl", "");
                        watchConfigLocked.cooldownSeconds =
                                clampInt(wc.optInt("cooldownSeconds", 300), 60, 3600, 300);
                        int qs = wc.optInt("quietStart", -1);
                        watchConfigLocked.quietStart = qs < 0 ? -1 : clampInt(qs, 0, 23, -1);
                        watchConfigLocked.quietEnd = clampInt(wc.optInt("quietEnd", 8), 0, 23, 8);
                        watchConfigLocked.roleUidScope = new ArrayList<>();
                        JSONArray ru = wc.optJSONArray("roleUidScope");
                        if (ru != null) {
                            for (int i = 0; i < ru.length() && i < 64; i++) {
                                long u = ru.optLong(i, 0L);
                                if (u > 0L) watchConfigLocked.roleUidScope.add(u);
                            }
                        }
                    }
                    JSONObject al = root.optJSONObject("alert");
                    if (al != null) {
                        alertMigrated = al.optBoolean("migrated", false);
                        alertWatchlist.clear();
                        JSONArray wl = al.optJSONArray("watchlist");
                        if (wl != null) {
                            for (int i = 0; i < wl.length(); i++) {
                                AlertWatchEntry e = AlertWatchEntry.fromJson(wl.optJSONObject(i));
                                if (e != null) alertWatchlist.add(e);
                            }
                        }
                        alertUnresolved.clear();
                        JSONArray un = al.optJSONArray("unresolved");
                        if (un != null) {
                            for (int i = 0; i < un.length(); i++) {
                                AlertWatchEntry e = AlertWatchEntry.fromJson(un.optJSONObject(i));
                                if (e != null) alertUnresolved.add(e);
                            }
                        }
                    }
                    // 2026-11-11 默认开启策略：旧库（无版本字段/低于当前版本）一次性迁移 false→新默认开。
                    boolean migrated = false;
                    for (Account a : accounts) {
                        if (a.configDefaultsVersion < CURRENT_CONFIG_DEFAULTS_VERSION) {
                            migrateConfigMaps(a.roleConfig);
                            migrateConfigMaps(a.roleConfigByUid);
                            a.configDefaultsVersion = CURRENT_CONFIG_DEFAULTS_VERSION;
                            migrated = true;
                        }
                    }
                    if (migrated) saveLocked();
                    return; // 主文件解析成功即不再看备份
                } catch (Exception e) {
                    FileLog.warn("库文件解析失败 {}：{}，尝试下一份", p.getFileName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 跨端任务（App 有、服务端 {@code ServerTasks} 目录没有）：迁移时同样按新策略默认开。
     * guardian/war 由独立引擎执行，但仍需 roleConfig 显式为 true 才进入节奏调度。
     */
    private static final java.util.Set<String> CROSS_CLIENT_DEFAULT_ON =
            new java.util.HashSet<>(java.util.Arrays.asList("guardian", "war"));

    /** 2026-11-11 默认开启策略：把旧默认 false 迁移为目录默认开（未知键保持原值）。 */
    static void migrateConfigDefaults(Map<String, Boolean> cfg) {
        if (cfg == null || cfg.isEmpty()) return;
        for (Map.Entry<String, Boolean> e : cfg.entrySet()) {
            if (!Boolean.FALSE.equals(e.getValue())) continue;
            com.carlos.vaultserver.task.ServerTasks.TaskDef def =
                    com.carlos.vaultserver.task.ServerTasks.byId(e.getKey());
            if ((def != null && def.enabledByDefault) || CROSS_CLIENT_DEFAULT_ON.contains(e.getKey())) {
                e.setValue(Boolean.TRUE);
            }
        }
    }

    static void migrateConfigMaps(Map<String, Map<String, Boolean>> cfgs) {
        if (cfgs == null || cfgs.isEmpty()) return;
        for (Map<String, Boolean> c : cfgs.values()) migrateConfigDefaults(c);
    }

    private void saveLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : accounts) {
                JSONObject o = new JSONObject();
                o.put("fpid", a.fpid);
                o.put("token", a.token);
                o.put("label", a.label);
                o.put("group", a.group);
                o.put("keep", a.keep);
                o.put("activeSid", a.activeSid == null ? "" : a.activeSid);
                o.put("activeUid", a.activeUid == null ? "" : a.activeUid);
                o.put("identity", a.identity == null ? "" : a.identity);
                o.put("proxy", a.proxy == null ? "" : a.proxy);
                JSONObject roles = new JSONObject();
                for (Map.Entry<String, String> e : a.roleIdentity.entrySet()) {
                    roles.put(e.getKey(), e.getValue());
                }
                o.put("roleIdentity", roles);
                o.put("auto", a.auto);
                o.put("windowStart", a.windowStart);
                o.put("windowEnd", a.windowEnd);
                o.put("configDefaultsVersion", a.configDefaultsVersion);
                JSONObject cfgs = new JSONObject();
                for (Map.Entry<String, Map<String, Boolean>> e : a.roleConfig.entrySet()) {
                    JSONObject cm = new JSONObject();
                    for (Map.Entry<String, Boolean> ce : e.getValue().entrySet()) {
                        cm.put(ce.getKey(), ce.getValue());
                    }
                    cfgs.put(e.getKey(), cm);
                }
                o.put("roleConfig", cfgs);
                JSONObject prms = new JSONObject();
                for (Map.Entry<String, Map<String, String>> e : a.roleParams.entrySet()) {
                    JSONObject pm = new JSONObject();
                    for (Map.Entry<String, String> pe : e.getValue().entrySet()) {
                        if (!pe.getValue().isEmpty()) {
                            try {
                                pm.put(pe.getKey(), new JSONObject(pe.getValue()));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    prms.put(e.getKey(), pm);
                }
                o.put("roleParams", prms);
                // R2 Phase 2：按角色 uid 的开关/参数（仅写非空，vault.json 兼容旧读方忽略未知字段）
                JSONObject cfgsUid = new JSONObject();
                for (Map.Entry<String, Map<String, Boolean>> e : a.roleConfigByUid.entrySet()) {
                    JSONObject cm = new JSONObject();
                    for (Map.Entry<String, Boolean> ce : e.getValue().entrySet()) {
                        cm.put(ce.getKey(), ce.getValue());
                    }
                    cfgsUid.put(e.getKey(), cm);
                }
                o.put("roleConfigByUid", cfgsUid);
                JSONObject prmsUid = new JSONObject();
                for (Map.Entry<String, Map<String, String>> e : a.roleParamsByUid.entrySet()) {
                    JSONObject pm = new JSONObject();
                    for (Map.Entry<String, String> pe : e.getValue().entrySet()) {
                        if (!pe.getValue().isEmpty()) {
                            try {
                                pm.put(pe.getKey(), new JSONObject(pe.getValue()));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    prmsUid.put(e.getKey(), pm);
                }
                o.put("roleParamsByUid", prmsUid);
                JSONObject rls = new JSONObject();
                for (Map.Entry<String, RoleEntry> e : a.roles.entrySet()) {
                    RoleEntry r = e.getValue();
                    JSONObject ro = new JSONObject();
                    ro.put("sid", r.sid);
                    ro.put("uid", r.uid);
                    ro.put("name", r.name);
                    ro.put("level", r.level);
                    ro.put("power", r.power);
                    ro.put("enabled", r.enabled);
                    ro.put("desiredOnline", r.desiredOnline);
                    ro.put("desiredAt", r.desiredAt);
                    ro.put("runState", r.runState);
                    ro.put("pauseResumeAt", r.pauseResumeAt);
                    rls.put(e.getKey(), ro);
                }
                o.put("roles", rls);
                o.put("takeoverRecoveryEnabled", a.takeoverRecoveryEnabled);
                o.put("takeoverInitialDelaySeconds", a.takeoverInitialDelaySeconds);
                o.put("takeoverRetryIntervalSeconds", a.takeoverRetryIntervalSeconds);
                o.put("takeoverMaxAttempts", a.takeoverMaxAttempts);
                o.put("takeoverCooldownMinutes", a.takeoverCooldownMinutes);
                JSONArray wts = new JSONArray();
                for (WatchTarget t : a.watchTargets) {
                    JSONObject wt = new JSONObject();
                    wt.put("uid", t.uid);
                    wt.put("name", t.name);
                    wt.put("kid", t.kid);
                    wt.put("note", t.note);
                    wt.put("enabled", t.enabled);
                    wts.put(wt);
                }
                o.put("watchTargets", wts);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("accounts", arr);
            root.put("takeoverPolicy", takeoverPolicy);
            JSONObject wc = new JSONObject();
            wc.put("enabled", watchConfigLocked.enabled);
            wc.put("wecomEnabled", watchConfigLocked.wecomEnabled);
            wc.put("webhookUrl", watchConfigLocked.webhookUrl);
            wc.put("cooldownSeconds", watchConfigLocked.cooldownSeconds);
            wc.put("quietStart", watchConfigLocked.quietStart);
            wc.put("quietEnd", watchConfigLocked.quietEnd);
            JSONArray scope = new JSONArray();
            for (Long u : watchConfigLocked.roleUidScope) scope.put(u);
            wc.put("roleUidScope", scope);
            root.put("watch", wc);
            JSONObject al = new JSONObject();
            JSONArray wl = new JSONArray();
            for (AlertWatchEntry e : alertWatchlist) wl.put(e.toJson());
            al.put("watchlist", wl);
            JSONArray un = new JSONArray();
            for (AlertWatchEntry e : alertUnresolved) un.put(e.toJson());
            al.put("unresolved", un);
            al.put("migrated", alertMigrated);
            root.put("alert", al);
            root.put("savedAt", System.currentTimeMillis());

            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            FileLog.error("库文件写入失败：{}", e.getMessage());
        }
    }

    /** 全量替换账号列表（App sync）。保留服务器学到而 App 未携带的指纹。 */
    public int replaceAll(List<Account> incoming) {
        synchronized (lock) {
            Map<String, Account> old = new LinkedHashMap<>();
            for (Account a : accounts) old.put(a.fpid, a);
            accounts.clear();
            for (Account in : incoming) {
                if (in.fpid == null || in.fpid.isEmpty()) continue;
                Account merged = new Account(in.fpid);
                merged.token = in.token;
                merged.label = in.label;
                merged.group = in.group;
                // 统一自动化：auto（整轮调度）与 keep（常驻会话）必须同值。
                // 旧客户端可能只开其中一个；任一为真都视为统一自动化开启。
                boolean automation = in.auto || in.keep;
                merged.keep = automation;
                merged.activeSid = in.activeSid;
                merged.activeUid = in.activeUid;
                merged.identity = in.identity;
                merged.proxy = in.proxy;
                // 整轮调度三件套必须跟随 incoming（曾遗漏：每次同步把 auto 抹回 false，
                // 表现为 App 端开了自动执行、服务器端永远不自动跑整轮）
                merged.auto = automation;
                merged.windowStart = in.windowStart;
                merged.windowEnd = in.windowEnd;
                Account prev = old.get(in.fpid);
                if (prev != null) {
                    for (Map.Entry<String, String> e : prev.roleIdentity.entrySet()) {
                        merged.roleIdentity.putIfAbsent(e.getKey(), e.getValue());
                    }
                    if (merged.identity == null) merged.identity = prev.identity;
                    // P1 恢复策略是服务器侧设置（App sync 不携带）：跨 sync 保留
                    merged.takeoverRecoveryEnabled = prev.takeoverRecoveryEnabled;
                    merged.takeoverInitialDelaySeconds = prev.takeoverInitialDelaySeconds;
                    merged.takeoverRetryIntervalSeconds = prev.takeoverRetryIntervalSeconds;
                    merged.takeoverMaxAttempts = prev.takeoverMaxAttempts;
                    merged.takeoverCooldownMinutes = prev.takeoverCooldownMinutes;
                    // 战争通知监视名单（服务器侧设置）：跨 sync 保留
                    merged.watchTargets.addAll(prev.watchTargets);
                }
                merged.roleIdentity.putAll(in.roleIdentity);
                merged.roleConfig.putAll(in.roleConfig);
                merged.roleParams.putAll(in.roleParams);
                // R2 Phase 2：按 uid 表——旧 App（不带）不覆盖服务器已有；新 App 按角色覆盖。
                if (prev != null) {
                    merged.roleConfigByUid.putAll(prev.roleConfigByUid);
                    merged.roleParamsByUid.putAll(prev.roleParamsByUid);
                }
                merged.roleConfigByUid.putAll(in.roleConfigByUid);
                merged.roleParamsByUid.putAll(in.roleParamsByUid);
                // 2026-11-11 默认开启策略：旧客户端（无版本/低版本）上传的 false 按新默认迁移；
                // 新客户端已自行迁移并携带 current 版本，用户手动关（false）原样保留。
                merged.configDefaultsVersion = Math.max(
                        prev != null ? prev.configDefaultsVersion : 0, in.configDefaultsVersion);
                if (merged.configDefaultsVersion < CURRENT_CONFIG_DEFAULTS_VERSION) {
                    migrateConfigMaps(merged.roleConfig);
                    migrateConfigMaps(merged.roleConfigByUid);
                    merged.configDefaultsVersion = CURRENT_CONFIG_DEFAULTS_VERSION;
                }
                mergeRolesLocked(prev, merged, in);
                accounts.add(merged);
            }
            saveLocked();
            return accounts.size();
        }
    }

    /**
     * sync 角色池合并（replaceAll 内部，持锁调用）：
     * 旧条目保留（服务器侧意图态不丢），App sync 携带的角色元数据（name/level/power/enabled）覆盖；
     * 期望态重算（2026-10-21 多角色并行）：
     * <ul>
     *   <li>keep=false：App 全停意图 —— 全部角色 desired 清空；</li>
     *   <li>keep=true 且 active 指针相对上次 sync 发生变化（v4.1.4 角色管理切角色会即时
     *       sync）：按旧单会话语义收敛 —— desired 只留 active 角色；</li>
     *   <li>keep=true 且 active 未变：active 角色 desired 置位；<b>其它角色的既有
     *       desired 保留</b>（显式 role-action 的多角色并行意图不因 sync 重算被冲掉）。</li>
     * </ul>
     */
    private static void mergeRolesLocked(Account prev, Account merged, Account in) {
        if (prev != null) {
            for (Map.Entry<String, RoleEntry> e : prev.roles.entrySet()) {
                merged.roles.put(e.getKey(), e.getValue().copy());
            }
        }
        for (RoleEntry r : in.roles.values()) {
            String key = roleKey(r.sid, r.uid);
            RoleEntry m = merged.roles.get(key);
            if (m == null) {
                m = r.copy();          // App 新带来的角色：元数据全量接收
                m.desiredOnline = false;
                m.desiredAt = 0;
                m.runState = RUN_STOPPED;
                merged.roles.put(key, m);
            } else {
                if (r.name != null && !r.name.isEmpty()) m.name = r.name;
                if (r.level > 0) m.level = r.level;
                if (r.power > 0) m.power = r.power;
                m.enabled = r.enabled;
            }
        }
        // active 角色必有条目（同服多角色直绑 / 账号池视图寻址都依赖它）
        if (merged.activeSid != null && !merged.activeSid.isEmpty()
                && merged.activeUid != null && !merged.activeUid.isEmpty()
                && !merged.roles.containsKey(roleKey(merged.activeSid, merged.activeUid))) {
            merged.roles.put(roleKey(merged.activeSid, merged.activeUid),
                    new RoleEntry(merged.activeSid, merged.activeUid));
        }
        boolean activeKnown = merged.activeSid != null && !merged.activeSid.isEmpty()
                && merged.activeUid != null && !merged.activeUid.isEmpty();
        boolean activeSwitched = prev != null
                && (!nz(prev.activeSid).equals(nz(merged.activeSid))
                    || !nz(prev.activeUid).equals(nz(merged.activeUid)));
        long now = System.currentTimeMillis();
        // R2 Phase 2（2026-10-29，用户决策「切换角色不停兄弟角色」）：VAULT_PER_ROLE=on 时，
        // App 切换当前角色（activeUid 变化）不再连带停止其它已 desired 的角色会话——
        // 双角色各自持续自动化；off（默认）保持旧「sync 切角色 = 单会话收敛」语义不变。
        boolean perRole = perRoleMode();
        for (RoleEntry m : merged.roles.values()) {
            boolean isActive = activeKnown
                    && m.sid.equals(merged.activeSid) && m.uid.equals(merged.activeUid);
            boolean wantOn = merged.keep && isActive;
            if (wantOn) {
                if (!m.desiredOnline) {
                    m.desiredAt = now;
                    if (RUN_STOPPED.equals(m.runState)) m.runState = RUN_RUNNING;
                }
                // 已 desired 的保留 runState（RUNNING/PAUSED 都不因 sync 抹掉）
                m.desiredOnline = true;
            } else if (!merged.keep
                    || (!perRole && activeSwitched && m.desiredOnline && !isActive)) {
                if (m.desiredOnline || !RUN_STOPPED.equals(m.runState)) m.runState = RUN_STOPPED;
                m.desiredOnline = false;
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Phase 2 灰度开关（与 task.RoundScheduler.perRoleEnabled 同源语义）：
     * on = 全任务按角色并行；此时 App 切换当前角色不连带停止其它已 desired 的角色会话。
     * 测试可用 {@link #setPerRoleModeForTest(Boolean)} 注入。
     */
    private static volatile Boolean perRoleOverrideForTest = null;

    static boolean perRoleMode() {
        Boolean o = perRoleOverrideForTest;
        if (o != null) return o;
        String v = System.getenv("VAULT_PER_ROLE");
        return v != null && ("on".equalsIgnoreCase(v) || "1".equals(v)
                || "true".equalsIgnoreCase(v));
    }

    /** 测试注入（null=恢复读 env）。 */
    static void setPerRoleModeForTest(Boolean v) {
        perRoleOverrideForTest = v;
    }

    /** 解析可寻址角色：精确 (sid,uid) 匹配；回落 active 角色（自动建条目）。找不到返回 null。 */
    public RoleEntry resolveRole(String fpid, String sid, String uid) {
        if (sid == null || sid.isEmpty()) return null;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return null;
            String key = roleKey(sid, uid == null ? "" : uid);
            RoleEntry e = a.roles.get(key);
            if (e == null && a.activeSid != null && a.activeSid.equals(sid)
                    && a.activeUid != null && a.activeUid.equals(uid == null ? "" : uid)) {
                e = new RoleEntry(sid, uid == null ? "" : uid);
                a.roles.put(key, e);
                saveLocked();
            }
            return e == null ? null : e.copy();
        }
    }

    /** 角色条目快照（深拷贝；无账号返回空表）。 */
    public List<RoleEntry> roles(String fpid) {
        synchronized (lock) {
            List<RoleEntry> out = new ArrayList<>();
            Account a = findLocked(fpid);
            if (a != null) {
                for (RoleEntry e : a.roles.values()) out.add(e.copy());
            }
            return out;
        }
    }

    /** 确保角色条目存在（登录学到/同步种子用）；返回条目快照。 */
    public RoleEntry ensureRole(String fpid, String sid, String uid) {
        if (sid == null || sid.isEmpty()) return null;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return null;
            String key = roleKey(sid, uid);
            RoleEntry e = a.roles.get(key);
            if (e == null) {
                e = new RoleEntry(sid, uid);
                a.roles.put(key, e);
                saveLocked();
            }
            return e.copy();
        }
    }

    /** 登录学到角色元数据（不覆盖已有非空名称）。 */
    public void learnRoleMeta(String fpid, String sid, String uid, String name, long level, long power) {
        if (sid == null || sid.isEmpty() || uid == null || uid.isEmpty()) return;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return;
            RoleEntry e = a.roles.get(roleKey(sid, uid));
            if (e == null) {
                e = new RoleEntry(sid, uid);
                a.roles.put(roleKey(sid, uid), e);
            }
            if (name != null && !name.isEmpty() && (e.name == null || e.name.isEmpty())) e.name = name;
            if (level > 0) e.level = level;
            if (power > 0) e.power = power;
            saveLocked();
        }
    }

    /**
     * 显式登录意图（角色级，2026-10-21 多角色并行）：目标角色 desiredOnline（desiredAt=now）、
     * active 指向目标角色、keep/auto=true；同 fpid 其它角色的期望态<b>保留</b>——
     * login 不停止兄弟角色，各角色由自己的显式动作管理。
     */
    public boolean applyRoleLogin(String fpid, String sid, String uid) {
        return applyRoleLoginInternal(fpid, sid, uid, false);
    }

    /**
     * 账号级登录收敛（automation「登录并运行自动化」用，对齐 sync 切角色旧语义）：
     * 目标角色 desiredOnline，同 fpid 其它角色 desired 一并清空（单会话收敛，
     * v4.1.4 账号级登录语义不变；角色级并行上线请走 {@link #applyRoleLogin}）。
     */
    public boolean applyRoleLoginExclusive(String fpid, String sid, String uid) {
        return applyRoleLoginInternal(fpid, sid, uid, true);
    }

    private boolean applyRoleLoginInternal(String fpid, String sid, String uid, boolean exclusive) {
        if (sid == null || sid.isEmpty()) return false;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            String key = roleKey(sid, uid);
            RoleEntry target = a.roles.get(key);
            if (target == null) {
                target = new RoleEntry(sid, uid);
                a.roles.put(key, target);
            }
            long now = System.currentTimeMillis();
            for (RoleEntry e : a.roles.values()) {
                if (e == target) {
                    e.desiredOnline = true;
                    e.desiredAt = now;
                    e.runState = RUN_RUNNING;
                    e.pauseResumeAt = 0;   // 显式 login 立即清除暂停恢复计划（契约 §二.7）
                } else if (exclusive && e.desiredOnline) {
                    e.desiredOnline = false;
                    e.runState = RUN_STOPPED;
                    e.pauseResumeAt = 0;
                }
            }
            a.activeSid = sid;
            a.activeUid = uid == null ? "" : uid;
            a.keep = true;
            a.auto = true;
            saveLocked();
            return true;
        }
    }

    /**
     * 显式停止意图：目标角色下线（runState=STOPPED）；preserveKeep=false（stop）且该账号
     * 已无 desired 角色时关闭 keep/auto（不自动恢复），logout 保留 keep。
     */
    public boolean applyRoleStop(String fpid, String sid, String uid, boolean preserveKeep) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
        RoleEntry e = a.roles.get(roleKey(sid, uid));
        if (e != null) {
            e.desiredOnline = false;
            e.runState = RUN_STOPPED;
            e.pauseResumeAt = 0;   // stop/logout 清空暂停恢复计划（契约 §二.7）
        }
            if (!preserveKeep) {
                boolean anyDesired = false;
                for (RoleEntry r : a.roles.values()) {
                    if (r.desiredOnline) {
                        anyDesired = true;
                        break;
                    }
                }
                if (!anyDesired) {
                    a.keep = false;
                    a.auto = false;
                }
            }
            saveLocked();
            return true;
        }
    }

    /** 会话级运行态持久化（PAUSED/RUNNING；重启恢复暂停态用）。 */
    public void applyRoleRunState(String fpid, String sid, String uid, String runState) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return;
            RoleEntry e = a.roles.get(roleKey(sid, uid));
            if (e == null) return;
            e.runState = RUN_PAUSED.equals(runState) ? RUN_PAUSED
                    : (RUN_STOPPED.equals(runState) ? RUN_STOPPED : RUN_RUNNING);
            if (!RUN_PAUSED.equals(e.runState)) e.pauseResumeAt = 0;
            saveLocked();
        }
    }

    // ---------- P1（2026-10-21）：暂停自动恢复 / 角色管理增强 ----------

    /** 冷却时长合法值（线上 GameAccountRecoveryPolicy.ALLOWED_COOLDOWN_MINUTES 同款）。 */
    public static final int[] ALLOWED_COOLDOWN_MINUTES = {15, 30, 60, 120, 1440};

    private static int clampInt(int v, int min, int max, int def) {
        return v >= min && v <= max ? v : def;
    }

    private static int parseCooldown(int v) {
        for (int allowed : ALLOWED_COOLDOWN_MINUTES) {
            if (allowed == v) return v;
        }
        return 15;
    }

    /**
     * 恢复设置校验（分钟域，对齐线上文案；通过返回 null，否则中文原因）。
     * API 收 initialDelayMinutes（1..1440），内部换算为秒持久化。
     */
    public static String validateRecovery(int initialDelayMinutes, int retryIntervalSeconds,
                                          int maxAttempts, int cooldownMinutes) {
        if (initialDelayMinutes < 1 || initialDelayMinutes > 1440) {
            return "顶号后等待必须在 1 到 1440 分钟之间";
        }
        if (retryIntervalSeconds < 1 || retryIntervalSeconds > 300) {
            return "重试间隔秒数必须在 1 到 300 之间";
        }
        if (maxAttempts < 0 || maxAttempts > 10) {
            return "连续尝试次数必须在 0 到 10 之间";
        }
        for (int allowed : ALLOWED_COOLDOWN_MINUTES) {
            if (allowed == cooldownMinutes) return null;
        }
        return "冷却时长只支持 15 分钟、30 分钟、1 小时、2 小时或 24 小时";
    }

    /** 保存账号级恢复设置（调用方先过 {@link #validateRecovery}）；账号不存在返回 false。 */
    public boolean updateRecoverySettings(String fpid, boolean enabled, int initialDelaySeconds,
                                          int retryIntervalSeconds, int maxAttempts,
                                          int cooldownMinutes) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            a.takeoverRecoveryEnabled = enabled;
            a.takeoverInitialDelaySeconds = clampInt(initialDelaySeconds, 1, 86400, 1800);
            a.takeoverRetryIntervalSeconds = clampInt(retryIntervalSeconds, 1, 300, 5);
            a.takeoverMaxAttempts = clampInt(maxAttempts, 0, 10, 3);
            a.takeoverCooldownMinutes = parseCooldown(cooldownMinutes);
            saveLocked();
            return true;
        }
    }

    /** 改名回写（覆盖旧名；在线协议 result.nickName 为准）。 */
    public boolean setRoleName(String fpid, String sid, String uid, String name) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            RoleEntry e = a.roles.get(roleKey(sid, uid));
            if (e == null) return false;
            e.name = name == null ? "" : name;
            saveLocked();
            return true;
        }
    }

    /**
     * 从账号工作区移除角色（P1，仅工具内移除，不删游戏内角色）；
     * 被移除的是 active 角色时 activeSid/activeUid 置空。角色不存在返回 false。
     */
    public boolean removeRole(String fpid, String sid, String uid) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            RoleEntry removed = a.roles.remove(roleKey(sid, uid));
            if (removed == null) return false;
            if (a.activeSid != null && a.activeSid.equals(sid)
                    && a.activeUid != null && a.activeUid.equals(uid == null ? "" : uid)) {
                a.activeSid = null;
                a.activeUid = null;
            }
            saveLocked();
            return true;
        }
    }

    /** 启用/收纳角色（P1 op enabled 持久化）。 */
    public boolean setRoleEnabled(String fpid, String sid, String uid, boolean enabled) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            RoleEntry e = a.roles.get(roleKey(sid, uid));
            if (e == null) return false;
            e.enabled = enabled;
            saveLocked();
            return true;
        }
    }

    /**
     * 暂停/重排暂停恢复计划：runState=PAUSED、pauseResumeAt=指定时刻；
     * desiredOnline 不动（暂停保留期望在线，到期自动恢复）。
     */
    public void applyRolePauseSchedule(String fpid, String sid, String uid, long pauseResumeAt) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return;
            RoleEntry e = a.roles.get(roleKey(sid, uid));
            if (e == null) return;
            e.runState = RUN_PAUSED;
            e.pauseResumeAt = Math.max(0, pauseResumeAt);
            saveLocked();
        }
    }

    /** 恢复成功/显式恢复：清 PAUSED 与恢复计划（desiredOnline 不变）。 */
    public void clearRolePause(String fpid, String sid, String uid) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return;
            RoleEntry e = a.roles.get(roleKey(sid, uid));
            if (e == null) return;
            if (RUN_PAUSED.equals(e.runState)) e.runState = RUN_RUNNING;
            e.pauseResumeAt = 0;
            saveLocked();
        }
    }

    // ---------- 告警中枢：关注名单（fid 主键，全局一份；设计 §9） ----------

    /** 关注名单快照（深拷贝）。 */
    public List<AlertWatchEntry> alertWatchlist() {
        synchronized (lock) {
            List<AlertWatchEntry> out = new ArrayList<>();
            for (AlertWatchEntry e : alertWatchlist) out.add(e.copy());
            return out;
        }
    }

    /** 反查不到 fid 的旧条目（迁移待处理）。 */
    public List<AlertWatchEntry> alertUnresolved() {
        synchronized (lock) {
            List<AlertWatchEntry> out = new ArrayList<>();
            for (AlertWatchEntry e : alertUnresolved) out.add(e.copy());
            return out;
        }
    }

    public boolean alertMigrated() {
        synchronized (lock) {
            return alertMigrated;
        }
    }

    public void markAlertMigrated() {
        synchronized (lock) {
            alertMigrated = true;
            saveLocked();
        }
    }

    /**
     * 新增/更新一条关注名单（fid 为主键）。返回 true=有新变化并已落盘。
     * fid 必须为纯数字；同 fid 已存在则更新 uid/nick/kid/note/enabled/health 等。
     */
    public boolean upsertAlertWatch(AlertWatchEntry in) {
        if (in == null) return false;
        String fid = in.fid == null ? "" : in.fid.trim();
        if (fid.isEmpty() || !fid.matches("\\d+")) return false;
        synchronized (lock) {
            AlertWatchEntry cur = null;
            for (AlertWatchEntry e : alertWatchlist) {
                if (fid.equals(e.fid)) { cur = e; break; }
            }
            if (cur == null) {
                cur = new AlertWatchEntry(fid);
                cur.uid = in.uid;
                cur.nick = in.nick == null ? "" : in.nick;
                cur.kid = in.kid == null ? "" : in.kid;
                cur.note = in.note == null ? "" : in.note;
                cur.enabled = in.enabled;
                cur.health = in.health == null ? "UNRESOLVED" : in.health;
                cur.source = in.source == null ? "USER" : in.source;
                cur.legacyRaw = in.legacyRaw == null ? "" : in.legacyRaw;
                cur.resolvedAt = in.resolvedAt;
                alertWatchlist.add(cur);
            } else {
                if (in.uid > 0L) {
                    cur.uid = in.uid;
                    cur.health = in.health == null ? "OK" : in.health;
                    cur.resolvedAt = System.currentTimeMillis();
                }
                if (in.nick != null && !in.nick.isEmpty()) cur.nick = in.nick;
                if (in.kid != null && !in.kid.isEmpty()) cur.kid = in.kid;
                if (in.note != null && !in.note.isEmpty()) cur.note = in.note;
                cur.enabled = in.enabled;
                if (in.source != null && !in.source.isEmpty()) cur.source = in.source;
                if (in.legacyRaw != null && !in.legacyRaw.isEmpty()) cur.legacyRaw = in.legacyRaw;
            }
            final String legacy = cur.legacyRaw;
            if (!legacy.isEmpty()) {
                alertUnresolved.removeIf(u -> legacy.equals(u.legacyRaw));
            }
            saveLocked();
            return true;
        }
    }

    public boolean removeAlertWatch(String fid) {
        String f = fid == null ? "" : fid.trim();
        if (f.isEmpty()) return false;
        synchronized (lock) {
            boolean removed = alertWatchlist.removeIf(e -> f.equals(e.fid));
            if (removed) saveLocked();
            return removed;
        }
    }

    /** 用户补填 fid 成功后，按 uid 清掉迁移期 unresolved 占位。 */
    public void removeAlertUnresolvedByUid(long uid) {
        if (uid <= 0L) return;
        synchronized (lock) {
            boolean removed = alertUnresolved.removeIf(e -> e.uid == uid);
            if (removed) saveLocked();
        }
    }

    /** 记录一条无法反查 fid 的旧条目（迁移用；legacyRaw 存原 uid/坐标）。 */
    public void addAlertUnresolved(AlertWatchEntry e) {
        if (e == null) return;
        synchronized (lock) {
            alertUnresolved.add(e.copy());
            saveLocked();
        }
    }

    // ---------- 战争通知：监视名单 CRUD（2026-10-22） ----------

    /** 账号监视名单快照（深拷贝；无账号返回 null 以区分"不存在"与"空名单"）。 */
    public List<WatchTarget> watchTargets(String fpid) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return null;
            List<WatchTarget> out = new ArrayList<>();
            for (WatchTarget t : a.watchTargets) out.add(t.copy());
            return out;
        }
    }

    /**
     * 新增监视目标（uid 必填且未重复）。返回值：0=成功 1=账号不存在 2=uid 非法 3=已存在。
     */
    public int addWatchTarget(String fpid, String uid, String name, String kid, String note) {
        String u = normUid(uid);
        if (u == null) return 2;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return 1;
            for (WatchTarget t : a.watchTargets) {
                if (t.uid.equals(u)) return 3;
            }
            WatchTarget t = new WatchTarget(u);
            t.name = name == null ? "" : name.trim();
            t.kid = kid == null ? "" : kid.trim();
            t.note = note == null ? "" : note.trim();
            a.watchTargets.add(t);
            saveLocked();
            return 0;
        }
    }

    /**
     * 更新监视目标（uid 定位；name/kid/note/enabled 传 null = 不变）。
     * 返回 false = 账号或目标不存在。
     */
    public boolean updateWatchTarget(String fpid, String uid, String name, String kid,
                                     String note, Boolean enabled) {
        String u = normUid(uid);
        if (u == null) return false;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            WatchTarget t = findTargetLocked(a, u);
            if (t == null) return false;
            if (name != null) t.name = name.trim();
            if (kid != null) t.kid = kid.trim();
            if (note != null) t.note = note.trim();
            if (enabled != null) t.enabled = enabled;
            saveLocked();
            return true;
        }
    }

    /** 删除监视目标。返回 false = 账号或目标不存在。 */
    public boolean removeWatchTarget(String fpid, String uid) {
        String u = normUid(uid);
        if (u == null) return false;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return false;
            boolean removed = a.watchTargets.removeIf(t -> t.uid.equals(u));
            if (removed) saveLocked();
            return removed;
        }
    }

    /** uid 规范化（纯数字 &gt;0）；非法返回 null。 */
    private static String normUid(String uid) {
        if (uid == null) return null;
        String u = uid.trim();
        if (u.isEmpty()) return null;
        try {
            long v = Long.parseLong(u);
            return v > 0 ? String.valueOf(v) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static WatchTarget findTargetLocked(Account a, String uid) {
        for (WatchTarget t : a.watchTargets) {
            if (t.uid.equals(uid)) return t;
        }
        return null;
    }

    /**
     * 全局监视命中（2026-11-04）：跨账号查找 enabled 的监视目标。
     * 背景：战争通知按「收到推送的会话账号」查名单，但观察点的整图/保护查询会分摊到其它账号会话，
     * 敌方行军帧一旦从非名单账号的会话回来就会被静默丢弃（生产 17:2x 监视目标打人漏报）。
     */
    public WatchTarget matchWatchedAnyAccount(long ownerUid) {
        if (ownerUid <= 0) return null;
        String u = String.valueOf(ownerUid);
        synchronized (lock) {
            for (Account a : accounts) {
                WatchTarget t = findTargetLocked(a, u);
                if (t != null && t.enabled) return t.copy();
            }
            return null;
        }
    }

    /** 监视命中（推送匹配用）：enabled 且 uid 相等。返回命中条目快照，未命中 null。 */
    public WatchTarget matchWatched(String fpid, long ownerUid) {
        if (ownerUid <= 0) return null;
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return null;
            String u = String.valueOf(ownerUid);
            WatchTarget t = findTargetLocked(a, u);
            return t != null && t.enabled ? t.copy() : null;
        }
    }

    public boolean updateToken(String fpid, String token) {        synchronized (lock) {
            for (Account a : accounts) {
                if (a.fpid.equals(fpid)) {
                    a.token = token == null ? "" : token;
                    saveLocked();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 只读：账号当前 token（重装后恢复通道 {@code /v1/vault/token/get} 用）。
     *
     * <p>锁内取值返回不可变 String，调用方拿到的不是 Account 引用，避免锁外读到半更新状态。
     * 账号不存在返回 {@code null}；账号存在但从未补给 token 返回空串（两者语义不同，调用方
     * 分别映射 CODE_ACCOUNT_NOT_FOUND / CODE_TOKEN_MISSING，错误信息不含 token 明文）。
     */
    public String getToken(String fpid) {
        synchronized (lock) {
            Account a = findLocked(fpid);
            if (a == null) return null;
            return a.token == null ? "" : a.token;
        }
    }

    /**
     * 统一自动化开关：auto（整轮调度）与 keep（常驻会话）同值。
     * 客户端登录按钮/总开关都会走这里，保证服务器侧不会出现“开但没会话”的半开状态。
     */
    public boolean setAutomation(String fpid, boolean on) {
        synchronized (lock) {
            for (Account a : accounts) {
                if (!a.fpid.equals(fpid)) continue;
                a.auto = on;
                a.keep = on;
                saveLocked();
                return true;
            }
            return false;
        }
    }

    /** 服务器登录后固化指纹：角色级（sid 非空）或账号级兜底。 */
    public void learnIdentity(String fpid, String sid, String identityJson) {
        learnIdentity(fpid, sid, null, identityJson);
    }

    /**
     * 服务器登录后固化指纹（角色级，2026-11-08 对齐原版 ConfigJsonLoader.scopeFingerprintToRole）：
     * 键优先 {@code sid:uid}——同账号同服多角色各自一台独立"设备"，登录后固化，后续
     * 全量 sync（旧 App 只带 sid 键）不会冲掉；uid 缺省退回旧 {@code sid} 键（单角色/旧数据
     * 零迁移）。已有固定设备不覆盖（与旧行为一致）。
     */
    public void learnIdentity(String fpid, String sid, String uid, String identityJson) {
        if (identityJson == null || identityJson.isEmpty()) return;
        synchronized (lock) {
            for (Account a : accounts) {
                if (!a.fpid.equals(fpid)) continue;
                if (sid != null && !sid.isEmpty()) {
                    String key = (uid == null || uid.isEmpty()) ? sid : roleKey(sid, uid);
                    String prev = a.roleIdentity.get(key);
                    if (prev != null && !prev.isEmpty()) return; // 已有固定设备，不覆盖
                    a.roleIdentity.put(key, identityJson);
                } else if (a.identity == null || a.identity.isEmpty()) {
                    a.identity = identityJson;
                } else {
                    return;
                }
                saveLocked();
                return;
            }
        }
    }

    public boolean setPolicy(String policy) {
        if (!"human_first".equals(policy) && !"grab_back".equals(policy) && !"yield_only".equals(policy)) {
            return false;
        }
        synchronized (lock) {
            takeoverPolicy = policy;
            saveLocked();
            return true;
        }
    }

    public String policy() {
        synchronized (lock) {
            return takeoverPolicy;
        }
    }

    /** 账号快照（迁移遍历用；不暴露内部可变列表）。 */
    public List<Account> accounts() {
        synchronized (lock) {
            return new ArrayList<>(accounts);
        }
    }

    public Account find(String fpid) {
        synchronized (lock) {
            return findLocked(fpid);
        }
    }

    private Account findLocked(String fpid) {
        for (Account a : accounts) {
            if (a.fpid.equals(fpid)) return a;
        }
        return null;
    }

    /** 账号快照（深拷贝调用方可随意改）。 */
    public List<Account> snapshot() {
        synchronized (lock) {
            List<Account> out = new ArrayList<>(accounts.size());
            for (Account a : accounts) {
                Account c = new Account(a.fpid);
                c.token = a.token;
                c.label = a.label;
                c.group = a.group;
                c.keep = a.keep;
                c.activeSid = a.activeSid;
                c.activeUid = a.activeUid;
                c.identity = a.identity;
                c.proxy = a.proxy;
                c.auto = a.auto;
                c.windowStart = a.windowStart;
                c.windowEnd = a.windowEnd;
                c.configDefaultsVersion = a.configDefaultsVersion;
                c.roleIdentity.putAll(a.roleIdentity);
                for (Map.Entry<String, Map<String, Boolean>> e : a.roleConfig.entrySet()) {
                    c.roleConfig.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, Map<String, String>> e : a.roleParams.entrySet()) {
                    c.roleParams.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, Map<String, Boolean>> e : a.roleConfigByUid.entrySet()) {
                    c.roleConfigByUid.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, Map<String, String>> e : a.roleParamsByUid.entrySet()) {
                    c.roleParamsByUid.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, RoleEntry> e : a.roles.entrySet()) {
                    c.roles.put(e.getKey(), e.getValue().copy());
                }
                c.takeoverRecoveryEnabled = a.takeoverRecoveryEnabled;
                c.takeoverInitialDelaySeconds = a.takeoverInitialDelaySeconds;
                c.takeoverRetryIntervalSeconds = a.takeoverRetryIntervalSeconds;
                c.takeoverMaxAttempts = a.takeoverMaxAttempts;
                c.takeoverCooldownMinutes = a.takeoverCooldownMinutes;
                for (WatchTarget t : a.watchTargets) c.watchTargets.add(t.copy());
                out.add(c);
            }
            return out;
        }
    }
}
