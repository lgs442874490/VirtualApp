package com.carlos.vault;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃可观测性（P2-1）：未捕获异常落盘环形缓冲，供"我的 → 崩溃日志"查看/复制。
 *
 * <p>背景：线上闪退此前只能靠用户口述或小米智能助手的一句"应用自身空指针异常"，
 * 没有堆栈、没有现场。这里在进程内安装默认 UncaughtExceptionHandler：
 * <ul>
 *   <li>先写盘（最近 {@value #MAX_ENTRIES} 条，单条含时间/进程/版本/线程/完整堆栈）；</li>
 *   <li>再交给系统原 handler（不吞崩溃，系统弹窗/日志照常）；</li>
 *   <li>写盘失败静默，绝不因为"记录崩溃"制造第二次崩溃。</li>
 * </ul>
 *
 * <p>只是最小闭环：ANR/tombstone 仍由系统 DropBox 管；后续可把 ApplicationExitInfo
 * 一并纳入诊断包（见 qa/修复计划-20261018.md P2-1）。
 */
public final class CrashGuard {

    private static final String FILE = "crash_ring.log";
    private static final String SEP = "\n---CRASH---\n";
    static final int MAX_ENTRIES = 20;
    private static final long MAX_BYTES = 256 * 1024L;
    private static final Object LOCK = new Object();
    private static volatile boolean installed;

    private CrashGuard() {
    }

    /** 幂等安装；保留系统原 handler 并转发（不改变系统崩溃行为）。 */
    public static void install(Context ctx) {
        if (installed) return;
        synchronized (CrashGuard.class) {
            if (installed) return;
            final Context app = ctx.getApplicationContext();
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    append(app, buildEntry(app, t, e));
                } catch (Throwable ignored) {
                    // 记录失败绝不影响崩溃原流程
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            });
            installed = true;
            Log.i("CrashGuard", "installed, file=" + file(app).getAbsolutePath());
        }
    }

    /** 追加一条现场备注（可选；用于在崩溃前留下"最后在做什么"）。 */
    public static void note(Context ctx, String message) {
        try {
            append(ctx.getApplicationContext(), "[" + now() + "] NOTE " + message);
        } catch (Throwable ignored) {
        }
    }

    /** 读取环形日志（无记录返回空串）。 */
    public static String readLog(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.isFile() || f.length() <= 0) return "";
            byte[] b = new byte[(int) Math.min(f.length(), MAX_BYTES)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return "";
                return new String(b, 0, n, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** 清空日志。 */
    public static void clear(Context ctx) {
        synchronized (LOCK) {
            try {
                File f = file(ctx);
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE);
    }

    private static void append(Context ctx, String entry) {
        if (entry == null || entry.isEmpty()) return;
        synchronized (LOCK) {
            try {
                File f = file(ctx);
                byte[] old = null;
                if (f.isFile() && f.length() > 0 && f.length() < MAX_BYTES) {
                    old = new byte[(int) f.length()];
                    try (FileInputStream in = new FileInputStream(f)) {
                        int n = in.read(old);
                        if (n > 0) old = n == old.length ? old : java.util.Arrays.copyOf(old, n);
                        else old = null;
                    }
                }
                String content = (old == null ? "" : new String(old, StandardCharsets.UTF_8));
                String merged = content.isEmpty() ? entry : content + SEP + entry;
                merged = trimRing(merged, MAX_ENTRIES);
                try (FileOutputStream out = new FileOutputStream(f, false)) {
                    out.write(merged.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String buildEntry(Context ctx, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (e != null) e.printStackTrace(pw);
        pw.flush();
        return "==== " + now() + " ====\n"
                + "process=" + ctx.getPackageName()
                + " pid=" + android.os.Process.myPid()
                + " thread=" + (t == null ? "?" : t.getName())
                + " version=" + versionName(ctx) + "\n"
                + sw;
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
                .format(new Date());
    }

    /**
     * 环形截断：保留最后 maxEntries 条（纯函数，JVM 单测覆盖）。
     * 输入为空/条目数不超限时原样返回。
     */
    static String trimRing(String content, int maxEntries) {
        if (content == null || content.isEmpty() || maxEntries <= 0) return "";
        String[] parts = content.split(java.util.regex.Pattern.quote(SEP));
        if (parts.length <= maxEntries) return content;
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - maxEntries; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(SEP);
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
