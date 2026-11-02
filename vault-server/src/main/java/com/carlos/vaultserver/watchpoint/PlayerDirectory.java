package com.carlos.vaultserver.watchpoint;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.SessionKeeper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家目录（2026-11-02）：游戏 UI 里用户只能看到「账号ID（fid）」和「联盟简称」，看不到角色 uid 与联盟 aid。
 * 本目录用整图扫描的城缓存 + {@code req_players_base_info} 批量补齐：
 * <ul>
 *   <li>uid → {fid, nick, abbr, aid, stoveLv, x, y}（角色 ↔ 账号 ↔ 联盟 ↔ 坐标）</li>
 *   <li>fid → uid（用户按账号ID配置监视目标/查坐标）</li>
 *   <li>abbr → aid（用户按联盟简称配置保护/监控联盟）</li>
 * </ul>
 *
 * <p>构建成本：城缓存约 4500 个 uid，按 50/次批量 ≈ 90 次请求（约 20–40s）。目录落地
 * {@code data/players_dir.json}，TTL 6 小时；整图扫描完成后如过期则后台重建，不阻塞扫描。
 */
public final class PlayerDirectory {

    /** 目录项。 */
    public static final class Entry {
        public long uid;
        public long aid;
        public long stoveLv;
        public String fid = "";
        public String nick = "";
        public String abbr = "";
        public int x;
        public int y;
    }

    private static final long TTL_MS = 6L * 3600_000L;
    private static final int BATCH = 50;
    private static final int MAX_UIDS = 6000;

    private static volatile PlayerDirectory SHARED;

    private final Path file;
    // 读写都只认这三个 volatile 快照：重建时先在锁外把新表建好，再整体换引用，
    // 读者永远不会看到「clear 之后、重灌之前」的空目录（旧实现会让并发 fid 查询误报不存在）。
    private volatile Map<Long, Entry> byUid = new java.util.HashMap<>();
    private volatile Map<String, Long> byFid = new java.util.HashMap<>();
    private volatile Map<String, Long> abbrToAid = new java.util.HashMap<>();
    private final AtomicBoolean building = new AtomicBoolean(false);
    private volatile long builtAt;

    private PlayerDirectory(Path file) {
        this.file = file;
    }

    public static void install(Path dataDir) {
        PlayerDirectory d = new PlayerDirectory(dataDir.resolve("players_dir.json"));
        d.load();
        SHARED = d;
    }

    public static PlayerDirectory shared() {
        return SHARED;
    }

    public Entry byFid(String fid) {
        if (fid == null || fid.trim().isEmpty()) return null;
        Long uid = byFid.get(fid.trim());
        return uid == null ? null : byUid.get(uid);
    }

    public Entry byUid(long uid) {
        return uid <= 0L ? null : byUid.get(uid);
    }

    public Long aidOfAbbr(String abbr) {
        return abbr == null ? null : abbrToAid.get(abbr.trim());
    }

    /** 记住「联盟简称 → aid」（来自官方联盟搜索协议），落库复用；写时复制，不阻塞重建线程。 */
    public void rememberAbbr(String abbr, long aid) {
        if (abbr == null || abbr.trim().isEmpty() || aid <= 0L) return;
        String k = abbr.trim();
        if (Long.valueOf(aid).equals(abbrToAid.get(k))) return;   // 已记过就别重复落盘
        synchronized (this) {
            Map<String, Long> next = new java.util.HashMap<>(abbrToAid);
            next.put(k, aid);
            abbrToAid = next;
            save();
        }
    }

    /** aid → 联盟简称（App 回填「友盟简称」用；多条目取第一个非空）。 */
    public String abbrOfAid(long aid) {
        if (aid <= 0L) return "";
        for (Entry e : byUid.values()) {
            if (e.aid == aid && e.abbr != null && !e.abbr.isEmpty()) return e.abbr;
        }
        return "";
    }

    public int size() {
        return byUid.size();
    }

    public long builtAt() {
        return builtAt;
    }

    public boolean isStale() {
        return builtAt <= 0L || System.currentTimeMillis() - builtAt > TTL_MS;
    }

