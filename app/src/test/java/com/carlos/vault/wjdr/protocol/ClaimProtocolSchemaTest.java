package com.carlos.vault.wjdr.protocol;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 专家额外情报 / 曙光市集领取协议形状回归：
 * 领取字段名与 finishs 的数组形状必须与抓包 .spb 一致，否则会静默“执行成功但没领到”。
 */
public class ClaimProtocolSchemaTest {

    private static SprotoSchema c2s;
    private static SprotoSchema s2c;

    private static File asset(String rel) {
        File f = new File("src/main/assets/" + rel);
        if (f.exists()) return f;
        return new File("../app/src/main/assets/" + rel);
    }

    @BeforeClass
    public static void load() throws Exception {
        c2s = new SprotoSchemaParser().parse(
                Files.readAllBytes(asset("wjdr/game_proto_c2s.spb").toPath()));
        s2c = new SprotoSchemaParser().parse(
                Files.readAllBytes(asset("wjdr/game_proto_s2c.spb").toPath()));
    }

    private static SprotoFieldDef field(SprotoTypeDef type, String name) {
        for (SprotoFieldDef f : type.getFields()) {
            if (name.equals(f.getName())) return f;
        }
        return null;
    }

    @Test
    public void expertQuests_finishsIsArray() {
        SprotoProtocolDef p = s2c.getProtocolsByName().get("notify_expert_quests");
        assertNotNull(p);
        SprotoTypeDef response = p.getResponseType();
        if (response == null) response = s2c.getTypesByName().get("notify_expert_quests.request");
        assertNotNull(response);

        SprotoFieldDef finishs = field(response, "finishs");
        assertNotNull(finishs);
        assertTrue("finishs 必须是数组", finishs.isArray());
        assertNotNull(finishs.getSubtype());
        assertNotNull(field(finishs.getSubtype(), "id"));
        assertNotNull(field(finishs.getSubtype(), "isgetaward"));
    }

    @Test
    public void dawnMarket_hasTaskInfoAndCostSlots() {
        SprotoProtocolDef p = s2c.getProtocolsByName().get("notify_duck_market_act_info");
        assertNotNull(p);
        SprotoTypeDef response = p.getResponseType();
        if (response == null) response = s2c.getTypesByName().get("notify_duck_market_act_info.request");
        assertNotNull(response);

        SprotoFieldDef actInfo = field(response, "act_info");
        assertNotNull(actInfo);
        assertNotNull(actInfo.getSubtype());
        // 关键：task_info 与 act_info 同级，旧代码去 act_info 里取会永远拿不到任务列表。
        SprotoFieldDef taskInfo = field(response, "task_info");
        assertNotNull("task_info 必须与 act_info 同级", taskInfo);
        assertNotNull(taskInfo.getSubtype());
        SprotoFieldDef finishs = field(taskInfo.getSubtype(), "finishs");
        assertNotNull(finishs);
        assertTrue("finishs 必须是数组", finishs.isArray());
        SprotoFieldDef cost = field(actInfo.getSubtype(), "rewarded_idx_lst");
        assertNotNull(cost);
        assertTrue("rewarded_idx_lst 必须是数组", cost.isArray());
    }

    @Test
    public void claimRequests_fieldNamesMatch() {
        SprotoTypeDef expert = c2s.getProtocolsByName()
                .get("req_get_expert_quest_award").getRequestType();
        assertNotNull(field(expert, "quest_id"));

        SprotoTypeDef quest = c2s.getProtocolsByName()
                .get("req_duck_market_get_quest_award").getRequestType();
        assertNotNull(field(quest, "act_id"));
        assertNotNull(field(quest, "quest_id"));

        SprotoTypeDef cost = c2s.getProtocolsByName()
                .get("req_duck_market_get_cost_award").getRequestType();
        assertNotNull(field(cost, "act_id"));
        assertFalse("cost_award 不接收 quest_id", field(cost, "quest_id") != null);
    }

    @Test
    public void claimResponses_areResolvableProtocols() {
        for (String name : new String[]{"req_get_expert_quest_award",
                "req_duck_market_get_quest_award", "req_duck_market_get_cost_award"}) {
            SprotoProtocolDef p = c2s.getProtocolsByName().get(name);
            assertNotNull(name + " 必须存在于 c2s schema", p);
            assertTrue(name + " tag 必须有效", p.getTag() > 0);
        }
        assertEquals("notify_expert_quests",
                s2c.getProtocolsByTag().get(30464).getName());
    }
}
