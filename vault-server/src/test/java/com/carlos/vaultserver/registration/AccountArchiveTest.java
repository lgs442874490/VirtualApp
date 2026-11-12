package com.carlos.vaultserver.registration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 账号存档单测（2026-11-11 用户要求：记录手机号与 token，掉登录可重取码、换设备可直登）。
 */
public class AccountArchiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void recordRegistration_storesPhoneAndToken() throws Exception {
        AccountArchive a = AccountArchive.create(tmp.getRoot().toPath());
        a.recordRegistration("542893784", "17703785597", "d1", "tk-abc-123");
        Map<String, Object> e = a.findByFpid("542893784");
        assertNotNull(e);
        assertEquals("17703785597", e.get("phone"));
        assertEquals("d1", e.get("provider"));
        assertEquals("tk-abc-123", e.get("sdkToken"));
    }

    @Test
    public void recordRole_appendsServerAndUid() {
        AccountArchive a = AccountArchive.create(tmp.getRoot().toPath());
        a.recordRegistration("536409023", "16220590893", "d1", "tk1");
        a.recordRole("536409023", 888L, 299452689L);
        a.recordRole("536409023", 888L, 299453063L);   // 同区更新 uid，不重复追加
        Map<String, Object> e = a.findByFpid("536409023");
        String roles = String.valueOf(e.get("roles"));
        assertTrue("应含 uid 299453063: " + roles, roles.contains("299453063"));
        assertEquals("同区只保留一条", 1, countOccurrences(roles, "888"));
    }

    @Test
    public void updateToken_refreshesForDeviceSwitch() {
        AccountArchive a = AccountArchive.create(tmp.getRoot().toPath());
        a.recordRegistration("999", "13800000000", "d1", "old-token");
        a.updateToken("999", "new-token");
        assertEquals("new-token", a.findByFpid("999").get("sdkToken"));
    }

    @Test
    public void persistence_survivesReload() throws Exception {
        Path dir = tmp.getRoot().toPath();
        AccountArchive a = AccountArchive.create(dir);
        a.recordRegistration("777", "13900000000", "d1", "tk777");
        // 重新加载（模拟服务重启）
        AccountArchive b = AccountArchive.create(dir);
        Map<String, Object> e = b.findByFpid("777");
        assertNotNull("重启后应能查到", e);
        assertEquals("13900000000", e.get("phone"));
        assertEquals("tk777", e.get("sdkToken"));
        assertTrue("文件应存在", Files.isRegularFile(dir.resolve("account_archive.json")));
    }

    @Test
    public void mask_hidesMiddle() {
        assertEquals("177****5597", AccountArchive.mask("17703785597"));
        assertEquals("***", AccountArchive.mask("1234567"));
        assertEquals("", AccountArchive.mask(null));
    }

    @Test
    public void findByFpid_unknownReturnsNull() {
        AccountArchive a = AccountArchive.create(tmp.getRoot().toPath());
        assertNull(a.findByFpid("nope"));
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
