package com.carlos.vault.daily;

import android.content.Context;

import com.carlos.vault.wjdr.WjdrEngine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领取类任务推送实时唤醒（P3；对标原版 TaskEngine 的 interestedProtocols 信号驱动）。
 *
 * <p>原版：每任务声明兴趣协议 → 推送到达 → 引擎唤醒该任务 decide → 可领即 Execute。
 * 号库 P3-lite：常驻会话收到下列推送时，直接在会话上执行对应<b>领取类</b>任务（角色配置须启用），
 * 每任务 10 分钟冷却（原版任务间隔 30s~3600s 的保守聚合，防推送风暴重复请求）。
 *
 * <p><b>刻意不挂推送唤醒的任务</b>：行军/养成类（采集/狩猎/巨兽/情报/竞技场/练兵/科研/建筑）——
 * 原版有独占窗与资源锁门控，号库无锁体系，维持每日窗口串行（移植方案风险 7）。
 * 互助不在此表（HelpResponder 独立链路，秒级响应）。
 */
final class PushWakeDriver {

    /** 推送 → 领取类任务（仅收录原版文档/号库实测双证实的协议名）。 */
    private static final Map<String, String> WAKE_MAP = new LinkedHashMap<>();

    static {
        WAKE_MAP.put("notify_dailyquests", "daily_mission");            // 原版#17 日常任务推送
        WAKE_MAP.put("notify_growthquests", "chapter_quest");           // 成长任务列表（登录期到达）
        WAKE_MAP.put("notify_chapterquests", "chapter_quest");          // 章节任务列表（登录期到达）
        WAKE_MAP.put("notify_fourteen_sign_info", "login_rewards");     // 原版#32 签到推送
        WAKE_MAP.put("notify_recover_resource_init", "supply");         // 原版#9 奖励推送
        WAKE_MAP.put("notify_recover_resource_update", "supply");
        WAKE_MAP.put("notify_adventure_pve_info", "adventure");         // 原版#2 探险挂机推送
        WAKE_MAP.put("notify_homeland_info", "homeland");               // 原版#25 家园推送
        WAKE_MAP.put("notify_homeland_life_tree_output_update", "homeland");     // 生命之树产出更新
        WAKE_MAP.put("notify_homeland_logging_site_update_info", "homeland");    // 木材加工厂更新
        WAKE_MAP.put("notify_homeland_free_production_info", "homeland");        // 晨曦回礼刷新
        WAKE_MAP.put("notify_spar_info", "fire_crystal");               // 原版#22 火晶推送
        WAKE_MAP.put("notify_general_bp_extra_info", "act_endless");    // 原版#18 BP 推送
        WAKE_MAP.put("notify_general_bp_normal_info", "act_endless");
        WAKE_MAP.put("notify_mobilization_alliance_init", "alliance_mobilization");   // 原版#5 总动员推送
        WAKE_MAP.put("notify_alliance_showdown_activity", "alliance_showdown");       // 原版#6 活动窗口
        WAKE_MAP.put("notify_social_gift_info", "social_gift");         // 原版#43（默认关，唤醒无害）
        WAKE_MAP.put("notify_frostfire_war_info", "act_frostfire");     // 原版D组 燃霜（默认关）
    }

    /**
     * 行军类推送 → 任务（M2）。推送名未全部实证的（收不到即不触发，靠下方轮询兜底）：
     * 15 分钟冷却 + MarchCoordinator 资源锁，与原版行军任务间隔（10s/15s）相比是超保守版。
     */
    private static final Map<String, String> MARCH_WAKE_MAP = new LinkedHashMap<>();

    static {
        MARCH_WAKE_MAP.put("notify_world_march_update", "gather");          // 原版 gather 19 推送之一（行军返回→补采集）
        MARCH_WAKE_MAP.put("notify_mapobj_update", "hunt");                 // 原版 hunt 地图推送
        MARCH_WAKE_MAP.put("notify_intelligence_info", "intel");            // 已实证（capturePushStates 在用）
        MARCH_WAKE_MAP.put("notify_alliance_activity_update", "behemoth");  // 原版 behemoth 世界 NPC 推送
    }

    /** 行军类任务集（独立冷却判定用）。 */
    private static final java.util.Set<String> MARCH_FNS = java.util.Set.of(
            "gather", "hunt", "intel", "behemoth", "act_icehunter", "act_mercenary",
            "act_truck", "act_feast", "arena");

    /** 每任务唤醒冷却：领取类 10 分钟；行军类 15 分钟（保守版，体力/队列由轮询兜底）。 */
    private static final long WAKE_COOLDOWN_MILLIS = 10 * 60_000L;
    private static final long MARCH_COOLDOWN_MILLIS = 15 * 60_000L;

