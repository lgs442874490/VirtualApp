package com.carlos.vault.wjdr;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 设备伪装增强（2026-11-11）引擎接入单测：pinned 注入、协议字段集合不变、
 * 未固化时重试不漂移、identityJson v2、set_device 载荷。
 *
 * <p>对应需求 AC-4/AC-5/AC-9（docs/requirements/设备伪装增强-需求文档-20261111.md）。
 */
public class WjdrEngineIdentityTest {

    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");

    /** 登录载荷固定字段集合（改造前基线：不得增删）。 */
    private static final Set<String> PROTOCOL_FIELDS = new HashSet<>(Arrays.asList(
            "account", "sdkToken", "device", "device_os", "country_code", "distinct_id",
            "lan", "version", "package_tag", "ad_source", "device_id", "device_lv",
            "is_create", "group", "is_simulator", "ip"));

    private static WjdrEngine newEngine() {
        return new WjdrEngine(name -> new byte[0]);
    }

    // ===================== AC-9 协议字段集合 =====================

    @Test
    public void buildLoginPayload_keepsProtocolFieldSet() throws Exception {
        WjdrEngine engine = newEngine();
        Map<String, Object> payload = engine.buildLoginPayload("fpid-1", "token-1", null);

        assertTrue("缺少签名/时间戳", payload.containsKey("sign") && payload.containsKey("ts"));
        Set<String> actual = new HashSet<>(payload.keySet());
        actual.remove("sign");
        actual.remove("ts");
        assertEquals("登录载荷字段集合不得增删: " + actual, PROTOCOL_FIELDS, actual);
        assertEquals("device_os 固定 android", "android", payload.get("device_os"));
        assertEquals("device_lv 缺省 3", 3, payload.get("device_lv"));
        assertEquals("ip 必须是合法 IPv4",
                payload.get("ip"), DeviceProfile.normalizeIp(String.valueOf(payload.get("ip"))));
    }

    @Test
    public void buildLoginPayload_addsServeridOnlyWhenPositive() throws Exception {
        WjdrEngine engine = newEngine();
        assertFalse(engine.buildLoginPayload("fpid-1", "token-1", null).containsKey("serverid"));
        assertFalse(engine.buildLoginPayload("fpid-1", "token-1", 0L).containsKey("serverid"));
        Map<String, Object> withSid = engine.buildLoginPayload("fpid-1", "token-1", 888L);
        assertEquals(888L, withSid.get("serverid"));
        Set<String> expected = new HashSet<>(PROTOCOL_FIELDS);
        expected.add("serverid");
        Set<String> actual = new HashSet<>(withSid.keySet());
        actual.remove("sign");
        actual.remove("ts");
        assertEquals(expected, actual);
    }

    // ===================== 未固化：重试不漂移（FR-2） =====================

    @Test
    public void buildLoginPayload_isDeterministicBeforePinning() throws Exception {
        // 两个全新引擎模拟"登录失败后重试"：未注入固定指纹，同 fpid+serverid 必须得到同一台设备
        Map<String, Object> first = newEngine().buildLoginPayload("fpid-retry", "tok", 888L);
        Map<String, Object> second = newEngine().buildLoginPayload("fpid-retry", "tok", 888L);
        for (String key : new String[]{"device", "device_id", "distinct_id", "ip"}) {
            assertEquals("重试不应换设备字段 " + key, first.get(key), second.get(key));
        }
        assertTrue(HEX32.matcher(String.valueOf(first.get("device_id"))).matches());
    }

    @Test
    public void buildLoginPayload_differsByServerAndFpid() throws Exception {
        Map<String, Object> a = newEngine().buildLoginPayload("fpid-a", "tok", 1L);
        Map<String, Object> b = newEngine().buildLoginPayload("fpid-b", "tok", 1L);
        Map<String, Object> c = newEngine().buildLoginPayload("fpid-a", "tok", 2L);
        assertNotEquals(a.get("device_id"), b.get("device_id"));
        assertNotEquals(a.get("device_id"), c.get("device_id"));
        assertNotEquals(a.get("distinct_id"), b.get("distinct_id"));
    }

