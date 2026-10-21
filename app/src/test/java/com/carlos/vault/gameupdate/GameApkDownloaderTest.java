package com.carlos.vault.gameupdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * M3 下载器纯逻辑（T1b 取证口径）。单测绝不联网、绝不下载 888MB：
 * 只锁 Content-Range 解析 / 302 解析 / 续传计划 / 存储预检 / 失败分类 / 文案。
 */
public class GameApkDownloaderTest {

    // ---------- Content-Range（真机 curl 取证值） ----------

    @Test
    public void contentRange_total_matchesEvidence() {
        // T1b: HTTP/2 206  content-range: bytes 0-0/887587540
        assertEquals(887587540L,
                GameApkDownloader.totalFromContentRange("bytes 0-0/887587540"));
        assertEquals(887587540L,
                GameApkDownloader.totalFromContentRange("bytes 100-887587539/887587540"));
    }

    @Test
    public void contentRange_malformed_returnsUnknown() {
        assertEquals(-1L, GameApkDownloader.totalFromContentRange(null));
        assertEquals(-1L, GameApkDownloader.totalFromContentRange(""));
        assertEquals(-1L, GameApkDownloader.totalFromContentRange("bytes 0-0"));
        assertEquals(-1L, GameApkDownloader.totalFromContentRange("bytes 0-0/"));
        assertEquals(-1L, GameApkDownloader.totalFromContentRange("bytes 0-0/abc"));
        assertEquals(-1L, GameApkDownloader.totalFromContentRange("bytes 0-0/-5"));
    }

    @Test
    public void contentRange_startOffset() {
        assertEquals(100L, GameApkDownloader.startFromContentRange("bytes 100-887587539/887587540"));
        assertEquals(0L, GameApkDownloader.startFromContentRange("bytes 0-0/887587540"));
        assertEquals(-1L, GameApkDownloader.startFromContentRange(null));
        assertEquals(-1L, GameApkDownloader.startFromContentRange("junk"));
        assertEquals(-1L, GameApkDownloader.startFromContentRange("bytes -/100"));
    }

    // ---------- 302 解析（短链 → CDN） ----------

    @Test
    public void redirectCodes() {
        assertTrue(GameApkDownloader.isRedirect(301));
        assertTrue(GameApkDownloader.isRedirect(302));
        assertTrue(GameApkDownloader.isRedirect(303));
        assertTrue(GameApkDownloader.isRedirect(307));
        assertTrue(GameApkDownloader.isRedirect(308));
        assertFalse(GameApkDownloader.isRedirect(200));
        assertFalse(GameApkDownloader.isRedirect(206));
        assertFalse(GameApkDownloader.isRedirect(404));
    }

    @Test
    public void redirect_absoluteLocation() {
        assertEquals("https://wjdr-download.campfiregames.cn/apk/package/2026/10/14/x.apk",
                GameApkDownloader.resolveRedirect(
                        "https://wjdr-app.campfiregames.cn/download",
                        "https://wjdr-download.campfiregames.cn/apk/package/2026/10/14/x.apk"));
    }

    @Test
    public void redirect_relativeLocation_resolvedAgainstCurrent() {
        // 绝对路径 Location：替换 path、保留 host
        assertEquals("https://app.example.com/apk/a.apk",
                GameApkDownloader.resolveRedirect("https://app.example.com/dl", "/apk/a.apk"));
        // 相对路径 Location：相对当前 path 所在目录（/dl 的目录是 /）
        assertEquals("https://app.example.com/files/a.apk",
                GameApkDownloader.resolveRedirect("https://app.example.com/dl", "files/a.apk"));
    }

    @Test
    public void redirect_unusable_returnsNull() {
        assertNull(GameApkDownloader.resolveRedirect("https://a.com/dl", null));
        assertNull(GameApkDownloader.resolveRedirect("https://a.com/dl", "  "));
        // 注：仅格式非法但 URL 构造器能接受的串（如含空格）不在解析期拒绝，
        // 统一由连接建立阶段失败并归入 HTTP 类。
    }

    // ---------- 断点续传计划 ----------

