package com.carlos.vaultserver.bootstrap;

import com.carlos.vaultserver.passport.PassportIds;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 设备指纹派生（对齐网页版 {@code ConfigJsonLoader.ensureAccountFingerprint}）。
 *
 * <p><b>2026-11-14 二次审查 P1-3 定论（当前契约）</b>：
 * <ul>
 *   <li>{@link #forRole} 按「一角色一设备」派生（登录既有角色时稳定复用同一设备）。</li>
 *   <li>{@link #forNewRole} 由调用方传入<b>每次尝试不同的盐</b>（起号使用时间戳，见
 *       {@code BootstrapService.createRole}），因此同一 {@code fpid+区服} 新建的每个角色
 *       都是「另一台设备」——与网页版 accountRoleId 语义一致。</li>
 *   <li>决策沿革：2026-11-13 曾为规避 {@code loginauth code=25} 改为固定盐 {@code "create"}；
 *       随后排查定论 code=25 = **账号封禁（2999 年）**，设备漂移假设被证伪。
 *       2026-11-14 二次审查（方案 b）**回退固定盐**，恢复「一角色一设备」，避免在用户书面接受前
 *       把「同账号同区多角色同设备」的未知风险带上生产。</li>
 *   <li>若未来确需「建号重试不漂移设备」，应作为独立需求评审，并登记已知限制后再改。</li>
 * </ul>
 *
 * <p>算法（逐字对齐网页版）：
 * <pre>
 *   digest    = sha256(kind + "|" + identity + "|" + profileHash)
 *   device_id = digest[0..32)
 *   ip        = PREFIXES[hex(digest[0..2]) % 8] + "." + hex(digest[2..4]) + "." + (hex(digest[4..6]) % 254 + 1)
 * </pre>
 * {@code identity} 用 (fpid, 区服, 建号盐/角色 uid) 组合，保证同一输入得到同一设备身份。
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
     * 为「某账号在某个区服新建成的一个角色」派生指纹。
     *
     * <p>「一角色一设备」由调用方用<b>不同的盐</b>保证（起号传时间戳）；
     * 同一 salt 输入会得到同一设备身份（纯函数，便于测试）。
     *
     * @param fpid     游戏账号
     * @param serverId 目标区服
     * @param salt     建号盐（起号传 {@code System.currentTimeMillis()}，保证每次新角色不同）
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
