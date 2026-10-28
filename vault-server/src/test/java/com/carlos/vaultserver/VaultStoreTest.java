package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 身份库：sync 覆盖合并、token 更新、学到指纹落库与重载。 */
public class VaultStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private VaultStore store;

    @Before
    public void setUp() {
        store = new VaultStore(tmp.getRoot().toPath());
    }

    private VaultStore.Account acc(String fpid, boolean keep, String token) {
        VaultStore.Account a = new VaultStore.Account(fpid);
        a.keep = keep;
        a.token = token;
        a.label = "号" + fpid;
        return a;
    }

    @Test
    public void replaceAll_fullOverwrite() {
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(acc("111", true, "tk1"));
        in.add(acc("222", false, "tk2"));
        assertEquals(2, store.replaceAll(in));
        assertEquals("tk1", store.find("111").token);
        assertFalse(store.find("222").keep);
        assertNull(store.find("333"));
    }

    @Test
    public void replaceAll_preservesServerLearnedIdentityWhenAppOmits() {
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(acc("111", true, "tk1"));
        store.replaceAll(in);
        // 服务器登录后学到的角色指纹
        store.learnIdentity("111", "913", "{\"distinct_id\":\"d1\",\"device_id\":\"e1\"}");

        // App 全量 sync 未携带任何指纹（重装后）
        store.replaceAll(in);

        VaultStore.Account a = store.find("111");
        assertEquals("{\"distinct_id\":\"d1\",\"device_id\":\"e1\"}", a.roleIdentity.get("913"));
    }

    @Test
    public void replaceAll_appIdentityWins() {
        List<VaultStore.Account> in = new ArrayList<>();
        in.add(acc("111", true, "tk1"));
        store.replaceAll(in);
        store.learnIdentity("111", "913", "{\"distinct_id\":\"server\"}");

        VaultStore.Account fresh = acc("111", true, "tk1");
        fresh.roleIdentity.put("913", "{\"distinct_id\":\"app\"}");
        List<VaultStore.Account> in2 = new ArrayList<>();
        in2.add(fresh);
        store.replaceAll(in2);

        assertEquals("{\"distinct_id\":\"app\"}", store.find("111").roleIdentity.get("913"));
    }

    @Test
    public void learnIdentity_notOverwriteExisting() {
        store.replaceAll(List.of(acc("111", true, "tk1")));
        store.learnIdentity("111", "888", "{\"distinct_id\":\"first\"}");
        store.learnIdentity("111", "888", "{\"distinct_id\":\"second\"}");
        assertEquals("{\"distinct_id\":\"first\"}", store.find("111").roleIdentity.get("888"));
    }

    @Test
    public void updateToken_thenPersistAcrossReload() {
        store.replaceAll(List.of(acc("111", true, "tk1")));
        assertTrue(store.updateToken("111", "tk2"));
        assertFalse(store.updateToken("999", "x"));

        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        assertEquals("tk2", reloaded.find("111").token);
        assertTrue(reloaded.find("111").keep);
    }

    @Test
    public void setPolicy_validatedAndPersisted() {
        assertTrue(store.setPolicy("grab_back"));
        assertFalse(store.setPolicy("bogus"));
        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        assertEquals("grab_back", reloaded.policy());
    }

    // ---------- 同服多角色：activeUid（2026-10-20） ----------

    @Test
    public void activeUid_persistedAcrossReload() {
        List<VaultStore.Account> in = new ArrayList<>();
        VaultStore.Account a = acc("48460403", true, "tk");
        a.activeSid = "888";
        a.activeUid = "88280792";   // 同服第二角色
        in.add(a);
        store.replaceAll(in);

        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        VaultStore.Account got = reloaded.find("48460403");
        assertEquals("888", got.activeSid);
        assertEquals("88280792", got.activeUid);
    }

    @Test
    public void activeUid_absentStaysNull_legacyBehaviour() {
        List<VaultStore.Account> in = new ArrayList<>();
        VaultStore.Account a = acc("111", true, "tk");
        a.activeSid = "913";
        in.add(a);
        store.replaceAll(in);

        VaultStore reloaded = new VaultStore(tmp.getRoot().toPath());
        reloaded.load();
        VaultStore.Account got = reloaded.find("111");
        assertEquals("913", got.activeSid);
        assertNull(got.activeUid);   // 旧客户端/旧数据：缺省 = 旧行为（只按 sid 发现）
    }

    @Test
    public void activeUid_followsIncomingOnSync() {
        List<VaultStore.Account> in = new ArrayList<>();
        VaultStore.Account a = acc("48460403", true, "tk");
        a.activeSid = "888";
        a.activeUid = "35371154";
        in.add(a);
        store.replaceAll(in);
        assertEquals("35371154", store.find("48460403").activeUid);

        // App 切到同服另一个角色：sync 全量覆盖，activeUid 跟随
        VaultStore.Account b = acc("48460403", true, "tk");
        b.activeSid = "888";
        b.activeUid = "88280792";
        store.replaceAll(List.of(b));
        assertEquals("88280792", store.find("48460403").activeUid);

        // snapshot 深拷贝带 uid（reconcile 比对角色变化用）
        assertEquals("88280792", store.snapshot().get(0).activeUid);
    }

    // ==================== R2 Phase 2：按角色 uid 的开关/参数 ====================

    private static Map<String, Boolean> cfg(Object... kv) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), (Boolean) kv[i + 1]);
        return m;
    }

    private static Map<String, String> params(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), (String) kv[i + 1]);
        return m;
    }

    @Test
    public void roleConfigByUid_roundTripThroughFile() {
        Path dir = tmp.getRoot().toPath();
        VaultStore s1 = new VaultStore(dir);
        VaultStore.Account a = acc("111", true, "tk");
        a.roleConfig.put("888", cfg("gather", true));
        a.roleConfigByUid.put("888:35371154", cfg("gather", false, "mail", true));
        a.roleParamsByUid.put("888:35371154", params("mail", "{\"x\":1}"));
        a.roleConfigByUid.put("888:88280792", cfg("mail", false));
        s1.replaceAll(List.of(a));

        VaultStore s2 = new VaultStore(dir);
        s2.load();
        VaultStore.Account b = s2.find("111");
        assertEquals(2, b.roleConfigByUid.size());
        assertEquals(Boolean.FALSE, b.roleConfigByUid.get("888:35371154").get("gather"));
        assertEquals(Boolean.FALSE, b.roleConfigByUid.get("888:88280792").get("mail"));
        assertEquals("{\"x\":1}", b.roleParamsByUid.get("888:35371154").get("mail"));
        // 旧 sid 表保留为区服默认
        assertEquals(Boolean.TRUE, b.roleConfig.get("888").get("gather"));
    }

    @Test
    public void roleConfigOf_uidFirstThenSidDefault() {
        VaultStore.Account a = acc("111", true, "tk");
        a.roleConfig.put("888", cfg("gather", true, "mail", true));
        a.roleConfigByUid.put("888:35371154", cfg("gather", false));
        a.roleConfigByUid.put("888:88280792", cfg("mail", false));
        a.roleParamsByUid.put("888:88280792", params("mail", "{\"y\":2}"));

        assertEquals("uid 表优先", Boolean.FALSE,
                VaultStore.roleConfigOf(a, 888L, 35371154L).get("gather"));
        assertEquals("uid 表可只覆盖部分键（其余键由服务端按目录默认处理）",
                1, VaultStore.roleConfigOf(a, 888L, 88280792L).size());
        assertEquals("uid", VaultStore.roleConfigScope(a, 888L, 88280792L));
        // 未配置过的角色 → 继承 sid 默认
        assertEquals("继承 sid 默认", Boolean.TRUE,
                VaultStore.roleConfigOf(a, 888L, 777L).get("gather"));
        assertEquals("sid", VaultStore.roleConfigScope(a, 888L, 777L));
        assertEquals("参数同样 uid→sid", "{\"y\":2}",
                VaultStore.roleParamsOf(a, 888L, 88280792L).get("mail"));
        assertNull("sid 参数没有则 null", VaultStore.roleParamsOf(a, 888L, 777L));
        // uid<=0 = 旧路径
        assertEquals(Boolean.TRUE, VaultStore.roleConfigOf(a, 888L, 0L).get("gather"));
    }

    @Test
    public void roleConfigOf_nullAccountAndNothingConfigured() {
        assertNull(VaultStore.roleConfigOf(null, 888L, 1L));
        VaultStore.Account a = acc("111", true, "tk");
        assertNull(VaultStore.roleConfigOf(a, 888L, 35371154L));
        assertEquals("default", VaultStore.roleConfigScope(a, 888L, 35371154L));
    }

    @Test
    public void replaceAll_oldAppSyncKeepsPerUidConfig() {
        Path dir = tmp.getRoot().toPath();
        VaultStore s1 = new VaultStore(dir);
        VaultStore.Account a = acc("111", true, "tk");
        a.roleConfigByUid.put("888:35371154", cfg("gather", false));
        s1.replaceAll(List.of(a));
        // 旧 App（不带 roleConfigByUid）再同步：不得清掉服务器已存的 per-uid 配置（迁移/回滚安全）
        s1.replaceAll(List.of(acc("111", true, "tk2")));
        VaultStore.Account b = s1.find("111");
        assertEquals(Boolean.FALSE, b.roleConfigByUid.get("888:35371154").get("gather"));
        assertEquals("tk2", b.token);
    }
}
