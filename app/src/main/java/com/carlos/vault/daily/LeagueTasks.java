package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 联盟类追加任务（总动员/争霸赛/互助）+ 背包道具，协议流移植自网页版对应服务。
 */
final class LeagueTasks {

    private LeagueTasks() {
    }

    // ---------- 联盟总动员：领奖 + 免费接取次数 + 里程碑 ----------

    static DailyResult mobilization(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            GameMessage info = engine.request("req_mobilization_open_info", null);
            if (!ExecUtil.ok(info)) {
                m.skip("总动员未开启(码" + ExecUtil.codeOf(info) + ")");
            } else {
                // 已完成未领的任务奖励（notify 维护的任务板）
                Map<String, Object> ni = engine.notifyContent("notify_mobilization_init");
                int claimed = 0;
                if (ni != null) {
                    for (String key : new String[]{"quests", "quest_list", "data"}) {
                        for (Map<String, Object> q : ExecUtil.mapsOf(ni.get(key))) {
                            long pos = ExecUtil.longOf(q.get("pos"));
                            long progress = ExecUtil.longOf(q.get("progress"));
                            long target = ExecUtil.longOf(q.get("target"));
                            if (pos > 0 && target > 0 && progress >= target) {
                                GameMessage r = engine.request("req_mobilization_get_quest_reward",
                                        ExecUtil.args("pos", pos));
                                if (ExecUtil.ok(r)) {
                                    claimed++;
                                    m.rewards("任务奖励", r);
                                }
                            }
                        }
                    }
                }
                if (claimed > 0) m.claimed("任务奖励×" + claimed);
                // 免费领一次接取次数
                GameMessage buy = engine.request("req_mobilization_buy_count", ExecUtil.args("count", 1));
                if (ExecUtil.ok(buy)) m.claimed("接取次数+1");
                // 里程碑批量领取
                GameMessage ms = engine.request("req_mobilization_batch_get_milestone", null);
                if (ExecUtil.ok(ms)) {
                    m.claimed("里程碑");
                    m.rewards("里程碑", ms);
                }
                else if (ExecUtil.codeOf(ms) != 8413) m.skip("里程碑码" + ExecUtil.codeOf(ms));
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "联盟总动员");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("alliance_mobilization", "联盟总动员", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 联盟争霸赛：报名 ----------

    static DailyResult showdown(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            GameMessage info = engine.request("req_alliance_showdown_activity_info", null);
            if (!ExecUtil.ok(info)) {
                m.skip("争霸赛未开启(码" + ExecUtil.codeOf(info) + ")");
            } else {
                Map<String, Object> all = info.getContent();
                boolean signed = ExecUtil.longOf(all == null ? null : all.get("apply")) == 1
                        || "true".equals(String.valueOf(all == null ? null : all.get("is_apply")));
                if (signed) {
                    m.skip("已报名");
                } else {
                    Map<String, Object> formation = ExecUtil.formation(engine, 1);
                    GameMessage apply = engine.request("req_alliance_showdown_apply", ExecUtil.args(
                            "type", 1L, "formation", formation));
                    if (ExecUtil.ok(apply)) m.claimed("已报名赛道1");
                    else m.skip("报名码" + ExecUtil.codeOf(apply));
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "联盟争霸赛");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("alliance_showdown", "争霸赛", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 联盟互助：一键帮助全盟（每次运行执行，配合定时器高频互助） ----------

    static DailyResult allianceHelp(WjdrEngine engine) {
        try {
            GameMessage rsp = engine.request("req_alliance_help_all", null);
            int code = ExecUtil.codeOf(rsp);
            if (ExecUtil.ok(rsp)) {
                try {
                    FnParams.saveModText(engine.appContext(), engine.lastFpid(), engine.lastServerId(),
                            "alliance_help", "help_all",
                            "上轮互助 " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                    .format(new java.util.Date()));
                } catch (Throwable ignored) {
                }
                return new DailyResult("alliance_help", "联盟互助", DailyResult.Category.CLAIMED,
                        "已一键帮助全盟（建议配合自动日常高频运行）");
            }
            if (code == 75 || code == 2 || code == 1536) {
                return new DailyResult("alliance_help", "联盟互助", DailyResult.Category.SKIPPED,
                        code == 1536 ? "今日帮助次数已用完或无请求" : "当前无互助请求或不可用");
            }
            if (code == 16873) {
                return new DailyResult("alliance_help", "联盟互助", DailyResult.Category.SKIPPED, "无联盟");
            }
            return new DailyResult("alliance_help", "联盟互助", DailyResult.Category.ERROR, "错误码 " + code);
        } catch (Exception e) {
            return DailyTasks.classifyException(e, "联盟互助");
        }
    }

    // ---------- 背包道具：批量使用固定资源包（安全档） ----------

    static DailyResult backpack(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            Map<String, Object> bi = engine.notifyContent("notify_backpack_init");
            Map<Long, Long> items = ExecUtil.kvPairs(bi == null ? null : bi.get("data"));
            if (items == null || items.isEmpty()) {
                items = ExecUtil.kvPairs(bi == null ? null : bi.get("items"));
            }
            if (items.isEmpty()) {
                m.skip("背包数据未同步");
            } else {
                List<Map<String, Object>> params = new ArrayList<>();
                int used = 0;
                // 固定资源包（普通 100111~100115：100/1k/1w/10w/100w 档）
                for (long id = 100111; id <= 100111 + 4; id++) {
                    Long cnt = items.get(id);
                    if (cnt != null && cnt > 0) {
                        params.add(ExecUtil.args("id", id, "count", cnt, "extra", new java.util.LinkedHashMap<>()));
                        used += cnt;
                    }
                }
                // 随机补给箱（可选，默认关）
                if (FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "backpack", "random", false)) {
                    for (long id = 100091; id <= 100093; id++) {
                        Long cnt = items.get(id);
                        if (cnt != null && cnt > 0) {
                            params.add(ExecUtil.args("id", id, "count", cnt, "extra", new java.util.LinkedHashMap<>()));
                            used += cnt;
                        }
                    }
                }
                if (used == 0) {
                    m.skip("无资源包可开");
                } else {
                    GameMessage rsp = engine.request("req_batch_use_item", ExecUtil.args("params", params));
                    if (ExecUtil.ok(rsp)) {
                        m.claimed("开启资源包×" + used);
                        m.rewards("资源包", rsp);
                    }
                    else m.skip("使用码" + ExecUtil.codeOf(rsp));
                }
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "背包道具");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("backpack", "背包道具", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 晨曦岛拜访协助（原版 homeland-visit 同款） ----------

    static DailyResult homelandVisit(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            long myUid = engine.lastUid();
            long kid = engine.lastServerId();
            // 1. 联盟活跃排行（type=10003 = 晨曦岛活跃榜），rank_list[].key = 成员 uid
            GameMessage rank = engine.request("req_alliance_rank_data",
                    ExecUtil.args("type", 10003L));
            if (!ExecUtil.ok(rank)) {
                m.skip("联盟排行未开放(码" + ExecUtil.codeOf(rank) + ")");
                return visitResult(m);
            }
            List<Long> candidates = new ArrayList<>();
            for (Map<String, Object> row : ExecUtil.mapsOf(rank.getContent().get("rank_list"))) {
                long uid = ExecUtil.longOf(row.get("key"));
                if (uid > 0 && uid != myUid && candidates.size() < 20) candidates.add(uid);
            }
            if (candidates.isEmpty()) {
                m.skip("联盟排行无成员");
                return visitResult(m);
            }
            // 2. 批量查协助状态：今日已协助≥3 或 6 小时内协助过 → 跳过（原版同款阈值）
            GameMessage infos = engine.request("req_homeland_player_homeland_info",
                    ExecUtil.args("uids", candidates, "type", 1L));
            Map<String, Object> pi = ExecUtil.mapOf(infos.getContent().get("player_homeland_infos"));
            int visited = 0, assisted = 0;
            for (Long uid : candidates) {
                if (visited >= 10) break;   // 单轮上限（原版 300s 任务节律的保守版）
                Map<String, Object> info = ExecUtil.mapOf(pi == null ? null : pi.get(String.valueOf(uid)));
                Map<String, Object> assist = ExecUtil.mapOf(info == null ? null : info.get("homeland_assist_info"));
                long assistedCount = ExecUtil.longOf(assist == null ? null : assist.get("assisted_count"));
                long lastTime = ExecUtil.longOf(assist == null ? null : assist.get("last_assisted_time"));
                if (assistedCount >= 3 || (lastTime > 0
                        && System.currentTimeMillis() / 1000L - lastTime < 21600L)) {
                    continue;
                }
                GameMessage visit = engine.request("req_homeland_visit_other_homeland",
                        ExecUtil.args("uid", uid, "kid", kid));
                if (!ExecUtil.ok(visit)) continue;
                GameMessage as = engine.request("req_homeland_assist", ExecUtil.args("uid", uid));
                int code = ExecUtil.codeOf(as);
                if (ExecUtil.ok(as)) {
                    assisted++;
                    m.claimed("协助 uid=" + uid);
                } else if (code == 15026) {
                    m.skip("今日协助次数已用完(3/3)");
                    break;
                } else if (code == 15027 || code == 15028) {
                    m.skip("uid=" + uid + " 冷却/对方已满");
                } else {
                    m.skip("uid=" + uid + " 码" + code);
                }
                visited++;
            }
            if (assisted > 0) {
                m.claimed("拜访协助×" + assisted);
                m.subOk("assist", "协助×" + assisted);
            } else if (m.sb.length() == 0) {
                m.skip("无可协助对象");
                m.subSkip("assist", "无可协助");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "晨曦岛拜访");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return visitResult(m);
    }

    private static DailyResult visitResult(ExecUtil.Multi m) {
        DailyResult rr = new DailyResult("homeland_visit", "晨曦岛拜访", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }
}
