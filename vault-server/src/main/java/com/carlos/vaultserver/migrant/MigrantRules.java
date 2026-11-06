package com.carlos.vaultserver.migrant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 移民（藏宝阁 · 自由移民）规则与常量。
 *
 * <p>逐条移植原版反编译 {@code wjdr-review/.../migrant/service/MigrantRules.java}（212 行）：
 * 许可书阈值表、赛区战力档位表、活动阶段窗口、名额上限、冷却、许可书消耗。
 * <b>不得凭记忆改值</b>；本类只做纯计算，不访问网络/存储。
 *
 * <p>另外收纳本模块的时序常量（契约 §4「时序常量（移植，不得改值）」）：准备窗口 15 分钟、
 * 就绪目标开放前 2 分钟、抢位节拍 50ms、最大尝试 200 次、对账窗口 90 秒、
 * 钻石兜底 10 次/任务、召回道具 800 钻、行军加速器 400 钻。
 */
public final class MigrantRules {

    /** 活动配置版本（网页版同源）。 */
    public static final String CONFIG_VERSION = "gofcn-1.14.8-20260725-r4";
    /** 支持的移民活动 id（2423001）。 */
    public static final long SUPPORTED_ACTIVITY_ID = 2423001L;
    /** 展望阶段时长：3 天。 */
    public static final long PROSPECT_SECONDS = 259200L;
    /** 邀请阶段时长：2 天。 */
    public static final long INVITATION_SECONDS = 172800L;
    /** 自由移民阶段时长：2 天。 */
    public static final long FREEDOM_SECONDS = 172800L;
    /** 自由移民相对活动开始的偏移：5 天。 */
    public static final long FREEDOM_START_OFFSET_SECONDS = 432000L;
    /** 活动总时长：7 天。 */
    public static final long ACTIVITY_DURATION_SECONDS = 604800L;
    /** 移民冷却：25 天。 */
    public static final long MIGRANT_COOLDOWN_SECONDS = 2160000L;
    /** 同一账号在目标王国最多角色数。 */
    public static final int MAX_TARGET_ROLES = 4;

    // ---------- 时序常量（契约 §4，不得改值） ----------

    /** 准备窗口：自由移民开放前 15 分钟上号准备。 */
    public static final long PREPARE_WINDOW_MILLIS = 15L * 60_000L;
    /** 就绪目标：开放前 2 分钟完成退盟/召回/加速。 */
    public static final long READY_BEFORE_MILLIS = 2L * 60_000L;
    /** 抢位节拍：50ms。 */
    public static final long RETRY_INTERVAL_NANOS = 50_000_000L;
    /** 抢位最大尝试次数：200。 */
    public static final int MAX_ATTEMPTS = 200;
    /** 单次迁移请求等待服务端裁定：1 秒。 */
    public static final long ATTEMPT_RESPONSE_WAIT_MILLIS = 1000L;
    /** 提交窗口：开放边界后 15 秒内持续尝试。 */
    public static final long SUBMIT_WINDOW_MILLIS = 15_000L;
    /** 结果对账窗口：90 秒。 */
    public static final long RECONCILE_WINDOW_MILLIS = 90_000L;
    /** 对账轮询间隔：2 秒。 */
    public static final long RECONCILE_RETRY_INTERVAL_MILLIS = 2000L;
    /** 钻石兜底购买上限：10 次/任务。 */
    public static final int DIAMOND_FALLBACK_LIMIT = 10;
    /** 召回道具（出征召回）钻石价：800。 */
    public static final long RECALL_ITEM_DIAMOND_COST = 800L;
    /** 行军加速器钻石价：400。 */
    public static final long SPEEDUP_ITEM_DIAMOND_COST = 400L;
    /** 早期测试安全边界：开放前 10 秒内禁止提前测试。 */
    public static final long EARLY_TEST_SAFETY_MARGIN_MILLIS = 10_000L;

    // ---------- 道具 / 协议常量 ----------

