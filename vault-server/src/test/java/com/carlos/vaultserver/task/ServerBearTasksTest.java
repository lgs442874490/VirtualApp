package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;

import com.carlos.vault.wjdr.WjdrEngine;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 巨熊行动（B1 / task-8）纯函数单测：窗口计算 / 幂等键 / 参数构造 / 触发判定 / 解析。
 *
 * <p>不连真协议、不建会话；时间常量取自生产 `state.json` 实测值（57259770/724 的
 * {@code reserve=1792760400} = 2026-10-23 21:00 北京时间，周五），保证窗口口径与线上一致。
 */
public class ServerBearTasksTest {

    /** 生产实测：2026-10-23 21:00 北京时间（周五）预约场次开始。 */
    private static final long START = 1792760400L;
    /** 20:50 = 开赛前 10 分钟（准备窗开始）。 */
    private static final long PREP_START = 1792759800L;
    /** 20:00（准备窗之前）。 */
    private static final long T_2000 = 1792756800L;
    /** 21:10（活动进行中）。 */
    private static final long T_MID = 1792761000L;
    /** 21:30（活动结束）。 */
    private static final long END = 1792762200L;
    /** 21:40（结束后）。 */
    private static final long T_AFTER = 1792762800L;
    /** 21:01（开赛后 1 分钟）。 */
    private static final long T_START_PLUS_60 = 1792760460L;
    /** 2026-10-25 21:00（下一场预约）。 */
    private static final long NEXT_EVENT = 1792933200L;
    /** 2026-10-22 20:00（周四）。 */
    private static final long THU_2000 = 1792670400L;
    /** 2026-10-23 00:30 北京时间。 */
    private static final long MIDNIGHT_0030 = 1792686600L;
    /** 2026-10-24 00:00 北京时间。 */
    private static final long NEXT_DAY_START = 1792771200L;
    /** 2026-10-30 21:00（下周五）。 */
    private static final long NEXT_WEEK_START = 1793365200L;
    /** 2026-10-23 15:00。 */
    private static final long T_1500 = 1792738800L;
    /** 2026-10-23 09:00。 */
    private static final long T_0900 = 1792717200L;

    private static ServerBearTasks.BearConfig defaults() {
        return ServerBearTasks.BearConfig.defaults();
    }

    // ======================= 1. 窗口计算 =======================

    @Test
    public void window_reserveIsAuthoritative() {
        ServerBearTasks.Window prep = ServerBearTasks.windowOf(PREP_START, START, 0L, defaults());
        assertEquals("开赛前 10 分钟 = 准备窗", ServerBearTasks.Window.PREPARING, prep.phase);
        assertEquals("准备窗来源=预约", ServerBearTasks.Window.SRC_RESERVE, prep.source);
        assertEquals("窗口起点=reserve_time", START, prep.startSec);
        assertEquals("窗口终点=reserve+30min", START + 1800L, prep.endSec);

        ServerBearTasks.Window active = ServerBearTasks.windowOf(T_MID, START, 0L, defaults());
        assertEquals("窗口内=ACTIVE", ServerBearTasks.Window.ACTIVE, active.phase);

        ServerBearTasks.Window waiting = ServerBearTasks.windowOf(T_2000, START, 0L, defaults());
        assertEquals("场次之前=WAITING", ServerBearTasks.Window.WAITING, waiting.phase);
        assertEquals("WAITING 给出下一场开始", START, waiting.startSec);

        ServerBearTasks.Window ended = ServerBearTasks.windowOf(END, START, 0L, defaults());
        assertEquals("reserve 有效但已过期=ENDED（不臆造下一场）",
                ServerBearTasks.Window.ENDED, ended.phase);
    }

    @Test
    public void window_trapStartCoversReserveRollover() {
        // 生产时序：开赛后 notify 会把 reserve 翻到下一场（10/25 21:00），mass_start_time 才是本场开始
        ServerBearTasks.Window w = ServerBearTasks.windowOf(
                T_START_PLUS_60, NEXT_EVENT, START, defaults());
        assertEquals("reserve 已翻场时用 mass_start_time 兜底为 ACTIVE",
                ServerBearTasks.Window.ACTIVE, w.phase);
        assertEquals("窗口来源=mass_start_time", ServerBearTasks.Window.SRC_TRAP_START, w.source);
        assertEquals("场次开始=mass_start_time", START, w.startSec);
    }

    @Test
    public void window_trapStartFuture_becomesNextWindow() {
        ServerBearTasks.Window w = ServerBearTasks.windowOf(T_2000, 0L, NEXT_EVENT, defaults());
        assertEquals(ServerBearTasks.Window.WAITING, w.phase);
        assertEquals("未来场次以 trapStart 作为下一窗口", NEXT_EVENT, w.startSec);
    }

