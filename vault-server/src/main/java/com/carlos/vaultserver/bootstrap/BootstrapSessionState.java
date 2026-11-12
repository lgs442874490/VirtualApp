package com.carlos.vaultserver.bootstrap;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 起号会话状态——按作用域（账号 fpid）分域的进程级存储（审查 P1-2 / 需求 R-02）。
 *
 * <p>背景：单号挂点 {@code single-*} 每个请求都 {@code new BootstrapService(...)}，
 * 原实现把 world/finishPass/res_update 去重/失败钻石/ENDED 等会话级标记放在 service
 * 实例字段里，跨请求即丢——表现为单步模式重复发 world 请求、finishPass 每请求重跑、
 * 增量重复应用、ENDED 不持续。本类把这些状态上移为进程级（与
 * {@link BootstrapHoldStore} 同形）：{@link #shared()} 供批次与单号挂点共用，键为
 * {@code fpid}；单测用 {@code new BootstrapSessionState()} 获得隔离实例。
 *
 * <p>字段读写全部 {@code synchronized} 在同一个 Holder 上，读-改-写（如 attempts++）
 * 不会因并发请求丢更新。
 */
final class BootstrapSessionState {

    /** 生产共享实例（进程级；批次线程与 HTTP 单号挂点共用）。 */
    private static final BootstrapSessionState SHARED = new BootstrapSessionState();

    /** 作用域（fpid）→ 会话状态。 */
    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    /** 生产共享表。 */
    static BootstrapSessionState shared() {
        return SHARED;
    }

    /** 取（必要时创建）某账号的会话状态；同一 scope 始终返回同一实例。 */
    Holder holder(String scope) {
        return holders.computeIfAbsent(scopeKey(scope), ignored -> new Holder());
    }

    /** 清理某账号全部会话状态（起号重跑、账号删除、批次取消）。 */
    void clearScope(String scope) {
        holders.remove(scopeKey(scope));
    }

    /** 清空全部（单测隔离用；生产不调用）。 */
    void clearAll() {
        holders.clear();
    }

    private static String scopeKey(String scope) {
        return scope == null ? "" : scope;
    }

    /**
     * 单账号会话状态（需求 R-02 表格的 9 项 + R-05 资源增量消费流水）。
     *
     * <p>方法均为 {@code synchronized}：同一 fpid 的并发 HTTP 请求共享同一 Holder，
     * 「读-改-写」必须在同一临界区内完成。
     */
    static final class Holder {

        /** world kingdom info 是否已发（R-08：失败不置位）。 */
        private boolean worldKingdomInfoSent;
        /** fog 是否已发（R-08：失败不置位）。 */
        private boolean worldFogSent;
        /** fog 的 kid（0 = 尚未取到）。 */
        private long worldKingdomId;
        /** world 连续失败次数（R-08；成功即清零）。 */
        private int worldAttempts;
        /** 收尾当日标记（yyyy-MM-dd；R-09：仅真完成时置位）。 */
        private String finishPassDay = "";
        /** 增量应用序号（R-05 累积通道的消费流水，诊断/可观测用）。 */
        private long lastResUpdateSeq;
        /** 永久失败的钻石道具（R-11：仅 code=51 加入）。 */
        private final Set<Long> failedDiamondItems = ConcurrentHashMap.newKeySet();
        /** 是否已 ENDED（R-06 终态）。 */
        private boolean resourceEnded;
        /** 耗尽计数（R-07：仅钻石直升成功时清零）。 */
        private int resourceExhaustedAttempts;

        synchronized boolean worldKingdomInfoSent() {
            return worldKingdomInfoSent;
        }

        synchronized void markWorldKingdomInfoSent() {
            worldKingdomInfoSent = true;
        }

        synchronized boolean worldFogSent() {
            return worldFogSent;
        }

        synchronized void markWorldFogSent() {
            worldFogSent = true;
        }

        synchronized long worldKingdomId() {
            return worldKingdomId;
        }

        synchronized void setWorldKingdomId(long kid) {
            if (kid > 0L) {
                worldKingdomId = kid;
            }
        }

        synchronized int worldAttempts() {
            return worldAttempts;
        }

        synchronized int incrementWorldAttempts() {
            return ++worldAttempts;
        }

        synchronized void resetWorldAttempts() {
            worldAttempts = 0;
        }

        synchronized String finishPassDay() {
            return finishPassDay;
        }

        synchronized void setFinishPassDay(String day) {
            finishPassDay = day == null ? "" : day;
        }

        /**
         * world kingdom info 的「检查 + 置位」原子化（二次审查 P2-4）：
         * 返回 false 表示已被其它并发请求认领，避免重复发送。
         */
        synchronized boolean claimWorldKingdomInfo() {
            if (worldKingdomInfoSent) {
                return false;
            }
            worldKingdomInfoSent = true;
            return true;
        }

        /** world kingdom info 认领回滚（请求失败/kid 缺失时，下一轮可重试；P2-4）。 */
        synchronized void releaseWorldKingdomInfo() {
            worldKingdomInfoSent = false;
        }

        /** fog 的「检查 + 置位」原子化（P2-4）：kid 未取到或已发返回 false。 */
        synchronized boolean claimWorldFog() {
            if (worldFogSent || worldKingdomId <= 0L) {
                return false;
            }
            worldFogSent = true;
            return true;
        }

        /** fog 认领回滚（请求失败时；P2-4）。 */
        synchronized void releaseWorldFog() {
            worldFogSent = false;
        }

        /**
         * 收尾占位（P2-4 / R-09）：同一日期只允许一个请求执行；
         * 三项全空时由 {@link #releaseFinishPass(String)} 回滚。
         */
        synchronized boolean claimFinishPass(String day) {
            String target = day == null ? "" : day;
            if (!finishPassDay.isEmpty() && finishPassDay.equals(target)) {
                return false;
            }
            finishPassDay = target;
            return true;
        }

        /** 收尾回滚（三项全空时不占当日；R-09）。 */
        synchronized void releaseFinishPass(String day) {
            String target = day == null ? "" : day;
            if (finishPassDay.equals(target)) {
                finishPassDay = "";
            }
        }

        /** 消费一次资源增量：返回自增后的流水号（R-05 可观测）。 */
        synchronized long nextResUpdateSeq() {
            return ++lastResUpdateSeq;
        }

        /** 永久失败的钻石道具（并发 set，调用方直接 contains/add）。 */
        Set<Long> failedDiamondItems() {
            return failedDiamondItems;
        }

        synchronized boolean resourceEnded() {
            return resourceEnded;
        }

        synchronized void markResourceEnded() {
            resourceEnded = true;
        }

        synchronized int resourceExhaustedAttempts() {
            return resourceExhaustedAttempts;
        }

        synchronized int incrementResourceExhaustedAttempts() {
            return ++resourceExhaustedAttempts;
        }

        synchronized void resetResourceExhaustedAttempts() {
            resourceExhaustedAttempts = 0;
        }
    }
}
