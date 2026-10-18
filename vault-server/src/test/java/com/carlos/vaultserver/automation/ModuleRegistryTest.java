package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.task.Modules;
import com.carlos.vaultserver.task.ServerTasks;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ModuleRegistryTest {

    @Test
    public void modulesPartitionAllServerTasksExactlyOnce() {
        ModuleRegistry registry = Modules.registry();
        registry.validate();

        Set<String> expected = new LinkedHashSet<>();
        for (ServerTasks.TaskDef d : ServerTasks.all()) expected.add(d.id);

        Set<String> actual = registry.allActionIds();
        assertEquals("模块动作必须覆盖且只覆盖全部 ServerTasks", expected, actual);
        assertEquals("不允许同一动作出现在两个模块", expected.size(), actual.size());
    }

    @Test
    public void orderedRespectsDependencies() {
        ModuleRegistry r = new ModuleRegistry();
        r.register(new TestModule("a", new String[]{}));
        r.register(new TestModule("b", new String[]{"a"}));
        r.register(new TestModule("c", new String[]{"b"}));
        r.validate();
        assertEquals("a", r.ordered().get(0).id());
        assertEquals("b", r.ordered().get(1).id());
        assertEquals("c", r.ordered().get(2).id());
    }

    @Test(expected = IllegalStateException.class)
    public void cycleRejected() {
        ModuleRegistry r = new ModuleRegistry();
        r.register(new TestModule("a", new String[]{"b"}));
        r.register(new TestModule("b", new String[]{"a"}));
        r.validate();
    }

    @Test
    public void registryHasEightDomainModulesAndLegacy() {
        ModuleRegistry r = Modules.registry();
        assertNotNull(r.module("reward"));
        assertNotNull(r.module("alliance"));
        assertNotNull(r.module("bank"));
        assertNotNull(r.module("gather"));
        assertNotNull(r.module("pet"));
        assertNotNull(r.module("shop"));
        assertNotNull(r.module("medic"));
        assertNotNull(r.module("wilderness"));
        assertNotNull(r.module("legacy"));
    }

    private static final class TestModule implements AutomationModule {
        private final String id;
        private final java.util.List<String> deps;

        TestModule(String id, String[] deps) {
            this.id = id;
            this.deps = java.util.Arrays.asList(deps);
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public ModuleCategory category() { return ModuleCategory.LEGACY; }
        @Override public java.util.List<String> dependsOn() { return deps; }
        @Override public java.util.List<ActionDefinition> actions() {
            return java.util.Collections.emptyList();
        }
    }
}
