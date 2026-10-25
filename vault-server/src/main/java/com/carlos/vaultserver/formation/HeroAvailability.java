package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 英雄可用性短时黑名单 + 出征自适应状态（301/403/406/415）。
 *
 * <p><b>301/403 黑名单</b>：游戏对 {@code req_world_march} 返回 301 表示编队中的英雄不在城
 * （随其他部队外出/驻防/训练等），403 表示英雄被占用；登录 {@code notify_hero_init} 不含实时
 * 在城状态。按引擎实例记录被拒英雄，TTL 10 分钟内 {@link FormationResolver} 的 AUTO 候选与
 * ROLE 校验都会排除它们，下一轮换下一批候选；全部被拉黑时返回明确 SKIP 原因，等英雄回城
 * （TTL 到期自动恢复）。
 *
 * <p><b>415 自适应</b>：{@code noteHeroCountRejected} 把自动编队英雄上限逐级下调（3→2→1），
 * 由 {@link FormationResolver#autoProfessionHeroes} 消费；成功出征后 {@link #markSuccess} 复位。
 *
 * <p><b>406 自适应</b>：{@code noteSoldierLimitRejected} 把兵力缩放 100%→75%→50%→25%；
 * AUTO/ROLE 解析都会按 {@link #soldierScalePercent} 缩放后下发；成功出征后 {@link #markSuccess} 复位。
 *
 * <p>线程安全：WeakHashMap + 同步块（会话销毁后引擎可回收；与 FormationCatalog 缓存同策略）。
 */
public final class HeroAvailability {

    /** 默认拉黑时长（毫秒）：覆盖一次采集往返（分钟级）并允许回城后自动恢复。 */
    public static final long DEFAULT_BLOCK_MILLIS = 10L * 60L * 1000L;

    private static final Map<WjdrEngine, Map<Long, Long>> BLOCKED =
            Collections.synchronizedMap(new WeakHashMap<WjdrEngine, Map<Long, Long>>());

    /** 415（编队人数/配置超限）后的自动编队英雄数上限（被拒后逐步下调，成功后复位）。 */
    private static final Map<WjdrEngine, Integer> HERO_LIMIT_HINT =
            Collections.synchronizedMap(new WeakHashMap<WjdrEngine, Integer>());

    /** 默认自动编队英雄上限（游戏规则每队列 3 英雄：盾/矛/弓各一）。 */
    public static final int DEFAULT_AUTO_HEROES = 3;

    /** 406（编队士兵数量超上限）后的兵力缩放百分比（默认 100；被拒后 75→50→25 收敛）。 */
    private static final Map<WjdrEngine, Integer> SOLDIER_SCALE_PCT =
            Collections.synchronizedMap(new WeakHashMap<WjdrEngine, Integer>());

    private HeroAvailability() {
    }

    // ------------------------------------------------------------------
    // 301/403 黑名单
    // ------------------------------------------------------------------

    /** 拉黑一批英雄（until = now + millis；id<=0 忽略）。 */
    public static void block(WjdrEngine engine, Iterable<Long> heroIds, long millis) {
        if (engine == null || heroIds == null) return;
        long until = System.currentTimeMillis() + Math.max(1L, millis);
        synchronized (BLOCKED) {
            Map<Long, Long> map = BLOCKED.get(engine);
            if (map == null) {
                map = new LinkedHashMap<Long, Long>();
                BLOCKED.put(engine, map);
            }
            for (Long id : heroIds) {
                if (id != null && id > 0) map.put(id, until);
            }
        }
    }

    /** 是否在拉黑窗内（过期自动清理）。 */
    public static boolean isBlocked(WjdrEngine engine, long heroId) {
        if (engine == null || heroId <= 0) return false;
        long now = System.currentTimeMillis();
        synchronized (BLOCKED) {
            Map<Long, Long> map = BLOCKED.get(engine);
            if (map == null) return false;
            Long until = map.get(heroId);
            if (until == null) return false;
            if (until <= now) {
                map.remove(heroId);
                return false;
            }
            return true;
        }
    }

    /** 在给定英雄里挑出仍被拉黑的（顺序保持；供 ROLE 校验写可读 SKIP 原因）。 */
    public static List<Long> blockedAmong(WjdrEngine engine, Collection<Long> heroIds) {
        List<Long> out = new ArrayList<Long>();
        if (heroIds == null) return out;
        for (Long id : heroIds) {
            if (id != null && isBlocked(engine, id)) out.add(id);
        }
        return out;
    }

    /** 当前仍被拉黑的英雄 id（诊断/日志用）。 */
    public static List<Long> blockedIds(WjdrEngine engine) {
        List<Long> out = new ArrayList<Long>();
        if (engine == null) return out;
        long now = System.currentTimeMillis();
        synchronized (BLOCKED) {
            Map<Long, Long> map = BLOCKED.get(engine);
            if (map == null) return out;
            java.util.Iterator<Map.Entry<Long, Long>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> e = it.next();
                if (e.getValue() == null || e.getValue() <= now) {
                    it.remove();
                } else {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 415：自动编队英雄数自适应
    // ------------------------------------------------------------------

    /** 当前自动编队英雄数上限（415 后自适应下调；默认 {@link #DEFAULT_AUTO_HEROES}=3）。 */
    public static int autoHeroLimit(WjdrEngine engine) {
        if (engine == null) return DEFAULT_AUTO_HEROES;
        Integer hint = HERO_LIMIT_HINT.get(engine);
        if (hint == null || hint < 1) return DEFAULT_AUTO_HEROES;
        return Math.min(DEFAULT_AUTO_HEROES, hint);
    }

    /** 是否处于 415 下调态（诊断/单测用）。 */
    public static boolean hasHeroLimitHint(WjdrEngine engine) {
        return engine != null && HERO_LIMIT_HINT.get(engine) != null;
    }

    /** 415 拒绝：把自动编队英雄数下调到 used-1（最低 1，最高默认-1）；返回新的上限。 */
    public static int noteHeroCountRejected(WjdrEngine engine, int usedHeroCount) {
        if (engine == null) return DEFAULT_AUTO_HEROES;
        int next = Math.max(1, Math.min(DEFAULT_AUTO_HEROES - 1,
                usedHeroCount > 0 ? usedHeroCount - 1 : DEFAULT_AUTO_HEROES - 1));
        HERO_LIMIT_HINT.put(engine, next);
        return next;
    }

    /** 编队成功后清除 415 下调（下次按完整上限尝试）。 */
    public static void clearHeroLimitHint(WjdrEngine engine) {
        if (engine != null) HERO_LIMIT_HINT.remove(engine);
    }

    // ------------------------------------------------------------------
    // 406：兵力缩放自适应
    // ------------------------------------------------------------------

    /** 406 后的兵力缩放百分比（100=不缩放，最低 25）。 */
    public static int soldierScalePercent(WjdrEngine engine) {
        if (engine == null) return 100;
        Integer v = SOLDIER_SCALE_PCT.get(engine);
        return v == null ? 100 : Math.max(25, Math.min(100, v));
    }

    /** 是否处于 406 缩放态（诊断/单测用）。 */
    public static boolean hasSoldierScaleHint(WjdrEngine engine) {
        return engine != null && SOLDIER_SCALE_PCT.get(engine) != null;
    }

    /** 406 拒绝：兵力缩放 -25%（最低 25%），返回新百分比。 */
    public static int noteSoldierLimitRejected(WjdrEngine engine) {
        if (engine == null) return 100;
        int next = Math.max(25, soldierScalePercent(engine) - 25);
        SOLDIER_SCALE_PCT.put(engine, next);
        return next;
    }

    /** 复位 406 缩放（成功出征后）。 */
    public static void clearSoldierScale(WjdrEngine engine) {
        if (engine != null) SOLDIER_SCALE_PCT.remove(engine);
    }

    // ------------------------------------------------------------------
    // 成功回调（P0-5：406/415 自适应必须能恢复）
    // ------------------------------------------------------------------

    /**
     * 出征成功回调（ServerFieldTasks 成功分支 / {@link FormationResolver} 出口统一调用）：
     * 复位 415 英雄上限与 406 兵力缩放，下一轮按完整英雄数/兵量重新尝试。
     * <b>不</b>清 301/403 黑名单（拉黑是英雄级事实，TTL 自然过期）。
     */
    public static void markSuccess(WjdrEngine engine) {
        if (engine == null) return;
        clearHeroLimitHint(engine);
        clearSoldierScale(engine);
    }

    /** 会话关闭/测试清理（黑名单 + 415/406 自适应状态）。 */
    public static void clear(WjdrEngine engine) {
        if (engine == null) return;
        BLOCKED.remove(engine);
        HERO_LIMIT_HINT.remove(engine);
        SOLDIER_SCALE_PCT.remove(engine);
    }

    /** 从 formation payload（{@code {"hero_id":[{"k":id,"v":1}]}}）提取英雄 id。 */
    @SuppressWarnings("unchecked")
    public static List<Long> heroIdsOf(Map<String, Object> formationPayload) {
        List<Long> out = new ArrayList<Long>();
        if (formationPayload == null) return out;
        Object raw = formationPayload.get("hero_id");
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> pair = (Map<String, Object>) o;
            // 新 payload：v=英雄 id；兼容旧脏数据：id / k
            Object k = pair.containsKey("v") ? pair.get("v")
                    : (pair.containsKey("id") ? pair.get("id") : pair.get("k"));
            long id = 0L;
            if (k instanceof Number) id = ((Number) k).longValue();
            else if (k != null) {
                try {
                    id = Long.parseLong(String.valueOf(k).trim());
                } catch (Throwable ignored) {
                }
            }
            if (id > 0L) out.add(id);
        }
        return out;
    }
}
