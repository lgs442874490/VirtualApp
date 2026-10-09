package com.carlos.vault.wjdr.protocol;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ProtocolValueSupport {
    private ProtocolValueSupport() {
    }

    public static Map<String, Object> map(Object raw) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        if (raw instanceof Map) {
            Map source = (Map)raw;
            source.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        return result;
    }

    public static long number(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            return ((Number)raw).longValue();
        }
        if (raw instanceof Number) {
            Number value = (Number)raw;
            return ProtocolValueSupport.finiteLong(value.doubleValue());
        }
        try {
            return ProtocolValueSupport.finiteLong(Double.parseDouble(String.valueOf(raw).trim()));
        }
        catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long finiteLong(double value) {
        if (!Double.isFinite(value)) {
            return 0L;
        }
        if (value >= 9.223372036854776E18) {
            return Long.MAX_VALUE;
        }
        if (value <= -9.223372036854776E18) {
            return Long.MIN_VALUE;
        }
        return (long)value;
    }

    public static long positiveNumber(Object primary, Object fallback) {
        long value = ProtocolValueSupport.number(primary);
        return value > 0L ? value : Math.max(0L, ProtocolValueSupport.number(fallback));
    }

    public static long keyedNumber(Object raw, long key) {
        Object direct = ProtocolValueSupport.map(raw).get(String.valueOf(key));
        if (direct != null) {
            return ProtocolValueSupport.wrappedNumber(direct);
        }
        if (raw instanceof Map) {
            Map source = (Map)raw;
            return ProtocolValueSupport.keyedNumberIn(source.values(), key);
        }
        if (raw instanceof Collection) {
            Collection source = (Collection)raw;
            return ProtocolValueSupport.keyedNumberIn(source, key);
        }
        return 0L;
    }

    private static long keyedNumberIn(Collection<?> values, long key) {
        for (Object value : values) {
            Map<String, Object> item = ProtocolValueSupport.map(value);
            if (ProtocolValueSupport.number(item.get("k")) != key) continue;
            return ProtocolValueSupport.wrappedNumber(item.get("v"));
        }
        return 0L;
    }

    private static long wrappedNumber(Object raw) {
        Map<String, Object> item = ProtocolValueSupport.map(raw);
        return item.isEmpty() ? ProtocolValueSupport.number(raw) : ProtocolValueSupport.number(item.get("v"));
    }

    public static Set<Long> heroIds(Object raw) {
        LinkedHashSet<Long> result = new LinkedHashSet<Long>();
        ProtocolValueSupport.collectHeroIds(raw, result);
        return Collections.unmodifiableSet(result);
    }

    public static Set<Long> worldMarchHeroIds(Object raw) {
        Object values;
        Map<String, Object> march = ProtocolValueSupport.map(raw);
        Object formation = march.get("squad_list");
        if (formation == null) {
            formation = march.get("formation");
        }
        if ((values = ProtocolValueSupport.map(formation).get("hero_id")) == null) {
            return Set.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<Long>();
        ProtocolValueSupport.collectHeroValues(values, result);
        return Collections.unmodifiableSet(result);
    }

    private static void collectHeroIds(Object raw, Set<Long> target) {
        if (raw instanceof Map) {
            Map source = (Map)raw;
            source.forEach((key, value) -> {
                if ("hero_id".equals(String.valueOf(key))) {
                    ProtocolValueSupport.collectHeroValues(value, target);
                } else {
                    ProtocolValueSupport.collectHeroIds(value, target);
                }
            });
            return;
        }
        ProtocolValueSupport.visitValues(raw, value -> ProtocolValueSupport.collectHeroIds(value, target));
    }

    private static void collectHeroValues(Object raw, Set<Long> target) {
        Map source;
        if (raw instanceof Map && (source = (Map)raw).containsKey("v")) {
            ProtocolValueSupport.addHeroId(source.get("v"), target);
            return;
        }
        if (!ProtocolValueSupport.visitValues(raw, value -> ProtocolValueSupport.collectHeroValues(value, target))) {
            ProtocolValueSupport.addHeroId(raw, target);
        }
    }

    private static void addHeroId(Object raw, Set<Long> target) {
        long heroId = ProtocolValueSupport.number(raw);
        if (heroId > 0L) {
            target.add(heroId);
        }
    }

    private static boolean visitValues(Object raw, Consumer<Object> visitor) {
        if (raw instanceof Map) {
            Map source = (Map)raw;
            source.values().forEach(visitor);
            return true;
        }
        if (raw instanceof Collection) {
            Collection source = (Collection)raw;
            source.forEach(visitor);
            return true;
        }
        if (raw != null && raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int index = 0; index < length; ++index) {
                visitor.accept(Array.get(raw, index));
            }
            return true;
        }
        return false;
    }
}

