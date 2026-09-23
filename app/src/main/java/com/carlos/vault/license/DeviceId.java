package com.carlos.vault.license;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.util.UUID;

/**
 * 设备指纹。
 *
 * 口径（决策 D2）：
 * <ul>
 *   <li>首选 {@link Settings.Secure#ANDROID_ID}</li>
 *   <li>取不到（或命中已知无效值）时，回退为「首次运行生成并持久化的 UUID」</li>
 *   <li>不读取 {@link Build#SERIAL}（Android 10+ 已不可得，且属敏感标识）</li>
 *   <li>上传前与服务端下发的盐、包名一起做 SHA-256，<b>不上传任何明文设备标识</b></li>
 * </ul>
 */
public final class DeviceId {

    /**
     * 已知无效的 ANDROID_ID 值。
     * 9774d56d682e549c 是 Android 2.2 时代全机型相同的漏洞值；
     * 全 0 是部分 ROM 在异常情况下返回的占位值。命中即视为不可用。
     */
    private static final String[] BAD_ANDROID_IDS = {
            "9774d56d682e549c",
            "0000000000000000",
            "ffffffffffffffff"
    };

    private DeviceId() {
    }

    /** 原始设备标识（ANDROID_ID 或回退 UUID）。仅用于本地参与哈希，不直接上送。 */
    public static String raw(Context ctx) {
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
            // 部分定制 ROM 会抛异常，直接走回退分支
        }
        if (isUsable(androidId)) {
            return androidId.trim();
        }

        String uuid = null;
        try {
            uuid = LicenseStore.getDeviceUuid(ctx);
        } catch (Throwable ignored) {
        }
        if (uuid == null || uuid.trim().isEmpty()) {
            uuid = UUID.randomUUID().toString();
            try {
                LicenseStore.setDeviceUuid(ctx, uuid);
            } catch (Throwable ignored) {
            }
        }
        return uuid;
    }

    private static boolean isUsable(String id) {
        if (id == null) return false;
        String s = id.trim();
        if (s.isEmpty()) return false;
        for (String bad : BAD_ANDROID_IDS) {
            if (bad.equalsIgnoreCase(s)) return false;
        }
        return true;
    }

    /**
     * 加盐指纹：<code>SHA256(ANDROID_ID + "|" + 包名 + "|" + salt)</code>。
     *
     * 该值即协议中的 <code>deviceHash</code>。盐由服务端 hello 阶段下发，且自契约 v1.1
     * 起为<b>确定性盐</b>：<code>HMAC_SHA256(LICENSE_SECRET, "vp-fp-salt:v1")[:32]</code>。
     * 因此同一台设备无论何时、激活多少次，指纹都恒定不变——这是同机重激活（幂等）的前提。
     *
     * 说明：服务端无法自行复算该值（它拿不到 ANDROID_ID），故服务端只做存储与比对。
     */
    public static String withSalt(Context ctx, String salt) {
        String raw = raw(ctx);
        String s = salt == null ? "" : salt.trim();
        return LicenseCrypto.sha256Hex(raw + "|" + ctx.getPackageName() + "|" + s);
    }

    /**
     * 非敏感机型标识，仅供运营在 list 里辨认设备（如 "vivo V2141A"）。
     * 会剔除换行与控制字符，避免污染日志与 JSON。
     */
    public static String label() {
        String manufacturer = sanitize(Build.MANUFACTURER);
        String model = sanitize(Build.MODEL);
        String out = (manufacturer + " " + model).trim();
        return out.isEmpty() ? "unknown" : out;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        return sb.toString().trim();
    }
}
