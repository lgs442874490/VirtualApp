package com.carlos.vault.migrant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 藏宝阁·自由移民的数据模型与展示映射（纯 Java，无 Android 依赖，可 JVM 直测）。
 *
 * <p><b>唯一权威基准</b>：网页版 {@code MigrantWorkspace-C_ZVt8cF.js}。本类的每个
 * {@code static} 映射函数都与其同名 JS 函数逐条对应（注释标注行号语义），
 * 用于保证「行为与信息结构 1:1」；视觉分层由 {@link MigrantWorkspaceActivity} 按
 * {@code design/UI规范.md} 落地。
 *
 * <p>字段名严格按《移民功能-实施契约-20261106》§3/§4 的 snake_case 取值，
 * 不因服务端实现差异改名（两端契约由 Lead 对齐）。
 */
public final class MigrantModels {

    private MigrantModels() {
    }

    // ==================== 常量（契约 §4 时序/成本，不得改值）====================

    /** 准备窗口：自由移民开放前 15 分钟上号准备。 */
    public static final long PREPARE_WINDOW_MILLIS = 15L * 60L * 1000L;
    /** 就绪目标：准备在开放前 2 分钟完成。 */
    public static final long READY_DEADLINE_BEFORE_MILLIS = 2L * 60L * 1000L;
    /** 召回道具钻石单价（网页版文案「召回道具 800 钻」）。 */
    public static final int RECALL_ITEM_DIAMOND_COST = 800;
    /** 行军加速器钻石单价（网页版文案「加速器 400 钻」）。 */
    public static final int SPEEDUP_DIAMOND_COST = 400;
    /** 钻石兜底上限（每个任务 10 个）。 */
    public static final int DIAMOND_FALLBACK_LIMIT = 10;
    /** 提前量取值范围（服务端同样钳位 [0,100] 毫秒）。 */
    public static final int ADVANCE_MIN = 0;
    public static final int ADVANCE_MAX = 100;
    /** 活动阶段时长：展望 3 天 / 邀请 2 天 / 自由移民 2 天（网页版时间轴标签）。 */
    public static final long PROSPECT_DAYS = 3L;
    public static final long INVITATION_DAYS = 2L;
    public static final long FREEDOM_DAYS = 2L;

    public static final String STAGE_PROSPECTS = "PROSPECTS";
    public static final String STAGE_INVITATION = "INVITATION";
    public static final String STAGE_FREEDOM = "FREEDOM";
    public static final String STAGE_CLOSED = "CLOSED";

    /** 服务端时间展示时区（与网页版 execution-audit 一致：Asia/Shanghai）。 */
    public static final TimeZone DISPLAY_TZ = TimeZone.getTimeZone("Asia/Shanghai");

    // ==================== 数值访问 ====================

    /** 安全取 long（null/NaN → 0）。 */
    public static long num(Object o) {
        if (o == null || JSONObject.NULL.equals(o)) return 0L;
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return 0L;
            return ((Number) o).longValue();
        }
        try {
            double d = Double.parseDouble(String.valueOf(o).trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) return 0L;
            return (long) d;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 可选整型：缺字段/null → {@code null}（网页版 {@code response_code !== null} 判定）。 */
    public static Integer optInt(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        try {
            Object v = o.opt(key);
            if (v == null || JSONObject.NULL.equals(v)) return null;
            return (int) num(v);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean bool(JSONObject o, String key) {
        if (o == null) return false;
        try {
            Object v = o.opt(key);
            if (v == null || JSONObject.NULL.equals(v)) return false;
            if (v instanceof Boolean) return ((Boolean) v).booleanValue();
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            return "true".equalsIgnoreCase(String.valueOf(v).trim());
        } catch (Throwable t) {
            return false;
        }
    }

    public static String str(JSONObject o, String key, String def) {
        if (o == null) return def;
        try {
            String s = o.optString(key, def);
            return s == null ? def : s;
        } catch (Throwable t) {
            return def;
        }
    }

    // ==================== 模型 ====================

    /** 活动元数据（{@code overview.activity}）。 */
    public static final class Activity {
        public String serialNo = "";
        public String configVersion = "";
        public long startMillis;
        public long freedomStartMillis;
        public long endMillis;

        static Activity parse(JSONObject o) {
            Activity a = new Activity();
            if (o == null) return a;
            a.serialNo = str(o, "serial_no", "");
            a.configVersion = str(o, "config_version", "");
            a.startMillis = num(o.opt("start_millis"));
            a.freedomStartMillis = num(o.opt("freedom_start_millis"));
            a.endMillis = num(o.opt("end_millis"));
            return a;
        }

        /** 时间轴可用：起止时刻齐全且终点晚于起点（网页版 {@code ye}）。 */
        public boolean hasSpan() {
            return startMillis > 0 && endMillis > startMillis;
        }
    }

    /** 目标王国条目（{@code overview.kingdoms[]}）。 */
    public static final class Kingdom {
        public long kid;
        public String label = "";
        public int state;
        public int powerLimit;
        public long powerLimitValue;
        public long migrantNum;
        public long normalMigrantNum;
        public long remainingQuota;

        static Kingdom parse(JSONObject o) {
            Kingdom k = new Kingdom();
            if (o == null) return k;
            k.kid = num(o.opt("kid"));
            k.label = str(o, "label", "");
            k.state = (int) num(o.opt("state"));
            k.powerLimit = (int) num(o.opt("power_limit"));
            k.powerLimitValue = num(o.opt("power_limit_value"));
            k.migrantNum = num(o.opt("migrant_num"));
            k.normalMigrantNum = num(o.opt("normal_migrant_num"));
            k.remainingQuota = num(o.opt("remaining_quota"));
            return k;
        }

        /** 强盛王国（{@code state === 1}）。 */
        public boolean strong() {
            return state == 1;
        }
    }

    /** 资格预检结果（{@code precheck}）。 */
    public static final class Precheck {
        public boolean ready;
        public long permitsOwned;
        public long permitsRequired;
        public long remainingQuota;
        public List<Check> checks = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();

        static Precheck parse(JSONObject o) {
            Precheck p = new Precheck();
            if (o == null) return p;
            p.ready = bool(o, "ready");
            p.permitsOwned = num(o.opt("permits_owned"));
            p.permitsRequired = num(o.opt("permits_required"));
            p.remainingQuota = num(o.opt("remaining_quota"));
            JSONArray cs = o.optJSONArray("checks");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    Check c = Check.parse(cs.optJSONObject(i));
                    if (c != null) p.checks.add(c);
                }
            }
            JSONArray ws = o.optJSONArray("warnings");
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    String w = ws.optString(i, "");
                    if (w != null && !w.isEmpty()) p.warnings.add(w);
                }
            }
            return p;
        }

