package com.carlos.vault.wjdr;

import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vault.wjdr.protocol.GameProtocolClient;
import com.carlos.vault.wjdr.protocol.PortableSprotoCodec;
import com.carlos.vault.wjdr.protocol.SprotoSchemaParser;

import java.net.Proxy;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 游戏协议引擎（平台中立，App 与 vault-server 共用）：注入 AssetSource 提供 sproto schema，
 * 直连游戏服登录。移植自 wjdr-tool 反编译实现，登录参数/签名算法与之一致。
 */
public class WjdrEngine {

    public static final String GAME_HOST = "43.144.130.216";
    public static final int GAME_PORT = 30101;

    private final AssetSource assets;
    private GameProtocolClient client;
    private final SecureRandom random = new SecureRandom();
    /** 出口代理（vault-server 静态住宅 IP 用；App 不设即直连）。 */
    private Proxy proxy;

    public WjdrEngine(AssetSource assets) {
        this.assets = assets;
    }

    /** 会话出口走 SOCKS5/HTTP 代理（须在 initialize 前设置）。 */
    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    /** 从 assets 加载协议 schema 并初始化连接器（schema 字节与解析结果均为进程级缓存，多会话共享） */
    public void initialize() throws Exception {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Throwable ignored) {
            }
        }
        sessionEpoch.incrementAndGet();      // 防封：新连接周期，作废旧的风控检测调度
        clearResourceUpdate();               // 新连接周期：丢弃上一会话未消费的资源增量（R-05）
        dun163RiskCheckPerformed.set(false); // 新的在线周期允许重做风控检测
        GameProtocolClient.Config config = new GameProtocolClient.Config();
        config.gameHost = GAME_HOST;
        config.gamePort = GAME_PORT;
        config.proxy = proxy;
        client = new GameProtocolClient(config,
                new PortableSprotoCodec(new SprotoSchemaParser()));
        client.initialize(c2sAssetBytes(), s2cAssetBytes());
        hookNotifies();   // 登录前挂钩：服务器在登录过程中就会推送初始化数据（建筑/科技/背包等）
    }

    /** schema 字节进程级缓存：多会话只读一次 assets（schema 解析结果另在 codec 层共享）。 */
    private static volatile byte[] sharedC2sBytes;
    private static volatile byte[] sharedS2cBytes;

    private byte[] c2sAssetBytes() throws Exception {
        if (sharedC2sBytes == null) {
            synchronized (WjdrEngine.class) {
                if (sharedC2sBytes == null) sharedC2sBytes = readAsset("wjdr/game_proto_c2s.spb");
            }
        }
        return sharedC2sBytes;
    }

    private byte[] s2cAssetBytes() throws Exception {
        if (sharedS2cBytes == null) {
            synchronized (WjdrEngine.class) {
                if (sharedS2cBytes == null) sharedS2cBytes = readAsset("wjdr/game_proto_s2c.spb");
            }
        }
        return sharedS2cBytes;
    }

    private byte[] readAsset(String name) throws Exception {
        byte[] bytes = assets.read(name);
        return bytes != null ? bytes : new byte[0];
    }

    /**
     * 注入账号固定设备指纹（AccountStore.settings / roleIdentitySeed）。
     *
     * <p>空串/null/非 JSON 视为"无固定指纹"并清空旧 pinned（P2-4：引擎复用时不得串号）。
     * 只固化 JSON 中真实存在且合法的字段；非法/缺失字段不 pin，交给 {@link #buildLoginPayload}
     * 按 (fpid, serverid, roleUid) 确定性生成，避免登录前拿不到 fpid 时把随机值固化（P2-1）。
     */
    public void applyIdentity(String settingsJson) {
        if (settingsJson == null || settingsJson.isEmpty()) {
            clearPinnedIdentity();
            return;
        }
        String fpid = savedFpid != null ? savedFpid : (lastFpid != null ? lastFpid : "");
        org.json.JSONObject o;
        try {
            o = new org.json.JSONObject(settingsJson);
        } catch (Throwable t) {
            clearPinnedIdentity();
            return;
        }
        DeviceProfile.Device d = DeviceProfile.parse(settingsJson, fpid);
        if (d == null) {
            clearPinnedIdentity();
            return;
        }
        boolean modelFromJson = o.has("device")
                && DeviceProfile.normalizeModel(o.optString("device", "")) != null;
        pinnedDeviceModel = modelFromJson ? d.model : null;
        pinnedManufacturer = modelFromJson ? d.manufacturer : null;
        pinnedBrand = modelFromJson ? d.brand : null;
        pinnedAndroidVersion = modelFromJson ? d.androidVersion : 0;
        pinnedDeviceLv = d.deviceLv;
        pinnedDistinctId = o.has("distinct_id")
                ? DeviceProfile.normalizeDeviceId(o.optString("distinct_id", "")) : null;
        pinnedDeviceId = o.has("device_id")
                ? DeviceProfile.normalizeDeviceId(o.optString("device_id", "")) : null;
        pinnedLoginIp = o.has("ip") ? DeviceProfile.normalizeIp(o.optString("ip", "")) : null;
    }

    /** 清空固定指纹（空注入/非 JSON 时调用，保证引擎复用不同账号不串号）。 */
    private void clearPinnedIdentity() {
        pinnedDistinctId = null;
        pinnedDeviceId = null;
        pinnedLoginIp = null;
        pinnedDeviceModel = null;
        pinnedManufacturer = null;
        pinnedBrand = null;
        pinnedAndroidVersion = 0;
        pinnedDeviceLv = 0;
    }

    /** 导出本次登录实际使用的设备指纹（v2 JSON，回写 AccountStore；下次登录复用同一"设备"）。 */
    public String identityJson() {
        if (lastDistinctId == null || lastDeviceId == null || lastLoginIp == null) return "";
        DeviceProfile.Device d = new DeviceProfile.Device(
                lastDeviceModel, lastManufacturer, lastBrand, lastAndroidVersion,
                lastDeviceLv, lastDeviceId, lastDistinctId, lastLoginIp);
        return DeviceProfile.toJson(d);
    }

    /** 登录结果 */
    public static class LoginResult {
        public long uid;
        public long serverId;
        public String gameName;
        public Map<String, Object> loadSummary;
        /** loginauth 原文（选角研究/身份核验用；平时可忽略）。 */
        public Map<String, Object> authContent;
        /** 角色元数据（loaddata/players 双源提取；取不到保持零值，登记侧不冲掉旧值）。 */
        public long level;            // 熔炉等级
        public long power;            // 战力
        public String alliance = "";  // 联盟名
    }

    private long lastUid;
    private String lastFpid;
    private long lastServerId;
    /** 游戏服务器时钟（P0）：登录/心跳响应校准，单调外推；waitUntil 供行军类毫秒到点用。 */
    private final GameServerClock serverClock = new GameServerClock();
    /** 账号固定设备指纹（AccountStore.settings 注入；未注入时按 fpid+serverid 确定性生成并经 identityJson 固化）。 */
    private String pinnedDistinctId;
    private String pinnedDeviceId;
    private String pinnedLoginIp;
    /** 机型池/厂商/系统版本元数据统一由 {@link DeviceProfile} 维护（2026-11-11 从本类迁出）。 */
    private String pinnedDeviceModel;
    private String pinnedManufacturer;
    private String pinnedBrand;
    private int pinnedAndroidVersion;
    private int pinnedDeviceLv;
    private String lastDistinctId;
    private String lastDeviceId;
    private String lastLoginIp;
    private String lastDeviceModel;
    private String lastManufacturer;
    private String lastBrand;
    private int lastAndroidVersion;
    private int lastDeviceLv;
    /** 发现式登录（无角色 uid）的临时设备：每引擎一台、重试稳定、不落库（P1-1）。 */
    private DeviceProfile.Device ephemeralDevice;
    /** 游戏服务端推送通知收集（proto → 最近一次内容），任务层用于提取活动 id 等 */
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> notifies =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 资源增量累积缓冲（R-05）：resourceId/itemId → 自上次消费以来的累计 delta。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> pendingResUpdate =
            new java.util.concurrent.ConcurrentHashMap<>();
    private AutoCloseable notifyHook;

    // ================= 常驻会话支持（对标原版 GameAccountSessionService：心跳/恢复/顶号） =================

    public static final String STATE_OFFLINE = "offline";
    public static final String STATE_LOGGING = "logging";
    public static final String STATE_ONLINE = "online";
    public static final String STATE_RECOVERING = "recovering";
    public static final String STATE_TAKEOVER = "takeover";     // 被顶号（code=5），人玩优先暂停
    public static final String STATE_NEED_TOKEN = "need_token"; // 凭证失效（code=26），待人工重导 token

    /** 心跳参数照抄原版 heartbeatDue：空闲≥3s 或 距上次心跳≥60s 才发；1 秒扫描由会话保活方驱动。 */
    static final long HEARTBEAT_IDLE_MILLIS = 3000L;
    static final long HEARTBEAT_MAX_INTERVAL_MILLIS = 60000L;
    /** 恢复退避照抄原版：8s×2^n 封顶 300s，±10% 抖动。 */
    static final long RECOVERY_BACKOFF_BASE_MILLIS = 8000L;
    static final long RECOVERY_BACKOFF_MAX_MILLIS = 300000L;
    static final int RECOVERY_BACKOFF_SHIFT_CAP = 6;
    /** 被顶号（code=5）默认"人玩优先"：30 分钟后探测回连（原版为按模块策略延迟重登抢回）。 */
    static final long TAKEOVER_PROBE_MILLIS = 30 * 60_000L;

    /** 会话级请求串行锁（对标原版 requestLock）：互助响应/任务执行/wake 补领共用在线会话时排队，防交叉。 */
    private final java.util.concurrent.locks.ReentrantLock requestLock =
            new java.util.concurrent.locks.ReentrantLock(true);
    /** 最近一次 request 等待 requestLock 的毫秒数（-1=尚未发过请求；R0-1 观测用）。 */
    private volatile long lastRequestLockWaitMs = -1L;
    private final java.util.concurrent.atomic.AtomicBoolean heartbeatInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 心跳计数（灰度观察：每 20 次打一条存活日志）。 */
    private final java.util.concurrent.atomic.AtomicLong heartbeatCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean reconnectInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 原样消息钩子（proto, content）：互助响应等常驻监听用，与 notifies 收集并行分发。 */
    private final java.util.concurrent.CopyOnWriteArrayList<
            java.util.function.BiConsumer<String, Map<String, Object>>> messageHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String sessionState = STATE_OFFLINE;
    private volatile long lastActivityAt;
    private volatile long lastHeartbeatSentAt;
    private volatile String savedFpid;
    private volatile String savedToken;
    private volatile Long savedServerid;
    /** 选角参数（loginauth uid=角色uid）：断线重连重放时必须一并带上，否则退回默认角色。 */
    private volatile Map<String, Object> savedExtraParams;
    private volatile int backoffShift;
    private volatile long reconnectAtMillis;
    private volatile long takeoverProbeAtMillis;
    private volatile String lastFailure;
    private volatile java.util.function.Consumer<String> stateListener;

    // ================= 防封登录（默认链路 2026-11-05；对齐线上 GameAccountSessionService.Antiban） =================

    /** 会话代次（每次 initialize 递增）：作废旧调度里过期在线周期的风控检测任务。 */
    private final java.util.concurrent.atomic.AtomicLong sessionEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    /** 本次在线周期是否已发过易盾风控检测（一次性闸门）。 */
    private final java.util.concurrent.atomic.AtomicBoolean dun163RiskCheckPerformed =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 风控检测延迟（online 后 10 秒，对齐线上 scheduleAntibanRiskCheck）。 */
    static final long ANTIBAN_RISK_CHECK_DELAY_SECONDS = 10L;
    /** 共享守护调度器（异步风控检测不阻塞登录；任务内校验在线/代次，进程退出随守护线程消亡）。 */
    private static volatile java.util.concurrent.ScheduledExecutorService antibanScheduler;

    private static java.util.concurrent.ScheduledExecutorService scheduler() {
        java.util.concurrent.ScheduledExecutorService s = antibanScheduler;
        if (s == null) {
            synchronized (WjdrEngine.class) {
                if (antibanScheduler == null) {
                    antibanScheduler = java.util.concurrent.Executors
                            .newSingleThreadScheduledExecutor(r -> {
                                Thread t = new Thread(r, "wjdr-antiban");
                                t.setDaemon(true);
                                return t;
                            });
                }
                s = antibanScheduler;
            }
        }
        return s;
    }

    /**
     * req_settings_set_device 载荷（对齐线上 sendSettingsSetDevice）：
     * params=[platform, deviceModel, "13", qualityLevel]；platform："android"/"2"→"2" 否则 "1"。
     * 供登录流程与单测共用。
     */
    static java.util.List<String> buildDeviceParams(String deviceOs, String deviceModel,
                                                    Object deviceLv) {
        return buildDeviceParams(deviceOs, deviceModel, null, deviceLv);
    }

    /**
     * 同上，新增 {@code androidVersion} 入参（供调用方校验与未来切换）；
     * 协议第 3 参数按需求 A2 仍固定 {@code "13"}，不随 androidVersion 变化。
     */
    static java.util.List<String> buildDeviceParams(String deviceOs, String deviceModel,
                                                    Object androidVersion, Object deviceLv) {
        String os = deviceOs == null ? "" : deviceOs.trim();
        String platform = "android".equalsIgnoreCase(os) || "2".equals(os) ? "2" : "1";
        String model = deviceModel == null ? "" : deviceModel.trim();
        if (model.isEmpty()) model = "meizu MEIZU 18 Pro";
        // A2：第 3 参数（系统版本）语义未确认，固定 "13"；androidVersion 仅由调用方保证合法，
        // 不改变协议格式（若后续确认该参数就是系统版本，再切换为 androidVersion）。
        String quality = String.valueOf(normalizeDeviceLv(deviceLv));
        // Java 8 兼容（List.of 为 Java 9+，App 源级不可用）
        return java.util.Arrays.asList(platform, model, "13", quality);
    }

    /** device_lv 合法区间 1–5，缺失/越界回退 3（需求 FR-3）。 */
    private static int normalizeDeviceLv(Object deviceLv) {
        if (deviceLv == null) return 3;
        int v;
        if (deviceLv instanceof Number) {
            v = ((Number) deviceLv).intValue();
        } else {
            try {
                v = Integer.parseInt(String.valueOf(deviceLv).trim());
            } catch (Throwable t) {
                return 3;
            }
        }
        return v >= 1 && v <= 5 ? v : 3;
    }

    /** 登录流程内调用（req_loaddata 成功后、online 前）：上报设备环境；失败只记日志不阻断。 */
    private void sendSettingsSetDevice(long uid) {
        try {
            java.util.List<String> params = buildDeviceParams("android", lastDeviceModel,
                    lastAndroidVersion, lastDeviceLv);
            GameMessage resp = lockedRequest("req_settings_set_device",
                    mapOf("params", (Object) params), seconds(15));
            long code = resp == null || resp.getContent() == null
                    ? -1L : toLong(resp.getContent().get("code"));
            WjdrLog.i("[防封保护] 设备环境上报完成 fid={} code={} params={}",
                    String.valueOf(uid), String.valueOf(code), params);
        } catch (Exception e) {
            WjdrLog.w("[防封保护] 设备环境上报异常（已忽略，不阻断登录） fid={} message={}",
                    String.valueOf(uid), e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    /** online 后 10 秒异步易盾风控检测（本次在线仅一次；失败只记日志不影响运行）。 */
    private void scheduleAntibanRiskCheck(long delaySeconds) {
        if (!dun163RiskCheckPerformed.compareAndSet(false, true)) return;
        long epoch = sessionEpoch.get();
        long uid = lastUid;
        try {
            scheduler().schedule(() -> {
                try {
                    if (!STATE_ONLINE.equals(sessionState)
                            || sessionEpoch.get() != epoch || client == null) {
                        return;   // 会话已离线/已重建：本次在线周期作废
                    }
                    String token = HtProtectTokenSupport.rewrapToken();
                    GameMessage resp;
                    requestLock.lock();
                    try {
                        resp = lockedRequest("req_dun163_risk_check",
                                mapOf("token", token), java.time.Duration.ofSeconds(15));
                    } finally {
                        requestLock.unlock();
                    }
                    long code = resp == null || resp.getContent() == null
                            ? -1L : toLong(resp.getContent().get("code"));
                    Object sdkResp = resp == null || resp.getContent() == null
                            ? null : resp.getContent().get("sdk_resp_data");
                    int sdkRespLen = sdkResp instanceof String ? ((String) sdkResp).length() : 0;
                    WjdrLog.i("[防封保护] 易盾安全风控检测完成 fid={} code={} sdkRespLen={}",
                            String.valueOf(uid), String.valueOf(code), String.valueOf(sdkRespLen));
                } catch (Exception e) {
                    WjdrLog.w("[防封保护] 易盾安全风控检测异常（已忽略，不影响运行） fid={} message={}",
                            String.valueOf(uid),
                            e.getMessage() == null ? String.valueOf(e) : e.getMessage());
                }
            }, Math.max(0, delaySeconds), TimeUnit.SECONDS);
        } catch (Throwable t) {
            dun163RiskCheckPerformed.set(false);   // 调度失败允许下次补做
            WjdrLog.w("[防封保护] 风控检测调度失败（已忽略） fid={} message={}",
                    String.valueOf(uid), String.valueOf(t));
        }
    }

    /**
     * 用 号库账号（fpid + sdkToken）直连游戏登录。
     * 登录载荷与 wjdr-tool loginauth 一致：profile 缺省值 + ts + sign。
     */
    public LoginResult login(String fpid, String sdkToken) throws Exception {
        return login(fpid, sdkToken, null);
    }

    /**
     * 按角色登录（2026-10-09 实证）：loginauth 的选角参数是 <b>uid（角色 uid）</b>——
     * serverid 参数对角色绑定无效（实测被忽略，恒落「最近角色」）。
     * 绑定结果以 auth 返回的 uid 为准，调用方应校验 {@link LoginResult#uid} == roleUid。
     *
     * <p>与原版 ConfigJsonLoader 对齐：原版 account map 同时携带 uid 与 server_id
     * （映射为协议字段 serverid）；这里把 serverid 也一并放进载荷。服务器仍以 uid 选角，
     * 但载荷形态与原版一致，便于日志/服务端校验对齐。
     */
    public LoginResult loginByRole(String fpid, String sdkToken, long roleUid, Long serverid) throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("uid", roleUid);
        return login(fpid, sdkToken, serverid, extra);
    }

    /** 兼容旧调用：只钉扎 uid，不额外带 serverid。 */
    public LoginResult loginByRole(String fpid, String sdkToken, long roleUid) throws Exception {
        return loginByRole(fpid, sdkToken, roleUid, null);
    }

    /**
     * 角色清单 → 目标区服(kid)对应的角色 uid；未登录到目标区服角色返回 null。
     * 需在会话在线状态下调用（req_role_list 复用当前连接）。
     */
    public Long discoverRoleUid(long kid) {
        try {
            GameMessage rl = request("req_role_list", null);
            Object list = rl == null || rl.getContent() == null ? null : rl.getContent().get("list");
            if (!(list instanceof List)) return null;
            for (Object o : (List<?>) list) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                if (toLong(m.get("kid")) == kid) {
                    long uid = toLong(m.get("uid"));
                    return uid > 0 ? uid : null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 指定区服登录（同一凭证下的角色切换；serverid=null 由服务端选默认角色）。 */
    public LoginResult login(String fpid, String sdkToken, Long serverid) throws Exception {
        return login(fpid, sdkToken, serverid, null);
    }

    /**
     * 选角登录实验/实现入口：extraParams 在签名前并入 loginauth 载荷
     * （如 kid / uid / role_uid 等选角候选参数）。auth 原文存 {@link LoginResult#authContent}
     * 供上层判定实际绑定的角色（游戏按「最近角色」兜底，选角参数无效时会静默落错角色）。
     */
    public LoginResult login(String fpid, String sdkToken, Long serverid,
                             Map<String, Object> extraParams) throws Exception {
        this.savedFpid = fpid;
        this.savedToken = sdkToken;
        this.savedServerid = serverid;
        this.savedExtraParams = extraParams == null ? null
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extraParams));
        setSessionState(STATE_LOGGING);
        try {
            requestLock.lock();
            try {
                // 角色维度设备 seed（P0）：loginByRole 的 uid 经 extraParams 下传，
                // 同账号同服多角色在无角色级指纹时也不会撞设备。
                long roleUid = roleUidFrom(extraParams);
                Map<String, Object> payload = buildLoginPayload(fpid, sdkToken, serverid, roleUid);
                if (extraParams != null) payload.putAll(extraParams);
                long t0 = GameServerClock.monoNow();
                GameMessage auth = lockedRequest("loginauth", payload, seconds(15));
                long t1 = GameServerClock.monoNow();
                validate(auth, "loginauth");
                serverClock.calibrate(auth.getContent(), t0, t1, System.currentTimeMillis() / 1000L);
                LoginResult result = new LoginResult();
                result.authContent = auth.getContent();
                result.uid = toLong(auth.getContent().get("uid"));
                result.serverId = toLong(auth.getContent().get("serverid"));
                this.lastUid = result.uid;
                this.lastFpid = fpid;
                // 落地真值优先（防串区）：请求的 serverid 会被服务器忽略（恒落最近角色），
                // lastServerId 必须反映实际落地的区服——下游 FnParams/状态写入按它分键
                this.lastServerId = result.serverId > 0 ? result.serverId
                        : (serverid != null ? serverid : 0);
                // 钉扎校验：登录载荷带 uid（loginByRole/重连重放）时，落地 uid 必须一致；
                // 不一致=落错角色，立即失败（外层 catch → handleLoginFailure 进退避，不在线、不假报身份）
                Object pinUid = extraParams == null ? null : extraParams.get("uid");
                long pin = pinUid == null ? 0 : toLong(pinUid);
                if (pin > 0 && result.uid != pin) {
                    throw new IllegalStateException(
                            "落错角色：钉扎 fid=" + pin + " 实际 fid=" + result.uid
                                    + "（serverid=" + result.serverId + "）");
                }

                long t2 = GameServerClock.monoNow();
                GameMessage load = lockedRequest("req_loaddata", new LinkedHashMap<String, Object>(), seconds(20));
                long t3 = GameServerClock.monoNow();
                validate(load, "req_loaddata");
                serverClock.calibrate(load.getContent(), t2, t3, System.currentTimeMillis() / 1000L);
                result.loadSummary = summarize(load.getContent());
                // 角色元数据源1：loaddata 顶层/浅层字段（字段名形状防御式深找）
                result.level = toLong(load.getContent().get("level"));
                if (result.level <= 0) result.level = deepFindNum(load.getContent(), 0, "level", "stove_lv", "furnace_lv", "lv");
                result.power = deepFindNum(load.getContent(), 0, "power", "fight_num", "fight_capacity");
                result.alliance = extractAllianceName(load.getContent());
                // 防封登录（默认链路）：loaddata 成功后、online 前上报设备环境（失败不阻断登录）
                sendSettingsSetDevice(result.uid);

                GameMessage info = lockedRequest("req_players_base_info",
                        mapOf("uids", (Object) java.util.Collections.singletonList(result.uid)), seconds(15));
                validate(info, "req_players_base_info");
                result.gameName = extractGameName(info.getContent(), result.uid);
                // 角色元数据源2：players 列表内本角色的条目（与 gameName 同一匹配规则）
                extractPlayerMeta(info.getContent(), result.uid, result);
                markOnline();
                // 防封登录（默认链路）：online 后 10 秒异步易盾风控检测（本次在线仅一次，不阻塞登录）
                scheduleAntibanRiskCheck(ANTIBAN_RISK_CHECK_DELAY_SECONDS);
                return result;
            } finally {
                requestLock.unlock();
            }
        } catch (Exception e) {
            handleLoginFailure(e);
            throw e;
        }
    }

    /** 登录失败分类：凭证类停机待人工，其余进指数退避自动重试（原版 markFailed/scheduleRecoveryFailure 同款）。 */
    private void handleLoginFailure(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (msg.contains("code=26")) {
            try {
                if (client != null) client.disconnect();
            } catch (Throwable ignored) {
            }
            lastFailure = "角色已被删除/凭证失效，需重新导出 token";
            setSessionState(STATE_NEED_TOKEN);
            return;
        }
        beginRecovery(msg);
    }

    private void markOnline() {
        backoffShift = 0;
        lastFailure = null;
        lastActivityAt = System.currentTimeMillis();
        setSessionState(STATE_ONLINE);
    }

    /** 断线进入指数退避恢复：8s×2^n 封顶 300s，±10% 抖动（原版同款）。 */
    void beginRecovery(String reason) {
        try {
            if (client != null) client.disconnect();
        } catch (Throwable ignored) {
        }
        long shift = Math.min(backoffShift, RECOVERY_BACKOFF_SHIFT_CAP);
        long backoff = Math.min(RECOVERY_BACKOFF_BASE_MILLIS << shift, RECOVERY_BACKOFF_MAX_MILLIS);
        backoff = (long) (backoff * (0.9 + random.nextDouble() * 0.2));
        backoffShift++;
        reconnectAtMillis = System.currentTimeMillis() + backoff;
        lastFailure = reason;
        setSessionState(STATE_RECOVERING);
    }

    private void beginRecovery(Throwable t) {
        beginRecovery(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    /** 立即触发重连（顶号探测到期/人工恢复）：清空退避即刻排队重登。 */
    public void beginRecoveryNow(String reason) {
        backoffShift = 0;
        reconnectAtMillis = 0;
        beginRecovery(reason);
    }

    /** 被踢类冲突冷却（原版 protocol-kick-cooldown-millis 默认 60s）。 */
    static final long KICK_COOLDOWN_MILLIS = 60_000L;

    /** 被踢冲突版恢复：沿用指数退避的计数与状态机，但下次重连至少冷却 60s（防互踢抖动环）。 */
    void beginKickCooldown(String reason) {
        beginRecovery(reason);
        long earliest = System.currentTimeMillis() + KICK_COOLDOWN_MILLIS;
        if (reconnectAtMillis < earliest) reconnectAtMillis = earliest;
    }

    /** 到点重登（keeper 调用；重放完整登录序列）。返回 true=已恢复在线。 */
    public boolean tryReconnect() {
        if (!STATE_RECOVERING.equals(sessionState)) return false;
        if (System.currentTimeMillis() < reconnectAtMillis) return false;
        if (!reconnectInFlight.compareAndSet(false, true)) return false;
        try {
            initialize();
            login(savedFpid, savedToken, savedServerid, savedExtraParams);
            return true;
        } catch (Throwable t) {
            return false;   // 失败路径已排好下一次退避（凭证失效则停在 need_token）
        } finally {
            reconnectInFlight.set(false);
        }
    }

    /** notify_logout 码表（原版 GameAccountSessionService.handleLogout 同款分类）。 */
    private void handleLogoutPush(Map<String, Object> content) {
        long code = toLong(content == null ? null : content.get("code"));
        if (code == 5) {
            // 被顶号（多为本机游戏客户端登录同一号）：按用户策略处理（默认人玩优先）
            String policy = takeoverPolicy();
            try {
                if (client != null) client.disconnect();
            } catch (Throwable ignored) {
            }
            if ("grab_back".equals(policy)) {
                // 协议优先：立即重登抢回（会把顶入者踢下线）
                lastFailure = "被顶号，按策略立即抢回";
                beginRecoveryNow("被顶号，立即抢回");
                return;
            }
            if ("yield_only".equals(policy)) {
                // 只让位：不再自动回连，直到开关/同步触发重建
                takeoverProbeAtMillis = Long.MAX_VALUE;
                lastFailure = "账号在其他设备上线，已让位（策略：只让位）";
                setSessionState(STATE_TAKEOVER);
                return;
            }
            // human_first（默认）：30 分钟后探测回连
            takeoverProbeAtMillis = System.currentTimeMillis() + TAKEOVER_PROBE_MILLIS;
            lastFailure = "账号在其他设备上线，30分钟后自动探测回连";
            setSessionState(STATE_TAKEOVER);
            return;
        }
        if (code == 26) {
            try {
                if (client != null) client.disconnect();
            } catch (Throwable ignored) {
            }
            lastFailure = "游戏账号角色已被删除/凭证失效，需重新导出 token";
            setSessionState(STATE_NEED_TOKEN);
            return;
        }
        if (code == 46) {
            // 同设备连接冲突（原版码表同名分类）：多为同 device_id 的另一条登录顶掉了本会话。
            // 可恢复，但重连太快会与对方踢成环——按原版被踢冷却（protocol-kick-cooldown-millis
            // 默认 60s）拉长回连间隔。根治靠上层"每角色一台独立设备指纹"（AccountStore.roleIdentitySeed）。
            try {
                if (client != null) client.disconnect();
            } catch (Throwable ignored) {
            }
            beginKickCooldown("同设备连接冲突(code=46)，冷却后自动重连");
            return;
        }
        beginRecovery("会话被服务端断开 code=" + code + "，等待自动恢复");
    }

    /** 顶号暂停是否已到探测时刻（keeper 每秒问）。 */
    public boolean takeoverProbeDue(long now) {
        return STATE_TAKEOVER.equals(sessionState) && now >= takeoverProbeAtMillis;
    }

    /**
     * 主动让位（vault-server 让位指令：App 端用户要进游戏时调用）：
     * 断开当前会话进入 takeover 态，probeMillis 后按正常探测逻辑回连。
     */
    public void beginYield(long probeMillis, String reason) {
        try {
            if (client != null) client.disconnect();
        } catch (Throwable ignored) {
        }
        takeoverProbeAtMillis = System.currentTimeMillis() + Math.max(0, probeMillis);
        lastFailure = reason;
        setSessionState(STATE_TAKEOVER);
    }

    /** 顶号策略来源（平台注入：App 读 daily_sched:takeover_policy，服务器读配置文件）。 */
    private static volatile Supplier<String> takeoverPolicySource;

    public static void setTakeoverPolicySource(Supplier<String> source) {
        takeoverPolicySource = source;
    }

    /** 顶号策略（human_first 默认 / grab_back / yield_only）。 */
    private String takeoverPolicy() {
        Supplier<String> s = takeoverPolicySource;
        if (s != null) {
            try {
                String v = s.get();
                if (v != null && !v.isEmpty()) return v;
            } catch (Throwable ignored) {
            }
        }
        return "human_first";
    }

    private void setSessionState(String s) {
        this.sessionState = s;
        java.util.function.Consumer<String> l = stateListener;
        if (l != null) {
            try {
                l.accept(s);
            } catch (Throwable ignored) {
            }
        }
    }

    public void close() {
        setSessionState(STATE_OFFLINE);
        clearResourceUpdate();
        try {
            if (notifyHook != null) notifyHook.close();
        } catch (Exception ignored) {
        }
        if (client != null) {
            try {
                client.shutdown();
            } catch (Throwable ignored) {
            }
            client = null;
        }
    }

    private void hookNotifies() {
        try {
            if (notifyHook != null) {
                notifyHook.close();
            }
        } catch (Exception ignored) {
        }
            notifyHook = client.addInboundListener(msg -> {
                if (msg == null || msg.getProto() == null) return;
                notifies.put(msg.getProto(), msg.getContent());
                // 资源增量累积（R-05）：同 id 多次推送累加，供起号 syncLoadData 一次性消费
                if ("notify_resource_update".equals(msg.getProto())) {
                    try {
                        accumulateResourceUpdate(msg.getContent());
                    } catch (Throwable ignored) {
                    }
                }
                // P0 服务器时钟：推送侧校准（notify_game_init.servertime / notify_schedule_update.server_time，秒域实证）
                if ("notify_game_init".equals(msg.getProto()) || "notify_schedule_update".equals(msg.getProto())) {
                    try {
                        serverClock.calibratePush(msg.getContent(), GameServerClock.monoNow());
                    } catch (Throwable ignored) {
                    }
                }
                // 常驻会话：服务端主动断开（notify_logout）按原版码表分类处理
                if ("notify_logout".equals(msg.getProto())) {
                try {
                    handleLogoutPush(msg.getContent());
                } catch (Throwable ignored) {
                }
            }
            for (java.util.function.BiConsumer<String, Map<String, Object>> h : messageHooks) {
                try {
                    h.accept(msg.getProto(), msg.getContent());
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 等待并收集登录后的服务端推送（活动/状态通知），供任务提取参数。 */
    public void collectNotifies(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 某条通知最近一次的内容（未收到返回 null）。 */
    public Map<String, Object> notifyContent(String proto) {
        return notifies.get(proto);
    }

    /** 已收到的通知协议名（调试用）。 */
    public String notifyProtos() {
        return String.join(",", notifies.keySet());
    }

    /** 通知内容解包：多数游戏推送为 {data:{...}} 包装，这里直接返回内层（无包装返回原内容）。 */
    public Map<String, Object> notifyData(String proto) {
        Map<String, Object> c = notifies.get(proto);
        if (c == null) return null;
        for (String k : new String[]{"data", "info", "content"}) {
            Object v = c.get(k);
            if (v instanceof Map) return (Map<String, Object>) v;
        }
        return c;
    }

    /**
     * 入站资源增量累积（R-05 / 审查 P2-2）：{@code notify_resource_update} 的
     * {@code data:[{id,count}]} 按 id 累加，避免「同内容两次真实增量」被最近一次覆盖。
     */
    public void accumulateResourceUpdate(Map<String, Object> content) {
        if (content == null) return;
        Object data = content.get("data");
        if (data == null) data = content.get("info");
        if (data == null) data = content.get("content");
        if (data == null) data = content;
        synchronized (pendingResUpdate) {
            for (Map<String, Object> entry : resourceUpdateEntries(data)) {
                long id = longOf(entry.get("id"));
                if (id <= 0L) continue;
                long delta = longOf(entry.get("count"));
                if (delta == 0L) continue;
                pendingResUpdate.merge(id, delta, Long::sum);
            }
        }
    }

    /**
     * 原子取走当前累积的资源增量并清空（R-05）：返回 {@code {data:[{id,count}]}}；
     * 无累积返回 {@code null}（调用方回退 loadContent.res_update）。
     */
    public Map<String, Object> consumeResourceUpdate() {
        synchronized (pendingResUpdate) {
            if (pendingResUpdate.isEmpty()) return null;
            java.util.ArrayList<Map<String, Object>> data = new java.util.ArrayList<>();
            for (Map.Entry<Long, Long> e : pendingResUpdate.entrySet()) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("id", e.getKey());
                item.put("count", e.getValue());
                data.add(item);
            }
            pendingResUpdate.clear();
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("data", data);
            return out;
        }
    }

    /** 清空未消费的资源增量（起号重跑/重登时调用，避免上一会话残留被下一轮误应用）。 */
    public void clearResourceUpdate() {
        synchronized (pendingResUpdate) {
            pendingResUpdate.clear();
        }
    }

    /** 资源增量条目解析：兼容 {@code [{id,count}]}、{@code {data:[...]}} 与单条 map。 */
    private static java.util.List<Map<String, Object>> resourceUpdateEntries(Object raw) {
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            if (map.get("id") != null) {
                LinkedHashMap<String, Object> single = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) single.put(String.valueOf(e.getKey()), e.getValue());
                }
                out.add(single);
                return out;
            }
            for (Object value : map.values()) out.addAll(resourceUpdateEntries(value));
        } else if (raw instanceof Iterable) {
            for (Object value : (Iterable<?>) raw) out.addAll(resourceUpdateEntries(value));
        }
        return out;
    }

    private static long longOf(Object raw) {
        if (raw instanceof Number) return ((Number) raw).longValue();
        try {
            return raw == null ? 0L : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** 从通知内容里向下找一个整型 id 字段（常见形状：id / activity_id / activityId）。 */
    public static Long extractId(Map<String, Object> content) {
        if (content == null) return null;
        for (String key : new String[]{"id", "activity_id", "activityId"}) {
            Object v = content.get(key);
            if (v instanceof Number) return ((Number) v).longValue();
            try {
                if (v != null) return Long.parseLong(String.valueOf(v).trim());
            } catch (Exception ignored) {
            }
        }
        // 嵌套一层的 activity 列表：[{id:...}]
        Object list = content.get("activities");
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof Map) {
                    Long id = extractId((Map<String, Object>) o);
                    if (id != null) return id;
                }
            }
        }
        return null;
    }

    /** 角色信息（发现协议 req_server_list_simple + req_role_list）。 */
    public static class RoleInfo {
        public long serverid;
        public long uid;
        public String name = "";
    }

    /** 自动发现当前凭证下的全部角色（须已登录）。 */
    public java.util.List<RoleInfo> discoverRoles() throws Exception {
        java.util.List<RoleInfo> out = new java.util.ArrayList<>();
        java.util.Set<Long> servers = new java.util.LinkedHashSet<>();
        try {
            GameMessage sl = request("req_server_list_simple", null);
            if (!isUnknown(sl)) collectServers(sl.getContent(), servers);
        } catch (Exception ignored) {
        }
        if (servers.isEmpty()) {
            try {
                GameMessage sl = request("req_server_list", null);
                if (!isUnknown(sl)) collectServers(sl.getContent(), servers);
            } catch (Exception ignored) {
            }
        }
        if (servers.isEmpty()) servers.add(0L);
        try {
            GameMessage rl = request("req_role_list", null);
            if (!isUnknown(rl)) collectRoles(rl.getContent(), out, lastServerId);
        } catch (Exception ignored) {
        }
        for (Long sid : servers) {
            boolean covered = false;
            for (RoleInfo r : out) {
                if (sid == 0 || r.serverid == sid) {
                    covered = true;
                    break;
                }
            }
            if (!covered && sid > 0) {
                try {
                    java.util.Map<String, Object> args = new LinkedHashMap<>();
                    args.put("serverid", sid);
                    GameMessage rl2 = request("req_role_list", args);
                    if (!isUnknown(rl2)) collectRoles(rl2.getContent(), out, sid);
                } catch (Exception ignored) {
                }
            }
        }
        if (out.isEmpty() && lastUid > 0) {
            RoleInfo self = new RoleInfo();
            self.uid = lastUid;
            out.add(self);
        }
        return out;
    }

    private static boolean isUnknown(GameMessage m) {
        return m == null || m.getContent() == null
                || m.getContent().containsKey("_unknown_proto_tag");
    }

    @SuppressWarnings("unchecked")
    private static void collectServers(Map<String, Object> content, java.util.Set<Long> out) {
        for (Object v : content.values()) {
            if (!(v instanceof List)) continue;
            for (Object o : (List<Object>) v) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                for (String k : new String[]{"serverid", "server_id", "id"}) {
                    Object idv = m.get(k);
                    if (idv instanceof Number && ((Number) idv).longValue() > 0) {
                        out.add(((Number) idv).longValue());
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRoles(Map<String, Object> content, java.util.List<RoleInfo> out,
                                     long fallbackServerId) {
        for (Object v : content.values()) {
            if (!(v instanceof List)) continue;
            for (Object o : (List<Object>) v) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                RoleInfo r = new RoleInfo();
                for (String k : new String[]{"uid", "id", "role_id"}) {
                    Object idv = m.get(k);
                    if (idv instanceof Number && ((Number) idv).longValue() > 0) {
                        r.uid = ((Number) idv).longValue();
                        break;
                    }
                }
                for (String k : new String[]{"serverid", "server_id", "kid", "server"}) {
                    Object idv = m.get(k);
                    if (idv instanceof Number && ((Number) idv).longValue() > 0) {
                        r.serverid = ((Number) idv).longValue();
                        break;
                    }
                }
                for (String k : new String[]{"name", "role_name", "nickname", "player_name"}) {
                    Object nv = m.get(k);
                    if (nv != null && !String.valueOf(nv).isEmpty()) {
                        r.name = String.valueOf(nv);
                        break;
                    }
                }
                if (r.serverid <= 0) r.serverid = fallbackServerId;   // 按区服查询的结果无 serverid 字段，归属到查询区
                if (r.uid > 0 || r.serverid > 0) out.add(r);
            }
        }
    }

    /**
     * 通用协议请求（任务层直接使用，返回原始消息由任务自行校验）。
     * 会话级串行锁（对标原版 requestLock）：互助响应/任务执行/wake 补领共用在线会话时排队执行；
     * 在线状态下的连接类失败自动进入指数退避恢复。
     */
    public GameMessage request(String proto, Map<String, Object> args) throws Exception {
        long lockT0Ms = GameServerClock.monoNow();
        requestLock.lock();
        lastRequestLockWaitMs = Math.max(0L, GameServerClock.monoNow() - lockT0Ms);
        try {
            return lockedRequest(proto, args == null ? new LinkedHashMap<String, Object>() : args,
                    java.time.Duration.of(20, java.util.concurrent.TimeUnit.SECONDS.toChronoUnit()));
        } catch (Exception e) {
            if (STATE_ONLINE.equals(sessionState)) beginRecovery(e);
            throw e;
        } finally {
            requestLock.unlock();
        }
    }

    /**
     * 「只发不等」协议发送（地图范围查询 req_world_map_info 等专用）。
     *
     * <p>这类协议发请求后由服务端以 notify 推送回数据（notify_world_mapobjs /
     * notify_world_marchs，推送 content.session 回显请求时使用的 session）；数据经
     * {@link #addMessageHook} 按 session 对账。session 由调用方指定（地图查询约定 1e8~4e8）。
     *
     * <p>不占用 requestLock、不等待响应；写帧由底层 ioLock 串行化，线程安全。
     */
    public void sendFireAndForget(String proto, Map<String, Object> args, int session) {
        GameProtocolClient c = client;
        if (c == null) throw new IllegalStateException("游戏会话未初始化");
        c.sendFireAndForget(proto, args == null ? new LinkedHashMap<String, Object>() : args, session);
    }

    /**
     * 短超时请求（卡秒路径专用）：超时仍会断连触发恢复，但把"无响应协议"的占坑时间
     * 从 20s 压到秒级，保住发射窗（守护盟友 R3 加速跟进用）。
     */
    public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) throws Exception {
        long lockT0Ms = GameServerClock.monoNow();
        requestLock.lock();
        lastRequestLockWaitMs = Math.max(0L, GameServerClock.monoNow() - lockT0Ms);
        try {
            return lockedRequest(proto, args == null ? new LinkedHashMap<String, Object>() : args,
                    java.time.Duration.ofSeconds(Math.max(1, timeoutSec)));
        } catch (Exception e) {
            if (STATE_ONLINE.equals(sessionState)) beginRecovery(e);
            throw e;
        } finally {
            requestLock.unlock();
        }
    }

    /** 持锁请求（调用方须已持有 requestLock）：成功即记录活动时间。 */
    private GameMessage lockedRequest(String proto, Map<String, Object> args, java.time.Duration timeout) throws Exception {
        ensureClient();
        // P1 实证辅助：关键协议的出站参数落引擎日志（files/wjdr_log.txt）——自动化成功样本可直接对照复刻
        if ("req_world_map_search".equals(proto) || "req_world_march".equals(proto)
                || "req_world_march_speedup".equals(proto) || "req_world_march_recall".equals(proto)
                || "req_arsenal_league_move_city".equals(proto)
                || "req_arsenal_league_march_speedup".equals(proto)
                || "req_embassy_help_simple_info".equals(proto) || "req_embassy_repatriation".equals(proto)
                || "req_arsenal_league_embassy_help_simple_info".equals(proto)
                || "req_arsenal_league_embassy_repatriation".equals(proto)) {
            try {
                WjdrLog.i("c2s " + proto + " args=" + args);
            } catch (Throwable ignored) {
            }
        }
        // 监控/诊断 IO 监听（真机监控 P0）：未注册监听器时零额外开销，且绝不改变请求语义
        final boolean watch = !IO_LISTENERS.isEmpty();
        final long t0 = watch ? System.nanoTime() : 0L;
        if (watch) notifyIo(this, "req", proto, args, 0L, null);
        GameMessage rsp;
        try {
            rsp = client.request(proto, args, timeout);
        } catch (Throwable t) {
            if (watch) {
                notifyIo(this, "err", proto, null, elapsedMs(t0), t);
            }
            throw t;   // 原样抛出：不改变现有请求/恢复语义
        }
        this.lastActivityAt = System.currentTimeMillis();
        if (watch) {
            notifyIo(this, "rsp", proto, rsp == null ? null : rsp.getContent(), elapsedMs(t0), null);
        }
        return rsp;
    }

    /** 监听耗时（毫秒，非负）。 */
    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * 心跳扫描（由会话保活方每秒调用）。语义照抄原版 heartbeatDue：
     * 空闲≥3s 或 距上次心跳≥60s 才发；有在途请求（锁被占）跳过本轮。
     */
    public void heartbeatTick() {
        if (!STATE_ONLINE.equals(sessionState)) return;
        long now = System.currentTimeMillis();
        if (now - lastActivityAt < HEARTBEAT_IDLE_MILLIS
                && now - lastHeartbeatSentAt < HEARTBEAT_MAX_INTERVAL_MILLIS) {
            return;
        }
        if (!heartbeatInFlight.compareAndSet(false, true)) return;
        try {
            if (requestLock.tryLock()) {
                try {
                    lastHeartbeatSentAt = System.currentTimeMillis();
                    long sentLocalSec = lastHeartbeatSentAt / 1000L;
                    long t0 = GameServerClock.monoNow();
                    GameMessage hb = lockedRequest("req_heartbeat", new LinkedHashMap<String, Object>(), seconds(5));
                    long t1 = GameServerClock.monoNow();
                    serverClock.calibrate(hb == null ? null : hb.getContent(), t0, t1, sentLocalSec);
                    long n = heartbeatCount.incrementAndGet();
                    if (n % 20 == 0) {
                        com.carlos.vault.wjdr.WjdrLog.i("session alive fpid={} sid={} heartbeats={}",
                                (Object) String.valueOf(lastFpid), (Object) String.valueOf(lastServerId),
                                (Object) String.valueOf(n));
                    }
                } finally {
                    requestLock.unlock();
                }
            }
        } catch (Throwable ignored) {
            // 连接类失败已由恢复流程接管；其余（锁竞争/瞬时超时）下轮扫描再试
        } finally {
            heartbeatInFlight.set(false);
        }
    }

    // ---------- 常驻会话状态 API（会话保活方 / UI 使用） ----------

    public String sessionState() {
        return sessionState;
    }

    /** 游戏服务器时钟（P0）：校准状态/nowServerMs/waitUntil，驻防类毫秒到点的统一入口。 */
    public GameServerClock serverClock() {
        return serverClock;
    }

    /** 最近一次 request 等待 requestLock 的毫秒数（R0-1 阶段③观测；-1=尚未发过请求）。 */
    public long lastRequestLockWaitMs() {
        return lastRequestLockWaitMs;
    }

    public String lastFailure() {
        return lastFailure;
    }

    /** 当前凭证（会话管理器比对 token 是否已更新）。 */
    public String savedToken() {
        return savedToken;
    }

    public void setStateListener(java.util.function.Consumer<String> listener) {
        this.stateListener = listener;
    }

    /** 原样消息钩子：互助响应等常驻监听（与 notifies 收集并行，不替换）。 */
    public void addMessageHook(java.util.function.BiConsumer<String, Map<String, Object>> hook) {
        if (hook != null) messageHooks.add(hook);
    }

    // ================= 协议 IO 监听（真机监控 P0 2026-10-25） =================

    /**
     * 协议 IO 监听器：每次 {@code lockedRequest} 发出前（{@code req}）、成功后（{@code rsp}）、
     * 异常时（{@code err}）回调，供服务端任务级监控（TaskTrace）/真机诊断使用。
     *
     * <p>约定：{@code direction} ∈ {@code "req"} / {@code "rsp"} / {@code "err"}；
     * {@code body} 对 req 为出站参数、对 rsp 为响应 content、对 err 为 {@code null}；
     * {@code durMs} 为本次请求耗时（req 固定 0）；{@code error} 仅 err 非空。
     *
     * <p><b>实现约束</b>：监听器自身必须快、必须自行吞异常；引擎分发侧也会兜底，
     * 任何监听器异常都不得影响请求本身。未注册监听器时请求路径零额外开销。
     */
    public interface IoListener {
        void onIo(WjdrEngine engine, String direction, String proto,
                  Map<String, Object> body, long durMs, Throwable error);
    }

    /** IO 监听注册表（进程级；App 与服务端共用）。 */
    private static final java.util.concurrent.CopyOnWriteArrayList<IoListener> IO_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 注册 IO 监听（重复注册同一实例会收到多次回调，调用方自行去重）。 */
    public static void addIoListener(IoListener listener) {
        if (listener != null) IO_LISTENERS.add(listener);
    }

    /** 注销 IO 监听。 */
    public static void removeIoListener(IoListener listener) {
        if (listener != null) IO_LISTENERS.remove(listener);
    }

    /** 是否注册了 IO 监听（监控层/单测可用；未注册时请求走零开销分支）。 */
    public static boolean hasIoListeners() {
        return !IO_LISTENERS.isEmpty();
    }

    /** 分发一次 IO 事件：逐监听器 try/catch，异常绝不外溢到请求/任务线程。 */
    public static void notifyIo(WjdrEngine engine, String direction, String proto,
                                Map<String, Object> body, long durMs, Throwable error) {
        for (IoListener listener : IO_LISTENERS) {
            try {
                listener.onIo(engine, direction, proto, body, durMs, error);
            } catch (Throwable ignored) {
                // 监听器（监控）异常绝不影响请求本身
            }
        }
    }

    /** 上次登录得到的 uid（未登录为 0） */
    public String lastFpid() {
        return lastFpid;
    }

    /**
     * 宿主上下文（不透明载荷）：App 注入 android.content.Context 供任务层读配置/assets；
     * 服务器侧不使用。经泛型隐式转换回灌，daily/ 调用点无需改动。
     */
    private Object hostContext;

    public void setAppContext(Object context) {
        this.hostContext = context;
    }

    @SuppressWarnings("unchecked")
    public <T> T appContext() {
        return (T) hostContext;
    }

    public long lastServerId() {
        return lastServerId;
    }

    public long lastUid() {
        return lastUid;
    }

    private void ensureClient() {
        if (client == null) {
            throw new IllegalStateException("引擎未初始化");
        }
    }

    /** 从 extraParams 提取角色 uid（loginByRole 放入 "uid"）；缺失/非法返回 0。 */
    static long roleUidFrom(Map<String, Object> extraParams) {
        if (extraParams == null) return 0L;
        Object v = extraParams.get("uid");
        return v == null ? 0L : toLong(v);
    }

    /**
     * 登录载荷构建（旧 3 参重载，等价 roleUid=0）：即发现式/legacy 路径，
     * 设备取每引擎临时设备（见 4 参重载），不进入角色维度确定性派生。
     */
    Map<String, Object> buildLoginPayload(String fpid, String sdkToken, Long serverid) {
        return buildLoginPayload(fpid, sdkToken, serverid, 0L);
    }

    /**
     * 登录载荷构建（角色维度）：roleUid&gt;0 时设备 seed 含角色 uid，保证同账号同服多角色
     * 在无角色级指纹时也不会撞设备（P0 修复）；roleUid&lt;=0 时使用每引擎临时设备（P1-1）。
     */
    Map<String, Object> buildLoginPayload(String fpid, String sdkToken, Long serverid,
                                          long roleUid) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        // 固定指纹优先（每账号持久一份）；未注入则按 fpid+serverid 确定性生成——同一角色
        // 登录失败重试得到同一台设备（2026-11-11 修复"每次重试换设备"的漂移）。
        long sid = serverid != null && serverid > 0 ? serverid : 0L;
        DeviceProfile.Device base;
        if (roleUid > 0) {
            base = DeviceProfile.generate(fpid, sid, roleUid);
        } else {
            // P1-1：发现式/legacy 登录（无角色 uid）用每引擎一台临时设备——旧实现是随机，
            // 本类改为按 (fpid, serverid) 确定性后，同账号多 Session 发现登录会撞设备互踢。
            // 临时设备在同一引擎内重试稳定，且不写入持久化（identityJson 不用于发现路径固化）。
            if (ephemeralDevice == null) ephemeralDevice = DeviceProfile.ephemeral();
            base = ephemeralDevice;
        }
        String distinctId = pinnedDistinctId != null ? pinnedDistinctId : base.distinctId;
        String deviceId = pinnedDeviceId != null ? pinnedDeviceId : base.deviceId;
        String loginIp = pinnedLoginIp != null ? pinnedLoginIp : base.ip;
        String deviceModel = pinnedDeviceModel != null ? pinnedDeviceModel : base.model;
        // 机型已固化但厂商/版本缺失时，从机型推导（保证 v2 输出自洽），推导失败再用确定性基线
        String manufacturer = pinnedManufacturer;
        if (manufacturer == null) {
            manufacturer = DeviceProfile.manufacturerOf(deviceModel);
            if (manufacturer == null) manufacturer = base.manufacturer;
        }
        int androidVersion = pinnedAndroidVersion;
        if (androidVersion <= 0) {
            androidVersion = DeviceProfile.androidVersionOf(deviceModel);
            if (androidVersion <= 0) androidVersion = base.androidVersion;
        }
        int deviceLv = pinnedDeviceLv > 0 ? pinnedDeviceLv : base.deviceLv;
        lastDistinctId = distinctId;
        lastDeviceId = deviceId;
        lastLoginIp = loginIp;
        lastDeviceModel = deviceModel;
        lastManufacturer = manufacturer;
        lastBrand = pinnedBrand != null ? pinnedBrand : manufacturer;
        lastAndroidVersion = androidVersion;
        lastDeviceLv = deviceLv;
        p.put("account", fpid);
        p.put("sdkToken", sdkToken);
        if (serverid != null && serverid > 0) {
            p.put("serverid", serverid);   // 角色选择：同一号在不同区服的角色
        }
        p.put("device", deviceModel);
        p.put("device_os", "android");
        p.put("country_code", "CN");
        p.put("distinct_id", distinctId);
        p.put("lan", "zh_cn");
        p.put("version", "1.16.11");
        p.put("package_tag", "App_android_8_funplus");
        p.put("ad_source", "funplus");
        p.put("device_id", deviceId);
        p.put("device_lv", deviceLv);
        p.put("is_create", false);
        p.put("group", 1);
        p.put("is_simulator", false);
        p.put("ip", loginIp);
        return WjdrCrypto.withLoginauthSign(p);
    }

    private static void validate(GameMessage message, String proto) {
        if (message == null || message.getContent() == null) {
            throw new IllegalStateException(proto + " 无响应");
        }
        Object code = message.getContent().get("code");
        if (code instanceof Number && ((Number) code).intValue() != 0) {
            throw new IllegalStateException(proto + " 返回错误 code=" + code);
        }
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> summarize(Map<String, Object> content) {
        LinkedHashMap<String, Object> s = new LinkedHashMap<>();
        if (content == null) return s;
        for (String key : new String[]{"uid", "serverid", "name", "level", "vip", "kingdom"}) {
            Object v = content.get(key);
            if (v != null) s.put(key, v);
        }
        s.put("keys", content.size());
        return s;
    }

    @SuppressWarnings("unchecked")
    private static String extractGameName(Map<String, Object> content, long uid) {
        try {
            Object players = content.get("players");
            if (players instanceof List) {
                for (Object o : (List<Object>) players) {
                    if (o instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) o;
                        if (toLong(m.get("uid")) == uid) {
                            Object name = m.get("name");
                            if (name != null) return String.valueOf(name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** players 列表内匹配本角色的条目，补齐等级/战力/联盟名（loaddata 没给时兜底）。 */
    @SuppressWarnings("unchecked")
    private static void extractPlayerMeta(Map<String, Object> content, long uid, LoginResult result) {
        try {
            Object players = content.get("players");
            if (!(players instanceof List)) return;
            for (Object o : (List<Object>) players) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                if (toLong(m.get("uid")) != uid) continue;
                if (result.level <= 0) {
                    for (String k : new String[]{"level", "stove_lv", "furnace_lv", "lv"}) {
                        long v = toLong(m.get(k));
                        if (v > 0) { result.level = v; break; }
                    }
                }
                if (result.power <= 0) {
                    for (String k : new String[]{"power", "fight_num", "fight_capacity"}) {
                        long v = toLong(m.get(k));
                        if (v > 0) { result.power = v; break; }
                    }
                }
                if (result.alliance.isEmpty()) result.alliance = extractAllianceName(m);
                return;
            }
        } catch (Exception ignored) {
        }
    }

    /** 联盟名提取：只认 alliance/alliance_info 映射内的 name/short_name/abbr（避免误抓角色 name）。 */
    private static String extractAllianceName(Map<String, Object> m) {
        if (m == null) return "";
        for (String k : new String[]{"alliance_name", "ally_name"}) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v);
        }
        for (String k : new String[]{"alliance", "alliance_info"}) {
            Object v = m.get(k);
            if (v instanceof Map) {
                Map<String, Object> al = (Map<String, Object>) v;
                for (String nk : new String[]{"name", "short_name", "abbr"}) {
                    Object nv = al.get(nk);
                    if (nv != null && !String.valueOf(nv).isEmpty()) return String.valueOf(nv);
                }
            }
        }
        return "";
    }

    /** 深度查找首个在合理量程内的数值字段（depth≤3；power/等级类字段做量程防御）。 */
    @SuppressWarnings("unchecked")
    private static long deepFindNum(Map<String, Object> m, int depth, String... keys) {
        if (m == null || depth > 3) return 0;
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number && ((Number) v).longValue() > 0) return ((Number) v).longValue();
        }
        for (Object v : m.values()) {
            if (v instanceof Map) {
                long r = deepFindNum((Map<String, Object>) v, depth + 1, keys);
                if (r > 0) return r;
            }
        }
        return 0;
    }

    private static long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static java.time.Duration seconds(int s) {
        return java.time.Duration.of(s, TimeUnit.SECONDS.toChronoUnit());
    }
}
