package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.FileLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起号批次持久化仓（M4b 任务书 §2.5，模式对齐 RegistrationStore：原子写 + .bak）。
 *
 * <p>文件 {@code <dataDir>/bootstrap_batches.json}，保存最近 {@link #MAX_BATCHES}=30 个批次的
 * <b>可持久化视图</b>（{@link BootstrapBatchRunner#view()} 产出）：批次元数据 + 每号
 * label/fpid/state/steps/error/uid/furnaceLevel + masked sdkToken + encrypted 产物；
 * <b>不含</b>明文 sdkToken（与 {@link BootstrapReferral} 凭据交付清单的差别）。
 *
 * <p>重启恢复：{@link #load()} 时发现 RUNNING/WAITING 批次一律标记
 * {@code FAILED("服务重启中断")}（进程重启后无人续跑，历史可查）。进程级单例：
 * {@link #install(Path)} + {@link #get()}。
 */
public final class BootstrapBatchStore {

    /** 最多保留批次个数（任务书 30）。 */
    public static final int MAX_BATCHES = 30;
    /** 重启中断标记。 */
    public static final String RESTART_INTERRUPTED = "服务重启中断";

    private static volatile BootstrapBatchStore instance;

    /** 安装进程级实例（Main 启动调用；重复 install 覆盖旧实例，单测可重装）。 */
    public static BootstrapBatchStore install(Path dataDir) {
        BootstrapBatchStore s = new BootstrapBatchStore(dataDir);
        s.load();
        instance = s;
        return s;
    }

    /** 全局实例（未安装为 null）。 */
    public static BootstrapBatchStore get() {
        return instance;
    }

    /** 清掉进程级实例（进程退出 / 单测隔离）。 */
    public static void shutdown() {
        instance = null;
    }

    private final Path file;
    private final Path backup;
    /** 批次 id → 视图（插入序；list 时倒序 = 最近在前）。 */
    private final Map<String, JSONObject> batches = new LinkedHashMap<>();
    private final Object lock = new Object();

    public BootstrapBatchStore(Path dataDir) {
        Path dir = dataDir == null ? Path.of("data") : dataDir;
        this.file = dir.resolve("bootstrap_batches.json");
        this.backup = dir.resolve("bootstrap_batches.json.bak");
    }

    /** 读档（file → .bak 兜底）；RUNNING/WAITING 批次标记重启中断并立即回写。 */
    public void load() {
        synchronized (lock) {
            batches.clear();
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                    JSONArray arr = root.optJSONArray("batches");
                    if (arr == null) continue;
                    boolean dirty = false;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) continue;
                        String id = b.optString("id", "");
                        if (id.isEmpty()) continue;
                        String st = b.optString("state", "");
                        if ("RUNNING".equals(st) || "WAITING".equals(st)) {
                            b.put("state", "FAILED");
                            b.put("error", RESTART_INTERRUPTED);
                            b.put("finishedAt", System.currentTimeMillis());
                            dirty = true;
                        }
                        batches.put(id, b);
                    }
                    if (dirty) flushLocked();
                    return;
                } catch (Exception e) {
                    FileLog.warn("起号批次文件解析失败 {}：{}", p.getFileName(),
                            String.valueOf(e.getMessage()));
                }
            }
        }
    }

    /** 追加批次视图（批次启动时）；超出 {@link #MAX_BATCHES} 淘汰最旧。 */
    public void append(JSONObject view) {
        if (view == null) return;
        String id = view.optString("id", "");
        if (id.isEmpty()) return;
        synchronized (lock) {
            batches.put(id, new JSONObject(view.toString()));
            while (batches.size() > MAX_BATCHES) {
                String oldest = batches.keySet().iterator().next();
                batches.remove(oldest);
            }
            flushLocked();
        }
    }

    /** 按 id 更新批次视图（每号结束 / 终态）；不存在的 id 静默忽略（已被截断淘汰）。 */
    public void update(JSONObject view) {
        if (view == null) return;
        String id = view.optString("id", "");
        if (id.isEmpty()) return;
        synchronized (lock) {
            if (!batches.containsKey(id)) return;
            batches.put(id, new JSONObject(view.toString()));
            flushLocked();
        }
    }

    /** 按 id 查批次视图；不存在返回 null。 */
    public JSONObject find(String batchId) {
        if (batchId == null || batchId.isEmpty()) return null;
        synchronized (lock) {
            JSONObject v = batches.get(batchId);
            return v == null ? null : new JSONObject(v.toString());
        }
    }

    /** 最近批次列表（新→旧），最多 limit 条。 */
    public List<JSONObject> list(int limit) {
        List<JSONObject> out = new ArrayList<>();
        synchronized (lock) {
            List<String> ids = new ArrayList<>(batches.keySet());
            for (int i = ids.size() - 1; i >= 0 && out.size() < limit; i--) {
                out.add(new JSONObject(batches.get(ids.get(i)).toString()));
            }
        }
        return out;
    }

    /** 第一个非终态批次（WAITING/RUNNING；新→旧找）；无则 null。 */
    public JSONObject active() {
        synchronized (lock) {
            List<String> ids = new ArrayList<>(batches.keySet());
            for (int i = ids.size() - 1; i >= 0; i--) {
                JSONObject v = batches.get(ids.get(i));
                String st = v.optString("state", "");
                if ("WAITING".equals(st) || "RUNNING".equals(st)) {
                    return new JSONObject(v.toString());
                }
            }
        }
        return null;
    }

    /** 当前保留批次数（诊断 / 单测）。 */
    public int size() {
        synchronized (lock) {
            return batches.size();
        }
    }

    /** 落盘：临时文件 + .bak 备份 + 原子替换（对齐 RegistrationStore.flush）。 */
    public void flush() {
        synchronized (lock) {
            if (!batches.isEmpty()) flushLocked();
        }
    }

    private void flushLocked() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (JSONObject v : batches.values()) arr.put(v);
            root.put("batches", arr);
            root.put("savedAt", System.currentTimeMillis());
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            FileLog.error("起号批次文件写入失败：{}", String.valueOf(e.getMessage()));
        }
    }
}
