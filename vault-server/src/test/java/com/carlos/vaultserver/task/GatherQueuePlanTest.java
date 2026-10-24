package com.carlos.vaultserver.task;

import com.carlos.vault.daily.gather.GatherConfigV2;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.QueueCapacity;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * I2 服务端采集 v2 队列化执行：纯函数/可注入单测（不连真协议、不依赖网络）。
 *
 * <p>覆盖矩阵（对齐 design/research-20261024/采集配置落点分析.md §7-A4）：
 * <ul>
 *   <li>{@link ServerFieldTasks#planQueues}：每队列资源 res_id（102-105）、等级从高到低
 *       （9→1，maxLevel=8 时夹取）、每队列编队参数（ROLE/AUTOMATIC）；</li>
 *   <li>{@link ServerFieldTasks#reconcileMapping}：stale marchId 移除、特殊队列 type=1201 不算采集、
 *       type=4/110 保留；</li>
 *   <li>{@link ServerFieldTasks#slotCount}：capacity/occupied/idle/limitReached/启用数/maxQueues 夹取；</li>
 *   <li>旧配置（无 v2）迁移后行为等价（min_level/max_queues/resources_named/strategy.send）；</li>
 *   <li>{@link ServerFieldTasks#searchQueueResource}：2872 逐级降级、其它 code 记录并跳过该队列；</li>
 *   <li>v2 但资产库未接线 → SKIP 且不抛异常（含 missing 原因）。</li>
 * </ul>
 */
public class GatherQueuePlanTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ==================== 测试替身/工具 ====================

    /** 只读协议替身（同 FieldP1Test/CaptureTasksTest FakeEngine 模式）。 */
    static final class FakeEngine extends WjdrEngine {
        final Map<String, List<GameMessage>> responses = new HashMap<String, List<GameMessage>>();
        final List<String> requested = new ArrayList<String>();

        FakeEngine() {
            super(null);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            List<GameMessage> queue = responses.get(proto);
            if (queue == null || queue.isEmpty()) return null;
            return queue.size() == 1 ? queue.get(0) : queue.remove(0);
        }
    }

    private TaskContext context(WjdrEngine engine, JSONObject gatherParams) {
        Map<String, JSONObject> params = new HashMap<String, JSONObject>();
        params.put("gather", gatherParams);
        return new TaskContext(engine, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), params, "服务器");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static GameMessage msg(Map<String, Object> content) {
        GameMessage m = new GameMessage();
        m.setContent(content);
        return m;
    }

    /** 单条 v2 队列配置。 */
    private static JSONObject queue(int id, boolean enabled, String resource, int level,
                                    String formationMode, long formationId,
                                    long infantry, long lancer, long marksman) {
        JSONObject q = new JSONObject();
        q.put("id", id);
        q.put("enabled", enabled);
        q.put("resourceType", resource);
        q.put("resourceLevel", level);
        q.put("formationMode", formationMode);
        if (formationId > 0L) q.put("formationId", formationId);
        q.put("infantry", infantry);
        q.put("lancer", lancer);
        q.put("marksman", marksman);
        return q;
    }

    /** gather 参数对象（内嵌 v2）。 */
    private static JSONObject v2Params(JSONArray queues, boolean fullResourceOnly, int maxQueues) {
        JSONObject v2 = new JSONObject();
        v2.put("version", 2);
        v2.put("queues", queues);
        v2.put("fullResourceOnly", fullResourceOnly);
        v2.put("maxQueues", maxQueues);
        JSONObject p = new JSONObject();
        p.put("v2", v2);
        return p;
    }

    /** 前 N 条启用、等级 8、AUTO 的 v2 配置。 */
    private static GatherConfigV2 enabledCfg(int enabledCount, int maxQueues) {
        JSONArray arr = new JSONArray();
        for (int i = 1; i <= GatherConfigV2.QUEUE_COUNT; i++) {
            arr.put(queue(i, i <= enabledCount, "MEAT", 8, "AUTOMATIC", 0L,
                    40000L, 30000L, 30000L));
        }
        return GatherConfigV2.parse(v2Params(arr, false, maxQueues));
    }

    /** 最小 QueueMarch（资产库在途行军结构）。 */
    private static AssetSnapshot.QueueMarch march(long id, long type, long endTime) {
        return new AssetSnapshot.QueueMarch(id, 28181058L, type, 3L, 0L, endTime,
                2L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    // ==================== 1. 每队列计划（资源 res_id / 等级递减 / 编队参数） ====================

    @Test
    public void planQueues_v2_perQueueResourceAndDescendingLevels() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "MEAT", 9, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        arr.put(queue(2, true, "WOOD", 7, "ROLE", 3L, 10000L, 20000L, 30000L));
        arr.put(queue(3, false, "COAL", 9, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        JSONObject p = v2Params(arr, true, 0);

        List<ServerFieldTasks.QueuePlan> plans = ServerFieldTasks.planQueues(p, 8);

        assertEquals("只计划启用队列", 2, plans.size());
        // 队列1：肉 → res_id 102；配置 9 级被 maxLevel=8 夹到 8 → 8..1
        assertEquals(1, plans.get(0).queueId);
        assertEquals(102, plans.get(0).resId);
        assertEquals(Arrays.asList(8, 7, 6, 5, 4, 3, 2, 1), plans.get(0).levels);
        assertTrue("v2.fullResourceOnly → is_full", plans.get(0).fullResourceOnly);
        // 队列2：木 → res_id 103；等级 7 → 7..1
        assertEquals(2, plans.get(1).queueId);
        assertEquals(103, plans.get(1).resId);
        assertEquals(Arrays.asList(7, 6, 5, 4, 3, 2, 1), plans.get(1).levels);
    }

    @Test
    public void planQueues_level9KeptWhenMaxLevel9() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "IRON", 9, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        List<ServerFieldTasks.QueuePlan> plans = ServerFieldTasks.planQueues(v2Params(arr, false, 0), 9);

        assertEquals(1, plans.size());
        assertEquals(105, plans.get(0).resId);
        assertEquals("9 级在 maxLevel=9 时保留（开服 701 天）",
                Arrays.asList(9, 8, 7, 6, 5, 4, 3, 2, 1), plans.get(0).levels);
    }

    @Test
    public void planQueues_level1SingleSearch() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "COAL", 1, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        List<ServerFieldTasks.QueuePlan> plans = ServerFieldTasks.planQueues(v2Params(arr, false, 0), 8);

        assertEquals(java.util.Collections.singletonList(1), plans.get(0).levels);
        assertEquals(104, plans.get(0).resId);
    }

    @Test
    public void queueFormationParams_roleUsesFormationIdAndSoldiers() {
        GatherConfigV2.QueueCfg q = new GatherConfigV2.QueueCfg(
                1, true, "MEAT", 8, "ROLE", 5L, 10000L, 20000L, 30000L);
        JSONObject f = ServerFieldTasks.queueFormationParams(q);

        assertEquals(GatherConfigV2.MODE_ROLE, f.getString("formation_mode"));
        assertEquals(5L, f.getLong("formation_id"));
        JSONObject soldiers = f.getJSONObject("soldiers");
        assertEquals(10000L, soldiers.getLong("infantry"));
        assertEquals(20000L, soldiers.getLong("lancer"));
        assertEquals(30000L, soldiers.getLong("marksman"));
        assertEquals("ROLE#" + 5L, ServerFieldTasks.formationDesc(
                new ServerFieldTasks.QueuePlan(1, "MEAT", 102,
                        Arrays.asList(8), true, f)));
    }

    @Test
    public void queueFormationParams_automaticUsesQueueSoldiersAsWeights() {
        GatherConfigV2.QueueCfg q = new GatherConfigV2.QueueCfg(
                2, true, "WOOD", 7, "AUTOMATIC", 0L, 40000L, 30000L, 30000L);
        JSONObject f = ServerFieldTasks.queueFormationParams(q);

        assertEquals(GatherConfigV2.MODE_AUTOMATIC, f.getString("formation_mode"));
        JSONObject ratio = f.getJSONObject("ratio");
        assertEquals(40000L, ratio.getLong("infantry"));
        assertEquals(30000L, ratio.getLong("lancer"));
        assertEquals(30000L, ratio.getLong("marksman"));
        assertEquals("总量=每队列三兵种之和（0=沿用库存上限）", 100000L, f.getLong("total"));
        assertEquals("AUTOMATIC(40000/30000/30000)", ServerFieldTasks.formationDesc(
                new ServerFieldTasks.QueuePlan(2, "WOOD", 103, Arrays.asList(7), true, f)));
    }

    @Test
    public void queueFormationParams_automaticFallsBackToDefaultRatioWhenZero() {
        GatherConfigV2.QueueCfg q = new GatherConfigV2.QueueCfg(
                3, true, "IRON", 8, "AUTOMATIC", 0L, 0L, 0L, 0L);
        JSONObject f = ServerFieldTasks.queueFormationParams(q);

        JSONObject ratio = f.getJSONObject("ratio");
        assertEquals(40L, ratio.getLong("infantry"));
        assertEquals(30L, ratio.getLong("lancer"));
        assertEquals(30L, ratio.getLong("marksman"));
        assertEquals(0L, f.getLong("total"));
    }

    // ==================== 2. 旧配置（无 v2）迁移后行为等价 ====================

    @Test
    public void legacyConfig_migratesToEquivalentPlans() {
        JSONObject p = new JSONObject();
        p.put("min_level", 5);          // 旧等级上限 ≤6
        p.put("max_queues", 4);         // 旧队列数 1-5
        JSONArray named = new JSONArray();
        named.put("MEAT");
        named.put("WOOD");
        p.put("resources_named", named);
        JSONObject strategy = new JSONObject();
        strategy.put("send", "full");
        p.put("strategy", strategy);

        assertFalse("无 v2 → 旧分支", GatherConfigV2.hasV2(p));
        GatherConfigV2 cfg = GatherConfigV2.parse(p);
        assertEquals("max_queues → 前 4 条启用", 4, cfg.enabledQueues().size());
        assertTrue("strategy.send=full → fullResourceOnly", cfg.fullResourceOnly);

        List<ServerFieldTasks.QueuePlan> plans = ServerFieldTasks.planQueues(p, 8);
        assertEquals(4, plans.size());
        assertEquals("min_level → 队列等级", Arrays.asList(5, 4, 3, 2, 1), plans.get(0).levels);
        assertEquals("resources_named 轮转 → 队列1 肉", 102, plans.get(0).resId);
        assertEquals("resources_named 轮转 → 队列2 木", 103, plans.get(1).resId);
        assertEquals("resources_named 轮转 → 队列3 肉", 102, plans.get(2).resId);
        assertEquals("resources_named 轮转 → 队列4 木", 103, plans.get(3).resId);
    }

    @Test
    public void legacyEmptyConfig_migratesToOldBehaviorDefaults() {
        GatherConfigV2 cfg = GatherConfigV2.parse(new JSONObject());
        assertEquals("空配置 = 旧行为等价默认：3 队", 3, cfg.enabledQueues().size());
        assertFalse("旧默认 any（任意存量）", cfg.fullResourceOnly);
        List<ServerFieldTasks.QueuePlan> plans = ServerFieldTasks.planQueues(new JSONObject(), 8);
        assertEquals(3, plans.size());
        assertEquals("旧默认等级 5", Arrays.asList(5, 4, 3, 2, 1), plans.get(0).levels);
    }

    // ==================== 3. 队列↔行军映射对账 ====================

    @Test
    public void reconcileMapping_keepsOnlyLiveGatherMarches() {
        ServerFieldTasks.QueueMapping mapping = ServerFieldTasks.parseQueueMapping(
                "{\"nextQueueId\":3,\"map\":{\"1\":111,\"2\":333,\"3\":444,\"4\":555}}");
        List<AssetSnapshot.QueueMarch> marches = Arrays.asList(
                march(111L, 4L, 1_790_000_100L),      // 普通采集，在途 → 保留
                march(333L, 1201L, 1_790_000_200L),   // 雪原商路护送 = 特殊队列，不算采集 → 移除
                march(555L, 110L, 1_790_000_300L));   // 联盟矿采集，在途 → 保留

        ServerFieldTasks.QueueMapping out = ServerFieldTasks.reconcileMapping(mapping, marches);

        assertTrue("type=4 在途保留", out.bound(1));
        assertEquals(111L, out.marchIdOf(1));
        assertFalse("type=1201 不占采集映射", out.bound(2));
        assertFalse("已结束（不在 marches）移除", out.bound(3));
        assertTrue("type=110 在途保留", out.bound(4));
        assertEquals("nextQueueId 保留", 3, out.nextQueueId);
    }

    @Test
    public void reconcileMapping_emptyMarchesClearsAll() {
        ServerFieldTasks.QueueMapping mapping = ServerFieldTasks.parseQueueMapping(
                "{\"nextQueueId\":1,\"map\":{\"1\":111,\"2\":222}}");
        ServerFieldTasks.QueueMapping out =
                ServerFieldTasks.reconcileMapping(mapping, java.util.Collections.emptyList());
        assertTrue("全部已结束", out.map.isEmpty());
    }

    @Test
    public void queueMapping_roundTripAndBadJson() {
        ServerFieldTasks.QueueMapping m = new ServerFieldTasks.QueueMapping();
        m.nextQueueId = 5;
        m.map.put("2", 999L);
        m.map.put("1", 123L);
        String raw = ServerFieldTasks.encodeQueueMapping(m);
        assertTrue("JSON 形状含 nextQueueId/map 键", raw.contains("nextQueueId"));
        assertTrue(raw.contains("map"));

        ServerFieldTasks.QueueMapping back = ServerFieldTasks.parseQueueMapping(raw);
        assertEquals(5, back.nextQueueId);
        assertEquals(123L, back.marchIdOf(1));
        assertEquals(999L, back.marchIdOf(2));
        assertEquals("nextQueueId 夹 1..6", 6, ServerFieldTasks.parseQueueMapping(
                "{\"nextQueueId\":99,\"map\":{}}").nextQueueId);
        assertEquals("坏 JSON 回默认", 1,
                ServerFieldTasks.parseQueueMapping("not-json").nextQueueId);
        assertEquals("null 回默认", 1, ServerFieldTasks.parseQueueMapping(null).nextQueueId);
    }

    // ==================== 4. 槽位计算 ====================

    @Test
    public void slotCount_clampsByCapacityIdleAndMaxQueues() {
        GatherConfigV2 cfg3 = enabledCfg(3, 0);   // 3 启用，无 maxQueues 额外限制

        assertEquals("idle=5/未绑定3 → 3", 3, ServerFieldTasks.slotCount(cfg3, 5, 3, 5, false));
        assertEquals("idle 夹取", 2, ServerFieldTasks.slotCount(cfg3, 2, 3, 5, false));
        assertEquals("未绑定队列数夹取", 1, ServerFieldTasks.slotCount(cfg3, 5, 1, 5, false));
        assertEquals("limitReached → 0", 0, ServerFieldTasks.slotCount(cfg3, 5, 3, 5, true));
        assertEquals("idle=0 → 0", 0, ServerFieldTasks.slotCount(cfg3, 0, 3, 5, false));
        assertEquals("世界容量夹取 capacity=2", 2,
                ServerFieldTasks.slotCount(cfg3, 5, 3, 2, false));

        GatherConfigV2 cfgCapped = enabledCfg(3, 2);   // maxQueues=2
        assertEquals("maxQueues 夹取", 2,
                ServerFieldTasks.slotCount(cfgCapped, 5, 3, 6, false));

        // occupied 影响 idle：capacity=3/occupied=2 → idle=1
        int idle = QueueCapacity.idle(3, 2, 0, false);
        assertEquals(1, idle);
        assertEquals("占用后空闲夹取", 1, ServerFieldTasks.slotCount(cfg3, idle, 3, 3, false));
    }

    @Test
    public void nextUnboundQueue_cyclicAndQueueAfterWraps() {
        GatherConfigV2 cfg = enabledCfg(3, 0);
        ServerFieldTasks.QueueMapping mapping = new ServerFieldTasks.QueueMapping();

        assertEquals("从游标 2 起找", 2, ServerFieldTasks.nextUnboundQueue(cfg, mapping, 2).id);
        mapping.map.put("1", 11L);
        assertEquals("跳过已绑定队列1", 2, ServerFieldTasks.nextUnboundQueue(cfg, mapping, 1).id);
        mapping.map.put("2", 22L);
        mapping.map.put("3", 33L);
        assertNull("全部启用队列已绑定", ServerFieldTasks.nextUnboundQueue(cfg, mapping, 1));

        assertEquals("6→1 环状", 1, ServerFieldTasks.nextQueueIdAfter(6));
        assertEquals(2, ServerFieldTasks.nextQueueIdAfter(1));
        assertEquals("越界夹取", 1, ServerFieldTasks.nextQueueIdAfter(0));
    }

    // ==================== 5. 搜索逐级降级 / 错误码 ====================

    @Test
    public void searchQueueResource_2872DegradesToNextLevel() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "WOOD", 3, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        ServerFieldTasks.QueuePlan plan = ServerFieldTasks.planQueues(v2Params(arr, true, 0), 8).get(0);
        assertEquals(Arrays.asList(3, 2, 1), plan.levels);

        FakeEngine engine = new FakeEngine();
        engine.responses.put("req_world_map_search", new ArrayList<GameMessage>(Arrays.asList(
                msg(map("code", 2872)),                                  // 3 级无矿
                msg(map("code", 0, "point", map("x", 12, "y", 34))))));   // 2 级命中

        ServerFieldTasks.SearchOutcome out = ServerFieldTasks.searchQueueResource(engine, plan);

        assertTrue("命中 2 级", out.found());
        assertEquals(2, out.level);
        assertEquals(12L, Exec.longOf(out.point.get("x")));
        assertEquals("2872 后再搜一次，命中即停", 2, engine.requested.size());
    }

    @Test
    public void searchQueueResource_otherCodeStopsQueue() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "WOOD", 3, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        ServerFieldTasks.QueuePlan plan = ServerFieldTasks.planQueues(v2Params(arr, true, 0), 8).get(0);

        FakeEngine engine = new FakeEngine();
        engine.responses.put("req_world_map_search", new ArrayList<GameMessage>(Arrays.asList(
                msg(map("code", 2872)),
                msg(map("code", 500)))));

        ServerFieldTasks.SearchOutcome out = ServerFieldTasks.searchQueueResource(engine, plan);

        assertFalse(out.found());
        assertEquals("非 2872 的错误码记录并跳过该队列", 500, out.code);
        assertEquals("不再继续降级", 2, engine.requested.size());
    }

    @Test
    public void searchQueueResource_allLevelsEmpty() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "WOOD", 2, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        ServerFieldTasks.QueuePlan plan = ServerFieldTasks.planQueues(v2Params(arr, true, 0), 8).get(0);

        FakeEngine engine = new FakeEngine();
        engine.responses.put("req_world_map_search", new ArrayList<GameMessage>(Arrays.asList(
                msg(map("code", 2872)), msg(map("code", 2872)))));

        ServerFieldTasks.SearchOutcome out = ServerFieldTasks.searchQueueResource(engine, plan);
        assertFalse(out.found());
        assertTrue("全等级无矿 = 2872（正常业务状态）", out.empty());
        assertEquals(2, engine.requested.size());
    }

    // ==================== 6. 王国等级上限 / 续派唤醒 ====================

    @Test
    public void gatherMaxResourceLevel_clockFallbacks() {
        assertEquals("未知 → 8", 8, ServerFieldTasks.gatherMaxResourceLevel(null));
        assertEquals(8, ServerFieldTasks.gatherMaxResourceLevel(
                new AssetSnapshot.Clock(0, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals("clock.maxResourceLevel 优先", 9, ServerFieldTasks.gatherMaxResourceLevel(
                new AssetSnapshot.Clock(1_790_000_000L, 0, 0, 0, 0, 0, 0, 9)));
        assertEquals("开服第 700 天 → 8", 8, ServerFieldTasks.gatherMaxResourceLevel(
                new AssetSnapshot.Clock(1_790_000_000L, 0, 0, 0, 0, 0, 700, 0)));
        assertEquals("开服第 701 天 → 9", 9, ServerFieldTasks.gatherMaxResourceLevel(
                new AssetSnapshot.Clock(1_790_000_000L, 0, 0, 0, 0, 0, 701, 0)));
    }

    @Test
    public void gatherV2WakeMillis_afterEarliestMarchEnd() {
        long now = 1_790_000_000_000L;
        assertEquals("无在途 → now+3s", now + 3_000L,
                ServerFieldTasks.gatherV2WakeMillis(now, 0L));
        assertEquals("最早结束 +250ms", now + 60_000L + 250L,
                ServerFieldTasks.gatherV2WakeMillis(now, now + 60_000L));
        assertEquals("封顶 now+180s", now + 180_000L,
                ServerFieldTasks.gatherV2WakeMillis(now, now + 600_000L));
        assertEquals("已过期 → now+250ms", now + 250L,
                ServerFieldTasks.gatherV2WakeMillis(now, now - 5_000L));
    }

    @Test
    public void gatherV2EarliestMarchEnd_onlyType4And110() {
        List<AssetSnapshot.QueueMarch> marches = Arrays.asList(
                march(1L, 4L, 1_790_000_100L),
                march(2L, 110L, 1_790_000_200L),
                march(3L, 1201L, 1_790_000_001L),   // 特殊队列不参与续派
                march(4L, 4L, 1_790_000_300L));
        assertEquals(1_790_000_100_000L, ServerFieldTasks.gatherV2EarliestMarchEndMillis(marches));
        assertEquals("无 type4/110 → 0",
                0L, ServerFieldTasks.gatherV2EarliestMarchEndMillis(
                        java.util.Collections.singletonList(march(9L, 1201L, 1L))));
        assertEquals(0L, ServerFieldTasks.gatherV2EarliestMarchEndMillis(null));
    }

    // ==================== 7. 资产库未接线：SKIP 不抛异常（含 missing） ====================

    @Test
    public void gatherV2_assetRegistryMissingSkipsWithoutThrowing() {
        JSONArray arr = new JSONArray();
        arr.put(queue(1, true, "MEAT", 8, "AUTOMATIC", 0L, 40000L, 30000L, 30000L));
        JSONObject p = v2Params(arr, true, 0);
        assertTrue(GatherConfigV2.hasV2(p));
        FakeEngine engine = new FakeEngine();
        TaskContext tc = context(engine, p);

        TaskOutcome out = ServerFieldTasks.gather(tc);

        assertEquals("未接线不算异常", TaskOutcome.Category.SKIPPED, out.category);
        assertTrue("原因含未就绪", out.message.contains("资产库未就绪"));
        assertTrue("原因含 missing", out.message.contains("missing"));
        assertTrue("不发出任何协议请求", engine.requested.isEmpty());
        long wake = ServerFieldTasks.stateLongOf(tc, ServerFieldTasks.gatherWakeKey(tc));
        assertTrue("写续派唤醒（now+3s）", wake >= System.currentTimeMillis());
        String mod = tc.store.getString("mod:48460403:888:gather");
        assertTrue("写 mod:gather:send 状态", mod != null && mod.contains("资产库未就绪"));
    }

    // ==================== 8. B2-T4 采集暂停协调（情报优先/巨熊窗口） ====================

    @Test
    public void gatherPause_bearReserveWindow() {
        long now = 10_000L;
        assertNotNull(ServerFieldTasks.gatherPauseReason(now + 100L, 0L, 0L, 0, now));
        // 窗口 [reserve-600, reserve+1800)：now=10000，reserve=7000 → 窗口 [6400, 8800)，已结束
        assertNull(ServerFieldTasks.gatherPauseReason(now - 3_000L, 0L, 0L, 0, now));
        assertNull(ServerFieldTasks.gatherPauseReason(now + 2_500L, 0L, 0L, 0, now));
    }

    @Test
    public void gatherPause_bearMassWindow() {
        long now = 10_000L;
        assertNotNull(ServerFieldTasks.gatherPauseReason(0L, now + 100L, now + 2000L, 0, now));
        assertNull(ServerFieldTasks.gatherPauseReason(0L, now + 700L, now + 2000L, 0, now));
    }

    @Test
    public void gatherPause_intelPriority() {
        assertNotNull(ServerFieldTasks.gatherPauseReason(0L, 0L, 0L, 2, 10_000L));
        assertNull(ServerFieldTasks.gatherPauseReason(0L, 0L, 0L, 0, 10_000L));
    }

    @Test
    public void intelGatherPriorityCount_rules() {
        java.util.Map<Long, com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent> events =
                new java.util.LinkedHashMap<>();
        // PVE（type 3，event_id 3001）不占普通队列 → 不计
        events.put(1L, new com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent(
                1L, 3001L, 1L, 10L, 20L, 9_999_999_999L, false));
        com.carlos.vault.wjdr.asset.AssetSnapshot.Intel pveOnly =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Intel(0, 0, 0, 0, events, 0L);
        com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina st100 =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina(100, 200, 0, 0, 0);
        assertEquals(0, ServerFieldTasks.intelGatherPriorityCount(pveOnly, st100, 1000L));
        // 普通队列事件（event_id 1001 → type 1）体力足够 → 计 1
        events.put(2L, new com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent(
                2L, 1001L, 1L, 1L, 2L, 9_999_999_999L, false));
        com.carlos.vault.wjdr.asset.AssetSnapshot.Intel mixed =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Intel(0, 0, 0, 0, events, 0L);
        assertEquals(1, ServerFieldTasks.intelGatherPriorityCount(mixed, st100, 1000L));
        // 体力不足（<10）→ 情报发不出去，返回 0（采集不让位）
        com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina st5 =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina(5, 200, 0, 0, 0);
        assertEquals(0, ServerFieldTasks.intelGatherPriorityCount(mixed, st5, 1000L));
        // 已可领（status=2）不计
        java.util.Map<Long, com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent> claimed =
                new java.util.LinkedHashMap<>();
        claimed.put(3L, new com.carlos.vault.wjdr.asset.AssetSnapshot.IntelEvent(
                3L, 1001L, 2L, 1L, 2L, 9_999_999_999L, false));
        com.carlos.vault.wjdr.asset.AssetSnapshot.Intel claimedOnly =
                new com.carlos.vault.wjdr.asset.AssetSnapshot.Intel(0, 0, 0, 0, claimed, 0L);
        assertEquals(0, ServerFieldTasks.intelGatherPriorityCount(claimedOnly, st100, 1000L));
    }

    @Test
    public void gatherPause_notReadyIsNull() {
        assertNull(ServerFieldTasks.gatherPauseReason(
                (com.carlos.vault.wjdr.asset.CoreAssetLibrary) null, 10_000L));
    }

    @Test
    public void allianceMineFormationParams_roleAndAuto() {
        GatherConfigV2 cfg = GatherConfigV2.legacyDefaults();
        cfg.allianceMineEnabled = true;
        cfg.allianceMineFormationMode = GatherConfigV2.MODE_ROLE;
        cfg.allianceMineFormationId = 7L;
        org.json.JSONObject role = ServerFieldTasks.allianceMineFormationParams(cfg);
        assertEquals(GatherConfigV2.MODE_ROLE, role.optString("formation_mode"));
        assertEquals(7L, role.optLong("formation_id"));
        cfg.allianceMineFormationMode = GatherConfigV2.MODE_AUTOMATIC;
        cfg.allianceMineInfantry = 40000L;
        cfg.allianceMineLancer = 30000L;
        cfg.allianceMineMarksman = 30000L;
        org.json.JSONObject auto = ServerFieldTasks.allianceMineFormationParams(cfg);
        assertEquals(GatherConfigV2.MODE_AUTOMATIC, auto.optString("formation_mode"));
        assertEquals(100000L, auto.optLong("total"));
        assertEquals(40000L, auto.optJSONObject("ratio").optLong("infantry"));
    }

    @Test
    public void marchSnapshotFromAsset_countsAndSpecial() {
        com.carlos.vault.wjdr.asset.AssetSnapshot.Queue empty =
                com.carlos.vault.wjdr.asset.AssetSnapshot.Queue.empty();
        assertFalse(ServerFieldTasks.marchSnapshotFromAsset(empty).known());
    }

    @Test
    public void backpackCount_parsesInitAndUpdateShapes() {
        java.util.Map<String, Object> init = new java.util.LinkedHashMap<>();
        init.put("data", java.util.Map.of("302000.0", java.util.Map.of("id", 302000L, "count", 3L)));
        assertEquals(3L, ServerFieldTasks.backpackCount(init, 302000L));
        java.util.Map<String, Object> update = new java.util.LinkedHashMap<>();
        update.put("type", "add");
        update.put("data", java.util.Arrays.asList(
                java.util.Map.of("id", 302001L, "count", 2L)));
        assertEquals(2L, ServerFieldTasks.backpackCount(update, 302001L));
        assertEquals(0L, ServerFieldTasks.backpackCount(update, 302000L));
        assertEquals(0L, ServerFieldTasks.backpackCount(null, 302000L));
    }
}
