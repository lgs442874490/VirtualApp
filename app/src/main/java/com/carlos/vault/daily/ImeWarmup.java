package com.carlos.vault.daily;

/**
 * IME 预热状态机（纯逻辑，无 Android 依赖，便于单测；Android 侧接线在
 * {@code EntryDetailActivity#warmUpInputMethodThen}）。
 *
 * <p>背景（IME-Fix，vivo V2141A / Android 14 真机复现）：输入法被杀后冷启动时，
 * 容器分身内首次触发 IME 会与 VirtualApp 窗口焦点事件竞争 → 系统 ANR
 * （FocusEvent 等待 5000ms，hasFocus=false）→ 容器任务被移除，表现为点聊天输入框
 * 约 5s 后「闪退」。在宿主内先聚焦输入框把输入法拉起（键盘出现即预热完成），
 * 分身内再触发即正常。
 *
 * <p>时序：{@link #begin} → 首查 300ms（给输入法冷启动留时间）→ 键盘已弹出即
 * {@link Tick#DONE} 提前收尾；未弹出继续等，800ms 到点无论成败都以
 * {@link Tick#TIMEOUT} 兜底。<b>DONE/TIMEOUT 都继续启动分身</b>，预热失败绝不阻断；
 * 结束后再 tick 返回 null（护栏：收尾动作只执行一次）。
 */
public final class ImeWarmup {

    /** 首次检查时点（ms）：约 300ms，给输入法进程冷启动留时间 */
    public static final long FIRST_CHECK_MS = 300;
    /** 超时兜底时点（ms）：到点无论键盘是否弹出都收尾并继续启动 */
    public static final long TIMEOUT_MS = 800;

    /** 预热会话状态：空闲 / 预热中 / 已结束（完成、超时或被取消） */
    public enum Phase { IDLE, WARMING, FINISHED }

    /** 单次检查判定：继续等 / 键盘已弹出（提前收尾）/ 超时兜底收尾 */
    public enum Tick { KEEP_WAITING, DONE, TIMEOUT }

    private Phase phase = Phase.IDLE;

    /** 当前状态（空闲 / 预热中 / 已结束）。 */
    public synchronized Phase phase() {
        return phase;
    }

    /** 开始一次预热；已在预热中返回 false（防重入：预热期间重复点击不另起会话）。 */
    public synchronized boolean begin() {
        if (phase == Phase.WARMING) return false;
        phase = Phase.WARMING;
        return true;
    }

    /**
     * 时钟推进：elapsedMs = 距本次 begin 的毫秒数，keyboardVisible = 键盘当前是否已弹出。
     * 会话不在预热中（已收尾/被取消）返回 null；否则返回本次判定。
     */
    public synchronized Tick tick(long elapsedMs, boolean keyboardVisible) {
        if (phase != Phase.WARMING) return null;
        if (keyboardVisible) {
            phase = Phase.FINISHED;
            return Tick.DONE;
        }
        if (elapsedMs >= TIMEOUT_MS) {
            phase = Phase.FINISHED;
            return Tick.TIMEOUT;
        }
        return Tick.KEEP_WAITING;
    }

    /** 是否预热中（供防重入判断与 stray 回调短路）。 */
    public synchronized boolean warming() {
        return phase == Phase.WARMING;
    }

    /** 强制结束（Activity 销毁/页面 finishing）；返回 true 表示确有进行中的会话被终止。 */
    public synchronized boolean abort() {
        boolean was = phase == Phase.WARMING;
        phase = Phase.FINISHED;
        return was;
    }

    /** 距超时点还剩多久（postDelayed 用）；已到点返回 0。 */
    public static long remainingToTimeout(long elapsedMs) {
        return Math.max(0, TIMEOUT_MS - elapsedMs);
    }
}
