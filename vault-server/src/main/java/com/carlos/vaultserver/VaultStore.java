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
            return c;
        }
    }

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
        /** 账号池角色条目：roleKey(sid:uid) → 条目（M1 2026-10-21，服务器侧意图态）。 */
        public final Map<String, RoleEntry> roles = new LinkedHashMap<>();

        public Account(String fpid) {
            this.fpid = fpid;
        }
    }

    private final Path file;
    private final Path backup;
    private final List<Account> accounts = new ArrayList<>();
    private String takeoverPolicy = "human_first";
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
                                a.roles.put(roleKey(e.sid, e.uid), e);
                            }
                        }
                        accounts.add(a);
                    }
                    String policy = root.optString("takeoverPolicy", "");
                    if (!policy.isEmpty()) takeoverPolicy = policy;
                    return; // 主文件解析成功即不再看备份
                } catch (Exception e) {
                    FileLog.warn("库文件解析失败 {}：{}，尝试下一份", p.getFileName(), e.getMessage());
                }
            }
        }
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
                    rls.put(e.getKey(), ro);
                }
                o.put("roles", rls);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("accounts", arr);
            root.put("takeoverPolicy", takeoverPolicy);
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
                }
                merged.roleIdentity.putAll(in.roleIdentity);
                merged.roleConfig.putAll(in.roleConfig);
                merged.roleParams.putAll(in.roleParams);
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
                    || (activeSwitched && m.desiredOnline && !isActive)) {
                if (m.desiredOnline || !RUN_STOPPED.equals(m.runState)) m.runState = RUN_STOPPED;
                m.desiredOnline = false;
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
                } else if (exclusive && e.desiredOnline) {
                    e.desiredOnline = false;
                    e.runState = RUN_STOPPED;
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
            saveLocked();
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
        if (identityJson == null || identityJson.isEmpty()) return;
        synchronized (lock) {
            for (Account a : accounts) {
                if (!a.fpid.equals(fpid)) continue;
                if (sid != null && !sid.isEmpty()) {
                    String prev = a.roleIdentity.get(sid);
                    if (prev != null && !prev.isEmpty()) return; // 已有固定设备，不覆盖
                    a.roleIdentity.put(sid, identityJson);
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
                c.roleIdentity.putAll(a.roleIdentity);
                for (Map.Entry<String, Map<String, Boolean>> e : a.roleConfig.entrySet()) {
                    c.roleConfig.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, Map<String, String>> e : a.roleParams.entrySet()) {
                    c.roleParams.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, RoleEntry> e : a.roles.entrySet()) {
                    c.roles.put(e.getKey(), e.getValue().copy());
                }
                out.add(c);
            }
            return out;
        }
    }
}