    /** 移民许可证道具 id（背包计数）。 */
    public static final int MIGRANT_PERMIT_ITEM_ID = 600052;
    /** 出征召回道具 id（原版/守护盟友实证口径；存疑项见汇报）。 */
    public static final int RECALL_ITEM_ID = 301800;
    /** 行军加速器一阶（25%）。 */
    public static final int SPEEDUP_ITEM_TIER1 = 301900;
    /** 行军加速器二阶（50%）。 */
    public static final int SPEEDUP_ITEM_TIER2 = 301901;

    public static final String PROTO_KINGDOM_LIST = "req_migrant_get_kingdom_list";
    public static final String PROTO_KINGDOM_INFO = "req_migrant_get_kingdom_info";
    public static final String PROTO_MIGRATE = "req_migrant_migrate";
    public static final String PROTO_FIRST_LOGIN = "req_migrant_first_login";
    public static final String PROTO_ALLIANCE_QUIT = "req_alliance_quit";
    public static final String PROTO_MARCH_RECALL = "req_world_march_recall";
    public static final String PROTO_MARCH_OFF_WORK = "req_world_march_off_work";
    public static final String PROTO_MARCH_SPEEDUP = "req_world_march_speedup";
    public static final String PROTO_SHOP_QUICK_BUY = "req_shop_quick_buy";

    /** 迁移请求「正在移民/迁服中」类响应码：收到即停止重试。 */
    public static final int CODE_MIGRATING = 12044;
    /** 迁移请求「迁服断线」类响应码：收到即停止重试。 */
    public static final int CODE_MIGRATE_DISCONNECT = 12085;

    /** 许可书阈值表：个人分/标准分百分比 ≥ ratio 时消耗 count 张（从高到低取第一条）。 */
    private static final int[][] PERMIT_THRESHOLDS = {
            {100, 50}, {95, 45}, {90, 40}, {85, 35}, {80, 30}, {75, 25}, {70, 20},
            {65, 18}, {60, 16}, {55, 14}, {50, 12}, {45, 10}, {40, 9}, {35, 8},
            {30, 7}, {25, 6}, {20, 5}, {15, 4}, {10, 3}, {5, 2}, {0, 1}
    };

    /** 赛区档位表：赛区 id = 下标+1；每档 5 个战力上限（目标 power_limit 1..5 取对应值）。 */
    private static final WarzoneRule[] WARZONE_RULES = {
            new WarzoneRule(1, 20, 20000000L, 40000000L, 65000000L, 80000000L, 100000000L),
            new WarzoneRule(155, 25, 26000000L, 52000000L, 84500000L, 104000000L, 130000000L),
            new WarzoneRule(197, 25, 35000000L, 70000000L, 113750000L, 140000000L, 175000000L),
            new WarzoneRule(225, 25, 45000000L, 90000000L, 146250000L, 180000000L, 225000000L),
            new WarzoneRule(281, 30, 54000000L, 108000000L, 160000000L, 216000000L, 270000000L),
            new WarzoneRule(323, 30, 63000000L, 126000000L, 187000000L, 252000000L, 315000000L),
            new WarzoneRule(365, 30, 80000000L, 160000000L, 232000000L, 320000000L, 400000000L),
            new WarzoneRule(449, 30, 97000000L, 194000000L, 272000000L, 388000000L, 485000000L),
            new WarzoneRule(491, 30, 105000000L, 210000000L, 295000000L, 420000000L, 525000000L),
            new WarzoneRule(533, 30, 120000000L, 240000000L, 348000000L, 480000000L, 600000000L),
            new WarzoneRule(617, 30, 140000000L, 280000000L, 372000000L, 560000000L, 700000000L),
            new WarzoneRule(701, 30, 168000000L, 336000000L, 426700000L, 672000000L, 840000000L),
            new WarzoneRule(785, 30, 186000000L, 372000000L, 462270000L, 744000000L, 930000000L),
            new WarzoneRule(869, 30, 200000000L, 400000000L, 497840000L, 800000000L, 1000000000L),
            new WarzoneRule(953, 30, 220000000L, 440000000L, 533410000L, 880000000L, 1100000000L),
            new WarzoneRule(1037, 30, 230000000L, 460000000L, 568980000L, 920000000L, 1150000000L),
            new WarzoneRule(1121, 30, 240000000L, 480000000L, 588980000L, 960000000L, 1200000000L)
    };

    private MigrantRules() {
    }

