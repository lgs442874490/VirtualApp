package com.carlos.vaultserver.registration;

import com.carlos.vaultserver.FileLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 账号存档：记录「游戏账号(fpid) ↔ 注册手机号 ↔ 接码平台 ↔ 区服角色」的对应关系。
 *
 * <p><b>为什么必须有</b>（2026-11-11 用户要求）：接码平台的号码是一次性的，
 * 一旦会话掉登录（token 失效/被顶号），只能用**原手机号**重新取验证码登录；
 * 不存档就等于账号永久失联。故注册成功即落库，建号成功后补写 serverId/uid。
 *
 * <p>持久化 {@code data/account_archive.json}（原子写 + .bak，语义对齐 RegistrationStore）；
 * <b>同时保存最新 sdkToken</b>（2026-11-11 用户要求）：token 实测有效期约 10 年
 * （session_expire_in=315360000s），可直接在其他设备登录；掉登录时用手机号重新接码即可刷新。
 * 手机号与 token 均为明文（就是账号凭证本体），日志一律掩码。
 */
public final class AccountArchive {

    private static final int MAX_ENTRIES = 2000;
    private static volatile AccountArchive INSTANCE;

    private final Path file;
    private final Path backup;
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final Object lock = new Object();
    private long dirtyAt;

    private AccountArchive(Path dataDir) {
        this.file = dataDir.resolve("account_archive.json");
        this.backup = dataDir.resolve("account_archive.json.bak");
    }

    /** 安装单例（Main 启动时调用）。 */
    public static synchronized AccountArchive install(Path dataDir) {
        AccountArchive a = new AccountArchive(dataDir);
        a.load();
        INSTANCE = a;
        return a;
    }

    /** 当前单例；未安装时返回 null（单测可自行构造）。 */
    public static AccountArchive get() {
        return INSTANCE;
    }

    /** 测试用构造。 */
    static AccountArchive create(Path dataDir) {
        AccountArchive a = new AccountArchive(dataDir);
        a.load();
        return a;
    }

    /**
     * 记录一次注册成功（幂等：同 fpid 覆盖更新，保留首次注册手机号作为找回凭证）。
     *
     * @param fpid  游戏账号
     * @param phone 注册用手机号（游客注册为空）
     * @param provider 接码平台（d1 / haozhuma；游客为空）
     */
    public void recordRegistration(String fpid, String phone, String provider, String sdkToken) {
        if (isBlank(fpid)) return;
        synchronized (lock) {
            Map<String, Object> e = findLocked(fpid);
            if (e == null) {
                e = new LinkedHashMap<>();
                e.put("fpid", fpid.trim());
                e.put("phone", phone == null ? "" : phone.trim());
                e.put("provider", provider == null ? "" : provider.trim());
                e.put("registeredAt", System.currentTimeMillis());
                e.put("sdkToken", sdkToken == null ? "" : sdkToken.trim());
                entries.add(e);
            } else {
                if (e.get("phone") == null || String.valueOf(e.get("phone")).isBlank()) {
                    e.put("phone", phone == null ? "" : phone.trim());
                    e.put("provider", provider == null ? "" : provider.trim());
                }
                // token 会随重新登录变化：每次成功注册/登录都刷新为最新值，方便换设备直接使用
                if (sdkToken != null && !sdkToken.trim().isEmpty()) {
                    e.put("sdkToken", sdkToken.trim());
                    e.put("tokenUpdatedAt", System.currentTimeMillis());
                }
            }
            trimLocked();
            dirtyAt = System.currentTimeMillis();
            saveLocked();
        }
        FileLog.info("账号存档：注册 fpid={} phone={} provider={} token={}",
                fpid, mask(phone), provider, mask(sdkToken));
    }

