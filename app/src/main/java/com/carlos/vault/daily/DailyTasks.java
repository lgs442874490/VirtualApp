package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首批日常领取任务（协议与判定移植自原版反编译，按失效根因分析加固）：
 * - 不依赖服务端推送状态，直接尝试领取并以响应为准（修"状态未初始化→永久无效"）
 * - 服务端错误码分级：合理跳过 vs 真异常（修"静默 SKIPPED"）
 * - 响应缺预期字段 → DEGRADED 而非成功（修"假成功无效果"）
 * - 会话中断关键字 → SESSION_CLOSED 可重试
 */
public final class DailyTasks {

    private static final String[] SESSION_CLOSED_MARKS = {
            "不在线", "会话已", "连接已关闭", "断开", "强制下线", "连接失败", "发送协议数据失败",
            "等待协议响应超时", "Socket", "socket", "EOF"};

    /** 任务定义 */
    public static class TaskDef {
        public final String id;
        public final String name;
        public final boolean enabledByDefault;
        public final Executor executor;

        public interface Executor {
            DailyResult run(WjdrEngine engine) throws Exception;
        }

        public TaskDef(String id, String name, boolean enabledByDefault, Executor executor) {
            this.id = id;
            this.name = name;
            this.enabledByDefault = enabledByDefault;
            this.executor = executor;
        }
    }

    /** 已注册任务（首批 5 项；后续批次在此追加）。 */
    public static List<TaskDef> all() {
        List<TaskDef> list = new ArrayList<>();
        list.add(new TaskDef("login_rewards", "登录好礼", true, DailyTasks::loginRewards));
        list.add(new TaskDef("mail", "邮件奖励", true, DailyTasks::mailRewards));
        list.add(new TaskDef("supply", "仓库补给", true, DailyTasks::supplyRewards));
        list.add(new TaskDef("daily_mission", "每日任务", true, DailyTasks::dailyMission));
        list.add(new TaskDef("chapter_quest", "章节任务", true, DailyTasks::chapterQuest));
        list.add(new TaskDef("adventure", "挂机奖励", true, DailyTasks::adventureReward));
        list.add(new TaskDef("activity_rewards", "活动奖励", true, DailyTasks::activityRewards));
        list.add(new TaskDef("alliance_chest", "联盟宝箱", true, DailyTasks::allianceChest));
        list.add(new TaskDef("alliance_donate", "联盟捐献", true, DailyTasks::allianceDonate));
        list.add(new TaskDef("battle_shield", "战斗护盾", true, DailyTasks::freeShield));
        list.add(new TaskDef("shop", "商店购买", false, ShopTasks::shopBuy));
        list.add(new TaskDef("commander_gift", "统帅礼包", true, DailyTasks::commanderGift));
        list.add(new TaskDef("achievement_claim", "成就奖励", true, DailyTasks::achievementClaim));
        // ===== 城镇 =====
        list.add(new TaskDef("research", "科技研究", true, TownDevTasks::research));
        list.add(new TaskDef("building", "建筑升级", false, TownDevTasks::building));
        list.add(new TaskDef("town_affairs", "城镇管理", true, TownTasks::townAffairs));
        list.add(new TaskDef("medic", "军医所", true, TownTasks::medic));
        list.add(new TaskDef("barracks", "兵营训练", false, TownTasks::barracks));
        list.add(new TaskDef("pet", "宠物", true, TownTasks::pet));
        list.add(new TaskDef("expert", "专家游历", true, TownTasks::expert));
        list.add(new TaskDef("homeland", "晨曦岛", true, TownTasks::homeland));
        list.add(new TaskDef("fire_crystal", "火晶提炼", true, TownTasks::fireCrystal));
        list.add(new TaskDef("climbtower", "地心探索", true, TownTasks::climbtower));
        list.add(new TaskDef("arena", "竞技场", false, TownTasks::arena));
        // ===== 活动 =====
        list.add(new TaskDef("act_balloon", "正中靶心", true, ActivityTasks::balloon));
        list.add(new TaskDef("act_pitchpot", "壶纳千祥", true, ActivityTasks::pitchpot));
        list.add(new TaskDef("act_snowclean", "除雪", true, ActivityTasks::snowclean));
        list.add(new TaskDef("act_icebound", "冰封宝藏", true, ActivityTasks::icebound));
        list.add(new TaskDef("act_polar", "逐光之旅", true, ActivityTasks::polar));
        list.add(new TaskDef("act_brawl", "欢乐乱斗", true, ActivityTasks::brawl));
        list.add(new TaskDef("act_fishing", "钓鱼", true, ActivityTasks::fishing));
        list.add(new TaskDef("act_endless", "无尽征程", true, ActivityTasks::endlessJourney));
        list.add(new TaskDef("act_joy", "怪攻守城", false, ActivityTasks::joyAction));
        list.add(new TaskDef("act_feast", "至尊盛宴", false, ActivityTasks::supremacyFeast));
        list.add(new TaskDef("act_mercenary", "佣兵荣耀", false, ActivityTasks::mercenary));
        list.add(new TaskDef("act_truck", "运宝车队", false, ActivityTasks::truckEscort));
        list.add(new TaskDef("act_icehunter", "冰原猎人", false, ActivityTasks::icefieldHunter));
        list.add(new TaskDef("red_packet", "游戏红包", true, ActivityTasks::redPacket));
        list.add(new TaskDef("bank", "钻石银行", false, ActivityTasks::bank));
        list.add(new TaskDef("act_flamefangs", "烈焰獠牙", false, ShopTasks::flamefangs));
        list.add(new TaskDef("act_snowtrade", "雪原贸易站", false, ShopTasks::snowTrade));
        list.add(new TaskDef("act_frostfire", "燃霜矿区", false, ShopTasks::frostfire));
        list.add(new TaskDef("social_gift", "社交礼物", false, ShopTasks::socialGift));
        // ===== 野外 =====
        list.add(new TaskDef("gather", "野外采集", false, FieldTasks::gather));
        list.add(new TaskDef("intel", "情报任务", false, FieldTasks::intel));
        list.add(new TaskDef("behemoth", "巨兽狩猎", true, FieldTasks::behemoth));
        list.add(new TaskDef("bear", "巨熊行动", true, FieldTasks::bear));
        list.add(new TaskDef("hunt", "自动狩猎", false, FieldTasks::hunt));
        list.add(new TaskDef("adventure_sweep", "探险速刷", false, FieldTasks::adventureSweep));
        // ===== 联盟 =====
        list.add(new TaskDef("alliance_mobilization", "联盟动员", true, LeagueTasks::mobilization));
        list.add(new TaskDef("alliance_showdown", "争霸赛", false, LeagueTasks::showdown));
        list.add(new TaskDef("alliance_help", "联盟互助", true, LeagueTasks::allianceHelp));
        list.add(new TaskDef("homeland_visit", "晨曦岛拜访", true, LeagueTasks::homelandVisit));
        // ===== 商店/其他 =====
        list.add(new TaskDef("alliance_shop", "联盟商店", false, ShopTasks::allianceShop));
        list.add(new TaskDef("backpack", "背包道具", true, LeagueTasks::backpack));
        return list;
    }