    @Test
    public void buildLoginPayload_deviceComesFromPool() throws Exception {
        Map<String, Object> payload = newEngine().buildLoginPayload("fpid-pool", "tok", 9L);
        String device = String.valueOf(payload.get("device"));
        boolean inPool = false;
        for (DeviceProfile.DeviceModel m : DeviceProfile.pool()) {
            if (m.model.equals(device)) inPool = true;
        }
        assertTrue("device 必须来自机型池: " + device, inPool);
    }

    // ===================== pinned 注入（AC-9） =====================

    @Test
    public void buildLoginPayload_usesPinnedIdentity() throws Exception {
        String json = new JSONObject()
                .put("v", 2)
                .put("device", "HUAWEI Mate 60")
                .put("manufacturer", "HUAWEI")
                .put("brand", "HUAWEI")
                .put("android_version", 12)
                .put("device_lv", 5)
                .put("device_id", "0123456789abcdef0123456789abcdef")
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "39.144.7.8")
                .toString();
        WjdrEngine engine = newEngine();
        engine.applyIdentity(json);
        Map<String, Object> payload = engine.buildLoginPayload("fpid-1", "tok", 888L);

        assertEquals("HUAWEI Mate 60", payload.get("device"));
        assertEquals("0123456789abcdef0123456789abcdef", payload.get("device_id"));
        assertEquals("fedcba9876543210fedcba9876543210", payload.get("distinct_id"));
        assertEquals("39.144.7.8", payload.get("ip"));
        assertEquals("device_lv 应使用持久化值", 5, payload.get("device_lv"));

