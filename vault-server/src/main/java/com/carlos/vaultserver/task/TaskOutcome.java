package com.carlos.vaultserver.task;

import java.util.LinkedHashMap;
import java.util.Map;

/** 任务执行结果（App 端 DailyResult 同构：分类设计照搬，不允许"无异常即成功"）。 */
public final class TaskOutcome {

    public enum Category {
        CLAIMED,        // 领取成功（响应有证据）
        SKIPPED,        // 合理跳过（已领/不可领/活动未开/待迁移）
        DEGRADED,       // 降级：协议形状异常（不视为成功）
        SESSION_CLOSED, // 会话中断——可重试
        ERROR           // 真异常
    }

    public final String taskId;
    public final String taskName;
    public final Category category;
    public final String message;
    /** 领取明细（与 App DailyResult.rewards 同构："普通补给：钻石×30"式，落盘到日志 detail）。 */
    public String rewards = "";
    /** 子项级结果：subId → "ok:文案"/"skip:文案"/"fail:文案"。 */
    public final Map<String, String> subResults = new LinkedHashMap<>();
    public long durationMs;

    public TaskOutcome(String taskId, String taskName, Category category, String message) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.category = category;
        this.message = message;
    }

    public String symbol() {
        switch (category) {
            case CLAIMED: return "✅";
            case SKIPPED: return "⏭";
            case DEGRADED: return "⚠️";
            case SESSION_CLOSED: return "🔌";
            default: return "❌";
        }
    }

    public boolean successLike() {
        return category == Category.CLAIMED || category == Category.SKIPPED;
    }

    @Override
    public String toString() {
        return symbol() + " " + taskName + "：" + message;
    }
}
