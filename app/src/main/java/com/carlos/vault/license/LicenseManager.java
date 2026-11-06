package com.carlos.vault.license;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * 授权门面：状态机 + 本地门禁 + 异步校验编排。
 *
 * 设计要点：
 * <ul>
 *   <li><b>门禁纯本地</b>：{@link #gateForLaunch} 只读内存状态 + 本地时钟，绝不联网。
 *       它会在 UI 线程被 {@code VaultEngine.launchGame()} 调用。</li>
 *   <li><b>服务端为权威</b>：到期时间 / 状态由服务端给出，客户端只在离线时用
 *       纠正后的本地时钟做宽限期判定。</li>
 *   <li><b>失败保守拦截</b>：门禁内部任何异常都返回「拒绝」原因，避免异常被用来绕过授权。</li>
 * </ul>
 */
public class LicenseManager {

    /** 异步结果回调。始终在主线程调用。 */
    public interface Callback {
        void onResult(LicenseState state, String message);
    }

    private static volatile LicenseManager sInstance;

    private final Context ctx;
    private final LicenseStore store;
    private final LicenseApi api;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();

    /** 当前票据；可能为 null。 */
    private LicenseTicket ticket;
    /** 票据存在但签名不符。 */
    private boolean tampered;
    /** 是否已完成一次落盘加载。 */
    private boolean loaded;
    /** 最近一次本地判定结果。 */
    private LicenseState state = LicenseState.UNACTIVATED;

    /**
     * 本机加盐指纹缓存（与 {@link #deviceHashCacheSalt} 配对）。
     *
     * 门禁路径 {@link #gateForLaunch} 每次判定都要比对指纹，而 {@link DeviceId#withSalt}
     * 需读 ANDROID_ID（一次 Settings 提供者 IPC，取不到时还会落盘写 UUID）。
     * 盐在一次激活周期内不变，故按盐缓存即可把重复开销降为一次。
     * 访问须持有 {@link #lock}。
     */
    private String deviceHashCache;
    private String deviceHashCacheSalt;

    private volatile long lastVerifyAttemptAt = 0L;
    private volatile boolean verifyInFlight = false;

    private LicenseManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = new LicenseStore(this.ctx);
        this.api = new LicenseApi(this.ctx);
    }

    /** 取单例。 */
    public static LicenseManager get(Context ctx) {
        LicenseManager local = sInstance;
        if (local == null) {
            synchronized (LicenseManager.class) {
                local = sInstance;
                if (local == null) {
                    local = new LicenseManager(ctx);
                    sInstance = local;
                }
            }
        }
        return local;
    }

    // ---------- 加载与判定 ----------

    /** 首次调用时从 SharedPreferences 载入票据；后续为空操作。 */
    public void ensureLoaded() {
        synchronized (lock) {
            if (loaded) return;
            loaded = true;
            LicenseStore.LoadResult r = store.load();
            tampered = r.tampered;
            ticket = r.ticket;
            if (ticket != null) {
                api.setClockOffsetMs(ticket.clockOffsetMs);
            }
            state = computeState();
            Log.i(LicenseConfig.LOG_TAG, "loadPersisted: state=" + state
                    + " tampered=" + tampered + " ticket=" + ticket);
        }
    }

    /** 架构文档中约定的方法名，语义等同 {@link #ensureLoaded()}。 */
    public void loadPersisted() {
        ensureLoaded();
    }

    /** 强制重新从磁盘加载（换码后调用）。 */
    public void reload() {
        synchronized (lock) {
            loaded = false;
        }
        ensureLoaded();
    }

    /**
     * 纯本地重新判定当前状态（无网络、无阻塞）。
     * 判定优先级（D4）：REVOKED &gt; DEVICE_MISMATCH &gt; EXPIRED &gt; OFFLINE_EXPIRED。
     */
    public LicenseState recheckLocalState() {
        synchronized (lock) {
            state = computeState();
            return state;
        }
    }

    private LicenseState computeState() {
        if (tampered) {
            return LicenseState.TAMPERED;
        }
        if (ticket == null || ticket.isEmpty()) {
            return LicenseState.UNACTIVATED;
        }
        // 1) 服务端明确的吊销 —— 最高优先级
        if (LicenseConfig.STATUS_REVOKED.equals(ticket.serverStatus)) {
            return LicenseState.REVOKED;
        }

        // 2) 设备不匹配（D4 第二优先）——两个来源取并集：
        //    (a) 服务端下发的 device_mismatch；
        //    (b) 本地指纹与票据记录不符。服务端拿不到 ANDROID_ID，无法自行复算指纹，
        //        故「换机后搬运旧票据启动」只有本地能判出来，是服务端判定的必要补充。
        if (LicenseConfig.STATUS_DEVICE_MISMATCH.equals(ticket.serverStatus) || isDeviceMismatch()) {
            return LicenseState.DEVICE_MISMATCH;
        }

        long now = correctedNow();

        // 3) 到期判定（D4 第三优先）：服务端 status 权威，或本地时钟已越过到期时间
        if (LicenseConfig.STATUS_EXPIRED.equals(ticket.serverStatus)
                || (!ticket.isPermanent() && ticket.expireAt > 0 && now > ticket.expireAt)) {
            return LicenseState.EXPIRED;
        }

        // 4) 离线宽限判定（D3）：以最后一次联网校验成功为起点
        if (ticket.lastVerifiedAt <= 0) {
            // 从未成功联网校验过（异常情形），保守要求联网
            return LicenseState.OFFLINE_EXPIRED;
        }
        long offlineFor = now - ticket.lastVerifiedAt;
        if (offlineFor > (long) LicenseConfig.GRACE_DAYS * LicenseConfig.MS_PER_DAY) {
            return LicenseState.OFFLINE_EXPIRED;
        }

        // 5) 宽限期内可用；仅按最近校验新鲜度区分展示态
        return offlineFor <= LicenseConfig.VERIFY_THROTTLE_MS
                ? LicenseState.ACTIVE
                : LicenseState.ACTIVE_OFFLINE;
    }

    /**
     * 票据记录的指纹与本机当前指纹是否不符（换机 / 票据被搬运到别的机器）。
     *
     * 盐或票据指纹缺失时无法比对，返回 {@code false} 退回服务端判定，避免误伤正常票据。
     * 调用方须持有 {@link #lock}。
     */
    private boolean isDeviceMismatch() {
        String salt = ticket.salt;
        String recorded = ticket.deviceHash;
        if (salt == null || salt.isEmpty() || recorded == null || recorded.isEmpty()) {
            return false;
        }
        return !recorded.equals(currentDeviceHash(salt));
    }

    /**
     * 计算并缓存当前设备针对指定盐的指纹。
     *
     * 缓存按盐生效：盐在一次激活周期内不变，故只需首次计算，后续直接命中缓存，
     * 使门禁路径（UI 线程）不再重复做 IPC + SHA-256。调用方须持有 {@link #lock}。
     */
    private String currentDeviceHash(String salt) {
        if (deviceHashCache != null && salt.equals(deviceHashCacheSalt)) {
            return deviceHashCache;
        }
        String h = DeviceId.withSalt(ctx, salt);
        deviceHashCacheSalt = salt;
        deviceHashCache = h;
        return h;
    }

    /**
     * 把服务端 status 归一化到已知取值；未知值按 {@code active} 容错
     * （宁可让后续的本地到期/离线判定兜底，也不因未知字符串而放行终态失效）。
     */
    private static String normalizeServerStatus(String status) {
        if (LicenseConfig.STATUS_REVOKED.equals(status)
                || LicenseConfig.STATUS_EXPIRED.equals(status)
                || LicenseConfig.STATUS_DEVICE_MISMATCH.equals(status)) {
            return status;
        }
        return LicenseConfig.STATUS_ACTIVE;
    }

    /** 当前是否可用（门禁与 UI 门禁都用它）。 */
    public boolean localUsable() {
        return recheckLocalState().usable();
    }

    public LicenseState currentState() {
        synchronized (lock) {
            return state;
        }
    }

    public LicenseTicket currentTicket() {
        synchronized (lock) {
            return ticket == null ? null : ticket.copy();
        }
    }

    // ---------- 授权等级（批次7 三级权限；供功能门控使用） ----------

    /**
     * 当前生效的授权等级：{@link LicenseConfig#TIER_BASIC}=1 普通 /
     * {@link LicenseConfig#TIER_MEMBER}=2 会员 / {@link LicenseConfig#TIER_PREMIUM}=3 高级会员。
     *
     * <p>数据来源 = 本地票据（服务端权威：激活/联网校验时落盘）。兼容规则：
     * <ul>
     *   <li><b>旧票据无 tier 字段</b> → {@link LicenseConfig#TIER_DEFAULT}=3（存量用户不降权，兼容旧 HMAC）；</li>
     *   <li><b>无票据/空票据</b> → {@link LicenseConfig#TIER_BASIC}=1（最小权限 fail-safe；
     *       是否已激活请另用 {@link #localUsable()} / {@link #gateForLaunch} 判定，tier 不承担授权判定）；</li>
     *   <li>非法值（0/负/超界）→ 归一化为 3（与服务端缺省一致）。</li>
     * </ul>
     * 只读内存（首次会读一次 SharedPreferences），可在 UI 线程调用。
     */
    public int tier() {
        ensureLoaded();
        LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        if (t == null || t.isEmpty()) {
            return LicenseConfig.TIER_BASIC;
        }
        return LicenseConfig.normalizeTier(t.tier);
    }

    /** 是否达到指定等级（一行门控用；{@code requiredTier} 非法时按 3 处理 → 只有高级会员满足）。 */
    public boolean tierAtLeast(int requiredTier) {
        return tier() >= LicenseConfig.normalizeTier(requiredTier);
    }

    // ---------- 角色与功能矩阵（2026-11-06 管理后台） ----------

    /**
     * 当前角色档位：1=T1 / 2=T2 / 3=T3 / 4=GM（服务端权威）。
     *
     * <p>兼容：旧票据无 role → 默认 3；无票据/空票据 → T1（fail-safe）。
     * 只读内存，可在 UI 线程调用。
     */
    public int role() {
        ensureLoaded();
        LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        if (t == null || t.isEmpty()) {
            return LicenseConfig.TIER_BASIC;
        }
        return LicenseConfig.normalizeRole(t.role);
    }

    /**
     * 服务端是否已下发功能矩阵。
     *
     * <p>{@code false} = 字段缺失（旧服务端/旧票据）→ 调用方回退 FeatureGate 硬编码默认；
     * {@code true} = 以 {@link #features()} 为权威（空列表表示"明确无功能"）。
     */
    public boolean featuresPresent() {
        ensureLoaded();
        synchronized (lock) {
            return ticket != null && !ticket.isEmpty() && ticket.featuresPresent;
        }
    }

    /**
     * 当前角色可见功能 key（已排序）；未下发矩阵时返回 {@code null}（区别于空列表）。
     * 只读内存，可在 UI 线程调用。
     */
    public java.util.List<String> features() {
        ensureLoaded();
        synchronized (lock) {
            if (ticket == null || ticket.isEmpty() || !ticket.featuresPresent) {
                return null;
            }
            // 说明（2026-11-06 代码审查）：服务端 featuresForRole 已按 key 字典序下发，
            // 且 visible() 只做按 key 命中、不依赖顺序；这里返回只读视图，避免 UI 热路径
            // （FeatureGate.visible 在列表渲染中高频调用）反复排序/复制。
            // 需要规范化排序串时请用 LicenseTicket.normalizedFeatures()（签名场景）。
            return java.util.Collections.unmodifiableList(ticket.features);
        }
    }

    // ---------- 硬闸门（被 VaultEngine.launchGame 调用） ----------

    /**
     * 启动闸门：返回 null 表示放行，否则返回给用户看的原因。
     *
     * <b>本方法必须在 UI 线程可安全调用</b>：不联网、不做磁盘 IO（首次会读一次
     * SharedPreferences，之后全部走内存缓存）、不抛异常。
     */
    public static String gateForLaunch(Context ctx) {
        try {
            LicenseManager lm = get(ctx);
            lm.ensureLoaded();
            LicenseState s = lm.recheckLocalState();
            if (s.usable()) {
                return null;
            }
            return reasonFor(s);
        } catch (Throwable t) {
            // 保守拦截：授权判定异常时不允许放行，避免靠制造异常绕过。
            Log.e(LicenseConfig.LOG_TAG, "gateForLaunch 判定异常，按拒绝处理", t);
            return "授权校验异常，请重启应用后重试";
        }
    }

    /** 状态 → 用户可读原因（文案与 PRD 4.2 一致）。 */
    public static String reasonFor(LicenseState s) {
        if (s == null) return "请先使用兑换码激活";
        switch (s) {
            case REVOKED:
                return "授权已被停用，请联系客服";
            case DEVICE_MISMATCH:
                return "设备不匹配，换机请联系客服换绑";
            case EXPIRED:
                return "授权已到期，请续费后继续使用";
            case OFFLINE_EXPIRED:
                return "已超过 " + LicenseConfig.GRACE_DAYS + " 天未联网校验，请联网后重试";
            case TAMPERED:
                return "授权数据异常，请重新输入兑换码";
            case UNACTIVATED:
            default:
                return "请先使用兑换码激活本应用";
        }
    }

    // ---------- 展示 ----------

    /** 剩余天数；-1 表示永久有效；0 表示已到期/无票据。 */
    public int remainDays() {
        LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        if (t == null || t.isEmpty()) return 0;
        if (t.isPermanent()) return -1;
        if (t.expireAt <= 0) return 0;
        long remain = t.expireAt - correctedNow();
        if (remain <= 0) return 0;
        return (int) ((remain + LicenseConfig.MS_PER_DAY - 1) / LicenseConfig.MS_PER_DAY);
    }

    /** 到期日展示串（yyyy-MM-dd）；永久返回空串。 */
    public String expireDateText() {
        LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        if (t == null || t.isPermanent() || t.expireAt <= 0) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date(t.expireAt));
    }

    /** 主界面顶部信息条文案（PRD 4.3）。 */
    public String describeRemain() {
        LicenseState s = recheckLocalState();
        if (!s.usable()) {
            return "● " + s.title();
        }
        LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        if (t != null && t.isPermanent()) {
            return "● 永久有效";
        }
        int days = remainDays();
        if (s == LicenseState.ACTIVE_OFFLINE) {
            return "● 有效期（离线）· 剩余 " + days + " 天";
        }
        return days <= 7
                ? "● 即将到期 · 剩余 " + days + " 天"
                : "● 有效 · 剩余 " + days + " 天";
    }

    // ---------- 激活 ----------

    /**
     * 激活：两阶段调用同一接口。
     * 阶段一 hello 取盐与 challengeId，阶段二 bind 出票。
     */
    public void activateAsync(final String rawCode, final Callback cb) {
        final String code = normalize(rawCode);
        if (code.isEmpty()) {
            callback(cb, LicenseState.UNACTIVATED, "请输入兑换码");
            return;
        }

        api.hello(new LicenseApi.ApiCallback() {
            @Override
            public void onResult(LicenseApi.ApiResult helloResult) {
                if (!helloResult.ok() || helloResult.data == null) {
                    callback(cb, LicenseState.UNACTIVATED,
                            helloResult.networkError ? "网络异常，请重试"
                                    : LicenseConfig.messageFor(helloResult.code));
                    return;
                }
                final String salt = helloResult.data.optString("salt", "");
                final String challengeId = helloResult.data.optString("challengeId", "");
                if (salt.isEmpty() || challengeId.isEmpty()) {
                    callback(cb, LicenseState.UNACTIVATED, "网络异常，请重试");
                    return;
                }
                final String deviceHash = DeviceId.withSalt(ctx, salt);

                api.activate(code, deviceHash, salt, challengeId, DeviceId.label(),
                        new LicenseApi.ApiCallback() {
                            @Override
                            public void onResult(LicenseApi.ApiResult r) {
                                onActivateBindResult(r, code, deviceHash, salt, cb);
                            }
                        });
            }
        });
    }

    private void onActivateBindResult(LicenseApi.ApiResult r, String code,
                                      String deviceHash, String salt, Callback cb) {
        if (r.networkError) {
            callback(cb, currentState(), "网络异常，请重试");
            return;
        }
        if (r.code == LicenseConfig.CODE_CHALLENGE_INVALID) {
            // 盐过期了：外层重试一次握手即可（这里直接再走一遍 activateAsync 的一个周期）。
            callback(cb, LicenseState.UNACTIVATED, "校验超时，请再点一次激活");
            return;
        }
        if (r.code != LicenseConfig.CODE_OK || r.data == null) {
            callback(cb, LicenseState.UNACTIVATED, LicenseConfig.messageFor(r.code));
            return;
        }

        JSONObject t = r.data.optJSONObject("ticket");
        if (t == null) {
            callback(cb, LicenseState.UNACTIVATED, "网络异常，请重试");
            return;
        }

        synchronized (lock) {
            LicenseTicket nt = new LicenseTicket();
            nt.applyServerTicket(t);
            nt.deviceHash = deviceHash;
            nt.salt = salt;
            nt.clockOffsetMs = (r.serverTime > 0) ? (r.serverTime - System.currentTimeMillis()) : 0L;
            nt.lastVerifiedAt = correctedNow(nt.clockOffsetMs);
            nt.serverStatus = LicenseConfig.STATUS_ACTIVE;

            ticket = nt;
            tampered = false;
            store.save(nt);
            api.setClockOffsetMs(nt.clockOffsetMs);
            state = computeState();
        }

        Log.i(LicenseConfig.LOG_TAG, "激活成功: " + state);
        callback(cb, state, "激活成功，有效期至 "
                + (expireDateText().isEmpty() ? "永久" : expireDateText()));
    }

    // ---------- 校验 ----------

    /** 强制校验（忽略节流）。 */
    public void verifyAsync(Callback cb) {
        verifyAsyncInternal(true, cb);
    }

    /** 节流校验：距上次尝试不足 {@link LicenseConfig#VERIFY_THROTTLE_MS} 则跳过（cb 立即回调当前态）。 */
    public void verifyThrottled(Callback cb) {
        verifyAsyncInternal(false, cb);
    }

    private void verifyAsyncInternal(boolean force, final Callback cb) {
        ensureLoaded();

        final LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        if (t == null || t.isEmpty()) {
            callback(cb, LicenseState.UNACTIVATED, "尚未激活");
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && (now - lastVerifyAttemptAt) < LicenseConfig.VERIFY_THROTTLE_MS) {
            callback(cb, currentState(), "节流跳过");
            return;
        }
        if (verifyInFlight) {
            callback(cb, currentState(), "已有校验进行中");
            return;
        }
        verifyInFlight = true;
        lastVerifyAttemptAt = now;

        api.verify(t.code, t.ticketId, t.deviceHash, new LicenseApi.ApiCallback() {
            @Override
            public void onResult(LicenseApi.ApiResult r) {
                verifyInFlight = false;
                onVerifyResult(r, cb);
            }
        });
    }

    private void onVerifyResult(LicenseApi.ApiResult r, Callback cb) {
        // 网络失败：不改状态，只按本地规则重算（可能进入 ACTIVE_OFFLINE / OFFLINE_EXPIRED）。
        if (r.networkError) {
            LicenseState s = recheckLocalState();
            callback(cb, s, s.usable() ? "离线使用中" : reasonFor(s));
            return;
        }

        JSONObject data = r.data;
        String status = (data == null) ? "" : data.optString("status", "");

        // 码已失效 / 尚未激活：清掉本地票据。
        // 契约 v1.1 起，服务端「未激活」只回 code=1007，不再下发 not_activated status；
        // 下方对 STATUS_NOT_ACTIVATED 的比较仅为历史兜底，保持 fail-closed。
        boolean codeGone = r.code == LicenseConfig.CODE_NOT_FOUND
                || r.code == LicenseConfig.CODE_FORMAT_INVALID
                || r.code == LicenseConfig.CODE_NOT_ACTIVATED;
        if (codeGone || LicenseConfig.STATUS_NOT_ACTIVATED.equals(status)) {
            synchronized (lock) {
                store.clear();
                ticket = null;
                tampered = false;
                state = LicenseState.UNACTIVATED;
            }
            callback(cb, LicenseState.UNACTIVATED,
                    LicenseConfig.messageFor(codeGone ? r.code : LicenseConfig.CODE_NOT_ACTIVATED));
            return;
        }

        // 服务端在**失败响应**里同样携带 data.status（见服务端 writeVerifyErr：
        // REVOKED/DEVICE_MISMATCH 走非 0 code 但带 status）。因此这里不能只在 code==0
        // 时才读 status，否则 device_mismatch / expired 无法落盘。只要拿到 status
        // 就以它为权威写入本地，再由 computeState() 按 D4 优先级统一映射。
        if (!status.isEmpty()) {
            long expireAt = data.optLong("expireAt", 0L);
            String type = data.optString("type", "");
            // 批次7：verify 响应新增 tier（1/2/3）。旧服务端无该字段 → 不改本地等级（旧票据默认 3）。
            boolean tierPresent = data.has("tier");
            int tier = data.optInt("tier", LicenseConfig.TIER_DEFAULT);
            // 2026-11-06：role/features 与 tier 同一口径——服务端下发才覆盖，缺失保持原值。
            LicenseState s = applyVerifySuccess(status, type, expireAt, r.serverTime, tier, tierPresent, data);
            callback(cb, s, s.usable() ? "校验成功" : reasonFor(s));
            return;
        }

        // 无 status（纯协议/网络层问题）：不改状态，按本地规则重算。
        LicenseState s = recheckLocalState();
        callback(cb, s, LicenseConfig.messageFor(r.code));
    }

    /**
     * 把一次成功的 verify 结果写入本地票据。
     *
     * 关键：{@code status} 必须**原样持久化**四种服务端取值
     * （active / expired / revoked / device_mismatch），不得折叠成 active ——
     * 早先把非 revoked 一律写成 active，导致 DEVICE_MISMATCH / EXPIRED 只在本次内存态
     * 生效、落盘即丢，重启后「意外复活」。现由 {@link #computeState()} 按 D4 优先级映射。
     */
    private LicenseState applyVerifySuccess(String status, String type, long expireAt, long serverTime,
                                            int tier, boolean tierPresent, JSONObject data) {
        synchronized (lock) {
            if (ticket == null) return LicenseState.UNACTIVATED;
            if (type != null && !type.isEmpty()) {
                ticket.type = type;
            }
            // 批次7：服务端下发 tier 才覆盖（旧服务端/旧票据保持原值；非法值归一化为 3）。
            if (tierPresent) {
                ticket.tier = LicenseConfig.normalizeTier(tier);
            }
            // 2026-11-06：role/features 下发才覆盖（缺失保持旧值，避免旧服务端清空矩阵）。
            ticket.applyServerRoleFeatures(data);
            if (!LicenseConfig.TYPE_PERMANENT.equals(ticket.type)) {
                ticket.expireAt = expireAt;
            }
            ticket.serverStatus = normalizeServerStatus(status);
            if (serverTime > 0) {
                ticket.clockOffsetMs = serverTime - System.currentTimeMillis();
                api.setClockOffsetMs(ticket.clockOffsetMs);
            }
            ticket.lastVerifiedAt = correctedNow(ticket.clockOffsetMs);
            store.save(ticket);
            state = computeState();
            return state;
        }
    }

    // ---------- 换绑 ----------

    /** 换绑到新设备（需运营密钥）。调用方通常是客服/排障，不在常规 UI 流程内。 */
    public void rebindAsync(final String rawCode, final String newDeviceHash,
                            final String adminToken, Callback cb) {
        final String code = normalize(rawCode);
        if (code.isEmpty() || newDeviceHash == null || newDeviceHash.isEmpty()) {
            callback(cb, currentState(), "参数不完整");
            return;
        }
        api.rebind(code, newDeviceHash, adminToken, DeviceId.label(), "换机",
                new LicenseApi.ApiCallback() {
                    @Override
                    public void onResult(LicenseApi.ApiResult r) {
                        if (r.networkError) {
                            callback(cb, currentState(), "网络异常，请重试");
                            return;
                        }
                        if (r.code != LicenseConfig.CODE_OK) {
                            callback(cb, currentState(), LicenseConfig.messageFor(r.code));
                            return;
                        }
                        // 换绑后本机票据即失效，需要重新激活。
                        synchronized (lock) {
                            store.clear();
                            ticket = null;
                            tampered = false;
                            state = LicenseState.UNACTIVATED;
                        }
                        callback(cb, LicenseState.UNACTIVATED, "已换绑，请在新设备上重新激活");
                    }
                });
    }

    // ---------- 其他 ----------

    /** 清空本地票据（换码/重置）。 */
    public void clearTicket() {
        synchronized (lock) {
            store.clear();
            ticket = null;
            tampered = false;
            loaded = true;
            state = LicenseState.UNACTIVATED;
        }
    }

    /** 归一化兑换码：去分隔符、转大写。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 'a' && c <= 'z') sb.append((char) (c - 32));
            else if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** 把 16 位规范码格式化为 XXXX-XXXX-XXXX-XXXX（供展示）。 */
    public static String formatCode(String normalized) {
        if (normalized == null || normalized.length() != 16) return normalized == null ? "" : normalized;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(normalized.charAt(i));
        }
        return sb.toString();
    }

    private long correctedNow() {
        LicenseTicket t;
        synchronized (lock) {
            t = ticket;
        }
        return correctedNow(t == null ? 0L : t.clockOffsetMs);
    }

    private static long correctedNow(long offsetMs) {
        return System.currentTimeMillis() + offsetMs;
    }

    /** 回调统一切回主线程，避免 UI 层直接操作视图时踩线程问题。 */
    private void callback(final Callback cb, final LicenseState s, final String message) {
        if (cb == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onResult(s, message);
                } catch (Throwable t) {
                    Log.w(LicenseConfig.LOG_TAG, "回调异常", t);
                }
            }
        });
    }
}
