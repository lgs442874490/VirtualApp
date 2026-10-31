package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.automation.services.MedicService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        // 伤兵统计/治疗/援助统一收敛到 MedicService：合并 notify_hospital_init +
        // notify_hospital_wound_soldiers（及跨服军医所），按兵种取 max 防漏计/重复计；
        // 参数契约（heal_count 缺省 400 / immediate / request_help）唯一事实源见
        // MedicService.healFromParams（Modules.medic 入口同源）。
        return MedicService.healFromParams(tc);
    }

    // ---------- 兵营训练：三营独立收兵/训练 + 实时状态 ----------

    public static TaskOutcome barracks(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            captureBarrackState(tc);
            String[] collectIds = {"", "collect_shield", "collect_spear", "collect_bow"};
            long[] bids = {0, 321, 322, 323};
            int subAttempt = 0;
            for (int line = 1; line <= 3; line++) {
                if (!tc.subOn("barracks", collectIds[line], true)) continue;
                subAttempt++;
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
            // 真实三营建筑等级（[0]=熔炉，[1..3]=盾/矛/弓）：用于 cap_lv_*（升级后清 cap 重探）与状态排查
            long[] barrackLv = barrackBuildingLevels(tc.engine);
            try {
                JSONObject ms = tc.modState("barracks");
                ms.put("max_shield", maxTier[1]);
                ms.put("max_spear", maxTier[2]);
                ms.put("max_bow", maxTier[3]);
                ms.put("batch", batch);
                ms.put("tier_src", tierSource);
                ms.put("furnace_lv", barrackLv[0]);
                ms.put("lv_shield", barrackLv[1]);
                ms.put("lv_spear", barrackLv[2]);
                ms.put("lv_bow", barrackLv[3]);
                tc.store.putString("mod:" + tc.fpid + ":" + tc.sid + ":barracks", ms.toString());
            } catch (Exception ignored) {
            }
            String[] trainIds = {"", "train_shield", "train_spear", "train_bow"};
            String[] capKeys = {"", "cap_shield", "cap_spear", "cap_bow"};
            String[] capLvKeys = {"", "cap_lv_shield", "cap_lv_spear", "cap_lv_bow"};
            JSONObject capState = tc.modState("barracks");
            // 本轮训练响应体里带回的队列完成时刻（line → epoch millis）：
            // 收尾 captureBarrackState 优先用它们，避免旧 notify_barrack_init 把状态覆盖回"空闲"。
            Map<Integer, Long> freshTrainEnds = new LinkedHashMap<>();
            StringBuilder trNote = new StringBuilder();
            int trOk = 0;
            for (int line = 1; line <= 3; line++) {
                if (!tc.subOn("barracks", trainIds[line], true)) continue;
                subAttempt++;
                String sel = tc.strategy("barracks", trainIds[line], "auto");
                int parsed = 0;
                if (!"auto".equals(sel)) {
                    try {
                        parsed = Integer.parseInt(sel.trim());
                    } catch (NumberFormatException nfe) {
                        // 历史脏数据（曾把 map 串写进 strategy）→ 回退 auto，不再抛异常中断整个兵营任务
                        parsed = 0;
                    }
                }
                int derived = barrackTrainTier(parsed, maxTier[line], 12);
                int curLv = (int) Math.max(0L, Math.min(barrackLv[line], Integer.MAX_VALUE));
                int cap = capState.optInt(capKeys[line], 0);
                int capLv = capState.optInt(capLvKeys[line], 0);
                int chosen = effectiveTrainTier(derived, cap, capLv, curLv);
                if (chosen <= 0) {
                    // 2026-10-28 修复：缺熔炉/兵营/士兵推送时无法推导已解锁档位，
                    // 旧代码只写"无兵力数据"难以定位；改为明确文案，不静默也不盲发未解锁档。
                    String why = "无解锁档位数据（缺熔炉/兵营/士兵推送）";
                    trNote.append("盾矛弓".charAt(line - 1)).append(why).append(' ');
                    m.subSkip(trainIds[line], why);
                    continue;
                }
                int[] attempts = barrackTierAttempts(chosen);
                int trained = 0;
                int downgrades = 0;
                int stopCode = 0;
                boolean stopOther = false;
                for (int attempt : attempts) {
                    GameMessage rsp = tc.engine.request("req_barrack_train_soldier",
                            Exec.args("soldier_id", line * 10000L + attempt * 100L,
                                    "soldier_num", batch, "immediate", false));
                    int code = Exec.codeOf(rsp);
                    if (Exec.ok(rsp)) {
                        trained = attempt;
                        // 2026-10-28 task-7：训练响应体自带新队列
                        // {barrack:{bid,soldier_id,soldier_num,finish_ts,total_time,qid}}
                        // （网页版 DailyBarrackState.markTrainSuccess:73-81 → rememberQueue 同款）。
                        // 立即写"训练中 · HH:mm可收"，并登记本轮 freshTrainEnds，防收尾旧 notify 覆盖。
                        long endMs = barrackQueueEndMillis(rsp.getContent(), System.currentTimeMillis());
                        if (endMs > 0L) {
                            int rowLine = barrackQueueLine(rsp.getContent(), line);
                            freshTrainEnds.put(rowLine, endMs);
                            String row = barrackQueueStatusText(endMs);
                            if (row != null) tc.saveModText("barracks", collectIds[rowLine], row);
                        }
                        break;
                    }
                    if (code == 2106) {
                        // 该档未解锁 → 降一档重试（游戏真实口径与参考表有偏差时的兜底）
                        downgrades++;
                        stopCode = 2106;
                        continue;
                    }
                    // 非 2106 的码：正常状态分类，不再降档
                    stopCode = code;
                    stopOther = true;
                    if (code == 2103) {
                        trNote.append("盾矛弓".charAt(line - 1)).append("容量满 ");
                        m.subSkip(trainIds[line], "兵营容量满");
                    } else if (code == 2104) {
                        m.subSkip(trainIds[line], "无可收兵");
                    } else if (code == 2101) {
                        trNote.append("盾矛弓".charAt(line - 1)).append("训练中 ");
                        m.subSkip(trainIds[line], "上批训练中");
                    } else if (code == 2107) {
                        m.subSkip(trainIds[line], "兵营升级中");
                    } else if (code == 2105) {
                        m.subSkip(trainIds[line], "兵营类型不匹配");
                    } else {
                        trNote.append("盾矛弓".charAt(line - 1)).append("码").append(code).append(" ");
                        m.subFail(trainIds[line], "训练码" + code);
                    }
                    break;
                }
                if (trained > 0) {
                    trOk++;
                    trNote.append("盾矛弓".charAt(line - 1)).append("T").append(trained).append("×").append(batch)
                            .append(downgrades > 0 ? "(↓" + downgrades + ")" : "").append(' ');
                    m.subOk(trainIds[line], "续训T" + trained + "×" + batch
                            + (downgrades > 0 ? "（2106降档" + downgrades + "次）" : ""));
                    // 记录可训练上限 + 发现时的兵营等级：后续轮次 min(推导档, cap)，升级后自动重探
                    capState.put(capKeys[line], trained);
                    capState.put(capLvKeys[line], curLv);
                } else if (!stopOther && stopCode == 2106) {
                    // 本轮降档尝试全部 2106：把探测起点压到更低一档，跨轮继续收敛（不再永远撞同一档）
                    int lowest = attempts[attempts.length - 1];
                    int next = Math.max(1, lowest - 1);
                    capState.put(capKeys[line], next);
                    capState.put(capLvKeys[line], curLv);
                    trNote.append("盾矛弓".charAt(line - 1)).append("该档未解锁(已降至T").append(lowest).append(") ");
                    m.subSkip(trainIds[line], "该档未解锁（T" + derived + "→T" + lowest
                            + " 均 2106，下轮从 T" + next + " 探测）");
                }
            }
            // cap_* 落盘（供后续轮次收敛与 App 排查；max_* 已在上面按建筑等级写入）
            try {
                JSONObject ms = tc.modState("barracks");
                for (int line = 1; line <= 3; line++) {
                    if (capState.has(capKeys[line])) ms.put(capKeys[line], capState.getInt(capKeys[line]));
                    if (capState.has(capLvKeys[line])) ms.put(capLvKeys[line], capState.getInt(capLvKeys[line]));
                }
                tc.store.putString("mod:" + tc.fpid + ":" + tc.sid + ":barracks", ms.toString());
            } catch (Exception ignored) {
            }
            if (trOk > 0) m.claimed("训练" + trNote.toString().trim());
            else if (trNote.length() > 0) m.skip("训练未成：" + trNote.toString().trim());
            else if (subAttempt == 0) m.skip("兵营收兵/训练子项均未启用");
            // 收发动作会改变兵营队列，服务端会推新 notify_barrack_init——补捕一次刷新状态行
            // （与 App 端 TownTasks.barracks 收尾同款），避免状态行停留在上一轮。
            // 本轮训练响应带回的 freshTrainEnds 优先级更高：不会被旧 notify 覆盖回"空闲"。
            try {
                tc.engine.collectNotifies(800);
                captureBarrackState(tc, freshTrainEnds);
            } catch (Throwable ignored) {
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "兵营训练");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "barracks", "兵营训练");
    }

    /**
     * 训练档位判定（纯函数，App 端 TownTasks.barracks 同款口径）：
     * <ul>
     *   <li>策略显式 Tn（{@code strategyTier>0}）→ min(Tn, 已解锁最高档)；解锁数据缺失时按
     *       {@code fallbackTier} 保守放行（App 端同样允许显式指定）；</li>
     *   <li>策略 auto（{@code strategyTier<=0}）→ 已解锁最高档；</li>
     *   <li>返回 0 = 无任何可训练档位，调用方必须写可读 SKIP，不能静默也不该盲发。</li>
     * </ul>
     */
    static int barrackTrainTier(int strategyTier, int maxTier, int fallbackTier) {
        int best = Math.max(0, Math.min(maxTier, 12));
        if (strategyTier <= 0) return best;
        int cap = maxTier > 0 ? best : Math.max(1, Math.min(fallbackTier, 12));
        return Math.max(0, Math.min(strategyTier, cap));
    }

    /**
     * 2106 降档尝试序列（纯函数，2026-10-28 生产兜底）：
     * 首选档返回 2106「该档未解锁」时按 T-1、T-2… 逐级重试，单营单轮最多 3 次（防请求风暴），
     * 最低 T1。这样即使游戏真实解锁口径与参考表有偏差也能找出可训练档，不再"永远空转"。
     */
    static int[] barrackTierAttempts(int preferredTier) {
        int p = Math.max(1, Math.min(preferredTier, 12));
        int n = Math.min(3, p);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = p - i;
        return out;
    }

    /**
     * 有效训练档（纯函数）：把"按兵营等级推导档"与"历史探测到的可训练上限 cap"合成。
     * <ul>
     *   <li>cap≤0（未探测过）→ 用推导档；推导档未知但 cap 已知 → 用 cap；</li>
     *   <li>兵营已升级（当前等级 &gt; cap 记录时等级）→ cap 失效，重新探测（用推导档）；</li>
     *   <li>否则 min(推导档, cap)，避免每轮都用已知不可训练的档去撞 2106。</li>
     * </ul>
     */
    static int effectiveTrainTier(int derivedTier, int capTier, int capLevel, int currentLevel) {
        int derived = Math.max(0, Math.min(derivedTier, 12));
        int cap = Math.max(0, Math.min(capTier, 12));
        if (derived <= 0) return cap;
        if (cap <= 0) return derived;
        if (capLevel > 0 && currentLevel > capLevel) return derived;   // 兵营升级 → 清 cap 重探
        return Math.min(derived, cap);
    }

    // ================= 兵营可训练档位（与 App/网页版一致） =================

    /** 网页版 DailyBarrackCatalog.BARRACK_LEVELS：T1~T10 所需<b>本营建筑等级</b>。 */
    private static final int[] BARRACK_TIER_LEVELS = {1, 4, 7, 11, 13, 16, 19, 22, 26, 30};
    private static final long[] TIER11_TECH = {7007, 8007, 9007};
    private static final long[] TIER12_TECH = {10006, 11006, 12006};
    /** 大熔炉建筑 id（301）：只在兵营等级缺失时作为回退口径，绝不覆盖兵营等级。 */
    private static final long FURNACE_BID = 301L;

    static int[] barrackUnlockTiers(WjdrEngine engine) {
        return barrackUnlockTiers(engine, null);
    }

    /** 三营建筑等级 + 熔炉等级：[0]=熔炉(301)，[1..3]=盾/矛/弓(321/322/323)；缺失记 0。 */
    static long[] barrackBuildingLevels(WjdrEngine engine) {
        try {
            Map<String, Object> shelter = engine.notifyData("notify_shelter_info");
            return parseBarrackBuildingLevels(shelter == null ? null : shelter.get("building_info"));
        } catch (Throwable t) {
            return new long[4];
        }
    }

    /**
     * 纯函数：解析 building_info → 建筑等级表。
     * 形状兼容 {bid→{base:{lv}}} Map / [{bid,lv}] List / 单条建筑本体；bid 缺失时兼容 id 键。
     *
     * @return 长度 4：[0]=熔炉(301)，[1..3]=盾(321)/矛(322)/弓(323)；缺失=0（区分"未知"与 1 级）
     */
    static long[] parseBarrackBuildingLevels(Object buildingInfo) {
        long[] levels = new long[4];
        if (buildingInfo == null) return levels;
        List<Object> entries = new ArrayList<>();
        if (buildingInfo instanceof Map) {
            entries.addAll(((Map<?, ?>) buildingInfo).values());
        } else if (buildingInfo instanceof List) {
            entries.addAll((List<?>) buildingInfo);
        } else {
            Map<String, Object> single = Exec.mapOf(buildingInfo);
            if (single == null) return levels;
            entries.add(single);
        }
        for (Object raw : entries) {
            Map<String, Object> b = Exec.mapOf(raw);
            if (b == null) continue;
            long bid = Exec.longOf(b.get("bid"));
            if (bid <= 0L) bid = Exec.longOf(b.get("id"));       // 兼容 id 键
            Map<String, Object> base = Exec.mapOf(b.get("base"));
            Object lvObj = base != null && base.get("lv") != null ? base.get("lv") : b.get("lv");
            long lv = Exec.longOf(lvObj);
            if (lv <= 0L) continue;
            if (bid == FURNACE_BID) {
                if (lv > levels[0]) levels[0] = lv;
            } else if (bid >= 321L && bid <= 323L) {
                int idx = (int) (bid - 320L);
                if (lv > levels[idx]) levels[idx] = lv;
            }
        }
        return levels;
    }

    /**
     * 纯函数：由建筑等级推导三营可训练档位（1:1 网页版
     * {@code DailyBarrackState.availableOptions:357-368} + {@code DailyBarrackCatalog.minimumBarrackLevel}）。
     *
     * <p><b>2026-10-28 口径修正</b>：每营用<b>自己</b>的建筑等级（321盾/322矛/323弓）对照
     * {@link #BARRACK_TIER_LEVELS}，<b>不再用大熔炉(301)等级覆盖</b>——旧实现把熔炉 13 级当成
     * 三营 13 级去训练 T5(10500)，被游戏服以 2106「该档未解锁」拒绝（生产实证），导致永远不训练。
     * 兵营等级缺失(≤0)时才回退熔炉等级，并标注 {@code furnace-fallback} 便于排查。
     *
     * @param raw    [0]=熔炉，[1..3]=盾/矛/弓建筑等级（0=缺失）
     * @param srcOut 可选：回填口径 "barrack" / "furnace-fallback"
     * @return 档位 [1..3]；无任何建筑数据返回 null（调用方回退"已拥有士兵最高档"）
     */
    static int[] barrackTiersFromLevels(long[] raw, Map<Long, Long> spar,
                                        Map<Long, Long> refine, String[] srcOut) {
        long[] r = raw == null ? new long[4] : raw;
        long furnace = r.length > 0 ? Math.max(0L, r[0]) : 0L;
        long[] effective = new long[4];
        boolean anyBarrack = false;
        boolean fallback = false;
        for (int line = 1; line <= 3; line++) {
            long lv = r.length > line ? r[line] : 0L;
            if (lv > 0L) {
                effective[line] = lv;
                anyBarrack = true;
            } else if (furnace > 0L) {
                effective[line] = furnace;
                fallback = true;
            }
        }
        if (!anyBarrack && furnace <= 0L) return null;
        if (srcOut != null && srcOut.length > 0) {
            srcOut[0] = fallback ? "furnace-fallback" : "barrack";
        }
        return computeUnlockTiers(effective, spar, refine);
    }

    /**
     * 兵种 T 级解锁判定（2026-10-28 按网页版 1:1 修正口径）：
     * <ul>
     *   <li>T1~T10：<b>各营自己的建筑等级</b> ≥ BARRACK_LEVELS[T-1]（网页版
     *       DailyBarrackState.availableOptions + DailyBarrackCatalog.minimumBarrackLevel）；</li>
     *   <li>T11/T12：另需 spar(7007/8007/9007) / refine(10006/11006/12006) 科技，且本营等级 ≥30；</li>
     *   <li>兵营等级缺失才回退熔炉等级（tier_src=furnace-fallback）。</li>
     * </ul>
     */
    static int[] barrackUnlockTiers(WjdrEngine engine, String[] sourceOut) {
        try {
            Map<String, Object> shelter = engine.notifyData("notify_shelter_info");
            if (shelter == null) return null;
            long[] raw = parseBarrackBuildingLevels(shelter.get("building_info"));
            if (raw[0] <= 0L && raw[1] <= 0L && raw[2] <= 0L && raw[3] <= 0L) return null;
            Map<Long, Long> spar = researchLevels(engine, "notify_spar_science_all_research");
            Map<Long, Long> refine = researchLevels(engine, "notify_refine_science_all_research");
            // 诊断日志：解锁规则实证（熔炉/真实三营等级 + 全量科技等级 + 兵营init + 已有士兵），用于校正 T 级判定
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
            String[] srcRef = new String[1];
            int[] tiers = barrackTiersFromLevels(raw, spar, refine, srcRef);
            if (tiers == null) return null;
            String src = srcRef[0];
            if (sourceOut != null && sourceOut.length > 0) sourceOut[0] = src;
            // 打印<b>真实</b>三营等级与推导档位：部署后可直接核对
            // （例：兵营T级 熔炉lv13 兵营[11,11,11] 口径=barrack → 盾T4/矛T4/弓T4）
            FileLog.info("兵营T级 熔炉lv{} 兵营[{},{},{}] 口径={} → 盾T{} 矛T{} 弓T{} spar科技{}条 refine科技{}条",
                    raw[0], raw[1], raw[2], raw[3], src,
                    tiers[1], tiers[2], tiers[3],
                    String.valueOf(spar == null ? 0 : spar.size()),
                    String.valueOf(refine == null ? 0 : refine.size()));
            return tiers;
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
        captureBarrackState(tc, null);
    }

    /**
     * 三兵营状态刷新（2026-10-28 task-7 增强）：
     * 除登录/推送的 notify_barrack_init 外，接受本轮<b>训练响应体回灌</b>的队列完成时刻。
     * 训练刚成功时旧 notify 往往还是"空闲"快照，若直接覆盖会把用户刚看到的状态打回原形。
     *
     * @param freshEnds line(1盾/2矛/3弓) → 队列完成时刻（epoch millis）；非空时该营优先用 fresh 值
     */
    static void captureBarrackState(TaskContext tc, Map<Integer, Long> freshEnds) {
        try {
            Map<String, Object> bi = tc.engine.notifyData("notify_barrack_init");
            Map<Integer, Long> fresh = freshEnds == null
                    ? java.util.Collections.emptyMap() : freshEnds;
            if (bi == null && fresh.isEmpty()) return;
            long now = System.currentTimeMillis() / 1000L;
            String[] collectIds = {"", "collect_shield", "collect_spear", "collect_bow"};
            for (int line = 1; line <= 3; line++) {
                Long freshEnd = fresh.get(line);
                if (bi == null && freshEnd == null) continue;
                long best = bi == null ? 0L : scanBarrackEnd(bi, 320 + line, now, 0);
                String row = barrackStateRow(freshEnd, best, now);
                if (row != null) tc.saveModText("barracks", collectIds[line], row);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 单营状态行（纯函数）：训练响应带回的队列完成时刻 <b>优先于</b> notify 扫描结果。
     *
     * @param freshEndMillis 本轮训练响应里的完成时刻（epoch millis；null=无）
     * @param notifyEndSec   notify_barrack_init 扫描到的完成时刻（秒；0=无队列数据）
     * @param nowSec         当前时刻（秒）
     * @return 状态行；null=不写（保持既有文案）
     */
    static String barrackStateRow(Long freshEndMillis, long notifyEndSec, long nowSec) {
        if (freshEndMillis != null && freshEndMillis > 0L) {
            String row = barrackQueueStatusText(freshEndMillis);
            if (row != null) return row;
        }
        if (notifyEndSec > nowSec) return barrackQueueStatusText(notifyEndSec * 1000L);
        return notifyEndSec > 0L ? "有完工批次可收" : "空闲（无训练队列数据）";
    }

    // ---------- 训练响应体里的兵营队列（task-7，网页版 markTrainSuccess 同款） ----------

    /**
     * 从 req_barrack_train_soldier 响应 content 取队列完成时刻（epoch millis；0=响应未带队列）。
     * 实证形状：{@code {"code":0,"barrack":{"bid":321,"finish_ts":1791896497,"total_time":10319,...}}}；
     * finish_ts 缺失/已过期时用 {@code now + total_time} 兜底；兼容毫秒时间戳。
     */
    static long barrackQueueEndMillis(Map<String, Object> content, long nowMillis) {
        Map<String, Object> barrack = barrackQueueOf(content);
        if (barrack == null) return 0L;
        long nowSec = nowMillis / 1000L;
        long finish = Exec.longOf(barrack.get("finish_ts"));
        long total = Exec.longOf(barrack.get("total_time"));
        long endSec = 0L;
        if (finish > 100_000_000_000L) {
            endSec = finish / 1000L;                 // 毫秒时间戳兼容
        } else if (finish > nowSec) {
            endSec = finish;
        }
        if (endSec <= nowSec && total > 0L) endSec = nowSec + total;   // finish_ts 缺失/过期 → total_time 兜底
        if (endSec <= nowSec) return 0L;
        return endSec * 1000L;
    }

    /** 状态行文案："训练中 · HH:mm可收"（北京时区）；endMillis<=0 返回 null。 */
    static String barrackQueueStatusText(long endMillis) {
        if (endMillis <= 0L) return null;
        return "训练中 · " + com.carlos.vaultserver.Beijing.fmt("HH:mm")
                .format(new java.util.Date(endMillis)) + "可收";
    }

    /** 训练响应队列所属营（1盾/2矛/3弓）：取 barrack.bid(321~323)，识别不到用请求行兜底。 */
    static int barrackQueueLine(Map<String, Object> content, int fallbackLine) {
        Map<String, Object> barrack = barrackQueueOf(content);
        long bid = barrack == null ? 0L : Exec.longOf(barrack.get("bid"));
        if (bid >= 321L && bid <= 323L) return (int) (bid - 320L);
        return fallbackLine >= 1 && fallbackLine <= 3 ? fallbackLine : 0;
    }

    /** 是否像兵营队列本体（含 bid 且含完成时间字段）。 */
    private static boolean isBarrackQueueBody(Map<String, Object> m) {
        return m != null && m.containsKey("bid")
                && (m.containsKey("finish_ts") || m.containsKey("total_time"));
    }

    /**
     * 从训练响应 content 取 barrack 队列 Map（纯函数）：
     * 兼容 {barrack:{...}} 包裹、已解包内层（content 本身是队列）、{barrack:[{...}]} 多营 List（取首条）。
     */
    static Map<String, Object> barrackQueueOf(Map<String, Object> content) {
        if (content == null) return null;
        Object raw = content.get("barrack");
        Map<String, Object> data = Exec.mapOf(content.get("data"));
        if (raw == null && data != null) raw = data.get("barrack");   // {data:{barrack:{...}}} 兼容
        if (raw == null) {
            // 响应已被解包成"队列本体"的兜底（含 data 内层）
            if (isBarrackQueueBody(content)) return content;
            if (data != null && isBarrackQueueBody(data)) return data;
            return null;
        }
        Map<String, Object> single = Exec.mapOf(raw);
        if (single != null) return single;
        for (Map<String, Object> m : Exec.mapsOf(raw)) {
            if (m != null && (m.containsKey("bid")
                    || m.containsKey("finish_ts") || m.containsKey("total_time"))) {
                return m;
            }
        }
        return null;
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
            // 指定技能优先；未配置时沿用旧默认技能集合，保证零配置账号行为不变。
            org.json.JSONArray selectedSkills = pp2.optJSONArray("selected_skills");
            if (selectedSkills != null && selectedSkills.length() > 0) {
                for (int i = 0; i < selectedSkills.length(); i++) {
                    long sid = selectedSkills.optLong(i, 0);
                    if (sid > 0) skills.add(sid);
                }
            } else {
                for (long s : new long[]{550051L, 550071L, 550081L, 550091L, 550101L,
                        550111L, 550121L, 550131L, 550141L}) {
                    skills.add(s);
                }
            }
            if (pp2.optBoolean("skills", pp2.optBoolean("auto_skills", true))) {
                GameMessage sk = tc.engine.request("req_pet_use_skill",
                        Exec.args("skill_id_lst", skills));
                if (Exec.ok(sk)) { m.claimed("宠物技能"); m.subOk("skills", "技能已开"); }
                else { m.skip("宠物技能冷却/不可用(码" + Exec.codeOf(sk) + ")"); m.subSkip("skills", "码" + Exec.codeOf(sk)); }
            }
            if (pp2.optBoolean("alliance_chest", true)) {
                GameMessage ab = tc.engine.request("req_pet_adventure_batch_take_alliance_reward", null);
                if (Exec.ok(ab)) { m.claimed("联盟寻宝宝箱"); m.subOk("alliance_chest", "宝箱已领"); }
                else {
                    // 2026-10-30：网页版 DailyPetAdventureService.NO_ALLIANCE_REWARD_CODES
                    // = {11790,11792,11794,11795,11796,11797,11799,11803} → 暂无可领（非异常）。
                    int abCode = Exec.codeOf(ab);
                    boolean noReward = abCode == 11790 || abCode == 11792 || abCode == 11794
                            || abCode == 11795 || abCode == 11796 || abCode == 11797
                            || abCode == 11799 || abCode == 11803;
                    m.skip(noReward ? "联盟宝箱暂无可领" : ("联盟宝箱不可领(码" + abCode + ")"));
                    m.subSkip("alliance_chest", noReward ? "暂无可领" : ("码" + abCode));
                }
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
            // I3 专家情报技能（免费日技能，默认开；与网页版 expert-intelligence 60s 节奏同语义，
            // 由 TaskCadenceScheduler 的 expert=60s 驱动，不新增 task id）。
            if (tc.subOn("expert", "skill", true)) {
                expertSkill(tc, m);
            }
        } catch (Exception e) {
            if (sessionLost(e)) return ServerTasks.classifyException(e, "专家游历");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "expert", "专家游历");
    }

    // ---------- 专家情报技能（I3，1:1 网页版 DailyExpertIntelligenceService） ----------
    //
    // 网页版：notify_expert_info/update_expert_info/update_expert_skill_info 的
    // experts[].skills[].effects[] 中扫描 effect id=6 的“情报”技能；服务器当日 00:01:30
    // 安全窗（dayStart+90s，DailyExpertIntelligenceState.ServerDay.requestWindowEpochSecond）
    // 后调 req_use_expert_skill{expert_id, skill_id, effect_type:6}；成功或 40237（当日刷新
    // 上限）记当日台账，当天不再重试（DailyExpertIntelligenceService.java:39-143,200-214）。

    static final long EXPERT_SKILL_WINDOW_OFFSET_MILLIS = 90_000L;
    static final int EXPERT_SKILL_EFFECT_ID = 6;
    static final String EXPERT_SKILL_LEDGER_SCOPE = "expert_skill";

    /** 情报技能快照：专家/技能 id + 该技能情报效果的最近使用时刻（秒，0=未用）。 */
    static final class ExpertSkill {
        final long expertId;
        final long skillId;
        final long lastUseTs;

        ExpertSkill(long expertId, long skillId, long lastUseTs) {
            this.expertId = expertId;
            this.skillId = skillId;
            this.lastUseTs = Math.max(0L, lastUseTs);
        }

        boolean ready() {
            return expertId > 0L && skillId > 0L;
        }
    }

    /** 00:01:30 安全窗（北京时间当日 00:00 后 90 秒）。 */
    static boolean expertSkillWindowOpen(long nowMillis) {
        java.time.ZonedDateTime now = java.time.Instant.ofEpochMilli(nowMillis).atZone(Beijing.ZONE_ID);
        java.time.ZonedDateTime window = now.toLocalDate().atStartOfDay(Beijing.ZONE_ID)
                .plusSeconds(EXPERT_SKILL_WINDOW_OFFSET_MILLIS / 1000L);
        return !now.isBefore(window);
    }

    /** 是否“今日已用过”（效果 last_use_ts/last_refresh_time 的北京日期==今天）。 */
    static boolean expertSkillUsedToday(long lastUseTs, long nowMillis) {
        if (lastUseTs <= 0L) return false;
        String usedDay = Beijing.fmt("yyyy-MM-dd").format(new java.util.Date(lastUseTs * 1000L));
        String today = Beijing.fmt("yyyy-MM-dd").format(new java.util.Date(nowMillis));
        return usedDay.equals(today);
    }

    /** 从通知扫描情报技能（info 全量 → update_expert_info 覆盖 → update_expert_skill_info 更新使用时刻）。 */
    static ExpertSkill expertSkillOf(WjdrEngine engine) {
        ExpertSkill skill = scanExpertSkillNotify(engine.notifyData("notify_expert_info"));
        ExpertSkill updated = scanExpertSkillNotify(engine.notifyData("notify_update_expert_info"));
        if (updated != null) skill = updated;
        Map<String, Object> skillUpdate = engine.notifyData("notify_update_expert_skill_info");
        if (skillUpdate != null && skill != null) {
            long eid = Exec.longOf(skillUpdate.get("expert_id"));
            Map<String, Object> skillInfo = Exec.mapOf(skillUpdate.get("skill_info"));
            if (skillInfo == null) skillInfo = Exec.mapOf(skillUpdate.get("skill"));
            long sid = skillInfo == null ? 0L : Exec.longOf(skillInfo.get("id"));
            if (skillInfo != null && (eid <= 0L || eid == skill.expertId)
                    && (sid <= 0L || sid == skill.skillId)) {
                Long ts = intelligenceEffectUseTs(skillInfo);
                if (ts != null) skill = new ExpertSkill(skill.expertId, skill.skillId, ts);
            }
        }
        return skill;
    }

    static ExpertSkill scanExpertSkillNotify(Map<String, Object> data) {
        if (data == null) return null;
        List<Map<String, Object>> experts = new ArrayList<>();
        Object raw = data.get("experts");
        if (raw instanceof Map) {
            for (Object v : ((Map<?, ?>) raw).values()) {
                Map<String, Object> m = Exec.mapOf(v);
                if (m != null) experts.add(m);
            }
        } else {
            experts.addAll(Exec.mapsOf(raw));
        }
        Map<String, Object> single = Exec.mapOf(data.get("expert"));
        if (single != null) experts.add(single);
        if (data.containsKey("skills")) experts.add(data);   // update_expert_info 的形状可能直接是 expert 本体
        for (Map<String, Object> expert : experts) {
            ExpertSkill s = scanExpertSkill(expert);
            if (s != null) return s;
        }
        return null;
    }

    static ExpertSkill scanExpertSkill(Map<String, Object> expert) {
        if (expert == null) return null;
        long eid = Exec.longOf(expert.get("id"));
        if (eid <= 0L) return null;
        List<Map<String, Object>> skills = new ArrayList<>();
        Object raw = expert.get("skills");
        if (raw instanceof Map) {
            for (Object v : ((Map<?, ?>) raw).values()) {
                Map<String, Object> m = Exec.mapOf(v);
                if (m != null) skills.add(m);
            }
        } else {
            skills.addAll(Exec.mapsOf(raw));
        }
        for (Map<String, Object> skill : skills) {
            Long ts = intelligenceEffectUseTs(skill);
            if (ts == null) continue;
            long sid = Exec.longOf(skill.get("id"));
            if (sid <= 0L) continue;
            return new ExpertSkill(eid, sid, ts);
        }
        return null;
    }

    /** 技能效果里 effect id=6（情报）的最近使用时刻：返回 null=没有情报效果，0=有但未用。 */
    static Long intelligenceEffectUseTs(Map<String, Object> skill) {
        if (skill == null) return null;
        Object effects = skill.get("effects");
        List<Map<String, Object>> matched = new ArrayList<>();
        if (effects instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) effects).entrySet()) {
                Map<String, Object> effect = Exec.mapOf(e.getValue());
                long id = effect == null ? 0L : Exec.longOf(effect.get("id"));
                if (id <= 0L) id = expertNumericId(e.getKey());
                if (id <= 0L && effect != null) id = Exec.longOf(effect.get("type"));
                if (id == EXPERT_SKILL_EFFECT_ID) {
                    matched.add(effect == null ? new LinkedHashMap<>() : effect);
                }
            }
        } else {
            for (Map<String, Object> effect : Exec.mapsOf(effects)) {
                long id = Exec.longOf(effect.get("id"));
                if (id <= 0L) id = Exec.longOf(effect.get("type"));
                if (id == EXPERT_SKILL_EFFECT_ID) matched.add(effect);
            }
        }
        if (matched.isEmpty()) return null;
        long ts = 0L;
        for (Map<String, Object> effect : matched) {
            if (effect == null) continue;
            long t = Exec.longOf(effect.get("last_refresh_time"));
            if (t <= 0L) t = Exec.longOf(effect.get("last_use_ts"));
            if (t > ts) ts = t;
        }
        return ts;
    }

    /** "6.0" 这类数字键解析（网页版 DailyServiceSupport.number 同款）。 */
    private static long expertNumericId(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** I3 执行：默认开（sub.skill），当日台账用 ClaimLedger（跨天自动失效）。 */
    private static void expertSkill(TaskContext tc, Exec.Multi m) throws Exception {
        long now = System.currentTimeMillis();
        if (ClaimLedger.isClaimed(tc, EXPERT_SKILL_LEDGER_SCOPE, "done")
                || ClaimLedger.isClaimed(tc, EXPERT_SKILL_LEDGER_SCOPE, "limit")) {
            m.subSkip("skill", "今日专家情报技能已处理");
            return;
        }
        if (!expertSkillWindowOpen(now)) {
            m.subSkip("skill", "待服务器00:01:30安全窗");
            return;
        }
        ExpertSkill skill = expertSkillOf(tc.engine);
        if (skill == null || !skill.ready()) {
            m.subSkip("skill", "专家数据未同步");
            return;
        }
        if (expertSkillUsedToday(skill.lastUseTs, now)) {
            ClaimLedger.mark(tc, EXPERT_SKILL_LEDGER_SCOPE, "done");
            tc.saveModText("expert", "skill", "今日专家情报技能已使用");
            m.subSkip("skill", "今日已使用");
            return;
        }
        GameMessage rsp = tc.engine.request("req_use_expert_skill", Exec.args(
                "expert_id", skill.expertId, "skill_id", skill.skillId,
                "effect_type", (long) EXPERT_SKILL_EFFECT_ID));
        int code = Exec.codeOf(rsp);
        if (Exec.ok(rsp)) {
            ClaimLedger.mark(tc, EXPERT_SKILL_LEDGER_SCOPE, "done");
            tc.saveModText("expert", "skill", "专家情报技能已使用（expert " + skill.expertId
                    + " · skill " + skill.skillId + "）");
            m.claimed("专家情报技能");
            m.subOk("skill", "专家情报技能已用");
        } else if (code == 40237 || code == 40238 || code == 40236) {
            // 40237=当日刷新次数上限；40236/40238 与 town_affairs 已领家族同义（防 60s 节奏重复请求）
            ClaimLedger.mark(tc, EXPERT_SKILL_LEDGER_SCOPE, "limit");
            tc.saveModText("expert", "skill", "今日专家情报技能次数已用尽(40237)");
            m.skip("专家情报技能今日次数已用尽(40237)");
            m.subSkip("skill", "今日已用尽");
        } else {
            m.subSkip("skill", "技能码" + code);
            tc.saveModText("expert", "skill", "专家情报技能待重试(码" + code + ")");
        }
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
    //
    // P0 修复 2026-10-23（1:1 网页版 DailyArenaService/DailyArenaState）：
    //  ① 每日奖励不再“打完 5 场立即领”：网页版结算窗 23:10–24:00 才领
    //     （DailyArenaService.java:74 DAILY_REWARD_WINDOW_START=LocalTime.MIDNIGHT.minusSeconds(3000)、
    //      :761-768,774-809）；未到窗/次数不足写 SKIP“待结算窗”，领取失败 5 分钟重试、
    //      2926/2925 记 settled，30s 节奏下不刷 ERROR。
    //  ② 当日挑战次数/胜负按服务端 base 与 fight 响应 report.win 记录（DailyArenaState.java:160-186、
    //     DailyArenaService.java:599-601），base 取不到时按本地当日已发场次封顶 5 次，
    //     避免 30s 节奏重复请求；③ 买次数默认关（A2 一小项未做，宁漏不花）。

    /** 每日奖励达标场次：1:1 网页版 DAILY_REWARD_CHALLENGE_TARGET=5。 */
    static final int ARENA_DAILY_REWARD_CHALLENGES = 5;

    /** 结算窗起点 23:10（网页版 DAILY_REWARD_WINDOW_START，窗口到次日 00:00 为止）。 */
    static final int ARENA_REWARD_WINDOW_START_MINUTE = 23 * 60 + 10;

    /** 领取失败后的重试间隔：1:1 网页版 DAILY_REWARD_RETRY_MILLIS=300000。 */
    static final long ARENA_REWARD_RETRY_MILLIS = 300_000L;

    private static final String ARENA_DAY_KEY_PREFIX = "srv:arena:day:";

    public static TaskOutcome arena(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        ArenaDay day = null;
        try {
            tc.engine.collectNotifies(1500);
            JSONObject ap = tc.params("arena");
            JSONObject strat = ap.optJSONObject("strategy");
            int maxFights = strat != null && strat.has("fight") ? strat.optInt("fight", 5)
                    : ap.optInt("max_fights", 5);
            maxFights = Math.max(1, Math.min(10, maxFights));
            boolean onFight = tc.subOn("arena", "fight", true);
            boolean onDaily = tc.subOn("arena", "daily", true);
            long now = System.currentTimeMillis();
            boolean inWindow = arenaInDailyRewardWindow(now);
            day = ArenaDay.load(tc, now);
            // 批次 B：竞技场独占窗（按角色）。自动挑战开启且未进结算窗时先进入 PREPARING；
            // 本轮结束按剩余场次提升为 ACTIVE 或释放，异常路径释放（TTL 另有 30 分钟兜底）。
            RoleKey arenaRole = new RoleKey(tc.fpid, tc.sid, tc.roleUid);
            // 安全口径（2026-10-30）：只有「已知还有场次」或「本轮确实派出过战斗」才进入独占窗；
            // 数据未知（knownCompleted<0 且 fightsSent=0）时不得假设有 5 场余量——否则会误锁其它任务 30 分钟。
            boolean arenaGateAllowed = onFight && !inWindow
                    && (day.knownCompleted() >= 0 || day.fightsSent > 0);
            if (arenaGateAllowed) ArenaGate.enterPreparing(arenaRole, now);
            else ArenaGate.release(arenaRole);
            // 登录推送 notify_arena_init 的 base 只在“当日尚无更权威计数”时采用（见 mergeInitBase）：
            // ① 只在 last_reset_ts 属于今天时才当权威（网页版 resetDailyStateIfNeeded 同口径，DailyArenaState.java:246-261）；
            // ② 一旦拿到过今天 fight 响应的 base，就不再回灌登录时旧快照（否则每轮把今日已打计数覆盖回 0，
            //    会导致结算窗里误判“次数不足”而不领奖）。
            day.mergeInitBase(arenaBaseOf(tc.engine.notifyData("notify_arena_init")), now);
            // A2 买次数（默认关）：网页版在 00:00-20:00 预购挑战次数（DailyArenaService.prePurchase:421-467,
            // req_arena_buy_challenge_times:439）；默认 off=不花钻，只有显式 buy_times=true + 预算才买。
            arenaBuyTimes(tc, m, day, ap, now, inWindow);
            day.save(tc);

            // 1. 挑战：结算窗内不新开挑战（网页版 23:10 后只做领奖，DailyArenaService.java:390-400）
            if (!onFight) {
                m.skip("子开关「自动挑战」已关闭");
                m.subSkip("fight", "子开关已关闭");
            } else if (inWindow) {
                m.skip("已进结算窗(23:10-24:00)，不新开挑战");
                m.subSkip("fight", "结算窗不新开");
            } else {
                int budget = arenaFightBudget(day.knownCompleted(), day.fightsSent, maxFights);
                // 买来的次数可以继续打（网页版打到 totalRemaining=0，不只看每日奖励目标 5 场）
                if (day.boughtTimes > 0) {
                    int cap = arenaDailyFightCap(day.boughtTimes);
                    int used = Math.max(day.fightsSent, Math.max(0, day.knownCompleted()));
                    budget = Math.min(maxFights, Math.max(budget, cap - used));
                }
                if (budget <= 0) {
                    int completed = day.knownCompleted();
                    String why = completed >= ARENA_DAILY_REWARD_CHALLENGES
                            ? "今日挑战已完成(" + completed + "/5)·待结算窗"
                            : "今日挑战场次已用完(" + day.fightsSent + "/5)·待结算窗";
                    m.skip(why);
                    m.subSkip("fight", "待结算窗");
                } else {
                    arenaFight(tc, m, day, budget, now);
                }
            }
            day.save(tc);
            tc.saveModText("arena", "fight",
                    "今日挑战 " + day.fightsSent + "/5 · 胜 " + day.wins
                            + (day.losses > 0 ? " 负 " + day.losses : "")
                            + (day.unknownResult > 0 ? " 结果未知 " + day.unknownResult : ""));

            // 2. 每日奖励（结算窗 23:10–24:00；2926/2925 记 settled；失败 5 分钟重试、SKIP 不写 ERROR）
            arenaDailyReward(tc, m, day, onDaily, inWindow);
            day.save(tc);
            int completedForText = day.knownCompleted();
            tc.saveModText("arena", "daily", !onDaily
                    ? "子开关「每日奖励」已关闭"
                    : day.rewardClaimed
                    ? "今日每日奖励已领取"
                    : day.rewardSettled
                    ? "今日每日奖励已处理 · 待次日重新统计"
                    : "待结算窗(23:10-24:00) · 已完成"
                    + (completedForText >= 0 ? completedForText : day.fightsSent) + "/5");
            // 批次 B：还有剩余场次且可推进 → 保持 ACTIVE（其它任务让位，直到打完/结算窗/TTL）
            if (arenaGateAllowed && arenaGateRemaining(day, onFight, inWindow) > 0L) {
                ArenaGate.enterActive(arenaRole, now);
            } else {
                ArenaGate.release(arenaRole);
            }
        } catch (Exception e) {
            // 异常/断线：释放独占窗（TTL 兜底之外的主动安全），避免其它任务被卡住
            ArenaGate.release(new RoleKey(tc.fpid, tc.sid, tc.roleUid));
            // 断线/异常也要保住当日已发场次与领奖状态，避免重连后按 0 重算再打一遍
            if (day != null) day.save(tc);
            if (sessionLost(e)) return ServerTasks.classifyException(e, "竞技场");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "arena", "竞技场");
    }

    /** 挑战阶段（日场次上限内）：对手按战力升序；胜负读 report.win，不再用 Exec.ok 当胜场。 */
    private static void arenaFight(TaskContext tc, Exec.Multi m, ArenaDay day, int budget, long now)
            throws Exception {
        GameMessage opp = tc.engine.request("req_arena_get_opponent", null);
        if (!Exec.ok(opp)) {
            // 2026-10-28 修复"竞技场从来没执行过"的服务端静默：旧文案一律"未解锁"，
            // 分不清"次数用完/对手失效/请求过频"，用户侧看起来就是没跑。按码给可读原因。
            int opCode = Exec.codeOf(opp);
            if (opCode == 2901 || opCode == 2902) day.exhausted = true;   // 服务端判次数用完 → 独占窗释放
            String why = arenaOpponentSkipNote(opCode);
            m.skip(why);
            m.subSkip("fight", why);
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (String k : new String[]{"opponents", "data", "list"}) {
            list = Exec.mapsOf(opp.getContent().get(k));
            if (!list.isEmpty()) break;
        }
        if (list.isEmpty()) {
            m.skip("无对手数据");
            m.subSkip("fight", "无对手数据");
            return;
        }
        list.sort((a, b) -> Long.compare(powerOf(a), powerOf(b)));
        List<Long> heroes = squadHeroes(tc.engine);
        // 缺英雄推送/布阵数据时旧代码会直接跳过循环、产出空 Multi（SKIPPED 无文案）=
        // 用户看到"从来没执行过"。显式判定并写可读 SKIP。
        if (!arenaHasFightableHeroes(heroes)) {
            m.skip("无出征英雄数据，未发起挑战");
            m.subSkip("fight", "无英雄编队数据");
            return;
        }
        int fights = 0, wins = 0, losses = 0, unknownWins = 0;
        StringBuilder failCodes = new StringBuilder();
        for (Map<String, Object> o : list) {
            if (fights >= budget
                    || day.fightsSent >= arenaDailyFightCap(day.boughtTimes)) break;
            long oid = Exec.longOf(o.get("uid"));
            if (oid <= 0) oid = Exec.longOf(o.get("id"));
            if (oid <= 0) continue;
            List<Object> heroArray = new ArrayList<>(heroes);
            GameMessage f = tc.engine.request("req_arena_fight", Exec.args(
                    "opponent_type", Exec.longOf(o.get("type")) == 0 ? 1 : Exec.longOf(o.get("type")),
                    "opponent_id", oid,
                    "hero_array", heroArray,
                    "version", 26042101L));
            day.fightsSent++;   // 封顶按“已发出场次”计：即使失败也不会 30s 节奏无限重发
            int code = Exec.codeOf(f);
            if (Exec.ok(f)) {
                fights++;
                day.mergeBase(arenaBaseOf(f.getContent()), now);
                int win = arenaWinOf(f.getContent());
                if (win > 0) wins++;
                else if (win == 0) losses++;
                else unknownWins++;
            } else if (code == 2901 || code == 2902) {
                // 挑战次数已用完/不可购买：本轮到此为止，等次日（本轮不做买次数）
                day.exhausted = true;
                day.mergeBase(arenaBaseOf(f.getContent()), now);
                m.skip("挑战次数已用完(码" + code + ")");
                break;
            } else if (code == 2903) {
                // 太频繁：不计入当日场次，退避到下一轮 30s 节奏
                day.fightsSent = Math.max(0, day.fightsSent - 1);
                m.skip("挑战过于频繁(2903)，待下一轮");
                break;
            } else if (code == 2904 || code == 2920 || code == 2921) {
                // 对手过期/不可用：不计场次，下一轮重新拉对手（网页版视为需刷新）
                day.fightsSent = Math.max(0, day.fightsSent - 1);
                m.skip("对手已失效(码" + code + ")·下一轮刷新");
                break;
            } else {
                day.mergeBase(arenaBaseOf(f.getContent()), now);
                if (failCodes.length() < 40) failCodes.append(code).append(' ');
            }
            Exec.sleep(800);
        }
        day.wins += wins;
        day.losses += losses;
        day.unknownResult += unknownWins;
        if (fights > 0) {
            m.claimed("挑战" + fights + "场胜" + wins
                    + (losses > 0 ? "负" + losses : "")
                    + (unknownWins > 0 ? "结果未知" + unknownWins : "")
                    + " · 今日" + day.fightsSent + "/5");
            m.subOk("fight", "挑战" + fights + "胜" + wins);
        } else if (failCodes.length() > 0) {
            m.skip("挑战失败码" + failCodes.toString().trim());
            m.subSkip("fight", "失败码" + failCodes.toString().trim());
        } else {
            // 兜底：不产出空 Multi（空聚合 = SKIPPED 无文案，等价"从来没执行过"）。
            m.skip("本轮未发起挑战（对手/次数不可用）");
            m.subSkip("fight", "本轮未挑战");
        }
    }

    /** 竞技场是否有可出战英雄（缺 notify_hero_init / 布阵数据时整轮静默的最直接原因）。 */
    static boolean arenaHasFightableHeroes(List<Long> heroes) {
        return heroes != null && !heroes.isEmpty();
    }

    /**
     * 拉取对手失败的可读原因（网页版 DailyArenaService 同族码口径）：
     * 2901/2902=次数用完 → 等次日；2920/2921=对手失效 → 下一轮刷新；2903=请求过频 → 退避一拍。
     * 其余归"未解锁/不可用"，把码写进状态行便于排查（旧实现一律"未解锁"）。
     */
    static String arenaOpponentSkipNote(int code) {
        if (code == 2901 || code == 2902) return "挑战次数已用完(码" + code + ")";
        if (code == 2920 || code == 2921) return "对手数据已失效(码" + code + ")·下一轮刷新";
        if (code == 2903) return "请求过于频繁(码2903)·待下一轮";
        return "竞技场未解锁/不可用(码" + code + ")";
    }

    /**
     * 每日奖励领取（网页版 claimDailyRewardIfDue，DailyArenaService.java:774-809）：
     * 结算窗 23:10–24:00 内、次数达标（或计数未知时由服务端裁决）才请求；
     * 2926=已领、2925=次数未达标 → 记 settled（网页同款），当日不再重试；
     * 其它失败 → SKIP“稍后重试”，5 分钟后下一轮（30s 节奏不刷 ERROR）。
     */
    private static void arenaDailyReward(TaskContext tc, Exec.Multi m, ArenaDay day,
                                         boolean onDaily, boolean inWindow) throws Exception {
        if (!onDaily) {
            m.skip("子开关「每日奖励」已关闭");
            m.subSkip("daily", "子开关已关闭");
            return;
        }
        if (day.rewardSettled) {
            m.skip(day.rewardClaimed ? "每日奖励今日已领" : "每日奖励今日已处理·待次日重新统计");
            m.subSkip("daily", day.rewardClaimed ? "已领" : "已处理");
            return;
        }
        long now = System.currentTimeMillis();
        if (!inWindow) {
            String hint = day.knownCompleted() >= ARENA_DAILY_REWARD_CHALLENGES
                    || day.fightsSent >= ARENA_DAILY_REWARD_CHALLENGES
                    ? "·挑战已完成，到点自动领取" : "";
            m.skip("每日奖励待结算窗(23:10-24:00)" + hint);
            m.subSkip("daily", "待结算窗");
            return;
        }
        if (!arenaRewardRetryDue(day.lastRewardAttemptAt, now)) {
            m.skip("每日奖励领取重试冷却中(5分钟)");
            m.subSkip("daily", "重试冷却");
            return;
        }
        int completed = day.knownCompleted();
        if (!arenaRewardAttemptAllowed(completed, day.fightsSent)) {
            // 计数明确不足且无“本地已发场次 > 服务端计数”的兜底证据：
            // 网页版 dailyRewardClaimPending 同口径不请求；待次日重新统计。
            m.skip("挑战完成" + completed + "/5未达标·待次日重新统计");
            m.subSkip("daily", "次数不足");
            return;
        }
        GameMessage daily = tc.engine.request("req_arena_get_daily_reward", null);
        int dc = Exec.codeOf(daily);
        day.lastRewardAttemptAt = now;
        day.rewardRetries++;
        if (Exec.ok(daily)) {
            day.rewardClaimed = true;
            day.rewardSettled = true;
            m.claimed("每日奖励");
            m.subOk("daily", "每日奖励已领");
        } else if (dc == 2926) {
            day.rewardClaimed = true;
            day.rewardSettled = true;
            m.skip("每日奖励已领(2926)");
            m.subSkip("daily", "已领");
        } else if (dc == 2925) {
            day.rewardSettled = true;
            m.skip("每日挑战次数未达标(2925)·待次日重新统计");
            m.subSkip("daily", "次数未达标");
        } else {
            m.skip("每日奖励领取未完成(码" + dc + ")，5分钟后重试");
            m.subSkip("daily", "码" + dc);
        }
    }

    // ---------- A2 竞技场买次数（默认关，显式预算，余额未知不买） ----------
    //
    // 网页版：00:00-20:00 预购（DailyArenaService.PRE_PURCHASE_WINDOW_START/END:78-79，
    // prePurchase:421-467）→ req_arena_buy_challenge_times{buy_times:remaining}:439；
    // 非定时模式次数用完再买 1（:538-562）。我方默认关（零花钻），只有显式参数
    // arena.buy_times=true 且 max_buy_times/max_diamond>0 才买；钻石余额未知不买；
    // 单价未知时最多探 1 次并把当日预算视为耗尽（防无预算买钻）。

    static final int ARENA_BUY_WINDOW_END_MINUTE = 20 * 60;   // 网页版 PRE_PURCHASE_WINDOW_END=20:00
    static final long ARENA_DIAMOND_RESOURCE_ID = 101L;
    static final String ARENA_DIAMOND_LEDGER_PREFIX = "srv:arena:diamond:";
    static final String ARENA_BUY_SUB_ID = "buy_times";

    /** 买次数窗口：北京时间 00:00-20:00（网页版 PRE_PURCHASE_WINDOW_START/END）。 */
    static boolean arenaBuyWindowOpen(long epochMillis) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.setTimeInMillis(epochMillis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) < ARENA_BUY_WINDOW_END_MINUTE;
    }

    /** 当日总挑战上限 = 每日奖励目标 5 + 已购买次数（网页版打到 totalRemaining=0）。 */
    static int arenaDailyFightCap(int boughtTimes) {
        return ARENA_DAILY_REWARD_CHALLENGES + Math.max(0, boughtTimes);
    }

    /**
     * 竞技场独占窗剩余场次（批次 B 纯函数，与 {@link #arenaFightBudget} 同口径）：
     * 自动挑战关闭/结算窗/服务端判次数用完 → 0（释放独占窗）；否则 cap - max(本地已发, 服务端已完成)。
     */
    static long arenaGateRemaining(ArenaDay day, boolean onFight, boolean inWindow) {
        if (day == null || !onFight || inWindow || day.exhausted) return 0L;
        int cap = arenaDailyFightCap(day.boughtTimes);
        int used = Math.max(Math.max(0, day.fightsSent), Math.max(0, day.knownCompleted()));
        return Math.max(0L, (long) cap - used);
    }

    /**
     * 买次数预算门控（纯函数；返回本轮请求次数，0=不买）：
     * <ul>
     *   <li>默认关 / 未显式给 max_buy_times、max_diamond → 0；</li>
     *   <li>钻石余额未知（null/负数）→ 0（“余额未知不买”）；</li>
     *   <li>今日已买次数达上限 / 钻石预算已耗尽 → 0；</li>
     *   <li>单价已知 → 按预算与余额封顶；单价未知 → 最多探 1 次。</li>
     * </ul>
     */
    static int arenaBuyCount(boolean enabled, int maxBuyTimes, long maxDiamond,
                             int boughtTimes, Long diamondBalance, long spentDiamonds, long price) {
        if (!enabled || maxBuyTimes <= 0 || maxDiamond <= 0L) return 0;
        int remainCount = maxBuyTimes - Math.max(0, boughtTimes);
        if (remainCount <= 0) return 0;
        long remainBudget = maxDiamond - Math.max(0L, spentDiamonds);
        if (remainBudget <= 0L) return 0;
        if (diamondBalance == null || diamondBalance < 0L) return 0;
        int count;
        if (price > 0L) {
            count = (int) Math.min((long) remainCount, remainBudget / price);
            count = (int) Math.min((long) count, diamondBalance / price);
        } else {
            count = Math.min(remainCount, 1);
        }
        return Math.max(0, count);
    }

    /** 买次数响应里的单次价格（防御式）：cost/diamond_cost/price/consume/diamond，或 {cost:{diamond:n}}。 */
    static long arenaBuyCostOf(Map<String, Object> content) {
        if (content == null) return 0L;
        for (String k : new String[]{"cost", "diamond_cost", "price", "consume", "diamond"}) {
            Object v = content.get(k);
            if (v instanceof Map) {
                Map<String, Object> m = Exec.mapOf(v);
                if (m == null) continue;
                long n = Exec.longOf(m.get("diamond"));
                if (n <= 0L) n = Exec.longOf(m.get("count"));
                if (n > 0L) return n;
            } else if (v != null && !(v instanceof Map)) {
                long n = Exec.longOf(v);
                if (n > 0L) return n;
            }
        }
        return 0L;
    }

    /** 资源推送里的钻石数（id=101）：兼容 list / map / notifyData 解包后的 "101.0" 键形状。 */
    static Long arenaDiamondOf(Map<String, Object> content) {
        if (content == null) return null;
        Long direct = arenaDiamondIn(content.get("data"));
        if (direct != null) return direct;
        return arenaDiamondIn(content);
    }

    private static Long arenaDiamondIn(Object raw) {
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            if (arenaResourceId(map.get("id")) == ARENA_DIAMOND_RESOURCE_ID) {
                Object count = map.get("count");
                if (count != null) return Exec.longOf(count);
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (arenaResourceId(e.getKey()) != ARENA_DIAMOND_RESOURCE_ID) continue;
                if (e.getValue() instanceof Map) {
                    Map<String, Object> item = Exec.mapOf(e.getValue());
                    Object count = item == null ? null : item.get("count");
                    if (count != null) return Exec.longOf(count);
                } else if (e.getValue() instanceof Number) {
                    return ((Number) e.getValue()).longValue();
                }
            }
            return null;
        }
        for (Map<String, Object> item : Exec.mapsOf(raw)) {
            if (arenaResourceId(item.get("id")) != ARENA_DIAMOND_RESOURCE_ID) continue;
            Object count = item.get("count");
            if (count != null) return Exec.longOf(count);
        }
        return null;
    }

    private static long arenaResourceId(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static boolean arenaResourceDeltaDelete(Map<String, Object> content) {
        return content != null && "del".equals(String.valueOf(content.get("type")));
    }

    static String arenaDiamondKey(TaskContext tc) {
        return ARENA_DIAMOND_LEDGER_PREFIX + tc.fpid + ":" + tc.sid;
    }

    /**
     * 钻石余额（跨轮台账，形状同网页版 DailyArenaState:305-324）：
     * notify_resource_init 提供一次绝对值；notify_resource_update 按 type(add/del) 应用增量，
     * 用内容哈希去重避免同一推送被重复累加。返回 null=未知（此时不买）。
     */
    static Long arenaDiamondBalance(TaskContext tc) {
        try {
            JSONObject st;
            String raw = tc.store.getString(arenaDiamondKey(tc));
            st = raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            long balance = st.optLong("balance", -1L);
            boolean abs = st.optBoolean("abs", false);
            Long init = arenaDiamondOf(tc.engine.notifyData("notify_resource_init"));
            if (init != null && !abs) {
                balance = init;
                abs = true;
            }
            Map<String, Object> update = tc.engine.notifyContent("notify_resource_update");
            if (update != null) {
                Long delta = arenaDiamondOf(update);
                if (delta != null) {
                    String hash = String.valueOf(update.get("type")) + "|"
                            + String.valueOf(update.get("data")) + "|" + delta;
                    if (!hash.equals(st.optString("hash", ""))) {
                        if (balance >= 0L) {
                            balance = arenaResourceDeltaDelete(update)
                                    ? Math.max(0L, balance - delta) : balance + delta;
                        }
                        st.put("hash", hash);
                    }
                }
            }
            if (balance >= 0L) st.put("balance", balance);
            st.put("abs", abs);
            tc.store.putString(arenaDiamondKey(tc), st.toString());
            return balance >= 0L ? balance : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * A2 执行：默认关；显式 buy_times=true + max_buy_times/max_diamond 才请求
     * {@code req_arena_buy_challenge_times{buy_times:n}}；成功累计当日台账，
     * 单价未知时只探 1 次并把预算视为耗尽（零默认花钻）。
     */
    private static void arenaBuyTimes(TaskContext tc, Exec.Multi m, ArenaDay day, JSONObject ap,
                                      long nowMillis, boolean settlementWindow) throws Exception {
        boolean enabled = ap != null && ap.optBoolean("buy_times", false);
        if (!enabled) {
            m.subSkip(ARENA_BUY_SUB_ID, "买次数默认关");
            return;
        }
        if (settlementWindow) {
            m.subSkip(ARENA_BUY_SUB_ID, "结算窗不买");
            return;
        }
        if (!arenaBuyWindowOpen(nowMillis)) {
            m.subSkip(ARENA_BUY_SUB_ID, "买次数窗口00:00-20:00外");
            return;
        }
        int maxBuyTimes = ap.optInt("max_buy_times", 0);
        long maxDiamond = ap.optLong("max_diamond", 0L);
        long explicitPrice = Math.max(0L, ap.optLong("buy_cost", 0L));
        long price = explicitPrice > 0L ? explicitPrice : day.buyPrice;
        Long balance = arenaDiamondBalance(tc);
        int count = arenaBuyCount(true, maxBuyTimes, maxDiamond, day.boughtTimes,
                balance, day.diamondSpent, price);
        if (count <= 0) {
            String why;
            if (maxBuyTimes <= 0 || maxDiamond <= 0L) {
                why = "缺显式 max_buy_times/max_diamond 预算，不买";
            } else if (balance == null) {
                why = "钻石余额未知，不买";
            } else if (day.boughtTimes >= maxBuyTimes) {
                why = "今日买次数已达上限";
            } else if (day.diamondSpent >= maxDiamond) {
                why = "钻石预算已耗尽";
            } else {
                why = "余额不足/暂不满足购买条件";
            }
            tc.saveModText("arena", ARENA_BUY_SUB_ID, why);
            m.subSkip(ARENA_BUY_SUB_ID, why);
            return;
        }
        GameMessage rsp = tc.engine.request("req_arena_buy_challenge_times",
                Exec.args("buy_times", (long) count));
        int code = Exec.codeOf(rsp);
        if (!Exec.ok(rsp)) {
            String why;
            if (code == 2902 || code == 2924) {
                why = "服务端暂不允许买次数(码" + code + ")";
            } else {
                why = "买次数码" + code;
            }
            tc.saveModText("arena", ARENA_BUY_SUB_ID, why);
            m.subSkip(ARENA_BUY_SUB_ID, why);
            return;
        }
        day.boughtTimes += count;
        day.mergeBase(arenaBaseOf(rsp.getContent()), nowMillis);
        long cost = arenaBuyCostOf(rsp.getContent());
        if (cost <= 0L) {
            Long after = arenaDiamondBalance(tc);
            if (balance != null && after != null && balance > after) {
                cost = Math.max(1L, (balance - after) / Math.max(1, count));
            }
        }
        if (cost <= 0L) cost = explicitPrice;
        if (cost > 0L) {
            day.buyPrice = cost;
            day.diamondSpent += cost * count;
            tc.saveModText("arena", ARENA_BUY_SUB_ID,
                    "今日买次数 " + day.boughtTimes + "/" + maxBuyTimes
                            + " · 约 " + (cost * count) + " 钻");
        } else {
            // 价格未确认：今日预算视为耗尽，避免无预算继续买钻（宁可少买，不可超支）
            day.buyPrice = 0L;
            day.diamondSpent = maxDiamond;
            tc.saveModText("arena", ARENA_BUY_SUB_ID,
                    "买次数×" + count + " · 价格未确认，今日停止继续买");
        }
        m.claimed("竞技场买次数×" + count);
        m.subOk(ARENA_BUY_SUB_ID, "买次数×" + count);
    }

    // ---------- 竞技场纯函数/当日台账（A1，单测见 IntelArenaFixTest） ----------

    /** 结算窗判定：北京时间 >= 23:10（窗口到 00:00；与网页版
     *  settlementStartsAt = 次日 0 点 - 3000s 一致，DailyArenaService.java:761-768）。 */
    static boolean arenaInDailyRewardWindow(long epochMillis) {
        Calendar c = Calendar.getInstance(Beijing.TZ);
        c.setTimeInMillis(epochMillis);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) >= ARENA_REWARD_WINDOW_START_MINUTE;
    }

    /** 今日已挑战场次：1:1 网页版 challengesCompletedToday = free_times_use + max(0,buy_times-left_times)
     *  （DailyArenaState.java:179-186）。 */
    static int arenaChallengesCompleted(int freeUsed, int buyTimes, int leftTimes) {
        return Math.max(0, freeUsed) + Math.max(0, Math.max(0, buyTimes) - Math.max(0, leftTimes));
    }

    /** 领取重试是否到期（网页版 5 分钟重试，DailyArenaService.java:800-807）。 */
    static boolean arenaRewardRetryDue(long lastAttemptAtMillis, long nowMillis) {
        return lastAttemptAtMillis <= 0L || nowMillis - lastAttemptAtMillis >= ARENA_REWARD_RETRY_MILLIS;
    }

    /**
     * 结算窗内是否允许请求 req_arena_get_daily_reward（免费请求，由服务端最终裁决）：
     * <ul>
     *   <li>服务端计数已达标 → 允许；</li>
     *   <li>服务端计数未知（base 未同步）→ 允许（“拿不到真实计数时保守在窗口内重试领取”）；</li>
     *   <li>服务端计数明确不足，但本地已发场次更多（fight 响应未带 base / 只有登录旧快照）→
     *       仍允许一次，避免因计数陈旧漏掉真实达标的奖励；</li>
     *   <li>其余（明确不足且无兜底证据）→ 不请求，写“次数不足·待次日”。</li>
     * </ul>
     */
    static boolean arenaRewardAttemptAllowed(int challengesCompleted, int sentToday) {
        if (challengesCompleted < 0) return true;
        if (challengesCompleted >= ARENA_DAILY_REWARD_CHALLENGES) return true;
        return sentToday >= ARENA_DAILY_REWARD_CHALLENGES && sentToday > challengesCompleted;
    }

    /** 本轮可挑战场数：服务端计数权威（-1=未知时不判超额，改用本地已发出场次封顶 5），
     *  本轮不买次数，故每日上限固定 5（网页版 DAILY_REWARD_CHALLENGE_TARGET）。 */
    static int arenaFightBudget(int challengesCompleted, int localSentToday, int maxFights) {
        int sent = Math.max(0, localSentToday);
        int remaining;
        if (challengesCompleted < 0) {
            remaining = ARENA_DAILY_REWARD_CHALLENGES - sent;
        } else {
            remaining = ARENA_DAILY_REWARD_CHALLENGES - Math.max(0, challengesCompleted);
            // base 未刷新时本地已发场次也要计入，防止同一轮/下一轮重复打空
            remaining = Math.min(remaining, ARENA_DAILY_REWARD_CHALLENGES - sent);
        }
        return Math.max(0, Math.min(Math.max(1, maxFights), remaining));
    }

    /** 战斗胜负：1:1 网页版 response.report.win（DailyArenaService.java:599-601）。
     *  返回 1=胜 / 0=负 / -1=未知（防各种 Boolean/Number/String 形状）。 */
    static int arenaWinOf(Map<String, Object> content) {
        if (content == null) return -1;
        Map<String, Object> report = Exec.mapOf(content.get("report"));
        if (report == null) report = Exec.mapOf(content.get("battle_report"));
        Object win = report == null ? null : report.get("win");
        if (win instanceof Boolean) return (Boolean) win ? 1 : 0;
        if (win instanceof Number) return ((Number) win).longValue() != 0L ? 1 : 0;
        if (win != null) {
            String s = String.valueOf(win).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return 1;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return 0;
        }
        return -1;
    }

    /** 竞技场服务端 base（notify_arena_init.base / req_arena_fight 响应 base，DailyArenaState.java:237-244）。 */
    static final class ArenaBase {
        final int freeUsed;
        final int buyTimes;
        final int leftTimes;
        final boolean dailyRewardClaimed;
        final long lastResetTs;

        ArenaBase(int freeUsed, int buyTimes, int leftTimes,
                  boolean dailyRewardClaimed, long lastResetTs) {
            this.freeUsed = Math.max(0, freeUsed);
            this.buyTimes = Math.max(0, buyTimes);
            this.leftTimes = Math.max(0, leftTimes);
            this.dailyRewardClaimed = dailyRewardClaimed;
            this.lastResetTs = lastResetTs;
        }
    }

    /** 防御式解析 base：{base:{...}} / notifyData 已解包 / 直接就是 base 本体 / {data:{base:{...}}}。 */
    static ArenaBase arenaBaseOf(Map<String, Object> content) {
        if (content == null) return null;
        Map<String, Object> base = Exec.mapOf(content.get("base"));
        if (base == null && arenaHasCounts(content)) base = content;
        if (base == null) {
            Map<String, Object> data = Exec.mapOf(content.get("data"));
            if (data != null) {
                base = Exec.mapOf(data.get("base"));
                if (base == null && arenaHasCounts(data)) base = data;
            }
        }
        if (base == null) return null;
        long freeUsed = arenaNum(base, "free_times_use");
        long buyTimes = arenaNum(base, "buy_times");
        long leftTimes = arenaNum(base, "left_times");
        if (freeUsed < 0 && buyTimes < 0 && leftTimes < 0) return null;   // 无任何计数 = 未同步
        Object dr = base.get("daily_reward");
        boolean claimed = dr instanceof Boolean ? (Boolean) dr
                : dr != null && ("true".equalsIgnoreCase(String.valueOf(dr))
                || "1".equals(String.valueOf(dr)));
        return new ArenaBase((int) Math.max(0, freeUsed), (int) Math.max(0, buyTimes),
                (int) Math.max(0, leftTimes), claimed, arenaNum(base, "last_reset_ts"));
    }

    private static boolean arenaHasCounts(Map<String, Object> m) {
        return m != null && (m.containsKey("free_times_use") || m.containsKey("left_times")
                || m.containsKey("buy_times") || m.containsKey("daily_reward"));
    }

    private static long arenaNum(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : -1L;
    }

    /** base.last_reset_ts 是否属于“今天”（北京时间）；毫秒时间戳兼容。 */
    static boolean arenaBaseFreshForDay(long lastResetTs, long nowMillis) {
        long ts = lastResetTs;
        if (ts > 100_000_000_000L) ts = ts / 1000L;
        return arenaBeijingDay(ts * 1000L).equals(arenaBeijingDay(nowMillis));
    }

    static String arenaBeijingDay(long epochMillis) {
        return Beijing.fmt("yyyy-MM-dd").format(new java.util.Date(epochMillis));
    }

    /**
     * 竞技场当日台账（srv:arena:day:&lt;fpid&gt;:&lt;sid&gt;）：挑战场次/胜负/服务端 base/领奖状态。
     * 网页版由 DailyArenaState + checkpoint 维护（DailyArenaState.java:160-186、:1007），
     * 我方无常驻状态机 → 按北京日期落盘，跨天自动重置。
     */
    static final class ArenaDay {
        String day = "";
        int fightsSent;
        int wins;
        int losses;
        int unknownResult;
        int freeUsed;
        int buyTimes;
        int leftTimes;
        boolean baseKnown;
        /** 服务端明确判"挑战次数已用完"（2901/2902）：独占窗立即释放，不再阻塞其它任务。 */
        boolean exhausted;
        long lastResetTs;
        boolean rewardSettled;
        boolean rewardClaimed;
        long lastRewardAttemptAt;
        int rewardRetries;
        /** A2 买次数：今日已买次数/已花钻石/单次价格（0=价格未确认，当天停止继续买）。 */
        int boughtTimes;
        long diamondSpent;
        long buyPrice;

        static ArenaDay load(TaskContext tc, long nowMillis) {
            String today = arenaBeijingDay(nowMillis);
            try {
                String raw = tc.store.getString(ARENA_DAY_KEY_PREFIX + tc.fpid + ":" + tc.sid);
                if (raw != null && !raw.isEmpty()) {
                    JSONObject o = new JSONObject(raw);
                    if (today.equals(o.optString("day", ""))) {
                        ArenaDay d = new ArenaDay();
                        d.day = today;
                        d.fightsSent = o.optInt("fights_sent", 0);
                        d.wins = o.optInt("wins", 0);
                        d.losses = o.optInt("losses", 0);
                        d.unknownResult = o.optInt("unknown_result", 0);
                        d.freeUsed = o.optInt("free_used", 0);
                        d.buyTimes = o.optInt("buy_times", 0);
                        d.leftTimes = o.optInt("left_times", 0);
                        d.baseKnown = o.optBoolean("base_known", false);
                        d.exhausted = o.optBoolean("exhausted", false);
                        d.lastResetTs = o.optLong("last_reset_ts", 0L);
                        d.rewardSettled = o.optBoolean("reward_settled", false);
                        d.rewardClaimed = o.optBoolean("reward_claimed", false);
                        d.lastRewardAttemptAt = o.optLong("reward_last_attempt", 0L);
                        d.rewardRetries = o.optInt("reward_retries", 0);
                        d.boughtTimes = o.optInt("bought_times", 0);
                        d.diamondSpent = o.optLong("diamond_spent", 0L);
                        d.buyPrice = o.optLong("buy_price", 0L);
                        return d;
                    }
                }
            } catch (Throwable ignored) {
            }
            ArenaDay d = new ArenaDay();
            d.day = today;
            return d;
        }

        void save(TaskContext tc) {
            try {
                JSONObject o = new JSONObject();
                o.put("day", day);
                o.put("fights_sent", fightsSent);
                o.put("wins", wins);
                o.put("losses", losses);
                o.put("unknown_result", unknownResult);
                o.put("free_used", freeUsed);
                o.put("buy_times", buyTimes);
                o.put("left_times", leftTimes);
                o.put("base_known", baseKnown);
                o.put("exhausted", exhausted);
                o.put("last_reset_ts", lastResetTs);
                o.put("reward_settled", rewardSettled);
                o.put("reward_claimed", rewardClaimed);
                o.put("reward_last_attempt", lastRewardAttemptAt);
                o.put("reward_retries", rewardRetries);
                o.put("bought_times", boughtTimes);
                o.put("diamond_spent", diamondSpent);
                o.put("buy_price", buyPrice);
                tc.store.putString(ARENA_DAY_KEY_PREFIX + tc.fpid + ":" + tc.sid, o.toString());
            } catch (Throwable ignored) {
            }
        }

        /** 合并服务端 base：只接受今天（或 last_reset_ts 缺失）的快照，跨天旧快照丢弃。 */
        void mergeBase(ArenaBase base, long nowMillis) {
            if (base == null) return;
            if (base.lastResetTs > 0L && !arenaBaseFreshForDay(base.lastResetTs, nowMillis)) return;
            freeUsed = base.freeUsed;
            buyTimes = base.buyTimes;
            leftTimes = base.leftTimes;
            // 服务端 buy_times 是当日累计购买次数：重连/台账丢失后用它兜底，避免重复买钻
            boughtTimes = Math.max(boughtTimes, base.buyTimes);
            if (base.lastResetTs > 0L) lastResetTs = base.lastResetTs;
            baseKnown = true;
            if (base.dailyRewardClaimed) {
                rewardClaimed = true;
                rewardSettled = true;
            }
        }

        /** 合并登录推送的 notify_arena_init 快照：当日已有更权威的 fight 响应 base 时不回灌，
         *  防止每轮把旧登录快照覆盖回今日已打计数。 */
        void mergeInitBase(ArenaBase base, long nowMillis) {
            if (baseKnown) return;
            mergeBase(base, nowMillis);
        }

        /** 服务端明确完成场次；-1=未知（基础数据未同步 → 领奖交给服务端裁决）。 */
        int knownCompleted() {
            return baseKnown ? arenaChallengesCompleted(freeUsed, buyTimes, leftTimes) : -1;
        }
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

    public static boolean sessionLost(Exception e) {
        return ServerTasks.classifyException(e, "x").category == TaskOutcome.Category.SESSION_CLOSED;
    }
}
