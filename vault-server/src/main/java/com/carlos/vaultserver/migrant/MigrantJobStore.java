package com.carlos.vaultserver.migrant;

import com.carlos.vaultserver.FileLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移民任务仓：{@code data/migrant.json}（任务 + attempts，原子写 + .bak）。
 *
 * <p>风格照 {@code vault-server/.../StateStore.java}：写临时文件 → 旧文件备份 .bak →
 * ATOMIC_MOVE 覆盖；读取先主文件再 .bak，单条任务解析失败只跳过该条并告警。
 *
 * <p>线程安全：所有读写走 {@code lock}；调度线程每秒 {@link #flushIfDirty()}，关停时
 * Main 调 {@link #flush()}。
 */
public final class MigrantJobStore {

    private static final int FORMAT_VERSION = 1;

    private final Path file;
    private final Path backup;
    private final Map<String, MigrantJob> jobs = new LinkedHashMap<>();
    private final Object lock = new Object();
    private volatile boolean dirty;

    public MigrantJobStore(Path dataDir) {
        this.file = dataDir.resolve("migrant.json");
        this.backup = dataDir.resolve("migrant.json.bak");
    }

    /** 载入任务；主文件损坏时回落 .bak。 */
    public void load() {
        synchronized (lock) {
            jobs.clear();
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    JSONArray arr = root.optJSONArray("jobs");
                    if (arr == null) arr = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        try {
                            MigrantJob job = MigrantJob.fromJson(o);
                            if (!job.jobId.isEmpty()) jobs.put(job.jobId, job);
                        } catch (RuntimeException e) {
                            FileLog.warn("移民任务解析跳过 {}：{}", p.getFileName(), e.getMessage());
                        }
                    }
                    dirty = false;
                    return;
                } catch (Exception e) {
                    FileLog.warn("移民任务文件解析失败 {}：{}", p.getFileName(), e.getMessage());
                }
            }
        }
    }

    /** 任务总数（Main 启动日志用）。 */
    public int count() {
        synchronized (lock) {
            return jobs.size();
        }
    }

    /** 全部任务快照（引用；读字段请容忍并发更新）。 */
    public List<MigrantJob> all() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(jobs.values()));
        }
    }

    public MigrantJob get(String jobId) {
        if (jobId == null || jobId.isEmpty()) return null;
        synchronized (lock) {
            return jobs.get(jobId);
        }
    }

    /** 新增/覆盖任务并标脏。 */
    public void put(MigrantJob job) {
        if (job == null || job.jobId == null || job.jobId.isEmpty()) return;
        synchronized (lock) {
            jobs.put(job.jobId, job);
            dirty = true;
        }
    }

    public boolean remove(String jobId) {
        if (jobId == null || jobId.isEmpty()) return false;
        synchronized (lock) {
            boolean removed = jobs.remove(jobId) != null;
            if (removed) dirty = true;
            return removed;
        }
    }

    /** 任务有更新（引擎改字段后调用）。 */
    public void markDirty() {
        dirty = true;
    }

    /** 有更新才落盘；返回是否真的写盘。 */
    public boolean flushIfDirty() {
        if (!dirty) return false;
        flush();
        return true;
    }

    /** 落盘（原子写 + .bak）。 */
    public void flush() {
        synchronized (lock) {
            if (!dirty) return;
            try {
                JSONObject root = new JSONObject();
                root.put("version", FORMAT_VERSION);
                JSONArray arr = new JSONArray();
                for (MigrantJob job : jobs.values()) {
                    arr.put(job.toStoreJson());
                }
                root.put("jobs", arr);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, root.toString().getBytes(StandardCharsets.UTF_8));
                if (Files.isRegularFile(file)) {
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
            } catch (IOException e) {
                FileLog.error("移民任务文件写入失败：{}", e.getMessage());
            }
        }
    }
}
