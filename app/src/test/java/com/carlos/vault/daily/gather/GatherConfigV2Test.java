package com.carlos.vault.daily.gather;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link GatherConfigV2} 纯 JVM 单测（不依赖 Android）。
 *
 * <p>覆盖：默认/空配置迁移等价、旧键迁移（resources_named+min_level+max_queues+full、
 * resources 1-4、ROLE 编队只给队列1、soldiers、max_queues 夹取）、v2 优先/宽容解析、
 * round-trip、旧键双写投影（含幂等）、validate 各类非法、searchLevels/resId/maxLevel
 * 等纯函数、summary 文案。
 */
public class GatherConfigV2Test {

    // ==================== 默认 / 迁移 ====================

    @Test
    public void emptyConfigMigratesToLegacyDefaults() throws Exception {
        GatherConfigV2 c = GatherConfigV2.parse(new JSONObject());
        assertEquals(6, c.queues.size());
        for (int id = 1; id <= 3; id++) assertTrue("q" + id + " enabled", q(c, id).enabled);
        for (int id = 4; id <= 6; id++) assertFalse("q" + id + " enabled", q(c, id).enabled);
        assertEquals(GatherConfigV2.RES_MEAT, q(c, 1).resourceType);
        assertEquals(GatherConfigV2.RES_WOOD, q(c, 2).resourceType);
        // 空对象走 migrate()：默认资源按队列轮转 defaultResource(id) → 肉/木/煤
        assertEquals(GatherConfigV2.RES_COAL, q(c, 3).resourceType);
        assertEquals(5, q(c, 1).level);
        assertFalse(c.fullResourceOnly);
        assertEquals(3, c.maxQueues);
        assertFalse(c.allianceMineEnabled);
        for (GatherConfigV2.QueueCfg queue : c.queues) {
            assertEquals(GatherConfigV2.DEFAULT_INFANTRY, queue.infantry);
            assertEquals(GatherConfigV2.DEFAULT_LANCER, queue.lancer);
            assertEquals(GatherConfigV2.DEFAULT_MARKSMAN, queue.marksman);
        }
    }

    @Test
    public void nullParamsFallsBackToLegacyDefaults() throws Exception {
        // null 参数走 legacyDefaults()（默认配置）；空对象走 migrate()（旧键迁移），两者语义不同
        assertJsonEqual(v2Of(GatherConfigV2.legacyDefaults()), v2Of(GatherConfigV2.parse(null)));
    }

    @Test
    public void badV2ShapeFallsBackToMigration() throws Exception {
        JSONObject p = new JSONObject();
        p.put("v2", "not-an-object");
        p.put("min_level", 6);
        p.put("max_queues", 2);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        assertFalse(GatherConfigV2.hasV2(p));
        assertTrue(q(c, 1).enabled);
        assertTrue(q(c, 2).enabled);
        assertFalse(q(c, 3).enabled);
        assertEquals(6, q(c, 1).level);
    }

    @Test
    public void v2TakesPriorityOverLegacyKeys() throws Exception {
        JSONObject p = new JSONObject();
        p.put("min_level", 3);
        p.put("max_queues", 5);
        p.put("resources_named", new JSONArray().put("COAL"));
        JSONObject v2 = new JSONObject();
        v2.put("version", 2);
        JSONArray queues = new JSONArray();
        queues.put(new JSONObject().put("id", 1).put("enabled", true)
                .put("resourceType", "IRON").put("resourceLevel", 9));
        v2.put("queues", queues);
        v2.put("fullResourceOnly", true);
        v2.put("maxQueues", 1);
        p.put("v2", v2);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        assertEquals(GatherConfigV2.RES_IRON, q(c, 1).resourceType);
        assertEquals(9, q(c, 1).level);
        assertTrue(c.fullResourceOnly);
        assertEquals(1, c.maxQueues);
        assertEquals(GatherConfigV2.RES_WOOD, q(c, 2).resourceType); // 缺失队列用默认补齐
    }

