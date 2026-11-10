package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.watchpoint.PlayerDirectory;
import com.carlos.vaultserver.watchpoint.WatchPointStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P2 迁移器：三源合并、unresolved 保留、dryRun、幂等、已迁移短路。
 */
public class WatchlistMigratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private VaultStore store;
    private WatchlistService svc;

    @Before
    public void setUp() throws Exception {
        root = Paths.get(tmp.getRoot().getAbsolutePath());
        PlayerDirectory.install(root);
        store = new VaultStore(root);
        store.load();
        svc = new WatchlistService(store);
    }

    @After
    public void clean() {
        PlayerDirectory.install(null);
    }

    private void writePlayerDirectory(long uid, String fid, String nick) throws Exception {
        JSONObject o = new JSONObject();
        o.put("builtAt", 1L);
        JSONArray arr = new JSONArray();
        JSONObject p = new JSONObject();
        p.put("uid", uid);
        p.put("fid", fid);
        p.put("nick", nick);
        p.put("aid", 888L);
        arr.put(p);
        o.put("players", arr);
        Files.writeString(root.resolve("players_dir.json"), o.toString(), StandardCharsets.UTF_8);
        PlayerDirectory.install(root);
    }

    private void addAccountWithWatchTarget(String uid) {
        VaultStore.Account acc = new VaultStore.Account("acc1");
        acc.token = "t";
        store.replaceAll(Collections.singletonList(acc));
        int rc = store.addWatchTarget("acc1", uid, "n", "888", "迁移备注");
        assertEquals("addWatchTarget rc", 0, rc);
        assertEquals("watchTargets size", 1, store.watchTargets("acc1").size());
    }

    private WatchPointStore buildWps(long... uids) {
        WatchPointStore wps = new WatchPointStore(root);
        wps.load();
        wps.monitorTargets.clear();
        for (long uid : uids) {
            wps.monitorTargets.add(new WatchPointStore.Target(uid, "nick" + uid, 10, 20));
        }
        return wps;
    }

    @Test
    public void emptyDirectory_keepsUnresolvedAndMarksMigrated() {
        addAccountWithWatchTarget("49422287");
        WatchPointStore wps = buildWps(123456L);

        WatchlistMigrator.MigrateReport r =
                WatchlistMigrator.run(store, wps, svc, null, false);

        assertEquals(0, r.merged);
        assertEquals(2, r.unresolved);
        assertTrue(store.alertMigrated());
        assertEquals(2, svc.unresolved().size());
        assertTrue(svc.list().isEmpty());
    }

    @Test
    public void resolvableEntriesMergeIntoWatchlistByFid() throws Exception {
        writePlayerDirectory(49422287L, "123861751", "某某");
        assertNotNull("player dir entry", PlayerDirectory.shared().byUid(49422287L));
        addAccountWithWatchTarget("49422287");
        WatchPointStore wps = buildWps(49422287L);

        WatchlistMigrator.MigrateReport r =
                WatchlistMigrator.run(store, wps, svc, null, false);

        assertTrue(r.merged >= 1);
        assertNotNull(svc.find("123861751"));
        VaultStore.AlertWatchEntry e = svc.find("123861751");
        assertEquals("OK", e.health);
        assertEquals(49422287L, e.uid);
        assertEquals(1, svc.list().size());
        assertTrue(svc.allWatchedUids().contains(49422287L));
    }

    @Test
    public void dryRunWritesNothing() throws Exception {
        writePlayerDirectory(49422287L, "123861751", "某某");
        addAccountWithWatchTarget("49422287");
        WatchPointStore wps = buildWps();

        WatchlistMigrator.MigrateReport r =
                WatchlistMigrator.run(store, wps, svc, null, true);

        assertTrue(r.dryRun);
        assertTrue(r.merged >= 1);
        assertTrue(svc.list().isEmpty());
        assertFalse(store.alertMigrated());
    }

    @Test
    public void rerunIsIdempotent() throws Exception {
        writePlayerDirectory(49422287L, "123861751", "某某");
        addAccountWithWatchTarget("49422287");
        WatchPointStore wps = buildWps();

        WatchlistMigrator.run(store, wps, svc, null, false);
        int first = svc.list().size();
        WatchlistMigrator.run(store, wps, svc, null, false);
        assertEquals(first, svc.list().size());
        assertEquals(1, svc.list().size());
    }

    @Test
    public void migrateIfNeededShortCircuitsAfterMarked() throws Exception {
        writePlayerDirectory(49422287L, "123861751", "某某");
        addAccountWithWatchTarget("49422287");
        WatchPointStore wps = buildWps();

        WatchlistMigrator.run(store, wps, svc, null, false);
        WatchlistMigrator.MigrateReport again =
                WatchlistMigrator.migrateIfNeeded(store, wps, svc, false);
        assertTrue(again.alreadyDone);
    }

    @Test
    public void localSeedsAreMigratedPausedByDefault() throws Exception {
        writePlayerDirectory(57347368L, "900000001", "种子");
        java.util.List<WatchlistMigrator.LocalSeed> seeds = new java.util.ArrayList<>();
        seeds.add(new WatchlistMigrator.LocalSeed(57347368L, "本地种子", "888"));
        WatchlistMigrator.run(store, buildWps(), svc, seeds, false);
        VaultStore.AlertWatchEntry e = svc.find("900000001");
        assertNotNull(e);
        assertFalse("App 本地 seed 迁移后默认暂停", e.enabled);
        assertEquals("MIGRATED_LOCAL", e.source);
    }
}
