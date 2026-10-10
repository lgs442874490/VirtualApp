package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 城镇类任务（协议流移植自网页版 DailyTownService/Hospital/Barrack/Pet/Expert/Homeland/
 * FireCrystal/Climbtower/Arena 系列服务，按一次性执行语义裁剪）。
 */
final class TownTasks {

    private TownTasks() {
    }

    // ---------- 城镇管理：免费招募 + 收兵 + 专家技能 ----------

    static DailyResult townAffairs(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        captureHeroSearch(engine);
        // 1. 免费英雄招募（高级 search_type=1 免费次数；史诗 search_type=2 48小时一次）
        try {
            android.content.Context rctx = engine.appContext();
            String rfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long rsid = engine.lastServerId();
            boolean doFree = FnParams.subOn(rctx, rfpid, rsid, "town_affairs", "recruit_free", true);
            boolean doEpic = FnParams.subOn(rctx, rfpid, rsid, "town_affairs", "recruit_epic", true);
            int free = 0;
            if (doFree) {
                for (int i = 0; i < 5; i++) {
                    GameMessage rsp = engine.request("req_hero_search_times",
                            ExecUtil.args("search_type", 1, "cost_type", 1, "times", 1));
                    if (ExecUtil.ok(rsp)) {
                        free++;
                    } else {
                        break;
                    }
                }
            }
            if (free > 0) { m.claimed("高级招募×" + free); m.subOk("recruit_free", "招募×" + free); }
            else { m.skip("免费招募无次数"); m.subSkip("recruit_free", doFree ? "今日无免费次数" : "已关闭"); }

            if (doEpic) {
                GameMessage epic = engine.request("req_hero_search_times",
                        ExecUtil.args("search_type", 2, "cost_type", 1, "times", 1));
                if (ExecUtil.ok(epic)) { m.claimed("史诗招募"); m.subOk("recruit_epic", "史诗招募已招"); }
                else { m.skip("史诗招募未到时间"); m.subSkip("recruit_epic", "未到48小时"); }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "城镇管理");
            m.fail("招募:" + DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        // 3. 罗慕路斯双技能：忠诚令牌(14) + 每日兵营士兵(18, param1=兵营ID 321盾/322矛/323弓)
        android.content.Context tctx = engine.appContext();
        String tfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
        long tsid = engine.lastServerId();
        if (FnParams.subOn(tctx, tfpid, tsid, "town_affairs", "expert_token", true)) {
            try {
                GameMessage rsp = engine.request("req_use_expert_skill",
                        ExecUtil.args("expert_id", 7004, "skill_id", 70041, "effect_type", 14));
                int code = ExecUtil.codeOf(rsp);
                if (ExecUtil.ok(rsp)) m.claimed("忠诚令牌");
                else if (code == 40237 || code == 40238 || code == 40236) m.skip("忠诚令牌已领");
                else m.skip("令牌码" + code);
            } catch (Exception e) {
                if (sessionLost(e)) return DailyTasks.classifyException(e, "城镇管理");
                m.skip("令牌未执行");
            }
        }
        if (FnParams.subOn(tctx, tfpid, tsid, "town_affairs", "expert_soldier", true)) {
            String bid = FnParams.strategy(tctx, tfpid, tsid, "town_affairs", "expert_soldier", "321");
            try {
                GameMessage rsp = engine.request("req_use_expert_skill",
                        ExecUtil.args("expert_id", 7004, "skill_id", 70041,
                                "effect_type", 18, "param1", Long.parseLong(bid)));
                int code = ExecUtil.codeOf(rsp);
                if (ExecUtil.ok(rsp)) {
                    m.claimed("每日士兵(" + ("321".equals(bid) ? "盾" : "322".equals(bid) ? "矛" : "弓") + "兵营)");
                    TownTasks.mod(engine, "town_affairs", "expert_soldier",
                            "每日士兵：已领 " + ("321".equals(bid) ? "盾" : "322".equals(bid) ? "矛" : "弓") + "兵营");
                } else if (code == 40237 || code == 40238 || code == 40236) m.skip("每日士兵已领");
                else m.skip("士兵码" + code);
            } catch (Exception e) {
                if (sessionLost(e)) return DailyTasks.classifyException(e, "城镇管理");
                m.skip("士兵未执行");
            }
        }
        DailyResult rr = new DailyResult("town_affairs", "城镇管理", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    static void mod(WjdrEngine engine, String fnId, String subId, String text) {
        try {
            FnParams.saveModText(engine.appContext(), engine.lastFpid(), engine.lastServerId(), fnId, subId, text);
        } catch (Throwable ignored) {
        }
    }

    static double num(Object v, double def) {
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    /** 招募状态采集：notify_hero_search_data → 今日次数进度 + 下次冷却倒计时。 */
    static void captureHeroSearch(WjdrEngine engine) {
        try {
            Map<String, Object> hs = engine.notifyData("notify_hero_search_data");
            if (hs == null) return;
            String raw = String.valueOf(hs);
            DailyTasks.debugLog(engine.appContext(), "hero_search raw=" + raw.substring(0, Math.min(400, raw.length())));
            long now = System.currentTimeMillis() / 1000L;
            // 深扫：找含 type/search_type=1或2 的子表，收集 free 次数与时间字段
            // 具体字段：free_search_times=今日剩余次数; search_time{1:{time:上次普通},2:{time:上次史诗}}; 冷却: 普通300秒/史诗172800秒
            double freeLeft = num(hs.get("free_search_times"), -1);
            Object stObj = hs.get("search_time");
            long lastFree = 0, lastEpic = 0;
            if (stObj instanceof Map) {
                for (Object o : ((Map<?, ?>) stObj).values()) {
                    Map<?, ?> e = (Map<?, ?>) o;
                    int type = (int) num(e.get("search_type"), 0);
                    long time = (long) num(e.get("time"), 0);
                    if (type == 1) lastFree = time;
                    if (type == 2) lastEpic = time;
                }
            }
            java.text.SimpleDateFormat hh = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
            java.text.SimpleDateFormat dd = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            if (freeLeft >= 0) {
                int used = Math.max(0, 5 - (int) freeLeft);
                String s = "今日 " + used + "/5";
                if (freeLeft > 0) s += " · 当前可招募";
                else if (lastFree > 0) s += " · 下次 " + hh.format(new java.util.Date((lastFree + 300) * 1000));
                mod(engine, "town_affairs", "recruit_free", s);
            }
            if (lastEpic > 0) {
                long nextEpic = lastEpic + 172800;
                if (nextEpic <= now) {
                    mod(engine, "town_affairs", "recruit_epic", "上次 " + dd.format(new java.util.Date(lastEpic * 1000)) + " · 当前可招募");
                } else {
                    long left = nextEpic - now;
                    mod(engine, "town_affairs", "recruit_epic", "上次 " + dd.format(new java.util.Date(lastEpic * 1000))
                            + " · 下次还 " + (left / 3600) + "小时" + ((left % 3600) / 60) + "分");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 深扫招募表：子表含 type/search_type∈{1,2} → {type, free次数, 最近未来时间戳}。 */
    private static void scanSearch(Object v, java.util.List<Object[]> out, int depth) {
        if (depth > 4 || v == null) return;
        if (v instanceof Map) {
            Map<?, ?> mm = (Map<?, ?>) v;
            Integer type = null;
            for (String k : new String[]{"search_type", "type"}) {
                Object tv = mm.get(k);
                if (tv instanceof Number && (((Number) tv).intValue() == 1 || ((Number) tv).intValue() == 2)) {
                    type = ((Number) tv).intValue();
                    break;
                }
            }
            if (type != null) {
                Object free = null, ts = null;
                long now = System.currentTimeMillis() / 1000L;
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    String k = String.valueOf(e.getKey()).toLowerCase();
                    Object val = e.getValue();
                    if (val instanceof Number) {
                        long n = ((Number) val).longValue();
                        if ((k.contains("free") || k.contains("times") || k.contains("count")) && n >= 0 && n <= 99 && free == null) {
                            free = val;
                        }
                        if (n > now && n < now + 30 * 86400L && (k.contains("end") || k.contains("next") || k.contains("ts") || k.contains("time"))) {
                            if (ts == null || n < ((Number) ts).longValue()) ts = val;
                        }
                    }
                }
                out.add(new Object[]{type, free, ts});
                return;
            }
            for (Object o : mm.values()) scanSearch(o, out, depth + 1);
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) scanSearch(o, out, depth + 1);
        }
    }

    /** 三兵营实时等级：321盾/322矛/323弓。 */
    static int[] barrackLevels(WjdrEngine engine) {
        int[] lv = new int[4];
        try {
            Map<String, Object> si = engine.notifyData("notify_shelter_info");
            if (si == null) return lv;
            Object bi = si.get("building_info");
            java.util.List<Map<String, Object>> rows = new ArrayList<>();
            if (bi instanceof Map) {
                for (Object v : ((Map<?, ?>) bi).values()) {
                    Map<String, Object> mm = ExecUtil.mapOf(v);
                    if (mm != null) rows.add(mm);
                }
            } else {
                rows = ExecUtil.mapsOf(bi);
            }
            for (Map<String, Object> e : rows) {
                long bid = ExecUtil.longOf(e.get("bid"));
                Map<String, Object> base = ExecUtil.mapOf(e.get("base"));
                long l = base != null ? ExecUtil.longOf(base.get("lv")) : ExecUtil.longOf(e.get("lv"));
                if (bid >= 321 && bid <= 323) lv[(int) (bid - 320)] = (int) l;
            }
        } catch (Throwable ignored) {
        }
        return lv;
    }

    private static List<Long> barrackIds(WjdrEngine engine) {
        List<Long> out = new ArrayList<>();
        Map<String, Object> bi = engine.notifyContent("notify_barrack_init");
        if (bi == null) return out;
        for (Map<String, Object> mm : ExecUtil.mapsOf(bi.get("data"))) {
            long id = ExecUtil.longOf(mm.get("bid"));
            if (id <= 0) id = ExecUtil.longOf(mm.get("id"));
            if (id > 0) out.add(id);
        }
        return out;
    }

    // ---------- 军医所：治疗伤兵 + 联盟求助 ----------

    static DailyResult medic(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            Map<String, Object> hi = engine.notifyData("notify_hospital_init");
            // 形状防御：wound_soldiers 可能为 [{k,v}] 或 {soldiers:[{k,v}]}
            Object w = hi == null ? null : hi.get("wound_soldiers");
            if (w instanceof Map) w = ((Map<?, ?>) w).get("soldiers");
            Map<Long, Long> wound = ExecUtil.kvPairs(w);
            long total = 0;
            for (long v : wound.values()) total += v;
            boolean doHeal = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "medic", "heal", true);
            if (total <= 0) {
                m.skip("无伤兵");
                m.subSkip("heal", "无伤兵");
                mod(engine, "medic", "heal", "当前无伤兵 · " + new java.text.SimpleDateFormat(
                        "HH:mm", java.util.Locale.CHINA).format(new java.util.Date()) + "检查");
            } else if (!doHeal) {
                m.skip("治疗已关闭");
                m.subSkip("heal", "已关闭");
            } else {
                List<Map<String, Object>> batch = new ArrayList<>();
                for (Map.Entry<Long, Long> e : wound.entrySet()) {
                    if (e.getValue() > 0) batch.add(ExecUtil.kv(e.getKey(), e.getValue()));
                }
                GameMessage rsp = engine.request("req_hospital_heal_soldier",
                        ExecUtil.args("soldiers", batch, "immediate", false));
                int code = ExecUtil.codeOf(rsp);
                if (ExecUtil.ok(rsp)) {
                    m.claimed("治疗" + total + "伤兵");
                    m.subOk("heal", "治疗" + total + "伤兵");
                    mod(engine, "medic", "heal", "治疗中 " + total + " 伤兵");
                }
                else if (code == 2301) { m.skip("上批仍在治疗中"); m.subSkip("heal", "上批治疗中"); }
                else if (code == 51) { m.skip("资源不足，未治疗"); m.subSkip("heal", "资源不足"); }
                else { m.fail("治疗码" + code); m.subFail("heal", "码" + code); }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "军医所");
            m.fail("治疗:" + DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        if (FnParams.subOn(engine.appContext(),
                engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                "medic", "help_all", true)) {
            try {
                GameMessage help = engine.request("req_alliance_help_all", null);
                if (ExecUtil.ok(help)) { m.claimed("联盟一键求助/互助"); m.subOk("help_all", "已互助全盟"); }
                else { m.skip("联盟求助不可用(码" + ExecUtil.codeOf(help) + ")"); m.subSkip("help_all", "码" + ExecUtil.codeOf(help)); }
            } catch (Exception e) {
                if (sessionLost(e)) return DailyTasks.classifyException(e, "军医所");
                m.skip("联盟求助未执行");
            }
        }
        DailyResult rr = new DailyResult("medic", "军医所", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 兵营训练：三营独立收兵/训练 + 实时状态 ----------

    static DailyResult barracks(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            android.content.Context ctx = engine.appContext();
            String fpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long sid = engine.lastServerId();
            captureBarrackState(engine);
            // 1. 三营独立收兵（固定 bid 321盾/322矛/323弓）
            String[] collectIds = {"", "collect_shield", "collect_spear", "collect_bow"};
            long[] bids = {0, 321, 322, 323};
            for (int line = 1; line <= 3; line++) {
                if (!FnParams.subOn(ctx, fpid, sid, "barracks", collectIds[line], true)) continue;
                // 原版参数：arr_bid = [建筑id] 纯数字列表（321盾/322矛/323弓）
                GameMessage rsp = engine.request("req_barrack_recruit_soldier",
                        ExecUtil.args("arr_bid", java.util.Collections.singletonList(bids[line])));
                int code = ExecUtil.codeOf(rsp);
                if (ExecUtil.ok(rsp)) {
                    m.claimed("收" + "盾矛弓".charAt(line - 1) + "兵");
                    m.subOk(collectIds[line], "已收" + "盾矛弓".charAt(line - 1) + "兵");
                } else {
                    m.skip("收" + "盾矛弓".charAt(line - 1) + "码" + code);
                    m.subSkip(collectIds[line], "码" + code
                            + (code == 2104 ? "（无可收）" : ""));
                }
            }
            // 2. 三营独立训练（等级：auto=该营最高已解锁；Tn 未解锁回退）
            org.json.JSONObject bp = FnParams.params(ctx, fpid, sid, "barracks");
            long batch = Math.max(10, Math.min(1000, bp.optInt("batch", 200)));
            // 档位推导（对齐原版 DailyBarrackState.availableOptions + DailyBarrackCatalog）：
            // 可训练档 = 兵营建筑等级(notify_shelter_info.building_info) ≥ 该档所需兵营等级
            //           且科技解锁（T1-10 免科技；T11 需 spar 科技 7007/8007/9007；T12 需 refine
            //           科技 10006/11006/12006，均按 盾/矛/弓 顺序）。旧口径按"已拥有士兵最高档"
            //           推导，在兵营已升级但尚未练过高档兵时会永远取旧档（真机事故：T11/T10/T11
            //           兵营被当成 T3 续训），仅作为建筑 notify 缺失时的回退。
            int[] maxTier = barrackUnlockTiers(engine);
            String tierSource = "building";
            if (maxTier == null) {
                tierSource = "soldiers";
                maxTier = new int[4];
                Map<Long, Long> sol = ExecUtil.soldiers(engine);
                // 士兵id规则（原版 DailyBarrackCatalog）：兵种*10000 + 等级*100（11000=盾T10, 11100=盾T11）
                for (long id : sol.keySet()) {
                    int line = (int) (id / 10000);
                    int idx = (int) ((id % 10000) / 100);
                    if (line >= 1 && line <= 3 && idx > maxTier[line]) maxTier[line] = idx;
                }
            }
            // 已解锁档写入状态表（供等级下拉动态取选项；tier_src 便于排查口径来源）
            try {
                org.json.JSONObject ms = FnParams.modState(ctx, fpid, sid, "barracks");
                ms.put("max_shield", maxTier[1]);
                ms.put("max_spear", maxTier[2]);
                ms.put("max_bow", maxTier[3]);
                ms.put("batch", batch);
                ms.put("tier_src", tierSource);
                FnParams.saveModState(ctx, fpid, sid, "barracks", ms);
            } catch (Exception ignored) {
            }
            String[] trainIds = {"", "train_shield", "train_spear", "train_bow"};
            StringBuilder trNote = new StringBuilder();
            int trOk = 0;
            for (int line = 1; line <= 3; line++) {
                if (!FnParams.subOn(ctx, fpid, sid, "barracks", trainIds[line], true)) continue;
                String sel = FnParams.strategy(ctx, fpid, sid, "barracks", trainIds[line], "auto");
                int parsed = 0;
                if (!"auto".equals(sel)) {
                    try {
                        parsed = Integer.parseInt(sel.trim());
                    } catch (NumberFormatException nfe) {
                        // 历史脏数据（曾把 map 串写进 strategy）→ 回退 auto，不再抛异常中断整个兵营任务
                        parsed = 0;
                    }
                }
                int useTier = parsed > 0 ? Math.min(parsed, maxTier[line] == 0 ? 12 : maxTier[line])
                        : maxTier[line];
                if (useTier <= 0) {
                    trNote.append("盾矛弓".charAt(line - 1)).append("无兵力数据 ");
                    m.subSkip(trainIds[line], "无兵力数据");
                    continue;
                }
                long id = line * 10000L + useTier * 100L;
                GameMessage rsp = engine.request("req_barrack_train_soldier",
                        ExecUtil.args("soldier_id", id, "soldier_num", batch, "immediate", false));
                int code = ExecUtil.codeOf(rsp);
                if (ExecUtil.ok(rsp)) {
                    trOk++;
                    trNote.append("盾矛弓".charAt(line - 1)).append("T").append(useTier).append("×").append(batch).append(" ");
                    m.subOk(trainIds[line], "续训T" + useTier + "×" + batch);
                } else if (code == 2103) {
                    trNote.append("盾矛弓".charAt(line - 1)).append("容量满 ");
                    m.subSkip(trainIds[line], "兵营容量满");
                } else if (code == 2104) {
                    m.subSkip(trainIds[line], "无可收兵");
                } else if (code == 2101) {
                    // 原版语义：上一批尚未训练完成（正常状态，等待即可）
                    trNote.append("盾矛弓".charAt(line - 1)).append("训练中 ");
                    m.subSkip(trainIds[line], "上批训练中");
                } else if (code == 2106) {
                    m.subSkip(trainIds[line], "该档未解锁");
                } else if (code == 2107) {
                    m.subSkip(trainIds[line], "兵营升级中");
                } else if (code == 2105) {
                    m.subSkip(trainIds[line], "兵营类型不匹配");
                } else {
                    trNote.append("盾矛弓".charAt(line - 1)).append("码").append(code).append(" ");
                    m.subFail(trainIds[line], "训练码" + code);
                }
            }
            if (trOk > 0) m.claimed("训练" + trNote.toString().trim());
            else if (trNote.length() > 0) m.skip("训练未成：" + trNote.toString().trim());
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "兵营训练");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult r = new DailyResult("barracks", "兵营训练", m.cat(), m.note() + " " + m.sb);
        r.rewards = m.rewardsText();
        r.subResults.putAll(m.subResults);
        return r;
    }

    // ================= 兵营可训练档位（对齐原版 DailyBarrackCatalog/DailyBarrackState） =================

    /** T1..T10 解锁所需兵营建筑等级（原版 BARRACK_LEVELS）；T11/T12 需 30 级 + 科技。 */
    private static final int[] BARRACK_TIER_LEVELS = {1, 4, 7, 11, 13, 16, 19, 22, 26, 30};
    /** T11 所需 spar 科技（盾/矛/弓）；T12 所需 refine 科技。 */
    private static final long[] TIER11_TECH = {7007, 8007, 9007};
    private static final long[] TIER12_TECH = {10006, 11006, 12006};

    /**
     * 兵种 T 级解锁判定（实证修正 2026-10-09，与 vault-server ServerTownTasks 同口径）：
     * T1~T10 由<b>大熔炉（bid 301）</b>等级按阈值表解锁——兵营建筑（321~323）等级不决定 T 级
     * （原实现误用兵营等级，熔炉 13 级账号被误判 T4，实际 T5）。熔炉数据缺失时回退兵营等级（旧行为）。
     * T11/T12 仍由 spar/refine 科技门控。
     */
    static int[] barrackUnlockTiers(WjdrEngine engine) {
        try {
            Map<String, Object> shelter = engine.notifyData("notify_shelter_info");
            if (shelter == null) return null;
            Object bi = shelter.get("building_info");
            java.util.List<Object> entries = new java.util.ArrayList<>();
            if (bi instanceof Map) entries.addAll(((Map<?, ?>) bi).values());
            else if (bi instanceof List) entries.addAll((List<?>) bi);
            long[] levels = new long[4];
            long furnace = 0;
            for (Object raw : entries) {
                Map<String, Object> b = ExecUtil.mapOf(raw);
                if (b == null) continue;
                long bid = ExecUtil.longOf(b.get("bid"));
                Map<String, Object> base = ExecUtil.mapOf(b.get("base"));
                long lv = ExecUtil.longOf(base != null ? base.get("lv") : b.get("lv"));
                if (bid == 301 && lv > furnace) furnace = lv;
                if (bid >= 321 && bid <= 323 && lv > levels[(int) (bid - 320)]) {
                    levels[(int) (bid - 320)] = lv;
                }
            }
            if (furnace <= 0 && levels[1] <= 0 && levels[2] <= 0 && levels[3] <= 0) return null;
            if (furnace > 0) {
                levels[1] = furnace;
                levels[2] = furnace;
                levels[3] = furnace;
            }
            Map<Long, Long> spar = researchLevels(engine, "notify_spar_science_all_research");
            Map<Long, Long> refine = researchLevels(engine, "notify_refine_science_all_research");
            return computeUnlockTiers(levels, spar, refine);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 纯函数：按原版规则算每营已解锁最高档（下标 1/2/3 = 盾/矛/弓）。
     * 规则：t<=10 只看兵营等级表；t=11 加 spar 科技门控；t=12 加 refine 科技门控。
     * 科技表为 null（notify 缺失）时按空表处理——保守拦截高档（对齐原版：科技查不到就不放行）。
     */
    static int[] computeUnlockTiers(long[] levels, Map<Long, Long> spar, Map<Long, Long> refine) {
        Map<Long, Long> sparSafe = spar != null ? spar : java.util.Collections.emptyMap();
        Map<Long, Long> refineSafe = refine != null ? refine : java.util.Collections.emptyMap();
        int[] tiers = new int[4];
        for (int line = 1; line <= 3; line++) {
            int best = 0;
            for (int t = 12; t >= 1; t--) {
                int need = t <= 10 ? BARRACK_TIER_LEVELS[t - 1] : 30;
                if (levels[line] < need) continue;
                if (t == 11 && sparSafe.getOrDefault(TIER11_TECH[line - 1], 0L) < 1) continue;
                if (t == 12 && refineSafe.getOrDefault(TIER12_TECH[line - 1], 0L) < 1) continue;
                best = t;
                break;
            }
            tiers[line] = best;
        }
        return tiers;
    }

    /** 科技等级表：notify 的 researched 列表（{tid,lev} 形状；缺 notify 返回 null=不校验科技）。 */
    private static Map<Long, Long> researchLevels(WjdrEngine engine, String proto) {
        try {
            Map<String, Object> data = engine.notifyData(proto);
            if (data == null) return null;
            Object researched = data.get("researched");
            java.util.List<Object> entries = new java.util.ArrayList<>();
            if (researched instanceof Map) entries.addAll(((Map<?, ?>) researched).values());
            else if (researched instanceof List) entries.addAll((List<?>) researched);
            Map<Long, Long> out = new java.util.HashMap<>();
            for (Object raw : entries) {
                Map<String, Object> r = ExecUtil.mapOf(raw);
                if (r == null) continue;
                long tid = ExecUtil.longOf(r.get("tid"));
                long lev = ExecUtil.longOf(r.get("lev"));
                if (tid > 0 && lev > 0) out.put(tid, lev);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 三兵营实时状态：notify_barrack_init 逐营解析（训练中·HH:mm可收 / 空闲）。 */    static void captureBarrackState(WjdrEngine engine) {
        try {
            Map<String, Object> bi = engine.notifyData("notify_barrack_init");
            if (bi == null) return;
            String raw = String.valueOf(bi);
            DailyTasks.debugLog(engine.appContext(), "barrack raw=" + raw.substring(0, Math.min(500, raw.length())));
            long now = System.currentTimeMillis() / 1000L;
            java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
            String[] collectIds = {"", "collect_shield", "collect_spear", "collect_bow"};
            // 深扫：找含 bid/建筑id∈{321,322,323} 的子表，取其中最近未来时间戳为"可收时间"
            for (int line = 1; line <= 3; line++) {
                long bidWant = 320 + line;
                long best = 0;
                best = scanBarrackEnd(bi, bidWant, now, 0);
                if (best > now) {
                    mod(engine, "barracks", collectIds[line], "训练中 · " + hm.format(new java.util.Date(best * 1000)) + "可收");
                } else {
                    mod(engine, "barracks", collectIds[line], best > 0 ? "有完工批次可收" : "空闲（无训练队列数据）");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 在兵营通知里找指定建筑 id 的最近未来结束时间（防御式：任何 end/finish/time 键）。 */
    private static long scanBarrackEnd(Object v, long bidWant, long now, int depth) {
        if (depth > 5 || v == null) return 0;
        long best = 0;
        if (v instanceof Map) {
            Map<?, ?> mm = (Map<?, ?>) v;
            boolean mine = false;
            for (String k : new String[]{"bid", "id", "building_id"}) {
                Object b = mm.get(k);
                if (b instanceof Number && ((Number) b).longValue() == bidWant) {
                    mine = true;
                    break;
                }
            }
            if (mine) {
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    Object val = e.getValue();
                    if (val instanceof Number) {
                        long n = ((Number) val).longValue();
                        String k = String.valueOf(e.getKey()).toLowerCase();
                        if (n > now && n < now + 7 * 86400L
                                && (k.contains("end") || k.contains("finish") || k.contains("time") || k.contains("ts"))) {
                            if (best == 0 || n < best) best = n;
                        }
                    }
                }
                if (best > 0) return best;
            }
            for (Object o : mm.values()) {
                long r = scanBarrackEnd(o, bidWant, now, depth + 1);
                if (r > 0 && (best == 0 || r < best)) best = r;
            }
        } else if (v instanceof List) {
            for (Object o : (List<?>) v) {
                long r = scanBarrackEnd(o, bidWant, now, depth + 1);
                if (r > 0 && (best == 0 || r < best)) best = r;
            }
        }
        return best;
    }

    // ---------- 宠物：技能 + 寻宝领奖/派遣 + 联盟宝箱 ----------

    static DailyResult pet(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(2000);
            android.content.Context pctx = engine.appContext();
            String pfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long psid = engine.lastServerId();
            // 1. 批量开战斗技能
            List<Object> skills = new ArrayList<>();
            for (long s : new long[]{550051L, 550071L, 550081L, 550091L, 550101L,
                    550111L, 550121L, 550131L, 550141L}) {
                skills.add(s);
            }
            if (FnParams.subOn(pctx, pfpid, psid, "pet", "skills", true)) {
                GameMessage sk = engine.request("req_pet_use_skill",
                        ExecUtil.args("skill_id_lst", skills));
                if (ExecUtil.ok(sk)) { m.claimed("宠物技能"); m.subOk("skills", "技能已开"); }
                else { m.skip("宠物技能冷却/不可用(码" + ExecUtil.codeOf(sk) + ")"); m.subSkip("skills", "码" + ExecUtil.codeOf(sk)); }
            }
            // 2. 寻宝：先批量领联盟宝箱，再按通知逐区领奖+派遣
            if (FnParams.subOn(pctx, pfpid, psid, "pet", "alliance_chest", true)) {
                GameMessage ab = engine.request("req_pet_adventure_batch_take_alliance_reward", null);
                if (ExecUtil.ok(ab)) { m.claimed("联盟寻宝宝箱"); m.subOk("alliance_chest", "宝箱已领"); }
                else { m.skip("联盟宝箱无可领(码" + ExecUtil.codeOf(ab) + ")"); m.subSkip("alliance_chest", "码" + ExecUtil.codeOf(ab)); }
            }

            Map<String, Object> adv = engine.notifyContent("notify_pet_adventure_info");
            Map<String, Object> petInfo = engine.notifyData("notify_pet_info");
            List<Long> petIds = new ArrayList<>();
            if (petInfo != null) {
                for (Map<String, Object> p : ExecUtil.mapsOf(petInfo.get("info"))) {
                    long id = ExecUtil.longOf(p.get("id"));
                    if (id > 0) petIds.add(id);
                }
            }
            if (adv != null && !petIds.isEmpty() && FnParams.subOn(pctx, pfpid, psid, "pet", "adventure", true)) {
                int claimed = 0, sent = 0;
                for (Map<String, Object> area : ExecUtil.mapsOf(adv.get("areas"))) {
                    long aid = ExecUtil.longOf(area.get("id"));
                    if (aid <= 0) continue;
                    GameMessage r = engine.request("req_pet_adventure_get_reward",
                            ExecUtil.args("id", aid));
                    if (ExecUtil.ok(r)) claimed++;
                }
                for (Map<String, Object> area : ExecUtil.mapsOf(adv.get("areas"))) {
                    long aid = ExecUtil.longOf(area.get("id"));
                    Object st = area.get("status");
                    if (aid <= 0 || (st != null && ExecUtil.longOf(st) != 0)) continue;
                    for (long pid : petIds) {
                        GameMessage r = engine.request("req_pet_adventure_dispatch",
                                ExecUtil.args("id", aid, "pet_id", pid));
                        if (ExecUtil.ok(r)) {
                            sent++;
                            break;
                        }
                    }
                }
                if (claimed > 0) { m.claimed("寻宝领奖×" + claimed); m.subOk("adventure", "领奖×" + claimed + (sent > 0 ? " 派遣×" + sent : "")); }
                else { m.skip("寻宝无已完成区域"); m.subSkip("adventure", sent > 0 ? "无已完成区域，派遣×" + sent : "无已完成区域"); }
                if (sent > 0) {
                    m.claimed("派遣宠物×" + sent);
                    mod(engine, "pet", "adventure", "上轮派遣 " + sent + " 只宠物寻宝");
                }
            } else {
                m.skip("寻宝数据未同步");
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "宠物");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("pet", "宠物", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 专家游历：领行动力 + 全额挂机 ----------

    static DailyResult expert(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            GameMessage info = engine.request("req_travel_info", null);
            if (!ExecUtil.ok(info)) {
                m.skip("专家游历未解锁(码" + ExecUtil.codeOf(info) + ")");
            } else {
                GameMessage recv = engine.request("req_travel_recv_energy", null);
                long energy = -1;
                Map<String, Object> c = recv != null ? recv.getContent() : null;
                for (String k : new String[]{"energy", "stamina", "power", "cnt", "action"}) {
                    if (c != null && c.containsKey(k)) {
                        energy = ExecUtil.longOf(c.get(k));
                        break;
                    }
                }
                if (energy < 0 && info.getContent() != null) {
                    for (String k : new String[]{"energy", "stamina", "power", "cnt", "action"}) {
                        if (info.getContent().containsKey(k)) {
                            energy = ExecUtil.longOf(info.getContent().get(k));
                            break;
                        }
                    }
                }
                if (ExecUtil.ok(recv)) { m.claimed("行动力已领"); m.subOk("energy", "行动力已领"); }
                else m.subSkip("energy", "行动力不可领");
                if (FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "expert", "buy", false)) {
                    for (int i = 0; i < 4; i++) {
                        GameMessage be = engine.request("req_travel_buy_energy", null);
                        if (!ExecUtil.ok(be)) break;
                        m.claimed("购体力+1");
                    }
                }
                // 策略：保留 N 点行动力不挂机（0=用全部）
                long keep = 0;
                try {
                    keep = Long.parseLong(FnParams.strategy(engine.appContext(),
                            engine.lastFpid(), engine.lastServerId(), "expert", "afk", "0").trim());
                } catch (Throwable ignored) {
                }
                long use = Math.max(0, energy - keep);
                if (use > 0) {
                    GameMessage afk = engine.request("req_normal_travel_afk",
                            ExecUtil.args("cnt", use));
                    if (ExecUtil.ok(afk)) {
                        m.claimed("挂机游历×" + use + (keep > 0 ? "（保留" + keep + "）" : ""));
                        m.subOk("afk", "挂机×" + use);
                        engine.request("req_travel_atk_info_noted", null);
                    } else {
                        m.skip("挂机未执行(码" + ExecUtil.codeOf(afk) + ")");
                        m.subSkip("afk", "码" + ExecUtil.codeOf(afk));
                    }
                } else {
                    m.skip("行动力" + energy + "，按保留阈值跳过");
                    m.subSkip("afk", "按保留阈值跳过");
                }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "专家游历");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("expert", "专家游历", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 晨曦岛：回礼 + 生命之树 + 伐木小屋 + 加工厂 ----------

    static DailyResult homeland(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            android.content.Context hctx = engine.appContext();
            String hfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long hsid = engine.lastServerId();
            if (FnParams.subOn(hctx, hfpid, hsid, "homeland", "free", true)) {
                tryStep(engine, m, "晨曦回礼", "free", "req_homeland_gain_free_production", null);
            }
            if (FnParams.subOn(hctx, hfpid, hsid, "homeland", "tree", true)) {
                tryStep(engine, m, "生命之树", "tree", "req_homeland_take_life_tree_coin", null);
            }
            // 伐木小屋/加工厂需要坐标：从家园通知里找 point
            if (!FnParams.subOn(hctx, hfpid, hsid, "homeland", "logging", true)) {
                m.subSkip("logging", "已关闭");
            } else {
                Map<String, Object> hi = engine.notifyContent("notify_homeland_info");
                List<Map<String, Object>> pts = new ArrayList<>();
                if (hi != null) {
                    collectPoints(hi, pts, 0);
                }
                if (pts.isEmpty()) {
                    m.skip("伐木点未同步");
                } else {
                    Map<String, Object> p = pts.get(0);
                    tryStep(engine, m, "伐木小屋", "logging", "req_homeland_take_logging_cabin_tree",
                            ExecUtil.args("point", p));
                    if (pts.size() > 1) {
                        tryStep(engine, m, "木材加工厂", "req_homeland_take_logging_site_coin",
                                ExecUtil.args("point", pts.get(1)));
                    }
                }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "晨曦岛");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("homeland", "晨曦岛", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    private static void collectPoints(Map<String, Object> m, List<Map<String, Object>> out, int depth) {
        if (depth > 3 || out.size() >= 2) return;
        for (Object v : m.values()) {
            if (v instanceof Map) {
                Map<?, ?> mm = (Map<?, ?>) v;
                if (mm.containsKey("x") && mm.containsKey("y")) {
                    out.add(ExecUtil.point(ExecUtil.longOf(mm.get("x")), ExecUtil.longOf(mm.get("y"))));
                    if (out.size() >= 2) return;
                } else {
                    collectPoints((Map<String, Object>) mm, out, depth + 1);
                }
            } else if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    if (o instanceof Map) collectPoints((Map<String, Object>) o, out, depth + 1);
                }
            }
        }
    }

    // ---------- 火晶提炼：普通提炼至每日上限 ----------

    static DailyResult fireCrystal(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            int done = 0;
            for (int i = 0; i < 40; i++) {
                GameMessage rsp = engine.request("req_spar_purify", null);
                if (ExecUtil.ok(rsp)) {
                    done++;
                } else {
                    int code = ExecUtil.codeOf(rsp);
                    if (code == 10051 || code == 10052 || code == 51 || code == 2) {
                        break;   // 未解锁/材料不足/到上限
                    }
                    if (i == 0) {
                        m.skip("提炼不可用(码" + code + ")");
                    }
                    break;
                }
            }
            if (done > 0) {
                m.claimed("提炼×" + done);
                m.subOk("purify", "提炼×" + done);
                mod(engine, "fire_crystal", "purify", "上轮提炼 " + done + " 次");
            }
            else if (m.sb.length() == 0) { m.skip("今日提炼已达上限或未解锁"); m.subSkip("purify", "达上限或未解锁"); }
            // 超级提炼（子项开关，默认关；次数可选配 super_times，默认 1 次/轮）
            org.json.JSONObject fp = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "fire_crystal");
            boolean superOn = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "fire_crystal", "super", false);
            int superN = superOn ? Math.max(1, Math.min(50, fp.optInt("super_times", 1))) : 0;
            int superDone = 0;
            for (int i = 0; i < superN; i++) {
                GameMessage sp = engine.request("req_spar_super_purify",
                        ExecUtil.args("count", 1L));
                if (ExecUtil.ok(sp)) superDone++;
                else break;
            }
            if (superDone > 0) { m.claimed("超炼×" + superDone); m.subOk("super", "超炼×" + superDone); }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "火晶提炼");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("fire_crystal", "火晶提炼", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 地心探索爬塔：按星期扫荡今日开放塔 ----------

    static DailyResult climbtower(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            GameMessage info = engine.request("req_climbtower_info", null);
            if (!ExecUtil.ok(info)) {
                m.skip("地心探索未解锁(码" + ExecUtil.codeOf(info) + ")");
            } else {
                int wd = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);   // 1=周日
                long tower = todayTower(wd);
                mod(engine, "climbtower", "sweep", "今日开放：" + towerName(tower) + "（周" + "日一二三四五六".charAt(wd - 1) + "）");
                GameMessage sw = engine.request("req_climbtower_sweep",
                        ExecUtil.args("tower_id", tower));
                int code = ExecUtil.codeOf(sw);
                if (ExecUtil.ok(sw)) m.claimed("扫荡塔" + tower);
                else if (code == 7401 || code == 7404 || code == 2) m.skip("今日未开放或已扫荡");
                else m.skip("扫荡码" + code);
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "地心探索");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("climbtower", "地心探索", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    static String towerName(long tower) {
        switch ((int) tower) {
            case 1: return "英雄之地";
            case 2: return "群兽洞窟";
            case 3: return "宝石矿洞";
            case 4: return "地下科研所";
            case 5: return "装备锻炉";
            default: return "大地之心";
        }
    }

    /** 区域表：1英雄之地(一二) 2/3群兽·宝石(三四) 4/5科研·锻炉(五六) 6大地之心(日)。 */
    private static long todayTower(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:
            case Calendar.TUESDAY:
                return 1;
            case Calendar.WEDNESDAY:
                return 2;
            case Calendar.THURSDAY:
                return 3;
            case Calendar.FRIDAY:
                return 4;
            case Calendar.SATURDAY:
                return 5;
            default:
                return 6;
        }
    }

    // ---------- 竞技场：挑战弱敌 + 每日奖励 ----------

    static DailyResult arena(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            GameMessage opp = engine.request("req_arena_get_opponent", null);
            if (ExecUtil.ok(opp)) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (String k : new String[]{"opponents", "data", "list"}) {
                    list = ExecUtil.mapsOf(opp.getContent().get(k));
                    if (!list.isEmpty()) break;
                }
                // 选战力最低的打（字段名防御式取）
                list.sort((a, b) -> Long.compare(powerOf(a), powerOf(b)));
                List<Long> heroes = squadHeroes(engine);
                org.json.JSONObject ap = FnParams.params(engine.appContext(),
                        engine.lastFpid(), engine.lastServerId(), "arena");
                // 次数：策略chips优先（3/5/8/10场），无策略再兼容旧 params.max_fights
                int maxFights = 5;
                org.json.JSONObject strat = ap.optJSONObject("strategy");
                if (strat != null && strat.has("fight")) {
                    maxFights = strat.optInt("fight", 5);
                } else if (ap.has("max_fights")) {
                    maxFights = ap.optInt("max_fights", 5);
                }
                maxFights = Math.max(1, Math.min(10, maxFights));
                int wins = 0, fights = 0;
                for (Map<String, Object> o : list) {
                    if (fights >= maxFights || heroes.isEmpty()) break;
                    long oid = ExecUtil.longOf(o.get("uid"));
                    if (oid <= 0) oid = ExecUtil.longOf(o.get("id"));
                    if (oid <= 0) continue;
                    List<Object> heroArray = new ArrayList<>(heroes);
                    GameMessage f = engine.request("req_arena_fight", ExecUtil.args(
                            "opponent_type", ExecUtil.longOf(o.get("type")) == 0 ? 1 : ExecUtil.longOf(o.get("type")),
                            "opponent_id", oid,
                            "hero_array", heroArray,
                            "version", 26042101L));
                    fights++;
                    if (ExecUtil.ok(f)) wins++;
                    ExecUtil.sleep(800);
                }
                if (fights > 0) {
                    m.claimed("挑战" + fights + "场胜" + wins);
                    m.subOk("fight", "挑战" + fights + "胜" + wins);
                    mod(engine, "arena", "fight", "上轮挑战 " + fights + " 场 · 胜 " + wins);
                }
                else { m.skip("无对手数据"); m.subSkip("fight", "无对手数据"); }
            } else {
                m.skip("竞技场未解锁(码" + ExecUtil.codeOf(opp) + ")");
            }
            GameMessage daily = engine.request("req_arena_get_daily_reward", null);
            int dc = ExecUtil.codeOf(daily);
            if (ExecUtil.ok(daily)) { m.claimed("每日奖励"); m.subOk("daily", "每日奖励已领"); }
            else if (dc == 2926 || dc == 2925) { m.skip(dc == 2926 ? "每日奖励已领" : "次数未达标"); m.subSkip("daily", dc == 2926 ? "已领" : "次数未达标"); }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "竞技场");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("arena", "竞技场", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    private static long powerOf(Map<String, Object> o) {
        for (String k : new String[]{"power", "fight", "fight_power", "score", "strength"}) {
            Object v = o.get(k);
            if (v instanceof Number) return ExecUtil.longOf(v);
        }
        return Long.MAX_VALUE;
    }

    static List<Long> squadHeroes(WjdrEngine engine) {
        Map<Long, List<Long>> squads = ExecUtil.clientSquads(engine);
        if (!squads.isEmpty()) return squads.values().iterator().next();
        List<Long> all = ExecUtil.allHeroes(engine);
        return all.subList(0, Math.min(5, all.size()));
    }

    // ---------- 公共 ----------

    static void tryStep(WjdrEngine engine, ExecUtil.Multi m, String name, String proto,
                        Map<String, Object> args) throws Exception {
        tryStep(engine, m, name, null, proto, args);
    }

    /** 带子项id的 tryStep（B表子项级记录）。 */
    static void tryStep(WjdrEngine engine, ExecUtil.Multi m, String name, String subId,
                        String proto, Map<String, Object> args) throws Exception {
        GameMessage rsp = engine.request(proto, args);
        int code = ExecUtil.codeOf(rsp);
        if (ExecUtil.ok(rsp)) {
            m.claimed(name);
            m.rewards(name, rsp);
            if (subId != null) m.subOk(subId, name + "✓");
        } else if (code == 15005 || code == 15029 || code == 15048 || code == 2 || code == 7401 || code == 7404) {
            m.skip(name + "无可收");
            if (subId != null) m.subSkip(subId, "无可收");
        } else {
            m.skip(name + "码" + code);
            if (subId != null) m.subFail(subId, "码" + code);
        }
    }

    static boolean sessionLost(Exception e) {
        return DailyTasks.classifyException(e, "x").category == DailyResult.Category.SESSION_CLOSED;
    }
}