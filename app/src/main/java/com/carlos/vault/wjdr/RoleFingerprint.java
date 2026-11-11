package com.carlos.vault.wjdr;

import org.json.JSONObject;

/**
 * 角色级设备指纹派生（2026-11-08，对齐原版网页版
 * {@code ConfigJsonLoader.scopeFingerprintToRole}）。
 *
 * <p><b>为什么需要</b>：游戏服务器把「同账号 + 同设备」的重复登录判为互踢（code=46/5）。
 * 网页版为每个角色（accountRoleId）稳定派生独立的 {@code device_id}/{@code distinct_id}，
 * 因此同账号同服多角色可长期同时在线。
 *
 * <p><b>共用同一份实现</b>：App（容器登录）与 vault-server 都调用本类，
 * 避免两端各写一份导致规则漂移——同一角色在两种模式下必须得到同一台"设备"。
 *
 * <p>只改 {@code device_id}/{@code distinct_id}；{@code ip}/机型保持账号级（原版 scoping
 * 也只覆盖这两个字段）。非 JSON 输入原样返回，绝不阻断登录。
 */
public final class RoleFingerprint {

    /** 派生 device_id 的前缀（与原版一致：{@code sha256("device|base|identity")}）。 */
    private static final String KIND_DEVICE = "device";
    /** 派生 distinct_id 的前缀。 */
    private static final String KIND_DISTINCT = "distinct";

    private RoleFingerprint() {
    }

    /**
     * 派生某角色的指纹 JSON。
     *
     * @param baseJson 账号级/旧区服级指纹 JSON（可为空）
     * @param fpid     账号 fpid
     * @param sid      区服 id（字符串）
     * @param uid      角色 uid（字符串）
     * @return 派生后的 JSON；baseJson 为空/非 JSON 时原样返回
     */
    public static String scoped(String baseJson, String fpid, String sid, String uid) {
        if (baseJson == null) return "";
        if (baseJson.isEmpty()) return baseJson;
        try {
            JSONObject o = new JSONObject(baseJson);
            String identity = (fpid == null ? "" : fpid) + "|"
                    + (sid == null ? "" : sid) + "|" + (uid == null ? "" : uid);
            String device = o.optString("device_id", "");
            String deviceHash = device.isEmpty() ? "" : sha256Hex(KIND_DEVICE + "|" + device + "|" + identity);
            if (deviceHash.length() >= 32) o.put("device_id", deviceHash.substring(0, 32));
            String distinct = o.optString("distinct_id", "");
            String distinctHash = distinct.isEmpty() ? ""
                    : sha256Hex(KIND_DISTINCT + "|" + distinct + "|" + identity);
            if (distinctHash.length() >= 32) o.put("distinct_id", distinctHash.substring(0, 32));
            return o.toString();
        } catch (Throwable t) {
            return baseJson;   // 非 JSON/异常：退回原指纹，绝不阻断登录
        }
    }

    /** 稳定 SHA-256（小写十六进制；异常返回空串，由调用方跳过该字段）。 */
    public static String sha256Hex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((text == null ? "" : text)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
