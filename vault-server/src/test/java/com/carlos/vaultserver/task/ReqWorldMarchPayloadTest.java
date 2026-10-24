package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vault.wjdr.protocol.PortableSprotoCodec;
import com.carlos.vault.wjdr.protocol.SprotoSchemaParser;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * req_world_march 编队载荷的<b>真实线格式</b>回归（用仓库 sproto schema 做 encode→decode 往返）：
 * <ul>
 *   <li>{@code hero_id} 是 pair_int_int：k=上阵位次（1..n）、v=英雄 id
 *       （真实行军推送实证 {"k":1,"v":50002}；网页版 heroPairs=pair(index+1,heroId)）；</li>
 *   <li>{@code soldier} 是 formation_soldier：字段名 id/count（编码按字段名匹配，
 *       不能用 {k,v}，否则士兵被编码成空结构）。</li>
 * </ul>
 *
 * <p>背景：生产 301（英雄不在城）根因即 hero pair 的 k/v 写反（v 恒 1 → 服务端找不到英雄）。
 */
public class ReqWorldMarchPayloadTest {

    private static byte[] c2s;
    private static byte[] s2c;
    private static PortableSprotoCodec codec;

    private static File asset(String rel) {
        File f = new File("src/main/assets/" + rel);
        if (f.exists()) return f;
        return new File("../app/src/main/assets/" + rel);
    }

    @BeforeClass
    public static void load() throws Exception {
        c2s = Files.readAllBytes(asset("wjdr/game_proto_c2s.spb").toPath());
        s2c = Files.readAllBytes(asset("wjdr/game_proto_s2c.spb").toPath());
        codec = new PortableSprotoCodec(new SprotoSchemaParser());
        codec.loadSchemas(c2s, s2c);
        assertTrue(codec.available());
    }

    @Test
    public void gatherMarchPayload_heroPairsAndSoldiersRoundTrip() {
        List<Long> heroIds = Arrays.asList(50002L, 50006L, 50042L);
        List<Map<String, Object>> heroPairs = Exec.heroPairs(heroIds);
        assertEquals(3, heroPairs.size());
        assertEquals(1L, ((Number) heroPairs.get(0).get("k")).longValue());
        assertEquals(50002L, ((Number) heroPairs.get(0).get("v")).longValue());
        assertEquals(3L, ((Number) heroPairs.get(2).get("k")).longValue());
        assertEquals(50042L, ((Number) heroPairs.get(2).get("v")).longValue());

        List<Map<String, Object>> soldierPairs = new ArrayList<>();
        soldierPairs.add(Exec.soldierPair(11100L, 30000L));
        soldierPairs.add(Exec.soldierPair(21100L, 30000L));

        Map<String, Object> formation = new LinkedHashMap<>();
        formation.put("hero_id", heroPairs);
        formation.put("soldier", soldierPairs);
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("x", 685L);
        endpoint.put("y", 706L);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", 4L);
        args.put("endpoint", endpoint);
        args.put("formation", formation);
        args.put("extra", new LinkedHashMap<String, Object>());

        byte[] frame = codec.encodeRequest("req_world_march", args, 99);
        assertNotNull(frame);
        GameMessage back = codec.decodeResponse(frame);
        assertEquals("req_world_march", back.getProto());
        assertNotNull(back.getContent());

        Map<String, Object> decoded = asMap(back.getContent().get("formation"));
        assertNotNull("formation 必须往返存在", decoded);
        List<Map<String, Object>> decodedHeroes = asMapList(decoded.get("hero_id"));
        assertEquals(3, decodedHeroes.size());
        assertEquals(1L, num(decodedHeroes.get(0).get("k")));
        assertEquals(50002L, num(decodedHeroes.get(0).get("v")));
        assertEquals(2L, num(decodedHeroes.get(1).get("k")));
        assertEquals(50006L, num(decodedHeroes.get(1).get("v")));
        assertEquals(3L, num(decodedHeroes.get(2).get("k")));
        assertEquals(50042L, num(decodedHeroes.get(2).get("v")));

        List<Map<String, Object>> decodedSoldiers = asMapList(decoded.get("soldier"));
        assertEquals("soldier 不能编码为空结构 decoded=" + decoded, 2, decodedSoldiers.size());
        // c2s formation.soldier 元素是 pair_int_int：k=兵种 id、v=数量
        assertEquals(11100L, num(decodedSoldiers.get(0).get("k")));
        assertEquals(30000L, num(decodedSoldiers.get(0).get("v")));
        assertEquals(21100L, num(decodedSoldiers.get(1).get("k")));
        assertEquals(30000L, num(decodedSoldiers.get(1).get("v")));
        codec.releaseSession(99);
    }

    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static List<Map<String, Object>> asMapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<?> items;
        if (o instanceof List) {
            items = (List<?>) o;
        } else if (o instanceof Map) {
            items = new ArrayList<Object>(((Map<?, ?>) o).values());
        } else {
            return out;
        }
        for (Object item : items) {
            Map<String, Object> m = asMap(item);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
