package com.carlos.vault.wjdr;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 设备伪装增强（2026-11-11）核心单测：机型池一致性、确定性生成、字段校验回退、
 * v1 兼容回填、v2 幂等。
 *
 * <p>对应需求 AC-1 ~ AC-8（docs/requirements/设备伪装增强-需求文档-20261111.md）。
 */
public class DeviceProfileTest {

    private static final Pattern MODEL_RULE = Pattern.compile("^[A-Za-z][A-Za-z0-9 .+-]*$");
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");

    // ===================== AC-1 机型池 =====================

    @Test
    public void pool_isConsistent() throws Exception {
        List<DeviceProfile.DeviceModel> pool = DeviceProfile.pool();
        assertTrue("机型池规模应 ≥ 20", pool.size() >= 20);

        Set<String> models = new HashSet<>();
        Set<String> manufacturers = new HashSet<>();
        for (DeviceProfile.DeviceModel m : pool) {
            assertNotNull(m);
            // manufacturer/brand：非空、2–20、仅字母数字、brand == manufacturer
            assertTrue("manufacturer 非法: " + m, isAlphaNum2to20(m.manufacturer));
            assertTrue("brand 非法: " + m, isAlphaNum2to20(m.brand));
            assertEquals("brand 必须等于 manufacturer: " + m, m.manufacturer, m.brand);
            // model：3–40、首字母、字符集、首段与 manufacturer 大小写不敏感一致
            assertTrue("model 长度非法: " + m,
                    m.model != null && m.model.length() >= 3 && m.model.length() <= 40);
            assertTrue("model 字符集非法: " + m, MODEL_RULE.matcher(m.model).matches());
            assertEquals("model 首段应与 manufacturer 一致: " + m,
                    m.manufacturer.toLowerCase(Locale.ROOT), firstSegment(m.model));
            // androidVersion ∈ [11,14]
            assertTrue("androidVersion 越界: " + m,
                    m.androidVersion >= 11 && m.androidVersion <= 14);
            // model 唯一
            assertTrue("model 重复: " + m.model, models.add(m.model));
            manufacturers.add(m.manufacturer.toLowerCase(Locale.ROOT));
        }
        // 覆盖 8 厂商
        for (String want : new String[]{"vivo", "xiaomi", "huawei", "honor", "oppo",
                "oneplus", "realme", "meizu"}) {
            assertTrue("缺少厂商 " + want + "，实际=" + manufacturers, manufacturers.contains(want));
        }
    }

    // ===================== AC-2 / AC-3 确定性 =====================

    @Test
    public void generate_isDeterministicForSameSeed() throws Exception {
        DeviceProfile.Device first = DeviceProfile.generate("fpid-abc", 888L);
        for (int i = 0; i < 10; i++) {
            DeviceProfile.Device again = DeviceProfile.generate("fpid-abc", 888L);
            assertEquals(first.model, again.model);
            assertEquals(first.manufacturer, again.manufacturer);
            assertEquals(first.brand, again.brand);
            assertEquals(first.androidVersion, again.androidVersion);
            assertEquals(first.deviceLv, again.deviceLv);
            assertEquals(first.deviceId, again.deviceId);
            assertEquals(first.distinctId, again.distinctId);
            assertEquals(first.ip, again.ip);
        }
    }

    @Test
    public void generate_differsForDifferentSeed() throws Exception {
        DeviceProfile.Device a = DeviceProfile.generate("fpid-a", 888L);
        DeviceProfile.Device b = DeviceProfile.generate("fpid-b", 888L);
        DeviceProfile.Device c = DeviceProfile.generate("fpid-a", 889L);
        assertTrue("不同 fpid 的 device_id 不应相同", !a.deviceId.equals(b.deviceId));
        assertTrue("不同 fpid 的 distinct_id 不应相同", !a.distinctId.equals(b.distinctId));
        assertTrue("不同 serverid 的 device_id 不应相同", !a.deviceId.equals(c.deviceId));
        // 机型最多 20 款，不要求必不同；但整体指纹（ID）必须不同
    }

