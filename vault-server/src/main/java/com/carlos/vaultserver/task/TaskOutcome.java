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
    /**
     * 业务态唤醒时刻（C3，epoch millis；0=不覆盖节奏间隔，沿用 {@link TaskCadence} 的固定间隔）。
     *
     * <p>1:1 复刻网页版 {@code TaskDecision.SleepUntil}：任务自己知道"下次该跑的时刻"
     * （如情报派队后等 15 分钟结果、巨熊开赛前 60 秒/上车 30 秒对账），由
     * {@link RoundRunner#applyResult} 在<b>单任务模式</b>下落盘到
     * {@code srv:cad:{fpid}:{sid}:{fn}}；整轮模式不写（避免整轮覆盖单任务节奏）。
     * 必须在 {@code [now, now+24h]} 内，超界由 runner 丢弃（防止任务算错把节奏"冻"住）。
     */
    public long nextWakeAtMillis;

    public TaskOutcome(String taskId, String taskName, Category category, String message) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.category = category;
        this.message = message;
    }

    /**
     * 链式设置业务态唤醒时刻（epoch millis）：{@code return Exec.result(...).wakeAt(t)}；
     * 传 0/负数 = 不覆盖（保持兼容）。
     */
    public TaskOutcome wakeAt(long epochMillis) {
        this.nextWakeAtMillis = epochMillis;
        return this;
    }

    /**
     * 链式设置"从现在起 delayMillis 后唤醒"（便捷写法；任务侧需自行避免负数）。
     */
    public TaskOutcome wakeIn(long delayMillis) {
        this.nextWakeAtMillis = delayMillis > 0L ? System.currentTimeMillis() + delayMillis : 0L;
        return this;
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
