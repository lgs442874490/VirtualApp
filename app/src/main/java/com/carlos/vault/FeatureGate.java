package com.carlos.vault;

import android.content.Context;

import com.carlos.BuildConfig;
import com.carlos.vault.license.LicenseConfig;
import com.carlos.vault.license.LicenseManager;

import java.util.List;

/**
 * 功能分级门控 —— 全 App 唯一事实源（批次7，2026-10-23；2026-11-06 接入服务端矩阵）。
 *
 * <p>背景（用户要求）：
 * <ul>
 *   <li><b>没有对应权限的功能不要显示</b>：兑换码三级 ——
 *       T1 普通（只有分身）/ T2 会员（日常脚本）/ T3 高级会员（高阶功能）/ GM（全部已实现）；</li>
 *   <li><b>没开发/没立项的功能不要放出去</b>：未实现功能一律隐藏，即使 GM 也不显示。</li>
 * </ul>
 *
 * <p>判定优先级（2026-11-06）：
 * <ol>
 *   <li>{@code implemented=false} → 恒隐藏（任何角色）；</li>
 *   <li>{@code debugOnly=true} 且非 debug 包 → 恒隐藏；</li>
 *   <li>服务端已下发功能矩阵（{@link LicenseManager#featuresPresent()}）→ <b>按矩阵 key 命中</b>；</li>
 *   <li>未下发矩阵（旧服务端/旧票据）→ 回退 {@code minTier} 比较（与历史行为一致）。</li>
 * </ol>
 *
 * <p>约定：UI 层只允许通过本类判断功能可见性；授权可用性（是否已激活/到期/被吊销）
 * 仍由既有 {@link LicenseManager#localUsable()} 与 {@code gateForLaunch} 负责。
 *
 * <p>本类为纯 Java 判定（{@link #visible(Feature, int, boolean, List, boolean)} 可在 JVM 单测直跑，
 * 不依赖 Android 运行时）。Android 相关方法只做“读数”。
 */
public final class FeatureGate {

    /** 未激活/无权限（fail-safe）。UI 判断用 0 级，不做任何功能放行。 */
    public static final int TIER_NONE = 0;
    /** T1 普通：只有分身（账号池/启动/基础设置）。 */
    public static final int TIER_BASIC = LicenseConfig.TIER_BASIC;
    /** T2 会员：日常脚本（今日/日常/七模块/自动化状态/通知）。 */
    public static final int TIER_MEMBER = LicenseConfig.TIER_MEMBER;
    /** T3 高级会员：高阶功能（守护盟友/敌情监控/工具箱等）。 */
    public static final int TIER_PREMIUM = LicenseConfig.TIER_PREMIUM;
    /** GM：全部"已实现且非调试包"功能（2026-11-06 管理后台）。 */
    public static final int TIER_GM = LicenseConfig.TIER_GM;

    private FeatureGate() {
    }

    /**
     * 功能清单（矩阵唯一事实源）：{@code key / 最低等级 / 是否已实现 / 是否仅调试包 / 中文名}。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code implemented=false}：未开发/未立项 → 任何等级都隐藏（含 GM）；</li>
     *   <li>{@code debugOnly=true}：调试/实测项 → 仅 debug 包可见，正式包隐藏；</li>
     *   <li>其余按服务端矩阵（未下发时按 {@code minTier}）判定。</li>
     * </ul>
     */
    public enum Feature {
        // ===== T1 普通：分身 / 账号 / 启动 / 基础设置 =====
        ACCOUNT_POOL("account_pool", TIER_BASIC, true, false, "账号池"),
        ACCOUNT_ADD("account_add", TIER_BASIC, true, false, "添加分身账号"),
        ACCOUNT_START("account_start", TIER_BASIC, true, false, "启动分身"),
        BASIC_SETTINGS("basic_settings", TIER_BASIC, true, false, "基础设置"),
        LICENSE_MANAGE("license_manage", TIER_BASIC, true, false, "授权管理"),

