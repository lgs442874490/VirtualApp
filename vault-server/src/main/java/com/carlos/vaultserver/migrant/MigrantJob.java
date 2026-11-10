package com.carlos.vaultserver.migrant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 移民任务实体（字段与网页版 job 响应 1:1，契约 §4）。
 *
 * <p>状态机：WAITING → PREPARING（准备窗口内）→ ARMED（准备完成）→ FIRING（发射中）
 * → SUCCEEDED / REJECTED / UNKNOWN / CANCELLED。终态 = SUCCEEDED / REJECTED / UNKNOWN / CANCELLED。
 *
 * <p>线程模型：调度线程/角色会话线程会改字段，HTTP 线程读取；字段用 volatile 保证可见性，
 * attempts 列表用 synchronized 方法保护。持久化由 {@link MigrantJobStore} 负责。
 */
public final class MigrantJob {

    public static final String WAITING = "WAITING";
    public static final String PREPARING = "PREPARING";
    public static final String ARMED = "ARMED";
    public static final String FIRING = "FIRING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String CANCELLED = "CANCELLED";

    /** 默认最大抢位尝试次数（契约 §4）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 200;

    // ---------- 身份（契约 §1 映射：account_id→fpid，server_id→sid，account_role_id→uid） ----------
    public String jobId = "";
    public String fpid = "";
    public long sid;
    public long uid;
    public long accountRoleId;
    public String accountName = "";

    // ---------- 目标 / 活动 ----------
    public int targetKid;
    public String targetName = "";
    public int originalKid;
    public long activityId;
    public long activitySerialNo;
    public int requiredPermits;
    public String configVersion = "";

    // ---------- 状态与时序 ----------
    public volatile String status = WAITING;
    public volatile long freedomStartMillis;
    public volatile long freedomEndMillis;
    public volatile long createdAtMillis;
    public volatile long armedAtMillis;
    public volatile long sentAtMillis;
    public volatile long updatedAtMillis;
    public volatile long finishedAtMillis;
    public volatile long preparationStartMillis;
    public volatile long preparationStartedAtMillis;
    /** 发射任务投递时刻（看门狗用，服务器域/墙钟均只做间隔比较）。 */
    public volatile long fireStartedAtMillis;
    /** 50ms 节拍结束时刻（进入分轮对账；服务器钟）。 */
    public volatile long fireDoneAtMillis;
    /** 对账截止时刻（fireDone + 90s，服务器钟）。 */
    public volatile long reconcileDeadlineMillis;
    /** 对账期间是否出现过成功候选（接受/正在移民/首登）。 */
    public volatile boolean successCandidate;
    /** 对账期间是否出现过传输层失败。 */
    public volatile boolean transportFailure;
    /** 延迟复核（+3min/+10min）下一次到期时刻（服务器钟；0=不再复核）。 */
    public volatile long nextReverifyAtMillis;
    /** 延迟复核轮次（0=+3min，1=+10min，-1=未安排）。 */
    public volatile int reverifyIndex = -1;
    public volatile int attemptCount;
    public int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    public volatile String clockSource = "";
    public volatile long clockEpochMillis;
    public volatile long heartbeatRttMillis;
    public volatile int marchCount;
    public volatile long latestMarchEndMillis;

    // ---------- 准备选项 ----------
    public boolean recallBeforeOpen;
    public volatile boolean recallReady;
    public boolean recallSpeedup;
    public boolean recallDiamondSpeedup;
    public boolean autoExitAlliance;
    public volatile String exitAllianceMessage = "";
    public volatile String recallMessage = "";
    public volatile int speedupTier2Used;
    public volatile int speedupTier1Used;
    public volatile int speedupDiamondUsed;

    // ---------- 结果 ----------
    public volatile Integer responseCode;
    public volatile long reconciledKid;
    public volatile String error = "";
    public int advanceMillis;

    // ---------- 资格预检（网页版 job 响应原样透传） ----------
    public JSONObject precheck = new JSONObject();

    // ---------- 运行时（不落盘） ----------
    /** 最近一次尝试拉起登录的时刻（登录风暴节流，不持久化）。 */
    public volatile long loginAttemptAtMillis;
    /** 准备阶段下一次允许重试的时刻（防止每个 1s 扫描都占用会话线程，不持久化）。 */
    public volatile long prepareRetryAtMillis;
    /** 分轮对账上一次探测投递时刻（5s 节流，不持久化）。 */
    public volatile long reconcileAttemptAtMillis;