    @Test
    public void migrateNamedResourcesMinLevelMaxQueuesFull() throws Exception {
        JSONObject p = new JSONObject();
        p.put("resources_named", new JSONArray().put("COAL").put("IRON"));
        p.put("min_level", 7);
        p.put("max_queues", 2);
        JSONObject strategy = new JSONObject();
        strategy.put("send", "full");
        p.put("strategy", strategy);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        assertEquals(GatherConfigV2.RES_COAL, q(c, 1).resourceType);
        assertEquals(GatherConfigV2.RES_IRON, q(c, 2).resourceType);
        assertTrue(q(c, 1).enabled);
        assertTrue(q(c, 2).enabled);
        assertFalse(q(c, 3).enabled);
        assertEquals(7, q(c, 1).level);
        assertEquals(7, q(c, 2).level);
        assertTrue(c.fullResourceOnly);
        assertEquals(2, c.maxQueues);
    }

    @Test
    public void migrateNumericResources1to4() throws Exception {
        JSONObject p = new JSONObject();
        JSONArray resources = new JSONArray();
        resources.put(1);
        resources.put(2);
        resources.put(3);
        resources.put(4);
        p.put("resources", resources);
        p.put("max_queues", 4);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        assertEquals(GatherConfigV2.RES_MEAT, q(c, 1).resourceType);
        assertEquals(GatherConfigV2.RES_WOOD, q(c, 2).resourceType);
        assertEquals(GatherConfigV2.RES_COAL, q(c, 3).resourceType);
        assertEquals(GatherConfigV2.RES_IRON, q(c, 4).resourceType);
        // 第 5 条起按资源池轮转（旧行为：多选池循环分配），但 max_queues=4 → 5/6 关闭
        assertEquals(GatherConfigV2.RES_MEAT, q(c, 5).resourceType);
        assertFalse(q(c, 5).enabled);
    }

    @Test
    public void migrateRoleFormationOnlyFirstQueue() throws Exception {
        JSONObject p = new JSONObject();
        p.put("formation_mode", "ROLE");
        p.put("formation_id", 9L);
        p.put("max_queues", 3);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        assertTrue(q(c, 1).isRole());
        assertEquals(9L, q(c, 1).formationId);
        for (int id = 2; id <= 6; id++) {
            assertFalse("q" + id + " must be AUTOMATIC", q(c, id).isRole());
            assertEquals(0L, q(c, id).formationId);
        }
    }

    @Test
    public void migrateSoldiersOverride() throws Exception {
        JSONObject p = new JSONObject();
        JSONObject soldiers = new JSONObject();
        soldiers.put("infantry", 50000L);
        soldiers.put("lancer", 20000L);
        soldiers.put("marksman", 25000L);
        p.put("soldiers", soldiers);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        for (int id = 1; id <= 6; id++) {
            assertEquals(50000L, q(c, id).infantry);
            assertEquals(20000L, q(c, id).lancer);
            assertEquals(25000L, q(c, id).marksman);
        }
    }

    @Test
    public void migrateMaxQueuesClampedTo1to5() throws Exception {
        JSONObject high = new JSONObject();
        high.put("max_queues", 6);
        GatherConfigV2 c1 = GatherConfigV2.parse(high);
        assertEquals(5, c1.maxQueues);
        assertTrue(q(c1, 5).enabled);
        assertFalse(q(c1, 6).enabled);

        JSONObject zero = new JSONObject();
        zero.put("max_queues", 0);
        GatherConfigV2 c2 = GatherConfigV2.parse(zero);
        assertEquals(1, c2.maxQueues);
        assertTrue(q(c2, 1).enabled);
        assertFalse(q(c2, 2).enabled);
    }

    @Test
    public void v2MissingQueuesFilledToSix() throws Exception {
        JSONObject v2 = new JSONObject();
        JSONArray queues = new JSONArray();
        queues.put(new JSONObject().put("id", 3).put("enabled", true)
                .put("resourceType", "COAL").put("resourceLevel", 6));
        v2.put("queues", queues);
        JSONObject p = new JSONObject();
        p.put("v2", v2);
        GatherConfigV2 c = GatherConfigV2.parse(p);
        assertEquals(6, c.queues.size());
        assertEquals(3, q(c, 3).id);
        assertTrue(q(c, 3).enabled);
        assertEquals(GatherConfigV2.RES_COAL, q(c, 3).resourceType);
        assertEquals(6, q(c, 3).level);
        assertFalse(q(c, 1).enabled);
        assertNull(c.queue(7));
    }

    // ==================== 写回 / 投影 ====================

