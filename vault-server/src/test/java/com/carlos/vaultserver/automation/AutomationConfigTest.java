package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.task.Modules;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutomationConfigTest {

    @Test
    public void legacyConfigMapsByActionId() {
        Collection<AutomationModule> modules = Modules.registry().modules().values();
        Map<String, Boolean> roleConfig = new LinkedHashMap<>();
        roleConfig.put("gather", false);
        roleConfig.put("medic", true);
        roleConfig.put("daily_mission", true);

        Map<String, JSONObject> roleParams = new LinkedHashMap<>();
        JSONObject gather = new JSONObject();
        gather.put("resources", new org.json.JSONArray().put(101).put(102));
        roleParams.put("gather", gather);

        AutomationConfig c = AutomationConfig.fromLegacy(modules, roleConfig, roleParams);
        assertFalse(c.actionEnabled("gather", "gather", true));
        assertTrue(c.actionEnabled("medic", "medic", false));
        assertTrue(c.actionEnabled("reward", "daily_mission", false));
        assertTrue(c.module("gather").paramsFor("gather").has("resources"));
    }

    @Test
    public void newConfigCanOverrideActionSwitch() {
        ModuleConfig cfg = new ModuleConfig("shop", true, new JSONObject(),
                Map.of("shop", false));
        assertFalse(cfg.actionEnabled("shop", true));
        assertTrue(cfg.actionEnabled("alliance_shop", true));
    }

    @Test
    public void toJsonRoundTripKeepsModules() {
        AutomationConfig c = AutomationConfig.defaults(Modules.registry().modules().values());
        JSONObject json = c.toJson();
        assertTrue(json.has("modules"));
        assertTrue(json.getJSONObject("modules").has("gather"));
    }
}