    @Test
    public void window_noReserve_usesConfiguredFridayWindow() {
        ServerBearTasks.Window active = ServerBearTasks.windowOf(START, 0L, 0L, defaults());
        assertEquals("无预约时按配置周窗口（周五 21:00）执行", ServerBearTasks.Window.ACTIVE, active.phase);
        assertEquals("配置窗口来源", ServerBearTasks.Window.SRC_FALLBACK, active.source);
        assertEquals(START, active.startSec);

        ServerBearTasks.Window waiting = ServerBearTasks.windowOf(T_2000, 0L, 0L, defaults());
        assertEquals(ServerBearTasks.Window.WAITING, waiting.phase);
        assertEquals("周四→等待本周五 21:00", START, waiting.startSec);

        ServerBearTasks.Window after = ServerBearTasks.windowOf(T_AFTER, 0L, 0L, defaults());
        assertEquals(ServerBearTasks.Window.WAITING, after.phase);
        assertEquals("周五窗口已过→下周五", NEXT_WEEK_START, after.startSec);
    }

    @Test
    public void window_customWeekdayAndHour() {
        JSONObject p = new JSONObject();
        p.put("window_weekday", 7);          // 周日
        p.put("window_start_hour", 20);
        p.put("window_start_minute", 30);
        p.put("window_end_hour", 21);
        p.put("window_end_minute", 0);
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(p);
        // 2026-10-25 21:00 是周日 21:00；窗口 20:30-21:00，此时刚结束
        ServerBearTasks.Window ended = ServerBearTasks.windowOf(NEXT_EVENT, 0L, 0L, cfg);
        assertEquals("自定义周窗口结束后→等待下周", ServerBearTasks.Window.WAITING, ended.phase);
        assertEquals("下周日 20:30", NEXT_EVENT - 1800L + 7 * 86400L, ended.startSec);
    }

    // ======================= 2. 幂等键 =======================

    @Test
    public void launchedRecord_sameDayAndWindow_isIdempotent() {
        String rec = ServerBearTasks.launchedRecord("2026-10-23", START, 12345L);
        assertTrue("同一天同一场次=已发起", ServerBearTasks.launchedForWindow(rec, "2026-10-23", START));
        assertFalse("不同场次不算已发起",
                ServerBearTasks.launchedForWindow(rec, "2026-10-23", START + 86400L));
        assertFalse("不同日期不算已发起", ServerBearTasks.launchedForWindow(rec, "2026-10-24", START));
        assertFalse("null 安全", ServerBearTasks.launchedForWindow(null, "2026-10-23", START));
        assertEquals("marchId 可回读", 12345L, ServerBearTasks.launchedMarchId(rec));
    }

    @Test
    public void launchedRecord_failedAttempt_stillBlocksSecondLaunch() {
        String failed = ServerBearTasks.launchedFailedRecord("2026-10-23", START, 5204);
        assertTrue("失败/已尝试记录同样不允许发第二辆车（避免重复消耗）",
                ServerBearTasks.launchedForWindow(failed, "2026-10-23", START));
        assertEquals("失败记录无 marchId", 0L, ServerBearTasks.launchedMarchId(failed));
        assertTrue("文案保留原错误码", ServerBearTasks.describeLaunched(failed).contains("5204"));

        String attempted = ServerBearTasks.launchedRecord("2026-10-23", START, 0L);
        assertTrue(ServerBearTasks.launchedForWindow(attempted, "2026-10-23", START));
        assertTrue(ServerBearTasks.describeLaunched(attempted).contains("未确认"));
    }

    @Test
    public void joinsRecord_appendParseDedupe() {
        String rec = ServerBearTasks.joinedRecordAppend(null, "2026-10-23", START, 555L);
        rec = ServerBearTasks.joinedRecordAppend(rec, "2026-10-23", START, 777L);
        rec = ServerBearTasks.joinedRecordAppend(rec, "2026-10-23", START, 555L);
        List<Long> ids = ServerBearTasks.joinedMasters(rec, "2026-10-23", START);
        assertEquals("同一车头去重", Arrays.asList(555L, 777L), ids);
        assertTrue("跨场次不串台账",
                ServerBearTasks.joinedMasters(rec, "2026-10-23", START + 86400L).isEmpty());
        assertTrue("跨日期不串台账",
                ServerBearTasks.joinedMasters(rec, "2026-10-24", START).isEmpty());
        assertTrue("malformed 安全", ServerBearTasks.joinedMasters("x#y#z", "2026-10-23", START).isEmpty());
    }

    @Test
    public void dayKey_isBeijingDate() {
        assertEquals("2026-10-23 21:00 北京 → 10-23", "2026-10-23", ServerBearTasks.dayKey(START));
        assertEquals("2026-10-23 00:30 北京 → 10-23（不是 UTC 前一天）",
                "2026-10-23", ServerBearTasks.dayKey(MIDNIGHT_0030));
        assertEquals("跨日 00:00 → 10-24", "2026-10-24", ServerBearTasks.dayKey(NEXT_DAY_START));
    }

    // ======================= 3. 协议参数构造 =======================

