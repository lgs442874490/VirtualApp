package com.carlos.vault.gameupdate;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M3b 本地更新包扫描判定（纯函数）：wjdr*.apk 过滤（大小写不敏感）、
 * 最新 mtime 优先、运行时权限需求判定（Android 6+ 且未授予）。
 *
 * <p>对应 M4 真机验证 §3.2：未授权时 listFiles() 恒空 → 权限判定抽到
 * {@link LocalApkScanner#needsReadPermission}，由 UI 层先闸门再扫描。
 */
public class LocalApkScannerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------- isUpdateCandidate ----------

    @Test
    public void candidate_matchesOfficialNaming() {
        assertTrue(LocalApkScanner.isUpdateCandidate("wjdr_1-16-12_147_1.apk"));
        assertTrue(LocalApkScanner.isUpdateCandidate("wjdr_test.apk"));
    }

    @Test
    public void candidate_isCaseInsensitive() {
        assertTrue(LocalApkScanner.isUpdateCandidate("WJDR_1-16-12_147_1.APK"));
        assertTrue(LocalApkScanner.isUpdateCandidate("Wjdr_x.Apk"));
    }

    @Test
    public void candidate_rejectsNonApkAndNonWjdr() {
        assertFalse(LocalApkScanner.isUpdateCandidate("other_game.apk"));
        assertFalse(LocalApkScanner.isUpdateCandidate("wjdr_readme.txt"));
        assertFalse(LocalApkScanner.isUpdateCandidate("game.zip"));
        assertFalse(LocalApkScanner.isUpdateCandidate("apk"));
        assertFalse(LocalApkScanner.isUpdateCandidate(""));
        assertFalse(LocalApkScanner.isUpdateCandidate(null));
    }

    // ---------- newestMatch ----------

    @Test
    public void newestMatch_picksLatestMtimeAmongCandidates() throws Exception {
        File dir = tmp.newFolder("dl");
        File w1 = new File(dir, "wjdr_old.apk");
        File w2 = new File(dir, "wjdr_new.apk");
        File other = new File(dir, "other.apk");
        assertTrue(w1.createNewFile());
        assertTrue(w2.createNewFile());
        assertTrue(other.createNewFile());
        assertTrue(w1.setLastModified(1_000_000L));
        assertTrue(w2.setLastModified(2_000_000L));
        assertTrue(other.setLastModified(9_000_000L)); // 非 wjdr，mtime 再新也不选
        assertEquals(w2, LocalApkScanner.newestMatch(dir.listFiles()));
    }

    @Test
    public void newestMatch_ignoresDirectoriesAndNonCandidates() throws Exception {
        File dir = tmp.newFolder("dl2");
        File decoyDir = new File(dir, "wjdr_folder.apk");
        assertTrue(decoyDir.mkdir()); // 目录不参与（原实现 f.isFile() 语义）
        File readme = new File(dir, "wjdr_readme.txt");
        assertTrue(readme.createNewFile());
        assertNull(LocalApkScanner.newestMatch(dir.listFiles()));
    }

    @Test
    public void newestMatch_emptyOrNullInputReturnsNull() throws Exception {
        assertNull(LocalApkScanner.newestMatch(null));
        assertNull(LocalApkScanner.newestMatch(new File[0]));
        File dir = tmp.newFolder("dl3");
        assertNull(LocalApkScanner.newestMatch(dir.listFiles()));
    }

    @Test
    public void newestMatch_singleCandidateWinsByDefault() throws Exception {
        File dir = tmp.newFolder("dl4");
        File only = new File(dir, "wjdr_only.apk");
        assertTrue(only.createNewFile());
        assertEquals(only, LocalApkScanner.newestMatch(dir.listFiles()));
    }

    // ---------- needsReadPermission ----------

    @Test
    public void permission_notNeededBelowMarshmallow() {
        // API < 23 安装时静默授予，无需运行时请求（既有路径不变）
        assertFalse(LocalApkScanner.needsReadPermission(22, false));
        assertFalse(LocalApkScanner.needsReadPermission(15, false));
    }

    @Test
    public void permission_neededWhenGrantedFalseOnMarshmallowPlus() {
        assertTrue(LocalApkScanner.needsReadPermission(23, false));
        assertTrue(LocalApkScanner.needsReadPermission(26, false));
        assertTrue(LocalApkScanner.needsReadPermission(34, false)); // M4 真机 Android 14
    }

    @Test
    public void permission_notNeededWhenGranted() {
        assertFalse(LocalApkScanner.needsReadPermission(23, true));
        assertFalse(LocalApkScanner.needsReadPermission(34, true));
    }
}
