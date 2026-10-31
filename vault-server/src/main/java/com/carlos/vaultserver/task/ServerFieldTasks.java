package com.carlos.vaultserver.task;

import com.carlos.vault.daily.gather.GatherConfigV2;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.MarchCapacityCalculator;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.guardian.GuardianReserve;
import com.carlos.vaultserver.formation.FormationLibrary;
import com.carlos.vaultserver.formation.HeroAvailability;
import com.carlos.vaultserver.formation.FormationResolver;
import com.carlos.vaultserver.formation.IntelFormationPlan;
import com.carlos.vaultserver.formation.ResolvedFormation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 野外类 + 城建类任务（App 端 daily/FieldTasks + TownDevTasks 逐行移植，改动须两侧同步）。
 */
public final class ServerFieldTasks {

    private ServerFieldTasks() {
    }

    // ---------- 野外采集 ----------

    /**
     * 野外采集入口（I2，2026-10-24）：
     * <ul>
     *   <li>{@code tc.params("gather").v2} 存在 → 队列化执行 {@link #gatherV2}（6 队列 × 资源/等级/编队）；</li>
     *   <li>无 v2（旧 App / 存量账号）→ {@link #gatherLegacy}，与改造前逐字节一致（零行为变化）。</li>
     * </ul>
     */
    public static TaskOutcome gather(TaskContext tc) {
        // 批次 J（2026-10-31 用户长期规则第 3 条）：守护战争态直接停派采集——6 个行军队列
        // 全部留给对盟友驻防；退出战争态（无在途威胁 60s）后本闸自动放行，恢复正常采集。
        if (GuardianReserve.gatherBlocked(tc)) {
            return new TaskOutcome("gather", "野外采集", TaskOutcome.Category.SKIPPED,
                    "战争态停派采集（队列全部留给守护驻防）");
        }
        JSONObject gatherParams = null;
        try {
            gatherParams = tc.params("gather");
        } catch (Throwable ignored) {
        }
        // 诊断开关：VAULT_LEGACY_GATHER=on 时强制走旧路径（用于 301/编队对照排查）
        String legacyForce = System.getenv("VAULT_LEGACY_GATHER");
        if (legacyForce != null && ("on".equalsIgnoreCase(legacyForce.trim())
                || "1".equals(legacyForce.trim()) || "true".equalsIgnoreCase(legacyForce.trim()))) {
            return gatherLegacy(tc);
        }
        if (!GatherConfigV2.hasV2(gatherParams)) return gatherLegacy(tc);
        return gatherV2(tc, gatherParams);
    }

