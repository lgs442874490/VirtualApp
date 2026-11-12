package com.carlos.vault.wjdr;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link WjdrEngine} 资源增量累积缓冲单测（审查 P2-2 / 需求 R-05）：
 * 入站 {@code notify_resource_update} 按 id 累加（不覆盖），消费即清空。
 */
public class WjdrEngineTest {

    private static Map<String, Object> resourceUpdate(Object... idCountPairs) {
        java.util.ArrayList<Map<String, Object>> data = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < idCountPairs.length; i += 2) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", idCountPairs[i]);
            entry.put("count", idCountPairs[i + 1]);
            data.add(entry);
        }
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("data", data);
        return content;
    }

    private static long deltaOf(Map<String, Object> consumed, long id) {
        Object data = consumed.get("data");
        assertTrue("data 必须是数组", data instanceof List);
        for (Object raw : (List<?>) data) {
            if (!(raw instanceof Map)) continue;
            Map<?, ?> entry = (Map<?, ?>) raw;
            if (entry.get("id") instanceof Number
                    && ((Number) entry.get("id")).longValue() == id) {
                return ((Number) entry.get("count")).longValue();
            }
        }
        return 0L;
    }

    @Test
    public void consumeResourceUpdate_accumulatesSameIdUntilConsumed() {
        WjdrEngine engine = new WjdrEngine(null);
        engine.accumulateResourceUpdate(resourceUpdate(103L, -900L));
        engine.accumulateResourceUpdate(resourceUpdate(103L, -900L));

        Map<String, Object> consumed = engine.consumeResourceUpdate();
        assertNotNull("两次推送应累积成一次消费", consumed);
        assertEquals("同 id 增量累加：-900 + -900", -1800L, deltaOf(consumed, 103L));
        assertNull("消费即清空", engine.consumeResourceUpdate());
    }

    @Test
    public void consumeResourceUpdate_multipleIdsAndSigns() {
        WjdrEngine engine = new WjdrEngine(null);
        engine.accumulateResourceUpdate(resourceUpdate(103L, -500L, 104L, 300L));
        engine.accumulateResourceUpdate(resourceUpdate(103L, 200L, 100001L, 2L));

        Map<String, Object> consumed = engine.consumeResourceUpdate();
        assertNotNull(consumed);
        assertEquals(-300L, deltaOf(consumed, 103L));
        assertEquals(300L, deltaOf(consumed, 104L));
        assertEquals(2L, deltaOf(consumed, 100001L));
    }

    @Test
    public void consumeResourceUpdate_noPush_returnsNull() {
        WjdrEngine engine = new WjdrEngine(null);
        assertNull("无推送时返回 null（syncLoadData 回退旧路径）",
                engine.consumeResourceUpdate());
    }
}
