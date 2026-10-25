package com.carlos.vaultserver.guardian;

/**
 * 守护盟友 P1（驻防对等加速）——<b>纯函数</b>加速决策规划器（2026-10-25）。
 *
 * <p><b>背景</b>：服务端 P0 只会「立即发射 type=5 驻防」，敌人在途中加速（{@code situation_update}
 * 的 endtime 前移/压缩）后我方会被反超。App 本地模式的 R3 加速链（{@code GuardianLaunch.java:913-1062}）
 * 已验证「{@code req_world_march_speedup} 对在途 type=5 驻防有效、严格乘法压缩、背靠背连发均生效」，
 * 本类把那套经验抽成<b>可单测、可循环推进</b>的纯函数：<b>每次调用只决定下一步</b>，
 * 宿主（{@link ServerGuardianEngine} 的 guardExec 单线程）执行动作、拿响应 endtime 后再调用一次，
 * 直到「先到」或「不可为」。本类不碰网络、不读时钟、不写状态。
 *
 * <p><b>数学（实证口径，{@code design/守护盟友-会话交接-20261015.md} ③⑤）</b>：
 * <ul>
 *   <li>加速只作用于我方行军的<b>剩余时间</b>：25% 道具（itemid=301900）→ {@code 剩余 × 0.75}；
 *       50% 道具（itemid=301901）→ {@code 剩余 × 0.50}；实测严格乘法、零偏差、可背靠背连发；</li>
 *   <li>「先到」判定：{@code 敌方剩余 − 我方剩余 ≥ required}，其中
 *       {@code required = margin}（默认 2s，可配）。即我方 ETA 至少领先 margin 秒；</li>
 *   <li>若 {@code margin} 比敌方剩余还大（敌马上落地），margin 物理不可达，退化为「严格先到」
 *       （领先 ≥1ms），避免在数学无解时白烧道具；</li>
 *   <li>无固定 16s 门槛（用户裁决：敌出发后任何时刻都可以加速）；唯一限制是末端约最后 1 秒加不动，
 *       实施时留 {@link #MIN_REMAIN_MS} = 2s 安全余量（我方剩余 ≤2s 不再出手）。</li>
 * </ul>
 *
 * <p><b>选档规则（用户 2026-10-25 拍板，必须遵守）</b>：
 * <ol>
 *   <li><b>道具优先 25%</b>：25% 能达成（可多次叠加）就只用 25%，不升 50%；</li>
 *   <li>25% 一次不够 → <b>循环叠加多次 25%</b>；只有 25% 在库存/单轮上限内<b>仍不够</b>时才升 50%；</li>
 *   <li>道具不设预算 cap；受库存、{@code max_speedups_per_march} 与单轮安全上限
 *       {@link #MAX_SHOTS_PER_ROUND} 约束；</li>
 *   <li><b>钻石默认零消耗</b>：{@code diamond_speedup_enabled=false}（默认）时本类只在无道具时返回
 *       {@link Action#NONE}（诚实止损）；只有开关显式打开才可能返回 {@link Action#NEED_DIAMOND}，
 *       宿主（{@link ServerGuardianEngine}）随即按 {@code diamond_max_per_march} 购买 301901
 *       （{@code req_shop_quick_buy}）并立即用该 50% 加速；购买上限/失败/库存未知时诚实止损。</li>
 * </ol>
 *
 * <p><b>边界</b>：敌方已到达 / 我方已到达 / 双方 ETA 相同（视为未先到，继续算）/ 库存 0 /
 * margin 大于差距 / 时钟异常（负值或异常大值）/ 末端 2s / 次数上限，全部有确定返回值，
 * 不抛异常、不返回 null。
 *
 * <p><b>线程</b>：无状态静态方法，任意线程可调；宿主负责幂等与库存记账。
 */
public final class GuardianSpeedupPlanner {

    // ---------------- 道具/协议常量（A-10 实证；勿凭猜测改） ----------------

    /** 一阶加速道具：25%，剩余时间 ×0.75。 */
    public static final long ITEM_SPEEDUP_25 = 301900L;
    /** 二阶加速道具：50%，剩余时间 ×0.50。 */
    public static final long ITEM_SPEEDUP_50 = 301901L;
    /** 25% 道具压缩系数。 */
    public static final double FACTOR_25 = 0.75d;
    /** 50% 道具压缩系数。 */
    public static final double FACTOR_50 = 0.5d;

