package com.carlos.vault.daily;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色与调度存储。模型与原版一致：
 * 凭证（号库账号 fpid+token） → 多个角色（serverid+uid+name）。
 * 登录带 serverid 即选择对应区服的角色；登录成功自动登记角色。
 * 调度：每凭证一份 {autoEnabled, serverid, nextRunAt, lastRunAt, windowStart/End}。
 *
 * <p><b>角色身份 = (serverid, uid)</b>（2026-10-20 同服多角色修复）：一个 token 可以在
 * 同一个区服建多个角色（真机 48460403 的 888 服：隆科多 uid=35371154 / 小鹿矿工5号
 * uid=88280792），历史上按 serverid 单键去重会把第二个角色覆盖合并、开关与登录目标
 * 串到另一个角色。所有匹配/去重/开关/uid 更新/当前角色统一走 {@link RoleIdentity}，
 * 同 sid 不同 uid 视为不同角色；uid=0 的旧记录在 uid 已知时原地升级。
 */
public class RoleStore {

    /**
     * 角色表写锁：roles 表是"读全表→改→写全表"模式，registerRole（登录登记，多角色
     * 竞速时多线程并发）/setEnabled（UI 开关）/updateUid（钉扎自愈）并发时后写者
     * 会用旧快照整表覆盖、丢掉别的线程刚写入的角色（真机事故：94450730 的 K916
     * 角色被并发的 K913 登记覆盖，工作区里凭空消失）。所有写路径必须在同一把锁内
     * 完成"读-改-写"。
     */
    private static final Object WRITE_LOCK = new Object();

    /** 当前角色复合键前缀（值 "sid:uid"）；旧键 active:<fpid>（long sid）只读兼容 + 惰性迁移。 */
    private static final String ACTIVE_KEY = "active2:";

    /**
     * 当前角色激活时间戳前缀（epoch ms；{@link #setActiveRole} 每次写入时刷新）。
     *
     * <p>防回滚用（2026-10-20 P2）：切角色只写本地、上传有延迟，服务端旧回传需按
     * 「本地激活是否晚于请求发起」判断新旧（见 {@link RoleIdentity#shouldLearnActiveRole}）。
     */
    private static final String ACTIVE_AT_KEY = "active2_at:";

    /**
     * 角色切换待上传标记前缀（boolean）：用户切了当前角色但全量同步尚未成功。
     *
     * <p>窗口内服务端 {@code /state}、{@code /status} 回传仍是旧角色，此时
     * {@code learnServerRole} 不得回写当前角色（会把刚切的角色回滚）。由 UI 切换入口
     * 置位（{@link #markRoleDirty}），一次成功的全量 sync 清空（{@link #clearAllRoleDirty}，
     * sync payload 带全部账号，故一次性清所有）。
     */
    private static final String DIRTY_KEY = "role_dirty:";
    private static final String DIRTY_AT_KEY = "role_dirty_at:";

    public static class Role implements RoleIdentity.Identity {
        public long serverid;
        public long uid;
        public String name = "";
        public boolean enabled;   // 工作区开关（默认关）
        /** 角色元数据（登录摘要/玩家信息补全；老数据无这些字段，optLong/optString 兜底为零值）。 */
        public long level;            // 熔炉等级（0=未知）
        public long power;            // 战力（0=未知）
        public String alliance = "";      // 联盟名（""=未知/无盟）
        public String allianceAbbr = "";  // 联盟简称（notify_alliance_init.abbr；""=未知）

        @Override public long serverid() {
            return serverid;
        }

        @Override public long uid() {
            return uid;
        }
    }

    /** 当前选中角色（复合身份；sid=-1 表示从未选过）。 */
    public static final class ActiveRole {
        public long sid = -1;
        public long uid;

        public ActiveRole(long sid, long uid) {
            this.sid = sid;
            this.uid = uid;
        }
    }

