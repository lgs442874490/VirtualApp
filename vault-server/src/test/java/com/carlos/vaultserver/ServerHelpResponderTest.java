package com.carlos.vaultserver;

import com.carlos.vault.wjdr.WjdrEngine;
import com.carlos.vault.wjdr.protocol.GameMessage;

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
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 联盟互助常驻响应器单测（P0 2026-10-28，bug 4「不会自动给盟友请求点互助」加固）。
 *
 * <p>真实协议样本（tools/bundle_re/audit_1014/proto_log_dev_old.txt / proto_log.txt）：
 * <pre>
 * notify_alliance_help_init {"alliance_help":{"help_list":[{"uid":20778222,"qid":20001,
 *   "helpers":[23943109,...],"qtype":2,"endtime":1792218859,"max":51}],"day_glory":1700,"help_num":0}}
 * notify_alliance_help_update {"type":3,"data":[{"uid":...,"qid":...}]}   // 3=删除，1/2=增改
 * </pre>
 * 锁死：进度字段在 alliance_help 内层也能解析、owner 字段兼容、响应码 1536 要 settled
 * （旧实现无论响应码一律写“已响应 N 位”，且 1536 后仍每 30s 反复请求）。
 */
public class ServerHelpResponderTest {

    private static final String FPID = "111";
    private static final long SID = 913L;
    private static final long OWN_UID = 1001L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private StateStore state;

    @Before
    public void setUp() {
        state = new StateStore(tmp.getRoot().toPath());
    }

    /** 最小引擎替身：在线态/uid/通知/响应可注入（不连网）。 */
    private static final class TestEngine extends WjdrEngine {
        final Map<String, Map<String, Object>> notifies = new HashMap<>();
        final Map<String, GameMessage> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<BiConsumer<String, Map<String, Object>>> hooks = new ArrayList<>();
        boolean online = true;
        long uid = OWN_UID;

        TestEngine() {
            super(null);
        }

        @Override
        public void addMessageHook(BiConsumer<String, Map<String, Object>> hook) {
            hooks.add(hook);
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
        public String sessionState() {
            return online ? WjdrEngine.STATE_ONLINE : WjdrEngine.STATE_TAKEOVER;
        }

        @Override
        public long lastUid() {
            return uid;
        }

        @Override
        public GameMessage request(String proto, Map<String, Object> args) {
            requested.add(proto);
            return responses.get(proto);
        }

        /** 模拟推送到达（钩子线程）：驱动 dirty 标记。 */
        void push(String proto, Map<String, Object> content) {
            notifies.put(proto, content);
            for (BiConsumer<String, Map<String, Object>> h : hooks) h.accept(proto, content);
        }
    }

