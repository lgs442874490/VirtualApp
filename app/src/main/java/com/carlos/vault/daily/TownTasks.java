package com.carlos.vault.daily;

import com.carlos.vault.daily.gather.GatherConfigV2;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.pet.PetSkillCatalog;
import com.carlos.vault.wjdr.pet.PetSupport;
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
        // 联盟互助已收敛到 alliance_help（常驻响应器 + 定时兜底），军医所不再附带重复请求
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
            // 收发动作会改变兵营队列，服务器会推送新 notify_barrack_init——补捕一次刷新状态行
            engine.collectNotifies(800);
            captureBarrackState(engine);
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
            if (bi == null) {
                // 通知未同步也要覆盖旧文本，避免"永远几天前快照"的假象
                for (String sub : new String[]{"collect_shield", "collect_spear", "collect_bow"}) {
                    mod(engine, "barracks", sub, "兵营通知未同步");
                }
                return;
            }
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
            long now = petNowSec(engine);
            org.json.JSONObject pp = FnParams.params(pctx, pfpid, psid, "pet");
            Map<String, Object> petInfo = engine.notifyData("notify_pet_info");
            Map<String, Object> exInfo = engine.notifyData("notify_pet_ex_info");
            java.util.Set<Long> ownedPets = PetSupport.ownedPetIds(petInfo);
            java.util.Set<Long> ownedSkills = PetSupport.ownedSkillIds(petInfo, exInfo);
            if (!ownedSkills.isEmpty()) {
                mod(engine, "pet", "owned", PetSupport.csv(ownedSkills));
            }

            // 1. 宠物技能：默认只自动使用「发育类」，战斗类由用户逐项勾选（selected_skills）。
            if (FnParams.subOn(pctx, pfpid, psid, "pet", "skills", true)) {
                petApplySkills(engine, pctx, pfpid, psid, pp, ownedSkills, exInfo, now, m);
            } else {
                m.subSkip("skills", "自动使用已关闭");
            }

            // 2. 盟友分享的寻宝宝箱（每日 5 次；用户可关闭）。
            if (FnParams.subOn(pctx, pfpid, psid, "pet", "alliance_chest", true)) {
                GameMessage ab = engine.request("req_pet_adventure_batch_take_alliance_reward", null);
                if (ExecUtil.ok(ab)) { m.claimed("联盟寻宝宝箱"); m.subOk("alliance_chest", "宝箱已领"); }
                else {
                    int abCode = ExecUtil.codeOf(ab);
                    // 与网页版/服务端一致：这些码=暂无可领（非异常）。
                    boolean noReward = abCode == 11790 || abCode == 11792 || abCode == 11794
                            || abCode == 11795 || abCode == 11796 || abCode == 11797
                            || abCode == 11799 || abCode == 11803;
                    m.skip(noReward ? "联盟宝箱暂无可领" : ("联盟宝箱不可领(码" + abCode + ")"));
                    m.subSkip("alliance_chest", noReward ? "暂无可领" : ("码" + abCode));
                }
            } else {
                m.subSkip("alliance_chest", "自动领取已关闭");
            }

            // 3. 宠物寻宝：领已完成奖励 + 派遣空闲藏宝点（每轮最多 3 只）。
            Map<String, Object> adv = engine.notifyData("notify_pet_adventure_info");
            if (adv == null) {
                m.skip("寻宝数据未同步");
                m.subSkip("adventure", "通知未同步");
            } else if (!FnParams.subOn(pctx, pfpid, psid, "pet", "adventure", true)) {
                m.skip("寻宝已关闭");
                m.subSkip("adventure", "已关闭");
            } else {
                petRunAdventure(engine, adv, ownedPets, now, m);
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

    /** 服务器时钟秒；未校准时退回本机时间。 */
    private static long petNowSec(WjdrEngine engine) {
        try {
            long t = engine.serverClock().nowServerSec();
            if (t > 0) return t;
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() / 1000L;
    }

    /** selected_skills 存在即按其内容（空数组=全部关闭）；缺省=发育类技能。 */
    private static List<Long> petSelectedSkills(org.json.JSONObject pp) {
        List<Long> out = new ArrayList<>();
        org.json.JSONArray arr = pp == null ? null : pp.optJSONArray("selected_skills");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                long sid = arr.optLong(i, 0L);
                if (sid > 0 && !out.contains(sid)) out.add(sid);
            }
            return out;
        }
        for (long sid : PetSkillCatalog.developmentIds()) out.add(sid);
        return out;
    }

    private static void petApplySkills(WjdrEngine engine, android.content.Context ctx,
                                       String fpid, long sid, org.json.JSONObject pp,
                                       java.util.Set<Long> ownedSkills, Map<String, Object> exInfo,
                                       long now, ExecUtil.Multi m) throws Exception {
        List<Long> selected = petSelectedSkills(pp);
        if (selected.isEmpty()) {
            m.skip("未选择宠物技能");
            m.subSkip("skills", "未选择技能");
            return;
        }
        Map<Long, Long> lastUse = PetSupport.skillUseTimes(exInfo);
        for (Map.Entry<Long, Long> e : petStoredSkillUseTimes(ctx, fpid, sid).entrySet()) {
            Long prev = lastUse.get(e.getKey());
            if (prev == null || e.getValue() > prev) lastUse.put(e.getKey(), e.getValue());
        }
        List<Long> ready = new ArrayList<>();
        List<Long> cooling = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        List<Long> delegated = new ArrayList<>();
        boolean heavyByGather = false;
        try {
            heavyByGather = GatherConfigV2.parse(
                    FnParams.params(ctx, fpid, sid, "gather")).heavyLiftingEnabled;
        } catch (Throwable ignored) {
        }
        boolean ownedKnown = ownedSkills != null && !ownedSkills.isEmpty();
        for (Long skillId : selected) {
            if (skillId == null || skillId <= 0) continue;
            if (heavyByGather && skillId == 550031L) {
                delegated.add(skillId);
                continue;
            }
            if (ownedKnown && !ownedSkills.contains(skillId)) {
                missing.add(skillId);
                continue;
            }
            PetSkillCatalog.Skill sk = PetSkillCatalog.byId(skillId);
            int cd = sk == null ? 20 : sk.cooldownHours;
            Long last = lastUse.get(skillId);
            if (PetSupport.skillReady(last == null ? 0L : last, cd, now)) ready.add(skillId);
            else cooling.add(skillId);
        }

        List<String> usedNames = new ArrayList<>();
        List<String> coolNames = new ArrayList<>();
        int lastCode = 0;
        for (Long skillId : ready) {
            List<Object> one = new ArrayList<>();
            one.add(skillId);
            GameMessage r = engine.request("req_pet_use_skill",
                    ExecUtil.args("skill_id_lst", one));
            if (ExecUtil.ok(r)) {
                usedNames.add(PetSkillCatalog.nameOf(skillId));
                lastUse.put(skillId, now);
            } else {
                lastCode = ExecUtil.codeOf(r);
                if (lastCode == 11536 || lastCode == 11537 || lastCode == 11539 || lastCode == 11542) {
                    coolNames.add(PetSkillCatalog.nameOf(skillId));
                } else {
                    break;
                }
            }
        }
        petSaveSkillUseTimes(engine, ctx, fpid, sid, lastUse);
        StringBuilder sub = new StringBuilder();
        if (!usedNames.isEmpty()) sub.append("已开：").append(join(usedNames));
        if (!coolNames.isEmpty()) {
            if (sub.length() > 0) sub.append("；");
            sub.append("冷却/不可用：").append(join(coolNames));
        }
        if (!missing.isEmpty()) {
            if (sub.length() > 0) sub.append("；");
            sub.append("未拥有：").append(PetSupport.formatSkillNames(missing));
        }
        if (!delegated.isEmpty()) {
            if (sub.length() > 0) sub.append("；");
            sub.append("由采集接管：").append(PetSupport.formatSkillNames(delegated));
        }
        if (sub.length() == 0) sub.append("无可用技能");
        String text = sub.toString();
        if (!usedNames.isEmpty()) {
            m.claimed("宠物技能×" + usedNames.size());
            m.subOk("skills", text);
        } else {
            String why;
            if (ready.isEmpty()) {
                if (!missing.isEmpty()) why = "已选技能未拥有";
                else if (!delegated.isEmpty() && cooling.isEmpty()) why = "重物搬运已由采集接管";
                else why = "技能均未到冷却";
            } else {
                why = "技能不可用(码" + lastCode + ")";
            }
            m.skip(why);
            m.subSkip("skills", text);
        }
        mod(engine, "pet", "skills", text);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('、');
            sb.append(s);
        }
        return sb.toString();
    }

    private static Map<Long, Long> petStoredSkillUseTimes(android.content.Context ctx,
                                                          String fpid, long sid) {
        Map<Long, Long> out = new LinkedHashMap<>();
        try {
            String raw = FnParams.modState(ctx, fpid, sid, "pet").optString("skill_used", "");
            if (raw == null || raw.trim().isEmpty()) return out;
            org.json.JSONObject o = new org.json.JSONObject(raw);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    long skillId = Long.parseLong(k);
                    long t = o.optLong(k, 0L);
                    if (skillId > 0 && t > 0) out.put(skillId, t);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void petSaveSkillUseTimes(WjdrEngine engine, android.content.Context ctx,
                                             String fpid, long sid, Map<Long, Long> times) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            if (times != null) {
                for (Map.Entry<Long, Long> e : times.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                        o.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            }
            FnParams.saveModText(ctx, fpid, sid, "pet", "skill_used", o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 寻宝：先领已完成，再给空闲藏宝点派宠物；每轮最多 3 只，失败即停。 */
    private static void petRunAdventure(WjdrEngine engine, Map<String, Object> adv,
                                        java.util.Set<Long> ownedPets, long now,
                                        ExecUtil.Multi m) throws Exception {
        List<PetSupport.Area> areas = PetSupport.adventureAreas(adv);
        if (areas.isEmpty()) {
            m.skip("寻宝无藏宝点数据");
            m.subSkip("adventure", "无藏宝点数据");
            return;
        }
        int usedBefore = PetSupport.dailyAdventureUsed(adv);
        int claimed = 0;
        java.util.Set<Long> freedPets = new java.util.LinkedHashSet<>();
        for (PetSupport.Area a : areas) {
            if (!a.completed(now)) continue;
            GameMessage r = engine.request("req_pet_adventure_get_reward",
                    ExecUtil.args("id", a.id));
            if (ExecUtil.ok(r)) {
                claimed++;
                freedPets.add(a.petId);
                Map<String, Object> c = r.getContent();
                PetSupport.markAreaClaimed(adv, a.id,
                        c == null ? 0L : ExecUtil.longOf(c.get("refresh_index")),
                        c == null ? 0L : ExecUtil.longOf(c.get("refresh_time")));
            } else if (ExecUtil.codeOf(r) == 11787) {
                freedPets.add(a.petId);
            }
        }
        java.util.Set<Long> busy = new java.util.LinkedHashSet<>();
        for (PetSupport.Area a : areas) {
            if (!a.occupied()) continue;
            boolean freed = a.completed(now) && freedPets.contains(a.petId);
            if (!freed) busy.add(a.petId);
        }
        List<Long> pets = PetSupport.idlePets(ownedPets, busy);
        int sent = 0;
        int failCode = 0;
        if (!pets.isEmpty()) {
            outer:
            for (PetSupport.Area a : areas) {
                if (sent >= PetSupport.MAX_DISPATCH_PER_ROUND) break;
                boolean idle = a.idle() || (a.completed(now) && freedPets.contains(a.petId));
                if (!idle) continue;
                java.util.Iterator<Long> it = pets.iterator();
                while (it.hasNext()) {
                    long pid = it.next();
                    GameMessage r = engine.request("req_pet_adventure_dispatch",
                            ExecUtil.args("id", a.id, "pet_id", pid));
                    if (ExecUtil.ok(r)) {
                        sent++;
                        it.remove();
                        Map<String, Object> c = r.getContent();
                        PetSupport.markAreaDispatched(adv, a.id, pid,
                                c == null ? 0L : ExecUtil.longOf(c.get("start_time")),
                                c == null ? 0L : ExecUtil.longOf(c.get("finish_time")),
                                c != null && PetSupport.truthy(c.get("is_reward_multiple")));
                        break;
                    }
                    int code = ExecUtil.codeOf(r);
                    if (code == 11784 || code == 11801) {
                        continue;
                    }
                    failCode = code;
                    break outer;
                }
            }
        }
        int used = usedBefore + sent;
        StringBuilder sub = new StringBuilder();
        if (claimed > 0) sub.append("领奖×").append(claimed);
        if (sent > 0) {
            if (sub.length() > 0) sub.append(" ");
            sub.append("派遣×").append(sent);
        }
        if (sub.length() == 0) sub.append("无已完成/可派遣藏宝点");
        sub.append("（今日寻宝 ").append(used).append(" 次");
        if (PetSupport.dailyHintExhausted(used)) sub.append("，基础次数已用完");
        sub.append("）");
        if (failCode != 0) {
            sub.append("；派遣码").append(failCode);
            if (failCode == 51) sub.append("（体力不足）");
        }
        String text = sub.toString();
        if (claimed > 0) m.claimed("寻宝领奖×" + claimed);
        if (sent > 0) m.claimed("派遣宠物×" + sent);
        if (claimed > 0 || sent > 0) {
            m.subOk("adventure", text);
        } else {
            m.skip(text);
            m.subSkip("adventure", text);
        }
        mod(engine, "pet", "adventure", text);
    }

    // ---------- 专家游历：领行动力 + 全额挂机 ----------

    static DailyResult expert(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            GameMessage info = engine.request("req_travel_info", null);
            if (!ExecUtil.ok(info)) {
                m.skip("专家游历未解锁(码" + ExecUtil.codeOf(info) + ")");
            } else {
                // 子开关门控：energy=领取行动力（默认开）
                boolean onEnergy = FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "expert", "energy", true);
                long energy = -1;
                if (onEnergy) {
                    GameMessage recv = engine.request("req_travel_recv_energy", null);
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
                } else {
                    m.subSkip("energy", "子开关已关闭");
                    // 行动力当前值从 info 兜底读取，供 afk 决策
                    if (info.getContent() != null) {
                        for (String k : new String[]{"energy", "stamina", "power", "cnt", "action"}) {
                            if (info.getContent().containsKey(k)) {
                                energy = ExecUtil.longOf(info.getContent().get(k));
                                break;
                            }
                        }
                    }
                }
                if (FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "expert", "buy", false)) {
                    for (int i = 0; i < 4; i++) {
                        GameMessage be = engine.request("req_travel_buy_energy", null);
                        if (!ExecUtil.ok(be)) break;
                        m.claimed("购体力+1");
                    }
                }
                // 子开关门控：afk=全额挂机游历（策略只管保留多少）
                boolean onAfk = FnParams.subOn(engine.appContext(),
                        engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                        "expert", "afk", true);
                // 策略：保留 N 点行动力不挂机（0=用全部）
                long keep = 0;
                try {
                    keep = Long.parseLong(FnParams.strategy(engine.appContext(),
                            engine.lastFpid(), engine.lastServerId(), "expert", "afk", "0").trim());
                } catch (Throwable ignored) {
                }
                long use = onAfk ? Math.max(0, energy - keep) : -1;
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
                    m.skip(onAfk ? "行动力" + energy + "，按保留阈值跳过" : "子开关「全额挂机游历」已关闭");
                    m.subSkip("afk", onAfk ? "按保留阈值跳过" : "子开关已关闭");
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
            Map<String, Object> hi = engine.notifyContent("notify_homeland_info");
            if (hi == null) {
                m.skip("家园通知未同步（登录期首跑即可）");
                m.subSkip("free", "未同步");
                m.subSkip("tree", "未同步");
                m.subSkip("logging", "未同步");
                return homelandResult(m);
            }
            long nowSec = System.currentTimeMillis() / 1000L;

            // 回礼：每 8 小时一个、至多 3 个（原版 free_production_ts 同款口径）
            if (FnParams.subOn(hctx, hfpid, hsid, "homeland", "free", true)) {
                long fpTs = numIn(hi, "free_production_ts");
                if (fpTs > 0 && nowSec > fpTs) {
                    long count = Math.min(3, (nowSec - fpTs) / 28800L);
                    if (count > 0) {
                        tryStep(engine, m, "晨曦回礼×" + count, "free",
                                "req_homeland_gain_free_production", null);
                    } else {
                        m.subSkip("free", "未到8小时");
                    }
                } else {
                    m.subSkip("free", "时间戳未同步");
                }
            }

            // 生命之树：按等级的小时产量表 + 12 小时上限本地外推，可收值>0 才领
            if (FnParams.subOn(hctx, hfpid, hsid, "homeland", "tree", true)) {
                Map<String, Object> tree = deepMap(hi, "life_tree_info");
                long lifeTreeLevel = homelandBuildingLevel(hi, 7000L);
                long hourly = LIFE_TREE_HOURLY[Math.min((int) lifeTreeLevel, LIFE_TREE_HOURLY.length - 1)];
                long max = hourly * 12L;
                long cur = numIn(tree, "cur_value");
                long lastRec = numIn(tree, "last_recovery_time");
                long base = Math.max(0, Math.min(cur, max));
                long value = base;
                if (hourly > 0 && lastRec > 0 && nowSec > lastRec && base < max) {
                    long perMin = Math.max(1, hourly / 60L);
                    long elapsedMin = (nowSec - lastRec) / 60L;
                    long growCapMin = (max - base + perMin - 1) / perMin;
                    value = base + Math.min(elapsedMin, growCapMin) * perMin;
                }
                if (value > 0) {
                    tryStep(engine, m, "生命之树（约" + value + "）", "tree",
                            "req_homeland_take_life_tree_coin", null);
                } else {
                    m.subSkip("tree", "无可收（产量" + hourly + "/时）");
                }
            }

            // 伐木小屋(7001)满 720 / 木材加工厂(7082)满 2：逐建筑判阈值（原版同款）
            if (!FnParams.subOn(hctx, hfpid, hsid, "homeland", "logging", true)) {
                m.subSkip("logging", "已关闭");
            } else {
                List<Map<String, Object>> builds = homelandBuildings(hi);
                boolean took = false;
                boolean cabinTaken = false;
                for (Map<String, Object> b : builds) {
                    long bid = numIn(b, "bid");
                    if (bid != 7001L && bid != 7082L) continue;
                    Map<String, Object> point = deepMap(b, "point");
                    if (point == null || numIn(point, "x") == 0 && numIn(point, "y") == 0) continue;
                    if (bid == 7001L) {
                        if (cabinTaken) continue;   // 每轮只取一个小屋（原版同款）
                        long cur = loggingCur(b);
                        if (cur >= 720) {
                            tryStep(engine, m, "伐木小屋", "logging",
                                    "req_homeland_take_logging_cabin_tree",
                                    ExecUtil.args("point", point));
                            took = true;
                            cabinTaken = true;
                        }
                    } else {
                        long cur = loggingCur(b);
                        if (cur >= 2) {
                            tryStep(engine, m, "木材加工厂", "logging",
                                    "req_homeland_take_logging_site_coin",
                                    ExecUtil.args("point", point));
                            took = true;
                        }
                    }
                }
                if (!took && m.sb.length() == 0) {
                    m.subSkip("logging", "未满阈值（小屋720/工厂2）");
                }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "晨曦岛");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return homelandResult(m);
    }

    /** 生命之树小时产量表（按建筑等级，index=等级，封顶 420/时）。 */
    private static final long[] LIFE_TREE_HOURLY = {0, 120, 150, 180, 210, 240, 270, 300, 330, 360, 420};

    private static DailyResult homelandResult(ExecUtil.Multi m) {
        DailyResult rr = new DailyResult("homeland", "晨曦岛", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    /** 递归收集家园建筑条目（含 bid 的 map）。 */
    static List<Map<String, Object>> homelandBuildings(Map<String, Object> node) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectBuildings(node, out, 0);
        return out;
    }

    private static void collectBuildings(Map<String, Object> node, List<Map<String, Object>> out, int depth) {
        if (node == null || depth > 4 || out.size() >= 32) return;
        if (node.containsKey("bid")) {
            out.add(node);
            return;
        }
        for (Object v : node.values()) {
            if (v instanceof Map) {
                collectBuildings((Map<String, Object>) v, out, depth + 1);
            } else if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    if (o instanceof Map) collectBuildings((Map<String, Object>) o, out, depth + 1);
                }
            }
        }
    }

    private static long homelandBuildingLevel(Map<String, Object> hi, long bid) {
        for (Map<String, Object> b : homelandBuildings(hi)) {
            if (numIn(b, "bid") == bid) return numIn(b, "level");
        }
        return 0;
    }

    /** 小屋/加工厂的 cur_value（字段可能直接挂建筑条目或内嵌 info）。 */
    private static long loggingCur(Map<String, Object> b) {
        long v = numIn(b, "cur_value");
        if (v > 0) return v;
        for (Object o : b.values()) {
            if (o instanceof Map) {
                v = numIn((Map<String, Object>) o, "cur_value");
                if (v > 0) return v;
            }
        }
        return 0;
    }

    /** 递归找第一个含指定键的子 map。 */
    private static Map<String, Object> deepMap(Map<String, Object> node, String key) {
        if (node == null) return null;
        Object direct = node.get(key);
        if (direct instanceof Map) return (Map<String, Object>) direct;
        for (Object v : node.values()) {
            if (v instanceof Map) {
                Map<String, Object> r = deepMap((Map<String, Object>) v, key);
                if (r != null) return r;
            }
        }
        return null;
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
            // 子开关门控：purify=普通提炼（super/quests 各自独立门控）
            boolean onPurify = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "fire_crystal", "purify", true);
            int done = 0;
            if (onPurify) {
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
            }
            if (done > 0) {
                m.claimed("提炼×" + done);
                m.subOk("purify", "提炼×" + done);
                mod(engine, "fire_crystal", "purify", "上轮提炼 " + done + " 次");
            }
            else if (!onPurify) { m.subSkip("purify", "子开关已关闭"); }
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
            // 火晶悬赏任务：notify_firecrystal_quests 的任务批量领取（quest_ids 批量接口，已领/未达标服务端跳过）
            if (FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "fire_crystal", "quests", true)) {
                Map<String, Object> fq = engine.notifyData("notify_firecrystal_quests");
                List<Object> questIds = new ArrayList<>();
                for (Map<String, Object> q : ExecUtil.mapsOf(fq == null ? null : fq.get("quests"))) {
                    long id = ExecUtil.longOf(q.get("id"));
                    if (id > 0 && questIds.size() < 30) questIds.add(id);
                }
                if (!questIds.isEmpty()) {
                    GameMessage qa = engine.request("req_get_firecrystal_quest_award",
                            ExecUtil.args("quest_ids", questIds));
                    if (ExecUtil.ok(qa)) {
                        m.claimed("悬赏任务×" + questIds.size());
                        m.subOk("quests", "悬赏×" + questIds.size());
                    } else {
                        int code = ExecUtil.codeOf(qa);
                        m.skip("悬赏无可领(码" + code + ")");
                        m.subSkip("quests", "码" + code);
                    }
                } else {
                    m.subSkip("quests", "无任务记录");
                }
            }
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
            // 子开关门控：sweep=每日扫荡（本功能唯一动作，关=整功能跳过）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "climbtower", "sweep", true)) {
                m.skip("子开关「每日扫荡」已关闭");
                m.subSkip("sweep", "子开关已关闭");
                DailyResult off = new DailyResult("climbtower", "地心探索", m.cat(), m.note() + " " + m.sb);
                off.subResults.putAll(m.subResults);
                return off;
            }
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
            android.content.Context actx = engine.appContext();
            String afpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long asid = engine.lastServerId();
            org.json.JSONObject ap = FnParams.params(actx, afpid, asid, "arena");
            org.json.JSONObject strat = ap.optJSONObject("strategy");
            // 子开关门控：fight=自动挑战（strategy.fight 只管场数），daily=每日奖励
            boolean onFight = FnParams.subOn(actx, afpid, asid, "arena", "fight", true);
            boolean onDaily = FnParams.subOn(actx, afpid, asid, "arena", "daily", true);
            // 次数：策略chips优先（3/5/8/10场），无策略再兼容旧 params.max_fights
            int maxFights = strat != null && strat.has("fight") ? strat.optInt("fight", 5)
                    : ap.optInt("max_fights", 5);
            maxFights = Math.max(1, Math.min(10, maxFights));

            // 1. 战斗版本学习（原版同款：从 battle_data 学 version；写死值可被热更包
            //    nums."arena.version_fallback" 覆盖——版本更新后远端改数即生效）
            long version = HotConfig.num("arena.version_fallback", 26042101L);
            if (onFight) {
                try {
                    GameMessage bd = engine.request("req_arena_get_battle_data", null);
                    if (ExecUtil.ok(bd)) {
                        long v = deepFindNum(bd.getContent(), 0, "version", "battle_version");
                        if (v > 0) version = v;
                    }
                } catch (Throwable ignored) {
                }
            }

            // 2. 竞技场基础状态（防御式）：完成次数/英雄阵容
            Map<String, Object> base = arenaBase(engine);
            long freeUsed = numIn(base, "free_times_use");
            long buyTimes = numIn(base, "buy_times");
            long leftTimes = numIn(base, "left_times");
            long completed = freeUsed + Math.max(0, buyTimes - leftTimes);

            // 3. 对手列表（fight 子开关关闭则整段跳过）
            GameMessage opp = engine.request("req_arena_get_opponent", null);
            int fights = 0, wins = 0;
            if (!onFight) {
                m.skip("子开关「自动挑战」已关闭");
                m.subSkip("fight", "子开关已关闭");
            } else if (ExecUtil.ok(opp)) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (String k : new String[]{"opponents", "data", "list"}) {
                    list = ExecUtil.mapsOf(opp.getContent().get(k));
                    if (!list.isEmpty()) break;
                }
                // 战力降序无意义，先过滤再按防御战力升序（挑最软的）
                long myPower = numIn(base, "power");
                if (myPower <= 0) myPower = numIn(base, "defence_power");
                java.util.Set<Long> fought = new java.util.HashSet<>();
                List<Long> heroes = arenaHeroes(engine, base);
                list.sort((x, y) -> Long.compare(powerOf(x), powerOf(y)));
                for (Map<String, Object> o : list) {
                    if (fights >= maxFights || heroes.isEmpty()) break;
                    long oid = ExecUtil.longOf(o.get("uid"));
                    if (oid <= 0) oid = ExecUtil.longOf(o.get("id"));
                    if (oid <= 0 || fought.contains(oid)) continue;
                    long pw = powerOf(o);
                    boolean robot = pw == 0 || ExecUtil.longOf(o.get("fid")) == 0;
                    // 弱于我才打（机器人豁免）；战力未知按可打处理
                    if (!robot && myPower > 0 && pw >= myPower) continue;
                    List<Object> heroArray = new ArrayList<>(heroes);
                    GameMessage f = engine.request("req_arena_fight", ExecUtil.args(
                            "opponent_type", robot ? 1 : 0,
                            "opponent_id", oid,
                            "hero_array", heroArray,
                            "version", version));
                    fights++;
                    if (ExecUtil.ok(f)) {
                        wins++;
                        fought.add(oid);
                    } else {
                        int code = ExecUtil.codeOf(f);
                        if (code == 2901) { m.skip("挑战次数已用完"); break; }
                        if (code == 2903) { ExecUtil.sleep(1000); fights--; continue; }  // 太频繁，等一下重试
                        if (code == 2904 || code == 2920 || code == 2921) { m.skip("对手已过期，下轮刷新"); break; }
                        fought.add(oid);   // 其余错误码（含 2914 结果未知）本轮不再碰这个对手
                        m.skip("对手码" + code);
                    }
                    ExecUtil.sleep(800);
                }
                if (fights > 0) {
                    m.claimed("挑战" + fights + "场胜" + wins);
                    m.subOk("fight", "挑战" + fights + "胜" + wins);
                    mod(engine, "arena", "fight", "上轮挑战 " + fights + " 场 · 胜 " + wins
                            + " · 战斗版本 " + version);
                } else {
                    m.skip("无可打对手（含战力门槛过滤）");
                    m.subSkip("fight", "无合适对手");
                }
            } else {
                m.skip("竞技场未解锁(码" + ExecUtil.codeOf(opp) + ")");
            }

            // 4. 每日奖励：完成次数达标才领（原版同款门控），不达标不发请求；daily 子开关总闸
            if (!onDaily) {
                m.skip("子开关「每日奖励」已关闭");
                m.subSkip("daily", "子开关已关闭");
            } else if (completed >= 5) {
                GameMessage daily = engine.request("req_arena_get_daily_reward", null);
                int dc = ExecUtil.codeOf(daily);
                if (ExecUtil.ok(daily)) { m.claimed("每日奖励"); m.subOk("daily", "每日奖励已领"); }
                else if (dc == 2926) { m.skip("每日奖励已领"); m.subSkip("daily", "已领"); }
                else { m.skip("每日奖励码" + dc); m.subFail("daily", "码" + dc); }
            } else {
                m.skip("完成" + completed + "/5，未达每日奖励门槛");
                m.subSkip("daily", "完成" + completed + "/5");
            }
        } catch (Exception e) {
            if (sessionLost(e)) return DailyTasks.classifyException(e, "竞技场");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("arena", "竞技场", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    /** 竞技场基础状态（notify_arena_init.base 或消息本体），防御式。 */
    private static Map<String, Object> arenaBase(WjdrEngine engine) {
        Map<String, Object> init = engine.notifyData("notify_arena_init");
        if (init == null) return new java.util.HashMap<>();
        Map<String, Object> base = ExecUtil.mapOf(init.get("base"));
        return base == null ? init : base;
    }

    /** 防御式数字提取：依次找 depth≤2 的同名键。 */
    static long numIn(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        if (v instanceof Number) return ExecUtil.longOf(v);
        for (Object o : map.values()) {
            if (o instanceof Map) {
                Object inner = ((Map<?, ?>) o).get(key);
                if (inner instanceof Number) return ExecUtil.longOf(inner);
            }
        }
        return 0;
    }

    /** 英雄阵容三级回退：竞技场保存的进攻阵容 → 基础防守阵容 → 客户端 1 队/英雄榜。 */
    private static List<Long> arenaHeroes(WjdrEngine engine, Map<String, Object> base) {
        for (String k : new String[]{"challenge_hero", "hero_array"}) {
            List<Long> ids = new ArrayList<>();
            for (Object o : ExecUtil.listOf(base.get(k)) == null
                    ? new ArrayList<Object>() : ExecUtil.listOf(base.get(k))) {
                long id = ExecUtil.longOf(o);
                if (id > 0) ids.add(id);
            }
            if (!ids.isEmpty()) return ids;
        }
        return squadHeroes(engine);
    }

    private static long deepFindNum(Map<String, Object> map, int depth, String... keys) {
        if (map == null || depth > 4) return 0;
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof Number) return ExecUtil.longOf(v);
        }
        for (Object o : map.values()) {
            if (o instanceof Map) {
                long r = deepFindNum((Map<String, Object>) o, depth + 1, keys);
                if (r > 0) return r;
            }
        }
        return 0;
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
        } else if (code == 15002 || code == 15005 || code == 15029 || code == 15048 || code == 2 || code == 7401 || code == 7404) {
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