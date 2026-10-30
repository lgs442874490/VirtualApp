package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.task.ArenaGate;
import com.carlos.vaultserver.task.RoleKey;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 批次 B：整轮执行器（模块化路径）与单任务路径的竞技场独占门回归。
 *
 * <p>覆盖：ACTIVE 时本角色非白名单动作 SKIPPED 且不执行；白名单 battle_shield 照常执行；
 * 兄弟角色不受影响；单任务 {@link ModuleRegistry#runAction} 同样被挡。
 */
public class ArenaGateRunnerTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;
    private static final long UID_A = 35371154L;
    private static final long UID_B = 88280792L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;
    private final AtomicInteger calls = new AtomicInteger();
    private AutomationModule module;
    private ModuleRegistry registry;
    private AutomationConfig config;

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
        calls.set(0);
        module = new AutomationModule() {
            @Override public String id() { return "test"; }
            @Override public String displayName() { return "测试模块"; }
            @Override public ModuleCategory category() { return ModuleCategory.REWARD; }
            @Override public List<ActionDefinition> actions() {
                return List.of(
                        new ActionDefinition("probe", "普通任务", true, ctx -> {
                            calls.incrementAndGet();
                            return new TaskOutcome("probe", "普通任务",
                                    TaskOutcome.Category.CLAIMED, "ok");
                        }),
                        new ActionDefinition("battle_shield", "战斗护盾", true, ctx -> {
                            calls.incrementAndGet();
                            return new TaskOutcome("battle_shield", "战斗护盾",
                                    TaskOutcome.Category.CLAIMED, "ok");
                        }));
            }
        };
        registry = new ModuleRegistry().register(module);
        registry.validate();
        config = AutomationConfig.fromLegacy(List.of(module), null, null);
    }

    @After
    public void clean() {
        ArenaGate.clearAll();
    }

    private TaskContext ctx(long uid) {
        return new TaskContext(null, FPID, SID, store, Collections.emptyMap(), "测试", null, uid);
    }

    private AutomationContext actx(long uid) {
        return new AutomationContext(ctx(uid), config.module("test"), AutomationServices.defaults());
    }

    private static TaskOutcome outcomeOf(ModuleResult r, String actionId) {
        for (ModuleResult.Entry e : r.entries) {
            if (actionId.equals(e.actionId)) return e.outcome;
        }
        return null;
    }

    @Test
    public void fullRound_blocksNonAllowlist_butAllowlistExecutes() {
        ArenaGate.enterActive(new RoleKey(FPID, SID, UID_A));
        ModuleResult r = module.execute(actx(UID_A));

        TaskOutcome blocked = outcomeOf(r, "probe");
        assertEquals("非白名单动作整轮也让位", TaskOutcome.Category.SKIPPED, blocked.category);
        assertTrue("文案可读: " + blocked.message, blocked.message.contains("竞技场独占"));
        TaskOutcome shield = outcomeOf(r, "battle_shield");
        assertEquals("白名单 battle_shield 照常执行", TaskOutcome.Category.CLAIMED, shield.category);
        assertEquals("只有白名单动作真正执行", 1, calls.get());
    }

    @Test
    public void siblingRole_notBlocked() {
        ArenaGate.enterActive(new RoleKey(FPID, SID, UID_A));
        ModuleResult r = module.execute(actx(UID_B));
        assertEquals("兄弟角色普通任务照常", TaskOutcome.Category.CLAIMED,
                outcomeOf(r, "probe").category);
        assertEquals("兄弟角色两个动作都执行", 2, calls.get());
    }

    @Test
    public void singleTaskRunAction_blockedForGatedRole() {
        ArenaGate.enterActive(new RoleKey(FPID, SID, UID_A));
        ModuleResult r = registry.runAction("probe", actx(UID_A), config);
        assertEquals("单任务路径同样被挡", TaskOutcome.Category.SKIPPED,
                outcomeOf(r, "probe").category);
        assertEquals("被阻塞单任务不得执行", 0, calls.get());

        ModuleResult ok = registry.runAction("probe", actx(UID_B), config);
        assertEquals("兄弟角色单任务照常", TaskOutcome.Category.CLAIMED,
                outcomeOf(ok, "probe").category);
        assertEquals(1, calls.get());
    }
}