        /** 授权书文案（网页版 {@code bl}：{@code owned / required}，无预检为 {@code -}）。 */
        public String permitText() {
            return fmtInt(permitsOwned) + " / " + permitsRequired;
        }
    }

    /** 单条资格检查项。 */
    public static final class Check {
        public String id = "";
        public String label = "";
        public String detail = "";
        public boolean passed;

        static Check parse(JSONObject o) {
            if (o == null) return null;
            Check c = new Check();
            c.id = str(o, "id", "");
            c.label = str(o, "label", "");
            c.detail = str(o, "detail", "");
            c.passed = bool(o, "passed");
            return c;
        }
    }

    /** 活动总览（{@code overview}）。 */
    public static final class Overview {
        public Activity activity = new Activity();
        public boolean activityOpen = true;
        public boolean stateComplete;
        public String message = "";
        /** 网页版 state：FREEDOM / PROSPECT / INVITATION / CLOSED。 */
        public String state = "";
        public long freedomStartMillis;
        public long endMillis;
        public long currentKid;
        public long serverNowMillis;
        public List<Kingdom> kingdoms = new ArrayList<>();

        static Overview parse(JSONObject o) {
            if (o == null) return null;
            // 2026-11-06 裁决：服务端信封会把 null 兜成 {}，故服务端离线改为显式
            // 返回 {online:false, message}；此处按 online=false 视为「无概览」（表单禁用、提示上线）。
            if (o.has("online") && !o.optBoolean("online", true)) return null;
            Overview ov = new Overview();
            ov.activity = Activity.parse(o.optJSONObject("activity"));
            ov.activityOpen = o.has("activity_open") ? bool(o, "activity_open") : true;
            ov.stateComplete = bool(o, "state_complete");
            ov.message = str(o, "message", "");
            ov.state = str(o, "state", "");
            ov.freedomStartMillis = num(o.opt("freedom_start_millis"));
            ov.endMillis = num(o.opt("end_millis"));
            ov.currentKid = num(o.opt("current_kid"));
            ov.serverNowMillis = num(o.opt("server_now_millis"));
            JSONArray ks = o.optJSONArray("kingdoms");
            if (ks != null) {
                for (int i = 0; i < ks.length(); i++) {
                    Kingdom k = Kingdom.parse(ks.optJSONObject(i));
                    if (k.kid > 0) ov.kingdoms.add(k);
                }
            }
            return ov;
        }

        public Kingdom kingdom(long kid) {
            for (Kingdom k : kingdoms) {
                if (k.kid == kid) return k;
            }
            return null;
        }
    }

    /** 迁移任务（契约 §4，与网页版 job 响应 1:1）。 */
    public static final class Job {
        public String jobId = "";
        public long accountRoleId;
        public String accountName = "";
        public long targetKid;
        public String targetName = "";
        public long originalKid;
        public String status = "";
        public String statusLabel = "";
        public long freedomStartMillis;
        public long createdAtMillis;
        public long armedAtMillis;
        public long sentAtMillis;
        public long updatedAtMillis;
        public long finishedAtMillis;
        public int attemptCount;
        public int maxAttempts = 200;
        public String clockSource = "";
        public long clockEpochMillis;
        public long heartbeatRttMillis;
        public int marchCount;
        public long latestMarchEndMillis;
        public boolean recallBeforeOpen;
        public boolean recallReady;
        public boolean recallSpeedup;
        public boolean recallDiamondSpeedup;
        public long preparationStartMillis;
        public long preparationStartedAtMillis;
        public boolean autoExitAlliance;
        public String exitAllianceMessage = "";
        public int speedupTier2Used;
        public int speedupTier1Used;
        public int speedupDiamondUsed;
        public Integer responseCode;
        public long reconciledKid;
        public String error = "";
        public int advanceMillis;
        public boolean active;
        public boolean cancelable;
        public Precheck precheck;

        static Job parse(JSONObject o) {
            if (o == null) return null;
            Job j = new Job();
            j.jobId = str(o, "job_id", "");
            j.accountRoleId = num(o.opt("account_role_id"));
            j.accountName = str(o, "account_name", "");
            j.targetKid = num(o.opt("target_kid"));
            j.targetName = str(o, "target_name", "");
            j.originalKid = num(o.opt("original_kid"));
            j.status = str(o, "status", "").toUpperCase(Locale.US);
            j.statusLabel = str(o, "status_label", "");
            j.freedomStartMillis = num(o.opt("freedom_start_millis"));
            j.createdAtMillis = num(o.opt("created_at_millis"));
            j.armedAtMillis = num(o.opt("armed_at_millis"));
            j.sentAtMillis = num(o.opt("sent_at_millis"));
            j.updatedAtMillis = num(o.opt("updated_at_millis"));
            j.finishedAtMillis = num(o.opt("finished_at_millis"));
            j.attemptCount = (int) num(o.opt("attempt_count"));
            int max = (int) num(o.opt("max_attempts"));
            j.maxAttempts = max > 0 ? max : 200;
            j.clockSource = str(o, "clock_source", "");
            j.clockEpochMillis = num(o.opt("clock_epoch_millis"));
            j.heartbeatRttMillis = num(o.opt("heartbeat_rtt_millis"));
            j.marchCount = (int) num(o.opt("march_count"));
            j.latestMarchEndMillis = num(o.opt("latest_march_end_millis"));
            j.recallBeforeOpen = bool(o, "recall_before_open");
            j.recallReady = bool(o, "recall_ready");
            j.recallSpeedup = bool(o, "recall_speedup");
            j.recallDiamondSpeedup = bool(o, "recall_diamond_speedup");
            j.preparationStartMillis = num(o.opt("preparation_start_millis"));
            j.preparationStartedAtMillis = num(o.opt("preparation_started_at_millis"));
            j.autoExitAlliance = bool(o, "auto_exit_alliance");
            j.exitAllianceMessage = str(o, "exit_alliance_message", "");
            j.speedupTier2Used = (int) num(o.opt("speedup_tier2_used"));
            j.speedupTier1Used = (int) num(o.opt("speedup_tier1_used"));
            j.speedupDiamondUsed = (int) num(o.opt("speedup_diamond_used"));
            j.responseCode = optInt(o, "response_code");
            j.reconciledKid = num(o.opt("reconciled_kid"));
            j.error = str(o, "error", "");
            j.advanceMillis = (int) num(o.opt("advance_millis"));
            j.active = bool(o, "active");
            j.cancelable = bool(o, "cancelable");
            j.precheck = Precheck.parse(o.optJSONObject("precheck"));
            return j;
        }

        /** 用于列表展示的角色名（网页版 {@code s.account_name || "角色 " + account_role_id}）。 */
        public String displayName() {
            return accountName != null && !accountName.isEmpty()
                    ? accountName : "角色 " + accountRoleId;
        }
    }

    /** 任务列表（{@code jobs}）：active = 非终态，finished 按 updated_at 倒序最多 50。 */
    public static final class Jobs {
        public List<Job> active = new ArrayList<>();
        public List<Job> finished = new ArrayList<>();

        static Jobs parse(JSONObject o) {
            Jobs j = new Jobs();
            if (o == null) return j;
            JSONArray a = o.optJSONArray("active");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    Job job = Job.parse(a.optJSONObject(i));
                    if (job != null) j.active.add(job);
                }
            }
            JSONArray f = o.optJSONArray("finished");
            if (f != null) {
                for (int i = 0; i < f.length(); i++) {
                    Job job = Job.parse(f.optJSONObject(i));
                    if (job != null) j.finished.add(job);
                }
            }
            return j;
        }
    }

    /** 单支行军（{@code marches.marches[]}）。 */
    public static final class March {
        public long marchId;
        public String type = "";
        public double x = Double.NaN;
        public double y = Double.NaN;
        public long endMillis;
        public long remainingMillis;
        public boolean recalled;
        public boolean rally;
        public boolean recallable;
        public String recallDisabledReason = "";
        public boolean requiresRecallItem;
        public String recallItemName = "";
        public long recallItemCount;
        public boolean recallItemSynced;
        public String recallAction = "";
        /** 页面展示的状态文案（服务端给 {@code state}，缺省回退 {@code state_code} 映射）。 */
        public String state = "";
        public int stateCode;
    }

    /** 撤退详情（{@code marches}）。 */
    public static final class MarchInfo {
        public boolean online;
        public boolean manualRecallAllowed;
        public String manualRecallBlockReason = "";
        public boolean recallBeforeOpen;
        public int nonQueueMarchCount;
        public long freedomStartMillis;
        public long preparationStartMillis;
        public long preparationDeadlineMillis;
        public String recallMessage = "";
        public List<March> marches = new ArrayList<>();

        static MarchInfo parse(JSONObject o) {
            if (o == null) return null;
            MarchInfo m = new MarchInfo();
            m.online = bool(o, "online");
            m.manualRecallAllowed = bool(o, "manual_recall_allowed");
            m.manualRecallBlockReason = str(o, "manual_recall_block_reason", "");
            m.recallBeforeOpen = bool(o, "recall_before_open");
            m.nonQueueMarchCount = (int) num(o.opt("non_queue_march_count"));
            m.freedomStartMillis = num(o.opt("freedom_start_millis"));
            m.preparationStartMillis = num(o.opt("preparation_start_millis"));
            m.preparationDeadlineMillis = num(o.opt("preparation_deadline_millis"));
            m.recallMessage = str(o, "recall_message", "");
            JSONArray arr = o.optJSONArray("marches");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject mo = arr.optJSONObject(i);
                    if (mo == null) continue;
                    March mch = new March();
                    mch.marchId = num(mo.opt("march_id"));
                    mch.type = str(mo, "type", "");
                    JSONObject pt = mo.optJSONObject("end_point");
                    if (pt != null && pt.has("x") && pt.has("y")) {
                        mch.x = num(pt.opt("x"));
                        mch.y = num(pt.opt("y"));
                    }
                    // 契约 §3.10 未约定 end_point：服务端当前把坐标放在顶层 x/y，两种形状都吃
                    if (Double.isNaN(mch.x) && mo.has("x") && mo.has("y")) {
                        mch.x = num(mo.opt("x"));
                        mch.y = num(mo.opt("y"));
                    }
                    mch.endMillis = num(mo.opt("end_millis"));
                    mch.remainingMillis = num(mo.opt("remaining_millis"));
                    mch.recalled = bool(mo, "recalled");
                    mch.rally = bool(mo, "rally");
                    mch.recallable = bool(mo, "recallable");
                    mch.recallDisabledReason = str(mo, "recall_disabled_reason", "");
                    mch.requiresRecallItem = bool(mo, "requires_recall_item");
                    mch.recallItemName = str(mo, "recall_item_name", "");
                    mch.recallItemCount = num(mo.opt("recall_item_count"));
                    mch.recallItemSynced = bool(mo, "recall_item_synced");
                    mch.recallAction = str(mo, "recall_action", "");
                    mch.state = str(mo, "state", "");
                    mch.stateCode = (int) num(mo.opt("state_code"));
                    m.marches.add(mch);
                }
            }
            return m;
        }

        /** 未召回的在外部队数（网页版 {@code G}）。 */
        public int pendingCount() {
            int n = 0;
            for (March m : marches) {
                if (!m.recalled) n++;
            }
            return n;
        }
    }

    /** 召回单支结果。 */
    public static final class RecallItem {
        public long marchId;
        public boolean success;
        public String message = "";
        public String recallItemName = "";
    }

    /** 召回结果（{@code recall}）。 */
    public static final class RecallResult {
        public List<RecallItem> results = new ArrayList<>();
        public int recalled;
        public boolean requiresDiamond;
        public String message = "";

        static RecallResult parse(JSONObject o) {
            if (o == null) return null;
            RecallResult r = new RecallResult();
            JSONArray arr = o.optJSONArray("results");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ro = arr.optJSONObject(i);
                    if (ro == null) continue;
                    RecallItem it = new RecallItem();
                    it.marchId = num(ro.opt("march_id"));
                    it.success = bool(ro, "success");
                    it.message = str(ro, "message", "");
                    it.recallItemName = str(ro, "recall_item_name", "");
                    r.results.add(it);
                }
            }
            r.recalled = (int) num(o.opt("recalled"));
            r.requiresDiamond = bool(o, "requires_diamond");
            r.message = str(o, "message", "");
            return r;
        }

        /** 召回道具名（取首个带名的结果，兜底「召回道具」）。 */
        public String recallItemName() {
            for (RecallItem it : results) {
                if (it.recallItemName != null && !it.recallItemName.isEmpty()) {
                    return it.recallItemName;
                }
            }
            return "召回道具";
        }
    }

    /** 单次迁入请求记录（{@code attempts.attempts[]}）。 */
    public static final class Attempt {
        public int attemptNo;
        public long sentAtMillis;
        public long responseAtMillis;
        public long serverNowMillis;
        public boolean serverClockReady;
        public String phase = "";
        public String phaseLabel = "";
        public String outcome = "";
        public String outcomeLabel = "";
        public Integer responseCode;
        public String requestText = "";
        public String responseText = "";
        public long latencyMillis;
        public long hostClockOffsetMillis;
        public long heartbeatRttMillis;
        public String requestJson = "";
        public String responseJson = "";
        public String requestSession = "";
        public boolean probe;
        public String error = "";
    }

    /** 请求审计（{@code attempts}）：任务汇总 + 逐次记录。 */
    public static final class Attempts {
        public long firstAttemptAtMillis;
        public long lastAttemptAtMillis;
        public long firstResponseAtMillis;
        public long lastResponseAtMillis;
        public int attemptCount;
        public String status = "";
        public String statusLabel = "";
        public Integer responseCode;
        public String clockSource = "";
        public long clockEpochMillis;
        public long hostClockOffsetMillis;
        public long heartbeatRttMillis;
        public int clockSyncCount;
        public long clockSyncedAtMillis;
        public String accountName = "";
        public long targetKid;
        public String targetName = "";
        public long freedomStartMillis;
        public long createdAtMillis;
        public long armedAtMillis;
        public long sentAtMillis;
        public long finishedAtMillis;
        public long serverNowMillis;
        public String error = "";
        public List<Attempt> attempts = new ArrayList<>();

        static Attempts parse(JSONObject o) {
            if (o == null) return null;
            Attempts a = new Attempts();
            a.firstAttemptAtMillis = num(o.opt("first_attempt_at_millis"));
            a.lastAttemptAtMillis = num(o.opt("last_attempt_at_millis"));
            a.firstResponseAtMillis = num(o.opt("first_response_at_millis"));
            a.lastResponseAtMillis = num(o.opt("last_response_at_millis"));
            a.attemptCount = (int) num(o.opt("attempt_count"));
            a.status = str(o, "status", "").toUpperCase(Locale.US);
            a.statusLabel = str(o, "status_label", "");
            a.responseCode = optInt(o, "response_code");
            a.clockSource = str(o, "clock_source", "");
            a.clockEpochMillis = num(o.opt("clock_epoch_millis"));
            a.hostClockOffsetMillis = num(o.opt("host_clock_offset_millis"));
            a.heartbeatRttMillis = num(o.opt("heartbeat_rtt_millis"));
            a.clockSyncCount = (int) num(o.opt("clock_sync_count"));
            a.clockSyncedAtMillis = num(o.opt("clock_synced_at_millis"));
            a.accountName = str(o, "account_name", "");
            a.targetKid = num(o.opt("target_kid"));
            a.targetName = str(o, "target_name", "");
            a.freedomStartMillis = num(o.opt("freedom_start_millis"));
            a.createdAtMillis = num(o.opt("created_at_millis"));
            a.armedAtMillis = num(o.opt("armed_at_millis"));
            a.sentAtMillis = num(o.opt("sent_at_millis"));
            a.finishedAtMillis = num(o.opt("finished_at_millis"));
            a.serverNowMillis = num(o.opt("server_now_millis"));
            a.error = str(o, "error", "");
            JSONArray arr = o.optJSONArray("attempts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ao = arr.optJSONObject(i);
                    if (ao == null) continue;
                    Attempt at = new Attempt();
                    at.attemptNo = (int) num(ao.opt("attempt_no"));
                    at.sentAtMillis = num(ao.opt("sent_at_millis"));
                    at.responseAtMillis = num(ao.opt("response_at_millis"));
                    at.serverNowMillis = num(ao.opt("server_now_millis"));
                    at.serverClockReady = bool(ao, "server_clock_ready");
                    at.phase = str(ao, "phase", "");
                    // phase_label 由服务端补；缺失时展示层用 phaseLabelOf() 的内置中文映射，
                    // 不在此处回填英文 phase（否则徽标会显示 BURST 这类内部代号）。
                    at.phaseLabel = str(ao, "phase_label", "");
                    at.outcome = str(ao, "outcome", "");
                    at.outcomeLabel = str(ao, "outcome_label", "");
                    if (at.outcomeLabel.isEmpty()) at.outcomeLabel = at.outcome;
                    at.responseCode = optInt(ao, "response_code");
                    at.requestText = str(ao, "request_text", "");
                    at.responseText = str(ao, "response_text", "");
                    at.latencyMillis = num(ao.opt("latency_millis"));
                    // 服务端只落 request_text/response_text：JSON 展示回退到文本，避免「—」
                    at.requestJson = str(ao, "request_json", "");
                    if (at.requestJson.isEmpty()) at.requestJson = at.requestText;
                    at.responseJson = str(ao, "response_json", "");
                    if (at.responseJson.isEmpty()) at.responseJson = at.responseText;
                    at.hostClockOffsetMillis = num(ao.opt("host_clock_offset_millis"));
                    at.heartbeatRttMillis = num(ao.opt("heartbeat_rtt_millis"));
                    at.requestSession = str(ao, "request_session", "");
                    at.probe = bool(ao, "probe");
                    at.error = str(ao, "error", "");
                    a.attempts.add(at);
                }
            }
            for (Attempt at : a.attempts) {
                if (at.latencyMillis <= 0 && at.sentAtMillis > 0 && at.responseAtMillis > at.sentAtMillis) {
                    at.latencyMillis = at.responseAtMillis - at.sentAtMillis;
                }
            }
            Collections.sort(a.attempts, (x, y) -> Integer.compare(x.attemptNo, y.attemptNo));
            return a;
        }

        /** 已对时的请求数（{@code server_clock_ready && server_now > 0}）。 */
        public int syncedCount() {
            int n = 0;
            for (Attempt at : attempts) {
                if (at.serverClockReady && at.serverNowMillis > 0) n++;
            }
            return n;
        }
    }

    /** 批量创建结果（{@code bulk}）。 */
    public static final class BulkResult {
        public int requested;
        public List<String> created = new ArrayList<>();
        public final List<Failure> failed = new ArrayList<>();

        static BulkResult parse(JSONObject o) {
            if (o == null) return null;
            BulkResult b = new BulkResult();
            b.requested = (int) num(o.opt("requested"));
            JSONArray c = o.optJSONArray("created");
            if (c != null) {
                for (int i = 0; i < c.length(); i++) {
                    String id = c.optString(i, "");
                    if (id != null && !id.isEmpty()) b.created.add(id);
                }
            }
            JSONArray f = o.optJSONArray("failed");
            if (f != null) {
                for (int i = 0; i < f.length(); i++) {
                    JSONObject fo = f.optJSONObject(i);
                    if (fo == null) continue;
                    Failure fail = new Failure();
                    fail.accountRoleId = num(fo.opt("account_role_id"));
                    fail.message = str(fo, "message", "");
                    b.failed.add(fail);
                }
            }
            return b;
        }
    }

    /** 批量创建失败项。 */
    public static final class Failure {
        public long accountRoleId;
        public String message = "";
    }

    /** 账号池条目（{@code accounts.accounts[]}）。 */
    public static final class AccountItem {
        public long accountRoleId;
        public String gameName = "";
        public long serverId;
        public boolean online;

        static AccountItem parse(JSONObject o) {
            if (o == null) return null;
            AccountItem a = new AccountItem();
            a.accountRoleId = num(o.opt("accountRoleId"));
            if (a.accountRoleId <= 0) a.accountRoleId = num(o.opt("account_role_id"));
            a.gameName = str(o, "game_name", "");
            if (a.gameName.isEmpty()) a.gameName = str(o, "account", "");
            a.serverId = num(o.opt("server_id"));
            if (a.serverId <= 0) a.serverId = num(o.opt("kid"));
            a.online = bool(o, "online");
            if (a.accountRoleId <= 0) return null;
            return a;
        }

        /** 下拉展示文案：{@code 名称 · K服（未上线）}（网页版 {@code P.map} 逐字）。 */
        public String label() {
            String name = gameName == null || gameName.isEmpty()
                    ? "角色 " + accountRoleId : gameName;
            String k = serverId > 0 ? String.valueOf(serverId) : "-";
            String off = online ? "" : "（未上线）";
            return name + " · K" + k + off;
        }
    }

    // ==================== 解析入口 ====================

    public static Overview overviewOf(JSONObject data) {
        return Overview.parse(data);
    }

    public static Precheck precheckOf(JSONObject data) {
        if (data == null) return null;
        Precheck p = Precheck.parse(data);
        return p;
    }

    public static Jobs jobsOf(JSONObject data) {
        return Jobs.parse(data);
    }

    public static MarchInfo marchesOf(JSONObject data) {
        return MarchInfo.parse(data);
    }

    public static RecallResult recallOf(JSONObject data) {
        return RecallResult.parse(data);
    }

    public static Attempts attemptsOf(JSONObject data) {
        return Attempts.parse(data);
    }

    public static BulkResult bulkOf(JSONObject data) {
        return BulkResult.parse(data);
    }

    public static List<AccountItem> accountsOf(JSONObject data) {
        List<AccountItem> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("accounts");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            AccountItem a = AccountItem.parse(arr.optJSONObject(i));
            if (a != null) out.add(a);
        }
        return out;
    }

    // ==================== 格式化（网页版 Fe / ke / $e / Ml）====================

    /** 千分位整数（网页版 {@code ke}，{@code Intl.NumberFormat("zh-CN")}）。 */
    public static String fmtInt(long v) {
        boolean neg = v < 0;
        String digits = String.valueOf(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) sb.append(',');
            sb.append(digits.charAt(i));
        }
        return (neg ? "-" : "") + sb;
    }

    /** 时间文案：{@code yyyy-MM-dd HH:mm:ss}（Asia/Shanghai）；非正数 → {@code -}。 */
    public static String fmtTime(long ms) {
        if (ms <= 0) return "-";
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            f.setTimeZone(DISPLAY_TZ);
            return f.format(new Date(ms));
        } catch (Throwable t) {
            return "-";
        }
    }

    /** 时间文案变体（审计弹窗 {@code h}：无记录显示「未记录」）。 */
    public static String fmtTimeOrMissing(long ms) {
        return ms > 0 ? fmtTime(ms) : "未记录";
    }

    /** 时刻文案：{@code HH:mm}（网页版 {@code Ml}，用于「已安排 HH:mm 前回城」）。 */
    public static String fmtClock(long ms) {
        if (ms <= 0) return "-";
        try {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.CHINA);
            f.setTimeZone(DISPLAY_TZ);
            return f.format(new Date(ms));
        } catch (Throwable t) {
            return "-";
        }
    }

    /**
     * 相对时长（网页版 {@code Fe}，入参毫秒）：
     * {@code ≥1天 → "X天 X时 X分"}；{@code ≥1时 → "X时 X分 X秒"}；否则 {@code "X分 X秒"}。
     */
    public static String fmtDuration(long ms) {
        long sec = Math.max(0L, (long) Math.ceil(ms / 1000.0));
        long d = sec / 86400L;
        long h = (sec % 86400L) / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (d > 0) return d + "天 " + h + "时 " + m + "分";
        if (h > 0) return h + "时 " + m + "分 " + s + "秒";
        return m + "分 " + s + "秒";
    }

    /** 短时长（网页版 {@code Fl}，入参秒）：{@code "X时 Y分"} / {@code "X分 Y秒"} / {@code "Y秒"}。 */
    public static String fmtRemain(long seconds) {
        long sec = Math.max(0L, seconds);
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (h > 0) return h + "时 " + m + "分";
        if (m > 0) return m + "分 " + s + "秒";
        return s + "秒";
    }

    /** RTT（网页版 execution-audit {@code f}）：{@code ≥1000ms → "x.xx s"}，否则 {@code "x ms"}。 */
    public static String fmtRtt(long ms) {
        if (ms < 0) return "未记录";
        if (ms >= 1000L) return String.format(Locale.CHINA, "%.2f s", ms / 1000.0);
        return ms + " ms";
    }

    /**
     * 主机时钟偏差（网页版 execution-audit {@code c}）：
     * 未对时 → 「未同步」；0 → 「0 ms」；正 → 「+x ms（本机偏慢）」；负 → 「x ms（本机偏快）」。
     */
    public static String fmtClockOffset(long offsetMs, boolean synced) {
        if (!synced) return "未同步";
        if (offsetMs == 0L) return "0 ms";
        if (offsetMs > 0) return "+" + offsetMs + " ms（本机偏慢）";
        return offsetMs + " ms（本机偏快）";
    }

    /** 服务器时间说明文案（网页版 {@code gl}）。 */
    public static String serverClockHint(Activity activity, long serverNow) {
        if (activity == null || !activity.hasSpan() || serverNow <= 0) return "等待服务器时间";
        long freedom = activity.freedomStartMillis;
        if (serverNow < freedom) return "距自由移民 " + fmtDuration(freedom - serverNow);
        if (serverNow < activity.endMillis) {
            return "自由移民剩余 " + fmtDuration(activity.endMillis - serverNow);
        }
        return "本轮活动已结束";
    }

    // ==================== 阶段（网页版 Re / ml / vl）====================

    /**
     * 由服务器时间推导阶段。网页版口径：
     * {@code now < start || now >= end → CLOSED}；{@code now < start+3天 → PROSPECTS}；
     * {@code now < freedomStart → INVITATION}；否则 {@code FREEDOM}。
     */
    public static String stageOf(Activity activity, long serverNow) {
        if (activity == null || !activity.hasSpan() || serverNow <= 0) return STAGE_CLOSED;
        long prospectEnd = activity.startMillis + PROSPECT_DAYS * 86400L * 1000L;
        if (serverNow < activity.startMillis || serverNow >= activity.endMillis) return STAGE_CLOSED;
        if (serverNow < prospectEnd) return STAGE_PROSPECTS;
        if (serverNow < activity.freedomStartMillis) return STAGE_INVITATION;
        return STAGE_FREEDOM;
    }

    /** 阶段标签（网页版 {@code ml}）。 */
    public static String stageLabel(String stage) {
        if (STAGE_PROSPECTS.equals(stage)) return "展望阶段";
        if (STAGE_INVITATION.equals(stage)) return "邀请阶段";
        if (STAGE_FREEDOM.equals(stage)) return "自由移民开放";
        return "活动未开放";
    }

    /** 阶段配色（网页版 {@code vl}：success / info / warning）。 */
    public static String stageTone(String stage) {
        if (STAGE_FREEDOM.equals(stage)) return "ok";
        if (STAGE_CLOSED.equals(stage)) return "idle";
        return "warn";
    }

    /** 时间轴进度百分比 0-100（网页版 {@code q}）。 */
    public static int timelinePercent(Activity activity, long serverNow) {
        if (activity == null || !activity.hasSpan() || serverNow <= 0) return 0;
        double ratio = (double) (serverNow - activity.startMillis)
                / (double) (activity.endMillis - activity.startMillis);
        double pct = Math.min(100.0, Math.max(0.0, ratio * 100.0));
        return (int) Math.round(pct);
    }

    // ==================== 王国 / 任务文案（Ke / ll / Ol / ke / el）====================

    /** 王国展示文案（网页版 {@code Ke}）：优先 kingdoms.label + 「[强盛]」，否则 {@code #kid 王国}。 */
    public static String kingdomLabel(Overview ov, long kid) {
        if (kid <= 0) return "-";
        Kingdom k = ov == null ? null : ov.kingdom(kid);
        if (k != null && k.label != null && !k.label.isEmpty()) {
            return k.label + (k.strong() ? " [强盛]" : "");
        }
        return "#" + kid + " 王国";
    }

    /** 下拉选项文案（网页版 {@code el}）：{@code label[ [强盛]] · ≤战力上限}。 */
    public static String kingdomOptionLabel(Kingdom k) {
        if (k == null) return "-";
        String base = (k.label == null ? "" : k.label) + (k.strong() ? " [强盛]" : "");
        if (k.powerLimitValue > 0) return base + " · ≤" + fmtInt(k.powerLimitValue);
        return base;
    }

    /** 任务路由（网页版 {@code ll}）：{@code #原王国 → 目标名 或 #目标王国 王国}。 */
    public static String jobRoute(Job job) {
        if (job == null) return "-";
        String target = job.targetName != null && !job.targetName.isEmpty()
                ? job.targetName : "#" + job.targetKid + " 王国";
        return "#" + job.originalKid + " → " + target;
    }

    /**
     * 抢位倒计时文案（网页版 {@code Ol}）：
     * 服务器时间未知 → 状态标签；早于开放 → 「距开放 X」；否则状态标签。
     */
    public static String jobCountdown(Job job, long serverNow) {
        if (job == null) return "-";
        String label = job.statusLabel == null || job.statusLabel.isEmpty() ? "-" : job.statusLabel;
        if (serverNow <= 0) return label;
        if (serverNow < job.freedomStartMillis) {
            return "距开放 " + fmtDuration(job.freedomStartMillis - serverNow);
        }
        return label;
    }

    /** 时钟已同步（网页版 {@code Oe}：{@code clock_source === "role_clock"}）。 */
    public static boolean isRoleClock(Job job) {
        return job != null && "role_clock".equals(job.clockSource);
    }

    /** 有在外部队（网页版 {@code _e}：{@code march_count > 0}）。 */
    public static boolean hasMarches(Job job) {
        return job != null && job.marchCount > 0;
    }

    /** 召回准备窗口已开始（网页版 {@code Ae}）。 */
    public static boolean recallWindowOpen(Job job, long serverNow) {
        if (job == null) return false;
        if (!"WAITING".equals(job.status)) return true;
        long start = job.preparationStartMillis;
        if (start <= 0 || serverNow <= 0) return false;
        return serverNow >= start;
    }

    /** 部队待召回（网页版 {@code xe}）。 */
    public static boolean recallPending(Job job, long serverNow) {
        if (!hasMarches(job)) return false;
        if (!job.recallBeforeOpen) return true;
        if (recallWindowOpen(job, serverNow)) return !job.recallReady;
        return false;
    }

    /** 行军行配色（网页版 {@code Al}）。 */
    public static String marchLineTone(Job job, long serverNow) {
        if (recallPending(job, serverNow)) return "warn";
        if (hasMarches(job) && !job.recallReady) return "wait";
        return "";
    }

    /** 时钟文案（网页版 {@code zl}）。 */
    public static String clockText(Job job) {
        if (job == null) return "未采集";
        String src = job.clockSource;
        if (src == null || src.isEmpty()) return "未采集";
        String base;
        if ("role_clock".equals(src)) base = "已同步";
        else if ("role_clock_unsynced".equals(src)) base = "校准中";
        else base = "本机墙钟";
        long rtt = job.heartbeatRttMillis;
        return rtt > 0 ? base + " · " + rtt + "ms" : base;
    }

    /** 行军文案（网页版 {@code hl}）。 */
    public static String marchText(Job job, long serverNow) {
        if (!hasMarches(job)) return "角色时钟";
        int n = job.marchCount;
        if (!job.recallBeforeOpen) return "在外 " + n + " 支 · 未设召回";
        if (job.recallReady) {
            long end = job.latestMarchEndMillis;
            return end > 0
                    ? "在外 " + n + " 支 · 已安排 " + fmtClock(end) + " 前回城"
                    : "在外 " + n + " 支 · 已安排回城";
        }
        return recallWindowOpen(job, serverNow)
                ? "在外 " + n + " 支 · 召回进行中"
                : "在外 " + n + " 支 · 开放前自动召回";
    }

    /** 召回标签配色（网页版 {@code Il}）：danger / success / warning / info。 */
    public static String recallTagTone(Job job, long serverNow) {
        if (job == null) return "warn";
        if (!job.recallBeforeOpen) return "err";
        if (recallWindowOpen(job, serverNow)) return job.recallReady ? "ok" : "warn";
        return "wait";
    }

    /** 召回标签文案（网页版 {@code Dl}）。 */
    public static String recallTagText(Job job, long serverNow) {
        if (job == null) return "";
        if (!job.recallBeforeOpen) return "在外未设召回";
        if (recallWindowOpen(job, serverNow)) return job.recallReady ? "已安排回城" : "召回中";
        return "待自动召回";
    }

    /** 退盟徽标（网页版 {@code Te}：{@code show/type/text}）。 */
    public static final class ExitBadge {
        public final boolean show;
        public final String tone;
        public final String text;

        ExitBadge(boolean show, String tone, String text) {
            this.show = show;
            this.tone = tone;
            this.text = text;
        }

        static final ExitBadge NONE = new ExitBadge(false, "idle", "");
    }

    /** 退盟徽标（网页版 {@code Te}；顺序不可调换）。 */
    public static ExitBadge exitAllianceBadge(Job job) {
        if (job == null || !job.autoExitAlliance) return ExitBadge.NONE;
        String msg = job.exitAllianceMessage == null ? "" : job.exitAllianceMessage;
        if (msg.contains("失败")) return new ExitBadge(true, "err", "退盟失败");
        if (msg.contains("已自动退出")) return new ExitBadge(true, "ok", "已退盟");
        if (msg.contains("未加入联盟")) return new ExitBadge(true, "idle", "未在联盟");
        if (job.precheck != null) {
            for (Check c : job.precheck.checks) {
                if ("alliance".equals(c.id)) {
                    String detail = c.detail == null ? "" : c.detail;
                    if (detail.contains("未加入联盟")) return new ExitBadge(true, "idle", "未在联盟");
                    break;
                }
            }
        }
        return new ExitBadge(true, "warn", "将自动退盟");
    }

    /** 全部就绪（网页版 {@code Ul}）：时钟同步 + 无待召回 + 退盟无失败。 */
    public static boolean allReady(Job job, long serverNow) {
        if (job == null) return false;
        return isRoleClock(job)
                && (!hasMarches(job) || job.recallReady)
                && !"err".equals(exitAllianceBadge(job).tone);
    }

    /** 最终结果文案（网页版 {@code Bl}）。 */
    public static String finalResultText(Job job) {
        if (job == null) return "-";
        if (job.reconciledKid > 0) return "重登王国 K" + job.reconciledKid;
        if (job.responseCode != null) return "响应码 " + job.responseCode;
        return "无补充结果";
    }

    /** 状态配色（网页版 {@code tl}）：SUCCEEDED=success，REJECTED/UNKNOWN=danger，active=warning。 */
    public static String statusTone(Job job) {
        if (job == null) return "idle";
        if ("SUCCEEDED".equals(job.status)) return "ok";
        if ("REJECTED".equals(job.status) || "UNKNOWN".equals(job.status)) return "err";
        if (job.active) return "warn";
        return "idle";
    }

    /** 异常任务（网页版 {@code Ve}）：拒收/未知/时钟未同步/待召回/退盟失败。 */
    public static boolean hasIssue(Job job, long serverNow) {
        if (job == null) return false;
        return "REJECTED".equals(job.status)
                || "UNKNOWN".equals(job.status)
                || !isRoleClock(job)
                || recallPending(job, serverNow)
                || "err".equals(exitAllianceBadge(job).tone);
    }

    /** 可重新核验（网页版 {@code El}）：已终态（SUCCEEDED/REJECTED/UNKNOWN）。 */
    public static boolean canRecheck(Job job) {
        if (job == null || job.jobId == null || job.jobId.isEmpty()) return false;
        String s = job.status == null ? "" : job.status.toUpperCase(Locale.US);
        return "SUCCEEDED".equals(s) || "REJECTED".equals(s) || "UNKNOWN".equals(s);
    }

    /**
     * 就绪 chip 文案（网页版 {@code _l}）：
     * 前段「就绪 F/N」，中段「待处理：时钟待同步 X · 部队待召回 Y」或「开放前自动召回 X 支」
     * 或「全部可抢位」。F = 非异常任务数。
     */
    public static String readinessText(List<Job> jobs, long serverNow) {
        if (jobs == null || jobs.isEmpty()) return "暂无任务";
        int clockOff = 0;
        int recallPend = 0;
        int autoRecall = 0;
        int issue = 0;
        for (Job j : jobs) {
            if (!isRoleClock(j)) clockOff++;
            if (recallPending(j, serverNow)) recallPend++;
            if (hasMarches(j) && !recallPending(j, serverNow)) autoRecall++;
            if (hasIssue(j, serverNow)) issue++;
        }
        int ready = jobs.size() - issue;
        StringBuilder pending = new StringBuilder();
        if (clockOff > 0) pending.append("时钟待同步 ").append(clockOff);
        if (recallPend > 0) {
            if (pending.length() > 0) pending.append(" · ");
            pending.append("部队待召回 ").append(recallPend);
        }
        if (pending.length() > 0) {
            return "就绪 " + ready + "/" + jobs.size() + " · 待处理：" + pending;
        }
        if (autoRecall > 0) {
            return "就绪 " + ready + "/" + jobs.size() + " · 开放前自动召回 " + autoRecall + " 支";
        }
        return "就绪 " + ready + "/" + jobs.size() + " · 全部可抢位";
    }

    /** 就绪 chip 状态类（网页版 {@code yl}）：has-issues / is-ready / is-empty。 */
    public static String readinessTone(List<Job> jobs, long serverNow) {
        if (jobs == null || jobs.isEmpty()) return "";
        for (Job j : jobs) {
            if (hasIssue(j, serverNow)) return "warn";
        }
        return "ok";
    }

    /** 创建前准备摘要（网页版 {@code Rl}，逐字照抄）。 */
    public static String prepareSummary(boolean autoExitAlliance, boolean recallBeforeOpen,
                                        boolean recallSpeedup, boolean recallDiamondSpeedup) {
        StringBuilder sb = new StringBuilder();
        if (autoExitAlliance) sb.append("退出联盟");
        if (recallBeforeOpen) {
            if (sb.length() > 0) sb.append("、");
            sb.append(recallSpeedup ? "召回在外部队并加速回城" : "召回在外部队");
            if (recallDiamondSpeedup) {
                sb.append("、背包不足时钻石购买（召回道具 800 钻／加速器 400 钻）");
            }
        }
        if (sb.length() > 0) {
            return "任务会在开放前 15 分钟上号自动执行：" + sb + "，全部准备在开放前 2 分钟完成。";
        }
        return "未开启自动准备，开放前需要账号自行满足全部条件。";
    }

    /** 时钟来源文案（审计弹窗 {@code G}）。 */
    public static String clockSourceLabel(String source) {
        String s = source == null ? "" : source.trim();
        if ("role_clock".equals(s)) return "角色游戏服时钟";
        if ("role_clock_unsynced".equals(s)) return "角色时钟未同步";
        if ("host_wall_clock".equals(s)) return "本机墙钟回退";
        return s.isEmpty() ? "未记录" : s;
    }

    /**
     * 请求阶段文案：优先服务端 {@code phase_label}，缺失时用内置中文映射。
     *
     * <p>阶段取值（网页版 {@code ce} 的三分支）：{@code BURST} 抢位爆发、
     * {@code FOLLOW_UP} 跟进重试、{@code FALLBACK} 兜底重试；未知值原样透出，
     * 空值回 {@code —}。内置映射只补「服务端还没下发 label」这一档，不覆盖服务端文案。
     */
    public static String phaseLabelOf(String phase, String phaseLabel) {
        if (phaseLabel != null && !phaseLabel.trim().isEmpty()) return phaseLabel.trim();
        String p = phase == null ? "" : phase.trim().toUpperCase(Locale.US);
        if ("BURST".equals(p)) return "抢位爆发";
        if ("FOLLOW_UP".equals(p)) return "跟进重试";
        if ("FALLBACK".equals(p)) return "兜底重试";
        return p.isEmpty() ? "—" : phase.trim();
    }

    /** 请求阶段配色（网页版 {@code ce}）。 */
    public static String phaseTone(String phase) {
        if ("BURST".equals(phase)) return "warn";
        if ("FOLLOW_UP".equals(phase)) return "err";
        if ("FALLBACK".equals(phase)) return "ok";
        return "idle";
    }

    /** 请求结果配色（网页版 {@code ue}）。 */
    public static String outcomeTone(String outcome) {
        if ("SUCCESS".equals(outcome) || "ACCEPTED".equals(outcome)) return "ok";
        if ("IN_PROGRESS".equals(outcome)) return "warn";
        if ("REJECTED".equals(outcome) || "TRANSPORT_ERROR".equals(outcome)) return "err";
        return "idle";
    }

    /** 审计状态配色（网页版 {@code le}）。 */
    public static String auditStatusTone(String status) {
        String s = status == null ? "" : status.toUpperCase(Locale.US);
        if ("SUCCEEDED".equals(s)) return "ok";
        if ("REJECTED".equals(s)) return "err";
        if ("UNKNOWN".equals(s)) return "warn";
        if ("CANCELLED".equals(s)) return "idle";
        return "warn";
    }

    /** 相对开放边界的偏差文案（审计 {@code P}）：{@code 开放后/前 N ms} / 与开放边界一致。 */
    public static String boundaryDelta(long atMillis, long freedomStartMillis) {
        if (atMillis <= 0 || freedomStartMillis <= 0) return "无法比较";
        long delta = atMillis - freedomStartMillis;
        if (delta == 0L) return "与开放边界一致";
        return "开放" + (delta > 0 ? "后 " : "前 ") + Math.abs(delta) + " ms";
    }

    /** 距首次请求的时间差文案（审计 {@code j}）。 */
    public static String sinceFirst(long atMillis, long firstSentMillis) {
        if (atMillis <= 0 || firstSentMillis <= 0) return "无法比较";
        return "距首次请求 +" + Math.max(0L, atMillis - firstSentMillis) + " ms";
    }

    /** JSON 美化（审计 {@code B}）：解析失败原样返回；空 → {@code —}。 */
    public static String prettyJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "—";
        try {
            String t = raw.trim();
            if (t.startsWith("{")) return new JSONObject(t).toString(2);
            if (t.startsWith("[")) return new JSONArray(t).toString(2);
            return raw;
        } catch (Throwable e) {
            return raw;
        }
    }

    /** 行军回城文案（撤退弹窗 {@code o}）：{@code 时刻 · 剩余 X}。 */
    public static String marchEta(March m) {
        if (m == null) return "时间未知";
        if (m.endMillis <= 0) return "时间未知";
        String at = fmtTime(m.endMillis);
        if (m.remainingMillis > 0) {
            long sec = (long) Math.ceil(m.remainingMillis / 1000.0);
            return at + " · 剩余 " + fmtRemain(sec);
        }
        return at;
    }

    /** 坐标文案（撤退弹窗 {@code a}）：{@code (x, y)} 或 {@code -}。 */
    public static String marchCoord(March m) {
        if (m == null) return "-";
        if (Double.isNaN(m.x) || Double.isNaN(m.y)) return "-";
        return "(" + num(m.x) + ", " + num(m.y) + ")";
    }

    /** 召回方式文案（撤退弹窗 {@code M}，顺序不可调换）。 */
    public static String marchRecallMode(March m) {
        if (m == null) return "-";
        if (m.rally) return "集结行军不支持召回";
        if (!m.recallable) {
            return m.recallDisabledReason == null || m.recallDisabledReason.isEmpty()
                    ? "当前不可召回" : m.recallDisabledReason;
        }
        if (!m.requiresRecallItem) return "免费撤回（停工作）";
        String item = m.recallItemName == null || m.recallItemName.isEmpty()
                ? "召回道具" : m.recallItemName;
        if (!m.recallItemSynced) return "需要「" + item + "」，道具库存同步中";
        long count = m.recallItemCount;
        return count > 0
                ? "需要「" + item + "」道具 1 个（背包 " + count + "）"
                : "需要「" + item + "」道具 1 个（背包 0，可钻石购买）";
    }

    /** 行军行提示（撤退弹窗 {@code Q}）。 */
    public static String marchHint(March m, MarchInfo info) {
        if (m == null) return "";
        if (m.recalled) return "已发送召回指令，等待服务端回城";
        if (m.rally) return "集结行军不支持撤回或召回";
        if (info == null) return "-";
        if (!info.online) return "账号未上线";
        if (!info.manualRecallAllowed) {
            return info.manualRecallBlockReason == null || info.manualRecallBlockReason.isEmpty()
                    ? "当前阶段不能手动召回" : info.manualRecallBlockReason;
        }
        if (!m.recallable) {
            return m.recallDisabledReason == null || m.recallDisabledReason.isEmpty()
                    ? "当前不可召回" : m.recallDisabledReason;
        }
        if (m.stateCode == 4) return "部队已经在返回途中";
        return "可立即" + (m.recallAction == null || m.recallAction.isEmpty()
                ? "召回" : m.recallAction);
    }

    /** 行军状态配色（撤退弹窗 {@code fe}）：state_code 4/3 → success/warning。 */
    public static String marchStateTone(March m) {
        if (m == null) return "idle";
        if (m.stateCode == 4) return "ok";
        if (m.stateCode == 3) return "warn";
        return "idle";
    }

    /** 单支召回确认文案（撤退弹窗 {@code ue}）。 */
    public static String recallConfirmText(March m, int pendingCount) {
        if (m == null) {
            return "对当前 " + pendingCount + " 支在外部队逐支发送召回指令？"
                    + "行军中的部队会消耗 1 个召回道具，采集等工作状态的部队免费撤回。";
        }
        String item = m.recallItemName == null || m.recallItemName.isEmpty()
                ? "召回" : m.recallItemName;
        String cost = m.requiresRecallItem
                ? "将消耗 1 个「" + item + "」道具。"
                : "部队会按游戏规则返回。";
        String action = m.recallAction == null || m.recallAction.isEmpty() ? "召回" : m.recallAction;
        return action + "「" + m.type + "」部队 #" + m.marchId + "？" + cost;
    }

    /** 撤退弹窗顶部提示（撤退弹窗 {@code oe}）。 */
    public static String recallNote(MarchInfo info) {
        if (info == null) return "正在读取任务与账号状态";
        if (!info.online) return "账号未上线：上线后可读取每支部队的回城时间并手动召回";
        if (!info.recallBeforeOpen) {
            return "该任务未开启「开放前自动收回外出部队」，请在开放前自行保证部队全部回城";
        }
        if (!info.manualRecallAllowed) {
            return info.manualRecallBlockReason == null || info.manualRecallBlockReason.isEmpty()
                    ? "当前阶段不能手动召回" : info.manualRecallBlockReason;
        }
        return "任务会在开放前 15 分钟自动召回在外部队；这里可以随时手动召回作为兜底";
    }

    /** 审计弹窗对时统计文案（{@code te}）。 */
    public static String syncSummary(Attempts a) {
        if (a == null || a.attempts.isEmpty()) {
            return a != null && epochReady(a.clockEpochMillis, a.clockSource)
                    ? "边界已对时 · 尚无请求" : "尚无请求记录";
        }
        int synced = a.syncedCount();
        int total = a.attempts.size();
        if (synced == total) return "全部 " + total + " 次请求已对时";
        if (synced == 0) return "全部 " + total + " 次请求未对时";
        return synced + "/" + total + " 次请求已对时";
    }

    /** 审计弹窗对时统计配色（{@code ie}）。 */
    public static String syncSummaryTone(Attempts a) {
        if (a == null || a.attempts.isEmpty()) {
            return a != null && epochReady(a.clockEpochMillis, a.clockSource) ? "ok" : "warn";
        }
        int synced = a.syncedCount();
        if (synced == a.attempts.size()) return "ok";
        if (synced == 0) return "err";
        return "warn";
    }

    /** 请求级对时覆盖文案（{@code oe}）。 */
    public static String syncCoverage(Attempts a) {
        if (a == null || a.attempts.isEmpty()) return "尚无请求";
        int synced = a.syncedCount();
        return synced + " 次已同步 / " + (a.attempts.size() - synced) + " 次未同步";
    }

    /** 边界是否已对时（{@code L}）。 */
    public static boolean epochReady(long epochMillis, String clockSource) {
        return "role_clock".equals(clockSource) && epochMillis > 0;
    }

    /** 单次请求对时状态文案（{@code ne}）。 */
    public static String attemptSyncLabel(Attempt at) {
        if (at == null) return "未记录";
        return at.serverClockReady && at.serverNowMillis > 0 ? "已同步游戏服" : "未同步";
    }

    /** 单次请求游戏服时刻文案（{@code ae}）。 */
    public static String attemptServerNow(Attempt at) {
        if (at == null) return "未记录";
        return at.serverClockReady && at.serverNowMillis > 0
                ? fmtTime(at.serverNowMillis) : "未同步";
    }

    /** 数字或「未记录」（审计 {@code de}）。 */
    public static String fmtCount(long v) {
        return v >= 0 ? String.valueOf(v) : "未记录";
    }

    /** 提前量钳位（服务端同样钳位 [0,100]）。 */
    public static int clampAdvance(int v) {
        return Math.max(ADVANCE_MIN, Math.min(ADVANCE_MAX, v));
    }
}
