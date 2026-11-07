package com.carlos.vault;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号库：SharedPreferences 存储 [{id, fpid, token, label, lastLogin, group}] + 独立键 group_meta。
 *
 * <p><b>排序 = 数组顺序（隐式）</b>：不加 {@code order} 字段 ⇒ 38 条老数据零迁移。
 * <b>分组 = 每条 {@code group}</b>（分组名，{@code ""} = 未分组）+ {@link #getGroupMeta()} 里的
 * {@code group_meta} = {@code [{"name":"…","collapsed":false}]}（同时表达分组顺序与折叠态）。
 */
public class AccountStore {

    public static class Account {
        public String id;      // 入口唯一标识（有号ID用号ID，否则自动生成）
        public String fpid;    // 可选：号ID（设置token后填充）
        public String token;   // 可选：登录token
        public String label;
        public long lastLogin;
        /**
         * 所属分组名（{@code ""} = 未分组）。
         *
         * ⚠️ 38 条老数据**没有**这个字段，{@link #all()} 用 {@code optString("group", "")}
         * 兜底 ⇒ 全部归入"未分组"，**零迁移、一条不丢**。
         */
        public String group;
        /**
         * 设备指纹 JSON（{@code {"distinct_id":…,"device_id":…,"ip":…}}，WjdrEngine 登录载荷用）。
         *
         * <p>每账号固定一份 ⇒ 同一账号在游戏风控侧始终是"同一台设备"，
         * 而不是每次协议登录都换一批设备 id。首次登录由引擎随机生成并回写，之后登录复用。
         *
         * <p>老数据没有该字段，{@link #all()} 用 {@code optString("settings", "")} 兜底，零迁移。
         */
        public String settings;
    }

    private final SharedPreferences sp;
    private final android.content.Context appCtx;

    public AccountStore(Context ctx) {
        sp = ctx.getSharedPreferences("account_vault", Context.MODE_PRIVATE);
        appCtx = ctx.getApplicationContext();
    }

    public List<Account> all() {
        List<Account> out = new ArrayList<>();
        boolean changed = false;
        java.util.Map<String, String> idRemap = new java.util.HashMap<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("accounts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Account a = new Account();
                a.id = o.optString("id");
                a.fpid = o.optString("fpid");
                a.token = o.optString("token");
                a.label = o.optString("label", a.fpid.isEmpty() ? "入口" : a.fpid);
                a.lastLogin = o.optLong("lastLogin");
                // 分组名缺省即"未分组"：38 条老数据没有该字段，绝不能因为读不到就丢条目。
                a.group = o.optString("group", "");
                a.settings = o.optString("settings", "");
                if (a.id.isEmpty()) a.id = a.fpid.isEmpty() ? ("e" + a.hashCode()) : a.fpid;
                // 修复旧版批量导入混入备注的号ID（如 "kk大王｜⇥81444913" → "81444913"）
                if (!a.fpid.isEmpty() && !a.fpid.matches("[0-9]+")) {
                    String fixed = extractFpid(a.fpid);
                    if (!fixed.isEmpty() && !fixed.equals(a.fpid)) {
                        // 从脏id里恢复备注（"kk大王｜⇥81444913" → "kk大王"）
                        if (a.fpid.equals(a.label)) {
                            int bar = Math.max(a.fpid.lastIndexOf('|'), a.fpid.lastIndexOf('｜'));
                            if (bar > 0) {
                                String lbl = a.fpid.substring(0, bar).trim();
                                if (!lbl.isEmpty()) a.label = lbl;
                            }
                        }
                        if (a.fpid.equals(a.id)) { idRemap.put(a.id, fixed); a.id = fixed; }
                        a.fpid = fixed;
                        changed = true;
                    }
                }
                out.add(a);
            }
        } catch (Throwable ignored) {
        }
        if (changed) {
            save(out);
            String last = sp.getString("last_entry", null);
            if (last != null && idRemap.containsKey(last)) {
                sp.edit().putString("last_entry", idRemap.get(last)).apply();
            }
        }
        return out;
    }

    private void save(List<Account> list) {
        JSONArray arr = new JSONArray();
        for (Account a : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", a.id);
                o.put("fpid", a.fpid);
                o.put("token", a.token);
                o.put("label", a.label);
                o.put("lastLogin", a.lastLogin);
                // ⚠️⚠️ 本行与 toJsonArray() 里的同名行必须同时存在！
                // save() 与 toJsonArray() 是本类仅有的两份 accounts 序列化；只加一处，
                // 就会在"批量导入一次"(走 saveSync→toJsonArray) 时把全部 38 条的分组抹掉。
                o.put("group", a.group == null ? "" : a.group);
                // settings 同理（设备指纹，见字段注释）。
                o.put("settings", a.settings == null ? "" : a.settings);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        sp.edit().putString("accounts", arr.toString()).apply();
    }

    /**
     * 序列化为 JSON 数组（{@link #saveSync} 与 {@link #saveOrder} 专用；
     * {@code save()} 本体未作任何改动）。
     *
     * ⚠️ 字段列表必须与 {@code save()} 保持一致（尤其是 {@code group}）——见 {@code save()} 内注释。
     */
    private JSONArray toJsonArray(List<Account> list) {
        JSONArray arr = new JSONArray();
        for (Account a : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", a.id);
                o.put("fpid", a.fpid);
                o.put("token", a.token);
                o.put("label", a.label);
                o.put("lastLogin", a.lastLogin);
                // ⚠️⚠️ 本行与 save() 里的同名行必须同时存在！见 save() 的说明。
                o.put("group", a.group == null ? "" : a.group);
                // settings 同理（设备指纹，见字段注释）。
                o.put("settings", a.settings == null ? "" : a.settings);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    /**
     * 同步落盘（{@code commit()}），<b>仅用于批量导入</b>这类"要么全有、要么全无"的提交路径。
     *
     * 与 {@code save()}（{@code apply()} 异步）的区别只在落盘方式，序列化规则完全一致。
     * 用 {@code commit()} 的理由与 {@link #setLiveEntryId} 相同：{@code apply()} 是异步的，
     * 批量导入一次可能写入几十条，若用户导入后立刻把 App 划掉，整个批次可能还没落盘就随进程
     * 一起没了；同步写在返回前就能保证已写入磁盘，代价可忽略。
     *
     * @return true 表示已同步写入磁盘
     */
    public boolean saveSync(List<Account> list) {
        return sp.edit().putString("accounts", toJsonArray(list).toString()).commit();
    }

    /**
     * 从文本中提取末尾连续数字作为号ID。
     * 兼容 "备注｜⇥81444913"、"备注|81444913"、"81444913" 等格式混入的备注/分隔符/空白。
     */
    public static String extractFpid(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        int start = raw.length();
        while (start > 0 && Character.isDigit(raw.charAt(start - 1))) start--;
        return raw.substring(start);
    }

    /**
     * 导入一行：备注|号ID----token / 备注｜号ID----token 或 号ID----token
     * 分隔符支持半角 | 和全角｜，号ID自动提取末尾数字。
     * @return 错误信息，null 表示成功
     */
    public String importLine(String line) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        String label = null, body = line;
        int sepDash = line.indexOf("----");
        if (sepDash > 0) {
            String head = line.substring(0, sepDash);
            int bar = Math.max(head.lastIndexOf('|'), head.lastIndexOf('｜'));
            if (bar > 0) {
                label = head.substring(0, bar).trim();
                body = head.substring(bar + 1).trim() + line.substring(sepDash);
            }
        }
        int sep = body.indexOf("----");
        if (sep <= 0) return "无法解析: " + line;
        String fpid = extractFpid(body.substring(0, sep));
        String token = body.substring(sep + 4).trim();
        if (fpid.isEmpty() || token.isEmpty() || token.contains("----")) return "无法解析: " + line;
        if (label == null || label.isEmpty()) label = fpid;
        // 去重：同 fpid 覆盖
        List<Account> list = all();
        for (Account a : list) {
            if (a.fpid.equals(fpid)) {
                a.token = token;
                if (label != null && !label.equals(fpid)) a.label = label;
                save(list);
                VaultSync.onAccountsChanged(appCtx);
                return null;
            }
        }
        Account a = new Account();
        a.id = fpid;
        a.fpid = fpid;
        a.token = token;
        a.label = label;
        list.add(a);
        save(list);
        VaultSync.onAccountsChanged(appCtx);
        return null;
    }

    public void updateLabel(String id, String label) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.label = label;
                save(list);
                return;
            }
        }
    }

    public void updateToken(String id, String token) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.token = token;
                save(list);
                VaultSync.onAccountsChanged(appCtx);
                return;
            }
        }
    }

    // ---------- 设备指纹（按 fpid 存取：协议登录侧只认 fpid，不认入口 id） ----------

    /** 取某账号的设备指纹 JSON；无则返回 {@code ""}（调用方回落引擎随机生成）。只读，不触发写盘。 */
    public String getSettings(String fpid) {
        if (fpid == null || fpid.isEmpty()) return "";
        for (Account a : all()) {
            if (a != null && fpid.equals(a.fpid) && a.settings != null && !a.settings.isEmpty()) {
                return a.settings;
            }
        }
        return "";
    }

    /** 记录/更新某账号的设备指纹（引擎首次登录随机生成后回写固化，此后复用同一"设备"）。 */
    public void updateSettings(String fpid, String settings) {
        if (fpid == null || fpid.isEmpty() || settings == null || settings.isEmpty()) return;
        List<Account> list = all();
        for (Account a : list) {
            if (fpid.equals(a.fpid)) {
                if (settings.equals(a.settings)) return;   // 未变化不写盘
                a.settings = settings;
                save(list);
                return;
            }
        }
    }

    // ---------- 设备指纹（按角色存取：同账号多角色并行在线，每角色一台独立"设备"） ----------

    /**
     * 取某角色的设备指纹 JSON（独立键 {@code role_settings:<fpid>:<sid>}）。刻意不进 accounts
     * 数组——那份序列化有 save()/toJsonArray() 两处必须同步的坑，独立键零迁移零风险。
     * 无则返回 {@code ""}（调用方回落引擎随机生成）。
     *
     * <p>uid 未知的旧调用保持旧键；新代码用 {@link #getRoleSettings(String, long, long)}
     * 读 {@code sid:uid} 键（同服多角色各自一台设备，2026-11-08 对齐原版网页版）。
     */
    public String getRoleSettings(String fpid, long sid) {
        if (fpid == null || fpid.isEmpty() || sid <= 0) return "";
        String v = sp.getString("role_settings:" + fpid + ":" + sid, "");
        return v == null ? "" : v;
    }

    /** 取某角色（sid+uid）的设备指纹；无 uid 时回落旧 sid 键。 */
    public String getRoleSettings(String fpid, long sid, long uid) {
        if (fpid == null || fpid.isEmpty() || sid <= 0) return "";
        if (uid <= 0) return getRoleSettings(fpid, sid);
        String v = sp.getString("role_settings:" + fpid + ":" + sid + ":" + uid, "");
        return v == null ? "" : v;
    }

    /** 记录/更新某角色的设备指纹（引擎登录后回写固化，此后该角色复用同一"设备"）。 */
    public void updateRoleSettings(String fpid, long sid, String settings) {
        updateRoleSettings(fpid, sid, 0, settings);
    }

    /** 记录/更新某角色（sid+uid）的设备指纹；uid<=0 时写旧 sid 键（兼容旧调用）。 */
    public void updateRoleSettings(String fpid, long sid, long uid, String settings) {
        if (fpid == null || fpid.isEmpty() || sid <= 0 || settings == null || settings.isEmpty()) return;
        String k = uid > 0
                ? "role_settings:" + fpid + ":" + sid + ":" + uid
                : "role_settings:" + fpid + ":" + sid;
        if (settings.equals(sp.getString(k, ""))) return;   // 未变化不写盘
        sp.edit().putString(k, settings).apply();
    }

    /**
     * 角色登录用的身份种子（含从账号级旧指纹的迁移规则）：
     * <ul>
     *   <li>已有 {@code sid:uid} 角色级指纹 → 直接复用（登录成功后固化，永不变）；</li>
     *   <li>仅有旧 {@code sid} 键且该区服无兄弟角色 → 沿用（单角色/旧数据零迁移、不换脸）；</li>
     *   <li>仅有旧 {@code sid} 键且同区服还有其它角色 → 按 {@code fpid|sid|uid} 稳定派生
     *       （每角色一台设备，避免同设备互踢——对齐原版 ConfigJsonLoader.scopeFingerprintToRole）；</li>
     *   <li>都没有且是主角色 → 沿用账号级旧指纹；其它角色 → 返回空串由引擎随机生成，
     *       登录成功后按 {@code sid:uid} 固化。</li>
     * </ul>
     *
     * <p><b>2026-11-08 结论修正</b>：原「服务器按账号唯一会话、同账号新登录必踢旧」的终判
     * 是在 web 端反抢干扰下得出的；网页版生产实测同账号同服双角色可长期同时在线
     * （531/532@888、536/537@888），关键前提是<b>每个角色一台独立设备</b>。
     */
    public String roleIdentitySeed(String fpid, long sid, long mainSid) {
        return roleIdentitySeed(fpid, sid, 0, mainSid);
    }

    /** 角色登录身份种子（uid 维度，2026-11-08；uid<=0 时退化为旧 sid 键语义）。 */
    public String roleIdentitySeed(String fpid, long sid, long uid, long mainSid) {
        if (fpid == null || fpid.isEmpty()) return "";
        if (sid <= 0) return getSettings(fpid);
        String exact = uid > 0 ? getRoleSettings(fpid, sid, uid) : "";
        String sidSeed = getRoleSettings(fpid, sid);
        boolean hasSibling = uid > 0 && hasSiblingRole(fpid, sid, uid);
        String scoped = sidSeed.isEmpty() ? "" : roleScopedIdentity(sidSeed, fpid, sid, uid);
        return resolveRoleSeed(exact, sidSeed, getSettings(fpid), scoped,
                uid, sid, mainSid, hasSibling);
    }

    /**
     * 纯函数：给定各级种子与「同区服是否有兄弟角色」决定本角色登录种子
     * （无 Android 依赖，JVM 单测直跑）。
     *
     * <p>2026-11-08 修复：主角色在"同服有兄弟"时<b>不能</b>沿用账号级指纹——否则 A 用
     * 账号级指纹固化后、B 切换为主角色时又拿到同一指纹，两者被视为同一台设备互踢。
     */
    static String resolveRoleSeed(String exactSeed, String sidSeed, String accountSeed,
                                  String scopedSeed, long uid, long sid, long mainSid,
                                  boolean hasSibling) {
        if (uid > 0 && exactSeed != null && !exactSeed.isEmpty()) return exactSeed;
        if (sidSeed != null && !sidSeed.isEmpty()) {
            if (uid > 0 && hasSibling) return scopedSeed == null ? "" : scopedSeed;
            return sidSeed;
        }
        // 主角色只有在同区服没有兄弟角色时才沿用账号级旧指纹；同服多角色一律不共享。
        if (sid == mainSid && (uid <= 0 || !hasSibling)) {
            return accountSeed == null ? "" : accountSeed;
        }
        return "";
    }

    /** 同区服是否还有其它角色（roles 表判断；查不到视为无，保持旧行为）。 */
    private boolean hasSiblingRole(String fpid, long sid, long uid) {
        try {
            java.util.List<com.carlos.vault.daily.RoleStore.Role> roles =
                    com.carlos.vault.daily.RoleStore.roles(appCtx, fpid);
            if (roles == null) return false;
            for (com.carlos.vault.daily.RoleStore.Role r : roles) {
                if (r == null || r.serverid != sid) continue;
                if (r.uid > 0 && r.uid != uid) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 角色级指纹派生（与 vault-server 共用 {@link com.carlos.vault.wjdr.RoleFingerprint}，
     * 两端规则不再各自维护）。只改 device_id/distinct_id；ip/机型保持账号级。
     */
    static String roleScopedIdentity(String baseJson, String fpid, long sid, long uid) {
        return com.carlos.vault.wjdr.RoleFingerprint.scoped(
                baseJson, fpid, String.valueOf(sid), String.valueOf(uid));
    }

    /**
     * 会话 ↔ 入口 归属同步：把入口的 {@code fpid} 改成「live 里**实际登录**的那个号」，并作废旧 token。
     *
     * <p><b>为什么需要它</b>：真机上出现过「A 入口的快照里存着 B 账号的会话」（鲍哥888，D-4）——
     * 用户在**同一入口内用验证码换了号**，而 R2 存档只按「live 归属哪个入口」落盘、
     * <b>不校验会话 fpid 与入口 fpid 是否一致</b>，于是把错归属的会话固化进了快照。
     * 用户拍板的语义是「同一入口内切换登录，以最新登录的号为准」⇒ 这里主动把入口校准到最新登录的号。
     *
     * <p><b>为什么连 token 一起清</b>：旧 token 属于**旧号**。留着它，下次走「还原基线 + 注入 token」
     * 分支时会把用户又登回旧号（{@code useToken} 只看 fpid/token 是否非空）。清空后该入口回落到
     * 「按快照还原」，与「以最新登录为准」的语义一致。
     *
     * <p><b>刻意不动的三样</b>：
     * <ul>
     *   <li>{@code id} —— {@code snap_<id>.zip}、{@code live_entry_id}、{@code last_entry}、
     *       {@code snap_fp_<id>} 全以 id 为键，改 id 会把它们**全部孤儿化**；</li>
     *   <li>{@code label} —— 用户起的备注，不该被自动覆盖；</li>
     *   <li>{@code lastLogin} —— 由 {@link #markLogin} 单独管。</li>
     * </ul>
     *
     * <p>落盘走 {@link #save(List)}，与其它写路径是**同一份序列化**（含 {@code group} 字段），
     * 不存在"某条路径漏字段把分组抹掉"的隐患。
     *
     * @return true 表示找到了该入口并已写入；false = 参数非法或该 id 不存在（调用方静默跳过即可）
     */
    public boolean updateFpidAndClearToken(String id, String fpid) {
        if (id == null || id.isEmpty() || fpid == null || fpid.isEmpty()) return false;
        List<Account> list = all();
        for (Account a : list) {
            if (id.equals(a.id)) {
                a.fpid = fpid;
                a.token = "";
                save(list);
                return true;
            }
        }
        return false;
    }

    public void remove(String id) {
        List<Account> list = all();
        List<Account> out = new ArrayList<>();
        Account removed = null;
        for (Account a : list) {
            if (!a.id.equals(id)) out.add(a);
            else removed = a;
        }
        save(out);
        // 同时清掉该凭证的角色表/当前角色/任务开关，避免“删除后旧角色仍被当成当前角色”。
        if (removed != null) {
            com.carlos.vault.daily.RoleStore.removeAccount(appCtx, removed.fpid);
        }
    }

    public String getLastEntry() {
        return sp.getString("last_entry", null);
    }

    public void setLastEntry(String id) {
        sp.edit().putString("last_entry", id).apply();
    }

    /**
     * 当前 live（游戏内部数据目录）归属于哪个入口。
     *
     * 只存**入口 id**，不存任何凭据（无 token / 无手机号 / 无 fpid）。这是 "下次冷启动要不要
     * wipe live" 的唯一判据，见 VaultEngine.launchEntry()。
     */
    public String getLiveEntryId() {
        String v = sp.getString("live_entry_id", null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /**
     * 记录 live 目录的归属；传 null 表示清空（live 已不属于任何入口）。
     *
     * 这里刻意用 **commit() 而不是 apply()**：apply() 是异步落盘的，进程被强杀时这个标记
     * 可能还没写下去；一旦它丢了，下次冷启动就会把 live 当成"无主数据"从而 wipe 掉，正好把
     * 刚刚登录好的会话删掉 —— 与本次要修的问题是同一个失效模式。该键很小，同步写的开销可忽略。
     */
    public void setLiveEntryId(String id) {
        if (id == null) sp.edit().remove("live_entry_id").commit();
        else sp.edit().putString("live_entry_id", id).commit();
    }

    /**
     * 上次为该入口打快照时、live 目录的指纹。用于跳过"内容没变却重写一遍约 9MB zip"的无意义操作。
     * 指纹本身只含文件数/总字节数/最新 mtime，不含任何文件内容或凭据。
     */
    public String getSnapshotFingerprint(String entryId) {
        return sp.getString("snap_fp_" + entryId, null);
    }

    public void setSnapshotFingerprint(String entryId, String fp) {
        if (fp == null) sp.edit().remove("snap_fp_" + entryId).apply();
        else sp.edit().putString("snap_fp_" + entryId, fp).apply();
    }

    // ---------- 排序 / 分组（②拖拽排序 ③分组） ----------
    //
    // 排序 = accounts 数组顺序（隐式，不加 order 字段）⇒ 38 条老数据零迁移。
    // 分组 = 每条 Account.group（分组名，"" = 未分组）+ 独立键 group_meta。
    // 纯逻辑（构建展示列表 / 拖拽落位 / 分组增删改名 / 折叠 / JSON 读写）都在
    // EntryLayout 里（不 import android.*，可在宿主机 javac 干跑）；这里只做落盘。

    /** 分组元数据键名。 */
    private static final String KEY_GROUP_META = "group_meta";

    /**
     * 按给定数组顺序落盘（{@code commit()} 同步写）。
     *
     * 与 {@link #saveSync} 的区别：这个用于"用户拖完顺序"这种交互式提交，必须同步落盘，
     * 否则用户拖完立刻把 App 划掉，顺序会随进程一起丢。序列化走 {@link #toJsonArray}，与
     * {@code save()/saveSync()} 规则完全一致（含 group 字段）。
     *
     * @return true 表示已同步写入磁盘
     */
    public boolean saveOrder(List<Account> list) {
        return sp.edit().putString("accounts", toJsonArray(list).toString()).commit();
    }

    /** 读分组元数据（分组顺序 + 折叠态）。无该键 / 解析失败都返回空列表。 */
    public List<EntryLayout.Group> getGroupMeta() {
        return EntryLayout.parseMeta(sp.getString(KEY_GROUP_META, ""));
    }

    /** 写分组元数据（{@code apply()}）。用于**折叠态**这类"丢了不致命"的改动。 */
    public void setGroupMeta(String json) {
        sp.edit().putString(KEY_GROUP_META, json == null ? "[]" : json).apply();
    }

    /** 写分组元数据（{@code commit()}）。用于分组增删改名这类"丢不起"的改动。 */
    public boolean setGroupMetaSync(String json) {
        return sp.edit().putString(KEY_GROUP_META, json == null ? "[]" : json).commit();
    }

    /** 把某入口移到指定分组（{@code ""} = 未分组）。同步落盘。 */
    public boolean setGroup(String entryId, String group) {
        if (entryId == null) return false;
        String g = group == null ? "" : group.trim();
        List<Account> list = all();
        for (Account a : list) {
            if (entryId.equals(a.id)) {
                a.group = g;
                return saveOrder(list);
            }
        }
        return false;
    }

    /** 新建分组（只写 group_meta，不动任何成员）。空名 / 重名 / 保留名被拒。 */
    public boolean createGroup(String name) {
        List<EntryLayout.Group> meta = getGroupMeta();
        if (EntryLayout.validateGroupName(meta, name) != null) return false;
        meta.add(new EntryLayout.Group(name.trim(), false));
        return setGroupMetaSync(EntryLayout.toMeta(meta));
    }

    /**
     * 重命名分组：改 {@code group_meta} 的组名 + 把所有 {@code group==oldName} 的成员改成新名，
     * 并且<b>只做一次事务</b> —— 同一个 {@code Editor} 里同时写 {@code accounts} 与
     * {@code group_meta}，{@code commit()} 保证要么都写、要么都不写，
     * 不会出现"元数据改了、成员没改"的中间态。空名 / 重名 / 保留名 / 原名被拒。
     */
    public boolean renameGroup(String oldName, String newName) {
        List<EntryLayout.Group> meta = getGroupMeta();
        if (EntryLayout.validateRename(meta, oldName, newName) != null) return false;
        List<Account> list = all();
        List<EntryLayout.Item> items = new ArrayList<>();
        for (Account a : list) items.add(new EntryLayout.Item(a.id, a.group));
        if (!EntryLayout.renameInPlace(meta, items, oldName, newName)) return false;
        for (int i = 0; i < list.size(); i++) list.get(i).group = items.get(i).group;
        return sp.edit()
                .putString("accounts", toJsonArray(list).toString())
                .putString(KEY_GROUP_META, EntryLayout.toMeta(meta))
                .commit();
    }

    /**
     * 删除分组：从 {@code group_meta} 移除该组，成员<b>退回"未分组"</b>（绝不删条目）。
     * 同样是一次事务（accounts + group_meta 同一个 {@code commit()}）。
     *
     * 注意：**最后一个成员被移走不会自动删组**（只变空组，仍显示，方便改名/删除）；
     * 只有显式调用本方法才真正删组。
     */
    public boolean deleteGroup(String name) {
        if (name == null || name.isEmpty()) return false;
        List<EntryLayout.Group> meta = getGroupMeta();
        List<Account> list = all();
        List<EntryLayout.Item> items = new ArrayList<>();
        for (Account a : list) items.add(new EntryLayout.Item(a.id, a.group));
        if (!EntryLayout.deleteInPlace(meta, items, name)) return false;
        for (int i = 0; i < list.size(); i++) list.get(i).group = items.get(i).group;
        return sp.edit()
                .putString("accounts", toJsonArray(list).toString())
                .putString(KEY_GROUP_META, EntryLayout.toMeta(meta))
                .commit();
    }

    /**
     * 按 id 取一条**最新**账户；不存在返回 {@code null}。
     *
     * <p><b>为什么要有它（#72 F1/F2）</b>：管理层 Activity 的 {@code acc} 只在 onCreate 取过一次，
     * 用户改完 token 后内存里的对象就陈旧了 —— 点「启动分身」传下去的还是旧对象，
     * 于是 {@code useToken} 判空、不注入。引擎侧同样需要一条"按 id 重新读"的标准姿势。
     * 此前各处都是手写 for 循环在 {@link #all()} 里找，容易出现"某处忘了同步、某处口径不一"的偏差。
     *
     * <p>刻意<b>只读</b>：不触发任何写盘，可在任意线程/时机安全调用。
     *
     * @param id 入口 id；null / 空串直接返回 null
     * @return 磁盘上最新的那条账户；没有则返回 null
     */
    public Account byId(String id) {
        if (id == null || id.isEmpty()) return null;
        List<Account> list = all();
        for (Account a : list) {
            if (a != null && id.equals(a.id)) return a;
        }
        return null;
    }

    public void markLogin(String id) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.lastLogin = System.currentTimeMillis();
                save(list);
                return;
            }
        }
    }

    public void createEntry(String label) {
        Account a = new Account();
        a.id = "e" + System.currentTimeMillis();
        a.fpid = "";
        a.token = "";
        a.group = "";   // 新建入口默认进"未分组"
        a.label = label == null || label.trim().isEmpty() ? ("入口" + (all().size() + 1)) : label.trim();
        List<Account> list = all();
        list.add(a);
        save(list);
    }

    public void setToken(String id, String fpid, String token) {
        List<Account> list = all();
        for (Account a : list) {
            if (a.id.equals(id)) {
                a.fpid = fpid;
                a.token = token;
                if (a.label == null || a.label.isEmpty() || a.label.startsWith("入口")) {
                    a.label = fpid;
                }
                save(list);
                VaultSync.pushToken(appCtx, fpid, token);
                VaultSync.onAccountsChanged(appCtx);
                return;
            }
        }
    }
}
