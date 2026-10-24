package com.carlos.vaultserver.formation;

import com.carlos.vault.wjdr.WjdrEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 英雄可用性短时黑名单（301 英雄不在城 → 拉黑换组合重试）。
 *
 * <p>背景：游戏对 {@code req_world_march} 返回 301 表示编队中的英雄不在城（随其他部队外出/
 * 驻防/训练等），而登录 {@code notify_hero_init} 不含实时在城状态。自动编队重复选同一批英雄
 * 会持续 301（生产实证：采集 4 队列 × 5 英雄全 301）。本类按引擎实例记录被 301 拒绝的英雄，
 * TTL 10 分钟内 {@link FormationResolver} 自动编队会跳过它们，下一轮换下一批候选；全部被拉黑时
 * 返回明确 SKIP 原因，等英雄回城（TTL 到期自动恢复）。
 *
 * <p>线程安全：WeakHashMap + 同步块（会话销毁后引擎可回收；与 FormationCatalog 缓存同策略）。
 */
public final class HeroAvailability {

    /** 默认拉黑时长（毫秒）：覆盖一次采集往返（分钟级）并允许回城后自动恢复。 */
    public static final long DEFAULT_BLOCK_MILLIS = 10L * 60L * 1000L;

    private static final Map<WjdrEngine, Map<Long, Long>> BLOCKED =
            Collections.synchronizedMap(new WeakHashMap<WjdrEngine, Map<Long, Long>>());

    private HeroAvailability() {
    }

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

    /** 会话关闭/测试清理。 */
    public static void clear(WjdrEngine engine) {
        if (engine == null) return;
        BLOCKED.remove(engine);
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
            Object k = pair.containsKey("k") ? pair.get("k") : pair.get("id");
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
