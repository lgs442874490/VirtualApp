package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.task.TaskContext;

/**
 * 单模块执行上下文：任务上下文 + 本模块配置 + 共享服务。
 * 模块只看到这三个入口，避免直接依赖其他模块。
 */
public final class AutomationContext {

    private final TaskContext task;
    private final ModuleConfig config;
    private final AutomationServices services;

    public AutomationContext(TaskContext task, ModuleConfig config,
                             AutomationServices services) {
        this.task = task;
        this.config = config;
        this.services = services;
    }

    public TaskContext task() {
        return task;
    }

    public ModuleConfig config() {
        return config;
    }

    public AutomationServices services() {
        return services;
    }

    public AutomationContext withConfig(ModuleConfig next) {
        return new AutomationContext(task, next, services);
    }
}
