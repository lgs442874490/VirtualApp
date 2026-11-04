package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.StateStore;

import org.json.JSONObject;

/**
 * 单任务执行上下文：替代 App 端的 android.content.Context 依赖面——
 * 配置（子项开关/策略，来自 App 同步的参数）与状态写入（mod:… 键，与 App daily_state 一致）。
 */
public final class TaskContext {

    public final WjdrEngine engine;
    public final String fpid;
    public final long sid;
    public final StateStore store;
    /** 触发来源（写入 mod 的 _trg）：定时/手动/服务器。 */
    public final String trigger;
    /** 角色参数：fnId → 参数 JSONObject（App FnParams p:fpid:sid:fn 的同步副本）。 */
    private final java.util.Map<String, JSONObject> params;
    /**
     * 本角色当前启用任务判定（2026-10-25 P0 修复）：供任务内做“关联任务是否在跑”的决策，
     * 例如采集让位给情报前必须先确认 intel 确实启用（未启用时情报待办永远没人处理，
     * 采集会被永久让位）。null = 未知（旧测试/反应式路径）→ {@link #taskEnabled} 返回 true。
     */
    private final java.util.function.Predicate<String> enabledTasks;

    /**
     * 角色 uid（Phase 1，0=旧/主控兼容路径）：
     * <ul>
     *   <li>0：所有状态键保持旧型 mod:&lt;fpid&gt;:&lt;sid&gt;:&lt;fn&gt; —— 主控角色/App 显示链路 100% 不变；</li>
     *   <li>&gt;0：同账号同服多角色按 uid 隔离（SessionKeeper 对非主控角色每 30s 跑科技捐献时传入）。</li>
     * </ul>
     */
    public final long roleUid;

    /**
     * 是否为该账号当前 App 选中角色（active writer，Phase 2B，默认 true=旧路径）：
     * <ul>
     *   <li>true：本角色的写入同时镜像旧键（{@code mod:<fpid>:<sid>:<fn>} 等），App 显示链路不变；</li>
     *   <li>false：兄弟角色（非 App 选中）只写角色键，绝不覆盖旧键，避免 App 旧镜像短暂显示兄弟角色数据。</li>
     * </ul>
     * roleUid&lt;=0 时该字段无实际意义（角色键即旧键）。
     */
    public final boolean activeWriter;

    public TaskContext(WjdrEngine engine, String fpid, long sid, StateStore store,
                       java.util.Map<String, JSONObject> params, String trigger) {
        this(engine, fpid, sid, store, params, trigger, null, 0L, true);
    }

    /** 带启用任务判定的构造（整轮/单任务执行器传入；见 {@link #enabledPredicate}）。 */
    public TaskContext(WjdrEngine engine, String fpid, long sid, StateStore store,
                       java.util.Map<String, JSONObject> params, String trigger,
                       java.util.function.Predicate<String> enabledTasks) {
        this(engine, fpid, sid, store, params, trigger, enabledTasks, 0L, true);
    }

    /**
     * Phase 1：带角色 uid 的构造（同服多角色状态隔离）。roleUid<=0 等价旧构造，行为完全不变。
     */
    public TaskContext(WjdrEngine engine, String fpid, long sid, StateStore store,
                       java.util.Map<String, JSONObject> params, String trigger,
                       java.util.function.Predicate<String> enabledTasks, long roleUid) {
        this(engine, fpid, sid, store, params, trigger, enabledTasks, roleUid, true);
    }

    /**
     * Phase 2B：带角色 uid + activeWriter 的构造（整轮/单任务执行器按角色传入）。
     *
     * @param roleUid      角色 uid（&gt;0 时任务状态键按角色隔离；0=旧/兼容路径）
     * @param activeWriter 是否 App 当前选中角色（仅它镜像写旧键）；true 等价 Phase 2B 之前的全部构造
     */
    public TaskContext(WjdrEngine engine, String fpid, long sid, StateStore store,
                       java.util.Map<String, JSONObject> params, String trigger,
                       java.util.function.Predicate<String> enabledTasks, long roleUid,
                       boolean activeWriter) {
        this.engine = engine;
        this.fpid = fpid;
        this.sid = sid;
        this.store = store;
        this.params = params == null ? java.util.Collections.emptyMap() : params;
        this.trigger = trigger == null ? "服务器" : trigger;
        this.enabledTasks = enabledTasks;
        this.roleUid = roleUid;
        this.activeWriter = activeWriter;
    }