    /** 旧采集分支（无 v2 时逐字节保留；v2 队列化见 {@link #gatherV2}）。 */
    static TaskOutcome gatherLegacy(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        MarchSnapshot marchSnapshot = null;   // G2：catch 兜底出口也要写续派唤醒
        try {
            tc.engine.collectNotifies(2500);
            JSONObject p = tc.params("gather");
            int minLevel = Math.max(1, Math.min(6, p.optInt("min_level", 5)));
            // 批次 E：守护实盘 → 采集让出 reserve_slots 队（默认 1，clamp 0..2，至少保留 1 队采集）
            int guardReserve = GuardianReserve.reserveSlots(tc);
            int rawMaxQueues = Math.max(1, Math.min(5, p.optInt("max_queues", 3)));
            int maxQueues = GuardianReserve.effectiveMaxQueues(rawMaxQueues, guardReserve);
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
            // G2 续派：一次查询同时取“各类型行军数 + type3/4 最早结束时刻”，
            // 不新增协议请求（与旧 countMarches 同一 req_world_march_detail_4_player_situation）。
            marchSnapshot = snapshotMarches(tc.engine);
            int active = marchSnapshot.count(4);
            int worldCapacity = marchCapacity(tc.engine);
            int capacity = Math.min(maxQueues, worldCapacity);
            boolean fullOnly = "full".equals(tc.strategy("gather", "send", "any"));
            // G3 联盟矿（默认关）：开启时按网页版为联盟矿预留 1 个世界队列
            // （DailyGatherService.regularQueueCapacity = capacity - allianceMineReserved，
            //   :1181-1313 联盟矿/普通采集双池）。默认关=行为与旧实现完全一致。
            boolean allianceMine = gatherAllianceMineEnabled(p);
            boolean mineReserved = allianceMine && marchSnapshot.count(GATHER_MINE_MARCH_TYPE) > 0;
            if (allianceMine) {
                AllianceMineOutcome mine = maintainAllianceMine(tc, marchSnapshot, worldCapacity);
                if (mine.dispatched) {
                    m.claimed("联盟矿已派出");
                    m.subOk("alliance_mine", mine.message);
                    mineReserved = true;            // 联盟矿占 1 个世界出征队列
                    active += 1;
                } else {
                    m.subSkip("alliance_mine", mine.message);
                }
                tc.saveModText("gather", "alliance_mine", mine.message);
            }
            int regularCapacity = Math.max(0, capacity - (mineReserved ? 1 : 0));
            int slots = Math.max(0, regularCapacity - active);
            if (slots == 0) {
                m.skip("采集队列已满(" + active + "/" + capacity + ")"
                        + (guardReserve > 0 ? "（为守护预留 " + guardReserve + " 队）" : ""));
            } else {
                int sent = 0;
                for (int res : resList) {
                    if (sent >= slots) break;
                    for (int level = Math.min(6, minLevel); level >= 1 && sent < slots; level--) {
                        Map<String, Object> pt = searchResource(tc.engine, res, level, fullOnly);
                        if (pt == null) continue;
                        StringBuilder formationWhy = new StringBuilder();
                        Map<String, Object> formation = formationFor(tc, "gather", 1 + (sent % 3), formationWhy);
                        if (formation == null) {
                            m.skip(formationWhy.length() > 0 ? "编队跳过：" + formationWhy : "无英雄可派");
                            return finishGather(tc, m, marchSnapshot);
                        }
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", 4L, "endpoint", pt, "formation", formation,
                                "extra", new java.util.LinkedHashMap<>()));
                        if (Exec.marchId(march) > 0) {
                            sent++;
                            // P0-5：成功出征复位 415 英雄上限/406 兵力缩放（自适应必须能恢复）
                            HeroAvailability.markSuccess(tc.engine);
                        } else {
                            int code = Exec.codeOf(march);
                            if (code == 2878 || code == 401) {
                                m.skip("派出" + sent + "后受阻(码" + code + ")");
                                return finishGather(tc, m, marchSnapshot);
                            }
                            if (code == GATHER_ERR_HERO_AWAY) {
                                blockHeroesOn301(tc.engine, formation);   // 301：拉黑英雄，继续下一个候选
                            }
                        }
                    }
                }
                if (sent > 0) {
                    m.claimed("派出采集×" + sent);
                    tc.saveModText("gather", "send",
                            "上轮派出 " + sent + " 队（队列 " + active + "/" + capacity + "）"
                                    + (guardReserve > 0 ? "；为守护预留 " + guardReserve + " 队" : ""));
                } else m.skip("附近未搜到资源点");
            }
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "野外采集");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return finishGather(tc, m, marchSnapshot);
    }

    /**
     * 统一编队解析（批次10，1:1 参考网页版 DailyFormationConfigSupport/DailyMarchFormationResolver）：
     * <ul>
     *   <li><b>无编队参数</b>（旧 App / 未配置）→ 返回 {@link #legacyFormationFor}（旧行为零变化）；</li>
     *   <li>配置了 {@code formation_mode/formation_id/soldiers/ratio/total} → {@link FormationResolver}
     *       （ROLE=功能页编队；AUTOMATIC=自动英雄+比例），无法解析返回 null 并由调用方 SKIP；</li>
     *   <li>日志/mod 写「编队 来源=… 英雄=… 兵种=…」或 SKIP 原因。</li>
     * </ul>
     *
     * @param reasonOut 非 null 时写入 SKIP 原因（调用方拼进任务 reason）
     */
    static Map<String, Object> formationFor(TaskContext tc, String fnId, int fallbackSquad,
                                            StringBuilder reasonOut) {
        try {
            JSONObject p = tc.params(fnId);
            if (!FormationResolver.isConfigured(p)) {
                return legacyFormationFor(tc, fnId, fallbackSquad);   // 无参数=旧行为
            }
            FormationLibrary lib = FormationResolver.libraryFor(tc, fnId);
            FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(tc.engine, lib, p);
            if (!ro.ready()) {
                if (reasonOut != null) reasonOut.append(ro.reason);
                FileLog.info("编队解析跳过 fpid={} fn={}：{}", tc.fpid, fnId, ro.reason);
                tc.saveModText(fnId, "formation", "编队跳过：" + ro.reason);
                return null;
            }
            ResolvedFormation rf = ro.formation;
            tc.saveModText(fnId, "formation", rf.describe() + " · " + ro.reason);
            FileLog.info("编队 fpid={} fn={} {}", tc.fpid, fnId, rf.describe());
            return rf.toFormationPayload();
        } catch (Throwable t) {
            if (reasonOut != null) reasonOut.append("编队异常：").append(ServerTasks.brief(String.valueOf(t.getMessage())));
            FileLog.warn("编队解析异常 fpid={} fn={}：{}", tc.fpid, fnId, String.valueOf(t));
            return null;
        }
    }

    /** 兼容旧调用（无 SKIP 原因）。 */
    static Map<String, Object> formationFor(TaskContext tc, String fnId, int fallbackSquad) {
        return formationFor(tc, fnId, fallbackSquad, null);
    }

    /**
     * 旧编队逻辑（无编队参数时保持逐字节行为不变）：配置了 hero_ids 时按“英雄 id + 动态兵力”构造；
     * 否则回退旧 Exec.formation（按 squad_index/fallbackSquad 轮换）。
     *
     * <p>字段（存于 daily_params 的 fnId 参数）：
     * hero_ids:[id1,id2,id3]、troop_count、full_troops、
     * shield_ratio/spear_ratio/bow_ratio。
     * 采集/打野兽共用同一套，避免两处算法漂移。
     */
    static Map<String, Object> legacyFormationFor(TaskContext tc, String fnId, int fallbackSquad) {
        org.json.JSONObject p = tc.params(fnId);
        // App 采集弹窗的“编队”选择：直接使用游戏内第 N 队布阵。
        int squad = p.optInt("squad_index", 0);
        if (squad > 0) return Exec.formation(tc.engine, squad);
        org.json.JSONArray heroArr = p.optJSONArray("hero_ids");
        if (heroArr == null || heroArr.length() == 0) {
            return Exec.formation(tc.engine, fallbackSquad);
        }
        List<Long> heroIds = new ArrayList<>();
        for (int i = 0; i < heroArr.length(); i++) {
            long hid = heroArr.optLong(i, 0);
            if (hid > 0) heroIds.add(hid);
        }
        List<Map<String, Object>> heroPairs = Exec.heroPairs(heroIds);
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
            if (want[i] > 0) soldierPairs.add(Exec.soldierPair(ids[i], want[i]));
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

    // ---------- G2 采集续派 + G3 联盟矿（P1 2026-10-23） ----------

    /** 续派唤醒上限/兜底：min(now+180s, 最早结束-1s)；取不到 endtime → now+120s
     *  （1:1 网页版 DailyGatherService.nextMarchTransitionAt:1060-1074 + queueWaitUntil 180s 封顶）。 */
    static final long GATHER_WAKE_CAP_MILLIS = 180_000L;
    static final long GATHER_WAKE_FALLBACK_MILLIS = 120_000L;

    /** 联盟矿失败冷却：默认关的功能，失败后 10 分钟再试，避免 15s 节奏刷请求（G3“失败不重试风暴”）。 */
    static final long GATHER_MINE_RETRY_MILLIS = 600_000L;
    static final long GATHER_MINE_BUILDING_TYPE = 39L;
    static final long GATHER_MINE_MARCH_TYPE = 110L;

    /** 行军快照：各类型计数 + type3/4 最早结束时刻（毫秒）。known=false 表示查询失败。 */
    static final class MarchSnapshot {
        private final Map<Long, Integer> countsByType;
        private final long earliestMarchEndMillis;
        private final boolean known;

        MarchSnapshot(Map<Long, Integer> countsByType, long earliestMarchEndMillis, boolean known) {
            this.countsByType = countsByType == null ? new LinkedHashMap<>() : countsByType;
            this.earliestMarchEndMillis = Math.max(0L, earliestMarchEndMillis);
            this.known = known;
        }

        static MarchSnapshot unknown() {
            return new MarchSnapshot(new LinkedHashMap<>(), 0L, false);
        }

        int count(long type) {
            Integer n = countsByType.get(type);
            return n == null ? 0 : n;
        }

        int totalActive() {
            int n = 0;
            for (Integer v : countsByType.values()) if (v != null) n += v;
            return n;
        }

        long earliestMarchEndMillis() {
            return earliestMarchEndMillis;
        }

        boolean known() {
            return known;
        }
    }

    /**
     * G2：与旧 countMarches 同一个 {@code req_world_march_detail_4_player_situation} 请求
     * （不新增查询协议），一次解析出“各类型行军数 + type3/4 最早结束时刻”。
     * 协议实证见旧注释：owner=玩家uid、kid=区号，行军列表在响应 detail 字段。
     */
    static MarchSnapshot snapshotMarches(WjdrEngine engine) {
        // 资产库优先（登录 world_player_init + player/mass_march 推送维护；type 计数与最早结束时刻）；
        // 生产旧 detail 查询恒 code=2，仅作未接线/回滚兜底。
        if (assetReadsEnabled()) {
            try {
                CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
                if (lib != null && lib.ready() && lib.queue().known()) {
                    return marchSnapshotFromAsset(lib.queue());
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return MarchSnapshot.unknown();
            Object code = rsp.getContent().get("code");
            if (code != null && Exec.longOf(code) != 0) return MarchSnapshot.unknown();
            List<Map<String, Object>> marches = marchDetailList(rsp.getContent());
            if (marches == null) return MarchSnapshot.unknown();
            Map<Long, Integer> counts = new LinkedHashMap<>();
            for (Map<String, Object> march : marches) {
                long type = Exec.longOf(march.get("type"));
                counts.merge(type, 1, Integer::sum);
            }
            return new MarchSnapshot(counts, gatherEarliestMarchEndMillis(marches), true);
        } catch (Throwable ignored) {
            return MarchSnapshot.unknown();
        }
    }

    /** 行军列表解析（纯函数）：无 detail=未知(null)；有 detail 无列表=已知为空。
     *  兼容 marchs/march_list/list/marches 形状。 */
    static List<Map<String, Object>> marchDetailList(Map<String, Object> content) {
        if (content == null) return null;
        Object code = content.get("code");
        if (code != null && Exec.longOf(code) != 0) return null;
        Map<String, Object> detail = Exec.mapOf(content.get("detail"));
        if (detail == null) return null;
        for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
            List<Map<String, Object>> list = Exec.mapsOf(detail.get(k));
            if (!list.isEmpty()) return list;
        }
        return List.of();
    }

    /** 行军结束时刻（秒/毫秒兼容）：endtime/end_time/endAt/end_at/end。 */
    static long gatherMarchEndMillis(Map<String, Object> march) {
        if (march == null) return 0L;
        for (String k : new String[]{"endtime", "end_time", "endAt", "end_at", "end"}) {
            Object v = march.get(k);
            if (v == null) continue;
            long t = Exec.longOf(v);
            if (t <= 0L) continue;
            return t >= 100_000_000_000L ? t : t * 1000L;   // >=1e11 视为毫秒（秒级 epoch 约 1.8e9）
        }
        return 0L;
    }

    /** type=3/4 行军的最早结束毫秒（0=取不到）。 */
    static long gatherEarliestMarchEndMillis(List<Map<String, Object>> marches) {
        long earliest = 0L;
        if (marches == null) return 0L;
        for (Map<String, Object> march : marches) {
            long type = Exec.longOf(march.get("type"));
            if (type != 3L && type != 4L) continue;
            long end = gatherMarchEndMillis(march);
            if (end <= 0L) continue;
            if (earliest == 0L || end < earliest) earliest = end;
        }
        return earliest;
    }

    /** 续派唤醒：min(now+180s, earliestEnd-1s)；取不到 → now+120s（过期给 1s 兜底）。 */
    static long gatherNextWakeMillis(long nowMillis, long earliestMarchEndMillis) {
        if (earliestMarchEndMillis <= 0L) return nowMillis + GATHER_WAKE_FALLBACK_MILLIS;
        long wake = earliestMarchEndMillis - 1000L;
        if (wake <= nowMillis) wake = nowMillis + 1000L;
        return Math.min(nowMillis + GATHER_WAKE_CAP_MILLIS, wake);
    }

    /** 写 TaskCadenceScheduler 同键 {@code srv:cad:{fpid}:{sid}:gather}（毫秒）。 */
    static void writeGatherWake(TaskContext tc, long wakeAtMillis) {
        try {
            tc.store.putLong(gatherWakeKey(tc), Math.max(1L, wakeAtMillis));
        } catch (Throwable ignored) {
        }
    }

    static String gatherWakeKey(TaskContext tc) {
        return "srv:cad:" + tc.fpid + ":" + tc.sid + ":gather";
    }

    /** 采集任何出口（派出/跳过/异常）都先写续派唤醒，再出结果。 */
    private static TaskOutcome finishGather(TaskContext tc, Exec.Multi m, MarchSnapshot snapshot) {
        long earliest = snapshot == null ? 0L : snapshot.earliestMarchEndMillis();
        writeGatherWake(tc, gatherNextWakeMillis(System.currentTimeMillis(), earliest));
        return Exec.result(m, "gather", "野外采集");
    }

    /** G3 联盟矿开关（默认关；params.alliance_mine=true 才做）。 */
    static boolean gatherAllianceMineEnabled(JSONObject gatherParams) {
        return gatherParams != null && gatherParams.optBoolean("alliance_mine", false);
    }

    static final class AllianceMineOutcome {
        final boolean dispatched;
        final String message;

        AllianceMineOutcome(boolean dispatched, String message) {
            this.dispatched = dispatched;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * G3：联盟矿（默认关）——{@code req_alliance_building_list} type=39 →
     * {@code req_world_mapobj} 二次校验 → {@code req_world_march type=110}。
     * 一次运行最多派 1 队；失败走 10 分钟冷却台账，避免节奏调度刷请求。
     * 参考网页版 DailyGatherService.maintainAllianceMine:1181-1313 +
     * findAllianceMineTargets:1316-1354 + allianceMineMarchPayload:1652-1654。
     */
    private static AllianceMineOutcome maintainAllianceMine(TaskContext tc, MarchSnapshot snapshot,
                                                            int worldCapacity) {
        return maintainAllianceMine(tc, snapshot, worldCapacity, null);
    }

    /** B2-T5：v2 联盟矿（编队参数可覆盖；null=legacy 功能级编队，行为不变）。 */
    private static AllianceMineOutcome maintainAllianceMine(TaskContext tc, MarchSnapshot snapshot,
                                                            int worldCapacity,
                                                            JSONObject formationParamsOverride) {
        long now = System.currentTimeMillis();
        if (snapshot != null && snapshot.count(GATHER_MINE_MARCH_TYPE) > 0) {
            return new AllianceMineOutcome(false, "联盟矿采集中，占用 1 个世界出征队列");
        }
        if (snapshot == null || !snapshot.known()) {
            return new AllianceMineOutcome(false, "行军数据未同步，本轮跳过联盟矿");
        }
        if (snapshot.totalActive() >= worldCapacity) {
            return new AllianceMineOutcome(false, "无空闲世界出征队列，联盟矿等待");
        }
        if (!gatherMineRetryDue(tc, now)) {
            return new AllianceMineOutcome(false, "联盟矿失败冷却中，稍后重试");
        }
        try {
            GameMessage buildings = tc.engine.request("req_alliance_building_list", Exec.args());
            if (!Exec.ok(buildings)) {
                markGatherMineRetry(tc, now);
                return new AllianceMineOutcome(false, "联盟建筑列表码" + Exec.codeOf(buildings));
            }
            long nowSeconds = now / 1000L;
            List<Map<String, Object>> targets = allianceMineTargets(buildings.getContent(), nowSeconds);
            if (targets.isEmpty()) {
                markGatherMineRetry(tc, now);
                return new AllianceMineOutcome(false, "联盟尚无可采资源中心");
            }
            Map<String, Object> target = targets.get(0);
            long x = Exec.longOf(target.get("x"));
            long y = Exec.longOf(target.get("y"));
            GameMessage mapobj = tc.engine.request("req_world_mapobj", Exec.args(
                    "kid", tc.engine.lastServerId(), "point", Exec.point(x, y)));
            if (!Exec.ok(mapobj)
                    || !allianceMineMapobjOk(mapobj.getContent(), gatherAllianceId(tc.engine), nowSeconds)) {
                markGatherMineRetry(tc, now);
                return new AllianceMineOutcome(false, "联盟资源中心已失效或不属于本盟");
            }
            Map<String, Object> formation;
            if (formationParamsOverride != null) {
                FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(tc.engine,
                        FormationResolver.libraryFor(tc, "gather"), formationParamsOverride);
                if (!ro.ready()) {
                    return new AllianceMineOutcome(false, "联盟矿编队跳过：" + ro.reason);
                }
                formation = ro.formation.toFormationPayload();
            } else {
                StringBuilder formationWhy = new StringBuilder();
                formation = formationFor(tc, "gather", 1, formationWhy);
                if (formation == null) {
                    return new AllianceMineOutcome(false, formationWhy.length() > 0
                            ? "联盟矿编队跳过：" + formationWhy : "联盟矿无英雄可派");
                }
            }
            GameMessage march = tc.engine.request("req_world_march", Exec.args(
                    "type", GATHER_MINE_MARCH_TYPE,
                    "endpoint", Exec.point(x, y),
                    "formation", formation,
                    "extra", new LinkedHashMap<>()));
            if (Exec.marchId(march) > 0L) {
                tc.store.putLong(gatherMineRetryKey(tc), 0L);
                return new AllianceMineOutcome(true, "已派出联盟矿采集");
            }
            int code = Exec.codeOf(march);
            markGatherMineRetry(tc, now);
            return new AllianceMineOutcome(false, "联盟矿出征码" + code);
        } catch (Exception e) {
            markGatherMineRetry(tc, now);
            return new AllianceMineOutcome(false, "联盟矿异常：" + ServerTasks.brief(String.valueOf(e.getMessage())));
        }
    }

    /** 资产库队列 → MarchSnapshot（类型计数 + type4/110 最早结束毫秒；未 know 返回 unknown）。 */
    static MarchSnapshot marchSnapshotFromAsset(AssetSnapshot.Queue queue) {
        if (queue == null || !queue.known()) return MarchSnapshot.unknown();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        long earliest = 0L;
        for (AssetSnapshot.QueueMarch march : queue.marches) {
            if (march == null) continue;
            counts.merge(march.type, 1, Integer::sum);
            if (march.type == GATHER_MARCH_TYPE || march.type == GATHER_MINE_MARCH_TYPE) {
                long end = queueMarchEndMillis(march);
                if (end > 0L && (earliest == 0L || end < earliest)) earliest = end;
            }
        }
        return new MarchSnapshot(counts, earliest, true);
    }

    /** v2 联盟矿编队参数（与 queueFormationParams 同口径）。 */
    static JSONObject allianceMineFormationParams(GatherConfigV2 cfg) {
        JSONObject f = new JSONObject();
        if (cfg == null) return f;
        String mode = GatherConfigV2.normalizeMode(cfg.allianceMineFormationMode);
        long total = cfg.allianceMineInfantry + cfg.allianceMineLancer + cfg.allianceMineMarksman;
        if (GatherConfigV2.MODE_ROLE.equals(mode)) {
            f.put("formation_mode", GatherConfigV2.MODE_ROLE);
            f.put("formation_id", cfg.allianceMineFormationId);
            JSONObject soldiers = new JSONObject();
            soldiers.put("infantry", Math.max(0L, cfg.allianceMineInfantry));
            soldiers.put("lancer", Math.max(0L, cfg.allianceMineLancer));
            soldiers.put("marksman", Math.max(0L, cfg.allianceMineMarksman));
            f.put("soldiers", soldiers);
            return f;
        }
        f.put("formation_mode", GatherConfigV2.MODE_AUTOMATIC);
        JSONObject ratio = new JSONObject();
        if (total > 0L) {
            ratio.put("infantry", Math.max(0L, cfg.allianceMineInfantry));
            ratio.put("lancer", Math.max(0L, cfg.allianceMineLancer));
            ratio.put("marksman", Math.max(0L, cfg.allianceMineMarksman));
        } else {
            ratio.put("infantry", 40L);
            ratio.put("lancer", 30L);
            ratio.put("marksman", 30L);
        }
        f.put("ratio", ratio);
        f.put("total", total);
        return f;
    }

    /** 联盟建筑列表 → type=39 资源中心坐标（纯函数；resource_info.end_time 已过期的丢弃）。 */
    static List<Map<String, Object>> allianceMineTargets(Map<String, Object> content, long nowSeconds) {
        if (content == null) return List.of();
        List<Map<String, Object>> buildings = new ArrayList<>();
        for (Map<String, Object> group : Exec.mapsOf(content.get("building_type_2_list"))) {
            if (Exec.longOf(group.get("mapobj_type")) != GATHER_MINE_BUILDING_TYPE) continue;
            buildings.addAll(Exec.mapsOf(group.get("building_list")));
        }
        buildings.addAll(Exec.mapsOf(content.get("building_list")));
        List<Map<String, Object>> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Map<String, Object> b : buildings) {
            Object type = b.get("mapobj_type");
            if (type != null && Exec.longOf(type) > 0L && Exec.longOf(type) != GATHER_MINE_BUILDING_TYPE) continue;
            long x = Exec.longOf(b.get("x"));
            long y = Exec.longOf(b.get("y"));
            if (x <= 0L || y <= 0L) continue;
            long endAt = allianceMineResourceEndAt(b);
            if (endAt > 0L && endAt <= nowSeconds) continue;
            if (seen.add(x + "," + y)) out.add(b);
        }
        return out;
    }

    static long allianceMineResourceEndAt(Map<String, Object> building) {
        if (building == null) return 0L;
        Map<String, Object> res = Exec.mapOf(building.get("resource_info"));
        if (res != null) {
            long end = Exec.longOf(res.get("end_time"));
            if (end > 0L) return end;
        }
        for (String k : new String[]{"end_time", "resource_endtime"}) {
            long end = Exec.longOf(building.get(k));
            if (end > 0L) return end;
        }
        return 0L;
    }

    /** mapobj 二次校验（纯函数）：type=39 + map_alliance_resource 存在 + 未到期 + 归属当前联盟。 */
    static boolean allianceMineMapobjOk(Map<String, Object> content, long allianceId, long nowSeconds) {
        Map<String, Object> mapObj = Exec.mapOf(content == null ? null : content.get("mapobj"));
        if (mapObj == null || Exec.longOf(mapObj.get("type")) != GATHER_MINE_BUILDING_TYPE) return false;
        Map<String, Object> res = Exec.mapOf(mapObj.get("map_alliance_resource"));
        if (res == null || res.isEmpty()) return false;
        long endAt = Exec.longOf(res.get("resource_endtime"));
        if (endAt > 0L && endAt <= nowSeconds) return false;
        if (allianceId > 0L) {
            long aid = Exec.longOf(res.get("aid"));
            if (aid > 0L && aid != allianceId) return false;
        }
        return true;
    }

    /** 当前联盟 id（notify_alliance_init / notify_world_alliance_situation_init；形状兼容）。取不到 0。 */
    static long gatherAllianceId(WjdrEngine engine) {
        Long id = gatherAllianceIdOf(engine.notifyData("notify_alliance_init"));
        if (id == null) id = gatherAllianceIdOf(engine.notifyData("notify_world_alliance_situation_init"));
        return id == null ? 0L : id;
    }

    static Long gatherAllianceIdOf(Map<String, Object> data) {
        if (data == null) return null;
        Long direct = positiveAllianceId(data);
        if (direct != null) return direct;
        for (String k : new String[]{"alliance", "info", "alliance_info", "data"}) {
            Long nested = positiveAllianceId(Exec.mapOf(data.get(k)));
            if (nested != null) return nested;
        }
        return null;
    }

    private static Long positiveAllianceId(Map<String, Object> m) {
        if (m == null) return null;
        for (String k : new String[]{"id", "alliance_id", "aid"}) {
            long v = Exec.longOf(m.get(k));
            if (v > 0L) return v;
        }
        return null;
    }

    static boolean gatherMineRetryDue(TaskContext tc, long nowMillis) {
        long retryAt = stateLongOf(tc, gatherMineRetryKey(tc));
        return retryAt <= 0L || nowMillis >= retryAt;
    }

    private static void markGatherMineRetry(TaskContext tc, long nowMillis) {
        try {
            tc.store.putLong(gatherMineRetryKey(tc), nowMillis + GATHER_MINE_RETRY_MILLIS);
        } catch (Throwable ignored) {
        }
    }

    static String gatherMineRetryKey(TaskContext tc) {
        return "srv:gather:mine:retry:" + tc.fpid + ":" + tc.sid;
    }

    static long stateLongOf(TaskContext tc, String key) {
        Object v = tc.store.snapshot().get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    /**
     * 世界出征容量（1:1 网页版 MarchCapacityState.worldMarchCapacity）：
     * 资产库优先 → 登录批通知公式现算 → 旧字段（错误但保留兜底）→ 3。
     *
     * <p>公式：1 + Σ科技{1007,1020,1027} + (VIP≥6 且未过期) + Σbuff[10505] + extra_march，
     * 只读层 clamp 1..6。旧实现读 {@code notify_world_player_init.world.capacity}（协议无此字段，恒 3）。
     */
    static int marchCapacity(WjdrEngine engine) {
        int fromAsset = assetMarchCapacity(engine);
        if (fromAsset > 0) return fromAsset;
        int computed = marchCapacityFromNotify(engine);
        if (computed > 0) return computed;
        Map<String, Object> wp = engine.notifyData("notify_world_player_init");
        if (wp == null) return 3;
        Map<String, Object> world = Exec.mapOf(wp.get("world"));
        long cap = world == null ? 0 : Exec.longOf(world.get("capacity"));
        return cap > 0 ? (int) Math.min(cap, 6L) : 3;
    }

    /** 资产库容量（未接线/未 READY/回滚开关 off/未知 → 0）。 */
    static int assetMarchCapacity(WjdrEngine engine) {
        if (engine == null || !assetReadsEnabled()) return 0;
        try {
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            if (lib == null || !lib.ready()) return 0;
            AssetSnapshot.Queue q = lib.queue();
            return q != null && q.capacity > 0 ? q.capacity : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 资产库读侧回滚开关（默认 on；{@code VAULT_ASSET_LIBRARY=off} 回退旧读法）。 */
    static boolean assetReadsEnabled() {
        try {
            String v = System.getenv("VAULT_ASSET_LIBRARY");
            return v == null || !"off".equalsIgnoreCase(v.trim());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 公式兜底：从登录批通知现算容量；科技/世界 init 任一缺失 → 0（未知，不猜）。 */
    static int marchCapacityFromNotify(WjdrEngine engine) {
        if (engine == null) return 0;
        try {
            Map<String, Object> wpi = engine.notifyData("notify_world_player_init");
            Map<String, Object> research = engine.notifyData("notify_all_research");
            if (wpi == null || research == null) return 0;
            long nowSec;
            try {
                com.carlos.vault.wjdr.GameServerClock ck = engine.serverClock();
                nowSec = ck != null && ck.locked() ? ck.nowServerSec()
                        : System.currentTimeMillis() / 1000L;
            } catch (Throwable t) {
                nowSec = System.currentTimeMillis() / 1000L;
            }
            return marchCapacityFromNotifies(wpi, research, engine.notifyData("notify_vip_info"),
                    engine.notifyData("notify_buff_show"), nowSec);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 纯函数：容量公式（科技 1007/1020/1027 + VIP≥6 + buff10505 + extra_march，clamp 1..6）。 */
    static int marchCapacityFromNotifies(Map<String, Object> wpi, Map<String, Object> research,
                                         Map<String, Object> vip, Map<String, Object> buffShow,
                                         long nowSec) {
        if (wpi == null || research == null) return 0;
        int tech = researchQueueTechCount(research);
        boolean vip6 = vipQueueActive(vip, nowSec);
        int buffQueues = buffQueueCount(buffShow, nowSec);
        long extra = extraMarchCount(wpi);
        int raw = com.carlos.vault.wjdr.asset.QueueCapacity.rawCapacity(tech, vip6, buffQueues, extra);
        return com.carlos.vault.wjdr.asset.QueueCapacity.clampCapacity(raw);
    }

    /** 队列科技数：researched 中 tid∈{1007,1020,1027} 且 lev>0 的个数（map "tid.0" / list / {tid,lev} 兼容）。 */
    static int researchQueueTechCount(Map<String, Object> researchNotify) {
        Map<Long, Long> levels = new LinkedHashMap<>();
        Object researched = researchNotify == null ? null : researchNotify.get("researched");
        if (researched == null && researchNotify != null) researched = researchNotify.get("research");
        if (researched instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) researched).entrySet()) {
                long tid = queueTechNumericId(e.getKey());
                long lev;
                if (e.getValue() instanceof Map) {
                    Map<String, Object> row = Exec.mapOf(e.getValue());
                    if (tid <= 0L) tid = Exec.longOf(row.get("tid"));
                    lev = Exec.longOf(row.get("lev"));
                } else {
                    lev = Exec.longOf(e.getValue());
                }
                if (tid > 0L && lev > 0L) levels.put(tid, lev);
            }
        } else {
            for (Map<String, Object> row : Exec.mapsOf(researched)) {
                long tid = Exec.longOf(row.get("tid"));
                long lev = Exec.longOf(row.get("lev"));
                if (tid > 0L && lev > 0L) levels.put(tid, lev);
            }
        }
        int n = 0;
        for (long tid : com.carlos.vault.wjdr.asset.QueueCapacity.TECH_IDS) {
            Long lev = levels.get(tid);
            if (lev != null && lev > 0L) n++;
        }
        return n;
    }

    /** "1020.0" 这类键的 id 解析（字符串/数字兼容）。 */
    static long queueTechNumericId(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0L;
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** VIP 队列加成：vip_lv≥6 且 vip_active_endtime>now（网页同口径）。 */
    static boolean vipQueueActive(Map<String, Object> vip, long nowSec) {
        if (vip == null) return false;
        long lv = Exec.longOf(vip.get("vip_lv"));
        long end = Exec.longOf(vip.get("vip_active_endtime"));
        return lv >= com.carlos.vault.wjdr.asset.QueueCapacity.VIP_QUEUE_LEVEL && end > nowSec;
    }

    /** 临时队列 buff：buff_map[10505] 未过期项求和（map "10505.0" / list 兼容）。 */
    static int buffQueueCount(Map<String, Object> buffShow, long nowSec) {
        if (buffShow == null) return 0;
        Object raw = buffShow.get("map");
        if (raw == null) raw = buffShow.get("data");
        int sum = 0;
        for (Map<String, Object> buff : Exec.mapsOf(raw)) {
            long expire = Exec.longOf(buff.get("expire_ts"));
            if (expire > 0L && expire <= nowSec) continue;
            Object bmRaw = buff.get("buff_map");
            if (bmRaw instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) bmRaw).entrySet()) {
                    if (queueTechNumericId(e.getKey()) != 10505L) continue;
                    Object v = e.getValue();
                    if (v instanceof Map) sum += (int) Math.max(0L, Exec.longOf(Exec.mapOf(v).get("v")));
                    else sum += (int) Math.max(0L, Exec.longOf(v));
                }
            } else {
                for (Map<String, Object> item : Exec.mapsOf(bmRaw)) {
                    if (Exec.longOf(item.get("k")) != 10505L) continue;
                    sum += (int) Math.max(0L, Exec.longOf(item.get("v")));
                }
            }
        }
        return sum;
    }

    /** 额外队列：world_extra_march.value（缺省 0）。 */
    static long extraMarchCount(Map<String, Object> wpi) {
        if (wpi == null) return 0L;
        Map<String, Object> extra = Exec.mapOf(wpi.get("world_extra_march"));
        return extra == null ? 0L : Math.max(0L, Exec.longOf(extra.get("value")));
    }

    // ---------- 野外采集 v2：队列化执行（I2，2026-10-24） ----------

    /**
     * v2 队列化采集（1:1 参考网页版 DailyGatherService）：
     * <pre>
     * 配置（roleParams.gather.v2，GatherConfigV2）→ 6 队列 × {资源/等级1-9/编队/兵量}
     * 资产库（CoreAssetRegistry/CoreAssetLibrary.Queue）→ capacity/occupied/idle/limitReached/marches
     * 每轮：对账队列↔行军映射 → 算空闲槽位 → 按 nextQueueId 环状找未绑定队列 →
     *       逐级搜索（L→1，2872 降级）→ 每队列编队 → req_world_march type=4 →
     *       绑定 marchId + 资产库回灌 → 续派唤醒（最早在途采集行军 endTime+250ms）
     * </pre>
     * v2 缺失时走 {@link #gatherLegacy}（逐字节旧行为）。联盟矿本期不接入队列化（旧路径保留）。
     */
    static final long GATHER_MARCH_TYPE = 4L;

    /** 采集搜索/派队错误码（1:1 网页版 DailyGatherService:91-100，真实日志实证）。 */
    static final int GATHER_ERR_MARCH_LIMIT = 2878;        // 世界出征队列已满
    static final int GATHER_ERR_SOLDIERS = 401;            // 可用士兵不足
    static final int GATHER_ERR_HERO_BUSY = 403;           // 编队英雄已被占用
    static final int GATHER_ERR_TARGET_GONE = 2816;        // 资源点不存在
    static final int GATHER_ERR_TARGET_REFRESHED = 2822;   // 资源点已刷新
    static final int GATHER_ERR_ARSENAL_WAR = 8619;        // 兵工厂战争锁（退避 300s）
    static final int GATHER_ERR_FROSTFIRE_WAR = 8637;      // 燃霜矿区战锁（退避 60s）
    /** 搜索无矿（正常业务状态：逐级降级，不报错）。 */
    static final int GATHER_SEARCH_EMPTY = 2872;

    /** v2 续派：最早在途采集行军 endTime + 250ms 唤醒（网页版 nextMarchTransitionAt:1060-1074）。 */
    static final long GATHER_WAKE_AFTER_END_MILLIS = 250L;
    /** v2 续派：没有在途采集行军时 now+3s 复查（同网页版“无 march 则 +3s”）。 */
    static final long GATHER_V2_WAKE_FALLBACK_MILLIS = 3_000L;
    /** 兵工厂战争锁退避（网页版 WAITING_ARSENAL_WAR 300s）。 */
    static final long GATHER_V2_ARSENAL_BACKOFF_MILLIS = 300_000L;
    /** 燃霜矿区战锁退避（网页版 WAITING_FROSTFIRE 60s）。 */
    static final long GATHER_V2_FROSTFIRE_BACKOFF_MILLIS = 60_000L;
    /** 2878 本地“队列满”短标记（资产库无 mark API 时的本地兜底；成功派队即清除）。 */
    static final long GATHER_V2_LIMIT_BACKOFF_MILLIS = 30_000L;
    /** 资产库 READY 等待上限（超时按 SKIP 处理，不阻塞任务线程超过该值）。 */
    static final long GATHER_ASSET_READY_TIMEOUT_MILLIS = 15_000L;
    /** 王国可采资源等级未知时的保守上限（未满 701 天 = 8 级，1:1 GatherConfigV2.maxLevelForKingdomDay）。 */
    static final int GATHER_DEFAULT_MAX_RESOURCE_LEVEL = 8;
    /** 单队列每轮搜索次数上限（等级 1-9 递减，防请求风暴）。 */
    static final int GATHER_MAX_SEARCHES_PER_QUEUE = GatherConfigV2.MAX_RESOURCE_LEVEL;

    /** 301=英雄不在城（换编队候选；与守护盟友/巨熊同码表语义）。 */
    static final int GATHER_ERR_HERO_AWAY = 301;
    /** 415=编队人数/配置超限（新号英雄上阵位不足等；下调自动编队英雄数重试）。 */
    static final int GATHER_ERR_FORMATION_LIMIT = 415;
    /** 406=编队士兵数量达到允许上限（按兵力缩放比例自适应，配合容量夹取）。 */
    static final int GATHER_ERR_SOLDIER_LIMIT = 406;

    /** v2 单队列执行计划（纯函数产物：资源 res_id、等级递减序列、每队列编队参数）。 */
    static final class QueuePlan {
        final int queueId;
        final String resourceType;
        final int resId;
        /** 从配置等级递减到 1（已按王国可采上限夹取；长度 ≤ 9）。 */
        final List<Integer> levels;
        final boolean fullResourceOnly;
        /** 传给 {@code FormationResolver.resolveOutcome} 的每队列编队参数。 */
        final JSONObject formationParams;

        QueuePlan(int queueId, String resourceType, int resId, List<Integer> levels,
                  boolean fullResourceOnly, JSONObject formationParams) {
            this.queueId = queueId;
            this.resourceType = resourceType == null ? GatherConfigV2.RES_MEAT : resourceType;
            this.resId = resId;
            this.levels = levels == null ? new ArrayList<Integer>() : new ArrayList<Integer>(levels);
            this.fullResourceOnly = fullResourceOnly;
            this.formationParams = formationParams == null ? new JSONObject() : formationParams;
        }

        int topLevel() {
            return levels.isEmpty() ? 0 : levels.get(0).intValue();
        }
    }

    /** 队列↔行军映射（状态键 {@code srv:gather:queues:{fpid}:{sid}}）。 */
    static final class QueueMapping {
        int nextQueueId = 1;
        final LinkedHashMap<String, Long> map = new LinkedHashMap<String, Long>();

        QueueMapping copy() {
            QueueMapping c = new QueueMapping();
            c.nextQueueId = nextQueueId;
            c.map.putAll(map);
            return c;
        }

        boolean bound(int queueId) {
            return map.containsKey(String.valueOf(queueId));
        }

        long marchIdOf(int queueId) {
            Long v = map.get(String.valueOf(queueId));
            return v == null ? 0L : v.longValue();
        }
    }

    /** 单次搜索结果（code=0 且 point 非空 = 命中；2872 = 该级无空矿）。 */
    static final class SearchOutcome {
        final int code;
        final int level;
        final Map<String, Object> point;

        SearchOutcome(int code, int level, Map<String, Object> point) {
            this.code = code;
            this.level = level;
            this.point = point;
        }

        boolean found() {
            return code == 0 && point != null;
        }

        boolean empty() {
            return code == GATHER_SEARCH_EMPTY;
        }
    }

    /**
     * v2 队列化采集入口：资产库就绪判定 → 队列游标派队 → 续派唤醒。
     * 资产库未接线/未 READY 只记 SKIP（原因含 missing），绝不抛异常。
     */
    static TaskOutcome gatherV2(TaskContext tc, JSONObject gatherParams) {
        Exec.Multi m = new Exec.Multi();
        CoreAssetLibrary lib = null;
        try {
            lib = CoreAssetRegistry.global().get(tc.engine);
            if (lib == null) return gatherV2AssetNotReady(tc, m, missingProtocolsText(lib));
            if (!lib.awaitReady(GATHER_ASSET_READY_TIMEOUT_MILLIS)) {
                return gatherV2AssetNotReady(tc, m, missingProtocolsText(lib));
            }
            gatherV2Dispatch(tc, gatherParams, lib, m);
        } catch (Exception e) {
            if (ServerTownTasks.sessionLost(e)) return ServerTasks.classifyException(e, "野外采集");
            m.fail(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return finishGatherV2(tc, m, lib);
    }

    /** 资产库未就绪：SKIP（reason 含 missing）+ 写续派唤醒 + mod 状态。 */
    private static TaskOutcome gatherV2AssetNotReady(TaskContext tc, Exec.Multi m, String reason) {
        String text = "资产库未就绪(" + reason + ")";
        m.skip(text);
        try {
            tc.saveModText("gather", "send", text);
        } catch (Throwable ignored) {
        }
        FileLog.warn("gather v2 跳过 fpid={}：{}", tc.fpid, text);
        return finishGatherV2(tc, m, null);
    }

    /** 资产库缺协议诊断文案（READY 超时用；含 missing 关键字）。 */
    static String missingProtocolsText(CoreAssetLibrary lib) {
        if (lib == null) return "missing: registry";
        try {
            List<String> missing = lib.missingProtocols();
            if (missing == null || missing.isEmpty()) return "missing: (推荐协议已齐但 READY 超时)";
            return "missing: " + String.join(",", missing);
        } catch (Throwable t) {
            return "missing: (诊断异常 " + ServerTasks.brief(String.valueOf(t.getMessage())) + ")";
        }
    }

    /** v2 主流程：队列↔行军对账 → 槽位 → 游标派队（每队列独立资源/等级/编队）。 */
    private static void gatherV2Dispatch(TaskContext tc, JSONObject gatherParams, CoreAssetLibrary lib,
                                         Exec.Multi m) throws Exception {
        GatherConfigV2 cfg = GatherConfigV2.parse(gatherParams);
        AssetSnapshot.Queue queue = lib.queue() == null ? AssetSnapshot.Queue.empty() : lib.queue();
        // 批次 E：守护实盘时预留行军队列（采集让位；默认 1，clamp 0..2）
        int guardReserve = GuardianReserve.reserveSlots(tc);
        FileLog.info("gather queue: capacity={} occupied={} idle={} special={} limit={} reserve={}",
                queue.capacity, queue.occupied, queue.idle, specialQueueCount(queue),
                queue.limitReached, guardReserve);

        long now = System.currentTimeMillis();
        long backoffUntil = stateLongOf(tc, gatherBackoffKey(tc));
        if (backoffUntil > now) {
            String text = "采集退避中(" + secondsLeft(now, backoffUntil) + "s)";
            m.skip(text);
            tc.saveModText("gather", "send", text);
            return;
        }
        long limitUntil = stateLongOf(tc, gatherLimitKey(tc));
        if (limitUntil > now) {
            String text = "世界出征队列已满(本地限制 " + secondsLeft(now, limitUntil) + "s)";
            m.skip(text);
            tc.saveModText("gather", "send", text);
            return;
        }

        // 采集加速维护（网页版 gatherSpeedupBlocked/GatherSpeedupLevel：8h=302000/250钻、24h=302001/600钻；
        // 默认仅用背包道具，钻石购买显式开启才花钻）
        maintainGatherSpeedup(tc, cfg, lib);
        // 重物搬运（宠物技能 550031 专用队列：派遣最高级矿，额外 1 队；失败不影响普通采集）
        maintainHeavyLifting(tc, cfg, lib);

        // B2-T4 暂停协调（1:1 网页版 DailyGatherService.pauseBeforeDispatch:694-712）：
        // 情报待办优先 / 巨熊活动窗口时让位，避免抢队列。
        // 2026-10-25 P0 修复：仅当 intel 任务对本角色确实启用时才让位——intel 目录默认关闭，
        // 若只开采集而未开情报，情报待办永远不会被处理，采集会被永久让位（生产实证 58/58 全跳过、
        // 全天 0 队派出）。intel 未启用 → 待办事件不参与优先级判定。
        final boolean intelEnabled = tc.taskEnabled("intel");
        String pauseReason = gatherPauseReason(lib, System.currentTimeMillis() / 1000L, tc, intelEnabled);
        if (pauseReason != null) {
            m.skip(pauseReason);
            tc.saveModText("gather", "send", pauseReason);
            return;
        }

        int maxLevel = gatherMaxResourceLevel(lib.clock());
        QueueMapping mapping = reconcileMapping(
                parseQueueMapping(tc.store.getString(gatherQueueKey(tc))), queue.marches);
        int unbound = unboundEnabledCount(cfg, mapping);
        if (cfg.enabledQueues().isEmpty()) {
            String text = "采集配置未启用任何队列";
            m.skip(text);
            tc.saveModText("gather", "send", text);
            writeQueueMapping(tc, mapping);
            return;
        }
        // B2-T5 联盟矿（type=110）：占 1 个世界队列；失败只记子项，不影响普通队列派队。
        int allianceMineReserved = 0;
        if (cfg.allianceMineEnabled) {
            MarchSnapshot mineSnapshot = marchSnapshotFromAsset(queue);
            if (mineSnapshot.count(GATHER_MINE_MARCH_TYPE) > 0) {
                allianceMineReserved = 1;
                m.subSkip("alliance_mine", "联盟矿已在途，占用 1 队");
                tc.saveModText("gather", "alliance_mine", "联盟矿已在途，占用 1 队");
            } else if (queue.capacity <= 0) {
                m.subSkip("alliance_mine", "世界容量未知，跳过联盟矿");
                tc.saveModText("gather", "alliance_mine", "世界容量未知，跳过");
            } else {
                AllianceMineOutcome mine = maintainAllianceMine(tc, mineSnapshot, queue.capacity,
                        allianceMineFormationParams(cfg));
                if (mine.dispatched) {
                    allianceMineReserved = 1;
                    m.claimed("联盟矿已派出");
                    m.subOk("alliance_mine", mine.message);
                } else {
                    m.subSkip("alliance_mine", mine.message);
                }
                tc.saveModText("gather", "alliance_mine", mine.message);
            }
        }
        int slots = Math.max(0, slotCount(cfg, queue.idle, unbound, queue.capacity,
                queue.limitReached, guardReserve) - allianceMineReserved);
        if (queue.limitReached || slots <= 0) {
            String text = "采集队列已满(capacity=" + queue.capacity + " occupied=" + queue.occupied
                    + " idle=" + queue.idle + " unbound=" + unbound
                    + (guardReserve > 0 ? " reserve=" + guardReserve : "") + ")"
                    + (guardReserve > 0 ? "（为守护预留 " + guardReserve + " 队）" : "");
            m.skip(text);
            tc.saveModText("gather", "send", text);
            writeQueueMapping(tc, mapping);
            return;
        }

        List<QueuePlan> plans = planQueues(cfg, maxLevel);
        Map<Integer, QueuePlan> planById = new LinkedHashMap<Integer, QueuePlan>();
        for (QueuePlan plan : plans) planById.put(plan.queueId, plan);

        List<String> statuses = new ArrayList<String>();
        // P1-7：同轮多队列英雄预留（按序解析时把前序队列已用英雄传给 Resolver，防同轮 403）
        Set<Long> usedHeroes = new LinkedHashSet<Long>();
        int sent = 0;
        int cursor = GatherConfigV2.clampInt(mapping.nextQueueId, 1, GatherConfigV2.QUEUE_COUNT);
        HashSet<Integer> attempted = new HashSet<Integer>();
        boolean stopRound = false;
        while (!stopRound && sent < slots && attempted.size() < plans.size()) {
            GatherConfigV2.QueueCfg q = nextUnboundQueue(cfg, mapping, cursor);
            if (q == null) break;
            cursor = nextQueueIdAfter(q.id);
            if (!attempted.add(q.id)) break;      // 单轮内不重复派同一队列
            QueuePlan plan = planById.get(q.id);
            if (plan == null) continue;
            String label = "队列" + q.id + " " + GatherConfigV2.resourceLabel(q.resourceType) + q.level;

            SearchOutcome found = searchQueueResource(tc.engine, plan);
            if (!found.found()) {
                String why = found.empty() ? "无矿" : "搜索失败(码" + found.code + ")";
                statuses.add(label + " " + why);
                FileLog.info("gather queue#{} res={}({}) lv={} formation={} -> {}",
                        q.id, plan.resourceType, plan.resId, plan.topLevel(), "-", why);
                if (!found.empty()) m.subSkip("queue" + q.id, why);
                continue;
            }

            StringBuilder fWhy = new StringBuilder();
            Map<String, Object> formation = gatherV2Formation(tc, plan, fWhy, usedHeroes);
            if (formation == null) {
                String why = "编队跳过：" + (fWhy.length() == 0 ? "无可用编队" : fWhy.toString());
                statuses.add(label + " " + why);
                m.subSkip("queue" + q.id, why);
                continue;
            }

            GameMessage march = tc.engine.request("req_world_march", Exec.args(
                    "type", GATHER_MARCH_TYPE,
                    "endpoint", found.point,
                    "formation", formation,
                    "extra", new LinkedHashMap<String, Object>()));
            long marchId = Exec.marchId(march);
            int code = Exec.codeOf(march);
            String levelNote = found.level < q.level ? "→L" + found.level : "";
            if (marchId > 0L) {
                // 415 上限是账号属性（上阵位）：成功只说明本次英雄数可用；P0-5 契约要求成功后
                // 复位 415/406 自适应（重试完整上限/兵量），否则 hint 永久生效导致缩水。
                HeroAvailability.markSuccess(tc.engine);
                usedHeroes.addAll(HeroAvailability.heroIdsOf(formation));
                mapping.map.put(String.valueOf(q.id), marchId);
                mapping.nextQueueId = nextQueueIdAfter(q.id);
                applyGatherResponse(lib, march);
                tc.store.putLong(gatherLimitKey(tc), 0L);
                sent++;
                statuses.add(label + levelNote + " 已派出");
                m.subOk("queue" + q.id, "已派出 march=" + marchId);
                FileLog.info("gather queue#{} res={}({}) lv={} formation={} -> {}", q.id,
                        plan.resourceType, plan.resId, found.level, formationDesc(plan),
                        "march#" + marchId);
            } else if (code == GATHER_ERR_MARCH_LIMIT) {
                // 2878：停止本轮 + 本地 limit 标记（同时回灌资产库，若其解析器支持则置 limitReached）
                applyGatherResponse(lib, march);
                tc.store.putLong(gatherLimitKey(tc),
                        System.currentTimeMillis() + GATHER_V2_LIMIT_BACKOFF_MILLIS);
                statuses.add(label + " 世界队列已满(2878)");
                m.skip("世界出征队列已满(码2878)");
                FileLog.warn("gather queue#{} 派队受阻：世界出征队列已满(2878)", q.id);
                stopRound = true;
            } else if (code == GATHER_ERR_SOLDIERS) {
                statuses.add(label + " 士兵不足(401)");
                m.subSkip("queue" + q.id, "士兵不足(401)");
            } else if (code == GATHER_ERR_HERO_BUSY) {
                // 403=英雄已在其他编队/行军：拉黑本轮编队英雄，下一队列/下一轮换候选
                blockHeroesOn301(tc.engine, formation);
                statuses.add(label + " 英雄占用(403)，已轮换候选");
                m.subSkip("queue" + q.id, "英雄占用(403)");
            } else if (code == GATHER_ERR_FORMATION_LIMIT) {
                // 415=编队人数/配置超限（新号英雄上阵位不足等）：下调自动编队英雄数，下轮重试
                int used = HeroAvailability.heroIdsOf(formation).size();
                int next = HeroAvailability.noteHeroCountRejected(tc.engine, used);
                statuses.add(label + " 编队人数超限(415)，下轮改 " + next + " 英雄");
                m.subSkip("queue" + q.id, "编队人数超限(415)");
            } else if (code == GATHER_ERR_SOLDIER_LIMIT) {
                int scale = HeroAvailability.noteSoldierLimitRejected(tc.engine);
                List<Long> rejectedHeroes = HeroAvailability.heroIdsOf(formation);
                FileLog.warn("gather 406 fpid={} queue#{} heroes={} soldiers={} capacity={} scale→{}%",
                        tc.fpid, q.id, rejectedHeroes, formation.get("soldier"),
                        MarchCapacityCalculator.capacityFor(tc.engine, rejectedHeroes), scale);
                statuses.add(label + " 士兵超上限(406)，兵力缩放至" + scale + "%");
                m.subSkip("queue" + q.id, "士兵超上限(406)");
            } else if (code == GATHER_ERR_HERO_AWAY) {
                // 301=英雄不在城：拉黑本轮编队英雄，下一队列/下一轮换候选
                blockHeroesOn301(tc.engine, formation);
                statuses.add(label + " 英雄不在城(301)，已轮换候选");
                m.subSkip("queue" + q.id, "英雄不在城(301)");
            } else if (code == GATHER_ERR_TARGET_GONE || code == GATHER_ERR_TARGET_REFRESHED) {
                statuses.add(label + " 矿点变化(" + code + ")，下轮重搜");
                m.subSkip("queue" + q.id, "矿点变化(" + code + ")");
            } else if (code == GATHER_ERR_ARSENAL_WAR) {
                tc.store.putLong(gatherBackoffKey(tc),
                        System.currentTimeMillis() + GATHER_V2_ARSENAL_BACKOFF_MILLIS);
                statuses.add(label + " 兵工厂战锁(8619)，退避300s");
                m.skip("兵工厂战争锁(8619)，退避300s");
                stopRound = true;
            } else if (code == GATHER_ERR_FROSTFIRE_WAR) {
                tc.store.putLong(gatherBackoffKey(tc),
                        System.currentTimeMillis() + GATHER_V2_FROSTFIRE_BACKOFF_MILLIS);
                statuses.add(label + " 燃霜战锁(8637)，退避60s");
                m.skip("燃霜矿区战锁(8637)，退避60s");
                stopRound = true;
            } else {
                statuses.add(label + " 出征失败(码" + code + ")");
                m.subSkip("queue" + q.id, "出征码" + code);
                FileLog.warn("gather queue#{} 出征失败 code={}", q.id, code);
            }
        }
        writeQueueMapping(tc, mapping);
        if (sent > 0) {
            m.claimed("派出采集×" + sent);
        } else {
            m.skip("本轮未派出采集队");
        }
        if (guardReserve > 0) statuses.add("为守护预留 " + guardReserve + " 队");
        String text = statuses.isEmpty() ? "无可派采集队列" : String.join("；", statuses);
        tc.saveModText("gather", "send", text);
    }

    /**
     * 每队列计划（纯函数）：仅启用队列；资源名 → res_id 102-105；等级从配置等级递减到 1
     * （先夹到王国可采上限 maxLevel）；每队列编队参数 = ROLE(指定编队+兵量) 或 AUTOMATIC(比例权重+总量)。
     */
    static List<QueuePlan> planQueues(GatherConfigV2 cfg, int maxLevel) {
        List<QueuePlan> out = new ArrayList<QueuePlan>();
        if (cfg == null) return out;
        for (GatherConfigV2.QueueCfg q : cfg.enabledQueues()) {
            out.add(planQueue(q, maxLevel, cfg.fullResourceOnly));
        }
        return out;
    }

    /** JSON 重载（服务端读配置后直接生成计划；测试友好）。 */
    static List<QueuePlan> planQueues(JSONObject gatherParams, int maxLevel) {
        return planQueues(GatherConfigV2.parse(gatherParams), maxLevel);
    }

    static QueuePlan planQueue(GatherConfigV2.QueueCfg q, int maxLevel, boolean fullResourceOnly) {
        if (q == null) return null;
        return new QueuePlan(q.id, q.resourceType, GatherConfigV2.resId(q.resourceType),
                searchLevelsForQueue(q, maxLevel), fullResourceOnly, queueFormationParams(q));
    }

    /** 队列等级递减序列（9→1；maxLevel=8 时 9 被夹到 8）。 */
    static List<Integer> searchLevelsForQueue(GatherConfigV2.QueueCfg q, int maxLevel) {
        if (q == null) return new ArrayList<Integer>();
        return GatherConfigV2.searchLevels(q.level, maxLevel);
    }

    /**
     * 每队列编队参数（FormationResolver 契约）：
     * <ul>
     *   <li>ROLE：{@code {formation_mode:ROLE, formation_id, soldiers:{infantry,lancer,marksman}}}；</li>
     *   <li>AUTOMATIC：{@code {formation_mode:AUTOMATIC, ratio:{...每队列兵量作权重/默认40:30:30}, total:sum}}。</li>
     * </ul>
     */
    static JSONObject queueFormationParams(GatherConfigV2.QueueCfg q) {
        JSONObject f = new JSONObject();
        if (q == null) return f;
        long total = q.totalSoldiers();
        if (q.isRole()) {
            f.put("formation_mode", GatherConfigV2.MODE_ROLE);
            f.put("formation_id", q.formationId);
            JSONObject soldiers = new JSONObject();
            soldiers.put("infantry", Math.max(0L, q.infantry));
            soldiers.put("lancer", Math.max(0L, q.lancer));
            soldiers.put("marksman", Math.max(0L, q.marksman));
            f.put("soldiers", soldiers);
            return f;
        }
        f.put("formation_mode", GatherConfigV2.MODE_AUTOMATIC);
        JSONObject ratio = new JSONObject();
        if (total > 0L) {
            ratio.put("infantry", Math.max(0L, q.infantry));
            ratio.put("lancer", Math.max(0L, q.lancer));
            ratio.put("marksman", Math.max(0L, q.marksman));
        } else {
            ratio.put("infantry", 40L);
            ratio.put("lancer", 30L);
            ratio.put("marksman", 30L);
        }
        f.put("ratio", ratio);
        f.put("total", total > 0L ? total : 0L);
        return f;
    }

    /** 每队列编队解析（ROLE/AUTOMATIC；失败返回 null 并回填原因，绝不静默换编队）。
     *
     * @param usedHeroes 本轮前序队列已用英雄（Resolver 候选/自动编队预留，防同轮 403）
     */
    private static Map<String, Object> gatherV2Formation(TaskContext tc, QueuePlan plan,
                                                         StringBuilder reasonOut,
                                                         Set<Long> usedHeroes) {
        try {
            FormationLibrary lib = FormationResolver.libraryFor(tc, "gather");
            FormationResolver.ResolveOutcome ro =
                    FormationResolver.resolveOutcome(tc.engine, lib, plan.formationParams, usedHeroes);
            if (!ro.ready()) {
                if (reasonOut != null) reasonOut.append(ro.reason);
                FileLog.info("gather 编队跳过 fpid={} queue#{}：{}", tc.fpid, plan.queueId, ro.reason);
                return null;
            }
            Map<String, Object> payload = ro.formation.toFormationPayload();
            if (payload == null || payload.isEmpty()) {
                if (reasonOut != null) reasonOut.append("编队无可出征内容");
                return null;
            }
            return payload;
        } catch (Throwable t) {
            if (reasonOut != null) {
                reasonOut.append("编队异常：").append(ServerTasks.brief(String.valueOf(t.getMessage())));
            }
            FileLog.warn("gather 编队异常 fpid={} queue#{}：{}", tc.fpid, plan.queueId, String.valueOf(t));
            return null;
        }
    }

    /** 301=英雄不在城 / 403=英雄已被占用：拉黑本轮编队英雄 10 分钟，下轮自动编队换候选。 */
    static void blockHeroesOn301(WjdrEngine engine, Map<String, Object> formation) {
        try {
            List<Long> heroIds = HeroAvailability.heroIdsOf(formation);
            if (!heroIds.isEmpty()) {
                HeroAvailability.block(engine, heroIds, HeroAvailability.DEFAULT_BLOCK_MILLIS);
                // P2-3：补足占位符参数（原日志 `heroes={}` 恒空，排障信息丢失）
                FileLog.info("301/403 英雄拉黑 engine-heroes={}（拉黑 {} 分钟）",
                        String.valueOf(heroIds), HeroAvailability.DEFAULT_BLOCK_MILLIS / 60_000L);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 编队日志文案（ROLE#id / AUTOMATIC(40/30/30)）。 */
    static String formationDesc(QueuePlan plan) {
        if (plan == null || plan.formationParams == null) return "-";
        String mode = plan.formationParams.optString("formation_mode", "");
        if (GatherConfigV2.MODE_ROLE.equals(mode)) {
            return "ROLE#" + plan.formationParams.optLong("formation_id", 0L);
        }
        JSONObject ratio = plan.formationParams.optJSONObject("ratio");
        if (ratio == null) return mode.isEmpty() ? "AUTOMATIC" : mode;
        return "AUTOMATIC(" + ratio.optLong("infantry", 0L) + "/"
                + ratio.optLong("lancer", 0L) + "/" + ratio.optLong("marksman", 0L) + ")";
    }

    /**
     * 单队列逐级搜索：从配置等级（已夹 maxLevel）递减到 1；2872/空 point 降级；
     * 其它非 0 code 立即返回（调用方记录并跳过该队列）。每队列最多
     * {@link #GATHER_MAX_SEARCHES_PER_QUEUE} 次请求。
     */
    static SearchOutcome searchQueueResource(WjdrEngine engine, QueuePlan plan) {
        if (plan == null || plan.levels.isEmpty() || plan.resId <= 0) {
            return new SearchOutcome(GATHER_SEARCH_EMPTY, 0, null);
        }
        int searches = Math.min(plan.levels.size(), GATHER_MAX_SEARCHES_PER_QUEUE);
        SearchOutcome last = new SearchOutcome(GATHER_SEARCH_EMPTY, plan.topLevel(), null);
        for (int i = 0; i < searches; i++) {
            int level = plan.levels.get(i).intValue();
            SearchOutcome so = searchGatherResource(engine, plan.resId, level, plan.fullResourceOnly);
            if (so.found()) return so;
            last = so;
            // 2872=该级无矿；code=0 但无坐标=协议异常，均按“继续降级”处理；其它 code 记录并跳过该队列。
            if (so.empty() || so.code == 0) continue;
            return so;
        }
        return last;
    }

    /** 单级搜索（req_world_map_search；响应只有 code/point）。 */
    static SearchOutcome searchGatherResource(WjdrEngine engine, int resId, int level,
                                              boolean fullOnly) {
        try {
            GameMessage rsp = engine.request("req_world_map_search", Exec.args(
                    "mapobj_type", 2L,
                    "level_min", level,
                    "level_max", level,
                    "res_id", resId,
                    "is_full", fullOnly));
            if (rsp == null || rsp.getContent() == null) return new SearchOutcome(-1, level, null);
            int code = Exec.codeOf(rsp);
            if (code != 0) return new SearchOutcome(code, level, null);
            return new SearchOutcome(0, level, searchPointOf(rsp.getContent()));
        } catch (Throwable t) {
            return new SearchOutcome(-1, level, null);
        }
    }

    /** 搜索响应坐标（协议单对象 point；兼容 points/data/list 旧形状）。 */
    static Map<String, Object> searchPointOf(Map<String, Object> content) {
        if (content == null) return null;
        Map<String, Object> point = Exec.mapOf(content.get("point"));
        if (point != null && point.containsKey("x") && point.containsKey("y")) return point;
        for (String k : new String[]{"points", "data", "list"}) {
            for (Map<String, Object> p : Exec.mapsOf(content.get(k))) {
                if (p.containsKey("x") && p.containsKey("y")) return p;
            }
        }
        return null;
    }

    // ---------- v2 队列↔行军映射 / 槽位（纯函数） ----------

    /** 状态键：队列↔行军映射（JSON {"nextQueueId":n,"map":{"1":marchId,...}}）。 */
    static String gatherQueueKey(TaskContext tc) {
        return "srv:gather:queues:" + tc.fpid + ":" + tc.sid;
    }

    /** 状态键：8619/8637 战锁退避截止（毫秒）。 */
    static String gatherBackoffKey(TaskContext tc) {
        return "srv:gather:backoff:" + tc.fpid + ":" + tc.sid;
    }

    /** 状态键：2878 本地队列满标记截止（毫秒）。 */
    static String gatherLimitKey(TaskContext tc) {
        return "srv:gather:limit:" + tc.fpid + ":" + tc.sid;
    }

    static QueueMapping parseQueueMapping(String raw) {
        QueueMapping out = new QueueMapping();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(raw);
            out.nextQueueId = GatherConfigV2.clampInt(
                    o.optInt("nextQueueId", o.optInt("next_queue_id", 1)),
                    1, GatherConfigV2.QUEUE_COUNT);
            JSONObject map = o.optJSONObject("map");
            if (map != null) {
                List<Integer> ids = new ArrayList<Integer>();
                for (String k : map.keySet()) {
                    int id = parseInt(k);
                    if (id >= 1 && id <= GatherConfigV2.QUEUE_COUNT) ids.add(id);
                }
                ids.sort(null);
                for (Integer id : ids) {
                    long marchId = map.optLong(String.valueOf(id), 0L);
                    if (marchId > 0L) out.map.put(String.valueOf(id), Long.valueOf(marchId));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String encodeQueueMapping(QueueMapping mapping) {
        QueueMapping m = mapping == null ? new QueueMapping() : mapping;
        JSONObject map = new JSONObject();
        List<Integer> ids = new ArrayList<Integer>();
        for (String k : m.map.keySet()) {
            int id = parseInt(k);
            if (id >= 1 && id <= GatherConfigV2.QUEUE_COUNT) ids.add(id);
        }
        ids.sort(null);
        for (Integer id : ids) {
            Long marchId = m.map.get(String.valueOf(id));
            if (marchId != null && marchId.longValue() > 0L) {
                map.put(String.valueOf(id), marchId.longValue());
            }
        }
        JSONObject o = new JSONObject();
        o.put("nextQueueId", GatherConfigV2.clampInt(m.nextQueueId, 1, GatherConfigV2.QUEUE_COUNT));
        o.put("map", map);
        return o.toString();
    }

    /**
     * 对账：只保留 type∈{4 普通采集,110 联盟矿} 且该 marchId 仍在资产库在途行军中（id 命中）
     * 的映射；已结束的移除（1:1 网页版 activeMarches 重建 :621-625）。
     */
    static QueueMapping reconcileMapping(QueueMapping mapping,
                                         List<AssetSnapshot.QueueMarch> marches) {
        QueueMapping out = mapping == null ? new QueueMapping() : mapping.copy();
        out.nextQueueId = GatherConfigV2.clampInt(out.nextQueueId, 1, GatherConfigV2.QUEUE_COUNT);
        HashSet<Long> live = new HashSet<Long>();
        if (marches != null) {
            for (AssetSnapshot.QueueMarch march : marches) {
                if (march == null) continue;
                if (march.type == GATHER_MARCH_TYPE || march.type == GATHER_MINE_MARCH_TYPE) {
                    live.add(Long.valueOf(march.id));
                }
            }
        }
        out.map.entrySet().removeIf(e -> !live.contains(e.getValue()));
        return out;
    }

    /** 未绑定启用队列数（=下一步可派的新队列）。 */
    static int unboundEnabledCount(GatherConfigV2 cfg, QueueMapping mapping) {
        if (cfg == null) return 0;
        int n = 0;
        for (GatherConfigV2.QueueCfg q : cfg.enabledQueues()) {
            if (mapping == null || !mapping.bound(q.id)) n++;
        }
        return n;
    }

    /**
     * 本轮派队数：{@code min(idle, 未绑定启用队列数, effectiveQueueLimit(capacity, 0))}；
     * limitReached → 0（1:1 网页版 normalQueueIdle）。
     */
    static int slotCount(GatherConfigV2 cfg, int idle, int unboundEnabledQueues,
                         int worldCapacity, boolean limitReached) {
        return slotCount(cfg, idle, unboundEnabledQueues, worldCapacity, limitReached, 0);
    }

    /**
     * 批次 E：在容量/启用数/空闲夹取后，再扣掉守护预留（采集让位；下限保留 1 队采集）。
     * reserve&gt;0 且有效上限&gt;0 时 {@code effective = max(1, effective - reserve)}。
     */
    static int slotCount(GatherConfigV2 cfg, int idle, int unboundEnabledQueues,
                         int worldCapacity, boolean limitReached, int reserve) {
        if (cfg == null || limitReached) return 0;
        int effective = cfg.effectiveQueueLimit(worldCapacity, 0);
        if (reserve > 0 && effective > 0) {
            effective = Math.max(1, effective - Math.max(0, Math.min(2, reserve)));
        }
        int slots = Math.min(Math.max(0, idle), Math.max(0, unboundEnabledQueues));
        return Math.max(0, Math.min(slots, effective));
    }

    /** 环状找下一个未绑定启用队列（从 fromQueueId 起，含）；无则 null。 */
    static GatherConfigV2.QueueCfg nextUnboundQueue(GatherConfigV2 cfg, QueueMapping mapping,
                                                    int fromQueueId) {
        if (cfg == null) return null;
        QueueMapping m = mapping == null ? new QueueMapping() : mapping;
        int start = GatherConfigV2.clampInt(fromQueueId, 1, GatherConfigV2.QUEUE_COUNT);
        for (int i = 0; i < GatherConfigV2.QUEUE_COUNT; i++) {
            int id = ((start - 1 + i) % GatherConfigV2.QUEUE_COUNT) + 1;
            GatherConfigV2.QueueCfg q = cfg.queue(id);
            if (q != null && q.enabled && !m.bound(id)) return q;
        }
        return null;
    }

    /** 队列游标 +1（6 → 1）＝网页版 queueAfter。 */
    static int nextQueueIdAfter(int queueId) {
        if (queueId < 1 || queueId > GatherConfigV2.QUEUE_COUNT) return 1;
        return queueId >= GatherConfigV2.QUEUE_COUNT ? 1 : queueId + 1;
    }

    static void writeQueueMapping(TaskContext tc, QueueMapping mapping) {
        try {
            tc.store.putString(gatherQueueKey(tc), encodeQueueMapping(mapping));
        } catch (Throwable ignored) {
        }
    }

    // ---------- 采集加速（GatherSpeedup，默认关；缺道具不花钻） ----------

    static final String GATHER_SPEEDUP_STATE_KEY_PREFIX = "srv:gather:speedup:";

    static String gatherSpeedupStateKey(TaskContext tc) {
        return GATHER_SPEEDUP_STATE_KEY_PREFIX + tc.fpid + ":" + tc.sid;
    }

    /**
     * 维持采集加速 buff：已生效 → 跳过；否则优先用背包道具（302000/302001），
     * 背包无道具且显式开启钻石购买 → {@code req_shop_quick_buy}（按日预算限流）。
     * 失败只写 mod 文案，不阻塞采集派队。
     */
    static void maintainGatherSpeedup(TaskContext tc, GatherConfigV2 cfg, CoreAssetLibrary lib) {
        if (cfg == null || !cfg.speedupEnabled) return;
        try {
            long now = System.currentTimeMillis();
            int hours = cfg.speedupHours == 24 ? 24 : 8;
            long itemId = GatherConfigV2.speedupItemId(hours);
            JSONObject st = gatherSpeedupState(tc);
            long appliedAt = st.optLong("appliedAt", 0L);
            if (GatherConfigV2.speedupActive(appliedAt, hours, now)) {
                tc.saveModText("gather", "speedup",
                        (hours == 24 ? "24小时" : "8小时") + "采集加速生效中");
                return;
            }
            long stock = itemCountOf(lib, tc.engine, itemId);
            if (stock > 0L) {
                GameMessage use = tc.engine.request("req_use_item", Exec.args(
                        "id", itemId, "count", 1L, "extra", new LinkedHashMap<String, Object>()));
                if (Exec.ok(use)) {
                    st.put("appliedAt", now);
                    st.put("itemId", itemId);
                    st.put("hours", hours);
                    saveGatherSpeedupState(tc, st);
                    tc.saveModText("gather", "speedup",
                            (hours == 24 ? "24小时" : "8小时") + "采集加速已续期（道具）");
                } else {
                    tc.saveModText("gather", "speedup", "采集加速道具使用失败码" + Exec.codeOf(use));
                }
                return;
            }
            if (!cfg.speedupDiamondPurchaseEnabled) {
                tc.saveModText("gather", "speedup",
                        (hours == 24 ? "24小时" : "8小时") + "加速道具不足（未开启钻石购买）");
                return;
            }
            long price = GatherConfigV2.speedupDiamondPrice(hours);
            long day = now / 86_400_000L;
            long spent = st.optLong("day", 0L) == day ? st.optLong("spent", 0L) : 0L;
            long limit = Math.max(0L, cfg.speedupDiamondDailyLimit);
            if (spent + price > limit) {
                tc.saveModText("gather", "speedup",
                        "采集加速今日钻石预算已用尽(" + spent + "/" + limit + ")");
                return;
            }
            GameMessage buy = tc.engine.request("req_shop_quick_buy", Exec.args(
                    "id", itemId, "count", 1L, "use", true));
            if (Exec.ok(buy)) {
                st.put("appliedAt", now);
                st.put("itemId", itemId);
                st.put("hours", hours);
                st.put("day", day);
                st.put("spent", spent + price);
                saveGatherSpeedupState(tc, st);
                tc.saveModText("gather", "speedup",
                        (hours == 24 ? "24小时" : "8小时") + "采集加速已续期（钻石-" + price + "）");
            } else {
                tc.saveModText("gather", "speedup", "采集加速钻石购买失败码" + Exec.codeOf(buy));
            }
        } catch (Throwable t) {
            try {
                tc.saveModText("gather", "speedup", "采集加速异常：" + ServerTasks.brief(String.valueOf(t.getMessage())));
            } catch (Throwable ignored) {
            }
        }
    }

    static JSONObject gatherSpeedupState(TaskContext tc) {
        try {
            String raw = tc.store.getString(gatherSpeedupStateKey(tc));
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static void saveGatherSpeedupState(TaskContext tc, JSONObject st) {
        try {
            tc.store.putString(gatherSpeedupStateKey(tc), st == null ? "{}" : st.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 背包道具数量：资产库 backpack 优先；回退登录背包推送解析。 */
    static long itemCountOf(CoreAssetLibrary lib, WjdrEngine engine, long itemId) {
        try {
            if (lib != null && lib.ready()) {
                long n = lib.backpack().get(itemId);
                if (n > 0L) return n;
            }
        } catch (Throwable ignored) {
        }
        try {
            Map<String, Object> bp = engine.notifyData("notify_backpack_init");
            if (bp == null) bp = engine.notifyData("notify_backpack_update");
            return backpackCount(bp, itemId);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 背包推送解析（data Map/List；元素 {id,count} 或 map 形 "302000.0"）。 */
    static long backpackCount(Map<String, Object> backpack, long itemId) {
        if (backpack == null || itemId <= 0L) return 0L;
        Object data = backpack.get("data");
        if (data == null) data = backpack;
        if (data instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) data).entrySet()) {
                long id = queueTechNumericId(e.getKey());
                Object v = e.getValue();
                long count = v instanceof Map ? Exec.longOf(Exec.mapOf(v).get("count")) : Exec.longOf(v);
                if (v instanceof Map) {
                    Map<String, Object> m = Exec.mapOf(v);
                    if (id <= 0L) id = Exec.longOf(m.get("id"));
                    count = Exec.longOf(m.get("count"));
                }
                if (id == itemId) return count;
            }
        } else {
            for (Map<String, Object> m : Exec.mapsOf(data)) {
                if (Exec.longOf(m.get("id")) == itemId) return Exec.longOf(m.get("count"));
            }
        }
        return 0L;
    }

    // ---------- 重物搬运（宠物技能 550031：最高级矿专用队列，额外 1 队） ----------

    /** 网页版 DailyPetSkillCatalog 的重物搬运技能 id。 */
    static final long HEAVY_LIFTING_SKILL_ID = 550031L;
    static final String HEAVY_LIFTING_STATE_PREFIX = "srv:gather:heavy:";

    static String heavyLiftingStateKey(TaskContext tc) {
        return HEAVY_LIFTING_STATE_PREFIX + tc.fpid + ":" + tc.sid;
    }

    /**
     * 重物搬运（网页版 DailyGatherService.maintainHeavyLifting 的简化等价实现）：
     * <ol>
     *   <li>已在途 → 等待结束；</li>
     *   <li>冷却中 → 等待；</li>
     *   <li>搜索当前可采最高级矿（找不到不消耗技能）；</li>
     *   <li>{@code req_pet_use_skill{skill_id_lst:[550031]}} 激活技能；</li>
     *   <li>立即派出 type=4 专用队列（不占普通队列槽位，技能提供额外 1 队），记录 marchId；</li>
     *   <li>队列结束后恢复普通采集；失败只写 mod，不阻塞普通采集。</li>
     * </ol>
     */
    static void maintainHeavyLifting(TaskContext tc, GatherConfigV2 cfg, CoreAssetLibrary lib) {
        if (cfg == null || !cfg.heavyLiftingEnabled) return;
        try {
            long now = System.currentTimeMillis();
            JSONObject st = heavyLiftingState(tc);
            long marchId = st.optLong("marchId", 0L);
            if (marchId > 0L) {
                if (heavyMarchActive(lib, marchId)) {
                    tc.saveModText("gather", "heavy", "重物搬运专用队列在途（march#" + marchId + "）");
                    return;
                }
                st.put("marchId", 0L);
                st.put("state", "IDLE");
                saveHeavyLiftingState(tc, st);
                tc.saveModText("gather", "heavy", "重物搬运完成，恢复普通采集");
                return;
            }
            long cooldownUntil = st.optLong("cooldownUntil", 0L);
            if (cooldownUntil > now) {
                tc.saveModText("gather", "heavy",
                        "重物搬运冷却中（" + Math.max(1L, (cooldownUntil - now) / 1000L) + "s）");
                return;
            }
            int maxLevel = gatherMaxResourceLevel(lib.clock());
            int resId = GatherConfigV2.resId(cfg.heavyLiftingResourceType);
            Map<String, Object> point = null;
            int targetLevel = 0;
            for (int lv = maxLevel; lv >= 1; lv--) {
                SearchOutcome so = searchGatherResource(tc.engine, resId, lv, false);
                if (so.found()) {
                    point = so.point;
                    targetLevel = lv;
                    break;
                }
                if (so.code != 0 && so.code != GATHER_SEARCH_EMPTY) break;
            }
            if (point == null) {
                tc.saveModText("gather", "heavy", "最高级矿暂未找到，稍后重试");
                return;
            }
            // 本地判定技能是否解锁：未解锁/未拥有不触发请求（避免每 15 分钟 11542），6 小时后再查
            if (!com.carlos.vaultserver.formation.PetSkillSupport.heavyLiftingUnlocked(tc.engine)) {
                String why = com.carlos.vaultserver.formation.PetSkillSupport
                        .heavyLiftingLockReason(tc.engine);
                st.put("cooldownUntil", now + 6L * 3600_000L);
                saveHeavyLiftingState(tc, st);
                tc.saveModText("gather", "heavy", "重物搬运暂不可用：" + why);
                return;
            }
            // 先激活宠物技能（技能冷却/服务器拒绝 → 15 分钟内不再尝试）
            GameMessage skill = tc.engine.request("req_pet_use_skill", Exec.args(
                    "skill_id_lst", java.util.Collections.singletonList(HEAVY_LIFTING_SKILL_ID)));
            if (!Exec.ok(skill)) {
                int code = Exec.codeOf(skill);
                st.put("cooldownUntil", now + 15L * 60L * 1000L);
                saveHeavyLiftingState(tc, st);
                tc.saveModText("gather", "heavy", "重物搬运技能不可用（码" + code + "），15 分钟后重试");
                return;
            }
            StringBuilder why = new StringBuilder();
            Map<String, Object> formation = heavyLiftingFormation(tc, cfg, why);
            if (formation == null) {
                tc.saveModText("gather", "heavy", why.length() > 0
                        ? "重物搬运编队跳过：" + why : "重物搬运无可用编队");
                return;
            }
            GameMessage march = tc.engine.request("req_world_march", Exec.args(
                    "type", GATHER_MARCH_TYPE,
                    "endpoint", point,
                    "formation", formation,
                    "extra", new LinkedHashMap<String, Object>()));
            long id = Exec.marchId(march);
            if (id > 0L) {
                st.put("marchId", id);
                st.put("targetLevel", targetLevel);
                st.put("appliedAt", now);
                st.put("state", "WAITING_RETURN");
                saveHeavyLiftingState(tc, st);
                tc.saveModText("gather", "heavy",
                        "重物搬运已派出（" + targetLevel + "级"
                                + GatherConfigV2.resourceLabel(cfg.heavyLiftingResourceType) + "，march#" + id + "）");
                applyGatherResponse(lib, march);
            } else {
                int code = Exec.codeOf(march);
                st.put("cooldownUntil", now + (code == 2878 ? 60_000L : 5L * 60L * 1000L));
                saveHeavyLiftingState(tc, st);
                tc.saveModText("gather", "heavy", "重物搬运出征失败（码" + code + "）");
            }
        } catch (Throwable t) {
            try {
                tc.saveModText("gather", "heavy",
                        "重物搬运异常：" + ServerTasks.brief(String.valueOf(t.getMessage())));
            } catch (Throwable ignored) {
            }
        }
    }

    static JSONObject heavyLiftingState(TaskContext tc) {
        try {
            String raw = tc.store.getString(heavyLiftingStateKey(tc));
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    static void saveHeavyLiftingState(TaskContext tc, JSONObject st) {
        try {
            tc.store.putString(heavyLiftingStateKey(tc), st == null ? "{}" : st.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 专用队列是否仍在途（资产库未知时保守为 true，避免误判完成重复消耗技能）。 */
    static boolean heavyMarchActive(CoreAssetLibrary lib, long marchId) {
        if (marchId <= 0L) return false;
        if (lib == null || !lib.ready() || lib.queue() == null) return true;
        for (AssetSnapshot.QueueMarch march : lib.queue().marches) {
            if (march != null && march.id == marchId) return true;
        }
        return false;
    }

    /** 重物搬运编队参数（ROLE/AUTOMATIC，口径与采集队列一致）。 */
    static JSONObject heavyLiftingFormationParams(GatherConfigV2 cfg) {
        JSONObject f = new JSONObject();
        if (cfg == null) return f;
        long total = cfg.heavyLiftingInfantry + cfg.heavyLiftingLancer + cfg.heavyLiftingMarksman;
        if (GatherConfigV2.MODE_ROLE.equals(GatherConfigV2.normalizeMode(cfg.heavyLiftingFormationMode))) {
            f.put("formation_mode", GatherConfigV2.MODE_ROLE);
            f.put("formation_id", cfg.heavyLiftingFormationId);
            JSONObject soldiers = new JSONObject();
            soldiers.put("infantry", Math.max(0L, cfg.heavyLiftingInfantry));
            soldiers.put("lancer", Math.max(0L, cfg.heavyLiftingLancer));
            soldiers.put("marksman", Math.max(0L, cfg.heavyLiftingMarksman));
            f.put("soldiers", soldiers);
            return f;
        }
        f.put("formation_mode", GatherConfigV2.MODE_AUTOMATIC);
        JSONObject ratio = new JSONObject();
        if (total > 0L) {
            ratio.put("infantry", Math.max(0L, cfg.heavyLiftingInfantry));
            ratio.put("lancer", Math.max(0L, cfg.heavyLiftingLancer));
            ratio.put("marksman", Math.max(0L, cfg.heavyLiftingMarksman));
        } else {
            ratio.put("infantry", 40L);
            ratio.put("lancer", 30L);
            ratio.put("marksman", 30L);
        }
        f.put("ratio", ratio);
        f.put("total", total);
        return f;
    }

    private static Map<String, Object> heavyLiftingFormation(TaskContext tc, GatherConfigV2 cfg,
                                                             StringBuilder reasonOut) {
        try {
            FormationResolver.ResolveOutcome ro = FormationResolver.resolveOutcome(tc.engine,
                    FormationResolver.libraryFor(tc, "gather"), heavyLiftingFormationParams(cfg));
            if (!ro.ready()) {
                if (reasonOut != null) reasonOut.append(ro.reason);
                return null;
            }
            return ro.formation.toFormationPayload();
        } catch (Throwable t) {
            if (reasonOut != null) reasonOut.append("编队异常：").append(ServerTasks.brief(String.valueOf(t.getMessage())));
            return null;
        }
    }

    /** 王国可采资源等级上限：资产库 clock.maxResourceLevel 优先，其次 kingdomDay 推导，未知=8。 */
    static int gatherMaxResourceLevel(AssetSnapshot.Clock clock) {
        if (clock != null) {
            if (clock.maxResourceLevel > 0) {
                return GatherConfigV2.clampInt(clock.maxResourceLevel, GatherConfigV2.MIN_LEVEL,
                        GatherConfigV2.MAX_RESOURCE_LEVEL);
            }
            if (clock.kingdomDay > 0) {
                return GatherConfigV2.maxLevelForKingdomDay(clock.kingdomDay);
            }
        }
        return GATHER_DEFAULT_MAX_RESOURCE_LEVEL;
    }

    /** 采集暂停原因（资产库事实）：巨熊活动窗口 / 情报待办优先；null=可派。 */
    static String gatherPauseReason(CoreAssetLibrary lib, long nowSec) {
        return gatherPauseReason(lib, nowSec, null);
    }

    /** 同 {@link #gatherPauseReason(CoreAssetLibrary, long)}；tc 非空时排除“去重窗内不可派”的情报事件。 */
    static String gatherPauseReason(CoreAssetLibrary lib, long nowSec, TaskContext tc) {
        return gatherPauseReason(lib, nowSec, tc, true);
    }

    /**
     * 同 {@link #gatherPauseReason(CoreAssetLibrary, long, TaskContext)}，但显式指定 intel 任务是否启用。
     *
     * <p>2026-10-25 P0：intel 未启用时其待办事件永远无人处理，不能作为采集让位依据；
     * 巨熊活动窗口仍按资产库事实让位（与 intel 开关无关）。
     *
     * @param intelEnabled 本角色 intel 任务是否启用；false → 情报待办不计入优先级
     */
    static String gatherPauseReason(CoreAssetLibrary lib, long nowSec, TaskContext tc,
                                    boolean intelEnabled) {
        if (lib == null || !lib.ready()) return null;
        AssetSnapshot.Bear bear = lib.bear();
        long reserve = bear == null ? 0L : bear.reserveTime;
        long start = bear == null ? 0L : bear.massStartTime;
        long end = bear == null ? 0L : bear.massEndTime;
        int intel = 0;
        if (intelEnabled) {
            try {
                intel = intelGatherPriorityCount(lib.intel(), lib.stamina(), nowSec, tc);
            } catch (Throwable ignored) {
            }
        }
        return gatherPauseReason(reserve, start, end, intel, nowSec);
    }

    /**
     * 情报优先事件数（用户规则 2，2026-10-31 修正）：
     * 只统计 <b>status=1 且占普通行军队列</b>的可派事件——即狩猎类 1/11/100；
     * 营救(2) 走 301 特殊队列、英雄之旅(3) 是 PVE，都<b>不与采集争普通队列</b>，不能让采集让位
     * （否则营救类可无限等待，采集被永久饿死）。
     *
     * <p>体力要求：体力 >= Σ事件体力成本时才返回事件数（体力不足时情报发不出去，采集不应让位）。
     */
    static int intelGatherPriorityCount(AssetSnapshot.Intel intel, AssetSnapshot.Stamina stamina,
                                        long nowSec) {
        return intelGatherPriorityCount(intel, stamina, nowSec, null);
    }

    /** 同 {@link #intelGatherPriorityCount(AssetSnapshot.Intel, AssetSnapshot.Stamina, long)}；
     *  tc 非空时排除情报去重窗内（已尝试派出/失败阻塞）的事件——否则 301 英雄占用类无法派出的事件
     *  会永久阻塞采集（生产实证：14 条事件全部 301 → 采集持续让位）。 */
    static int intelGatherPriorityCount(AssetSnapshot.Intel intel, AssetSnapshot.Stamina stamina,
                                        long nowSec, TaskContext tc) {
        if (intel == null || intel.events == null || intel.events.isEmpty()) return 0;
        int count = 0;
        long costSum = 0L;
        for (AssetSnapshot.IntelEvent e : intel.events.values()) {
            if (e == null || e.status != 1L) continue;
            if (e.expireTs > 0L && e.expireTs <= nowSec) continue;
            int type = intelEventType(e.eventId);
            if (!intelConsumesNormalMarchSlot(type)) continue;   // 只有狩猎类占普通队列
            if (tc != null && intelDispatchBlocked(tc, e.id, nowSec * 1000L)) continue;
            count++;
            costSum += intelStaminaCost(type);
        }
        if (count <= 0) return 0;
        long st = stamina == null || !stamina.known() ? 0L : stamina.staminaAt(nowSec);
        return st >= costSum ? count : 0;
    }

    /** 纯函数：巨熊窗口 [reserve-600, reserve+1800] / [mass_start-600, mass_end]；情报可派优先。 */
    static String gatherPauseReason(long bearReserve, long bearMassStart, long bearMassEnd,
                                    int intelDispatchableCount, long nowSec) {
        if (bearReserve > 0L && nowSec >= bearReserve - 600L && nowSec < bearReserve + 1800L) {
            return "巨熊活动窗口，采集让位";
        }
        if (bearMassStart > 0L) {
            long begin = bearMassStart - 600L;
            long finish = bearMassEnd > 0L ? bearMassEnd : bearMassStart + 1800L;
            if (nowSec >= begin && nowSec < finish) return "巨熊活动窗口，采集让位";
        }
        if (intelDispatchableCount > 0) return "情报待办优先，采集让位";
        return null;
    }

    /** 巨熊活动窗口暂停（狩猎/巨兽用）：lib 未就绪或无窗口 → null。 */
    static String bearWindowPauseReason(CoreAssetLibrary lib, long nowSec) {
        if (lib == null || !lib.ready()) return null;
        AssetSnapshot.Bear bear = lib.bear();
        if (bear == null) return null;
        return gatherPauseReason(bear.reserveTime, bear.massStartTime, bear.massEndTime, 0, nowSec);
    }

    /** 特殊队列条数（仅展示；不参与普通队列空闲计算）。 */
    static int specialQueueCount(AssetSnapshot.Queue queue) {
        if (queue == null || queue.specialByType == null) return 0;
        int n = 0;
        for (Integer v : queue.specialByType.values()) {
            if (v != null) n += v.intValue();
        }
        return n;
    }

    /** v2 续派唤醒：最早在途采集行军 endTime+250ms；无 → now+3s；封顶 now+180s。 */
    static long gatherV2WakeMillis(long nowMillis, long earliestMarchEndMillis) {
        if (earliestMarchEndMillis <= 0L) return nowMillis + GATHER_V2_WAKE_FALLBACK_MILLIS;
        long wake = earliestMarchEndMillis + GATHER_WAKE_AFTER_END_MILLIS;
        if (wake <= nowMillis) wake = nowMillis + GATHER_WAKE_AFTER_END_MILLIS;
        return Math.min(nowMillis + GATHER_WAKE_CAP_MILLIS, wake);
    }

    /** 采集行军结束毫秒（秒/毫秒兼容，同 {@link #gatherMarchEndMillis} 口径）。 */
    static long queueMarchEndMillis(AssetSnapshot.QueueMarch march) {
        if (march == null || march.endTime <= 0L) return 0L;
        return march.endTime >= 100_000_000_000L ? march.endTime : march.endTime * 1000L;
    }

    /** type=4/110 在途采集行军的最早结束毫秒（0=无）。 */
    static long gatherV2EarliestMarchEndMillis(List<AssetSnapshot.QueueMarch> marches) {
        long earliest = 0L;
        if (marches == null) return 0L;
        for (AssetSnapshot.QueueMarch march : marches) {
            if (march == null) continue;
            if (march.type != GATHER_MARCH_TYPE && march.type != GATHER_MINE_MARCH_TYPE) continue;
            long end = queueMarchEndMillis(march);
            if (end <= 0L) continue;
            if (earliest == 0L || end < earliest) earliest = end;
        }
        return earliest;
    }

    /** 回灌 req_world_march 响应到资产库（可选：未接线/异常都不影响派队结果）。 */
    private static void applyGatherResponse(CoreAssetLibrary lib, GameMessage march) {
        if (lib == null || march == null || march.getContent() == null) return;
        try {
            lib.applyResponse("req_world_march", march.getContent());
        } catch (Throwable ignored) {
        }
    }

    /** v2 任何出口都写续派唤醒（战锁退避时唤醒不早于退避截止，且仍受 180s 上限约束）。 */
    private static TaskOutcome finishGatherV2(TaskContext tc, Exec.Multi m, CoreAssetLibrary lib) {
        long now = System.currentTimeMillis();
        long earliest = 0L;
        try {
            AssetSnapshot.Queue queue = lib == null ? null : lib.queue();
            earliest = gatherV2EarliestMarchEndMillis(queue == null ? null : queue.marches);
        } catch (Throwable ignored) {
        }
        long wake = gatherV2WakeMillis(now, earliest);
        long backoffUntil = stateLongOf(tc, gatherBackoffKey(tc));
        if (backoffUntil > now) {
            wake = Math.min(now + GATHER_WAKE_CAP_MILLIS, Math.max(wake, backoffUntil));
            if (wake <= now) wake = now + GATHER_V2_WAKE_FALLBACK_MILLIS;
        }
        writeGatherWake(tc, wake);
        return Exec.result(m, "gather", "野外采集");
    }

    private static long secondsLeft(long nowMillis, long untilMillis) {
        long remain = untilMillis - nowMillis;
        return remain <= 0L ? 0L : (remain + 999L) / 1000L;
    }

    private static int parseInt(String raw) {
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            return 0;
        }
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

    /** C3 业务态唤醒：情报派队后多久兜底再看一次（15 分钟；正常由结果推送提前唤醒）。 */
    static final long INTEL_NEXT_WAKE_MILLIS = 15L * 60L * 1000L;

    /** 用户规则 3：情报剩余 ≤30min 且队列满时，召回 1 个采集队列（同角色冷却 5 分钟）。 */
    static final long INTEL_PREEMPT_COOLDOWN_MILLIS = 300_000L;
    /** 用户规则 3：10 分钟内最多召回 2 队（绝不把采集全部召回）。 */
    static final long INTEL_PREEMPT_WINDOW_MILLIS = 600_000L;
    static final int INTEL_PREEMPT_MAX_PER_WINDOW = 2;
    /** 召回后重派等待（队列释放有推送/响应延迟）。 */
    static final long INTEL_PREEMPT_RETRY_DELAY_MILLIS = 15_000L;
    /** 召回失败码：行军不存在/已结束（等同于队列已释放，不再重试该队）。 */
    static final int INTEL_ERR_MARCH_GONE = 2806;

    /**
     * C3 业务态唤醒（纯函数）：本轮<b>派出情报行军</b>后，下一次兜底检查时刻。
     *
     * <p>1:1 网页版语义：派队后 TaskEngine 睡到"行军/战斗结果"（结果推送由
     * {@code notify_intelligence_events/battle_result} 触发 {@link TaskWakeRouter} 立即唤醒）；
     * 服务器侧给 15 分钟兜底，避免推送丢失时 15s 节奏反复空跑。
     * 只领奖（status==2）/无事件轮不设，交给固定 15s 节奏（领奖即完成）。
     *
     * @return epoch millis；0=不覆盖节奏间隔
     */
    static long intelNextWakeAt(long nowMillis, int dispatched) {
        return dispatched > 0 ? nowMillis + INTEL_NEXT_WAKE_MILLIS : 0L;
    }

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
        // C3：方法级计数（try 外的 dispatched 作用域），用于落盘业务态唤醒时刻。
        int dispatchedAny = 0;
        int pendingAny = 0;
        boolean preemptedAny = false;
        boolean eventsEmpty = true;
        boolean dedupedOrInflight = false;
        long nowSecAny = System.currentTimeMillis() / 1000L;
        long roundStartAny = 0L;
        try {
            tc.engine.collectNotifies(2500);
            Map<Long, Map<String, Object>> events = new LinkedHashMap<>();
            // 资产库优先（notify_intelligence_info/events/del 已由解析器合并为权威快照）；
            // 未接线/未 READY/快照为空时回退旧 notify 直读，保证推送缺失时仍可用。
            boolean eventsFromAssets = false;
            CoreAssetLibrary intelLib = assetReadsEnabled()
                    ? CoreAssetRegistry.global().get(tc.engine) : null;
            if (intelLib != null && intelLib.ready() && !intelLib.intel().events.isEmpty()) {
                events.putAll(intelEventsFromAssets(intelLib.intel()));
                eventsFromAssets = true;
            }
            if (!eventsFromAssets) {
                mergeIntelEvents(tc.engine, "notify_intelligence_info", events);    // 全量初始化
                mergeIntelEvents(tc.engine, "notify_intelligence_events", events);  // 增量
                Map<String, Object> del = notifyContentOf(tc.engine, "notify_intelligence_events_del");
                if (del != null) {
                    for (Object rawId : rawValues(del.get("ids"))) {
                        long id = Exec.longOf(rawId);
                        if (id > 0) events.remove(id);
                    }
                }
            }
            eventsEmpty = events.isEmpty();
            if (eventsEmpty) {
                m.skip("暂无情报事件（下一轮 " + IntelRound.roundLabel(IntelRound.nextRefreshSec(nowSecAny))
                        + " 刷新）");
            } else {
                // I2 门控（用户规则 2，2026-10-31 修正）：
                // ① 普通队列数 = min(世界容量, 配置 queueCount，默认 1)；
                // ② 在途普通情报只数 type=302（狩猎）；301 营救走特殊队列，不占普通队列；
                // ③ 体力门：狩猎 10 点、营救/英雄之旅 12 点、保留 50 点底仓；
                // ④ 用户规则 3：狩猎剩余 ≤30min 且普通队列满 → 召回 1 个采集队列腾位（不全部召回）。
                JSONObject ip = tc.params("intel");
                long now = System.currentTimeMillis();
                long nowSec = now / 1000L;
                nowSecAny = nowSec;
                roundStartAny = intelRoundStartOf(events, nowSec);
                boolean hasDispatchable = false;
                long expiringHuntExpire = 0L;
                for (Map<String, Object> e : events.values()) {
                    long id = Exec.longOf(e.get("id"));
                    if (id <= 0) continue;
                    long status = e.containsKey("status") ? Exec.longOf(e.get("status")) : 1L;
                    if (status != 1) continue;
                    int t = intelEventType(intelEventConfigId(e));
                    if (t == 0 || intelDispatchBlocked(tc, id, now)) continue;
                    long expireTs = Exec.longOf(e.get("expire_ts"));
                    if (IntelRound.expired(nowSec, expireTs)) continue;
                    hasDispatchable = true;
                    if (intelConsumesNormalMarchSlot(t) && expireTs > 0L
                            && (expiringHuntExpire <= 0L || expireTs < expiringHuntExpire)) {
                        expiringHuntExpire = expireTs;     // 最紧急的狩猎事件
                    }
                }
                // I2 队列门控（用户规则 2）：营救(2)/英雄之旅(3) 不占普通行军队列；
                // 狩猎(1)/活动巨兽(11)/冒险巨兽(100) 才占普通队列（type=302）。
                int allowedSlots = 0, activeNormalIntel = 0, activeSpecialIntel = 0;
                int remainingNormalSlots = 0;
                boolean staminaKnown = false;
                long usableStamina = Long.MAX_VALUE;
                IntelQueueSnapshot intelQueue = null;
                if (hasDispatchable) {
                    // 页面/请求只在有可派事件时取，避免纯领奖轮次多发队列查询。
                    intelQueue = intelQueueSnapshot(tc.engine);
                    allowedSlots = intelQueueSlots(intelQueueCount(ip), intelQueue.capacity);
                    activeNormalIntel = intelQueue.activeNormalIntelMarches;
                    activeSpecialIntel = intelQueue.activeSpecialIntelMarches;
                    remainingNormalSlots = intelQueue.queueIdleKnown
                            ? intelRemainingNormalSlots(allowedSlots, activeNormalIntel, intelQueue.normalIdle)
                            : intelRemainingNormalSlots(allowedSlots, activeNormalIntel);
                    Long stamina = intelStamina(tc.engine);
                    staminaKnown = stamina != null;
                    if (staminaKnown) usableStamina = Math.max(0L, stamina - INTEL_STAMINA_RESERVE);
                    // 用户规则 3：仅当“狩猎快过期 + 普通队列真被采集占满”时才召回 1 队采集。
                    if (intelNeedsQueuePreempt(intelQueue, allowedSlots, activeNormalIntel,
                            expiringHuntExpire, nowSec) && intelPreemptEnabled(tc)) {
                        String preemptText = tryPreemptGatherForIntel(tc, now, nowSec);
                        if (preemptText != null) {
                            preemptedAny = true;
                        }
                    }
                }
                int handled = 0, claimed = 0, dispatched = 0, waiting = 0, deduped = 0;
                int queueFullBlocked = 0, staminaBlocked = 0;
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
                    long expireTs = Exec.longOf(e.get("expire_ts"));
                    if (IntelRound.expired(nowSec, expireTs)) continue;   // 已过期不派（只等推送删除）
                    if (intelDispatchBlocked(tc, id, now)) {
                        // 已派出/已开战（或 10 分钟内刚失败）：等推送把 status 变 2 或去重窗过期。
                        deduped++;
                        continue;
                    }
                    boolean worldMarch = intelIsWorldMarch(type);
                    long staminaCost = intelStaminaCost(type);
                    int gate = intelDispatchGate(type, remainingNormalSlots, staminaKnown, usableStamina);
                    if (gate == INTEL_GATE_QUEUE_FULL) {
                        queueFullBlocked++;     // 普通队列满：留待下一次节奏，不写失败（营救类不走这里）
                        waiting++;
                        if (preemptedAny) {
                            appendWait(waits, "队列腾位中");
                        } else if (IntelRound.expiringSoon(nowSec, expireTs)) {
                            appendWait(waits, "30分钟内消失且队列满");
                        }
                        continue;   // 不写去重窗：下一轮腾位/让位后仍可派
                    }
                    if (gate == INTEL_GATE_STAMINA) {
                        staminaBlocked++;       // 体力不足：留待下一次节奏，不写失败
                        waiting++;
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
                                markIntelDispatched(tc, id, now);   // 失败计入去重窗，避免 15s 重试风暴+阻塞采集
                                appendWait(waits, "英雄之旅结算码" + Exec.codeOf(en));
                            }
                        } else {
                            int sc = Exec.codeOf(st);
                            waiting++;
                            if (sc != 51) markIntelDispatched(tc, id, now);   // 非体力失败：计入去重窗
                            appendWait(waits, "英雄之旅码" + sc);
                            if (sc == 51) break;   // 体力不足：本轮停止，等恢复（避免连续失败刷请求）
                        }
                    } else if (worldMarch) {
                        long marchType = type == 2 ? 301L : 302L;
                        // 用户规则 4：情报编队——指定编队(ROLE) > 情报设置(英雄/比例) > 默认
                        // 最强盾/波根(矛)/吉娜(弓)（缺失回退最强矛/弓）。不再回退“游戏第 1 队”。
                        FormationResolver.ResolveOutcome fro = IntelFormationPlan.resolve(tc);
                        if (!fro.ready()) {
                            waiting++;
                            markIntelDispatched(tc, id, now);   // 编队不可用计入去重窗（防 15s 请求风暴）
                            appendWait(waits, fro.reason.isEmpty() ? "无英雄可派" : "编队：" + fro.reason);
                            continue;
                        }
                        Map<String, Object> formation = fro.formation.toFormationPayload();
                        tc.saveModText("intel", "formation", fro.formation.describe() + " · " + fro.reason);
                        GameMessage march = tc.engine.request("req_world_march", Exec.args(
                                "type", marchType,
                                "endpoint", Exec.point(Exec.longOf(e.get("x")), Exec.longOf(e.get("y"))),
                                "formation", formation,
                                "extra", Exec.args("event_id", id)));
                        if (Exec.marchId(march) > 0) {
                            handled++;
                            dispatched++;
                            // 仅占普通队列的情报事件扣剩余槽位；营救(301)不占普通队列，不扣。
                            if (intelConsumesNormalMarchSlot(type)) remainingNormalSlots--;
                            markIntelDispatched(tc, id, now);
                            if (staminaKnown) usableStamina = Math.max(0L, usableStamina - staminaCost);
                            // P0-5：成功出征复位 415 英雄上限/406 兵力缩放
                            HeroAvailability.markSuccess(tc.engine);
                        } else {
                            int mc = Exec.codeOf(march);
                            waiting++;
                            if (mc == GATHER_ERR_HERO_AWAY || mc == GATHER_ERR_HERO_BUSY) {
                                blockHeroesOn301(tc.engine, formation);
                            }
                            if (mc == GATHER_ERR_FORMATION_LIMIT) {
                                HeroAvailability.noteHeroCountRejected(tc.engine,
                                        HeroAvailability.heroIdsOf(formation).size());
                            }
                            if (mc == GATHER_ERR_SOLDIER_LIMIT) {
                                int scale = HeroAvailability.noteSoldierLimitRejected(tc.engine);
                                FileLog.warn("intel 406 fpid={} heroes={} soldiers={} scale→{}%",
                                        tc.fpid, HeroAvailability.heroIdsOf(formation),
                                        formation.get("soldier"), scale);
                            }
                            if (mc != 51) markIntelDispatched(tc, id, now);   // 非体力失败：计入去重窗（修采集饿死）
                            appendWait(waits, "派队码" + mc);
                            if (mc == 51) break;   // 体力不足：本轮停止，等恢复（避免连续失败刷请求）
                        }
                    }
                }
                dispatchedAny = dispatched;
                pendingAny = intelPendingDispatchableCount(tc, events, now, nowSec);
                boolean inFlight = false;
                for (Map<String, Object> e : events.values()) {
                    if (e != null && Exec.longOf(e.get("status")) == 3L) {
                        inFlight = true;
                        break;
                    }
                }
                dedupedOrInflight = deduped > 0 || inFlight;
                long remainSec = intelRoundRemainSec(events, nowSec);
                String roundText = " · " + IntelRound.roundLabel(roundStartAny)
                        + (remainSec > 0L ? " · 剩 " + IntelRound.remainingLabel(remainSec) : "");
                String preemptText = preemptedAny ? "；已召回 1 个采集队列腾位（不会全部召回）" : "";
                String statusText;
                if (handled > 0) {
                    statusText = "处理情报×" + handled
                            + (claimed > 0 ? "（领奖" + claimed + "）" : "")
                            + (dispatched > 0 ? "（派队" + dispatched + "）" : "")
                            + preemptText + roundText;
                    m.claimed(statusText);
                } else if (preemptedAny) {
                    statusText = "已召回 1 个采集队列为情报腾位（剩余采集仍保留，不会全部召回），15 秒后重派"
                            + roundText;
                    m.skip(statusText);
                } else if (queueFullBlocked > 0) {
                    statusText = "情报队列已满(普通情报" + activeNormalIntel + "/" + allowedSlots + "·空闲"
                            + remainingNormalSlots
                            + (activeSpecialIntel > 0 ? "·营救在途" + activeSpecialIntel : "")
                            + ")，营救/英雄之旅不受队列限制" + roundText;
                    m.skip(statusText);
                } else if (staminaBlocked > 0
                        || (hasDispatchable && staminaKnown && usableStamina < intelStaminaCost(1))) {
                    statusText = "情报体力不足(可用" + usableStamina + "·保留" + INTEL_STAMINA_RESERVE
                            + ")，待下一次" + roundText;
                    m.skip(statusText);
                } else if (waiting > 0) {
                    statusText = "情报待重试：" + waits.toString().trim() + roundText;
                    m.skip(statusText);
                } else if (deduped > 0) {
                    statusText = "情报行军/领奖进行中，待下一次" + roundText;
                    m.skip(statusText);
                } else {
                    statusText = "无可派情报" + roundText;
                    m.skip(statusText);
                }
                tc.saveModText("intel", "events", statusText);
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
        TaskOutcome out = Exec.result(m, "intel", "情报任务");
        // C3 业务态唤醒（网页版 TaskDecision.SleepUntil，2026-10-31 轮次化）：
        // - 刚召回采集队 → 15s 后重派；
        // - 无事件 → 下一 0/8/16 刷新点 +5s（新事件仍由 TaskWakeRouter 推送即时唤醒）；
        // - 还有可派但被门控 → 60s 重试；已派出/战斗中 → 15min 兜底（结果推送会提前唤醒）。
        long nextWake = IntelRound.intelWakeAt(System.currentTimeMillis(), nowSecAny, dispatchedAny,
                pendingAny, preemptedAny, eventsEmpty, dedupedOrInflight);
        if (nextWake > 0L) out.wakeAt(nextWake);
        return out;
    }

    // ---------- 情报轮次 / 采集让位（2026-10-31 用户规则 1/3） ----------

    /** 本轮开始时刻：由事件 expire_ts 反推（优先）→ 无 expire 时按当前 0/8/16 轮次。 */
    static long intelRoundStartOf(Map<Long, Map<String, Object>> events, long nowSec) {
        long start = 0L;
        if (events != null) {
            for (Map<String, Object> e : events.values()) {
                if (e == null) continue;
                long rs = IntelRound.roundStartSecOfExpire(Exec.longOf(e.get("expire_ts")));
                if (rs > start) start = rs;
            }
        }
        return start > 0L ? start : IntelRound.currentRoundStartSec(nowSec);
    }

    /** 本轮剩余时间（取事件 expire_ts 最大值；未知/已过期 → 0）。 */
    static long intelRoundRemainSec(Map<Long, Map<String, Object>> events, long nowSec) {
        long expire = 0L;
        if (events != null) {
            for (Map<String, Object> e : events.values()) {
                if (e == null) continue;
                long ts = Exec.longOf(e.get("expire_ts"));
                if (ts > expire) expire = ts;
            }
        }
        return IntelRound.remainingSec(nowSec, expire);
    }

    /** 仍有“可派但本轮没派出去”的事件数（status=1/未过期/不在去重窗/类型可识别）。 */
    static int intelPendingDispatchableCount(TaskContext tc, Map<Long, Map<String, Object>> events,
                                             long nowMillis, long nowSec) {
        if (events == null || events.isEmpty()) return 0;
        int n = 0;
        for (Map<String, Object> e : events.values()) {
            if (e == null) continue;
            long id = Exec.longOf(e.get("id"));
            if (id <= 0L) continue;
            long status = e.containsKey("status") ? Exec.longOf(e.get("status")) : 1L;
            if (status != 1L) continue;
            if (intelEventType(intelEventConfigId(e)) == 0) continue;
            if (IntelRound.expired(nowSec, Exec.longOf(e.get("expire_ts")))) continue;
            if (tc != null && intelDispatchBlocked(tc, id, nowMillis)) continue;
            n++;
        }
        return n;
    }

    /**
     * 是否需要为情报召回 1 队采集（纯函数）：
     * 有狩猎事件剩余 ≤30min，且普通队列“真的被采集/其它占满”（worldIdle=0），
     * 且不是情报自己的 302 占满（activeNormal &lt; allowedSlots）。
     */
    static boolean intelNeedsQueuePreempt(IntelQueueSnapshot q, int allowedSlots,
                                          int activeNormalIntel, long expiringHuntExpireTs,
                                          long nowSec) {
        if (q == null || expiringHuntExpireTs <= 0L) return false;
        if (!q.queueIdleKnown || q.normalIdle > 0) return false;
        if (activeNormalIntel >= Math.max(1, allowedSlots)) return false;
        return IntelRound.expiringSoon(nowSec, expiringHuntExpireTs);
    }

    /** 采集让位开关：env {@code VAULT_INTEL_PREEMPT=off/0/false} 硬关；否则 intel params 默认 true。 */
    static boolean intelPreemptEnabled(TaskContext tc) {
        String env = System.getenv("VAULT_INTEL_PREEMPT");
        if (env != null) {
            String v = env.trim().toLowerCase(java.util.Locale.ROOT);
            if ("off".equals(v) || "0".equals(v) || "false".equals(v)) return false;
        }
        if (tc == null) return false;
        try {
            JSONObject p = tc.params("intel");
            return !p.has("intel_preempt_gather") || p.optBoolean("intel_preempt_gather", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 统计我方 type=4 采集行军数（state 2/3、owner 自己或未知）。 */
    static int intelCountGatherMarches(List<AssetSnapshot.QueueMarch> marches, long ownUid) {
        int n = 0;
        if (marches == null) return 0;
        for (AssetSnapshot.QueueMarch m : marches) {
            if (m == null) continue;
            if (m.type != 4L) continue;
            if (m.state != 2L && m.state != 3L) continue;
            if (m.owner > 0L && ownUid > 0L && m.owner != ownUid) continue;
            n++;
        }
        return n;
    }

    /**
     * 选出要召回的采集队（纯函数）：只 type=4、state=2/3、我方；优先在途(2)，其次剩余采集时间最短，
     * 再按 marchId 小。绝不返回保护类（5/99/102/411）。
     */
    static AssetSnapshot.QueueMarch intelChooseGatherToRecall(List<AssetSnapshot.QueueMarch> marches,
                                                              long ownUid) {
        AssetSnapshot.QueueMarch best = null;
        if (marches == null) return null;
        long bestEnd = Long.MAX_VALUE;
        for (AssetSnapshot.QueueMarch m : marches) {
            if (m == null || m.id <= 0L) continue;
            if (m.type != 4L) continue;
            if (m.state != 2L && m.state != 3L) continue;
            if (m.owner > 0L && ownUid > 0L && m.owner != ownUid) continue;
            long end = queueMarchEndMillis(m);
            if (end <= 0L) end = Long.MAX_VALUE;
            if (best == null) {
                best = m;
                bestEnd = end;
                continue;
            }
            boolean bestInFlight = best.state == 2L;
            boolean candInFlight = m.state == 2L;
            if (candInFlight != bestInFlight) {
                if (candInFlight) { best = m; bestEnd = end; }
                continue;
            }
            if (end < bestEnd || (end == bestEnd && m.id < best.id)) {
                best = m;
                bestEnd = end;
            }
        }
        return best;
    }

    /** 情报采集让位状态键（按角色隔离）：{@code srv:intel:preempt:<fpid>:<sid>:<uid>}。 */
    static String intelPreemptKey(TaskContext tc) {
        return "srv:intel:preempt:" + tc.fpid + ":" + tc.sid + ":" + tc.roleUid;
    }

    static JSONObject intelPreemptState(TaskContext tc) {
        try {
            String raw = tc.store.getString(intelPreemptKey(tc));
            return raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    /** 冷却 + 10 分钟窗口上限判定（纯函数）。 */
    static boolean intelPreemptBudgetAllows(JSONObject state, long nowMillis) {
        if (state == null) return true;
        long last = state.optLong("last", 0L);
        if (last > 0L && nowMillis - last < INTEL_PREEMPT_COOLDOWN_MILLIS) return false;
        JSONArray recent = state.optJSONArray("recent");
        int n = 0;
        if (recent != null) {
            for (int i = 0; i < recent.length(); i++) {
                long t = recent.optLong(i, 0L);
                if (t > 0L && nowMillis - t <= INTEL_PREEMPT_WINDOW_MILLIS) n++;
            }
        }
        return n < INTEL_PREEMPT_MAX_PER_WINDOW;
    }

    static void markIntelPreempt(TaskContext tc, long nowMillis) {
        JSONObject o = new JSONObject();
        o.put("last", nowMillis);
        JSONArray recent = new JSONArray();
        JSONObject prev = intelPreemptState(tc);
        JSONArray old = prev.optJSONArray("recent");
        if (old != null) {
            for (int i = 0; i < old.length(); i++) {
                long t = old.optLong(i, 0L);
                if (t > 0L && nowMillis - t <= INTEL_PREEMPT_WINDOW_MILLIS) recent.put(t);
            }
        }
        recent.put(nowMillis);
        o.put("recent", recent);
        try {
            tc.store.putString(intelPreemptKey(tc), o.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 用户规则 3：情报剩余 ≤30min 且普通队列满时，召回且仅召回 1 个采集队列。
     *
     * <p>安全约束：只 type=4（采集）、state=2/3、我方；**至少 2 队采集才召回**（只有 1 队=全部召回，
     * 违反用户“不要全部召回”）；同角色 5 分钟冷却 + 10 分钟最多 2 队；绝不碰驻防/集结/上车等保护类。
     *
     * @return 成功时返回状态文案；未召回返回 null（只记日志，不重试风暴）
     */
    static String tryPreemptGatherForIntel(TaskContext tc, long nowMillis, long nowSec) {
        if (tc == null || tc.engine == null) return null;
        List<AssetSnapshot.QueueMarch> marches = null;
        try {
            CoreAssetLibrary lib = assetReadsEnabled()
                    ? CoreAssetRegistry.global().get(tc.engine) : null;
            if (lib != null && lib.ready() && lib.queue() != null) {
                marches = lib.queue().marches;
            }
        } catch (Throwable ignored) {
        }
        long ownUid = tc.engine.lastUid();
        int gatherCount = intelCountGatherMarches(marches, ownUid);
        if (gatherCount < 2) {
            FileLog.info("intel 采集让位跳过 fpid={}：采集行军不足 2 队（{} 队，不全部召回）",
                    tc.fpid, gatherCount);
            return null;
        }
        JSONObject state = intelPreemptState(tc);
        if (!intelPreemptBudgetAllows(state, nowMillis)) {
            FileLog.info("intel 采集让位冷却中 fpid={} last={}", tc.fpid, state.optLong("last", 0L));
            return null;
        }
        AssetSnapshot.QueueMarch cand = intelChooseGatherToRecall(marches, ownUid);
        if (cand == null) return null;
        String proto = cand.state == 3L ? "req_world_march_off_work" : "req_world_march_recall";
        int code;
        try {
            GameMessage rsp = tc.engine.request(proto, Exec.args("id", cand.id));
            code = Exec.codeOf(rsp);
        } catch (Throwable t) {
            FileLog.warn("intel 采集让位召回异常 fpid={} marchId={}：{}",
                    tc.fpid, cand.id, String.valueOf(t.getMessage()));
            return null;
        }
        if (code == 0 || code == INTEL_ERR_MARCH_GONE) {
            markIntelPreempt(tc, nowMillis);
            long remain = cand.endTime > 0L ? Math.max(0L,
                    queueMarchEndMillis(cand) / 1000L - nowSec) : 0L;
            FileLog.info("intel 采集让位 fpid={} 召回 marchId={} type={} state={} code={} 剩余采集≈{}s"
                            + "（采集 {}→{} 队）",
                    tc.fpid, cand.id, cand.type, cand.state, code, remain,
                    gatherCount, gatherCount - 1);
            return "已召回 1 个采集队列为情报腾位（采集" + gatherCount + "→" + (gatherCount - 1)
                    + " 队，不会全部召回）";
        }
        FileLog.warn("intel 采集让位失败 fpid={} marchId={} state={} code={}",
                tc.fpid, cand.id, cand.state, code);
        return null;
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

    // ---------- 情报普通队列语义（2026-10-31 用户规则 2：只有狩猎占普通行军队列） ----------
    // 用户口径：情报三种类型——狩猎/营救/英雄之旅；**除狩猎外不需要行军队列**。
    //   狩猎(1)/活动巨兽(11)/冒险巨兽(100) → req_world_march type=302，占普通队列；
    //   营救(2) → req_world_march type=301（特殊队列），不占普通队列；
    //   英雄之旅(3) → start/end_battle（PVE），不需要队列。
    // 生产根因（2026-10-31 用户反馈“情报一天没打过”）：旧实现把在途 **301 营救**也计入 active
    // 普通队列占用；默认 queue_count=1 时只要有一队营救在途，`remainingNormalSlots=0`，
    // 真正“打”的狩猎(302)永远派不出 → 全天只做营救/领奖。修复：普通 active 只数 302。

    /** 情报普通队列门控快照。 */
    static final class IntelQueueSnapshot {
        /** 普通行军队列容量（marchCapacity 口径，未再与配置取小）。 */
        final int capacity;
        /** 在途情报行军合计（301+302；仅诊断/旧键兼容，不参与普通队列扣减）。 */
        final int activeIntelligenceMarches;
        /** 在途普通情报行军（type=302 狩猎类，唯一参与普通队列扣减的口径）。 */
        final int activeNormalIntelMarches;
        /** 在途特殊情报行军（type=301 营救，不占普通队列）。 */
        final int activeSpecialIntelMarches;
        /** 是否拿到真实普通队列空闲（资产库 Queue.idle；false=只扣在途情报的兜底口径）。 */
        final boolean queueIdleKnown;
        /** 普通队列真实空闲（32 位保护性 clamp，≥0）。 */
        final int normalIdle;

        IntelQueueSnapshot(int capacity, int activeNormalIntelMarches,
                           int activeSpecialIntelMarches, boolean queueIdleKnown, int normalIdle) {
            this.capacity = Math.max(0, capacity);
            this.activeNormalIntelMarches = Math.max(0, activeNormalIntelMarches);
            this.activeSpecialIntelMarches = Math.max(0, activeSpecialIntelMarches);
            this.activeIntelligenceMarches = this.activeNormalIntelMarches + this.activeSpecialIntelMarches;
            this.queueIdleKnown = queueIdleKnown;
            this.normalIdle = Math.max(0, normalIdle);
        }
    }

    /**
     * 读取情报普通队列门控数据：
     * <ul>
     *   <li>资产库优先：{@code AssetSnapshot.Queue} 的 capacity/occupied/idle 已按网页版口径算好
     *       （301 等特殊队列不计普通占用，pending 也扣除），idle 与网页版 world.idle() 同义；</li>
     *   <li>未接线/未 READY/容量未知：回退 {@code req_world_march_detail_4_player_situation}
     *       分别统计 302（普通）与 301（特殊），空闲未知时按「容量 - 在途 302」口径。</li>
     * </ul>
     */
    static IntelQueueSnapshot intelQueueSnapshot(WjdrEngine engine) {
        int capacity = 0;
        int activeNormal = 0;
        int activeSpecial = 0;
        int normalIdle = 0;
        boolean idleKnown = false;
        if (engine != null && assetReadsEnabled()) {
            try {
                CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
                if (lib != null && lib.ready()) {
                    AssetSnapshot.Queue q = lib.queue();
                    if (q != null && q.known()) {
                        capacity = q.capacity;
                        normalIdle = q.idle;
                        idleKnown = true;
                        for (AssetSnapshot.QueueMarch march : q.marches) {
                            if (march == null) continue;
                            if (march.type == 302L) activeNormal++;
                            else if (march.type == 301L) activeSpecial++;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 资产库异常按未接线处理，走回退口径
            }
        }
        if (capacity <= 0) capacity = engine == null ? 0 : marchCapacity(engine);
        if (!idleKnown) {
            int[] split = engine == null ? new int[]{0, 0} : countIntelMarchesByType(engine);
            activeNormal = split[0];
            activeSpecial = split[1];
            normalIdle = Math.max(0, capacity - activeNormal);
        }
        return new IntelQueueSnapshot(capacity, activeNormal, activeSpecial, idleKnown, normalIdle);
    }

    /** 是否消耗普通世界行军队列（用户规则 2）：只有狩猎类（1/11/100）；营救(2)/英雄之旅(3) 不占。 */
    static boolean intelConsumesNormalMarchSlot(int type) {
        return type == 1 || type == 11 || type == 100;
    }

    /** 普通队列可派情报数 = clamp(min(队列配置, 世界容量) - 在途普通情报(302), 0, 世界空闲)。 */
    static int intelRemainingNormalSlots(int allowedSlots, int activeNormalIntelMarches, int worldIdle) {
        int slots = Math.max(0, allowedSlots - Math.max(0, activeNormalIntelMarches));
        return Math.max(0, Math.min(slots, Math.max(0, worldIdle)));
    }

    /** 同上，但世界空闲未知时的兜底口径：只扣在途普通情报(302)。 */
    static int intelRemainingNormalSlots(int allowedSlots, int activeNormalIntelMarches) {
        return Math.max(0, allowedSlots - Math.max(0, activeNormalIntelMarches));
    }

    /** 派队门控结果：可派。 */
    static final int INTEL_GATE_OK = 0;
    /** 派队门控结果：普通队列已满（营救类不会返回该值）。 */
    static final int INTEL_GATE_QUEUE_FULL = 1;
    /** 派队门控结果：体力不足。 */
    static final int INTEL_GATE_STAMINA = 2;

    /**
     * 单事件派队门控（纯函数，1:1 网页版 planActions 内联判定）：
     * 先判普通队列（仅消耗队列的事件），再判体力；体力未知不阻塞（推送未到时只做队列门）。
     */
    static int intelDispatchGate(int type, int remainingNormalSlots, boolean staminaKnown,
                                 long usableStamina) {
        if (intelConsumesNormalMarchSlot(type) && remainingNormalSlots <= 0) return INTEL_GATE_QUEUE_FULL;
        if (staminaKnown && !intelStaminaAllows(usableStamina, intelStaminaCost(type))) {
            return INTEL_GATE_STAMINA;
        }
        return INTEL_GATE_OK;
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
        // 资产库优先（recover 域 id=201 权威；count 可超上限，按网页版不 clamp）
        if (assetReadsEnabled()) {
            try {
                CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
                if (lib != null && lib.ready()) {
                    AssetSnapshot.Stamina st = lib.stamina();
                    if (st != null && st.known()) {
                        return st.staminaAt(lib.clock().serverNowSec());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Long v = intelStaminaOf(notifyContentOf(engine, "notify_recover_resource_update"));
        if (v == null) v = intelStaminaOf(notifyContentOf(engine, "notify_recover_resource_init"));
        return v;
    }

    /** 资产库情报事件 → 旧循环使用的 Map 形状（id/status/x/y/event_id/expire_ts/played）。 */
    static Map<Long, Map<String, Object>> intelEventsFromAssets(AssetSnapshot.Intel intel) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (intel == null || intel.events == null) return out;
        for (AssetSnapshot.IntelEvent e : intel.events.values()) {
            if (e == null || e.id <= 0L) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("status", e.status);
            m.put("x", e.x);
            m.put("y", e.y);
            m.put("event_id", e.eventId);
            m.put("expire_ts", e.expireTs);
            m.put("played", e.played);
            out.put(e.id, m);
        }
        return out;
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
     * 当前情报行军数拆分：{@code [普通(302 狩猎), 特殊(301 营救)]}。
     *
     * <p>用户规则 2：只有狩猎占普通行军队列；营救走 301 特殊队列、不占普通队列。
     * 资产库优先（push 维护），未接线时回退 {@code req_world_march_detail_4_player_situation} 单次查询。
     */
    static int[] countIntelMarchesByType(WjdrEngine engine) {
        int[] out = new int[]{0, 0};
        if (engine == null) return out;
        if (assetReadsEnabled()) {
            try {
                CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
                if (lib != null && lib.ready() && lib.queue() != null) {
                    for (AssetSnapshot.QueueMarch march : lib.queue().marches) {
                        if (march == null) continue;
                        if (march.type == 302L) out[0]++;
                        else if (march.type == 301L) out[1]++;
                    }
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("owner", engine.lastUid());
            args.put("kid", engine.lastServerId());
            GameMessage rsp = engine.request("req_world_march_detail_4_player_situation", args);
            if (rsp == null || rsp.getContent() == null) return out;
            Object code = rsp.getContent().get("code");
            if (code != null && Exec.longOf(code) != 0) return out;
            Map<String, Object> detail = Exec.mapOf(rsp.getContent().get("detail"));
            for (String k : new String[]{"marchs", "march_list", "list", "marches"}) {
                for (Map<String, Object> march : Exec.mapsOf(detail == null ? null : detail.get(k))) {
                    long t = Exec.longOf(march.get("type"));
                    if (t == 302L) out[0]++;
                    else if (t == 301L) out[1]++;
                }
                if (out[0] + out[1] > 0) break;
            }
            return out;
        } catch (Throwable ignored) {
            return out;
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
            // B3-T1：巨熊活动窗口让位（1:1 网页版 HuntTaskModule/DailyHuntService 暂停协调；
            // 只判巨熊窗口，不把情报优先用于狩猎，避免狩猎长期饥饿）。
            CoreAssetLibrary huntLib = assetReadsEnabled()
                    ? CoreAssetRegistry.global().get(tc.engine) : null;
            String huntPause = bearWindowPauseReason(huntLib,
                    huntLib == null ? 0L : huntLib.clock().serverNowSec());
            if (huntPause != null) {
                m.skip(huntPause);
                tc.saveModText("hunt", "hunt", huntPause);
                return Exec.result(m, "hunt", "自动狩猎");
            }
            JSONObject hp = tc.params("hunt");
            int lvMin = Math.max(1, Math.min(10, hp.optInt("level_min", 4)));
            int lvMax = Math.max(lvMin, Math.min(10, hp.optInt("level_max", 6)));
            int maxTimes = Math.max(1, Math.min(20, hp.optInt("max_times", 5)));
            int hunted = 0;
            int noTarget = 0;
            List<Long> allHeroes = Exec.allHeroes(tc.engine);
            // 批次10：新编队系统（formation_mode/id/soldiers/ratio）优先于旧 hero_ids/squad_index 分支
            boolean newFormation = FormationResolver.isConfigured(hp);
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
                StringBuilder formationWhy = new StringBuilder();
                if (!newFormation && !heroConfigured && heroOffset + 3 <= allHeroes.size()) {
                    // 301=英雄不在城（正在采集/狩猎）：每队轮换下一组 3 个空闲候选。
                    formation = Exec.formationWithHeroes(tc.engine,
                            allHeroes.subList(heroOffset, heroOffset + 3));
                } else {
                    formation = formationFor(tc, "hunt", 1 + (round % 3), formationWhy);
                }
                if (formation == null) {
                    m.skip(formationWhy.length() > 0 ? "编队跳过：" + formationWhy : "无英雄可派");
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
            } else if (code == 73) {
                // 2026-11-01 生产实证（新角色）：研究码 73 = 科技暂不可研究（前置/等级/条件不足）→ SKIP
                m.skip("科技暂不可研究（码73，前置或等级不足）");
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
