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
 * 联赛任务（2026-11-08）：
 * - 雪域兵器联赛：req_alliance_arsenal_league_login_weekly；
 * - 冰火战歌：req_slg_3_league_get_task_reward（按 info_init.quests）+ 点赞。
 */
public class LeagueTasksTest {

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
        Map<String, Object> initData;

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            if ("notify_slg_3_league_info_init".equals(proto)) return initData;
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

    private static GameMessage ok() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", 0);
        GameMessage m = new GameMessage();
        m.setContent(c);
        return m;
    }

    private TaskContext tc(TestEngine e, JSONObject p) {
        Map<String, JSONObject> params = new HashMap<>();
        params.put("act_slg_league", p);
        params.put("act_arsenal_league", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    private static int count(List<String> calls, String proto) {
        int n = 0;
        for (String c : calls) if (proto.equals(c)) n++;
        return n;
    }

    @Test
    public void arsenalLeagueClaimsWeeklyLogin() {
        TestEngine e = new TestEngine();
        ServerActivityTasks.arsenalLeague(tc(e, new JSONObject()));
        assertEquals(1, count(e.calls, "req_alliance_arsenal_league_login_weekly"));
    }

    @Test
    public void slgLeagueClaimsAllQuestsAndLikes() {
        TestEngine e = new TestEngine();
        Map<String, Object> quests = new LinkedHashMap<>();
        quests.put("1510500", Map.of("id", 1510500L, "progress", 7L));
        quests.put("1510501", Map.of("id", 1510501L, "progress", 9L));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quests", quests);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("data", data);
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("task", task);
        e.initData = init;

        JSONObject p = new JSONObject();
        p.put("sub", new JSONObject().put("like", true));
        ServerActivityTasks.slgLeague(tc(e, p));

        assertEquals("两个任务各领一次", 2, count(e.calls, "req_slg_3_league_get_task_reward"));
        assertEquals(1, count(e.calls, "req_slg_3_league_like"));
        assertTrue(e.argsList.stream().anyMatch(a -> ((Number) a.getOrDefault("rank", 0)).longValue() == 1L));
    }
}
