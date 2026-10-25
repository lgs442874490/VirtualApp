package com.carlos.vaultserver.monitor;

import com.carlos.vaultserver.Beijing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真机调试监控仓（2026-10-25）：服务端任务级协议监控事件的内存环形缓冲 + JSONL 落盘。
 *
 * <p>用途：真机上「领取/任务」类功能经常“有在跑但游戏里没效果”。本仓把每个功能执行的
 * 链路落成事件流（task.start / io.req / io.rsp / task.end / session / notify / mark），
 * App「真机监控」页经 {@code POST /v1/vault/monitor} 拉取实时查看，也可 SSH 直接看
 * {@code data/monitor/monitor-yyyyMMdd.jsonl}。默认关闭，避免生产常开写盘。
 *
 * <p>开关（环境变量，见 {@link #install(Path, Map)}）：
 * <ul>
 *   <li>{@code VAULT_MONITOR=on|off} 默认 off；</li>
 *   <li>{@code VAULT_MONITOR_DIR} 默认 {@code <dataDir>/monitor}；</li>
 *   <li>{@code VAULT_MONITOR_MEM} 内存保留事件数，默认 5000（1..200000）；</li>
 *   <li>{@code VAULT_MONITOR_MAX_BYTES} 单文件上限，默认 64MB（超出转 .1）；</li>
 *   <li>{@code VAULT_MONITOR_CONTENT} 单字段内容截断字符数，默认 2000（200..20000）。</li>
 * </ul>
 *
 * <p>事件公共字段（记录方负责填，{@link #record(JSONObject)} 补 {@code seq}/{@code ts}）：
 * <pre>
 * seq, ts, kind, fpid, sid, fn, name, trigger, proto, code, durMs, msg, args, data, trunc
 * kind ∈ task.start | task.end | io.req | io.rsp | session | notify | mark | error
 * </pre>
 */
public final class MonitorStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final System.Logger LOG = System.getLogger("Monitor");

    private static volatile MonitorStore instance;

    /** 安装全局实例；{@code env} 为 null 时读 {@link System#getenv()}（单测可注入替身）。 */
    public static MonitorStore install(Path dataDir, Map<String, String> env) {
        MonitorStore s = new MonitorStore(dataDir, env);
        instance = s;
        return s;
    }

    public static MonitorStore install(Path dataDir) {
        return install(dataDir, System.getenv());
    }

    /** 全局实例（未安装为 null）。 */
    public static MonitorStore get() {
        return instance;
    }

    /** 是否已启用（未安装/VAULT_MONITOR≠on → false）。 */
    public static boolean enabled() {
        MonitorStore s = instance;
        return s != null && s.on;
    }

    /** 关闭文件句柄（进程退出/单测清理）。 */
    public static void shutdown() {
        MonitorStore s = instance;
        if (s != null) s.close();
        instance = null;
    }

    // ---------- 实例 ----------

    private final boolean on;
    private final Path dir;
    private final int memMax;
    private final long maxBytes;
    private final int contentLimit;
    private final int argsLimit;
    private final Deque<JSONObject> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Object ioLock = new Object();

    private BufferedWriter writer;
    private String openDay = "";
    private long openBytes;

    MonitorStore(Path dataDir, Map<String, String> env) {
        String flag = envValue(env, "VAULT_MONITOR", "off").trim().toLowerCase(Locale.ROOT);
        this.on = "on".equals(flag) || "1".equals(flag) || "true".equals(flag);
        String custom = envValue(env, "VAULT_MONITOR_DIR", "").trim();
        Path d = custom.isEmpty()
                ? (dataDir == null ? Path.of("data/monitor") : dataDir.resolve("monitor"))
                : Path.of(custom);
        this.dir = d;
        this.memMax = clamp(intEnv(env, "VAULT_MONITOR_MEM", 5000), 1, 200_000);
        this.maxBytes = Math.max(1L << 20, longEnv(env, "VAULT_MONITOR_MAX_BYTES", 64L << 20));
        this.contentLimit = clamp(intEnv(env, "VAULT_MONITOR_CONTENT", 2000), 200, 20_000);
        this.argsLimit = Math.max(200, Math.min(contentLimit, 1000));
        if (this.on) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "监控目录创建失败 " + dir + "：" + e.getMessage());
            }
            // 2026-10-25 修复：seq 必须跨进程重启连续。否则重启后从 1 重新计数，App 端增量游标
            // （since=旧 seq）会永久拉不到新事件。启动时从当日 JSONL 里扫描最大 seq 续号。
            long resumed = scanMaxSeq(dir);
            if (resumed > 0L) seq.set(resumed);
            LOG.log(System.Logger.Level.INFO, "真机监控已启用 dir=" + dir + " mem=" + memMax
                    + " maxBytes=" + maxBytes + " content=" + contentLimit
                    + " seq=" + seq.get());
        }
    }

    /** 扫描监控目录（当日 + .1 轮转文件）内的最大 seq；无文件/失败返回 0。 */
    static long scanMaxSeq(Path dir) {
        if (dir == null) return 0L;
        long max = 0L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "monitor-*.jsonl*")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                try (java.io.BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf("\"seq\":");
                        if (i < 0) continue;
                        int j = i + 6;
                        int k = j;
                        while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
                        if (k > j) {
                            try {
                                long v = Long.parseLong(line.substring(j, k));
                                if (v > max) max = v;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return max;
    }

    public boolean isEnabled() {
        return on;
    }

    public int contentLimit() {
        return contentLimit;
    }

    public int argsLimit() {
        return argsLimit;
    }

    public Path dir() {
        return dir;
    }

    /**
     * 记录一条事件：自动补 {@code seq}/{@code ts}，入内存环形缓冲并追加 JSONL。
     *
     * @return 事件序号；未启用/记录失败返回 0
     */
    public long record(JSONObject event) {
        if (!on || event == null) return 0L;
        long id;
        synchronized (ioLock) {
            id = seq.incrementAndGet();
            try {
                event.put("seq", id);
                event.put("ts", System.currentTimeMillis());
                String line = event.toString();
                appendLine(line, id);
                events.addLast(event);
                trimMemory();
            } catch (Throwable t) {
                dropped.incrementAndGet();
                LOG.log(System.Logger.Level.DEBUG, "监控事件写入失败：" + t.getMessage());
                return 0L;
            }
        }
        return id;
    }

    /**
     * 查询事件（App 轮询用）。
     *
     * @param since      只返回 seq &gt; since 的事件
     * @param limit      最多返回条数（1..1000）
     * @param kinds      空=全部；否则 kind 白名单
     * @param fpid       非空=只看该账号
     * @param includeData false 时剥离 args/data（列表页轻量）
     */
    public JSONObject query(long since, int limit, Collection<String> kinds,
                            String fpid, boolean includeData) {
        int capped = clamp(limit, 1, 1000);
        Set<String> filter = kinds == null ? new LinkedHashSet<>()
                : new LinkedHashSet<>(kinds);
        List<JSONObject> picked = new ArrayList<>();
        long minSeq = 0L;
        for (JSONObject e : events) {
            if (e == null) continue;
            long s = e.optLong("seq", 0L);
            if (minSeq == 0L) minSeq = s;
            if (s <= since) continue;
            if (!filter.isEmpty() && !filter.contains(e.optString("kind", ""))) continue;
            if (fpid != null && !fpid.isEmpty() && !fpid.equals(e.optString("fpid", ""))) {
                continue;
            }
            picked.add(e);
            if (picked.size() >= capped) break;
        }
        JSONArray arr = new JSONArray();
        for (JSONObject e : picked) {
            JSONObject copy = includeData ? e : stripData(e);
            arr.put(copy);
        }
        JSONObject out = new JSONObject();
        out.put("enabled", on);
        out.put("events", arr);
        out.put("count", arr.length());
        out.put("lastSeq", seq.get());
        out.put("minSeq", minSeq);
        out.put("mem", events.size());
        out.put("dropped", dropped.get());
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }

    /** 轻量状态（健康检查/卡片副标题用）。 */
    public JSONObject status() {
        JSONObject out = new JSONObject();
        out.put("enabled", on);
        out.put("dir", dir == null ? "" : dir.toString());
        out.put("lastSeq", seq.get());
        out.put("mem", events.size());
        out.put("memMax", memMax);
        out.put("dropped", dropped.get());
        out.put("openDay", openDay);
        out.put("openBytes", openBytes);
        return out;
    }

    void close() {
        synchronized (ioLock) {
            closeWriterLocked();
        }
        events.clear();
    }

    // ---------- 落盘 ----------

    private void appendLine(String line, long id) throws IOException {
        String day = LocalDate.now(Beijing.ZONE_ID).format(DAY);
        if (writer == null || !day.equals(openDay)) {
            closeWriterLocked();
            openWriterLocked(day);
        }
        if (openBytes > maxBytes) {
            rotateLocked(day);
        }
        writer.write(line);
        writer.newLine();
        writer.flush();
        openBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private void openWriterLocked(String day) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("monitor-" + day + ".jsonl");
        boolean existed = Files.isRegularFile(f);
        writer = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        openDay = day;
        openBytes = existed ? Files.size(f) : 0L;
    }

    private void rotateLocked(String day) {
        try {
            closeWriterLocked();
            Path f = dir.resolve("monitor-" + day + ".jsonl");
            Path old = dir.resolve("monitor-" + day + ".jsonl.1");
            Files.deleteIfExists(old);
            if (Files.isRegularFile(f)) Files.move(f, old,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            openWriterLocked(day);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "监控文件轮转失败：" + e.getMessage());
        }
    }

    private void closeWriterLocked() {
        BufferedWriter w = writer;
        writer = null;
        openDay = "";
        openBytes = 0L;
        if (w == null) return;
        try {
            w.flush();
            w.close();
        } catch (IOException ignored) {
        }
    }

    // ---------- 内存 ----------

    private void trimMemory() {
        while (events.size() > memMax) {
            if (events.pollFirst() == null) break;
            dropped.incrementAndGet();
        }
    }

    private static JSONObject stripData(JSONObject e) {
        JSONObject copy = new JSONObject(e.toString());
        copy.remove("args");
        copy.remove("data");
        return copy;
    }

    // ---------- 工具 ----------

    private static String envValue(Map<String, String> env, String key, String def) {
        if (env == null) return def;
        String v = env.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int intEnv(Map<String, String> env, String key, int def) {
        try {
            return Integer.parseInt(envValue(env, key, String.valueOf(def)).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static long longEnv(Map<String, String> env, String key, long def) {
        try {
            return Long.parseLong(envValue(env, key, String.valueOf(def)).trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 便于单测/巡检：清理目录内 7 天前的监控文件（仅文件名匹配 monitor-yyyyMMdd.jsonl[.1]）。 */
    public static void pruneOldFiles(Path dir, int keepDays) {
        if (dir == null || keepDays <= 0) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "monitor-*.jsonl*")) {
            String cutoff = LocalDate.now(Beijing.ZONE_ID).minusDays(keepDays).format(DAY);
            for (Path p : ds) {
                String name = p.getFileName().toString();
                int start = "monitor-".length();
                if (name.length() < start + 8) continue;
                String day = name.substring(start, start + 8);
                if (day.compareTo(cutoff) < 0) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }
}
