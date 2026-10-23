package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.Beijing;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 批次3 单测：G2 采集续派醒/ G3 联盟矿开关 / I3 专家情报技能日台账 / A2 竞技场买次数预算门控。
 *
 * <p>基准（网页版反编译源码）：
 * {@code DailyGatherService.java:1060-1079,1181-1354,1413-1415,1652-1654}、
 * {@code DailyExpertIntelligenceService.java:39-143,200-214}、
 * {@code DailyArenaService.java:78-79,421-467,538-562}。
 * 不连真协议，只锁纯函数/台账/开关；默认行为必须“零花钻、零新增请求”。
 */
public class FieldP1Test {

    private static final ZoneId BEIJING = ZoneId.of("GMT+8");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskContext context() {
        return new TaskContext(new FakeEngine(), "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "服务器");
    }

    private static long beijing(int year, int month, int day, int hour, int minute, int second) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0, BEIJING)
                .toInstant().toEpochMilli();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m = new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }

    /** 只读替身：notify 表 + 协议响应表（同 CaptureTasksTest.FakeEngine 模式）。 */
    static final class FakeEngine extends com.carlos.vault.wjdr.WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, List<com.carlos.vault.wjdr.protocol.GameMessage>> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        FakeEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }

        @Override
        public com.carlos.vault.wjdr.protocol.GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            List<com.carlos.vault.wjdr.protocol.GameMessage> queue = responses.get(proto);
            if (queue == null || queue.isEmpty()) return null;
            return queue.size() == 1 ? queue.get(0) : queue.remove(0);
        }

        @Override
        public long lastUid() {
            return 12345L;
        }

        @Override
        public long lastServerId() {
            return 888L;
        }
    }

    // ==================== G2 采集续派 ====================

    @Test
    public void gatherMarchEndMillis_secondsAndMillis() {
        assertEquals("秒 → 毫秒", 1_790_000_000_000L,
                ServerFieldTasks.gatherMarchEndMillis(map("endtime", 1_790_000_000L)));
        assertEquals("毫秒原样", 1_790_000_000_123L,
                ServerFieldTasks.gatherMarchEndMillis(map("endtime", 1_790_000_000_123L)));
        assertEquals("endAt 别名", 1_790_000_000_000L,
                ServerFieldTasks.gatherMarchEndMillis(map("endAt", 1_790_000_000L)));
        assertEquals("end_time 别名", 1_790_000_000_000L,
                ServerFieldTasks.gatherMarchEndMillis(map("end_time", 1_790_000_000L)));
        assertEquals("取不到=0", 0L, ServerFieldTasks.gatherMarchEndMillis(map("x", 1)));
        assertEquals("空=0", 0L, ServerFieldTasks.gatherMarchEndMillis(null));
    }

    @Test
    public void gatherEarliestMarchEndMillis_onlyType3And4() {
        List<Map<String, Object>> marches = Arrays.asList(
                map("type", 4, "endtime", 1_790_000_100L),   // 采集，最早
                map("type", 3, "endtime", 1_790_000_200L),   // 狩猎
                map("type", 110, "endtime", 1_790_000_001L), // 联盟矿不算续派依据
                map("type", 99, "endtime", 1_790_000_002L)); // 上车不算
        assertEquals(1_790_000_100_000L, ServerFieldTasks.gatherEarliestMarchEndMillis(marches));
        assertEquals("没有 type3/4 = 0", 0L,
                ServerFieldTasks.gatherEarliestMarchEndMillis(List.of(map("type", 99, "endtime", 1_790_000_000L))));
        assertEquals("null = 0", 0L, ServerFieldTasks.gatherEarliestMarchEndMillis(null));
    }

    @Test
    public void gatherNextWakeMillis_capAndFallback() {
        long now = 1_790_000_000_000L;
        assertEquals("取不到 endtime → now+120s（G2 兜底）",
                now + 120_000L, ServerFieldTasks.gatherNextWakeMillis(now, 0L));
        assertEquals("正常提前 1 秒唤醒",
                now + 60_000L, ServerFieldTasks.gatherNextWakeMillis(now, now + 61_000L));
        assertEquals("最长 now+180s（G2 上限）",
                now + 180_000L, ServerFieldTasks.gatherNextWakeMillis(now, now + 600_000L));
        assertEquals("endtime 已过期 → 1 秒后复查",
                now + 1_000L, ServerFieldTasks.gatherNextWakeMillis(now, now - 5_000L));
    }

    @Test
    public void writeGatherWake_usesCadenceKeyAndMillis() {
        TaskContext tc = context();
        ServerFieldTasks.writeGatherWake(tc, 1_790_000_123_456L);
        assertEquals(1_790_000_123_456L,
                ServerFieldTasks.stateLongOf(tc, "srv:cad:48460403:888:gather"));
    }

    @Test
    public void marchDetailList_knownShapesAndUnknown() {
        assertNull("code!=0 = 未知", ServerFieldTasks.marchDetailList(map("code", 2)));
        assertNull("无 detail = 未知", ServerFieldTasks.marchDetailList(map("code", 0)));
        List<Map<String, Object>> parsed = ServerFieldTasks.marchDetailList(
                map("code", 0, "detail", map("marchs", List.of(map("type", 4, "endtime", 1L)))));
        assertEquals(1, parsed.size());
        assertTrue("有 detail 无列表 = 已知为空",
                ServerFieldTasks.marchDetailList(map("code", 0, "detail", map("x", 1))).isEmpty());
    }

    @Test
    public void snapshotMarches_countsAndEarliest() {
        FakeEngine engine = new FakeEngine();
        engine.responses.put("req_world_march_detail_4_player_situation", List.of(msg(
                map("code", 0, "detail", map("marchs", List.of(
                        map("type", 4, "endtime", 1_790_000_200L),
                        map("type", 4, "endtime", 1_790_000_300L),
                        map("type", 110, "endtime", 1_790_000_400L)))))));
        ServerFieldTasks.MarchSnapshot snap = ServerFieldTasks.snapshotMarches(engine);
        assertTrue(snap.known());
        assertEquals(2, snap.count(4));
        assertEquals(1, snap.count(110));
        assertEquals(3, snap.totalActive());
        assertEquals(1_790_000_200_000L, snap.earliestMarchEndMillis());
        assertEquals(1, engine.requested.size());   // G2：不新增查询请求
    }

    @Test
    public void snapshotMarches_queryFailureIsUnknown() {
        ServerFieldTasks.MarchSnapshot snap = ServerFieldTasks.snapshotMarches(new FakeEngine());
        assertFalse(snap.known());
        assertEquals(0, snap.totalActive());
    }

    // ==================== G3 联盟矿（默认关） ====================

    @Test
    public void gatherAllianceMineEnabled_defaultOff() {
        assertFalse("默认关", ServerFieldTasks.gatherAllianceMineEnabled(new JSONObject()));
        assertFalse(ServerFieldTasks.gatherAllianceMineEnabled(null));
        assertFalse("显式 false 仍关",
                ServerFieldTasks.gatherAllianceMineEnabled(new JSONObject("{\"alliance_mine\":false}")));
        assertTrue("显式 true 才开",
                ServerFieldTasks.gatherAllianceMineEnabled(new JSONObject("{\"alliance_mine\":true}")));
    }

    @Test
    public void allianceMineTargets_groupShapeAndExpiry() {
        long nowSec = 1_790_000_000L;
        Map<String, Object> content = map(
                "building_type_2_list", List.of(
                        map("mapobj_type", 39, "building_list", List.of(
                                map("x", 10, "y", 20, "resource_info", map("end_time", nowSec + 3600)),
                                map("x", 11, "y", 21, "resource_info", map("end_time", nowSec - 1)))),
                        map("mapobj_type", 33, "building_list", List.of(
                                map("x", 30, "y", 40)))),
                "building_list", List.of(map("x", 50, "y", 60)));
        List<Map<String, Object>> targets = ServerFieldTasks.allianceMineTargets(content, nowSec);
        assertEquals("排除过期+排除非39分组，保留顶层 building_list", 2, targets.size());
        assertEquals("39 分组内未过期的在首位", 10L, Exec.longOf(targets.get(0).get("x")));
        assertEquals("顶层 building_list 兜底（33 分组不算联盟矿）", 50L, Exec.longOf(targets.get(1).get("x")));
    }

    @Test
    public void allianceMineTargets_dedupesSamePoint() {
        Map<String, Object> content = map("building_list", List.of(
                map("x", 10, "y", 20),
                map("x", 10, "y", 20)));
        assertEquals(1, ServerFieldTasks.allianceMineTargets(content, 1L).size());
    }

    @Test
    public void allianceMineMapobjOk_verifiesTypeExpiryAlliance() {
        long nowSec = 1_790_000_000L;
        Map<String, Object> ok = map("code", 0, "mapobj", map(
                "type", 39, "map_alliance_resource", map("aid", 777, "resource_endtime", nowSec + 60)));
        assertTrue(ServerFieldTasks.allianceMineMapobjOk(ok, 777, nowSec));
        assertTrue("联盟 id 未知时不做归属否决", ServerFieldTasks.allianceMineMapobjOk(ok, 0, nowSec));
        assertFalse("type 非 39",
                ServerFieldTasks.allianceMineMapobjOk(map("mapobj", map("type", 33)), 777, nowSec));
        assertFalse("无资源信息",
                ServerFieldTasks.allianceMineMapobjOk(map("mapobj", map("type", 39)), 777, nowSec));
        assertFalse("已过期", ServerFieldTasks.allianceMineMapobjOk(map("mapobj", map(
                "type", 39, "map_alliance_resource", map("resource_endtime", nowSec - 1))), 777, nowSec));
        assertFalse("不属于本盟", ServerFieldTasks.allianceMineMapobjOk(map("mapobj", map(
                "type", 39, "map_alliance_resource", map("aid", 1, "resource_endtime", nowSec + 60))),
                777, nowSec));
    }

    @Test
    public void gatherAllianceIdOf_shapes() {
        assertEquals(Long.valueOf(777L), ServerFieldTasks.gatherAllianceIdOf(map("id", 777)));
        assertEquals(Long.valueOf(778L),
                ServerFieldTasks.gatherAllianceIdOf(map("alliance", map("id", 778))));
        assertEquals(Long.valueOf(779L),
                ServerFieldTasks.gatherAllianceIdOf(map("data", map("alliance_id", 779))));
        assertNull(ServerFieldTasks.gatherAllianceIdOf(map("x", 1)));
    }

    @Test
    public void gatherMineRetryLedger_cooldown() {
        TaskContext tc = context();
        long now = 1_790_000_000_000L;
        assertTrue("无台账=可试", ServerFieldTasks.gatherMineRetryDue(tc, now));
        tc.store.putLong(ServerFieldTasks.gatherMineRetryKey(tc), now + 60_000L);
        assertFalse("冷却中", ServerFieldTasks.gatherMineRetryDue(tc, now));
        assertTrue("到点可试", ServerFieldTasks.gatherMineRetryDue(tc, now + 60_000L));
    }

    // ==================== I3 专家情报技能（日台账/安全窗） ====================

    @Test
    public void expertSkillWindowOpen_afterBeijing00130() {
        assertFalse("00:01:29 未开窗",
                ServerTownTasks.expertSkillWindowOpen(beijing(2026, 10, 23, 0, 1, 29)));
        assertTrue("00:01:30 开窗",
                ServerTownTasks.expertSkillWindowOpen(beijing(2026, 10, 23, 0, 1, 30)));
        assertTrue("白天开窗",
                ServerTownTasks.expertSkillWindowOpen(beijing(2026, 10, 23, 12, 0, 0)));
    }

    @Test
    public void expertSkillUsedToday_beijingDay() {
        long now = beijing(2026, 10, 23, 12, 0, 0);
        long todayTs = beijing(2026, 10, 23, 0, 2, 0) / 1000L;
        long yesterdayTs = beijing(2026, 10, 22, 23, 59, 0) / 1000L;
        assertTrue(ServerTownTasks.expertSkillUsedToday(todayTs, now));
        assertFalse(ServerTownTasks.expertSkillUsedToday(yesterdayTs, now));
        assertFalse("0=未用", ServerTownTasks.expertSkillUsedToday(0L, now));
    }

    @Test
    public void intelligenceEffectUseTs_findsEffectSixAndLatestUse() {
        // effects 是 Map（键即 effect id，含 "6.0" 字符串键）
        Map<String, Object> skillMap = map("id", 70041, "effects", map(
                "6.0", map("last_refresh_time", 111L),
                "14.0", map("last_refresh_time", 222L)));
        assertEquals("只认 effect 6", Long.valueOf(111L),
                ServerTownTasks.intelligenceEffectUseTs(skillMap));
        // effects 是 List，effect id 在字段/type 里
        assertEquals(Long.valueOf(333L), ServerTownTasks.intelligenceEffectUseTs(map(
                "id", 1, "effects", List.of(
                        map("id", 7, "last_use_ts", 999),
                        map("type", 6, "last_use_ts", 333)))));
        assertEquals("有情报效果但未用 → 0", Long.valueOf(0L), ServerTownTasks.intelligenceEffectUseTs(
                map("effects", map("6", map("x", 1)))));
        assertNull("无 effect 6", ServerTownTasks.intelligenceEffectUseTs(
                map("effects", map("14.0", map("last_refresh_time", 1)))));
        assertNull(ServerTownTasks.intelligenceEffectUseTs(null));
    }

    @Test
    public void andSkillOf_scansExpertsShapes() {
        // notify_expert_info：experts 是 Map（id → expert）
        Map<String, Object> info = map("experts", map(
                "7001", map("id", 7001, "skills", map(
                        "70011", map("id", 70011, "effects", map("14.0", map("x", 1))))),
                "7002", map("id", 7002, "skills", map(
                        "70021", map("id", 70021, "effects", map("6.0", map("last_refresh_time", 55)))))));
        ServerTownTasks.ExpertSkill skill = ServerTownTasks.scanExpertSkillNotify(info);
        assertNotNull(skill);
        assertEquals(7002L, skill.expertId);
        assertEquals(70021L, skill.skillId);
        assertEquals(55L, skill.lastUseTs);
        assertNull("没有 effect 6 的专家 → null", ServerTownTasks.scanExpertSkillNotify(
                map("experts", map("7001", map("id", 7001, "skills", List.of())))));
    }

    @Test
    public void expertSkillLedger_dayScopedViaClaimLedger() {
        TaskContext tc = context();
        assertFalse(ClaimLedger.isClaimed(tc, ServerTownTasks.EXPERT_SKILL_LEDGER_SCOPE, "done"));
        ClaimLedger.mark(tc, ServerTownTasks.EXPERT_SKILL_LEDGER_SCOPE, "done");
        assertTrue(ClaimLedger.isClaimed(tc, ServerTownTasks.EXPERT_SKILL_LEDGER_SCOPE, "done"));
        assertFalse("另一 key 不受影响",
                ClaimLedger.isClaimed(tc, ServerTownTasks.EXPERT_SKILL_LEDGER_SCOPE, "limit"));
    }

    // ==================== A2 竞技场买次数（默认关/预算门控） ====================

    @Test
    public void arenaBuyCount_defaultOffAndMissingBudget() {
        assertEquals("默认关=0 次", 0, ServerTownTasks.arenaBuyCount(
                false, 5, 1000L, 0, 5000L, 0L, 100L));
        assertEquals("未给 max_buy_times=0 次", 0, ServerTownTasks.arenaBuyCount(
                true, 0, 1000L, 0, 5000L, 0L, 100L));
        assertEquals("未给 max_diamond=0 次", 0, ServerTownTasks.arenaBuyCount(
                true, 5, 0L, 0, 5000L, 0L, 100L));
    }

    @Test
    public void arenaBuyCount_unknownBalanceNeverBuys() {
        assertEquals("余额未知不买", 0, ServerTownTasks.arenaBuyCount(
                true, 5, 1000L, 0, null, 0L, 100L));
        assertEquals("负余额不买", 0, ServerTownTasks.arenaBuyCount(
                true, 5, 1000L, 0, -1L, 0L, 100L));
    }

    @Test
    public void arenaBuyCount_budgetCaps() {
        assertEquals("预算只够 2 次", 2, ServerTownTasks.arenaBuyCount(
                true, 5, 250L, 0, 10_000L, 0L, 100L));
        assertEquals("已买 3 次剩余上限 2", 2, ServerTownTasks.arenaBuyCount(
                true, 5, 10_000L, 3, 10_000L, 0L, 100L));
        assertEquals("预算已耗尽", 0, ServerTownTasks.arenaBuyCount(
                true, 5, 1000L, 0, 10_000L, 1000L, 100L));
        assertEquals("余额只够 1 次", 1, ServerTownTasks.arenaBuyCount(
                true, 5, 10_000L, 0, 150L, 0L, 100L));
        assertEquals("价格未知最多探 1 次", 1, ServerTownTasks.arenaBuyCount(
                true, 5, 1000L, 0, 10_000L, 0L, 0L));
    }

    @Test
    public void arenaBuyWindowOpen_until2000() {
        assertTrue(ServerTownTasks.arenaBuyWindowOpen(beijing(2026, 10, 23, 0, 0, 1)));
        assertTrue(ServerTownTasks.arenaBuyWindowOpen(beijing(2026, 10, 23, 19, 59, 59)));
        assertFalse(ServerTownTasks.arenaBuyWindowOpen(beijing(2026, 10, 23, 20, 0, 0)));
    }

    @Test
    public void arenaDailyFightCap_includesBoughtTimes() {
        assertEquals(5, ServerTownTasks.arenaDailyFightCap(0));
        assertEquals(8, ServerTownTasks.arenaDailyFightCap(3));
        assertEquals(5, ServerTownTasks.arenaDailyFightCap(-2));
    }

    @Test
    public void arenaBuyCostOf_shapes() {
        assertEquals(100L, ServerTownTasks.arenaBuyCostOf(map("cost", 100)));
        assertEquals(200L, ServerTownTasks.arenaBuyCostOf(map("cost", map("diamond", 200))));
        assertEquals(300L, ServerTownTasks.arenaBuyCostOf(map("diamond_cost", 300)));
        assertEquals(0L, ServerTownTasks.arenaBuyCostOf(map("x", 1)));
        assertEquals(0L, ServerTownTasks.arenaBuyCostOf(null));
    }

    @Test
    public void arenaDiamondOf_shapes() {
        assertEquals(Long.valueOf(5200L), ServerTownTasks.arenaDiamondOf(map(
                "data", List.of(map("id", 101, "count", 5200), map("id", 201, "count", 50)))));
        assertEquals(Long.valueOf(777L), ServerTownTasks.arenaDiamondOf(map(
                "101.0", map("count", 777))));
        assertNull(ServerTownTasks.arenaDiamondOf(map("data", List.of(map("id", 201, "count", 1)))));
        assertNull(ServerTownTasks.arenaDiamondOf(null));
    }

    @Test
    public void arenaDiamondBalance_initPlusDeltaDeduped() {
        TaskContext tc = context();
        FakeEngine engine = (FakeEngine) tc.engine;
        engine.notifies.put("notify_resource_init", map("data", List.of(map("id", 101, "count", 5000))));
        assertEquals("初始化绝对值", Long.valueOf(5000L), ServerTownTasks.arenaDiamondBalance(tc));

        engine.notifies.put("notify_resource_update", map("type", "del",
                "data", List.of(map("id", 101, "count", 100))));
        assertEquals("增量扣减", Long.valueOf(4900L), ServerTownTasks.arenaDiamondBalance(tc));
        assertEquals("同一推送不重复扣", Long.valueOf(4900L), ServerTownTasks.arenaDiamondBalance(tc));

        engine.notifies.put("notify_resource_update", map("type", "add",
                "data", List.of(map("id", 101, "count", 50))));
        assertEquals("增量增加", Long.valueOf(4950L), ServerTownTasks.arenaDiamondBalance(tc));
    }

    @Test
    public void arenaDiamondBalance_unknownWithoutData() {
        TaskContext tc = context();
        assertNull("无 init/update → 余额未知（不买）", ServerTownTasks.arenaDiamondBalance(tc));
    }
}