    @Test
    public void launchPayload_shapeMatchesWeb() {
        ServerBearTasks.TrapTarget target = new ServerBearTasks.TrapTarget(9001L, 100L, 200L, 777L);
        Map<String, Object> payload = ServerBearTasks.launchPayload(target, formation(), 2);
        assertNotNull(payload);
        assertEquals("发起集结 type=411", 411L, Exec.longOf(payload.get("type")));
        Map<String, Object> endpoint = Exec.mapOf(payload.get("endpoint"));
        assertNotNull(endpoint);
        assertEquals(100L, Exec.longOf(endpoint.get("x")));
        assertEquals(200L, Exec.longOf(endpoint.get("y")));
        Map<String, Object> extra = Exec.mapOf(payload.get("extra"));
        assertNotNull(extra);
        assertEquals("monsterid 来自 map_alliance_trap.id", 9001L, Exec.longOf(extra.get("monsterid")));
        assertEquals("准备档 1..4", 2L, Exec.longOf(extra.get("prepare_time_index")));
        assertTrue(ServerBearTasks.formationReady(Exec.mapOf(payload.get("formation"))));
    }

    @Test
    public void launchPayload_rejectsIncompleteInput() {
        assertNull("无目标不允许构造", ServerBearTasks.launchPayload(null, formation(), 2));
        assertNull("monsterid=0 不允许构造",
                ServerBearTasks.launchPayload(new ServerBearTasks.TrapTarget(0, 1, 2, 0), formation(), 2));
        assertNull("坐标=0 不允许构造",
                ServerBearTasks.launchPayload(new ServerBearTasks.TrapTarget(1, 0, 2, 0), formation(), 2));
        assertNull("无编队不允许构造",
                ServerBearTasks.launchPayload(new ServerBearTasks.TrapTarget(1, 1, 2, 0), null, 2));
        Map<String, Object> noSoldier = new LinkedHashMap<>();
        noSoldier.put("hero_id", Collections.singletonList(Exec.kv(1L, 1L)));
        noSoldier.put("soldier", new ArrayList<>());
        assertNull("无兵力不允许构造",
                ServerBearTasks.launchPayload(new ServerBearTasks.TrapTarget(1, 1, 2, 0), noSoldier, 2));
    }

    @Test
    public void prepareTimeIndex_clampedAndMappedToSeconds() {
        assertEquals("index 越界向上夹到 4", 4, ServerBearTasks.clampPrepareIndex(9));
        assertEquals("index 越界向下夹到 1", 1, ServerBearTasks.clampPrepareIndex(0));
        assertEquals("默认档 index=2 对应 620 秒", 620, ServerBearTasks.prepareSeconds(2));
        assertEquals(320, ServerBearTasks.prepareSeconds(1));
        assertEquals(1820, ServerBearTasks.prepareSeconds(3));
        assertEquals(3620, ServerBearTasks.prepareSeconds(4));
        assertTrue("30 分钟场剩余 1800s 够默认 620s 档", ServerBearTasks.enoughTimeToLaunch(1800, 2));
        assertFalse("剩余 1800s 不够 3620s 档", ServerBearTasks.enoughTimeToLaunch(1800, 4));
    }

    @Test
    public void joinPayload_shapeMatchesWeb() {
        Map<String, Object> payload = ServerBearTasks.joinPayload(555L, 10L, 20L, formation());
        assertNotNull(payload);
        assertEquals("上车 type=99", 99L, Exec.longOf(payload.get("type")));
        Map<String, Object> endpoint = Exec.mapOf(payload.get("endpoint"));
        assertNotNull(endpoint);
        assertEquals("endpoint=车头城市坐标 x", 10L, Exec.longOf(endpoint.get("x")));
        assertEquals("endpoint=车头城市坐标 y", 20L, Exec.longOf(endpoint.get("y")));
        Map<String, Object> extra = Exec.mapOf(payload.get("extra"));
        assertNotNull(extra);
        assertEquals("extra.master_marchid", 555L, Exec.longOf(extra.get("master_marchid")));
        assertNull("车头 id=0 不允许构造", ServerBearTasks.joinPayload(0, 10, 20, formation()));
        assertNull("坐标=0 不允许构造", ServerBearTasks.joinPayload(1, 0, 20, formation()));
    }

    @Test
    public void chooseSquadHeroes_avoidsLaunchedHeroes() {
        Map<Long, List<Long>> squads = new TreeMap<>();
        squads.put(1L, Arrays.asList(11L, 12L, 13L));
        squads.put(2L, Arrays.asList(21L, 22L, 23L));
        assertEquals("避开已占用英雄，选下一队", Arrays.asList(21L, 22L, 23L),
                ServerBearTasks.chooseSquadHeroes(squads, new LinkedHashSet<>(Arrays.asList(11L, 12L, 13L))));
        assertEquals("无冲突时选第一队", Arrays.asList(11L, 12L, 13L),
                ServerBearTasks.chooseSquadHeroes(squads, Collections.emptySet()));
        assertTrue("所有队都冲突→空（由调用方 SKIP）",
                ServerBearTasks.chooseSquadHeroes(squads, new LinkedHashSet<>(Arrays.asList(11L, 21L))).isEmpty());
        assertTrue("无布阵数据→空", ServerBearTasks.chooseSquadHeroes(null, null).isEmpty());
    }

