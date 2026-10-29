package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.monitor.TaskTrace;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块注册表：唯一性校验、依赖校验、拓扑排序、整轮执行。
 *
 * <p>约束：
 * - 所有模块的动作 id 必须全局唯一（否则日志/状态会串键）；
 * - 依赖只表达顺序，模块之间不互相调用；
 * - 有环直接拒绝启动，避免运行期随机顺序。
 */
public final class ModuleRegistry {

    private final Map<String, AutomationModule> modules = new LinkedHashMap<>();

    public ModuleRegistry register(AutomationModule module) {
        if (module == null) throw new IllegalArgumentException("module == null");
        if (modules.containsKey(module.id())) {
            throw new IllegalStateException("重复模块 id: " + module.id());
        }
        modules.put(module.id(), module);
        return this;
    }

    public void validate() {
        Map<String, String> actionOwner = new HashMap<>();
        for (AutomationModule m : modules.values()) {
            for (ActionDefinition a : m.actions()) {
                String prev = actionOwner.put(a.id, m.id());
                if (prev != null) {
                    throw new IllegalStateException("动作 id 重复: " + a.id
                            + " (" + prev + " / " + m.id() + ")");
                }
            }
            for (String dep : m.dependsOn()) {
                if (!modules.containsKey(dep)) {
                    throw new IllegalStateException("模块 " + m.id()
                            + " 依赖不存在的模块 " + dep);
                }
            }
        }
        // 环检测
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : modules.keySet()) {
            visit(id, visiting, visited);
        }
    }

    private void visit(String id, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalStateException("模块依赖存在环: " + visiting + " -> " + id);
        }
        for (String dep : modules.get(id).dependsOn()) {
            visit(dep, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    /** 依赖在前、被依赖在后；同层按注册顺序。 */
    public List<AutomationModule> ordered() {
        List<AutomationModule> out = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String id : modules.keySet()) {
            appendOrdered(id, visited, out);
        }
        return out;
    }

    private void appendOrdered(String id, Set<String> visited, List<AutomationModule> out) {
        if (!visited.add(id)) return;
        AutomationModule m = modules.get(id);
        for (String dep : m.dependsOn()) appendOrdered(dep, visited, out);
        if (!out.contains(m)) out.add(m);
    }

    public List<ModuleResult> runAll(AutomationContext base, AutomationConfig config) {
        validate();
        List<ModuleResult> results = new ArrayList<>();
        for (AutomationModule m : ordered()) {
            ModuleConfig mc = config == null
                    ? new ModuleConfig(m.id(), false, null, null)
                    : config.module(m.id());
            ModuleResult result = m.execute(base.withConfig(mc));
            results.add(result);
            // 会话已断：后续模块不可能成功，立即截断整轮（与旧 RoundRunner 同语义）。
            if (hasSessionClosed(result)) break;
        }
        return results;
    }

    private static boolean hasSessionClosed(ModuleResult result) {
        if (result == null) return false;
        for (ModuleResult.Entry e : result.entries) {
            if (e.outcome != null
                    && e.outcome.category == TaskOutcome.Category.SESSION_CLOSED) {
                return true;
            }
        }
        return false;
    }

    /** 单任务模式：只跑指定动作，不触发同模块其它动作。 */
    public ModuleResult runAction(String actionId, AutomationContext base,
                                  AutomationConfig config) {
        AutomationModule owner = moduleOfAction(actionId);
        if (owner == null) {
            ModuleResult r = new ModuleResult("legacy");
            r.add(actionId, new TaskOutcome(
                    actionId, actionId,
                    TaskOutcome.Category.ERROR,
                    "未知动作 id: " + actionId));
            return r;
        }
        ModuleConfig mc = config == null
                ? new ModuleConfig(owner.id(), false, null, null)
                : config.module(owner.id());
        ModuleResult r = new ModuleResult(owner.id());
        for (ActionDefinition a : owner.actions()) {
            if (!a.id.equals(actionId)) continue;
            if (!mc.actionEnabled(a.id, a.defaultEnabled)) {
                r.add(a.id, new TaskOutcome(
                        a.id, a.name,
                        TaskOutcome.Category.SKIPPED,
                        "配置未启用"));
                return r;
            }
            long t0 = System.currentTimeMillis();
            // 真机监控（2026-10-25）：动作前后 task.start/task.end，只观测不改变结果/控制流
            TaskContext traceTask = base == null ? null : base.task();
            TaskTrace.Context trace = TaskTrace.begin(
                    traceTask == null ? "" : traceTask.fpid,
                    traceTask == null ? 0L : traceTask.sid,
                    traceTask == null ? 0L : traceTask.roleUid,
                    a.id, a.name, traceTask == null ? "" : traceTask.trigger);
            TaskOutcome outcome = null;
            try {
                try {
                    outcome = a.execute(base.task());
                } catch (Throwable t) {
                    // 与整轮一致：会话中断/异常保留 classifyException 分类。
                    outcome = ServerTasks.classifyException(t, a.name);
                }
                outcome.durationMs = Math.max(0L, System.currentTimeMillis() - t0);
            } finally {
                trace.end(outcome);
            }
            r.add(a.id, outcome);
            return r;
        }
        r.add(actionId, new TaskOutcome(
                actionId, actionId,
                TaskOutcome.Category.ERROR,
                "动作未注册: " + actionId));
        return r;
    }

    public AutomationModule moduleOfAction(String actionId) {
        if (actionId == null) return null;
        for (AutomationModule m : modules.values()) {
            for (ActionDefinition a : m.actions()) {
                if (a.id.equals(actionId)) return m;
            }
        }
        return null;
    }

    public Set<String> allActionIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (AutomationModule m : modules.values()) {
            for (ActionDefinition a : m.actions()) ids.add(a.id);
        }
        return ids;
    }

    public AutomationModule module(String id) {
        return modules.get(id);
    }

    public Map<String, AutomationModule> modules() {
        return modules;
    }
}
