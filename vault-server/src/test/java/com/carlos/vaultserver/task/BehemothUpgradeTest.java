package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次8（task-27）：巨兽狩猎升级纯函数单测（1:1 基准网页版 DailyBehemothService /
 * BehemothLaunchFacade / BehemothAutomationService / BehemothStaminaItemSupport）。
 *
 * <p>覆盖：配置（发起默认关/各级夹取/sub 开关兼容）、等级倒序、准备档、体力（自然恢复外推/道具数量/
 * 等待秒数）、发起/上车 payload、mapobj 目标解析、集结候选解析与过滤、失败码文案、wakeAt、台账日滚动。
 * 不连真协议；时间常量 = 2026-10-23 21:10 北京时间（生产巨熊窗口同夜，便于对照）。
 */
public class BehemothUpgradeTest {

    /** 2026-10-23 21:10 北京时间（epoch 秒）。 */
    private static final long NOW = 1792761000L;
    /** 2026-10-24 00:00 北京时间。 */
    private static final long NEXT_DAY_START = 1792771200L;

    // ======================= 配置 =======================

    @Test
    public void config_defaults_safe() {
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(new JSONObject());
        assertTrue("原生自动上车保留默认开（既有行为）", cfg.autoJoinEnabled);
        assertFalse("每日发起默认关（批次安全口径）", cfg.launchEnabled);
        assertEquals("发起上限默认 1 次/日", 1, cfg.launchLimit);
        assertEquals("队列数默认 1", 1, cfg.queueCount);
        assertTrue("手工上车默认开（与原生互斥）", cfg.joinEnabled);
        assertEquals("手工上车默认 1 队", 1, cfg.joinLimit);
        assertFalse("默认不与原生自动上车叠加", cfg.manualJoinWithAutoJoin);
        assertEquals(1, cfg.minLevel);
        assertEquals(8, cfg.maxLevel);
        assertEquals("准备档默认 1（320s）", 1, cfg.prepareTimeIndex);
        assertEquals("体力保留默认 50", 50L, cfg.staminaReserve);
        assertFalse("体力道具默认关", cfg.autoUseStaminaItem);
        assertEquals("原生自动上车队列上限默认 3（保持现状）", 3, cfg.autoJoinMarchLimit);
        assertEquals(Arrays.asList(1L, 4L, 3L), cfg.autoJoinIds);
        assertEquals("默认队1", 1, cfg.squadIndex);
        assertTrue("默认无 hero_ids", cfg.heroIds.isEmpty());
    }

    @Test
    public void config_clampsAndParses() {
        JSONObject p = new JSONObject();
        p.put("launch_enabled", true);
        p.put("launch_limit", 999);
        p.put("queue_count", 9);
        p.put("join_limit", 0);
        p.put("min_level", 0);
        p.put("max_level", 99);
        p.put("prepare_time_index", 5);
        p.put("stamina_reserve", 999);
        p.put("auto_use_stamina_item", true);
        p.put("auto_join_march_limit", 9);
        p.put("auto_join_ids", "3,1");
        p.put("hero_ids", "101,102,103,104");
        p.put("squad_index", 9);
        p.put("force_max_level", true);
        p.put("manual_join_with_auto_join", true);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        assertTrue(cfg.launchEnabled);
        assertEquals("launch_limit 夹到 100", 100, cfg.launchLimit);
        assertEquals("queue_count 夹到 6", 6, cfg.queueCount);
        assertEquals("join_limit 夹到 1", 1, cfg.joinLimit);
        assertEquals(1, cfg.minLevel);
        assertEquals(8, cfg.maxLevel);
        assertEquals("prepare 夹到 4", 4, cfg.prepareTimeIndex);
        assertEquals(500L, cfg.staminaReserve);
        assertTrue("显式开关才用体力道具", cfg.autoUseStaminaItem);
        assertEquals(6, cfg.autoJoinMarchLimit);
        assertEquals(Arrays.asList(3L, 1L), cfg.autoJoinIds);
        assertEquals("hero_ids 最多 3 名", Arrays.asList(101L, 102L, 103L), cfg.heroIds);
        assertEquals("squad_index 夹到 6", 6, cfg.squadIndex);
        assertTrue(cfg.forceMaxLevel);
        assertTrue(cfg.manualJoinWithAutoJoin);
    }

