package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import org.json.JSONArray;
import org.json.JSONObject;

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

    // ---------- 雪原商路（运宝车队玩法） ----------

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
                // 2026-11-09（用户口径）：刷新优先消耗「任务送的刷新券」(use_diamond=false)，
                // 刷新券用尽才花钻石刷新（use_diamond=true，价格 100/200…逐次叠加，受预算 diamond_refresh_max）。
                // 每辆车循环刷新直到品质 >= target_quality 或券/预算用尽。
                // 2026-11-09（用户口径 + 审查修复）：刷新优先消耗任务券(use_diamond=false)，
                // 券连续失败 2 次才转钻石；钻石按金额累计（第 1 次 100 钻、第 2 次 200…），
                // 受 diamond_refresh_max（钻石金额）上限；品质连续 2 次不变即停，避免空耗。
                if (tc.subOn("act_truck", "refresh", false)) {
                    int targetQuality = Math.max(1, tc.params("act_truck").optInt("target_quality", 5));
                    long maxDiamond = Math.max(0L, tc.params("act_truck").optLong("diamond_refresh_max", 0L));
                    int refreshed = 0;
                    int diamondUsed = 0;
                    long diamondSpent = 0L;
                    for (Map<String, Object> t : Exec.mapsOf(ni.get("truck_infos"))) {
                        long tid = Exec.longOf(t.get("truck_id"));
                        if (tid <= 0) tid = Exec.longOf(t.get("slot_id"));
                        if (tid <= 0) continue;
                        boolean finish = Exec.longOf(t.get("is_finish")) == 1
                                || Exec.longOf(t.get("status")) == 2;
                        if (finish) continue;
                        long quality = Exec.longOf(t.get("quality"));
                        int failed = 0;
                        int unchanged = 0;
                        for (int round = 0; round < 30 && quality < targetQuality; round++) {
                            GameMessage rr = tc.engine.request("req_truck_escort_refresh_truck_reward",
                                    Exec.args("id", id, "truck_id", tid, "use_diamond", false));
                            if (Exec.ok(rr)) {
                                long q2 = qualityOf(rr, quality);
                                if (q2 <= quality) {
                                    if (++unchanged >= 2) break;
                                } else {
                                    unchanged = 0;
                                }
                                quality = q2;
                                refreshed++;
                                failed = 0;
                                continue;
                            }
                            // 任务券刷新失败：连续 2 次才视为券用完 → 钻石刷新（按金额预算）
                            if (++failed < 2) continue;
                            if (maxDiamond <= 0L) {
                                m.skip("刷新券不足（未配置钻石预算），停止刷新");
                                break;
                            }
                            long price = 100L * (diamondUsed + 1);   // 100/200/300…
                            if (diamondSpent + price > maxDiamond) {
                                m.skip("钻石预算不足（已花 " + diamondSpent + " 钻），停止刷新");
                                break;
                            }
                            GameMessage rd = tc.engine.request("req_truck_escort_refresh_truck_reward",
                                    Exec.args("id", id, "truck_id", tid, "use_diamond", true));
                            if (!Exec.ok(rd)) {
                                m.skip("钻石刷新未通过(码" + Exec.codeOf(rd) + ")");
                                break;
                            }
                            diamondSpent += price;
                            diamondUsed++;
                            quality = qualityOf(rd, quality);
                            refreshed++;
                        }
                    }
                    if (refreshed > 0) {
                        m.claimed("刷新货车×" + refreshed
                                + (diamondUsed > 0 ? "（钻石×" + diamondUsed + " · " + diamondSpent + "钻）" : ""));
                    }
                }
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
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "雪原商路");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_truck", "雪原商路");
    }

    /** 刷新响应里的货车品质（缺失时保留原值）。 */
    private static long qualityOf(GameMessage rsp, long fallback) {
        try {
            Map<String, Object> ti = Exec.mapOf(rsp.getContent().get("truck_info"));
            long q = ti == null ? 0L : Exec.longOf(ti.get("quality"));
            return q > 0L ? q : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---------- 雪域兵器联赛 / 冰火战歌 ----------

    /** 雪域兵器联赛：领联盟周常登录奖励（竞猜默认不自动下注）。 */
    public static TaskOutcome arsenalLeague(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            GameMessage login = tc.engine.request("req_alliance_arsenal_league_login_weekly", null);
            if (Exec.ok(login)) {
                m.claimed("雪域兵器联赛登录奖励");
                m.rewards("登录奖励", login);
            } else {
                m.skip("登录奖励未领(码" + Exec.codeOf(login) + ")");
            }
            // P2-4：雪域兵器联赛竞猜下注（默认关；App 子项 bet 或 auto_bet=true 才执行）
            JSONObject p = tc.params("act_arsenal_league");
            JSONObject strategy = p.optJSONObject("strategy");
            boolean autoBet = tc.subOn("act_arsenal_league", "bet", false)
                    || p.optBoolean("auto_bet", false);
            if (autoBet) {
                Map<String, Object> gi = tc.engine.notifyData("notify_arsenal_league_guess_info");
                long guessId = gi == null ? 0L : Exec.longOf(gi.get("id"));
                long groupId = gi == null ? 0L : Exec.longOf(gi.get("group_id"));
                if (guessId > 0L && groupId > 0L) {
                    // 2026-11-09（用户策略）：每场上限 10000 竞猜币；选「双方实力差距最大」的前 3 场拉满；
                    // 票不够时按差距顺序依次下注，下注失败即停（不再继续后续场次）。
                    // 用户口径：选差距最大的三场，每场「拉满一万」（上限 10000）。
                    long perBet = 10000L;
                    int fixedSide = p.optInt("bet_side", 0);
                    if (fixedSide != 1 && fixedSide != 2) fixedSide = 0;   // 非法值按自动选强侧
                    GameMessage bi = tc.engine.request("req_arsenal_league_guess_get_winner_bet_info",
                            Exec.args("id", guessId, "group_id", groupId, "matches", List.of(1L)));
                    if (Exec.ok(bi)) {
                        Map<String, Object> infos = Exec.mapOf(bi.getContent().get("infos"));
                        List<long[]> cands = new ArrayList<long[]>();   // [matchId, gap, side]
                        if (infos != null) {
                            for (Map.Entry<String, Object> ie : infos.entrySet()) {
                                Map<String, Object> inf = Exec.mapOf(ie.getValue());
                                long mid = inf == null ? 0L : Exec.longOf(inf.get("match_id"));
                                if (mid <= 0L) mid = Exec.longOf(ie.getKey());
                                Map<String, Object> match = inf == null ? null : Exec.mapOf(inf.get("match"));
                                Map<String, Object> sides = match == null ? null : Exec.mapOf(match.get("sides"));
                                long p1 = 0L, p2 = 0L;
                                if (sides != null) {
                                    Map<String, Object> s1 = Exec.mapOf(sides.get("1"));
                                    Map<String, Object> s2 = Exec.mapOf(sides.get("2"));
                                    p1 = s1 == null ? 0L : Exec.longOf(s1.get("power"));
                                    p2 = s2 == null ? 0L : Exec.longOf(s2.get("power"));
                                }
                                if (mid <= 0L) continue;
                                int side = fixedSide > 0 ? fixedSide : (p1 >= p2 ? 1 : 2);
                                cands.add(new long[]{mid, Math.abs(p1 - p2), side});
                            }
                        }
                        java.util.Collections.sort(cands, new java.util.Comparator<long[]>() {
                            @Override
                            public int compare(long[] a, long[] b) {
                                return Long.compare(b[1], a[1]);
                            }
                        });
                        JSONObject betState = tc.modState("act_arsenal_league");
                        int placed = 0;
                        for (long[] c : cands) {
                            if (placed >= 3) break;   // 最多 3 场
                            String betKey = "bet_" + guessId + "_" + groupId + "_" + c[0];
                            if (betState.optLong(betKey, 0L) > 0L) continue;   // 幂等
                            GameMessage bet = tc.engine.request("req_arsenal_league_guess_bet_winner",
                                    Exec.args("id", guessId, "group_id", groupId,
                                            "match_id", c[0], "side", c[2], "num", perBet));
                            if (!Exec.ok(bet)) break;   // 票不足/失败：按差距顺序停止
                            tc.saveModText("act_arsenal_league", betKey,
                                    String.valueOf(System.currentTimeMillis()));
                            placed++;
                            m.claimed("竞猜下注 " + perBet + "（match " + c[0] + " side " + c[2]
                                    + " 差距 " + c[1] + "）");
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "雪域兵器联赛");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_arsenal_league", "雪域兵器联赛");
    }

    /** 冰火战歌：领取所有已完成任务奖励 + 点赞（幂等）。 */
    public static TaskOutcome slgLeague(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> init = tc.engine.notifyData("notify_slg_3_league_info_init");
            // P2-5：已领任务缓存，避免每轮对同一批任务重复请求
            JSONObject st = tc.modState("act_slg_league");
            JSONObject claimedIds = new JSONObject();
            try {
                String raw = st.optString("claimed_ids", "");
                if (!raw.isEmpty()) claimedIds = new JSONObject(raw);
            } catch (Throwable ignored) {
            }
            int claimed = 0;
            if (init != null) {
                Map<String, Object> task = Exec.mapOf(init.get("task"));
                Map<String, Object> data = task == null ? null : Exec.mapOf(task.get("data"));
                Map<String, Object> quests = data == null ? null : Exec.mapOf(data.get("quests"));
                if (quests != null) {
                    for (Map.Entry<String, Object> e : quests.entrySet()) {
                        Map<String, Object> q = Exec.mapOf(e.getValue());
                        long taskId = q == null ? 0L : Exec.longOf(q.get("id"));
                        if (taskId <= 0L) taskId = Exec.longOf(e.getKey());
                        if (taskId <= 0L) continue;
                        if (claimedIds.has(String.valueOf(taskId))) continue;
                        GameMessage r = tc.engine.request("req_slg_3_league_get_task_reward",
                                Exec.args("task_id", taskId));
                        if (Exec.ok(r)) {
                            claimed++;
                            claimedIds.put(String.valueOf(taskId), 1);
                            m.rewards("联赛任务", r);
                        }
                    }
                }
            }
            // P2-5：修剪缓存，避免长期累积（保留最近 60 条）
            if (claimedIds.length() > 60) {
                java.util.List<String> keys = new ArrayList<>();
                java.util.Iterator<String> it = claimedIds.keys();
                while (it.hasNext()) keys.add(it.next());
                JSONObject trimmed = new JSONObject();
                for (int i = Math.max(0, keys.size() - 60); i < keys.size(); i++) {
                    trimmed.put(keys.get(i), 1);
                }
                claimedIds = trimmed;
            }
            if (claimed > 0) tc.saveModText("act_slg_league", "claimed_ids", claimedIds.toString());
            if (claimed > 0) m.claimed("冰火战歌任务×" + claimed);
            else m.skip("无未领任务奖励");
            if (tc.subOn("act_slg_league", "like", true)) {
                GameMessage like = tc.engine.request("req_slg_3_league_like", Exec.args("rank", 1L));
                if (Exec.ok(like)) m.claimed("点赞");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "冰火战歌");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_slg_league", "冰火战歌");
    }

    // ---------- 最强王国（部队复活） ----------

    /** 最强王国：活动期检测到阵亡部队时 req_cross_capital_war_revive {} 全复活 + 请求联盟帮助。 */
    public static TaskOutcome crossCapitalRevive(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            if (!tc.subOn("act_cross_capital_revive", "revive", true)) {
                m.skip("子项「自动复活」已关闭");
                return Exec.result(m, "act_cross_capital_revive", "最强王国复活");
            }
            tc.engine.collectNotifies(1500);
            Map<String, Object> init = tc.engine.notifyData("notify_cross_capital_war_init");
            boolean inActivity = false;
            boolean hasDead = false;
            if (init != null) {
                Map<String, Object> activity = Exec.mapOf(init.get("activity"));
                long stage = activity == null ? 0L : Exec.longOf(activity.get("stage"));
                // P2-6：stage_ts 最后阶段 + 1 天缓冲，避免活动结束后仍反复尝试复活
                long lastStageTs = 0L;
                java.util.List<Object> stageTs = activity == null ? null : Exec.listOf(activity.get("stage_ts"));
                if (stageTs != null) {
                    for (Object o : stageTs) {
                        long ts = Exec.longOf(o);
                        if (ts > lastStageTs) lastStageTs = ts;
                    }
                }
                long nowSec = System.currentTimeMillis() / 1000L;
                inActivity = stage > 0L && (lastStageTs <= 0L || nowSec <= lastStageTs + 86400L);
                Map<String, Object> player = Exec.mapOf(init.get("player"));
                Map<String, Object> revive = player == null ? null : Exec.mapOf(player.get("revive_data"));
                Map<String, Object> dead = revive == null ? null : Exec.mapOf(revive.get("dead_map"));
                hasDead = dead != null && !dead.isEmpty();
            }
            if (!inActivity) {
                m.skip("最强王国未开启");
            } else if (!hasDead) {
                m.skip("无阵亡部队");
            } else {
                GameMessage r = tc.engine.request("req_cross_capital_war_revive", null);
                if (Exec.ok(r)) {
                    m.claimed("部队复活（免费 30%）");
                    m.rewards("复活", r);
                } else {
                    m.skip("复活未成功(码" + Exec.codeOf(r) + ")");
                }
                // 2026-11-09（抓包校准）：总可复活 90% = 无条件 30% + 盟友援助 50%
                // + 钻石复活药剂 10%（每份 1%，第 1 份 50 钻、第 2 份 100…每次 +50 钻）。
                // 抓包：buy_medkit{count:1} → use_medkit_count 累加；药剂先买、再 revive 统一消耗。
                boolean buyDiamond = tc.subOn("act_cross_capital_revive", "buy_diamond", false);
                int maxMedkits = 10;
                try {
                    JSONObject cp = tc.params("act_cross_capital_revive");
                    if (cp != null && cp.has("diamond_medkits")) {
                        maxMedkits = Math.max(0, Math.min(10, cp.optInt("diamond_medkits", 10)));
                    }
                } catch (Throwable ignored) {
                }
                int needMedkits = buyDiamond ? medkitNeed(init, maxMedkits) : 0;
                int bought = 0;
                long diamondSpent = 0L;
                for (int i = 0; i < needMedkits; i++) {
                    long price = 50L * (i + 1);   // 50/100/150/…
                    GameMessage mk = tc.engine.request("req_cross_capital_war_buy_medkit",
                            Exec.args("count", 1));
                    if (!Exec.ok(mk)) {
                        m.skip("钻石购买复活药剂失败(码" + Exec.codeOf(mk) + ")，已买 "
                                + bought + " 份（" + diamondSpent + " 钻）");
                        break;
                    }
                    bought++;
                    diamondSpent += price;
                }
                if (bought > 0) {
                    GameMessage again = tc.engine.request("req_cross_capital_war_revive", null);
                    if (Exec.ok(again)) {
                        m.claimed("钻石复活药剂×" + bought + "（+" + bought + "% · 累计 "
                                + diamondSpent + " 钻）");
                    } else {
                        m.skip("购买药剂后复活失败(码" + Exec.codeOf(again) + ")");
                    }
                }
                GameMessage help = tc.engine.request(
                        "req_cross_capital_war_revive_call_alliance_help", null);
                if (Exec.ok(help)) m.claimed("已请求联盟帮助");
                // 2026-11-09（用户要求）：自动援助盟友复活——抓包协议：
                // req_cross_capital_war_get_user_revive_data {uids} 查复活数据 →
                // req_cross_capital_war_help_revive {uid,times} 对阵亡最多的盟友逐个援助。
                helpReviveAllies(tc, m, init);
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "最强王国复活");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "act_cross_capital_revive", "最强王国复活");
    }

    /** 按当前复活比例（revive_map/dead_map）计算还需几份钻石药剂补到 90%（0..maxNeed）。 */
    static int medkitNeed(Map<String, Object> init, int maxNeed) {
        try {
            Map<String, Object> player = Exec.mapOf(init == null ? null : init.get("player"));
            Map<String, Object> revive = player == null ? null : Exec.mapOf(player.get("revive_data"));
            if (revive == null) return 0;
            double dead = sumMap(revive.get("dead_map"));
            double revived = sumMap(revive.get("revive_map"));
            if (dead <= 0.0) return 0;
            double needPct = 0.90 - revived / dead;
            if (needPct <= 0.0) return 0;
            return Math.max(0, Math.min(maxNeed, (int) Math.ceil(needPct * 100.0)));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** {k:{k,v:数量}} 求和。 */
    private static double sumMap(Object raw) {
        Map<String, Object> m = Exec.mapOf(raw);
        if (m == null) return 0.0;
        double total = 0.0;
        for (Object v : m.values()) {
            Map<String, Object> row = Exec.mapOf(v);
            if (row != null) total += Exec.longOf(row.get("v"));
        }
        return total;
    }

    /** 最多援助的盟友数 / 每次援助次数（抓包样本 times=5）。 */
    private static final int HELP_REVIVE_MAX_ALLIES = 10;
    private static final int HELP_REVIVE_TIMES = 5;
    private static final int HELP_REVIVE_BATCH = 100;

    /**
     * 援助盟友复活（2026-11-09，抓包协议）：联盟成员 → get_user_revive_data 批量查 →
     * 按 total_dead_soldier 降序对未援助过的盟友发 help_revive {uid,times}。
     */
    private static void helpReviveAllies(TaskContext tc, Exec.Multi m, Map<String, Object> init) {
        // 2026-11-11 默认开启策略：援助盟友消耗「复活币」（道具），默认关；用户显式开才执行。
        if (!tc.subOn("act_cross_capital_revive", "help_revive", false)) return;
        try {
            Map<String, Object> membersRoot = Exec.mapOf(tc.engine.notifyData("notify_alliance_member_init"));
            Map<String, Object> members = membersRoot == null ? null : Exec.mapOf(membersRoot.get("members"));
            if (members == null || members.isEmpty()) return;
            java.util.List<Long> uids = new java.util.ArrayList<Long>();
            for (Object o : members.values()) {
                Map<String, Object> row = Exec.mapOf(o);
                long uid = row == null ? 0L : Exec.longOf(row.get("uid"));
                if (uid > 0L) uids.add(uid);
            }
            if (uids.isEmpty()) return;
            // 本场活动的援助台账（stage_ts 最小值作为场次键）
            long window = 0L;
            Map<String, Object> activity = Exec.mapOf(init == null ? null : init.get("activity"));
            java.util.List<Object> stageTs = activity == null ? null : Exec.listOf(activity.get("stage_ts"));
            if (stageTs != null) {
                for (Object o : stageTs) {
                    long ts = Exec.longOf(o);
                    if (ts > 0L && (window == 0L || ts < window)) window = ts;
                }
            }
            // 援助盟友消耗自己的「复活币」（抓包：初始 20，help_revive times=5 后 15）。
            // 用户口径：援助盟友复活只有 20 个复活币可用——总援助次数不得超过剩余复活币。
            // 复活币预算：字段存在时以真实值（可为 0=已用完）为准；字段缺失才默认 20。
            long budget = 20L;
            Map<String, Object> player = Exec.mapOf(init == null ? null : init.get("player"));
            Map<String, Object> revive = player == null ? null : Exec.mapOf(player.get("revive_data"));
            if (revive != null && revive.containsKey("revive_coupon")) {
                budget = Math.max(0L, Exec.longOf(revive.get("revive_coupon")));
            }
            String ledgerKey = "help_" + window;
            java.util.Set<Long> helped = new java.util.LinkedHashSet<Long>();
            try {
                for (String part : tc.modState("act_cross_capital_revive")
                        .optString(ledgerKey, "").split(",")) {
                    if (!part.trim().isEmpty()) helped.add(Long.parseLong(part.trim()));
                }
            } catch (Throwable ignored) {
            }
            // 批量查复活数据 → 收集阵亡数
            java.util.List<long[]> cands = new java.util.ArrayList<long[]>();   // [uid, dead]
            for (int from = 0; from < uids.size(); from += HELP_REVIVE_BATCH) {
                int to = Math.min(uids.size(), from + HELP_REVIVE_BATCH);
                GameMessage live = tc.engine.request("req_cross_capital_war_get_user_revive_data",
                        Exec.args("uids", new java.util.ArrayList<Long>(uids.subList(from, to))));
                if (!Exec.ok(live)) continue;
                Map<String, Object> dataMap = Exec.mapOf(live.getContent().get("data_map"));
                if (dataMap == null) continue;
                for (Map.Entry<String, Object> e : dataMap.entrySet()) {
                    Map<String, Object> row = Exec.mapOf(e.getValue());
                    long uid = row == null ? 0L : Exec.longOf(row.get("uid"));
                    if (uid <= 0L) uid = Exec.longOf(e.getKey());
                    long dead = row == null ? 0L : Exec.longOf(row.get("total_dead_soldier"));
                    if (uid > 0L && dead > 0L) {
                        cands.add(new long[]{uid, dead});
                    }
                }
            }
            if (cands.isEmpty()) return;
            // 阵亡最多优先
            java.util.Collections.sort(cands, new java.util.Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) {
                    return Long.compare(b[1], a[1]);
                }
            });
            // 多轮援助：每轮每个盟友最多帮一次（times=5），币够可继续下一轮
            // （20 币 ≈ 4 轮），直到预算耗尽或候选帮完；2026-11-09 审查修复：
            // 旧实现整场只帮同一盟友一次，20 币用不完。
            StringBuilder ledger = new StringBuilder();
            int assisted = 0;
            long remaining = Math.max(0L, budget);
            for (int round = 0; round < 4 && remaining > 0L; round++) {
                boolean any = false;
                for (long[] c : cands) {
                    if (assisted >= HELP_REVIVE_MAX_ALLIES || remaining <= 0L) break;
                    long times = Math.min(HELP_REVIVE_TIMES, remaining);
                    if (times <= 0L) break;
                    GameMessage rsp = tc.engine.request("req_cross_capital_war_help_revive",
                            Exec.args("uid", c[0], "times", times));
                    if (!Exec.ok(rsp)) continue;
                    remaining -= times;
                    assisted++;
                    any = true;
                    if (ledger.length() > 0) ledger.append(',');
                    ledger.append(c[0]);
                    long suc = Exec.longOf(rsp.getContent().get("suc_times"));
                    m.claimed("援助盟友复活 uid=" + c[0] + " 次数=" + (suc > 0L ? suc : times)
                            + "（复活币剩余 " + remaining + "）");
                }
                if (!any) break;
            }
            if (ledger.length() > 0) {
                String prev = tc.modState("act_cross_capital_revive").optString(ledgerKey, "");
                String next = prev.isEmpty() ? ledger.toString() : prev + "," + ledger;
                tc.saveModText("act_cross_capital_revive", ledgerKey, next);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------- 联盟/同账号离线联动 ----------

    /** 离线联动（2026-11-08）：从战争/态势通知自动发现被攻击盟友 → 查在线 → 离线顶号开盾。 */
    public static TaskOutcome allyOfflineGuard(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            boolean watch = tc.subOn("ally_offline_guard", "watch", true);
            boolean force = tc.subOn("ally_offline_guard", "force", false);
            if (!watch && !force) {
                m.skip("离线联动子项已关闭");
                return Exec.result(m, "ally_offline_guard", "离线联动");
            }
            tc.engine.collectNotifies(1500);
            // 1) 从战争/态势通知自动发现被攻击盟友（无需用户输入 uid）
            List<Long> uids = situationTargetUids(tc);
            if (uids.isEmpty()) {
                m.skip("最近无盟友被攻击的战争/态势通知");
                return Exec.result(m, "ally_offline_guard", "离线联动");
            }
            if (uids.size() > 20) uids = new ArrayList<>(uids.subList(0, 20));
            GameMessage r = tc.engine.request("req_players_base_info_with_permission",
                    Exec.args("uids", new ArrayList<>(uids), "permission", 1L));
            if (!Exec.ok(r)) {
                m.skip("查询失败(码" + Exec.codeOf(r) + ")");
                return Exec.result(m, "ally_offline_guard", "离线联动");
            }
            Map<String, Object> result = r.getContent() == null ? null
                    : Exec.mapOf(r.getContent().get("result"));
            int online = 0;
            int offline = 0;
            List<Long> offlineUids = new ArrayList<>();
            if (result != null) {
                for (Map.Entry<String, Object> e : result.entrySet()) {
                    Map<String, Object> info = Exec.mapOf(e.getValue());
                    if (info == null || !info.containsKey("offline_time")) continue;
                    long offlineTime = Exec.longOf(info.get("offline_time"));
                    if (offlineTime == 0L) {
                        online++;
                    } else {
                        offline++;
                        long uid = Exec.longOf(info.get("uid"));
                        if (uid > 0L) offlineUids.add(uid);
                    }
                }
            }
            m.subOk("watch", "被攻击盟友 " + uids.size() + " · 在线 " + online + " / 离线 " + offline);
            m.claimed("离线联动：在线 " + online + " / 离线 " + offline);
            tc.saveModText("ally_offline_guard", "watch",
                    "被攻击盟友 " + uids.size() + " · 在线 " + online + " / 离线 " + offline
                            + (offlineUids.isEmpty() ? "" : " · 离线uid " + offlineUids));
            // 2) 顶号开盾（force 子项开 + 会话控制面可用）
            if (force && !offlineUids.isEmpty()) {
                if (tc.sessionControl() == null) {
                    m.skip("会话控制面未注入，无法顶号");
                } else {
                    JSONObject fstrategy = tc.params("ally_offline_guard").optJSONObject("strategy");
                    int forceLimit = Math.max(1, Math.min(10, fstrategy != null && fstrategy.has("force")
                            ? fstrategy.optInt("force", 3) : 3));
                    int forced = 0;
                    for (long uid : offlineUids) {
                        if (forced >= forceLimit) break;
                        try {
                            com.carlos.vaultserver.SessionControl.RoleOpResult res =
                                    tc.sessionControl().roleActionByUid(String.valueOf(uid), "login");
                            if (res != null && res.accepted) {
                                forced++;
                                m.claimed("顶号上线 " + uid);
                            } else if (res != null) {
                                m.skip("顶号被拒 " + uid + "(码" + res.code + ")");
                            } else {
                                m.skip("号库中无该角色 " + uid);
                            }
                        } catch (Exception e) {
                            m.skip("顶号异常 " + uid);
                        }
                    }
                    if (forced > 0) m.subOk("force", "顶号上线 " + forced + " 个");
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "离线联动");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "ally_offline_guard", "离线联动");
    }

    /** 从战争/态势通知提取被攻击目标 uid（atk_city.targetid 优先，退化 target.map_city.uid）。 */
    static List<Long> situationTargetUids(TaskContext tc) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (String proto : new String[]{"notify_world_alliance_situation_init",
                "notify_world_alliance_situation_add", "notify_world_alliance_situation_update",
                "notify_world_situation_info", "notify_world_situation_info_add",
                "notify_world_situation_info_up"}) {
            Map<String, Object> c = tc.engine.notifyData(proto);
            if (c == null) continue;
            Object info = c.get("situation_info");
            List<Map<String, Object>> items = new ArrayList<>();
            if (info instanceof Map) {
                for (Object v : ((Map<?, ?>) info).values()) {
                    Map<String, Object> one = Exec.mapOf(v);
                    if (one != null) items.add(one);
                }
            } else if (info instanceof List) {
                items.addAll(Exec.mapsOf(info));
            }
            if (items.isEmpty()) {
                long uid = targetUidOf(c);
                if (uid > 0L) out.add(uid);
            } else {
                for (Map<String, Object> one : items) {
                    long uid = targetUidOf(one);
                    if (uid > 0L) out.add(uid);
                }
            }
        }
        return new ArrayList<>(out);
    }

    static long targetUidOf(Map<String, Object> m) {
        Map<String, Object> atk = Exec.mapOf(m.get("atk_city"));
        if (atk != null) {
            long t = Exec.longOf(atk.get("targetid"));
            if (t > 0L) return t;
        }
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null) return Exec.longOf(city.get("uid"));
        }
        return 0L;
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

                    // 2026-11-11 默认开启策略：生成 BOSS 消耗活动零件（道具），默认关；用户显式开才执行。
                    if (!tc.subOn("act_icehunter", "boss", false)) {
                        m.subSkip("boss", "默认关（消耗零件）");
                        return Exec.result(m, "act_icehunter", "冰原猎人");
                    }
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
