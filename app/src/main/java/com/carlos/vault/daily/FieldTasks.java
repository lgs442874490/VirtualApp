package com.carlos.vault.daily;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 野外类任务（协议流移植自网页版 Gather/Intelligence/Behemoth/BearAction/Hunt 服务，
 * 按一次性执行语义：每次运行填满空闲采集队列 / 处理当前情报 / 开启原生自动上车 / 狩猎若干野怪）。
 */
final class FieldTasks {

    private FieldTasks() {
    }

    /** P7 战时让位：WarState 战争态/预警级时，占行军队列的可选出征类任务让位（E1/§6.11.2）。 */
    private static boolean warBusy(WjdrEngine engine) {
        try {
            return com.carlos.vault.daily.guardian.GuardianEngine.warBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId());
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 野外采集：搜资源 → 派队（填空闲队列，默认肉） ----------

    static DailyResult gather(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // P7 战时（E3 池改组）：6 队全部为驻防/支援预留，采集暂停（在途已由 E2 召回）
            if (warBusy(engine)) {
                m.skip("战争态：队列全部留驻防，采集暂停");
                m.subSkip("send", "战时暂停");
                return done(m);
            }
            // 子开关门控：send=自动采集（本功能唯一动作；策略只管"任意存量/只采满矿"）
            boolean onSend = FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "gather", "send", true);
            if (!onSend) {
                m.skip("子开关「自动采集」已关闭");
                m.subSkip("send", "子开关已关闭");
                return done(m);
            }
            engine.collectNotifies(2500);
            org.json.JSONObject p = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "gather");
            int minLevel = Math.max(1, Math.min(6, p.optInt("min_level", 5)));
            int maxQueues = Math.max(1, Math.min(5, p.optInt("max_queues", 3)));
            java.util.List<Integer> resList = new java.util.ArrayList<>();
            // 与服务器侧（ServerFieldTasks.gather）同源修复：UI 写的是 resources_named；
            // 采集 res_id 直接是 102肉/103木/104煤/105铁，旧实现再减 101 → 搜索恒 2872（采集从未派出）。
            org.json.JSONArray named = p.optJSONArray("resources_named");
            if (named != null && named.length() > 0) {
                for (int i = 0; i < named.length(); i++) {
                    String n = named.optString(i, "").trim().toUpperCase();
                    switch (n) {
                        case "MEAT": resList.add(102); break;
                        case "WOOD": resList.add(103); break;
                        case "COAL": resList.add(104); break;
                        case "IRON": resList.add(105); break;
                        default: break;
                    }
                }
            }
            if (resList.isEmpty()) {
                org.json.JSONArray resArr = p.optJSONArray("resources");
                if (resArr == null || resArr.length() == 0) {
                    resList.add(102);   // 默认肉
                    resList.add(103);   // 默认木
                } else {
                    for (int i = 0; i < resArr.length(); i++) {
                        // 兼容历史配置：旧版本 JSON 里可能存 1-4 的“资源序号”，统一换算成 102-105。
                        int v = resArr.optInt(i);
                        if (v >= 1 && v <= 4) v += 101;
                        resList.add(v);
                    }
                }
            }
            int active = countMarches(engine, 4);
            int capacity = Math.min(maxQueues, marchCapacity(engine));
            // 守护盟友队列预留（设计 §6.10 双池）：守护开启时采集最多占 capacity-2，
            // 给卡秒驻防留 2 个机动队列（GuardianLaunch 发 type=5 需要空队列）
            try {
                if (com.carlos.vault.daily.guardian.GuardianEngine.reserveQueues(engine.appContext(),
                        engine.lastFpid(), engine.lastServerId()) > 0) {
                    capacity = Math.max(active, capacity - com.carlos.vault.daily.guardian.GuardianEngine.reserveQueues(
                            engine.appContext(), engine.lastFpid(), engine.lastServerId()));
                }
            } catch (Throwable ignored) {
            }
            // 策略：只采满矿（req_world_map_search 的 is_full 过滤）
            boolean fullOnly = "full".equals(FnParams.strategy(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "gather", "send", "any"));
            int slots = Math.max(0, capacity - active);
            if (slots == 0) {
                m.skip("采集队列已满(" + active + "/" + capacity + ")");
            } else {
                int sent = 0;
                for (int res : resList) {
                    if (sent >= slots) break;
                    for (int level = Math.min(6, minLevel); level >= 1 && sent < slots; level--) {
                        Map<String, Object> pt = searchResource(engine, res, level, fullOnly);
                        if (pt == null) continue;
                        Map<String, Object> formation = ExecUtil.formation(engine, 1 + (sent % 3));
                        if (formation == null) {
                            m.skip("无英雄可派");
                            return done(m);
                        }
                        GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                "type", 4L, "endpoint", pt, "formation", formation,
                                "extra", new java.util.LinkedHashMap<>()));
                        if (ExecUtil.marchId(march) > 0) {
                            sent++;
                        } else {
                            int code = ExecUtil.codeOf(march);
                            if (code == 2878 || code == 401) {   // 队列满/兵不足
                                m.skip("派出" + sent + "后受阻(码" + code + ")");
                                return done(m);
                            }
                        }
                    }
                }
                if (sent > 0) {
                    m.claimed("派出采集×" + sent);
                    mod(engine, "gather", "send", "上轮派出 " + sent + " 队（队列 " + active + "/" + capacity + "）");
                    // 容量估算 + 采集英雄推荐（对标原版 MarchCapacityState；兵量=节点余量÷容量）
                    try {
                        GatherCapacity.Estimate est = GatherCapacity.estimateFull(engine);
                        mod(engine, "gather", "capacity", "编队容量≈" + est.capacity + "/队"
                                + (est.note.isEmpty() ? "" : "（" + est.note + "）")
                                + " · 满载兵量=节点余量÷容量 · " + GatherCapacity.GATHER_HERO_HINT);
                    } catch (Throwable ignored) {
                    }
                } else m.skip("附近未搜到资源点");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "野外采集");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return done(m);
    }

    static void mod(WjdrEngine engine, String fnId, String subId, String text) {
        try {
            FnParams.saveModText(engine.appContext(), engine.lastFpid(), engine.lastServerId(), fnId, subId, text);
        } catch (Throwable ignored) {
        }
    }

    private static DailyResult done(ExecUtil.Multi m) {
        DailyResult rr = new DailyResult("gather", "野外采集", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    /** req_world_map_search 找一个资源点坐标（找不到返回 null）。fullOnly=只搜满存量矿。 */
    private static Map<String, Object> searchResource(WjdrEngine engine, int resId, int level,
                                                      boolean fullOnly) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", ExecUtil.args(
                "mapobj_type", 2L, "level_min", level, "level_max", level,
                "res_id", resId, "is_full", fullOnly));
        if (!ExecUtil.ok(rsp)) return null;
        // 协议：坐标在单对象 point{x,y}（不是 points/data/list）。
        Map<String, Object> point = ExecUtil.mapOf(rsp.getContent().get("point"));
        if (point != null && point.containsKey("x") && point.containsKey("y")) return point;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : ExecUtil.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    /** 当前 type=N 的行军数。协议盘点 2026-10-12 两级实锤：
     * ① notify_player_march_init 不在 s2c schema（旧实现恒返回 0 → 已派出队列被重复计入空位）；
     * ② schema 解析得 req_world_march_detail_4_player_situation.request{owner,kid}，
     *    空参被服务端拒（code 2 实证）——owner=玩家uid、kid=区号，行军列表在响应 detail 字段。
     *  查询失败按 0 处理（回退旧行为，宁可让服务端拒多发也不漏发）。 */
    private static int countMarches(WjdrEngine engine, int type) {
        try {
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return 0;
            Object code = rsp.getContent().get("code");
            if (code != null && ExecUtil.longOf(code) != 0) return 0;
            Map<String, Object> detail = ExecUtil.mapOf(rsp.getContent().get("detail"));
            int n = 0;
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : ExecUtil.mapsOf(detail == null ? null : detail.get(k))) {
                    if (ExecUtil.longOf(march.get("type")) == type) n++;
                }
                if (n > 0) break;   // 取第一个命中的列表形状
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 普通行军队列容量（1:1 网页 {@code MarchCapacityState.worldMarchCapacity}）：
     * {@code 1 + Σ科技{1007,1020,1027}(lev>0) + (VIP≥6 且 vip_active_endtime>now) +
     * Σbuff(buff_map[10505], expire_ts>now) + max(0, world_extra_march.value)}，只读层 clamp 1..6。
     *
     * <p>读取顺序（W2 全局替换错误读取）：① 核心资产库 {@code queue().capacity}（登录 READY 后
     * 即权威派生值）；② 资产库未 READY / 回滚开关 off → 从登录批通知按公式现算（科技/世界/额外
     * 队列两类必经通知缺失时视为未知，不拿半套数据压低容量）；③ 都未知 → 旧行为回退 3
     * （宁可少发不漏发）。旧实现对 {@code notify_world_player_init.world.capacity} 的读取是错误字段
     * （schema 无 world/capacity，恒回退 3），仅在公式路径不可用时保留为最终兜底。
     */
    private static int marchCapacity(WjdrEngine engine) {
        int fromAsset = assetMarchCapacity(engine);
        if (fromAsset > 0) return fromAsset;
        int computed = marchCapacityFromNotify(engine);
        if (computed > 0) return computed;
        try {
            Map<String, Object> wp = engine.notifyData("notify_world_player_init");
            if (wp != null) {
                Map<String, Object> world = ExecUtil.mapOf(wp.get("world"));
                long cap = world == null ? 0 : ExecUtil.longOf(world.get("capacity"));
                if (cap > 0) return (int) Math.min(cap, 6L);
            }
        } catch (Throwable ignored) {
        }
        return 3;
    }

    /** 资产库容量（未 READY / 回滚开关 off / 未知 → 0）。 */
    private static int assetMarchCapacity(WjdrEngine engine) {
        if (engine == null || !assetLibraryEnabled()) return 0;
        try {
            com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                    com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(engine);
            if (lib == null || !lib.ready()) return 0;
            com.carlos.vault.wjdr.asset.AssetSnapshot.Queue q = lib.queue();
            return q != null && q.capacity > 0 ? q.capacity : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 资产库读侧回滚开关（默认 on；{@code VAULT_ASSET_LIBRARY=off} 走旧读法）。 */
    private static boolean assetLibraryEnabled() {
        try {
            String v = System.getenv("VAULT_ASSET_LIBRARY");
            return v == null || !"off".equalsIgnoreCase(v.trim());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 公式兜底：从登录批通知现算容量（资产库不可用时）。
     *
     * <p>科技 1007/1020/1027 → {@code notify_all_research.researched}；VIP →
     * {@code notify_vip_info/update.vip_lv/vip_active_endtime}；临时队列 →
     * {@code notify_buff_show.map[*].buff_map[10505]（expire_ts 过滤）}；额外队列 →
     * {@code notify_world_player_init.world_extra_march.value}（或
     * {@code notify_world_add_extra_march}）。科技/世界 init 任一缺失 → 0=未知（不猜）。
     */
    private static int marchCapacityFromNotify(WjdrEngine engine) {
        if (engine == null) return 0;
        try {
            Map<String, Object> wpi = engine.notifyData("notify_world_player_init");
            Map<String, Object> researchNotify = engine.notifyData("notify_all_research");
            if (wpi == null || researchNotify == null) return 0;   // 登录批未齐：未知，交给旧兜底
            long nowSec;
            try {
                com.carlos.vault.wjdr.GameServerClock ck = engine.serverClock();
                nowSec = ck != null && ck.locked() ? ck.nowServerSec()
                        : System.currentTimeMillis() / 1000L;
            } catch (Throwable t) {
                nowSec = System.currentTimeMillis() / 1000L;
            }
            int tech = researchQueueCount(researchNotify);
            boolean vip6 = vipQueueActive(engine, nowSec);
            int buffQueues = buffQueueCount(engine, nowSec);
            long extra = extraMarchCount(engine, wpi);
            int raw = com.carlos.vault.wjdr.asset.QueueCapacity.rawCapacity(
                    tech, vip6, buffQueues, extra);
            return com.carlos.vault.wjdr.asset.QueueCapacity.clampCapacity(raw);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 科技队列数：notify_all_research.researched 中 tid∈{1007,1020,1027} 且 lev>0 的个数。 */
    private static int researchQueueCount(Map<String, Object> researchNotify) {
        Map<Long, Long> levels = new java.util.LinkedHashMap<>();
        Object researched = researchNotify.get("researched");
        if (researched == null) researched = researchNotify.get("research");
        if (researched instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) researched).entrySet()) {
                long tid = keyLong(e.getKey());
                long lev;
                if (e.getValue() instanceof Map) {
                    Map<?, ?> row = (Map<?, ?>) e.getValue();
                    if (tid <= 0) tid = ExecUtil.longOf(row.get("tid"));
                    lev = ExecUtil.longOf(row.get("lev"));
                } else {
                    lev = ExecUtil.longOf(e.getValue());
                }
                if (tid > 0 && lev > 0) levels.put(tid, lev);
            }
        } else {
            for (Map<String, Object> row : ExecUtil.mapsOf(researched)) {
                long tid = ExecUtil.longOf(row.get("tid"));
                long lev = ExecUtil.longOf(row.get("lev"));
                if (tid > 0 && lev > 0) levels.put(tid, lev);
            }
        }
        int n = 0;
        for (long tid : com.carlos.vault.wjdr.asset.QueueCapacity.TECH_IDS) {
            Long lev = levels.get(tid);
            if (lev != null && lev > 0) n++;
        }
        return n;
    }

    /** VIP 队列加成：vip_lv≥6 且 vip_active_endtime>now（网页同口径，无「0=永久」例外）。 */
    private static boolean vipQueueActive(WjdrEngine engine, long nowSec) {
        Map<String, Object> vip = engine.notifyData("notify_vip_info");
        if (vip == null) vip = engine.notifyData("notify_vip_update");
        if (vip == null) return false;
        long lv = ExecUtil.longOf(vip.get("vip_lv"));
        long end = ExecUtil.longOf(vip.get("vip_active_endtime"));
        return lv >= com.carlos.vault.wjdr.asset.QueueCapacity.VIP_QUEUE_LEVEL && end > nowSec;
    }

    /** 临时队列 buff：buff_show.map[*].buff_map[10505] 未过期项求和。 */
    private static int buffQueueCount(WjdrEngine engine, long nowSec) {
        Map<String, Object> show = engine.notifyData("notify_buff_show");
        if (show == null) show = engine.notifyData("notify_buff_show_update");
        if (show == null) return 0;
        Object mapObj = show.get("map");
        if (mapObj == null) mapObj = show.get("buffs");
        if (mapObj == null) mapObj = show;
        int sum = 0;
        for (Map<String, Object> buff : ExecUtil.mapsOf(mapObj)) {
            long expire = ExecUtil.longOf(buff.get("expire_ts"));
            if (expire > 0 && expire <= nowSec) continue;
            Object buffMap = buff.get("buff_map");
            if (buffMap == null) continue;
            if (buffMap instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) buffMap).entrySet()) {
                    long attr = keyLong(e.getKey());
                    long val;
                    if (e.getValue() instanceof Map) {
                        Map<?, ?> v = (Map<?, ?>) e.getValue();
                        if (attr <= 0) attr = ExecUtil.longOf(v.get("k"));
                        val = ExecUtil.longOf(v.get("v"));
                    } else {
                        val = ExecUtil.longOf(e.getValue());
                    }
                    if (attr == 10505L && val > 0) sum += (int) Math.min(6L, val);
                }
            }
        }
        return sum;
    }

    /** map key（sproto map 形如 "1020.0"）→ 长整型；解析失败 0。 */
    private static long keyLong(Object k) {
        long v = ExecUtil.longOf(k);
        if (v > 0) return v;
        if (k == null) return 0;
        try {
            return (long) Double.parseDouble(String.valueOf(k).trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 额外队列：world_player_init.world_extra_march.value（或 world_add_extra_march 增量）。 */
    private static long extraMarchCount(WjdrEngine engine, Map<String, Object> worldPlayerInit) {
        long extra = 0;
        Object raw = worldPlayerInit == null ? null : worldPlayerInit.get("world_extra_march");
        Map<String, Object> m = ExecUtil.mapOf(raw);
        if (m != null) extra = ExecUtil.longOf(m.get("value"));
        if (extra <= 0) {
            Map<String, Object> add = engine.notifyData("notify_world_add_extra_march");
            Object addRaw = add == null ? null : add.get("world_extra_march");
            if (addRaw == null && add != null) addRaw = add;
            Map<String, Object> am = ExecUtil.mapOf(addRaw);
            if (am != null) extra = ExecUtil.longOf(am.get("value"));
        }
        return Math.max(0L, extra);
    }

    // ---------- 情报任务：处理当前可派事件 ----------

    static DailyResult intel(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 守护盟友威胁期间让位：情报派队同样占行军队列，威胁窗口内让给卡秒驻防
            if (com.carlos.vault.daily.guardian.GuardianEngine.threatBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId())) {
                m.skip("守护盟友威胁执行中，本轮让位");
                DailyResult yd = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // P7 战时让位（E1/预警级）：WarState 战争态/预警时可选出征类任务让位
            if (warBusy(engine)) {
                m.skip("战争态：队列全部留驻防，情报出征让位");
                DailyResult yd = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // 子开关门控：events=处理情报（本功能唯一动作）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "intel", "events", true)) {
                m.skip("子开关「处理情报」已关闭");
                m.subSkip("events", "子开关已关闭");
                DailyResult off = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
                off.rewards = m.rewardsText();
                off.subResults.putAll(m.subResults);
                return off;
            }
            engine.collectNotifies(2500);
            // 与服务器侧（ServerFieldTasks.intel）同源修复：全量初始化在 notify_intelligence_info，
            // events 只是增量；事件类型由 event_id 区间解析（1怪物/2营救/3英雄之旅/11活动巨兽/100冒险巨兽），
            // status=2 才领奖，status=1 才派队；派队后不立即领奖（等战斗结果推送）。
            Map<Long, Map<String, Object>> events = new java.util.LinkedHashMap<>();
            mergeIntelEvents(engine, "notify_intelligence_info", events);
            mergeIntelEvents(engine, "notify_intelligence_events", events);
            Map<String, Object> del = notifyContentOf(engine, "notify_intelligence_events_del");
            if (del != null) {
                for (Object rawId : rawValuesLoose(del.get("ids"))) {
                    long id = ExecUtil.longOf(rawId);
                    if (id > 0) events.remove(id);
                }
            }
            if (events.isEmpty()) {
                m.skip("暂无情报事件");
            } else {
                int handled = 0;
                for (Map<String, Object> e : events.values()) {
                    long id = ExecUtil.longOf(e.get("id"));
                    if (id <= 0) continue;
                    long status = e.containsKey("status") ? ExecUtil.longOf(e.get("status")) : 1L;
                    int type = intelEventType(intelEventConfigId(e));
                    if (status == 2) {
                        GameMessage rc = engine.request("req_intelligence_receive",
                                ExecUtil.args("id", id));
                        if (ExecUtil.ok(rc) || ExecUtil.codeOf(rc) == 2501) handled++;
                        continue;
                    }
                    if (status != 1) continue;
                    if (type == 3) {
                        // 英雄之旅（PVE）：start_battle → 结算 → end_battle
                        List<Long> heroes = TownTasks.squadHeroes(engine);
                        List<Object> fightHeros = new ArrayList<>(heroes);
                        GameMessage st = engine.request("req_intelligence_start_battle",
                                ExecUtil.args("id", id, "fight_heros_data",
                                        ExecUtil.args("source", 1, "fight_heros", fightHeros)));
                        if (ExecUtil.ok(st)) {
                            ExecUtil.sleep(3000);
                            GameMessage en = engine.request("req_intelligence_end_battle",
                                    ExecUtil.args("id", id));
                            if (ExecUtil.ok(en)) handled++;
                        }
                    } else if (intelIsWorldMarch(type)) {
                        long marchType = type == 2 ? 301L : 302L;
                        Map<String, Object> formation = ExecUtil.formation(engine, 1);
                        GameMessage march = engine.request("req_world_march", ExecUtil.args(
                                "type", marchType,
                                "endpoint", ExecUtil.point(ExecUtil.longOf(e.get("x")), ExecUtil.longOf(e.get("y"))),
                                "formation", formation,
                                "extra", ExecUtil.args("event_id", id)));
                        if (ExecUtil.marchId(march) > 0) handled++;
                    }
                }
                if (handled > 0) m.claimed("处理情报×" + handled);
                else m.skip("无可派情报");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "情报任务");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("intel", "情报任务", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 情报事件状态工具（与服务器侧同源） ----------

    /** 合并情报事件推送（info 全量 / events 增量）：以事件实例 id 归并。 */
    private static void mergeIntelEvents(WjdrEngine engine, String proto,
                                         Map<Long, Map<String, Object>> events) {
        Map<String, Object> content = notifyContentOf(engine, proto);
        if (content == null) return;
        for (Map<String, Object> raw : ExecUtil.mapsOf(content.get("events"))) {
            long id = ExecUtil.longOf(raw.get("id"));
            if (id <= 0) continue;
            Map<String, Object> merged = new java.util.LinkedHashMap<>();
            Map<String, Object> prev = events.get(id);
            if (prev != null) merged.putAll(prev);
            for (Map.Entry<String, Object> en : raw.entrySet()) {
                if (en.getValue() != null) merged.put(en.getKey(), en.getValue());
            }
            merged.put("id", id);
            events.put(id, merged);
        }
    }

    /** 情报事件配置 id（协议字段 event_id；兼容历史形状）。 */
    private static long intelEventConfigId(Map<String, Object> e) {
        for (String k : new String[]{"event_id", "event_cfg_id", "event_config_id", "type"}) {
            Object v = e.get(k);
            if (v != null) return ExecUtil.longOf(v);
        }
        return 0L;
    }

    /** 事件类型：1:1 网页版 DailyIntelligenceEventCatalog.resolveType。 */
    static int intelEventType(long configId) {
        if (configId >= 11001L && configId <= 11010L) return 11;
        if (configId >= 100000L) return 100;
        if (configId == 4001L || intelBetween(configId, 2001L, 2050L)
                || intelBetween(configId, 6001L, 6050L)) return 2;
        if (intelBetween(configId, 3001L, 3050L) || intelBetween(configId, 7001L, 7050L)) return 3;
        if (intelBetween(configId, 1001L, 1050L) || intelBetween(configId, 4002L, 4004L)
                || intelBetween(configId, 5001L, 5050L)
                || intelBetween(configId, 8001L, 8050L)) return 1;
        return 0;
    }

    private static boolean intelBetween(long v, long min, long max) {
        return v >= min && v <= max;
    }

    static boolean intelIsWorldMarch(int type) {
        return type == 1 || type == 2 || type == 11 || type == 100;
    }

    private static Map<String, Object> notifyContentOf(WjdrEngine engine, String proto) {
        Map<String, Object> inner = engine.notifyData(proto);
        return inner != null ? inner : engine.notifyContent(proto);
    }

    private static List<Object> rawValuesLoose(Object v) {
        if (v instanceof Map) return new ArrayList<>(((Map<?, ?>) v).values());
        if (v instanceof List) return new ArrayList<>((List<?>) v);
        if (v instanceof java.util.Collection) return new ArrayList<>((java.util.Collection<?>) v);
        return new ArrayList<>();
    }

    // ---------- 巨兽狩猎：开启客户端原生自动上车（巨兽/死神/巨熊） ----------

    static DailyResult behemoth(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "behemoth", "auto_join", true)) {
                m.skip("自动上车已关闭（设置页可开）");
                DailyResult rr = new DailyResult("behemoth", "巨兽狩猎", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
            }
            // 同步一次当前开关状态
            engine.request("req_alliance_activity_auto_join_info",
                    ExecUtil.args("auto_mass_id_list", java.util.Arrays.asList(1L, 4L, 3L)));
            GameMessage open = engine.request("req_alliance_activity_auto_join_open",
                    ExecUtil.args("auto_mass_id_list", java.util.Arrays.asList(1L, 4L, 3L),
                            "march_limit", 3L));
            if (ExecUtil.ok(open)) {
                m.claimed("自动上车已开启(巨兽/死神/巨熊)");
                mod(engine, "behemoth", "auto_join", "原生自动上车：已开启（巨兽/死神/巨熊）");
            }
            else m.skip("开启码" + ExecUtil.codeOf(open));
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "巨兽狩猎");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("behemoth", "巨兽狩猎", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    // ---------- 巨熊行动：同步陷阱预约（发车/上车由自动上车承接） ----------

    static DailyResult bear(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 守护盟友威胁期间让位（交接 §6.2：防请求队列饿死卡秒驻防 + 保留队列不被日常吃掉）
            if (com.carlos.vault.daily.guardian.GuardianEngine.threatBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId())) {
                m.skip("守护盟友威胁执行中，本轮让位");
                DailyResult yd = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // P7 战时让位（E1/预警级）：巨熊同步占请求队列，战时让给卡秒驻防
            if (warBusy(engine)) {
                m.skip("战争态：让位驻防，巨熊同步暂停");
                DailyResult yd = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // 子开关门控：trap=巨熊陷阱同步（本功能唯一动作）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "bear", "trap", true)) {
                m.skip("子开关「巨熊陷阱同步」已关闭");
                m.subSkip("trap", "子开关已关闭");
                DailyResult off = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
                off.rewards = m.rewardsText();
                off.subResults.putAll(m.subResults);
                return off;
            }
            engine.collectNotifies(1500);
            GameMessage trap = engine.request("req_alliance_trap_info", null);
            try {
                DailyRunner.debugLog(engine.appContext(), "trap rsp=" + String.valueOf(trap.getContent())
                        .substring(0, Math.min(800, String.valueOf(trap.getContent()).length())));
            } catch (Throwable ignored) {
            }
            if (ExecUtil.ok(trap)) {
                // 原版字段：reserve_time/mass_start_time/mass_end_time（内容可能在 data/trap/trap_info 嵌套里）
                long reserve = deepLong(trap.getContent(), "reserve_time", "reserveTime", 0);
                long massStart = deepLong(trap.getContent(), "mass_start_time", "massStartTime", 0);
                long massEnd = deepLong(trap.getContent(), "mass_end_time", "massEndTime", 0);
                long coolEnd = deepLong(trap.getContent(), "cool_end_time", "coolEndTime", 0);
                java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm",
                        java.util.Locale.CHINA);
                java.text.SimpleDateFormat dh = new java.text.SimpleDateFormat("M月d日 HH:mm",
                        java.util.Locale.CHINA);
                boolean rv = reserve > 1_700_000_000L && reserve < 2_000_000_000L;
                String bearText = rv
                        ? "已预约 " + dh.format(new java.util.Date(reserve * 1000))
                        : (reserve > 0 ? "已预约" : "未预约 · 建议提前预约");
                try {
                    org.json.JSONObject mod = new org.json.JSONObject();
                    mod.put("trap", bearText);
                    if (rv) mod.put("reserve", reserve);
                    if (massStart > 1_700_000_000L && massStart < 2_000_000_000L) {
                        mod.put("start", massStart);
                    }
                    if (massEnd > 1_700_000_000L && massEnd < 2_000_000_000L) {
                        mod.put("end", massEnd);
                    }
                    if (coolEnd > 1_700_000_000L && coolEnd < 2_000_000_000L) {
                        mod.put("cool", coolEnd);
                    }
                    FnParams.saveModState(engine.appContext(), engine.lastFpid(),
                            engine.lastServerId(), "bear", mod);
                } catch (Throwable ignored) {
                }
                m.claimed(reserve > 0 ? bearText : "陷阱状态已同步");
            } else {
                int code = ExecUtil.codeOf(trap);
                m.skip(code == 2 || code == 7401 ? "无巨熊陷阱/未到活动" : "陷阱码" + code);
            }
        } catch (Exception e) {
            try {
                DailyRunner.debugLog(engine.appContext(), "bear fail: " + e);
            } catch (Throwable ignored) {
            }
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "巨熊行动");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("bear", "巨熊行动", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    /** 深扫找数字字段（嵌套 Map/List，depth<=4）。 */
    private static long deepLong(Object v, String k1, String k2, long def) {
        Long r = deepFind(v, k1, k2, 0);
        return r == null ? def : r;
    }

    private static Long deepFind(Object v, String k1, String k2, int depth) {
        if (depth > 4 || v == null) return null;
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            for (String k : new String[]{k1, k2}) {
                Object x = m.get(k);
                if (x instanceof Number) return ((Number) x).longValue();
            }
            for (Object x : m.values()) {
                Long r = deepFind(x, k1, k2, depth + 1);
                if (r != null) return r;
            }
        } else if (v instanceof java.util.List) {
            for (Object x : (java.util.List<?>) v) {
                Long r = deepFind(x, k1, k2, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------- 自动狩猎野怪 ----------

    static DailyResult hunt(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            // 守护盟友威胁期间让位：狩猎占行军队列，威胁窗口内不与卡秒驻防抢队
            if (com.carlos.vault.daily.guardian.GuardianEngine.threatBusyForRole(
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId())) {
                m.skip("守护盟友威胁执行中，本轮让位");
                DailyResult yd = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // P7 战时让位（E1/预警级）：狩猎是战时明令禁止的非战斗行为（§6.11.4）
            if (warBusy(engine)) {
                m.skip("战争态：队列全部留驻防，狩猎暂停");
                DailyResult yd = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
                yd.rewards = m.rewardsText();
                yd.subResults.putAll(m.subResults);
                return yd;
            }
            // 子开关门控：hunt=自动狩猎（本功能唯一动作；参数只管等级区间/次数）
            if (!FnParams.subOn(engine.appContext(),
                    engine.lastFpid() == null ? "" : engine.lastFpid(), engine.lastServerId(),
                    "hunt", "hunt", true)) {
                m.skip("子开关「自动狩猎」已关闭");
                m.subSkip("hunt", "子开关已关闭");
                DailyResult off = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
                off.rewards = m.rewardsText();
                off.subResults.putAll(m.subResults);
                return off;
            }
            engine.collectNotifies(2500);
            org.json.JSONObject hp = FnParams.params(engine.appContext(),
                    engine.lastFpid(), engine.lastServerId(), "hunt");
            int lvMin = Math.max(1, Math.min(10, hp.optInt("level_min", 4)));
            int lvMax = Math.max(lvMin, Math.min(10, hp.optInt("level_max", 6)));
            int maxTimes = Math.max(1, Math.min(20, hp.optInt("max_times", 5)));
            int hunted = 0;
            int noTarget = 0;
            for (int round = 0; round < maxTimes; round++) {
                Map<String, Object> target = null;
                long level = 0;
                for (int lv = lvMax; lv >= lvMin && target == null; lv--) {
                    target = searchMonster(engine, lv);
                    level = lv;
                }
                if (target == null) {
                    noTarget++;
                    break;
                }
                Map<String, Object> formation = ExecUtil.formation(engine, 1 + (round % 3));
                if (formation == null) {
                    m.skip("无英雄可派");
                    break;
                }
                long monsterId = ExecUtil.longOf(target.get("monsterid"));
                GameMessage march = engine.request("req_world_march", ExecUtil.args(
                        "type", 3L,
                        "endpoint", ExecUtil.point(ExecUtil.longOf(target.get("x")), ExecUtil.longOf(target.get("y"))),
                        "formation", formation,
                        "extra", ExecUtil.args("monsterid", monsterId)));
                if (ExecUtil.marchId(march) > 0) {
                    hunted++;
                } else {
                    int code = ExecUtil.codeOf(march);
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
                ExecUtil.sleep(500);
            }
            if (hunted > 0) {
                m.claimed("狩猎×" + hunted);
                mod(engine, "hunt", "hunt", "上轮狩猎 " + hunted + " 只（Lv" + lvMin + "-" + lvMax + "）");
            }
            else if (m.sb.length() == 0) m.skip("未找到可猎野怪");
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "自动狩猎");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        DailyResult rr = new DailyResult("hunt", "自动狩猎", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }

    private static Map<String, Object> searchMonster(WjdrEngine engine, int level) throws Exception {
        GameMessage rsp = engine.request("req_world_map_search", ExecUtil.args(
                "mapobj_type", 3L, "level_min", level, "level_max", level));
        if (!ExecUtil.ok(rsp)) return null;
        Map<String, Object> point = ExecUtil.mapOf(rsp.getContent().get("point"));
        if (point != null && point.containsKey("x") && point.containsKey("y")) return point;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : ExecUtil.mapsOf(rsp.getContent().get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    // ---------- 探险速刷（is_fast 快速战斗通道，2026-10-11 协议实证） ----------
    //
    // req_begin_adventure_pve{checkpoint, fight_heros_data, is_fast} →
    // req_end_adventure_pve{checkpoint, operatedata, enddata}。
    // 已通关(≤pass_checkpoint)的关卡走 is_fast 速刷无需战斗回放；未通关的新关
    // 需要客户端战斗模拟，enddata 形状未实证——遇到即停并提示手动解锁一次。

    static DailyResult adventureSweep(WjdrEngine engine) {
        ExecUtil.Multi m = new ExecUtil.Multi();
        try {
            engine.collectNotifies(1500);
            android.content.Context sctx = engine.appContext();
            String sfpid = engine.lastFpid() == null ? "" : engine.lastFpid();
            long ssidx = engine.lastServerId();
            org.json.JSONObject sp = FnParams.params(sctx, sfpid, ssidx, "adventure_sweep");
            org.json.JSONObject strat = sp.optJSONObject("strategy");
            int maxSweep = strat != null && strat.has("sweep") ? strat.optInt("sweep", 5) : 5;
            maxSweep = Math.max(1, Math.min(50, maxSweep));

            Map<String, Object> pve = engine.notifyData("notify_adventure_pve_info");
            long passed = TownTasks.numIn(pve, "pass_checkpoint");
            if (passed <= 0) {
                m.skip("探险进度未同步（先手动进一次探险）");
                m.subSkip("sweep", "进度未同步");
                return sweepResult(m);
            }
            List<Long> heroes = ExecUtil.clientSquads(engine).values().stream().findFirst()
                    .orElseGet(() -> ExecUtil.allHeroes(engine).stream().limit(5)
                            .collect(java.util.stream.Collectors.toList()));
            if (heroes.isEmpty()) {
                m.skip("无可用英雄编队");
                m.subSkip("sweep", "无编队");
                return sweepResult(m);
            }
            int swept = 0;
            for (int i = 0; i < maxSweep; i++) {
                long next = passed + 1;
                GameMessage begin = engine.request("req_begin_adventure_pve", ExecUtil.args(
                        "checkpoint", next, "fight_heros_data", heroes, "is_fast", true));
                if (!ExecUtil.ok(begin)) {
                    int code = ExecUtil.codeOf(begin);
                    m.skip("第" + next + "关无法速刷(码" + code + ")"
                            + (code != 0 ? "" : ""));
                    break;
                }
                GameMessage end = engine.request("req_end_adventure_pve", ExecUtil.args(
                        "checkpoint", next, "operatedata", new java.util.LinkedHashMap<>(),
                        "enddata", new ArrayList<>()));
                if (ExecUtil.ok(end)) {
                    swept++;
                    passed = next;
                    m.claimed("速刷第" + next + "关");
                } else {
                    int code = ExecUtil.codeOf(end);
                    m.skip("第" + next + "关未确认(码" + code + ")——新关卡需手动战斗解锁一次");
                    break;
                }
            }
            if (swept > 0) {
                m.subOk("sweep", "速刷×" + swept + "（推进到第" + passed + "关）");
                mod(engine, "adventure_sweep", "sweep", "上轮速刷 " + swept + " 关 → 第 " + passed + " 关");
            }
        } catch (Exception e) {
            if (TownTasks.sessionLost(e)) return DailyTasks.classifyException(e, "探险速刷");
            m.fail(DailyTasks.brief(String.valueOf(e.getMessage())));
        }
        return sweepResult(m);
    }

    private static DailyResult sweepResult(ExecUtil.Multi m) {
        DailyResult rr = new DailyResult("adventure_sweep", "探险速刷", m.cat(), m.note() + " " + m.sb);
        rr.rewards = m.rewardsText();
        rr.subResults.putAll(m.subResults);
        return rr;
    }
}
