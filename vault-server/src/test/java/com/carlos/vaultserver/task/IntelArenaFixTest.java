package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 情报门控（I2）、专家额外情报初始化竞态（I4）、竞技场结算窗（A1）纯函数单测。
 *
 * <p>基准：网页版 wjdr-tool 反编译源码
 * {@code modules/daily/service/DailyIntelligenceService.java:256-262,598-606}、
 * {@code DailyArenaService.java:74,761-809}、{@code DailyArenaState.java:160-186,237-261}。
 * 不连真协议，只锁“计数/胜负/时间窗/去重/宽限”判定，防止 15s/30s 节奏下重复请求或误判。
 */
public class IntelArenaFixTest {

    private static final ZoneId BEIJING = ZoneId.of("GMT+8");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "服务器");
    }

    /** 北京时间毫秒时间戳。 */
    private static long beijing(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BEIJING)
                .toInstant().toEpochMilli();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // ==================== I2 情报门控 ====================

    @Test
    public void intelStaminaCost_matchesWeb() {
        assertEquals("营救 12 点", 12L, ServerFieldTasks.intelStaminaCost(2));
        assertEquals("英雄之旅 12 点", 12L, ServerFieldTasks.intelStaminaCost(3));
        assertEquals("怪物 10 点", 10L, ServerFieldTasks.intelStaminaCost(1));
        assertEquals("活动巨兽 10 点", 10L, ServerFieldTasks.intelStaminaCost(11));
        assertEquals("冒险巨兽 10 点", 10L, ServerFieldTasks.intelStaminaCost(100));
    }

    @Test
    public void intelStaminaOf_listShape() {
        // 生产/网页版口径：notify_recover_resource_init = {data:[{id:201,count:...}, {id:205,...}]}
        Map<String, Object> notify = map("code", 0, "data", List.of(
                map("id", 205, "count", 3),
                map("id", 200.0, "count", 30),
                map("id", 201, "count", 560)));
        assertEquals(Long.valueOf(560L), ServerFieldTasks.intelStaminaOf(notify));
    }

    @Test
    public void intelStaminaOf_mapShape() {
        // 形状兼容：data 是 Map（键 "201.0"），值里带 count
        Map<String, Object> notify = map("data", map("201.0", map("count", 88), "205.0", map("count", 3)));
        assertEquals(Long.valueOf(88L), ServerFieldTasks.intelStaminaOf(notify));
    }

    @Test
    public void intelStaminaOf_unwrappedMapShape() {
        // notifyData 已把 data(Map) 解包成内层键值表：{ "201.0": {count,...} }
        Map<String, Object> notify = map("201.0", map("count", 66, "max_count", 120),
                "205.0", map("count", 3));
        assertEquals(Long.valueOf(66L), ServerFieldTasks.intelStaminaOf(notify));
    }

    @Test
    public void intelStaminaOf_singleEntryAndValueAlias() {
        assertEquals("notifyData 已解包成单条目",
                Long.valueOf(42L), ServerFieldTasks.intelStaminaOf(map("id", 201, "count", 42)));
        assertEquals("兼容 value 别名",
                Long.valueOf(77L), ServerFieldTasks.intelStaminaOf(map("data", List.of(
                        map("id", 201.0, "value", 77)))));
    }

    @Test
    public void intelStaminaOf_missingReturnsNull() {
        assertNull(ServerFieldTasks.intelStaminaOf(null));
        assertNull(ServerFieldTasks.intelStaminaOf(map("code", 0)));
        assertNull("只有 205（联盟捐献次数）不能当体力",
                ServerFieldTasks.intelStaminaOf(map("data", List.of(map("id", 205, "count", 3)))));
    }

    @Test
    public void intelStaminaAllows_unknownStaminaDoesNotBlock() {
        assertTrue("体力未同步时不阻塞派队（只做队列门）", ServerFieldTasks.intelStaminaAllows(null, 10));
        assertTrue(ServerFieldTasks.intelStaminaAllows(50L, 10));
        assertTrue(ServerFieldTasks.intelStaminaAllows(10L, 10));
        assertFalse(ServerFieldTasks.intelStaminaAllows(9L, 10));
        assertFalse(ServerFieldTasks.intelStaminaAllows(11L, 12));
    }

    @Test
    public void intelQueueSlots_minConfigAndWorldCapacity() {
        assertEquals("配置 1、世界容量 3 → 1（网页默认 queueCount=1）",
                1, ServerFieldTasks.intelQueueSlots(1, 3));
        assertEquals("配置 4、世界容量 3 → 3", 3, ServerFieldTasks.intelQueueSlots(4, 3));
        assertEquals("世界容量未知兜底 3", 2, ServerFieldTasks.intelQueueSlots(2, 0));
        assertEquals("配置上限 6", 6, ServerFieldTasks.intelQueueSlots(99, 10));
        assertEquals("配置下限 1", 1, ServerFieldTasks.intelQueueSlots(0, 5));
    }

    @Test
    public void intelQueueCount_paramFallbacks() {
        assertEquals("网页版默认 1", 1, ServerFieldTasks.intelQueueCount(null));
        assertEquals(1, ServerFieldTasks.intelQueueCount(new JSONObject()));
        assertEquals(2, ServerFieldTasks.intelQueueCount(new JSONObject().put("queue_count", 2)));
        assertEquals(3, ServerFieldTasks.intelQueueCount(new JSONObject().put("queueCount", 3)));
        assertEquals("兼容旧参数 max_queues",
                4, ServerFieldTasks.intelQueueCount(new JSONObject().put("max_queues", 4)));
    }

    @Test
    public void intelDispatchBlocked_window() {
        long now = 1_000_000L;
        assertFalse("从未派过 → 不阻塞", ServerFieldTasks.intelDispatchBlocked(0L, now, 600_000L));
        assertTrue("5 分钟前派过 → 去重窗内不重复派",
                ServerFieldTasks.intelDispatchBlocked(now - 300_000L, now, 600_000L));
        assertFalse("10 分钟前派过 → 允许重试",
                ServerFieldTasks.intelDispatchBlocked(now - 600_000L, now, 600_000L));
    }

    @Test
    public void intelDispatchLedger_roundTrip() {
        TaskContext tc = context();
        long now = System.currentTimeMillis();
        assertFalse(ServerFieldTasks.intelDispatchBlocked(tc, 9001L, now));
        ServerFieldTasks.markIntelDispatched(tc, 9001L, now);
        assertTrue("已派事件在去重窗内不再派",
                ServerFieldTasks.intelDispatchBlocked(tc, 9001L, now + 60_000L));
        assertFalse("其它事件不受影响",
                ServerFieldTasks.intelDispatchBlocked(tc, 9002L, now));
        assertFalse("去重窗过期后可再次尝试",
                ServerFieldTasks.intelDispatchBlocked(tc, 9001L, now + 600_001L));
    }

    @Test
    public void intelExpertGrace_firstThreeTimesWithinTenMinutes() {
        long first = 10_000_000L;
        assertTrue(ServerFieldTasks.intelExpertGraceActive(0L, 1, first));
        assertTrue(ServerFieldTasks.intelExpertGraceActive(first, 1, first + 1_000L));
        assertTrue(ServerFieldTasks.intelExpertGraceActive(first, 3, first + 9 * 60_000L));
        assertFalse("第 4 次不再宽限", ServerFieldTasks.intelExpertGraceActive(first, 4, first + 1_000L));
        assertFalse("超过 10 分钟不再宽限",
                ServerFieldTasks.intelExpertGraceActive(first, 2, first + 600_001L));
    }

    // ==================== I4 expert_extra 竞态：状态键 ====================

    @Test
    public void intelExpertFirstState_persistsAcrossRounds() {
        TaskContext tc = context();
        String key = "srv:intel:expertfirst:48460403:888";
        assertNull("首轮未写入", tc.store.getString(key));
        tc.store.putString(key, new JSONObject().put("ts", 1234L).put("attempts", 2).toString());
        assertEquals(2, new JSONObject(tc.store.getString(key)).optInt("attempts"));
        assertEquals(1234L, new JSONObject(tc.store.getString(key)).optLong("ts"));
    }

    // ==================== A1 竞技场结算窗 ====================

    @Test
    public void arenaInDailyRewardWindow_2310to2400() {
        assertFalse("23:09 未进结算窗", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 23, 23, 9)));
        assertTrue("23:10 进结算窗", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 23, 23, 10)));
        assertTrue("23:59 仍在结算窗", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 23, 23, 59)));
        assertFalse("次日 00:00 结算窗结束（跨天重置）", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 24, 0, 0)));
        assertFalse("白天不是结算窗", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 23, 12, 0)));
    }

    @Test
    public void arenaChallengesCompleted_matchesWeb() {
        assertEquals(0, ServerTownTasks.arenaChallengesCompleted(0, 0, 0));
        assertEquals(3, ServerTownTasks.arenaChallengesCompleted(3, 0, 0));
        assertEquals(5, ServerTownTasks.arenaChallengesCompleted(5, 0, 0));
        assertEquals("buy_times 2 / left_times 0 → 已用 2 次付费",
                5, ServerTownTasks.arenaChallengesCompleted(3, 2, 0));
        assertEquals("买 2 剩 1 → 只用掉 1 次",
                4, ServerTownTasks.arenaChallengesCompleted(3, 2, 1));
        assertEquals("负数防御", 0, ServerTownTasks.arenaChallengesCompleted(-1, -2, -1));
    }

    @Test
    public void arenaFightBudget_knownCounts() {
        assertEquals(5, ServerTownTasks.arenaFightBudget(0, 0, 5));
        assertEquals(2, ServerTownTasks.arenaFightBudget(3, 3, 5));
        assertEquals("已完成 5 场不再挑战", 0, ServerTownTasks.arenaFightBudget(5, 5, 5));
        assertEquals("本日 maxFights 限额", 3, ServerTownTasks.arenaFightBudget(0, 0, 3));
        assertEquals("已发场次兜底", 1, ServerTownTasks.arenaFightBudget(0, 4, 5));
        assertEquals(0, ServerTownTasks.arenaFightBudget(4, 5, 5));
    }

    @Test
    public void arenaFightBudget_unknownCountsCapsByLocalSent() {
        assertEquals("base 未同步：不判超额，按本地已发封顶 5",
                5, ServerTownTasks.arenaFightBudget(-1, 0, 5));
        assertEquals(1, ServerTownTasks.arenaFightBudget(-1, 4, 5));
        assertEquals(0, ServerTownTasks.arenaFightBudget(-1, 5, 5));
    }

    @Test
    public void arenaRewardRetryDue_fiveMinuteThrottle() {
        long now = 10_000_000L;
        assertTrue("从未尝试 → 立即可以领", ServerTownTasks.arenaRewardRetryDue(0L, now));
        assertFalse("4 分钟内不重试", ServerTownTasks.arenaRewardRetryDue(now - 240_000L, now));
        assertTrue("满 5 分钟可重试", ServerTownTasks.arenaRewardRetryDue(now - 300_000L, now));
    }

    @Test
    public void arenaRewardAttemptAllowed_gating() {
        assertTrue("服务端计数已达 5", ServerTownTasks.arenaRewardAttemptAllowed(5, 5));
        assertTrue("计数未知（base 未同步）→ 服务端裁决",
                ServerTownTasks.arenaRewardAttemptAllowed(-1, 0));
        assertFalse("明确 0 场且本地也没发过 → 不请求",
                ServerTownTasks.arenaRewardAttemptAllowed(0, 0));
        assertFalse("明确 3/5 且本地也只发 3 场 → 不请求",
                ServerTownTasks.arenaRewardAttemptAllowed(3, 3));
        assertTrue("本地已发 5 场但服务端计数陈旧(0) → 仍让服务端裁决一次",
                ServerTownTasks.arenaRewardAttemptAllowed(0, 5));
        assertTrue(ServerTownTasks.arenaRewardAttemptAllowed(4, 5));
    }

    @Test
    public void arenaWinOf_defensiveShapes() {
        assertEquals(1, ServerTownTasks.arenaWinOf(map("report", map("win", true))));
        assertEquals(0, ServerTownTasks.arenaWinOf(map("report", map("win", false))));
        assertEquals(1, ServerTownTasks.arenaWinOf(map("report", map("win", 1))));
        assertEquals(0, ServerTownTasks.arenaWinOf(map("report", map("win", 0))));
        assertEquals(1, ServerTownTasks.arenaWinOf(map("report", map("win", "true"))));
        assertEquals(0, ServerTownTasks.arenaWinOf(map("report", map("win", "false"))));
        assertEquals("缺 report 不猜胜负", -1, ServerTownTasks.arenaWinOf(map("foo", 1)));
        assertEquals(-1, ServerTownTasks.arenaWinOf(null));
    }

    @Test
    public void arenaBaseOf_standardAndWrappedShapes() {
        long resetTs = beijing(2026, 10, 23, 0, 0) / 1000L;
        Map<String, Object> body = map("free_times_use", 3, "buy_times", 0, "left_times", 0,
                "daily_reward", false, "last_reset_ts", resetTs);
        ServerTownTasks.ArenaBase a = ServerTownTasks.arenaBaseOf(map("base", body));
        assertNotNull(a);
        assertEquals(3, a.freeUsed);
        assertEquals(3, ServerTownTasks.arenaChallengesCompleted(a.freeUsed, a.buyTimes, a.leftTimes));
        assertFalse(a.dailyRewardClaimed);
        assertEquals(resetTs, a.lastResetTs);

        // notifyData 已解包后的直接 base 本体
        ServerTownTasks.ArenaBase b = ServerTownTasks.arenaBaseOf(body);
        assertNotNull(b);
        assertEquals(3, b.freeUsed);

        // data 包装（跨版本防御）
        ServerTownTasks.ArenaBase c = ServerTownTasks.arenaBaseOf(map("data", map("base", body)));
        assertNotNull(c);
        assertEquals(3, c.freeUsed);

        // daily_reward 真值防御（Boolean/String）
        ServerTownTasks.ArenaBase d = ServerTownTasks.arenaBaseOf(map("base",
                map("free_times_use", 5, "daily_reward", true)));
        assertNotNull(d);
        assertTrue(d.dailyRewardClaimed);
        ServerTownTasks.ArenaBase e = ServerTownTasks.arenaBaseOf(map("base",
                map("free_times_use", 5, "daily_reward", "true")));
        assertNotNull(e);
        assertTrue(e.dailyRewardClaimed);
    }

    @Test
    public void arenaBaseOf_missingCountsReturnsNull() {
        assertNull(ServerTownTasks.arenaBaseOf(null));
        assertNull(ServerTownTasks.arenaBaseOf(map("code", 0)));
        assertNull("base 里没有计数字段 = 未同步",
                ServerTownTasks.arenaBaseOf(map("base", map("foo", 1))));
    }

    @Test
    public void arenaBaseFreshForDay_rejectsYesterdaySnapshot() {
        long now = beijing(2026, 10, 23, 12, 0);
        long yesterdayNoon = beijing(2026, 10, 22, 12, 0) / 1000L;
        long todayMidnight = beijing(2026, 10, 23, 0, 0) / 1000L;
        assertFalse(ServerTownTasks.arenaBaseFreshForDay(yesterdayNoon, now));
        assertTrue(ServerTownTasks.arenaBaseFreshForDay(todayMidnight, now));
        assertTrue("毫秒时间戳兼容",
                ServerTownTasks.arenaBaseFreshForDay(todayMidnight * 1000L, now));
    }

    // ==================== A1 当日台账（跨天重置/base 合并） ====================

    @Test
    public void arenaDay_resetsAcrossBeijingDays() {
        TaskContext tc = context();
        long yesterday = beijing(2026, 10, 22, 20, 0);
        ServerTownTasks.ArenaDay old = ServerTownTasks.ArenaDay.load(tc, yesterday);
        old.fightsSent = 5;
        old.wins = 5;
        old.rewardSettled = true;
        old.save(tc);

        ServerTownTasks.ArenaDay today = ServerTownTasks.ArenaDay.load(
                tc, beijing(2026, 10, 23, 9, 0));
        assertEquals(0, today.fightsSent);
        assertEquals(0, today.wins);
        assertFalse("跨天重置领奖状态", today.rewardSettled);
        assertEquals(-1, today.knownCompleted());
    }

    @Test
    public void arenaDay_saveRoundTripWithinDay() {
        TaskContext tc = context();
        long now = beijing(2026, 10, 23, 9, 0);
        ServerTownTasks.ArenaDay day = ServerTownTasks.ArenaDay.load(tc, now);
        day.fightsSent = 3;
        day.wins = 3;
        day.baseKnown = true;
        day.freeUsed = 3;
        day.buyTimes = 0;
        day.leftTimes = 0;
        day.rewardClaimed = false;
        day.rewardSettled = true;
        day.save(tc);

        ServerTownTasks.ArenaDay again = ServerTownTasks.ArenaDay.load(
                tc, beijing(2026, 10, 23, 22, 0));
        assertEquals(3, again.fightsSent);
        assertEquals(3, again.wins);
        assertTrue(again.baseKnown);
        assertEquals(3, again.knownCompleted());
        assertTrue(again.rewardSettled);
        assertFalse(again.rewardClaimed);
    }

    @Test
    public void arenaDay_mergeBase_ignoresYesterdaySnapshotAndAcceptsToday() {
        TaskContext tc = context();
        long now = beijing(2026, 10, 23, 12, 0);
        ServerTownTasks.ArenaDay day = ServerTownTasks.ArenaDay.load(tc, now);

        long yesterdayTs = beijing(2026, 10, 22, 23, 0) / 1000L;
        day.mergeBase(new ServerTownTasks.ArenaBase(5, 0, 0, true, yesterdayTs), now);
        assertFalse("昨日 base 不能当今日权威计数（否则今天整天不挑战/不领奖）", day.baseKnown);
        assertEquals(-1, day.knownCompleted());
        assertFalse(day.rewardSettled);

        long todayTs = beijing(2026, 10, 23, 0, 0) / 1000L;
        day.mergeBase(new ServerTownTasks.ArenaBase(2, 0, 0, false, todayTs), now);
        assertTrue(day.baseKnown);
        assertEquals(2, day.knownCompleted());
        assertEquals(todayTs, day.lastResetTs);

        day.mergeBase(new ServerTownTasks.ArenaBase(0, 0, 0, true, todayTs), now);
        assertTrue("服务端 daily_reward=true → 今日已领", day.rewardClaimed);
        assertTrue(day.rewardSettled);
    }

    @Test
    public void arenaDay_mergeBase_nullNoop() {
        TaskContext tc = context();
        long now = beijing(2026, 10, 23, 12, 0);
        ServerTownTasks.ArenaDay day = ServerTownTasks.ArenaDay.load(tc, now);
        day.mergeBase(null, now);
        assertFalse(day.baseKnown);
        assertEquals(-1, day.knownCompleted());
    }

    @Test
    public void arenaDay_mergeInitBase_doesNotOverwriteFreshFightBase() {
        TaskContext tc = context();
        long now = beijing(2026, 10, 23, 14, 0);
        long todayTs = beijing(2026, 10, 23, 0, 0) / 1000L;
        ServerTownTasks.ArenaDay day = ServerTownTasks.ArenaDay.load(tc, now);

        day.mergeInitBase(new ServerTownTasks.ArenaBase(0, 0, 0, false, todayTs), now);
        assertEquals("登录时 base：今日已打 0 场", 0, day.knownCompleted());

        // 之后每场 fight 响应都会带回更权威的 base
        day.mergeBase(new ServerTownTasks.ArenaBase(4, 0, 0, false, todayTs), now);
        assertEquals(4, day.knownCompleted());

        // 30s 节奏下一轮又读到同一份登录快照：不得覆盖回 0（否则结算窗误判次数不足、不领奖）
        day.mergeInitBase(new ServerTownTasks.ArenaBase(0, 0, 0, false, todayTs), now);
        assertEquals("fight 响应 base 更权威，登录旧快照不得回灌", 4, day.knownCompleted());
    }

    /** 辅助：确认测试数据构造形状正确（List/Map 混用不误判）。 */
    @Test
    public void arenaBaseOf_ignoresNonCountBaseFields() {
        ServerTownTasks.ArenaBase b = ServerTownTasks.arenaBaseOf(map("base", map(
                "free_times_use", 4,
                "left_times", 1,
                "buy_times", 1,
                "daily_reward", false)));
        assertNotNull(b);
        assertEquals("4 + max(0, 1-1) = 4", 4,
                ServerTownTasks.arenaChallengesCompleted(b.freeUsed, b.buyTimes, b.leftTimes));
        List<Object> ignored = new ArrayList<>();
        ignored.add(b);
        assertEquals(1, ignored.size());
    }
}
