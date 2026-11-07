package com.carlos.vaultserver.passport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次性设备指纹：每次注册/登录为独立新号生成，避免多号共用 guid 触发风控。
 *
 * <p>字段口径与游戏 login_profile 一致（原版部署快照 login_profile.json：
 * device/device_os/country_code/distinct_id/lan/version/package_tag/ad_source/device_id）。
 * {@link #random()} 用 {@link SecureRandom}；{@link #seeded(byte[])} 供单测生成确定性实例。
 */
public final class DeviceProfile {

    /** 与游戏客户端一致的机型口径（原版 login_profile 同值）。 */
    public static final String DEFAULT_DEVICE = "meizu MEIZU 18 Pro";

    public final String guid;
    public final String originGuid;
    public final String androidId;
    public final String distinctId;
    public final String device;
    public final String deviceOs;
    public final String countryCode;
    public final String lan;
    public final String version;
    public final String packageTag;
    public final String adSource;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DeviceProfile(String guid, String distinctId, String version) {
        this.guid = guid;
        this.originGuid = guid;
        this.androidId = guid;
        this.distinctId = distinctId;
        this.device = DEFAULT_DEVICE;
        this.deviceOs = "android";
        this.countryCode = "CN";
        this.lan = "zh_cn";
        this.version = version;
        this.packageTag = "App_android_8_funplus";
        this.adSource = "funplus";
    }

    /** 随机指纹（guid 三字段同值；distinctId 独立随机）。 */
    public static DeviceProfile random() {
        return new DeviceProfile(randomHex32(), randomHex32(), PassportConfig.DEFAULT_GAME_VERSION);
    }

    /** 确定性指纹（单测用）：guid/distinctId 由 seed 派生，同 seed 同结果。 */
    public static DeviceProfile seeded(byte[] seed) {
        return new DeviceProfile(sha256Hex(seed), sha256Hex((seed + ":distinct").getBytes(StandardCharsets.UTF_8)),
                PassportConfig.DEFAULT_GAME_VERSION);
    }

    /** login_profile 视图（account 字段由调用方按 fpid 填充）。 */
    public Map<String, String> toLoginProfile() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("account", "");
        m.put("device", device);
        m.put("device_os", deviceOs);
        m.put("country_code", countryCode);
        m.put("distinct_id", distinctId);
        m.put("lan", lan);
        m.put("version", version);
        m.put("package_tag", packageTag);
        m.put("ad_source", adSource);
        m.put("device_id", androidId);
        return m;
    }

    private static String randomHex32() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : buf) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(data)) {
                sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
            }
            return sb.toString();
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 unavailable", t);
        }
    }
}
