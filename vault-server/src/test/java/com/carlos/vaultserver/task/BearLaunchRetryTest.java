package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次5 · 巨熊行动今晚窗口增强纯函数单测（task-16）：
 * <ul>
 *   <li>5204 降档立即重试 1 次（620→320、1820→620，最低档/已用过/窗口剩余不足→不重试）；</li>
 *   <li>401/2878/403/415 延时 60s 重试 1 次（已用过/窗口剩余不足→不重试；其它码不重试）；</li>
 *   <li>编队配置（hero_ids/squad_index）解析与来源文案；</li>
 *   <li>20:50-21:00 预热刷新节流/文案；发车/对账 mod 文案可读性；</li>
 *   <li>重试/预热唤醒时刻与状态跨场次归一。</li>
 * </ul>
 *
 * <p>不连真协议；时间常量复用生产实测值（57259770/724 reserve=1792760400 = 2026-10-23 21:00 北京，周五）。
 */
public class BearLaunchRetryTest {

    /** 2026-10-23 21:00 北京时间（活动开始，生产实测 reserve）。 */
    private static final long START = 1792760400L;
    /** 20:50（开赛前 10 分钟，准备窗开始）。 */
    private static final long PREP_START = 1792759800L;
    /** 20:00（准备窗之前）。 */
    private static final long T_2000 = 1792756800L;
    /** 21:10（活动进行中）。 */
    private static final long T_MID = 1792761000L;
    /** 21:30（活动结束）。 */
    private static final long END = 1792762200L;

    private static ServerBearTasks.BearConfig defaults() {
        return ServerBearTasks.BearConfig.defaults();
    }

    private static ServerBearTasks.BearState freshState() {
        return new ServerBearTasks.BearState();
    }

    private static ServerBearTasks.Window activeWindow() {
        return ServerBearTasks.windowOf(T_MID, START, 0L, defaults());
    }

    // ======================= 1. 5204 降档重试 =======================