    private final List<Attempt> attempts = new ArrayList<>();

    public MigrantJob() {
    }

    // =====================================================================
    // 状态判定（纯函数，单测覆盖）
    // =====================================================================

    /** 非终态 = 仍在调度/执行中。 */
    public static boolean isActive(String status) {
        return WAITING.equals(status) || PREPARING.equals(status) || ARMED.equals(status)
                || FIRING.equals(status);
    }

    /** 终态（契约 §5）。 */
    public static boolean isTerminal(String status) {
        return !isActive(status);
    }

    /** 仅 WAITING 可取消（网页版同口径）。 */
    public static boolean isCancelable(String status) {
        return WAITING.equals(status);
    }

    /** 状态中文标签（网页版同文案）。 */
    public static String labelOf(String status) {
        if (WAITING.equals(status)) return "等待开放";
        if (PREPARING.equals(status)) return "刷新预检";
        if (ARMED.equals(status)) return "已就绪";
        if (FIRING.equals(status)) return "正在提交";
        if (SUCCEEDED.equals(status)) return "移民成功";
        if (REJECTED.equals(status)) return "未执行/被拒绝";
        if (UNKNOWN.equals(status)) return "结果待人工核验";
        if (CANCELLED.equals(status)) return "已取消";
        return String.valueOf(status);
    }

    /** 未知/空状态归一为 UNKNOWN（恢复/反序列化容错）。 */
    public static String normalizeStatus(String raw) {
        if (raw == null) return UNKNOWN;
        String s = raw.trim().toUpperCase();
        if (WAITING.equals(s) || PREPARING.equals(s) || ARMED.equals(s) || FIRING.equals(s)
                || SUCCEEDED.equals(s) || REJECTED.equals(s) || UNKNOWN.equals(s)
                || CANCELLED.equals(s)) {
            return s;
        }
        return UNKNOWN;
    }

    public boolean active() {
        return isActive(status);
    }

    public boolean cancelable() {
        return isCancelable(status);
    }

    public void touch() {
        updatedAtMillis = System.currentTimeMillis();
    }

    /** 进入终态（统一更新 finishedAtMillis/updatedAtMillis）。 */
    public synchronized void finish(String nextStatus, String message) {
        status = nextStatus;
        error = message == null ? "" : message;
        long now = System.currentTimeMillis();
        updatedAtMillis = now;
        finishedAtMillis = now;
    }

    // =====================================================================
    // 尝试记录（attempts action 用；随任务一起落盘）
    // =====================================================================

    public synchronized void addAttempt(Attempt attempt) {
        if (attempt == null) return;
        attempts.add(attempt);
        while (attempts.size() > Math.max(DEFAULT_MAX_ATTEMPTS, maxAttempts)) {
            attempts.remove(0);
        }
        attemptCount = attempt.attemptNo;
        touch();
    }

