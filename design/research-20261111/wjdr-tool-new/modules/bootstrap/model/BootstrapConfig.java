/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.model;

import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import java.util.LinkedHashMap;
import java.util.Map;

public record BootstrapConfig(boolean enabled, long targetServerId, int targetFurnaceLevel, String allianceName, boolean autoApplyWhenPrivate, String roleName) {
    public static final int MAX_ROLE_NAME_LENGTH = 16;
    public static final String SECTION = "settings";
    public static final int DEFAULT_TARGET_FURNACE_LEVEL = 10;
    public static final int MAX_TARGET_FURNACE_LEVEL = 10;

    public static BootstrapConfig defaults() {
        return new BootstrapConfig(false, 0L, 10, "", false, "");
    }

    public static BootstrapConfig fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return BootstrapConfig.defaults();
        }
        long rawFurnace = ProtocolValueSupport.number(raw.get("targetFurnaceLevel"));
        int furnaceLevel = rawFurnace <= 0L ? 10 : Math.clamp((long)((int)rawFurnace), (int)1, (int)10);
        return new BootstrapConfig(BootstrapConfig.booleanValue(raw.get("enabled")), Math.max(0L, ProtocolValueSupport.number(raw.get("targetServerId"))), furnaceLevel, BootstrapConfig.text(raw.get("allianceName")), BootstrapConfig.booleanValue(raw.get("autoApplyWhenPrivate")), BootstrapConfig.normalizeRoleName(raw.get("roleName")));
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", this.enabled);
        result.put("targetServerId", this.targetServerId);
        result.put("targetFurnaceLevel", this.targetFurnaceLevel);
        result.put("allianceName", this.allianceName);
        result.put("autoApplyWhenPrivate", this.autoApplyWhenPrivate);
        result.put("roleName", this.roleName);
        return result;
    }

    private static String normalizeRoleName(Object raw) {
        String value = BootstrapConfig.text(raw);
        return value.length() <= 16 ? value : value.substring(0, 16);
    }

    private static boolean booleanValue(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean) {
            Boolean value = (Boolean)raw;
            return value;
        }
        String text = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}

