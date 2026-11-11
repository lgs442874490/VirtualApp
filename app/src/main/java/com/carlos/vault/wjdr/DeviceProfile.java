package com.carlos.vault.wjdr;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 设备伪装（2026-11-11）：机型池 / 字段校验 / 确定性生成 / 指纹 JSON v1 兼容与 v2 输出。
 *
 * <p><b>平台中立</b>：纯 Java 8 + {@code org.json}，零 Android 依赖。App 与 vault-server
 * 编译同一份源码（vault-server/build.gradle 直接 include 本包），因此同一 seed 在两种模式下
 * 必然得到同一台"设备"。
 *
 * <p><b>确定性</b>：未注入固定种子时 {@code seed = fpid + "|" + serverid}，再 SHA-256；
 * 机型/ID/IP 全部由摘要派生，同一 {@code fpid + serverid} 重试结果完全一致（修复"登录失败
 * 重试就换设备"的漂移）。{@code fpid} 为空时回退 {@link SecureRandom}，绝不抛异常。
 *
 * <p><b>兼容</b>：读取 v1（无 {@code v} 或 {@code v != 2}）时保留既有
 * {@code device/device_id/distinct_id/ip}，缺失字段回填；v2 严格校验，非法字段单字段回退，
 * 不影响其它合法字段（需求 FR-3）。输出统一为 v2。
 */
public final class DeviceProfile {

    private static final int MIN_ANDROID_VERSION = 11;
    private static final int MAX_ANDROID_VERSION = 14;
    private static final int DEFAULT_ANDROID_VERSION = 13;
    private static final int MIN_DEVICE_LV = 1;
    private static final int MAX_DEVICE_LV = 5;
    private static final int DEFAULT_DEVICE_LV = 3;

    /** 机型池防御性兜底（池被误改空时使用；与 WjdrEngine 原兜底值一致）。 */
    private static final String DEFAULT_MODEL = "meizu MEIZU 18 Pro";
    private static final String DEFAULT_MANUFACTURER = "meizu";

    private static final int DEVICE_ID_LENGTH = 32;
    private static final int IP_MIN_HOST_OCTET = 1;
    private static final int IP_MAX_HOST_OCTET = 254;

