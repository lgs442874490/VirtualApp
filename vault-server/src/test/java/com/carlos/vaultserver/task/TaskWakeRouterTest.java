package com.carlos.vaultserver.task;

import com.carlos.vaultserver.StateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C2 推送事件唤醒路由器单测（qa/audit-20261023/reward-gaps.md §6、wilderness-gaps.md §2.3）。
 *
 * <p>覆盖：协议 → 任务映射（含通配前缀/未知协议）、同 (fpid,fn) 10s 节流（可注入时钟）、
 * 命中后把 {@code srv:cad:{fpid}:{sid}:{fn}} 写成 0、参数非法/未知协议零副作用、
 * 映射任务 id 必须全部存在于 {@link TaskCadence} 节奏表（否则写了键也没人扫）。
 */
public class TaskWakeRouterTest {

    private static final String FPID = "48460403";
    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore stateStore;

    @Before
    public void setUp() {
        stateStore = new StateStore(tmp.getRoot().toPath());
    }

    // ---------------- 映射判定（纯函数） ----------------

    @Test
    public void tasksForProtocol_activityRewardsFamily() {
        Set<String> triggers = new HashSet<>(Arrays.asList(
                "notify_daily_claim_info",          // 远征宝藏
                "notify_value_exchange_init",       // 英勇的见证 / 英雄殿堂
                "notify_value_exchange_info",
                "notify_card_bp_info",              // 卡 BP
                "notify_battle_pass_info",          // 英雄集结
                "notify_battle_pass_prepared",
                "notify_customize_item_init",       // 奇货/匠心/武备自选
                "notify_customize_item_info",
                "notify_multiple_choice_pack_init", // 匠人宝库
                "notify_hero_turntable_init",       // 许愿屋
                "notify_optional_gift_pack_info",   // 曙光市集/曙光智慧
                "notify_month_card_info",           // 月卡每日奖励
                "notify_daily_bundle_info",         // 每日特惠
                "notify_fortune_card_info",         // 米娅许愿屋
                "notify_infinite_box_info",         // 无限宝箱
                "notify_lost_treasure_info",        // 失落宝藏
                "notify_fishing_activity_info",     // 钓鱼
                "notify_activity_calendar_data"));  // 活动日历
        for (String proto : triggers) {
            assertTrue(proto + " → activity_rewards",
                    TaskWakeRouter.tasksForProtocol(proto).contains("activity_rewards"));
        }
    }

    @Test
    public void tasksForProtocol_vipWakesActivityRewardsAndCommanderGift() {
        // 网页版 ActivityRewardsTaskModule:21 的 notify_vip* + CommanderGiftTaskModule:39 的 policy：
        // 同一个 vip 推送必须同时唤醒两个任务（集合语义，而非单值覆盖）。
        for (String proto : Arrays.asList("notify_vip_info", "notify_vip_update")) {
            Set<String> fns = TaskWakeRouter.tasksForProtocol(proto);
            assertTrue(proto + " → activity_rewards", fns.contains("activity_rewards"));
            assertTrue(proto + " → commander_gift", fns.contains("commander_gift"));
        }
    }

