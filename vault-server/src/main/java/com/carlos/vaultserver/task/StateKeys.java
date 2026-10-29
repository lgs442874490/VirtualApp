package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

/**
 * 状态键统一入口（R2 Phase 2，2026-10-29）。
 *
 * <p>现状问题：全仓 20+ 文件各自裸拼 {@code prefix:fpid:sid…}，同账号同服双角色会互相覆盖
 * （生产 888 服两角色在线：{@code st:*} 666 键、{@code log:*} 258 键、{@code srv:cad:*} 83 键
 * 全部不带 uid）。本类把 Phase 2 需要的键型集中定义，业务代码只允许经这里构造键。
 *
 * <p>三形态（与方案文档一致）：
 * <ul>
 *   <li><b>角色级 R</b>：{@code <family>:<fpid>:<sid>:<uid>:<rest>}（uid>0；uid<=0 退化为旧键）；</li>
 *   <li><b>账号级 A</b>：{@code <family>:<fpid>[:<sid>]}（保持账号级语义，如 lastrun/alert）；</li>
 *   <li><b>全局 G</b>：{@code <family>[:<day>]}（如 srv:cad:stats / srv:digest）。</li>
 * </ul>
 *
 * <p>兼容策略：写角色键；**旧键仅由 active 角色镜像写**（由调用方判断 active 后调用 legacy 方法）；
 * 读优先角色键，miss 回退旧键（{@link #readWithLegacy}）。
 */
public final class StateKeys {

    private StateKeys() {
    }

    // ---------------- 通用构造 ----------------

    /** 角色级键：{@code family:fpid:sid:uid[:rest]}。uid<=0 退化为旧键 {@code family:fpid:sid[:rest]}。 */
    public static String role(String family, RoleKey role, String rest) {
        if (role == null) return family;
        StringBuilder sb = new StringBuilder(family).append(':').append(role.fpid).append(':').append(role.sid);
        if (role.roleScoped()) sb.append(':').append(role.uid);
        if (rest != null && !rest.isEmpty()) sb.append(':').append(rest);
        return sb.toString();
    }

    /** 旧键（Phase 1 前形态）：{@code family:fpid:sid[:rest]}。 */
    public static String legacy(String family, RoleKey role, String rest) {
        if (role == null) return family;
        String k = family + ":" + role.fpid + ":" + role.sid;
        return (rest == null || rest.isEmpty()) ? k : k + ":" + rest;
    }

    /** 账号级键：{@code family:fpid[:rest]}。 */
    public static String account(String family, String fpid, String rest) {
        String k = family + ":" + (fpid == null ? "" : fpid);
        return (rest == null || rest.isEmpty()) ? k : k + ":" + rest;
    }

    /** 全局键：{@code family[:rest]}。 */
    public static String global(String family, String rest) {
        return (rest == null || rest.isEmpty()) ? family : family + ":" + rest;
    }

    // ---------------- 具体键型 ----------------

    /** 节奏下次到期：{@code srv:cad:<fpid>:<sid>:<uid>:<fn>}。 */
    public static String cadence(RoleKey role, String fn) {
        return role("srv:cad", role, fn);
    }

    public static String cadenceLegacy(RoleKey role, String fn) {
        return legacy("srv:cad", role, fn);
    }

    /** 当日结果状态机：{@code st:<day>:<fpid>:<sid>:<uid>:<fn>}。 */
    public static String dayState(String day, RoleKey role, String fn) {
        return role("st:" + day, role, fn);
    }

    public static String dayStateLegacy(String day, RoleKey role, String fn) {
        return legacy("st:" + day, role, fn);
    }

    /** 角色最近派单时刻：{@code srv:cadlast:<fpid>:<sid>:<uid>}。 */
    public static String cadenceLast(RoleKey role) {
        return role("srv:cadlast", role, null);
    }

    /** 整轮最近执行：{@code srv:lastrun:<fpid>:<sid>:<uid>}。 */
    public static String lastRun(RoleKey role) {
        return role("srv:lastrun", role, null);
    }

    public static String lastRunLegacy(RoleKey role) {
        return legacy("srv:lastrun", role, null);
    }

    /** 补给补领去重：{@code srv:supplyfire:<fpid>:<sid>:<uid>}。 */
    public static String supplyFired(RoleKey role) {
        return role("srv:supplyfire", role, null);
    }

    /** 任务结果日志：{@code log:<fpid>:<sid>:<uid>:<fn>}。 */
    public static String taskLog(RoleKey role, String fn) {
        return role("log", role, fn);
    }

    public static String taskLogLegacy(RoleKey role, String fn) {
        return legacy("log", role, fn);
    }

    /** 整轮摘要：{@code summary:<fpid>:<sid>:<uid>}。 */
    public static String summary(RoleKey role) {
        return role("summary", role, null);
    }

    // ---------------- 采集/资源状态 ----------------

    /** todo 列表：{@code todo:<fpid>:<sid>:<uid>}。 */
    public static String todo(RoleKey role) {
        return role("todo", role, null);
    }

    /** 资源快照：{@code res:<fpid>:<sid>:<uid>}。 */
    public static String resources(RoleKey role) {
        return role("res", role, null);
    }

