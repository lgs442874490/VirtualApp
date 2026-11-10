package com.carlos.vaultserver;

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
 * 战争通知事件环形缓冲（data/events.json，原子写 + .bak，风格同 StateStore）。
 *
 * <p>每条事件带单调递增 seq（持久化，重启不回退）；容量 200 条，超出裁掉最旧。
 * App 经 /v1/vault/events 用 cursor（上次拿到的最大 seq）增量拉取。事件频率受
 * WarNotify 冷却节流约束（同 target+action 默认 5 分钟一条），同步落盘压力可忽略。
 */
public final class WatchEventStore {

    /** 环形容量：最近 500 条（P3 观察点事件入库后共用）。 */
    static final int CAP = 500;
    /** 单次拉取上限（防 App 端首拉全量过大；事件满 200 时分两页）。 */
    static final int FETCH_LIMIT = 100;

    private final Path file;
    private final Path backup;
    private final List<JSONObject> events = new ArrayList<>();
    /** alertKey → 事件（幂等：同一条告警只入库一次）。 */
    private final java.util.Map<String, JSONObject> byAlertKey = new java.util.LinkedHashMap<>();
    private long seq;
    private final Object lock = new Object();

    public WatchEventStore(Path dataDir) {
        this.file = dataDir.resolve("events.json");
        this.backup = dataDir.resolve("events.json.bak");
    }

    public void load() {
        synchronized (lock) {
            events.clear();
            byAlertKey.clear();
            seq = 0;
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                    seq = root.optLong("seq", 0);
                    JSONArray arr = root.optJSONArray("events");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject e = arr.optJSONObject(i);
                            if (e == null) continue;
                            events.add(e);
                            String k = e.optString("alertKey", "");
                            if (!k.isEmpty()) byAlertKey.put(k, e);
                        }
                    }
                    return;
                } catch (Exception e) {
                    FileLog.warn("事件文件解析失败 {}：{}，尝试下一份", p.getFileName(), e.getMessage());
                }
            }
        }
    }

    /** 追加事件（自动赋 seq 与 eventId）；返回带 seq 的最终事件。 */
    public JSONObject append(JSONObject event) {
        synchronized (lock) {
            return appendLocked(event);
        }
    }

    /** 锁内追加（append/appendOnce 共用，保证幂等检查与写入原子）。 */
    private JSONObject appendLocked(JSONObject event) {
        JSONObject stored;
        {
            stored = event == null ? new JSONObject() : new JSONObject(event.toString());
            stored.put("seq", ++seq);
            if (!stored.has("eventId") || stored.optString("eventId", "").isEmpty()) {
                stored.put("eventId", "w" + seq);
            }
            events.add(stored);
            String k = stored.optString("alertKey", "");
            if (!k.isEmpty()) byAlertKey.put(k, stored);
            while (events.size() > CAP) {
                JSONObject old = events.remove(0);
                String ok = old.optString("alertKey", "");
                if (!ok.isEmpty()) byAlertKey.remove(ok);
            }
            saveLocked();
        }
        return stored;
    }

    /**
     * 幂等追加：带 {@code alertKey} 且已存在时直接返回 false（不重复入库）。
     * 观察点/推送两条通道共用同一事件库时，用它保证“同一条告警只出一条事件”。
     */
    public boolean appendOnce(JSONObject event) {
        if (event == null) return false;
        synchronized (lock) {
            String key = event.optString("alertKey", "");
            if (!key.isEmpty() && byAlertKey.containsKey(key)) return false;
            appendLocked(event);
            return true;
        }
    }

    /** cursor（上次最大 seq）之后的事件，最旧在前；超过 FETCH_LIMIT 分页取。 */
    public List<JSONObject> after(long cursor) {
        synchronized (lock) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject e : events) {
                if (e.optLong("seq", 0) <= cursor) continue;
                out.add(e);
                if (out.size() >= FETCH_LIMIT) break;
            }
            return out;
        }
    }

    /** 当前最大 seq（游标推进用）。 */
    public long latestSeq() {
        synchronized (lock) {
            return seq;
        }
    }

    /** 已存事件条数（测试/诊断）。 */
    public int size() {
        synchronized (lock) {
            return events.size();
        }
    }

    private void saveLocked() {
        try {
            JSONObject root = new JSONObject();
            root.put("seq", seq);
            JSONArray arr = new JSONArray();
            for (JSONObject e : events) arr.put(e);
            root.put("events", arr);
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
            FileLog.error("事件文件写入失败：{}", e.getMessage());
        }
    }
}
