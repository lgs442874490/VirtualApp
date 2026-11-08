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
        Map<String, Object> guessInfo;
        Map<String, Object> betInfo;

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            if ("notify_slg_3_league_info_init".equals(proto)) return initData;
            if ("notify_arsenal_league_guess_info".equals(proto)) return guessInfo;
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
            if ("req_arsenal_league_guess_get_winner_bet_info".equals(proto)) {
                c.put("infos", betInfo);
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
    public void arsenalBetWhenEnabled() {
        TestEngine e = new TestEngine();
        Map<String, Object> gi = new LinkedHashMap<>();
        gi.put("id", 2963001L);
        gi.put("group_id", 17L);
        e.guessInfo = gi;
        Map<String, Object> sides = new LinkedHashMap<>();
        sides.put("1", Map.of("power", 100L));
        sides.put("2", Map.of("power", 200L));
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("sides", sides);
        Map<String, Object> inf = new LinkedHashMap<>();
        inf.put("match_id", 1L);
        inf.put("match", match);
        Map<String, Object> infos = new LinkedHashMap<>();
        infos.put("1", inf);
        e.betInfo = infos;

        JSONObject p = new JSONObject();
        p.put("auto_bet", true);
        p.put("bet_amount", 4500L);
        p.put("bet_side", 0);
        ServerActivityTasks.arsenalLeague(tc(e, p));

        int idx = e.calls.indexOf("req_arsenal_league_guess_bet_winner");
        assertTrue("应发竞猜下注", idx >= 0);
        Map<String, Object> args = e.argsList.get(idx);
        assertEquals("按战力选强侧 2", 2, ((Number) args.get("side")).intValue());
        assertEquals(4500L, ((Number) args.get("num")).longValue());
    }

    @Test
    public void arsenalBetPicksTopThreeByPowerGapAtTenThousandCap() {
        TestEngine e = new TestEngine();
        Map<String, Object> gi = new LinkedHashMap<>();
        gi.put("id", 2963001L);
        gi.put("group_id", 17L);
        e.guessInfo = gi;
        // 4 场：差距 100 / 500 / 50 / 300 → 应选差最大的 3 场（500/300/100），每场 10000
        Map<String, Object> infos = new LinkedHashMap<>();
        long[][] rows = {{1L, 100L, 200L}, {2L, 100L, 600L}, {3L, 100L, 150L}, {4L, 100L, 400L}};
        for (long[] r : rows) {
            Map<String, Object> inf = new LinkedHashMap<>();
            inf.put("match_id", r[0]);
            inf.put("match", Map.of("sides", Map.of(
                    "1", Map.of("power", r[1]),
                    "2", Map.of("power", r[2]))));
            infos.put(String.valueOf(r[0]), inf);
        }
        e.betInfo = infos;

        JSONObject p = new JSONObject();
        p.put("auto_bet", true);   // 未配 bet_amount → 每场上限 10000
        ServerActivityTasks.arsenalLeague(tc(e, p));

        assertEquals("每场都拉满 10000，最多 3 场", 3, count(e.calls, "req_arsenal_league_guess_bet_winner"));
        java.util.List<Long> matchIds = new java.util.ArrayList<Long>();
        for (int i = 0; i < e.calls.size(); i++) {
            if ("req_arsenal_league_guess_bet_winner".equals(e.calls.get(i))) {
                Map<String, Object> args = e.argsList.get(i);
                matchIds.add(((Number) args.get("match_id")).longValue());
                assertEquals("每场上限 10000", 10000L, ((Number) args.get("num")).longValue());
            }
        }
        assertEquals("差距最大的三场（500/300/100）", java.util.Arrays.asList(2L, 4L, 1L), matchIds);
    }

    @Test
    public void arsenalBetIsIdempotent() {
        TestEngine e = new TestEngine();
        Map<String, Object> gi = new LinkedHashMap<>();
        gi.put("id", 2963001L);
        gi.put("group_id", 17L);
        e.guessInfo = gi;
        Map<String, Object> inf = new LinkedHashMap<>();
        inf.put("match_id", 1L);
        inf.put("match", Map.of("sides", Map.of("1", Map.of("power", 100L),
                "2", Map.of("power", 200L))));
        Map<String, Object> infos = new LinkedHashMap<>();
        infos.put("1", inf);
        e.betInfo = infos;

        JSONObject p = new JSONObject();
        p.put("auto_bet", true);
        p.put("bet_amount", 100L);
        TaskContext ctx = tc(e, p);
        ServerActivityTasks.arsenalLeague(ctx);
        ServerActivityTasks.arsenalLeague(ctx);   // 第二轮：应跳过下注

        assertEquals("两次运行只应下注一次", 1, count(e.calls, "req_arsenal_league_guess_bet_winner"));
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
