package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vault.wjdr.pet.PetSkillCatalog;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.formation.FormationCatalog;
import com.carlos.vaultserver.formation.FormationLibrary;
import com.carlos.vaultserver.formation.FormationResolver;
import com.carlos.vaultserver.formation.FormationSpec;
import com.carlos.vaultserver.formation.ResolvedFormation;

import org.json.JSONArray;
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
 *
 * <p><b>批次5（2026-10-23 今晚窗口增强）</b>：发车编队配置化（{@code hero_ids}/{@code squad_index}，缺省沿用队1）；
 * 5204 降一档准备时间立即重试 1 次、401/2878/403/415 记录后 60s 延时重试 1 次（均受窗口剩余约束；
 * 403/415 为 2026-11-06 生产排查补入）；
 * 发车尝试/成败/重试计划写日志与 {@code mod.bear.launch}，对账结果写 {@code mod.bear.join}（成员数/含自己）；
 * 20:50-21:00 每 60s 预热刷新目标/编队并写 {@code mod.bear.prepare}。幂等/未预约 SKIP/零钻石语义不变。
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
    static final int DEFAULT_PREPARE_TIME_INDEX = 1;
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
    /** 编队人数/配置超限（新号上阵位不足等；与 ServerFieldTasks.GATHER_ERR_FORMATION_LIMIT 同码）。 */
    static final int CODE_FORMATION_LIMIT = 415;
    static final int CODE_ALREADY_JOINED = 2844;
    static final int CODE_WORLD_MARCH_LIMIT = 2878;
    /** 发车可重试码（批次5）：401=兵/队列等参数不足（生产采集/狩猎同口径按“队列不足”处理）。 */
    static final int CODE_LAUNCH_RETRY_401 = 401;
    /** 5204 降档重试上限（1 次）；401/2878 延时重试上限（1 次）。 */
    static final int MAX_DOWNGRADE_RETRIES = 1;
    static final int MAX_DELAY_RETRIES = 1;
    /** 401/2878 延时重试间隔（秒）。 */
    static final long LAUNCH_RETRY_DELAY_SEC = 60L;
    /** 重试安全余量：窗口剩余须 ≥ 准备档秒数 + 该余量才允许重试。 */
    static final long LAUNCH_RETRY_MARGIN_SEC = 60L;
    /** 窗口剩余不足重试时写入幂等记录的内部码（非服务端码，仅用于 mod 可读性）。 */
    static final int CODE_RETRY_WINDOW_SHORT = -2;
    /** 开赛前预热（20:50-21:00）刷新目标/编队检查的间隔（秒）。 */
    static final long PREHEAT_REFRESH_SEC = 60L;
    /** 距开赛 ≤ 该值进入 60s 预热节拍（20 分钟，确保覆盖 20:50 准备窗入口）。 */
    static final long PREHEAT_LOOKAHEAD_SEC = 20L * 60L;
    /** 距开赛 ≤ 该值开始分段步进唤醒（2 小时），避免全天每 15 分钟醒一次。 */
    static final long PREHEAT_ARM_SEC = 2L * 3600L;
    /** 编队英雄上限（网页版 HeroFormationRules.MAX_HERO_COUNT=3）。 */
    static final int MAX_FORMATION_HEROES = 3;
    /** 英雄不在城（ServerGuardianLauncher.CODE_HERO_AWAY / ServerFieldTasks 采集同码）：换英雄组合重试用。 */
    static final int CODE_HERO_NOT_IN_CITY = 301;
    /** 巨熊 301 降级重试最大轮数（网页版语义：拉黑 301 英雄后换其余英雄组合，最多 2 轮）。 */
    static final int MAX_BEAR_301_ROUNDS = 2;
    /** 默认打熊队预设（无显式编队时服务端回退；2026-11-10 生产修复）。 */
    static final long DEFAULT_BEAR_LAUNCH_FORMATION_ID = 9100000003L;
    /** 默认熊上车1 预设起点（1-6 = +0..+5）。 */
    static final long DEFAULT_BEAR_RIDE_FIRST_ID = 9100000010L;
    /** 开赛前召回/暂停队列的提前量（秒）：用户 2026-11-07 口径 = 5 分钟。 */
    static final long PREP_RECALL_SECONDS = 300L;
    /** 编队士兵数超限（406）：取消兵量自适应后仍保留一次 60s 延时重试。 */
    static final int CODE_SOLDIER_LIMIT = 406;
    /** 打熊自动开启宠物战斗技能时排除：550051 泰坦巨鸟·锋锐铁喙（用户 2026-11-07 指定）。 */
    static final long BEAR_PET_SKILL_EXCLUDE = 550051L;
    /** 关闭原生自动上车：只关巨兽/死神（1/4），保留巨熊自己（3）。 */
    static final List<Long> BEAR_CLOSE_AUTOJOIN_IDS = java.util.Arrays.asList(1L, 4L);
    /** 开赛前可召回的本角色行军类型（侦查/进攻/狩猎/采集/驻防/集结类/巨兽/矿/情报；排除 99/411）。 */
    static final Set<Long> MARCH_RECALL_TYPES = Set.of(
            1L, 2L, 3L, 4L, 5L, 11L, 102L, 110L, 302L);
    /** 降级重试时自动编队取用的英雄数（巨熊集结一队 3 英雄）。 */
    static final int BEAR_DOWNGRADE_HERO_COUNT = 3;
    /** 状态键前缀（幂等/缓存）。 */
    static final String KEY_PREFIX = "srv:bear:";
    /** 预约同步节流：窗口附近 5 分钟、平时 6 小时。 */
    static final long SYNC_NEAR_WINDOW_SEC = 300L;
    static final long SYNC_IDLE_SEC = 6 * 3600L;
    /** 行军/集结候选缓存 TTL（内存，仅用于上车候选发现）。 */
    static final long RALLY_CACHE_TTL_SEC = 15 * 60L;
    /**
     * C3 业务态唤醒（网页版 TaskDecision.SleepUntil 语义）：
     * 已预约且窗口未开始 → 开赛前 60 秒唤醒（在最后 60 秒内则直接唤醒到开赛时刻）；
     * 窗口内已发车 → 30 秒后唤醒做上车/对账（不晚于窗口结束）。
     */
    static final long BEAR_WAKE_LEAD_SECONDS = 60L;
    /** 窗口内已发车后的上车/对账复查间隔（秒）。 */
    static final long BEAR_JOIN_RECHECK_SECONDS = 30L;

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
        // C3 业务态唤醒时刻（0=不覆盖节奏间隔）；窗口/发车状态确定后由 bearNextWakeAtMillis 计算。
        long nextWakeAt = 0L;
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
                // 核心资产库：陷阱响应回灌（reserve_time 权威；任务线程调用，串行于 session requestLock）
                try {
                    com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                            com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(tc.engine);
                    if (lib != null && rsp != null && rsp.getContent() != null) {
                        lib.applyResponse("req_alliance_trap_info", rsp.getContent());
                    }
                } catch (Throwable ignored) {
                }
            }
            TrapTimes pushed = trapTimesFromAssets(tc.engine);
            if (pushed == null) pushed = trapTimesFromNotifies(tc.engine);
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
            // 2026-11-08 修复：本场发车失败后 reserve 会翻到下一场（生产实测翻到两天后），
            // windowOf 判 WAITING → 60s 重试被 resetRetryIfNewWindow 清掉、任务被推迟到下一场，
            // 本场永远不再尝试（21:30 场 415 后重试丢失、cad 被推到 23:31）。
            // 本场（retryWindowStart）仍在活动期内时，强制按本场 ACTIVE 窗口继续重试。
            boolean alreadyLaunched = launchedForWindow(tc.store.getString(launchedKeyOf(tc)),
                    dayKey(nowSec), state.retryWindowStart);
            if (state.nextLaunchRetryAt > 0 && state.retryWindowStart > 0
                    && state.lastLaunchCode != CODE_TRAP_MASS_TIME_EXPIRED
                    && !alreadyLaunched
                    && nowSec >= state.retryWindowStart
                    && nowSec < state.retryWindowStart + ACTIVITY_MINUTES * 60L) {
                window = new Window(Window.ACTIVE, Window.SRC_RESERVE,
                        state.retryWindowStart - PREP_LEAD_MINUTES * 60L,
                        state.retryWindowStart,
                        state.retryWindowStart + ACTIVITY_MINUTES * 60L);
            }
            trapText = trapSummary(state, window, cfg.launchWithoutReserve);
            windowText = phaseText(window);
            // 批次5：新场次归一重试/预热台账（同场次保留预算，避免无限重试）
            resetRetryIfNewWindow(state, window.startSec, nowSec);
            saveState(tc, state);

            // 未读取到预约 + 走到配置周窗口：默认 SKIP（预约是游戏端行为，没有客户端预约协议，
            // 不臆造“本场会开”而空发车；确实要按配置窗口执行需显式 launch_without_reserve=true）。
            if (shouldSkipFallbackWindow(window, cfg)) {
                m.skip("未预约巨熊场次（无客户端预约协议，需游戏内手动预约），配置窗口内不发车");
                launchText = "未预约";
                writeMod(tc, trapText, windowText, launchText);
                return Exec.result(m, TASK_ID, TASK_NAME);
            }

            boolean launchedForWindowNow = false;
            if (window.phase == Window.ENDED) {
                BearGate.release(roleKeyOf(tc));
                m.skip("本场巨熊已结束" + syncNote);
                launchText = "本场已结束";
            } else if (window.phase == Window.WAITING) {
                m.skip("未在巨熊窗口 · " + trapText + syncNote);
                launchText = "未在窗口";
            } else if (window.phase == Window.PREPARING) {
                launchText = "准备窗口中";
                // 批次5：20:50-21:00 每 60s 预热一次（刷新目标 + 编队检查 + mod 状态），到点直接发车
                preheat(tc, m, cfg, state, nowSec, window);
                launchText = null;      // preheat 已写更细的 mod.bear.launch
            } else {
                // ---------- 3) ACTIVE：发车 + 上车 + 对账 ----------
                // 2026-11-07：窗口内保持让位门（准备窗错过/进程重启时兜底）；若 T-5 未执行，
                // 本轮补做召回/关闭自动上车/宠物技能（runPrepGate 幂等，已执行只刷新门）。
                runPrepGate(tc, m, cfg, state, nowSec, window);
                boolean launchedNow = false;
                // 对齐网页版 recallPending：召回部队未全部回城前不发车（否则必然 403 英雄占用）；
                // 15s 后复查，回城后同场次立即发车。
                java.util.List<Long> pendingRecall = pendingRecallIds(tc, state, window);
                if (!pendingRecall.isEmpty()) {
                    String waitText = "等待召回部队回城 " + pendingRecall.size()
                            + " 支，回城后立即发车：" + pendingRecall;
                    m.skip(waitText);
                    state.lastLaunchText = waitText;
                    writeLaunchMod(tc, state, waitText);
                    nextWakeAt = (nowSec + 15L) * 1000L;
                } else {
                    state.recallMarchIds = "";   // 已全部回城/无需等待
                    launchedNow = launchRally(tc, m, cfg, state, nowSec, window);
                }
                Window refreshed = windowOf(nowSec(tc), state.reserve, state.start, cfg);
                // 2026-11-09 审查修复：召回未回城期间整体等待（与网页版 recallPending 一致），
                // 避免上车/发车与召回行军抢英雄。
                if (pendingRecall.isEmpty()) {
                    joinRallies(tc, m, cfg, state, nowSec(tc), refreshed);
                }
                launchedForWindowNow = launchedNow || launchedForWindow(
                        tc.store.getString(launchedKeyOf(tc)), dayKey(nowSec), window.startSec);
                // 批次5：发车尝试/成败/重试计划由 launchRally 直接写 mod.bear.launch（含错误码原码），
                // 上车核对由 joinRallies 写 mod.bear.join；此处不再用粗文案覆盖。
                launchText = null;
            }
            writeMod(tc, trapText, windowText, launchText);
            // C3 业务态唤醒（网页版 TaskDecision.SleepUntil）：
            // 已预约→开赛前 60 秒；窗口内已发车→30 秒后做上车/对账；未预约/已结束→不覆盖节奏。
            // 落盘由 RoundRunner 在单任务模式下完成（整轮不写），并受 now..now+24h 边界约束。
            long baseWake = bearWakeAtMillis(nowSec(tc) * 1000L, window,
                    isValidEpoch(state.reserve), launchedForWindowNow, state.nextLaunchRetryAt);
            nextWakeAt = nextWakeAt > 0L
                    ? (baseWake > 0L ? Math.min(nextWakeAt, baseWake) : nextWakeAt)
                    : baseWake;
        } catch (Exception e) {
            try {
                writeMod(tc, trapText, windowText, "异常：" + ServerTasks.brief(String.valueOf(e.getMessage())));
            } catch (Throwable ignored) {
            }
            return ServerTasks.classifyException(e, TASK_NAME);
        }
        TaskOutcome out = Exec.result(m, TASK_ID, TASK_NAME);
        if (nextWakeAt > 0L) out.wakeAt(nextWakeAt);
        return out;
    }

    // ==================================================================================
    // 二、执行子流程（发车/上车/清场/目标发现）——网络交互，失败只 SKIP 不 ERROR 刷屏
    // ==================================================================================

    /**
     * 窗口内发起巨熊集结（type=411）。批次5 增强：编队配置化（hero_ids/squad_index）、
     * 5204 降档立即重试 1 次、401/2878 60s 后延时重试 1 次（受窗口剩余约束）、发车尝试/结果全量写日志与
     * {@code mod.bear.launch}（失败原因一眼可读）。
     *
     * <p>幂等：同“当日+场次”成功或封板后绝不发第二辆车；只重试“服务端明确拒绝”的码，
     * 结果未知（超时/空响应/无 marchId）保留“已尝试”标记——宁可漏发也不重复消耗（宁漏不花）。
     *
     * @return true=本次新发起成功
     */
    private static boolean launchRally(TaskContext tc, Exec.Multi m, BearConfig cfg,
                                       BearState state, long nowSec, Window window) throws Exception {
        String key = launchedKeyOf(tc);
        String day = dayKey(nowSec);
        String record = tc.store.getString(key);
        if (launchedForWindow(record, day, window.startSec)) {
            String prefix = record.contains("#code:") ? "本场已封板" : "本场已发起";
            String text = prefix + "（" + describeLaunched(record) + "），不重复发车";
            m.skip(text);
            writeLaunchMod(tc, state, text);
            return false;
        }
        if (!cfg.launchEnabled) {
            String text = "发起开关 launch_enabled=false，本场不发车";
            m.skip(text);
            writeLaunchMod(tc, state, text);
            return false;
        }
        // 延时重试等待闸（401/2878 计划 60s 后重试一次；其余时间不重复请求）
        if (state.nextLaunchRetryAt > nowSec) {
            long wait = state.nextLaunchRetryAt - nowSec;
            String text = "发车等待重试：还有 " + wait + "s（上次码" + state.lastLaunchCode + "）";
            m.skip(text);
            writeLaunchMod(tc, state, text);
            return false;
        }
        int prepareIndex = effectivePrepareIndex(cfg, state);
        long secondsToEnd0 = window.endSec - nowSec;
        if (!enoughTimeToLaunch(secondsToEnd0, prepareIndex)) {
            String text = "剩余 " + secondsToEnd0 + "s 不足以按准备档 " + prepareIndex
                    + "（" + prepareSeconds(prepareIndex) + "s）发起，本场不发车";
            m.skip(text);
            writeLaunchMod(tc, state, text);
            return false;
        }
        // 目标：先用缓存坐标复核（req_world_mapobj），失败再走完整发现链
        TrapTarget target = refreshTarget(tc, state);
        if (target == null) {
            StringBuilder why = new StringBuilder();
            target = discoverTarget(tc, why);
            if (target == null) {
                String text = "未找到联盟熊坑目标：" + (why.length() == 0 ? "联盟建筑/世界地图确认为空" : why);
                m.skip(text);
                writeLaunchMod(tc, state, text);
                return false;
            }
        }
        state.setTarget(target);
        BearFormationChoice initial = chooseLaunchFormation(tc, cfg);
        String formationSource = initial.label;
        Map<String, Object> formation = initial.payload;
        if (!formationReady(formation)) {
            String text = "无可用英雄/兵力编队（" + formationSource + "）：" + initial.reason;
            m.skip(text);
            saveState(tc, state);
            writeLaunchMod(tc, state, text);
            return false;
        }
        state.launchHeroes = appendHeroIds("", heroIdsOf(formation));
        state.launchPrepareIndexUsed = prepareIndex;
        saveState(tc, state);

        // 批次10：301（英雄不在城）降级重试预算 + 5204 降档预算（各自独立，总请求数受限，不重试风暴）
        int used301Rounds = 0;
        int used5204Downgrade = 0;
        boolean formationLimitDowngraded = false;
        boolean heroOccupiedDowngraded = false;
        java.util.Set<Long> blacklist301 = new java.util.LinkedHashSet<>();
        for (int attempt = 1; attempt <= 6; attempt++) {
            long now = nowSec(tc);
            long secondsToEnd = window.endSec - now;
            if (!enoughTimeToLaunch(secondsToEnd, prepareIndex)) {
                tc.store.putString(key, launchedFailedRecord(day, window.startSec, CODE_RETRY_WINDOW_SHORT));
                String text = launchFailureText(CODE_RETRY_WINDOW_SHORT, null);
                state.lastLaunchCode = CODE_RETRY_WINDOW_SHORT;
                saveState(tc, state);
                m.skip(text);
                writeLaunchMod(tc, state, text);
                return false;
            }
            Map<String, Object> payload = launchPayload(target, formation, prepareIndex);
            if (payload == null) {
                String text = "发起参数不完整（熊坑坐标/monsterid/编队缺失），未发起";
                m.skip(text);
                writeLaunchMod(tc, state, text);
                return false;
            }
            // 先落幂等标记（marchId=0=已尝试）再发请求：结果未知/异常时保留标记，绝不发第二辆车
            tc.store.putString(key, launchedRecord(day, window.startSec, 0L));
            state.launchPrepareIndexUsed = prepareIndex;
            state.lastLaunchCode = 0;
            String attemptText = "发车尝试" + attempt + "：" + launchAttemptText(
                    window.startSec, prepareIndex, formationSource, secondsToEnd);
            state.lastLaunchText = attemptText;
            saveState(tc, state);
            FileLog.info("巨熊发车尝试 fpid={} sid={} attempt={} {} 场次={}", tc.fpid,
                    String.valueOf(tc.sid), String.valueOf(attempt),
                    launchAttemptText(window.startSec, prepareIndex, formationSource, secondsToEnd),
                    day + "#" + window.startSec);
            GameMessage rsp = tc.engine.request("req_world_march", payload);
            int code = rsp == null ? -1 : Exec.codeOf(rsp);
            long marchId = Exec.marchId(rsp);
            if (marchId > 0) {
                tc.store.putString(key, launchedRecord(day, window.startSec, marchId));
                String text = "已发起 · marchId=" + marchId + " · prepare=" + prepareIndex
                        + "(" + prepareSeconds(prepareIndex) + "s) · 编队=" + formationSource
                        + " · 目标(" + target.x + "," + target.y + ")monster=" + target.monsterId;
                state.lastLaunchCode = 0;
                state.nextLaunchRetryAt = 0L;
                state.lastLaunchText = text;
                saveState(tc, state);
                m.claimed("发起巨熊集结(marchId=" + marchId + ")");
                writeLaunchMod(tc, state, text);
                FileLog.info("巨熊发车成功 fpid={} sid={} marchId={} target=({},{}) monsterid={} prepare={} 场次={}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(marchId),
                        String.valueOf(target.x), String.valueOf(target.y),
                        String.valueOf(target.monsterId), String.valueOf(prepareIndex),
                        day + "#" + window.startSec);
                return true;
            }
            // 响应 code=0 但无 marchId / 空响应：结果未知 → 保留“已尝试”标记，不重试不重复消耗
            if (code == 0 || code == -1) {
                state.lastLaunchCode = code;
                state.lastLaunchText = "发车结果未知(码" + code + ")，保留已尝试标记不重发，等待权威状态";
                saveState(tc, state);
                m.skip(state.lastLaunchText);
                writeLaunchMod(tc, state, state.lastLaunchText);
                FileLog.warn("巨熊发车结果未知 fpid={} sid={} code={} 场次={}", tc.fpid,
                        String.valueOf(tc.sid), String.valueOf(code), day + "#" + window.startSec);
                return false;
            }
            state.lastLaunchCode = code;
            // 批次10：301=英雄不在城 → 拉黑本编队英雄，换其余英雄组合重试（最多 2 轮）
            if (code == CODE_HERO_NOT_IN_CITY) {
                blacklist301 = blacklistHeroes(blacklist301, heroIdsOf(formation));
                if (!canRetry301(used301Rounds)) {
                    String text = "301(英雄不在城)降级 " + MAX_BEAR_301_ROUNDS
                            + " 轮仍失败，本场不再重试（避免风暴）；拉黑英雄 " + blacklist301;
                    tc.store.putString(key, launchedFailedRecord(day, window.startSec, code));
                    state.lastLaunchCode = code;
                    state.lastLaunchText = text;
                    saveState(tc, state);
                    m.skip(text);
                    writeLaunchMod(tc, state, text);
                    FileLog.warn("巨熊301降级耗尽 fpid={} sid={} 拉黑={} 场次={}", tc.fpid,
                            String.valueOf(tc.sid), String.valueOf(blacklist301),
                            day + "#" + window.startSec);
                    return false;
                }
                used301Rounds++;
                Map<String, Object> downgraded = downgrade301Formation(tc.engine, blacklist301);
                if (!formationReady(downgraded)) {
                    String text = "301(英雄不在城)降级失败：拉黑 " + blacklist301
                            + " 后无可用英雄组合，本场不再重试";
                    tc.store.putString(key, launchedFailedRecord(day, window.startSec, code));
                    state.lastLaunchCode = code;
                    state.lastLaunchText = text;
                    saveState(tc, state);
                    m.skip(text);
                    writeLaunchMod(tc, state, text);
                    FileLog.warn("巨熊301降级无英雄 fpid={} sid={} 拉黑={}", tc.fpid,
                            String.valueOf(tc.sid), String.valueOf(blacklist301));
                    return false;
                }
                formation = downgraded;
                formationSource = "301降级#" + used301Rounds + " 英雄=" + heroIdsOf(formation)
                        + "（拉黑" + blacklist301 + "）";
                state.launchHeroes = appendHeroIds("", heroIdsOf(formation));
                state.lastLaunchCode = code;
                state.lastLaunchText = bear301Text(used301Rounds, blacklist301);
                saveState(tc, state);
                m.skip(state.lastLaunchText + "（换英雄组合立即重试）");
                writeLaunchMod(tc, state, state.lastLaunchText);
                FileLog.info("巨熊301降级 fpid={} sid={} round={} 新英雄={} 拉黑={} 场次={}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(used301Rounds),
                        String.valueOf(heroIdsOf(formation)), String.valueOf(blacklist301),
                        day + "#" + window.startSec);
                continue;
            }
            // 2026-11-08 修复：415=编队人数/配置超限，60s 后用同一编队重试必然再 415。
            // 降级为自动编队（3 英雄，同 301 降级器）立即重试一次，仍失败再走延时/封板。
            // 2026-11-08：403=英雄已在其他编队/行军（准备窗召回后仍可能被驻防/采集队占用）：
            // 拉黑本队英雄 → 自动编队换 3 名未占用英雄立即重试一次（与 301 降级同器）。
            if (code == CODE_HERO_OCCUPIED && !heroOccupiedDowngraded) {
                heroOccupiedDowngraded = true;
                blacklist301 = blacklistHeroes(blacklist301, heroIdsOf(formation));
                Map<String, Object> auto403 = downgrade301Formation(tc.engine, blacklist301);
                if (formationReady(auto403)) {
                    formation = auto403;
                    formationSource = "403降级 自动编队 英雄=" + heroIdsOf(auto403)
                            + "（拉黑" + blacklist301 + "）";
                    state.launchHeroes = appendHeroIds("", heroIdsOf(formation));
                    state.nextLaunchRetryAt = 0L;
                    state.lastLaunchText = "403(英雄占用) → 自动编队换人立即重试";
                    saveState(tc, state);
                    m.skip(state.lastLaunchText);
                    writeLaunchMod(tc, state, state.lastLaunchText);
                    FileLog.info("巨熊403降级 fpid={} sid={} 新英雄={} 拉黑={} 场次={}", tc.fpid,
                            String.valueOf(tc.sid), String.valueOf(heroIdsOf(formation)),
                            String.valueOf(blacklist301), day + "#" + window.startSec);
                    continue;
                }
            }
            if (code == CODE_FORMATION_LIMIT && !formationLimitDowngraded) {
                formationLimitDowngraded = true;
                java.util.Set<Long> bl415 = blacklistHeroes(new java.util.LinkedHashSet<Long>(),
                        heroIdsOf(formation));
                Map<String, Object> auto = downgrade301Formation(tc.engine, bl415);
                if (formationReady(auto)) {
                    formation = auto;
                    formationSource = "415降级 自动编队 英雄=" + heroIdsOf(auto);
                    state.launchHeroes = appendHeroIds("", heroIdsOf(formation));
                    state.nextLaunchRetryAt = 0L;
                    state.lastLaunchText = "415(编队超限) → 自动编队立即重试";
                    saveState(tc, state);
                    m.skip(state.lastLaunchText);
                    writeLaunchMod(tc, state, state.lastLaunchText);
                    FileLog.info("巨熊415降级 fpid={} sid={} 新英雄={} 场次={}", tc.fpid,
                            String.valueOf(tc.sid), String.valueOf(heroIdsOf(formation)),
                            day + "#" + window.startSec);
                    continue;
                }
            }
            LaunchRetry retry = launchRetryDecision(code, now, window, cfg, state);
            if (retry.retry && retry.immediate && used5204Downgrade == 0) {
                // 5204 降一档：服务端明确拒绝（未发车/未消耗），本轮立即重试 1 次
                used5204Downgrade++;
                state.downgradeRetries++;
                prepareIndex = retry.nextPrepareIndex;
                state.launchPrepareIndexUsed = prepareIndex;
                state.lastLaunchText = launchFailureText(code, retry);
                saveState(tc, state);
                m.skip("首次发车失败(码" + code + ") → 降档 prepare=" + prepareIndex
                        + "(" + prepareSeconds(prepareIndex) + "s) 立即重试一次");
                writeLaunchMod(tc, state, state.lastLaunchText);
                continue;
            }
            // 明确被拒：清掉“已尝试”标记，按策略延时重试或直接封板（幂等记录保留到本场结束）
            tc.store.putString(key, "");
            if (retry.retry && retry.retryAtSec > now) {
                state.delayRetries++;
                state.nextLaunchRetryAt = retry.retryAtSec;
                state.lastLaunchText = launchFailureText(code, retry)
                        + " · 计划 " + Beijing.fmt("HH:mm:ss").format(new Date(retry.retryAtSec * 1000L)) + " 重试";
                saveState(tc, state);
                m.skip(state.lastLaunchText);
                writeLaunchMod(tc, state, state.lastLaunchText);
                FileLog.warn("巨熊发车被拒 fpid={} sid={} code={} 计划重试={} 场次={}", tc.fpid,
                        String.valueOf(tc.sid), String.valueOf(code), String.valueOf(retry.retryAtSec),
                        day + "#" + window.startSec);
                return false;
            }
            // 不重试：写失败记录封板（本场不再尝试，避免空耗）
            tc.store.putString(key, launchedFailedRecord(day, window.startSec, code));
            String text = launchFailureText(code, retry);
            state.lastLaunchText = text;
            saveState(tc, state);
            m.skip(text);
            writeLaunchMod(tc, state, text);
            FileLog.warn("巨熊发车失败 fpid={} sid={} code={} 场次={}", tc.fpid,
                    String.valueOf(tc.sid), String.valueOf(code), day + "#" + window.startSec);
            return false;
        }
        return false;
    }

    /**
     * 窗口内上车（type=99，默认 1 队、上限 6），并对账最多 1 次（req_world_mass_master_detail）。
     *
     * <p>批次11（join_formations 配置化）：
     * <ul>
     *   <li>每个启用槽先解析（{@link #selectJoinSlots}）：坏槽（编队不存在/英雄无效/兵量超库存/SKIP）
     *       记录「上车编队#id 跳过：原因」后<b>继续下一启用槽</b>；全部槽失败才结束本场上车；</li>
     *   <li>{@code enabled=false} 槽直接跳过（不发请求、不计失败，也不产生跳过失败记录）；</li>
     *   <li>每槽最多 1 次、每车头最多 1 次，解析全为本地计算（不连协议），不产生重试风暴。</li>
     * </ul>
     * 无 {@code join_formations} → 旧自动上车逻辑（客户端队）逐字节不变。
     */
    private static void joinRallies(TaskContext tc, Exec.Multi m, BearConfig cfg, BearState state,
                                    long nowSec, Window window) throws Exception {
        if (!cfg.joinEnabled) {
            m.skip("上车开关 join_enabled=false");
            writeJoinMod(tc, "未上车 · 开关关闭");
            return;
        }
        String key = joinsKeyOf(tc);
        String day = dayKey(nowSec);
        List<Long> joined = joinedMasters(tc.store.getString(key), day, window.startSec);
        // 2026-11-10：无 join_formations 时，服务端按库内熊上车1-6 生成默认槽（有则用，无则走旧自动）。
        List<JoinFormationConfig> joinSlots = cfg.joinFormations;
        boolean usingDefaultSlots = false;
        if (joinSlots.isEmpty()) {
            FormationLibrary defaultJoinLib = FormationResolver.libraryFor(tc, TASK_ID);
            joinSlots = defaultJoinSlots(defaultJoinLib, cfg);
            usingDefaultSlots = !joinSlots.isEmpty();
            if (usingDefaultSlots) {
                FileLog.info("巨熊上车使用默认模板槽 fpid={} sid={} count={}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(joinSlots.size()));
            }
        }
        long ownUid = tc.engine.lastUid();
        // 2026-11-11（对齐网页版 pendingJoins）：先对账上一轮“已请求未确认”的上车。
        int[] reconciled = reconcilePendingJoins(tc, state, m, window, ownUid);
        int joinLimit = Math.max(cfg.joinCount, usingDefaultSlots ? joinSlots.size() : 0);
        int queueCapacity = marchQueueCapacity(tc.engine);
        if (queueCapacity > 0 && joinLimit > queueCapacity) {
            FileLog.info("巨熊上车预算按队列容量夹取 fpid={} sid={} {} -> {}",
                    tc.fpid, String.valueOf(tc.sid), String.valueOf(joinLimit),
                    String.valueOf(queueCapacity));
            joinLimit = queueCapacity;
        }
        int remaining = joinLimit - joined.size();
        if (remaining <= 0) {
            // 仍先对账 pending，再按上限结束本轮
            if (reconciled[0] + reconciled[1] > 0) {
                writeJoinMod(tc, "本场上车已达上限 " + joined.size() + "/" + joinLimit
                        + "；对账成功" + reconciled[0] + " 失败" + reconciled[1]);
            } else {
                m.skip("本场上车已达配置上限 " + joinLimit + " 队");
                writeJoinMod(tc, "本场已上车 " + joined.size() + "/" + joinLimit + " 队（上限）");
            }
            return;
        }
        long allianceId = state.targetAllianceId;
        List<RallyCandidate> candidates = rallyCandidates(tc.engine, state.targetX, state.targetY,
                ownUid, allianceId, joined, nowSec);
        if (candidates.isEmpty()) {
            m.skip("未发现可加入的巨熊集结（等待联盟车头发车）");
            writeJoinMod(tc, "未上车 · 未发现可加入的巨熊集结（等待车头）");
            return;
        }
        CandidateSelection preferredSelection = selectPreferredCandidates(
                candidates, cfg, state, window, nowSec);
        if (!preferredSelection.waitMessage.isEmpty()) {
            m.skip(preferredSelection.waitMessage);
            writeJoinMod(tc, "未上车 · " + preferredSelection.waitMessage);
            saveState(tc, state);
            return;
        }
        candidates = preferredSelection.candidates;
        Set<Long> usedHeroes = new LinkedHashSet<>();
        usedHeroes.addAll(parseHeroIds(state.launchHeroes));
        usedHeroes.addAll(parseHeroIds(state.joinHeroes));
        Map<Long, List<Long>> squads = Exec.clientSquads(tc.engine);
        boolean detailQueried = false;
        boolean joinTextWritten = false;
        int tried = 0;

        // ---------- 批次11：join_formations 槽预解析（坏槽跳过继续；disabled 直接跳过） ----------
        List<JoinAttempt> configuredAttempts = null;
        if (!joinSlots.isEmpty()) {
            FormationLibrary joinLib = FormationResolver.libraryFor(tc, TASK_ID);
            final FormationLibrary lib = joinLib;
            final java.util.Map<Long, Long> reservedSoldiers = new LinkedHashMap<>();
            JoinSlotSelection selection = selectJoinSlots(joinSlots,
                    slot -> resolveJoinSlot(tc, lib, slot, reservedSoldiers));
            if (!selection.disabledIds.isEmpty()) {
                FileLog.info("巨熊上车 disabled 槽跳过 fpid={} sid={} ids={}（不发请求/不计失败）",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(selection.disabledIds));
            }
            for (int i = 0; i < selection.skippedSlots.size(); i++) {
                JoinFormationConfig slot = selection.skippedSlots.get(i);
                String note = selection.skippedNotes.get(i);
                m.skip(note);
                if (recordJoinSkipLogOnce(state, window.startSec, slot.id)) {
                    FileLog.info("巨熊上车槽跳过 fpid={} sid={} {}", tc.fpid,
                            String.valueOf(tc.sid), note);
                }
            }
            List<String> conflictNotes = new ArrayList<>();
            configuredAttempts = planJoinAttempts(selection, candidates, usedHeroes, remaining, conflictNotes);
            for (String note : conflictNotes) {
                m.skip(note);
                FileLog.info("巨熊上车槽冲突跳过 fpid={} sid={} {}", tc.fpid,
                        String.valueOf(tc.sid), note);
            }
            if (configuredAttempts.isEmpty()) {
                // 2026-11-10：编队槽全部不可用（如低等级角色预设无英雄）→ 回退旧自动编队，
                // 不再整场放弃上车；自动编队会按空闲英雄/客户端布阵尝试。
                StringBuilder summary = new StringBuilder("上车编队槽均不可用，回退自动编队");
                if (!selection.skippedNotes.isEmpty()) summary.append("（").append(selection.notesText()).append("）");
                m.skip(summary.toString());
                FileLog.info("巨熊上车回退自动 fpid={} sid={} {}", tc.fpid,
                        String.valueOf(tc.sid), summary);
                configuredAttempts = null;
            }
        }

        int attemptCount = configuredAttempts == null ? candidates.size() : configuredAttempts.size();
        for (int attemptIndex = 0; attemptIndex < attemptCount; attemptIndex++) {
            if (tried >= remaining) break;
            RallyCandidate candidate;
            Map<String, Object> formation;
            List<Long> heroes;
            String joinSource;
            if (configuredAttempts != null) {
                // 批次11：槽+车头已预配对（坏槽已跳过），直接使用
                JoinAttempt attempt = configuredAttempts.get(attemptIndex);
                candidate = attempt.candidate;
                formation = attempt.formation;
                heroes = heroIdsOf(formation);
                joinSource = attempt.label;
                // 批次11：同一轮内前序槽已成功上车的英雄，后序槽动态再查一次（计划时只查了初始占用）
                boolean conflictNow = false;
                for (Long heroId : heroes) {
                    if (heroId != null && usedHeroes.contains(heroId)) {
                        conflictNow = true;
                        break;
                    }
                }
                if (conflictNow) {
                    String note = "上车编队跳过：英雄已被本轮前序上车占用 " + heroes;
                    m.skip(note);
                    writeJoinMod(tc, "未上车 · " + note);
                    joinTextWritten = true;
                    continue;
                }
            } else {
                candidate = candidates.get(attemptIndex);
                heroes = chooseSquadHeroes(squads, usedHeroes);
                if (heroes.isEmpty()) {
                    m.skip("无空闲英雄编队可上车");
                    writeJoinMod(tc, "未上车 · 无空闲英雄编队（发起队/已上车队占用）");
                    joinTextWritten = true;
                    break;
                }
                formation = Exec.formationWithHeroes(tc.engine, heroes);
                if (!formationReady(formation)) {
                    m.skip("上车编队无可用兵力，停止上车");
                    writeJoinMod(tc, "未上车 · 上车编队无可用兵力");
                    joinTextWritten = true;
                    break;
                }
                joinSource = "旧自动上车（客户端队）";
            }
            Map<String, Object> payload = joinPayload(candidate.masterMarchId,
                    candidate.startX, candidate.startY, formation);
            if (payload == null) {
                m.skip("上车参数不完整（车头坐标/编队缺失）");
                writeJoinMod(tc, "未上车 · 参数不完整（车头坐标/编队缺失）");
                joinTextWritten = true;
                break;
            }
            // 先落台账（避免同一车头重复请求），再发请求
            tc.store.putString(key, joinedRecordAppend(tc.store.getString(key), day, window.startSec,
                    candidate.masterMarchId));
            GameMessage rsp;
            try {
                rsp = tc.engine.request("req_world_march", payload);
            } catch (Exception e) {
                // 2026-11-11：请求结果未知 → 记 pending，等待成员详情/通知对账，避免漏记/重复撞车。
                pendingJoinAppend(state, window.startSec, 0, candidate, heroes, nowSec);
                m.skip("上车请求异常，待对账（车头" + candidate.masterMarchId + "）");
                writeJoinMod(tc, "上车请求异常(车头=" + candidate.masterMarchId + ")，待对账");
                joinTextWritten = true;
                continue;
            }
            tried++;
            int code = rsp == null ? -1 : Exec.codeOf(rsp);
            if (code == 0) {
                boolean confirmed = responseConfirmsJoin(rsp.getContent(), candidate.masterMarchId);
                String detailText = "";
                if (!confirmed && !detailQueried) {
                    detailQueried = true;
                    GameMessage detailRsp = requestRallyDetail(tc, candidate);
                    if (detailRsp != null) {
                        detailText = joinDetailSummary(detailRsp.getContent(), ownUid);
                        int match = memberMatch(detailRsp.getContent(), ownUid, heroes);
                        confirmed = match == 1;
                        if (match == 2) detailText = detailText + " · 英雄与本次编队不符";
                    } else {
                        detailText = "对账请求未返回";
                    }
                    FileLog.info("巨熊对账 fpid={} sid={} masterMarchId={} {}", tc.fpid,
                            String.valueOf(tc.sid), String.valueOf(candidate.masterMarchId), detailText);
                }
                state.joinHeroes = appendHeroIds(state.joinHeroes, heroes);
                usedHeroes.addAll(heroes);
                if (confirmed) {
                    m.claimed("上车×1(车头" + candidate.masterMarchId + ")");
                    writeJoinMod(tc, "已上车 车头=" + candidate.masterMarchId
                            + " · " + joinSource
                            + (detailText.isEmpty() ? "" : " · " + detailText));
                    joinTextWritten = true;
                    FileLog.info("巨熊上车成功 fpid={} sid={} masterMarchId={} 车头=({},{}) 编队={}",
                            tc.fpid, String.valueOf(tc.sid), String.valueOf(candidate.masterMarchId),
                            String.valueOf(candidate.startX), String.valueOf(candidate.startY),
                            joinSource);
                } else {
                    pendingJoinAppend(state, window.startSec, 0, candidate, heroes, nowSec);
                    m.skip("上车请求已发(车头" + candidate.masterMarchId + ")，进入待对账");
                    writeJoinMod(tc, "上车已请求(车头=" + candidate.masterMarchId + ")，待对账"
                            + (detailText.isEmpty() ? "" : " · " + detailText));
                    joinTextWritten = true;
                }
                continue;
            }
            if (code == CODE_ALREADY_JOINED) {
                pendingJoinAppend(state, window.startSec, 0, candidate, heroes, nowSec);
                m.skip("当前账号已在目标集结或通知滞后（码2844），进入待对账");
                writeJoinMod(tc, "上车跳过(码2844) · 已在该集结，待对账");
                joinTextWritten = true;
                usedHeroes.addAll(heroes);
                continue;
            } else if (code == CODE_WORLD_MARCH_LIMIT) {
                m.skip("行军队列已满（码2878），停止上车");
                writeJoinMod(tc, "上车失败(码2878) · 行军队列已满");
                joinTextWritten = true;
                break;
            } else if (code == CODE_HERO_OCCUPIED) {
                // 2026-11-11：对齐网页版——403 记 pending 并继续下一车/下一槽，不整场放弃。
                pendingJoinAppend(state, window.startSec, 0, candidate, heroes, nowSec);
                m.skip("上车英雄被占用（码403），已记录待对账并换下一车");
                writeJoinMod(tc, "上车失败(码403) · 英雄被占用，待对账");
                joinTextWritten = true;
                usedHeroes.addAll(heroes);
                continue;
            } else if (code == CODE_TRAP_MASS_TIME_EXPIRED) {
                m.skip("巨熊集结已关闭（码5204）");
                writeJoinMod(tc, "上车失败(码5204) · 集结已关闭");
                joinTextWritten = true;
                break;
            } else {
                m.skip("上车失败（码" + code + "）");
                writeJoinMod(tc, "上车失败(码" + code + ")");
                joinTextWritten = true;
            }
        }
        if (!joinTextWritten) {
            if (reconciled[0] + reconciled[1] > 0) {
                writeJoinMod(tc, "上车对账：成功" + reconciled[0] + " 失败" + reconciled[1]);
            } else {
                writeJoinMod(tc, "未上车 · 本轮无动作（上限/候选/槽）");
            }
        } else if (configuredAttempts != null && !configuredAttempts.isEmpty()) {
            // 已写具体结果；补充“启用槽/跳过槽”摘要便于生产核对（不改成功文案主体）
            try {
                tc.saveModText(TASK_ID, "join_slots", "启用槽=" + enabledJoinFormationIds(joinSlots));
            } catch (Throwable ignored) {
            }
        }
        int pendingNow = pendingJoinCount(state, window.startSec);
        if (pendingNow > 0) {
            m.skip("本场上车待对账 " + pendingNow + " 支");
            if (!joinTextWritten) {
                writeJoinMod(tc, "上车待对账 " + pendingNow + " 支");
            }
        }
        saveState(tc, state);
    }

    /** 实时行军队列容量（资产库 queue.capacity；0=不可用）。 */
    static int marchQueueCapacity(WjdrEngine engine) {
        try {
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            AssetSnapshot.Queue q = lib == null ? null : lib.queue();
            return q == null ? 0 : q.capacity;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 实时行军队列快照标记（资产库 queue.updatedAtMs；0=不可用）。 */
    static long marchQueueUpdatedAt(WjdrEngine engine) {
        try {
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            AssetSnapshot.Queue q = lib == null ? null : lib.queue();
            return q == null ? 0L : q.updatedAtMs;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    // ==================== 2026-11-11：指定车头（对齐网页版 preferredLeaders） ====================

    /** 候选选择结果：waitMessage 非空=本轮等待指定车头，不发请求。 */
    static final class CandidateSelection {
        final List<RallyCandidate> candidates;
        final String waitMessage;

        CandidateSelection(List<RallyCandidate> candidates, String waitMessage) {
            this.candidates = candidates == null ? new ArrayList<RallyCandidate>() : candidates;
            this.waitMessage = waitMessage == null ? "" : waitMessage;
        }
    }

    /** 指定车头在列表中的优先级（越小越优先；未命中 MAX）。 */
    static int preferredLeaderIndex(RallyCandidate candidate, List<long[]> leaders) {
        if (candidate == null || leaders == null) return Integer.MAX_VALUE;
        for (int i = 0; i < leaders.size(); i++) {
            long[] l = leaders.get(i);
            if (l == null || l.length == 0) continue;
            if (candidate.ownerUid == l[0]
                    && (l.length < 2 || l[1] <= 0L || candidate.ownerKid == l[1])) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 按指定车头规则选候选（1:1 网页版 DailyBearMarchExecutor.selectCandidates:617-643）：
     * 未启用/等待 0 → 全部；仅指定 → 只返回指定车头或等待；
     * 指定车头存在 → 只返回指定车头；不存在 → 等待 N 秒后返回全部（指定车头优先）。
     */
    static CandidateSelection selectPreferredCandidates(List<RallyCandidate> candidates,
                                                        BearConfig cfg, BearState state,
                                                        Window window, long nowSec) {
        List<RallyCandidate> all = candidates == null
                ? new ArrayList<RallyCandidate>() : new ArrayList<>(candidates);
        if (cfg == null || !cfg.preferredLeaderEnabled || cfg.preferredLeaderWaitSeconds == 0) {
            resetPreferredWait(state);
            return new CandidateSelection(all, "");
        }
        if (all.isEmpty()) {
            resetPreferredWait(state);
            return new CandidateSelection(all, "");
        }
        List<RallyCandidate> preferred = new ArrayList<>();
        for (RallyCandidate c : all) {
            if (preferredLeaderIndex(c, cfg.preferredLeaders) < Integer.MAX_VALUE) preferred.add(c);
        }
        java.util.Collections.sort(preferred, (a, b) -> {
            int ia = preferredLeaderIndex(a, cfg.preferredLeaders);
            int ib = preferredLeaderIndex(b, cfg.preferredLeaders);
            if (ia != ib) return Integer.compare(ia, ib);
            return Long.compare(a.masterMarchId, b.masterMarchId);
        });
        if (cfg.preferredLeaderOnly) {
            resetPreferredWait(state);
            return preferred.isEmpty()
                    ? new CandidateSelection(new ArrayList<RallyCandidate>(),
                    "仅加入指定车头，等待有效巨熊集结")
                    : new CandidateSelection(preferred, "");
        }
        if (!preferred.isEmpty()) {
            resetPreferredWait(state);
            return new CandidateSelection(preferred, "");
        }
        String context = preferredLeaderWaitContext(candidates, cfg);
        long startedAt = state == null ? 0L : state.preferredWaitStartedAt;
        boolean changed = state == null
                || state.preferredWaitWindowStart != (window == null ? 0L : window.startSec)
                || startedAt <= 0L
                || !context.equals(state.preferredWaitContext);
        if (changed) {
            if (state != null) {
                state.preferredWaitWindowStart = window == null ? 0L : window.startSec;
                state.preferredWaitStartedAt = nowSec;
                state.preferredWaitContext = context;
            }
            startedAt = nowSec;
        }
        long elapsed = Math.max(0L, nowSec - startedAt);
        if (elapsed < cfg.preferredLeaderWaitSeconds) {
            long remaining = cfg.preferredLeaderWaitSeconds - elapsed;
            return new CandidateSelection(new ArrayList<RallyCandidate>(),
                    "等待指定车头发起巨熊集结，剩余 " + remaining + " 秒");
        }
        resetPreferredWait(state);
        // 等待超时：全部候选，指定车头优先（其余保持原顺序）。
        List<RallyCandidate> ordered = new ArrayList<>();
        ordered.addAll(preferred);
        for (RallyCandidate c : all) {
            if (!preferred.contains(c)) ordered.add(c);
        }
        return new CandidateSelection(ordered, "");
    }

    /** 等待上下文（车头列表 + 候选车头 id 集合），变化时重新计时。 */
    static String preferredLeaderWaitContext(List<RallyCandidate> candidates, BearConfig cfg) {
        StringBuilder leaders = new StringBuilder();
        if (cfg != null && cfg.preferredLeaders != null) {
            for (long[] l : cfg.preferredLeaders) {
                if (leaders.length() > 0) leaders.append(',');
                leaders.append(l == null || l.length == 0 ? "0" : l[0] + "@" + (l.length > 1 ? l[1] : 0L));
            }
        }
        List<Long> mids = new ArrayList<>();
        if (candidates != null) {
            for (RallyCandidate c : candidates) {
                if (c != null) mids.add(c.masterMarchId);
            }
        }
        java.util.Collections.sort(mids);
        return (cfg == null ? 0 : cfg.preferredLeaderWaitSeconds) + ":" + leaders + "|" + mids;
    }

    static void resetPreferredWait(BearState state) {
        if (state == null) return;
        state.preferredWaitWindowStart = 0L;
        state.preferredWaitStartedAt = 0L;
        state.preferredWaitContext = "";
    }

    // ==================== 2026-11-11：待对账上车（对齐网页版 pendingJoins） ====================

    /** 当前场次待对账数组（跨场次自动清空）。 */
    static JSONArray pendingJoinArray(BearState state, long windowStartSec) {
        if (state == null) return new JSONArray();
        if (state.pendingJoinWindowStart != windowStartSec) {
            state.pendingJoins = "";
            state.pendingJoinWindowStart = windowStartSec;
            return new JSONArray();
        }
        try {
            return new JSONArray(state.pendingJoins == null || state.pendingJoins.isEmpty()
                    ? "[]" : state.pendingJoins);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    static int pendingJoinCount(BearState state, long windowStartSec) {
        return pendingJoinArray(state, windowStartSec).length();
    }

    static boolean pendingJoinContains(BearState state, long windowStartSec, long masterMarchId) {
        JSONArray arr = pendingJoinArray(state, windowStartSec);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("mid", 0L) == masterMarchId) return true;
        }
        return false;
    }

    /** 记录一条“已请求、结果未确认”的上车；同车头去重。 */
    static void pendingJoinAppend(BearState state, long windowStartSec, int formationId,
                                  RallyCandidate candidate, List<Long> heroes, long nowSec) {
        if (state == null || candidate == null || candidate.masterMarchId <= 0L) return;
        JSONArray arr = pendingJoinArray(state, windowStartSec);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("mid", 0L) == candidate.masterMarchId) return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("fid", formationId);
            o.put("mid", candidate.masterMarchId);
            o.put("owner", candidate.ownerUid);
            o.put("kid", candidate.ownerKid);
            o.put("heroes", joinIds(heroes));
            o.put("at", nowSec);
            o.put("tries", 0);
            arr.put(o);
            state.pendingJoins = arr.toString();
            state.pendingJoinWindowStart = windowStartSec;
            // 待对账队伍的英雄同样视为占用，防止本轮/下轮重复使用（对齐网页版 protectedHeroes）。
            state.joinHeroes = appendHeroIds(state.joinHeroes, heroes);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 对账上一轮 pending 上车（网页版 reconcilePendingJoins）：
     * 最多查 2 条/轮；成员详情确认 → 成功；明确不在成员里 → 失败释放；
     * 查询失败 → 保留（tries+1）；超时 180s 或尝试 3 次 → 释放并记失败。
     */
    static int[] reconcilePendingJoins(TaskContext tc, BearState state, Exec.Multi m,
                                       Window window, long ownUid) {
        JSONArray arr = pendingJoinArray(state, window.startSec);
        if (arr.length() == 0) return new int[]{0, 0};
        long now = nowSec(tc);
        JSONArray keep = new JSONArray();
        int ok = 0;
        int fail = 0;
        int checked = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long mid = o.optLong("mid", 0L);
            long owner = o.optLong("owner", 0L);
            long kid = o.optLong("kid", 0L);
            int tries = o.optInt("tries", 0);
            // 2026-11-11 审查修复：pending 保留到本场结束/明确失败为止，不用固定 180s 过期，
            // 避免“实际已上车但确认晚到”被误判成失败（网页版同样只按成员详情/场次清理）。
            if (checked >= 2) {
                keep.put(o);
                continue;
            }
            GameMessage rsp = requestRallyDetailById(tc, mid, owner, kid);
            checked++;
            if (rsp == null) {
                try {
                    o.put("tries", tries + 1);
                } catch (Throwable ignored) {
                }
                keep.put(o);
                continue;
            }
            java.util.Set<Long> expectedHeroes = parseIds(o.optString("heroes"));
            int match = memberMatch(rsp.getContent(), ownUid, expectedHeroes);
            if (match == 1) {
                ok++;
                if (m != null) m.claimed("上车对账成功(车头" + mid + ")");
                FileLog.info("巨熊上车对账成功 fpid={} sid={} masterMarchId={}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(mid));
            } else {
                fail++;
                if (m != null) m.skip("上车对账" + (match == 2 ? "英雄不符" : "未确认")
                        + "(车头" + mid + ")，已释放");
                FileLog.info("巨熊上车对账未确认 fpid={} sid={} masterMarchId={} match={}",
                        tc.fpid, String.valueOf(tc.sid), String.valueOf(mid),
                        String.valueOf(match));
            }
        }
        state.pendingJoins = keep.toString();
        state.pendingJoinWindowStart = window.startSec;
        saveState(tc, state);
        return new int[]{ok, fail};
    }

    /** 按 pending 记录查询主集结成员详情（异常/超时返回 null）。 */
    static GameMessage requestRallyDetailById(TaskContext tc, long masterMarchId, long owner, long kid) {
        try {
            return tc.engine.request("req_world_mass_master_detail", Exec.args(
                    "id", masterMarchId, "owner", owner, "kid", kid));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 生产上车槽解析器：功能级/槽级 soldiers 合并后调用 FormationResolver（坏槽由 selectJoinSlots 收集原因）。 */
    static JoinSlotPlan resolveJoinSlot(TaskContext tc, FormationLibrary lib, JoinFormationConfig slot) {
        return resolveJoinSlot(tc, lib, slot, null);
    }

    /** 带同轮已预留兵量的槽解析：解析后按库存-预留夹取，并把本槽实际兵量累加回 reservedSoldiers。 */
    static JoinSlotPlan resolveJoinSlot(TaskContext tc, FormationLibrary lib, JoinFormationConfig slot,
                                        java.util.Map<Long, Long> reservedSoldiers) {
        Map<String, Object> params = mergeSoldiersForResolve(slot.params, lib);
        FormationResolver.ResolveOutcome out = FormationResolver.resolveOutcome(tc.engine, lib, params);
        if (!out.ready()) {
            return new JoinSlotPlan(slot, null, "", out.reason);
        }
        Map<String, Object> payload = out.formation.toFormationPayload();
        if (payload == null) {
            return new JoinSlotPlan(slot, null, "", "编队英雄/兵种为空");
        }
        String reserveError = applyReservedSoldiers(tc, payload, reservedSoldiers);
        if (reserveError != null) {
            return new JoinSlotPlan(slot, null, "", reserveError);
        }
        return new JoinSlotPlan(slot, payload, out.formation.describe(), "");
    }

    /**
     * 同轮多上车队兵量预留（对齐网页版 reservedSoldiers）：
     * 按兵种库存减去已预留量夹取本队 soldier pairs；返回非空=预留后无兵可用。
     */
    static String applyReservedSoldiers(TaskContext tc, Map<String, Object> payload,
                                        java.util.Map<Long, Long> reservedSoldiers) {
        if (payload == null || reservedSoldiers == null) return null;
        long[] stock;
        try {
            stock = FormationCatalog.soldierStock(tc.engine);
        } catch (Throwable t) {
            return null;   // 库存不可用：保持原编队（旧行为）
        }
        if (stock == null || stock[0] + stock[1] + stock[2] <= 0L) return null;
        List<Map<String, Object>> pairs = Exec.mapsOf(payload.get("soldier"));
        List<Map<String, Object>> out = new ArrayList<>();
        long kept = 0L;
        for (Map<String, Object> pair : pairs) {
            long k = Exec.longOf(pair.get("k"));
            long v = Exec.longOf(pair.get("v"));
            int idx = soldierTypeIndex(k);
            if (idx >= 0) {
                long already = reservedSoldiers.getOrDefault(k, 0L);
                long remain = Math.max(0L, stock[idx] - already);
                if (v > remain) v = remain;
            }
            if (v <= 0L) continue;
            Map<String, Object> copy = new LinkedHashMap<>(pair);
            copy.put("v", v);
            out.add(copy);
            kept += v;
            reservedSoldiers.merge(k, v, Long::sum);
        }
        if (kept <= 0L) return "同轮上车兵量预留后无可用兵力";
        payload.put("soldier", out);
        return null;
    }

    /** 兵种 k → 库存下标（11000/11100→0 盾、21000→1 矛、31000/31100→2 弓；其它 -1）。 */
    static int soldierTypeIndex(long k) {
        if (k == 11000L || k == 11100L) return 0;
        if (k == 21000L || k == 21100L) return 1;
        if (k == 31000L || k == 31100L) return 2;
        return -1;
    }

    /**
     * 开赛前预热（批次5：20:50-21:00 的 PREPARING 分支）：
     * 每 {@value #PREHEAT_REFRESH_SEC}s 刷新一次熊坑目标并检查发车编队，写 mod.bear.prepare / mod.bear.launch，
     * 避免 21:00 开赛瞬间因目标/编队数据未就绪而错过发车（失败原因写清“目标未就绪/编队不可用”）。
     */
    private static void preheat(TaskContext tc, Exec.Multi m, BearConfig cfg, BearState state,
                                long nowSec, Window window) {
        boolean due = preheatRefreshDue(nowSec, state.preheatRefreshAt);
        if (due) {
            state.preheatRefreshAt = nowSec;
            StringBuilder why = new StringBuilder();
            TrapTarget target = resolveTarget(tc, state, why);
            if (target != null) state.setTarget(target);
            saveState(tc, state);
        }
        boolean targetReady = targetUsable(state);
        BearFormationChoice choice = chooseLaunchFormation(tc, cfg);
        String formationSource = choice.label;
        boolean formationOk = formationReady(choice.payload);
        long secondsToStart = Math.max(0L, window.startSec - nowSec);
        String summary = preheatSummary(targetReady, state.targetX, state.targetY,
                state.targetMonsterId, formationOk, formationSource, secondsToStart);
        try {
            tc.saveModText(TASK_ID, "prepare", summary);
        } catch (Throwable ignored) {
        }
        if (due) {
            FileLog.info("巨熊预热 fpid={} sid={} {}", tc.fpid, String.valueOf(tc.sid), summary);
        }
        writeLaunchMod(tc, state, (targetReady && formationOk ? "预热就绪" : "预热未就绪") + " · " + summary);
        saveState(tc, state);
        if (!formationOk) {
            m.skip("巨熊预热：无可用英雄/兵力编队（" + formationSource + "）"
                    + (choice.reason.isEmpty() ? "，到点将无法发车" : "：" + choice.reason));
        } else if (!targetReady) {
            m.skip(summary + " · 目标未就绪（每 60s 重试）");
        } else {
            m.skip(summary);
        }
        if (cfg.prepareRecallEnabled) {
            int recalled = recallOrdinaryMarches(tc);
            if (recalled > 0) m.claimed("准备窗清场：撤回普通行军×" + recalled);
        }
        // 2026-11-07：开赛前 5 分钟召回全部队伍 + 暂停队列任务（每场一次）
        runPrepGate(tc, m, cfg, state, nowSec, window);
    }

    /**
     * 开赛前 5 分钟动作（2026-11-07，每场一次）：
     * 1) 召回本角色全部在途/驻扎行军；2) 关闭巨兽/死神原生自动上车；3) 开启巨熊窗口让位门；
     * 4) 若开启 {@code pet_skills}，自动使用全部战斗型宠物技能（排除 550051）。
     */
    private static void runPrepGate(TaskContext tc, Exec.Multi m, BearConfig cfg, BearState state,
                                    long nowSec, Window window) {
        if (!prepGateDue(window.startSec, nowSec, state.prepGateWindowStart)) {
            if (window.startSec - nowSec <= PREP_RECALL_SECONDS) {
                BearGate.enter(roleKeyOf(tc), window.endSec * 1000L);
            }
            return;
        }
        BearGate.enter(roleKeyOf(tc), window.endSec * 1000L);
        state.prepGateWindowStart = window.startSec;
        java.util.List<Long> recalledIds = cfg.prepRecall
                ? recallAllMarches(tc) : java.util.Collections.<Long>emptyList();
        int recalled = recalledIds.size();
        state.recallSnapshotAt = marchQueueUpdatedAt(tc.engine);
        // 记录本场召回的行军 id：全部回城前不发车（对齐网页版"等待部队返回确认"）。
        state.recallWindowStart = window.startSec;
        state.recallMarchIds = joinIds(recalledIds);
        boolean autoJoinClosed = closeBehemothAutoJoin(tc);
        ensurePetSkills(tc, cfg, state, m, window);
        saveState(tc, state);
        String text = "巨熊准备（T-5）：召回行军×" + recalled
                + (autoJoinClosed ? " · 已暂停巨兽/死神自动上车" : "")
                + " · 队列任务让位至本场结束";
        FileLog.info("巨熊准备窗清场 fpid={} sid={} {}", tc.fpid, String.valueOf(tc.sid), text);
        m.claimed(text);
    }

    /** 纯函数：是否到达 T-5 且本场尚未执行（便于 JVM 单测）。 */
    static boolean prepGateDue(long startSec, long nowSec, long doneWindowStart) {
        return startSec - nowSec <= PREP_RECALL_SECONDS && doneWindowStart != startSec;
    }

    /** 召回本角色全部在途/驻扎行军（state=2→recall；其余→off_work；排除 99/411）。
     *
     * <p>2026-11-10 生产修复：只使用<b>实时资产库</b>行军列表；返回 2806（行军不存在）的行军
     * 立即从等待清单剔除，避免“等一个不存在的旧行军回城”卡死整场发车。
     */
    static java.util.List<Long> recallAllMarches(TaskContext tc) {
        long now = nowSec(tc);
        List<long[]> targets = liveMarchTargets(tc.engine, now);
        java.util.List<Long> done = new java.util.ArrayList<Long>();
        for (long[] t : targets) {
            long id = t[0];
            long stateVal = t[1];
            String proto = autoRecallProto(t[2], stateVal);
            if (proto.isEmpty()) {
                // 2026-11-11 对齐网页版：state=2 需消耗召回道具、rally/返程/待命不自动召回。
                FileLog.info("巨熊准备窗召回跳过 fpid={} marchId={} type={} state={}（不可自动召回）",
                        tc.fpid, String.valueOf(id), String.valueOf(t[2]), String.valueOf(stateVal));
                continue;
            }
            try {
                GameMessage rsp = tc.engine.request(proto, Exec.args("id", id));
                int code = Exec.codeOf(rsp);
                if (code == 2806) {
                    FileLog.info("巨熊准备窗召回跳过 fpid={} marchId={}：行军不存在（码2806）",
                            tc.fpid, String.valueOf(id));
                    continue;
                }
                if (code != 0) {
                    FileLog.warn("巨熊准备窗召回未成功 fpid={} marchId={} 码={}", tc.fpid,
                            String.valueOf(id), String.valueOf(code));
                }
                // 成功/暂时失败都等待其从实时列表消失；2806 已跳过，不会永久等待。
                done.add(id);
            } catch (Exception e) {
                done.add(id);
                FileLog.warn("巨熊准备窗召回失败 fpid={} marchId={} proto={}：{}", tc.fpid,
                        String.valueOf(id), proto, ServerTasks.brief(String.valueOf(e.getMessage())));
            }
        }
        return done;
    }

    /**
     * 自动召回协议（对齐网页版 recallDecision/automaticRecallable）：
     * state=3（驻扎/采集）→ off_work；state=2（行军中）需召回道具，不自动化；rally/1/4/其它 → 空。
     * 返回空串表示“不可自动召回”，调用方跳过。
     */
    static String autoRecallProto(long type, long state) {
        if (type == 99L || type == 411L) return "";
        if (state == 3L) return "req_world_march_off_work";
        return "";
    }

    /** 召回等待上限（秒）：行军迟迟不回城时放弃等待，避免整场不发车。 */
    static final long RECALL_WAIT_MAX_SECONDS = 120L;

    /** 本场召回的仍"在途"行军 id（空=全部回城，可发车）。对齐网页版 recallPending。 */
    static java.util.List<Long> pendingRecallIds(TaskContext tc, BearState state, Window window) {
        java.util.List<Long> out = new java.util.ArrayList<Long>();
        if (tc == null || state == null || window == null) return out;
        if (state.recallWindowStart <= 0L || state.recallWindowStart != window.startSec) return out;
        long now = nowSec(tc);
        // 2026-11-10：开赛后不再等待（用户口径：T-0 必须发车）；防止旧行军 id 拖死整场。
        if (now >= window.startSec) {
            state.recallMarchIds = "";
            state.recallWaitStart = 0L;
            return out;
        }
        if (state.recallWaitStart <= 0L) {
            state.recallWaitStart = now;
        } else if (now - state.recallWaitStart >= RECALL_WAIT_MAX_SECONDS) {
            state.recallMarchIds = "";   // 等待超时：放弃等待，按现有编队发车
            state.recallWaitStart = 0L;
            return out;
        }
        java.util.Set<Long> recalled = parseIds(state.recallMarchIds);
        if (recalled.isEmpty()) return out;
        long currentMarker = marchQueueUpdatedAt(tc.engine);
        // 快照未推进时，不能把“列表里没有”当成已回城（可能只是还没收到新快照）；继续等到 T-0/120s。
        if (state.recallSnapshotAt > 0L && currentMarker > 0L
                && currentMarker <= state.recallSnapshotAt) {
            return new java.util.ArrayList<Long>(recalled);
        }
        java.util.Set<Long> onWay = new java.util.LinkedHashSet<Long>();
        for (long[] t : liveMarchTargets(tc.engine, now)) onWay.add(t[0]);
        for (Long id : recalled) {
            if (id != null && onWay.contains(id)) out.add(id);
        }
        // 等待中的 id 已全部消失 → 视为回城，清空等待台账
        if (out.isEmpty()) {
            state.recallMarchIds = "";
            state.recallWaitStart = 0L;
        }
        return out;
    }

    /** 逗号 id 列表解析（非法/≤0 忽略）。 */
    static java.util.Set<Long> parseIds(String csv) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<Long>();
        if (csv == null || csv.isEmpty()) return out;
        for (String s : csv.split(",")) {
            try {
                long v = Long.parseLong(s.trim());
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** id 集合 → 逗号串。 */
    static String joinIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null || id <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 本角色当前行军 [id,state,type]（实时资产库优先；库不可用才回退旧推动快照）。
     *
     * <p>2026-11-10 生产修复：旧实现只读 init/add 的**累积**快照，包含已结束行军，
     * 召回全部返回 2806，进而卡死“等待回城”。资产库 queue.marches 是实时全量列表。
     */
    static List<long[]> liveMarchTargets(WjdrEngine engine, long nowSec) {
        List<long[]> out = new ArrayList<>();
        if (engine == null) return out;
        try {
            CoreAssetLibrary lib = CoreAssetRegistry.global().get(engine);
            AssetSnapshot.Queue q = lib == null ? null : lib.queue();
            if (q != null && q.updatedAtMs > 0L) {
                for (AssetSnapshot.QueueMarch m : q.marches) {
                    if (m == null || m.id <= 0) continue;
                    if (!MARCH_RECALL_TYPES.contains(Long.valueOf(m.type))) continue;
                    if (m.endTime > 0L && m.endTime < nowSec - 60L) continue;   // 已过期残影
                    out.add(new long[]{m.id, m.state, m.type});
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return ownMarchTargets(engine);
    }

    /** 旧快照兜底（资产库不可用时）：累积 init/add 快照 [id,state,type]。 */
    static List<long[]> ownMarchTargets(WjdrEngine engine) {
        List<long[]> out = new ArrayList<>();
        if (engine == null) return out;
        for (String proto : new String[]{"notify_world_player_init", "notify_player_march_add"}) {
            try {
                collectOwnMarchTargets(engine.notifyContent(proto), out, 0);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void collectOwnMarchTargets(Object node, List<long[]> out, int depth) {
        if (node == null || depth > 4 || out.size() >= 64) return;
        if (node instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) node;
            long id = Exec.longOf(map.get("id"));
            long type = Exec.longOf(map.get("type"));
            if (id > 0 && MARCH_RECALL_TYPES.contains(Long.valueOf(type))) {
                boolean seen = false;
                for (long[] e : out) {
                    if (e[0] == id) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) out.add(new long[]{id, Exec.longOf(map.get("state")), type});
                return;
            }
            for (Object value : map.values()) collectOwnMarchTargets(value, out, depth + 1);
        } else if (node instanceof List) {
            for (Object value : (List<?>) node) collectOwnMarchTargets(value, out, depth + 1);
        }
    }

    /** 关闭巨兽/死神原生自动上车（按 behemoth.auto_join_ids，排除巨熊自己 3）；失败仅日志不阻塞。 */
    private static boolean closeBehemothAutoJoin(TaskContext tc) {
        try {
            List<Long> ids = new ArrayList<>();
            try {
                String raw = tc.params("behemoth").optString("auto_join_ids", "1,4,3");
                for (String part : raw.split(",")) {
                    try {
                        long id = Long.parseLong(part.trim());
                        if (id > 0 && id != 3L && !ids.contains(id)) ids.add(id);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            if (ids.isEmpty()) ids.addAll(BEAR_CLOSE_AUTOJOIN_IDS);
            GameMessage rsp = tc.engine.request("req_alliance_activity_auto_join_close",
                    Exec.args("auto_mass_id_list", ids));
            return Exec.ok(rsp);
        } catch (Throwable t) {
            FileLog.warn("巨熊准备窗关闭自动上车失败 fpid={}：{}", tc.fpid,
                    ServerTasks.brief(String.valueOf(t.getMessage())));
            return false;
        }
    }

    /** 本场开启宠物战斗技能（pet_skills=true；排除 550051；同场只做一次）。 */
    private static void ensurePetSkills(TaskContext tc, BearConfig cfg, BearState state,
                                        Exec.Multi m, Window window) {
        if (!cfg.petSkillsEnabled || state.petSkillsWindowStart == window.startSec) return;
        state.petSkillsWindowStart = window.startSec;
        long[] ids = bearPetSkillIds();
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        for (long id : ids) {
            int code = usePetSkill(tc, id);
            if (code == 0) ok++;
            else if (code == 11536 || code == 11537 || code == 11539 || code == 11542) skipped++;
            else failed++;
        }
        String text = "宠物战斗技能：已开启 " + ok + "/" + ids.length
                + (skipped > 0 ? "，跳过 " + skipped : "")
                + (failed > 0 ? "，失败 " + failed : "");
        FileLog.info("巨熊宠物技能 fpid={} sid={} {}", tc.fpid, String.valueOf(tc.sid), text);
        try {
            tc.saveModText(TASK_ID, "pet_skills", text);
        } catch (Throwable ignored) {
        }
        if (m != null) m.claimed(text);
    }

    /** 打熊自动开启的技能集合：战斗类全部，排除 550051（锋锐铁喙）。 */
    static long[] bearPetSkillIds() {
        long[] all = PetSkillCatalog.combatIds();
        List<Long> out = new ArrayList<>();
        for (long id : all) {
            if (id != BEAR_PET_SKILL_EXCLUDE) out.add(id);
        }
        long[] ids = new long[out.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = out.get(i);
        return ids;
    }

    /** 单技能请求（失败返回码；异常 -1）。 */
    static int usePetSkill(TaskContext tc, long skillId) {
        try {
            List<Object> one = new ArrayList<>();
            one.add(skillId);
            GameMessage r = tc.engine.request("req_pet_use_skill", Exec.args("skill_id_lst", one));
            return r == null ? -1 : Exec.codeOf(r);
        } catch (Throwable t) {
            return -1;
        }
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

    /**
     * C3 业务态唤醒（纯函数，网页版 {@code TaskDecision.SleepUntil} 语义）：
     * 巨熊任务下一次该跑的时刻（epoch millis；0=不覆盖 15s 节奏间隔）。
     *
     * <ul>
     *   <li>已预约（{@code reserve_time} 有效）且窗口未开始（WAITING/PREPARING）→ 开赛前 60 秒；
     *       若当前已进入最后 60 秒 → 直接睡到开赛时刻（发车点）；</li>
     *   <li>窗口内（ACTIVE）且已发车 → {@code min(now+30s, 窗口结束)}（上车/对账复查）；</li>
     *   <li>未预约 / 已结束 / 其它 → 0（交给固定 15s 节奏兜底，未预约不发车）。</li>
     * </ul>
     *
     * <p>返回值仍受 {@link RoundRunner#acceptNextWakeAt} 的 {@code now..now+24h} 边界约束：
     * 下一场在 24 小时之外时不会落盘，节奏继续按 15s 探测（预约同步本身有 5min/6h 节流）。
     */
    static long bearNextWakeAtMillis(long nowMillis, Window window, boolean reserved, boolean launched) {
        if (window == null || !reserved) return 0L;      // 未预约：不臆造窗口/不发车，交给节奏兜底
        long nowSec = nowMillis / 1000L;
        if (window.phase == Window.WAITING || window.phase == Window.PREPARING) {
            long candidate = nowSec + BEAR_WAKE_LEAD_SECONDS < window.startSec
                    ? window.startSec - BEAR_WAKE_LEAD_SECONDS   // 开赛前 60 秒
                    : window.startSec;                            // 已进入最后 60 秒 → 开赛时刻
            return candidate > nowSec ? candidate * 1000L : 0L;
        }
        if (window.phase == Window.ACTIVE && launched) {
            long candidate = Math.min(nowSec + BEAR_JOIN_RECHECK_SECONDS,
                    Math.max(nowSec + 1L, window.endSec));
            return candidate > nowSec ? candidate * 1000L : 0L;
        }
        return 0L;                                        // ENDED / 窗口内未发车：交给 15s 节奏
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
            long id = pair == null ? Exec.longOf(raw)
                    : (Exec.longOf(pair.get("v")) > 0L ? Exec.longOf(pair.get("v"))
                    : Exec.longOf(pair.get("k")));
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

    // ---------- 5.1 发车编队配置（批次5，纯函数） ----------

    /**
     * hero_ids 参数解析（纯函数）：支持 JSONArray / List / {@code "1,2,3"} 字符串；
     * 只取正数、去重、最多 {@value #MAX_FORMATION_HEROES} 名（网页版 HeroFormationRules.MAX_HERO_COUNT=3）。
     */
    static List<Long> parseConfiguredHeroIds(JSONObject params) {
        List<Long> out = new ArrayList<>();
        if (params == null || !params.has("hero_ids")) return out;
        Object raw = params.opt("hero_ids");
        if (raw instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) raw;
            for (int i = 0; i < arr.length() && out.size() < MAX_FORMATION_HEROES; i++) {
                long id = Exec.longOf(arr.opt(i));
                if (id > 0) out.add(id);
            }
        } else if (raw instanceof List) {
            for (Object value : (List<?>) raw) {
                long id = Exec.longOf(value);
                if (id > 0 && out.size() < MAX_FORMATION_HEROES) out.add(id);
            }
        } else {
            for (String part : String.valueOf(raw).split("[,;\\s]+")) {
                long id = Exec.longOf(part);
                if (id > 0 && out.size() < MAX_FORMATION_HEROES) out.add(id);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /**
     * 解析指定车头 {@code preferred_leaders}（2026-11-11，对齐网页版 preferredLeaders）：
     * 支持 "uid@kid,uid@kid" 字符串、JSONArray（元素为字符串或 {uid,kid} 对象）。
     */
    static List<long[]> parsePreferredLeaders(JSONObject p) {
        List<long[]> out = new ArrayList<>();
        if (p == null) return out;
        Object raw = p.opt("preferred_leaders");
        if (raw == null) raw = p.opt("preferredLeaders");
        List<Object> rows = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) rows.add(arr.opt(i));
        } else if (raw instanceof List) {
            rows.addAll((List<?>) raw);
        } else if (raw != null) {
            String text = String.valueOf(raw).trim();
            if (!text.isEmpty()) {
                for (String part : text.split("[,;]")) rows.add(part.trim());
            }
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object row : rows) {
            long uid = 0L;
            long kid = 0L;
            if (row instanceof JSONObject) {
                JSONObject o = (JSONObject) row;
                uid = o.optLong("uid", 0L);
                kid = o.optLong("kid", o.optLong("kingdomId", 0L));
            } else if (row instanceof Map) {
                Map<String, Object> m = anyToMap(row);
                uid = Exec.longOf(m == null ? null : m.get("uid"));
                kid = Exec.longOf(m == null ? null : m.get("kid"));
                if (kid <= 0L && m != null) kid = Exec.longOf(m.get("kingdomId"));
            } else if (row != null) {
                String text = String.valueOf(row).trim();
                String[] parts = text.split("@");
                try {
                    uid = Long.parseLong(parts[0].trim());
                    if (parts.length > 1) kid = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (uid <= 0L) continue;
            String key = uid + "@" + kid;
            if (seen.add(key)) out.add(new long[]{uid, kid});
        }
        return out;
    }

    /** 发车编队来源文案（纯函数，写日志/mod 用）：hero_ids / squad_index(N) / 默认队1。 */
    static String formationSourceLabel(BearConfig cfg) {
        if (cfg == null) return "默认队1";
        return formationSourceLabel(cfg, !cfg.heroIds.isEmpty());
    }

    /** 发车编队来源文案（实际生效口径）：hero_ids 不可用时如实标注回退到 squad_index。 */
    static String formationSourceLabel(BearConfig cfg, boolean heroIdsUsable) {
        if (cfg == null) return "默认队1";
        if (!cfg.heroIds.isEmpty() && heroIdsUsable) {
            StringBuilder sb = new StringBuilder("hero_ids(");
            for (int i = 0; i < cfg.heroIds.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cfg.heroIds.get(i));
            }
            return sb.append(')').toString();
        }
        if (!cfg.heroIds.isEmpty()) {
            return "squad_index(" + cfg.squadIndex + ")/hero_ids不可用回退";
        }
        return "squad_index(" + cfg.squadIndex + ")";
    }

    /** 实际生效的编队来源（写日志/mod 用；hero_ids 配置了但当前不可用会标注回退）。 */
    static String effectiveFormationSource(WjdrEngine engine, BearConfig cfg) {
        boolean heroIdsUsable = false;
        if (cfg != null && !cfg.heroIds.isEmpty()) {
            try {
                heroIdsUsable = formationReady(Exec.formationWithHeroes(engine, cfg.heroIds));
            } catch (Throwable ignored) {
            }
        }
        return formationSourceLabel(cfg, heroIdsUsable);
    }

    /**
     * 解析发车编队（hero_ids 优先 → squad_index 客户端布阵 → 默认队1；逐级回退保证线上行为不变）。
     * 结果可能为空编队，由调用方 SKIP（不猜英雄、不换兵）。
     */
    static Map<String, Object> launchFormation(WjdrEngine engine, BearConfig cfg) {
        if (cfg != null && !cfg.heroIds.isEmpty()) {
            Map<String, Object> byHeroIds = Exec.formationWithHeroes(engine, cfg.heroIds);
            if (formationReady(byHeroIds)) return byHeroIds;
        }
        int squad = cfg == null ? 1 : cfg.squadIndex;
        Map<String, Object> bySquad = Exec.formation(engine, squad);
        if (formationReady(bySquad)) return bySquad;
        // 最终兜底现有行为：默认第 1 队（可能仍为空，由调用方 SKIP 并写清原因）
        return squad == 1 ? bySquad : Exec.formation(engine, 1);
    }

    /** 发车编队选择结果（批次10：统一编队 / 旧行为回退；payload=null 表示 SKIP）。 */
    static final class BearFormationChoice {
        final Map<String, Object> payload;
        final String label;
        final String reason;

        BearFormationChoice(Map<String, Object> payload, String label, String reason) {
            this.payload = payload;
            this.label = label == null ? "" : label;
            this.reason = reason == null ? "" : reason;
        }
    }

    /**
     * 选发车编队（批次10 统一编队接入，1:1 参考网页版 DailyBearMarchExecutor.resolveFormation:562-570）：
     * <ul>
     *   <li>显式编队参数（{@link FormationResolver#isConfigured}）→ 统一编队解析；库缺失/编队不存在/
     *       英雄无效/兵量超库存 → payload=null + 可读原因（调用方 SKIP，不静默回退旧编队）；</li>
     *   <li>无编队参数 → 旧行为（hero_ids→squad_index→队1，payload 与既有逐字节一致）。</li>
     * </ul>
     */
    static BearFormationChoice chooseLaunchFormation(TaskContext tc, BearConfig cfg) {
        if (FormationResolver.isConfigured(cfg.paramsMap)) {
            FormationLibrary lib = FormationResolver.libraryFor(tc, TASK_ID);
            // 批次11：功能级 soldiers 覆盖 > 库值（按字段合并；0/缺省=用库值）
            Map<String, Object> resolveParams = mergeSoldiersForResolve(cfg.paramsMap, lib);
            FormationResolver.ResolveOutcome out = FormationResolver.resolveOutcome(
                    tc.engine, lib, resolveParams);
            if (!out.ready()) {
                return new BearFormationChoice(null, "统一编队", out.reason);
            }
            ResolvedFormation resolved = out.formation;
            Map<String, Object> payload = resolved.toFormationPayload();
            return new BearFormationChoice(payload, resolved.describe(),
                    payload == null ? "编队英雄/兵种为空" : "");
        }
        // 2026-11-10：无显式编队参数时，若角色编队库含默认打熊队预设，优先使用，
        // 避免回退 squad_index(1) 导致 403/415（生产 11-08/11-10 多角色失败主因）。
        FormationLibrary defaultLib = FormationResolver.libraryFor(tc, TASK_ID);
        if (defaultLib.byId(DEFAULT_BEAR_LAUNCH_FORMATION_ID) != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("formation_mode", "ROLE");
            params.put("formation_id", DEFAULT_BEAR_LAUNCH_FORMATION_ID);
            FormationResolver.ResolveOutcome def = FormationResolver.resolveOutcome(
                    tc.engine, defaultLib, params);
            if (def.ready()) {
                Map<String, Object> defPayload = def.formation.toFormationPayload();
                if (formationReady(defPayload)) {
                    return new BearFormationChoice(defPayload,
                            "默认打熊队模板(" + def.formation.describe() + ")", "");
                }
            }
        }
        String label = effectiveFormationSource(tc.engine, cfg);
        Map<String, Object> payload = launchFormation(tc.engine, cfg);
        return new BearFormationChoice(payload, label,
                formationReady(payload) ? "" : "空编队(无英雄/兵力)");
    }

    // ---------- 5.1b 统一编队（批次10）：参数转换 / 上车 1-6 编队 / 301 降级（纯函数） ----------

    /** JSONObject → Map 深转换（{@code FormationResolver.resolve} 契约入参是 Map<String,Object>）。 */
    static Map<String, Object> paramsToMap(JSONObject p) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) return out;
        java.util.Iterator<String> it = p.keys();
        while (it.hasNext()) {
            String key = it.next();
            out.put(key, jsonValueToJava(p.opt(key)));
        }
        return out;
    }

    /** JSON 值 → Java 值（嵌套 JSONObject/JSONArray 递归转换）。 */
    static Object jsonValueToJava(Object v) {
        if (v instanceof JSONObject) return paramsToMap((JSONObject) v);
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) v;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(jsonValueToJava(arr.opt(i)));
            return list;
        }
        return v;
    }

    /**
     * 是否显式配置了通用编队字段（批次10）：formation_mode/formation_id/soldiers/ratio/total
     * 或 clarify join_formations 任一出现即 true。只有旧 hero_ids/squad_index → false（走旧行为）。
     */
    static boolean hasGenericFormationConfig(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return false;
        for (String key : new String[]{"formation_mode", "formationMode", "mode",
                "formation_id", "formationId", "soldiers", "ratio", "total",
                "join_formations", "joinFormations"}) {
            if (params.containsKey(key) && params.get(key) != null) return true;
        }
        return false;
    }

    /** 任意 JSON/Map → Map 深副本（join_formations 元素兼容两种输入形状）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> anyToMap(Object raw) {
        if (raw instanceof JSONObject) return paramsToMap((JSONObject) raw);
        if (raw instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), jsonValueToJava(e.getValue()));
            }
            return out;
        }
        return null;
    }

    /**
     * 巨熊上车编队配置（App {@code join_formations} 数组元素；id 1-6 对应网页版
     * {@code DailyBearFormationConfig}）。mode/soldiers/ratio/total 原样保留，解析由 formation 包完成。
     */
    static final class JoinFormationConfig {
        final int id;
        final boolean enabled;
        final String mode;
        final long formationId;
        final long infantry;
        final long lancer;
        final long marksman;
        final Map<String, Object> params;

        JoinFormationConfig(int id, boolean enabled, String mode, long formationId,
                            long infantry, long lancer, long marksman, Map<String, Object> params) {
            this.id = id;
            this.enabled = enabled;
            this.mode = mode == null ? "" : mode;
            this.formationId = formationId;
            this.infantry = Math.max(0L, infantry);
            this.lancer = Math.max(0L, lancer);
            this.marksman = Math.max(0L, marksman);
            this.params = params == null ? new LinkedHashMap<>() : params;
        }

        String label() {
            return "上车编队" + id;
        }

        String describe() {
            return label() + "[" + (mode.isEmpty() ? "AUTO" : mode)
                    + (formationId > 0 ? "#" + formationId : "")
                    + " 兵=" + infantry + "/" + lancer + "/" + marksman + "]";
        }
    }

    /** 无 join_formations 时的默认上车槽：库内存在的熊上车1-6（最多 6）。 */
    static List<JoinFormationConfig> defaultJoinSlots(FormationLibrary lib, BearConfig cfg) {
        List<JoinFormationConfig> out = new ArrayList<>();
        if (lib == null) return out;
        int max = MAX_JOIN_COUNT;   // 默认模板固定尝试 1-6（库内有多少用多少）
        for (int slot = 1; slot <= max; slot++) {
            long fid = DEFAULT_BEAR_RIDE_FIRST_ID + (slot - 1);
            if (lib.byId(fid) == null) continue;
            Map<String, Object> slotParams = new LinkedHashMap<>();
            slotParams.put("formation_mode", "ROLE");
            slotParams.put("formation_id", fid);
            slotParams.put("id", slot);
            slotParams.put("mode", "ROLE");
            slotParams.put("enabled", true);
            out.add(new JoinFormationConfig(slot, true, "ROLE", fid, 0L, 0L, 0L, slotParams));
        }
        return out;
    }

    /**
     * 解析巨熊 {@code join_formations}（纯函数）：支持 JSONArray/List；id 缺省=下标+1；
     * id 越界(1-6)/重复 → 丢弃；保持数组顺序。缺失/非法 → 空列表（调用方走旧自动上车逻辑）。
     */
    static List<JoinFormationConfig> parseJoinFormations(JSONObject params) {
        List<JoinFormationConfig> out = new ArrayList<>();
        if (params == null) return out;
        Object raw = params.opt("join_formations");
        if (raw == null) raw = params.opt("joinFormations");
        List<Object> rows = new ArrayList<>();
        if (raw instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) rows.add(arr.opt(i));
        } else if (raw instanceof List) {
            rows.addAll((List<?>) raw);
        }
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> m = anyToMap(rows.get(i));
            if (m == null || m.isEmpty()) continue;
            long rawId = Exec.longOf(m.containsKey("id") ? m.get("id") : null);
            int id = (int) (rawId > 0 ? rawId : i + 1);
            if (id < 1 || id > MAX_JOIN_COUNT || !seen.add(id)) continue;
            boolean enabled = optBoolValue(m.get("enabled"), true);
            Object modeRaw = m.containsKey("mode") ? m.get("mode")
                    : (m.containsKey("formation_mode") ? m.get("formation_mode") : m.get("formationMode"));
            String mode = modeRaw == null ? "" : String.valueOf(modeRaw).trim();
            long formationId = Exec.longOf(m.containsKey("formation_id") ? m.get("formation_id")
                    : m.get("formationId"));
            long infantry = 0L, lancer = 0L, marksman = 0L;
            Map<String, Object> soldiers = anyToMap(m.get("soldiers"));
            if (soldiers != null) {
                infantry = Exec.longOf(soldiers.get("infantry"));
                lancer = Exec.longOf(soldiers.get("lancer"));
                marksman = Exec.longOf(soldiers.get("marksman"));
            }
            // 传给 FormationResolver 的槽参数：统一键名（App 用 mode，契约用 formation_mode）
            Map<String, Object> slotParams = new LinkedHashMap<>(m);
            if (!mode.isEmpty()) slotParams.put("formation_mode", mode);
            if (formationId > 0L) slotParams.put("formation_id", formationId);
            out.add(new JoinFormationConfig(id, enabled, mode, formationId,
                    infantry, lancer, marksman, slotParams));
        }
        return out;
    }

    /** 已启用的上车编队 id 升序（网页版 1-6 固定顺序）；无配置 → 空。 */
    static List<Integer> enabledJoinFormationIds(BearConfig cfg) {
        return cfg == null ? new ArrayList<>() : enabledJoinFormationIds(cfg.joinFormations);
    }

    /**
     * 已启用的上车编队 id 升序（编队槽列表版；巨兽/巨熊共用）。
     * {@code enabled=false} 的槽不参与“第 N 次尝试”选取（直接跳过，不发请求/不计失败）。
     */
    static List<Integer> enabledJoinFormationIds(List<JoinFormationConfig> slots) {
        List<Integer> ids = new ArrayList<>();
        if (slots == null) return ids;
        for (JoinFormationConfig slot : slots) {
            if (slot != null && slot.enabled && !ids.contains(slot.id)) ids.add(slot.id);
        }
        ids.sort(Integer::compareTo);
        return ids;
    }

    /** 第 n 个上车尝试使用的编队槽（按启用 id 升序；越界 → null=走旧逻辑）。 */
    static JoinFormationConfig joinFormationAt(BearConfig cfg, int attemptIndex) {
        return cfg == null ? null : joinFormationAt(cfg.joinFormations, attemptIndex);
    }

    /**
     * 第 n 个上车尝试使用的编队槽（编队槽列表版；巨兽/巨熊共用）。
     *
     * @param attemptIndex 零基下标（巨兽=state.joinCount）；越界/无配置 → null（调用方走旧逻辑）
     */
    static JoinFormationConfig joinFormationAt(List<JoinFormationConfig> slots, int attemptIndex) {
        if (slots == null || attemptIndex < 0) return null;
        List<Integer> ids = enabledJoinFormationIds(slots);
        if (attemptIndex >= ids.size()) return null;
        int want = ids.get(attemptIndex);
        for (JoinFormationConfig slot : slots) {
            if (slot != null && slot.id == want) return slot;
        }
        return null;
    }

    // ---------- 5.1c 功能级覆盖 / 上车槽跳过（批次11，纯函数） ----------

    /**
     * 功能级 soldiers 覆盖合并（批次11；优先级=参数覆盖 &gt; 编队库值 &gt; 比例自动）：
     * 按字段合并（参数某字段 &gt;0 覆盖该字段，否则用库值）；仅 ROLE 模式且库内编队有显式兵量时注入
     * {@code soldiers}，保证 {@link FormationResolver#resolveRole} 的 {@code soldiersOf(p)} 读到合并结果。
     * AUTOMATIC / 无库值 / 无 formation_id → 原样返回（交给 resolver 的 ratio/total 自动分配）。
     */
    static Map<String, Object> mergeSoldiersForResolve(Map<String, Object> params, FormationLibrary lib) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (params != null) out.putAll(params);
        if (lib == null || lib.isEmpty()) return out;
        if (!FormationResolver.MODE_ROLE.equals(FormationResolver.modeOf(out))) return out;
        long formationId = FormationResolver.formationIdOf(out);
        if (formationId <= 0L) return out;
        FormationSpec spec = lib.byId(formationId);
        if (spec == null || !spec.hasExplicitSoldiers()) return out;
        long[] override = FormationResolver.soldiersOf(out);
        long infantry = override != null && override.length > 0 && override[0] > 0L
                ? override[0] : spec.infantry;
        long lancer = override != null && override.length > 1 && override[1] > 0L
                ? override[1] : spec.lancer;
        long marksman = override != null && override.length > 2 && override[2] > 0L
                ? override[2] : spec.marksman;
        if (infantry + lancer + marksman <= 0L) return out;
        Map<String, Object> soldiers = new LinkedHashMap<>();
        soldiers.put("infantry", infantry);
        soldiers.put("lancer", lancer);
        soldiers.put("marksman", marksman);
        out.put("soldiers", soldiers);
        return out;
    }

    /** 上车槽解析结果（批次11：坏槽跳过继续）。 */
    static final class JoinSlotPlan {
        final JoinFormationConfig slot;
        final Map<String, Object> payload;
        final String label;
        final String reason;

        JoinSlotPlan(JoinFormationConfig slot, Map<String, Object> payload, String label, String reason) {
            this.slot = slot;
            this.payload = payload;
            this.label = label == null ? "" : label;
            this.reason = reason == null ? "" : reason;
        }

        boolean ready() {
            return payload != null && !payload.isEmpty();
        }
    }

    /** 上车槽解析器（生产实现=FormationResolver；单测注入假解析器，不连引擎）。 */
    interface JoinSlotResolver {
        JoinSlotPlan resolve(JoinFormationConfig slot);
    }

    /** 上车槽选择结果：usable=可用槽（保序）；skipped=坏槽记录；disabled=已关闭槽（直接跳过，不发请求/不计失败）。 */
    static final class JoinSlotSelection {
        final List<JoinSlotPlan> usable = new ArrayList<>();
        final List<JoinFormationConfig> skippedSlots = new ArrayList<>();
        final List<String> skippedNotes = new ArrayList<>();
        final List<Integer> disabledIds = new ArrayList<>();

        boolean allSkipped() {
            return usable.isEmpty();
        }

        String notesText() {
            StringBuilder sb = new StringBuilder();
            for (String note : skippedNotes) {
                if (sb.length() > 0) sb.append("；");
                sb.append(note);
            }
            return sb.toString();
        }
    }

    /**
     * 批次11：遍历上车槽——坏槽（编队不存在/英雄无效/兵量超库存/SKIP）记录原因后<b>继续下一启用槽</b>；
     * {@code enabled=false} 槽直接跳过（不发请求、不计失败）；全部槽失败由调用方结束本场上车（不重试风暴）。
     */
    static JoinSlotSelection selectJoinSlots(List<JoinFormationConfig> slots, JoinSlotResolver resolver) {
        JoinSlotSelection out = new JoinSlotSelection();
        if (slots == null) return out;
        for (JoinFormationConfig slot : slots) {
            if (slot == null) continue;
            if (!slot.enabled) {
                out.disabledIds.add(slot.id);
                continue;
            }
            JoinSlotPlan plan = null;
            try {
                if (resolver != null) plan = resolver.resolve(slot);
            } catch (Throwable ignored) {
            }
            if (plan == null || !plan.ready()) {
                String reason = plan == null || plan.reason == null || plan.reason.isEmpty()
                        ? "编队不可用" : plan.reason;
                out.skippedSlots.add(slot);
                out.skippedNotes.add("上车编队#" + slot.id + " 跳过：" + reason);
                continue;
            }
            out.usable.add(plan);
        }
        return out;
    }

    /** 一次上车尝试（槽+车头配对；批次11 坏槽跳过后的计划）。 */
    static final class JoinAttempt {
        RallyCandidate candidate;
        Map<String, Object> formation;
        String label = "";
    }

    /**
     * 纯函数：把可用上车槽与候选车头一一配对（每槽最多 1 次、每车头最多 1 次）；
     * 英雄与发起队/前序上车占用的槽记入 skipNotesOut 后继续下一槽（不阻塞其余槽）。
     */
    static List<JoinAttempt> planJoinAttempts(JoinSlotSelection selection, List<RallyCandidate> candidates,
                                              Set<Long> usedHeroes, int remaining,
                                              List<String> skipNotesOut) {
        List<JoinAttempt> out = new ArrayList<>();
        if (selection == null || candidates == null || candidates.isEmpty()) return out;
        int max = Math.min(selection.usable.size(),
                Math.min(candidates.size(), Math.max(0, remaining)));
        int candidateIndex = 0;
        for (JoinSlotPlan plan : selection.usable) {
            if (out.size() >= max || candidateIndex >= candidates.size()) break;
            RallyCandidate candidate = candidates.get(candidateIndex);
            List<Long> heroes = heroIdsOf(plan.payload);
            boolean conflict = false;
            if (usedHeroes != null) {
                for (Long hero : heroes) {
                    if (hero != null && usedHeroes.contains(hero)) {
                        conflict = true;
                        break;
                    }
                }
            }
            if (conflict) {
                if (skipNotesOut != null) {
                    skipNotesOut.add("上车编队#" + plan.slot.id
                            + " 跳过：英雄已被发起队/前序上车占用 " + heroes);
                }
                continue;   // 该槽跳过，下一个槽复用同一车头（车头未消耗）
            }
            JoinAttempt attempt = new JoinAttempt();
            attempt.candidate = candidate;
            attempt.formation = plan.payload;
            attempt.label = plan.label;
            out.add(attempt);
            candidateIndex++;
        }
        return out;
    }

    /** 同场次同槽只记一次 FileLog（返回 true=首次；mod 每轮仍写）。 */
    static boolean recordJoinSkipLogOnce(BearState state, long windowStartSec, int slotId) {
        if (state == null) return true;
        if (state.joinSkipWindowStart != windowStartSec) {
            state.joinSkipWindowStart = windowStartSec;
            state.joinSkipSlots = "";
        }
        List<Integer> ids = new ArrayList<>();
        if (state.joinSkipSlots != null && !state.joinSkipSlots.isEmpty()) {
            for (String part : state.joinSkipSlots.split(",")) {
                try {
                    int v = Integer.parseInt(part.trim());
                    if (v > 0) ids.add(v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (ids.contains(slotId)) return false;
        ids.add(slotId);
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        state.joinSkipSlots = sb.toString();
        return true;
    }

    // ---------- 巨熊 301（英雄不在城）降级重试（纯函数；批次10） ----------

    /** 把本次编队的英雄加入拉黑集合（返回新集合；null 安全）。 */
    static java.util.Set<Long> blacklistHeroes(java.util.Set<Long> blacklist, List<Long> formationHeroes) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        if (blacklist != null) out.addAll(blacklist);
        if (formationHeroes != null) {
            for (Long id : formationHeroes) {
                if (id != null && id > 0L) out.add(id);
            }
        }
        return out;
    }

    /** 301 降级轮次是否还有预算（最多 {@value #MAX_BEAR_301_ROUNDS} 轮，不重试风暴）。 */
    static boolean canRetry301(int usedRounds) {
        return usedRounds >= 0 && usedRounds < MAX_BEAR_301_ROUNDS;
    }

    /** 301 降级文案（日志/mod 可读）。 */
    static String bear301Text(int round, java.util.Set<Long> blacklist) {
        StringBuilder sb = new StringBuilder("301(英雄不在城)降级第").append(round).append("轮：拉黑英雄 ");
        if (blacklist == null || blacklist.isEmpty()) {
            sb.append("(无)");
        } else {
            int i = 0;
            for (Long id : blacklist) {
                if (i++ > 0) sb.append(',');
                sb.append(id);
            }
        }
        return sb.append("，换其余英雄组合重试").toString();
    }

    /** 从全部英雄中挑出未被拉黑/未占用的候选（去重、保序、最多 max 个；不足返回空）。 */
    static List<Long> pickHeroesExcluding(List<Long> allHeroes, java.util.Set<Long> excluded, int max) {
        List<Long> out = new ArrayList<>();
        if (allHeroes == null || max <= 0) return out;
        java.util.Set<Long> skip = excluded == null ? Collections.emptySet() : excluded;
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (Long id : allHeroes) {
            if (id == null || id <= 0L || skip.contains(id) || !seen.add(id)) continue;
            out.add(id);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 301 降级编队（换其余英雄组合；无可用英雄 → null=停止重试）。 */
    static Map<String, Object> downgrade301Formation(WjdrEngine engine, java.util.Set<Long> blacklist) {
        try {
            List<Long> heroes = pickHeroesExcluding(Exec.allHeroes(engine), blacklist,
                    BEAR_DOWNGRADE_HERO_COUNT);
            if (heroes.isEmpty()) return null;
            return Exec.formationWithHeroes(engine, heroes);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Map 值转布尔（App 参数/JSON 混用 Boolean/Number/String 三种形状）。 */
    static boolean optBoolValue(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0L;
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return def;
    }

    // ---------- 5.2 发车重试决策（批次5，纯函数） ----------

    /** 发车重试决策：immediate=同轮立即降档重试；retryAtSec>0=延时重试时刻；都不满足=不重试。 */
    static final class LaunchRetry {
        final boolean retry;
        final boolean immediate;
        final long retryAtSec;
        final int nextPrepareIndex;
        final String reason;

        private LaunchRetry(boolean retry, boolean immediate, long retryAtSec,
                            int nextPrepareIndex, String reason) {
            this.retry = retry;
            this.immediate = immediate;
            this.retryAtSec = retryAtSec;
            this.nextPrepareIndex = nextPrepareIndex;
            this.reason = reason;
        }

        static LaunchRetry none(String reason) {
            return new LaunchRetry(false, false, 0L, 0, reason);
        }

        static LaunchRetry immediateDowngrade(int nextPrepareIndex, String reason) {
            return new LaunchRetry(true, true, 0L, nextPrepareIndex, reason);
        }

        static LaunchRetry delayed(long retryAtSec, int prepareIndex, String reason) {
            return new LaunchRetry(true, false, retryAtSec, prepareIndex, reason);
        }

        @Override
        public String toString() {
            return retry ? (immediate ? "立即重试→" + nextPrepareIndex : "延时重试@" + retryAtSec) : "不重试（" + reason + "）";
        }
    }

    /**
     * 发车失败重试决策（纯函数）：
     * <ul>
     *   <li>5204（场次未开/准备档过长）→ 降一档 prepare_time_index 立即重试 1 次（620→320、1820→620…）；</li>
     *   <li>401/2878（兵/队列不足）→ 记录并 {@value #LAUNCH_RETRY_DELAY_SEC}s 后延时重试 1 次；</li>
     *   <li>其它码不重试（写明确 SKIP 并由幂等键封板）；</li>
     *   <li>两类重试都受窗口剩余约束：剩余 ≥ 准备档秒数 + {@value #LAUNCH_RETRY_MARGIN_SEC}s。</li>
     * </ul>
     */
    static LaunchRetry launchRetryDecision(int code, long nowSec, Window window, BearConfig cfg, BearState state) {
        int usedIndex = effectivePrepareIndex(cfg, state);
        long secondsToEnd = window == null ? 0L : window.endSec - nowSec;
        if (code == CODE_TRAP_MASS_TIME_EXPIRED) {
            if (state != null && state.downgradeRetries >= MAX_DOWNGRADE_RETRIES) {
                return LaunchRetry.none("码5204 已降档重试过 1 次");
            }
            int next = clampPrepareIndex(usedIndex - 1);
            if (next >= usedIndex) {
                return LaunchRetry.none("已是最低准备档 " + prepareSeconds(usedIndex) + "s");
            }
            long need = prepareSeconds(next) + LAUNCH_RETRY_MARGIN_SEC;
            if (secondsToEnd < need) {
                return LaunchRetry.none("窗口剩余 " + secondsToEnd + "s 不足降档重试（需≥" + need + "s）");
            }
            return LaunchRetry.immediateDowngrade(next, "码5204 → 准备档 " + usedIndex
                    + "(" + prepareSeconds(usedIndex) + "s) 降为 " + next + "(" + prepareSeconds(next) + "s) 立即重试");
        }
        if (code == CODE_LAUNCH_RETRY_401 || code == CODE_WORLD_MARCH_LIMIT
                || code == CODE_HERO_OCCUPIED || code == CODE_FORMATION_LIMIT
                || code == CODE_SOLDIER_LIMIT) {
            // 2026-11-06：403/415 也纳入「60s 延时重试 1 次」。下一轮会重新选取编队
            // （配合 Exec.formation 的 3 英雄夹取），避免像生产 11-02/11-04 那样一次被拒即封板。
            if (state != null && state.delayRetries >= MAX_DELAY_RETRIES) {
                return LaunchRetry.none("码" + code + " 的 60s 延时重试已用过 1 次");
            }
            long need = prepareSeconds(usedIndex) + LAUNCH_RETRY_MARGIN_SEC;
            if (secondsToEnd < need) {
                return LaunchRetry.none("窗口剩余 " + secondsToEnd + "s 不足以 60s 后重试（需≥" + need + "s）");
            }
            return LaunchRetry.delayed(nowSec + LAUNCH_RETRY_DELAY_SEC, usedIndex,
                    "码" + code + " → " + LAUNCH_RETRY_DELAY_SEC + "s 后重试 1 次");
        }
        String known = bearCodeText(code);
        return LaunchRetry.none(known.isEmpty() ? ("码" + code + " 不重试") : known);
    }

    /** 本次应使用的准备档：已降档则沿用降档值，否则用配置值。 */
    static int effectivePrepareIndex(BearConfig cfg, BearState state) {
        if (state != null && state.launchPrepareIndexUsed >= 1) {
            return clampPrepareIndex(state.launchPrepareIndexUsed);
        }
        return cfg == null ? DEFAULT_PREPARE_TIME_INDEX : clampPrepareIndex(cfg.prepareTimeIndex);
    }

    /** 新场次归一重试/预热状态（跨场次不串台账；同场次保留预算避免无限重试）。 */
    static void resetRetryIfNewWindow(BearState state, long windowStartSec) {
        resetRetryIfNewWindow(state, windowStartSec, 0L);
    }

    /**
     * 新场次归一（2026-11-08 修复）：reserve 在本场进行中会翻到下一场，windowOf 随之给出
     * 下一场窗口——旧实现在此时清掉 nextLaunchRetryAt，本场的 60s 重试永久丢失
     * （生产 21:30 场：415 后被 reset，cad 被推迟到 23:31，本场不再发车）。
     * 规则：上次重试计划所属场次（retryWindowStart）仍在活动期内 → 视为同场次重试，
     * 保留重试计划；真正进入新场次（已过旧场次结束）才重置。
     */
    static void resetRetryIfNewWindow(BearState state, long windowStartSec, long nowSec) {
        if (state == null) return;
        if (state.retryWindowStart == windowStartSec) return;
        if (state.nextLaunchRetryAt > 0 && state.retryWindowStart > 0 && nowSec > 0
                && nowSec < state.retryWindowStart + ACTIVITY_MINUTES * 60L) {
            return;   // 本场仍在进行：保留重试计划，不把"reserve 翻场"当成新场次
        }
        state.retryWindowStart = windowStartSec;
        state.downgradeRetries = 0;
        state.delayRetries = 0;
        state.nextLaunchRetryAt = 0L;
        state.lastLaunchCode = 0;
        state.launchPrepareIndexUsed = 0;
        state.preheatRefreshAt = 0L;
        state.lastLaunchText = "";
    }

    // ---------- 5.3 唤醒/预热（批次5，纯函数） ----------

    /**
     * 任务下一次唤醒时刻（epoch millis；0=不覆盖节奏），在 C3 语义上叠加批次5：
     * 预热窗（PREPARING 且未发车）每 {@value #PREHEAT_REFRESH_SEC}s 醒一次做目标/编队检查；
     * 有发车重试计划时醒在 {@code retryAtSec}（与 C3 取更早者）。
     */
    static long bearWakeAtMillis(long nowMillis, Window window, boolean reserved, boolean launched,
                                 long retryAtSec) {
        long base = bearNextWakeAtMillis(nowMillis, window, reserved, launched);
        long nowSec = nowMillis / 1000L;
        if (retryAtSec > nowSec) {
            long retryMs = retryAtSec * 1000L;
            base = base <= 0L ? retryMs : Math.min(base, retryMs);
        }
        if (reserved && window != null && !launched && window.startSec > nowSec) {
            long toStart = window.startSec - nowSec;
            long candidate = 0L;
            if (window.phase == Window.PREPARING) {
                // 准备窗：每 60s 醒一次做目标/编队预热，不晚于开赛时刻
                candidate = Math.min((nowSec + PREHEAT_REFRESH_SEC) * 1000L, window.startSec * 1000L);
            } else if (window.phase == Window.WAITING) {
                if (toStart <= PREHEAT_LOOKAHEAD_SEC) {
                    // 已进入 20 分钟预热提前量：60s 节拍（跨进 20:50 准备窗后继续）
                    candidate = Math.min((nowSec + PREHEAT_REFRESH_SEC) * 1000L, window.startSec * 1000L);
                } else if (toStart <= PREHEAT_ARM_SEC) {
                    // 2 小时内：≤20 分钟一步，保证落进 20:50-21:00 预热窗
                    candidate = Math.min((window.startSec - BEAR_WAKE_LEAD_SECONDS) * 1000L,
                            (nowSec + PREHEAT_LOOKAHEAD_SEC) * 1000L);
                } else {
                    // 更远：≤2 小时一步（C3 原本一觉睡到开赛前 60s，会跳过预热窗；分段步进代价极低）
                    candidate = Math.min((window.startSec - BEAR_WAKE_LEAD_SECONDS) * 1000L,
                            (nowSec + PREHEAT_ARM_SEC) * 1000L);
                }
            }
            if (candidate > nowMillis) {
                base = base <= 0L ? candidate : Math.min(base, candidate);
            }
        }
        return base;
    }

    /** 预热刷新是否到期（纯函数）：首次/满 60s/时钟回拨都刷新。 */
    static boolean preheatRefreshDue(long nowSec, long lastRefreshSec) {
        if (lastRefreshSec <= 0L) return true;
        if (nowSec < lastRefreshSec) return true;
        return nowSec - lastRefreshSec >= PREHEAT_REFRESH_SEC;
    }

    /** 预热状态文案（写 mod.bear.prepare，失败原因一眼可读）。 */
    static String preheatSummary(boolean targetReady, long targetX, long targetY, long monsterId,
                                 boolean formationReady, String formationSource, long secondsToStart) {
        StringBuilder sb = new StringBuilder("预热：");
        if (targetReady) {
            sb.append("目标(").append(targetX).append(',').append(targetY).append(")monster=")
                    .append(monsterId).append(" 就绪");
        } else {
            sb.append("目标未就绪");
        }
        sb.append(" · 编队=").append(formationSource == null ? "未知" : formationSource)
                .append(formationReady ? " 就绪" : " 不可用");
        sb.append(" · 距开赛 ").append(Math.max(0L, secondsToStart)).append("s");
        return sb.toString();
    }

    // ---------- 5.4 发车可观测文案（批次5，纯函数） ----------

    /** 发车尝试日志文案：窗口/准备档/编队来源/剩余秒（失败原因一眼可读）。 */
    static String launchAttemptText(long windowStartSec, int prepareIndex, String formationSource,
                                    long secondsToEnd) {
        return "窗口=" + fmtTime(windowStartSec)
                + " prepare=" + prepareIndex + "(" + prepareSeconds(prepareIndex) + "s)"
                + " 编队=" + (formationSource == null ? "未知" : formationSource)
                + " 剩余=" + secondsToEnd + "s";
    }

    /** 发车失败文案（写 mod.bear.launch / SKIP 原因）：错误码原码 + 已知语义 + 重试计划。 */
    static String launchFailureText(int code, LaunchRetry retry) {
        StringBuilder sb = new StringBuilder("发车失败(码").append(code).append(')');
        if (code == CODE_RETRY_WINDOW_SHORT) {
            sb.append("：窗口剩余不足，已放弃重试");
            return sb.toString();
        }
        String known = bearCodeText(code);
        if (!known.isEmpty()) sb.append("：").append(known);
        if (code == CODE_HERO_OCCUPIED) {
            sb.append(" · 可在 bear.hero_ids 配置空闲英雄后重试");
        }
        if (retry != null && retry.retry) {
            if (retry.immediate) {
                sb.append(" · 立即降档至 prepare=").append(retry.nextPrepareIndex)
                        .append("(").append(prepareSeconds(retry.nextPrepareIndex)).append("s) 重试");
            } else {
                sb.append(" · 计划 ").append(LAUNCH_RETRY_DELAY_SEC).append("s 后重试 1 次");
            }
        } else if (retry != null && retry.reason != null && !retry.reason.isEmpty()) {
            sb.append(" · 不再重试（").append(retry.reason).append('）');
        }
        return sb.toString();
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

    /** B2-T3：资产库 trap 域（reserve_time 权威，来自 req 响应回灌）→ TrapTimes；未 ready/无数据返回 null。 */
    static TrapTimes trapTimesFromAssets(WjdrEngine engine) {
        try {
            com.carlos.vault.wjdr.asset.CoreAssetLibrary lib =
                    com.carlos.vault.wjdr.asset.CoreAssetRegistry.global().get(engine);
            if (lib == null || !lib.ready()) return null;
            com.carlos.vault.wjdr.asset.AssetSnapshot.Bear bear = lib.bear();
            if (bear == null || !bear.reserveKnown) return null;
            TrapTimes t = new TrapTimes();
            t.reserve = bear.reserveTime;
            t.start = bear.massStartTime;
            t.end = bear.massEndTime;
            t.cool = bear.coolEndTime;
            return t.any() ? t : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    /** 集结成员详情里“自己”的英雄 id 集合（hero_map；兼容 {hero:{id}} / {id}/{v} 形状）。 */
    static java.util.Set<Long> memberHeroIds(Map<String, Object> content, long ownUid) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<Long>();
        if (content == null || ownUid <= 0) return out;
        for (Map<String, Object> detail : Exec.mapsOf(content.get("detail_list"))) {
            long owner = Exec.longOf(detail.get("owner"));
            if (owner <= 0) owner = Exec.longOf(detail.get("uid"));
            if (owner != ownUid) continue;
            for (Map<String, Object> pair : Exec.mapsOf(detail.get("hero_map"))) {
                long hid = 0L;
                Map<String, Object> hero = Exec.mapOf(pair.get("hero"));
                if (hero != null) hid = Exec.longOf(hero.get("id"));
                if (hid <= 0) hid = Exec.longOf(pair.get("id"));
                if (hid <= 0) hid = Exec.longOf(pair.get("v"));
                if (hid > 0) out.add(hid);
            }
        }
        return out;
    }

    /**
     * 成员详情与本次编队英雄匹配：0=不在成员里；1=匹配/英雄未知；2=已加入但英雄不符。
     * 对齐网页版 queryRallyMembers 的 DIFFERENT_FORMATION 判定。
     */
    static int memberMatch(Map<String, Object> content, long ownUid, java.util.Collection<Long> expected) {
        if (content == null || ownUid <= 0) return 0;
        boolean present = false;
        for (Map<String, Object> detail : Exec.mapsOf(content.get("detail_list"))) {
            long owner = Exec.longOf(detail.get("owner"));
            if (owner <= 0) owner = Exec.longOf(detail.get("uid"));
            if (owner == ownUid) {
                present = true;
                break;
            }
        }
        if (!present) return 0;
        java.util.Set<Long> actual = memberHeroIds(content, ownUid);
        if (actual.isEmpty() || expected == null || expected.isEmpty()) return 1;
        return actual.equals(new java.util.HashSet<Long>(expected)) ? 1 : 2;
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

    /**
     * {@code req_world_mass_master_detail} 对账文案（批次5 可观测性）：成员数 / 是否含自己。
     * 纯函数便于单测（不在单测里连协议）。
     */
    static String joinDetailSummary(Map<String, Object> content, long ownUid) {
        if (content == null) return "对账未返回";
        int count = 0;
        boolean self = false;
        for (Map<String, Object> detail : Exec.mapsOf(content.get("detail_list"))) {
            count++;
            long owner = Exec.longOf(detail.get("owner"));
            if (owner <= 0) owner = Exec.longOf(detail.get("uid"));
            if (ownUid > 0 && owner == ownUid) self = true;
        }
        if (count == 0) return "对账详情为空";
        return "成员" + count + " · 含自己=" + (self ? "是" : "否");
    }

    /** 对账请求（每轮最多 1 次，网页版 RALLY_MEMBER_DETAIL_TIMEOUT 同语义）；异常返回 null。 */
    private static GameMessage requestRallyDetail(TaskContext tc, RallyCandidate candidate) {
        try {
            return tc.engine.request("req_world_mass_master_detail", Exec.args(
                    "id", candidate.masterMarchId, "owner", candidate.ownerUid, "kid", candidate.ownerKid));
        } catch (Exception e) {
            return null;
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
            case CODE_FORMATION_LIMIT:
                return "编队人数/配置超限（码415）";
            case CODE_SOLDIER_LIMIT:
                return "编队士兵数超限（码406）";
            case CODE_ALREADY_JOINED:
                return "已加入目标集结或通知滞后（码2844）";
            case CODE_WORLD_MARCH_LIMIT:
                return "行军队列已满（码2878）";
            case CODE_LAUNCH_RETRY_401:
                return "兵/队列参数不足（码401）";
            default:
                return "";
        }
    }

    // ==================================================================================
    // 七、纯函数：配置 / 触发节流 / mod 文案（单测直接覆盖）
    // ==================================================================================

    /** 任务参数（App FnParams 同源；全部有默认值，缺省不阻塞）。 */
    static final class BearConfig {
        /**
         * 发起总开关（对齐网页版 {@code DailyBearActionConfig.launchEnabled}）：false=本场不发起集结。
         * 说明：网页版编队级 {@code DailyBearFormationConfig.enabled} 在发起侧仅参与配置校验/保护英雄，
         * 不单独拦截发起（发起闸门只看 launchEnabled）；上车槽 enabled 见 {@link JoinFormationConfig#enabled}。
         */
        final boolean launchEnabled;
        final boolean joinEnabled;
        final boolean prepareRecallEnabled;
        /** 2026-11-07：开赛前 5 分钟是否召回全部队伍并暂停队列任务（默认开）。 */
        final boolean prepRecall;
        /** 2026-11-07：打熊是否自动开启全部战斗型宠物技能（排除 550051；默认关）。 */
        final boolean petSkillsEnabled;
        /** 未读取到预约时是否允许按配置周窗口发起（默认 false：未预约→SKIP，不臆造场次）。 */
        final boolean launchWithoutReserve;
        final int joinCount;
        final int prepareTimeIndex;
        final int windowWeekday;       // ISO：1=周一 … 7=周日（默认 5=周五）
        final int windowStartMinute;   // 0..1439（北京时间）
        final int windowDurationMinutes;
        /** 编队配置（批次5）：hero_ids 优先（≤3 名）；为空时用 squad_index 客户端布阵；都缺省沿用队1。 */
        final List<Long> heroIds;
        final int squadIndex;
        // ---- 批次10 统一编队（非 final：defaults() 之外由 of() 填充；缺省=旧行为） ----
        /** 原始 fnParams（JSONObject→Map 深转换；供 FormationResolver.resolve 使用）。 */
        final Map<String, Object> paramsMap = new LinkedHashMap<>();
        /** 是否显式配置了通用编队字段（formation_mode/formation_id/soldiers/ratio/total）。 */
        boolean formationConfigured;
        /** formation_library 原始 JSON（空=无库）。 */
        String formationLibraryJson = "";
        /** 巨熊上车编队 1-6（App join_formations；空=沿用旧自动上车逻辑）。 */
        List<JoinFormationConfig> joinFormations = Collections.emptyList();
        // ---- 2026-11-11 指定车头（对齐网页版 preferredLeaders） ----
        boolean preferredLeaderEnabled;
        boolean preferredLeaderOnly;
        int preferredLeaderWaitSeconds;
        /** 指定车头 [uid,kid] 列表（按优先级）。 */
        List<long[]> preferredLeaders = Collections.emptyList();

        BearConfig(boolean launchEnabled, boolean joinEnabled, boolean prepareRecallEnabled,
                   boolean prepRecall, boolean petSkillsEnabled,
                   boolean launchWithoutReserve, int joinCount, int prepareTimeIndex,
                   int windowWeekday, int windowStartMinute, int windowDurationMinutes,
                   List<Long> heroIds, int squadIndex,
                   boolean preferredLeaderEnabled, boolean preferredLeaderOnly,
                   int preferredLeaderWaitSeconds, List<long[]> preferredLeaders) {
            this.launchEnabled = launchEnabled;
            this.joinEnabled = joinEnabled;
            this.prepareRecallEnabled = prepareRecallEnabled;
            this.prepRecall = prepRecall;
            this.petSkillsEnabled = petSkillsEnabled;
            this.launchWithoutReserve = launchWithoutReserve;
            this.joinCount = Math.max(1, Math.min(MAX_JOIN_COUNT, joinCount));
            this.prepareTimeIndex = clampPrepareIndex(prepareTimeIndex);
            this.windowWeekday = Math.max(1, Math.min(7, windowWeekday));
            this.windowStartMinute = Math.max(0, Math.min(1439, windowStartMinute));
            this.windowDurationMinutes = Math.max(1, Math.min(180, windowDurationMinutes));
            this.heroIds = heroIds == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(heroIds));
            this.squadIndex = Math.max(1, Math.min(6, squadIndex));
            this.preferredLeaderEnabled = preferredLeaderEnabled;
            this.preferredLeaderOnly = preferredLeaderOnly;
            this.preferredLeaderWaitSeconds = Math.max(0, Math.min(86400, preferredLeaderWaitSeconds));
            this.preferredLeaders = preferredLeaders == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(preferredLeaders));
        }

        int windowStartMinuteOfDay() {
            return windowStartMinute;
        }

        int windowDurationMinutes() {
            return windowDurationMinutes;
        }

        static BearConfig defaults() {
            return new BearConfig(true, true, false, true, false, false, 1,
                    DEFAULT_PREPARE_TIME_INDEX, 5, 21 * 60, ACTIVITY_MINUTES,
                    Collections.emptyList(), 1, false, false, 2, Collections.emptyList());
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
            // 2026-11-03：App「上车编队 1..N」按启用槽数决定本场上车数；未显式给 join_count 时
            // 跟随启用的 join_formations 槽数（无 join_formations → 仍默认 1，旧行为零变化）。
            List<JoinFormationConfig> joinSlots = parseJoinFormations(p);
            int enabledSlots = 0;
            for (JoinFormationConfig slot : joinSlots) {
                if (slot != null && slot.enabled) enabledSlots++;
            }
            int joinCountDefault = joinSlots.isEmpty() ? 1 : Math.max(1, enabledSlots);
            int joinCount = p.has("join_count")
                    ? p.optInt("join_count", joinCountDefault) : joinCountDefault;
            BearConfig cfg = new BearConfig(
                    optBool(p, "launch_enabled", true),
                    optBool(p, "join_enabled", true),
                    optBool(p, "prepare_recall", false),
                    optBool(p, "prep_recall", true),
                    optBool(p, "pet_skills", false),
                    optBool(p, "launch_without_reserve", false),
                    joinCount,
                    p.optInt("prepare_time_index", DEFAULT_PREPARE_TIME_INDEX),
                    p.optInt("window_weekday", 5),
                    startMinute, duration,
                    parseConfiguredHeroIds(p),
                    p.optInt("squad_index", 1),
                    optBool(p, "preferred_leader_enabled", false),
                    optBool(p, "preferred_leader_only", false),
                    p.optInt("preferred_leader_wait_seconds", 2),
                    parsePreferredLeaders(p));
            // 批次10：统一编队参数（无这些键 → formationConfigured=false → 完全旧行为）
            cfg.paramsMap.putAll(paramsToMap(p));
            cfg.formationConfigured = hasGenericFormationConfig(cfg.paramsMap);
            cfg.formationLibraryJson = p.optString("formation_library", "");
            cfg.joinFormations = joinSlots;
            return cfg;
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
        /** 批次5：发车重试/预热状态（按场次 windowStartSec 归一）。 */
        long retryWindowStart;
        long nextLaunchRetryAt;
        int downgradeRetries;
        int delayRetries;
        int lastLaunchCode;
        int launchPrepareIndexUsed;
        long preheatRefreshAt;
        String lastLaunchText = "";
        /** 批次11：上车槽跳过 FileLog 节流（同场次同槽只记一次；mod 每轮仍写）。 */
        long joinSkipWindowStart;
        String joinSkipSlots = "";
        /** 2026-11-07：本场已执行「开赛前 5 分钟召回/暂停」的场次起点（0=未执行）。 */
        long prepGateWindowStart;
        /** 2026-11-07：本场已开启宠物战斗技能的场次起点（0=未开启）。 */
        long petSkillsWindowStart;
        /**
         * 2026-11-08（对齐网页版 DailyBearActionService.recallPending）：本场召回的行军 id（逗号）。
         * 召回是异步的——全部回城（从行军列表消失）前不发车，否则必然 403 英雄占用。
         */
        String recallMarchIds = "";
        long recallWindowStart;
        /** 召回时的实时行军快照标记（queue.updatedAtMs；2026-11-11 对齐网页版 worldSnapshotMarker）。 */
        long recallSnapshotAt;
        /** 2026-11-11（对齐网页版 pendingJoins）：待对账上车请求（JSON 数组）。 */
        String pendingJoins = "";
        long pendingJoinWindowStart;
        /** 2026-11-11 指定车头等待（对齐网页版 preferredLeaderWait）。 */
        long preferredWaitWindowStart;
        long preferredWaitStartedAt;
        String preferredWaitContext = "";
        /** 召回等待起始（秒；超过 RECALL_WAIT_MAX_SECONDS 放弃等待）。 */
        long recallWaitStart;

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

    /** 角色状态后缀：uid>0 时按角色隔离（2026-11-07 修复同服多角色互相封板/共用上车台账）。 */
    static String roleScopedSuffix(TaskContext tc) {
        return roleScopedSuffix(tc.fpid, tc.sid, tc.roleUid);
    }

    /** 纯函数版角色后缀（便于 JVM 单测）。 */
    static String roleScopedSuffix(String fpid, long sid, long uid) {
        return (fpid == null ? "" : fpid) + ":" + sid + (uid > 0L ? ":" + uid : "");
    }

    static String launchedKeyOf(TaskContext tc) {
        return KEY_PREFIX + roleScopedSuffix(tc) + ":launched";
    }

    static String joinsKeyOf(TaskContext tc) {
        return KEY_PREFIX + roleScopedSuffix(tc) + ":joins";
    }

    static String stateKeyOf(TaskContext tc) {
        return KEY_PREFIX + roleScopedSuffix(tc) + ":state";
    }

    /** 当前角色键（窗口让位门/日志用）。 */
    static RoleKey roleKeyOf(TaskContext tc) {
        return new RoleKey(tc.fpid, tc.sid, tc.roleUid);
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
            o.put("retryWindowStart", state.retryWindowStart);
            o.put("nextLaunchRetryAt", state.nextLaunchRetryAt);
            o.put("downgradeRetries", state.downgradeRetries);
            o.put("delayRetries", state.delayRetries);
            o.put("lastLaunchCode", state.lastLaunchCode);
            o.put("launchPrepareIndexUsed", state.launchPrepareIndexUsed);
            o.put("preheatRefreshAt", state.preheatRefreshAt);
            o.put("joinSkipWindowStart", state.joinSkipWindowStart);
            o.put("recallSnapshotAt", state.recallSnapshotAt);
            o.put("pendingJoinWindowStart", state.pendingJoinWindowStart);
            o.put("preferredWaitWindowStart", state.preferredWaitWindowStart);
            o.put("preferredWaitStartedAt", state.preferredWaitStartedAt);
            if (state.preferredWaitContext != null && !state.preferredWaitContext.isEmpty()) {
                o.put("preferredWaitContext", state.preferredWaitContext);
            }
            if (state.pendingJoins != null && !state.pendingJoins.isEmpty()) {
                o.put("pendingJoins", state.pendingJoins);
            }
            o.put("prepGateWindowStart", state.prepGateWindowStart);
            o.put("petSkillsWindowStart", state.petSkillsWindowStart);
            o.put("recallWindowStart", state.recallWindowStart);
            o.put("recallWaitStart", state.recallWaitStart);
            if (state.recallMarchIds != null && !state.recallMarchIds.isEmpty()) {
                o.put("recallMarchIds", state.recallMarchIds);
            }
            if (state.joinSkipSlots != null && !state.joinSkipSlots.isEmpty()) {
                o.put("joinSkipSlots", state.joinSkipSlots);
            }
            if (state.lastLaunchText != null && !state.lastLaunchText.isEmpty()) {
                o.put("lastLaunchText", state.lastLaunchText);
            }
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
            state.retryWindowStart = o.optLong("retryWindowStart", 0);
            state.nextLaunchRetryAt = o.optLong("nextLaunchRetryAt", 0);
            state.downgradeRetries = o.optInt("downgradeRetries", 0);
            state.delayRetries = o.optInt("delayRetries", 0);
            state.lastLaunchCode = o.optInt("lastLaunchCode", 0);
            state.launchPrepareIndexUsed = o.optInt("launchPrepareIndexUsed", 0);
            state.preheatRefreshAt = o.optLong("preheatRefreshAt", 0);
            state.joinSkipWindowStart = o.optLong("joinSkipWindowStart", 0);
            state.recallSnapshotAt = o.optLong("recallSnapshotAt", 0);
            state.pendingJoinWindowStart = o.optLong("pendingJoinWindowStart", 0);
            state.preferredWaitWindowStart = o.optLong("preferredWaitWindowStart", 0);
            state.preferredWaitStartedAt = o.optLong("preferredWaitStartedAt", 0);
            state.preferredWaitContext = o.optString("preferredWaitContext", "");
            state.pendingJoins = o.optString("pendingJoins", "");
            state.prepGateWindowStart = o.optLong("prepGateWindowStart", 0);
            state.petSkillsWindowStart = o.optLong("petSkillsWindowStart", 0);
            state.recallWindowStart = o.optLong("recallWindowStart", 0);
            state.recallWaitStart = o.optLong("recallWaitStart", 0);
            state.recallMarchIds = o.optString("recallMarchIds", "");
            state.joinSkipSlots = o.optString("joinSkipSlots", "");
            state.lastLaunchText = o.optString("lastLaunchText", "");
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

    /** mod 文案收口：launchText 传 null 表示“执行体已写过更细的 mod.bear.launch”，不覆盖。 */
    private static void writeMod(TaskContext tc, String trapText, String windowText, String launchText) {
        try {
            tc.saveModText(TASK_ID, "trap", trapText == null ? "" : trapText);
            tc.saveModText(TASK_ID, "window", windowText == null ? "" : windowText);
            if (launchText != null) {
                tc.saveModText(TASK_ID, "launch", launchText);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 单写 mod.bear.launch（批次5：发车尝试/成败/重试计划一眼可读）。 */
    private static void writeLaunchMod(TaskContext tc, BearState state, String text) {
        String value = text == null ? "" : text;
        if (state != null) state.lastLaunchText = value;
        try {
            tc.saveModText(TASK_ID, "launch", value);
        } catch (Throwable ignored) {
        }
    }

    /** 单写 mod.bear.join（批次5：对账成员数/是否含自己）。 */
    private static void writeJoinMod(TaskContext tc, String text) {
        try {
            tc.saveModText(TASK_ID, "join", text == null ? "" : text);
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