    // ---------------- 行为常量 ----------------

    /** 默认先至余量（秒；{@code speedup_margin_s} 缺省值，对齐 P1 验收「我方 endtime ≤ 敌 endtime −1s」）。 */
    public static final long DEFAULT_MARGIN_MS = 2_000L;
    /**
     * 末端安全余量：我方剩余 ≤ 2s 时不再发加速请求。
     * 实证「末端约最后 1 秒加不动」（App 交接 ㉒：floor 只留落地缓冲 ≥2.5s；用户裁决不按旧 16s floor），
     * 这里取 2s 留出请求往返 + 响应处理余量。
     */
    public static final long MIN_REMAIN_MS = 2_000L;
    /** 单轮加速链安全上限（防请求风暴；不是道具预算 cap——敌再压缩会触发新一轮最小响应）。 */
    public static final int MAX_SHOTS_PER_ROUND = 6;
    /** 可达性推演的最大道具发数（floor 下 24h 行军也只需 ≤38 发 25%，64 足够）。 */
    static final int REACH_SHOT_CAP = 64;
    /** 合理的剩余时间上界（24h）：超出视为时钟/endtime 异常，不加速。 */
    public static final long MAX_SANE_REMAIN_MS = 24L * 3600_000L;

    /** 规划动作。 */
    public enum Action {
        /** 使用 25% 道具（301900）。 */
        USE_25,
        /** 使用 50% 道具（301901）。 */
        USE_50,
        /** 不动作（已先到/不可为/库存不足且钻石关/上限）。 */
        NONE,
        /** 道具不足但配置允许钻石——宿主将按 {@code diamond_max_per_march} 购买 301901 并立即使用。 */
        NEED_DIAMOND
    }

    private GuardianSpeedupPlanner() {
    }

    // =====================================================================
    // 入参 / 出参（不可变；便于宿主循环与单测断言）
    // =====================================================================

    /**
     * 规划入参（一次决策的全部已知量；宿主每次拿真实响应后重新组装）。
     *
     * <p>时间单位统一 <b>毫秒</b>，由宿主用服务器时钟（{@code GameServerClock.nowServerMs()}，
     * 未锁定时退回本机时钟）计算 {@code endtime × 1000 − nowMs}，保留毫秒精度避免秒域取整抖动。
     */
    public static final class Input {
        /** 我方驻防行军剩余毫秒（endtime×1000 − nowMs；≤0=已到达/未知）。 */
        public final long ourRemainMs;
        /** 敌方进攻行军剩余毫秒（endtime×1000 − nowMs；≤0=已落地）。 */
        public final long enemyRemainMs;
        /** 目标先至余量毫秒（默认 {@link #DEFAULT_MARGIN_MS}；负值=时钟异常）。 */
        public final long marginMs;
        /** 25% 道具当前可用库存（负值按 0）。 */
        public final long stock25;
        /** 50% 道具当前可用库存（负值按 0）。 */
        public final long stock50;
        /** 本行军已用加速发数（记账值）。 */
        public final int usedThisMarch;
        /** 本行军加速发数上限（0=不限；{@code max_speedups_per_march}）。 */
        public final int maxPerMarch;
        /** 本轮链已用发数（宿主传入，用于单轮安全上限）。 */
        public final int usedThisRound;
        /** 是否允许钻石路径（{@code diamond_speedup_enabled}；默认 false）。 */
        public final boolean diamondAllowed;

        /** 便捷构造：不带次数上限/钻石（库存 + margin；等价于「不限次数、钻石关」）。 */
        public Input(long ourRemainMs, long enemyRemainMs, long marginMs, long stock25, long stock50) {
            this(ourRemainMs, enemyRemainMs, marginMs, stock25, stock50, 0, 0, 0, false);
        }

        public Input(long ourRemainMs, long enemyRemainMs, long marginMs,
                     long stock25, long stock50,
                     int usedThisMarch, int maxPerMarch, int usedThisRound, boolean diamondAllowed) {
            this.ourRemainMs = ourRemainMs;
            this.enemyRemainMs = enemyRemainMs;
            this.marginMs = marginMs;
            this.stock25 = stock25;
            this.stock50 = stock50;
            this.usedThisMarch = usedThisMarch;
            this.maxPerMarch = maxPerMarch;
            this.usedThisRound = usedThisRound;
            this.diamondAllowed = diamondAllowed;
        }
    }

