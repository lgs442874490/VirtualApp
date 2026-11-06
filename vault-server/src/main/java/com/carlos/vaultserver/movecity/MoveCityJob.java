package com.carlos.vaultserver.movecity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 迁城任务模型（1:1 复刻网页版 {@code MoveCityService.MoveJob}，2026-11-06）。
 *
 * <p>状态机与文案逐字对齐原版：
 * <pre>
 * 已创建 → 准备中 → 等待执行 → 执行中 → 已完成/失败
 *             └→ 等待恢复（准备失败且 autoLogin 保护窗未过）
 * 已创建 → 已取消（仅未开始可取消；用户取消 / 账号删除 / 移除全部账号）
 * </pre>
 * 寻址差异：原版 {@code accountRoleId/accountId/accountUid/serverId} 四元组 →
 * 本项目 {@code fpid}（号库账号唯一标识，active 角色会话）；展示字段 {@code name} 保留。
 */
public final class MoveCityJob {

    /**
     * 单个迁城动作（原版 MoveCityServiceMoveAction：账号 + 目标坐标/王国）。
     *
     * <p>审阅修复（P0）：增加 {@code sid}/{@code uid} 角色身份锁定。原版按
     * {@code accountRoleId} 精确寻址角色；本项目按 {@code fpid} 取「当前 active 会话」，
     * 同服多角色账号在任务创建后切换角色会导致**迁错号**（消耗他人道具/改变他人城位）。
     * 创建时记录会话角色，执行前比对，不一致即失败要求重建任务。
     */
    public record Action(String fpid, String name, int x, int y, int kid, boolean initiallyOnline,
                         String sid, long uid) {

        /** 旧调用点兼容（无角色身份 = 不校验）。 */
        public Action(String fpid, String name, int x, int y, int kid, boolean initiallyOnline) {
            this(fpid, name, x, y, kid, initiallyOnline, "", 0L);
        }

        /** 角色标识（{@code sid:uid}）；空串=未知（旧任务/未登录），不参与校验。 */
        public String role() {
            if (sid == null || sid.isEmpty() || uid <= 0L) return "";
            return sid + ":" + uid;
        }

        public Action withRole(String sid, long uid) {
            return new Action(fpid, name, x, y, kid, initiallyOnline,
                    sid == null ? "" : sid, uid);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fpid", fpid);
            m.put("account_name", name == null ? "" : name);
            m.put("x", x);
            m.put("y", y);
            m.put("kid", kid);
            m.put("initially_online", initiallyOnline);
            m.put("sid", sid == null ? "" : sid);
            m.put("uid", uid);
            return m;
        }

        public static Action fromMap(Map<String, Object> m) {
            return new Action(
                    str(m.get("fpid")),
                    str(m.get("account_name")),
                    intValue(m.get("x")),
                    intValue(m.get("y")),
                    intValue(m.get("kid")),
                    Boolean.TRUE.equals(m.get("initially_online")),
                    str(m.get("sid")),
                    longValue(m.get("uid")));
        }
    }

    public final String id;
    /** 立即迁城 / 定时迁城。 */
    public final String type;
    public final MoveCityProtocol protocol;
    public final String executeAt;
    public final long executeAtMillis;
    public final long advanceMillis;
    public final boolean autoLogin;
    public final long createdAtMillis = System.currentTimeMillis();

    private final List<Action> actions;
    private final ReentrantLock actionsLock = new ReentrantLock();

    private volatile String status = "已创建";
    private volatile String error = "";
    private volatile List<Map<String, Object>> results = List.of();
    private volatile long preparedAtNanos;

    public MoveCityJob(String id, String type, MoveCityProtocol protocol, String executeAt,
                       long executeAtMillis, long advanceMillis, List<Action> actions, boolean autoLogin) {
        this.id = id;
        this.type = type;
        this.protocol = protocol;
        this.executeAt = executeAt == null ? "" : executeAt;
        this.executeAtMillis = executeAtMillis;
        this.advanceMillis = advanceMillis;
        this.actions = new ArrayList<>(actions);
        this.autoLogin = autoLogin;
    }

    public static MoveCityJob immediate(MoveCityProtocol protocol, List<Action> actions) {
        return new MoveCityJob(UUID.randomUUID().toString(), "立即迁城", protocol, "", 0L, 0L, actions, false);
    }

    // ---------- 状态机（原版 finished/started/cancelable 语义） ----------

    public boolean finished() {
        return "已完成".equals(status) || "失败".equals(status) || "已取消".equals(status);
    }

    public boolean started() {
        return "准备中".equals(status) || "等待恢复".equals(status)
                || "等待执行".equals(status) || "执行中".equals(status);
    }

