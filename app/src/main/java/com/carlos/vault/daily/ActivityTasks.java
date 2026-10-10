package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动类任务（协议流移植自网页版 Balloon/PitchPot/SnowClean/Icebound/Polar/BrawlWar/Fishing/
 * EndlessJourney/JoyAction/SupremacyFeast/RedPacket/Bank/Mercenary/TruckEscort/IceFieldHunter 服务）。
 * 活动窗口判定：以登录推送 notify 为准，无推送时请求探测并按错误码归为跳过。
 */
final class ActivityTasks {

    private ActivityTasks() {
    }

    // ---------- 正中靶心（射气球） ----------

    static final long BALLOON_ID = 2965001L;
    static final int[] BALLOON_EXCHANGE = {5, 10, 30, 60, 90, 120, 150, 180, 210, 240, 270,
            300, 360, 420, 480, 540, 600, 700, 800, 1000};

    static DailyResult balloon(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            // 签到奖励
            TownTasks.tryStep(engine, m, "签到奖励", "req_balloon_sign_reward_all",
                    ExecUtil.args("id", BALLOON_ID));
            // 兑换档位（按累计代币数领所有达到的档）
            Map<String, Object> data = engine.notifyData("notify_balloon_data");
            long coin = -1;
            if (data != null) {
                Map<String, Object> ex = ExecUtil.mapOf(data.get("exchange"));
                if (ex != null) coin = ExecUtil.longOf(ex.get("accumulate_coin_num"));
            }
            if (coin >= 5) {
                int exOk = 0;
                for (int t : BALLOON_EXCHANGE) {
                    if (coin < t) break;
                    GameMessage r = engine.request("req_balloon_coin_exchange",
                            ExecUtil.args("id", BALLOON_ID, "exchange_num", t));
                    if (ExecUtil.ok(r)) {
                        exOk++;
                        m.rewards("兑换档" + t, r);
                    }
                }
                if (exOk > 0) m.claimed("兑换×" + exOk);
            }
            // 免费局：开局→顺序射12靶（只用免费次数，不耗券）
            int games = 0;
            for (int round = 0; round < 3; round++) {
                GameMessage st = engine.request("req_balloon_start_level",
                        ExecUtil.args("id", BALLOON_ID, "type", 0));
                int code = ExecUtil.codeOf(st);
                if (!ExecUtil.ok(st)) {
                    if (round == 0) m.skip(code == 40501 || code == 40502 || code == 40503 ? "免费次数已用完或未开"
                            : code == 40504 ? "活动未开放" : "开局码" + code);
                    break;
                }
                long free = ExecUtil.longOf(st.getContent().get("free_times"));
                int hits = 0;
                for (int idx = 1; idx <= 12; idx++) {
                    GameMessage h = engine.request("req_balloon_hit",
                            ExecUtil.args("id", BALLOON_ID, "balloon_index", idx));
                    if (ExecUtil.ok(h)) hits++;
                    else if (ExecUtil.codeOf(h) == 40507 || ExecUtil.codeOf(h) == 40505) continue;
                }
                games++;
                if (free <= 0) break;
            }
            if (games > 0) m.claimed("免费局×" + games);
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "正中靶心");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_balloon", "正中靶心", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 壶纳千祥（满分结算档案取自网页版配置） ----------

    static final long[] PITCHPOT_IDS = {2436001L, 2436002L, 2436003L};

