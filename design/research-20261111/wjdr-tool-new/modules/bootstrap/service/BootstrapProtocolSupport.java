/*
 * Decompiled with CFR 0.152.
 */
package com.wjdr.tool.modules.bootstrap.service;

import com.wjdr.tool.core.protocol.GameMessage;
import com.wjdr.tool.core.protocol.ProtocolValueSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class BootstrapProtocolSupport {
    private BootstrapProtocolSupport() {
    }

    static Map<String, Object> content(GameMessage response) {
        return response == null || response.getContent() == null ? Map.of() : response.getContent();
    }

    static long responseCode(GameMessage response) {
        return BootstrapProtocolSupport.number(BootstrapProtocolSupport.content(response).get("code"));
    }

    static Map<String, Object> map(Object raw) {
        return ProtocolValueSupport.map(raw);
    }

    static List<Map<String, Object>> maps(Object raw) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (raw instanceof Map) {
            Map source = (Map)raw;
            source.forEach((ignored, value) -> result.add(BootstrapProtocolSupport.map(value)));
            return result;
        }
        if (raw instanceof Iterable) {
            Iterable iterable = (Iterable)raw;
            for (Object value2 : iterable) {
                Map<String, Object> entry = BootstrapProtocolSupport.map(value2);
                if (entry.isEmpty()) continue;
                result.add(entry);
            }
        }
        return result;
    }

    static Iterable<?> iterable(Object raw) {
        Iterable iterable;
        if (raw instanceof Map) {
            Map source = (Map)raw;
            return source.values();
        }
        if (raw instanceof Iterable) {
            Iterable iterable2 = (Iterable)raw;
            iterable = iterable2;
        } else {
            iterable = List.of();
        }
        return iterable;
    }

    static long number(Object value) {
        return (long)Math.floor(Math.max(0.0, (double)ProtocolValueSupport.number(value)));
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            Boolean flag = (Boolean)value;
            return flag;
        }
        String text = BootstrapProtocolSupport.text(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }
}

