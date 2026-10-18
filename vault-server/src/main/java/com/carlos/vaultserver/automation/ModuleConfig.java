package com.carlos.vaultserver.automation;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * 单模块配置视图。
 *
 * <p>兼容旧版 App 同步的 roleConfig/roleParams：
 * - 动作开关：旧 roleConfig 的 fnId→bool；
 * - 动作参数：旧 roleParams 的 fnId→JSONObject；
 * - 新配置可在 modules.&lt;id&gt;.actions.&lt;actionId&gt; 覆盖，
 *   模块级参数放 modules.&lt;id&gt;.params。
 */
public final class ModuleConfig {

    private final String moduleId;
    private final boolean enabled;
    private final JSONObject params;
    private final Map<String, Boolean> actionOverrides;

    public ModuleConfig(String moduleId, boolean enabled, JSONObject params,
                        Map<String, Boolean> actionOverrides) {
        this.moduleId = moduleId;
        this.enabled = enabled;
        this.params = params == null ? new JSONObject() : params;
        this.actionOverrides = actionOverrides == null
                ? Collections.emptyMap() : actionOverrides;
    }

    public String moduleId() {
        return moduleId;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean actionEnabled(String actionId, boolean fallback) {
        if (!enabled) return false;
        if (actionOverrides.containsKey(actionId)) {
            return Boolean.TRUE.equals(actionOverrides.get(actionId));
        }
        JSONObject actions = params.optJSONObject("actions");
        if (actions != null && actions.has(actionId)) {
            return actions.optBoolean(actionId, fallback);
        }
        return fallback;
    }

    public JSONObject params() {
        return params;
    }

    public Map<String, Boolean> actionOverrides() {
        return actionOverrides;
    }

    /** 动作级参数（旧 FnParams 的 p:fpid:sid:fn 形状）。 */
    public JSONObject paramsFor(String actionId) {
        JSONObject p = params.optJSONObject(actionId);
        return p == null ? new JSONObject() : p;
    }

    public boolean bool(String key, boolean fallback) {
        return params.optBoolean(key, fallback);
    }

    public int integer(String key, int fallback) {
        return params.optInt(key, fallback);
    }

    public long longValue(String key, long fallback) {
        return params.optLong(key, fallback);
    }

    public String string(String key, String fallback) {
        return params.optString(key, fallback);
    }
}