    @Test
    public void tasksForProtocol_allianceFamilies() {
        for (String proto : Arrays.asList("notify_alliance_chest_init", "notify_alliance_chest_add",
                "notify_alliance_chest_update", "notify_alliance_chest_delete",
                "notify_alliance_chest_brilliant_update")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("alliance_chest"));
        }
        for (String proto : Arrays.asList("notify_alliance_science_init",
                "notify_alliance_science_exp_update", "notify_alliance_science_research_finish",
                "notify_alliance_science_recommend_update", "notify_alliance_dev_init",
                "notify_alliance_dev_update")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("alliance_donate"));
        }
        for (String proto : Arrays.asList("notify_recover_resource_init",
                "notify_recover_resource_update")) {
            Set<String> fns = TaskWakeRouter.tasksForProtocol(proto);
            assertTrue(proto + " → alliance_donate（免费捐献次数 205）", fns.contains("alliance_donate"));
            assertTrue(proto + " → supply", fns.contains("supply"));
        }
        for (String proto : Arrays.asList("notify_alliance_help_init", "notify_alliance_help_update",
                "notify_alliance_help_update_all", "notify_alliance_help_auto_help")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("alliance_help"));
        }
        for (String proto : Arrays.asList("notify_alliance_showdown_activity",
                "notify_alliance_showdown_global_activity", "notify_alliance_showdown_grade")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("alliance_showdown"));
        }
        for (String proto : Arrays.asList("notify_mobilization_init",
                "notify_mobilization_score_update", "notify_mobilization_alliance_init")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("alliance_mobilization"));
        }
    }

    @Test
    public void tasksForProtocol_worldFamilies() {
        for (String proto : Arrays.asList("notify_player_march_add", "notify_player_march_update",
                "notify_player_march_del", "notify_world_resource_info")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("gather"));
        }
        for (String proto : Arrays.asList("notify_intelligence_info", "notify_intelligence_events",
                "notify_intelligence_events_del", "notify_intelligence_battle_result",
                "notify_intelligence_power_info")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("intel"));
        }
        for (String proto : Arrays.asList("notify_arena_init", "notify_arena_report")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("arena"));
        }
    }

    @Test
    public void tasksForProtocol_rewardGapTasks() {
        for (String proto : Arrays.asList("notify_dailyquests", "notify_update_dailyquest",
                "notify_update_dailyquest_integral")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("daily_mission"));
        }
        for (String proto : Arrays.asList("notify_new_mail_syn", "notify_mail_type_info_syn",
                "notify_unlock_mail_attach", "notify_mail_important")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("mail"));
        }
        assertTrue(TaskWakeRouter.tasksForProtocol("notify_adventure_pve_info").contains("adventure"));
        for (String proto : Arrays.asList("notify_reward_info", "notify_start_online_reward",
                "notify_online_reward_timeup")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("supply"));
        }
        for (String proto : Arrays.asList("notify_general_bp_normal_info",
                "notify_general_bp_extra_info", "notify_general_bp_integral_update",
                "notify_general_bp_purchase")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("act_endless"));
        }
        for (String proto : Arrays.asList("notify_fourteen_sign_info", "notify_fourteen_purchase")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("login_rewards"));
        }
        assertTrue(TaskWakeRouter.tasksForProtocol("notify_red_packet_user_info").contains("red_packet"));
        assertTrue(TaskWakeRouter.tasksForProtocol("notify_achievement_info").contains("achievement_claim"));
        for (String proto : Arrays.asList("notify_chapterquests", "notify_growthquests")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("chapter_quest"));
        }
        for (String proto : Arrays.asList("notify_backpack_init", "notify_backpack_update")) {
            assertTrue(proto, TaskWakeRouter.tasksForProtocol(proto).contains("backpack"));
        }
    }

    @Test
    public void tasksForProtocol_unknownProtocolIsEmpty() {
        for (String proto : Arrays.asList("req_world_march", "notify_unknown_xyz", "notify_logout",
                "notify_game_init", "notify_server_time", "", null)) {
            Set<String> fns = TaskWakeRouter.tasksForProtocol(proto);
            assertNotNull(fns);
            assertTrue("未知协议必须零映射：" + proto, fns.isEmpty());
        }
    }

    @Test
    public void mappedTaskIds_allExistInCadenceTable() {
        Set<String> expected = new HashSet<>(Arrays.asList(
                "activity_rewards", "commander_gift", "mail", "daily_mission", "adventure", "supply",
                "alliance_chest", "alliance_donate", "alliance_mobilization", "alliance_showdown",
                "alliance_help", "arena", "gather", "intel", "act_endless", "login_rewards",
                "red_packet", "achievement_claim", "chapter_quest", "backpack",
                // 批次4 C3 配套：巨熊预约/场次/集结推送、专家推送也纳入唤醒
                "bear", "expert",
                // 2026-10-28 城镇域功能型 bug：训练完成即续训（notify_barrack_*）、
                // 治疗完成即续疗（notify_hospital_*）
                "barracks", "medic"));
        assertEquals(expected, new HashSet<>(TaskWakeRouter.mappedTaskIds()));
        for (String fn : TaskWakeRouter.mappedTaskIds()) {
            assertTrue("映射任务必须在 TaskCadence 节奏表中：" + fn, TaskCadence.contains(fn));
            assertTrue("节奏间隔必须 >= 安全下限：" + fn,
                    TaskCadence.intervalSeconds(fn) >= TaskCadence.MIN_INTERVAL_SECONDS);
        }
        assertTrue("映射规则应覆盖网页版 16 类活动通知 + 奖励缺口任务", TaskWakeRouter.ruleCount() >= 30);
    }

    @Test
    public void cadenceKey_matchesSchedulerFormat() {
        // 必须与 TaskCadenceScheduler 的私有 nextKey 完全一致，否则调度器扫不到
        assertEquals("srv:cad:48460403:888:gather",
                TaskWakeRouter.cadenceKey("48460403", 888L, "gather"));
    }

    // ---------------- 写键（stateStore 副作用） ----------------

    @Test
    public void onNotify_writesZeroToCadenceKey() {
        TaskWakeRouter router = new TaskWakeRouter(stateStore);
        router.onNotify(FPID, SID, "notify_daily_claim_info");   // 命中 activity_rewards

        String key = "srv:cad:" + FPID + ":" + SID + ":activity_rewards";
        Map<String, Object> snap = stateStore.snapshot();
        Object v = snap.get(key);
        assertNotNull("未写 cadence 键：" + key, v);
        assertTrue(v instanceof Number);
        assertEquals("值必须为 0（=立即到期）", 0L, ((Number) v).longValue());
    }

    @Test
    public void onNotify_multiTaskProtocolWritesAllKeys() {
        TaskWakeRouter router = new TaskWakeRouter(stateStore);
        int written = router.onNotifyAt(FPID, SID, "notify_recover_resource_init", 1_000_000L);
        assertEquals(2, written);   // alliance_donate + supply

        Map<String, Object> snap = stateStore.snapshot();
        for (String fn : Arrays.asList("alliance_donate", "supply")) {
            String key = "srv:cad:" + FPID + ":" + SID + ":" + fn;
            assertNotNull("未写 " + key, snap.get(key));
            assertEquals(0L, ((Number) snap.get(key)).longValue());
        }
    }

    @Test
    public void onNotify_unknownProtocolHasNoSideEffects() {
        TaskWakeRouter router = new TaskWakeRouter(stateStore);
        assertEquals(0, router.onNotifyAt(FPID, SID, "req_world_march", 1_000_000L));
        assertEquals(0, router.onNotifyAt(FPID, SID, "notify_unknown_xyz", 1_000_000L));
        assertTrue("未知协议不得写任何状态键", stateStore.snapshot().isEmpty());
    }

    @Test
    public void onNotify_invalidArgsHaveNoSideEffects() {
        TaskWakeRouter router = new TaskWakeRouter(stateStore);
        assertEquals(0, router.onNotifyAt(null, SID, "notify_dailyquests", 1_000_000L));
        assertEquals(0, router.onNotifyAt("", SID, "notify_dailyquests", 1_000_000L));
        assertEquals(0, router.onNotifyAt(FPID, 0L, "notify_dailyquests", 1_000_000L));   // 登录未落地
        assertEquals(0, router.onNotifyAt(FPID, -1L, "notify_dailyquests", 1_000_000L));
        assertEquals(0, router.onNotifyAt(FPID, SID, null, 1_000_000L));
        assertTrue(stateStore.snapshot().isEmpty());
    }

    // ---------------- 节流（纯函数 + 行为） ----------------

    @Test
    public void isThrottled_pureFunction() {
        assertFalse(TaskWakeRouter.isThrottled(0L, 1_000_000L, TaskWakeRouter.THROTTLE_MILLIS));
        assertFalse(TaskWakeRouter.isThrottled(-5L, 1_000_000L, TaskWakeRouter.THROTTLE_MILLIS));
        assertTrue(TaskWakeRouter.isThrottled(1_000_000L, 1_000_000L, TaskWakeRouter.THROTTLE_MILLIS));
        assertTrue(TaskWakeRouter.isThrottled(1_000_000L, 1_009_999L, TaskWakeRouter.THROTTLE_MILLIS));
        assertFalse("满 10s 必须放行",
                TaskWakeRouter.isThrottled(1_000_000L, 1_010_000L, TaskWakeRouter.THROTTLE_MILLIS));
        // 时钟回拨视为未节流（否则一次回拨会误伤一个回拨窗口）
        assertFalse(TaskWakeRouter.isThrottled(2_000_000L, 1_000_000L, TaskWakeRouter.THROTTLE_MILLIS));
    }

    @Test
    public void onNotify_throttlesSameTaskWithinTenSeconds() {
        long[] now = {1_000_000L};
        TaskWakeRouter router = new TaskWakeRouter(stateStore, () -> now[0]);
        String key = "srv:cad:" + FPID + ":" + SID + ":daily_mission";

        assertEquals(1, router.onNotifyAt(FPID, SID, "notify_dailyquests", now[0]));
        assertEquals(0L, ((Number) stateStore.snapshot().get(key)).longValue());

        // 模拟调度器派单后写入了下一个到期时刻；节流窗口内的推送不得把它重置为 0
        stateStore.putLong(key, 424_242L);
        now[0] += 5_000L;
        assertEquals(0, router.onNotifyAt(FPID, SID, "notify_update_dailyquest", now[0]));
        assertEquals("10s 内不得重写（避免推送风暴放大请求）", 424_242L,
                ((Number) stateStore.snapshot().get(key)).longValue());

        // 满 10s 放行
        now[0] += 5_000L;
        assertEquals(1, router.onNotifyAt(FPID, SID, "notify_dailyquests", now[0]));
        assertEquals(0L, ((Number) stateStore.snapshot().get(key)).longValue());
    }

    @Test
    public void onNotify_throttleIsKeyedByFpidAndTaskNotSid() {
        long[] now = {1_000_000L};
        TaskWakeRouter router = new TaskWakeRouter(stateStore, () -> now[0]);

        assertEquals(1, router.onNotifyAt(FPID, SID, "notify_dailyquests", now[0]));
        // 同 fpid 不同任务：不受 daily_mission 的节流影响
        assertEquals(1, router.onNotifyAt(FPID, SID, "notify_new_mail_syn", now[0]));
        // 同 fpid 同任务不同 sid：同一 (fpid,fn) 预算，仍被节流
        assertEquals(0, router.onNotifyAt(FPID, 999L, "notify_dailyquests", now[0]));
        // 不同 fpid：各自独立
        assertEquals(1, router.onNotifyAt("other-account", SID, "notify_dailyquests", now[0]));

        Map<String, Object> snap = stateStore.snapshot();
        assertNotNull(snap.get("srv:cad:" + FPID + ":" + SID + ":daily_mission"));
        assertNotNull(snap.get("srv:cad:" + FPID + ":" + SID + ":mail"));
        assertNotNull(snap.get("srv:cad:other-account:" + SID + ":daily_mission"));
        assertEquals("同 (fpid,fn) 节流后不应为兄弟 sid 写键", 3, snap.size());
    }

    @Test
    public void onNotify_offlineGuardIsCallerConcern_routerWritesWhenInvoked() {
        // 会话在线/离线守卫在 SessionKeeper 钩子内（离线不调用本类）；router 只对传入参数负责。
        TaskWakeRouter router = new TaskWakeRouter(stateStore, () -> 1_000_000L);
        assertEquals(Collections.singleton("gather"),
                TaskWakeRouter.tasksForProtocol("notify_world_resource_info"));
        assertEquals(1, router.onNotifyAt(FPID, SID, "notify_world_resource_info", 1_000_000L));
    }
}
