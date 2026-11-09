package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.watchpoint.PlayerDirectory;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 关注名单服务（设计 §9）：fid 主键、fid→uid 解析、UNRESOLVED 重试、幂等 upsert。
 *
 * <p>存储由 {@link VaultStore} 的 {@code alert.watchlist} 节负责；本类只做业务操作。
 * 用户输入/展示只用 fid（玩家肉眼可见），内部匹配用解析出的 uid。
 * 派生 uid 集合带缓存，名单变更即失效。
 */
public final class WatchlistService {

    private final VaultStore store;
    private volatile Set<Long> cachedUids;

    public WatchlistService(VaultStore store) {
        this.store = store;
    }

    public List<VaultStore.AlertWatchEntry> list() {
        return store.alertWatchlist();
    }

    /** 迁移时反查不到 fid 的旧条目（页面提示用户补填）。 */
    public List<VaultStore.AlertWatchEntry> unresolved() {
        return store.alertUnresolved();
    }

    public VaultStore.AlertWatchEntry find(String fid) {
        String f = norm(fid);
        if (f == null) return null;
        for (VaultStore.AlertWatchEntry e : store.alertWatchlist()) {
            if (f.equals(e.fid)) return e;
        }
        return null;
    }

    /** 新增关注玩家（只收 fid）。fid 非法返回 false；解析失败也保留条目（UNRESOLVED）。 */
    public boolean add(String fid, String note) {
        String f = norm(fid);
        if (f == null) return false;
        VaultStore.AlertWatchEntry e = new VaultStore.AlertWatchEntry(f);
        e.note = note == null ? "" : note.trim();
        e.source = "USER";
        e.enabled = true;
        resolve(e);
        boolean ok = upsert(e);
        if (ok && e.uid > 0L) store.removeAlertUnresolvedByUid(e.uid);   // 补填成功：清掉旧占位
        return ok;
    }

    public boolean update(String fid, String note, Boolean enabled) {
        VaultStore.AlertWatchEntry cur = find(fid);
        if (cur == null) return false;
        if (note != null) cur.note = note.trim();
        if (enabled != null) cur.enabled = enabled;
        return upsert(cur);
    }

    public boolean remove(String fid) {
        String f = norm(fid);
        boolean ok = f != null && store.removeAlertWatch(f);
        if (ok) cachedUids = null;
        return ok;
    }

    /** 新增/更新（迁移器与 HTTP 共用）；成功后失派生缓存。 */
    public boolean upsert(VaultStore.AlertWatchEntry e) {
        boolean ok = store.upsertAlertWatch(e);
        if (ok) cachedUids = null;
        return ok;
    }

    /** 刷新所有未解析条目；返回本次成功解析数。 */
    public int resolvePending() {
        int n = 0;
        for (VaultStore.AlertWatchEntry e : store.alertWatchlist()) {
            if ("OK".equals(e.health)) continue;
            if (resolve(e)) {
                upsert(e);
                n++;
            }
        }
        return n;
    }

    public OptionalLong uidOf(String fid) {
        VaultStore.AlertWatchEntry e = find(fid);
        return e == null || e.uid <= 0L ? OptionalLong.empty() : OptionalLong.of(e.uid);
    }

    /** 参与告警匹配的 uid 集合（enabled 且已解析）；带缓存。 */
    public Set<Long> allWatchedUids() {
        Set<Long> c = cachedUids;
        if (c != null) return c;
        synchronized (this) {
            if (cachedUids == null) {
                Set<Long> out = new HashSet<>();
                for (VaultStore.AlertWatchEntry e : store.alertWatchlist()) {
                    if (e.enabled && e.uid > 0L && "OK".equals(e.health)) out.add(e.uid);
                }
                // 迁移期平稳过渡：反查不到 fid 的旧 uid 仍参与匹配（不因缺 fid 漏监控）。
                for (VaultStore.AlertWatchEntry e : store.alertUnresolved()) {
                    if (e.uid > 0L) out.add(e.uid);
                }
                cachedUids = out;
            }
            return cachedUids;
        }
    }

    /** 把 fid 解析成 uid/nick/health；返回是否成功。迁移器也复用。 */
    boolean resolve(VaultStore.AlertWatchEntry e) {
        PlayerDirectory dir = PlayerDirectory.shared();
        if (dir == null) {
            e.health = "UNRESOLVED";
            return false;
        }
        PlayerDirectory.Entry p = dir.byFid(e.fid);
        if (p == null) {
            e.health = "UNRESOLVED";
            return false;
        }
        e.uid = p.uid;
        if (p.nick != null && !p.nick.isEmpty()) e.nick = p.nick;
        e.health = "OK";
        e.resolvedAt = System.currentTimeMillis();
        return true;
    }

    static String norm(String fid) {
        String f = fid == null ? "" : fid.trim();
        return !f.isEmpty() && f.matches("\\d+") ? f : null;
    }
}