    /**
     * 资格评估（原版 {@code MigrantRules.evaluate} 逐条移植）。
     *
     * @param snapshot 登录初始化快照（base/migrant/backpack/alliance/serverTime）
     * @param targetKid 目标王国 id
     */
    public static Evaluation evaluate(MigrantState.Snapshot snapshot, long targetKid) {
        List<Check> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        check(checks, "state", "登录状态数据", snapshot.complete(),
                snapshot.complete() ? "登录初始化数据完整" : missingStateDescription(snapshot));

        long serverNowSeconds = snapshot.serverNowMillis / 1000L;
        boolean activitySupported = snapshot.activityId == SUPPORTED_ACTIVITY_ID
                && snapshot.activityStartSeconds > 0L
                && snapshot.activityEndSeconds - snapshot.activityStartSeconds == ACTIVITY_DURATION_SECONDS
                && snapshot.standardPoints > 0L;
        check(checks, "activity", "移民活动配置", activitySupported,
                activitySupported ? "活动配置与当前客户端快照一致"
                        : "活动 ID、时长或标准分与支持的配置不一致");

        boolean activityOpen = activitySupported
                && serverNowSeconds >= snapshot.activityStartSeconds
                && serverNowSeconds < snapshot.activityEndSeconds;
        check(checks, "activity-window", "活动时间窗", activityOpen,
                activityOpen ? phaseLabel(snapshot, serverNowSeconds)
                        : "当前不在本轮移民活动时间内");

        MigrantState.KingdomInfo kingdom = snapshot.kingdoms.get(targetKid);
        boolean targetLoaded = kingdom != null;
        check(checks, "target", "目标王国", targetLoaded,
                targetLoaded ? kingdomLabel(kingdom) : "目标王国详情尚未同步");

        boolean differentKingdom = targetKid > 0L && targetKid != snapshot.currentKid;
        check(checks, "different-kingdom", "目标不是当前王国", differentKingdom,
                differentKingdom ? "目标王国与当前王国不同" : "不能移民到当前所在王国");

        boolean sameArea = snapshot.kidOpenTimes.containsKey(targetKid);
        check(checks, "same-area", "同一移民赛区", sameArea,
                sameArea ? "目标王国属于赛区 " + snapshot.competitionAreaId
                        : "目标王国不在当前移民赛区");

        int totalLimit = kingdom == null ? 0 : totalMigrantLimit(kingdom);
        long remainingQuota = kingdom == null ? 0L : (long) totalLimit - kingdom.migrantNum;
        boolean invited = snapshot.invitationKids.contains(targetKid);
        boolean quotaAvailable = targetLoaded && (remainingQuota > 0L || invited);
        check(checks, "quota", "自由移民总名额", quotaAvailable,
                quotaDescription(targetLoaded, invited, remainingQuota, totalLimit));

        WarzoneRule warzone = resolveWarzoneRule(snapshot);
        long powerLimit = kingdom == null || warzone == null ? 0L : warzone.powerLimit(kingdom.powerLimit);
        boolean powerAllowed = powerLimit > 0L && snapshot.personalPoints <= powerLimit;
        check(checks, "power", "移民评分限制", powerAllowed,
                powerLimit > 0L ? "当前评分 " + snapshot.personalPoints + "，目标上限 " + powerLimit
                        : "目标实力档位或赛区配置无法识别");

        int furnaceLevel = warzone == null ? 0 : warzone.furnaceLevel;
        boolean furnaceAllowed = furnaceLevel > 0 && snapshot.stoveLevel >= furnaceLevel;
        check(checks, "furnace", "熔炉等级", furnaceAllowed,
                furnaceLevel > 0 ? "当前 " + snapshot.stoveLevel + " 级，要求至少 " + furnaceLevel + " 级"
                        : "赛区熔炉配置无法识别");

        check(checks, "alliance", "联盟状态", snapshot.allianceId == 0L,
                snapshot.allianceId == 0L ? "当前未加入联盟" : "当前仍在联盟 " + snapshot.allianceId);

        boolean identityAllowed = (snapshot.presidentUid <= 0L || snapshot.presidentUid != snapshot.uid)
                && !snapshot.supremacyWarActive;
        check(checks, "identity", "角色身份", identityAllowed,
                identityAllowed ? "不是本国执政官，且未处于霜龙战场身份"
                        : "执政官或霜龙相关身份不允许移民");

        int targetRoleCount = snapshot.roleCountByKingdom.getOrDefault(targetKid, 0);
        check(checks, "role-count", "目标王国角色数", targetRoleCount < MAX_TARGET_ROLES,
                "目标王国已有 " + targetRoleCount + " / " + MAX_TARGET_ROLES + " 个角色");

        long cooldownRemaining = cooldownRemaining(snapshot, serverNowSeconds);
        check(checks, "cooldown", "25 天移民冷却", cooldownRemaining == 0L,
                cooldownRemaining == 0L ? "移民冷却已满足" : "还需等待 " + durationText(cooldownRemaining));

        int requiredPermits = requiredPermits(snapshot.personalPoints, snapshot.standardPoints);
        boolean permitsEnough = requiredPermits > 0 && snapshot.permitCount >= (long) requiredPermits;
        check(checks, "permits", "移民授权书", permitsEnough,
                requiredPermits > 0 ? "持有 " + snapshot.permitCount + "，预计消耗 " + requiredPermits
                        : "标准分无效，无法计算授权书消耗");

        warnings.add("城市迁城中、实时战斗状态及目标并发名额仍由服务端在最终请求时裁决");
        warnings.add("自由移民开放后在 15 秒提交窗口内持续尝试，单次最多等待服务端裁定 "
                + ATTEMPT_RESPONSE_WAIT_MILLIS + " 毫秒；收到成功、正在移民或迁服断线信号后立即停止并重新登录核验");
        warnings.add("结果核验最长 90 秒；仍未确认的任务会在开放后 3 分钟和 10 分钟各复核一次");

        boolean ready = true;
        for (Check c : checks) {
            if (!c.passed) {
                ready = false;
                break;
            }
        }
        long freedomStart = snapshot.activityStartSeconds <= 0L ? 0L
                : (snapshot.activityStartSeconds + FREEDOM_START_OFFSET_SECONDS) * 1000L;
        long freedomEnd = snapshot.activityStartSeconds <= 0L ? 0L
                : (snapshot.activityStartSeconds + ACTIVITY_DURATION_SECONDS) * 1000L;
        return new Evaluation(ready, CONFIG_VERSION, targetKid,
                kingdom == null ? "" : kingdom.name, requiredPermits, snapshot.permitCount,
                remainingQuota, freedomStart, freedomEnd,
                phase(snapshot, serverNowSeconds), checks, warnings);
    }

