package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 活动类任务（App 端 daily/ActivityTasks 逐行移植，改动须两侧同步）。
 * 活动窗口判定：以登录推送 notify 为准，无推送时请求探测并按错误码归为跳过。
 */
public final class ServerActivityTasks {

    private ServerActivityTasks() {
    }

    // ---------- 正中靶心（射气球） ----------

    public static final long BALLOON_ID = 2965001L;
    public static final int[] BALLOON_EXCHANGE = {5, 10, 30, 60, 90, 120, 150, 180, 210, 240, 270,
            300, 360, 420, 480, 540, 600, 700, 800, 1000};

    public static TaskOutcome balloon(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            ServerTownTasks.tryStep(tc, m, "签到奖励", "req_balloon_sign_reward_all",
                    Exec.args("id", BALLOON_ID));
            Map<String, Object> data = tc.engine.notifyData("notify_balloon_data");
            long coin = -1;
            if (data != null) {
                Map<String, Object> ex = Exec.mapOf(data.get("exchange"));
                if (ex != null) coin = Exec.longOf(ex.get("accumulate_coin_num"));
            }
            if (coin >= 5) {
                int exOk = 0;
                for (int t : BALLOON_EXCHANGE) {
                    if (coin < t) break;
                    GameMessage r = tc.engine.request("req_balloon_coin_exchange",
                            Exec.args("id", BALLOON_ID, "exchange_num", t));
                    if (Exec.ok(r)) {
                        exOk++;
                        m.rewards("兑换档" + t, r);
                    }
                }
                if (exOk > 0) m.claimed("兑换×" + exOk);
            }
            int games = 0;
            for (int round = 0; round < 3; round++) {
                GameMessage st = tc.engine.request("req_balloon_start_level",
                        Exec.args("id", BALLOON_ID, "type", 0));
                int code = Exec.codeOf(st);
                if (!Exec.ok(st)) {
                    if (round == 0) m.skip(code == 40501 || code == 40502 || code == 40503 ? "免费次数已用完或未开"
                            : code == 40504 ? "活动未开放" : "开局码" + code);
                    break;
                }
                long free = Exec.longOf(st.getContent().get("free_times"));
                int hits = 0;
                for (int idx = 1; idx <= 12; idx++) {
                    GameMessage h = tc.engine.request("req_balloon_hit",
                            Exec.args("id", BALLOON_ID, "balloon_index", idx));
                    if (Exec.ok(h)) hits++;
                    else if (Exec.codeOf(h) == 40507 || Exec.codeOf(h) == 40505) continue;
                }
                games++;
                if (free <= 0) break;
            }
            if (games > 0) m.claimed("免费局×" + games);
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "正中靶心");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_balloon", "正中靶心");
    }

    // ---------- 壶纳千祥 ----------

    public static final long[] PITCHPOT_IDS = {2436001L, 2436002L, 2436003L};

    public static TaskOutcome pitchpot(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Long openId = null;
            Map<String, Object> ni = tc.engine.notifyContent("notify_pitch_pot_info");
            if (ni != null) {
                long id = Exec.longOf(ni.get("id"));
                if (id > 0) openId = id;
            }
            for (long id : PITCHPOT_IDS) {
                if (openId != null && openId != id) continue;
                GameMessage st = tc.engine.request("req_pitch_pot_start", Exec.args("id", id));
                int code = Exec.codeOf(st);
                if (code == 40701) {
                    m.skip("有活动但无券");
                    break;
                }
                if (code == 40702 || code == 40707 || code == 7401 || code == 7404) {
                    if (openId == null) continue;
                    break;
                }
                if (!Exec.ok(st)) {
                    m.skip("开局码" + code);
                    break;
                }
                Exec.sleep(15000);   // 网页版：开局至少15秒后提交结算
                List<Map<String, Object>> achieve = new ArrayList<>();
                achieve.add(Exec.kv(3, 1));
                achieve.add(Exec.kv(6, 1));
                achieve.add(Exec.kv(5, 10));
                achieve.add(Exec.kv(7, 1));
                achieve.add(Exec.kv(1, 8));
                achieve.add(Exec.kv(2, 8));
                GameMessage se = tc.engine.request("req_pitch_pot_settle", Exec.args(
                        "id", id, "score", 4650L, "achieve", achieve,
                        "n_normal", 8L, "n_advance", 3L));
                if (Exec.ok(se)) {
                    m.claimed("满分结算(4650分)");
                    m.rewards("满分结算", se);
                }
                else m.fail("结算码" + Exec.codeOf(se));
                break;
            }
            Map<String, Object> ti = tc.engine.notifyContent("notify_pitch_pot_task_info");
            if (ti != null) {
                Map<String, Object> task = Exec.mapOf(ti.get("task"));
                List<Map<String, Object>> quests = task == null
                        ? new ArrayList<>() : Exec.mapsOf(task.get("quests"));
                long actId = Exec.longOf(ti.get("id"));
                for (Map<String, Object> q : quests) {
                    long qid = Exec.longOf(q.get("id"));
                    long prog = Exec.longOf(q.get("progress"));
                    if (qid <= 0 || actId <= 0 || prog < pitchpotTarget(qid)) continue;
                    GameMessage r = tc.engine.request("req_pitch_pot_task_reward",
                            Exec.args("id", actId, "task_id", qid));
                    if (Exec.ok(r)) {
                        m.claimed("任务" + qid);
                        m.rewards("任务" + qid, r);
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "壶纳千祥");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        if (m.sb.length() == 0) m.skip("活动未开放");
        return Exec.result(m, "act_pitchpot", "壶纳千祥");
    }

    private static long pitchpotTarget(long taskId) {
        int t = (int) taskId;
        switch (t) {
            case 1319520: case 1319521: case 1319526: case 1319527: case 1319528:
            case 1319531: case 1319537: case 1319538: case 1319543: case 1319544:
            case 1319545: case 1319548:
                return 1;
            case 1319522: case 1319539: return 3000;
            case 1319523: case 1319540: return 10000;
            case 1319524: case 1319541: return 30000;
            case 1319525: case 1319542: return 60000;
            case 1319529: case 1319532: case 1319546: case 1319549: return 10;
            case 1319530: case 1319547: return 30;
            default: return Long.MAX_VALUE;
        }
    }

    // ---------- 除雪小分队 ----------

    public static TaskOutcome snowclean(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> ni = tc.engine.notifyData("notify_snow_clean_info");
            long id = ni == null ? 2450001L : Exec.longOf(ni.get("id"));
            if (id <= 0) id = 2450001L;
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> tasks = Exec.mapOf(ni.get("tasks"));
                List<Map<String, Object>> quests = tasks == null
                        ? new ArrayList<>() : Exec.mapsOf(tasks.get("quests"));
                for (Map<String, Object> q : quests) {
                    long qid = Exec.longOf(q.get("id"));
                    if (qid <= 0) continue;
                    GameMessage r = tc.engine.request("req_snow_clean_task",
                            Exec.args("id", id, "task_id", qid));
                    if (Exec.ok(r)) {
                        m.claimed("任务" + qid);
                        m.rewards("任务" + qid, r);
                    }
                }
                ServerTownTasks.tryStep(tc, m, "冰雪基金", "req_snow_fund_get_all_reward", null);
                long mission = Exec.longOf(ni.get("mission"));
                int swept = 0;
                for (int i = 0; i < 30 && mission > 0; i++) {
                    GameMessage st = tc.engine.request("req_snow_clean_start",
                            Exec.args("id", id, "mission", mission));
                    int code = Exec.codeOf(st);
                    if (!Exec.ok(st) && code != 40084) {
                        if (i == 0) m.skip(code == 40077 ? "已全部通关" : "除雪码" + code);
                        break;
                    }
                    GameMessage sw = tc.engine.request("req_snow_clean_sweep",
                            Exec.args("id", id));
                    int sc = Exec.codeOf(sw);
                    if (Exec.ok(sw)) {
                        swept++;
                        mission++;
                    } else {
                        if (sc == 40085) m.skip("燃油不足");
                        else if (sc == 40077) m.claimed("全部通关");
                        break;
                    }
                }
                if (swept > 0) m.claimed("除雪×" + swept + "关");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "除雪小分队");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_snowclean", "除雪小分队");
    }

    // ---------- 冰封的宝藏 ----------

    public static TaskOutcome icebound(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> fs = tc.engine.notifyContent("notify_frozen_soil_info");
            if (fs != null) {
                long id = Exec.longOf(fs.get("id"));
                if (id > 0) {
                    ServerTownTasks.tryStep(tc, m, "特训任务奖励", "req_frozen_soil_get_all_reward",
                            Exec.args("id", id));
                    ServerTownTasks.tryStep(tc, m, "特训额外奖励", "req_frozen_soil_get_extra_reward",
                            Exec.args("id", id));
                }
            } else {
                m.skip("寻宝特训未开");
            }
            Map<String, Object> am = tc.engine.notifyContent("notify_icebound_amazing_info");
            if (am != null) {
                Map<String, Object> info = Exec.mapOf(am.get("info"));
                long aid = info == null ? 0 : Exec.longOf(Exec.nest(info, "base") != null
                        ? Exec.nest(info, "base").get("id") : info.get("id"));
                if (aid > 0) {
                    long progress = Exec.longOf(info.get("chest_progress"));
                    if (progress > 0) {
                        GameMessage pr = tc.engine.request("req_icebound_amazing_progress_reward",
                                Exec.args("progress", progress, "activity_id", aid));
                        if (Exec.ok(pr)) {
                            m.claimed("积分档位奖励");
                            m.rewards("积分档位", pr);
                        }
                    }
                    Map<String, Object> chest = Exec.mapOf(info.get("chest"));
                    if (chest != null && !chest.isEmpty()) {
                        List<Object> ids = new ArrayList<>(chest.keySet());
                        GameMessage oc = tc.engine.request("req_icebound_amazing_open_chest",
                                Exec.args("ids", ids, "activity_id", aid));
                        if (Exec.ok(oc)) {
                            m.claimed("宝箱×" + ids.size());
                            m.rewards("宝箱", oc);
                        }
                    }
                }
            } else {
                m.skip("宝藏活动未开");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "冰封的宝藏");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_icebound", "冰封的宝藏");
    }

    // ---------- 逐光之旅 ----------

    public static TaskOutcome polar(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> ni = tc.engine.notifyContent("notify_polar_expedition_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                long id = Exec.longOf(ni.get("activityId"));
                if (id <= 0) id = Exec.longOf(ni.get("id"));
                for (Map<String, Object> team : Exec.mapsOf(ni.get("team_infos"))) {
                    long tid = Exec.longOf(team.get("team_id"));
                    long state = Exec.longOf(team.get("state"));
                    if (tid <= 0) continue;
                    if (state == 2) {
                        GameMessage r = tc.engine.request("req_polar_expedition_get_chest",
                                Exec.args("id", id, "team_id", tid));
                        if (Exec.ok(r)) {
                            m.claimed("队列" + tid + "宝箱");
                            m.rewards("队列宝箱", r);
                        }
                    } else if (state == 0) {
                        GameMessage r = tc.engine.request("req_polar_expedition_start",
                                Exec.args("id", id, "team_id", tid));
                        if (Exec.ok(r)) m.claimed("队列" + tid + "已派遣");
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "逐光之旅");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_polar", "逐光之旅");
    }

    // ---------- 欢乐大乱斗 ----------

    public static TaskOutcome brawl(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            GameMessage ach = tc.engine.request("req_brawl_war_get_achievement_reward",
                    Exec.args("id", 2438001L));
            int code = Exec.codeOf(ach);
            if (Exec.ok(ach)) {
                m.claimed("成就奖励");
                m.rewards("成就奖励", ach);
            }
            else m.skip(code == 7401 || code == 7404 ? "活动未开放" : "成就码" + code);
            m.skip("战斗部分待开放");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "欢乐大乱斗");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_brawl", "欢乐乱斗");
    }

    // ---------- 钓鱼 ----------

    public static TaskOutcome fishing(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> ni = tc.engine.notifyContent("notify_fishing_activity_info");
            if (ni == null) ni = tc.engine.notifyContent("notify_fishing_info");
            if (ni == null) {
                m.skip("钓鱼活动未开放");
            } else {
                Long id = WjdrEngine.extractId(ni);
                if (id != null && id > 0) {
                    ServerTownTasks.tryStep(tc, m, "每日奖励", "req_fishing_get_daily_rewards",
                            Exec.args("id", id));
                    for (int type = 1; type <= 3; type++) {
                        ServerTownTasks.tryStep(tc, m, "任务奖励T" + type,
                                "req_fishing_get_all_task_reward",
                                Exec.args("id", id, "type", type));
                    }
                    ServerTownTasks.tryStep(tc, m, "俱乐趣奖励", "req_fishing_bp_get_all_reward",
                            Exec.args("id", id));
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "钓鱼");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_fishing", "钓鱼");
    }

    // ---------- 无尽征程 ----------

    public static TaskOutcome endlessJourney(TaskContext tc) {
        try {
            GameMessage rsp = tc.engine.request("req_general_bp_get_all_reward",
                    Exec.args("id", 2904006L));
            int code = Exec.codeOf(rsp);
            if (Exec.ok(rsp)) {
                TaskOutcome r = new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.CLAIMED,
                        "里程碑奖励已领取");
                r.rewards = com.carlos.vault.daily.RewardText.fromContent(rsp.getContent());
                return r;
            }
            if (code == 11723) {
                return new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.SKIPPED, "暂无可领奖励");
            }
            if (code == 11724) {
                return new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.SKIPPED, "今日已领取");
            }
            if (code == 11727) {
                return new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.SKIPPED, "积分不足");
            }
            if (code == 11728) {
                return new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.SKIPPED, "暂无可领（档位未达成）");
            }
            if (code == 7401 || code == 7404) {
                return new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.SKIPPED, "活动未开启");
            }
            return new TaskOutcome("act_endless", "无尽征程", TaskOutcome.Category.ERROR, "错误码 " + code);
        } catch (Exception e) {
            return ServerTasks.classifyException(e, "无尽征程");
        }
    }

    // ---------- 怪攻守城 ----------

    public static TaskOutcome joyAction(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> siege = tc.engine.notifyContent("notify_monster_siege_activity");
            if (siege == null) {
                m.skip("活动未开战");
            } else {
                m.skip("活动进行中（自动驻防待盟友配置）");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "怪攻守城");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_joy", "怪攻守城");
    }

    // ---------- 至尊盛宴 ----------

    public static TaskOutcome supremacyFeast(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> fi = tc.engine.notifyContent("notify_supremacy_war_feast_init");
            if (fi == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> act = Exec.mapOf(fi.get("activity"));
                long actId = act == null ? 0 : Exec.longOf(act.get("id"));
                if (actId <= 0) {
                    m.skip("活动数据异常");
                } else {
                    GameMessage search = tc.engine.request("req_world_search_supremacy_table",
                            Exec.args("act_id", actId));
                    Map<String, Object> pt = Exec.ok(search)
                            ? Exec.mapOf(search.getContent().get("point")) : null;
                    if (pt == null) {
                        m.skip("暂无空桌");
                    } else {
                        Map<String, Object> formation = Exec.formation(tc.engine, 1);
                        Map<String, Object> extra = Exec.args(
                                "dinner_act_id", actId, "dinner_table_id", 0L);
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", 1002L, "endpoint", pt, "formation", formation,
                                "extra", extra, "MarchMapType", 1L));
                        if (Exec.marchId(march) > 0) m.claimed("已赴宴");
                        else m.skip("赴宴失败(码" + Exec.codeOf(march) + ")");
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "至尊盛宴");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_feast", "至尊盛宴");
    }

    // ---------- 红包 ----------

    public static TaskOutcome redPacket(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            for (int channel = 1; channel <= 2; channel++) {
                GameMessage rf = tc.engine.request("req_red_packet_refresh",
                        Exec.args("channel_type", channel, "id_lst", new ArrayList<>()));
                if (!Exec.ok(rf)) {
                    int code = Exec.codeOf(rf);
                    m.skip(channel == 1 ? "联盟" : "世界" + (code == 16873 ? "无联盟" : "刷新码" + code));
                    continue;
                }
                int got = 0;
                for (Map<String, Object> p : Exec.mapsOf(rf.getContent().get("lst"))) {
                    long pid = Exec.longOf(p.get("id"));
                    long idx = Exec.longOf(p.get("index"));
                    if (pid <= 0) continue;
                    GameMessage g = tc.engine.request("req_red_packet_get", Exec.args(
                            "channel_type", channel, "id", pid, "index", idx));
                    int code = Exec.codeOf(g);
                    if (Exec.ok(g)) {
                        got++;
                        m.rewards((channel == 1 ? "联盟" : "世界") + "红包", g);
                    }
                    else if (code == 16876) {
                        m.skip("今日配额已满");
                        return Exec.result(m, "red_packet", "游戏红包");
                    }
                }
                if (got > 0) m.claimed((channel == 1 ? "联盟" : "世界") + "×" + got);
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "游戏红包");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "red_packet", "游戏红包");
    }

    // ---------- 钻石银行 ----------

    public static TaskOutcome bank(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> ri = tc.engine.notifyContent("notify_reward_info");
            Map<String, Object> bank = ri == null ? null : Exec.mapOf(ri.get("bank"));
            Map<String, Object> use = bank == null ? null : Exec.mapOf(bank.get("use_locker"));
            long lockerId = use == null ? 0 : Exec.longOf(use.get("locker_id"));
            long amount = use == null ? 0 : Exec.longOf(use.get("amount"));
            long expire = use == null ? 0 : Exec.longOf(use.get("expire_time"));
            if (use == null || amount <= 0) {
                m.skip("无在存订单（默认不新增存款）");
            } else if (expire > 0 && expire * 1000L <= System.currentTimeMillis() + 2000) {
                GameMessage wd = tc.engine.request("req_bank_withdraw", null);
                if (Exec.ok(wd)) {
                    m.claimed("取款" + amount);
                    m.rewards("取款", wd);
                    long reDep = amount / 100 * 100;
                    if (reDep >= 100) {
                        GameMessage dp = tc.engine.request("req_bank_deopsit",
                                Exec.args("locker_id", lockerId, "amount", reDep));
                        if (Exec.ok(dp)) m.claimed("续存" + reDep);
                        else m.skip("续存码" + Exec.codeOf(dp));
                    } else {
                        // G14（2026-11-01）：余额不足 100 不续存时旧实现无任何记录，
                        // 巡检看不到“取到了但没续存”，与成功后静默无法区分。
                        m.skip("余额不足100不续存（可取" + amount + "）");
                    }
                } else {
                    int code = Exec.codeOf(wd);
                    m.skip(code == 6157 ? "未到期" : "取款码" + code);
                }
            } else {
                m.skip("存款未到期");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "钻石银行");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "bank", "钻石银行");
    }

    // ---------- 佣兵荣耀 ----------

    public static TaskOutcome mercenary(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> ni = tc.engine.notifyData("notify_mercenary_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> base = Exec.mapOf(ni.get("base"));
                long id = base == null ? Exec.longOf(ni.get("id"))
                        : Exec.longOf(base.get("id"));
                long maxDiff = 0;
                for (String k : new String[]{"maxDifficulty", "max_difficulty", "difficulty"}) {
                    Object v = base != null ? base.get(k) : ni.get(k);
                    if (v instanceof Number) {
                        maxDiff = Exec.longOf(v);
                        break;
                    }
                }
                if (id <= 0) {
                    m.skip("活动数据异常");
                } else {
                    long diff = Math.min(20, maxDiff + 1);
                    tc.engine.request("req_mercenary_choose_difficulty",
                            Exec.args("id", id, "difficulty", diff));
                    GameMessage sp = tc.engine.request("req_mercenary_spawn_player_monster",
                            Exec.args("id", id));
                    if (Exec.ok(sp)) {
                        Map<String, Object> boss = Exec.mapOf(sp.getContent().get("boss_data"));
                        long x = boss == null ? 0 : Exec.longOf(boss.get("x"));
                        long y = boss == null ? 0 : Exec.longOf(boss.get("y"));
                        long mid = boss == null ? 0 : Exec.longOf(boss.get("id"));
                        Map<String, Object> formation = Exec.formation(tc.engine, 1);
                        if (x != 0 || y != 0) {
                            GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                    "type", 502L, "endpoint", Exec.point(x, y),
                                    "formation", formation,
                                    "extra", Exec.args("monsterid", mid)));
                            if (Exec.marchId(march) > 0) m.claimed("已出征佣兵(难度" + diff + ")");
                            else m.skip("出征码" + Exec.codeOf(march));
                        } else {
                            m.skip("未取到佣兵坐标");
                        }
                    } else {
                        int code = Exec.codeOf(sp);
                        m.skip(code == 21 ? "个人怪已全部通关" : code == 51 ? "体力不足" : "生成码" + code);
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "佣兵荣耀");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_mercenary", "佣兵荣耀");
    }

    // ---------- 运宝车队 ----------

    public static TaskOutcome truckEscort(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> ni = tc.engine.notifyData("notify_truck_escort_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> base = Exec.mapOf(ni.get("base"));
                long id = base == null ? Exec.longOf(ni.get("id")) : Exec.longOf(base.get("id"));
                int taken = 0;
                for (Map<String, Object> t : Exec.mapsOf(ni.get("truck_infos"))) {
                    long tid = Exec.longOf(t.get("truck_id"));
                    if (tid <= 0) tid = Exec.longOf(t.get("slot_id"));
                    boolean finish = Exec.longOf(t.get("is_finish")) == 1
                            || Exec.longOf(t.get("status")) == 2;
                    if (!finish) continue;
                    GameMessage r = tc.engine.request("req_truck_escort_take_truck_reward",
                            Exec.args("id", id, "truck_id", tid));
                    if (Exec.ok(r)) {
                        taken++;
                        m.rewards("货车奖励", r);
                    }
                }
                if (taken > 0) m.claimed("货车奖励×" + taken);
                else m.skip("暂无送达货车");
                Map<String, Object> send = Exec.mapOf(ni.get("send_truck_info"));
                long stage = send == null ? 0 : Exec.longOf(send.get("stage_index"));
                if (send != null && stage >= 0) {
                    List<Long> heroes = ServerTownTasks.squadHeroes(tc.engine);
                    if (!heroes.isEmpty()) {
                        Map<String, Object> formation = Exec.args(
                                "hero_id", java.util.Collections.singletonList(Exec.heroPair(1L, heroes.get(0))),
                                "soldier", new ArrayList<>());
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", 1201L, "endpoint", Exec.point(0, 0),
                                "formation", formation,
                                "extra", Exec.args("event_id", id)));
                        if (Exec.marchId(march) > 0) m.claimed("已发车");
                        else if (Exec.codeOf(march) != 12706) m.skip("发车码" + Exec.codeOf(march));
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "运宝车队");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_truck", "运宝车队");
    }

    // ---------- 冰原猎人 ----------

    public static TaskOutcome icefieldHunter(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            Map<String, Object> ni = tc.engine.notifyContent("notify_icefield_hunter2_info");
            if (ni == null) ni = tc.engine.notifyContent("notify_icefield_hunter_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> base = Exec.mapOf(ni.get("base"));
                long id = base == null ? Exec.longOf(ni.get("id")) : Exec.longOf(base.get("id"));
                if (id <= 0) {
                    m.skip("活动数据异常");
                } else {
                    GameMessage col = tc.engine.request("req_icefield_hunter2_collect_master_reward",
                            Exec.args("id", id, "reward_id", 0L, "is_gain_all", true));
                    int code = Exec.codeOf(col);
                    if (Exec.ok(col)) {
                        m.claimed("进度奖励");
                        m.rewards("进度奖励", col);
                    }
                    else if (code != 61 && code != 8251) m.skip("领奖码" + code);

                    GameMessage sp = tc.engine.request("req_icefield_hunter_spawn_monster",
                            Exec.args("item_id", 600008L));
                    if (Exec.ok(sp)) {
                        Map<String, Object> boss = Exec.mapOf(sp.getContent().get("boss_data"));
                        long x = boss == null ? 0 : Exec.longOf(boss.get("x"));
                        long y = boss == null ? 0 : Exec.longOf(boss.get("y"));
                        long mid = boss == null ? 0 : Exec.longOf(boss.get("id"));
                        if (x != 0 || y != 0) {
                            Map<String, Object> formation = Exec.formation(tc.engine, 1);
                            GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                    "type", 501L, "endpoint", Exec.point(x, y),
                                    "formation", formation,
                                    "extra", Exec.args("monsterid", mid, "prepare_time_index", 2L)));
                            if (Exec.marchId(march) > 0) m.claimed("已集结BOSS");
                            else m.skip("集结码" + Exec.codeOf(march));
                        }
                    } else {
                        int sc = Exec.codeOf(sp);
                        m.skip(sc == 51 ? "零件道具不足" : "生成码" + sc);
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "冰原猎人");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_icehunter", "冰原猎人");
    }
}
