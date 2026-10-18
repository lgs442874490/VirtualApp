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
}