    @Test
    public void generate_emptyFpidFallsBackWithoutException() throws Exception {
        DeviceProfile.Device d = DeviceProfile.generate("", 0L);
        assertNotNull(d);
        assertTrue("空 fpid 也要生成合法 device_id", HEX32.matcher(d.deviceId).matches());
        assertTrue("空 fpid 也要生成合法 distinct_id", HEX32.matcher(d.distinctId).matches());
        assertNotNull(DeviceProfile.normalizeModel(d.model));
        assertNotNull(DeviceProfile.normalizeIp(d.ip));
        // null fpid 同样不得抛异常
        assertNotNull(DeviceProfile.generate(null, 0L));
    }

    @Test
    public void pick_isDeterministicAndInPool() throws Exception {
        DeviceProfile.DeviceModel a = DeviceProfile.pick("seed-1");
        DeviceProfile.DeviceModel b = DeviceProfile.pick("seed-1");
        assertNotNull(a);
        assertEquals(a.model, b.model);
        boolean inPool = false;
        for (DeviceProfile.DeviceModel m : DeviceProfile.pool()) {
            if (m.model.equals(a.model)) inPool = true;
        }
        assertTrue("pick 结果必须来自机型池: " + a.model, inPool);
    }

    // ===================== AC-4 / AC-5 校验回退 =====================

    @Test
    public void parse_invalidDeviceIdFallsBack() throws Exception {
        String json = new JSONObject()
                .put("device", "vivo X200 Pro")
                .put("device_id", "abc1234567")                       // 长度不符
                .put("distinct_id", "0123456789abcdef0123456789abcdef")
                .put("ip", "36.128.12.34")
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertTrue("非法 device_id 应回退为 32 位小写 hex: " + d.deviceId,
                HEX32.matcher(d.deviceId).matches());
        assertEquals("合法 distinct_id 应保留", "0123456789abcdef0123456789abcdef", d.distinctId);
        assertEquals("合法 ip 应保留", "36.128.12.34", d.ip);
        assertEquals("合法 device 应保留", "vivo X200 Pro", d.model);
    }

