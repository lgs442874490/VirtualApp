package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.monitor.TaskTrace;
import com.carlos.vaultserver.task.ServerTasks;
import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

import java.util.Collections;
import java.util.List;

/**
 * 自动化域模块。
 *
 * <p>约束：
 * 1) 模块之间不直接调用；跨域能力通过 {@link AutomationServices} 注入；
 * 2) 动作 id 复用旧 fnId，保证日志/状态/UI 兼容；
 * 3) 模块只负责“编排 + 业务规则”，协议请求仍在既有执行器或注入的服务中。
 */
public interface AutomationModule {

    String id();

    String displayName();

    ModuleCategory category();

    /** 依赖的模块 id（只用于排序/校验，不用于直接调用）。 */
    default List<String> dependsOn() {
        return Collections.emptyList();
    }

    List<ActionDefinition> actions();

    /**
     * 默认顺序执行本模块动作。复杂模块（采集/商店/军医所/野外）可覆盖，
     * 但仍应把结果按旧 fnId 写入 ModuleResult。
     */
    default ModuleResult execute(AutomationContext ctx) {
        ModuleResult result = new ModuleResult(id());
        for (ActionDefinition action : actions()) {
            boolean enabled = ctx.config().actionEnabled(action.id, action.defaultEnabled);
            if (!enabled) {
                result.add(action.id, new TaskOutcome(action.id, action.name,
                        TaskOutcome.Category.SKIPPED, "配置未启用"));
                continue;
            }
            TaskOutcome outcome = null;
            long t0 = System.currentTimeMillis();
            // 真机监控（2026-10-25）：每个 action 前后 task.start/task.end，只观测不改变结果/控制流
            TaskContext traceTask = ctx.task();
            TaskTrace.Context trace = TaskTrace.begin(traceTask.fpid, traceTask.sid,
                    traceTask.roleUid, action.id, action.name, traceTask.trigger);
            try {
                try {
                    outcome = action.execute(traceTask);
                } catch (Throwable t) {
                    // 保留旧 RoundRunner 的分类口径：会话中断不能被吞成普通 ERROR，
                    // 否则整轮无法按“断线可重试”的 SESSION_CLOSED 语义截断并落 st=1。
                    outcome = ServerTasks.classifyException(t, action.name);
                }
                outcome.durationMs = Math.max(0L, System.currentTimeMillis() - t0);
            } finally {
                trace.end(outcome);
            }
            result.add(action.id, outcome);
            if (outcome.category == TaskOutcome.Category.SESSION_CLOSED) break;
        }
        return result;
    }
}
