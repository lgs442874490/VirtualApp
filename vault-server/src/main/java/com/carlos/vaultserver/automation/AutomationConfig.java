package com.carlos.vaultserver.automation;

import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整轮自动化配置（模块级 + 动作级）。
 *
 * <p>持久化建议：vault.json 的 account 增加 automation 字段；
 * 未提供时由旧 roleConfig/roleParams 迁移，保证旧账号零改动。
 */
public final class AutomationConfig {

    private final Map<String, ModuleConfig> modules = new LinkedHashMap<>();
    private final Map<String, Boolean> legacyActions = new LinkedHashMap<>();

    private AutomationConfig() {
    }

    /** 全部模块默认启用、动作沿用 ServerTasks/FnRegistry 的 defaultEnabled。 */
    public static AutomationConfig defaults(Collection<AutomationModule> modules) {
        AutomationConfig c = new AutomationConfig();
        for (AutomationModule m : modules) {
            JSONObject params = new JSONObject();
            Map<String, Boolean> overrides = new LinkedHashMap<>();
            c.modules.put(m.id(), new ModuleConfig(m.id(), true, params, overrides));
        }
        return c;
    }

    /** 从旧版 roleConfig/roleParams 迁移（动作 id 与旧 fnId 完全同名）。 */
    public static AutomationConfig fromLegacy(Collection<AutomationModule> modules,
                                              Map<String, Boolean> roleConfig,
                                              Map<String, JSONObject> roleParams) {
        AutomationConfig c = new AutomationConfig();
        Map<String, Boolean> rc = roleConfig == null
                ? java.util.Collections.emptyMap() : roleConfig;
        Map<String, JSONObject> rp = roleParams == null
                ? java.util.Collections.emptyMap() : roleParams;
        c.legacyActions.putAll(rc);
        for (AutomationModule m : modules) {
            JSONObject params = new JSONObject();
            Map<String, Boolean> overrides = new LinkedHashMap<>();
            for (ActionDefinition a : m.actions()) {
                if (rc.containsKey(a.id)) overrides.put(a.id, rc.get(a.id));
                JSONObject p = rp.get(a.id);
                if (p != null && p.length() > 0) params.put(a.id, p);
            }
            c.modules.put(m.id(), new ModuleConfig(m.id(), true, params, overrides));
        }
        return c;
    }

    public ModuleConfig module(String moduleId) {
        ModuleConfig m = modules.get(moduleId);
        if (m != null) return m;
        return new ModuleConfig(moduleId, false, new JSONObject(),
                java.util.Collections.emptyMap());
    }

    public boolean actionEnabled(String moduleId, String actionId, boolean fallback) {
        return module(moduleId).actionEnabled(actionId, fallback);
    }

    public Map<String, Boolean> legacyActions() {
        return legacyActions;
    }

    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        try {
            JSONObject mods = new JSONObject();
            for (Map.Entry<String, ModuleConfig> e : modules.entrySet()) {
                ModuleConfig c = e.getValue();
                JSONObject m = new JSONObject();
                m.put("enabled", c.enabled());
                m.put("params", c.params());
                JSONObject actions = new JSONObject();
                for (Map.Entry<String, Boolean> a : c.actionOverrides().entrySet()) {
                    actions.put(a.getKey(), a.getValue());
                }
                if (actions.length() > 0) m.put("actions", actions);
                mods.put(e.getKey(), m);
            }
            root.put("version", 1);
            root.put("modules", mods);
        } catch (Throwable ignored) {
        }
        return root;
    }
}
