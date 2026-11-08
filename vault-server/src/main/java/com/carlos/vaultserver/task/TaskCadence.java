package com.carlos.vaultserver.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 每任务执行节奏表（1:1 复刻网页版 wjdr-tool）。
 *
 * <p>事实基准：网页版反编译源码
 * {@code com.wjdr.tool.modules.daily.model.AbstractDailyAutomationTaskDefinition.forTask(taskKey)}
 * ——每个日常任务都有独立的 {@code intervalSeconds}，TaskEngine 按 {任务 wakeAt + 通知唤醒}
 * 持续执行，而不是“每天整轮一次”。我方 RoundScheduler 只在 05:00-09:00 窗内跑一轮，
 * 导致挂机/补给/活动奖励/联盟宝箱等大量奖励全天漏领（长期缺陷）。
 *
 * <p>本表把网页版 72 项任务键映射到我方 {@link ServerTasks} 任务 id（同一 id 体系），
 * 并统一套一个安全下限 {@link #MIN_INTERVAL_SECONDS}：网页版部分任务间隔 2-10 秒
 * （正中靶心 2s / 巨熊 5s / 采集 10s），对“每次都要发协议请求”的我方执行器过密，
 * 统一抬到 15s 下限；其余间隔逐项照抄，不擅自改动语义。
 *
 * <p>回滚：环境变量 {@code VAULT_TASK_CADENCE=off} 关闭节奏调度（恢复每天一轮行为）。
 */
public final class TaskCadence {

    /**
     * 执行间隔安全下限（秒）。
     *
     * <p>网页版 TaskEngine 的 status/decide 是内存态评估（不发协议），因此 2-15 秒间隔合理；
     * 我方任务每次都要发协议请求，生产实测 15s 下限会让节奏调度一直跑在最小提交间隔上
     * （约 19 轮/分钟，请求量不可控）。2026-10-23 生产观察后统一抬到 60 秒：
     * 事件响应由 TaskWakeRouter 推送唤醒兜底（宝箱/情报/行军/活动等），不依赖高频轮询。
     */
    public static final long MIN_INTERVAL_SECONDS = 60L;

    /** 未收录任务（不应出现）的兜底间隔：5 分钟。 */
    public static final long FALLBACK_INTERVAL_SECONDS = 300L;

    /** 服务端任务 id → 执行间隔（秒），与网页版 forTask 对照表一一对应。 */
    private static final Map<String, Long> INTERVALS;

    static {
        Map<String, Long> m = new LinkedHashMap<>();
        // ===== 奖励（网页版 CLAIM 组）=====
        m.put("login_rewards", 300L);      // login-rewards
        m.put("mail", 180L);               // mailbox-rewards
        m.put("supply", 60L);              // supply
        m.put("daily_mission", 120L);      // daily-mission
        m.put("chapter_quest", 300L);      // 网页版并入 daily-mission/CD_BASED，取 300 保守值
        m.put("adventure", 3600L);         // adventure-rewards
        m.put("activity_rewards", 180L);   // activity-rewards
        m.put("commander_gift", 180L);     // commander-gift
        m.put("act_endless", 180L);        // endless-journey
        m.put("achievement_claim", 600L);  // 网页版无独立键，成就类低频即可
        // ===== 城镇（网页版 TOWN/BACKGROUND 组）=====
        m.put("research", 60L);            // technology-research
        m.put("building", 30L);            // building-upgrade
        m.put("town_affairs", 30L);        // town
        m.put("medic", 60L);               // 网页版归 town（30s），军医所低频更稳
        m.put("barracks", 60L);            // 网页版归 town（30s），兵营训练/收兵 60s
        m.put("pet", 60L);                 // pet-skills
        m.put("expert", 60L);              // expert-travel / expert-intelligence / expert-management
        m.put("homeland", 3600L);          // homeland
        m.put("fire_crystal", 300L);       // fire-crystal-refine
        m.put("climbtower", 30L);          // climbtower
        m.put("arena", 30L);               // arena
        // ===== 活动（网页版 TIME_WINDOW/CD_BASED 组）=====
        m.put("act_balloon", 15L);         // balloon（网页 2s → 下限 15s）
        m.put("act_pitchpot", 180L);       // pitch-pot
        m.put("act_snowclean", 15L);       // snow-clean（网页 10s → 下限 15s）
        m.put("act_icebound", 60L);        // icebound-treasure
        m.put("act_polar", 15L);           // polar-expedition
        m.put("act_flamefangs", 1800L);    // flame-fangs
        m.put("act_snowtrade", 1800L);     // 雪原贸易站（网页版同属限时活动，取 30 分钟）
        m.put("act_brawl", 15L);           // brawl-war（网页 5s → 下限 15s）
        m.put("act_frostfire", 30L);       // 燃霜矿区（网页 TIME_WINDOW 组）
        m.put("act_fishing", 180L);        // 钓鱼：奖励类按 3 分钟
        m.put("act_joy", 15L);             // joy-action（网页 5s → 下限 15s）
        m.put("act_feast", 30L);           // supremacy-feast
        m.put("act_mercenary", 15L);       // mercenary
        m.put("act_truck", 30L);           // truck-escort
        m.put("act_icehunter", 15L);       // icefield-hunter
        m.put("act_arsenal_league", 1800L); // 雪域兵器联赛（周常/竞猜）
        m.put("act_slg_league", 1800L);     // 冰火战歌（任务奖励/点赞）
        m.put("act_cross_capital_revive", 600L); // 最强王国（部队复活，活动期频繁检查）
        m.put("ally_offline_guard", 300L);       // 联盟/同账号离线联动（观察者查询）
        // ===== 野外（网页版 WORLD_MARCH 组）=====
        m.put("gather", 15L);              // gather（网页 10s → 下限 15s）
        m.put("intel", 15L);               // intelligence
        m.put("behemoth", 15L);            // behemoth
        m.put("bear", 15L);                // bear-action（网页 5s → 下限 15s）
        m.put("hunt", 15L);                // hunt
        m.put("adventure_sweep", 600L);    // 自研任务（网页版无独立键），10 分钟
        m.put("battle_shield", 60L);       // battle-protection
        // ===== 联盟（网页版 CLAIM 组）=====
        m.put("alliance_chest", 120L);     // alliance-chests
        m.put("alliance_donate", 30L);     // alliance-donation
        m.put("alliance_mobilization", 15L); // alliance-mobilization（网页 5s → 下限 15s）
        m.put("alliance_showdown", 30L);   // alliance-showdown
        m.put("alliance_help", 60L);       // 常驻互助已接管；本任务低频兜底
        m.put("homeland_visit", 300L);     // homeland-visit
        // ===== 商店/其他 =====
        m.put("shop", 60L);                // shops
        m.put("alliance_shop", 60L);       // 联盟商店（网页 shops 同组）
        m.put("red_packet", 300L);         // red-packets
        m.put("bank", 30L);                // town-bank
        m.put("backpack", 300L);           // 背包道具（网页 shops/资源组）
        m.put("social_gift", 3600L);       // social-gift
        INTERVALS = Collections.unmodifiableMap(m);
    }

    private TaskCadence() {
    }

    /** 该任务的执行间隔（秒）；未收录任务返回保守兜底值。 */
    public static long intervalSeconds(String fnId) {
        Long v = INTERVALS.get(fnId);
        if (v == null) return FALLBACK_INTERVAL_SECONDS;
        return Math.max(MIN_INTERVAL_SECONDS, v);
    }

    public static boolean contains(String fnId) {
        return fnId != null && INTERVALS.containsKey(fnId);
    }

    /** 全部已收录任务 id（不可变）。 */
    public static Set<String> taskIds() {
        return INTERVALS.keySet();
    }

    /** 表项数量（自检/测试用）。 */
    public static int size() {
        return INTERVALS.size();
    }
}