    /** FR-1：{@code ^[A-Za-z][A-Za-z0-9 .+-]*$}，长度 3–40 另判。 */
    private static final Pattern MODEL_RULE = Pattern.compile("^[A-Za-z][A-Za-z0-9 .+-]*$");
    private static final Pattern HEX32_RULE = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern MANUFACTURER_RULE = Pattern.compile("^[A-Za-z0-9]{2,20}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** IP 前缀池（≥16）：保留原有 8 个，新增 8 个国内移动/联通/电信常见段。 */
    private static final String[] IP_PREFIXES = {
            "36.128", "39.144", "111.0", "112.17", "117.188", "120.204", "183.192", "223.104",
            "101.226", "106.121", "116.228", "121.40", "144.12", "180.163", "221.1", "58.246"};

    /**
     * 机型池（20 款 / 8 厂商）。约束：manufacturer == brand、model 首段与 manufacturer
     * 大小写不敏感一致、androidVersion 与发布年代自洽（HUAWEI 按需求口径上报 Android 12）。
     */
    private static final DeviceModel[] POOL = {
            // vivo（iQOO 为 vivo 子品牌，按真机上报格式带 vivo 前缀）
            new DeviceModel("vivo X200 Pro", "vivo", "vivo", 14),
            new DeviceModel("vivo X100 Pro", "vivo", "vivo", 14),
            new DeviceModel("vivo X100", "vivo", "vivo", 14),
            new DeviceModel("vivo S19", "vivo", "vivo", 14),
            new DeviceModel("vivo iQOO 12", "vivo", "vivo", 14),
            // Xiaomi（Redmi 为小米子品牌，按真机上报格式带 Xiaomi 前缀）
            new DeviceModel("Xiaomi 14", "Xiaomi", "Xiaomi", 14),
            new DeviceModel("Xiaomi 14 Pro", "Xiaomi", "Xiaomi", 14),
            new DeviceModel("Xiaomi Redmi K70", "Xiaomi", "Xiaomi", 14),
            new DeviceModel("Xiaomi Redmi Note 13 Pro", "Xiaomi", "Xiaomi", 13),
            // HUAWEI（HarmonyOS 侧上报 Android 12）
            new DeviceModel("HUAWEI Mate 60", "HUAWEI", "HUAWEI", 12),
            new DeviceModel("HUAWEI P60", "HUAWEI", "HUAWEI", 12),
            // HONOR
            new DeviceModel("HONOR Magic6", "HONOR", "HONOR", 14),
            new DeviceModel("HONOR 100 Pro", "HONOR", "HONOR", 13),
            // OPPO
            new DeviceModel("OPPO Find X7", "OPPO", "OPPO", 14),
            new DeviceModel("OPPO Reno11", "OPPO", "OPPO", 14),
            // OnePlus
            new DeviceModel("OnePlus 12", "OnePlus", "OnePlus", 14),
            // realme
            new DeviceModel("realme GT5 Pro", "realme", "realme", 14),
            new DeviceModel("realme GT Neo6", "realme", "realme", 13),
            // Meizu
            new DeviceModel("Meizu 21", "Meizu", "Meizu", 13),
            new DeviceModel("Meizu 20", "Meizu", "Meizu", 13)};

    private static final List<DeviceModel> POOL_VIEW =
            Collections.unmodifiableList(Arrays.asList(POOL));

    static {
        // 池数据自检（AC-1）：数据错误只记日志、不阻断类初始化（P2-3：避免一条脏数据导致
        // 整个登录链路 NoClassDefFoundError）；运行期由 normalize*/回退逻辑兜底。
        Set<String> seen = new HashSet<>();
        for (DeviceModel m : POOL) {
            String problem = poolProblem(m, seen);
            if (problem == null) {
                if (m != null) seen.add(m.model);
            } else {
                WjdrLog.w("[设备伪装] pool_invalid reason={} item={}", problem, String.valueOf(m));
            }
        }
    }

    /** 池数据自检项；返回 null 表示合法。 */
    private static String poolProblem(DeviceModel m, Set<String> seen) {
        if (m == null) return "null_entry";
        if (normalizeManufacturer(m.manufacturer) == null) return "manufacturer_invalid";
        if (!m.manufacturer.equals(m.brand)) return "brand_mismatch";
        String model = normalizeModel(m.model);
        if (model == null) return "model_invalid";
        if (!m.manufacturer.equalsIgnoreCase(firstSegment(model))) return "model_prefix_mismatch";
        if (m.androidVersion < MIN_ANDROID_VERSION
                || m.androidVersion > MAX_ANDROID_VERSION) return "android_version_out_of_range";
        if (seen.contains(m.model)) return "model_duplicate";
        return null;
    }

    /** 机型池中的一条记录（不可变）。 */
    public static final class DeviceModel {
        public final String model;
        public final String manufacturer;
        public final String brand;
        public final int androidVersion;

        public DeviceModel(String model, String manufacturer, String brand, int androidVersion) {
            this.model = model;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.androidVersion = androidVersion;
        }

        @Override
        public String toString() {
            return model + "/" + manufacturer + "/" + brand + "/" + androidVersion;
        }
    }

    /** 一台完整伪装设备（不可变）。 */
    public static final class Device {
        public final String model;
        public final String manufacturer;
        public final String brand;
        public final int androidVersion;
        public final int deviceLv;
        public final String deviceId;
        public final String distinctId;
        public final String ip;

        public Device(String model, String manufacturer, String brand, int androidVersion,
                      int deviceLv, String deviceId, String distinctId, String ip) {
            this.model = model;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.androidVersion = androidVersion;
            this.deviceLv = deviceLv;
            this.deviceId = deviceId;
            this.distinctId = distinctId;
            this.ip = ip;
        }

        @Override
        public String toString() {
            return model + "/" + manufacturer + "/" + brand + "/" + androidVersion + "/"
                    + deviceLv + "/" + deviceId + "/" + distinctId + "/" + ip;
        }
    }

    private DeviceProfile() {
    }

    // ===================== 解析 / 生成 / 序列化 =====================

    /**
     * 解析指纹 JSON（v1 兼容、v2 严格）。
     *
     * <p>null / 空串 / 非 JSON / 无任何已知字段（如 {@code "{}"}）返回 {@code null}，
     * 由上层走确定性生成；任一字段非法只回退该字段，不影响其它字段，绝不抛异常。
     *
     * @param json 指纹 JSON
     * @param fpid 账号 fpid（用于日志与确定性回退种子；为空时回退 SecureRandom）
     */
    public static Device parse(String json, String fpid) {
        if (json == null) return null;
        String text = json.trim();
        if (text.isEmpty()) return null;
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Throwable t) {
            return null;   // 非 JSON：返回空结果，不阻断登录
        }
        int version = o.optInt("v", 0);
        boolean hasKnownField = o.has("v") || o.has("device") || o.has("device_id")
                || o.has("distinct_id") || o.has("ip");
        if (!hasKnownField) return null;

        // 确定性基线：单字段非法时只回退该字段，保证同 fpid 回退结果稳定
        Device base = generate(fpid, 0L);

        String model = normalizeModel(text(o, "device"));
        if (model == null) {
            if (o.has("device")) warnFallback(fpid, "device", "invalid");
            model = base.model;
        }

        String manufacturer = null;
        if (version == 2) {
            manufacturer = normalizeManufacturer(text(o, "manufacturer"));
            String prefix = manufacturerFromModel(model);
            if (manufacturer == null) {
                if (o.has("manufacturer")) warnFallback(fpid, "manufacturer", "invalid");
            } else if (prefix != null && !manufacturer.equalsIgnoreCase(prefix)) {
                // P2-2：v2 存档中 manufacturer 与 device 首段冲突时以 device 为准，保证自洽
                warnFallback(fpid, "manufacturer", "conflicts_with_device");
                manufacturer = prefix;
            }
        }
        if (manufacturer == null) manufacturer = manufacturerFromModel(model);
        if (manufacturer == null) manufacturer = base.manufacturer;

        int androidVersion = androidVersionOfJson(o, fpid, model);
        int deviceLv = deviceLvOfJson(o, fpid);

        String deviceId = normalizeDeviceId(text(o, "device_id"));
        if (deviceId == null) {
            if (o.has("device_id")) warnFallback(fpid, "device_id", "invalid");
            deviceId = base.deviceId;
        }
        String distinctId = normalizeDeviceId(text(o, "distinct_id"));
        if (distinctId == null) {
            if (o.has("distinct_id")) warnFallback(fpid, "distinct_id", "invalid");
            distinctId = base.distinctId;
        }
        String ip = normalizeIp(text(o, "ip"));
        if (ip == null) {
            if (o.has("ip")) warnFallback(fpid, "ip", "invalid");
            ip = base.ip;
        }

        return new Device(model, manufacturer, manufacturer, androidVersion, deviceLv,
                deviceId, distinctId, ip);
    }