    static DailyResult pitchpot(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Long openId = null;
            Map<String, Object> ni = engine.notifyContent("notify_pitch_pot_info");
            if (ni != null) {
                long id = ExecUtil.longOf(ni.get("id"));
                if (id > 0) openId = id;
            }
            for (long id : PITCHPOT_IDS) {
                if (openId != null && openId != id) continue;
                GameMessage st = engine.request("req_pitch_pot_start", ExecUtil.args("id", id));
                int code = ExecUtil.codeOf(st);
                if (code == 40701) {
                    m.skip("有活动但无券");
                    break;
                }
                if (code == 40702 || code == 40707 || code == 7401 || code == 7404) {
                    if (openId == null) continue;   // 探测下一档活动号
                    break;
                }
                if (!ExecUtil.ok(st)) {
                    m.skip("开局码" + code);
                    break;
                }
                ExecUtil.sleep(15000);   // 网页版：开局至少15秒后提交结算
                List<Map<String, Object>> achieve = new ArrayList<>();
                achieve.add(ExecUtil.kv(3, 1));
                achieve.add(ExecUtil.kv(6, 1));
                achieve.add(ExecUtil.kv(5, 10));
                achieve.add(ExecUtil.kv(7, 1));
                achieve.add(ExecUtil.kv(1, 8));
                achieve.add(ExecUtil.kv(2, 8));
                GameMessage se = engine.request("req_pitch_pot_settle", ExecUtil.args(
                        "id", id, "score", 4650L, "achieve", achieve,
                        "n_normal", 8L, "n_advance", 3L));
                if (ExecUtil.ok(se)) {
                    m.claimed("满分结算(4650分)");
                    m.rewards("满分结算", se);
                }
                else m.fail("结算码" + ExecUtil.codeOf(se));
                break;
            }
            // 任务奖励：进度达标的领
            Map<String, Object> ti = engine.notifyContent("notify_pitch_pot_task_info");
            if (ti != null) {
                Map<String, Object> task = ExecUtil.mapOf(ti.get("task"));
                List<Map<String, Object>> quests = task == null
                        ? new ArrayList<>() : ExecUtil.mapsOf(task.get("quests"));
                long actId = ExecUtil.longOf(ti.get("id"));
                for (Map<String, Object> q : quests) {
                    long qid = ExecUtil.longOf(q.get("id"));
                    long prog = ExecUtil.longOf(q.get("progress"));
                    if (qid <= 0 || actId <= 0 || prog < pitchpotTarget(qid)) continue;
                    GameMessage r = engine.request("req_pitch_pot_task_reward",
                            ExecUtil.args("id", actId, "task_id", qid));
                    if (ExecUtil.ok(r)) {
                        m.claimed("任务" + qid);
                        m.rewards("任务" + qid, r);
                    }
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "壶纳千祥");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        if (m.sb.length() == 0) m.skip("活动未开放");
        DailyResult rr = new DailyResult("act_pitchpot", "壶纳千祥", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
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

    static DailyResult snowclean(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> ni = engine.notifyData("notify_snow_clean_info");
            long id = ni == null ? 2450001L : ExecUtil.longOf(ni.get("id"));
            if (id <= 0) id = 2450001L;
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                // 每日任务奖励
                Map<String, Object> tasks = ExecUtil.mapOf(ni.get("tasks"));
                List<Map<String, Object>> quests = tasks == null
                        ? new ArrayList<>() : ExecUtil.mapsOf(tasks.get("quests"));
                for (Map<String, Object> q : quests) {
                    long qid = ExecUtil.longOf(q.get("id"));
                    if (qid <= 0) continue;
                    GameMessage r = engine.request("req_snow_clean_task",
                            ExecUtil.args("id", id, "task_id", qid));
                    if (ExecUtil.ok(r)) {
                        m.claimed("任务" + qid);
                        m.rewards("任务" + qid, r);
                    }
                }
                TownTasks.tryStep(engine, m, "冰雪基金", "req_snow_fund_get_all_reward", null);
                // 极速除雪：当前关连扫
                long mission = ExecUtil.longOf(ni.get("mission"));
                int swept = 0;
                for (int i = 0; i < 30 && mission > 0; i++) {
                    GameMessage st = engine.request("req_snow_clean_start",
                            ExecUtil.args("id", id, "mission", mission));
                    int code = ExecUtil.codeOf(st);
                    if (!ExecUtil.ok(st) && code != 40084) {
                        if (i == 0) m.skip(code == 40077 ? "已全部通关" : "除雪码" + code);
                        break;
                    }
                    GameMessage sw = engine.request("req_snow_clean_sweep",
                            ExecUtil.args("id", id));
                    int sc = ExecUtil.codeOf(sw);
                    if (ExecUtil.ok(sw)) {
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
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "除雪小分队");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_snowclean", "除雪小分队", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 冰封的宝藏 ----------

    static DailyResult icebound(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> fs = engine.notifyContent("notify_frozen_soil_info");
            if (fs != null) {
                long id = ExecUtil.longOf(fs.get("id"));
                if (id > 0) {
                    TownTasks.tryStep(engine, m, "特训任务奖励", "req_frozen_soil_get_all_reward",
                            ExecUtil.args("id", id));
                    TownTasks.tryStep(engine, m, "特训额外奖励", "req_frozen_soil_get_extra_reward",
                            ExecUtil.args("id", id));
                }
            } else {
                m.skip("寻宝特训未开");
            }
            Map<String, Object> am = engine.notifyContent("notify_icebound_amazing_info");
            if (am != null) {
                Map<String, Object> info = ExecUtil.mapOf(am.get("info"));
                long aid = info == null ? 0 : ExecUtil.longOf(ExecUtil.nest(info, "base") != null
                        ? ExecUtil.nest(info, "base").get("id") : info.get("id"));
                if (aid > 0) {
                    long progress = ExecUtil.longOf(info.get("chest_progress"));
                    if (progress > 0) {
                        GameMessage pr = engine.request("req_icebound_amazing_progress_reward",
                                ExecUtil.args("progress", progress, "activity_id", aid));
                        if (ExecUtil.ok(pr)) {
                            m.claimed("积分档位奖励");
                            m.rewards("积分档位", pr);
                        }
                    }
                    Map<String, Object> chest = ExecUtil.mapOf(info.get("chest"));
                    if (chest != null && !chest.isEmpty()) {
                        List<Object> ids = new ArrayList<>(chest.keySet());
                        GameMessage oc = engine.request("req_icebound_amazing_open_chest",
                                ExecUtil.args("ids", ids, "activity_id", aid));
                        if (ExecUtil.ok(oc)) {
                            m.claimed("宝箱×" + ids.size());
                            m.rewards("宝箱", oc);
                        }
                    }
                }
            } else {
                m.skip("宝藏活动未开");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "冰封的宝藏");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_icebound", "冰封的宝藏", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 逐光之旅（极地远征） ----------

    static DailyResult polar(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> ni = engine.notifyContent("notify_polar_expedition_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                long id = ExecUtil.longOf(ni.get("activityId"));
                if (id <= 0) id = ExecUtil.longOf(ni.get("id"));
                for (Map<String, Object> team : ExecUtil.mapsOf(ni.get("team_infos"))) {
                    long tid = ExecUtil.longOf(team.get("team_id"));
                    long state = ExecUtil.longOf(team.get("state"));
                    if (tid <= 0) continue;
                    if (state == 2) {
                        GameMessage r = engine.request("req_polar_expedition_get_chest",
                                ExecUtil.args("id", id, "team_id", tid));
                        if (ExecUtil.ok(r)) {
                            m.claimed("队列" + tid + "宝箱");
                            m.rewards("队列宝箱", r);
                        }
                    } else if (state == 0) {
                        GameMessage r = engine.request("req_polar_expedition_start",
                                ExecUtil.args("id", id, "team_id", tid));
                        if (ExecUtil.ok(r)) m.claimed("队列" + tid + "已派遣");
                    }
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "逐光之旅");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_polar", "逐光之旅", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 欢乐大乱斗（奖励领取部分） ----------

    static DailyResult brawl(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            GameMessage ach = engine.request("req_brawl_war_get_achievement_reward",
                    ExecUtil.args("id", 2438001L));
            int code = ExecUtil.codeOf(ach);
            if (ExecUtil.ok(ach)) {
                m.claimed("成就奖励");
                m.rewards("成就奖励", ach);
            }
            else m.skip(code == 7401 || code == 7404 ? "活动未开放" : "成就码" + code);
            // 战场内自动战斗（匹配/迁城/循环打矿）逻辑复杂，待专项迭代
            m.skip("战斗部分待开放");
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "欢乐大乱斗");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_brawl", "欢乐大乱斗", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 钓鱼（奖励领取部分） ----------

    static DailyResult fishing(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> ni = engine.notifyContent("notify_fishing_activity_info");
            if (ni == null) ni = engine.notifyContent("notify_fishing_info");
            if (ni == null) {
                m.skip("钓鱼活动未开放");
            } else {
                Long id = WjdrEngine.extractId(ni);
                if (id != null && id > 0) {
                    TownTasks.tryStep(engine, m, "每日奖励", "req_fishing_get_daily_rewards",
                            ExecUtil.args("id", id));
                    for (int type = 1; type <= 3; type++) {
                        TownTasks.tryStep(engine, m, "任务奖励T" + type,
                                "req_fishing_get_all_task_reward",
                                ExecUtil.args("id", id, "type", type));
                    }
                    TownTasks.tryStep(engine, m, "俱乐趣奖励", "req_fishing_bp_get_all_reward",
                            ExecUtil.args("id", id));
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "钓鱼");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_fishing", "钓鱼", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 无尽征程 ----------

    static DailyResult endlessJourney(WjdrEngine engine) {
        try {
            GameMessage rsp = engine.request("req_general_bp_get_all_reward",
                    ExecUtil.args("id", 2904006L));
            int code = ExecUtil.codeOf(rsp);
            if (ExecUtil.ok(rsp)) {
                DailyResult r = new DailyResult("act_endless", "无尽征程", DailyResult.Category.CLAIMED,
                        "里程碑奖励已领取");
                r.rewards = RewardText.fromContent(rsp.getContent());
                return r;
            }
            if (code == 11723) {
                return new DailyResult("act_endless", "无尽征程", DailyResult.Category.SKIPPED, "暂无可领奖励");
            }
            if (code == 11724) {
                return new DailyResult("act_endless", "无尽征程", DailyResult.Category.SKIPPED, "今日已领取");
            }
            if (code == 11727) {
                return new DailyResult("act_endless", "无尽征程", DailyResult.Category.SKIPPED, "积分不足");
            }
            if (code == 11728) {
                return new DailyResult("act_endless", "无尽征程", DailyResult.Category.SKIPPED, "暂无可领（档位未达成）");
            }
            if (code == 7401 || code == 7404) {
                return new DailyResult("act_endless", "无尽征程", DailyResult.Category.SKIPPED, "活动未开启");
            }
            return new DailyResult("act_endless", "无尽征程", DailyResult.Category.ERROR, "错误码 " + code);
        } catch (Exception e) {
            return DailyTasks.classifyException(e, "无尽征程");
        }
    }

    // ---------- 怪攻守城（原版 joy-action：活动窗口内驻防盟友） ----------

    static DailyResult joyAction(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            Map<String, Object> siege = engine.notifyContent("notify_monster_siege_activity");
            if (siege == null) {
                m.skip("活动未开战");
            } else {
                // 驻防目标需盟友在线信息，第一版先不自动出征
                m.skip("活动进行中（自动驻防待盟友配置）");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "怪攻守城");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_joy", "怪攻守城", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 至尊盛宴（赴宴） ----------

    static DailyResult supremacyFeast(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            Map<String, Object> fi = engine.notifyContent("notify_supremacy_war_feast_init");
            if (fi == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> act = ExecUtil.mapOf(fi.get("activity"));
                long actId = act == null ? 0 : ExecUtil.longOf(act.get("id"));
                if (actId <= 0) {
                    m.skip("活动数据异常");
                } else {
                    GameMessage search = engine.request("req_world_search_supremacy_table",
                            ExecUtil.args("act_id", actId));
                    Map<String, Object> pt = ExecUtil.ok(search)
                            ? ExecUtil.mapOf(search.getContent().get("point")) : null;
                    if (pt == null) {
                        m.skip("暂无空桌");
                    } else {
                        Map<String, Object> formation = ExecUtil.formation(engine, 1);
                        Map<String, Object> extra = ExecUtil.args(
                                "dinner_act_id", actId, "dinner_table_id", 0L);
                        GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                "type", 1002L, "endpoint", pt, "formation", formation,
                                "extra", extra, "MarchMapType", 1L));
                        if (ExecUtil.marchId(march) > 0) m.claimed("已赴宴");
                        else m.skip("赴宴失败(码" + ExecUtil.codeOf(march) + ")");
                    }
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "至尊盛宴");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_feast", "至尊盛宴", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 红包 ----------

    static DailyResult redPacket(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            android.content.Context rpctx = engine.appContext();
            String rpfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long rpsid = engine.lastServerId();
            for (int channel = 1; channel <= 2; channel++) {
                // 子项开关：alliance=联盟频道 / world=世界频道（默认开）
                boolean on = FnParams.subOn(rpctx, rpfpid, rpsid, "red_packet",
                        channel == 1 ? "alliance" : "world", true);
                if (!on) {
                    m.subSkip(channel == 1 ? "alliance" : "world", "已关闭");
                    continue;
                }
                GameMessage rf = engine.request("req_red_packet_refresh",
                        ExecUtil.args("channel_type", channel, "id_lst", new ArrayList<>()));
                if (!ExecUtil.ok(rf)) {
                    int code = ExecUtil.codeOf(rf);
                    m.skip(channel == 1 ? "联盟" : "世界" + (code == 16873 ? "无联盟" : "刷新码" + code));
                    continue;
                }
                int got = 0;
                for (Map<String, Object> p : ExecUtil.mapsOf(rf.getContent().get("lst"))) {
                    long pid = ExecUtil.longOf(p.get("id"));
                    long idx = ExecUtil.longOf(p.get("index"));
                    if (pid <= 0) continue;
                    GameMessage g = engine.request("req_red_packet_get", ExecUtil.args(
                            "channel_type", channel, "id", pid, "index", idx));
                    int code = ExecUtil.codeOf(g);
                    if (ExecUtil.ok(g)) {
                        got++;
                        m.rewards((channel == 1 ? "联盟" : "世界") + "红包", g);
                    }
                    else if (code == 16876) {
                        m.skip("今日配额已满");
                        DailyResult rr = new DailyResult("red_packet", "游戏红包", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
                    }
                }
                if (got > 0) m.claimed((channel == 1 ? "联盟" : "世界") + "×" + got);
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "游戏红包");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("red_packet", "游戏红包", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 钻石银行（到期取款+原额续存） ----------

    static DailyResult bank(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            Map<String, Object> ri = engine.notifyContent("notify_reward_info");
            Map<String, Object> bank = ri == null ? null : ExecUtil.mapOf(ri.get("bank"));
            Map<String, Object> use = bank == null ? null : ExecUtil.mapOf(bank.get("use_locker"));
            long lockerId = use == null ? 0 : ExecUtil.longOf(use.get("locker_id"));
            long amount = use == null ? 0 : ExecUtil.longOf(use.get("amount"));
            long expire = use == null ? 0 : ExecUtil.longOf(use.get("expire_time"));
            if (use == null || amount <= 0) {
                m.skip("无在存订单（默认不新增存款）");
            } else if (expire > 0 && expire * 1000L <= System.currentTimeMillis() + 2000) {
                boolean doWithdraw = FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "bank", "withdraw", true);
                boolean doRedeposit = FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "bank", "redeposit", true);
                if (!doWithdraw) {
                    m.skip("取款已关闭");
                    m.subSkip("withdraw", "已关闭");
                } else {
                GameMessage wd = engine.request("req_bank_withdraw", null);
                if (ExecUtil.ok(wd)) {
                    m.claimed("取款" + amount);
                    m.rewards("取款", wd);
                    long reDep = amount / 100 * 100;
                    if (reDep >= 100 && doRedeposit) {
                        GameMessage dp = engine.request("req_bank_deopsit",
                                ExecUtil.args("locker_id", lockerId, "amount", reDep));
                        if (ExecUtil.ok(dp)) m.claimed("续存" + reDep);
                        else m.skip("续存码" + ExecUtil.codeOf(dp));
                    }
                } else {
                    int code = ExecUtil.codeOf(wd);
                    m.skip(code == 6157 ? "未到期" : "取款码" + code);
                }
                }
            } else {
                m.skip("存款未到期");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "钻石银行");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("bank", "钻石银行", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 佣兵荣耀 ----------

    static DailyResult mercenary(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> ni = engine.notifyData("notify_mercenary_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> base = ExecUtil.mapOf(ni.get("base"));
                long id = base == null ? ExecUtil.longOf(ni.get("id"))
                        : ExecUtil.longOf(base.get("id"));
                long maxDiff = 0;
                for (String k : new String[]{"maxDifficulty", "max_difficulty", "difficulty"}) {
                    Object v = base != null ? base.get(k) : ni.get(k);
                    if (v instanceof Number) {
                        maxDiff = ExecUtil.longOf(v);
                        break;
                    }
                }
                if (id <= 0) {
                    m.skip("活动数据异常");
                } else {
                    long diff = Math.min(20, maxDiff + 1);
                    engine.request("req_mercenary_choose_difficulty",
                            ExecUtil.args("id", id, "difficulty", diff));
                    GameMessage sp = engine.request("req_mercenary_spawn_player_monster",
                            ExecUtil.args("id", id));
                    if (ExecUtil.ok(sp)) {
                        Map<String, Object> boss = ExecUtil.mapOf(sp.getContent().get("boss_data"));
                        long x = boss == null ? 0 : ExecUtil.longOf(boss.get("x"));
                        long y = boss == null ? 0 : ExecUtil.longOf(boss.get("y"));
                        long mid = boss == null ? 0 : ExecUtil.longOf(boss.get("id"));
                        Map<String, Object> formation = ExecUtil.formation(engine, 1);
                        if (x != 0 || y != 0) {
                            GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                    "type", 502L, "endpoint", ExecUtil.point(x, y),
                                    "formation", formation,
                                    "extra", ExecUtil.args("monsterid", mid)));
                            if (ExecUtil.marchId(march) > 0) m.claimed("已出征佣兵(难度" + diff + ")");
                            else m.skip("出征码" + ExecUtil.codeOf(march));
                        } else {
                            m.skip("未取到佣兵坐标");
                        }
                    } else {
                        int code = ExecUtil.codeOf(sp);
                        m.skip(code == 21 ? "个人怪已全部通关" : code == 51 ? "体力不足" : "生成码" + code);
                    }
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "佣兵荣耀");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_mercenary", "佣兵荣耀", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 运宝车队（领送达奖励为主） ----------

    static DailyResult truckEscort(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> ni = engine.notifyData("notify_truck_escort_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> base = ExecUtil.mapOf(ni.get("base"));
                long id = base == null ? ExecUtil.longOf(ni.get("id")) : ExecUtil.longOf(base.get("id"));
                int taken = 0;
                for (Map<String, Object> t : ExecUtil.mapsOf(ni.get("truck_infos"))) {
                    long tid = ExecUtil.longOf(t.get("truck_id"));
                    if (tid <= 0) tid = ExecUtil.longOf(t.get("slot_id"));
                    boolean finish = ExecUtil.longOf(t.get("is_finish")) == 1
                            || ExecUtil.longOf(t.get("status")) == 2;
                    if (!finish) continue;
                    GameMessage r = engine.request("req_truck_escort_take_truck_reward",
                            ExecUtil.args("id", id, "truck_id", tid));
                    if (ExecUtil.ok(r)) {
                        taken++;
                        m.rewards("货车奖励", r);
                    }
                }
                if (taken > 0) m.claimed("货车奖励×" + taken);
                else m.skip("暂无送达货车");
                // 发车（一名英雄即可）
                Map<String, Object> send = ExecUtil.mapOf(ni.get("send_truck_info"));
                long stage = send == null ? 0 : ExecUtil.longOf(send.get("stage_index"));
                if (send != null && stage >= 0) {
                    List<Long> heroes = TownTasks.squadHeroes(engine);
                    if (!heroes.isEmpty()) {
                        Map<String, Object> formation = ExecUtil.args(
                                "hero_id", java.util.Collections.singletonList(ExecUtil.kv(heroes.get(0), 1)),
                                "soldier", new ArrayList<>());
                        GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                "type", 1201L, "endpoint", ExecUtil.point(0, 0),
                                "formation", formation,
                                "extra", ExecUtil.args("event_id", id)));
                        if (ExecUtil.marchId(march) > 0) m.claimed("已发车");
                        else if (ExecUtil.codeOf(march) != 12706) m.skip("发车码" + ExecUtil.codeOf(march));
                    }
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "运宝车队");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_truck", "运宝车队", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 冰原猎人（领奖 + 集结BOSS） ----------

    static DailyResult icefieldHunter(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            Map<String, Object> ni = engine.notifyContent("notify_icefield_hunter2_info");
            if (ni == null) ni = engine.notifyContent("notify_icefield_hunter_info");
            if (ni == null) {
                m.skip("活动未开放");
            } else {
                Map<String, Object> base = ExecUtil.mapOf(ni.get("base"));
                long id = base == null ? ExecUtil.longOf(ni.get("id")) : ExecUtil.longOf(base.get("id"));
                if (id <= 0) {
                    m.skip("活动数据异常");
                } else {
                    GameMessage col = engine.request("req_icefield_hunter2_collect_master_reward",
                            ExecUtil.args("id", id, "reward_id", 0L, "is_gain_all", true));
                    int code = ExecUtil.codeOf(col);
                    if (ExecUtil.ok(col)) {
                        m.claimed("进度奖励");
                        m.rewards("进度奖励", col);
                    }
                    else if (code != 61 && code != 8251) m.skip("领奖码" + code);

                    GameMessage sp = engine.request("req_icefield_hunter_spawn_monster",
                            ExecUtil.args("item_id", 600008L));
                    if (ExecUtil.ok(sp)) {
                        Map<String, Object> boss = ExecUtil.mapOf(sp.getContent().get("boss_data"));
                        long x = boss == null ? 0 : ExecUtil.longOf(boss.get("x"));
                        long y = boss == null ? 0 : ExecUtil.longOf(boss.get("y"));
                        long mid = boss == null ? 0 : ExecUtil.longOf(boss.get("id"));
                        if (x != 0 || y != 0) {
                            Map<String, Object> formation = ExecUtil.formation(engine, 1);
                            GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                    "type", 501L, "endpoint", ExecUtil.point(x, y),
                                    "formation", formation,
                                    "extra", ExecUtil.args("monsterid", mid, "prepare_time_index", 2L)));
                            if (ExecUtil.marchId(march) > 0) m.claimed("已集结BOSS");
                            else m.skip("集结码" + ExecUtil.codeOf(march));
                        }
                    } else {
                        int sc = ExecUtil.codeOf(sp);
                        m.skip(sc == 51 ? "零件道具不足" : "生成码" + sc);
                    }
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "冰原猎人");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("act_icehunter", "冰原猎人", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }
}