    /** 一次决策结果（不可变）。 */
    public static final class Step {
        /** 动作。 */
        public final Action action;
        /** 本次动作要用的道具 id（USE_25/USE_50 有效；其余 0）。 */
        public final long itemId;
        /** 动作前我方剩余毫秒（NONE 时=入参值）。 */
        public final long ourRemainMs;
        /** 敌方剩余毫秒。 */
        public final long enemyRemainMs;
        /** margin（毫秒）。 */
        public final long marginMs;
        /** 本次要求的先至领先量（毫秒；margin 不可达时=1ms）。 */
        public final long requiredAheadMs;
        /** 动作前领先量（敌方−我方；负=落后）。 */
        public final long aheadBeforeMs;
        /** 动作后预计我方剩余毫秒（NONE 时=动作前值）。 */
        public final long projectedRemainMs;
        /** 动作后预计领先量（敌方−预计我方；负=仍落后）。 */
        public final long projectedAheadMs;
        /** 25% 库存（入参快照）。 */
        public final long stock25;
        /** 50% 库存（入参快照）。 */
        public final long stock50;
        /** 稳定原因码（日志/单测断言；中文原因见 {@link #reason}）。 */
        public final String reasonCode;
        /** 可读原因（中文；写 FileLog/账本）。 */
        public final String reason;

        Step(Action action, long itemId, long ourRemainMs, long enemyRemainMs, long marginMs,
             long requiredAheadMs, long aheadBeforeMs, long projectedRemainMs,
             long stock25, long stock50, String reasonCode, String reason) {
            this.action = action;
            this.itemId = itemId;
            this.ourRemainMs = ourRemainMs;
            this.enemyRemainMs = enemyRemainMs;
            this.marginMs = marginMs;
            this.requiredAheadMs = requiredAheadMs;
            this.aheadBeforeMs = aheadBeforeMs;
            this.projectedRemainMs = projectedRemainMs;
            this.projectedAheadMs = enemyRemainMs - projectedRemainMs;
            this.stock25 = stock25;
            this.stock50 = stock50;
            this.reasonCode = reasonCode == null ? "" : reasonCode;
            this.reason = reason == null ? "" : reason;
        }

        /** 是否要发加速请求（USE_25/USE_50）。 */
        public boolean usesItem() {
            return action == Action.USE_25 || action == Action.USE_50;
        }

        @Override
        public String toString() {
            return "Step{" + action + (itemId > 0 ? " item=" + itemId : "")
                    + " our=" + ourRemainMs + "ms enemy=" + enemyRemainMs + "ms"
                    + " aheadBefore=" + aheadBeforeMs + "ms projected=" + projectedRemainMs + "ms"
                    + " reason=" + reasonCode + "}";
        }
    }

    // =====================================================================
    // 主决策（纯函数；每次给下一步）
    // =====================================================================