    /** 系统版本解析：缺失按池回填；存在必须是 Number 且 11–14（P2-6：跨 org.json 类型差异）。 */
    private static int androidVersionOfJson(JSONObject o, String fpid, String model) {
        Object raw = o.opt("android_version");
        if (raw == null || raw == JSONObject.NULL) return poolAndroidVersion(model);
        if (!(raw instanceof Number)) {
            warnFallback(fpid, "android_version", "type_mismatch");
            return poolAndroidVersion(model);   // P2-1′：回退机型池版本，保持机型/系统自洽
        }
        int v = ((Number) raw).intValue();
        if (v < MIN_ANDROID_VERSION || v > MAX_ANDROID_VERSION) {
            warnFallback(fpid, "android_version", "out_of_range");
            return poolAndroidVersion(model);   // P2-1′：越界同样回退池版本
        }
        return v;
    }

    /** 设备等级解析：缺失回填 3；存在必须是 1–5 的 Number。 */
    private static int deviceLvOfJson(JSONObject o, String fpid) {
        Object raw = o.opt("device_lv");
        if (raw == null || raw == JSONObject.NULL) return DEFAULT_DEVICE_LV;
        if (!(raw instanceof Number)) {
            warnFallback(fpid, "device_lv", "type_mismatch");
            return DEFAULT_DEVICE_LV;
        }
        int v = ((Number) raw).intValue();
        int clamped = clampDeviceLv(v);
        if (clamped != v) warnFallback(fpid, "device_lv", "out_of_range");
        return clamped;
    }

    /** 从机型推导厂商（引擎在机型已固化、厂商未固化时保持自洽）；推导失败返回 null。 */
    public static String manufacturerOf(String model) {
        return manufacturerFromModel(model);
    }

    /** 机型对应系统版本；不在池内返回 0（由调用方回退）。 */
    public static int androidVersionOf(String model) {
        if (model == null) return 0;
        for (DeviceModel m : POOL) {
            if (m.model.equalsIgnoreCase(model)) return m.androidVersion;
        }
        return 0;
    }

    /** 校验回退日志（FR-7）：含 fpid，不打印完整指纹。 */
    private static void warnFallback(String fpid, String field, String reason) {
        WjdrLog.w("[设备伪装] field={} reason={} fpid={}",
                field, reason, fpid == null ? "" : fpid);
    }