    /**
     * 该角色某任务是否启用：roleConfig 显式值优先，缺省回退目录默认（与执行器选任务的语义一致）。
     * 与 {@link #taskEnabled} 配套；执行器在创建 TaskContext 时传入。
     */
    public static java.util.function.Predicate<String> enabledPredicate(
            java.util.Map<String, Boolean> roleConfig) {
        return fnId -> {
            if (fnId == null || fnId.isEmpty()) return false;
            Boolean explicit = roleConfig == null ? null : roleConfig.get(fnId);
            if (explicit != null) return explicit;
            ServerTasks.TaskDef def = ServerTasks.byId(fnId);
            return def != null && def.enabledByDefault;
        };
    }

    /** 该角色某任务是否启用（无判定信息时保守返回 true，保持旧行为）。 */
    public boolean taskEnabled(String fnId) {
        if (enabledTasks == null) return true;
        try {
            return enabledTasks.test(fnId);
        } catch (Throwable t) {
            return true;
        }
    }

    private JSONObject fnParams(String fnId) {
        JSONObject p = params.get(fnId);
        return p == null ? new JSONObject() : p;
    }

    /** 该角色的任务参数原始 JSON（App FnParams.params 同源同步副本；无则空对象）。 */
    public JSONObject params(String fnId) {
        return fnParams(fnId);
    }

    /** 子项开关（App FnParams.subOn 同款：sub:{subId:bool}）。 */
    public boolean subOn(String fnId, String subId, boolean def) {
        try {
            JSONObject sub = fnParams(fnId).optJSONObject("sub");
            if (sub != null && sub.has(subId)) return sub.getBoolean(subId);
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** 子项开关是否显式存在（用于新旧配置兼容判定）。 */
    public boolean hasSub(String fnId, String subId) {
        try {
            JSONObject sub = fnParams(fnId).optJSONObject("sub");
            return sub != null && sub.has(subId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 子项策略是否显式存在（用于新旧配置兼容判定）。 */
    public boolean hasStrategy(String fnId, String subId) {
        try {
            JSONObject st = fnParams(fnId).optJSONObject("strategy");
            return st != null && st.has(subId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 子项策略（App FnParams.strategy 同款：strategy:{subId:value}）。 */
    public String strategy(String fnId, String subId, String def) {
        try {
            JSONObject st = fnParams(fnId).optJSONObject("strategy");
            if (st != null && st.has(subId)) return st.getString(subId);
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** 自定义策略分钟数（App FnParams.strategyMinutes 同款）。 */
    public int strategyMinutes(String fnId, String subId, int def) {
        return fnParams(fnId).optInt("strategy_min_" + subId, def);
    }

    /**
     * mod 状态键（Phase 1 按角色隔离）：roleUid&gt;0 → mod:&lt;fpid&gt;:&lt;sid&gt;:&lt;uid&gt;:&lt;fn&gt;；
     * roleUid&lt;=0 → 旧键 mod:&lt;fpid&gt;:&lt;sid&gt;:&lt;fn&gt;（包级可见便于单测锁定键型）。
     */
    static String modKey(String fpid, long sid, long roleUid, String fnId) {
        return roleUid > 0L
                ? "mod:" + fpid + ":" + sid + ":" + roleUid + ":" + fnId
                : "mod:" + fpid + ":" + sid + ":" + fnId;
    }

    /** 读模块状态（App FnParams.modState 同款：mod:fpid:sid:fn；角色键缺失时回退旧键一次）。 */
    public JSONObject modState(String fnId) {
        try {
            String raw = store.getString(modKey(fpid, sid, roleUid, fnId));
            if (raw == null && roleUid > 0L) {
                // 迁移兼容：本角色首次没有角色键 → 复用旧（主控）键里的状态作为初始值；
                // 随后的 saveModText 只写角色键，旧键不被改写（主控/App 链路不变）。
                raw = store.getString(modKey(fpid, sid, 0L, fnId));
            }
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 写子项状态行（App FnParams.saveModText 同款：文案 + _ts + _trg）。 */
    public void saveModText(String fnId, String subId, String text) {
        try {
            JSONObject o = modState(fnId);
            o.put(subId, text);
            o.put(subId + "_ts", System.currentTimeMillis());
            o.put(subId + "_trg", trigger);
            store.putString(modKey(fpid, sid, roleUid, fnId), o.toString());
        } catch (Throwable ignored) {
        }
    }
}
