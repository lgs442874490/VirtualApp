package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.EngineFactory;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.Session;
import com.carlos.vaultserver.bootstrap.BootstrapService.ProtocolPort;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BootstrapApi} 单测（M4b 任务书 §2.6）：八 action 分发 + 校验中文错误 +
 * batch-start→status 闭环（fake EngineFactory + 同步 executor + 跳时 sleeper，
 * 零真实网络/零线程等待）。
 */
public class BootstrapApiTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final BootstrapService.Sleeper NO_SLEEP = millis -> {
    };

    /** fake 引擎：login 固定结果，close 空转（同 BatchRunnerTest 思路）。 */
    private static final class FakeEngine extends WjdrEngine {
        FakeEngine() {
            super(null);
        }

        @Override
        public LoginResult login(String fpid, String sdkToken, Long serverid,
                                 Map<String, Object> extraParams) {
            LoginResult r = new LoginResult();
            r.uid = 8001L;
            r.serverId = serverid == null ? 0L : serverid;
            r.authContent = Map.of();
            return r;
        }
    }

    /** 脚本回放端口：req_loaddata 回熔炉达标 loaddata（批次同步跑完即 SUCCESS）。 */
    private static final class ScriptedPort implements ProtocolPort {
        final List<Map.Entry<String, Map<String, Object>>> calls = new ArrayList<>();
        /** 推送内容（proto → 已解包内容）：单号挂点 world/情报链路用（P1-2 回归）。 */
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        /** 可编程 loaddata 覆盖（缺省 301 lv=2 + 空队列）；用于构造熔炉 7 等场景。 */
        Map<String, Object> loadData = null;
        private final Map<String, java.util.ArrayDeque<Map<String, Object>>> scripted =
                new LinkedHashMap<>();

        @SafeVarargs
        final void when(String proto, Map<String, Object>... contents) {
            java.util.ArrayDeque<Map<String, Object>> q =
                    scripted.computeIfAbsent(proto, ignored -> new java.util.ArrayDeque<>());
            q.addAll(Arrays.asList(contents));
        }

        @Override
        public Map<String, Object> notify(String proto) {
            return notifies.get(proto);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(new AbstractMap.SimpleImmutableEntry<>(proto, args));
            GameMessage msg = new GameMessage();
            msg.setProto(proto);
            Map<String, Object> content = new LinkedHashMap<>();
            if ("req_loaddata".equals(proto)) {
                Map<String, Object> buildings = new LinkedHashMap<>();
                buildings.put("301", Map.of("bid", 301L, "lv", 2L, "status", 0L));
                content.put("uid", 8001L);
                content.put("buildings", buildings);
                content.put("queues", new ArrayList<>());
                if (loadData != null) content.putAll(loadData);
            }
            if ("req_alliance_search_by_key".equals(proto)) {
                // 入盟闭环（任务书 §2.2：目标达成后 joinAlliance）：唯一公开招募联盟
                content.put("code", 0);
                content.put("alliances", List.of(Map.of(
                        "aid", 7L, "name", "远征军", "abbr", "YZJ", "public", 1L,
                        "count", 50L, "member_max", 100L, "lv", 5L)));
            }
            java.util.ArrayDeque<Map<String, Object>> q = scripted.get(proto);
            if (q != null && !q.isEmpty()) {
                content.putAll(q.poll());
            } else {
                content.put("code", 0);
            }
            msg.setContent(content);
            return msg;
        }

        int count(String proto) {
            return (int) calls.stream().filter(c -> c.getKey().equals(proto)).count();
        }
    }

    private BootstrapBatchStore store;
    private BootstrapReferral referral;

    @Before
    public void setUp() {
        BootstrapHoldStore.shared().clearAll();
        BootstrapSessionState.shared().clearAll();
        Path dir = tmp.getRoot().toPath();
        store = new BootstrapBatchStore(dir);
        referral = new BootstrapReferral(dir);
        EngineFactory factory = (fpid, sdkToken, serverId) ->
                new Session(new FakeEngine(), new ScriptedPort());
        BootstrapApi.installForTest(store, referral, factory, NO_SLEEP, true);
    }

    @After
    public void tearDown() {
        BootstrapApi.resetForTest();
        BootstrapHoldStore.shared().clearAll();
        BootstrapSessionState.shared().clearAll();
    }

    private static Map<String, Object> fields(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String accountsJson(String fpid, String token) {
        JSONArray arr = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("fpid", fpid);
        o.put("sdkToken", token);
        o.put("label", "测试号");
        arr.put(o);
        return arr.toString();
    }

    // ================= import =================

    @Test
    public void importAction_parsesAndReports() {
        JSONObject out = BootstrapApi.handle(fields(
                "action", "import",
                "lines", new JSONArray(Arrays.asList("fp1|tk1", "bad!!")).toString()));
        assertEquals(1, out.optInt("count"));
        assertEquals(1, out.optInt("errorCount"));
        assertEquals("fp1", out.optJSONArray("imported").getJSONObject(0).optString("fpid"));
        assertTrue(out.optJSONArray("errors").getString(0).contains("第 2 行"));
    }

    @Test
    public void importAction_requiresLines() {
        JSONObject out = BootstrapApi.handle(fields("action", "import"));
        assertEquals(0, out.optInt("count"));
        assertTrue(out.optJSONArray("errors").length() > 0);
    }

    // ================= batch-start → status 闭环 =================

    @Test
    public void batchStartStatusClosedLoop() {
        JSONObject start = BootstrapApi.handle(fields(
                "action", "batch-start",
                "serverId", 3L,
                "targetFurnaceLevel", 2,
                "allianceName", "远征军",
                "accounts", accountsJson("fp1", "tk1")));
        assertFalse(start.optString("error", ""), start.has("error"));
        String batchId = start.optString("id");
        assertFalse(batchId.isEmpty());
        assertTrue(batchId.startsWith("bb"));
        assertEquals(3L, start.optLong("serverId"));
        assertEquals(2, start.optInt("targetFurnaceLevel"));
        assertEquals(1, start.optInt("accountCount"));

        // 同步 executor：batch-start 返回时批次已终态；RUNNERS 已移除 → status 走 store
        assertFalse(BootstrapApi.hasRunner(batchId));
        JSONObject status = BootstrapApi.handle(fields("action", "batch-status",
                "batchId", batchId));
        assertEquals("COMPLETED", status.optString("state"));
        assertEquals(1, status.optInt("successCount"));
        assertEquals(0, status.optInt("failCount"));
        assertEquals("远征军", status.optString("allianceName"));
        JSONObject account = status.optJSONArray("accounts").getJSONObject(0);
        assertEquals("SUCCESS", account.optString("state"));
        assertEquals("fp1", account.optString("fpid"));
        // 凭据纪律：视图只出 masked（PassportIds 语义，"tk1" ≤6 位 → 全掩 ***），不出明文 token
        assertEquals("***", account.optString("sdkTokenMasked"));
        assertFalse(account.has("sdkToken"));

        // batch-list
        JSONObject list = BootstrapApi.handle(fields("action", "batch-list"));
        assertEquals(1, list.optInt("count"));
        assertEquals(batchId, list.optJSONArray("batches").getJSONObject(0).optString("id"));

        // SUCCESS 写回流
        assertEquals(1, referral.listPending().size());
    }

    // ================= batch-cancel =================

    @Test
    public void batchCancel_terminalBatchReportsFinished() {
        // targetFurnaceLevel=2 与 fake loaddata（熔炉 2）匹配 → 同步 executor 下批次
        // 立即终态 COMPLETED（默认目标 9 会让每轮 loaddata 重置等级、空转至超时上限）
        JSONObject start = BootstrapApi.handle(fields(
                "action", "batch-start", "serverId", 1L, "targetFurnaceLevel", 2,
                "accounts", accountsJson("fp1", "tk1")));
        String batchId = start.optString("id");
        JSONObject out = BootstrapApi.handle(fields("action", "batch-cancel",
                "batchId", batchId));
        assertEquals(false, out.optBoolean("ok"));
        assertEquals("COMPLETED", out.optString("state"));
        assertTrue(out.optString("message").contains("批次已结束"));
    }

    @Test
    public void batchCancel_unknownBatchReportsError() {
        JSONObject out = BootstrapApi.handle(fields("action", "batch-cancel",
                "batchId", "bb-nope"));
        assertTrue(out.has("error"));
        assertTrue(out.optString("error").contains("批次不存在"));
    }

    // ================= referral =================

    @Test
    public void referralListAndPull() {
        JSONObject item = new JSONObject();
        item.put("fpid", "fp9");
        item.put("sdkToken", "tok-fp9-longenough");
        item.put("serverId", 1L);
        item.put("pulled", false);
        referral.append(item);

        JSONObject list = BootstrapApi.handle(fields("action", "referral-list"));
        assertEquals(1, list.optInt("count"));
        assertEquals("fp9", list.optJSONArray("entries").getJSONObject(0).optString("fpid"));

        JSONObject pull = BootstrapApi.handle(fields("action", "referral-pull",
                "fpids", new JSONArray(List.of("fp9")).toString()));
        assertEquals(1, pull.optInt("marked"));
        assertEquals(0, referral.listPending().size());
    }

    @Test
    public void referralPull_requiresFpids() {
        JSONObject out = BootstrapApi.handle(fields("action", "referral-pull"));
        assertTrue(out.optString("error").contains("fpids"));
    }

    // ================= single-* =================

    @Test
    public void singleStatus_withoutActiveSession_reportsOffline() {
        JSONObject out = BootstrapApi.handle(fields("action", "single-status",
                "fpid", "fp1"));
        assertTrue(out.optString("error").contains("该号不在线"));
    }

    @Test
    public void singleActions_withActiveSession_dispatch() throws Exception {
        ScriptedPort port = new ScriptedPort();
        // serviceOf 与批次线程同链路：装配后先经 req_loaddata 同步全量状态
        // （ScriptedPort 回 buildings 301/lv=2 + 空队列），single-* 可真实推进。
        BootstrapApi.ACTIVE.put("fp-live", new Session(new FakeEngine(), port));

        JSONObject status = BootstrapApi.handle(fields("action", "single-status", "fpid", "fp-live"));
        assertFalse(status.optString("error", ""), status.has("error"));
        assertEquals("fp-live", status.optString("fpid"));
        assertTrue(status.has("furnaceLevel"));
        assertTrue("loaddata 同步后状态就绪", status.optBoolean("stateReady"));

        JSONObject next = BootstrapApi.handle(fields("action", "single-run-next", "fpid", "fp-live"));
        assertFalse(next.optString("error", ""), next.has("error"));
        assertEquals("状态已同步：真实执行而非 WAITING", "STARTED", next.optString("status"));

        JSONObject until = BootstrapApi.handle(fields("action", "single-run-until",
                "fpid", "fp-live", "maxSteps", 3));
        assertFalse(until.optString("error", ""), until.has("error"));
        assertTrue(until.has("started"));
        assertTrue("至少真实执行一步", until.optInt("started") >= 1);

        JSONObject level = BootstrapApi.handle(fields("action", "single-level-building",
                "fpid", "fp-live", "buildingId", 301L));
        // 301 现等级 2 → 直升 3：升级请求真实下发（code=0）→ STARTED
        assertEquals("STARTED", level.optString("status"));

        JSONObject alliance = BootstrapApi.handle(fields("action", "single-alliance", "fpid", "fp-live"));
        assertTrue("联盟名未配置 → 中文错误", alliance.optString("error").contains("联盟"));

        BootstrapApi.ACTIVE.remove("fp-live");
    }

    /** P1-2 回归：serviceOf 经 req_loaddata 同步状态后，single-run-next 真实推进一步（不再永远 WAITING）。 */
    @Test
    public void singleRunNext_progressesAfterLoadDataSync() {
        ScriptedPort port = new ScriptedPort();
        BootstrapApi.ACTIVE.put("fp-live", new Session(new FakeEngine(), port));
        try {
            JSONObject next = BootstrapApi.handle(fields("action", "single-run-next", "fpid", "fp-live"));
            assertFalse(next.optString("error", ""), next.has("error"));
            assertEquals("状态已同步：真实执行而非 WAITING", "STARTED", next.optString("status"));
            boolean sawLoadData = false;
            for (Map.Entry<String, Map<String, Object>> call : port.calls) {
                if ("req_loaddata".equals(call.getKey())) sawLoadData = true;
            }
            assertTrue("serviceOf 应经 port 发 req_loaddata", sawLoadData);
        } finally {
            BootstrapApi.ACTIVE.remove("fp-live");
        }
    }

    /**
     * 卡点 A / P1-2 回归：单号挂点每次请求都重建 {@link BootstrapService}，51 hold 必须
     * 跨请求可见（{@link BootstrapHoldStore} 按 fpid 进程级共享）。
     *
     * <p>第 1 次请求「建造 民居1 到 1 级」被 51 hold；第 2 次请求不得再重发同一个必然被拒的
     * 请求，而应返回 WAITING「动作退避中」（其余动作都依赖民居1，故无可绕行动作）。
     */
    @Test
    public void singleRunNext_holdVisibleAcrossRequests_doesNotRetryHeldAction() {
        ScriptedPort port = new ScriptedPort();
        port.when("req_upgrade_building", Map.of("code", 51L), Map.of("code", 51L),
                Map.of("code", 0L));
        String fpid = "fp-hold-" + System.nanoTime();
        BootstrapApi.ACTIVE.put(fpid, new Session(new FakeEngine(), port));
        try {
            JSONObject first = BootstrapApi.handle(fields("action", "single-run-next", "fpid", fpid));
            assertFalse(first.optString("error", ""), first.has("error"));
            assertEquals("WAITING", first.optString("status"));
            assertTrue(first.optString("message").contains("资源或道具不足"));

            JSONObject second = BootstrapApi.handle(fields("action", "single-run-next", "fpid", fpid));
            assertFalse(second.optString("error", ""), second.has("error"));
            assertEquals("WAITING", second.optString("status"));
            assertTrue("跨请求 hold 生效：应返回退避等待文案",
                    second.optString("message").contains("动作退避中"));

            int retriedHeld = 0;
            for (Map.Entry<String, Map<String, Object>> call : port.calls) {
                if ("req_upgrade_building".equals(call.getKey())
                        && Long.valueOf(308L).equals(call.getValue().get("bid"))) {
                    retriedHeld++;
                }
            }
            assertEquals("被 hold 的民居1 动作只应出现在首次请求（51 首发 + 补偿重试）",
                    2, retriedHeld);
        } finally {
            BootstrapApi.ACTIVE.remove(fpid);
            BootstrapHoldStore.shared().clearScope(fpid);
        }
    }

    @Test
    public void singleAction_requiresFpid() {
        JSONObject out = BootstrapApi.handle(fields("action", "single-status"));
        assertTrue(out.optString("error").contains("fpid"));
    }

    // ================= P1-2 回归：单号挂点会话状态跨请求 =================

    /** 熔炉 7 + 情报推送 + fog 可发的 loaddata（单号挂点 world 链路场景）。 */
    private static Map<String, Object> furnace7Load() {
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("uid", 8001L);
        load.put("buildings", Map.of(
                "301", Map.of("bid", 301L, "lv", 7L, "status", 0L),
                "302", Map.of("bid", 302L, "lv", 1L, "status", 0L)));
        load.put("queues", new ArrayList<>());
        return load;
    }

    /**
     * P1-2 回归（需求 R-02 验收 1）：熔炉 7 + 情报推送存在时，连续两次 single-run-next，
     * 第二次不得重发 {@code req_world_kingdom_info}（world 标记按 fpid 跨请求存活）。
     */
    @Test
    public void singleRunNext_worldAdvanceStatePersistsAcrossRequests() {
        ScriptedPort port = new ScriptedPort();
        port.loadData = furnace7Load();
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO, Map.of(
                "events", Map.of("11", Map.of("id", 11L, "status", 2L, "type", 0L))));
        port.when("req_world_kingdom_info", Map.of("code", 0L, "kid", 77L));
        String fpid = "fp-world-" + System.nanoTime();
        BootstrapApi.ACTIVE.put(fpid, new Session(new FakeEngine(), port));
        try {
            JSONObject first = BootstrapApi.handle(fields("action", "single-run-next", "fpid", fpid));
            assertFalse(first.optString("error", ""), first.has("error"));
            assertEquals("STARTED", first.optString("status"));
            assertEquals("首次应发 req_world_kingdom_info",
                    1, port.count("req_world_kingdom_info"));

            JSONObject second = BootstrapApi.handle(fields("action", "single-run-next", "fpid", fpid));
            assertFalse(second.optString("error", ""), second.has("error"));
            assertEquals("跨请求不得重发 req_world_kingdom_info",
                    1, port.count("req_world_kingdom_info"));
            assertEquals("第二次应发 req_world_map_fog（kid 跨请求保留）",
                    1, port.count("req_world_map_fog"));
        } finally {
            BootstrapApi.ACTIVE.remove(fpid);
            BootstrapSessionState.shared().clearScope(fpid);
        }
    }

    /**
     * P1-2 回归（需求 R-02 验收 2）：连续请求 single-run-next 时，finishPass 的
     * 邮箱/里程碑/背包钻石收尾当日只执行一轮。
     */
    @Test
    public void singleRunNext_finishPassRunsOnceAcrossRequests() {
        ScriptedPort port = new ScriptedPort();
        Map<String, Object> load = furnace7Load();
        load.put("bag", Map.of("100001", 2L));   // 背包钻石道具
        port.loadData = load;
        // 情报快照存在但无可领事件：world 完成后进入 finishPass
        port.notifies.put(BootstrapService.NOTIFY_INTEL_INFO, Map.of("events", Map.of()));
        port.when("req_world_kingdom_info", Map.of("code", 0L, "kid", 77L));
        port.when("req_use_item", Map.of("code", 0L));
        String fpid = "fp-finish-" + System.nanoTime();
        BootstrapApi.ACTIVE.put(fpid, new Session(new FakeEngine(), port));
        try {
            BootstrapApi.handle(fields("action", "single-run-next", "fpid", fpid));   // kingdom info
            BootstrapApi.handle(fields("action", "single-run-next", "fpid", fpid));   // fog

            JSONObject finish = BootstrapApi.handle(fields(
                    "action", "single-run-next", "fpid", fpid));
            assertFalse(finish.optString("error", ""), finish.has("error"));
            assertTrue("第 3 次应执行 ≥7 收尾：" + finish,
                    finish.optString("message").contains("收尾完成"));
            assertTrue("收尾应打开背包钻石道具", port.count("req_use_item") >= 1);

            JSONObject again = BootstrapApi.handle(fields(
                    "action", "single-run-next", "fpid", fpid));
            assertFalse(again.optString("error", ""), again.has("error"));
            assertFalse("finishPass 当日只执行一轮：" + again,
                    again.optString("message").contains("收尾完成"));
        } finally {
            BootstrapApi.ACTIVE.remove(fpid);
            BootstrapSessionState.shared().clearScope(fpid);
        }
    }

    // ================= 校验中文错误 =================

    @Test
    public void batchStart_validations() {
        // serverId 必须为正整数
        assertEquals("serverId 必须为正整数", BootstrapApi.handle(fields(
                "action", "batch-start", "serverId", 0L,
                "accounts", accountsJson("fp1", "tk1"))).optString("error"));
        // targetFurnaceLevel 1–9
        assertEquals("targetFurnaceLevel 必须在 1–9 之间", BootstrapApi.handle(fields(
                "action", "batch-start", "serverId", 1L, "targetFurnaceLevel", 10,
                "accounts", accountsJson("fp1", "tk1"))).optString("error"));
        // accounts 缺失
        assertTrue(BootstrapApi.handle(fields(
                "action", "batch-start", "serverId", 1L)).optString("error").contains("accounts"));
        // accounts 非 JSON 数组
        assertTrue(BootstrapApi.handle(fields(
                "action", "batch-start", "serverId", 1L, "accounts", "not-json"))
                .optString("error").contains("accounts"));
        // accounts 数量超限
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 101; i++) tooMany.put("fp" + i + "|tk");
        assertTrue(BootstrapApi.handle(fields(
                "action", "batch-start", "serverId", 1L, "accounts", tooMany.toString()))
                .optString("error").contains("1–100"));
    }

    @Test
    public void unknownAction_reportsChineseError() {
        JSONObject out = BootstrapApi.handle(fields("action", "nope"));
        assertTrue(out.optString("error").contains("未知 action"));
    }

    @Test
    public void statusOfUnknownBatch_reportsChineseError() {
        JSONObject out = BootstrapApi.handle(fields("action", "batch-status", "batchId", "ghost"));
        assertTrue(out.optString("error").contains("批次不存在"));
    }

    @Test
    public void engineCloseIdempotentOnFakeSession() {
        // Session.close 走 WjdrEngine.close（fake 覆写空转）——断言不抛即语义正常
        Session session = new Session(new FakeEngine(), new ScriptedPort());
        session.close();
        assertNotNull(session.engine);
    }
}
