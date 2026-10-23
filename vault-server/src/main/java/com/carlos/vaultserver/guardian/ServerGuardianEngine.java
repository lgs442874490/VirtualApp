package com.carlos.vaultserver.guardian;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.ServerWarState;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.task.Exec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 守护盟友 P0（服务端化，批次9 2026-10-23）：盟友主城被敌方进攻时，由服务器会话自动发起
 * type=5 驻防（设计依据 {@code design/守护盟友-服务端化设计与分期-20261023.md}）。
 *
 * <p><b>P0 范围（本批）</b>：
 * <ul>
 *   <li>推送解析：{@code notify_world_alliance_situation_add/_update/_init/_del}（主检测源，
 *       生产实测 22/102/11/… 条/日到达服务器会话）+ {@code notify_alliance_member_pos_info/_update}
 *       （盟友坐标）+ {@code notify_world_player_init}（city_point 参考）；</li>
 *   <li>威胁表 + 单盟友规则（{@code ally_uid}/{@code target_uid}/{@code target_point} 过滤）；</li>
 *   <li>命中后：{@code dry_run=true} 只记录；否则 type=5 驻防发射（{@link ServerGuardianLauncher}），
 *       发射响应 endtime 为权威；敌召回（A-11: remain>2s）→ off_work/recall 撤防；</li>
 *   <li>P0 **立即发射**（不做 waitUntil/EWMA/加速，全部留 P1）。</li>
 * </ul>
 *
 * <p><b>硬安全约束（宁可不发）</b>：
 * <ol>
 *   <li><b>默认关闭</b>：{@link Config#enabled}={@code false}、{@link Config#dryRun}={@code true}
 *       （roleParams 未配置时什么都不做，零状态写入、零请求）；</li>
 *   <li><b>零钻石</b>：本类与 Launcher 均不含任何 quick_buy/钻石协议；{@code speedup_enabled}
 *       字段仅为 P1 预留，P0 不解析不执行；</li>
 *   <li><b>不重试风暴</b>：每个威胁最多 1 次动作（A-2 三变体最多 3 个请求）；失败只记原始码；</li>
 *   <li><b>并发 ≤1</b>：同角色同时只允许 1 个在途驻防任务；同目标不重复；</li>
 *   <li><b>会话在线才动作</b>：推送只入账；请求只在 {@code STATE_ONLINE} 时发出；</li>
 *   <li><b>不改状态机</b>：本类由 SessionKeeper 在 online 分支挂载/tick，shutdown 时停止；
 *       战时闸只做展示标注（守护是战斗任务，不因日常战时让位而停）。</li>
 * </ol>
 *
 * <p><b>线程模型</b>：消息钩子线程只做解析/威胁表更新（微秒级、不阻塞入站）；
 * 发射/撤防动作在 {@code guardian-{fpid}-{sid}} 单线程 daemon 执行器串行执行，
 * 经 {@code engine.requestLock} 与会话线程/心跳天然串行。
 *
 * <p><b>配置通道</b>：App FnParams → {@code /v1/vault/sync} params → {@code Account.roleParams}
 * （{@code VaultHttpApi.java:166,225}、{@code VaultStore.java:109}），键 {@code guardian}，
 * 形状 {@code {"sub":{...},"strategy":{...}}}（与 ServerWarState.configOf 同款；也容错扁平键）：
 * <pre>
 * enabled(默认false) dry_run(默认true) ally_uid target_uid target_point{x,y}
 * formation{hero_ids:[],squad_index:1} recall(默认true) recall_distance(预留) speedup_enabled(默认false)
 * d_sec(默认38)  // P0 仅用于日志/D 展示，不参与排程（立即发射）
 * </pre>
 *
 * <p><b>观测键型（稳定契约，供 App/日报/看板消费）</b>：
 * <ul>
 *   <li>{@code mod:{fpid}:{sid}:guardian}：JSON，子项 {@code last}/{@code last_ts}/{@code last_trg="守护"}；</li>
 *   <li>{@code srv:guardian:{fpid}:{sid}:ledger}：JSON 数组（最新在前、上限 50），元素
 *       {ts, ev, marchId, owner, type, endSec, targetUid, x, y, ownMarchId, ownEndSec, code, variant, note}；</li>
 *   <li>{@code describe()}：诊断一行（配置/威胁/任务/盟友/最近推送）。</li>
 * </ul>
 */
public final class ServerGuardianEngine {

    /** 任务 id（与 App FnRegistry/FnDetail 一致）。 */
    public static final String FN_ID = "guardian";

    // ---------------- 协议常量（实证口径；服务器侧仅消费这些） ----------------

    static final String PROTO_ALLIANCE_ADD = "notify_world_alliance_situation_add";
    static final String PROTO_ALLIANCE_UPDATE = "notify_world_alliance_situation_update";
    static final String PROTO_ALLIANCE_INIT = "notify_world_alliance_situation_init";
    static final String PROTO_ALLIANCE_DEL = "notify_world_alliance_situation_del";
    static final String PROTO_ALLIANCE_POS_INFO = "notify_alliance_member_pos_info";
    static final String PROTO_ALLIANCE_POS_UPDATE = "notify_alliance_member_pos_update";
    static final String PROTO_WORLD_PLAYER_INIT = "notify_world_player_init";
    static final String PROTO_MARCH_DETAIL = "req_world_march_detail_4_player_situation";

    static final Set<String> ALLIANCE_MARCH_PROTOS = Set.of(
            PROTO_ALLIANCE_ADD, PROTO_ALLIANCE_UPDATE, PROTO_ALLIANCE_INIT);

    // ---------------- 行为常量 ----------------

    /** 进攻类行军（type=2 target_type=1 攻玩家城；type=1 为侦查，P0 不驻防）。 */
    static final long MARCH_TYPE_ATTACK = 2L;
    /** 默认行军时长 D（秒；设计 §3.5：P0 仅用于日志，立即发射；P1 再标定）。 */
    static final long DEFAULT_D_SEC = 38L;
    /** ETA 过近（<3s）不再追（物理上无解；P1 卡秒/加速才处理）。 */
    static final long MIN_ETA_SEC = 3L;
    /** 敌召回/到达判定阈值（A-11 实证：endtime−now >2s 判召回）。 */
    static final long RECALL_THRESHOLD_SEC = 2L;
    /** 配置热载间隔（同 ServerWarState 60s 热载纪律，这里 10s 更快响应开关）。 */
    static final long CONFIG_TTL_MILLIS = 10_000L;
    /** 威胁表保留：endtime 之后保留 90s（丢 del 推送时清理；同 App GuardianEngine.hasThreatOn 口径）。 */
    static final long THREAT_KEEP_AFTER_END_SEC = 90L;
    /** 任务表保留：endtime 之后保留 10 分钟（等战报/敌情结束；P0 不主动撤已驻扎）。 */
    static final long MISSION_KEEP_AFTER_END_SEC = 600L;
    /** 同角色最多同时 1 个在途驻防任务（P0；App 上限 2，服务器先保守 1）。 */
    static final int MAX_CONCURRENT_MISSIONS = 1;
    /** 账本上限。 */
    static final int LEDGER_CAP = 50;
    /** 解析深度/候选上限（防异常 payload）。 */
    private static final int MAX_DEPTH = 5;
    private static final int MAX_CANDIDATES = 48;

    // ---------------- 配置 ----------------

    /** 生效配置（解析后；默认=关闭+dry_run）。 */
    static final class Config {
        boolean enabled = false;
        boolean dryRun = true;
        boolean recall = true;
        boolean speedupEnabled = false;      // P1 预留，P0 忽略
        long allyUid = 0L;
        long targetUid = 0L;
        int targetX = 0;
        int targetY = 0;
        int squadIndex = 1;
        List<Long> heroIds = new ArrayList<>();
        double recallDistance = 0d;          // 预留
        long dSec = DEFAULT_D_SEC;
        // ---- 批次10 统一编队（task-32；缺省=旧行为零变化） ----
        /** 原始 roleParams（JSONObject→Map 深转换；FormationResolver 契约入参）。 */
        Map<String, Object> rawParams = new LinkedHashMap<>();
        /** formation_library JSON（先取 guardian 自身，其次 formation fn 参数）。 */
        String formationLibraryJson = "";

        static Config disabled() {
            return new Config();
        }

        boolean hasTargetPoint() {
            return targetX > 0 && targetY > 0;
        }

        String describe() {
            return "enabled=" + enabled + " dry_run=" + dryRun + " ally_uid=" + allyUid
                    + " target_uid=" + targetUid
                    + (hasTargetPoint() ? " target_point=" + targetX + "," + targetY : "")
                    + " squad=" + squadIndex
                    + (heroIds.isEmpty() ? "" : " heroes=" + heroIds)
                    + " recall=" + recall + " d=" + dSec + "s";
        }
    }

    // ---------------- 状态对象 ----------------

    /** 一个敌方威胁（已入账的行军；P0 只关心 id/owner/type/end/endpoint/targetUid）。 */
    static final class Threat {
        final long marchId;
        final long ownerUid;
        final long type;
        final long targetUid;
        final int x;
        final int y;
        final boolean hasXY;
        final long firstSeenMs;
        volatile long endEpochSec;

        Threat(long marchId, long ownerUid, long type, long targetUid,
               int x, int y, boolean hasXY, long endEpochSec, long firstSeenMs) {
            this.marchId = marchId;
            this.ownerUid = ownerUid;
            this.type = type;
            this.targetUid = targetUid;
            this.x = x;
            this.y = y;
            this.hasXY = hasXY;
            this.endEpochSec = endEpochSec;
            this.firstSeenMs = firstSeenMs;
        }

        /** 合并同一行军的新旧推送（endtime 前移=敌加速；坐标/target 以新值为准）。 */
        Threat merge(Threat newer) {
            if (newer == null) return this;
            long end = newer.endEpochSec > 0 ? newer.endEpochSec : endEpochSec;
            long target = newer.targetUid > 0 ? newer.targetUid : targetUid;
            long owner = newer.ownerUid > 0 ? newer.ownerUid : ownerUid;
            long typeNew = newer.type > 0 ? newer.type : type;
            boolean xy = newer.hasXY || hasXY;
            int nx = newer.hasXY ? newer.x : x;
            int ny = newer.hasXY ? newer.y : y;
            Threat t = new Threat(marchId, owner, typeNew, target,
                    nx, ny, xy, end, Math.min(firstSeenMs, newer.firstSeenMs));
            return t;
        }

        String kind() {
            return type == MARCH_TYPE_ATTACK ? "进攻" : (type == 1L ? "侦查" : "type" + type);
        }
    }

    /** 一次已发射的驻防任务（targetKey → Mission）。 */
    static final class Mission {
        final long enemyMarchId;
        final long marchId;
        final long endtimeSec;
        final long launchedAtMs;

        Mission(long enemyMarchId, long marchId, long endtimeSec, long launchedAtMs) {
            this.enemyMarchId = enemyMarchId;
            this.marchId = marchId;
            this.endtimeSec = endtimeSec;
            this.launchedAtMs = launchedAtMs;
        }
    }

    /** 规则判定结果（纯函数返回）。 */
    static final class Decision {
        final boolean hit;
        final String reason;

        private Decision(boolean hit, String reason) {
            this.hit = hit;
            this.reason = reason;
        }

        static Decision hit() {
            return new Decision(true, "hit");
        }

        static Decision no(String reason) {
            return new Decision(false, reason);
        }
    }

    // ---------------- 实例状态 ----------------

    private final String fpid;
    private final long sid;
    private final StateStore store;
    private final VaultStore vault;
    private final ServerGuardianLauncher launcher = new ServerGuardianLauncher();

    /** 推送线程写、执行器读：threats/成员坐标表/UI 诊断。 */
    private final Map<Long, Threat> threats = new ConcurrentHashMap<>();
    private final Map<Long, long[]> allies = new ConcurrentHashMap<>();
    private final Map<String, Mission> missions = new ConcurrentHashMap<>();
    /** 已尝试动作的威胁 id（每威胁最多 1 次动作；防更新推送重复触发）。 */
    private final Set<Long> attempted = ConcurrentHashMap.newKeySet();
    /** 已入队待执行的威胁 id（防同 tick/连续推送重复提交）。 */
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();
    /** 目标级进行中标记（"t:{uid}" / "p:{x},{y}"）。 */
    private final Set<String> activeTargets = ConcurrentHashMap.newKeySet();

    private final ExecutorService guardExec;
    private final AtomicBoolean attached = new AtomicBoolean(false);
    private volatile Config config = Config.disabled();
    private volatile long lastConfigLoadAt;
    private volatile long lastPushAt;
    private volatile String lastProto = "";
    private volatile boolean stopped;

    public ServerGuardianEngine(String fpid, long sid, StateStore store, VaultStore vault) {
        this.fpid = fpid == null ? "" : fpid;
        this.sid = sid;
        this.store = store;
        this.vault = vault;
        this.guardExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "guardian-" + this.fpid + "-" + sid);
            t.setDaemon(true);
            return t;
        });
    }

    // =====================================================================
    // 生命周期（SessionKeeper 三处接线：字段 / online 挂载+tick / shutdown）
    // =====================================================================

    /** 挂载推送钩子（幂等；钩子线程只解析入账，不发请求）。 */
    public void attach(WjdrEngine engine) {
        if (engine == null || !attached.compareAndSet(false, true)) return;
        try {
            engine.addMessageHook((proto, content) -> {
                try {
                    onPush(engine, proto, content);
                } catch (Throwable t) {
                    FileLog.warn("守护盟友钩子异常 {}:{} {}", fpid, String.valueOf(sid), String.valueOf(t));
                }
            });
            reloadConfig(true);
            FileLog.info("守护盟友 P0 钩子已挂载 {}:{}（{}）", fpid, String.valueOf(sid),
                    config.describe());
        } catch (Throwable t) {
            attached.set(false);
            FileLog.warn("守护盟友钩子挂载失败 {}:{} {}", fpid, String.valueOf(sid), String.valueOf(t));
        }
    }

    /**
     * 会话线程 tick（online 时由 SessionKeeper 调用）：配置热载 + 内存过期清理。
     * 不做任何网络请求；发射/撤防在 guardExec 线程。
     */
    public void tick(WjdrEngine engine) {
        if (stopped) return;
        reloadConfig(false);
        long nowS = nowSec(engine);
        try {
            for (Map.Entry<Long, Threat> e : threats.entrySet()) {
                Threat t = e.getValue();
                if (t.endEpochSec > 0 && nowS - t.endEpochSec > THREAT_KEEP_AFTER_END_SEC) {
                    if (threats.remove(e.getKey(), t)) {
                        attempted.remove(t.marchId);
                        submitted.remove(t.marchId);
                    }
                }
            }
            missions.entrySet().removeIf(en -> {
                Mission m = en.getValue();
                return m.endtimeSec > 0 && nowS - m.endtimeSec > MISSION_KEEP_AFTER_END_SEC;
            });
        } catch (Throwable ignored) {
        }
    }

    /** 停止执行器（会话 shutdown 时调用；不发请求）。 */
    public void shutdown() {
        stopped = true;
        try {
            guardExec.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 推送入口
    // =====================================================================

    /** 推送入口（钩子线程；包级可见便于单测直接驱动）。 */
    void onPush(WjdrEngine engine, String proto, Map<String, Object> content) {
        if (stopped || proto == null || content == null) return;
        reloadConfig(false);
        Config cfg = config;
        if (!cfg.enabled) return;                       // 默认关：零副作用（不解析、不写状态）
        lastPushAt = System.currentTimeMillis();
        lastProto = proto;

        // 盟友坐标表（P1 距离标定用；P0 仅存内存，供 target_point 回退）
        if (PROTO_ALLIANCE_POS_INFO.equals(proto) || PROTO_ALLIANCE_POS_UPDATE.equals(proto)) {
            Map<Long, long[]> parsed = parseMemberPos(content);
            if (!parsed.isEmpty()) allies.putAll(parsed);
            return;
        }

        // 敌情解除：召回 → 按配置撤防；到达 → 不撤（进战斗/落地）
        if (PROTO_ALLIANCE_DEL.equals(proto)) {
            for (long id : parseDeletedMarchIds(content)) handleThreatGone(engine, id);
            return;
        }

        if (!ALLIANCE_MARCH_PROTOS.contains(proto)) return;

        long nowMs = System.currentTimeMillis();
        for (Threat t : parseThreats(content)) {
            if (t.marchId <= 0 || t.ownerUid <= 0) continue;
            Threat prev = threats.get(t.marchId);
            Threat cur = prev == null ? t : prev.merge(t);
            threats.put(cur.marchId, cur);
            Decision d = decide(cur, cfg, nowMs);
            if (!d.hit) continue;
            if (!submitted.add(cur.marchId)) continue;
            final long marchId = cur.marchId;
            try {
                guardExec.execute(() -> {
                    submitted.remove(marchId);
                    executeThreat(engine, marchId);
                });
            } catch (Throwable t2) {
                submitted.remove(marchId);
            }
        }
    }

    /** 威胁消失处理（召回/到达；钩子线程 → 只入队撤防）。 */
    private void handleThreatGone(WjdrEngine engine, long marchId) {
        Threat t = threats.remove(marchId);
        if (t == null) return;
        attempted.remove(marchId);
        submitted.remove(marchId);
        long nowS = nowSec(engine);
        boolean recalled = t.endEpochSec - nowS > RECALL_THRESHOLD_SEC;
        if (!recalled) {
            ledger("threat_landed", t, null, "敌方已落地（进入战斗，不撤防）");
            return;
        }
        Mission m = missions.get(targetKey(t));
        if (m == null) {
            ledger("threat_recalled", t, null, "敌召回（无我方驻防任务）");
            return;
        }
        Config cfg = config;
        if (!cfg.recall) {
            ledger("threat_recalled_keep", t, null, "敌召回；配置 recall=false 保留驻防");
            return;
        }
        if (cfg.dryRun) {
            ledger("threat_recalled_dry", t, null, "[DRY] 敌召回 → 将撤防（dry_run 不发请求）");
            return;
        }
        final Mission mission = m;
        try {
            guardExec.execute(() -> executeRecall(engine, mission, "敌召回"));
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 执行（guardExec 单线程：发射 / 撤防；每威胁最多 1 次动作）
    // =====================================================================

    /** 执行一条威胁：重新校验规则 → 门控 → dry_run 记录 / 立即发射。 */
    void executeThreat(WjdrEngine engine, long marchId) {
        if (stopped) return;
        Threat t = threats.get(marchId);
        if (t == null) return;
        Config cfg = config;
        long nowMs = System.currentTimeMillis();
        Decision d = decide(t, cfg, nowMs);
        if (!d.hit) return;
        if (!attempted.add(marchId)) return;             // P0：每威胁最多 1 次动作
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) {
            ledger("skip_offline", t, null, "会话不在线（跳过，不补发旧情）");
            attempted.remove(marchId);
            return;
        }
        String targetKey = targetKey(t);
        if (!activeTargets.add(targetKey) || missions.size() >= MAX_CONCURRENT_MISSIONS) {
            activeTargets.remove(targetKey);
            ledger("skip_busy", t, null,
                    "已有驻防任务在跑（missions=" + missions.size() + "）");
            return;
        }
        try {
            if (cfg.dryRun) {
                long eta = t.endEpochSec - nowSec(engine);
                String text = "命中威胁 " + t.kind() + " march=" + t.marchId + " owner=" + t.ownerUid
                        + " → 目标" + (t.targetUid > 0 ? " uid" + t.targetUid : "")
                        + (t.hasXY ? "(" + t.x + "," + t.y + ")" : "")
                        + " ETA " + Math.max(0, eta) + "s，D=" + cfg.dSec + "s，warYield="
                        + warYieldNote() + "（dry_run 只记录不发射）";
                FileLog.info("守护盟友[DRY] {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("dry_run", t, null, text);
                writeMod("🛡[DRY] " + text);
                return;
            }
            int free = freeMarchSlots(engine);
            if (free <= 0) {
                ledger("skip_queue", t, null, free < 0 ? "队列容量/占用未知（宁可不发）" : "队列无空位 free=" + free);
                return;
            }
            int[] pt = resolveTargetPoint(t, cfg);
            if (pt == null || pt[0] <= 0 || pt[1] <= 0) {
                ledger("skip_no_point", t, null, "目标坐标未知（无 endpoint/盟友坐标/target_point）");
                return;
            }
            Map<String, Object> formation = formationOf(engine, cfg);
            if (formation == null || formation.isEmpty()) {
                ledger("skip_no_formation", t, null, "编队不可用（hero/兵种数据缺失）");
                return;
            }
            ServerGuardianLauncher.LaunchResult r = launcher.launch(engine, pt[0], pt[1], formation, t.targetUid);
            if (r.ok) {
                missions.put(targetKey, new Mission(t.marchId, r.marchId, r.endtimeSec, nowMs));
                long nowS = nowSec(engine);
                long ahead = t.endEpochSec > 0 && r.endtimeSec > 0 ? t.endEpochSec - r.endtimeSec : 0L;
                String text = "已发射 type=5 驻防 marchId=" + r.marchId + " endtime=" + r.endtimeSec
                        + "（敌 endtime=" + t.endEpochSec + (ahead != 0 ? "，我方" + (ahead >= 0 ? "先于敌 " : "晚于敌 ") + Math.abs(ahead) + "s" : "")
                        + "）目标" + targetText(t, pt);
                FileLog.info("守护盟友发射 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("launched", t, r, text);
                writeMod("🛡" + text);
            } else {
                String text = "发射失败 code=" + r.code + "（" + r.text + "），不重试";
                FileLog.warn("守护盟友发射失败 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("launch_failed", t, r, text);
                writeMod("⚠ " + text);
            }
        } finally {
            activeTargets.remove(targetKey);
        }
    }

    /** 执行一次撤防（在途→recall；已驻扎→off_work；dry_run 不发）。 */
    void executeRecall(WjdrEngine engine, Mission mission, String reason) {
        if (stopped || mission == null) return;
        Config cfg = config;
        if (!cfg.enabled || cfg.dryRun) return;
        if (!WjdrEngine.STATE_ONLINE.equals(engine.sessionState())) return;
        try {
            long nowS = nowSec(engine);
            boolean inFlight = ServerGuardianLauncher.inFlightAt(mission.endtimeSec, nowS);
            String proto = ServerGuardianLauncher.recallProtoFor(inFlight);
            GameMessage rsp = engine.request(proto, ServerGuardianLauncher.buildMarchIdArgs(mission.marchId));
            int code = Exec.codeOf(rsp);
            String text = "撤防 " + proto.replace("req_world_march_", "") + " marchId=" + mission.marchId
                    + " code=" + code + "（" + reason + "）";
            if (code == 0) {
                missions.entrySet().removeIf(en -> en.getValue().marchId == mission.marchId);
                FileLog.info("守护盟友撤防 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("recalled", null, null, text);
                writeMod("🧹" + text);
            } else {
                FileLog.warn("守护盟友撤防失败 {}:{} {}", fpid, String.valueOf(sid), text);
                ledger("recall_failed", null, null, text);
            }
        } catch (Throwable t) {
            FileLog.warn("守护盟友撤防异常 {}:{} {}", fpid, String.valueOf(sid), String.valueOf(t));
            ledger("recall_error", null, null, "撤防异常：" + brief(t.getMessage()));
        }
    }

    // =====================================================================
    // 配置读取（roleParams `guardian`，默认关）
    // =====================================================================

    /** 配置热载（10s 节流；force=启动时立即读）。任何异常 → 关闭（fail-closed）。 */
    void reloadConfig(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastConfigLoadAt < CONFIG_TTL_MILLIS) return;
        lastConfigLoadAt = now;
        try {
            VaultStore.Account acc = vault == null ? null : vault.find(fpid);
            Map<String, String> raw = acc == null ? null : acc.roleParams.get(String.valueOf(sid));
            String json = raw == null ? null : raw.get(FN_ID);
            Config parsed = json == null || json.isEmpty() ? Config.disabled() : parseConfig(new JSONObject(json));
            // 批次10：编队库可能由 App 同步在独立 formation fn 参数里（与 FormationResolver.libraryFor 同口径兜底）
            if (parsed.formationLibraryJson.isEmpty() && raw != null) {
                try {
                    String fj = raw.get("formation");
                    if (fj != null && !fj.isEmpty()) {
                        parsed.formationLibraryJson = new JSONObject(fj).optString("formation_library", "");
                    }
                } catch (Throwable ignored) {
                }
            }
            config = parsed;
        } catch (Throwable t) {
            config = Config.disabled();
        }
    }

    /** 解析 roleParams `guardian` JSON（纯函数；缺省/异常一律关闭+dry_run）。 */
    static Config parseConfig(JSONObject p) {
        Config c = Config.disabled();
        if (p == null) return c;
        try {
            JSONObject sub = p.optJSONObject("sub");
            JSONObject st = p.optJSONObject("strategy");
            c.enabled = bool(sub, st, p, "enabled", false)
                    || bool(sub, st, p, "intercept", false);   // 兼容 App FnDetail 既有 intercept 开关
            c.dryRun = bool(sub, st, p, "dry_run", true);
            c.recall = bool(sub, st, p, "recall", true);
            c.speedupEnabled = bool(sub, st, p, "speedup_enabled", false);   // P1 预留
            c.allyUid = Math.max(0L, num(sub, st, p, "ally_uid", 0L));
            c.targetUid = Math.max(0L, num(sub, st, p, "target_uid", 0L));
            Object tp = first(sub, st, p, "target_point");
            if (tp instanceof JSONObject) {
                JSONObject o = (JSONObject) tp;
                c.targetX = o.optInt("x", 0);
                c.targetY = o.optInt("y", 0);
            } else if (tp instanceof JSONArray && ((JSONArray) tp).length() >= 2) {
                c.targetX = ((JSONArray) tp).optInt(0, 0);
                c.targetY = ((JSONArray) tp).optInt(1, 0);
            } else if (tp instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) tp;
                c.targetX = (int) Exec.longOf(m.get("x"));
                c.targetY = (int) Exec.longOf(m.get("y"));
            } else if (tp instanceof List && ((List<?>) tp).size() >= 2) {
                c.targetX = (int) Exec.longOf(((List<?>) tp).get(0));
                c.targetY = (int) Exec.longOf(((List<?>) tp).get(1));
            }
            Object fm = first(sub, st, p, "formation");
            if (fm instanceof JSONObject) {
                JSONObject o = (JSONObject) fm;
                int squad = o.optInt("squad_index", 0);
                if (squad <= 0) squad = o.optInt("squad", 0);
                if (squad > 0) c.squadIndex = squad;
                JSONArray heroes = o.optJSONArray("hero_ids");
                if (heroes != null) {
                    for (int i = 0; i < heroes.length(); i++) {
                        long id = heroes.optLong(i, 0L);
                        if (id > 0) c.heroIds.add(id);
                    }
                }
            } else if (fm instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) fm;
                int squad = (int) Exec.longOf(m.get("squad_index"));
                if (squad <= 0) squad = (int) Exec.longOf(m.get("squad"));
                if (squad > 0) c.squadIndex = squad;
                Object heroes = m.get("hero_ids");
                if (heroes instanceof List) {
                    for (Object h : (List<?>) heroes) {
                        long id = Exec.longOf(h);
                        if (id > 0) c.heroIds.add(id);
                    }
                }
            } else {
                int squad = (int) num(sub, st, p, "squad_index", 0L);
                if (squad > 0) c.squadIndex = squad;
            }
            long d = num(sub, st, p, "d_sec", DEFAULT_D_SEC);
            if (d >= 10L && d <= 600L) c.dSec = d;   // 界外值忽略（防配置错误把 D 当 0）
            Object rd = first(sub, st, p, "recall_distance");
            if (rd != null) c.recallDistance = Math.max(0d, Exec.longOf(rd));
        } catch (Throwable ignored) {
            return Config.disabled();
        }
        // 批次10：统一编队参数（无这些键 → isConfigured=false → 旧行为）
        c.rawParams = ServerGuardianLauncher.toParamMap(p);
        c.formationLibraryJson = p.optString("formation_library", "");
        return c;
    }

    private static Object first(JSONObject sub, JSONObject st, JSONObject top, String key) {
        if (sub != null && sub.has(key)) return sub.opt(key);
        if (st != null && st.has(key)) return st.opt(key);
        return top == null ? null : top.opt(key);
    }

    private static boolean bool(JSONObject sub, JSONObject st, JSONObject top, String key, boolean def) {
        Object v = first(sub, st, top, key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s) || "off".equalsIgnoreCase(s)) return false;
        }
        return def;
    }

    private static long num(JSONObject sub, JSONObject st, JSONObject top, String key, long def) {
        Object v = first(sub, st, top, key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // =====================================================================
    // 推送解析（纯函数；容错递归，不猜协议）
    // =====================================================================

    /** 解析一条 situation 推送中的敌军行军 → Threat 列表（add/update/init；重复 id 合并）。 */
    static List<Threat> parseThreats(Map<String, Object> content) {
        List<Threat> out = new ArrayList<>();
        if (content == null) return out;
        long nowMs = System.currentTimeMillis();
        Map<Long, Threat> merged = new LinkedHashMap<>();
        for (Map<String, Object> m : marchesOf(content)) {
            long id = Exec.longOf(m.get("id"));
            long owner = Exec.longOf(m.get("owner"));
            if (id <= 0 || owner <= 0) continue;
            long type = Exec.longOf(m.get("type"));
            long end = firstLong(m, "endtime", "end_time", "endTime");
            long targetUid = targetUidOf(m);
            int[] xy = endpointOf(m);
            Threat t = new Threat(id, owner, type, targetUid,
                    xy == null ? 0 : xy[0], xy == null ? 0 : xy[1], xy != null, end, nowMs);
            Threat prev = merged.get(id);
            merged.put(id, prev == null ? t : prev.merge(t));
            if (merged.size() >= MAX_CANDIDATES) break;
        }
        out.addAll(merged.values());
        return out;
    }

    /**
     * 解析 del 推送中的行军 id（形态 `{to_marchs:[{id}]}` / `{marchs:[...]}` / `{ids:[..]}` / `{id}`）。
     * del 推送只带 `{id,type,kid}`（不含 owner），故不能复用 marchesOf 的 owner 过滤，这里按结构直取 id。
     */
    static List<Long> parseDeletedMarchIds(Map<String, Object> content) {
        Set<Long> ids = new LinkedHashSet<>();
        collectDeletedIds(content, ids, 0);
        return new ArrayList<>(ids);
    }

    private static void collectDeletedIds(Object node, Set<Long> ids, int depth) {
        if (node == null || depth > MAX_DEPTH || ids.size() >= MAX_CANDIDATES) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectDeletedIds(o, ids, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        long direct = Exec.longOf(m.get("id"));
        if (direct > 0) ids.add(direct);
        for (String k : new String[]{"ids", "march_ids", "marchIds"}) {
            Object v = m.get(k);
            if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    long id = Exec.longOf(o);
                    if (id > 0) ids.add(id);
                }
            }
        }
        for (String k : new String[]{"to_marchs", "marchs", "data", "info", "content"}) {
            Object v = m.get(k);
            if (v instanceof Map || v instanceof List) collectDeletedIds(v, ids, depth + 1);
        }
    }

    /** 解析盟友坐标推送（`member_list` map/list 或 `member_pos` 单条）→ uid → (x,y)。 */
    static Map<Long, long[]> parseMemberPos(Map<String, Object> content) {
        Map<Long, long[]> out = new LinkedHashMap<>();
        if (content == null) return out;
        Object single = content.get("member_pos");
        if (single instanceof Map) collectMemberPos((Map<?, ?>) single, out);
        Object list = content.get("member_list");
        if (list instanceof Map) {
            for (Object v : ((Map<?, ?>) list).values()) collectMemberPos(v, out);
        } else if (list instanceof List) {
            for (Object v : (List<?>) list) collectMemberPos(v, out);
        }
        if (out.isEmpty() && content.containsKey("uid")) collectMemberPos(content, out);
        return out;
    }

    private static void collectMemberPos(Object node, Map<Long, long[]> out) {
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        long uid = Exec.longOf(m.get("uid"));
        long x = Exec.longOf(m.get("x"));
        long y = Exec.longOf(m.get("y"));
        if (uid > 0 && x > 0 && y > 0) out.put(uid, new long[]{x, y});
    }

    /**
     * 递归收集推送中的行军对象（三种实证形状）：
     * <pre>
     *   目标系列: {"to_marchs":[{...}]} / {"marchs":[{...}]}
     *   观察哨:   {"marches":{"9.0":{"march":{...},"power":0}}}
     *   包装层:   {"data":{...}} / {"info":{...}}
     * </pre>
     * 防御式：任意嵌套含 id+owner 的 map 也可能是 march 候选，不锁定单一形状。
     */
    static List<Map<String, Object>> marchesOf(Map<String, Object> content) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectMarches(content, out, 0);
        return out;
    }

    private static void collectMarches(Object node, List<Map<String, Object>> out, int depth) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_CANDIDATES) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectMarches(o, out, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        Object marches = m.get("marches");
        if (marches instanceof Map) {
            for (Object v : ((Map<?, ?>) marches).values()) {
                Map<String, Object> e = Exec.mapOf(v);
                if (e == null) continue;
                Map<String, Object> inner = Exec.mapOf(e.get("march"));
                Map<String, Object> cand = inner != null ? inner : e;
                if (isMarchLike(cand)) out.add(cand);
            }
        } else if (marches instanceof List) {
            collectMarches(marches, out, depth + 1);
        }
        for (String key : new String[]{"to_marchs", "marchs"}) {
            Object arr = m.get(key);
            if (arr instanceof List) collectMarches(arr, out, depth + 1);
        }
        if (isMarchLike(m)) out.add(Exec.mapOf(m));
        for (String wrap : new String[]{"data", "info", "content", "detail"}) {
            Object w = m.get(wrap);
            if (w instanceof Map || w instanceof List) collectMarches(w, out, depth + 1);
        }
    }

    private static boolean isMarchLike(Map<?, ?> m) {
        return m != null && Exec.longOf(m.get("id")) > 0 && Exec.longOf(m.get("owner")) > 0
                && (m.containsKey("endtime") || m.containsKey("end_time") || m.containsKey("type"));
    }

    private static long firstLong(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                long n = Exec.longOf(v);
                if (n > 0) return n;
            }
        }
        return 0L;
    }

    /** 守方/目标 uid：atk_city.targetid 优先，退化 target.map_city.uid / target_uid（ServerShieldResponder 同口径）。 */
    static long targetUidOf(Map<String, Object> m) {
        if (m == null) return 0L;
        Map<String, Object> atk = Exec.mapOf(m.get("atk_city"));
        if (atk != null) {
            long t = Exec.longOf(atk.get("targetid"));
            if (t > 0) return t;
            t = Exec.longOf(atk.get("uid"));
            if (t > 0) return t;
        }
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            Map<String, Object> city = Exec.mapOf(target.get("map_city"));
            if (city != null) {
                long uid = Exec.longOf(city.get("uid"));
                if (uid > 0) return uid;
            }
            long uid = Exec.longOf(target.get("uid"));
            if (uid > 0) return uid;
        }
        return Exec.longOf(m.get("target_uid"));
    }

    /** 行军目标坐标：endpoint{x,y} → target.map_city{x,y}（防御式；取不到 null）。 */
    static int[] endpointOf(Map<String, Object> m) {
        if (m == null) return null;
        int[] p = pointOf(Exec.mapOf(m.get("endpoint")));
        if (p != null) return p;
        Map<String, Object> target = Exec.mapOf(m.get("target"));
        if (target != null) {
            p = pointOf(Exec.mapOf(target.get("map_city")));
            if (p != null) return p;
            p = pointOf(target);
            if (p != null) return p;
        }
        return pointOf(m);
    }

    private static int[] pointOf(Map<String, Object> m) {
        if (m == null) return null;
        long x = Exec.longOf(m.get("x"));
        long y = Exec.longOf(m.get("y"));
        return x > 0 && y > 0 ? new int[]{(int) x, (int) y} : null;
    }

    // =====================================================================
    // 规则判定 / 目标解析（纯函数）
    // =====================================================================

    /** P0 规则：type=2 进攻 + ETA 够 + 目标匹配（单盟友白名单）。 */
    static Decision decide(Threat t, Config cfg, long nowMs) {
        if (t == null) return Decision.no("no_threat");
        if (cfg == null || !cfg.enabled) return Decision.no("disabled");
        if (t.type != MARCH_TYPE_ATTACK) return Decision.no("not_attack（type=" + t.type + " 不驻防）");
        long nowS = nowMs / 1000L;
        if (t.endEpochSec <= 0) return Decision.no("no_eta");
        if (t.endEpochSec - nowS < MIN_ETA_SEC) return Decision.no("eta_too_close（剩余 " + (t.endEpochSec - nowS) + "s）");
        // 单盟友过滤：ally_uid 优先；target_uid/target_point 为可选精确过滤
        if (cfg.allyUid > 0 && t.targetUid > 0 && t.targetUid != cfg.allyUid) {
            return Decision.no("not_ally（targetUid=" + t.targetUid + " != ally_uid=" + cfg.allyUid + "）");
        }
        if (cfg.targetUid > 0) {
            if (t.targetUid > 0) {
                if (t.targetUid != cfg.targetUid) return Decision.no("target_uid 不符：" + t.targetUid);
            } else if (cfg.hasTargetPoint()) {
                if (!t.hasXY || t.x != cfg.targetX || t.y != cfg.targetY) {
                    return Decision.no("target_uid 未知且 endpoint 不匹配 target_point");
                }
            } else {
                return Decision.no("target_uid 未知（配置了 target_uid 但推送无目标 uid）");
            }
        }
        if (cfg.hasTargetPoint()) {
            if (!t.hasXY) return Decision.no("target_point 已配置但推送无坐标");
            if (t.x != cfg.targetX || t.y != cfg.targetY) {
                return Decision.no("endpoint 不匹配 target_point（" + t.x + "," + t.y + "）");
            }
        }
        return Decision.hit();
    }

    /** 目标坐标：威胁 endpoint → target_point 配置 → 盟友坐标表（targetUid）。 */
    int[] resolveTargetPoint(Threat t, Config cfg) {
        if (t == null) return null;
        if (t.hasXY && t.x > 0 && t.y > 0) return new int[]{t.x, t.y};
        if (cfg != null && cfg.hasTargetPoint()) return new int[]{cfg.targetX, cfg.targetY};
        if (t.targetUid > 0) {
            long[] xy = allies.get(t.targetUid);
            if (xy != null) return new int[]{(int) xy[0], (int) xy[1]};
        }
        return null;
    }

    private static String targetKey(Threat t) {
        if (t == null) return "unknown";
        return t.targetUid > 0 ? "t:" + t.targetUid
                : (t.hasXY ? "p:" + t.x + "," + t.y : "m:" + t.marchId);
    }

    private static String targetText(Threat t, int[] pt) {
        StringBuilder sb = new StringBuilder();
        if (t != null && t.targetUid > 0) sb.append("uid").append(t.targetUid);
        if (pt != null) sb.append(sb.length() > 0 ? " " : "").append('(').append(pt[0]).append(',').append(pt[1]).append(')');
        return sb.toString();
    }

    /**
     * 编队（批次10 统一编队接入）：
     * <ol>
     *   <li>显式编队参数 → {@link ServerGuardianLauncher#resolveFormation}（ROLE/AUTOMATIC；不可用→null=上层
     *       SKIP，不静默回退）；</li>
     *   <li>无编队参数 → 旧行为：hero_ids 优先，否则 Exec.formation(engine, squad_index)（缺省队 1）。</li>
     * </ol>
     * 三变体自发现在 {@link ServerGuardianLauncher#launch}（与本方法解耦，保留不变）。
     */
    Map<String, Object> formationOf(WjdrEngine engine, Config cfg) {
        try {
            ServerGuardianLauncher.FormationResolution resolved = ServerGuardianLauncher.resolveFormation(
                    engine, cfg == null ? null : cfg.rawParams,
                    cfg == null ? "" : cfg.formationLibraryJson);
            if (resolved.configured) {
                if (!resolved.ready()) {
                    FileLog.warn("守护盟友统一编队不可用 {}:{}：{}", fpid, String.valueOf(sid), resolved.reason);
                    return null;
                }
                FileLog.info("守护盟友统一编队 {}:{}：{}", fpid, String.valueOf(sid), resolved.label);
                return resolved.payload;
            }
            if (cfg != null && cfg.heroIds != null && !cfg.heroIds.isEmpty()) {
                return Exec.formationWithHeroes(engine, cfg.heroIds);
            }
            int squad = cfg == null || cfg.squadIndex <= 0 ? 1 : cfg.squadIndex;
            return Exec.formation(engine, squad);
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // 队列校验（发射前；未知一律宁可不发）
    // =====================================================================

    /** 剩余可出征队列数；容量/占用取不到 → -1（上层跳过发射）。 */
    int freeMarchSlots(WjdrEngine engine) {
        try {
            int capacity = capacityOf(engine.notifyData(PROTO_WORLD_PLAYER_INIT));
            if (capacity <= 0) return -1;
            GameMessage rsp = engine.request(PROTO_MARCH_DETAIL,
                    Exec.args("owner", engine.lastUid(), "kid", engine.lastServerId()), 5);
            if (Exec.codeOf(rsp) != 0 || rsp.getContent() == null) return -1;
            return freeSlots(capacity, activeMarchesOf(rsp.getContent()));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 世界出征容量：`notify_world_player_init.world.capacity`（同 ServerFieldTasks 口径），取不到 0。 */
    static int capacityOf(Map<String, Object> worldPlayerInit) {
        if (worldPlayerInit == null) return 0;
        Integer n = findCapacity(worldPlayerInit.get("world"), 0);
        if (n == null) n = findCapacity(worldPlayerInit, 0);
        int cap = n == null ? 0 : n;
        return cap > 0 && cap <= 12 ? cap : 0;
    }

    private static Integer findCapacity(Object node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            Object c = m.get("capacity");
            if (c instanceof Number) {
                int n = ((Number) c).intValue();
                if (n > 0) return n;
            }
            for (Object v : m.values()) {
                Integer n = findCapacity(v, depth + 1);
                if (n != null) return n;
            }
        }
        return null;
    }

    /**
     * 自身在途/驻扎行军数（用于"空队列"校验）：递归找含 id+state 的 march 对象，
     * state ∈ {2 在途, 3 驻扎, 4 回程} 计占用（同 App QueueLedger 口径；去重防嵌套重复）。
     */
    static int activeMarchesOf(Map<String, Object> detailRsp) {
        if (detailRsp == null) return 0;
        Map<Long, Long> states = new LinkedHashMap<>();
        collectMarchStates(detailRsp, states, 0);
        int n = 0;
        for (Long st : states.values()) {
            if (st == 2L || st == 3L || st == 4L) n++;
        }
        return n;
    }

    private static void collectMarchStates(Object node, Map<Long, Long> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 200) return;
        if (node instanceof List) {
            for (Object o : (List<?>) node) collectMarchStates(o, out, depth + 1);
            return;
        }
        if (!(node instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) node;
        long id = Exec.longOf(m.get("id"));
        long state = Exec.longOf(m.get("state"));
        boolean marchLike = id > 0 && state > 0
                && (m.containsKey("type") || m.containsKey("endtime") || m.containsKey("end_time"));
        if (marchLike) out.putIfAbsent(id, state);
        for (Object v : m.values()) {
            if (v instanceof Map || v instanceof List) collectMarchStates(v, out, depth + 1);
        }
    }

    /** 剩余队列数（纯函数；负值防御）。 */
    static int freeSlots(int capacity, int active) {
        return Math.max(0, capacity - active);
    }

    // =====================================================================
    // 状态输出（mod/srv 键型固定，供 App/日报消费）
    // =====================================================================

    /** 本次动作的账本记录（最新在前，上限 {@link #LEDGER_CAP}）。 */
    void ledger(String event, Threat t, ServerGuardianLauncher.LaunchResult r, String note) {
        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis());
            e.put("ev", event == null ? "" : event);
            if (t != null) {
                e.put("marchId", t.marchId);
                e.put("owner", t.ownerUid);
                e.put("type", t.type);
                e.put("endSec", t.endEpochSec);
                e.put("targetUid", t.targetUid);
                if (t.hasXY) {
                    e.put("x", t.x);
                    e.put("y", t.y);
                }
            }
            if (r != null) {
                e.put("ownMarchId", r.marchId);
                e.put("ownEndSec", r.endtimeSec);
                e.put("code", r.code);
                e.put("variant", r.variant);
            }
            e.put("note", clip(note, 400));
            String key = ledgerKey(fpid, sid);
            JSONArray arr = new JSONArray();
            String raw = store == null ? null : store.getString(key);
            if (raw != null) {
                try {
                    arr = new JSONArray(raw);
                } catch (Throwable ignored) {
                }
            }
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < arr.length() && i < LEDGER_CAP - 1; i++) out.put(arr.get(i));
            if (store != null) {
                store.putString(key, out.toString());
                store.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 写 `mod:{fpid}:{sid}:guardian`（App FnDetail/今日页同键展示）。 */
    void writeMod(String text) {
        try {
            if (store == null) return;
            String key = modKey(fpid, sid);
            JSONObject o = new JSONObject();
            String raw = store.getString(key);
            if (raw != null) {
                try {
                    o = new JSONObject(raw);
                } catch (Throwable ignored) {
                }
            }
            o.put("last", clip(text, 400));
            o.put("last_ts", System.currentTimeMillis());
            o.put("last_trg", "守护");
            store.putString(key, o.toString());
            store.flush();
        } catch (Throwable ignored) {
        }
    }

    static String ledgerKey(String fpid, long sid) {
        return "srv:guardian:" + fpid + ":" + sid + ":ledger";
    }

    static String modKey(String fpid, long sid) {
        return "mod:" + fpid + ":" + sid + ":" + FN_ID;
    }

    private String warYieldNote() {
        try {
            return ServerWarState.wartimeYield(fpid) ? "战时" : "非战时";
        } catch (Throwable t) {
            return "未知";
        }
    }

    private long nowSec(WjdrEngine engine) {
        try {
            if (engine != null && engine.serverClock() != null && engine.serverClock().locked()) {
                return engine.serverClock().nowServerSec();
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() / 1000L;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String brief(String s) {
        return clip(s, 120);
    }

    // =====================================================================
    // 诊断（日志/单测）
    // =====================================================================

    /** 诊断一行：配置 + 威胁/任务/盟友数 + 最近推送（日志/接口用，不泄露敏感信息）。 */
    public String describe() {
        long ago = lastPushAt > 0 ? (System.currentTimeMillis() - lastPushAt) : -1L;
        return "guardian{fpid=" + fpid + ", sid=" + sid + ", " + config.describe()
                + ", threats=" + threats.size() + ", missions=" + missions.size()
                + ", allies=" + allies.size() + ", lastProto=" + lastProto
                + ", lastPushAgoMs=" + ago + (stopped ? ", stopped" : "") + "}";
    }

    /** 配置快照（单测/诊断）。 */
    Config config() {
        return config;
    }

    /** 威胁表大小（单测/诊断）。 */
    int threatCount() {
        return threats.size();
    }

    /** 任务表大小（单测/诊断）。 */
    int missionCount() {
        return missions.size();
    }

    /** 盟友坐标表大小（单测/诊断）。 */
    int allyCount() {
        return allies.size();
    }

    /** 单测等待执行器空闲（提交的任务先于本次调用全部完成）。 */
    void awaitIdleForTest(long timeoutMs) {
        try {
            guardExec.submit(() -> {
            }).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }
}
