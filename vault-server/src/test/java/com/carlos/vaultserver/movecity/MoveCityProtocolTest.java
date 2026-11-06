package com.carlos.vaultserver.movecity;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A1/A2：协议注册表与 payload 构造（1:1 对齐原版 MoveCityService.PROTOCOLS:100）。
 */
public class MoveCityProtocolTest {

    @Test
    public void protocolsTable_hasExactly12Items_fieldByField() {
        List<MoveCityProtocol> ps = MoveCityProtocol.PROTOCOLS;
        assertEquals(12, ps.size());

        String[][] expected = {
                {"迁城", "req_world_move_city", "1", "0", "610104", "2835"},
                {"随机迁城", "req_world_random_move_city", "0", "0", "610101", "0"},
                {"霜龙迁城", "req_supremacy_war_move_city", "1", "0", "0", "0"},
                {"燃霜迁城", "req_frostfire_war_move_city", "1", "0", "610104", "0"},
                {"大乱斗迁城", "req_brawl_war_move_city", "1", "0", "610104", "40652"},
                {"联盟迁城", "req_world_alliance_move_city", "1", "0", "610102", "2875"},
                {"领地迁城", "req_world_alliance_territory_move_city", "1", "0", "610103", "5108"},
                {"兵工厂迁城", "req_arsenal_war_move_city", "1", "0", "610104", "9003"},
                {"跨王国随机迁城", "req_world_wormhole_jumping_random", "0", "1", "0", "0"},
                {"跨王国回家", "req_world_gohome", "0", "0", "0", "0"},
                {"跨王国高级迁城", "req_world_wormhole_jumping_to_point", "1", "1", "0", "0"},
                {"跨王国高级回家", "req_world_gohome_to_point", "1", "0", "0", "0"},
        };
        for (int i = 0; i < expected.length; i++) {
            MoveCityProtocol p = ps.get(i);
            assertEquals("第 " + (i + 1) + " 项 label", expected[i][0], p.label());
            assertEquals("第 " + (i + 1) + " 项 proto", expected[i][1], p.proto());
            assertEquals("第 " + (i + 1) + " 项 needsPoint", expected[i][2].equals("1"), p.needsPoint());
            assertEquals("第 " + (i + 1) + " 项 needsKid", expected[i][3].equals("1"), p.needsKid());
            assertEquals("第 " + (i + 1) + " 项 requiredItemId", Long.parseLong(expected[i][4]), p.requiredItemId());
            assertEquals("第 " + (i + 1) + " 项 itemShortageCode", Integer.parseInt(expected[i][5]), p.itemShortageCode());
        }
    }

    @Test
    public void buildPayload_pointProtocol_hasPointXY() {
        MoveCityProtocol p = MoveCityProtocol.byProto("req_world_move_city");
        Map<String, Object> payload = p.buildPayload(100, 200, 0);
        assertEquals(1, payload.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> point = (Map<String, Object>) payload.get("point");
        assertEquals(100, point.get("x"));
        assertEquals(200, point.get("y"));
    }

    @Test
    public void buildPayload_kidProtocol_hasKid() {
        MoveCityProtocol p = MoveCityProtocol.byProto("req_world_wormhole_jumping_random");
        Map<String, Object> payload = p.buildPayload(0, 0, 77);
        assertEquals(1, payload.size());
        assertEquals(77, payload.get("kid"));
    }

    @Test
    public void buildPayload_pointAndKidProtocol_hasBoth() {
        MoveCityProtocol p = MoveCityProtocol.byProto("req_world_wormhole_jumping_to_point");
        Map<String, Object> payload = p.buildPayload(10, 20, 33);
        assertEquals(2, payload.size());
        assertEquals(33, payload.get("kid"));
        @SuppressWarnings("unchecked")
        Map<String, Object> point = (Map<String, Object>) payload.get("point");
        assertEquals(10, point.get("x"));
        assertEquals(20, point.get("y"));
    }

    @Test
    public void buildPayload_randomProtocol_isEmpty() {
        MoveCityProtocol p = MoveCityProtocol.byProto("req_world_random_move_city");
        assertTrue(p.buildPayload(0, 0, 0).isEmpty());
    }

    @Test
    public void resolve_byProtoAndLabel_unknownGivesNull() {
        assertEquals("req_world_move_city", MoveCityProtocol.resolve("req_world_move_city").proto());
        assertEquals("req_world_move_city", MoveCityProtocol.resolve("迁城").proto());
        assertNull(MoveCityProtocol.resolve("不存在"));
        assertNull(MoveCityProtocol.resolve(null));
    }
}