    public synchronized List<Attempt> attemptsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(attempts));
    }

    public synchronized int attemptHistorySize() {
        return attempts.size();
    }

    // =====================================================================
    // JSON
    // =====================================================================

    /** 网页版 job 响应字段（1:1 + 本项目身份字段）。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("job_id", jobId);
        o.put("status", status);
        o.put("status_label", labelOf(status));
        o.put("active", isActive(status));
        o.put("cancelable", isCancelable(status));
        o.put("account_role_id", accountRoleId > 0L ? accountRoleId : uid);
        o.put("account_name", accountName);
        o.put("fpid", fpid);
        o.put("server_id", sid);
        o.put("uid", uid);
        o.put("original_kid", originalKid);
        o.put("target_kid", targetKid);
        o.put("target_name", targetName);
        o.put("activity_id", activityId);
        o.put("activity_serial_no", activitySerialNo);
        o.put("required_permits", requiredPermits);
        o.put("config_version", configVersion);
        o.put("freedom_start_millis", freedomStartMillis);
        o.put("freedom_end_millis", freedomEndMillis);
        o.put("advance_millis", advanceMillis);
        o.put("created_at_millis", createdAtMillis);
        o.put("updated_at_millis", updatedAtMillis);
        o.put("armed_at_millis", armedAtMillis);
        o.put("sent_at_millis", sentAtMillis);
        o.put("finished_at_millis", finishedAtMillis);
        o.put("preparation_start_millis", preparationStartMillis);
        o.put("preparation_started_at_millis", preparationStartedAtMillis);
        o.put("fire_started_at_millis", fireStartedAtMillis);
        o.put("fire_done_at_millis", fireDoneAtMillis);
        o.put("reconcile_deadline_millis", reconcileDeadlineMillis);
        o.put("success_candidate", successCandidate);
        o.put("transport_failure", transportFailure);
        o.put("next_reverify_at_millis", nextReverifyAtMillis);
        o.put("reverify_index", reverifyIndex);
        o.put("attempt_count", attemptCount);
        o.put("max_attempts", maxAttempts);
        o.put("clock_source", clockSource);
        o.put("clock_epoch_millis", clockEpochMillis);
        o.put("heartbeat_rtt_millis", heartbeatRttMillis);
        o.put("march_count", marchCount);
        o.put("latest_march_end_millis", latestMarchEndMillis);
        o.put("recall_before_open", recallBeforeOpen);
        o.put("recall_ready", recallReady);
        o.put("recall_speedup", recallSpeedup);
        o.put("recall_diamond_speedup", recallDiamondSpeedup);
        o.put("auto_exit_alliance", autoExitAlliance);
        o.put("exit_alliance_message", exitAllianceMessage);
        o.put("recall_message", recallMessage);
        o.put("speedup_tier2_used", speedupTier2Used);
        o.put("speedup_tier1_used", speedupTier1Used);
        o.put("speedup_diamond_used", speedupDiamondUsed);
        o.put("response_code", responseCode == null ? JSONObject.NULL : responseCode);
        o.put("reconciled_kid", reconciledKid);
        o.put("error", error);
        o.put("precheck", precheck == null ? new JSONObject() : precheck);
        return o;
    }

    /** 落盘 JSON（在 job 响应基础上带 attempts，供重启恢复）。 */
    public JSONObject toStoreJson() {
        JSONObject o = toJson();
        JSONArray arr = new JSONArray();
        for (Attempt a : attemptsSnapshot()) arr.put(a.toJson());
        o.put("attempts", arr);
        return o;
    }

    /** 从落盘 JSON 恢复；缺字段走默认值，坏状态归 UNKNOWN。 */
    public static MigrantJob fromJson(JSONObject o) {
        MigrantJob job = new MigrantJob();
        if (o == null) return job;
        job.jobId = o.optString("job_id", "");
        job.fpid = o.optString("fpid", "");
        job.sid = o.optLong("server_id", 0L);
        job.uid = o.optLong("uid", 0L);
        job.accountRoleId = o.optLong("account_role_id", job.uid);
        job.accountName = o.optString("account_name", "");
        job.originalKid = o.optInt("original_kid", 0);
        job.targetKid = o.optInt("target_kid", 0);
        job.targetName = o.optString("target_name", "");
        job.activityId = o.optLong("activity_id", 0L);
        job.activitySerialNo = o.optLong("activity_serial_no", 0L);
        job.requiredPermits = o.optInt("required_permits", 0);
        job.configVersion = o.optString("config_version", "");
        job.status = normalizeStatus(o.optString("status", WAITING));
        job.freedomStartMillis = o.optLong("freedom_start_millis", 0L);
        job.freedomEndMillis = o.optLong("freedom_end_millis", 0L);
        job.advanceMillis = clampAdvance(o.optInt("advance_millis", 0));
        job.createdAtMillis = o.optLong("created_at_millis", 0L);
        job.updatedAtMillis = o.optLong("updated_at_millis", job.createdAtMillis);
        job.armedAtMillis = o.optLong("armed_at_millis", 0L);
        job.sentAtMillis = o.optLong("sent_at_millis", 0L);
        job.finishedAtMillis = o.optLong("finished_at_millis", 0L);
        job.preparationStartMillis = o.optLong("preparation_start_millis", 0L);
        job.preparationStartedAtMillis = o.optLong("preparation_started_at_millis", 0L);
        job.fireStartedAtMillis = o.optLong("fire_started_at_millis", 0L);
        job.fireDoneAtMillis = o.optLong("fire_done_at_millis", 0L);
        job.reconcileDeadlineMillis = o.optLong("reconcile_deadline_millis", 0L);
        job.successCandidate = o.optBoolean("success_candidate", false);
        job.transportFailure = o.optBoolean("transport_failure", false);
        job.nextReverifyAtMillis = o.optLong("next_reverify_at_millis", 0L);
        job.reverifyIndex = o.optInt("reverify_index", -1);
        job.attemptCount = o.optInt("attempt_count", 0);
        job.maxAttempts = o.optInt("max_attempts", DEFAULT_MAX_ATTEMPTS);
        job.clockSource = o.optString("clock_source", "");
        job.clockEpochMillis = o.optLong("clock_epoch_millis", 0L);
        job.heartbeatRttMillis = o.optLong("heartbeat_rtt_millis", 0L);
        job.marchCount = o.optInt("march_count", 0);
        job.latestMarchEndMillis = o.optLong("latest_march_end_millis", 0L);
        job.recallBeforeOpen = o.optBoolean("recall_before_open", false);
        job.recallReady = o.optBoolean("recall_ready", false);
        job.recallSpeedup = o.optBoolean("recall_speedup", false);
        job.recallDiamondSpeedup = o.optBoolean("recall_diamond_speedup", false);
        job.autoExitAlliance = o.optBoolean("auto_exit_alliance", false);
        job.exitAllianceMessage = o.optString("exit_alliance_message", "");
        job.recallMessage = o.optString("recall_message", "");
        job.speedupTier2Used = o.optInt("speedup_tier2_used", 0);
        job.speedupTier1Used = o.optInt("speedup_tier1_used", 0);
        job.speedupDiamondUsed = o.optInt("speedup_diamond_used", 0);
        if (!o.isNull("response_code")) job.responseCode = o.optInt("response_code", 0);
        job.reconciledKid = o.optLong("reconciled_kid", 0L);
        job.error = o.optString("error", "");
        JSONObject pre = o.optJSONObject("precheck");
        job.precheck = pre == null ? new JSONObject() : pre;
        JSONArray arr = o.optJSONArray("attempts");
        if (arr != null) {
            synchronized (job) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ao = arr.optJSONObject(i);
                    if (ao != null) job.attempts.add(Attempt.fromJson(ao));
                }
            }
        }
        return job;
    }

    /** advance_millis 服务端钳位 [0,100]（契约 §4）。 */
    public static int clampAdvance(int value) {
        if (value < 0) return 0;
        if (value > 100) return 100;
        return value;
    }

    /** 单次迁移请求记录（网页版 attempts 字段 1:1）。 */
    public static final class Attempt {
        public int attemptNo;
        public long sentAtMillis;
        /** 主机墙钟发送时刻（仅诊断；V2 判定不得用它）。 */
        public long hostSentAtMillis;
        public long responseAtMillis;
        public long latencyMillis;
        public long serverNowMillis;
        public boolean serverClockReady;
        public String phase = "";
        public String phaseLabel = "";
        public String outcome = "";
        public String outcomeLabel = "";
        public Integer responseCode;
        public String requestText = "";
        public String responseText = "";
        public String error = "";

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("attempt_no", attemptNo);
            o.put("sent_at_millis", sentAtMillis);
            o.put("host_sent_at_millis", hostSentAtMillis);
            o.put("response_at_millis", responseAtMillis);
            o.put("latency_millis", latencyMillis);
            o.put("server_now_millis", serverNowMillis);
            o.put("server_clock_ready", serverClockReady);
            o.put("phase", phase);
            o.put("phase_label", phaseLabel);
            o.put("outcome", outcome);
            o.put("outcome_label", outcomeLabel);
            o.put("response_code", responseCode == null ? JSONObject.NULL : responseCode);
            o.put("request_text", requestText);
            o.put("response_text", responseText);
            o.put("error", error);
            return o;
        }

        public static Attempt fromJson(JSONObject o) {
            Attempt a = new Attempt();
            if (o == null) return a;
            a.attemptNo = o.optInt("attempt_no", 0);
            a.sentAtMillis = o.optLong("sent_at_millis", 0L);
            a.hostSentAtMillis = o.optLong("host_sent_at_millis", 0L);
            a.responseAtMillis = o.optLong("response_at_millis", 0L);
            a.latencyMillis = o.optLong("latency_millis", 0L);
            a.serverNowMillis = o.optLong("server_now_millis", 0L);
            a.serverClockReady = o.optBoolean("server_clock_ready", false);
            a.phase = o.optString("phase", "");
            a.phaseLabel = o.optString("phase_label", "");
            a.outcome = o.optString("outcome", "");
            a.outcomeLabel = o.optString("outcome_label", "");
            if (!o.isNull("response_code")) a.responseCode = o.optInt("response_code", 0);
            a.requestText = o.optString("request_text", "");
            a.responseText = o.optString("response_text", "");
            a.error = o.optString("error", "");
            return a;
        }
    }
}
