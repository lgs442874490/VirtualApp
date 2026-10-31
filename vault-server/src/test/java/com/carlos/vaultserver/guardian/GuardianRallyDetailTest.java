package com.carlos.vaultserver.guardian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 批次 N（2026-10-31）：集结兵力字段缺失时的详情补链。
 *
 * <p>生产实证：mass_march 推送普遍缺 soldier_count/soldier_max/上车字段 → rally_unknown 不发射。
 * 规则：{@code req_world_mass_master_detail} 返回的 mass 字段合并后，兵力 > rally_troops_min（默认 30 万）
 * 即判真集结。
 */
public class GuardianRallyDetailTest {

    private static ServerGuardianEngine.Config cfg(String json) {
        return ServerGuardianEngine.parseConfig(new JSONObject(json));
    }

    private static Map<String, Object> detail(long soldierCount, long soldierMax) {
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("soldier_count", soldierCount);
        mass.put("soldier_max", soldierMax);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mass_master", mass);
        return content;
    }

    @Test
    public void detailTroopsAboveThreshold_makesRallyTrue() {
        ServerGuardianEngine.Config c = cfg("{\"sub\":{\"enabled\":true}}");
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(9001L, 1_000L);
        r.type = 102L;
        assertEquals("初始无兵力信息 → UNKNOWN",
                ServerGuardianEngine.RallyTruth.UNKNOWN, ServerGuardianEngine.evaluateRally(r, c));

        // 模拟详情返回 mass_master{soldier_count:350000,soldier_max:400000}
        ServerGuardianEngine.mergeRallyMass(r, detail(350_000L, 400_000L));
        assertTrue(r.hasSoldierInfo);
        assertEquals("兵力 35 万 > 阈值 30 万 → 真集结",
                ServerGuardianEngine.RallyTruth.TRUE, ServerGuardianEngine.evaluateRally(r, c));
    }

    @Test
    public void detailTroopsBelowThreshold_staysFakeOrUnknown() {
        ServerGuardianEngine.Config c = cfg("{\"sub\":{\"enabled\":true}}");
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(9002L, 1_000L);
        r.type = 102L;
        // 20 万 / 上限 40 万 → 未满兵且低于阈值；无上车信息 → UNKNOWN（不是 TRUE）
        ServerGuardianEngine.mergeRallyMass(r, detail(200_000L, 400_000L));
        assertEquals("未达 30 万且未满兵 → FAKE（不驻防）",
                ServerGuardianEngine.RallyTruth.FAKE, ServerGuardianEngine.evaluateRally(r, c));
    }

    @Test
    public void thresholdIsUserConfigurable() {
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(9003L, 1_000L);
        r.type = 102L;
        ServerGuardianEngine.mergeRallyMass(r, detail(350_000L, 400_000L));
        ServerGuardianEngine.Config high = cfg(
                "{\"sub\":{\"enabled\":true,\"rally_troops_min\":500000}}");
        assertEquals("阈值提到 50 万时 35 万不算真（未满兵 → FAKE，不驻防）",
                ServerGuardianEngine.RallyTruth.FAKE, ServerGuardianEngine.evaluateRally(r, high));
        ServerGuardianEngine.Config low = cfg(
                "{\"sub\":{\"enabled\":true,\"rally_troops_min\":100000}}");
        assertEquals("阈值降到 10 万时 35 万算真",
                ServerGuardianEngine.RallyTruth.TRUE, ServerGuardianEngine.evaluateRally(r, low));
    }

    @Test
    public void detailFieldsUnderAlternateKeysAlsoWork() {
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(9004L, 1_000L);
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("total_soldier", 320_000L);
        mass.put("max_soldier", 400_000L);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mass", mass);
        ServerGuardianEngine.mergeRallyMass(r, content);
        assertTrue(r.hasSoldierInfo);
        assertEquals(320_000L, r.soldierCount);
    }

    @Test
    public void cancelledRallyNeverTrue() {
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(9005L, 1_000L);
        r.type = 102L;
        ServerGuardianEngine.mergeRallyMass(r, detail(350_000L, 400_000L));
        r.cancelled = true;
        assertEquals(ServerGuardianEngine.RallyTruth.CANCELLED,
                ServerGuardianEngine.evaluateRally(r, cfg("{\"sub\":{\"enabled\":true}}")));
    }

    @Test
    public void joinFullWithoutTroopsInfo_unknownNotTrue() {
        ServerGuardianEngine.Rally r = new ServerGuardianEngine.Rally(9006L, 1_000L);
        r.type = 102L;
        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("members", java.util.List.of(
                new LinkedHashMap<>(Map.of("uid", 1L, "full", true))));
        ServerGuardianEngine.mergeRallyMass(r, mass);
        assertFalse(r.hasSoldierInfo);
        assertTrue(r.someoneFull);
        assertEquals("无兵力字段 → UNKNOWN（规则要求先看兵力>阈值）",
                ServerGuardianEngine.RallyTruth.UNKNOWN,
                ServerGuardianEngine.evaluateRally(r, cfg("{\"sub\":{\"enabled\":true}}")));
    }
}