        // ===== T2 会员：日常脚本 =====
        TODAY_TAB("today_tab", TIER_MEMBER, true, false, "今日页"),
        TASK_TAB("task_tab", TIER_MEMBER, true, false, "日常页"),
        AUTOMATION_STATUS("automation_status", TIER_MEMBER, true, false, "自动化状态"),
        DAILY_CONFIG("daily_config", TIER_MEMBER, true, false, "任务/日常配置"),
        SEVEN_MODULES("seven_modules", TIER_MEMBER, true, false, "七模块任务"),
        NOTIFY_SETTINGS("notify_settings", TIER_MEMBER, true, false, "通知设置"),
        RUN_PROGRESS("run_progress", TIER_MEMBER, true, false, "执行进度/日志"),
        KEEP_ALIVE("keep_alive", TIER_MEMBER, true, false, "定时与保活"),
        AID_ALLIANCE("aid_alliance", TIER_MEMBER, true, false, "一键帮助全盟"),

        // ===== T3 高级会员：高阶已实现（有真实实现/入口） =====
        // 敌情监控 = T3 六项中唯一已完整实现（App EnemyWatch/EnemyWatchStore + 服务端 WarNotify/WeComNotifier）
        ENEMY_WATCH("enemy_watch", TIER_PREMIUM, true, false, "敌情监控"),
        // 真机监控（2026-10-25）：服务端任务级协议监控 /v1/vault/monitor + App daily/MonitorActivity。
        // T3 正式包可见（非 debugOnly）：用户当前即 T3，需要在真机上观察领取/任务链路与会话顶号。
        // 旧服务端无该接口时页面内提示「服务端未升级」，不影响入口（可先装 App 等部署）。
        DEV_MONITOR("dev_monitor", TIER_PREMIUM, true, false, "真机监控"),
        // 工具箱：release 包仅剩敌情监控（T3）与调试工具 → 跟随 T3；T2 无可渲染工具则不显示
        TOOLBOX("toolbox", TIER_PREMIUM, true, false, "工具箱"),
        // 守护盟友：T3 高阶（批次11 用户确认放开 UI）。服务端 ServerGuardianEngine/Launcher 已上线：
        // 默认 enabled=false + dry_run=true（演练模式只记录不派兵），App 配置页显式切换实发（二次确认）。
        GUARDIAN("guardian", TIER_PREMIUM, true, false, "守护盟友"),
        // 观察点（批次 B 2026-10-30）：工具箱内可视化编辑服务器 watchpoint.json（API get/set/enable）。
        WATCHPOINT("watchpoint", TIER_PREMIUM, true, false, "观察点"),
        // 真机抓包（T3，2026-11-01）：VpnService+tun2socks 免 root 抓游戏 TCP，sproto 实时解码。
        // 2026-11-01 真机验证通过（分裂路由 43/101 网段，不影响游戏联网）→ release 放开；
        // 入口默认关闭，页面内有“抓包期间游戏可能短暂重连”的提示。
        PROTO_CAPTURE("proto_capture", TIER_PREMIUM, true, false, "真机抓包"),
        // 起号（M5，2026-11-07 立项恢复）：BootstrapActivity 注册/起号/批次三区 + 账号回流。
        // 2026-11-06 决策6 曾作为零引用死枚举删除；现服务端 registration/bootstrap API
        // 已上线（M3/M4b），App 端 UI 落地 → implemented=true 转正，从工具箱入口卡进入。
        ACCOUNT_FARM("account_farm", TIER_PREMIUM, true, false, "起号"),

        // ===== T3 高级会员：尚未实现（即使 T3 也隐藏，立项完成后再打开） =====
        MOVE_CITY("move_city", TIER_PREMIUM, false, false, "迁城"),
        COORD_QUERY("coord_query", TIER_PREMIUM, false, false, "坐标查询"),
        RECHARGE_RECORD("recharge_record", TIER_PREMIUM, false, false, "充值记录"),
        BEHEMOTH_BATCH("behemoth_batch", TIER_PREMIUM, false, false, "巨兽批量"),

        // ===== T2 日常里的“例外项（UI 有、服务端无/仅部分）”：见门控审计 §3.3/§4 =====
        // guardian/war：FnRegistry 有行但 ServerTasks 55 项无对应任务，勾选后不执行 → 隐藏
        WAR_STATE("war_state", TIER_MEMBER, false, false, "战争状态"),
        // 背包自动使用范围产品未确认（解析已修，自动开箱未立项）→ 隐藏
        BACKPACK_ITEMS("backpack_items", TIER_MEMBER, false, false, "背包道具"),
        // 巨兽狩猎：仅“原生自动上车”（服务端 post 被拒 1507/1600，每日发起未做）；UI 保留可关，
        // 但文案需改为“仅原生自动上车”（FnRegistry 文案修改不在本任务写作用域，已在交付说明列出）
        BEHEMOTH_AUTOJOIN("behemoth_autojoin", TIER_MEMBER, true, false, "巨兽狩猎（仅原生自动上车）"),

