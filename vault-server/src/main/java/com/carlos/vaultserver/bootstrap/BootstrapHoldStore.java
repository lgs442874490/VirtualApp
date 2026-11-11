package com.carlos.vaultserver.bootstrap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 起号退避表（hold / retry）——按作用域（账号 fpid）分域的进程级存储。
 *
 * <p>背景（卡点 A 审查 P1-2）：原实现把 {@code retryUntil} 放在 {@link BootstrapService}
 * 实例字段里，而单号挂点 {@code single-*} 每个请求都 {@code new BootstrapService(...)}，
 * 因此上一次请求写入的 51 hold 在下一次请求中不存在，30 分钟长退避在单号路径从未生效。
 *
 * <p>本类把退避状态上移为进程级：{@link #shared()} 供批次与单号挂点共用，键为
 * {@code fpid}，同一账号跨请求/跨实例可见；单测可直接 {@code new BootstrapHoldStore(clock)}
 * 注入可控时钟，做到「退避到期恢复重试」的确定性验证。
 *
 * <p>键空间沿用旧实现（{@code hold:...} / {@code adventure:checkpoint:...} / {@code res:diag}
 * 等），仅增加 fpid 作用域隔离，不同账号互不污染。
 */
final class BootstrapHoldStore {

    /** 生产共享实例（进程级；批次线程与 HTTP 单号挂点共用）。 */
    private static final BootstrapHoldStore SHARED = new BootstrapHoldStore();

    /** 作用域（fpid）→ (退避键 → 到期毫秒)。 */
    private final Map<String, Map<String, Long>> until = new ConcurrentHashMap<>();
    /** 单调时钟（毫秒）；单测注入可控实现，生产为 {@link System#currentTimeMillis()}。 */
    private final LongSupplier clock;

    BootstrapHoldStore() {
        this(System::currentTimeMillis);
    }

    /** 单测装配：注入可控时钟（验证到期恢复，不真实睡眠）。 */
    BootstrapHoldStore(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** 生产共享表。 */
    static BootstrapHoldStore shared() {
        return SHARED;
    }

    /** 该键当前是否允许执行（无退避记录或已到期）。 */
    boolean allowed(String scope, String key) {
        Long deadline = deadline(scope, key);
        return deadline == null || clock.getAsLong() >= deadline;
    }

    /** 写入退避：{@code delayMillis} 毫秒内不再尝试。 */
    void mark(String scope, String key, long delayMillis) {
        scopeOf(scope).put(key, clock.getAsLong() + Math.max(0L, delayMillis));
    }

    /** 显式解除退避（成功执行/运维入口）。 */
    void clear(String scope, String key) {
        Map<String, Long> scopeMap = until.get(scopeKey(scope));
        if (scopeMap != null) {
            scopeMap.remove(key);
        }
    }

    /** 剩余退避毫秒（无记录或已到期返回 0）。 */
    long remainingMillis(String scope, String key) {
        Long deadline = deadline(scope, key);
        return deadline == null ? 0L : Math.max(0L, deadline - clock.getAsLong());
    }

    /** 当前退避到期时刻（无记录返回 0；测试/排障用）。 */
    long deadlineMillis(String scope, String key) {
        Long deadline = deadline(scope, key);
        return deadline == null ? 0L : deadline;
    }

    /** 清空某账号全部退避（测试隔离/账号重新起号）。 */
    void clearScope(String scope) {
        until.remove(scopeKey(scope));
    }

    /** 清空全部退避（单测隔离用；生产不调用——各账号按 fpid 分域互不影响）。 */
    void clearAll() {
        until.clear();
    }

    private Long deadline(String scope, String key) {
        Map<String, Long> scopeMap = until.get(scopeKey(scope));
        return scopeMap == null ? null : scopeMap.get(key);
    }

    private Map<String, Long> scopeOf(String scope) {
        return until.computeIfAbsent(scopeKey(scope), ignored -> new ConcurrentHashMap<>());
    }

    private static String scopeKey(String scope) {
        return scope == null ? "" : scope;
    }
}