    private static GameMessage msg(long code, Map<String, Object> extra) {
        GameMessage m = new GameMessage();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", code);
        if (extra != null) content.putAll(extra);
        m.setContent(content);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 真实 init 形状：进度字段在 alliance_help 内层。 */
    private static Map<String, Object> initPush() {
        Map<String, Object> req = map("uid", 222L, "qid", 10001L, "helpers", List.of(),
                "endtime", 0L, "max", 5L);
        Map<String, Object> help = map("help_list", List.of(req), "day_glory", 1700L, "help_num", 3L);
        return map("alliance_help", help);
    }

    private String modText() {
        String raw = state.getString("mod:" + FPID + ":" + SID + ":alliance_help");
        return raw == null ? "" : new JSONObject(raw).optString("help_all", "");
    }

    // ==================== 推送解析（真实样本形状） ====================

    @Test
    public void progressFields_parsedFromNestedAllianceHelp() {
        Map<String, Object> content = initPush();
        assertEquals("init 的 help_num 在 alliance_help 内层", 3L,
                ServerHelpResponder.helpNumOf(content));
        assertEquals("init 的 day_glory 在 alliance_help 内层", 1700L,
                ServerHelpResponder.dayGloryOf(content));
        // 裸 help_list 形状（update_all）也要兼容
        Map<String, Object> bare = map("help_list", List.of(), "help_num", 7L, "day_glory", 200L);
        assertEquals(7L, ServerHelpResponder.helpNumOf(bare));
        assertEquals(200L, ServerHelpResponder.dayGloryOf(bare));
        // data 包装（部分协议）
        assertEquals(9L, ServerHelpResponder.helpNumOf(map("data", map("help_num", 9L))));
    }

    @Test
    public void parseHelpRequests_realShape() {
        Object list = map("alliance_help", map("help_list", List.of(
                map("uid", 20778222L, "qid", 20001L,
                        "helpers", List.of(23943109L, 23940407L), "endtime", 1792218859L, "max", 51L))));
        // 真实推送里 help_list 在 alliance_help 内层；服务端 applyPush 用 deepGet 取到后再解析
        List<ServerHelpResponder.HelpReq> parsed = ServerHelpResponder.parseHelpRequests(
                ((Map<?, ?>) ((Map<?, ?>) list).get("alliance_help")).get("help_list"));
        assertEquals(1, parsed.size());
        ServerHelpResponder.HelpReq r = parsed.get(0);
        assertEquals(20778222L, r.owner);
        assertEquals(20001L, r.qid);
        assertEquals(51L, r.max);
        assertFalse("自己没帮过但已过期 → 不可帮", r.availableFor(OWN_UID, 1792218860L));
        assertTrue("未过期/未满员/没帮过 → 可帮", r.availableFor(OWN_UID, 1792218858L));
        assertFalse("发起人自己不能帮自己", r.availableFor(20778222L, 1792218858L));
        assertFalse("未登录 uid=0 不发", r.availableFor(0L, 1792218858L));
    }

    @Test
    public void parseHelpRequests_ownerFieldFallbacks() {
        List<ServerHelpResponder.HelpReq> byUserId = ServerHelpResponder.parseHelpRequests(List.of(
                map("user_id", 300L, "qid", 1L, "max", 2L)));
        assertEquals(300L, byUserId.get(0).owner);
        assertEquals(300L, ServerHelpResponder.helpOwnerOf(map("userId", "300")));
        assertEquals(300L, ServerHelpResponder.helpOwnerOf(map("id", 300L)));
        assertEquals(300L, ServerHelpResponder.helpOwnerOf(map("uid", 300L)));
        assertEquals(0L, ServerHelpResponder.helpOwnerOf(map("nick", "x")));
        // qid 兼容 queue_id
        assertEquals(88L, ServerHelpResponder.helpQidOf(map("queue_id", 88L)));
    }

    @Test
    public void parseHelpRequests_helpersListAndMap() {
        List<ServerHelpResponder.HelpReq> listShape = ServerHelpResponder.parseHelpRequests(List.of(
                map("uid", 1L, "qid", 2L, "max", 5L, "helpers", List.of(10L, 11L))));
        assertEquals(2, listShape.get(0).helpers.size());

        List<ServerHelpResponder.HelpReq> mapShape = ServerHelpResponder.parseHelpRequests(List.of(
                map("uid", 1L, "qid", 2L, "max", 2L,
                        "helpers", map("1.0", 10L, "2.0", 11L))));
        assertTrue("helpers 为 Map 时同样能识别已帮成员", mapShape.get(0).helpers.contains(10L));
        assertEquals(2, mapShape.get(0).helpers.size());
        assertFalse("人数已满（max=2）→ 不可帮", mapShape.get(0).availableFor(OWN_UID, 0L));
    }

    @Test
    public void parseHelpRequests_singleAndEmptyShapes() {
        assertEquals(1, ServerHelpResponder.parseHelpRequests(
                map("uid", 5L, "qid", 6L, "max", 1L)).size());
        assertTrue(ServerHelpResponder.parseHelpRequests(null).isEmpty());
        assertTrue(ServerHelpResponder.parseHelpRequests(List.of()).isEmpty());
        assertTrue("缺 qid 的脏数据忽略",
                ServerHelpResponder.parseHelpRequests(List.of(map("uid", 5L))).isEmpty());
        assertTrue("缺 owner 的脏数据忽略",
                ServerHelpResponder.parseHelpRequests(List.of(map("qid", 5L))).isEmpty());
    }

    // ==================== 响应码语义 ====================

    @Test
    public void responseKind_mapping() {
        assertEquals(ServerHelpResponder.HELP_RSP_OK, ServerHelpResponder.helpResponseKind(0));
        assertEquals("1536=今日互助次数已用完 → settled",
                ServerHelpResponder.HELP_RSP_DAILY_LIMIT,
                ServerHelpResponder.helpResponseKind(1536));
        assertEquals(ServerHelpResponder.HELP_RSP_NO_REQUEST,
                ServerHelpResponder.helpResponseKind(75));
        assertEquals(ServerHelpResponder.HELP_RSP_NO_REQUEST,
                ServerHelpResponder.helpResponseKind(2));
        assertEquals(ServerHelpResponder.HELP_RSP_ALLIANCE,
                ServerHelpResponder.helpResponseKind(1510));
        assertEquals(ServerHelpResponder.HELP_RSP_ALLIANCE,
                ServerHelpResponder.helpResponseKind(1511));
        assertEquals(ServerHelpResponder.HELP_RSP_ALLIANCE,
                ServerHelpResponder.helpResponseKind(16873));
        assertEquals(ServerHelpResponder.HELP_RSP_UNKNOWN,
                ServerHelpResponder.helpResponseKind(999));
    }

    @Test
    public void blockMillis_byKind() {
        assertEquals(ServerHelpResponder.HELP_BLOCK_MILLIS,
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_DAILY_LIMIT));
        assertTrue("未知码要退避，避免 30s 节奏反复打",
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_UNKNOWN) > 0);
        assertTrue("联盟异常等待更久",
                ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_ALLIANCE)
                        >= ServerHelpResponder.HELP_BLOCK_MILLIS);
        assertEquals(0L, ServerHelpResponder.helpBlockMillis(ServerHelpResponder.HELP_RSP_OK));
    }

    @Test
    public void responseHelpers_codeAndHelpNum() {
        assertEquals(0, ServerHelpResponder.responseCode(msg(0, null)));
        assertEquals(1536, ServerHelpResponder.responseCode(msg(1536, null)));
        assertEquals(-1, ServerHelpResponder.responseCode(null));
        assertEquals(5L, ServerHelpResponder.responseHelpNum(
                msg(0, map("help_num", 5L))));
        assertEquals(4L, ServerHelpResponder.responseHelpNum(
                msg(0, map("alliance_help", map("help_num", 4L)))));
        assertEquals(3L, ServerHelpResponder.responseHelpNum(msg(0, map("count", 3L))));
        assertEquals(0L, ServerHelpResponder.responseHelpNum(null));
    }

    @Test
    public void settledDay_crossesBeijingMidnight() {
        long now = 1_800_000_000_000L;
        String today = ServerHelpResponder.beijingDay(now);
        assertTrue(ServerHelpResponder.helpSettledToday(today, now));
        assertFalse("跨天自动失效（明天继续帮）",
                ServerHelpResponder.helpSettledToday(today, now + 86_400_000L));
        assertFalse(ServerHelpResponder.helpSettledToday(null, now));
        assertFalse(ServerHelpResponder.helpSettledToday("", now));
    }

    @Test
    public void settled_persistsInStateStore() {
        long now = System.currentTimeMillis();
        assertFalse(ServerHelpResponder.helpSettled(state, FPID, SID, now));
        ServerHelpResponder.markHelpSettled(state, FPID, SID, now);
        assertTrue(ServerHelpResponder.helpSettled(state, FPID, SID, now));
        assertEquals(ServerHelpResponder.beijingDay(now),
                state.getString(ServerHelpResponder.helpSettledKey(FPID, SID)));
        // 另一角色（不同 sid）不受影响
        assertFalse(ServerHelpResponder.helpSettled(state, FPID, 888L, now));
    }

    // ==================== 会话线程 tick 集成（引擎替身） ====================

    @Test
    public void tick_successRespondsAndWritesState() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init", initPush());
        engine.responses.put("req_alliance_help_all", msg(0, map("help_num", 3L)));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        assertTrue(responder.tick(engine));

        assertEquals(1, engine.requested.size());
        assertEquals("req_alliance_help_all", engine.requested.get(0));
        assertTrue("成功文案（响应 help_num=3 优先）", modText().contains("已响应 3 位"));
        JSONObject mod = new JSONObject(state.getString("mod:" + FPID + ":" + SID + ":alliance_help"));
        assertEquals("进度字段来自 alliance_help 内层", 3, mod.optInt("help_num", 0));
        assertEquals(1700L, mod.optLong("day_glory", 0L));
    }

    @Test
    public void tick_noAvailableRequests_writesReadableState() {
        TestEngine engine = new TestEngine();
        // 只有自己发起的请求 → 没有可帮对象
        Map<String, Object> req = map("uid", OWN_UID, "qid", 1L, "helpers", List.of(),
                "endtime", 0L, "max", 5L);
        engine.push("notify_alliance_help_init",
                map("alliance_help", map("help_list", List.of(req))));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine);

        assertTrue("不发无意义请求", engine.requested.isEmpty());
        assertTrue(modText().contains("当前没有盟友需要互助"));
    }

    @Test
    public void tick_1536SettlesAndStopsRequestingAcrossRestart() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init", initPush());
        engine.responses.put("req_alliance_help_all", msg(1536, null));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine);

        assertEquals(1, engine.requested.size());
        assertTrue("1536 要写 settled 键",
                ServerHelpResponder.helpSettled(state, FPID, SID, System.currentTimeMillis()));
        assertTrue("1536 状态可读且不能写成功",
                modText().contains("1536") && modText().contains("今日联盟互助次数已用完"));

        // 模拟会话重启：新实例读持久化 settled，即使来了新请求也不再请求
        TestEngine engine2 = new TestEngine();
        Map<String, Object> newer = map("alliance_help", map("help_list", List.of(
                map("uid", 999L, "qid", 3L, "helpers", List.of(), "endtime", 0L, "max", 5L))));
        engine2.push("notify_alliance_help_init", newer);
        engine2.responses.put("req_alliance_help_all", msg(0, null));
        ServerHelpResponder responder2 = new ServerHelpResponder(FPID, SID, state);
        responder2.attach(engine2);
        responder2.tick(engine2);
        assertTrue("今日已结算，重启后也不重复请求", engine2.requested.isEmpty());
        assertTrue(modText().contains("1536"));
    }

    @Test
    public void tick_unknownCodeDoesNotClaimSuccess() {
        TestEngine engine = new TestEngine();
        engine.push("notify_alliance_help_init", initPush());
        engine.responses.put("req_alliance_help_all", msg(999, null));

        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        responder.tick(engine);

        assertEquals(1, engine.requested.size());
        assertFalse("未知码不能写成功", modText().contains("已响应"));
        assertTrue("未知码要有可读状态", modText().contains("999"));
        assertFalse(ServerHelpResponder.helpSettled(state, FPID, SID, System.currentTimeMillis()));
    }

    @Test
    public void tick_offlineDoesNothing() {
        TestEngine engine = new TestEngine();
        engine.online = false;
        engine.push("notify_alliance_help_init", initPush());
        engine.responses.put("req_alliance_help_all", msg(0, null));
        ServerHelpResponder responder = new ServerHelpResponder(FPID, SID, state);
        responder.attach(engine);
        assertFalse(responder.tick(engine));
        assertTrue(engine.requested.isEmpty());
    }
}
