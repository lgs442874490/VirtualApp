package com.carlos.vaultserver.bootstrap;

import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DeviceFingerprint} 单测（2026-11-14 二次审查 P1-3 / §8 缺口 3）。
 *
 * <p>固化当前契约：建号指纹由调用方传入**每次不同的盐**（起号传时间戳）保证「一角色一设备」；
 * 同一输入必须确定性复现（同账号同角色重登不漂移）。
 */
public class DeviceFingerprintTest {

    @Test
    public void forNewRole_sameSalt_isDeterministic() {
        String a = DeviceFingerprint.forNewRole("fp-1", 4502L, "1700000000000");
        String b = DeviceFingerprint.forNewRole("fp-1", 4502L, "1700000000000");
        assertEquals("同一输入必须确定性复现（重试不漂移）", a, b);
    }

    @Test
    public void forNewRole_differentSalt_isAnotherDevice() {
        Map<String, String> a = DeviceFingerprint.parse(
                DeviceFingerprint.forNewRole("fp-1", 4502L, "1700000000000"));
        Map<String, String> b = DeviceFingerprint.parse(
                DeviceFingerprint.forNewRole("fp-1", 4502L, "1700000000001"));
        assertNotEquals("不同建号盐 = 另一台设备（一角色一设备）",
                a.get("device_id"), b.get("device_id"));
        assertNotEquals(a.get("distinct_id"), b.get("distinct_id"));
        assertFalse(a.get("ip") == null || a.get("ip").isBlank());
        assertTrue("IP 应为 IPv4 形状: " + a.get("ip"),
                a.get("ip").matches("\\d{1,3}(\\.\\d{1,3}){3}"));
    }

    @Test
    public void forRole_isStablePerRole() {
        Map<String, String> first = DeviceFingerprint.parse(
                DeviceFingerprint.forRole("fp-1", 4502L, 8001L));
        Map<String, String> again = DeviceFingerprint.parse(
                DeviceFingerprint.forRole("fp-1", 4502L, 8001L));
        assertEquals("同一角色每次登录必须复用同一设备", first.get("device_id"),
                again.get("device_id"));
        Map<String, String> otherRole = DeviceFingerprint.parse(
                DeviceFingerprint.forRole("fp-1", 4502L, 8002L));
        assertNotEquals("不同角色必须是不同设备",
                first.get("device_id"), otherRole.get("device_id"));
        Map<String, String> otherServer = DeviceFingerprint.parse(
                DeviceFingerprint.forRole("fp-1", 4503L, 8001L));
        assertNotEquals("不同区服必须是不同设备",
                first.get("device_id"), otherServer.get("device_id"));
    }
}
