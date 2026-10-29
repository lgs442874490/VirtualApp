package com.carlos.vaultserver;

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
     * Phase 2B（2026-10-29）：角色级键前缀白名单（删除角色时只清这些 family）。
     *
     * <p>只允许命中**带 uid 段**的角色键；旧（无 uid）键、账号级键（summary/lastrun/alert/digest…）、
     * 全局键（srv:cad:stats）一律不在白名单或无法命中角色段，绝不误删。
     */
    private static final String[] ROLE_KEY_PREFIXES = {
            "mod:", "log:", "st:", "res:", "todo:", "rallies:", "questprog:", "questdone:",
            "questts:", "act:", "boxes:", "summary:", "ledger:", "war:",
            "srv:cad:", "srv:cadlast:", "srv:lastrun:", "srv:supplyfire:", "srv:guardian:",
            "srv:alliance_science:", "srv:alliance_help:", "srv:asset:", "srv:formation:"
    };

    /**
     * 删除某角色（{@code fpid + sid + uid}）的全部**角色级**键，返回删除条数。
     *
     * <p>命中规则：键属于 {@link #ROLE_KEY_PREFIXES} 白名单 family，且包含完整角色段
     * {@code :<fpid>:<sid>:<uid>}（左右边界为 {@code :} 或键首尾）。兼容两种形状：
     * <ul>
     *   <li>{@code <family>:<fpid>:<sid>:<uid>[:<rest>]}（mod/log/res/ledger/srv:* …）；</li>
     *   <li>{@code st:<yyyy-MM-dd>:<fpid>:<sid>:<uid>[:<fn>]}（按日键，跨天全部清理）。</li>
     * </ul>
     *
     * <p><b>安全约束</b>：{@code fpid} 空/空白或 {@code uid<=0} 直接返回 0（0 = 旧/兼容路径，
     * 绝不删旧键、账号级键或整表）；只删本角色 uid 的键，兄弟角色/旧键/全局键保留。
     * 线程安全；有删除才标脏。
     */
    public int removeRoleKeys(String fpid, long sid, long uid) {
        if (fpid == null || fpid.trim().isEmpty() || uid <= 0L) return 0;
        String marker = fpid + ":" + sid + ":" + uid;
        int removed = 0;
        synchronized (lock) {
            List<String> targets = new ArrayList<>();
            for (String k : states.keySet()) {
                if (matchesRoleKey(k, marker)) targets.add(k);
            }
            for (String k : targets) {
                if (states.remove(k) != null) removed++;
            }
            if (removed > 0) dirtyAt = System.currentTimeMillis();
        }
        return removed;
    }

    /** 键是否为“白名单 family + 完整角色段 marker”形态（纯函数，供单测）。 */
    private static boolean matchesRoleKey(String key, String marker) {
        if (key == null || marker == null || marker.isEmpty()) return false;
        boolean family = false;
        for (String p : ROLE_KEY_PREFIXES) {
            if (key.startsWith(p)) {
                family = true;
                break;
            }
        }
        if (!family) return false;
        int idx = key.indexOf(marker);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || key.charAt(idx - 1) == ':';
            int end = idx + marker.length();
            boolean rightOk = end == key.length() || key.charAt(end) == ':';
            if (leftOk && rightOk) return true;
            idx = key.indexOf(marker, idx + 1);
        }
        return false;
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