    @Test
    public void config_subFlagsCompatibleWithApp() {
        JSONObject sub = new JSONObject();
        sub.put("auto_join", false);
        sub.put("launch", true);
        sub.put("join", false);
        JSONObject p = new JSONObject();
        p.put("sub", sub);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        assertFalse("App sub.auto_join=false 生效（保留既有开关语义）", cfg.autoJoinEnabled);
        assertTrue("sub.launch=true 开启每日发起", cfg.launchEnabled);
        assertFalse("sub.join=false 关闭手工上车", cfg.joinEnabled);
    }

    @Test
    public void effectiveQueueCount_minOfConfigAndCapacity() {
        JSONObject p = new JSONObject();
        p.put("queue_count", 2);
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(p);
        assertEquals(2, cfg.effectiveQueueCount(3));
        assertEquals("容量更小取容量", 1, cfg.effectiveQueueCount(1));
        assertEquals("容量未知回退配置", 2, cfg.effectiveQueueCount(0));
    }

    // ======================= 等级/准备档 =======================

    @Test
    public void levels_maxToMinDescending() {
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(new JSONObject());
        List<Integer> levels = ServerTasks.behemothLevels(cfg);
        assertEquals("默认 8→1 共 8 级", 8, levels.size());
        assertEquals(Integer.valueOf(8), levels.get(0));
        assertEquals(Integer.valueOf(1), levels.get(levels.size() - 1));

        JSONObject p = new JSONObject();
        p.put("min_level", 3);
        p.put("max_level", 6);
        ServerTasks.BehemothConfig custom = ServerTasks.BehemothConfig.of(p);
        assertEquals(Arrays.asList(6, 5, 4, 3), ServerTasks.behemothLevels(custom));

        JSONObject force = new JSONObject();
        force.put("min_level", 3);
        force.put("max_level", 6);
        force.put("force_max_level", true);
        ServerTasks.BehemothConfig forced = ServerTasks.BehemothConfig.of(force);
        assertEquals("force_max_level 只搜最高级", Arrays.asList(6), ServerTasks.behemothLevels(forced));
        assertTrue(ServerTasks.behemothLevels(null).size() > 0);
    }

    @Test
    public void prepareSeconds_mapping() {
        assertEquals(320, ServerTasks.behemothPrepareSeconds(1));
        assertEquals(620, ServerTasks.behemothPrepareSeconds(2));
        assertEquals(1820, ServerTasks.behemothPrepareSeconds(3));
        assertEquals(3620, ServerTasks.behemothPrepareSeconds(4));
        assertEquals("越界夹到 1", 320, ServerTasks.behemothPrepareSeconds(0));
        assertEquals("越界夹到 4", 3620, ServerTasks.behemothPrepareSeconds(9));
    }

    // ======================= 体力 =======================

    @Test
    public void staminaItemCount_deficitToItems() {
        assertEquals(0, ServerTasks.behemothStaminaItemCount(0));
        assertEquals(0, ServerTasks.behemothStaminaItemCount(-5));
        assertEquals(1, ServerTasks.behemothStaminaItemCount(10));
        assertEquals(2, ServerTasks.behemothStaminaItemCount(11));
        assertEquals("单次最多 6 颗", 6, ServerTasks.behemothStaminaItemCount(1000));
    }

    @Test
    public void stamina_currentAt_includesNaturalRecovery() {
        ServerTasks.BehemothStamina st = stamina(100, 150, 10, 1000, 60);
        assertEquals(100, st.currentAt(999));
        assertEquals("首个恢复点 +10", 110, st.currentAt(1000));
        assertEquals(110, st.currentAt(1059));
        assertEquals(120, st.currentAt(1060));
        assertEquals("不超过上限", 150, st.currentAt(5000));

        ServerTasks.BehemothStamina unknown = new ServerTasks.BehemothStamina();
        assertEquals(0, unknown.currentAt(NOW));
        assertEquals("无恢复计划时返回基础值", 80,
                stamina(80, 150, 10, 0, 0).currentAt(NOW));
    }