    /**
     * 规划下一步动作（纯函数；不抛异常）。
     *
     * <p>宿主循环约定：调用 → 若 {@link Step#usesItem()} 则发 {@code req_world_march_speedup} →
     * 用响应新 endtime 重新构造 {@link Input}（{@code usedThisRound+1}、库存按实际扣减）再调用，
     * 直到 {@link Action#NONE}/{@link Action#NEED_DIAMOND} 或响应无前移。
     *
     * @param in 入参（null → NONE/clock_anomaly）
     * @return 下一步决策（非 null）
     */
    public static Step next(Input in) {
        if (in == null) {
            return new Step(Action.NONE, 0L, 0L, 0L, 0L, 1L, 0L, 0L,
                    0L, 0L, "clock_anomaly", "入参为空（时钟/状态异常），不加速");
        }
        long our = in.ourRemainMs;
        long enemy = in.enemyRemainMs;
        long margin = in.marginMs;
        long stock25 = Math.max(0L, in.stock25);
        long stock50 = Math.max(0L, in.stock50);

        // ---- 边界 1：敌方已到达（我方再加速也追不上已落地的攻击） ----
        if (enemy <= 0) {
            return none(in, "enemy_landed",
                    "敌方已到达（剩余 " + enemy + "ms），加速无意义");
        }
        // ---- 边界 2：我方已到达/行军结束 ----
        if (our <= 0) {
            return none(in, "our_arrived",
                    "我方已到达或行军已结束（剩余 " + our + "ms），无法加速");
        }
        // ---- 边界 3：时钟/数据异常（负 margin、异常大的剩余） ----
        if (margin < 0 || our > MAX_SANE_REMAIN_MS || enemy > MAX_SANE_REMAIN_MS) {
            return none(in, "clock_anomaly",
                    "时钟/数据异常（我方剩余 " + our + "ms，敌方剩余 " + enemy
                            + "ms，margin " + margin + "ms），不加速");
        }

        long required = requiredAheadMs(enemy, margin);
        boolean marginReachable = enemy > Math.max(1L, margin);

        // ---- 边界 4：已先到（先至 ≥ margin；margin 不可达时按严格先到） ----
        if (isSatisfied(our, enemy, required)) {
            if (marginReachable) {
                return none(in, "already_ahead",
                        "已先到（领先 " + (enemy - our) + "ms ≥ margin " + margin + "ms），无需加速");
            }
            return none(in, "margin_unreachable_ahead",
                    "已严格先到（领先 " + (enemy - our) + "ms），但 margin " + margin
                            + "ms 物理不可达（敌方剩余 " + enemy + "ms < margin），不再消耗道具");
        }

        // ---- 边界 5：末端安全余量（无 16s floor，只有末端 ~1s 加不动） ----
        if (our <= MIN_REMAIN_MS) {
            return none(in, "tail_guard",
                    "末端安全余量（我方剩余 " + our + "ms ≤ " + MIN_REMAIN_MS
                            + "ms，末端约 1s 加不动），不加速");
        }

        // ---- 边界 6：次数上限（本行军 / 单轮链） ----
        int usedMarch = Math.max(0, in.usedThisMarch);
        int usedRound = Math.max(0, in.usedThisRound);
        int maxMarch = in.maxPerMarch;
        if (maxMarch > 0 && usedMarch >= maxMarch) {
            return none(in, "march_limit",
                    "本行军加速次数已达上限 max_speedups_per_march=" + maxMarch + "，止损");
        }
        if (usedRound >= MAX_SHOTS_PER_ROUND) {
            return none(in, "round_limit",
                    "单轮加速链已达安全上限 " + MAX_SHOTS_PER_ROUND + " 发，止损");
        }
        int marchLeft = maxMarch > 0 ? maxMarch - usedMarch : Integer.MAX_VALUE;
        int roundLeft = MAX_SHOTS_PER_ROUND - usedRound;
        int avail25 = (int) Math.min(stock25, Math.min(marchLeft, roundLeft));
        int avail50 = (int) Math.min(stock50, Math.min(marchLeft, roundLeft));
        int total25 = avail25;
        int total50 = avail50;

        // ---- 分支 A：25% 优先（能达成 → 只用 25%；一次不够 → 循环叠加） ----
        if (total25 > 0) {
            long after25 = projectRemainMs(our, FACTOR_25);
            if (reachableWithin(after25, enemy, required, total25 - 1, total50)) {
                int k25 = minShots(our, enemy, required, FACTOR_25);
                String code;
                String reason;
                if (k25 == 1) {
                    code = "use_25_sufficient";
                    reason = "25% 一发足够保持先到（预计剩 " + after25 + "ms vs 敌 " + enemy
                            + "ms，领先 " + (enemy - after25) + "ms ≥ margin " + margin + "ms）";
                } else if (k25 > 1 && k25 <= total25) {
                    code = "use_25_stack";
                    reason = "25% 单发不够，叠加 " + k25 + " 发可达（本次第 1 发后预计剩 "
                            + after25 + "ms，25% 可用 " + total25 + " 发）";
                } else {
                    code = "use_25_stack";
                    reason = "25% 优先（单发后预计剩 " + after25 + "ms；纯 25% 需 "
                            + (k25 > 0 ? k25 + " 发" : "超出可达范围")
                            + "/可用 " + total25 + " 发，不足再按规则升 50%）";
                }
                return new Step(Action.USE_25, ITEM_SPEEDUP_25, our, enemy, margin, required,
                        enemy - our, after25, stock25, stock50, code, reason);
            }
        }

        // ---- 分支 B：25% 在库存/上限内无法达成 → 升 50% ----
        if (total50 > 0) {
            long after50 = projectRemainMs(our, FACTOR_50);
            if (reachableWithin(after50, enemy, required, total25, total50 - 1)) {
                int k50 = minShots(our, enemy, required, FACTOR_50);
                String why = total25 <= 0
                        ? "25% 库存为 0/已达上限"
                        : "25% 无法在库存(" + stock25 + ")/上限内达成";
                String reason = why + "，升 50%（" + (k50 == 1
                        ? "一发足够"
                        : "预计需 " + k50 + " 发") + "；本次后预计剩 "
                        + after50 + "ms vs 敌 " + enemy + "ms）";
                return new Step(Action.USE_50, ITEM_SPEEDUP_50, our, enemy, margin, required,
                        enemy - our, after50, stock25, stock50, "use_50_required", reason);
            }
        }

        // ---- 分支 C：没有可用道具 → 钻石开关（默认关=诚实止损） ----
        if (total25 <= 0 && total50 <= 0) {
            if (in.diamondAllowed) {
                return new Step(Action.NEED_DIAMOND, 0L, our, enemy, margin, required,
                        enemy - our, our, stock25, stock50, "need_diamond",
                        "道具库存为 0（25=" + stock25 + "/50=" + stock50
                                + "），钻石加速已开启 → 需走钻石路径（宿主执行 req_shop_quick_buy 购买 301901 后立即使用）");
            }
            return none(in, "stock_insufficient",
                    "道具库存为 0（25=" + stock25 + "/50=" + stock50
                            + "）且钻石加速关闭（默认），无法保证先到，诚实止损");
        }

        // ---- 分支 D：有道具但当前库存/上限内不可达（诚实止损，不浪费道具） ----
        boolean reachableWithCaps = reachableWithin(our, enemy, required, total25, total50);
        if (!reachableWithCaps) {
            // 忽略 max_speedups_per_march / 单轮链上限（只看库存）的可达性
            boolean reachableWithStock = reachableWithin(our, enemy, required,
                    (int) Math.min(stock25, MAX_SHOTS_PER_ROUND),
                    (int) Math.min(stock50, MAX_SHOTS_PER_ROUND));
            if (reachableWithStock) {
                return none(in, "limit_prevents_ahead",
                        "受 max_speedups_per_march/单轮链上限限制（本轮可用 " + (total25 + total50)
                                + " 发，库存 25=" + stock25 + "/50=" + stock50
                                + "），无法保证先到，诚实止损（不浪费道具）");
            }
            boolean reachableAtAll = reachableWithin(our, enemy, required,
                    REACH_SHOT_CAP, REACH_SHOT_CAP);
            if (reachableAtAll) {
                if (in.diamondAllowed) {
                    return new Step(Action.NEED_DIAMOND, 0L, our, enemy, margin, required,
                            enemy - our, our, stock25, stock50, "need_diamond",
                            "现有道具不足（25=" + stock25 + "/50=" + stock50
                                    + "，当前库存内无法先到），钻石加速已开启 → 需走钻石路径（宿主购买 301901 后立即使用）");
                }
                return none(in, "stock_insufficient",
                        "现有道具不足（25=" + stock25 + "/50=" + stock50
                                + "，当前库存内无法先到）且钻石加速关闭（默认），诚实止损");
            }
            return none(in, "unreachable",
                    "即使耗尽道具也无法满足先到（我方剩余 " + our + "ms / 敌方剩余 " + enemy
                            + "ms / margin " + margin + "ms），数学不可达（含末端 2s floor），止损");
        }
        // 防御：理论可达但首步分支未命中（不应发生）；保守止损，不发无依据请求
        return none(in, "unreachable",
                "可达性推演异常（首步未命中 25%/50% 分支），保守止损");
    }

