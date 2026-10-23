package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器侧「巨熊行动」任务（B1，2026-10-23，新文件；{@code ServerTasks.bear} 保持不动作 fallback）。
 *
 * <p>1:1 对齐网页版（{@code wjdr-review/src-tool/com/wjdr/tool/modules/daily/}）：
 * <ul>
 *   <li>{@code service/DailyBearActionService.java:143-181}：登录/被唤醒时同步预约（{@code req_alliance_trap_info}）——
 *       <b>预约本身是游戏端行为，没有任何客户端“预约”协议</b>；本类只读取 {@code reserve_time}（权威），
 *       绝不发送预约请求，也不臆造“帮我预约”；</li>
 *   <li>{@code service/DailyBearActionService.java:243-300}：目标发现
 *       {@code req_alliance_building_list}（响应 {@code building_type_2_list[].mapobj_type==33} 过滤熊坑）
 *       → 兜底 {@code req_alliance_building_list_exist{mapobj_type:33}} → {@code req_world_mapobj{kid,point}}
 *       取 {@code mapobj.map_alliance_trap.id}（monsterid）与坐标；</li>
 *   <li>{@code service/DailyBearSchedule.java:15-72}：活动时长 30 分钟、开赛前 10 分钟准备窗；
 *       有 {@code reserve_time} 时以其为活动开始（权威），否则用配置周窗口（默认周五 21:00-21:30 北京时间）；</li>
 *   <li>{@code service/DailyBearMarchExecutor.java:677-698}：发起 {@code req_world_march{type:411}} +
 *       {@code extra{monsterid, prepare_time_index}}（准备档 1/2/3/4 = 320/620/1820/3620 秒，默认 2=620 秒）；
 *       上车 {@code req_world_march{type:99}} + {@code extra{master_marchid}}（坐标=车头城市）；
 *       {@code :430-505} 用 {@code req_world_mass_master_detail{id,owner,kid}} 对账成员；</li>
 *   <li>{@code service/DailyBearActivityGate.java:108-123}：窗口内其它野外任务让位（本分支由 cadence/调用方协调，
 *       本类入口只做自身守卫，不触碰他人文件）。</li>
 * </ul>
 *
 * <p><b>安全约束（宁漏不花）</b>：
 * <ul>
 *   <li>只发审计报告/网页版出现过的协议：{@code req_alliance_trap_info}、{@code req_alliance_building_list}、
 *       {@code req_alliance_building_list_exist}、{@code req_world_mapobj}、{@code req_world_march}(type=411/99)、
 *       {@code req_world_mass_master_detail}、可选清场 {@code req_world_march_off_work}；</li>
 *   <li>任何数据缺失/错误码 → SKIP + 可读原因；绝不花钻石；</li>
 *   <li>幂等：{@code srv:bear:{fpid}:{sid}:launched} 记录“当日日期 + 场次”，窗口内发起成功/已发起后绝不发第二辆车
 *       （标记在请求前落盘，异常/超时同样不重发，避免重复消耗兵力）；上车同样按 {@code :joins} 台账去重；</li>
 *   <li>非窗口一律不发任何请求（节奏调度 15s 一拍时保持零成本）；预约同步按“窗口附近 5 分钟一次 / 平时 6 小时一次”节流。</li>
 * </ul>
 *
 * <p>时间：一律北京时间（{@link Beijing#TZ} / {@link Beijing#ZONE_ID}）；服务器时钟优先（{@code GameServerClock}，
 * 未校准时回退本机时间）。纯函数（窗口/幂等键/参数构造/解析/触发判定）均为 static，便于单测不连真协议。
 */
public final class ServerBearTasks {

    /** 任务 id（与 ServerTasks 目录/App FnRegistry 一致）。 */
    public static final String TASK_ID = "bear";
    /** 任务名（日志/UI）。 */
    public static final String TASK_NAME = "巨熊行动";

    /** 秒域时间戳合法性下限（2020-09 之后；网页版生产值约 1.79e9）。 */
    static final long MIN_EPOCH_SEC = 1_600_000_000L;
    /** 活动时长 30 分钟（DailyBearSchedule.ACTIVITY_DURATION_MINUTES）。 */
    static final int ACTIVITY_MINUTES = 30;
    /** 开赛前准备提前量 10 分钟（DailyBearSchedule.PREPARATION_LEAD_MINUTES）。 */
    static final int PREP_LEAD_MINUTES = 10;
    /** 默认准备档 index=2 → 620 秒（DailyBearMarchExecutor:690-698）。 */
    static final int DEFAULT_PREPARE_TIME_INDEX = 2;
    /** 上车编队数上限 6（DailyBearActionConfig.NORMAL_QUEUE_LIMIT / 发起1+上车1-6）。 */
    static final int MAX_JOIN_COUNT = 6;
    /** 发起巨熊集结的行军 type（DailyBearMarchExecutor:677-680）。 */
    static final long MARCH_TYPE_BEAR_RALLY = 411L;
    /** 加入集结（上车）的行军 type（DailyBearMarchExecutor:681-685）。 */
    static final long MARCH_TYPE_JOIN_RALLY = 99L;
    /** 普通狩猎/采集行军 type（可选清场撤回用）。 */
    static final long MARCH_TYPE_HUNT = 3L;
    static final long MARCH_TYPE_GATHER = 4L;
    /** 联盟熊坑 mapobj 类型（DailyBearActionService:68 ALLIANCE_TRAP_MAP_OBJECT_TYPE=33）。 */
    static final long ALLIANCE_TRAP_MAP_OBJECT_TYPE = 33L;
    /** 网页版错误码（DailyBearMarchExecutor:55-58）。 */
    static final int CODE_TRAP_MASS_TIME_EXPIRED = 5204;
    static final int CODE_HERO_OCCUPIED = 403;
    static final int CODE_ALREADY_JOINED = 2844;
    static final int CODE_WORLD_MARCH_LIMIT = 2878;
    /** 状态键前缀（幂等/缓存）。 */
    static final String KEY_PREFIX = "srv:bear:";
    /** 预约同步节流：窗口附近 5 分钟、平时 6 小时。 */
    static final long SYNC_NEAR_WINDOW_SEC = 300L;
    static final long SYNC_IDLE_SEC = 6 * 3600L;
    /** 行军/集结候选缓存 TTL（内存，仅用于上车候选发现）。 */
    static final long RALLY_CACHE_TTL_SEC = 15 * 60L;

    private ServerBearTasks() {
    }

    // ==================================================================================
    // 一、入口：public static TaskOutcome bear(TaskContext tc)（签名同 ServerTasks.bear）
    // ==================================================================================

    /**
     * 巨熊行动单任务执行入口（与 {@link ServerTasks.TaskDef.Executor} 同签名，Modules/legacy 可直接方法引用）。
     */
    public static TaskOutcome bear(TaskContext tc) {
        Exec.Multi m = new Exec.Multi();
        String trapText = "";
        String windowText = "";
        String launchText = "";
        try {
            if (!WjdrEngine.STATE_ONLINE.equals(tc.engine.sessionState())) {
                return new TaskOutcome(TASK_ID, TASK_NAME, TaskOutcome.Category.SKIPPED, "会话不在线，跳过巨熊行动");
            }
            BearConfig cfg = BearConfig.of(tc.params(TASK_ID));
            long nowSec = nowSec(tc);
            BearState state = loadState(tc);

            // ---------- 1) 预约同步：只读 reserve_time/start/end/cool（推送优先作为“补空”，请求结果为权威） ----------
            int syncFailCode = 0;
            if (needTrapSync(nowSec, state.syncAt, state.reserve, state.start, cfg)) {
                GameMessage rsp = tc.engine.request("req_alliance_trap_info", null);
                TrapTimes fresh = syncTrapTimes(rsp);
                if (fresh != null) {
                    state.mergeTimes(fresh);
                    state.syncAt = nowSec;
                } else {
                    syncFailCode = rsp == null ? -1 : Exec.codeOf(rsp);
                }
            }
            TrapTimes pushed = trapTimesFromNotifies(tc.engine);
            if (pushed != null) {
                // 预约/场次时间在生产上只向前推进（reserve 每场向后翻），推送按“更大者胜”合并：
                // 既不让旧推送回退请求结果，也能在 reserve 已翻到下一场时用 mass_start_time 兜底本场。
                state.mergeNewer(pushed);
            }
            boolean hasAnyTrapData = isValidEpoch(state.reserve) || isValidEpoch(state.start);
            if (!hasAnyTrapData && syncFailCode != 0) {
                // 数据缺失 + 同步失败：明确 SKIP，不臆造窗口也不猜预约
                saveState(tc, state);
                writeMod(tc, "未同步 · 预约数据缺失", "未知", "未发起");
                return new TaskOutcome(TASK_ID, TASK_NAME, TaskOutcome.Category.SKIPPED,
                        "同步巨熊预约失败（码" + syncFailCode + "），本地无缓存，本轮跳过");
            }
            String syncNote = syncFailCode == 0 ? "" : "（预约同步失败码" + syncFailCode + "，使用本地缓存）";

            // ---------- 2) 窗口判定（reserve_time 权威；无预约用配置周窗口） ----------
            Window window = windowOf(nowSec, state.reserve, state.start, cfg);
            trapText = trapSummary(state, window, cfg.launchWithoutReserve);
            windowText = phaseText(window);
            saveState(tc, state);

            // 未读取到预约 + 走到配置周窗口：默认 SKIP（预约是游戏端行为，没有客户端预约协议，
            // 不臆造“本场会开”而空发车；确实要按配置窗口执行需显式 launch_without_reserve=true）。
            if (shouldSkipFallbackWindow(window, cfg)) {
                m.skip("未预约巨熊场次（无客户端预约协议，需游戏内手动预约），配置窗口内不发车");
                launchText = "未预约";
                writeMod(tc, trapText, windowText, launchText);
                return Exec.result(m, TASK_ID, TASK_NAME);
            }

            if (window.phase == Window.ENDED) {
                m.skip("本场巨熊已结束" + syncNote);
                launchText = "本场已结束";
            } else if (window.phase == Window.WAITING) {
                m.skip("未在巨熊窗口 · " + trapText + syncNote);
                launchText = "未在窗口";
            } else if (window.phase == Window.PREPARING) {
                launchText = "准备窗口中";
                // 准备窗内预取熊坑目标（只读，零消耗），到点发车不再等目标发现
                if (!targetUsable(state)) {
                    TrapTarget target = resolveTarget(tc, state, new StringBuilder());
                    if (target != null) {
                        state.setTarget(target);
                        saveState(tc, state);
                    }
                }
                String prepMsg = "巨熊准备窗口（" + fmtTime(window.startSec) + " 开赛），等待开赛";
                if (cfg.prepareRecallEnabled) {
                    int recalled = recallOrdinaryMarches(tc);
                    if (recalled > 0) {
                        m.claimed("准备窗清场：撤回普通行军×" + recalled);
                    } else {
                        m.skip("准备窗清场：未取到可撤回的普通行军（不影响发车）");
                    }
                } else {
                    m.skip(prepMsg + "（清场开关 prepare_recall 默认关）");
                }
            } else {
                // ---------- 3) ACTIVE：发车 + 上车 + 对账 ----------
                boolean launchedNow = launchRally(tc, m, cfg, state, nowSec, window);
                Window refreshed = windowOf(nowSec(tc), state.reserve, state.start, cfg);
                joinRallies(tc, m, cfg, state, nowSec(tc), refreshed);
                if (launchedNow) {
                    launchText = "已发起";
                } else if (launchedForWindow(tc.store.getString(launchedKeyOf(tc)),
                        dayKey(nowSec), window.startSec)) {
                    launchText = "本场已发起（不重复发车）";
                } else {
                    launchText = "未发起";
                }
            }
            writeMod(tc, trapText, windowText, launchText);
        } catch (Exception e) {
            try {
                writeMod(tc, trapText, windowText, "异常：" + ServerTasks.brief(String.valueOf(e.getMessage())));
            } catch (Throwable ignored) {
            }
            return ServerTasks.classifyException(e, TASK_NAME);
        }
        return Exec.result(m, TASK_ID, TASK_NAME);
    }

    // ==================================================================================
    // 二、执行子流程（发车/上车/清场/目标发现）——网络交互，失败只 SKIP 不 ERROR 刷屏
    // ==================================================================================

    /**
     * 窗口内发起巨熊集结（type=411）。幂等：{@code srv:bear:{fpid}:{sid}:launched} 先落盘再请求，
     * 同一“当日 + 场次”只发一辆车（异常/超时后也不重发，避免重复消耗；宁漏不花）。
     *
     * @return true=本次新发起成功
     */
    private static boolean launchRally(TaskContext tc, Exec.Multi m, BearConfig cfg,
                                       BearState state, long nowSec, Window window) throws Exception {
        String key = launchedKeyOf(tc);
        String record = tc.store.getString(key);
        String day = dayKey(nowSec);
        if (launchedForWindow(record, day, window.startSec)) {
            m.skip("本场已发起（" + describeLaunched(record) + "），不重复发车");
            return false;
        }
        if (!cfg.launchEnabled) {
            m.skip("发起开关 launch_enabled=false，本轮不发车");
            return false;
        }
        long secondsToEnd = window.endSec - nowSec;
        if (!enoughTimeToLaunch(secondsToEnd, cfg.prepareTimeIndex)) {
            m.skip("剩余 " + secondsToEnd + "s 不足以按准备档 " + cfg.prepareTimeIndex
                    + "（" + prepareSeconds(cfg.prepareTimeIndex) + "s）发起，本场不发车");
            return false;
        }
        // 目标：先用缓存坐标复核（req_world_mapobj），失败再走完整发现链
        TrapTarget target = refreshTarget(tc, state);
        if (target == null) {
            StringBuilder why = new StringBuilder();
            target = discoverTarget(tc, why);
            if (target == null) {
                m.skip("未找到联盟熊坑目标：" + (why.length() == 0 ? "联盟建筑/世界地图确认为空" : why));
                return false;
            }
        }
        state.setTarget(target);
        Map<String, Object> formation = Exec.formation(tc.engine, 1);
        if (!formationReady(formation)) {
            saveState(tc, state);
            m.skip("无可用英雄/兵力编队（Exec.formation(engine,1) 为空），未发起集结");
            return false;
        }
        Map<String, Object> payload = launchPayload(target, formation, cfg.prepareTimeIndex);
        if (payload == null) {
            saveState(tc, state);
            m.skip("发起参数不完整（熊坑坐标/monsterid/编队缺失），未发起");
            return false;
        }
        state.launchHeroes = appendHeroIds("", heroIdsOf(formation));
        saveState(tc, state);
        // 先落幂等标记（marchId=0=已尝试），再发请求：异常/超时同样不会发第二辆车
        tc.store.putString(key, launchedRecord(day, window.startSec, 0L));
        GameMessage rsp = tc.engine.request("req_world_march", payload);
        int code = rsp == null ? -1 : Exec.codeOf(rsp);
        long marchId = Exec.marchId(rsp);
        if (marchId > 0) {
            tc.store.putString(key, launchedRecord(day, window.startSec, marchId));
            m.claimed("发起巨熊集结(marchId=" + marchId + ")");
            tc.saveModText(TASK_ID, "launch", "已发起 · marshId=" + marchId
                    + " · 目标(" + target.x + "," + target.y + ")monster=" + target.monsterId);
            FileLog.info("巨熊发车成功 fpid={} sid={} marchId={} target=({},{}) monsterid={} 场次={}",
                    tc.fpid, String.valueOf(tc.sid), String.valueOf(marchId),
                    String.valueOf(target.x), String.valueOf(target.y),
                    String.valueOf(target.monsterId), day + "#" + window.startSec);
            return true;
        }
        tc.store.putString(key, launchedFailedRecord(day, window.startSec, code));
        String text = bearCodeText(code);
        m.skip("发起集结未成功（码" + code + "）" + (text.isEmpty() ? "" : "：" + text));
        FileLog.warn("巨熊发车失败 fpid={} sid={} code={} 场次={}", tc.fpid,
                String.valueOf(tc.sid), String.valueOf(code), day + "#" + window.startSec);
        return false;
    }

    /** 窗口内上车（type=99，默认 1 队、上限 6），并对账最多 1 次（req_world_mass_master_detail）。 */
    private static void joinRallies(TaskContext tc, Exec.Multi m, BearConfig cfg, BearState state,
                                    long nowSec, Window window) throws Exception {
        if (!cfg.joinEnabled) {
            m.skip("上车开关 join_enabled=false");
            return;
        }
        String key = joinsKeyOf(tc);
        String day = dayKey(nowSec);
        List<Long> joined = joinedMasters(tc.store.getString(key), day, window.startSec);
        int remaining = cfg.joinCount - joined.size();
        if (remaining <= 0) {
            m.skip("本场上车已达配置上限 " + cfg.joinCount + " 队");
            return;
        }
        long ownUid = tc.engine.lastUid();
        long allianceId = state.targetAllianceId;
        List<RallyCandidate> candidates = rallyCandidates(tc.engine, state.targetX, state.targetY,
                ownUid, allianceId, joined, nowSec);
        if (candidates.isEmpty()) {
            m.skip("未发现可加入的巨熊集结（等待联盟车头发车）");
            return;
        }
        Set<Long> usedHeroes = new LinkedHashSet<>();
        usedHeroes.addAll(parseHeroIds(state.launchHeroes));
        usedHeroes.addAll(parseHeroIds(state.joinHeroes));
        Map<Long, List<Long>> squads = Exec.clientSquads(tc.engine);
        boolean detailQueried = false;
        int tried = 0;
        for (RallyCandidate candidate : candidates) {
            if (tried >= remaining) break;
            List<Long> heroes = chooseSquadHeroes(squads, usedHeroes);
            if (heroes.isEmpty()) {
                m.skip("无空闲英雄编队可上车");
                break;
            }
            Map<String, Object> formation = Exec.formationWithHeroes(tc.engine, heroes);
            if (!formationReady(formation)) {
                m.skip("上车编队无可用兵力，停止上车");
                break;
            }
            Map<String, Object> payload = joinPayload(candidate.masterMarchId,
                    candidate.startX, candidate.startY, formation);
            if (payload == null) {
                m.skip("上车参数不完整（车头坐标/编队缺失）");
                break;
            }
            // 先落台账（避免同一车头重复请求），再发请求
            tc.store.putString(key, joinedRecordAppend(tc.store.getString(key), day, window.startSec,
                    candidate.masterMarchId));
            GameMessage rsp = tc.engine.request("req_world_march", payload);
            tried++;
            int code = rsp == null ? -1 : Exec.codeOf(rsp);
            if (code == 0) {
                boolean confirmed = responseConfirmsJoin(rsp.getContent(), candidate.masterMarchId);
                if (!confirmed && !detailQueried) {
                    detailQueried = true;
                    confirmed = detailConfirmsJoin(tc, candidate, ownUid);
                }
                state.joinHeroes = appendHeroIds(state.joinHeroes, heroes);
                usedHeroes.addAll(heroes);
                if (confirmed) {
                    m.claimed("上车×1(车头" + candidate.masterMarchId + ")");
                    FileLog.info("巨熊上车成功 fpid={} sid={} masterMarchId={} 车头=({},{})",
                            tc.fpid, String.valueOf(tc.sid), String.valueOf(candidate.masterMarchId),
                            String.valueOf(candidate.startX), String.valueOf(candidate.startY));
                } else {
                    m.skip("上车请求已发(车头" + candidate.masterMarchId + ")，成员详情未确认");
                }
                continue;
            }
            if (code == CODE_ALREADY_JOINED) {
                m.skip("当前账号已在目标集结或通知滞后（码2844）");
            } else if (code == CODE_WORLD_MARCH_LIMIT) {
                m.skip("行军队列已满（码2878），停止上车");
                break;
            } else if (code == CODE_HERO_OCCUPIED) {
                m.skip("上车英雄被占用（码403），停止上车");
                break;
            } else if (code == CODE_TRAP_MASS_TIME_EXPIRED) {
                m.skip("巨熊集结已关闭（码5204）");
                break;
            } else {
                m.skip("上车失败（码" + code + "）");
            }
        }
        saveState(tc, state);
    }

    /**
     * 可选清场（默认关）：撤回普通采集/狩猎行军（{@code req_world_march_off_work{id}}）。
     * 1:1 对齐网页版“活动前 10 分钟清场”（DailyBearActionService:507-570 plan/recall），
     * 但默认不开启，避免破坏用户自己的采集/狩猎队列。行军列表只从推送快照读取，不额外发查询协议。
     *
     * @return 成功撤回的路数（0=未取到数据或无需撤回）
     */
    private static int recallOrdinaryMarches(TaskContext tc) {
        List<Long> marchIds = ordinaryMarchIds(tc.engine);
        if (marchIds.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (Long id : marchIds) {
            if (id == null || id <= 0) continue;
            try {
                GameMessage rsp = tc.engine.request("req_world_march_off_work", Exec.args("id", id));
                if (Exec.ok(rsp)) done++;
            } catch (Exception e) {
                FileLog.warn("巨熊清场撤回失败 fpid={} marchId={}：{}",
                        tc.fpid, String.valueOf(id), ServerTasks.brief(String.valueOf(e.getMessage())));
            }
        }
        return done;
    }

    /** 窗口内复核/刷新目标（req_world_mapobj，网页版 DailyBearMarchExecutor:127-141 refreshTarget）。 */
    private static TrapTarget refreshTarget(TaskContext tc, BearState state) {
        if (!targetUsable(state)) return null;
        long kid = tc.engine.lastServerId();
        if (kid <= 0) return null;
        try {
            GameMessage rsp = tc.engine.request("req_world_mapobj", Exec.args(
                    "kid", kid, "point", Exec.point(state.targetX, state.targetY)));
            if (rsp == null || !Exec.ok(rsp)) return null;
            TrapTarget target = parseTrapTarget(rsp.getContent(), state.targetX, state.targetY);
            if (target != null) {
                state.setTarget(target);
                return target;
            }
        } catch (Exception e) {
            FileLog.warn("巨熊目标复核失败 fpid={}：{}", tc.fpid,
                    ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return null;
    }

    /** 目标复核 + 兜底发现（准备窗预取与发车共用）。 */
    private static TrapTarget resolveTarget(TaskContext tc, BearState state, StringBuilder why) {
        TrapTarget target = refreshTarget(tc, state);
        if (target != null) return target;
        return discoverTarget(tc, why);
    }

    /** 目标发现链：联盟建筑列表（33 熊坑）→ 存在性兜底 → req_world_mapobj 二次校验（网页版:243-300）。 */
    private static TrapTarget discoverTarget(TaskContext tc, StringBuilder why) {
        try {
            List<long[]> points = new ArrayList<>();
            try {
                GameMessage list = tc.engine.request("req_alliance_building_list",
                        Exec.args("type", ALLIANCE_TRAP_MAP_OBJECT_TYPE));
                if (list != null && Exec.ok(list)) {
                    points.addAll(allianceTrapPoints(list.getContent()));
                } else if (list != null) {
                    why.append("联盟建筑列表码").append(Exec.codeOf(list)).append("；");
                }
            } catch (Exception e) {
                why.append("联盟建筑列表异常；");
            }
            if (points.isEmpty()) {
                try {
                    GameMessage exist = tc.engine.request("req_alliance_building_list_exist",
                            Exec.args("mapobj_type", ALLIANCE_TRAP_MAP_OBJECT_TYPE));
                    if (exist != null && Exec.ok(exist)) {
                        points.addAll(allianceTrapPoints(exist.getContent()));
                    } else if (exist != null) {
                        why.append("熊坑存在性查询码").append(Exec.codeOf(exist)).append("；");
                    }
                } catch (Exception e) {
                    why.append("熊坑存在性查询异常；");
                }
            }
            if (points.isEmpty()) {
                why.append("联盟建筑中无已建成的熊坑(type=33)");
                return null;
            }
            long kid = tc.engine.lastServerId();
            if (kid <= 0) {
                why.append("服务区号未就绪");
                return null;
            }
            int tried = 0;
            for (long[] point : points) {
                if (tried++ >= 6) break;
                try {
                    GameMessage rsp = tc.engine.request("req_world_mapobj", Exec.args(
                            "kid", kid, "point", Exec.point(point[0], point[1])));
                    if (rsp == null || !Exec.ok(rsp)) continue;
                    TrapTarget target = parseTrapTarget(rsp.getContent(), point[0], point[1]);
                    if (target != null) return target;
                } catch (Exception ignored) {
                }
            }
            why.append("世界地图确认熊坑目标为空");
        } catch (Exception e) {
            why.append(ServerTasks.brief(String.valueOf(e.getMessage())));
        }
        return null;
    }

    // ==================================================================================
    // 三、纯函数：窗口计算（单测直接覆盖）
    // ==================================================================================

    /** 场次窗口。phase 取 WAITING/PREPARING/ACTIVE/ENDED；source 标明窗口来源。 */
    static final class Window {
        static final int WAITING = 0;
        static final int PREPARING = 1;
        static final int ACTIVE = 2;
        static final int ENDED = 3;
        static final int SRC_RESERVE = 0;
        static final int SRC_TRAP_START = 1;
        static final int SRC_FALLBACK = 2;

        final int phase;
        final int source;
        final long prepStartSec;
        final long startSec;
        final long endSec;

        Window(int phase, int source, long prepStartSec, long startSec, long endSec) {
            this.phase = phase;
            this.source = source;
            this.prepStartSec = prepStartSec;
            this.startSec = startSec;
            this.endSec = endSec;
        }
    }

    /**
     * 窗口判定（纯函数）：
     * <ol>
     *   <li>reserve_time 权威：窗口 = [reserve-10min, reserve, reserve+30min]；</li>
     *   <li>mass_start_time 兜底“场次刚开始时 reserve 已翻到下一场”的时序：
     *       若某场 [start-10min, start+30min] 覆盖当前时刻，取最近开始的那场为进行中；</li>
     *   <li>无进行中场次：有未来场次 → WAITING；reserve 有效但已过期 → ENDED；</li>
     *   <li>完全无 reserve_time（未预约/读不到）→ 配置周窗口（默认周五 21:00-21:30 北京时间）。</li>
     * </ol>
     */
    static Window windowOf(long nowSec, long reserveTime, long massStartTime, BearConfig cfg) {
        Window active = null;
        if (isValidEpoch(reserveTime)) {
            Window w = windowFrom(reserveTime, Window.SRC_RESERVE);
            if (nowSec >= w.prepStartSec && nowSec < w.endSec) active = w;
        }
        if (isValidEpoch(massStartTime) && massStartTime != reserveTime) {
            Window w = windowFrom(massStartTime, Window.SRC_TRAP_START);
            if (nowSec >= w.prepStartSec && nowSec < w.endSec
                    && (active == null || w.startSec > active.startSec)) {
                active = w;
            }
        }
        if (active != null) {
            int phase = nowSec < active.startSec ? Window.PREPARING : Window.ACTIVE;
            return new Window(phase, active.source, active.prepStartSec, active.startSec, active.endSec);
        }
        // 未来场次（reserve 优先；trapStart 未来时也接受，如服务端预置场次）
        Window next = null;
        if (isValidEpoch(reserveTime) && reserveTime > nowSec) {
            next = windowFrom(reserveTime, Window.SRC_RESERVE);
        }
        if (isValidEpoch(massStartTime) && massStartTime > nowSec
                && (next == null || massStartTime < next.startSec)) {
            next = windowFrom(massStartTime, Window.SRC_TRAP_START);
        }
        if (next != null) {
            return new Window(Window.WAITING, next.source, next.prepStartSec, next.startSec, next.endSec);
        }
        if (isValidEpoch(reserveTime)) {
            // 预约有效但已过期（reserve 是“下一场开始时间”，过期说明本场已结束/待下次同步）
            Window ended = windowFrom(reserveTime, Window.SRC_RESERVE);
            return new Window(Window.ENDED, Window.SRC_RESERVE,
                    ended.prepStartSec, ended.startSec, ended.endSec);
        }
        return fallbackWindow(nowSec, cfg);
    }

    private static Window windowFrom(long startSec, int source) {
        return new Window(Window.WAITING, source,
                startSec - PREP_LEAD_MINUTES * 60L, startSec,
                startSec + ACTIVITY_MINUTES * 60L);
    }

    /**
     * 配置周窗口（无 reserve_time 的兜底）：默认每周五 21:00-21:30 北京时间。
     * 进行中→PREPARING/ACTIVE；否则给下一次（跨周）WAITING。
     */
    static Window fallbackWindow(long nowSec, BearConfig cfg) {
        ZonedDateTime now = Instant.ofEpochSecond(nowSec).atZone(Beijing.ZONE_ID);
        long nowS = now.toEpochSecond();
        int startMinute = cfg.windowStartMinuteOfDay();
        int duration = cfg.windowDurationMinutes();
        LocalDate today = now.toLocalDate();
        for (int offset = -1; offset <= 1; offset++) {
            LocalDate day = today.plusDays(offset);
            if (day.getDayOfWeek().getValue() != cfg.windowWeekday) continue;
            long start = epochOf(day, startMinute);
            long prep = start - PREP_LEAD_MINUTES * 60L;
            long end = start + duration * 60L;
            if (nowS >= prep && nowS < end) {
                return new Window(nowS < start ? Window.PREPARING : Window.ACTIVE,
                        Window.SRC_FALLBACK, prep, start, end);
            }
        }
        int delta = (cfg.windowWeekday - today.getDayOfWeek().getValue() + 7) % 7;
        LocalDate nextDay = today.plusDays(delta);
        long start = epochOf(nextDay, startMinute);
        if (nowS >= start - PREP_LEAD_MINUTES * 60L) {
            start = epochOf(nextDay.plusWeeks(1), startMinute);
        }
        return new Window(Window.WAITING, Window.SRC_FALLBACK,
                start - PREP_LEAD_MINUTES * 60L, start, start + duration * 60L);
    }

    private static long epochOf(LocalDate day, int minuteOfDay) {
        return day.atStartOfDay(Beijing.ZONE_ID).plusMinutes(minuteOfDay).toEpochSecond();
    }

    // ==================================================================================
    // 四、纯函数：幂等键 / 场次 / 日期（单测直接覆盖）
    // ==================================================================================

    /** 北京时间日期键 yyyy-MM-dd（跨日口径与 App/服务器 st: 键一致）。 */
    static String dayKey(long epochSec) {
        return Beijing.fmt("yyyy-MM-dd").format(new Date(epochSec * 1000L));
    }

    /** 幂等记录：当日日期 # 场次开始秒 # 结果（marchId / code:xxx / 0=已尝试未确认）。 */
    static String launchedRecord(String dayKey, long windowStartSec, long marchId) {
        return dayKey + "#" + windowStartSec + "#" + marchId;
    }

    static String launchedFailedRecord(String dayKey, long windowStartSec, int code) {
        return dayKey + "#" + windowStartSec + "#code:" + code;
    }

    /** 该记录是否属于同一“当日 + 场次”（窗口内不重复发车）。 */
    static boolean launchedForWindow(String record, String dayKey, long windowStartSec) {
        if (record == null || record.isEmpty()) return false;
        return record.startsWith(dayKey + "#" + windowStartSec + "#");
    }

    /** 记录里的 marchId（0=未确认/失败）。 */
    static long launchedMarchId(String record) {
        if (record == null) return 0;
        int idx = record.lastIndexOf('#');
        if (idx < 0 || idx + 1 >= record.length()) return 0;
        String tail = record.substring(idx + 1);
        if (tail.startsWith("code:")) return 0;
        try {
            return Math.max(0, Long.parseLong(tail.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 日志/文案用的已发起描述。 */
    static String describeLaunched(String record) {
        if (record == null || record.isEmpty()) return "无记录";
        long marchId = launchedMarchId(record);
        if (marchId > 0) return "marchId=" + marchId;
        if (record.contains("#code:")) return "发起失败记录(" + record.substring(record.lastIndexOf("code:")) + ")";
        return "已尝试未确认";
    }

    /** 上车台账：当日日期 # 场次开始秒 # 已尝试车头 master_marchid 列表（逗号分隔）。 */
    static String joinedRecordAppend(String record, String dayKey, long windowStartSec, long masterMarchId) {
        List<Long> ids = joinedMasters(record, dayKey, windowStartSec);
        if (!ids.contains(masterMarchId)) ids.add(masterMarchId);
        StringBuilder sb = new StringBuilder(dayKey).append('#').append(windowStartSec).append('#');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    /** 解析某场次已尝试上车的车头 id 列表（形状不符/跨场次→空）。 */
    static List<Long> joinedMasters(String record, String dayKey, long windowStartSec) {
        List<Long> out = new ArrayList<>();
        if (record == null || record.isEmpty()) return out;
        String prefix = dayKey + "#" + windowStartSec + "#";
        if (!record.startsWith(prefix)) return out;
        String tail = record.substring(prefix.length());
        if (tail.isEmpty()) return out;
        for (String part : tail.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    // ==================================================================================
    // 五、纯函数：协议参数构造（单测直接覆盖）
    // ==================================================================================

    /**
     * 发起巨熊集结 payload（1:1 网页版 DailyBearMarchExecutor:677-680）：
     * {@code {type:411, endpoint:{x,y}, formation:{hero_id,soldier}, extra:{monsterid, prepare_time_index}}}。
     */
    static Map<String, Object> launchPayload(TrapTarget target, Map<String, Object> formation, int prepareTimeIndex) {
        if (target == null || target.monsterId <= 0 || target.x <= 0 || target.y <= 0) return null;
        if (!formationReady(formation)) return null;
        int prepared = clampPrepareIndex(prepareTimeIndex);
        return Exec.args(
                "type", MARCH_TYPE_BEAR_RALLY,
                "endpoint", Exec.point(target.x, target.y),
                "formation", formation,
                "extra", Exec.args("monsterid", target.monsterId, "prepare_time_index", prepared));
    }

    /**
     * 上车 payload（网页版 DailyBearMarchExecutor:681-685）：
     * {@code {type:99, endpoint:车头城市坐标, formation, extra:{master_marchid}}}。
     */
    static Map<String, Object> joinPayload(long masterMarchId, long leaderCityX, long leaderCityY,
                                           Map<String, Object> formation) {
        if (masterMarchId <= 0 || leaderCityX <= 0 || leaderCityY <= 0) return null;
        if (!formationReady(formation)) return null;
        return Exec.args(
                "type", MARCH_TYPE_JOIN_RALLY,
                "endpoint", Exec.point(leaderCityX, leaderCityY),
                "formation", formation,
                "extra", Exec.args("master_marchid", masterMarchId));
    }

    /** 编队是否可用：hero_id 与 soldier 均非空（网页版 formationPayload 的 requireValid 同口径）。 */
    static boolean formationReady(Map<String, Object> formation) {
        if (formation == null || formation.isEmpty()) return false;
        List<Object> heroes = Exec.listOf(formation.get("hero_id"));
        if (heroes == null || heroes.isEmpty()) return false;
        List<Object> soldiers = Exec.listOf(formation.get("soldier"));
        return soldiers != null && !soldiers.isEmpty();
    }

    /** 编队英雄 id（launch/join 去重占位用）。 */
    static List<Long> heroIdsOf(Map<String, Object> formation) {
        List<Long> out = new ArrayList<>();
        if (formation == null) return out;
        List<Object> heroes = Exec.listOf(formation.get("hero_id"));
        if (heroes == null) return out;
        for (Object raw : heroes) {
            Map<String, Object> pair = Exec.mapOf(raw);
            long id = pair == null ? Exec.longOf(raw) : Exec.longOf(pair.get("k"));
            if (id > 0) out.add(id);
        }
        return out;
    }

    static String appendHeroIds(String csv, List<Long> ids) {
        LinkedHashSet<Long> all = new LinkedHashSet<>(parseHeroIds(csv));
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0) all.add(id);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : all) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    static List<Long> parseHeroIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 选一组与已占用英雄不冲突的客户端布阵（网页版“发起队/上车队英雄不重复”）。 */
    static List<Long> chooseSquadHeroes(Map<Long, List<Long>> squads, Set<Long> usedHeroes) {
        if (squads == null || squads.isEmpty()) return new ArrayList<>();
        Set<Long> used = usedHeroes == null ? Collections.emptySet() : usedHeroes;
        for (Map.Entry<Long, List<Long>> e : squads.entrySet()) {
            List<Long> heroes = e.getValue();
            if (heroes == null || heroes.isEmpty()) continue;
            boolean conflict = false;
            for (Long hero : heroes) {
                if (hero != null && used.contains(hero)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return new ArrayList<>(heroes);
        }
        return new ArrayList<>();
    }

    /** 准备档 index → 秒（网页版 DailyBearMarchExecutor.minimumRemainingSeconds:690-698）。 */
    static int prepareSeconds(int prepareTimeIndex) {
        switch (clampPrepareIndex(prepareTimeIndex)) {
            case 1: return 320;
            case 2: return 620;
            case 3: return 1820;
            case 4: return 3620;
            default: return 620;
        }
    }

    static int clampPrepareIndex(int index) {
        return Math.max(1, Math.min(4, index));
    }

    /** 剩余时间是否够按准备档发起（网页版 :154-158 的停止发起守卫）。 */
    static boolean enoughTimeToLaunch(long secondsToEnd, int prepareTimeIndex) {
        return secondsToEnd >= prepareSeconds(prepareTimeIndex);
    }

    // ==================================================================================
    // 六、纯函数：通知/响应解析（单测用真机形状覆盖）
    // ==================================================================================

    /** 预约/场次时间（秒域，0=未知）。 */
    static final class TrapTimes {
        long reserve;
        long start;
        long end;
        long cool;

        boolean any() {
            return reserve > 0 || start > 0 || end > 0 || cool > 0;
        }
    }

    /**
     * 解析 {@code req_alliance_trap_info} 响应 / {@code notify_alliance_trap_update} 推送（网页版
     * DailyBearActionState.updateActivity:142-160）：字段 {@code reserve_time / mass_start_time /
     * mass_end_time / cool_end_time}（驼峰变体一并兼容，深度 ≤4 防御式查找）。
     */
    static TrapTimes parseTrapTimes(Map<String, Object> content) {
        if (content == null) return null;
        Map<String, Object> unwrapped = unwrap(content);
        TrapTimes t = new TrapTimes();
        t.reserve = deepLong(unwrapped, 0, "reserve_time", "reserveTime", "reserve_time_sec");
        t.start = deepLong(unwrapped, 0, "mass_start_time", "massStartTime");
        t.end = deepLong(unwrapped, 0, "mass_end_time", "massEndTime");
        t.cool = deepLong(unwrapped, 0, "cool_end_time", "coolEndTime", "cooldown_end_time");
        return t.any() ? t : null;
    }

    /** 解析 {@code req_alliance_trap_info} 响应：无效/降级响应返回 null（不把异常形状当数据）。 */
    static TrapTimes syncTrapTimes(GameMessage rsp) {
        if (rsp == null || !Exec.ok(rsp)) return null;
        try {
            return parseTrapTimes(rsp.getContent());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 引擎缓存里的预约推送（{@code notify_alliance_trap_update}，登录批也可能有同名 info 推送）。 */
    static TrapTimes trapTimesFromNotifies(WjdrEngine engine) {
        if (engine == null) return null;
        for (String proto : new String[]{"notify_alliance_trap_update", "notify_alliance_trap_info"}) {
            try {
                Map<String, Object> content = engine.notifyContent(proto);
                TrapTimes times = parseTrapTimes(content);
                if (times != null) return times;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 熊坑目标（req_world_mapobj 解析结果）：monsterid + 坐标 + 归属联盟。 */
    static final class TrapTarget {
        long monsterId;
        long x;
        long y;
        long allianceId;

        TrapTarget(long monsterId, long x, long y, long allianceId) {
            this.monsterId = monsterId;
            this.x = x;
            this.y = y;
            this.allianceId = allianceId;
        }
    }

    /**
     * 解析 {@code req_world_mapobj} 响应（网页版 DailyBearActionState.updateMapTarget:156-197）：
     * {@code mapobj.map_alliance_trap{id,aid/occupy_aid}} + {@code mapobj.point{x,y}}（多包装层/坐标缺省回退）。
     */
    static TrapTarget parseTrapTarget(Map<String, Object> content, long expectedX, long expectedY) {
        if (content == null) return null;
        Map<String, Object> mapObj = firstNonEmptyMap(content, "mapobj", "march_map_obj", "obj", "change", "data");
        if (mapObj == null || mapObj.isEmpty()) mapObj = content;
        Map<String, Object> trap = Exec.mapOf(mapObj.get("map_alliance_trap"));
        if (trap == null || trap.isEmpty()) trap = findMap(content, "map_alliance_trap", 0);
        if (trap == null || trap.isEmpty()) return null;
        long monsterId = positive(trap.get("id"), trap.get("monsterid"), trap.get("monster_id"));
        Map<String, Object> point = Exec.mapOf(mapObj.get("point"));
        if (point == null || point.isEmpty()) point = Exec.mapOf(content.get("point"));
        long x = point == null ? 0 : positive(point.get("x"));
        long y = point == null ? 0 : positive(point.get("y"));
        if (x <= 0) x = expectedX;
        if (y <= 0) y = expectedY;
        long allianceId = positive(trap.get("aid"), trap.get("alliance_id"), trap.get("occupy_aid"));
        if (monsterId <= 0 || x <= 0 || y <= 0) return null;
        return new TrapTarget(monsterId, x, y, allianceId);
    }

    /**
     * 解析 {@code req_alliance_building_list} / {@code req_alliance_building_list_exist} 响应里的熊坑坐标：
     * {@code building_type_2_list[].mapobj_type==33 → building_list[]}（网页版 DailyServiceSupport.allianceBuildings:124-148），
     * 也兼容顶层 building_list；坐标取 x/y 或 point{x,y}；is_finish 缺省视为已建成，显式 false 过滤。
     */
    static List<long[]> allianceTrapPoints(Map<String, Object> content) {
        List<long[]> out = new ArrayList<>();
        if (content == null) return out;
        Map<String, Object> unwrapped = unwrap(content);
        List<Map<String, Object>> groups = Exec.mapsOf(unwrapped.get("building_type_2_list"));
        for (Map<String, Object> group : groups) {
            if (Exec.longOf(group.get("mapobj_type")) != ALLIANCE_TRAP_MAP_OBJECT_TYPE) continue;
            collectBuildingPoints(group.get("building_list"), out);
        }
        collectBuildingPoints(unwrapped.get("building_list"), out);
        return out;
    }

    private static void collectBuildingPoints(Object rawList, List<long[]> out) {
        for (Map<String, Object> building : Exec.mapsOf(rawList)) {
            if (!isFinish(building.get("is_finish"))) continue;
            long x = Exec.longOf(building.get("x"));
            long y = Exec.longOf(building.get("y"));
            if (x <= 0 || y <= 0) {
                Map<String, Object> point = Exec.mapOf(building.get("point"));
                if (point != null) {
                    x = Exec.longOf(point.get("x"));
                    y = Exec.longOf(point.get("y"));
                }
            }
            if (x <= 0 || y <= 0) continue;
            boolean duplicate = false;
            for (long[] exists : out) {
                if (exists[0] == x && exists[1] == y) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(new long[]{x, y});
        }
    }

    /** 显式真值（null/缺省=false；用于 is_start 这类“只有明确 true 才动作”的字段）。 */
    static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static boolean isFinish(Object v) {
        if (v == null) return true;              // 字段缺省不阻塞（仍由 req_world_mapobj 二次校验）
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    /** 巨熊集结候选（来自世界态势/集结推送，网页版 BehemothAccountState.bearRallyCandidates:894-921 同口径字段）。 */
    static final class RallyCandidate {
        long masterMarchId;
        long type;
        long state;
        long ownerUid;
        long ownerKid;
        long allianceId;
        long endTime;
        long startX;
        long startY;
        long endX;
        long endY;
        long memberCount;
        long memberMax;
        long soldierCount;
        long soldierMax;

        boolean massFull() {
            return (memberMax > 0 && memberCount >= memberMax)
                    || (soldierMax > 0 && soldierCount >= soldierMax);
        }
    }

    /** 从推送内容解析集结候选：{@code {marchs:[...]}} / {@code {march:{...}}} / {@code {data:...}} 多形状兼容。 */
    static List<RallyCandidate> parseRallyCandidates(Map<String, Object> content) {
        List<RallyCandidate> out = new ArrayList<>();
        if (content == null) return out;
        Map<String, Object> data = unwrap(content);
        List<Map<String, Object>> marches = Exec.mapsOf(data.get("marchs"));
        if (marches.isEmpty()) {
            Map<String, Object> march = Exec.mapOf(data.get("march"));
            if (march != null && !march.isEmpty()) marches = Collections.singletonList(march);
        }
        for (Map<String, Object> march : marches) {
            RallyCandidate candidate = rallyCandidateOf(march);
            if (candidate != null) out.add(candidate);
        }
        return out;
    }

    static RallyCandidate rallyCandidateOf(Map<String, Object> march) {
        if (march == null || march.isEmpty()) return null;
        RallyCandidate c = new RallyCandidate();
        c.masterMarchId = Exec.longOf(march.get("id"));
        c.type = Exec.longOf(march.get("type"));
        c.state = Exec.longOf(march.get("state"));
        c.ownerUid = Exec.longOf(march.get("owner"));
        c.ownerKid = Exec.longOf(march.get("kid"));
        c.allianceId = Exec.longOf(march.get("aid"));
        c.endTime = Exec.longOf(march.get("endtime"));
        Map<String, Object> start = Exec.mapOf(march.get("startpoint"));
        if (start != null) {
            c.startX = Exec.longOf(start.get("x"));
            c.startY = Exec.longOf(start.get("y"));
        }
        Map<String, Object> end = Exec.mapOf(march.get("endpoint"));
        if (end != null) {
            c.endX = Exec.longOf(end.get("x"));
            c.endY = Exec.longOf(end.get("y"));
        }
        Map<String, Object> mass = Exec.mapOf(march.get("mass_alliance_trap"));
        if (mass == null || mass.isEmpty()) mass = Exec.mapOf(march.get("mass_master"));
        if (mass == null || mass.isEmpty()) mass = Exec.mapOf(march.get("mass_boss"));
        if (mass != null && !mass.isEmpty()) {
            c.memberMax = Exec.longOf(mass.get("max"));
            List<Object> uids = Exec.listOf(mass.get("uids"));
            c.memberCount = uids == null ? 0 : uids.size();
            c.soldierCount = Exec.longOf(mass.get("soldier_count"));
            c.soldierMax = Exec.longOf(mass.get("soldier_max"));
        }
        // 只缓存巨熊集结（type=411），避免无关集结占用内存
        return c.type == MARCH_TYPE_BEAR_RALLY ? c : null;
    }

    /**
     * 是否可加入该巨熊集结（1:1 网页版 bearRallyCandidate:3021-3036）：
     * type=411 / state=5 / 非自己发起 / 集结未满 / 终点=熊坑坐标 / 同联盟 / 未过期。
     */
    static boolean bearRallyJoinable(RallyCandidate c, long targetX, long targetY, long ownUid,
                                     long allianceId, long nowSec) {
        if (c == null || c.masterMarchId <= 0) return false;
        if (c.type != MARCH_TYPE_BEAR_RALLY) return false;
        if (c.state != 5L) return false;
        if (c.ownerUid > 0 && c.ownerUid == ownUid) return false;
        if (c.startX <= 0 || c.startY <= 0) return false;
        if (c.endX != targetX || c.endY != targetY) return false;
        if (allianceId > 0 && c.allianceId > 0 && c.allianceId != allianceId) return false;
        if (c.endTime > 0 && nowSec > 0 && c.endTime <= nowSec) return false;
        return !c.massFull();
    }

    /** 从引擎缓存/推送快照里筛出可加入的巨熊集结（按 master_marchid 去重排序）。 */
    static List<RallyCandidate> rallyCandidates(WjdrEngine engine, long targetX, long targetY, long ownUid,
                                                long allianceId, List<Long> excluded, long nowSec) {
        Map<Long, RallyCandidate> merged = new LinkedHashMap<>();
        if (engine != null) {
            for (String proto : new String[]{
                    "notify_world_alliance_situation_init",
                    "notify_world_alliance_situation_activity",
                    "notify_world_alliance_situation_add",
                    "notify_world_alliance_situation_update",
                    "notify_mass_march_add"}) {
                try {
                    Map<String, Object> content = engine.notifyContent(proto);
                    for (RallyCandidate c : parseRallyCandidates(content)) {
                        if (c.masterMarchId > 0) merged.putIfAbsent(c.masterMarchId, c);
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                for (RallyCandidate c : rallyCacheOf(engine).candidates()) {
                    merged.putIfAbsent(c.masterMarchId, c);
                }
            } catch (Throwable ignored) {
            }
        }
        Set<Long> skip = excluded == null ? Collections.emptySet() : new LinkedHashSet<>(excluded);
        List<RallyCandidate> out = new ArrayList<>();
        for (RallyCandidate c : merged.values()) {
            if (skip.contains(c.masterMarchId)) continue;
            if (bearRallyJoinable(c, targetX, targetY, ownUid, allianceId, nowSec)) out.add(c);
        }
        out.sort((a, b) -> Long.compare(a.masterMarchId, b.masterMarchId));
        if (out.size() > 12) out = new ArrayList<>(out.subList(0, 12));
        return out;
    }

    /** 上车同步响应是否确认（网页版 responseConfirmsJoinedMaster:506-511）。 */
    static boolean responseConfirmsJoin(Map<String, Object> content, long masterMarchId) {
        if (content == null) return false;
        Map<String, Object> march = Exec.mapOf(content.get("march_info"));
        if (march == null) return false;
        Map<String, Object> slave = Exec.mapOf(march.get("mass_slave"));
        return slave != null && Exec.longOf(slave.get("master_marchid")) == masterMarchId;
    }

    /** {@code req_world_mass_master_detail} 是否确认自己在集结成员里（网页版 queryRallyMembers:473-504）。 */
    static boolean memberPresent(Map<String, Object> content, long ownUid) {
        if (content == null || ownUid <= 0) return false;
        for (Map<String, Object> detail : Exec.mapsOf(content.get("detail_list"))) {
            if (Exec.longOf(detail.get("owner")) == ownUid || Exec.longOf(detail.get("uid")) == ownUid) {
                return true;
            }
        }
        return false;
    }

    /** 对账一次（每轮最多 1 次，网页版 RALLY_MEMBER_DETAIL_TIMEOUT 同语义）。 */
    private static boolean detailConfirmsJoin(TaskContext tc, RallyCandidate candidate, long ownUid) {
        try {
            GameMessage rsp = tc.engine.request("req_world_mass_master_detail", Exec.args(
                    "id", candidate.masterMarchId, "owner", candidate.ownerUid, "kid", candidate.ownerKid));
            return rsp != null && memberPresent(rsp.getContent(), ownUid);
        } catch (Exception e) {
            return false;
        }
    }

    /** 从推送快照里取普通采集/狩猎行军 id（只用于可选清场，不额外发查询协议）。 */
    static List<Long> ordinaryMarchIds(WjdrEngine engine) {
        List<Long> out = new ArrayList<>();
        if (engine == null) return out;
        for (String proto : new String[]{"notify_world_player_init", "notify_player_march_add"}) {
            try {
                Map<String, Object> content = engine.notifyContent(proto);
                collectOrdinaryMarchIds(content, out, 0);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void collectOrdinaryMarchIds(Object node, List<Long> out, int depth) {
        if (node == null || depth > 4 || out.size() >= 8) return;
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            long type = Exec.longOf(map.get("type"));
            long id = Exec.longOf(map.get("id"));
            if (id > 0 && (type == MARCH_TYPE_HUNT || type == MARCH_TYPE_GATHER)) {
                if (!out.contains(id)) out.add(id);
                return;
            }
            for (Object value : map.values()) collectOrdinaryMarchIds(value, out, depth + 1);
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) collectOrdinaryMarchIds(value, out, depth + 1);
        }
    }

    /** 错误码文案（原码原样记录，语义取网页版/审计口径；未知码不猜）。 */
    static String bearCodeText(int code) {
        switch (code) {
            case CODE_TRAP_MASS_TIME_EXPIRED:
                return "服务端已关闭本场巨熊发车时间（码5204），继续尝试上车";
            case CODE_HERO_OCCUPIED:
                return "英雄被占用/安全拒绝（码403）";
            case CODE_ALREADY_JOINED:
                return "已加入目标集结或通知滞后（码2844）";
            case CODE_WORLD_MARCH_LIMIT:
                return "行军队列已满（码2878）";
            default:
                return "";
        }
    }

    // ==================================================================================
    // 七、纯函数：配置 / 触发节流 / mod 文案（单测直接覆盖）
    // ==================================================================================

    /** 任务参数（App FnParams 同源；全部有默认值，缺省不阻塞）。 */
    static final class BearConfig {
        final boolean launchEnabled;
        final boolean joinEnabled;
        final boolean prepareRecallEnabled;
        /** 未读取到预约时是否允许按配置周窗口发起（默认 false：未预约→SKIP，不臆造场次）。 */
        final boolean launchWithoutReserve;
        final int joinCount;
        final int prepareTimeIndex;
        final int windowWeekday;       // ISO：1=周一 … 7=周日（默认 5=周五）
        final int windowStartMinute;   // 0..1439（北京时间）
        final int windowDurationMinutes;

        BearConfig(boolean launchEnabled, boolean joinEnabled, boolean prepareRecallEnabled,
                   boolean launchWithoutReserve, int joinCount, int prepareTimeIndex,
                   int windowWeekday, int windowStartMinute, int windowDurationMinutes) {
            this.launchEnabled = launchEnabled;
            this.joinEnabled = joinEnabled;
            this.prepareRecallEnabled = prepareRecallEnabled;
            this.launchWithoutReserve = launchWithoutReserve;
            this.joinCount = Math.max(1, Math.min(MAX_JOIN_COUNT, joinCount));
            this.prepareTimeIndex = clampPrepareIndex(prepareTimeIndex);
            this.windowWeekday = Math.max(1, Math.min(7, windowWeekday));
            this.windowStartMinute = Math.max(0, Math.min(1439, windowStartMinute));
            this.windowDurationMinutes = Math.max(1, Math.min(180, windowDurationMinutes));
        }

        int windowStartMinuteOfDay() {
            return windowStartMinute;
        }

        int windowDurationMinutes() {
            return windowDurationMinutes;
        }

        static BearConfig defaults() {
            return new BearConfig(true, true, false, false, 1, DEFAULT_PREPARE_TIME_INDEX,
                    5, 21 * 60, ACTIVITY_MINUTES);
        }

        /** 参数解析：launch_enabled/join_enabled/prepare_recall/join_count/prepare_time_index/
         *  window_weekday/window_start_hour/_minute/window_end_hour/_minute（默认周五 21:00-21:30）。 */
        static BearConfig of(JSONObject params) {
            JSONObject p = params == null ? new JSONObject() : params;
            int startHour = clamp(p.optInt("window_start_hour", 21), 0, 23);
            int startMin = clamp(p.optInt("window_start_minute", 0), 0, 59);
            int endHour = clamp(p.optInt("window_end_hour", 21), 0, 23);
            int endMin = clamp(p.optInt("window_end_minute", 30), 0, 59);
            int startMinute = startHour * 60 + startMin;
            int endMinute = endHour * 60 + endMin;
            int duration = endMinute - startMinute;
            if (duration <= 0) duration = ACTIVITY_MINUTES;  // 不支持跨零点：非法配置回落到 30 分钟
            return new BearConfig(
                    optBool(p, "launch_enabled", true),
                    optBool(p, "join_enabled", true),
                    optBool(p, "prepare_recall", false),
                    optBool(p, "launch_without_reserve", false),
                    p.optInt("join_count", 1),
                    p.optInt("prepare_time_index", DEFAULT_PREPARE_TIME_INDEX),
                    p.optInt("window_weekday", 5),
                    startMinute, duration);
        }
    }

    /**
     * 是否该发起一次预约同步（纯函数）：
     * 首次必同步；预约/场次附近（±30 分钟）5 分钟一次；配置周窗口前 1 小时到结束后 30 分钟 5 分钟一次；
     * 其余时间 6 小时一次。节奏调度 15s 一拍时靠它保持请求零放大。
     */
    static boolean needTrapSync(long nowSec, long syncAt, long reserveTime, long massStartTime, BearConfig cfg) {
        if (syncAt <= 0 || nowSec < syncAt) return true;
        long age = nowSec - syncAt;
        if (nearEvent(nowSec, reserveTime) || nearEvent(nowSec, massStartTime)) {
            return age >= SYNC_NEAR_WINDOW_SEC;
        }
        Window fallback = fallbackWindow(nowSec, cfg);
        if (fallback.startSec > 0
                && nowSec >= fallback.startSec - 3600
                && nowSec <= fallback.endSec + 1800) {
            return age >= SYNC_NEAR_WINDOW_SEC;
        }
        return age >= SYNC_IDLE_SEC;
    }

    private static boolean nearEvent(long nowSec, long startSec) {
        return isValidEpoch(startSec)
                && nowSec >= startSec - 1800
                && nowSec <= startSec + 1800;
    }

    /** mod 文案（网页版 scheduleMessage:703-723 口径）：已预约 HH:mm / 窗口内 / 未预约。 */
    static String trapSummary(BearState state, Window window, boolean launchWithoutReserve) {
        if (state != null && isValidEpoch(state.reserve)) {
            String reserved = "已预约 " + Beijing.fmt("M月d日 HH:mm")
                    .format(new Date(state.reserve * 1000L));
            if (window == null) return reserved;
            if (window.phase == Window.ACTIVE) {
                return "窗口内 " + timeRange(window) + "（" + reserved + "）";
            }
            if (window.phase == Window.PREPARING) return reserved + " · 准备窗口中";
            if (window.phase == Window.ENDED) return reserved + " · 本场已结束";
            return reserved + " · 等待窗口";
        }
        if (window != null && (window.phase == Window.ACTIVE || window.phase == Window.PREPARING)
                && launchWithoutReserve) {
            return "窗口内 " + timeRange(window) + " · 未读取到预约（按配置窗口执行）";
        }
        if (window != null && (window.phase == Window.ACTIVE || window.phase == Window.PREPARING)) {
            return "未预约 · 需在游戏内手动预约（配置观察窗口 " + timeRange(window) + "，不自动发车）";
        }
        if (window != null && window.phase == Window.ENDED) {
            return "未预约 · 本场配置窗口已结束";
        }
        return "未预约 · 需在游戏内手动预约";
    }

    /** 未读取到预约时是否应跳过配置窗口（默认 true：未预约→SKIP，不臆造场次；可显式放开）。 */
    static boolean shouldSkipFallbackWindow(Window window, BearConfig cfg) {
        if (window == null || cfg == null || cfg.launchWithoutReserve) return false;
        return window.source == Window.SRC_FALLBACK
                && (window.phase == Window.ACTIVE || window.phase == Window.PREPARING);
    }

    /** 窗口阶段文案。 */
    static String phaseText(Window window) {
        if (window == null) return "未知";
        switch (window.phase) {
            case Window.ACTIVE:
                return "窗口进行中 " + timeRange(window);
            case Window.PREPARING:
                return "准备窗口（" + fmtTime(window.startSec) + " 开赛）";
            case Window.ENDED:
                return "本场已结束 " + fmtTime(window.endSec);
            default:
                return window.startSec > 0
                        ? "等待窗口 " + Beijing.fmt("M月d日 HH:mm").format(new Date(window.startSec * 1000L))
                        : "未在窗口";
        }
    }

    private static String timeRange(Window window) {
        return fmtTime(window.startSec) + "-" + fmtTime(window.endSec);
    }

    private static String fmtTime(long epochSec) {
        return Beijing.fmt("HH:mm").format(new Date(epochSec * 1000L));
    }

    // ==================================================================================
    // 八、状态持久化（srv:bear:{fpid}:{sid}:*）
    // ==================================================================================

    /** 巨熊本地状态：预约/场次时间、熊坑目标、同步时刻、已占用英雄（跨轮次去重）。 */
    static final class BearState {
        long reserve;
        long start;
        long end;
        long cool;
        long syncAt;
        long targetX;
        long targetY;
        long targetMonsterId;
        long targetAllianceId;
        String launchHeroes = "";
        String joinHeroes = "";

        void mergeTimes(TrapTimes times) {
            if (times == null) return;
            if (times.reserve > 0) reserve = times.reserve;
            if (times.start > 0) start = times.start;
            if (times.end > 0) end = times.end;
            if (times.cool > 0) cool = times.cool;
        }

        /** 推送合并：时间只向前推进，取更大者（旧推送不回退请求结果；新推送可补本场 mass_start_time）。 */
        void mergeNewer(TrapTimes times) {
            if (times == null) return;
            if (isValidEpoch(times.reserve) && times.reserve > reserve) reserve = times.reserve;
            if (isValidEpoch(times.start) && times.start > start) start = times.start;
            if (isValidEpoch(times.end) && times.end > end) end = times.end;
            if (isValidEpoch(times.cool) && times.cool > cool) cool = times.cool;
        }

        void setTarget(TrapTarget target) {
            if (target == null) return;
            targetX = target.x;
            targetY = target.y;
            targetMonsterId = target.monsterId;
            if (target.allianceId > 0) targetAllianceId = target.allianceId;
        }
    }

    static String launchedKeyOf(TaskContext tc) {
        return KEY_PREFIX + tc.fpid + ":" + tc.sid + ":launched";
    }

    static String joinsKeyOf(TaskContext tc) {
        return KEY_PREFIX + tc.fpid + ":" + tc.sid + ":joins";
    }

    static String stateKeyOf(TaskContext tc) {
        return KEY_PREFIX + tc.fpid + ":" + tc.sid + ":state";
    }

    static String stateJson(BearState state) {
        if (state == null) return "{}";
        JSONObject o = new JSONObject();
        try {
            o.put("reserve", state.reserve);
            o.put("start", state.start);
            o.put("end", state.end);
            o.put("cool", state.cool);
            o.put("syncAt", state.syncAt);
            o.put("targetX", state.targetX);
            o.put("targetY", state.targetY);
            o.put("monsterId", state.targetMonsterId);
            o.put("allianceId", state.targetAllianceId);
            if (state.launchHeroes != null && !state.launchHeroes.isEmpty()) o.put("launchHeroes", state.launchHeroes);
            if (state.joinHeroes != null && !state.joinHeroes.isEmpty()) o.put("joinHeroes", state.joinHeroes);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    static BearState parseState(String raw) {
        BearState state = new BearState();
        if (raw == null || raw.isEmpty()) return state;
        try {
            JSONObject o = new JSONObject(raw);
            state.reserve = o.optLong("reserve", 0);
            state.start = o.optLong("start", 0);
            state.end = o.optLong("end", 0);
            state.cool = o.optLong("cool", 0);
            state.syncAt = o.optLong("syncAt", 0);
            state.targetX = o.optLong("targetX", 0);
            state.targetY = o.optLong("targetY", 0);
            state.targetMonsterId = o.optLong("monsterId", 0);
            state.targetAllianceId = o.optLong("allianceId", 0);
            state.launchHeroes = o.optString("launchHeroes", "");
            state.joinHeroes = o.optString("joinHeroes", "");
        } catch (Throwable ignored) {
        }
        return state;
    }

    private static BearState loadState(TaskContext tc) {
        try {
            return parseState(tc.store.getString(stateKeyOf(tc)));
        } catch (Throwable t) {
            return new BearState();
        }
    }

    private static void saveState(TaskContext tc, BearState state) {
        try {
            tc.store.putString(stateKeyOf(tc), stateJson(state));
        } catch (Throwable ignored) {
        }
    }

    private static void writeMod(TaskContext tc, String trapText, String windowText, String launchText) {
        try {
            tc.saveModText(TASK_ID, "trap", trapText == null ? "" : trapText);
            tc.saveModText(TASK_ID, "window", windowText == null ? "" : windowText);
            tc.saveModText(TASK_ID, "launch", launchText == null ? "" : launchText);
        } catch (Throwable ignored) {
        }
    }

    private static boolean targetUsable(BearState state) {
        return state != null
                && state.targetX > 0 && state.targetY > 0 && state.targetMonsterId > 0;
    }

    // ==================================================================================
    // 九、通用小工具（遍历/解析/时间）
    // ==================================================================================

    /** 当前时间（秒）：优先服务器时钟（未校准时 it 返回本机外推，语义一致）；异常回退本机。 */
    static long nowSec(TaskContext tc) {
        try {
            return tc.engine.serverClock().nowServerSec();
        } catch (Throwable t) {
            return System.currentTimeMillis() / 1000L;
        }
    }

    static boolean isValidEpoch(long sec) {
        return sec >= MIN_EPOCH_SEC && sec < 2_200_000_000L;
    }

    private static Map<String, Object> unwrap(Map<String, Object> content) {
        if (content == null) return Collections.emptyMap();
        for (String key : new String[]{"data", "info", "content", "trap", "trap_info"}) {
            Map<String, Object> inner = Exec.mapOf(content.get(key));
            if (inner != null && !inner.isEmpty()) return inner;
        }
        return content;
    }

    private static Map<String, Object> firstNonEmptyMap(Map<String, Object> content, String... keys) {
        if (content == null) return null;
        for (String key : keys) {
            Map<String, Object> map = Exec.mapOf(content.get(key));
            if (map != null && !map.isEmpty()) return map;
        }
        return null;
    }

    private static Map<String, Object> findMap(Object node, String key, int depth) {
        if (node == null || depth > 4) return null;
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            Map<String, Object> hit = Exec.mapOf(map.get(key));
            if (hit != null && !hit.isEmpty()) return hit;
            for (Object value : map.values()) {
                Map<String, Object> found = findMap(value, key, depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) {
                Map<String, Object> found = findMap(value, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static long deepLong(Object node, int depth, String... keys) {
        if (node == null || depth > 4) return 0;
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            for (String key : keys) {
                long value = Exec.longOf(map.get(key));
                if (isValidEpoch(value)) return value;
            }
            for (Object value : map.values()) {
                long found = deepLong(value, depth + 1, keys);
                if (found > 0) return found;
            }
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) {
                long found = deepLong(value, depth + 1, keys);
                if (found > 0) return found;
            }
        }
        return 0;
    }

    private static long positive(Object... values) {
        for (Object value : values) {
            long v = Exec.longOf(value);
            if (v > 0) return v;
        }
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean optBool(JSONObject params, String key, boolean fallback) {
        if (params == null || !params.has(key)) return fallback;
        Object value = params.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() != 0;
        String s = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return fallback;
    }

    // ==================================================================================
    // 十、集结候选内存缓存（补引擎“每协议只留最后一条推送”的缺口，仅用于上车发现）
    // ==================================================================================

    private static final Map<WjdrEngine, RallyCache> RALLY_CACHES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static RallyCache rallyCacheOf(WjdrEngine engine) {
        synchronized (RALLY_CACHES) {
            RallyCache cache = RALLY_CACHES.get(engine);
            if (cache != null) return cache;
            final RallyCache created = new RallyCache();
            RALLY_CACHES.put(engine, created);
            try {
                engine.addMessageHook((proto, content) -> {
                    try {
                        created.onPush(proto, content);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
            return created;
        }
    }

    /** 仅收集世界态势/集结推送里的巨熊集结（type=411）；TTL 15 分钟，容量上限 64。 */
    static final class RallyCache {
        private static final long ENTRY_TTL_MS = RALLY_CACHE_TTL_SEC * 1000L;
        private static final int MAX_ENTRIES = 64;
        private final Map<Long, Cached> entries = new ConcurrentHashMap<>();

        void onPush(String proto, Map<String, Object> content) {
            if (proto == null || content == null) return;
            boolean situationProtocol = "notify_world_alliance_situation_init".equals(proto)
                    || "notify_world_alliance_situation_activity".equals(proto)
                    || "notify_world_alliance_situation_add".equals(proto)
                    || "notify_world_alliance_situation_update".equals(proto);
            // 网页版 BehemothAccountState.allianceSituationInit:184-190：
            // activity 全量替换；init 仅在 is_start=true 时替换（分页续传时不能清掉已收部分）。
            boolean replaceAll = "notify_world_alliance_situation_activity".equals(proto)
                    || ("notify_world_alliance_situation_init".equals(proto)
                        && isTruthy(unwrap(content).get("is_start")));
            if (replaceAll) entries.clear();
            if (!situationProtocol && !"notify_mass_march_add".equals(proto)) {
                return;
            }
            long now = System.currentTimeMillis();
            for (RallyCandidate candidate : parseRallyCandidates(content)) {
                if (candidate.masterMarchId <= 0) continue;
                entries.put(candidate.masterMarchId, new Cached(candidate, now));
            }
            if (entries.size() > MAX_ENTRIES) {
                Long oldestKey = null;
                long oldest = Long.MAX_VALUE;
                for (Map.Entry<Long, Cached> e : entries.entrySet()) {
                    if (e.getValue().savedAtMs < oldest) {
                        oldest = e.getValue().savedAtMs;
                        oldestKey = e.getKey();
                    }
                }
                if (oldestKey != null) entries.remove(oldestKey);
            }
        }

        List<RallyCandidate> candidates() {
            long now = System.currentTimeMillis();
            List<RallyCandidate> out = new ArrayList<>();
            for (Map.Entry<Long, Cached> e : entries.entrySet()) {
                if (now - e.getValue().savedAtMs > ENTRY_TTL_MS) {
                    entries.remove(e.getKey(), e.getValue());
                    continue;
                }
                out.add(e.getValue().candidate);
            }
            return out;
        }

        private static final class Cached {
            final RallyCandidate candidate;
            final long savedAtMs;

            Cached(RallyCandidate candidate, long savedAtMs) {
                this.candidate = candidate;
                this.savedAtMs = savedAtMs;
            }
        }
    }
}
