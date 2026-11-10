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
import java.util.LinkedHashMap;
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

    /**
     * 单号连续「无进展」轮数上限（每轮 {@link #ROUND_INTERVAL_MILLIS}）。
     * 真实轮（2026-11-10）：新号资源不足建伐木场、建筑正在建造，都是**正常等待态**，
     * 原版语义是等资源/等队列完成后继续，而非失败。故等待类原因连续 120 轮（约 10 分钟）
     * 仍无进展才判停滞，异常类原因（不支持建筑/依赖成环等）仍立即失败。
     */
    private static final int MAX_IDLE_ROUNDS = 120;

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
        FileLog.info("起号批次 {}：id={} 个账号 timeout={}min", startText, entries.size(),
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
            BootstrapService.LoginOutcome outcome;
            try {
                outcome = service.createRole(session.engine,
                        entry.fpid, entry.sdkToken, config.targetServerId(), config.roleName());
                entry.steps.add("建号成功 uid=" + outcome.uid());
            } catch (Exception createError) {
                // 角色已存在时 is_create=true 会被游戏拒绝（真实轮 code=9）：回退普通登录进既有角色
                FileLog.warn("起号建号失败，回退既有角色登录 fpid={}：{}", entry.fpid,
                        String.valueOf(createError.getMessage()));
                session.engine.initialize();   // 二次 loginauth 必须走全新连接
                outcome = service.loginExisting(session.engine,
                        entry.fpid, entry.sdkToken, config.targetServerId());
                entry.steps.add("已存在角色，直接登录 uid=" + outcome.uid());
            }
            entry.state = AccountState.RUNNING;
            entry.uid = outcome.uid();
            // 存档补写区服角色（手机号换号找回链路的第二段）
            try {
                com.carlos.vaultserver.registration.AccountArchive archive =
                        com.carlos.vaultserver.registration.AccountArchive.get();
                if (archive != null) {
                    archive.recordRole(entry.fpid, config.targetServerId(), outcome.uid());
                }
            } catch (RuntimeException ignored) {
                // 存档失败不影响起号
            }

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
    /** 等待类阻塞原因判定（真实轮校准：这些都不是错误，等时间即可恢复）。 */
    static boolean isWaitableBlock(String reason) {
        if (reason == null || reason.isBlank()) return false;
        // 注意：不含「等待完成」——MANUAL 文案「需要等待完成，请开启钻石即时升级」属于需要
        // 人工改配置才能推进的状态，必须立即失败暴露出来，不能当等待类空等。
        for (String kw : new String[]{"资源或道具不足", "正在建造", "正在升级",
                "等待建筑与队列", "等待同步", "没有空闲建筑队列", "等待资源"}) {
            if (reason.contains(kw)) return true;
        }
        return false;
    }

    /** 轮间等待；被中断则置 cancelled 并返回 false（本号按取消收尾）。 */
    private boolean sleepRound(AccountEntry entry) {
        try {
            roundSleeper.sleep(ROUND_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            entry.state = AccountState.FAILED;
            entry.error = "批次已取消";
            return false;
        }
    }

    private void runLeveling(BootstrapService.ProtocolPort port, BootstrapService service,
                             AccountEntry entry) throws Exception {
        long target = config.targetFurnaceLevel();
        long deadline = System.currentTimeMillis() + timeoutMillis();
        int round = 0;
        int idleRounds = 0;
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
            port.collectNotifies(6000L);   // 游戏是推送式下发：登录后要等全量推送（实测需 >5s）
            Map<String, Object> loadContent = contentOf(port.request(LOAD_DATA, Map.of()));
            // 真实轮（2026-11-10 实测）：req_loaddata 只回 {code}，建筑/队列/资源全在推送里——
            //   notify_shelter_info.building_info（建筑明细）、notify_queue_info.queue_map（队列）、
            //   notify_resource_init.data / notify_backpack_init.data（资源与背包）、
            //   notify_base_info.stove_lv/uid/nickName（熔炉等级与角色）。
            // 故先把推送合成到 content 再交给状态机（键名与 BootstrapState 既有候选一致）。
            Map<String, Object> merged = new LinkedHashMap<>(loadContent);
            putIfPresent(merged, "building_info", notifyField(port, "notify_shelter_info", "building_info"));
            putIfPresent(merged, "queue_map", notifyField(port, "notify_queue_info", "queue_map"));
            putIfPresent(merged, "res", notifyField(port, "notify_resource_init", "data"));
            putIfPresent(merged, "bag", notifyField(port, "notify_backpack_init", "data"));
            // 幸存者列表（真实轮 2026-11-11：委任上岗是资源产出的前提）
            putIfPresent(merged, "survivor_list", notifyField(port, "notify_survivor_info", "survivor_list"));
            putIfPresent(merged, "survivor_list", notifyField(port, "notify_survivor_add", "survivor_list"));
            // 英雄与探险（新版 BootstrapTutorialState 数据源）：没有英雄打不了探险
            putIfPresent(merged, "hero_list", notifyField(port, "notify_hero_init", "data"));
            putIfPresent(merged, "adventure_pve_info",
                    notifyField(port, "notify_adventure_pve_info", "adventure_pve_info"));
            // 新手引导（新版 BootstrapTutorialState 数据源）
            putIfPresent(merged, "guide_list", notifyField(port, "notify_guide_init", "data"));
            // 产出资源（新号初始资源来源，必须领取才会进背包）
            putIfPresent(merged, "produced_list",
                    notifyField(port, "notify_produced_resource_init", "data"));
            Map<String, Object> baseInfo = port.notify("notify_base_info");
            if (baseInfo != null) {
                putIfPresent(merged, "stove_lv", baseInfo.get("stove_lv"));
                putIfPresent(merged, "uid", baseInfo.get("uid"));
                putIfPresent(merged, "name", baseInfo.get("nickName"));
            }
            loadContent = merged;
            service.syncLoadData(loadContent);
            // 首轮记录一次资源/背包/建筑规模（真实轮排障：新号资源为 0 是 51 的常见原因）
            // 每轮诊断（前 5 轮）：熔炉等级/busy/队列/动作结果，定位「只执行一次后停滞」
            if (round < 5) {
                FileLog.warn("起号轮{} 熔炉={} busy={} 队列={} 建筑数={}",
                        String.valueOf(round),
                        String.valueOf(service.state().furnaceLevel()),
                        String.valueOf(service.state().busyBuildings()),
                        String.valueOf(service.state().queueMapForTest()),
                        String.valueOf(service.state().buildingLevels().size()));
            }
            if (round == 0) {
                // 诊断：确认各类推送是否到齐（缺哪类就补哪类合成）
                FileLog.info("起号首轮已收推送协议({}个)：{}", String.valueOf(port.notifyProtos().size()),
                        String.valueOf(port.notifyProtos()));
                for (String proto : new String[]{"notify_hero_init", "notify_guide_init",
                        "notify_produced_resource_init", "notify_adventure_pve_info"}) {
                    Map<String, Object> raw = port.notify(proto);
                    String text = String.valueOf(raw);
                    FileLog.info("推送原文 {} keys={} 内容={}", proto,
                            raw == null ? "null" : String.valueOf(raw.keySet()),
                            text.length() > 300 ? text.substring(0, 300) : text);
                }
                FileLog.info("起号首轮推送：survivors={} heroes={} guides={} adventure={} 背包物品={}",
                        String.valueOf(service.state().survivors().size()),
                        String.valueOf(service.state().heroLevels().size()),
                        String.valueOf(service.state().guideSteps().size()),
                        String.valueOf(service.state().adventurePassed()),
                        String.valueOf(loadContent.get("bag") instanceof java.util.List
                                ? ((java.util.List<?>) loadContent.get("bag")).size() : -1));
            }
            if (round == 0) {
                FileLog.info("起号首轮状态 res={} bagItems={} buildings={} furnace={}",
                        String.valueOf(service.state().resourceMap()),
                        String.valueOf(loadContent.get("bag") instanceof java.util.List
                                ? ((java.util.List<?>) loadContent.get("bag")).size() : -1),
                        String.valueOf(service.state().buildingLevels().size()),
                        String.valueOf(service.state().furnaceLevel()));
            }
            if (!service.state().buildingsReady() || !service.state().queuesReady()) {
                // 推送未到齐（登录初期偶发）：记录一次便于排障，本轮按等待处理
                FileLog.warn("起号推送未齐：buildingsReady={} queuesReady={} 已收协议数={}",
                        String.valueOf(service.state().buildingsReady()),
                        String.valueOf(service.state().queuesReady()), port.notifyProtos().size());
            }
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
                String blocked = stateView == null ? ""
                        : String.valueOf(stateView.getOrDefault("blockedReason", ""));
                String message = String.valueOf(summary.getOrDefault("message", ""));
                String reason = blocked.isBlank()
                        ? (message.isBlank() ? "无进展且原因未知" : message) : blocked;
                // 等待类原因（资源/道具不足、建筑正在建造、等待同步）→ 继续等，不判失败
                if (isWaitableBlock(reason)) {
                    idleRounds++;
                    if (idleRounds > MAX_IDLE_ROUNDS) {
                        entry.state = AccountState.FAILED;
                        entry.error = "起号停滞超时：" + reason;
                        entry.steps.add("失败：" + entry.error);
                        failCount++;
                        return;
                    }
                    if (idleRounds == 1 || idleRounds % 12 == 0) {
                        entry.steps.add("第 " + round + " 轮等待中：" + reason);
                    }
                    if (!sleepRound(entry)) return;
                    continue;
                }
                entry.state = AccountState.FAILED;
                entry.error = "起号停滞：" + reason;
                entry.steps.add("失败：" + entry.error);
                failCount++;
                return;
            }
            idleRounds = 0;
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
    /** 取推送里的一级字段（推送体已是 {@code {field:...}} 形状）；缺失返回 null。 */
    private static Object notifyField(BootstrapService.ProtocolPort port, String proto, String field) {
        Map<String, Object> n = port.notify(proto);
        if (n == null) return null;
        Object v = n.get(field);
        if (v != null) return v;
        // engine.notifyData 已把 {data:{...}} 解包成内层 Map，此时不存在 data 键：
        // 对这种形状直接返回整体（英雄/引导/产出资源都是这个形状）
        return "data".equals(field) ? n : null;
    }

    /** 仅当值非空时写入（避免 null 覆盖 content 里已有的同名键）。 */
    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && target != null) target.put(key, value);
    }

    private static Map<String, Object> contentOf(GameMessage response) {
        return response == null || response.getContent() == null
                ? Map.of() : response.getContent();
    }
}