    /**
     * 生成一台设备：{@code fpid} 非空时按 {@code seed = fpid + "|" + serverId} 确定性派生；
     * 为空时回退 {@link SecureRandom}（不抛异常）。
     */
    public static Device generate(String fpid, long serverId) {
        return generate(fpid, serverId, 0L);
    }

    /**
     * 角色维度生成（P0 修复）：seed = {@code fpid|serverId|roleUid}（roleUid&gt;0 时）。
     *
     * <p>同账号同服多角色在无角色级指纹时上层注入空串，必须仍得到不同设备，否则游戏服
     * 按"同账号同设备"互踢。roleUid&lt;=0 保持账号级语义（旧行为）。
     */
    public static Device generate(String fpid, long serverId, long roleUid) {
        if (fpid == null || fpid.trim().isEmpty()) return randomDevice();
        String seed = roleUid > 0 ? fpid + "|" + serverId + "|" + roleUid : fpid + "|" + serverId;
        byte[] digest = sha256(seed);
        DeviceModel m = pickFromDigest(digest);
        return new Device(m.model, m.manufacturer, m.brand, m.androidVersion, DEFAULT_DEVICE_LV,
                hex(sha256("device|" + seed)).substring(0, DEVICE_ID_LENGTH),
                hex(sha256("distinct|" + seed)).substring(0, DEVICE_ID_LENGTH),
                pickIp(digest));
    }

    /**
     * 临时设备（发现式/无角色上下文登录用）：每次调用随机生成一台，供引擎按 Session 缓存。
     * 不参与确定性派生、不写入持久化（P1-1：同账号多 Session 发现登录不得共用设备）。
     */
    public static Device ephemeral() {
        return randomDevice();
    }

    /** 按 seed 确定性选择机型（池为空时返回默认项，不崩溃）。 */
    public static DeviceModel pick(String seed) {
        return pickFromDigest(sha256(seed));
    }

    /** 输出 v2 指纹 JSON；任何异常返回空串（由上层回退，不阻断登录）。 */
    public static String toJson(Device d) {
        if (d == null) return "";
        try {
            String model = normalizeModel(d.model);
            if (model == null) model = defaultModel().model;
            String manufacturer = normalizeManufacturer(d.manufacturer);
            if (manufacturer == null) manufacturer = manufacturerFromModel(model);
            if (manufacturer == null) manufacturer = defaultModel().manufacturer;
            String deviceId = normalizeDeviceId(d.deviceId);
            if (deviceId == null) deviceId = randomHex32();
            String distinctId = normalizeDeviceId(d.distinctId);
            if (distinctId == null) distinctId = randomHex32();
            String ip = normalizeIp(d.ip);
            if (ip == null) ip = randomIp();

            JSONObject o = new JSONObject();
            o.put("v", 2);
            o.put("device", model);
            o.put("manufacturer", manufacturer);
            o.put("brand", manufacturer);
            o.put("android_version", clampAndroidVersion(d.androidVersion));
            o.put("device_lv", clampDeviceLv(d.deviceLv));
            o.put("distinct_id", distinctId);
            o.put("device_id", deviceId);
            o.put("ip", ip);
            return o.toString();
        } catch (Throwable t) {
            WjdrLog.w("[设备伪装] field=identity_json reason=serialize_failed message={}",
                    String.valueOf(t.getMessage() == null ? t : t.getMessage()));
            return "";
        }
    }

    /** 机型池只读视图（测试用）。 */
    public static List<DeviceModel> pool() {
        return POOL_VIEW;
    }

    // ===================== 字段规范化 =====================

    /** {@code ^[0-9a-f]{32}$}：非法返回 null。 */
    public static String normalizeDeviceId(String v) {
        if (v == null) return null;
        String s = v.trim();
        return HEX32_RULE.matcher(s).matches() ? s : null;
    }

