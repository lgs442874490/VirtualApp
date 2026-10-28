package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.CaptureTasks;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.formation.FormationLibrary;
import com.carlos.vaultserver.formation.FormationResolver;
import com.carlos.vaultserver.formation.ResolvedFormation;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器侧任务执行器（P3A 首批：奖励/联盟领取类 12 项）。
 * 协议交互与判定逐行移植自 App 端 daily/DailyTasks，改动须两侧同步。
 * 未迁移任务在 RoundRunner 中以 SKIPPED（待迁移）呈现，不静默丢失。
 */
public final class ServerTasks {

    /** 会话中断关键字（与 App DailyTasks.SESSION_CLOSED_MARKS 一致；P1-A 二轮增补网络层专有词）。 */
    private static final String[] SESSION_CLOSED_MARKS = {
            "不在线", "会话已", "连接已关闭", "断开", "强制下线", "连接失败", "发送协议数据失败",
            "等待协议响应超时", "Socket", "socket", "EOF",
            // P1-A 二轮（ZCode 复验 §5.4）：connect() 路径抛 "连接游戏服务器失败"——
            // "连接失败"不是它的连续子串，round-1 漏判；其 cause 为 ConnectException
            // ("Connection refused")。增补词均为网络层专有文案，不误伤业务错误码文案。
            "连接游戏服务器失败", "Connection refused", "Connection reset", "Broken pipe"};

    /**
     * 网络层异常类型白名单（P1-A 二轮）：cause 链上任一出现即判会话中断。
     * 覆盖文案被本地化/裁剪、无关键字可匹配的静默断线形态。
     * 注意 SocketTimeoutException 是 SocketException 子类，一并显式列出只为口径自文档。
     */
    private static final Class<?>[] SESSION_CLOSED_TYPES = {
            java.net.ConnectException.class,
            java.net.SocketException.class,
            java.net.SocketTimeoutException.class,
            java.io.EOFException.class,
            java.nio.channels.ClosedChannelException.class};

    /** 与 App DailyTasks.DAILY_QUEST_IDS 一致（缺失段由服务端忽略）。 */
    static final int[] DAILY_QUEST_IDS = {
            1320000,
            1320010, 1320011, 1320012,
            1320020, 1320021, 1320022,
            1320030, 1320031,
            1320040, 1320041,
            1320050, 1320051,
            1320060, 1320061,
            1320070, 1320071, 1320072,
            1320081, 1320082, 1320083};

    public static final class TaskDef {
        public final String id;
        public final String name;
        public final boolean enabledByDefault;
        public final Executor executor;

        public interface Executor {
            TaskOutcome run(TaskContext tc) throws Exception;
        }

        TaskDef(String id, String name, boolean enabledByDefault, Executor executor) {
            this.id = id;
            this.name = name;
            this.enabledByDefault = enabledByDefault;
            this.executor = executor;
        }
    }

    /** 已迁移任务表（P3B 起为全量 45 项；顺序与 App FnRegistry/DailyTasks 目录一致）。 */
    public static List<TaskDef> all() {
        List<TaskDef> l = new ArrayList<>();
        // ===== 奖励（仅领取类） =====
        l.add(new TaskDef("login_rewards", "登录好礼", true, ServerTasks::loginRewards));
        l.add(new TaskDef("mail", "邮件奖励", true, ServerTasks::mailRewards));
        l.add(new TaskDef("supply", "仓库补给", true, ServerTasks::supplyRewards));
        l.add(new TaskDef("daily_mission", "每日任务", true, ServerTasks::dailyMission));
        l.add(new TaskDef("chapter_quest", "章节任务", true, ServerTasks::chapterQuest));
        l.add(new TaskDef("adventure", "挂机奖励", true, ServerTasks::adventureReward));
        l.add(new TaskDef("activity_rewards", "活动奖励", true, ServerTasks::activityRewards));
        l.add(new TaskDef("commander_gift", "统帅礼包", true, ServerTasks::commanderGift));
        l.add(new TaskDef("achievement_claim", "成就奖励", true, ServerTasks::achievementClaim));
        // ===== 城镇 =====
        l.add(new TaskDef("research", "科技研究", true, ServerFieldTasks::research));
        l.add(new TaskDef("building", "建筑升级", false, ServerFieldTasks::building));
        l.add(new TaskDef("town_affairs", "城镇管理", true, ServerTownTasks::townAffairs));
        l.add(new TaskDef("medic", "军医所", true, ServerTownTasks::medic));
        l.add(new TaskDef("barracks", "兵营训练", false, ServerTownTasks::barracks));
        l.add(new TaskDef("pet", "宠物", true, ServerTownTasks::pet));
        l.add(new TaskDef("expert", "专家游历", true, ServerTownTasks::expert));
        l.add(new TaskDef("homeland", "晨曦岛", true, ServerTownTasks::homeland));
        l.add(new TaskDef("fire_crystal", "火晶提炼", true, ServerTownTasks::fireCrystal));
        l.add(new TaskDef("climbtower", "地心探索", true, ServerTownTasks::climbtower));
        l.add(new TaskDef("arena", "竞技场", false, ServerTownTasks::arena));
        // ===== 活动 =====
        l.add(new TaskDef("act_balloon", "正中靶心", true, ServerActivityTasks::balloon));
        l.add(new TaskDef("act_pitchpot", "壶纳千祥", true, ServerActivityTasks::pitchpot));
        l.add(new TaskDef("act_snowclean", "除雪", true, ServerActivityTasks::snowclean));
        l.add(new TaskDef("act_icebound", "冰封宝藏", true, ServerActivityTasks::icebound));
        l.add(new TaskDef("act_polar", "逐光之旅", true, ServerActivityTasks::polar));
        l.add(new TaskDef("act_flamefangs", "烈焰獠牙", false, ServerShopLeagueTasks::flamefangs));
        l.add(new TaskDef("act_snowtrade", "雪原贸易站", false, ServerShopLeagueTasks::snowTrade));
        l.add(new TaskDef("act_brawl", "欢乐乱斗", true, ServerActivityTasks::brawl));
        l.add(new TaskDef("act_frostfire", "燃霜矿区", false, ServerShopLeagueTasks::frostfire));
        l.add(new TaskDef("act_fishing", "钓鱼", true, ServerActivityTasks::fishing));
        l.add(new TaskDef("act_endless", "无尽征程", true, ServerActivityTasks::endlessJourney));
        l.add(new TaskDef("act_joy", "怪攻守城", false, ServerActivityTasks::joyAction));
        l.add(new TaskDef("act_feast", "至尊盛宴", false, ServerActivityTasks::supremacyFeast));
        l.add(new TaskDef("act_mercenary", "佣兵荣耀", false, ServerActivityTasks::mercenary));
        l.add(new TaskDef("act_truck", "运宝车队", false, ServerActivityTasks::truckEscort));
        l.add(new TaskDef("act_icehunter", "冰原猎人", false, ServerActivityTasks::icefieldHunter));
        // ===== 野外 =====
        l.add(new TaskDef("gather", "野外采集", false, ServerFieldTasks::gather));
        l.add(new TaskDef("intel", "情报任务", false, ServerFieldTasks::intel));
        l.add(new TaskDef("behemoth", "巨兽狩猎", true, ServerTasks::behemoth));
        l.add(new TaskDef("bear", "巨熊行动", true, ServerTasks::bear));
        l.add(new TaskDef("hunt", "自动狩猎", false, ServerFieldTasks::hunt));
        l.add(new TaskDef("adventure_sweep", "探险速刷", false, ServerFieldTasks::adventureSweep));
        l.add(new TaskDef("battle_shield", "战斗护盾", true, ServerTasks::freeShield));
        // ===== 联盟 =====
        l.add(new TaskDef("alliance_chest", "联盟宝箱", true, ServerTasks::allianceChest));
        l.add(new TaskDef("alliance_donate", "联盟捐献", true, ServerTasks::allianceDonate));
        l.add(new TaskDef("alliance_mobilization", "联盟动员", true, ServerShopLeagueTasks::mobilization));
        l.add(new TaskDef("alliance_showdown", "争霸赛", false, ServerShopLeagueTasks::showdown));
        l.add(new TaskDef("alliance_help", "联盟互助", true, ServerShopLeagueTasks::allianceHelp));
        l.add(new TaskDef("homeland_visit", "晨曦岛拜访", true, ServerShopLeagueTasks::homelandVisit));
        // ===== 商店/其他 =====
        l.add(new TaskDef("shop", "商店购买", false, ServerShopLeagueTasks::shopBuy));
        l.add(new TaskDef("alliance_shop", "联盟商店", false, ServerShopLeagueTasks::allianceShop));
        l.add(new TaskDef("red_packet", "游戏红包", true, ServerActivityTasks::redPacket));
        l.add(new TaskDef("bank", "钻石银行", false, ServerActivityTasks::bank));
        l.add(new TaskDef("backpack", "背包道具", true, ServerShopLeagueTasks::backpack));
        l.add(new TaskDef("social_gift", "社交礼物", false, ServerShopLeagueTasks::socialGift));
        return l;
    }

