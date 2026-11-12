package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.passport.PassportIds;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 每角色独立设备指纹（对齐网页版 {@code ConfigJsonLoader.ensureAccountFingerprint}）。
 *
 * <p><b>为什么必须有</b>（2026-11-11 用户实测）：网页版可在同一区服 30 秒内连建两个号而不被拒；
 * 关键在于它的 device_id/distinct_id/login_ip 是**按角色确定性派生**的
 * （identity = role|accountRoleId|account），因此每次建号都是"另一台设备"。
 * 若多个号共用同一指纹，游戏侧会以设备风控拒绝建号（实测 loginauth code=25）。
 *
 * <p>算法（逐字对齐网页版）：
 * <pre>
 *   digest    = sha256(kind + "|" + identity + "|" + profileHash)
 *   device_id = digest[0..32)
 *   ip        = PREFIXES[hex(digest[0..2]) % 8] + "." + hex(digest[2..4]) + "." + (hex(digest[4..6]) % 254 + 1)
 * </pre>
 * {@code identity} 用 (fpid, 区服, 建号盐) 组合——与网页版 accountRoleId 等价地保证"一角色一设备"。
 */
public final class DeviceFingerprint {

    /** 登录 IP 前缀池（网页版 ConfigJsonLoader.RANDOM_LOGIN_IP_PREFIXES 原样）。 */
    static final List<String> LOGIN_IP_PREFIXES =
            List.of("36.128", "39.144", "111.0", "112.17", "117.188", "120.204", "183.192", "223.104");

    /** 机型池（网页版 ANDROID_14/15 前缀摘录，用于让 device 字段也随角色变化）。 */
    static final List<String> DEVICE_MODELS = List.of(
            "vivo x100", "vivo s19", "xiaomi 14", "redmi k70", "oneplus 12", "oppo find x7",
            "honor magic6", "iqoo 12", "samsung sm-s9210", "realme gt6", "meizu 18 pro");

    private DeviceFingerprint() {
    }

    /**
     * 为「某账号在某个区服新建成的一个角色」派生独立指纹。
     *
     * @param fpid     游戏账号
     * @param serverId 目标区服
     * @param salt     建号盐（建议用时间戳/随机串），同一 fpid+区服建第二个角色时与第一个不同
     * @return 可直接交给 {@code WjdrEngine.applyIdentity(json)} 的 JSON 字符串
     */
    public static String forNewRole(String fpid, long serverId, String salt) {
        String identity = "role|" + safe(fpid) + "|" + serverId + "|" + safe(salt);
        return derive(identity);
    }

    /** 为「已存在角色」派生指纹（同一角色稳定复用同一设备，避免每次登录都换设备）。 */
    public static String forRole(String fpid, long serverId, long uid) {
        String identity = "role|" + safe(fpid) + "|" + serverId + "|" + uid;
        return derive(identity);
    }

    private static String derive(String identity) {
        String profileHash = "v1";
        String deviceId = digest("device", identity, profileHash);
        String distinctId = digest("distinct", identity, profileHash);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("device_id", deviceId);
        out.put("distinct_id", distinctId);
        out.put("ip", deterministicLoginIp(identity, profileHash));
        int modelIndex = Integer.parseInt(digest("model", identity, profileHash).substring(0, 2), 16)
                % DEVICE_MODELS.size();
        out.put("device", DEVICE_MODELS.get(modelIndex));
        return new org.json.JSONObject(out).toString();
    }

    private static String digest(String kind, String identity, String profileHash) {
        String hex = PassportIds.sha256Hex(kind + "|" + identity + "|" + profileHash);
        return hex.length() >= 32 ? hex.substring(0, 32) : hex;
    }

    /** 确定性伪随机 IP（网页版 deterministicLoginIp 同算法）。 */
    static String deterministicLoginIp(String identity, String profileHash) {
        String hex = PassportIds.sha256Hex("ip|" + identity + "|" + profileHash);
        if (hex.length() < 6) return LOGIN_IP_PREFIXES.get(0) + ".1.1";
        int prefixIndex = Integer.parseInt(hex.substring(0, 2), 16) % LOGIN_IP_PREFIXES.size();
        int third = Integer.parseInt(hex.substring(2, 4), 16);
        int fourth = Integer.parseInt(hex.substring(4, 6), 16) % 254 + 1;
        return LOGIN_IP_PREFIXES.get(prefixIndex) + "." + third + "." + fourth;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** 供测试：解析指纹 JSON 成 Map。 */
    static Map<String, String> parse(String json) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        org.json.JSONObject o = new org.json.JSONObject(json);
        for (String k : o.keySet()) out.put(k, o.optString(k, ""));
        return out;
    }
}
