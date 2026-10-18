package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.CaptureTasks;
import com.carlos.vaultserver.FileLog;

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

    static TaskOutcome loginRewards(TaskContext tc) throws Exception {
        try {
            // 三轨（与 App DailyTasks.loginRewards 2026-10-12 版一致）：14天双轨 / 七天 / 21天，
            // 各子开关独立门控；聚合口径 claimed>0→CLAIMED，否则有降级→DEGRADED，否则 SKIPPED
            if (!tc.subOn("login_rewards", "sign", true)) {
                return new TaskOutcome("login_rewards", "登录好礼", TaskOutcome.Category.SKIPPED,
                        "子开关「7天签到」已关闭");
            }
            StringBuilder msg = new StringBuilder();
            StringBuilder rewards = new StringBuilder();
            int claimed = 0, degraded = 0;

            // ① 登录好礼（十四天双轨）
            GameMessage rsp = tc.engine.request("req_fourteen_sign_get_all_reward", null);
            // code=0 不是到账证据；必须有奖励字段才算 CLAIMED（防“显示已完成但没领到”）。
            TaskOutcome r = classifySimple(rsp, "login_rewards", "登录好礼",
                    new String[]{"awards", "rewards", "items", "award_success_list", "gain"},
                    "已一键领取全部可领签到奖励");
            if (r.category == TaskOutcome.Category.CLAIMED) {
                claimed++;
                msg.append("登录好礼已领");
                if (r.rewards != null && !r.rewards.isEmpty()) {
                    msg.append("（").append(r.rewards).append("）");
                    rewards.append(r.rewards).append("；");
                }
            } else if (r.category == TaskOutcome.Category.DEGRADED) {
                degraded++;
                msg.append("登录好礼响应异常");
            } else {
                msg.append("登录好礼无可领");
            }

            // ② 七天签到：award_success_list 非空才算真领到（防假成功）
            if (tc.subOn("login_rewards", "seven", true)) {
                GameMessage seven = tc.engine.request("req_seven_sign_one_click_award", null);
                if (isDegraded(seven)) {
                    degraded++;
                    msg.append("; 七天签到响应异常");
                } else if (Exec.ok(seven)) {
                    List<Object> okList = listOfRaw(seven.getContent().get("award_success_list"));
                    if (okList != null && !okList.isEmpty()) {
                        claimed++;
                        msg.append("; 七天签到×").append(okList.size());
                        rewards.append("七天签到奖励；");
                    } else {
                        msg.append("; 七天签到无可领");
                    }
                } else {
                    msg.append("; 七天签到已全领或不可用");
                }
            } else {
                msg.append("; 七天签到已关");
            }

            // ③ 21天签到免费档（付费档 has_pay 才有，免费档空参全领）
            if (tc.subOn("login_rewards", "day21", true)) {
                GameMessage d21 = tc.engine.request("req_sign_21day_get_all_reward", null);
                if (isDegraded(d21)) {
                    degraded++;
                    msg.append("; 21天签到响应异常");
                } else if (Exec.ok(d21)) {
                    List<Object> rw = listOfRaw(d21.getContent().get("rewards"));
                    if (rw != null && !rw.isEmpty()) {
                        claimed++;
                        msg.append("; 21天签到已领");
                        rewards.append("21天签到奖励；");
                    } else {
                        msg.append("; 21天签到无可领");
                    }
                } else {
                    msg.append("; 21天签到已全领或不可用");
                }
            } else {
                msg.append("; 21天签到已关");
            }

            TaskOutcome out = new TaskOutcome("login_rewards", "登录好礼",
                    claimed > 0 ? TaskOutcome.Category.CLAIMED
                            : degraded > 0 ? TaskOutcome.Category.DEGRADED
                            : TaskOutcome.Category.SKIPPED,
                    msg.toString());
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
            // 子开关门控（与 App 2026-10-12 版一致）：keep_unread=仅领有附件邮件（默认开）；
            // 关=无附件公告一并批量已读
            boolean keepUnread = tc.subOn("mail", "keep_unread", true);
            int sections = 0;
            List<String> pendingIds = new ArrayList<>();
            List<String> noAttachIds = new ArrayList<>();   // 无附件邮件（仅 keep_unread=false 时批量已读）
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
                    Object idNew = m.get("mailid_new");
                    if (idNew == null || String.valueOf(idNew).isEmpty()) continue;
                    Object attach = m.get("attach_statue");
                    if (attach != null && toInt(attach) == 1) {
                        pendingIds.add(String.valueOf(idNew));
                    } else if (!keepUnread) {
                        noAttachIds.add(String.valueOf(idNew));
                    }
                }
                if (pendingIds.size() >= 100) break;
            }
            if (pendingIds.isEmpty()) {
                // 关掉「仅领有附件邮件」：没有可领附件也把无附件公告批量已读
                if (!keepUnread && !noAttachIds.isEmpty()) {
                    try {
                        Map<String, Object> readArgs = new LinkedHashMap<>();
                        readArgs.put("first_type", 10);
                        readArgs.put("mid_list_new", noAttachIds);
                        GameMessage readRsp = tc.engine.request("req_batch_mail_read", readArgs);
                        if (!isDegraded(readRsp)) {
                            Object rc = readRsp.getContent().get("code");
                            if (rc == null || toInt(rc) == 0) {
                                List<Object> rl = listOfRaw(readRsp.getContent().get("readlist_new"));
                                int n = rl == null ? noAttachIds.size() : rl.size();
                                return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.CLAIMED,
                                        "无附件可领，公告已读 " + n + " 封");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
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
            // keep_unread=false：无附件公告一并已读（有附件邮件上面已领）
            int readNoAttach = 0;
            if (!keepUnread && !noAttachIds.isEmpty()) {
                try {
                    Map<String, Object> readArgs = new LinkedHashMap<>();
                    readArgs.put("first_type", 10);
                    readArgs.put("mid_list_new", noAttachIds);
                    GameMessage readRsp = tc.engine.request("req_batch_mail_read", readArgs);
                    if (!isDegraded(readRsp)) {
                        Object rc = readRsp.getContent().get("code");
                        if (rc == null || toInt(rc) == 0) {
                            List<Object> rl = listOfRaw(readRsp.getContent().get("readlist_new"));
                            readNoAttach = rl == null ? noAttachIds.size() : rl.size();
                        }
                    }
                } catch (Exception ignored) {
                    // 已读失败不影响附件领取结果
                }
            }
            tc.saveModText("mail", "read", "上轮领取 " + confirmed + " 封 · 候选 " + pendingIds.size());
            return new TaskOutcome("mail", "邮件奖励", TaskOutcome.Category.CLAIMED,
                    "领取邮件 " + confirmed + " 封（候选 " + pendingIds.size() + "）"
                            + (readNoAttach > 0 ? "，公告已读 " + readNoAttach + " 封" : ""));
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
            // 英雄殿堂·英勇见证（hero story）：req_hero_story_claim_reward(id)，
            // id 来自 notify_hero_story_info；未同步时明确跳过，不猜测。
            if (tc.subOn("activity_rewards", "hero_valiant", true)) {
                Long hsId = WjdrEngine.extractId(tc.engine.notifyContent("notify_hero_story_info"));
                if (hsId != null) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("id", hsId);
                    int[] c = tryClaim(tc, "英勇见证", "req_hero_story_claim_reward", args, rewardParts);
                    ok += c[0]; skip += c[1]; fail += c[3];
                } else {
                    skip++;
                }
            }
            // 曙光市集（协议名 duck_market）：任务奖励 + 消耗档位奖励。
            // 旧实现未接，且游戏通知会持续保留可领项；这里按天台账去重，已领不再请求/弹窗。
            String dawnStatus = null;
            if (tc.subOn("activity_rewards", "dawn_market", true)) {
                try {
                    Map<String, Object> dm = tc.engine.notifyData("notify_duck_market_act_info");
                    Map<String, Object> ai = dm == null ? null : Exec.mapOf(dm.get("act_info"));
                    long actId = ai == null ? 0 : Exec.longOf(ai.get("id"));
                    if (actId <= 0) {
                        skip++;
                        dawnStatus = "未开启/未同步";
                    } else {
                        int dc = 0, df = 0;
                        // 协议：task_info 与 act_info 同级挂在通知根上，不在 act_info 里面。
                        Map<String, Object> ti = Exec.mapOf(dm.get("task_info"));
                        Object finishs = ti == null ? null : ti.get("finishs");
                        List<Long> pending = unclaimedFinishIds(finishs);
                        int ds = Math.max(0, finishCount(finishs) - pending.size());
                        // finishs 在协议里是数组；沿用统一解析，isgetaward=true 的不再请求。
                        for (long qid : pending) {
                            String key = "quest:" + qid;
                            if (ClaimLedger.isClaimed(tc, "dawn_market", key)) {
                                ds++;
                                continue;
                            }
                            int[] rc = tryClaim(tc, "曙光市集",
                                    "req_duck_market_get_quest_award",
                                    args("act_id", actId, "quest_id", qid), rewardParts);
                            if (rc[0] > 0) {
                                dc++;
                                ClaimLedger.mark(tc, "dawn_market", key);
                            } else if (rc[1] > 0) {
                                ds++;
                                ClaimLedger.mark(tc, "dawn_market", key);
                            } else {
                                df++;
                            }
                        }
                        // 消耗档位：rewarded_idx_lst 非空表示已领过；台账兜底防重复请求。
                        List<Object> gotIdx = listOfRaw(ai.get("rewarded_idx_lst"));
                        boolean costClaimed = gotIdx != null && !gotIdx.isEmpty();
                        if (!costClaimed && !ClaimLedger.isClaimed(tc, "dawn_market", "cost")) {
                            int[] rc = tryClaim(tc, "曙光市集消耗档",
                                    "req_duck_market_get_cost_award",
                                    args("act_id", actId), rewardParts);
                            if (rc[0] > 0) {
                                dc++;
                                ClaimLedger.mark(tc, "dawn_market", "cost");
                            } else if (rc[1] > 0) {
                                ds++;
                                ClaimLedger.mark(tc, "dawn_market", "cost");
                            } else {
                                df++;
                            }
                        } else {
                            ds++;
                        }
                        if (dc > 0) {
                            ok += dc;
                            dawnStatus = "领取" + dc;
                            rewardParts.append("曙光市集×").append(dc).append("；");
                        } else if (df > 0) {
                            fail += df;
                            dawnStatus = "失败" + df;
                        } else {
                            skip++;
                            dawnStatus = "无可领（已领/去重 " + ds + "）";
                        }
                    }
                } catch (Exception ex) {
                    fail++;
                    dawnStatus = "异常:" + ServerTasks.brief(String.valueOf(ex.getMessage()));
                }
                // 未开启/异常也要写状态：否则 App 看到的还是上一轮旧文案。
                if (dawnStatus != null) {
                    tc.saveModText("activity_rewards", "dawn_market", dawnStatus);
                }
            }
            StringBuilder note = new StringBuilder();
            note.append("领取").append(ok).append("/跳过").append(skip);
            if (fail > 0) note.append("/失败").append(fail);
            TaskOutcome.Category cat = fail > 0 ? TaskOutcome.Category.ERROR
                    : (ok > 0 ? TaskOutcome.Category.CLAIMED : TaskOutcome.Category.SKIPPED);
            TaskOutcome r = new TaskOutcome("activity_rewards", "活动奖励合集(17种)", cat,
                    note + "（未开放的活动自动跳过）");
            if (dawnStatus != null) {
                String tag = dawnStatus.startsWith("领取") ? "ok:"
                        : (dawnStatus.startsWith("失败") || dawnStatus.startsWith("异常")
                        ? "fail:" : "skip:");
                r.subResults.put("dawn_market", tag + "曙光市集 " + dawnStatus);
            }
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

    static TaskOutcome allianceChest(TaskContext tc) throws Exception {
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> notify = tc.engine.notifyData("notify_alliance_chest_init");
            if (notify == null) notify = tc.engine.notifyData("notify_alliance_chest_update");
            Map<String, Object> chests = notify == null ? null : Exec.mapOf(notify.get("chests"));
            if (chests == null || chests.isEmpty()) {
                return new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.SKIPPED, "暂无可领取的宝箱（无宝箱列表）");
            }
            int claimed = 0, skipped = 0, failed = 0;
            StringBuilder detail = new StringBuilder();
            for (Object value : chests.values()) {
                Map<String, Object> chest = Exec.mapOf(value);
                if (chest == null) continue;
                Object receive = chest.get("receive");
                boolean already = receive instanceof Boolean
                        ? (Boolean) receive : "true".equalsIgnoreCase(String.valueOf(receive));
                if (already) { skipped++; continue; }
                long id = Exec.longOf(chest.get("id"));
                if (id <= 0) continue;
                long cid = Exec.longOf(chest.get("cid"));
                // 协议：req_alliance_receive_chest{id,type}；普通联盟宝箱 type=1，
                // 礼物类宝箱(cid=90101) type=2。旧代码传 null，服务端恒返回 code=2。
                int type = cid == 90101L ? 2 : 1;
                GameMessage rsp = tc.engine.request("req_alliance_receive_chest",
                        args("id", id, "type", type));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    claimed++;
                    detail.append(chest.get("name") == null ? ("#" + id) : chest.get("name")).append("✓ ");
                } else if (code == 2 || code == 4303 || code == 7501 || code == 40611) {
                    skipped++;
                } else {
                    failed++;
                }
            }
            TaskOutcome r;
            if (claimed > 0) {
                r = new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.CLAIMED, "联盟宝箱领取×" + claimed + " " + detail);
            } else if (failed > 0) {
                r = new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.ERROR, "宝箱失败×" + failed);
            } else {
                r = new TaskOutcome("alliance_chest", "联盟宝箱",
                        TaskOutcome.Category.SKIPPED, "暂无可领取的宝箱");
            }
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
            tc.engine.collectNotifies(1500);

            // 免费科技捐献：协议 req_alliance_science_donate{id,level}，
            // 旧代码无参调用恒 code=2。优先用配置的 id/level，否则取 notify 的第一个科技。
            try {
                long donateId = 0, donateLevel = 0;
                org.json.JSONObject p = tc.params("alliance_donate");
                if (p.has("science_id")) {
                    donateId = p.optLong("science_id", 0);
                    donateLevel = p.optLong("science_level", 0);
                }
                if (donateId <= 0) {
                    Map<String, Object> sci = tc.engine.notifyData("notify_alliance_science_init");
                    for (Map<String, Object> s : Exec.mapsOf(sci == null ? null : sci.get("sciences"))) {
                        long id = Exec.longOf(s.get("id"));
                        if (id <= 0) continue;
                        donateId = id;
                        donateLevel = Exec.longOf(s.get("lv"));
                        break;
                    }
                }
                if (donateId > 0) {
                    GameMessage rsp = tc.engine.request("req_alliance_science_donate",
                            args("id", donateId, "level", donateLevel));
                    int code = Exec.codeOf(rsp);
                    if (Exec.ok(rsp)) {
                        ok++;
                        sb.append("科技捐献✓ ");
                        String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                        if (!d.isEmpty()) rewardParts.append("科技捐献：").append(d).append("；");
                    } else {
                        sb.append("科技捐献").append(describeSupplyCode(code, ":")).append("; ");
                    }
                } else {
                    sb.append("科技捐献:科技数据未同步; ");
                }
            } catch (Exception ex) {
                TaskOutcome r = classifyException(ex, "科技捐献");
                if (r.category == TaskOutcome.Category.SESSION_CLOSED) return r;
                sb.append("科技捐献未完成; ");
            }

            // 无参领取类：每日/每周发展奖励；排名奖励协议需要 type。
            String[][] noArg = {
                    {"每日发展奖励", "req_alliance_dev_get_daily"},
                    {"每周发展奖励", "req_alliance_dev_get_week"},
            };
            for (String[] st : noArg) {
                try {
                    GameMessage rsp = tc.engine.request(st[1], null);
                    if (isDegraded(rsp)) { sb.append(st[0]).append(":响应异常; "); continue; }
                    int code = Exec.codeOf(rsp);
                    if (code != 0) {
                        sb.append(st[0]).append(describeSupplyCode(code, ":")).append("; ");
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
            try {
                int rankType = Math.max(1, tc.params("alliance_donate").optInt("rank_type", 1));
                GameMessage rsp = tc.engine.request("req_alliance_rank_get_rewards",
                        args("type", rankType));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    ok++;
                    sb.append("联盟排名奖励✓ ");
                    String d = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                    if (!d.isEmpty()) rewardParts.append("联盟排名奖励：").append(d).append("；");
                } else {
                    sb.append("联盟排名奖励").append(describeSupplyCode(code, ":")).append("; ");
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

    static TaskOutcome freeShield(TaskContext tc) throws Exception {
        try {
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

    public static TaskOutcome classifyException(Throwable e, String name) {
        String msg = String.valueOf(e.getMessage());
        for (String mark : SESSION_CLOSED_MARKS) {
            if (msg.contains(mark)) {
                return new TaskOutcome(name, name, TaskOutcome.Category.SESSION_CLOSED,
                        "会话中断（可重试）：" + brief(msg));
            }
        }
        Throwable cause = e.getCause();
        String causeMsg = cause == null ? "" : " cause=" + cause.getMessage();
        return new TaskOutcome(name, name, TaskOutcome.Category.ERROR,
                brief(e.getClass().getSimpleName() + ": " + msg + causeMsg));
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
