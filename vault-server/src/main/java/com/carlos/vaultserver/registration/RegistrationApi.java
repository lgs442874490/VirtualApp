package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.passport.PassportClient;
import com.carlos.vaultserver.passport.PassportConfig;
import com.carlos.vaultserver.passport.PassportIds;
import com.carlos.vaultserver.registration.RegistrationService.PhoneBatchConfig;
import com.carlos.vaultserver.registration.RegistrationService.RegistrationOutcome;
import com.carlos.vaultserver.sms.D1SmsProvider;
import com.carlos.vaultserver.sms.SmsProvider;
import com.carlos.vaultserver.sms.SmsProviders;

import org.json.JSONObject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 起号注册 HTTP 处理（{@code POST /v1/vault/registration}，op={@code vaultRegistration}），
 * 静态 handler 风格对齐 {@code MonitorApi}：按 {@code action} 分发，异常全部兜成
 * {@code {error:"中文说明"}}，绝不外溢到 HTTP 层。
 *
 * <pre>
 * action=express        → 游客注册（sdkToken 仅本响应返回一次；凭据 seal 存内存表供 real-name）
 * action=real-name      → fpid/name/id_card；sdkToken 从内存 seal 表取（取不到提示先 express/批次）
 * action=provider-test  → 接码平台连接自检（ok/balanceText/maxNumbers/credentialMask/error）
 * action=batch-start    → 建批次并提交独立单线程 executor，HTTP 立即回批次概要（凭据 masked）
 * action=batch-status   → 批次全量视图（每号状态/步骤/统计；encrypted 仅 SUCCESS 条目携带）
 * action=batch-cancel   → 请求取消（当前号自然结束后置 CANCELLED）
 * action=batch-list     → 最近批次列表摘要（limit 默认 30）
 * </pre>
 *
 * <p>校验（batch-start）：providerType ∈ {d1, haozhuma}；goalCount 1–50；maxNumbers ≥ goalCount
 * 且 ≤ 100；realName.idCard 18 位；gamePassword ≥ 6 位或空。凭据纪律：响应/日志只出 masked
 * 与 encrypted 产物；{@code gamePassword} 不落盘、不回显。
 */
public final class RegistrationApi {

    /** goalCount / maxNumbers 合法域（任务书 §2.1）。 */
    static final int GOAL_MIN = 1;
    static final int GOAL_MAX = 50;
    static final int MAX_NUMBERS_CEILING = 100;
    /** batch-list 默认条数。 */
    static final int DEFAULT_LIST_LIMIT = RegistrationStore.MAX_BATCHES;

    /**
     * D1 transport 供给（生产 HttpURLConnection；包级可覆写——单测注入假传输防真实网络），
     * 仅 {@code provider-test} 自检通道使用；批次取号/收码走 {@link RegistrationService} 注入。
     */
    static java.util.function.Supplier<D1SmsProvider.Transport> TRANSPORT_SUPPLIER =
            D1SmsProvider.HttpUrlConnectionTransport::new;

    /**
     * 批次号间隔 sleeper（生产真实睡眠；包级可覆写——单测注入跳时实现，测试秒级完成）。
     */
    static RegistrationService.Sleeper BATCH_SLEEPER = RegistrationService.REAL_SLEEPER;

    /** 批次执行 executor：单线程 + daemon（批次串行，HTTP 线程不参与执行）。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "registration-batch");
        t.setDaemon(true);
        return t;
    });

    /** express 游客注册的内存凭据表（批次外单独注册的账号：real-name / 后续建号用）。 */
    private static final CredentialBox EXPRESS_BOX = new CredentialBox();
    /** 运行中的批次（id → runner），终态后由执行任务移除（历史走 RegistrationStore）。 */
    private static final ConcurrentMap<String, RegistrationBatchRunner> RUNNERS =
            new ConcurrentHashMap<>();

    private static volatile RegistrationService service;

    private RegistrationApi() {
    }

    /** 安装服务（Main 启动调用生产装配；单测注入 fake service 覆盖）。 */
    public static void install(RegistrationService svc) {
        service = svc;
    }