        JSONObject exported = new JSONObject(engine.identityJson());
        assertEquals("v2 输出", 2, exported.getInt("v"));
        assertEquals("HUAWEI", exported.getString("manufacturer"));
        assertEquals("HUAWEI", exported.getString("brand"));
        assertEquals(12, exported.getInt("android_version"));
        assertEquals(5, exported.getInt("device_lv"));
    }

    @Test
    public void applyIdentity_invalidFieldsFallBack() throws Exception {
        String json = new JSONObject()
                .put("device", "vivo X200 Pro")
                .put("device_id", "abc1234567")          // 非法
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "999.1.1.1")                  // 非法
                .toString();
        WjdrEngine engine = newEngine();
        engine.applyIdentity(json);
        Map<String, Object> payload = engine.buildLoginPayload("fpid-1", "tok", 888L);
        assertTrue("非法 device_id 应回退为 32 位 hex",
                HEX32.matcher(String.valueOf(payload.get("device_id"))).matches());
        assertNotNull("非法 ip 应回退为合法 IPv4",
                DeviceProfile.normalizeIp(String.valueOf(payload.get("ip"))));
        assertEquals("合法字段保留", "vivo X200 Pro", payload.get("device"));
        assertEquals("合法字段保留", "fedcba9876543210fedcba9876543210", payload.get("distinct_id"));
    }

    @Test
    public void applyIdentity_garbageDoesNotThrowAndKeepsUnpinned() throws Exception {
        WjdrEngine engine = newEngine();
        engine.applyIdentity(null);
        engine.applyIdentity("");
        engine.applyIdentity("not-a-json");
        engine.applyIdentity("{}");
        Map<String, Object> payload = engine.buildLoginPayload("fpid-garbage", "tok", 3L);
        assertTrue("垃圾输入后仍走确定性生成",
                HEX32.matcher(String.valueOf(payload.get("device_id"))).matches());
    }

    // ===================== identityJson v2 =====================

    @Test
    public void identityJson_emitsV2() throws Exception {
        WjdrEngine engine = newEngine();
        assertEquals("未登录时保持空串", "", engine.identityJson());
        engine.buildLoginPayload("fpid-id", "tok", 888L);
        JSONObject o = new JSONObject(engine.identityJson());
        assertEquals(2, o.getInt("v"));
        assertNotNull(DeviceProfile.normalizeModel(o.getString("device")));
        assertNotNull(DeviceProfile.normalizeIp(o.getString("ip")));
        assertFalse("manufacturer 不得为空", o.optString("manufacturer", "").isEmpty());
        assertTrue("android_version 必须合法", o.getInt("android_version") >= 11
                && o.getInt("android_version") <= 14);
        assertEquals(3, o.getInt("device_lv"));
    }

    @Test
    public void identityJson_roundTripsThroughApplyIdentity() throws Exception {
        WjdrEngine engine = newEngine();
        engine.buildLoginPayload("fpid-round", "tok", 888L);
        String exported = engine.identityJson();

        WjdrEngine next = newEngine();
        next.applyIdentity(exported);       // 模拟"固化后下次登录"
        Map<String, Object> payload = next.buildLoginPayload("fpid-round", "tok", 888L);
        JSONObject first = new JSONObject(exported);
        assertEquals(first.getString("device"), payload.get("device"));
        assertEquals(first.getString("device_id"), payload.get("device_id"));
        assertEquals(first.getString("distinct_id"), payload.get("distinct_id"));
        assertEquals(first.getString("ip"), payload.get("ip"));
        assertEquals(first.getInt("device_lv"), payload.get("device_lv"));
    }

    // ===================== req_settings_set_device 载荷 =====================

    @Test
    public void buildDeviceParams_keepsThirdParam13() throws Exception {
        // 4 参：新增 androidVersion，但协议第 3 参数按需求 A2 固定 "13"
        List<String> params = WjdrEngine.buildDeviceParams("android", "vivo X200 Pro", 14, 3);
        assertEquals("2", params.get(0));
        assertEquals("vivo X200 Pro", params.get(1));
        assertEquals("第 3 参数必须保持 13（A2）", "13", params.get(2));
        assertEquals("3", params.get(3));

        // 旧 3 参重载保留（既有调用/测试不回归）
        List<String> legacy = WjdrEngine.buildDeviceParams("android", "vivo X200 Pro", "3");
        assertEquals("13", legacy.get(2));
    }

    @Test
    public void buildDeviceParams_usesDeviceLvAndDefaults() throws Exception {
        assertEquals("5", WjdrEngine.buildDeviceParams("android", "m", 12, 5).get(3));
        assertEquals("device_lv 越界回退 3",
                "3", WjdrEngine.buildDeviceParams("android", "m", 12, 9).get(3));
        assertEquals("device_lv 缺失回退 3",
                "3", WjdrEngine.buildDeviceParams("android", "m", 12, null).get(3));
        assertEquals("机型缺失用默认值",
                "meizu MEIZU 18 Pro", WjdrEngine.buildDeviceParams("android", "", 12, 3).get(1));
        assertEquals("非 android 平台=1", "1", WjdrEngine.buildDeviceParams("ios", "m", 12, 3).get(0));
        assertEquals("平台 2=2", "2", WjdrEngine.buildDeviceParams("2", "m", 12, 3).get(0));
    }

    @Test
    public void buildDeviceParams_androidVersionOutOfRangeStillKeeps13() throws Exception {
        for (int v : new int[]{0, 10, 11, 14, 15, 99}) {
            assertEquals("androidVersion=" + v + " 时第 3 参数仍为 13（A2）",
                    "13", WjdrEngine.buildDeviceParams("android", "m", v, 3).get(2));
        }
    }

    // ===================== P0 修复：角色维度隔离 / 真实时序 =====================

    @Test
    public void buildLoginPayload_differsByRoleUid() throws Exception {
        Map<String, Object> a = newEngine().buildLoginPayload("fpid-role", "tok", 888L, 1001L);
        Map<String, Object> b = newEngine().buildLoginPayload("fpid-role", "tok", 888L, 1002L);
        assertNotEquals("同账号同服不同角色必须不同 device_id",
                a.get("device_id"), b.get("device_id"));
        assertNotEquals("同账号同服不同角色必须不同 distinct_id",
                a.get("distinct_id"), b.get("distinct_id"));
        // 同一角色重试稳定
        Map<String, Object> again = newEngine().buildLoginPayload("fpid-role", "tok", 888L, 1001L);
        assertEquals(a.get("device_id"), again.get("device_id"));
        assertEquals(a.get("distinct_id"), again.get("distinct_id"));
        assertEquals(a.get("ip"), again.get("ip"));
    }

    @Test
    public void buildLoginPayload_uidZeroMatchesAccountLevel() throws Exception {
        Map<String, Object> legacy = newEngine().buildLoginPayload("fpid-x", "tok", 888L);
        Map<String, Object> zero = newEngine().buildLoginPayload("fpid-x", "tok", 888L, 0L);
        assertEquals("uid=0 保持账号级语义（旧 3 参兼容）",
                legacy.get("device_id"), zero.get("device_id"));
    }

    @Test
    public void applyIdentity_beforeFpidKnownDoesNotPinRandomIdentity() throws Exception {
        // 真实时序：登录前 applyIdentity，此时引擎还不知道 fpid。若把回退随机值 pin 住，
        // 同账号同服多角色会拿到同一台设备（P0 的另一种触发方式）。
        String json = new JSONObject()
                .put("device", "vivo X200 Pro")
                .put("device_id", "abc1234567")          // 非法 -> 不得 pin
                .put("distinct_id", "fedcba9876543210fedcba9876543210")  // 合法 -> pin
                .put("ip", "999.1.1.1")                  // 非法 -> 不得 pin
                .toString();
        WjdrEngine e1 = newEngine();
        e1.applyIdentity(json);
        WjdrEngine e2 = newEngine();
        e2.applyIdentity(json);
        Map<String, Object> a = e1.buildLoginPayload("fpid-t", "tok", 888L, 1001L);
        Map<String, Object> b = e2.buildLoginPayload("fpid-t", "tok", 888L, 1002L);
        assertNotEquals("非法 device_id 不应被随机值 pin 住：不同角色必须不同",
                a.get("device_id"), b.get("device_id"));
        assertNotEquals("非法 ip 不应被随机值 pin 住：不同角色必须不同",
                a.get("ip"), b.get("ip"));
        assertEquals("非法 device_id 回退值应等于同角色确定性生成（未 pin 随机值）",
                newEngine().buildLoginPayload("fpid-t", "tok", 888L, 1001L).get("device_id"),
                a.get("device_id"));
        assertEquals("非法 ip 回退值应等于同角色确定性生成（未 pin 随机值）",
                newEngine().buildLoginPayload("fpid-t", "tok", 888L, 1001L).get("ip"),
                a.get("ip"));
        assertEquals("合法字段照常 pinned", "fedcba9876543210fedcba9876543210", a.get("distinct_id"));
        assertEquals("合法机型照常 pinned", "vivo X200 Pro", a.get("device"));
    }

    @Test
    public void applyIdentity_emptyClearsPreviouslyPinnedIdentity() throws Exception {
        String json = new JSONObject()
                .put("v", 2)
                .put("device", "HUAWEI Mate 60")
                .put("manufacturer", "HUAWEI")
                .put("brand", "HUAWEI")
                .put("android_version", 12)
                .put("device_lv", 3)
                .put("device_id", "0123456789abcdef0123456789abcdef")
                .put("distinct_id", "fedcba9876543210fedcba9876543210")
                .put("ip", "39.144.7.8")
                .toString();
        WjdrEngine engine = newEngine();
        engine.applyIdentity(json);
        engine.applyIdentity("");            // 空注入 = 无固定指纹，必须清掉旧 pinned
        Map<String, Object> payload = engine.buildLoginPayload("fpid-clear", "tok", 888L, 1001L);
        assertNotEquals("空注入后不得复用上一账号的设备",
                "0123456789abcdef0123456789abcdef", payload.get("device_id"));
        Map<String, Object> fresh = newEngine().buildLoginPayload("fpid-clear", "tok", 888L, 1001L);
        assertEquals("空注入后回到确定性生成", fresh.get("device_id"), payload.get("device_id"));
    }

    @Test
    public void applyIdentity_reconnectReplayUsesRoleSeed() throws Exception {
        // 重连重放路径：loginByRole 的 uid 进入 savedExtraParams，重连时必须仍按角色维度生成
        WjdrEngine engine = newEngine();
        Map<String, Object> first = engine.buildLoginPayload("fpid-reconnect", "tok", 888L, 1001L);
        engine.applyIdentity(engine.identityJson());   // 模拟固化后重连注入
        Map<String, Object> replay = engine.buildLoginPayload("fpid-reconnect", "tok", 888L, 1001L);
        assertEquals(first.get("device_id"), replay.get("device_id"));
        assertEquals(first.get("distinct_id"), replay.get("distinct_id"));
        assertEquals(first.get("ip"), replay.get("ip"));
    }
}