    @Test
    public void resumePlan() {
        assertEquals(GameApkDownloader.Resume.RESUME,
                GameApkDownloader.planResume(500, 1000, true));
        assertEquals(GameApkDownloader.Resume.RESTART,
                GameApkDownloader.planResume(1000, 1000, true));   // 已下满 → 重走校验口径
        assertEquals(GameApkDownloader.Resume.RESTART,
                GameApkDownloader.planResume(0, 1000, true));      // 无半截文件
        assertEquals(GameApkDownloader.Resume.RESTART,
                GameApkDownloader.planResume(500, 1000, false));   // 服务端不支持 Range
        assertEquals(GameApkDownloader.Resume.RESTART,
                GameApkDownloader.planResume(500, -1, true));      // 总大小未知
        assertEquals(GameApkDownloader.Resume.RESTART,
                GameApkDownloader.planResume(1500, 1000, true));   // 半截比总数还大（坏文件）
    }

    // ---------- 存储预检（契约：≥1.5GB 可用） ----------

    @Test
    public void storagePrecheck() {
        long gb = 1024L * 1024L * 1024L;
        // 888MB 包 + 256MB 余量 = 1.14GB < 1.5GB 基线 → 按 1.5GB 卡
        assertFalse(GameApkDownloader.hasEnoughStorage(gb, 887587540L));
        assertTrue(GameApkDownloader.hasEnoughStorage((long) (1.6 * gb), 887587540L));
        // 更大的包按 包大小+256MB 卡
        long need = GameApkDownloader.needBytes(2 * gb);
        assertTrue(need >= 2 * gb + GameApkDownloader.FREE_MARGIN_BYTES);
        assertFalse(GameApkDownloader.hasEnoughStorage(2 * gb, 2 * gb));
        // 包大小未知 → 只按 1.5GB 基线
        assertEquals(GameApkDownloader.MIN_FREE_BYTES, GameApkDownloader.needBytes(0));
        assertFalse(GameApkDownloader.hasEnoughStorage(gb, -1));
    }

    // ---------- 失败分类 ----------

    @Test
    public void classify_keepsExplicitCategory() {
        assertEquals(GameApkDownloader.Failure.STORAGE, GameApkDownloader.classify(
                new GameApkDownloader.DownloadException(
                        GameApkDownloader.Failure.STORAGE, "存储空间不足", 0, null)));
    }

    @Test
    public void classify_byMessage() {
        assertEquals(GameApkDownloader.Failure.STORAGE,
                GameApkDownloader.classify(new RuntimeException("空间不足")));
        assertEquals(GameApkDownloader.Failure.HTTP,
                GameApkDownloader.classify(new RuntimeException("HTTP 404")));
        assertEquals(GameApkDownloader.Failure.VERIFY,
                GameApkDownloader.classify(new RuntimeException("下载不完整：x/y")));
        assertEquals(GameApkDownloader.Failure.NETWORK,
                GameApkDownloader.classify(new RuntimeException("Connection reset")));
        assertEquals(GameApkDownloader.Failure.NETWORK, GameApkDownloader.classify(null));
    }

    // ---------- 文案 ----------

    @Test
    public void formatBytes_evidenceSize() {
        // 十进制口径（与 vivo 浏览器弹窗一致）：887,587,540B 显示 887.6MB
        assertEquals("887.6MB", GameApkDownloader.formatBytes(887587540L));
        assertEquals("1.61GB", GameApkDownloader.formatBytes(1610612736L));
        assertEquals("524KB", GameApkDownloader.formatBytes(512 * 1024L));
        assertEquals("100B", GameApkDownloader.formatBytes(100L));
        assertEquals("?", GameApkDownloader.formatBytes(-1));
    }

    @Test
    public void formatSpeedAndEta() {
        assertTrue(GameApkDownloader.formatSpeed(0).endsWith("/s"));
        assertEquals("--:--", GameApkDownloader.formatEta(-1));
        assertEquals("01:05", GameApkDownloader.formatEta(65));
        assertEquals("00:00", GameApkDownloader.formatEta(0));
        assertEquals("--:--", GameApkDownloader.formatEta(100L * 365L * 24L * 3600L));
    }
}
