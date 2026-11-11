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
    /** 产出资源领取（新版 BootstrapTutorialService.PRODUCED_NEED_UPDATE）：新号初始资源靠它进背包。 */
    static final String PRODUCED_NEED_UPDATE = "req_produced_resource_need_update";
    /** 邮件大类上限（实测常见 1-6：系统/联盟/活动等，超出按失败忽略）。 */
    static final long MAIL_FIRST_TYPE_MAX = 6L;
    /** 邮件协议（新版 BootstrapMailService 同值）。 */
    static final String MAIL_COVER_LIST = "req_mail_cover_list";
    static final String MAIL_BATCH_READ = "req_batch_mail_read";
    /** 成就/里程碑协议（新版 BootstrapMailService 同值）。 */
    static final String ACHIEVEMENT_INFO = "req_achievement_info";
    static final String ACHIEVEMENT_REWARD = "req_achievement_get_reward";
    /** 新手引导协议（新版 BootstrapTutorialService.GUIDE_SET_STEP）。 */
    static final String GUIDE_SET_STEP = "req_guide_set_step";
    /** 英雄协议（新版 BootstrapHeroService 同值）。 */
    static final String HERO_SEARCH = "req_hero_search_times";
    static final String HERO_UPGRADE = "req_hero_upgrade";
    static final String HERO_PROMOTE_STAR = "req_hero_promote_star";
    /** 探险战斗协议（新版 BootstrapAdventureService 同值）。 */
    static final String ADVENTURE_BEGIN = "req_begin_adventure_pve";
    static final String ADVENTURE_END = "req_end_adventure_pve";
    /** 探险战斗版本号（新版同值）。 */
    static final long ADVENTURE_BATTLE_VERSION = 26042101L;
    /** 兑换码协议（新版 BootstrapRewardService.EXCHANGE_CDKEY）。 */
    static final String EXCHANGE_CDKEY = "req_exchange_cdkey";
    /** 兑换码固定清单（新版 BootstrapCdkeyCatalog 原样）。 */
    static final java.util.List<String> CDKEYS = java.util.List.of(
            "WJDR666", "WJDR2025", "WOAIWJDR", "TILI520",
            "吴嶮888", "吴嶮666", "金子888", "金子666", "大聪666", "大聪888",
            "JC666", "JC888", "666WJDR2024");
    /** 兑换码前置：熔炉达到该等级才兑换（新版 CDKEY_MIN_FURNACE_LEVEL）。 */
    static final long CDKEY_MIN_FURNACE_LEVEL = 3L;
    /** 背包钻石道具 id（新版 BootstrapRewardService.DIAMOND_ITEM_IDS）：兑换码奖励的钻石以道具形式发放。 */
    static final List<Long> DIAMOND_ITEM_IDS = List.of(100001L, 100002L, 100003L, 100004L, 100005L);
    /** 兑换码错误码：稍后重试（新版 7208，5 分钟）。 */
    static final int CDKEY_RETRY_LATER = 7208;
    /** 兑换码错误码：永久失败（已兑换过/无效码），不再重试。 */
    static final java.util.Set<Integer> CDKEY_PERMANENT_FAILURES =
            java.util.Set.of(7201, 7202, 7203, 7204, 7205, 7206, 7207, 7209, 7210);

    /**
     * 幸存者上岗（真实轮 2026-11-11 网页版实证）：新号有若干闲置幸存者，
     * 委任他们到工作位后资源才开始产出；不委任则资源恒为 0，任何建造都会被 51 拒绝。
     * 协议取自游戏 c2s sproto：{@code req_survivor_work_start{sid}}。
     */
    static final String SURVIVOR_WORK_START = "req_survivor_work_start";
    /** 精确委任：派幸存者到指定工位（新版 BootstrapService.SURVIVOR_SEND_OR_FIRED）。 */
    static final String SURVIVOR_SEND_OR_FIRED = "req_survivor_send_or_fired";
    /** 生产建筑（新版 BootstrapTutorialCatalog.PRODUCTION_BUILDINGS）。 */
    static final List<Long> PRODUCTION_BUILDINGS = List.of(302L, 303L, 304L, 305L, 306L, 307L);
    /** 生产工位类型（新版 PRODUCTION_STATION_TYPES）。 */
    static final List<Long> PRODUCTION_STATION_TYPES = List.of(9L, 24L, 30L, 42L);
    /** 探险挂机奖励（原版 DailyAdventureRewardService 同值）：预览 + 领取。 */
    static final String ADVENTURE_PREVIEW = "req_open_hang_up_reward_view";
    static final String ADVENTURE_CLAIM = "req_get_hang_up_reward";
    /** 探险挂机奖励错误码：挂机时长未达领取条件。 */
    static final int ADVENTURE_NOT_READY = 4004;
    /** 任务奖励推送键（原版 BootstrapRewardState 同值）。 */
    static final String NOTIFY_GROWTH_QUESTS = "notify_growthquests";
    static final String NOTIFY_CHAPTER_QUESTS = "notify_chapterquests";
    /** 情报事件列表推送键：全量初始化（对齐 ServerFieldTasks.intel / IntelRound 数据源口径）。 */
    static final String NOTIFY_INTEL_INFO = "notify_intelligence_info";
    /** 情报事件列表推送键：增量合并。 */
    static final String NOTIFY_INTEL_EVENTS = "notify_intelligence_events";
    /** 情报事件列表推送键：删除。 */
    static final String NOTIFY_INTEL_DEL = "notify_intelligence_events_del";
    /** 服务端错误码：情报事件已被处理/不存在（原版 IntelligenceQuestSupport.EVENT_NOT_FOUND_CODE）。 */
    static final int INTEL_EVENT_NOT_FOUND = 2501;
    /** 统帅商店补资源（原版 BootstrapShopService 同值）：商店 id 701、资源包 itemId 与每包资源量。 */
    static final long COMMANDER_SHOP_ID = 701L;
    static final long MEAT_PACK_ITEM_ID = 100113L;
    static final long WOOD_PACK_ITEM_ID = 100123L;
    static final long COAL_PACK_ITEM_ID = 100133L;
    static final long IRON_PACK_ITEM_ID = 100143L;
    static final long PACK_SIZE = 10000L;
    static final String SHOP_INFO = "req_shop_info";
    static final String SHOP_BUY = "req_shop_buy";
    /** 领奖协议（原版 BootstrapRewardService 同值）。 */
    static final String GROWTH_AWARD = "req_get_growthquest_award";
    static final String CHAPTER_QUEST_AWARD = "req_get_chapterquest_award";
    static final String CHAPTER_AWARD = "req_get_chapter_award";
    static final String SEVEN_SIGN_AWARD = "req_seven_sign_one_click_award";
    static final String SIGN_21DAY_AWARD = "req_sign_21day_get_all_reward";
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

        /** 已收到的推送协议名（排障/状态定位用）。 */
        default java.util.Set<String> notifyProtos() {
            return java.util.Set.of();
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

    /** 测试用：暴露内部状态（同包测试）。 */
    BootstrapState stateForTest() {
        return state;
    }
    private BootstrapConfig config = BootstrapConfig.defaults();
    /** 本地家具等级覆盖（跨轮保留：154「已存在」/153「已达上限」标记，避免重复规划）。 */
    private final java.util.Map<String, Long> furnitureOverrides = new java.util.concurrent.ConcurrentHashMap<>();
    /** 英雄动作连发计数（对齐新版 yieldTurn；阈值必须小于每轮步数 STEPS_PER_ROUND=8）。 */
    private int heroBurst;
    /** 已见过的钻石最大值（资源 101 不一定每轮都推送，跨轮取最大值避免读到 0）。 */
    private long knownDiamonds;
    /** 上次已领取的产出资源签名（避免同一批资源反复领取造成假进展）。 */
    private String lastProducedSignature = "";
    /** 已尝试过的兑换码（永久失败或已兑换，避免重复请求）。 */
    private final Set<String> attemptedCdkeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        BootstrapState previous = this.state;
        this.state = BootstrapState.fromLoadData(loadContent);
        // 补齐本轮推送里缺失的字段（资源 101 钻石等只在登录时推一次）
        this.state.mergeLegacyFrom(previous);
        knownDiamonds = Math.max(knownDiamonds, Math.max(0L, this.state.resourceCount(101L)));
        // 重放本地乐观覆盖（2026-11-11 真实轮）：游戏侧对「家具已存在(154)」「家具已达建筑等级上限(153)」
        // 的响应不会改变推送里的家具数据，若每轮重建后不重放，planner 会反复规划同一动作形成死循环。
        for (Map.Entry<String, Long> e : furnitureOverrides.entrySet()) {
            long[] key = parseFurnitureKey(e.getKey());
            if (key != null) {
                state.markedFurniture(key[0], key[1], e.getValue());
            }
        }
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
        // 领取产出资源（最高优先级：新号初始资源只在这里，不领则资源恒 0）
        if (claimProducedResources()) {
            return actionResult("STARTED", "已领取产出资源");
        }
        // 新手引导（新版 runNextStepInternal 第一步）：逐级上报引导步骤
        Map<String, Object> tutorial = tutorialAdvance(null);
        if (tutorial != null) {
            return tutorial;
        }
        // 邮件领奖（新版顺序：情报 → 邮件 → 里程碑 → 英雄）
        int mails = claimMails();
        if (mails > 0) {
            Map<String, Object> result = actionResult("STARTED", "已领取 " + mails + " 封邮件附件");
            result.put("mailCount", mails);
            return result;
        }
        // 成就/里程碑奖励
        int achievements = claimAchievements();
        if (achievements > 0) {
            Map<String, Object> result = actionResult("STARTED", "已领取 " + achievements + " 项成就奖励");
            result.put("achievementCount", achievements);
            return result;
        }
        // 英雄推进（新版顺序：招募/升星/升级；有英雄才有队伍打探险）
        Map<String, Object> hero = heroAdvance();
        if (hero != null) {
            return hero;
        }
        // 探险战斗（新版 BootstrapAdventureService：有队伍才打；失败 30 分钟退避）
        Map<String, Object> adventure = adventureBattle(0L);
        if (adventure != null && "STARTED".equals(adventure.get("status"))) {
            return adventure;
        }
        // 兑换码（新版 BootstrapService：熔炉 ≥3 才兑换，一次性拿大量资源/钻石）
        if (state.furnaceLevel() >= CDKEY_MIN_FURNACE_LEVEL) {
            int cdkeyClaimed = claimCdkeys();
            int diamonds = claimBackpackDiamonds();   // 兑换码奖励的钻石需打开才可用
            if (cdkeyClaimed > 0 || diamonds > 0) {
                Map<String, Object> result = actionResult("STARTED",
                        "已兑换 " + cdkeyClaimed + " 条兑换码，打开背包钻石道具 " + diamonds + " 种");
                result.put("rewardClaimed", true);
                return result;
            }
        }
        // 幸存者委任（必须在建造之前）：网页版实证——不委任则资源恒为 0、建造恒 51 被拒。
        // 每轮检查一次，有新闲置者就上岗；本轮若有委任则直通返回，下一轮再建造。
        if (!state.idleSurvivors().isEmpty()) {
            Map<String, Object> assigned = assignIdleSurvivors();
            if (number(assigned.get("assignedCount")) > 0L) {
                Map<String, Object> result = actionResult("STARTED",
                        String.valueOf(assigned.get("message")));
                result.put("survivors", assigned);
                return result;
            }
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
        FileLog.info("[起号] 已请求{}（instantDiamond={}）", action.label(), cfg.instantDiamond());
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
        // 154：家具服务端已存在（本地未同步）→ 标记 1 级继续，不算失败（新版同语义）
        if (code == 154 && action.kind() == ActionKind.CREATE_FURNITURE) {
            state.markedFurniture(action.buildingId(), action.furnitureId(), 1L);
            furnitureOverrides.put(furnitureKey(action.buildingId(), action.furnitureId()), 1L);
            // 154 时服务端没有可用实例 id：本地不记录实例（furnitureInstanceId 会按公式兜底）
            Map<String, Object> exists = actionResult("STARTED",
                    action.buildingName() + " 的家具 " + action.furnitureId() + " 服务端已存在，按 1 级继续");
            exists.put("step", stepView(action));
            exists.put("responseCode", 154);
            return exists;
        }
        // 153：家具已达当前建筑等级上限（新版 FURNITURE_MAX_LEVEL）→ 标记"已达上限"后等待建筑升级，
        // 否则 planner 会每轮重复规划同一家具升级，形成死循环（实测熔炉卡 3 级 600+ 轮）。
        if (code == 153) {
            long cap = Math.max(0L, state.level(action.buildingId()));
            for (Cost req : catalog.furnitureRequirement(action.buildingId(), cap)) {
                if (req.id() == action.furnitureId()) {
                    cap = Math.max(cap, req.amount());
                }
            }
            if (cap > 0L) {
                state.markedFurniture(action.buildingId(), action.furnitureId(), cap);
                furnitureOverrides.put(furnitureKey(action.buildingId(), action.furnitureId()), cap);
            }
            Map<String, Object> capped = actionResult("WAITING",
                    action.buildingName() + " 的家具 " + action.furnitureId()
                            + " 已达当前建筑等级上限，等待升级 " + action.buildingName());
            capped.put("step", stepView(action));
            capped.put("errorCode", code);
            return capped;
        }
        // 77：建造队列尚未解锁 → 等待，换其它空闲队列
        if (code == 77) {
            Map<String, Object> locked = actionResult("WAITING", "建造队列尚未解锁，已改用其它空闲队列");
            locked.put("step", stepView(action));
            locked.put("responseCode", 77);
            return locked;
        }
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
        // 真实轮（2026-11-10）：新号资源为 0 且背包无资源包，只能靠领任务奖励补资源——
        // 这正是原版 claimRewards 开关的用途（原版 prepareResources → rewards.claimAll）。
        if (cfg.claimRewards()) {
            try {
                result.put("rewards", claimTaskRewards());
            } catch (RuntimeException e) {
                result.put("rewards", Map.of("message", "领奖失败：" + e.getMessage()));
            }
        }
        result.put("backpack", useBackpackResourcePacks(costs));
        // 真实轮（2026-11-10）：背包无资源包时，若开启 buyShopResources 则用钻石从统帅商店补缺资源
        if (cfg.buyShopResources()) {
            try {
                result.put("purchase", buyMissingResources(costs));
            } catch (RuntimeException e) {
                result.put("purchase", Map.of("message", "补资源失败：" + e.getMessage()));
            }
        }
        return result;
    }

    /**
     * 统帅商店补缺资源（语义对齐原版 BootstrapShopService.buyMissingResources）：
     * 按木材/煤矿/铁矿缺口，用 {@code req_shop_info} 取商品与价格 → {@code req_shop_buy} 购买，
     * 受钻石余额与单次 200 包上限约束。价格取 {@code bootstrap/daily-town-shop-catalog.json}。
     */
    Map<String, Object> buyMissingResources(List<Cost> costs) throws Exception {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (costs == null || costs.isEmpty()) {
            result.put("purchases", List.of());
            result.put("message", "当前没有需要补充的资源");
            return result;
        }
        // 缺口（普通 + 安全资源一起算）。顺序对齐新版 BootstrapShopService：
        // 生肉(100113) → 木材(100123) → 煤矿(100133) → 铁矿(100143)，各限 200 份。
        long needMeat = Math.max(0L, missingOf(costs, 102L));
        long needWood = Math.max(0L, missingOf(costs, 103L));
        long needCoal = Math.max(0L, missingOf(costs, 104L));
        long needIron = Math.max(0L, missingOf(costs, 105L));
        if (needMeat <= 0L && needWood <= 0L && needCoal <= 0L && needIron <= 0L) {
            result.put("purchases", List.of());
            result.put("message", "当前没有需要补充的资源");
            return result;
        }
        Map<Long, Long> goodsByItem = shopGoods();
        // 钻石余额：以当前 state 资源 101 为准（101=钻石，与新版 resourceCount(101L) 一致）
        long diamonds = Math.max(knownDiamonds, Math.max(0L, state.resourceCount(101L)));
        if (retryAllowed("shop:diag")) {
            markRetry("shop:diag", 120_000L);
            FileLog.info("[起号] 商店诊断：商品数={} 钻石={} 背包钻石道具种类={} 缺口肉={} 木={} 煤={} 铁={}",
                    String.valueOf(goodsByItem.size()), String.valueOf(diamonds),
                    String.valueOf(diamondItemsInBag().size()),
                    String.valueOf(needMeat), String.valueOf(needWood),
                    String.valueOf(needCoal), String.valueOf(needIron));
        }
        ArrayList<Map<String, Object>> purchases = new ArrayList<>();
        buyForMissing(goodsByItem, MEAT_PACK_ITEM_ID, needMeat, diamonds, purchases);
        buyForMissing(goodsByItem, WOOD_PACK_ITEM_ID, needWood,
                Math.max(0L, diamonds - spentDiamonds(purchases)), purchases);
        buyForMissing(goodsByItem, COAL_PACK_ITEM_ID, needCoal,
                Math.max(0L, diamonds - spentDiamonds(purchases)), purchases);
        buyForMissing(goodsByItem, IRON_PACK_ITEM_ID, needIron,
                Math.max(0L, diamonds - spentDiamonds(purchases)), purchases);
        result.put("purchases", List.copyOf(purchases));
        result.put("diamondSpent", spentDiamonds(purchases));
        result.put("message", purchases.isEmpty()
                ? "当前没有需要补充的资源" : "已补充 " + purchases.size() + " 类资源");
        return result;
    }

    /** 某资源 id 的缺口（普通 + 安全资源合计后的缺口）。 */
    private long missingOf(List<Cost> costs, long resourceId) {
        for (Cost cost : costs) {
            if (cost.id() == resourceId || cost.id() == safeResourceId(resourceId)) {
                return Math.max(0L, cost.amount() - totalResourceCount(resourceId));
            }
        }
        return 0L;
    }

    /** 商店商品：itemId → goodsId（从 req_shop_info 的推送/响应里取）。 */
    /**
     * 商店商品（对齐新版 BootstrapShopService.currentGoods）：
     * {@code req_shop_info{shop_id:701}} → {@code info.goods}（Map，key=槽位）→
     * 每项 {@code {id, conf_id, grid_id, buy_count}}；{@code conf_id} 经目录
     * {@code configId→itemId} 反查得到 itemId，goodsId 取条目 {@code id}。
     */
    private Map<Long, Long> shopGoods() throws Exception {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        Map<String, Object> content = request(SHOP_INFO,
                Map.of("shop_id", COMMANDER_SHOP_ID)).getContent();
        if (content == null) return out;
        if (retryAllowed("shop:raw")) {
            markRetry("shop:raw", 300_000L);
            FileLog.info("[起号] req_shop_info keys={}", String.valueOf(content.keySet()));
        }
        Map<String, Object> info = mapOf(content.get("info"));
        Object goods = info.get("goods") != null ? info.get("goods") : content.get("goods");
        collectShopGoods(goods, out);
        return out;
    }

    /** 遍历 goods（Map/List 均可）：conf_id → itemId，goodsId = 条目 id。 */
    private void collectShopGoods(Object raw, Map<Long, Long> out) {
        if (raw == null) return;
        if (raw instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) raw;
            long goodsId = number(m.get("id"));
            long confId = number(m.get("conf_id"));
            long gridId = number(m.get("grid_id"));
            long itemId = resolveShopItemId(confId, goodsId, gridId);
            if (goodsId > 0L && itemId > 0L) {
                out.put(itemId, goodsId);
                return;
            }
            for (Object v : m.values()) collectShopGoods(v, out);
            return;
        }
        if (raw instanceof Iterable) {
            for (Object v : (Iterable<?>) raw) collectShopGoods(v, out);
        }
    }

    /** conf_id → itemId（对齐新版 resolveItemId：configId 映射优先，回退直接匹配目录 itemId）。 */
    private static long resolveShopItemId(long confId, long goodsId, long gridId) {
        long byConfig = BootstrapShopCatalog.itemIdByConfigId(confId);
        if (byConfig > 0L) return byConfig;
        for (long candidate : new long[]{confId, goodsId, gridId}) {
            if (candidate > 0L && BootstrapShopCatalog.priceOf(candidate) > 0L) return candidate;
        }
        return 0L;
    }


    /** 按缺口购买某资源包（受钻石余额约束，单次最多 200 包）。 */
    private void buyForMissing(Map<Long, Long> goodsByItem, long packItemId, long missing,
                               long remainingDiamonds, List<Map<String, Object>> purchases) throws Exception {
        if (missing <= 0L) return;
        Long goodsId = goodsByItem.get(packItemId);
        long price = catalogPrice(packItemId);
        if (goodsId == null || price <= 0L) return;
        long packs = (missing + PACK_SIZE - 1L) / PACK_SIZE;
        long affordable = Math.min(packs, remainingDiamonds / price);
        if (affordable <= 0L) return;
        affordable = Math.min(affordable, 200L);
        request(SHOP_BUY, Map.of("shop_id", COMMANDER_SHOP_ID, "goods_id", goodsId,
                "count", (int) affordable));
        FileLog.info("[起号] 统帅商店购买 itemId={} 数量={} 花费={}钻",
                String.valueOf(packItemId), String.valueOf(affordable),
                String.valueOf(affordable * price));
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("itemId", packItemId);
        entry.put("packs", affordable);
        entry.put("diamondCost", affordable * price);
        purchases.add(entry);
    }

    /** 商品目录价格（bootstrap/daily-town-shop-catalog.json；缺失返回 0 = 不购买）。 */
    private static long catalogPrice(long itemId) {
        return BootstrapShopCatalog.priceOf(itemId);
    }

    private long spentDiamonds(List<Map<String, Object>> purchases) {
        long total = 0L;
        for (Map<String, Object> p : purchases) total += number(p.get("diamondCost"));
        return total;
    }

    private long requestLong(String what) {
        // 钻石余额：优先 state 资源表 101，其次安全资源 191
        long direct = Math.max(0L, state.resourceCount(101L));
        return direct > 0L ? direct : Math.max(0L, state.resourceCount(191L));
    }

    private static Object firstNonEmpty(Object... values) {
        for (Object v : values) {
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 领取任务奖励（语义对齐原版 BootstrapRewardService.claimAll）：
     * 从推送 {@code notify_growthquests/notify_chapterquests} 累积可领 id →
     * 依次 {@code req_get_growthquest_award{questid}}、{@code req_get_chapterquest_award{questid}}、
     * {@code req_get_chapter_award{chapterid}}（{@code get_flag} 未领者），
     * 再试七日签到与 21 天签到一键领奖。单项失败不影响其它项。
     */
    Map<String, Object> claimTaskRewards() throws Exception {
        List<Long> growth = questIds(notifyValue(NOTIFY_GROWTH_QUESTS, "quests"));
        List<Long> chapterQuests = questIds(notifyValue(NOTIFY_CHAPTER_QUESTS, "quests"));
        List<Long> chapterRewards = unclaimedRewardIds(notifyValue(NOTIFY_CHAPTER_QUESTS, "chapter_award"));
        if (growth.isEmpty() && chapterQuests.isEmpty() && chapterRewards.isEmpty()) {
            // 推送未到，尝试两个一键领奖协议（七日签到/21 天签到）
            growth = List.of();
        }
        ArrayList<String> claimed = new ArrayList<>();
        int failed = 0;
        for (long questId : growth) {
            if (tryClaim(GROWTH_AWARD, Map.of("questid", questId))) claimed.add("成长任务 " + questId);
            else failed++;
        }
        for (long questId : chapterQuests) {
            if (tryClaim(CHAPTER_QUEST_AWARD, Map.of("questid", questId))) claimed.add("章节任务 " + questId);
            else failed++;
        }
        for (long rewardId : chapterRewards) {
            if (tryClaim(CHAPTER_AWARD, Map.of("chapterid", rewardId))) claimed.add("章节宝箱 " + rewardId);
            else failed++;
        }
        for (String protocol : List.of(SEVEN_SIGN_AWARD, SIGN_21DAY_AWARD)) {
            if (tryClaim(protocol, Map.of())) claimed.add(protocol);
        }
        // 探险挂机奖励（网页版 0 基础起号补资源手段之一）：预览刷新 → 尝试领取；
        // 挂机时长未到（4004）视为正常跳过，不算失败。
        Map<String, Object> adventure = claimAdventureHangUp();
        if (Boolean.TRUE.equals(adventure.get("claimed"))) {
            claimed.add("探险挂机奖励");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("claimed", List.copyOf(claimed));
        result.put("failed", failed);
        result.put("adventure", adventure);
        result.put("message", claimed.isEmpty()
                ? "当前没有可领取的任务奖励" : "已领取 " + claimed.size() + " 项奖励");
        return result;
    }

    /**
     * 打开背包里的钻石道具（对齐新版 BootstrapRewardService.claimBackpackDiamonds）。
     *
     * <p>兑换码奖励的钻石不是直接进资源 101，而是以道具 100001-100005 形式落在背包；
     * 必须 {@code req_use_item} 打开才变成可用钻石。实测：不打开时钻石=0，
     * 统帅商店买不了肉/木材（用户要求的两条主资源）。
     *
     * @return 打开的道具种类数
     */
    /** 背包里现存的钻石道具（id → count），诊断用。 */
    Map<Long, Long> diamondItemsInBag() {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        for (long id : DIAMOND_ITEM_IDS) {
            long c = Math.max(0L, state.itemCount(id));             // 对齐新版 itemCount
            if (c > 0L) out.put(id, c);
        }
        return out;
    }

    int claimBackpackDiamonds() {
        int used = 0;
        for (long itemId : DIAMOND_ITEM_IDS) {
            long count = Math.max(0L, state.itemCount(itemId));     // 对齐新版 itemCount
            if (count <= 0L) continue;
            try {
                GameMessage rsp = request("req_use_item", Map.of("id", itemId,
                        "count", (int) Math.min(count, 9999L), "extra", Map.of()));
                Map<String, Object> content = rsp == null || rsp.getContent() == null
                        ? Map.of() : rsp.getContent();
                used++;
                // 诊断：确认 use_item 真实返回（新版按成功处理；实测需核对是否给出钻石增量）
                FileLog.info("[起号] 已打开背包钻石道具 itemId={} count={} 响应={}",
                        String.valueOf(itemId), String.valueOf(count),
                        String.valueOf(content).length() > 200
                                ? String.valueOf(content).substring(0, 200) : String.valueOf(content));
            } catch (Exception e) {
                FileLog.info("[起号] 打开钻石道具失败 itemId={}：{}",
                        String.valueOf(itemId), String.valueOf(e.getMessage()));
            }
        }
        return used;
    }

    /**
     * 领取「产出资源」（对齐新版 BootstrapTutorialService.refreshProducedResources）。
     *
     * <p>真实轮（2026-11-11）实证：新角色的初始资源在 {@code notify_produced_resource_init}
     * 里（木材 103=1000、肉 102=3000），**不在背包**；必须调
     * {@code req_produced_resource_need_update} 才会进背包。不领则木材恒为 0，
     * 任何建造都被 51 拒绝——这是新号起号卡住的直接原因。
     *
     * @return true 表示本轮领取了产出资源
     */
    boolean claimProducedResources() {
        Map<Long, Long> produced = state.producedResources();
        if (produced.isEmpty()) {
            return false;
        }
        if (!retryAllowed("produced")) return false;
        // 去重（2026-11-11 真实轮教训）：游戏对「产出资源」的成功响应不会清零该计数，
        // 若每轮都领就会反复成功（实测 104 次/120 分钟），被 runUntil 当成"有进展"而永不判停滞，
        // 其余建筑动作被挤掉、熔炉卡在 3 级。故：同一批产出资源只领一次（按 id:count 签名去重）。
        String signature = produced.toString();
        if (signature.equals(lastProducedSignature)) {
            markRetry("produced", 60_000L);
            return false;
        }
        try {
            request(PRODUCED_NEED_UPDATE, Map.of());
            lastProducedSignature = signature;
            markRetry("produced", 60_000L);
            FileLog.info("[起号] 已领取产出资源 {} 项：{}",
                    String.valueOf(produced.size()), String.valueOf(produced));
            return true;
        } catch (Exception e) {
            markRetry("produced", 300_000L);
            return false;
        }
    }

    /**
     * 邮件领奖（对齐新版 BootstrapMailService.advance 的核心）：
     * 按大类 {@code first_type} 拉封面 {@code req_mail_cover_list{first_type,beginidx,endidx}}
     * → 对「未读或有附件」的邮件批量 {@code req_batch_mail_read{first_type, mid_list_new}}。
     *
     * @return 本轮处理封数（0 = 无可领邮件）
     */
    int claimMails() {
        int claimed = 0;
        for (long firstType = 1L; firstType <= MAIL_FIRST_TYPE_MAX; firstType++) {
            String typeKey = "mail:type:" + firstType;
            if (!retryAllowed(typeKey)) continue;
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("first_type", firstType);
                payload.put("beginidx", 1L);
                payload.put("endidx", 40L);
                GameMessage cover = request(MAIL_COVER_LIST, payload);
                Map<String, Object> content = cover == null || cover.getContent() == null
                        ? Map.of() : cover.getContent();
                ArrayList<String> ids = new ArrayList<>();
                for (Map<String, Object> mail : maps(content.get("coverlist"))) {
                    Object mid = mail.get("mailid_new");
                    if (mid == null || String.valueOf(mid).trim().isEmpty()) continue;
                    boolean read = number(mail.get("isread")) > 0L;
                    boolean attach = number(mail.get("attach_statue")) == 1L;
                    if (!read || attach) ids.add(String.valueOf(mid).trim());
                }
                if (ids.isEmpty()) continue;
                LinkedHashMap<String, Object> readPayload = new LinkedHashMap<>();
                readPayload.put("first_type", firstType);
                readPayload.put("mid_list_new", List.copyOf(ids));
                request(MAIL_BATCH_READ, readPayload);
                claimed += ids.size();
                clearRetry(typeKey);
                FileLog.info("[起号] 已批量已读并领取 {} 封邮件附件（大类 {}）",
                        String.valueOf(ids.size()), String.valueOf(firstType));
            } catch (Exception e) {
                // 该大类处理不了就退避，避免每轮都请求
                markRetry(typeKey, 300_000L);
            }
        }
        return claimed;
    }

    /**
     * 成就/里程碑奖励（对齐新版 BootstrapMilestoneService 的核心）：
     * {@code req_achievement_info} 拉信息 → 对可领项 {@code req_achievement_get_reward{id}}。
     *
     * @return 本轮领取项数
     */
    int claimAchievements() {
        if (!retryAllowed("achievement")) return 0;
        int claimed = 0;
        try {
            GameMessage info = request(ACHIEVEMENT_INFO, Map.of());
            Map<String, Object> content = info == null || info.getContent() == null
                    ? Map.of() : info.getContent();
            Object raw = content.get("list");
            if (raw == null) raw = content.get("achievements");
            if (raw == null) raw = content.get("data");
            for (Map<String, Object> item : maps(raw)) {
                long id = number(item.get("id"));
                if (id <= 0L) continue;
                long state = number(item.get("state"));
                boolean claimable = state == 2L || number(item.get("can_get")) > 0L
                        || number(item.get("status")) == 2L;
                if (!claimable) continue;
                try {
                    request(ACHIEVEMENT_REWARD, Map.of("id", id));
                    claimed++;
                } catch (Exception ignored) {
                    // 单项失败不影响其它
                }
            }
            if (claimed == 0) markRetry("achievement", 300_000L);
        } catch (Exception e) {
            markRetry("achievement", 300_000L);
        }
        return claimed;
    }

    /**
     * 新手引导推进（对齐新版 BootstrapTutorialService.advanceGuides 的核心：
     * 逐个 guide 逐级上报 {@code req_guide_set_step{id, step}} 直到 maxStep）。
     *
     * <p>真实轮（2026-11-11）验证：新号启动起号后引导 10 步全部完成（建造伐木场→工作台→
     * 委任工人→厨房→灶台→民居床位→委任厨师→猎人之家→捕猎工位→委任猎人），是资源与解锁的前置。
     *
     * @param guideMaxSteps guideId → maxStep（由调用方从推送/目录提供，缺省按 1 步推进）
     */
    Map<String, Object> tutorialAdvance(Map<Long, Long> guideMaxSteps) {
        if (!state.guideReady()) {
            return null;
        }
        for (Map.Entry<Long, Long> e : state.guideSteps().entrySet()) {
            long guideId = e.getKey();
            long current = e.getValue();
            long maxStep = guideMaxSteps == null ? 0L : guideMaxSteps.getOrDefault(guideId, 0L);
            if (maxStep <= 0L || current >= maxStep) continue;
            long next = current + 1L;
            String retryKey = "guide:" + guideId + ":" + next;
            if (!retryAllowed(retryKey)) continue;
            try {
                request(GUIDE_SET_STEP, Map.of("id", guideId, "step", next));
                state.markGuideStep(guideId, next);
                clearRetry(retryKey);
                return actionResult("STARTED",
                        "上报引导 " + guideId + " 第 " + next + "/" + maxStep + " 步");
            } catch (Exception ex) {
                markRetry(retryKey, 30_000L);
            }
        }
        return null;
    }

    /**
     * 英雄推进（对齐新版 BootstrapHeroService.advance）：
     * 首次史诗招募 → 招募阶梯（免费高级/免费史诗/道具高级/道具史诗/宝箱积分）
     * → 有碎片英雄升星 → 最高级英雄升级。
     *
     * <p>顺序有讲究：先招募拿到英雄，才有队伍打探险；升星/升级提升战力才能过更高关卡。
     * 返回 null 表示本轮无可做动作。
     */
    Map<String, Object> heroAdvance() {
        if (!state.heroReady()) {
            return null;
        }
        // yieldTurn（对齐新版 BootstrapHeroService.yieldTurn）：英雄招募/升星/升级每轮都能成功，
        // 若不定期让出，runUntil 会被英雄动作占满、建筑永远排不上（实测熔炉卡 3 级）。
        if (heroBurst >= 3) {
            heroBurst = 0;
            return null;
        }
        heroBurst++;
        // 1) 新手引导的首次史诗招募（免费 1 次）
        if (!state.epicSearched()) {
            try {
                request(HERO_SEARCH, Map.of("search_type", 2L, "cost_type", 1L, "times", 1L));
                state.markEpicSearched();
                return actionResult("STARTED", "请求首次史诗招募");
            } catch (Exception e) {
                state.markEpicSearched();   // 失败也标记，避免反复请求
            }
        }
        // 2) 招募阶梯
        long[][] ladder = {{1L, 1L}, {2L, 1L}, {1L, 2L}, {2L, 2L}, {3L, 3L}};
        String[] labels = {"免费高级招募", "免费史诗招募", "道具高级招募", "道具史诗招募", "招募宝箱"};
        for (int i = 0; i < ladder.length; i++) {
            String key = "hero:recruit:" + i;
            if (!retryAllowed(key)) continue;
            try {
                request(HERO_SEARCH, Map.of("search_type", ladder[i][0],
                        "cost_type", ladder[i][1], "times", 1L));
                markRetry(key, 5000L);
                return actionResult("STARTED", "已请求" + labels[i] + " 1 次");
            } catch (Exception e) {
                markRetry(key, 1_800_000L);
            }
        }
        // 3) 有碎片的英雄升星（碎片来自 notify_hero_update.fragment_num）
        for (Map.Entry<Long, Long> e : state.heroFragments().entrySet()) {
            if (e.getValue() <= 0L) continue;
            String key = "hero:star:" + e.getKey();
            if (!retryAllowed(key)) continue;
            try {
                request(HERO_PROMOTE_STAR, Map.of("id", e.getKey()));
                return actionResult("STARTED", "请求英雄 " + e.getKey() + " 升星");
            } catch (Exception ex) {
                markRetry(key, 1_800_000L);
            }
        }
        // 4) 升级「探险队伍」英雄（用户 2026-11-11 策略：优先升前五个英雄的等级/星级，
        //    战力越高探险能推到更深关卡，探险又是重要资源来源）。
        for (long heroId : state.topFiveHeroes()) {
            if (heroId <= 0L) continue;
            String key = "hero:upgrade:" + heroId;
            if (!retryAllowed(key)) continue;
            try {
                request(HERO_UPGRADE, Map.of("id", heroId));
                clearRetry(key);
                return actionResult("STARTED", "请求探险队英雄 " + heroId + " 升级");
            } catch (Exception ex) {
                markRetry(key, 300_000L);
            }
        }
        return null;
    }

    /**
     * 探险战斗（对齐新版 BootstrapAdventureService.advance）：
     * 有队伍才打；{@code req_begin_adventure_pve{checkpoint, fight_heros_data}} →
     * {@code req_end_adventure_pve{checkpoint, operatedata:[], enddata:[], version}}；
     * is_fast / is_win 视为通关，失败退避 30 分钟。
     *
     * @param targetCheckpoint 目标关卡（0 = 不限制，打下一关）
     */
    Map<String, Object> adventureBattle(long targetCheckpoint) {
        if (!state.hasAnyHero()) {
            if (retryAllowed("adventure:nolog")) {
                markRetry("adventure:nolog", 300_000L);
                FileLog.info("[起号] 探险跳过：还没有英雄（heroes={}）",
                        String.valueOf(state.heroLevels().size()));
            }
            return null;   // 没有英雄打不了探险
        }
        long checkpoint = state.adventurePassed() + 1L;
        if (targetCheckpoint > 0L && checkpoint > targetCheckpoint) {
            return null;   // 已达目标
        }
        String retryKey = "adventure:checkpoint:" + checkpoint;
        if (!retryAllowed(retryKey)) {
            if (retryAllowed("adventure:waitlog")) {
                markRetry("adventure:waitlog", 120_000L);
                FileLog.info("[起号] 探险第 {} 关仍在失败退避中，{} 秒后重试",
                        String.valueOf(checkpoint),
                        String.valueOf(Math.max(0L, (retryUntil.getOrDefault(retryKey, 0L)
                                - System.currentTimeMillis()) / 1000L)));
            }
            return null;
        }
        try {
            Map<String, Object> beginPayload = new LinkedHashMap<>();
            beginPayload.put("checkpoint", checkpoint);
            beginPayload.put("fight_heros_data", Map.of("source", 1L,
                    "fight_heros", state.topFiveHeroes()));
            GameMessage begin = request(ADVENTURE_BEGIN, beginPayload);
            Map<String, Object> beginContent = begin == null || begin.getContent() == null
                    ? Map.of() : begin.getContent();
            if (isTrue(beginContent.get("is_fast"))) {
                state.markAdventurePassed(checkpoint);
                clearRetry(retryKey);
                return actionResult("STARTED", "快速探险通关第 " + checkpoint + " 关");
            }
            Map<String, Object> endPayload = new LinkedHashMap<>();
            endPayload.put("checkpoint", checkpoint);
            endPayload.put("operatedata", List.of());
            endPayload.put("enddata", List.of());
            endPayload.put("version", ADVENTURE_BATTLE_VERSION);
            GameMessage end = request(ADVENTURE_END, endPayload);
            Map<String, Object> endContent = end == null || end.getContent() == null
                    ? Map.of() : end.getContent();
            if (isTrue(endContent.get("is_win"))) {
                state.markAdventurePassed(checkpoint);
                clearRetry(retryKey);
                FileLog.info("[起号] 探险通关第 {} 关", String.valueOf(checkpoint));
                return actionResult("STARTED", "通关探险第 " + checkpoint + " 关");
            }
            markRetry(retryKey, 1_800_000L);
            FileLog.info("[起号] 探险第 {} 关战力不足，30 分钟后再试（等英雄升级）",
                    String.valueOf(checkpoint));
            return actionResult("WAITING", "探险第 " + checkpoint + " 关战力不足，等英雄升级后再试");
        } catch (Exception e) {
            markRetry(retryKey, 1_800_000L);
            return null;
        }
    }

    private static boolean isTrue(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(raw)) || "1".equals(String.valueOf(raw));
    }

    // ---------------- 重试退避表（对齐新版 tutorial.retryAllowed） ----------------

    private final java.util.Map<String, Long> retryUntil = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean retryAllowed(String key) {
        Long until = retryUntil.get(key);
        return until == null || System.currentTimeMillis() >= until;
    }

    private void markRetry(String key, long delayMillis) {
        retryUntil.put(key, System.currentTimeMillis() + delayMillis);
    }

    private void clearRetry(String key) {
        retryUntil.remove(key);
    }

    /**
     * 兑换兑换码（对齐新版 BootstrapRewardService.claimCdkeys）：
     * 熔炉未达 {@link #CDKEY_MIN_FURNACE_LEVEL} 不兑换；逐码 {@code req_exchange_cdkey{cdkey, platform:2}}；
     * 7208 稍后重试（本轮中断），永久失败码记入已尝试不再重试。
     *
     * @return 本轮成功兑换条数
     */
    int claimCdkeys() {
        if (state.furnaceLevel() < CDKEY_MIN_FURNACE_LEVEL) {
            return 0;
        }
        int claimed = 0;
        for (String code : CDKEYS) {
            if (attemptedCdkeys.contains(code)) continue;
            try {
                GameMessage response = request(EXCHANGE_CDKEY,
                        Map.of("cdkey", code, "platform", 2L));
                Map<String, Object> content = response == null || response.getContent() == null
                        ? Map.of() : response.getContent();
                long rc = number(content.get("code"));
                attemptedCdkeys.add(code);
                if (rc == 0L) {
                    claimed++;
                    FileLog.info("[起号] 兑换码 {} 兑换成功", code);
                }
            } catch (ProtocolException e) {
                int failureCode = e.code();
                if (failureCode == CDKEY_RETRY_LATER) {
                    FileLog.info("[起号] 兑换码 {} 稍后重试（code=7208）", code);
                    break;   // 本轮中断，5 分钟后再试
                }
                if (CDKEY_PERMANENT_FAILURES.contains(failureCode)) {
                    attemptedCdkeys.add(code);
                    FileLog.info("[起号] 兑换码 {} 永久失败 code={}，不再尝试", code, String.valueOf(failureCode));
                }
            } catch (Exception e) {
                FileLog.warn("[起号] 兑换码 {} 兑换异常：{}", code, String.valueOf(e.getMessage()));
            }
        }
        return claimed;
    }

    /**
     * 把全部闲置幸存者委任上岗（网页版起号第一步，见 {@link #SURVIVOR_WORK_START}）。
     *
     * <p>真实轮实证（2026-11-11，724 区新建号）：启动起号后 25 秒内 idle 4→0、working 0→3，
     * 木材 0→1446、熔炉 0→1；此前不委任时资源恒为 0、建造恒被 51 拒。故委任必须在
     * 资源检查/建造之前执行。返回 {@code {assigned, failed, idleBefore}}。
     */
    Map<String, Object> assignIdleSurvivors() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        List<BootstrapState.Survivor> idle = state.idleSurvivors();
        result.put("idleBefore", idle.size());
        if (idle.isEmpty()) {
            result.put("assigned", List.of());
            result.put("assignedCount", 0);
            result.put("failed", 0);
            result.put("message", "没有需要委任的幸存者");
            return result;
        }
        java.util.Set<Long> assignedIds = state.assignedSurvivorIds();
        ArrayList<Long> assigned = new ArrayList<>();
        int failed = 0;
        // 精确委任（对齐新版 assignIdleSurvivorToProduction）：
        // 遍历生产建筑 → 工位类型 → 找空闲工位 → 取闲置幸存者 → req_survivor_send_or_fired{sid, fid_work, type:1}
        outer:
        for (long buildingId : PRODUCTION_BUILDINGS) {
            if (state.level(buildingId) <= 0L) continue;
            for (long type : PRODUCTION_STATION_TYPES) {
                for (Map.Entry<Long, List<Long>> station : state.furnitureByType(buildingId, type).entrySet()) {
                    if (!station.getValue().isEmpty()) continue;   // 工位已有人
                    long confFid = station.getKey();
                    long fidWork = state.furnitureInstanceId(buildingId, confFid);
                    if (fidWork <= 0L) continue;
                    BootstrapState.Survivor pick = null;
                    for (BootstrapState.Survivor v : idle) {
                        if (!assignedIds.contains(v.sid())) { pick = v; break; }
                    }
                    if (pick == null) break outer;   // 没有闲置幸存者了
                    String retryKey = "station:" + buildingId + ":" + confFid;
                    if (!retryAllowed(retryKey)) continue;
                    try {
                        request(SURVIVOR_SEND_OR_FIRED,
                                Map.of("sid", pick.sid(), "fid_work", fidWork, "type", 1L));
                        assignedIds.add(pick.sid());
                        assigned.add(pick.sid());
                        clearRetry(retryKey);
                        FileLog.info("[起号] 已把幸存者 {} 派到 {} 的工位 {}",
                                String.valueOf(pick.sid()), String.valueOf(buildingId), String.valueOf(fidWork));
                    } catch (Exception e) {
                        markRetry(retryKey, 60_000L);
                        failed++;
                        FileLog.info("[起号] 工位 {} 派遣失败：{}", String.valueOf(fidWork),
                                String.valueOf(e.getMessage()));
                    }
                }
            }
        }
        // 兜底：精确委任没成功（无工位数据）时，用简化协议让游戏自动分配
        if (assigned.isEmpty() && failed == 0) {
            for (BootstrapState.Survivor v : idle) {
                try {
                    GameMessage response = request(SURVIVOR_WORK_START, Map.of("sid", v.sid()));
                    Map<String, Object> content = response == null || response.getContent() == null
                            ? Map.of() : response.getContent();
                    if (number(content.get("code")) == 0L) assigned.add(v.sid());
                } catch (Exception ignored) {
                    // 兜底也失败则本轮不委任
                }
            }
        }
        result.put("assigned", List.copyOf(assigned));
        result.put("assignedCount", assigned.size());
        result.put("failed", failed);
        result.put("message", assigned.isEmpty() ? "没有需要委任的幸存者"
                : "已委任 " + assigned.size() + " 位幸存者上岗");
        return result;
    }

    /**
     * 领取探险挂机奖励（语义对齐原版 DailyAdventureRewardService.claimOnce）：
     * {@code req_open_hang_up_reward_view} 刷新 → {@code req_get_hang_up_reward} 领取；
     * 挂机时长未到（code 4004）或未解锁时按跳过处理（{@code claimed=false}），不抛错。
     */
    Map<String, Object> claimAdventureHangUp() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        try {
            request(ADVENTURE_PREVIEW, Map.of());
        } catch (Exception e) {
            result.put("claimed", false);
            result.put("reason", "探险状态不可用：" + String.valueOf(e.getMessage()));
            return result;
        }
        try {
            GameMessage response = request(ADVENTURE_CLAIM, Map.of());
            Map<String, Object> content = response == null || response.getContent() == null
                    ? Map.of() : response.getContent();
            long code = number(content.get("code"));
            result.put("code", code);
            result.put("claimed", code == 0L);
            if (code != 0L) {
                result.put("reason", code == ADVENTURE_NOT_READY
                        ? "挂机时长未达到领取条件" : "领取被拒绝 code=" + code);
            }
            return result;
        } catch (Exception e) {
            result.put("claimed", false);
            // 4004 = 挂机时长未达领取条件（原版按跳过处理，不是错误）
            Integer code = protocolErrorCode(e);
            if (code != null && code == ADVENTURE_NOT_READY) {
                result.put("code", code);
                result.put("reason", "挂机时长未达到领取条件");
            } else {
                result.put("reason", "领取异常：" + String.valueOf(e.getMessage()));
            }
            return result;
        }
    }

    private static String furnitureKey(long buildingId, long furnitureId) {
        return buildingId + ":" + furnitureId;
    }

    private static long[] parseFurnitureKey(String key) {
        if (key == null) return null;
        int idx = key.indexOf(':');
        if (idx <= 0 || idx + 1 >= key.length()) return null;
        try {
            return new long[]{Long.parseLong(key.substring(0, idx)), Long.parseLong(key.substring(idx + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从异常链里识别协议错误码（无则 null）。 */
    private static Integer protocolErrorCode(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ProtocolException) {
                return ((ProtocolException) c).code();
            }
            if (c.getCause() == c) break;
        }
        return null;
    }

    /** 单次领奖；上游拒绝/异常返回 false（不中断其它项）。 */
    private boolean tryClaim(String protocol, Map<String, Object> payload) {
        try {
            GameMessage response = request(protocol, payload);
            Map<String, Object> content = response == null ? Map.of()
                    : (response.getContent() == null ? Map.of() : response.getContent());
            return number(content.get("code")) == 0L;
        } catch (RuntimeException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 推送里取一级字段（推送体已是 {field:...} 形状）。 */
    private Object notifyValue(String proto, String field) {
        Map<String, Object> n = port.notify(proto);
        return n == null ? null : n.get(field);
    }

    /** 任务 id 列表解析（对齐原版 questIds：支持单对象与列表，取字段 id）。 */
    private static List<Long> questIds(Object raw) {
        ArrayList<Long> out = new ArrayList<>();
        if (raw instanceof Map) {
            long id = number(((Map<?, ?>) raw).get("id"));
            if (id > 0L) out.add(id);
            return out;
        }
        if (raw instanceof Iterable) {
            for (Object v : (Iterable<?>) raw) {
                if (!(v instanceof Map)) continue;
                long id = number(((Map<?, ?>) v).get("id"));
                if (id > 0L) out.add(id);
            }
        }
        return out;
    }

    /** 未领宝箱 id（对齐原版 unclaimedRewardIds：get_flag 为 true 视为已领）。 */
    private static List<Long> unclaimedRewardIds(Object raw) {
        ArrayList<Long> out = new ArrayList<>();
        if (!(raw instanceof Iterable)) return out;
        for (Object v : (Iterable<?>) raw) {
            if (!(v instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) v;
            long id = number(m.get("id"));
            Object flag = m.get("get_flag");
            boolean claimed = Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
            if (id > 0L && !claimed) out.add(id);
        }
        return out;
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
                    FileLog.warn("[起号] 使用资源包被拒绝 itemId={} code={}",
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
                FileLog.warn("[起号] 领取情报奖励被拒绝 id={} code={}", id, exception.code());
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
    /**
     * 快速完成空闲情报（对齐新版 {@code fightIdleIntelligence}）：
     * 只有 <b>type==3</b> 的事件支持快速战斗（新版 {@code IntelligenceQuestSupport.supportsQuickBattle}），
     * 队伍用与探险相同的 {@link BootstrapState#topFiveHeroes()}（新版权威 clientSquad）；
     * 此前未判 type 且传空队伍 [0,0,0,0,0] → 战斗必败。
     */
    private Optional<Map<String, Object>> fightIdleIntelligence(Map<Long, Map<String, Object>> events) {
        Map<String, Object> target = null;
        for (Map<String, Object> event : events.values()) {
            long status = event.containsKey("status") ? number(event.get("status")) : 1L;
            long type = number(event.get("type"));
            // 仅 status==1（空闲）且 type==3（支持快速战斗）且未在退避期
            if (status == 1L && type == 3L
                    && retryAllowed("intel:" + number(event.get("id")))) {
                target = event;
                break;
            }
        }
        if (target == null) {
            return Optional.empty();
        }
        long id = number(target.get("id"));
        if (id <= 0L) {
            return Optional.empty();
        }
        if (!state.hasAnyHero()) {
            return Optional.empty();   // 没有英雄打不了情报战
        }
        Map<String, Object> startPayload = Map.of("id", id,
                "fight_heros_data", Map.of("source", 1L,
                        "fight_heros", state.topFiveHeroes()));
        try {
            request(INTEL_START_BATTLE_PROTOCOL, startPayload);
            request(INTEL_END_BATTLE_PROTOCOL, Map.of("id", id));
        } catch (ProtocolException exception) {
            markRetry("intel:" + id, 600_000L);   // 对齐新版：10 分钟后再试
            FileLog.warn("[起号] 发起情报战斗被拒绝 id={} code={}（10 分钟后再试）",
                    String.valueOf(id), String.valueOf(exception.code()));
            return Optional.empty();
        } catch (Exception exception) {
            FileLog.warn("[起号] 发起情报战斗失败 id={} error={}", id,
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

    /** 拉一次角色清单（失败返回 null，不阻断建号）。 */
    private Map<String, Object> roleListContent(WjdrEngine engine) {
        try {
            GameMessage response = request("req_role_list", Map.of());
            return response == null ? null : response.getContent();
        } catch (Exception e) {
            return null;
        }
    }

    /** 统计清单里属于某区服的角色数（兼容 list / {list:[...]} 与 serverid/kid 两种键）。 */
    static int countRolesInServer(Map<String, Object> roles, long serverId) {
        if (roles == null || serverId <= 0L) return 0;
        Object raw = firstNonNull(roles.get("list"), roles.get("roles"), roles.get("data"));
        int count = 0;
        for (Map<String, Object> role : maps(raw)) {
            long sid = number(role.get("serverid"));
            if (sid <= 0L) sid = number(role.get("server_id"));
            if (sid <= 0L) sid = number(role.get("kid"));
            if (sid == serverId) count++;
        }
        return count;
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }

    /** 秒 → 「X 小时 Y 分」中文时长。 */
    private static String humanDuration(long seconds) {
        if (seconds <= 0L) return "0 秒";
        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        if (h > 0L) return h + " 小时" + (m > 0L ? " " + m + " 分" : "");
        if (m > 0L) return m + " 分钟";
        return seconds + " 秒";
    }

    public LoginOutcome createRole(WjdrEngine engine, String fpid, String sdkToken,
                                   long serverId, long targetUid, String displayName) throws Exception {
        if (serverId <= 0L) {
            throw new IllegalArgumentException("请选择目标区服");
        }
        // 建号 = 一次 loginauth + is_create=true（对齐新版 RegistrationRoleCreationService）。
        // 建号限制（block_create_role_endtime）在 loginauth 响应里，失败时据此给明确中文提示；
        // 不做额外的前置登录——新版同样是一次请求，避免多余会话与顶号风险。
        // 建号前注入「本角色独立设备指纹」（对齐网页版 ensureAccountFingerprint）：
        // 网页版 30 秒内同区连建两号不被拒，靠的就是每个角色不同的 device_id/distinct_id/login_ip；
        // 共用指纹会触发游戏侧设备风控（实测 loginauth code=25）。
        try {
            String fingerprint = DeviceFingerprint.forNewRole(fpid, serverId,
                    String.valueOf(System.currentTimeMillis()));
            engine.applyIdentity(fingerprint);
        } catch (RuntimeException ignored) {
            // 注入失败退回引擎默认随机指纹（仍比固定指纹安全）
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
        long blockEndtime = r.authContent == null ? 0L
                : ProtocolValueSupport.number(r.authContent.get("block_create_role_endtime"));
        String blockHint = blockEndtime <= 0L ? ""
                : "（服务端禁止该账号建号至时间戳 " + blockEndtime + "）";
        if (uid <= 0L) {
            // 对齐新版文案：服务端没建出角色（建号冷却/同区角色数达上限/风控）
            throw new IllegalStateException("建号失败：服务端没有创建角色" + blockHint
                    + describeServer(serverId));
        }
        if (targetUid > 0L && uid != targetUid) {
            throw new IllegalStateException("游戏账号登录到了其他角色：期望 uid=" + targetUid
                    + describeServer(serverId) + "，实际 uid=" + uid + describeServer(landedServerId)
                    + "，已中止登录。请检查该角色的区服资料后重试");
        }
        String message = "已在该区服创建新角色并登录";
        FileLog.info("[起号] 建号成功 uid={} server={}", uid, landedServerId);
        return new LoginOutcome(uid, landedServerId, r.authContent,
                serverId > 0L && landedServerId > 0L && landedServerId != serverId, message);
    }

    /** 建号便捷重载（不钉扎 uid）。 */
    public LoginOutcome createRole(WjdrEngine engine, String fpid, String sdkToken,
                                   long serverId, String displayName) throws Exception {
        return createRole(engine, fpid, sdkToken, serverId, 0L, displayName);
    }

    /**
     * 登录**已存在角色**（{@code is_create=false}）。
     *
     * <p>真实轮（2026-11-10）：同一账号第二次用 {@code is_create=true} 登录时游戏返回
     * {@code loginauth code=9}（角色已存在/创建冲突）。故批量起号在「建号」失败且判定为
     * 已存在角色时回退本方法，用普通登录进入既有角色继续升级。
     */
    public LoginOutcome loginExisting(WjdrEngine engine, String fpid, String sdkToken,
                                      long serverId) throws Exception {
        if (serverId <= 0L) {
            throw new IllegalArgumentException("请选择目标区服");
        }
        // 既有角色：用 (fpid, 区服, uid) 派生的稳定指纹，同一角色每次登录都是"同一台设备"。
        // 注意：换指纹必须关连接 → 注入 → 重新 initialize+login，否则引擎处于未初始化态。
        try {
            WjdrEngine.LoginResult probe = engine.login(fpid, sdkToken, serverId, java.util.Map.of());
            long uid = probe.uid;
            if (uid > 0L) {
                engine.close();
                engine.applyIdentity(DeviceFingerprint.forRole(fpid, serverId, uid));
                engine.initialize();                               // 重建连接
                WjdrEngine.LoginResult r = engine.login(fpid, sdkToken, serverId);   // 用新指纹登录
                return new LoginOutcome(r.uid, r.serverId,
                        r.authContent == null ? Map.of() : r.authContent, false,
                        "已登录既有角色 uid=" + r.uid + describeServer(r.serverId));
            }
        } catch (Exception ignored) {
            // 探测/重登失败则走下面的常规登录
        }
        WjdrEngine.LoginResult r = engine.login(fpid, sdkToken, serverId);
        return new LoginOutcome(r.uid, r.serverId,
                r.authContent == null ? Map.of() : r.authContent, false,
                "已登录既有角色 uid=" + r.uid + describeServer(r.serverId));
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
