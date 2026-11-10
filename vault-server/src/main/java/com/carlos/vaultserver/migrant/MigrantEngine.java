package com.carlos.vaultserver.migrant;

import com.carlos.vault.wjdr.GameServerClock;
import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.asset.AssetSnapshot;
import com.carlos.vault.wjdr.asset.CoreAssetLibrary;
import com.carlos.vault.wjdr.asset.CoreAssetRegistry;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionControl;
import com.carlos.vaultserver.SessionKeeper;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.task.Exec;
import com.carlos.vaultserver.task.RoleKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 移民执行引擎（移植原版 {@code MigrantService} 1149 行 + {@code MigrantRetryExecutor}
 * + {@code MigrantPreparationRetrier}）。
 *
 * <p><b>执行模型（契约 §6.1）</b>：所有发协议的动作都经
 * {@code keeper.submitRoleJob(roleKey, "migrant.xxx", () -> ...)} 投递到<b>角色会话线程</b>，
 * 任务体内用 {@code keeper.roleEngine(roleKey)} 取引擎再 {@code request(engine, ...)}；
 * 其它线程绝不直接发请求（只读时钟/状态）。
 *
 * <p><b>调度</b>：{@link MigrantScheduler} 每秒调用 {@link #startPrepare}/{@link #startFire}；
 * 准备窗口 = 开放前 15 分钟，就绪目标 = 开放前 2 分钟，抢位 = 开放边界前
 * {@code advance_millis}（0..100）起、50ms 节拍最多 200 次、提交窗口 15 秒、
 * 结果对账 90 秒。
 */
public final class MigrantEngine {

    /** 通用协议请求超时（秒）。 */
    private static final int REQUEST_TIMEOUT_SEC = 8;
    /** 等待登录初始化推送的时长。 */
    private static final long INIT_WAIT_MILLIS = 15_000L;
    /** 角色级调用（HTTP 线程等待）超时。 */
    private static final long CALL_TIMEOUT_MILLIS = 60_000L;
    /** 发射前提前拉起的余量：调度器在边界前 2 秒投递发射任务。 */
    public static final long FIRE_LEAD_MILLIS = 2_000L;
    /** 登录拉起节流（同一任务两次 login 至少间隔）。 */
    private static final long LOGIN_THROTTLE_MILLIS = 30_000L;
    /** 准备阶段重试间隔。 */
    private static final long PREPARE_RETRY_MILLIS = 5_000L;
    /** 单任务加速请求上限（防失控）。 */
    private static final int MAX_SPEEDUP_REQUESTS = 30;
    /** 准备阶段单轮等待登录初始化数据的上限（分轮短任务，不再长阻塞会话线程）。 */
    private static final long INIT_WAIT_SHORT_MILLIS = 5_000L;
    /** 准备阶段召回后留给服务端回城数据刷新的间隔（交给调度器下一轮）。 */
    private static final long RECALL_SETTLE_MILLIS = 3_000L;
    /** 延迟复核节点：开放后 +3min / +10min（原版 REVERIFY_DELAYS_MILLIS）。 */
    private static final long[] REVERIFY_DELAYS_MILLIS = {180_000L, 600_000L};
    /** 延迟复核只在该时间窗内进行（原版 RESTORE_REVERIFY_WINDOW_MILLIS）。 */
    private static final long REVERIFY_WINDOW_MILLIS = 21_600_000L;
    /** 看门狗：准备任务体卡死上限。 */
    public static final long PREPARE_STUCK_MILLIS = 180_000L;
    /** 看门狗：发射任务体卡死上限（等边界 2s + 抢位 15s + 余量）。 */
    public static final long FIRE_STUCK_MILLIS = 120_000L;
    /** 抢占位避让：角色忙时提前提交发射任务的时间。 */
    public static final long FIRE_LEAD_BUSY_MILLIS = 30_000L;

    private final SessionKeeper keeper;
    private final VaultStore store;
    private final MigrantJobStore jobs;
    private final StateStore stateStore;

    private final Map<String, MigrantState> states = new ConcurrentHashMap<>();
    private final Map<String, WjdrEngine> stateEngines = new ConcurrentHashMap<>();
    /** 正在角色线程上执行的任务（防重复投递）。 */
    private final java.util.Set<String> running = ConcurrentHashMap.newKeySet();
    /** 各任务开始占用的时刻（看门狗判卡死用）。 */
    private final Map<String, Long> runningSince = new ConcurrentHashMap<>();
    private final Object createLock = new Object();

    private final MigrantRetryExecutor retryExecutor = new MigrantRetryExecutor();

    public MigrantEngine(SessionKeeper keeper, VaultStore store, MigrantJobStore jobs,
                         StateStore stateStore) {
        this.keeper = keeper;
        this.store = store;
        this.jobs = jobs;
        this.stateStore = stateStore;
        recoverJobs();
    }

    // =====================================================================
    // 角色 / 时钟 / 状态基础设施
    // =====================================================================

    /** 角色键（fpid|sid|uid）。 */
    public static RoleKey roleOf(MigrantJob job) {
        return new RoleKey(job.fpid, job.sid, job.uid);
    }

    /** 角色游戏服时钟（未在线返回 null）。抢位等待用它的 {@code waitUntil}。 */
    public GameServerClock serverClock(RoleKey role) {
        WjdrEngine engine = keeper == null ? null : keeper.roleEngine(role);
        return engine == null ? null : engine.serverClock();
    }

    /** 角色当前服务器毫秒：已校准用游戏服时钟，否则本机墙钟兜底（clock_source 语义）。 */
    public long serverNowMillis(RoleKey role) {
        GameServerClock clock = serverClock(role);
        if (clock != null && clock.locked()) return clock.nowServerMs();
        return System.currentTimeMillis();
    }

    /** 任务视角的服务器时间（调度判定用；角色离线回落墙钟）。 */
    public long jobServerNowMillis(MigrantJob job) {
        return serverNowMillis(roleOf(job));
    }

    /** clock_source：role_clock / role_clock_unsynced / host_wall_clock。 */
    private String clockSourceOf(RoleKey role) {
        WjdrEngine engine = keeper == null ? null : keeper.roleEngine(role);
        if (engine == null) return "host_wall_clock";
        return engine.serverClock().locked() ? "role_clock" : "role_clock_unsynced";
    }

    private boolean roleClockReady(RoleKey role) {
        GameServerClock clock = serverClock(role);
        return clock != null && clock.locked();
    }

    /** 绑定角色引擎的消息钩子并回灌引擎已缓存的推送（登录初始化数据）。 */
    private MigrantState attachState(RoleKey role, WjdrEngine engine) {
        String key = role.key();
        MigrantState state = states.get(key);
        if (state == null) {
            MigrantState fresh = new MigrantState();
            MigrantState prev = states.putIfAbsent(key, fresh);
            state = prev == null ? fresh : prev;
        }
        if (engine != null && stateEngines.get(key) != engine) {
            stateEngines.put(key, engine);
            final MigrantState target = state;
            engine.addMessageHook((proto, content) -> {
                try {
                    target.handle(proto, content);
                } catch (Throwable ignored) {
                    // 钩子在 inbound 线程：解析异常绝不外抛
                }
            });
            seedFromEngineCache(state, engine);
        }
        return state;
    }

    /** 把引擎缓存的最近一次推送回灌进状态（角色长期在线时钩子可能错过登录推送）。 */
    private void seedFromEngineCache(MigrantState state, WjdrEngine engine) {
        if (state == null || engine == null) return;
        String[] protos = {
                "loginauth", "notify_base_info", "notify_migrant_init",
                "notify_migrant_first_login", "notify_alliance_init",
                "notify_alliance_player_update", "notify_alliance_update",
                "notify_backpack_init", "notify_world_player_init", "notify_game_init",
                "notify_server_time", "notify_supremacy_war_player_init"
        };
        for (String proto : protos) {
            try {
                Map<String, Object> content = engine.notifyContent(proto);
                if (content != null) state.handle(proto, content);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用账号库角色表补齐同账号目标王国角色数（原版 role_list 的等价来源）。 */
    private void seedRoleCounts(MigrantState state, RoleKey role) {
        if (state == null || store == null || role == null) return;
        Map<Long, Integer> counts = new LinkedHashMap<>();
        List<VaultStore.RoleEntry> roles = store.roles(role.fpid);
        for (VaultStore.RoleEntry entry : roles) {
            if (entry == null || entry.uid == null || entry.uid.isEmpty()) continue;
            long sid;
            try {
                sid = Long.parseLong(entry.sid);
            } catch (RuntimeException e) {
                continue;
            }
            if (sid <= 0L) continue;
            Integer prev = counts.get(sid);
            counts.put(sid, prev == null ? 1 : prev + 1);
        }
        state.seedRoleCounts(counts);
    }

    /** 等待登录初始化数据（最多 waitMillis；不强制失败，交给 evaluate 报告缺项）。 */
    private void waitForInit(MigrantState state, WjdrEngine engine, long waitMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
        while (System.currentTimeMillis() < deadline) {
            MigrantState.Snapshot snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
            if (snap.baseInitialized && snap.migrantInitialized && snap.backpackInitialized
                    && snap.allianceInitialized) {
                return;
            }
            seedFromEngineCache(state, engine);
            sleepQuietly(200L);
        }
    }

    private long serverNowMillisOf(WjdrEngine engine) {
        if (engine == null) return System.currentTimeMillis();
        GameServerClock clock = engine.serverClock();
        return clock.locked() ? clock.nowServerMs() : System.currentTimeMillis();
    }

    private boolean clockReadyOf(WjdrEngine engine) {
        return engine != null && engine.serverClock().locked();
    }

    /** 统一协议请求：把 WjdrEngine.request 的受检异常归一为运行时异常（中文消息）。 */
    private static GameMessage request(WjdrEngine engine, String proto, Map<String, Object> args,
                                       int timeoutSec) {
        try {
            return engine.request(proto, args, timeoutSec);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(normalizedMessage(e), e);
        }
    }

    /** 发一次请求并把成功响应记录进状态（原版 requestAndRecord）。 */
    private GameMessage requestAndRecord(WjdrEngine engine, MigrantState state, String proto,
                                         Map<String, Object> args) {
        GameMessage rsp = request(engine, proto, args, REQUEST_TIMEOUT_SEC);
        int code = Exec.codeOf(rsp);
        if (code != 0) {
            throw new IllegalStateException(proto + " 响应 code=" + code + "（" + describeCode(code) + "）");
        }
        if (rsp != null) state.recordResponse(proto, rsp.getContent());
        return rsp;
    }

    // =====================================================================
    // 启动恢复
    // =====================================================================

    /** 服务重启恢复：SENDING/FIRING 阶段无法证明结果 → UNKNOWN；其余活跃任务回 WAITING。 */
    private void recoverJobs() {
        boolean changed = false;
        for (MigrantJob job : jobs.all()) {
            try {
                if (restoreStatus(job, jobServerNowMillis(job))) changed = true;
                if (MigrantJob.UNKNOWN.equals(job.status)) {
                    if (scheduleReverifyAfter(job)) changed = true;
                }
            } catch (RuntimeException e) {
                FileLog.warn("移民任务恢复失败 job={}：{}", job.jobId, e.getMessage());
            }
        }
        if (changed) jobs.markDirty();
        FileLog.info("移民任务恢复完成：共 {} 个任务，活跃 {} 个", String.valueOf(jobs.count()),
                String.valueOf(countActive()));
    }

    private int countActive() {
        int n = 0;
        for (MigrantJob job : jobs.all()) if (MigrantJob.isActive(job.status)) n++;
        return n;
    }

    /**
     * 单任务恢复状态归一（纯逻辑，单测覆盖）。
     *
     * @return 是否改动了任务
     */
    static boolean restoreStatus(MigrantJob job, long nowMillis) {
        if (job == null || MigrantJob.isTerminal(job.status)) return false;
        // P1-3：只要已经发出过真实请求，重启后就不能回到 WAITING 重发（结果无法证明）
        if (job.attemptHistorySize() > 0 || job.attemptCount > 0) {
            job.finish(MigrantJob.UNKNOWN,
                    "重启前已发出迁移请求，无法证明请求结果，已禁止自动重发，需人工核验");
            return true;
        }
        if (MigrantJob.FIRING.equals(job.status)) {
            job.finish(MigrantJob.UNKNOWN,
                    "程序在最终请求或结果对账阶段重启，无法证明请求结果，已禁止自动重发");
            return true;
        }
        if (job.freedomEndMillis > 0L && nowMillis >= job.freedomEndMillis) {
            job.finish(MigrantJob.UNKNOWN, "已过自由移民窗口且未取得结果，需人工核验");
            return true;
        }
        job.status = MigrantJob.WAITING;
        job.preparationStartedAtMillis = 0L;
        job.sentAtMillis = 0L;
        job.armedAtMillis = 0L;
        job.responseCode = null;
        if (job.error == null || job.error.trim().isEmpty() || "服务重启".equals(job.error)) {
            job.error = "服务重启后恢复等待";
        }
        job.touch();
        return true;
    }

    // =====================================================================
    // 角色级调用（HTTP 线程 → 角色会话线程）
    // =====================================================================

    @FunctionalInterface
    private interface RoleCall<T> {
        T run() throws Exception;
    }

    private <T> T callInRole(RoleKey role, String tag, long timeoutMillis, RoleCall<T> call) {
        if (keeper == null) throw new IllegalStateException("会话管理器未就绪");
        if (role == null || !role.valid()) throw new IllegalStateException("角色身份不完整");
        if (!keeper.roleOnline(role)) throw new IllegalStateException("角色未上线，请先上线该角色");
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        boolean submitted = keeper.submitRoleJob(role, tag, () -> {
            try {
                result[0] = call.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        }, () -> {
            failure[0] = new IllegalStateException("角色会话掉线，操作未执行");
            latch.countDown();
        });
        if (!submitted) throw new IllegalStateException("角色会话不可用（离线或已暂停）");
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("角色操作等待超时（" + timeoutMillis + "ms）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("角色操作等待被中断", e);
        }
        if (failure[0] != null) {
            if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
            throw new IllegalStateException(String.valueOf(failure[0]), failure[0]);
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    // =====================================================================
    // 概览 / 预检 / 提前测试
    // =====================================================================

    /** 概览（网页版字段 1:1）；角色离线返回 null（App 显示「未上线」并禁用表单）。 */
    public JSONObject overview(RoleKey role) {
        if (keeper == null || !keeper.roleOnline(role)) return null;
        return callInRole(role, "migrant.overview", CALL_TIMEOUT_MILLIS, () -> overviewBody(role));
    }

    private JSONObject overviewBody(RoleKey role) {
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        waitForInit(state, engine, 10_000L);
        seedRoleCounts(state, role);
        try {
            requestAndRecord(engine, state, MigrantRules.PROTO_KINGDOM_LIST,
                    Collections.<String, Object>emptyMap());
        } catch (RuntimeException e) {
            FileLog.warn("移民概览刷新王国列表失败 role={}：{}", role.key(), e.getMessage());
        }
        MigrantState.Snapshot snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
        JSONObject out = new JSONObject();
        out.put("account_role_id", role.uid);
        out.put("account_name", roleName(role));
        out.put("current_kid", snap.currentKid);
        out.put("server_now_millis", snap.serverNowMillis);
        out.put("clock_source", clockSourceOf(role));
        boolean activitySupported = snap.activityId == MigrantRules.SUPPORTED_ACTIVITY_ID
                && snap.activityStartSeconds > 0L
                && snap.activityEndSeconds - snap.activityStartSeconds
                == MigrantRules.ACTIVITY_DURATION_SECONDS
                && snap.standardPoints > 0L;
        long nowSec = snap.serverNowMillis / 1000L;
        boolean activityOpen = activitySupported && nowSec >= snap.activityStartSeconds
                && nowSec < snap.activityEndSeconds;
        out.put("activity_open", activityOpen);
        out.put("state_complete", snap.complete());
        out.put("state", publicPhase(MigrantRules.phase(snap, nowSec)));
        out.put("message", overviewMessage(snap, activityOpen));
        out.put("freedom_start_millis", snap.activityStartSeconds <= 0L ? 0L
                : (snap.activityStartSeconds + MigrantRules.FREEDOM_START_OFFSET_SECONDS) * 1000L);
        out.put("end_millis", snap.activityEndSeconds <= 0L ? 0L : snap.activityEndSeconds * 1000L);
        out.put("activity", activityJson(snap));
        out.put("kingdoms", kingdomsJson(snap));
        return out;
    }

    /** 契约 §3.2 的公开阶段名（网页版内部为 PROSPECTS，契约写作 PROSPECT）。 */
    private static String publicPhase(String phase) {
        return "PROSPECTS".equals(phase) ? "PROSPECT" : phase;
    }

    private JSONObject activityJson(MigrantState.Snapshot snap) {
        JSONObject activity = new JSONObject();
        activity.put("id", snap.activityId);
        activity.put("serial_no", snap.activitySerialNo);
        activity.put("config_version", MigrantRules.CONFIG_VERSION);
        activity.put("start_millis",
                snap.activityStartSeconds > 0L ? snap.activityStartSeconds * 1000L : 0L);
        activity.put("freedom_start_millis", snap.activityStartSeconds > 0L
                ? (snap.activityStartSeconds + MigrantRules.FREEDOM_START_OFFSET_SECONDS) * 1000L : 0L);
        activity.put("end_millis",
                snap.activityEndSeconds > 0L ? snap.activityEndSeconds * 1000L : 0L);
        return activity;
    }

    private String overviewMessage(MigrantState.Snapshot snap, boolean activityOpen) {
        if (!snap.complete()) {
            List<String> missing = new ArrayList<>();
            if (!snap.baseInitialized) missing.add("角色基础信息");
            if (!snap.migrantInitialized) missing.add("移民活动");
            if (!snap.backpackInitialized) missing.add("背包");
            if (!snap.allianceInitialized) missing.add("联盟");
            return "尚未同步：" + String.join("、", missing);
        }
        if (!activityOpen) return "当前不在本轮移民活动时间内";
        long nowSec = snap.serverNowMillis / 1000L;
        String phase = MigrantRules.phase(snap, nowSec);
        if ("PROSPECTS".equals(phase)) return "当前为展望阶段，任务将在自由移民开放时自动执行";
        if ("INVITATION".equals(phase)) return "当前为邀请阶段，任务将在自由移民开放时自动执行";
        if ("FREEDOM".equals(phase)) return "自由移民已开放";
        return "本轮移民活动已结束";
    }

    private JSONArray kingdomsJson(MigrantState.Snapshot snap) {
        List<MigrantState.KingdomInfo> list = new ArrayList<>(snap.kingdoms.values());
        Collections.sort(list, new Comparator<MigrantState.KingdomInfo>() {
            @Override
            public int compare(MigrantState.KingdomInfo a, MigrantState.KingdomInfo b) {
                return Long.compare(a.kid, b.kid);
            }
        });
        JSONArray arr = new JSONArray();
        for (MigrantState.KingdomInfo kingdom : list) {
            if (kingdom.kid == snap.currentKid) continue;
            if (!snap.kidOpenTimes.containsKey(kingdom.kid)) continue;
            JSONObject item = new JSONObject();
            item.put("kid", kingdom.kid);
            item.put("name", kingdom.name);
            item.put("label", "K" + kingdom.kid
                    + (kingdom.name == null || kingdom.name.trim().isEmpty() ? "" : " " + kingdom.name));
            item.put("state", kingdom.state);
            item.put("power_limit", kingdom.powerLimit);
            item.put("power_limit_value", powerLimitValue(snap, kingdom));
            item.put("migrant_num", kingdom.migrantNum);
            item.put("normal_migrant_num", kingdom.normalMigrantNum);
            item.put("remaining_quota", remainingQuota(kingdom));
            arr.put(item);
        }
        return arr;
    }

    private long powerLimitValue(MigrantState.Snapshot snap, MigrantState.KingdomInfo kingdom) {
        return MigrantRules.powerLimitValue(snap, kingdom.kid);
    }

    private long remainingQuota(MigrantState.KingdomInfo kingdom) {
        return MigrantRules.remainingQuota(kingdom);
    }

    /**
     * 资格预检（创建前调用）。
     *
     * <p>P2-4：召回/加速/提前量入参必须参与预检结论，与创建后实际执行一致；
     * autoExitAlliance 时联盟项由准备窗口自动执行、不阻断。
     */
    public JSONObject precheck(RoleKey role, long targetKid, boolean autoExitAlliance,
                               boolean recallBeforeOpen, boolean recallSpeedup,
                               boolean recallDiamondSpeedup, int advanceMillis) {
        final long kid = checkedTargetKid(targetKid);
        EvalResult result = callInRole(role, "migrant.precheck", CALL_TIMEOUT_MILLIS,
                () -> evaluateInRole(role, kid, true));
        MigrantRules.Evaluation evaluation = result.evaluation.withAutoExitAlliance(autoExitAlliance);
        int advance = MigrantJob.clampAdvance(advanceMillis);
        JSONObject out = evaluation.toJson();
        out.put("account_role_id", role.uid);
        out.put("account_name", roleName(role));
        out.put("current_kid", result.snapshot.currentKid);
        out.put("activity_open", activityOpen(result.snapshot));
        out.put("server_now_millis", result.snapshot.serverNowMillis);
        out.put("recall_before_open", recallBeforeOpen);
        out.put("recall_speedup", recallSpeedup);
        out.put("recall_diamond_speedup", recallDiamondSpeedup);
        out.put("advance_millis", advance);
        JSONArray warnings = out.optJSONArray("warnings");
        if (warnings == null) {
            warnings = new JSONArray();
            out.put("warnings", warnings);
        }
        for (String warning : precheckOptionWarnings(recallBeforeOpen, recallSpeedup, advance)) {
            warnings.put(warning);
        }
        return out;
    }

    /** 预检选项提示（纯函数，单测覆盖）：与实际执行策略一致，不产生虚假“已开启”结论。 */
    static List<String> precheckOptionWarnings(boolean recallBeforeOpen, boolean recallSpeedup,
                                               int advanceMillis) {
        List<String> out = new ArrayList<>();
        if (!recallBeforeOpen) {
            out.add("未开启开放前召回：在外部队可能晚于自由移民开放边界回城，请自行确认");
        }
        if (!recallBeforeOpen && recallSpeedup) {
            out.add("未开启开放前召回，行军加速设置不会执行");
        }
        if (advanceMillis > 0) {
            out.add("将比开放边界提前 " + advanceMillis + " 毫秒开始抢位（服务端钳位 0..100）");
        }
        return out;
    }

    /** 目标王国 id：必须 >0 且不超过 int 上限（任务字段 target_kid 为 int）。 */
    static int checkedTargetKid(long targetKid) {
        if (targetKid <= 0L) throw new IllegalArgumentException("请选择目标王国");
        if (targetKid > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("目标王国 id 超出范围");
        }
        return (int) targetKid;
    }

    /** 提前测试：真实发送一次 req_migrant_migrate（危险语义保留）。 */
    public JSONObject testEarly(RoleKey role, long rawTargetKid) {
        final long targetKid = checkedTargetKid(rawTargetKid);
        return callInRole(role, "migrant.test-early", CALL_TIMEOUT_MILLIS, () -> {
            WjdrEngine engine = keeper.roleEngine(role);
            if (engine == null) throw new IllegalStateException("角色引擎不可用");
            MigrantState state = attachState(role, engine);
            waitForInit(state, engine, INIT_WAIT_MILLIS);
            seedRoleCounts(state, role);
            refreshKingdoms(engine, state, targetKid);
            MigrantState.Snapshot snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
            MigrantRules.Evaluation evaluation = MigrantRules.evaluate(snap, targetKid);
            if (!evaluation.ready) {
                throw new IllegalStateException(MigrantRules.firstFailure(evaluation));
            }
            long serverNow = snap.serverNowMillis;
            if (serverNow >= evaluation.freedomStartMillis - MigrantRules.EARLY_TEST_SAFETY_MARGIN_MILLIS) {
                throw new IllegalStateException(
                        "距离自由移民开放不足 10 秒，已禁止提前测试，请使用正式任务执行");
            }
            FileLog.info("移民危险动作 test-early fpid={} uid={} target_kid={} freedom_start={}",
                    role.fpid, String.valueOf(role.uid), String.valueOf(targetKid),
                    String.valueOf(evaluation.freedomStartMillis));
            JSONObject out = new JSONObject();
            out.put("account_role_id", role.uid);
            out.put("target_kid", targetKid);
            out.put("freedom_start_millis", evaluation.freedomStartMillis);
            out.put("tested_at_millis", System.currentTimeMillis());
            try {
                GameMessage rsp = request(engine, MigrantRules.PROTO_MIGRATE,
                        Exec.args("kid", targetKid), REQUEST_TIMEOUT_SEC);
                int code = Exec.codeOf(rsp);
                out.put("response_code", code);
                out.put("accepted", code == 0);
                if (code == 0) {
                    sleepQuietly(500L);
                    long kid = state.currentKid();
                    out.put("reconciled_kid", kid);
                    out.put("message", kid == targetKid
                            ? "服务端提前接受迁入请求，重新登录后已确认进入目标王国"
                            : "服务端返回 code=0，但重新登录后未进入目标王国，请立即人工核验");
                } else {
                    out.put("message", "服务端拒绝：错误码 " + code + "（" + describeCode(code) + "）");
                }
            } catch (RuntimeException e) {
                out.put("accepted", false);
                out.put("response_code", JSONObject.NULL);
                out.put("message", "测试请求未获得明确业务错误码：" + normalizedMessage(e));
            }
            return out;
        });
    }

    private boolean activityOpen(MigrantState.Snapshot snap) {
        boolean supported = snap.activityId == MigrantRules.SUPPORTED_ACTIVITY_ID
                && snap.activityStartSeconds > 0L
                && snap.activityEndSeconds - snap.activityStartSeconds
                == MigrantRules.ACTIVITY_DURATION_SECONDS
                && snap.standardPoints > 0L;
        long nowSec = snap.serverNowMillis / 1000L;
        return supported && nowSec >= snap.activityStartSeconds && nowSec < snap.activityEndSeconds;
    }

    // =====================================================================
    // 创建 / 取消 / 查询
    // =====================================================================

    /** 创建单个任务（同角色本轮已有活跃任务时拒绝）。 */
    public MigrantJob create(RoleKey role, Map<String, Object> payload) {
        synchronized (createLock) {
            long targetKid = checkedTargetKid(longValue(payload.get("target_kid"), 0L));
            boolean autoExit = boolValue(payload.get("auto_exit_alliance"), true);
            boolean recallBeforeOpen = boolValue(payload.get("recall_before_open"), true);
            boolean recallSpeedup = boolValue(payload.get("recall_speedup"), true);
            boolean recallDiamondSpeedup = boolValue(payload.get("recall_diamond_speedup"), true);
            int advance = MigrantJob.clampAdvance((int) longValue(payload.get("advance_millis"), 0L));

            EvalResult result = callInRole(role, "migrant.create", CALL_TIMEOUT_MILLIS,
                    () -> evaluateInRole(role, targetKid, true));
            MigrantRules.Evaluation evaluation =
                    result.evaluation.withAutoExitAlliance(autoExit);
            if (!evaluation.ready) {
                throw new IllegalStateException(MigrantRules.firstFailure(evaluation));
            }
            MigrantState.Snapshot snap = result.snapshot;
            ensureNoActiveJob(role, snap.activitySerialNo);

            MigrantJob job = new MigrantJob();
            job.jobId = UUID.randomUUID().toString();
            job.fpid = role.fpid;
            job.sid = role.sid;
            job.uid = role.uid;
            job.accountRoleId = role.uid;
            job.accountName = roleName(role);
            job.originalKid = (int) snap.currentKid;
            job.targetKid = (int) targetKid;
            job.targetName = evaluation.targetName;
            job.activityId = snap.activityId;
            job.activitySerialNo = snap.activitySerialNo;
            job.freedomStartMillis = evaluation.freedomStartMillis;
            job.advanceMillis = advance;
            job.requiredPermits = evaluation.permitsRequired;
            job.configVersion = evaluation.configVersion;
            job.createdAtMillis = System.currentTimeMillis();
            job.updatedAtMillis = job.createdAtMillis;
            job.preparationStartMillis = evaluation.freedomStartMillis > 0L
                    ? evaluation.freedomStartMillis - MigrantRules.PREPARE_WINDOW_MILLIS : 0L;
            job.recallBeforeOpen = recallBeforeOpen;
            job.recallReady = !recallBeforeOpen;
            job.recallSpeedup = recallSpeedup;
            job.recallDiamondSpeedup = recallDiamondSpeedup;
            job.autoExitAlliance = autoExit;
            job.precheck = evaluation.toJson();
            job.clockSource = clockSourceOf(role);
            GameServerClock clock = serverClock(role);
            if (clock != null && clock.locked()) {
                job.clockEpochMillis = clock.lastServerSec() * 1000L;
            }
            job.status = MigrantJob.WAITING;
            jobs.put(job);
            jobs.flushIfDirty();
            FileLog.info("移民危险动作 create fpid={} uid={} job={} target_kid={} advance_millis={} "
                            + "auto_exit_alliance={} recall_before_open={} recall_speedup={} "
                            + "recall_diamond_speedup={}",
                    role.fpid, String.valueOf(role.uid), job.jobId, String.valueOf(targetKid),
                    String.valueOf(advance), String.valueOf(autoExit),
                    String.valueOf(recallBeforeOpen), String.valueOf(recallSpeedup),
                    String.valueOf(recallDiamondSpeedup));
            return job;
        }
    }

    private void ensureNoActiveJob(RoleKey role, long activitySerialNo) {
        for (MigrantJob existing : jobs.all()) {
            if (!MigrantJob.isActive(existing.status)) continue;
            if (!existing.fpid.equals(role.fpid)) continue;
            if (existing.uid != role.uid) continue;
            if (activitySerialNo > 0L && existing.activitySerialNo > 0L
                    && existing.activitySerialNo != activitySerialNo) {
                continue;
            }
            throw new IllegalStateException("该账号在本轮移民活动中已有等待或执行中的任务");
        }
    }

    /** 取消任务（仅 WAITING 可取消）。 */
    public JSONObject cancel(String jobId) {
        MigrantJob job = jobs.get(jobId);
        if (job == null) throw new IllegalStateException("移民任务不存在");
        synchronized (job) {
            if (!MigrantJob.isCancelable(job.status)) {
                throw new IllegalStateException("任务已进入准备或执行阶段，不能取消");
            }
            job.finish(MigrantJob.CANCELLED, "用户取消任务");
        }
        jobs.markDirty();
        jobs.flushIfDirty();
        FileLog.info("移民危险动作 cancel fpid={} uid={} job={} target_kid={}",
                job.fpid, String.valueOf(job.uid), job.jobId, String.valueOf(job.targetKid));
        JSONObject out = new JSONObject();
        out.put("message", "移民任务已取消");
        out.put("job", job.toJson());
        return out;
    }

    public MigrantJob findJob(String jobId) {
        return jobs.get(jobId);
    }

    /** 按 fpid 过滤任务（active 按开放时间升序，finished 按更新时间倒序，最多 50）。 */
    public List<MigrantJob> listForFpid(String fpid) {
        List<MigrantJob> out = new ArrayList<>();
        for (MigrantJob job : jobs.all()) {
            if (fpid == null || fpid.isEmpty() || job.fpid.equals(fpid)) out.add(job);
        }
        return out;
    }

    public JSONObject jobsJson(String fpid) {
        List<MigrantJob> active = new ArrayList<>();
        List<MigrantJob> finished = new ArrayList<>();
        for (MigrantJob job : listForFpid(fpid)) {
            if (MigrantJob.isActive(job.status)) active.add(job);
            else finished.add(job);
        }
        Collections.sort(active, new Comparator<MigrantJob>() {
            @Override
            public int compare(MigrantJob a, MigrantJob b) {
                return Long.compare(a.freedomStartMillis, b.freedomStartMillis);
            }
        });
        Collections.sort(finished, new Comparator<MigrantJob>() {
            @Override
            public int compare(MigrantJob a, MigrantJob b) {
                return Long.compare(b.updatedAtMillis, a.updatedAtMillis);
            }
        });
        JSONArray activeArr = new JSONArray();
        for (MigrantJob job : active) activeArr.put(job.toJson());
        JSONArray finishedArr = new JSONArray();
        for (int i = 0; i < finished.size() && i < 50; i++) finishedArr.put(finished.get(i).toJson());
        JSONObject out = new JSONObject();
        out.put("active", activeArr);
        out.put("finished", finishedArr);
        return out;
    }

    // =====================================================================
    // 召回 / 行军
    // =====================================================================

    /** 行军详情（网页版 marches 字段 1:1）；离线返回 online=false 的空表。 */
    public JSONObject marches(MigrantJob job) {
        RoleKey role = roleOf(job);
        if (keeper == null || !keeper.roleOnline(role)) return offlineMarches(job);
        try {
            return callInRole(role, "migrant.marches", CALL_TIMEOUT_MILLIS, () -> marchesBody(job));
        } catch (RuntimeException e) {
            JSONObject out = offlineMarches(job);
            out.put("online", false);
            out.put("manual_recall_block_reason", "读取部队失败：" + normalizedMessage(e));
            out.put("recall_message", normalizedMessage(e));
            return out;
        }
    }

    private JSONObject offlineMarches(MigrantJob job) {
        JSONObject out = new JSONObject();
        out.put("online", false);
        out.put("manual_recall_allowed", false);
        out.put("manual_recall_block_reason", "账号未上线：上线后可读取每支部队的回城时间并手动召回");
        out.put("recall_before_open", job.recallBeforeOpen);
        out.put("recall_ready", job.recallReady);
        out.put("non_queue_march_count", 0);
        out.put("preparation_deadline_millis", preparationDeadline(job));
        out.put("marches", new JSONArray());
        out.put("recall_message", job.recallMessage);
        return out;
    }

    private int nonQueueCount(List<MarchView> marches) {
        int n = 0;
        for (MarchView march : marches) {
            if (!march.rally()) n++;
        }
        return n;
    }

    private long preparationDeadline(MigrantJob job) {
        return job.freedomStartMillis > 0L
                ? job.freedomStartMillis - MigrantRules.READY_BEFORE_MILLIS : 0L;
    }

    private JSONObject marchesBody(MigrantJob job) {
        RoleKey role = roleOf(job);
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        long serverNow = serverNowMillisOf(engine);
        List<MarchView> list = readMarches(engine, state, role.uid, serverNow);
        job.marchCount = list.size();
        job.latestMarchEndMillis = latestEnd(list);
        jobs.markDirty();

        boolean manualAllowed = job.freedomStartMillis <= 0L || serverNow < job.freedomStartMillis;
        String blockReason = manualAllowed ? ""
                : "任务已进入迁移执行阶段，不能手动召回";
        JSONArray arr = new JSONArray();
        for (MarchView march : list) {
            arr.put(marchJson(march, state, serverNow));
        }
        JSONObject out = new JSONObject();
        out.put("online", true);
        out.put("manual_recall_allowed", manualAllowed);
        out.put("manual_recall_block_reason", blockReason);
        out.put("recall_before_open", job.recallBeforeOpen);
        out.put("recall_ready", job.recallReady);
        out.put("non_queue_march_count", nonQueueCount(list));
        out.put("preparation_deadline_millis", preparationDeadline(job));
        out.put("marches", arr);
        out.put("recall_message", job.recallMessage);
        return out;
    }

    private JSONObject marchJson(MarchView march, MigrantState state, long serverNow) {
        JSONObject o = new JSONObject();
        o.put("march_id", march.id);
        o.put("type", marchTypeLabel(march.type));
        o.put("state", marchStateLabel(march.state));
        o.put("state_code", march.state);
        o.put("x", march.endX);
        o.put("y", march.endY);
        o.put("end_millis", march.endMillis);
        o.put("remaining_millis", Math.max(0L, march.endMillis - serverNow));
        o.put("recalled", march.state == 4L);
        o.put("rally", march.rally());
        boolean recallable = march.recallable();
        o.put("recallable", recallable);
        o.put("recall_disabled_reason", recallable ? "" : marchDisabledReason(march));
        boolean requiresItem = march.state == 2L;
        o.put("requires_recall_item", requiresItem);
        o.put("recall_action", march.state == 3L ? "撤回" : "召回");
        o.put("recall_item_name", requiresItem ? "出征召回" : "");
        o.put("recall_item_count", state == null ? 0L : state.itemCount(MigrantRules.RECALL_ITEM_ID));
        o.put("recall_item_synced", state != null && state.backpackSynced());
        return o;
    }

    private String marchDisabledReason(MarchView march) {
        if (march.rally()) return "集结行军不支持撤回或召回";
        if (march.state == 4L) return "部队已经在返回途中";
        if (march.state != 2L && march.state != 3L) return "当前状态不可召回";
        return "";
    }

    private String marchTypeLabel(long type) {
        if (type == 1L) return "侦查";
        if (type == 2L) return "进攻";
        if (type == 3L) return "狩猎";
        if (type == 4L) return "采集";
        if (type == 5L) return "驻防";
        if (type == 99L || type == 100L || type == 102L) return "集结";
        return "远征";
    }

    private String marchStateLabel(long state) {
        if (state == 2L) return "在途";
        if (state == 3L) return "执行中";
        if (state == 4L) return "回城中";
        return "未知";
    }

    /**
     * 手动召回（网页版 recall 字段 1:1）。
     *
     * @param marchId 0 = 全部
     * @param mode    item（只用手上的道具）/ diamond（不足时钻石购买）
     */
    public JSONObject recall(MigrantJob job, long marchId, String mode) {
        String normalizedMode = mode == null ? "item" : mode.trim().toLowerCase();
        if (!"item".equals(normalizedMode) && !"diamond".equals(normalizedMode)) {
            throw new IllegalArgumentException("召回方式只能是 item 或 diamond");
        }
        return callInRole(roleOf(job), "migrant.recall", CALL_TIMEOUT_MILLIS,
                () -> recallBody(job, marchId, normalizedMode));
    }

    private JSONObject recallBody(MigrantJob job, long marchId, String mode) {
        RoleKey role = roleOf(job);
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        long serverNow = serverNowMillisOf(engine);
        List<MarchView> all = readMarches(engine, state, role.uid, serverNow);
        List<MarchView> targets = new ArrayList<>();
        if (marchId > 0L) {
            for (MarchView march : all) {
                if (march.id == marchId) targets.add(march);
            }
            if (targets.isEmpty()) throw new IllegalStateException("部队不存在或已回城");
        } else {
            for (MarchView march : all) {
                if (march.recallable()) targets.add(march);
            }
        }
        JSONArray results = new JSONArray();
        int recalled = 0;
        boolean requiresDiamond = false;
        for (MarchView march : targets) {
            JSONObject item = recallOne(job, engine, state, march, mode);
            results.put(item);
            if (item.optBoolean("success", false)) recalled++;
            if (item.optBoolean("requires_diamond", false)) requiresDiamond = true;
        }
        job.recallMessage = recalled > 0 ? "已发送 " + recalled + " 支召回指令"
                : (targets.isEmpty() ? "没有需要召回的部队" : "召回未发出");
        job.touch();
        jobs.markDirty();
        JSONObject out = new JSONObject();
        out.put("results", results);
        out.put("recalled", recalled);
        out.put("requires_diamond", requiresDiamond);
        out.put("message", job.recallMessage);
        return out;
    }

    private JSONObject recallOne(MigrantJob job, WjdrEngine engine, MigrantState state,
                                 MarchView march, String mode) {
        JSONObject item = new JSONObject();
        item.put("march_id", march.id);
        FileLog.info("移民危险动作 recall fpid={} uid={} job={} march_id={} state={} mode={}",
                job.fpid, String.valueOf(job.uid), job.jobId, String.valueOf(march.id),
                String.valueOf(march.state), mode);
        try {
            if (march.rally()) return recallResult(item, false, "集结行军不支持召回", null);
            if (march.state == 4L) {
                item.put("recalled", true);
                return recallResult(item, true, "部队已经在返回途中", null);
            }
            if (march.state == 3L) {
                GameMessage rsp = request(engine, MigrantRules.PROTO_MARCH_OFF_WORK,
                        Exec.args("id", march.id), REQUEST_TIMEOUT_SEC);
                int code = Exec.codeOf(rsp);
                if (code == 0) return recallResult(item, true, "已发送撤回指令（停工作）", null);
                return recallResult(item, false, "撤回失败 code=" + code + "（" + describeCode(code) + "）", null);
            }
            if (march.state != 2L) {
                return recallResult(item, false, "当前状态不可召回", null);
            }
            // 在途行军：需要「出征召回」道具（或钻石购买）
            long own = state.itemCount(MigrantRules.RECALL_ITEM_ID);
            if (own <= 0L) {
                if (!"diamond".equals(mode)) {
                    item.put("requires_diamond", true);
                    return recallResult(item, false, "召回道具不足", "出征召回");
                }
                if (!buyDiamond(item, job, engine, MigrantRules.RECALL_ITEM_ID,
                        MigrantRules.RECALL_ITEM_DIAMOND_COST)) {
                    if (job.speedupDiamondUsed >= MigrantRules.DIAMOND_FALLBACK_LIMIT) {
                        item.put("requires_diamond", true);
                    }
                    return recallResult(item, false, "钻石召回失败", "出征召回");
                }
            } else {
                state.consumeItem(MigrantRules.RECALL_ITEM_ID, 1L);
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("id", march.id);
            args.put("itemid", MigrantRules.RECALL_ITEM_ID);
            GameMessage rsp = request(engine, MigrantRules.PROTO_MARCH_RECALL, args, REQUEST_TIMEOUT_SEC);
            int code = Exec.codeOf(rsp);
            if (code == 0) return recallResult(item, true, "已发送召回指令", "出征召回");
            return recallResult(item, false, "召回失败 code=" + code + "（" + describeCode(code) + "）",
                    "出征召回");
        } catch (RuntimeException e) {
            return recallResult(item, false, "召回异常：" + normalizedMessage(e), null);
        }
    }

    private JSONObject recallResult(JSONObject item, boolean success, String message,
                                    String itemName) {
        item.put("success", success);
        item.put("message", message);
        if (itemName != null) item.put("recall_item_name", itemName);
        return item;
    }

    /** 钻石快捷购买（守护盟友实证：{use:false, id, count:1}）；受 10 次/任务兜底上限约束。 */
    private boolean buyDiamond(JSONObject item, MigrantJob job, WjdrEngine engine,
                               int itemId, long diamondCost) {
        if (!diamondBudgetAvailable(job.speedupDiamondUsed)) {
            item.put("diamond_blocked", true);
            return false;
        }
        // P2-1：先自增并落盘（超时也可能已被服务端扣钻），失败再回滚
        job.speedupDiamondUsed = job.speedupDiamondUsed + 1;
        job.touch();
        jobs.markDirty();
        jobs.flushIfDirty();
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("use", false);
            args.put("id", itemId);
            args.put("count", 1);
            GameMessage rsp = request(engine, MigrantRules.PROTO_SHOP_QUICK_BUY, args, REQUEST_TIMEOUT_SEC);
            int code = Exec.codeOf(rsp);
            if (code != 0) {
                rollbackDiamond(job);
                item.put("diamond_message", "钻石购买失败 code=" + code + "（" + describeCode(code) + "）");
                return false;
            }
            job.touch();
            jobs.markDirty();
            FileLog.info("移民钻石兜底购买 fpid={} uid={} job={} item={} cost={} used={}",
                    job.fpid, String.valueOf(job.uid), job.jobId, String.valueOf(itemId),
                    String.valueOf(diamondCost), String.valueOf(job.speedupDiamondUsed));
            return true;
        } catch (RuntimeException e) {
            rollbackDiamond(job);
            item.put("diamond_message", "钻石购买异常：" + normalizedMessage(e));
            return false;
        }
    }

    private void rollbackDiamond(MigrantJob job) {
        if (job.speedupDiamondUsed > 0) job.speedupDiamondUsed = job.speedupDiamondUsed - 1;
        job.touch();
        jobs.markDirty();
    }

    /** 钻石兜底额度（纯函数，单测覆盖）：上限 10 次/任务。 */
    static boolean diamondBudgetAvailable(int used) {
        return used < MigrantRules.DIAMOND_FALLBACK_LIMIT;
    }

    // =====================================================================
    // 请求审计
    // =====================================================================

    /** 请求审计（网页版 attempts 字段 1:1）。 */
    public JSONObject attempts(MigrantJob job) {
        List<MigrantJob.Attempt> attempts = job.attemptsSnapshot();
        long firstSent = 0L;
        long lastSent = 0L;
        long firstResponse = 0L;
        long lastResponse = 0L;
        JSONArray arr = new JSONArray();
        for (MigrantJob.Attempt a : attempts) {
            if (firstSent == 0L || (a.sentAtMillis > 0L && a.sentAtMillis < firstSent)) firstSent = a.sentAtMillis;
            if (a.sentAtMillis > lastSent) lastSent = a.sentAtMillis;
            if (a.responseAtMillis > 0L && (firstResponse == 0L || a.responseAtMillis < firstResponse)) {
                firstResponse = a.responseAtMillis;
            }
            if (a.responseAtMillis > lastResponse) lastResponse = a.responseAtMillis;
            arr.put(a.toJson());
        }
        JSONObject out = new JSONObject();
        out.put("job_id", job.jobId);
        out.put("status", job.status);
        out.put("status_label", MigrantJob.labelOf(job.status));
        out.put("account_name", job.accountName);
        out.put("account_role_id", job.accountRoleId > 0L ? job.accountRoleId : job.uid);
        out.put("target_kid", job.targetKid);
        out.put("target_name", job.targetName);
        out.put("original_kid", job.originalKid);
        out.put("freedom_start_millis", job.freedomStartMillis);
        out.put("created_at_millis", job.createdAtMillis);
        out.put("armed_at_millis", job.armedAtMillis);
        out.put("sent_at_millis", job.sentAtMillis);
        out.put("finished_at_millis", job.finishedAtMillis);
        out.put("attempt_count", job.attemptCount);
        out.put("first_attempt_at_millis", firstSent);
        out.put("last_attempt_at_millis", lastSent);
        out.put("first_response_at_millis", firstResponse);
        out.put("last_response_at_millis", lastResponse);
        out.put("response_code", job.responseCode == null ? JSONObject.NULL : job.responseCode);
        out.put("clock_source", job.clockSource);
        out.put("clock_epoch_millis", job.clockEpochMillis);
        out.put("heartbeat_rtt_millis", job.heartbeatRttMillis);
        out.put("clock_synced_at_millis", clockSyncedAt(job));
        out.put("host_clock_offset_millis", hostClockOffset(job));
        out.put("server_now_millis", jobServerNowMillis(job));
        out.put("error", job.error);
        out.put("attempts", arr);
        return out;
    }

    private long clockSyncedAt(MigrantJob job) {
        GameServerClock clock = serverClock(roleOf(job));
        if (clock == null || !clock.locked()) return 0L;
        long since = clock.sinceCalibMs();
        return since < 0L ? 0L : System.currentTimeMillis() - since;
    }

    private long hostClockOffset(MigrantJob job) {
        GameServerClock clock = serverClock(roleOf(job));
        if (clock == null || !clock.locked()) return 0L;
        return clock.nowServerMs() - System.currentTimeMillis();
    }

    // =====================================================================
    // 重登核验
    // =====================================================================

    /** 重登读当前王国对账（网页版 recheck 语义）。 */
    public JSONObject recheck(MigrantJob job) {
        RoleKey role = roleOf(job);
        if (running.contains(job.jobId)) {
            throw new IllegalStateException("任务正在执行，暂不能重新核验");
        }
        if (MigrantJob.isActive(job.status)) {
            throw new IllegalStateException("任务尚未结束，暂不能重新核验");
        }
        if (keeper == null) throw new IllegalStateException("会话管理器未就绪");
        FileLog.info("移民危险动作 recheck fpid={} uid={} job={}", job.fpid,
                String.valueOf(job.uid), job.jobId);
        MigrantState cached = states.get(role.key());
        if (cached != null) cached.resetForLogin();
        // 先下线再上线，强制拿到最新王国的登录初始化数据
        try {
            keeper.roleAction(job.fpid, String.valueOf(job.sid), String.valueOf(job.uid), "logout");
        } catch (RuntimeException e) {
            FileLog.warn("移民核验前下线失败 job={}：{}", job.jobId, e.getMessage());
        }
        sleepQuietly(1000L);
        SessionControl.RoleOpResult login =
                keeper.roleAction(job.fpid, String.valueOf(job.sid), String.valueOf(job.uid), "login");
        if (login != null && login.code != 0) {
            throw new IllegalStateException("重新上线失败：" + login.message);
        }
        long deadline = System.currentTimeMillis() + CALL_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && !keeper.roleOnline(role)) {
            sleepQuietly(500L);
        }
        if (!keeper.roleOnline(role)) {
            job.error = "重新核验失败：角色未能重新上线";
            job.touch();
            jobs.markDirty();
            return job.toJson();
        }
        long kid = callInRole(role, "migrant.recheck", CALL_TIMEOUT_MILLIS, () -> {
            WjdrEngine engine = keeper.roleEngine(role);
            if (engine == null) throw new IllegalStateException("角色引擎不可用");
            MigrantState state = attachState(role, engine);
            waitForInit(state, engine, INIT_WAIT_MILLIS);
            long current = state.currentKid();
            if (current <= 0L) {
                MigrantState.Snapshot snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
                current = snap.currentKid;
            }
            putState(lastKidKey(role), String.valueOf(current));
            return current;
        });
        job.reconciledKid = kid;
        applyReconciled(job, kid, false, false, false, null);
        jobs.markDirty();
        jobs.flushIfDirty();
        return job.toJson();
    }

    // =====================================================================
    // 登录
    // =====================================================================

    /** 角色上线（复用 SessionKeeper 角色登录入口）。 */
    public JSONObject login(RoleKey role) {
        if (keeper == null) throw new IllegalStateException("会话管理器未就绪");
        if (!keeper.roleOnline(role)) {
            // 仅离线重登时清空状态；已在线角色点「上线」不能清掉正在执行任务的数据
            MigrantState cached = states.get(role.key());
            if (cached != null) cached.resetForLogin();
        }
        SessionControl.RoleOpResult result =
                keeper.roleAction(role.fpid, String.valueOf(role.sid), String.valueOf(role.uid), "login");
        JSONObject out = new JSONObject();
        out.put("accepted", result != null && result.code == 0);
        out.put("message", result == null ? "登录动作无响应" : result.message);
        out.put("state", result == null ? "" : result.runState);
        out.put("online", keeper.roleOnline(role));
        FileLog.info("移民角色上线 fpid={} uid={} accepted={}", role.fpid,
                String.valueOf(role.uid), String.valueOf(result != null && result.code == 0));
        return out;
    }

    /**
     * 角色下线（2026-11-06 裁决：网页版工作区头部有「账号上线/下线」开关，1:1 需支持下线）。
     *
     * <p>语义与 {@link #login(RoleKey)} 对称，复用 {@code SessionControl.roleAction logout}；
     * 下线前不取消任务——任务仍归属该角色，下次准备/发射到点会自行重新上线（与网页版一致：
     * 下线只是当前会话动作，不改变任务定义）。调度侧靠 {@code roleOnline} 判定自然避让。
     */
    public JSONObject logout(RoleKey role) {
        if (keeper == null) throw new IllegalStateException("会话管理器未就绪");
        SessionControl.RoleOpResult result =
                keeper.roleAction(role.fpid, String.valueOf(role.sid), String.valueOf(role.uid), "logout");
        JSONObject out = new JSONObject();
        out.put("accepted", result != null && result.code == 0);
        out.put("message", result == null ? "下线动作无响应" : result.message);
        out.put("state", result == null ? "" : result.runState);
        out.put("online", keeper.roleOnline(role));
        FileLog.info("移民角色下线 fpid={} uid={} accepted={}", role.fpid,
                String.valueOf(role.uid), String.valueOf(result != null && result.code == 0));
        return out;
    }

    // =====================================================================
    // 调度入口：准备
    // =====================================================================

    /** 任务是否正在角色线程执行（调度避让/防重复投递）。 */
    public boolean isRunning(String jobId) {
        return jobId != null && running.contains(jobId);
    }

    /** P1-1 看门狗：任务占用角色线程是否已超过给定毫秒。 */
    public boolean isRunningTooLong(String jobId, long millis) {
        if (jobId == null || millis <= 0L) return false;
        Long since = runningSince.get(jobId);
        return since != null && System.currentTimeMillis() - since > millis;
    }

    private boolean beginRunning(String jobId) {
        if (jobId == null || !running.add(jobId)) return false;
        runningSince.put(jobId, System.currentTimeMillis());
        return true;
    }

    private void endRunning(String jobId) {
        if (jobId == null) return;
        running.remove(jobId);
        runningSince.remove(jobId);
    }

    /**
     * 投递准备任务（准备窗口到点由调度器调用；失败/离线可在下一拍重试）。
     *
     * @return 是否成功投递
     */
    public boolean startPrepare(final MigrantJob job) {
        if (job == null) return false;
        synchronized (job) {
            if (!MigrantJob.WAITING.equals(job.status) && !MigrantJob.PREPARING.equals(job.status)) {
                return false;
            }
            if (!beginRunning(job.jobId)) return false;
            job.status = MigrantJob.PREPARING;
            if (job.preparationStartedAtMillis <= 0L) {
                job.preparationStartedAtMillis = System.currentTimeMillis();
            }
            job.touch();
        }
        final RoleKey role = roleOf(job);
        if (keeper == null || !keeper.roleOnline(role)) {
            job.error = "等待角色上线执行准备";
            job.prepareRetryAtMillis = System.currentTimeMillis() + PREPARE_RETRY_MILLIS;
            job.touch();
            jobs.markDirty();
            endRunning(job.jobId);
            tryLogin(job);
            return false;
        }
        job.error = "";
        job.touch();
        jobs.markDirty();
        boolean submitted = keeper.submitRoleJob(role, "migrant.prepare", () -> {
            try {
                prepareInRole(job);
            } catch (Throwable t) {
                onPrepareFailure(job, t);
            } finally {
                endRunning(job.jobId);
                jobs.markDirty();
            }
        }, () -> {
            endRunning(job.jobId);
            job.error = "角色会话掉线，准备未执行，等待重试";
            job.prepareRetryAtMillis = System.currentTimeMillis() + PREPARE_RETRY_MILLIS;
            job.touch();
            jobs.markDirty();
            FileLog.warn("移民准备任务被跳过 job={} fpid={} uid={}（角色掉线）", job.jobId,
                    job.fpid, String.valueOf(job.uid));
        });
        if (!submitted) {
            endRunning(job.jobId);
            job.error = "角色会话不可用，等待重新上线";
            job.touch();
            jobs.markDirty();
            tryLogin(job);
        }
        return submitted;
    }

    private void onPrepareFailure(MigrantJob job, Throwable failure) {
        if (MigrantJob.isTerminal(job.status)) return;   // 看门狗/取消已收口，不再复活
        String message = normalizedMessage(failure);
        long now = jobServerNowMillis(job);
        long readyDeadline = job.freedomStartMillis - MigrantRules.READY_BEFORE_MILLIS;
        boolean fatal = failure instanceof FatalException;
        if (fatal || (job.freedomStartMillis > 0L && now >= readyDeadline)) {
            job.finish(MigrantJob.REJECTED, "准备失败：" + message);
            putState(lastResultKey(roleOf(job)),
                    "REJECTED " + job.jobId + " " + message);
            FileLog.warn("移民任务准备失败 job={} fpid={} uid={}：{}", job.jobId, job.fpid,
                    String.valueOf(job.uid), message);
        } else {
            job.status = MigrantJob.PREPARING;
            job.error = "准备重试：" + message;
            job.prepareRetryAtMillis = System.currentTimeMillis() + PREPARE_RETRY_MILLIS;
            job.touch();
        }
        jobs.markDirty();
    }

    /**
     * 准备窗口内执行：刷新资格 → 自动退盟 → 召回 → 加速 → ARMED。
     *
     * <p><b>不阻塞会话线程</b>：每次只做一轮（等待 init/刷新/评估/动作），未就绪时把任务留在
     * PREPARING 并设置 {@code prepareRetryAtMillis}，由 {@link MigrantScheduler} 下一拍重新投递。
     * 这样角色会话的 1s 节拍（心跳/重连/日常）不会被 15 分钟准备窗口长期占用。
     */
    private void prepareInRole(MigrantJob job) {
        RoleKey role = roleOf(job);
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        if (job.freedomStartMillis <= 0L) {
            throw new FatalException("任务缺少自由移民开始时间，无法准备");
        }
        MigrantState state = attachState(role, engine);
        // P1-4：准备阶段每轮只等 5s 初始化，未齐就交给调度器下一轮，避免长占会话线程
        waitForInit(state, engine, INIT_WAIT_SHORT_MILLIS);
        MigrantState.Snapshot initSnap =
                state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
        if (!initSnap.complete()) {
            job.error = "等待登录初始化数据";
            job.prepareRetryAtMillis = System.currentTimeMillis() + 15_000L;
            job.touch();
            jobs.markDirty();
            return;
        }
        seedRoleCounts(state, role);

        MigrantState.Snapshot snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
        validateIdentity(job, snap);
        refreshKingdoms(engine, state, job.targetKid);
        snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
        MigrantRules.Evaluation evaluation = MigrantRules.evaluate(snap, job.targetKid);

        // 自动退盟：一轮只发一次；成功后乐观置零并立即复评（不退避等待推送）
        if (!evaluation.ready && job.autoExitAlliance) {
            MigrantRules.Check alliance = evaluation.checkOf("alliance");
            if (alliance != null && !alliance.passed) {
                exitAlliance(job, engine, state);
                snap = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
                evaluation = MigrantRules.evaluate(snap, job.targetKid);
            }
        }

        if (!evaluation.ready) {
            job.precheck = evaluation.toJson();
            String reason = MigrantRules.firstFailure(evaluation);
            long readyDeadline = job.freedomStartMillis - MigrantRules.READY_BEFORE_MILLIS;
            if (jobServerNowMillis(job) >= readyDeadline) {
                throw new FatalException(reason);
            }
            job.error = "准备重试：" + reason;
            // 初始化数据未齐时拉长退避，避免每轮都占满会话线程等推送
            long retryDelay = snap.complete() ? PREPARE_RETRY_MILLIS : 15_000L;
            job.prepareRetryAtMillis = System.currentTimeMillis() + retryDelay;
            job.touch();
            jobs.markDirty();
            return;
        }

        job.precheck = evaluation.toJson();
        job.targetName = evaluation.targetName;
        job.requiredPermits = evaluation.permitsRequired;
        job.error = "";
        job.touch();
        jobs.markDirty();
        jobs.flushIfDirty();

        if (job.recallBeforeOpen) {
            if (!job.recallReady) {
                int recalled = recallForPrepare(job, engine, state);
                if (recalled > 0 && job.recallSpeedup) {
                    // P1-4：召回后给服务端 3s 刷回城数据，下一轮再做加速（不在这里 sleep）
                    job.error = "已发送召回，等待回城数据后加速";
                    job.prepareRetryAtMillis = System.currentTimeMillis() + RECALL_SETTLE_MILLIS;
                    job.touch();
                    jobs.markDirty();
                    jobs.flushIfDirty();
                    return;
                }
                if (!job.recallReady) {
                    job.error = "召回未全部发出，准备重试：" + job.recallMessage;
                    job.prepareRetryAtMillis = System.currentTimeMillis() + PREPARE_RETRY_MILLIS;
                    job.touch();
                    jobs.markDirty();
                    return;
                }
            }
            if (job.recallSpeedup) {
                speedupForPrepare(job, engine, state);
            }
        }

        List<MarchView> finalMarches = readMarches(engine, state, role.uid,
                serverNowMillisOf(engine));
        job.marchCount = finalMarches.size();
        job.latestMarchEndMillis = latestEnd(finalMarches);
        synchronized (job) {
            if (MigrantJob.isTerminal(job.status)) {
                // 已被调度器/用户收口（例如窗口过期），不再覆盖终态
                return;
            }
            job.status = MigrantJob.ARMED;
            job.armedAtMillis = System.currentTimeMillis();
            job.error = "";
            job.prepareRetryAtMillis = 0L;
        }
        job.touch();
        jobs.markDirty();
        jobs.flushIfDirty();
        FileLog.info("移民任务准备完成 job={} fpid={} uid={} target_kid={} march_count={} "
                        + "latest_march_end={} diamond_used={}",
                job.jobId, job.fpid, String.valueOf(job.uid), String.valueOf(job.targetKid),
                String.valueOf(job.marchCount), String.valueOf(job.latestMarchEndMillis),
                String.valueOf(job.speedupDiamondUsed));
    }

    private void validateIdentity(MigrantJob job, MigrantState.Snapshot snap) {
        if (job.uid > 0L && snap.uid > 0L && snap.uid != job.uid) {
            throw new FatalException("登录角色与任务创建时不一致，旧任务确认失效");
        }
        if (job.activitySerialNo > 0L && snap.activitySerialNo > 0L
                && snap.activitySerialNo != job.activitySerialNo) {
            throw new FatalException("移民活动序列已变化，旧任务确认失效");
        }
        if (job.activityId > 0L && snap.activityId > 0L && snap.activityId != job.activityId) {
            throw new FatalException("移民活动已变化，旧任务确认失效");
        }
        if (job.originalKid > 0 && snap.currentKid > 0L && snap.currentKid != job.originalKid) {
            throw new FatalException("角色已不在创建任务时的王国（当前 K" + snap.currentKid + "）");
        }
    }

    private void exitAlliance(MigrantJob job, WjdrEngine engine, MigrantState state) {
        try {
            GameMessage rsp = request(engine, MigrantRules.PROTO_ALLIANCE_QUIT,
                    Collections.<String, Object>emptyMap(), REQUEST_TIMEOUT_SEC);
            int code = Exec.codeOf(rsp);
            if (code == 0) {
                job.exitAllianceMessage = "已退出联盟";
                state.markAllianceLeft();
            } else {
                job.exitAllianceMessage = "退出联盟失败 code=" + code + "（" + describeCode(code) + "）";
            }
        } catch (RuntimeException e) {
            job.exitAllianceMessage = "退出联盟异常：" + normalizedMessage(e);
        }
        job.touch();
        jobs.markDirty();
        FileLog.info("移民自动退盟 job={} fpid={} uid={} result={}", job.jobId, job.fpid,
                String.valueOf(job.uid), job.exitAllianceMessage);
    }

    /** 准备窗口召回：在途用道具、执行中免费撤回；不足时可按任务配置钻石兜底。返回成功召回数。 */
    private int recallForPrepare(MigrantJob job, WjdrEngine engine, MigrantState state) {
        RoleKey role = roleOf(job);
        long serverNow = serverNowMillisOf(engine);
        List<MarchView> marches = readMarches(engine, state, role.uid, serverNow);
        long roundDeadline = System.currentTimeMillis() + 10_000L;   // P1-4：单轮最多占 10s
        int recalled = 0;
        int failed = 0;
        for (MarchView march : marches) {
            if (!march.recallable()) continue;
            if (System.currentTimeMillis() > roundDeadline) {
                failed++;
                break;
            }
            JSONObject item = recallOne(job, engine, state, march,
                    job.recallDiamondSpeedup ? "diamond" : "item");
            if (item.optBoolean("success", false)) recalled++;
            else failed++;
        }
        job.marchCount = marches.size();
        job.latestMarchEndMillis = latestEnd(marches);
        job.recallReady = failed == 0;
        job.recallMessage = recalled > 0 ? "已召回 " + recalled + " 支在外部队"
                : (marches.isEmpty() ? "没有在外部队" : "召回未全部发出");
        job.touch();
        jobs.markDirty();
        jobs.flushIfDirty();
        return recalled;
    }

    /** 准备窗口加速：回城时间晚于「开放前 2 分钟」的部队用加速器压缩（钻石兜底受 10 次限制）。 */
    private void speedupForPrepare(MigrantJob job, WjdrEngine engine, MigrantState state) {
        long readyAt = job.freedomStartMillis - MigrantRules.READY_BEFORE_MILLIS;
        if (readyAt <= 0L) return;
        int requests = 0;
        int rounds = 0;
        long roundDeadline = System.currentTimeMillis() + 10_000L;   // P1-4：单轮最多占 10s
        while (rounds++ < 4 && requests < MAX_SPEEDUP_REQUESTS
                && System.currentTimeMillis() < roundDeadline) {
            RoleKey role = roleOf(job);
            List<MarchView> marches = readMarches(engine, state, role.uid, serverNowMillisOf(engine));
            boolean pending = false;
            for (MarchView march : marches) {
                if (march.state != 2L && march.state != 4L) continue;
                long endMillis = march.endMillis;
                while (endMillis > readyAt && requests < MAX_SPEEDUP_REQUESTS
                        && System.currentTimeMillis() < roundDeadline) {
                    int itemId = 0;
                    if (state.itemCount(MigrantRules.SPEEDUP_ITEM_TIER2) > 0L) {
                        itemId = MigrantRules.SPEEDUP_ITEM_TIER2;
                    } else if (state.itemCount(MigrantRules.SPEEDUP_ITEM_TIER1) > 0L) {
                        itemId = MigrantRules.SPEEDUP_ITEM_TIER1;
                    } else if (job.recallDiamondSpeedup) {
                        JSONObject dummy = new JSONObject();
                        if (!buyDiamond(dummy, job, engine, MigrantRules.SPEEDUP_ITEM_TIER1,
                                MigrantRules.SPEEDUP_ITEM_DIAMOND_COST)) {
                            pending = true;
                            break;
                        }
                        itemId = MigrantRules.SPEEDUP_ITEM_TIER1;
                    } else {
                        pending = true;
                        break;
                    }
                    requests++;
                    try {
                        Map<String, Object> args = new LinkedHashMap<>();
                        args.put("id", march.id);
                        args.put("itemid", itemId);
                        args.put("owner", String.valueOf(role.uid));
                        GameMessage rsp = request(engine, MigrantRules.PROTO_MARCH_SPEEDUP,
                                args, REQUEST_TIMEOUT_SEC);
                        int code = Exec.codeOf(rsp);
                        if (code != 0) {
                            job.recallMessage = "行军加速失败 code=" + code + "（"
                                    + describeCode(code) + "）";
                            pending = true;
                            break;
                        }
                        if (itemId == MigrantRules.SPEEDUP_ITEM_TIER2) job.speedupTier2Used++;
                        else job.speedupTier1Used++;
                        state.consumeItem(itemId, 1L);
                        long endtime = rsp == null || rsp.getContent() == null ? 0L
                                : Exec.longOf(rsp.getContent().get("endtime"));
                        if (endtime > 0L) endMillis = endtime * 1000L;
                        else endMillis = Math.max(0L, readyAt);
                    } catch (RuntimeException e) {
                        job.recallMessage = "行军加速异常：" + normalizedMessage(e);
                        pending = true;
                        break;
                    }
                }
                if (pending) break;
            }
            job.latestMarchEndMillis = latestEnd(marches);
            job.touch();
            jobs.markDirty();
            if (!pending) break;
        }
        jobs.flushIfDirty();
    }

    // =====================================================================
    // 调度入口：发射（P1-4：发射结束即返回；对账由调度器分轮进行）
    // =====================================================================

    /**
     * 投递发射任务（开放边界前 {@code advance_millis} 起抢位）。
     *
     * <p>P1-2：角色游戏服时钟未校准时**不发射**（否则 waitUntil 退化为本机墙钟，抢位窗口整体平移）。
     * <p>P1-3：进入 FIRING 后同步落盘 status/sent_at，崩溃重启不会被当成可重发任务。
     */
    public boolean startFire(final MigrantJob job) {
        if (job == null) return false;
        synchronized (job) {
            boolean dispatchable = MigrantJob.ARMED.equals(job.status)
                    || (MigrantJob.FIRING.equals(job.status) && job.sentAtMillis <= 0L
                        && job.fireDoneAtMillis <= 0L);
            if (!dispatchable) return false;
            if (!beginRunning(job.jobId)) return false;
            job.status = MigrantJob.FIRING;
            job.sentAtMillis = 0L;
            job.fireStartedAtMillis = System.currentTimeMillis();
            job.error = "";
            job.touch();
        }
        final RoleKey role = roleOf(job);
        if (keeper == null || !keeper.roleOnline(role)) {
            job.error = "发射窗口内角色未在线，等待重新上线";
            job.touch();
            jobs.markDirty();
            endRunning(job.jobId);
            tryLogin(job);
            return false;
        }
        GameServerClock clock = serverClock(role);
        if (clock == null || !clock.locked()) {
            markClockUnsynced(job);
            endRunning(job.jobId);
            return false;
        }
        // P1-3：FIRING + 发射起点先落盘（崩溃窗口内不会重复真实发射）
        jobs.markDirty();
        jobs.flush();
        boolean submitted = keeper.submitRoleJob(role, "migrant.fire", () -> {
            try {
                fireInRole(job);
            } catch (Throwable t) {
                onFireFailure(job, t);
            } finally {
                endRunning(job.jobId);
                jobs.markDirty();
            }
        }, () -> onRoleJobSkipped(job, "发射"));
        if (!submitted) {
            endRunning(job.jobId);
            job.error = "角色会话不可用，等待重新上线";
            job.touch();
            jobs.markDirty();
            tryLogin(job);
        }
        return submitted;
    }

    /** P1-1：角色会话在任务体执行前掉线 → 清理 running，交给调度器重试。 */
    private void onRoleJobSkipped(MigrantJob job, String stage) {
        endRunning(job.jobId);
        job.error = "角色会话掉线，" + stage + "未执行，等待重试";
        if ("发射".equals(stage)) job.sentAtMillis = 0L;
        job.touch();
        jobs.markDirty();
        FileLog.warn("移民{}任务被跳过 job={} fpid={} uid={}（角色掉线）", stage, job.jobId,
                job.fpid, String.valueOf(job.uid));
    }

    /** P1-2：时钟未校准收口（不发送任何迁移请求）。 */
    private void markClockUnsynced(MigrantJob job) {
        synchronized (job) {
            if (MigrantJob.isTerminal(job.status)) return;
            job.finish(MigrantJob.UNKNOWN, "角色游戏服时钟未校准，未发送迁移请求");
        }
        putState(lastResultKey(roleOf(job)), "UNKNOWN " + job.jobId + " 时钟未校准");
        scheduleReverifyAfter(job);
        jobs.markDirty();
        jobs.flushIfDirty();
        FileLog.warn("移民任务时钟未校准，取消发射 job={} fpid={} uid={}", job.jobId, job.fpid,
                String.valueOf(job.uid));
    }

    private void onFireFailure(MigrantJob job, Throwable failure) {
        if (MigrantJob.FIRING.equals(job.status)) {
            job.finish(MigrantJob.UNKNOWN,
                    "最终请求或结果核验阶段异常，已禁止自动重发：" + normalizedMessage(failure));
            jobs.markDirty();
            jobs.flushIfDirty();
        }
        FileLog.warn("移民发射异常 job={} fpid={} uid={}：{}", job.jobId, job.fpid,
                String.valueOf(job.uid), normalizedMessage(failure));
    }

    /**
     * 发射：等待边界 → 50ms 节拍抢位（≤15s）→ 立即返回；90 秒对账交给调度器分轮探测，
     * 避免在角色会话线程里 sleep 90s 中断心跳（P1-4）。
     */
    private void fireInRole(MigrantJob job) {
        RoleKey role = roleOf(job);
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        GameServerClock clock = engine.serverClock();
        if (clock == null || !clock.locked()) {
            markClockUnsynced(job);
            return;
        }
        job.clockSource = "role_clock";
        job.clockEpochMillis = clock.lastServerSec() * 1000L;
        job.heartbeatRttMillis = 0L;   // GameServerClock 不暴露心跳 RTT，保持 0 不冒充
        job.touch();
        jobs.markDirty();

        long fireAt = job.freedomStartMillis - Math.max(0, job.advanceMillis);
        try {
            clock.waitUntil(fireAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (job.attemptCount <= 0 && job.attemptHistorySize() == 0) {
                job.finish(MigrantJob.REJECTED, "等待发射边界时被中断，未发送迁移请求");
            } else {
                job.finish(MigrantJob.UNKNOWN, "发射等待被中断，已有尝试记录，结果待人工核验");
            }
            jobs.markDirty();
            return;
        }
        if (MigrantJob.isTerminal(job.status)) return;

        // 契约 V2：sent_at_millis 取“真正开始发请求”的服务器时刻（服务器钟域）
        job.sentAtMillis = clock.nowServerMs();
        job.fireStartedAtMillis = System.currentTimeMillis();
        jobs.markDirty();
        jobs.flush();
        final long burstStartServerMillis = job.sentAtMillis;
        final long submitDeadlineNanos =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MigrantRules.SUBMIT_WINDOW_MILLIS);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean accepted = new AtomicBoolean();
        final AtomicBoolean firstLoginSignal = new AtomicBoolean();
        final AtomicBoolean transportFailure = new AtomicBoolean();
        final AtomicReference<Integer> responseCode = new AtomicReference<>();
        final AtomicReference<RuntimeException> lastFailure = new AtomicReference<>();

        MigrantRetryExecutor.RetryResult retryResult = retryExecutor.execute(
                attempt -> sendAttempt(job, engine, state, attempt, submitDeadlineNanos, clock,
                        burstStartServerMillis, stop, accepted, firstLoginSignal, transportFailure,
                        responseCode, lastFailure),
                () -> shouldStop(role, state, stop, firstLoginSignal, submitDeadlineNanos, job),
                System::nanoTime, MigrantEngine::parkNanos);

        if (MigrantJob.isTerminal(job.status)) return;
        job.attemptCount = retryResult.attemptCount;
        job.responseCode = responseCode.get();
        job.successCandidate = accepted.get() || firstLoginSignal.get()
                || state.firstLoginNotified()
                || Integer.valueOf(MigrantRules.CODE_MIGRATING).equals(job.responseCode);
        job.transportFailure = transportFailure.get();
        job.fireDoneAtMillis = clock.nowServerMs();
        job.reconcileDeadlineMillis = job.fireDoneAtMillis
                + MigrantRules.RECONCILE_WINDOW_MILLIS;
        job.touch();
        jobs.markDirty();
        jobs.flush();
        FileLog.info("移民抢位结束 job={} fpid={} uid={} target_kid={} attempts={} last_code={} "
                        + "success_candidate={} transport_failure={}",
                job.jobId, job.fpid, String.valueOf(job.uid), String.valueOf(job.targetKid),
                String.valueOf(job.attemptCount), String.valueOf(job.responseCode),
                String.valueOf(job.successCandidate), String.valueOf(job.transportFailure));
        // 立即做一次轻量探测（结果已定时直接收口），否则调度器每 5s 分轮探测
        try {
            reconcileProbe(job);
        } catch (RuntimeException e) {
            FileLog.warn("移民对账首探失败 job={}：{}", job.jobId, normalizedMessage(e));
        }
    }

    private boolean shouldStop(RoleKey role, MigrantState state, AtomicBoolean stop,
                               AtomicBoolean firstLoginSignal, long submitDeadlineNanos,
                               MigrantJob job) {
        if (System.nanoTime() >= submitDeadlineNanos) return true;
        if (stop.get() || firstLoginSignal.get()) return true;
        if (job != null && !MigrantJob.FIRING.equals(job.status)) return true;   // 被看门狗收口
        if (keeper != null && !keeper.roleOnline(role)) return true;
        return state != null && state.firstLoginNotified();
    }

    /** 单次迁移尝试（调用前由 50ms 节拍器对齐时间）。 */
    private MigrantRetryExecutor.AttemptOutcome sendAttempt(
            MigrantJob job, WjdrEngine engine, MigrantState state, int attempt,
            long submitDeadlineNanos, GameServerClock clock, long burstStartServerMillis,
            AtomicBoolean stop, AtomicBoolean accepted, AtomicBoolean firstLoginSignal,
            AtomicBoolean transportFailure, AtomicReference<Integer> responseCode,
            AtomicReference<RuntimeException> lastFailure) {
        long waitMillis = MigrantRetryExecutor.waitBudgetMillis(submitDeadlineNanos,
                System.nanoTime(), MigrantRules.ATTEMPT_RESPONSE_WAIT_MILLIS);
        if (waitMillis <= 0L) {
            return MigrantRetryExecutor.AttemptOutcome.failed(null,
                    new IllegalStateException("自由移民提交窗口已结束"));
        }
        int timeoutSec = (int) Math.max(1L, Math.min(REQUEST_TIMEOUT_SEC, (waitMillis + 999L) / 1000L));
        long sentAt = attemptSentAtMillis(clock, job.sentAtMillis, attempt);
        long hostSentAt = System.currentTimeMillis();
        long elapsed = Math.max(0L, sentAt - burstStartServerMillis);
        MigrantJob.Attempt record = new MigrantJob.Attempt();
        record.attemptNo = job.attemptHistorySize() + 1;
        record.sentAtMillis = sentAt;
        record.hostSentAtMillis = hostSentAt;
        record.serverNowMillis = sentAt;     // 与 sent_at 同一服务器钟域，避免 attempts 自相矛盾
        record.serverClockReady = clock != null && clock.locked();
        record.phase = attemptPhase(elapsed);
        record.phaseLabel = attemptPhaseLabel(record.phase);
        record.requestText = MigrantRules.PROTO_MIGRATE + " kid=" + job.targetKid;
        // P1-3：第一条尝试先落盘再发请求（崩溃后 restoreStatus 见 attempt 历史即判 UNKNOWN）
        boolean prePersisted = false;
        if (attempt == 1) {
            job.addAttempt(record);
            jobs.markDirty();
            jobs.flush();
            prePersisted = true;
        }
        job.attemptCount = record.attemptNo;
        try {
            GameMessage rsp = request(engine, MigrantRules.PROTO_MIGRATE,
                    Exec.args("kid", job.targetKid), timeoutSec);
            int code = Exec.codeOf(rsp);
            record.responseAtMillis = clock == null ? System.currentTimeMillis()
                    : clock.nowServerMs();
            record.latencyMillis = Math.max(0L, System.currentTimeMillis() - hostSentAt);
            record.responseCode = code;
            record.responseText = truncate(String.valueOf(rsp == null ? null : rsp.getContent()), 400);
            responseCode.set(code);
            if (code == 0) {
                record.outcome = "accepted";
                record.outcomeLabel = "服务端接受";
                accepted.set(true);
                stop.set(true);
            } else if (code == MigrantRules.CODE_MIGRATING
                    || code == MigrantRules.CODE_MIGRATE_DISCONNECT) {
                record.outcome = "stop";
                record.outcomeLabel = describeCode(code);
                stop.set(true);
            } else {
                record.outcome = "rejected";
                record.outcomeLabel = describeCode(code);
            }
            if (!prePersisted) job.addAttempt(record);
            job.touch();
            jobs.markDirty();
            return new MigrantRetryExecutor.AttemptOutcome(code == 0, code, null);
        } catch (RuntimeException e) {
            record.responseAtMillis = clock == null ? System.currentTimeMillis()
                    : clock.nowServerMs();
            record.latencyMillis = Math.max(0L, System.currentTimeMillis() - hostSentAt);
            record.outcome = "error";
            record.outcomeLabel = "请求异常";
            record.error = truncate(normalizedMessage(e), 300);
            transportFailure.set(true);
            lastFailure.set(e);
            if (!prePersisted) job.addAttempt(record);
            job.touch();
            jobs.markDirty();
            return MigrantRetryExecutor.AttemptOutcome.failed(null, e);
        }
    }

    // ---------------------------------------------------------------------
    // 结果对账（P1-4：调度器分轮探测，每轮 <2s）
    // ---------------------------------------------------------------------

    /** 调度器投递一次轻量对账探测（5s 节流由调度器控制）。 */
    public boolean startReconcile(final MigrantJob job) {
        if (job == null || !MigrantJob.FIRING.equals(job.status)) return false;
        if (job.fireDoneAtMillis <= 0L) return false;
        if (job.reconcileDeadlineMillis > 0L
                && jobServerNowMillis(job) >= job.reconcileDeadlineMillis) {
            return false;
        }
        if (!beginRunning(job.jobId)) return false;
        final RoleKey role = roleOf(job);
        if (keeper == null || !keeper.roleOnline(role)) {
            endRunning(job.jobId);
            tryLogin(job);
            return false;
        }
        job.reconcileAttemptAtMillis = System.currentTimeMillis();
        boolean submitted = keeper.submitRoleJob(role, "migrant.reconcile", () -> {
            try {
                reconcileProbe(job);
            } catch (Throwable t) {
                FileLog.warn("移民对账探测失败 job={}：{}", job.jobId, normalizedMessage(t));
            } finally {
                endRunning(job.jobId);
                jobs.markDirty();
            }
        }, () -> {
            endRunning(job.jobId);
            jobs.markDirty();
        });
        if (!submitted) endRunning(job.jobId);
        return submitted;
    }

    /** 读当前王国/首登标记（只读，不 sleep）；确认成功即收口。 */
    private void reconcileProbe(MigrantJob job) {
        RoleKey role = roleOf(job);
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        if (state.firstLoginNotified()) {
            job.reconciledKid = job.targetKid;
            finishSucceeded(job, role, engine, state);
            return;
        }
        long kid = state.currentKid();
        if (kid <= 0L) {
            kid = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine)).currentKid;
        }
        if (kid > 0L) {
            job.reconciledKid = kid;
            putState(lastKidKey(role), String.valueOf(kid));
        }
        if (kid == job.targetKid) {
            finishSucceeded(job, role, engine, state);
            return;
        }
        if (kid > 0L && kid == job.originalKid) {
            job.error = "结果核验中：角色当前仍在原王国 K" + kid;
        } else if (kid > 0L) {
            job.error = "结果核验中：角色当前在 K" + kid;
        } else {
            job.error = "结果核验中：尚未读到当前王国";
        }
        job.touch();
        jobs.markDirty();
    }

    /** 调度器在对账窗口结束时收口（依据最近一次探测到的王国）。 */
    public void finishReconcile(MigrantJob job) {
        if (job == null) return;
        synchronized (job) {
            if (!MigrantJob.FIRING.equals(job.status)) return;
            applyReconciledLocked(job, job.reconciledKid, job.successCandidate,
                    job.transportFailure,
                    Integer.valueOf(MigrantRules.CODE_MIGRATE_DISCONNECT).equals(job.responseCode),
                    null);
        }
        jobs.markDirty();
        jobs.flushIfDirty();
        boolean reverify = scheduleReverifyAfter(job);
        FileLog.info("移民对账收口 job={} fpid={} uid={} target_kid={} reconciled_kid={} "
                        + "status={} reverify={}",
                job.jobId, job.fpid, String.valueOf(job.uid), String.valueOf(job.targetKid),
                String.valueOf(job.reconciledKid), job.status, String.valueOf(reverify));
    }

    private void finishSucceeded(MigrantJob job, RoleKey role, WjdrEngine engine,
                                 MigrantState state) {
        synchronized (job) {
            // 允许 UNKNOWN → SUCCEEDED（延迟复核/重登核验确认到位）；不覆盖已明确的终态
            if (MigrantJob.SUCCEEDED.equals(job.status)
                    || MigrantJob.REJECTED.equals(job.status)
                    || MigrantJob.CANCELLED.equals(job.status)) {
                return;
            }
            job.finish(MigrantJob.SUCCEEDED, "");
            job.reconciledKid = job.targetKid;
            clearReverify(job);
        }
        putState(lastKidKey(role), String.valueOf(job.targetKid));
        putState(lastResultKey(role), "SUCCEEDED " + job.jobId);
        jobs.markDirty();
        jobs.flushIfDirty();
        // 首登标记清理（原版 clearFirstLoginFlag：向服务端确认首登）
        if (state != null && state.firstLoginNotified()) {
            try {
                request(engine, MigrantRules.PROTO_FIRST_LOGIN,
                        Collections.<String, Object>emptyMap(), REQUEST_TIMEOUT_SEC);
                state.clearFirstLoginFlag();
            } catch (RuntimeException e) {
                FileLog.info("移民成功后清理首登标记失败，保留成功结论 job={}：{}",
                        job.jobId, normalizedMessage(e));
            }
        }
        FileLog.info("移民成功 job={} fpid={} uid={} target_kid={} attempts={}",
                job.jobId, job.fpid, String.valueOf(job.uid), String.valueOf(job.targetKid),
                String.valueOf(job.attemptCount));
    }

    /** 原版 applyReconciledResult 逐条移植（含不确定判定）。P2-3：与 finishAndPersist 同样加锁。 */
    private void applyReconciled(MigrantJob job, long reconciledKid, boolean successCandidate,
                                 boolean transportFailure, boolean disconnectSignal,
                                 RuntimeException lastFailure) {
        synchronized (job) {
            applyReconciledLocked(job, reconciledKid, successCandidate, transportFailure,
                    disconnectSignal, lastFailure);
        }
    }

    private void applyReconciledLocked(MigrantJob job, long reconciledKid,
                                       boolean successCandidate, boolean transportFailure,
                                       boolean disconnectSignal, RuntimeException lastFailure) {
        if (reconciledKid <= 0L) {
            job.finish(MigrantJob.UNKNOWN, "对账窗口内未能读取当前王国，需人工核验");
            return;
        }
        if (reconciledKid == job.targetKid) {
            job.finish(MigrantJob.SUCCEEDED, "");
            putState(lastKidKeyForJob(job), String.valueOf(job.targetKid));
            job.reconciledKid = reconciledKid;
        } else if (reconciledKid == job.originalKid) {
            boolean uncertain = successCandidate || transportFailure || disconnectSignal;
            job.status = uncertain ? MigrantJob.UNKNOWN : MigrantJob.REJECTED;
            job.error = uncertain
                    ? "已重新登录原王国，但重试窗口内出现成功候选、提交等待超时或断线，结果需人工确认"
                    : "已完成 " + job.attemptCount + " 次迁入尝试并重新登录原王国，服务端仍拒绝迁入："
                    + responseDescription(job.responseCode, lastFailure);
            job.finishedAtMillis = System.currentTimeMillis();
            job.reconciledKid = reconciledKid;
            job.touch();
            putState(lastResultKey(roleOf(job)),
                    job.status + " " + job.jobId + " " + job.error);
        } else {
            job.finish(MigrantJob.UNKNOWN,
                    "重新登录后的王国与原王国和目标王国均不一致，需要人工处理");
            job.reconciledKid = reconciledKid;
        }
    }

    private String responseDescription(Integer responseCode, RuntimeException failure) {
        if (responseCode != null) {
            return "错误码 " + responseCode + "（" + describeCode(responseCode) + "）";
        }
        return normalizedMessage(failure);
    }

    // ---------------------------------------------------------------------
    // 延迟复核（P1-5：UNKNOWN 任务在开放后 +3min / +10min 自动复核）
    // ---------------------------------------------------------------------

    /** 调度器投递一次延迟复核（轻量读当前王国，不发迁移请求）。 */
    public boolean startReverify(final MigrantJob job) {
        if (job == null || !MigrantJob.UNKNOWN.equals(job.status)) return false;
        if (job.nextReverifyAtMillis <= 0L) return false;
        if (!beginRunning(job.jobId)) return false;
        final RoleKey role = roleOf(job);
        if (keeper == null || !keeper.roleOnline(role)) {
            endRunning(job.jobId);
            // 2026-11-07 修复（端到端验证发现）：离线时只 tryLogin 而不推进轮次，会让
            // nextReverifyAtMillis 长期停留在过去 → 调度器每秒重投 → 反复拉起登录
            // （token 失效时刷屏），且永远排不到 +10min 第二轮、任务永久滞留 UNKNOWN。
            // 正确语义：本次复核无法执行也算「本轮无结论」，照常推进（两轮后停止自动复核）。
            synchronized (job) {
                advanceReverify(job);
                job.touch();
            }
            jobs.markDirty();
            jobs.flushIfDirty();
            tryLogin(job);
            return false;
        }
        boolean submitted = keeper.submitRoleJob(role, "migrant.reverify", () -> {
            try {
                reverifyProbe(job);
            } catch (Throwable t) {
                FileLog.warn("移民延迟复核失败 job={}：{}", job.jobId, normalizedMessage(t));
            } finally {
                endRunning(job.jobId);
                jobs.markDirty();
            }
        }, () -> {
            endRunning(job.jobId);
            jobs.markDirty();
        });
        if (!submitted) endRunning(job.jobId);
        return submitted;
    }

    private void reverifyProbe(MigrantJob job) {
        RoleKey role = roleOf(job);
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        waitForInit(state, engine, INIT_WAIT_SHORT_MILLIS);
        if (state.firstLoginNotified()) {
            job.reconciledKid = job.targetKid;
            finishSucceeded(job, role, engine, state);
            return;
        }
        long kid = state.currentKid();
        if (kid <= 0L) {
            kid = state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine)).currentKid;
        }
        if (kid > 0L) {
            job.reconciledKid = kid;
            putState(lastKidKey(role), String.valueOf(kid));
        }
        if (kid == job.targetKid) {
            finishSucceeded(job, role, engine, state);
            return;
        }
        if (kid > 0L && kid == job.originalKid) {
            synchronized (job) {
                job.status = MigrantJob.REJECTED;
                job.error = "延迟复核已确认角色仍在原王国，判定未迁入";
                job.finishedAtMillis = System.currentTimeMillis();
                job.touch();
                clearReverify(job);
            }
            putState(lastResultKey(role), "REJECTED " + job.jobId + " 延迟复核仍在原王国");
            jobs.markDirty();
            jobs.flushIfDirty();
            FileLog.info("移民延迟复核判定未迁入 job={} fpid={} uid={}", job.jobId, job.fpid,
                    String.valueOf(job.uid));
            return;
        }
        advanceReverify(job);
        job.touch();
        jobs.markDirty();
    }

    /** +3min 后仍无法确认 → 安排 +10min 复核；两轮都无结论则停止（仍 UNKNOWN）。 */
    private void advanceReverify(MigrantJob job) {
        long now = jobServerNowMillis(job);
        int next = job.reverifyIndex + 1;
        if (next >= REVERIFY_DELAYS_MILLIS.length) {
            clearReverify(job);
            FileLog.info("移民延迟复核两轮无结论，停止自动复核 job={}", job.jobId);
            return;
        }
        job.reverifyIndex = next;
        job.nextReverifyAtMillis = now + REVERIFY_DELAYS_MILLIS[next];
        FileLog.info("移民延迟复核仍无结论，安排下一次 job={} at={} (+{}min)", job.jobId,
                String.valueOf(job.nextReverifyAtMillis),
                String.valueOf(REVERIFY_DELAYS_MILLIS[next] / 60_000L));
    }

    /** UNKNOWN 任务安排延迟复核（限活动窗口内、且 6 小时内）；返回是否新安排。 */
    private boolean scheduleReverifyAfter(MigrantJob job) {
        if (job == null || !MigrantJob.UNKNOWN.equals(job.status)) return false;
        if (job.nextReverifyAtMillis > 0L && job.reverifyIndex >= 0) return false;
        long now = jobServerNowMillis(job);
        if (job.freedomEndMillis > 0L && now >= job.freedomEndMillis) return false;
        if (job.updatedAtMillis > 0L && now - job.updatedAtMillis > REVERIFY_WINDOW_MILLIS) {
            return false;
        }
        job.reverifyIndex = 0;
        job.nextReverifyAtMillis = now + REVERIFY_DELAYS_MILLIS[0];
        job.touch();
        jobs.markDirty();
        FileLog.info("移民任务安排延迟复核 job={} at={} (+3min)", job.jobId,
                String.valueOf(job.nextReverifyAtMillis));
        return true;
    }

    private void clearReverify(MigrantJob job) {
        job.reverifyIndex = -1;
        job.nextReverifyAtMillis = 0L;
    }

    // ---------------------------------------------------------------------
    // 时钟/节拍纯函数（单测覆盖）
    // ---------------------------------------------------------------------

    /** 单次尝试的服务器钟发送时刻：首条复用任务的 sent_at，其余取角色游戏服时钟。 */
    static long attemptSentAtMillis(GameServerClock clock, long jobSentAtMillis, int attempt) {
        if (attempt <= 1 && jobSentAtMillis > 0L) return jobSentAtMillis;
        if (clock != null && clock.locked()) return clock.nowServerMs();
        return System.currentTimeMillis();
    }

    /** 抢位阶段分类（App 按 BURST/FOLLOW_UP/FALLBACK 上色）。 */
    static String attemptPhase(long elapsedMillis) {
        if (elapsedMillis < 5_000L) return "BURST";
        if (elapsedMillis < 10_000L) return "FOLLOW_UP";
        return "FALLBACK";
    }

    static String attemptPhaseLabel(String phase) {
        if ("BURST".equals(phase)) return "抢位爆发";
        if ("FOLLOW_UP".equals(phase)) return "持续跟进";
        if ("FALLBACK".equals(phase)) return "兜底重试";
        return String.valueOf(phase);
    }

    // =====================================================================
    // 调度器的兜底收口（窗口过期 / 准备超时 / 发射错过）
    // =====================================================================

    /** 已过自由移民窗口：活跃任务一律 UNKNOWN（不猜测结果）。 */
    public void markOverWindow(MigrantJob job) {
        finishAndPersist(job, MigrantJob.UNKNOWN, "已过自由移民窗口且未取得结果，需人工核验",
                "窗口过期");
    }

    /** 准备窗口到点仍未就绪：未发请求，判 REJECTED（安全）。 */
    public void markPrepareTimeout(MigrantJob job) {
        String reason = job.error == null || job.error.trim().isEmpty()
                ? "资格条件未满足" : job.error;
        finishAndPersist(job, MigrantJob.REJECTED, "准备窗口结束仍未完成准备：" + reason,
                "准备超时");
    }

    /** 发射窗口内角色始终未上线：没有发出任何请求，判 REJECTED。 */
    public void markFireMissed(MigrantJob job) {
        finishAndPersist(job, MigrantJob.REJECTED, "发射窗口内角色未上线，未发送迁移请求",
                "发射错过");
    }

    /** 发射与对账窗口都结束仍无结论：UNKNOWN。 */
    public void markFireTimeout(MigrantJob job) {
        finishAndPersist(job, MigrantJob.UNKNOWN, "发射与对账窗口已结束但结果未落定，需人工核验",
                "对账超时");
    }

    private void finishAndPersist(MigrantJob job, String status, String message, String tag) {
        synchronized (job) {
            if (MigrantJob.isTerminal(job.status)) return;
            job.finish(status, message);
        }
        putState(lastResultKey(roleOf(job)), status + " " + job.jobId + " " + message);
        boolean reverify = scheduleReverifyAfter(job);
        jobs.markDirty();
        jobs.flushIfDirty();
        FileLog.info("移民任务收口（{}）job={} fpid={} uid={} status={} message={} reverify={}", tag,
                job.jobId, job.fpid, String.valueOf(job.uid), status, message,
                String.valueOf(reverify));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private static void parkNanos(long nanos) {
        LockSupport.parkNanos(nanos);
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("移民重试等待被中断");
        }
    }

    private void tryLogin(MigrantJob job) {
        if (keeper == null) return;
        long now = System.currentTimeMillis();
        if (now - job.loginAttemptAtMillis < LOGIN_THROTTLE_MILLIS) return;
        job.loginAttemptAtMillis = now;
        MigrantState cached = states.get(roleOf(job).key());
        if (cached != null) cached.resetForLogin();
        try {
            SessionControl.RoleOpResult result = keeper.roleAction(job.fpid,
                    String.valueOf(job.sid), String.valueOf(job.uid), "login");
            FileLog.info("移民任务拉起登录 job={} fpid={} uid={} code={} message={}",
                    job.jobId, job.fpid, String.valueOf(job.uid),
                    String.valueOf(result == null ? -1 : result.code),
                    result == null ? "" : result.message);
        } catch (RuntimeException e) {
            FileLog.warn("移民任务拉起登录失败 job={}：{}", job.jobId, e.getMessage());
        }
    }

    private String roleName(RoleKey role) {
        if (store != null && role != null) {
            for (VaultStore.RoleEntry entry : store.roles(role.fpid)) {
                if (entry == null || entry.uid == null || entry.uid.isEmpty()) continue;
                long uid;
                try {
                    uid = Long.parseLong(entry.uid);
                } catch (RuntimeException e) {
                    continue;
                }
                if (uid == role.uid && entry.name != null && !entry.name.trim().isEmpty()) {
                    return entry.name;
                }
            }
            VaultStore.Account account = store.find(role.fpid);
            if (account != null && account.label != null && !account.label.trim().isEmpty()) {
                return account.label;
            }
        }
        return role == null ? "" : role.fpid;
    }

    private String lastKidKey(RoleKey role) {
        return "srv:migrant:kid:" + role.suffix();
    }

    private String lastKidKeyForJob(MigrantJob job) {
        return "srv:migrant:kid:" + job.fpid + ":" + job.sid + ":" + job.uid;
    }

    private String lastResultKey(RoleKey role) {
        return "srv:migrant:result:" + role.suffix();
    }

    /** StateStore 可空（单测只构造任务仓时的容错）。 */
    private void putState(String key, String value) {
        if (stateStore == null || key == null) return;
        stateStore.putString(key, value);
    }

    /** 刷新王国列表 + 目标详情（失败向上抛，由调用方决定是否阻断）。 */
    private void refreshKingdoms(WjdrEngine engine, MigrantState state, long targetKid) {
        try {
            requestAndRecord(engine, state, MigrantRules.PROTO_KINGDOM_LIST,
                    Collections.<String, Object>emptyMap());
        } catch (RuntimeException e) {
            FileLog.warn("移民刷新王国列表失败：{}", e.getMessage());
        }
        requestAndRecord(engine, state, MigrantRules.PROTO_KINGDOM_INFO,
                Exec.args("kid", targetKid));
    }

    private EvalResult evaluateInRole(RoleKey role, long targetKid, boolean includeList) {
        WjdrEngine engine = keeper.roleEngine(role);
        if (engine == null) throw new IllegalStateException("角色引擎不可用");
        MigrantState state = attachState(role, engine);
        waitForInit(state, engine, INIT_WAIT_MILLIS);
        seedRoleCounts(state, role);
        if (includeList) {
            try {
                requestAndRecord(engine, state, MigrantRules.PROTO_KINGDOM_LIST,
                        Collections.<String, Object>emptyMap());
            } catch (RuntimeException e) {
                FileLog.warn("移民预检刷新王国列表失败 role={}：{}", role.key(), e.getMessage());
            }
        }
        requestAndRecord(engine, state, MigrantRules.PROTO_KINGDOM_INFO,
                Exec.args("kid", targetKid));
        MigrantState.Snapshot snap =
                state.snapshot(serverNowMillisOf(engine), clockReadyOf(engine));
        MigrantRules.Evaluation evaluation = MigrantRules.evaluate(snap, targetKid);
        return new EvalResult(evaluation, snap);
    }

    private List<MarchView> readMarches(WjdrEngine engine, MigrantState state, long uid,
                                        long serverNowMillis) {
        List<MarchView> out = new ArrayList<>();
        CoreAssetLibrary library = engine == null ? null : CoreAssetRegistry.global().get(engine);
        if (library != null && library.ready() && library.queue() != null
                && library.queue().marches != null) {
            for (AssetSnapshot.QueueMarch march : library.queue().marches) {
                if (march == null || march.id <= 0L) continue;
                if (march.owner > 0L && uid > 0L && march.owner != uid) continue;
                out.add(new MarchView(march.id, march.owner, march.type, march.state,
                        march.endTime > 0L ? march.endTime * 1000L : 0L,
                        march.endX, march.endY, march.masterMarchId));
            }
            return out;
        }
        if (state != null) {
            for (MigrantState.March march : state.marches()) {
                if (march.id <= 0L) continue;
                if (march.owner > 0L && uid > 0L && march.owner != uid) continue;
                out.add(new MarchView(march.id, march.owner, march.type, march.state,
                        march.endTime > 0L ? march.endTime * 1000L : 0L,
                        march.endX, march.endY, march.masterMarchId));
            }
        }
        return out;
    }

    private long latestEnd(List<MarchView> marches) {
        long latest = 0L;
        for (MarchView march : marches) {
            if (march.endMillis > latest) latest = march.endMillis;
        }
        return latest;
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0L) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("移民线程等待被中断", e);
        }
    }

    static String normalizedMessage(Throwable e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 游戏业务错误码中文描述（原版 GameErrorCatalog 的轻量等价）。 */
    static String describeCode(int code) {
        switch (code) {
            case 0:
                return "成功";
            case MigrantRules.CODE_MIGRATING:
                return "正在移民中";
            case MigrantRules.CODE_MIGRATE_DISCONNECT:
                return "迁服断线";
            case 7401:
                return "条件不满足";
            case 1507:
                return "已领取/不可用";
            case 2827:
                return "道具不足";
            case 2894:
                return "目标状态已变化";
            case 40271:
                return "会话被服务端强制下线";
            default:
                return "服务端拒绝";
        }
    }

    static long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return fallback;
        try {
            return Long.parseLong(text);
        } catch (RuntimeException e) {
            try {
                return (long) Double.parseDouble(text);
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
    }

    static boolean boolValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() != 0L;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return fallback;
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) {
            return true;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    /** 评估结果 + 创建时快照（避免二次进角色线程）。 */
    private static final class EvalResult {
        final MigrantRules.Evaluation evaluation;
        final MigrantState.Snapshot snapshot;

        EvalResult(MigrantRules.Evaluation evaluation, MigrantState.Snapshot snapshot) {
            this.evaluation = evaluation;
            this.snapshot = snapshot;
        }
    }

    /** 任务级致命失败（身份/活动变化等，重试无意义）。 */
    private static final class FatalException extends RuntimeException {
        FatalException(String message) {
            super(message);
        }
    }

    /** 行军视图（资产库/推送两种数据源的统一形状）。 */
    private static final class MarchView {
        final long id;
        final long owner;
        final long type;
        final long state;
        final long endMillis;
        final long endX;
        final long endY;
        final long masterMarchId;

        MarchView(long id, long owner, long type, long state, long endMillis,
                  long endX, long endY, long masterMarchId) {
            this.id = id;
            this.owner = owner;
            this.type = type;
            this.state = state;
            this.endMillis = endMillis;
            this.endX = endX;
            this.endY = endY;
            this.masterMarchId = masterMarchId;
        }

        boolean rally() {
            return type == 99L || type == 100L || type == 102L || masterMarchId > 0L;
        }

        boolean recallable() {
            if (rally()) return false;
            return state == 2L || state == 3L;
        }
    }
}
