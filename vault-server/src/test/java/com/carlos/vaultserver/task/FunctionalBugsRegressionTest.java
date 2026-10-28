package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.automation.services.MedicService;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 功能型 bug 回归（6 条用户反馈的端到端关键纯函数）：
 * <ol>
 *   <li>练兵不自动续 —— 服务端兵营档位/唤醒规则（见 TownBarracksArenaFixTest）；本文件锁竞技场/医院/情报/捐献的纯函数；</li>
 *   <li>情报只做营救不打狩猎 —— type2 营救不占普通队列、type1/11/100 需空闲队列（队列满时 type2 仍可派）；</li>
 *   <li>军医所有伤兵不自动治疗 —— 快照不能丢伤兵、按 heal_count 分批循环扣减、医院队列 qid 解析；</li>
 *   <li>不自动联盟互助 —— ServerHelpResponder 解析/响应码/当日结算（专项用例见 ServerHelpResponderTest）；</li>
 *   <li>联盟科技满了不自动捐 —— sciences/recovery 持久化 round-trip 与免费次数投影；</li>
 *   <li>竞技场从来没执行 —— 结算窗（23:10-24:00 北京）与今日挑战次数判定。</li>
 * </ol>
 *
 * <p>约定：只测纯函数/包内可见静态方法，不连真协议；与队友既有用例（IntelArenaFixTest /
 * RewardClaimFixTest / MedicServiceTest）互补，不重复删改。
 */
public class FunctionalBugsRegressionTest {

    private static final ZoneId BEIJING = ZoneId.of("GMT+8");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // =====================================================================
    // bug 3：军医所 —— 伤兵快照 / 批次规划 / 循环扣减 / 医院队列 qid
    // =====================================================================

    /** 生产根因：旧 snapshot() 把 wounded/healing 合并进局部 Map 后丢弃，woundedTotal() 恒 0。 */
    @Test
    public void hospitalSnapshot_keepsWoundedAndHealing() {
        Map<String, Object> init = map("data", map(
                "wound_soldiers", list(kv(10101, 376), kv(20101, 24)),
                "heal_data", map("soldiers", list(kv(30101, 10)))));
        Map<String, Object> push = map("wound_soldiers", list(kv(10101, 900), kv(20201, 100)));
        Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        notifies.put("notify_hospital_init", init);
        notifies.put("notify_hospital_wound_soldiers", push);

        MedicService.Snapshot s = MedicService.buildSnapshot(notifies);
        assertEquals("init+push 同兵种取 max，不能重复累加", Long.valueOf(900L), s.wounded.get(10101L));
        assertEquals(Long.valueOf(24L), s.wounded.get(20101L));
        assertEquals(Long.valueOf(100L), s.wounded.get(20201L));
        assertEquals("伤兵总数必须可见（旧实现恒 0）", 1024L, s.woundedTotal());
        assertEquals("治疗中批次单独统计", 10L, s.healingTotal());
        assertTrue("来源文案要可读", s.sources.contains("hospital_init"));
    }

    /** 快照空通知不能 NPE，且总数为 0（写“无伤兵”SKIP 而不是伪造批次）。 */
    @Test
    public void hospitalSnapshot_emptyNotifiesSafe() {
        MedicService.Snapshot s = MedicService.buildSnapshot(null);
        assertEquals(0L, s.woundedTotal());
        assertEquals("无军医所通知", s.sources);
    }