        // 编队管理（2026-11-06 决策6）：FormationManagerActivity 已实现并多入口上线
        //（今日页快捷卡/功能页编队卡/采集设置页），属日常脚本能力 → T2；入口统一走
        // FeatureGate.visible(FORMATION_MANAGE) 防深链越权。
        FORMATION_MANAGE("formation_manage", TIER_MEMBER, true, false, "编队管理"),

        // ===== 调试/实测项（仅调试包可见，正式包一律不渲染） =====
        PROTOCOL_LOGGER("protocol_logger", TIER_BASIC, true, true, "协议监听");

        // 2026-11-06 决策6：删除零引用死枚举（RANKING/RALLY_ATTACK/ACTIVITY_RANK/
        // MATCH3/TROOP_RUSH/PET_RESET/IMMIGRATION/RECON_REPORT 及 PROTOCOL_PROBE）——
        // 历史规划中功能的占位登记，生产代码零引用；PROTOCOL_PROBE 实际由 debugBuild 分支控制。
        // 其中 ACCOUNT_FARM 已于 2026-11-07 随 M5 起号立项恢复（见上方枚举），其余仍按需
        // 重新登记，不要凭空恢复。

        /** 稳定 key（日志/状态/document 用，不随中文名变化）。 */
        public final String key;
        /** 最低可见等级（T1/T2/T3；未下发矩阵时的回退依据）。 */
        public final int minTier;
        /** 是否已有真实实现（false = 未开发/未立项，任何等级都隐藏）。 */
        public final boolean implemented;
        /** 是否仅调试包可见（调试/实测工具）。 */
        public final boolean debugOnly;
        /** 界面中文名。 */
        public final String label;

        Feature(String key, int minTier, boolean implemented, boolean debugOnly, String label) {
            this.key = key;
            this.minTier = minTier;
            this.implemented = implemented;
            this.debugOnly = debugOnly;
            this.label = label;
        }
    }

    // ================= 矩阵判定（UI 唯一入口） =================