    /** 仅未开始（已创建）的任务可取消（原版 cancel 语义）。 */
    public boolean cancelable() {
        return !finished() && !started();
    }

    // ---------- 字段访问 ----------

    public String status() {
        return status;
    }

    public void status(String status) {
        this.status = status;
    }

    public String error() {
        return error;
    }

    public void error(String error) {
        this.error = error == null ? "" : error;
    }

    public List<Map<String, Object>> results() {
        return results;
    }

    public void results(List<Map<String, Object>> results) {
        this.results = List.copyOf(results);
    }

    public long preparedAtNanos() {
        return preparedAtNanos;
    }

    public void preparedAtNanos(long nanos) {
        this.preparedAtNanos = nanos;
    }

    public int actionCount() {
        actionsLock.lock();
        try {
            return actions.size();
        } finally {
            actionsLock.unlock();
        }
    }

    public List<Action> actionsSnapshot() {
        actionsLock.lock();
        try {
            return List.copyOf(actions);
        } finally {
            actionsLock.unlock();
        }
    }

    /** 移除一个账号；不存在抛 IllegalArgumentException（原版 removeAction 语义）。 */
    public void removeAction(String fpid) {
        actionsLock.lock();
        try {
            boolean removed = actions.removeIf(a -> a.fpid.equals(fpid));
            if (!removed) {
                throw new IllegalArgumentException("定时任务中不存在该账号");
            }
        } finally {
            actionsLock.unlock();
        }
    }

    /** 原版 protectionDeadlineMillis = executeAt + 60s。 */
    public long protectionDeadlineMillis() {
        return executeAtMillis + 60_000L;
    }

    /** 排序键（原版 sortTime）：定时按执行时刻，立即按创建时刻。 */
    public long sortTime() {
        return executeAtMillis > 0 ? executeAtMillis : createdAtMillis;
    }

    // ---------- 序列化（字段名对齐原版 toMap；持久化与 API 共用） ----------

    public Map<String, Object> toMap() {
        long success = results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job_id", id);
        data.put("type", type);
        data.put("status", status);
        data.put("protocol", protocol.toMap());
        data.put("execute_at", executeAt);
        data.put("execute_at_millis", executeAtMillis);
        data.put("advance_millis", advanceMillis);
        data.put("created_at_millis", createdAtMillis);
        data.put("accounts", actionCount());
        data.put("account_details", actionMaps());
        data.put("auto_login", autoLogin);
        data.put("cancelable", cancelable());
        data.put("success", !results.isEmpty() && success == results.size());
        data.put("success_count", success);
        data.put("total", results.size());
        data.put("prepared_at_nanos", preparedAtNanos);
        data.put("error", error);
        data.put("results", results);
        return data;
    }

    private List<Map<String, Object>> actionMaps() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Action a : actionsSnapshot()) items.add(a.toMap());
        return items;
    }

    /** 从持久化 JSON 恢复（jobData 语义：完整恢复运行态，含 status/results/error）。 */
    public static MoveCityJob fromJson(JSONObject o) {
        JSONObject protocol = o.optJSONObject("protocol");
        MoveCityProtocol proto = protocol != null
                ? MoveCityProtocol.resolve(protocol.optString("proto", ""))
                : null;
        if (proto == null) return null;
        List<Action> actions = new ArrayList<>();
        JSONArray details = o.optJSONArray("account_details");
        if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                JSONObject d = details.optJSONObject(i);
                if (d == null) continue;
                String fpid = d.optString("fpid", "");
                if (fpid.isEmpty()) continue;
                actions.add(Action.fromMap(d.toMap()));
            }
        }
        if (actions.isEmpty()) return null;
        long advance = o.optLong("advance_millis", 0L);
        MoveCityJob job = new MoveCityJob(
                o.optString("job_id", UUID.randomUUID().toString()),
                o.optString("type", "定时迁城"),
                proto,
                o.optString("execute_at", ""),
                o.optLong("execute_at_millis", 0L),
                Math.max(0L, Math.min(100L, advance)),
                actions,
                o.optBoolean("auto_login", false));
        String status = o.optString("status", "");
        if (!status.isEmpty()) job.status(status);
        job.error(o.optString("error", ""));
        job.preparedAtNanos(o.optLong("prepared_at_nanos", 0L));
        JSONArray results = o.optJSONArray("results");
        if (results != null) {
            List<Map<String, Object>> restored = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null) restored.add(r.toMap());
            }
            job.results(restored);
        }
        return job;
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static int intValue(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static long longValue(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0L;
        }
    }
}