    /** 许可书消耗（原版移植）：个人分 / 标准分 * 100 落到阈值表第一条；标准分无效返回 0。 */
    public static int requiredPermits(long personalPoints, long standardPoints) {
        if (standardPoints <= 0L) return 0;
        double ratio = (double) personalPoints / (double) standardPoints * 100.0;
        for (int[] threshold : PERMIT_THRESHOLDS) {
            if (ratio >= (double) threshold[0]) return threshold[1];
        }
        return 1;
    }

    /** 目标王国战力上限值（赛区档位表换算；概览展示用）。 */
    public static long powerLimitValue(MigrantState.Snapshot snapshot, long targetKid) {
        if (snapshot == null) return 0L;
        MigrantState.KingdomInfo kingdom = snapshot.kingdoms.get(targetKid);
        WarzoneRule warzone = resolveWarzoneRule(snapshot);
        if (kingdom == null || warzone == null) return 0L;
        return warzone.powerLimit(kingdom.powerLimit);
    }

    /** 目标王国自由移民剩余名额（非强盛 55 / 强盛 30+15）。 */
    public static long remainingQuota(MigrantState.KingdomInfo kingdom) {
        if (kingdom == null) return 0L;
        return Math.max(0L, (long) totalMigrantLimit(kingdom) - kingdom.migrantNum);
    }

