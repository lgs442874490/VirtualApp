package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.ClasspathAssets;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.AccountEntry;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.EngineFactory;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.ImportResult;
import com.carlos.vaultserver.bootstrap.BootstrapBatchRunner.Session;
import com.carlos.vaultserver.bootstrap.BootstrapService.BootstrapConfig;
import com.carlos.vaultserver.bootstrap.BootstrapService.Sleeper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 起号批量 HTTP 处理（{@code POST /v1/vault/bootstrap}，op={@code vaultBootstrap}），
 * 静态 handler 风格对齐 {@code RegistrationApi}：按 {@code action} 分发，异常全部兜成
 * {@code {error:"中文说明"}}，绝不外溢到 HTTP 层。
 *
 * <pre>
 * action=import            → 行清单解析预检（加密串 / fpid|token；成功清单 + 错误行）
 * action=batch-start       → 校验 → 建批次 → 提交独立单线程 executor → 立即回批次概要
 * action=batch-status      → 批次全量视图（每号状态/步骤摘要/统计）
 * action=batch-cancel      → 请求取消（当前号自然结束后置 CANCELLED）
 * action=batch-list        → 最近批次列表（limit 默认 30）
 * action=referral-list     → 待回流清单（pulled=false）
 * action=referral-pull     → 标记已拉取（M5 App 并入 AccountStore 后再 sync）
 * action=single-*          → 单号挂点（fpid → 在线起号会话表；无在线 engine 报错）
 * </pre>
 *
 * <p>校验（batch-start）：serverId 正整数；targetFurnaceLevel 1–9；accounts 1–100。
 * 凭据纪律：响应/日志只出 masked 与 encrypted 产物，明文 sdkToken 绝不回显
 * （回流清单文件本身是凭据交付本体，由 {@link BootstrapReferral} 独立承载）。
 */
public final class BootstrapApi {

    /** accounts / lines 合法域（任务书 §2.4）。 */
    static final int ACCOUNTS_MIN = 1;
    static final int ACCOUNTS_MAX = 100;
    /** batch-list 默认条数。 */
    static final int DEFAULT_LIST_LIMIT = BootstrapBatchStore.MAX_BATCHES;