    @Test
    public void retry5204_downgrades620to320_immediately() {
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                5204, T_MID, activeWindow(), defaults(), freshState());
        assertTrue("5204 应重试", retry.retry);
        assertTrue("5204 应同轮立即重试", retry.immediate);
        assertEquals("默认档 2(620s) 降为 1(320s)", 1, retry.nextPrepareIndex);
        assertEquals("立即重试无需延时", 0L, retry.retryAtSec);
    }

    @Test
    public void retry5204_downgrades1820to620() {
        JSONObject p = new JSONObject();
        p.put("prepare_time_index", 3);   // 1820s
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                5204, T_MID, activeWindow(), cfg, freshState());
        assertTrue(retry.retry);
        assertTrue(retry.immediate);
        assertEquals("准备档 3(1820s) 降为 2(620s)", 2, retry.nextPrepareIndex);
    }

    @Test
    public void retry5204_lowestPrepare_noRetry() {
        JSONObject p = new JSONObject();
        p.put("prepare_time_index", 1);   // 320s 最低档
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                5204, T_MID, activeWindow(), cfg, freshState());
        assertFalse("已是最低准备档 320s，不再降档", retry.retry);
        assertTrue("原因可读", retry.reason.contains("最低准备档"));
    }

    @Test
    public void retry5204_alreadyDowngraded_noRetry() {
        ServerBearTasks.BearState state = freshState();
        state.downgradeRetries = ServerBearTasks.MAX_DOWNGRADE_RETRIES;
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                5204, T_MID, activeWindow(), defaults(), state);
        assertFalse("降档预算 1 次已用完，绝不无限重试", retry.retry);
        assertTrue(retry.reason.contains("已降档重试过"));
    }

    @Test
    public void retry5204_windowTooShort_noRetry() {
        // 距结束仅 300s < 320s(降档后准备) + 60s 安全余量
        long now = END - 300L;
        ServerBearTasks.Window w = ServerBearTasks.windowOf(now, START, 0L, defaults());
        assertEquals(ServerBearTasks.Window.ACTIVE, w.phase);
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                5204, now, w, defaults(), freshState());
        assertFalse("窗口剩余不足，不再降档重试", retry.retry);
        assertTrue(retry.reason.contains("窗口剩余"));
    }

    // ======================= 2. 401/2878/403/415 延时重试 =======================

    @Test
    public void retry401_scheduled60sLater() {
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                401, T_MID, activeWindow(), defaults(), freshState());
        assertTrue("401 应重试", retry.retry);
        assertFalse("401 走延时重试，不是同轮立即重试", retry.immediate);
        assertEquals("60s 后重试", T_MID + ServerBearTasks.LAUNCH_RETRY_DELAY_SEC, retry.retryAtSec);
        assertEquals("延时重试沿用当前准备档", 2, retry.nextPrepareIndex);
    }

    @Test
    public void retry2878_scheduled60sLater() {
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                2878, T_MID, activeWindow(), defaults(), freshState());
        assertTrue(retry.retry);
        assertEquals(T_MID + 60L, retry.retryAtSec);
    }

    @Test
    public void retry403_scheduled60sLater() {
        // 2026-11-06 生产排查：403（英雄被占用）曾直接封板；改为 60s 后重试 1 次（下轮重选编队）
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                403, T_MID, activeWindow(), defaults(), freshState());
        assertTrue("403 应重试", retry.retry);
        assertFalse("403 走延时重试", retry.immediate);
        assertEquals(T_MID + ServerBearTasks.LAUNCH_RETRY_DELAY_SEC, retry.retryAtSec);
    }

    @Test
    public void retry406_scheduled60sLater() {
        // 2026-11-07：取消 406 兵量自适应后，406 也纳入 60s 延时重试一次
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                406, T_MID, activeWindow(), defaults(), freshState());
        assertTrue("406 应重试", retry.retry);
        assertFalse("406 走延时重试", retry.immediate);
        assertEquals(T_MID + ServerBearTasks.LAUNCH_RETRY_DELAY_SEC, retry.retryAtSec);
    }

    @Test
    public void retry415_scheduled60sLater() {
        // 2026-11-06 生产排查：415（编队人数/配置超限）曾直接封板；改为 60s 后重试 1 次
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                415, T_MID, activeWindow(), defaults(), freshState());
        assertTrue("415 应重试", retry.retry);
        assertFalse("415 走延时重试", retry.immediate);
        assertEquals(T_MID + ServerBearTasks.LAUNCH_RETRY_DELAY_SEC, retry.retryAtSec);
    }

    @Test
    public void retry401_alreadyUsed_noRetry() {
        ServerBearTasks.BearState state = freshState();
        state.delayRetries = ServerBearTasks.MAX_DELAY_RETRIES;
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                401, T_MID, activeWindow(), defaults(), state);
        assertFalse("延时重试预算 1 次已用完", retry.retry);
        assertTrue(retry.reason.contains("60s 延时重试已用过"));
    }

    @Test
    public void retry401_windowTooShort_noRetry() {
        // 距结束 600s < 620s(准备档) + 60s 安全余量
        long now = END - 600L;
        ServerBearTasks.Window w = ServerBearTasks.windowOf(now, START, 0L, defaults());
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                401, now, w, defaults(), freshState());
        assertFalse("窗口剩余不足以 60s 后重试", retry.retry);
        assertTrue(retry.reason.contains("窗口剩余"));
    }

    @Test
    public void retryUnknownCode_noRetry() {
        ServerBearTasks.LaunchRetry retry = ServerBearTasks.launchRetryDecision(
                2844, T_MID, activeWindow(), defaults(), freshState());
        assertFalse("2844（已在集结）不重试", retry.retry);
        assertTrue("原因带原码语义", retry.reason.contains("2844"));

        ServerBearTasks.LaunchRetry unknown = ServerBearTasks.launchRetryDecision(
                9999, T_MID, activeWindow(), defaults(), freshState());
        assertFalse("未知码不猜语义、不重试", unknown.retry);
    }

    // ======================= 3. 准备档/场次状态 =======================

    @Test
    public void effectivePrepareIndex_defaultAndDowngraded() {
        assertEquals("默认用配置档 2", 2, ServerBearTasks.effectivePrepareIndex(defaults(), freshState()));
        ServerBearTasks.BearState state = freshState();
        state.launchPrepareIndexUsed = 1;
        assertEquals("降档后沿用降档值 1", 1, ServerBearTasks.effectivePrepareIndex(defaults(), state));
        JSONObject p = new JSONObject();
        p.put("prepare_time_index", 4);
        assertEquals("配置档 4", 4, ServerBearTasks.effectivePrepareIndex(
                ServerBearTasks.BearConfig.of(p), freshState()));
    }

    @Test
    public void resetRetryIfNewWindow_scopedPerWindow() {
        ServerBearTasks.BearState state = freshState();
        state.retryWindowStart = START;
        state.downgradeRetries = 1;
        state.delayRetries = 1;
        state.nextLaunchRetryAt = START + 60L;
        state.lastLaunchCode = 5204;
        state.launchPrepareIndexUsed = 1;
        state.preheatRefreshAt = START - 100L;

        ServerBearTasks.resetRetryIfNewWindow(state, START);
        assertEquals("同场次保留重试预算（防无限重试）", 1, state.downgradeRetries);
        assertEquals("同场次保留延时重试预算", 1, state.delayRetries);

        ServerBearTasks.resetRetryIfNewWindow(state, START + 2 * 86400L);
        assertEquals("新场次归零降档预算", 0, state.downgradeRetries);
        assertEquals("新场次归零延时重试预算", 0, state.delayRetries);
        assertEquals("新场次清空重试时刻", 0L, state.nextLaunchRetryAt);
        assertEquals("新场次清空上次错误码", 0, state.lastLaunchCode);
        assertEquals("新场次重置准备档", 0, state.launchPrepareIndexUsed);
        assertEquals("新场次重置预热时刻", 0L, state.preheatRefreshAt);
    }

    // ======================= 4. 编队配置 =======================

    @Test
    public void parseConfiguredHeroIds_jsonArrayStringList() {
        JSONObject p = new JSONObject();
        p.put("hero_ids", new JSONArray(Arrays.asList(101L, 102L, 103L, 104L)));
        assertEquals("最多取 3 名英雄（网页版 MAX_HERO_COUNT=3）",
                Arrays.asList(101L, 102L, 103L), ServerBearTasks.parseConfiguredHeroIds(p));

        JSONObject str = new JSONObject();
        str.put("hero_ids", "101, 102");
        assertEquals(Arrays.asList(101L, 102L), ServerBearTasks.parseConfiguredHeroIds(str));

        JSONObject dup = new JSONObject();
        dup.put("hero_ids", new JSONArray(Arrays.asList(101L, 101L, 102L)));
        assertEquals("重复英雄去重", Arrays.asList(101L, 102L),
                ServerBearTasks.parseConfiguredHeroIds(dup));

        assertEquals("未配置→空", Collections.emptyList(),
                ServerBearTasks.parseConfiguredHeroIds(new JSONObject()));
        assertTrue("null 安全", ServerBearTasks.parseConfiguredHeroIds(null).isEmpty());
    }

    @Test
    public void bearConfig_parsesFormationAndClamps() {
        JSONObject p = new JSONObject();
        p.put("hero_ids", new JSONArray(Arrays.asList(101L, 102L)));
        p.put("squad_index", 9);
        p.put("join_count", 9);
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        assertEquals(Arrays.asList(101L, 102L), cfg.heroIds);
        assertEquals("squad_index 夹到 1..6", 6, cfg.squadIndex);
        assertEquals("join_count 夹到 6", 6, cfg.joinCount);

        ServerBearTasks.BearConfig ds = ServerBearTasks.BearConfig.defaults();
        assertTrue("默认无 hero_ids 配置", ds.heroIds.isEmpty());
        assertEquals("默认队1", 1, ds.squadIndex);
    }

    @Test
    public void formationSourceLabel_readable() {
        JSONObject p = new JSONObject();
        p.put("hero_ids", new JSONArray(Arrays.asList(101L, 102L)));
        assertEquals("hero_ids(101,102)", ServerBearTasks.formationSourceLabel(
                ServerBearTasks.BearConfig.of(p)));

        JSONObject squad = new JSONObject();
        squad.put("squad_index", 3);
        assertEquals("squad_index(3)", ServerBearTasks.formationSourceLabel(
                ServerBearTasks.BearConfig.of(squad)));
        assertEquals("squad_index(1)", ServerBearTasks.formationSourceLabel(defaults()));
    }

    @Test
    public void formationSourceLabel_effectiveFallback() {
        JSONObject p = new JSONObject();
        p.put("hero_ids", new JSONArray(Arrays.asList(101L, 102L)));
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        assertEquals("hero_ids 可用 → 标注 hero_ids",
                "hero_ids(101,102)", ServerBearTasks.formationSourceLabel(cfg, true));
        assertTrue("hero_ids 不可用 → 如实标注回退 squad_index",
                ServerBearTasks.formationSourceLabel(cfg, false).contains("回退"));
    }

    // ======================= 5. 预热（20:50-21:00） =======================

    @Test
    public void preheatRefreshDue_60sThrottle() {
        assertTrue("首次必刷新", ServerBearTasks.preheatRefreshDue(PREP_START, 0L));
        assertFalse("60s 内不重复刷新", ServerBearTasks.preheatRefreshDue(PREP_START, PREP_START - 30L));
        assertTrue("满 60s 刷新", ServerBearTasks.preheatRefreshDue(PREP_START, PREP_START - 60L));
        assertTrue("时钟回拨视为到期", ServerBearTasks.preheatRefreshDue(PREP_START, PREP_START + 10L));
    }

    @Test
    public void preheatSummary_readyAndNotReady() {
        String ready = ServerBearTasks.preheatSummary(true, 100L, 200L, 9001L,
                true, "squad_index(1)", 60L);
        assertTrue(ready, ready.contains("目标(100,200)monster=9001 就绪"));
        assertTrue(ready, ready.contains("编队=squad_index(1) 就绪"));
        assertTrue(ready, ready.contains("距开赛 60s"));

        String notReady = ServerBearTasks.preheatSummary(false, 0L, 0L, 0L,
                false, "hero_ids(101,102)", 120L);
        assertTrue("目标未就绪原因可读", notReady.contains("目标未就绪"));
        assertTrue("编队不可用原因可读", notReady.contains("hero_ids(101,102) 不可用"));
        assertTrue(notReady.contains("距开赛 120s"));
    }

    @Test
    public void bearWakeAtMillis_preheatEvery60s() {
        // 20:50 刚进准备窗：预热每 60s 醒（比 C3 的开赛前 60s 更早）
        ServerBearTasks.Window prep = ServerBearTasks.windowOf(PREP_START, START, 0L, defaults());
        assertEquals(ServerBearTasks.Window.PREPARING, prep.phase);
        assertEquals("20:50 预热 → 20:51",
                (PREP_START + 60L) * 1000L,
                ServerBearTasks.bearWakeAtMillis(PREP_START * 1000L, prep, true, false, 0L));

        // 20:59:30（距开赛 30s）：预热唤醒不晚于开赛时刻
        long near = START - 30L;
        ServerBearTasks.Window prep2 = ServerBearTasks.windowOf(near, START, 0L, defaults());
        assertEquals(ServerBearTasks.Window.PREPARING, prep2.phase);
        assertEquals("预热不晚于开赛", START * 1000L,
                ServerBearTasks.bearWakeAtMillis(near * 1000L, prep2, true, false, 0L));
    }

    @Test
    public void bearWakeAtMillis_retryAndRecheck() {
        // 窗口内发车成功 → C3 30s 后对账；有更早重试点则取重试点
        ServerBearTasks.Window active = activeWindow();
        assertEquals("发车后 30s 对账",
                (T_MID + 30L) * 1000L,
                ServerBearTasks.bearWakeAtMillis(T_MID * 1000L, active, true, true, 0L));
        assertEquals("重试点比 30s 更早 → 取重试点",
                (T_MID + 10L) * 1000L,
                ServerBearTasks.bearWakeAtMillis(T_MID * 1000L, active, true, false, T_MID + 10L));
        assertEquals("发车失败后按 60s 重试点唤醒",
                (T_MID + 60L) * 1000L,
                ServerBearTasks.bearWakeAtMillis(T_MID * 1000L, active, true, false, T_MID + 60L));
    }

    @Test
    public void bearWakeAtMillis_stagedIntoPreheatWindow() {
        // 距开赛 3 小时：分段步进 ≤2h，避免 C3 一觉睡到开赛前 60s 跳过 20:50-21:00 预热窗
        long threeHoursBefore = START - 3 * 3600L;
        ServerBearTasks.Window w3h = ServerBearTasks.windowOf(threeHoursBefore, START, 0L, defaults());
        assertEquals(ServerBearTasks.Window.WAITING, w3h.phase);
        assertEquals("远距每 ≤2h 醒一步",
                (threeHoursBefore + ServerBearTasks.PREHEAT_ARM_SEC) * 1000L,
                ServerBearTasks.bearWakeAtMillis(threeHoursBefore * 1000L, w3h, true, false, 0L));

        // 距开赛 1 小时：≤20 分钟一步
        long oneHourBefore = START - 3600L;
        ServerBearTasks.Window w1h = ServerBearTasks.windowOf(oneHourBefore, START, 0L, defaults());
        assertEquals(ServerBearTasks.Window.WAITING, w1h.phase);
        assertEquals("近距每 ≤20min 醒一步",
                (oneHourBefore + ServerBearTasks.PREHEAT_LOOKAHEAD_SEC) * 1000L,
                ServerBearTasks.bearWakeAtMillis(oneHourBefore * 1000L, w1h, true, false, 0L));

        // 距开赛 20 分钟：进入 60s 预热节拍
        long twentyMinBefore = START - ServerBearTasks.PREHEAT_LOOKAHEAD_SEC;
        ServerBearTasks.Window w20m = ServerBearTasks.windowOf(twentyMinBefore, START, 0L, defaults());
        assertEquals(ServerBearTasks.Window.WAITING, w20m.phase);
        assertEquals("进入预热提前量后 60s 节拍",
                (twentyMinBefore + 60L) * 1000L,
                ServerBearTasks.bearWakeAtMillis(twentyMinBefore * 1000L, w20m, true, false, 0L));
    }

    @Test
    public void bearWakeAtMillis_unreserved_noWake() {
        ServerBearTasks.Window fallback = ServerBearTasks.windowOf(T_MID, 0L, 0L, defaults());
        assertEquals(ServerBearTasks.Window.ACTIVE, fallback.phase);
        assertEquals("未预约不发车 → 不覆盖节奏唤醒", 0L,
                ServerBearTasks.bearWakeAtMillis(T_MID * 1000L, fallback, false, false, 0L));
    }

    // ======================= 6. 可观测文案 / 对账 / 状态 =======================

    @Test
    public void launchAttemptText_hasAllObservabilityFields() {
        String text = ServerBearTasks.launchAttemptText(START, 2, "squad_index(1)", 1800L);
        assertTrue(text, text.contains("窗口=21:00"));
        assertTrue(text, text.contains("prepare=2(620s)"));
        assertTrue(text, text.contains("编队=squad_index(1)"));
        assertTrue(text, text.contains("剩余=1800s"));
    }

    @Test
    public void launchFailureText_retryPlansReadable() {
        ServerBearTasks.LaunchRetry downgrade = ServerBearTasks.LaunchRetry.immediateDowngrade(1, "x");
        String text5204 = ServerBearTasks.launchFailureText(5204, downgrade);
        assertTrue(text5204, text5204.contains("码5204"));
        assertTrue("降档计划可读", text5204.contains("立即降档至 prepare=1(320s)"));

        ServerBearTasks.LaunchRetry delayed = ServerBearTasks.LaunchRetry.delayed(START + 60L, 2, "x");
        String text401 = ServerBearTasks.launchFailureText(401, delayed);
        assertTrue(text401, text401.contains("码401"));
        assertTrue(text401, text401.contains("60s 后重试 1 次"));

        String finalText = ServerBearTasks.launchFailureText(403,
                ServerBearTasks.LaunchRetry.none("英雄被占用/安全拒绝（码403）"));
        assertTrue("不重试原因可读", finalText.contains("不再重试"));

        String shortWindow = ServerBearTasks.launchFailureText(
                ServerBearTasks.CODE_RETRY_WINDOW_SHORT, null);
        assertTrue("窗口剩余不足文案", shortWindow.contains("窗口剩余不足"));
    }

    @Test
    public void joinDetailSummary_countsAndSelf() {
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("owner", 2L);
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("owner", 5L);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("detail_list", Arrays.asList(other, self));
        assertEquals("成员2 · 含自己=是", ServerBearTasks.joinDetailSummary(detail, 2L));
        assertEquals("成员2 · 含自己=否", ServerBearTasks.joinDetailSummary(detail, 7L));
        assertEquals("对账详情为空", ServerBearTasks.joinDetailSummary(new LinkedHashMap<>(), 2L));
        assertEquals("对账未返回", ServerBearTasks.joinDetailSummary(null, 2L));
    }

    @Test
    public void bearCodeText_401Readable() {
        assertTrue(ServerBearTasks.bearCodeText(401).contains("401"));
        assertEquals("未知码仍不猜语义", "", ServerBearTasks.bearCodeText(9999));
    }

    @Test
    public void stateJson_roundTripRetryFields() {
        ServerBearTasks.BearState state = freshState();
        state.reserve = START;
        state.retryWindowStart = START;
        state.nextLaunchRetryAt = START + 60L;
        state.downgradeRetries = 1;
        state.delayRetries = 1;
        state.lastLaunchCode = 401;
        state.launchPrepareIndexUsed = 1;
        state.preheatRefreshAt = START - 120L;
        state.lastLaunchText = "发车失败(码401)：兵/队列参数不足（码401） · 计划 60s 后重试 1 次";
        ServerBearTasks.BearState back = ServerBearTasks.parseState(ServerBearTasks.stateJson(state));
        assertEquals("重试场次可持久化", START, back.retryWindowStart);
        assertEquals("重试时刻可持久化", START + 60L, back.nextLaunchRetryAt);
        assertEquals(1, back.downgradeRetries);
        assertEquals(1, back.delayRetries);
        assertEquals(401, back.lastLaunchCode);
        assertEquals(1, back.launchPrepareIndexUsed);
        assertEquals(START - 120L, back.preheatRefreshAt);
        assertEquals(state.lastLaunchText, back.lastLaunchText);
    }

    @Test
    public void launchRetry_noneToStringReadable() {
        ServerBearTasks.LaunchRetry none = ServerBearTasks.LaunchRetry.none("窗口剩余不足");
        assertFalse(none.retry);
        assertTrue(none.toString().contains("窗口剩余不足"));

        ServerBearTasks.LaunchRetry delayed = ServerBearTasks.LaunchRetry.delayed(
                START + 60L, 2, "401");
        assertTrue(delayed.retry);
        assertTrue(delayed.toString().contains("延时重试"));
    }

    @Test
    public void activeWindow_notNull() {
        ServerBearTasks.Window w = activeWindow();
        assertNotNull(w);
        assertEquals(ServerBearTasks.Window.ACTIVE, w.phase);
    }
}
