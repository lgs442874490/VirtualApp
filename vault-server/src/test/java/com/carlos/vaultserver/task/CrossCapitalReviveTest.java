package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最强王国部队复活（2026-11-08）：活动期 + dead_map 非空 → req_cross_capital_war_revive + 求助。
 */
public class CrossCapitalReviveTest {

    private static final long SID = 888L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private StateStore state;
    private VaultStore vault;

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
        vault = new VaultStore(tmp.getRoot().toPath());
    }

    private static final class TestEngine extends WjdrEngine {
        final List<String> calls = new ArrayList<>();
        Map<String, Object> init;

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        Map<String, Object> membersInit;

        @Override
        public Map<String, Object> notifyData(String proto) {
            if ("notify_cross_capital_war_init".equals(proto)) return init;
            if ("notify_alliance_member_init".equals(proto)) return membersInit;
            return null;
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(proto);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", 0);
            if ("req_cross_capital_war_get_user_revive_data".equals(proto)) {
                Map<String, Object> dataMap = new LinkedHashMap<>();
                dataMap.put("6308486", Map.of("uid", 6308486L, "total_dead_soldier", 500L,
                        "revive_coupon", 10L, "receive_help_times", 0L));
                dataMap.put("35371154", Map.of("uid", 35371154L, "total_dead_soldier", 100L,
                        "revive_coupon", 0L, "receive_help_times", 0L));
                c.put("data_map", dataMap);
            }
            if ("req_cross_capital_war_help_revive".equals(proto)) {
                c.put("suc_times", 5L);
            }
            GameMessage m = new GameMessage();
            m.setContent(c);
            return m;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args, int timeoutSec) {
            return request(proto, args);
        }

        @Override
        public long lastUid() {
            return 1L;
        }

        @Override
        public long lastServerId() {
            return SID;
        }
    }

    private TaskContext tc(TestEngine e) {
        return new TaskContext(e, "111", SID, state, new HashMap<String, JSONObject>(), "测试");
    }

    private static Map<String, Object> init(long stage, boolean dead) {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("stage", stage);
        Map<String, Object> revive = new LinkedHashMap<>();
        Map<String, Object> deadMap = new LinkedHashMap<>();
        if (dead) {
            deadMap.put("11100", Map.of("k", 11100, "v", 255));
        }
        revive.put("dead_map", deadMap);
        revive.put("revive_coupon", 20L);
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("revive_data", revive);
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("activity", activity);
        init.put("player", player);
        return init;
    }

    @Test
    public void revivesWhenActivityOpenAndDeadSoldiers() {
        TestEngine e = new TestEngine();
        e.init = init(6L, true);
        ServerActivityTasks.crossCapitalRevive(tc(e));
        assertTrue(e.calls.contains("req_cross_capital_war_revive"));
        assertTrue(e.calls.contains("req_cross_capital_war_revive_call_alliance_help"));
    }

    @Test
    public void skipsWhenNoDeadSoldiers() {
        TestEngine e = new TestEngine();
        e.init = init(6L, false);
        ServerActivityTasks.crossCapitalRevive(tc(e));
        assertFalse(e.calls.contains("req_cross_capital_war_revive"));
    }

    @Test
    public void helpsAllianceMembersReviveByDeadSoldiers() {
        TestEngine e = new TestEngine();
        e.init = init(6L, true);
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("6308486", Map.of("uid", 6308486L, "rank", 1L));
        members.put("35371154", Map.of("uid", 35371154L, "rank", 3L));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("members", members);
        e.membersInit = root;
        ServerActivityTasks.crossCapitalRevive(tc(e));
        assertTrue("应查询联盟成员复活数据", e.calls.contains("req_cross_capital_war_get_user_revive_data"));
        assertTrue("应援助盟友复活", e.calls.contains("req_cross_capital_war_help_revive"));
    }

    @Test
    public void skipsWhenActivityClosed() {
        TestEngine e = new TestEngine();
        e.init = init(0L, true);
        ServerActivityTasks.crossCapitalRevive(tc(e));
        assertFalse(e.calls.contains("req_cross_capital_war_revive"));
    }
}
