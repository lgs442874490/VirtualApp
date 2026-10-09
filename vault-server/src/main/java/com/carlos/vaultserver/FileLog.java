package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrLog;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 服务器日志：按天滚动文件（logs/vault-yyyyMMdd.log）+ stdout 双写；
 * 同时接管 WjdrLog sink（引擎/协议层日志与服务器自身日志同文件）。
 */
public final class FileLog {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final AtomicReference<PrintWriter> WRITER = new AtomicReference<>();
    private static volatile Path logDir;
    private static volatile String currentDay = "";

    private FileLog() {
    }

    public static void setup(Path dir) {
        logDir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("日志目录创建失败: " + e.getMessage());
        }
        rollIfNeeded();
        WjdrLog.setSink((level, tag, message) -> log(level, tag, message));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PrintWriter w = WRITER.get();
            if (w != null) {
                w.flush();
                w.close();
            }
        }));
        // 每小时检查滚动 + 清理 14 天前旧日志
        Thread sweeper = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3600_000L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    rollIfNeeded();
                    try (var stream = Files.list(logDir)) {
                        String cutoff = LocalDate.now(Beijing.ZONE_ID).minusDays(14).format(DAY);
                        stream.filter(p -> p.getFileName().toString().matches("vault-\\d{8}\\.log"))
                                .filter(p -> p.getFileName().toString().substring(6, 14).compareTo(cutoff) < 0)
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "log-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    private static synchronized void rollIfNeeded() {
        if (logDir == null) return; // 未 setup（单测）：纯 stdout
        String today = LocalDate.now(Beijing.ZONE_ID).format(DAY);
        if (today.equals(currentDay) && WRITER.get() != null) return;
        try {
            PrintWriter old = WRITER.getAndSet(null);
            if (old != null) {
                old.flush();
                old.close();
            }
            PrintWriter w = new PrintWriter(Files.newBufferedWriter(
                    logDir.resolve("vault-" + today + ".log"), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND));
            currentDay = today;
            WRITER.set(w);
        } catch (IOException e) {
            System.err.println("日志文件打开失败: " + e.getMessage());
        }
    }

    public static void log(char level, String tag, String message) {
        rollIfNeeded();
        String line = java.time.LocalTime.now().format(TIME) + " " + level + "/" + tag + ": " + message;
        System.out.println(line);
        PrintWriter w = WRITER.get();
        if (w != null) {
            synchronized (FileLog.class) {
                w.println(line);
                w.flush();
            }
        }
    }

    public static void info(String format, Object... args) {
        log('I', "VaultSrv", fmt(format, args));
    }

    public static void warn(String format, Object... args) {
        log('W', "VaultSrv", fmt(format, args));
    }

    public static void error(String format, Object... args) {
        log('E', "VaultSrv", fmt(format, args));
    }

    private static String fmt(String format, Object... args) {
        String f = String.valueOf(format);
        if (args == null || args.length == 0) return f;
        for (Object a : args) {
            int i = f.indexOf("{}");
            if (i < 0) break;
            f = f.substring(0, i) + String.valueOf(a) + f.substring(i + 2);
        }
        return f;
    }
}
