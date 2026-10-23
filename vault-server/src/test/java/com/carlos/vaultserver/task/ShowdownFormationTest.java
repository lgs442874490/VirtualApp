package com.carlos.vaultserver.task;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次6：联盟争霸赛完整编队（5:2:3 + hero_ids/formation_index 覆盖）、赛季/已报名幂等、
 * 战报只读解析、窗口判定的纯函数单测（不依赖真引擎/真协议）。
 */
public class ShowdownFormationTest {

    // ================= 测试辅助 =================

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
        return out;
    }

    private static List<Object> l(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static long epoch(String localDateTime) {
        return LocalDateTime.parse(localDateTime)
                .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    // ================= 5:2:3 兵力分配 =================

    @Test
    public void soldierCounts_default_5_2_3() {
        long[] counts = ServerShopLeagueTasks.showdownSoldierCounts(
                new long[]{50_000L, 20_000L, 30_000L}, ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP);
        assertEquals("盾=50%", 25_000L, counts[0]);
        assertEquals("矛=20%", 10_000L, counts[1]);
        assertEquals("弓=剩余容量（夹在 30% 上限内）", 15_000L, counts[2]);
        assertEquals(ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP,
                counts[0] + counts[1] + counts[2]);
    }

    @Test
    public void soldierCounts_clampedByPerLineStock() {
        long[] counts = ServerShopLeagueTasks.showdownSoldierCounts(
                new long[]{1_000L, 100_000L, 100_000L}, ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP);
        assertEquals("盾库存不足按实夹取", 1_000L, counts[0]);
        assertEquals("矛=20%", 10_000L, counts[1]);
        assertEquals("弓吃掉剩余容量", 39_000L, counts[2]);
        assertEquals(ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP,
                counts[0] + counts[1] + counts[2]);
    }

    @Test
    public void soldierCounts_cappedAt50k() {
        long[] counts = ServerShopLeagueTasks.showdownSoldierCounts(
                new long[]{900_000L, 900_000L, 900_000L}, ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP);
        assertEquals("单队容量上限 50000（防服务端 code=2）",
                ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP, counts[0] + counts[1] + counts[2]);
    }

    @Test
    public void soldierCounts_zeroStock() {
        assertArrayEquals("兵力未同步 → 全 0（调用方 SKIP）",
                new long[]{0L, 0L, 0L},
                ServerShopLeagueTasks.showdownSoldierCounts(new long[]{0L, 0L, 0L},
                        ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP));
        assertArrayEquals(new long[]{0L, 0L, 0L},
                ServerShopLeagueTasks.showdownSoldierCounts(null,
                        ServerShopLeagueTasks.SHOWDOWN_SOLDIER_CAP));
    }

    // ================= 编队选择：AUTOMATIC / hero_ids / formation_index =================

    private static List<Long> heroes(long... ids) {
        List<Long> out = new ArrayList<>();
        for (long id : ids) out.add(id);
        return out;
    }

    private static final long[] STOCK = {50_000L, 20_000L, 30_000L};
    private static final long[] SOLDIER_IDS = {11100L, 21100L, 31100L};

    @Test
    public void formation_automatic_usesStrongestFiveHeroes() {
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(new JSONObject(), null,
                        heroes(11, 12, 13, 14, 15, 16, 17), STOCK, SOLDIER_IDS);
        assertTrue(formation.ready());
        assertNull(formation.error());
        assertEquals("AUTOMATIC 默认 5 英雄", 5, formation.heroIds().size());
        assertEquals(Long.valueOf(11L), formation.heroIds().get(0));
        assertEquals(Long.valueOf(15L), formation.heroIds().get(4));
        assertTrue(formation.source().contains("AUTOMATIC"));
    }

    @Test
    public void formation_heroIdsOverride() {
        JSONObject p = new JSONObject();
        JSONArray ids = new JSONArray();
        ids.put(101).put(102).put(103).put(104).put(105).put(106).put(102).put(0);
        p.put("hero_ids", ids);
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(p, null, heroes(11, 12), STOCK,
                        SOLDIER_IDS);
        assertTrue(formation.ready());
        assertEquals("hero_ids 覆盖且上限 5 英雄", 5, formation.heroIds().size());
        assertTrue("去重/非法值过滤", formation.heroIds().contains(Long.valueOf(101L)));
        assertFalse(formation.heroIds().contains(Long.valueOf(0L)));
        assertEquals("同 hero_id 只出现一次", 1,
                formation.heroIds().stream().filter(id -> id == 102L).count());
        assertTrue(formation.source().contains("hero_ids"));
    }

    @Test
    public void formation_heroIdsStringOverride() {
        JSONObject p = new JSONObject();
        p.put("hero_ids", "7, 8，9");
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(p, null, heroes(11, 12, 13),
                        STOCK, SOLDIER_IDS);
        assertTrue(formation.ready());
        assertEquals(3, formation.heroIds().size());
        assertEquals(Long.valueOf(9L), formation.heroIds().get(2));
    }

    @Test
    public void formation_indexOverridesAutomatic() {
        JSONObject p = new JSONObject();
        p.put("formation_index", 2);
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(p, heroes(21, 22, 23),
                        heroes(11, 12, 13), STOCK, SOLDIER_IDS);
        assertTrue(formation.ready());
        assertEquals(3, formation.heroIds().size());
        assertEquals(Long.valueOf(21L), formation.heroIds().get(0));
        assertTrue(formation.source().contains("客户端编队2"));
    }

    @Test
    public void formation_heroIdsPriorityOverFormationIndex() {
        JSONObject p = new JSONObject();
        p.put("formation_index", 1);
        p.put("hero_ids", new JSONArray().put(301).put(302));
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(p, heroes(21, 22),
                        heroes(11, 12), STOCK, SOLDIER_IDS);
        assertEquals(Long.valueOf(301L), formation.heroIds().get(0));
        assertTrue("hero_ids 优先于 formation_index", formation.source().contains("hero_ids"));
    }

    @Test
    public void formation_missingSquadFallsBackToAutomatic() {
        JSONObject p = new JSONObject();
        p.put("formation_index", 3);
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(p, null, heroes(11, 12),
                        STOCK, SOLDIER_IDS);
        assertTrue(formation.ready());
        assertTrue("指定编队不存在 → AUTOMATIC", formation.source().contains("AUTOMATIC"));
    }

    @Test
    public void formation_noHeroes_skipReason() {
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(new JSONObject(), null,
                        List.of(), STOCK, SOLDIER_IDS);
        assertFalse(formation.ready());
        assertNotNull(formation.error());
        assertTrue(formation.error().contains("没有可用英雄"));
    }

    @Test
    public void formation_noSoldiers_skipReason() {
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(new JSONObject(), null,
                        heroes(11, 12), new long[]{0L, 0L, 0L}, SOLDIER_IDS);
        assertFalse(formation.ready());
        assertTrue(formation.error().contains("兵力未同步"));
    }

    @Test
    public void formation_missingSoldierIds_skipReason() {
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(new JSONObject(), null,
                        heroes(11, 12), STOCK, null);
        assertFalse(formation.ready());
        assertTrue(formation.error().contains("兵种数据未同步"));
    }

    @Test
    public void formationPayload_shape() {
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(new JSONObject(), null,
                        heroes(11, 12, 13, 14, 15), STOCK, SOLDIER_IDS);
        Map<String, Object> payload = ServerShopLeagueTasks.showdownFormationPayload(formation,
                SOLDIER_IDS);
        List<?> heroPairs = (List<?>) payload.get("hero_id");
        List<?> soldierPairs = (List<?>) payload.get("soldier");
        assertEquals(5, heroPairs.size());
        assertEquals(3, soldierPairs.size());
        Map<?, ?> firstHero = (Map<?, ?>) heroPairs.get(0);
        assertEquals(11L, Exec.longOf(firstHero.get("k")));
        assertEquals(1L, Exec.longOf(firstHero.get("v")));
        Map<?, ?> firstSoldier = (Map<?, ?>) soldierPairs.get(0);
        assertEquals(11100L, Exec.longOf(firstSoldier.get("k")));
        assertEquals(25_000L, Exec.longOf(firstSoldier.get("v")));
    }

    @Test
    public void formationPayload_omitsZeroLines() {
        ServerShopLeagueTasks.ShowdownFormation formation =
                ServerShopLeagueTasks.resolveShowdownFormation(new JSONObject(), null,
                        heroes(11, 12), new long[]{1_000L, 0L, 0L}, SOLDIER_IDS);
        Map<String, Object> payload = ServerShopLeagueTasks.showdownFormationPayload(formation,
                SOLDIER_IDS);
        assertEquals("0 兵力兵种不发 pair", 1, ((List<?>) payload.get("soldier")).size());
    }

    // ================= 赛季/已报名状态解析 =================

    @Test
    public void activity_parseHome() {
        Map<String, Object> content = m(
                "activity", m("season", 3L, "stage", 2L),
                "player", m("uid", 123L),
                "group", m("aids", l(0L, 777L, 888L)),
                "target", m("aid", 999L),
                "count", 4L,
                "grade", 5L);
        ServerShopLeagueTasks.ShowdownActivity activity =
                ServerShopLeagueTasks.showdownActivity(content);
        assertTrue(activity.initialized());
        assertEquals(3L, activity.season());
        assertEquals(2, activity.stage());
        assertTrue("player 非空=已报名", activity.signedUp());
        assertEquals("group.aids 首个 >0", 777L, activity.groupAid());
        assertEquals(4L, activity.count());
        assertEquals(5L, activity.grade());
    }

    @Test
    public void activity_groupAidFallsBackToTarget() {
        ServerShopLeagueTasks.ShowdownActivity activity = ServerShopLeagueTasks.showdownActivity(
                m("activity", m("season", 3L),
                        "group", m("aids", l()),
                        "target", m("aid", 999L)));
        assertEquals(999L, activity.groupAid());
    }

    @Test
    public void activity_notSignedWhenPlayerEmpty() {
        ServerShopLeagueTasks.ShowdownActivity activity = ServerShopLeagueTasks.showdownActivity(
                m("activity", m("season", 3L, "stage", 1L), "player", m()));
        assertFalse(activity.signedUp());
        assertEquals(1, activity.stage());
    }

    @Test
    public void activity_nullSafe() {
        ServerShopLeagueTasks.ShowdownActivity activity = ServerShopLeagueTasks.showdownActivity(null);
        assertFalse(activity.initialized());
        assertEquals(0L, activity.season());
        assertEquals(0L, activity.groupAid());
    }

    @Test
    public void activity_skipReasons() {
        assertTrue(ServerShopLeagueTasks.showdownActivitySkipReason(
                ServerShopLeagueTasks.showdownActivity(null)).contains("无活动数据"));
        assertTrue(ServerShopLeagueTasks.showdownActivitySkipReason(
                ServerShopLeagueTasks.showdownActivity(m("activity", m("season", 0L))))
                .contains("无赛季"));
        assertTrue(ServerShopLeagueTasks.showdownActivitySkipReason(
                ServerShopLeagueTasks.showdownActivity(
                        m("activity", m("season", 3L), "player", m("uid", 1L))))
                .contains("已报名"));
        assertNull("赛季有效且未报名 → 允许报名",
                ServerShopLeagueTasks.showdownActivitySkipReason(
                        ServerShopLeagueTasks.showdownActivity(
                                m("activity", m("season", 3L), "player", m()))));
    }

    @Test
    public void idempotent_byServerStateOrModSeason() {
        ServerShopLeagueTasks.ShowdownActivity signed = ServerShopLeagueTasks.showdownActivity(
                m("activity", m("season", 3L), "player", m("uid", 1L)));
        assertTrue("服务端已报名 → 幂等", ServerShopLeagueTasks.showdownSignedIdempotent(signed, 0L));

        ServerShopLeagueTasks.ShowdownActivity notSigned = ServerShopLeagueTasks.showdownActivity(
                m("activity", m("season", 3L), "player", m()));
        assertTrue("mod 记录同赛季 → 幂等不再请求",
                ServerShopLeagueTasks.showdownSignedIdempotent(notSigned, 3L));
        assertFalse("不同赛季 → 可报名",
                ServerShopLeagueTasks.showdownSignedIdempotent(notSigned, 2L));
        assertFalse("无赛季且无记录 → 非幂等",
                ServerShopLeagueTasks.showdownSignedIdempotent(
                        ServerShopLeagueTasks.showdownActivity(m("activity", m("season", 0L))), 0L));
    }

    // ================= 窗口判定（报名节奏）=================

    @Test
    public void window_mondayOpenFridayClosed() {
        LocalTime midnight = LocalTime.MIDNIGHT;
        long monday = epoch("2026-10-19T00:30");
        assertTrue("周一 00:30 在窗口内",
                ServerShopLeagueTasks.showdownWindowOpen(monday, 1, midnight));
        long friday = epoch("2026-10-23T03:00");
        assertFalse("周五已过窗口（周二 23:00 截止）",
                ServerShopLeagueTasks.showdownWindowOpen(friday, 1, midnight));
        assertTrue("周五 SKIP 文案含 窗口未到",
                ServerShopLeagueTasks.showdownWindowSkipReason(friday, 1, midnight)
                        .contains("窗口未到"));
    }

    @Test
    public void window_tuesdaySignupDayStillObserved() {
        LocalTime time = LocalTime.of(20, 0);
        long mondayNight = epoch("2026-10-19T21:00");
        assertFalse("周二报名日：周一未到",
                ServerShopLeagueTasks.showdownWindowOpen(mondayNight, 2, time));
        long tuesdayNight = epoch("2026-10-20T20:30");
        assertTrue("周二 20:30 已到窗口",
                ServerShopLeagueTasks.showdownWindowOpen(tuesdayNight, 2, time));
    }

    // ================= 战报只读解析 =================

    @Test
    public void reportIds_parseMapAndListShapes() {
        Map<String, Object> group = m(
                "rounds", l(
                        m("results", l(m("report_id", "r1"), m("report_id", "r2"))),
                        m("results", m("0", m("report_id", "r2"), "1", m("report_id", "r3")))));
        List<String> ids = ServerShopLeagueTasks.showdownReportIds(group);
        assertEquals(3, ids.size());
        assertTrue(ids.contains("r1"));
        assertTrue(ids.contains("r3"));
        assertFalse("重复 report_id 去重", ids.indexOf("r2") != ids.lastIndexOf("r2"));
    }

    @Test
    public void reportIds_emptyAndCap() {
        assertTrue(ServerShopLeagueTasks.showdownReportIds(null).isEmpty());
        assertTrue(ServerShopLeagueTasks.showdownReportIds(Map.of()).isEmpty());
        Map<String, Object> group = m("rounds", l(
                m("results", l(m("report_id", "a"), m("report_id", "b"),
                        m("report_id", "c"), m("report_id", "d")))));
        assertEquals("最多只读 3 条", 3,
                ServerShopLeagueTasks.showdownReportIds(group).size());
    }

    @Test
    public void reportResultText_readsTextFieldsOnly() {
        assertEquals("超过", "", ServerShopLeagueTasks.showdownReportResultText(null));
        assertEquals("win=true", "win=true",
                ServerShopLeagueTasks.showdownReportResultText(m("win", true)));
        assertTrue(ServerShopLeagueTasks.showdownReportResultText(m("score", 123))
                .contains("123"));
        assertEquals("二进制 report 不解码 → 空文案",
                "", ServerShopLeagueTasks.showdownReportResultText(
                        m("report", new byte[]{1, 2, 3})));
    }

    @Test
    public void modSummary_containsSeasonSignupReports() {
        ServerShopLeagueTasks.ShowdownActivity activity = ServerShopLeagueTasks.showdownActivity(
                m("activity", m("season", 3L, "stage", 2L), "player", m("uid", 1L)));
        String summary = ServerShopLeagueTasks.showdownModSummary(activity, 2, 2, "已处理");
        assertTrue(summary.contains("赛季3"));
        assertTrue(summary.contains("阶段2"));
        assertTrue(summary.contains("已报名"));
        assertTrue(summary.contains("战报2条"));
        assertTrue(summary.contains("赛道2"));
    }

    @Test
    public void applyFailureNote_401and7705() {
        assertTrue(ServerShopLeagueTasks.showdownApplyFailureNote(7705).contains("超时"));
        assertTrue(ServerShopLeagueTasks.showdownApplyFailureNote(401).contains("士兵不足"));
        assertTrue(ServerShopLeagueTasks.showdownApplyFailureNote(2).contains("报名码"));
    }
}
