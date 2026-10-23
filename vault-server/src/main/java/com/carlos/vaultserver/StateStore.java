package com.carlos.vaultserver;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 采集状态仓：键型与 App 端 daily_state SharedPreferences 完全一致
 * （todo:/res:/rallies:/questprog:/questdone:/mod:… 为字符串，act:/boxes: 为 int，questts: 为 long），
 * App 经 /v1/vault/state 拉取后按同名键回写本地 prefs，今日页零改动渲染。
 * 持久化 data/state.json（原子写 + .bak）。
 */
public final class StateStore {

    private final Path file;
    private final Path backup;
    private final Map<String, Object> states = new LinkedHashMap<>();
    private final Object lock = new Object();
    private volatile long dirtyAt;

    public StateStore(Path dataDir) {
        this.file = dataDir.resolve("state.json");
        this.backup = dataDir.resolve("state.json.bak");
    }

    public void load() {
        synchronized (lock) {
            states.clear();
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                    for (String k : root.keySet()) {
                        Object v = root.get(k);
                        if (!JSONObject.NULL.equals(v)) states.put(k, v);
                    }
                    return;
                } catch (Exception e) {
                    FileLog.warn("状态文件解析失败 {}：{}", p.getFileName(), e.getMessage());
                }
            }
        }
    }

    /** 写入字符串型键（值为 JSON 文本）。 */
    public void putString(String key, String jsonText) {
        if (key == null || jsonText == null) return;
        synchronized (lock) {
            states.put(key, jsonText);
            dirtyAt = System.currentTimeMillis();
        }
    }

    /** 写入数值型键（act:/boxes: int，questts: long）。 */
    public void putLong(String key, long v) {
        if (key == null) return;
        synchronized (lock) {
            states.put(key, v);
            dirtyAt = System.currentTimeMillis();
        }
    }

    public String getString(String key) {
        synchronized (lock) {
            Object v = states.get(key);
            return v instanceof String ? (String) v : null;
        }
    }

    /** 快照（App 拉取用）。 */
    public Map<String, Object> snapshot() {
        synchronized (lock) {
            return new LinkedHashMap<>(states);
        }
    }

    /** 删除某账号全部键（账号从库中移除时清理）。 */
    public void removeAccount(String fpid) {
        String tag = ":" + fpid + ":";
        synchronized (lock) {
            states.keySet().removeIf(k -> k.contains(tag));
            dirtyAt = System.currentTimeMillis();
        }
    }

    /**
     * 删除单个键（批次6：日报 30 天清理用）。键不存在时静默返回；线程安全、有删除才标脏。
     */
    public void remove(String key) {
        if (key == null) return;
        synchronized (lock) {
            if (states.remove(key) != null) {
                dirtyAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * 删除前缀匹配的全部键（如 {@code srv:digest:}），返回删除条数；线程安全、有删除才标脏。
     * 空/null 前缀直接返回 0（防止误删全表）。
     */
    public int removePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return 0;
        synchronized (lock) {
            int before = states.size();
            states.keySet().removeIf(k -> k != null && k.startsWith(prefix));
            int removed = before - states.size();
            if (removed > 0) {
                dirtyAt = System.currentTimeMillis();
            }
            return removed;
        }
    }

    /** 落盘（由 SessionKeeper 周期调用；无变更跳过）。 */
    public void flush() {
        synchronized (lock) {
            if (dirtyAt == 0) return;
            try {
                JSONObject root = new JSONObject();
                for (Map.Entry<String, Object> e : states.entrySet()) {
                    root.put(e.getKey(), e.getValue());
                }
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
                if (Files.isRegularFile(file)) {
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                dirtyAt = 0;
            } catch (IOException e) {
                FileLog.error("状态文件写入失败：{}", e.getMessage());
            }
        }
    }
}