    @Test
    public void stamina_waitSeconds_toRequired() {
        // 60 → 需 75：缺 15，每 10 恢复 → 2 个周期；下个恢复点 now+30，间隔 60 → now+90
        ServerTasks.BehemothStamina st = stamina(60, 150, 10, NOW + 30, 60);
        assertEquals(90, ServerTasks.behemothStaminaWaitSeconds(st, 75, NOW));
        // 已满足 → 0
        assertEquals(0, ServerTasks.behemothStaminaWaitSeconds(st, 60, NOW));
        // 下个恢复点已过：currentAt 已含刚恢复的 +10（60→70），还差 5 → 再等 1 个周期 = now+50
        ServerTasks.BehemothStamina passed = stamina(60, 150, 10, NOW - 10, 60);
        assertEquals(50, ServerTasks.behemothStaminaWaitSeconds(passed, 75, NOW));
        // 目标超过上限 → 0（等不到）
        assertEquals(0, ServerTasks.behemothStaminaWaitSeconds(st, 999, NOW));
        // 未知体力 → 0
        assertEquals(0, ServerTasks.behemothStaminaWaitSeconds(
                new ServerTasks.BehemothStamina(), 75, NOW));
    }

    @Test
    public void staminaFromContent_listAndMapShapes() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 201L);
        row.put("count", 120L);
        row.put("max_count", 150L);
        row.put("recover", 10L);
        row.put("next_calc_ts", NOW + 60);
        row.put("interval", 60L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("data", new ArrayList<>(Arrays.asList(row)));
        ServerTasks.BehemothStamina st = ServerTasks.behemothStaminaFromContent(content);
        assertNotNull(st);
        assertTrue(st.hasCurrent);
        assertEquals(120L, st.current);
        assertEquals(150L, st.max);
        assertEquals(10L, st.recover);
        assertEquals(NOW + 60, st.nextRecoverAt);
        assertEquals(60L, st.interval);

        Map<String, Object> byIdRow = new LinkedHashMap<>();
        byIdRow.put("count", 80L);
        Map<String, Object> idMap = new LinkedHashMap<>();
        idMap.put("201", byIdRow);
        Map<String, Object> mapShape = new LinkedHashMap<>();
        mapShape.put("data", idMap);
        ServerTasks.BehemothStamina fromMap = ServerTasks.behemothStaminaFromContent(mapShape);
        assertNotNull("兼容 data:{201:{...}} 形状", fromMap);
        assertEquals(80L, fromMap.current);

        Map<String, Object> other = new LinkedHashMap<>();
        other.put("id", 205L);
        Map<String, Object> no201 = new LinkedHashMap<>();
        no201.put("data", new ArrayList<>(Arrays.asList(other)));
        assertNull("非 201（体力）行忽略", ServerTasks.behemothStaminaFromContent(no201));
        assertNull(ServerTasks.behemothStaminaFromContent(null));
    }

    // ======================= payload =======================

    @Test
    public void launchPayload_shapeMatchesWeb() {
        Map<String, Object> payload = ServerTasks.behemothLaunchPayload(
                300L, 400L, 9001L, formation(), 9);
        assertNotNull(payload);
        assertEquals("发起 type=102", 102L, Exec.longOf(payload.get("type")));
        Map<String, Object> endpoint = Exec.mapOf(payload.get("endpoint"));
        assertNotNull(endpoint);
        assertEquals(300L, Exec.longOf(endpoint.get("x")));
        assertEquals(400L, Exec.longOf(endpoint.get("y")));
        Map<String, Object> extra = Exec.mapOf(payload.get("extra"));
        assertNotNull(extra);
        assertEquals(9001L, Exec.longOf(extra.get("monsterid")));
        assertEquals("准备档夹到 4", 4L, Exec.longOf(extra.get("prepare_time_index")));
        assertEquals("非法坐标拒绝", null, ServerTasks.behemothLaunchPayload(0, 1, 2, formation(), 1));
        assertEquals("无编队拒绝", null, ServerTasks.behemothLaunchPayload(1, 1, 2, null, 1));
        Map<String, Object> noSoldier = new LinkedHashMap<>();
        noSoldier.put("hero_id", Arrays.asList(Exec.kv(11L, 1L)));
        noSoldier.put("soldier", new ArrayList<>());
        assertEquals("无兵力拒绝", null, ServerTasks.behemothLaunchPayload(1, 1, 2, noSoldier, 1));
    }

    @Test
    public void joinPayload_shapeMatchesWeb() {
        Map<String, Object> payload = ServerTasks.behemothJoinPayload(555L, 10L, 20L, formation());
        assertNotNull(payload);
        assertEquals("上车 type=99", 99L, Exec.longOf(payload.get("type")));
        Map<String, Object> extra = Exec.mapOf(payload.get("extra"));
        assertNotNull(extra);
        assertEquals(555L, Exec.longOf(extra.get("master_marchid")));
        assertEquals(null, ServerTasks.behemothJoinPayload(0, 10, 20, formation()));
        assertEquals(null, ServerTasks.behemothJoinPayload(1, 0, 20, formation()));
    }

    @Test
    public void formationReady_validation() {
        assertTrue(ServerTasks.behemothFormationReady(formation()));
        assertFalse(ServerTasks.behemothFormationReady(null));
        assertFalse(ServerTasks.behemothFormationReady(new LinkedHashMap<>()));
        Map<String, Object> noHero = new LinkedHashMap<>();
        noHero.put("hero_id", new ArrayList<>());
        noHero.put("soldier", Arrays.asList(Exec.kv(11000L, 1000L)));
        assertFalse(ServerTasks.behemothFormationReady(noHero));
    }

    // ======================= mapobj / 集结候选 =======================

    @Test
    public void bossTarget_parseMapObj() {
        Map<String, Object> boss = new LinkedHashMap<>();
        boss.put("id", 9001L);
        boss.put("march_count", 2L);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("x", 100L);
        point.put("y", 200L);
        Map<String, Object> mapobj = new LinkedHashMap<>();
        mapobj.put("type", 4L);
        mapobj.put("point", point);
        mapobj.put("map_boss", boss);
        ServerTasks.BehemothBossTarget target = ServerTasks.parseBehemothBossTarget(mapobj, 6, 0, 0);
        assertNotNull(target);
        assertEquals(9001L, target.monsterId);
        assertEquals(100L, target.x);
        assertEquals(200L, target.y);
        assertEquals(2L, target.marchCount);
        assertEquals(6, target.level);

        assertEquals("type≠4 拒绝", null, ServerTasks.parseBehemothBossTarget(
                mapobjOf(5L, boss), 1, 1, 1));
        assertEquals("缺 monsterid 拒绝", null, ServerTasks.parseBehemothBossTarget(
                mapobjOf(4L, new LinkedHashMap<>()), 1, 1, 1));
        Map<String, Object> altBoss = new LinkedHashMap<>();
        altBoss.put("monster_id", 777L);
        ServerTasks.BehemothBossTarget alt = ServerTasks.parseBehemothBossTarget(
                mapobjOf(4L, altBoss), 3, 11, 22);
        assertNotNull("兼容 monster_id 且坐标回退查询点", alt);
        assertEquals(11L, alt.x);
        assertEquals(22L, alt.y);
    }

    @Test
    public void rallies_parseAndFilter() {
        Map<String, Object> march = bossMarch(555L, 102L, 5L, 10L, 20L, 0L, 4L, 1);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", march);
        List<ServerTasks.BossRally> parsed = ServerTasks.parseBehemothRallies(content, NOW, 2L);
        assertEquals(1, parsed.size());
        assertEquals(555L, parsed.get(0).masterMarchId);
        assertEquals(10L, parsed.get(0).startX);
        assertEquals(20L, parsed.get(0).startY);

        // 多帧/包装：{data:{marchs:[...]}}
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("marchs", Arrays.asList(march, bossMarch(556L, 5004L, 6L, 30L, 40L, 0L, 4L, 1)));
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", inner);
        assertEquals("type=5004 也是巨兽集结", 2,
                ServerTasks.parseBehemothRallies(wrapped, NOW, 2L).size());

        // 过滤：自己 / 非法 type / 已完成 / 已过期 / 已满员
        assertEquals(0, ServerTasks.parseBehemothRallies(content, NOW, 5L).size());
        assertEquals(0, ServerTasks.parseBehemothRallies(
                singleMarch(bossMarch(1L, 411L, 5L, 10L, 20L, 0L, 4L, 1)), NOW, 2L).size());
        assertEquals(0, ServerTasks.parseBehemothRallies(
                singleMarch(bossMarch(1L, 102L, 5L, 10L, 20L, 0L, 4L, 1, 4L)), NOW, 2L).size());
        assertEquals(0, ServerTasks.parseBehemothRallies(
                singleMarch(bossMarch(1L, 102L, 5L, 10L, 20L, NOW - 1L, 4L, 1)), NOW, 2L).size());
        assertEquals(0, ServerTasks.parseBehemothRallies(
                singleMarch(bossMarch(1L, 102L, 5L, 10L, 20L, 0L, 1L, 1)), NOW, 2L).size());
        assertEquals("缺 startpoint 拒绝", 0, ServerTasks.parseBehemothRallies(
                singleMarch(bossMarch(1L, 102L, 5L, 0L, 0L, 0L, 4L, 1)), NOW, 2L).size());
    }

    // ======================= 失败码 / 唤醒 =======================

    @Test
    public void codeText_knownCodes() {
        assertTrue(ServerTasks.behemothCodeText(2872).contains("2872"));
        assertTrue(ServerTasks.behemothCodeText(2851).contains("2851"));
        assertTrue(ServerTasks.behemothCodeText(2844).contains("2844"));
        assertTrue(ServerTasks.behemothCodeText(2845).contains("2845"));
        assertTrue(ServerTasks.behemothCodeText(2846).contains("2846"));
        assertTrue(ServerTasks.behemothCodeText(51).contains("体力不足"));
        assertTrue(ServerTasks.behemothCodeText(1507).contains("1507"));
        assertTrue(ServerTasks.behemothCodeText(1600).contains("1600"));
        assertTrue(ServerTasks.behemothCodeText(401).contains("401"));
        assertTrue(ServerTasks.behemothCodeText(8637).contains("8637"));
        assertEquals("未知码不猜语义", "", ServerTasks.behemothCodeText(9999));
    }

    @Test
    public void wakeAt_launchDisabled_noOverride() {
        ServerTasks.BehemothConfig cfg = ServerTasks.BehemothConfig.of(new JSONObject());
        assertEquals(0L, ServerTasks.behemothWakeAtMillis(NOW, cfg,
                behemothState(0), healthyStamina(), 75L, false));
        assertEquals("state 为 null 安全", 0L, ServerTasks.behemothWakeAtMillis(
                NOW, cfg, null, healthyStamina(), 75L, false));
    }

    @Test
    public void wakeAt_limitReached_nextBeijingDay() {
        ServerTasks.BehemothConfig cfg = launchEnabledConfig(5);
        assertEquals("次日 00:05 再跑",
                (NEXT_DAY_START + 300L) * 1000L,
                ServerTasks.behemothWakeAtMillis(NOW, cfg, behemothState(5),
                        healthyStamina(), 75L, false));
    }

    @Test
    public void wakeAt_lowStamina_waitRecovery() {
        ServerTasks.BehemothConfig cfg = launchEnabledConfig(5);
        ServerTasks.BehemothStamina low = stamina(60, 150, 10, NOW + 30, 60);
        long wait = ServerTasks.behemothStaminaWaitSeconds(low, 75, NOW);
        assertEquals("体力不足按自然恢复到保留+25 的时刻唤醒",
                (NOW + Math.min(wait, 3600L)) * 1000L,
                ServerTasks.behemothWakeAtMillis(NOW, cfg, behemothState(0), low, 75L, false));
    }

    @Test
    public void wakeAt_justLaunched_recheck() {
        ServerTasks.BehemothConfig cfg = launchEnabledConfig(5);
        assertEquals("发起成功后 180s 复查",
                (NOW + 180L) * 1000L,
                ServerTasks.behemothWakeAtMillis(NOW, cfg, behemothState(1),
                        healthyStamina(), 75L, true));
        assertEquals("普通轮次不覆盖节奏", 0L,
                ServerTasks.behemothWakeAtMillis(NOW, cfg, behemothState(1),
                        healthyStamina(), 75L, false));
    }

    @Test
    public void nextDayWakeSec_beijingMidnightPlusOffset() {
        assertEquals(NEXT_DAY_START + 300L, ServerTasks.behemothNextDayWakeSec(NOW, 300L));
        assertEquals(NEXT_DAY_START, ServerTasks.behemothNextDayWakeSec(NOW, 0L));
    }

    // ======================= 台账日滚动 =======================

    @Test
    public void state_rollDay_resetsOnNewDayOnly() {
        ServerTasks.BehemothState st = new ServerTasks.BehemothState();
        st.day = "2026-10-22";
        st.launchCount = 2;
        st.joinCount = 1;
        st.lastLaunchLevel = 5;
        st.lastJoinMasterId = 99L;
        st.rollDay(NOW);
        assertEquals("2026-10-23", st.day);
        assertEquals("跨日清零发起次数", 0, st.launchCount);
        assertEquals("跨日清零上车次数", 0, st.joinCount);
        assertEquals(-1, st.lastLaunchLevel);
        assertEquals(0L, st.lastJoinMasterId);

        st.launchCount = 1;
        st.rollDay(NOW + 60L);
        assertEquals("同日不清零", 1, st.launchCount);
    }

    // ======================= 辅助构造 =======================

    private static Map<String, Object> formation() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", Arrays.asList(Exec.kv(11L, 1L), Exec.kv(12L, 1L), Exec.kv(13L, 1L)));
        f.put("soldier", Arrays.asList(Exec.kv(11000L, 1000L), Exec.kv(21000L, 500L)));
        return f;
    }

    private static ServerTasks.BehemothStamina stamina(long current, long max, long recover,
                                                       long nextRecoverAt, long interval) {
        ServerTasks.BehemothStamina st = new ServerTasks.BehemothStamina();
        st.hasCurrent = true;
        st.current = current;
        st.max = max;
        st.recover = recover;
        st.nextRecoverAt = nextRecoverAt;
        st.interval = interval;
        return st;
    }

    private static ServerTasks.BehemothStamina healthyStamina() {
        return stamina(120, 150, 10, NOW + 60, 60);
    }

    private static ServerTasks.BehemothState behemothState(int launchCount) {
        ServerTasks.BehemothState st = new ServerTasks.BehemothState();
        st.day = "2026-10-23";
        st.launchCount = launchCount;
        return st;
    }

    private static ServerTasks.BehemothConfig launchEnabledConfig(int limit) {
        JSONObject p = new JSONObject();
        p.put("launch_enabled", true);
        p.put("launch_limit", limit);
        return ServerTasks.BehemothConfig.of(p);
    }

    private static Map<String, Object> mapobjOf(long type, Map<String, Object> boss) {
        Map<String, Object> mapobj = new LinkedHashMap<>();
        mapobj.put("type", type);
        mapobj.put("map_boss", boss);
        return mapobj;
    }

    private static Map<String, Object> singleMarch(Map<String, Object> march) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("march", march);
        return content;
    }

    private static Map<String, Object> bossMarch(long id, long type, long owner, long sx, long sy,
                                                 long endAt, long memberMax, int memberCount) {
        return bossMarch(id, type, owner, sx, sy, endAt, memberMax, memberCount, 5L);
    }

    private static Map<String, Object> bossMarch(long id, long type, long owner, long sx, long sy,
                                                 long endAt, long memberMax, int memberCount,
                                                 long state) {
        Map<String, Object> march = new LinkedHashMap<>();
        march.put("id", id);
        march.put("type", type);
        march.put("owner", owner);
        march.put("kid", 3L);
        march.put("state", state);
        march.put("endtime", endAt);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("x", sx);
        start.put("y", sy);
        march.put("startpoint", start);
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("max", memberMax);
        List<Long> uids = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) uids.add((long) (100 + i));
        mass.put("uids", uids);
        march.put("mass_boss", mass);
        return march;
    }
}
