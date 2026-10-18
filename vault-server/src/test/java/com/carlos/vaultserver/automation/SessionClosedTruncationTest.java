package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T2 门禁：SESSION_CLOSED 必须保留分类并截断整轮。
 *
 * <p>回归背景（G2）：模块化执行曾把未捕获异常一律吞成 ERROR，且 SESSION_CLOSED
 * 只 break 模块内部循环、后续模块继续执行（旧 RoundRunner 会中止整轮）。
 */
public class SessionClosedTruncationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private TaskContext task;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        task = new TaskContext(new CaptureTasksTest.FakeEngine(), "111", 913L, store, null, "测试");
    }

    private static AutomationModule module(String id, ActionDefinition... actions) {
        return new AutomationModule() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public ModuleCategory category() { return ModuleCategory.LEGACY; }
            @Override public List<ActionDefinition> actions() { return Arrays.asList(actions); }
        };
    }

    private static ActionDefinition action(String id, ActionDefinition.Executor executor) {
        return new ActionDefinition(id, id, true, executor);
    }

    private static AutomationContext context(AutomationConfig config, TaskContext task) {
        return new AutomationContext(task, config.module("a"), AutomationServices.defaults());
    }

    @Test
    public void sessionClosedStopsSubsequentModules() {
        AtomicBoolean bExecuted = new AtomicBoolean(false);
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a", action("a1", tc -> new TaskOutcome(
                "a1", "a1", TaskOutcome.Category.SESSION_CLOSED, "会话中断"))));
        registry.register(module("b", action("b1", tc -> {
            bExecuted.set(true);
            return new TaskOutcome("b1", "b1", TaskOutcome.Category.CLAIMED, "ok");
        })));
        registry.validate();

        Map<String, Boolean> roleConfig = new LinkedHashMap<>();
        roleConfig.put("a1", true);
        roleConfig.put("b1", true);
        AutomationConfig config = AutomationConfig.fromLegacy(registry.modules().values(), roleConfig, null);

        List<ModuleResult> results = registry.runAll(context(config, task), config);

        assertEquals("遇到 SESSION_CLOSED 后不应继续执行后续模块", 1, results.size());
        assertFalse("模块 b 不得执行", bExecuted.get());
    }

    @Test
    public void sessionClosedStopsRemainingActionsInSameModule() {
        AtomicBoolean a2Executed = new AtomicBoolean(false);
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a",
                action("a1", tc -> new TaskOutcome(
                        "a1", "a1", TaskOutcome.Category.SESSION_CLOSED, "会话中断")),
                action("a2", tc -> {
                    a2Executed.set(true);
                    return new TaskOutcome("a2", "a2", TaskOutcome.Category.CLAIMED, "ok");
                })));
        registry.validate();

        Map<String, Boolean> roleConfig = new LinkedHashMap<>();
        roleConfig.put("a1", true);
        roleConfig.put("a2", true);
        AutomationConfig config = AutomationConfig.fromLegacy(registry.modules().values(), roleConfig, null);

        ModuleResult result = registry.runAll(context(config, task), config).get(0);

        assertEquals("同模块内 SESSION_CLOSED 后应停止后续动作", 1, result.entries.size());
        assertFalse(a2Executed.get());
    }

    @Test
    public void thrownSessionExceptionKeepsSessionClosedCategory() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a",
                action("boom", tc -> {
                    throw new RuntimeException("boom");
                }),
                action("offline", tc -> {
                    throw new IllegalStateException("连接已关闭");
                })));
        registry.validate();

        Map<String, Boolean> roleConfig = new LinkedHashMap<>();
        roleConfig.put("boom", true);
        roleConfig.put("offline", true);
        AutomationConfig config = AutomationConfig.fromLegacy(registry.modules().values(), roleConfig, null);

        ModuleResult result = registry.runAll(context(config, task), config).get(0);

        assertEquals(2, result.entries.size());
        assertEquals(TaskOutcome.Category.ERROR, result.entries.get(0).outcome.category);
        assertEquals("会话中断异常必须保留 SESSION_CLOSED 分类，不能被吞成 ERROR",
                TaskOutcome.Category.SESSION_CLOSED, result.entries.get(1).outcome.category);
        assertTrue(result.entries.get(1).outcome.message.contains("会话中断"));
    }

    @Test
    public void runActionAlsoKeepsSessionClosedCategory() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a", action("offline", tc -> {
            throw new IllegalStateException("Socket closed");
        })));
        registry.validate();

        Map<String, Boolean> roleConfig = new LinkedHashMap<>();
        roleConfig.put("offline", true);
        AutomationConfig config = AutomationConfig.fromLegacy(registry.modules().values(), roleConfig, null);

        ModuleResult result = registry.runAction("offline", context(config, task), config);

        assertEquals(1, result.entries.size());
        assertNotNull(result.entries.get(0).outcome);
        assertEquals(TaskOutcome.Category.SESSION_CLOSED, result.entries.get(0).outcome.category);
    }

    @Test
    public void classifyExceptionMarkerConsistency() {
        // 保护性断言：本测试用到的断线关键字仍在 ServerTasks 分类表内（防止关键字漂移后测试变假绿）。
        TaskOutcome o = ServerTasks.classifyException(new IllegalStateException("连接已关闭"), "x");
        assertEquals(TaskOutcome.Category.SESSION_CLOSED, o.category);
    }

    // ---------- P1-A：cause 链追溯（ZCode 真机回归：杀出口整轮 19 任务误判 ERROR） ----------

    @Test
    public void classifyException_wrappedConnectionClosed_isSessionClosed() {
        // 真实形态（GameProtocolClient.request → ExecutionException 分支）：
        // IllegalStateException("协议调用失败: req_x", cause=IllegalStateException("连接已关闭"))
        TaskOutcome o = ServerTasks.classifyException(
                new IllegalStateException("协议调用失败: req_x",
                        new IllegalStateException("连接已关闭")), "任务x");
        assertEquals("包装在 cause 里的真实断线必须判 SESSION_CLOSED",
                TaskOutcome.Category.SESSION_CLOSED, o.category);
        assertTrue(o.message.contains("会话中断"));
    }

    @Test
    public void classifyException_wrappedTimeout_isSessionClosed() {
        // timeout 包装形态：协议调用失败 ← 等待协议响应超时 ← TimeoutException
        TaskOutcome o = ServerTasks.classifyException(
                new IllegalStateException("协议调用失败: req_x",
                        new IllegalStateException("等待协议响应超时: req_x",
                                new java.util.concurrent.TimeoutException("50ms"))), "任务x");
        assertEquals(TaskOutcome.Category.SESSION_CLOSED, o.category);
    }

    @Test
    public void classifyException_deepCauseChain_fiveHops() {
        // 深层包装（顶层 + 5 层 cause），断线关键字落在最底层
        Throwable deep = new IllegalStateException("连接已关闭");
        for (int i = 1; i <= 5; i++) {
            deep = new IllegalStateException("包装层" + i, deep);
        }
        TaskOutcome o = ServerTasks.classifyException(deep, "任务x");
        assertEquals("5 层 cause 内的断线关键字必须能追溯到",
                TaskOutcome.Category.SESSION_CLOSED, o.category);
    }

    @Test
    public void classifyException_beyondCauseCap_fallsBackToError() {
        // 超过追溯上限（第 6 层 cause）不再深挖：封顶防爆栈/防环，行为可预期
        Throwable deep = new IllegalStateException("连接已关闭");
        for (int i = 1; i <= 6; i++) {
            deep = new IllegalStateException("包装层" + i, deep);
        }
        TaskOutcome o = ServerTasks.classifyException(deep, "任务x");
        assertEquals(TaskOutcome.Category.ERROR, o.category);
    }

    @Test
    public void classifyException_mutualCauseCycle_terminates() {
        // 互环 cause（a→b→a，JDK 禁自环但不禁互环）不得死循环：上限兜底
        IllegalStateException a = new IllegalStateException("异常A");
        IllegalStateException b = new IllegalStateException("异常B", a);
        a.initCause(b);
        TaskOutcome o = ServerTasks.classifyException(a, "任务x");
        assertEquals(TaskOutcome.Category.ERROR, o.category);
    }

    @Test
    public void classifyException_topLevelMark_messageUnchanged() {
        // 顶层即命中：分类与文案与旧行为完全一致（无 cause 时 chain==原 msg）
        TaskOutcome o = ServerTasks.classifyException(new IllegalStateException("连接已关闭"), "任务x");
        assertEquals(TaskOutcome.Category.SESSION_CLOSED, o.category);
        assertEquals("会话中断（可重试）：连接已关闭", o.message);
    }

    @Test
    public void classifyException_nonSessionCause_stillError_withChainShown() {
        // 非断线原因：仍判 ERROR，但 cause 链要进展示（排障需要）
        TaskOutcome o = ServerTasks.classifyException(
                new IllegalStateException("协议调用失败: req_x",
                        new IllegalArgumentException("参数不合法")), "任务x");
        assertEquals(TaskOutcome.Category.ERROR, o.category);
        assertTrue(o.message.contains("协议调用失败"));
        assertTrue(o.message.contains("参数不合法"));
    }

    // ---------- P1-A 二轮：connect() 拒绝链 + 网络异常类型白名单 + isSessionClosed 复用入口 ----------

    @Test
    public void classifyException_connectRefusedChain_isSessionClosed() {
        // 形态 A（ZCode 复验 §5.4）：connect() 路径抛
        // IllegalStateException("连接游戏服务器失败", cause=ConnectException("Connection refused"))。
        // "连接失败"不是"连接游戏服务器失败"的连续子串，round-1 漏判。
        TaskOutcome o = ServerTasks.classifyException(
                new IllegalStateException("连接游戏服务器失败",
                        new java.net.ConnectException("Connection refused")), "任务x");
        assertEquals("connect() 拒绝链必须判 SESSION_CLOSED",
                TaskOutcome.Category.SESSION_CLOSED, o.category);
        // 双重保险：类型白名单单独也能命中（文案全无关键字时）
        assertTrue(ServerTasks.isSessionClosed(
                new IllegalStateException("连接游戏服务器失败",
                        new java.net.ConnectException("Connection refused"))));
        assertTrue(ServerTasks.isSessionClosed(
                new IllegalStateException("本地化文案无关键字",
                        new java.net.ConnectException("Connection refused"))));
    }

    @Test
    public void classifyException_networkCauseTypes_isSessionClosed() {
        // 形态 B 变体：cause 为网络层异常、文案无任何断线关键字 → 靠类型白名单兜底
        for (Throwable net : new Throwable[]{
                new java.net.SocketException("Connection reset"),
                new java.net.SocketTimeoutException("Read timed out"),
                new java.io.EOFException("EOF while reading"),
                new java.nio.channels.ClosedChannelException()}) {
            TaskOutcome o = ServerTasks.classifyException(
                    new IllegalStateException("协议调用失败: req_x", net), "任务x");
            assertEquals("cause=" + net.getClass().getSimpleName() + " 必须判 SESSION_CLOSED",
                    TaskOutcome.Category.SESSION_CLOSED, o.category);
        }
    }

    @Test
    public void isSessionClosedString_rejectsBusinessTexts() {
        // 形态 d（文案侧）：业务失败文案不得误判会话中断（聚合路径直接吃文案）
        assertFalse(ServerTasks.isSessionClosed("结算码6158"));
        assertFalse(ServerTasks.isSessionClosed("参数不合法"));
        assertFalse(ServerTasks.isSessionClosed("免费护盾冷却中"));
        assertFalse(ServerTasks.isSessionClosed((String) null));
        assertFalse(ServerTasks.isSessionClosed(""));
        // P1-A 二轮增补词命中确认（防止关键字漂移后测试变假绿）
        assertTrue(ServerTasks.isSessionClosed("连接游戏服务器失败"));
        assertTrue(ServerTasks.isSessionClosed("Connection refused"));
        assertTrue(ServerTasks.isSessionClosed("Connection reset"));
        assertTrue(ServerTasks.isSessionClosed("Broken pipe"));
    }
}