    /** 清除服务（进程退出 / 单测隔离）。 */
    public static void uninstall() {
        service = null;
    }

    /** 生产装配：RegistrationStore.install + 真实 passport/接码工厂（Main 一行挂载）。 */
    public static RegistrationService install(Path dataDir) {
        RegistrationStore.install(dataDir);
        RegistrationService svc = RegistrationService.production(
                PassportFacade.of(new PassportClient(PassportConfig.defaults(),
                        new PassportClient.HttpUrlConnectionTransport())));
        install(svc);
        return svc;
    }

    /** express 内存表清空（单测隔离用）。 */
    static void clearExpressBox() {
        EXPRESS_BOX.clear();
    }

    /**
     * 处理一次注册请求。
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
                case "express":
                    return express();
                case "real-name":
                    return realName(f);
                case "provider-test":
                    return providerTest(f);
                case "batch-start":
                    return batchStart(f);
                case "batch-status":
                    return batchStatus(f);
                case "batch-cancel":
                    return batchCancel(f);
                case "batch-list":
                    return batchList(f);
                default:
                    return error("未知 action: " + action + "（支持 express / real-name / provider-test"
                            + " / batch-start / batch-status / batch-cancel / batch-list）");
            }
        } catch (IllegalArgumentException e) {
            return error(e.getMessage() == null ? "参数不合法" : e.getMessage());
        } catch (IllegalStateException e) {
            return error(e.getMessage() == null ? "注册失败" : e.getMessage());
        } catch (Throwable t) {
            FileLog.warn("注册接口异常 action={}：{}", action, String.valueOf(t));
            return error("注册服务异常: " + (t.getMessage() == null
                    ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    // ---------------- action 实现 ----------------

    /** express：游客注册；sdkToken 仅本次响应返回，凭据 seal 进内存表（表键 fpid）。 */
    private static JSONObject express() {
        RegistrationService svc = requireService();
        RegistrationOutcome o = svc.guestRegister();
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("fpid", o.fpid);
        creds.put("sdkToken", o.sdkToken);
        EXPRESS_BOX.seal(creds);
        FileLog.info("express 游客注册 fpid={}", o.fpid);
        JSONObject out = new JSONObject();
        out.put("fpid", o.fpid);
        out.put("sdkToken", o.sdkToken); // 仅此一次
        out.put("encrypted", o.encrypted);
        out.put("createdAt", o.createdAt);
        return out;
    }