    /** 活动阶段：PROSPECTS / INVITATION / FREEDOM / CLOSED（原版移植）。 */
    static String phase(MigrantState.Snapshot snapshot, long nowSeconds) {
        long offset = nowSeconds - snapshot.activityStartSeconds;
        if (offset >= 0L && offset < PROSPECT_SECONDS) return "PROSPECTS";
        if (offset >= PROSPECT_SECONDS && offset < FREEDOM_START_OFFSET_SECONDS) return "INVITATION";
        if (offset >= FREEDOM_START_OFFSET_SECONDS && offset < ACTIVITY_DURATION_SECONDS) return "FREEDOM";
        return "CLOSED";
    }

    /** 阶段中文说明（原版移植）。 */
    private static String phaseLabel(MigrantState.Snapshot snapshot, long nowSeconds) {
        String phase = phase(snapshot, nowSeconds);
        if ("PROSPECTS".equals(phase)) return "当前为展望阶段，任务将在自由移民开放时自动执行";
        if ("INVITATION".equals(phase)) return "当前为邀请阶段，任务将在自由移民开放时自动执行";
        if ("FREEDOM".equals(phase)) return "自由移民已开放，任务创建后立即进入执行准备";
        return "不在可执行阶段";
    }

    /** 第一条未通过的检查文案（创建/准备失败原因）。 */
    public static String firstFailure(Evaluation evaluation) {
        if (evaluation == null) return "移民条件不符合";
        for (Check c : evaluation.checks) {
            if (!c.passed) return c.label + "不符合：" + c.detail;
        }
        return "移民条件不符合";
    }

    /** 冷却剩余秒（原版移植；未冷却返回 0）。 */
    private static long cooldownRemaining(MigrantState.Snapshot snapshot, long serverNowSeconds) {
        if (snapshot.lastMigrantSeconds <= 0L
                || serverNowSeconds - snapshot.lastMigrantSeconds > MIGRANT_COOLDOWN_SECONDS) {
            return 0L;
        }
        return Math.max(0L, MIGRANT_COOLDOWN_SECONDS - (serverNowSeconds - snapshot.lastMigrantSeconds) + 1L);
    }

    /** 自由移民名额上限（原版移植）：非强盛 55；强盛 30 + 连续强盛≥3 加 15。 */
    private static int totalMigrantLimit(MigrantState.KingdomInfo kingdom) {
        return kingdom.state != 1 ? 55 : 30 + (kingdom.continuousSuperCount >= 3 ? 15 : 0);
    }

    private static String quotaDescription(boolean targetLoaded, boolean invited,
                                           long remaining, int total) {
        if (!targetLoaded) return "无法计算目标名额";
        if (invited && remaining <= 0L) return "已有邀请，客户端允许继续交由服务端核定保留名额";
        return "总名额剩余 " + Math.max(0L, remaining) + " / " + total;
    }

    private static WarzoneRule resolveWarzoneRule(MigrantState.Snapshot snapshot) {
        long areaId = snapshot.competitionAreaId;
        if (areaId <= 0L || areaId > (long) WARZONE_RULES.length) return null;
        return WARZONE_RULES[(int) (areaId - 1L)];
    }

    private static String missingStateDescription(MigrantState.Snapshot snapshot) {
        List<String> missing = new ArrayList<>();
        if (!snapshot.baseInitialized) missing.add("角色基础信息");
        if (!snapshot.migrantInitialized) missing.add("移民活动");
        if (!snapshot.backpackInitialized) missing.add("背包");
        if (!snapshot.allianceInitialized) missing.add("联盟");
        if (!snapshot.serverTimeInitialized) missing.add("服务器时间");
        return "尚未同步：" + String.join("、", missing);
    }

    private static String kingdomLabel(MigrantState.KingdomInfo kingdom) {
        String name = kingdom.name == null || kingdom.name.trim().isEmpty() ? "未命名王国" : kingdom.name;
        return "K" + kingdom.kid + " " + name;
    }

    private static String durationText(long seconds) {
        long days = seconds / 86400L;
        long hours = seconds % 86400L / 3600L;
        long minutes = seconds % 3600L / 60L;
        return days + " 天 " + hours + " 小时 " + minutes + " 分钟";
    }

    private static void check(List<Check> checks, String id, String label, boolean passed, String detail) {
        checks.add(new Check(id, label, passed, detail));
    }

