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

    public TaskContext(WjdrEngine engine, String fpid, long sid, StateStore store,
                       java.util.Map<String, JSONObject> params, String trigger) {
        this.engine = engine;
        this.fpid = fpid;
        this.sid = sid;
        this.store = store;
        this.params = params == null ? java.util.Collections.emptyMap() : params;
        this.trigger = trigger == null ? "服务器" : trigger;
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

    /** 读模块状态（App FnParams.modState 同款：mod:fpid:sid:fn）。 */
    public JSONObject modState(String fnId) {
        try {
            String raw = store.getString("mod:" + fpid + ":" + sid + ":" + fnId);
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
            store.putString("mod:" + fpid + ":" + sid + ":" + fnId, o.toString());
        } catch (Throwable ignored) {
        }
    }
}
