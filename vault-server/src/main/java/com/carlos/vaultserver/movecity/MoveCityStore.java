package com.carlos.vaultserver.movecity;

import com.carlos.vaultserver.FileLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 迁城任务持久化：{@code data/movecity_jobs.json}（任务数组）。
 *
 * <p>写入模式对齐 {@code WatchPointStore}：tmp 文件 + 原子 move（失败退化普通替换）。
 * 引擎在每次任务状态变更后调用 {@link #save}；服务重启时 {@link #loadJobs} 恢复。
 */
public final class MoveCityStore {

    private final Path file;

    public MoveCityStore(Path dataDir) {
        this.file = dataDir.resolve("movecity_jobs.json");
    }

    /** 全量覆写持久化（job 快照数组）。 */
    public synchronized void save(List<MoveCityJob> jobs) {
        JSONArray arr = new JSONArray();
        for (MoveCityJob job : jobs) {
            arr.put(new JSONObject(job.toMap()));
        }
        JSONObject root = new JSONObject();
        root.put("jobs", arr);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, root.toString(2).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException fallback) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            FileLog.warn("保存迁城任务到磁盘失败：{}", String.valueOf(e));
        }
    }

    /** 启动时载入历史任务快照（原始 JSON，引擎自行判定恢复/归档）。 */
    public synchronized List<JSONObject> loadJobs() {
        List<JSONObject> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            // 旧版残留 tmp（崩溃残留）也接受
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            if (!Files.isRegularFile(tmp)) return out;
            try {
                file.toFile().getParentFile().mkdirs();
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                return out;
            }
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("jobs");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception e) {
            FileLog.warn("迁城任务文件无法解析（忽略并重建）：{}", String.valueOf(e));
        }
        return out;
    }
}
