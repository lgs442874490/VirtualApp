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
 * 海岛（homeland）自动化（2026-11-08）：
 * - 主动 req_homeland_enter_homeland 拉状态；
 * - 免费商品 8002 购买（free_store 子项）；
 * - 每周免费钻石 req_homeland_token_shop_weekly_recv（weekly_diamond 子项）。
 */
public class HomelandTaskTest {

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
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<Map<String, Object>> callArgs = new ArrayList<>();

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(proto);
            callArgs.add(args == null ? Map.of() : new LinkedHashMap<>(args));
            return msg(0);
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

    private static GameMessage msg(long code) {
        GameMessage m = new GameMessage();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        m.setContent(c);
        return m;
    }

    private TaskContext tc(TestEngine e, JSONObject homelandParams) {
        Map<String, JSONObject> params = new HashMap<>();
        params.put("homeland", homelandParams);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    private static JSONObject subs(boolean freeStore, boolean weeklyDiamond) {
        JSONObject sub = new JSONObject();
        sub.put("free_store", freeStore);
        sub.put("weekly_diamond", weeklyDiamond);
        JSONObject p = new JSONObject();
        p.put("sub", sub);
        return p;
    }

    @Test
    public void buysFreeItemAndClaimsWeeklyDiamondByDefault() {
        TestEngine e = new TestEngine();
        // 通知里给一个伐木点，避免“伐木点未同步”分支干扰
        Map<String, Object> hi = new LinkedHashMap<>();
        hi.put("map_info", Map.of("logging_cabin_info", Map.of("point", Map.of("x", 76, "y", 97))));
        e.notifies.put("notify_homeland_info", hi);

        ServerTownTasks.homeland(tc(e, subs(true, true)));

        assertTrue("应主动进岛拉状态", e.calls.contains("req_homeland_enter_homeland"));
        assertTrue("应购买免费商品", e.calls.contains("req_homeland_buy_store_item"));
        assertTrue("应领取每周钻石", e.calls.contains("req_homeland_token_shop_weekly_recv"));
        int idx = e.calls.indexOf("req_homeland_buy_store_item");
        Object item = e.callArgs.get(idx).get("item");
        assertTrue(item instanceof Map);
        assertEquals(8002L, ((Number) ((Map<?, ?>) item).get("id")).longValue());
    }

    @Test
    public void skipsFreeItemAndWeeklyDiamondWhenDisabled() {
        TestEngine e = new TestEngine();

        ServerTownTasks.homeland(tc(e, subs(false, false)));

        assertFalse("关闭时不买免费商品", e.calls.contains("req_homeland_buy_store_item"));
        assertFalse("关闭时不领每周钻石", e.calls.contains("req_homeland_token_shop_weekly_recv"));
        assertTrue("仍应主动进岛", e.calls.contains("req_homeland_enter_homeland"));
    }
}