    /** 行军类轮询兜底间隔（不依赖推送名；体力满/队列空的保守替代）。 */
    static final long MARCH_POLL_MILLIS = 15 * 60_000L;

    /** 冷却表：fpid:sid:fnId → 上次唤醒执行时刻（进程内；重启即清，无害）。 */
    private static final Map<String, Long> LAST_WAKE = new ConcurrentHashMap<>();

    private PushWakeDriver() {
    }

    /** 在会话上挂推送唤醒钩子（每会话一次；重连后随引擎 messageHooks 存续）。 */
    static void attach(Context ctx, RoleSessionKeeper.LiveSession ls) {
        if (!ls.wakeAttached.compareAndSet(false, true)) return;
        ls.engine.addMessageHook((proto, content) -> {
            String fnId = WAKE_MAP.get(proto);
            if (fnId == null) fnId = MARCH_WAKE_MAP.get(proto);
            if (fnId == null) return;
            final String wakeFn = fnId;
            try {
                wake(ctx, ls, wakeFn, proto);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 行军类轮询兜底（keeper 每 15 分钟调；仅对角色配置启用的任务生效——gather/hunt/intel）。 */
    static void pollMarch(Context ctx, RoleSessionKeeper.LiveSession ls) {
        for (String fnId : new String[]{"gather", "hunt", "intel"}) {
            try {
                wake(ctx, ls, fnId, "march-poll");
            } catch (Throwable ignored) {
            }
        }
    }

    private static void wake(Context ctx, RoleSessionKeeper.LiveSession ls, String fnId, String proto) {
        if (!WjdrEngine.STATE_ONLINE.equals(ls.engine.sessionState())) return;
        // 角色配置未启用的任务不唤醒（口径与整轮一致：关闭=不自动也不推送唤醒）
        Map<String, Boolean> cfg;
        try {
            cfg = new DailyConfigStore(ctx).roleConfig(ls.fpid, ls.sid);
        } catch (Throwable t) {
            cfg = null;
        }
        if (cfg != null && Boolean.FALSE.equals(cfg.get(fnId))) return;

        long cooldown = MARCH_FNS.contains(fnId) ? MARCH_COOLDOWN_MILLIS : WAKE_COOLDOWN_MILLIS;
        String k = ls.fpid + ":" + ls.sid + ":" + fnId;
        long now = System.currentTimeMillis();
        Long last = LAST_WAKE.get(k);
        if (last != null && now - last < cooldown) return;
        LAST_WAKE.put(k, now);

        final String token = ls.engine.savedToken();
        if (token == null || token.isEmpty()) return;
        RoleSessionKeeper.get(ctx).execute(() -> {
            // 行军域锁：与整轮/互助/手动执行互斥；30s 拿不到=让出（下轮推送/轮询再试）
            AutoCloseable guard = null;
            if (MarchCoordinator.isGuarded(fnId)) {
                guard = MarchCoordinator.forKey(ls.fpid, ls.sid).tryAcquire(fnId, 30_000L);
                if (guard == null) {
                    android.util.Log.i("PushWake", ls.label + " " + proto + " → " + fnId
                            + "：行军资源占用中，让出");
                    return;
                }
            }
            try {
                DailyResult r = DailyRunner.runSingle(ctx, ls.fpid, token, ls.sid, fnId);
                boolean ok = r.category == DailyResult.Category.CLAIMED
                        || r.category == DailyResult.Category.SKIPPED;
                LogStore log = new LogStore(ctx);
                log.append(ls.fpid, ls.sid, fnId, ok, r.toString(),
                        r.rewards == null ? "" : r.rewards,
                        "通知", r.durationMs, r.category.name(), 1);
                log.setStatus(ls.fpid, ls.sid, fnId,
                        r.category == DailyResult.Category.CLAIMED ? LogStore.ST_DONE
                                : r.category == DailyResult.Category.SKIPPED
                                ? LogStore.ST_SKIPPED : LogStore.ST_FAILED);
                log.applySubResults(ls.fpid, ls.sid, fnId, r.subResults);
                if (r.category == DailyResult.Category.CLAIMED) {
                    android.util.Log.i("PushWake", ls.label + " " + proto + " → " + fnId
                            + "：已领取（" + r.message + "）");
                }
            } catch (Throwable t) {
                android.util.Log.w("PushWake", "wake fail " + fnId + ": " + t);
            } finally {
                if (guard != null) {
                    try {
                        guard.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
