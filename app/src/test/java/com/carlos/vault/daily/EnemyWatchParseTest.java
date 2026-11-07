package com.carlos.vault.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EnemyWatch 观察哨推送解析（2026-11-07）：兼容槽位/列表/轮询/单数 march 四种实证形状。
 * 纯函数测试，不依赖 Context/SharedPreferences。
 */
public class EnemyWatchParseTest {

    private static Map<String, Object> march(long id, long type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("owner", 49422287L);
        m.put("type", type);
        return m;
    }

    @Test
    public void slotShape_returnsMarchEntry() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("march", march(13079L, 1L));
        slot.put("power", 0L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marches", Map.of("9.0", slot));

        List<Map<?, ?>> entries = EnemyWatch.situationEntries(content);
        assertEquals(1, entries.size());
        assertEquals(13079L, number(entryMarch(entries.get(0)).get("id")));
    }

    @Test
    public void listShape_returnsMarch() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marches", List.of(march(13080L, 2L)));

        List<Map<?, ?>> entries = EnemyWatch.situationEntries(content);
        assertEquals(1, entries.size());
        assertEquals(13080L, number(entries.get(0).get("id")));
    }

    @Test
    public void pollMarchsShape_returnsMarch() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marchs", List.of(march(13081L, 2L)));

        List<Map<?, ?>> entries = EnemyWatch.situationEntries(content);
        assertEquals(1, entries.size());
        assertEquals(13081L, number(entries.get(0).get("id")));
    }

    @Test
    public void singleMarchShape_keepsMarchAndPower() {
        // 实证 2026-10-15 §2.2：敌方进攻发射帧 {"march":{...},"power":115}
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", march(13080L, 2L));
        content.put("power", 115L);

        List<Map<?, ?>> entries = EnemyWatch.situationEntries(content);
        assertEquals(1, entries.size());
        assertEquals(13080L, number(entryMarch(entries.get(0)).get("id")));
        assertEquals(115L, number(entries.get(0).get("power")));
    }

    @Test
    public void emptyOrNull_returnsEmpty() {
        assertTrue(EnemyWatch.situationEntries(null).isEmpty());
        assertTrue(EnemyWatch.situationEntries(new LinkedHashMap<String, Object>()).isEmpty());
    }

    private static Map<?, ?> entryMarch(Map<?, ?> entry) {
        Object m = entry.get("march");
        return m instanceof Map ? (Map<?, ?>) m : entry;
    }

    private static long number(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }
}
