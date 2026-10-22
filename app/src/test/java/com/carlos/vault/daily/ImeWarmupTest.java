package com.carlos.vault.daily;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * IME 预热状态机（IME-Fix）纯逻辑单测：防重入 / 键盘弹出提前收尾 / 800ms 超时兜底 /
 * 收尾只执行一次的护栏 / 取消语义 / 排期辅助。不依赖真机与 Android 框架。
 */
public class ImeWarmupTest {

    // ---------- 常量契约（真机取证口径：首查 300ms，超时兜底 800ms） ----------

    @Test
    public void constants_firstCheck300_timeout800() {
        assertEquals(300L, ImeWarmup.FIRST_CHECK_MS);
        assertEquals(800L, ImeWarmup.TIMEOUT_MS);
    }

    // ---------- begin：防重入 ----------

    @Test
    public void begin_fromIdle_returnsTrue() {
        ImeWarmup w = new ImeWarmup();
        assertTrue(w.begin());
        assertEquals(ImeWarmup.Phase.WARMING, w.phase());
        assertTrue(w.warming());
    }

    @Test
    public void begin_whileWarming_secondRejected() {
        ImeWarmup w = new ImeWarmup();
        assertTrue(w.begin());
        // 预热中再次点击启动 → 不另起会话（防重入）
        assertFalse(w.begin());
        assertEquals(ImeWarmup.Phase.WARMING, w.phase());
    }

    @Test
    public void begin_afterFinish_startsNewSession() {
        ImeWarmup w = new ImeWarmup();
        assertTrue(w.begin());
        w.tick(ImeWarmup.TIMEOUT_MS, false);
        assertEquals(ImeWarmup.Phase.FINISHED, w.phase());
        // 上一次启动的预热已结束：下一次启动可以再预热（不永久卡死）
        assertTrue(w.begin());
        assertEquals(ImeWarmup.Phase.WARMING, w.phase());
    }

    // ---------- tick：键盘弹出提前收尾 ----------

    @Test
    public void tick_keyboardVisibleAtFirstCheck_done() {
        ImeWarmup w = new ImeWarmup();
        w.begin();
        // 首查 300ms 时键盘已弹出 → 预热完成，可提前收尾
        assertEquals(ImeWarmup.Tick.DONE, w.tick(ImeWarmup.FIRST_CHECK_MS, true));
        assertEquals(ImeWarmup.Phase.FINISHED, w.phase());
    }

    @Test
    public void tick_keyboardVisibleExactlyAtDeadline_done() {
        ImeWarmup w = new ImeWarmup();
        w.begin();
        w.tick(ImeWarmup.FIRST_CHECK_MS, false);
        // 卡点弹出：键盘已出现优先于超时（体验口径：确有键盘就不算失败）
        assertEquals(ImeWarmup.Tick.DONE, w.tick(ImeWarmup.TIMEOUT_MS, true));
    }

    // ---------- tick：超时兜底（键盘始终不弹也必须继续启动） ----------

    @Test
    public void tick_hiddenAtFirstCheck_keepsWaiting() {
        ImeWarmup w = new ImeWarmup();
        w.begin();
        assertEquals(ImeWarmup.Tick.KEEP_WAITING, w.tick(ImeWarmup.FIRST_CHECK_MS, false));
        assertEquals(ImeWarmup.Phase.WARMING, w.phase());
    }

    @Test
    public void tick_hiddenAtDeadline_timeout() {
        ImeWarmup w = new ImeWarmup();
        w.begin();
        w.tick(ImeWarmup.FIRST_CHECK_MS, false);
        assertEquals(ImeWarmup.Tick.TIMEOUT, w.tick(ImeWarmup.TIMEOUT_MS, false));
        assertEquals(ImeWarmup.Phase.FINISHED, w.phase());
    }

    @Test
    public void tick_hiddenAfterDeadline_stillTimeout() {
        // 主线程卡顿错过 800ms 点，迟到的检查也按超时兜底
        ImeWarmup w = new ImeWarmup();
        w.begin();
        assertEquals(ImeWarmup.Tick.TIMEOUT, w.tick(1500L, false));
    }

    // ---------- 收尾只执行一次的护栏 ----------

    @Test
    public void tick_afterFinished_returnsNull() {
        ImeWarmup w = new ImeWarmup();
        w.begin();
        w.tick(ImeWarmup.TIMEOUT_MS, false);
        // FINISHED 后再来的 stray 回调（无论参数）都不再触发判定/收尾
        assertNull(w.tick(2000L, true));
        assertNull(w.tick(2000L, false));
        assertNull(w.tick(0L, false));
    }

    @Test
    public void tick_whenIdle_returnsNull() {
        ImeWarmup w = new ImeWarmup();
        assertNull(w.tick(0L, true));
    }

    // ---------- 完整时序（模拟 Activity 接线：begin → 300ms 查 → 800ms 查） ----------

    @Test
    public void sequence_warmIme_showsKeyboard_thenLaunchProceeds() {
        ImeWarmup w = new ImeWarmup();
        assertTrue(w.begin());
        assertEquals(ImeWarmup.Tick.KEEP_WAITING, w.tick(300L, false));
        assertEquals(ImeWarmup.Tick.DONE, w.tick(800L, true));
    }

    @Test
    public void sequence_coldImeNeverShows_timeoutStillProceeds() {
        // 关键兜底：输入法始终没弹出（如 ROM 拦截 showSoftInput）→ 超时也继续启动分身
        ImeWarmup w = new ImeWarmup();
        assertTrue(w.begin());
        assertEquals(ImeWarmup.Tick.KEEP_WAITING, w.tick(300L, false));
        assertEquals(ImeWarmup.Tick.TIMEOUT, w.tick(800L, false));
    }

    // ---------- abort：页面销毁 ----------

    @Test
    public void abort_duringWarming_reportsTrue_andStopsTicks() {
        ImeWarmup w = new ImeWarmup();
        w.begin();
        assertTrue(w.abort());
        assertEquals(ImeWarmup.Phase.FINISHED, w.phase());
        assertNull(w.tick(1000L, true));   // 销毁后回调不再触发收尾/启动
        assertFalse(w.warming());
    }

    @Test
    public void abort_whenIdleOrFinished_reportsFalse() {
        ImeWarmup w = new ImeWarmup();
        assertFalse(w.abort());            // 空闲：无可取消
        w.begin();
        w.tick(800L, false);               // 已超时收尾
        assertFalse(w.abort());            // 已结束：不重复报「被终止」
    }

    // ---------- 排期辅助：下一次 postDelayed 的延时 ----------

    @Test
    public void remainingToTimeout_scheduleMath() {
        assertEquals(800L, ImeWarmup.remainingToTimeout(0L));
        assertEquals(500L, ImeWarmup.remainingToTimeout(300L));   // 首查未弹出 → 再等 500ms
        assertEquals(1L, ImeWarmup.remainingToTimeout(799L));
        assertEquals(0L, ImeWarmup.remainingToTimeout(800L));     // 已到点：立刻查（超时）
        assertEquals(0L, ImeWarmup.remainingToTimeout(1200L));    // 迟到 tick：不为负
    }
}
