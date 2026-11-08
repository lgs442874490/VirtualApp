package com.carlos.vaultserver.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.SessionControl;
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
 * 离线联动（2026-11-08 v2）：从战争/态势通知自动发现被攻击盟友 → 查 online →
 * force 开时按 uid 顶号上线。不再依赖 watch_uids 用户输入。
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
        Map<String, Object> situationInit;

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            if ("notify_world_alliance_situation_init".equals(proto)) return situationInit;
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

    private static final class FakeControl implements SessionControl {
        final List<String> calls = new ArrayList<>();

        @Override
        public void reconcile() {
        }

        @Override
        public boolean ensureSession(String fpid) {
            return true;
        }

        @Override
        public List<SessionControl.SessionStatus> status() {
            return new ArrayList<>();
        }

        @Override
        public boolean yield(String fpid, long probeMillis) {
            return true;
        }

        @Override
        public RoleOpResult roleActionByUid(String uid, String action) {
            calls.add(uid + ":" + action);
            RoleOpResult r = new RoleOpResult();
            r.accepted = true;
            r.code = 0;
            return r;
        }
    }

    private TaskContext tc(TestEngine e, JSONObject p) {
        Map<String, JSONObject> params = new HashMap<>();
        params.put("ally_offline_guard", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    private static Map<String, Object> situation(long targetUid) {
        Map<String, Object> atk = new LinkedHashMap<>();
        atk.put("targetid", targetUid);
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("atk_city", atk);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("1", one);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("situation_info", info);
        return c;
    }

    @Test
    public void autoDiscoversTargetFromSituation() {
        TestEngine e = new TestEngine();
        e.situationInit = situation(222L);
        JSONObject p = new JSONObject();
        p.put("sub", new JSONObject().put("watch", true).put("force", false));

        ServerActivityTasks.allyOfflineGuard(tc(e, p));

        int idx = e.calls.indexOf("req_players_base_info_with_permission");
        assertTrue("应从态势通知发现目标并查询", idx >= 0);
        assertTrue(((List<?>) e.argsList.get(idx).get("uids")).contains(222L));
    }

    @Test
    public void forceOnlineByUidWhenOffline() {
        TestEngine e = new TestEngine();
        e.situationInit = situation(222L);
        JSONObject p = new JSONObject();
        JSONObject sub = new JSONObject().put("watch", true).put("force", true);
        p.put("sub", sub);
        p.put("strategy", new JSONObject().put("force", "1"));
        FakeControl control = new FakeControl();
        TaskContext ctx = new TaskContext(e, "111", SID, state,
                Map.of("ally_offline_guard", p), "测试").withSessionControl(control);

        ServerActivityTasks.allyOfflineGuard(ctx);

        assertEquals("离线盟友应顶号上线", 1, control.calls.size());
        assertEquals("222:login", control.calls.get(0));
    }

    @Test
    public void skipsWithoutSituation() {
        TestEngine e = new TestEngine();
        JSONObject p = new JSONObject();
        p.put("sub", new JSONObject().put("watch", true).put("force", false));
        ServerActivityTasks.allyOfflineGuard(tc(e, p));
        assertFalse(e.calls.contains("req_players_base_info_with_permission"));
    }
}
