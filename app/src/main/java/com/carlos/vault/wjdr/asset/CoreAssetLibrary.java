package com.carlos.vault.wjdr.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每角色核心资产库：登录推送建库 → 增量更新 → 不可变快照供各模块复用。
 *
 * <p><b>单写者</b>：{@link #accept(String, Map)} / {@link #applyResponse(String, Map)} 只能在
 * engine 的 inbound 钩子线程（或任务线程回灌请求响应时串行）调用；解析在锁内完成，
 * 监听回调在锁外分发。读者通过 {@link #snapshot()} 无锁读取。
 *
 * <p><b>READY</b>：核心 4 协议（game_init/world_player_init/resource_init/recover_resource_init）
 * 到达即视为可用；推荐协议集（网页 BASE_RESOURCE_NOTIFICATIONS 11 项 + intelligence_info）
 * 到期未齐只记 {@link #missingProtocols()} 诊断，不阻塞（避免个别角色缺协议永久不可用）。
 *
 * <p><b>App/Server 同源</b>：纯 java.util，无 Android/Spring 依赖；Java 8 兼容。
 */
public final class CoreAssetLibrary {

    /** 核心必备协议：缺任一则 READY=false（通常登录 2s 内齐）。 */
    public static final String[] REQUIRED_CORE = {
            "notify_game_init",
            "notify_world_player_init",
            "notify_resource_init",
            "notify_recover_resource_init"
    };

    /** 推荐协议（网页 BASE_RESOURCE_NOTIFICATIONS 11 项 + 情报全量；仅用于诊断 missing）。 */
    public static final String[] REQUIRED_RECOMMENDED = {
            "notify_game_init",
            "notify_world_player_init",
            "notify_soldier_init",
            "notify_hero_init",
            "notify_shelter_info",
            "notify_all_research",
            "notify_vip_info",
            "notify_lords_equipment_info",
            "notify_buff_show",
            "notify_resource_init",
            "notify_backpack_init",
            "notify_recover_resource_init",
            "notify_intelligence_info"
    };

    /** 推荐协议未齐时的宽限（此后核心齐即 READY，missing 仅诊断）。 */
    public static final long READY_GRACE_MS = 5000L;

    /** 变更监听（锁外回调；异常逐个隔离，不影响其它监听者与引擎）。 */
    public interface Listener {
        void onChanged(AssetSnapshot snapshot, Set<AssetDomain> dirtyDomains);
    }

    private final AssetKey key;
    private final AssetState state;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Set<String> seen = new LinkedHashSet<String>();
    private final long createdAtMs;

    private volatile AssetSnapshot snapshot;
    private long version;
    private boolean ready;
    private volatile boolean closed;
    private long firstFrameAtMs;

    public CoreAssetLibrary(AssetKey key) {
        this.key = key == null ? new AssetKey("", 0, 0, 0) : key;
        this.state = new AssetState(this.key);
        this.snapshot = AssetSnapshot.empty(this.key);
        this.createdAtMs = System.currentTimeMillis();
    }

    public AssetKey key() {
        return key;
    }

    public AssetSnapshot snapshot() {
        return snapshot;
    }

    public long version() {
        return snapshot.version;
    }

    public boolean ready() {
        return snapshot.ready;
    }

    public long readyAtMs() {
        return snapshot.readyAtMs;
    }

    /** 推荐协议中尚未到达的（READY 后仍可能补到；用于诊断）。 */
    public List<String> missingProtocols() {
        List<String> out = new ArrayList<String>();
        synchronized (seenLock()) {
            for (String p : REQUIRED_RECOMMENDED) {
                if (!seen.contains(p)) out.add(p);
            }
        }
        return out;
    }

    /** 推荐协议是否已齐（诊断用）。 */
    public boolean recommendedComplete() {
        synchronized (seenLock()) {
            for (String p : REQUIRED_RECOMMENDED) {
                if (!seen.contains(p)) return false;
            }
            return true;
        }
    }

    private Object seenLock() {
        return seen;
    }

    /** READY 前缺失的推荐协议（调用方已持 seen 锁或处于写锁内时安全）。 */
    private Set<String> missingProtocolsSet() {
        Set<String> out = new LinkedHashSet<String>();
        for (String p : REQUIRED_RECOMMENDED) {
            if (!seen.contains(p)) out.add(p);
        }
        return out;
    }

    // ================= 写入（单写者） =================

    /** 入站推送帧。 */
    public void accept(String proto, Map<String, Object> content) {
        acceptFrame(proto, content);
    }

    /**
     * 主动请求响应回灌（任务线程调用；串行于 engine.requestLock 时安全）。
     * 例：req_alliance_trap_info / req_alliance_activity_auto_join_info / req_world_march。
     */
    public void applyResponse(String proto, Map<String, Object> content) {
        acceptFrame(proto, content);
    }

    private void acceptFrame(String proto, Map<String, Object> content) {
        if (closed || proto == null || proto.isEmpty()) return;
        AssetSnapshot snap;
        List<Listener> todo;
        Set<AssetDomain> dirty = new LinkedHashSet<AssetDomain>();
        boolean becameReady = false;
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (firstFrameAtMs <= 0) firstFrameAtMs = now;
            seen.add(proto);
            state.protocolSeenAtMs.put(proto, now);
            boolean handled;
            try {
                handled = AssetParsers.apply(proto, content, state, dirty);
            } catch (Throwable t) {
                state.noteError(proto, t);
                handled = false;
            }
            if (!handled) return;
            if (!ready) {
                ready = coreComplete() && (recommendedComplete()
                        || now - firstFrameAtMs >= READY_GRACE_MS);
                if (ready) {
                    becameReady = true;
                    state.readyAtMs = now;
                    state.metaAtMs = state.metaAtMs > 0 ? state.metaAtMs : now;
                }
            }
            if (!becameReady && dirty.isEmpty()) return;
            version++;
            snap = state.build(version, ready, missingProtocolsSet());
            snapshot = snap;
            todo = new ArrayList<Listener>(listeners);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (becameReady) {
            // READY 时广播全量域，唤醒所有等待方
            dirty.addAll(java.util.Arrays.asList(AssetDomain.values()));
        }
        for (Listener l : todo) {
            try {
                l.onChanged(snap, dirty);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean coreComplete() {
        for (String p : REQUIRED_CORE) {
            if (!seen.contains(p)) return false;
        }
        return true;
    }

    /** 会话断开/被顶号/换角色：数据保留但 READY=false，等待重连后 init 重放。 */
    public void invalidate(String reason) {
        lock.lock();
        try {
            ready = false;
            version++;
            seen.clear();
            firstFrameAtMs = 0;
            for (AssetDomain d : AssetDomain.values()) state.markStale(d);
            snapshot = state.build(version, false, missingProtocolsSet());
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 会话恢复后重新挂载（不清数据；READY 由新 init 帧重新判定）。 */
    public void revive() {
        lock.lock();
        try {
            ready = false;
            seen.clear();
            firstFrameAtMs = 0;
            snapshot = state.build(version, false, missingProtocolsSet());
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        closed = true;
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ================= 读取/等待/订阅 =================

    /** 等待 READY（返回是否 READY；超时返回当前状态）。 */
    public boolean awaitReady(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        lock.lock();
        try {
            while (!ready && !closed) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    changed.await(remain, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return ready;
        } finally {
            lock.unlock();
        }
    }

    /** 等待版本变化（返回新版本；超时返回 sinceVersion）。 */
    public long awaitChange(long sinceVersion, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        lock.lock();
        try {
            while (snapshot.version <= sinceVersion && !closed) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    changed.await(remain, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return snapshot.version;
        } finally {
            lock.unlock();
        }
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    // ================= 便捷读取（等价 snapshot().xxx） =================

    public AssetSnapshot.Queue queue() {
        return snapshot.queue;
    }

    public AssetSnapshot.Stamina stamina() {
        return snapshot.stamina;
    }

    public AssetSnapshot.Resources resources() {
        return snapshot.resources;
    }

    public AssetSnapshot.Clock clock() {
        return snapshot.clock;
    }

    public AssetSnapshot.Intel intel() {
        return snapshot.intel;
    }

    public AssetSnapshot.Bear bear() {
        return snapshot.bear;
    }

    public AssetSnapshot.Behemoth behemoth() {
        return snapshot.behemoth;
    }

    public AssetSnapshot.AutoJoin autoJoin() {
        return snapshot.autoJoin;
    }

    public AssetSnapshot.War war() {
        return snapshot.war;
    }

    public AssetSnapshot.Guardian guardian() {
        return snapshot.guardian;
    }

    public AssetSnapshot.Meta meta() {
        return snapshot.meta;
    }

    public AssetSnapshot.Calendar calendar() {
        return snapshot.calendar;
    }

    public AssetSnapshot.Backpack backpack() {
        return snapshot.backpack;
    }

    public AssetSnapshot.Recover recover() {
        return snapshot.recover;
    }

    /** 探针：用于测试/诊断（只读）。 */
    Map<String, Long> protocolSeenAt() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Long>(state.protocolSeenAtMs));
    }

    long createdAtMs() {
        return createdAtMs;
    }
}
