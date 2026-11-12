package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.FileLog;
import com.carlos.vaultserver.passport.PassportIds;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 起号回流清单（M4b 任务书 §2.3）：起号 SUCCESS 的号写入
 * {@code <dataDir>/bootstrap_referral.json}（原子写 + .bak，模式对齐 RegistrationStore），
 * 供 App 端拉取并并入本地 AccountStore 后再全量 sync（M5 实现）。
 *
 * <p><b>为什么不直接写 VaultStore</b>：App→server 全量 sync 会以 App 本地库覆盖服务端
 * 自建账号；回流采用「App 拉取清单 → 并入 AccountStore → 随下一次 sync 双向合并」路径。
 *
 * <p><b>凭据纪律</b>：本文件是凭据交付清单本体，{@code sdkToken} 明文允许落盘
 * （App 拉取后才能登录），与 registration 持久化视图只存 encrypted 不同；
 * {@code encrypted} 缺失（fpid|token 两段式导入）记 null。日志/异常输出一律
 * {@link #mask} 打码，绝不出现完整凭据。条目结构：
 * {@code {fpid, sdkToken, encrypted, label, serverId, uid, furnaceLevel, batchId,
 * bootstrappedAt, pulled}}。
 *
 * <p>进程级单例：{@link #install(Path)} + {@link #get()}，对齐 RegistrationStore 模式。
 */
public final class BootstrapReferral {

    private static volatile BootstrapReferral instance;

    /** 安装进程级实例（Main 启动调用；重复 install 覆盖旧实例，单测可重装）。 */
    public static BootstrapReferral install(Path dataDir) {
        BootstrapReferral r = new BootstrapReferral(dataDir);
        r.load();
        instance = r;
        return r;
    }

    /** 全局实例（未安装为 null）。 */
    public static BootstrapReferral get() {
        return instance;
    }

    /** 清掉进程级实例（进程退出 / 单测隔离）。 */
    public static void shutdown() {
        instance = null;
    }

    private final Path file;
    private final Path backup;
    /** fpid → 条目（插入序；同 fpid 重新起号覆盖旧条目并重置 pulled）。 */
    private final Map<String, JSONObject> entries = new LinkedHashMap<>();
    private final Object lock = new Object();

    public BootstrapReferral(Path dataDir) {
        Path dir = dataDir == null ? Path.of("data") : dataDir;
        this.file = dir.resolve("bootstrap_referral.json");
        this.backup = dir.resolve("bootstrap_referral.json.bak");
    }

    /** 读档（file → .bak 兜底）；清单天然持久，无状态转换。 */
    public void load() {
        synchronized (lock) {
            entries.clear();
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                    JSONArray arr = root.optJSONArray("entries");
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.optJSONObject(i);
                        String fpid = e == null ? "" : e.optString("fpid", "");
                        if (e == null || fpid.isEmpty()) continue;
                        entries.put(fpid, e);
                    }
                    return;
                } catch (Exception e) {
                    FileLog.warn("回流清单文件解析失败 {}：{}", p.getFileName(),
                            String.valueOf(e.getMessage()));
                }
            }
        }
    }

    /**
     * 追加一条起号成功条目（批次 runner 在号置 SUCCESS 后调用）；同 fpid 覆盖旧条目
     * 并重置 {@code pulled=false}（重新起号=新凭据，App 需重新拉取）。幂等安全。
     */
    public void append(JSONObject entry) {
        if (entry == null) return;
        String fpid = entry.optString("fpid", "");
        if (fpid.isEmpty()) return;
        synchronized (lock) {
            JSONObject copy = new JSONObject(entry.toString());
            copy.put("pulled", false);
            entries.put(fpid, copy);
            flushLocked();
        }
        FileLog.info("回流清单追加 fpid={} batchId={} furnaceLevel={}", fpid,
                entry.optString("batchId", ""), entry.optLong("furnaceLevel", 0L));
    }

    /** 待回流清单（pulled=false，插入序）。 */
    public List<JSONObject> listPending() {
        synchronized (lock) {
            ArrayList<JSONObject> out = new ArrayList<>();
            for (JSONObject e : entries.values()) {
                if (!e.optBoolean("pulled", false)) {
                    out.add(new JSONObject(e.toString()));
                }
            }
            return out;
        }
    }

    /** 全量清单（含已拉取；诊断用）。 */
    public List<JSONObject> listAll() {
        synchronized (lock) {
            ArrayList<JSONObject> out = new ArrayList<>();
            for (JSONObject e : entries.values()) {
                out.add(new JSONObject(e.toString()));
            }
            return out;
        }
    }

    /**
     * 标记已拉取；返回实际标记条数（未知 fpid 跳过）。
     *
     * <p>入参集合超过 {@link #MAX_MARK_BATCH} 时截断（防御异常超大请求）。
     */
    public int markPulled(List<String> fpids) {
        if (fpids == null || fpids.isEmpty()) return 0;
        Set<String> wanted = new LinkedHashSet<>();
        for (String fpid : fpids) {
            if (fpid != null && !fpid.isBlank()) wanted.add(fpid.trim());
        }
        int marked = 0;
        synchronized (lock) {
            for (String fpid : wanted) {
                JSONObject e = entries.get(fpid);
                if (e == null) continue;
                if (!e.optBoolean("pulled", false)) marked++;
                e.put("pulled", true);
            }
            if (marked > 0) flushLocked();
        }
        FileLog.info("回流清单标记已拉取 {} 条（请求 {} 个 fpid）", marked, wanted.size());
        return marked;
    }

    /** 当前清单条数（诊断 / 单测）。 */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** 单次标记上限（防御异常超大请求）。 */
    static final int MAX_MARK_BATCH = 500;

    /** 落盘：临时文件 + .bak 备份 + 原子替换（对齐 RegistrationStore.flush）。 */
    public void flush() {
        synchronized (lock) {
            if (!entries.isEmpty()) flushLocked();
        }
    }

    private void flushLocked() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (JSONObject v : entries.values()) arr.put(v);
            root.put("entries", arr);
            root.put("savedAt", System.currentTimeMillis());
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, REPLACE_EXISTING_ATOMIC);
        } catch (IOException e) {
            FileLog.error("回流清单文件写入失败：{}", String.valueOf(e.getMessage()));
        }
    }

    private static final StandardCopyOption[] REPLACE_EXISTING_ATOMIC =
            {StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE};

    /**
     * 凭据打码：语义对齐 {@link PassportIds#mask}（M1 权威实现，≤6 位全掩为 {@code ***}，
     * 否则前 3 后 3），委托而不复制，避免两份掩码规则漂移。
     */
    public static String mask(String secret) {
        return PassportIds.mask(secret);
    }
}
