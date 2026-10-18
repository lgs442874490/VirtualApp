package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.CaptureTasksTest;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T3 门禁：动作耗时必须真实写入 TaskOutcome.durationMs。
 *
 * <p>回归背景（G6）：模块化整轮曾不写 durationMs，App 运行日志耗时恒为 0。
 */
public class ActionDurationTest {

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

    private static ActionDefinition slowAction() {
        return new ActionDefinition("timed", "计时动作", true, tc -> {
            try {
                Thread.sleep(5L);   // 真实耗时，不伪造
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new TaskOutcome("timed", "计时动作", TaskOutcome.Category.CLAIMED, "ok");
        });
    }

    private static AutomationContext context(AutomationConfig config, ModuleRegistry registry,
                                             TaskContext task) {
        return new AutomationContext(task, config.module("a"), AutomationServices.defaults());
    }

    private static AutomationConfig config(ModuleRegistry registry, boolean enabled) {
        Map<String, Boolean> roleConfig = new LinkedHashMap<>();
        roleConfig.put("timed", enabled);
        return AutomationConfig.fromLegacy(registry.modules().values(), roleConfig, null);
    }

    @Test
    public void moduleExecuteWritesRealDuration() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a", slowAction()));
        registry.validate();
        AutomationConfig config = config(registry, true);

        ModuleResult result = registry.runAll(context(config, registry, task), config).get(0);

        TaskOutcome outcome = result.entries.get(0).outcome;
        assertNotNull(outcome);
        assertTrue("真实执行的动作 durationMs 应 > 0，实际=" + outcome.durationMs,
                outcome.durationMs > 0L);
    }

    @Test
    public void runActionWritesRealDuration() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a", slowAction()));
        registry.validate();
        AutomationConfig config = config(registry, true);

        ModuleResult result = registry.runAction("timed",
                context(config, registry, task), config);

        TaskOutcome outcome = result.entries.get(0).outcome;
        assertNotNull(outcome);
        assertTrue("单任务执行也应计入真实耗时，实际=" + outcome.durationMs,
                outcome.durationMs > 0L);
    }

    @Test
    public void disabledActionIsNotTimed() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("a", slowAction()));
        registry.validate();
        AutomationConfig config = config(registry, false);

        ModuleResult result = registry.runAction("timed",
                context(config, registry, task), config);

        TaskOutcome outcome = result.entries.get(0).outcome;
        assertNotNull(outcome);
        assertEquals(TaskOutcome.Category.SKIPPED, outcome.category);
        assertEquals("未执行的动作不应有耗时", 0L, outcome.durationMs);
    }
}
