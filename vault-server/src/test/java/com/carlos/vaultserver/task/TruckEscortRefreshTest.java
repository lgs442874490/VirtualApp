package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
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
 * 运宝车队刷新品质（2026-11-08）：req_truck_escort_refresh_truck_reward {id,truck_id,use_diamond}。
 */
public class TruckEscortRefreshTest {

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
        final List<Map<String, Object>> argsList = new ArrayList<>();

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            if (!"notify_truck_escort_info".equals(proto)) return null;
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("id", 2426002L);
            Map<String, Object> truck = new LinkedHashMap<>();
            truck.put("truck_id", 11L);
            truck.put("quality", 2L);
            truck.put("is_finish", 0L);
            Map<String, Object> trucks = new LinkedHashMap<>();
            trucks.put("1", truck);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("base", base);
            c.put("truck_infos", trucks);
            c.put("send_truck_info", null);
            return c;
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(proto);
            argsList.add(args == null ? Map.of() : new LinkedHashMap<>(args));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", 0);
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
        JSONObject sub = new JSONObject();
        sub.put("refresh", true);
        JSONObject p = new JSONObject();
        p.put("sub", sub);
        p.put("target_quality", 5);
        p.put("diamond_refresh_max", 0);
        Map<String, JSONObject> params = new HashMap<>();
        params.put("act_truck", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    @Test
    public void refreshesLowQualityTruck() {
        TestEngine e = new TestEngine();
        ServerActivityTasks.truckEscort(tc(e));

        int idx = e.calls.indexOf("req_truck_escort_refresh_truck_reward");
        assertTrue("应刷新低品质货车", idx >= 0);
        Map<String, Object> a = e.argsList.get(idx);
        assertEquals(2426002L, ((Number) a.get("id")).longValue());
        assertEquals(11L, ((Number) a.get("truck_id")).longValue());
        assertEquals("预算 0 时用免费刷新", Boolean.FALSE, a.get("use_diamond"));
    }
}