    @Test
    public void roundTripThroughApplyTo() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        q(c, 1).resourceType = GatherConfigV2.RES_COAL;
        q(c, 1).level = 9;
        q(c, 1).formationMode = GatherConfigV2.MODE_ROLE;
        q(c, 1).formationId = 3L;
        q(c, 2).resourceType = GatherConfigV2.RES_IRON;
        q(c, 2).level = 7;
        q(c, 2).infantry = 11111L;
        c.fullResourceOnly = true;
        c.maxQueues = 4;
        c.allianceMineEnabled = true;

        JSONObject next = c.applyTo(new JSONObject());
        assertTrue(GatherConfigV2.hasV2(next));
        GatherConfigV2 parsed = GatherConfigV2.parse(next);
        assertJsonEqual(v2Of(c), v2Of(parsed));
        assertEquals(3L, q(parsed, 1).formationId);
        assertEquals(11111L, q(parsed, 2).infantry);
        assertTrue(parsed.allianceMineEnabled);
        assertTrue(parsed.fullResourceOnly);
        assertEquals(4, parsed.maxQueues);
    }

    @Test
    public void applyToPreservesUnrelatedKeys() throws Exception {
        JSONObject params = new JSONObject();
        params.put("squad_index", 4);
        params.put("hero_ids", new JSONArray().put(10001));
        params.put("custom_flag", "keep-me");
        JSONObject next = GatherConfigV2.legacyDefaults().applyTo(params);
        assertEquals(4, next.optInt("squad_index"));
        assertEquals("keep-me", next.optString("custom_flag"));
        assertEquals(10001L, next.optJSONArray("hero_ids").optLong(0));
        // 入参不被修改
        assertFalse(params.has(GatherConfigV2.KEY));
    }

    @Test
    public void projectionWritesLegacyKeys() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        c.queues.get(0).enabled = true;
        c.queues.get(0).resourceType = GatherConfigV2.RES_MEAT;
        c.queues.get(0).level = 8;
        c.queues.get(1).enabled = true;
        c.queues.get(1).resourceType = GatherConfigV2.RES_WOOD;
        c.queues.get(1).level = 7;
        c.queues.get(2).enabled = false;
        c.fullResourceOnly = true;
        JSONObject next = c.applyTo(new JSONObject());
        assertEquals(8, next.optInt("min_level"));
        JSONArray names = next.optJSONArray("resources_named");
        assertNotNull(names);
        assertEquals(2, names.length());
        assertEquals("MEAT", names.optString(0));
        assertEquals("WOOD", names.optString(1));
        assertEquals(2, next.optInt("max_queues"));
        assertEquals("full", next.optJSONObject("strategy").optString("send"));
        assertFalse(next.optBoolean("alliance_mine", true));
        assertEquals(GatherConfigV2.MODE_AUTOMATIC, next.optString("formation_mode"));
        assertFalse(next.has("formation_id"));
        assertFalse(next.has("soldiers"));
    }

    @Test
    public void projectionUniformRoleWritesFunctionalFormation() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        for (int id = 1; id <= 3; id++) {
            GatherConfigV2.QueueCfg q = q(c, id);
            q.enabled = true;
            q.formationMode = GatherConfigV2.MODE_ROLE;
            q.formationId = 2L;
            q.infantry = 40000L;
            q.lancer = 30000L;
            q.marksman = 30000L;
        }
        JSONObject next = c.applyTo(new JSONObject());
        assertEquals(GatherConfigV2.MODE_ROLE, next.optString("formation_mode"));
        assertEquals(2L, next.optLong("formation_id"));
        JSONObject soldiers = next.optJSONObject("soldiers");
        assertNotNull(soldiers);
        assertEquals(40000L, soldiers.optLong("infantry"));
    }

    @Test
    public void projectionIdempotent() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        q(c, 1).resourceType = GatherConfigV2.RES_IRON;
        q(c, 1).level = 9;
        c.maxQueues = 2;
        JSONObject first = c.applyTo(new JSONObject());
        JSONObject second = c.applyTo(first);
        assertJsonEqual(first, second);
        // 二次写回后 parse 结果一致
        assertJsonEqual(v2Of(c), v2Of(GatherConfigV2.parse(second)));
    }

    // ==================== validate ====================

    @Test
    public void validateRejectsDuplicateRoleFormation() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        q(c, 1).formationMode = GatherConfigV2.MODE_ROLE;
        q(c, 1).formationId = 7L;
        q(c, 2).formationMode = GatherConfigV2.MODE_ROLE;
        q(c, 2).formationId = 7L;
        try {
            c.validate();
            fail("应拦截重复 ROLE 编队");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("已被其他采集队列使用"));
        }
    }

    @Test
    public void validateRejectsLevelOutOfRange() throws Exception {
        GatherConfigV2 low = GatherConfigV2.legacyDefaults();
        q(low, 1).level = 0;
        try {
            low.validate();
            fail("level=0 应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("1-9"));
        }
        GatherConfigV2 high = GatherConfigV2.legacyDefaults();
        q(high, 1).level = 10;
        try {
            high.validate();
            fail("level=10 应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("1-9"));
        }
    }

    @Test
    public void validateRejectsRoleWithoutFormationId() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        q(c, 1).formationMode = GatherConfigV2.MODE_ROLE;
        q(c, 1).formationId = 0L;
        try {
            c.validate();
            fail("ROLE 缺编队 id 应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("请选择指定编队"));
        }
    }

    @Test
    public void validateRejectsMaxQueuesOutOfRange() throws Exception {
        GatherConfigV2 low = GatherConfigV2.legacyDefaults();
        low.maxQueues = -1;
        try {
            low.validate();
            fail("maxQueues=-1 应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("0-6"));
        }
        GatherConfigV2 high = GatherConfigV2.legacyDefaults();
        high.maxQueues = 7;
        try {
            high.validate();
            fail("maxQueues=7 应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("0-6"));
        }
    }

    @Test
    public void validateRejectsDuplicateQueueId() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        c.queues.get(1).id = 1;
        try {
            c.validate();
            fail("队列 id 重复应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("重复"));
        }
    }

    @Test
    public void validateRejectsInvalidResource() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        q(c, 1).resourceType = "GOLD";
        try {
            c.validate();
            fail("非法资源应被拦截");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("未选择采集资源"));
        }
    }

    @Test
    public void validateAcceptsAllDisabledQueues() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        for (GatherConfigV2.QueueCfg queue : c.queues) queue.enabled = false;
        c.maxQueues = 0;
        c.validate(); // 不抛异常即通过
    }

    // ==================== 纯函数 / 文案 ====================

    @Test
    public void searchLevelsClampsToMaxLevel() throws Exception {
        assertEquals(Integer.valueOf(9), GatherConfigV2.searchLevels(9, 9).get(0));
        assertEquals(9, GatherConfigV2.searchLevels(9, 9).size());
        List<Integer> capped = GatherConfigV2.searchLevels(9, 8);
        assertEquals(Integer.valueOf(8), capped.get(0));
        assertEquals(8, capped.size());
        assertEquals(Integer.valueOf(1), capped.get(capped.size() - 1));
        // 配置 0 → 夹到 1；maxLevel 未知(0) → 按 9
        List<Integer> low = GatherConfigV2.searchLevels(0, 8);
        assertEquals(1, low.size());
        assertEquals(Integer.valueOf(1), low.get(0));
        assertEquals(9, GatherConfigV2.searchLevels(9, 0).size());
    }

    @Test
    public void maxLevelForKingdomDayThreshold() throws Exception {
        assertEquals(8, GatherConfigV2.maxLevelForKingdomDay(0));
        assertEquals(8, GatherConfigV2.maxLevelForKingdomDay(700));
        assertEquals(9, GatherConfigV2.maxLevelForKingdomDay(701));
        assertEquals(9, GatherConfigV2.maxLevelForKingdomDay(999));
    }

    @Test
    public void resIdMapping() throws Exception {
        assertEquals(102, GatherConfigV2.resId(GatherConfigV2.RES_MEAT));
        assertEquals(103, GatherConfigV2.resId(GatherConfigV2.RES_WOOD));
        assertEquals(104, GatherConfigV2.resId(GatherConfigV2.RES_COAL));
        assertEquals(105, GatherConfigV2.resId(GatherConfigV2.RES_IRON));
        assertEquals(105, GatherConfigV2.resId("iron"));
        // normalizeResource 对未知值兜底为肉，故 resId 未知值实际返回 102（按现实现锁定）
        assertEquals(102, GatherConfigV2.resId("GOLD"));
        assertEquals(102, GatherConfigV2.resId(null));
        // 旧数值资源归一化
        assertEquals(GatherConfigV2.RES_WOOD, GatherConfigV2.normalizeResource("2"));
        assertEquals(GatherConfigV2.RES_IRON, GatherConfigV2.normalizeResource("105"));
    }

    @Test
    public void modeAndResourceNormalization() throws Exception {
        assertEquals(GatherConfigV2.MODE_ROLE, GatherConfigV2.normalizeMode("role"));
        assertEquals(GatherConfigV2.MODE_ROLE, GatherConfigV2.normalizeMode("NORMAL"));
        assertEquals(GatherConfigV2.MODE_ROLE, GatherConfigV2.normalizeMode("CUSTOM"));
        assertEquals(GatherConfigV2.MODE_AUTOMATIC, GatherConfigV2.normalizeMode("auto"));
        assertEquals(GatherConfigV2.MODE_AUTOMATIC, GatherConfigV2.normalizeMode(null));
        assertEquals("肉", GatherConfigV2.resourceLabel("MEAT"));
        assertEquals("木", GatherConfigV2.resourceLabel(GatherConfigV2.RES_WOOD));
        assertEquals("煤", GatherConfigV2.resourceLabel(GatherConfigV2.RES_COAL));
        assertEquals("铁", GatherConfigV2.resourceLabel(GatherConfigV2.RES_IRON));
    }

    @Test
    public void summaryText() throws Exception {
        GatherConfigV2 c = GatherConfigV2.legacyDefaults();
        q(c, 1).resourceType = GatherConfigV2.RES_MEAT;
        q(c, 1).level = 8;
        q(c, 2).resourceType = GatherConfigV2.RES_WOOD;
        q(c, 2).level = 7;
        q(c, 3).resourceType = GatherConfigV2.RES_COAL;
        q(c, 3).level = 9;
        c.fullResourceOnly = true;
        c.maxQueues = 2;
        assertEquals("3队 · 肉8/木7/煤9 · 只采满矿 · 上限2队", c.summary());
    }

    @Test
    public void newConfigDefaults() throws Exception {
        GatherConfigV2 c = new GatherConfigV2();
        assertEquals(6, c.queues.size());
        for (GatherConfigV2.QueueCfg q : c.queues) {
            assertFalse(q.enabled);
            assertEquals(8, q.level);
            assertFalse(q.isRole());
        }
        assertTrue(c.fullResourceOnly);
        assertEquals(0, c.maxQueues);
        assertEquals(0, c.enabledQueues().size());
        assertEquals(0, c.effectiveQueueLimit(0, 0));
    }

    // ==================== 工具 ====================

    private static GatherConfigV2.QueueCfg q(GatherConfigV2 c, int id) {
        GatherConfigV2.QueueCfg q = c.queue(id);
        assertNotNull("queue " + id + " 缺失", q);
        return q;
    }

    private static JSONObject v2Of(GatherConfigV2 c) {
        return c.toV2Json();
    }

    /** org.json 数字类型在文本 round-trip 后会变（Integer/Long），统一按文本比较。 */
    private static void assertJsonEqual(Object expected, Object actual) {
        assertJsonEqual("$", expected, actual);
    }

    private static void assertJsonEqual(String path, Object expected, Object actual) {
        if (expected == null || JSONObject.NULL.equals(expected)) {
            assertTrue(path + " 期望 null，实际 " + actual,
                    actual == null || JSONObject.NULL.equals(actual));
            return;
        }
        if (expected instanceof JSONObject) {
            assertTrue(path + " 期望对象，实际 " + actual, actual instanceof JSONObject);
            JSONObject e = (JSONObject) expected;
            JSONObject a = (JSONObject) actual;
            List<String> eKeys = sortedKeys(e);
            assertEquals(path + " 键集合", eKeys, sortedKeys(a));
            for (String key : eKeys) {
                assertJsonEqual(path + "." + key, e.opt(key), a.opt(key));
            }
            return;
        }
        if (expected instanceof JSONArray) {
            assertTrue(path + " 期望数组，实际 " + actual, actual instanceof JSONArray);
            JSONArray e = (JSONArray) expected;
            JSONArray a = (JSONArray) actual;
            assertEquals(path + " 数组长度", e.length(), a.length());
            for (int i = 0; i < e.length(); i++) {
                assertJsonEqual(path + "[" + i + "]", e.opt(i), a.opt(i));
            }
            return;
        }
        if (expected instanceof Number) {
            assertTrue(path + " 期望数字，实际 " + actual, actual instanceof Number);
            assertEquals(path, String.valueOf(expected), String.valueOf(actual));
            return;
        }
        assertEquals(path, expected, actual);
    }

    private static List<String> sortedKeys(JSONObject o) {
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }
}