    /** 建号/登录成功后补写区服与角色（同一 fpid 可有多区角色，按 serverId 去重追加）。 */
    public void recordRole(String fpid, long serverId, long uid) {
        if (isBlank(fpid) || serverId <= 0L) return;
        synchronized (lock) {
            Map<String, Object> e = findLocked(fpid);
            if (e == null) {
                e = new LinkedHashMap<>();
                e.put("fpid", fpid.trim());
                e.put("phone", "");
                e.put("provider", "");
                e.put("registeredAt", 0L);
                entries.add(e);
            }
            Object raw = e.get("roles");
            JSONArray roles = raw instanceof JSONArray ? (JSONArray) raw : new JSONArray();
            boolean exists = false;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject o = roles.optJSONObject(i);
                if (o != null && o.optLong("serverId") == serverId) {
                    o.put("uid", uid);
                    o.put("updatedAt", System.currentTimeMillis());
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                JSONObject o = new JSONObject();
                o.put("serverId", serverId);
                o.put("uid", uid);
                o.put("createdAt", System.currentTimeMillis());
                roles.put(o);
            }
            e.put("roles", roles);
            dirtyAt = System.currentTimeMillis();
            saveLocked();
        }
        FileLog.info("账号存档：建号 fpid={} server={} uid={}", fpid, String.valueOf(serverId), String.valueOf(uid));
    }

    /** 更新某 fpid 的 token（重新登录成功后调用）。 */
    public void updateToken(String fpid, String sdkToken) {
        if (isBlank(fpid) || isBlank(sdkToken)) return;
        synchronized (lock) {
            Map<String, Object> e = findLocked(fpid);
            if (e == null) return;
            e.put("sdkToken", sdkToken.trim());
            e.put("tokenUpdatedAt", System.currentTimeMillis());
            saveLocked();
        }
    }

    /** 按 fpid 查注册手机号与 token（换设备登录/掉登录重取码用）；未知返回 null。 */
    public Map<String, Object> findByFpid(String fpid) {
        if (isBlank(fpid)) return null;
        synchronized (lock) {
            Map<String, Object> e = findLocked(fpid);
            return e == null ? null : new LinkedHashMap<>(e);
        }
    }

    /** 全部条目（只读快照）。 */
    public List<Map<String, Object>> list() {
        synchronized (lock) {
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : entries) out.add(new LinkedHashMap<>(e));
            return out;
        }
    }

    // ---------------- 内部 ----------------

    private Map<String, Object> findLocked(String fpid) {
        String key = fpid.trim();
        for (Map<String, Object> e : entries) {
            if (key.equals(String.valueOf(e.get("fpid")))) return e;
        }
        return null;
    }

    /** 只保留最近 MAX_ENTRIES 条（按 registeredAt 升序丢最旧）。 */
    private void trimLocked() {
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    private void load() {
        synchronized (lock) {
            entries.clear();
            for (Path p : new Path[]{file, backup}) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    JSONObject root = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                    JSONArray arr = root.optJSONArray("accounts");
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Map<String, Object> e = new LinkedHashMap<>();
                        for (String k : o.keySet()) e.put(k, o.get(k));
                        if (!isBlank(String.valueOf(e.get("fpid")))) entries.add(e);
                    }
                    return;
                } catch (Exception ex) {
                    FileLog.warn("账号存档解析失败 {}：{}", p.getFileName(), ex.getMessage());
                }
            }
        }
    }

    private void saveLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (Map<String, Object> e : entries) arr.put(new JSONObject(e));
            JSONObject root = new JSONObject();
            root.put("accounts", arr);
            root.put("savedAt", System.currentTimeMillis());
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            dirtyAt = 0L;
        } catch (Exception e) {
            FileLog.warn("账号存档写入失败：{}", String.valueOf(e.getMessage()));
        }
    }

    /** 手机号掩码（日志用）：前3后4，≤7 位全掩。 */
    static String mask(String phone) {
        if (phone == null) return "";
        String v = phone.trim();
        if (v.isEmpty()) return "";
        if (v.length() <= 7) return "***";
        return v.substring(0, 3) + "****" + v.substring(v.length() - 4);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