    /** real-name：凭据从 express 内存表取；取不到提示先用 express / 批次注册。 */
    private static JSONObject realName(Map<String, Object> f) {
        RegistrationService svc = requireService();
        String fpid = str(f, "fpid");
        if (fpid == null || fpid.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 fpid");
        }
        String name = str(f, "name");
        String idCard = str(f, "id_card");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入姓名");
        }
        if (!PassportIds.isValidIdCard(idCard)) {
            throw new IllegalArgumentException("身份证号必须为 18 位");
        }
        Map<String, String> creds = EXPRESS_BOX.take(fpid.trim());
        if (creds == null || creds.get("sdkToken") == null) {
            throw new IllegalArgumentException("该 fpid 没有在服务端登记的凭据，请先用 express 游客注册"
                    + "或批次注册成功后再实名");
        }
        RegistrationOutcome o = svc.realName(fpid.trim(), creds.get("sdkToken"),
                name.trim(), idCard.trim());
        JSONObject out = new JSONObject();
        out.put("fpid", fpid.trim());
        out.put("realNameVerified", o.realNameVerified);
        out.put("realNameMessage", o.realNameMessage);
        return out;
    }

    /** provider-test：接码平台连接自检（create 失败转中文错误）。 */
    private static JSONObject providerTest(Map<String, Object> f) {
        String type = providerType(f);
        Map<String, String> cfg = providerConfig(f);
        SmsProvider provider = SmsProviders.create(type, cfg, TRANSPORT_SUPPLIER.get());
        SmsProvider.ProviderStatus st = provider.testConnection();
        JSONObject out = new JSONObject();
        out.put("ok", st.ok);
        out.put("balanceText", st.balanceText);
        out.put("maxNumbers", st.maxNumbers);
        out.put("credentialMask", st.credentialMask);
        out.put("error", st.error);
        FileLog.info("接码平台自检 type={} ok={} balance={}", type, st.ok, st.balanceText);
        return out;
    }

    /** batch-start：校验 → 建批次 → executor 提交 → 立即回概要（凭据 masked）。 */
    private static JSONObject batchStart(Map<String, Object> f) {
        RegistrationService svc = requireService();
        PhoneBatchConfig cfg = new PhoneBatchConfig();
        cfg.providerType = providerType(f);
        cfg.providerConfig = providerConfig(f);
        cfg.keyword = str(f, "keyword") == null ? "" : str(f, "keyword").trim();
        cfg.goalCount = intField(f, "goalCount", -1);
        if (cfg.goalCount < GOAL_MIN || cfg.goalCount > GOAL_MAX) {
            throw new IllegalArgumentException("goalCount 必须在 " + GOAL_MIN + "–" + GOAL_MAX
                    + " 之间");
        }
        cfg.maxNumbers = intField(f, "maxNumbers", -1);
        if (cfg.maxNumbers < cfg.goalCount) {
            throw new IllegalArgumentException("maxNumbers 不能小于 goalCount");
        }
        if (cfg.maxNumbers > MAX_NUMBERS_CEILING) {
            throw new IllegalArgumentException("maxNumbers 不能超过 " + MAX_NUMBERS_CEILING);
        }
        cfg.smsWaitSeconds = intField(f, "smsWaitSeconds",
                RegistrationService.DEFAULT_SMS_WAIT_SECONDS);
        if (cfg.smsWaitSeconds <= 0) {
            throw new IllegalArgumentException("smsWaitSeconds 必须为正整数（秒）");
        }
        JSONObject realNameCfg = optJsonObject(f, "realName");
        if (realNameCfg != null) {
            String rnName = realNameCfg.optString("name", "").trim();
            String rnIdCard = realNameCfg.optString("idCard", "").trim();
            if (rnName.isEmpty()) {
                throw new IllegalArgumentException("realName.name 不能为空");
            }
            if (!PassportIds.isValidIdCard(rnIdCard)) {
                throw new IllegalArgumentException("realName.idCard 必须为 18 位");
            }
            cfg.realName = new PhoneBatchConfig.RealNameInfo(rnName, rnIdCard);
        }
        String pwd = str(f, "gamePassword");
        cfg.gamePassword = pwd == null ? "" : pwd;
        if (!cfg.gamePassword.isEmpty() && cfg.gamePassword.length() < 6) {
            throw new IllegalArgumentException("gamePassword 至少 6 位（或留空）");
        }

        RegistrationBatchRunner runner = new RegistrationBatchRunner(cfg, svc,
                RegistrationStore.get() == null ? new RegistrationStore(null)
                        : RegistrationStore.get(),
                new CredentialBox(), BATCH_SLEEPER);
        RUNNERS.put(runner.id, runner);
        EXECUTOR.submit(() -> {
            try {
                runner.run();
            } finally {
                RUNNERS.remove(runner.id);
            }
        });
        FileLog.info("注册批次已启动 id={} goal={} max={} provider={}",
                runner.id, cfg.goalCount, cfg.maxNumbers, cfg.providerType);
        return batchSummary(runner);
    }

    /** batch-status：优先内存 runner 实时视图，批次已移除则回查 store 历史。 */
    private static JSONObject batchStatus(Map<String, Object> f) {
        String batchId = str(f, "batchId");
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 batchId");
        }
        RegistrationBatchRunner runner = RUNNERS.get(batchId.trim());
        JSONObject view = runner != null ? runner.view() : store().find(batchId.trim());
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
        RegistrationBatchRunner runner = RUNNERS.get(batchId.trim());
        if (runner == null) {
            JSONObject historic = store().find(batchId.trim());
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
        out.put("message", "已请求取消，当前号码注册完成后停止");
        return out;
    }

    /** batch-list：最近批次列表摘要（limit 默认 30，上限 100）。 */
    private static JSONObject batchList(Map<String, Object> f) {
        int limit = intField(f, "limit", DEFAULT_LIST_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIST_LIMIT;
        if (limit > 100) limit = 100;
        java.util.List<JSONObject> list = store().list(limit);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject v : list) {
            JSONObject summary = new JSONObject();
            summary.put("id", v.optString("id", ""));
            summary.put("state", v.optString("state", ""));
            summary.put("providerType", v.optString("providerType", ""));
            summary.put("goalCount", v.optInt("goalCount", 0));
            summary.put("maxNumbers", v.optInt("maxNumbers", 0));
            summary.put("successCount", v.optInt("successCount", 0));
            summary.put("failCount", v.optInt("failCount", 0));
            summary.put("attemptCount", v.optInt("attemptCount", 0));
            summary.put("startedAt", v.optLong("startedAt", 0L));
            summary.put("finishedAt", v.optLong("finishedAt", 0L));
            arr.put(summary);
        }
        JSONObject out = new JSONObject();
        out.put("count", arr.length());
        out.put("batches", arr);
        return out;
    }

    // ---------------- 工具 ----------------

    /** 批次概要（batch-start 立即返回）：id/状态/配置摘要，凭据 masked。 */
    private static JSONObject batchSummary(RegistrationBatchRunner runner) {
        JSONObject v = runner.view();
        JSONObject out = new JSONObject();
        out.put("id", v.optString("id"));
        out.put("state", v.optString("state"));
        out.put("providerType", v.optString("providerType"));
        out.put("keyword", v.optString("keyword"));
        out.put("goalCount", v.optInt("goalCount"));
        out.put("maxNumbers", v.optInt("maxNumbers"));
        out.put("smsWaitSeconds", v.optInt("smsWaitSeconds"));
        out.put("realNameEnabled", v.optBoolean("realNameEnabled"));
        out.put("hasGamePassword", v.optBoolean("hasGamePassword"));
        return out;
    }

    private static RegistrationService requireService() {
        RegistrationService svc = service;
        if (svc == null) {
            throw new IllegalStateException("注册服务未安装（服务端未就绪）");
        }
        return svc;
    }

    private static RegistrationStore store() {
        RegistrationStore s = RegistrationStore.get();
        return s == null ? new RegistrationStore(null) : s;
    }

    /** providerType 校验：∈ {d1, haozhuma}（大小写不敏感）。 */
    private static String providerType(Map<String, Object> f) {
        String type = str(f, "providerType");
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少接码平台类型 providerType");
        }
        String t = type.trim().toLowerCase(Locale.ROOT);
        if (!"d1".equals(t) && !"haozhuma".equals(t)) {
            throw new IllegalArgumentException("接码平台类型必须是 d1 或 haozhuma: " + type);
        }
        return t;
    }

    /** providerConfig：JSON 对象字符串字段（HMAC 扁平标量约定），解析失败给中文错误。 */
    private static Map<String, String> providerConfig(Map<String, Object> f) {
        String raw = str(f, "providerConfig");
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少接码平台配置 providerConfig");
        }
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Throwable t) {
            throw new IllegalArgumentException("providerConfig 必须是 JSON 对象字符串");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : o.keySet()) {
            out.put(k, o.optString(k, ""));
        }
        return out;
    }

    /** realName 等可选 JSON 对象字段；缺失/空返回 null。 */
    private static JSONObject optJsonObject(Map<String, Object> f, String key) {
        String raw = str(f, key);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            throw new IllegalArgumentException(key + " 必须是 JSON 对象字符串");
        }
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

    /** 统一错误形态：{error: 中文说明}。 */
    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        out.put("error", message == null ? "错误" : message);
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }

    /** 供单测观察运行中批次表（不暴露 map 本体）。 */
    static boolean hasRunner(String batchId) {
        return RUNNERS.containsKey(batchId);
    }
}
