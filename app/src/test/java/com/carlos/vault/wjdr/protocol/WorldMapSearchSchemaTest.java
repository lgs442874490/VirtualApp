package com.carlos.vault.wjdr.protocol;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * req_world_map_search 响应形状回归：坐标在单对象 point{x,y}。
 * 旧实现只解析 points/data/list，采集/狩猎会恒定“附近未搜到资源点”。
 */
public class WorldMapSearchSchemaTest {

    private static SprotoSchema c2s;

    private static File asset(String rel) {
        File f = new File("src/main/assets/" + rel);
        if (f.exists()) return f;
        return new File("../app/src/main/assets/" + rel);
    }

    @BeforeClass
    public static void load() throws Exception {
        c2s = new SprotoSchemaParser().parse(
                Files.readAllBytes(asset("wjdr/game_proto_c2s.spb").toPath()));
    }

    @Test
    public void response_hasPointObjectWithXY() {
        SprotoProtocolDef p = c2s.getProtocolsByName().get("req_world_map_search");
        assertNotNull(p);
        assertEquals(5615, p.getTag());
        SprotoTypeDef response = p.getResponseType();
        assertNotNull(response);

        SprotoFieldDef point = null;
        for (SprotoFieldDef f : response.getFields()) {
            if ("point".equals(f.getName())) point = f;
        }
        assertNotNull("响应字段必须是 point", point);
        assertTrue("point 是单个对象而不是数组", !point.isArray());
        assertNotNull(point.getSubtype());
        boolean x = false, y = false;
        for (SprotoFieldDef f : point.getSubtype().getFields()) {
            if ("x".equals(f.getName())) x = true;
            if ("y".equals(f.getName())) y = true;
        }
        assertTrue("point 必须含 x", x);
        assertTrue("point 必须含 y", y);
    }

    @Test
    public void request_fields_matchCaller() {
        SprotoProtocolDef p = c2s.getProtocolsByName().get("req_world_map_search");
        SprotoTypeDef req = p.getRequestType();
        assertNotNull(req);
        boolean type = false, min = false, max = false, res = false, full = false;
        for (SprotoFieldDef f : req.getFields()) {
            switch (f.getName()) {
                case "mapobj_type": type = true; break;
                case "level_min": min = true; break;
                case "level_max": max = true; break;
                case "res_id": res = true; break;
                case "is_full": full = true; break;
                default: break;
            }
        }
        assertTrue(type && min && max && res && full);
    }
}
