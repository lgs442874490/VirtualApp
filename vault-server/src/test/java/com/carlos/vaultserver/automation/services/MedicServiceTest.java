package com.carlos.vaultserver.automation.services;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 军医所单测（2026-10-28 功能型 bug 3 回归）。
 *
 * <p>覆盖三层：
 * <ol>
 *   <li>伤兵统计：init 与 wound push 必须合并取 max，不能只看 init（既有用例）；</li>
 *   <li><b>P0 数据丢失回归</b>：{@link MedicService#snapshot} 曾只把 sources 传进 Snapshot，
 *       wounded/healing 被丢弃 → woundedTotal() 恒为 0（生产实证"无伤兵（hospital_init=376; ）"）；</li>
 *   <li>分批循环治疗/本地扣减/联盟求助 qid 解析（网页版 DailyHospitalState.planHealingBatch:332-346、
 *       DailyTownService.processHospitalHealing:965-1035、RecoveryAutomationExecutor:20-22）。</li>
 * </ol>
 * 不连真协议：用 {@link FakeEngine} 回放推送/响应。
 */
public class MedicServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;

    @org.junit.Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
    }

    // ==================== 伤兵统计（既有回归） ====================

    @Test
    public void parsesListShapeAndKeepsLargeWoundedCount() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Exec.kv(10101, 800));
        list.add(Exec.kv(20101, 400));
        Map<Long, Long> m = MedicService.parseWounded(list);
        assertEquals(Long.valueOf(800), m.get(10101L));
        assertEquals(Long.valueOf(400), m.get(20101L));
    }

    @Test
    public void parsesNestedSoldiersShape() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Exec.kv(30101, 55));
        nested.put("soldiers", list);
        Map<Long, Long> m = MedicService.parseWounded(nested);
        assertEquals(Long.valueOf(55), m.get(30101L));
    }

    @Test
    public void mergeAcrossInitAndPushTakesMaxPerType() {
        Map<Long, Long> init = new LinkedHashMap<>();
        init.put(10101L, 100L);
        Map<Long, Long> push = new LinkedHashMap<>();
        push.put(10101L, 900L);   // 推送才是实际大量伤兵
        push.put(20101L, 300L);
        MedicService.mergeMax(init, push);
        assertEquals(Long.valueOf(900), init.get(10101L));
        assertEquals(Long.valueOf(300), init.get(20101L));
        assertEquals(1200, init.values().stream().mapToLong(Long::longValue).sum());
    }

    // ==================== P0：Snapshot 必须携带 wounded/healing ====================

    @Test
    public void buildSnapshot_keepsWoundedTotal_regressionForDroppedMaps() {
        // 生产实证：hospital_init=376 但状态行写"无伤兵" → told woundedTotal 恒 0。
        Map<String, Object> init = map("wound_soldiers", pairs(Exec.kv(10101, 376)));
        MedicService.Snapshot s = MedicService.buildSnapshot(mapOfNotifies(
                "notify_hospital_init", init));
        assertEquals("伤兵必须进入 Snapshot（不允许丢图）", 376L, s.woundedTotal());
        assertTrue("来源文案应保留 hospital_init=376，实际=" + s.sources,
                s.sources.contains("hospital_init=376"));
    }

    @Test
    public void buildSnapshot_unwrapsDataWrapperAndMergesPushByMax() {
        Map<String, Object> init = map("data", map("wound_soldiers", pairs(Exec.kv(10101, 100))));
        Map<String, Object> push = map("data", map("wound_soldiers", pairs(
                Exec.kv(10101, 900), Exec.kv(20101, 300))));
        MedicService.Snapshot s = MedicService.buildSnapshot(mapOfNotifies(
                "notify_hospital_init", init,
                "notify_hospital_wound_soldiers", push));
        assertEquals("同兵种取 max，不重复累加", 900L + 300L, s.woundedTotal());
    }

    @Test
    public void buildSnapshot_readsHealingFromInitHealData() {
        Map<String, Object> init = map(
                "wound_soldiers", pairs(Exec.kv(10101, 500)),
                "heal_data", map("soldiers", pairs(Exec.kv(20101, 120))));
        MedicService.Snapshot s = MedicService.buildSnapshot(mapOfNotifies(
                "notify_hospital_init", init));
        assertEquals(500L, s.woundedTotal());
        assertEquals("治疗中伤兵单独统计", 120L, s.healingTotal());
    }

    @Test
    public void buildSnapshot_emptyNotifiesHasReadableSource() {
        MedicService.Snapshot s = MedicService.buildSnapshot(Collections.emptyMap());
        assertEquals(0L, s.woundedTotal());
        assertTrue(s.sources.contains("无军医所通知"));
    }

    // ==================== 批次规划 / 扣减 ====================

    @Test
    public void planHealingBatch_prefersHigherLevelThenTroopOrder() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 100L);   // 盾 T1
        wounded.put(11101L, 50L);    // 盾 T11 → 最高等级优先
        wounded.put(30101L, 30L);    // 弓 T1
        wounded.put(20101L, 20L);    // 矛 T1
        Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 120);
        assertEquals("先取 T11 的 50 名", Long.valueOf(50L), batch.get(11101L));
        assertEquals("再按兵种顺序取盾兵 70 名", Long.valueOf(70L), batch.get(10101L));
        assertEquals("本批总量不超过 heal_count", 120L, sum(batch));
        assertFalse(batch.containsKey(30101L));
    }

    @Test
    public void planHealingBatch_limitZeroMeansAll() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 100L);
        wounded.put(20101L, 20L);
        Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 0);
        assertEquals(120L, sum(batch));
        assertEquals(2, batch.size());
    }

    @Test
    public void planHealingBatch_skipsUnsupportedSoldierTypes() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 10L);
        wounded.put(40101L, 999L);   // 兵种 4 非伤兵可治疗范围
        wounded.put(10L, 5L);        // 非法 id
        Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 0);
        assertEquals(1, batch.size());
        assertEquals(Long.valueOf(10L), batch.get(10101L));
    }

    @Test
    public void subtractBatch_removesClearedAndKeepsRemaining() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 500L);
        wounded.put(20101L, 100L);
        Map<Long, Long> batch = new LinkedHashMap<>();
        batch.put(10101L, 200L);
        batch.put(20101L, 100L);
        Map<Long, Long> left = MedicService.subtractBatch(wounded, batch);
        assertEquals(Long.valueOf(300L), left.get(10101L));
        assertFalse("扣到 0 的兵种应移除", left.containsKey(20101L));
        assertEquals("入参不可被修改", Long.valueOf(500L), wounded.get(10101L));
        assertEquals(Long.valueOf(100L), wounded.get(20101L));
    }

    // ==================== 医院队列 qid 解析 ====================

    @Test
    public void hospitalQid_mapKeyShape() {
        Map<String, Object> content = map("data", map("queue_map", map(
                "5.0", map("base", map("qtype", 1)),
                "123.0", map("base", map("qtype", 6)))));
        assertEquals(123L, MedicService.hospitalQidOfNotify(content));
    }

    @Test
    public void hospitalQid_listShape() {
        List<Map<String, Object>> queues = new ArrayList<>();
        queues.add(map("qid", 9, "base", map("qtype", 2)));
        queues.add(map("qid", 77, "base", map("qtype", 6)));
        assertEquals(77L, MedicService.hospitalQidOfNotify(map("queue_map", queues)));
    }

    @Test
    public void hospitalQid_unwrappedShape() {
        // notifyData 已剥 data 包装后的内层
        assertEquals(88L, MedicService.hospitalQidOfNotify(map("queue_map", map(
                "88", map("base", map("qtype", 6))))));
    }

    @Test
    public void hospitalQid_singleQueueShape() {
        assertEquals(66L, MedicService.hospitalQidOfNotify(
                map("queue", map("qid", 66, "base", map("qtype", 6)))));
    }

    @Test
    public void hospitalQid_otherQueueTypesOrMissingReturnsZero() {
        assertEquals("非医院队列不能当求助目标", 0L, MedicService.hospitalQidOfNotify(
                map("queue_map", map("1", map("base", map("qtype", 1))))));
        assertEquals(0L, MedicService.hospitalQidOfNotify(map("code", 0)));
        assertEquals(0L, MedicService.hospitalQidOfNotify(null));
        assertEquals(0L, MedicService.hospitalQidOfQueueMap(null));
    }

    // ==================== 循环治疗主流程（回放响应） ====================

    @Test
    public void heal_regressionForDroppedWounded_healsAndAsksHelpWithQid() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 376))));
        engine.hospitalQueueQid = 123L;
        engine.responses.add(map("code", 0));

        TaskOutcome r = new MedicService().heal(context(engine), 400, false, true);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertTrue("应写明治疗数量，实际=" + r.message, r.message.contains("治疗376"));
        assertTrue(r.message.contains("伤兵已清零"));
        assertEquals("一批 + 一次求助", 2, engine.requestProtos.size());
        assertEquals("req_hospital_heal_soldier", engine.requestProtos.get(0));
        assertEquals("req_queue_ask_for_help", engine.requestProtos.get(1));
        assertEquals("求助必须带医院队列 qid（旧实现误用 req_alliance_help_all）",
                123L, Exec.longOf(engine.requestArgs.get(1).get("qid")));
        // payload 仍是 soldiers:[{k,v}]，immediate 透传
        List<Map<String, Object>> soldiers =
                (List<Map<String, Object>>) engine.requestArgs.get(0).get("soldiers");
        assertEquals(1, soldiers.size());
        assertEquals(10101L, Exec.longOf(soldiers.get(0).get("k")));
        assertEquals(376L, Exec.longOf(soldiers.get(0).get("v")));
        assertEquals(Boolean.FALSE, engine.requestArgs.get(0).get("immediate"));
        // 状态行
        JSONObject mod = modState(engine);
        assertTrue(mod.optString("heal", "").contains("治疗376"));
        assertTrue(mod.optString("help", "").contains("已求助"));
    }

    @Test
    public void heal_loopsBatchesWhenServerAcceptsThem() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 1000))));
        engine.hospitalQueueQid = 7L;
        for (int i = 0; i < 3; i++) engine.responses.add(map("code", 0));

        TaskOutcome r = new MedicService().heal(context(engine), 400, true, true);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        int healRequests = 0;
        int helpRequests = 0;
        for (String p : engine.requestProtos) {
            if ("req_hospital_heal_soldier".equals(p)) healRequests++;
            if ("req_queue_ask_for_help".equals(p)) helpRequests++;
        }
        assertEquals("400+400+200 三批", 3, healRequests);
        assertEquals("同一轮求助只发一次，不随批次刷屏", 1, helpRequests);
        assertTrue(r.message.contains("治疗1000伤兵/3批"));
        assertTrue(r.message.contains("伤兵已清零"));
    }

    @Test
    public void heal_stopsOn2301HealingInProgress() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 1000))));
        engine.responses.add(map("code", 0));
        engine.responses.add(map("code", 2301));

        TaskOutcome r = new MedicService().heal(context(engine), 400, false, false);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertTrue("应写明上批治疗中，实际=" + r.message,
                r.message.contains("上批仍在治疗中"));
        assertTrue(r.message.contains("治疗400伤兵/1批"));
        assertEquals("第二批被 2301 挡住即停，不再放大请求", 2, engine.requestProtos.size());
    }

    @Test
    public void heal_resourceShort51IsSkipNotError() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 400))));
        engine.responses.add(map("code", 51));

        TaskOutcome r = new MedicService().heal(context(engine), 400, false, true);

        assertEquals("资源不足是正常跳过，不该刷 ERROR", TaskOutcome.Category.SKIPPED, r.category);
        assertTrue(r.message.contains("资源不足"));
        assertEquals(1, engine.requestProtos.size());
    }

    @Test
    public void heal_withoutQueueSyncDoesNotBlindFireHelp() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 400))));
        engine.hospitalQueueQid = 0L;   // 医院队列尚未同步
        engine.responses.add(map("code", 0));

        new MedicService().heal(context(engine), 400, false, true);

        assertEquals("解析不到 qid 时不能盲发求助", 1, engine.requestProtos.size());
        assertFalse(engine.requestProtos.contains("req_queue_ask_for_help"));
        assertTrue(modState(engine).optString("help", "").contains("求助未发（医院队列未同步）"));
    }

    @Test
    public void heal_requestHelpFalseSkipsHelp() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 400))));
        engine.hospitalQueueQid = 9L;
        engine.responses.add(map("code", 0));

        new MedicService().heal(context(engine), 400, false, false);

        assertFalse(engine.requestProtos.contains("req_queue_ask_for_help"));
        assertTrue(modState(engine).optString("help", "").contains("求助已关闭"));
    }

    @Test
    public void heal_noWoundedWritesReadableSkip() {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs()));

        TaskOutcome r = new MedicService().heal(context(engine), 400, false, true);

        assertEquals(TaskOutcome.Category.SKIPPED, r.category);
        assertTrue(r.message.contains("无伤兵"));
        assertTrue(modState(engine).optString("heal", "").startsWith("skip:"));
        assertTrue(engine.requestProtos.isEmpty());
    }

    @Test
    public void helpSubPrefix_classifiesOkSkipFail() {
        // App 执行参数卡按 ok/skip/fail 着色：只有真正发成功才 ok，失败/异常必须 fail。
        assertEquals("ok:", MedicService.helpSubPrefix("已求助联盟（qid=123）"));
        assertEquals("skip:", MedicService.helpSubPrefix("求助未发（医院队列未同步）"));
        assertEquals("skip:", MedicService.helpSubPrefix("求助已关闭（request_help=false）"));
        assertEquals("skip:", MedicService.helpSubPrefix("求助暂不可用（码75）"));
        assertEquals("fail:", MedicService.helpSubPrefix("求助失败（码999）"));
        assertEquals("fail:", MedicService.helpSubPrefix("求助异常：SocketException"));
        assertEquals("skip:", MedicService.helpSubPrefix(null));
    }

    @Test
    public void heal_helpFailureMarkedFailNotOk() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 400))));
        engine.hospitalQueueQid = 12L;
        engine.responses.add(map("code", 0));
        engine.responses.add(map("code", 999));   // 求助失败

        TaskOutcome r = new MedicService().heal(context(engine), 400, false, true);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertEquals("fail:求助失败（码999）（1批后）", r.subResults.get("help"));
        assertTrue(modState(engine).optString("help", "").contains("求助失败"));
    }

    @Test
    public void heal_honoursMedicSubSwitch() {
        // App 执行参数卡「自动治疗」子开关（medic.sub.heal）：关掉后服务端不得偷偷治疗。
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 400))));
        JSONObject params = new JSONObject();
        params.put("sub", new JSONObject().put("heal", false));
        TaskContext tc = new TaskContext(engine, "48460403", 888L, store,
                Collections.singletonMap("medic", params), "服务器");

        TaskOutcome r = new MedicService().heal(tc, 400, false, true);

        assertEquals(TaskOutcome.Category.SKIPPED, r.category);
        assertTrue(r.message.contains("自动治疗已关闭"));
        assertTrue(engine.requestProtos.isEmpty());
    }

    @Test
    public void healFromParams_defaultsTo400PerBatch() throws Exception {
        // Lead 契约（2026-10-28）：App 不再"打开医院卡即写默认 400"，服务端缺省必须是 400，
        // 不能把缺省 0 当"全部"（否则用户没动过设置就一次性请求全部伤兵）。
        assertEquals(400, MedicService.DEFAULT_HEAL_COUNT);
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 1000))));
        for (int i = 0; i < 3; i++) engine.responses.add(map("code", 0));
        TaskContext tc = context(engine);   // params 为空 → 缺省 heal_count=400

        assertEquals("缺省解析为 400", 400, MedicService.healCountOf(tc));
        TaskOutcome r = MedicService.healFromParams(tc);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertEquals("1000 名按 400/400/200 三批", 3, countProto(engine, "req_hospital_heal_soldier"));
        assertEquals("第一批 400 名", 400L, firstHealBatchSum(engine));
        assertTrue(r.message.contains("治疗1000伤兵/3批"));
    }

    @Test
    public void healFromParams_explicitZeroStillMeansAll() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 500), Exec.kv(20101, 200))));
        engine.responses.add(map("code", 0));
        JSONObject params = new JSONObject();
        params.put("heal_count", 0);   // 显式 0 兼容旧客户端语义 = 全部
        TaskContext tc = new TaskContext(engine, "48460403", 888L, store,
                Collections.singletonMap("medic", params), "服务器");

        assertEquals(0, MedicService.healCountOf(tc));
        TaskOutcome r = MedicService.healFromParams(tc);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertEquals("0=全部 → 一批清空", 1, countProto(engine, "req_hospital_heal_soldier"));
        assertEquals(700L, firstHealBatchSum(engine));
    }

    @Test
    public void healFromParams_respectsExplicitHealCountAndRequestHelp() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 5000))));
        engine.hospitalQueueQid = 55L;
        engine.responses.add(map("code", 0));
        engine.responses.add(map("code", 2301));
        JSONObject params = new JSONObject();
        params.put("heal_count", 1500);
        params.put("request_help", true);
        TaskContext tc = new TaskContext(engine, "48460403", 888L, store,
                Collections.singletonMap("medic", params), "服务器");

        TaskOutcome r = MedicService.healFromParams(tc);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertEquals("显式 1500 → 第一批 1500", 1500L, firstHealBatchSum(engine));
        assertTrue("request_help=true → 带 qid 求助",
                engine.requestProtos.contains("req_queue_ask_for_help"));
    }

    /** 首次治疗请求的 soldiers 总数（跳过求助等非治疗请求）。 */
    private static long firstHealBatchSum(FakeEngine engine) {
        for (int i = 0; i < engine.requestProtos.size(); i++) {
            if (!"req_hospital_heal_soldier".equals(engine.requestProtos.get(i))) continue;
            Object raw = engine.requestArgs.get(i).get("soldiers");
            if (!(raw instanceof List)) return 0L;
            long sum = 0L;
            for (Object o : (List<?>) raw) {
                Map<String, Object> m = Exec.mapOf(o);
                if (m != null) sum += Exec.longOf(m.get("v"));
            }
            return sum;
        }
        return 0L;
    }

    @Test
    public void healAllAtOnceWhenHealCountZero() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.notifies.put("notify_hospital_init", map(
                "wound_soldiers", pairs(Exec.kv(10101, 500), Exec.kv(20101, 200))));
        engine.responses.add(map("code", 0));

        TaskOutcome r = new MedicService().heal(context(engine), 0, false, false);

        assertEquals(TaskOutcome.Category.CLAIMED, r.category);
        assertTrue(r.message.contains("治疗700伤兵/1批"));
        assertEquals(1, countProto(engine, "req_hospital_heal_soldier"));
    }

    // ==================== 测试基础设施 ====================

    private TaskContext context(FakeEngine engine) {
        return new TaskContext(engine, "48460403", 888L,
                store, Collections.emptyMap(), "服务器");
    }

    private JSONObject modState(FakeEngine engine) {
        String raw = store.getString("mod:48460403:888:medic");
        return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    private static int countProto(FakeEngine engine, String proto) {
        int n = 0;
        for (String p : engine.requestProtos) if (proto.equals(p)) n++;
        return n;
    }

    private static long sum(Map<Long, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }

    @SafeVarargs
    private static List<Map<String, Object>> pairs(Map<String, Object>... items) {
        List<Map<String, Object>> out = new ArrayList<>();
        Collections.addAll(out, items);
        return out;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Map<String, Object>> mapOfNotifies(Object... kv) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), (Map<String, Object>) kv[i + 1]);
        }
        return m;
    }

    /** 只回放推送/响应、不连网的引擎替身（同 formation/TestEngines.FakeEngine 语义）。 */
    static final class FakeEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        final List<String> requestProtos = new ArrayList<>();
        final List<Map<String, Object>> requestArgs = new ArrayList<>();
        final Deque<Map<String, Object>> responses = new ArrayDeque<>();
        /** 医院队列 qid（0=未同步）；用于构造 notify_queue_info 推送。 */
        long hospitalQueueQid;

        FakeEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
            // 单测不等待
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            if ("notify_queue_info".equals(proto) && hospitalQueueQid > 0L) {
                return map("queue_map", map(String.valueOf(hospitalQueueQid),
                        map("base", map("qtype", 6))));
            }
            return notifies.get(proto);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifyContent(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requestProtos.add(proto);
            requestArgs.add(args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args));
            GameMessage msg = new GameMessage();
            msg.setProto(proto + ".response");
            msg.setContent(responses.isEmpty()
                    ? new LinkedHashMap<>() : responses.poll());
            return msg;
        }
    }
}
