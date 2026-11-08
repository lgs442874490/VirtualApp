package com.carlos.vaultserver.task;

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
 * 竞技场自动防守站位（2026-11-08）：req_arena_set_defence_heroes。
 * 覆盖：子项关闭不发请求；无英雄时跳过。
 */
public class ArenaFormationTest {

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

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return null;
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            return null;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            calls.add(proto);
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

    private TaskContext tc(TestEngine e, boolean formation) {
        JSONObject sub = new JSONObject();
        sub.put("formation", formation);
        JSONObject p = new JSONObject();
        p.put("sub", sub);
        Map<String, JSONObject> params = new HashMap<>();
        params.put("arena", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    @Test
    public void formationDisabledSkipsRequest() {
        TestEngine e = new TestEngine();
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.arenaSetFormation(tc(e, false), m);
        assertFalse(e.calls.contains("req_arena_set_defence_heroes"));
        assertTrue(m.subResults.getOrDefault("formation", "").contains("关闭"));
    }

    @Test
    public void formationWithoutHeroesSkips() {
        TestEngine e = new TestEngine();
        Exec.Multi m = new Exec.Multi();
        ServerTownTasks.arenaSetFormation(tc(e, true), m);
        assertFalse("无英雄时不应发请求", e.calls.contains("req_arena_set_defence_heroes"));
        assertTrue(m.subResults.getOrDefault("formation", "").contains("英雄不足"));
    }
}
