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

    public static final class Account {
        public String fpid;
        public String token = "";
        public String label = "";
        public String group = "";
        public boolean keep;
        /** 当前选中角色 sid（null = 服务器默认角色）。 */
        public String activeSid;
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
                accounts.add(merged);
            }
            saveLocked();
            return accounts.size();
        }
    }

    public boolean updateToken(String fpid, String token) {
        synchronized (lock) {
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
            for (Account a : accounts) {
                if (a.fpid.equals(fpid)) return a;
            }
            return null;
        }
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
                out.add(c);
            }
            return out;
        }
    }
}
