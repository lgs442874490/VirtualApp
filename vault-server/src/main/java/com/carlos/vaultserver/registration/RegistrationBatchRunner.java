package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.registration.RegistrationService.PhoneBatchConfig;
import com.carlos.vaultserver.registration.RegistrationService.PhoneResult;
import com.carlos.vaultserver.registration.RegistrationService.StepLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 注册批次状态机（任务书 M3 §2.1）：单线程串行逐号执行，直到目标数达成 / 号码用尽 / 被取消。
 *
 * <p>状态迁移：{@code WAITING → RUNNING → COMPLETED | FAILED | CANCELLED}。
 * {@link #run()} 由调用方放到独立单线程 executor（HTTP 线程绝不参与批次执行）；
 * {@link #cancel()} 只是标志位——当前号自然结束后不再开下一号（置 CANCELLED）。
 *
 * <p>凭据纪律：全程凭据只存 {@link CredentialBox}（批次期加密）；批次终态 {@code box.clear()}；
 * 持久化视图（{@link #view()}）只含元数据 + 每号 phone/state/steps/error/masked 凭据 + encrypted
 * 产物，明文 sdkToken / 身份证 / 密码 / 验证码绝不出现。每号结束即 {@code store.update} 落盘，
 * 重启后未完成批次由 RegistrationStore 标记 FAILED(服务重启中断)。
 */
public final class RegistrationBatchRunner {

    /** 批次状态。 */
    public enum BatchState { WAITING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /** 单号状态。 */
    public enum PhoneState { PENDING, RUNNING, SUCCESS, FAILED }

    /** 终态集合（批次不可再推进）。 */
    public static boolean isTerminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    /** 批次 id 序号（进程内递增，同秒多批次不重号）。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /** 批次 id：b + yyyyMMddHHmmss + 两位序号。 */
    public final String id;
    private final PhoneBatchConfig config;
    private final RegistrationService service;
    private final RegistrationStore store;
    private final CredentialBox box;
    private final RegistrationService.Sleeper sleeper;

    /** 每号条目（maxNumbers 个槽位，启动时 PENDING 占位，取号后回填 phone）。 */
    private final List<PhoneEntry> entries;
    private volatile BatchState state = BatchState.WAITING;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile int successCount;
    private volatile int failCount;
    private volatile int attemptCount;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 单号条目：槽位状态 + 结果（result 含明文凭据，只在内存；视图走 {@code view()}）。 */
    public static final class PhoneEntry {
        public volatile String phone = "";
        public volatile PhoneState state = PhoneState.PENDING;
        public volatile PhoneResult result;
        public volatile String error = "";

        JSONObject toView() {
            JSONObject o;
            PhoneResult r = result;
            if (r != null) {
                o = r.toPersistentView(state.name());
                // view 里 phone 以 result 为准；槽位未取到号时保持空
                if (o.optString("phone", "").isEmpty()) o.put("phone", phone);
            } else {
                o = new JSONObject();
                o.put("phone", phone);
                o.put("state", state.name());
                o.put("error", error);
                o.put("fpidMasked", "");
                o.put("encrypted", JSONObject.NULL);
                o.put("steps", new JSONArray());
            }
            return o;
        }
    }

    /**
     * @param config  批次配置（provider/keyword/goalCount/maxNumbers/超时/可选实名/密码不落盘）
     * @param service 编排服务（registerOnePhone）
     * @param store   持久化仓（每号结束与终态时 update）
     * @param box     批次凭据箱（终态 clear）
     * @param sleeper 号间隔睡眠（生产真实睡眠；单测注入跳时）
     */
    public RegistrationBatchRunner(PhoneBatchConfig config, RegistrationService service,
            RegistrationStore store, CredentialBox box, RegistrationService.Sleeper sleeper) {
        this.config = config;
        this.service = service;
        this.store = store;
        this.box = box;
        this.sleeper = sleeper == null ? RegistrationService.REAL_SLEEPER : sleeper;
        this.id = "b" + LocalDateTime.now(Beijing.ZONE_ID).format(TS)
                + String.format(Locale.ROOT, "%02d", (int) (SEQ.incrementAndGet() % 100));
        this.entries = new ArrayList<>();
        for (int i = 0; i < config.maxNumbers; i++) entries.add(new PhoneEntry());
    }

    /** 执行批次（阻塞直到终态；调用方负责放独立线程）。 */
    public void run() {
        if (state != BatchState.WAITING) {
            return; // 已在跑/已终态：幂等防重入（终态重跑会二次落盘）——对齐 BootstrapBatchRunner
        }
        if (cancelled.get()) {
            finish(BatchState.CANCELLED);
            return;
        }
        state = BatchState.RUNNING;
        startedAt = System.currentTimeMillis();
        FileLog.info("注册批次开始 id={} goal={} max={} provider={}",
                id, config.goalCount, config.maxNumbers, config.providerType);
        store.append(view()); // 首次落盘建档；后续每号结束走 update

        for (int i = 0; i < entries.size() && successCount < config.goalCount; i++) {
            if (cancelled.get()) break;
            PhoneEntry entry = entries.get(i);
            entry.state = PhoneState.RUNNING;
            attemptCount++;
            PhoneResult r;
            try {
                r = service.registerOnePhone(config, box);
            } catch (RuntimeException t) {
                // registerOnePhone 内部已兜各分支；此处防御未预期异常，批次不断
                r = new PhoneResult();
                r.phone = entry.phone;
                r.step = "ACQUIRE";
                r.error = "单号执行异常: " + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage());
            }
            entry.result = r;
            entry.phone = r.phone;
            entry.error = r.error;
            if (r.ok) {
                entry.state = PhoneState.SUCCESS;
                successCount++;
            } else {
                entry.state = PhoneState.FAILED;
                failCount++;
            }
            FileLog.info("注册批次进度 id={} i={} state={} success={} fail={}",
                    id, i, entry.state, successCount, failCount);
            store.update(view());
            if (cancelled.get()) break; // 当前号自然结束后停
            boolean more = i + 1 < entries.size() && successCount < config.goalCount;
            if (more) {
                try {
                    sleeper.sleep(config.intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                    break;
                }
            }
        }

        if (cancelled.get()) {
            finish(BatchState.CANCELLED);
        } else if (successCount >= config.goalCount) {
            finish(BatchState.COMPLETED);
        } else if (successCount > 0) {
            finish(BatchState.COMPLETED); // 号码用尽部分成功
        } else {
            finish(BatchState.FAILED);
        }
    }

    /** 请求取消（标志位）：当前号自然结束后停，置 CANCELLED。 */
    public void cancel() {
        cancelled.set(true);
        FileLog.info("注册批次收到取消请求 id={} state={}", id, state);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String state() {
        return state.name();
    }

    public CredentialBox box() {
        return box;
    }

    public PhoneEntry entry(int i) {
        return entries.get(i);
    }

    public int successCount() {
        return successCount;
    }

    public int failCount() {
        return failCount;
    }

    public int attemptCount() {
        return attemptCount;
    }

    /**
     * 可持久化视图：批次元数据 + 统计 + 每号视图。凭据只出 masked / encrypted 产物，
     * 不含明文 sdkToken / 身份证 / 密码 / 验证码。
     */
    public JSONObject view() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("state", state.name());
        o.put("providerType", config.providerType);
        o.put("keyword", config.keyword);
        o.put("goalCount", config.goalCount);
        o.put("maxNumbers", config.maxNumbers);
        o.put("smsWaitSeconds", config.smsWaitSeconds);
        o.put("intervalMillis", config.intervalMillis);
        o.put("realNameEnabled", config.realName != null);
        o.put("hasGamePassword", config.gamePassword != null && !config.gamePassword.isEmpty());
        o.put("createdAt", startedAt > 0 ? startedAt : System.currentTimeMillis());
        o.put("startedAt", startedAt);
        o.put("finishedAt", finishedAt);
        o.put("successCount", successCount);
        o.put("failCount", failCount);
        o.put("attemptCount", attemptCount);
        o.put("cancelRequested", cancelled.get());
        JSONArray phones = new JSONArray();
        for (PhoneEntry e : entries) phones.put(e.toView());
        o.put("phones", phones);
        return o;
    }

    /** 终态收尾：置状态 + finishedAt + 清凭据箱 + 落盘 + 生命周期日志。 */
    private void finish(BatchState terminal) {
        state = terminal;
        finishedAt = System.currentTimeMillis();
        box.clear();
        store.update(view());
        FileLog.info("注册批次结束 id={} state={} success={} fail={} attempt={}",
                id, terminal, successCount, failCount, attemptCount);
    }
}
