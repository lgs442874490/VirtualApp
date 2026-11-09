package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.watchpoint.PlayerDirectory;
import com.carlos.vaultserver.watchpoint.WatchPointStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 三套旧名单 → 一份 fid 名单的迁移器（设计 §9.3）。
 *
 * <ul>
 *   <li>{@code VaultStore.Account.watchTargets}（uid）→ 反查 fid，source=MIGRATED_WATCH；</li>
 *   <li>{@code WatchPointStore.monitorTargets}（uid+坐标）→ 反查 fid，source=MIGRATED_MONITOR；</li>
 *   <li>App 本地 seed（uid）由调用方传入 → source=MIGRATED_LOCAL，enabled=true。</li>
 * </ul>
 *
 * <p>反查不到 fid 的条目进 {@code alert.unresolved}（legacyRaw 保留原值），不静默丢弃。
 * 迁移幂等：以 fid 为键 upsert，重复执行条数不变；{@code dryRun=true} 不写任何数据。
 */
public final class WatchlistMigrator {

    /** App 本地 seed 条目（由 App 首次同步时带上）。 */
    public static final class LocalSeed {
        public final long uid;
        public final String note;
        public final String kid;

        public LocalSeed(long uid, String note, String kid) {
            this.uid = uid;
            this.note = note == null ? "" : note;
            this.kid = kid == null ? "" : kid;
        }
    }

    public static final class MigrateReport {
        public boolean dryRun;
        public boolean alreadyDone;
        public int merged;
        public int updated;
        public int unresolved;
        public int skipped;
        public final List<String> details = new ArrayList<>();

        public String summary() {
            return (dryRun ? "[dryRun] " : "") + "merged=" + merged + " updated=" + updated
                    + " unresolved=" + unresolved + " skipped=" + skipped
                    + (alreadyDone ? " (已迁移，跳过)" : "");
        }
    }

    private WatchlistMigrator() {
    }

    /** 启动时调用：已迁移则直接返回；否则执行一次。 */
    public static MigrateReport migrateIfNeeded(VaultStore store, WatchPointStore wps,
                                                WatchlistService svc, boolean dryRun) {
        if (!dryRun && store.alertMigrated()) {
            MigrateReport r = new MigrateReport();
            r.alreadyDone = true;
            return r;
        }
        return run(store, wps, svc, null, dryRun);
    }

    /**
     * 执行迁移。{@code localSeeds} 可为 null。
     */
    public static MigrateReport run(VaultStore store, WatchPointStore wps, WatchlistService svc,
                                    List<LocalSeed> localSeeds, boolean dryRun) {
        MigrateReport r = new MigrateReport();
        r.dryRun = dryRun;
        PlayerDirectory dir = PlayerDirectory.shared();

        // 1) 账号级 watchTargets（uid → fid）
        for (VaultStore.Account a : store.accounts()) {
            if (a.watchTargets == null) continue;
            for (VaultStore.WatchTarget t : a.watchTargets) {
                String uidStr = t.uid == null ? "" : t.uid.trim();
                long uid;
                try {
                    uid = Long.parseLong(uidStr);
                } catch (Exception ex) {
                    r.skipped++;
                    r.details.add("skip watchTargets 非数字 uid=" + uidStr);
                    continue;
                }
                mergeOne(r, store, svc, dir, uid, t.note, t.kid, "MIGRATED_WATCH", t.enabled, dryRun);
            }
        }

        // 2) 观察点 monitorTargets（uid+坐标 → fid）
        if (wps != null && wps.monitorTargets != null) {
            for (WatchPointStore.Target t : wps.monitorTargets) {
                if (t.uid <= 0L) {
                    r.skipped++;
                    r.details.add("skip monitorTargets 无 uid（纯坐标）");
                    continue;
                }
                mergeOne(r, store, svc, dir, t.uid, t.nick, "", "MIGRATED_MONITOR", true, dryRun);
            }
        }

        // 3) App 本地 seed（uid → fid；默认暂停，用户在页面确认后再启用）
        if (localSeeds != null) {
            for (LocalSeed s : localSeeds) {
                if (s.uid <= 0L) {
                    r.skipped++;
                    continue;
                }
                mergeOne(r, store, svc, dir, s.uid, s.note, s.kid, "MIGRATED_LOCAL", false, dryRun);
            }
        }

        if (!dryRun) store.markAlertMigrated();
        return r;
    }

    private static void mergeOne(MigrateReport r, VaultStore store, WatchlistService svc,
                                 PlayerDirectory dir, long uid, String note, String kid,
                                 String source, boolean enabled, boolean dryRun) {
        PlayerDirectory.Entry hit = dir == null ? null : dir.byUid(uid);
        if (hit == null || hit.fid == null || hit.fid.trim().isEmpty()) {
            r.unresolved++;
            r.details.add("unresolved " + source + " uid=" + uid + "（目录反查不到 fid）");
            if (!dryRun) {
                VaultStore.AlertWatchEntry u = new VaultStore.AlertWatchEntry("uid:" + uid);
                u.uid = uid;
                u.note = note == null ? "" : note;
                u.kid = kid == null ? "" : kid;
                u.health = "UNRESOLVED";
                u.source = source;
                u.legacyRaw = "uid:" + uid;
                store.addAlertUnresolved(u);
            }
            return;
        }
        String fid = hit.fid.trim();
        VaultStore.AlertWatchEntry e = new VaultStore.AlertWatchEntry(fid);
        e.uid = uid;
        e.nick = hit.nick == null ? "" : hit.nick;
        e.kid = kid == null ? "" : kid;
        e.note = note == null ? "" : note;
        e.enabled = enabled;
        e.health = "OK";
        e.source = source;
        e.legacyRaw = String.valueOf(uid);
        e.resolvedAt = System.currentTimeMillis();
        boolean existed = svc.find(fid) != null;
        if (!dryRun) svc.upsert(e);
        if (existed) r.updated++;
        else r.merged++;
    }
}
