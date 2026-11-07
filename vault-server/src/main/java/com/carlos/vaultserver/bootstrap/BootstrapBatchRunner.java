package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.Beijing;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.bootstrap.BootstrapService.BootstrapConfig;
import com.carlos.vaultserver.bootstrap.BootstrapService.Sleeper;
import com.carlos.vaultserver.registration.RegistrationService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 起号批次执行器（M4b 任务书 §2.2）：daemon 单线程串行逐号执行——建号 → 循环
 * {@code runUntil + 5s 间隔}（{@code req_loaddata} 每轮校准状态）→ 目标达成后入盟 →
 * 写回流清单 → 引擎关闭。
 *
 * <p>状态迁移：{@code WAITING → RUNNING → COMPLETED | FAILED | CANCELLED}。
 * {@link #run()} 由调用方放到独立单线程 executor（HTTP 线程绝不参与批次执行）；
 * {@link #cancel()} 只是标志位——当前号自然结束后不再开下一号（对齐注册批次 runner 语义）。
 *
 * <p>单号失败（建号失败/起号停滞/超时上限）只标记该号 FAILED 并继续下一号；
 * 单号超时上限 {@code timeoutMinutes}（默认 120）防死循环。每号结束即
 * {@code store.update} 落盘，重启后未完成批次由 BootstrapBatchStore 标记
 * FAILED(服务重启中断)。
 *
 * <p>凭据纪律：持久化视图（{@link #view()}）只出 masked sdkToken + encrypted 产物，
 * 明文 sdkToken 仅存活在内存 {@link AccountEntry}（批次期）与回流清单文件
 * （{@link BootstrapReferral}，凭据交付本体）。日志一律 mask。
 */
public final class BootstrapBatchRunner {

    /** 批次状态。 */
    public enum BatchState { WAITING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /** 单号状态（任务书 §2.2；TRANSFERRED 为预留位，M4b 不产生）。 */
    public enum AccountState { PENDING, CREATING, RUNNING, SUCCESS, FAILED }

    /** 终态集合（批次不可再推进）。 */
    public static boolean isTerminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    /** 批次 id 序号（进程内递增，同秒多批次不重号）。 */
    private static final AtomicLong SEQ = new AtomicLong();
    /** 每轮 runUntil 步数（对齐原版每轮规划上限 DEFAULT_MAX_ACTIONS）。 */
    static final int STEPS_PER_ROUND = 8;
    /** 轮间隔毫秒（任务书：每轮间隔 5s）。 */
    static final long ROUND_INTERVAL_MILLIS = 5000L;
    /** 单号超时上限默认分钟（任务书：防死循环）。 */
    public static final int DEFAULT_TIMEOUT_MINUTES = 120;
    /** 全量状态拉取协议（每轮 runUntil 前校准，对齐 M4a「等待下一轮 loaddata 校准」）。 */
    private static final String LOAD_DATA = "req_loaddata";

    /** 批次 id：bb + yyyyMMddHHmmss + 两位序号。 */
    public final String id;
    private final BootstrapConfig config;
    /** 单号超时上限毫秒（任务书 cfg.batchTimeoutMinutes，批次级配置不进 BootstrapConfig）。 */
    private final long timeoutMillisField;
    /** 单号超时上限分钟（展示/日志用）。 */
    private final int timeoutMinutes;
    private final List<AccountEntry> entries;
    private final BootstrapBatchStore store;
    private final BootstrapReferral referral;
    private final EngineFactory factory;
    /** 服务内步间等待（runUntil 空闲队列等待；生产真实睡眠，单测跳时）。 */
    private final Sleeper serviceSleeper;
    /** 轮间等待（每轮 5s；单测跳时）。 */
    private final Sleeper roundSleeper;
    /** 在线起号会话表（BootstrapApi 的 ACTIVE 表；null = 不注册，单号挂点不可用）。 */
    private final ConcurrentMap<String, Session> activeSessions;
    private volatile BatchState state = BatchState.WAITING;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile int successCount;
    private volatile int failCount;
    private volatile String summaryMessage = "";
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 起号会话：引擎 + 协议端口组合（{@link EngineFactory} 产物）。
     *
     * <p>生产 {@code port} 包装 {@code engine::request} + 推送通道
     * （{@code notifyData/collectNotifies}）；单测注入脚本端口。{@link BootstrapApi}
     * 的单号挂点（single-*）经 {@code ACTIVE} 表按 fpid 寻址本会话。
     */
    public static final class Session {
        public final WjdrEngine engine;
        public final BootstrapService.ProtocolPort port;

        public Session(WjdrEngine engine, BootstrapService.ProtocolPort port) {
            this.engine = engine;
            this.port = port;
        }

        /** 会话关闭（对齐 SessionKeeper：{@code WjdrEngine.close()} 置离线 + 断连接）。 */
        public void close() {
            try {
                engine.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 引擎/会话工厂（生产：new WjdrEngine + initialize；单测：替身 + 脚本端口）。 */
    public interface EngineFactory {
        Session create(String fpid, String sdkToken, long serverId) throws Exception;
    }

    /** 单号条目：凭据（内存）+ 状态 + 结果摘要。 */
    public static final class AccountEntry {
        public final String fpid;
        public final String sdkToken;
        /** 导入加密串（fpid|token 两段式导入为 null）。 */
        public final String encrypted;
        /** 展示标签（accounts 对象元素可带 label；缺省 fpid）。 */
        public volatile String label;
        public volatile AccountState state = AccountState.PENDING;
        public volatile String error = "";
        /** 关键步骤摘要（建号/轮次/入盟/成功文案；COW：批次线程 add 与 toView 迭代并发安全）。 */
        public final List<String> steps = new CopyOnWriteArrayList<>();
        public volatile long uid;
        public volatile long furnaceLevel;

        public AccountEntry(String fpid, String sdkToken, String encrypted, String label) {
            this.fpid = fpid;
            this.sdkToken = sdkToken;
            this.encrypted = encrypted;
            this.label = label == null || label.isBlank() ? fpid : label.trim();
        }

        /** 可持久化视图：凭据只出 masked + encrypted 产物，明文 sdkToken 绝不出现。 */
        JSONObject toView() {
            JSONObject o = new JSONObject();
            o.put("label", label);
            o.put("fpid", fpid);
            o.put("sdkTokenMasked", BootstrapReferral.mask(sdkToken));
            o.put("encrypted", encrypted == null ? JSONObject.NULL : encrypted);
            o.put("state", state.name());
            o.put("error", error);
            o.put("steps", String.join("；", steps));
            o.put("uid", uid);
            o.put("furnaceLevel", furnaceLevel);
            return o;
        }
    }

    /** 导入解析结果：成功清单 + 错误行（带行号）。 */
    public static final class ImportResult {
        public final List<AccountEntry> accounts = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public List<AccountEntry> accounts() {
            return List.copyOf(accounts);
        }

        public List<String> errors() {
            return List.copyOf(errors);
        }
    }

    /**
     * 行清单导入（任务书 §2.2 importAccounts）：逐行解析——加密串
     * （{@link RegistrationService#parseEncrypted}）或 {@code fpid|sdkToken} 两段式；
     * 按 fpid 去重（重复行跳过并记入 errors）；非法行报行号错误。
     */
    public static ImportResult importAccounts(List<String> lines) {
        ImportResult result = new ImportResult();
        if (lines == null || lines.isEmpty()) {
            result.errors.add("账号清单为空");
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            int lineNo = i + 1;
            if (line.isEmpty()) {
                result.errors.add("第 " + lineNo + " 行：空行已跳过");
                continue;
            }
            String fpid;
            String sdkToken;
            String encrypted = null;
            if (line.indexOf('|') > 0) {
                // 两段式 fpid|sdkToken（三段以上按第一段 fpid、其余拼接回 token）
                int sep = line.indexOf('|');
                fpid = line.substring(0, sep).trim();
                sdkToken = line.substring(sep + 1).trim();
            } else {
                try {
                    String[] parts = RegistrationService.parseEncrypted(line);
                    fpid = parts[0];
                    sdkToken = parts[1];
                    encrypted = line;
                } catch (RuntimeException e) {
                    result.errors.add("第 " + lineNo + " 行："
                            + (e.getMessage() == null ? "格式不正确" : e.getMessage()));
                    continue;
                }
            }
            if (fpid.isEmpty() || sdkToken.isEmpty()) {
                result.errors.add("第 " + lineNo + " 行：fpid 与 sdkToken 不能为空");
                continue;
            }
            if (!seen.add(fpid)) {
                result.errors.add("第 " + lineNo + " 行：fpid " + fpid + " 重复已跳过");
                continue;
            }
            result.accounts.add(new AccountEntry(fpid, sdkToken, encrypted, ""));
        }
        return result;
    }

    /**
     * @param config         批次配置（enabled 强制 true；serverId/target/alliance/roleName/instantDiamond 透传）
     * @param accounts       导入成功的账号清单（1–100，调用方校验）
     * @param store          批次持久化仓（每号结束与终态 update）
     * @param referral       回流清单（SUCCESS 写入）
     * @param factory        会话工厂（引擎替身注入点）
     * @param sleeper        等待注入（服务内步间 + 轮间共用；生产真实睡眠）
     * @param timeoutMinutes 单号超时上限（分钟，&le;0 取默认 120）
     * @param activeSessions 在线会话表（BootstrapApi 单号挂点寻址；可为 null）
     */
    public BootstrapBatchRunner(BootstrapConfig config, List<AccountEntry> accounts,
                                BootstrapBatchStore store, BootstrapReferral referral,
                                EngineFactory factory, Sleeper sleeper, int timeoutMinutes,
                                ConcurrentMap<String, Session> activeSessions) {
        this(config, accounts, store, referral, factory, sleeper,
                Math.max(1, timeoutMinutes) * 60_000L, activeSessions);
    }

    /**
     * 包级测试构造：直接指定超时上限毫秒（0 = 立即超时，用于触发超时分支不等待真实分钟）。
     */
    BootstrapBatchRunner(BootstrapConfig config, List<AccountEntry> accounts,
                         BootstrapBatchStore store, BootstrapReferral referral,
                         EngineFactory factory, Sleeper sleeper, long timeoutMillis,
                         ConcurrentMap<String, Session> activeSessions) {
        this.config = config;
        this.timeoutMillisField = timeoutMillis;
        this.timeoutMinutes = (int) Math.max(1, timeoutMillis / 60_000L);
        this.entries = new ArrayList<>(accounts);
        this.store = store;
        this.referral = referral;
        this.factory = factory;
        Sleeper s = sleeper == null ? BootstrapService.REAL_SLEEPER : sleeper;
        this.serviceSleeper = s;
        this.roundSleeper = s;
        this.activeSessions = activeSessions;
        this.id = "bb" + LocalDateTime.now(Beijing.ZONE_ID).format(TS)
                + String.format(Locale.ROOT, "%02d", (int) (SEQ.incrementAndGet() % 100));
    }

    /** 执行批次（阻塞直到终态；调用方负责放独立线程）。 */
    public void run() {
        if (state != BatchState.WAITING) {
            return; // 已在跑/已终态：幂等防重入
        }
        if (cancelled.get()) {
            finish(BatchState.CANCELLED);
            return;
        }
        state = BatchState.RUNNING;
        startedAt = System.currentTimeMillis();
        String startText = "开始批量起号：区服 " + config.targetServerId()
                + "，目标大熔炉 " + config.targetFurnaceLevel() + " 级";
        summaryMessage = startText;
        FileLog.info("起号批次 %s：id=%d 个账号 timeout=%dmin", startText, entries.size(),
                timeoutMinutes);
        store.append(view()); // 首次落盘建档；后续每号结束走 update

        for (AccountEntry entry : entries) {
            if (cancelled.get()) break;
            runAccount(entry);
            store.update(view());
        }

        if (cancelled.get()) {
            finish(BatchState.CANCELLED);
        } else if (failCount == 0) {
            finish(BatchState.COMPLETED);
        } else if (successCount > 0) {
            finish(BatchState.COMPLETED); // 部分成功（失败号有独立 error）
        } else {
            finish(BatchState.FAILED);
        }
    }

    /** 请求取消（标志位）：当前号自然结束后停，置 CANCELLED。 */
    public void cancel() {
        cancelled.set(true);
        FileLog.info("起号批次收到取消请求 id={} state={}", id, state);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String state() {
        return state.name();
    }

    public AccountEntry entry(int i) {
        return entries.get(i);
    }

    public int successCount() {
        return successCount;
    }

    public int failCount() {
        return failCount;
    }

    /**
     * 可持久化视图：批次元数据 + 统计 + 每号视图。凭据只出 masked / encrypted 产物，
     * 明文 sdkToken 绝不出现（回流清单由 {@link BootstrapReferral} 单独承载）。
     */
    public JSONObject view() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("state", state.name());
        o.put("serverId", config.targetServerId());
        o.put("targetFurnaceLevel", config.targetFurnaceLevel());
        o.put("allianceName", config.allianceName());
        o.put("roleName", config.roleName());
        o.put("instantDiamond", config.instantDiamond());
        o.put("timeoutMinutes", timeoutMinutes);
        o.put("createdAt", startedAt > 0 ? startedAt : System.currentTimeMillis());
        o.put("startedAt", startedAt);
        o.put("finishedAt", finishedAt);
        o.put("successCount", successCount);
        o.put("failCount", failCount);
        o.put("cancelRequested", cancelled.get());
        o.put("message", summaryMessage);
        JSONArray arr = new JSONArray();
        for (AccountEntry e : entries) arr.put(e.toView());
        o.put("accounts", arr);
        return o;
    }

    /** 终态收尾：置状态 + finishedAt + 落盘 + 汇总文案（对齐原版前端日志文案）。 */
    private void finish(BatchState terminal) {
        state = terminal;
        finishedAt = System.currentTimeMillis();
        summaryMessage = "批量处理结束：成功 " + successCount + " 个，失败 " + failCount + " 个";
        if (terminal == BatchState.CANCELLED) {
            summaryMessage += "（已取消）";
        }
        store.update(view());
        FileLog.info("起号批次结束 id={} state={} success={} fail={}",
                id, terminal, successCount, failCount);
    }

    // ================= 单号执行 =================

    /** 单号全流程：建号 → 循环升级 → 入盟 → 回流 → 关闭会话。失败只标记本号，批次继续。 */
    private void runAccount(AccountEntry entry) {
        entry.state = AccountState.RUNNING;
        entry.error = "";
        FileLog.info("起号批次 {} 开始处理 fpid={} sdkToken={}", id, entry.fpid,
                BootstrapReferral.mask(entry.sdkToken));
        Session session = null;
        try {
            entry.state = AccountState.CREATING;
            session = factory.create(entry.fpid, entry.sdkToken, config.targetServerId());
            if (activeSessions != null) {
                activeSessions.put(entry.fpid, session);
            }
            BootstrapService service = new BootstrapService(session.port,
                    new BootstrapPlanner(), BootstrapCatalog.combined(), serviceSleeper);
            service.applyConfig(accountConfig());
            BootstrapService.LoginOutcome outcome = service.createRole(session.engine,
                    entry.fpid, entry.sdkToken, config.targetServerId(), config.roleName());
            entry.state = AccountState.RUNNING;
            entry.uid = outcome.uid();
            entry.steps.add("建号成功 uid=" + outcome.uid());

            runLeveling(session.port, service, entry);

            if (entry.state != AccountState.SUCCESS) {
                return; // 升级阶段已标记 FAILED（停滞/超时/取消路径）
            }
            joinAllianceIfConfigured(service, entry);

            entry.furnaceLevel = service.state().furnaceLevel();
            writeReferral(entry);
            entry.steps.add("起号完成：大熔炉 " + entry.furnaceLevel + " 级，已写入回流清单");
            successCount++;
            FileLog.info("起号批次 {} 账号成功 fpid={} furnace={}", id, entry.fpid,
                    entry.furnaceLevel);
        } catch (InterruptedException e) {
            // 中断 = 停批次（建号/升级内 serviceSleeper 抛出）：恢复中断标志 + 置 cancelled，
            // 本号按取消收尾而非伪 FAILED（对齐轮间中断语义），剩余号不再开新号
            Thread.currentThread().interrupt();
            cancelled.set(true);
            entry.state = AccountState.FAILED;
            entry.error = "批次已取消";
            entry.steps.add("取消：" + entry.error);
            FileLog.warn("起号批次 {} 账号中断停止 fpid={}", id, entry.fpid);
        } catch (Exception e) {
            entry.state = AccountState.FAILED;
            entry.error = safeMessage(e);
            entry.steps.add("失败：" + entry.error);
            failCount++;
            FileLog.warn("起号批次 {} 账号失败 fpid={} error={}", id, entry.fpid, entry.error);
        } finally {
            if (activeSessions != null && session != null) {
                activeSessions.remove(entry.fpid, session);
            }
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * 升级循环：每轮 {@code req_loaddata} 拉全量校准 → {@code runUntil} 批量推进 →
     * 熔炉达标即出；{@code started==0} 视为不可继续（停滞），单号超时上限防死循环。
     */
    private void runLeveling(BootstrapService.ProtocolPort port, BootstrapService service,
                             AccountEntry entry) throws Exception {
        long target = config.targetFurnaceLevel();
        long deadline = System.currentTimeMillis() + timeoutMillis();
        int round = 0;
        while (true) {
            if (cancelled.get()) {
                entry.state = AccountState.FAILED;
                entry.error = "批次已取消";
                entry.steps.add("取消：" + entry.error);
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                entry.state = AccountState.FAILED;
                entry.error = "起号超时上限 " + timeoutMinutes + " 分钟";
                entry.steps.add("失败：" + entry.error);
                failCount++;
                return;
            }
            service.syncLoadData(contentOf(port.request(LOAD_DATA, Map.of())));
            round++;
            Map<String, Object> summary = service.runUntil(STEPS_PER_ROUND);
            Map<String, Object> stateView = (Map<String, Object>) summary.get("state");
            long furnace = service.state().furnaceLevel();
            int started = (int) com.carlos.vault.wjdr.protocol.ProtocolValueSupport
                    .number(summary.get("started"));
            if (furnace >= target) {
                entry.state = AccountState.SUCCESS;
                return;
            }
            if (started == 0) {
                entry.state = AccountState.FAILED;
                String blocked = stateView == null ? ""
                        : String.valueOf(stateView.getOrDefault("blockedReason", ""));
                String message = String.valueOf(summary.getOrDefault("message", ""));
                entry.error = "起号停滞：" + (blocked.isBlank()
                        ? (message.isBlank() ? "无进展且原因未知" : message) : blocked);
                entry.steps.add("失败：" + entry.error);
                failCount++;
                return;
            }
            entry.steps.add("第 " + round + " 轮升级 " + started + " 步（熔炉 " + furnace + " 级）");
            try {
                roundSleeper.sleep(ROUND_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                // 中断 = 停批次：恢复中断标志 + 置 cancelled，本号按取消收尾，剩余号不再开新号
                Thread.currentThread().interrupt();
                cancelled.set(true);
                entry.state = AccountState.FAILED;
                entry.error = "批次已取消";
                entry.steps.add("取消：" + entry.error);
                return;
            }
        }
    }

    /** 按配置入盟（allianceName 空跳过；失败抛异常 → 本号 FAILED）。 */
    private void joinAllianceIfConfigured(BootstrapService service, AccountEntry entry) throws Exception {
        String alliance = config.allianceName() == null ? "" : config.allianceName().trim();
        if (alliance.isEmpty()) {
            return;
        }
        Map<String, Object> joined = service.joinAlliance();
        entry.steps.add("已提交加入联盟 " + alliance + "（protocol="
                + joined.getOrDefault("protocol", "") + "）");
    }

    /** SUCCESS → 回流清单（M5 由 App 拉取并入 AccountStore 后再 sync，本类不写 VaultStore）。 */
    private void writeReferral(AccountEntry entry) {
        if (referral == null) return;
        JSONObject item = new JSONObject();
        item.put("fpid", entry.fpid);
        item.put("sdkToken", entry.sdkToken);
        item.put("encrypted", entry.encrypted == null ? JSONObject.NULL : entry.encrypted);
        item.put("label", entry.label);
        item.put("serverId", config.targetServerId());
        item.put("uid", entry.uid);
        item.put("furnaceLevel", entry.furnaceLevel);
        item.put("batchId", id);
        item.put("bootstrappedAt", System.currentTimeMillis());
        item.put("pulled", false);
        referral.append(item);
    }

    /** 单号配置：enabled=true + 批次字段透传（领奖/商店保持原版默认 false）。 */
    private BootstrapConfig accountConfig() {
        return new BootstrapConfig(true, config.targetServerId(), config.targetFurnaceLevel(),
                config.instantDiamond(), config.claimRewards(), config.buyShopResources(),
                config.allianceName(), config.autoApplyWhenPrivate(), config.roleName());
    }

    /** 单号超时上限毫秒（包级可覆写语义字段；测试构造可注入 0 立即触发超时分支）。 */
    long timeoutMillis() {
        return timeoutMillisField;
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    /** 响应内容防御式取值（null/缺 content → 空 map，语义同 BootstrapService.content）。 */
    private static Map<String, Object> contentOf(GameMessage response) {
        return response == null || response.getContent() == null
                ? Map.of() : response.getContent();
    }
}
