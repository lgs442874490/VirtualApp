package com.carlos.vault.wjdr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 引擎防封保护（P1 2026-10-21 契约 §二.8）：
 * 开关注入、req_settings_set_device 载荷映射（platform/deviceModel/systemVersion/qualityLevel
 * 与线上 sendSettingsSetDevice 同款）、离线补做入口不误发。
 */
public class WjdrEngineAntibanTest {

    @Test
    public void antibanFlag_defaultOff_toggleVisible() {
        WjdrEngine engine = new WjdrEngine(null);
        assertFalse(engine.antibanEnabled());
        engine.setAntibanEnabled(true);
        assertTrue(engine.antibanEnabled());
        engine.setAntibanEnabled(false);
        assertFalse(engine.antibanEnabled());
    }

    @Test
    public void deviceParams_platformMapping() {
        // "android"/"2" → "2"；其余（ios/空）→ "1"
        assertEquals(List.of("2", "vivo X200 Pro", "13", "3"),
                WjdrEngine.buildDeviceParams("android", "vivo X200 Pro", "3"));
        assertEquals(List.of("2", "meizu MEIZU 18 Pro", "13", "3"),
                WjdrEngine.buildDeviceParams("2", "meizu MEIZU 18 Pro", "3"));
        assertEquals(List.of("1", "iPhone 15", "13", "3"),
                WjdrEngine.buildDeviceParams("ios", "iPhone 15", "3"));
        assertEquals(List.of("1", "x", "13", "3"),
                WjdrEngine.buildDeviceParams("", "x", "3"));
    }

    @Test
    public void deviceParams_defaultsForMissingModelAndQuality() {
        // 机型缺省 → meizu MEIZU 18 Pro；画质缺省 → 3（契约 §二.8）
        assertEquals(List.of("2", "meizu MEIZU 18 Pro", "13", "3"),
                WjdrEngine.buildDeviceParams("android", "", ""));
        assertEquals(List.of("2", "meizu MEIZU 18 Pro", "13", "3"),
                WjdrEngine.buildDeviceParams("android", "  ", null));
        assertEquals(List.of("2", "m", "13", "2"),
                WjdrEngine.buildDeviceParams("android", "m", "2"));
    }

    @Test
    public void riskCheckIfPending_offlineOrDisabled_neverThrows() {
        WjdrEngine engine = new WjdrEngine(null);   // 未初始化连接（离线态）
        engine.performAntibanRiskCheckIfPending();  // 关闭+离线：静默 no-op
        engine.setAntibanEnabled(true);
        engine.performAntibanRiskCheckIfPending();  // 离线：静默 no-op（不发协议）
    }
}