    /** 后台刷新（正在建则跳过；stale 判定 + 互斥都在 rebuild 里，这里只负责起线程）。 */
    public void refreshAsync(SessionKeeper keeper, String fpid, long sid,
                             List<WatchPointEngine.CityInfo> cities) {
        if (keeper == null || cities == null || cities.isEmpty() || !isStale()) return;
        if (building.get()) return;
        Thread t = new Thread(() -> {
            try {
                rebuild(keeper, fpid, sid, cities);
            } catch (Throwable e) {
                FileLog.info("玩家目录构建失败：{}", String.valueOf(e));
            }
        }, "player-dir");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步重建（HTTP 查询首次调用也会用到）：按 50/批解析城缓存里的 uid，
     * 落库 uid/nick/abbr/aid/stoveLv 与坐标。返回条目数。
     */
    public int rebuild(SessionKeeper keeper, String fpid, long sid,
                       List<WatchPointEngine.CityInfo> cities) {
        if (keeper == null || cities == null || cities.isEmpty()) return size();
        if (!building.compareAndSet(false, true)) {
            FileLog.info("玩家目录已在重建中，跳过本次重复请求");
            return size();
        }
        try {
            int total = Math.min(cities.size(), MAX_UIDS);
            Map<Long, Entry> nextUid = new LinkedHashMap<>();
            Map<String, Long> nextFid = new java.util.HashMap<>();
            Map<String, Long> nextAbbr = new java.util.HashMap<>();
            // 网络 IO 全在锁外做：老实现持锁跑 90 次协议，期间 rememberAbbr/save 全被堵死
            for (int i = 0; i < total; i += BATCH) {
                int end = Math.min(total, i + BATCH);
                List<Long> uids = new ArrayList<>();
                for (int j = i; j < end; j++) {
                    WatchPointEngine.CityInfo c = cities.get(j);
                    if (c != null && c.uid > 0L) uids.add(c.uid);
                }
                if (uids.isEmpty()) continue;
                Map<Long, Map<String, Object>> infos = keeper.resolvePlayers(fpid, sid, uids);
                for (int j = i; j < end; j++) {
                    WatchPointEngine.CityInfo c = cities.get(j);
                    if (c == null) continue;
                    Map<String, Object> info = infos.get(c.uid);
                    if (info == null) continue;
                    Entry e = new Entry();
                    e.uid = c.uid;
                    e.nick = c.nick == null ? "" : c.nick;
                    e.x = c.x;
                    e.y = c.y;
                    long fidNum = num(info.get("fid"));   // JSON 数字是 double，必须转 long（否则 1.23861751E8）
                    e.fid = fidNum > 0L ? String.valueOf(fidNum) : "";
                    e.stoveLv = num(info.get("stove_lv"));
                    Map<String, Object> al = com.carlos.vaultserver.task.Exec.mapOf(info.get("alliance"));
                    if (al != null) {
                        e.aid = num(al.get("aid"));
                        e.abbr = text(al.get("abbr"));
                    }
                    nextUid.put(e.uid, e);
                    if (!e.fid.isEmpty() && !"0".equals(e.fid)) nextFid.put(e.fid, e.uid);
                    if (!e.abbr.isEmpty() && e.aid > 0L) nextAbbr.put(e.abbr, e.aid);
                }
            }
            if (nextUid.isEmpty()) {
                FileLog.info("玩家目录重建无结果（在线会话无应答？保留旧目录 {} 条）",
                        String.valueOf(size()));
                return size();
            }
            // 已有 rememberAbbr 记下的简称也合并进来（官方搜索拿到的 aid 不该被重建冲掉）
            for (Map.Entry<String, Long> e : abbrToAid.entrySet()) {
                nextAbbr.putIfAbsent(e.getKey(), e.getValue());
            }
            byUid = nextUid;
            byFid = nextFid;
            abbrToAid = nextAbbr;
            builtAt = System.currentTimeMillis();
            save();
            FileLog.info("玩家目录已更新：{} 条（fid {} / 联盟简称 {}），批次 {}",
                    String.valueOf(nextUid.size()), String.valueOf(nextFid.size()),
                    String.valueOf(nextAbbr.size()), String.valueOf((total + BATCH - 1) / BATCH));
            return nextUid.size();
        } finally {
            building.set(false);
        }
    }

    /** 全部条目（只读快照，供 API 输出）。 */
    public List<Entry> entries() {
        return new ArrayList<>(byUid.values());
    }

    // ---------------- 落库 ----------------

    private void load() {
        try {
            if (file == null || !Files.isRegularFile(file)) return;
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(raw);
            builtAt = o.optLong("builtAt", 0L);
            JSONArray arr = o.optJSONArray("players");
            if (arr == null) return;
            Map<Long, Entry> nextUid = new java.util.HashMap<>();
            Map<String, Long> nextFid = new java.util.HashMap<>();
            Map<String, Long> nextAbbr = new java.util.HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                Entry e = new Entry();
                e.uid = p.optLong("uid");
                e.aid = p.optLong("aid");
                e.stoveLv = p.optLong("stoveLv");
                e.fid = p.optString("fid", "");
                e.nick = p.optString("nick", "");
                e.abbr = p.optString("abbr", "");
                e.x = p.optInt("x");
                e.y = p.optInt("y");
                if (e.uid <= 0L) continue;
                nextUid.put(e.uid, e);
                if (!e.fid.isEmpty() && !"0".equals(e.fid)) nextFid.put(e.fid, e.uid);
                if (!e.abbr.isEmpty() && e.aid > 0L) nextAbbr.put(e.abbr, e.aid);
            }
            byUid = nextUid;
            byFid = nextFid;
            abbrToAid = nextAbbr;
            FileLog.info("玩家目录已载入：{} 条（fid {} / 联盟简称 {}）",
                    String.valueOf(nextUid.size()), String.valueOf(nextFid.size()),
                    String.valueOf(nextAbbr.size()));
        } catch (Throwable t) {
            FileLog.info("玩家目录载入失败：{}", String.valueOf(t));
        }
    }

    private synchronized void save() {
        try {
            if (file == null) return;
            JSONObject o = new JSONObject();
            o.put("builtAt", builtAt);
            JSONArray arr = new JSONArray();
            for (Entry e : byUid.values()) {   // volatile 读一次，拿到的是一致快照
                JSONObject p = new JSONObject();
                p.put("uid", e.uid);
                p.put("aid", e.aid);
                p.put("stoveLv", e.stoveLv);
                p.put("fid", e.fid);
                p.put("nick", e.nick);
                p.put("abbr", e.abbr);
                p.put("x", e.x);
                p.put("y", e.y);
                arr.put(p);
            }
            o.put("players", arr);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp."
                    + java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
                    .replaceAll("[^A-Za-z0-9]", "") + "." + System.nanoTime());
            Files.write(tmp, o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            FileLog.info("玩家目录落盘失败：{}", String.valueOf(t));
        }
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static long num(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }
}
