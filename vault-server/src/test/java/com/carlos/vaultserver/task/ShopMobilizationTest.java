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
 * 批次3 单测：商店名单模式/预算（S2）、总动员最小状态机（L3）、争霸赛窗口（L4）。
 *
 * <p>全部为纯判定函数测试，不依赖真引擎/真协议；资金安全相关断言优先：
 * 钻石默认关、余额未知不买、同名只买最便宜可支付 variant、总动员只允许 cost=0 首购。
 */
public class ShopMobilizationTest {

    // ================= 测试辅助 =================

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
        return out;
    }

    private static List<Object> l(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static ServerShopLeagueTasks.ShopShelfItem item(long shopId, long goodsId, long configId,
                                                            long currencyId, long price,
                                                            long buyCount, long limit) {
        return new ServerShopLeagueTasks.ShopShelfItem(shopId, goodsId, configId, currencyId,
                price, buyCount, limit);
    }

    private static ServerShopLeagueTasks.ShopBudget budget(boolean allowDiamond, long maxDiamond,
                                                           int maxBuys) {
        return new ServerShopLeagueTasks.ShopBudget(allowDiamond, maxDiamond, maxBuys);
    }

    private static Map<Long, String> nameByConfig(Long... pairs) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) out.put(pairs[i], String.valueOf(pairs[i + 1]));
        return out;
    }

    private static Map<Long, Long> balances(Object... kv) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(Long.parseLong(String.valueOf(kv[i])), Long.parseLong(String.valueOf(kv[i + 1])));
        }
        return out;
    }

    private static long epoch(String localDateTime) {
        return LocalDateTime.parse(localDateTime)
                .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    private static ServerShopLeagueTasks.MobilizationState activeState(long nowSec) {
        ServerShopLeagueTasks.MobilizationState st = new ServerShopLeagueTasks.MobilizationState();
        st.activityId = 9001L;
        st.stageTimestamps.addAll(List.of(nowSec - 2000L, nowSec - 1000L, nowSec + 1000L));
        st.allianceId = 888L;
        st.participantAllianceId = 888L;
        st.allianceJoined = true;
        return st;
    }

    // ================= S2：名单解析 =================

    @Test
    public void parseBuyNames_objectForm() {
        JSONObject names = new JSONObject();
        names.put("100点统帅经验", 2);
        names.put("1,000点英雄经验", 1);
        Map<String, Long> got = ServerShopLeagueTasks.parseBuyNames(names);
        assertEquals(2, got.size());
        assertEquals(Long.valueOf(2L), got.get("100点统帅经验"));
        assertEquals(Long.valueOf(1L), got.get("1,000点英雄经验"));
    }

    @Test
    public void parseBuyNames_arrayStringForm() {
        JSONArray names = new JSONArray();
        names.put("抛光液");
        names.put("秘银");
        Map<String, Long> got = ServerShopLeagueTasks.parseBuyNames(names);
        assertEquals(2, got.size());
        assertEquals(Long.valueOf(1L), got.get("抛光液"));
    }

    @Test
    public void parseBuyNames_arrayObjectForm() {
        JSONArray names = new JSONArray();
        names.put(new JSONObject().put("name", "火晶微粒").put("count", 3));
        names.put(new JSONObject().put("name", "  "));
        Map<String, Long> got = ServerShopLeagueTasks.parseBuyNames(names);
        assertEquals(1, got.size());
        assertEquals(Long.valueOf(3L), got.get("火晶微粒"));
    }

    @Test
    public void parseBuyNames_nullBlankIgnored() {
        assertTrue(ServerShopLeagueTasks.parseBuyNames(null).isEmpty());
        assertTrue(ServerShopLeagueTasks.parseBuyNames(new JSONObject()).isEmpty());
        assertTrue(ServerShopLeagueTasks.parseBuyNames("   ").isEmpty());
        JSONArray blank = new JSONArray();
        blank.put("");
        assertTrue(ServerShopLeagueTasks.parseBuyNames(blank).isEmpty());
    }

    @Test
    public void parseLegacyBuys_dedupAndInvalidSkip() {
        JSONObject buys = new JSONObject();
        buys.put("40110121", 2);
        buys.put("40110122", 1);
        buys.put("abc", 9);
        buys.put("-5", 1);
        LinkedHashMap<Long, Long> got = ServerShopLeagueTasks.parseLegacyBuys(buys);
        assertEquals("同一 configId 去重（map 键天然唯一）+ 非法键跳过", 2, got.size());
        assertEquals(Long.valueOf(2L), got.get(40110121L));
    }

    // ================= S2：同名最便宜可支付变体 =================

    @Test
    public void cheapestVariant_picksLowestPrice() {
        List<ServerShopLeagueTasks.ShopShelfItem> shelf = List.of(
                item(801, 11, 101, 101L, 300, 0, 5),
                item(801, 12, 102, 101L, 100, 0, 5),
                item(801, 13, 103, 101L, 200, 0, 5));
        Map<Long, String> names = nameByConfig(101L, 0L, 102L, 0L, 103L, 0L);
        Map<Long, Long> bal = balances(101, 10000);
        ServerShopLeagueTasks.ShopShelfItem chosen =
                ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0",
                        bal, budget(true, 10000, 20), 0L);
        assertNotNull(chosen);
        assertEquals("必须选最便宜档", 102L, chosen.configId());
        assertEquals(100L, chosen.price());
    }

    @Test
    public void cheapestVariant_skipsDiamondWhenDisallowed() {
        // 同名两档：钻石 50（更便宜）vs 联盟币 200；allow_diamond=false → 只能买联盟币档。
        List<ServerShopLeagueTasks.ShopShelfItem> shelf = List.of(
                item(801, 21, 201, 101L, 50, 0, 3),
                item(401, 22, 202, 110L, 200, 0, 3));
        Map<Long, String> names = nameByConfig(201L, 0L, 202L, 0L);
        Map<Long, Long> bal = balances(101, 10000, 110, 1000);
        ServerShopLeagueTasks.ShopShelfItem chosen =
                ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0",
                        bal, budget(false, 0, 20), 0L);
        assertNotNull("钻石默认关时仍可选非钻石可支付档", chosen);
        assertEquals("绝不能默认买钻石档", 202L, chosen.configId());
    }

    @Test
    public void cheapestVariant_diamondGatedByExplicitBudget() {
        List<ServerShopLeagueTasks.ShopShelfItem> shelf = List.of(
                item(801, 31, 301, 101L, 50, 0, 3),
                item(401, 32, 302, 110L, 200, 0, 3));
        Map<Long, String> names = nameByConfig(301L, 0L, 302L, 0L);
        Map<Long, Long> bal = balances(101, 10000, 110, 1000);
        // 开钻石且预算 60 ≥ 50 → 选钻石档
        assertEquals(301L, ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0", bal,
                budget(true, 60, 20), 0L).configId());
        // 预算 40 < 50 → 回落非钻石档（不超额）
        assertEquals(302L, ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0", bal,
                budget(true, 40, 20), 0L).configId());
    }

    @Test
    public void cheapestVariant_unknownBalanceSkips() {
        List<ServerShopLeagueTasks.ShopShelfItem> shelf = List.of(
                item(901, 41, 401, 111L, 100, 0, 5));
        Map<Long, String> names = nameByConfig(401L, 0L);
        assertNull("币种余额未知 → 不买（资金安全）",
                ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0",
                        new LinkedHashMap<>(), budget(false, 0, 20), 0L));
    }

    @Test
    public void cheapestVariant_freeItemBuysWithoutBalance() {
        List<ServerShopLeagueTasks.ShopShelfItem> shelf = List.of(
                item(601, 51, 501, 600020L, 0, 0, 5));
        Map<Long, String> names = nameByConfig(501L, 0L);
        ServerShopLeagueTasks.ShopShelfItem chosen =
                ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0",
                        new LinkedHashMap<>(), budget(false, 0, 20), 0L);
        assertNotNull("免费档(price=0)无需余额", chosen);
        assertEquals(0L, chosen.price());
    }

    @Test
    public void cheapestVariant_limitExhaustedSkips() {
        List<ServerShopLeagueTasks.ShopShelfItem> shelf = List.of(
                item(801, 61, 601, 101L, 10, 2, 2),      // 已达限购
                item(801, 62, 602, 101L, 20, 1, 2));
        Map<Long, String> names = nameByConfig(601L, 0L, 602L, 0L);
        ServerShopLeagueTasks.ShopShelfItem chosen =
                ServerShopLeagueTasks.chooseCheapestVariant(shelf, names, "0",
                        balances(101, 1000), budget(true, 1000, 20), 0L);
        assertNotNull(chosen);
        assertEquals("限购已满的档必须跳过", 602L, chosen.configId());
    }

    @Test
    public void shopItemPayable_diamondNeedsBudget() {
        ServerShopLeagueTasks.ShopShelfItem diamond = item(801, 71, 701, 101L, 50, 0, 1);
        assertFalse("钻石默认关", ServerShopLeagueTasks.shopItemPayable(diamond,
                balances(101, 10000), budget(false, 0, 20), 0L));
        assertFalse("预算不足", ServerShopLeagueTasks.shopItemPayable(diamond,
                balances(101, 10000), budget(true, 40, 20), 0L));
        assertTrue("预算足额", ServerShopLeagueTasks.shopItemPayable(diamond,
                balances(101, 10000), budget(true, 50, 20), 0L));
        assertFalse("已花预算后再买超支", ServerShopLeagueTasks.shopItemPayable(diamond,
                balances(101, 10000), budget(true, 60, 20), 20L));
    }

    // ================= S2：刷新费用/预算 =================

    @Test
    public void refreshCost_tables() {
        assertEquals(0L, ServerShopLeagueTasks.shopRefreshCost(801, 0));
        assertEquals(0L, ServerShopLeagueTasks.shopRefreshCost(801, 2));
        assertEquals(50L, ServerShopLeagueTasks.shopRefreshCost(801, 3));
        assertEquals(1000L, ServerShopLeagueTasks.shopRefreshCost(801, 9));
        assertEquals(-1L, ServerShopLeagueTasks.shopRefreshCost(801, 10));
        assertEquals(0L, ServerShopLeagueTasks.shopRefreshCost(601, 0));
        assertEquals(100L, ServerShopLeagueTasks.shopRefreshCost(601, 1));
        assertEquals("不可刷新店铺无费用表", -1L, ServerShopLeagueTasks.shopRefreshCost(901, 0));
    }

    @Test
    public void refreshTimesFrom_missingFieldBlocksRefresh() {
        Map<String, Object> mapInfo = m(
                "801", m("refresh_times", 2L, "goods", l()),
                "601", m("goods", l()));
        Map<Long, Integer> got = ServerShopLeagueTasks.refreshTimesFrom(mapInfo);
        assertEquals(Integer.valueOf(2), got.get(801L));
        assertNull("refresh_times 缺失=刷新次数未同步，不许刷新（防误花钻石）", got.get(601L));
    }

    @Test
    public void diamondRefreshAllowed_gate() {
        assertTrue("免费次数不花钻石", ServerShopLeagueTasks.diamondRefreshAllowed(
                budget(false, 0, 20), 801, 0, 0L));
        assertFalse("钻石刷新默认关", ServerShopLeagueTasks.diamondRefreshAllowed(
                budget(false, 0, 20), 801, 3, 0L));
        assertFalse("钻石预算不足", ServerShopLeagueTasks.diamondRefreshAllowed(
                budget(true, 40, 20), 801, 3, 0L));
        assertTrue("钻石预算足额", ServerShopLeagueTasks.diamondRefreshAllowed(
                budget(true, 50, 20), 801, 3, 0L));
        assertFalse("已花预算", ServerShopLeagueTasks.diamondRefreshAllowed(
                budget(true, 60, 20), 801, 3, 20L));
    }

    // ================= S2：pending 对账 =================

    @Test
    public void shopPending_roundTripAndWindow() {
        long now = 1_000_000L;
        ServerShopLeagueTasks.ShopPending pending =
                new ServerShopLeagueTasks.ShopPending(801, 12, 101, 50, 101L, 1, now);
        ServerShopLeagueTasks.ShopPending parsed =
                ServerShopLeagueTasks.ShopPending.fromJson(pending.toJson());
        assertNotNull(parsed);
        assertEquals(801L, parsed.shopId());
        assertEquals(12L, parsed.goodsId());
        assertTrue("窗口内 pending 防重复下单",
                ServerShopLeagueTasks.shopPendingFresh(parsed, now + 1000L));
        assertFalse("超过 TTL 允许重试",
                ServerShopLeagueTasks.shopPendingFresh(parsed,
                        now + ServerShopLeagueTasks.SHOP_PENDING_TTL_MS + 1L));
        assertTrue("货架已购数增加=上轮已完成对账",
                ServerShopLeagueTasks.shopPendingCompleted(parsed, 2L));
        assertFalse(ServerShopLeagueTasks.shopPendingCompleted(parsed, 1L));
        assertNull("空串解析为空", ServerShopLeagueTasks.ShopPending.fromJson(""));
    }

    // ================= S2：资源余额解析 =================

    @Test
    public void resourceBalances_absoluteAndDelta() {
        Map<Long, Long> out = new LinkedHashMap<>();
        ServerShopLeagueTasks.applyResourceList(out, m("data",
                l(m("id", 101L, "count", 1000L), m("id", 110L, "count", 5000L))));
        assertEquals(Long.valueOf(1000L), out.get(101L));
        ServerShopLeagueTasks.applyResourceDelta(out, m("data",
                l(m("id", 101L, "count", 200L))));
        assertEquals("resource_update 是差值", Long.valueOf(1200L), out.get(101L));
        ServerShopLeagueTasks.applyResourceDelta(out, m("type", "del", "data",
                l(m("id", 110L, "count", 100L))));
        assertEquals("type=del 为减少", Long.valueOf(4900L), out.get(110L));
    }

    // ================= L3：总动员状态机 =================

    @Test
    public void mobilizationState_initTransitions() {
        long nowSec = 1_000_000L;
        Map<String, Object> init = m(
                "activity", m("id", 9001L, "stage_ts", l(nowSec - 2000L, nowSec - 1000L, nowSec + 1000L)),
                "alliance", m("aid", 888L, "is_join", true, "score", 100L),
                "player", m("count", 2L, "score", 10L,
                        "day_data", m("count", 0L),
                        "quest", m("id", 77L, "progress", 0L, "endtime", 9999L, "pos", 2L),
                        "positions", l(m("id", 0L), m("id", 55L))));
        ServerShopLeagueTasks.MobilizationState st =
                ServerShopLeagueTasks.readMobilizationState(
                        proto -> "notify_mobilization_init".equals(proto) ? init : null);
        assertEquals(9001L, st.activityId);
        assertTrue(st.inAlliance());
        assertEquals(2, st.remainingAcceptCount);
        assertEquals(0, st.dailyPurchaseCount);
        assertTrue(st.hasQuest());
        assertEquals(77L, st.questId);
        assertEquals(2, st.questPosition);
        assertEquals(Long.valueOf(0L), st.personalPositions.get(1));
        assertEquals(Long.valueOf(55L), st.personalPositions.get(2));
        assertEquals("stage_ts 窗口内为开启阶段", 3, st.stage(nowSec));
        assertTrue(st.active(nowSec));
        assertTrue(st.boardAvailable(nowSec));
        assertNull(ServerShopLeagueTasks.mobilizationStateSkipReason(st, nowSec));
    }

    @Test
    public void mobilizationState_updatesApply() {
        long nowSec = 1_000_000L;
        Map<String, Object> init = m(
                "activity", m("id", 9001L, "stage_ts", l(nowSec - 2000L, nowSec - 1000L, nowSec + 1000L)),
                "player", m("count", 2L, "positions", l(m("id", 0L))));
        Map<String, Map<String, Object>> updates = new LinkedHashMap<>();
        updates.put("notify_mobilization_init", init);
        updates.put("notify_alliance_init", m("player", m("aid", 777L)));
        updates.put("notify_mobilization_activity_update",
                m("activity", m("id", 9002L, "stage_ts", l(nowSec - 100L, nowSec - 50L, nowSec + 50L))));
        updates.put("notify_mobilization_quest_update",
                m("quest", m("id", 88L, "progress", 5L, "pos", 3L)));
        updates.put("notify_mobilization_position_update",
                m("pos", 3L, "position", m("id", 66L)));
        updates.put("notify_mobilization_personal_position_update",
                m("pos", 1L, "position", m("id", 44L)));
        ServerShopLeagueTasks.MobilizationState st =
                ServerShopLeagueTasks.readMobilizationState(updates::get);
        assertEquals("activity_update 覆盖活动 id", 9002L, st.activityId);
        assertTrue(st.active(nowSec));
        assertEquals(88L, st.questId);
        assertEquals(5L, st.questProgress);
        assertEquals(3, st.questPosition);
        assertEquals(777L, st.allianceId);
        assertEquals(Long.valueOf(66L), st.publicPositions.get(3));
        assertEquals(Long.valueOf(44L), st.personalPositions.get(1));
    }

    @Test
    public void mobilizationState_skipReasons() {
        long nowSec = 1_000_000L;
        ServerShopLeagueTasks.MobilizationState noActivity =
                new ServerShopLeagueTasks.MobilizationState();
        assertNotNull(ServerShopLeagueTasks.mobilizationStateSkipReason(noActivity, nowSec));

        ServerShopLeagueTasks.MobilizationState noAlliance = activeState(nowSec);
        noAlliance.allianceId = 0L;
        assertTrue(ServerShopLeagueTasks.mobilizationStateSkipReason(noAlliance, nowSec)
                .contains("未加入联盟"));

        ServerShopLeagueTasks.MobilizationState stage2 = activeState(nowSec);
        stage2.stageTimestamps.clear();
        stage2.stageTimestamps.addAll(List.of(nowSec - 100L, nowSec + 100L, nowSec + 200L));
        assertEquals(2, stage2.stage(nowSec));
        assertTrue(ServerShopLeagueTasks.mobilizationStateSkipReason(stage2, nowSec)
                .contains("尚未进入开启阶段"));
    }

    @Test
    public void mobilizationFreeBuy_onlyCostZeroFirstPurchase() {
        long nowSec = 1_000_000L;
        ServerShopLeagueTasks.MobilizationState st = activeState(nowSec);
        st.remainingAcceptCount = 0;
        st.dailyPurchaseCount = 0;
        ServerShopLeagueTasks.MobilizationCountBuy allowed =
                ServerShopLeagueTasks.mobilizationFreeCountBuyDecision(st, nowSec, null);
        assertTrue("cost=0 首购允许一次", allowed.allowed());

        st.remainingAcceptCount = 1;
        assertFalse("已有接取次数时先接任务，保留免费首购",
                ServerShopLeagueTasks.mobilizationFreeCountBuyDecision(st, nowSec, null).allowed());

        st.remainingAcceptCount = 0;
        st.dailyPurchaseCount = 1;
        assertFalse("当日已购买过 → 后续都是付费，禁止",
                ServerShopLeagueTasks.mobilizationFreeCountBuyDecision(st, nowSec, null).allowed());

        st.dailyPurchaseCount = 0;
        st.questId = 99L;
        assertFalse("已有进行中任务",
                ServerShopLeagueTasks.mobilizationFreeCountBuyDecision(st, nowSec, null).allowed());

        ServerShopLeagueTasks.MobilizationState inactive = activeState(nowSec);
        inactive.stageTimestamps.clear();
        inactive.stageTimestamps.addAll(List.of(nowSec - 100L, nowSec - 50L, nowSec - 10L));
        assertEquals(4, inactive.stage(nowSec));
        assertFalse("结算阶段不买次数",
                ServerShopLeagueTasks.mobilizationFreeCountBuyDecision(inactive, nowSec, null).allowed());
    }

    @Test
    public void openInfoProbe_throttled() {
        ServerShopLeagueTasks.MobilizationCheckpoint cp =
                ServerShopLeagueTasks.MobilizationCheckpoint.EMPTY;
        long now = 5_000_000L;
        assertTrue("从未探测 → 探测一次", ServerShopLeagueTasks.shouldProbeOpenInfo(cp, now));

        cp = cp.rememberOpenInfo(now, ServerShopLeagueTasks.MOB_CODE_NOT_OPEN);
        assertFalse("7401 后 1 小时内不再探测",
                ServerShopLeagueTasks.shouldProbeOpenInfo(cp, now + 60_000L));
        assertFalse("7401 后 59 分钟仍不探测",
                ServerShopLeagueTasks.shouldProbeOpenInfo(cp,
                        now + ServerShopLeagueTasks.MOB_OPEN_INFO_7401_RETRY_MS - 1L));
        assertTrue("7401 满 1 小时可重探",
                ServerShopLeagueTasks.shouldProbeOpenInfo(cp,
                        now + ServerShopLeagueTasks.MOB_OPEN_INFO_7401_RETRY_MS));

        ServerShopLeagueTasks.MobilizationCheckpoint other =
                ServerShopLeagueTasks.MobilizationCheckpoint.EMPTY.rememberOpenInfo(now, 2);
        assertFalse("其它错误码 5 分钟内不重复探测",
                ServerShopLeagueTasks.shouldProbeOpenInfo(other, now + 1_000L));
        assertTrue("其它错误码 5 分钟后可重试",
                ServerShopLeagueTasks.shouldProbeOpenInfo(other,
                        now + ServerShopLeagueTasks.MOB_OPEN_INFO_OTHER_RETRY_MS));
    }

    @Test
    public void mobilizationMemory_questAndMilestone() {
        long now = 2_000_000L;
        long nowSec = 2_000L;
        ServerShopLeagueTasks.MobilizationState st = activeState(nowSec);
        st.questId = 77L;
        st.questProgress = 3L;
        String questFp = ServerShopLeagueTasks.mobQuestFingerprint(st);
        String milestoneFp = ServerShopLeagueTasks.mobMilestoneFingerprint(st, nowSec);

        ServerShopLeagueTasks.MobilizationCheckpoint cp =
                ServerShopLeagueTasks.MobilizationCheckpoint.EMPTY
                        .rememberQuestEmpty(questFp, now)
                        .rememberMilestoneEmpty(milestoneFp, now);
        assertTrue("8409/8410 空奖 300 秒记忆",
                ServerShopLeagueTasks.mobQuestMemoryActive(cp, questFp, now + 1000L));
        assertFalse("指纹变化后重新尝试",
                ServerShopLeagueTasks.mobQuestMemoryActive(cp, questFp + "#x", now + 1000L));
        assertFalse("300 秒后过期",
                ServerShopLeagueTasks.mobQuestMemoryActive(cp, questFp,
                        now + ServerShopLeagueTasks.MOB_EMPTY_MEMORY_MS));
        assertTrue("8413 里程碑记忆",
                ServerShopLeagueTasks.mobMilestoneMemoryActive(cp, milestoneFp, now + 1000L));
        assertFalse("clear 后立即可以重试",
                ServerShopLeagueTasks.mobMilestoneMemoryActive(
                        cp.clearMilestoneEmpty(), milestoneFp, now + 1000L));
    }

    @Test
    public void refreshBudget_limits() {
        long now = 10_000_000L;
        ServerShopLeagueTasks.MobilizationRefreshBudget b =
                ServerShopLeagueTasks.MobilizationRefreshBudget.EMPTY;
        assertTrue(ServerShopLeagueTasks.mobRefreshAllowed(b, now, 2, 3));
        b = ServerShopLeagueTasks.mobRefreshRecorded(b, now);
        assertTrue(ServerShopLeagueTasks.mobRefreshAllowed(b, now, 2, 3));
        b = ServerShopLeagueTasks.mobRefreshRecorded(b, now);
        assertFalse("每小时上限命中", ServerShopLeagueTasks.mobRefreshAllowed(b, now, 2, 3));

        long nextHour = now + 3_600_000L;
        assertTrue("进入新小时，小时计数归零",
                ServerShopLeagueTasks.mobRefreshAllowed(b, nextHour, 2, 3));
        b = ServerShopLeagueTasks.mobRefreshRecorded(b, nextHour);
        b = ServerShopLeagueTasks.mobRefreshRecorded(b, nextHour + 1000L);
        assertFalse("每日上限命中（跨小时累计）",
                ServerShopLeagueTasks.mobRefreshAllowed(b, nextHour + 2000L, 2, 3));
    }

    @Test
    public void mobilizationPositions_parseAndPick() {
        Map<Integer, Long> fromList = ServerShopLeagueTasks.parseMobPositions(
                l(m("id", 0L), m("id", 12L)), true);
        assertEquals(2, fromList.size());
        assertEquals("list 形态 index 从 1 起", Long.valueOf(12L), fromList.get(2));
        Map<Integer, Long> fromMap = ServerShopLeagueTasks.parseMobPositions(
                m("3", m("id", 33L), "4", m("id", 0L)), false);
        assertEquals(Long.valueOf(33L), fromMap.get(3));
        assertNull("keepEmpty=false 丢弃空槽", fromMap.get(4));
        assertEquals("取最小 pos 的已有任务槽", 3,
                ServerShopLeagueTasks.firstPositionWithTask(fromMap));
    }

    // ================= L4：争霸赛窗口 =================

    @Test
    public void showdownWindow_fridayRollsToNextMonday() {
        LocalTime time = LocalTime.MIDNIGHT;
        long friday = epoch("2026-10-23T03:00");
        long due = ServerShopLeagueTasks.showdownNextDueEpoch(friday, 1, time);
        assertEquals("周五已过本周窗口 → 下周一 00:00",
                epoch("2026-10-26T00:00"), due);
        assertFalse(ServerShopLeagueTasks.showdownWindowOpen(friday, 1, time));
        String reason = ServerShopLeagueTasks.showdownWindowSkipReason(friday, 1, time);
        assertNotNull(reason);
        assertTrue(reason.contains("窗口未到"));
    }

    @Test
    public void showdownWindow_mondayAndTuesdayInWindow() {
        LocalTime time = LocalTime.MIDNIGHT;
        long monday = epoch("2026-10-19T00:30");
        assertEquals(epoch("2026-10-19T00:00"),
                ServerShopLeagueTasks.showdownNextDueEpoch(monday, 1, time));
        assertTrue("周一 00:30 在窗口内", ServerShopLeagueTasks.showdownWindowOpen(monday, 1, time));

        long tuesdayLate = epoch("2026-10-20T22:00");
        assertTrue("周二 22:00 仍在截止前",
                ServerShopLeagueTasks.showdownWindowOpen(tuesdayLate, 1, time));

        long wednesday = epoch("2026-10-21T00:00");
        assertFalse("周三已过截止 → 下周",
                ServerShopLeagueTasks.showdownWindowOpen(wednesday, 1, time));
        assertEquals(epoch("2026-10-26T00:00"),
                ServerShopLeagueTasks.showdownNextDueEpoch(wednesday, 1, time));
    }

    @Test
    public void showdownWindow_tuesdaySignupDay() {
        LocalTime time = LocalTime.of(20, 0);
        long monday = epoch("2026-10-19T21:00");
        assertFalse("周二报名日：周一晚未到",
                ServerShopLeagueTasks.showdownWindowOpen(monday, 2, time));
        long tuesday = epoch("2026-10-20T20:30");
        assertTrue("周二 20:30 已到窗口",
                ServerShopLeagueTasks.showdownWindowOpen(tuesday, 2, time));
    }

    @Test
    public void showdownParams_defaultsAndClamp() {
        assertEquals(1, ServerShopLeagueTasks.showdownSignupDay(null));
        assertEquals(LocalTime.MIDNIGHT, ServerShopLeagueTasks.showdownSignupTime(null));
        assertEquals(2, ServerShopLeagueTasks.showdownPath(null));

        assertEquals(2, ServerShopLeagueTasks.showdownSignupDay(
                new JSONObject().put("signup_day", 2)));
        assertEquals("非法报名日回落周一", 1, ServerShopLeagueTasks.showdownSignupDay(
                new JSONObject().put("signup_day", 5)));
        assertEquals(LocalTime.of(20, 30), ServerShopLeagueTasks.showdownSignupTime(
                new JSONObject().put("signup_time", "20:30")));
        assertEquals("非法时间回落 00:00", LocalTime.MIDNIGHT,
                ServerShopLeagueTasks.showdownSignupTime(
                        new JSONObject().put("signup_time", "25:99")));
        assertEquals("path 上限 3", 3, ServerShopLeagueTasks.showdownPath(
                new JSONObject().put("path", 9)));
        assertEquals("path 下限 1", 1, ServerShopLeagueTasks.showdownPath(
                new JSONObject().put("path", 0)));
    }

    @Test
    public void showdownApplyFailureNote_classification() {
        assertTrue(ServerShopLeagueTasks.showdownApplyFailureNote(7705).contains("超时"));
        assertTrue(ServerShopLeagueTasks.showdownApplyFailureNote(401).contains("士兵不足"));
        assertTrue(ServerShopLeagueTasks.showdownApplyFailureNote(9999).contains("报名码"));
    }
}