    private static Step none(Input in, String code, String reason) {
        long our = in.ourRemainMs;
        long enemy = in.enemyRemainMs;
        long margin = in.marginMs;
        long required = requiredAheadMs(enemy, margin);
        return new Step(Action.NONE, 0L, our, enemy, margin, required,
                enemy - our, our, Math.max(0L, in.stock25), Math.max(0L, in.stock50), code, reason);
    }

    // =====================================================================
    // 数学工具（纯函数；供宿主日志复算与单测）
    // =====================================================================

    /**
     * 本次应要求的先至领先量（毫秒）：
     * <ul>
     *   <li>margin 可达（敌方剩余 &gt; margin）→ margin（≥1ms，margin≤0 时按 1ms=严格先到）；</li>
     *   <li>margin 物理不可达（敌方剩余 ≤ margin，敌马上落地）→ 退化为严格先到（1ms）。</li>
     * </ul>
     *
     * @param enemyRemainMs 敌方剩余毫秒
     * @param marginMs      配置 margin 毫秒（可负=异常）
     */
    public static long requiredAheadMs(long enemyRemainMs, long marginMs) {
        long margin = Math.max(1L, marginMs);
        return enemyRemainMs > margin ? margin : 1L;
    }

    /** 先到判定：敌方剩余 − 我方剩余 ≥ required（纯函数）。 */
    public static boolean isSatisfied(long ourRemainMs, long enemyRemainMs, long requiredAheadMs) {
        return enemyRemainMs - ourRemainMs >= Math.max(1L, requiredAheadMs);
    }

