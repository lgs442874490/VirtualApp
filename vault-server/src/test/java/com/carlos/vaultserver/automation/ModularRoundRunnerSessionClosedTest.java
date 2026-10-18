package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vaultserver.task.ModularRoundRunner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T2 补强：ModularRoundRunner 断线截断 + 不消耗当天整轮的集成回归。
 *
 * <p>回归背景（G2）：模块化整轮第一动作断线时，必须
 * ①保留 SESSION_CLOSED 分类并落 st=1；②截断后续动作/模块；③不写 srv:lastrun（允许重试）；
 * ④summary 保留“会话中断”可观测性；⑤断线后不再做采集收尾（本测试引擎会在任何协议请求时抛错，
 * 若收尾仍发请求会额外失败——同时验证 runner 的 sessionClosed 分支）。
 */
public class ModularRoundRunnerSessionClosedTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private VaultStore.Account acc;

    /**
     * 断线引擎：任何协议请求都抛“连接已关闭”（命中 ServerTasks 的 SESSION_CLOSED 关键字）；
     * 通知类接口返回空，collectNotifies 不做事。
     */
    private static final class SessionClosedEngine extends WjdrEngine {
        SessionClosedEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
            // 不采集通知：保持测试确定、不触网
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return null;
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            return null;
        }

        @Override
        public com.carlos.vault.wjdr.protocol.GameMessage request(
                String proto, Map<String, Object> args) {
            throw new IllegalStateException("连接已关闭");
        }
    }

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        acc = new VaultStore.Account("111");
        acc.token = "tk";
        Map<String, Boolean> cfg = new LinkedHashMap<>();
        cfg.put("login_rewards", true);    // reward 模块首个动作：断线即发生在这里
        cfg.put("mail", true);             // 同模块后续动作：必须被截断
        cfg.put("alliance_chest", true);   // 后续模块动作：必须被截断
        acc.roleConfig.put("913", cfg);
    }

    @Test
    public void sessionClosedTruncatesRoundAndDoesNotConsumeLastRun() {
        String summary = ModularRoundRunner.runRound(
                new SessionClosedEngine(), acc, 913L, store, null, null, "测试");

        // ① 首个动作 SESSION_CLOSED → st=1
        String day = Beijing.fmt("yyyy-MM-dd").format(new Date());
        Object st = store.snapshot().get("st:" + day + ":111:913:login_rewards");
        assertNotNull("首个动作应落当日状态键", st);
        assertEquals("SESSION_CLOSED 应落 st=1", 1L, ((Number) st).longValue());

        org.json.JSONArray logs = new org.json.JSONArray(
                store.getString("log:111:913:login_rewards"));
        JSONObject entry = logs.getJSONObject(0);
        assertEquals("日志分类应为 SESSION_CLOSED",
                "SESSION_CLOSED", entry.getString("cat"));
        assertTrue("日志原因应含“会话中断”",
                entry.getString("reason").contains("会话中断"));

        // ② 断线轮次不得写 srv:lastrun（允许同一窗口重试）
        assertNull("断线轮次不得写 srv:lastrun:111",
                store.snapshot().get("srv:lastrun:111"));

        // ③ summary 保留可观测性
        assertTrue("返回 summary 应含“会话中断”", summary.contains("会话中断"));
        assertNotNull("应落 summary:111", store.getString("summary:111"));
        assertTrue("落盘 summary 应含“会话中断”",
                store.getString("summary:111").contains("会话中断"));

        // ④ 截断：同模块后续动作与后续模块均不得执行（无 log 键）
        assertNull("同模块后续动作 mail 必须被截断",
                store.getString("log:111:913:mail"));
        assertNull("后续模块动作 alliance_chest 必须被截断",
                store.getString("log:111:913:alliance_chest"));
    }
}
