package com.carlos.vaultserver.passport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次性设备指纹：每次注册/登录为独立新号生成，避免多号共用 guid 触发风控。
 *
 * <p><b>参数口径已按真实客户端实证校准（2026-11-10，游戏 1.16.12 dex 反编译 +
 * passport 实测）</b>：游戏 {@code RequestHelper.buildBaseParameters} 的四个关键参数为
 * <ul>
 *   <li>{@code guid} = 客户端持久化的 32 位 hex UUID（本类每号独立）</li>
 *   <li>{@code origin_guid} = <b>md5(guid)</b>（不是 guid 本身）</li>
 *   <li>{@code android_id} = <b>MAC 地址格式</b>（游戏把 getMacAddress 当 android_id 上传）</li>
 *   <li>{@code origin_address} = 同一 MAC 地址</li>
 * </ul>
 *
 * <p>字段口径同时与游戏 login_profile 一致（原版部署快照 login_profile.json：
 * device/device_os/country_code/distinct_id/lan/version/package_tag/ad_source/device_id）。
 * {@link #random()} 用 {@link SecureRandom}；{@link #seeded(byte[])} 供单测生成确定性实例。
 */
public final class DeviceProfile {

    /** 与游戏客户端一致的机型口径（原版 login_profile 同值）。 */
    public static final String DEFAULT_DEVICE = "meizu MEIZU 18 Pro";
    /** 默认占位 MAC（真实客户端 origin_address 同值；实测 passport 接受该值）。 */
    public static final String DEFAULT_MAC = "02:00:00:00:00:00";

    public final String guid;
    /** md5(guid)：游戏 origin_guid 的真实口径。 */
    public final String originGuid;
    /** MAC 地址：游戏 android_id 与 origin_address 共用此值。 */
    public final String macAddress;
    /** 上传用的 android_id（= {@link #macAddress}）。 */
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

    private DeviceProfile(String guid, String distinctId, String version, String mac) {
        this.guid = guid;
        this.originGuid = PassportIds.md5Hex(guid);
        this.macAddress = (mac == null || mac.trim().isEmpty()) ? DEFAULT_MAC : mac.trim();
        this.androidId = this.macAddress;
        this.distinctId = distinctId;
        this.device = DEFAULT_DEVICE;
        this.deviceOs = "android";
        this.countryCode = "CN";
        this.lan = "zh_cn";
        this.version = version;
        this.packageTag = "App_android_8_funplus";
        this.adSource = "funplus";
    }

    /** 随机指纹（guid/distinctId 独立随机；MAC 用默认占位值，实测 passport 接受）。 */
    public static DeviceProfile random() {
        return new DeviceProfile(randomHex32(), randomHex32(),
                PassportConfig.DEFAULT_GAME_VERSION, DEFAULT_MAC);
    }

    /** 随机指纹 + 随机合法 MAC（"02:xx:xx:xx:xx:xx"，多号互不关联时选用）。 */
    public static DeviceProfile randomWithUniqueMac() {
        byte[] buf = new byte[5];
        RANDOM.nextBytes(buf);
        StringBuilder mac = new StringBuilder("02");
        for (byte b : buf) {
            mac.append(':').append(String.format("%02x", b & 0xff));
        }
        return new DeviceProfile(randomHex32(), randomHex32(),
                PassportConfig.DEFAULT_GAME_VERSION, mac.toString());
    }

    /** 确定性指纹（单测用）：guid/distinctId 由 seed 派生，同 seed 同结果。 */
    public static DeviceProfile seeded(byte[] seed) {
        return new DeviceProfile(sha256Hex(seed), sha256Hex((seed + ":distinct").getBytes(StandardCharsets.UTF_8)),
                PassportConfig.DEFAULT_GAME_VERSION, DEFAULT_MAC);
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