    @Test
    public void heroIds_roundTripAndAppend() {
        List<Long> ids = ServerBearTasks.heroIdsOf(formation());
        assertEquals(Arrays.asList(11L, 12L, 13L), ids);
        String csv = ServerBearTasks.appendHeroIds("", ids);
        assertEquals("11,12,13", csv);
        assertEquals("追加并去重", "11,12,13,14",
                ServerBearTasks.appendHeroIds(csv, Arrays.asList(13L, 14L)));
        assertEquals(Arrays.asList(11L, 12L, 13L, 14L), ServerBearTasks.parseHeroIds(csv + ",14"));
        assertTrue(ServerBearTasks.parseHeroIds(null).isEmpty());
        assertTrue(ServerBearTasks.heroIdsOf(null).isEmpty());
    }

    // ======================= 4. 通知/响应解析 =======================

    @Test
    public void parseTrapTimes_schemaFields() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reserve_time", 1792760400L);
        content.put("mass_start_time", 1792587600L);
        content.put("mass_end_time", 1792589400L);
        content.put("cool_end_time", 1792758600L);
        ServerBearTasks.TrapTimes times = ServerBearTasks.parseTrapTimes(content);
        assertNotNull(times);
        assertEquals(START, times.reserve);
        assertEquals(1792587600L, times.start);
        assertEquals(1792589400L, times.end);
        assertEquals(1792758600L, times.cool);

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", content);
        ServerBearTasks.TrapTimes fromData = ServerBearTasks.parseTrapTimes(wrapped);
        assertNotNull("兼容 {data:{...}} 包装", fromData);
        assertEquals(START, fromData.reserve);