    public static TaskDef byId(String id) {
        for (TaskDef d : all()) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    // ---------------- 执行器 ----------------

    // ================= 登录好礼状态化（R7，批次4 2026-10-23；1:1 网页版） =================
    //
    // 网页版权威：
    // - service/DailyLoginRewardService.java:96-102,140-162（有可领档才发 req_fourteen_sign_get_all_reward，
    //   `requestOn` 成功即 markClaimed；:118-135 单日兜底 req_fourteen_sign_get_reward{day}）；
    // - service/DailyLoginRewardState.java:96-136（notify_fourteen_sign_info{server_index,sign_days,
    //   free_award_record[],buy_award_record[],has_pay:"false"/bool,last_sign_ts} + notify_fourteen_purchase）；
    // - model/DailyLoginRewardStateSnapshot.java:20-42（claimableDay = day<=signDays 且未领；completed）；
    // - task/loginrewards/LoginRewardsTaskModule.java:22（TRIGGER=notify_fourteen_sign_info/purchase）。
    // 七天 / 21 天（网页版 bootstrap 页；抓包实证）：
    // - tools/bundle_re/proto_log.txt:146 notify_sevensign_info{sign_days,get_award_info[],lastsign_time}；
    // - tools/bundle_re/audit_1014/proto_log_dev_old.txt notify_sign_21day_info{sign_days,
    //   free_award_record[],buy_award_record[],has_pay,start_ts}。
    // 旧实现盲发三协议、code=0/空奖励统一落“无可领”，无法区分「今日已领 / 无可领 / 周期剩余」，
    // 且 TaskCadence.login_rewards=300s 每轮重复请求。本实现：先解析状态 → 可领才发请求 → ClaimLedger
    // 按天去重；状态缺失（notify 未到）时保留旧盲发兜底与旧文案，不猜测新协议。

    /** 签到轨状态（十四天双轨 / 七天 / 21 天共用；字段语义见各自 notify_*_sign_info）。 */
    static final class SignTrackState {
        final String track;
        final boolean ready;
        final int signDays;
        final int totalDays;
        final java.util.Set<Integer> freeReceived;
        final java.util.Set<Integer> paidReceived;
        final boolean paidActive;
        final long lastSignTs;

        SignTrackState(String track, boolean ready, int signDays, int totalDays,
                       java.util.Set<Integer> freeReceived, java.util.Set<Integer> paidReceived,
                       boolean paidActive, long lastSignTs) {
            this.track = track;
            this.ready = ready;
            this.signDays = Math.max(0, Math.min(signDays, totalDays));
            this.totalDays = totalDays;
            this.freeReceived = freeReceived == null ? java.util.Collections.emptySet() : freeReceived;
            this.paidReceived = paidReceived == null ? java.util.Collections.emptySet() : paidReceived;
            this.paidActive = paidActive;
            this.lastSignTs = lastSignTs;
        }

        /** 已签到的天数里尚未领取的免费档数量（网页版 claimableDays.day<=signDays 且 !freeReceived）。 */
        int pendingFree() {
            int n = 0;
            for (int day = 1; day <= signDays; day++) {
                if (!freeReceived.contains(day)) n++;
            }
            return n;
        }

        /** 已签到的天数里尚未领取的付费档数量（史诗通行证激活才有）。 */
        int pendingPaid() {
            if (!paidActive) return 0;
            int n = 0;
            for (int day = 1; day <= signDays; day++) {
                if (!paidReceived.contains(day)) n++;
            }
            return n;
        }

        boolean claimable() {
            return ready && (pendingFree() > 0 || pendingPaid() > 0);
        }

        /** 网页版 DailyLoginRewardStateSnapshot.completed()：免费 7 天全领且（未激活付费或付费也全领）。 */
        boolean completed() {
            return ready && freeReceived.size() >= totalDays
                    && (!paidActive || paidReceived.size() >= totalDays);
        }

        /** 周期剩余天数（7/21 天签到用；14 天双轨按网页版 7 天周期）。 */
        int remainingDays() {
            return Math.max(0, totalDays - signDays);
        }

        /**
         * mod 子状态文案（网页版 status().currentDay/freeClaimableCount/nextUnlockAt 的服务器侧等价）：
         * “已连签 N/总天 · 免费档待领 X 天 · 周期剩 Y 天 · 今日已领/无可领”。
         */
        String modText(boolean claimedToday) {
            if (!ready) return track + "状态未同步";
            StringBuilder sb = new StringBuilder();
            sb.append(track).append("已连签 ").append(Math.min(signDays, totalDays))
                    .append('/').append(totalDays).append(" 天");
            if (pendingFree() > 0) sb.append(" · 免费档待领 ").append(pendingFree()).append(" 天");
            if (paidActive && pendingPaid() > 0) sb.append(" · 付费档待领 ").append(pendingPaid()).append(" 天");
            if (claimedToday) {
                sb.append(" · 今日已领");
            } else if (!claimable()) {
                sb.append(" · 无可领");
            }
            sb.append(" · 周期剩 ").append(remainingDays()).append(" 天");
            return sb.toString();
        }
    }

    /** 已领天数集合：兼容数组（[1,2,3]）、数组内对象（[{id:1}]）、Map（{"1.0":true}/{"1.0":{id:1}}）。 */
    static java.util.Set<Integer> receivedSignDays(Object rawDays) {
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        if (rawDays == null) return out;
        if (rawDays instanceof List) {
            for (Object o : (List<?>) rawDays) {
                long day = signDayOf(o);
                if (day > 0 && day <= 100) out.add((int) day);
            }
        } else if (rawDays instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawDays).entrySet()) {
                long day = numericKey(e.getKey());
                if (day <= 0) day = signDayOf(e.getValue());
                if (day > 0 && day <= 100) out.add((int) day);
            }
        }
        return out;
    }

    private static long signDayOf(Object raw) {
        if (raw instanceof Map) {
            Map<String, Object> m = Exec.mapOf(raw);
            long day = Exec.longOf(m.get("id"));
            if (day <= 0) day = Exec.longOf(m.get("day"));
            if (day <= 0) day = Exec.longOf(m.get("value"));
            return day;
        }
        return numericKey(raw);
    }

    /** 十四天双轨：网页版 TOTAL_DAYS=7（7 天免费 + 7 天史诗双轨），sign_days 钳 0..7（State:120-136）。 */
    static SignTrackState parseFourteenSign(Map<String, Object> content) {
        if (content == null) return new SignTrackState("十四天双轨", false, 0, 7, null, null, false, 0L);
        int signDays = (int) Math.max(0L, Math.min(7L, Exec.longOf(content.get("sign_days"))));
        boolean paidActive = truthy(content.get("has_pay"));
        return new SignTrackState("十四天双轨", true, signDays, 7,
                receivedSignDays(content.get("free_award_record")),
                receivedSignDays(content.get("buy_award_record")),
                paidActive, Exec.longOf(content.get("last_sign_ts")));
    }

    /** 七天签到：notify_sevensign_info{sign_days,get_award_info[],lastsign_time}（proto_log.txt:146）。 */
    static SignTrackState parseSevenSign(Map<String, Object> content) {
        if (content == null) return new SignTrackState("七天签到", false, 0, 7, null, null, false, 0L);
        int signDays = (int) Math.max(0L, Math.min(7L, Exec.longOf(content.get("sign_days"))));
        return new SignTrackState("七天签到", true, signDays, 7,
                receivedSignDays(content.get("get_award_info")),
                java.util.Collections.emptySet(), false,
                Exec.longOf(content.get("lastsign_time")));
    }

    /** 21 天签到：notify_sign_21day_info{sign_days,free_award_record[],buy_award_record[],has_pay,start_ts}。 */
    static SignTrackState parse21DaySign(Map<String, Object> content) {
        if (content == null) return new SignTrackState("21天签到", false, 0, 21, null, null, false, 0L);
        int signDays = (int) Math.max(0L, Math.min(21L, Exec.longOf(content.get("sign_days"))));
        boolean paidActive = truthy(content.get("has_pay"));
        return new SignTrackState("21天签到", true, signDays, 21,
                receivedSignDays(content.get("free_award_record")),
                receivedSignDays(content.get("buy_award_record")),
                paidActive, Exec.longOf(content.get("last_sign_ts")));
    }

    /**
     * 单轨领取（三轨共用）：台账按天去重 → 状态可领才发请求 → 错误码分类（已领/未开放/不可用 → SKIP）。
     * 状态缺失（notify 未到，state.ready=false）时保留旧盲发兜底：仍以响应奖励字段为到账证据。
     *
     * @param stateCountsAsEvidence 十四天双轨按网页版语义「请求成功即已领」，七天/21 天要求响应带奖励明细
     * @return {claimed, skipped, degraded, failed}
     */
    static int[] claimSignTrack(TaskContext tc, String ledgerKey, String name, String proto,
                                SignTrackState state, String evidenceKind,
                                boolean stateCountsAsEvidence,
                                StringBuilder msg, StringBuilder rewards) {
        if (ClaimLedger.isClaimed(tc, "login_rewards", ledgerKey)) {
            msg.append("; ").append(name).append("今日已领（台账）");
            return new int[]{0, 1, 0, 0};
        }
        if (state != null && state.ready && !state.claimable()) {
            msg.append("; ").append(name)
                    .append(state.completed() ? "已全领" : "暂无可领")
                    .append("（").append(state.modText(true)).append("）");
            return new int[]{0, 1, 0, 0};
        }
        try {
            GameMessage rsp = tc.engine.request(proto, null);
            if (isDegraded(rsp)) {
                msg.append("; ").append(name).append("响应异常");
                return new int[]{0, 0, 1, 0};
            }
            int code = Exec.codeOf(rsp);
            if (code != 0) {
                if (isActivityUnavailableCode(code)) {
                    if (isActivityAlreadyClaimedCode(code)) {
                        ClaimLedger.mark(tc, "login_rewards", ledgerKey);
                    }
                    msg.append("; ").append(name).append(activityUnavailableText(code));
                    return new int[]{0, 1, 0, 0};
                }
                TaskOutcome fail = classifyServerCode(code, name);
                if (fail.category == TaskOutcome.Category.SESSION_CLOSED) {
                    throw new RuntimeException(fail.message);
                }
                msg.append("; ").append(name).append("错误码").append(code);
                return new int[]{0, 0, 0, 1};
            }
            // 到账证据口径：十四天轨沿用旧 classifySimple「响应含奖励字段即 CLAIMED」（兼容
            // CaptureTasksTest.FakeEngine 的字符串形态 rewards:"diamond:10"）；七天/21 天沿用
            // 旧实现的 award_success_list/rewards 非空口径。detail 仅用于展示，可与证据口径解耦。
            boolean evidence = hasSignRewardEvidence(rsp, evidenceKind);
            String detail = signRewardDetail(rsp, evidenceKind);
            boolean stateClaimed = stateCountsAsEvidence && state != null && state.ready;
            if (!evidence && !stateClaimed) {
                // 旧兜底语义：响应无到账证据 → 已全领或不可用（SKIP，不伪造 CLAIMED）
                msg.append("; ").append(name).append("已全领或不可用");
                return new int[]{0, 1, 0, 0};
            }
            ClaimLedger.mark(tc, "login_rewards", ledgerKey);
            msg.append("; ").append(name).append("已领");
            if (!detail.isEmpty()) {
                msg.append("（").append(detail).append("）");
                if (rewards != null && rewards.length() < 600) {
                    rewards.append(name).append("：").append(detail).append("；");
                }
            } else {
                msg.append("（到账明细未回执）");
            }
            return new int[]{1, 0, 0, 0};
        } catch (Exception e) {
            TaskOutcome r = classifyException(e, name);
            if (r.category == TaskOutcome.Category.SESSION_CLOSED) throw new RuntimeException(e);
            msg.append("; ").append(name).append(r.message);
            return new int[]{0, 0, 0, 1};
        }
    }

    /**
     * 到账证据判定（1:1 旧实现口径，防回归）：
     * 十四天轨沿用旧 classifySimple —— 响应含 awards/rewards/items/award_success_list/gain 任一 key 即算
     * （抓包/FakeEngine 都可能给字符串形态 {@code rewards:"diamond:10"}，不能用 RewardText.describe 判空）；
     * 七天=award_success_list 非空；21 天=rewards 非空（列表）或非列表形态。
     */
    static boolean hasSignRewardEvidence(GameMessage rsp, String evidenceKind) {
        if (rsp == null || rsp.getContent() == null) return false;
        if ("seven".equals(evidenceKind)) {
            List<Object> okList = listOfRaw(rsp.getContent().get("award_success_list"));
            return okList != null && !okList.isEmpty();
        }
        if ("day21".equals(evidenceKind)) {
            Object raw = rsp.getContent().get("rewards");
            if (raw instanceof List) return !((List<?>) raw).isEmpty();
            return raw != null;
        }
        for (String k : new String[]{"awards", "rewards", "items", "award_success_list", "gain"}) {
            if (rsp.getContent().containsKey(k)) return true;
        }
        return false;
    }

    /** 响应到账明细（14 天多候选字段；七天看 award_success_list 条数；21 天看 rewards）。 */
    static String signRewardDetail(GameMessage rsp, String evidenceKind) {
        if (rsp == null || rsp.getContent() == null) return "";
        if ("seven".equals(evidenceKind)) {
            List<Object> okList = listOfRaw(rsp.getContent().get("award_success_list"));
            return okList == null || okList.isEmpty() ? "" : "×" + okList.size();
        }
        if ("day21".equals(evidenceKind)) {
            return descRewards(rsp.getContent().get("rewards"));
        }
        for (String k : new String[]{"awards", "rewards", "items", "award_success_list", "gain"}) {
            Object raw = rsp.getContent().get(k);
            if (raw == null) continue;
            String d = descRewards(raw);
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    /** 今日是否已领（台账优先；状态说“已签名天数全部领完”也视为今日已领，用于 mod 文案）。 */
    static boolean signClaimedToday(TaskContext tc, String ledgerKey, SignTrackState state) {
        if (ClaimLedger.isClaimed(tc, "login_rewards", ledgerKey)) return true;
        return state != null && state.ready && state.signDays > 0 && !state.claimable();
    }

    static TaskOutcome loginRewards(TaskContext tc) throws Exception {
        try {
            // 三轨（十四天双轨 / 七天 / 21 天）：各子开关独立门控；
            // 聚合口径 claimed>0→CLAIMED，否则 degraded/failed>0→DEGRADED，否则 SKIPPED。
            if (!tc.subOn("login_rewards", "sign", true)) {
                return new TaskOutcome("login_rewards", "登录好礼", TaskOutcome.Category.SKIPPED,
                        "子开关「签到」已关闭");
            }
            StringBuilder msg = new StringBuilder();
            StringBuilder rewards = new StringBuilder();
            int claimed = 0, skipped = 0, degraded = 0, failed = 0;

            // 状态解析（引擎按 proto 保留最后一帧；缺失 → state.ready=false，走旧盲发兜底）
            SignTrackState s14 = parseFourteenSign(firstNotify(tc.engine, "notify_fourteen_sign_info"));
            SignTrackState s7 = parseSevenSign(firstNotify(tc.engine, "notify_sevensign_info"));
            SignTrackState s21 = parse21DaySign(firstNotify(tc.engine, "notify_sign_21day_info"));

            // ① 十四天双轨：notify_fourteen_sign_info 判可领，有可领才发一键全领
            int[] c14 = claimSignTrack(tc, "14", "登录好礼(14天双轨)",
                    "req_fourteen_sign_get_all_reward", s14, "fourteen", true, msg, rewards);
            claimed += c14[0];
            skipped += c14[1];
            degraded += c14[2];
            failed += c14[3];

            // ② 七天签到：award_success_list 非空才算真领到
            if (tc.subOn("login_rewards", "seven", true)) {
                int[] c7 = claimSignTrack(tc, "7", "七天签到",
                        "req_seven_sign_one_click_award", s7, "seven", false, msg, rewards);
                claimed += c7[0];
                skipped += c7[1];
                degraded += c7[2];
                failed += c7[3];
            } else {
                msg.append("; 七天签到已关");
            }

            // ③ 21 天签到免费档（付费档 has_pay 才有，本任务只领免费全领协议）
            if (tc.subOn("login_rewards", "day21", true)) {
                int[] c21 = claimSignTrack(tc, "21", "21天签到",
                        "req_sign_21day_get_all_reward", s21, "day21", false, msg, rewards);
                claimed += c21[0];
                skipped += c21[1];
                degraded += c21[2];
                failed += c21[3];
            } else {
                msg.append("; 21天签到已关");
            }

            // mod 子状态（已连签 N 天 / 今日已领 / 无可领 / 周期剩余）——三轨分别落盘，
            // App 功能页直接展示（旧实现不写任何登录好礼子状态）。
            tc.saveModText("login_rewards", "sign14",
                    s14.modText(signClaimedToday(tc, "14", s14)));
            tc.saveModText("login_rewards", "seven",
                    s7.modText(signClaimedToday(tc, "7", s7)));
            tc.saveModText("login_rewards", "day21",
                    s21.modText(signClaimedToday(tc, "21", s21)));

            TaskOutcome.Category cat = claimed > 0 ? TaskOutcome.Category.CLAIMED
                    : (degraded > 0 || failed > 0) ? TaskOutcome.Category.DEGRADED
                    : TaskOutcome.Category.SKIPPED;
            String text = msg.length() > 0 ? msg.substring(2) : "无结果";
            TaskOutcome out = new TaskOutcome("login_rewards", "登录好礼", cat, text);
            out.rewards = rewards.toString();
            return out;
        } catch (Exception e) {
            return classifyException(e, "登录好礼");
        }
    }

    // ---------- 1b. 成就奖励（与 App DailyTasks.achievementClaim 2026-10-12 版一致） ----------

    /**
     * notify_achievement_info 字段实证：infos[]{id, count, complete:"true"/"false", received:...}。
     * 领取条件 = complete && !received；req_achievement_get_reward.request{list_data}（批量 id）。
     */
    static TaskOutcome achievementClaim(TaskContext tc) {
        try {
            if (!tc.subOn("achievement_claim", "claim", true)) {
                return new TaskOutcome("achievement_claim", "成就奖励", TaskOutcome.Category.SKIPPED,
                        "子开关「批量领取」已关闭");
            }
            tc.engine.collectNotifies(1200);
            Map<String, Object> ai = tc.engine.notifyData("notify_achievement_info");
            List<Integer> ids = new ArrayList<>();
            for (Map<String, Object> a : listOfMaps(ai == null ? null : ai.get("infos"))) {
                boolean complete = "true".equals(String.valueOf(a.get("complete")));
                boolean received = "true".equals(String.valueOf(a.get("received")));
                int id = toInt(a.get("id"));
                if (complete && !received && id > 0) ids.add(id);
            }
            if (ids.isEmpty()) {
                return new TaskOutcome("achievement_claim", "成就奖励", TaskOutcome.Category.SKIPPED,
                        ai == null ? "成就数据未同步" : "没有已完成未领取的成就");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("list_data", ids);
            GameMessage rsp = tc.engine.request("req_achievement_get_reward", args);
            if (isDegraded(rsp)) {
                return new TaskOutcome("achievement_claim", "成就奖励", TaskOutcome.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                return classifyServerCode(toInt(code), "成就奖励");
            }
            String d = com.carlos.vault.daily.RewardText.describe(rsp.getContent().get("rewards") != null
                    ? rsp.getContent().get("rewards") : rsp.getContent().get("awards"));
            TaskOutcome r = new TaskOutcome("achievement_claim", "成就奖励", TaskOutcome.Category.CLAIMED,
                    "成就奖励×" + ids.size() + (d.isEmpty() ? "" : "：" + d));
            r.rewards = d;
            return r;
        } catch (Exception e) {
            return classifyException(e, "成就奖励");
        }
    }

    // ================= 邮件奖励（R5 P0 修复 2026-10-23；1:1 网页版） =================
    //
    // 网页版 DailyMailboxRewardService.java：
    // - :323-329 MailSection 5 板块（system 10 / alliance 20,100,120,170 /
    //   battle 30,110,130,140,160,210 / message 40,70,80,90,150,180,190,200,220 / collection 50）；
    // - :145-154 候选 = unread || hasAttachment，且排除 important（notify_mail_important）；
    // - :187-215 分页 req_mail_cover_list{first_type,beginidx,endidx}（50/页，1201=无邮件）；
    // - :240-300 批量 req_batch_mail_read{first_type,mid_list_new,mid_list（legacy 数字 id）}，
    //   未确认/批量异常 → 逐封 req_mail_read + req_mail_receive 兜底；1201/1202/1204/1215 归 SKIP。
    // 旧实现只扫 5 个类型、只用 mailid_new、无逐封兜底 → battle/message 板块附件永远不领，
    // legacy mid_list 邮件也永远不领（生产「候选 8」即此口径）。
    //
    // 节奏：TaskCadence.mail=180s + notify_new_mail_syn 等推送唤醒；候选状态来自服务端 coverlist，
    // 已领邮件自然消失，不做按天台账（日内新邮件必须能继续领）。

    static final int[] MAIL_TYPES_SYSTEM = {10};
    static final int[] MAIL_TYPES_ALLIANCE = {20, 100, 120, 170};
    static final int[] MAIL_TYPES_BATTLE = {30, 110, 130, 140, 160, 210};
    static final int[] MAIL_TYPES_MESSAGE = {40, 70, 80, 90, 150, 180, 190, 200, 220};
    static final int[] MAIL_TYPES_COLLECTION = {50};

    /** 板块策略 → 邮件类型表（"all" 默认 5 板块全量；"sys"/"sys_ally"/"no_battle"/逗号列表保持兼容）。 */
    static int[] mailTypesFor(String board) {
        String b = board == null ? "" : board.trim();
        if (b.contains(",")) {
            List<Integer> tl = new ArrayList<>();
            for (String part : b.split(",")) {
                int v = (int) numericKey(part);
                if (v > 0) tl.add(v);
            }
            if (tl.isEmpty()) tl.add(10);
            int[] out = new int[tl.size()];
            for (int i = 0; i < tl.size(); i++) out[i] = tl.get(i);
            return out;
        }
        switch (b) {
            case "sys":
                return MAIL_TYPES_SYSTEM.clone();
            case "sys_ally":
                return concatInts(MAIL_TYPES_SYSTEM, MAIL_TYPES_ALLIANCE);
            case "no_battle":
                return concatInts(MAIL_TYPES_SYSTEM, MAIL_TYPES_ALLIANCE,
                        MAIL_TYPES_MESSAGE, MAIL_TYPES_COLLECTION);
            default:
                return concatInts(MAIL_TYPES_SYSTEM, MAIL_TYPES_ALLIANCE, MAIL_TYPES_BATTLE,
                        MAIL_TYPES_MESSAGE, MAIL_TYPES_COLLECTION);
        }
    }

    static int[] concatInts(int[]... groups) {
        int n = 0;
        for (int[] g : groups) n += g.length;
        int[] out = new int[n];
        int i = 0;
        for (int[] g : groups) {
            for (int v : g) out[i++] = v;
        }
        return out;
    }

    /** 邮件候选（网页版 DailyMailboxRewardServiceMailCandidate：mailid_new / legacy mailid）。 */
    static final class MailCandidate {
        final String newId;
        final long legacyId;
        final boolean unread;
        final boolean attach;

        MailCandidate(String newId, long legacyId, boolean unread, boolean attach) {
            this.newId = newId == null ? "" : newId;
            this.legacyId = legacyId;
            this.unread = unread;
            this.attach = attach;
        }

        String key() {
            return !newId.isEmpty() ? newId : (legacyId > 0L ? "legacy:" + legacyId : "");
        }

        boolean valid() {
            return !key().isEmpty();
        }
    }

    /** 封面 → 候选（网页版 candidateFrom:145-154：unread || attach；unread 且无附件由调用方按开关过滤）。 */
    static MailCandidate mailCandidateOf(Map<String, Object> cover) {
        if (cover == null) return null;
        String newId = firstStringOf(cover, "mailid_new");
        long legacyId = Math.max(0L, Exec.longOf(cover.get("mailid")));
        boolean unread = Exec.longOf(cover.get("isread")) == 0L;
        boolean attach = Exec.longOf(cover.get("attach_statue")) == 1L;
        if (!unread && !attach) return null;
        MailCandidate c = new MailCandidate(newId, legacyId, unread, attach);
        return c.valid() ? c : null;
    }

    /** notify_mail_important{covers[]} → 排除 key 集合（网页版 :145-154 排除 important 邮件）。 */
    static java.util.Set<String> importantMailKeys(Map<String, Object> notify) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (notify == null) return out;
        Object covers = notify.containsKey("covers") ? notify.get("covers") : notify.get("coverlist");
        for (Map<String, Object> m : Exec.mapsOf(covers)) {
            String newId = firstStringOf(m, "mailid_new");
            if (!newId.isEmpty()) out.add(newId);
            long legacy = Exec.longOf(m.get("mailid"));
            if (legacy > 0L) out.add("legacy:" + legacy);
        }
        return out;
    }

    // ---- 批次5：邮件通知历史（notify_new_mail_syn / notify_mail_type_info_syn 多帧合并） ----

    /**
     * 从通知历史提取“有可操作邮件”的邮件类型/板块（批次5）：
     * - notify_new_mail_syn{mail_cover_info{first_type,...}}：新到邮件 → 其 first_type；
     * - notify_mail_type_info_syn{infolist[]{type,num,un_readnum,can_get_attach}}：仅取“有未读/可领附件”
     *   的类型（un_readnum>0 或 can_get_attach==1），避免把 200 封已读历史板块全部并入扫描。
     * 无历史返回空集合（调用方回退配置类型，行为等价批次4）。
     */
    static java.util.Set<Integer> mailHistoryTypes(String fpid) {
        java.util.Set<Integer> out = new java.util.TreeSet<>();
        if (fpid == null || fpid.trim().isEmpty()) return out;
        for (ServerNotifyHistory.Entry e : ServerNotifyHistory.history(fpid,
                "notify_new_mail_syn", "notify_mail_type_info_syn")) {
            if (e == null || e.content == null) continue;
            if ("notify_new_mail_syn".equals(e.proto)) {
                Map<String, Object> cover = Exec.mapOf(e.content.get("mail_cover_info"));
                if (cover == null) cover = e.content;
                addMailHistoryType(out, Exec.longOf(cover.get("first_type")));
            } else {
                Object list = e.content.containsKey("infolist")
                        ? e.content.get("infolist") : e.content.get("infos");
                for (Map<String, Object> info : Exec.mapsOf(list)) {
                    boolean actionable = Exec.longOf(info.get("un_readnum")) > 0L
                            || Exec.longOf(info.get("can_get_attach")) > 0L;
                    if (actionable) addMailHistoryType(out, Exec.longOf(info.get("type")));
                }
            }
        }
        return out;
    }

    private static void addMailHistoryType(java.util.Set<Integer> out, long type) {
        // 只接受合法邮件类型（抓包实证 ≤220；上限 999 防脏数据把扫描类型打爆）
        if (type > 0L && type <= 999L) out.add((int) type);
    }

    /** 邮件历史帧数（观测用：>1 才说明“历史补全”真正生效）。 */
    static int mailHistoryFrameCount(String fpid) {
        return ServerNotifyHistory.history(fpid,
                "notify_new_mail_syn", "notify_mail_type_info_syn").size();
    }

    /**
     * 配置类型 ∪ 历史类型（批次5，纯函数）：配置顺序优先、历史类型升序追加、去重；
     * 上限 40 类防异常历史把每轮 cover_list 请求打爆。默认 all 配置下通常无新增（服务器类型是 21 类子集）。
     */
    static int[] mergeMailTypes(int[] configured, java.util.Set<Integer> historyTypes) {
        java.util.List<Integer> merged = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        if (configured != null) {
            for (int t : configured) {
                if (t > 0 && seen.add(t)) merged.add(t);
            }
        }
        if (historyTypes != null) {
            for (int t : historyTypes) {
                if (seen.add(t)) merged.add(t);
            }
        }
        final int max = 40;
        int n = Math.min(merged.size(), max);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = merged.get(i);
        return out;
    }

    static TaskOutcome mailRewards(TaskContext tc) throws Exception {
        try {
            int[] configured = mailTypesFor(tc.strategy("mail", "read", "all"));
            // 批次5：通知历史（每 proto ≤8 帧）合并“服务器实际有可领邮件”的类型/板块；
            // 不新增任何协议请求，下方 cover_list 请求逻辑与批次4 完全一致。
            java.util.Set<Integer> historyTypes = mailHistoryTypes(tc.fpid);
            int[] types = mergeMailTypes(configured, historyTypes);
            logHistoryMerge(tc, "mail", mailHistoryFrameCount(tc.fpid));
            boolean keepUnread = tc.subOn("mail", "keep_unread", true);
            try {
                tc.engine.request("req_mail_type_info", null);
            } catch (Exception ignored) {
            }
            tc.engine.collectNotifies(400);
            java.util.Set<String> important = importantMailKeys(
                    firstNotify(tc.engine, "notify_mail_important"));
            Map<Integer, List<MailCandidate>> byType = new LinkedHashMap<>();
            StringBuilder degrade = new StringBuilder();
            int sections = 0;
            for (int type : types) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                int begin = 0;
                for (int page = 0; page < 20; page++) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("first_type", type);
                    args.put("beginidx", begin);
                    args.put("endidx", begin + 50);
                    GameMessage cover;
                    try {
                        cover = tc.engine.request("req_mail_cover_list", args);
                    } catch (Exception e) {
                        TaskOutcome r = classifyException(e, "邮件奖励");
                        if (r.category == TaskOutcome.Category.SESSION_CLOSED) throw e;
                        degrade.append("板块").append(type).append("请求异常; ");
                        break;
                    }
                    if (isDegraded(cover)) {
                        degrade.append("板块").append(type).append("响应异常; ");
                        break;
                    }
                    int code = Exec.codeOf(cover);
                    if (code == 1201) break;   // 该类型无邮件（网页版 :187-215）
                    if (code != 0) {
                        if (!isActivityUnavailableCode(code)) {
                            degrade.append("板块").append(type).append("码").append(code).append("; ");
                        }
                        break;
                    }
                    sections++;
                    List<Map<String, Object>> covers = Exec.mapsOf(cover.getContent().get("coverlist"));
                    int accepted = 0;
                    for (Map<String, Object> m : covers) {
                        MailCandidate c = mailCandidateOf(m);
                        if (c == null || important.contains(c.key()) || !seen.add(c.key())) continue;
                        if (keepUnread && !c.attach) continue;   // 默认仅领有附件邮件（保持旧开关语义）
                        byType.computeIfAbsent(type, k -> new ArrayList<>()).add(c);
                        accepted++;
                    }
                    if (covers.size() < 50 || accepted == 0) break;
                    begin += 50;
                }
            }
            int candidates = 0;
            for (List<MailCandidate> l : byType.values()) candidates += l.size();
            if (candidates == 0) {
                if (degrade.length() > 0) {
                    return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.DEGRADED,
                            "部分板块协议异常：" + degrade);
                }
                return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.SKIPPED,
                        sections == 0 ? "邮箱板块未开放或无数据" : "没有可领取的邮件附件");
            }
            int claimed = 0, skipped = 0, failed = 0;
            StringBuilder rewardParts = new StringBuilder();
            for (Map.Entry<Integer, List<MailCandidate>> e : byType.entrySet()) {
                int[] c = claimMailType(tc, e.getKey(), e.getValue(), rewardParts);
                claimed += c[0];
                skipped += c[1];
                failed += c[2];
            }
            tc.saveModText("mail", "read", "上轮领取 " + claimed + " 封 · 候选 " + candidates);
            TaskOutcome.Category cat = claimed > 0 ? TaskOutcome.Category.CLAIMED
                    : (failed > 0 || degrade.length() > 0 ? TaskOutcome.Category.DEGRADED
                    : TaskOutcome.Category.SKIPPED);
            String msg = (claimed > 0 ? "领取邮件 " + claimed + " 封" : "本轮未领到附件")
                    + "（候选 " + candidates + "，跳过 " + skipped + "）"
                    + (degrade.length() > 0 ? "；" + degrade : "");
            TaskOutcome r = new TaskOutcome("mail", "邮件奖励", cat, msg);
            String rw = rewardParts.toString();
            if (rw.endsWith("；")) rw = rw.substring(0, rw.length() - 1);
            r.rewards = rw;
            return r;
        } catch (Exception e) {
            return classifyException(e, "邮件奖励");
        }
    }

    /** 单板块领取：批量（≤100/批，含 legacy mid_list）失败或未确认 → 逐封兜底（Service:240-300）。 */
    static int[] claimMailType(TaskContext tc, int type, List<MailCandidate> candidates,
                               StringBuilder rewardsOut) throws Exception {
        int claimed = 0, skipped = 0, failed = 0;
        for (int off = 0; off < candidates.size(); off += 100) {
            List<MailCandidate> batch = candidates.subList(off, Math.min(off + 100, candidates.size()));
            List<String> newIds = new ArrayList<>();
            List<Long> legacyIds = new ArrayList<>();
            for (MailCandidate c : batch) {
                if (!c.newId.isEmpty()) newIds.add(c.newId);
                else if (c.legacyId > 0L) legacyIds.add(c.legacyId);
            }
            boolean batchOk = false;
            if (!newIds.isEmpty() || !legacyIds.isEmpty()) {
                GameMessage rsp = tc.engine.request("req_batch_mail_read",
                        mailBatchArgs(type, newIds, legacyIds));
                if (!isDegraded(rsp) && Exec.codeOf(rsp) == 0) {
                    batchOk = true;
                    String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                    if (!d.isEmpty() && rewardsOut.length() < 700) {
                        rewardsOut.append("板块").append(type).append("：").append(d).append("；");
                    }
                    if (!rsp.getContent().containsKey("readlist_new")
                            && !rsp.getContent().containsKey("readlist")) {
                        // 服务端未回执 id 列表时以 code=0 视为整批受理（旧实现同口径）
                        claimed += batch.size();
                    } else {
                        claimed += confirmMailBatch(batch, rsp);
                        List<MailCandidate> remaining = unconfirmedMail(batch, rsp);
                        if (!remaining.isEmpty()) {
                            int[] rc = claimMailIndividually(tc, remaining, rewardsOut);
                            claimed += rc[0];
                            skipped += rc[1];
                            failed += rc[2];
                        }
                    }
                }
            }
            if (!batchOk) {
                int[] rc = claimMailIndividually(tc, batch, rewardsOut);
                claimed += rc[0];
                skipped += rc[1];
                failed += rc[2];
            }
        }
        return new int[]{claimed, skipped, failed};
    }

    /** 批量已确认 candidate 数（readlist_new=新 id；readlist=legacy 数字 id）。 */
    static int confirmMailBatch(List<MailCandidate> batch, GameMessage rsp) {
        java.util.Set<String> confirmed = mailConfirmedKeys(rsp);
        int n = 0;
        for (MailCandidate c : batch) {
            if (confirmed.contains(c.key())) n++;
        }
        return n;
    }

    /** 批量响应里未确认的候选（网页版 :240-300 remaining → 逐封兜底）。 */
    static List<MailCandidate> unconfirmedMail(List<MailCandidate> batch, GameMessage rsp) {
        java.util.Set<String> confirmed = mailConfirmedKeys(rsp);
        List<MailCandidate> out = new ArrayList<>();
        for (MailCandidate c : batch) {
            if (!confirmed.contains(c.key())) out.add(c);
        }
        return out;
    }

    static java.util.Set<String> mailConfirmedKeys(GameMessage rsp) {
        java.util.Set<String> confirmed = new java.util.HashSet<>();
        if (rsp == null) return confirmed;
        List<Object> readNew = listOfRaw(rsp.getContent().get("readlist_new"));
        if (readNew != null) {
            for (Object o : readNew) confirmed.add(String.valueOf(o));
        }
        List<Object> readLegacy = listOfRaw(rsp.getContent().get("readlist"));
        if (readLegacy != null) {
            for (Object o : readLegacy) confirmed.add("legacy:" + Exec.longOf(o));
        }
        return confirmed;
    }

    /** 逐封兜底：unread → req_mail_read；有附件 → req_mail_receive（Service:283-329）。 */
    static int[] claimMailIndividually(TaskContext tc, List<MailCandidate> batch,
                                       StringBuilder rewardsOut) throws Exception {
        int processed = 0, skipped = 0, failed = 0;
        for (MailCandidate c : batch) {
            Map<String, Object> payload = mailSingleArgs(c);
            boolean anyOk = c.attach || c.unread;
            if (c.unread) {
                GameMessage read = tc.engine.request("req_mail_read", payload);
                int rc = isDegraded(read) ? -1 : Exec.codeOf(read);
                if (rc != 0 && rc != 1201 && rc != 1202 && rc != 1204 && rc != 1215) anyOk = false;
            }
            if (c.attach) {
                GameMessage rsp = tc.engine.request("req_mail_receive", payload);
                int code = isDegraded(rsp) ? -1 : Exec.codeOf(rsp);
                if (code == 0) {
                    String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                    if (!d.isEmpty() && rewardsOut.length() < 800) {
                        rewardsOut.append("邮件：").append(d).append("；");
                    }
                } else if (code == 1201 || code == 1202 || code == 1204 || code == 1215) {
                    // 邮件不存在/附件已领/状态不允许 → 合理跳过（网页版 :240-300）
                } else {
                    failed++;
                    anyOk = false;
                }
            }
            if (anyOk) processed++;
            else skipped++;
        }
        return new int[]{processed, skipped, failed};
    }

    static TaskOutcome supplyRewards(TaskContext tc) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean anyDegraded = false;
        // 2026-10-25 P1 修复：是否“真领到”必须由各子项成功分支显式置位，不能用文案里是否出现
        // “已领取”做判定——6158 的跳过文案本身就是“本窗口已领取”，会把“没领到”误判成 CLAIMED
        // （生产实证：12/12 次 CLAIMED 中 10 次两子项全是 6158、rewards 为空）。
        boolean anyClaimed = false;
        boolean onOnline = tc.subOn("supply", "online", true);
        boolean onSpec = tc.subOn("supply", "spec", true);
        String strat = tc.strategy("supply", "spec", "now");
        Map<String, String> subs = new LinkedHashMap<>();
        StringBuilder rewardParts = new StringBuilder();   // 领取明细（与 App supplyRewards 一致）
        try {
            if (onOnline) {
                try {
                    GameMessage online = tc.engine.request("req_receive_online_reward", null);
                    if (isDegraded(online)) {
                        anyDegraded = true;
                        sb.append("普通补给响应异常; ");
                    } else {
                        Object code = online.getContent().get("code");
                        if (code != null && toInt(code) != 0) {
                            sb.append("普通补给").append(describeSupplyCode(toInt(code), ":")).append("; ");
                            subs.put("online", "skip:码" + toInt(code));
                        } else if (online.getContent().containsKey("online_reward")) {
                            String d = descRewards(online.getContent().get("online_reward"));
                            sb.append("普通补给已领取").append(d).append("; ");
                            subs.put("online", "ok:已领" + d);
                            anyClaimed = true;
                            if (!d.isEmpty()) rewardParts.append("普通补给：").append(d).append("；");
                        } else {
                            sb.append("普通补给暂不可领; ");
                            subs.put("online", "skip:暂不可领");
                        }
                    }
                } catch (Exception e) {
                    TaskOutcome r = classifyException(e, "普通补给");
                    if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                    sb.append(r.message).append("; ");
                }
            } else {
                sb.append("普通补给已关; ");
            }
            if (onSpec) {
                boolean allowed = true;
                long specEnd = tc.modState("supply").optLong("spec_end", 0);
                if (!"now".equals(strat) && specEnd > 0) {
                    long leadMin = "end30".equals(strat) ? 30
                            : "end60".equals(strat) ? 60
                            : tc.strategyMinutes("supply", "spec", 30);
                    allowed = System.currentTimeMillis() >= (specEnd - leadMin * 60_000L);
                }
                if (allowed) {
                    try {
                        GameMessage spec = tc.engine.request("req_receive_spec_reward", null);
                        if (isDegraded(spec)) {
                            anyDegraded = true;
                            sb.append("体力补给响应异常; ");
                        } else {
                            Object code = spec.getContent().get("code");
                            if (code != null && toInt(code) != 0) {
                                sb.append("体力补给").append(describeSupplyCode(toInt(code), ":")).append("; ");
                                subs.put("spec", "skip:码" + toInt(code));
                            } else if (spec.getContent().containsKey("spec_reward")) {
                                String d = descRewards(spec.getContent().get("spec_reward"));
                                sb.append("体力补给已领取").append(d).append("; ");
                                subs.put("spec", "ok:已领" + d);
                                anyClaimed = true;
                                if (!d.isEmpty()) rewardParts.append("体力补给：").append(d).append("；");
                            } else {
                                sb.append("体力补给暂不可领; ");
                                subs.put("spec", "skip:暂不可领");
                            }
                        }
                    } catch (Exception e) {
                        TaskOutcome r = classifyException(e, "体力补给");
                        if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                        sb.append(r.message).append("; ");
                    }
                } else {
                    subs.put("spec", "skip:按策略等待窗口");
                    sb.append("体力补给按策略等待截止前").append("now".equals(strat) ? 0
                            : "end30".equals(strat) ? 30 : "end60".equals(strat) ? 60
                            : tc.strategyMinutes("supply", "spec", 30)).append("分钟; ");
                }
            } else {
                sb.append("体力补给已关; ");
            }
            // 补领后立即刷新补给状态（顺延下一次唤醒的依据）
            CaptureTasks.captureSupplyState(tc.engine, tc.store, tc.fpid, tc.sid);
            // C3（批次4；TaskOutcome.nextWakeAtMillis 契约见 TaskOutcome.java:26-35，
            // 单任务模式由 RoundRunner.applyNextWake:190-208 落盘 srv:cad:{fpid}:{sid}:{fn}）：
            // 补给按 recovery next_calc_ts 精确唤醒，避免 60s 白跑；返回 0 时 Runner 不写、沿用节奏间隔。
            // 批次5 修复：online_ts 可能远于 24h（如跨活动/脏字段），直接交给 Runner 会被
            // RoundRunner.applyNextWake 越界丢弃并每次 WARN（生产 6s/条噪声）→ 先过 24h 边窗，
            // 越界/过去一律不设（回落 TaskCadence 固定间隔）。
            long supplyWakeAt = safeNextWakeAtMillis(System.currentTimeMillis(),
                    supplyNextWakeAtMillis(tc.modState("supply")));
            String msg = sb.length() == 0 ? "无结果" : sb.substring(0, sb.length() - 2);
            boolean claimed = anyClaimed;
            TaskOutcome sr = new TaskOutcome("supply", "仓库补给",
                    anyDegraded ? TaskOutcome.Category.DEGRADED
                            : (claimed ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED),
                    msg);
            sr.subResults.putAll(subs);
            if (rewardParts.length() > 0) {
                sr.rewards = rewardParts.substring(0, rewardParts.length() - 1);
            }
            sr.wakeAt(supplyWakeAt);
            return sr;
        } catch (Exception e) {
            return classifyException(e, "仓库补给");
        }
    }

    static TaskOutcome dailyMission(TaskContext tc) throws Exception {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            List<Integer> ids = new ArrayList<>();
            java.util.TreeSet<Long> merged = new java.util.TreeSet<>();
            List<Long> unclaimedIds = new ArrayList<>();
            int unclaimedBefore = 0;
            try {
                tc.engine.collectNotifies(800);
                Map<String, Object> q = tc.engine.notifyData("notify_dailyquests");
                String qs = String.valueOf(q);
                if (qs.length() > 2000) qs = qs.substring(0, 2000) + "…";
                FileLog.info("dailyquest notify fpid={} {}", tc.fpid, qs);
                unclaimedIds = unclaimedQuestIds(q);
                unclaimedBefore = unclaimedIds.size();
                Object quests = q == null ? null : q.get("quests");
                if (quests instanceof Map) {
                    for (Object k : ((Map<?, ?>) quests).keySet()) {
                        try {
                            // 游戏 notify 的 key 形如 "1320040.0"：必须按 double 解析，
                            // 旧代码 Long.parseLong 全部失败，只发送了静态 21 个旧 id，
                            // 新增任务段（1320090+）因此漏领。
                            long id = (long) Double.parseDouble(String.valueOf(k).trim());
                            if (id > 0) merged.add(id);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            for (int id : DAILY_QUEST_IDS) merged.add((long) id);
            for (long id : merged) ids.add((int) id);
            args.put("quest_ids", ids);
            FileLog.info("dailyquest request fpid={} ids={} unclaimed={}", tc.fpid, ids, unclaimedBefore);
            GameMessage rsp = tc.engine.request("req_get_all_dailyquest_award", args);
            Map<String, Object> content = rsp == null || rsp.getContent() == null
                    ? java.util.Collections.emptyMap() : rsp.getContent();
            FileLog.info("dailyquest response fpid={} content={}", tc.fpid, String.valueOf(content));
            if (isDegraded(rsp)) {
                return new TaskOutcome("daily_mission", "每日任务奖励", TaskOutcome.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            int code = toInt(content.get("code"));
            if (code != 0) {
                if (code == 4302 || code == 4303) {
                    if (unclaimedBefore == 0) {
                        return new TaskOutcome("daily_mission", "每日任务奖励",
                                TaskOutcome.Category.SKIPPED,
                                "今日任务奖励已全部领取（码" + code + "）");
                    }
                    // 批量接口拒绝时，对“已完成未领”的任务逐个走单任务领取协议兜底。
                    int oneByOne = claimDailyQuestIndividually(tc, unclaimedIds);
                    if (oneByOne > 0) {
                        return new TaskOutcome("daily_mission", "每日任务奖励",
                                TaskOutcome.Category.CLAIMED,
                                "任务奖励已单领×" + oneByOne);
                    }
                    return new TaskOutcome("daily_mission", "每日任务奖励",
                            TaskOutcome.Category.DEGRADED,
                            "仍有 " + unclaimedBefore + " 个已完成未领任务，服务端返回码" + code);
                }
                return classifyServerCode(code, "每日任务奖励");
            }
            // code=0：本协议响应只有 integral/geted_boxid，没有 awards/rewards；
            // 用“领取前后未领任务数 + 活跃度宝箱”作为到账证据。
            int unclaimedAfter = unclaimedBefore;
            try {
                tc.engine.collectNotifies(1000);
                Map<String, Object> q2 = tc.engine.notifyData("notify_dailyquests");
                unclaimedAfter = countUnclaimedQuestAwards(q2);
            } catch (Throwable ignored) {
            }
            List<Object> boxes = listOfRaw(content.get("geted_boxid"));
            int integralAfter = toInt(content.get("integral"));
            int newlyClaimed = Math.max(0, unclaimedBefore - unclaimedAfter);
            if (newlyClaimed > 0) {
                TaskOutcome r = new TaskOutcome("daily_mission", "每日任务奖励",
                        TaskOutcome.Category.CLAIMED,
                        "任务奖励已领取×" + newlyClaimed);
                return r;
            }
            if (boxes != null && !boxes.isEmpty()) {
                TaskOutcome r = new TaskOutcome("daily_mission", "每日任务奖励",
                        TaskOutcome.Category.CLAIMED,
                        "活跃度宝箱已领取×" + boxes.size());
                r.rewards = "宝箱" + boxes;
                return r;
            }
            if (unclaimedBefore > 0) {
                int oneByOne = claimDailyQuestIndividually(tc, unclaimedIds);
                if (oneByOne > 0) {
                    return new TaskOutcome("daily_mission", "每日任务奖励",
                            TaskOutcome.Category.CLAIMED,
                            "任务奖励已单领×" + oneByOne);
                }
            }
            if (unclaimedBefore == 0) {
                // 积分变化可能只是任务结算/服务端刷新，不能单独当作“领取到账”证据。
                return new TaskOutcome("daily_mission", "每日任务奖励",
                        TaskOutcome.Category.SKIPPED,
                        "今日任务奖励已全部领取（活跃度 " + integralAfter + "）");
            }
            return new TaskOutcome("daily_mission", "每日任务奖励", TaskOutcome.Category.DEGRADED,
                    "仍有 " + unclaimedBefore + " 个已完成未领任务，响应无到账证据");
        } catch (Exception e) {
            return classifyException(e, "每日任务奖励");
        }
    }

    /**
     * finishs 的“已完成未领” id：协议里既可能是数组（新版活动/专家情报），
     * 也可能是 "1320040.0" → {isgetaward} 的 map（每日任务）。
     * 两种形状统一在这里解析，避免各模块各写一套后出现“只认数组/只认 map”的漏领。
     */
    @SuppressWarnings("unchecked")
    static List<Long> unclaimedFinishIds(Object finishs) {
        List<Long> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        if (finishs instanceof List) {
            for (Map<String, Object> f : Exec.mapsOf(finishs)) {
                long id = Exec.longOf(f.get("id"));
                if (id <= 0 || truthy(f.get("isgetaward"))) continue;
                if (seen.add(id)) out.add(id);
            }
        } else if (finishs instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) finishs).entrySet()) {
                Object v = e.getValue();
                boolean claimed = false;
                long id = 0;
                if (v instanceof Map) {
                    claimed = truthy(((Map<?, ?>) v).get("isgetaward"));
                    Object vid = ((Map<?, ?>) v).get("id");
                    if (vid != null) {
                        try {
                            id = (long) Double.parseDouble(String.valueOf(vid).trim());
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (id <= 0) {
                    try {
                        id = (long) Double.parseDouble(String.valueOf(e.getKey()).trim());
                    } catch (Throwable ignored) {
                    }
                }
                if (!claimed && id > 0 && seen.add(id)) out.add(id);
            }
        }
        return out;
    }

    static boolean truthy(Object v) {
        return v instanceof Boolean ? (Boolean) v : "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** finishs 条目总数（数组或 "id.0"→item 的 map）。 */
    static int finishCount(Object finishs) {
        if (finishs instanceof List) return ((List<?>) finishs).size();
        if (finishs instanceof Map) return ((Map<?, ?>) finishs).size();
        return 0;
    }

    /** notify_dailyquests.finishs 中 isgetaward=false/缺失的 id（已完成但未领）。 */
    static List<Long> unclaimedQuestIds(Map<String, Object> dailyQuestNotify) {
        return dailyQuestNotify == null ? new ArrayList<>()
                : unclaimedFinishIds(dailyQuestNotify.get("finishs"));
    }

    static int countUnclaimedQuestAwards(Map<String, Object> dailyQuestNotify) {
        return unclaimedQuestIds(dailyQuestNotify).size();
    }

    /**
     * 单任务领取兜底：批量接口返回 4302/4303 或无到账证据、但 finishs 仍有未领任务时，
     * 按 req_get_dailyquest_award(questid) 逐个领取；以 finishs 未领数下降作为到账证据。
     */
    static int claimDailyQuestIndividually(TaskContext tc, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int before = countUnclaimedQuestAwards(tc.engine.notifyData("notify_dailyquests"));
        for (long id : ids) {
            try {
                GameMessage one = tc.engine.request("req_get_dailyquest_award",
                        Exec.args("questid", id));
                int c = Exec.codeOf(one);
                FileLog.info("dailyquest single fpid={} id={} code={}", tc.fpid, id, c);
                if (isDegraded(one)) break;
                if (c != 0 && c != 4302 && c != 4303) {
                    // 其它错误码继续尝试下一个，避免一个任务阻塞全部
                    FileLog.warn("dailyquest single fail fpid={} id={} code={}", tc.fpid, id, c);
                }
            } catch (Throwable t) {
                FileLog.warn("dailyquest single exception fpid={} id={} {}", tc.fpid, id,
                        String.valueOf(t));
                break;
            }
        }
        try {
            tc.engine.collectNotifies(1200);
        } catch (Throwable ignored) {
        }
        int after = countUnclaimedQuestAwards(tc.engine.notifyData("notify_dailyquests"));
        return Math.max(0, before - after);
    }

    // ---------- 4b. 章节奖励 + 章节任务 + 成长任务（App DailyTasks.chapterQuest 逐行移植） ----------

    static TaskOutcome chapterQuest(TaskContext tc) {
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> cq = tc.engine.notifyContent("notify_chapterquests");
            Map<String, Object> gq = tc.engine.notifyContent("notify_growthquests");
            int claimed = 0, skipped = 0;
            StringBuilder hits = new StringBuilder();

            // 成长任务：notify_growthquests.quests[].id 逐个尝试（未达标由服务端错误码跳过，上限 60 条防刷屏）
            if (tc.subOn("chapter_quest", "growth_quest", true)) {
                int sent = 0;
                for (Map<String, Object> q : Exec.mapsOf(gq == null ? null : gq.get("quests"))) {
                    long id = Exec.longOf(q.get("id"));
                    if (id <= 0 || sent >= 60) continue;
                    sent++;
                    GameMessage r = tc.engine.request("req_get_growthquest_award",
                            Exec.args("questid", id));
                    if (Exec.ok(r)) {
                        claimed++;
                        if (hits.length() > 0) hits.append("、");
                        hits.append("成长#").append(id);
                    } else skipped++;
                }
                if (gq == null) skipped++;
            }

            // 章节任务：notify_chapterquests.quests[].id
            if (tc.subOn("chapter_quest", "chapter_quest", true)) {
                int sent = 0;
                for (Map<String, Object> q : Exec.mapsOf(cq == null ? null : cq.get("quests"))) {
                    long id = Exec.longOf(q.get("id"));
                    if (id <= 0 || sent >= 60) continue;
                    sent++;
                    GameMessage r = tc.engine.request("req_get_chapterquest_award",
                            Exec.args("questid", id));
                    if (Exec.ok(r)) {
                        claimed++;
                        if (hits.length() > 0) hits.append("、");
                        hits.append("章任#").append(id);
                    } else skipped++;
                }
                if (cq == null) skipped++;
            }

            // 章节奖励：cur_chapter（含上一章兜底，跨章领漏）
            if (tc.subOn("chapter_quest", "chapter", true)) {
                long cur = cq == null ? 0 : Exec.longOf(cq.get("cur_chapter"));
                if (cur > 0) {
                    long from = Math.max(1, cur - 1);
                    for (long ch = from; ch <= cur; ch++) {
                        GameMessage r = tc.engine.request("req_get_chapter_award",
                                Exec.args("chapterid", ch));
                        if (Exec.ok(r)) {
                            claimed++;
                            if (hits.length() > 0) hits.append("、");
                            hits.append("章奖#").append(ch);
                        } else skipped++;
                    }
                } else {
                    skipped++;
                }
            }

            if (claimed > 0) {
                return new TaskOutcome("chapter_quest", "章节任务", TaskOutcome.Category.CLAIMED,
                        "领取×" + claimed + "：" + hits + "（服务端跳过×" + skipped + "）");
            }
            if (cq == null && gq == null) {
                return new TaskOutcome("chapter_quest", "章节任务", TaskOutcome.Category.SKIPPED,
                        "章节/成长任务通知未同步");
            }
            return new TaskOutcome("chapter_quest", "章节任务", TaskOutcome.Category.SKIPPED,
                    "无可领（服务端跳过×" + skipped + "）");
        } catch (Exception e) {
            return classifyException(e, "章节任务");
        }
    }

    /**
     * 挂机奖励（R3 P0 修复 2026-10-23）——1:1 网页版：
     * DailyAdventureRewardState.java:18-19（MINIMUM_CLAIM_SECONDS=600 / MAXIMUM_ACCUMULATION_SECONDS=32400）、
     * :89-103（notify_adventure_pve_info{adventure_pve_info{pass_checkpoint,hang_start_time}}；
     * 预览响应字段实为 open_hang_up_reward_view_time）、:105-116（领取响应字段 hang_start_time/current_reward）、
     * DailyAdventureRewardService.java:76-88,154-195（4004 → 15s 后重试、到点精确唤醒）。
     *
     * 旧实现三处错误：①预览/可领判定取 endtime/end_time（实际 open_hang_up_reward_view_time）；
     * ②到账证据取 rewards/reward（实际 current_reward）→ 领到了也写「当前暂无可领取」；
     * ③从未消费 notify_adventure_pve_info.hang_start_time（发协议前无法判 10 分钟到量）。
     */
    static TaskOutcome adventureReward(TaskContext tc) throws Exception {
        try {
            long now = serverNowSec(tc);
            // C3 唤醒边窗（批次5）：所有业务态唤醒统一经 safeNextWakeAtMillis 过滤（>now 且 ≤24h）。
            long nowMillis = System.currentTimeMillis();
            Map<String, Object> pve = activityInfoOf(tc.engine,
                    "notify_adventure_pve_info", "adventure_pve_info");
            long passCheckpoint = pve == null ? -1L : Exec.longOf(pve.get("pass_checkpoint"));
            long hangStart = pve == null ? -1L : Exec.longOf(pve.get("hang_start_time"));
            if (passCheckpoint == 0L) {
                return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                        "探险挂机尚未解锁（需通关首个探险关卡，pass_checkpoint=0）");
            }
            // 用户阈值（分钟，默认 0=网页版最小 600s）
            long minSeconds = 600L;
            try {
                long minMin = Long.parseLong(tc.strategy("adventure", "hang_up", "0").trim());
                if (minMin > 0L) minSeconds = Math.max(600L, minMin * 60L);
            } catch (Throwable ignored) {
            }
            long accumulated = pve == null ? -1L
                    : adventureAccumulatedSeconds(passCheckpoint, hangStart, now);
            if (accumulated >= 0L && accumulated < minSeconds) {
                long needSec = minSeconds - accumulated;
                saveAdventureNext(tc, now + needSec);
                // C3：到量时刻精确唤醒（+2s 余量防边界 4004；TaskOutcome.nextWakeAtMillis）
                return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                        "挂机累积 " + accumulated + "s，还差 " + needSec + "s 到可领（10 分钟起领）")
                        .wakeAt(safeNextWakeAtMillis(nowMillis, (now + needSec + 2L) * 1000L));
            }
            GameMessage view = tc.engine.request("req_open_hang_up_reward_view", null);
            String previewText = "";
            if (!isDegraded(view)) {
                int vcode = Exec.codeOf(view);
                previewText = descRewards(view.getContent().get("current_reward"));
                long previewTs = firstLongOf(view.getContent(),
                        "open_hang_up_reward_view_time", "endtime", "end_time");
                if (vcode == 4004 || vcode == 4003) {
                    long nextAt = previewTs > 0L ? previewTs : now + 15L;
                    saveAdventureNext(tc, nextAt);
                    // C3：预览给出 open_hang_up_reward_view_time，按它精确唤醒（缺省 15s 重试，
                    // 对齐网页版 DailyAdventureRewardService.java:167-171 的 4004 处理）
                    return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                            "挂机奖励尚未解锁或间隔未到（码" + vcode + "）")
                            .wakeAt(safeNextWakeAtMillis(nowMillis, nextAt * 1000L));
                }
                if (vcode != 0 && !isActivityUnavailableCode(vcode)) {
                    return classifyServerCode(vcode, "探险挂机");
                }
            }
            GameMessage claim = tc.engine.request("req_get_hang_up_reward", null);
            if (isDegraded(claim)) {
                return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.DEGRADED,
                        "领取响应异常（协议可能变更）");
            }
            int code = Exec.codeOf(claim);
            if (code != 0) {
                if (code == 4004) {
                    saveAdventureNext(tc, now + 15L);
                    // C3：4004=领取间隔未到 → 15s 后重试（网页版 DailyAdventureRewardService.java:167-171）
                    return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                            "领取间隔未到（约10分钟一领）")
                            .wakeAt(safeNextWakeAtMillis(nowMillis, (now + 15L) * 1000L));
                }
                if (code == 4003) {
                    return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                            "探险挂机尚未解锁（需通关首个探险关卡）");
                }
                return classifyServerCode(code, "探险挂机");
            }
            // 到账证据：网页版 State.acceptClaim:105-116 只认 hang_start_time / current_reward
            boolean evidence = claim.getContent().containsKey("hang_start_time")
                    || claim.getContent().containsKey("current_reward");
            if (!evidence) {
                return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.DEGRADED,
                        "领取响应缺少 hang_start_time/current_reward，未确认到账");
            }
            long nextHangStart = Exec.longOf(claim.getContent().get("hang_start_time"));
            String d = descRewards(claim.getContent().get("current_reward"));
            if (d.isEmpty()) d = previewText;
            long nextAt = nextHangStart > 0L ? nextHangStart + 600L : now + 600L;
            saveAdventureNext(tc, nextAt);
            TaskOutcome r = new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.CLAIMED,
                    "挂机奖励已领取" + (d.isEmpty() ? "" : "：" + d)
                            + "（下次可领 " + Beijing.fmt("HH:mm")
                            .format(new java.util.Date(nextAt * 1000L)) + "）");
            r.rewards = d;
            // C3：按 hang_start_time+600s 精确唤醒（状态：State:105-116 acceptClaim；批次5 加 24h 边窗）
            r.wakeAt(safeNextWakeAtMillis(nowMillis, nextAt * 1000L));
            return r;
        } catch (Exception e) {
            return classifyException(e, "探险挂机");
        }
    }

    /**
     * 挂机累积秒数（网页版 DailyAdventureRewardStateSnapshot.java:39-57 accumulatedSeconds）：
     * 未解锁=0；hang_start_time<=0 视为已满 32400s；否则 min(max(now-hang_start,0),32400)。纯函数便于单测。
     */
    static long adventureAccumulatedSeconds(long passCheckpoint, long hangStartTime, long nowSec) {
        if (passCheckpoint <= 0L) return 0L;
        if (hangStartTime <= 0L) return 32400L;
        return Math.min(Math.max(0L, nowSec - hangStartTime), 32400L);
    }

    /**
     * C3 唤醒边窗（批次5，修复生产 WARN 噪声）：仅当 {@code wakeAt} 严格晚于 now 且不超过 24h 时
     * 才交给 Runner（{@code TaskOutcome.nextWakeAtMillis}），否则返回 0=不覆盖节奏间隔。
     * 与 RoundRunner.acceptNextWakeAt:183-190 的边窗一致，但把“越界”在任务侧就静默消化，
     * 避免每轮落一条 WARN（生产实测 supply.online_ts 可能 >24h）。纯函数便于单测。
     */
    static long safeNextWakeAtMillis(long nowMillis, long wakeAtMillis) {
        if (wakeAtMillis <= 0L) return 0L;
        long delta = wakeAtMillis - nowMillis;
        if (delta <= 0L) return 0L;
        if (delta > 24L * 60L * 60L * 1000L) return 0L;
        return wakeAtMillis;
    }

    /**
     * C3：挂机下次业务态唤醒（epoch millis；0=不覆盖节奏间隔）。网页版
     * DailyAdventureRewardStateSnapshot.java:39-57 nextClaimInSeconds：未解锁=0；
     * 已累积>=600s（现在可领）=0；否则 now+(600-累积)。纯函数便于单测。
     */
    static long adventureNextWakeAtMillis(long passCheckpoint, long hangStartTime, long nowSec) {
        if (passCheckpoint <= 0L) return 0L;
        long accum = adventureAccumulatedSeconds(passCheckpoint, hangStartTime, nowSec);
        if (accum >= 600L) return 0L;
        return (nowSec + (600L - accum)) * 1000L;
    }

    /**
     * C3：补给下次业务态唤醒（epoch millis；0=不覆盖节奏间隔）。
     * 来源 = {@code mod:…:supply.online_ts}（CaptureTasks.captureSupplyState:322-343 从
     * notify_recover_resource_init 的 next_calc_ts 族字段取最早到点；与 RoundRunner.supplyWakeAt:194-202
     * 同口径），秒→毫秒。纯函数便于单测。
     */
    static long supplyNextWakeAtMillis(org.json.JSONObject supplyMod) {
        if (supplyMod == null) return 0L;
        long ts = supplyMod.optLong("online_ts", 0L);
        return ts > 0L ? ts * 1000L : 0L;
    }

    /** 写挂机下次可领时间（沿用旧 mod 键 adventure.hang_up，供 App 页展示/唤醒调度读取）。 */
    static void saveAdventureNext(TaskContext tc, long epochSec) {
        tc.saveModText("adventure", "hang_up", "下次可领 "
                + Beijing.fmt("HH:mm:ss").format(new java.util.Date(epochSec * 1000L)));
    }

    // ================= 活动奖励合集（P0 重写 2026-10-23；1:1 网页版） =================
    //
    // 网页版权威（/Users/liangguansheng/ZCodeProject/wjdr-review/src-tool/com/wjdr/tool/modules/daily/）：
    // - service/DailyActivityRewardsService.java:161-206（claimKeys 只对 claimable 发请求）、
    //   :219-239（unavailable/alreadyClaimed 错误码）、:241-316（英雄集结批量 + 7307 逐档兜底）、
    //   :325-362（月卡按 cardList[].id）、:368-396（卡 BP 逐档 8201/8202 停、8203 记已领）、
    //   :398-411（claimOne：协议与参数权威表）；
    // - service/DailyActivityRewardsState.java:95-225（notify_*_init/_info 双变体 + bp_info/ve_info/
    //   ci_info/ogp_info/ht_info/fc_info/mcp_info 包装层）、:302-336（价值兑换版本→下标表）、
    //   :652-669,725-754（卡 BP）、:764-778（价值兑换）、:790-811（英雄集结）、
    //   :897-910（远征宝藏 notify_daily_claim_info）、:912-920（optional_gift_pack）、
    //   :932-1003（月卡 cardList）、:1032-1048（无限宝箱）、:1050-1058（自选箱）、
    //   :1060-1080（钓鱼 activity_info）、:1082-1137（时间戳判定/lastClaimAt）；
    // - service/DailyActivityRewardKey.java:72-79（LOST/HERO_RALLY/RARE/CRAFT/ARMAMENT/DAWN_MARKET 常量）。
    //
    // 旧实现三类根因（生产 activity_rewards = 领取2/跳过13/失败1）：
    //   ①notify 名字错：远征宝藏查 notify_daily_free_reward_info（不存在，实际 notify_daily_claim_info）、
    //     许愿屋/自选箱/匠人宝库/自选礼包只查 _info（实际推送 _init）；
    //   ②extractId 只查顶层：battle pass id 在 {"bp_info":{...}}、value_exchange 在 ve_info、
    //     customize 在 ci_info、optional gift 在 ogp_info、hero turntable 在 ht_info → 恒取不到 id；
    //   ③协议族系错：曙光市集用自造 notify_duck_market_act_info/req_duck_market_*（网页版权威源码 0 命中），
    //     实际是 req_optional_gift_pack_get_free_box{id:2508001..2508007}；英勇见证用不存在的
    //     req_hero_story_claim_reward，实际是 req_value_exchange_free_exchange{id:2702001,idx}。
    //
    // 节奏：本分支 TaskCadence.activity_rewards=180s（TaskCadence.java）→ 每个领取目标用
    // ClaimLedger 按天去重（跨轮次不重复请求），可领判定用推送状态；宁漏不花：全部为免费领取协议。
    //
    // R2（批次3 2026-10-23）多实例活动：引擎 per-proto 只留最后一条推送
    // （app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:686-688），而网页版按活动 id 逐个维护
    // （service/DailyActivityRewardsState.java:95-225）并逐个 claimOne（:398-411）——曙光市集 7 档、
    // 自选箱 15 id、卡 BP 6 卡、失落宝藏 2 个等每轮只能覆盖最后到达项。现从
    // {@link ServerNotifyHistory}（SessionKeeper inbound 钩子写入，每协议最多 8 条）取全部实例 id，
    // 按 id 去重（后者覆盖前者）后逐个领取；每轮请求总数硬上限
    // {@link #ACTIVITY_MAX_REQUESTS_PER_ROUND}=40，超限下轮继续；ClaimLedger 按天去重防重复请求。
    // 活动日历门控（C5）见 {@link #activityCalendarBlock}：只对“能确认未开放/已结束”的活动生效，
    // 日历未收到/过旧/未列出该活动（解析不出）一律不拦。

    /** 活动领取协议名（权威来源：网页版 DailyActivityRewardsService.java:398-411 claimOne 表）。 */
    static final String NOTIFY_DAILY_CLAIM_INFO = "notify_daily_claim_info";
    static final String PROTO_JOURNEY_TREASURE = "req_daily_claim_free_reward";
    static final String PROTO_MONTH_CARD_DAILY = "req_month_card_get_daily_reaward";
    static final String PROTO_VIP_DAILY_EXP = "req_vip_get_daily_exp";
    static final String PROTO_DAILY_SPECIAL = "req_daily_bundle_get_free_gift";
    static final String PROTO_VALUE_EXCHANGE_FREE = "req_value_exchange_free_exchange";
    static final String PROTO_VALUE_EXCHANGE = "req_value_exchange_exchange";
    static final String PROTO_OPTIONAL_GIFT_FREE_BOX = "req_optional_gift_pack_get_free_box";
    static final String PROTO_CUSTOMIZE_FREE_BOX = "req_customize_item_get_free_box";
    static final String PROTO_MULTIPLE_CHOICE_FREE_BOX = "req_multiple_choice_pack_get_free_box";
    static final String PROTO_CARD_BP_COLLECT = "req_card_bp_collect_reward";
    static final String PROTO_BATTLE_PASS_COLLECT_ALL = "req_battle_pass_collect_all_reward";
    static final String PROTO_BATTLE_PASS_COLLECT = "req_battle_pass_collect_reward";
    static final String PROTO_COMMANDER_GIFT = "req_vip_get_daily_free_gift";

    /** 网页版 DailyActivityRewardsState.java:22-33（活动 id 常量）。 */
    static final long ACT_JOURNEY_TREASURE_ID = 2435002L;
    static final long ACT_VALUE_EXCHANGE_ID = 2702001L;
    static final long ACT_WISH_HOUSE_ID = 2402001L;
    static final long ACT_MIA_WISH_HOUSE_ID = 2703001L;
    static final long ACT_CRAFTSMAN_TREASURY_ID = 2509001L;
    static final long ACT_INFINITE_GROCERY_ID = 2510001L;
    static final long ACT_DAWN_WISDOM_ID = 2508008L;

    /** 网页版 DailyActivityRewardKey.java:72-79 常量集合（id 顺序照抄，便于对照）。 */
    static final long[] ACT_LOST_TREASURE_IDS = {2706001L, 2706002L};
    static final long[] ACT_DAWN_MARKET_IDS = {2508001L, 2508002L, 2508003L, 2508004L,
            2508005L, 2508006L, 2508007L};
    static final long[] ACT_RARE_CUSTOM_IDS = {2507001L, 2507002L, 2507003L, 2507004L,
            2507005L, 2507006L};
    static final long[] ACT_CRAFT_CUSTOM_IDS = {2507007L, 2507008L, 2507015L, 2507016L};
    static final long[] ACT_ARMAMENT_CUSTOM_IDS = {2507009L, 2507010L, 2507012L, 2507013L,
            2507014L};
    static final long[] ACT_FISHING_IDS = {2418001L, 2418002L};
    static final long[] ACT_HERO_RALLY_IDS = {2401001L, 2401002L, 2401003L, 2401004L, 2401005L,
            2401006L, 2401007L, 2401008L, 2401009L, 2401010L};
    /** 卡 BP 6 项（网页版 DailyActivityRewardsState.java:725-745 cardKey 只认这 6 个 id）。 */
    static final long[] ACT_CARD_BP_IDS = {2505001L, 2505002L, 2505003L, 2506001L, 2506002L,
            2506003L};

    /** 网页版 DailyActivityRewardsState.java:27-28：价值兑换 version→档位下标。 */
    static final Map<Long, Long> ACT_VALUE_EXCHANGE_DAILY_INDEX = Map.ofEntries(
            Map.entry(1L, 66L), Map.entry(2L, 67L), Map.entry(3L, 68L), Map.entry(4L, 69L),
            Map.entry(5L, 70L), Map.entry(6L, 71L), Map.entry(7L, 72L), Map.entry(8L, 73L),
            Map.entry(9L, 74L), Map.entry(10L, 132L), Map.entry(11L, 156L),
            Map.entry(12L, 182L), Map.entry(13L, 211L), Map.entry(14L, 241L));
    static final Map<Long, Long> ACT_VALUE_EXCHANGE_WITNESS_INDEX = Map.ofEntries(
            Map.entry(1L, 5L), Map.entry(2L, 12L), Map.entry(3L, 23L), Map.entry(4L, 33L),
            Map.entry(5L, 45L), Map.entry(6L, 59L), Map.entry(7L, 91L), Map.entry(8L, 110L),
            Map.entry(9L, 131L), Map.entry(10L, 155L), Map.entry(11L, 181L),
            Map.entry(12L, 209L), Map.entry(13L, 210L), Map.entry(14L, 240L));

    /** 网页版 DailyActivityRewardsState.java:33 INFINITE_FREE_ORDERS（万象杂货铺免费档位）。 */
    static final java.util.Set<Long> ACT_INFINITE_FREE_ORDERS = new java.util.HashSet<>(
            java.util.Arrays.asList(1L, 3L, 4L, 6L, 7L, 9L, 10L, 12L, 13L, 15L, 16L, 18L, 19L,
                    21L, 22L, 24L, 25L, 27L, 28L));

    /** 活动合集领取统计：ok/skip/fail + 领取明细 + 子项状态（TaskOutcome.subResults）。 */
    static final class ClaimStat {
        int ok;
        int skip;
        int fail;
        /**
         * 本轮已发出的领取请求数（R2，批次3 2026-10-23）：历史里同一协议可能有多个活动实例
         * （曙光市集 7 档/自选箱多组/卡 BP 多卡/多封邮件），逐个 claimOne 时硬上限
         * {@link #ACTIVITY_MAX_REQUESTS_PER_ROUND}=40，超限本轮停手、下轮（180s）继续，
         * 防止推送风暴/历史放大成协议风暴或触发反挂。
         */
        int requests;
        final StringBuilder rewards = new StringBuilder();
        final Map<String, String> subs = new LinkedHashMap<>();

        private void note(String key, String value) {
            String prev = subs.get(key);
            subs.put(key, prev == null ? value : prev + "；" + value);
        }

        void claimed(String key, String name, GameMessage rsp) {
            ok++;
            String d = rsp == null || rsp.getContent() == null ? ""
                    : com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
            if (!d.isEmpty() && rewards.length() < 800) {
                rewards.append(name).append("：").append(d).append("；");
            }
            note(key, "ok:" + (d.isEmpty() ? name + "已领取" : name + "：" + d));
        }

        void okCount(String key, String name, int count) {
            if (count <= 0) return;
            ok += count;
            note(key, "ok:" + name + "×" + count);
        }

        void skipped(String key, String why) {
            skip++;
            note(key, "skip:" + why);
        }

        void failed(String key, String why) {
            fail++;
            note(key, "fail:" + why);
        }

        boolean has(String key) {
            return subs.containsKey(key);
        }
    }

    /** 取第一条存在的推送原始内容（引擎按 proto 保存最近一条，不保证顺序）。 */
    static Map<String, Object> firstNotify(WjdrEngine engine, String... protos) {
        if (engine == null) return null;
        for (String proto : protos) {
            Map<String, Object> c = engine.notifyContent(proto);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * 推送包装层下钻：返回 content 里第一个命中的 wrapper 子 map；都不命中返回 content 本身。
     * 网页版 DailyActivityRewardsState.java:95-225 按 {"bp_info":{...}}/{"ve_info":{...}} 取内层，
     * 旧实现只查顶层 id（WjdrEngine.extractId:715-736）→ 包装层协议全部取不到。
     */
    static Map<String, Object> notch(Map<String, Object> content, String... wrappers) {
        if (content == null) return null;
        for (String w : wrappers) {
            if (w == null) continue;
            Map<String, Object> inner = Exec.mapOf(content.get(w));
            if (inner != null) return inner;
        }
        return content;
    }

    /** 包装层 + 顶层 id（含 activities[]），等价“逐层下钻后的 extractId”。 */
    static Long extractIdDeep(Map<String, Object> content, String... wrappers) {
        if (content == null) return null;
        Map<String, Object> target = notch(content, wrappers);
        Long id = WjdrEngine.extractId(target);
        if (id == null && target != content) id = WjdrEngine.extractId(content);
        return id;
    }

    /** 取某推送的活动内层内容（wrapper 可空；包装层缺失时回退原内容）。 */
    static Map<String, Object> activityInfoOf(WjdrEngine engine, String proto, String wrapper) {
        if (engine == null) return null;
        Map<String, Object> raw = engine.notifyContent(proto);
        if (raw == null) return null;
        if (wrapper == null) return raw;
        Map<String, Object> inner = Exec.mapOf(raw.get(wrapper));
        if (inner != null) return inner;
        // 引擎 notifyData 已解包 data/info/content 一层时的兼容
        Map<String, Object> data = engine.notifyData(proto);
        if (data != null) {
            inner = Exec.mapOf(data.get(wrapper));
            if (inner != null) return inner;
        }
        return raw;
    }

    /** 逐变体（_info/_init/_prepared…）收集活动内层内容；每个 proto 保留一条，去重由调用方做。 */
    static List<Map<String, Object>> activityInfos(WjdrEngine engine, String wrapper, String... protos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (engine == null) return out;
        for (String proto : protos) {
            Map<String, Object> info = activityInfoOf(engine, proto, wrapper);
            if (info != null && !info.isEmpty()) out.add(info);
        }
        return out;
    }

    // =======================================================================
    // R2 通知历史消费 + C5 活动日历门控（批次3 2026-10-23）
    //
    // 网页版把每条 inbound notify 都当一次状态更新（engine/TaskEngine.java:446-483），
    // 活动状态机按活动 id 逐个维护并逐个 claimOne（service/DailyActivityRewardsState.java:95-225、
    // service/DailyActivityRewardsService.java:398-411）；我方引擎 per-proto 只留最后一帧
    // （app/src/main/java/com/carlos/vault/wjdr/WjdrEngine.java:686-688），多实例活动每轮只覆盖最后到达项。
    // 这里从 {@link ServerNotifyHistory}（SessionKeeper inbound 钩子写入的环形历史）补齐，
    // 并保留 engine.notifyContent 单条路径兜底：历史被容量淘汰/钩子晚挂时仍按旧行为执行。
    // =======================================================================

    /** 每轮活动领取请求上限（历史多实例放大保护；网页版 claimOne 逐项领取，无批量捷径）。 */
    static final int ACTIVITY_MAX_REQUESTS_PER_ROUND = 40;

    /** 活动日历新鲜度：超过该时长视为“无法确认”（不拦）；对齐网页版日历周期续拉语义
     *  （service/DailyActivityCalendarSyncScheduler.java:29-120）。 */
    static final long ACTIVITY_CALENDAR_FRESH_MILLIS = 24L * 3600_000L;

    /**
     * “刚结束”守卫：只拦最近 {@value} 秒内结束的窗口。
     * 对齐网页版 {@code DailyActivityCalendarState.recentEndFamily(guardSeconds)}
     * （service/DailyActivityCalendarState.java:222-243）——周期活动（周常/月常）当前日历帧
     * 可能只含本期窗口，久远结束 ≠ 活动永不再开，此时不能确认未开放，不拦。
     */
    static final long ACTIVITY_CALENDAR_RECENT_END_GUARD = 12L * 3600L;

    /**
     * 历史原始内容解包（与 {@link #activityInfoOf} 同构，作用于历史快照）：
     * 先找顶层 wrapper（{@code {"ogp_info":{...}}}），再找 data/info/content 一层内的 wrapper，
     * 都不命中回退原内容（对齐 service/DailyActivityRewardsState.java:95-225 的包装层处理）。
     */
    static Map<String, Object> activityInfoSnapshot(Map<String, Object> content, String wrapper) {
        if (content == null) return null;
        if (wrapper == null) return content;
        Map<String, Object> inner = Exec.mapOf(content.get(wrapper));
        if (inner != null) return inner;
        for (String k : new String[]{"data", "info", "content"}) {
            Map<String, Object> data = Exec.mapOf(content.get(k));
            if (data == null) continue;
            inner = Exec.mapOf(data.get(wrapper));
            if (inner != null) return inner;
        }
        return content;
    }

    /**
     * 活动内层内容合集（R2）：历史快照（每协议最多 8 条）+ 引擎最新一条兜底。
     *
     * <p>按到达顺序追加，最后调用 {@link #dedupeLatestActivityInfos} 做“同 id 后者覆盖”。
     * 历史为空（未挂钩子/被淘汰）时行为与旧 {@link #activityInfos(WjdrEngine, String, String...)} 一致。
     */
    static List<Map<String, Object>> activityInfos(TaskContext tc, String wrapper, String... protos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (tc == null) return out;
        for (ServerNotifyHistory.Entry entry : ServerNotifyHistory.history(tc.fpid, protos)) {
            Map<String, Object> info = activityInfoSnapshot(entry.content, wrapper);
            if (info != null && !info.isEmpty()) out.add(info);
        }
        // 旧单条路径兜底：引擎快照可能比历史更新（历史未收录/被淘汰时）
        if (tc.engine != null) {
            for (String proto : protos) {
                Map<String, Object> raw = tc.engine.notifyContent(proto);
                if (raw == null) continue;
                Map<String, Object> info = activityInfoSnapshot(raw, wrapper);
                if (info != null && !info.isEmpty()) out.add(info);
            }
        }
        return dedupeLatestActivityInfos(out);
    }

    /** 历史优先、引擎兜底的单个协议内层内容（历史最新一帧通常与引擎一致，历史缺失时回落引擎）。 */
    static Map<String, Object> activityInfoOf(TaskContext tc, String proto, String wrapper) {
        if (tc == null) return null;
        Map<String, Object> latest = ServerNotifyHistory.latestContent(tc.fpid, proto);
        if (latest != null) {
            Map<String, Object> info = activityInfoSnapshot(latest, wrapper);
            if (info != null) return info;
        }
        return activityInfoOf(tc.engine, proto, wrapper);
    }

    /** 原始内容合集（不拆包装层；月卡 cardList 这类整帧字段用）：历史 + 引擎最新兜底。 */
    static List<Map<String, Object>> activityContents(TaskContext tc, String... protos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (tc == null) return out;
        for (ServerNotifyHistory.Entry entry : ServerNotifyHistory.history(tc.fpid, protos)) {
            if (entry.content != null && !entry.content.isEmpty()) out.add(entry.content);
        }
        if (tc.engine != null) {
            for (String proto : protos) {
                Map<String, Object> raw = tc.engine.notifyContent(proto);
                if (raw != null && !raw.isEmpty() && !out.contains(raw)) out.add(raw);
            }
        }
        return out;
    }

    /**
     * 同 id 活动实例只保留最后一次推送（历史按到达顺序，后者覆盖前者；无 id 的条目原样保留）。
     * 网页版按活动 id 维护状态（service/DailyActivityRewardsState.java:95-225），同一 id 的
     * version/进度以最新帧为准。
     */
    static List<Map<String, Object>> dedupeLatestActivityInfos(List<Map<String, Object>> infos) {
        if (infos == null || infos.isEmpty()) return new ArrayList<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        List<Map<String, Object>> noId = new ArrayList<>();
        for (Map<String, Object> info : infos) {
            if (info == null) continue;
            long id = Exec.longOf(info.get("id"));
            if (id > 0L) byId.put(id, info);
            else noId.add(info);
        }
        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.addAll(noId);
        return out;
    }

    // ---------------- C5 活动日历门控 ----------------

    /**
     * 解析 {@code notify_activity_calendar_data}（纯函数，逐行对齐网页版
     * service/DailyActivityCalendarState.java:37-140 parse）：{@code data} 为 List 或
     * Map&lt;id, {id, schedule:[{begin_ts,end_ts}]}&gt;；解析不出/非法返回空 map（调用方“不拦”）。
     *
     * @return 活动 id → 开放窗口 [{begin,end}]（按 begin 升序；无窗口的活动不收录）
     */
    static Map<Long, List<long[]>> parseActivityCalendar(Map<String, Object> content) {
        Map<Long, List<long[]>> out = new LinkedHashMap<>();
        if (content == null) return out;
        Object data = content.get("data");
        List<Map<String, Object>> entries;
        if (data instanceof List) {
            entries = Exec.mapsOf(data);
        } else if (data instanceof Map) {
            entries = Exec.mapsOf(data);   // Exec.mapsOf 会把数值 key 回填为 id（网页版同款形状兼容）
        } else {
            return out;
        }
        for (Map<String, Object> entry : entries) {
            long id = Exec.longOf(entry.get("id"));
            if (id <= 0L) continue;
            List<long[]> windows = new ArrayList<>();
            for (Map<String, Object> schedule : Exec.mapsOf(entry.get("schedule"))) {
                long begin = firstLongOf(schedule, "begin_ts", "begin");
                long end = firstLongOf(schedule, "end_ts", "end");
                if (begin > 0L && end > begin) windows.add(new long[]{begin, end});
            }
            if (windows.isEmpty()) continue;
            windows.sort((a, b) -> Long.compare(a[0], b[0]));
            out.put(id, windows);
        }
        return out;
    }

    /** 当前活动日历（最新一帧；网页版 accept 是整体替换而非增量合并，service/DailyActivityCalendarState.java:68-79）。 */
    static Map<Long, List<long[]>> activityCalendar(TaskContext tc) {
        if (tc == null) return new LinkedHashMap<>();
        Map<String, Object> latest = ServerNotifyHistory.latestContent(tc.fpid,
                "notify_activity_calendar_data");
        if (latest == null && tc.engine != null) {
            latest = tc.engine.notifyContent("notify_activity_calendar_data");
        }
        return parseActivityCalendar(latest);
    }

    /**
     * 取某活动 id 的日历窗口：先精确 id，再按 family=id/1000 合并同族（网页版
     * service/DailyActivityCalendarState.java:126-256 openFamily/nextBeginFamily 同口径）。
     * 日历未列出该活动 → null（=无法确认，不拦）。
     */
    static List<long[]> calendarWindows(Map<Long, List<long[]>> calendar, long activityId) {
        if (calendar == null || calendar.isEmpty() || activityId <= 0L) return null;
        List<long[]> exact = calendar.get(activityId);
        if (exact != null && !exact.isEmpty()) return exact;
        long family = activityId / 1000L;
        if (family <= 0L) return null;
        List<long[]> merged = null;
        for (Map.Entry<Long, List<long[]>> e : calendar.entrySet()) {
            if (e.getKey() / 1000L != family) continue;
            if (merged == null) merged = new ArrayList<>();
            merged.addAll(e.getValue());
        }
        return merged == null || merged.isEmpty() ? null : merged;
    }

    /**
     * 活动日历门控（C5）：只对<b>能确认</b>未开放/已结束的活动生效——
     * <ul>
     *   <li>日历未收到 / 最新一帧超过 {@link #ACTIVITY_CALENDAR_FRESH_MILLIS} → 不拦；</li>
     *   <li>日历未列出该 id 及同 family 活动 → 不拦（解析不出不拦）；</li>
     *   <li>窗口正在开放 → 不拦；有未来窗口（尚未开始）→ 拦；</li>
     *   <li>窗口全部结束：仅最近 {@link #ACTIVITY_CALENDAR_RECENT_END_GUARD} 内结束才拦
     *       （周期活动久远结束不能确认不会重开，见网页版 recentlyEndedFamily 语义）。</li>
     * </ul>
     *
     * @return null = 放行；非 null = SKIP 原因（含“活动日历”字样，便于 App 展示）
     */
    static String activityCalendarBlock(TaskContext tc, long activityId, long nowSec) {
        if (tc == null || activityId <= 0L) return null;
        long latestAt = ServerNotifyHistory.latestRecordedAt(tc.fpid, "notify_activity_calendar_data");
        boolean knownToEngine = tc.engine != null
                && tc.engine.notifyContent("notify_activity_calendar_data") != null;
        if (latestAt <= 0L && !knownToEngine) return null;               // 没收到日历：不拦
        if (latestAt > 0L && System.currentTimeMillis() - latestAt > ACTIVITY_CALENDAR_FRESH_MILLIS) {
            return null;                                                 // 日历过旧：无法确认，不拦
        }
        List<long[]> windows = calendarWindows(activityCalendar(tc), activityId);
        if (windows == null) return null;                                // 日历未收录：无法确认，不拦
        long nextBegin = 0L;
        long latestEnd = 0L;
        for (long[] window : windows) {
            if (window[0] <= nowSec && nowSec < window[1]) return null;  // 正在开放
            if (window[0] > nowSec && (nextBegin == 0L || window[0] < nextBegin)) nextBegin = window[0];
            latestEnd = Math.max(latestEnd, window[1]);
        }
        if (nextBegin > 0L) {
            return "活动日历显示尚未开启（" + Beijing.fmt("MM-dd HH:mm")
                    .format(new java.util.Date(nextBegin * 1000L)) + " 开始）";
        }
        if (latestEnd > 0L && nowSec - latestEnd <= ACTIVITY_CALENDAR_RECENT_END_GUARD) {
            return "活动日历显示活动已结束";
        }
        return null;   // 久远结束：周期活动可能重开，无法确认未开放 → 不拦
    }

    /** 日历窗口文案（SKIP/调试用）：begin-end 北京时间。 */
    static String calendarWindowsText(List<long[]> windows) {
        if (windows == null || windows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt = Beijing.fmt("MM-dd HH:mm");
        for (long[] window : windows) {
            if (sb.length() > 0) sb.append('/');
            sb.append(fmt.format(new java.util.Date(window[0] * 1000L)))
                    .append('-').append(fmt.format(new java.util.Date(window[1] * 1000L)));
        }
        return sb.toString();
    }

    /** 本轮请求预算是否还有余量。 */
    static boolean activityBudgetLeft(ClaimStat st) {
        return st != null && st.requests < ACTIVITY_MAX_REQUESTS_PER_ROUND;
    }

    /** 占用一次请求预算（返回 false = 本轮已达上限，调用方必须停手且不要记录“已尝试”状态）。 */
    static boolean spendActivityRequest(ClaimStat st) {
        if (!activityBudgetLeft(st)) return false;
        st.requests++;
        return true;
    }

    /** 数值/浮点键（"132.0"/132）→ 132；无法解析 → 0。 */
    static long numericKey(Object key) {
        if (key instanceof Number) return ((Number) key).longValue();
        if (key == null) return 0L;
        String s = String.valueOf(key).trim();
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 包装层/free_reward 里的 {stageId: {mark:"true"}} 或数组 → 已领 stage id 集合（State.trueKeys）。 */
    @SuppressWarnings("unchecked")
    static java.util.Set<Long> markedStageIds(Object raw) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                Object v = e.getValue();
                long stage = 0;
                boolean marked;
                if (v instanceof Map) {
                    Map<String, Object> item = Exec.mapOf(v);
                    stage = Exec.longOf(item.get("id"));
                    marked = truthy(item.get("mark"));
                } else {
                    marked = truthy(v);
                }
                if (stage <= 0) stage = numericKey(e.getKey());
                if (stage > 0 && marked) out.add(stage);
            }
            return out;
        }
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                Map<String, Object> item = Exec.mapOf(o);
                if (item == null) continue;
                long stage = Exec.longOf(item.get("id"));
                if (stage <= 0) stage = numericKey(item.get("stage_id"));
                if (stage > 0 && truthy(item.get("mark"))) out.add(stage);
            }
        }
        return out;
    }

    /** exchange_data {"132.0":{"k":132,"v":0}} 取 idx 的已兑换值；未知 → -1。 */
    static long exchangeValue(Map<String, Object> exchangeData, long idx) {
        if (exchangeData == null || idx <= 0L) return -1L;
        for (Map.Entry<String, Object> e : exchangeData.entrySet()) {
            if (numericKey(e.getKey()) != idx) continue;
            Object v = e.getValue();
            if (v instanceof Map) {
                Map<String, Object> item = Exec.mapOf(v);
                Object raw = item.containsKey("v") ? item.get("v") : item.get("count");
                return Exec.longOf(raw);
            }
            return Exec.longOf(v);
        }
        return -1L;
    }

    static long witnessIndex(long version) {
        return ACT_VALUE_EXCHANGE_WITNESS_INDEX.getOrDefault(version, 0L);
    }

    static long heroHallIndex(long version) {
        return ACT_VALUE_EXCHANGE_DAILY_INDEX.getOrDefault(version, 0L);
    }

    /** 万象杂货铺免费档：reward_group 1..16 且下一档在免费档位表（State.java:1032-1048,1178-1183）。 */
    static boolean infiniteFree(long rewardGroup, long nextOrder) {
        return rewardGroup > 0L && rewardGroup <= 16L && nextOrder > 0L
                && ACT_INFINITE_FREE_ORDERS.contains(nextOrder);
    }

    /** 活动时间窗判定（网页版 fillActivity：start_ts/end_ts）。 */
    static boolean activityActive(long startTs, long endTs, long nowSec) {
        if (startTs > 0L && nowSec < startTs) return false;
        return endTs <= 0L || nowSec < endTs;
    }

    /** 同一北京时间自然日（网页版 DailyServerClock.isSameDay）。 */
    static boolean sameBeijingDay(long aSec, long bSec) {
        if (aSec <= 0L || bSec <= 0L) return false;
        if (aSec == bSec) return true;
        String fmt = "yyyy-MM-dd";
        return Beijing.fmt(fmt).format(new java.util.Date(aSec * 1000L))
                .equals(Beijing.fmt(fmt).format(new java.util.Date(bSec * 1000L)));
    }

    static boolean isOneOf(long id, long[] ids) {
        if (ids == null) return false;
        for (long v : ids) {
            if (v == id) return true;
        }
        return false;
    }

    static long firstLongOf(Map<String, Object> m, String... keys) {
        if (m == null) return 0L;
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return Exec.longOf(v);
        }
        return 0L;
    }

    static String firstStringOf(Map<String, Object> m, String... keys) {
        if (m == null) return "";
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    /** 服务器时间（秒）：优先引擎校准钟，未校准回退本机（与 GameServerClock 缺省语义一致）。 */
    static long serverNowSec(TaskContext tc) {
        if (tc != null && tc.engine != null) {
            com.carlos.vault.wjdr.GameServerClock clock = tc.engine.serverClock();
            if (clock != null && clock.locked()) return clock.nowServerSec();
        }
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * 活动领取错误码分类（网页版 DailyActivityRewardsService.java:219-239）：
     * 冷却/已领/活动未开/未到档位 → 合理跳过；其余 → 失败（不落 ERROR 白名单外不污染）。
     */
    static boolean isActivityUnavailableCode(int code) {
        switch (code) {
            case 2: case 21: case 61: case 7501: case 4303: case 4501:
            case 7401: case 7404: case 40610: case 40611: case 40612: case 7602:
            case 40502: case 40503: case 11723: case 11724: case 11727: case 11728: case 2926:
            case 7301: case 7302: case 7303: case 7306: case 7307:
            case 8201: case 8202: case 8203:
            case 4502: case 4503:
            case 1201: case 1202: case 1204: case 1215:
            case 3601: case 3602: case 6101:
            case 1510: case 1511: case 1528: case 1545:
            case 1570: case 1571: case 1574: case 1576: case 1577: case 1579: case 1580: case 1581:
                return true;
            default:
                return false;
        }
    }

    /** 明确“今日已领”的错误码：写台账后当天不再请求。 */
    static boolean isActivityAlreadyClaimedCode(int code) {
        switch (code) {
            case 21: case 61: case 7501: case 40032: case 40034: case 40611: case 7602: case 4502:
            case 1507:   // 联盟发展/排名奖励「今日已捐献」（生产 state 实证）
                return true;
            default:
                return false;
        }
    }

    /** 活动领取出错文案（按审计口径区分已领/未开放）。 */
    static String activityUnavailableText(int code) {
        switch (code) {
            case 7401: case 7404:
                return "活动已结束或尚未开启，已跳过";
            case 61: case 21: case 7501: case 40611: case 7602: case 4502:
                return "今日免费奖励已领取";
            case 40610: case 40612:
                return "远征宝藏暂不可领（码" + code + "）";
            case 8201: case 8202: case 7301: case 7303: case 7306:
                return "未达到可领档位（码" + code + "）";
            case 7302: case 7307: case 8203:
                return "该档已领或批量失败（码" + code + "）";
            case 4503:
                return "统帅特权未激活";
            case 1528:
                return "免费捐献次数已用尽";
            case 1545:
                return "资源不足，暂不可捐献";
            default:
                return "暂不可领取（码" + code + "）";
        }
    }

    /**
     * 单条免费领取：台账按天去重 → 请求 → 错误码分类 → 领取明细。
     * 领取成功只在 code=0（服务端受理响应）时记账；宁漏不花，无任何付费/钻石分支。
     */
    static void activityClaim(TaskContext tc, ClaimStat st, String subKey, String name, String proto,
                              Map<String, Object> args, String ledgerKey) {
        if (ledgerKey != null && ClaimLedger.isClaimed(tc, "activity_rewards", ledgerKey)) {
            st.skipped(subKey, name + "今日已领（台账去重）");
            return;
        }
        if (ledgerKey != null && ClaimLedger.isClaimed(tc, "activity_rewards", ledgerKey + ":na")) {
            st.skipped(subKey, name + "今日已判活动未开启（不重复探测）");
            return;
        }
        // R2 请求上限：历史多实例（7 档曙光/多卡 BP/多封邮件）逐个领取时防协议风暴。
        if (!spendActivityRequest(st)) {
            st.skipped(subKey, name + "本轮请求上限" + ACTIVITY_MAX_REQUESTS_PER_ROUND + "已到（下轮继续）");
            return;
        }
        try {
            GameMessage rsp = tc.engine.request(proto, args);
            if (isDegraded(rsp)) {
                st.skipped(subKey, name + "协议未收录/响应异常（本活动未开放）");
                return;
            }
            int code = Exec.codeOf(rsp);
            if (code == 0) {
                if (ledgerKey != null) ClaimLedger.mark(tc, "activity_rewards", ledgerKey);
                st.claimed(subKey, name, rsp);
                return;
            }
            if (isActivityUnavailableCode(code)) {
                if (ledgerKey != null && isActivityAlreadyClaimedCode(code)) {
                    ClaimLedger.mark(tc, "activity_rewards", ledgerKey);
                }
                if (ledgerKey != null && (code == 7401 || code == 7404)) {
                    // 活动已结束/未开启：当天不再探测该活动（避免 180s 节奏反复请求）
                    ClaimLedger.mark(tc, "activity_rewards", ledgerKey + ":na");
                }
                st.skipped(subKey, name + activityUnavailableText(code));
                return;
            }
            FileLog.warn("活动奖励领取失败 name={} proto={} code={}", name, proto, code);
            st.failed(subKey, name + "错误码" + code);
        } catch (Exception e) {
            TaskOutcome r = classifyException(e, name);
            if (r.category == TaskOutcome.Category.SESSION_CLOSED) throw new RuntimeException(e);
            st.failed(subKey, r.message);
        }
    }

    static TaskOutcome activityRewards(TaskContext tc) throws Exception {
        try {
            tc.engine.collectNotifies(1500);
            ClaimStat st = new ClaimStat();
            long now = serverNowSec(tc);
            if (tc.subOn("activity_rewards", "month_card", true)) {
                claimWelfareCardDaily(tc, st, now);
            }
            if (tc.subOn("activity_rewards", "vip_exp", true)) {
                claimVipDailyExp(tc, st, now);
            }
            if (tc.subOn("activity_rewards", "bundle", true)) {
                claimDailySpecial(tc, st);
            }
            if (tc.subOn("activity_rewards", "treasures", true)) {
                claimJourneyTreasure(tc, st, now);
                claimLostTreasure(tc, st, now);
                claimWishHouse(tc, st, now);
                claimMiaWishHouse(tc, st, now);
                claimInfiniteGrocery(tc, st, now);
                claimOptionalGiftFamily(tc, st, now);
                claimCustomizeFamily(tc, st, now);
                claimCraftsmanTreasury(tc, st, now);
                claimFishingChampionship(tc, st, now);
            }
            if (tc.subOn("activity_rewards", "battle_pass", true)) {
                claimHeroRally(tc, st, now);
                claimCardBp(tc, st, now);
            }
            if (tc.subOn("activity_rewards", "hero_hall", true)) {
                claimHeroHall(tc, st, now);
            }
            if (tc.subOn("activity_rewards", "hero_valiant", true)) {
                claimHeroicWitness(tc, st, now);
            }
            StringBuilder note = new StringBuilder();
            note.append("领取").append(st.ok).append("/跳过").append(st.skip);
            if (st.fail > 0) note.append("/失败").append(st.fail);
            TaskOutcome.Category cat = st.fail > 0 ? TaskOutcome.Category.ERROR
                    : (st.ok > 0 ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED);
            TaskOutcome r = new TaskOutcome("activity_rewards", "活动奖励合集(17种)", cat,
                    note + "（未开放的活动自动跳过）");
            r.subResults.putAll(st.subs);
            if (st.rewards.length() > 0) {
                r.rewards = st.rewards.substring(0, st.rewards.length() - 1);
            }
            return r;
        } catch (Exception e) {
            return classifyException(e, "活动奖励合集");
        }
    }

    /**
     * 月卡/周卡每日奖励：notify_month_card_info.cardList[]，按卡 id 领（Service:325-362/State:932-1003）。
     *
     * <p>R2：历史里同一协议可能有多帧（多卡先后到/月卡变更），按 card id 合并为“每卡最新一帧”，
     * 引擎单条路径仍作为兜底（{@link #activityContents}）。
     */
    static void claimWelfareCardDaily(TaskContext tc, ClaimStat st, long now) {
        List<Map<String, Object>> contents = activityContents(tc, "notify_month_card_info");
        Map<Long, Map<String, Object>> cards = new LinkedHashMap<>();
        for (Map<String, Object> raw : contents) {
            for (Map<String, Object> card : Exec.mapsOf(raw.get("cardList"))) {
                long id = Exec.longOf(card.get("id"));
                if (id > 0L) cards.put(id, card);   // 后到覆盖先到
            }
        }
        if (cards.isEmpty()) {
            st.skipped("welfare_card", contents.isEmpty() ? "月卡通知未同步" : "未购买月卡/周卡");
            return;
        }
        int claimed = 0;
        for (Map<String, Object> card : cards.values()) {
            long id = Exec.longOf(card.get("id"));
            long validity = firstLongOf(card, "validity_period", "validity");
            long claimAt = firstLongOf(card, "daily_reaward_time", "daily_reward_time");
            if (id <= 0L) continue;
            if (validity <= now) continue;   // 网页版 State:932-1003：validity_period>now 才可领（0=已过期）
            if (sameBeijingDay(claimAt, now)) continue;
            int before = st.ok;
            activityClaim(tc, st, "welfare_card", "月卡每日奖励",
                    PROTO_MONTH_CARD_DAILY, Exec.args("id", id), "welfare_card:" + id);
            if (st.ok > before) claimed++;
        }
        if (claimed == 0 && !st.has("welfare_card")) {
            st.skipped("welfare_card", "月卡每日奖励今日已领或暂不可领");
        }
    }

    /** VIP 每日经验：req_vip_get_daily_exp 无参；状态判据 get_daily_exp_ts（State:1005-1030）。 */
    static void claimVipDailyExp(TaskContext tc, ClaimStat st, long now) {
        // R2：历史最新一帧优先（notify_vip_update 可能晚到覆盖 info），引擎兜底在 activityInfoOf 内。
        Map<String, Object> vip = activityInfoOf(tc, "notify_vip_info", null);
        if (vip == null) vip = activityInfoOf(tc, "notify_vip_update", null);
        if (vip != null) {
            long lv = Exec.longOf(vip.get("vip_lv"));
            if (lv <= 0L) {
                st.skipped("commander_daily_exp", "VIP 状态未同步（vip_lv=0）");
                return;
            }
            if (sameBeijingDay(Exec.longOf(vip.get("get_daily_exp_ts")), now)) {
                st.skipped("commander_daily_exp", "VIP 每日经验今日已领");
                return;
            }
        }
        activityClaim(tc, st, "commander_daily_exp", "VIP每日经验",
                PROTO_VIP_DAILY_EXP, null, "vip_daily_exp");
    }

    /** 每日特惠免费礼包（无参）；台账按天去重避免 180s 节奏重复请求。 */
    static void claimDailySpecial(TaskContext tc, ClaimStat st) {
        activityClaim(tc, st, "daily_special", "每日特惠礼包",
                PROTO_DAILY_SPECIAL, null, "daily_special");
    }

    /** 远征/征途宝藏：notify_daily_claim_info{id:2435002,info{free_reward_ts}}（State:897-910；抓包 :98）。 */
    static void claimJourneyTreasure(TaskContext tc, ClaimStat st, long now) {
        // R2：同一协议会推多个“每日领取”活动，历史里按 id 找 2435002（引擎单条兜底也在这里）。
        Map<String, Object> info = null;
        for (Map<String, Object> candidate : activityInfos(tc, "info", NOTIFY_DAILY_CLAIM_INFO)) {
            long cid = Exec.longOf(candidate.get("id"));
            if (cid == ACT_JOURNEY_TREASURE_ID) info = candidate;
            else if (cid <= 0L && info == null) info = candidate;
        }
        long id = info == null ? ACT_JOURNEY_TREASURE_ID : Exec.longOf(info.get("id"));
        if (id <= 0L) id = ACT_JOURNEY_TREASURE_ID;
        if (id != ACT_JOURNEY_TREASURE_ID) {
            st.skipped("journey_treasure", "远征宝藏活动 id 不匹配（" + id + "）");
            return;
        }
        String calBlock = activityCalendarBlock(tc, ACT_JOURNEY_TREASURE_ID, now);
        if (calBlock != null) {
            st.skipped("journey_treasure", "远征宝藏" + calBlock);
            return;
        }
        if (info != null && sameBeijingDay(Exec.longOf(info.get("free_reward_ts")), now)) {
            st.skipped("journey_treasure", "远征宝藏在今日已领");
            return;
        }
        // 推送未到也尝试（id 为网页版权威常量 2435002），台账防止重复请求
        activityClaim(tc, st, "journey_treasure", "远征宝藏", PROTO_JOURNEY_TREASURE,
                journeyTreasureArgs(), "journey_treasure");
    }

    /** 失落宝藏：notify_lost_treasure_info.info{id,daily_reward_get_ts}（State:756-762）。 */
    static void claimLostTreasure(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        // R2：历史里两个失落宝藏活动（2706001/2706002）各有一帧，逐 id 领取。
        for (Map<String, Object> info : activityInfos(tc, "info", "notify_lost_treasure_info")) {
            long id = Exec.longOf(info.get("id"));
            if (!isOneOf(id, ACT_LOST_TREASURE_IDS)) continue;
            any = true;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("lost_treasure", "失落宝藏" + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "daily_reward_get_ts", "daily_reward_ts"), now)) continue;
            activityClaim(tc, st, "lost_treasure", "失落宝藏",
                    "req_lost_treasure_get_daily_reward", Exec.args("id", id), "lost_treasure:" + id);
        }
        if (!st.has("lost_treasure")) {
            st.skipped("lost_treasure", "失落宝藏未开启/未同步");
        }
    }

    /** 许愿屋：notify_hero_turntable_info|_init.ht_info{id,free_gift_get_time}（State:144-153）。 */
    static void claimWishHouse(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        for (Map<String, Object> info : activityInfos(tc, "ht_info",
                "notify_hero_turntable_info", "notify_hero_turntable_init")) {
            any = true;
            long id = Exec.longOf(info.get("id"));
            if (id <= 0L) id = ACT_WISH_HOUSE_ID;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("wish_house", "许愿屋" + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "free_gift_get_time"), now)) continue;
            activityClaim(tc, st, "wish_house", "许愿屋",
                    "req_hero_turntable_get_free_gift", Exec.args("id", id), "wish_house:" + id);
        }
        if (!st.has("wish_house")) {
            st.skipped("wish_house", "许愿屋通知未同步（活动可能未开放）");
        }
    }

    /** 米娅的许愿小屋：notify_fortune_card_info|_init.fc_info{daily_reward_ts}（State:154-158）。 */
    static void claimMiaWishHouse(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        for (Map<String, Object> info : activityInfos(tc, "fc_info",
                "notify_fortune_card_info", "notify_fortune_card_init")) {
            any = true;
            long id = Exec.longOf(info.get("id"));
            if (id <= 0L) id = ACT_MIA_WISH_HOUSE_ID;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("mia_wish_house", "米娅许愿屋" + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "daily_reward_ts"), now)) continue;
            activityClaim(tc, st, "mia_wish_house", "米娅许愿屋",
                    "req_fortune_card_get_daily_free_gift", Exec.args("id", id),
                    "mia_wish_house:" + id);
        }
        if (!st.has("mia_wish_house")) {
            st.skipped("mia_wish_house", "米娅许愿屋通知未同步（活动可能未开放）");
        }
    }

    /** 万象杂货铺：notify_infinite_box_info|_prepared.info{reward_group,cur_order}（State:1032-1048）。 */
    static void claimInfiniteGrocery(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        // R2：同协议多帧历史（免费档位随 cur_order 推进），按 id 去重后逐项判免费档。
        for (Map<String, Object> info : activityInfos(tc, "info",
                "notify_infinite_box_info", "notify_infinite_box_prepared")) {
            any = true;
            long id = Exec.longOf(info.get("id"));
            if (id <= 0L) id = ACT_INFINITE_GROCERY_ID;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("infinite_grocery", "无限宝箱" + calBlock);
                continue;
            }
            long group = Exec.longOf(info.get("reward_group"));
            long order = Exec.longOf(info.get("cur_order"));
            if (!infiniteFree(group, order + 1L)) continue;
            activityClaim(tc, st, "infinite_grocery", "无限宝箱",
                    "req_infinite_box_get_reward", Exec.args("id", id), "infinite_grocery:" + id);
        }
        if (!st.has("infinite_grocery")) {
            st.skipped("infinite_grocery", "无限宝箱通知未同步（活动可能未开放）");
        }
    }

    /**
     * 曙光智慧 2508008 + 曙光市集 2508001-7：网页版权威是 req_optional_gift_pack_get_free_box
     * （DailyActivityRewardsService.java:398-411 + State.java:912-920），协议族不是 duck_market
     * ——旧 notify_duck_market_act_info/req_duck_market_* 已按审计 §3 移除（网页版源码 0 命中），
     * 保留同名子项文案兜底，避免 App 页显示空白。
     *
     * <p>R2：7 个市集档位 + 智慧各占一帧推送，引擎只留最后一帧 → 从历史取全部 id 逐个 claimOne
     * （同 id 后到帧覆盖：free_box_get_time 等最新状态），台账按 id 去重防重复请求。
     */
    static void claimOptionalGiftFamily(TaskContext tc, ClaimStat st, long now) {
        if (!tc.subOn("activity_rewards", "dawn_market", true)) {
            st.skipped("dawn_market", "子开关「曙光市集/智慧」已关闭");
            return;
        }
        boolean any = false;
        for (Map<String, Object> info : activityInfos(tc, "ogp_info",
                "notify_optional_gift_pack_info", "notify_optional_gift_pack_init")) {
            long id = Exec.longOf(info.get("id"));
            if (id <= 0L) continue;
            boolean wisdom = id == ACT_DAWN_WISDOM_ID;
            boolean market = isOneOf(id, ACT_DAWN_MARKET_IDS);
            if (!wisdom && !market) {
                st.skipped("dawn_market", "曙光礼包未知活动 id " + id);
                continue;
            }
            any = true;
            String subKey = wisdom ? "dawn_wisdom" : "dawn_market";
            String name = wisdom ? "曙光智慧" : "曙光市集";
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped(subKey, name + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "free_box_get_time"), now)) continue;
            activityClaim(tc, st, subKey, name, PROTO_OPTIONAL_GIFT_FREE_BOX,
                    activityIdArgs(id), subKey + ":" + id);
        }
        if (!any && !st.has("dawn_market")) {
            st.skipped("dawn_market", "曙光市集未开启/未同步（optional_gift_pack 推送未到）");
        }
    }

    /** 奇货/匠心/武备自选：notify_customize_item_info|_init.ci_info{id,free_box_get_time}（State:1050-1058）。 */
    static void claimCustomizeFamily(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        // R2：15 个自选活动 id 分散在多帧推送，历史逐 id 领取（同 id 后到帧覆盖）。
        for (Map<String, Object> info : activityInfos(tc, "ci_info",
                "notify_customize_item_info", "notify_customize_item_init")) {
            long id = Exec.longOf(info.get("id"));
            if (id <= 0L) continue;
            String subKey;
            String name;
            if (isOneOf(id, ACT_RARE_CUSTOM_IDS)) {
                subKey = "rare_custom";
                name = "奇货自选";
            } else if (isOneOf(id, ACT_CRAFT_CUSTOM_IDS)) {
                subKey = "craft_custom";
                name = "匠心自选";
            } else if (isOneOf(id, ACT_ARMAMENT_CUSTOM_IDS)) {
                subKey = "armament_custom";
                name = "武备自选";
            } else {
                st.skipped("customize", "自选箱未知活动 id " + id + "（网页版常量表 15 个 id 之外）");
                continue;
            }
            any = true;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped(subKey, name + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "free_box_get_time"), now)) continue;
            activityClaim(tc, st, subKey, name, PROTO_CUSTOMIZE_FREE_BOX,
                    Exec.args("id", id), subKey + ":" + id);
        }
        if (!any && !st.has("customize")) {
            st.skipped("rare_custom", "自选箱通知未同步（活动可能未开放）");
        }
    }

    /** 匠人宝库：notify_multiple_choice_pack_info|_init.mcp_info{id,free_box_get_time}（State:171-176）。 */
    static void claimCraftsmanTreasury(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        // R2：历史逐 id（匠人宝库可能多期/多 id 先后推送），同 id 后到帧覆盖。
        for (Map<String, Object> info : activityInfos(tc, "mcp_info",
                "notify_multiple_choice_pack_info", "notify_multiple_choice_pack_init")) {
            long id = Exec.longOf(info.get("id"));
            if (id <= 0L) continue;
            any = true;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("craftsman_treasury", "匠人宝库" + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "free_box_get_time"), now)) continue;
            activityClaim(tc, st, "craftsman_treasury", "匠人宝库",
                    PROTO_MULTIPLE_CHOICE_FREE_BOX, Exec.args("id", id),
                    "craftsman_treasury:" + id);
        }
        if (!st.has("craftsman_treasury")) {
            st.skipped("craftsman_treasury", "匠人宝库通知未同步（活动可能未开放）");
        }
    }

    /** 钓鱼锦标赛免费礼：notify_fishing_activity_info.activity_info{id,free_gift_get_time}（State:1060-1080）。 */
    static void claimFishingChampionship(TaskContext tc, ClaimStat st, long now) {
        boolean any = false;
        // R2：历史多帧（两个钓鱼活动 id 先后开启），逐 id 领取。
        for (Map<String, Object> info : activityInfos(tc, "activity_info",
                "notify_fishing_activity_info")) {
            long id = Exec.longOf(info.get("id"));
            if (!isOneOf(id, ACT_FISHING_IDS)) continue;
            any = true;
            long start = firstLongOf(info, "preview_end_ts");
            long end = firstLongOf(info, "start_end_ts");
            if (!(start > 0L && end > start && now >= start && now < end)) continue;
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("fishing_championship", "钓鱼免费礼" + calBlock);
                continue;
            }
            if (sameBeijingDay(firstLongOf(info, "free_gift_get_time"), now)) continue;
            activityClaim(tc, st, "fishing_championship", "钓鱼免费礼",
                    "req_fishing_get_free_gift", Exec.args("id", id), "fishing:" + id);
        }
        if (!st.has("fishing_championship")) {
            st.skipped("fishing_championship", "钓鱼活动通知未同步（活动可能未开放）");
        }
    }

    /**
     * 英雄集结（战令）：notify_battle_pass_info|_prepared.bp_info{id,score,free_reward}（State:790-811）；
     * 在线积分变化 notify_battle_pass_score_change{id,score} 也要合并（State:824-836），
     * 否则当天涨分后的新档位要等次日。防重复请求用「上轮已尝试积分」而非按天台账（积分日内在变）。
     *
     * <p>R2：历史里同协议多活动 id（网页版常量 10 个英雄集结 id）逐 id 合并领取；日历门控只拦
     * “能确认未开放”的活动，被门控拦下时<b>不写</b>“已尝试积分”，下轮开窗后仍会补领。
     */
    static void claimHeroRally(TaskContext tc, ClaimStat st, long now) {
        Map<Long, Map<String, Object>> infos = new LinkedHashMap<>();
        for (Map<String, Object> info : activityInfos(tc, "bp_info",
                "notify_battle_pass_info", "notify_battle_pass_prepared")) {
            mergeActivityInfoById(infos, info);
        }
        for (Map<String, Object> info : activityInfos(tc, null,
                "notify_battle_pass_score_change")) {
            mergeActivityInfoById(infos, info);
        }
        boolean any = false;
        for (Map.Entry<Long, Map<String, Object>> e : infos.entrySet()) {
            long id = e.getKey();
            if (!isOneOf(id, ACT_HERO_RALLY_IDS)) continue;
            any = true;
            Map<String, Object> info = e.getValue();
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) {
                st.skipped("hero_rally", "英雄集结未开启/已结束");
                continue;
            }
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("hero_rally", "英雄集结" + calBlock);
                continue;
            }
            long score = Exec.longOf(info.get("score"));
            if (score <= 0L) {
                st.skipped("hero_rally", "英雄集结积分 0，暂无免费档可领");
                continue;
            }
            if (attemptedScore(tc, "hero_rally_" + id) == score) {
                st.skipped("hero_rally", "英雄集结积分未变化（上轮已尝试）");
                continue;
            }
            if (claimHeroRallyOne(tc, st, id, info)) {
                saveModNumber(tc, "hero_rally_" + id, score);
            }
        }
        if (!any && !st.has("hero_rally")) {
            st.skipped("hero_rally", "英雄集结战令通知未同步");
        }
    }

    /**
     * 英雄集结单活动：先批量 req_battle_pass_collect_all_reward；7307 走逐档兜底（Service:241-316）。
     *
     * @return false = 本轮请求预算已耗尽（未真正尝试，调用方不得记录“已尝试积分”）
     */
    static boolean claimHeroRallyOne(TaskContext tc, ClaimStat st, long activityId,
                                     Map<String, Object> info) {
        if (!spendActivityRequest(st)) {
            st.skipped("hero_rally", "本轮请求上限" + ACTIVITY_MAX_REQUESTS_PER_ROUND + "已到（下轮继续）");
            return false;
        }
        try {
            GameMessage rsp = tc.engine.request(PROTO_BATTLE_PASS_COLLECT_ALL,
                    battlePassAllArgs(activityId));
            if (isDegraded(rsp)) {
                st.skipped("hero_rally", "英雄集结响应异常（协议未收录）");
                return true;
            }
            int code = Exec.codeOf(rsp);
            if (code == 0) {
                st.claimed("hero_rally", "英雄集结", rsp);
                return true;
            }
            if (code == 7307) {
                // 批量领取被拒（7307）→ 逐档 req_battle_pass_collect_reward{id,reward_id}
                int claimed = claimHeroRallyStages(tc, st, activityId, info);
                if (claimed > 0) {
                    st.okCount("hero_rally", "英雄集结逐档", claimed);
                } else {
                    st.skipped("hero_rally", "英雄集结暂无可领免费档");
                }
                return true;
            }
            if (isActivityUnavailableCode(code)) {
                st.skipped("hero_rally", "英雄集结" + activityUnavailableText(code));
                return true;
            }
            st.failed("hero_rally", "英雄集结错误码" + code);
            return true;
        } catch (Exception e) {
            TaskOutcome r = classifyException(e, "英雄集结");
            if (r.category == TaskOutcome.Category.SESSION_CLOSED) throw new RuntimeException(e);
            st.failed("hero_rally", r.message);
            return true;
        }
    }

    /**
     * 英雄集结逐档：reward_id = (version-1)*31 + 1..31；7301/7303/7306 停，7302/7307 继续。
     * R2：逐档请求也占本轮预算（{@link #ACTIVITY_MAX_REQUESTS_PER_ROUND}），预算尽即停。
     */
    static int claimHeroRallyStages(TaskContext tc, ClaimStat st, long activityId,
                                    Map<String, Object> info) throws Exception {
        long version = Exec.longOf(info.get("bp_version_idx"));
        if (version <= 0L || version > 39L) return 0;
        long first = (version - 1L) * 31L + 1L;
        java.util.Set<Long> received = markedStageIds(info.get("free_reward"));
        int claimed = 0;
        for (long offset = 0L; offset < 31L; offset++) {
            long rewardId = first + offset;
            if (received.contains(rewardId)) continue;
            if (!spendActivityRequest(st)) break;
            GameMessage r = tc.engine.request(PROTO_BATTLE_PASS_COLLECT,
                    battlePassStageArgs(activityId, rewardId));
            int code = Exec.codeOf(r);
            if (code == 0) {
                claimed++;
                received.add(rewardId);
                continue;
            }
            if (code == 7302 || code == 7307) {
                received.add(rewardId);
                continue;
            }
            if (code == 7301 || code == 7303 || code == 7306) break;
            FileLog.warn("英雄集结逐档失败 activity={} reward={} code={}", activityId, rewardId, code);
            break;
        }
        return claimed;
    }

    /** 卡 BP 逐档：req_card_bp_collect_reward{id,stage_id}，8201/8202 停、8203 记已领（Service:368-396）。 */
    static void claimCardBp(TaskContext tc, ClaimStat st, long now) throws Exception {
        Map<Long, Map<String, Object>> infos = new LinkedHashMap<>();
        // R2：历史里 6 张卡 BP（2505001-3/2506001-3）可能各有一帧，逐 id 合并领取。
        for (Map<String, Object> info : activityInfos(tc, "info",
                "notify_card_bp_info", "notify_card_bp_prepared")) {
            mergeActivityInfoById(infos, info);
        }
        for (Map<String, Object> info : activityInfos(tc, null,
                "notify_card_bp_progress_change", "notify_card_bp_buy_gfit")) {
            mergeActivityInfoById(infos, info);
        }
        boolean any = false;
        for (Map.Entry<Long, Map<String, Object>> e : infos.entrySet()) {
            long id = e.getKey();
            if (!isOneOf(id, ACT_CARD_BP_IDS)) continue;   // 网页版 cardKey 只认 6 个 id
            any = true;
            Map<String, Object> info = e.getValue();
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) {
                st.skipped("card_bp", "卡BP活动未开启/已结束");
                continue;
            }
            String calBlock = activityCalendarBlock(tc, id, now);
            if (calBlock != null) {
                st.skipped("card_bp", "卡BP" + calBlock);
                continue;
            }
            long progress = Exec.longOf(info.get("progress"));
            if (progress <= 0L) {
                st.skipped("card_bp", "卡BP进度 0，暂无免费档可领");
                continue;
            }
            if (attemptedScore(tc, "card_bp_" + id) == progress) {
                st.skipped("card_bp", "卡BP进度未变化（上轮已尝试）");
                continue;
            }
            if (claimCardBpStages(tc, st, id, info, progress)) {
                saveModNumber(tc, "card_bp_" + id, progress);
            }
        }
        if (!any && !st.has("card_bp")) {
            st.skipped("card_bp", "卡BP通知未同步");
        }
    }

    /** 按 activity id 合并多次推送的同一活动（info/prepared/progress_change/score_change 互补字段）。 */
    static void mergeActivityInfoById(Map<Long, Map<String, Object>> infos, Map<String, Object> info) {
        if (info == null) return;
        long id = Exec.longOf(info.get("id"));
        if (id <= 0L) return;
        Map<String, Object> prev = infos.get(id);
        if (prev == null) {
            infos.put(id, new LinkedHashMap<>(info));
            return;
        }
        for (Map.Entry<String, Object> e : info.entrySet()) {
            if (e.getValue() != null) prev.put(e.getKey(), e.getValue());
        }
    }

    /**
     * 卡 BP 逐档（R2：逐档请求占本轮预算，预算尽即停；原 120 次上限改为全局 40 次/轮）。
     *
     * @return false = 本轮请求预算已耗尽（未真正尝试，调用方不得记录“已尝试进度”）
     */
    static boolean claimCardBpStages(TaskContext tc, ClaimStat st, long activityId,
                                     Map<String, Object> info, long progress) throws Exception {
        java.util.Set<Long> rx = loadModStages(tc, "card_bp_rx_" + activityId);
        rx.addAll(markedStageIds(info.get("free_reward")));
        int claimed = 0;
        boolean stopped = false;
        boolean attempted = false;
        for (long stage = 1L; stage <= 100L; stage++) {
            if (rx.contains(stage)) continue;
            if (!activityBudgetLeft(st)) {
                stopped = true;
                st.skipped("card_bp", "本轮请求上限" + ACTIVITY_MAX_REQUESTS_PER_ROUND + "已到（下轮继续）");
                break;
            }
            spendActivityRequest(st);
            attempted = true;
            GameMessage r = tc.engine.request(PROTO_CARD_BP_COLLECT,
                    cardBpStageArgs(activityId, stage));
            int code = Exec.codeOf(r);
            if (code == 0) {
                claimed++;
                rx.add(stage);
                continue;
            }
            if (code == 8203 || code == 7302 || code == 7307) {
                rx.add(stage);       // 网页版 8203=该档已领完，继续下一档
                continue;
            }
            if (code == 8201 || code == 8202 || code == 7301 || code == 7303 || code == 7306) {
                stopped = true;      // 未达到可领进度/已到边界，停止
                break;
            }
            FileLog.warn("卡BP领取失败 card={} stage={} code={}", activityId, stage, code);
            st.failed("card_bp", "卡BP领取错误码" + code);
            stopped = true;
            break;
        }
        saveModStages(tc, "card_bp_rx_" + activityId, rx);
        if (claimed > 0) {
            st.okCount("card_bp", "卡BP阶段", claimed);
        } else if (!stopped) {
            st.skipped("card_bp", "卡BP暂无可领阶段");
        }
        return attempted;
    }

    /** 英雄殿堂（value_exchange 每日档）：req_value_exchange_exchange{id:2702001,idx,count:1}（State:764-778）。 */
    static void claimHeroHall(TaskContext tc, ClaimStat st, long now) {
        claimValueExchange(tc, st, now, false);
    }

    /** 英勇的见证（value_exchange 每期一次）：req_value_exchange_free_exchange{id:2702001,idx}（Service:225）。 */
    static void claimHeroicWitness(TaskContext tc, ClaimStat st, long now) {
        claimValueExchange(tc, st, now, true);
    }

    static void claimValueExchange(TaskContext tc, ClaimStat st, long now, boolean witness) {
        String key = witness ? "heroic_witness" : "hero_hall";
        String name = witness ? "英勇的见证" : "英雄殿堂";
        boolean any = false;
        // R2：历史多帧（_init/_info 及版本推进）按 id 去重取最新一帧（dedupeLatestActivityInfos）。
        for (Map<String, Object> info : activityInfos(tc, "ve_info",
                "notify_value_exchange_info", "notify_value_exchange_init")) {
            if (Exec.longOf(info.get("id")) != ACT_VALUE_EXCHANGE_ID) continue;
            any = true;
            if (!activityActive(firstLongOf(info, "start_ts"), firstLongOf(info, "end_ts"), now)) {
                st.skipped(key, name + "活动未开启/已结束");
                continue;
            }
            String calBlock = activityCalendarBlock(tc, ACT_VALUE_EXCHANGE_ID, now);
            if (calBlock != null) {
                st.skipped(key, name + calBlock);
                continue;
            }
            long version = Exec.longOf(info.get("version"));
            if (version <= 0L) continue;
            if (witness) {
                long idx = witnessIndex(version);
                if (idx <= 0L) {
                    st.skipped(key, "版本 " + version + " 无英勇见证档位");
                    continue;
                }
                if (truthy(info.get("is_free_exchange"))) {
                    st.skipped(key, "英勇的见证本期已领");
                    continue;
                }
                activityClaim(tc, st, key, name, PROTO_VALUE_EXCHANGE_FREE,
                        heroicWitnessArgs(version), "witness:" + version);
            } else {
                long idx = heroHallIndex(version);
                if (idx <= 0L) {
                    st.skipped(key, "版本 " + version + " 无英雄殿堂今日档位");
                    continue;
                }
                long exchanged = exchangeValue(Exec.mapOf(info.get("exchange_data")), idx);
                if (exchanged > 0L) {
                    st.skipped(key, "英雄殿堂今日已兑换");
                    continue;
                }
                activityClaim(tc, st, key, name, PROTO_VALUE_EXCHANGE,
                        heroHallArgs(version), "hero_hall:" + version);
            }
        }
        if (!any && !st.has(key)) {
            st.skipped(key, "价值兑换通知未同步");
        }
    }

    /** 读 mod 里保存的“上轮已尝试进度/积分”（防同状态重复请求）。 */
    static long attemptedScore(TaskContext tc, String key) {
        return numericKey(tc.modState("activity_rewards").optString(key, "0"));
    }

    /** 写 mod 数字（复用 saveModText：文案即十进制数字，跨天保留）。 */
    static void saveModNumber(TaskContext tc, String key, long value) {
        tc.saveModText("activity_rewards", key, String.valueOf(value));
    }

    static java.util.Set<Long> loadModStages(TaskContext tc, String key) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        String raw = tc.modState("activity_rewards").optString(key, "");
        if (raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            long v = numericKey(part);
            if (v > 0L) out.add(v);
        }
        return out;
    }

    static void saveModStages(TaskContext tc, String key, java.util.Set<Long> stages) {
        StringBuilder sb = new StringBuilder();
        for (long v : stages) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        tc.saveModText("activity_rewards", key, sb.toString());
    }

    static int[] tryClaim(TaskContext tc, String name, String proto, Map<String, Object> args,
                          StringBuilder rewardsOut) {
        try {
            GameMessage rsp = tc.engine.request(proto, args);
            if (isDegraded(rsp)) {
                return new int[]{0, 1, 0, 0};
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 2 || c == 21 || c == 61 || c == 7401 || c == 7404 || c == 7501
                        || c == 4303 || c == 4501 || c == 40031 || c == 40032 || c == 40034
                        || c == 40502 || c == 40503 || c == 40611
                        || c == 11724 || c == 11728 || c == 2926) {
                    // 冷却/已领/未开通/未到时间等账号状态：记为跳过，不污染异常计数。
                    return new int[]{0, 1, 0, 0};
                }
                FileLog.warn("活动奖励领取失败 name={} proto={} code={}", name, proto, c);
                return new int[]{0, 0, 0, 1};
            }
            if (rewardsOut != null && rewardsOut.length() <= 600) {
                String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                if (!d.isEmpty()) rewardsOut.append(name).append("：").append(d).append("；");
            }
            return new int[]{1, 0, 0, 0};
        } catch (Exception e) {
            TaskOutcome r = classifyException(e, name);
            if (r.category == TaskOutcome.Category.SESSION_CLOSED) {
                throw new RuntimeException(e);
            }
            return new int[]{0, 0, 0, 1};
        }
    }

    /**
     * 联盟宝箱（P0 修复 2026-10-23，1:1 对齐网页版 DailyAllianceChestState/Service）：
     * <ol>
     *   <li>状态合并：init 初始化 + <b>add 增量</b>（登录后新箱全靠它，旧实现完全忽略 →
     *       生产实证“登录后宝箱全天漏领”）+ update（receive/trophy_received_count/is_end）
     *       + delete（已删箱剔除）；</li>
     *   <li>分类口径按 cid 区间：盟友赠礼 90100-90199 → type 1，战利品宝箱 90200-90299 → type 2，
     *       辉煌宝箱 90300-90399 → type 3（旧实现 cid==90101?2:1 是错的）；</li>
     *   <li>战利品宝箱 500/日上限（trophy_received_count）；</li>
     *   <li>过期箱（endtime &lt; now）不再请求。</li>
     * </ol>
     * 形状兼容：chests/ids 在协议里可能是 List 也可能是 Map，统一走 {@link Exec#mapsOf(Object)}。
     */
    static TaskOutcome allianceChest(TaskContext tc) throws Exception {
        try {
            tc.engine.collectNotifies(1500);
            long nowSec = System.currentTimeMillis() / 1000L;
            Map<Long, Map<String, Object>> chests = new LinkedHashMap<>();
            long trophyReceived = -1L;   // -1 = 未知（不启用上限判断）

            trophyReceived = mergeChestContent(chests,
                    notifyContentOf(tc.engine, "notify_alliance_chest_init"), false, trophyReceived);
            // 批次5：add 用 ServerNotifyHistory 最近多帧合并（引擎每 proto 只留最后一帧，连续新增会丢；
            // 历史 ≤8 帧，旧→新合并、同 id 后帧覆盖；无历史时回退引擎单帧）。
            long initRecordedAt = ServerNotifyHistory.latestRecordedAt(
                    tc.fpid, "notify_alliance_chest_init");
            java.util.List<Map<String, Object>> addFrames = chestHistoryContents(
                    tc.fpid, tc.engine, "notify_alliance_chest_add", initRecordedAt);
            trophyReceived = mergeChestFrames(chests, addFrames, false, trophyReceived);
            logHistoryMerge(tc, "alliance_chest_add", addFrames.size());
            trophyReceived = mergeChestContent(chests,
                    notifyContentOf(tc.engine, "notify_alliance_chest_update"), true, trophyReceived);
            Map<String, Object> del = notifyContentOf(tc.engine, "notify_alliance_chest_delete");
            if (del != null) {
                for (Object rawId : rawValues(del.get("ids"))) {
                    long id = Exec.longOf(rawId);
                    if (id > 0) chests.remove(id);
                }
            }

            if (chests.isEmpty()) {
                Map<String, Object> init = notifyContentOf(tc.engine, "notify_alliance_chest_init");
                String why = init == null
                        ? "宝箱状态未同步（未收到 notify_alliance_chest_init）"
                        : "当前无未领取宝箱";
                return new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.SKIPPED, why);
            }

            long trophyRemaining = trophyReceived < 0 ? Long.MAX_VALUE
                    : Math.max(0L, 500L - trophyReceived);
            int claimed = 0, skipped = 0, failed = 0;
            boolean trophyLimitHit = false;
            StringBuilder detail = new StringBuilder();
            // 2026-10-25：失败码入文案（生产 7 次 ERROR 只写“宝箱失败×1”，无法定位是 1540 还是别的）
            java.util.TreeSet<Integer> failCodes = new java.util.TreeSet<>();
            for (Map<String, Object> chest : chests.values()) {
                if (truthy(chest.get("receive"))) {
                    skipped++;
                    continue;
                }
                long id = Exec.longOf(chest.get("id"));
                if (id <= 0) continue;
                long endTime = firstLong(chest, "endtime", "end_time");
                if (endTime > 0 && endTime < nowSec) {
                    skipped++;            // 已过期
                    continue;
                }
                long cid = firstLong(chest, "cid", "chest_config_id");
                int type = allianceChestType(cid);
                if (type == 2) {
                    if (trophyRemaining <= 0) {
                        skipped++;
                        trophyLimitHit = true;
                        continue;
                    }
                    trophyRemaining--;
                }
                GameMessage rsp = tc.engine.request("req_alliance_receive_chest",
                        args("id", id, "type", type));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    claimed++;
                    detail.append(chest.get("name") == null ? ("#" + id) : chest.get("name"))
                            .append("✓ ");
                } else if (code == 2 || code == 4303 || code == 7501 || code == 40611) {
                    skipped++;
                } else if (code == 1529) {   // 战利品宝箱上限
                    trophyLimitHit = true;
                    skipped++;
                } else {
                    failed++;
                    failCodes.add(code);
                }
            }
            TaskOutcome r;
            if (claimed > 0) {
                r = new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.CLAIMED, "联盟宝箱领取×" + claimed + " " + detail);
            } else if (failed > 0) {
                r = new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.ERROR,
                        "宝箱失败×" + failed + (failCodes.isEmpty() ? "" : "（码" + failCodes + "）"));
            } else {
                String why = trophyLimitHit ? "战利品宝箱已达 500/日上限" : "暂无可领取的宝箱";
                r = new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.SKIPPED, why);
            }
            return r;
        } catch (Exception e) {
            return classifyException(e, "联盟宝箱");
        }
    }

    /**
     * 合并一帧宝箱推送（init/add/update）到内存视图（批次4 起抽成纯函数，批次5 多帧复用）。
     *
     * @param markReceived update 推送：出现的箱子一律标记 receive=true
     * @return 最新的 trophy_received_count（-1 = 未出现）
     */
    static long mergeChestContent(Map<Long, Map<String, Object>> chests,
                                  Map<String, Object> content,
                                  boolean markReceived, long trophyReceived) {
        if (content == null) return trophyReceived;
        if (content.containsKey("trophy_received_count")) {
            trophyReceived = Math.max(0L, Exec.longOf(content.get("trophy_received_count")));
        }
        for (Map<String, Object> raw : Exec.mapsOf(content.get("chests"))) {
            long id = Exec.longOf(raw.get("id"));
            if (id <= 0) continue;
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> prev = chests.get(id);
            if (prev != null) merged.putAll(prev);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() != null) merged.put(e.getKey(), e.getValue());
            }
            merged.put("id", id);
            if (markReceived) {
                merged.put("receive", true);
            } else if (raw.containsKey("receive")) {
                merged.put("receive", raw.get("receive"));
            }
            chests.put(id, merged);
        }
        return trophyReceived;
    }

    /**
     * 多帧宝箱合并（批次5）：按历史到达顺序（旧→新）逐帧调用 {@link #mergeChestContent}，
     * 同 id 后帧覆盖先帧、不同 id 并集；空/缺失帧不改变视图（调用方回退单帧）。
     */
    static long mergeChestFrames(Map<Long, Map<String, Object>> chests,
                                 java.util.List<Map<String, Object>> frames,
                                 boolean markReceived, long trophyReceived) {
        if (frames == null || frames.isEmpty()) return trophyReceived;
        long last = trophyReceived;
        for (Map<String, Object> frame : frames) {
            last = mergeChestContent(chests, frame, markReceived, last);
        }
        return last;
    }

    /**
     * 宝箱历史帧（批次5）：优先 {@link ServerNotifyHistory#history(String, String...)}
     * 的最近多帧（同 proto ≤8，旧→新），为空时回退引擎单帧（等价批次4 行为）。
     * 历史内容是 inbound 原始帧，按 {@link #notifyPayload} 解包 data/info/content 一层。
     */
    static java.util.List<Map<String, Object>> chestHistoryContents(String fpid, WjdrEngine engine,
                                                                    String proto) {
        return chestHistoryContents(fpid, engine, proto, 0L);
    }

    /**
     * @param sinceMillis 只收 recordedAtMillis &gt;= since 的历史帧（0=不过滤）。联盟宝箱传“本次
     *                    notify_alliance_chest_init 的记录时刻”，避免把上一会话残留的旧 add 箱
     *                    带入当前视图（历史按账号保留、会话下线不清）。
     */
    static java.util.List<Map<String, Object>> chestHistoryContents(String fpid, WjdrEngine engine,
                                                                    String proto, long sinceMillis) {
        java.util.List<Map<String, Object>> out = new ArrayList<>();
        for (ServerNotifyHistory.Entry e : ServerNotifyHistory.history(fpid, proto)) {
            if (sinceMillis > 0L && e.recordedAtMillis < sinceMillis) continue;
            Map<String, Object> payload = notifyPayload(e.content);
            if (payload != null && !payload.isEmpty()) out.add(payload);
        }
        if (!out.isEmpty()) return out;
        if (engine == null) return out;
        Map<String, Object> payload = notifyPayload(engine.notifyContent(proto));
        if (payload == null) payload = engine.notifyData(proto);
        if (payload != null && !payload.isEmpty()) out.add(payload);
        return out;
    }

    /** 推送内容解包（与 WjdrEngine.notifyData:704-712 同口径）：data/info/content 一层。 */
    static Map<String, Object> notifyPayload(Map<String, Object> raw) {
        if (raw == null) return null;
        for (String k : new String[]{"data", "info", "content"}) {
            Map<String, Object> inner = Exec.mapOf(raw.get(k));
            if (inner != null) return inner;
        }
        return raw;
    }

    /**
     * 历史合并观测（批次5）：合并帧数 >1 时写一条 info，同一 (fpid,key) 10 分钟限流，
     * 便于生产确认“通知历史真正生效”，又不被 120s/180s 节奏刷屏。
     */
    private static final Map<String, Long> HISTORY_INFO_AT =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void logHistoryMerge(TaskContext tc, String key, int frameCount) {
        if (frameCount <= 1 || tc == null) return;
        long now = System.currentTimeMillis();
        String k = tc.fpid + "|" + key;
        Long last = HISTORY_INFO_AT.get(k);
        if (last != null && now - last < 10 * 60_000L) return;
        HISTORY_INFO_AT.put(k, now);
        FileLog.info("通知历史合并 fpid={} proto={} frames={}", tc.fpid, key, frameCount);
    }

    /** 推送内容：优先解包 data/info/content 内层，取不到再回退原始 content。 */
    private static Map<String, Object> notifyContentOf(WjdrEngine engine, String proto) {
        Map<String, Object> inner = engine.notifyData(proto);
        return inner != null ? inner : engine.notifyContent(proto);
    }

    /** 宝箱类型（1:1 网页版 DailyAllianceChestCategory.fromChestConfigId）。 */
    static int allianceChestType(long cid) {
        if (cid >= 90200L && cid < 90300L) return 2;   // 战利品宝箱
        if (cid >= 90300L && cid < 90400L) return 3;   // 辉煌宝箱
        return 1;                                     // 盟友赠礼（含未知 cid，保持旧默认 1）
    }

    private static long firstLong(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) return Exec.longOf(v);
        }
        return 0L;
    }

    /** Map → values（键集合）；Collection → 原样；其它 → 空。用于 delete.ids 等形状不固定字段。 */
    private static List<Object> rawValues(Object v) {
        if (v instanceof Map) return new ArrayList<>(((Map<?, ?>) v).values());
        if (v instanceof List) return new ArrayList<>((List<?>) v);
        if (v instanceof java.util.Collection) return new ArrayList<>((java.util.Collection<?>) v);
        return new ArrayList<>();
    }

    static TaskOutcome allianceDonate(TaskContext tc) throws Exception {
        try {
            StringBuilder sb = new StringBuilder();
            StringBuilder rewardParts = new StringBuilder();
            int ok = 0;
            tc.engine.collectNotifies(1500);

            // 免费科技捐献（L2 P0 修复 2026-10-23；P0 2026-10-28 持久化加固）——1:1 网页版：
            // DailyAllianceDonationService.java:500-556（while freeCount>0 循环、推荐科技优先、
            //   req_alliance_science_donate{id,level:target.level()+1}、SKIPPABLE_TARGET_CODES:66-68、
            //   orderedTargets:556+、均不可捐 → donationDeferred 300000ms）；
            // DailyAllianceDonationState.java:394-406（notify_alliance_science_init.sciences 是 Map，
            //   Exec.mapsOf 已兼容 Map/List）、:443-447（notify_alliance_science_exp_update.science 单条累积，
            //   生产单日 144 条）、:520-566（notify_recover_resource_init/update.data["205.0"]
            //   {count,max_count,next_calc_ts,recover,interval} + projectRecovery）。
            // P0 2026-10-28（生产实证）：会话启动 37 分钟内 notify_alliance_science_init 未到 →
            //   旧实现整段“科技数据未同步”一次不捐；1507/1584 单目标码阻塞整轮（883 次/日）。
            //   修复：sciences/recommend/recovery 持久化到 srv:alliance_science:<fpid>:<sid>，
            //   推送漏发/会话重启后仍可捐；免费次数仍「未知不盲捐」，但可由持久化快照投影恢复；
            //   单目标不可捐 → 下一个目标；全部不可捐 → 明确状态 + 5 分钟冷却，不误判失败、不请求风暴。
            try {
                // ① 读持久化快照 + 合并本次推送：推送权威、持久化补漏。
                AllianceScienceState st = loadAllianceScience(tc);
                long nowSec = serverNowSec(tc);
                // 先按持久化快照投影（可能含上一轮本地扣减，不能先被推送覆盖）
                long persistedFree = st.recoveryKnown()
                        ? projectedRecoveryCount(st.recovery, nowSec) : -1L;
                java.util.Map<Long, long[]> freshSciences = collectSciences(tc.engine);
                if (!freshSciences.isEmpty()) mergeSciences(st.sciences, freshSciences);
                long freshRecommend = recommendedScienceId(tc.engine);
                if (freshRecommend > 0L) st.recommend = freshRecommend;
                java.util.Map<String, Object> freshRecovery = recoveryOf(tc.engine);
                long engineFree = freshRecovery == null ? -1L : projectedRecoveryCount(freshRecovery, nowSec);
                // ② 免费次数：两路投影取小（宁漏不花）；都未知 → -1（不盲捐付费）。
                long freeCount = effectiveFreeCount(engineFree, persistedFree);
                // 选一个恢复快照做后续扣减：没有持久化 / 引擎侧更保守 → 用引擎新值；否则保留持久化侧
                // （持久化侧可能已按上一轮成功捐献本地扣减，用陈旧 init 快照覆盖会导致重复超捐）。
                if (freshRecovery != null && (persistedFree < 0L || engineFree <= persistedFree)) {
                    st.recovery = new LinkedHashMap<>(freshRecovery);
                }
                // 兼容历史版本写在 mod 键里的 no_free_until
                long legacyNoFree = tc.modState("alliance_donate").optLong("no_free_until", 0L);
                if (legacyNoFree > nowSec) st.noFreeUntilSec = Math.max(st.noFreeUntilSec, legacyNoFree);
                // 解析成功即落盘（推送漏发/会话重启后下一轮仍可用）
                if (!freshSciences.isEmpty() || freshRecommend > 0L || freshRecovery != null) {
                    saveAllianceScience(tc, st);
                }
                if (st.noFreeUntilSec > nowSec) freeCount = 0L;

                if (!st.sciencesKnown()) {
                    sb.append("科技捐献:科技数据未同步（notify_alliance_science_init/exp_update 未到，等待推送）; ");
                } else if (freeCount < 0L) {
                    sb.append("科技捐献:免费次数未同步（notify_recover_resource_init 未到），本轮不盲捐（不消耗付费）; ");
                } else if (freeCount > 0L && st.targetCooldownUntilSec > nowSec) {
                    sb.append("科技捐献:科技暂不可捐，")
                            .append(donationClock(st.targetCooldownUntilSec)).append(" 后自动重试; ");
                } else if (freeCount <= 0L) {
                    sb.append("科技捐献:免费次数已用尽");
                    long next = st.recoveryKnown() ? Exec.longOf(st.recovery.get("next_calc_ts")) : 0L;
                    if (next > nowSec) sb.append("（下次恢复 ").append(donationClock(next)).append("）");
                    sb.append("，恢复后自动捐献; ");
                } else {
                    if (freshSciences.isEmpty()) {
                        sb.append("科技捐献:推送未到，使用缓存科技表; ");   // 持久化兜底，避免整段时间不捐
                    }
                    int donated = 0;
                    int lastSkipCode = 0;
                    java.util.List<long[]> targets = orderedScienceTargets(st.sciences, st.recommend);
                    int budget = (int) Math.min(50L, freeCount);
                    boolean stopped = false;
                    for (int i = 0; i < budget && !stopped; i++) {
                        boolean any = false;
                        for (long[] target : targets) {
                            GameMessage rsp = tc.engine.request("req_alliance_science_donate",
                                    args("id", target[0], "level", target[1] + 1L));
                            if (isDegraded(rsp)) {
                                sb.append("科技捐献:响应异常; ");
                                stopped = true;
                                break;
                            }
                            int code = Exec.codeOf(rsp);
                            if (code == 0) {
                                donated++;
                                any = true;
                                String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                                if (!d.isEmpty() && rewardParts.length() < 600) {
                                    rewardParts.append("科技捐献：").append(d).append("；");
                                }
                                break;   // 成功即重新选目标（网页版成功一次就进入下一次循环）
                            }
                            if (isDonationTargetSkipCode(code)) {
                                // 1576 研究中 / 1577 捐满 / 1581 条件不满足 / 1507 今日已捐 / 1584 目标不可捐…
                                lastSkipCode = code;
                                continue;   // 该科技不可捐 → 下一个目标
                            }
                            if (code == 1528) {
                                // 服务端判免费次数用尽：本地记恢复时刻，恢复后自动继续捐。
                                java.util.Map<String, Object> base = st.recoveryKnown() ? st.recovery : freshRecovery;
                                st.noFreeUntilSec = donationNoFreeUntil(base, nowSec);
                                st.recovery = normalizeRecovery(base, nowSec, 0L);
                                saveAllianceScience(tc, st);
                                tc.saveModText("alliance_donate", "no_free_until", String.valueOf(st.noFreeUntilSec));
                                sb.append("科技捐献:免费次数已用尽（服务端码1528），恢复后自动捐献; ");
                                stopped = true;
                                break;
                            }
                            if (code == 1545) {
                                st.targetCooldownUntilSec = nowSec + DONATION_RESOURCE_COOLDOWN_SEC;
                                saveAllianceScience(tc, st);
                                sb.append("科技捐献:当前资源不足，").append(DONATION_RESOURCE_COOLDOWN_SEC)
                                        .append(" 秒后再试; ");
                                stopped = true;
                                break;
                            }
                            if (code == 1510 || code == 1511) {
                                st.targetCooldownUntilSec = nowSec + DONATION_UNKNOWN_COOLDOWN_SEC;
                                saveAllianceScience(tc, st);
                                sb.append("科技捐献:联盟状态异常，等待重新同步; ");
                                stopped = true;
                                break;
                            }
                            // 其它未知码：不静默（warn + 可读状态 + 冷却），下一轮继续尝试。
                            FileLog.warn("联盟科技捐献失败 id={} level={} code={}",
                                    target[0], target[1] + 1L, code);
                            st.targetCooldownUntilSec = nowSec + DONATION_UNKNOWN_COOLDOWN_SEC;
                            saveAllianceScience(tc, st);
                            sb.append("科技捐献:未知错误码 ").append(code).append("，稍后自动重试; ");
                            stopped = true;
                            break;
                        }
                        if (!any && !stopped) {
                            // 全部目标都“不可捐”：明确语义（不是失败），5 分钟后继续尝试（网页版 donationDeferred 同款）。
                            st.targetCooldownUntilSec = nowSec + DONATION_TARGET_COOLDOWN_SEC;
                            saveAllianceScience(tc, st);
                            sb.append("科技捐献:当前联盟科技均不可捐（已满/研究中等")
                                    .append(lastSkipCode > 0 ? "，最近码 " + lastSkipCode : "")
                                    .append("），").append(donationClock(st.targetCooldownUntilSec))
                                    .append(" 后自动重试; ");
                            break;
                        }
                    }
                    if (donated > 0) {
                        ok += donated;
                        // ③ 成功捐献后落盘：本地扣减免费次数（服务端推送到达前不重复超捐）。
                        java.util.Map<String, Object> base = st.recoveryKnown() ? st.recovery : freshRecovery;
                        st.recovery = normalizeRecovery(base, nowSec, freeCount - donated);
                        st.noFreeUntilSec = 0L;
                        st.targetCooldownUntilSec = 0L;
                        saveAllianceScience(tc, st);
                        tc.saveModText("alliance_donate", "no_free_until", "0");
                        sb.append("科技捐献✓×").append(donated).append("; ");
                    }
                }
            } catch (Exception ex) {
                TaskOutcome r = classifyException(ex, "科技捐献");
                if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                sb.append("科技捐献未完成; ");
            }

            // 无参领取类：每日/每周发展奖励；排名奖励协议需要 type。
            // 已领状态用 ClaimLedger 按天去重（TaskCadence.alliance_donate=30s，不重复请求）。
            String[][] noArg = {
                    {"每日发展奖励", "req_alliance_dev_get_daily", "dev_daily"},
                    {"每周发展奖励", "req_alliance_dev_get_week", "dev_week"},
            };
            for (String[] st : noArg) {
                if (ClaimLedger.isClaimed(tc, "alliance_donate", st[2])) {
                    sb.append(st[0]).append("已领（台账）; ");
                    continue;
                }
                try {
                    GameMessage rsp = tc.engine.request(st[1], null);
                    if (isDegraded(rsp)) { sb.append(st[0]).append(":响应异常; "); continue; }
                    int code = Exec.codeOf(rsp);
                    if (code != 0) {
                        if (isActivityAlreadyClaimedCode(code)) {
                            ClaimLedger.mark(tc, "alliance_donate", st[2]);
                        }
                        sb.append(st[0]).append(describeSupplyCode(code, ":")).append("; ");
                        continue;
                    }
                    ok++;
                    ClaimLedger.mark(tc, "alliance_donate", st[2]);
                    sb.append(st[0]).append("✓ ");
                    String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                    if (!d.isEmpty()) rewardParts.append(st[0]).append("：").append(d).append("；");
                } catch (Exception ex) {
                    TaskOutcome r = classifyException(ex, st[0]);
                    if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                    sb.append(st[0]).append("未完成; ");
                }
            }
            try {
                int rankType = Math.max(1, tc.params("alliance_donate").optInt("rank_type", 1));
                String rankKey = "rank:" + rankType;
                if (!ClaimLedger.isClaimed(tc, "alliance_donate", rankKey)) {
                    GameMessage rsp = tc.engine.request("req_alliance_rank_get_rewards",
                            args("type", rankType));
                    int code = Exec.codeOf(rsp);
                    if (Exec.ok(rsp)) {
                        ok++;
                        ClaimLedger.mark(tc, "alliance_donate", rankKey);
                        sb.append("联盟排名奖励✓ ");
                        String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                        if (!d.isEmpty()) rewardParts.append("联盟排名奖励：").append(d).append("；");
                    } else {
                        if (isActivityAlreadyClaimedCode(code)) {
                            ClaimLedger.mark(tc, "alliance_donate", rankKey);
                        }
                        sb.append("联盟排名奖励").append(describeSupplyCode(code, ":")).append("; ");
                    }
                } else {
                    sb.append("联盟排名奖励已领（台账）; ");
                }
            } catch (Exception ex) {
                TaskOutcome r = classifyException(ex, "联盟排名奖励");
                if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                sb.append("联盟排名奖励未完成; ");
            }

            boolean anyDegraded = sb.toString().contains("响应异常");
            TaskOutcome r = new TaskOutcome("alliance_donate", "联盟捐献",
                    anyDegraded ? TaskOutcome.Category.DEGRADED
                            : (ok > 0 ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED),
                    sb.length() == 0 ? "无结果" : sb.toString());
            if (rewardParts.length() > 0) {
                r.rewards = rewardParts.substring(0, rewardParts.length() - 1);
            }
            return r;
        } catch (Exception e) {
            return classifyException(e, "联盟捐献");
        }
    }

    // ---- 活动/邮件领取参数构造（纯函数，便于单测锁定「协议名+参数」；对照 file:line 见注释） ----

    /** 网页版 DailyActivityRewardsService.java:225：req_value_exchange_free_exchange{id:2702001, idx}。 */
    static Map<String, Object> heroicWitnessArgs(long version) {
        long idx = witnessIndex(version);
        if (idx <= 0L) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ACT_VALUE_EXCHANGE_ID);
        m.put("idx", idx);
        return m;
    }

    /** 网页版 DailyActivityRewardsService.java:222：req_value_exchange_exchange{id:2702001, idx, count:1}。 */
    static Map<String, Object> heroHallArgs(long version) {
        long idx = heroHallIndex(version);
        if (idx <= 0L) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ACT_VALUE_EXCHANGE_ID);
        m.put("idx", idx);
        m.put("count", 1L);
        return m;
    }

    /** 网页版 DailyActivityRewardsService.java:368-396：req_card_bp_collect_reward{id, stage_id}。 */
    static Map<String, Object> cardBpStageArgs(long cardId, long stageId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cardId);
        m.put("stage_id", stageId);
        return m;
    }

    /** 网页版 DailyActivityRewardsService.java:241-316：req_battle_pass_collect_all_reward{id}。 */
    static Map<String, Object> battlePassAllArgs(long activityId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", activityId);
        return m;
    }

    /** 网页版 DailyActivityRewardsService.java:268-296：req_battle_pass_collect_reward{id, reward_id}。 */
    static Map<String, Object> battlePassStageArgs(long activityId, long rewardId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", activityId);
        m.put("reward_id", rewardId);
        return m;
    }

    /** 网页版 DailyCommanderGiftService.java:272-292：req_vip_get_daily_free_gift{lv:vip_lv}（必传）。 */
    static Map<String, Object> commanderGiftArgs(long vipLv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lv", vipLv);
        return m;
    }

    /** 网页版 DailyActivityRewardsService.java:398-411：req_daily_claim_free_reward{id:2435002}。 */
    static Map<String, Object> journeyTreasureArgs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ACT_JOURNEY_TREASURE_ID);
        return m;
    }

    /** 网页版 DailyActivityRewardsService.java:398-411：idPayload{id}（optional_gift/customize/lost 等）。 */
    static Map<String, Object> activityIdArgs(long activityId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", activityId);
        return m;
    }

    /** 网页版 DailyMailboxRewardService.java:240-300：mid_list_new（新 id）+ mid_list（legacy 数字 id）双字段。 */
    static Map<String, Object> mailBatchArgs(int firstType, List<String> newIds, List<Long> legacyIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("first_type", firstType);
        if (newIds != null && !newIds.isEmpty()) m.put("mid_list_new", new ArrayList<>(newIds));
        if (legacyIds != null && !legacyIds.isEmpty()) m.put("mid_list", new ArrayList<>(legacyIds));
        return m;
    }

    /** 网页版 DailyMailboxRewardService.java:283-312：逐封兜底 payload（mailid_new 优先，legacy 用 mailid）。 */
    static Map<String, Object> mailSingleArgs(MailCandidate candidate) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (candidate == null) return m;
        if (!candidate.newId.isEmpty()) m.put("mailid_new", candidate.newId);
        else if (candidate.legacyId > 0L) m.put("mailid", candidate.legacyId);
        return m;
    }

    // ---- 联盟科技捐献解析/判定（网页版 DailyAllianceDonationState.java:394-566） ----

    /** 通知族 → 联盟科技表（Map/List 容错；exp_update 单条在线事件累积）。 */
    static java.util.Map<Long, long[]> collectSciences(WjdrEngine engine) {
        java.util.Map<Long, long[]> out = new java.util.LinkedHashMap<>();
        if (engine == null) return out;
        Map<String, Object> content = engine.notifyContent("notify_alliance_science_init");
        if (content != null) {
            Map<String, Object> data = engine.notifyData("notify_alliance_science_init");
            Map<String, Object> root = data != null ? data : content;
            for (Map<String, Object> s : Exec.mapsOf(root.get("sciences"))) {
                rememberScience(out, s);
            }
        }
        for (String proto : new String[]{"notify_alliance_science_exp_update",
                "notify_alliance_science_research_finish"}) {
            Map<String, Object> ev = engine.notifyContent(proto);
            if (ev == null) continue;
            Object raw = ev.get("science");
            if (raw == null) {
                Map<String, Object> data = engine.notifyData(proto);
                raw = data == null ? null : data.get("science");
            }
            Map<String, Object> single = Exec.mapOf(raw);
            if (single != null) rememberScience(out, single);
        }
        return out;
    }

    static void rememberScience(java.util.Map<Long, long[]> sciences, Map<String, Object> s) {
        if (s == null) return;
        long id = Exec.longOf(s.get("id"));
        if (id <= 0L) return;
        long lv = Math.max(0L, Exec.longOf(s.get("lv")));
        long donate = Math.max(0L, Exec.longOf(s.get("donate")));
        long[] prev = sciences.get(id);
        if (prev == null || lv >= prev[0]) {
            sciences.put(id, new long[]{lv, donate});
        }
    }

    /** 推荐科技 id：notify_alliance_science_recommend_update/init.recommend（State:430-434）。 */
    static long recommendedScienceId(WjdrEngine engine) {
        if (engine == null) return 0L;
        for (String proto : new String[]{"notify_alliance_science_recommend_update",
                "notify_alliance_science_init"}) {
            Map<String, Object> c = engine.notifyContent(proto);
            if (c == null) continue;
            Object rec = c.get("recommend");
            if (rec == null) {
                Map<String, Object> data = engine.notifyData(proto);
                rec = data == null ? null : data.get("recommend");
            }
            if (rec != null) return Math.max(0L, Exec.longOf(rec));
        }
        return 0L;
    }

    /** 免费捐献次数源：notify_recover_resource_init/update.data["205.0"]（State:520-556）。 */
    static java.util.Map<String, Object> recoveryOf(WjdrEngine engine) {
        if (engine == null) return null;
        // 在线 update 优先于登录 init：init 是旧快照，update 才是捐完后/恢复后的当前值
        for (String proto : new String[]{"notify_recover_resource_update",
                "notify_recover_resource_init"}) {
            Map<String, Object> content = engine.notifyContent(proto);
            if (content == null) continue;
            Map<String, Object> data = engine.notifyData(proto);
            Object raw = data != null ? data : content.get("data");
            for (Map<String, Object> m : Exec.mapsOf(raw)) {
                if (Exec.longOf(m.get("id")) == 205L) return m;
            }
        }
        return null;
    }

    /**
     * 免费捐献次数投影（网页版 DailyAllianceDonationState.java:520-566 rememberRecovery/projectRecovery）：
     * count 是快照次数；now>=next_calc_ts 时按 cycles=1+(now-next)/interval 个周期 × recover 补齐，
     * 上限 max_count。纯函数便于单测。
     */
    static long projectedRecoveryCount(Map<String, Object> recovery, long nowSec) {
        if (recovery == null) return 0L;
        long count = Math.max(0L, Exec.longOf(recovery.get("count")));
        long max = Math.max(0L, Exec.longOf(recovery.get("max_count")));
        long next = Exec.longOf(recovery.get("next_calc_ts"));
        long recover = Math.max(1L, Exec.longOf(recovery.get("recover")));
        long interval = Math.max(0L, Exec.longOf(recovery.get("interval")));
        if (max <= 0L || count >= max || next <= 0L || interval <= 0L || nowSec < next) {
            return count;
        }
        long cycles = 1L + (nowSec - next) / interval;
        return Math.min(max, count + cycles * recover);
    }

    /** 推荐科技优先 + 其余按 id 升序（网页版 orderedTargets 的确定性版本，便于测试/复盘）。 */
    static java.util.List<long[]> orderedScienceTargets(java.util.Map<Long, long[]> sciences,
                                                       long recommended) {
        java.util.List<long[]> out = new ArrayList<>();
        if (sciences == null || sciences.isEmpty()) return out;
        long[] rec = recommended > 0L ? sciences.get(recommended) : null;
        if (rec != null) out.add(new long[]{recommended, rec[0]});
        java.util.List<Long> ids = new ArrayList<>(sciences.keySet());
        java.util.Collections.sort(ids);
        for (long id : ids) {
            if (id == recommended) continue;
            out.add(new long[]{id, sciences.get(id)[0]});
        }
        return out;
    }

    /**
     * 可跳过目标错误码（网页版 DailyAllianceDonationService.java:66-68 SKIPPABLE_TARGET_CODES）。
     *
     * <p>P0 2026-10-28 生产实证补充：
     * <ul>
     *   <li>1507「今日已捐献」：vault-20261027.log 全天 883 次 {@code 科技捐献失败 id=6023 level=1 code=1507}
     *       —— 推荐科技 id=6023 已捐满后旧实现直接停轮，其它科技永远不被尝试（=用户“捐献满了不自动捐”）；</li>
     *   <li>1584：vault-20261028.log 17:51:55 {@code id=6023 level=1 code=1584}（该科技 lv=0 但
     *       donate 累计值巨大 → 目标级“暂不可捐献/需研究”语义），同款阻塞整轮。</li>
     * </ul>
     * 两者都按「该目标不可捐 → 下一个目标」处理；若全部目标都跳过，本轮写“均不可捐”并 5 分钟冷却
     * （网页版 donationDeferred 同款），不会请求风暴。
     */
    static boolean isDonationTargetSkipCode(int code) {
        switch (code) {
            case 2:
            case 1507: case 1584:
            case 1570: case 1571: case 1574: case 1576: case 1577: case 1579: case 1580:
            case 1581:
                return true;
            default:
                return false;
        }
    }

    // ---- 联盟科技捐献持久化（P0 2026-10-28：srv:alliance_science:<fpid>:<sid>） ----
    // 生产实证：会话启动 17:13 → notify_alliance_science_init 17:50:45 才到，37 分钟内
    // “科技数据未同步”一次都不捐（用户感知“捐献满了也不自动捐”）。修复后把已解析的
    // sciences/recommend/免费次数(recovery) 落 StateStore，推送漏发/会话重启后仍可捐；
    // 免费次数仍按网页版「未知不盲捐」，但可由持久化快照投影恢复。

    /** 目标均不可捐冷却（网页版 donationDeferred 300000ms）。 */
    static final long DONATION_TARGET_COOLDOWN_SEC = 300L;
    /** 资源不足冷却（网页版 1545 → donationDeferred 60000ms）。 */
    static final long DONATION_RESOURCE_COOLDOWN_SEC = 60L;
    /** 联盟状态变化/未知码冷却（网页版 1510/1511 等待重新同步、未知码 300000ms）。 */
    static final long DONATION_UNKNOWN_COOLDOWN_SEC = 300L;

    /** 联盟科技捐献持久化快照。 */
    static final class AllianceScienceState {
        final java.util.Map<Long, long[]> sciences = new java.util.LinkedHashMap<>();
        long recommend;
        /** 免费次数恢复快照（null=未知）。 */
        java.util.Map<String, Object> recovery;
        /** 1528/服务端判“免费次数用尽”后的本地恢复时刻（秒，0=无）。 */
        long noFreeUntilSec;
        /** 目标均不可捐/资源不足/联盟异常后的冷却时刻（秒，0=无），防止 30s 节奏请求风暴。 */
        long targetCooldownUntilSec;
        long updatedAtMs;

        boolean sciencesKnown() {
            return !sciences.isEmpty();
        }

        boolean recoveryKnown() {
            return recovery != null && !recovery.isEmpty();
        }
    }

    /** 持久化键（按 fpid:sid，与其它 srv:* 台账同键型）。 */
    static String allianceScienceKey(String fpid, long sid) {
        return "srv:alliance_science:" + fpid + ":" + sid;
    }

    /** 编码持久化快照（纯函数，便于 round-trip 单测）。 */
    static String encodeAllianceScience(AllianceScienceState st) {
        JSONObject o = new JSONObject();
        if (st == null) return o.toString();
        try {
            JSONObject sciences = new JSONObject();
            for (java.util.Map.Entry<Long, long[]> e : st.sciences.entrySet()) {
                long[] v = e.getValue();
                if (e.getKey() == null || e.getKey() <= 0L || v == null) continue;
                JSONObject item = new JSONObject();
                item.put("lv", v[0]);
                item.put("donate", v.length > 1 ? v[1] : 0L);
                sciences.put(String.valueOf(e.getKey()), item);
            }
            o.put("sciences", sciences);
            o.put("recommend", st.recommend);
            if (st.recovery != null) o.put("recovery", new JSONObject(st.recovery));
            o.put("no_free_until", st.noFreeUntilSec);
            o.put("target_cooldown_until", st.targetCooldownUntilSec);
            o.put("updated_at", st.updatedAtMs > 0L ? st.updatedAtMs : System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** 解码持久化快照（纯函数）：空/坏 JSON → 空状态，绝不抛。 */
    static AllianceScienceState decodeAllianceScience(String json) {
        AllianceScienceState st = new AllianceScienceState();
        if (json == null || json.trim().isEmpty()) return st;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject sciences = o.optJSONObject("sciences");
            if (sciences != null) {
                for (String key : sciences.keySet()) {
                    JSONObject item = sciences.optJSONObject(key);
                    if (item == null) continue;
                    long[] v = new long[]{Math.max(0L, item.optLong("lv", 0L)),
                            Math.max(0L, item.optLong("donate", 0L))};
                    long id = Exec.longOf(key);
                    if (id > 0L) st.sciences.put(id, v);
                }
            }
            st.recommend = Math.max(0L, o.optLong("recommend", 0L));
            JSONObject rec = o.optJSONObject("recovery");
            if (rec != null && rec.length() > 0) {
                java.util.Map<String, Object> recovery = new LinkedHashMap<>();
                for (String key : rec.keySet()) recovery.put(key, rec.get(key));
                st.recovery = recovery;
            }
            st.noFreeUntilSec = Math.max(0L, o.optLong("no_free_until", 0L));
            st.targetCooldownUntilSec = Math.max(0L, o.optLong("target_cooldown_until", 0L));
            st.updatedAtMs = Math.max(0L, o.optLong("updated_at", 0L));
        } catch (Throwable ignored) {
            return new AllianceScienceState();
        }
        return st;
    }

    /** 读取持久化快照（无记录=空状态）。 */
    static AllianceScienceState loadAllianceScience(TaskContext tc) {
        try {
            return decodeAllianceScience(tc.store.getString(allianceScienceKey(tc.fpid, tc.sid)));
        } catch (Throwable t) {
            return new AllianceScienceState();
        }
    }

    /** 写持久化快照（写盘失败不影响任务）。 */
    static void saveAllianceScience(TaskContext tc, AllianceScienceState st) {
        if (st == null) return;
        try {
            st.updatedAtMs = System.currentTimeMillis();
            tc.store.putString(allianceScienceKey(tc.fpid, tc.sid), encodeAllianceScience(st));
        } catch (Throwable ignored) {
        }
    }

    /** 合并科技表：引擎推送（fresh）覆盖持久化旧值；同级保留更高 lv（与 rememberScience 一致）。 */
    static void mergeSciences(java.util.Map<Long, long[]> into,
                              java.util.Map<Long, long[]> fresh) {
        if (into == null || fresh == null) return;
        for (java.util.Map.Entry<Long, long[]> e : fresh.entrySet()) {
            long id = e.getKey() == null ? 0L : e.getKey();
            long[] v = e.getValue();
            if (id <= 0L || v == null) continue;
            long[] prev = into.get(id);
            if (prev == null || v[0] >= prev[0]) {
                into.put(id, new long[]{v[0], v.length > 1 ? v[1] : 0L});
            }
        }
    }

    /**
     * 两路免费次数投影合并（纯函数，宁漏不花）：都未知=-1；仅一路已知取该路；都已知取较小值
     * （持久化侧通常是本地已扣减后的值，取小可避免用陈旧 init 快照重复捐到超出免费预算）。
     */
    static long effectiveFreeCount(long engineFreeCount, long persistedFreeCount) {
        if (engineFreeCount < 0L && persistedFreeCount < 0L) return -1L;
        if (engineFreeCount < 0L) return persistedFreeCount;
        if (persistedFreeCount < 0L) return engineFreeCount;
        return Math.min(engineFreeCount, persistedFreeCount);
    }

    /**
     * 1528（免费次数已用尽）后的下一次可捐时刻（秒，纯函数）：优先 recovery.next_calc_ts，
     * 无投影信息时按 interval 兜底（至少 60s，防 30s 节奏请求风暴）。
     */
    static long donationNoFreeUntil(java.util.Map<String, Object> recovery, long nowSec) {
        long next = recovery == null ? 0L : Exec.longOf(recovery.get("next_calc_ts"));
        long interval = recovery == null ? 0L : Math.max(0L, Exec.longOf(recovery.get("interval")));
        if (next > nowSec) return next;
        return nowSec + Math.max(60L, interval > 0L ? interval : 300L);
    }

    /**
     * 把「按 next_calc_ts 投影后的免费次数」归一化成新快照（纯函数）：count=剩余次数，
     * next_calc_ts 推进到未来第一个恢复点（否则下次投影会把已消费周期重复补算 → 多捐）。
     */
    static java.util.Map<String, Object> normalizeRecovery(java.util.Map<String, Object> recovery,
                                                           long nowSec, long count) {
        if (recovery == null) return null;
        java.util.Map<String, Object> out = new LinkedHashMap<>(recovery);
        long max = Math.max(0L, Exec.longOf(recovery.get("max_count")));
        long interval = Math.max(0L, Exec.longOf(recovery.get("interval")));
        long next = Exec.longOf(recovery.get("next_calc_ts"));
        long left = Math.max(0L, count);
        if (max > 0L) left = Math.min(left, max);
        out.put("count", left);
        if (interval > 0L && next > 0L) {
            long firstFuture = next;
            if (firstFuture <= nowSec) {
                long cycles = 1L + (nowSec - firstFuture) / interval;
                firstFuture = firstFuture + cycles * interval;
            }
            out.put("next_calc_ts", firstFuture);
        }
        return out;
    }

    /** 北京时间 HH:mm（状态文案用；next 秒<=0 → 空串）。 */
    static String donationClock(long epochSec) {
        if (epochSec <= 0L) return "";
        return Beijing.fmt("HH:mm").format(new java.util.Date(epochSec * 1000L));
    }

    /** 免费护盾执行器（整轮与 ServerShieldResponder 反应式开盾复用；free_id 动态取，码表归 SKIPPED）。 */
    public static TaskOutcome freeShield(TaskContext tc) throws Exception {
        try {
            // 总开关 battle_shield.enabled（缺省 true=兼容旧行为）：关=定时免费盾与反应式都停
            if (!tc.subOn("battle_shield", "enabled", true)) {
                return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.SKIPPED,
                        "总开关已关闭（App 设置页可开）");
            }
            // free_id 来自 notify_city_item_init.data[].free_id，不能写死 1。
            long freeId = 0;
            Map<String, Object> ci = tc.engine.notifyData("notify_city_item_init");
            Object data = ci == null ? null : ci.get("data");
            List<Map<String, Object>> items = Exec.mapsOf(data);
            if (items.isEmpty() && data instanceof Map) {
                for (Object v : ((Map<?, ?>) data).values()) {
                    Map<String, Object> m = Exec.mapOf(v);
                    if (m != null) items.add(m);
                }
            }
            for (Map<String, Object> item : items) {
                long fid = Exec.longOf(item.get("free_id"));
                if (fid > 0) { freeId = fid; break; }
            }
            if (freeId <= 0) {
                return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.SKIPPED,
                        "城市道具未同步，暂无可用的免费护盾");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("free_id", freeId);
            GameMessage rsp = tc.engine.request("req_city_item_free_use", args);
            if (isDegraded(rsp)) {
                return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 51 || c == 6151 || c == 6158 || c == 7501 || c == 4303) {
                    return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.SKIPPED,
                            "今日已使用或不可用（码" + c + "）");
                }
                return classifyServerCode(c, "战斗护盾");
            }
            return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.CLAIMED,
                    "8小时免费护盾已开启（被袭击时生效）");
        } catch (Exception e) {
            return classifyException(e, "战斗护盾");
        }
    }

    /** 统帅特权是否有效（网页版 DailyCommanderGiftStateSnapshot.active()：vip_lv>0 && activeEnd>serverTime）。 */
    static boolean commanderVipActive(long vipLv, long activeEnd, long nowSec) {
        return vipLv > 0L && (activeEnd <= 0L || activeEnd > nowSec);
    }

    /**
     * 统帅每日礼包（R6 P0 修复 2026-10-23）——1:1 网页版 DailyCommanderGiftService.java:140-292：
     * ①claimable = online && vip_lv>0 && vip_active_endtime>serverTime && get_daily_gift_ts 非今日；
     * ②请求必须带 {lv:vip_lv}（:272-292），旧实现无参 → 生产返回码 4503 并被 classifySimple 落 ERROR；
     * ③4502=今日已领 / 4503=VIP 未激活 → 归 SKIPPED（:41,188-192）；
     * ④autoRenew 默认关（config/DailyCommanderGiftConfig.java:10-12），本函数不做任何钻石续费。
     */
    static TaskOutcome commanderGift(TaskContext tc) throws Exception {
        try {
            long now = serverNowSec(tc);
            Map<String, Object> vip = firstNotify(tc.engine, "notify_vip_info", "notify_vip_update");
            if (vip == null) {
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.SKIPPED,
                        "统帅状态未同步（未收到 notify_vip_info），本轮不猜参数");
            }
            long lv = Exec.longOf(vip.get("vip_lv"));
            long activeEnd = Exec.longOf(vip.get("vip_active_endtime"));
            long claimedAt = Exec.longOf(vip.get("get_daily_gift_ts"));
            if (lv <= 0L) {
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.SKIPPED,
                        "当前统帅等级无效（vip_lv=" + lv + "）");
            }
            if (!commanderVipActive(lv, activeEnd, now)) {
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.SKIPPED,
                        "统帅特权未激活（vip_active_endtime 已过期）");
            }
            if (sameBeijingDay(claimedAt, now)
                    || ClaimLedger.isClaimed(tc, "commander_gift", "gift")) {
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.SKIPPED,
                        "今日统帅每日礼包已领取");
            }
            GameMessage rsp = tc.engine.request(PROTO_COMMANDER_GIFT, commanderGiftArgs(lv));
            if (isDegraded(rsp)) {
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            int code = Exec.codeOf(rsp);
            if (code == 4502) {
                ClaimLedger.mark(tc, "commander_gift", "gift");
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.SKIPPED,
                        "今日统帅每日礼包已领取（码4502）");
            }
            if (code == 4503) {
                return new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.SKIPPED,
                        "统帅特权未激活（码4503，需先开通/续费 VIP）");
            }
            if (code != 0) {
                return classifyServerCode(code, "统帅礼包");
            }
            ClaimLedger.mark(tc, "commander_gift", "gift");
            String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
            TaskOutcome r = new TaskOutcome("commander_gift", "统帅礼包", TaskOutcome.Category.CLAIMED,
                    "统帅每日礼包已领取（VIP" + lv + "）" + (d.isEmpty() ? "" : "：" + d));
            r.rewards = d;
            return r;
        } catch (Exception e) {
            return classifyException(e, "统帅礼包");
        }
    }

    // ==================================================================================
    // 巨兽狩猎（批次8，task-27）：每日发起(type=102) + 手工上车(type=99) + 体力保留 + 唤醒
    //
    // 1:1 基准：网页版 wjdr-review/src-tool/com/wjdr/tool/modules/daily/
    //   - config/DailyBehemothConfig.java：enabled/queueCount/launchLimit(默认10)/minLevel/maxLevel/
    //     forceMaxLevel/prepareTimeIndex/autoUseStaminaItems/autoJoin*（默认 enabled=false）
    //   - service/DailyBehemothService.java:579-700 evaluateLocked：次数→情报让位→队列→
    //     体力(保留+25)→编队→BehemothLaunchFacade；:652-690 体力道具；:752-800 下次唤醒
    //   - modules/behemoth/service/BehemothLaunchFacade.java:34-107 searchTargets：
    //     等级倒序、req_world_map_search{mapobj_type:4,level_min,level_max} → response.point
    //     → req_world_mapobj{kid,point} → mapobj.type==4 且 map_boss.id/march_count
    //   - BehemothAutomationService.java:984-1005：发起 type=102 extra{monsterid,prepare_time_index}；
    //     上车 type=99 extra{master_marchid}（坐标=车头城市 startpoint）
    //   - BehemothStaminaItemSupport.java:20-44：体力道具 610002，每颗 +10，单次最多 6
    //
    // 批次安全口径（宁漏不花）：
    //   - 每日发起默认关闭（behemoth.launch_enabled=false），开启后默认每人每日 1 次；
    //   - 零钻石协议：本区只发 req_world_map_search / req_world_mapobj / req_world_march /
    //     req_use_item(610002，仅显式开关) / 原生 auto_join 两条（保留现状）；
    //   - 体力/队列/编队/目标任一未知 → SKIP + 可读原因，绝不猜；
    //   - 原生 auto_join 行为原样保留（文案/请求不变）；手工上车与其互斥（默认不叠加）。
    // ==================================================================================

    /** 巨兽发起行军 type（BehemothAutomationService.java:1000）。 */
    static final long BEHEMOTH_MARCH_TYPE_LAUNCH = 102L;
    /** 加入集结（上车）type（BehemothAutomationService.java:988）。 */
    static final long BEHEMOTH_MARCH_TYPE_JOIN = 99L;
    /** 世界巨兽 mapobj 类型（BehemothAutomationService.searchPayload：mapobj_type=4）。 */
    static final long BEHEMOTH_MAPOBJ_TYPE = 4L;
    /** 单次发起体力消耗（DailyBehemothService.java:77 BOSS_STAMINA_COST=25）。 */
    static final long BEHEMOTH_STAMINA_PER_LAUNCH = 25L;
    /** 体力道具 id / 每颗恢复 / 单次最多（BehemothStaminaItemSupport.java:12-14）。 */
    static final long BEHEMOTH_STAMINA_ITEM_ID = 610002L;
    static final long BEHEMOTH_STAMINA_ITEM_RECOVERY = 10L;
    static final int BEHEMOTH_STAMINA_ITEM_MAX = 6;
    /** 搜索无目标（BehemothAutomationService.isNoTargetSearchError:2872）。 */
    static final int CODE_BEHEMOTH_NO_TARGET = 2872;
    /** 目标已被抢先集结（isTargetAlreadyRalliedError:2851）。 */
    static final int CODE_BEHEMOTH_TARGET_TAKEN = 2851;
    /** 加入集结类错误：2844 已加入 / 2845 集结失效 / 2846 集结满员。 */
    static final int CODE_BEHEMOTH_JOIN_ALREADY = 2844;
    static final int CODE_BEHEMOTH_JOIN_INVALID = 2845;
    static final int CODE_BEHEMOTH_JOIN_FULL = 2846;
    /** 其它已知错误码。 */
    static final int CODE_BEHEMOTH_STAMINA_SHORT = 51;
    static final int CODE_BEHEMOTH_ALLIANCE = 1507;
    static final int CODE_BEHEMOTH_LIMIT = 1600;
    static final int CODE_BEHEMOTH_FORMATION_SHORT = 401;
    static final int CODE_BEHEMOTH_FROSTFIRE_LOCK = 8637;
    /** 发起成功后的复查间隔（等行军创建/世界状态刷新）。 */
    static final long BEHEMOTH_LAUNCH_RECHECK_SEC = 180L;
    /** 巨兽状态键前缀（srv:behemoth:{fpid}:{sid}:state）。 */
    static final String BEHEMOTH_KEY_PREFIX = "srv:behemoth:";

    static TaskOutcome behemoth(TaskContext tc) throws Exception {
        Exec.Multi m = new Exec.Multi();
        long wakeAtMs = 0L;
        String launchText = "";
        String joinText = "";
        String staminaText = "";
        try {
            BehemothConfig cfg = BehemothConfig.of(tc.params("behemoth"));
            BehemothState state = BehemothState.load(tc);
            long now = serverNowSec(tc);
            state.rollDay(now);

            // ---------- 1) 原生自动上车（既有行为保留；失败只记录，不阻断每日发起/手工上车兜底） ----------
            BehemothAutoJoin autoJoin = behemothAutoJoin(tc, cfg);
            // 资产库已知“已开启”=无任何动作，按 SKIPPED 记录（不制造假领取）；
            // 真正发过开启请求并 code=0 才计 CLAIMED。
            if (autoJoin.on && !autoJoin.noop) m.claimed(autoJoin.text);
            else m.skip(autoJoin.text);

            // ---------- 2) 体力（只读推送 notify_recover_resource_*；未知一律 SKIP） ----------
            BehemothStamina stamina = behemothReadStamina(tc, now);
            long requiredStamina = cfg.staminaReserve + BEHEMOTH_STAMINA_PER_LAUNCH;
            staminaText = stamina.describe(now, cfg.staminaReserve);

            // ---------- 3) 每日发起（默认关；开启后保守 N 次/日，默认 1） ----------
            boolean launchedNow = false;
            if (!cfg.launchEnabled) {
                m.skip("每日巨兽发起默认关（behemoth.launch_enabled=false）；原生自动上车行为不变");
                launchText = "每日发起关闭（默认安全）";
            } else if (state.launchCount >= cfg.launchLimit) {
                m.skip("今日巨兽发起已完成 " + state.launchCount + "/" + cfg.launchLimit);
                launchText = "今日已完成 " + state.launchCount + "/" + cfg.launchLimit;
            } else {
                BehemothLaunch launch = behemothLaunchOnce(tc, cfg, state, stamina, requiredStamina, now);
                launchedNow = launch.launched;
                launchText = launch.text;
                if (launch.launched) m.claimed(launch.text);
                else m.skip(launch.text);
            }

            // ---------- 4) 手工上车（type=99；默认上限 1 队；与原生自动上车互斥） ----------
            BehemothJoin join = behemothJoinOnce(tc, cfg, state, stamina, autoJoin.on, now);
            joinText = join.text;
            if (join.joined > 0) m.claimed(join.text);
            else m.skip(join.text);

            // ---------- 5) 状态落盘 + mod + 下次唤醒 ----------
            state.save(tc);
            behemothWriteMod(tc, autoJoin, staminaText, launchText, joinText, cfg, state);
            wakeAtMs = behemothWakeAtMillis(now, cfg, state, stamina, requiredStamina, launchedNow);
        } catch (Exception e) {
            return classifyException(e, "巨兽狩猎");
        }
        TaskOutcome out = Exec.result(m, "behemoth", "巨兽狩猎");
        if (wakeAtMs > 0L) out.wakeAt(wakeAtMs);
        return out;
    }

    // ---------- 3.1 原生自动上车（保留既有行为：同请求、同文案、同错误码记录） ----------

    static final class BehemothAutoJoin {
        boolean on;
        /**
         * 仅“资产库判定已开启”而无任何实际动作（2026-10-25 P2 修复）：这种情况不产生游戏效果，
         * 不能算领取（生产实证 17/17 次 CLAIMED 但窗口内 0 个请求）。
         */
        boolean noop;
        String text = "";
    }

    private static BehemothAutoJoin behemothAutoJoin(TaskContext tc, BehemothConfig cfg) {
        BehemothAutoJoin out = new BehemothAutoJoin();
        if (!cfg.autoJoinEnabled) {
            out.text = "自动上车已关闭（App 设置页可开）";
            return out;
        }
        // B2-T3：资产库优先（auto_join 状态无独立推送，req 响应已回灌）；明确未开启才走原请求路径。
        try {
            com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                    com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(tc.engine);
            if (lib != null && lib.ready()) {
                com.carlos.vault.wjdr.asset.AssetSnapshot.AutoJoin aj = lib.autoJoin();
                long nowSec = lib.clock().serverNowSec();
                if (autoJoinActive(aj, cfg.autoJoinIds, nowSec)) {
                    out.on = true;
                    out.noop = true;
                    out.text = "自动上车已开启(巨兽/死神/巨熊)（资产库，无需操作）";
                    tc.saveModText("behemoth", "auto_join", "原生自动上车：已开启（资产库）");
                    return out;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            GameMessage autoJoinInfo = tc.engine.request("req_alliance_activity_auto_join_info",
                    args("auto_mass_id_list", cfg.autoJoinIds));
            // W1 响应回灌：auto_join 状态无独立推送，响应即权威源
            applyAssetResponse(tc.engine, "req_alliance_activity_auto_join_info", autoJoinInfo);
            GameMessage open = tc.engine.request("req_alliance_activity_auto_join_open",
                    args("auto_mass_id_list", cfg.autoJoinIds, "march_limit", (long) cfg.autoJoinMarchLimit));
            if (isDegraded(open)) {
                out.text = "原生自动上车响应异常（协议可能变更）";
                return out;
            }
            int code = open == null ? -1 : Exec.codeOf(open);
            if (code == 0) {
                tc.saveModText("behemoth", "auto_join", "原生自动上车：已开启（巨兽/死神/巨熊）");
                out.on = true;
                out.text = "自动上车已开启(巨兽/死神/巨熊)";
                return out;
            }
            String why = behemothCodeText(code);
            out.text = "自动上车开启失败（码" + code + "）" + (why.isEmpty() ? "" : "：" + why);
        } catch (Exception e) {
            if (isSessionClosed(e)) throw new RuntimeException(e);
            out.text = "自动上车异常：" + brief(String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** 资产库 auto_join 是否已开启：选中类目与期望有交集且未过期。 */
    static boolean autoJoinActive(com.carlos.vault.wjdr.asset.AssetSnapshot.AutoJoin aj,
                                  java.util.List<Long> wantedIds, long nowSec) {
        if (aj == null || aj.updatedAtMs <= 0L || aj.selectedIds == null || aj.selectedIds.isEmpty()) {
            return false;
        }
        if (aj.endTime > 0L && aj.endTime <= nowSec) return false;
        if (wantedIds == null || wantedIds.isEmpty()) return true;
        for (Long id : wantedIds) {
            if (id != null && aj.selectedIds.contains(id)) return true;
        }
        return false;
    }

    // ---------- 3.2 每日发起（默认关；流程 1:1 DailyBehemothService.evaluateLocked:579-700） ----------

    static final class BehemothLaunch {
        boolean launched;
        String text = "";
    }

    private static BehemothLaunch behemothLaunchOnce(TaskContext tc, BehemothConfig cfg,
                                                     BehemothState state, BehemothStamina stamina,
                                                     long requiredStamina, long now) throws Exception {
        BehemothLaunch out = new BehemothLaunch();
        // ① 体力：低于“保留 + 25”不发；道具仅显式开关才用（默认关）
        long current = stamina.currentAt(now);
        if (!stamina.hasCurrent) {
            out.text = "体力状态未知（未收到 notify_recover_resource_init），本轮不发起";
            return out;
        }
        if (current < requiredStamina) {
            if (!cfg.autoUseStaminaItem) {
                out.text = "体力不足（当前 " + current + " < 保留" + cfg.staminaReserve
                        + "+" + BEHEMOTH_STAMINA_PER_LAUNCH + "），等待自然恢复";
                return out;
            }
            String refill = behemothUseStaminaItems(tc, requiredStamina - current);
            if (!refill.isEmpty()) {
                out.text = refill;
                return out;
            }
            stamina = behemothReadStamina(tc, now);
            current = stamina.currentAt(now);
            if (!stamina.hasCurrent || current < requiredStamina) {
                out.text = "体力道具使用后仍不足（当前 " + current + "），本轮不发起";
                return out;
            }
        }
        // ② 队列：容量/占用任一未知 → SKIP（宁漏不花）
        int capacity = behemothQueueCapacity(tc.engine);
        int active = behemothActiveMarchCount(tc);
        if (capacity <= 0 || active < 0) {
            out.text = "行军队列状态未知（容量=" + capacity + " 占用=" + (active < 0 ? "未知" : String.valueOf(active)) + "），本轮不发起";
            return out;
        }
        int queueLimit = cfg.effectiveQueueCount(capacity);
        if (active >= queueLimit) {
            out.text = "行军队列已满（" + active + "/" + queueLimit + "），等待部队回城";
            return out;
        }
        // ③ 编队（批次10 统一编队：显式参数→FormationResolver；无参数→旧 hero_ids/squad_index/队1）
        BehemothFormationChoice launchFormationChoice = chooseBehemothFormation(tc, cfg);
        Map<String, Object> formation = launchFormationChoice.payload;
        if (!behemothFormationReady(formation)) {
            out.text = "无可用英雄/兵力编队（" + launchFormationChoice.label + "）："
                    + launchFormationChoice.reason + "，未发起";
            return out;
        }
        // ④ 等级倒序搜索 + 目标校验 + 发起（每级 1 次搜索，防请求风暴）
        for (int level : behemothLevels(cfg)) {
            Map<String, Object> point;
            try {
                point = behemothSearchPoint(tc.engine, level);
            } catch (Exception e) {
                if (isSessionClosed(e)) throw e;
                continue;
            }
            if (point == null) continue;
            BehemothBossTarget target;
            try {
                target = behemothTargetAt(tc.engine, point, level);
            } catch (Exception e) {
                if (isSessionClosed(e)) throw e;
                continue;
            }
            if (target == null) continue;
            if (target.marchCount > 0L) continue;   // 已有集结（别人已发起）
            Map<String, Object> payload = behemothLaunchPayload(target.x, target.y,
                    target.monsterId, formation, cfg.prepareTimeIndex);
            if (payload == null) {
                out.text = "发起参数不完整（坐标/monsterid/编队缺失），未发起";
                return out;
            }
            GameMessage rsp = tc.engine.request("req_world_march", payload);
            // W1 响应回灌：发起成功即新增一条在途行军，资产库队列占用立刻可见
            applyAssetResponse(tc.engine, "req_world_march", rsp);
            int code = rsp == null ? -1 : Exec.codeOf(rsp);
            long marchId = Exec.marchId(rsp);
            if (marchId > 0L) {
                state.launchCount++;
                state.lastLaunchAtSec = now;
                state.lastLaunchLevel = level;
                state.lastCode = 0;
                state.save(tc);
                out.launched = true;
                out.text = "已发起巨兽集结(level=" + level + " monster=" + target.monsterId
                        + " marchId=" + marchId + ") 今日 " + state.launchCount + "/" + cfg.launchLimit;
                FileLog.info("巨兽发起成功 fpid={} sid={} level={} monsterid={} marchId={} 今日={}/{}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(level),
                        String.valueOf(target.monsterId), String.valueOf(marchId),
                        String.valueOf(state.launchCount), String.valueOf(cfg.launchLimit));
                return out;
            }
            if (code == CODE_BEHEMOTH_TARGET_TAKEN) {
                // 目标被抢先集结：换下一个目标/等级，不记失败
                FileLog.info("巨兽发起目标被抢先 fpid={} level={} point=({},{})", tc.fpid,
                        String.valueOf(level), String.valueOf(target.x), String.valueOf(target.y));
                continue;
            }
            state.lastCode = code;
            state.save(tc);
            out.text = "巨兽发起失败（码" + code + "）：" + behemothCodeText(code);
            FileLog.warn("巨兽发起失败 fpid={} sid={} level={} code={}", tc.fpid,
                    String.valueOf(tc.sid), String.valueOf(level), String.valueOf(code));
            return out;
        }
        out.text = "附近未搜到可发起的巨兽（等级 " + cfg.minLevel + "-" + cfg.maxLevel
                + "；已被集结/无目标）";
        return out;
    }

    /** 显式开关下的体力道具（默认关）：失败返回可读原因；成功/无需补充返回空串。 */
    private static String behemothUseStaminaItems(TaskContext tc, long deficit) {
        int count = behemothStaminaItemCount(deficit);
        if (count <= 0) return "体力道具数量计算为 0，未使用";
        try {
            GameMessage rsp = tc.engine.request("req_use_item",
                    args("id", BEHEMOTH_STAMINA_ITEM_ID, "count", (long) count));
            int code = rsp == null ? -1 : Exec.codeOf(rsp);
            if (code != 0) return "体力道具使用失败（码" + code + "）";
            FileLog.info("巨兽体力道具已使用 fpid={} count={}（每颗+{}）", tc.fpid,
                    String.valueOf(count), String.valueOf(BEHEMOTH_STAMINA_ITEM_RECOVERY));
            return "";
        } catch (Exception e) {
            return "体力道具请求异常：" + brief(String.valueOf(e.getMessage()));
        }
    }

    // ---------- 3.3 手工上车（type=99；默认 1 队；与原生自动上车互斥） ----------

    static final class BehemothJoin {
        int joined;
        String text = "";
    }

    private static BehemothJoin behemothJoinOnce(TaskContext tc, BehemothConfig cfg, BehemothState state,
                                                 BehemothStamina stamina, boolean autoJoinOn, long now) throws Exception {
        BehemothJoin out = new BehemothJoin();
        if (!cfg.joinEnabled) {
            out.text = "手工上车关闭（join_enabled=false）";
            return out;
        }
        if (autoJoinOn && !cfg.manualJoinWithAutoJoin) {
            out.text = "原生自动上车已开启成功，手工上车互斥跳过（避免重复上车）";
            return out;
        }
        int remaining = cfg.joinLimit - state.joinCount;
        if (remaining <= 0) {
            out.text = "今日手工上车已达上限 " + cfg.joinLimit + " 队";
            return out;
        }
        long ownUid = tc.engine.lastUid();
        List<BossRally> candidates = behemothRallyCandidates(tc.engine, tc.fpid, now, ownUid);
        if (candidates.isEmpty()) {
            out.text = "未发现可加入的巨兽集结（可开启原生自动上车；本任务不主动搜索他人集结）";
            return out;
        }
        // 体力/队列未知 → 不上车（宁漏不花；上车暂按保留值门控，不扣 25 发起成本）
        long current = stamina.currentAt(now);
        if (!stamina.hasCurrent || current < cfg.staminaReserve) {
            out.text = "体力未知或低于保留值 " + cfg.staminaReserve + "，本轮不上车";
            return out;
        }
        int capacity = behemothQueueCapacity(tc.engine);
        int active = behemothActiveMarchCount(tc);
        if (capacity <= 0 || active < 0) {
            out.text = "行军队列状态未知，本轮不上车";
            return out;
        }
        int queueLimit = cfg.effectiveQueueCount(capacity);
        if (active >= queueLimit) {
            out.text = "行军队列已满（" + active + "/" + queueLimit + "），本轮不上车";
            return out;
        }
        BehemothFormationChoice joinFormationChoice = chooseBehemothFormation(tc, cfg);
        Map<String, Object> formation = joinFormationChoice.payload;
        if (!behemothFormationReady(formation)) {
            out.text = "无可用上车编队（" + joinFormationChoice.label + "）："
                    + joinFormationChoice.reason;
            return out;
        }
        int tried = 0;
        for (BossRally rally : candidates) {
            if (tried >= remaining) break;
            if (ClaimLedger.isClaimed(tc, "behemoth_join", "m" + rally.masterMarchId)) continue;
            Map<String, Object> payload = behemothJoinPayload(rally.masterMarchId,
                    rally.startX, rally.startY, formation);
            if (payload == null) continue;
            GameMessage rsp = tc.engine.request("req_world_march", payload);
            // W1 响应回灌（上车 type=99 占普通队列）
            applyAssetResponse(tc.engine, "req_world_march", rsp);
            tried++;
            int code = rsp == null ? -1 : Exec.codeOf(rsp);
            long marchId = Exec.marchId(rsp);
            if (code == 0 || marchId > 0L) {
                ClaimLedger.mark(tc, "behemoth_join", "m" + rally.masterMarchId);
                state.joinCount++;
                state.lastJoinMasterId = rally.masterMarchId;
                state.save(tc);
                out.joined++;
                out.text = "已手工上车 1 队(车头" + rally.masterMarchId + ") 今日 "
                        + state.joinCount + "/" + cfg.joinLimit;
                FileLog.info("巨兽手动上车成功 fpid={} sid={} masterMarchId={} 今日={}/{} 编队={}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(rally.masterMarchId),
                        String.valueOf(state.joinCount), String.valueOf(cfg.joinLimit),
                        joinFormationChoice.label);
                return out;
            }
            ClaimLedger.mark(tc, "behemoth_join", "m" + rally.masterMarchId);
            String cls = behemothCodeText(code);
            out.text = "手工上车失败（码" + code + "）" + (cls.isEmpty() ? "" : "：" + cls);
            FileLog.warn("巨兽手动上车失败 fpid={} code={} masterMarchId={}", tc.fpid,
                    String.valueOf(code), String.valueOf(rally.masterMarchId));
            if (code == CODE_BEHEMOTH_JOIN_ALREADY) continue;   // 已在集结：换下一车头
            if (code == CODE_BEHEMOTH_JOIN_INVALID || code == CODE_BEHEMOTH_JOIN_FULL) continue;
            return out;
        }
        if (out.text.isEmpty()) out.text = "可加入集结均已在今日尝试过（台账去重）";
        return out;
    }

    // ---------- 3.4 只读状态读取（体力/队列/联盟集结） ----------

    /** 体力快照（网页版 BehemothAccountState.currentStaminaLocked:2245-2270 同口径）。 */
    static final class BehemothStamina {
        boolean hasCurrent;
        long current;
        long max;
        long recover;
        long nextRecoverAt;   // 秒
        long interval;        // 秒

        /** 当前体力（含自然恢复外推；未知返回 0）。 */
        long currentAt(long nowSec) {
            if (!hasCurrent) return 0L;
            long base = Math.max(0L, current);
            if (max <= base || recover <= 0L || nextRecoverAt <= 0L || interval <= 0L) return base;
            long now = nowSec > 0L ? nowSec : System.currentTimeMillis() / 1000L;
            if (now < nextRecoverAt) return base;
            long completed = (now - nextRecoverAt) / interval;
            long periods = completed == Long.MAX_VALUE ? Long.MAX_VALUE : completed + 1L;
            long recovered;
            try {
                recovered = Math.multiplyExact(recover, periods);
            } catch (ArithmeticException ex) {
                recovered = Long.MAX_VALUE;
            }
            if (recovered > Long.MAX_VALUE - base) return max;
            return Math.min(max, base + recovered);
        }

        String describe(long nowSec, long reserve) {
            if (!hasCurrent) return "体力未知（等待 notify_recover_resource_init）";
            StringBuilder sb = new StringBuilder("体力 ").append(currentAt(nowSec));
            if (max > 0L) sb.append("/").append(max);
            sb.append("（保留 ").append(reserve).append("）");
            if (recover > 0L && nextRecoverAt > 0L) {
                sb.append(" · 下次恢复 +").append(recover).append('@')
                        .append(Beijing.fmt("HH:mm").format(new java.util.Date(nextRecoverAt * 1000L)));
            }
            return sb.toString();
        }
    }

    /**
     * 读体力：优先核心资产库（recover id=201 的唯一权威派生视图），资产库未 READY/回滚
     * 开关 off 时保留旧读取（notify_recover_resource_init 全量 + 历史兜底），不新增查询协议。
     * init 缺失 → hasCurrent=false（未知，SKIP）。
     */
    static BehemothStamina behemothReadStamina(TaskContext tc, long nowSec) {
        BehemothStamina fromAsset = behemothAssetStamina(tc);
        if (fromAsset != null) return fromAsset;
        BehemothStamina out = new BehemothStamina();
        if (tc == null || tc.engine == null) return out;
        Map<String, Object> init = null;
        try {
            init = tc.engine.notifyData("notify_recover_resource_init");
        } catch (Throwable ignored) {
        }
        BehemothStamina parsed = behemothStaminaFromContent(init);
        if (parsed != null) return parsed;
        // 历史兜底（ServerNotifyHistory 保留最近 8 帧；登录批 init 可能已被 update 覆盖）
        try {
            for (ServerNotifyHistory.Entry entry : ServerNotifyHistory.history(
                    tc.fpid, "notify_recover_resource_init")) {
                if (entry == null || !"notify_recover_resource_init".equals(entry.proto)) continue;
                BehemothStamina fromHistory = behemothStaminaFromContent(entry.content);
                if (fromHistory != null) return fromHistory;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 资产库体力视图（W2）：ready 且体力已知才返回，否则 null 交给旧读法兜底。
     * 字段口径与 {@link BehemothStamina#currentAt(long)} 一致：
     * count/max_count/recover/next_calc_ts/interval（recover id=201）。
     */
    private static BehemothStamina behemothAssetStamina(TaskContext tc) {
        if (tc == null || tc.engine == null || !AssetStateProjector.enabled()) return null;
        try {
            com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                    com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(tc.engine);
            if (lib == null || !lib.ready()) return null;
            com.carlos.vault.wjdr.asset.AssetSnapshot.Stamina st = lib.stamina();
            if (st == null || !st.known()) return null;
            BehemothStamina out = new BehemothStamina();
            out.hasCurrent = true;
            out.current = Math.max(0L, st.current);
            out.max = Math.max(0L, st.max);
            out.recover = Math.max(0L, st.recoverPer);
            out.nextRecoverAt = Math.max(0L, st.nextCalcTs);
            out.interval = Math.max(0L, st.intervalSec);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 主动请求响应回灌资产库（W1；资产库未 READY/回滚 off 时静默跳过）。
     *
     * <p>设计中的「单写者」是 engine inbound 钩子线程；任务线程在 request 返回后串行回灌
     * 权威响应（auto_join 状态 / req_world_march 行军），CoreAssetLibrary 内部有锁，安全。
     */
    static void applyAssetResponse(WjdrEngine engine, String proto, GameMessage response) {
        if (engine == null || response == null || proto == null || proto.isEmpty()) return;
        if (!AssetStateProjector.enabled()) return;
        Map<String, Object> content = response.getContent();
        if (content == null) return;
        try {
            com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                    com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(engine);
            if (lib == null || !lib.ready()) return;
            lib.applyResponse(proto, content);
        } catch (Throwable ignored) {
        }
    }

    /** 从 notify_recover_resource_init 内容解析体力（id==201，字段 count/max_count/recover/next_calc_ts/interval）。 */
    static BehemothStamina behemothStaminaFromContent(Map<String, Object> content) {
        if (content == null) return null;
        Object data = content.get("data");
        if (data == null) data = content.get("list");
        for (Map<String, Object> item : Exec.mapsOf(data)) {
            if (Exec.longOf(item.get("id")) != 201L) continue;
            return behemothStaminaOf(item);
        }
        // 兼容 data 本身是 {201:{count,...}} 的 map 形状
        Map<String, Object> byId = Exec.mapOf(data);
        if (byId != null) {
            Map<String, Object> row = Exec.mapOf(byId.get("201"));
            if (row == null) row = Exec.mapOf(byId.get(201L));
            if (row != null) return behemothStaminaOf(row);
        }
        return null;
    }

    private static BehemothStamina behemothStaminaOf(Map<String, Object> row) {
        BehemothStamina st = new BehemothStamina();
        st.hasCurrent = true;
        st.current = Math.max(0L, Exec.longOf(row.get("count")));
        st.max = Math.max(0L, Exec.longOf(row.get("max_count")));
        st.recover = Math.max(0L, Exec.longOf(row.get("recover")));
        st.nextRecoverAt = Math.max(0L, Exec.longOf(row.get("next_calc_ts")));
        st.interval = Math.max(0L, Exec.longOf(row.get("interval")));
        return st;
    }

    /**
     * 世界行军队列容量：优先资产库 queue().capacity（网页 MarchCapacityState 派生公式：
     * 科技 1007/1020/1027 + VIP≥6 + buff 10505 + extra_march + 1，只读层 clamp 1..6）；
     * 资产库未 READY/回滚 off 时保留旧读取（notify_world_player_init.world.capacity；未知 0）。
     */
    static int behemothQueueCapacity(WjdrEngine engine) {
        if (AssetStateProjector.enabled()) {
            try {
                com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                        com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(engine);
                if (lib != null && lib.ready()) {
                    com.carlos.vault.wjdr.asset.AssetSnapshot.Queue q = lib.queue();
                    if (q != null && q.capacity > 0) return q.capacity;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Map<String, Object> content = engine.notifyData("notify_world_player_init");
            if (content == null) return 0;
            Map<String, Object> world = Exec.mapOf(content.get("world"));
            if (world == null) world = content;
            long cap = Exec.longOf(world.get("capacity"));
            return cap > 0L ? (int) Math.min(cap, 20L) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 自己当前行军队列占用：优先资产库 queue().occupied（排除巨熊等特殊队列 + 含 type=99
     * 上车，同网页 BehemothAccountState 口径；资产库未 READY/回滚 off 时保留旧协议查询）。
     * 旧读法：req_world_march_detail_4_player_situation（既有协议，-1=未知）。
     */
    static int behemothActiveMarchCount(TaskContext tc) throws Exception {
        if (tc != null && tc.engine != null && AssetStateProjector.enabled()) {
            try {
                com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                        com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(tc.engine);
                if (lib != null && lib.ready()) {
                    com.carlos.vault.wjdr.asset.AssetSnapshot.Queue q = lib.queue();
                    if (q != null && q.known()) return Math.max(0, q.occupied);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("owner", tc.engine.lastUid());
            payload.put("kid", tc.engine.lastServerId());
            GameMessage rsp = tc.engine.request("req_world_march_detail_4_player_situation", payload);
            if (rsp == null || !Exec.ok(rsp)) return -1;
            Map<String, Object> detail = Exec.mapOf(rsp.getContent().get("detail"));
            if (detail == null) detail = rsp.getContent();
            int n = 0;
            for (String key : new String[]{"marchs", "march_list", "list", "marches"}) {
                List<Map<String, Object>> rows = Exec.mapsOf(detail.get(key));
                if (rows.isEmpty()) continue;
                for (Map<String, Object> row : rows) {
                    if (Exec.longOf(row.get("id")) > 0L) n++;
                }
                return n;
            }
            return 0;
        } catch (Exception e) {
            if (isSessionClosed(e)) throw e;
            return -1;
        }
    }

    // ---------- 3.5 世界搜索 / 目标校验 / 集结候选 ----------

    /** 按等级搜索巨兽（req_world_map_search；2872/无 point → null 表示该级无目标）。 */
    static Map<String, Object> behemothSearchPoint(WjdrEngine engine, int level) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search",
                Exec.args("mapobj_type", BEHEMOTH_MAPOBJ_TYPE,
                        "level_min", (long) level, "level_max", (long) level));
        if (rsp == null || !Exec.ok(rsp)) return null;
        Map<String, Object> point = Exec.mapOf(rsp.getContent().get("point"));
        if (point == null) point = Exec.mapOf(rsp.getContent().get("target"));
        long x = point == null ? 0L : Exec.longOf(point.get("x"));
        long y = point == null ? 0L : Exec.longOf(point.get("y"));
        if (x <= 0L || y <= 0L) return null;
        return Exec.point(x, y);
    }

    /** req_world_mapobj 二次校验并解析巨兽目标（mapobj.type==4 / map_boss.id / march_count）。 */
    static BehemothBossTarget behemothTargetAt(WjdrEngine engine, Map<String, Object> point,
                                               int level) throws Exception {
        GameMessage rsp = engine.request("req_world_mapobj",
                Exec.args("kid", engine.lastServerId(), "point", point));
        if (rsp == null || !Exec.ok(rsp)) return null;
        Map<String, Object> mapobj = Exec.mapOf(rsp.getContent().get("mapobj"));
        if (mapobj == null) mapobj = Exec.mapOf(rsp.getContent().get("change"));
        if (mapobj == null) mapobj = rsp.getContent();
        long x = Exec.longOf(point.get("x"));
        long y = Exec.longOf(point.get("y"));
        return parseBehemothBossTarget(mapobj, level, x, y);
    }

    /** 世界巨兽目标。 */
    static final class BehemothBossTarget {
        int level;
        long monsterId;
        long x;
        long y;
        long marchCount;
    }

    /** 纯函数：从 mapobj 解析巨兽目标（BehemothMapTarget.parse 同口径）。 */
    static BehemothBossTarget parseBehemothBossTarget(Map<String, Object> mapobj, int level,
                                                      long fallbackX, long fallbackY) {
        if (mapobj == null) return null;
        Map<String, Object> obj = mapobj;
        Map<String, Object> nested = Exec.mapOf(mapobj.get("mapobj"));
        if (nested != null) obj = nested;
        if (Exec.longOf(obj.get("type")) != BEHEMOTH_MAPOBJ_TYPE) return null;
        Map<String, Object> boss = Exec.mapOf(obj.get("map_boss"));
        if (boss == null) boss = Exec.mapOf(obj.get("boss"));
        if (boss == null) return null;
        long monsterId = Exec.longOf(boss.get("id"));
        if (monsterId <= 0L) monsterId = Exec.longOf(boss.get("monster_id"));
        if (monsterId <= 0L) return null;
        Map<String, Object> point = Exec.mapOf(obj.get("point"));
        long x = point == null ? 0L : Exec.longOf(point.get("x"));
        long y = point == null ? 0L : Exec.longOf(point.get("y"));
        if (x <= 0L) x = fallbackX;
        if (y <= 0L) y = fallbackY;
        if (x <= 0L || y <= 0L) return null;
        BehemothBossTarget target = new BehemothBossTarget();
        target.level = level;
        target.monsterId = monsterId;
        target.x = x;
        target.y = y;
        target.marchCount = Math.max(0L, Exec.longOf(boss.get("march_count")));
        return target;
    }

    /** 可加入的巨兽集结（type=102/5004；来自 notify_mass_march_* 历史，不新增查询协议）。 */
    static final class BossRally {
        long masterMarchId;
        long ownerUid;
        long ownerKid;
        long startX;
        long startY;
        long endAt;
    }

    /** 从通知历史/单帧里筛巨兽集结候选（纯聚合；同 id 去重，保留最新帧）。 */
    static List<BossRally> behemothRallyCandidates(WjdrEngine engine, String fpid, long nowSec, long ownUid) {
        Map<Long, BossRally> merged = new LinkedHashMap<>();
        try {
            for (ServerNotifyHistory.Entry entry : ServerNotifyHistory.history(fpid, "notify_mass_march_")) {
                if (entry == null || entry.content == null) continue;
                for (BossRally rally : parseBehemothRallies(entry.content, nowSec, ownUid)) {
                    merged.put(rally.masterMarchId, rally);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Map<String, Object> latestAdd = engine == null ? null : engine.notifyContent("notify_mass_march_add");
            if (latestAdd != null) {
                for (BossRally rally : parseBehemothRallies(latestAdd, nowSec, ownUid)) {
                    merged.put(rally.masterMarchId, rally);
                }
            }
        } catch (Throwable ignored) {
        }
        List<BossRally> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Long.compare(a.masterMarchId, b.masterMarchId));
        if (out.size() > 6) out = new ArrayList<>(out.subList(0, 6));
        return out;
    }

    /**
     * 纯函数：解析一帧 notify_mass_march_add（{march:{...}} 或 {data:{marchs:[...]}} 兼容）里的巨兽集结。
     * 过滤：type=102/5004、id/owner/startpoint>0、非自己、未过期、未满员。
     */
    static List<BossRally> parseBehemothRallies(Map<String, Object> content, long nowSec, long ownUid) {
        List<BossRally> out = new ArrayList<>();
        if (content == null) return out;
        Map<String, Object> data = content;
        for (String key : new String[]{"data", "info", "content"}) {
            Map<String, Object> inner = Exec.mapOf(data.get(key));
            if (inner != null) {
                data = inner;
                break;
            }
        }
        Map<String, Object> single = Exec.mapOf(data.get("march"));
        if (single != null && !single.isEmpty()) {
            BossRally rally = parseBossRally(single, nowSec, ownUid);
            if (rally != null) out.add(rally);
            return out;
        }
        List<Map<String, Object>> marchs = Exec.mapsOf(data.get("marchs"));
        if (marchs.isEmpty()) marchs = Exec.mapsOf(data.get("marches"));
        if (marchs.isEmpty()) marchs = Exec.mapsOf(data.get("march_list"));
        if (marchs.isEmpty()) {
            BossRally self = parseBossRally(data, nowSec, ownUid);
            if (self != null) out.add(self);
            return out;
        }
        for (Map<String, Object> march : marchs) {
            BossRally rally = parseBossRally(march, nowSec, ownUid);
            if (rally != null) out.add(rally);
        }
        return out;
    }

    static BossRally parseBossRally(Map<String, Object> march, long nowSec, long ownUid) {
        if (march == null) return null;
        long type = Exec.longOf(march.get("type"));
        if (type != BEHEMOTH_MARCH_TYPE_LAUNCH && type != 5004L) return null;
        long id = Exec.longOf(march.get("id"));
        long owner = Exec.longOf(march.get("owner"));
        if (id <= 0L || owner <= 0L) return null;
        if (ownUid > 0L && owner == ownUid) return null;
        Map<String, Object> start = Exec.mapOf(march.get("startpoint"));
        if (start == null) start = Exec.mapOf(march.get("start_point"));
        long sx = start == null ? 0L : Exec.longOf(start.get("x"));
        long sy = start == null ? 0L : Exec.longOf(start.get("y"));
        if (sx <= 0L || sy <= 0L) return null;
        long endAt = Exec.longOf(march.get("endtime"));
        if (endAt > 0L && nowSec > 0L && endAt <= nowSec) return null;
        long state = Exec.longOf(march.get("state"));
        if (state == 4L || state == 0L) return null;   // 已完成/失效
        // 满员过滤（mass_* 详情，max/uids 口径同网页版 isRallyFull）
        Map<String, Object> mass = Exec.mapOf(march.get("mass_alliance_trap"));
        if (mass == null || mass.isEmpty()) mass = Exec.mapOf(march.get("mass_boss"));
        if (mass == null || mass.isEmpty()) mass = Exec.mapOf(march.get("mass_master"));
        if (mass != null && !mass.isEmpty()) {
            long memberMax = Exec.longOf(mass.get("max"));
            int memberCount = Exec.listOf(mass.get("uids")) == null ? 0 : Exec.listOf(mass.get("uids")).size();
            long soldierMax = Exec.longOf(mass.get("soldier_max"));
            long soldierCount = Exec.longOf(mass.get("soldier_count"));
            if (memberMax > 0L && memberCount >= memberMax) return null;
            if (soldierMax > 0L && soldierCount >= soldierMax) return null;
        }
        BossRally rally = new BossRally();
        rally.masterMarchId = id;
        rally.ownerUid = owner;
        rally.ownerKid = Exec.longOf(march.get("kid"));
        rally.startX = sx;
        rally.startY = sy;
        rally.endAt = endAt;
        return rally;
    }

    // ---------- 3.6 参数构造 / 配置 / 状态 / 唤醒（纯函数，单测覆盖） ----------

    /** 发起 payload（BehemothAutomationService.java:991-1005）：type=102 + extra{monsterid,prepare_time_index}。 */
    static Map<String, Object> behemothLaunchPayload(long x, long y, long monsterId,
                                                     Map<String, Object> formation, int prepareTimeIndex) {
        if (x <= 0L || y <= 0L || monsterId <= 0L) return null;
        if (!behemothFormationReady(formation)) return null;
        return Exec.args("type", BEHEMOTH_MARCH_TYPE_LAUNCH,
                "endpoint", Exec.point(x, y),
                "formation", formation,
                "extra", Exec.args("monsterid", monsterId,
                        "prepare_time_index", (long) clampBehemothPrepareIndex(prepareTimeIndex)));
    }

    /** 手工上车 payload（BehemothAutomationService.java:984-989）：type=99 + extra{master_marchid}。 */
    static Map<String, Object> behemothJoinPayload(long masterMarchId, long leaderCityX, long leaderCityY,
                                                   Map<String, Object> formation) {
        if (masterMarchId <= 0L || leaderCityX <= 0L || leaderCityY <= 0L) return null;
        if (!behemothFormationReady(formation)) return null;
        return Exec.args("type", BEHEMOTH_MARCH_TYPE_JOIN,
                "endpoint", Exec.point(leaderCityX, leaderCityY),
                "formation", formation,
                "extra", Exec.args("master_marchid", masterMarchId));
    }

    static boolean behemothFormationReady(Map<String, Object> formation) {
        if (formation == null || formation.isEmpty()) return false;
        List<Object> heroes = Exec.listOf(formation.get("hero_id"));
        if (heroes == null || heroes.isEmpty()) return false;
        List<Object> soldiers = Exec.listOf(formation.get("soldier"));
        return soldiers != null && !soldiers.isEmpty();
    }

    static int clampBehemothPrepareIndex(int index) {
        return Math.max(1, Math.min(4, index));
    }

    /** 准备档 index → 秒（网页版 DailyBehemothService.minimumRemainingSeconds 同档位）。 */
    static int behemothPrepareSeconds(int index) {
        switch (clampBehemothPrepareIndex(index)) {
            case 1: return 320;
            case 2: return 620;
            case 3: return 1820;
            case 4: return 3620;
            default: return 320;
        }
    }

    /** 等级搜索顺序：max→min 倒序（BehemothAutomationService.searchLevels:1174-1180）。 */
    static List<Integer> behemothLevels(BehemothConfig cfg) {
        List<Integer> levels = new ArrayList<>();
        int min = Math.max(1, Math.min(cfg == null ? 1 : cfg.minLevel, 8));
        int max = Math.max(1, Math.min(cfg == null ? 8 : cfg.maxLevel, 8));
        if (cfg != null && cfg.forceMaxLevel) min = max;
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        for (int level = max; level >= min; level--) levels.add(level);
        return levels;
    }

    /** 体力道具数量：缺多少补多少（每颗+10，单次≤6；BehemothStaminaItemSupport.itemUseCount 同口径）。 */
    static int behemothStaminaItemCount(long deficit) {
        if (deficit <= 0L) return 0;
        long items = (deficit + BEHEMOTH_STAMINA_ITEM_RECOVERY - 1) / BEHEMOTH_STAMINA_ITEM_RECOVERY;
        return (int) Math.min(Math.min(items, BEHEMOTH_STAMINA_ITEM_MAX), Integer.MAX_VALUE);
    }

    /** 体力自然恢复到目标值还需秒数（BehemothAccountState.staminaRecoveryWaitSeconds:2205-2240 同口径）。 */
    static long behemothStaminaWaitSeconds(BehemothStamina stamina, long requiredStamina, long nowSec) {
        if (stamina == null || !stamina.hasCurrent) return 0L;
        long current = stamina.currentAt(nowSec);
        if (current >= requiredStamina) return 0L;
        if (stamina.max <= 0L || requiredStamina > stamina.max) return 0L;
        if (stamina.recover <= 0L || stamina.nextRecoverAt <= 0L || stamina.interval <= 0L) return 0L;
        long now = nowSec > 0L ? nowSec : System.currentTimeMillis() / 1000L;
        long missing = requiredStamina - current;
        long periods = (missing + stamina.recover - 1L) / stamina.recover;
        long nextRecovery = stamina.nextRecoverAt;
        if (now >= nextRecovery) {
            long completed = (now - nextRecovery) / stamina.interval;
            nextRecovery = nextRecovery + (completed + 1L) * stamina.interval;
        }
        long targetAt = nextRecovery + (periods - 1L) * stamina.interval;
        return Math.max(1L, targetAt - now);
    }

    /**
     * 下次唤醒时刻（epoch millis；0=不覆盖节奏）：
     * 今日次数已满 → 次日 00:05；体力不足 → 自然恢复到“保留+25”的时刻（≤1h 封顶）；
     * 刚发起成功 → 180s 后复查；其它情况交给节奏间隔。
     */
    static long behemothWakeAtMillis(long nowSec, BehemothConfig cfg, BehemothState state,
                                     BehemothStamina stamina, long requiredStamina, boolean launchedNow) {
        if (cfg == null || !cfg.launchEnabled) return 0L;
        if (state == null) return 0L;
        if (state.launchCount >= cfg.launchLimit) {
            return behemothNextDayWakeSec(nowSec, 300L) * 1000L;
        }
        if (stamina != null && stamina.hasCurrent
                && stamina.currentAt(nowSec) < requiredStamina) {
            long wait = behemothStaminaWaitSeconds(stamina, requiredStamina, nowSec);
            if (wait > 0L) return (nowSec + Math.min(wait, 3600L)) * 1000L;
        }
        if (launchedNow) return (nowSec + BEHEMOTH_LAUNCH_RECHECK_SEC) * 1000L;
        return 0L;
    }

    /** 北京时间下一个自然日 + offset 秒（如 00:05）。 */
    static long behemothNextDayWakeSec(long nowSec, long offsetSec) {
        try {
            java.time.ZonedDateTime now = java.time.Instant.ofEpochSecond(
                    nowSec > 0L ? nowSec : System.currentTimeMillis() / 1000L)
                    .atZone(Beijing.ZONE_ID);
            return now.toLocalDate().plusDays(1).atStartOfDay(Beijing.ZONE_ID).toEpochSecond()
                    + Math.max(0L, offsetSec);
        } catch (Throwable t) {
            return (nowSec > 0L ? nowSec : System.currentTimeMillis() / 1000L) + 3600L;
        }
    }

    /** 失败码分类（错误码原码保留；语义以网页版/审计为准，未知码不猜）。 */
    static String behemothCodeText(int code) {
        switch (code) {
            case CODE_BEHEMOTH_NO_TARGET:
                return "搜索无目标（码2872）";
            case CODE_BEHEMOTH_TARGET_TAKEN:
                return "目标已被抢先集结（码2851）";
            case CODE_BEHEMOTH_JOIN_ALREADY:
                return "已在目标集结中（码2844）";
            case CODE_BEHEMOTH_JOIN_INVALID:
                return "目标集结已失效（码2845）";
            case CODE_BEHEMOTH_JOIN_FULL:
                return "目标集结已满员（码2846）";
            case CODE_BEHEMOTH_STAMINA_SHORT:
                return "体力不足（码51）";
            case CODE_BEHEMOTH_ALLIANCE:
                return "联盟未加入/活动未开放（码1507，审计语义待真机复核）";
            case CODE_BEHEMOTH_LIMIT:
                return "医院容量/次数限制（码1600，审计语义待真机复核）";
            case CODE_BEHEMOTH_FORMATION_SHORT:
                return "编队兵力不足（码401）";
            case CODE_BEHEMOTH_FROSTFIRE_LOCK:
                return "燃霜矿区战区锁未释放（码8637）";
            default:
                return "";
        }
    }

    /** 任务参数（App FnParams 同源；全部有安全默认：发起默认关、零钻石）。 */
    static final class BehemothConfig {
        boolean autoJoinEnabled = true;
        boolean launchEnabled = false;
        int launchLimit = 1;
        int queueCount = 1;
        boolean joinEnabled = true;
        int joinLimit = 1;
        boolean manualJoinWithAutoJoin = false;
        int minLevel = 1;
        int maxLevel = 8;
        boolean forceMaxLevel = false;
        int prepareTimeIndex = 1;
        long staminaReserve = 50L;
        boolean autoUseStaminaItem = false;
        int autoJoinMarchLimit = 3;
        List<Long> autoJoinIds = java.util.Arrays.asList(1L, 4L, 3L);
        List<Long> heroIds = new ArrayList<>();
        int squadIndex = 1;
        // ---- 批次10 统一编队（缺省=旧行为） ----
        /** 原始 fnParams（JSONObject→Map 深转换；供 FormationResolver.resolve 使用）。 */
        final Map<String, Object> paramsMap = new LinkedHashMap<>();
        /** 是否显式配置了通用编队字段（formation_mode/formation_id/soldiers/ratio/total）。 */
        boolean formationConfigured;
        /** formation_library 原始 JSON（空=无库）。 */
        String formationLibraryJson = "";

        static BehemothConfig of(org.json.JSONObject params) {
            BehemothConfig cfg = new BehemothConfig();
            org.json.JSONObject p = params == null ? new org.json.JSONObject() : params;
            // App FnParams：开关可能放 sub:{auto_join/launch/join:bool}，也可能放顶层（两者都兼容）
            org.json.JSONObject sub = p.optJSONObject("sub");
            cfg.autoJoinEnabled = optSubBool(p, sub, "auto_join", "auto_join", true);
            cfg.launchEnabled = optSubBool(p, sub, "launch", "launch_enabled", false);
            cfg.launchLimit = clamp(p.optInt("launch_limit", 1), 1, 100);
            cfg.queueCount = clamp(p.optInt("queue_count", 1), 1, 6);
            cfg.joinEnabled = optSubBool(p, sub, "join", "join_enabled", true);
            cfg.joinLimit = clamp(p.optInt("join_limit", 1), 1, 6);
            cfg.manualJoinWithAutoJoin = optBool(p, "manual_join_with_auto_join", false);
            cfg.minLevel = clamp(p.optInt("min_level", 1), 1, 8);
            cfg.maxLevel = clamp(p.optInt("max_level", 8), 1, 8);
            cfg.forceMaxLevel = optBool(p, "force_max_level", false);
            cfg.prepareTimeIndex = clampBehemothPrepareIndex(p.optInt("prepare_time_index", 1));
            cfg.staminaReserve = clampLong(p.optLong("stamina_reserve", 50L), 0L, 500L);
            cfg.autoUseStaminaItem = optBool(p, "auto_use_stamina_item", false);
            cfg.autoJoinMarchLimit = clamp(p.optInt("auto_join_march_limit", 3), 1, 6);
            cfg.autoJoinIds = parseIds(p.optString("auto_join_ids", "1,4,3"),
                    java.util.Arrays.asList(1L, 4L, 3L));
            cfg.heroIds = parseIds(p.optString("hero_ids", ""), new ArrayList<Long>());
            if (cfg.heroIds.size() > 3) cfg.heroIds = new ArrayList<>(cfg.heroIds.subList(0, 3));
            cfg.squadIndex = clamp(p.optInt("squad_index", 1), 1, 6);
            // 批次10：统一编队参数（无这些键 → formationConfigured=false → 完全旧行为）
            cfg.paramsMap.putAll(ServerBearTasks.paramsToMap(p));
            cfg.formationConfigured = ServerBearTasks.hasGenericFormationConfig(cfg.paramsMap);
            cfg.formationLibraryJson = p.optString("formation_library", "");
            return cfg;
        }

        /** 队列上限 = min(配置 queueCount, 世界容量)（DailyBehemothService.effectiveQueueCount:701-711）。 */
        int effectiveQueueCount(int capacity) {
            if (capacity <= 0) return queueCount;
            return Math.max(1, Math.min(queueCount, capacity));
        }
    }

    /** 巨兽本地台账（srv:behemoth:{fpid}:{sid}:state，按北京时间自然日滚动）。 */
    static final class BehemothState {
        String day = "";
        int launchCount;
        long lastLaunchAtSec;
        int lastLaunchLevel = -1;
        int lastCode;
        int joinCount;
        long lastJoinMasterId;

        static BehemothState load(TaskContext tc) {
            BehemothState st = new BehemothState();
            try {
                String raw = tc.store.getString(BEHEMOTH_KEY_PREFIX + tc.fpid + ":" + tc.sid + ":state");
                if (raw == null || raw.isEmpty()) return st;
                org.json.JSONObject o = new org.json.JSONObject(raw);
                st.day = o.optString("day", "");
                st.launchCount = o.optInt("launchCount", 0);
                st.lastLaunchAtSec = o.optLong("lastLaunchAtSec", 0L);
                st.lastLaunchLevel = o.optInt("lastLaunchLevel", -1);
                st.lastCode = o.optInt("lastCode", 0);
                st.joinCount = o.optInt("joinCount", 0);
                st.lastJoinMasterId = o.optLong("lastJoinMasterId", 0L);
            } catch (Throwable ignored) {
            }
            return st;
        }

        void save(TaskContext tc) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("day", day);
                o.put("launchCount", launchCount);
                o.put("lastLaunchAtSec", lastLaunchAtSec);
                o.put("lastLaunchLevel", lastLaunchLevel);
                o.put("lastCode", lastCode);
                o.put("joinCount", joinCount);
                o.put("lastJoinMasterId", lastJoinMasterId);
                tc.store.putString(BEHEMOTH_KEY_PREFIX + tc.fpid + ":" + tc.sid + ":state", o.toString());
            } catch (Throwable ignored) {
            }
        }

        void rollDay(long nowSec) {
            String current = Beijing.fmt("yyyy-MM-dd").format(
                    new java.util.Date((nowSec > 0L ? nowSec : System.currentTimeMillis() / 1000L) * 1000L));
            if (current.equals(day)) return;
            day = current;
            launchCount = 0;
            lastLaunchAtSec = 0L;
            lastLaunchLevel = -1;
            lastCode = 0;
            joinCount = 0;
            lastJoinMasterId = 0L;
        }
    }

    /**
     * mod 文案：auto_join 保持既有口径（成功文案不变），新增 stamina / launch / join 三行
     * （批次8 可观测性：今日发起进度、体力、手工上车结果一眼可读）。
     */
    private static void behemothWriteMod(TaskContext tc, BehemothAutoJoin autoJoin, String staminaText,
                                         String launchText, String joinText,
                                         BehemothConfig cfg, BehemothState state) {
        try {
            String autoJoinLine;
            if (autoJoin != null && autoJoin.on) {
                autoJoinLine = "原生自动上车：已开启（巨兽/死神/巨熊）";
            } else if (autoJoin == null) {
                autoJoinLine = "原生自动上车：未知";
            } else if (autoJoin.text.startsWith("自动上车")) {
                autoJoinLine = autoJoin.text;   // 「自动上车已关闭（App 设置页可开）」保持原样
            } else {
                autoJoinLine = "原生自动上车：" + autoJoin.text;
            }
            tc.saveModText("behemoth", "auto_join", autoJoinLine);
            tc.saveModText("behemoth", "stamina", staminaText == null ? "" : staminaText);
            tc.saveModText("behemoth", "launch",
                    (launchText == null ? "" : launchText) + "（今日 " + state.launchCount
                            + "/" + cfg.launchLimit + "）");
            tc.saveModText("behemoth", "join", joinText == null ? "" : joinText);
        } catch (Throwable ignored) {
        }
    }

    /** 巨兽编队选择结果（批次10：统一编队 / 旧行为回退；payload=null 表示 SKIP）。 */
    static final class BehemothFormationChoice {
        final Map<String, Object> payload;
        final String label;
        final String reason;

        BehemothFormationChoice(Map<String, Object> payload, String label, String reason) {
            this.payload = payload;
            this.label = label == null ? "" : label;
            this.reason = reason == null ? "" : reason;
        }
    }

    /**
     * 选巨兽编队（批次10 统一编队接入，1:1 参考网页版 DailyBehemothConfig 编队字段 +
     * DailyBehemothService：编队不可用→SKIP）：
     * <ul>
     *   <li>显式编队参数（formation_mode/formation_id/soldiers/ratio/total）→ FormationResolver；
     *       库缺失/编队不存在/英雄无效/兵量超库存 → payload=null + 可读原因（SKIP，不静默回退）；</li>
     *   <li>无编队参数 → 旧行为（hero_ids→squad_index→队1，零行为变化）。</li>
     * </ul>
     */
    static BehemothFormationChoice chooseBehemothFormation(TaskContext tc, BehemothConfig cfg) {
        if (FormationResolver.isConfigured(cfg.paramsMap)) {
            FormationLibrary lib = FormationResolver.libraryFor(tc, "behemoth");
            FormationResolver.ResolveOutcome out = FormationResolver.resolveOutcome(
                    tc.engine, lib, cfg.paramsMap);
            if (!out.ready()) {
                return new BehemothFormationChoice(null, "统一编队", out.reason);
            }
            ResolvedFormation resolved = out.formation;
            Map<String, Object> payload = resolved.toFormationPayload();
            return new BehemothFormationChoice(payload, resolved.describe(),
                    payload == null ? "编队英雄/兵种为空" : "");
        }
        Map<String, Object> payload = behemothFormation(tc.engine, cfg);
        String label = cfg.heroIds.isEmpty()
                ? "squad_index(" + cfg.squadIndex + ")" : "hero_ids(" + cfg.heroIds + ")";
        return new BehemothFormationChoice(payload, label,
                behemothFormationReady(payload) ? "" : "空编队(无英雄/兵力)");
    }

    /** 旧编队：hero_ids 优先（≤3）→ 客户端队 squad_index → 默认队1（与既有 Exec.formation 行为一致）。 */
    static Map<String, Object> behemothFormation(WjdrEngine engine, BehemothConfig cfg) {
        if (cfg != null && !cfg.heroIds.isEmpty()) {
            Map<String, Object> byHeroIds = Exec.formationWithHeroes(engine, cfg.heroIds);
            if (behemothFormationReady(byHeroIds)) return byHeroIds;
        }
        int squad = cfg == null ? 1 : cfg.squadIndex;
        Map<String, Object> bySquad = Exec.formation(engine, squad);
        if (behemothFormationReady(bySquad)) return bySquad;
        return squad == 1 ? bySquad : Exec.formation(engine, 1);
    }

    private static List<Long> parseIds(String raw, List<Long> fallback) {
        List<Long> out = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split("[,;\\s]+")) {
                try {
                    long id = Long.parseLong(part.trim());
                    if (id > 0L && !out.contains(id)) out.add(id);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out.isEmpty() ? fallback : out;
    }

    /** 子项开关优先（App sub:{key:bool}），否则读顶层 key（兼容旧参数/RPC 直传）。 */
    private static boolean optSubBool(org.json.JSONObject p, org.json.JSONObject sub,
                                      String subKey, String topKey, boolean def) {
        if (sub != null && sub.has(subKey)) {
            Object v = sub.opt(subKey);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).longValue() != 0L;
        }
        return optBool(p, topKey, def);
    }

    private static boolean optBool(org.json.JSONObject p, String key, boolean def) {
        if (p == null || !p.has(key)) return def;
        Object v = p.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0L;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    static TaskOutcome bear(TaskContext tc) throws Exception {
        try {
            CaptureTasks.bear(tc.engine, tc.store, tc.fpid, tc.sid);
            org.json.JSONObject mod = tc.modState("bear");
            String trap = mod.optString("trap", "");
            if (trap.isEmpty()) {
                return new TaskOutcome("bear", "巨熊行动", TaskOutcome.Category.DEGRADED, "陷阱状态未取到");
            }
            return new TaskOutcome("bear", "巨熊行动", TaskOutcome.Category.CLAIMED, trap);
        } catch (Exception e) {
            return classifyException(e, "巨熊行动");
        }
    }

    // ---------------- 公共判定（与 App DailyTasks 一致） ----------------

    static boolean isDegraded(GameMessage msg) {
        if (msg == null || msg.getContent() == null) return true;
        return msg.getContent().containsKey("_unknown_proto_tag")
                || msg.getContent().containsKey("_unmapped_session");
    }

    static TaskOutcome classifySimple(GameMessage msg, String proto, String name,
                                      String[] evidenceKeys, String claimedMsg) {
        if (isDegraded(msg)) {
            return new TaskOutcome(proto, name, TaskOutcome.Category.DEGRADED, "响应异常（协议可能变更）");
        }
        Object code = msg.getContent().get("code");
        if (code != null && toInt(code) != 0) {
            return classifyServerCode(toInt(code), name);
        }
        for (String k : evidenceKeys) {
            if (msg.getContent().containsKey(k)) {
                TaskOutcome r = new TaskOutcome(proto, name, TaskOutcome.Category.CLAIMED, claimedMsg);
                r.rewards = com.carlos.vault.daily.RewardText.fromContent(msg.getContent());
                return r;
            }
        }
        return new TaskOutcome(proto, name, TaskOutcome.Category.DEGRADED, "响应缺少预期字段，未确认到账");
    }

    static String describeSupplyCode(int code, String which) {
        switch (code) {
            case 6151: return which + "当前不可领取（冷却中）";
            case 6158: return which + "本窗口已领取";
            case 6152: return which + "补给不可领取";
            case 1507: return which + "今日已捐献";
            case 40032: return which + "今日已领取";
            case 40034: return which + "本周已领取";
            default: return which + "错误码 " + code;
        }
    }

    static TaskOutcome classifyServerCode(int code, String name) {
        switch (code) {
            case 2:
                return new TaskOutcome("supply", name, TaskOutcome.Category.SKIPPED, "尚未到可领时间（冷却中）");
            case 6151:
                return new TaskOutcome("supply", name, TaskOutcome.Category.SKIPPED, "当前不可领取，等待下一窗口");
            case 6158:
                return new TaskOutcome("supply", name, TaskOutcome.Category.SKIPPED, "本窗口已领过");
            case 6152:
                return new TaskOutcome("supply", name, TaskOutcome.Category.SKIPPED, "体力补给不可领取");
            case 7501:
                return new TaskOutcome(name, name, TaskOutcome.Category.SKIPPED, "今日已领取或暂无可领奖励");
            case 4303:
                return new TaskOutcome(name, name, TaskOutcome.Category.SKIPPED, "暂无可领取的任务奖励");
            case 4302:
                // 实测：账号已完成/已领过时返回 4302。旧逻辑落到 ERROR，界面误报异常。
                return new TaskOutcome(name, name, TaskOutcome.Category.SKIPPED,
                        "任务奖励已领取或暂无可领（码4302）");
            case 4104:
                // req_begin_research 实测返回：目标科技已满级/当前路线不可研究。
                return new TaskOutcome(name, name, TaskOutcome.Category.SKIPPED,
                        "科技目标已满级或当前不可研究（码4104）");
            case 4004:
                return new TaskOutcome("adventure", name, TaskOutcome.Category.SKIPPED, "领取间隔未到");
            case 4003:
                return new TaskOutcome("adventure", name, TaskOutcome.Category.SKIPPED, "功能尚未解锁");
            case 21:
                return new TaskOutcome(name, name, TaskOutcome.Category.SKIPPED, "今日已领取或次数已用");
            case 7401: case 7404:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED, "活动未开启或已结束");
            case 40611: case 61:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED, "今日已领取");
            case 4502:
                // 网页版 DailyCommanderGiftService.java:41,188-192：4502 = 今日统帅礼包已领。
                return new TaskOutcome("commander_gift", name, TaskOutcome.Category.SKIPPED,
                        "今日统帅每日礼包已领取（码4502）");
            case 4503:
                // 网页版 DailyCommanderGiftService.java:41,188-192：4503 = VIP 未激活（旧实现落 ERROR）。
                return new TaskOutcome("commander_gift", name, TaskOutcome.Category.SKIPPED,
                        "统帅特权未激活（码4503）");
            case 40032:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED, "今日已领取");
            case 40034:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED, "本周已领取");
            case 40610: case 40612:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED,
                        "远征宝藏暂不可领（码" + code + "）");
            case 7602:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED,
                        "每日特惠礼包今日已领取");
            case 7301: case 7303: case 7306:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED,
                        "战令未达到可领档位（码" + code + "）");
            case 7302: case 7307:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED,
                        "战令该档已领或批量失败（码" + code + "）");
            case 8201: case 8202:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED,
                        "卡BP未达到可领档位（码" + code + "）");
            case 8203:
                return new TaskOutcome("activity", name, TaskOutcome.Category.SKIPPED,
                        "卡BP该档已领完");
            case 1507:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED,
                        "今日已捐献，奖励已领");
            case 1528:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED,
                        "免费捐献次数已用尽");
            case 1545:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED,
                        "资源不足，暂不可捐献");
            case 1510: case 1511:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED,
                        "联盟状态已变化，等待重新同步");
            case 1570: case 1571: case 1574: case 1576: case 1577: case 1579: case 1580:
            case 1581:
                return new TaskOutcome("alliance_donate", name, TaskOutcome.Category.SKIPPED,
                        "该联盟科技暂不可捐献（码" + code + "）");
            case 1201:
                return new TaskOutcome("mail", name, TaskOutcome.Category.SKIPPED, "邮件不存在（已被领）");
            case 1202:
                return new TaskOutcome("mail", name, TaskOutcome.Category.SKIPPED, "附件已领取");
            case 1204: case 1215:
                return new TaskOutcome("mail", name, TaskOutcome.Category.SKIPPED, "邮件状态不允许领取");
            case 40271:
                return new TaskOutcome(name, name, TaskOutcome.Category.SESSION_CLOSED, "被服务端强制下线");
            default:
                return new TaskOutcome(name, name, TaskOutcome.Category.ERROR, "服务端返回错误码 " + code);
        }
    }

    /**
     * cause 链最大追溯层数（P1-A）：GameProtocolClient 把协议异常包装为
     * "协议调用失败: &lt;proto&gt;"，真实原因在 cause（"连接已关闭"/"连接游戏服务器失败"/
     * "等待协议响应超时"/"发送协议数据失败"…）。只看顶层 message 会把真实断线误判 ERROR。
     * 上限同时防止自环/互环 cause 造成死循环。
     */
    private static final int MAX_CAUSE_DEPTH = 5;

    /**
     * 是否会话中断（P1-A 二轮，ZCode 复验 §5.4/§6）：文案命中 SESSION_CLOSED_MARKS。
     * 供 {@link Exec.Multi} 聚合路径复用——子项聚合不经过 classifyException，
     * 只能拿到异常 message 文案，故提供 String 版判定入口。
     */
    public static boolean isSessionClosed(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String mark : SESSION_CLOSED_MARKS) {
            if (text.contains(mark)) return true;
        }
        return false;
    }

    /**
     * 异常链版会话中断判定：逐层查文案 + 查网络层异常类型
     * （ConnectException/SocketException/SocketTimeoutException/EOFException/ClosedChannelException）。
     * 覆盖 connect() 路径 "连接游戏服务器失败" + cause "Connection refused"（无关键字文案）
     * 与一切 cause 携带网络异常的包装形态。
     */
    public static boolean isSessionClosed(Throwable e) {
        if (e == null) return false;
        Throwable cur = e;
        for (int hop = 0; cur != null && hop <= MAX_CAUSE_DEPTH; hop++) {
            if (isSessionClosed(String.valueOf(cur.getMessage()))) return true;
            for (Class<?> t : SESSION_CLOSED_TYPES) {
                if (t.isInstance(cur)) return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    public static TaskOutcome classifyException(Throwable e, String name) {
        // P1-A（ZCode 真机回归实证）：杀出口后整轮 19 个任务全落 ERROR(st=3)、summary 无
        // "会话中断"、srv:lastrun 被写 → RoundScheduler 当日不再自动重试，与"断线可重试"相反。
        // 根因 = 只匹配顶层 message，而协议异常的断线关键字在 cause 链上。
        // round-1：cause 链拼接匹配；round-2：判定统一走 isSessionClosed（文案 + 异常类型），
        // 覆盖 connect() 拒绝形态。brief 仅用于展示截断，不参与判定。
        String chain = causeChain(e);
        if (isSessionClosed(e)) {
            return new TaskOutcome(name, name, TaskOutcome.Category.SESSION_CLOSED,
                    "会话中断（可重试）：" + brief(chain));
        }
        return new TaskOutcome(name, name, TaskOutcome.Category.ERROR,
                brief(e.getClass().getSimpleName() + ": " + chain));
    }

    /** 沿 cause 链拼接各级 message（"顶层 cause=… cause=…"；超出层数的截断处记 …）。 */
    private static String causeChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        for (int hop = 0; cur != null && hop <= MAX_CAUSE_DEPTH; hop++) {
            if (hop > 0) sb.append(" cause=");
            sb.append(String.valueOf(cur.getMessage()));
            cur = cur.getCause();
        }
        if (cur != null) sb.append(" cause=…");
        return sb.toString();
    }

    static String brief(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    static String descRewards(Object rewards) {
        return com.carlos.vault.daily.RewardText.describe(rewards);
    }

    static int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object v) {
        if (!(v instanceof List)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Object> listOfRaw(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private ServerTasks() {
    }
}
