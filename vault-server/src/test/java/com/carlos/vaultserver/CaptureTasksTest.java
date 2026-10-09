package com.carlos.vaultserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 采集解析器单测：用合成 notify 形状驱动（引擎无关），断言 StateStore 键型与内容
 * 与 App 端 daily_state 期望一致。
 */
public class CaptureTasksTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore store;

    /** 最小引擎替身：只提供 notifyData/notifyContent/request 所需的预置内容。 */
    public static final class FakeEngine extends com.carlos.vault.wjdr.WjdrEngine {
        public final Map<String, Map<String, Object>> notifies = new HashMap<>();
        public final Map<String, com.carlos.vault.wjdr.protocol.GameMessage> responses = new HashMap<>();

        public FakeEngine() {
            super(null);
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            Map<String, Object> c = notifies.get(proto);
            if (c == null) return null;
            for (String k : new String[]{"data", "info", "content"}) {
                Object v = c.get(k);
                if (v instanceof Map) return (Map<String, Object>) v;
            }
            return c;
        }

        @Override
        public com.carlos.vault.wjdr.protocol.GameMessage request(String proto,
                Map<String, Object> args) {
            return responses.get(proto);
        }
    }

    private static com.carlos.vault.wjdr.protocol.GameMessage msg(Map<String, Object> content) {
        com.carlos.vault.wjdr.protocol.GameMessage m =
                new com.carlos.vault.wjdr.protocol.GameMessage();
        m.setContent(content);
        return m;
    }

    @Before
    public void setUp() {
        store = new StateStore(tmp.getRoot().toPath());
    }

    @Test
    public void questState_progressIntegralBoxes() {
        FakeEngine e = new FakeEngine();
        Map<String, Object> quests = new LinkedHashMap<>();
        Map<String, Object> q1 = new HashMap<>();
        q1.put("progress", 3L);
        quests.put("1320000", q1);
        Map<String, Object> data = new HashMap<>();
        data.put("quests", quests);
        data.put("finishs", java.util.Arrays.asList(1320010L));
        data.put("integral", 270L);
        e.notifies.put("notify_dailyquests", data);

        CaptureTasks.captureQuestState(e, store, "111", 913L);

        JSONObject prog = new JSONObject(store.getString("questprog:111:913"));
        assertEquals(3, prog.optInt("1320000"));
        JSONArray done = new JSONArray(store.getString("questdone:111:913"));
        assertEquals(1320010L, done.optLong(0));
        assertEquals(270L, ((Number) store.snapshot().get("act:111:913")).longValue());
        assertEquals(6L, ((Number) store.snapshot().get("boxes:111:913")).longValue());
        assertTrue(store.snapshot().get("questts:111:913") instanceof Number);
    }

    @Test
    public void calendar_familyNameAndText() {
        FakeEngine e = new FakeEngine();
        Map<String, Object> sched = new HashMap<>();
        long begin = System.currentTimeMillis() / 1000L + 3600;
        long end = begin + 7200;
        sched.put("begin_ts", begin);
        sched.put("end_ts", end);
        Map<String, Object> act = new HashMap<>();
        act.put("id", 2705001L);          // family 2705 → 烈焰与獠牙 / act_flamefangs
        act.put("schedule", java.util.Arrays.asList(sched));
        Map<String, Object> data = new HashMap<>();
        data.put("data", new HashMap<>(Map.of("2705001", act)));
        e.responses.put("req_activity_calendar_get_data", msg(data));

        CaptureTasks.captureCalendar(e, store, "111", 913L);

        JSONArray todos = new JSONArray(store.getString("todo:111:913"));
        assertEquals(1, todos.length());
        JSONObject t = todos.getJSONObject(0);
        assertEquals("烈焰与獠牙", t.getString("name"));
        assertEquals("act_flamefangs", t.getString("fn"));
        assertTrue(t.getString("text").contains("今日开启"));
        JSONObject mod = new JSONObject(store.getString("mod:111:913:act_flamefangs"));
        assertEquals(begin + " 时段已写入活动功能状态", true, mod.has("open"));
    }

    @Test
    public void supply_timesParsed() {
        FakeEngine e = new FakeEngine();
        long now = System.currentTimeMillis() / 1000L;
        Map<String, Object> data = new HashMap<>();
        // 键形规则（与 App 一致）：含 spec/end → kind2（专项）；含 time/ts 且不含 end/spec → kind1（在线可领）
        data.put("online_next_ts", now + 1800);
        data.put("spec_end", now + 5400);
        e.notifies.put("notify_recover_resource_init", data);

        CaptureTasks.captureSupplyState(e, store, "111", 913L);

        JSONObject mod = new JSONObject(store.getString("mod:111:913:supply"));
        assertTrue(mod.getString("online").startsWith("下一次可领"));
        assertEquals(now + 1800, mod.getLong("online_ts"));
        assertTrue(mod.getString("spec").contains("补给"));
    }

    @Test
    public void bear_reserveTimeNested() {
        FakeEngine e = new FakeEngine();
        Map<String, Object> trap = new HashMap<>();
        trap.put("reserve_time", 1791203400L);
        Map<String, Object> data = new HashMap<>();
        data.put("trap", trap);
        e.responses.put("req_alliance_trap_info", msg(data));

        CaptureTasks.bear(e, store, "111", 913L);

        JSONObject mod = new JSONObject(store.getString("mod:111:913:bear"));
        assertTrue(mod.getString("trap").startsWith("已预约"));
        assertEquals(1791203400L, mod.getLong("reserve"));
    }

    @Test
    public void rallyLines_collected() {
        FakeEngine e = new FakeEngine();
        Map<String, Object> rally = new HashMap<>();
        rally.put("type", 1L);
        rally.put("monsterid", 90001L);
        Map<String, Object> data = new HashMap<>();
        data.put("help_list_like_wrap", java.util.Arrays.asList(rally));
        e.notifies.put("notify_world_alliance_situation_init", data);

        CaptureTasks.saveRallies(e, store, "111", 913L);

        JSONArray arr = new JSONArray(store.getString("rallies:111:913"));
        assertEquals(1, arr.length());
        assertEquals(90001L, arr.getJSONObject(0).getLong("monsterid"));
    }
}
