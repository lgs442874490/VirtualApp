package com.carlos.vaultserver.passport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * DeviceProfile 单测：hex 规范、guid 三字段一致、seeded 确定性、toLoginProfile 键集。
 */
public class DeviceProfileTest {

    @Test
    public void random_guidFieldsAreSame32Hex() {
        DeviceProfile p = DeviceProfile.random();
        // 实证口径：originGuid = md5(guid)；androidId = MAC（不是 guid）
        assertEquals(com.carlos.vaultserver.passport.PassportIds.md5Hex(p.guid), p.originGuid);
        assertEquals(p.macAddress, p.androidId);
        assertTrue(p.guid.matches("[0-9a-f]{32}"));
        assertTrue("MAC 格式", p.macAddress.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}"));
        assertTrue(p.distinctId.matches("[0-9a-f]{32}"));
        assertNotEquals(p.guid, p.distinctId);
    }

    @Test
    public void random_isUniquePerCall() {
        DeviceProfile a = DeviceProfile.random();
        DeviceProfile b = DeviceProfile.random();
        assertNotEquals(a.guid, b.guid);
        assertNotEquals(a.distinctId, b.distinctId);
    }

    @Test
    public void seeded_isDeterministic() {
        byte[] seed = "m1-seed".getBytes();
        DeviceProfile a = DeviceProfile.seeded(seed);
        DeviceProfile b = DeviceProfile.seeded(seed);
        assertEquals(a.guid, b.guid);
        assertEquals(a.distinctId, b.distinctId);
        assertTrue(a.guid.matches("[0-9a-f]{64}"));
    }

    @Test
    public void loginProfile_keySetAndValues() {
        DeviceProfile p = DeviceProfile.seeded("x".getBytes());
        Map<String, String> lp = p.toLoginProfile();
        assertEquals("", lp.get("account"));
        assertEquals("meizu MEIZU 18 Pro", lp.get("device"));
        assertEquals("android", lp.get("device_os"));
        assertEquals("CN", lp.get("country_code"));
        assertEquals(p.distinctId, lp.get("distinct_id"));
        assertEquals("zh_cn", lp.get("lan"));
        assertEquals("1.16.12", lp.get("version"));
        assertEquals("App_android_8_funplus", lp.get("package_tag"));
        assertEquals("funplus", lp.get("ad_source"));
        assertEquals(p.androidId, lp.get("device_id"));   // device_id = MAC（实证口径）
        assertEquals(10, lp.keySet().size());
        // account 之外无空值
        for (Map.Entry<String, String> e : lp.entrySet()) {
            if ("account".equals(e.getKey())) continue;
            assertFalse(e.getKey() + " 为空", e.getValue() == null || e.getValue().isEmpty());
        }
    }
}
