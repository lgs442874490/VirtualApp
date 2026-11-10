package com.carlos.vaultserver.alert;

import com.carlos.vaultserver.VaultStore;
import com.carlos.vaultserver.watchpoint.PlayerDirectory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P2 名单服务：fid CRUD、UNRESOLVED、持久化、幂等 upsert、派生 uid 集合。
 */
public class WatchlistServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;
    private WatchlistService svc;

    @Before
    public void setUp() throws Exception {
        PlayerDirectory.install(Paths.get(tmp.getRoot().getAbsolutePath()));
        store = new VaultStore(Paths.get(tmp.getRoot().getAbsolutePath()));
        store.load();
        svc = new WatchlistService(store);
    }

    @After
    public void clean() {
        // PlayerDirectory.install(null) 会 NPE；重装到临时目录等价于清空共享目录。
        PlayerDirectory.install(null);
    }

    @Test
    public void add_keepsFidEvenWhenUnresolved() {
        assertTrue(svc.add("123861751", "宿敌"));
        List<VaultStore.AlertWatchEntry> list = svc.list();
        assertEquals(1, list.size());
        VaultStore.AlertWatchEntry e = list.get(0);
        assertEquals("123861751", e.fid);
        assertEquals("宿敌", e.note);
        assertEquals("UNRESOLVED", e.health);
        assertEquals(0L, e.uid);
        assertTrue(svc.allWatchedUids().isEmpty());
    }

    @Test
    public void add_rejectsNonNumericFid() {
        assertFalse(svc.add("abc", ""));
        assertFalse(svc.add("", ""));
        assertFalse(svc.add(null, ""));
        assertTrue(svc.list().isEmpty());
    }

    @Test
    public void add_sameFidIsIdempotent() {
        assertTrue(svc.add("123861751", "A"));
        assertTrue(svc.add("123861751", "B"));
        assertEquals(1, svc.list().size());
        assertEquals("B", svc.list().get(0).note);
    }

    @Test
    public void updateAndRemove_persistAcrossReload() {
        svc.add("123861751", "宿敌");
        assertTrue(svc.update("123861751", "改名", false));
        store.load();
        VaultStore.AlertWatchEntry e = svc.list().get(0);
        assertEquals("改名", e.note);
        assertFalse(e.enabled);
        assertTrue(svc.remove("123861751"));
        store.load();
        assertTrue(svc.list().isEmpty());
    }

    @Test
    public void upsertWithUid_marksOkAndDerivesUidSet() {
        VaultStore.AlertWatchEntry e = new VaultStore.AlertWatchEntry("123861751");
        e.uid = 49422287L;
        e.nick = "某某";
        e.health = "OK";
        e.source = "MIGRATED_WATCH";
        e.legacyRaw = "49422287";
        assertTrue(store.upsertAlertWatch(e));
        VaultStore.AlertWatchEntry got = svc.find("123861751");
        assertNotNull(got);
        assertEquals("OK", got.health);
        assertEquals(49422287L, got.uid);
        Set<Long> uids = svc.allWatchedUids();
        assertTrue(uids.contains(49422287L));
    }

    @Test
    public void disabledEntryIsNotInUidSet() {
        VaultStore.AlertWatchEntry e = new VaultStore.AlertWatchEntry("123861751");
        e.uid = 49422287L;
        e.health = "OK";
        e.enabled = false;
        store.upsertAlertWatch(e);
        assertTrue(svc.allWatchedUids().isEmpty());
    }

    @Test
    public void resolvePendingReturnsZeroWithoutDirectoryHit() {
        svc.add("123861751", "");
        assertEquals(0, svc.resolvePending());
        assertEquals("UNRESOLVED", svc.list().get(0).health);
    }

    @Test
    public void unresolvedLegacyUidStillMatchesDuringMigration() {
        VaultStore.AlertWatchEntry u = new VaultStore.AlertWatchEntry("uid:49422287");
        u.uid = 49422287L;
        u.health = "UNRESOLVED";
        u.source = "MIGRATED_WATCH";
        u.legacyRaw = "uid:49422287";
        store.addAlertUnresolved(u);
        assertTrue("迁移期旧 uid 不能漏监控", svc.allWatchedUids().contains(49422287L));
    }

    @Test
    public void findReturnsNullForUnknownOrInvalidFid() {
        assertNull(svc.find("999"));
        assertNull(svc.find("abc"));
        assertNull(svc.find(null));
    }
}
