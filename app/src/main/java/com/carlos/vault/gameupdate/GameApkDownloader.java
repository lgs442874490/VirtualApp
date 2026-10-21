package com.carlos.vault.gameupdate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 游戏 APK 下载器（M3）。
 *
 * <p>链路按 T1b 取证设计：apkUrl 是 302 短链 → 现场解析重定向拿真实 CDN 地址
 * （路径含日期会变，绝不能硬编码）；CDN 支持 Range（206 + Content-Range）→
 * 断点续传；包体 ~888MB → 下载前做存储预检，过程中回报 进度/速度/剩余时间。
 *
 * <p>所有可单测的纯决策（重定向解析、Content-Range 解析、续传计划、存储预检、
 * 失败分类、文案格式化）都收在 static 纯函数里，<b>单元测试不真正联网、
 * 不下载 888MB</b>；唯一的联网方法 {@link #download} 由 UI 在用户确认后调用。
 */
public class GameApkDownloader {

    /** 下载失败分类（诊断弹窗按类给修复动作） */
    public enum Failure { NETWORK, STORAGE, HTTP, VERIFY }

    /** 重定向跟踪上限：短链 → CDN 一般 1 跳，5 跳足够且防循环 */
    public static final int MAX_REDIRECTS = 5;
    /**
     * 存储预检基线：可用空间至少 1.5GB（契约 §4.2；888MB 包 + 安装/解压余量）。
     * 与系统/文件管理器同口径用十进制（1GB = 10^9），避免「明明还有 1.6G 却提示不足」。
     */
    public static final long MIN_FREE_BYTES = 1_500_000_000L;
    /** 在 1.5GB 基线之上，额外要求「包大小 + 256MB」余量（更大的包也能提前拦截） */
    public static final long FREE_MARGIN_BYTES = 256_000_000L;

    /** 已分类的下载失败：category 给弹窗选按钮，原文进诊断 */
    public static class DownloadException extends IOException {
        public final Failure failure;
        public final int httpCode;

        public DownloadException(Failure failure, String message, int httpCode, Throwable cause) {
            super(message);
            this.failure = failure;
            this.httpCode = httpCode;
            if (cause != null) initCause(cause);
        }
    }

    /** 进度回调：在下载线程调用，UI 侧自行切线程；totalBytes<=0 表示总大小未知 */
    public interface ProgressListener {
        void onProgress(long bytesDone, long totalBytes, long bytesPerSec, long etaSeconds);
    }

    // ---------- 纯函数（JVM 单测锁定口径） ----------

    /** "bytes 0-0/887587540" → 887587540；缺斜杠/非数字 → -1（总大小未知） */
    public static long totalFromContentRange(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash == header.length() - 1) return -1;
        try {
            long total = Long.parseLong(header.substring(slash + 1).trim());
            return total > 0 ? total : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "bytes 100-887587539/887587540" 里的起始偏移（服务端确认的续传起点）；解析失败 -1 */
    public static long startFromContentRange(String header) {
        if (header == null) return -1;
        String h = header.trim();
        if (h.startsWith("bytes")) h = h.substring(5).trim();
        int dash = h.indexOf('-');
        if (dash <= 0) return -1;
        try {
            return Long.parseLong(h.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isRedirect(int httpCode) {
        return httpCode == 301 || httpCode == 302 || httpCode == 303
                || httpCode == 307 || httpCode == 308;
    }

    /**
     * 解析一跳重定向：Location 支持绝对与相对地址（CDN 常给相对路径）。
     * 不可用（location 空/非法）返回 null。
     */
    public static String resolveRedirect(String currentUrl, String location) {
        if (location == null || location.trim().isEmpty()) return null;
        String loc = location.trim();
        try {
            return new URL(new URL(currentUrl), loc).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 续传计划 */
    public enum Resume { RESUME, RESTART }

    /**
     * 断点续传决策（纯函数）：
     * <ul>
     *   <li>RESUME：有半截文件、总大小已知、服务端支持 Range；
     *       起点=半截文件大小（服务端以 206 的 Content-Range 确认为准）；</li>
     *   <li>RESTART：其余一律重下（口径「宁重下、不装坏包」）。</li>
     * </ul>
     */
    public static Resume planResume(long partialBytes, long totalBytes, boolean rangeSupported) {
        if (partialBytes > 0 && totalBytes > 0 && partialBytes < totalBytes && rangeSupported) {
            return Resume.RESUME;
        }
        return Resume.RESTART;
    }

    /** 存储预检：可用空间 ≥ max(1.5GB, 包大小+256MB)；包大小未知按 1.5GB 基线。 */
    public static boolean hasEnoughStorage(long freeBytes, long apkBytes) {
        return freeBytes >= needBytes(apkBytes);
    }

    public static long needBytes(long apkBytes) {
        long need = MIN_FREE_BYTES;
        if (apkBytes > 0) need = Math.max(need, apkBytes + FREE_MARGIN_BYTES);
        return need;
    }

    /** 失败分类兜底：异常类型/文案 → 四类之一（download 主体已精确分类，这里给 catch-all） */
    public static Failure classify(Throwable t) {
        if (t instanceof DownloadException) return ((DownloadException) t).failure;
        String m = t == null ? "" : String.valueOf(t.getMessage());
        if (m.contains("空间") || m.contains("存储") || m.contains("ENOSPC")) return Failure.STORAGE;
        if (m.contains("HTTP") || m.contains("http")) return Failure.HTTP;
        if (m.contains("校验") || m.contains("完整")) return Failure.VERIFY;
        return Failure.NETWORK;
    }

    /**
     * 人类可读大小：十进制口径（1MB = 10^6 字节），与浏览器弹窗/应用商店一致
     * （T1b：887,587,540 字节在 vivo 浏览器弹窗显示 888MB，用户对的是这个数）。
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        double b = bytes;
        if (b >= 1e9) return String.format(Locale.CHINA, "%.2fGB", b / 1e9);
        if (b >= 1e6) return String.format(Locale.CHINA, "%.1fMB", b / 1e6);
        if (b >= 1e3) return String.format(Locale.CHINA, "%.0fKB", b / 1e3);
        return bytes + "B";
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 0) return "?";
        return formatBytes(bytesPerSec) + "/s";
    }

    /** 剩余时间 mm:ss；未知/超长显示 "--:--" */
    public static String formatEta(long etaSeconds) {
        if (etaSeconds < 0 || etaSeconds > 99 * 3600) return "--:--";
        return String.format(Locale.CHINA, "%02d:%02d", etaSeconds / 60, etaSeconds % 60);
    }

    // ---------- 运行时下载（用户点击「立即更新」后才调用） ----------

    /**
     * 下载到 destApk（半截文件为 destApk.path + ".part"，成功后原子改名）。
     * 阻塞式（调用方负责后台线程）；cancel 置 true 后尽快以「下载已取消」失败返回，
     * 半截文件保留供下次续传。
     *
     * @return 已下载完成的完整 APK 文件
     * @throws DownloadException 已分类的失败（含原文）
     */
    public static File download(String url, File destApk, long expectedSizeBytes,
                                ProgressListener listener, AtomicBoolean cancel)
            throws IOException {
        File dir = destApk.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new DownloadException(Failure.STORAGE, "无法创建下载目录 " + dir, 0, null);
        }
        long free = dir == null ? 0 : dir.getUsableSpace();
        if (!hasEnoughStorage(free, expectedSizeBytes)) {
            throw new DownloadException(Failure.STORAGE, "存储空间不足：可用 "
                    + formatBytes(free) + "，本次更新至少需要 "
                    + formatBytes(needBytes(expectedSizeBytes)) + "（请清理后重试）", 0, null);
        }

        File part = new File(destApk.getPath() + ".part");
        long partial = part.exists() ? part.length() : 0;

        // 每条路径恰好一次有效请求；仅当「续传被拒」时多发一次完整请求。
        HttpResult r = null;
        boolean resumed = false;
        long total = -1;
        if (partial > 0) {
            r = openFollowingRedirects(url, "bytes=" + partial + "-");
            boolean serverAligned = r.code == 206
                    && startFromContentRange(r.conn.getHeaderField("Content-Range")) == partial;
            if (r.code != 200 && r.code != 206) {
                int bad = r.code;
                closeQuietly(r);
                throw new DownloadException(Failure.HTTP,
                        "下载服务返回 HTTP " + bad, bad, null);
            }
            if (serverAligned) {
                resumed = true;
                total = totalFromContentRange(r.conn.getHeaderField("Content-Range"));
                if (total <= 0) total = r.conn.getContentLength();
            } else {
                // 服务端不支持续传（200）或起点对不上（206 错位）：不能把错位切片当全量写，
                // 丢弃半截后重新请求完整内容
                closeQuietly(r);
                r = null;
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                partial = 0;
            }
        }
        if (r == null) {
            r = openFollowingRedirects(url, null);
            try {
                if (r.code != 200 && r.code != 206) {
                    throw new DownloadException(Failure.HTTP,
                            "下载服务返回 HTTP " + r.code, r.code, null);
                }
                if (r.code == 206) {
                    total = totalFromContentRange(r.conn.getHeaderField("Content-Range"));
                }
                if (total <= 0) total = r.conn.getContentLength();
            } finally {
                if (r.code != 200 && r.code != 206) closeQuietly(r);
            }
        }

        long startOffset = resumed ? partial : 0;
        try {
            OutputStream out = new FileOutputStream(part, resumed);
            InputStream in = r.conn.getInputStream();
            byte[] buf = new byte[64 * 1024];
            long done = startOffset;
            long lastCbAt = System.currentTimeMillis();
            long lastCbBytes = done;
            long speed = 0;
            try {
                while (true) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        throw new DownloadException(Failure.NETWORK,
                                "连接中断：" + e.getMessage() + "（重试将断点续传）", 0, e);
                    }
                    if (n < 0) break;
                    if (cancel != null && cancel.get()) {
                        throw new DownloadException(Failure.NETWORK, "下载已取消", 0, null);
                    }
                    try {
                        out.write(buf, 0, n);
                    } catch (IOException e) {
                        throw new DownloadException(Failure.STORAGE,
                                "写入失败（存储空间不足？）：" + e.getMessage(), 0, e);
                    }
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastCbAt >= 500) {
                        long dt = now - lastCbAt;
                        if (dt > 0) speed = (done - lastCbBytes) * 1000L / dt;
                        lastCbAt = now;
                        lastCbBytes = done;
                        if (listener != null) {
                            listener.onProgress(done, total, speed, etaOf(done, total, speed));
                        }
                    }
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
                try { in.close(); } catch (Throwable ignored) { }
                r.conn.disconnect();
            }
            if (listener != null) listener.onProgress(done, total, speed, 0);
            // 完整性：长度必须与总大小一致（总大小未知时至少要求非空）
            if (total > 0 && part.length() != total) {
                throw new DownloadException(Failure.VERIFY,
                        "下载不完整：" + formatBytes(part.length()) + "/" + formatBytes(total)
                                + "（重试将断点续传）", 0, null);
            }
            if (part.length() <= 0) {
                throw new DownloadException(Failure.VERIFY, "下载内容为空", 0, null);
            }
            if (destApk.exists()) //noinspection ResultOfMethodCallIgnored
                destApk.delete();
            if (!part.renameTo(destApk)) {
                throw new DownloadException(Failure.STORAGE, "下载文件改名失败", 0, null);
            }
            return destApk;
        } finally {
            closeQuietly(r);
        }
    }

    private static long etaOf(long done, long total, long bytesPerSec) {
        if (total <= 0 || done > total || bytesPerSec <= 0) return -1;
        return (total - done) / bytesPerSec;
    }

    // ---------- HTTP 连接（302 手动逐跳跟随并记录最终地址） ----------

    private static final class HttpResult {
        final HttpURLConnection conn;
        final int code;

        HttpResult(HttpURLConnection conn, int code) {
            this.conn = conn;
            this.code = code;
        }
    }

    private static HttpResult openFollowingRedirects(String url, String rangeHeader)
            throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = open(current, rangeHeader);
            int code = c.getResponseCode();
            if (isRedirect(code)) {
                String next = resolveRedirect(current, c.getHeaderField("Location"));
                closeQuietly(new HttpResult(c, code));
                if (next == null) {
                    throw new DownloadException(Failure.HTTP,
                            "重定向缺少 Location（HTTP " + code + "）", code, null);
                }
                current = next;
                continue;
            }
            return new HttpResult(c, code);
        }
        throw new DownloadException(Failure.HTTP, "重定向次数超过 " + MAX_REDIRECTS, 0, null);
    }

    private static HttpURLConnection open(String url, String rangeHeader) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);   // 手动逐跳跟随，跨协议（http↔https）也安全
        c.setRequestProperty("User-Agent", "MultiAppVault/4.1 (game-update)");
        if (rangeHeader != null) c.setRequestProperty("Range", rangeHeader);
        return c;
    }

    private static void closeQuietly(HttpResult r) {
        if (r == null || r.conn == null) return;
        try {
            InputStream e = r.conn.getErrorStream();
            if (e != null) e.close();
        } catch (Throwable ignored) {
        }
        r.conn.disconnect();
    }
}
