package com.carlos.vault.gameupdate;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 「从本地 APK 更新」扫描器（M3b）：在手机「下载」目录里找最新的无尽冬日官方包。
 *
 * <p>M4 真机验证 §3.2：READ_EXTERNAL_STORAGE 未授权时 {@code listFiles()} 恒空，
 * 兜底路径不可用 —— 权限判定抽为 {@link #needsReadPermission}（JVM 可测），
 * UI 层在点击入口时先判定并请求（见 EntryDetailActivity）。
 *
 * <p>扫描两级：①直接读 {@link Environment#DIRECTORY_DOWNLOADS}（targetSdk 26 +
 * requestLegacyExternalStorage，授权后各版本可用）；②Android 10+ 兜底 —— 直接读
 * 失败/为空时查 {@link MediaStore#Downloads}，命中后复制进 app cache 再走原有
 * 校验链（MediaStore 只给流/URI，包解析需要实体文件）。
 *
 * <p>文件名过滤（wjdr*.apk，大小写不敏感）与「最新 mtime 优先」选择是纯函数，
 * JVM 单测锁定（{@code LocalApkScannerTest}）。
 */
public final class LocalApkScanner {

    private LocalApkScanner() {
    }

    // ---------- 纯函数（JVM 单测） ----------

    /** 更新包候选：文件名含 wjdr 且以 .apk 结尾（大小写不敏感）。 */
    public static boolean isUpdateCandidate(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.CHINA);
        return lower.endsWith(".apk") && lower.contains("wjdr");
    }

    /** 候选里挑 lastModified 最新的；没有候选/数组为空返回 null。 */
    public static File newestMatch(File[] files) {
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (f == null || !f.isFile() || !isUpdateCandidate(f.getName())) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    /**
     * 是否需要发起 READ_EXTERNAL_STORAGE 运行时请求：Android 6.0+ 且尚未授予。
     * API &lt; 23 系统安装时静默授予，无需请求（与既有路径一致）。
     */
    public static boolean needsReadPermission(int apiLevel, boolean permissionGranted) {
        return apiLevel >= 23 && !permissionGranted;
    }

    // ---------- Android 路径 ----------

    /**
     * 直接扫「下载」目录，返回最新的 wjdr*.apk；无权限/目录不可读/无候选返回 null。
     */
    public static File scanDownloads() {
        try {
            File dir = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] list = dir == null ? null : dir.listFiles();
            return newestMatch(list);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Android 10+ 兜底：直接扫不到时查 MediaStore.Downloads 里的 wjdr*.apk
     * （按修改时间倒序取第一个命中），复制进 cacheDir 后返回该实体文件 ——
     * 后续校验/安装链路只认文件路径。低版本或任何失败返回 null（不阻断主路径）。
     */
    public static File queryMediaStoreDownloads(ContentResolver cr, File cacheDir) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
            };
            Cursor c = cr.query(collection, proj,
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%.apk"},
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return null;
            try {
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    if (!isUpdateCandidate(name)) continue;
                    Uri item = Uri.withAppendedPath(collection, c.getString(0));
                    InputStream in = cr.openInputStream(item);
                    if (in == null) continue;
                    return copyToCache(in, cacheDir, name);
                }
            } finally {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** MediaStore 项复制为 cache 下的实体文件（固定名覆盖，成功安装后由收尾逻辑删除）。 */
    private static File copyToCache(InputStream in, File cacheDir, String originName)
            throws IOException {
        File dir = cacheDir == null ? null : new File(cacheDir, "local_update");
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("cache 目录不可用");
        File out = new File(dir, originName);
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                os.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
