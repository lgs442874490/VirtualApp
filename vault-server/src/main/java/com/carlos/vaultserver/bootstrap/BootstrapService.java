package com.carlos.vaultserver.bootstrap;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vault.wjdr.protocol.ProtocolValueSupport;
import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.bootstrap.BuildingGateCatalog.Cost;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Action;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.ActionKind;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Catalog;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Plan;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.Request;
import com.carlos.vaultserver.bootstrap.BootstrapPlanner.ResourceAvailability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 起号单号执行核心（M4a 任务书 §2.4，原版 wjdr-tool BootstrapService 移植）。
 *
 * <p>依赖注入 {@link ProtocolPort}（函数接口：生产包 {@link WjdrEngine#request}，单测脚本回放）
 * 与 {@link Catalog}（生产 {@link BootstrapCatalog}），全部文案（STARTED/WAITING/MANUAL/
 * COMPLETED/RESYNC_REQUIRED/EXP_BLOCKED/FAILED/JOINED + message + runState/runMessage）
 * 逐字对齐原版。
 *
 * <p><b>M4b 增量</b>：熔炉 ≥7 级后 runNext 在状态同步检查前先处理情报（领取已可领奖
 * + 快打空闲英雄之旅，语义对齐原版 claimIntelligenceRewards/fightIdleIntelligence）；
 * 背包资源包 usePacks 实装（白名单对齐原版 DailyBackpackResourceCatalog：固定资源包 +
 * 资源自选箱，{@code req_use_item} 使用直到资源够或包用尽）。
 *
 * <p><b>仍在的 M4b 边界</b>：任务奖励领取（claimRewards）与统帅商店补资源
 * （buyShopResources）保持占位不实现（原版默认 false；后续立项）。
 */
public final class BootstrapService {

    private static final long FURNACE_ID = 301L;
    private static final long HERO_HALL_ID = 318L;
    private static final String UPGRADE_BUILDING = "req_upgrade_building";
    private static final String CREATE_FURNITURE = "req_create_furniture";
    private static final String UPGRADE_FURNITURE = "req_upgrade_furniture";
    /** 背包资源包使用协议（原版 DailyBackpackResourceSupport.USE_ITEM_PROTOCOL）。 */
    private static final String USE_ITEM_PROTOCOL = "req_use_item";
    /** 情报领奖协议（原版 IntelligenceQuestSupport.RECEIVE_REWARD_PROTOCOL）。 */
    static final String INTEL_RECEIVE_PROTOCOL = "req_intelligence_receive";
    /** 情报开战协议（原版 IntelligenceQuestSupport.START_BATTLE_PROTOCOL）。 */
    static final String INTEL_START_BATTLE_PROTOCOL = "req_intelligence_start_battle";
    /** 情报结算协议（原版 IntelligenceQuestSupport.END_BATTLE_PROTOCOL）。 */
    static final String INTEL_END_BATTLE_PROTOCOL = "req_intelligence_end_battle";
    /** 情报事件列表推送键：全量初始化（对齐 ServerFieldTasks.intel / IntelRound 数据源口径）。 */
    static final String NOTIFY_INTEL_INFO = "notify_intelligence_info";
    /** 情报事件列表推送键：增量合并。 */
    static final String NOTIFY_INTEL_EVENTS = "notify_intelligence_events";
    /** 情报事件列表推送键：删除。 */
    static final String NOTIFY_INTEL_DEL = "notify_intelligence_events_del";
    /** 服务端错误码：情报事件已被处理/不存在（原版 IntelligenceQuestSupport.EVENT_NOT_FOUND_CODE）。 */
    static final int INTEL_EVENT_NOT_FOUND = 2501;
    /** 单次使用资源包数量上限（原版 DailyBackpackResourceCatalog.MAX_USE_COUNT）。 */
    private static final long MAX_USE_COUNT = 999999L;
    /** 服务端错误码：前置建筑等级不足。 */
    private static final int CONDITION_BUILDING_LEVEL_NOT_ENOUGH = 806;
    /** 服务端错误码：建筑经验不足。 */
    private static final int BUILDING_EXP_NOT_ENOUGH = 119;
    /** 服务端错误码：道具/资源不足。 */
    private static final int ITEM_NOT_ENOUGH = 51;
    /** 服务端错误码：建筑缺少家具。 */
    private static final int BUILDING_HAS_NOT_FURNITURE = 120;
    /** 服务端错误码：角色名与原名相同（视为改名成功）。 */
    private static final int NAME_SAME_AS_BEFORE = 205;
    private static final String ALLIANCE_SEARCH = "req_alliance_search_by_key";
    private static final String ALLIANCE_JOIN = "req_alliance_join";
    private static final String ALLIANCE_APPLY_JOIN = "req_alliance_apply_join";
    private static final String MODIFY_NAME_PROTOCOL = "req_player_modify_name";
    private static final int MAX_RUN_UNTIL_STEPS = 300;
    /** 世界地图解锁熔炉等级（原版 WORLD_MAP_UNLOCK_FURNACE_LEVEL，情报模块启用门槛）。 */
    static final long WORLD_MAP_UNLOCK_FURNACE_LEVEL = 7L;

    /**
     * 协议端口：生产包 WjdrEngine.request，单测脚本回放。
     *
     * <p>M4b 增加两个 default 方法（情报列表来自服务端推送，非请求-响应协议）：
     * {@link #notify}（生产 {@code WjdrEngine.notifyData}，未收到推送返回 null）与
     * {@link #collectNotifies}（生产 {@code WjdrEngine.collectNotifies}，等待收集推送）。
     * 缺省实现 = 推送通道不可用 → 情报模块自动降级跳过，不阻塞建筑主线。
     */
    @FunctionalInterface
    public interface ProtocolPort {
        GameMessage request(String proto, Map<String, Object> args) throws Exception;

        /** 推送最近一次内容（键形 {@code {data:{…}}} 已解包；未收到返回 null）。 */
        default Map<String, Object> notify(String proto) {
            return null;
        }

        /** 收集推送（阻塞指定毫秒，生产等待服务端把情报/资源推送送齐）。 */
        default void collectNotifies(long millis) {
        }
    }

    /** 可注入睡眠（runUntil 步间等待空闲队列）：单测注入空实现跳时。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 真实睡眠（生产默认）。 */
    public static final Sleeper REAL_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    };

    /** 协议业务错误（响应 code != 0，对齐原版 GameProtocolException 的 code 语义）。 */
    public static final class ProtocolException extends RuntimeException {
        private final int code;

        public ProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** 起号配置（原版 BootstrapConfig 移植：字段/默认值/fromMap/toMap 一致）。 */
    public record BootstrapConfig(boolean enabled, long targetServerId, int targetFurnaceLevel,
                                  boolean instantDiamond, boolean claimRewards, boolean buyShopResources,
                                  String allianceName, boolean autoApplyWhenPrivate, String roleName) {
        public static final int MAX_ROLE_NAME_LENGTH = 16;
        public static final String SECTION = "settings";
        public static final int DEFAULT_TARGET_FURNACE_LEVEL = 9;
        public static final int MAX_TARGET_FURNACE_LEVEL = 9;

        public static BootstrapConfig defaults() {
            return new BootstrapConfig(false, 0L, 9, true, false, false, "", false, "");
        }

        public static BootstrapConfig fromMap(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return defaults();
            }
            return new BootstrapConfig(
                    booleanValue(raw.get("enabled"), false),
                    Math.max(0L, ProtocolValueSupport.number(raw.get("targetServerId"))),
                    clampFurnaceLevel((int) ProtocolValueSupport.number(raw.get("targetFurnaceLevel"))),
                    booleanValue(raw.get("instantDiamond"), true),
                    booleanValue(raw.get("claimRewards"), false),
                    booleanValue(raw.get("buyShopResources"), false),
                    text(raw.get("allianceName")),
                    booleanValue(raw.get("autoApplyWhenPrivate"), false),
                    normalizeRoleName(raw.get("roleName")));
        }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", enabled);
            result.put("targetServerId", targetServerId);
            result.put("targetFurnaceLevel", targetFurnaceLevel);
            result.put("instantDiamond", instantDiamond);
            result.put("claimRewards", claimRewards);
            result.put("buyShopResources", buyShopResources);
            result.put("allianceName", allianceName);
            result.put("autoApplyWhenPrivate", autoApplyWhenPrivate);
            result.put("roleName", roleName);
            return result;
        }

        private static String normalizeRoleName(Object raw) {
            String value = text(raw);
            return value.length() <= MAX_ROLE_NAME_LENGTH ? value
                    : value.substring(0, MAX_ROLE_NAME_LENGTH);
        }

        /** 熔炉目标等级夹取（1..9；JDK 17 无 Math.clamp，手写同语义）。 */
        private static int clampFurnaceLevel(int raw) {
            return Math.max(1, Math.min(MAX_TARGET_FURNACE_LEVEL, raw));
        }

        private static boolean booleanValue(Object raw, boolean fallback) {
            if (raw == null) return fallback;
            if (raw instanceof Boolean value) return value;
            String text = String.valueOf(raw).trim();
            if (text.isEmpty()) return fallback;
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
            return fallback;
        }

        private static String text(Object raw) {
            return raw == null ? "" : String.valueOf(raw).trim();
        }
    }

    /** 建号结果（M4a 直通实现：uid/serverId/authContent + 落区校验结论）。 */
    public record LoginOutcome(long uid, long serverId, Map<String, Object> authContent,
                               boolean serverMismatch, String message) {
        public LoginOutcome {
            authContent = authContent == null ? Map.of() : Map.copyOf(authContent);
        }
    }

    private final ProtocolPort port;
    private final Catalog catalog;
    private final BootstrapPlanner planner;
    private final Sleeper sleeper;
    private BootstrapState state = BootstrapState.empty();
    private BootstrapConfig config = BootstrapConfig.defaults();
    /** 改名成功缓存（对齐原版 renamedRoles：205 视为成功，避免重复请求）。 */
    private String renamedName = "";
    /** 最近一次改名失败文案（status 侧透出）。 */
    private String renameError = "";

    /** 生产装配：组合目录 + 默认规划器 + 真实睡眠。 */
    public BootstrapService(ProtocolPort port) {
        this(port, new BootstrapPlanner(), BootstrapCatalog.combined(), REAL_SLEEPER);
    }

    /** 全量注入装配（单测用）。 */
    public BootstrapService(ProtocolPort port, BootstrapPlanner planner, Catalog catalog, Sleeper sleeper) {
        this.port = port;
        this.planner = planner;
        this.catalog = catalog;
        this.sleeper = sleeper == null ? REAL_SLEEPER : sleeper;
    }

    // ================= 配置与状态注入 =================

    /** 当前配置。 */
    public BootstrapConfig config() {
        return config;
    }

    /** 应用配置（fromMap 语义，对齐原版 saveSettings）。 */
    public void applyConfig(Map<String, Object> raw) {
        this.config = BootstrapConfig.fromMap(raw);
    }

    /** 直接替换配置对象（编排层用）。 */
    public void applyConfig(BootstrapConfig cfg) {
        this.config = cfg == null ? BootstrapConfig.defaults() : cfg;
    }

    /** 注入 loaddata 全量内容（对齐原版注册表自动同步；单号版由会话层手动喂）。 */
    public void syncLoadData(Map<String, Object> loadContent) {
        this.state = BootstrapState.fromLoadData(loadContent);
    }

    /** 当前状态快照。 */
    public BootstrapState state() {
        return state;
    }

    // ================= status =================

    /**
     * 状态视图（字段与文案对齐原版 status 结构的 M4a 子集）：
     * config/roleUid/roleName/targetRoleName/stateReady/furnaceLevel/targetFurnaceLevel/
     * buildings/resources/remainingSteps/blockedReason/completed/runState/message。
     */
    public Map<String, Object> status() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("config", config.toMap());
        result.put("roleUid", state.roleUid());
        result.put("roleName", state.roleName());
        result.put("targetRoleName", config.roleName());
        result.put("stateReady", state.stateReady());
        result.put("furnaceLevel", state.furnaceLevel());
        result.put("targetFurnaceLevel", config.targetFurnaceLevel());
        result.put("buildings", buildingView());
        result.put("resources", resourceView());
        Plan plan = planNext(config, BootstrapPlanner.DEFAULT_MAX_ACTIONS);
        result.put("remainingSteps", plan.actions().stream().map(this::stepView).toList());
        result.put("blockedReason", plan.blockedReason());
        result.put("completed", state.furnaceLevel() >= config.targetFurnaceLevel());
        result.put("runState", runState());
        result.put("message", runMessage());
        return result;
    }

    // ================= 执行入口 =================

    /**
     * 执行下一步（对齐原版 runNextStep）：改名 →（熔炉 ≥7 情报领取/快打）→ 状态未同步
     * WAITING → 规划 → 执行首个动作。
     */
    public Map<String, Object> runNext() throws Exception {
        BootstrapConfig cfg = requireEnabled();
        Optional<Map<String, Object>> rename = renameIfNeeded(cfg);
        if (rename.isPresent()) {
            return rename.get();
        }
        // 情报挂点（对齐原版：熔炉 ≥ WORLD_MAP_UNLOCK_FURNACE_LEVEL 才启用；领取/快打
        // 结果非空则本轮直通返回，不阻塞建筑主线；推送不可得自动降级跳过）。
        if (state.furnaceLevel() >= WORLD_MAP_UNLOCK_FURNACE_LEVEL) {
            Optional<Map<String, Object>> intelligence = claimIntelligenceRewards();
            if (intelligence.isPresent()) {
                return intelligence.get();
            }
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return actionResult("WAITING", "等待建筑与队列全量状态同步");
        }
        Plan plan = planNext(cfg, BootstrapPlanner.DEFAULT_MAX_ACTIONS);
        if (plan.actions().isEmpty()) {
            if (plan.complete()) {
                Map<String, Object> result = actionResult("COMPLETED",
                        "大熔炉已到达目标等级 " + cfg.targetFurnaceLevel());
                result.put("levelingCompleted", true);
                return result;
            }
            Map<String, Object> result = actionResult("WAITING",
                    plan.blockedReason().isEmpty() ? "当前没有可执行动作，等待状态同步" : plan.blockedReason());
            result.put("blockedReason", plan.blockedReason());
            return result;
        }
        return executeFirstAction(cfg, plan);
    }

    /**
     * 连续执行到非 STARTED 或步数上限（对齐原版 runUntil）：步间等待空闲队列 ≤10s
     * （{@link Sleeper} 可注入）。
     */
    public Map<String, Object> runUntil(int maxSteps) throws Exception {
        requireEnabled();
        int limit = Math.max(1, Math.min(MAX_RUN_UNTIL_STEPS, maxSteps));
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        int started = 0;
        String stopMessage = "";
        for (int index = 0; index < limit; ++index) {
            Map<String, Object> result = runNext();
            results.add(result);
            String status = text(result.get("status"));
            if (!"STARTED".equals(status)) {
                stopMessage = text(result.get("message"));
                break;
            }
            ++started;
            boolean keepGoing = index + 1 < limit && idleReached(10000L);
            if (keepGoing) continue;
            if (index + 1 < limit) break;
            stopMessage = "本轮已达到单次执行上限 " + limit + " 步，可再次点击继续";
            break;
        }
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("started", started);
        summary.put("results", List.copyOf(results));
        summary.put("state", status());
        summary.put("message", stopSummary(started, stopMessage));
        return summary;
    }

    /** 单建筑直升一级（对齐原版 levelBuilding：current+1 目标规划执行）。 */
    public Map<String, Object> levelBuilding(long buildingId) throws Exception {
        BootstrapConfig cfg = requireEnabled();
        if (!state.buildingsReady() || !state.queuesReady()) {
            return actionResult("WAITING", "等待建筑与队列全量状态同步");
        }
        if (!catalog.supports(buildingId)) {
            throw new IllegalArgumentException("不支持的建筑编号: " + buildingId);
        }
        long current = state.level(buildingId);
        if (current >= catalog.maxLevel(buildingId)) {
            return actionResult("COMPLETED", "该建筑已满级或不可升级");
        }
        Plan plan = planner.plan(new Request(currentLevels(), state.furnitureLevels(),
                Map.of(buildingId, current + 1L), state.busyBuildings(),
                ResourceAvailability.ALWAYS, BootstrapPlanner.DEFAULT_MAX_ACTIONS));
        if (plan.actions().isEmpty()) {
            Map<String, Object> result = actionResult("WAITING",
                    plan.blockedReason().isEmpty() ? "该建筑当前没有可执行动作" : plan.blockedReason());
            result.put("blockedReason", plan.blockedReason());
            return result;
        }
        return executeFirstAction(cfg, plan);
    }

    // ================= 改名 =================

    private Optional<Map<String, Object>> renameIfNeeded(BootstrapConfig cfg) throws Exception {
        String target = cfg.roleName() == null ? "" : cfg.roleName().trim();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        String current = state.roleName();
        if (target.equals(current) || target.equals(renamedName)) {
            renameError = "";
            return Optional.empty();
        }
        try {
            requestModifyName(target, false);
        } catch (ProtocolException exception) {
            if (exception.code() == ITEM_NOT_ENOUGH) {
                // 钻石不足改用钻石重试（对齐原版：51 → use_diamond=true 再试一次）
                try {
                    requestModifyName(target, true);
                } catch (ProtocolException retry) {
                    renameError = renameRejectionMessage(target, retry);
                    return Optional.empty();
                }
            }
            if (exception.code() == NAME_SAME_AS_BEFORE) {
                renamedName = target;
                renameError = "";
                return Optional.empty();
            }
            renameError = renameRejectionMessage(target, exception);
            return Optional.empty();
        }
        renamedName = target;
        renameError = "";
        Map<String, Object> result = actionResult("STARTED", "已修改角色名称为 " + target);
        result.put("rename", Map.of("from", current, "to", target));
        return Optional.of(result);
    }

    private void requestModifyName(String name, boolean useDiamond) throws Exception {
        request(MODIFY_NAME_PROTOCOL, Map.of("name", name, "use_diamond", useDiamond));
    }

    private static String renameRejectionMessage(String target, ProtocolException exception) {
        String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                ? String.valueOf(exception) : exception.getMessage();
        return "无法把角色名改为「" + target + "」：错误码 " + exception.code() + "：" + detail;
    }

    // ================= 执行首个动作 =================

    private Map<String, Object> executeFirstAction(BootstrapConfig cfg, Plan plan) throws Exception {
        Action action = plan.actions().get(0);
        Map<String, Object> preparation = prepareResources(cfg, action);
        if (action.usesQueue() && !cfg.instantDiamond()) {
            return actionResult("MANUAL", "下一步 " + action.buildingName() + " " + action.toLevel()
                    + " 级需要等待完成，请开启钻石即时升级");
        }
        long queueId = action.usesQueue() ? state.idleQueueId() : 0L;
        if (action.usesQueue() && queueId <= 0L) {
            return actionResult("WAITING", "当前没有空闲建筑队列");
        }
        GameMessage response;
        try {
            response = request(protocol(action), payload(cfg, action, queueId));
        } catch (ProtocolException exception) {
            if (exception.code() != ITEM_NOT_ENOUGH) {
                return upgradeRejected(action, exception, preparation);
            }
            // 51：背包资源包补资源后重试一次（M4b 实装 usePacks；商店未实现，重试仍失败走 WAITING 文案）
            preparation.putAll(topUpResources(cfg, action));
            try {
                response = request(protocol(action), payload(cfg, action, queueId));
            } catch (ProtocolException retryException) {
                return upgradeRejected(action, retryException, preparation);
            }
        }
        if (action.kind() == ActionKind.UPGRADE_BUILDING) {
            if (cfg.instantDiamond()) {
                state.markedUpgraded(action.buildingId(), action.toLevel());
            } else {
                state.markedBusy(action.buildingId());
            }
        } else {
            long appliedLevel = ProtocolValueSupport.number(
                    content(response).getOrDefault("lv", 0L));
            state.markedFurniture(action.buildingId(), action.furnitureId(),
                    appliedLevel > 0L ? appliedLevel : action.toLevel());
        }
        FileLog.info("[起号] 已请求%s（instantDiamond=%b）", action.label(), cfg.instantDiamond());
        Map<String, Object> result = actionResult("STARTED",
                "已请求" + action.label() + backpackUseSummary(preparation));
        result.put("step", stepView(action));
        result.put("responseCode", responseCode(response));
        result.put("preparation", preparation);
        return result;
    }

    /** 升级被服务端拒绝的分支文案（806/119/51/120/其他，逐字对齐原版 upgradeRejected）。 */
    private Map<String, Object> upgradeRejected(Action action, ProtocolException exception,
                                                Map<String, Object> preparation) {
        int code = exception.code();
        if (code == CONDITION_BUILDING_LEVEL_NOT_ENOUGH) {
            Map<String, Object> result = actionResult("RESYNC_REQUIRED",
                    "服务端判定前置建筑等级不足，本地建筑等级可能尚未同步，请等待全量同步后重试");
            result.put("step", stepView(action));
            result.put("errorCode", code);
            result.put("sync", Map.of("buildings", state.buildingsReady(), "queues", state.queuesReady()));
            return result;
        }
        if (code == BUILDING_EXP_NOT_ENOUGH) {
            Map<String, Object> result = actionResult("EXP_BLOCKED",
                    action.buildingName() + " 的家具经验不足，已改为先补齐家具再升级建筑");
            result.put("step", stepView(action));
            result.put("errorCode", code);
            result.put("blockedBuildingId", action.buildingId());
            return result;
        }
        if (code == ITEM_NOT_ENOUGH) {
            Map<String, Object> result = actionResult("WAITING",
                    "资源或道具不足，等待补充后重试：" + action.label() + preparationSummary(preparation));
            result.put("step", stepView(action));
            result.put("errorCode", code);
            result.put("preparation", preparation == null ? Map.of() : Map.copyOf(preparation));
            return result;
        }
        if (code == BUILDING_HAS_NOT_FURNITURE) {
            Map<String, Object> result = actionResult("RESYNC_REQUIRED",
                    "服务端判定建筑缺少家具，等待家具数据同步后重试");
            result.put("step", stepView(action));
            result.put("errorCode", code);
            return result;
        }
        Map<String, Object> result = actionResult("FAILED",
                "升级被服务端拒绝：" + (exception.getMessage() == null ? String.valueOf(exception)
                        : exception.getMessage()));
        result.put("step", stepView(action));
        result.put("errorCode", code);
        return result;
    }

    /**
     * 执行前置资源准备（领奖占位）。M4b 边界：领奖与商店未实现，仅透出提示不阻塞；
     * 背包资源包是 51 被拒后的补偿动作（见 {@link #executeFirstAction} 的 51 分支），
     * 不在首次请求前预开——否则重试路径的补包结果会把实际开包记录覆盖成空。
     */
    private Map<String, Object> prepareResources(BootstrapConfig cfg, Action action) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (cfg.claimRewards()) {
            // M4b 边界：任务奖励领取未实现，透出提示不阻塞。
            result.put("rewards", Map.of("message", "任务奖励领取将在后续版本提供"));
        }
        return result;
    }

    /**
     * 资源补给（仅 51 被拒后的补偿路径调用，见 {@link #executeFirstAction}）：
     * 背包资源包（M4b 实装）+ 统帅商店（保持占位不实现，原版默认 false）。
     */
    private Map<String, Object> topUpResources(BootstrapConfig cfg, Action action) throws Exception {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        List<Cost> costs = action.costs();
        if (costs.isEmpty() || action.buildingId() == HERO_HALL_ID) {
            return result;
        }
        result.put("backpack", useBackpackResourcePacks(costs));
        return result;
    }

    /**
     * 背包资源包开启（M4b 实装，语义对齐原版 DailyBackpackResourceSupport.usePacks）：
     * 对每个缺口按白名单包序列（单价升序、普通包先于安全包）依次 {@code req_use_item}，
     * 直到资源够或包用尽；单包被服务端拒绝吞掉换下一包。资源计数含安全资源
     * （normal + safe，原版 totalResourceCount 口径），使用成功后本地乐观扣物品/加资源，
     * 等待下一轮 loaddata 校准。背包未同步（loaddata 无背包键）返回 ready=false + 原占位文案。
     */
    private Map<String, Object> useBackpackResourcePacks(List<Cost> costs) throws Exception {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (costs == null || costs.isEmpty()) {
            result.put("ready", true);
            result.put("used", List.of());
            return result;
        }
        if (!state.backpackInitialized()) {
            result.put("ready", false);
            result.put("message", "背包尚未同步，暂不使用资源包");
            return result;
        }
        ArrayList<Map<String, Object>> used = new ArrayList<>();
        for (Cost cost : costs) {
            long deficit = cost.amount() - totalResourceCount(cost.id());
            if (deficit <= 0L) continue;
            for (ResourcePack pack : packsFor(cost.id())) {
                if (deficit <= 0L) break;
                long stock = state.itemCount(pack.itemId());
                if (stock <= 0L) continue;
                long needed = (deficit + pack.amount() - 1L) / pack.amount();
                long useCount = Math.min(stock, Math.min(needed, MAX_USE_COUNT));
                try {
                    request(USE_ITEM_PROTOCOL, pack.payload(useCount));
                } catch (ProtocolException exception) {
                    FileLog.warn("[起号] 使用资源包被拒绝 itemId=%d code=%d",
                            pack.itemId(), exception.code());
                    continue;
                }
                state.markedItemUsed(pack.itemId(), useCount);
                state.markedResourceGained(pack.resourceId(), useCount * pack.amount());
                deficit -= useCount * pack.amount();
                LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
                entry.put("itemId", pack.itemId());
                entry.put("name", pack.name());
                entry.put("count", useCount);
                entry.put("resourceId", pack.resourceId());
                entry.put("amount", useCount * pack.amount());
                used.add(entry);
            }
        }
        result.put("ready", true);
        result.put("used", used);
        return result;
    }

    // ================= 情报（熔炉 ≥7 挂点，对齐原版 claimIntelligenceRewards/fightIdleIntelligence） =================

    /**
     * 情报领取：拉取推送构建事件表 → 对 status=2（已可领）逐个 {@code req_intelligence_receive}
     * （成功或 2501 视为处理完毕；其他错误码终止本轮）→ 有领取返回 STARTED「已领取 N 个情报任务奖励」；
     * 无领取转 {@link #fightIdleIntelligence}。推送不可得（{@link ProtocolPort#notify} 全空）
     * 返回 empty 降级跳过——情报列表无请求-响应协议，只能来自推送（真实轮以
     * {@code notify_intelligence_info/events/del} 实际形状校准）。
     */
    private Optional<Map<String, Object>> claimIntelligenceRewards() throws Exception {
        Map<Long, Map<String, Object>> events = intelEvents();
        if (events == null) {
            return Optional.empty();
        }
        ArrayList<Long> claimedIds = new ArrayList<>();
        for (Map<String, Object> event : events.values()) {
            long id = number(event.get("id"));
            long status = event.containsKey("status") ? number(event.get("status")) : 1L;
            if (id <= 0L || status != 2L) continue;
            try {
                request(INTEL_RECEIVE_PROTOCOL, Map.of("id", id));
                claimedIds.add(id);
            } catch (ProtocolException exception) {
                if (exception.code() == INTEL_EVENT_NOT_FOUND) {
                    continue; // 已领过/已处理：跳过不计数（对齐原版 remove+continue）
                }
                FileLog.warn("[起号] 领取情报奖励被拒绝 id=%d code=%d", id, exception.code());
                break;
            }
        }
        if (!claimedIds.isEmpty()) {
            Map<String, Object> result = actionResult("STARTED",
                    "已领取 " + claimedIds.size() + " 个情报任务奖励");
            result.put("intelligenceClaimed", List.copyOf(claimedIds));
            return Optional.of(result);
        }
        return fightIdleIntelligence(events);
    }

    /**
     * 空闲情报快打：取第一个 status=1 事件，英雄大厅（318）等级 &gt; 0 视为小队就绪
     * （任务书简化判定：真实英雄编队由游戏端默认小队兜底，fight_heros 传满槽占位 0），
     * {@code req_intelligence_start_battle} + {@code req_intelligence_end_battle} 顺序快打。
     * 失败吞掉返回 empty（不阻塞建筑主线）。成功文案对齐原版
     * 「已快速完成 1 个英雄之旅情报（客户端默认小队）」。
     */
    private Optional<Map<String, Object>> fightIdleIntelligence(Map<Long, Map<String, Object>> events) {
        Map<String, Object> target = null;
        for (Map<String, Object> event : events.values()) {
            long status = event.containsKey("status") ? number(event.get("status")) : 1L;
            if (status == 1L) {
                target = event;
                break;
            }
        }
        if (target == null) {
            return Optional.empty();
        }
        long id = number(target.get("id"));
        if (id <= 0L || state.level(HERO_HALL_ID) <= 0L) {
            return Optional.empty();
        }
        Map<String, Object> startPayload = Map.of("id", id,
                "fight_heros_data", Map.of("source", 1L,
                        "fight_heros", List.of(0L, 0L, 0L, 0L, 0L)));
        try {
            request(INTEL_START_BATTLE_PROTOCOL, startPayload);
            request(INTEL_END_BATTLE_PROTOCOL, Map.of("id", id));
        } catch (ProtocolException exception) {
            FileLog.warn("[起号] 发起情报战斗被拒绝 id=%d code=%d", id, exception.code());
            return Optional.empty();
        } catch (Exception exception) {
            FileLog.warn("[起号] 发起情报战斗失败 id=%d error=%s", id,
                    exception.getMessage() == null ? String.valueOf(exception) : exception.getMessage());
            return Optional.empty();
        }
        Map<String, Object> result = actionResult("STARTED",
                "已快速完成 1 个英雄之旅情报（客户端默认小队）");
        result.put("intelligenceFought", id);
        return Optional.of(result);
    }

    /**
     * 情报事件表构建：{@code collectNotifies} 收推送 → notify_intelligence_info 全量 +
     * notify_intelligence_events 增量合并（同 id 后到字段覆盖）→ events_del 删除。
     * 全部键都无推送返回 null（= 快照未初始化，调用方降级跳过）。
     */
    private Map<Long, Map<String, Object>> intelEvents() {
        port.collectNotifies(2500);
        Map<String, Object> info = port.notify(NOTIFY_INTEL_INFO);
        Map<String, Object> delta = port.notify(NOTIFY_INTEL_EVENTS);
        if (info == null && delta == null) {
            return null;
        }
        Map<Long, Map<String, Object>> events = new LinkedHashMap<>();
        mergeIntelEvents(info, events);
        mergeIntelEvents(delta, events);
        Map<String, Object> del = port.notify(NOTIFY_INTEL_DEL);
        if (del != null) {
            for (Object rawId : rawValues(del.get("ids"))) {
                long id = number(rawId);
                if (id > 0L) events.remove(id);
            }
        }
        return events;
    }

    /** 增量合并一批情报事件条目（对齐 ServerFieldTasks.mergeIntelEvents 的字段覆盖语义）。 */
    private static void mergeIntelEvents(Map<String, Object> content,
                                         Map<Long, Map<String, Object>> events) {
        if (content == null) return;
        for (Map<String, Object> raw : maps(content.get("events"))) {
            long id = number(raw.get("id"));
            if (id <= 0L) continue;
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> prev = events.get(id);
            if (prev != null) merged.putAll(prev);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() != null) merged.put(e.getKey(), e.getValue());
            }
            merged.put("id", id);
            events.put(id, merged);
        }
    }

    /** Map → values；List → 原样（用于形状不固定的 ids 字段，对齐 ServerFieldTasks.rawValues）。 */
    private static List<Object> rawValues(Object raw) {
        if (raw instanceof Map) return new ArrayList<>(((Map<?, ?>) raw).values());
        if (raw instanceof List) return new ArrayList<>((List<?>) raw);
        return new ArrayList<>();
    }

    // ================= 背包资源包白名单（对齐原版 DailyBackpackResourceCatalog） =================

    /**
     * 资源 id → 安全资源 id（原版 safeResourceId：生肉 102→192 / 木材 103→193 /
     * 煤矿 104→194 / 铁矿 105→195；白名单外返回 0）。
     */
    private static long safeResourceId(long resourceId) {
        return switch ((int) resourceId) {
            case 102 -> 192L;
            case 103 -> 193L;
            case 104 -> 194L;
            case 105 -> 195L;
            default -> 0L;
        };
    }

    /** 资源计数（原版 totalResourceCount 口径：普通资源 + 同名安全资源）。 */
    private long totalResourceCount(long resourceId) {
        long total = Math.max(0L, state.resourceCount(resourceId));
        long safeId = safeResourceId(resourceId);
        return safeId > 0L ? total + Math.max(0L, state.resourceCount(safeId)) : total;
    }

    /** 资源名（白名单内；白名单外空串）。 */
    private static String resourceLabel(long resourceId) {
        return switch ((int) resourceId) {
            case 102, 192 -> "生肉";
            case 103, 193 -> "木材";
            case 104, 194 -> "煤矿";
            case 105, 195 -> "铁矿";
            default -> "";
        };
    }

    /**
     * 目标资源的可用资源包序列（原版 packs() 同规则）：固定资源包 + 资源自选箱，
     * 排除随机补给箱；单价升序、普通包先于安全包、包 id 升序。
     *
     * <p>固定包 id 规则（原版 buildItems）：普通包段 100011/100021/100031/100041、
     * 安全包段 100111/100121/100131/100141（肉/木/煤/铁），段内 +0..+4 对应
     * 100/1000/10000/100000/1000000 五档；自选箱段 100101..100103（普通）、
     * 100104..100106（安全），基准量 10000/100000/1000000（煤矿 ÷5、铁矿 ÷20）。
     */
    private static List<ResourcePack> packsFor(long resourceId) {
        long safeId = safeResourceId(resourceId);
        boolean known = resourceId >= 102L && resourceId <= 105L;
        if (!known && safeId <= 0L) return List.of();
        String label = resourceLabel(resourceId);
        ArrayList<ResourcePack> packs = new ArrayList<>();
        long[] amounts = {100L, 1000L, 10000L, 100000L, 1000000L};
        for (int tier = 0; tier < amounts.length; ++tier) {
            long amount = amounts[tier];
            if (known) {
                packs.add(new ResourcePack(normalPackBase(resourceId) + tier,
                        amount + label, resourceId, amount, 0, false));
            }
            if (safeId > 0L) {
                packs.add(new ResourcePack(safePackBase(safeId) + tier,
                        amount + label + "（安全）", safeId, amount, 0, false));
            }
        }
        long[] boxBaseAmounts = {10000L, 100000L, 1000000L};
        for (int tier = 0; tier < boxBaseAmounts.length; ++tier) {
            long base = boxBaseAmounts[tier];
            if (known) {
                packs.add(new ResourcePack(100101L + tier, base + "资源自选箱",
                        resourceId, boxAmount(resourceId, base), tier + 1, true));
            }
            if (safeId > 0L) {
                packs.add(new ResourcePack(100104L + tier, base + "安全资源自选箱",
                        safeId, boxAmount(safeId, base), tier + 1, true));
            }
        }
        packs.sort((a, b) -> {
            if (a.amount() != b.amount()) return Long.compare(a.amount(), b.amount());
            if (a.selectable() != b.selectable()) return Boolean.compare(a.selectable(), b.selectable());
            return Long.compare(a.itemId(), b.itemId());
        });
        return List.copyOf(packs);
    }

    /** 普通固定包段基址（肉 100011 / 木 100021 / 煤 100031 / 铁 100041）。 */
    private static long normalPackBase(long resourceId) {
        return switch ((int) resourceId) {
            case 102 -> 100011L;
            case 103 -> 100021L;
            case 104 -> 100031L;
            case 105 -> 100041L;
            default -> 0L;
        };
    }

    /** 安全固定包段基址（肉 100111 / 木 100121 / 煤 100131 / 铁 100141）。 */
    private static long safePackBase(long resourceId) {
        return switch ((int) resourceId) {
            case 192 -> 100111L;
            case 193 -> 100121L;
            case 194 -> 100131L;
            case 195 -> 100141L;
            default -> 0L;
        };
    }

    /** 自选箱内该资源的实际量（原版 selectBoxes：煤矿 ÷5、铁矿 ÷20，其余足额）。 */
    private static long boxAmount(long resourceId, long baseAmount) {
        return switch ((int) resourceId) {
            case 104, 194 -> baseAmount / 5L;
            case 105, 195 -> baseAmount / 20L;
            default -> baseAmount;
        };
    }

    /**
     * 背包资源包条目（对齐原版 BackpackResourcePack）：{@code payload} 区分固定包
     * {@code {id,count}} 与自选箱 {@code {id,count,extra:{option,immediate_use:true}}}。
     */
    private record ResourcePack(long itemId, String name, long resourceId, long amount,
                                int option, boolean selectable) {
        Map<String, Object> payload(long count) {
            if (!selectable) {
                return Map.of("id", itemId, "count", count);
            }
            return Map.of("id", itemId, "count", count,
                    "extra", Map.of("option", option, "immediate_use", true));
        }
    }

    // ================= 联盟 =================

    /** 联盟名精确匹配（name/abbr 唯一命中）。 */
    public Map<String, Object> resolveAlliance(String keyword) throws Exception {
        String key = keyword == null ? "" : keyword.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("请填写联盟名称或简称");
        }
        if (key.length() > 16) {
            throw new IllegalArgumentException("联盟名称或简称最长 16 个字符");
        }
        GameMessage response = request(ALLIANCE_SEARCH, Map.of("key", key));
        ArrayList<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> alliance : maps(content(response).get("alliances"))) {
            String name = text(alliance.get("name"));
            String abbr = text(alliance.get("abbr"));
            if (!name.equals(key) && !abbr.equals(key)) continue;
            matches.add(allianceView(alliance));
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", key);
        result.put("matches", matches);
        result.put("count", matches.size());
        if (matches.size() == 1) {
            result.put("allianceId", matches.get(0).get("id"));
            result.put("unique", true);
        } else {
            result.put("allianceId", 0L);
            result.put("unique", false);
        }
        return result;
    }

    /** 按配置加入联盟（公开直加 / 私密按开关申请；文案与校验逐字对齐原版 joinAlliance）。 */
    public Map<String, Object> joinAlliance() throws Exception {
        BootstrapConfig cfg = requireEnabled();
        String keyword = cfg.allianceName() == null ? "" : cfg.allianceName().trim();
        if (keyword.isEmpty()) {
            throw new IllegalStateException("尚未配置联盟名称或简称");
        }
        Map<String, Object> resolved = resolveAlliance(keyword);
        if (!Boolean.TRUE.equals(resolved.get("unique"))) {
            throw new IllegalStateException("联盟名称或简称未匹配到唯一联盟，请检查配置");
        }
        long allianceId = ProtocolValueSupport.number(resolved.get("allianceId"));
        Map<String, Object> alliance = maps(resolved.get("matches")).get(0);
        boolean publicRecruit = booleanValue(alliance.get("publicRecruit"));
        if (!publicRecruit && !cfg.autoApplyWhenPrivate()) {
            throw new IllegalStateException("目标联盟非公开招募，请开启自动申请后再执行");
        }
        String protocol = publicRecruit ? ALLIANCE_JOIN : ALLIANCE_APPLY_JOIN;
        GameMessage response = request(protocol, Map.of("aid", allianceId));
        Map<String, Object> result = actionResult("JOINED",
                publicRecruit ? "已提交加入联盟请求" : "已提交联盟申请");
        result.put("allianceId", allianceId);
        result.put("alliance", alliance);
        result.put("protocol", protocol);
        result.put("responseCode", responseCode(response));
        result.put("demandApply", !publicRecruit);
        return result;
    }

    private Map<String, Object> allianceView(Map<String, Object> alliance) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", ProtocolValueSupport.number(alliance.get("aid")));
        result.put("name", text(alliance.get("name")));
        result.put("abbr", text(alliance.get("abbr")));
        result.put("publicRecruit", booleanValue(alliance.get("public")));
        result.put("memberCount", ProtocolValueSupport.number(alliance.get("count")));
        result.put("memberMax", ProtocolValueSupport.number(alliance.get("member_max")));
        result.put("level", ProtocolValueSupport.number(alliance.get("lv")));
        Map<String, Object> condition = mapOf(alliance.get("condition"));
        result.put("requiredLevel", ProtocolValueSupport.number(condition.get("level")));
        result.put("requiredPower", ProtocolValueSupport.number(condition.get("power")));
        return result;
    }

    // ================= 建号（M4a 直通实现） =================

    /**
     * 在目标区服建号并登录（对齐原版 createRole 直通：{@code engine.login(fpid, sdkToken,
     * serverId, extraParams)}，extraParams 并入 loginauth 载荷，建号传 {@code is_create=true}）。
     *
     * <p>serverMismatch 判定对齐原版 verifyLoginTarget：期望 uid &gt; 0 且落地 uid 不一致 →
     * 抛「游戏账号登录到了其他角色…已中止登录」（文案逐字）。
     */
    public LoginOutcome createRole(WjdrEngine engine, String fpid, String sdkToken,
                                   long serverId, long targetUid, String displayName) throws Exception {
        if (serverId <= 0L) {
            throw new IllegalArgumentException("请选择目标区服");
        }
        LinkedHashMap<String, Object> extraParams = new LinkedHashMap<>();
        extraParams.put("is_create", true);
        if (targetUid > 0L) {
            extraParams.put("uid", targetUid);
        }
        if (displayName != null && !displayName.isBlank()) {
            extraParams.put("name", displayName.trim());
        }
        WjdrEngine.LoginResult r = engine.login(fpid, sdkToken, serverId, extraParams);
        long uid = r.uid;
        long landedServerId = r.serverId;
        if (targetUid > 0L && uid != targetUid) {
            throw new IllegalStateException("游戏账号登录到了其他角色：期望 uid=" + targetUid
                    + describeServer(serverId) + "，实际 uid=" + uid + describeServer(landedServerId)
                    + "，已中止登录。请检查该角色的区服资料后重试");
        }
        String message = "已在该区服创建新角色并登录";
        FileLog.info("[起号] 建号成功 uid=%d server=%d", uid, landedServerId);
        return new LoginOutcome(uid, landedServerId, r.authContent,
                serverId > 0L && landedServerId > 0L && landedServerId != serverId, message);
    }

    /** 建号便捷重载（不钉扎 uid）。 */
    public LoginOutcome createRole(WjdrEngine engine, String fpid, String sdkToken,
                                   long serverId, String displayName) throws Exception {
        return createRole(engine, fpid, sdkToken, serverId, 0L, displayName);
    }

    private static String describeServer(long serverId) {
        return serverId > 0L ? "（区服 " + serverId + "）" : "";
    }

    // ================= 私有：规划/协议/视图 =================

    private Plan planNext(BootstrapConfig cfg, int maxActions) {
        return planner.plan(new Request(currentLevels(), state.furnitureLevels(),
                Map.of(FURNACE_ID, (long) cfg.targetFurnaceLevel()), state.busyBuildings(),
                ResourceAvailability.ALWAYS, maxActions));
    }

    private Map<Long, Long> currentLevels() {
        return state.buildingLevels();
    }

    private static String protocol(Action action) {
        return switch (action.kind()) {
            case CREATE_FURNITURE -> CREATE_FURNITURE;
            case UPGRADE_FURNITURE -> UPGRADE_FURNITURE;
            case UPGRADE_BUILDING -> UPGRADE_BUILDING;
        };
    }

    private static Map<String, Object> payload(BootstrapConfig cfg, Action action, long queueId) {
        return switch (action.kind()) {
            case CREATE_FURNITURE -> Map.of("bid", action.buildingId(), "conf_fid", action.furnitureId());
            case UPGRADE_FURNITURE -> Map.of("bid", action.buildingId(), "conf_fid", action.furnitureId(),
                    "add_lv", Math.max(1L, action.toLevel() - action.fromLevel()));
            case UPGRADE_BUILDING -> Map.of("bid", action.buildingId(), "use_diamond", cfg.instantDiamond(),
                    "qid", queueId);
        };
    }

    /** 发协议并把响应 code != 0 转为 {@link ProtocolException}（对齐原版 GameProtocolException）。 */
    private GameMessage request(String proto, Map<String, Object> args) throws Exception {
        GameMessage response = port.request(proto, args);
        Map<String, Object> content = content(response);
        int code = (int) ProtocolValueSupport.number(content.get("code"));
        if (code != 0) {
            throw new ProtocolException(code, proto + " 返回错误码 " + code);
        }
        return response;
    }

    private static Map<String, Object> content(GameMessage response) {
        return response == null || response.getContent() == null
                ? Map.of() : response.getContent();
    }

    private long responseCode(GameMessage response) {
        return ProtocolValueSupport.number(content(response).get("code"));
    }

    private Map<String, Object> actionResult(String status, String message) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> stepView(Action action) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("buildingId", action.buildingId());
        result.put("buildingName", action.buildingName());
        result.put("furnitureId", action.furnitureId());
        result.put("fromLevel", action.fromLevel());
        result.put("toLevel", action.toLevel());
        result.put("kind", action.kind().name());
        result.put("action", switch (action.kind()) {
            case CREATE_FURNITURE -> "CREATE_FURNITURE";
            case UPGRADE_FURNITURE -> "UPGRADE_FURNITURE";
            case UPGRADE_BUILDING -> action.fromLevel() <= 0L ? "BUILD" : "UPGRADE";
        });
        result.put("costs", action.costs().stream()
                .map(cost -> Map.of("id", cost.id(), "amount", cost.amount())).toList());
        return result;
    }

    /** 建筑视图（对齐原版 buildingView：只展示已建建筑与熔炉族，英雄大厅单独追加）。 */
    private List<Map<String, Object>> buildingView() {
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Catalog.TownBuilding definition : catalog.townBuildings()) {
            long level = state.level(definition.id());
            if (level <= 0L && !definition.name().contains("熔炉")) continue;
            result.add(buildingEntry(definition.id(), definition.name(), level));
        }
        if (catalog.supports(HERO_HALL_ID)) {
            result.add(buildingEntry(HERO_HALL_ID, catalog.buildingName(HERO_HALL_ID),
                    state.level(HERO_HALL_ID)));
        }
        return result;
    }

    private Map<String, Object> buildingEntry(long id, String name, long level) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("level", level);
        result.put("busy", state.busy(id));
        return result;
    }

    /** 资源视图：木材 103 / 煤 104 / 铁 105 / 钻石 101（loaddata 背包资源深找）。 */
    private Map<String, Object> resourceView() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("wood", state.resourceCount(103L));
        result.put("coal", state.resourceCount(104L));
        result.put("iron", state.resourceCount(105L));
        result.put("diamond", state.resourceCount(101L));
        return result;
    }

    // ================= runUntil 辅助 =================

    private boolean idleReached(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!state.buildingsReady() || !state.queuesReady()) {
                sleepQuietly(200L);
                continue;
            }
            if (state.busyBuildings().isEmpty()) {
                return true;
            }
            sleepQuietly(300L);
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stopSummary(int started, String stopMessage) {
        String detail = stopMessage == null ? "" : stopMessage.trim();
        if (started == 0) {
            return detail.isEmpty() ? "没有执行任何升级，请检查状态" : detail;
        }
        return detail.isEmpty() ? "已连续执行 " + started + " 步"
                : "已连续执行 " + started + " 步；" + detail;
    }

    // ================= runState / 文案 =================

    private String runState() {
        if (!config.enabled()) {
            return "DISABLED";
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return "WAITING_SYNC";
        }
        if (state.furnaceLevel() >= config.targetFurnaceLevel()) {
            return "LEVEL_COMPLETED";
        }
        return "READY";
    }

    private String runMessage() {
        if (!config.enabled()) {
            return "主开关未开启，起号流程不会执行";
        }
        if (!state.buildingsReady() || !state.queuesReady()) {
            return "等待建筑与队列全量状态同步";
        }
        if (state.furnaceLevel() >= config.targetFurnaceLevel()) {
            return "大熔炉已到达目标等级";
        }
        return "可以执行下一步";
    }

    private BootstrapConfig requireEnabled() {
        if (!config.enabled()) {
            throw new IllegalStateException("起号模块主开关未开启");
        }
        return config;
    }

    // ================= 防御式解析工具 =================

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /** 防御式数值解析（协议/推送字段形状不定，统一走 {@link ProtocolValueSupport#number}）。 */
    private static long number(Object raw) {
        return ProtocolValueSupport.number(raw);
    }

    private static boolean booleanValue(Object raw) {
        if (raw instanceof Boolean value) return value;
        String text = text(raw);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map)) return Map.of();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object v : (List<Object>) raw) {
                Map<String, Object> m = mapOf(v);
                if (!m.isEmpty()) out.add(m);
            }
        } else if (raw instanceof Map) {
            for (Object v : ((Map<?, ?>) raw).values()) {
                Map<String, Object> m = mapOf(v);
                if (!m.isEmpty()) out.add(m);
            }
        }
        return out;
    }

    /** 预置准备结果摘要（对齐原版 preparationSummary）。 */
    private static String preparationSummary(Map<String, Object> preparation) {
        if (preparation == null || preparation.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (preparation.containsKey("rewards")) {
            parts.add("已领取任务奖励");
        }
        if (!backpackUsedItems(preparation).isEmpty()) {
            parts.add("已开启背包资源包 " + backpackUsedItems(preparation).size() + " 项");
        }
        if (preparation.containsKey("purchase")) {
            parts.add("已尝试统帅商店补资源");
        }
        return parts.isEmpty() ? "" : "（" + String.join("；", parts) + "）";
    }

    private static String backpackUseSummary(Map<String, Object> preparation) {
        List<?> used = backpackUsedItems(preparation);
        return used.isEmpty() ? "" : "（已开启背包资源包 " + used.size() + " 项）";
    }

    private static List<?> backpackUsedItems(Map<String, Object> preparation) {
        if (preparation == null || !(preparation.get("backpack") instanceof Map)) {
            return List.of();
        }
        Object used = mapOf(preparation.get("backpack")).get("used");
        return used instanceof List ? (List<?>) used : List.of();
    }
}
