package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 奖励取数 P0 修复回归（task-5，2026-10-23）：把「协议名 / 包装层解析 / 参数构造 / 错误码分类」
 * 抽成纯函数单测（不依赖真协议/真会话）。对照 qa/audit-20261023/reward-gaps.md §3/§4/§5/§7。
 *
 * <p>覆盖旧实现三类根因的回归锚点：
 * <ul>
 *   <li>R1：_info/_init 双变体、bp_info/ve_info/ci_info/ogp_info/ht_info 包装层、权威协议名
 *       （notify_daily_claim_info / req_value_exchange_free_exchange / req_optional_gift_pack_get_free_box）、
 *       卡 BP/英雄集结/价值兑换参数构造、活动 id 常量表；</li>
 *   <li>R3：挂机累积秒数（600s 起领 / 32400s 封顶）；</li>
 *   <li>R5：邮件 5 板块类型表、unread||attach 候选、legacy mid_list 双字段、批量失败逐封兜底 key；</li>
 *   <li>R6：统帅礼包 {lv} 参数、4502/4503 → SKIP；</li>
 *   <li>L2：科技捐献 sciences Map 形态、免费次数投影、推荐科技优先、可跳过错误码。</li>
 * </ul>
 */
public class RewardClaimFixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------- helpers ----------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static GameMessage msg(Map<String, Object> content) {
        GameMessage g = new GameMessage();
        g.getContent().putAll(content);
        return g;
    }

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
    }

    private static long beijing(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.of("GMT+8")).toEpochSecond();
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    // =======================================================================
    // R1：协议名（网页版 DailyActivityRewardsService.java:398-411 claimOne 权威表）
    // =======================================================================

    @Test
    public void activityProtocolNames_matchWebAuthoritative() {
        // 远征宝藏实际推送是 notify_daily_claim_info（旧实现查 notify_daily_free_reward_info，不存在）
        assertEquals("notify_daily_claim_info", ServerTasks.NOTIFY_DAILY_CLAIM_INFO);
        assertEquals("req_daily_claim_free_reward", ServerTasks.PROTO_JOURNEY_TREASURE);
        // 英勇的见证 = 价值兑换免费档；req_hero_story_claim_reward 网页版源码 0 命中
        assertEquals("req_value_exchange_free_exchange", ServerTasks.PROTO_VALUE_EXCHANGE_FREE);
        assertEquals("req_value_exchange_exchange", ServerTasks.PROTO_VALUE_EXCHANGE);
        // 曙光市集 = optional_gift_pack 2508001-7；duck_market 协议族网页版不存在
        assertEquals("req_optional_gift_pack_get_free_box", ServerTasks.PROTO_OPTIONAL_GIFT_FREE_BOX);
        assertEquals("req_customize_item_get_free_box", ServerTasks.PROTO_CUSTOMIZE_FREE_BOX);
        assertEquals("req_multiple_choice_pack_get_free_box", ServerTasks.PROTO_MULTIPLE_CHOICE_FREE_BOX);
        // 卡 BP / 英雄集结 / 月卡 / 统帅礼包
        assertEquals("req_card_bp_collect_reward", ServerTasks.PROTO_CARD_BP_COLLECT);
        assertEquals("req_battle_pass_collect_all_reward", ServerTasks.PROTO_BATTLE_PASS_COLLECT_ALL);
        assertEquals("req_battle_pass_collect_reward", ServerTasks.PROTO_BATTLE_PASS_COLLECT);
        assertEquals("req_month_card_get_daily_reaward", ServerTasks.PROTO_MONTH_CARD_DAILY);
        assertEquals("req_vip_get_daily_free_gift", ServerTasks.PROTO_COMMANDER_GIFT);
        assertEquals("req_vip_get_daily_exp", ServerTasks.PROTO_VIP_DAILY_EXP);
        assertEquals("req_daily_bundle_get_free_gift", ServerTasks.PROTO_DAILY_SPECIAL);
    }

    @Test
    public void activityIdConstants_matchWebKeyTables() {
        assertEquals(2435002L, ServerTasks.ACT_JOURNEY_TREASURE_ID);
        assertEquals(2702001L, ServerTasks.ACT_VALUE_EXCHANGE_ID);
        assertEquals(2402001L, ServerTasks.ACT_WISH_HOUSE_ID);
        assertEquals(2703001L, ServerTasks.ACT_MIA_WISH_HOUSE_ID);
        assertEquals(2508008L, ServerTasks.ACT_DAWN_WISDOM_ID);
        assertEquals(2, ServerTasks.ACT_LOST_TREASURE_IDS.length);       // 2706001/2706002
        assertEquals(7, ServerTasks.ACT_DAWN_MARKET_IDS.length);         // 2508001-7
        assertEquals(6, ServerTasks.ACT_RARE_CUSTOM_IDS.length);
        assertEquals(4, ServerTasks.ACT_CRAFT_CUSTOM_IDS.length);
        assertEquals(5, ServerTasks.ACT_ARMAMENT_CUSTOM_IDS.length);
        assertEquals(2, ServerTasks.ACT_FISHING_IDS.length);
        assertEquals(10, ServerTasks.ACT_HERO_RALLY_IDS.length);         // 2401001-2401010
        assertEquals(6, ServerTasks.ACT_CARD_BP_IDS.length);             // 2505001-3/2506001-3
        assertEquals(14, ServerTasks.ACT_VALUE_EXCHANGE_DAILY_INDEX.size());
        assertEquals(14, ServerTasks.ACT_VALUE_EXCHANGE_WITNESS_INDEX.size());
        assertTrue(ServerTasks.isOneOf(2401010L, ServerTasks.ACT_HERO_RALLY_IDS));
        assertFalse(ServerTasks.isOneOf(2401011L, ServerTasks.ACT_HERO_RALLY_IDS));
    }

    // =======================================================================
    // R1：包装层解析（State.java:95-225 的 bp_info/ve_info/ci_info/ogp_info/ht_info）
    // =======================================================================

    @Test
    public void notch_drillsEachWrapperLayer() {
        assertEquals(2506003L, ServerTasks.extractIdDeep(
                map("info", map("id", 2506003L, "progress", 3300L)), "info").longValue());
        assertEquals(2401010L, ServerTasks.extractIdDeep(
                map("bp_info", map("id", 2401010L, "score", 40L)), "bp_info").longValue());
        assertEquals(2702001L, ServerTasks.extractIdDeep(
                map("ve_info", map("id", 2702001L, "version", 10L)), "ve_info").longValue());
        assertEquals(2507019L, ServerTasks.extractIdDeep(
                map("ci_info", map("id", 2507019L)), "ci_info").longValue());
        assertEquals(2508008L, ServerTasks.extractIdDeep(
                map("ogp_info", map("id", 2508008L)), "ogp_info").longValue());
        assertEquals(2402001L, ServerTasks.extractIdDeep(
                map("ht_info", map("id", 2402001L)), "ht_info").longValue());
        assertEquals(2703001L, ServerTasks.extractIdDeep(
                map("fc_info", map("id", 2703001L)), "fc_info").longValue());
        assertEquals(2509004L, ServerTasks.extractIdDeep(
                map("mcp_info", map("id", 2509004L)), "mcp_info").longValue());
        assertEquals(2418001L, ServerTasks.extractIdDeep(
                map("activity_info", map("id", 2418001L)), "activity_info").longValue());
        assertEquals(Long.valueOf(2707001L), ServerTasks.extractIdDeep(
                map("adventure_pve_info", map("id", 2707001L, "pass_checkpoint", 129L)),
                "adventure_pve_info"));
    }

    @Test
    public void wrapperId_oldExtractIdFails_deepSucceeds() {
        // 旧实现根因复现：WjdrEngine.extractId 只查顶层 → 包装层协议全 null
        Map<String, Object> cardBp = map("info", map("id", 2506003L, "progress", 3300L));
        assertNull(WjdrEngine.extractId(cardBp));
        assertEquals(Long.valueOf(2506003L), ServerTasks.extractIdDeep(cardBp, "info"));

        Map<String, Object> battlePass = map("bp_info", map("id", 2401010L, "score", 40L));
        assertNull(WjdrEngine.extractId(battlePass));
        assertEquals(Long.valueOf(2401010L), ServerTasks.extractIdDeep(battlePass, "bp_info"));
    }

    @Test
    public void notch_flatContentFallsBackAndNullSafe() {
        Map<String, Object> flat = map("id", 2435002L, "free_reward_ts", 1791776213L);
        assertEquals(flat, ServerTasks.notch(flat, "info"));
        assertEquals(Long.valueOf(2435002L), ServerTasks.extractIdDeep(flat, "info"));
        assertNull(ServerTasks.notch(null, "info"));
        assertNull(ServerTasks.extractIdDeep(null, "info"));
        // 顶层 activities[] 兜底（extractId 原有能力保持）
        Map<String, Object> listy = map("activities", Arrays.asList(
                (Object) map("id", 2401001L), map("id", 2401010L)));
        assertEquals(Long.valueOf(2401001L), ServerTasks.extractIdDeep(listy, "bp_info"));
    }

    @Test
    public void markedStageIds_parsesFreeRewardMarkMap() {
        Map<String, Object> freeReward = map(
                "1.0", map("id", 1L, "mark", "true"),
                "2.0", map("id", 2L, "mark", "false"),
                "3.0", map("mark", "true"),        // 无内层 id：从键 "3.0" 解析
                "4.0", map("id", 4L));             // 未标记
        Set<Long> ids = ServerTasks.markedStageIds(freeReward);
        assertEquals(new LinkedHashSet<>(Arrays.asList(1L, 3L)), ids);
        // 数组形态（部分版本 free_reward 为数组）
        Set<Long> arrIds = ServerTasks.markedStageIds(Arrays.asList(
                (Object) map("id", 7L, "mark", true), map("id", 8L, "mark", false)));
        assertEquals(new LinkedHashSet<>(Collections.singletonList(7L)), arrIds);
        assertTrue(ServerTasks.markedStageIds(null).isEmpty());
    }

    @Test
    public void exchangeValue_readsFloatKeyedMap() {
        Map<String, Object> exchangeData = map(
                "132.0", map("k", 132L, "v", 0L),
                "155.0", map("k", 155L, "v", 1L));
        assertEquals(0L, ServerTasks.exchangeValue(exchangeData, 132L));
        assertEquals(1L, ServerTasks.exchangeValue(exchangeData, 155L));
        assertEquals(-1L, ServerTasks.exchangeValue(exchangeData, 999L));   // 未知 ≠ 已兑换
        assertEquals(-1L, ServerTasks.exchangeValue(null, 132L));
        assertEquals(3L, ServerTasks.exchangeValue(map("66.0", 3L), 66L));  // 直接数字形态
    }

    @Test
    public void valueExchangeVersionIndexTables_matchWeb() {
        assertEquals(66L, ServerTasks.heroHallIndex(1L));
        assertEquals(132L, ServerTasks.heroHallIndex(10L));   // 抓包 version=10 → 132
        assertEquals(241L, ServerTasks.heroHallIndex(14L));
        assertEquals(5L, ServerTasks.witnessIndex(1L));
        assertEquals(155L, ServerTasks.witnessIndex(10L));
        assertEquals(240L, ServerTasks.witnessIndex(14L));
        assertEquals(0L, ServerTasks.heroHallIndex(99L));
        assertEquals(0L, ServerTasks.witnessIndex(99L));
    }

    @Test
    public void infiniteGroceryFreeOrders() {
        assertTrue(ServerTasks.infiniteFree(5L, 1L));
        assertTrue(ServerTasks.infiniteFree(5L, 4L));
        assertFalse(ServerTasks.infiniteFree(5L, 2L));   // 2 不在免费档位表
        assertFalse(ServerTasks.infiniteFree(17L, 1L));  // reward_group 上限 16
        assertFalse(ServerTasks.infiniteFree(0L, 1L));
    }

    @Test
    public void activityActiveAndSameBeijingDay() {
        long now = 1_000_000L;
        assertTrue(ServerTasks.activityActive(0L, 0L, now));
        assertTrue(ServerTasks.activityActive(now - 1L, now + 1L, now));
        assertFalse(ServerTasks.activityActive(now + 1L, 0L, now));
        assertFalse(ServerTasks.activityActive(0L, now, now));

        long early = beijing(2026, 10, 23, 0, 10);
        long late = beijing(2026, 10, 23, 23, 50);
        long nextDay = beijing(2026, 10, 24, 0, 10);
        assertTrue(ServerTasks.sameBeijingDay(early, late));
        assertFalse(ServerTasks.sameBeijingDay(late, nextDay));
        assertFalse(ServerTasks.sameBeijingDay(0L, late));
    }

    @Test
    public void numericKey_handlesFloatKeys() {
        assertEquals(132L, ServerTasks.numericKey("132.0"));
        assertEquals(132L, ServerTasks.numericKey(132L));
        assertEquals(132L, ServerTasks.numericKey(132.9));
        assertEquals(0L, ServerTasks.numericKey("x"));
        assertEquals(0L, ServerTasks.numericKey(null));
    }

    // =======================================================================
    // R1：参数构造（网页版 claimOne 协议参数；错误参数是生产失败根因）
    // =======================================================================

    @Test
    public void valueExchangeArgs_useVersionIndexTables() {
        Map<String, Object> hall = ServerTasks.heroHallArgs(10L);
        assertNotNull("version=10 必须有英雄殿堂每日档", hall);
        assertEquals(2702001L, ((Number) hall.get("id")).longValue());
        assertEquals(132L, ((Number) hall.get("idx")).longValue());
        assertEquals(1L, ((Number) hall.get("count")).longValue());

        Map<String, Object> witness = ServerTasks.heroicWitnessArgs(10L);
        assertNotNull("version=10 必须有英勇见证档", witness);
        assertEquals(2702001L, ((Number) witness.get("id")).longValue());
        assertEquals(155L, ((Number) witness.get("idx")).longValue());
        assertFalse("英勇见证是 free_exchange，无 count 参数", witness.containsKey("count"));

        assertNull(ServerTasks.heroHallArgs(99L));
        assertNull(ServerTasks.heroicWitnessArgs(99L));
    }

    @Test
    public void cardBpAndBattlePassArgs() {
        Map<String, Object> card = ServerTasks.cardBpStageArgs(2506003L, 7L);
        assertEquals(2506003L, ((Number) card.get("id")).longValue());
        assertEquals(7L, ((Number) card.get("stage_id")).longValue());
        assertEquals(2, card.size());

        Map<String, Object> bpAll = ServerTasks.battlePassAllArgs(2401010L);
        assertEquals(2401010L, ((Number) bpAll.get("id")).longValue());
        assertFalse(bpAll.containsKey("reward_id"));

        Map<String, Object> bpStage = ServerTasks.battlePassStageArgs(2401010L, 1086L);
        assertEquals(2401010L, ((Number) bpStage.get("id")).longValue());
        assertEquals(1086L, ((Number) bpStage.get("reward_id")).longValue());
    }

    @Test
    public void simpleIdArgs() {
        Map<String, Object> journey = ServerTasks.journeyTreasureArgs();
        assertEquals(2435002L, ((Number) journey.get("id")).longValue());
        assertEquals(2508003L, ((Number) ServerTasks.activityIdArgs(2508003L).get("id")).longValue());
    }

    @Test
    public void commanderGiftArgs_hasRequiredLv() {
        // 旧实现无参 → 生产 4503/失败；网页版 DailyCommanderGiftService.java:283 必传 {lv:vip_lv}
        Map<String, Object> args = ServerTasks.commanderGiftArgs(4L);
        assertEquals(1, args.size());
        assertEquals(4L, ((Number) args.get("lv")).longValue());
    }

    // =======================================================================
    // R1/R6：错误码分类（网页版 Service:219-239 / CommanderGiftService:188-192）
    // =======================================================================

    @Test
    public void activityUnavailableCodes_areSkippedNotError() {
        // 冷却/已领/未开放/未达标类错误码：不得记 ERROR
        for (int code : new int[]{2, 21, 61, 7501, 4303, 7401, 7404, 40610, 40611, 40612,
                7602, 7301, 7302, 7303, 7306, 7307, 8201, 8202, 8203, 4502, 4503,
                1201, 1202, 1204, 1215, 1528, 1545, 1510, 1511, 1577, 1581}) {
            assertTrue("码" + code + "应归 SKIP", ServerTasks.isActivityUnavailableCode(code));
        }
        assertFalse(ServerTasks.isActivityUnavailableCode(0));
        assertFalse(ServerTasks.isActivityUnavailableCode(9999));
    }

    @Test
    public void alreadyClaimedCodes_markLedgerOncePerDay() {
        for (int code : new int[]{21, 61, 7501, 40032, 40034, 40611, 7602, 4502, 1507}) {
            assertTrue("码" + code + "应标已领", ServerTasks.isActivityAlreadyClaimedCode(code));
        }
        assertFalse(ServerTasks.isActivityAlreadyClaimedCode(7401));
        assertFalse(ServerTasks.isActivityAlreadyClaimedCode(7307));
    }

    @Test
    public void classifyServerCode_commander4502And4503AreSkipped() {
        // 旧实现 4503 落 ERROR（classifyServerCode 白名单外），生产误报“服务端返回错误码 4503”
        TaskOutcome r4502 = ServerTasks.classifyServerCode(4502, "统帅礼包");
        assertEquals(TaskOutcome.Category.SKIPPED, r4502.category);
        assertTrue(r4502.message.contains("4502"));
        TaskOutcome r4503 = ServerTasks.classifyServerCode(4503, "统帅礼包");
        assertEquals(TaskOutcome.Category.SKIPPED, r4503.category);
        assertTrue(r4503.message.contains("4503"));
        TaskOutcome unknown = ServerTasks.classifyServerCode(9999, "统帅礼包");
        assertEquals(TaskOutcome.Category.ERROR, unknown.category);
    }

    @Test
    public void activityUnavailableText_distinguishesReasons() {
        assertTrue(ServerTasks.activityUnavailableText(7401).contains("未开启"));
        assertTrue(ServerTasks.activityUnavailableText(4503).contains("未激活"));
        assertTrue(ServerTasks.activityUnavailableText(8201).contains("档位"));
        assertTrue(ServerTasks.activityUnavailableText(1528).contains("次数"));
    }

    // =======================================================================
    // R3：挂机奖励（DailyAdventureRewardState.java:18-19,89-116）
    // =======================================================================

    @Test
    public void adventureAccumulatedSeconds_followsWebMinimumAndCap() {
        // 10 分钟（600s）起领
        assertEquals(0L, ServerTasks.adventureAccumulatedSeconds(1L, 1000L, 1000L));
        assertEquals(599L, ServerTasks.adventureAccumulatedSeconds(1L, 1000L, 1599L));
        assertEquals(600L, ServerTasks.adventureAccumulatedSeconds(1L, 1000L, 1600L));
        // 累积上限 32400s（9h）
        assertEquals(32400L, ServerTasks.adventureAccumulatedSeconds(1L, 1000L, 1000L + 40000L));
        // hang_start_time<=0 视为已满（State:91-96）
        assertEquals(32400L, ServerTasks.adventureAccumulatedSeconds(1L, 0L, 5000L));
        // 未解锁
        assertEquals(0L, ServerTasks.adventureAccumulatedSeconds(0L, 1000L, 5000L));
    }

    // =======================================================================
    // R5：邮件（DailyMailboxRewardService.java:145-154,187-215,240-329）
    // =======================================================================

    @Test
    public void mailTypesFor_defaultCoversAllFiveSections() {
        int[] all = ServerTasks.mailTypesFor("all");
        // 网页版 5 板块类型表：1+4+6+9+1
        assertEquals(21, all.length);
        // 旧实现默认 {10,20,30,40,50} → battle(110/130/140/160/210) 与 message(70..220) 全漏
        assertTrue(contains(all, 110));
        assertTrue(contains(all, 130));
        assertTrue(contains(all, 210));
        assertTrue(contains(all, 70));
        assertTrue(contains(all, 220));
        assertArrayEquals(new int[]{10}, ServerTasks.mailTypesFor("sys"));
        assertArrayEquals(new int[]{10, 20, 100, 120, 170}, ServerTasks.mailTypesFor("sys_ally"));
        int[] noBattle = ServerTasks.mailTypesFor("no_battle");
        assertFalse(contains(noBattle, 30));
        assertTrue(contains(noBattle, 40));
        assertArrayEquals(new int[]{10, 20}, ServerTasks.mailTypesFor("10,20"));
        assertArrayEquals(new int[]{10}, ServerTasks.mailTypesFor("bad,,"));
    }

    @Test
    public void mailCandidate_handlesNewAndLegacyIds() {
        ServerTasks.MailCandidate m1 = ServerTasks.mailCandidateOf(map(
                "mailid_new", "abc123", "mailid", 0L, "isread", 0L, "attach_statue", 1L));
        assertNotNull(m1);
        assertEquals("abc123", m1.newId);
        assertTrue(m1.attach);
        assertTrue(m1.unread);
        assertEquals("abc123", m1.key());

        // legacy 邮件只有数字 mailid，旧实现只读 mailid_new → 永远漏领
        ServerTasks.MailCandidate m2 = ServerTasks.mailCandidateOf(map(
                "mailid_new", "", "mailid", 777L, "isread", 0L, "attach_statue", 0L));
        assertNotNull(m2);
        assertEquals(777L, m2.legacyId);
        assertEquals("legacy:777", m2.key());
        assertFalse(m2.attach);

        // 已读且无附件 → 不是候选（网页版 :145-154）
        assertNull(ServerTasks.mailCandidateOf(map("mailid_new", "x", "isread", 1L, "attach_statue", 0L)));
        // 无任何 id → 无效候选
        assertNull(ServerTasks.mailCandidateOf(map("isread", 1L, "attach_statue", 1L)));
    }

    @Test
    public void mailBatchArgs_carryLegacyMidList() {
        Map<String, Object> batch = ServerTasks.mailBatchArgs(30,
                Arrays.asList("new-1", "new-2"), Arrays.asList(123L, 456L));
        assertEquals(30, ((Number) batch.get("first_type")).intValue());
        assertEquals(Arrays.asList("new-1", "new-2"), batch.get("mid_list_new"));
        assertEquals(Arrays.asList(123L, 456L), batch.get("mid_list"));
        // 只有 legacy：不出现空 mid_list_new 字段
        Map<String, Object> legacyOnly = ServerTasks.mailBatchArgs(10,
                Collections.emptyList(), Collections.singletonList(9L));
        assertFalse(legacyOnly.containsKey("mid_list_new"));
        assertEquals(Collections.singletonList(9L), legacyOnly.get("mid_list"));
        // 逐封兜底 payload：新 id 优先，legacy 用 mailid
        Map<String, Object> single = ServerTasks.mailSingleArgs(
                new ServerTasks.MailCandidate("mailid-1", 0L, true, true));
        assertEquals("mailid-1", single.get("mailid_new"));
        Map<String, Object> legacySingle = ServerTasks.mailSingleArgs(
                new ServerTasks.MailCandidate("", 42L, true, true));
        assertEquals(42L, ((Number) legacySingle.get("mailid")).longValue());
        assertFalse(legacySingle.containsKey("mailid_new"));
    }

    @Test
    public void mailConfirmKeys_andImportantFilter() {
        GameMessage rsp = msg(map(
                "readlist_new", Arrays.asList((Object) "a"),
                "readlist", Arrays.asList((Object) 123L)));
        Set<String> keys = ServerTasks.mailConfirmedKeys(rsp);
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("legacy:123"));
        assertEquals(2, keys.size());

        Set<String> important = ServerTasks.importantMailKeys(map("covers", Arrays.asList(
                (Object) map("mailid_new", "imp-1"),
                map("mailid", 55L))));
        assertTrue(important.contains("imp-1"));
        assertTrue(important.contains("legacy:55"));
        assertTrue(ServerTasks.importantMailKeys(null).isEmpty());
    }

    // =======================================================================
    // R6：统帅礼包（DailyCommanderGiftStateSnapshot.active / State.acceptVipState）
    // =======================================================================

    @Test
    public void commanderVipActive_matchesWebSnapshot() {
        long now = 1_700_000_000L;
        assertTrue("vip_lv>0 且未过期 → 可领", ServerTasks.commanderVipActive(4L, now + 100L, now));
        assertTrue("activeEnd 未知(0) 按有效处理", ServerTasks.commanderVipActive(4L, 0L, now));
        assertFalse("已过期 → 4503 语义", ServerTasks.commanderVipActive(4L, now, now));
        assertFalse("vip_lv=0 → 等级无效", ServerTasks.commanderVipActive(0L, now + 100L, now));
    }

    // =======================================================================
    // L2：科技捐献（DailyAllianceDonationService.java:500-556 / State.java:394-566）
    // =======================================================================

    @Test
    public void sciencesMapShape_parsedAndMerged() {
        // 抓包 proto_log.txt:83：sciences 是 Map<"id.0",{id,lv,donate}>；旧 Exec.mapsOf(List-only) 判空
        Map<String, Object> sciences = map(
                "6019.0", map("id", 6019L, "lv", 5L, "donate", 0L),
                "6023.0", map("id", 6023L, "lv", 0L, "donate", 257L));
        List<Map<String, Object>> parsed = Exec.mapsOf(sciences);
        assertEquals(2, parsed.size());
        Map<Long, long[]> table = new LinkedHashMap<>();
        for (Map<String, Object> s : parsed) ServerTasks.rememberScience(table, s);
        assertEquals(2, table.size());
        assertEquals(5L, table.get(6019L)[0]);

        // 在线事件 notify_alliance_science_exp_update.science 单条累积；lv 升高覆盖、降低不覆盖
        ServerTasks.rememberScience(table, map("id", 6023L, "lv", 2L, "donate", 999L));
        assertEquals(2L, table.get(6023L)[0]);
        ServerTasks.rememberScience(table, map("id", 6023L, "lv", 1L, "donate", 1L));
        assertEquals(2L, table.get(6023L)[0]);
    }

    @Test
    public void projectedRecoveryCount_matchesWebProjection() {
        Map<String, Object> rec = map("id", 205L, "count", 3L, "max_count", 10L,
                "next_calc_ts", 1000L, "recover", 1L, "interval", 1800L);
        assertEquals(3L, ServerTasks.projectedRecoveryCount(rec, 900L));   // 未到恢复点
        assertEquals(5L, ServerTasks.projectedRecoveryCount(rec, 1000L + 1800L)); // cycles=2 → 3+2
        // 封顶 max_count
        Map<String, Object> full = map("id", 205L, "count", 10L, "max_count", 10L,
                "next_calc_ts", 1000L, "recover", 1L, "interval", 1800L);
        assertEquals(10L, ServerTasks.projectedRecoveryCount(full, 999_999L));
        // 缺少 max/inter 时保持快照次数（宁漏不花：不臆测免费次数）
        Map<String, Object> unknown = map("id", 205L, "count", 2L, "max_count", 0L,
                "next_calc_ts", 0L, "recover", 1L, "interval", 0L);
        assertEquals(2L, ServerTasks.projectedRecoveryCount(unknown, 100L));
        assertEquals(0L, ServerTasks.projectedRecoveryCount(null, 100L));
    }

    @Test
    public void orderedScienceTargets_recommendedFirstThenAscending() {
        Map<Long, long[]> sciences = new LinkedHashMap<>();
        sciences.put(6023L, new long[]{0L, 0L});
        sciences.put(5006L, new long[]{5L, 0L});
        sciences.put(6019L, new long[]{5L, 0L});
        List<long[]> ordered = ServerTasks.orderedScienceTargets(sciences, 6023L);
        assertEquals(3, ordered.size());
        assertEquals(6023L, ordered.get(0)[0]);   // 推荐科技优先
        assertEquals(5006L, ordered.get(1)[0]);   // 其余按 id 升序
        assertEquals(6019L, ordered.get(2)[0]);
        assertEquals(5L, ordered.get(1)[1]);      // level 原值（领取时传 lv+1）
        assertTrue(ServerTasks.orderedScienceTargets(Collections.emptyMap(), 1L).isEmpty());
    }

    @Test
    public void donationSkipCodes_matchWebSkippableTargets() {
        assertTrue(ServerTasks.isDonationTargetSkipCode(2));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1570));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1577));
        assertTrue(ServerTasks.isDonationTargetSkipCode(1581));
        assertFalse(ServerTasks.isDonationTargetSkipCode(1528));   // 免费次数用尽 → 停
        assertFalse(ServerTasks.isDonationTargetSkipCode(1545));   // 资源不足 → 停
    }

    // =======================================================================
    // 节奏去重：ClaimLedger / 跨轮次指纹（TaskCadence 15s-3600s 防重复请求）
    // =======================================================================

    @Test
    public void activityLedger_dedupesPerTargetPerDay() {
        TaskContext tc = context();
        assertFalse(ClaimLedger.isClaimed(tc, "activity_rewards", "welfare_card:5000205"));
        ClaimLedger.mark(tc, "activity_rewards", "welfare_card:5000205");
        assertTrue(ClaimLedger.isClaimed(tc, "activity_rewards", "welfare_card:5000205"));
        // 同 scope 不同目标互不串味
        assertFalse(ClaimLedger.isClaimed(tc, "activity_rewards", "welfare_card:5000206"));
    }

    @Test
    public void stageFingerprints_persistAcrossRounds() {
        TaskContext tc = context();
        assertEquals(0L, ServerTasks.attemptedScore(tc, "hero_rally_2401010"));
        ServerTasks.saveModNumber(tc, "hero_rally_2401010", 700L);
        assertEquals(700L, ServerTasks.attemptedScore(tc, "hero_rally_2401010"));

        Set<Long> stages = new LinkedHashSet<>(Arrays.asList(1L, 2L, 5L));
        ServerTasks.saveModStages(tc, "card_bp_rx_2506003", stages);
        assertEquals(stages, ServerTasks.loadModStages(tc, "card_bp_rx_2506003"));
        assertTrue(ServerTasks.loadModStages(tc, "card_bp_rx_missing").isEmpty());
    }

    @Test
    public void cardBp8204_dedupesTodayOnly() throws Exception {
        // 2026-11-11：8204 归“该档暂不可领”，只写当日台账，不能写永久已领档。
        WjdrEngine engine = new WjdrEngine(null) {
            @Override
            public GameMessage request(String proto, Map<String, Object> args) {
                GameMessage g = new GameMessage();
                g.getContent().put("code",
                        "req_card_bp_collect_reward".equals(proto) ? 8204 : 0);
                return g;
            }
        };
        TaskContext tc = new TaskContext(engine, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "手动");
        ServerTasks.ClaimStat st = new ServerTasks.ClaimStat();
        boolean attempted = ServerTasks.claimCardBpStages(
                tc, st, 2506003L, new LinkedHashMap<>(), 5L);
        assertTrue(attempted);
        assertTrue("8204 按当日台账去重",
                ClaimLedger.isClaimed(tc, "card_bp", "2506003:1"));
        assertFalse("8204 不得写入永久已领档",
                ServerTasks.loadModStages(tc, "card_bp_rx_2506003").contains(1L));
    }
}