    /**
     * 批次规划：每批 min(剩余, heal_count)，高等级优先，兵种顺序 盾→弓→矛。
     * 15000 伤兵 3 类 × 5000，UI 默认 heal_count=400 → 首批 400。
     */
    @Test
    public void hospitalPlanBatch_truncatesByHealCount() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 5000L);   // 盾 lv1
        wounded.put(11101L, 5000L);   // 盾 lv11 → 等级优先
        wounded.put(20101L, 5000L);   // 矛 lv1

        Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 400);
        assertEquals("单批不得超过 heal_count", 400L, sum(batch));
        assertEquals("高等级伤兵优先治疗", Long.valueOf(400L), batch.get(11101L));
        assertNull("同批不能越过上限继续取低等级", batch.get(10101L));

        Map<Long, Long> all = MedicService.planHealingBatch(wounded, 0);
        assertEquals("heal_count=0/<=0 视为全部", 15000L, sum(all));
        assertEquals(3, all.size());
    }

    /** 单批治疗量上限 10000（App 输入框 1-10000，防呆超限请求）。 */
    @Test
    public void hospitalPlanBatch_capsAtMaxHealCount() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 9000L);
        wounded.put(20101L, 9000L);
        Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 20000);
        assertEquals("超出上限按 MAX_HEAL_COUNT 截断",
                MedicService.MAX_HEAL_COUNT, sum(batch));
        assertEquals(10000L, sum(batch));
    }

    /** 不可识别兵种（id/10000 不在 1..3）不参战规划，避免非法请求 payload。 */
    @Test
    public void hospitalPlanBatch_skipsUnknownSoldierTypes() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(40101L, 500L);   // 兵种 4：不可治疗
        wounded.put(10101L, 500L);
        Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 10000);
        assertEquals(1, batch.size());
        assertEquals(Long.valueOf(500L), batch.get(10101L));
    }

    /** 循环治疗：逐批扣减直到清零（单轮最多 MAX_BATCHES_PER_ROUND 批，防请求风暴）。 */
    @Test
    public void hospitalLoop_reducesUntilZero() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 800L);
        wounded.put(20101L, 400L);

        int batches = 0;
        long healedTotal = 0L;
        while (!wounded.isEmpty() && batches < MedicService.MAX_BATCHES_PER_ROUND) {
            Map<Long, Long> batch = MedicService.planHealingBatch(wounded, 400);
            assertFalse("有伤兵时必须能规划出批次", batch.isEmpty());
            long batchSum = sum(batch);
            assertTrue("每批不超过 heal_count", batchSum <= 400L);
            wounded = MedicService.subtractBatch(wounded, batch);
            healedTotal += batchSum;
            batches++;
        }
        assertEquals("1200 伤兵按 400/批 → 3 批清零", 3, batches);
        assertEquals(1200L, healedTotal);
        assertTrue("伤兵表清零", wounded.isEmpty());
    }

    /** subtractBatch 不改入参（副作用回归：旧实现直接改 Map 导致后续批次错乱）。 */
    @Test
    public void hospitalSubtractBatch_doesNotMutateInput() {
        Map<Long, Long> wounded = new LinkedHashMap<>();
        wounded.put(10101L, 100L);
        Map<Long, Long> batch = new LinkedHashMap<>();
        batch.put(10101L, 100L);
        Map<Long, Long> left = MedicService.subtractBatch(wounded, batch);
        assertTrue(left.isEmpty());
        assertEquals("入参必须保持原值", Long.valueOf(100L), wounded.get(10101L));
    }

    /** notify_queue_info：queue_map 中 base.qtype==6（医院队列）的 qid 才能作为求助目标。 */
    @Test
    public void hospitalQid_parsesHospitalQueueFromQueueMap() {
        Map<String, Object> content = map("data", map("queue_map", map(
                "9001", map("base", map("qtype", 3)),
                "9002", map("base", map("qtype", 6)))));
        assertEquals("取 qtype=6 的队列", 9002L, MedicService.hospitalQidOfNotify(content));

        // List 形状 + 显式 qid 字段
        Map<String, Object> listShape = map("queue_map", list(
                map("qid", 7001, "base", map("qtype", 1)),
                map("qid", 7002, "base", map("qtype", 6))));
        assertEquals(7002L, MedicService.hospitalQidOfQueueMap(listShape.get("queue_map")));
    }

    /** 未同步/无医院队列 → 0（状态行写“求助未发”，不能盲发 req_queue_ask_for_help）。 */
    @Test
    public void hospitalQid_missingReturnsZero() {
        assertEquals(0L, MedicService.hospitalQidOfNotify(null));
        assertEquals(0L, MedicService.hospitalQidOfQueueMap(null));
        assertEquals("无 qtype=6 不猜", 0L, MedicService.hospitalQidOfQueueMap(map(
                "9001", map("base", map("qtype", 1)))));
        assertEquals(0L, MedicService.hospitalQidOfNotify(map("code", 0)));
    }

    @Test
    public void hospitalConstants_matchUiContract() {
        assertEquals("App 默认每批 400、服务端上限 10000", 10000, MedicService.MAX_HEAL_COUNT);
        assertEquals("单轮最多 20 批防请求风暴", 20, MedicService.MAX_BATCHES_PER_ROUND);
        assertEquals("医院队列类型=6", 6L, MedicService.HOSPITAL_QUEUE_TYPE);
    }

    // =====================================================================
    // bug 2：情报 —— type2 营救不占普通队列 / type1/11/100 需空闲队列
    // =====================================================================

    /** 网页版 consumesNormalWorldMarchSlot：仅“非营救且是世界行军”的事件占普通队列。 */
    @Test
    public void intelConsumesNormalMarchSlot_onlyHuntingTypes() {
        assertFalse("营救(2) 不占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(2));
        assertTrue("怪物(1) 占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(1));
        assertTrue("活动巨兽(11) 占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(11));
        assertTrue("冒险巨兽(100) 占普通队列", ServerFieldTasks.intelConsumesNormalMarchSlot(100));
        assertFalse("英雄之旅(3) 走 PVE，不占世界队列",
                ServerFieldTasks.intelConsumesNormalMarchSlot(3));
        assertFalse("未知类型(0)", ServerFieldTasks.intelConsumesNormalMarchSlot(0));
    }

    /**
     * 生产根因：普通队列被采集/狩猎占满（world.idle=0）时，旧实现对所有世界行军一律
     * freeSlots<=0 → 营救照发、怪物/狩猎永不出征。修复后：
     * 营救门控放行且不扣槽；怪物门控 QUEUE_FULL。
     */
    @Test
    public void intelGate_rescuePassesWhenNormalQueueFull() {
        int allowedSlots = 1;        // 配置 queueCount=1
        int activeIntel = 1;         // 已有一支情报行军在途
        int worldIdle = 0;           // 普通队列被采集/狩猎占满
        int remaining = ServerFieldTasks.intelRemainingNormalSlots(
                allowedSlots, activeIntel, worldIdle);
        assertEquals("普通队列无空闲", 0, remaining);

        assertEquals("怪物情报需空闲普通队列 → 队列满拦截",
                ServerFieldTasks.INTEL_GATE_QUEUE_FULL,
                ServerFieldTasks.intelDispatchGate(1, remaining, false, Long.MAX_VALUE));
        assertEquals("营救情报不占普通队列 → 队列满仍可派",
                ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(2, remaining, false, Long.MAX_VALUE));
        assertEquals("活动巨兽同怪物",
                ServerFieldTasks.INTEL_GATE_QUEUE_FULL,
                ServerFieldTasks.intelDispatchGate(11, remaining, false, Long.MAX_VALUE));
    }

    /** remaining = clamp(min(queueCount, 世界容量) - 在途情报, 0, world.idle)；仅 type1/11/100 扣减。 */
    @Test
    public void intelRemainingNormalSlots_clampsByActiveAndWorldIdle() {
        assertEquals("1 队容量、无在途、世界空闲 1 → 1 可派",
                1, ServerFieldTasks.intelRemainingNormalSlots(1, 0, 1));
        assertEquals("在途 1 队占满", 0, ServerFieldTasks.intelRemainingNormalSlots(1, 1, 1));
        assertEquals("世界空闲 0（被采集占满）→ 0",
                0, ServerFieldTasks.intelRemainingNormalSlots(3, 0, 0));
        assertEquals("取世界空闲上限", 2, ServerFieldTasks.intelRemainingNormalSlots(3, 1, 2));
        assertEquals("负值防御", 0, ServerFieldTasks.intelRemainingNormalSlots(-1, 3, 2));

        // 世界空闲未知时的兜底口径：只扣在途情报（不能因采集占满而卡死营救/正常派队）
        assertEquals(1, ServerFieldTasks.intelRemainingNormalSlots(1, 0));
        assertEquals(0, ServerFieldTasks.intelRemainingNormalSlots(1, 2));
    }

    /** 体力门：未知不阻塞（登录推送未到时只做队列门）；不足拦截；足够放行。 */
    @Test
    public void intelGate_staminaSemantics() {
        assertEquals("体力未知不阻塞", ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(1, 1, false, 0L));
        assertEquals("营救耗 12 点、可用 11 → 不足", ServerFieldTasks.INTEL_GATE_STAMINA,
                ServerFieldTasks.intelDispatchGate(2, 0, true, 11L));
        assertEquals("可用 12 点刚好可派", ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(2, 0, true, 12L));
        assertEquals("怪物耗 10 点、可用 10 → 可派", ServerFieldTasks.INTEL_GATE_OK,
                ServerFieldTasks.intelDispatchGate(1, 1, true, 10L));
    }

    // =====================================================================
    // bug 5：联盟科技捐献 —— sciences/recovery 持久化 + 免费次数投影
    // =====================================================================

    private TaskContext context() {
        return new TaskContext(null, "48460403", 888L,
                new StateStore(tmp.getRoot().toPath()), Collections.emptyMap(), "服务器");
    }

    /** 持久化键按角色隔离：srv:alliance_science:<fpid>:<sid>（跨号/跨区不能串数据）。 */
    @Test
    public void donationKey_isPerRole() {
        assertEquals("srv:alliance_science:48460403:888",
                ServerTasks.allianceScienceKey("48460403", 888L));
        assertFalse(ServerTasks.allianceScienceKey("1", 1L)
                .equals(ServerTasks.allianceScienceKey("1", 2L)));
    }

    /** encode/decode round-trip：sciences/recommend/recovery/冷却时刻都必须保留。 */
    @Test
    public void donationState_encodeDecodeRoundTrip() {
        ServerTasks.AllianceScienceState st = new ServerTasks.AllianceScienceState();
        st.sciences.put(6019L, new long[]{5L, 120L});
        st.sciences.put(6023L, new long[]{2L, 0L});
        st.recommend = 6023L;
        Map<String, Object> recovery = map("id", 205L, "count", 3L, "max_count", 10L,
                "next_calc_ts", 1000L, "recover", 1L, "interval", 1800L);
        st.recovery = recovery;
        st.noFreeUntilSec = 1600L;
        st.targetCooldownUntilSec = 2600L;

        ServerTasks.AllianceScienceState back =
                ServerTasks.decodeAllianceScience(ServerTasks.encodeAllianceScience(st));
        assertEquals(2, back.sciences.size());
        assertEquals(5L, back.sciences.get(6019L)[0]);
        assertEquals(120L, back.sciences.get(6019L)[1]);
        assertEquals(6023L, back.recommend);
        assertTrue(back.recoveryKnown());
        assertEquals(3L, Exec.longOf(back.recovery.get("count")));
        assertEquals(1800L, Exec.longOf(back.recovery.get("interval")));
        assertEquals(1600L, back.noFreeUntilSec);
        assertEquals(2600L, back.targetCooldownUntilSec);
        assertTrue("updated_at 必须落时间戳", back.updatedAtMs > 0L);
    }

    /** 空/坏 JSON 不能抛异常，返回空状态（推送缺失时按“未知”处理，不盲捐）。 */
    @Test
    public void donationState_decodeGarbageSafe() {
        assertFalse(ServerTasks.decodeAllianceScience(null).sciencesKnown());
        assertFalse(ServerTasks.decodeAllianceScience("").sciencesKnown());
        assertFalse(ServerTasks.decodeAllianceScience("{not json").sciencesKnown());
        assertFalse(ServerTasks.decodeAllianceScience("{\"sciences\":{}}").recoveryKnown());
    }

    /** 会话重启/推送漏发后仍能捐：快照落 StateStore 再读回（sciences 可用）。 */
    @Test
    public void donationState_persistsAcrossSessionRestart() {
        TaskContext tc = context();
        assertFalse("首次无记录=空状态", ServerTasks.loadAllianceScience(tc).sciencesKnown());

        ServerTasks.AllianceScienceState st = new ServerTasks.AllianceScienceState();
        st.sciences.put(5006L, new long[]{7L, 10L});
        st.recommend = 5006L;
        st.recovery = map("id", 205L, "count", 4L, "max_count", 10L,
                "next_calc_ts", 2000L, "recover", 1L, "interval", 1800L);
        ServerTasks.saveAllianceScience(tc, st);
        tc.store.flush();   // 生产由 SessionKeeper 周期落盘；这里显式落盘模拟重启

        // 新 TaskContext/新 StateStore 实例模拟会话重启（重启时 StateStore 从 state.json 载入）
        StateStore reloaded = new StateStore(tmp.getRoot().toPath());
        reloaded.load();
        TaskContext restarted = new TaskContext(null, "48460403", 888L,
                reloaded, Collections.emptyMap(), "服务器");
        ServerTasks.AllianceScienceState back = ServerTasks.loadAllianceScience(restarted);
        assertTrue("持久化后 sciences 仍可用于捐献", back.sciencesKnown());
        assertEquals(7L, back.sciences.get(5006L)[0]);
        assertEquals(5006L, back.recommend);
        assertTrue(back.recoveryKnown());
        assertEquals("免费次数可由持久化快照投影",
                4L, ServerTasks.projectedRecoveryCount(back.recovery, 1000L));
    }

    /**
     * P0 2026-10-28 修复二：免费次数改为「服务端快照权威 + 本地已消费」，废弃旧双路取小
     * （旧逻辑持久化 count=0 会永久压住服务端真值 → 免费次数停在 24/25 不花）。
     */
    @Test
    public void donationFreeCount_serverSnapshotAuthoritative() {
        Map<String, Object> fresh = map("id", 205L, "count", 24L, "max_count", 25L,
                "next_calc_ts", 2000L, "recover", 1L, "interval", 600L);
        assertEquals("完全未知 → 未知（不盲捐付费）", -1L,
                ServerTasks.resolveFreeCount(new ServerTasks.AllianceScienceState(), null, 1000L));

        ServerTasks.AllianceScienceState st = new ServerTasks.AllianceScienceState();
        assertEquals("服务端快照积压一次可见（旧取小会得到 0）", 24L,
                ServerTasks.resolveFreeCount(st, fresh, 1000L));
        ServerTasks.recordDonated(st, 4L);
        assertEquals("本地已消费从投影中扣除", 20L,
                ServerTasks.resolveFreeCount(st, fresh, 1000L));

        Map<String, Object> newer = map("id", 205L, "count", 6L, "max_count", 25L,
                "next_calc_ts", 2600L, "recover", 1L, "interval", 600L);
        assertEquals("服务端新快照整体采纳并清零已消费", 6L,
                ServerTasks.resolveFreeCount(st, newer, 1000L));
    }

    /**
     * 免费次数投影后必须把 next_calc_ts 推进到未来第一个恢复点，否则下一轮会把已消费的
     * 周期重复补算 → 多捐（网页版 normalizeRecovery 同口径）。
     */
    @Test
    public void donationNormalizeRecovery_advancesNextCalc() {
        Map<String, Object> recovery = map("id", 205L, "count", 3L, "max_count", 10L,
                "next_calc_ts", 1000L, "recover", 1L, "interval", 1800L);
        long nowSec = 4600L;   // 已过 2 个恢复点（1000+1800*2=4600）
        long projected = ServerTasks.projectedRecoveryCount(recovery, nowSec);
        assertEquals("3 + 3 个周期 = 6", 6L, projected);

        Map<String, Object> normalized = ServerTasks.normalizeRecovery(recovery, nowSec, projected);
        assertEquals(6L, Exec.longOf(normalized.get("count")));
        long next = Exec.longOf(normalized.get("next_calc_ts"));
        assertTrue("next_calc_ts 必须推进到未来: " + next, next > nowSec);
        assertEquals("推进到 1000+3*1800", 6400L, next);
        // 归一化后立刻再投影不应再增长（幂等）
        assertEquals(6L, ServerTasks.projectedRecoveryCount(normalized, nowSec));
    }

    /** 1528 免费次数用尽后的冷却：优先 recovery.next_calc_ts，否则按 interval 兜底 ≥60s。 */
    @Test
    public void donationNoFreeUntil_usesNextCalcOrIntervalFloor() {
        long nowSec = 1000L;
        assertEquals("next 在未来 → 直接用", 2800L,
                ServerTasks.donationNoFreeUntil(map("next_calc_ts", 2800L, "interval", 1800L),
                        nowSec));
        assertEquals("next 已过 → now+interval", 2800L,
                ServerTasks.donationNoFreeUntil(map("next_calc_ts", 500L, "interval", 1800L),
                        nowSec));
        assertEquals("无 interval → 至少 60s（防 30s 节奏风暴）", 1300L,
                ServerTasks.donationNoFreeUntil(map("recover", 1L), nowSec));
        assertEquals("无 recovery → now+300s 兜底", 1300L,
                ServerTasks.donationNoFreeUntil(null, nowSec));
    }

    /** 合并科技表：fresh 同级更新覆盖，旧高等级不被低等级覆盖。 */
    @Test
    public void donationMergeSciences_keepsHigherLevel() {
        Map<Long, long[]> into = new LinkedHashMap<>();
        into.put(5006L, new long[]{5L, 0L});
        into.put(6019L, new long[]{1L, 0L});
        Map<Long, long[]> fresh = new LinkedHashMap<>();
        fresh.put(5006L, new long[]{4L, 0L});   // 低等级：不覆盖
        fresh.put(6019L, new long[]{3L, 20L});  // 高等级：覆盖
        ServerTasks.mergeSciences(into, fresh);
        assertEquals(5L, into.get(5006L)[0]);
        assertEquals(3L, into.get(6019L)[0]);
        assertEquals(20L, into.get(6019L)[1]);
    }

    // =====================================================================
    // bug 6：竞技场 —— 结算窗与今日挑战次数（纯函数，不连协议）
    // =====================================================================

    private static long beijing(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BEIJING)
                .toInstant().toEpochMilli();
    }

    /** 结算窗：北京时间 23:10 开始，00:00 跨天结束（网页版 settlementStartsAt = 次日0点 - 3000s）。 */
    @Test
    public void arenaRewardWindow_beijing2310to2400() {
        assertFalse("23:09 未进窗", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 28, 23, 9)));
        assertTrue("23:10 进窗", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 28, 23, 10)));
        assertTrue("23:59 仍在窗内", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 28, 23, 59)));
        assertFalse("00:00 结算窗结束", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 29, 0, 0)));
        assertFalse("白天不结算", ServerTownTasks.arenaInDailyRewardWindow(
                beijing(2026, 10, 28, 12, 0)));
    }

    /** 今日已挑战场次 = free_times_use + max(0, buy_times-left_times)（网页版同口径）。 */
    @Test
    public void arenaChallengesCompleted_countsFreeAndBought() {
        assertEquals(0, ServerTownTasks.arenaChallengesCompleted(0, 0, 0));
        assertEquals(5, ServerTownTasks.arenaChallengesCompleted(5, 0, 0));
        assertEquals("买 2 次全用完 = 5 场", 5,
                ServerTownTasks.arenaChallengesCompleted(3, 2, 0));
        assertEquals("买 2 次还剩 1 次 = 4 场", 4,
                ServerTownTasks.arenaChallengesCompleted(3, 2, 1));
        assertEquals("负值防御", 0, ServerTownTasks.arenaChallengesCompleted(-1, -2, -1));
    }

    /** 每日可打场次包含买次数；免费 5 场先打，结算窗内领奖要求完成 5 场。 */
    @Test
    public void arenaFightCap_andRewardGate() {
        assertEquals(5, ServerTownTasks.arenaDailyFightCap(0));
        assertEquals(8, ServerTownTasks.arenaDailyFightCap(3));
        assertEquals(5, ServerTownTasks.arenaDailyFightCap(-2));

        assertTrue("5 场达标可领", ServerTownTasks.arenaRewardAttemptAllowed(5, 5));
        assertTrue("计数未知(-1)保守允许领", ServerTownTasks.arenaRewardAttemptAllowed(-1, 0));
        assertFalse("明确不足且无兜底证据不请求", ServerTownTasks.arenaRewardAttemptAllowed(3, 3));
        assertTrue("本地已发 5 场但服务端计数陈旧 → 再试一次", 
                ServerTownTasks.arenaRewardAttemptAllowed(0, 5));
    }

    /** 领取失败 5 分钟重试冷却（防 30s 节奏风暴）。 */
    @Test
    public void arenaRewardRetryCooldown_fiveMinutes() {
        long now = System.currentTimeMillis();
        assertTrue("首次无记录可领", ServerTownTasks.arenaRewardRetryDue(0L, now));
        assertFalse("4:59 仍在冷却", ServerTownTasks.arenaRewardRetryDue(
                now - ServerTownTasks.ARENA_REWARD_RETRY_MILLIS + 1000L, now));
        assertTrue("5:00 可重试", ServerTownTasks.arenaRewardRetryDue(
                now - ServerTownTasks.ARENA_REWARD_RETRY_MILLIS, now));
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static List<Object> list(Object... values) {
        List<Object> l = new ArrayList<>();
        for (Object v : values) l.add(v);
        return l;
    }

    private static Map<String, Object> kv(long k, long v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("v", v);
        return m;
    }

    private static long sum(Map<Long, Long> m) {
        long n = 0L;
        for (long v : m.values()) n += v;
        return n;
    }
}
