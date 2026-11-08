package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * 地心自动挑战（2026-11-08）：req_climbtower_challenge + 3 队 formation_prefab。
 * 覆盖：连胜推进、失败达上限停止、编队不足跳过。
 */
public class ClimbtowerChallengeTest {

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
        final List<Integer> wins = new ArrayList<>();
        final Map<String, Object> soldierInit = new LinkedHashMap<>();

        TestEngine() {
            super(null);
            Map<String, Object> formation = new LinkedHashMap<>();
            for (int i = 1; i <= 3; i++) {
                Map<String, Object> soldier = new LinkedHashMap<>();
                soldier.put(String.valueOf(11000 + i), Map.of("id", 11000 + i, "count", 1000 * i));
                Map<String, Object> heroId = new LinkedHashMap<>();
                heroId.put("1", Map.of("k", 1, "v", 50000 + i));
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", 340000 + i);
                f.put("soldier", soldier);
                f.put("hero_id", heroId);
                formation.put(String.valueOf(340000 + i), f);
            }
            soldierInit.put("formation", formation);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            if ("notify_soldier_init".equals(proto)) return soldierInit;
            return null;
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(proto);
            if ("req_climbtower_info".equals(proto)) {
                return info();
            }
            if ("req_climbtower_challenge".equals(proto)) {
                int n = 0;
                for (String c : calls) if ("req_climbtower_challenge".equals(c)) n++;
                wins.add(n);
                boolean win = n <= 1;   // 第 1 次赢，第 2 次输
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", 0);
                c.put("is_win", win);
                GameMessage m = new GameMessage();
                m.setContent(c);
                return m;
            }
            return null;
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

        private GameMessage info() {
            Map<String, Object> towers = new LinkedHashMap<>();
            for (int i = 1; i <= 6; i++) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", i);
                t.put("cur_passed_level", 100);
                t.put("daily_fail", 0);
                towers.put(String.valueOf(i), t);
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", 0);
            c.put("towers", towers);
            GameMessage m = new GameMessage();
            m.setContent(c);
            return m;
        }
    }

    private TaskContext tc(TestEngine e, int failMax) {
        JSONObject sub = new JSONObject();
        sub.put("challenge", true);
        JSONObject p = new JSONObject();
        p.put("sub", sub);
        p.put("fail_max", failMax);
        p.put("max_levels", 5);
        Map<String, JSONObject> params = new HashMap<>();
        params.put("climbtower", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    private static int count(List<String> calls, String proto) {
        int n = 0;
        for (String c : calls) if (proto.equals(c)) n++;
        return n;
    }

    @Test
    public void winsThenStopsAtFailLimit() throws Exception {
        TestEngine e = new TestEngine();
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.climbtowerChallenge(tc(e, 1), m);

        assertEquals("1 胜后 1 负，应挑战 2 次", 2, count(e.calls, "req_climbtower_challenge"));
        assertTrue(m.subResults.getOrDefault("challenge", "").contains("通过 1 层"));
    }

    @Test
    public void buildsThreeTeamFormation() {
        TestEngine e = new TestEngine();
        Map<String, Object> prefab = ServerTownTasks.buildTowerFormation(tc(e, 3));
        assertNotNull(prefab);
        assertEquals(3, prefab.size());
        Map<?, ?> team1 = (Map<?, ?>) prefab.get("1");
        assertNotNull(team1);
        assertEquals(1L, ((Number) team1.get("id")).longValue());
        Map<?, ?> p1 = (Map<?, ?>) team1.get("prefab");
        assertNotNull(p1.get("hero_id"));
        assertNotNull(p1.get("soldier"));
    }

    @Test
    public void skipsWhenFormationMissing() throws Exception {
        TestEngine e = new TestEngine();
        e.soldierInit.clear();
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.climbtowerChallenge(tc(e, 3), m);
        assertEquals("编队缺失不应挑战", 0, count(e.calls, "req_climbtower_challenge"));
        assertTrue(m.subResults.getOrDefault("challenge", "").contains("编队不足"));
        assertNull(e.wins.isEmpty() ? null : e.wins.get(0));
    }
}