    /**
     * 批次执行 executor：单线程 + daemon（批次串行，HTTP 线程不参与执行）。
     * 包级可覆写（单测注入同步执行 Runnable::run，测试秒级完成）。
     */
    static ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bootstrap-batch");
        t.setDaemon(true);
        return t;
    });

    /** 轮间/服务内等待（生产真实睡眠；包级可覆写——单测注入跳时实现）。 */
    static Sleeper BATCH_SLEEPER = BootstrapService.REAL_SLEEPER;

    /** 运行中的批次（id → runner），终态后由执行任务移除（历史走 BootstrapBatchStore）。 */
    private static final ConcurrentMap<String, BootstrapBatchRunner> RUNNERS =
            new ConcurrentHashMap<>();
    /** 在线起号会话表（fpid → session；批次 runner 执行期间注册，供 single-* 寻址）。 */
    static final ConcurrentMap<String, Session> ACTIVE = new ConcurrentHashMap<>();

    private static volatile BootstrapBatchStore store;
    private static volatile BootstrapReferral referral;
    private static volatile EngineFactory factory;

    private BootstrapApi() {
    }

    /** 生产装配（Main 启动一行挂载）：批次仓 + 回流清单 + 真实引擎工厂。 */
    public static void install(Path dataDir) {
        store = BootstrapBatchStore.install(dataDir);
        referral = BootstrapReferral.install(dataDir);
        factory = new ProductionEngineFactory();
        shutdownExecutor(); // 回收旧实例（重复装配场景防线程泄漏）
        EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bootstrap-batch");
            t.setDaemon(true);
            return t;
        });
        FileLog.info("起号批量模块已装配（bootstrap_batches / bootstrap_referral）");
    }

    /** 清空运行态（进程退出 / 单测隔离）。 */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
        RUNNERS.clear();
        ACTIVE.clear();
        BootstrapBatchStore.shutdown();
        BootstrapReferral.shutdown();
        store = null;
        referral = null;
        factory = null;
    }

    /** 单测装配：注入 store/referral/factory/sleeper（绕开 Main 生命周期）。 */
    static void installForTest(BootstrapBatchStore s, BootstrapReferral r, EngineFactory f,
                               Sleeper sleeper, boolean synchronousExecutor) {
        store = s;
        referral = r;
        factory = f;
        if (sleeper != null) BATCH_SLEEPER = sleeper;
        if (synchronousExecutor) EXECUTOR = new InlineExecutorService();
    }

    /** 单测清理：恢复默认 executor/睡眠。 */
    static void resetForTest() {
        shutdownExecutor(); // 回收旧实例（反复装测防线程泄漏）
        EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bootstrap-batch");
            t.setDaemon(true);
            return t;
        });
        BATCH_SLEEPER = BootstrapService.REAL_SLEEPER;
        RUNNERS.clear();
        ACTIVE.clear();
        store = null;
        referral = null;
        factory = null;
    }

    /** 回收当前批次 executor（InlineExecutorService 等空实现安全幂等）。 */
    private static void shutdownExecutor() {
        try {
            EXECUTOR.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    /** 调用线程直跑的同步 executor（单测秒级完成批次）。 */
    static final class InlineExecutorService extends java.util.concurrent.AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    }

    /**
     * 生产会话工厂：{@code new WjdrEngine(ClasspathAssets)} + {@code initialize()} 建连，
     * 协议端口包装 {@code engine.request} + 推送通道（notifyData/collectNotifies）。
     */
    static final class ProductionEngineFactory implements EngineFactory {
        @Override
        public Session create(String fpid, String sdkToken, long serverId) throws Exception {
            WjdrEngine engine = new WjdrEngine(new ClasspathAssets());
            engine.initialize();
            Session session = new Session(engine, new EnginePort(engine));
            FileLog.info("起号会话已建立 fpid={} server={} sdkToken={}", fpid, serverId,
                    BootstrapReferral.mask(sdkToken));
            return session;
        }
    }

    /** 生产协议端口：请求 + 推送读取/收集直通 {@link WjdrEngine}。 */
    static final class EnginePort implements BootstrapService.ProtocolPort {
        private final WjdrEngine engine;

        EnginePort(WjdrEngine engine) {
            this.engine = engine;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) throws Exception {
            return engine.request(proto, args);
        }

        @Override
        public Map<String, Object> notify(String proto) {
            Map<String, Object> inner = engine.notifyData(proto);
            return inner != null ? inner : engine.notifyContent(proto);
        }

        @Override
        public void collectNotifies(long millis) {
            engine.collectNotifies(millis);
        }
    }

    /**
     * 处理一次起号批量请求。
     *
     * @param fields 已验签的扁平请求字段（可为 null，等价空请求）
     * @return 响应信封的 {@code data} 对象；任何异常都转成 {@code {error:"中文说明"}}
     */
    public static JSONObject handle(Map<String, Object> fields) {
        Map<String, Object> f = fields == null ? Map.of() : fields;
        String action = str(f, "action");
        if (action == null || action.trim().isEmpty()) action = "";
        try {
            switch (action.trim().toLowerCase(Locale.ROOT)) {
                case "import":
                    return importLines(f);
                case "batch-start":
                    return batchStart(f);
                case "batch-status":
                    return batchStatus(f);
                case "batch-cancel":
                    return batchCancel(f);
                case "batch-list":
                    return batchList(f);
                case "referral-list":
                    return referralList();
                case "referral-pull":
                    return referralPull(f);
                case "single-status":
                case "single-run-next":
                case "single-run-until":
                case "single-level-building":
                case "single-alliance":
                    return singleAction(action.trim().toLowerCase(Locale.ROOT), f);
                default:
                    return error("未知 action: " + action + "（支持 import / batch-start / batch-status"
                            + " / batch-cancel / batch-list / referral-list / referral-pull / single-*）");
            }
        } catch (IllegalArgumentException e) {
            return error(e.getMessage() == null ? "参数不合法" : e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage() == null ? "起号执行失败" : e.getMessage());
        } catch (Throwable t) {
            FileLog.warn("起号接口异常 action={}：{}", action, String.valueOf(t));
            return error("起号服务异常: " + (t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    // ---------------- action 实现 ----------------

    /** import：行清单解析预检（加密串 / fpid|token），不建批次。 */
    private static JSONObject importLines(Map<String, Object> f) {
        List<String> lines = stringList(f, "lines");
        ImportResult result = BootstrapBatchRunner.importAccounts(lines);
        JSONObject out = new JSONObject();
        JSONArray imported = new JSONArray();
        for (AccountEntry e : result.accounts()) {
            JSONObject o = new JSONObject();
            o.put("fpid", e.fpid);
            o.put("label", e.label);
            o.put("hasEncrypted", e.encrypted != null);
            imported.put(o);
        }
        JSONArray errors = new JSONArray();
        for (String err : result.errors()) errors.put(err);
        out.put("count", imported.length());
        out.put("imported", imported);
        out.put("errorCount", errors.length());
        out.put("errors", errors);
        FileLog.info("起号清单解析：成功 {} 行，错误 {} 行", imported.length(), errors.length());
        return out;
    }

    /** batch-start：校验 → 建批次 → executor 提交 → 立即回概要。 */
    private static JSONObject batchStart(Map<String, Object> f) {
        EngineFactory fac = requireFactory();
        long serverId = longField(f, "serverId", 0L);
        if (serverId <= 0L) {
            throw new IllegalArgumentException("serverId 必须为正整数");
        }
        int target = intField(f, "targetFurnaceLevel", BootstrapConfig.DEFAULT_TARGET_FURNACE_LEVEL);
        if (target < 1 || target > BootstrapConfig.MAX_TARGET_FURNACE_LEVEL) {
            throw new IllegalArgumentException("targetFurnaceLevel 必须在 1–"
                    + BootstrapConfig.MAX_TARGET_FURNACE_LEVEL + " 之间");
        }
        int timeoutMinutes = intField(f, "batchTimeoutMinutes",
                BootstrapBatchRunner.DEFAULT_TIMEOUT_MINUTES);
        List<AccountEntry> accounts = parseAccounts(f);
        Map<String, String> labelByFpid = collectLabels(f);
        for (AccountEntry e : accounts) {
            String label = labelByFpid.get(e.fpid);
            if (label != null && !label.isBlank()) {
                e.label = label.trim();
            }
        }
        BootstrapConfig cfg = new BootstrapConfig(true, serverId, target,
                booleanField(f, "instantDiamond", true), false, false,
                str(f, "allianceName") == null ? "" : str(f, "allianceName").trim(),
                false,
                str(f, "roleName") == null ? "" : str(f, "roleName").trim());
        BootstrapBatchRunner runner = new BootstrapBatchRunner(cfg, accounts, storeOf(),
                referralOf(), fac, BATCH_SLEEPER, timeoutMinutes, ACTIVE);
        RUNNERS.put(runner.id, runner);
        EXECUTOR.submit(() -> {
            try {
                runner.run();
            } finally {
                RUNNERS.remove(runner.id);
            }
        });
        FileLog.info("起号批次已启动 id={} server={} target={} accounts={} alliance={}",
                runner.id, serverId, target, accounts.size(), cfg.allianceName());
        return batchSummary(runner);
    }

    /** batch-status：优先内存 runner 实时视图，批次已移除则回查 store 历史。 */
    private static JSONObject batchStatus(Map<String, Object> f) {
        String batchId = str(f, "batchId");
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 batchId");
        }
        BootstrapBatchRunner runner = RUNNERS.get(batchId.trim());
        JSONObject view = runner != null ? runner.view() : storeOf().find(batchId.trim());
        if (view == null) {
            throw new IllegalArgumentException("批次不存在: " + batchId.trim());
        }
        return view;
    }

    /** batch-cancel：请求取消；批次不在内存（已终态/不存在）给出明确文案。 */
    private static JSONObject batchCancel(Map<String, Object> f) {
        String batchId = str(f, "batchId");
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 batchId");
        }
        JSONObject out = new JSONObject();
        out.put("batchId", batchId.trim());
        BootstrapBatchRunner runner = RUNNERS.get(batchId.trim());
        if (runner == null) {
            JSONObject historic = storeOf().find(batchId.trim());
            if (historic == null) {
                throw new IllegalArgumentException("批次不存在: " + batchId.trim());
            }
            out.put("ok", false);
            out.put("state", historic.optString("state", ""));
            out.put("message", "批次已结束，无需取消");
            return out;
        }
        runner.cancel();
        out.put("ok", true);
        out.put("state", runner.state());
        out.put("message", "已请求取消，当前账号起号自然结束后停止");
        return out;
    }

    /** batch-list：最近批次列表摘要（limit 默认 30，上限 100）。 */
    private static JSONObject batchList(Map<String, Object> f) {
        int limit = intField(f, "limit", DEFAULT_LIST_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIST_LIMIT;
        if (limit > 100) limit = 100;
        JSONArray arr = new JSONArray();
        for (JSONObject v : storeOf().list(limit)) {
            JSONObject summary = new JSONObject();
            summary.put("id", v.optString("id", ""));
            summary.put("state", v.optString("state", ""));
            summary.put("serverId", v.optLong("serverId", 0L));
            summary.put("targetFurnaceLevel", v.optInt("targetFurnaceLevel", 0));
            summary.put("successCount", v.optInt("successCount", 0));
            summary.put("failCount", v.optInt("failCount", 0));
            summary.put("startedAt", v.optLong("startedAt", 0L));
            summary.put("finishedAt", v.optLong("finishedAt", 0L));
            summary.put("message", v.optString("message", ""));
            arr.put(summary);
        }
        JSONObject out = new JSONObject();
        out.put("count", arr.length());
        out.put("batches", arr);
        return out;
    }

    /** referral-list：待回流清单（pulled=false）。 */
    private static JSONObject referralList() {
        List<JSONObject> pending = referralOf().listPending();
        JSONArray arr = new JSONArray();
        for (JSONObject e : pending) arr.put(e);
        JSONObject out = new JSONObject();
        out.put("count", arr.length());
        out.put("entries", arr);
        return out;
    }

    /** referral-pull：标记已拉取（fpids 数组字符串或逗号分隔）。 */
    private static JSONObject referralPull(Map<String, Object> f) {
        List<String> fpids = stringList(f, "fpids");
        if (fpids.isEmpty()) {
            throw new IllegalArgumentException("缺少 fpids");
        }
        int marked = referralOf().markPulled(fpids);
        JSONObject out = new JSONObject();
        out.put("marked", marked);
        out.put("requested", fpids.size());
        out.put("message", "已标记 " + marked + " 个账号为已拉取");
        return out;
    }

    /**
     * 单号挂点：fpid → 在线起号会话表 → {@link BootstrapService} 对应方法。
     * 无在线 engine 报「该号不在线，起号会话未运行」。
     */
    private static JSONObject singleAction(String action, Map<String, Object> f) throws Exception {
        String fpid = str(f, "fpid");
        if (fpid == null || fpid.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 fpid");
        }
        fpid = fpid.trim();
        Session session = ACTIVE.get(fpid);
        if (session == null) {
            throw new IllegalStateException("该号不在线，起号会话未运行");
        }
        BootstrapService service = serviceOf(session);
        JSONObject out;
        switch (action) {
            case "single-status":
                out = new JSONObject(service.status());
                break;
            case "single-run-next":
                out = new JSONObject(service.runNext());
                break;
            case "single-run-until":
                int maxSteps = intField(f, "maxSteps", 8);
                if (maxSteps < 1 || maxSteps > 300) {
                    throw new IllegalArgumentException("maxSteps 必须在 1–300 之间");
                }
                out = new JSONObject(service.runUntil(maxSteps));
                break;
            case "single-level-building":
                long buildingId = longField(f, "buildingId", 0L);
                if (buildingId <= 0L) {
                    throw new IllegalArgumentException("buildingId 必须为正整数");
                }
                out = new JSONObject(service.levelBuilding(buildingId));
                break;
            case "single-alliance":
                out = new JSONObject(service.joinAlliance());
                break;
            default:
                throw new IllegalArgumentException("未知 action: " + action);
        }
        out.put("fpid", fpid);
        return out;
    }

    // ---------------- 工具 ----------------

    /**
     * 单号挂点用 BootstrapService（每次请求临时装配：同一 port 重放，无共享状态污染）。
     * 装配后先经 port 发 {@code req_loaddata} 同步全量状态（对齐批次线程
     * {@link BootstrapBatchRunner} 每轮校准方式），否则 single-run-next / run-until /
     * level-building 因建筑队列未就绪永远 WAITING。
     */
    private static BootstrapService serviceOf(Session session) throws Exception {
        BootstrapService service = new BootstrapService(session.port, new BootstrapPlanner(),
                BootstrapCatalog.combined(), BATCH_SLEEPER);
        service.applyConfig(new BootstrapConfig(true, 0L, BootstrapConfig.DEFAULT_TARGET_FURNACE_LEVEL,
                true, false, false, "", false, ""));
        service.syncLoadData(contentOf(session.port.request("req_loaddata", Map.of())));
        return service;
    }

    /** 响应内容防御式取值（null/缺 content → 空 map，语义同 BootstrapService.content）。 */
    private static Map<String, Object> contentOf(GameMessage response) {
        return response == null || response.getContent() == null
                ? Map.of() : response.getContent();
    }

    /**
     * accounts 解析（JSON 数组字符串）：元素为字符串行（加密串或 fpid|token）或对象
     * （{fpid, sdkToken, label?, encrypted?}）；对象归一为行字符串后统一走
     * {@link BootstrapBatchRunner#importAccounts}（去重/报错行号同口径），label 按 fpid 回填。
     */
    private static List<AccountEntry> parseAccounts(Map<String, Object> f) {
        JSONArray arr = optJsonArray(f, "accounts");
        if (arr == null || arr.length() == 0) {
            throw new IllegalArgumentException("缺少账号清单 accounts");
        }
        if (arr.length() < ACCOUNTS_MIN || arr.length() > ACCOUNTS_MAX) {
            throw new IllegalArgumentException("accounts 数量必须在 " + ACCOUNTS_MIN + "–"
                    + ACCOUNTS_MAX + " 之间");
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Object element = arr.get(i);
            if (element instanceof String) {
                lines.add(String.valueOf(element));
                continue;
            }
            if (element instanceof JSONObject) {
                JSONObject o = (JSONObject) element;
                String encrypted = o.optString("encrypted", "").trim();
                if (!encrypted.isEmpty()) {
                    lines.add(encrypted);
                    continue;
                }
                String fpid = o.optString("fpid", "").trim();
                String sdkToken = o.optString("sdkToken", "").trim();
                if (!fpid.isEmpty() && !sdkToken.isEmpty()) {
                    lines.add(fpid + "|" + sdkToken);
                    continue;
                }
                throw new IllegalArgumentException("第 " + (i + 1) + " 个账号必须是加密串、"
                        + "fpid+sdkToken 或含 encrypted 的对象");
            }
            throw new IllegalArgumentException("第 " + (i + 1) + " 个账号必须是字符串或对象");
        }
        ImportResult result = BootstrapBatchRunner.importAccounts(lines);
        if (result.accounts().isEmpty()) {
            throw new IllegalArgumentException("账号清单解析后为空："
                    + (result.errors().isEmpty() ? "无有效行" : result.errors().get(0)));
        }
        return new ArrayList<>(result.accounts());
    }

    /** accounts 元素的 label 收集（fpid → label；解析后回填，不进行字符串）。 */
    private static Map<String, String> collectLabels(Map<String, Object> f) {
        Map<String, String> labels = new LinkedHashMap<>();
        JSONArray arr = optJsonArray(f, "accounts");
        if (arr == null) return labels;
        for (int i = 0; i < arr.length(); i++) {
            if (!(arr.get(i) instanceof JSONObject)) continue;
            JSONObject o = (JSONObject) arr.get(i);
            String fpid = o.optString("fpid", "").trim();
            String label = o.optString("label", "").trim();
            if (!fpid.isEmpty() && !label.isEmpty()) labels.put(fpid, label);
        }
        return labels;
    }

    /** 批次概要（batch-start 立即返回）：id/状态/配置摘要，凭据不出。 */
    private static JSONObject batchSummary(BootstrapBatchRunner runner) {
        JSONObject v = runner.view();
        JSONObject out = new JSONObject();
        out.put("id", v.optString("id"));
        out.put("state", v.optString("state"));
        out.put("serverId", v.optLong("serverId"));
        out.put("targetFurnaceLevel", v.optInt("targetFurnaceLevel"));
        out.put("allianceName", v.optString("allianceName"));
        out.put("roleName", v.optString("roleName"));
        out.put("instantDiamond", v.optBoolean("instantDiamond"));
        out.put("timeoutMinutes", v.optInt("timeoutMinutes"));
        out.put("accountCount", v.optJSONArray("accounts") == null ? 0
                : v.optJSONArray("accounts").length());
        out.put("message", v.optString("message"));
        return out;
    }

    private static EngineFactory requireFactory() {
        EngineFactory fac = factory;
        if (fac == null) {
            throw new IllegalStateException("起号服务未安装（服务端未就绪）");
        }
        return fac;
    }

    private static BootstrapBatchStore storeOf() {
        BootstrapBatchStore s = store;
        return s == null ? new BootstrapBatchStore(null) : s;
    }

    private static BootstrapReferral referralOf() {
        BootstrapReferral r = referral;
        if (r == null) {
            throw new IllegalStateException("回流清单未安装（服务端未就绪）");
        }
        return r;
    }

    /** JSON 数组字符串字段（HMAC 扁平标量约定），解析失败给中文错误；缺失/空返回 null。 */
    private static JSONArray optJsonArray(Map<String, Object> f, String key) {
        String raw = str(f, key);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new JSONArray(raw);
        } catch (Throwable t) {
            throw new IllegalArgumentException(key + " 必须是 JSON 数组字符串");
        }
    }

    /** JSON 数组字符串 → List&lt;String&gt;（元素 Number/Boolean 取字符串化）。缺失/空返回空表。 */
    private static List<String> stringList(Map<String, Object> f, String key) {
        List<String> out = new ArrayList<>();
        JSONArray arr = optJsonArray(f, key);
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.get(i);
            if (v == null) continue;
            out.add(String.valueOf(v));
        }
        return out;
    }

    private static String str(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return "null".equals(s) ? null : s;
    }

    private static int intField(Map<String, Object> fields, String key, int def) {
        Object v = fields.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static long longField(Map<String, Object> fields, String key, long def) {
        Object v = fields.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) {
            try {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static boolean booleanField(Map<String, Object> fields, String key, boolean def) {
        Object v = fields.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v != null) {
            String s = String.valueOf(v).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        }
        return def;
    }

    /** 统一错误形态：{error: 中文说明}。 */
    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        out.put("error", message == null ? "错误" : message);
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }

    /** 供单测观察运行中批次表 / 在线会话表（不暴露 map 本体）。 */
    static boolean hasRunner(String batchId) {
        return RUNNERS.containsKey(batchId);
    }

    static boolean hasActiveSession(String fpid) {
        return ACTIVE.containsKey(fpid);
    }
}