        Map<String, Object> camel = new LinkedHashMap<>();
        camel.put("reserveTime", 1792760400L);
        camel.put("massStartTime", 1792587600L);
        assertNotNull("兼容驼峰", ServerBearTasks.parseTrapTimes(camel));
        assertNull("无字段→null", ServerBearTasks.parseTrapTimes(new LinkedHashMap<>()));
    }

    @Test
    public void parseTrapTarget_fromMapobj() {
        Map<String, Object> trap = new LinkedHashMap<>();
        trap.put("id", 9001L);
        trap.put("aid", 777L);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("x", 100L);
        point.put("y", 200L);
        Map<String, Object> mapobj = new LinkedHashMap<>();
        mapobj.put("point", point);
        mapobj.put("map_alliance_trap", trap);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mapobj", mapobj);

        ServerBearTasks.TrapTarget target = ServerBearTasks.parseTrapTarget(content, 100, 200);
        assertNotNull(target);
        assertEquals(9001L, target.monsterId);
        assertEquals(100L, target.x);
        assertEquals(200L, target.y);
        assertEquals("熊坑归属联盟", 777L, target.allianceId);

        assertNull("没有 map_alliance_trap → 目标无效",
                ServerBearTasks.parseTrapTarget(new LinkedHashMap<>(), 100, 200));
    }

    @Test
    public void allianceTrapPoints_filtersType33AndUnfinished() {
        Map<String, Object> building = new LinkedHashMap<>();
        building.put("x", 100L);
        building.put("y", 200L);
        building.put("is_finish", true);
        Map<String, Object> unfinished = new LinkedHashMap<>();
        unfinished.put("x", 300L);
        unfinished.put("y", 400L);
        unfinished.put("is_finish", false);
        Map<String, Object> pointBuilding = new LinkedHashMap<>();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("x", 500L);
        point.put("y", 600L);
        pointBuilding.put("point", point);

        Map<String, Object> group33 = new LinkedHashMap<>();
        group33.put("mapobj_type", 33L);
        group33.put("building_list", Arrays.asList(building, unfinished, pointBuilding));
        Map<String, Object> groupOther = new LinkedHashMap<>();
        groupOther.put("mapobj_type", 39L);
        groupOther.put("building_list", Collections.singletonList(unfinished));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("building_type_2_list", Arrays.asList(group33, groupOther));
        content.put("building_list", Collections.singletonList(building));   // 与 group 内重复→去重

        List<long[]> points = ServerBearTasks.allianceTrapPoints(content);
        assertEquals("只保留 type=33 且已建成（含 point{x,y}）的熊坑并去重", 2, points.size());
        assertEquals(100L, points.get(0)[0]);
        assertEquals(500L, points.get(1)[0]);
    }

    @Test
    public void parseRallyCandidates_shapesAndTypeFilter() {
        Map<String, Object> march = bearRallyMarch(555L, 5L, 411L, 100L, 200L);
        Map<String, Object> other = bearRallyMarch(666L, 5L, 301L, 100L, 200L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marchs", Arrays.asList(march, other));
        List<ServerBearTasks.RallyCandidate> parsed = ServerBearTasks.parseRallyCandidates(content);
        assertEquals("只保留巨熊集结(type=411)", 1, parsed.size());
        assertEquals(555L, parsed.get(0).masterMarchId);

        Map<String, Object> add = new LinkedHashMap<>();
        add.put("march", march);
        assertEquals("兼容 notify_mass_march_add{march:{...}}", 1,
                ServerBearTasks.parseRallyCandidates(add).size());

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", content);
        assertEquals("兼容 {data:{marchs:[...]}}", 1,
                ServerBearTasks.parseRallyCandidates(wrapped).size());
    }

    @Test
    public void bearRallyJoinable_guardsMatchWeb() {
        long targetX = 100L;
        long targetY = 200L;
        ServerBearTasks.RallyCandidate c = candidate(555L, 5L, 411L, 5L, 9L, 100L, 200L);
        assertTrue("目标坐标/同联盟/非本人/未满 → 可加入",
                ServerBearTasks.bearRallyJoinable(c, targetX, targetY, 2L, 9L, T_MID));
        assertFalse("自己发起的集结不加入",
                ServerBearTasks.bearRallyJoinable(c, targetX, targetY, 5L, 9L, T_MID));
        c.state = 4L;
        assertFalse("state≠5 不加入", ServerBearTasks.bearRallyJoinable(c, targetX, targetY, 2L, 9L, T_MID));
        c.state = 5L;
        assertFalse("终点不是熊坑不加入",
                ServerBearTasks.bearRallyJoinable(c, 999L, 999L, 2L, 9L, T_MID));
        assertFalse("跨联盟不加入",
                ServerBearTasks.bearRallyJoinable(c, targetX, targetY, 2L, 10L, T_MID));
        c.endTime = T_MID;
        c.allianceId = 9L;
        assertFalse("已过期集结不加入",
                ServerBearTasks.bearRallyJoinable(c, targetX, targetY, 2L, 9L, T_MID));
        c.endTime = 0L;
        c.memberMax = 2L;
        c.memberCount = 2L;
        assertFalse("集结已满不加入", ServerBearTasks.bearRallyJoinable(c, targetX, targetY, 2L, 9L, T_MID));
    }

    @Test
    public void joinConfirmation_responseAndDetail() {
        Map<String, Object> slave = new LinkedHashMap<>();
        slave.put("master_marchid", 555L);
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("mass_slave", slave);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march_info", march);
        assertTrue("同步响应 mass_slave.master_marchid 确认",
                ServerBearTasks.responseConfirmsJoin(content, 555L));
        assertFalse("车头 id 不一致不确认", ServerBearTasks.responseConfirmsJoin(content, 777L));

        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("owner", 2L);
        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("owner", 9L);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("detail_list", Arrays.asList(d2, d1));
        assertTrue("成员详情含自己 → 对账成功", ServerBearTasks.memberPresent(detail, 2L));
        assertFalse("成员详情不含自己", ServerBearTasks.memberPresent(detail, 7L));
    }

    @Test
    public void window_reserveEqualsTrapStart_notDuplicated() {
        // reserve_time 与 mass_start_time 相同（生产 80971192 的历史场次形状）：仍只有一场进行中
        ServerBearTasks.Window w = ServerBearTasks.windowOf(T_START_PLUS_60, START, START, defaults());
        assertEquals(ServerBearTasks.Window.ACTIVE, w.phase);
        assertEquals(ServerBearTasks.Window.SRC_RESERVE, w.source);
        assertEquals(START, w.startSec);
    }

    @Test
    public void fallbackTrapSummary_mentionsConfiguredWindow() {
        ServerBearTasks.BearState state = new ServerBearTasks.BearState();
        ServerBearTasks.Window w = ServerBearTasks.windowOf(START, 0L, 0L, defaults());
        String defaultText = ServerBearTasks.trapSummary(state, w, false);
        assertTrue("未预约时明确文案", defaultText.contains("未预约"));
        assertTrue("说明配置观察窗口", defaultText.contains("配置观察窗口"));
        assertTrue("默认不自动发车", defaultText.contains("不自动发车"));

        String allowed = ServerBearTasks.trapSummary(state, w, true);
        assertTrue("显式放开后按配置窗口执行", allowed.contains("窗口内"));
        assertTrue(allowed.contains("未读取到预约"));
    }

    @Test
    public void fallbackWindowLaunchGuard_defaultSkip() {
        ServerBearTasks.Window fallback = ServerBearTasks.windowOf(START, 0L, 0L, defaults());
        assertTrue("无预约时配置窗口内默认 SKIP（不臆造场次）",
                ServerBearTasks.shouldSkipFallbackWindow(fallback, defaults()));
        assertFalse("预约窗口不触发该闸",
                ServerBearTasks.shouldSkipFallbackWindow(
                        ServerBearTasks.windowOf(T_MID, START, 0L, defaults()), defaults()));
        JSONObject p = new JSONObject();
        p.put("launch_without_reserve", true);
        assertFalse("显式 launch_without_reserve=true 才允许配置窗口发车",
                ServerBearTasks.shouldSkipFallbackWindow(fallback, ServerBearTasks.BearConfig.of(p)));
        assertFalse("null 安全", ServerBearTasks.shouldSkipFallbackWindow(null, defaults()));
    }

    // ======================= 5. 配置 / 触发判 / mod 文案 =======================

    @Test
    public void configOf_defaultsAndClamps() {
        ServerBearTasks.BearConfig cfg = ServerBearTasks.BearConfig.of(new JSONObject());
        assertTrue("默认发起开", cfg.launchEnabled);
        assertTrue("默认上车开", cfg.joinEnabled);
        assertFalse("可选清场默认关（不破坏用户队列）", cfg.prepareRecallEnabled);
        assertFalse("未预约默认不发车（配置窗口只观察）", cfg.launchWithoutReserve);
        assertEquals("上车编队默认 1 队", 1, cfg.joinCount);
        assertEquals("准备档默认 2（620 秒）", 2, cfg.prepareTimeIndex);
        assertEquals("默认周五", 5, cfg.windowWeekday);
        assertEquals("默认 21:00", 21 * 60, cfg.windowStartMinuteOfDay());
        assertEquals("默认 30 分钟窗口", 30, cfg.windowDurationMinutes());

        JSONObject p = new JSONObject();
        p.put("join_count", 9);
        p.put("prepare_time_index", 0);
        p.put("window_start_hour", 20);
        p.put("window_start_minute", 30);
        p.put("window_end_hour", 21);
        p.put("window_end_minute", 0);
        p.put("prepare_recall", "true");
        ServerBearTasks.BearConfig custom = ServerBearTasks.BearConfig.of(p);
        assertEquals("加入上限夹到 6", 6, custom.joinCount);
        assertEquals("准备档夹到 1", 1, custom.prepareTimeIndex);
        assertEquals("20:30", 20 * 60 + 30, custom.windowStartMinuteOfDay());
        assertEquals("20:30-21:00=30 分钟", 30, custom.windowDurationMinutes());
        assertTrue("布尔字符串兼容", custom.prepareRecallEnabled);
    }

    @Test
    public void needTrapSync_throttling() {
        assertTrue("首次必须同步", ServerBearTasks.needTrapSync(START, 0, START, 0, defaults()));
        assertFalse("窗口内 300s 内不重复同步",
                ServerBearTasks.needTrapSync(START, START - 100, START, 0, defaults()));
        assertTrue("窗口内超过 300s 再同步",
                ServerBearTasks.needTrapSync(START, START - 400, START, 0, defaults()));
        assertFalse("空闲期 1 小时不重复同步",
                ServerBearTasks.needTrapSync(T_1500, T_1500 - 3600, START, 0, defaults()));
        assertTrue("空闲期 6 小时同步一次",
                ServerBearTasks.needTrapSync(T_1500, T_1500 - 6 * 3600, START, 0, defaults()));
        assertTrue("时钟回拨/异常同步时刻→立即同步",
                ServerBearTasks.needTrapSync(START, START + 999, START, 0, defaults()));
    }

    @Test
    public void trapSummary_texts() {
        ServerBearTasks.BearState state = new ServerBearTasks.BearState();
        state.reserve = START;

        ServerBearTasks.Window active = ServerBearTasks.windowOf(T_MID, START, 0, defaults());
        String activeText = ServerBearTasks.trapSummary(state, active, false);
        assertTrue("窗口内文案", activeText.contains("窗口内"));
        assertTrue("已预约文案带北京时间", activeText.contains("已预约 10月23日 21:00"));

        ServerBearTasks.Window waiting = ServerBearTasks.windowOf(T_2000, START, 0, defaults());
        String waitingText = ServerBearTasks.trapSummary(state, waiting, false);
        assertTrue("等待时显示已预约", waitingText.contains("已预约 10月23日 21:00"));

        ServerBearTasks.BearState fresh = new ServerBearTasks.BearState();
        String noReserve = ServerBearTasks.trapSummary(fresh,
                ServerBearTasks.windowOf(T_2000, 0, 0, defaults()), false);
        assertTrue("未预约要有明确文案", noReserve.contains("未预约"));
        assertTrue("未预约提示需游戏内手动预约（无客户端预约协议）", noReserve.contains("游戏内手动预约"));
    }

    @Test
    public void phaseText_allPhases() {
        assertTrue(ServerBearTasks.phaseText(
                ServerBearTasks.windowOf(T_MID, START, 0, defaults())).contains("窗口进行中"));
        assertTrue(ServerBearTasks.phaseText(
                ServerBearTasks.windowOf(PREP_START, START, 0, defaults())).contains("准备窗口"));
        assertTrue(ServerBearTasks.phaseText(
                ServerBearTasks.windowOf(END, START, 0, defaults())).contains("已结束"));
        assertTrue(ServerBearTasks.phaseText(
                ServerBearTasks.windowOf(T_2000, START, 0, defaults())).contains("等待窗口"));
        assertEquals("未知窗口兜底文案", "未知", ServerBearTasks.phaseText(null));
    }

    @Test
    public void stateJson_roundTrip() {
        ServerBearTasks.BearState state = new ServerBearTasks.BearState();
        state.reserve = START;
        state.start = 1792587600L;
        state.end = 1792589400L;
        state.cool = 1792758600L;
        state.syncAt = T_MID;
        state.targetX = 100L;
        state.targetY = 200L;
        state.targetMonsterId = 9001L;
        state.targetAllianceId = 777L;
        state.launchHeroes = "11,12";
        state.joinHeroes = "21";
        ServerBearTasks.BearState back = ServerBearTasks.parseState(ServerBearTasks.stateJson(state));
        assertEquals(state.reserve, back.reserve);
        assertEquals(state.syncAt, back.syncAt);
        assertEquals(state.targetMonsterId, back.targetMonsterId);
        assertEquals(state.targetAllianceId, back.targetAllianceId);
        assertEquals(state.launchHeroes, back.launchHeroes);
        assertEquals(state.joinHeroes, back.joinHeroes);
        assertNotNull("坏 JSON 不抛异常", ServerBearTasks.parseState("{bad-json"));
    }

    @Test
    public void bearCodeText_knownCodes() {
        assertTrue(ServerBearTasks.bearCodeText(5204).contains("5204"));
        assertTrue(ServerBearTasks.bearCodeText(403).contains("403"));
        assertTrue(ServerBearTasks.bearCodeText(2844).contains("2844"));
        assertTrue(ServerBearTasks.bearCodeText(2878).contains("2878"));
        assertEquals("未知码不猜语义", "", ServerBearTasks.bearCodeText(9999));
    }

    @Test
    public void bearState_mergeNewerKeepsProgress() {
        ServerBearTasks.BearState state = new ServerBearTasks.BearState();
        state.reserve = START;
        state.start = 1792587600L;      // 上一场 mass_start_time
        ServerBearTasks.TrapTimes newer = new ServerBearTasks.TrapTimes();
        newer.reserve = NEXT_EVENT;      // 开赛后 reserve 翻到下一场
        newer.start = START;             // 本场 mass_start_time
        state.mergeNewer(newer);
        assertEquals("reserve 向前推进", NEXT_EVENT, state.reserve);
        assertEquals("mass_start_time 向前推进（兜底本场）", START, state.start);

        ServerBearTasks.TrapTimes older = new ServerBearTasks.TrapTimes();
        older.reserve = 1792587600L;
        older.start = 1792587600L;
        state.mergeNewer(older);
        assertEquals("旧推送不回退 reserve", NEXT_EVENT, state.reserve);
        assertEquals("旧推送不回退 start", START, state.start);
    }

    @Test
    public void syncTrapTimes_rejectsDegradedResponse() {
        com.carlos.vault.wjdr.protocol.GameMessage degraded =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("_unknown_proto_tag", 999L);
        degraded.setContent(bad);
        assertNull("降级/未知响应不当数据", ServerBearTasks.syncTrapTimes(degraded));
        assertNull("null 响应安全", ServerBearTasks.syncTrapTimes(null));

        com.carlos.vault.wjdr.protocol.GameMessage good =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reserve_time", START);
        good.setContent(content);
        ServerBearTasks.TrapTimes times = ServerBearTasks.syncTrapTimes(good);
        assertNotNull(times);
        assertEquals(START, times.reserve);
    }

    @Test
    public void isTruthy_onlyExplicitTrue() {
        assertTrue(ServerBearTasks.isTruthy(Boolean.TRUE));
        assertTrue(ServerBearTasks.isTruthy(1L));
        assertTrue(ServerBearTasks.isTruthy("1"));
        assertFalse(ServerBearTasks.isTruthy(null));
        assertFalse(ServerBearTasks.isTruthy(Boolean.FALSE));
        assertFalse(ServerBearTasks.isTruthy(0L));
    }

    // ======================= 辅助构造 =======================

    private static Map<String, Object> formation() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", Exec.heroPairs(Arrays.asList(11L, 12L, 13L)));
        f.put("soldier", Arrays.asList(Exec.kv(11000L, 1000L), Exec.kv(21000L, 500L)));
        return f;
    }

    private static Map<String, Object> bearRallyMarch(long id, long state, long type,
                                                      long endX, long endY) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", id);
        march.put("state", state);
        march.put("type", type);
        march.put("owner", 5L);
        march.put("kid", 3L);
        march.put("aid", 9L);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("x", 10L);
        start.put("y", 20L);
        march.put("startpoint", start);
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("x", endX);
        end.put("y", endY);
        march.put("endpoint", end);
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("max", 4L);
        mass.put("uids", Arrays.asList(1L, 2L));
        mass.put("soldier_count", 0L);
        mass.put("soldier_max", 0L);
        march.put("mass_alliance_trap", mass);
        return march;
    }

    private static ServerBearTasks.RallyCandidate candidate(long id, long state, long type,
                                                            long owner, long allianceId,
                                                            long endX, long endY) {
        ServerBearTasks.RallyCandidate c = new ServerBearTasks.RallyCandidate();
        c.masterMarchId = id;
        c.state = state;
        c.type = type;
        c.ownerUid = owner;
        c.allianceId = allianceId;
        c.startX = 10L;
        c.startY = 20L;
        c.endX = endX;
        c.endY = endY;
        c.memberMax = 4L;
        c.memberCount = 1L;
        return c;
    }

    // ==================== 2026-11-07：角色键隔离 / 宠物技能集合 ====================

    @Test
    public void roleScopedSuffix_isolatesUid() {
        assertEquals("48460403:888:35371154", ServerBearTasks.roleScopedSuffix(
                "48460403", 888L, 35_371_154L));
        assertEquals("48460403:888", ServerBearTasks.roleScopedSuffix("48460403", 888L, 0L));
    }

    @Test
    public void bearPetSkillIds_excludesRazorbeak() {
        long[] ids = ServerBearTasks.bearPetSkillIds();
        assertEquals("10 个战斗技能排除 550051 后剩 9 个", 9, ids.length);
        for (long id : ids) {
            assertTrue("不得包含 550051（锋锐铁喙）", id != 550051L);
        }
        assertTrue("应包含 550111（猛虎出山）", contains(ids, 550111L));
    }

    private static boolean contains(long[] ids, long want) {
        for (long id : ids) {
            if (id == want) return true;
        }
        return false;
    }

    // ==================== 2026-11-07：T-5 触发 / 召回目标解析 ====================

    @Test
    public void prepGateDue_onlyWithinFiveMinutesOncePerWindow() {
        long start = 1_793_970_000L;
        assertFalse("距开赛 301s 不触发", ServerBearTasks.prepGateDue(start, start - 301L, 0L));
        assertTrue("距开赛 300s 触发", ServerBearTasks.prepGateDue(start, start - 300L, 0L));
        assertTrue("开赛后（ACTIVE）未执行则补做", ServerBearTasks.prepGateDue(start, start + 5L, 0L));
        assertFalse("同场已执行不重复", ServerBearTasks.prepGateDue(start, start - 100L, start));
    }

    @Test
    public void ownMarchTargets_skipsBearAndJoinRallies() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("id", 1L);
        m1.put("type", 4L);
        m1.put("state", 3L);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("id", 2L);
        m2.put("type", 99L);
        m2.put("state", 2L);
        Map<String, Object> m3 = new LinkedHashMap<>();
        m3.put("id", 3L);
        m3.put("type", 411L);
        m3.put("state", 2L);
        Map<String, Object> m4 = new LinkedHashMap<>();
        m4.put("id", 4L);
        m4.put("type", 2L);
        m4.put("state", 2L);
        Map<String, Object> m5 = new LinkedHashMap<>();
        m5.put("id", 5L);
        m5.put("type", 999L);
        m5.put("state", 2L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marches", Arrays.asList(m1, m2, m3, m4, m5));

        WjdrEngine engine = new WjdrEngine(null) {
            @Override
            public Map<String, Object> notifyContent(String proto) {
                return "notify_player_march_add".equals(proto) ? content : null;
            }
        };
        List<long[]> out = ServerBearTasks.ownMarchTargets(engine);
        assertEquals("只保留采集/进攻行军（排除 99/411/未知类型）", 2, out.size());
        assertEquals(1L, out.get(0)[0]);
        assertEquals(3L, out.get(0)[1]);
        assertEquals(4L, out.get(1)[0]);
        assertEquals(2L, out.get(1)[1]);
    }

    // ==================== 2026-11-10：实时行军列表兜底 ====================

    @Test
    public void liveMarchTargets_fallsBackToSnapshotsWhenAssetLibraryMissing() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("id", 11L);
        m1.put("type", 4L);
        m1.put("state", 3L);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("id", 12L);
        m2.put("type", 99L);
        m2.put("state", 2L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("marches", Arrays.asList(m1, m2));
        WjdrEngine engine = new WjdrEngine(null) {
            @Override
            public Map<String, Object> notifyContent(String proto) {
                return "notify_player_march_add".equals(proto) ? content : null;
            }
        };
        List<long[]> targets = ServerBearTasks.liveMarchTargets(engine, 1_793_000_000L);
        assertEquals("资产库缺失时回退旧快照，且排除 99 集结", 1, targets.size());
        assertEquals(11L, targets.get(0)[0]);
    }
}
