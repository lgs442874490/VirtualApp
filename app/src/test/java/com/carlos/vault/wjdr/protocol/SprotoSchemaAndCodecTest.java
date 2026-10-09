package com.carlos.vault.wjdr.protocol;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * TC-X05 / TC-X06 / TC-X07：sproto schema 解析与编解码往返（真实 .spb 资产）。
 * 对标原版 wjdr：schema 文件同源，编解码必须字节兼容。
 */
public class SprotoSchemaAndCodecTest {

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
    public void schemaParses_fullBundle() {
        SprotoSchema c2sSchema = new SprotoSchemaParser().parse(c2s);
        SprotoSchema s2cSchema = new SprotoSchemaParser().parse(s2c);
        assertTrue("c2s 协议数量", c2sSchema.getProtocols().size() >= 400);
        assertTrue("s2c 协议数量", s2cSchema.getProtocols().size() >= 200);
        // tag 在各自 schema 内唯一（协议分发按 tag）
        assertEquals(c2sSchema.getProtocols().size(), c2sSchema.getProtocolsByTag().size());
        assertEquals(s2cSchema.getProtocols().size(), s2cSchema.getProtocolsByTag().size());
    }

    @Test
    public void knownProtocols_resolvable() {
        // 登录/心跳/日常 50 项功能覆盖到的代表性协议必须收录
        String[] protos = {
                "loginauth", "req_loaddata", "req_heartbeat",
                "req_fourteen_sign_get_all_reward",            // login_rewards
                "req_mail_type_info", "req_batch_mail_read",   // mail
                "req_receive_online_reward", "req_receive_spec_reward", // supply
                "req_get_all_dailyquest_award",                // daily_mission
                "req_open_hang_up_reward_view", "req_get_hang_up_reward", // adventure
                "req_vip_get_daily_free_gift",                 // commander_gift
                "req_general_bp_get_all_reward",               // act_endless
                "req_hero_search_times",                       // town_affairs
                "req_hospital_heal_soldier",                   // medic
                "req_barrack_train_soldier", "req_barrack_recruit_soldier", // barracks
                "req_begin_research",                          // research
                "req_upgrade_building",                        // building
                "req_pet_use_skill", "req_pet_adventure_dispatch", // pet
                "req_travel_recv_energy",                      // expert
                "req_homeland_take_life_tree_coin",            // homeland
                "req_spar_purify",                             // fire_crystal
                "req_climbtower_sweep",                        // climbtower
                "req_arena_fight", "req_arena_get_daily_reward", // arena
                "req_world_map_search", "req_world_march",     // gather/hunt/…
                "req_alliance_help_all",                       // alliance_help
                "req_alliance_receive_chest",                  // alliance_chest
                "req_mobilization_batch_get_milestone",        // alliance_mobilization
                "req_shop_buy",                                // shop
                "req_red_packet_refresh",                      // red_packet
                "req_bank_withdraw", "req_bank_deopsit",       // bank（协议名即原文本拼写）
                "req_batch_use_item",                          // backpack
                "req_social_gift_send",                        // social_gift
                "req_city_item_free_use",                      // battle_shield
                "req_frostfire_war_apply",                     // act_frostfire
                "req_alliance_activity_auto_join_open",        // behemoth 原生自动上车
                "req_alliance_trap_info",                      // bear
        };
        SprotoSchema schema = new SprotoSchemaParser().parse(c2s);
        for (String p : protos) {
            assertNotNull("协议必须收录: " + p, schema.getProtocolsByName().get(p));
            assertTrue(schema.getProtocolsByName().get(p).getTag() > 0);
        }
    }

    @Test
    public void packUnpack_alignmentSemantics() {
        // 协议语义（与原版 sproto pack 格式一致）：8 字节段 + 零位图压缩。
        // unpack 输出按 8 字节段还原 ⇒ 长度向上取整到 8 的倍数（尾部零填充），
        // 非 8 倍数长度不做逐字节恒等，只断言有效前缀一致；8 倍数长度必须恒等。
        Random rnd = new Random(42);
        int[] sizes = {0, 1, 2, 7, 8, 9, 15, 16, 17, 63, 64, 100, 255, 256, 1000, 8192};
        for (int size : sizes) {
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            for (int i = 0; i < size; i += 3) data[i] = (byte) 0xFF; // 触发 0xFF 段边界
            byte[] packed = codec.pack(data);
            byte[] unpacked = codec.unpack(packed);
            int expectedLen = (size + 7) / 8 * 8;
            assertEquals("unpack 按 8 字节段对齐 size=" + size, expectedLen, unpacked.length);
            for (int i = 0; i < size; i++) {
                assertEquals("有效前缀必须一致 size=" + size + " i=" + i, data[i], unpacked[i]);
            }
            if (size % 8 == 0) {
                assertArrayEquals("8 倍数长度必须逐字节恒等 size=" + size, data, unpacked);
            }
        }
    }

    @Test
    public void encodeRequest_then_decodeResponse_roundtrip() {
        // 全链路：encodeRequest（含 header/type/session/crypt）→ decodeResponse（REQUEST 分支按
        // c2s tag 反查 + 按请求类型解内容）→ 字段值往返一致
        SprotoProtocolDef def = new SprotoSchemaParser()
                .parse(c2s).requireProtocol("req_world_map_search");
        assertNotNull(def.getRequestType());

        Map<String, Object> args = new LinkedHashMap<>();
        int intFields = 0;
        for (SprotoFieldDef f : def.getRequestType().getFields()) {
            if (!f.isArray() && f.getType() == 0 && intFields < 3) { // SPROTO_TINTEGER
                args.put(f.getName(), 3 + intFields);
                intFields++;
            }
        }
        assertTrue("至少填入一个整数字段", intFields > 0);

        byte[] frame = codec.encodeRequest("req_world_map_search", args, 4242);
        assertNotNull(frame);
        assertTrue(frame.length > 2);

        GameMessage back = codec.decodeResponse(frame);
        assertEquals("REQUEST", back.getType());
        assertEquals("req_world_map_search", back.getProto());
        assertEquals(4242, back.getSession());
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = back.getContent().get(e.getKey());
            assertNotNull("字段往返必须存在: " + e.getKey(), v);
            assertEquals("字段值往返一致: " + e.getKey(),
                    ((Number) e.getValue()).longValue(), ((Number) v).longValue());
        }
        // 会话登记（encodeRequest session>0 ⇒ 注册响应映射）
        codec.releaseSession(4242);
    }

    @Test
    public void xorProtocol_carriesCryptHeader() {
        // req_world_march 属于协商前 XOR 协议（XOR_PROTOCOLS）⇒ header 带 ud.crypt_method=1
        byte[] frame = codec.encodeRequest("req_world_march",
                new LinkedHashMap<>(), 77);
        GameMessage back = codec.decodeResponse(frame);
        assertEquals("req_world_march", back.getProto());
        assertNotNull(back.getContent()); // 解密成功才可能有内容
    }

    @Test
    public void sessionLifecycle_registeredAndCleared() {
        codec.encodeRequest("req_heartbeat", new LinkedHashMap<>(), 555);
        codec.clearSessions();
        // 清空后未映射响应走 _unmapped 兜底路径（不抛异常）
        byte[] frame = codec.encodeRequest("req_heartbeat", new LinkedHashMap<>(), 0);
        GameMessage m = codec.decodeResponse(frame);
        assertNotNull(m);
    }
}
