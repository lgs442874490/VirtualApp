package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.task.TaskContext;
import com.carlos.vaultserver.task.TaskOutcome;

/**
 * 模块内的最小可执行动作。
 *
 * <p>动作 id 必须复用 App/服务端现有的任务 id（如 gather / medic / shop），
 * 这样模块化重构后日志键、st: 状态键、App 功能详情都不需要迁移。
 * 新增功能如果没有旧 id，使用 {@code <module>.<action>} 形式并保持唯一。
 */
public final class ActionDefinition {

    public interface Executor {
        TaskOutcome run(TaskContext context) throws Exception;
    }

    public final String id;
    public final String name;
    public final boolean defaultEnabled;
    private final Executor executor;

    public ActionDefinition(String id, String name, boolean defaultEnabled, Executor executor) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("action id empty");
        this.id = id;
        this.name = name == null ? id : name;
        this.defaultEnabled = defaultEnabled;
        this.executor = executor;
    }

    public TaskOutcome execute(TaskContext context) throws Exception {
        return executor.run(context);
    }
}