    @Test
    public void parse_invalidIpFallsBack() throws Exception {
        String json = new JSONObject()
                .put("device", "vivo X200 Pro")
                .put("device_id", "0123456789abcdef0123456789abcdef")
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "999.1.1.1")
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertNotNull("非法 ip 应回退为合法 IPv4", DeviceProfile.normalizeIp(d.ip));
        assertEquals("合法 device_id 应保留", "0123456789abcdef0123456789abcdef", d.deviceId);
        assertEquals("合法 distinct_id 应保留", "fedcba9876543210fedcba9876543210", d.distinctId);
    }

    @Test
    public void parse_fieldTypeErrorsFallbackIndependently() throws Exception {
        String json = new JSONObject()
                .put("device", 12345)                 // 数字开头 -> 非法
                .put("device_id", 123)                // 数字类型 + 长度不符 -> 非法
                .put("ip", true)                      // 布尔 -> 非法
                .put("distinct_id", "0123456789abcdef0123456789abcdef")  // 合法，应保留
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertNotNull("device 应回退为池内合法机型", DeviceProfile.normalizeModel(d.model));
        assertTrue(HEX32.matcher(d.deviceId).matches());
        assertNotNull(DeviceProfile.normalizeIp(d.ip));
        assertEquals("其余合法字段不受影响", "0123456789abcdef0123456789abcdef", d.distinctId);
    }

    // ===================== AC-6 v1 兼容回填 =====================

    @Test
    public void parse_v1BackfillsAndKeepsIdentity() throws Exception {
        String json = new JSONObject()
                .put("device", "Honor Magic6")         // 旧池机型（非新池原样值）
                .put("device_id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .put("distinct_id", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .put("ip", "223.104.1.2")
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertEquals("v1 device 原值保留", "Honor Magic6", d.model);
        assertEquals("v1 device_id 原值保留", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", d.deviceId);
        assertEquals("v1 distinct_id 原值保留", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", d.distinctId);
        assertEquals("v1 ip 原值保留", "223.104.1.2", d.ip);
        assertEquals("manufacturer 按 device 首段回填", "Honor", d.manufacturer);
        assertEquals("brand 与 manufacturer 一致", "Honor", d.brand);
        assertTrue("android_version 回填到合法区间: " + d.androidVersion,
                d.androidVersion >= 11 && d.androidVersion <= 14);
        assertEquals("device_lv 缺省回填 3", 3, d.deviceLv);

        JSONObject out = new JSONObject(DeviceProfile.toJson(d));
        assertEquals("输出应升级为 v2", 2, out.optInt("v", 0));
        assertEquals("Honor Magic6", out.optString("device"));
        assertEquals("Honor", out.optString("manufacturer"));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", out.optString("device_id"));
    }

    @Test
    public void parse_v1DeviceWinsOverConflictingManufacturer() throws Exception {
        String json = new JSONObject()
                .put("device", "HUAWEI Mate 60")
                .put("manufacturer", "vivo")
                .put("brand", "vivo")
                .put("device_id", "0123456789abcdef0123456789abcdef")
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "36.128.12.34")
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertEquals("以 device 为准回填 manufacturer", "HUAWEI", d.manufacturer);
        assertEquals("以 device 为准回填 brand", "HUAWEI", d.brand);
    }

    // ===================== AC-7 v2 幂等 =====================

    @Test
    public void v2RoundTripIsIdempotent() throws Exception {
        DeviceProfile.Device original = DeviceProfile.generate("fpid-round", 777L);
        String json = DeviceProfile.toJson(original);
        DeviceProfile.Device parsed = DeviceProfile.parse(json, "fpid-round");
        assertNotNull(parsed);
        assertEquals(original.model, parsed.model);
        assertEquals(original.manufacturer, parsed.manufacturer);
        assertEquals(original.brand, parsed.brand);
        assertEquals(original.androidVersion, parsed.androidVersion);
        assertEquals(original.deviceLv, parsed.deviceLv);
        assertEquals(original.deviceId, parsed.deviceId);
        assertEquals(original.distinctId, parsed.distinctId);
        assertEquals(original.ip, parsed.ip);

        JSONObject out = new JSONObject(DeviceProfile.toJson(parsed));
        JSONObject in = new JSONObject(json);
        for (String key : new String[]{"v", "device", "manufacturer", "brand",
                "android_version", "device_lv", "device_id", "distinct_id", "ip"}) {
            assertEquals("v2 字段应幂等: " + key, in.get(key).toString(), out.get(key).toString());
        }
    }

    @Test
    public void v2StrictValidationRepairsIllegalFields() throws Exception {
        String json = new JSONObject()
                .put("v", 2)
                .put("device", "vivo X200 Pro")
                .put("manufacturer", "vivo")
                .put("brand", "vivo")
                .put("android_version", 99)                 // 越界 -> 回退
                .put("device_lv", 9)                        // 越界 -> 3
                .put("device_id", "XYZ")                    // 非法 -> 重生成
                .put("distinct_id", "0123456789abcdef0123456789abcdef")
                .put("ip", "1.2.0.4")                       // c=0 -> 回退
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertTrue("android_version 越界应回退 11–14: " + d.androidVersion,
                d.androidVersion >= 11 && d.androidVersion <= 14);
        assertEquals("device_lv 越界应回退 3", 3, d.deviceLv);
        assertTrue(HEX32.matcher(d.deviceId).matches());
        assertNotNull(DeviceProfile.normalizeIp(d.ip));
        assertEquals("合法字段保留", "0123456789abcdef0123456789abcdef", d.distinctId);
    }

    // ===================== AC-8 空/非法输入 =====================

    @Test
    public void parse_nullOrGarbageReturnsNull() throws Exception {
        assertNull(DeviceProfile.parse(null, "fpid-x"));
        assertNull(DeviceProfile.parse("", "fpid-x"));
        assertNull(DeviceProfile.parse("   ", "fpid-x"));
        assertNull(DeviceProfile.parse("not-a-json", "fpid-x"));
        assertNull(DeviceProfile.parse("[1,2,3]", "fpid-x"));
        assertNull("空对象视为无指纹，由上层确定性生成", DeviceProfile.parse("{}", "fpid-x"));
    }

    // ===================== 字段规范化（边界） =====================

    @Test
    public void normalizeModelRejectsIllegal() throws Exception {
        assertNull(DeviceProfile.normalizeModel(null));
        assertNull(DeviceProfile.normalizeModel(""));
        assertNull(DeviceProfile.normalizeModel("ab"));                 // 长度不足
        assertNull(DeviceProfile.normalizeModel("1abc"));               // 数字开头
        assertNull(DeviceProfile.normalizeModel("vivo X200 Pro!"));     // 非法字符
        StringBuilder longModel = new StringBuilder();
        for (int i = 0; i < 41; i++) longModel.append('a');             // 超长
        assertNull(DeviceProfile.normalizeModel(longModel.toString()));
        assertEquals("vivo X200 Pro", DeviceProfile.normalizeModel("  vivo X200 Pro  "));
        assertEquals("HUAWEI Mate 60", DeviceProfile.normalizeModel("HUAWEI Mate 60"));
    }

    @Test
    public void normalizeDeviceIdAcceptsOnlyLowerHex32() throws Exception {
        assertEquals("0123456789abcdef0123456789abcdef",
                DeviceProfile.normalizeDeviceId("0123456789abcdef0123456789abcdef"));
        assertNull(DeviceProfile.normalizeDeviceId(null));
        assertNull(DeviceProfile.normalizeDeviceId(""));
        assertNull(DeviceProfile.normalizeDeviceId("0123456789ABCDEF0123456789ABCDEF"));
        assertNull(DeviceProfile.normalizeDeviceId("g123456789abcdef0123456789abcdef"));
        assertNull(DeviceProfile.normalizeDeviceId("0123456789abcdef"));
    }

    @Test
    public void normalizeIpAcceptsOnlyValidIpv4WithNonZeroTail() throws Exception {
        assertEquals("36.128.12.34", DeviceProfile.normalizeIp("36.128.12.34"));
        assertEquals("111.0.1.2", DeviceProfile.normalizeIp(" 111.0.1.2 "));
        assertNull(DeviceProfile.normalizeIp(null));
        assertNull(DeviceProfile.normalizeIp("1.2.3"));
        assertNull(DeviceProfile.normalizeIp("1.2.3.4.5"));
        assertNull(DeviceProfile.normalizeIp("999.1.1.1"));
        assertNull(DeviceProfile.normalizeIp("1.2.0.4"));
        assertNull(DeviceProfile.normalizeIp("1.2.3.0"));
        assertNull(DeviceProfile.normalizeIp("a.b.c.d"));
        assertNull(DeviceProfile.normalizeIp("01.2.3.4"));
    }

    @Test
    public void toJson_v2Shape() throws Exception {
        DeviceProfile.Device d = DeviceProfile.generate("fpid-json", 1L);
        JSONObject o = new JSONObject(DeviceProfile.toJson(d));
        assertEquals(2, o.getInt("v"));
        assertEquals(d.model, o.getString("device"));
        assertEquals(d.manufacturer, o.getString("manufacturer"));
        assertEquals(d.brand, o.getString("brand"));
        assertEquals(d.androidVersion, o.getInt("android_version"));
        assertEquals(d.deviceLv, o.getInt("device_lv"));
        assertEquals(d.deviceId, o.getString("device_id"));
        assertEquals(d.distinctId, o.getString("distinct_id"));
        assertEquals(d.ip, o.getString("ip"));
        assertTrue("device_id 必须是 32 位小写 hex", HEX32.matcher(o.getString("device_id")).matches());
        assertTrue("distinct_id 必须是 32 位小写 hex", HEX32.matcher(o.getString("distinct_id")).matches());
    }

    @Test
    public void normalizeDeviceId_coversAllPoolModels() throws Exception {
        List<String> bad = new ArrayList<>();
        for (DeviceProfile.DeviceModel m : DeviceProfile.pool()) {
            if (DeviceProfile.normalizeModel(m.model) == null) bad.add(m.model);
        }
        assertTrue("池内机型必须全部可通过 normalizeModel: " + bad, bad.isEmpty());
    }

    private static boolean isAlphaNum2to20(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 2 || n > 20) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }

    private static String firstSegment(String model) {
        int end = 0;
        while (end < model.length()) {
            char c = model.charAt(end);
            if (c == ' ' || c == '.' || c == '+' || c == '-') break;
            end++;
        }
        return model.substring(0, end).toLowerCase(Locale.ROOT);
    }

    // ===================== P0 修复：角色维度隔离 =====================

    @Test
    public void generate_differsForDifferentRoleUid() throws Exception {
        DeviceProfile.Device a = DeviceProfile.generate("fpid-role", 888L, 1001L);
        DeviceProfile.Device b = DeviceProfile.generate("fpid-role", 888L, 1002L);
        assertNotEquals("同账号同服不同角色必须不同设备 device_id",
                a.deviceId, b.deviceId);
        assertNotEquals("同账号同服不同角色必须不同 distinct_id",
                a.distinctId, b.distinctId);
        assertNotEquals("角色级与账号级(uid=0)也不应撞车",
                a.deviceId, DeviceProfile.generate("fpid-role", 888L, 0L).deviceId);
        // 同一角色重试必须稳定（不漂移）
        assertEquals(a.deviceId, DeviceProfile.generate("fpid-role", 888L, 1001L).deviceId);
        assertEquals(a.distinctId, DeviceProfile.generate("fpid-role", 888L, 1001L).distinctId);
        assertEquals(a.ip, DeviceProfile.generate("fpid-role", 888L, 1001L).ip);
    }

    // ===================== P2 修复 =====================

    @Test
    public void parse_v2ManufacturerConflictUsesDevicePrefix() throws Exception {
        String json = new JSONObject()
                .put("v", 2)
                .put("device", "Xiaomi 14")
                .put("manufacturer", "vivo")            // 与机型首段冲突
                .put("brand", "vivo")
                .put("android_version", 14)
                .put("device_lv", 3)
                .put("device_id", "0123456789abcdef0123456789abcdef")
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "36.128.12.34")
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertEquals("以 device 首段为准保持自洽", "Xiaomi", d.manufacturer);
        assertEquals("brand 与 manufacturer 一致", "Xiaomi", d.brand);
    }

    @Test
    public void parse_fieldTypesMustBeNumbers() throws Exception {
        String json = new JSONObject()
                .put("v", 2)
                .put("device", "vivo X200 Pro")
                .put("manufacturer", "vivo")
                .put("android_version", "14")           // 字符串类型 -> 回退
                .put("device_lv", "5")                  // 字符串类型 -> 回退
                .put("device_id", "0123456789abcdef0123456789abcdef")
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "36.128.12.34")
                .toString();
        DeviceProfile.Device d = DeviceProfile.parse(json, "fpid-x");
        assertNotNull(d);
        assertEquals("字符串系统版本按非法回退 13", 13, d.androidVersion);
        assertEquals("字符串设备等级按非法回退 3", 3, d.deviceLv);
    }
}
