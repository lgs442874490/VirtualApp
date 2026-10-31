package com.carlos.vaultserver.task;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;
import com.carlos.vaultserver.StateStore;
import com.carlos.vaultserver.formation.FormationCatalog;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端回归（用户反馈 2026-10-31「情报今天一天没打过」）：
 *
 * <p>修复前根因：默认 {@code queue_count=1}，在途 **301 营救**被误计入普通队列占用 →
 * {@code remainingNormalSlots=0} → 真正“打”的 302 狩猎永远派不出去。
 * 本用例用假引擎把“1 队营救在途 + 1 条狩猎事件”喂进 {@code ServerFieldTasks.intel()}，
 * 断言最终真的发出了 {@code req_world_march type=302}，且编队来自情报默认
 * （最强盾 / 波根 / 吉娜），不是游戏第 1 队。
 *
 * <p>只回放 notify/response，不连真实协议。
 */
public class IntelDispatchRegressionTest {

    private static final long HERO_SHIELD = 50001L;   // 史密斯（盾）
    private static final long HERO_BORGAN = 50029L;   // 卢姆·波根（矛）
    private static final long HERO_GINA = 50008L;     // 吉娜（弓）

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeEngine engine;
    private StateStore state;

    @Before
    public void setUp() {
        engine = new FakeEngine();
        state = new StateStore(tmp.getRoot().toPath());
        engine.notifies.put("notify_hero_init", heroes());
        engine.notifies.put("notify_soldier_init", soldiers());
        FormationCatalog.reset(engine);
        FormationCatalog.capture(engine);
    }

    @After
    public void tearDown() {
        FormationCatalog.reset(engine);
    }

    @Test
    public void rescueInFlight_doesNotBlockHuntingDispatch() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        engine.notifies.put("notify_intelligence_info", map(
                "events", list(map(
                        "id", 7001L,
                        "event_id", 1001L,          // type=1 狩猎
                        "status", 1L,
                        "x", 120L,
                        "y", 340L,
                        "expire_ts", now + 5 * 3600L,
                        "played", false))));
        engine.notifies.put("notify_recover_resource_init", map(
                "data", map("201.0", map("id", 201L, "count", 500L, "max_count", 500L,
                        "recover", 1L, "next_calc_ts", now + 99999L, "interval", 300L))));
        // 在途 1 队 301 营救：旧实现会把它算进普通队列 → 狩猎被卡死
        engine.detailMarches = new ArrayList<>();
        engine.detailMarches.add(map("id", 9001L, "type", 301L, "state", 2L,
                "endtime", now + 300L));

        JSONObject intelParams = new JSONObject();
        intelParams.put("queue_count", 1);
        Map<String, JSONObject> params = new LinkedHashMap<>();
        params.put("intel", intelParams);
        TaskContext tc = new TaskContext(engine, "fpid", 888L, state, params, "test");

        TaskOutcome out = ServerFieldTasks.intel(tc);

        assertEquals("应在本轮真正派出狩猎", TaskOutcome.Category.CLAIMED, out.category);
        assertTrue("状态文案应包含派队", out.message.contains("派队1"));
        GameMessage march = engine.lastMarch;
        assertNotNull("必须发出 req_world_march", march);
        assertEquals("狩猎走 302（营救才是 301）",
                302L, Exec.longOf(engine.lastMarchArgs.get("type")));
        // 编队默认情报三英雄（盾/矛/弓），不是游戏第 1 队的空编队
        String formation = String.valueOf(engine.lastMarchArgs.get("formation"));
        assertTrue("编队应含波根：" + formation, formation.contains(String.valueOf(HERO_BORGAN)));
        assertTrue("编队应含吉娜：" + formation, formation.contains(String.valueOf(HERO_GINA)));
        assertTrue("编队应含最强盾：" + formation, formation.contains(String.valueOf(HERO_SHIELD)));
    }

    @Test
    public void playedExpiredEvent_isNotDispatched() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        engine.notifies.put("notify_intelligence_info", map(
                "events", list(map(
                        "id", 7002L,
                        "event_id", 1001L,
                        "status", 1L,
                        "x", 1L, "y", 2L,
                        "expire_ts", now - 10L,     // 已过期
                        "played", false))));
        engine.notifies.put("notify_recover_resource_init", map(
                "data", map("201.0", map("id", 201L, "count", 500L, "max_count", 500L,
                        "recover", 1L, "next_calc_ts", now + 99999L, "interval", 300L))));
        Map<String, JSONObject> params = new LinkedHashMap<>();
        params.put("intel", new JSONObject().put("queue_count", 1));
        TaskContext tc = new TaskContext(engine, "fpid", 888L, state, params, "test");

        TaskOutcome out = ServerFieldTasks.intel(tc);
        assertEquals("过期事件只 SKIP，不发请求", TaskOutcome.Category.SKIPPED, out.category);
        assertTrue("不应发出世界行军", engine.worldMarchRequests == 0);
    }

    // ==================== fixtures ====================

    private static Map<String, Object> heroes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("50001.0", hero(HERO_SHIELD, 80, 8, 9000));
        m.put("50029.0", hero(HERO_BORGAN, 75, 7, 5000));
        m.put("50008.0", hero(HERO_GINA, 70, 6, 4000));
        m.put("50022.0", hero(50022L, 90, 9, 9999));   // 更强弓兵：默认仍优先吉娜
        return m;
    }

    private static Map<String, Object> hero(long id, long lv, long star, long power) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("lv", lv);
        h.put("star", star);
        h.put("power", power);
        return h;
    }

    private static Map<String, Object> soldiers() {
        Map<String, Object> soldier = new LinkedHashMap<>();
        soldier.put("11000.0", map("id", 11000L, "count", 30000L));
        soldier.put("21000.0", map("id", 21000L, "count", 30000L));
        soldier.put("31000.0", map("id", 31000L, "count", 30000L));
        return map("soldier", soldier);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<>();
        for (Object o : items) out.add(o);
        return out;
    }

    /** 假引擎：只回放 notify 与行军查询/出征响应。 */
    private static final class FakeEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new LinkedHashMap<>();
        List<Map<String, Object>> detailMarches = new ArrayList<>();
        int worldMarchRequests = 0;
        GameMessage lastMarch;
        Map<String, Object> lastMarchArgs;

        FakeEngine() {
            super(null);
        }

        @Override
        public void collectNotifies(long millis) {
            // no-op
        }

        @Override
        public Map<String, Object> notifyContent(String proto) {
            return notifies.get(proto);
        }

        @Override
        public Map<String, Object> notifyData(String proto) {
            return notifies.get(proto);
        }

        @Override
        public String lastFpid() {
            return "fpid";
        }

        @Override
        public long lastServerId() {
            return 888L;
        }

        @Override
        public long lastUid() {
            return 123L;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            GameMessage msg = new GameMessage();
            msg.setProto(proto);
            if ("req_world_march_detail_4_player_situation".equals(proto)) {
                msg.setContent(map("code", 0L, "detail", map("marchs", detailMarches)));
                return msg;
            }
            if ("req_world_march".equals(proto)) {
                worldMarchRequests++;
                lastMarch = msg;
                lastMarchArgs = args;
                msg.setContent(map("code", 0L, "march_info", map("id", 999L)));
                return msg;
            }
            msg.setContent(map("code", 0L));
            return msg;
        }
    }
}