    /**
     * 该功能在当前角色/功能矩阵/构建类型下是否应显示（Android 入口）。
     *
     * <p>矩阵来源：{@link LicenseManager#features()}（服务端 verify/activate 下发并落盘）。
     * 未下发（旧服务端/旧票据）时回退硬编码 minTier。
     */
    public static boolean visible(Context ctx, Feature feature) {
        if (ctx == null) return false;
        try {
            LicenseManager lm = LicenseManager.get(ctx);
            if (lm == null) return false;
            lm.ensureLoaded();
            return visible(feature, currentTier(ctx), lm.featuresPresent(),
                    lm.features(), isDebugBuild(ctx));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 纯函数：是否可见（JVM 单测直跑）。
     *
     * @param role            当前角色 1/2/3/4（GM）；0=无授权
     * @param featuresPresent 服务端是否已下发矩阵
     * @param features        矩阵 key（featuresPresent=true 时生效；空列表=明确无功能）
     * @param debugBuild      是否调试包
     */
    public static boolean visible(Feature feature, int role, boolean featuresPresent,
                                  List<String> features, boolean debugBuild) {
        if (feature == null) return false;                  // 未知功能：安全隐藏
        if (!feature.implemented) return false;             // 未开发/未立项：即使 GM 也隐藏
        if (feature.debugOnly && !debugBuild) return false; // 调试实测项：仅调试包
        if (featuresPresent) {
            // 服务端矩阵为准（大小写/顺序无关；未知 key 忽略）。
            if (features == null) return false;
            for (String k : features) {
                if (feature.key.equals(k)) return true;
            }
            return false;
        }
        return sanitizeTier(role) >= requiredTier(feature); // 回退：无矩阵时按等级
    }

    /**
     * 纯函数：回退判定（无服务端矩阵时使用；保留给单测与旧调用方）。
     *
     * @deprecated 优先使用 {@link #visible(Feature, int, boolean, List, boolean)}（含服务端矩阵）。
     */
    @Deprecated
    public static boolean visible(Feature feature, int tier, boolean debugBuild) {
        return visible(feature, tier, false, null, debugBuild);
    }

    /** 该功能所需最低等级；null/未知按 T1 处理（由 visible() 兜底隐藏）。 */
    public static int requiredTier(Feature feature) {
        return feature == null ? TIER_NONE : feature.minTier;
    }

    /** 隐藏原因文案（用于需要解释的场景；空串=无等级门槛）。 */
    public static String reasonText(Feature feature) {
        if (feature == null) return "该功能未开放";
        if (!feature.implemented) return "该功能尚未开放";
        if (feature.debugOnly) return "仅调试版可用";
        switch (requiredTier(feature)) {
            case TIER_PREMIUM:
                return "需要高级会员权限（T3）";
            case TIER_MEMBER:
                return "需要会员权限（T2）";
            default:
                return "";
        }
    }

    /** 中文名（null 安全）。 */
    public static String label(Feature feature) {
        return feature == null ? "未知功能" : feature.label;
    }

    /** 等级归一化（UI 用：0=无权限，1/2/3/4(GM) 封顶；**不**把 0 提升为 3，与票据解析区分）。 */
    public static int sanitizeTier(int raw) {
        if (raw <= TIER_NONE) return TIER_NONE;
        if (raw >= TIER_GM) return TIER_GM;
        return raw;
    }

    /** 角色中文名（含 GM；直接复用 LicenseConfig，避免两处文案漂移）。 */
    public static String tierName(int role) {
        return LicenseConfig.roleName(role);
    }

    // ================= 日常功能（FnRegistry）门控 =================

    /**
     * 日常功能 id → 功能矩阵项（FnRegistry 不在本任务写作用域，故矩阵映射集中在这里）。
     *
     * <p>需要映射的三类：
     * <ul>
     *   <li>高阶功能：guardian（T3）；</li>
     *   <li>UI 有行但服务端无对应任务：war（隐藏）；</li>
     *   <li>产品未确认/仅部分：backpack（隐藏）、behemoth（保留可关）。</li>
     * </ul>
     * 其余日常模块均 T2（页面级由 TAB 门控），这里返回 null 表示“不额外过滤”。
     */
    public static Feature dailyFnFeature(String fnId) {
        if (fnId == null) return null;
        switch (fnId) {
            case "guardian":
                return Feature.GUARDIAN;
            case "war":
                return Feature.WAR_STATE;
            case "backpack":
                return Feature.BACKPACK_ITEMS;
            case "behemoth":
                return Feature.BEHEMOTH_AUTOJOIN;
            default:
                return null;
        }
    }

    /** 日常功能是否应出现在列表（纯函数，无矩阵；保留给单测）。 */
    public static boolean dailyFnVisible(String fnId, int tier, boolean debugBuild) {
        Feature f = dailyFnFeature(fnId);
        return f == null || visible(f, tier, false, null, debugBuild);
    }

    /** 日常功能是否应出现在列表（Android 入口，走服务端矩阵）。 */
    public static boolean dailyFnVisible(Context ctx, String fnId) {
        Feature f = dailyFnFeature(fnId);
        return f == null || visible(ctx, f);
    }

    /**
     * 上传给服务器的角色任务配置过滤（2026-11-06 决策：隐藏 = 不执行）。
     *
     * <p>背景：backpack 曾被门控隐藏（{@code BACKPACK_ITEMS.implemented=false}），但
     * {@code DailyTasks} 默认启用、{@code DailyConfigStore.fillMissing} 补齐 true 后整包上传，
     * 服务端按 true 每日执行——用户不可见、不可关的功能在跑。本方法在上传前把所有
     * {@link #dailyFnVisible} 判不可见的日常项强制置 false，保证「UI 隐藏的任务绝不在服务器执行」。
     *
     * <p>纯函数（JVM 单测直跑）：返回新 map，不修改入参；入参 null 安全返回 null。
     */
    public static java.util.Map<String, Boolean> filterHiddenDailyFns(
            java.util.Map<String, Boolean> config, int tier, boolean debugBuild) {
        if (config == null) return null;
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>(config);
        for (java.util.Map.Entry<String, Boolean> e : out.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !dailyFnVisible(e.getKey(), tier, debugBuild)) {
                e.setValue(false);
            }
        }
        return out;
    }

    /** 上传过滤（Android 便捷入口，读当前票据等级）。 */
    public static java.util.Map<String, Boolean> filterHiddenDailyFns(
            Context ctx, java.util.Map<String, Boolean> config) {
        return filterHiddenDailyFns(config, currentTier(ctx), isDebugBuild(ctx));
    }

    // ================= 环境读取（Android，仅读数不参与矩阵判定） =================

    /**
     * 当前角色：本地票据 role（服务端权威，激活/联网校验时落盘；1/2/3/4，4=GM）。
     *
     * <p>fail-safe：无 Context/LicenseManager、授权不可用（未激活/到期/被吊销）或判定异常
     * 一律返回 {@link #TIER_NONE}（不越权放行）；存量旧票据由 LicenseManager 默认 T3 兼容。
     */
    public static int currentTier(Context ctx) {
        if (ctx == null) return TIER_NONE;
        try {
            LicenseManager lm = LicenseManager.get(ctx);
            if (lm == null) return TIER_NONE;
            lm.ensureLoaded();
            if (!lm.localUsable()) return TIER_NONE;
            return sanitizeTier(LicenseConfig.normalizeRole(lm.role()));
        } catch (Throwable t) {
            return TIER_NONE;
        }
    }

    /**
     * 是否调试包（决定调试/实测项是否渲染）。取不到信息时按正式包保守处理（隐藏）。
     *
     * <p><b>批次8（P0-1）修正</b>：不能用 {@code ApplicationInfo.FLAG_DEBUGGABLE}——本项目
     * release 构建为支持 run-as 读取容器数据**恒开 debuggable true**（{@code app/build.gradle:83}）；
     * 且 AGP 7.2 生成的 {@link BuildConfig#DEBUG} 跟随构建类型 debuggable 标记，release 包里
     * 同样为 {@code true}（实测生成代码 {@code DEBUG=Boolean.parseBoolean("true")}）。
     * 唯一稳定的“正式包/调试包”判据是 {@link BuildConfig#BUILD_TYPE}：
     * release="release"、debug="debug"。
     */
    public static boolean isDebugBuild(Context ctx) {
        try {
            return isDebugBuildType(BuildConfig.BUILD_TYPE);
        } catch (Throwable t) {
            return false;   // 判定异常 → 按正式包处理（调试项不渲染，宁缺勿滥）
        }
    }

    /** 纯函数（JVM 单测）：构建类型是否 debug。 */
    public static boolean isDebugBuildType(String buildType) {
        return "debug".equals(buildType);
    }

    /**
     * 工具箱当前角色/构建/矩阵下的可用工具数（release 下 T3 起号 + 敌情监控 + 真机监控 +
     * 观察点 + 真机抓包；debug 包额外协议监听）。用于 ToolBox「无任何可见工具时整组/说明卡都不渲染」。
     */
    public static int visibleToolCount(Context ctx) {
        int n = 0;
        if (visible(ctx, Feature.ACCOUNT_FARM)) n++;
        if (visible(ctx, Feature.ENEMY_WATCH)) n++;
        if (visible(ctx, Feature.DEV_MONITOR)) n++;
        if (visible(ctx, Feature.WATCHPOINT)) n++;
        if (visible(ctx, Feature.PROTO_CAPTURE)) n++;
        if (visible(ctx, Feature.PROTOCOL_LOGGER)) n++;
        return n;
    }

    /** 纯函数版本（无矩阵；保留给单测）。 */
    @Deprecated
    public static int visibleToolCount(int tier, boolean debugBuild) {
        int n = 0;
        if (visible(Feature.ACCOUNT_FARM, tier, false, null, debugBuild)) n++;
        if (visible(Feature.ENEMY_WATCH, tier, false, null, debugBuild)) n++;
        if (visible(Feature.DEV_MONITOR, tier, false, null, debugBuild)) n++;
        if (visible(Feature.WATCHPOINT, tier, false, null, debugBuild)) n++;
        if (visible(Feature.PROTO_CAPTURE, tier, false, null, debugBuild)) n++;
        if (visible(Feature.PROTOCOL_LOGGER, tier, false, null, debugBuild)) n++;
        return n;
    }
}
