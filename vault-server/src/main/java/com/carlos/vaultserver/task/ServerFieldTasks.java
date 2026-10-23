package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 野外类 + 城建类任务（App 端 daily/FieldTasks + TownDevTasks 逐行移植，改动须两侧同步）。
 */
public final class ServerFieldTasks {

    private ServerFieldTasks() {
    }

    // ---------- 野外采集 ----------

    public static TaskOutcome gather(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            JSONObject p = tc.params("gather");
            int minLevel = Math.max(1, Math.min(6, p.optInt("min_level", 5)));
            int maxQueues = Math.max(1, Math.min(5, p.optInt("max_queues", 3)));
            List<Integer> resList = new ArrayList<>();
            org.json.JSONArray named = p.optJSONArray("resources_named");
            if (named != null && named.length() > 0) {
                // 业务名 → 采集 res_id：肉102/木103/煤104/铁105（1:1 网页版
                // DailyGatherConfig 的 res_id 直传 102-105；旧实现错误地再减 101 成 1-4，
                // req_world_map_search 恒返回 2872 → 生产从未派出过采集队，P0 2026-10-23 修）。
                for (int i = 0; i < named.length(); i++) {
                    String n = named.optString(i, "").trim().toUpperCase();
                    int raw;
                    switch (n) {
                        case "MEAT": raw = 102; break;
                        case "WOOD": raw = 103; break;
                        case "COAL": raw = 104; break;
                        case "IRON": raw = 105; break;
                        default: raw = 0;
                    }
                    if (raw > 0) resList.add(raw);
                }
            }
            if (resList.isEmpty()) {
                org.json.JSONArray resArr = p.optJSONArray("resources");
                if (resArr == null || resArr.length() == 0) {
                    resList.add(102);   // 默认肉
                    resList.add(103);   // 默认木
                } else {
                    for (int i = 0; i < resArr.length(); i++) {
                        int v = resArr.optInt(i);
                        // 兼容历史配置：旧版本 JSON 里可能存的是 1-4 的“资源序号”，
                        // 统一换算成游戏 res_id 102-105（新 UI 只写 resources_named）。
                        if (v >= 1 && v <= 4) v += 101;
                        resList.add(v);
                    }
                }
            }
            int active = countMarches(tc.engine, 4);
            int capacity = Math.min(maxQueues, marchCapacity(tc.engine));
            boolean fullOnly = "full".equals(tc.strategy("gather", "send", "any"));
            int slots = Math.max(0, capacity - active);
            if (slots == 0) {
                m.skip("采集队列已满(" + active + "/" + capacity + ")");
            } else {
                int sent = 0;
                for (int res : resList) {
                    if (sent >= slots) break;
                    for (int level = Math.min(6, minLevel); level >= 1 && sent < slots; level--) {
                        Map<String, Object> pt = searchResource(tc.engine, res, level, fullOnly);
                        if (pt == null) continue;
                        Map<String, Object> formation = formationFor(tc, "gather", 1 + (sent % 3));
                        if (formation == null) {
                            m.skip("无英雄可派");
                            return Exec.result(m, "gather", "野外采集");
                        }
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", 4L, "endpoint", pt, "formation", formation,
                                "extra", new java.util.LinkedHashMap<>()));
                        if (Exec.marchId(march) > 0) {
                            sent++;
                        } else {
                            int code = Exec.codeOf(march);
                            if (code == 2878 || code == 401) {
                                m.skip("派出" + sent + "后受阻(码" + code + ")");
                                return Exec.result(m, "gather", "野外采集");
                            }
                        }
                    }
                }
                if (sent > 0) {
                    m.claimed("派出采集×" + sent);
                    tc.saveModText("gather", "send",
                            "上轮派出 " + sent + " 队（队列 " + active + "/" + capacity + "）");
                } else m.skip("附近未搜到资源点");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "野外采集");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "gather", "野外采集");
    }

    /**
     * 编队：配置了 hero_ids 时按“英雄 id + 动态兵力”构造；否则回退旧 Exec.formation。
     *
     * <p>字段（存于 daily_params 的 fnId 参数）：
     * hero_ids:[id1,id2,id3]、troop_count、full_troops、
     * shield_ratio/spear_ratio/bow_ratio。
     * 采集/打野兽共用同一套，避免两处算法漂移。
     */
    static Map<String, Object> formationFor(TaskContext tc, String fnId, int fallbackSquad) {
        org.json.JSONObject p = tc.params(fnId);
        // App 采集弹窗的“编队”选择：直接使用游戏内第 N 队布阵。
        int squad = p.optInt("squad_index", 0);
        if (squad > 0) return Exec.formation(tc.engine, squad);
        org.json.JSONArray heroArr = p.optJSONArray("hero_ids");
        if (heroArr == null || heroArr.length() == 0) {
            return Exec.formation(tc.engine, fallbackSquad);
        }
        List<Map<String, Object>> heroPairs = new ArrayList<>();
        for (int i = 0; i < heroArr.length(); i++) {
            long hid = heroArr.optLong(i, 0);
            if (hid > 0) heroPairs.add(Exec.kv(hid, 1));
        }
        if (heroPairs.isEmpty()) return Exec.formation(tc.engine, fallbackSquad);

        long[] base = Exec.baseSoldiers(tc.engine); // 盾/矛/弓 tier1 可用量
        long available = base[0] + base[1] + base[2];
        if (available <= 0) return null;
        long requested = p.optLong("troop_count", 0);
        if (requested <= 0) {
            long target = p.optLong("target_amount", 0);
            if (target > 0) {
                double carry = Math.max(1.0, p.optDouble("carry_per_troop", 100)
                        * (1.0 + p.optDouble("hero_carry_bonus", 0.30)));
                requested = (long) Math.ceil(target / carry
                        * (1.0 + p.optDouble("buffer_pct", 5.0) / 100.0));
            }
        }
        long total = p.optBoolean("full_troops", false)
                ? available : Math.min(available, Math.max(1, requested > 0 ? requested : available));
        double sh = Math.max(0, p.optDouble("shield_ratio", 0.40));
        double sp = Math.max(0, p.optDouble("spear_ratio", 0.30));
        double bw = Math.max(0, p.optDouble("bow_ratio", 0.30));
        double sum = sh + sp + bw;
        if (sum <= 0) { sh = 0.4; sp = 0.3; bw = 0.3; sum = 1.0; }
        long wantShield = Math.min(base[0], Math.round(total * sh / sum));
        long wantSpear = Math.min(base[1], Math.round(total * sp / sum));
        long wantBow = Math.min(base[2], Math.max(0, total - wantShield - wantSpear));
        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        long[] want = {wantShield, wantSpear, wantBow};
        // 兵种*10000 + 等级*100；用账号当前最高档，不能写死 10001/20001/30001。
        long[] ids = Exec.bestSoldierIds(Exec.soldiers(tc.engine));
        for (int i = 0; i < 3; i++) {
            if (want[i] > 0) soldierPairs.add(Exec.kv(ids[i], want[i]));
        }
        if (soldierPairs.isEmpty()) return null;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("hero_id", heroPairs);
        f.put("soldier", soldierPairs);
        return f;
    }

    private static Map<String, Object> searchResource(WjdrEngine engine, int resId, int level,
                                                      boolean fullOnly) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", Exec.args(
                "mapobj_type", 2L, "level_min", level, "level_max", level,
                "res_id", resId, "is_full", fullOnly));
        if (!Exec.ok(rsp)) {
            // 2872=本区无空矿，属正常状态，不刷日志。
            int code = Exec.codeOf(rsp);
            if (code != 2872) FileLog.warn("采集搜索失败 res={} lv={} code={}", resId, level, code);
            return null;
        }
        // 协议（req_world_map_search.response）：坐标在单对象 point{x,y}，
        // 旧代码只找 points/data/list，导致每次都判定“附近未搜到资源点”。
        Map<String, Object> point = Exec.mapOf(rsp.getContent().get("point"));
        if (point != null && point.containsKey("x") && point.containsKey("y")) return point;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : Exec.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    /** 当前 type=N 的行军数。协议盘点 2026-10-12 两级实锤（与 App FieldTasks 2026-10-14 版一致）：
     * ① notify_player_march_init 不在 s2c schema（旧实现恒返回 0 → 已派出队列被重复计入空位）；
     * ② schema 解析得 req_world_march_detail_4_player_situation.request{owner,kid}，
     *    空参被服务端拒（code 2 实证）——owner=玩家uid、kid=区号，行军列表在响应 detail 字段。
     *  查询失败按 0 处理（回退旧行为，宁可让服务端拒多发也不漏发）。 */
    private static int countMarches(WjdrEngine engine, int type) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return 0;
            Object code = rsp.getContent().get("code");
            if (code != null && Exec.longOf(code) != 0) return 0;
            Map<String, Object> detail = Exec.mapOf(rsp.getContent().get("detail"));
            int n = 0;
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : Exec.mapsOf(detail == null ? null : detail.get(k))) {
                    if (Exec.longOf(march.get("type")) == type) n++;
                }
                if (n > 0) break;   // 取第一个命中的列表形状
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int marchCapacity(WjdrEngine engine) {
        Map<String, Object> wp = engine.notifyData("notify_world_player_init");
        if (wp == null) return 3;
        Map<String, Object> world = Exec.mapOf(wp.get("world"));
        long cap = world == null ? 0 : Exec.longOf(world.get("capacity"));
        return cap > 0 ? (int) cap : 3;
    }

    // ---------- 情报任务 ----------

    /**
     * 情报体力底仓（1:1 网页版 DailyIntelligenceService.staminaReserve:256-262 的 50 点保留：
     * 网页版由巨兽侧为情报保留 50；我方同样保守保留，避免情报把体力打空影响巨兽/后续任务）。
     */
    static final long INTEL_STAMINA_RESERVE = 50L;

    /** 体力资源 id：notify_recover_resource_init/update 的 data[] 中 id=201 项
     *  （1:1 网页版 BehemothAccountState.recoverUpdate:2645-2653）。 */
    static final long INTEL_STAMINA_RESOURCE_ID = 201L;

    /**
     * 情报派队去重窗：网页版用本地状态机 markFighting(status=3) 与失败 blockEvent 300s 防重复
     * （DailyIntelligenceService.java:723-724,779-800）；我方 15s 节奏无常驻状态机，
     * 用 10 分钟去重窗防止对同一事件重复 req_world_march / start_battle。
     */
    static final long INTEL_DISPATCH_DEDUPE_MILLIS = 600_000L;

    /** 专家情报初始化宽限（I4）：前 3 次且 10 分钟内不判“未同步”（生产推送迟到 ~30s）。 */
    static final int INTEL_EXPERT_FIRST_MAX_ATTEMPTS = 3;

    /** 专家情报初始化宽限时长（I4）：10 分钟。 */
    static final long INTEL_EXPERT_FIRST_GRACE_MILLIS = 600_000L;

    /**
     * 情报任务（P0 修复 2026-10-23，1:1 对齐网页版 DailyIntelligenceState/Service）：
     * <ul>
     *   <li>数据源修正：全量初始化来自 {@code notify_intelligence_info}（登录批实际只有它，
     *       旧实现只读 {@code notify_intelligence_events} → 生产每天恒“暂无情报事件”），
     *       events 增量合并 + events_del 删除；</li>
     *   <li>类型判定改为网页版口径：由 {@code event_id}(eventConfigId) 区间解析
     *       1 怪物 / 2 营救 / 3 英雄之旅(PVE) / 11 活动巨兽 / 100 冒险巨兽，
     *       不再把消息里的 type 字段当事件类型（旧映射 3→302 与网页版相反）；</li>
     *   <li>status==2（已可领）→ {@code req_intelligence_receive}；status==1 才派队；
     *       派队后不再立刻领奖（等战斗结果推送把 status 变 2 后下一轮再领）。</li>
     * </ul>
     */
    public static TaskOutcome intel(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            Map<Long, Map<String, Object>> events = new LinkedHashMap<>();
            mergeIntelEvents(tc.engine, "notify_intelligence_info", events);    // 全量初始化
            mergeIntelEvents(tc.engine, "notify_intelligence_events", events);  // 增量
            Map<String, Object> del = notifyContentOf(tc.engine, "notify_intelligence_events_del");
            if (del != null) {
                for (Object rawId : rawValues(del.get("ids"))) {
                    long id = Exec.longOf(rawId);
                    if (id > 0) events.remove(id);
                }
            }
            if (events.isEmpty()) {
                m.skip("暂无情报事件");
            } else {
                // I2 门控（P0 2026-10-23，1:1 网页版 DailyIntelligenceService）：
                // ① 普通队列数 = min(世界容量, 配置 queueCount，默认 1)（:256-262,598-606 的
                //    effectiveQueueCount = min(config.queueCount, playerQueueLimit(world))）；
                // ② 已占用情报行军（type=301/302）不重复派（:919-928 activeIntelligenceMarchCount）；
                // ③ 体力门：普通事件 10/12 点、保留 50 点底仓（:598-606 staminaCost、:256-262
                //    staminaReserve）；体力取不到（notify_recover_resource_init 未到）时只做队列门。
                // 15s 节奏天然重试：本轮条件不足只写 SKIP“待下一次”，不循环重试、不刷 ERROR。
                JSONObject ip = tc.params("intel");
                long now = System.currentTimeMillis();
                boolean hasDispatchable = false;
                for (Map<String, Object> e : events.values()) {
                    long id = Exec.longOf(e.get("id"));
                    if (id <= 0) continue;
                    long status = e.containsKey("status") ? Exec.longOf(e.get("status")) : 1L;
                    if (status != 1) continue;
                    int t = intelEventType(intelEventConfigId(e));
                    if (t == 0 || intelDispatchBlocked(tc, id, now)) continue;
                    hasDispatchable = true;
                    break;
                }
                int allowedSlots = 0, activeIntelMarches = 0, freeSlots = 0;
                boolean staminaKnown = false;
                long usableStamina = Long.MAX_VALUE;
                if (hasDispatchable) {
                    // 页面/请求只在有可派事件时取，避免纯领奖轮次多发队列查询。
                    allowedSlots = intelQueueSlots(intelQueueCount(ip), marchCapacity(tc.engine));
                    activeIntelMarches = countIntelMarches(tc.engine);
                    freeSlots = Math.max(0, allowedSlots - activeIntelMarches);
                    Long stamina = intelStamina(tc.engine);
                    staminaKnown = stamina != null;
                    if (staminaKnown) usableStamina = Math.max(0L, stamina - INTEL_STAMINA_RESERVE);
                }
                int handled = 0, claimed = 0, dispatched = 0, waiting = 0, deduped = 0;
                StringBuilder waits = new StringBuilder();
                for (Map<String, Object> e : events.values()) {
                    long id = Exec.longOf(e.get("id"));
                    if (id <= 0) continue;
                    long status = e.containsKey("status") ? Exec.longOf(e.get("status")) : 1L;
                    long configId = intelEventConfigId(e);
                    int type = intelEventType(configId);
                    if (status == 2) {
                        // 已可领：领取即视为移除（2501=游戏端已处理）；按天台账去重，
                        // 避免 notify 未刷新时 15s 节奏重复 req_intelligence_receive。
                        if (ClaimLedger.isClaimed(tc, "intel_receive", String.valueOf(id))) {
                            deduped++;
                            continue;
                        }
                        GameMessage rc = tc.engine.request("req_intelligence_receive",
                                Exec.args("id", id));
                        int rcCode = Exec.codeOf(rc);
                        if (Exec.ok(rc) || rcCode == 2501) {
                            handled++;
                            claimed++;
                            ClaimLedger.mark(tc, "intel_receive", String.valueOf(id));
                        } else {
                            waiting++;
                            appendWait(waits, "领奖码" + rcCode);
                        }
                        continue;
                    }
                    if (status != 1) continue;
                    if (type == 0) continue;
                    if (intelDispatchBlocked(tc, id, now)) {
                        // 已派出/已开战（或 10 分钟内刚失败）：等推送把 status 变 2 或去重窗过期。
                        deduped++;
                        continue;
                    }
                    boolean worldMarch = intelIsWorldMarch(type);
                    long staminaCost = intelStaminaCost(type);
                    if (worldMarch && freeSlots <= 0) {
                        waiting++;      // 队列满：留待下一次节奏，不写失败
                        continue;
                    }
                    if (staminaKnown && !intelStaminaAllows(usableStamina, staminaCost)) {
                        waiting++;      // 体力不足：留待下一次节奏，不写失败
                        continue;
                    }
                    if (type == 3) {
                        // 英雄之旅（PVE，不占普通队列、仍耗体力）：start_battle → 结算 → end_battle；
                        // 领奖等 status=2。失败只写 SKIP 文案（15s 节奏再试），不写 ERROR。
                        List<Long> heroes = ServerTownTasks.squadHeroes(tc.engine);
                        List<Object> fightHeros = new ArrayList<>(heroes);
                        GameMessage st = tc.engine.request("req_intelligence_start_battle",
                                Exec.args("id", id, "fight_heros_data",
                                        Exec.args("source", 1, "fight_heros", fightHeros)));
                        if (Exec.ok(st)) {
                            Exec.sleep(3000);
                            GameMessage en = tc.engine.request("req_intelligence_end_battle",
                                    Exec.args("id", id));
                            if (Exec.ok(en)) {
                                handled++;
                                markIntelDispatched(tc, id, now);
                                if (staminaKnown) usableStamina = Math.max(0L, usableStamina - staminaCost);
                            } else {
                                waiting++;
                                appendWait(waits, "英雄之旅结算码" + Exec.codeOf(en));
                            }
                        } else {
                            waiting++;
                            appendWait(waits, "英雄之旅码" + Exec.codeOf(st));
                            if (Exec.codeOf(st) == 51) break;   // 体力不足：本轮停止，等恢复（避免连续失败刷请求）
                        }
                    } else if (worldMarch) {
                        long marchType = type == 2 ? 301L : 302L;
                        Map<String, Object> formation = Exec.formation(tc.engine, 1);
                        if (formation == null) {
                            waiting++;
                            appendWait(waits, "无英雄可派");
                            continue;
                        }
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", marchType,
                                "endpoint", Exec.point(Exec.longOf(e.get("x")), Exec.longOf(e.get("y"))),
                                "formation", formation,
                                "extra", Exec.args("event_id", id)));
                        if (Exec.marchId(march) > 0) {
                            handled++;
                            dispatched++;
                            freeSlots--;
                            markIntelDispatched(tc, id, now);
                            if (staminaKnown) usableStamina = Math.max(0L, usableStamina - staminaCost);
                        } else {
                            int mc = Exec.codeOf(march);
                            waiting++;
                            appendWait(waits, "派队码" + mc);
                            if (mc == 51) break;   // 体力不足：本轮停止，等恢复（避免连续失败刷请求）
                        }
                    }
                }
                if (handled > 0) {
                    m.claimed("处理情报×" + handled
                            + (claimed > 0 ? "（领奖" + claimed + "）" : "")
                            + (dispatched > 0 ? "（派队" + dispatched + "）" : ""));
                } else if (hasDispatchable && freeSlots <= 0) {
                    m.skip("情报队列已满(" + activeIntelMarches + "/" + allowedSlots + ")，待下一次");
                } else if (hasDispatchable && staminaKnown && usableStamina < intelStaminaCost(1)) {
                    m.skip("情报体力不足(可用" + usableStamina + "·保留" + INTEL_STAMINA_RESERVE + ")，待下一次");
                } else if (waiting > 0) {
                    m.skip("情报待重试：" + waits.toString().trim());
                } else if (deduped > 0) {
                    m.skip("情报行军/领奖进行中，待下一次");
                } else {
                    m.skip("无可派情报");
                }
            }

            // 专家额外情报页：notify_expert_quests.finishs 中 isgetaward=false 的项，
            // 逐个 req_get_expert_quest_award{quest_id}；按天台账去重，避免重复领取/弹窗。
            if (tc.subOn("intel", "expert_extra", true)) {
                Map<String, Object> eq = tc.engine.notifyData("notify_expert_quests");
                if (eq == null) {
                    // I4 初始化竞态（P0 2026-10-23）：生产实测整轮开始时（16:58:45 建会话即开跑）
                    // notify_expert_quests 还没到，16:59:16 才推 → 首读为空不再直接判“未同步”。
                    // 前 3 次 / 10 分钟内保持上轮文案，只写子项“同步中”；超过宽限才写“未同步（待重试）”。
                    long nowMs = System.currentTimeMillis();
                    JSONObject first = intelExpertFirstState(tc);
                    long firstTs = first.optLong("ts", 0L);
                    int attempts = first.optInt("attempts", 0) + 1;
                    if (firstTs <= 0L) firstTs = nowMs;
                    saveIntelExpertFirst(tc, firstTs, attempts);
                    if (intelExpertGraceActive(firstTs, attempts, nowMs)) {
                        m.subSkip("expert_extra", "专家情报同步中（第" + attempts + "次·待重试）");
                    } else {
                        m.subSkip("expert_extra", "专家情报未同步（待重试）");
                        tc.saveModText("intel", "expert_extra", "未同步（待重试）");
                    }
                } else {
                    clearIntelExpertFirst(tc);
                    List<Long> pending = ServerTasks.unclaimedFinishIds(eq.get("finishs"));
                    int claimed = 0, failed = 0;
                    int skipped = Math.max(0, ServerTasks.finishCount(eq.get("finishs")) - pending.size());
                    for (long qid : pending) {
                        String key = String.valueOf(qid);
                        if (ClaimLedger.isClaimed(tc, "expert_extra", key)) {
                            skipped++;
                            continue;
                        }
                        int[] c = ServerTasks.tryClaim(tc, "专家情报", "req_get_expert_quest_award",
                                Exec.args("quest_id", qid), null);
                        if (c[0] > 0) {
                            claimed++;
                            ClaimLedger.mark(tc, "expert_extra", key);
                        } else if (c[1] > 0) {
                            // 已领/未开放等账号状态：记台账，后续轮次不再请求也不再提示。
                            skipped++;
                            ClaimLedger.mark(tc, "expert_extra", key);
                        } else {
                            failed++;
                        }
                    }
                    if (claimed > 0) {
                        m.claimed("专家额外情报×" + claimed);
                        m.subOk("expert_extra", "领取×" + claimed);
                        tc.saveModText("intel", "expert_extra", "已领取 " + claimed + " 项");
                    } else if (failed > 0) {
                        m.fail("专家额外情报失败×" + failed);
                        m.subFail("expert_extra", "失败×" + failed);
                        tc.saveModText("intel", "expert_extra", "失败 " + failed + " 项");
                    } else {
                        m.subSkip("expert_extra", "无可领（已领/去重 " + skipped + "）");
                        tc.saveModText("intel", "expert_extra",
                                "无可领 · 已领/去重 " + skipped + " 项");
                    }
                }
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "情报任务");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "intel", "情报任务");
    }

    /** 合并情报事件推送（info 全量 / events 增量）：以事件实例 id 归并。 */
    private static void mergeIntelEvents(WjdrEngine engine, String proto,
                                         Map<Long, Map<String, Object>> events) {
        Map<String, Object> content = notifyContentOf(engine, proto);
        if (content == null) return;
        for (Map<String, Object> raw : Exec.mapsOf(content.get("events"))) {
            long id = Exec.longOf(raw.get("id"));
            if (id <= 0) continue;
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> prev = events.get(id);
            if (prev != null) merged.putAll(prev);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() != null) merged.put(e.getKey(), e.getValue());
            }
            merged.put("id", id);
            events.put(id, merged);
        }
    }

    /** 情报事件配置 id（协议字段 event_id；兼容历史形状的 event_cfg_id/type）。 */
    private static long intelEventConfigId(Map<String, Object> e) {
        for (String k : new String[]{"event_id", "event_cfg_id", "event_config_id", "type"}) {
            Object v = e.get(k);
            if (v != null) return Exec.longOf(v);
        }
        return 0L;
    }

    /**
     * 事件类型：1:1 网页版 {@code DailyIntelligenceEventCatalog.resolveType}——
     * 由 eventConfigId 区间解析（不是读消息里的 type 字段）。
     */
    static int intelEventType(long configId) {
        if (configId >= 11001L && configId <= 11010L) return 11;   // 活动巨兽（烈焰獠牙）
        if (configId >= 100000L) return 100;                        // 冒险巨兽
        if (configId == 4001L || intelBetween(configId, 2001L, 2050L)
                || intelBetween(configId, 6001L, 6050L)) return 2;  // 营救幸存者
        if (intelBetween(configId, 3001L, 3050L) || intelBetween(configId, 7001L, 7050L)) return 3; // 英雄之旅
        if (intelBetween(configId, 1001L, 1050L) || intelBetween(configId, 4002L, 4004L)
                || intelBetween(configId, 5001L, 5050L)
                || intelBetween(configId, 8001L, 8050L)) return 1;  // 怪物情报
        return 0;
    }

    private static boolean intelBetween(long v, long min, long max) {
        return v >= min && v <= max;
    }

    /** 世界行军类：1 怪物 / 2 营救 / 11 活动巨兽 / 100 冒险巨兽。 */
    static boolean intelIsWorldMarch(int type) {
        return type == 1 || type == 2 || type == 11 || type == 100;
    }

    // ---------- 情报门控/竞态纯函数（I2/I4，单测见 IntelArenaFixTest） ----------

    /** 情报普通队列配置数：网页版 DailyIntelligenceConfig 默认 queueCount=1、上限 6；
     *  兼容旧参数名 queueCount / max_queues。 */
    static int intelQueueCount(JSONObject intelParams) {
        if (intelParams == null) return 1;
        int v = intelParams.optInt("queue_count", 0);
        if (v <= 0) v = intelParams.optInt("queueCount", 0);
        if (v <= 0) v = intelParams.optInt("max_queues", 0);
        return v <= 0 ? 1 : v;
    }

    /** 情报普通队列上限 = min(配置数, 世界容量)（1:1 网页版
     *  effectiveQueueCount = min(config.queueCount, playerQueueLimit(world))，DailyIntelligenceService:594-606）。 */
    static int intelQueueSlots(int configuredQueueCount, int worldCapacity) {
        int cfg = Math.max(1, Math.min(6, configuredQueueCount));
        int cap = worldCapacity > 0 ? worldCapacity : 3;   // 世界容量取不到时沿用 marchCapacity 的兜底 3
        return Math.max(0, Math.min(cfg, cap));
    }

    /** 单次情报体力消耗（1:1 网页版 DailyIntelligenceService.staminaCost:602-604）：
     *  营救(2)/英雄之旅(3) 12 点，其余 10 点。 */
    static long intelStaminaCost(int type) {
        return type == 2 || type == 3 ? 12L : 10L;
    }

    /** 体力门判定：unknown(null)=体力数据未到 → 只做队列门，不阻塞派队。 */
    static boolean intelStaminaAllows(Long usableStamina, long cost) {
        return usableStamina == null || usableStamina >= cost;
    }

    /** 派队去重判定（纯函数）：同一事件在窗口内只派一次。 */
    static boolean intelDispatchBlocked(long lastDispatchAtMillis, long nowMillis, long windowMillis) {
        return lastDispatchAtMillis > 0L && nowMillis - lastDispatchAtMillis < windowMillis;
    }

    /** I4 初始化宽限（纯函数）：前 3 次且 10 分钟内不判“未同步”。 */
    static boolean intelExpertGraceActive(long firstAttemptMillis, int attempts, long nowMillis) {
        if (firstAttemptMillis <= 0L) return true;
        return attempts <= INTEL_EXPERT_FIRST_MAX_ATTEMPTS
                && nowMillis - firstAttemptMillis <= INTEL_EXPERT_FIRST_GRACE_MILLIS;
    }

    /** 体力读取：先 update（增量，最新值）再 init（全量），取 notify_data 的 id=201 项。
     *  1:1 网页版 BehemothAccountState.recoverUpdate:2645-2653（id==201 → count）。 */
    static Long intelStamina(WjdrEngine engine) {
        Long v = intelStaminaOf(notifyContentOf(engine, "notify_recover_resource_update"));
        if (v == null) v = intelStaminaOf(notifyContentOf(engine, "notify_recover_resource_init"));
        return v;
    }

    /** 基础资源推送中的体力值；形状兼容 List&lt;{id,count}&gt; / Map{"201.0":{count}} / 本体即条目。
     *  取不到返回 null（体力门退化为只做队列门）。 */
    static Long intelStaminaOf(Map<String, Object> recoverNotify) {
        if (recoverNotify == null) return null;
        for (Map<String, Object> item : intelRecoverItems(recoverNotify)) {
            if (intelNumericId(item.get("id")) != INTEL_STAMINA_RESOURCE_ID) continue;
            Object count = item.get("count");
            if (count == null) count = item.get("value");
            if (count != null) return Exec.longOf(count);
        }
        return null;
    }

    private static List<Map<String, Object>> intelRecoverItems(Map<String, Object> recoverNotify) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object data = recoverNotify.get("data");
        if (data != null) out.addAll(Exec.mapsOf(data));
        if (out.isEmpty() && recoverNotify.containsKey("id")) {
            out.add(recoverNotify);
            return out;
        }
        if (out.isEmpty()) {
            // notifyData 已把 data（Map 形状）解包成内层：{ "201.0": {count,...} }
            addIdKeyedItems(out, data instanceof Map ? (Map<?, ?>) data : recoverNotify);
        }
        return out;
    }

    /** 形状兼容：{ "201.0": {count,max_count,...} }（键即资源 id，notifyData 解包后常见）。 */
    private static void addIdKeyedItems(List<Map<String, Object>> out, Map<?, ?> container) {
        int numericKeys = 0;
        for (Object k : container.keySet()) {
            if (intelNumericId(k) > 0L) numericKeys++;
        }
        if (numericKeys == 0) return;
        for (Map.Entry<?, ?> en : container.entrySet()) {
            if (intelNumericId(en.getKey()) <= 0L) continue;
            Map<String, Object> item = Exec.mapOf(en.getValue());
            if (item == null) continue;
            Map<String, Object> withId = new LinkedHashMap<>(item);
            withId.putIfAbsent("id", en.getKey());
            out.add(withId);
        }
    }

    /** "201.0" 这类字符串/数字 id 的统一解析。 */
    private static long intelNumericId(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 情报派队去重台账（srv:intel:dispatch:&lt;fpid&gt;:&lt;sid&gt; → {eventId: epochMillis}）。 */
    static boolean intelDispatchBlocked(TaskContext tc, long eventId, long nowMillis) {
        return intelDispatchBlocked(intelDispatchLedger(tc).optLong(String.valueOf(eventId), 0L),
                nowMillis, INTEL_DISPATCH_DEDUPE_MILLIS);
    }

    static void markIntelDispatched(TaskContext tc, long eventId, long nowMillis) {
        if (eventId <= 0L) return;
        JSONObject ledger = intelDispatchLedger(tc);
        try {
            java.util.Iterator<String> it = ledger.keys();
            List<String> stale = new ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (nowMillis - ledger.optLong(k, 0L) > 86_400_000L) stale.add(k);
            }
            for (String k : stale) ledger.remove(k);
            ledger.put(String.valueOf(eventId), nowMillis);
            tc.store.putString(intelDispatchKey(tc), ledger.toString());
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject intelDispatchLedger(TaskContext tc) {
        try {
            String raw = tc.store.getString(intelDispatchKey(tc));
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static String intelDispatchKey(TaskContext tc) {
        return "srv:intel:dispatch:" + tc.fpid + ":" + tc.sid;
    }

    /** I4：首次“notify_expert_quests 未到”的时间与尝试次数（srv:intel:expertfirst:&lt;fpid&gt;:&lt;sid&gt;）。 */
    private static JSONObject intelExpertFirstState(TaskContext tc) {
        try {
            String raw = tc.store.getString(intelExpertFirstKey(tc));
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void saveIntelExpertFirst(TaskContext tc, long firstMillis, int attempts) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", firstMillis);
            o.put("attempts", attempts);
            tc.store.putString(intelExpertFirstKey(tc), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void clearIntelExpertFirst(TaskContext tc) {
        try {
            tc.store.putString(intelExpertFirstKey(tc), "");
        } catch (Throwable ignored) {
        }
    }

    private static String intelExpertFirstKey(TaskContext tc) {
        return "srv:intel:expertfirst:" + tc.fpid + ":" + tc.sid;
    }

    /** 追加等待原因（限长，避免状态文案无限增长）。 */
    private static void appendWait(StringBuilder sb, String reason) {
        if (sb.length() < 60) sb.append(reason).append(' ');
    }

    /**
     * 当前情报行军数（type=301/302 合计）：1:1 网页版 activeIntelligenceMarchCount
     * （DailyIntelligenceService.java:926-928）。与 countMarches 同协议，
     * 但一次查询统计两种类型（gather 用的 countMarches 保持不动，避免回退采集修复）。
     */
    private static int countIntelMarches(WjdrEngine engine) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return 0;
            Object code = rsp.getContent().get("code");
            if (code != null && Exec.longOf(code) != 0) return 0;
            Map<String, Object> detail = Exec.mapOf(rsp.getContent().get("detail"));
            int n = 0;
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : Exec.mapsOf(detail == null ? null : detail.get(k))) {
                    long t = Exec.longOf(march.get("type"));
                    if (t == 301L || t == 302L) n++;
                }
                if (n > 0) break;
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 推送内容：优先解包 data/info/content 内层，取不到再回退原始 content。 */
    private static Map<String, Object> notifyContentOf(WjdrEngine engine, String proto) {
        Map<String, Object> inner = engine.notifyData(proto);
        return inner != null ? inner : engine.notifyContent(proto);
    }

    /** Map → values；Collection → 原样；其它 → 空（用于形状不固定的 ids 字段）。 */
    private static List<Object> rawValues(Object v) {
        if (v instanceof Map) return new ArrayList<>(((Map<?, ?>) v).values());
        if (v instanceof List) return new ArrayList<>((List<?>) v);
        if (v instanceof java.util.Collection) return new ArrayList<>((java.util.Collection<?>) v);
        return new ArrayList<>();
    }

    // ---------- 自动狩猎野怪 ----------

    public static TaskOutcome hunt(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            JSONObject hp = tc.params("hunt");
            int lvMin = Math.max(1, Math.min(10, hp.optInt("level_min", 4)));
            int lvMax = Math.max(lvMin, Math.min(10, hp.optInt("level_max", 6)));
            int maxTimes = Math.max(1, Math.min(20, hp.optInt("max_times", 5)));
            int hunted = 0;
            int noTarget = 0;
            List<Long> allHeroes = Exec.allHeroes(tc.engine);
            boolean heroConfigured = hp.has("hero_ids") || hp.optInt("squad_index", 0) > 0;
            int heroOffset = 0;
            for (int round = 0; round < maxTimes; round++) {
                Map<String, Object> target = null;
                for (int lv = lvMax; lv >= lvMin && target == null; lv--) {
                    target = searchMonster(tc.engine, lv);
                }
                if (target == null) {
                    noTarget++;
                    break;
                }
                Map<String, Object> formation;
                if (!heroConfigured && heroOffset + 3 <= allHeroes.size()) {
                    // 301=英雄不在城（正在采集/狩猎）：每队轮换下一组 3 个空闲候选。
                    formation = Exec.formationWithHeroes(tc.engine,
                            allHeroes.subList(heroOffset, heroOffset + 3));
                } else {
                    formation = formationFor(tc, "hunt", 1 + (round % 3));
                }
                if (formation == null) {
                    m.skip("无英雄可派");
                    break;
                }
                long monsterId = Exec.longOf(target.get("monsterid"));
                Map<String, Object> extra = monsterId > 0
                        ? Exec.args("monsterid", monsterId) : new LinkedHashMap<>();
                GameMessage march = tc.engine.request("req_world_march", Exec.args(
                        "type", 3L,
                        "endpoint", Exec.point(Exec.longOf(target.get("x")), Exec.longOf(target.get("y"))),
                        "formation", formation,
                        "extra", extra));
                if (Exec.marchId(march) > 0) {
                    hunted++;
                    heroOffset += 3;
                } else {
                    int code = Exec.codeOf(march);
                    if (code == 301) {
                        // 301=目标/英雄当前不可出征：换下一组候选重试同一目标。
                        heroOffset += 3;
                        if (!heroConfigured && heroOffset + 3 > allHeroes.size()) {
                            m.skip("暂无空闲英雄或目标不可出征(301)");
                            break;
                        }
                        Exec.sleep(300);
                        continue;
                    }
                    if (code == 51) {
                        m.skip("体力不足");
                        break;
                    }
                    if (code == 2878) {
                        m.skip("行军队列已满");
                        break;
                    }
                    noTarget++;
                    if (noTarget >= 3) break;
                }
                Exec.sleep(500);
            }
            if (hunted > 0) {
                m.claimed("狩猎×" + hunted);
                tc.saveModText("hunt", "hunt", "上轮狩猎 " + hunted + " 只（Lv" + lvMin + "-" + lvMax + "）");
            } else if (m.sb.length() == 0) m.skip("未找到可猎野怪");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "自动狩猎");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "hunt", "自动狩猎");
    }

    // ---------- 探险速刷（App FieldTasks.adventureSweep 逐行移植） ----------
    //
    // req_begin_adventure_pve{checkpoint, fight_heros_data, is_fast} →
    // req_end_adventure_pve{checkpoint, operatedata, enddata}。
    // 已通关(≤pass_checkpoint)的关卡走 is_fast 速刷无需战斗回放；未通关的新关
    // 需要客户端战斗模拟，enddata 形状未实证——遇到即停并提示手动解锁一次。

    public static TaskOutcome adventureSweep(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(1500);
            JSONObject sp = tc.params("adventure_sweep");
            JSONObject strat = sp.optJSONObject("strategy");
            int maxSweep = strat != null && strat.has("sweep") ? strat.optInt("sweep", 5) : 5;
            maxSweep = Math.max(1, Math.min(50, maxSweep));

            Map<String, Object> pve = tc.engine.notifyData("notify_adventure_pve_info");
            long passed = numIn(pve, "pass_checkpoint");
            if (passed <= 0) {
                m.skip("探险进度未同步（先手动进一次探险）");
                m.subSkip("sweep", "进度未同步");
                return Exec.result(m, "adventure_sweep", "探险速刷");
            }
            List<Long> heroes = null;
            for (List<Long> v : Exec.clientSquads(tc.engine).values()) {
                if (v != null && !v.isEmpty()) {
                    heroes = v;
                    break;
                }
            }
            if (heroes == null) {
                heroes = new ArrayList<>();
                List<Long> all = Exec.allHeroes(tc.engine);
                for (int i = 0; i < all.size() && heroes.size() < 5; i++) heroes.add(all.get(i));
            }
            if (heroes.isEmpty()) {
                m.skip("无可用英雄编队");
                m.subSkip("sweep", "无编队");
                return Exec.result(m, "adventure_sweep", "探险速刷");
            }
            int swept = 0;
            for (int i = 0; i < maxSweep; i++) {
                long next = passed + 1;
                // 协议：fight_heros_data 是对象 {source, fight_heros[]}，直接传数组会报
                // “Struct field expects Map”。
                Map<String, Object> fightData = Exec.args(
                        "source", 1L, "fight_heros", heroes);
                GameMessage begin = tc.engine.request("req_begin_adventure_pve", Exec.args(
                        "checkpoint", next, "fight_heros_data", fightData, "is_fast", true));
                if (!Exec.ok(begin)) {
                    int code = Exec.codeOf(begin);
                    m.skip("第" + next + "关无法速刷(码" + code + ")"
                            + (code != 0 ? "" : ""));
                    break;
                }
                GameMessage end = tc.engine.request("req_end_adventure_pve", Exec.args(
                        "checkpoint", next, "operatedata", new LinkedHashMap<String, Object>(),
                        "enddata", new ArrayList<>()));
                if (Exec.ok(end)) {
                    swept++;
                    passed = next;
                    m.claimed("速刷第" + next + "关");
                } else {
                    int code = Exec.codeOf(end);
                    m.skip("第" + next + "关未确认(码" + code + ")——新关卡需手动战斗解锁一次");
                    break;
                }
            }
            if (swept > 0) {
                m.subOk("sweep", "速刷×" + swept + "（推进到第" + passed + "关）");
                tc.saveModText("adventure_sweep", "sweep",
                        "上轮速刷 " + swept + " 关 → 第 " + passed + " 关");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "探险速刷");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "adventure_sweep", "探险速刷");
    }

    /** 逐层下钻取数值（App TownTasks.numIn 同款：顶层或一层容器内）。 */
    private static long numIn(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        if (v instanceof Number) return Exec.longOf(v);
        for (Object o : map.values()) {
            if (o instanceof Map) {
                Object inner = ((Map<?, ?>) o).get(key);
                if (inner instanceof Number) return Exec.longOf(inner);
            }
        }
        return 0;
    }

    private static Map<String, Object> searchMonster(WjdrEngine engine, int level) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", Exec.args(
                "mapobj_type", 3L, "level_min", level, "level_max", level));
        if (!Exec.ok(rsp)) return null;
        Map<String, Object> point = Exec.mapOf(rsp.getContent().get("point"));
        if (point != null && point.containsKey("x") && point.containsKey("y")) {
            // 搜索只给坐标；野怪 id 要用 req_world_mapobj{kid,point} 查该格的地图对象。
            try {
                GameMessage obj = engine.request("req_world_mapobj", Exec.args(
                        "kid", engine.lastServerId(),
                        "point", Exec.point(Exec.longOf(point.get("x")), Exec.longOf(point.get("y")))));
                Map<String, Object> mo = obj == null ? null : Exec.mapOf(obj.getContent().get("mapobj"));
                Map<String, Object> mon = mo == null ? null : Exec.mapOf(mo.get("map_monster"));
                if (mon != null) {
                    long mid = Exec.longOf(mon.get("id"));
                    if (mid > 0) point.put("monsterid", mid);
                    Map<String, Object> ap = Exec.mapOf(mon.get("atk_point"));
                    if (ap != null) point.put("atk_point", ap);
                }
            } catch (Throwable t) {
                FileLog.warn("野怪对象查询失败 {}", String.valueOf(t));
            }
            return point;
        }
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : Exec.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    // ================= 城建：科技研究 + 建筑升级（App TownDevTasks 移植） =================

    public static final class DevState {
        public final Map<Long, Long> techLevels = new HashMap<>();
        public final Map<Long, Integer> buildingLevels = new HashMap<>();
        public final java.util.Set<Long> busyBuildings = new HashSet<>();
        public Long idleResearchQueue;
        public final List<Long> idleBuildQueues = new ArrayList<>();
        public boolean ready;
    }

    static DevState snapshot(WjdrEngine engine, TaskContext tc) {
        DevState s = new DevState();
        Map<String, Object> ar = engine.notifyData("notify_all_research");
        Map<String, Object> si = engine.notifyData("notify_shelter_info");
        Map<String, Object> qi = engine.notifyData("notify_queue_info");
        if (ar == null || si == null || qi == null) return s;
        s.ready = true;
        Object researched = ar.get("researched");
        Map<Long, Long> kv = Exec.kvPairs(researched instanceof Map
                ? ((Map<?, ?>) researched) : null);
        if (!kv.isEmpty()) {
            for (Map.Entry<Long, Long> e : kv.entrySet()) s.techLevels.put(e.getKey(), e.getValue());
        } else {
            for (Map<String, Object> m : Exec.mapsOf(researched)) {
                long tid = Exec.longOf(m.get("tid"));
                if (tid > 0) s.techLevels.put(tid, Exec.longOf(m.get("lev")));
            }
        }
        Object bi = si.get("building_info");
        if (bi instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) bi).entrySet()) {
                absorbBuilding(s, e.getValue(), Exec.longOf(e.getKey()));
            }
        } else {
            for (Object o : Exec.listOf(bi) != null ? Exec.listOf(bi) : new ArrayList<>()) {
                absorbBuilding(s, o, 0);
            }
        }
        Object qm = qi.get("queue_map");
        List<Map<String, Object>> queues = new ArrayList<>();
        if (qm instanceof Map) {
            for (Object v : ((Map<?, ?>) qm).values()) {
                Map<String, Object> mm = Exec.mapOf(v);
                if (mm != null) queues.add(mm);
            }
        } else {
            queues = Exec.mapsOf(qm);
        }
        for (Map<String, Object> q : queues) {
            long qtype = Exec.longOf(Exec.mapOf(q.get("base")) != null
                    ? Exec.mapOf(q.get("base")).get("qtype") : q.get("qtype"));
            long status = Exec.longOf(q.get("status"));
            long end = Exec.longOf(q.get("end_time"));
            long qid = Exec.longOf(q.get("qid"));
            boolean busy = status != 1 && end > 0;
            if (busy) continue;
            if (qtype == 1 && qid > 0) s.idleBuildQueues.add(qid);
            if (qtype == 2 && qid > 0 && s.idleResearchQueue == null) s.idleResearchQueue = qid;
        }
        // 队列倒计时状态行（building.auto / research.auto）
        try {
            java.text.SimpleDateFormat hm = com.carlos.vaultserver.Beijing.fmt("HH:mm");
            Map<String, Object> qi2 = engine.notifyContent("notify_queue_info");
            Object qm2 = Exec.mapOf(qi2 == null ? null : qi2.get("data")) != null
                    ? Exec.mapOf(qi2.get("data")).get("queue_map") : qi2.get("queue_map");
            List<Map<String, Object>> qs = new ArrayList<>();
            if (qm2 instanceof Map) {
                for (Object v2 : ((Map<?, ?>) qm2).values()) {
                    Map<String, Object> mm = Exec.mapOf(v2);
                    if (mm != null) qs.add(mm);
                }
            } else {
                qs = Exec.mapsOf(qm2);
            }
            long bEnd = 0, rEnd = 0;
            for (Map<String, Object> q : qs) {
                long qtype = Exec.longOf(Exec.mapOf(q.get("base")) != null
                        ? Exec.mapOf(q.get("base")).get("qtype") : q.get("qtype"));
                long end = Exec.longOf(q.get("end_time"));
                if (end <= System.currentTimeMillis() / 1000L) continue;
                if (qtype == 1 && (bEnd == 0 || end < bEnd)) bEnd = end;
                if (qtype == 2 && (rEnd == 0 || end < rEnd)) rEnd = end;
            }
            if (bEnd > 0) tc.saveModText("building", "auto",
                    "施工中 · " + hm.format(new java.util.Date(bEnd * 1000)) + "完成");
            if (rEnd > 0) tc.saveModText("research", "auto",
                    "研究进行中 · " + hm.format(new java.util.Date(rEnd * 1000)) + "完成");
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static void absorbBuilding(DevState s, Object raw, long fallbackBid) {
        Map<String, Object> entry = Exec.mapOf(raw);
        if (entry == null) return;
        long bid = Exec.longOf(entry.get("bid"));
        if (bid <= 0) bid = fallbackBid;
        if (bid <= 0) return;
        Map<String, Object> base = Exec.mapOf(entry.get("base"));
        int lv = base != null ? Exec.intOf(base.get("lv")) : Exec.intOf(entry.get("lv"));
        long status = base != null ? Exec.longOf(base.get("status")) : Exec.longOf(entry.get("status"));
        long qid = Exec.longOf(entry.get("qid"));
        if (lv > 0) s.buildingLevels.put(bid, lv);
        if (status != 0 || qid > 0) s.busyBuildings.add(bid);
    }

    // ---------- 科技研究 ----------

    public static TaskOutcome research(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            DevState s = snapshot(tc.engine, tc);
            if (!s.ready) {
                m.skip("科技/建筑/队列数据未同步");
                return Exec.result(m, "research", "科技研究");
            }
            if (s.idleResearchQueue == null) {
                m.skip("研究队列忙");
                return Exec.result(m, "research", "科技研究");
            }
            String profile = tc.params("research").optString("profile", "casual");
            List<TownDevCatalog.Goal> goals = TownDevCatalog.route(profile);
            TownDevCatalog cat = TownDevCatalog.get();
            long[] action = nextTech(cat, goals, s.techLevels, s.buildingLevels, new HashSet<>());
            if (action == null) {
                m.skip("路线内科技已全部达标或被前置阻塞");
                return Exec.result(m, "research", "科技研究");
            }
            long tid = action[0];
            int toLevel = (int) action[1];
            TownDevCatalog.Tech t = cat.techs.get(tid);
            long encoded = tid * 10 + toLevel;
            GameMessage rsp = tc.engine.request("req_begin_research", Exec.args(
                    "tid_lev", encoded, "qid", s.idleResearchQueue));
            int code = Exec.codeOf(rsp);
            if (Exec.ok(rsp)) {
                m.claimed("研究 " + (t == null ? tid : t.name) + " → Lv" + toLevel);
                GameMessage help = tc.engine.request("req_queue_ask_for_help",
                        Exec.args("qid", s.idleResearchQueue));
                if (!Exec.ok(help)) m.skip("联盟求助不可用");
            } else if (code == 51) {
                m.skip("研究资源不足");
            } else if (code == 4111) {
                m.skip("科研所正在升级");
            } else if (code == 806) {
                m.skip("前置科技/建筑等级不足");
            } else if (code == 4104) {
                // 实测：目标科技已满级/当前路线不可研究，不是异常。
                m.skip("科技目标已满级或当前不可研究（码4104）");
            } else {
                m.fail("研究码" + code);
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "科技研究");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "research", "科技研究");
    }

    private static long[] nextTech(TownDevCatalog cat, List<TownDevCatalog.Goal> goals,
                                   Map<Long, Long> levels, Map<Long, Integer> buildingLevels,
                                   java.util.Set<Long> path) {
        for (TownDevCatalog.Goal g : goals) {
            long[] r = ensureTech(cat, g.tid, g.targetLevel, levels, buildingLevels, path);
            if (r != null) return r;
        }
        return null;
    }

    private static long[] ensureTech(TownDevCatalog cat, long tid, int requiredLevel,
                                     Map<Long, Long> levels, Map<Long, Integer> buildingLevels,
                                     java.util.Set<Long> path) {
        long cur = levels.getOrDefault(tid, 0L);
        if (cur >= requiredLevel) return null;
        if (!path.add(tid)) return null;
        TownDevCatalog.Tech t = cat.techs.get(tid);
        if (t == null) {
            path.remove(tid);
            return null;
        }
        int next = (int) cur + 1;
        if (next > t.maxLevel) {
            path.remove(tid);
            return null;
        }
        TownDevCatalog.TechLevel lv = t.level(next);
        if (lv == null) {
            path.remove(tid);
            return null;
        }
        for (long[] cond : lv.conditions) {
            if (cond[0] == 14) {
                long[] r = ensureTech(cat, cond[1], (int) cond[2], levels, buildingLevels, path);
                if (r != null) {
                    path.remove(tid);
                    return r;
                }
            } else if (cond[0] == 5) {
                int bl = buildingLevels.getOrDefault(cond[1], 0);
                if (bl < cond[2]) {
                    path.remove(tid);
                    return null;
                }
            }
        }
        path.remove(tid);
        return new long[]{tid, next};
    }

    // ---------- 建筑升级 ----------

    public static TaskOutcome building(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        try {
            tc.engine.collectNotifies(2500);
            DevState s = snapshot(tc.engine, tc);
            if (!s.ready) {
                m.skip("建筑/队列数据未同步");
                return Exec.result(m, "building", "建筑升级");
            }
            int maxActions = tc.params("building").optInt("max_actions", 2);
            TownDevCatalog cat = TownDevCatalog.get();
            int done = 0;
            for (int i = 0; i < maxActions; i++) {
                if (s.idleBuildQueues.isEmpty()) {
                    if (i == 0) m.skip("建造队列忙");
                    break;
                }
                long[] action = nextBuilding(cat, s, new HashSet<>());
                if (action == null) {
                    if (i == 0) m.skip("无可升建筑（前置/队列/资源限制）");
                    break;
                }
                long bid = action[0];
                int toLevel = (int) action[1];
                TownDevCatalog.Building b = cat.buildings.get(bid);
                long qid = s.idleBuildQueues.remove(0);
                GameMessage rsp = tc.engine.request("req_upgrade_building", Exec.args(
                        "bid", bid, "use_diamond", false, "qid", qid));
                int code = Exec.codeOf(rsp);
                if (Exec.ok(rsp)) {
                    done++;
                    s.buildingLevels.put(bid, toLevel);
                    s.busyBuildings.add(bid);
                    m.claimed((b == null ? bid : b.name) + "→Lv" + toLevel);
                    GameMessage help = tc.engine.request("req_queue_ask_for_help",
                            Exec.args("qid", qid));
                    if (!Exec.ok(help)) m.skip("求助不可用");
                } else if (code == 51) {
                    m.skip("资源不足，停止本轮");
                    break;
                } else if (code == 806) {
                    m.skip("前置建筑等级不足");
                    break;
                } else if (code == 119 || code == 120) {
                    m.skip("需先满足家具门槛（大熔炉类）");
                    break;
                } else {
                    m.skip("升级码" + code);
                    break;
                }
            }
            if (done == 0 && m.sb.length() == 0) m.skip("无可升建筑");
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "建筑升级");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return Exec.result(m, "building", "建筑升级");
    }

    private static long[] nextBuilding(TownDevCatalog cat, DevState s, java.util.Set<Long> path) {
        List<Long> ids = new ArrayList<>(cat.buildings.keySet());
        ids.sort(Long::compare);
        for (long bid : ids) {
            if (s.buildingLevels.getOrDefault(bid, 0) <= 0) continue;
            long[] r = ensureBuilding(cat, bid, s, path);
            if (r != null) return r;
        }
        return null;
    }

    private static long[] ensureBuilding(TownDevCatalog cat, long bid, DevState s, java.util.Set<Long> path) {
        TownDevCatalog.Building b = cat.buildings.get(bid);
        if (b == null) return null;
        if (!path.add(bid)) return null;
        try {
            int cur = s.buildingLevels.getOrDefault(bid, 0);
            if (cur >= b.maxLevel) return null;
            if (s.busyBuildings.contains(bid)) return null;
            int next = cur + 1;
            TownDevCatalog.BuildingLevel lv = b.level(next);
            if (lv == null) return null;
            for (long[] pre : lv.prerequisites) {
                int preLv = s.buildingLevels.getOrDefault(pre[0], 0);
                if (preLv < pre[1]) {
                    long[] r = ensureBuilding(cat, pre[0], s, path);
                    if (r != null) return r;
                }
            }
            return new long[]{bid, next};
        } finally {
            path.remove(bid);
        }
    }
}
