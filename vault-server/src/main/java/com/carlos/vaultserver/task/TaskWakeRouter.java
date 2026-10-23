package com.carlos.vaultserver.task;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.StateStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * notify 推送事件 → 任务唤醒路由器（C2，网页版 TaskEngine 通知唤醒语义的服务端最小实现）。
 *
 * <p><b>背景</b>：网页版 {@code TaskEngine} 把每条 inbound notify 变成
 * {@code PROTOCOL_NOTIFICATION} 信号，只投递给 {@code interestedProtocols()} 命中的模块，
 * 模块随即被唤醒重新 {@code decide}（{@code engine/TaskSignalBus.java:36-65}、
 * {@code engine/TaskEngine.java:446-483}）。我方服务器原先只有「每天整轮」+ 固定间隔的
 * {@link TaskCadenceScheduler}，日内推送（新邮件/宝箱/活动初始化/科技捐献恢复等）无法即时触发，
 * 大量奖励要等次日或永远错过（qa/audit-20261023/reward-gaps.md §6、wilderness-gaps.md §2.3）。
 *
 * <p><b>做法</b>：不改调度器。{@link TaskCadenceScheduler} 每 2 秒扫描
 * {@code srv:cad:{fpid}:{sid}:{fn}}，值 {@code <= now} 即派单；本类在推送到达时把命中任务的
 * cadence 键写成 {@code 0}（=立即到期），调度器下一拍就会单任务执行，天然复用其
 * 会话忙/战时让位/最小间隔/整轮兜底等安全闸。
 *
 * <p><b>映射来源</b>：逐个 {@code task/*}{@code TaskModule.java} 的
 * {@code interestedProtocols()/TRIGGER_PROTOCOLS}（权威），再补本周审计点名的缺口任务
 * （daily_mission / mail / adventure / supply / commander_gift / act_endless / red_packet /
 * achievement_claim / chapter_quest / backpack）。未知协议一律零副作用。
 *
 * <p><b>节流</b>：同一 {@code (fpid, fn)} 至少 {@link #THROTTLE_MILLIS} 毫秒才允许再次写键，
 * 避免推送风暴把 10 秒级高频 notify（行军/情报/恢复资源）放大成请求风暴。
 * 节流键不含 sid（同一账号多角色共用一个唤醒预算），与需求口径一致。
 *
 * <p><b>线程模型</b>：{@link #onNotify} 在引擎 inbound 线程执行（每个会话一条），
 * {@link StateStore#putLong} 自身加锁、节流表为 {@link ConcurrentHashMap}，因此本类线程安全。
 */
public final class TaskWakeRouter {

    /**
     * 同一 {@code (fpid, fn)} 的最小唤醒间隔（毫秒）。
     * 网页版领取类模块节奏 30-180s，这里取 10s 作为「推送驱动」的上限频率：
     * 既能让事件立即生效，又不会把高频推送放大成协议风暴。
     */
    public static final long THROTTLE_MILLIS = 10_000L;

    /** cadence 键前缀：必须与 {@link TaskCadenceScheduler} 的 {@code nextKey(...)} 完全一致。 */
    static final String CADENCE_KEY_PREFIX = "srv:cad:";

    private final StateStore stateStore;
    private final LongSupplier clock;
    /** 节流表：{@code fpid|fn → 上次写键时刻}（毫秒）。 */
    private final Map<String, Long> lastWakeAt = new ConcurrentHashMap<>();
    /** 首次唤醒日志去重（每个 fpid|fn 只打一条，防日志刷屏）。 */
    private final Set<String> wakeLogged = ConcurrentHashMap.newKeySet();

    public TaskWakeRouter(StateStore stateStore) {
        this(stateStore, System::currentTimeMillis);
    }

    /** 可注入时钟构造（单测：确定性节流断言）。 */
    TaskWakeRouter(StateStore stateStore, LongSupplier clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 处理一条 inbound 消息：命中映射 → 把对应任务的 cadence 键写成 0（立即到期）。
     *
     * @param fpid  账号指纹（空/null 直接忽略）
     * @param sid   角色区服 id（&lt;=0 = 登录未落地，直接忽略）
     * @param proto 协议名（非 notify / 未知协议零副作用）
     */
    public void onNotify(String fpid, long sid, String proto) {
        onNotifyAt(fpid, sid, proto, clock.getAsLong());
    }

    /**
     * 可注入时间的实现（包级可见便于单测）。
     *
     * @return 实际写入 cadence 键的任务数（0 = 未知协议 / 参数非法 / 全部被节流）
     */
    int onNotifyAt(String fpid, long sid, String proto, long now) {
        if (fpid == null || fpid.trim().isEmpty() || sid <= 0L || proto == null || proto.isEmpty()) {
            return 0;
        }
        Set<String> taskIds = tasksForProtocol(proto);
        if (taskIds.isEmpty()) return 0;                 // 未知协议：零副作用
        int written = 0;
        for (String fn : taskIds) {
            if (!claimWakeSlot(fpid, fn, now)) continue; // 同 (fpid,fn) 10s 节流
            stateStore.putLong(cadenceKey(fpid, sid, fn), 0L);
            written++;
            if (wakeLogged.add(fpid + "|" + fn)) {
                FileLog.info("推送唤醒 fpid={} fn={} proto={}（cadence=0，节流 {}s）",
                        fpid, fn, proto, String.valueOf(THROTTLE_MILLIS / 1000L));
            }
        }
        return written;
    }

    /**
     * cadence 键（与 {@link TaskCadenceScheduler} 私有 nextKey 同格式）。
     * 这里只读复用同一状态键，不改调度器代码；若调度器键格式变更须同步。
     */
    public static String cadenceKey(String fpid, long sid, String fn) {
        return CADENCE_KEY_PREFIX + fpid + ":" + sid + ":" + fn;
    }

    /** 协议 → 任务 id 映射（纯函数；未知协议返回空集合，绝不返回 null）。 */
    public static Set<String> tasksForProtocol(String proto) {
        if (proto == null || proto.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        Set<String> exact = EXACT.get(proto);
        if (exact != null) out.addAll(exact);
        for (PrefixRule rule : PREFIX_RULES) {
            if (proto.startsWith(rule.prefix)) out.addAll(rule.taskIds);
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    /**
     * 节流判定（纯函数）：上次写键后未满 {@code throttleMillis} 则不允许再次写。
     *
     * <p>时钟回拨（{@code now < last}）按「未节流」处理——否则回拨一次会误伤一个回拨窗口。
     *
     * @param lastWakeAtMillis 上次写键时刻（0/负数 = 从未写过）
     * @param nowMillis        当前时刻
     * @param throttleMillis   节流窗口（毫秒）
     */
    public static boolean isThrottled(long lastWakeAtMillis, long nowMillis, long throttleMillis) {
        if (lastWakeAtMillis <= 0L) return false;
        long delta = nowMillis - lastWakeAtMillis;
        return delta >= 0L && delta < throttleMillis;
    }

    /** 全部被映射的任务 id（自检/单测：每个 id 必须存在于 {@link TaskCadence} 节奏表）。 */
    public static Set<String> mappedTaskIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> ids : EXACT.values()) out.addAll(ids);
        for (PrefixRule rule : PREFIX_RULES) out.addAll(rule.taskIds);
        return Collections.unmodifiableSet(out);
    }

    /** 映射规则条数（精确 + 前缀；启动日志/自检用）。 */
    public static int ruleCount() {
        return EXACT.size() + PREFIX_RULES.size();
    }

    // ---------------- 内部：节流记账 ----------------

    /** 尝试占用 {@code (fpid, fn)} 的唤醒预算；成功占用返回 true（并把上次时刻推进到 now）。 */
    private boolean claimWakeSlot(String fpid, String fn, long now) {
        String key = fpid + "|" + fn;
        Long prev = lastWakeAt.get(key);
        if (isThrottled(prev == null ? 0L : prev.longValue(), now, THROTTLE_MILLIS)) return false;
        lastWakeAt.put(key, now);
        return true;
    }

    // ---------------- 内部：映射表 ----------------

    /** 精确协议名 → 任务集合（无序多任务用不可变 Set）。 */
    private static final Map<String, Set<String>> EXACT;

    /** 前缀规则（"notify_xxx_*" 通配；按声明顺序匹配，命中多条时并集）。 */
    private static final List<PrefixRule> PREFIX_RULES;

    static {
        Map<String, Set<String>> exact = new LinkedHashMap<>();
        List<PrefixRule> prefixes = new java.util.ArrayList<>();

        // ===== 联盟宝箱（网页版 task/alliancechests/AllianceChestsTaskModule.java:21：
        //       init/add/update/delete；capture 实证还有 brilliant_update）=====
        prefixes.add(new PrefixRule("notify_alliance_chest_", "alliance_chest"));

        // ===== 情报（网页版 task/intelligence/IntelligenceTaskModule.java:19：
        //       notify_intelligence_info / events / events_del / battle_result / power_info）=====
        prefixes.add(new PrefixRule("notify_intelligence_", "intel"));

        // ===== 野外采集（网页版 task/gather/GatherTaskModule.java:23：
        //       notify_player_march_add/update/del、notify_world_resource_info 等）=====
        prefixes.add(new PrefixRule("notify_player_march_", "gather"));
        prefixes.add(new PrefixRule("notify_march", "gather"));
        exact.put("notify_world_resource_info", set("gather"));

        // ===== 竞技场（网页版 task/arena/ArenaTaskModule.java:33：notify_arena_init 等；
        //       审计 §6.3 补 notify_arena_report 结算推送）=====
        prefixes.add(new PrefixRule("notify_arena_", "arena"));

        // ===== 联盟总动员（网页版 task/alliancemobilization/AllianceMobilizationTaskModule.java:21：
        //       notify_mobilization_init/activity_update/position_update/score_update/quest_update/…）=====
        prefixes.add(new PrefixRule("notify_mobilization_", "alliance_mobilization"));

        // ===== 联盟争霸赛（网页版 task/allianceshowdown/AllianceShowdownTaskModule.java:21：
        //       notify_alliance_showdown_activity / _global_activity；capture 实证还有 _grade）=====
        prefixes.add(new PrefixRule("notify_alliance_showdown_", "alliance_showdown"));

        // ===== 联盟捐献（网页版 task/alliancedonation/AllianceDonationTaskModule.java:21：
        //       notify_alliance_science_init / _science_exp_update / _science_research_finish /
        //       _science_recommend_update、notify_alliance_dev_*、notify_recover_resource_init/update）=====
        prefixes.add(new PrefixRule("notify_alliance_science_", "alliance_donate"));
        prefixes.add(new PrefixRule("notify_alliance_dev_", "alliance_donate"));
        // 恢复资源 205 = 联盟捐献免费次数；审计要求同时唤醒 supply（物资/在线奖励恢复同源数据）
        prefixes.add(new PrefixRule("notify_recover_resource_", "alliance_donate", "supply"));

        // ===== 联盟互助（网页版 task/alliancedonation/… 的 help_init/update/update_all/auto_help）=====
        prefixes.add(new PrefixRule("notify_alliance_help_", "alliance_help"));

        // ===== 活动奖励合集（网页版 task/activityrewards/ActivityRewardsTaskModule.java:21 的 16 类 notify；
        //       每条都可能让某个活动奖励从「未领」变「可领」）=====
        prefixes.add(new PrefixRule("notify_card_bp", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_lost_treasure", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_value_exchange", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_battle_pass", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_daily_claim", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_optional_gift_pack", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_daily_bundle", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_infinite_box", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_hero_turntable", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_fortune_card", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_customize_item", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_multiple_choice_pack", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_fishing_activity", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_month_card", "activity_rewards"));
        prefixes.add(new PrefixRule("notify_vip", "activity_rewards"));   // vip_info/update 同时唤醒 commander_gift（见下）
        // 活动日历（网页版 DailyActivityCalendarState.NOTIFY_PROTOCOL；活动开窗/结束门控唤醒奖励重评）
        exact.put("notify_activity_calendar_data", set("activity_rewards"));

        // ===== 以下为审计 §6.3 / 网页版 TaskModule 明确列出、但原整轮只在一天一次而漏领的任务 =====
        // 每日任务（网页版 task/dailymission/DailyMissionTaskModule.java:22：notify_dailyquests；
        // 审计补充在线事件 notify_update_dailyquest / _integral）
        exact.put("notify_dailyquests", set("daily_mission"));
        prefixes.add(new PrefixRule("notify_update_dailyquest", "daily_mission"));

        // 邮件（网页版 task/mailboxrewards/MailboxRewardsTaskModule.java:21 四类）
        exact.put("notify_new_mail_syn", set("mail"));
        exact.put("notify_mail_type_info_syn", set("mail"));
        exact.put("notify_unlock_mail_attach", set("mail"));
        exact.put("notify_mail_important", set("mail"));

        // 挂机奖励（网页版 task/adventurerewards/AdventureRewardsTaskModule.java:21）
        exact.put("notify_adventure_pve_info", set("adventure"));

        // 生存物资/在线奖励（网页版 task/supply/SupplyTaskModule.java:21 三类）
        exact.put("notify_reward_info", set("supply"));
        exact.put("notify_start_online_reward", set("supply"));
        exact.put("notify_online_reward_timeup", set("supply"));

        // 统帅礼包（网页版 task/commandergift/CommanderGiftTaskModule.java:39：vip_info/update；
        // 同两条也命中上面的 notify_vip 前缀 → 活动奖励合集一并唤醒）
        exact.put("notify_vip_info", set("commander_gift"));
        exact.put("notify_vip_update", set("commander_gift"));

        // 无尽征程（网页版 task/endlessjourney/EndlessJourneyTaskModule.java:21：notify_general_bp_*）
        prefixes.add(new PrefixRule("notify_general_bp_", "act_endless"));

        // 登录好礼（网页版 task/loginrewards/LoginRewardsTaskModule.java:21）
        exact.put("notify_fourteen_sign_info", set("login_rewards"));
        exact.put("notify_fourteen_purchase", set("login_rewards"));

        // 游戏红包（网页版 service/DailyRedPacketService.java:99：notify_red_packet_user_info）
        exact.put("notify_red_packet_user_info", set("red_packet"));

        // 成就（网页版无此模块，我方 App 额外功能：ServerTasks.achievementClaim 依赖该推送取数）
        exact.put("notify_achievement_info", set("achievement_claim"));

        // 章节/成长任务（网页版 BootstrapRewardState.java:28,36；我方 ServerTasks.chapterQuest 依赖）
        exact.put("notify_chapterquests", set("chapter_quest"));
        exact.put("notify_growthquests", set("chapter_quest"));

        // 背包（我方额外功能：ServerShopLeagueTasks.backpack 依赖 init/update 取数；
        // 网页版多处协议状态也消费 notify_backpack_init/update）
        exact.put("notify_backpack_init", set("backpack"));
        exact.put("notify_backpack_update", set("backpack"));

        EXACT = Collections.unmodifiableMap(exact);
        PREFIX_RULES = Collections.unmodifiableList(prefixes);
    }

    /** 前缀规则：{@code proto.startsWith(prefix)} 命中即把 taskIds 并入本次唤醒集合。 */
    private static final class PrefixRule {
        final String prefix;
        final Set<String> taskIds;

        PrefixRule(String prefix, String... taskIds) {
            this.prefix = prefix;
            this.taskIds = set(taskIds);
        }
    }

    private static Set<String> set(String... ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(ids)));
    }
}