    /** 某凭证下已登记的角色（按发现顺序）。 */
    public static List<Role> roles(Context ctx, String fpid) {
        List<Role> out = new ArrayList<>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("roles:" + fpid, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Role r = new Role();
                r.serverid = o.optLong("serverid", 0);
                r.uid = o.optLong("uid", 0);
                r.name = o.optString("name", "");
                r.enabled = o.optBoolean("enabled", false);
                r.level = o.optLong("level", 0);
                r.power = o.optLong("power", 0);
                r.alliance = o.optString("alliance", "");
                r.allianceAbbr = o.optString("allianceAbbr", "");
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 从服务端投影缓存联盟简称（不改变 enabled/其他元数据；角色不存在时不新建）。 */
    public static void cacheAllianceAbbr(Context ctx, String fpid, long serverid, long uid,
                                          String allianceAbbr) {
        if (ctx == null || fpid == null || fpid.isEmpty() || serverid <= 0L
                || allianceAbbr == null || allianceAbbr.isEmpty()) return;
        synchronized (WRITE_LOCK) {
            try {
                List<Role> roles = roles(ctx, fpid);
                boolean changed = false;
                for (Role r : roles) {
                    if (r.serverid == serverid && (uid <= 0L || r.uid <= 0L || r.uid == uid)) {
                        if (!allianceAbbr.equals(r.allianceAbbr)) {
                            r.allianceAbbr = allianceAbbr;
                            changed = true;
                        }
                        if (uid > 0L && r.uid <= 0L) {
                            r.uid = uid;
                            changed = true;
                        }
                    }
                }
                if (changed) saveRoles(ctx, fpid, roles);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 登录成功后登记/刷新角色（按 (serverid,uid) 去重；无 serverid 也可登记 uid=0 槽）。 */
    public static void registerRole(Context ctx, String fpid, long serverid, long uid, String name) {
        registerRole(ctx, fpid, serverid, uid, name, 0, 0, "", "");
    }

    /**
     * 带元数据版：等级/战力/联盟名仅在新值有效时覆盖（0/"" 不冲掉旧值，与 uid/name 同策略）。
     *
     * <p>去重口径（{@link RoleIdentity#matches}）：同 sid 且 uid 一侧未知 → 命中旧记录<b>原地升级</b>
     * （旧数据 uid=0 学到真 uid 后补登到同一行，不产生重复行）；同 sid 两侧 uid 均已知且不同 →
     * <b>新建角色行</b>（同服多角色不再互相覆盖）。
     */
    public static void registerRole(Context ctx, String fpid, long serverid, long uid, String name,
                                    long level, long power, String alliance) {
        registerRole(ctx, fpid, serverid, uid, name, level, power, alliance, "");
    }

    /** 带联盟简称版（角色列表统一展示 {serverid}-{abbr}-{name} 的数据来源）。 */
    public static void registerRole(Context ctx, String fpid, long serverid, long uid, String name,
                                    long level, long power, String alliance, String allianceAbbr) {
        if (fpid == null || fpid.isEmpty()) return;
        synchronized (WRITE_LOCK) {
            try {
                List<Role> list = roles(ctx, fpid);
                int hitIdx = RoleIdentity.indexOfLoose(list, serverid, uid);
                Role hit;
                if (hitIdx < 0) {
                    hit = new Role();
                    hit.serverid = serverid;
                    hit.enabled = false;   // 新角色默认不进工作区
                    list.add(hit);
                } else {
                    hit = list.get(hitIdx);
                }
                if (uid > 0) hit.uid = uid;
                if (name != null && !name.isEmpty()) hit.name = name;
                if (level > 0) hit.level = level;
                if (power > 0) hit.power = power;
                if (alliance != null && !alliance.isEmpty()) hit.alliance = alliance;
                if (allianceAbbr != null && !allianceAbbr.isEmpty()) hit.allianceAbbr = allianceAbbr;
                saveRoles(ctx, fpid, list);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 更新角色的工作区开关（按 (serverid,uid)；uid<=0 时退化为该 sid 第一行的旧口径）。 */
    public static void setEnabled(Context ctx, String fpid, long serverid, long uid, boolean enabled) {
        synchronized (WRITE_LOCK) {
            try {
                List<Role> list = roles(ctx, fpid);
                int idx = RoleIdentity.indexOf(list, serverid, uid);
                if (idx >= 0) list.get(idx).enabled = enabled;
                saveRoles(ctx, fpid, list);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 旧口径开关（只传 sid）：命中该 sid 第一行。同服多角色请改用 (sid,uid) 版。 */
    public static void setEnabled(Context ctx, String fpid, long serverid, boolean enabled) {
        setEnabled(ctx, fpid, serverid, 0, enabled);
    }

    /** 按 (fpid, serverid, uid) 取已登记角色；未登记返回 null。uid<=0 = 该 sid 第一行（旧口径）。 */
    public static Role roleOf(Context ctx, String fpid, long serverid, long uid) {
        if (fpid == null || serverid <= 0) return null;
        List<Role> list = roles(ctx, fpid);
        int idx = RoleIdentity.indexOf(list, serverid, uid);
        return idx < 0 ? null : list.get(idx);
    }

    /** 旧口径查询（只传 sid）：该 sid 第一行。同服多角色请改用 (sid,uid) 版。 */
    public static Role roleOf(Context ctx, String fpid, long serverid) {
        return roleOf(ctx, fpid, serverid, 0);
    }

    /**
     * 定点改写某角色的 uid（钉扎自愈用：串区时期登记的错 uid 需要纠正）。
     *
     * <p>按 (sid, fromUid) 定位（fromUid<=0 或严格找不到时退化该 sid 第一行——历史自愈
     * 调用点不知道旧 uid）；改为 toUid 后若已存在另一条 (sid, toUid) 记录 → 两条合并
     * （保留正确那条，enabled 取或，避免凭空丢失工作区成员），绝不留重复行。
     */
    public static void updateUid(Context ctx, String fpid, long serverid, long fromUid, long toUid) {
        if (fpid == null || serverid <= 0) return;
        synchronized (WRITE_LOCK) {
            try {
                List<Role> list = roles(ctx, fpid);
                int idx = RoleIdentity.indexOf(list, serverid, fromUid);
                if (idx < 0 && fromUid > 0) idx = RoleIdentity.indexOf(list, serverid, 0);
                if (idx < 0) return;
                Role target = list.get(idx);
                target.uid = toUid;
                if (toUid > 0) {
                    int dup = RoleIdentity.indexOf(list, serverid, toUid);
                    if (dup >= 0 && dup != idx) {
                        Role keep = list.get(dup);
                        keep.enabled = keep.enabled || target.enabled;
                        if (keep.name == null || keep.name.isEmpty()) keep.name = target.name;
                        if (keep.level <= 0) keep.level = target.level;
                        if (keep.power <= 0) keep.power = target.power;
                        if (keep.alliance == null || keep.alliance.isEmpty()) keep.alliance = target.alliance;
                        if (keep.allianceAbbr == null || keep.allianceAbbr.isEmpty()) keep.allianceAbbr = target.allianceAbbr;
                        list.remove(idx);
                    }
                }
                saveRoles(ctx, fpid, list);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 旧口径 uid 更新（只传 sid）：该 sid 第一行。同服多角色请改用 (sid, fromUid, toUid) 版。 */
    public static void updateUid(Context ctx, String fpid, long serverid, long uid) {
        updateUid(ctx, fpid, serverid, 0, uid);
    }

    /**
     * 发现角色登记（req_role_list 全量发现路径，HomeActivity「+ 添加角色」）：
     * 只带 name 的修正——复用 {@link #registerRole} 的去重/升级语义，并在<b>行内名字
     * 被修正</b>时清零 level/power/alliance（P3，QA 报告 §3.5）。
     *
     * <p>发现路径的 RoleInfo 只带 name，不带等级/战力/联盟。旧名字非空且与新名字不同
     * ⇒ 旧名字是错的（典型：被 P1-2 串行污染写过别人的元数据），随行的旧元数据同样
     * 无可信来源，一并清零避免同服两行更难分辨；名字未变（或旧名字为空=补全而非
     * 修正、新名字为空=不会覆盖）则不动，保住登录/采集学到的可信元数据。
     * 判定用 {@link RoleIdentity#shouldClearStaleMeta}（纯函数，有单测）。
     */
    public static void registerDiscoveredRole(Context ctx, String fpid, long serverid, long uid,
                                              String name) {
        if (fpid == null || fpid.isEmpty() || serverid <= 0) return;
        synchronized (WRITE_LOCK) {
            try {
                List<Role> list = roles(ctx, fpid);
                // registerRole 将命中的行（宽松口径，uid=0 旧行也在内）
                int idx = RoleIdentity.indexOfLoose(list, serverid, uid);
                boolean clearMeta = idx >= 0
                        && RoleIdentity.shouldClearStaleMeta(list.get(idx).name, name);
                registerRole(ctx, fpid, serverid, uid, name);
                if (clearMeta) {
                    // registerRole 后重读定位（uid=0 旧行可能被原地升级）
                    list = roles(ctx, fpid);
                    int idx2 = RoleIdentity.indexOfLoose(list, serverid, uid);
                    if (idx2 >= 0) {
                        Role r = list.get(idx2);
                        r.level = 0;
                        r.power = 0;
                        r.alliance = "";
                        saveRoles(ctx, fpid, list);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void saveRoles(Context ctx, String fpid, List<Role> list) throws org.json.JSONException {
        synchronized (WRITE_LOCK) {
            JSONArray arr = new JSONArray();
            for (Role r : list) {
                JSONObject o = new JSONObject();
                o.put("serverid", r.serverid);
                o.put("uid", r.uid);
                o.put("name", r.name);
                o.put("enabled", r.enabled);
                o.put("level", r.level);
                o.put("power", r.power);
                o.put("alliance", r.alliance == null ? "" : r.alliance);
                o.put("allianceAbbr", r.allianceAbbr == null ? "" : r.allianceAbbr);
                arr.put(o);
            }
            ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                    .edit().putString("roles:" + fpid, arr.toString()).apply();
        }
    }

    /**
     * 从登录/采集推送补全当前角色的元数据（昵称/等级/战力/联盟名）。
     *
     * <p>登录摘要（loginauth）经常拿不到角色昵称，导致角色表 name 永远为空——角色管理/
     * 角色切换里只剩"角色913"兜底名，用户感知为"没有任何数据"（真机：94450730）。
     * 数据源：notify_base_info（nickName/lv/power/uid/kid）+ notify_alliance_init
     * （alliance.name）。字段为空/缺失时不覆盖旧值，幂等可反复调用。
     */
    public static void syncRoleMeta(Context ctx, com.carlos.vault.wjdr.WjdrEngine engine, String fpid) {
        if (ctx == null || engine == null || fpid == null || fpid.isEmpty()) return;
        try {
            java.util.Map<String, Object> base = engine.notifyData("notify_base_info");
            if (base == null || base.isEmpty()) {
                try {
                    DailyRunner.debugLog(ctx, "syncRoleMeta: 无 base_info fpid=" + fpid
                            + " protos=" + (engine.notifyProtos().length() > 200
                            ? engine.notifyProtos().substring(0, 200) : engine.notifyProtos()));
                } catch (Throwable ignored) {
                }
                return;
            }
            long kid = num(base.get("kid"));
            long uid = num(base.get("uid"));
            if (kid <= 0 || uid <= 0) {
                try {
                    DailyRunner.debugLog(ctx, "syncRoleMeta: kid/uid 无效 fpid=" + fpid
                            + " kid=" + kid + " uid=" + uid);
                } catch (Throwable ignored) {
                }
                return;
            }
            String name = base.get("nickName") instanceof String ? (String) base.get("nickName") : "";
            long lv = num(base.get("lv"));
            long power = num(base.get("power"));
            String alliance = "";
            String allianceAbbr = "";
            try {
                java.util.Map<String, Object> ai = engine.notifyData("notify_alliance_init");
                Object all = ai == null ? null : ai.get("alliance");
                if (all instanceof java.util.Map) {
                    Object n = ((java.util.Map<?, ?>) all).get("name");
                    if (n instanceof String) alliance = (String) n;
                    Object ab = ((java.util.Map<?, ?>) all).get("abbr");
                    if (ab instanceof String) allianceAbbr = (String) ab;
                }
            } catch (Throwable ignored) {
            }
            registerRole(ctx, fpid, kid, uid, name, lv, power, alliance, allianceAbbr);
            try {
                DailyRunner.debugLog(ctx, "syncRoleMeta: ok fpid=" + fpid + " kid=" + kid
                        + " uid=" + uid + " name=" + name + " lv=" + lv + " power=" + power);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            try {
                DailyRunner.debugLog(ctx, "syncRoleMeta: 异常 " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** 删除账号时清理该凭证的角色/当前角色/任务开关，避免旧账号残留污染新账号状态。 */
    public static void removeAccount(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return;
        ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE).edit()
                .remove("roles:" + fpid)
                .remove("active:" + fpid)
                .remove(ACTIVE_KEY + fpid)
                .remove(ACTIVE_AT_KEY + fpid)
                .remove(DIRTY_KEY + fpid)
                .remove("picked:" + fpid)
                .apply();
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .remove("auto:" + fpid)
                .remove("keep:" + fpid)
                .remove("server:" + fpid)
                .remove("next:" + fpid)
                .remove("last:" + fpid)
                .remove("wake:" + fpid)
                .apply();
        SharedPreferences dc = ctx.getSharedPreferences("daily_config", Context.MODE_PRIVATE);
        SharedPreferences.Editor dce = dc.edit().remove("acc:" + fpid);
        for (String k : dc.getAll().keySet()) {
            if (k.startsWith("acc:" + fpid + ":")) dce.remove(k);
        }
        dce.apply();
        SharedPreferences dp = ctx.getSharedPreferences("daily_params", Context.MODE_PRIVATE);
        SharedPreferences.Editor dpe = dp.edit();
        for (String k : dp.getAll().keySet()) {
            if (k.startsWith("p:" + fpid + ":")) dpe.remove(k);
        }
        dpe.apply();
    }

    /**
     * 当前选中的角色（复合身份，工作区内切换）。
     *
     * <p>读取 {@code active2:<fpid>}（值 {@code "sid:uid"}）；缺省时回退旧键
     * {@code active:<fpid>}（long sid）并按角色表补 uid 后<b>惰性迁移</b>写回新键——
     * 存量设备无需手动迁移，之后一切写入都走复合键。从未选过返回 sid=-1。
     */
    public static ActiveRole activeRoleEx(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return new ActiveRole(-1, 0);
        SharedPreferences sp = ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE);
        String raw = sp.getString(ACTIVE_KEY + fpid, "");
        if (raw != null && !raw.isEmpty()) {
            long[] kv = RoleIdentity.parseKey(raw);
            if (kv != null && kv[0] > 0) return new ActiveRole(kv[0], kv[1]);
        }
        long legacy = sp.getLong("active:" + fpid, -1);
        if (legacy <= 0) return new ActiveRole(-1, 0);
        // 旧键迁移：sid 已知、uid 按角色表解析（优先工作区内角色），写回复合键
        List<Role> list = roles(ctx, fpid);
        long uid = RoleIdentity.resolveUidForSid(list, legacy, r -> ((Role) r).enabled);
        setActiveRole(ctx, fpid, legacy, uid);
        return new ActiveRole(legacy, uid);
    }

    /** 当前选中角色的 sid（无记录 -1）。兼容旧签名；同服多角色请用 {@link #activeRoleEx}。 */
    public static long activeRole(Context ctx, String fpid) {
        return activeRoleEx(ctx, fpid).sid;
    }

    /** 当前选中角色的 uid（0=未知/旧数据未迁移）。 */
    public static long activeRoleUid(Context ctx, String fpid) {
        return activeRoleEx(ctx, fpid).uid;
    }

    /** 设置当前角色（复合身份）。uid<=0 时按角色表解析后写入（旧签名行为保持一致）。 */
    public static void setActiveRole(Context ctx, String fpid, long serverid, long uid) {
        if (fpid == null || fpid.isEmpty()) return;
        long sid = serverid;
        long u = uid;
        if (sid > 0 && u <= 0) {
            u = RoleIdentity.resolveUidForSid(roles(ctx, fpid), sid, r -> ((Role) r).enabled);
        }
        ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .edit().putString(ACTIVE_KEY + fpid, RoleIdentity.key(sid, u))
                .putLong(ACTIVE_AT_KEY + fpid, System.currentTimeMillis())
                .putBoolean("picked:" + fpid, true).apply();
    }

    /**
     * 当前角色激活身份的写入时间（epoch ms；0=无记录/旧数据）。
     *
     * <p>与 {@link #activeRoleEx} 同源读取，供防回滚时间戳门控使用
     * （{@link RoleIdentity#shouldLearnActiveRole} 的 localTs）。
     */
    public static long activeRoleAt(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return 0;
        return ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .getLong(ACTIVE_AT_KEY + fpid, 0);
    }

    /**
     * 标记该凭证的当前角色切换尚未被服务器确认（UI 切换入口调用）。
     *
     * <p>2026-10-20 真实回归：sync 成功只代表服务器已收到 payload，会话重启是异步的；
     * 若此时就清 dirty，紧接着到达的旧快照 status 会通过 P2 门控把本地角色回滚，随后
     * 去抖 sync 又把旧角色写回服务器（真机：切隆科多后 3 秒服务器变回小鹿）。
     * 现在的清除时机 = {@code learnServerRole} 收到与本地一致的 activeUid（见
     * {@link #clearRoleDirty}），另加 {@value #DIRTY_TTL_MS}ms 超时兜底防卡死。
     */
    public static void markRoleDirty(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return;
        ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .edit().putBoolean(DIRTY_KEY + fpid, true)
                .putLong(DIRTY_AT_KEY + fpid, System.currentTimeMillis()).apply();
    }

    /** 待上传标记有效期：超时后不再拦截服务器角色回写（防服务端一直不开会话时永久锁死）。 */
    public static final long DIRTY_TTL_MS = 90_000L;

    /** 该凭证是否仍有「已切换但服务器尚未确认」的待确认标记（带 TTL）。 */
    public static boolean isRoleDirty(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return false;
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE);
        if (!sp.getBoolean(DIRTY_KEY + fpid, false)) return false;
        long at = sp.getLong(DIRTY_AT_KEY + fpid, 0);
        return at > 0 && System.currentTimeMillis() - at < DIRTY_TTL_MS;
    }

    /** 服务器已确认该凭证当前角色 = 本地角色：清除待确认标记。 */
    public static void clearRoleDirty(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return;
        ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .edit().remove(DIRTY_KEY + fpid)
                .remove(DIRTY_AT_KEY + fpid).apply();
    }

    /** 清空全部待上传标记（一次全量 sync 成功后调用：payload 含全部账号的当前角色）。 */
    public static void clearAllRoleDirty(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            boolean any = false;
            for (String k : sp.getAll().keySet()) {
                if (k != null && k.startsWith(DIRTY_KEY)) {
                    ed.remove(k);
                    any = true;
                }
            }
            if (any) ed.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 旧口径设置（只传 sid）：uid 按角色表解析（优先工作区内同 sid 角色）。 */
    public static void setActiveRole(Context ctx, String fpid, long serverid) {
        setActiveRole(ctx, fpid, serverid, 0);
    }

    /** 是否已明确选过角色（决定自动同步要不要自动挑主角色）。 */
    public static boolean rolePicked(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_roles", Context.MODE_PRIVATE)
                .getBoolean("picked:" + fpid, false);
    }

    /**
     * 自动挑主角色：优先名字与账号标签匹配的角色（如 913铁柱 → 铁柱、），
     * 否则用登录默认。仅在从未手动选择过时调用。
     *
     * <p>同服多角色时标签可能同时命中多行——取<b>第一个</b>匹配行并连同其 uid 一起
     * 设为当前角色，避免只记 sid 又落回第一行的旧歧义。
     */
    public static long pickMainRole(Context ctx, String fpid, String accountLabel, long loginDefaultSid) {
        try {
            String label = accountLabel == null ? "" : accountLabel.replaceAll("[^\\u4e00-\\u9fa5]", "");
            if (label.length() >= 2) {
                List<Role> list = roles(ctx, fpid);
                for (Role r : list) {
                    if (r.name != null && r.name.contains(label)) {
                        setActiveRole(ctx, fpid, r.serverid, r.uid);
                        return r.serverid;
                    }
                }
                // 前缀匹配（标签前2字）
                String head = label.substring(0, Math.min(2, label.length()));
                for (Role r : list) {
                    if (r.name != null && r.name.contains(head)) {
                        setActiveRole(ctx, fpid, r.serverid, r.uid);
                        return r.serverid;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        setActiveRole(ctx, fpid, loginDefaultSid);
        return loginDefaultSid;
    }

    // ---------- 自动调度（每凭证） ----------

    public static class Schedule {
        public boolean autoEnabled;
        public long serverid;        // 自动执行用哪个角色（0=默认）
        public long nextRunAt;       // epoch ms
        public long lastRunAt;
    }

    public static Schedule schedule(Context ctx, String fpid) {
        Schedule s = new Schedule();
        try {
            SharedPreferences sp = ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE);
            s.autoEnabled = sp.getBoolean("auto:" + fpid, false);
            s.serverid = sp.getLong("server:" + fpid, 0);
            s.nextRunAt = sp.getLong("next:" + fpid, 0);
            s.lastRunAt = sp.getLong("last:" + fpid, 0);
        } catch (Exception ignored) {
        }
        return s;
    }

    public static void saveSchedule(Context ctx, String fpid, Schedule s) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putBoolean("auto:" + fpid, s.autoEnabled)
                .putLong("server:" + fpid, s.serverid)
                .putLong("next:" + fpid, s.nextRunAt)
                .putLong("last:" + fpid, s.lastRunAt)
                .apply();
    }

    // ---------- 任务级 wake（比 nextRunAt 更细的"到点可动"时刻） ----------

    /**
     * 账号下次"到点可动"时刻（epoch ms；0=无）。
     *
     * <p>next:<fpid> 是账号级整轮日常的下次执行（时间窗内每天一次）；
     * wake:<fpid> 是任务级的更早时刻——当前由补给状态驱动（普通补给"下一次可领"
     * online_ts / 限时补给按策略提前量），到点由调度器只补领 supply 单功能，不重跑整轮。
     * 由 DailyRunner.scheduleNextSupplyWake 依据最新补给状态推算覆写。
     */
    public static long wakeAt(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).getLong("wake:" + fpid, 0);
    }

    public static void saveWakeAt(Context ctx, String fpid, long ms) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putLong("wake:" + fpid, ms).apply();
    }

    // ---------- 常驻在线（24h 会话开关，独立于每日自动日常；灰度期仅主力号开启） ----------

    /** 该账号是否 24h 常驻在线（RoleSessionKeeper.syncOnline 依据；默认 false）。 */
    public static boolean keepOnline(Context ctx, String fpid) {
        return ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE)
                .getBoolean("keep:" + fpid, false);
    }

    public static void saveKeepOnline(Context ctx, String fpid, boolean on) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putBoolean("keep:" + fpid, on).apply();
    }

    // ---------- 统一自动化开关（2026-10 起替代 auto + keep 双开关） ----------

    /**
     * 统一自动化开关：账号是否交给服务器执行任务。
     *
     * <p>迁移兼容：旧数据里 auto/keep 可能只有一个为 true（自动日常开、常驻关，或反之），
     * 任一为 true 都视为统一自动化已开启；下一次写入会把两者对齐。
     */
    public static boolean automationEnabled(Context ctx, String fpid) {
        if (fpid == null || fpid.isEmpty()) return false;
        return schedule(ctx, fpid).autoEnabled || keepOnline(ctx, fpid);
    }

    /**
     * 写统一开关：auto（服务器整轮调度）与 keep（服务器常驻会话）同时置位，
     * 保证“开关打开 = 服务器有会话且会跑整轮”。同时清掉本地 nextRunAt（本地调度已停用）。
     */
    public static void saveAutomation(Context ctx, String fpid, boolean on) {
        if (fpid == null || fpid.isEmpty()) return;
        Schedule s = schedule(ctx, fpid);
        s.autoEnabled = on;
        s.nextRunAt = 0;
        saveSchedule(ctx, fpid, s);
        saveKeepOnline(ctx, fpid, on);
    }

    // ---------- 全局时间窗 ----------

    /** 自动执行时间窗（小时，含端点）。默认 5:00-9:00。 */
    public static int[] window(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE);
        return new int[]{sp.getInt("window_start", 5), sp.getInt("window_end", 9)};
    }

    public static void saveWindow(Context ctx, int startHour, int endHour) {
        ctx.getSharedPreferences("daily_sched", Context.MODE_PRIVATE).edit()
                .putInt("window_start", startHour)
                .putInt("window_end", endHour)
                .apply();
    }
}
