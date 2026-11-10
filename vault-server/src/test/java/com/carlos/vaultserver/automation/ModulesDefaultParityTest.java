package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.task.Modules;
import com.carlos.vaultserver.task.ServerTasks;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T1 门禁：模块动作的默认开关必须与 ServerTasks 任务目录（唯一事实源）一致。
 *
 * <p>回归背景（G1）：域模块曾把 22 个动作的 defaultEnabled 全部硬编码 true，
 * 覆盖了目录中 bank/gather/intel/hunt/shop/alliance_shop 的默认 false，
 * 导致 App 未勾选的动作在服务器整轮中被执行。
 */
public class ModulesDefaultParityTest {

    @Test
    public void everyActionDefaultMatchesCatalog() {
        ModuleRegistry registry = Modules.registry();
        int checked = 0;
        for (AutomationModule m : registry.modules().values()) {
            for (ActionDefinition a : m.actions()) {
                ServerTasks.TaskDef def = ServerTasks.byId(a.id);
                assertNotNull("模块动作不在任务目录: " + m.id() + "." + a.id, def);
                assertEquals("默认开关与目录不一致: " + m.id() + "." + a.id,
                        def.enabledByDefault, a.defaultEnabled);
                checked++;
            }
        }
        assertEquals("模块动作数应覆盖全部任务目录", ServerTasks.all().size(), checked);
    }

    @Test
    public void catalogDefaultOffActionsStayOffInModules() {
        Set<String> expectedOff = new LinkedHashSet<>();
        for (ServerTasks.TaskDef d : ServerTasks.all()) {
            if (!d.enabledByDefault) expectedOff.add(d.id);
        }
        // 2026-11-11 默认开启策略：任务级全部默认开，目录默认关闭集合应为空。
        assertTrue("目录默认关闭集合应为空（策略：任务级全开）", expectedOff.isEmpty());
        for (String id : expectedOff) {
            ActionDefinition a = actionOf(Modules.registry(), id);
            assertNotNull("目录任务未进入模块: " + id, a);
            assertFalse("模块不得把目录默认关闭的 " + id + " 打开", a.defaultEnabled);
        }
    }

    private static ActionDefinition actionOf(ModuleRegistry registry, String id) {
        for (AutomationModule m : registry.modules().values()) {
            for (ActionDefinition a : m.actions()) {
                if (a.id.equals(id)) return a;
            }
        }
        return null;
    }
}
