package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 城镇类任务（App 端 daily/TownTasks 逐行移植，改动须两侧同步）。
 */
public final class ServerTownTasks {

    private ServerTownTasks() {
    }

    // ---------- 城镇管理：免费招募 + 收兵 + 专家技能 ----------

    public static TaskOutcome townAffairs(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        captureHeroSearch(tc);
        try {
            int free = 0;
            for (int i = 0; i < 5; i++) {
                GameMessage rsp = tc.engine.request("req_hero_search_times",
                        Exec.args("search_type", 1, "cost_type", 1, "times", 1));
                if (Exec.ok(rsp)) {
                    free++;
                } else {
                    break;
                }
            }
            if (free > 0) { m.claimed("高级招募×" + free); m.subOk("recruit_free", "招募×" + free); }
            else { m.skip("免费招募无次数"); m.subSkip("recruit_free", "今日无免费次数"); }

            GameMessage epic = tc.engine.request("req_hero_search_times",
                    Exec.args("search_type", 2, "cost_type", 1, "times", 1));
            if (Exec.ok(epic)) { m.claimed("史诗招募"); m.subOk("recruit_epic", "史诗招募已招"); }
            else { m.skip("史诗招募未到时间"); m.subSkip("recruit_epic", "未到48小时"); }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "城镇管理");
            m.fail("招募:" + ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        if (tc.subOn("town_affairs", "expert_token", true)) {
            try {
                GameMessage rsp = tc.engine.request("req_use_expert_skill",
                        Exec.args("expert_id", 7004, "skill_id", 70041, "effect_type", 14));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) m.claimed("忠诚令牌");
                else if (code == 40237 || code == 40238 || code == 40236) m.skip("忠诚令牌已领");
                else m.skip("令牌码" + code);
            } catch (Exception e) {
                if (sessionLost(e)) return ServerTasks.classifyException(e, "城镇管理");
                m.skip("令牌未执行");
            }
        }
        if (tc.subOn("town_affairs", "expert_soldier", true)) {
            String bid = tc.strategy("town_affairs", "expert_soldier", "321");
            try {
                GameMessage rsp = tc.engine.request("req_use_expert_skill",
                        Exec.args("expert_id", 7004, "skill_id", 70041,
                                "effect_type", 18, "param1", Long.parseLong(bid)));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    m.claimed("每日士兵(" + ("321".equals(bid) ? "盾" : "322".equals(bid) ? "矛" : "弓") + "兵营)");
                    tc.saveModText("town_affairs", "expert_soldier",
                            "每日士兵：已领 " + ("321".equals(bid) ? "盾" : "322".equals(bid) ? "矛" : "弓") + "兵营");
                } else if (code == 40237 || code == 40238 || code == 40236) m.skip("每日士兵已领");
                else m.skip("士兵码" + code);
            } catch (Exception e) {
                if (sessionLost(e)) return ServerTasks.classifyException(e, "城镇管理");
                m.skip("士兵未执行");
            }
        }
        return Exec.result(m, "town_affairs", "城镇管理");
    }

    /** 招募状态采集：notify_hero_search_data → 今日次数进度 + 下次冷却倒计时。 */
    static void captureHeroSearch(TaskContext tc) {
        try {
            Map<String, Object> hs = tc.engine.notifyData("notify_hero_search_data");
            if (hs == null) return;
            long now = System.currentTimeMillis() / 1000L;
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
            java.text.SimpleDateFormat hh = com.carlos.vaultserver.Beijing.fmt("HH:mm");
            java.text.SimpleDateFormat dd = com.carlos.vaultserver.Beijing.fmt("MM-dd HH:mm");
            if (freeLeft >= 0) {
                int used = Math.max(0, 5 - (int) freeLeft);
                String s = "今日 " + used + "/5";
                if (freeLeft > 0) s += " · 当前可招募";
                else if (lastFree > 0) s += " · 下次 " + hh.format(new java.util.Date((lastFree + 300) * 1000));
                tc.saveModText("town_affairs", "recruit_free", s);
            }
            if (lastEpic > 0) {
                long nextEpic = lastEpic + 172800;
                if (nextEpic <= now) {
                    tc.saveModText("town_affairs", "recruit_epic",
                            "上次 " + dd.format(new java.util.Date(lastEpic * 1000)) + " · 当前可招募");
                } else {
                    long left = nextEpic - now;
                    tc.saveModText("town_affairs", "recruit_epic",
                            "上次 " + dd.format(new java.util.Date(lastEpic * 1000))
                                    + " · 下次还 " + (left / 3600) + "小时" + ((left % 3600) / 60) + "分");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static double num(Object v, double def) {
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    // ---------- 军医所：治疗伤兵 + 联盟求助 ----------

    public static TaskOutcome medic(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            Map<String, Object> hi = tc.engine.notifyData("notify_hospital_init");
            Object w = hi == null ? null : hi.get("wound_soldiers");
            if (w instanceof Map) w = ((Map<?, ?>) w).get("soldiers");
            Map<Long, Long> wound = Exec.kvPairs(w);
            long total = 0;
            for (long v : wound.values()) total += v;
            if (total <= 0) {
                m.skip("无伤兵");
                m.subSkip("heal", "无伤兵");
                tc.saveModText("medic", "heal", "当前无伤兵 · " + com.carlos.vaultserver.Beijing.fmt(
                        "HH:mm").format(new java.util.Date()) + "检查");
            } else {
                List<Map<String, Object>> batch = new ArrayList<>();
                for (Map.Entry<Long, Long> e : wound.entrySet()) {
                    if (e.getValue() > 0) batch.add(Exec.kv(e.getKey(), e.getValue()));
                }
                GameMessage rsp = tc.engine.request("req_hospital_heal_soldier",
                        Exec.args("soldiers", batch, "immediate", false));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    m.claimed("治疗" + total + "伤兵");
                    m.subOk("heal", "治疗" + total + "伤兵");
                    tc.saveModText("medic", "heal", "治疗中 " + total + " 伤兵");
                } else if (code == 2301) { m.skip("上批仍在治疗中"); m.subSkip("heal", "上批治疗中"); }
                else if (code == 51) { m.skip("资源不足，未治疗"); m.subSkip("heal", "资源不足"); }
                else { m.fail("治疗码" + code); m.subFail("heal", "码" + code); }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "军医所");
            m.fail("治疗:" + ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        try {
            GameMessage help = tc.engine.request("req_alliance_help_all", null);
            if (Exec.ok(help)) { m.claimed("联盟一键求助/互助"); m.subOk("help_all", "已互助全盟"); }
            else { m.skip("联盟求助不可用(码" + Exec.codeOf(help) + ")"); m.subSkip("help_all", "码" + Exec.codeOf(help)); }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "军医所");
            m.skip("联盟求助未执行");
        }
        return Exec.result(m, "medic", "军医所");
    }

    // ---------- 兵营训练：三营独立收兵/训练 + 实时状态 ----------

    public static TaskOutcome barracks(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            captureBarrackState(tc);
            String[] collectIds = {"", "collect_shield", "collect_spear", "collect_bow"};
            long[] bids = {0, 321, 322, 323};
            for (int line = 1; line <= 3; line++) {
                if (!tc.subOn("barracks", collectIds[line], true)) continue;
                GameMessage rsp = tc.engine.request("req_barrack_recruit_soldier",
                        Exec.args("arr_bid", java.util.Collections.singletonList(bids[line])));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    m.claimed("收" + "盾矛弓".charAt(line - 1) + "兵");
                    m.subOk(collectIds[line], "已收" + "盾矛弓".charAt(line - 1) + "兵");
                } else {
                    m.skip("收" + "盾矛弓".charAt(line - 1) + "码" + code);
                    m.subSkip(collectIds[line], "码" + code
                            + (code == 2104 ? "（无可收）" : ""));
                }
            }
            JSONObject bp = tc.params("barracks");
            long batch = Math.max(10, Math.min(1000, bp.optInt("batch", 200)));
            String[] tierSrcRef = {"barracks"};
            int[] maxTier = barrackUnlockTiers(tc.engine, tierSrcRef);
            String tierSource = tierSrcRef[0];
            if (maxTier == null) {
                tierSource = "soldiers";
                maxTier = new int[4];
                Map<Long, Long> sol = Exec.soldiers(tc.engine);
                for (long id : sol.keySet()) {
                    int line = (int) (id / 10000);
                    int idx = (int) ((id % 10000) / 100);
                    if (line >= 1 && line <= 3 && idx > maxTier[line]) maxTier[line] = idx;
                }
            }
            try {
                JSONObject ms = tc.modState("barracks");
                ms.put("max_shield", maxTier[1]);
                ms.put("max_spear", maxTier[2]);
                ms.put("max_bow", maxTier[3]);
                ms.put("batch", batch);
                ms.put("tier_src", tierSource);
                tc.store.putString("mod:" + tc.fpid + ":" + tc.sid + ":barracks", ms.toString());
            } catch (Exception ignored) {
            }
            String[] trainIds = {"", "train_shield", "train_spear", "train_bow"};
            StringBuilder trNote = new StringBuilder();
            int trOk = 0;
            for (int line = 1; line <= 3; line++) {
                if (!tc.subOn("barracks", trainIds[line], true)) continue;
                String sel = tc.strategy("barracks", trainIds[line], "auto");
                int parsed = 0;
                if (!"auto".equals(sel)) {
                    try {
                        parsed = Integer.parseInt(sel.trim());
                    } catch (NumberFormatException nfe) {
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
                GameMessage rsp = tc.engine.request("req_barrack_train_soldier",
                        Exec.args("soldier_id", id, "soldier_num", batch, "immediate", false));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    trOk++;
                    trNote.append("盾矛弓".charAt(line - 1)).append("T").append(useTier).append("×").append(batch).append(" ");
                    m.subOk(trainIds[line], "续训T" + useTier + "×" + batch);
                } else if (code == 2103) {
                    trNote.append("盾矛弓".charAt(line - 1)).append("容量满 ");
                    m.subSkip(trainIds[line], "兵营容量满");
                } else if (code == 2104) {
                    m.subSkip(trainIds[line], "无可收兵");
                } else if (code == 2101) {
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
            if (sessionLost(e)) return ServerTasks.classifyException(e, "兵营训练");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "barracks", "兵营训练");
    }

    // ================= 兵营可训练档位（与 App 端一致） =================

    private static final int[] BARRACK_TIER_LEVELS = {1, 4, 7, 11, 13, 16, 19, 22, 26, 30};
    private static final long[] TIER11_TECH = {7007, 8007, 9007};
    private static final long[] TIER12_TECH = {10006, 11006, 12006};

    static int[] barrackUnlockTiers(WjdrEngine engine) {
        return barrackUnlockTiers(engine, null);
    }

    /**
     * 兵种 T 级解锁判定（实证修正 2026-10-09）：
     * T1~T10 由<b>大熔炉（bid 301）</b>等级按阈值表解锁——兵营建筑（321~323）等级不决定 T 级
     * （原实现误用兵营等级，熔炉 13 级账号被误判 T4，实际 T5）。熔炉数据缺失时回退兵营等级（旧行为）。
     * T11/T12 仍由 spar/refine 科技门控。
     */
    static int[] barrackUnlockTiers(WjdrEngine engine, String[] sourceOut) {
        try {
            Map<String, Object> shelter = engine.notifyData("notify_shelter_info");
            if (shelter == null) return null;
            Object bi = shelter.get("building_info");
            List<Object> entries = new ArrayList<>();
            if (bi instanceof Map) entries.addAll(((Map<?, ?>) bi).values());
            else if (bi instanceof List) entries.addAll((List<?>) bi);
            long[] levels = new long[4];
            long furnace = 0;
            for (Object raw : entries) {
                Map<String, Object> b = Exec.mapOf(raw);
                if (b == null) continue;
                long bid = Exec.longOf(b.get("bid"));
                Map<String, Object> base = Exec.mapOf(b.get("base"));
                long lv = Exec.longOf(base != null ? base.get("lv") : b.get("lv"));
                if (bid == 301 && lv > furnace) furnace = lv;
                if (bid >= 321 && bid <= 323 && lv > levels[(int) (bid - 320)]) {
                    levels[(int) (bid - 320)] = lv;
                }
            }
            if (furnace <= 0 && levels[1] <= 0 && levels[2] <= 0 && levels[3] <= 0) return null;
            String src = "barracks";
            if (furnace > 0) {
                src = "furnace";
                levels[1] = furnace;
                levels[2] = furnace;
                levels[3] = furnace;
            }
            if (sourceOut != null && sourceOut.length > 0) sourceOut[0] = src;
            Map<Long, Long> spar = researchLevels(engine, "notify_spar_science_all_research");
            Map<Long, Long> refine = researchLevels(engine, "notify_refine_science_all_research");
            // 诊断日志：解锁规则实证（熔炉/兵营等级 + 全量科技等级 + 兵营init + 已有士兵），用于校正 T 级判定
            StringBuilder tech = new StringBuilder();
            try {
                Map<String, Object> ar = engine.notifyData("notify_all_research");
                if (ar == null) {
                    tech.append("未收到");
                } else {
                    Object researched = ar.get("researched");
                    Map<Long, Long> kv = Exec.kvPairs(researched instanceof Map ? ((Map<?, ?>) researched) : null);
                    if (kv.isEmpty()) {
                        for (Map<String, Object> mm : Exec.mapsOf(researched)) {
                            long tid = Exec.longOf(mm.get("tid"));
                            if (tid > 0) kv.put(tid, Exec.longOf(mm.get("lev")));
                        }
                    }
                    // 只关心 7/8/9/10/11/12 千段（兵种解锁科技族候选）
                    kv.entrySet().stream()
                            .filter(e -> e.getKey() >= 7000 && e.getKey() < 13000)
                            .sorted(java.util.Map.Entry.comparingByKey())
                            .forEach(e -> tech.append(e.getKey()).append(':').append(e.getValue()).append(' '));
                    if (tech.length() == 0) tech.append("7-12千段无科技; 全量条数=").append(kv.size());
                }
            } catch (Throwable t) {
                tech.append("dump失败:").append(t);
            }
            FileLog.info("兵营T级 熔炉lv{} 兵营[{},{},{}] 口径={} spar科技{}条 refine科技{}条",
                    furnace, levels[1], levels[2], levels[3], src,
                    String.valueOf(spar == null ? 0 : spar.size()),
                    String.valueOf(refine == null ? 0 : refine.size()));
            return computeUnlockTiers(levels, spar, refine);
        } catch (Throwable t) {
            return null;
        }
    }

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

    private static Map<Long, Long> researchLevels(WjdrEngine engine, String proto) {
        try {
            Map<String, Object> data = engine.notifyData(proto);
            if (data == null) return null;
            Object researched = data.get("researched");
            List<Object> entries = new ArrayList<>();
            if (researched instanceof Map) entries.addAll(((Map<?, ?>) researched).values());
            else if (researched instanceof List) entries.addAll((List<?>) researched);
            Map<Long, Long> out = new HashMap<>();
            for (Object raw : entries) {
                Map<String, Object> r = Exec.mapOf(raw);
                if (r == null) continue;
                long tid = Exec.longOf(r.get("tid"));
                long lev = Exec.longOf(r.get("lev"));
                if (tid > 0 && lev > 0) out.put(tid, lev);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 三兵营实时状态：notify_barrack_init 逐营解析。 */
    static void captureBarrackState(TaskContext tc) {
        try {
            Map<String, Object> bi = tc.engine.notifyData("notify_barrack_init");
            if (bi == null) return;
            long now = System.currentTimeMillis() / 1000L;
            java.text.SimpleDateFormat hm = com.carlos.vaultserver.Beijing.fmt("HH:mm");
            String[] collectIds = {"", "collect_shield", "collect_spear", "collect_bow"};
            for (int line = 1; line <= 3; line++) {
                long bidWant = 320 + line;
                long best = scanBarrackEnd(bi, bidWant, now, 0);
                if (best > now) {
                    tc.saveModText("barracks", collectIds[line],
                            "训练中 · " + hm.format(new java.util.Date(best * 1000)) + "可收");
                } else {
                    tc.saveModText("barracks", collectIds[line],
                            best > 0 ? "有完工批次可收" : "空闲（无训练队列数据）");
                }
            }
        } catch (Throwable ignored) {
        }
    }

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

    // ---------- 宠物 ----------

    public static TaskOutcome pet(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2000);
            JSONObject pp2 = tc.params("pet");
            List<Object> skills = new ArrayList<>();
            for (long s : new long[]{550051L, 550071L, 550081L, 550091L, 550101L,
                    550111L, 550121L, 550131L, 550141L}) {
                skills.add(s);
            }
            if (pp2.optBoolean("skills", true)) {
                GameMessage sk = tc.engine.request("req_pet_use_skill",
                        Exec.args("skill_id_lst", skills));
                if (Exec.ok(sk)) { m.claimed("宠物技能"); m.subOk("skills", "技能已开"); }
                else { m.skip("宠物技能冷却/不可用(码" + Exec.codeOf(sk) + ")"); m.subSkip("skills", "码" + Exec.codeOf(sk)); }
            }
            if (pp2.optBoolean("alliance_chest", true)) {
                GameMessage ab = tc.engine.request("req_pet_adventure_batch_take_alliance_reward", null);
                if (Exec.ok(ab)) { m.claimed("联盟寻宝宝箱"); m.subOk("alliance_chest", "宝箱已领"); }
                else { m.skip("联盟宝箱无可领(码" + Exec.codeOf(ab) + ")"); m.subSkip("alliance_chest", "码" + Exec.codeOf(ab)); }
            }
            Map<String, Object> adv = tc.engine.notifyContent("notify_pet_adventure_info");
            Map<String, Object> petInfo = tc.engine.notifyData("notify_pet_info");
            List<Long> petIds = new ArrayList<>();
            if (petInfo != null) {
                for (Map<String, Object> p : Exec.mapsOf(petInfo.get("info"))) {
                    long id = Exec.longOf(p.get("id"));
                    if (id > 0) petIds.add(id);
                }
            }
            if (adv != null && !petIds.isEmpty() && pp2.optBoolean("adventure", true)) {
                int claimed = 0, sent = 0;
                for (Map<String, Object> area : Exec.mapsOf(adv.get("areas"))) {
                    long aid = Exec.longOf(area.get("id"));
                    if (aid <= 0) continue;
                    GameMessage r = tc.engine.request("req_pet_adventure_get_reward",
                            Exec.args("id", aid));
                    if (Exec.ok(r)) claimed++;
                }
                for (Map<String, Object> area : Exec.mapsOf(adv.get("areas"))) {
                    long aid = Exec.longOf(area.get("id"));
                    Object st = area.get("status");
                    if (aid <= 0 || (st != null && Exec.longOf(st) != 0)) continue;
                    for (long pid : petIds) {
                        GameMessage r = tc.engine.request("req_pet_adventure_dispatch",
                                Exec.args("id", aid, "pet_id", pid));
                        if (Exec.ok(r)) {
                            sent++;
                            break;
                        }
                    }
                }
                if (claimed > 0) { m.claimed("寻宝领奖×" + claimed); m.subOk("adventure", "领奖×" + claimed + (sent > 0 ? " 派遣×" + sent : "")); }
                else { m.skip("寻宝无已完成区域"); m.subSkip("adventure", sent > 0 ? "无已完成区域，派遣×" + sent : "无已完成区域"); }
                if (sent > 0) {
                    m.claimed("派遣宠物×" + sent);
                    tc.saveModText("pet", "adventure", "上轮派遣 " + sent + " 只宠物寻宝");
                }
            } else {
                m.skip("寻宝数据未同步");
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "宠物");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "pet", "宠物");
    }

    // ---------- 专家游历 ----------

    public static TaskOutcome expert(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            GameMessage info = tc.engine.request("req_travel_info", null);
            if (!Exec.ok(info)) {
                m.skip("专家游历未解锁(码" + Exec.codeOf(info) + ")");
            } else {
                GameMessage recv = tc.engine.request("req_travel_recv_energy", null);
                long energy = -1;
                Map<String, Object> c = recv != null ? recv.getContent() : null;
                for (String k : new String[]{"energy", "stamina", "power", "cnt", "action"}) {
                    if (c != null && c.containsKey(k)) {
                        energy = Exec.longOf(c.get(k));
                        break;
                    }
                }
                if (energy < 0 && info.getContent() != null) {
                    for (String k : new String[]{"energy", "stamina", "power", "cnt", "action"}) {
                        if (info.getContent().containsKey(k)) {
                            energy = Exec.longOf(info.getContent().get(k));
                            break;
                        }
                    }
                }
                if (Exec.ok(recv)) { m.claimed("行动力已领"); m.subOk("energy", "行动力已领"); }
                else m.subSkip("energy", "行动力不可领");
                JSONObject ep = tc.params("expert");
                if (ep.optBoolean("buy_energy", false)) {
                    for (int i = 0; i < 4; i++) {
                        GameMessage be = tc.engine.request("req_travel_buy_energy", null);
                        if (!Exec.ok(be)) break;
                        m.claimed("购体力+1");
                    }
                }
                long keep = 0;
                try {
                    keep = Long.parseLong(tc.strategy("expert", "afk", "0").trim());
                } catch (Throwable ignored) {
                }
                long use = Math.max(0, energy - keep);
                if (use > 0) {
                    GameMessage afk = tc.engine.request("req_normal_travel_afk",
                            Exec.args("cnt", use));
                    if (Exec.ok(afk)) {
                        m.claimed("挂机游历×" + use + (keep > 0 ? "（保留" + keep + "）" : ""));
                        m.subOk("afk", "挂机×" + use);
                        tc.engine.request("req_travel_atk_info_noted", null);
                    } else {
                        m.skip("挂机未执行(码" + Exec.codeOf(afk) + ")");
                        m.subSkip("afk", "码" + Exec.codeOf(afk));
                    }
                } else {
                    m.skip("行动力" + energy + "，按保留阈值跳过");
                    m.subSkip("afk", "按保留阈值跳过");
                }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "专家游历");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "expert", "专家游历");
    }

    // ---------- 晨曦岛 ----------

    public static TaskOutcome homeland(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            tryStep(tc, m, "晨曦回礼", "free", "req_homeland_gain_free_production", null);
            tryStep(tc, m, "生命之树", "tree", "req_homeland_take_life_tree_coin", null);
            Map<String, Object> hi = tc.engine.notifyContent("notify_homeland_info");
            List<Map<String, Object>> pts = new ArrayList<>();
            if (hi != null) {
                collectPoints(hi, pts, 0);
            }
            if (pts.isEmpty()) {
                m.skip("伐木点未同步");
            } else {
                tryStep(tc, m, "伐木小屋", "logging", "req_homeland_take_logging_cabin_tree",
                        Exec.args("point", pts.get(0)));
                if (pts.size() > 1) {
                    tryStep(tc, m, "木材加工厂", null, "req_homeland_take_logging_site_coin",
                            Exec.args("point", pts.get(1)));
                }
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "晨曦岛");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "homeland", "晨曦岛");
    }

    private static void collectPoints(Map<String, Object> m, List<Map<String, Object>> out, int depth) {
        if (depth > 3 || out.size() >= 2) return;
        for (Object v : m.values()) {
            if (v instanceof Map) {
                Map<?, ?> mm = (Map<?, ?>) v;
                if (mm.containsKey("x") && mm.containsKey("y")) {
                    out.add(Exec.point(Exec.longOf(mm.get("x")), Exec.longOf(mm.get("y"))));
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

    // ---------- 火晶提炼 ----------

    public static TaskOutcome fireCrystal(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            int done = 0;
            for (int i = 0; i < 40; i++) {
                GameMessage rsp = tc.engine.request("req_spar_purify", null);
                if (Exec.ok(rsp)) {
                    done++;
                } else {
                    int code = Exec.codeOf(rsp);
                    if (code == 10051 || code == 10052 || code == 51 || code == 2) {
                        break;
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
                tc.saveModText("fire_crystal", "purify", "上轮提炼 " + done + " 次");
            } else if (m.sb.length() == 0) { m.skip("今日提炼已达上限或未解锁"); m.subSkip("purify", "达上限或未解锁"); }
            JSONObject fp = tc.params("fire_crystal");
            int superN = Math.max(0, Math.min(50, fp.optInt("super_purify", 0)));
            int superDone = 0;
            for (int i = 0; i < superN; i++) {
                GameMessage sp = tc.engine.request("req_spar_super_purify",
                        Exec.args("count", 1L));
                if (Exec.ok(sp)) superDone++;
                else break;
            }
            if (superDone > 0) m.claimed("超炼×" + superDone);
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "火晶提炼");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "fire_crystal", "火晶提炼");
    }

    // ---------- 地心探索爬塔 ----------

    public static TaskOutcome climbtower(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            GameMessage info = tc.engine.request("req_climbtower_info", null);
            if (!Exec.ok(info)) {
                m.skip("地心探索未解锁(码" + Exec.codeOf(info) + ")");
            } else {
                int wd = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
                long tower = todayTower(wd);
                tc.saveModText("climbtower", "sweep",
                        "今日开放：" + towerName(tower) + "（周" + "日一二三四五六".charAt(wd - 1) + "）");
                GameMessage sw = tc.engine.request("req_climbtower_sweep",
                        Exec.args("tower_id", tower));
                int code = Exec.codeOf(sw);
                if (Exec.ok(sw)) m.claimed("扫荡塔" + tower);
                else if (code == 7401 || code == 7404 || code == 2) m.skip("今日未开放或已扫荡");
                else m.skip("扫荡码" + code);
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "地心探索");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "climbtower", "地心探索");
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

    // ---------- 竞技场 ----------

    public static TaskOutcome arena(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            GameMessage opp = tc.engine.request("req_arena_get_opponent", null);
            if (Exec.ok(opp)) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (String k : new String[]{"opponents", "data", "list"}) {
                    list = Exec.mapsOf(opp.getContent().get(k));
                    if (!list.isEmpty()) break;
                }
                list.sort((a, b) -> Long.compare(powerOf(a), powerOf(b)));
                List<Long> heroes = squadHeroes(tc.engine);
                JSONObject ap = tc.params("arena");
                int maxFights = 5;
                JSONObject strat = ap.optJSONObject("strategy");
                if (strat != null && strat.has("fight")) {
                    maxFights = strat.optInt("fight", 5);
                } else if (ap.has("max_fights")) {
                    maxFights = ap.optInt("max_fights", 5);
                }
                maxFights = Math.max(1, Math.min(10, maxFights));
                int wins = 0, fights = 0;
                for (Map<String, Object> o : list) {
                    if (fights >= maxFights || heroes.isEmpty()) break;
                    long oid = Exec.longOf(o.get("uid"));
                    if (oid <= 0) oid = Exec.longOf(o.get("id"));
                    if (oid <= 0) continue;
                    List<Object> heroArray = new ArrayList<>(heroes);
                    GameMessage f = tc.engine.request("req_arena_fight", Exec.args(
                            "opponent_type", Exec.longOf(o.get("type")) == 0 ? 1 : Exec.longOf(o.get("type")),
                            "opponent_id", oid,
                            "hero_array", heroArray,
                            "version", 26042101L));
                    fights++;
                    if (Exec.ok(f)) wins++;
                    Exec.sleep(800);
                }
                if (fights > 0) {
                    m.claimed("挑战" + fights + "场胜" + wins);
                    m.subOk("fight", "挑战" + fights + "胜" + wins);
                    tc.saveModText("arena", "fight", "上轮挑战 " + fights + " 场 · 胜 " + wins);
                } else { m.skip("无对手数据"); m.subSkip("fight", "无对手数据"); }
            } else {
                m.skip("竞技场未解锁(码" + Exec.codeOf(opp) + ")");
            }
            GameMessage daily = tc.engine.request("req_arena_get_daily_reward", null);
            int dc = Exec.codeOf(daily);
            if (Exec.ok(daily)) { m.claimed("每日奖励"); m.subOk("daily", "每日奖励已领"); }
            else if (dc == 2926 || dc == 2925) { m.skip(dc == 2926 ? "每日奖励已领" : "次数未达标"); m.subSkip("daily", dc == 2926 ? "已领" : "次数未达标"); }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "竞技场");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "arena", "竞技场");
    }

    private static long powerOf(Map<String, Object> o) {
        for (String k : new String[]{"power", "fight", "fight_power", "score", "strength"}) {
            Object v = o.get(k);
            if (v instanceof Number) return Exec.longOf(v);
        }
        return Long.MAX_VALUE;
    }

    static List<Long> squadHeroes(WjdrEngine engine) {
        Map<Long, List<Long>> squads = Exec.clientSquads(engine);
        if (!squads.isEmpty()) return squads.values().iterator().next();
        List<Long> all = Exec.allHeroes(engine);
        return all.subList(0, Math.min(5, all.size()));
    }

    // ---------- 公共 ----------

    static void tryStep(TaskContext tc, Exec.Multi m, String name, String proto,
                        Map<String, Object> args) throws Exception {
        tryStep(tc, m, name, null, proto, args);
    }

    /** 带子项id的 tryStep（B表子项级记录）。 */
    static void tryStep(TaskContext tc, Exec.Multi m, String name, String subId,
                        String proto, Map<String, Object> args) throws Exception {
        GameMessage rsp = tc.engine.request(proto, args);
        int code = Exec.codeOf(rsp);
        if (Exec.ok(rsp)) {
            m.claimed(name);
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
        return ServerTasks.classifyException(e, "x").category == TaskOutcome.Category.SESSION_CLOSED;
    }
}
