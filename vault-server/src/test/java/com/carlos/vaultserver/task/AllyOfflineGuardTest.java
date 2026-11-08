package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.VaultStore;

import org.json.JSONArray;
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
 * 离线联动（2026-11-08）：req_players_base_info_with_permission + offline_time（0=在线）。
 */
public class AllyOfflineGuardTest {

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
            return null;
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
            if ("req_players_base_info_with_permission".equals(proto)) {
                Map<String, Object> u1 = new LinkedHashMap<>();
                u1.put("uid", 111L);
                u1.put("offline_time", 0L);
                Map<String, Object> u2 = new LinkedHashMap<>();
                u2.put("uid", 222L);
                u2.put("offline_time", 1794000000L);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("111", u1);
                result.put("222", u2);
                c.put("result", result);
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

    private TaskContext tc(TestEngine e, JSONObject p) {
        Map<String, JSONObject> params = new HashMap<>();
        params.put("ally_offline_guard", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    @Test
    public void queriesWatchUidsAndCountsOnline() {
        TestEngine e = new TestEngine();
        JSONObject p = new JSONObject();
        JSONArray uids = new JSONArray();
        uids.put(111L);
        uids.put(222L);
        p.put("watch_uids", uids);

        ServerActivityTasks.allyOfflineGuard(tc(e, p));

        assertEquals(1, e.calls.stream().filter("req_players_base_info_with_permission"::equals).count());
        Map<String, Object> args = e.argsList.get(0);
        assertEquals(2, ((List<?>) args.get("uids")).size());
    }

    @Test
    public void skipsWithoutWatchUids() {
        TestEngine e = new TestEngine();
        ServerActivityTasks.allyOfflineGuard(tc(e, new JSONObject()));
        assertTrue("未配置关注角色不应发请求",
                e.calls.stream().noneMatch("req_players_base_info_with_permission"::equals));
        assertFalse(e.calls.contains("req_players_base_info_with_permission"));
    }
}
