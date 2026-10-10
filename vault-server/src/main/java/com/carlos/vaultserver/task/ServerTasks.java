package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.CaptureTasks;

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

    /** 会话中断关键字（与 App DailyTasks.SESSION_CLOSED_MARKS 一致）。 */
    private static final String[] SESSION_CLOSED_MARKS = {
            "不在线", "会话已", "连接已关闭", "断开", "强制下线", "连接失败", "发送协议数据失败",
            "等待协议响应超时", "Socket", "socket", "EOF"};

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
        l.add(new TaskDef("adventure", "挂机奖励", true, ServerTasks::adventureReward));
        l.add(new TaskDef("activity_rewards", "活动奖励", true, ServerTasks::activityRewards));
        l.add(new TaskDef("commander_gift", "统帅礼包", true, ServerTasks::commanderGift));
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
        l.add(new TaskDef("battle_shield", "战斗护盾", true, ServerTasks::freeShield));
        // ===== 联盟 =====
        l.add(new TaskDef("alliance_chest", "联盟宝箱", true, ServerTasks::allianceChest));
        l.add(new TaskDef("alliance_donate", "联盟捐献", true, ServerTasks::allianceDonate));
        l.add(new TaskDef("alliance_mobilization", "联盟动员", true, ServerShopLeagueTasks::mobilization));
        l.add(new TaskDef("alliance_showdown", "争霸赛", false, ServerShopLeagueTasks::showdown));
        l.add(new TaskDef("alliance_help", "联盟互助", true, ServerShopLeagueTasks::allianceHelp));
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

    static TaskOutcome loginRewards(TaskContext tc) throws Exception {
        try {
            GameMessage rsp = tc.engine.request("req_fourteen_sign_get_all_reward", null);
            // 服务端无签到进度快照，明细只取响应自带字段（推不出留空，App 端目录兜底）
            return classifySimple(rsp, "req_fourteen_sign_get_all_reward", "登录好礼",
                    new String[]{"code"}, "已一键领取全部可领签到奖励");
        } catch (Exception e) {
            return classifyException(e, "登录好礼");
        }
    }

    static TaskOutcome mailRewards(TaskContext tc) throws Exception {
        try {
            String board = tc.strategy("mail", "read", "all");
            int[] types;
            if (board.contains(",")) {
                String[] parts = board.split(",");
                List<Integer> tl = new ArrayList<>();
                for (String part : parts) {
                    try {
                        int v = Integer.parseInt(part.trim());
                        if (v > 0) tl.add(v);
                    } catch (Throwable ignored) {
                    }
                }
                if (tl.isEmpty()) tl.add(10);
                types = new int[tl.size()];
                for (int i = 0; i < tl.size(); i++) types[i] = tl.get(i);
            } else {
                switch (board) {
                    case "sys": types = new int[]{10}; break;
                    case "sys_ally": types = new int[]{10, 20, 100, 120, 170}; break;
                    case "no_battle": types = new int[]{10, 20, 40, 50}; break;
                    default: types = new int[]{10, 20, 30, 40, 50}; break;
                }
            }
            tc.engine.request("req_mail_type_info", null);
            int sections = 0;
            List<String> pendingIds = new ArrayList<>();
            StringBuilder degradeNote = new StringBuilder();
            for (int type : types) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("first_type", type);
                args.put("beginidx", 0);
                args.put("endidx", 50);
                GameMessage cover = tc.engine.request("req_mail_cover_list", args);
                if (isDegraded(cover)) {
                    degradeNote.append("板块").append(type).append("响应异常; ");
                    continue;
                }
                sections++;
                List<Map<String, Object>> covers = listOfMaps(cover.getContent().get("coverlist"));
                if (covers == null) continue;
                for (Map<String, Object> m : covers) {
                    Object attach = m.get("attach_statue");
                    if (attach == null || toInt(attach) != 1) continue;
                    Object idNew = m.get("mailid_new");
                    if (idNew != null && !String.valueOf(idNew).isEmpty()) {
                        pendingIds.add(String.valueOf(idNew));
                    }
                }
                if (pendingIds.size() >= 100) break;
            }
            if (pendingIds.isEmpty()) {
                if (degradeNote.length() > 0) {
                    return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.DEGRADED,
                            "部分板块协议异常：" + degradeNote);
                }
                return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.SKIPPED,
                        sections == 0 ? "邮箱板块未开放或无数据" : "没有可领取的邮件附件");
            }
            Map<String, Object> claimArgs = new LinkedHashMap<>();
            claimArgs.put("first_type", 10);
            claimArgs.put("mid_list_new", pendingIds);
            GameMessage claim = tc.engine.request("req_batch_mail_read", claimArgs);
            if (isDegraded(claim)) {
                return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.DEGRADED,
                        "批量领取响应异常（协议可能变更）");
            }
            Object code = claim.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                return classifyServerCode(toInt(code), "邮件奖励");
            }
            List<Object> readList = listOfRaw(claim.getContent().get("readlist_new"));
            int confirmed = readList == null ? pendingIds.size() : readList.size();
            tc.saveModText("mail", "read", "上轮领取 " + confirmed + " 封 · 候选 " + pendingIds.size());
            return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.CLAIMED,
                    "领取邮件 " + confirmed + " 封（候选 " + pendingIds.size() + "）");
        } catch (Exception e) {
            return classifyException(e, "邮件奖励");
        }
    }

    static TaskOutcome supplyRewards(TaskContext tc) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean anyDegraded = false;
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
            String msg = sb.length() == 0 ? "无结果" : sb.substring(0, sb.length() - 2);
            boolean claimed = msg.contains("已领取");
            TaskOutcome sr = new TaskOutcome("supply", "仓库补给",
                    anyDegraded ? TaskOutcome.Category.DEGRADED
                            : (claimed ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED),
                    msg);
            sr.subResults.putAll(subs);
            if (rewardParts.length() > 0) {
                sr.rewards = rewardParts.substring(0, rewardParts.length() - 1);
            }
            return sr;
        } catch (Exception e) {
            return classifyException(e, "仓库补给");
        }
    }

    static TaskOutcome dailyMission(TaskContext tc) throws Exception {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            List<Integer> ids = new ArrayList<>();
            for (int id : DAILY_QUEST_IDS) ids.add(id);
            args.put("quest_ids", ids);
            GameMessage rsp = tc.engine.request("req_get_all_dailyquest_award", args);
            if (isDegraded(rsp)) {
                return new TaskOutcome("daily_mission", "每日任务奖励", TaskOutcome.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                return classifyServerCode(toInt(code), "每日任务奖励");
            }
            boolean hasAward = rsp.getContent().containsKey("awards")
                    || rsp.getContent().containsKey("rewards")
                    || rsp.getContent().containsKey("quest_ids");
            if (hasAward) {
                TaskOutcome r = new TaskOutcome("daily_mission", "每日任务奖励", TaskOutcome.Category.CLAIMED,
                        "已完成任务奖励批量领取（未完成的任务由服务端自动跳过）");
                r.rewards = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                return r;
            }
            return new TaskOutcome("daily_mission", "每日任务奖励", TaskOutcome.Category.DEGRADED,
                    "响应无奖励字段，未确认到账（不视为成功）");
        } catch (Exception e) {
            return classifyException(e, "每日任务奖励");
        }
    }

    static TaskOutcome adventureReward(TaskContext tc) throws Exception {
        try {
            GameMessage view = tc.engine.request("req_open_hang_up_reward_view", null);
            if (!isDegraded(view)) {
                try {
                    Object end = view.getContent().get("endtime");
                    if (end == null) end = view.getContent().get("end_time");
                    if (end instanceof Number) {
                        long ts = ((Number) end).longValue();
                        if (ts > 1_700_000_000L && ts < 2_000_000_000L) {
                            tc.saveModText("adventure", "hang_up", "下次可领 " + com.carlos.vaultserver.Beijing.fmt("HH:mm:ss")
                                    .format(new java.util.Date(ts * 1000)));
                            long minSec = 0;
                            try {
                                minSec = Long.parseLong(tc.strategy("adventure", "hang_up", "0").trim());
                            } catch (Throwable ignored) {
                            }
                            long leftMin = (ts - System.currentTimeMillis() / 1000L) / 60L;
                            if (minSec / 60L > 0 && leftMin < minSec / 60L) {
                                return new TaskOutcome("adventure", "探险挂机奖励",
                                        TaskOutcome.Category.SKIPPED,
                                        "还差 " + Math.max(0, leftMin) + " 分钟到量，按阈值跳过");
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                Object code = view.getContent().get("code");
                if (code != null && toInt(code) != 0) {
                    TaskOutcome r = classifyServerCode(toInt(code), "探险挂机");
                    if (toInt(code) == 4004 || toInt(code) == 4003) {
                        return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                                "挂机奖励尚未解锁或间隔未到");
                    }
                    return r;
                }
            }
            GameMessage claim = tc.engine.request("req_get_hang_up_reward", null);
            if (isDegraded(claim)) {
                return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.DEGRADED,
                        "领取响应异常（协议可能变更）");
            }
            Object code = claim.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                if (toInt(code) == 4004) {
                    return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                            "领取间隔未到（约10分钟一领）");
                }
                if (toInt(code) == 4003) {
                    return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                            "探险挂机尚未解锁（需通关首个探险关卡）");
                }
                return classifyServerCode(toInt(code), "探险挂机");
            }
            if (claim.getContent().containsKey("rewards")
                    || claim.getContent().containsKey("reward")) {
                String d = descRewards(claim.getContent().get("rewards") != null
                        ? claim.getContent().get("rewards") : claim.getContent().get("reward"));
                TaskOutcome r = new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.CLAIMED,
                        "挂机奖励已领取" + d);
                r.rewards = d;
                return r;
            }
            return new TaskOutcome("adventure", "探险挂机奖励", TaskOutcome.Category.SKIPPED,
                    "当前暂无可领取的挂机奖励");
        } catch (Exception e) {
            return classifyException(e, "探险挂机");
        }
    }

    static TaskOutcome activityRewards(TaskContext tc) throws Exception {
        try {
            tc.engine.collectNotifies(2500);
            int ok = 0, skip = 0, fail = 0;
            StringBuilder rewardParts = new StringBuilder();   // 每个活动的领取明细（与 App 一致）
            String[][] noArg = {
                    {"月卡每日奖励", "req_month_card_get_daily_reaward"},
                    {"VIP每日经验", "req_vip_get_daily_exp"},
                    {"统帅每日礼包", "req_vip_get_daily_free_gift"},
                    {"每日特惠礼包", "req_daily_bundle_get_free_gift"},
            };
            for (String[] na : noArg) {
                int[] c = tryClaim(tc, na[0], na[1], null, rewardParts);
                ok += c[0]; skip += c[1]; fail += c[3];
            }
            String[][] byId = {
                    {"失落宝藏", "req_lost_treasure_get_daily_reward", "notify_lost_treasure_info"},
                    {"远征宝藏", "req_daily_claim_free_reward", "notify_daily_free_reward_info"},
                    {"许愿屋", "req_hero_turntable_get_free_gift", "notify_hero_turntable_info"},
                    {"米娅许愿屋", "req_fortune_card_get_daily_free_gift", "notify_fortune_card_info"},
                    {"无限宝箱", "req_infinite_box_get_reward", "notify_infinite_box_info"},
                    {"自选礼包", "req_optional_gift_pack_get_free_box", "notify_optional_gift_pack_info"},
                    {"自选箱", "req_customize_item_get_free_box", "notify_customize_item_info"},
                    {"匠人宝库", "req_multiple_choice_pack_get_free_box", "notify_multiple_choice_pack_info"},
                    {"钓鱼免费礼", "req_fishing_get_free_gift", "notify_fishing_info"},
            };
            for (String[] bi : byId) {
                Map<String, Object> notify = tc.engine.notifyContent(bi[2]);
                Long id = WjdrEngine.extractId(notify);
                if (id == null) {
                    skip++;
                    continue;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("id", id);
                int[] c = tryClaim(tc, bi[0], bi[1], args, rewardParts);
                ok += c[0]; skip += c[1]; fail += c[3];
            }
            Long bpId = WjdrEngine.extractId(tc.engine.notifyContent("notify_battle_pass_info"));
            if (bpId != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("id", bpId);
                int[] c = tryClaim(tc, "英雄集结", "req_battle_pass_collect_all_reward", args, rewardParts);
                ok += c[0]; skip += c[1]; fail += c[3];
            } else {
                skip++;
            }
            StringBuilder note = new StringBuilder();
            note.append("领取").append(ok).append("/跳过").append(skip);
            if (fail > 0) note.append("/失败").append(fail);
            TaskOutcome.Category cat = fail > 0 ? TaskOutcome.Category.ERROR
                    : (ok > 0 ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED);
            TaskOutcome r = new TaskOutcome("activity_rewards", "活动奖励合集(17种)", cat,
                    note + "（未开放的活动自动跳过）");
            if (rewardParts.length() > 0) {
                r.rewards = rewardParts.substring(0, rewardParts.length() - 1);
            }
            return r;
        } catch (Exception e) {
            return classifyException(e, "活动奖励合集");
        }
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
                if (c == 7401 || c == 7404 || c == 40611 || c == 61 || c == 7501 || c == 4303
                        || c == 21 || c == 40502 || c == 40503 || c == 11724 || c == 11728 || c == 2926) {
                    return new int[]{0, 1, 0, 0};
                }
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

    static TaskOutcome allianceChest(TaskContext tc) throws Exception {
        try {
            GameMessage rsp = tc.engine.request("req_alliance_receive_chest", null);
            if (isDegraded(rsp)) {
                return new TaskOutcome("alliance_chest", "联盟宝箱", TaskOutcome.Category.DEGRADED,
                        "响应异常（可能需要宝箱列表参数）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 4303 || c == 7501 || c == 40611) {
                    return new TaskOutcome("alliance_chest", "联盟宝箱", TaskOutcome.Category.SKIPPED,
                            "暂无可领取的宝箱");
                }
                return classifyServerCode(c, "联盟宝箱");
            }
            TaskOutcome r = new TaskOutcome("alliance_chest", "联盟宝箱", TaskOutcome.Category.CLAIMED,
                    "联盟宝箱已批量领取" + descRewards(rsp.getContent().get("rewards")));
            r.rewards = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
            return r;
        } catch (Exception e) {
            return classifyException(e, "联盟宝箱");
        }
    }

    static TaskOutcome allianceDonate(TaskContext tc) throws Exception {
        try {
            StringBuilder sb = new StringBuilder();
            StringBuilder rewardParts = new StringBuilder();
            int ok = 0;
            String[][] steps = {
                    {"免费科技捐献", "req_alliance_science_donate"},
                    {"每日发展奖励", "req_alliance_dev_get_daily"},
                    {"每周发展奖励", "req_alliance_dev_get_week"},
                    {"联盟排名奖励", "req_alliance_rank_get_rewards"},
            };
            for (String[] st : steps) {
                try {
                    GameMessage rsp = tc.engine.request(st[1], null);
                    if (isDegraded(rsp)) {
                        sb.append(st[0]).append(":响应异常; ");
                        continue;
                    }
                    Object code = rsp.getContent().get("code");
                    if (code != null && toInt(code) != 0) {
                        sb.append(st[0]).append(describeSupplyCode(toInt(code), ":")).append("; ");
                        continue;
                    }
                    ok++;
                    sb.append(st[0]).append("✓ ");
                    String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                    if (!d.isEmpty()) rewardParts.append(st[0]).append("：").append(d).append("；");
                } catch (Exception ex) {
                    TaskOutcome r = classifyException(ex, st[0]);
                    if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                    sb.append(st[0]).append("未完成; ");
                }
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

    static TaskOutcome freeShield(TaskContext tc) throws Exception {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("free_id", 1L);
            GameMessage rsp = tc.engine.request("req_city_item_free_use", args);
            if (isDegraded(rsp)) {
                return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 6151 || c == 6158 || c == 7501 || c == 4303) {
                    return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.SKIPPED,
                            "今日已使用或不可用");
                }
                return classifyServerCode(c, "战斗护盾");
            }
            return new TaskOutcome("battle_shield", "战斗护盾", TaskOutcome.Category.CLAIMED,
                    "8小时免费护盾已开启（被袭击时生效）");
        } catch (Exception e) {
            return classifyException(e, "战斗护盾");
        }
    }

    static TaskOutcome commanderGift(TaskContext tc) throws Exception {
        try {
            GameMessage rsp = tc.engine.request("req_vip_get_daily_free_gift", null);
            return classifySimple(rsp, "commander_gift", "统帅礼包",
                    new String[]{"code", "rewards"}, "当前统帅等级每日免费礼包已领取");
        } catch (Exception e) {
            return classifyException(e, "统帅礼包");
        }
    }

    static TaskOutcome behemoth(TaskContext tc) throws Exception {
        try {
            if (!tc.subOn("behemoth", "auto_join", true)) {
                return new TaskOutcome("behemoth", "巨兽狩猎", TaskOutcome.Category.SKIPPED,
                        "自动上车已关闭（App 设置页可开）");
            }
            tc.engine.request("req_alliance_activity_auto_join_info",
                    args("auto_mass_id_list", java.util.Arrays.asList(1L, 4L, 3L)));
            GameMessage open = tc.engine.request("req_alliance_activity_auto_join_open",
                    args("auto_mass_id_list", java.util.Arrays.asList(1L, 4L, 3L), "march_limit", 3L));
            if (!isDegraded(open)) {
                Object code = open.getContent().get("code");
                if (code == null || toInt(code) == 0) {
                    tc.saveModText("behemoth", "auto_join", "原生自动上车：已开启（巨兽/死神/巨熊）");
                    return new TaskOutcome("behemoth", "巨兽狩猎", TaskOutcome.Category.CLAIMED,
                            "自动上车已开启(巨兽/死神/巨熊)");
                }
                return new TaskOutcome("behemoth", "巨兽狩猎", TaskOutcome.Category.SKIPPED,
                        "开启码" + toInt(code));
            }
            return new TaskOutcome("behemoth", "巨兽狩猎", TaskOutcome.Category.DEGRADED,
                    "响应异常（协议可能变更）");
        } catch (Exception e) {
            return classifyException(e, "巨兽狩猎");
        }
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

    static TaskOutcome classifyException(Throwable e, String name) {
        String msg = String.valueOf(e.getMessage());
        for (String mark : SESSION_CLOSED_MARKS) {
            if (msg.contains(mark)) {
                return new TaskOutcome(name, name, TaskOutcome.Category.SESSION_CLOSED,
                        "会话中断（可重试）：" + brief(msg));
            }
        }
        return new TaskOutcome(name, name, TaskOutcome.Category.ERROR,
                brief(e.getClass().getSimpleName() + ": " + msg));
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