    /** 行军/采集队列快照：{@code rallies:<fpid>:<sid>:<uid>}。 */
    public static String rallies(RoleKey role) {
        return role("rallies", role, null);
    }

    /** 任务进度：{@code questprog:<fpid>:<sid>:<uid>}。 */
    public static String questProgress(RoleKey role) {
        return role("questprog", role, null);
    }

    /** 已完成任务：{@code questdone:<fpid>:<sid>:<uid>}。 */
    public static String questDone(RoleKey role) {
        return role("questdone", role, null);
    }

    /** 任务时间戳：{@code questts:<fpid>:<sid>:<uid>}。 */
    public static String questTs(RoleKey role) {
        return role("questts", role, null);
    }

    /** 采集附属 mod 键：{@code mod:<fpid>:<sid>:<uid>:<sub>}（bear/supply/frostfire…）。 */
    public static String captureMod(RoleKey role, String sub) {
        return role("mod", role, sub);
    }

    // ---------------- 战争/告警 ----------------

    /** 战争态：{@code war:<fpid>:<sid>:<uid>}。 */
    public static String war(RoleKey role) {
        return role("war", role, null);
    }

    /** 旧战争态键（账号聚合视图）：{@code war:<fpid>:<sid>}。 */
    public static String warLegacy(RoleKey role) {
        return legacy("war", role, null);
    }

    // ---------------- 观测键解析（HealthMonitor/DailyDigest） ----------------

    /**
     * 解析 {@code log:}/{@code mod:} 类角色键：{@code <family>:<fpid>:<sid>[:<uid>]:<fn...>}。
     *
     * <p>UID 判定：第 4 段为**纯数字**时视为 uid（fnId 均为字母/下划线，不会误判），
     * 否则按旧键处理（uid=0，第 4 段起都是 fn；fn 可含冒号，如 {@code pet:alliance_chest}）。
     *
     * @return {@code {fpid, sid, uid, fn}}；形状非法返回 null
     */
    public static String[] splitRoleKey(String family, String key) {
        if (family == null || key == null || !key.startsWith(family + ":")) return null;
        String[] p = key.split(":");
        if (p.length < 4) return null;
        String fpid = p[1];
        String sid = p[2];
        if (fpid.isEmpty() || sid.isEmpty()) return null;
        long uid = 0L;
        int fnFrom = 3;
        if (p.length >= 5 && isNumericUid(p[3])) {
            uid = parseLong(p[3]);
            fnFrom = 4;
        }
        if (fnFrom >= p.length) return null;
        String fn = joinFrom(p, fnFrom);
        if (fn.isEmpty()) return null;
        return new String[]{fpid, sid, String.valueOf(uid), fn};
    }

    /**
     * 解析当日状态机键：{@code st:<yyyy-MM-dd>:<fpid>:<sid>[:<uid>]:<fn...>}。
     *
     * @return {@code {day, fpid, sid, uid, fn}}；形状非法返回 null
     */
    public static String[] splitDayKey(String key) {
        if (key == null || !key.startsWith("st:")) return null;
        String[] p = key.split(":");
        if (p.length < 5) return null;
        String day = p[1];
        String fpid = p[2];
        String sid = p[3];
        if (day.isEmpty() || fpid.isEmpty() || sid.isEmpty()) return null;
        long uid = 0L;
        int fnFrom = 4;
        if (p.length >= 6 && isNumericUid(p[4])) {
            uid = parseLong(p[4]);
            fnFrom = 5;
        }
        if (fnFrom >= p.length) return null;
        String fn = joinFrom(p, fnFrom);
        if (fn.isEmpty()) return null;
        return new String[]{day, fpid, sid, String.valueOf(uid), fn};
    }

    /** 纯数字且长度合理 → 视为 uid 段；fnId 均为字母/下划线/冒号，不会命中。 */
    private static boolean isNumericUid(String seg) {
        if (seg == null || seg.isEmpty() || seg.length() > 19) return false;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static long parseLong(String seg) {
        try {
            return Long.parseLong(seg);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String joinFrom(String[] p, int from) {
        if (from >= p.length) return "";
        StringBuilder sb = new StringBuilder(p[from]);
        for (int i = from + 1; i < p.length; i++) sb.append(':').append(p[i]);
        return sb.toString();
    }

    // ---------------- 读兼容 ----------------

    /**
     * 读角色键，miss 时回退旧键（惰性迁移；只用于“状态连续性”场景，写侧仍只写角色键）。
     *
     * @return 原始字符串；两处都没有返回 null
     */
    public static String readWithLegacy(StateStore store, String roleKey, String legacyKey) {
        if (store == null) return null;
        try {
            String v = store.getString(roleKey);
            if (v != null) return v;
            if (legacyKey != null && !legacyKey.equals(roleKey)) return store.getString(legacyKey);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 把旧键内容惰性迁移到角色键（只在角色键缺失时写一次；失败忽略）。
     */
    public static void migrateLegacyIfAbsent(StateStore store, String roleKey, String legacyKey) {
        if (store == null || roleKey == null || legacyKey == null || roleKey.equals(legacyKey)) return;
        try {
            if (store.getString(roleKey) != null) return;
            String v = store.getString(legacyKey);
            if (v != null) store.putString(roleKey, v);
        } catch (Throwable ignored) {
        }
    }
}