    // ---------- 1. 登录好礼（7 天双轨签到：免费轨 + 史诗轨各 7 天） ----------

    static DailyResult loginRewards(WjdrEngine engine) throws Exception {
        try {
            // 子开关门控：sign=7天签到一键补领（FnDetail.defOn=true，存量角色默认行为不变）
            if (!FnParams.subOn(engine.appContext(), engine.lastFpid() == null ? "" : engine.lastFpid(),
                    engine.lastServerId(), "login_rewards", "sign", true)) {
                return new DailyResult("login_rewards", "登录好礼", DailyResult.Category.SKIPPED,
                        "子开关「7天签到」已关闭");
            }
            // 领取前快照：响应不带明细时按目录推"第N天领了什么"
            org.json.JSONObject mod = FnParams.modState(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "login_rewards");
            String fp = engine.lastFpid() == null ? "" : engine.lastFpid();
            long sid = engine.lastServerId();
            StringBuilder msg = new StringBuilder();
            StringBuilder rewards = new StringBuilder();
            int claimed = 0, degraded = 0;

            // ① 登录好礼（十四天双轨）
            GameMessage rsp = engine.request("req_fourteen_sign_get_all_reward", null);
            DailyResult r = classifySimple(rsp, "req_fourteen_sign_get_all_reward", "登录好礼",
                    new String[]{"code"}, "已一键领取全部可领签到奖励");
            if (r.category == DailyResult.Category.CLAIMED) {
                claimed++;
                String d = r.rewards.isEmpty() ? loginGiftDetail(mod) : r.rewards;
                msg.append("登录好礼已领");
                if (!d.isEmpty()) {
                    msg.append("（").append(d).append("）");
                    rewards.append(d).append("；");
                }
            } else if (r.category == DailyResult.Category.DEGRADED) {
                degraded++;
                msg.append("登录好礼响应异常");
            } else {
                msg.append("登录好礼无可领");
            }

            // ② 七天签到（协议盘点 2026-10-12 实锤：req_seven_sign_one_click_award 空参一键，
            //    已领天数由服务端跳过；award_success_list 非空才算真领到）
            if (FnParams.subOn(engine.appContext(), fp, sid, "login_rewards", "seven", true)) {
                GameMessage seven = engine.request("req_seven_sign_one_click_award", null);
                if (isDegraded(seven)) {
                    degraded++;
                    msg.append("; 七天签到响应异常");
                } else if (rspOk(seven)) {
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

            // ③ 21天签到免费档（req_sign_21day_get_all_reward 空参全领；付费档 has_pay 才有）
            if (FnParams.subOn(engine.appContext(), fp, sid, "login_rewards", "day21", true)) {
                GameMessage d21 = engine.request("req_sign_21day_get_all_reward", null);
                if (isDegraded(d21)) {
                    degraded++;
                    msg.append("; 21天签到响应异常");
                } else if (rspOk(d21)) {
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

            DailyResult out = new DailyResult("login_rewards", "登录好礼",
                    claimed > 0 ? DailyResult.Category.CLAIMED
                            : degraded > 0 ? DailyResult.Category.DEGRADED
                            : DailyResult.Category.SKIPPED,
                    msg.toString());
            out.rewards = rewards.toString();
            return out;
        } catch (Exception e) {
            return classifyException(e, "登录好礼");
        }
    }

    /** 轻量成功判定（与 classifySimple 同口径）：有 code 看 code，无 code 视为成功。 */
    private static boolean rspOk(GameMessage msg) {
        if (isDegraded(msg)) return false;
        Object code = msg.getContent().get("code");
        return code == null || toInt(code) == 0;
    }

    // ---------- 1b. 成就奖励（协议盘点 2026-10-12 新增） ----------

    /**
     * notify_achievement_info 字段实证：infos[]{id, count, complete:"true"/"false", received:...}。
     * 领取条件 = complete && !received；req_achievement_get_reward.request{list_data}（批量 id）。
     */
    static DailyResult achievementClaim(WjdrEngine engine) {
        try {
            if (!FnParams.subOn(engine.appContext(), engine.lastFpid() == null ? "" : engine.lastFpid(),
                    engine.lastServerId(), "achievement_claim", "claim", true)) {
                return new DailyResult("achievement_claim", "成就奖励", DailyResult.Category.SKIPPED,
                        "子开关「批量领取」已关闭");
            }
            engine.collectNotifies(1200);
            Map<String, Object> ai = engine.notifyData("notify_achievement_info");
            List<Integer> ids = new ArrayList<>();
            for (Map<String, Object> a : listOfMaps(ai == null ? null : ai.get("infos"))) {
                boolean complete = "true".equals(String.valueOf(a.get("complete")));
                boolean received = "true".equals(String.valueOf(a.get("received")));
                int id = toInt(a.get("id"));
                if (complete && !received && id > 0) ids.add(id);
            }
            if (ids.isEmpty()) {
                return new DailyResult("achievement_claim", "成就奖励", DailyResult.Category.SKIPPED,
                        ai == null ? "成就数据未同步" : "没有已完成未领取的成就");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("list_data", ids);
            GameMessage rsp = engine.request("req_achievement_get_reward", args);
            if (isDegraded(rsp)) {
                return new DailyResult("achievement_claim", "成就奖励", DailyResult.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                return classifyServerCode(toInt(code), "成就奖励");
            }
            String d = descRewards(rsp.getContent().get("rewards") != null
                    ? rsp.getContent().get("rewards") : rsp.getContent().get("awards"));
            DailyResult r = new DailyResult("achievement_claim", "成就奖励", DailyResult.Category.CLAIMED,
                    "成就奖励×" + ids.size() + (d.isEmpty() ? "" : "：" + d));
            r.rewards = d;
            return r;
        } catch (Exception e) {
            return classifyException(e, "成就奖励");
        }
    }

    /** 响应无明细时，按同步到的签到进度从目录推当日奖励（防御式：推不出留空）。 */
    static String loginGiftDetail(org.json.JSONObject mod) {
        try {
            if (mod == null || mod.optLong("sign_ts", 0) == 0) return "";
            int day = (int) Math.min(7, mod.optLong("sign_days", 0) + 1);
            if (day < 1 || day > 7) return "";
            int round = mod.optInt("server_index", 5004001);
            StringBuilder sb = new StringBuilder("第").append(day).append("天：")
                    .append(RewardText.join(RewardText.loginRewardsFree(round)[day - 1]));
            if (mod.optBoolean("has_pay", false)) {
                sb.append("；史诗轨：")
                        .append(RewardText.join(RewardText.loginRewardsEpic(round)[day - 1]));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    // ---------- 2. 邮件奖励 ----------

    static DailyResult mailRewards(WjdrEngine engine) throws Exception {
        try {
            // 板块策略：全部/仅系统/系统+联盟/除战报（板块→类型映射照原版MailSection）
            String board = FnParams.strategy(engine.appContext(), engine.lastFpid(),
                    engine.lastServerId(), "mail", "read", "all");
            int[] types;
            if (board.contains(",")) {
                // 任意板块组合（详情页板块卡勾选）：逗号分隔的 first_type 列表
                String[] parts = board.split(",");
                java.util.List<Integer> tl = new java.util.ArrayList<>();
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
            // 主动拉取邮箱板块（不依赖推送状态）
            engine.request("req_mail_type_info", null);
            // 子开关门控：keep_unread=仅领有附件邮件（默认开，公告类不动）；关=无附件邮件一并已读
            boolean keepUnread = FnParams.subOn(engine.appContext(), engine.lastFpid() == null ? "" : engine.lastFpid(),
                    engine.lastServerId(), "mail", "keep_unread", true);
            int claimed = 0;
            int sections = 0;
            List<String> pendingIds = new ArrayList<>();
            List<String> noAttachIds = new ArrayList<>();   // 无附件邮件（仅 keep_unread=false 时批量已读）
            StringBuilder degradeNote = new StringBuilder();
            for (int type : types) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("first_type", type);
                args.put("beginidx", 0);
                args.put("endidx", 50);
                GameMessage cover = engine.request("req_mail_cover_list", args);
                if (isDegraded(cover)) {
                    degradeNote.append("板块").append(type).append("响应异常; ");
                    continue;
                }
                sections++;
                List<Map<String, Object>> covers = listOfMaps(cover.getContent().get("coverlist"));
                if (covers == null) {
                    // 真机验证：空板块响应即无 coverlist 字段 → 视为空箱，不算协议异常
                    continue;
                }
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
                if (pendingIds.size() >= 100) break;   // 与原版一致：单批上限
            }
            if (pendingIds.isEmpty()) {
                // 关掉「仅领有附件邮件」后：即使没有可领附件，也把无附件公告批量已读
                if (!keepUnread && !noAttachIds.isEmpty()) {
                    try {
                        Map<String, Object> readArgs = new LinkedHashMap<>();
                        readArgs.put("first_type", 10);
                        readArgs.put("mid_list_new", noAttachIds);
                        GameMessage readRsp = engine.request("req_batch_mail_read", readArgs);
                        if (!isDegraded(readRsp)) {
                            Object rc = readRsp.getContent().get("code");
                            if (rc == null || toInt(rc) == 0) {
                                List<Object> rl = listOfRaw(readRsp.getContent().get("readlist_new"));
                                int n = rl == null ? noAttachIds.size() : rl.size();
                                return new DailyResult("mail", "邮件奖励", DailyResult.Category.CLAIMED,
                                        "无附件可领，公告已读 " + n + " 封");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (degradeNote.length() > 0) {
                    return new DailyResult("mail", "邮件奖励", DailyResult.Category.DEGRADED,
                            "部分板块协议异常：" + degradeNote);
                }
                return new DailyResult("mail", "邮件奖励", DailyResult.Category.SKIPPED,
                        sections == 0 ? "邮箱板块未开放或无数据" : "没有可领取的邮件附件");
            }
            // 批量领取（板块归属按批次内先试 type=10 批；失败细分到单封留待下批版本）
            Map<String, Object> claimArgs = new LinkedHashMap<>();
            claimArgs.put("first_type", 10);
            claimArgs.put("mid_list_new", pendingIds);
            GameMessage claim = engine.request("req_batch_mail_read", claimArgs);
            if (isDegraded(claim)) {
                return new DailyResult("mail", "邮件奖励", DailyResult.Category.DEGRADED,
                        "批量领取响应异常（协议可能变更）");
            }
            Object code = claim.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                return classifyServerCode(toInt(code), "邮件奖励");
            }
            List<Object> readList = listOfRaw(claim.getContent().get("readlist_new"));
            int confirmed = readList == null ? pendingIds.size() : readList.size();
            claimed = confirmed;
            // keep_unread=false：无附件的公告类邮件一并标记已读（附附件邮件上面已领）
            int readNoAttach = 0;
            if (!keepUnread && !noAttachIds.isEmpty()) {
                try {
                    Map<String, Object> readArgs = new LinkedHashMap<>();
                    readArgs.put("first_type", 10);
                    readArgs.put("mid_list_new", noAttachIds);
                    GameMessage readRsp = engine.request("req_batch_mail_read", readArgs);
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
            mod(engine, "mail", "read", "上轮领取 " + claimed + " 封 · 候选 " + pendingIds.size());
            return new DailyResult("mail", "邮件奖励", DailyResult.Category.CLAIMED,
                    "领取邮件 " + claimed + " 封（候选 " + pendingIds.size() + "）"
                            + (readNoAttach > 0 ? "，公告已读 " + readNoAttach + " 封" : ""));
        } catch (Exception e) {
            return classifyException(e, "邮件奖励");
        }
    }

    // ---------- 3. 仓库补给：普通补给 + 限时体力（按子项开关与策略） ----------

    static DailyResult supplyRewards(WjdrEngine engine) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean anyDegraded = false;
        android.content.Context ctx = engine.appContext();
        String fpid = engine.lastFpid() == null ? "" : engine.lastFpid();
        long sid = engine.lastServerId();
        boolean onOnline = FnParams.subOn(ctx, fpid, sid, "supply", "online", true);
        boolean onSpec = FnParams.subOn(ctx, fpid, sid, "supply", "spec", true);
        String strat = FnParams.strategy(ctx, fpid, sid, "supply", "spec", "now");
        java.util.Map<String, String> subs = new java.util.LinkedHashMap<>();
        StringBuilder rewardParts = new StringBuilder();   // 领取明细（落盘到日志 detail）
        try {
            // 3.1 普通仓库补给
            if (onOnline) {
                try {
                    GameMessage online = engine.request("req_receive_online_reward", null);
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
                    DailyResult r = classifyException(e, "普通补给");
                    if (r.category == DailyResult.Category.SESSION_CLOSED) return r;
                    sb.append(r.message).append("; ");
                }
            } else {
                sb.append("普通补给已关; ");
            }
            // 3.2 限时体力补给（策略：now=刷新即领；end30/end60/custom=截止前N分钟才领）
            if (onSpec) {
                boolean allowed = true;
                long specEnd = specWindowEnd(ctx, fpid, sid);
                if (!"now".equals(strat) && specEnd > 0) {
                    long leadMin = "end30".equals(strat) ? 30
                            : "end60".equals(strat) ? 60
                            : FnParams.strategyMinutes(ctx, fpid, sid, "supply", "spec", 30);
                    allowed = System.currentTimeMillis() >= (specEnd - leadMin * 60_000L);
                }
                if (allowed) {
                    try {
                        GameMessage spec = engine.request("req_receive_spec_reward", null);
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
                        DailyResult r = classifyException(e, "体力补给");
                        if (r.category == DailyResult.Category.SESSION_CLOSED) return r;
                        sb.append(r.message).append("; ");
                    }
                } else {
                    subs.put("spec", "skip:按策略等待窗口");
                    sb.append("体力补给按策略等待截止前").append("now".equals(strat) ? 0
                            : "end30".equals(strat) ? 30 : "end60".equals(strat) ? 60
                            : FnParams.strategyMinutes(ctx, fpid, sid, "supply", "spec", 30)).append("分钟; ");
                }
            } else {
                sb.append("体力补给已关; ");
            }
            // 采集补给状态行（下一次可领/窗口截止）
            captureSupplyState(engine, ctx, fpid, sid);
            String msg = sb.length() == 0 ? "无结果" : sb.substring(0, sb.length() - 2);
            boolean claimed = msg.contains("已领取");
            DailyResult sr = new DailyResult("supply", "仓库补给",
                    anyDegraded ? DailyResult.Category.DEGRADED
                            : (claimed ? DailyResult.Category.CLAIMED : DailyResult.Category.SKIPPED),
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

    /** 从已存的补给状态取当前限时窗口截止时间（epoch秒）。 */
    private static long specWindowEnd(android.content.Context ctx, String fpid, long sid) {
        try {
            org.json.JSONObject st = FnParams.modState(ctx, fpid, sid, "supply");
            return st.optLong("spec_end", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析 notify_recover_resource_init → 子项状态行文案 + spec_end（形状防御式：深扫时间字段）。 */
    static void captureSupplyState(WjdrEngine engine, android.content.Context ctx, String fpid, long sid) {
        try {
            Map<String, Object> ri = engine.notifyData("notify_recover_resource_init");
            if (ri == null) return;
            debugLog(ctx, "recover raw=" + String.valueOf(ri).substring(0, Math.min(400, String.valueOf(ri).length())));
            org.json.JSONObject out = new org.json.JSONObject();
            java.util.List<long[]> times = new java.util.ArrayList<>();   // {epochSec, kind}
            collectTimes(ri, times, 0);
            long now = System.currentTimeMillis() / 1000L;
            long onlineNext = 0, specEnd = 0;
            for (long[] t : times) {
                if (t[0] < now) continue;
                if (t[1] == 1 && (onlineNext == 0 || t[0] < onlineNext)) onlineNext = t[0];
                if (t[1] == 2 && (specEnd == 0 || t[0] > specEnd)) specEnd = t[0];
            }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
            if (onlineNext > 0) {
                out.put("online", "下一次可领 " + f.format(new java.util.Date(onlineNext * 1000)));
                out.put("online_ts", onlineNext);   // epoch 秒：任务级 wake 用（到点由调度器补领）
            }
            if (specEnd > 0) {
                out.put("spec", (isNoonWindow() ? "午间补给" : "晚间补给") + " · 截止 " + f.format(new java.util.Date(specEnd * 1000)));
                out.put("spec_end", specEnd);
            }
            FnParams.saveModState(ctx, fpid, sid, "supply", out);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNoonWindow() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h < 15;
    }

    /** 深扫收集时间字段：kind 1=名字含online/rec 2=名字含spec/end。 */
    private static void collectTimes(Object v, java.util.List<long[]> out, int depth) {
        if (depth > 4 || v == null) return;
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase();
                Object val = e.getValue();
                if (val instanceof Number) {
                    long n = ((Number) val).longValue();
                    boolean epochish = n > 1_700_000_000L && n < 2_000_000_000L;
                    if (epochish && (k.contains("end") || k.contains("time") || k.contains("ts"))) {
                        int kind = k.contains("spec") || k.contains("end") ? 2 : 1;
                        out.add(new long[]{n, kind});
                    }
                } else {
                    collectTimes(val, out, depth + 1);
                }
            }
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) collectTimes(o, out, depth + 1);
        }
    }

    // ---------- 4. 每日任务奖励 ----------

    /** 原版 DailyMissionCatalog 全量任务 ID（1320000~1320083 及后续段），缺失段由服务端忽略。 */
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

    static DailyResult dailyMission(WjdrEngine engine) throws Exception {
        try {
            // 子开关门控：claim=批量领奖（进度采集照跑，仅关领取动作）
            if (!FnParams.subOn(engine.appContext(), engine.lastFpid() == null ? "" : engine.lastFpid(),
                    engine.lastServerId(), "daily_mission", "claim", true)) {
                return new DailyResult("daily_mission", "每日任务奖励", DailyResult.Category.SKIPPED,
                        "子开关「批量领奖」已关闭");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            List<Integer> ids = dailyQuestIds(engine);
            args.put("quest_ids", ids);
            GameMessage rsp = engine.request("req_get_all_dailyquest_award", args);
            if (isDegraded(rsp)) {
                return new DailyResult("daily_mission", "每日任务奖励", DailyResult.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                return classifyServerCode(toInt(code), "每日任务奖励");
            }
            // 成功证据：奖励列表字段（原版为 awards / rewards 之一）
            boolean hasAward = rsp.getContent().containsKey("awards")
                    || rsp.getContent().containsKey("rewards")
                    || rsp.getContent().containsKey("quest_ids");
            if (hasAward) {
                DailyResult r = new DailyResult("daily_mission", "每日任务奖励", DailyResult.Category.CLAIMED,
                        "已完成任务奖励批量领取（未完成的任务由服务端自动跳过）");
                r.rewards = RewardText.fromContent(rsp.getContent());
                return r;
            }
            return new DailyResult("daily_mission", "每日任务奖励", DailyResult.Category.DEGRADED,
                    "响应无奖励字段，未确认到账（不视为成功）");
        } catch (Exception e) {
            return classifyException(e, "每日任务奖励");
        }
    }

    /**
     * 每日任务 id 清单：notify_dailyquests.quests 键（当前账号真实任务表）∪ 静态
     * DAILY_QUEST_IDS（推送缺失时兜底）。游戏新增任务（如 1320090/1320100 段）不再
     * 因硬编码表过时而漏领——2026-10-11 真机实证旧表止于 1320083，新任务全部漏领。
     */
    static List<Integer> dailyQuestIds(WjdrEngine engine) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        try {
            engine.collectNotifies(800);
            Map<String, Object> q = engine.notifyData("notify_dailyquests");
            Object quests = q == null ? null : q.get("quests");
            if (quests instanceof Map) {
                for (Object k : ((Map<?, ?>) quests).keySet()) {
                    try {
                        long id = (long) Double.parseDouble(String.valueOf(k).trim());
                        if (id > 0) ids.add(id);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        for (int id : DAILY_QUEST_IDS) ids.add((long) id);
        List<Integer> out = new ArrayList<>();
        for (long id : ids) out.add((int) id);
        return out;
    }

    // ---------- 4b. 章节奖励 + 章节任务 + 成长任务 ----------

    static DailyResult chapterQuest(WjdrEngine engine) throws Exception {
        try {
            engine.collectNotifies(1500);
            android.content.Context ctx = engine.appContext();
            String fpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long sid = engine.lastServerId();
            Map<String, Object> cq = engine.notifyContent("notify_chapterquests");
            Map<String, Object> gq = engine.notifyContent("notify_growthquests");
            int claimed = 0, skipped = 0;
            StringBuilder hits = new StringBuilder();

            // 成长任务：notify_growthquests.quests[].id 逐个尝试（未达标由服务端错误码跳过，上限 60 条防刷屏）
            if (FnParams.subOn(ctx, fpid, sid, "chapter_quest", "growth_quest", true)) {
                int sent = 0;
                for (Map<String, Object> q : ExecUtil.mapsOf(gq == null ? null : gq.get("quests"))) {
                    long id = ExecUtil.longOf(q.get("id"));
                    if (id <= 0 || sent >= 60) continue;
                    sent++;
                    GameMessage r = engine.request("req_get_growthquest_award",
                            ExecUtil.args("questid", id));
                    if (ExecUtil.ok(r)) {
                        claimed++;
                        if (hits.length() > 0) hits.append("、");
                        hits.append("成长#").append(id);
                    } else skipped++;
                }
                if (gq == null) skipped++;
            }

            // 章节任务：notify_chapterquests.quests[].id
            if (FnParams.subOn(ctx, fpid, sid, "chapter_quest", "chapter_quest", true)) {
                int sent = 0;
                for (Map<String, Object> q : ExecUtil.mapsOf(cq == null ? null : cq.get("quests"))) {
                    long id = ExecUtil.longOf(q.get("id"));
                    if (id <= 0 || sent >= 60) continue;
                    sent++;
                    GameMessage r = engine.request("req_get_chapterquest_award",
                            ExecUtil.args("questid", id));
                    if (ExecUtil.ok(r)) {
                        claimed++;
                        if (hits.length() > 0) hits.append("、");
                        hits.append("章任#").append(id);
                    } else skipped++;
                }
                if (cq == null) skipped++;
            }

            // 章节奖励：cur_chapter（含上一章兜底，跨章领漏）
            if (FnParams.subOn(ctx, fpid, sid, "chapter_quest", "chapter", true)) {
                long cur = cq == null ? 0 : ExecUtil.longOf(cq.get("cur_chapter"));
                if (cur > 0) {
                    long from = Math.max(1, cur - 1);
                    for (long ch = from; ch <= cur; ch++) {
                        GameMessage r = engine.request("req_get_chapter_award",
                                ExecUtil.args("chapterid", ch));
                        if (ExecUtil.ok(r)) {
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
                DailyResult r = new DailyResult("chapter_quest", "章节任务", DailyResult.Category.CLAIMED,
                        "领取×" + claimed + "：" + hits + "（服务端跳过×" + skipped + "）");
                return r;
            }
            if (cq == null && gq == null) {
                return new DailyResult("chapter_quest", "章节任务", DailyResult.Category.SKIPPED,
                        "章节/成长任务通知未同步");
            }
            return new DailyResult("chapter_quest", "章节任务", DailyResult.Category.SKIPPED,
                    "无可领（服务端跳过×" + skipped + "）");
        } catch (Exception e) {
            return classifyException(e, "章节任务");
        }
    }

    // ---------- 5. 探险挂机奖励 ----------

    static DailyResult adventureReward(WjdrEngine engine) throws Exception {
        try {
            // 子开关门控：hang_up 布尔（策略只管"距到量多久才领"，开关才是总闸）
            if (!FnParams.subOn(engine.appContext(), engine.lastFpid() == null ? "" : engine.lastFpid(),
                    engine.lastServerId(), "adventure", "hang_up", true)) {
                return new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.SKIPPED,
                        "子开关「挂机产量」已关闭");
            }
            // 主动预览（不依赖推送解锁状态；未解锁由错误码反馈）
            GameMessage view = engine.request("req_open_hang_up_reward_view", null);
            if (!isDegraded(view)) {
                try {
                    Object end = view.getContent().get("endtime");
                    if (end == null) end = view.getContent().get("end_time");
                    if (end instanceof Number) {
                        long ts = ((Number) end).longValue();
                        if (ts > 1_700_000_000L && ts < 2_000_000_000L) {
                            mod(engine, "adventure", "hang_up", "下次可领 " + new java.text.SimpleDateFormat(
                                    "HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date(ts * 1000)));
                            // 策略：距可领不足阈值（秒）则不领（总是/≥5分钟/≥10分钟/≥30分钟）
                            long minSec = 0;
                            try {
                                minSec = Long.parseLong(FnParams.strategy(engine.appContext(),
                                        engine.lastFpid(), engine.lastServerId(),
                                        "adventure", "hang_up", "0").trim());
                            } catch (Throwable ignored) {
                            }
                            long leftMin = (ts - System.currentTimeMillis() / 1000L) / 60L;
                            if (minSec / 60L > 0 && leftMin < minSec / 60L) {
                                return new DailyResult("adventure", "探险挂机奖励",
                                        DailyResult.Category.SKIPPED,
                                        "还差 " + Math.max(0, leftMin) + " 分钟到量，按阈值跳过");
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                Object code = view.getContent().get("code");
                if (code != null && toInt(code) != 0) {
                    DailyResult r = classifyServerCode(toInt(code), "探险挂机");
                    if (toInt(code) == 4004 || toInt(code) == 4003) {
                        return new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.SKIPPED,
                                "挂机奖励尚未解锁或间隔未到");
                    }
                    return r;
                }
            }
            GameMessage claim = engine.request("req_get_hang_up_reward", null);
            if (isDegraded(claim)) {
                return new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.DEGRADED,
                        "领取响应异常（协议可能变更）");
            }
            Object code = claim.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                if (toInt(code) == 4004) {
                    return new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.SKIPPED,
                            "领取间隔未到（约10分钟一领）");
                }
                if (toInt(code) == 4003) {
                    return new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.SKIPPED,
                            "探险挂机尚未解锁（需通关首个探险关卡）");
                }
                return classifyServerCode(toInt(code), "探险挂机");
            }
            if (claim.getContent().containsKey("rewards")
                    || claim.getContent().containsKey("reward")) {
                String d = descRewards(claim.getContent().get("rewards") != null
                        ? claim.getContent().get("rewards") : claim.getContent().get("reward"));
                DailyResult r = new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.CLAIMED,
                        "挂机奖励已领取" + d);
                r.rewards = d;
                return r;
            }
            // 真机验证：无可领时 code=0 且无奖励字段（预览也为空）→ 合理跳过
            return new DailyResult("adventure", "探险挂机奖励", DailyResult.Category.SKIPPED,
                    "当前暂无可领取的挂机奖励");
        } catch (Exception e) {
            return classifyException(e, "探险挂机");
        }
    }

    // ---------- 6. 活动奖励合集（17 种免费奖励） ----------

    static DailyResult activityRewards(WjdrEngine engine) throws Exception {
        try {
            // 等待登录后的活动状态推送（id 参数来源）
            engine.collectNotifies(2500);
            // 子开关门控（FnDetail：month_card/vip_exp/bundle/treasures/battle_pass，默认全开）
            android.content.Context actCtx = engine.appContext();
            String actFpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long actSid = engine.lastServerId();
            boolean onMonthCard = FnParams.subOn(actCtx, actFpid, actSid, "activity_rewards", "month_card", true);
            boolean onVipExp = FnParams.subOn(actCtx, actFpid, actSid, "activity_rewards", "vip_exp", true);
            boolean onBundle = FnParams.subOn(actCtx, actFpid, actSid, "activity_rewards", "bundle", true);
            boolean onTreasures = FnParams.subOn(actCtx, actFpid, actSid, "activity_rewards", "treasures", true);
            boolean onBattlePass = FnParams.subOn(actCtx, actFpid, actSid, "activity_rewards", "battle_pass", true);
            // 统帅每日礼包原挂在合集 noArg 里（独立 commander_gift 任务也发一次，重复领由服务端跳过），
            // 门控跟随 commander_gift.gift，保持"一个开关管一个动作"
            boolean onCommander = FnParams.subOn(actCtx, actFpid, actSid, "commander_gift", "gift", true);
            int ok = 0, skip = 0, degraded = 0, fail = 0;
            int gatedOff = 0;
            StringBuilder note = new StringBuilder();
            StringBuilder rewardParts = new StringBuilder();   // 每个活动的领取明细

            // (1) 无参奖励：直接领（按子开关逐项门控）
            Object[][] noArg = {
                    {"月卡每日奖励", "req_month_card_get_daily_reaward", onMonthCard},
                    {"VIP每日经验", "req_vip_get_daily_exp", onVipExp},
                    {"统帅每日礼包", "req_vip_get_daily_free_gift", onCommander},
                    {"每日特惠礼包", "req_daily_bundle_get_free_gift", onBundle},
            };
            for (Object[] na : noArg) {
                if (!(Boolean) na[2]) { gatedOff++; continue; }
                int[] c = tryClaim(engine, (String) na[0], (String) na[1], null, rewardParts);
                ok += c[0]; skip += c[1]; degraded += c[2]; fail += c[3];
            }

            // (2) 需要活动 id 的奖励：从对应 notify 提取，取不到则记为跳过（treasures 组）
            Object[][] byId = {
                    {"失落宝藏", "req_lost_treasure_get_daily_reward", "notify_lost_treasure_info"},
                    {"远征宝藏", "req_daily_claim_free_reward", "notify_daily_free_reward_info"},
                    {"许愿屋", "req_hero_turntable_get_free_gift", "notify_hero_turntable_info"},
                    {"米娅许愿屋", "req_fortune_card_get_daily_free_gift", "notify_fortune_card_info"},
                    {"无限宝箱", "req_infinite_box_get_reward", "notify_infinite_box_info"},
                    {"自选礼包", "req_optional_gift_pack_get_free_box", "notify_optional_gift_pack_info"},
                    {"自选箱", "req_customize_item_get_free_box", "notify_customize_item_info"},
                    {"匠人宝库", "req_multiple_choice_pack_get_free_box", "notify_multiple_choice_pack_info"},
                    // 协议盘点 2026-10-12：notify_fishing_info 不在 s2c schema（id 永远取不到 →
                    // 免费礼恒跳过），活动数据源实为 notify_fishing_activity_info（s2c 实证）
                    {"钓鱼免费礼", "req_fishing_get_free_gift", "notify_fishing_activity_info"},
            };
            for (Object[] bi : byId) {
                if (!onTreasures) { gatedOff++; continue; }
                Map<String, Object> notify = engine.notifyContent((String) bi[2]);
                Long id = WjdrEngine.extractId(notify);
                if (id == null) {
                    skip++;
                    continue;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("id", id);
                int[] c = tryClaim(engine, (String) bi[0], (String) bi[1], args, rewardParts);
                ok += c[0]; skip += c[1]; degraded += c[2]; fail += c[3];
            }

            // (3) 英雄集结（战令）：id 来自 battle_pass 通知
            if (onBattlePass) {
                Long bpId = WjdrEngine.extractId(engine.notifyContent("notify_battle_pass_info"));
                if (bpId != null) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("id", bpId);
                    int[] c = tryClaim(engine, "英雄集结", "req_battle_pass_collect_all_reward", args, rewardParts);
                    ok += c[0]; skip += c[1]; degraded += c[2]; fail += c[3];
                } else {
                    skip++;
                }
            } else {
                gatedOff++;
            }

            note.append("领取").append(ok).append("/跳过").append(skip);
            if (gatedOff > 0) note.append("/子开关关").append(gatedOff);
            if (fail > 0) note.append("/失败").append(fail);
            DailyResult.Category cat = fail > 0 ? DailyResult.Category.ERROR
                    : (ok > 0 ? DailyResult.Category.CLAIMED : DailyResult.Category.SKIPPED);
            DailyResult r = new DailyResult("activity_rewards", "活动奖励合集(17种)", cat,
                    note + "（未开放的活动自动跳过）");
            if (rewardParts.length() > 0) {
                r.rewards = rewardParts.substring(0, rewardParts.length() - 1);
            }
            return r;
        } catch (Exception e) {
            return classifyException(e, "活动奖励合集");
        }
    }

    /** 单个子领取：返回 {ok, skip, degraded, fail}；领取成功时把明细累计进 rewardsOut。 */
    static int[] tryClaim(WjdrEngine engine, String name, String proto, Map<String, Object> args,
                          StringBuilder rewardsOut) {
        try {
            GameMessage rsp = engine.request(proto, args);
            if (isDegraded(rsp)) {
                // 未知协议=本活动未开放或尚未收录映射：按合理跳过处理并留痕（不再拖垮整体）
                debugLog(engine.appContext(), "activity unknown proto: " + proto);
                return new int[]{0, 1, 0, 0};
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 7401 || c == 7404 || c == 40611 || c == 61 || c == 7501 || c == 4303
                            || c == 21 || c == 40502 || c == 40503 || c == 11724 || c == 11728 || c == 2926
                            // 真机 proto_log 2026-10-14 实证（物品快照确认已领/无资格，
                            // 但错误码不在白名单被记成"失败"，状态汇报失真）：
                            // 2=月卡无在期卡/条件不满足，4501=VIP每日经验已领
                            || c == 2 || c == 4501) {
                    return new int[]{0, 1, 0, 0};   // 活动未开/已领/无资格 → 合理跳过
                }
                return new int[]{0, 0, 0, 1};
            }
            if (rewardsOut != null && rewardsOut.length() <= 600) {
                String d = RewardText.fromContent(rsp.getContent());
                if (!d.isEmpty()) rewardsOut.append(name).append("：").append(d).append("；");
            }
            return new int[]{1, 0, 0, 0};
        } catch (Exception e) {
            DailyResult r = classifyException(e, name);
            if (r.category == DailyResult.Category.SESSION_CLOSED) {
                throw new RuntimeException(e);
            }
            return new int[]{0, 0, 0, 1};
        }
    }

    // ---------- 7. 联盟宝箱 ----------

    static DailyResult allianceChest(WjdrEngine engine) throws Exception {
        try {
            GameMessage rsp = engine.request("req_alliance_receive_chest", null);
            if (isDegraded(rsp)) {
                return new DailyResult("alliance_chest", "联盟宝箱", DailyResult.Category.DEGRADED,
                        "响应异常（可能需要宝箱列表参数）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 4303 || c == 7501 || c == 40611) {
                    return new DailyResult("alliance_chest", "联盟宝箱", DailyResult.Category.SKIPPED,
                            "暂无可领取的宝箱");
                }
                return classifyServerCode(c, "联盟宝箱");
            }
            DailyResult r = new DailyResult("alliance_chest", "联盟宝箱", DailyResult.Category.CLAIMED,
                    "联盟宝箱已批量领取" + descRewards(rsp.getContent().get("rewards")));
            r.rewards = RewardText.fromContent(rsp.getContent());
            return r;
        } catch (Exception e) {
            return classifyException(e, "联盟宝箱");
        }
    }

    // ---------- 8. 联盟捐献 + 联盟发展奖励 ----------

    static DailyResult allianceDonate(WjdrEngine engine) throws Exception {
        try {
            StringBuilder sb = new StringBuilder();
            StringBuilder rewardParts = new StringBuilder();
            int ok = 0, skip = 0;
            String[][] steps = {
                    {"免费科技捐献", "req_alliance_science_donate"},
                    {"每日发展奖励", "req_alliance_dev_get_daily"},
                    {"每周发展奖励", "req_alliance_dev_get_week"},
                    {"联盟排名奖励", "req_alliance_rank_get_rewards"},
            };
            for (String[] st : steps) {
                try {
                    GameMessage rsp = engine.request(st[1], null);
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
                    String d = RewardText.fromContent(rsp.getContent());
                    if (!d.isEmpty()) rewardParts.append(st[0]).append("：").append(d).append("；");
                } catch (Exception ex) {
                    DailyResult r = classifyException(ex, st[0]);
                    if (r.category == DailyResult.Category.SESSION_CLOSED) return r;
                    skip++;
                    sb.append(st[0]).append("未完成; ");
                }
            }
            boolean anyDegraded = sb.toString().contains("响应异常");
            DailyResult r = new DailyResult("alliance_donate", "联盟捐献",
                    anyDegraded ? DailyResult.Category.DEGRADED
                            : (ok > 0 ? DailyResult.Category.CLAIMED : DailyResult.Category.SKIPPED),
                    sb.length() == 0 ? "无结果" : sb.toString());
            if (rewardParts.length() > 0) {
                r.rewards = rewardParts.substring(0, rewardParts.length() - 1);
            }
            return r;
        } catch (Exception e) {
            return classifyException(e, "联盟捐献");
        }
    }

    // ---------- 9. 每日免费护盾 ----------

    static DailyResult freeShield(WjdrEngine engine) throws Exception {
        try {
            // 子开关门控：free=每日定时开盾，reactive=被袭击反应式开盾（同一协议的两个触发源，
            // 任一开启即允许执行；DailySchedulerService.onGameEvent 另有 reactive 单独门控）
            boolean fpidEmpty = engine.lastFpid() == null;
            String fp = fpidEmpty ? "" : engine.lastFpid();
            boolean shieldOn = FnParams.subOn(engine.appContext(), fp,
                    engine.lastServerId(), "battle_shield", "free", true)
                    || FnParams.subOn(engine.appContext(), fp,
                    engine.lastServerId(), "battle_shield", "reactive", true);
            if (!shieldOn) {
                return new DailyResult("battle_shield", "战斗护盾", DailyResult.Category.SKIPPED,
                        "子开关「免费护盾/反应式开盾」均已关闭");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("free_id", 1L);
            GameMessage rsp = engine.request("req_city_item_free_use", args);
            if (isDegraded(rsp)) {
                return new DailyResult("battle_shield", "战斗护盾", DailyResult.Category.DEGRADED,
                        "响应异常（协议可能变更）");
            }
            Object code = rsp.getContent().get("code");
            if (code != null && toInt(code) != 0) {
                int c = toInt(code);
                if (c == 6151 || c == 6158 || c == 7501 || c == 4303) {
                    return new DailyResult("battle_shield", "战斗护盾", DailyResult.Category.SKIPPED,
                            "今日已使用或不可用");
                }
                return classifyServerCode(c, "战斗护盾");
            }
            return new DailyResult("battle_shield", "战斗护盾", DailyResult.Category.CLAIMED,
                    "8小时免费护盾已开启（被袭击时生效）");
        } catch (Exception e) {
            return classifyException(e, "战斗护盾");
        }
    }

    // ---------- 统帅礼包（独立任务，从活动合集拆出） ----------

    static DailyResult commanderGift(WjdrEngine engine) throws Exception {
        try {
            // 子开关门控：gift=统帅每日礼包
            if (!FnParams.subOn(engine.appContext(), engine.lastFpid() == null ? "" : engine.lastFpid(),
                    engine.lastServerId(), "commander_gift", "gift", true)) {
                return new DailyResult("commander_gift", "统帅礼包", DailyResult.Category.SKIPPED,
                        "子开关「统帅每日礼包」已关闭");
            }
            GameMessage rsp = engine.request("req_vip_get_daily_free_gift", null);
            return classifySimple(rsp, "commander_gift", "统帅礼包",
                    new String[]{"code", "rewards"}, "当前统帅等级每日免费礼包已领取");
        } catch (Exception e) {
            return classifyException(e, "统帅礼包");
        }
    }

    // ---------- 10. 商店购买（骨架：默认关闭，待商品配置） ----------

    static DailyResult shopBuy(WjdrEngine engine) throws Exception {
        // 商品清单配置（买什么、钻石上限）在下一迭代接入；开启此任务前需要先做商品选择 UI。
        return new DailyResult("shop", "商店购买", DailyResult.Category.SKIPPED,
                "待配置商品清单后启用（防止误花钻石，默认关闭）");
    }

    /** 子项状态行写入助手。 */
    static void mod(WjdrEngine engine, String fnId, String subId, String text) {
        try {
            FnParams.saveModText(engine.appContext(), engine.lastFpid(), engine.lastServerId(),
                    fnId, subId, text);
        } catch (Throwable ignored) {
        }
    }

    static void debugLog(android.content.Context ctx, String msg) {
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "daily_log.txt"), true);
            w.write(System.currentTimeMillis() + " [dbg] " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    // ---------- 公共判定（根因修复核心） ----------

    /** 降级检测：未知协议 / 未映射会话 → 原版仅告警并假成功，这里明确降级。 */
    static boolean isDegraded(GameMessage msg) {
        if (msg == null || msg.getContent() == null) return true;
        return msg.getContent().containsKey("_unknown_proto_tag")
                || msg.getContent().containsKey("_unmapped_session");
    }

    /** 简单领取类通用分类：有 code 看 code，无 code 查证据字段；成功时顺带提取物品明细。 */
    static DailyResult classifySimple(GameMessage msg, String proto, String name, String[] evidenceKeys, String claimedMsg) {
        if (isDegraded(msg)) {
            return new DailyResult(proto, name, DailyResult.Category.DEGRADED, "响应异常（协议可能变更）");
        }
        Object code = msg.getContent().get("code");
        if (code != null && toInt(code) != 0) {
            return classifyServerCode(toInt(code), name);
        }
        for (String k : evidenceKeys) {
            if (msg.getContent().containsKey(k)) {
                DailyResult r = new DailyResult(proto, name, DailyResult.Category.CLAIMED, claimedMsg);
                r.rewards = RewardText.fromContent(msg.getContent());
                return r;
            }
        }
        return new DailyResult(proto, name, DailyResult.Category.DEGRADED, "响应缺少预期字段，未确认到账");
    }

    /** 生存物资类错误码的人类可读描述（6151/6158/6152）。 */
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

    /** 服务端业务错误码 → 合理跳过（带原因）或异常。码表来自原版各服务。 */
    static DailyResult classifyServerCode(int code, String name) {
        switch (code) {
            case 2:
                return new DailyResult("supply", name, DailyResult.Category.SKIPPED, "尚未到可领时间（冷却中）");
            case 6151:
                return new DailyResult("supply", name, DailyResult.Category.SKIPPED, "当前不可领取，等待下一窗口");
            case 6158:
                return new DailyResult("supply", name, DailyResult.Category.SKIPPED, "本窗口已领过");
            case 6152:
                return new DailyResult("supply", name, DailyResult.Category.SKIPPED, "体力补给不可领取");
            case 7501:
                return new DailyResult(name, name, DailyResult.Category.SKIPPED, "今日已领取或暂无可领奖励");
            case 4303:
                return new DailyResult(name, name, DailyResult.Category.SKIPPED, "暂无可领取的任务奖励");
            case 4004:
                return new DailyResult("adventure", name, DailyResult.Category.SKIPPED, "领取间隔未到");
            case 4003:
                return new DailyResult("adventure", name, DailyResult.Category.SKIPPED, "功能尚未解锁");
            case 21:
                return new DailyResult(name, name, DailyResult.Category.SKIPPED, "今日已领取或次数已用");
            case 7401: case 7404:
                return new DailyResult("activity", name, DailyResult.Category.SKIPPED, "活动未开启或已结束");
            case 40611: case 61:
                return new DailyResult("activity", name, DailyResult.Category.SKIPPED, "今日已领取");
            case 1201:
                return new DailyResult("mail", name, DailyResult.Category.SKIPPED, "邮件不存在（已被领）");
            case 1202:
                return new DailyResult("mail", name, DailyResult.Category.SKIPPED, "附件已领取");
            case 1204: case 1215:
                return new DailyResult("mail", name, DailyResult.Category.SKIPPED, "邮件状态不允许领取");
            case 40271:
                return new DailyResult(name, name, DailyResult.Category.SESSION_CLOSED, "被服务端强制下线");
            default:
                return new DailyResult(name, name, DailyResult.Category.ERROR, "服务端返回错误码 " + code);
        }
    }

    /** 异常分类：会话中断 → 可重试；其余 → ERROR（带消息）。 */
    static DailyResult classifyException(Throwable e, String name) {
        String msg = String.valueOf(e.getMessage());
        for (String mark : SESSION_CLOSED_MARKS) {
            if (msg.contains(mark)) {
                return new DailyResult(name, name, DailyResult.Category.SESSION_CLOSED,
                        "会话中断（可重试）：" + brief(msg));
            }
        }
        return new DailyResult(name, name, DailyResult.Category.ERROR,
                brief(e.getClass().getSimpleName() + ": " + msg));
    }

    static String brief(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    static String descRewards(Object rewards) {
        return RewardText.describe(rewards);
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

    private DailyTasks() {
    }
}
