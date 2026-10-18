package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 整轮执行：配置门控、结果落键（log:/st:/summary:）、会话中断截断、未迁移任务标记。 */
public class RoundRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private VaultStore.Account acc;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        acc = new VaultStore.Account("111");
        acc.token = "tk";
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }

    @Test
    public void fullRound_writesLogStatusSummary() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("rewards", "diamond:10");
        e.responses.put("req_fourteen_sign_get_all_reward", msg(ok));
        e.responses.put("req_receive_online_reward", msg(new HashMap<>(ok)));

        String summary = RoundRunner.runRound(e, acc, 913L, store, null, null, "定时");

        // summary 与 lastrun
        assertNotNull(store.getString("summary:111"));
        assertTrue(summary.contains("登录好礼"));
        // 日志键：log: 前缀 + 最新在前
        String log = store.getString("log:111:913:login_rewards");
        assertNotNull(log);
        JSONArray arr = new JSONArray(log);
        assertEquals(1, arr.length());
        JSONObject entry = arr.getJSONObject(0);
        assertEquals("CLAIMED", entry.getString("cat"));
        assertEquals("定时", entry.getString("trg"));
        assertTrue(entry.getLong("dur") >= 0);
        // 当日状态机：CLAIMED/SKIPPED → 2
        assertEquals(2L, ((Number) store.snapshot().get(
                stKey("111:913:login_rewards"))).longValue());
        // 12 个任务每个都有日志与状态
        for (ServerTasks.TaskDef d : ServerTasks.all()) {
            assertNotNull("缺日志 " + d.id, store.getString("log:111:913:" + d.id));
        }
    }

    @Test
    public void configOff_skipsTask() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("login_rewards", false);
        cfg.put("daily_mission", true);
        // daily_mission 未配置响应 → DEGRADED，但会落日志
        acc.roleConfig.put("913", cfg);

        RoundRunner.runRound(e, acc, 913L, store, null, null, "定时");

        JSONArray log = new JSONArray(store.getString("log:111:913:login_rewards"));
        assertEquals("配置未启用", log.getJSONObject(0).getString("reason"));
        assertNotNull(store.getString("log:111:913:daily_mission"));
    }

    @Test
    public void enabledButUnported_markedPending() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("future_task_x", true);   // 目录里不存在的任务（未来批次前）→ 记待迁移
        acc.roleConfig.put("913", cfg);

        RoundRunner.runRound(e, acc, 913L, store, null, null, "定时");

        JSONArray log = new JSONArray(store.getString("log:111:913:future_task_x"));
        assertTrue(log.getJSONObject(0).getString("reason").contains("待迁移"));
    }

    @Test
    public void singleTask_onlyRunsRequested() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        RoundRunner.runRound(e, acc, 913L, store, null, "supply", "手动");
        assertNotNull(store.getString("log:111:913:supply"));
        assertEquals(null, store.getString("log:111:913:login_rewards"));
    }

    @Test
    public void supplyParams_strategyRespected() {
        CaptureTasksTest.FakeEngine e = new CaptureTasksTest.FakeEngine();
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("online_reward", "wood:5");
        e.responses.put("req_receive_online_reward", msg(ok));
        // online 子项关闭 → 不发普通补给请求
        Map<String, JSONObject> params = new HashMap<>();
        JSONObject p = new JSONObject();
        JSONObject sub = new JSONObject();
        sub.put("online", false);
        p.put("sub", sub);
        params.put("supply", p);

        RoundRunner.runRound(e, acc, 913L, store, params, "supply", "手动");

        JSONArray log = new JSONArray(store.getString("log:111:913:supply"));
        assertTrue(log.getJSONObject(0).getString("reason").contains("普通补给已关"));
    }

    @Test
    public void taskContext_paramsAccess() {
        Map<String, JSONObject> params = new HashMap<>();
        JSONObject p = new JSONObject();
        JSONObject sub = new JSONObject();
        sub.put("spec", false);
        p.put("sub", sub);
        JSONObject st = new JSONObject();
        st.put("spec", "end30");
        p.put("strategy", st);
        p.put("strategy_min_spec", 45);
        params.put("supply", p);
        TaskContext tc = new TaskContext(null, "111", 913L, store, params, "手动");

        assertEquals(false, tc.subOn("supply", "spec", true));
        assertEquals(true, tc.subOn("supply", "online", true));
        assertEquals("end30", tc.strategy("supply", "spec", "now"));
        assertEquals(45, tc.strategyMinutes("supply", "spec", 30));
        assertEquals("now", tc.strategy("mail", "read", "now"));
    }

    @Test
    public void subResults_categoriesMatchSkipFailOk() {
        // P2-1：子项 skip 不得记成 CLAIMED/ok=true（"已检查但无可领"≠领取成功）
        TaskOutcome r = new TaskOutcome("supply", "仓库补给", TaskOutcome.Category.CLAIMED, "ok");
        r.subResults.put("online", "ok:已领wood:5");
        r.subResults.put("spec", "skip:暂不可领");
        r.subResults.put("extra", "fail:请求炸了");

        RoundRunner.applyResult(store, "111", 913L, "supply", r, "定时");

        JSONObject okEntry = new JSONArray(
                store.getString("log:111:913:supply:online")).getJSONObject(0);
        assertEquals("CLAIMED", okEntry.getString("cat"));
        assertTrue(okEntry.getBoolean("ok"));
        JSONObject skipEntry = new JSONArray(
                store.getString("log:111:913:supply:spec")).getJSONObject(0);
        assertEquals("SKIPPED", skipEntry.getString("cat"));
        assertFalse(skipEntry.getBoolean("ok"));
        assertEquals("暂不可领", skipEntry.getString("reason"));
        JSONObject failEntry = new JSONArray(
                store.getString("log:111:913:supply:extra")).getJSONObject(0);
        assertEquals("ERROR", failEntry.getString("cat"));
        assertFalse(failEntry.getBoolean("ok"));
        // 当日子项状态机与 cat 同口径：ok=2 / skip=4 / fail=3
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date());
        assertEquals(2L, num(store, "st:" + day + ":111:913:supply:online"));
        assertEquals(4L, num(store, "st:" + day + ":111:913:supply:spec"));
        assertEquals(3L, num(store, "st:" + day + ":111:913:supply:extra"));
    }

    private static long num(StateStore store, String key) {
        Object v = store.snapshot().get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    private static String stKey(String suffix) {
        return "st:" + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date()) + ":" + suffix;
    }
}
