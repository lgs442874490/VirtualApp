package com.carlos.vault.daily;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 观察点配置校验（批次 B 2026-10-30）：App 端与服务器 {@code WatchPointStore} 1:1 的纯函数规则。
 *
 * <p>覆盖：uid&gt;0、坐标 0~1200、矩形 x2&gt;x1/y2&gt;y1 且跨度≤55、目标/矩形非空与数量上限、
 * uid 去重、友盟 aid/告警类型逗号串解析；非法输入必须返回可读错误（保存前拦截）。
 */
public class WatchpointRulesTest {

    private static JSONObject target(long uid, String nick, int x, int y) {
        return WatchpointRules.target(uid, nick, x, y);
    }

    private static JSONObject rect(String name, int x1, int y1, int x2, int y2) {
        return WatchpointRules.rect(name, x1, y1, x2, y2);
    }

    @Test
    public void singleFieldRules() {
        assertNull(WatchpointRules.validateUid("35371154"));
        assertNotNull(WatchpointRules.validateUid("0"));
        assertNotNull(WatchpointRules.validateUid("-1"));
        assertNotNull(WatchpointRules.validateUid("abc"));
        assertNotNull(WatchpointRules.validateUid(""));

        assertNull(WatchpointRules.validateCoord("0"));
        assertNull(WatchpointRules.validateCoord("1200"));
        assertNotNull(WatchpointRules.validateCoord("1201"));
        assertNotNull(WatchpointRules.validateCoord("-1"));
        assertNotNull(WatchpointRules.validateCoord("x"));

        assertNull(WatchpointRules.validateTarget("123", "敌A", "10", "1200"));
        assertNotNull(WatchpointRules.validateTarget("0", "敌A", "10", "20"));
        assertNotNull(WatchpointRules.validateTarget("123", "n".repeat(65), "10", "20"));
        assertNotNull(WatchpointRules.validateTarget("123", "敌A", "1201", "20"));

        assertNull(WatchpointRules.validateRect("A", "100", "100", "155", "155"));
        assertNotNull("56 跨度拒绝", WatchpointRules.validateRect("A", "100", "100", "156", "155"));
        assertNotNull("反向拒绝", WatchpointRules.validateRect("A", "100", "100", "100", "155"));
        assertNotNull(WatchpointRules.validateRect("A", "-1", "0", "10", "10"));
    }

    @Test
    public void listRules_emptyDuplicateAndLimits() {
        assertEquals("监视目标不能为空（至少 1 个）",
                WatchpointRules.validateTargets(new JSONArray()));
        JSONArray dup = new JSONArray().put(target(7L, "A", 1, 1)).put(target(7L, "B", 2, 2));
        assertNotNull(WatchpointRules.validateTargets(dup));

        JSONArray full = new JSONArray();
        for (int i = 1; i <= WatchpointRules.MAX_TARGETS; i++) full.put(target(i, "t", i, i));
        assertNull(WatchpointRules.validateTargets(full));
        full.put(target(999, "extra", 1, 1));
        assertTrue(WatchpointRules.validateTargets(full).contains("过多"));

        assertEquals("保护矩形不能为空（至少 1 个）",
                WatchpointRules.validateRects(new JSONArray()));
        JSONArray rects = new JSONArray().put(rect("A", 0, 0, 55, 55));
        assertNull(WatchpointRules.validateRects(rects));
    }

    @Test
    public void friendAidsAndAlertTypes() {
        assertNull(WatchpointRules.validateFriendAids(""));
        assertNull(WatchpointRules.validateFriendAids("555, 666,89700055"));
        assertNotNull(WatchpointRules.validateFriendAids("0,555"));
        assertNotNull(WatchpointRules.validateFriendAids("-1"));
        assertNotNull(WatchpointRules.validateFriendAids("555,x"));
        assertEquals(Set.of(555L, 666L), WatchpointRules.parseIds("555,666,555"));

        assertNull(WatchpointRules.validateAlertTypes("1,2,99,100,102"));
        assertNotNull(WatchpointRules.validateAlertTypes(""));
        assertNotNull(WatchpointRules.validateAlertTypes("0"));
        assertNotNull(WatchpointRules.validateAlertTypes("100000"));
        assertNotNull(WatchpointRules.validateAlertTypes("1,abc"));
        assertEquals("1,2,99", WatchpointRules.joinCsv(WatchpointRules.parseIds("1,2,99")));
    }

    @Test
    public void validateAll_reportsFirstErrorInOrder() {
        JSONArray targets = new JSONArray().put(target(1L, "A", 1, 1));
        JSONArray rects = new JSONArray().put(rect("R", 0, 0, 55, 55));
        assertNull(WatchpointRules.validateAll(targets, rects, "555", "1,2"));

        assertEquals("监视目标不能为空（至少 1 个）",
                WatchpointRules.validateAll(new JSONArray(), rects, "555", "1,2"));
        assertTrue(WatchpointRules.validateAll(targets, new JSONArray(), "555", "1,2")
                .contains("保护矩形"));
        assertTrue(WatchpointRules.validateAll(targets, rects, "x", "1,2").contains("友盟"));
        assertTrue(WatchpointRules.validateAll(targets, rects, "555", "").contains("告警类型"));
    }

    @Test
    public void parseTargets_keepsOrderAndFields() {
        JSONArray arr = new JSONArray()
                .put(target(11L, "敌A", 10, 20))
                .put(target(22L, "敌B", 0, 1200));
        List<Map<String, Object>> rows = WatchpointRules.parseTargets(arr);
        assertEquals(2, rows.size());
        assertEquals(11L, rows.get(0).get("uid"));
        assertEquals("敌A", rows.get(0).get("nick"));
        assertEquals(10, rows.get(0).get("x"));
        assertEquals(1200, rows.get(1).get("y"));
    }
}
