package com.carlos.vault.protocapture;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 真机抓包结果缓冲：内存最多 500 条，Activity 实时读取；导出 files/proto-capture-<ts>.txt。
 * 无网络/无服务端依赖；停止后数据仍保留，可再次导出。
 */
public final class ProtoCaptureLog {
    public static final int MAX = 500;
    private static final ArrayDeque<ProtoCaptureRecord> BUFFER = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA);
    /** 实时落盘文件：抓包进程崩溃时内存记录会丢，文件保留已抓内容。 */
    private static volatile File liveFile;

    private ProtoCaptureLog() {
    }

    /** 服务启动时指定实时落盘文件（每次开始抓包清空）。 */
    public static synchronized void setLiveFile(File f) {
        liveFile = f;
        if (f != null) {
            try (FileWriter w = new FileWriter(f, false)) {
                w.write("");
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void add(ProtoCaptureRecord r) {
        if (r == null) return;
        BUFFER.addFirst(r);
        while (BUFFER.size() > MAX) BUFFER.removeLast();
        appendLive(r);
    }

    private static void appendLive(ProtoCaptureRecord r) {
        File f = liveFile;
        if (f == null || r == null) return;
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(r.exportText());
            w.write('\n');
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void clear() {
        BUFFER.clear();
        File f = liveFile;
        if (f != null) {
            try (FileWriter w = new FileWriter(f, false)) {
                w.write("");
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized List<ProtoCaptureRecord> snapshot() {
        return new ArrayList<>(BUFFER);
    }

    public static synchronized int size() {
        return BUFFER.size();
    }

    public static synchronized File export(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), "proto-capture-" + TS.format(new Date()) + ".txt");
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("# VAContainer 真机抓包 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.CHINA).format(new Date()) + " records=" + BUFFER.size() + "\n");
            for (ProtoCaptureRecord r : BUFFER) {
                w.write(r.exportText());
                w.write('\n');
            }
        }
        return f;
    }

    /** 从解码后的 Map/List 生成可读 JSON（不依赖 Android org.json，便于单测/导出）。 */
    public static String toReadable(Object v) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, v, 0);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object v, int depth) {
        if (depth > 6) {
            sb.append("…");
            return;
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                appendJson(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                appendJson(sb, o, depth + 1);
            }
            sb.append(']');
        } else if (v instanceof byte[]) {
            sb.append('"').append(hex((byte[]) v, 64)).append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            sb.append('"').append(esc(String.valueOf(v))).append('"');
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    public static String hex(byte[] b, int max) {
        if (b == null) return "";
        int n = Math.min(b.length, Math.max(0, max));
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
        if (b.length > n) sb.append("…");
        return sb.toString();
    }
}
