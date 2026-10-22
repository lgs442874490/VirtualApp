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

    /** 环形容量：最近 200 条。 */
    static final int CAP = 200;
    /** 单次拉取上限（防 App 端首拉全量过大；事件满 200 时分两页）。 */
    static final int FETCH_LIMIT = 100;

    private final Path file;
    private final Path backup;
    private final List<JSONObject> events = new ArrayList<>();
    private long seq;
    private final Object lock = new Object();

    public WatchEventStore(Path dataDir) {
        this.file = dataDir.resolve("events.json");
        this.backup = dataDir.resolve("events.json.bak");
    }

    public void load() {
        synchronized (lock) {
            events.clear();
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
                            if (e != null) events.add(e);
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
        JSONObject stored;
        synchronized (lock) {
            stored = event == null ? new JSONObject() : new JSONObject(event.toString());
            stored.put("seq", ++seq);
            if (!stored.has("eventId") || stored.optString("eventId", "").isEmpty()) {
                stored.put("eventId", "w" + seq);
            }
            events.add(stored);
            while (events.size() > CAP) events.remove(0);
            saveLocked();
        }
        return stored;
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
