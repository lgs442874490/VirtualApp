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
 * 探险碾压（2026-11-08）：begin 不传 is_fast，靠响应 is_fast=true 判定碾压通关；
 * 无法碾压（需手动战斗）达 fail_max 停止。
 */
public class AdventureCrushTest {

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
        final List<Boolean> fast = new ArrayList<>();   // 每次 begin 的 is_fast 值

        TestEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            if ("notify_adventure_pve_info".equals(proto)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("pass_checkpoint", 100);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("adventure_pve_info", p);
                return c;
            }
            if ("notify_battle_hero_arrange_data".equals(proto)) {
                Map<String, Object> squad = new LinkedHashMap<>();
                squad.put("fight_heros", List.of(50001, 50002, 50003, 50004, 50005));
                Map<String, Object> arr = new LinkedHashMap<>();
                arr.put("1", squad);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("hero_arranges", arr);
                return c;
            }
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
            if ("req_begin_adventure_pve".equals(proto)) {
                int n = 0;
                for (String x : calls) if ("req_begin_adventure_pve".equals(x)) n++;
                boolean f = n <= 1;   // 第 1 次碾压，第 2 次需手动
                fast.add(f);
                c.put("is_fast", f);
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

    private TaskContext tc(TestEngine e, int failMax) {
        JSONObject sub = new JSONObject();
        sub.put("crush", true);
        JSONObject strategy = new JSONObject();
        strategy.put("sweep", 5);
        JSONObject p = new JSONObject();
        p.put("sub", sub);
        p.put("strategy", strategy);
        p.put("fail_max", failMax);
        Map<String, JSONObject> params = new HashMap<>();
        params.put("adventure_sweep", p);
        return new TaskContext(e, "111", SID, state, params, "测试");
    }

    private static int count(List<String> calls, String proto) {
        int n = 0;
        for (String c : calls) if (proto.equals(c)) n++;
        return n;
    }

    @Test
    public void crushesOnceThenStopsAtFailLimit() {
        TestEngine e = new TestEngine();
        ServerFieldTasks.adventureSweep(tc(e, 1));

        assertEquals("碾压 1 关后无法碾压即停", 2, count(e.calls, "req_begin_adventure_pve"));
        assertEquals("无法碾压的关卡应补发 end 清理战斗态", 1, count(e.calls, "req_end_adventure_pve"));
        assertTrue("begin 请求不应传 is_fast", !e.argsList.get(0).containsKey("is_fast"));
    }

    @Test
    public void crushRequestOmitsIsFastFlag() {
        TestEngine e = new TestEngine();
        ServerFieldTasks.adventureSweep(tc(e, 5));
        for (Map<String, Object> a : e.argsList) {
            if (a.containsKey("checkpoint")) {
                assertTrue("请勿传 is_fast（碾压判定在响应里）", !a.containsKey("is_fast"));
            }
        }
    }
}