    /** 赛区档位（原版 private record WarzoneRule，Java 8 改 final class）。 */
    private static final class WarzoneRule {
        final int warzoneDay;
        final int furnaceLevel;
        final long[] powerGears;

        WarzoneRule(int warzoneDay, int furnaceLevel, long... powerGears) {
            this.warzoneDay = warzoneDay;
            this.furnaceLevel = furnaceLevel;
            this.powerGears = powerGears;
        }

        long powerLimit(int gear) {
            if (gear <= 0 || gear > powerGears.length) return 0L;
            return powerGears[gear - 1];
        }
    }

    /** 单条资格检查。 */
    public static final class Check {
        public final String id;
        public final String label;
        public final boolean passed;
        public final String detail;

        public Check(String id, String label, boolean passed, String detail) {
            this.id = id;
            this.label = label;
            this.passed = passed;
            this.detail = detail;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label);
            o.put("passed", passed);
            o.put("detail", detail);
            return o;
        }
    }

    /** 评估结果（原版 record Evaluation，Java 8 改 final class）。 */
    public static final class Evaluation {
        public final boolean ready;
        public final String configVersion;
        public final long targetKid;
        public final String targetName;
        public final int permitsRequired;
        public final long permitsOwned;
        public final long remainingQuota;
        public final long freedomStartMillis;
        public final long freedomEndMillis;
        public final String phase;
        public final List<Check> checks;
        public final List<String> warnings;

        public Evaluation(boolean ready, String configVersion, long targetKid, String targetName,
                          int permitsRequired, long permitsOwned, long remainingQuota,
                          long freedomStartMillis, long freedomEndMillis, String phase,
                          List<Check> checks, List<String> warnings) {
            this.ready = ready;
            this.configVersion = configVersion;
            this.targetKid = targetKid;
            this.targetName = targetName == null ? "" : targetName;
            this.permitsRequired = permitsRequired;
            this.permitsOwned = permitsOwned;
            this.remainingQuota = remainingQuota;
            this.freedomStartMillis = freedomStartMillis;
            this.freedomEndMillis = freedomEndMillis;
            this.phase = phase;
            this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        /** 网页版字段（1:1）转 JSON。 */
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("ready", ready);
            o.put("config_version", configVersion);
            o.put("target_kid", targetKid);
            o.put("target_name", targetName);
            o.put("permits_required", permitsRequired);
            o.put("permits_owned", permitsOwned);
            o.put("remaining_quota", remainingQuota);
            o.put("freedom_start_millis", freedomStartMillis);
            o.put("freedom_end_millis", freedomEndMillis);
            o.put("phase", phase);
            JSONArray arr = new JSONArray();
            for (Check c : checks) arr.put(c.toJson());
            o.put("checks", arr);
            JSONArray w = new JSONArray();
            for (String s : warnings) w.put(s);
            o.put("warnings", w);
            return o;
        }

        /** 找到指定 id 的检查（供自动化退出联盟时改写联盟项）。 */
        public Check checkOf(String id) {
            for (Check c : checks) {
                if (c.id.equals(id)) return c;
            }
            return null;
        }

        /**
         * 开启「自动退出联盟」时，联盟项由任务在准备窗口内执行，不应阻断创建/预检。
         * 其余检查全部通过才返回可创建副本；否则原样返回。
         */
        public Evaluation withAutoExitAlliance(boolean autoExitAlliance) {
            if (!autoExitAlliance || ready) return this;
            List<Check> replaced = new ArrayList<>();
            boolean onlyAllianceFailed = true;
            Check alliance = null;
            for (Check c : checks) {
                if (c.id.equals("alliance")) {
                    alliance = c;
                } else if (!c.passed) {
                    onlyAllianceFailed = false;
                }
            }
            if (!onlyAllianceFailed || alliance == null || alliance.passed) return this;
            for (Check c : checks) {
                if ("alliance".equals(c.id)) {
                    replaced.add(new Check(c.id, c.label, true, "任务将在准备窗口自动退出联盟"));
                } else {
                    replaced.add(c);
                }
            }
            return new Evaluation(true, configVersion, targetKid, targetName, permitsRequired,
                    permitsOwned, remainingQuota, freedomStartMillis, freedomEndMillis,
                    phase, replaced, warnings);
        }
    }
}