    /**
     * 单发加速后的预计剩余（毫秒）：25% → ×0.75；50% → ×0.50。
     * 仅用于决策/日志预估；真实值以 {@code req_world_march_speedup} 响应 endtime 为准。
     */
    public static long projectRemainMs(long remainMs, double factor) {
        if (remainMs <= 0) return 0L;
        double f = factor <= 0d || factor >= 1d ? 1d : factor;
        return Math.max(1L, Math.round(remainMs * f));
    }

    /** 单发加速后的预计剩余（毫秒）：按动作取 25%/50% 系数；非道具动作返回原值。 */
    public static long projectRemainMs(long remainMs, Action action) {
        if (action == Action.USE_25) return projectRemainMs(remainMs, FACTOR_25);
        if (action == Action.USE_50) return projectRemainMs(remainMs, FACTOR_50);
        return remainMs;
    }

    /**
     * 仅用单一档位时需要几发才能先到（含末端 2s floor；-1=该档位不可达）。
     * 供日志归因（"叠加 k 发可达"）；决策本身用 {@link #next}。
     */
    static int minShots(long remainMs, long enemyRemainMs, long requiredAheadMs, double factor) {
        long v = remainMs;
        for (int k = 1; k <= REACH_SHOT_CAP; k++) {
            if (v <= MIN_REMAIN_MS) return -1;
            v = projectRemainMs(v, factor);
            if (isSatisfied(v, enemyRemainMs, requiredAheadMs)) return k;
        }
        return -1;
    }

    /**
     * 可达性推演（纯函数）：从当前剩余出发，在「25% 最多 s25 发、50% 最多 s50 发」内，
     * 是否存在任一发数组合/顺序能先到。
     *
     * <p>实现：固定「先用 a 发 25%，再用 50%」的规范顺序（对同一组合，25% 先发会让中间剩余更大，
     * 对 floor 约束最宽松），枚举 a=0..s25 后逐发 50%；每个中间状态检查先到。
     * 复杂度 O(64×64)，远小于链长，纯 CPU。
     */
    static boolean reachableWithin(long remainMs, long enemyRemainMs, long requiredAheadMs,
                                   int s25, int s50) {
        // 当前状态本身已满足（例如「刚用一发就已先到」）→ 可达
        if (isSatisfied(remainMs, enemyRemainMs, requiredAheadMs)) return true;
        int cap25 = Math.max(0, Math.min(s25, REACH_SHOT_CAP));
        int cap50 = Math.max(0, Math.min(s50, REACH_SHOT_CAP));
        for (int a = 0; a <= cap25; a++) {
            long v = remainMs;
            boolean usable = true;
            for (int i = 0; i < a; i++) {
                if (v <= MIN_REMAIN_MS) {
                    usable = false;
                    break;
                }
                v = projectRemainMs(v, FACTOR_25);
                if (isSatisfied(v, enemyRemainMs, requiredAheadMs)) return true;
            }
            if (!usable) continue;
            for (int b = 1; b <= cap50; b++) {
                if (v <= MIN_REMAIN_MS) break;
                v = projectRemainMs(v, FACTOR_50);
                if (isSatisfied(v, enemyRemainMs, requiredAheadMs)) return true;
            }
        }
        return false;
    }
}
