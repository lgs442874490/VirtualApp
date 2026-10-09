package com.carlos.vault.daily;

/**
 * 日常任务执行结果。分类设计直接对应原版失效根因分析：
 * 不允许"无异常即成功"——成功必须有响应证据，跳过必须带原因。
 */
public class DailyResult {

    public enum Category {
        CLAIMED,        // 领取成功（响应有证据）
        SKIPPED,        // 合理跳过（已领/不可领/活动未开/无可领对象）
        DEGRADED,       // 降级：协议形状异常/未知协议/缺预期字段（原版假成功的根源，单列）
        SESSION_CLOSED, // 会话中断（被顶号/断线）——可重试
        ERROR           // 真异常
    }

    public final String taskId;
    public final String taskName;
    public final Category category;
    public final String message;
    /** B表子项级结果：subId → "ok:文案"/"skip:文案"/"fail:文案"（执行器填充，落盘到日志表） */
    public final java.util.Map<String, String> subResults = new java.util.LinkedHashMap<>();
    /** 执行信息（非构造必需，由执行链路填充后随日志落盘；对标原版 daily_execution_log 同名字段）。 */
    public long durationMs;   // 执行器耗时；0=未进入执行（登录失败/配置未启用）
    public int attempt;       // 尝试序号；0=未执行即跳过，1=首次执行

    public DailyResult(String taskId, String taskName, Category category, String message) {
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

    public boolean retryable() {
        return category == Category.SESSION_CLOSED;
    }

    @Override
    public String toString() {
        return symbol() + " " + taskName + "：" + message;
    }
}