    /**
     * IPv4 四段、各段 0–255、无前导零，且第 3/4 段 ≥ 1（需求 FR-3）；非法返回 null。
     */
    public static String normalizeIp(String v) {
        if (v == null) return null;
        String s = v.trim();
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            if (p.length() > 1 && p.charAt(0) == '0') return null;
            int n = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') return null;
                n = n * 10 + (c - '0');
            }
            if (n < 0 || n > 255) return null;
            octets[i] = n;
        }
        if (octets[2] < IP_MIN_HOST_OCTET || octets[3] < IP_MIN_HOST_OCTET) return null;
        return s;
    }

    /** {@code ^[A-Za-z][A-Za-z0-9 .+-]*$} 且长度 3–40；非法返回 null。 */
    public static String normalizeModel(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.length() < 3 || s.length() > 40) return null;
        return MODEL_RULE.matcher(s).matches() ? s : null;
    }

    /** 厂商名：非空、2–20、仅字母数字；非法返回 null。 */
    public static String normalizeManufacturer(String v) {
        if (v == null) return null;
        String s = v.trim();
        return MANUFACTURER_RULE.matcher(s).matches() ? s : null;
    }

    // ===================== 内部工具 =====================

    /** 取字段文本：缺失/null/嵌套结构返回空串；Number/Boolean 转字符串（交给规范化判定）。 */
    private static String text(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return "";
    }

    private static DeviceModel defaultModel() {
        return POOL.length > 0
                ? POOL[0]
                : new DeviceModel(DEFAULT_MODEL, DEFAULT_MANUFACTURER, DEFAULT_MANUFACTURER,
                DEFAULT_ANDROID_VERSION);
    }

    private static DeviceModel pickFromDigest(byte[] digest) {
        if (POOL.length == 0) return defaultModel();
        return POOL[index(digest, 0) % POOL.length];
    }

    private static String pickIp(byte[] digest) {
        if (IP_PREFIXES.length == 0) return "36.128.1.1";
        String prefix = IP_PREFIXES[index(digest, 4) % IP_PREFIXES.length];
        int third = IP_MIN_HOST_OCTET + index(digest, 8) % (IP_MAX_HOST_OCTET + 1 - IP_MIN_HOST_OCTET);
        int fourth = IP_MIN_HOST_OCTET + index(digest, 12) % (IP_MAX_HOST_OCTET + 1 - IP_MIN_HOST_OCTET);
        return prefix + "." + third + "." + fourth;
    }

    /** 取摘要 4 字节拼成非负 int（用于池/段位取模）。 */
    private static int index(byte[] digest, int offset) {
        int n = digest.length;
        int b0 = digest[offset % n] & 0xff;
        int b1 = digest[(offset + 1) % n] & 0xff;
        int b2 = digest[(offset + 2) % n] & 0xff;
        int b3 = digest[(offset + 3) % n] & 0xff;
        return ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3) & 0x7fffffff;
    }

    private static Device randomDevice() {
        DeviceModel m = defaultModel();
        if (POOL.length > 0) m = POOL[RANDOM.nextInt(POOL.length)];
        return new Device(m.model, m.manufacturer, m.brand, m.androidVersion, DEFAULT_DEVICE_LV,
                randomHex32(), randomHex32(), randomIp());
    }

    private static String randomHex32() {
        byte[] bytes = new byte[DEVICE_ID_LENGTH / 2];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    private static String randomIp() {
        if (IP_PREFIXES.length == 0) return "36.128.1.1";
        String prefix = IP_PREFIXES[RANDOM.nextInt(IP_PREFIXES.length)];
        int third = IP_MIN_HOST_OCTET + RANDOM.nextInt(IP_MAX_HOST_OCTET);
        int fourth = IP_MIN_HOST_OCTET + RANDOM.nextInt(IP_MAX_HOST_OCTET);
        return prefix + "." + third + "." + fourth;
    }

    private static int clampAndroidVersion(int v) {
        return v >= MIN_ANDROID_VERSION && v <= MAX_ANDROID_VERSION ? v : DEFAULT_ANDROID_VERSION;
    }

    private static int clampDeviceLv(int v) {
        return v >= MIN_DEVICE_LV && v <= MAX_DEVICE_LV ? v : DEFAULT_DEVICE_LV;
    }

    /** 池内匹配机型返回其系统版本；不在池内按需求回退 13。 */
    private static int poolAndroidVersion(String model) {
        if (model != null) {
            for (DeviceModel m : POOL) {
                if (m.model.equalsIgnoreCase(model)) return m.androidVersion;
            }
        }
        return DEFAULT_ANDROID_VERSION;
    }

    /** 从机型首段推导厂商；推导失败返回 null（由调用方用池默认）。 */
    private static String manufacturerFromModel(String model) {
        String s = normalizeModel(model);
        if (s == null) return null;
        return normalizeManufacturer(firstSegment(s));
    }

    /** 取机型首段（空格 / 点 / 加号 / 减号分隔），保留原始大小写。 */
    private static String firstSegment(String model) {
        int end = 0;
        while (end < model.length()) {
            char c = model.charAt(end);
            if (c == ' ' || c == '.' || c == '+' || c == '-') break;
            end++;
        }
        return model.substring(0, end);
    }

    private static byte[] sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new byte[32];   // 理论不可达；保持确定性（全零摘要），不抛异常
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
